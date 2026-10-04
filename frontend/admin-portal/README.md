# HomeFix Admin Portal

Desktop-first administrative web application for the HomeFix platform, and the
home of the **Tenant Portal** used by service agencies. Built with the same
stack as the Customer and Provider apps (React + TypeScript + Vite, React
Router, TanStack Query, Zustand, Material UI, React Hook Form + Zod), but laid
out for large screens with a persistent sidebar rather than a mobile-first
bottom nav (Requirement 28.3). It is a web app only; it has no Android or iOS
build.

## Who signs in, and what they see

Only staff accounts get into the shell (`STAFF_ROLES` in `src/config/roles.ts`):
`ADMIN`, `SUPER_ADMIN`, `FINANCE_ADMIN`, `DISPATCHER`, `SUPPORT_AGENT` and
`TENANT_ADMIN`. Any other signed-in account is sent to `/agency`: the agency
application form, or the status of its application (pending, approved — sign in
again to open the Tenant Portal — or rejected with the reason).

Every module is role-gated twice with the same role set: the sidebar only lists
modules the user may open, and the route guard (`RequireAuth roles=…` in
`src/router.tsx`) shows a Forbidden screen for a direct link. The backend
enforces the same rules and answers 403. `/` sends each user to their first
permitted module (`HomeRedirect`), so nobody lands on a Forbidden screen.

| Module | Route | Roles |
| --- | --- | --- |
| Dashboard | `/dashboard` | ADMIN, SUPER_ADMIN |
| User Management | `/users` | ADMIN, SUPER_ADMIN |
| Provider Management | `/providers` | ADMIN, SUPER_ADMIN |
| Verification Queue | `/verification` | ADMIN, SUPER_ADMIN |
| Tenants | `/tenants` | ADMIN, SUPER_ADMIN |
| Service Categories | `/categories` | ADMIN, SUPER_ADMIN |
| Pricing Configuration | `/pricing` | ADMIN, SUPER_ADMIN |
| Coupons | `/coupons` | ADMIN, SUPER_ADMIN |
| Booking Management | `/bookings` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT, DISPATCHER |
| Dispatch Rules | `/dispatch` | ADMIN, SUPER_ADMIN, DISPATCHER to view; **saving is SUPER_ADMIN only** |
| Complaints | `/complaints` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT |
| Review Moderation | `/reviews` | ADMIN, SUPER_ADMIN, SUPPORT_AGENT |
| Payments & Refunds | `/payments` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN |
| Reports | `/reports` | ADMIN, SUPER_ADMIN, FINANCE_ADMIN |
| Notification Templates | `/notifications` | ADMIN, SUPER_ADMIN |
| Audit Logs | `/audit-logs` | ADMIN, SUPER_ADMIN |
| System Configuration | `/system-config` | SUPER_ADMIN |
| Tenant Portal: Requests | `/tenant/requests` | TENANT_ADMIN |
| Tenant Portal: Team | `/tenant/team` | TENANT_ADMIN |
| Tenant Portal: Jobs | `/tenant/jobs` | TENANT_ADMIN |

Landing modules: ADMIN/SUPER_ADMIN → Dashboard; SUPPORT_AGENT and DISPATCHER →
Booking Management; FINANCE_ADMIN → Payments & Refunds; TENANT_ADMIN → Requests.

The sidebar groups modules into sections (Overview, Marketplace, Catalogue,
Operations, Finance, Platform, My agency — `src/config/navSections.ts`). The
grouping is presentational only; a new module must be added to both
`navigation.tsx` and `navSections.ts`, or it never reaches the sidebar.

## Platform modules

- **Dashboard** — seven metrics (active bookings, providers online, new
  registrations, gross revenue, average provider response, open complaints,
  platform rating) refreshed every 60 s, also in the background (Requirement 19.1).
- **User Management** — search accounts, see roles and status, suspend or
  reactivate (only a SUPER_ADMIN may change an ADMIN/SUPER_ADMIN account).
- **Provider Management**, **Service Categories**, **Pricing Configuration**,
  **Coupons**, **Notification Templates**, **Reports**, **Audit Logs** — the
  operational modules of Requirement 19.2.
- **Verification Queue** (Requirement 19.3) — two tabs, one per admin step.
  **Document review**: providers in `DOCUMENT_SUBMITTED`, oldest first, with an
  inline document viewer that renders PDFs in an embedded frame; **Accept
  documents** starts the background check. **Background check**: providers whose
  check has started; the admin records the result and chooses **Passed —
  approve** (eligible for jobs) or **Failed — reject** (with a reason the provider
  sees). No background-check vendor is integrated, so this is how a check completes.
- **Users** also has an **Invitations** tab (invite staff by email, revoke), and
  **Tenants** a **Pending applications** tab (approve or reject self-registered
  agencies).
- **Booking Management** — search and filter bookings, force-cancel an
  in-flight booking with a reason.
- **Payments & Refunds** — transactions with full or partial refunds up to the
  remaining refundable amount.
- **Dispatch Rules** (Requirement 19.5 / 8.4) — each weight in 0.0–1.0 and all
  weights summing to exactly 1.0 before Save is enabled; read-only, with an
  explanation, for anyone but a SUPER_ADMIN.
- **System Configuration** — SUPER_ADMIN only; the screen re-checks the role
  itself as well.
- **Tenants** (Requirement MT-12) — the service agencies that take over bookings
  automatic matching could not place. List with status, coverage and team size;
  create and edit (name, contact, base latitude/longitude, radius 1–100 km,
  categories from the catalogue, status `ACTIVE`/`SUSPENDED`); manage each
  Tenant's administrators and providers by mobile number, with the backend's
  errors (no such account, already in another agency) shown in plain language.

## Tenant Portal (TENANT_ADMIN)

A `TENANT_ADMIN` signs in to the same application and sees only the
**My agency** section, with their agency's name in the sidebar. Every call is
scoped server-side to the caller's own Tenant; the portal never sends a tenant
id (Requirement MT-10.1).

- **Requests** (landing) — the agency's assignment queue: bookings in its area
  and categories that no provider accepted automatically, oldest first,
  refreshed every 15 s. Each row shows the service, address with a map link,
  when it is wanted, how long it has waited (coloured as it nears the timeout)
  and the amount. **Assign** opens a picker of the team with availability and
  assignability. The queue is shared with every agency covering the address; a
  lost race is explained and the queue refreshed. The number of waiting
  requests is shown beside Requests wherever the admin is in the portal.
- **Team** — the agency's providers with primary skill, verification status,
  rating and whether they are available now; add a provider by mobile number,
  remove one (jobs already assigned to them are unaffected).
- **Jobs** — every booking the team is doing or has done, newest first,
  filterable by status, capped at the latest 200.

Platform staff do not see the Tenant Portal (they administer no Tenant); they
use the Tenants module instead.

## Signing in

The login screen offers **Password** (selected by default; the field takes an
**email or a username**) and **OTP** (mobile number + code), plus "Forgot
password?" and **Register your agency** (`/agency/register`: create an account
with an emailed code, then apply). Staff join by invitation: a super admin or
admin invites them under Users → Invitations, and the emailed link opens
`/invite/:token`, where they set their own password. Locally, codes and
invitation links are in `docker/dev-mail/dev-mail.log`. Each seeded account
also signs in by email as `<username>@homefix.local`. Locally, the
seeded accounts in [`docs/LOCAL_ACCESS.md`](../../docs/LOCAL_ACCESS.md) section 3
all use `HomeFix@2026`: `admin`, `superadmin`, `finance`, `dispatcher`,
`support` and `tenantadmin` (the demo agency "Ara Home Services").

## Development

```bash
npm install
cp .env.example .env   # optional: adjust proxy targets
npm run dev            # http://localhost:5175
```

The dev server proxies `/api/auth/*` straight to the Auth Service
(`VITE_AUTH_SERVICE_URL`, default `localhost:8081`) and every other `/api/*`
call to the API Gateway (`VITE_API_GATEWAY_URL`, default `localhost:8080`),
stripping the `/api` prefix. See `vite.config.ts`. If the Docker stack is
running, its admin-portal container already holds port 5175; stop it
(`docker compose -f docker-compose.core.yml stop admin-portal`) to run the dev
server there.

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | Base for API calls (proxied) |
| `VITE_AUTH_BASE_URL` | `VITE_API_BASE_URL` | Base for Auth Service calls; only needs setting where no proxy routes `/api/auth` |
| `VITE_API_GATEWAY_URL` | `http://localhost:8080` | Dev-server proxy target only |
| `VITE_AUTH_SERVICE_URL` | `http://localhost:8081` | Dev-server proxy target only |

## Scripts

| Script | Purpose |
| --- | --- |
| `npm run dev` | Start the dev server with API proxy |
| `npm run build` | Type-check and produce a production build in `dist/` |
| `npm run preview` | Preview the production build |
| `npm run lint` / `lint:fix` | Run ESLint (zero warnings allowed) / auto-fix |
| `npm run format` / `format:check` | Format with Prettier / check formatting |
| `npm run typecheck` | Type-check without emitting (`tsc -b`) |

## Running against the local stack

The whole platform (services + all three web apps) runs from the repository
root; [`docs/LOCAL_ACCESS.md`](../../docs/LOCAL_ACCESS.md) covers the one-time
setup, seeding (including `docker/seed-tenants.sql` for the demo agency) and
the test accounts.

```bash
docker compose -f docker-compose.core.yml up -d --build --wait
```

The `admin-portal` image (`Dockerfile`) builds the bundle with the default
`/api` base and serves it from nginx on <http://localhost:5175>. `nginx.conf`
proxies `/api/auth/*` to `auth-service:8081` and everything else under `/api/`
to `api-gateway:8080`.

## Layout conventions

Mirrors the Customer/Provider scaffolds: `api/client.ts` + `tokenBridge`,
`stores/authStore`, `lib/queryClient`, MUI `theme`, strict TS, path aliases,
and feature folders under `src/features/*`. Role sets live in
`config/roles.ts`; the sidebar entries in `config/navigation.tsx`; filtering
and landing-module logic in `config/navFilter.ts`.

## Design system

`src/lib/theme.ts` is the single source of visual truth: brand palette, type
scale, elevations and MUI component overrides. Screens compose those tokens
(`brand.*`, theme palette keys) rather than hard-coding colours or radii, so the
whole app restyles from that one file.

Shared layout pieces:

- `components/AppShell.tsx` — a permanent dark sidebar with the sectioned
  modules, a top bar with the module title and the signed-in operator, and a
  scrollable content area; on small screens the sidebar becomes a drawer
  toggled from the top bar. For a TENANT_ADMIN it shows the agency name and
  the waiting-requests badge.
- `components/DataTable.tsx`, `components/ModuleScreen.tsx` — the standard
  module list layout.
- `components/ForbiddenScreen.tsx`, `components/RequireAuth.tsx`,
  `components/HomeRedirect.tsx` — role guard, Forbidden state, landing redirect.
- `components/BrandLogo.tsx` — the HomeFix mark and wordmark.
- `components/QueryStateView.tsx` — the standard loading / error / empty states.

## Project structure

```text
src/
  api/          # Axios clients (gateway + auth), token bridge
  components/   # AppShell, guards, data table, state views
  config/       # env, roles, navigation, sections, nav filtering
  features/
    auth/          # Login (email/username password + OTP), reset, invitation acceptance
    agency/        # Register your agency, application status
    dashboard/     # Live metrics
    users/ providers/ verification/ categories/ pricing/ coupons/
    bookings/ dispatch/ complaints/ reviews/ payments/ reports/
    notifications/ audit/ system/
    tenants/       # Platform admin: Tenants module
    tenant-portal/ # TENANT_ADMIN: Requests, Team, Jobs
  lib/          # Query client, theme, formatting, downloads, correlation ID
  stores/       # Zustand auth store
  router.tsx    # Role-gated routes
  App.tsx       # Provider composition
  main.tsx      # Entry point
```
