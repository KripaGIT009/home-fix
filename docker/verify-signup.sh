#!/usr/bin/env bash
# End-to-end check of email sign-up, email sign-in, password reset, staff invitations and agency
# applications on the local stack (email-auth spec, Requirement 9.3).
#
# Codes and invitation links are read from the Dev_Mail_Log (docker/dev-mail/dev-mail.log,
# EMAIL_PROVIDER=file) and the super admin signs in by OTP read from docker/dev-sms/dev-sms.log, so
# no password from .env is needed. Every account it creates uses a fresh, unique email and number.
# The agency it applies for and approves sits at 0°N 0°E with a 1 km radius, in open sea, so it
# never covers a real booking.
#
# Usage:  bash docker/verify-signup.sh
#
# Takes a little over a minute: a code can be sent to one address at most once a minute, and the
# password reset is requested for the address that just signed up.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CUSTOMER_APP="${CUSTOMER_APP:-http://localhost:5173/api}"
PROVIDER_APP="${PROVIDER_APP:-http://localhost:5174/api}"
GATEWAY="${GATEWAY:-http://localhost:8080}"
AUTH="${AUTH:-http://localhost:8081}"
MAIL_LOG="${MAIL_LOG:-${ROOT}/dev-mail/dev-mail.log}"
SMS_LOG="${SMS_LOG:-${ROOT}/dev-sms/dev-sms.log}"
SUPER_ADMIN_MOBILE="${SUPER_ADMIN_MOBILE:-+919000000022}"

PASSED=0
FAILED=0
STATUS=''
BODY=''

RUN="$(date +%s)"
DIGITS="$(printf '%08d' $((RUN % 100000000)))"
CUSTOMER_EMAIL="cust-${RUN}@example.test"
PROVIDER_EMAIL="pro-${RUN}@example.test"
STAFF_EMAIL="staff-${RUN}@example.test"
CUSTOMER_MOBILE="+9170${DIGITS}"
PROVIDER_MOBILE="+9171${DIGITS}"
STAFF_MOBILE="+9172${DIGITS}"
PASSWORD="Homefix${RUN: -4}a"
NEW_PASSWORD="Reset${RUN: -4}b9"

# --- helpers ---------------------------------------------------------------------------------

# request METHOD URL [JSON] [TOKEN] — sets STATUS and BODY. Retries connection failures, which
# Docker Desktop's port forwarding produces under load (never a 4xx/5xx answer).
request() {
  local method="$1" url="$2" data="${3:-}" token="${4:-}"
  local args=(-sS -o /tmp/verify-signup.body -w '%{http_code}' --max-time 30 -X "${method}"
              -H 'Content-Type: application/json')
  [ -n "${token}" ] && args+=(-H "Authorization: Bearer ${token}")
  [ -n "${data}" ] && args+=(-d "${data}")
  for _ in 1 2 3 4 5; do
    STATUS="$(curl "${args[@]}" "${url}" 2>/dev/null)" && [ "${STATUS}" != "000" ] && break
    sleep 1
  done
  BODY="$(cat /tmp/verify-signup.body 2>/dev/null)"
}

json() { python -c "import json,sys; d=json.load(sys.stdin); print(eval('d' + sys.argv[1]))" "$1" <<<"${BODY}"; }

check() {
  local name="$1" expected="$2"
  if [ "${STATUS}" = "${expected}" ]; then
    echo "PASS  ${name} (${STATUS})"
    PASSED=$((PASSED + 1))
  else
    echo "FAIL  ${name}: expected ${expected}, got ${STATUS} ${BODY:0:200}"
    FAILED=$((FAILED + 1))
  fi
}

check_true() {
  local name="$1" condition="$2"
  if [ "${condition}" = "true" ]; then
    echo "PASS  ${name}"
    PASSED=$((PASSED + 1))
  else
    echo "FAIL  ${name}"
    FAILED=$((FAILED + 1))
  fi
}

# The newest email to an address, as one block of the Dev_Mail_Log.
last_mail() {
  python - "$1" "${MAIL_LOG}" <<'PY'
import sys
to, path = sys.argv[1], sys.argv[2]
blocks = open(path, encoding="utf-8").read().split("==== ")
mine = [b for b in blocks if ("\nTo: " + to + "\n") in b]
print(mine[-1] if mine else "")
PY
}

mail_code() { sleep 1; last_mail "$1" | grep -o '\b[0-9]\{6\}\b' | head -1; }
mail_invite_token() { sleep 1; last_mail "$1" | grep -o '/invite/[A-Za-z0-9_-]*' | head -1 | cut -d/ -f3; }

wrong_code() { if [ "$1" = "000000" ]; then echo 111111; else echo 000000; fi; }

if [ ! -d "${ROOT}/dev-mail" ]; then
  echo "No ${ROOT}/dev-mail: rebuild auth-service with EMAIL_PROVIDER=file (docker-compose.core.yml)." >&2
  exit 1
fi

# --- 1. customer email sign-up (customer app proxy) --------------------------------------------
echo "== Customer email sign-up (${CUSTOMER_EMAIL})"
request POST "${CUSTOMER_APP}/auth/register/email" "{\"displayName\":\"Asha Test\",\"email\":\"${CUSTOMER_EMAIL}\",\"mobileNumber\":\"${CUSTOMER_MOBILE}\",\"password\":\"${PASSWORD}\",\"role\":\"CUSTOMER\"}"
check "sign-up answers 202 CODE_SENT" 202

request POST "${CUSTOMER_APP}/auth/login/password" "{\"identifier\":\"${CUSTOMER_EMAIL}\",\"password\":\"${PASSWORD}\"}"
check "sign-in before verifying is EMAIL_NOT_VERIFIED" 403

CODE="$(mail_code "${CUSTOMER_EMAIL}")"
check_true "the code is in the Dev_Mail_Log" "$([ -n "${CODE}" ] && echo true)"
request POST "${CUSTOMER_APP}/auth/register/email/verify" "{\"email\":\"${CUSTOMER_EMAIL}\",\"code\":\"$(wrong_code "${CODE}")\"}"
check "a wrong code is INVALID_CODE" 400
request POST "${CUSTOMER_APP}/auth/register/email/verify" "{\"email\":\"${CUSTOMER_EMAIL}\",\"code\":\"${CODE}\"}"
check "the right code signs the customer in" 200
check_true "the new account is a CUSTOMER" "$([ "$(json "['roles']")" = "['CUSTOMER']" ] && echo true)"
CUSTOMER_ID="$(json "['userId']")"

request POST "${CUSTOMER_APP}/auth/login/password" "{\"identifier\":\"${CUSTOMER_EMAIL^^}\",\"password\":\"${PASSWORD}\"}"
check "email sign-in, any letter case" 200
CUSTOMER_TOKEN="$(json "['accessToken']")"
CUSTOMER_REFRESH="$(json "['refreshToken']")"

request GET "${CUSTOMER_APP}/auth/me" "" "${CUSTOMER_TOKEN}"
check "GET /auth/me" 200
check_true "the email is verified on the account" "$([ "$(json "['emailVerified']")" = "True" ] && echo true)"

request POST "${CUSTOMER_APP}/auth/register/email" "{\"displayName\":\"Someone\",\"email\":\"customer@homefix.local\",\"mobileNumber\":\"+9173${DIGITS}\",\"password\":\"${PASSWORD}\",\"role\":\"CUSTOMER\"}"
check "sign-up with a registered email answers the same 202" 202
check_true "the owner is emailed 'you already have an account', no code" \
  "$(last_mail customer@homefix.local | grep -q 'Subject: You already have a HomeFix account' && ! last_mail customer@homefix.local | grep -qE '\b[0-9]{6}\b' && echo true)"

request POST "${CUSTOMER_APP}/auth/register/email" "{\"displayName\":\"Someone\",\"email\":\"dup-${RUN}@example.test\",\"mobileNumber\":\"${CUSTOMER_MOBILE}\",\"password\":\"${PASSWORD}\",\"role\":\"CUSTOMER\"}"
check "sign-up with a mobile another account holds is MOBILE_IN_USE" 409

request POST "${CUSTOMER_APP}/auth/register/email" "{\"displayName\":\"Mallory\",\"email\":\"evil-${RUN}@example.test\",\"mobileNumber\":\"+9174${DIGITS}\",\"password\":\"${PASSWORD}\",\"role\":\"SUPER_ADMIN\"}"
check "sign-up can never name a staff role" 400

# --- 2. provider email sign-up (provider app proxy) --------------------------------------------
echo "== Provider email sign-up (${PROVIDER_EMAIL})"
request POST "${PROVIDER_APP}/auth/register/email" "{\"displayName\":\"Ravi Pro\",\"email\":\"${PROVIDER_EMAIL}\",\"mobileNumber\":\"${PROVIDER_MOBILE}\",\"password\":\"${PASSWORD}\",\"role\":\"SERVICE_PROVIDER\"}"
check "provider sign-up" 202
request POST "${PROVIDER_APP}/auth/register/email/verify" "{\"email\":\"${PROVIDER_EMAIL}\",\"code\":\"$(mail_code "${PROVIDER_EMAIL}")\"}"
check "provider verifies and is signed in" 200
check_true "the new account is a SERVICE_PROVIDER" "$([ "$(json "['roles']")" = "['SERVICE_PROVIDER']" ] && echo true)"

# --- 3. staff invitation -----------------------------------------------------------------------
echo "== Staff invitation (${STAFF_EMAIL})"
request POST "${AUTH}/auth/register/otp" "{\"mobileNumber\":\"${SUPER_ADMIN_MOBILE}\"}"
sleep 1
OTP="$(grep -F "${SUPER_ADMIN_MOBILE}" "${SMS_LOG}" | grep -o 'code is [0-9]*' | tail -1 | grep -o '[0-9]*')"
request POST "${AUTH}/auth/register/verify" "{\"mobileNumber\":\"${SUPER_ADMIN_MOBILE}\",\"otp\":\"${OTP}\"}"
check "super admin signs in by OTP" 201
SUPER_TOKEN="$(json "['accessToken']")"

request POST "${GATEWAY}/admin/invitations" "{\"email\":\"${STAFF_EMAIL}\",\"role\":\"SUPER_ADMIN\"}" "${SUPER_TOKEN}"
check "no invitation can carry SUPER_ADMIN" 400
request POST "${GATEWAY}/admin/invitations" "{\"email\":\"${STAFF_EMAIL}\",\"role\":\"DISPATCHER\"}" "${SUPER_TOKEN}"
check "super admin invites a dispatcher" 201
request POST "${GATEWAY}/admin/invitations" "{\"email\":\"${STAFF_EMAIL}\",\"role\":\"ADMIN\"}" "${CUSTOMER_TOKEN}"
check "a customer cannot invite" 403

TOKEN="$(mail_invite_token "${STAFF_EMAIL}")"
request GET "${CUSTOMER_APP}/auth/invitations/${TOKEN}"
check "the invitee's link shows the invitation" 200
request POST "${CUSTOMER_APP}/auth/invitations/${TOKEN}/acceptance" "{\"displayName\":\"Dev Dispatcher\",\"mobileNumber\":\"${STAFF_MOBILE}\",\"password\":\"${PASSWORD}\"}"
check "accepting creates the account and signs in" 200
check_true "the invitee holds DISPATCHER" "$([ "$(json "['roles']")" = "['DISPATCHER']" ] && echo true)"
request POST "${CUSTOMER_APP}/auth/invitations/${TOKEN}/acceptance" "{\"displayName\":\"Again\",\"mobileNumber\":\"+9175${DIGITS}\",\"password\":\"${PASSWORD}\"}"
check "the link works once" 410
request POST "${CUSTOMER_APP}/auth/login/password" "{\"identifier\":\"${STAFF_EMAIL}\",\"password\":\"${PASSWORD}\"}"
check "the dispatcher signs in by email" 200

# --- 4. agency application ---------------------------------------------------------------------
echo "== Agency application by ${CUSTOMER_EMAIL}"
request GET "${GATEWAY}/catalog/categories" "" "${CUSTOMER_TOKEN}"
CATEGORY="$(python -c "import json,sys; d=json.load(sys.stdin); items=d if isinstance(d,list) else d.get('content',d.get('items',[])); print(items[0]['id'])" <<<"${BODY}")"
request POST "${GATEWAY}/tenant-applications" "{\"name\":\"Verify Agency ${RUN}\",\"contactPhone\":\"${CUSTOMER_MOBILE}\",\"contactEmail\":\"${CUSTOMER_EMAIL}\",\"baseLatitude\":0.0,\"baseLongitude\":0.0,\"serviceRadiusKm\":1,\"categoryIds\":[\"${CATEGORY}\"]}" "${CUSTOMER_TOKEN}"
check "the customer applies to register an agency" 201
TENANT_ID="$(json "['tenantId']")"
request GET "${GATEWAY}/tenant-applications/me" "" "${CUSTOMER_TOKEN}"
check_true "the application is PENDING_APPROVAL" "$([ "$(json "['status']")" = "PENDING_APPROVAL" ] && echo true)"
request POST "${GATEWAY}/tenant-applications" "{\"name\":\"Second ${RUN}\",\"baseLatitude\":25.5,\"baseLongitude\":84.6,\"serviceRadiusKm\":5,\"categoryIds\":[\"${CATEGORY}\"]}" "${CUSTOMER_TOKEN}"
check "one open application per user" 409
request GET "${GATEWAY}/tenant/me" "" "${CUSTOMER_TOKEN}"
check "a pending applicant has no Tenant Portal" 403

request GET "${GATEWAY}/admin/tenants?status=PENDING_APPROVAL" "" "${SUPER_TOKEN}"
check_true "the application is in the pending list" "$(grep -q "${TENANT_ID}" <<<"${BODY}" && echo true)"
request POST "${GATEWAY}/admin/tenants/${TENANT_ID}/approval" "" "${SUPER_TOKEN}"
check "the super admin approves it" 200
check_true "the decision email reached the applicant" \
  "$(last_mail "${CUSTOMER_EMAIL}" | grep -q 'has been approved' && echo true)"

request POST "${CUSTOMER_APP}/auth/login/password" "{\"identifier\":\"${CUSTOMER_EMAIL}\",\"password\":\"${PASSWORD}\"}"
check_true "signing in again carries TENANT_ADMIN" "$(grep -q TENANT_ADMIN <<<"${BODY}" && echo true)"
APPLICANT_TOKEN="$(json "['accessToken']")"
request GET "${GATEWAY}/tenant/me" "" "${APPLICANT_TOKEN}"
check "the approved applicant opens the Tenant Portal" 200

# --- 5. password reset (the address last got a code over a minute ago) --------------------------
echo "== Password reset for ${CUSTOMER_EMAIL} (waiting out the one-code-a-minute limit)"
sleep 61
request POST "${CUSTOMER_APP}/auth/password/forgot" "{\"email\":\"nobody-${RUN}@example.test\"}"
check "a reset for an unknown address answers 202 too" 202
request POST "${CUSTOMER_APP}/auth/password/forgot" "{\"email\":\"${CUSTOMER_EMAIL}\"}"
check "reset requested" 202
request POST "${CUSTOMER_APP}/auth/password/reset" "{\"email\":\"${CUSTOMER_EMAIL}\",\"code\":\"$(mail_code "${CUSTOMER_EMAIL}")\",\"newPassword\":\"${NEW_PASSWORD}\"}"
check "reset with the emailed code" 204
request POST "${CUSTOMER_APP}/auth/login/password" "{\"identifier\":\"${CUSTOMER_EMAIL}\",\"password\":\"${PASSWORD}\"}"
check "the old password no longer works" 401
request POST "${CUSTOMER_APP}/auth/login/password" "{\"identifier\":\"${CUSTOMER_EMAIL}\",\"password\":\"${NEW_PASSWORD}\"}"
check "the new password works" 200
request POST "${CUSTOMER_APP}/auth/token/refresh" "{\"refreshToken\":\"${CUSTOMER_REFRESH}\"}"
check "sessions from before the reset are ended" 401

echo
echo "${PASSED} passed, ${FAILED} failed"
[ "${FAILED}" -eq 0 ]
