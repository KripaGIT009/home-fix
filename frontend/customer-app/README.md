# HomeFix Customer App

React + TypeScript + Vite single-page app for HomeFix customers: service
discovery, booking, live tracking, payment, and history.

## Stack

- **React 18 + TypeScript** (strict mode)
- **Vite** build/dev server
- **TanStack Query** — server state
- **Zustand** — client state (auth, active booking)
- **React Router** — navigation
- **Material UI** — components/theme (mobile-first)
- **React Hook Form + Zod** — forms and validation
- **Axios** — HTTP client with JWT Bearer + `X-Correlation-ID` injection

## Getting started

```bash
npm install
cp .env.example .env    # adjust proxy targets if needed
npm run dev             # http://localhost:5173
```

The dev server proxies `/api/*` to the API Gateway (`localhost:8080`) and
`/api/auth/*` directly to the Auth Service (`localhost:8081`). See
`vite.config.ts`.

## Scripts

| Script | Purpose |
|---|---|
| `npm run dev` | Start the dev server with API proxy |
| `npm run build` | Type-check and produce a production build in `dist/` |
| `npm run preview` | Preview the production build |
| `npm run lint` | Run ESLint (zero warnings allowed) |
| `npm run format` | Format with Prettier |
| `npm run typecheck` | Type-check without emitting |

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

## Running against the local stack

The whole platform (services + all three SPAs) runs from the repository root:

```bash
docker compose -f docker-compose.core.yml up -d --build
bash docker/seed-pricing.sh     # pricing parameters, required for estimates
```

Log in with any Indian mobile number — the local SMS gateway logs the OTP
instead of sending it:

```bash
docker logs homefix-core-auth-service-1 | grep "DEV SMS"
```

## Project structure

```
src/
  api/         # Axios client, token bridge
  components/  # Shared UI (error boundary, guards, placeholders)
  config/      # Typed env access
  lib/         # Query client, theme, correlation ID helpers
  stores/      # Zustand stores (auth, booking)
  router.tsx   # Route definitions
  App.tsx      # Provider composition
  main.tsx     # Entry point
```
