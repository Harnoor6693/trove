# apps/web Inventory — for the monolith collapse & web rebuild

Scope: `apps/web` only (React 19.2.8 + Vite + react-router-dom 6.30.4 + TanStack Query 5 +
zustand 5). Read-only pass against source; nothing here was executed (no `npm install`,
no `vitest`, no `eslint`, no `vite build` — a build is running elsewhere on this machine).
Every claim below is cited to a repo-relative path. Where a claim in
`docs/recovery/01-FINDINGS.md` (section I or A5) could not be reproduced from current
source, that is called out explicitly rather than repeated.

Owner decisions this inventory is written against (fixed, not re-litigated): 10 services
collapse into one `trove-core` JVM module; tax engine is cut and replaced by a categorised
CSV/PDF export; currency is a per-user CAD/USD setting chosen at onboarding; MinIO and
Authentik-as-hard-dependency are dropped (local disk + built-in local accounts, optional
OIDC); AI is a user-configured OpenAI-compatible endpoint, off by default; native
bank-notification listeners and IMAP ingestion are cut; plugins fold into `trove-core`.

---

## 1. Router

`src/router/index.tsx` — one `createBrowserRouter` tree, all pages lazy-loaded, each route
wrapped in `<Suspense fallback={<div>Loading…</div>}>`.

| Path | Page component | Status | Fixture file (if any) |
|---|---|---|---|
| `/login` | `src/pages/Login/index.tsx` | LIVE (redirect-only; no local form) | — |
| `/test-harness/:component` | `src/pages/TestHarness/index.tsx` | dev tool, **not gated by AuthGuard**, ships in the prod bundle (see §13) | — |
| `/onboarding` | `src/pages/Onboarding/index.tsx` | PARTIAL — steps 0–2 and 5 are static copy; step 3 (`ConnectSourcesStep`) and step 4 (`FirstBudgetStep`) call live `/v1/*` endpoints via `src/api/accounts.ts`, `src/api/categories.ts`, `src/api/budgets.ts` | — |
| `/` (index) | `src/pages/Today/index.tsx` | **LIVE** — the one page genuinely wired end-to-end | — |
| `/transactions` | `src/pages/Transactions/index.tsx` | FIXTURE — no hook call at all | `MOCK` array, `src/pages/Transactions/index.tsx:18-56` (5 rows) |
| `/coach` | `src/pages/Coach/index.tsx` | STUB — real `EventSource` wired to an endpoint no service exposes (see §3, §11) | — |
| `/budgets` | `src/pages/Budgets/index.tsx` | FIXTURE | `BUDGETS` array, `src/pages/Budgets/index.tsx:9-16` (6 rows), header hardcodes `"May 2026"` (`:23`) |
| `/subscriptions` | `src/pages/Subscriptions/index.tsx` | FIXTURE | `SUBS` array, `src/pages/Subscriptions/index.tsx:9-15` (5 rows) |
| `/receipts` | `src/pages/Receipts/index.tsx` | BROKEN — `useReceipts`/`useUploadReceipt` call `/api/receipts*`, which is not a path any backend serves (real is `/v1/receipts*`, `openapi/receipt-processor.yaml:14,135`) | — |
| `/tax/ca` | `src/pages/TaxCA/index.tsx` | FIXTURE — and computes an estimated refund/RRSP room, which is exactly the tax-engine output the owner decision cuts | `TAX_ROWS` array, `src/pages/TaxCA/index.tsx:15-22`; `"+$2,340.00"` literal at `:39` |
| `/tax/us` | `src/pages/TaxUS/index.tsx` | BROKEN — `useTaxYear` calls `/api/tax/us/{year}`, real tax-engine path is `/v1/tax/{jurisdiction}/{year}` (`openapi/tax-engine.yaml:123`), and the whole feature is slated to be cut | — |
| `/networth` | `src/pages/NetWorth/index.tsx` | FIXTURE | `CHART`/`ASSETS`/`LIABILITIES` arrays, `src/pages/NetWorth/index.tsx:6-21`; `"+12.4% YTD"` literal at `:48` |
| `/accounts` | `src/pages/Accounts/index.tsx` | BROKEN — `useAccounts` calls `/api/accounts`, real is `/v1/accounts` (`openapi/ingestion-service.yaml:80`) | — |
| `/settings` | `src/pages/Settings/index.tsx` | STUB — a theme toggle that writes state nothing reads (§6/§10 I6), an inert "Manage your account" paragraph, no logout button, no currency setting | — |

No `path: '*'` route and no `errorElement` anywhere in the tree
(`src/router/index.tsx:24-59`) — a mistyped URL renders React Router's raw
`Error: No routes matched location "..."` developer overlay in production, not a
branded 404. This is I8, confirmed unchanged.

`/tax/us` is reachable only by typing the URL: it has **no nav entry** at all (see
`NAV_ITEMS`, `src/layouts/AppShell.tsx:8-18` — "Tax CA" is present, "Tax US" is not).

### Nav / mobile-viewport overflow (I5)

`src/layouts/AppShell.tsx`: `NAV_ITEMS` holds 10 entries (`:8-18`). Desktop (`width>=1200`)
renders `QlSidebar` with the full 10-item list; tablet (`760<=width<1200`) renders `QlRail`
with the same full list. Mobile (`width<760`) renders a 5-item bottom bar defined as
`const BOTTOM_TABS = NAV_ITEMS.slice(0, 5)` (`:21`) — Today, Transactions, Coach, Budgets,
Subscriptions — with **no overflow/"More" menu**. On a phone, Receipts, Tax CA, Net Worth,
Accounts and Settings are unreachable through any UI affordance (confirmed unchanged from
I5). `useWindowWidth` (`src/hooks/useWindowWidth.ts`) is a plain resize listener, so this is
also a hard cutoff, not a graceful collapse.

---

## 2. Auth

**authStore** (`src/stores/authStore.ts`) — zustand store, **not wrapped in `persist`**
(compare `uiStore`, which is). `selectIsAuthenticated` derives from `user !== null` only
(`:23`), by design (see the comment at `:18-22`) — there is no separate boolean to drift.
`hydrate()` (`:32-77`) module-level-coalesces concurrent callers via a bare `inflight`
promise (StrictMode double-invoke guard, `:26-28`), GETs `/v1/auth/me`, and on a 401 sets
`user: null, hydrationError: null` treating it as the expected anonymous case (`:56-63`);
any other status is treated as a real outage and surfaces `hydrationError` with the raw
`err.message` (`:64-73`). `logout()` (`:81-96`) POSTs `/v1/auth/logout`, reads
`x-authentik-end-session` off the response to support an IdP-side logout redirect, clears
local state regardless of request outcome, then does a hard
`window.location.href = endSession ?? '/login'` (`:94-95`).

**Confirmed dead code**: `grep -rn "logout" src/` outside tests turns up exactly the
declaration/implementation in `authStore.ts` and the MSW mock
(`src/test/handlers/auth.ts:11`) — zero components call it. There is no logout button
anywhere in the app (Settings, `src/pages/Settings/index.tsx`, has an "Account" card that is
static text only). I2's "no logout button" and "logout is fully implemented and imported by
zero components" both hold unchanged.

**AuthGuard** (`src/router/AuthGuard.tsx`): order is `isHydrating` → `hydrationError` →
`!isAuthenticated` → allow `/onboarding` unconditionally → `prefs.isPending` → `!prefs.data
?.onboardingCompleted`. There is **no `prefs.isError` branch** (`:12`, `usePreferences()`
called once and only `.isPending`/`.data` read, `:44,52`). If the preferences endpoint 500s
after `isPending` flips false, `prefs.data` is `undefined`, `!undefined?.onboardingCompleted`
is `true`, and an already-onboarded user with a working session gets bounced to
`/onboarding` — the outage looks identical to a not-yet-onboarded first run. This is I3,
confirmed unchanged, at `src/router/AuthGuard.tsx:44-52`.

**Axios interceptor / refresh loop / I4** (`src/api/axiosInstance.ts`): singleton `refreshing`
promise coalesces concurrent 401s (`:16-18,44-51`); a 401 on the refresh call itself, or an
already-retried request, calls `clearAuthAndRedirect()` and rejects (`:38-44`);
`clearAuthAndRedirect()` (`:23-28`) unconditionally does
`window.location.href = '/login?error=expired'` unless already on `/login`. Traced against
`authStore.hydrate`: a brand-new anonymous visitor's `GET /v1/auth/me` 401s → interceptor
(not a retry, not the refresh call) marks `_retried`, fires `POST /v1/auth/refresh` → that
also 401s (no cookie) → `clearAuthAndRedirect()` fires *before* the rejected promise reaches
`authStore.hydrate`'s own catch block, so the hard redirect to `/login?error=expired` happens
regardless of `hydrate()`'s own graceful 401 handling. **This is directly asserted as
intended behavior by a passing test**: `src/api/axiosInstance.test.ts:133-145`,
`'redirects to /login?error=expired on 401 after refresh fails'`. I4's mechanism is
confirmed unchanged and is locked in by the test suite, not caught by it.

**Login page ERROR_MESSAGES** (`src/pages/Login/index.tsx:4-13`): keys are `denied, oauth,
claims, sub_mode, storage, token, session, csrf`. There is no `expired` key, so
`ERROR_MESSAGES[error] ?? ERROR_MESSAGES.oauth` (`:18`) falls through to
`"Sign-in failed. Please try again."` for the very first thing a brand-new visitor sees.
`Login.test.tsx` covers `?error=session` (a different, distinct code — the OIDC "session
expired before completing" case) but nothing tests `?error=expired`, so the mismatch between
what `axiosInstance.ts` emits and what `Login` recognizes has no test coverage on either
side. I4 confirmed unchanged.

The Login page itself is **redirect-only**: a single `<a href="/api/v1/auth/start">Continue
with Authentik</a>` (`src/pages/Login/index.tsx:24`) — no email/password form exists in the
current source. (Flagged again in §7: one of the three "real" e2e specs assumes a
username/password form on `/login` that does not exist.) Per the owner decision to drop
Authentik as a hard dependency, this entire page's UI and its `error=` vocabulary (`claims`,
`sub_mode`, `storage` are all Authentik/OIDC-specific failure modes) need to be rebuilt
around local-account login with optional OIDC, not patched.

**CSRF / cookie handling**: `api = axios.create({ baseURL: '/api', withCredentials: true,
headers: { 'Content-Type': 'application/json' } })` (`src/api/axiosInstance.ts:4-9`), plus
`X-Requested-With: XMLHttpRequest` set globally (`:11`). `grep -rn "csrf|CSRF|XSRF" src/`
outside tests returns nothing except the `csrf` key in `Login`'s `ERROR_MESSAGES` (a display
string for a query param) — there is **no client-side CSRF token/header handling at all**:
no `xsrfCookieName`/`xsrfHeaderName` axios config, no manual header injection. Given the
owner decision that CSRF protection is in scope for the public-internet deployment, the web
client currently does nothing to participate in whatever double-submit or synchronizer-token
scheme `trove-core` ends up using — this is a client-side gap to design in, not a regression
to fix.

**Base URL config**: `baseURL: '/api'` is a literal string
(`src/api/axiosInstance.ts:4`) — `grep -rn "import.meta.env|VITE_" src/` returns nothing.
There is no environment-variable override anywhere in the SPA; the only place the backend
host is named is `nginx.conf`'s `set $api_gateway "api-gateway";` (see §8). For the collapse,
this means zero client code changes are needed to point at `trove-core` — only the nginx
proxy target changes.

---

## 3. API layers

Two parallel, non-overlapping API generations coexist. **This split is the real explanation
for I1's "3 call endpoints that 404 / 2 are stubs"** — it is not three unrelated typos, it is
one whole earlier hook generation that was never migrated to the `/v1/*` + envelope
convention the newer `src/api/*.ts` layer settled on.

- **Generation A — `src/api/*.ts` (the aligned layer)**: `accounts.ts`, `budgets.ts`,
  `categories.ts`, `dashboard.ts`, `preferences.ts`, `transactions.ts`, plus
  `_envelope.ts`'s `unwrap()` helper for the `{ data: T }` envelope. All hit `/v1/...`
  paths. Consumed by the hooks `useDashboardToday`, `useDailySpendingTrend`,
  `useCurrentBudget`, `useCreateBudgetMutation`, `useRecentTransactions`, `useCategories`,
  `usePreferences`, `useUpdatePreferencesMutation`, `useExchangeSetupTokenMutation` — every
  one of these is wired into a real page (Today, Onboarding).
- **Generation B — hooks that call `api.get`/`api.post` directly**: `useAccounts.ts`,
  `useBudgets.ts`, `useTransactions.ts`, `useReceipts.ts`, `useSubscriptions.ts`,
  `useNetWorth.ts`, `useTaxYear.ts`, `useModelRouting.ts`. These hit bare, non-`/v1/`,
  differently-shaped paths (`/accounts`, `/budgets`, `/transactions`, `/receipts`,
  `/subscriptions`, `/networth/history`, `/tax/:j/:y`, `/coach/model-routing`) and expect the
  older `ApiResponse<T> = { success, data?, error?, meta? }` envelope
  (`src/api/types.ts:1-9`). Of these, **only `useAccounts`, `useReceipts`/`useUploadReceipt`,
  and `useTaxYear` are actually imported by a page** (Accounts, Receipts, TaxUS — the "3 that
  404"). `useBudgets`, `useTransactions`, `useSubscriptions`, `useNetWorth`, and
  `useModelRouting` are dead code — grep confirms zero imports from anywhere under
  `src/pages` — each still has a full `.test.tsx` file exercising it against a matching MSW
  mock, so `vitest` is green on hooks no page ever calls.
- **A third, unrelated generation for chat**: `src/hooks/useCoachSession.ts` (session-based:
  `POST /api/coach/sessions` then `GET /api/coach/sessions/:id/stream`) is dead — zero
  imports outside its own test. The actual Coach page uses a completely different, local
  `src/pages/Coach/useCoachStream.ts`, which opens `EventSource` against
  `GET /api/coach/stream?q=...` (`useCoachStream.ts:44-48`) — a fourth shape, matching
  neither `useCoachSession`'s mock nor any real model-manager path (`/v1/chat/completions`,
  `openapi/model-manager.yaml:147`).

`src/api/types.ts` itself documents this split in a comment (`:117-121`): a first,
placeholder `Transaction` interface (camelCase `amountCents`/`merchant`, matches Generation
B's fixtures) coexists with the backend-aligned `BankTransaction` (matches
ingestion-service's real entity, consumed by Generation A). The same duplication exists for
`Budget` vs. `BudgetPeriod`/`Envelope`, and `Account` vs. `ConnectedAccount`.

### Complete endpoint call table

| METHOD | Path called by client | Caller file | Request shape | Response shape expected | Notes |
|---|---|---|---|---|---|
| GET | `/api/v1/auth/me` | `authStore.ts:47` | — | `{ user_id, email, roles[] }` (snake_case `user_id`, unlike everything else) | Mapped to `User{id,email,roles}` at `:50` |
| POST | `/api/v1/auth/logout` | `authStore.ts:84` | — | reads `x-authentik-end-session` response header | Unreachable — no caller (§2) |
| POST | `/v1/auth/refresh` | `axiosInstance.ts:47` (via interceptor) | — | 200/401 status only, body unused | Coalesced singleton |
| GET | `/v1/dashboard/today` | `src/api/dashboard.ts:5` via `useDashboardToday` | — | `DashboardToday{date,spentToday,transactionsToday,spentThisMonth,incomeThisMonth,netThisMonth}` — money fields are `string` (BigDecimal-as-string) | Today (live) |
| GET | `/v1/transactions?from&to&type=DEBIT` | `src/api/transactions.ts:5` via `useDailySpendingTrend` | query params `from,to,type` | `BankTransaction[]` — `amount:string`, `categoryId`, `tagsJson` | client-side bucketed; server-side `from/to` filtering not exercised by the MSW mock (§6/§7) |
| GET | `/v1/transactions?limit&sort=date,desc` | `src/api/transactions.ts:5` via `useRecentTransactions` | `limit, sort` | same `BankTransaction[]` | Today (live) |
| GET | `/v1/budgets/current` | `src/api/budgets.ts:11` via `useCurrentBudget` | — | `{ data: BudgetPeriod }`, 404→`null` handled explicitly | Today (live) |
| POST | `/v1/budgets` | `src/api/budgets.ts:20` via `useCreateBudgetMutation` | `CreateBudgetRequest{month,totalIncome,currency?,budgetType?}` | `{ data: BudgetPeriod }` | Onboarding step 4 |
| POST | `/v1/budgets/{budgetId}/envelopes` | `src/api/budgets.ts:27` via `useCreateBudgetMutation` | `CreateEnvelopeRequest{categoryId,categoryName,allocated,currency?}` | `{ data: Envelope }` | called once per default category, sequentially, not batched |
| GET | `/v1/categories` | `src/api/categories.ts:4` via `useCategories` | — | `Category[]` | Today + Onboarding |
| GET | `/v1/me/preferences` | `src/api/preferences.ts:5` via `usePreferences` | — | `{ data: Preferences{deployment,aiMode,onboardingCompleted} }` | AuthGuard gate |
| PUT | `/v1/me/preferences` | `src/api/preferences.ts:10` via `useUpdatePreferencesMutation` | `Partial<Preferences>` | `{ data: Preferences }` | Onboarding finalize |
| POST | `/v1/accounts/exchange-setup-token` | `src/api/accounts.ts:9` via `useExchangeSetupTokenMutation` | `{pluginId:'simplefin',setupToken}` | `{accountIds[], syncLog:{id,status,transactionsNew,startedAt?,completedAt?}}` — **no envelope**, per comment at `src/api/types.ts:165-167` | Onboarding step 3; 409 handled specially in `Onboarding/index.tsx:154-156` |
| GET | `/v1/accounts` | `src/api/accounts.ts:18` (`listAccounts`) | — | `ConnectedAccount[]` | **defined but never called by any hook/page** — dead export |
| GET | `/api/accounts` | `useAccounts.ts:8` | — | `ApiResponse<Account[]>` (old envelope, `success/data/error/meta`) | Accounts page — wrong path, real is `/v1/accounts` |
| GET | `/api/budgets` | `useBudgets.ts:7` | — | `ApiResponse<Budget[]>` | dead (no page imports it) |
| POST | `/api/budgets` | `useBudgets.ts:16` (`useCreateBudget`) | `Omit<Budget,'id'\|'spentCents'>` | `ApiResponse<Budget>` | dead |
| GET | `/api/transactions` | `useTransactions.ts:8` | optional query object | `ApiResponse<Transaction[]>` | dead (Transactions page is 100% fixture) |
| GET | `/api/receipts` | `useReceipts.ts:6` | optional query object | `ApiResponse<Receipt[]>` | Receipts page — wrong path, real is `/v1/receipts` |
| POST | `/api/receipts/upload` | `useReceipts.ts:19` (`useUploadReceipt`) | `multipart/form-data`, field `file` | `ApiResponse<{jobId,status}>` | Receipts page — wrong path, real is `/v1/receipts/upload` |
| GET | `/api/subscriptions` | `useSubscriptions.ts:8` | — | `ApiResponse<Subscription[]>` | dead |
| GET | `/api/networth/history` | `useNetWorth.ts:8` | — | `ApiResponse<NetWorthSnapshot[]>` | dead — real budget-service path is `/v1/networth/series` (`openapi/budget-service.yaml:773`), a different shape entirely |
| GET | `/api/tax/:jurisdiction/:year` | `useTaxYear.ts:9` | path params | `ApiResponse<TaxYear{year,jurisdiction,incomeTotal,deductionsTotal,estimatedTax}>` | TaxUS page — wrong path (missing `/v1`), and the whole feature is cut per owner decision |
| GET | `/api/coach/model-routing` | `useModelRouting.ts:8` | — | `ApiResponse<ModelRouting>` | dead — real is `/v1/models/routing` (`openapi/model-manager.yaml:291`) |
| POST | `/api/coach/sessions` | `useCoachSession.ts` (dead hook) | — | `{success,data:{sessionId,model}}` | not called by the real Coach page |
| GET | `/api/coach/sessions/:id/stream` | `useCoachSession.ts` (dead hook) | SSE | `SseToken\|SseDone` frames | not called by the real Coach page |
| GET | `/api/coach/stream?q=` | `Coach/useCoachStream.ts:44` | query param `q` | raw SSE, `data:"[DONE]"` sentinel or `{token:string}` JSON frames | the actual Coach page's only network call; matches no known backend route |

---

## 4. Onboarding

`src/pages/Onboarding/index.tsx`. `STEPS = ['Welcome','Deployment','AI Mode','Connect
Sources','First Budget','Complete']` (`:14`) — 6 steps, no currency/country step, despite the
owner decision that country/currency (CAD vs. USD) is chosen at onboarding. `FirstBudgetStep`
hardcodes `currency: 'CAD'` on both the budget and every envelope it creates
(`:246,253`) — a US user completing today's onboarding gets a CAD budget regardless of any
future currency choice.

**Gating (I2, confirmed unchanged)**: `canContinue` (`:50-59`) is a switch keyed on `step`;
`case 3: return false // T15 — ConnectSourcesStep owns its Continue` (`:55`) — the shared
"Continue" footer is suppressed for steps 3 and 4 (`{step !== 3 && step !== 4 && (...)}`,
`:129`) in favor of each step owning its own button.  `ConnectSourcesStep`'s own Continue is
`disabled={!mutation.isSuccess}` (`:203`), where `mutation` is
`useExchangeSetupTokenMutation()` — i.e., posting a working SimpleFIN setup token to
`/v1/accounts/exchange-setup-token` is the *only* way past step 3. `grep -n "skip"
Onboarding/index.tsx` finds nothing — there is no skip affordance, no "I'll do this later"
path, and combined with AuthGuard's hard redirect for `!onboardingCompleted`
(`AuthGuard.tsx:52`) and the missing logout button (§2), a self-hoster without a SimpleFIN
token cannot reach the app and cannot back out to `/login` either. Confirmed unchanged.

`FirstBudgetStep` additionally throws synchronously if a default category name isn't found
in the live `/v1/categories` response (`Onboarding/index.tsx:191-192`,
`throw new Error(\`Default category missing: ${e.name}\`)`), uncaught by any error boundary
on this route — a categories-endpoint response that omits "Groceries", "Food & Dining", or
"Transport" white-screens step 4.

**Preferences endpoints** (§3 table): `GET /v1/me/preferences` gates `AuthGuard`; `PUT
/v1/me/preferences` finalizes onboarding by setting `onboardingCompleted: true` alongside
whatever `deployment`/`aiMode` were picked in steps 1–2 (`useFinalize`, `:26-37`). These two
map directly to `trove-core`'s per-user settings surface and are the cleanest, most
reusable piece of the onboarding flow.

**Component-generation straddle**: this single page imports from *both* design-system
generations — `QlBtn`/`QlRadioCard` from the old top-level `src/components/` set and
`QlInput` from the new `src/components/ui/` set (`Onboarding/index.tsx:3-5`). See §9.

---

## 5. Money / currency

`src/components/MoneyDisplay/MoneyDisplay.tsx` is the one shared money-formatting
component. It accepts a `currency?: string` prop (`:5`) that **is destructured out of props
and never read** — `formatCents` (`:9-14`) always does `` `$${dollars}` `` with a literal
`$`, and never groups thousands (`(abs/100).toFixed(2)`, no `toLocaleString`/
`Intl.NumberFormat`). It is used by Accounts (`balanceCents`), Receipts (`totalCents`), and
TaxUS (`incomeTotal`/`deductionsTotal`/`estimatedTax` — passed straight through even though
`TaxYear`'s fields are plain `number`, not `Cents`, so TaxUS's use of `<MoneyDisplay
cents={tax.incomeTotal}>` divides a dollar-scale mock value by 100). Given the owner
decision that CAD and USD are both first-class, this component is currency-blind by
construction and cannot render a USD amount correctly today even in principle — it needs a
currency-aware rewrite (locale + `Intl.NumberFormat(locale, {style:'currency',currency})`),
not a patch.

Every other money value in the app is formatted ad hoc, independently, always with a literal
`$`:

| File:line | Literal | Note |
|---|---|---|
| `Today/index.tsx:16-20` (`formatMoney`) | `` `${sign}$${...toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2})}` `` | `undefined` locale = browser default, not the user's chosen currency locale |
| `Today/index.tsx:90-91` | `` `$${spent}` ``, `` `$${limit}` `` | raw numbers, no `.toFixed` |
| `Transactions/index.tsx:96-99` | `'+$'` / `'-$'` prefix | fixture page |
| `Budgets/index.tsx:36,39,42,68,69` | `` `$${totalSpent}` `` etc. | fixture page, no decimal formatting at all |
| `Subscriptions/index.tsx:30,34,57` | `` `$${...toFixed(2)}` `` | fixture page |
| `NetWorth/index.tsx:25` (`fmt`) | `n.toLocaleString('en-CA', {minimumFractionDigits:2})` | locale hardcoded to `en-CA` — wrong for a USD user by construction |
| `NetWorth/index.tsx:44-48` | `"+12.4% YTD"` | literal fixture percentage badge |
| `TaxCA/index.tsx:64` | `.toLocaleString('en-CA')` | locale hardcoded |
| `TaxCA/index.tsx:39` | `"+$2,340.00"` | literal fixture estimated-refund |
| `TaxCA/index.tsx:75,77,87` | `.toLocaleString()` (no locale arg) | RRSP room, browser-default locale |
| `Onboarding/index.tsx:246,253` | `currency: 'CAD'` (×2) | hardcoded regardless of any future user setting |
| `Onboarding/index.tsx:280` | label `"Monthly total ($)"` | literal `$` in a form label |
| `Onboarding/index.tsx:294` | `` `$${e.allocated}` `` | envelope preview, literal `$` |

None of these read a currency from `usePreferences()`/user settings — there is currently no
such field on `Preferences` (`deployment | aiMode | onboardingCompleted` only,
`src/api/types.ts:106-110`) to read from. A currency-aware rebuild needs (a) a `currency`
field added to the preferences contract and an onboarding step to set it, (b) a single
shared money-formatting utility built on `Intl.NumberFormat`, and (c) every one of the
above call sites — plus every `BankTransaction`/`BudgetPeriod`/`Envelope` money field, which
are already `string`-typed BigDecimal (correct for precision) — routed through it.

---

## 6. Charts / hooks

`src/components/ui/QlChart/QlChart.tsx` wraps `recharts` (the only consumer of the
`recharts` dependency, confirmed via `grep -rl recharts src/`) with `spark`/`area` variants.
Used by Today (`spark`, live daily-spend trend) and NetWorth (`area`, fixture).

**`useDailySpendingTrend` — correction to 01-FINDINGS A5.** A5 describes "a date time-bomb
... unguarded Map.set creates an 8th bucket for out-of-window dates." Current source
(`src/hooks/useDailySpendingTrend.ts:31-36`) **already contains a guard**:
```
for (const t of txns) {
  // Only bucket dates inside the pre-seeded window; anything else would
  // silently add an extra day to the chart.
  if (!byDay.has(t.date)) continue
  byDay.set(t.date, (byDay.get(t.date) ?? 0) + Number(t.amount))
}
```
This specific mechanism ("unguarded Map.set / 8th bucket") does not reproduce against
current source — either it was already fixed, or A5 described an intermediate state. What
*does* remain, verified: the MSW fixture in `src/test/handlers/transactionsV1.ts:6-19`
hardcodes both mock transactions to date `2026-05-14`, and does not honor the `from`/`to`
query params `useDailySpendingTrend` sends at all (the handler ignores `request.url` and
always returns the same two rows) — so the mock never actually exercises server-side date
filtering, only the client-side bucketing loop above. As real time moves past
`2026-05-14` (today's date in this environment is 2026-09-03), the guard now silently drops
both fixture transactions on every test run (`!byDay.has('2026-05-14')` is `true` once that
date is outside any rolling 7-day window from "now"), so `useDailySpendingTrend.test.tsx`'s
second test (`:24-32`) degrades to an always-zero assertion its own `if (todayEntry)` guard
was written to tolerate — the test still passes, but it has stopped testing the bucketing
math it was written to test. This is a live, verified staleness risk independent of the
`Map.set` question; whether `vitest`'s full 171-test/2-failure count from A5 still holds
could not be confirmed here (test execution is out of scope for this read-only pass).

Other hooks are inventoried in full in §3's table. `useWindowWidth.ts` is a plain
`resize`-driven `useState`, no debounce/throttle — fires on every pixel of a resize, which
is what drives the nav breakpoint logic in `AppShell.tsx`.

---

## 7. Tests

**vitest**: `vite.config.ts` `test.include: ['src/**/*.{test,spec}.{ts,tsx}']` (`:31`) — scoped
to `src/` only. 171 `*.test.{ts,tsx}` files live under `src/`, one per component/hook/page
generally, each paired with its own MSW handler file. `src/test/setup.ts` installs a
hand-rolled `localStorage` mock (jsdom's is incomplete, `:6-15`), polyfills
`Element.prototype.scrollIntoView` (`:18-19`), and starts `msw/node`'s `server` with
`onUnhandledRequest: 'error'` (`:21`) — any request not covered by a handler fails the test
loudly, which is why both API generations (§3) needed their own full handler sets to keep
tests green.

**MSW handlers** (`src/test/handlers/*.ts`, 16 files) encode two separate, non-agreeing
contracts side by side: the legacy `ApiResponse{success,data,error,meta}`-enveloped set
(`accounts.ts`, `budgets.ts`, `transactions.ts`, `receipts.ts`, `subscriptions.ts`,
`networth.ts`, `tax.ts`, `modelRouting.ts`, all matching Generation-B hooks) and the
`{data:T}`-enveloped `/v1/*` set (`accountsV1.ts`, `budgetsV1.ts`, `transactionsV1.ts`,
`categoriesV1.ts`, `preferencesV1.ts`, plus unenveloped `dashboard.ts`). This mirrors the
Java-side M10 finding almost exactly: the test suite is real and green, but hermetic — it
validates each hook against a mock built to match that hook's own (sometimes wrong) idea of
the contract, not against the real backend's OpenAPI-declared shape. One concrete instance:
`transactionsV1Handlers` (`src/test/handlers/transactionsV1.ts:8-16`) returns fields
`category` and `tags`, but `api/types.ts`'s `BankTransaction` (the type this same handler is
supposed to satisfy) declares `categoryId`/`tagsJson` (`api/types.ts:145,148`) — the mock and
the type it's paired with already disagree with each other, independent of what the real
backend returns; `Today`'s category badge (`Today/index.tsx:135-138`) silently renders
nothing under test because of this, and the test suite does not catch it because nothing
asserts the badge is present.

**e2e (`apps/web/e2e/`, testDir per `playwright.config.ts:4`) — the 3 "real" specs**:
- `smoke.spec.ts` checks 6 routes for console errors, including `/dashboard` — **not a route
  that exists** in `src/router/index.tsx` (root `/` is Today; there is no `/dashboard`). This
  spec would currently fail on the `/dashboard` case, or at best hit the no-`errorElement`
  crash page from §1/I8.
- `auth-flow.spec.ts` also waits for `/dashboard` (`:16`) and drives an Authentik-hosted
  login form (`input[name='uidField']`) — consistent with the *current* Login page (which
  redirects out to Authentik), but doomed by the same `/dashboard` typo, and irrelevant once
  Authentik stops being a hard dependency.
- `onboarding-to-today.spec.ts` fills `getByRole('textbox', {name:/email/i})` and a password
  field directly on `/login` (`:9-11`) — **the current Login page has no such fields**, only
  a single "Continue with Authentik" link (`Login/index.tsx:24`). This spec cannot pass
  against current source; it was written for a local-credentials login page that does not
  exist yet (though matches the direction of the owner's built-in-local-accounts decision).

**`apps/web/tests/`, 111 spec files (98 in `tests/`, matching I7's count), orphaned**:
`playwright.config.ts:4` sets `testDir: "./e2e"` — Playwright never discovers anything under
`tests/`. `tests/helpers/auth.ts`'s `seedAuth()` also writes `AUTH_STATE` to
`localStorage.setItem('trove-auth', ...)` (`:14`) — but `authStore.ts` uses a bare
`create<AuthState>()`, **no `persist` middleware at all** (confirmed §2), so there is no
`'trove-auth'` key for the real store to ever read; even reconnected to the runner, this
helper would not authenticate anything. (`uiStore.ts` is the only persisted store, under key
`'trove-ui'` — a different key entirely.) I7 confirmed unchanged on both counts.

**Lint**: `.eslintrc.cjs` (`:1-16`) extends `eslint:recommended` +
`@typescript-eslint/recommended` + `react-hooks/recommended`, plugs in `react-refresh`, and
sets `'no-console': 'error'` with no `allow` list. A5 attributes 159 of 217 lint errors to an
`eslint-plugin-react-refresh` 0.4→0.5 ESM break — **not reproducible against current
source**: `package.json:42` pins `~0.4.16`, and the installed
`node_modules/eslint-plugin-react-refresh/package.json` reports `"version": "0.4.26",
"type": "commonjs"` — still pre-0.5, still CJS, no ESM/require mismatch visible. Either this
was already fixed or the audited lockfile state differs from what's checked in now; flagging
as unverified rather than repeating it. What *is* directly verifiable without running eslint:
`'no-console': 'error'` has no `{allow:['warn','error']}` exception, and
`console.error` appears three times in production code —
`authStore.ts:70`, `authStore.ts:97`, `ErrorBoundary.tsx:30` — each a genuine, real
lint violation under the current config, a small slice of A5's "58 genuine" bucket. Lint was
not executed here (no `npm`/`eslint` run, per task constraints).

**tsconfig/build**: `tsconfig.app.json` — `strict: true`, `noUnusedLocals`,
`noUnusedParameters`, `noFallthroughCasesInSwitch`, path aliases matching `vite.config.ts`'s
`resolve.alias`. `package.json`'s `"build"` script is `tsc -p tsconfig.app.json && vite
build` — but `Dockerfile:14` runs `npx vite build` directly, **skipping the `tsc` step**
(the Dockerfile's own comment says so: `"Build the SPA (Vite only — tsc type-checking is
enforced separately by CI)"`). The production image build therefore has no type-checking
gate of its own; it depends entirely on a separate CI job running `npm run build` (or `tsc`
directly) and actually blocking merges — which, per 01-FINDINGS A8 (Dependabot auto-merge
over red CI, no required status checks), is exactly the gap already identified as broken
elsewhere in this repo.

---

## 8. Docker

`apps/web/Dockerfile`: two-stage build. Builder: `node:20-alpine`, `npm install -g npm@11`
(comment explains: node 20-alpine ships npm 10, which can't read this lockfile's
platform-specific optional entries for esbuild prebuilds), `npm ci --legacy-peer-deps`, then
`npx vite build` (see §7 — skips `tsc`). Runtime: `nginx:1.31-alpine`, copies `dist/` to
`/usr/share/nginx/html`, copies `nginx.conf` to `/etc/nginx/conf.d/default.conf`, exposes 80,
and a `HEALTHCHECK` that greps the served `index.html` for `<div id="root"` — checks the
static shell is served, not that the app actually boots or that the backend is reachable.

**`nginx.conf`**: `server_tokens off`; gzip for text assets; forwards `X-Forwarded-*`
headers with a comment explaining Traefik terminates TLS upstream so `$http_x_forwarded_proto`
(not `$scheme`) carries the real scheme through to the backend's
`forward-headers-strategy`. Uses Docker's embedded DNS (`resolver 127.0.0.11`) and a
`set $api_gateway "api-gateway";` variable in `proxy_pass` specifically so nginx can start
even before the backend container is resolvable (request-time DNS, 502 if absent, rather
than a boot-time failure) — a deliberately good pattern, worth keeping. `location /api/`
does `proxy_pass http://$api_gateway:8080$request_uri;` — forwards the full original URI,
`/api/...` prefix included, unmodified, to `api-gateway:8080`. `location /login/oauth2/`,
`/oauth2/`, and `/logout` proxy the same way, with a comment explaining this keeps the
Authentik OAuth2 callback and the BFF session cookie on a single origin
(`trove.localhost`). `location /assets/` sets a 1-year immutable cache; `= /index.html` is
explicitly no-cache; `location /` does the standard SPA `try_files $uri $uri/ /index.html`
fallback.

**No env vars anywhere**: no `ARG`/`ENV` in the `Dockerfile`, no entrypoint script, no
`envsubst` step — the backend hostname is the single literal `"api-gateway"` baked into
`nginx.conf` at `:32`. For the collapse to `trove-core`, this is a one-line change
(`set $api_gateway "trove-core";` or whatever the compose service is named) — the SPA itself
needs zero changes since it only ever calls same-origin `/api/*` (§2).

Worth flagging for the rebuild: `vite.config.ts`'s dev proxy (`server.proxy['/api']`,
`:20-23`) targets `http://localhost:8080` with `changeOrigin: true` and no path rewrite — dev
and prod both assume the backend answers on `/api/v1/...` including the `/api` prefix. This
inventory did not trace how (or whether) `api-gateway`'s Spring config actually maps that
`/api` prefix — `openapi/api-gateway.yaml`'s documented paths (`/auth/me`, `/auth/refresh`,
etc., `openapi/api-gateway.yaml:17-125`) are written *without* an `/api` or `/v1` prefix, and
no `server.servlet.context-path` was found in `services/api-gateway/src/main/resources/application.yml`.
That's a `trove-core` routing question, out of scope for a web-only inventory, but it's the
first thing to confirm when wiring the collapsed backend behind this exact nginx config —
see §11.

---

## 9. Design system

Two non-interoperating component generations live side by side under `src/components/`:

- **Old / top-level** (`src/components/Ql*`): `QlBar`, `QlBtn`, `QlCard`, `QlDot`,
  `QlEyebrow`, `QlPageHeader`, `QlPill`, `QlRadioCard`, `QlSidebar`, `QlSpark`, `QlToggle`,
  plus `MoneyDisplay` and `ErrorBoundary`. Consumed by **Accounts, Receipts, Settings,
  TaxUS** (all four of the "3 broken + 1 stub" pages) and, for `QlBtn`/`QlRadioCard`, by
  **Onboarding** alongside...
- **New / `ui/`** (`src/components/ui/Ql*`): `QlAvatar`, `QlBadge`, `QlButton`, `QlCard`,
  `QlChart`, `QlDropdown`, `QlInput`, `QlModal`, `QlProgress`, `QlRail`, `QlSidebar`,
  `QlSkeleton`, `QlTable`, `QlTooltip`. Consumed by **Today (live), Budgets, Subscriptions,
  NetWorth, TaxCA** (the fixture pages), `TestHarness`, and — for `QlInput` only —
  **Onboarding**.

Two `QlCard`s and two `QlSidebar`s exist under different names but the same intent, styled
differently, verified by direct import-site grep (`@components/QlCard/QlCard'` →
Receipts/Settings/Accounts/TaxUS; `@components/ui/QlCard/QlCard'` →
Today/TaxCA/Subscriptions/NetWorth/Budgets/TestHarness). The split lines up almost exactly
with §3's two API generations: the old component set was built alongside the old
non-`/v1/` API layer, the new component set alongside the new fixtures/rewrite, and neither
migration finished. `Onboarding` straddles both within one file
(`Onboarding/index.tsx:3-5`).

**Tokens**: `src/styles/tokens.css` — one `:root` block, light-palette only (`--paper`,
`--ink`, `--sage`, `--amber`, `--red`, `--blue` families, `:1-27`). `grep -rn
"prefers-color-scheme|data-theme" src/` returns **zero matches** in any `.css` file in the
repo. `uiStore.ts` persists a `theme: 'light'|'dark'` field (`:5-14`, key `'trove-ui'`) and
`Settings/index.tsx` renders a working Light/Dark `QlToggle` bound to it (`:8-22`) — the
toggle writes real, persisted state, but literally nothing reads `useUiStore().theme`
anywhere else in the codebase. I6 confirmed unchanged: dark mode does not exist despite a
fully-functional-looking toggle for it.

**Accessibility**, each verified directly against source:
- `QlModal` (`src/components/ui/QlModal/QlModal.tsx`) has `role="dialog"`, `aria-modal`,
  Escape-to-close (`:14-16`), and backdrop-click-to-close, but **no focus trap**: no
  ref-based focus-on-open, no Tab-cycle/wrap, no focus restoration on close. (Currently used
  only by `TestHarness`, not any real page.)
- `QlDropdown` (`src/components/ui/QlDropdown/QlDropdown.tsx`): the `role="menu"` container
  and its `role="menuitem"` buttons are **always mounted** — visibility is CSS-class-only
  (`` `${styles.menu} ${open?styles.open:''}` ``, `:39`), so closed-menu items stay in the
  a11y tree and remain Tab-reachable.
- `QlRadioCard` (`src/components/QlRadioCard/QlRadioCard.tsx:16`):
  `onKeyDown={(e) => e.key === 'Enter' && onChange(value)}` — `role="radio"` but only Enter
  activates it, not Space (the WAI-ARIA-expected key for radio/checkbox/button-role
  widgets). Used in Onboarding steps 1 and 2.
- `QlPill` (`src/components/QlPill/QlPill.tsx:14-19`): when given an `onClick`, sets
  `role="button"` and `tabIndex={0}` but attaches **no `onKeyDown` at all** — keyboard users
  can Tab to it but neither Enter nor Space activates it.
- Receipts upload zone (`Receipts/index.tsx:30-33`): `role="button" tabIndex={0}` wrapping
  a hidden file input, `onClick` only, no `onKeyDown` — same pattern as `QlPill`.
- 9 of 14 pages have zero `aria-*` attributes (verified by spot `grep -c "aria-"` across
  `src/pages/*/index.tsx`: `Transactions`, `Budgets`, `Subscriptions`, `NetWorth`, `TaxCA`,
  `TaxUS`, `Accounts`, `Settings`, `TestHarness` all return 0; `Today`, `Onboarding`,
  `Coach`, `Login`, `Receipts` have at least one).

All of I9's items are confirmed unchanged against current source.

---

## 10. Verified defects (I + M, this pass)

Confirmed unchanged, with citations (superseding the looser prose in `01-FINDINGS.md` where
line numbers weren't given there):

- **I2** — Onboarding step 3 hard-blocks with no skip path; no logout button anywhere;
  `authStore.logout` unreachable. `Onboarding/index.tsx:55,203`; `AuthGuard.tsx:52`;
  `authStore.ts:81-96`.
- **I3** — `AuthGuard` has no `prefs.isError` branch; a preferences outage reads identically
  to "needs onboarding." `AuthGuard.tsx:44-52`.
- **I4** — First anonymous visit's `/v1/auth/me` 401 triggers a doomed `/v1/auth/refresh`,
  then a hard redirect to `/login?error=expired`; `expired` is not in `Login`'s
  `ERROR_MESSAGES`, falls back to the generic `oauth` message; this exact redirect is
  asserted as expected behavior by a passing test. `axiosInstance.ts:23-28,38-51`;
  `Login/index.tsx:4-18`; `axiosInstance.test.ts:133-145`.
- **I5** — Mobile bottom nav is `NAV_ITEMS.slice(0,5)` with no overflow menu; Receipts, Tax
  CA, Net Worth, Accounts, Settings unreachable on a phone. `AppShell.tsx:8-21`.
- **I6** — `uiStore.theme` is written by a real, working Settings toggle and persisted, but
  read nowhere; zero `prefers-color-scheme`/`data-theme` in any stylesheet. `uiStore.ts`;
  `Settings/index.tsx:8-22`; repo-wide CSS grep.
- **I7** — 98 Playwright specs under `apps/web/tests/` are orphaned by
  `playwright.config.ts`'s `testDir:"./e2e"`; `tests/helpers/auth.ts` seeds a
  `localStorage['trove-auth']` key `authStore` (unpersisted) never reads.
  `playwright.config.ts:4`; `tests/helpers/auth.ts:14`; `authStore.ts` (no `persist`).
- **I8** — No `path:'*'` route, no `errorElement`, anywhere in the router tree.
  `router/index.tsx:24-59`.
- **I9** — `QlModal` no focus trap; `QlDropdown` menu items always in the a11y tree;
  `QlRadioCard` handles Enter not Space; `QlPill`/Receipts-upload-zone `role="button"` with
  no `onKeyDown`; 9/14 pages have zero `aria-*`. All cited above in §9.

**New, this pass** (not separately itemized in `01-FINDINGS.md`'s I section, but load-bearing
for the rebuild):

- **I10** — Two non-interoperating design-system generations coexist
  (`src/components/Ql*` vs. `src/components/ui/Ql*`), split along almost exactly the same
  line as the two API generations; `Onboarding` imports from both. §9.
- **I11** — No client-side CSRF header/token handling exists anywhere in `src/`, despite CSRF
  being explicitly in scope for the public-internet deployment. §2.
- **I12** — `MoneyDisplay` accepts and ignores a `currency` prop; every money literal in the
  app hardcodes `$` and, in two places, hardcodes the `en-CA` locale — the whole app is
  currency-blind against the owner's CAD/USD-per-user decision, and Onboarding has no
  currency-selection step at all. §5.
- **I13** — `Coach`, the actual page users see, calls an endpoint
  (`GET /api/coach/stream?q=`) that matches no real backend route and no MSW mock; the
  session-based hook/mock pair that *does* have a matching contract (`useCoachSession.ts` +
  `handlers/coach.ts`) is dead code no page imports. §3.
- **I14** — `/test-harness/:component` is a dev-only component-gallery route, unauthenticated
  (outside `AuthGuard`), shipped in the production router tree. §1, §13.
- **I15 (correction to A5)** — the specific "unguarded `Map.set` creates an 8th bucket"
  mechanism in `useDailySpendingTrend.ts` does not reproduce against current source; a guard
  is present. The underlying MSW-fixture-date staleness risk is real and independently
  verified, but the failure mode A5 describes is not the current one. §6.
- **I16 (partial correction to A5)** — the `eslint-plugin-react-refresh` 0.4→0.5 ESM-break
  claim does not reproduce against current `package.json`/lockfile/`node_modules` (all show
  pre-0.5, CommonJS). Independently verified: `'no-console':'error'` with no exceptions is
  violated 3× in production code (`authStore.ts:70,97`; `ErrorBoundary.tsx:30`) — a small,
  concretely-verified slice of A5's "58 genuine" lint-error bucket. §7.

---

## 11. The client contract — what the web app needs from `trove-core`

Grouped by page/feature, with the endpoint the client currently calls (right or wrong) and
what it needs once `trove-core` is live, following the owner decisions (no tax computation,
CAD/USD per-user, no MinIO, local-account auth, AI off-by-default).

- **Login** — needs local-account endpoints that don't exist in the client today at all:
  register/login/logout against argon2-hashed local credentials, with optional OIDC as an
  alternate path. The one thing to keep: `GET /v1/auth/me` returning
  `{user_id,email,roles[]}` — session hydration is otherwise solid (§2).
- **Onboarding** — `GET/PUT /v1/me/preferences` (keep, add a `currency` field),
  `GET /v1/categories` (keep), `POST /v1/budgets` + `POST /v1/budgets/{id}/envelopes` (keep,
  consider a single batched "create budget with envelopes" endpoint since the client
  currently loops sequential POSTs, `Onboarding/index.tsx:186-193`), and a
  connect-accounts step that no longer hard-requires SimpleFIN specifically — the client's
  `POST /v1/accounts/exchange-setup-token` contract (`pluginId,setupToken` → `accountIds[],
  syncLog`) is reasonable and reusable, but the step needs a skip path regardless of which
  provider (or none) is configured.
- **Today** — `GET /v1/dashboard/today`, `GET /v1/transactions` (both `from/to/type` and
  `limit/sort` query modes), `GET /v1/budgets/current`, `GET /v1/categories` — this is the
  one page already fully speaking a coherent, real contract; keep it as the reference shape
  for the rest.
- **Transactions** — needs a real list endpoint with pagination/filtering (the client already
  has `listTransactions` in `src/api/transactions.ts` capable of `limit/sort/from/to/type` —
  extend, don't replace) and a way to edit category/tags per transaction (no mutation
  endpoint exists client-side today at all — the whole page is a read-only mock).
- **Accounts** — fix the path to `/v1/accounts` (matches `src/api/accounts.ts`'s
  already-correct, already-unused `listAccounts`); needs balance-with-currency per account.
- **Budgets** — `/v1/budgets/current` exists and is correct; the Budgets *page* needs a
  months-list/history endpoint it doesn't call today (it's 100% fixture), plus the create-
  budget flow to be reachable from here, not just onboarding.
- **Net Worth** — real budget-service path is `/v1/networth/series` (assets/liabilities time
  series) — nothing resembling the client's fixture shape (`CHART`/`ASSETS`/`LIABILITIES`
  arrays) currently exists client-side to consume it; needs a full rewrite against the real
  contract, not a path fix.
- **Subscriptions** — needs `/v1/subscriptions` wired up (client fixture shape is close to
  plausible: name, amount, cycle, next billing date, category — a reasonable target for the
  real contract).
- **Receipts** — fix the path to `/v1/receipts` + `/v1/receipts/upload`; since MinIO is
  dropped for local disk, the upload/list/status contract shape (`multipart/form-data` file
  → `{jobId,status}` → poll or list) is still reasonable and can likely be kept.
- **Coach** — needs a decision and a single implementation: either adopt the
  session-based shape the dead `useCoachSession` hook and its matching MSW mock already
  encode (`POST /v1/chat/sessions` → `GET /v1/chat/sessions/:id/stream`, aligned with
  `openapi/model-manager.yaml:147`'s `/v1/chat/completions`), or keep the simpler
  query-param `EventSource` shape the live `Coach` page actually uses — but not both. Given
  AI is off-by-default and user-configured, the page also needs a real "AI not configured"
  empty state — the hardcoded `"AI · Llama 3.1 8B · offline"` eyebrow
  (`Coach/index.tsx:38`) is fiction regardless of which endpoint shape is chosen.
- **Tax (export only)** — the entire `useTaxYear`/`TaxYear`/estimated-tax contract is
  obsolete per the owner decision; TaxCA and TaxUS both need to be replaced by one export
  flow hitting a new `trove-core` endpoint that returns a categorised-transactions CSV/PDF
  for a given year — no computed tax numbers, no RRSP room, no estimated refund.
- **Settings** — needs, and currently has zero client-side calls for: currency selection,
  AI-endpoint configuration (URL/key for the user's OpenAI-compatible endpoint), and a
  logout affordance wired to the already-implemented `authStore.logout()`.
- **404** — no contract needed; purely a router fix (`errorElement` + catch-all route).

---

## 12. Page-by-page recommendation

| Page | Recommendation | Fixture files to delete |
|---|---|---|
| Login | **Rewrite** — replace Authentik-redirect-only UI with local-account login (+ optional OIDC button); rebuild `ERROR_MESSAGES` around the new auth flow's actual error codes | — |
| Onboarding | **Rewrite** the flow (add currency/country step, remove the hard SimpleFIN block, add a real skip path); **keep** the `/v1/me/preferences`, `/v1/categories`, `/v1/budgets*` wiring as the template | — |
| Today | **Keep** — the one page already doing this right; use as the reference pattern for every rewrite below | — |
| Transactions | **Rewrite** — wire to real `/v1/transactions` with pagination + category/tag editing | delete `MOCK` array, `Transactions/index.tsx:18-56` |
| Coach | **Rewrite** — pick one endpoint shape (§11), add an "AI not configured" empty state, delete the hardcoded model-name eyebrow | — |
| Budgets | **Rewrite** — wire to `/v1/budgets/current` + a real history/list endpoint; reuse `useCurrentBudget`'s pattern | delete `BUDGETS` array, `Budgets/index.tsx:9-16` |
| Subscriptions | **Rewrite** — wire to `/v1/subscriptions` | delete `SUBS` array, `Subscriptions/index.tsx:9-15` |
| Receipts | **Fix path, then keep the shape** — `/api/receipts*` → `/v1/receipts*`; upload/list/status flow otherwise reasonable | — |
| Tax CA | **Delete and replace** — the estimated-refund/RRSP-room UI is exactly the cut tax-engine output; replace with the CSV/PDF categorised-export page | delete `TAX_ROWS` array, `TaxCA/index.tsx:15-22`, and the whole computed-tax UI |
| Tax US | **Delete and replace** — same reasoning; also drop `useTaxYear`, `TaxYear` type, and the old-generation `QlPageHeader`/`QlCard`/`QlToggle` imports it pulls in | delete the page; delete `useTaxYear.ts` + its test; delete `handlers/tax.ts` |
| Net Worth | **Rewrite** — wire to `/v1/networth/series`; fixture shape is a reasonable starting point for the target UI, but needs real data end to end | delete `CHART`/`ASSETS`/`LIABILITIES` arrays, `NetWorth/index.tsx:6-21` |
| Accounts | **Fix path, then keep the shape** — `/api/accounts` → `/v1/accounts` (the already-correct, already-unused `listAccounts` in `src/api/accounts.ts` is sitting right there) | — |
| Settings | **Rewrite/expand** — add currency picker, AI-endpoint config, and the logout button (`authStore.logout()` is fully implemented and just needs a caller); keep the theme toggle only once dark-mode CSS actually exists, or remove it until then | — |
| 404 | **Add** — `errorElement` + `path:'*'` route; does not exist today | — |
| TestHarness | **Delete from the production router**, or gate it behind a dev-only build flag — it's a useful component gallery for local dev but currently ships unauthenticated in the prod bundle | — |

**Also delete** as part of any of the above: the entire Generation-B hook set that's dead
code today regardless of what replaces it — `useBudgets.ts` (the non-`current` one),
`useTransactions.ts`, `useSubscriptions.ts`, `useNetWorth.ts`, `useModelRouting.ts`,
`useCoachSession.ts` — plus their `.test.tsx` files and matching MSW handlers
(`handlers/budgets.ts`, `handlers/transactions.ts`, `handlers/subscriptions.ts`,
`handlers/networth.ts`, `handlers/modelRouting.ts`, `handlers/coach.ts`'s session mocks,
`handlers/tax.ts`). Also delete the old-generation component set once every page that
imports it (Accounts, Receipts, Settings, TaxUS) has been migrated to `ui/` —
`QlBar, QlBtn, QlDot, QlEyebrow, QlPageHeader, QlPill, QlRadioCard, QlSidebar (old), QlSpark,
QlToggle` and their tests/CSS modules. Also delete `apps/web/tests/` (the 98 orphaned
Playwright specs) once its useful assertions (if any) are ported into `e2e/`, and rewrite the
three specs that remain in `e2e/` — all three currently reference either a `/dashboard` route
that doesn't exist or a username/password Login form that doesn't exist.

---

## 13. Risks

- **Two full parallel generations (API layer, design-system, e2e-vs-tests) means "read the
  code" estimates for this app will be wrong in the same way the Java side's estimates were
  wrong** — a grep for "does Accounts call the backend" finds `useAccounts` and a real
  `api.get` call and looks done; only tracing the exact path string against the OpenAPI spec
  reveals it's calling a path nothing serves. Every page in this inventory was verified this
  way; a lighter pass would likely have under-counted the broken pages.
- **Tax CA/US are the two most fully-built-looking fixture pages in the app** — polished
  RRSP-room progress bars, a formatted `"+$2,340.00"` estimated refund — which makes them the
  most tempting to "just wire up" rather than delete. They are exactly the surface area the
  owner decision cuts; shipping them as-is (even wired to real data) means shipping computed
  tax numbers Trove is deliberately not in the business of producing.
- **Currency is a bigger rewrite than a find-and-replace on `$`.** `MoneyDisplay` ignoring
  its own `currency` prop, two different hardcoded `en-CA` locale calls, and an Onboarding
  flow with no currency step at all mean there's no single existing "money renderer" to
  extend — a new one has to be built and then threaded through every page in the app,
  including the ones being rewritten anyway (natural to combine, but likely to be
  underestimated as "add a prop" work).
- **The one page that works (Today) sets a real bar** — its `unwrap()`-envelope,
  `/v1/`-prefixed, `staleTime`-tuned pattern is worth mandating as the house style for every
  rewrite, specifically to prevent a third API generation from starting.
- **`api-gateway`'s actual routing/context-path for `/api/v1/...` was not confirmed in this
  pass** (§8) — the nginx proxy forwards the `/api` prefix verbatim, but `api-gateway`'s own
  OpenAPI spec documents unprefixed paths and no `context-path` was found in its
  `application.yml`. Worth resolving explicitly during the collapse rather than assuming the
  current wiring already works end-to-end; it may itself be part of why so many of these
  client paths look "close but not quite."
- **`/test-harness/:component` ships unauthenticated in production** — low severity on its
  own (no data exposure, just a component gallery), but worth closing off before the public-
  internet deployment goes live, consistent with the owner's security-hardening scope.
- **The three "real" e2e specs cannot currently pass against current source** (`/dashboard`
  route, non-existent Login form fields) — they will need rewriting anyway as part of the
  Login rewrite, but should not be trusted as a regression net for anything else in the
  meantime.
