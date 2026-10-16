# Trove — Senior Reviews and Scope Recommendation

> **Historical record, written before 2026-09-03.** Reviews of `01-FINDINGS.md` and the scope cut taken from them. The decisions listed as open below were settled by the owner; see the Decisions table in `06-PROGRESS.md`. Current state: `docs-site/guide/architecture.md`.

Four independent senior reviews of the verified findings (`01-FINDINGS.md`), plus the
resulting scope recommendation and the decisions still open.

---

## Where all four reviewers agreed, without being asked to

Three conclusions arrived independently from different starting points — the strongest signal
in the assessment:

1. **Delete far more than you fix.**
2. **Build one end-to-end test before writing any feature code.**
3. **The tax engine must not ship.**

---

## Business Analyst

> **Proceed — but only on a scope that deletes ~70% of the repository.**

Measured the only way that matters — working end-to-end user journeys — this project is roughly
**3% done**: one page reaches a live backend, behind a login flow that locks first-time users
out permanently and a gateway that strips `Authorization`.

**The scope argument that should govern everything:** defect density per line is roughly
constant for a given team and process. The only lever a small team has is **line count**.
45,600 lines with one working page cannot be made bug-free by any budget you would fund;
~12,000 lines with six working pages can. **"Fully functional, user-friendly, bug-free" is a
scope statement, not a quality statement** — choosing those words is already choosing the
deletion.

**Positioning is internally contradictory in three load-bearing places:**

- *"No required internet connection, API keys, or external accounts"* — yet the only wired
  ingestion channel is SimpleFIN, a hosted paid service, and onboarding **hard-blocks** on it.
- *Privacy as the marquee differentiator* — and privacy is the one claim this audience will
  actually audit, and the one that is false.
- *README sells UK + US* — the code hardcodes CAD in at least eight places and the only tax page
  with a UI is Canadian.

**Target user is real** — the self-hosting privacy audience is active and underserved on exactly
this problem (Firefly III has no bank connectivity worth the name; Actual Budget is envelope-only;
Maybe is unmaintained). But: small market, near-zero willingness to pay, **extreme** technical
sophistication (*these users read your code*), and **zero tolerance for a broken install**. Their
entire evaluation happens in the first fifteen minutes. Trove currently fails at minute three.

**Top risks:** wrong tax numbers (VH/VH) · regulatory perimeter around bank credentials — low
while self-hosted, which is *the argument for never offering a hosted instance* · silent data
corruption (M0, D1, F2, F3 — none of them throw) · security defects on an internet-exposed
self-host · **bus factor of one** · the dependency treadmill that already killed it once · the
"90% done" illusion (any estimate from reading the code will be wrong by 3×) · provenance and
AGPL enforceability if the code is substantially LLM-generated.

---

## Architect

> **Ten microservices was the wrong call, decisively. Collapse to a modular monolith.**

A single-user, single-machine application carrying ~6.8 GB resident across 21 containers,
against a stated Raspberry Pi / NAS target — roughly an order of magnitude over the hardware
class. ADR-0001 justified the split on scaling properties the product will never need, and
retired its risks with "timeouts and circuit breakers" that do not exist in `ProxyController`.

**Recommendation:** 1 JVM + Postgres + optional Ollama = **3 containers, ~1 GB**. Keep ~70% of
the code; delete nine `main()` methods.

**Root cause of the failure:** the project had **no feedback signal spanning two processes**.
Integration failure was not overlooked — it was *unobservable*. Generalisable lesson:
**the number of integration seams a team can maintain is bounded by the number its CI exercises
end to end.** Trove had 9+ and zero.

**Boundaries were drawn around features, not consistency requirements.** A transaction has seven
consequences distributed across six services with no broker; **zero were implemented.**
ADR-0004 (envelope budgeting) was *correct* — and the decomposition made it unimplementable,
which is exactly why `Envelope.spent` exists as a column with no writer.

**Additional findings:**

- **"11 databases" is the worst of both worlds** — it is *one* Postgres process with 11 catalogs,
  paying the full cost of database-per-service (no joins, no FKs, no transactional consistency)
  and receiving none of the isolation benefit.
- **Broken hostname default** — `report-service:42` and `subscription-detector:40` default to
  `http://ingestion-service:8081`, a hostname defined in **no** compose file (the container is
  `ingestion-svc`). Only `infra/full-stack/` overrides it.
- **GC misconfiguration** — `-XX:MaxRAMPercentage=75.0` with **no `mem_limit` anywhere**: ten
  JVMs each sizing heap to 75% of *host* RAM.
- **M0 is a modelling failure, not a coding bug** — `sourceId` has two coherent readings ("id
  *of* the source" vs "id *from* the source"). The normalizers hold one; ADR-0009, dedup and the
  unique index hold the other. **The database was correct throughout; the name was ambiguous.**

Published companion: **Trove Structural Survey** — (private artifact, not published)

---

## Product Designer

> **The front door is locked from the inside, and the first thing the product says to a human
> being is that they did something wrong.**

Nobody has ever completed a first run. That is not a polish problem; it is the product.

The encouraging part: **~80% of the UX damage is fixed by about fifteen files** — a logout button
that already exists but is imported by nothing, a `slice(0, 5)`, a missing `isError` branch, an
error-message map missing one key, and five page components that should be deleted rather than
fixed.

**Recommended first-run redesign:**

- A `/welcome` route that is **always reachable and never redirected**, with four states:
  first-run, ready, degraded (name the container and print the log command), unreachable.
- **The hard rule that fixes I4: an anonymous 401 is not an error.** Distinguish "no session ever
  existed" from "a session expired." ~15 lines, highest ROI change in the web app.
- **Onboarding: three ungated steps, with `onboardingCompleted` set at the end of step 1.** From
  that moment no user can ever be locked out. Everything after becomes progressive disclosure via
  a "Finish setting up Trove" checklist on Today.
- Delete the Deployment step (asks a question whose answer changes nothing, and offers a "Cloud"
  option a self-hosted product does not have) and the First Budget step (budgets are inert, and a
  first budget with zero transactions is a number the user invents about themselves).
- **Five non-negotiable escape hatches:** a logout control on every authenticated route; "Skip for
  now" on every integration step; "Restart setup"/"Sign out" in the onboarding footer; `AuthGuard`
  fails *open* on preferences errors; a 404 route and `errorElement`.

**Self-hosting UX:** one compose file with three profiles, one `.env.example` generated from a
schema, and one verb set — `./trove up | down | status | logs | backup | restore | update | reset`.
`./trove up` must print named checks with the fix on the next line, generate secrets rather than
default them, show the admin password once, and **never map `down` to `down -v`**.

**Information architecture:** five primaries — Today · Money (Transactions/Accounts/Net Worth) ·
Plan (Budgets/Subscriptions/Goals) · Tax (with Receipts as its evidence tab) · Settings. Coach
becomes a persistent side panel, not a nav item. Enforce the count in the type system
(`[NavItem × 5]` tuple) so adding a sixth is a compile error rather than a silent amputation.

**Trust — the hard rule to adopt:**

> **No number that could be mistaken for the user's money may originate anywhere but the user's
> data.** If a route is not wired to a live endpoint, it is not in the router and not in the nav.
> A missing feature costs a user nothing; an invented one costs the product its credibility.

Enforce with a CI lint rule failing on currency- and percentage-shaped literals in `src/pages/**`
and `src/components/**` (~20 lines). Make `MoneyDisplay`'s `currency` prop **required** so every
call site becomes a compile error and gets audited.

**Dark mode: implement on web (2–3 days — the tokens are already role-named), remove the toggle
from mobile until its data layer works.** A dead toggle is the worst of the three options.

---

## Security

> **Do not expose this to the internet. On a shared LAN, treat it as an unauthenticated data
> store containing bank credentials.**

**The chain that decides everything:**

> Ports published on the host (all ten, 8080–8089) → the gateway is not in the request path →
> the JWT secret is a repo-published default engineered to pass its own 32-char check → mint a
> valid token → identity comes from a client-controlled `X-User-Id` → **full read/write over
> every user's financial data, from an unauthenticated start, over the LAN.**

Every link is trivially exploitable. Two of the three fixes take under three hours.

**Threat model:** the most likely attacker is *another device on the home LAN* — and the
deployment model guarantees one exists. Also realistic: any legitimate user escalating to
others (multi-user households are a stated use case); a malicious web page doing DNS-rebinding
or plain CSRF against `localhost:8081`, which has no CSRF protection and, under the IDOR, needs
no credential at all; and **supply-chain compromise via unattended Dependabot auto-merge, which
is a live, ongoing surface**.

**Findings correctly de-prioritised** (honesty about severity matters as much as raising it):

- Webhook DNS-rebinding — **not exploitable today**; webhook delivery has no producer. Fix it as
  part of finishing that feature, not before.
- IMAP inbox-to-LLM — **unreachable today** (plugin on no classpath). Note the nastier property:
  *anyone who can email the victim controls LLM input*. Must not be put on a classpath without a
  rewrite.
- `ServiceTokenAuthFilter` timing leak — genuinely theoretical. One-line fix, but do not let it
  displace the IDOR.
- `/test/**` permitAll — **no controller currently maps it**, so prophylactic rather than an
  active breach.
- model-manager having no auth is **fail-closed** — an availability bug, not a vulnerability.
  **The security risk is the fix:** whoever wires it must not "solve" it with `permitAll`.

**The privacy claim needs escalating beyond engineering.** It is a factual claim about the
handling of financial data, backed by a badge asserting automated verification, that the product
cannot substantiate. **The claim induces the exposure** — a user who believes "100% local"
connects their real bank. Five conditions must hold before it can be made again: rules actually
loaded (empty ruleset = boot failure, never a default); one policy and one enforcement point;
**destination verified in code, not config**, behind a mandatory egress interceptor that cannot
be bypassed; egress denied by default at the network layer; and the claim narrowed to what is
true.

**Recommended disclosure:** because the cloud blocks were commented out by default *and*
model-manager 401s every request, the likely honest answer is **"no data left any machine,
because the AI features never functioned at all."** That is a good answer — establish it with
evidence and say it plainly.

**Secrets principle:** *in a self-hosted product, any secret with a default is a public secret.*
Two acceptable behaviours only — **generated at install time**, or **absent and fatal at
startup**. Change every `${VAR:-default}` to `${VAR:?}` in the first hour. Reject known-bad
values explicitly; a length check alone is exactly what let the shipped default through.

**Deployment line:** LAN-only is acceptable after Tier 0 + Tier 1 (~3 weeks). Internet exposure
needs Tier 2 in full plus an external pen test. **The better product answer: don't expose it** —
ship a documented Tailscale/WireGuard path as the *primary* remote-access story.

---

## Recommended scope — "Trove Core"

> **Single jurisdiction (US/USD). Web only. Your bank transactions, imported into a database you
> own, categorised and budgeted — installed in under fifteen minutes.**

**Architecture:** collapse 10 services → 2 (`api-gateway` BFF + `trove-core`). Mostly deletion,
not merging. Eliminates the entire bug class that killed the project — B1–B4, M1, M7 and M11
become *inexpressible* — and makes D1 a method call.

### IN

| # | Feature | Justification |
|---|---|---|
| 1 | Local user accounts (OIDC optional) | Mandatory Authentik has blocked every install ever attempted; this also unblocks the disabled IT suite, which was blocked on precisely `/auth/register` |
| 2 | Logout button + skippable onboarding | Without them a tokenless self-hoster is permanently locked in the app |
| 3 | CSV + OFX import | Always-available path, works in every country; the OFX code exists and just needs a classpath |
| 4 | SimpleFIN — **optional, not mandatory** | Automatic capture *is* the value proposition, but it must be skippable and the SSRF must be closed first |
| 5 | Transactions: list, filter, sort, paginate, edit, split, manual entry | Requires fixing M0 and B3 |
| 6 | Rules-first categorisation, LLM optional and **off by default** | A seeded merchant map + user rules gets ~85% accuracy with zero inference cost and zero privacy exposure |
| 7 | Envelope budgets with real spend tracking | Fixes D1; in-process after the collapse |
| 8 | Net worth — manual assets/liabilities, single currency | Fixes F2; cheap, high perceived value |
| 9 | Six web pages: Today, Transactions, Budgets, Accounts, Net Worth, Settings | Finish the `src/api` migration already in flight; delete every fixture |
| 10 | Tax-time CSV/PDF export | Replaces the tax engine with something useful and liability-free |
| 11 | One install path with backup/restore | `infra/full-stack/` is good and isn't linked from the README; **no backup tooling exists at all** |
| 12 | One real end-to-end test in CI | The single artefact whose absence explains everything else |

### OUT

Tax engines (**cut** — wrong arithmetic, unbounded liability, annual treadmill × 3 jurisdictions) ·
`Sa100PdfExporter` (**delete this week**) · Receipt OCR (**cut** — replace with "attach an image
to a transaction", 1 day vs 3,000 lines) · AI Coach (**cut**) · model-manager (**cut** — replace
with a settings field for an OpenAI-compatible base URL) · iOS + Android (**cut both** — ship a
responsive PWA: ~80% of the mobile value for ~2% of the cost, and no app-store review for a client
pointing at a user-supplied server) · Email/IMAP (**cut permanently**) · native notification
listeners (**cut** — dead on arrival) · subscription detection (**defer v1.1** — cheap once
transactions exist) · notifications (**defer v1.1**) · report service (**cut**) · privacy engine
(**delete** — replace with one honest README paragraph) · plugin architecture (**delete** — fold
simplefin and ofx into core) · Kubernetes (**delete the docs**) · Grafana (**narrow** to panels
backed by real metrics) · `registry/` (**delete**) · `TestHarness` route (**delete**).

**Result:** ~45,600 LOC → **~12–14k**. 10 services → 2. 3 clients → 1. 3 jurisdictions → 1.
14 pages → 6.

---

## Decisions still open — needed before Phase 1

1. **Scope.** Recommendation is the cut above. Keeping mobile, tax or OCR each adds months and,
   for tax, liability. **Keeping everything is the one option that does not reach "bug-free" at
   any budget.**
2. **Architecture.** Collapse to two services, or complete the current topology? Every reviewer
   recommends collapsing. It is mostly deletion — but it is a real change and should be an
   explicit decision, not an assumption.
3. **Jurisdiction and currency.** README sells UK + US; the code hardcodes CAD in ≥8 places and
   the only tax page with a UI is Canadian. Pick one to be correct in first.
4. **Exposure model.** LAN-only after two phases, or internet-exposed (full hardening + external
   pen test)? A documented Tailscale/WireGuard path is cheaper and the better answer for this
   audience.

**Do this week regardless of the answers:** enable required status checks (5 minutes, stops four
months of accumulating damage), and **rewrite the README to describe what exists** — it is
currently the project's largest single liability.
