#!/usr/bin/env bash
# Seeds Pricing Engine parameters for every subcategory in the Service Catalog.
#
# The Pricing Engine refuses to quote a subcategory it has no parameters for
# (PRICING_PARAMETERS_NOT_FOUND), so without this the booking flow stops at the
# price-estimate step. Parameters are held in memory in the local slice
# (PRICING_CACHE=memory), so re-run this after restarting pricing-engine.
#
# The work runs INSIDE the compose network via `docker exec`. Docker Desktop's
# Windows port-forwarding proxy resets connections intermittently under this
# stack's load, which made a host-side seed fail most of the time;
# container-to-container calls over the compose network are reliable.
#
# Usage:  bash docker/seed-pricing.sh
set -uo pipefail

# Any container on the compose network with wget and a shell; the gateway will do.
RUNNER="${RUNNER:-homefix-core-api-gateway-1}"
SEED_MOBILE="${SEED_MOBILE:-+919000881234}"
SMS_LOG="$(dirname "$0")/dev-sms/dev-sms.log"

echo "==> Requesting an OTP for ${SEED_MOBILE}"
docker exec "${RUNNER}" sh -c \
  "wget -q -O /dev/null --post-data='{\"mobileNumber\":\"${SEED_MOBILE}\"}' \
   --header='Content-Type: application/json' \
   http://auth-service:8081/auth/register/otp" || {
  echo "Could not reach the Auth Service from ${RUNNER}." >&2
  exit 1
}

sleep 1
OTP="$(grep -F "${SEED_MOBILE}" "${SMS_LOG}" 2>/dev/null \
  | grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}')"
if [ -z "${OTP}" ]; then
  echo "No OTP for ${SEED_MOBILE} in ${SMS_LOG}." >&2
  echo "Is SMS_PROVIDER=file set for auth-service in docker-compose.core.yml?" >&2
  exit 1
fi

echo "==> Seeding from inside the compose network"
docker exec "${RUNNER}" sh -c "
set -e
TOKEN=\$(wget -q -O - --post-data='{\"mobileNumber\":\"${SEED_MOBILE}\",\"otp\":\"${OTP}\"}' \
  --header='Content-Type: application/json' \
  http://auth-service:8081/auth/register/verify \
  | sed 's/.*\"accessToken\":\"\([^\"]*\)\".*/\1/')
case \"\$TOKEN\" in eyJ*) ;; *) echo 'OTP verification failed' >&2; exit 1 ;; esac

wget -q -O - http://catalog-service:8085/catalog/categories \
  | tr '{' '\n' | grep '\"basePrice\"' \
  | sed 's/.*\"id\":\"\([^\"]*\)\".*\"basePrice\":\([0-9.]*\).*/\1 \2/' > /tmp/subs.txt

count=0
while read id base; do
  [ -z \"\$id\" ] && continue
  wget -q -O /dev/null --method=PUT \
    --body-data=\"{\\\"subcategoryId\\\":\\\"\$id\\\",\\\"basePrice\\\":\$base,\\\"perKmRate\\\":8.00,\\\"maxTravelCharge\\\":150.00,\\\"nightSurcharge\\\":100.00,\\\"weekendSurcharge\\\":75.00,\\\"platformFeeRate\\\":0.10,\\\"taxRate\\\":0.18,\\\"emergencyMultiplier\\\":2.0,\\\"surgeMultiplier\\\":1.0,\\\"overrideFloor\\\":1.00,\\\"overrideCeiling\\\":99999.00}\" \
    --header=\"Authorization: Bearer \$TOKEN\" --header='Content-Type: application/json' \
    http://pricing-engine:8086/admin/pricing/parameters \
    && echo \"    \$id  base=\$base\" \
    || echo \"    FAILED \$id\"
  count=\$((count + 1))
done < /tmp/subs.txt
echo \"==> Seeded pricing parameters for \$count subcategories\"
"
