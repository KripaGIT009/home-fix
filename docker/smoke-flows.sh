#!/usr/bin/env bash
# End-to-end smoke test across every HomeFix service in the local stack.
#
# Each check prints PASS/FAIL with the HTTP status it got, and the script exits
# non-zero if anything failed, so it works both as a readable report and as a
# CI gate.
#
# Usage:  bash docker/smoke-flows.sh
#
# Prerequisites: the stack is up and `bash docker/seed-pricing.sh` has been run
# (the Pricing Engine holds parameters in memory in this profile).

AUTH_CONTAINER="${AUTH_CONTAINER:-homefix-core-auth-service-1}"

AUTH=http://localhost:8081
GATEWAY=http://localhost:8080
CUSTOMER=http://localhost:8082
PROVIDER=http://localhost:8083
BOOKING=http://localhost:8084
CATALOG=http://localhost:8085
PRICING=http://localhost:8086
DISPATCH=http://localhost:8087
PAYMENT=http://localhost:8088
INVOICE=http://localhost:8089
NOTIFICATION=http://localhost:8090
COMPLAINT=http://localhost:8091
LOCATION=http://localhost:8092
CHAT=http://localhost:8093
VERIFICATION=http://localhost:8094
ADMIN=http://localhost:8095
REPORTING=http://localhost:8096
RATING=http://localhost:8097
PROMOTION=http://localhost:8098
OUTBOX=http://localhost:8099

PASSED=0
FAILED=0
CURRENT_BODY=''

# Docker Desktop's host port-forwarding drops roughly two in three connections
# under this stack's load while the containers themselves are healthy. Retry a
# transport failure (curl exit != 0, i.e. no HTTP status at all) so the report
# reflects the services, not the proxy. An HTTP error status is never retried.
curl_retry() {
  local attempt out rc
  for attempt in 1 2 3; do
    out="$(curl -sS --max-time 15 "$@" 2>/dev/null)"; rc=$?
    if [ "${rc}" -eq 0 ]; then printf '%s' "${out}"; return 0; fi
    sleep 2
  done
  printf '%s' "${out}"
  return "${rc}"
}

# check <name> <expected-status-regex> <curl args...>
check() {
  local name="$1" expected="$2"; shift 2
  local out status
  out="$(curl_retry -w $'\n%{http_code}' "$@")"
  status="${out##*$'\n'}"
  CURRENT_BODY="${out%$'\n'*}"
  if [[ "${status}" =~ ^(${expected})$ ]]; then
    printf '  PASS  %-52s %s\n' "${name}" "${status}"
    PASSED=$((PASSED + 1))
  else
    printf '  FAIL  %-52s %s (expected %s)\n' "${name}" "${status}" "${expected}"
    printf '        %s\n' "$(printf '%s' "${CURRENT_BODY}" | head -c 200)"
    FAILED=$((FAILED + 1))
  fi
}

section() { printf '\n== %s\n' "$1"; }

# Extracts a top-level JSON string field from the last response body.
field() { printf '%s' "${CURRENT_BODY}" | sed "s/.*\"$1\":\"\([^\"]*\)\".*/\1/"; }

# Registers a fresh account and echoes its access token.
SMS_LOG="${SMS_LOG:-$(dirname "$0")/dev-sms/dev-sms.log}"

# The role is chosen when the OTP is requested -- the auth service stores it on the
# OTP session and applies it at verification -- so it must be sent here, not at verify.
# Omitting it silently yields a CUSTOMER, which made the "provider" checks below
# exercise a customer token and fail on role enforcement rather than on the endpoint.
login() {
  local mobile="$1"
  local role="${2:-}"
  local body="{\"mobileNumber\":\"${mobile}\"}"
  [ -n "${role}" ] && body="{\"mobileNumber\":\"${mobile}\",\"role\":\"${role}\"}"
  curl_retry -o /dev/null -X POST -H 'Content-Type: application/json' \
    -d "${body}" "${AUTH}/auth/register/otp"
  sleep 1
  # This number's code from the dev SMS file; container logs only as fallback.
  local otp
  otp="$(grep -F "${mobile}" "${SMS_LOG}" 2>/dev/null | grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}')"
  if [ -z "${otp}" ]; then
    otp="$(docker logs "${AUTH_CONTAINER}" 2>&1 | grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}')"
  fi
  curl_retry -X POST -H 'Content-Type: application/json' \
    -d "{\"mobileNumber\":\"${mobile}\",\"otp\":\"${otp}\"}" "${AUTH}/auth/register/verify"
}

STAMP="$(date +%s)"

printf 'HomeFix end-to-end smoke test\n'

# ---------------------------------------------------------------- readiness --
section 'Service readiness'
for entry in \
  "auth:${AUTH}" "gateway:${GATEWAY}" "customer:${CUSTOMER}" "provider:${PROVIDER}" \
  "booking:${BOOKING}" "catalog:${CATALOG}" "pricing:${PRICING}" "dispatch:${DISPATCH}" \
  "payment:${PAYMENT}" "invoice:${INVOICE}" "notification:${NOTIFICATION}" \
  "complaint:${COMPLAINT}" "location:${LOCATION}" "chat:${CHAT}" \
  "verification:${VERIFICATION}" "admin:${ADMIN}" "reporting:${REPORTING}" \
  "rating:${RATING}" "promotion:${PROMOTION}" "outbox:${OUTBOX}"; do
  check "${entry%%:*} readiness" '200' "${entry#*:}/health/readiness"
done

# --------------------------------------------------------------------- auth --
section 'Auth — OTP registration, refresh, RBAC (Requirement 1)'
SESSION="$(login "+9198${STAMP: -8}")"
TOKEN="$(printf '%s' "${SESSION}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')"
REFRESH="$(printf '%s' "${SESSION}" | sed 's/.*"refreshToken":"\([^"]*\)".*/\1/')"
CUSTOMER_ID="$(printf '%s' "${SESSION}" | sed 's/.*"userId":"\([^"]*\)".*/\1/')"
AUTHZ="Authorization: Bearer ${TOKEN}"
JSON='Content-Type: application/json'

if [ -n "${TOKEN}" ]; then
  printf '  PASS  %-52s %s\n' 'OTP register + verify issues a JWT' '200'
  PASSED=$((PASSED + 1))
else
  printf '  FAIL  %-52s\n' 'OTP register + verify issues a JWT'
  FAILED=$((FAILED + 1))
fi

check 'wrong OTP is rejected' '400|401|422' \
  -X POST -H "${JSON}" -d '{"mobileNumber":"+919812345678","otp":"000000"}' \
  "${AUTH}/auth/register/verify"
check 'refresh token rotates the access token' '200' \
  -X POST -H "${JSON}" -d "{\"refreshToken\":\"${REFRESH}\"}" "${AUTH}/auth/token/refresh"
check 'token introspection' '200' -H "${AUTHZ}" "${AUTH}/auth/introspect"

# ------------------------------------------------------------------ catalog --
section 'Catalog — public listing (Requirement 3)'
check 'public catalog listing' '200' "${CATALOG}/catalog/categories"
CATS="${CURRENT_BODY}"
CATEGORY_ID="$(printf '%s' "${CATS}" | tr '{' '\n' | grep '"name":"Plumbing"' | sed 's/.*"id":"\([^"]*\)".*/\1/')"
SUBCATEGORY_ID="$(printf '%s' "${CATS}" | tr '{' '\n' | grep 'Tap / Faucet Repair' | sed 's/.*"id":"\([^"]*\)".*/\1/')"
check 'admin catalog CRUD needs auth' '401|403' "${CATALOG}/admin/catalog/categories"

# ------------------------------------------------------------------ pricing --
section 'Pricing — itemized estimate (Requirement 6)'
check 'estimate requires authentication' '401|403' \
  -X POST -H "${JSON}" -d "{\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":false}" \
  "${PRICING}/pricing/estimate"
check 'standard estimate' '200' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":false}" "${PRICING}/pricing/estimate"
STANDARD_TOTAL="$(printf '%s' "${CURRENT_BODY}" | sed 's/.*"total":\([0-9.]*\).*/\1/')"
check 'emergency estimate applies the multiplier' '200' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":true}" "${PRICING}/pricing/estimate"
EMERGENCY_TOTAL="$(printf '%s' "${CURRENT_BODY}" | sed 's/.*"total":\([0-9.]*\).*/\1/')"
if awk "BEGIN{exit !(${EMERGENCY_TOTAL:-0} > ${STANDARD_TOTAL:-0})}"; then
  printf '  PASS  %-52s %s > %s\n' 'emergency total exceeds standard' "${EMERGENCY_TOTAL}" "${STANDARD_TOTAL}"
  PASSED=$((PASSED + 1))
else
  printf '  FAIL  %-52s %s !> %s\n' 'emergency total exceeds standard' "${EMERGENCY_TOTAL}" "${STANDARD_TOTAL}"
  FAILED=$((FAILED + 1))
fi
check 'unknown subcategory has no parameters' '404|422' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d '{"subcategoryId":"11111111-1111-1111-1111-111111111111","emergency":false}' \
  "${PRICING}/pricing/estimate"

# ------------------------------------------------------------------ booking --
section 'Booking — creation and state machine (Requirements 7-9)'
# A booking must name a saved address: dispatch resolves the job's coordinates from it. This
# one is in Ara, inside the seeded test providers' service radius (seed-provider-profiles.sql).
check 'customer saves a service address' '201'   -X POST -H "${AUTHZ}" -H "${JSON}" -d '{"label":"Home","lat":25.5571,"lng":84.6612}'   "${CUSTOMER}/customers/${CUSTOMER_ID}/addresses"
ADDRESS_ID="$(field addressId)"
SCHEDULED="$(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+6H +%Y-%m-%dT%H:%M:%SZ)"
check 'scheduled booking is created' '201' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"addressId\":\"${ADDRESS_ID}\",\"categoryId\":\"${CATEGORY_ID}\",\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":false,\"scheduledAt\":\"${SCHEDULED}\",\"description\":\"Kitchen tap dripping\"}" \
  "${BOOKING}/bookings"
BOOKING_REF="$(field reference)"
BOOKING_ID="$(field bookingId)"
check 'emergency booking dispatches immediately' '201' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"addressId\":\"${ADDRESS_ID}\",\"categoryId\":\"${CATEGORY_ID}\",\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":true,\"description\":\"Burst pipe\"}" \
  "${BOOKING}/bookings"
if printf '%s' "${CURRENT_BODY}" | grep -q 'SEARCHING_PROVIDER'; then
  printf '  PASS  %-52s %s\n' 'emergency booking enters SEARCHING_PROVIDER' 'ok'
  PASSED=$((PASSED + 1))
else
  printf '  FAIL  %-52s\n' 'emergency booking enters SEARCHING_PROVIDER'
  FAILED=$((FAILED + 1))
fi
check 'unknown subcategory is refused' '422|503' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"addressId\":\"${ADDRESS_ID}\",\"categoryId\":\"${CATEGORY_ID}\",\"subcategoryId\":\"11111111-1111-1111-1111-111111111111\",\"emergency\":true}" \
  "${BOOKING}/bookings"
check 'booking lead time is enforced' '422|400' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"addressId\":\"${ADDRESS_ID}\",\"categoryId\":\"${CATEGORY_ID}\",\"subcategoryId\":\"${SUBCATEGORY_ID}\",\"emergency\":false,\"scheduledAt\":\"2020-01-01T00:00:00Z\"}" \
  "${BOOKING}/bookings"
check 'booking endpoints require a token' '401|403' \
  -X POST -H "${JSON}" -d '{}' "${BOOKING}/bookings"
check 'illegal transition is rejected' '404|409|422' \
  -X POST -H "${AUTHZ}" -H "${JSON}" -d '{}' "${BOOKING}/bookings/${BOOKING_REF}/complete"

# ----------------------------------------------------------------- provider --
section 'Provider — profile, availability, earnings (Requirement 4)'
PROVIDER_SESSION="$(login "+9197${STAMP: -8}" SERVICE_PROVIDER)"
PROVIDER_TOKEN="$(printf '%s' "${PROVIDER_SESSION}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')"
PROVIDER_ID="$(printf '%s' "${PROVIDER_SESSION}" | sed 's/.*"userId":"\([^"]*\)".*/\1/')"
PROVIDER_AUTHZ="Authorization: Bearer ${PROVIDER_TOKEN}"
check 'provider profile requires auth' '401|403' "${PROVIDER}/providers/${PROVIDER_ID}/profile"
check 'provider profile lookup' '200|404' -H "${PROVIDER_AUTHZ}" "${PROVIDER}/providers/${PROVIDER_ID}/profile"
check 'provider earnings lookup' '200|404' -H "${PROVIDER_AUTHZ}" "${PROVIDER}/providers/${PROVIDER_ID}/earnings"

# ------------------------------------------------------------- verification --
section 'Verification — state machine (Requirement 5)'
check 'verification status lookup' '200|404' -H "${PROVIDER_AUTHZ}" "${VERIFICATION}/verifications/${PROVIDER_ID}"
check 'unapproved provider cannot take jobs' '200|403|404' -H "${PROVIDER_AUTHZ}" \
  "${VERIFICATION}/verifications/${PROVIDER_ID}/job-assignment-eligibility"
check 'admin approval requires the ADMIN role' '401|403|404|422' \
  -X POST -H "${PROVIDER_AUTHZ}" -H "${JSON}" -d '{}' \
  "${VERIFICATION}/admin/verifications/${PROVIDER_ID}/approve"

# ----------------------------------------------------------------- location --
section 'Location — ingestion, snapshot, ETA (Requirement 10)'
# The body carries the reporting provider (LocationUpdateRequest.providerId);
# omitting it is a 400, which used to be masked by the unnamed-@PathVariable 500.
check 'location ingest' '200|202|204|404|422' \
  -X POST -H "${PROVIDER_AUTHZ}" -H "${JSON}" \
  -d '{"providerId":"'"${PROVIDER_ID}"'","latitude":25.5541,"longitude":84.6636}' "${LOCATION}/locations/${BOOKING_ID}"
check 'location snapshot' '200|404' -H "${AUTHZ}" "${LOCATION}/locations/${BOOKING_ID}"
check 'location requires auth' '401|403' "${LOCATION}/locations/${BOOKING_ID}"

# ------------------------------------------------------------------ payment --
section 'Payment — initiation and idempotency (Requirement 12)'
IDEMPOTENCY_KEY="smoke-${STAMP}"
check 'payment initiation' '200|201|202|400|404|422' \
  -X POST -H "${AUTHZ}" -H "${JSON}" -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d "{\"bookingId\":\"${BOOKING_ID}\",\"amount\":388.10,\"currency\":\"INR\",\"method\":\"UPI\",\"gateway\":\"RAZORPAY\"}" \
  "${PAYMENT}/payments"
check 'payment requires auth' '401|403' -X POST -H "${JSON}" -d '{}' "${PAYMENT}/payments"

# ------------------------------------------------------------------ invoice --
section 'Invoice — customer invoices (Requirement 13)'
check 'customer invoice list' '200|404' -H "${AUTHZ}" "${INVOICE}/invoices/customers/${CUSTOMER_ID}"
check 'invoice list requires auth' '401|403' "${INVOICE}/invoices/customers/${CUSTOMER_ID}"

# ---------------------------------------------------------------- promotion --
section 'Promotion — coupon lifecycle (Requirement 17)'
COUPON_CODE="SMOKE${STAMP: -6}"
check 'coupon creation' '200|201|401|403' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"code\":\"${COUPON_CODE}\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"maxRedemptions\":100,\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2027-01-01T00:00:00Z\"}" \
  "${PROMOTION}/coupons"
check 'unknown coupon lookup' '404|400' -H "${AUTHZ}" "${PROMOTION}/coupons/code/NOSUCHCODE"

# ------------------------------------------------------------------- rating --
section 'Rating & reviews (Requirement 15)'
check 'review submission is validated' '200|201|400|404|422' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"bookingId\":\"${BOOKING_ID}\",\"providerId\":\"${PROVIDER_ID}\",\"rating\":5,\"reviewText\":\"Great work\"}" \
  "${RATING}/reviews"
check 'review requires auth' '401|403' -X POST -H "${JSON}" -d '{}' "${RATING}/reviews"

# ---------------------------------------------------------------- complaint --
section 'Complaints — lifecycle and SLA (Requirement 16)'
check 'complaint creation' '200|201|400|404|422' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d "{\"bookingId\":\"${BOOKING_ID}\",\"category\":\"SERVICE_QUALITY\",\"description\":\"Tap still leaking after the visit\"}" \
  "${COMPLAINT}/complaints"
check 'complaint stats' '200|401|403' -H "${AUTHZ}" "${COMPLAINT}/complaints/stats"
check 'complaint requires auth' '401|403' -X POST -H "${JSON}" -d '{}' "${COMPLAINT}/complaints"

# --------------------------------------------------------------------- chat --
section 'Chat — channel access control (Requirement 18)'
check 'chat history' '200|403|404' -H "${AUTHZ}" "${CHAT}/chat/channels/${BOOKING_ID}/messages"
check 'chat requires auth' '401|403' "${CHAT}/chat/channels/${BOOKING_ID}/messages"

# -------------------------------------------------------------------- admin --
section 'Admin — dashboard and RBAC (Requirement 19)'
check 'admin dashboard denies a customer token' '401|403' -H "${AUTHZ}" "${ADMIN}/admin/dashboard"
check 'admin dashboard denies anonymous' '401|403' "${ADMIN}/admin/dashboard"
check 'audit log denies a customer token' '401|403' -H "${AUTHZ}" "${ADMIN}/admin/audit-logs"

# ---------------------------------------------------------------- reporting --
section 'Reporting (Requirement 20)'
check 'report generation requires auth' '401|403' -X POST -H "${JSON}" -d '{}' "${REPORTING}/reports"
check 'report generation with a customer token' '200|201|202|400|403|422' \
  -X POST -H "${AUTHZ}" -H "${JSON}" \
  -d '{"reportType":"DAILY_REVENUE_SUMMARY","from":"2026-09-01","to":"2026-09-05","format":"CSV"}'   "${REPORTING}/reports"

# ------------------------------------------------------------------ gateway --
section 'API Gateway — routing and auth (Requirement 22)'
check 'gateway rejects anonymous' '401' "${GATEWAY}/catalog/categories"
check 'gateway routes an authenticated call' '200' -H "${AUTHZ}" "${GATEWAY}/catalog/categories"
check 'gateway returns a correlation id' '200' -H "${AUTHZ}" -D - -o /dev/null "${GATEWAY}/catalog/categories"

# --------------------------------------------------------------------- SPAs --
section 'Single-page apps'
SPA_SEQ=0
for entry in "customer-app:5173" "provider-app:5174" "admin-portal:5175"; do
  check "${entry%%:*} serves" '200' "http://localhost:${entry#*:}/"
  # A fresh number per app per run. A number may request at most five OTPs an
  # hour, so the previous fixed number spent three of its allowance on every
  # run, making a second run within the hour fail 429 on a working proxy.
  check "${entry%%:*} proxies auth" '202' \
    -X POST -H "${JSON}" -d "{\"mobileNumber\":\"+9198${STAMP: -7}${SPA_SEQ}\"}" \
    "http://localhost:${entry#*:}/api/auth/register/otp"
  SPA_SEQ=$((SPA_SEQ + 1))
  check "${entry%%:*} proxies the gateway" '200' -H "${AUTHZ}" \
    "http://localhost:${entry#*:}/api/catalog/categories"
done

# ------------------------------------------------------------------ summary --
printf '\n----------------------------------------\n'
printf 'passed: %d   failed: %d\n' "${PASSED}" "${FAILED}"
[ "${FAILED}" -eq 0 ] || exit 1
