# HomeFix Admin Portal

Desktop-first administrative web application for the HomeFix platform. Built
with the same stack as the Customer and Provider apps (React + TypeScript +
Vite, React Router, TanStack Query, Zustand, Material UI, React Hook Form +
Zod), but laid out for large administrative screens with a persistent sidebar
navigation rather than a mobile-first bottom nav (Requirement 28.3).

## Scope

- **Dashboard** — 7 live-refresh metrics polled every 60 s (Requirement 19.1).
- **Operational modules** (Requirement 19.2): User Management, Provider
  Management, Verification Queue, Service Category Management, Pricing
  Configuration, Dispatch Rule Configuration, Booking Management, Payment and
  Refund Management, Complaint Management, Review Moderation, Coupon
  Management, Notification Templates, Report Generation, Audit Logs, and
  System Configuration.
- **RBAC** (Requirement 19.6/19.7): `ADMIN` can access every module except
  System Configuration; `SUPER_ADMIN` can access everything. System
  Configuration is hidden from the sidebar and route-guarded for ADMIN.
- **Verification Queue** (Requirement 19.3): providers in `DOCUMENT_SUBMITTED`
  status sorted oldest-first, with an inline document viewer that renders PDFs
  in an embedded frame without a separate download.
- **Dispatch weight validation** (Requirement 19.5 / 8.4): each weight in
  0.0–1.0 and all weights sum to exactly 1.0 before the update can be saved.

## Development

```bash
npm install
npm run dev        # http://localhost:5175
npm run lint       # ESLint (max-warnings 0)
npm run typecheck  # tsc --noEmit
npm run build      # tsc -b && vite build
```

API calls target `VITE_API_BASE_URL` (default `/api`), proxied in dev to the
API Gateway (`:8080`), with `/api/auth/*` proxied to the Auth Service (`:8081`).

## Layout conventions

Mirrors the Customer/Provider scaffolds: `api/client.ts` + `tokenBridge`,
`stores/authStore`, `lib/queryClient`, MUI `theme`, strict TS, path aliases,
and feature folders under `src/features/*`. The distinguishing element is
`components/AppShell.tsx`, a desktop sidebar + top-bar layout.

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
