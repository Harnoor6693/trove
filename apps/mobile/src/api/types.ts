/**
 * Wire types for `trove-core`.
 *
 * Step 3b: every type for a step-3 (`/v1/auth`, `/v1/me`, `/v1/status`)
 * route is now DERIVED from `openapi/trove-core.yaml` via the generated
 * `./generated/schema.d.ts` (owner decision 2026-09-04, "OpenAPI-first" —
 * the spec is the contract, `openapi-typescript` generates the shapes, this
 * file never hand-guesses them) and re-exported under the same names the
 * rest of the app already imports, so no call site elsewhere had to change.
 *
 * A handful of types intentionally do NOT mirror the generated schema
 * 1:1 — each says why at its definition:
 *
 *  - `ErrorDetail` stays a lenient, mostly-optional shape (only `status` is
 *    required) because `errors.ts` has to parse error bodies that do not
 *    conform to the contract at all — Spring's own `/error` page, a reverse
 *    proxy — not just the ones the spec describes.
 *  - `UserPreferences` omits `currency`/`country`, which the wire schema
 *    (`PreferencesResponse`) does carry: they are read-only duplicates of
 *    the same fields on `MeResponse`, which is where this app's `me` store
 *    slice already sources them from (see `useUserFormat`).
 *  - `ServerMeta` keeps `minMobileVersion` / `maxMobileVersion` / `features`
 *    as client-side-only optional fields. No route in `trove-core.yaml`
 *    sends them today (`ServerStatus` is just `{status, version, time}`);
 *    `compatibility.ts` already treats them as best-effort and degrades
 *    gracefully to a version-range check when they are absent, so keeping
 *    the fields costs nothing and the connect flow does not regress if a
 *    later server starts sending them.
 *
 * **Step 214 closed the last hand-written block in this file.** The receipts
 * types this header used to carve out as "stale, not this family's row" are
 * derived now like everything else, and the AI and coach shapes landed with
 * them. Every value the hand-written `ReceiptStatus` union carried but
 * `UPLOADED` and `FAILED` was wrong — the server's pipeline walks
 * `VALIDATING → PARSING → EXTRACTING → MATCHING → TAX_SPLIT → SCORING →
 * COMPLETE`, and "needs review" is a **boolean field** on the row, never a
 * status. Nothing in this file hand-writes a server DTO any more.
 *
 * Two rules the generated shapes carry through unchanged (§5.4 of the
 * earlier collapse plan, still true of the spec):
 *
 *  1. **camelCase everywhere.**
 *  2. **Dates are ISO-8601 strings.**
 */

import type { components, operations } from './generated/schema';

/** Shorthand for the generated schema map. Not exported — import the named types below instead. */
type Schemas = components['schemas'];

// ---------------------------------------------------------------------------
// Value types not carried by any step-3 schema
// ---------------------------------------------------------------------------

/** A `BigDecimal` on the wire: a plain decimal string such as `"12.34"`. Used by receipts (step 14). */
export type Decimal = string;

/** The `Money` wrapper type. Not part of any step-3 route; kept for `useUserFormat`/`MoneyDisplay`. */
export interface Money {
  amount: Decimal;
  currency: CurrencyCode;
}

// ---------------------------------------------------------------------------
// Shared value types (derived)
// ---------------------------------------------------------------------------

export type CurrencyCode = Schemas['CurrencyCode'];
export type CountryCode = Schemas['CountryCode'];
export type LocaleCode = Schemas['LocaleCode'];

/**
 * The two currencies this product supports (owner decision: CA + US only),
 * in the order a picker offers them. This file is the one place the app's
 * ESLint config exempts from the no-hardcoded-currency-literal rule
 * (`.eslintrc.cjs`) — a screen that offers an explicit currency override
 * (an envelope or a budget period in a currency other than the member's own)
 * imports this rather than writing `'CAD'`/`'USD'` itself.
 */
export const CURRENCY_CODES: CurrencyCode[] = ['CAD', 'USD'];
export type HouseholdRole = Schemas['UserRole'];

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

export type PaginationMeta = Schemas['PaginationMeta'];

/** One rejected input; a list of `{field, message}` pairs, per `Schemas['FieldError']`. */
export type FieldErrorItem = Schemas['FieldError'];

type WireErrorResponse = Schemas['ErrorResponse'];

/**
 * RFC 7807 problem details, camelCase.
 *
 * Deliberately more lenient than `Schemas['ErrorResponse']` (which requires
 * `type`/`title`/`detail`/`instance`): `errors.ts` also has to parse a bare
 * problem-details body from an entry point that never went through
 * `GlobalExceptionHandler` at all (Spring's own `/error` page, a reverse
 * proxy in front of it), where only `status` is dependable. Each field's
 * *type* still comes from the generated schema, so the 28-slug `type` union
 * and the rest stay accurate.
 */
export interface ErrorDetail {
  /**
   * Widened to `string` rather than `WireErrorResponse['type']`'s 28-slug
   * literal union: `readErrorDetail` also parses bodies that never went
   * through `GlobalExceptionHandler` (a proxy, a future slug this build
   * predates), and `slugOf` does string surgery on it, not an enum switch —
   * the app must not crash on a `type` the spec's snapshot does not list.
   */
  type?: string;
  title?: WireErrorResponse['title'];
  status: WireErrorResponse['status'];
  detail?: WireErrorResponse['detail'];
  instance?: WireErrorResponse['instance'];
  requestId?: WireErrorResponse['requestId'];
  /** Per-field validation messages; present only on a 400 (§7 lesson). */
  fieldErrors?: FieldErrorItem[];
}

/**
 * The error slugs the app branches on today. Any other slug (the full set is
 * the 28 in `Schemas['ErrorResponse']['type']`, see the spec's own table) is
 * rendered with the server's own `detail` text instead.
 */
export const ErrorSlug = {
  credentialsInvalid: 'credentials-invalid',
  accountLocked: 'account-locked',
  stepUpRequired: 'step-up-required',
  passwordPolicy: 'password-policy',
  registrationClosed: 'registration-closed',
  inviteInvalid: 'invite-invalid',
  inviteExpired: 'invite-expired',
  // The AI surface (step 214). Each of these means something a member can act
  // on, and several share a status with something that means the opposite —
  // `ai-disabled` and `conflict` are both 409, `ai-policy-blocked` and a
  // refused CSRF header are both 403 — so the slug is the only thing that can
  // choose the copy.
  aiDisabled: 'ai-disabled',
  aiUnreachable: 'ai-unreachable',
  aiEndpointUnauthorized: 'ai-endpoint-unauthorized',
  aiModelMissing: 'ai-model-missing',
  aiTimeout: 'ai-timeout',
  aiPolicyBlocked: 'ai-policy-blocked',
  outboundUrlRejected: 'outbound-url-rejected',
  /**
   * **In-band only.** No HTTP response of this API carries it: it is the last
   * frame of a streamed completion whose answer outran the length the server
   * stores. What arrived is kept and stays on the screen.
   */
  answerTooLong: 'answer-too-long',
  uploadTooLarge: 'upload-too-large',
  conflict: 'conflict',
  notFound: 'not-found',
  validationError: 'validation-error',
  // Step 215, family E.
  /**
   * `POST /v1/auth/password/reset`. Unknown, expired and already-used reset
   * tokens all answer this one slug on a 400, so the route cannot be used to
   * test guesses — which means the copy for it must not distinguish them
   * either.
   */
  resetInvalid: 'reset-invalid',
  /**
   * The role gate. A MEMBER who reaches an `/v1/admin/*` route gets this, and
   * it shares its 403 status with `csrf-header-missing` and with
   * `step-up-required` — which on an admin route means a SELF target, not a
   * bad password — so the slug is the only thing that can pick the copy.
   */
  forbidden: 'forbidden',
} as const;

export type ErrorSlugValue = (typeof ErrorSlug)[keyof typeof ErrorSlug];

/** The bare slug names the generated `ErrorResponse.type` enum actually carries, e.g. `"not-found"`. */
type GeneratedErrorSlug =
  WireErrorResponse['type'] extends `https://trove.finance/errors/${infer Slug}` ? Slug : never;

/**
 * Compile-time guard: every value in `ErrorSlug` must be a slug the spec's
 * `ErrorResponse.type` enum actually defines. If a slug above is renamed or
 * removed from `openapi/trove-core.yaml`, `npm run typecheck` fails here
 * instead of the mismatch surfacing as a silently-unmatched `error.code` at
 * runtime.
 */
type _ErrorSlugsAreValid = ErrorSlugValue extends GeneratedErrorSlug ? true : never;
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const _assertErrorSlugsAreValid: _ErrorSlugsAreValid = true;

/** The uniform envelope every `/api/v1` route returns, auth included. */
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: ErrorDetail;
  meta?: PaginationMeta;
}

// ---------------------------------------------------------------------------
// Unauthenticated server probe (`GET /v1/status`, `GET /v1/auth/providers`)
// ---------------------------------------------------------------------------

export type ServerStatus = Schemas['ServerStatus']['status'];

/**
 * Body of `GET /api/v1/status`. `status`, `version` and `time` are exactly
 * `Schemas['ServerStatus']`; `minMobileVersion`/`maxMobileVersion`/`features`
 * are NOT part of that schema (no route sends them today) and are kept here
 * only because `compatibility.ts` already treats them as optional,
 * best-effort fields — see the file header.
 */
export interface ServerMeta extends Pick<Schemas['ServerStatus'], 'status' | 'version'> {
  /** ISO-8601 UTC. On the wire per `ServerStatus.time`, but nothing in this app reads it yet. */
  time?: Schemas['ServerStatus']['time'];
  /** Lowest mobile app version this server supports, if advertised. Not in the contract today. */
  minMobileVersion?: string;
  /** Highest mobile app version this server supports, if advertised. Not in the contract today. */
  maxMobileVersion?: string;
  /** Optional feature flags; absent means "assume on and let the route 404/409". Not in the contract today. */
  features?: {
    ai?: boolean;
    simplefin?: boolean;
    ofx?: boolean;
    receipts?: boolean;
  };
}

/** Body of `GET /api/v1/auth/providers`. */
export type AuthProviders = Schemas['ProvidersResponse'];

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

/** The `user` object carried alongside a session. */
export type AuthUser = Schemas['AuthUser'];

/** Bearer-mode token pair. `expiresIn` is seconds (15-minute access token). */
export type TokenPair = Omit<Schemas['BearerSession'], 'user'>;

/**
 * A successful bearer-mode session — `POST /v1/auth/login` (no second
 * factor owed), `/login/totp`, or `/register`. Exactly `Schemas['BearerSession']`;
 * mobile is bearer-mode only (§1.5) and never receives `CookieSession`.
 */
export type AuthSession = Schemas['BearerSession'];

/**
 * `POST /v1/auth/login` returns a 60-second `typ=totp_challenge` JWT instead
 * of a session when the account has TOTP enabled.
 */
export type TotpChallenge = Schemas['TotpChallenge'];

/**
 * The login response is a union; `challengeToken` discriminates it. Mobile
 * only ever sees the bearer branch of `Schemas['LoginResponse']` (the third
 * branch, `CookieSession`, is what a cookie-mode caller — the web client —
 * gets instead), so it is typed here as `AuthSession | TotpChallenge` rather
 * than the full three-way union.
 */
export type LoginResult = AuthSession | TotpChallenge;

export function isTotpChallenge(result: LoginResult): result is TotpChallenge {
  return typeof (result as Partial<TotpChallenge>).challengeToken === 'string';
}

export type LoginRequest = Schemas['LoginRequest'];

export type TotpLoginRequest = Schemas['TotpLoginRequest'];

/** `POST /v1/auth/register`. `currency`/`country`/`timezone` are optional — absent or null takes the server's default. */
export type RegisterRequest = Schemas['RegisterRequest'];

/**
 * `POST /v1/auth/refresh` — rotation with a 30-second reuse grace. The whole
 * body, and its one field, may be omitted; mobile always sends the token.
 */
export type RefreshRequest = Schemas['RefreshRequest'];

/**
 * `POST /v1/auth/logout` — revokes the refresh-token family server-side.
 * The wire shape is `Schemas['RefreshRequest']`; there is no separate
 * `LogoutRequest` schema in the spec, but the name is kept here since that
 * is what this app calls it.
 */
export type LogoutRequest = Schemas['RefreshRequest'];

/** `POST /v1/auth/password` — step-up change; revokes all other sessions. */
export type ChangePasswordRequest = Schemas['PasswordChangeRequest'];

/**
 * The body `POST /v1/auth/password` actually returns: `{user}`, regardless
 * of auth mode — no tokens, no cookies (§ contract change: this route used
 * to be typed `Promise<void>`; it keeps the caller's existing session and
 * hands back the refreshed `user`, e.g. after a `totpEnabled` change made by
 * a step-up elsewhere).
 */
export type ChangePasswordResult = Schemas['CookieSession'];

/** `POST /v1/auth/totp/setup`. */
export type TotpSetup = Schemas['TotpEnrolment'];

/** `POST /v1/auth/totp/confirm` — the 10 recovery codes, shown exactly once. */
export type TotpConfirmed = Schemas['TotpConfirmResponse'];

// ---------------------------------------------------------------------------
// Me / preferences
// ---------------------------------------------------------------------------

/** `GET /v1/auth/me`. */
export type MeResponse = Schemas['MeResponse'];

/** `PUT /v1/me` — the only identity fields a user may edit. */
export type UpdateMeRequest = Schemas['UpdateMeRequest'];

/**
 * `GET`/`PUT /v1/me/preferences`. The wire schema (`PreferencesResponse`)
 * also carries `currency`/`country`, but they are read-only duplicates of
 * the same fields on `MeResponse` (which is where this app already reads
 * them from — see `useUserFormat`), so they are omitted here rather than
 * carried on two response types that must never disagree.
 */
export type UserPreferences = Omit<Schemas['PreferencesResponse'], 'currency' | 'country'>;

/**
 * `PUT /v1/me/preferences` — a partial patch. Derived from the request
 * schema, not from `Partial<UserPreferences>`: the two are not the same
 * shape (`currency`/`country` are not accepted here at all, and `region`'s
 * null-clears/absent-leaves-alone rule is a request-only concern).
 */
export type UserPreferencesPatch = Schemas['PreferencesUpdateRequest'];

/** `GET /v1/me/security-events`. */
export type SecurityEvent = Schemas['SecurityEventView'];

// ---------------------------------------------------------------------------
// Receipts — `/v1/receipts*` (step 214, family D)
//
// Nine operations, all described by the contract since step 14. Every shape
// below is DERIVED. The block that used to sit here was hand-written against
// a remembered server and disagreed with it on the status enum, on the
// nullability of every extracted field, and on whether the list was paged.
// ---------------------------------------------------------------------------

/**
 * The pipeline's stages. `COMPLETE` and `FAILED` are **terminal** — a poller
 * stops on either. There is no `REVIEW_REQUIRED` status: whether a human
 * should look at the fields is `reviewRequired`, a boolean that sits
 * alongside the status, and `COMPLETE` + `reviewRequired: true` is the
 * ordinary outcome on an install with no vision model rather than a failure.
 */
export type ReceiptStatus = Schemas['ReceiptStatus'];

/** How the image arrived: a phone capture (`SCAN`) or a file (`UPLOAD`). */
export type ReceiptSource = Schemas['ReceiptSource'];

/** A line-item count on the wire — a decimal string, like every other number here. */
export type DecimalQuantity = Schemas['DecimalQuantity'];

/**
 * `POST /v1/receipts/upload` → **202** `{id, status, createdAt}` and nothing
 * else. Deliberately not a whole receipt: the row exists the moment this
 * returns but its fields do not, so poll `getReceiptStatus`.
 */
export type ReceiptUploadResponse = Schemas['ReceiptUploadResponse'];

/** One row of the paged `GET /v1/receipts`. Every extracted field is nullable. */
export type ReceiptSummaryResponse = Schemas['ReceiptSummaryResponse'];

/** `GET /v1/receipts/{id}` — the whole row, with line items and the tax split. */
export type ReceiptResponse = Schemas['ReceiptResponse'];

/** One extracted line of a receipt. `unitPrice`/`totalPrice` are `MoneyAmount`. */
export type ReceiptLineItemResponse = Schemas['ReceiptLineItemResponse'];

/** `GET /v1/receipts/{id}/status` — the four fields a poller needs. */
export type ReceiptStatusResponse = Schemas['ReceiptStatusResponse'];

/**
 * `PATCH /v1/receipts/{id}` — every field optional, an absent field left
 * alone. There is no way to clear a field back to null through it.
 */
export type ReceiptPatchRequest = Schemas['ReceiptPatchRequest'];

/**
 * `PATCH /v1/receipts/{id}/review` — the reviewer's verdict, which is a
 * different thing from a correction: `patchReceipt` writes fields, this
 * records a decision.
 */
export type ReceiptReviewRequest = Schemas['ReceiptReviewRequest'];

/** The query `GET /v1/receipts` declares. Derived, not retyped (see `TransactionQuery`). */
export type ReceiptListQuery = NonNullable<operations['listReceipts']['parameters']['query']>;

// ---------------------------------------------------------------------------
// AI settings — `/v1/ai/*` (step 214, family D)
//
// Per **user**, not per household: the endpoint and its key are configured by
// each member individually and are invisible to everyone else, administrators
// included.
// ---------------------------------------------------------------------------

/**
 * What a member will let leave the install. The coach classifies every
 * payload `SENSITIVE`, so `CLOUD_OK` is refused with 403 `ai-policy-blocked`
 * before a connection is opened — `LOCAL_ONLY` is what the coach needs.
 */
export type PrivacyTier = Schemas['PrivacyTier'];

/**
 * `GET`/`PUT /v1/ai/settings`.
 *
 * **There is no `apiKey` on this type, and that is the contract, not an
 * omission.** The key is stored AES-GCM-encrypted and never leaves the
 * server in any form; `hasApiKey` is the only thing the response says about
 * it. A client that renders a mask has invented one.
 */
export type AiSettingsResponse = Schemas['AiSettingsResponse'];

/**
 * `PUT /v1/ai/settings` — a **replace**, not a patch. `enabled`,
 * `allowPrivateEndpoint` and `privacyTier` must be sent every time, and an
 * omitted nullable field is written as null. `apiKey` is the one exception:
 * write-only, and absent or null leaves the stored key alone.
 */
export type AiSettingsUpdateRequest = Schemas['AiSettingsUpdateRequest'];

/** `POST /v1/ai/settings/test` — takes no body; the settings under test are the saved ones. */
export type AiConnectionTestResponse = Schemas['AiConnectionTestResponse'];

/** One entry of `GET /v1/ai/models`, which works before a model has been chosen. */
export type AiModel = Schemas['AiModel'];

// ---------------------------------------------------------------------------
// Coach — `/v1/chat/*` and `/v1/insights` (step 214, family D)
//
// The prefix is `/v1/chat`, never `/v1/coach`: the pre-collapse route map is
// superseded by the spec wherever the two disagree.
// ---------------------------------------------------------------------------

/** How a **stored** turn names its author: `USER` / `ASSISTANT`, upper case. */
export type ChatTurnRole = Schemas['ChatTurnRole'];

/**
 * `POST /v1/chat/completions` body.
 *
 * `messages` is **the turn you are sending, not the history**. The server
 * takes the last entry, which must be `role: 'user'`, and rebuilds the
 * preceding turns from its own store; earlier entries are ignored. Sending a
 * transcript would let the app put words into the model's mouth and quote
 * them back as the coach's own, which is why this app sends exactly one turn.
 */
export type ChatCompletionRequest = Schemas['ChatCompletionRequest'];

/**
 * One turn on the way **out**. `role` is lower case here (`'user'`), unlike
 * the upper-case `ChatTurnRole` a stored turn comes back with — the two
 * spellings are not interchangeable and the compiler now says so.
 */
export type ChatTurnRequest = Schemas['ChatTurnRequest'];

/** The non-streaming answer. `sessionId` also arrives in the `X-Session-Id` header. */
export type ChatCompletionResponse = Schemas['ChatCompletionResponse'];

/** One stored turn from the paged `GET /v1/chat/history`. */
export type CoachMessageResponse = Schemas['CoachMessageResponse'];

/** One weekly note from the paged `GET /v1/insights`. Reading the page does not deliver it. */
export type InsightResponse = Schemas['InsightResponse'];

/** The query `GET /v1/chat/history` declares. `?sessionId=` (empty) is a 400 — omit it to mean all. */
export type ChatHistoryQuery = NonNullable<operations['listChatHistory']['parameters']['query']>;

/** The query `GET /v1/insights` declares. */
export type InsightsQuery = NonNullable<operations['listInsights']['parameters']['query']>;

// ---------------------------------------------------------------------------
// Accounts — `/v1/accounts*` (step 211, family A)
//
// Every shape below is DERIVED from `./generated/schema`, which
// `npm run generate` writes from `openapi/trove-core.yaml`. Nothing here is
// hand-written: the flat, `*Cents` shapes this app used to model (§4.13) are
// gone, and an amount is a decimal string on its own currency-carrying row.
// ---------------------------------------------------------------------------

/** How an account's rows arrive. `MANUAL` is the only kind manual entry accepts. */
export type SourceKind = Schemas['SourceKind'];

/** The product type shown on an account row. */
export type AccountType = Schemas['AccountType'];

/** The account's own sync state, as opposed to one run's outcome. */
export type AccountSyncStatus = Schemas['AccountSyncStatus'];

/** One sync run's outcome. `PARTIAL_FAILURE` and `FAILED` both carry `errorMessage`. */
export type SyncRunStatus = Schemas['SyncRunStatus'];

/**
 * An amount on the wire: a decimal string at the ledger's scale (`"12.34"`).
 * Never a number, never cents. Identical to `Decimal` above; both names exist
 * because the spec calls it `MoneyAmount` and this app called it `Decimal`.
 */
export type MoneyAmount = Schemas['MoneyAmount'];

/** `GET /v1/accounts`, `GET|PUT /v1/accounts/{id}`, `POST /v1/accounts`. */
export type AccountResponse = Schemas['AccountResponse'];

/** `POST /v1/accounts` body. `currency` omitted takes the caller's own. */
export type AccountRequest = Schemas['AccountRequest'];

/**
 * `PUT /v1/accounts/{id}` body. `sourceKind` and `currency` are absent from
 * the schema on purpose — changing either would reinterpret money already
 * stored against the account.
 */
export type AccountUpdateRequest = Schemas['AccountUpdateRequest'];

/** One row of `GET /v1/accounts/{id}/sync-logs`, and what `POST .../sync` answers. */
export type SyncLogResponse = Schemas['SyncLogResponse'];

/**
 * `POST /v1/accounts/exchange-setup-token` body.
 *
 * `setupToken` is a CREDENTIAL. It is never logged, never written to the
 * secure store, never put in a URL, and it is cleared from component state as
 * soon as the call returns.
 */
export type ExchangeSetupTokenRequest = Schemas['ExchangeSetupTokenRequest'];

/** What the exchange produced: one account id and one sync run per bridge account. */
export type ExchangeSetupTokenResponse = Schemas['ExchangeSetupTokenResponse'];

// ---------------------------------------------------------------------------
// Transactions — `/v1/transactions*`
// ---------------------------------------------------------------------------

/** The direction/kind of a row. `amount` is always the absolute value. */
export type TransactionType = Schemas['TransactionType'];

/** `GET /v1/transactions` item, `GET|PATCH /v1/transactions/{id}`, `POST /v1/transactions`. */
export type TransactionResponse = Schemas['TransactionResponse'];

/** `POST /v1/transactions` body. `currency` is the account's and is not accepted here. */
export type ManualTransactionRequest = Schemas['ManualTransactionRequest'];

/**
 * `PATCH /v1/transactions/{id}` body — **presence is meaningful**.
 *
 * A field that is absent is left alone; a field present with `null` clears it.
 * `{"transferGroupId": null}` unlinks a transfer leg and is a different request
 * from one that never mentions `transferGroupId`. Never spread a whole form
 * into this body.
 */
export type TransactionPatchRequest = Schemas['TransactionPatchRequest'];

/**
 * The query `GET /v1/transactions` declares. `page` is zero-based; `limit`
 * defaults to 50 and is *rejected* rather than clamped above 500.
 *
 * **Derived from the generated operation, not retyped.** `check:generated`
 * only compares `src/api/generated` against the contract, so a hand-written
 * copy of these parameters is the one shape in this file CI could never catch
 * drifting. A widened `sort` allowlist or a new filter now reaches this app as
 * a type error at the call site instead of silently disagreeing with the
 * server.
 */
export type TransactionQuery = NonNullable<operations['listTransactions']['parameters']['query']>;

/** The four values `sort` accepts on `GET /v1/transactions`; anything else is a 400. */
export type TransactionSort = NonNullable<TransactionQuery['sort']>;

/** A paged list operation's answer: one page of `data` plus the `meta` that describes it. */
export interface Page<T> {
  data: T[];
  meta: PaginationMeta;
}

// ---------------------------------------------------------------------------
// Imports — `/v1/imports*`
// ---------------------------------------------------------------------------

/** The two source kinds that come from a file. `SIMPLEFIN` and `MANUAL` have none. */
export type ImportSourceKind = Schemas['ImportSourceKind'];

/** Where a batch is in its life. Only a `PREVIEW` batch can be committed. */
export type ImportBatchStatus = Schemas['ImportBatchStatus'];

/** What committing would do with one row of the file. */
export type ImportRowVerdict = Schemas['ImportRowVerdict'];

/** The column mapping the sniffer detected for a CSV. */
export type CsvColumnMapping = Schemas['CsvColumnMapping'];

/** The member's corrections to that mapping, sent with the commit. */
export type CsvColumnMappingRequest = Schemas['CsvColumnMappingRequest'];

/**
 * One candidate date pattern, offered only when the file's dates are genuinely
 * ambiguous between `dd/MM/yyyy` and `MM/dd/yyyy`. The server never guesses.
 */
export type DateFormatCandidate = Schemas['DateFormatCandidate'];

/** One parsed row of the file, with its verdict and, when `INVALID`, its reason. */
export type ImportPreviewRow = Schemas['ImportPreviewRow'];

/** `POST /v1/imports` (201) and `GET /v1/imports/{id}/preview`. Nothing is written by either. */
export type ImportPreviewResponse = Schemas['ImportPreviewResponse'];

/** `POST /v1/imports/{id}/commit` body. Optional; omitted fields keep their detected values. */
export type ImportCommitRequest = Schemas['ImportCommitRequest'];

// ---------------------------------------------------------------------------
// Categories and merchant rules — `/v1/categories*` (step 212, family B)
//
// Every shape below is DERIVED from `./generated/schema`. The collapse plan
// (§4.5/§4.6) names the pre-collapse categoriser's flat, single-tier mapping
// as the thing being replaced: a category is a tree (`parentId`, `isSystem`),
// and a merchant maps to one through an ORDERED set of household rules plus a
// built-in community tier the household never edits.
// ---------------------------------------------------------------------------

/** `GET /v1/categories` item, `PUT`/`POST /v1/categories(/{id})` response. */
export type CategoryResponse = Schemas['CategoryResponse'];

/**
 * `POST`/`PUT /v1/categories` body. Carries only `name`, `parentId` and
 * `icon` — never `id`, `householdId` or `slug` (the G3 fix,
 * docs/recovery/01-FINDINGS.md): the old service bound the JPA entity itself
 * as the request body, so a client-supplied `id` matching a system category
 * performed a merge over it.
 */
export type CategoryRequest = Schemas['CategoryRequest'];

/** `EXACT` requires an exact match on the normalised merchant string; `PREFIX` a leading match. */
export type MatchType = Schemas['MatchType'];

/** How a merchant mapping came to exist. Only `RULE` rows are reachable through this API. */
export type MappingSource = Schemas['MappingSource'];

/** A decimal string in `[0.000, 1.000]` — never a JSON number, like every other `BigDecimal` here. */
export type Confidence = Schemas['Confidence'];

/** `GET /v1/categories/rules` item, `PUT`/`POST` response. Precedence order: `priority` ascending. */
export type CategoryRuleResponse = Schemas['CategoryRuleResponse'];

/**
 * `POST`/`PUT /v1/categories/rules(/{id})` body. `merchant` is normalised
 * server-side; `confidence` is optional and defaults to `"0.900"` when absent.
 */
export type CategoryRuleRequest = Schemas['CategoryRuleRequest'];

/**
 * `POST /v1/categories/rules/reorder` body. Sets each rule's `priority` to
 * its index in `orderedIds` (0 first). Every id must already belong to one of
 * the caller's household's own `RULE`-sourced rows, or the whole request is
 * refused before anything is written — there is no partial reorder.
 */
export type ReorderRulesRequest = Schemas['ReorderRulesRequest'];

/** `POST /v1/categories/rules/dry-run` body. Writes nothing. */
export type DryRunRequest = Schemas['DryRunRequest'];

/**
 * The dry-run result — a direct projection of the categorisation pipeline's
 * output. `source` is null when the match came from the built-in
 * community-registry tier or the `Uncategorised` fallback, neither of which
 * is a persisted household row.
 */
export type CategorisationResultResponse = Schemas['CategorisationResultResponse'];

// ---------------------------------------------------------------------------
// Budgets, envelopes and goals — `/v1/budgets*`, `/v1/goals*` (step 212, family B)
//
// The collapse plan (§4.13) names this app's flat, `Budget{name, category,
// limitCents, spentCents, period}` shape as modelling nothing any server DTO
// carries. Every shape below is DERIVED; no `*Cents` integer reappears
// anywhere, and every amount is a `MoneyAmount` decimal string.
// ---------------------------------------------------------------------------

/** `FLEXIBLE` (default) | `ZERO_BASED` (allocations must sum to income) | `ROLLOVER`. */
export type BudgetType = Schemas['BudgetType'];

/**
 * `POST /v1/budgets` body. `currency` explicit-`null`-or-omitted means the
 * CREATING member's own `users.currency`; `budgetType` explicit-`null`-or-omitted
 * means `FLEXIBLE`.
 */
export type BudgetPeriodRequest = Schemas['BudgetPeriodRequest'];

/**
 * `PUT /v1/budgets/{id}` body — a full replace of the two mutable fields,
 * both required. `month` and `currency` are not writable.
 */
export type BudgetPeriodUpdateRequest = Schemas['BudgetPeriodUpdateRequest'];

/** `GET /v1/budgets`, `GET/PUT /v1/budgets/{id}`, `POST /v1/budgets`, `GET /v1/budgets/current`. */
export type BudgetPeriodResponse = Schemas['BudgetPeriodResponse'];

/**
 * `POST /v1/budgets/{id}/envelopes` body. `spent` and `rolloverAmount` are
 * deliberately absent — both are server-computed, never client-writable.
 * `currency` omitted or null means the PERIOD's own currency.
 */
export type EnvelopeRequest = Schemas['EnvelopeRequest'];

/**
 * `PUT /v1/budgets/{id}/envelopes/{envelopeId}` body. `allocated` is the only
 * writable field and it is required — `categoryId` and `currency` are fixed
 * at creation, and `spent`/`rolloverAmount` are never client-writable.
 */
export type EnvelopeUpdateRequest = Schemas['EnvelopeUpdateRequest'];

/**
 * Computed on every read from the envelope's amounts and where today falls in
 * the period's month, in the CALLER's own timezone. Never stored. THE SPENT
 * FIGURE AND THIS HEALTH ARE THE SERVER'S OWN — never recomputed, never a
 * percentage invented client-side.
 */
export type EnvelopeHealth = Schemas['EnvelopeHealth'];

/**
 * One budgeted category inside a period, with its live health. `spent` is
 * rewritten — never incremented — from the household's ledger in this
 * category, month and currency, so recategorising a transaction or
 * re-running a sync cannot double-count it.
 */
export type EnvelopeResponse = Schemas['EnvelopeResponse'];

/**
 * `POST /v1/goals` body. `currentAmount` and `achieved` are not accepted — a
 * goal starts at zero and unmet whatever the caller sends.
 */
export type GoalRequest = Schemas['GoalRequest'];

/**
 * `PUT /v1/goals/{id}` body — a full replace of the writable fields.
 * `achieved` is derived server-side from `currentAmount >= targetAmount` and
 * is never sent; `currency` is not writable.
 */
export type GoalUpdateRequest = Schemas['GoalUpdateRequest'];

/** `GET /v1/goals` item, `PUT`/`POST` response. */
export type GoalResponse = Schemas['GoalResponse'];

// ---------------------------------------------------------------------------
// Dashboard — `GET /v1/dashboard/today`
// ---------------------------------------------------------------------------

/**
 * One currency's subtotals. Currencies are **never** converted into one
 * another — there is no exchange rate anywhere in this product — so a client
 * renders each entry separately and never adds two of them together.
 */
export type DashboardCurrencyTotals = Schemas['DashboardCurrencyTotals'];

/**
 * `GET /v1/dashboard/today`.
 *
 * `date` and `timezone` say which day, in which zone, the figures describe:
 * the server computed the boundaries from the member's own `users.timezone`,
 * so a client must never re-derive a day boundary from the device clock.
 */
export type DashboardTodayResponse = Schemas['DashboardTodayResponse'];

// ---------------------------------------------------------------------------
// Net worth — `/v1/networth/*` (step 213, family C)
//
// The plan (§4.13) names this app's `/api/net-worth/history` call as
// retargeted to `/v1/networth/series`; every shape below is DERIVED. The
// series is PER CURRENCY (`NetWorthSeriesResponse.series[]`, each a
// `NetWorthSeries`) — there is no exchange rate anywhere in this product, so a
// household holding both CAD and USD positions never has them added together.
// ---------------------------------------------------------------------------

/** `GET /v1/networth/series` range selector. `ALL` has no lower bound. */
export type NetWorthRange = Schemas['NetWorthRange'];

/** What kind of asset a row is, for display and reporting. */
export type AssetType = Schemas['AssetType'];

/** What kind of liability a row is, for display and reporting. */
export type LiabilityType = Schemas['LiabilityType'];

/** `GET /v1/networth/assets` item, `POST`/`PUT` response. Household-shared, not paged. */
export type AssetResponse = Schemas['AssetResponse'];

/**
 * `POST /v1/networth/assets` body. `currency` explicit-`null`-or-omitted means
 * the CREATING member's own `users.currency`.
 */
export type AssetRequest = Schemas['AssetRequest'];

/**
 * `PUT /v1/networth/assets/{id}` body — a full replace of the writable
 * fields. `currency` is not writable here: it is the denomination `value` was
 * already recorded in.
 */
export type AssetUpdateRequest = Schemas['AssetUpdateRequest'];

/** `GET /v1/networth/liabilities` item, `POST`/`PUT` response. Household-shared, not paged. */
export type LiabilityResponse = Schemas['LiabilityResponse'];

/**
 * `POST /v1/networth/liabilities` body. `interestRate` explicit-`null`-or-omitted
 * means zero (zero-interest debt); `currency` explicit-`null`-or-omitted means
 * the CREATING member's own `users.currency`.
 */
export type LiabilityRequest = Schemas['LiabilityRequest'];

/**
 * `PUT /v1/networth/liabilities/{id}` body — a full replace. `currency` is not
 * writable here, for the same reason as an asset's.
 */
export type LiabilityUpdateRequest = Schemas['LiabilityUpdateRequest'];

/**
 * One `net_worth_snapshot` row — one currency, one date. `POST
 * /v1/networth/snapshot` answers an array of these, one per currency present.
 */
export type NetWorthSnapshotResponse = Schemas['NetWorthSnapshotResponse'];

/** One dated point on a single-currency net-worth series. */
export type NetWorthPoint = Schemas['NetWorthPoint'];

/** One currency's net-worth history over the requested range. */
export type NetWorthSeries = Schemas['NetWorthSeries'];

/**
 * `GET /v1/networth/series`. `primaryCurrency` is the caller's own
 * `users.currency` — never the currency to render every line in, only the one
 * to label as "yours" when more than one series comes back.
 */
export type NetWorthSeriesResponse = Schemas['NetWorthSeriesResponse'];

// ---------------------------------------------------------------------------
// Subscriptions — `/v1/subscriptions*` (step 213, family C)
//
// The plan (§4.13) names this app's flat `Subscription{amountCents,
// billingCycle: 'monthly'|'annual'}` as the shape being replaced: no `*Cents`
// integer and the real five-value `BillingCycle` enum, not two lowercase
// strings. Every shape below is DERIVED.
// ---------------------------------------------------------------------------

/** The cadence a recurring charge was classified into. */
export type BillingCycle = Schemas['BillingCycle'];

/** How a subscription looked at the last scan: `GREEN`/`AMBER`/`RED`. */
export type SubscriptionSignal = Schemas['SubscriptionSignal'];

/** What a `subscription_alert` row is about. Only `PRICE_INCREASE` is written today. */
export type SubscriptionAlertType = Schemas['SubscriptionAlertType'];

/**
 * `GET /v1/subscriptions` item, `GET /v1/subscriptions/{id}`, and what
 * `scanSubscriptions`/`cancelSubscription` answer. Written only by detection —
 * the only field a member can change is `cancelled`, through the DELETE.
 */
export type SubscriptionResponse = Schemas['SubscriptionResponse'];

/** `GET /v1/subscriptions/alerts` item, `markSubscriptionAlertRead` response. */
export type SubscriptionAlertResponse = Schemas['SubscriptionAlertResponse'];

/** One currency's subscription totals inside the summary. Never blended across currencies. */
export type SubscriptionCurrencyTotals = Schemas['SubscriptionCurrencyTotals'];

/** One currency's share of a single day's upcoming charges. */
export type SubscriptionCurrencyAmount = Schemas['SubscriptionCurrencyAmount'];

/** The subscriptions due on one date, with their per-currency totals. */
export type UpcomingCharge = Schemas['UpcomingCharge'];

/**
 * `GET /v1/subscriptions/summary`. Deliberately no top-level total — every
 * figure is inside `byCurrency` or `next14Days[].totals`.
 */
export type SubscriptionSummaryResponse = Schemas['SubscriptionSummaryResponse'];

// ---------------------------------------------------------------------------
// Tax export — `/v1/tax-export/*` (step 213, family C)
//
// AN EXPORT, NOT AN ENGINE (owner decision — the tax engine was cut). Every
// shape below carries the member's own amounts and labels; nothing here is a
// computed tax figure, a rate or a bracket.
// ---------------------------------------------------------------------------

/** Where an export run is. Only `COMPLETE` can be downloaded. */
export type TaxExportStatus = Schemas['TaxExportStatus'];

/** What the generated file is: `CSV` (machine-readable) or `PDF` (for printing). */
export type TaxExportFormat = Schemas['TaxExportFormat'];

/**
 * `GET /v1/tax-export/lines` item, `PUT .../lines/{categoryId}` response. A
 * category with no row at all still appears here with empty `lineCode`/
 * `lineLabel` and `deductible: false`, rather than being dropped.
 */
export type TaxLineMappingResponse = Schemas['TaxLineMappingResponse'];

/**
 * `PUT /v1/tax-export/lines/{categoryId}` body. `lineCode`/`lineLabel` are
 * free text the member copies off a form; the server never interprets them.
 */
export type TaxLineMappingRequest = Schemas['TaxLineMappingRequest'];

/**
 * `POST /v1/tax-export/runs` body. `jurisdiction` is deliberately NOT here —
 * it comes from the caller's own `users.country`.
 */
export type CreateTaxExportRunRequest = Schemas['CreateTaxExportRunRequest'];

/**
 * `GET /v1/tax-export/runs` item, `POST` (202) response, `GET .../runs/{id}`.
 * Scoped to the member, not the household — a tax filing is individual.
 */
export type TaxExportRunResponse = Schemas['TaxExportRunResponse'];

/** The query `GET /v1/tax-export/runs` declares. Derived, not retyped. */
export type TaxExportRunsQuery = NonNullable<
  operations['listTaxExportRuns']['parameters']['query']
>;

// ---------------------------------------------------------------------------
// Notifications — `/v1/notifications*` (step 213, family C)
//
// Per member, not per household — one of the three areas of this API where a
// household mate is refused (404, never 403) rather than served. There is no
// push field of any kind anywhere here, and there never will be while the
// owner decision of 2026-09-03 stands: no push notifications until a direct
// APNs/FCM path exists, and no Expo relay. The mobile client's whole push
// story is `GET /v1/notifications/unread-count`, polled on foreground and on
// an interval.
// ---------------------------------------------------------------------------

/**
 * What a notification is about. A closed, five-value enum: the server's own
 * CHECK constraint carries the same five values, so a producer that invented
 * a sixth would fail its INSERT rather than ship a row no client can render.
 * Deriving the copy map for this from the generated type (rather than a
 * hand-written union) is what makes a new value a compile error here too.
 */
export type NotificationTypeCode = Schemas['NotificationTypeCode'];

/** `GET /v1/notifications` item, `PATCH .../{id}/read` response. Closed. */
export type NotificationResponse = Schemas['NotificationResponse'];

/** `GET /v1/notifications/unread-count`. An object so the field has a name. */
export type UnreadCountResponse = Schemas['UnreadCountResponse'];

/** `POST /v1/notifications/read-all`. How many rows that call changed. */
export type MarkedReadResponse = Schemas['MarkedReadResponse'];

/**
 * `GET`/`PUT /v1/notifications/preferences`. Closed, and deliberately
 * carrying no push field of any kind — no `pushEnabled`, no device list.
 */
export type NotificationPreferencesResponse = Schemas['NotificationPreferencesResponse'];

/**
 * `PUT /v1/notifications/preferences` — a partial update despite the verb:
 * every field is optional and an omitted one is left exactly as it was. An
 * explicit `null` for `emailAddress` clears it; that is the only field where
 * `null` and "omitted" differ.
 */
export type NotificationPreferencesUpdateRequest = Schemas['NotificationPreferencesUpdateRequest'];

/** The query `GET /v1/notifications` declares. Derived, not retyped. */
export type NotificationsQuery = NonNullable<
  operations['listNotifications']['parameters']['query']
>;

// ---------------------------------------------------------------------------
// Webhooks — `/v1/webhooks*` (step 213, family C)
//
// Per member, not per household: a subscription points at a URL that member
// controls and is signed with a secret only that member holds.
// ---------------------------------------------------------------------------

/**
 * `GET /v1/webhooks` item, `POST` response. **The secret is never returned,
 * in any form — not at creation, not masked, not truncated.** `hasSecret` is
 * the whole of what this API says about it; there is no show-once value to
 * hold anywhere in this app.
 */
export type WebhookResponse = Schemas['WebhookResponse'];

/**
 * `POST /v1/webhooks` body. `secret` is write-only — sixteen characters
 * minimum, encrypted at rest, never echoed back. `targetUrl` must be `https`
 * and is checked against the shared egress policy both now and on every
 * delivery attempt.
 */
export type WebhookCreateRequest = Schemas['WebhookCreateRequest'];

// ---------------------------------------------------------------------------
// Auth completion, data export, self-deletion, administration — step 215,
// family E.
//
// Every type below is derived from `generated/schema.d.ts`. The two nullable
// pairs in here are the ones this app has previously got wrong by guessing:
// `InviteResponse.token`/`url` go null the moment an invitation is accepted,
// revoked or expired, and `DataExportRunResponse.fileSizeBytes`/`expiresAt`
// are null until a run is `COMPLETE` and null again once the sweep has removed
// the archive. Both are `| null` in the generated shape, which is what makes
// "render the literal string null" a compile error rather than a screenshot.
// ---------------------------------------------------------------------------

/**
 * `GET /v1/auth/invites/{token}`. **Always 200** — the verdict is in the body,
 * never in the status, so an unknown, revoked, used or expired token all give
 * `{valid: false, email: null, expiresAt: null}` and the route cannot be used
 * to tell a real token from a near miss.
 */
export type InvitePreviewResponse = Schemas['InvitePreviewResponse'];

/**
 * `POST /v1/auth/password/reset-request` body. **This route is ADMIN-scoped**:
 * password reset on this product is not an email, because a self-hosted
 * install usually has no mail server. An admin mints a one-hour single-use
 * link and hands it over out of band.
 */
export type PasswordResetLinkRequest = Schemas['PasswordResetLinkRequest'];

/**
 * What the admin gets back: `{url, expiresAt}`, and this is the only time the
 * link is readable. `url` is `{publicUrl}/reset-password/{token}`.
 */
export type PasswordResetLinkResponse = Schemas['PasswordResetLinkResponse'];

/**
 * `POST /v1/auth/password/reset` body — public, the token is the whole
 * authorisation. Unknown, expired and already-used tokens share one 400
 * `reset-invalid`; the password policy is checked *before* the token is
 * consumed, so a refused password leaves the link usable.
 */
export type PasswordResetRequest = Schemas['PasswordResetRequest'];

/** `DELETE /v1/auth/totp` and `POST /v1/auth/totp/disable` — one body, two verbs. */
export type TotpDisableRequest = Schemas['TotpDisableRequest'];

/**
 * The step-up body `POST /v1/me/delete` and `POST /v1/me/export` take.
 *
 * **Both fields are optional in the contract and that is load-bearing**: the
 * factor set is resolved from what the account actually has. A member with a
 * local password proves it (plus a code where TOTP is enrolled); a member who
 * signs in only through an identity provider proves the code alone.
 *
 * This value is a credential. It is never logged, never written to the secure
 * store, never left in component state after the call settles, never put in a
 * query cache and never rendered into a snapshot.
 */
export type StepUpRequest = Schemas['StepUpRequest'];

/** Where a data-export run is. Only a `COMPLETE`, unexpired run can be downloaded. */
export type DataExportStatus = Schemas['DataExportStatus'];

/**
 * One full-data-export run of the caller's. Scoped to the member, not the
 * household: a household mate's runs are neither listed nor reachable by id.
 */
export type DataExportRunResponse = Schemas['DataExportRunResponse'];

/** `GET /v1/me/export` query — `page`/`limit`, the standard paged pair. */
export type DataExportsQuery = NonNullable<operations['listDataExports']['parameters']['query']>;

/** A member of the household, as an admin sees them. `active: false` covers both a deactivation and a member inside the deletion grace. */
export type AdminUserResponse = Schemas['AdminUserResponse'];

/**
 * An invitation as an admin sees it. **`token` and `url` are null once the
 * invitation is accepted, revoked or expired** — "still redeemable" is a
 * question about now, so the answer changes with the clock and not only with a
 * write. A client must hide the copy-link control when they are null.
 */
export type InviteResponse = Schemas['InviteResponse'];

/** `POST /v1/admin/invites` body. Optional in every sense — `{}` is the normal case. */
export type CreateInviteRequest = Schemas['CreateInviteRequest'];

/** `GET /v1/admin/settings` — one flag in this version. */
export type AdminSettingsResponse = Schemas['AdminSettingsResponse'];

/** `PUT /v1/admin/settings` — a partial update; an absent or null field leaves the setting alone. */
export type AdminSettingsRequest = Schemas['AdminSettingsRequest'];

/**
 * One mutating request in the household's audit log. `path` is the matched
 * **route template**, never the caller's raw URI, and no body, header, query
 * string or token is kept — so there is nothing in a row that could be markup
 * and nothing a client should render as such.
 */
export type AuditLogEntry = Schemas['AuditLogEntry'];

/** `GET /v1/audit` query — `page`/`limit`. */
export type AuditLogQuery = NonNullable<operations['listAuditLog']['parameters']['query']>;
