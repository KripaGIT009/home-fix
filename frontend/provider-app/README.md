# HomeFix Provider App

React + TypeScript + Vite app for HomeFix service providers: take job offers,
answer jobs their agency assigns, run a job from "on my way" to completion
with photos, parts and location sharing, follow the customer's payment, and
track earnings, settlements and verification. Mobile-first, built with the same
stack and conventions as the Customer App (Requirement 28.2). It runs in the
browser and ships as Android and iOS apps from the same code (Capacitor, see
[Mobile builds](#mobile-builds-android-and-ios)).

Sign-in is mobile number + OTP (Google when configured). Registration through
this app always asks for the `SERVICE_PROVIDER` role; the Booking, Dispatch and
Provider services decide what each call may do. Providers who belong to an
agency (a Tenant) use the same app; they additionally see jobs the agency
assigns them.

## Stack

- **React 18 + TypeScript** (strict mode)
- **Vite** build/dev server
- **TanStack Query** — server state (polling drives offers, assignments and payment status)
- **Zustand** — client state (auth)
- **React Router** — navigation
- **Material UI** — components/theme (mobile-first)
- **React Hook Form + Zod** — forms and validation
- **Axios** — HTTP client with JWT Bearer + `X-Correlation-ID` injection
- **Capacitor 7** — Android and iOS shells; `@capacitor/geolocation` for native location

## Screens

All routes except Splash and Login require a signed-in session. The bottom
navigation carries Dashboard, Earnings, Verification and Profile.

| Route | Screen | What it does |
| --- | --- | --- |
| `/` | Splash | Brand splash, then Dashboard or Login |
| `/login` | Login | Mobile number + OTP with resend countdown and lockout handling; Google sign-in when `VITE_GOOGLE_CLIENT_ID` is set |
| `/dashboard` | Dashboard | Open job offers (polled every 5 s, shown only while there are any), jobs the agency assigned and awaiting your answer (listed first), the earnings summary (wallet balance, today's net, jobs done) and active jobs with status indicators (refreshed every 15 s). After you decline an assigned job it says where the job went |
| `/jobs/:bookingId/request` | Job request | An incoming offer with a live countdown; **Accept** or **Decline**. Accept is disabled at zero and the server refuses a late accept with `OFFER_EXPIRED` |
| `/jobs/:bookingId` | Job details | Customer name, address, description, customer media and a navigation deep link; routes on to the active-job or completion flow. An agency-assigned job shows who assigned it with **Accept** / **Decline** |
| `/jobs/:bookingId/active` | Active job | See [below](#job-flow) |
| `/jobs/:bookingId/complete` | Job completion | Net duration, parts and final price, plus the customer's payment status until the earnings are credited |
| `/earnings` | Earnings & settlements | Paginated per-job earnings (gross, fee, net), a settlement request form (amount up to the available balance, verified bank account required) and settlement history |
| `/verification` | Verification status | Current verification state, progress through the verification state machine, document checklist and next steps |
| `/profile` | Profile | Identity, live verification badge and links to the other areas |

### Job flow

1. **Get the job** — either accept an automatic offer (Job request), or, for a
   job your agency assigned (`PROVIDER_ASSIGNED`), **Accept** or **Decline** it
   on Job details or Active job (the dashboard card, marked "Accept or
   decline", opens the job). The assigned job is
   re-read every 5 s while it waits, so a cancellation shows up on its own.
   Accepting turns it into an ordinary accepted job; declining (confirmed
   first) hands it back to the agency and returns you to the dashboard. Only
   the assigned provider can answer; anyone else gets 404.
2. **I'm on my way** → `PROVIDER_ON_THE_WAY`. While on the way the app shares
   your position with the Location Service (at most every 10 s) so the
   customer's map moves. In the Android and iOS apps the position comes from
   `@capacitor/geolocation` and the OS permission prompt; in the browser from
   `navigator.geolocation`. A status line says whether sharing works, and a
   refused permission is explained rather than blocking the job.
3. **I've arrived** → `PROVIDER_ARRIVED`.
4. **Before photo**, then **Start** → `JOB_STARTED` (start is disabled until a
   before photo exists; the Booking Service is the authoritative check).
   Photos come from the file picker; the native apps add a **Take photo**
   button that opens the camera.
5. While working: **pause/resume** with a mandatory reason, and **parts**
   (name, quantity, unit cost). Added parts re-price the job and ask the
   customer to approve the new total; the job is polled while they decide.
6. **After photo**, then **Complete** → `JOB_COMPLETED`, and on to Job
   completion.
7. **Payment status** — the completion and detail screens follow the job until
   the customer pays (`PAYMENT_COMPLETED`) and say when the earnings have been
   credited to the wallet.

## Getting started

```bash
npm install
cp .env.example .env    # optional: adjust proxy targets
npm run dev             # http://localhost:5174
```

The dev server proxies `/api/auth/*` straight to the Auth Service
(`VITE_AUTH_SERVICE_URL`, default `localhost:8081`) and every other `/api/*`
call to the API Gateway (`VITE_API_GATEWAY_URL`, default `localhost:8080`),
stripping the `/api` prefix. See `vite.config.ts`. The port (5174) differs from
the Customer App (5173) so both can run side by side. If the Docker stack is
running, its provider-app container already holds 5174; stop it
(`docker compose -f docker-compose.core.yml stop provider-app`) to run the dev
server there.

## Environment variables

Vite bakes these into the bundle at build time. See `.env.example`.

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | Base for API calls. Relative in the browser (proxied); **absolute in native builds** |
| `VITE_AUTH_BASE_URL` | `VITE_API_BASE_URL` | Base for Auth Service calls. Native builds point it at the Auth Service itself |
| `VITE_GOOGLE_CLIENT_ID` | empty | Enables Google sign-in; must equal the Auth Service's `GOOGLE_CLIENT_ID` |
| `VITE_API_GATEWAY_URL` | `http://localhost:8080` | Dev-server proxy target only |
| `VITE_AUTH_SERVICE_URL` | `http://localhost:8081` | Dev-server proxy target only |

A native build refuses to start, and names the offending variable on screen,
when `VITE_API_BASE_URL` or `VITE_AUTH_BASE_URL` is not an absolute URL
(`assertRuntimeConfig` in `src/config/env.ts`). The provider app has no
realtime stream, so it has no `VITE_REALTIME_BASE_URL`.

## Scripts

| Script | Purpose |
| --- | --- |
| `npm run dev` | Start the dev server with API proxy |
| `npm run build` | Type-check and produce a production build in `dist/` |
| `npm run preview` | Preview the production build |
| `npm run lint` / `lint:fix` | Run ESLint (zero warnings allowed) / auto-fix |
| `npm run format` / `format:check` | Format with Prettier / check formatting |
| `npm run typecheck` | Type-check without emitting |
| `npm run mobile:sync` | Build, then copy the bundle into both native projects (`cap sync`) |
| `npm run mobile:sync:android` / `mobile:sync:ios` | Same, for one platform |
| `npm run mobile:open:android` / `mobile:open:ios` | Open the native project in Android Studio / Xcode |
| `npm run mobile:run:android` | Build, sync and launch on an emulator or device |

## Mobile builds (Android and iOS)

App id `com.homefix.provider`, app name **HomeFix Pro**. The native projects
live in `android/` and `ios/`. Set absolute backend URLs in `.env` first (the
commented native section of `.env.example` has emulator and LAN examples), then:

```bash
npm run mobile:sync
npm run mobile:open:android     # or mobile:open:ios (macOS + Xcode only)
```

The native apps declare location and camera permissions (Android manifest,
iOS `Info.plist` usage strings). Prerequisites, device networking,
cleartext/ATS rules and the release checklist are in
[`frontend/MOBILE.md`](../MOBILE.md).

## Running against the local stack

The whole platform (services + all three web apps) runs from the repository
root; [`docs/LOCAL_ACCESS.md`](../../docs/LOCAL_ACCESS.md) covers the one-time
setup, seeding and test accounts.

```bash
docker compose -f docker-compose.core.yml up -d --build --wait
```

The `provider-app` image (`Dockerfile`) builds the bundle with the default
`/api` base and serves it from nginx on <http://localhost:5174>. `nginx.conf`
proxies `/api/auth/*` to `auth-service:8081` and everything else under `/api/`
to `api-gateway:8080`. The build copies the app directory, so keep native
(absolute) URLs out of a local `.env` when building the image.

Sign in with the seeded provider `+919000000011` (`provider2` is
`+919000000012`; both belong to the demo agency once `docker/seed-tenants.sql`
has run). The local SMS gateway does not send texts, so read the code with:

```bash
bash docker/otp.sh
```

## Design system

`src/lib/theme.ts` is the single source of visual truth: brand palette, type
scale, elevations and MUI component overrides. Screens compose those tokens
(`brand.*`, theme palette keys) rather than hard-coding colours or radii, so the
whole app restyles from that one file.

Shared layout pieces:

- `components/AppShell.tsx` — sticky app bar (branded on top-level tabs, titled
  with a back button on drill-in screens) plus the fixed bottom navigation.
- `components/BrandLogo.tsx` — the HomeFix mark and wordmark.
- `components/QueryStateView.tsx` — the standard loading / error / empty states.

## Project structure

```text
src/
  api/            # Axios clients (gateway + auth), token bridge
  components/     # Shared UI (AppShell, error boundary, guards, placeholders)
  config/         # Typed env access + native-build URL guard
  features/
    auth/         # Splash, Login/OTP, Google sign-in, lockout
    dashboard/    # Offers, assigned jobs, earnings summary, active jobs
    jobs/         # Job request, details, active job, completion, photos,
                  # parts, pause/resume, assignment answer, location sharing
    earnings/     # Earnings history and settlement requests
    verification/ # Verification state machine status
    profile/      # Profile
  lib/            # Query client, theme, booking status, formatting, correlation ID
  stores/         # Zustand stores (auth)
  router.tsx      # Route definitions
  App.tsx         # Provider composition
  main.tsx        # Entry point (runs the native config check first)
android/, ios/    # Capacitor native projects (see frontend/MOBILE.md)
```
