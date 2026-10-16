# Inventory: services/tax-engine

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild tax engine, which was cut and not carried over. Current state: `docs-site/guide/architecture.md`.

Verified against source at commit `9ad1b5f` (working tree, 2026-09-03). Every claim below was
read directly from the cited file; nothing here is carried over from README.md (fiction — see
`docs/recovery/01-FINDINGS.md` §N) without independent verification. Cross-references to the
prior audit's Section E (E1–E12) were re-verified line-by-line against current source; all still
hold except line numbers are cited fresh.

---

## 1. Purpose and LOC

Computes an "informational, not filing-grade" income-tax estimate for three jurisdictions (CA
federal+provincial, US federal+state+FICA, GB income tax+NI) from user-entered tax profiles and
slips, and tracks CRA registered-account contribution room (RRSP/TFSA/FHSA). Also contains a
never-wired SA100 PDF exporter.

- LOC main: **2656** (`find services/tax-engine/src/main -name '*.java' | xargs wc -l | tail -1`)
- LOC test: **1083** (`find services/tax-engine/src/test -name '*.java' | xargs wc -l | tail -1`)

## 2. Spring wiring

- Application class: `TroveTaxEngineApplication` (`src/main/java/finance/trove/tax/TroveTaxEngineApplication.java:27`), plain `@SpringBootApplication`, component scan rooted at `finance.trove.tax` (services/rest of `libs/common` is **not** auto-scanned).
- `@Configuration` / `@Import`:
  - `TaxSecurityConfig` (`config/TaxSecurityConfig.java:30-31`) — `@Import({SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class, RequestIdMdcFilter.class})`, all from `libs/common`.
  - `TaxRatesConfig` (`rates/TaxRatesConfig.java:15-18`) — `@EnableConfigurationProperties({CaTaxRates.class, UsTaxRates.class})`. **No `GbTaxRates` class exists and GB is not registered here** — confirms prior E8.
  - `finance.trove.common.config.JacksonConfig` (snake_case naming strategy, `libs/common/src/main/java/finance/trove/common/config/JacksonConfig.java`) is **NOT imported anywhere in this module** — neither via `@Import` nor autoconfiguration file (none found under `libs/common/src/main/resources`). Tax-engine's JSON uses Spring Boot's default (camelCase) naming; DTOs (`CreateTaxProfileRequest.filingStatus`, `provinceOrState`, etc.) and the IT tests (`TaxCalculationControllerIT.java:38-42`) confirm camelCase wire format. **Merge risk** — see §12.
  - No custom auto-config files (`*.imports`, `spring.factories`) anywhere in `libs/common` or this module.
- Security filter chain: shared `SecurityConfig` bean (`libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:60-89`). Stateless (no session), CSRF disabled, CSP/HSTS/frame-deny/referrer-policy/permissions-policy headers set. `permitAll` matchers: `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`, `/api/v1/health`, `/api/health` (line 84-87) — none of these paths exist in tax-engine's own controllers, so in practice every tax-engine route requires a JWT. `JwtAuthFilter` additionally bypasses (`shouldNotFilter`) any `/internal/**` path (`JwtAuthFilter.java:78-81`) — tax-engine has no such paths.
- Identity source: **JWT `sub` claim only.** `JwtAuthFilter.doFilterInternal` (`JwtAuthFilter.java:88-117`) reads `Authorization: Bearer <token>`, verifies HMAC-SHA256 with `trove.jwt.secret`, parses `sub` as a `UUID`, and sets a `TroveUserDetails` principal. Controllers read the caller via `@AuthenticationPrincipal TroveUserDetails user; user.getUserId()` (`calculation/TaxCalculationController.java:76,113`; `slip/TaxSlipController.java:60,70`). **No `X-User-Id` header is read anywhere in this module** (`grep -rn "X-User-Id"` → no hits).
- `@EnableScheduling` / `@EnableAsync` / `@Scheduled` / `@Async`: **none present** (grep confirms zero hits under `src/main`).
- JacksonConfig / naming strategy: see above — not wired; default camelCase in effect.

## 3. HTTP routes

All routes are in two `@RestController`s, both mounted at `@RequestMapping("/v1/tax")`. This is the **entire route surface** of the module — no other `@RestController`/`@RequestMapping` exists in `src/main` (grep-verified).

| METHOD | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| POST | `/v1/tax/{jurisdiction}/{year}/profile` | `TaxCalculationController#createProfile` (`calculation/TaxCalculationController.java:75-84`) | JWT `sub` via `@AuthenticationPrincipal` | `CreateTaxProfileRequest` (`filingStatus` `@NotBlank @Size(30)`, `provinceOrState` `@Size(3)`, `employmentType` `@Size(15)`) | `ApiResponse<TaxProfile>`, 201 | `jurisdiction` path var upper-cased and validated against `{CA,US,GB}` (line 128-134), else 422. Duplicate `(user,jurisdiction,year)` → 409 (`TaxProfileService.java:39-44`). No pagination. |
| GET | `/v1/tax/{jurisdiction}/{year}` | `TaxCalculationController#calculate` (`calculation/TaxCalculationController.java:120-128`) | JWT `sub` | none (path vars only) | `ApiResponse<TaxCalculationResponse>`, 200 | Requires a prior profile, else 404 (`ResourceNotFoundException`). Recomputes only if input SHA-256 hash changed (`TaxCalculationService.calculate`, line 90-163); otherwise returns cached row. No pagination. |
| POST | `/v1/tax/slips` | `TaxSlipController#createSlip` (`slip/TaxSlipController.java:60-90`) | JWT `sub` | `CreateTaxSlipRequest` (`slipType` `@NotBlank`, `taxYear` `@NotNull`, `issuer` `@NotBlank`, `amount` `@NotNull @Positive`, `currency` optional, **defaults to `"CAD"` if null**, line 88) | `ApiResponse<TaxSlip>`, 201 | Auto-links to the caller's sole profile for `taxYear`; 404 if none, 422 if the year is ambiguous across jurisdictions (multiple profiles same year — line 71-77). Invalidates the calculation cache for that profile (`calculationService.invalidateCache`, line 89). No pagination. |

**Endpoints that do NOT exist despite having entities/repositories/service logic reading them** (confirms prior E11 — re-verified against current source):
- No endpoint creates/lists `TaxDeduction` (no controller references `TaxDeductionRepository` for writes; only `TaxCalculationService` reads it, `calculation/TaxCalculationService.java:52,97`). Deductions are always `0` in practice.
- No endpoint creates/updates `RrspTfsaFhsaRoom` (only read in `TaxCalculationService.java:54,116-118`). RRSP/TFSA/FHSA room is always `0` in practice, meaning `CaTaxEngine`'s RRSP-over-contribution check (line 110-115) can never fire from real traffic and the RRSP deduction is always `0`.
- No endpoint calls `Sa100PdfExporter` — it is dead code (only referenced from its own test, `export/Sa100PdfExporterTest.java`; grep confirms zero references from `src/main` controllers).

## 4. Persistence

Database name (from `application.yml:8` and `docker/docker-compose.yml:232`): **`trove_tax`** (env `DB_NAME`, default `trove_tax`).

JPA entities → tables:

| Entity | Table | Key columns / FKs / unique / indexes |
|---|---|---|
| `TaxProfile` (`profile/TaxProfile.java`) | `tax_profile` | PK `id` UUID; `user_id`, `jurisdiction VARCHAR(2)` (CHECK `IN ('CA','US')`, widened to add `'GB'` by V6), `tax_year`, `filing_status VARCHAR(30)`, `province_or_state VARCHAR(3)`, `employment_type VARCHAR(15)` (CHECK `IN ('EMPLOYED','SELF_EMPLOYED','BOTH')` — note: Java default/only-documented values are `EMPLOYED`/`SELF_EMPLOYED`, `BOTH` is DB-only and never set by app code), `created_at`, `updated_at`. `UNIQUE(user_id, jurisdiction, tax_year)`. Index `idx_tax_profile_user_id(user_id)`. |
| `TaxDeduction` (`deduction/TaxDeduction.java`) | `tax_deduction` | PK `id`; `tax_profile_id` FK → `tax_profile(id) ON DELETE CASCADE`; `deduction_type VARCHAR(50)` free-form (not an enum — no category→tax-line mapping exists anywhere in this module, see §11); `amount NUMERIC(12,2)`; `currency VARCHAR(3) DEFAULT 'CAD'`; `transaction_id` (nullable, no FK constraint — soft link to an ingestion transaction); `document_url`; `jurisdiction VARCHAR(2)`. Indexes on `tax_profile_id`, `user_id`. |
| `TaxSlip` (`slip/TaxSlip.java`) | `tax_slip` | PK `id`; `tax_profile_id` FK → `tax_profile(id) ON DELETE CASCADE`; `slip_type VARCHAR(15)` CHECK `IN ('T4','T4A','T5','RRSP','W2','1099','1099INT','1099DIV')`, widened by V6 to add `'P60','P11D','SA100'`; `tax_year`; `issuer VARCHAR(200)`; `amount NUMERIC(12,2)`; `currency VARCHAR(3) DEFAULT 'CAD'`; `document_url`. Indexes on `tax_profile_id`, `(user_id, tax_year)`. |
| `RrspTfsaFhsaRoom` (`room/RrspTfsaFhsaRoom.java`) | `rrsp_tfsa_fhsa_room` | PK `id`; `user_id`; `tax_year`; `rrsp_room/rrsp_used`, `tfsa_room/tfsa_used`, `fhsa_room/fhsa_used` all `NUMERIC(12,2) DEFAULT 0`; `updated_at`. `UNIQUE(user_id, tax_year)`. Index `(user_id, tax_year DESC)`. |
| `TaxCalculationCache` (`calculation/TaxCalculationCache.java`) | `tax_calculation_cache` | PK `id`; `tax_profile_id` FK → `tax_profile(id) ON DELETE CASCADE`; result columns (`total_income`, `total_deductions`, `taxable_income`, `federal_tax`, `provincial_state_tax`, `total_tax`, `effective_rate NUMERIC(5,4)`, `marginal_rate NUMERIC(5,4)`, `tax_owing`, `currency DEFAULT 'CAD'`); `calculation_input_hash VARCHAR(64)`. `UNIQUE(tax_profile_id)` — this is why the service does update-in-place rather than delete+insert. |

Repositories with custom queries (all Spring-Data derived, no `@Query` annotations anywhere in the module):
- `TaxProfileRepository.findByUserIdAndJurisdictionAndTaxYear`, `.findAllByUserIdAndTaxYear` (`profile/TaxProfileRepository.java:28,39`)
- `TaxDeductionRepository.findAllByTaxProfileId` (`deduction/TaxDeductionRepository.java:23`)
- `TaxSlipRepository.findAllByTaxProfileId` (`slip/TaxSlipRepository.java:25`)
- `RoomRepository.findByUserIdAndTaxYear` (`room/RoomRepository.java:27`)
- `TaxCalculationCacheRepository.findByTaxProfileIdAndCalculationInputHash` (unused by any caller — `TaxCalculationService` only calls `findByTaxProfileId`), `.findByTaxProfileId`, `.deleteByTaxProfileId` (`calculation/TaxCalculationCacheRepository.java:27,36,45`)

Flyway migrations (`src/main/resources/db/migration/`):
| File | Summary | Tables/indexes |
|---|---|---|
| `V1__create_tax_profile.sql` | Creates `tax_profile`; enables `pgcrypto` extension for `gen_random_uuid()`. | `tax_profile`, `idx_tax_profile_user_id`, `uq_tax_profile_user_jurisdiction_year` |
| `V2__create_tax_deduction.sql` | Creates `tax_deduction`. | `tax_deduction`, `idx_deduction_profile_id`, `idx_deduction_user_id` |
| `V3__create_tax_slip.sql` | Creates `tax_slip`. | `tax_slip`, `idx_slip_profile_id`, `idx_slip_user_year` |
| `V4__create_rrsp_tfsa_fhsa_room.sql` | Creates `rrsp_tfsa_fhsa_room`. | `rrsp_tfsa_fhsa_room`, `idx_room_user_year`, `uq_room_user_year` |
| `V5__create_tax_calculation_cache.sql` | Creates `tax_calculation_cache`. | `tax_calculation_cache`, `uq_cache_profile` |
| `V6__add_gb_jurisdiction.sql` | Drops+re-adds the `jurisdiction` CHECK on `tax_profile` to add `'GB'`; drops+re-adds the `slip_type` CHECK on `tax_slip` to add `'P60','P11D','SA100'`; adds a column comment. | Alters `tax_profile`, `tax_slip` (no new tables) |

## 5. Config (application.yml + .env.example + docker-compose)

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `spring.application.name` | — | `tax-engine` | no |
| `spring.profiles.default` | — | `local` | no |
| `spring.datasource.url` (host) | `DB_HOST` | `localhost` | no |
| `spring.datasource.url` (port) | `DB_PORT` | `5432` | no |
| `spring.datasource.url` (db) | `DB_NAME` | `trove_tax` | no |
| `spring.datasource.username` | `DB_USER` | `trove` | **yes — placeholder default, unchanged in compose** |
| `spring.datasource.password` | `DB_PASS` | `trove` | **yes — placeholder default, unchanged in compose** (`.env.example:9`, `docker-compose.yml:234`, both literally `trove`) |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.jpa.open-in-view` | — | `false` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `spring.flyway.baseline-on-migrate` | — | `true` | no |
| `spring.flyway.baseline-version` | — | `0` | no |
| `spring.config.import` | — | `classpath:tax-rates-ca-2024.yml,classpath:tax-rates-us-2024.yml` | no |
| `server.port` | `SERVER_PORT` | `8083` (yml has no fallback token — hardcoded `8083`; `.env.example`/compose separately set `SERVER_PORT=8083`) | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none in `application.yml`** (`${TROVE_JWT_SECRET}` with no `:-default` — app fails fast if unset, per `JwtProperties.validate()`); but `docker-compose.yml:235` supplies `${TROVE_JWT_SECRET:-change-me-minimum-32-chars-in-production!!}` — **a default secret is injected at the compose layer**, and `.env.example:12` ships the same literal string labelled "CHANGE IN PRODUCTION" | **yes — default secret placeholder present in compose, easy to ship unchanged** |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |
| `management.endpoint.health.show-details` | — | `when-authorized` | no |
| `management.metrics.export.prometheus.enabled` | — | `true` | no |
| `JAVA_TOOL_OPTIONS` (compose/.env only) | `JAVA_TOOL_OPTIONS` | `-Xmx300m` | no |

Every `${VAR:-default}` placeholder in this module: `DB_HOST:localhost`, `DB_PORT:5432`, `DB_NAME:trove_tax`, `DB_USER:trove`, `DB_PASS:trove` (all in `application.yml:7-11`), plus the compose-layer `TROVE_JWT_SECRET:-change-me-minimum-32-chars-in-production!!` (`docker-compose.yml:235`).

## 6. Outbound HTTP

**None.** `grep -rn "RestClient|WebClient|RestTemplate|HttpClient" services/tax-engine/src/main` returns zero hits. This module makes no calls to any other Trove service or third party at runtime.

## 7. Scheduled/async

**None.** `grep -rn "@Scheduled|@EnableScheduling|@EnableAsync|@Async|ApplicationEvent" services/tax-engine/src/main` returns zero hits. No cron, no async methods, no application events published or consumed.

## 8. External dependencies

- **Postgres** — database `trove_tax` (own database per service, per prior finding O6 "11 databases"). Only external runtime dependency.
- No MinIO client code (doc comments in `TaxSlip.java:21` and `TaxDeduction.java:80` reference "stored in MinIO" for `documentUrl`, but no `MinioClient`/S3 SDK is imported or used anywhere in this module — it's a bare string column, presumably populated by another service).
- No Redis/Valkey, no Ollama/LiteLLM, no PaddleOCR, no SMTP, no SimpleFIN, no Authentik — none referenced.
- `com.itextpdf:kernel`/`layout` (iText) — used only by the dead `Sa100PdfExporter`.
- Actuator/Prometheus metrics exposed at `/actuator/prometheus`.

## 9. Tests

Counts by file (37 `@Test` methods total across 8 test classes):
- `BracketCalculatorTest` (6) — pure unit tests of the shared marginal-bracket algorithm.
- `CaTaxEngineTest` (6) — unit tests, real `TaxRatesConfig`-backed `CaTaxRates`/`UsTaxRates` beans via `@SpringBootTest(classes={...})` (not full context), no mocks.
- `UsTaxEngineTest` (8) — same pattern; one test builds a hand-rolled `UsTaxRates` to force AMT to bind.
- `GbTaxEngineTest` (4) — plain unit tests (`new GbTaxEngine()`), no Spring context.
- `TaxCalculationServiceTest` (5) — Mockito unit tests; mocks all 5 repositories, all 3 engines, both rate beans, and `ObjectMapper`. Verifies cache-hit/miss/stale-hash routing and jurisdiction dispatch.
- `Sa100PdfExporterTest` (2) — unit tests of the dead exporter class only.
- `OpenApiTest` (1, extends `BaseIT`) — full Spring context + Testcontainers Postgres; asserts `/v3/api-docs.yaml` is served and **writes the live spec to `../../openapi/tax-engine.yaml`** as a side effect of running the test (`OpenApiTest.java:30-32`) — i.e., the checked-in `openapi/tax-engine.yaml` is regenerated by running tests, not hand-maintained.
- `TaxCalculationControllerIT` (9, extends `BaseIT`) — full MockMvc + Testcontainers Postgres integration tests covering all 3 routes, 401/404/409/422 paths, and GB flow end-to-end.

`BaseIT` (`support/BaseIT.java`) spins up a real `postgres:16-alpine` Testcontainer, wires `DynamicPropertySource` to it, sets `trove.jwt.secret` to a fixed 32+ char test string, and uses `DirtiesContext(AFTER_CLASS)`. `JwtTestHelper` mints real HS256 JWTs signed with that same test secret.

**Tests that assert a bug as correct:**
- `CaTaxEngineTest.onSingle55k_correctFederalBracket` (`calculation/CaTaxEngineTest.java:33-50`) asserts `federalTax` equals `5297.66` for $55,000 gross T4 income under `ON`. That expected figure is computed with CPP and EI treated as pre-tax **deductions** from taxable income (per `CaTaxEngine.calculate`, line 99-131) — the modeling error already flagged as E10 in the prior audit (CPP/EI are non-refundable *credits* against tax owing under real CRA rules, not deductions from taxable income). The test locks in the wrong model as "correct."
- `GbTaxEngineTest.calculate_basicRateTaxpayer_correctIncomeTax` / `calculate_higherRateTaxpayer_appliesHigherBracket` (`calculation/GbTaxEngineTest.java:17-57`) and the matching IT test `gbJurisdiction_withP60Slip_calculatesCorrectIncomeTax` (`TaxCalculationControllerIT.java:144-166`) only exercise income levels above the personal allowance and above the basic-rate band; none construct a below-personal-allowance case, so the E6 marginal-rate bug (`GbTaxEngine.java:103` returns `BASIC_RATE` even at zero taxable income) is untested, not merely asserted-correct — worth flagging as a coverage gap alongside the CA case above.
- No test constructs a US slip set mixing W-2 with `1099INT`/`1099DIV` to check FICA base (E3), so that bug is likewise present but untested rather than locked-in by an assertion.

## 10. Verified defects

All re-verified directly against current source (line numbers below are fresh, not copied from the prior audit).

| id | summary | file:line | verified how |
|---|---|---|---|
| D1 (=prior E10) | CA: CPP and EI are treated as deductions from taxable income, not non-refundable tax credits. | `calculation/CaTaxEngine.java:99-131` (`totalDeductions = cppDeduction.add(eiPremium)...`) | Read calculation order; confirmed no credit-style subtraction from `netFedTax`/`netProvTax` exists for CPP/EI (only BPA is applied as a credit, lines 128-131, 144-147). |
| D2 (=prior E2/E3) | US: FICA (SS + Medicare) is computed on `grossIncome`, which sums W2, 1099, **1099INT, 1099DIV** — investment income is charged Social Security/Medicare tax. | `calculation/UsTaxEngine.java:69` (`INCOME_SLIP_TYPES` includes `1099INT`,`1099DIV`), `:89-92` (gross sum), `:97-108` (FICA computed from that same `grossIncome`) | Read the slip-type set and the FICA computation; both use the same undifferentiated `grossIncome`. |
| D3 (=prior E4) | US state tax is computed on federal taxable income (post standard/itemized deduction), not any state's actual tax base. | `calculation/UsTaxEngine.java:138-147` (`stateTax = BracketCalculator.calculateTax(taxableIncome, ...)`, using the same `taxableIncome` computed at line 123 from the federal deduction) | Traced `taxableIncome` variable from definition to state-tax call site — same value, no state-specific adjustment. |
| D4 (=prior E5) | US AMT income base starts from federal taxable income minus AMT exemption, not AGI; real AMTI starts from AGI and disallows the regular deduction. | `calculation/UsTaxEngine.java:129-131` (`amtIncome = taxableIncome.subtract(amtExemption)`, where `taxableIncome` already had the standard/itemized deduction subtracted at line 123) | Same trace as D3; `amtIncome` is downstream of the already-deducted `taxableIncome`. |
| D5 (=prior E6) | GB marginal rate returns 20% (`BASIC_RATE`) whenever `taxableIncome <= basicBand`, including `taxableIncome == 0` (income at/below the personal allowance). | `calculation/GbTaxEngine.java:103-105` | Read the ternary; `taxableIncome.compareTo(basicBand) <= 0` is true at `taxableIncome=0`, returning `BASIC_RATE` rather than `0`. |
| D6 (=prior E7) | GB National Insurance is computed on `adjustedIncome` (gross **minus** deductions), not gross pay; real Class 1 NI is calculated on gross pay before allowable deductions. | `calculation/GbTaxEngine.java:56` (`adjustedIncome = grossIncome.subtract(deductionTotal)`), `:90` (`niBase = adjustedIncome.subtract(NI_PRIMARY_THRESHOLD)...`) | Traced `adjustedIncome` from its definition (net of deductions) into the NI computation. |
| D7 (=prior E8) | `tax-rates-gb-2024.yml` is never loaded; no `@ConfigurationProperties`/`GbTaxRates` class exists; all GB constants are hardcoded in `GbTaxEngine`. | `calculation/GbTaxEngine.java:25-34` (hardcoded `BigDecimal` constants); `rates/TaxRatesConfig.java:17` (`@EnableConfigurationProperties({CaTaxRates.class, UsTaxRates.class})` — no GB class); `grep -rn "tax-rates-gb" src/main src/test` → 0 hits outside the resource file itself | Confirmed no class references the GB yaml filename and no GB rates config class exists anywhere in `src/main`. |
| D8 (=prior E9) | Rates are hardcoded to the 2024 basis with no year dispatch — `GbTaxEngine.calculate` takes no year parameter and the CA/US bracket lookups don't vary by requested `year` either (only `tax-rates-*-2024.yml` are ever imported). | `calculation/GbTaxEngine.java:46` (no year param); `application.yml:14` (`spring.config.import` only imports the `-2024` files, no year-keyed selection anywhere in `TaxCalculationService`) | Confirmed `calculate(TaxProfile, List, List)` signature has no year arg; confirmed `application.yml` imports exactly one hardcoded-year file per jurisdiction with no runtime selection logic. |
| D9 (=prior E11) | `TaxDeduction` and `RrspTfsaFhsaRoom` are fully modeled (entity+table+repository) and read by the calculation, but no endpoint exists to create/update either — so itemized deductions and RRSP/TFSA/FHSA contributions are always `0` for every real user, and the RRSP over-contribution guard (`CaTaxEngine.java:110-115`) is dead code in production traffic. | `deduction/TaxDeductionRepository.java` (no controller references it for writes); `room/RoomRepository.java` (same); confirmed only 2 `@RestController`s exist in the whole module (§3) | `grep -rn "RestController"` under `src/main` → only `TaxCalculationController` and `TaxSlipController`; neither imports `TaxDeductionRepository`/`RoomRepository`. |
| D10 (new) | `Sa100PdfExporter` is fully dead code in production — no controller, service, or scheduled job invokes it; it is reachable only from its own test. | `export/Sa100PdfExporter.java` (class exists, `@Component`); `grep -rn "Sa100PdfExporter" src/main` (excluding its own file) → 0 hits; only reference outside its own file is `src/test/java/finance/trove/tax/export/Sa100PdfExporterTest.java` | Grep across `src/main` for the class name found zero call sites outside its own declaration. |
| D11 (new) | Default JWT signing secret is baked into the compose layer and `.env.example`, both literally `change-me-minimum-32-chars-in-production!!`; `application.yml` itself has no default (fails fast), but the compose override reintroduces one. | `docker/docker-compose.yml:235`; `services/tax-engine/.env.example:12`; contrast with `services/tax-engine/src/main/resources/application.yml:20-21` (`${TROVE_JWT_SECRET}`, no fallback) | Read both files; compose's `${TROVE_JWT_SECRET:-change-me-...}` syntax supplies a default at the orchestration layer that the app-level config intentionally omits. |
| D12 (new) | `TaxCalculationCacheRepository.findByTaxProfileIdAndCalculationInputHash` is declared but never called (`TaxCalculationService` only ever calls `findByTaxProfileId` then compares the hash in Java). Dead repository method, harmless but worth pruning on merge. | `calculation/TaxCalculationCacheRepository.java:27`; `calculation/TaxCalculationService.java:101-104` | `grep -rn "findByTaxProfileIdAndCalculationInputHash"` → declared once, called zero times. |

## 11. Collapse recommendation

**Overall verdict: DELETE the calculation engines and the PDF exporter (owner decision — tax engine is cut). KEEP only the minimal profile/slip data-capture shape needed to seed a tax-time CSV/PDF export of categorised transactions, and even that should be REWRITTEN, not moved as-is, because nothing in this module currently does category→tax-line mapping.**

Per-package disposition (destination packages assume `finance.trove.core.tax.*` inside `trove-core`):

| Package | Verdict | Destination | Reason |
|---|---|---|---|
| `calculation` (`BracketCalculator`, `CaTaxEngine`, `CaTaxResult`, `GbTaxEngine`, `GbTaxResult`, `TaxCalculationCache`, `TaxCalculationCacheRepository`, `TaxCalculationController`, `TaxCalculationResponse`, `TaxCalculationService`, `UsTaxEngine`, `UsTaxResult`) | **DELETE** | — | Owner decision: tax engine is cut, calculators go. All 8 verified arithmetic defects (§10, D1-D8) live here; nothing is salvageable as calculation logic. `TaxCalculationController`'s two routes (`POST .../profile`, `GET .../{jurisdiction}/{year}`) are deleted with it. |
| `config` (`TaxSecurityConfig`) | **DELETE (as a file)** | n/a | Pure `@Import` boilerplate re-wiring shared `libs/common` beans (`SecurityConfig`, `JwtAuthFilter`, `JwtProperties`, `GlobalExceptionHandler`, `RequestIdMdcFilter`) that every other service also duplicates. In `trove-core` these beans are configured exactly once at the application root — this per-service duplicate config class disappears entirely, not just its content. |
| `deduction` (`TaxDeduction`, `TaxDeductionRepository`) | **REWRITE** | `finance.trove.core.tax.export` | The entity shape (amount, currency, `transaction_id` link, `document_url`, free-form `deductionType`) is a reasonable starting point for "deduction tagging" in the export feature, but `deductionType` has **no mapping to any tax line anywhere in this codebase** — there is no CA/US category→line-item table to reuse. Must be built new: a `deductionType`/category → `{jurisdiction, tax-line label}` mapping table plus a category picker on transactions. Keep the column shape (amount, currency, transaction_id, jurisdiction, document/receipt link) as a starting schema only. |
| `export` (`Sa100PdfExporter`) | **DELETE** | — | Dead code (§3, §10 D10); builds a UK SA100-style PDF from a `GbTaxResult` that no longer exists once GB tax calculation is cut. The *pattern* (iText `Document`/`Table` building) is a reasonable reference for the new CSV/PDF export builder but the class itself is not reusable — it's hardwired to `GbTaxResult` fields. |
| `profile` (`CreateTaxProfileRequest`, `TaxProfile`, `TaxProfileRepository`, `TaxProfileService`) | **REWRITE (shrink)** | `finance.trove.core.tax.export` | `TaxProfile` currently exists only to select a calculation engine (jurisdiction/filingStatus/provinceOrState/employmentType) — none of that is needed for a CSV/PDF export of already-categorised transactions except possibly `provinceOrState` (for provincial/state-specific line groupings) and `jurisdiction`/currency (already covered by the owner's per-user CAD/USD onboarding setting, so this whole concept may be redundant with the user-settings table rather than a separate `tax_profile`). Do not move `TaxProfile` as-is; design the export feature against the user's existing currency/region setting first and only add fields actually needed for CSV/PDF grouping. |
| `rates` (`CaTaxRates`, `TaxBracket`, `TaxRatesConfig`, `UsTaxRates`) plus `tax-rates-ca-2024.yml`, `tax-rates-us-2024.yml`, `tax-rates-gb-2024.yml` | **DELETE** | — | Only consumed by the deleted calculation engines. `tax-rates-gb-2024.yml` was already dead (D7) before this collapse. |
| `room` (`RoomRepository`, `RrspTfsaFhsaRoom`) | **DELETE** | — | RRSP/TFSA/FHSA contribution-room tracking is a calculator concern (feeds `CaTaxEngine`'s deduction and over-contribution check) and has no role in a plain categorised-transaction export. No endpoint ever populated it (D9) — zero real user data to migrate. |
| `slip` (`CreateTaxSlipRequest`, `TaxSlip`, `TaxSlipController`, `TaxSlipRepository`) | **REWRITE (shrink)** | `finance.trove.core.tax.export` | `TaxSlip` (T4/W2/1099/etc, issuer, amount, currency) is the closest thing in this module to "receipt/deduction tagging" input, but it's manually-entered slip totals, not derived from ingested/categorised transactions — the owner's export feature is meant to work off *categorised transactions*, which this module has no linkage to at all (no FK from `TaxSlip`/`TaxDeduction` to any `transactions` table beyond the soft, unenforced `transaction_id` UUID column on `TaxDeduction`). Treat as a design reference only, not code to move. |
| `TroveTaxEngineApplication` | **DELETE** | — | Service entry point disappears; tax-time export becomes a package inside `trove-core`, not its own Spring Boot app. |

**Nothing from `services/tax-engine/src/main` should be moved into `trove-core` unmodified.** The reusable surface for the tax-time export feature is: the general *shape* of `TaxSlip`/`TaxDeduction` (amount/currency/issuer/category/document-link/transaction-link columns) as schema inspiration, and the iText PDF-table-building pattern in `Sa100PdfExporter` as a coding reference — both to be rewritten against the actual categorised-transaction data model, not copied.

**Classes that duplicate `libs/common` or another service** (all deleted with the module, not moved, since `trove-core` wires each of these exactly once):
- `TaxSecurityConfig` — duplicates the same `@Import({SecurityConfig, JwtAuthFilter, JwtProperties, GlobalExceptionHandler, RequestIdMdcFilter})` pattern every other service repeats.
- No local `JwtAuthFilter`, `SecurityConfig`, `GlobalExceptionHandler`, `RequestIdMdcFilter`, `JwtProperties`, or `MinioStorageService` re-implementation exists in this module — it correctly imports the shared ones rather than reimplementing them. (Contrast with other services per the main audit if any reimplement these — tax-engine does not.)

**Tables to drop from the DB collapse plan** (all currently in the standalone `trove_tax` database, per §4): `tax_profile`, `tax_deduction`, `tax_slip`, `rrsp_tfsa_fhsa_room`, `tax_calculation_cache`. None of these five tables should be carried into the `trove-core` Postgres schema as-is; if the export feature needs persisted deduction/receipt tags, design a new table against the transactions schema instead of reusing `tax_deduction`/`tax_slip`.

## 12. Merge risks/gotchas

- **Bean-name clashes**: none specific to this module beyond the universal `TaxSecurityConfig`-style `@Import` duplication (every service imports the same 5 `libs/common` beans under a per-service `@Configuration` wrapper class with a different name — e.g. `TaxSecurityConfig` here — so no name collision today, but collapsing to one `@Configuration` per bean in `trove-core` is required and straightforward since none of the bean classes themselves are redefined locally).
- **Table-name clashes**: `tax_profile`, `tax_deduction`, `tax_slip`, `rrsp_tfsa_fhsa_room`, `tax_calculation_cache` — all being dropped (§11), so no clash risk against other services' tables, but do not accidentally reuse the name `tax_deduction`/`tax_slip` for the new export-feature tables without checking other services' migrations first.
- **Property-key clashes**: `trove.jwt.secret`, `spring.datasource.*`, `management.*` are the shared/common keys — identical pattern across services, no per-service override here to lose. `spring.config.import` (tax-rates yaml) disappears entirely with the calculators.
- **Static state**: none found — `BracketCalculator` is a stateless static-method utility (no mutable static fields); no other static/singleton state in the module.
- **Hardcoded ports/hostnames**: `server.port: 8083` (`application.yml:17`) and the Dockerfile's `EXPOSE 8083`/healthcheck URL (`Dockerfile:14,17-18`) — irrelevant once folded into `trove-core`'s single port, but note the docker-compose `TAX_URL: http://tax-engine:8083` reference in the gateway env block (`docker-compose.yml` line ~122 region, api-gateway service) will need removing when this service disappears.
- **Hardcoded currency (CAD) sites** — all of these default to `"CAD"` and will silently mislabel US-jurisdiction data if the export feature's currency isn't threaded through explicitly from the user's onboarding setting:
  - `slip/TaxSlip.java:80` — `@Builder.Default private String currency = "CAD";`
  - `deduction/TaxDeduction.java:70` — `@Builder.Default private String currency = "CAD";`
  - `slip/TaxSlipController.java:88` — `.currency(req.currency() != null ? req.currency() : "CAD")` (explicit fallback on every slip-creation request, regardless of the profile's actual jurisdiction — a US-jurisdiction slip posted without an explicit `currency` field is silently stored as CAD)
  - `slip/CreateTaxSlipRequest.java:16` (javadoc) — documents the CAD-default behavior as intentional.
  - `calculation/TaxCalculationCache.java:119` — `@Builder.Default private String currency = "CAD";` (overwritten per-jurisdiction by `TaxCalculationService`, lines 128/141/154, so less risky, but still a CAD-first default).
  - `calculation/TaxCalculationService.java:128` sets `"CAD"` literal for the CA branch (correct there, but confirms the pattern of literal currency strings scattered through the module rather than derived from a shared enum/constant).
- **Reads `X-User-Id`**: none — this module derives identity exclusively from the JWT `sub` claim (§2). Nothing here needs updating for that specific concern, but note it as a **positive** contrast if other services in the collapse *do* read `X-User-Id` (per the main audit, that pattern may exist elsewhere — check other module inventories).
- **JacksonConfig not wired** (§2): this module's JSON contract is camelCase by default while `libs/common`'s `JacksonConfig` (snake_case) exists and presumably *is* wired into other services. If `trove-core` standardizes on snake_case (the more common pattern per `JacksonConfig`'s existence), any code that survives from this module's DTOs (unlikely, per §11, but worth flagging) would need explicit re-verification of wire-format assumptions — the OpenAPI spec at `openapi/tax-engine.yaml` was generated under the camelCase regime and is now stale documentation, not a contract to preserve.
- **`transaction_id` on `TaxDeduction` is a bare UUID column with no FK constraint** (`db/migration/V2__create_tax_deduction.sql`) — if the export feature is rebuilt against a real `transactions` table, add a proper FK; the old column was decorative only (never enforced, never populated by any endpoint per D9).
- **OpenAPI spec is test-generated, not hand-maintained**: `OpenApiTest.java:30-32` writes `../../openapi/tax-engine.yaml` as a side effect of running the IT suite. If `openapi/tax-engine.yaml` is used anywhere as a source of truth (client codegen, docs site), it reflects whatever was last run locally, not necessarily this commit's actual routes — treat it as derived, not authoritative, when planning the collapse.
