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

**After every reboot, start Kafka again** (the `cd`, `KAFKA_HEAP_OPTS` and `Start-Process` lines
above; never the format step) before starting or restarting the stack. Nothing starts it for you, and with it down no event
moves even where the HTTP calls succeed: bookings stop at `SEARCHING_PROVIDER` and paid
bookings stay `PAYMENT_PENDING`. Check it with `kafka_tool kafka-topics.sh --list` (section 2).

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

### 1.2 Build, then start

The service images are runtime-only: each Dockerfile copies the fat jar already built under
`services/<name>/target/`. `--build` does **not** compile anything, so build the jars first, and
rebuild a service's jar after every change to it. Otherwise `--build` quietly packages the old
jar (this once left the API gateway without the admin routes added hours earlier).

```bash
cp .env.example .env            # first time only; Compose refuses to start without it

# Shared libraries must be installed before any service will resolve.
for m in observability security resilience outbox; do
  (cd shared/homefix-shared-$m && mvn -q -DskipTests -Djacoco.skip=true install)
done

# Every service, one at a time (see Memory below), or just the one you changed.
for s in services/*/; do
  (cd "$s" && mvn -q -DskipTests -Djacoco.skip=true package) || break
done

# Start Kafka (1.1) first, then everything else. --wait gates on each container's own health
# check and exits non-zero if anything fails to come up.
docker compose -f docker-compose.core.yml up -d --build --wait

# After changing one service: rebuild its jar, then its image and container.
(cd services/auth-service && mvn -q -DskipTests -Djacoco.skip=true package)
docker compose -f docker-compose.core.yml up -d --build --wait auth-service
```

A cold start takes 4-6 minutes on a 6-core machine: twenty JVMs boot at once and are CPU-bound.
The health checks allow a 300 s start period, so `--wait` does not give up on a service that is
merely slow.

### 1.3 Seed a new database (once)

Everything below persists in Postgres, so it is needed once per database, not per restart.
Run it after the stack is up, because the services' migrations create the tables.

```bash
. docker/local-infra.sh                                # psql_q, PSQL and PG* settings
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-catalog.sql           # categories, skill tags
bash docker/seed-pricing.sh                                     # pricing for every subcategory
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-provider-profiles.sql # 2 approved providers in Ara
"$PSQL" -v ON_ERROR_STOP=1 -f docker/seed-tenants.sql           # demo agency: Ara Home Services
```

Keep that order: the provider profiles need the catalog, and the Tenant seed needs the catalog,
the two provider profiles and the `tenantadmin` account. Provider One gets the plumbing and
electrical skills, Provider Two cleaning; both join the demo agency's team. The SQL seeds are
idempotent.

The test accounts in section 3 need no script: auth-service creates them at startup when
`DEV_SEED_ENABLED=true`. `docker/seed-test-users.sh` remains for extra OTP-only accounts.

Two check scripts exercise the running stack end to end and exit non-zero on any failure:

```bash
bash docker/smoke-flows.sh          # every service's main paths: 78/78
bash docker/verify-outbox-flow.sh   # book -> dispatch -> job -> payment -> wallet credit, then a
                                    # booking nobody accepts falling back to the demo agency
                                    # (sections 4 and 4.1); 53 checks at the last run
```

Right after `smoke-flows.sh` the first match can take up to a minute: smoke's emergency booking
may still hold Provider One's offer, and dispatch now waits for a busy provider instead of
failing the booking.

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

**Run Maven builds one at a time.** Each build is a JVM of its own on top of the running stack;
six in parallel once exhausted host memory and froze the Docker engine until Docker Desktop was
restarted. The sequential loop in 1.2 is deliberate.

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
| Admin portal | http://localhost:5175 | Staff: operations, finance, support; agency (Tenant) admins see only their agency's Requests, Team and Jobs |

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
`.env.example`. Change it there and restart auth-service to rotate all ten at once.

| Username | Password | Mobile number | Role | What it is for |
|----------|----------|---------------|------|----------------|
| `customer` | `HomeFix@2026` | `+919000000001` | CUSTOMER | Primary test customer: browse, book, pay, review |
| `customer2` | `HomeFix@2026` | `+919000000002` | CUSTOMER | Second customer, for chat and review counterparties |
| `provider` | `HomeFix@2026` | `+919000000011` | SERVICE_PROVIDER | Primary test provider (plumbing, electrical): accept offers, run the job, get paid |
| `provider2` | `HomeFix@2026` | `+919000000012` | SERVICE_PROVIDER | Second provider (cleaning); with Provider One, the demo agency's team |
| `admin` | `HomeFix@2026` | `+919000000021` | ADMIN | Operations console: every admin module except system config; manages Tenants |
| `superadmin` | `HomeFix@2026` | `+919000000022` | SUPER_ADMIN + ADMIN | Adds System Configuration to the admin surface |
| `finance` | `HomeFix@2026` | `+919000000023` | FINANCE_ADMIN | Payment reconciliation and settlement reports |
| `dispatcher` | `HomeFix@2026` | `+919000000024` | DISPATCHER | Manual assignment and dispatch weight tuning |
| `support` | `HomeFix@2026` | `+919000000025` | SUPPORT_AGENT | Complaint triage, status changes, refunds |
| `tenantadmin` | `HomeFix@2026` | `+919000000031` | TENANT_ADMIN | Administers the demo agency "Ara Home Services" in the Admin Portal: assignment queue, team, jobs |

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

This is the shortest path through today's whole flow: book, match, run the job, pay, credit the
provider. It assumes the seeds in 1.3 have run and Kafka is up. Everything goes through the
gateway, the way the apps call it. `docker/verify-outbox-flow.sh` runs this same flow (and the
Tenant fallback in 4.1) with assertions, so run that when you only want to know it works.

```bash
GW=http://localhost:8080; J='Content-Type: application/json'
login() { curl -s -X POST http://localhost:8081/auth/login/password -H "$J" \
  -d "{\"username\":\"$1\",\"password\":\"HomeFix@2026\"}"; }
field() { sed "s/.*\"$1\":\"\([^\"]*\)\".*/\1/"; }
CS=$(login customer); C="Authorization: Bearer $(echo "$CS" | field accessToken)"
CID=$(echo "$CS" | field userId)
P="Authorization: Bearer $(login provider | field accessToken)"

# 1. A service address inside Provider One's radius in Ara (a customer may keep ten)
ADDR=$(curl -s -X POST "$GW/customers/$CID/addresses" -H "$C" -H "$J" \
  -d '{"label":"Home","lat":25.5571,"lng":84.6612}' | field addressId)

# 2. Pick Plumbing > Tap / Faucet Repair from the catalog
curl -s "$GW/catalog/categories" -H "$C" | head -c 600

# 3. Create a scheduled booking (at least the minimum lead time ahead), then confirm it.
#    Confirmation is what starts the search; the customer app does both.
B=$(curl -s -X POST "$GW/bookings" -H "$C" -H "$J" -d "{\"addressId\":\"$ADDR\",
  \"categoryId\":\"<id>\",\"subcategoryId\":\"<id>\",\"emergency\":false,
  \"scheduledAt\":\"$(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"description\":\"Tap dripping\"}")
REF=$(echo "$B" | field reference); ID=$(echo "$B" | field bookingId)
curl -s -X POST "$GW/bookings/$REF/confirmation" -H "$C"

# 4. The provider sees the offer (the app polls this every 5 s) and accepts it
curl -s "$GW/dispatch/offers" -H "$P"
curl -s -X POST "$GW/dispatch/offers/$ID/accept" -H "$P"        # -> PROVIDER_ACCEPTED

# 5. Run the job as the provider app does
curl -s -X POST "$GW/bookings/$ID/on-the-way" -H "$P"
curl -s -X POST "$GW/locations/$ID" -H "$P" -H "$J" -d '{"latitude":25.559,"longitude":84.663}'
curl -s "$GW/locations/$ID" -H "$C"                              # what the customer's map reads
curl -s -X POST "$GW/bookings/$ID/arrived" -H "$P"
curl -s -X POST "$GW/bookings/$ID/photos" -H "$P" -F type=BEFORE_PHOTO -F "file=@before.jpg;type=image/jpeg"
curl -s -X POST "$GW/bookings/$ID/start" -H "$P"                 # 422 without a before-photo
curl -s -X POST "$GW/bookings/$ID/photos" -H "$P" -F type=AFTER_PHOTO -F "file=@after.jpg;type=image/jpeg"
curl -s -X POST "$GW/bookings/$ID/complete" -H "$P"              # 422 without an after-photo

# 6. The customer pays: only the booking and the method; the amount comes from the booking
curl -s -X POST "$GW/payments" -H "$C" -H "$J" -d "{\"bookingId\":\"$ID\",\"method\":\"UPI\"}"
sleep 3; curl -s "$GW/bookings/$ID" -H "$C" | grep -o '"status":"[A-Z_]*"'   # PAYMENT_COMPLETED

# 7. The provider's wallet was credited once for this booking
. docker/local-infra.sh
psql_q "SELECT gross, platform_fee, net FROM provider.provider_earning WHERE booking_id = '$ID'"
```

What happens behind each step, briefly: confirmation publishes `BookingCreated`; dispatch
resolves the address and skill tags, finds Provider One eligible and offers the job (an offer
lives 60 s in Redis); acceptance moves the booking to `PROVIDER_ACCEPTED`. Each milestone
command is refused (404) for anyone but the assigned provider. Payment moves the booking to
`PAYMENT_PENDING`, the local **payment simulator** settles the charge through the real
signed-callback path, `PaymentCompleted` moves the booking to `PAYMENT_COMPLETED` and triggers
the invoice, the review prompt and the notifications, and payment-service credits the
provider's net earning (80% by default) to provider-service. In the apps the same run is:
customer books on :5173, provider accepts on :5174 and works through On the way, Arrived,
photos, Start and Complete; the customer presses Pay on the booking or tracking screen; the
provider app shows "Paid — your earnings have been credited".

If the provider does not accept within the window, or declines, the booking falls back to the
demo agency (4.1); a booking nobody can take ends in `SEARCHING_FAILED`.

### 4.1 Tenant Portal walkthrough

A Tenant is a service agency covering an area for a set of categories, with its own team of
providers. The seed creates **Ara Home Services**, covering 20 km around Ara for every active
category, administered by `tenantadmin`, with both test providers on its team.

1. Make a booking fall back. Book a plumbing job in Ara as above, then, as `provider` in the
   provider app (or with `POST /dispatch/offers/{id}/decline`), **decline the offer**. Provider
   One is the only nearby provider with the plumbing skill, so dispatch runs out of candidates
   and booking-service routes the booking to the covering Tenant: status
   `AWAITING_ASSIGNMENT`, no cancellation sent to the customer. (Letting the offer expire
   works too, after the radius cycles finish.)
2. Sign in to http://localhost:5175 as `tenantadmin` / `HomeFix@2026`. The Tenant Portal shows
   only three pages:
   - **Requests**: the assignment queue, refreshed every 15 s with a badge for new requests.
     Assign opens the team picker; only members who are approved and not under review are
     assignable. The first assignment wins; a later one gets 409 `BOOKING_NOT_ASSIGNABLE`.
   - **Team**: the agency's providers with availability; add one by mobile number, or remove one.
   - **Jobs**: every booking taken by the agency's providers, whichever path matched them.
3. Assign `Provider One`. The booking goes to `PROVIDER_ASSIGNED` and the provider app shows an
   Accept / Decline card for it. Accept moves it to `PROVIDER_ACCEPTED` and the job continues as
   in section 4; Decline returns it to this agency's queue.
4. An unassigned or unanswered booking fails at the deadline (`TENANT_ASSIGNMENT_TIMEOUT`, 60
   minutes from first queuing; a decline does not reset it).

Platform admins (`admin`, `superadmin`) manage agencies in the Admin Portal's **Tenants** module:
create, edit coverage, categories and status, add or remove team providers, and add or remove
Tenant admins by mobile number (which grants or revokes `TENANT_ADMIN`). A revoked admin's current access token keeps the role
for up to 15 minutes.

---

## 5. What works and what does not, today

Knowing this up front saves debugging time. [CODEBASE_REVIEW.md](../CODEBASE_REVIEW.md)
sections 16 to 18 have the detail and the verification behind it.

**Works end to end** (the check scripts in section 1.3 cover the first eight)

- OTP and password sign-in, lockout, rate limiting; token rotation with replay detection;
  logout that revokes the whole token family; suspended or deactivated accounts are refused
- Catalog browsing; price estimates from persisted pricing parameters; coupons quoted by
  promotion-service and charged
- Booking creation (scheduled and emergency, always with a saved address) and confirmation;
  booking history and detail for the customer
- **Provider matching**: eligible providers in range, offers answered from the provider app, a
  busy provider waited for rather than skipped; acceptance moves the booking to
  `PROVIDER_ACCEPTED`
- **Tenant fallback**: a booking nobody accepts goes to the covering agency's queue, is assigned
  in the Tenant Portal, and accepted or declined by the provider (4.1)
- **Job execution**: on the way, live position shared by the provider app, arrived, before and
  after photos enforced, start, pause, parts quote with customer approve / decline, complete
- **Payment, locally**: the customer pays from the app; the simulator settles it through the
  signed-callback path; the booking reaches `PAYMENT_COMPLETED`; invoice, review prompt and
  chat close-out run from `PaymentCompleted`; the provider's wallet is credited once per booking
- Domain events from outbox to Kafka to every consumer; Flyway migrations for every schema
- **Admin Portal**: every module has a back end in the owning service (users, providers,
  verification, bookings, payments and refunds, complaints, reviews, coupons, pricing,
  dispatch weights, reports, notification templates, audit log, system configuration) with
  role tiers enforced; plus Tenants and the Tenant Portal
- All three web apps build, serve and proxy correctly

**Still not working**

| Area | Symptom | Review reference |
| --- | --- | --- |
| Chat | The apps call the wrong endpoints and open a raw WebSocket against a STOMP server no route serves | 17.5 |
| Provider onboarding | No screens for base location, radius, availability, skills or documents, so a provider who signs up in the app can never be matched; only the seeded providers can | 17.5 |
| Real external providers | Payment gateways (outside the local simulator), SMS/email/push, document storage, background checks and geocoding are logging or in-memory stubs | 16.12 |
| Notifications | Every channel is a logging adapter; push and email also have no device tokens or email addresses to send to. OTP codes go to `docker/dev-sms/dev-sms.log` | 16.12 |
| Complaint refunds | Recorded once per complaint and shown as succeeded, but the refund adapter only logs (`stub_rf_…` reference): no money is returned through payment-service | 17.3 |
| Ratings | Reviews never reach provider-service, so a provider's aggregate rating (used in matching) never changes | 17.5 |
| Location privacy | Any signed-in customer or provider with a booking id can read that booking's provider position | 17.5 |
| Stuck searches | A dispatch failure after `BookingCreated` is acknowledged (outage, restart mid-search) leaves the booking in `SEARCHING_PROVIDER` with no sweeper | 17.5 |
| Coupon redemption | Coupons are quoted and charged, but nothing calls promotion-service's redeem, so usage limits are never consumed | 16.12 |
| Saved addresses | customer-service cannot list a customer's addresses, so the app can only reuse ones saved from that device | 16.12 |
| Admin display fields | Customer, provider and reviewer names and mobile numbers are blank in several admin tables; dispatch weights reset on restart | 17.5 |
| Parts-quote timeout | The 60-minute auto-resolve exists but nothing schedules it | ARCHITECTURE 5.4 |
| Native mobile builds | Android and iOS projects exist but were not built on this machine (no Android SDK, JDK 25 too new for Gradle 8.11; iOS needs macOS). See `frontend/MOBILE.md` | 18 |
| Tenant alerts | Tenant admins are not pushed new requests; the portal polls every 15 s | 18 |

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
- One shared service credential for every `/internal/**` call (`INTERNAL_API_KEY`)
- A payment simulator that reports every charge as paid without moving money
  (`PAYMENT_SIMULATOR_ENABLED`, signed with a default secret); Helm never enables it

Never reuse any of these outside a local machine, and never point this Compose file at a shared database. The production path for all of them is AWS Secrets Manager, described in [ARCHITECTURE.md](ARCHITECTURE.md) section 10.2.
