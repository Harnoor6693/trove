/**
 * Wire types for `trove-core`, DERIVED from the generated OpenAPI schema
 * (`./generated/schema.d.ts`, produced by `npm run generate` from
 * `openapi/trove-core.yaml`) — the OpenAPI-first decision, 2026-09-04.
 *
 * Every type below that corresponds to a step-3 route (`/v1/status`,
 * `/v1/auth/*`, `/v1/me*`, `/v1/admin/invites*`) is a projection of
 * `components['schemas'][...]` or an operation's request/response body —
 * never hand-typed — so a spec change shows up here as a compile error, not
 * silent drift. `npm run check:generated` guards the generated file itself
 * against drift from the spec.
 *
 * What is still hand-written, deliberately:
 *  - `Money`, `ApiResponse<T>` and the jurisdiction constants: client-side
 *    conveniences with no `components/schemas` entry of their own (the spec
 *    composes the envelope per-operation with `allOf` rather than a generic —
 *    see the spec's header — so `ApiResponse<T>` is this client's own
 *    reusable shape for it, structurally identical to `SuccessEnvelope & {data}`).
 *  - Every DTO below marked "until step N regenerates": `openapi/trove-core.yaml`
 *    does not cover transactions, budgets, categories or accounts yet.
 */
import type { components, operations } from './generated/schema'

type Schemas = components['schemas']

/* ------------------------------------------------------- derivation helpers */

/** The `application/json` body of one operation's response status. */
type Json<
  Op extends keyof operations,
  Status extends keyof operations[Op]['responses'],
> = operations[Op]['responses'][Status] extends { content: { 'application/json': infer B } }
  ? B
  : never

/** Peels the envelope's `data` property, for the operations that have one. */
type Data<B> = B extends { data: infer D } ? D : B

/** The `application/json` request body of one operation, when it has one. */
type Body<Op extends keyof operations> = operations[Op] extends {
  requestBody?: { content: { 'application/json': infer B } }
}
  ? B
  : never

/* ------------------------------------------------------------------ money */

/** CAD and USD only — the owner decision, and `core`'s `CurrencyCode` enum. */
export type CurrencyCode = Schemas['CurrencyCode']

/** The two jurisdictions Trove reports in; `CurrencyCode.jurisdiction()` server-side. */
export type CountryCode = Schemas['CountryCode']

/** Locale is derived from the country at registration, overridable later. */
export type LocaleCode = Schemas['LocaleCode']

/**
 * The money wrapper. Client-side only — no route answers a bare `Money`, every
 * amount is a field alongside its own `currency` on a domain DTO — but every
 * call site that formats an amount goes through this shape (`useUserFormat`).
 */
export interface Money {
  amount: string
  currency: CurrencyCode
}

/* --------------------------------------------------------------- envelope */

/** One rejected input; `fieldErrors` is a list, not a map (server `ErrorResponse.FieldError`). */
export type FieldErrorItem = Schemas['FieldError']

/**
 * RFC 7807 problem details, carried inside `ApiResponse.error`. Derived from
 * `Schemas['ErrorResponse']` with one deliberate widening: the spec's `type` is
 * a closed 28-slug enum (the complete set the server can emit today), but a
 * client reading a slug off the wire treats it as an open string — new slugs
 * land in the spec before a client rebuild does, and `ApiError.code` just
 * returns whatever trailing segment arrived. Narrowing call sites (a `switch`
 * on `error.type`) still get literal-string comparison for free.
 */
export type ErrorResponse = Omit<Schemas['ErrorResponse'], 'type'> & { type: string }

export type PaginationMeta = Schemas['PaginationMeta']

/**
 * The client's own reusable envelope generic. The spec does not have one —
 * `SuccessEnvelope` is composed per-operation with `allOf` so generated types
 * get the exact `data`, not `unknown` (see the spec's header) — but every call
 * site in this client goes through one `unwrap<T>`, so one generic shape here
 * is worth the divergence. Structurally: `SuccessEnvelope & {data: T}` on
 * success, `ErrorEnvelope` on failure.
 */
export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: ErrorResponse
  meta?: PaginationMeta
}

/* ------------------------------------------------------------------- auth */

/** Household-scoped role. At least one ADMIN per household. */
export type HouseholdRole = Schemas['UserRole']

/** `GET /v1/auth/me`. */
export type MeResponse = Data<Json<'getMe', 200>>

/** The user object every session-bearing response carries. */
export type AuthUser = Schemas['AuthUser']

export type LoginRequest = Body<'login'>

/** Cookie-mode session: `__Host-trove_*` cookies are set, the body carries no tokens. */
export type CookieSession = Schemas['CookieSession']

/** Bearer-mode session: tokens travel in the body, no cookies are set. */
export type BearerSession = Schemas['BearerSession']

/** TOTP step-up: a 60-second `typ=totp_challenge` JWT, not a session. */
export type TotpChallenge = Schemas['TotpChallenge']

/**
 * `POST /v1/auth/login` and `POST /v1/auth/register` both answer this —
 * a session (cookie or bearer shape) or a second factor being owed.
 * Discriminate on `challengeToken`, exactly as both shipped clients already do
 * (the spec's `oneOf` has no discriminator property because the shapes are
 * disjoint enough not to need one).
 */
export type LoginResponse = Schemas['LoginResponse']

export function isTotpChallenge(r: LoginResponse): r is TotpChallenge {
  return typeof (r as TotpChallenge).challengeToken === 'string'
}

/** `POST /v1/auth/login/totp` — `code` is a TOTP code or a single-use recovery code. */
export type TotpLoginRequest = Body<'loginTotp'>

/**
 * `POST /v1/auth/register`. `inviteToken` is required unless registration is
 * open; `currency`/`country`/`timezone` are optional in the spec (server-side
 * defaults apply) even though this client always sends all three.
 */
export type RegisterRequest = Body<'register'>

/**
 * `GET /v1/auth/providers` — gates the OIDC button and the closed-registration
 * copy. `oidc.startUrl` is the absolute sign-on URL the spec adds (§ code
 * corrections — this client still links the static `OIDC_START_URL` below,
 * which resolves identically on the same origin; `startUrl` exists for a
 * client with no page origin to resolve a relative path against, i.e. mobile).
 */
export type AuthProviders = Data<Json<'getAuthProviders', 200>>

/**
 * `GET /v1/status` — unauthenticated, detail-free, and the one route in the
 * whole API that is NOT enveloped (confirmed against the spec's `info.description`
 * and `HealthController.java`).
 */
export type ServerStatus = Json<'getStatus', 200>

/* ------------------------------------------------------- me / preferences */

/**
 * `GET/PUT /v1/me/preferences`. Per the spec, `currency`/`country` ARE present
 * on this response now (read-only display — they live on `users`, not on
 * preferences) even though they are not writable through it; see
 * `PreferencesPatch` for the writable subset.
 */
export type Preferences = Data<Json<'getPreferences', 200>>

/**
 * The writable subset of `Preferences` — what `PUT /v1/me/preferences` actually
 * accepts. Deliberately NOT `Partial<Preferences>`: that would type `currency`/
 * `country` as settable, which the spec's `PreferencesUpdateRequest` refuses to
 * describe because the server does not accept them there.
 */
export type PreferencesPatch = Body<'updatePreferences'>

/** `PUT /v1/me` — the only identity fields a user may change after registration. */
export type UpdateProfileRequest = Body<'updateMe'>

/* ---------------------------------------------------------------- invites */

/**
 * `GET/POST /v1/admin/invites`, `DELETE /v1/admin/invites/{id}`.
 * Single-use, 72-hour, no SMTP required — the admin copies `url` and hands it
 * over. **`token` and `url` are `null` once the invite is no longer redeemable**
 * (accepted, revoked or expired) — narrower than this client's own pre-spec
 * assumption that `url` was always a string; see '## Code corrections'.
 */
export type Invite = Data<Json<'createInvite', 201>>

export type CreateInviteRequest = Body<'createInvite'>

/* ------------------------------------------------------- jurisdiction constants */

/**
 * The country ↔ currency ↔ locale mapping, mirroring `CurrencyCode.jurisdiction()`
 * in `core`. It lives in this file rather than in a page because it is contract
 * data, and because `src/api/types.ts` is the one place the currency-literal lint
 * rule permits `'CAD'`/`'USD'` to be written down. No `components/schemas` entry
 * holds this mapping — it is a client-side derivation of three independent enums.
 *
 * Currency is chosen once, at registration, and is not editable afterwards.
 */
export interface CountryProfile {
  country: CountryCode
  label: string
  currency: CurrencyCode
  locale: LocaleCode
}

export const COUNTRY_PROFILES: readonly CountryProfile[] = [
  { country: 'CA', label: 'Canada', currency: 'CAD', locale: 'en-CA' },
  { country: 'US', label: 'United States', currency: 'USD', locale: 'en-US' },
] as const

export function countryProfile(country: CountryCode): CountryProfile {
  const found = COUNTRY_PROFILES.find((p) => p.country === country)
  if (!found) throw new Error(`Unsupported country: ${country}`)
  return found
}


/* ============================================================================
 * Accounts, transactions, imports and the dashboard — DERIVED, step 201.
 *
 * The block that used to sit here was hand-written ("openapi/trove-core.yaml
 * does not cover these routes yet"). It does now, and every shape below is a
 * projection of `./generated/schema`. Three of the old shapes disagreed with the
 * server in ways that would have shipped:
 *
 *  - `ConnectedAccount` had no `syncStatus`, no `errorMessage`, no `createdBy`
 *    and no `householdId`, and it had a `balance` the server does not send at
 *    all (see the contract-gap note on `AccountResponse` below).
 *  - `DashboardToday` had no `timezone` and no `byCurrency`, so the screen could
 *    only ever show the caller's own currency and could not say which zone the
 *    day boundary came from.
 *  - `BankTransaction` had no `transferGroupId`, `importBatchId`, `tags`,
 *    `pending`, `manual` or `createdBy` — i.e. none of the fields the transfer
 *    affordance, the import undo and the delete rule are built on. Its
 *    `TransactionType` was a two-value union where the server has seven.
 *
 * Families B–E: `Category`, `BudgetType`, `Envelope` and `BudgetPeriod` are
 * deliberately left hand-written below; those routes belong to family B.
 * ========================================================================== */

/** The `multipart/form-data` request body of one operation, when it has one. */
type Multipart<Op extends keyof operations> = operations[Op] extends {
  requestBody?: { content: { 'multipart/form-data': infer B } }
}
  ? B
  : never

/** The `query` parameters of one operation, with every member optional. */
type Query<Op extends keyof operations> = operations[Op] extends {
  parameters: { query?: infer Q }
}
  ? NonNullable<Q>
  : never

/**
 * One page of a paged list, as this client consumes it.
 *
 * A paged operation's 200 requires BOTH `data` and `meta` in the same body, so
 * they are read out of the same response and travel together. The shipped
 * `listTransactions` returned `Promise<BankTransaction[]>` and dropped `meta` on
 * the floor, which silently truncated the member's ledger at the server's
 * unstated default of 50 rows. Nothing in this app returns a bare array for a
 * paged route again.
 */
export interface Page<T> {
  rows: T[]
  meta: PaginationMeta
}

/** A decimal string at the ledger's scale, e.g. `"12.34"`. Never cents, never a number. */
export type MoneyAmount = Schemas['MoneyAmount']

/* --------------------------------------------------------------- accounts */

/** How an account's rows arrive. `MANUAL` is the only kind manual entry accepts. */
export type SourceKind = Schemas['SourceKind']

/** The product type of an account. */
export type AccountType = Schemas['AccountType']

/** The account's own sync state, distinct from any one run's `SyncRunStatus`. */
export type AccountSyncStatus = Schemas['AccountSyncStatus']

/** One sync run's outcome. `PARTIAL_FAILURE` and `FAILED` are not HTTP errors. */
export type SyncRunStatus = Schemas['SyncRunStatus']

/**
 * `GET /v1/accounts`, `GET|PUT /v1/accounts/{id}`, `POST /v1/accounts`.
 *
 * **Contract gap, recorded rather than worked around:** there is no balance on
 * this schema, and no operation in the document answers a per-account balance.
 * The Accounts screen therefore shows no balance figure at all rather than
 * inventing or deriving one — a derived balance would be a number that looks
 * like the member's money and did not come from the API.
 */
export type AccountResponse = Data<Json<'getAccount', 200>>

/** `POST /v1/accounts`. `currency` defaults to the caller's own when omitted. */
export type AccountRequest = Body<'createAccount'>

/**
 * `PUT /v1/accounts/{id}`. Presentation only: `sourceKind` and `currency` are
 * deliberately absent because changing either would reinterpret money already
 * stored against the account.
 */
export type AccountUpdateRequest = Body<'updateAccount'>

/** `POST /v1/accounts/{id}/sync`, `GET /v1/accounts/{id}/sync-logs`, `POST /v1/imports/{id}/commit`. */
export type SyncLogResponse = Data<Json<'syncAccount', 200>>

/**
 * `POST /v1/accounts/exchange-setup-token`.
 *
 * The discriminator is `sourceKind: 'SIMPLEFIN'` — the shipped hand-written
 * shape called it `pluginId: 'simplefin'`, which the server would have rejected.
 * `setupToken` is a credential: never logged, never in a query string, never
 * echoed back to the screen.
 */
export type ExchangeSetupTokenRequest = Body<'exchangeSetupToken'>

/** `syncLogs` is a LIST, one run per connected account — not the single `syncLog` the old shape had. */
export type ExchangeSetupTokenResponse = Data<Json<'exchangeSetupToken', 201>>

/* ----------------------------------------------------------- transactions */

/** Seven values, not two. A `TRANSFER` row is excluded from every dashboard figure. */
export type TransactionType = Schemas['TransactionType']

/** `GET /v1/transactions`, `GET|PATCH /v1/transactions/{id}`, `POST /v1/transactions`. */
export type TransactionResponse = Data<Json<'getTransaction', 200>>

/** The query parameters `listTransactions` actually declares — no invented filter, none dropped. */
export type TransactionsQuery = Query<'listTransactions'>

/** `POST /v1/transactions`. `accountId` must name a `MANUAL` account or the server answers 409. */
export type ManualTransactionRequest = Body<'createManualTransaction'>

/**
 * `PATCH /v1/transactions/{id}` — **presence-meaningful**. An absent key is left
 * alone; a key present with `null` CLEARS the field. Never spread a whole form
 * state into this body: send only what the member changed.
 */
export type TransactionPatchRequest = Body<'patchTransaction'>

/* -------------------------------------------------------------- dashboard */

/** One currency's subtotals. Never added to another currency's — there is no rate in this product. */
export type DashboardCurrencyTotals = Schemas['DashboardCurrencyTotals']

/**
 * `GET /v1/dashboard/today`. `timezone` is the caller's own IANA zone, the one
 * the server computed the day and month boundaries in; the client never
 * recomputes a boundary from the browser clock.
 */
export type DashboardTodayResponse = Data<Json<'getDashboardToday', 200>>

/* ---------------------------------------------------------------- imports */

/** Only these two formats are importable. */
export type ImportSourceKind = Schemas['ImportSourceKind']

/** A batch is `PREVIEW` until it is committed; only a `COMMITTED` batch can be undone. */
export type ImportBatchStatus = Schemas['ImportBatchStatus']

/** What committing would do to one row. */
export type ImportRowVerdict = Schemas['ImportRowVerdict']

/** The mapping the sniffer detected for a CSV. */
export type CsvColumnMapping = Schemas['CsvColumnMapping']

/** The member's corrections to that mapping, sent with the commit. */
export type CsvColumnMappingRequest = Schemas['CsvColumnMappingRequest']

/** Offered only when the file's dates are genuinely ambiguous; the member picks one. */
export type DateFormatCandidate = Schemas['DateFormatCandidate']

/** One parsed row of the file, with its verdict and, when `INVALID`, the reason. */
export type ImportPreviewRow = Schemas['ImportPreviewRow']

/** `POST /v1/imports` (201) and `GET /v1/imports/{id}/preview`. Nothing is written by either. */
export type ImportPreviewResponse = Data<Json<'getImportPreview', 200>>

/** `POST /v1/imports/{id}/commit`. Optional; what it carries supersedes the detected mapping. */
export type ImportCommitRequest = Body<'commitImport'>

/** The multipart parts `POST /v1/imports` accepts. `accountId` is required for CSV. */
export type CreateImportBody = Multipart<'createImport'>

/* ============================================================================
 * Categories, merchant rules, budgets, envelopes and goals — DERIVED, step 202.
 *
 * The block that used to sit here was hand-written ("belong to family B, which
 * regenerates them from the same contract in its own step"). It does now. Two
 * of the old shapes disagreed with the server in ways that would have shipped:
 *
 *  - The old `Envelope` had no `health` at all — `EnvelopeHealth` (pace,
 *    over-pace, remaining, expected spend) is computed server-side on every
 *    read and never stored, so a client that recomputed it would drift from
 *    the ledger the moment "today" moved.
 *  - The old `BudgetPeriod` embedded `envelopes: Envelope[]` directly on the
 *    period. The real `BudgetPeriodResponse` carries no such field —
 *    envelopes are fetched separately via `GET /v1/budgets/{id}/envelopes`
 *    (a separate, unpaged list), so a client built against the old shape
 *    would never see an envelope at all no matter how many times it re-read
 *    the period.
 * ========================================================================== */

/* ------------------------------------------------------------- categories */

/**
 * `GET /v1/categories`, and the 200/201 of `PUT`/`POST /v1/categories`.
 * `isSystem` is true exactly when `householdId` is null; a system category
 * cannot be renamed, re-parented or deleted through this API (403 `forbidden`).
 */
export type CategoryResponse = Data<Json<'createCategory', 201>>

/**
 * `POST`/`PUT /v1/categories` body. Carries only `name`, `parentId` and `icon`
 * — never `id`, `householdId` or `slug`, the G3 fix: the old service bound the
 * JPA entity itself as the request body.
 */
export type CategoryRequest = Body<'createCategory'>

/** `EXACT` requires an exact normalised match; `PREFIX` a leading substring. */
export type MatchType = Schemas['MatchType']

/** How a merchant mapping came to exist. Only `RULE` rows are reachable through the rules controller. */
export type MappingSource = Schemas['MappingSource']

/** A decimal string in `[0.000, 1.000]` — never a JSON number. */
export type Confidence = Schemas['Confidence']

/** `GET /v1/categories/rules`, in priority order, and the 200/201 of the mutations. */
export type CategoryRuleResponse = Data<Json<'createCategoryRule', 201>>

/** `POST`/`PUT /v1/categories/rules(/{id})` body. `confidence` optional, defaults to `"0.900"`. */
export type CategoryRuleRequest = Body<'createCategoryRule'>

/**
 * `POST /v1/categories/rules/reorder` body. Sets each rule's `priority` to its
 * index in `orderedIds` (`0` first) — every id must already be one of the
 * caller's household's own `RULE`-sourced rows, or nothing is written.
 */
export type ReorderRulesRequest = Body<'reorderCategoryRules'>

/** `POST /v1/categories/rules/dry-run` body — a merchant string, writes nothing. */
export type DryRunRequest = Body<'dryRunCategoryRule'>

/**
 * The dry-run result. `source` is null when the match came from the built-in
 * community tier or the `Uncategorised` fallback, neither a persisted
 * household row.
 */
export type CategorisationResultResponse = Data<Json<'dryRunCategoryRule', 200>>

/* ------------------------------------------------------------------ budgets */

/**
 * How a period constrains its envelopes. `ZERO_BASED` is the invariant the
 * server enforces (envelope allocations must sum to `totalIncome`) — never
 * re-validated client-side beyond a non-blocking hint.
 */
export type BudgetType = Schemas['BudgetType']

/** `POST /v1/budgets` body. `currency`/`budgetType` default server-side when omitted or null. */
export type BudgetPeriodRequest = Body<'createBudgetPeriod'>

/** `PUT /v1/budgets/{id}` body — a full replace of the two mutable fields; `month`/`currency` are not writable. */
export type BudgetPeriodUpdateRequest = Body<'updateBudgetPeriod'>

/**
 * `GET /v1/budgets/current`, `GET/PUT /v1/budgets/{id}`, and `POST /v1/budgets`
 * (201). Carries no `envelopes` — those are `GET /v1/budgets/{id}/envelopes`,
 * a separate, unpaged list.
 */
export type BudgetPeriodResponse = Data<Json<'getCurrentBudgetPeriod', 200>>

/* ---------------------------------------------------------------- envelopes */

/**
 * `POST /v1/budgets/{id}/envelopes` body. `spent` and `rolloverAmount` are
 * deliberately absent — both are computed server-side and never client-writable.
 */
export type EnvelopeRequest = Body<'createEnvelope'>

/** `PUT /v1/budgets/{id}/envelopes/{envelopeId}` body — `allocated` is the only writable field. */
export type EnvelopeUpdateRequest = Body<'updateEnvelope'>

/**
 * Computed on every read from the envelope's amounts and where today falls in
 * the period's month, in the CALLER's own timezone. Never stored, never
 * recomputed client-side.
 */
export type EnvelopeHealth = Schemas['EnvelopeHealth']

/**
 * `GET/POST/PUT /v1/budgets/{id}/envelopes(/{envelopeId})`. `spent` is the
 * ledger's own rewritten figure — never summed, never recomputed here.
 */
export type EnvelopeResponse = Data<Json<'getEnvelope', 200>>

/* ------------------------------------------------------------------- goals */

/** `POST /v1/goals` body. `currentAmount` and `achieved` are not accepted — a goal always starts at zero, unmet. */
export type GoalRequest = Body<'createGoal'>

/** `PUT /v1/goals/{id}` body — a full replace. `achieved` is derived server-side and never sent. */
export type GoalUpdateRequest = Body<'updateGoal'>

/** `GET /v1/goals`, and the 200/201 of the mutations. `achieved` is `currentAmount >= targetAmount`, server-derived. */
export type GoalResponse = Data<Json<'createGoal', 201>>


/* ============================================================================
 * The AI surface — coach, insights, AI settings and receipts. DERIVED, step 204.
 *
 * Nineteen operations, every shape below a projection of `./generated/schema`.
 * Nothing here is hand-typed, including the enums: `PrivacyTier`, `ChatTurnRole`,
 * `ReceiptStatus` and `ReceiptSource` all changed during the collapse (the
 * receipt enum lost `QUEUED` and `OCR`, the source enum lost `EMAIL`), so a
 * hand-written union would have been wrong on the day it was written.
 *
 * The one shape in this family that is NOT derivable is the SSE delta frame:
 * `createChatCompletion`'s `text/event-stream` response is `type: string` in the
 * document and its frame grammar lives in the operation's description prose. It
 * is therefore typed once, next to the parser, in `./sse.ts` — never here, and
 * never twice.
 * ========================================================================== */

/* ------------------------------------------------------------ ai settings */

/**
 * How far the member lets their own data travel. The coach payload is always
 * classified `SENSITIVE`, so `CLOUD_OK` is refused with 403 `ai-policy-blocked`
 * before any connection opens — `LOCAL_ONLY` is what the coach needs.
 */
export type PrivacyTier = Schemas['PrivacyTier']

/**
 * `GET /v1/ai/settings` and the 200 of `PUT`. **The API key is never in this
 * shape in any form** — `hasApiKey` is the whole of what the server says about
 * it. Nothing in this client may render, cache or log a key.
 */
export type AiSettingsResponse = Data<Json<'getAiSettings', 200>>

/**
 * `PUT /v1/ai/settings` — a REPLACE, not a patch. `enabled`,
 * `allowPrivateEndpoint` and `privacyTier` are required on every save and an
 * omitted nullable field is written as null, so a form must resend the whole
 * shape rather than the fields it changed. `apiKey` is the one exception: absent
 * or null leaves the stored key alone.
 */
export type AiSettingsUpdateRequest = Body<'updateAiSettings'>

/** `POST /v1/ai/settings/test` — `ok` is always true; every failure is a typed slug. */
export type AiConnectionTestResponse = Data<Json<'testAiConnection', 200>>

/** One entry of `GET /v1/ai/models`, narrowed by the server to three fields. */
export type AiModel = Schemas['AiModel']

/* ------------------------------------------------------------------ coach */

/** `USER` or `ASSISTANT`. `SYSTEM` never appears in a request or a response. */
export type ChatTurnRole = Schemas['ChatTurnRole']

/**
 * `POST /v1/chat/completions`. **`messages` is the turn being sent, not the
 * history**: the server takes the LAST entry, which must be `role: user`, and
 * rebuilds the preceding turns from its own store. Sending the transcript is
 * not merely wasteful — a client-supplied `assistant` turn is refused by design.
 */
export type ChatCompletionRequest = Body<'createChatCompletion'>

/** One turn in a `ChatCompletionRequest`; `role` is the lower-case OpenAI spelling. */
export type ChatTurnRequest = Schemas['ChatTurnRequest']

/**
 * The non-streaming answer. Deliberately not OpenAI's `choices[]` shape — one
 * answer, reachable without going through two arrays. `content` is text and is
 * never interpreted.
 */
export type ChatCompletionResponse = Data<Json<'createChatCompletion', 200>>

/** One stored turn from `GET /v1/chat/history`, decrypted for its owner. */
export type CoachMessageResponse = Schemas['CoachMessageResponse']

/** One weekly note from `GET /v1/insights`. Reading the page does not deliver it. */
export type InsightResponse = Schemas['InsightResponse']

/** The query `listChatHistory` declares: `sessionId`, `page`, `limit`. */
export type ChatHistoryQuery = Query<'listChatHistory'>

/** The query `listInsights` declares: `page`, `limit`. */
export type InsightsQuery = Query<'listInsights'>

/* --------------------------------------------------------------- receipts */

/**
 * Where a receipt is in its pipeline. `COMPLETE` and `FAILED` are terminal —
 * stop polling on either. `COMPLETE` with `reviewRequired: true` is the ordinary
 * outcome on an install with no vision model, not a failure.
 */
export type ReceiptStatus = Schemas['ReceiptStatus']

/** How the image arrived. Two values: there is no email ingress in this design. */
export type ReceiptSource = Schemas['ReceiptSource']

/** A count of items on a receipt line, as a decimal string — fractional values are real. */
export type DecimalQuantity = Schemas['DecimalQuantity']

/** `POST /v1/receipts/upload` — **202**, and three fields only. */
export type ReceiptUploadResponse = Data<Json<'uploadReceipt', 202>>

/** One extracted line of a receipt. Written once, never updated in place. */
export type ReceiptLineItemResponse = Schemas['ReceiptLineItemResponse']

/** One row of the paged `GET /v1/receipts`. */
export type ReceiptSummaryResponse = Schemas['ReceiptSummaryResponse']

/**
 * `GET /v1/receipts/{id}` and the 200 of both patches. `thumbnailUrl` is a PATH
 * on this API — never an absolute URL, never presigned, never a data URI — and
 * fetching it needs the caller's own credentials.
 */
export type ReceiptResponse = Data<Json<'getReceipt', 200>>

/** `GET /v1/receipts/{id}/status` — the four fields a poller needs. */
export type ReceiptStatusResponse = Data<Json<'getReceiptStatus', 200>>

/**
 * `PATCH /v1/receipts/{id}` — every field optional, an absent one left alone,
 * and no way to clear one back to null. A body with no property at all is 400.
 */
export type ReceiptPatchRequest = Body<'patchReceipt'>

/**
 * `PATCH /v1/receipts/{id}/review` — the reviewer's verdict, a distinct action
 * from a plain patch. `accepted: false` marks the receipt `FAILED`.
 */
export type ReceiptReviewRequest = Body<'reviewReceipt'>

/** The query `listReceipts` declares: `page`, `limit`. */
export type ReceiptsQuery = Query<'listReceipts'>

/** The multipart parts `POST /v1/receipts/upload` accepts: `file`, and `source`. */
export type UploadReceiptBody = Multipart<'uploadReceipt'>

/* ============================================================================
 * Net worth, subscriptions, tax export, notifications and webhooks — DERIVED,
 * step 203. Thirty-four operations, every shape below a projection of
 * `./generated/schema`. Nothing here is hand-typed.
 * ========================================================================== */

/* -------------------------------------------------------------- net worth */

/** The window `getNetWorthSeries` reports. `ALL` has no lower bound. */
export type NetWorthRange = Schemas['NetWorthRange']

/** What kind of asset a row is, for display and reporting only. */
export type AssetType = Schemas['AssetType']

/** What kind of liability a row is, for display and reporting only. */
export type LiabilityType = Schemas['LiabilityType']

/** `GET /v1/networth/assets`, and the 200/201 of the mutations. Not paged. */
export type AssetResponse = Data<Json<'listAssets', 200>>[number]

/** `POST /v1/networth/assets` body. `currency` defaults to the caller's own when omitted or null. */
export type AssetRequest = Body<'createAsset'>

/** `PUT /v1/networth/assets/{id}` body — `currency` is deliberately absent; changing it would reinterpret `value`. */
export type AssetUpdateRequest = Body<'updateAsset'>

/** `GET /v1/networth/liabilities`, and the 200/201 of the mutations. Not paged. */
export type LiabilityResponse = Data<Json<'listLiabilities', 200>>[number]

/** `POST /v1/networth/liabilities` body. `interestRate` defaults to zero when omitted or null. */
export type LiabilityRequest = Body<'createLiability'>

/** `PUT /v1/networth/liabilities/{id}` body — a full replace; `currency` is absent for the same reason as an asset's. */
export type LiabilityUpdateRequest = Body<'updateLiability'>

/** One `net_worth_snapshot` row — one currency, one date. `POST /v1/networth/snapshot` answers an array of these. */
export type NetWorthSnapshotResponse = Data<Json<'takeNetWorthSnapshot', 201>>[number]

/** One dated point on a single-currency net-worth series. */
export type NetWorthPoint = Schemas['NetWorthPoint']

/** One currency's net-worth history over the requested range. Points are oldest first. */
export type NetWorthSeries = Schemas['NetWorthSeries']

/**
 * `GET /v1/networth/series`. `series` is grouped by currency (F2) — render one
 * chart line or small multiple PER CURRENCY, never a single blended line.
 * `primaryCurrency` is the caller's own `users.currency`.
 */
export type NetWorthSeriesResponse = Data<Json<'getNetWorthSeries', 200>>

/** The query `getNetWorthSeries` declares: `range`. */
export type NetWorthSeriesQuery = Query<'getNetWorthSeries'>

/* ---------------------------------------------------------------- subscriptions */

/** The cadence a recurring charge was classified into. */
export type BillingCycle = Schemas['BillingCycle']

/** How a subscription looked at the last scan: `GREEN`/`AMBER`/`RED`. */
export type SubscriptionSignal = Schemas['SubscriptionSignal']

/** What a `subscription_alert` row is about. Only `PRICE_INCREASE` is written in this version. */
export type SubscriptionAlertType = Schemas['SubscriptionAlertType']

/**
 * `GET /v1/subscriptions`, `GET /v1/subscriptions/{id}`, `POST /v1/subscriptions/scan`.
 * Written only by detection — there is no create or update route. `cancelled`
 * is the only field a member ever changes, through the DELETE (soft delete).
 */
export type SubscriptionResponse = Data<Json<'listSubscriptions', 200>>[number]

/** `GET /v1/subscriptions/alerts`, and the 200 of `markSubscriptionAlertRead`. */
export type SubscriptionAlertResponse = Data<Json<'listSubscriptionAlerts', 200>>[number]

/** One currency's subscription totals inside `SubscriptionSummaryResponse.byCurrency`. */
export type SubscriptionCurrencyTotals = Schemas['SubscriptionCurrencyTotals']

/** One currency's share of a single day's upcoming charges. */
export type SubscriptionCurrencyAmount = Schemas['SubscriptionCurrencyAmount']

/** The subscriptions whose next charge falls on one date, grouped server-side. */
export type UpcomingCharge = Schemas['UpcomingCharge']

/**
 * `GET /v1/subscriptions/summary`. `currency` is the CALLING member's own —
 * never a literal, and it may not appear in `byCurrency` at all. There is
 * deliberately no top-level total: every money figure is inside `byCurrency`
 * or `next14Days[].totals`, never blended across currencies.
 */
export type SubscriptionSummaryResponse = Data<Json<'getSubscriptionSummary', 200>>

/* ---------------------------------------------------------------- tax export */

/** Where an export run is. Only `COMPLETE` can be downloaded. */
export type TaxExportStatus = Schemas['TaxExportStatus']

/** What the generated file is: `CSV` or `PDF`. Both carry the member's own amounts and labels and no computed figure. */
export type TaxExportFormat = Schemas['TaxExportFormat']

/**
 * `GET /v1/tax-export/lines`, and the 200 of `setTaxLine`. Resolved PER MEMBER,
 * not per household — `overridden` is true exactly when this row is the
 * caller's own override rather than the install-wide seed.
 */
export type TaxLineMappingResponse = Schemas['TaxLineMappingResponse']

/**
 * `PUT /v1/tax-export/lines/{categoryId}` body. `lineCode`/`lineLabel` are free
 * text the member copies off a form, `minLength: 1` — there is no empty-string
 * clear; the seeded convention for "no line" is the literal `lineCode: 'NONE'`.
 */
export type TaxLineMappingRequest = Body<'setTaxLine'>

/**
 * `POST /v1/tax-export/runs` body. `jurisdiction` is deliberately NOT here —
 * it comes from the caller's own `users.country`.
 */
export type CreateTaxExportRunRequest = Body<'createTaxExportRun'>

/**
 * One export run of the caller's — `GET/POST /v1/tax-export/runs`,
 * `GET /v1/tax-export/runs/{id}`. Scoped to the member, not the household.
 */
export type TaxExportRunResponse = Data<Json<'createTaxExportRun', 202>>

/** The query `listTaxExportRuns` declares: `page`, `limit`. */
export type TaxExportRunsQuery = Query<'listTaxExportRuns'>

/* -------------------------------------------------------------- notifications */

/**
 * What a notification is about. A CLOSED, five-value enum — deriving copy from
 * this type (rather than a hand-written union) makes a future sixth value a
 * compile error here instead of a blank inbox row.
 */
export type NotificationTypeCode = Schemas['NotificationTypeCode']

/** One row of the caller's inbox. `dismissed` is always `false` here — a dismissed row is never returned by the list. */
export type NotificationResponse = Data<Json<'listNotifications', 200>>[number]

/** `GET /v1/notifications/unread-count` — what the shell's bell badge polls. */
export type UnreadCountResponse = Data<Json<'getUnreadNotificationCount', 200>>

/** `POST /v1/notifications/read-all` — how many rows the call changed. */
export type MarkedReadResponse = Data<Json<'markAllNotificationsRead', 200>>

/**
 * `GET/PUT /v1/notifications/preferences`. Closed, and deliberately carrying NO
 * push field of any kind (owner decision, 2026-09-03) — no `pushEnabled`, no
 * device token, no Expo relay. `emailEnabled` defaults false: the channel is
 * built but off until an operator configures SMTP.
 */
export type NotificationPreferencesResponse = Data<Json<'getNotificationPreferences', 200>>

/**
 * `PUT /v1/notifications/preferences` body — a PARTIAL update despite the verb:
 * every field optional, an omitted one left unchanged. An explicit `null` for
 * `emailAddress` clears it; that is the one field where `null` and "omitted"
 * differ.
 */
export type NotificationPreferencesUpdateRequest = Body<'updateNotificationPreferences'>

/** The query `listNotifications` declares: `page`, `limit`. */
export type NotificationsQuery = Query<'listNotifications'>

/* -------------------------------------------------------------------- webhooks */

/**
 * `GET/POST /v1/webhooks`. Closed, and the shared signing secret is NEVER in
 * this shape in any form, at creation or otherwise — `hasSecret` (always
 * `true`) is the whole of what this API says about it, the same shape
 * `AiSettingsResponse` uses for the AI API key.
 */
export type WebhookResponse = Schemas['WebhookResponse']

/**
 * `POST /v1/webhooks` body. `secret` is write-only: encrypted at rest, never
 * returned or logged, sixteen characters minimum. `targetUrl` must be an
 * absolute `https` URL — checked by the server's own egress policy, never
 * pre-validated here beyond obvious shape.
 */
export type WebhookCreateRequest = Body<'createWebhook'>

/* ==========================================================================
 * Family E (step 205) — the auth completion, account security, data export,
 * self-deletion and the admin console. Every type below is DERIVED from
 * `./generated/schema`; nothing here is hand-typed.
 * ======================================================================== */

/* ------------------------------------------------------- auth completion */

/**
 * `GET /v1/auth/invites/{token}` — public, and **always 200**: the verdict is
 * in the body, never in the status. An unknown, revoked, used or expired token
 * is the identical `{valid: false, email: null, expiresAt: null}`, so the route
 * cannot be used to tell a real token from a near miss.
 */
export type InvitePreviewResponse = Data<Json<'previewInvite', 200>>

/**
 * `POST /v1/auth/password` body. `currentPassword` is the step-up: a session is
 * what somebody who borrowed an unlocked laptop already has. A wrong one is
 * 403 `step-up-required`, NOT 401 — a 401 would send the client interceptor off
 * to refresh a session that is perfectly valid.
 */
export type PasswordChangeRequest = Body<'changePassword'>

/**
 * `POST /v1/auth/password/reset-request` body. **This operation requires the
 * `ADMIN` role** (`x-trove-required-role: ADMIN`): reset here is not an email,
 * it is an admin minting a one-hour single-use link and handing it over out of
 * band. A public minting route with no delivery channel would be a reset oracle.
 */
export type PasswordResetLinkRequest = Body<'requestPasswordResetLink'>

/** The minted link and its one-hour expiry. The only time it is readable. */
export type PasswordResetLinkResponse = Data<Json<'requestPasswordResetLink', 200>>

/**
 * `POST /v1/auth/password/reset` body — public, the token is the whole
 * authorisation. The password policy is checked BEFORE the token is consumed,
 * so a refused password leaves the link usable.
 */
export type PasswordResetRequest = Body<'redeemPasswordResetLink'>

/** `POST /v1/auth/totp/setup` body — the account's current password. */
export type TotpSetupRequest = Body<'setupTotp'>

/**
 * The enrolment in progress. **The factor is not yet in force** — it is enabled
 * only once `confirmTotp` sees one live code, which is what stops a member
 * scanning a QR badly and locking themselves out of their own finance server.
 */
export type TotpEnrolment = Data<Json<'setupTotp', 200>>

/** `POST /v1/auth/totp/confirm` body — one live code. No password: the enrolment was started behind one. */
export type TotpConfirmRequest = Body<'confirmTotp'>

/**
 * The recovery codes, readable **once**. They are stored hashed, so a client
 * that does not surface them here has lost them — never re-fetched, never
 * persisted, never in a cache.
 */
export type TotpConfirmResponse = Data<Json<'confirmTotp', 200>>

/**
 * `POST /v1/auth/totp/disable` (and its `DELETE /v1/auth/totp` twin) body.
 * Password **and** code: either alone would let through exactly the attacker
 * the second factor exists to stop.
 */
export type TotpDisableRequest = Body<'disableTotpByPost'>

/**
 * One row of the caller's own security history, newest first. `type` is
 * deliberately NOT an enum in the contract — the column is a plain string and
 * later steps add kinds, so a client must tolerate an unknown one.
 */
export type SecurityEventView = Data<Json<'getMySecurityEvents', 200>>[number]

/* ------------------------------------------------------------- step-up */

/**
 * The re-proof `createDataExport` and the two self-deletion twins demand.
 *
 * **Neither field is required by the schema**, because which one applies
 * depends on the account: a member with a local password sends `password` (plus
 * `code` where TOTP is enrolled); a member who signs in only through an identity
 * provider has no password and proves the `code` alone; a member with neither is
 * refused 403 `step-up-required` whatever they send. Sending a field the account
 * does not use is ignored; sending a wrong one is 403.
 *
 * This value is never logged, never cached, never stored and never left in
 * component state after the call.
 */
export type StepUpRequest = Body<'deleteMyAccountByPost'>

/* -------------------------------------------------------- data export */

/** Where a run is. Only a `COMPLETE` run that has not expired can be downloaded. */
export type DataExportStatus = Schemas['DataExportStatus']

/**
 * One full-data-export run of the caller's — `GET/POST /v1/me/export`,
 * `GET /v1/me/export/{id}`. Scoped to the MEMBER, not the household: an archive
 * is a copy of one person's whole financial life, so a household mate's runs are
 * neither listed nor reachable by id.
 */
export type DataExportRunResponse = Data<Json<'createDataExport', 202>>

/** The query `listDataExports` declares: `page`, `limit`. */
export type DataExportsQuery = Query<'listDataExports'>

/* ------------------------------------------------------------- admin */

/**
 * A member of the household as an admin sees them. `active` is false both for a
 * merely deactivated member and for one inside the seven-day deletion grace —
 * which is how an administrator finds them again to call `restoreHouseholdMember`.
 */
export type AdminUserResponse = Data<Json<'listHouseholdMembers', 200>>[number]

/** `GET/PUT /v1/admin/settings` — one flag in this version, read off the household row. */
export type AdminSettingsResponse = Data<Json<'getAdminSettings', 200>>

/** `PUT /v1/admin/settings` body — a partial update; an absent or null field leaves the flag alone. */
export type AdminSettingsRequest = Body<'updateAdminSettings'>

/**
 * One mutating request, as `GET /v1/audit` returns it. `path` is the matched
 * route TEMPLATE, never the raw URI — a table of URIs would be a second,
 * permanent, plaintext copy of every invitation token this install ever issued.
 * No body, no headers, no query string and no token is kept.
 */
export type AuditLogEntry = Data<Json<'listAuditLog', 200>>[number]

/** The query `listAuditLog` declares: `page`, `limit`. */
export type AuditLogQuery = Query<'listAuditLog'>
