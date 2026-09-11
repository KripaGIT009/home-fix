#!/usr/bin/env bash
# Seeds a fixed set of test users into the local HomeFix stack, one per role.
#
# Customers and providers are created through the real public OTP flow, so the
# accounts are indistinguishable from ones a user would create. Privileged roles
# (ADMIN, SUPER_ADMIN, FINANCE_ADMIN, DISPATCHER, SUPPORT_AGENT) are granted
# out of band with a direct INSERT into auth.user_account_role, because
# self-service registration deliberately refuses to hand out those roles.
#
# Re-running the script is safe: accounts are matched on mobile number and role
# rows are inserted only when missing.
#
# Usage:  bash docker/seed-test-users.sh
#
# Prerequisites: the stack is up (docker compose -f docker-compose.core.yml up -d)
# and auth-service is configured with SMS_PROVIDER=file, which the local compose
# file already does.
set -uo pipefail

AUTH="${AUTH:-http://localhost:8081}"
PG_CONTAINER="${PG_CONTAINER:-homefix-core-postgres-1}"
PG_USER="${PG_USER:-homefix}"
PG_DB="${PG_DB:-homefix}"
SMS_LOG="${SMS_LOG:-$(dirname "$0")/dev-sms/dev-sms.log}"
OUT_FILE="${OUT_FILE:-$(dirname "$0")/test-users.generated.txt}"

# mobile|role|label  — roles beyond the three the enum ships with are granted by SQL.
USERS=(
  "+919000000001|CUSTOMER|Customer one (primary test customer)"
  "+919000000002|CUSTOMER|Customer two (second party for chat and reviews)"
  "+919000000011|SERVICE_PROVIDER|Provider one (primary test provider)"
  "+919000000012|SERVICE_PROVIDER|Provider two (second candidate for dispatch)"
  "+919000000021|ADMIN|Admin (operations console)"
  "+919000000022|SUPER_ADMIN|Super admin (system configuration)"
  "+919000000023|FINANCE_ADMIN|Finance admin (reconciliation reports)"
  "+919000000024|DISPATCHER|Dispatcher (manual assignment)"
  "+919000000025|SUPPORT_AGENT|Support agent (complaint handling)"
)

# Roles the public registration endpoint is allowed to assign.
SELF_SERVICE_ROLES="CUSTOMER SERVICE_PROVIDER"

psql_q() {
  docker exec -i "${PG_CONTAINER}" psql -qAt -U "${PG_USER}" -d "${PG_DB}" -c "$1"
}

# register <mobile> <role-for-registration>  → echoes the verify response body
register() {
  local mobile="$1" role="$2"
  curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
    -d "{\"mobileNumber\":\"${mobile}\",\"role\":\"${role}\"}" \
    "${AUTH}/auth/register/otp"
  sleep 1
  local otp
  otp="$(grep -F "${mobile}" "${SMS_LOG}" 2>/dev/null \
    | grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}')"
  if [ -z "${otp}" ]; then
    echo "    no OTP found for ${mobile} in ${SMS_LOG}" >&2
    return 1
  fi
  curl -s -X POST -H 'Content-Type: application/json' \
    -d "{\"mobileNumber\":\"${mobile}\",\"otp\":\"${otp}\"}" \
    "${AUTH}/auth/register/verify"
}

json_field() { printf '%s' "$1" | sed "s/.*\"$2\":\"\([^\"]*\)\".*/\1/"; }

printf 'Seeding HomeFix test users\n\n'
: > "${OUT_FILE}"
printf '%-22s %-16s %-38s %s\n' 'MOBILE' 'ROLE' 'USER ID' 'NOTE' | tee -a "${OUT_FILE}"
printf '%s\n' '---------------------------------------------------------------------------------------------------' | tee -a "${OUT_FILE}"

for entry in "${USERS[@]}"; do
  IFS='|' read -r mobile role label <<< "${entry}"

  # Does the account already exist?
  user_id="$(psql_q "SELECT id FROM auth.user_account WHERE mobile_number = '${mobile}';" | tr -d '\r')"

  if [ -z "${user_id}" ]; then
    # Register through the public flow. Privileged roles are registered as
    # CUSTOMER first, then granted the real role by SQL below.
    reg_role="${role}"
    case " ${SELF_SERVICE_ROLES} " in
      *" ${role} "*) ;;
      *) reg_role="CUSTOMER" ;;
    esac

    body="$(register "${mobile}" "${reg_role}")" || { printf '%-22s %-16s %-38s %s\n' "${mobile}" "${role}" "-" "REGISTRATION FAILED" | tee -a "${OUT_FILE}"; continue; }
    user_id="$(json_field "${body}" userId)"
    if [ -z "${user_id}" ] || [ "${user_id}" = "${body}" ]; then
      printf '%-22s %-16s %-38s %s\n' "${mobile}" "${role}" "-" "VERIFY FAILED: $(printf '%s' "${body}" | head -c 80)" | tee -a "${OUT_FILE}"
      continue
    fi
    note="created"
  else
    note="existing"
  fi

  # Ensure the target role row exists. This is the out-of-band grant for
  # privileged roles and a no-op for roles registration already assigned.
  psql_q "INSERT INTO auth.user_account_role (user_id, role)
          SELECT '${user_id}', '${role}'
          WHERE NOT EXISTS (
            SELECT 1 FROM auth.user_account_role
            WHERE user_id = '${user_id}' AND role = '${role}');" >/dev/null

  # Privileged accounts should not keep the placeholder CUSTOMER role.
  case " ${SELF_SERVICE_ROLES} " in
    *" ${role} "*) ;;
    *) psql_q "DELETE FROM auth.user_account_role
               WHERE user_id = '${user_id}' AND role = 'CUSTOMER';" >/dev/null ;;
  esac

  roles="$(psql_q "SELECT string_agg(role, ',' ORDER BY role) FROM auth.user_account_role WHERE user_id = '${user_id}';" | tr -d '\r')"
  printf '%-22s %-16s %-38s %s\n' "${mobile}" "${roles}" "${user_id}" "${note} — ${label}" | tee -a "${OUT_FILE}"
done

printf '\nWritten to %s\n' "${OUT_FILE}"
printf 'Log in with any of these numbers; read the code with: bash docker/otp.sh\n'
