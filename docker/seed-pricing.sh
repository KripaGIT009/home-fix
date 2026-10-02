#!/usr/bin/env bash
# Seeds Pricing Engine parameters for every subcategory in the Service Catalog.
#
# The Pricing Engine refuses to quote a subcategory it has no parameters for
# (PRICING_PARAMETERS_NOT_FOUND), so without this the booking flow stops at the
# price-estimate step. Parameters persist in Postgres (pricing.pricing_parameters),
# so this is needed once per database, not after every pricing-engine restart.
# Re-running it is safe: each PUT replaces that subcategory's parameters.
#
# PUT /admin/pricing/parameters requires an ADMIN principal (RbacEnforcementFilter),
# so this signs in as the seeded `admin` account. It used to register a throwaway
# OTP number instead, which authorization hardening turned into a silent 403 on
# every subcategory.
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
ADMIN_USER="${ADMIN_USER:-admin}"
# Matches DEV_SEED_PASSWORD, which auth-service seeds the admin account with.
ADMIN_PASSWORD="${ADMIN_PASSWORD:-${DEV_SEED_PASSWORD:-HomeFix@2026}}"

echo "==> Signing in as ${ADMIN_USER}"
echo "==> Seeding from inside the compose network"
docker exec "${RUNNER}" sh -c "
set -u
TOKEN=\$(wget -q -O - --post-data='{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}' \
  --header='Content-Type: application/json' \
  http://auth-service:8081/auth/login/password \
  | sed 's/.*\"accessToken\":\"\([^\"]*\)\".*/\1/')
case \"\$TOKEN\" in
  eyJ*) ;;
  *) echo 'Admin sign-in failed. Is DEV_SEED_ENABLED=true and DEV_SEED_PASSWORD correct?' >&2; exit 1 ;;
esac

wget -q -O - http://catalog-service:8085/catalog/categories \
  | tr '{' '\n' | grep '\"basePrice\"' \
  | sed 's/.*\"id\":\"\([^\"]*\)\".*\"basePrice\":\([0-9.]*\).*/\1 \2/' > /tmp/subs.txt

ok=0
failed=0
while read id base; do
  [ -z \"\$id\" ] && continue
  if wget -q -O /dev/null --method=PUT \
    --body-data=\"{\\\"subcategoryId\\\":\\\"\$id\\\",\\\"basePrice\\\":\$base,\\\"perKmRate\\\":8.00,\\\"maxTravelCharge\\\":150.00,\\\"nightSurcharge\\\":100.00,\\\"weekendSurcharge\\\":75.00,\\\"platformFeeRate\\\":0.10,\\\"taxRate\\\":0.18,\\\"emergencyMultiplier\\\":2.0,\\\"surgeMultiplier\\\":1.0,\\\"overrideFloor\\\":1.00,\\\"overrideCeiling\\\":99999.00}\" \
    --header=\"Authorization: Bearer \$TOKEN\" --header='Content-Type: application/json' \
    http://pricing-engine:8086/admin/pricing/parameters; then
    ok=\$((ok + 1))
    echo \"    \$id  base=\$base\"
  else
    failed=\$((failed + 1))
    echo \"    FAILED \$id\"
  fi
done < /tmp/subs.txt

# Report what actually landed, and fail the script if anything did not. The old
# version counted attempts, so a run where every PUT 403'd still signed off with
# a success line.
echo \"==> Seeded pricing parameters for \$ok subcategories (\$failed failed)\"
[ \"\$failed\" -eq 0 ] && [ \"\$ok\" -gt 0 ]
"
