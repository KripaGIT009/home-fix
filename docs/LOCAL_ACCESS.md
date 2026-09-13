# HomeFix — Running Locally, Test Users and URLs

Everything here targets the local Docker Compose stack defined in [`docker-compose.core.yml`](../docker-compose.core.yml). Nothing in this document is a real credential: the stack ships with a throwaway signing secret and a file-based SMS gateway, and it must never be pointed at production data.

---

## 1. Start the stack

```bash
# From the repository root. Requires Docker Desktop running.
docker compose -f docker-compose.core.yml -p homefix-core up -d

# Watch until every service reports healthy (about 2-3 minutes on a cold start).
docker compose -f docker-compose.core.yml -p homefix-core ps
```

The service images are runtime-only: each Dockerfile copies an already-built fat jar. If a jar is missing, build it first.

```bash
# Shared libraries must be installed before any service will resolve.
for m in observability security resilience outbox; do
  (cd shared/homefix-shared-$m && mvn -q -DskipTests -Djacoco.skip=true install)
done

# Then each service, or just the one you changed.
(cd services/auth-service && mvn -q -DskipTests -Djacoco.skip=true package)
docker compose -f docker-compose.core.yml -p homefix-core up -d --build auth-service
```

One seeding step is needed after a fresh start, because the pricing engine holds its
parameters in memory and no catalog data is bootstrapped.

```bash
bash docker/seed-pricing.sh         # pricing parameters for every subcategory
bash docker/smoke-flows.sh          # optional: end-to-end check of every service
bash docker/verify-outbox-flow.sh   # optional: checks the event path works
```

The test accounts in section 3 no longer need a script: auth-service creates them itself at
startup when `DEV_SEED_ENABLED=true`, so they exist as soon as it reports ready.
`docker/seed-test-users.sh` remains for the OTP-only accounts it always made, but running it
is no longer necessary.

The stack now reads its secrets from an environment file, so copy the example before the first start:

```bash
cp .env.example .env
```

Compose fails with the name of the missing variable if you skip this, rather than starting with a placeholder secret.

### Memory

Twenty JVMs plus Kafka and Postgres need more memory than Docker Desktop takes by default. On
a 16 GB host the stack starts and then thrashes: containers intermittently stop answering,
image builds die with `Unavailable: EOF`, and the Docker API itself starts returning 500s. In
the worst case the engine falls over and every container exits 255 at once.

Raise the WSL ceiling in `%UserProfile%\.wslconfig`, then `wsl --shutdown` and restart Docker
Desktop:

```ini
[wsl2]
memory=12GB
```

If you cannot spare the memory, run a slice instead of the whole stack. This one is enough to
sign in and use the Dashboard and System Configuration modules:

```bash
docker compose -f docker-compose.core.yml -p homefix-core up -d \
  postgres redis kafka auth-service api-gateway admin-service admin-portal
```

Add the services a given flow needs on top of that. Every service now carries
`restart: "on-failure:5"`, so a container that loses a startup race to Kafka recovers on its
own rather than staying down until someone notices.

Re-run `seed-pricing.sh` after restarting pricing-engine; its parameters live in memory only. Account seeding is idempotent and runs on every auth-service start.

---

## 2. Application URLs

### Web apps

| App | URL | Who it is for |
|-----|-----|---------------|
| Customer app | http://localhost:5173 | Customers booking services |
| Provider app | http://localhost:5174 | Service professionals |
| Admin portal | http://localhost:5175 | Staff: operations, finance, support |

Each app serves its own nginx, which proxies `/api/auth` to auth-service and everything else under `/api` to the API gateway. Use these URLs rather than calling services directly, so the proxy and token handling behave as they do in a deployment.

### API entry points

| Entry point | URL | Notes |
|-------------|-----|-------|
| API gateway | http://localhost:8080 | Authenticated surface for every domain service |
| Auth service | http://localhost:8081 | Registration, refresh, introspection; public by design |

### Individual services

Useful for debugging a single service. Every service exposes `/health/liveness`, `/health/readiness`, `/prometheus` and `/metrics` at its root, not under `/actuator`.

| Service | URL | Schema |
|---------|-----|--------|
| api-gateway | http://localhost:8080 | — |
| auth-service | http://localhost:8081 | auth |
| customer-service | http://localhost:8082 | customer |
| provider-service | http://localhost:8083 | provider |
| booking-service | http://localhost:8084 | booking |
| catalog-service | http://localhost:8085 | catalog |
| pricing-engine | http://localhost:8086 | — |
| dispatch-engine | http://localhost:8087 | dispatch |
| payment-service | http://localhost:8088 | payment |
| invoice-service | http://localhost:8089 | invoice |
| notification-service | http://localhost:8090 | notification |
| complaint-service | http://localhost:8091 | complaint |
| location-service | http://localhost:8092 | location |
| chat-service | http://localhost:8093 | chat |
| verification-service | http://localhost:8094 | verification |
| admin-service | http://localhost:8095 | admin |
| reporting-service | http://localhost:8096 | — |
| rating-review-service | http://localhost:8097 | rating |
| promotion-service | http://localhost:8098 | promotion |
| outbox-processor | http://localhost:8099 | — |

Local ports differ from the `application.yml` defaults for location, verification, rating and promotion, because several services ship with colliding defaults. The Compose file is authoritative locally.

### Infrastructure

| Component | Connection | Credentials |
|-----------|-----------|-------------|
| PostgreSQL 16 | `localhost:5533`, database `homefix` | user `homefix`, password `homefix` |
| Redis 7 | `localhost:6380` | no auth |
| Kafka 3.7 (KRaft) | `localhost:9095` | no auth |

```bash
# psql into the stack
docker exec -it homefix-core-postgres-1 psql -U homefix -d homefix

# List the schemas, one per service
docker exec -it homefix-core-postgres-1 psql -U homefix -d homefix -c '\dn'

# Tail Kafka topics
docker exec -it homefix-core-kafka-1 \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic BookingCreated --from-beginning
```

---

## 3. Test users

Two ways in. **Username and password** is the everyday path and the one the Admin Portal
defaults to. **OTP** still works for every account, and remains the only way into an account
that has no credentials provisioned.

The accounts are created by the Auth Service itself at startup when `DEV_SEED_ENABLED=true`
(it is set in `.env.example`, so a copied `.env` has it on). Seeding is idempotent: an existing
account keeps its id and simply gains the roles and credentials below, so re-running it never
orphans data that already references the account.

Every account shares the password in `DEV_SEED_PASSWORD`, which defaults to `HomeFix@2026` in
`.env.example`. Change it there and restart auth-service to rotate all nine at once.

| Username | Password | Mobile number | Role | What it is for |
|----------|----------|---------------|------|----------------|
| `customer` | `HomeFix@2026` | `+919000000001` | CUSTOMER | Primary test customer: browse, book, pay, review |
| `customer2` | `HomeFix@2026` | `+919000000002` | CUSTOMER | Second customer, for chat and review counterparties |
| `provider` | `HomeFix@2026` | `+919000000011` | SERVICE_PROVIDER | Primary test provider: accept jobs, run milestones |
| `provider2` | `HomeFix@2026` | `+919000000012` | SERVICE_PROVIDER | Second provider, so dispatch has more than one candidate |
| `admin` | `HomeFix@2026` | `+919000000021` | ADMIN | Operations console: every admin module except system config |
| `superadmin` | `HomeFix@2026` | `+919000000022` | SUPER_ADMIN + ADMIN | Adds System Configuration to the admin surface |
| `finance` | `HomeFix@2026` | `+919000000023` | FINANCE_ADMIN | Payment reconciliation and settlement reports |
| `dispatcher` | `HomeFix@2026` | `+919000000024` | DISPATCHER | Manual assignment and dispatch weight tuning |
| `support` | `HomeFix@2026` | `+919000000025` | SUPPORT_AGENT | Complaint triage, status changes, refunds |

None of this is a real credential. The whole stack ships with a throwaway signing secret and a
file-based SMS gateway, and must never be pointed at production data. The seeder is off unless
explicitly enabled, and the password has no default inside the service, so an enabled seeder
that is given no password logs an error and seeds nothing rather than inventing one.

Staff roles still cannot be obtained by registering. Public registration accepts only
`CUSTOMER` and `SERVICE_PROVIDER`; anything else is refused with `INVALID_ROLE`. The seeder is
the out-of-band path a real administrator would otherwise take by hand.

### Signing in with a password

In the Admin Portal (http://localhost:5175) the Password tab is selected by default: enter
`admin` / `HomeFix@2026`. From the command line:

```bash
curl -s -X POST http://localhost:8081/auth/login/password   -H 'Content-Type: application/json'   -d '{"username":"admin","password":"HomeFix@2026"}'
```

The response is the same body every other authentication path returns — user id, roles, a
15-minute access token and a 30-day refresh token.

A wrong username and a wrong password answer identically (401 `INVALID_CREDENTIALS`), so the
endpoint cannot be used to discover which usernames exist. Five consecutive failures lock that
username for 30 minutes with a 429 and a `Retry-After`, mirroring the OTP flow's lockout. The
lock is keyed on the username, so it survives a service restart and is cleared by a successful
sign-in.

### Logging in

In any of the three web apps, enter one of the numbers above, then read the code:

```bash
bash docker/otp.sh                       # most recent code the stack issued
grep '+919000000001' docker/dev-sms/dev-sms.log | tail -1   # a specific number's code
```

The code is six digits, valid for five minutes. Five wrong attempts lock the number for thirty minutes. A number may request at most five codes per hour.

### Logging in from the command line

```bash
MOBILE='+919000000001'

curl -s -X POST http://localhost:8081/auth/register/otp \
  -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"$MOBILE\"}"

OTP=$(grep -F "$MOBILE" docker/dev-sms/dev-sms.log | grep -o 'code is [0-9]*' | tail -1 | grep -o '[0-9]*')

curl -s -X POST http://localhost:8081/auth/register/verify \
  -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"$MOBILE\",\"otp\":\"$OTP\"}"
```

The response carries the user id, roles, a bearer access token valid for fifteen minutes, and an opaque refresh token valid for thirty days.

```json
{
  "userId": "bc0551ae-b3fb-4571-8587-017fa39096a4",
  "roles": ["CUSTOMER"],
  "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
  "refreshToken": "22690c5e-221a-4d50-8d87-dc141e63c991...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900
}
```

Use the access token on every other call:

```bash
TOKEN='eyJhbGciOiJIUzM4NCJ9...'
curl -s http://localhost:8080/catalog/categories -H "Authorization: Bearer $TOKEN"
```

### Refreshing and logging out

```bash
curl -s -X POST http://localhost:8081/auth/token/refresh \
  -H 'Content-Type: application/json' -d '{"refreshToken":"'"$REFRESH"'"}'

curl -s -X POST http://localhost:8081/auth/logout \
  -H 'Content-Type: application/json' -d '{"refreshToken":"'"$REFRESH"'"}'
```

Refresh tokens rotate on every use. Presenting a token twice invalidates the whole token family, which is the replay defence.

---

## 4. A worked end-to-end run

This is the shortest path that exercises the main flow. It assumes `seed-pricing.sh` has run.

```bash
# 1. Log in as the test customer, keep the token
MOBILE='+919000000001'
curl -s -X POST http://localhost:8081/auth/register/otp -H 'Content-Type: application/json' -d "{\"mobileNumber\":\"$MOBILE\"}" > /dev/null
sleep 1
OTP=$(grep -F "$MOBILE" docker/dev-sms/dev-sms.log | grep -o 'code is [0-9]*' | tail -1 | grep -o '[0-9]*')
TOKEN=$(curl -s -X POST http://localhost:8081/auth/register/verify -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"$MOBILE\",\"otp\":\"$OTP\"}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')

# 2. Browse the catalog and pick a subcategory
curl -s http://localhost:8085/catalog/categories | head -c 400

# 3. Price it
curl -s -X POST http://localhost:8086/pricing/estimate \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"subcategoryId":"<id from step 2>","emergency":false}'

# 4. Create a scheduled booking at least the minimum lead time ahead
curl -s -X POST http://localhost:8084/bookings \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"categoryId":"<id>","subcategoryId":"<id>","emergency":false,
       "scheduledAt":"2026-09-12T10:00:00Z","description":"Kitchen tap dripping"}'
```

The booking is created and priced correctly. It then stops: the dispatch engine cannot assign a provider because of the broken integrations described in the review, so nothing reaches `PROVIDER_ACCEPTED` yet. See [CODEBASE_REVIEW.md](../CODEBASE_REVIEW.md) section 8.3 and the roadmap in section 11.

---

## 5. What works and what does not, today

Knowing this up front saves debugging time.

**Works end to end**

- OTP registration, verification, lockout and rate limiting
- Token issue, rotation with replay detection, introspection, logout
- Catalog browsing and admin catalog CRUD
- Price estimates including emergency multiplier and surcharges
- Booking creation and validation, both scheduled and emergency
- Job milestone transitions when driven directly against booking-service
- Location ingest, snapshot and the SSE stream
- Payment initiation with idempotency, invoice numbering, coupon validation
- Every service's health, readiness and Prometheus endpoints
- All three web apps build, serve and proxy correctly

**Fixed since the first review**

These were broken when the review was written and now work. See [CODEBASE_REVIEW.md](../CODEBASE_REVIEW.md) section 12 for the detail and the verification behind each.

| Area | What changed |
|------|--------------|
| Authorization | Staff endpoints now enforce roles across eleven services, and six services check that the caller owns the resource named in the path |
| Staff accounts | Registration refuses a staff role, so an admin account can no longer be minted by anyone with a phone number |
| Domain events | The relay and the producers now agree on one outbox schema, so events are actually published |
| Dispatch callback | The two internal booking endpoints exist, are credential-guarded, and perform the legal transition sequence |
| Chat activation | Provider-accepted events now carry the customer, so channels activate instead of dead-lettering |
| Live tracking stop | The location consumer listens on the topic the relay publishes to |
| Admin portal login | Uses the endpoints that exist |
| Session persistence | A page reload no longer logs you out |
| Logout | Revokes the refresh token server-side |

**Still broken**

| Area | Symptom | Review reference |
|------|---------|------------------|
| Provider matching | A booking reaches `SEARCHING_PROVIDER` and stops there. Dispatch deliberately refuses the event because it carries no customer coordinates or skill tags, and dead-letters it with a reason naming what it needs. The enrichment step is not built yet. | 12.2 |
| Payment callback | The signature covers only the payload, so the outcome and the target transaction are unsigned on a public endpoint | 8.4 |
| Notifications | No producer emits a recipient or contact details, so most lifecycle topics dead-letter and nothing can be delivered | contracts |
| Pricing after restart | Parameters live in memory only, so re-run `seed-pricing.sh` | 8.3 |
| Refunds | Neither the payment nor the complaint refund path is idempotent | 8.4, 8.5 |
| Deployment outside Compose | No migrations exist, so a service validating its schema finds nothing | 8.1 |

---

## 6. Stopping and resetting

```bash
# Stop, keep data
docker compose -f docker-compose.core.yml -p homefix-core stop

# Stop and remove containers, keep the database volume
docker compose -f docker-compose.core.yml -p homefix-core down

# Full reset, including all data and the OTP log
docker compose -f docker-compose.core.yml -p homefix-core down -v
rm -f docker/dev-sms/dev-sms.log
```

After a full reset, re-run both seed scripts.

---

## 7. Security note for this stack

The local stack is deliberately insecure and several of these values are committed in plain text:

- One shared JWT signing secret, repeated across all services
- Database password `homefix`
- OTP codes written to `docker/dev-sms/dev-sms.log` and echoed into auth-service logs
- Hibernate `ddl-auto: update`, so schema changes apply silently

Never reuse any of these outside a local machine, and never point this Compose file at a shared database. The production path for all of them is AWS Secrets Manager, described in [ARCHITECTURE.md](ARCHITECTURE.md) section 10.2.
