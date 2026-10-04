# HomeFix Customer App

React + TypeScript + Vite app for HomeFix customers: find a service, book it,
follow the professional to the door, approve price changes, pay, and look back
at past bookings. It runs in the browser and ships as Android and iOS apps
from the same code (Capacitor, see [Mobile builds](#mobile-builds-android-and-ios)).

Anyone can sign in with a mobile number (OTP), with email and password, or, when
configured, Google. A new customer can sign up with an email verified by an emailed
code, and reset a forgotten password.
A new number is registered as a `CUSTOMER`; the Auth Service decides what each
call may do, the app itself does not gate screens by role.

## Stack

- **React 18 + TypeScript** (strict mode)
- **Vite** build/dev server
- **TanStack Query** — server state
- **Zustand** — client state (auth, active booking)
- **React Router** — navigation
- **Material UI** — components/theme (mobile-first, with a desktop layout)
- **React Hook Form + Zod** — forms and validation
- **Axios** — HTTP client with JWT Bearer + `X-Correlation-ID` injection
- **Capacitor 7** — Android and iOS shells

## Screens

All routes except Splash, Login, Sign-up and Forgot password require a signed-in session (`RequireAuth`).
The bottom tab bar (mobile) and the top navigation (desktop) carry Home,
Bookings, Help and Profile.

| Route | Screen | What it does |
| --- | --- | --- |
| `/` | Splash | Brand splash, then Home or Login depending on the session |
| `/login` | Login | **Mobile number** tab (OTP with resend countdown and lockout handling) and **Email** tab (email + password, "Forgot password?", "Create account"); Google sign-in under both when `VITE_GOOGLE_CLIENT_ID` is set |
| `/signup`, `/signup/verify` | Email sign-up | Name, email, mobile and password, then the 6-digit code emailed to the address (locally in `docker/dev-mail/dev-mail.log`) |
| `/forgot-password` | Password reset | Email, then the emailed code and a new password; every session of the account ends |
| `/home` | Home (storefront) | See [below](#home-storefront) |
| `/categories/:categoryId` | Subcategory | The category's services as rows with "from" price, typical duration and 24×7 flag; desktop adds a jump rail and a sticky promise card |
| `/book/:subcategoryId` | Service request | Where (saved address, manual entry or "use my current location"), when (≥ 2 h ahead, ≤ 90 days), what (description, emergency toggle, up to 10 photos/videos — JPEG/PNG/MP4/MOV, ≤ 50 MB each; a **Take a photo** button in the native apps) |
| `/book/:subcategoryId/estimate` | Price estimate | Itemised estimate from the Pricing Engine, coupon check, confirm creates the booking; a pricing outage blocks confirmation |
| `/book/:bookingId/professionals` | Available professionals | Verified candidates with rating, jobs done, distance, ETA and starting price, re-sortable. Informational only: dispatch offers the job itself |
| `/bookings/:bookingId/track` | Live tracking | See [below](#live-tracking) |
| `/bookings/:bookingId/chat` | Chat | Booking chat over WebSocket; no phone numbers exchanged, read-only once the channel closes |
| `/bookings/:bookingId` | Booking detail | Progress timeline, links to tracking and chat while active, pay a completed job, download the invoice PDF |
| `/history` | Service history | Paginated bookings with status chip, date and amount |
| `/profile` | Profile | Signed-in identity, an **Email & password** card (add and verify an email, set or change the password) and links into the rest of the app |
| `/help` | Help & support | Inline answers on pricing, verification, cancellation and payment, plus routes to a booking or the 24×7 path |

### Home (storefront)

Everything on the storefront is derived on the client from the one cached
`GET /catalog/categories` response (`features/catalog/storefront.ts`); nothing
suggests ratings, volumes or discounts the catalog does not carry.

- **Category grid** — "What are you looking for?" card with one tile per
  category (plus 24×7 help when any service offers it) and a 2×2 mosaic of
  featured services.
- **Spotlight carousel** — promo banners built from real catalog data: 24×7
  help, the lowest "from" price in each category, and quick fixes.
- **Service rails** — a quick-fixes rail (visits of 60 minutes or less) and one
  rail per category, each with "See all".
- **Header search** — the search field in the desktop header (and at the top of
  Home on mobile) writes `?q=` into the Home URL and filters the cached catalog
  as you type; `?view=emergency` shows only 24×7 services.

### Live tracking

The booking is re-read every few seconds and the screen shows its current
phase (`features/tracking/progress.ts`):

| Status | Shown as |
| --- | --- |
| `SEARCHING_PROVIDER` | Finding a professional (searching radar) |
| `AWAITING_ASSIGNMENT` | Still finding a professional, with "a local partner is assigning one" copy |
| `PROVIDER_ASSIGNED` | Professional assigned by the partner, waiting for their confirmation (no chat yet) |
| `SEARCHING_FAILED` | Nobody available |
| `PROVIDER_ACCEPTED` … `PROVIDER_ARRIVED` | Live map with ETA, seeded from the Location Service snapshot and then streamed over SSE; a location older than 60 s is flagged |
| `JOB_STARTED`, `JOB_PAUSED` | Work in progress / paused |
| `ADDITIONAL_QUOTE_REQUIRED`, `CUSTOMER_APPROVAL_PENDING` | **Quote approval**: the parts the professional added and the new total; approve (work resumes) or decline (the job completes at the original price). Unanswered, it auto-completes at the original price |
| `JOB_COMPLETED`, `CUSTOMER_CONFIRMED`, `PAYMENT_PENDING` | **Payment panel**: pick UPI, card, net banking, wallet or cash and pay; the app follows the booking until `PAYMENT_COMPLETED` and offers a retry when a payment is declined |
| `PAYMENT_COMPLETED` | Paid, with follow-up actions |

The payment controls (`features/payment/PaymentPanel.tsx`) appear on both the
tracking screen and the booking detail. The app sends only the booking and the
method; the Payment Service reads the amount from the Booking Service.

## Getting started

```bash
npm install
cp .env.example .env    # optional: adjust proxy targets
npm run dev             # http://localhost:5173
```

The dev server proxies `/api/auth/*` straight to the Auth Service
(`VITE_AUTH_SERVICE_URL`, default `localhost:8081`) and every other `/api/*`
call to the API Gateway (`VITE_API_GATEWAY_URL`, default `localhost:8080`),
stripping the `/api` prefix and forwarding WebSocket/SSE upgrades. See
`vite.config.ts`.

If the Docker stack is running, its customer-app container already holds port
5173 and Vite will move to the next free port; stop the container
(`docker compose -f docker-compose.core.yml stop customer-app`) if you want the
dev server on 5173.

## Environment variables

Vite bakes these into the bundle at build time. See `.env.example`.

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | Base for API calls. Relative in the browser (proxied); **absolute in native builds** |
| `VITE_AUTH_BASE_URL` | `VITE_API_BASE_URL` | Base for Auth Service calls (OTP, email sign-up and sign-in, password reset, `/auth/me`, social login, refresh, logout). Native builds point it at the Auth Service itself |
| `VITE_REALTIME_BASE_URL` | `VITE_API_BASE_URL` | Base for the location SSE stream and chat WebSocket |
| `VITE_GOOGLE_CLIENT_ID` | empty | Enables Google sign-in; must equal the Auth Service's `GOOGLE_CLIENT_ID` |
| `VITE_PROVIDER_APP_URL` | `http://localhost:5174` | Where the footer's "Join as a professional" link points (the Provider App) |
| `VITE_API_GATEWAY_URL` | `http://localhost:8080` | Dev-server proxy target only |
| `VITE_AUTH_SERVICE_URL` | `http://localhost:8081` | Dev-server proxy target only |

A native build refuses to start, and names the offending variable on screen,
when `VITE_API_BASE_URL`, `VITE_AUTH_BASE_URL` or `VITE_REALTIME_BASE_URL` is
not an absolute URL (`assertRuntimeConfig` in `src/config/env.ts`).

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

App id `com.homefix.customer`, app name **HomeFix**. The native projects live in
`android/` and `ios/`. Set absolute backend URLs in `.env` first (the commented
native section of `.env.example` has emulator and LAN examples), then:

```bash
npm run mobile:sync
npm run mobile:open:android     # or mobile:open:ios (macOS + Xcode only)
```

Prerequisites, device networking, cleartext/ATS rules, permissions and the
release checklist are in [`frontend/MOBILE.md`](../MOBILE.md).

## Running against the local stack

The whole platform (services + all three web apps) runs from the repository
root; [`docs/LOCAL_ACCESS.md`](../../docs/LOCAL_ACCESS.md) covers the one-time
setup, seeding and test accounts.

```bash
docker compose -f docker-compose.core.yml up -d --build --wait
```

The `customer-app` image (`Dockerfile`) builds the bundle with the default
`/api` base and serves it from nginx on <http://localhost:5173>. `nginx.conf`
proxies `/api/auth/*` to `auth-service:8081` and everything else under `/api/`
to `api-gateway:8080`, with WebSocket/SSE support, long-lived caching for
`/assets/` and no caching for `index.html`. The build copies the app directory,
so keep native (absolute) URLs out of a local `.env` when building the image.

Sign in with the seeded customer `+919000000001` (or any Indian mobile number);
the local SMS gateway does not send texts, so read the code with:

```bash
bash docker/otp.sh
```

## Design system

`src/lib/theme.ts` is the single source of visual truth: brand palette, type
scale, elevations, layout widths and MUI component overrides. Screens compose
those tokens (`brand.*`, theme palette keys) rather than hard-coding colours or
radii, so the whole app restyles from that one file.

Shared layout pieces:

- `components/AppShell.tsx` — responsive shell: desktop top navigation with the
  service search and a centred content area; on mobile a compact app bar
  (branded on top-level tabs, titled with a back button on drill-in screens)
  and the fixed bottom tab bar. Top-level pages also get the site footer.
- `components/ServiceSearchField.tsx` — the header/home search bound to `?q=`.
- `components/ProgressTimeline.tsx` — the booking journey timeline.
- `components/BrandLogo.tsx` — the HomeFix mark and wordmark.
- `components/QueryStateView.tsx`, `components/StateViews.tsx` — the standard
  loading / error / empty states.

## Project structure

```text
src/
  api/          # Axios clients (gateway + auth), token bridge
  components/   # Shared UI (AppShell, guards, search, timeline, state views)
  config/       # Typed env access + native-build URL guard
  features/
    auth/       # Splash, Login (OTP + email), sign-up, reset, Google sign-in, lockout
    catalog/    # Storefront home, rails, carousel, subcategory screen
    booking/    # Service request, media upload, price estimate
    tracking/   # Available professionals, live tracking, map, chat
    payment/    # Payment panel and Payment Service bindings
    history/    # Service history, booking detail, status labels
    profile/    # Profile
    help/       # Help & support
  lib/          # Query client, theme, formatting, realtime, correlation ID
  stores/       # Zustand stores (auth, booking)
  router.tsx    # Route definitions
  App.tsx       # Provider composition
  main.tsx      # Entry point (runs the native config check first)
android/, ios/  # Capacitor native projects (see frontend/MOBILE.md)
```
