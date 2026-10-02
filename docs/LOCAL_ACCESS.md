# HomeFix — Running Locally, Test Users and URLs

Everything here targets the local stack defined in [`docker-compose.core.yml`](../docker-compose.core.yml), running against the Postgres, Kafka and Redis installed on this machine. Nothing in this document is a real credential: the stack ships with a throwaway signing secret and a file-based SMS gateway, and it must never be pointed at production data.

---

## 1. Start the stack

Locally the 20 services and 3 web apps run in Docker Compose, but **Postgres, Kafka and Redis
are the ones installed on this machine**, not containers. The containers reach them through
`host.docker.internal`. On AWS none of this applies: Helm points each service at RDS, MSK and
ElastiCache through per-environment values and Secrets (`helm/services/*.yaml`).

### 1.1 One-time setup of the local infrastructure

| Component | Where | Port(s) the stack uses |
|-----------|-------|------------------------|
| PostgreSQL 18 | Windows service `postgresql-x64-18` | `5432`, database `homefix`, role `homefix` |
| Kafka 4.1 (KRaft) | `C:\kafka`, started by hand (below) | `9092` for host tools, `9094` for containers |
| Redis-compatible | Memurai service, or the stand-in container `homefix-local-redis` | `6379` |

**Postgres.** Create the role and database once, then the schemas. The password is
`DB_PASSWORD` from `.env`:

```bash
PSQL="/c/Program Files/PostgreSQL/18/bin/psql.exe"
PGPASSWORD=<postgres superuser password> "$PSQL" -h localhost -U postgres \
  -c "CREATE ROLE homefix LOGIN PASSWORD '<DB_PASSWORD from .env>'" \
  -c "CREATE DATABASE homefix OWNER homefix"
PGPASSWORD=<DB_PASSWORD> "$PSQL" -h localhost -U homefix -d homefix -f docker/init-db.sql
```

Containers' connections arrive as `127.0.0.1`, so the installer's default `pg_hba.conf` already
admits them. The stock `max_connections=100` is enough: Compose gives each of the 18
database-backed services a pool of 4.

Tables are created by each service's **Flyway migrations** at startup
(`services/*/src/main/resources/db/migration`, plus the shared outbox tables shipped in
`homefix-shared-outbox`), and Hibernate only validates against them. That is the same
mechanism a deployment uses, so a schema that works locally works on RDS.

**Kafka.** `C:\kafka\config\server.properties` needs four changes from the distribution
defaults (the original is kept as `server.properties.bak`):

```properties
# A second listener the containers can reach; localhost:9092 means nothing inside a container.
listeners=PLAINTEXT://:9092,CONTROLLER://:9093,DOCKER://:9094
advertised.listeners=PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093,DOCKER://host.docker.internal:9094
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,DOCKER:PLAINTEXT,...
log.dirs=C:/kafka/data
# Kafka on native Windows cannot delete or compact memory-mapped segments: the rename fails,
# the whole log directory is marked failed and the broker shuts itself down. So neither runs.
log.retention.hours=-1
log.cleaner.enable=false
```

Format the data directory once, then start the broker (it runs until you close it or reboot):

```powershell
cd C:\kafka
$id = (bin\windows\kafka-storage.bat random-uuid)
bin\windows\kafka-storage.bat format -t $id -c config\server.properties --standalone
$env:KAFKA_HEAP_OPTS = "-Xmx768m -Xms256m"
Start-Process bin\windows\kafka-server-start.bat config\server.properties -WindowStyle Hidden `
  -RedirectStandardOutput logs\homefix-stdout.log -RedirectStandardError logs\homefix-stderr.log
```

Do not delete topics on this broker: deletion hits the same Windows rename failure and takes
the broker down. Topics are auto-created on first use.

**Redis.** Install Memurai Developer (a Redis-compatible Windows service on 6379) from an
**elevated** prompt — the installer needs administrator rights:

```powershell
winget install --id Memurai.MemuraiDeveloper -e
```

Until it is installed, a Redis container stands in on the same port, so nothing else changes
when you switch:

```bash
docker run -d --name homefix-local-redis --restart unless-stopped -p 6379:6379 redis:7-alpine
# once Memurai is installed:
docker rm -f homefix-local-redis
```

No local Postgres, Kafka or Redis at all? Use the containerised ones in
[`docker-compose.infra.yml`](../docker-compose.infra.yml) instead; its header has the command.

### 1.2 Start

```bash
cp .env.example .env            # first time only; Compose refuses to start without it

# Start Kafka (1.1) first, then everything else. --wait gates on each container's own health
# check and exits non-zero if anything fails to come up.
docker compose -f docker-compose.core.yml up -d --build --wait
```

A cold start takes 4-6 minutes on a 6-core machine: twenty JVMs boot at once and are CPU-bound.
The health checks allow a 300 s start period, so `--wait` does not give up on a service that is
merely slow.

The service images are runtime-only: each Dockerfile copies an already-built fat jar. Build
them first, or after changing a service:

```bash
# Shared libraries must be installed before any service will resolve.
for m in observability security resilience outbox; do
  (cd shared/homefix-shared-$m && mvn -q -DskipTests -Djacoco.skip=true install)
done

# Then each service, or just the one you changed.
(cd services/auth-service && mvn -q -DskipTests -Djacoco.skip=true package)
docker compose -f docker-compose.core.yml up -d --build --wait auth-service
```

### 1.3 Seed a new database (once)

Everything below persists in Postgres, so it is needed once per database, not per restart.
Run it after the stack is up, because the services' migrations create the tables.

```bash
. docker/local-infra.sh                                # psql_q, PSQL and PG* settings
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-catalog.sql           # categories, skill tags
bash docker/seed-pricing.sh                                     # pricing for every subcategory
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-provider-profiles.sql # 2 approved providers in Ara
```

The test accounts in section 3 need no script: auth-service creates them at startup when
`DEV_SEED_ENABLED=true`. `docker/seed-test-users.sh` remains for extra OTP-only accounts.

Two check scripts exercise the running stack end to end and exit non-zero on any failure:

```bash
bash docker/smoke-flows.sh          # every service's main paths: 78/78
bash docker/verify-outbox-flow.sh   # book -> outbox -> Kafka -> dispatch match -> provider accepts: 17/17
```

### Memory

Twenty JVMs do not fit in Docker Desktop's default WSL allocation (about half of host RAM).
Left unbounded the stack thrashes: containers stop answering, image builds die with
`Unavailable: EOF`, and the engine has fallen over outright, exiting every container at once.

Two things make it fit:

- Every Java service declares `mem_limit: 448m` and a `JAVA_TOOL_OPTIONS` heap percentage.
  Without a container limit each JVM sizes its max heap at 25% of the *whole VM* — about
  1.9 GB each — so twenty idle services feel no pressure to collect and between them exhaust
  the host. Measured steady state with the limits in place is ~300 MB per service.
- Raise the WSL ceiling above the default. Put this in `%UserProfile%\.wslconfig`, then
  `wsl --shutdown` and restart Docker Desktop:

```ini
[wsl2]
memory=11GB
processors=6
swap=2GB
```

Kafka (heap capped at 768 MB above) and Postgres now run on the host, outside that VM.

A readiness probe from the host is not evidence a service is down: under load Docker's port
forwarding drops connections while the service itself is fine. Probe from inside the network:

```bash
docker exec homefix-core-api-gateway-1 \
  wget -qO- http://rating-review-service:8097/health/readiness
```

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
| pricing-engine | http://localhost:8086 | pricing |
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

| Component | From this machine | From the containers | Credentials |
|-----------|-------------------|---------------------|-------------|
| PostgreSQL 18 | `localhost:5432`, database `homefix` | `host.docker.internal:5432` | user `homefix`, password `DB_PASSWORD` from `.env` |
| Redis | `localhost:6379` | `host.docker.internal:6379` | no auth |
| Kafka 4.1 (KRaft) | `localhost:9092` | `host.docker.internal:9094` | no auth |

```bash
# psql, topic listing and topic tailing, with the connection details filled in from .env
. docker/local-infra.sh
"$PSQL"                                       # interactive psql into homefix
psql_q '\dn'                                  # the schemas, one per service
psql_q 'SELECT version, type, success FROM booking.flyway_schema_history'
kafka_tool kafka-topics.sh --list
kafka_tool kafka-console-consumer.sh --topic BookingCreated --from-beginning
```

`kafka_tool` runs the Kafka CLI in a throwaway container against the `9094` listener, so it
behaves the same with or without the Kafka distribution's own `.bat` tools on your PATH.

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

Knowing this up front saves debugging time. [CODEBASE_REVIEW.md](../CODEBASE_REVIEW.md)
section 16 has the detail and the verification behind the latest changes.

**Works end to end** (both check scripts in section 1.3 cover these)

- OTP and password sign-in, lockout, rate limiting; token rotation with replay detection;
  logout that revokes the whole token family
- Catalog browsing and admin catalog CRUD
- Price estimates, persisted pricing parameters, and coupons quoted by promotion-service
- Booking creation (scheduled and emergency, always with a saved address) and confirmation
- **Provider matching**: dispatch resolves the address and skill tags, finds approved providers
  in range through provider-service, and offers the job; the provider app lists and answers
  offers (`/dispatch/offers` through the gateway); acceptance moves the booking to
  `PROVIDER_ACCEPTED`; an unanswered search ends in `SEARCHING_FAILED` and is announced
- Domain events from outbox to Kafka to every consumer, including cancellation, rejection and
  complaint events
- Job milestones, live location, chat channels, payments with signed callbacks and
  idempotent refunds, invoices, reviews, complaints with recorded refunds
- Schema migrations: every service creates and validates its schema through Flyway
- All three web apps build, serve and proxy correctly

**Still not working**

| Area | Symptom | Review reference |
|------|---------|------------------|
| Admin portal modules | Eleven of sixteen modules call list endpoints no service implements; they show "Not Found" | 13.2 |
| Real external providers | Payment gateways, SMS/email/push, document storage and geocoding are logging or in-memory stubs | 12.3 item 9 |
| Coupon redemption | Coupons are quoted and charged, but nothing calls promotion-service's redeem, so usage limits are never consumed | 16 |
| Saved addresses | customer-service cannot list a customer's addresses, so the app can only reuse ones saved from that device | 16 |
| Push and email | No device tokens or email addresses reach notification-service; SMS and in-app work | 16 |

---

## 6. Stopping and resetting

```bash
# Stop the services; data stays in the local Postgres
docker compose -f docker-compose.core.yml stop

# Remove the containers too
docker compose -f docker-compose.core.yml down

# Full reset of the data: recreate the database, then start and seed again (section 1.3)
PGPASSWORD=<postgres superuser password> "/c/Program Files/PostgreSQL/18/bin/psql.exe" \
  -h localhost -U postgres -c "DROP DATABASE homefix WITH (FORCE)" -c "CREATE DATABASE homefix OWNER homefix"
. docker/local-infra.sh && "$PSQL" -f docker/init-db.sql
rm -f docker/dev-sms/dev-sms.log
```

`down -v` no longer resets anything: the data lives in the host's Postgres, not a volume.
Kafka and Redis are left running by all of the above; stop them as you would any local
service.

---

## 7. Security note for this stack

The local stack is deliberately insecure and several of these values are committed in plain text:

- One shared JWT signing secret, repeated across all services
- One shared database role for every service, with a local-only password
- OTP codes written to `docker/dev-sms/dev-sms.log` and echoed into auth-service logs
- Test accounts with a shared, documented password (`DEV_SEED_ENABLED`)

Never reuse any of these outside a local machine, and never point this Compose file at a shared database. The production path for all of them is AWS Secrets Manager, described in [ARCHITECTURE.md](ARCHITECTURE.md) section 10.2.
