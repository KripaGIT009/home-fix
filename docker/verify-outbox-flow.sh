#!/usr/bin/env bash
# Verifies the transactional-outbox path end to end against the running local stack.
#
# This exists because the outbox was silently broken: every producer wrote its
# event rows into its own schema while the relay polled a different one, so no
# domain event was ever published and nothing downstream ever ran. A green
# unit-test suite did not catch it, because the defect lived in configuration
# and schema placement rather than in code. This script checks the real thing.
#
# What it asserts, in order:
#   1. the shared outbox schema exists and holds the outbox tables
#   2. confirming a booking writes a PENDING outbox row
#   3. the relay flips that row to PUBLISHED within a few seconds
#   4. the event actually lands on its Kafka topic
#   5. dispatch matches it: it resolves the address and skill tags, offers the job to the
#      seeded test provider, and the provider's acceptance moves the booking to
#      PROVIDER_ACCEPTED and publishes ProviderAccepted
#   6. the provider can run the job the way the provider app does, through the gateway:
#      the job detail carries the address, someone else's token cannot drive the booking,
#      on the way -> arrived (with a location the customer can read) -> before photo ->
#      start -> after photo -> complete, with the photo gates enforced
#   7. the customer pays for the completed job: the amount comes from the booking (a tampered
#      amount in the request is ignored), the local payment simulator settles it, and the
#      booking reaches PAYMENT_COMPLETED through the PaymentCompleted event, and the provider's
#      wallet is credited once
#
# Step 5 needs the seed data: docker/seed-catalog.sql and docker/seed-provider-profiles.sql
# (an approved provider based in Ara with the plumbing skill tag). The booking is placed
# inside that provider's service radius.
#
# Usage:  bash docker/verify-outbox-flow.sh
set -uo pipefail

# Git Bash on Windows rewrites an argument that looks like an absolute POSIX path into a
# Windows one, so the in-container "/opt/kafka/bin/..." below was handed to docker exec as
# "C:/Program Files/Git/opt/kafka/bin/...". The consumer never ran, its stderr went to
# /dev/null, and the topic check reported "topic empty or unreachable" against a topic that
# had the messages. Harmless on Linux and macOS, where the variable is simply unused.
export MSYS_NO_PATHCONV=1

AUTH=http://localhost:8081
CATALOG=http://localhost:8085
BOOKING=http://localhost:8084
CUSTOMER=http://localhost:8082
GATEWAY=http://localhost:8080
# psql_q / kafka_tool for the locally installed Postgres and Kafka.
. "$(dirname "$0")/local-infra.sh"
DISPATCH_CONTAINER="${DISPATCH_CONTAINER:-homefix-core-dispatch-engine-1}"
SMS_LOG="${SMS_LOG:-$(dirname "$0")/dev-sms/dev-sms.log}"

PASSED=0
FAILED=0

pass() { printf '  PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; FAILED=$((FAILED + 1)); }

printf 'HomeFix outbox flow verification\n\n'

# ---------------------------------------------------------------- schema ----
printf '== Shared outbox schema\n'
SCHEMA_COUNT="$(psql_q "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'outbox';")"
if [ "${SCHEMA_COUNT}" = "1" ]; then
  pass 'outbox schema exists'
else
  fail 'outbox schema exists' "found ${SCHEMA_COUNT}; did init-db.sql run on a fresh volume?"
fi

TABLES="$(psql_q "SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables WHERE table_schema = 'outbox';")"
# flyway_schema_history is Flyway's own bookkeeping for the shared outbox migration.
if [ "${TABLES}" = "flyway_schema_history,outbox_event,processed_event" ]; then
  pass 'outbox schema holds both tables'
else
  fail 'outbox schema holds both tables' "found: ${TABLES:-none}"
fi

# Nothing should be writing outbox rows anywhere else any more.
STRAY="$(psql_q "SELECT string_agg(table_schema, ',' ORDER BY table_schema) FROM information_schema.tables WHERE table_name = 'outbox_event' AND table_schema <> 'outbox';")"
if [ -z "${STRAY}" ]; then
  pass 'no per-service outbox tables remain'
else
  fail 'no per-service outbox tables remain' "also present in: ${STRAY}"
fi

# ------------------------------------------------------------------ login --
printf '\n== Booking a job as a fresh customer\n'
# A fresh number per run: every run saves an address, and a customer may hold only ten.
STAMP="$(date +%s)"
MOBILE="${MOBILE:-+9196${STAMP: -8}}"
curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"${MOBILE}\"}" "${AUTH}/auth/register/otp"
sleep 1
OTP="$(grep -F "${MOBILE}" "${SMS_LOG}" 2>/dev/null \
  | grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}')"
SESSION="$(curl -s -X POST -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"${MOBILE}\",\"otp\":\"${OTP}\"}" "${AUTH}/auth/register/verify")"
TOKEN="$(printf '%s' "${SESSION}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')"

if [ -n "${TOKEN}" ] && [ "${TOKEN}" != "${SESSION}" ]; then
  pass 'authenticated as a fresh customer'
else
  fail 'authenticated as a fresh customer' "$(printf '%s' "${SESSION}" | head -c 160)"
  printf '\nCannot continue without a token.\n'
  exit 1
fi

AUTHZ="Authorization: Bearer ${TOKEN}"
JSON='Content-Type: application/json'
CUSTOMER_ID="$(printf '%s' "${SESSION}" | sed 's/.*"userId":"\([^"]*\)".*/\1/')"

# Inside the seeded provider's service radius in Ara.
ADDRESS="$(curl -s -X POST -H "${AUTHZ}" -H "${JSON}" -d '{"label":"Home","lat":25.5571,"lng":84.6612}' \
  "${CUSTOMER}/customers/${CUSTOMER_ID}/addresses")"
ADDRESS_ID="$(printf '%s' "${ADDRESS}" | sed 's/.*"addressId":"\([^"]*\)".*/\1/')"
if [ -n "${ADDRESS_ID}" ] && [ "${ADDRESS_ID}" != "${ADDRESS}" ]; then
  pass 'saved a service address'
else
  fail 'saved a service address' "$(printf '%s' "${ADDRESS}" | head -c 200)"
  exit 1
fi

CATS="$(curl -s -H "${AUTHZ}" "${CATALOG}/catalog/categories")"
CATEGORY_ID="$(printf '%s' "${CATS}" | tr '{' '\n' | grep -m1 '"name":"Plumbing"' | sed 's/.*"id":"\([^"]*\)".*/\1/')"
SUBCATEGORY_ID="$(printf '%s' "${CATS}" | tr '{' '\n' | grep -m1 'Tap / Faucet Repair' | sed 's/.*"id":"\([^"]*\)".*/\1/')"

if [ -n "${SUBCATEGORY_ID}" ]; then
  pass 'resolved a bookable subcategory from the catalog'
else
  fail 'resolved a bookable subcategory from the catalog' 'is the catalog seeded?'
  exit 1
fi

BEFORE="$(psql_q "SELECT count(*) FROM outbox.outbox_event WHERE event_type = 'BookingCreated';")"

SCHEDULED="$(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+6H +%Y-%m-%dT%H:%M:%SZ)"
CREATED="$(curl -s -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"addressId\":\"${ADDRESS_ID}\",\"categoryId\":\"${CATEGORY_ID}\",\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":false,\"scheduledAt\":\"${SCHEDULED}\",\"description\":\"Outbox verification run\"}" \
  "${BOOKING}/bookings")"
REFERENCE="$(printf '%s' "${CREATED}" | sed 's/.*"reference":"\([^"]*\)".*/\1/')"

if [ -n "${REFERENCE}" ] && [ "${REFERENCE}" != "${CREATED}" ]; then
  pass "booking created (${REFERENCE})"
else
  fail 'booking created' "$(printf '%s' "${CREATED}" | head -c 200)"
  exit 1
fi

# Confirmation is what drives CREATED -> SEARCHING_PROVIDER and writes the event.
CONFIRMED="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "${AUTHZ}" -H "${JSON}" \
  "${BOOKING}/bookings/${REFERENCE}/confirmation")"
if [ "${CONFIRMED}" = "200" ]; then
  pass 'booking confirmed'
else
  fail 'booking confirmed' "HTTP ${CONFIRMED}"
fi

# ------------------------------------------------------------------ outbox --
printf '\n== Outbox row lifecycle\n'
AFTER="$(psql_q "SELECT count(*) FROM outbox.outbox_event WHERE event_type = 'BookingCreated';")"
if [ "${AFTER}" -gt "${BEFORE}" ]; then
  pass "BookingCreated row written (${BEFORE} -> ${AFTER})"
else
  fail 'BookingCreated row written' "count stayed at ${BEFORE}"
fi

# The relay polls once a second; give it a generous window.
STATUS=''
for _ in 1 2 3 4 5 6 7 8 9 10; do
  STATUS="$(psql_q "SELECT status FROM outbox.outbox_event WHERE event_type = 'BookingCreated' ORDER BY created_at DESC LIMIT 1;")"
  [ "${STATUS}" = "PUBLISHED" ] && break
  sleep 2
done

if [ "${STATUS}" = "PUBLISHED" ]; then
  pass 'relay published the row to Kafka'
else
  LAST_ERROR="$(psql_q "SELECT coalesce(last_error, '(none)') FROM outbox.outbox_event WHERE event_type = 'BookingCreated' ORDER BY created_at DESC LIMIT 1;")"
  fail 'relay published the row to Kafka' "status=${STATUS:-unknown} last_error=${LAST_ERROR}"
fi

FAILED_ROWS="$(psql_q "SELECT count(*) FROM outbox.outbox_event WHERE status = 'FAILED';")"
if [ "${FAILED_ROWS}" = "0" ]; then
  pass 'no outbox rows in FAILED'
else
  fail 'no outbox rows in FAILED' "${FAILED_ROWS} row(s) exhausted their retries"
fi

# ------------------------------------------------------------------- kafka --
printf '\n== Kafka delivery\n'
MESSAGES="$(kafka_tool kafka-console-consumer.sh \
  --topic BookingCreated --from-beginning --timeout-ms 10000 2>/dev/null | grep -c 'bookingId')"
if [ "${MESSAGES:-0}" -gt 0 ]; then
  pass "BookingCreated topic carries ${MESSAGES} message(s)"
else
  fail 'BookingCreated topic carries messages' 'topic empty or unreachable'
fi

# ---------------------------------------------------------------- dispatch --
printf '\n== Dispatch match and provider acceptance\n'
BOOKING_ID="$(psql_q "SELECT id FROM booking.booking WHERE reference = '${REFERENCE}';")"
PROVIDER_LOGIN="$(curl -s -X POST -H "${JSON}" \
  -d "{\"username\":\"${PROVIDER_USER:-provider}\",\"password\":\"${DEV_SEED_PASSWORD:-HomeFix@2026}\"}" \
  "${AUTH}/auth/login/password")"
PROVIDER_TOKEN="$(printf '%s' "${PROVIDER_LOGIN}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')"
if [ -z "${PROVIDER_TOKEN}" ] || [ "${PROVIDER_TOKEN}" = "${PROVIDER_LOGIN}" ]; then
  fail 'signed in as the seeded test provider' "$(printf '%s' "${PROVIDER_LOGIN}" | head -c 160)"
else
  pass 'signed in as the seeded test provider'
  PROVIDER_AUTHZ="Authorization: Bearer ${PROVIDER_TOKEN}"

  # Dispatch resolves the address and skill tags, queries eligible providers and records the
  # offer; the provider app polls this same endpoint through the gateway.
  # Up to 90 s: when the provider is still answering another booking's offer (the smoke
  # run's emergency booking, say), dispatch now waits up to one 60 s offer window for them.
  OFFERED=''
  for _ in $(seq 1 90); do
    if curl -s -H "${PROVIDER_AUTHZ}" "${GATEWAY}/dispatch/offers" | grep -q "${BOOKING_ID}"; then
      OFFERED=yes; break
    fi
    sleep 1
  done
  if [ -n "${OFFERED}" ]; then
    pass 'dispatch offered the job to the test provider'
    ACCEPT="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "${PROVIDER_AUTHZ}" \
      "${GATEWAY}/dispatch/offers/${BOOKING_ID}/accept")"
    if [ "${ACCEPT}" = "200" ]; then
      pass 'provider accepted the offer'
    else
      fail 'provider accepted the offer' "HTTP ${ACCEPT}"
    fi

    STATUS=''
    for _ in $(seq 1 20); do
      STATUS="$(psql_q "SELECT status FROM booking.booking WHERE id = '${BOOKING_ID}';")"
      [ "${STATUS}" = "PROVIDER_ACCEPTED" ] && break
      sleep 1
    done
    if [ "${STATUS}" = "PROVIDER_ACCEPTED" ]; then
      pass 'booking moved to PROVIDER_ACCEPTED'
    else
      fail 'booking moved to PROVIDER_ACCEPTED' "status: ${STATUS:-unknown}"
    fi
    ACCEPTED_EVENTS="$(psql_q "SELECT count(*) FROM outbox.outbox_event WHERE event_type = 'ProviderAccepted' AND aggregate_id = '${BOOKING_ID}';")"
    if [ "${ACCEPTED_EVENTS:-0}" -gt 0 ]; then
      pass 'ProviderAccepted written to the outbox'
    else
      fail 'ProviderAccepted written to the outbox' 'no row for this booking'
    fi
    [ "${STATUS}" = "PROVIDER_ACCEPTED" ] && JOB_READY=yes
  else
    fail 'dispatch offered the job to the test provider' \
      'no offer within 90 s; are seed-catalog.sql and seed-provider-profiles.sql applied?'
  fi
fi

# --------------------------------------------------------------------- job --
if [ "${JOB_READY:-}" = "yes" ]; then
  printf '
== Running the job as the provider app does
'
  BOOKINGS="${GATEWAY}/bookings/${BOOKING_ID}"

  # http_code <curl args...> -> just the status code.
  http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

  DETAIL="$(curl -s -H "${PROVIDER_AUTHZ}" "${BOOKINGS}")"
  if printf '%s' "${DETAIL}" | grep -q '"address":"Home"'       && printf '%s' "${DETAIL}" | grep -q '"coordinates":{"latitude":25.5571'; then
    pass 'job detail carries the service address and coordinates'
  else
    fail 'job detail carries the service address and coordinates' "$(printf '%s' "${DETAIL}" | head -c 240)"
  fi

  # The customer is a participant but not the provider: a job milestone must not be theirs.
  CODE="$(http_code -X POST -H "${AUTHZ}" "${BOOKINGS}/on-the-way")"
  if [ "${CODE}" = "404" ]; then
    pass "the customer cannot drive a provider milestone (HTTP ${CODE})"
  else
    fail 'the customer cannot drive a provider milestone' "expected 404, got HTTP ${CODE}"
  fi

  CODE="$(http_code -X POST -H "${PROVIDER_AUTHZ}" "${BOOKINGS}/on-the-way")"
  [ "${CODE}" = "200" ] && pass 'provider set off (by booking id)'     || fail 'provider set off (by booking id)' "HTTP ${CODE}"

  CODE="$(http_code -X POST -H "${PROVIDER_AUTHZ}" -H "${JSON}"     -d '{"latitude":25.5590,"longitude":84.6630}' "${GATEWAY}/locations/${BOOKING_ID}")"
  [ "${CODE}" = "202" ] && pass 'provider shared a location'     || fail 'provider shared a location' "HTTP ${CODE}"
  if curl -s -H "${AUTHZ}" "${GATEWAY}/locations/${BOOKING_ID}" | grep -q '"latitude":25.559'; then
    pass 'customer reads the live location'
  else
    fail 'customer reads the live location' 'no matching snapshot'
  fi
  CODE="$(http_code -X POST -H "${AUTHZ}" -H "${JSON}"     -d '{"latitude":1,"longitude":1}' "${GATEWAY}/locations/${BOOKING_ID}")"
  [ "${CODE}" = "403" ] && pass "a customer cannot post a provider location (HTTP ${CODE})"     || fail 'a customer cannot post a provider location' "expected 403, got HTTP ${CODE}"

  CODE="$(http_code -X POST -H "${PROVIDER_AUTHZ}" "${BOOKINGS}/arrived")"
  [ "${CODE}" = "200" ] && pass 'provider arrived' || fail 'provider arrived' "HTTP ${CODE}"

  CODE="$(http_code -X POST -H "${PROVIDER_AUTHZ}" "${BOOKINGS}/start")"
  case "${CODE}" in
    4*) pass "start is refused without a before-photo (HTTP ${CODE})" ;;
    *) fail 'start is refused without a before-photo' "HTTP ${CODE}" ;;
  esac

  # A 1x1 JPEG, enough for the content-type and size checks.
  PHOTO="$(mktemp)"
  printf '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=='     | base64 -d > "${PHOTO}" 2>/dev/null
  # curl on Windows is a native program and MSYS_NO_PATHCONV (set above) stops Git Bash
  # translating "/tmp/..." for it, so hand it the native path where cygpath exists.
  PHOTO_ARG="${PHOTO}"
  command -v cygpath >/dev/null 2>&1 && PHOTO_ARG="$(cygpath -w "${PHOTO}")"
  upload_photo() {
    http_code -X POST -H "${PROVIDER_AUTHZ}" -F "type=$1" -F "file=@${PHOTO_ARG};type=image/jpeg;filename=$1.jpg"       "${BOOKINGS}/photos"
  }
  CODE="$(upload_photo BEFORE_PHOTO)"
  [ "${CODE}" = "204" ] && pass 'before-photo uploaded' || fail 'before-photo uploaded' "HTTP ${CODE}"
  if curl -s -H "${PROVIDER_AUTHZ}" "${BOOKINGS}" | grep -q '"kind":"BEFORE"'; then
    pass 'job detail lists the before-photo (unlocks Start job)'
  else
    fail 'job detail lists the before-photo (unlocks Start job)' 'no BEFORE photo in the detail'
  fi

  CODE="$(http_code -X POST -H "${PROVIDER_AUTHZ}" "${BOOKINGS}/start")"
  [ "${CODE}" = "200" ] && pass 'job started' || fail 'job started' "HTTP ${CODE}"

  CODE="$(upload_photo AFTER_PHOTO)"
  [ "${CODE}" = "204" ] && pass 'after-photo uploaded' || fail 'after-photo uploaded' "HTTP ${CODE}"
  rm -f "${PHOTO}"

  CODE="$(http_code -X POST -H "${PROVIDER_AUTHZ}" "${BOOKINGS}/complete")"
  [ "${CODE}" = "200" ] && pass 'job completed' || fail 'job completed' "HTTP ${CODE}"
  if curl -s -H "${PROVIDER_AUTHZ}" "${BOOKINGS}" | grep -q '"netDurationSeconds":'; then
    pass 'completion summary has the net duration'
  else
    fail 'completion summary has the net duration' 'netDurationSeconds missing'
  fi

  printf '
== Paying for the job as the customer app does
'
  BOOKED_AMOUNT="$(psql_q "SELECT coalesce(final_total, estimated_total) FROM booking.booking WHERE id = '${BOOKING_ID}';")"
  # amount and providerId are deliberately wrong: the service must price from the booking.
  PAYMENT="$(curl -s -w '
%{http_code}' -X POST -H "${AUTHZ}" -H "${JSON}"     -d "{\"bookingId\":\"${BOOKING_ID}\",\"method\":\"UPI\",\"amount\":1.00,\"providerId\":\"${CUSTOMER_ID}\"}"     "${GATEWAY}/payments")"
  CODE="$(printf '%s' "${PAYMENT}" | tail -1)"
  [ "${CODE}" = "201" ] && pass 'payment initiated' || fail 'payment initiated' "HTTP ${CODE}: $(printf '%s' "${PAYMENT}" | head -1 | head -c 200)"
  PAID_AMOUNT="$(psql_q "SELECT amount FROM payment.payment_transaction WHERE booking_id = '${BOOKING_ID}' ORDER BY created_at DESC LIMIT 1;" 2>/dev/null)"
  if [ -n "${PAID_AMOUNT}" ] && [ "${PAID_AMOUNT}" = "${BOOKED_AMOUNT}" ]; then
    pass "charged the booking's amount (${PAID_AMOUNT}), not the client's"
  else
    fail "charged the booking's amount" "booking ${BOOKED_AMOUNT}, charged ${PAID_AMOUNT:-nothing}"
  fi

  STATUS=''
  for _ in $(seq 1 30); do
    STATUS="$(psql_q "SELECT status FROM booking.booking WHERE id = '${BOOKING_ID}';")"
    [ "${STATUS}" = "PAYMENT_COMPLETED" ] && break
    sleep 1
  done
  [ "${STATUS}" = "PAYMENT_COMPLETED" ] && pass 'booking moved to PAYMENT_COMPLETED'     || fail 'booking moved to PAYMENT_COMPLETED' "status: ${STATUS:-unknown}"
  PAID_EVENTS="$(psql_q "SELECT count(*) FROM outbox.outbox_event WHERE event_type = 'PaymentCompleted' AND payload LIKE '%${BOOKING_ID}%';")"
  [ "${PAID_EVENTS:-0}" -gt 0 ] && pass 'PaymentCompleted written to the outbox'     || fail 'PaymentCompleted written to the outbox' 'no row for this booking'

  CREDITED=''
  for _ in $(seq 1 15); do
    CREDITED="$(psql_q "SELECT net FROM provider.provider_earning WHERE booking_id = '${BOOKING_ID}' AND type = 'JOB_CREDIT';")"
    [ -n "${CREDITED}" ] && break
    sleep 1
  done
  [ -n "${CREDITED}" ] && pass "provider's wallet credited (net ${CREDITED})"     || fail "provider's wallet credited" 'no JOB_CREDIT earning for this booking'

  CODE="$(http_code -X POST -H "${AUTHZ}" -H "${JSON}" -d "{\"bookingId\":\"${BOOKING_ID}\",\"method\":\"UPI\"}" "${GATEWAY}/payments")"
  case "${CODE}" in
    200|201) pass "paying again returns the existing payment (HTTP ${CODE})" ;;
    *) fail 'paying again returns the existing payment' "HTTP ${CODE}" ;;
  esac
fi

# ----------------------------------------------------------------- summary --
printf '\n----------------------------------------\n'
printf 'passed: %d   failed: %d\n' "${PASSED}" "${FAILED}"
[ "${FAILED}" -eq 0 ] || exit 1
