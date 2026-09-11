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
#   5. the dispatch consumer reacts to it
#
# Step 5 currently expects a refusal, not a match: the Booking Service publishes
# booking facts with no customer coordinates or skill tags, so the Dispatch
# Engine deliberately dead-letters the event rather than scoring every candidate
# against latitude 0, longitude 0. See CODEBASE_REVIEW.md section 12.2.
#
# Usage:  bash docker/verify-outbox-flow.sh
set -uo pipefail

AUTH=http://localhost:8081
CATALOG=http://localhost:8085
BOOKING=http://localhost:8084
PG_CONTAINER="${PG_CONTAINER:-homefix-core-postgres-1}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-homefix-core-kafka-1}"
DISPATCH_CONTAINER="${DISPATCH_CONTAINER:-homefix-core-dispatch-engine-1}"
SMS_LOG="${SMS_LOG:-$(dirname "$0")/dev-sms/dev-sms.log}"

PASSED=0
FAILED=0

pass() { printf '  PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  FAIL  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; FAILED=$((FAILED + 1)); }

psql_q() {
  docker exec -i "${PG_CONTAINER}" psql -qAt -U homefix -d homefix -c "$1" 2>/dev/null | tr -d '\r'
}

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
if [ "${TABLES}" = "outbox_event,processed_event" ]; then
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
printf '\n== Booking a job as the test customer\n'
MOBILE="${MOBILE:-+919000000001}"
curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"${MOBILE}\"}" "${AUTH}/auth/register/otp"
sleep 1
OTP="$(grep -F "${MOBILE}" "${SMS_LOG}" 2>/dev/null \
  | grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}')"
SESSION="$(curl -s -X POST -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"${MOBILE}\",\"otp\":\"${OTP}\"}" "${AUTH}/auth/register/verify")"
TOKEN="$(printf '%s' "${SESSION}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')"

if [ -n "${TOKEN}" ] && [ "${TOKEN}" != "${SESSION}" ]; then
  pass 'authenticated as the test customer'
else
  fail 'authenticated as the test customer' "$(printf '%s' "${SESSION}" | head -c 160)"
  printf '\nCannot continue without a token.\n'
  exit 1
fi

AUTHZ="Authorization: Bearer ${TOKEN}"
JSON='Content-Type: application/json'

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
  -d "{\"categoryId\":\"${CATEGORY_ID}\",\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":false,\"scheduledAt\":\"${SCHEDULED}\",\"description\":\"Outbox verification run\"}" \
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
MESSAGES="$(docker exec "${KAFKA_CONTAINER}" timeout 15 \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic BookingCreated --from-beginning --timeout-ms 10000 2>/dev/null | grep -c 'bookingId')"
if [ "${MESSAGES:-0}" -gt 0 ]; then
  pass "BookingCreated topic carries ${MESSAGES} message(s)"
else
  fail 'BookingCreated topic carries messages' 'topic empty or unreachable'
fi

# ---------------------------------------------------------------- dispatch --
printf '\n== Dispatch reaction\n'
sleep 3
LOGS="$(docker logs --since 3m "${DISPATCH_CONTAINER}" 2>&1 | tail -200)"
if printf '%s' "${LOGS}" | grep -q 'carries no customer coordinates'; then
  pass 'dispatch refused the unenriched event with a diagnosable reason (expected today)'
  printf '        This is the documented partial fix: enrichment is not implemented yet.\n'
  printf '        See CODEBASE_REVIEW.md section 12.2.\n'
elif printf '%s' "${LOGS}" | grep -q 'Dispatching'; then
  pass 'dispatch accepted the event and began matching'
else
  fail 'dispatch reacted to the event' 'no matching log line; is the consumer running?'
fi

# ----------------------------------------------------------------- summary --
printf '\n----------------------------------------\n'
printf 'passed: %d   failed: %d\n' "${PASSED}" "${FAILED}"
[ "${FAILED}" -eq 0 ] || exit 1
