/**
 * MSW handlers for `trove-core`.
 *
 * The auth / me / invites handlers below were transcribed by hand from the plan
 * before the contract existed, and are still waiting on the step that owns those
 * routes to retype them from the schema.
 *
 * **Everything from "family A" downwards is contract-derived** (step 201): every
 * body is typed as `AccountResponse`, `TransactionResponse`,
 * `ImportPreviewResponse`, `DashboardTodayResponse` … straight out of
 * `@api/types`, which projects `src/api/generated/schema.d.ts`. A mock body that
 * drifts from the spec is now a TypeScript error rather than a green test, which
 * is the replacement the note above asked for — the failure mode the inventory
 * found (`docs/recovery/inventory/web.md` §7) was a suite validating each hook
 * against a mock built to match that hook's own idea of the contract, and a
 * hand-written mock can only ever prove the client is self-consistent.
 *
 * Conventions asserted here, so a drift shows up as a test failure:
 *  - every body except `/v1/status` is `{success, data}` / `{success, error}`;
 *  - every money field is a decimal STRING and carries a currency;
 *  - `/v1/auth/me` is camelCase `userId` (it was snake_case `user_id`);
 *  - `error.fieldErrors` is a LIST of `{field, message}`.
 */
import { http, HttpResponse } from 'msw'
import type {
  AccountRequest,
  AccountResponse,
  AccountUpdateRequest,
  ApiResponse,
  AuthProviders,
  DashboardTodayResponse,
  ErrorResponse,
  ImportCommitRequest,
  ImportPreviewResponse,
  Invite,
  LoginResponse,
  ManualTransactionRequest,
  MeResponse,
  PaginationMeta,
  Preferences,
  PreferencesPatch,
  SyncLogResponse,
  TransactionPatchRequest,
  TransactionResponse,
} from '@api/types'
import type {
  AiConnectionTestResponse,
  AiModel,
  AiSettingsResponse,
  AiSettingsUpdateRequest,
  ChatCompletionRequest,
  ChatCompletionResponse,
  CoachMessageResponse,
  InsightResponse,
  ReceiptPatchRequest,
  ReceiptResponse,
  ReceiptReviewRequest,
  ReceiptStatusResponse,
  ReceiptSummaryResponse,
  ReceiptUploadResponse,
} from '@api/types'
import type {
  BudgetPeriodRequest,
  BudgetPeriodResponse,
  BudgetPeriodUpdateRequest,
  CategorisationResultResponse,
  CategoryRequest,
  CategoryResponse,
  CategoryRuleRequest,
  CategoryRuleResponse,
  DryRunRequest,
  EnvelopeHealth,
  EnvelopeRequest,
  EnvelopeResponse,
  EnvelopeUpdateRequest,
  GoalRequest,
  GoalResponse,
  GoalUpdateRequest,
  ReorderRulesRequest,
} from '@api/types'
import type {
  AdminRoleRequest,
  AdminSettingsRequest,
  CookieSession,
  PasswordResetLinkResponse,
  AdminSettingsResponse,
  AdminUserResponse,
  AuditLogEntry,
  DataExportRunResponse,
  InvitePreviewResponse,
  PasswordChangeRequest,
  PasswordResetLinkRequest,
  PasswordResetRequest,
  SecurityEventView,
  StepUpRequest,
  TotpConfirmRequest,
  TotpConfirmResponse,
  TotpDisableRequest,
  TotpEnrolment,
  TotpLoginRequest,
  TotpSetupRequest,
} from '@api/types'
import type {
  AssetRequest,
  AssetResponse,
  AssetUpdateRequest,
  LiabilityRequest,
  LiabilityResponse,
  LiabilityUpdateRequest,
  NetWorthSeriesResponse,
  NetWorthSnapshotResponse,
  NotificationPreferencesResponse,
  NotificationPreferencesUpdateRequest,
  NotificationResponse,
  SubscriptionAlertResponse,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
  TaxExportRunResponse,
  TaxLineMappingRequest,
  TaxLineMappingResponse,
  WebhookCreateRequest,
  WebhookResponse,
} from '@api/types'

/* ------------------------------------------------------------- test state */

export interface MswState {
  /** Whether `GET /v1/auth/me` answers 200 or 401. */
  authenticated: boolean
  /** Whether the account has TOTP enabled, so login returns a challenge. */
  totpEnabled: boolean
  registrationOpen: boolean
  oidcEnabled: boolean
  me: MeResponse
  preferences: Preferences
  /** Counts every `POST /v1/auth/refresh`, for single-flight assertions. */
  refreshCalls: number
  /** Counts every `GET /v1/auth/me`. */
  meCalls: number
  refreshSucceeds: boolean

  /* ------------------------------------------------------ family A (step 201) */

  accounts: AccountResponse[]
  transactions: TransactionResponse[]
  /** Sync runs per account id, newest first, as `listAccountSyncLogs` answers. */
  syncLogs: Record<string, SyncLogResponse[]>
  /** Import batches by id, as `getImportPreview` answers. */
  imports: Record<string, ImportPreviewResponse>
  dashboard: DashboardTodayResponse

  /** The query string `GET /v1/transactions` was last called with. */
  lastTransactionsQuery: Record<string, string> | null
  /** The exact body `PATCH /v1/transactions/{id}` was last called with — the
   *  presence-meaningful assertion depends on seeing it unmodified. */
  lastTransactionPatch: TransactionPatchRequest | null
  /** The exact body `POST /v1/imports/{id}/commit` was last called with. */
  lastCommitBody: ImportCommitRequest | null
  /** The parts `POST /v1/imports` was last called with. */
  lastImportUpload: { filename: string; accountId: string | null; sourceKind: string | null } | null
  /** Counts every `POST /v1/accounts/{id}/sync`. */
  syncCalls: number

  /** When set, the next `GET /v1/transactions` answers 429 with a `Retry-After`. */
  transactionsRateLimited: boolean
  /** When set, `POST /v1/accounts/{id}/sync` answers 409 `conflict`. */
  syncConflicts: boolean
  /** When set, `POST /v1/imports` answers 413 `upload-too-large`. */
  uploadTooLarge: boolean

  /* ------------------------------------------------- family D (step 204) */

  /** `GET /v1/ai/settings`. The response schema cannot carry an API key. */
  aiSettings: AiSettingsResponse
  /** `GET /v1/ai/models` — the picker's options. */
  aiModels: AiModel[]
  /** The exact body `PUT /v1/ai/settings` was last called with, stored verbatim. */
  lastAiSettingsBody: AiSettingsUpdateRequest | null
  /** When set, `GET /v1/ai/models` answers this typed failure instead of a list. */
  aiModelsFailure: SlugFailure | null
  /** When set, `POST /v1/ai/settings/test` answers this typed failure. */
  aiTestFailure: SlugFailure | null
  /** When set, `PUT /v1/ai/settings` answers this typed failure. */
  aiSettingsSaveFailure: SlugFailure | null

  /** `GET /v1/chat/suggestions` — the server's own prompt starters. */
  chatSuggestions: string[]
  /** `GET /v1/chat/history`, every conversation, most recently used first. */
  chatMessages: CoachMessageResponse[]
  /** `GET /v1/insights`. */
  insights: InsightResponse[]
  /** The exact body `POST /v1/chat/completions` was last called with. */
  lastChatBody: ChatCompletionRequest | null
  /** The `X-Session-Id` request header that body arrived with, or null. */
  lastChatSessionHeader: string | null
  /** The `X-Session-Id` every chat response sets. */
  chatSessionId: string
  /** Every `DELETE /v1/chat/history`, as the `sessionId` param it carried (null = absent). */
  chatHistoryDeletes: (string | null)[]
  /**
   * The exact chunks the streamed body writes, in order. Written as an array so
   * a test can pin a frame SPLIT ACROSS TWO CHUNKS, an in-band error frame with
   * no `[DONE]` after it, and a stream that ends with neither.
   */
  chatStreamChunks: string[]
  /** When set, the completion route answers this typed failure BEFORE any stream opens. */
  chatFailure: SlugFailure | null
  /** When set, the completion route answers JSON even though `stream=true` was asked. */
  chatAnswersJsonDespiteStream: boolean
  /**
   * When set, the streamed body writes its chunks and then STAYS OPEN. The only
   * way out is the client aborting — which is how "unmount aborts the request"
   * and the Stop control are tested against a real in-flight stream rather than
   * against one that had already finished.
   */
  chatStreamHangs: boolean
  /**
   * When set, the completion route does not answer AT ALL until the request is
   * aborted — no status, no headers, no body.
   *
   * This is the window `chatStreamHangs` cannot reach: there, a chunk has
   * already been written, so an abort lands inside the SSE reader's own read
   * loop, which classifies it. Here the abort rejects the `fetch` itself before
   * there is any response to read, and it is the CALLER that has to tell "the
   * member pressed Stop" apart from "the server is unreachable". A member losing
   * patience with a local model that has not answered yet — `readTimeoutMs` goes
   * up to ten minutes — is exactly this case.
   */
  chatResponseHangs: boolean

  /** The household's receipts, full rows; the list projects summaries from these. */
  receipts: ReceiptResponse[]
  /** The exact body `PATCH /v1/receipts/{id}` was last called with. */
  lastReceiptPatch: ReceiptPatchRequest | null
  /** The exact body `PATCH /v1/receipts/{id}/review` was last called with. */
  lastReceiptReview: ReceiptReviewRequest | null
  /** The parts `POST /v1/receipts/upload` was last called with. */
  lastReceiptUpload: { filename: string; type: string; source: string | null } | null
  /** When set, `POST /v1/receipts/upload` answers 413 `upload-too-large`. */
  receiptUploadTooLarge: boolean
  /** Counts every `GET /v1/receipts/{id}/thumbnail` — the object-URL assertions need it. */
  thumbnailFetches: number
  /** When set, the thumbnail route answers 404. */
  thumbnailMissing: boolean
  /** Statuses `GET /v1/receipts/{id}/status` walks through, one per call, last repeats. */
  receiptStatusWalk: ReceiptStatusResponse['status'][]
  /** Counts every `GET /v1/receipts/{id}/status` — the polling assertions need it. */
  receiptStatusPolls: number

  /* ------------------------------------------------- family B (step 202) */

  /** `GET /v1/categories` — every system category plus this household's own. */
  categories: CategoryResponse[]
  /** When set, `DELETE /v1/categories/{id}` answers 409 `conflict` — the category is still in use. */
  categoryDeleteConflict: boolean

  /** `GET /v1/categories/rules` — this household's own `RULE`-sourced rows, in priority order. */
  categoryRules: CategoryRuleResponse[]
  /** When set, `POST`/`PUT /v1/categories/rules` answers 409 `conflict` (merchant already has a rule). */
  categoryRuleConflict: boolean
  /** When set, `GET /v1/categories/rules` answers 429 with a `Retry-After` header. */
  categoryRulesRateLimited: boolean
  /** The exact body `POST /v1/categories/rules/reorder` was last called with. */
  lastReorderBody: ReorderRulesRequest | null
  /** The exact body `POST /v1/categories/rules/dry-run` was last called with. */
  lastDryRunBody: DryRunRequest | null
  /** What `POST /v1/categories/rules/dry-run` answers next. */
  dryRunResult: CategorisationResultResponse

  /** Every budget period the household has, `month` descending. `GET /v1/budgets`. */
  budgetPeriods: BudgetPeriodResponse[]
  /** The id of the period that covers the caller's current month, or null for the 404 empty state. */
  currentBudgetPeriodId: string | null
  /** When set, `POST /v1/budgets` answers 409 `conflict` (a period already exists for that month). */
  budgetPeriodConflict: boolean
  /**
   * When set, `GET /v1/budgets` and `GET /v1/budgets/current` both answer 429
   * with a `Retry-After` header — a genuine fetch failure, distinct from the
   * null-currentBudgetPeriodId 404 empty state, so a test can prove the
   * "no budget this month" card never renders alongside a real error.
   */
  budgetsRateLimited: boolean
  /** When set, a period/envelope write that would break the ZERO_BASED invariant answers 400 naming this field. */
  zeroBasedViolationField: 'totalIncome' | 'allocated' | null

  /** Envelopes per period id, as `listEnvelopes` answers. */
  envelopesByPeriod: Record<string, EnvelopeResponse[]>
  /** When set, `POST /v1/budgets/{id}/envelopes` answers 409 `conflict` (duplicate category). */
  envelopeDuplicateConflict: boolean
  /** When set, `GET /v1/budgets/{id}/envelopes` answers 429 with a `Retry-After` header. */
  envelopesRateLimited: boolean

  /** `GET /v1/goals` — the household's savings goals, newest first. */
  goals: GoalResponse[]

  /* ------------------------------------------------- family C (step 203) */

  /** `GET /v1/networth/assets`. Not paged. */
  assets: AssetResponse[]
  /** `GET /v1/networth/liabilities`. Not paged. */
  liabilities: LiabilityResponse[]
  /** `GET /v1/networth/series` — grouped by currency; never one blended series. */
  netWorthSeries: NetWorthSeriesResponse
  /** The `range` query `getNetWorthSeries` was last called with. */
  lastNetWorthRange: string | null

  /** `GET /v1/subscriptions` — active (non-cancelled) only. */
  subscriptions: SubscriptionResponse[]
  /** `GET /v1/subscriptions/alerts` — unread only. */
  subscriptionAlerts: SubscriptionAlertResponse[]
  /** Counts every `POST /v1/subscriptions/scan`. */
  scanCalls: number

  /** `GET /v1/tax-export/lines`. One row per visible category. */
  taxLines: TaxLineMappingResponse[]
  /** The exact body `PUT /v1/tax-export/lines/{categoryId}` was last called with. */
  lastTaxLineBody: TaxLineMappingRequest | null
  /** `GET/POST /v1/tax-export/runs`, the caller's own. */
  taxExportRuns: TaxExportRunResponse[]
  /**
   * How many more `GET /v1/tax-export/runs` calls before every `PENDING`/
   * `GENERATING` row flips to `COMPLETE` — the polling-to-completion
   * assertion needs a run that is not ready on the first fetch.
   */
  taxExportPollsRemaining: number

  /** `GET /v1/notifications` — the caller's own inbox, undismissed only. */
  notifications: NotificationResponse[]
  /** `GET/PUT /v1/notifications/preferences`. */
  notificationPreferences: NotificationPreferencesResponse
  /** The exact body `PUT /v1/notifications/preferences` was last called with. */
  lastNotificationPreferencesBody: NotificationPreferencesUpdateRequest | null

  /** `GET /v1/webhooks` — the caller's own subscriptions. */
  webhooks: WebhookResponse[]
  /** The exact body `POST /v1/webhooks` was last called with — never a secret assertion off the RESPONSE, only off what was sent. */
  lastWebhookCreateBody: WebhookCreateRequest | null
  /** When set, `POST /v1/webhooks` answers 400 `outbound-url-rejected`. */
  webhookUrlRejected: boolean
  /** When set, `POST /v1/webhooks` answers 409 `conflict` (subscription cap reached). */
  webhookCapReached: boolean

  /* ------------------------------------------------- family E (step 205) */

  /** The password every step-up and password-gated route accepts. Anything else is 403 `step-up-required`. */
  stepUpPassword: string
  /** The TOTP code those routes accept, when the account has a second factor. */
  stepUpCode: string
  /**
   * WHICH KEYS the last step-up body carried — never the values. A fixture that
   * recorded the password would be the very leak the screens are built to avoid,
   * and the assertions only ever need to know that `password`/`code` were sent.
   */
  lastStepUpKeys: string[]
  /** The exact body `POST /v1/auth/login/totp` was last called with — the `{code}`-alone assertion reads this. */
  lastTotpLoginBody: TotpLoginRequest | null
  /** Which verb the self-deletion actually arrived on. The POST twin is the one the client must use. */
  lastAccountDeleteVerb: 'POST' | 'DELETE' | null
  /** When set, the self-deletion and the admin member delete both answer 409 `conflict` — the household's last ADMIN. */
  lastAdminConflict: boolean

  /** `GET /v1/me/security-events` — bounded, newest first. */
  securityEvents: SecurityEventView[]
  /** What `POST /v1/auth/totp/setup` answers. */
  totpEnrolment: TotpEnrolment
  /** What `POST /v1/auth/totp/confirm` answers — readable exactly once, so the count matters. */
  totpRecoveryCodes: string[]
  /** Counts every `POST /v1/auth/totp/confirm`; the "shown once, never re-fetched" assertion reads it. */
  totpConfirmCalls: number
  /** When set, `POST /v1/auth/totp/setup` answers 409 `totp-already-enabled`. */
  totpAlreadyEnabled: boolean
  /** The exact body `POST /v1/auth/password` was last called with — asserted absent from any cache, never logged. */
  lastPasswordChangeSent: boolean

  /** `GET/POST /v1/me/export` — the caller's own runs, newest first. */
  dataExports: DataExportRunResponse[]
  /** How many more `GET /v1/me/export` fetches leave a non-terminal run alone before it flips to COMPLETE. */
  dataExportPollsRemaining: number
  /** When set, `POST /v1/me/export` answers 409 `conflict` with this detail — the one-run-per-day cap. */
  dataExportDailyCap: string | null
  /** When set, `GET /v1/me/export/{id}/download` answers 409 `ExportNotReady` whatever the row says. */
  dataExportDownloadNotReady: boolean

  /** `GET /v1/admin/users` — the household's members, oldest first. */
  members: AdminUserResponse[]
  /** When set, `POST /v1/admin/users/{id}/restore` answers 409 `conflict` — never deleted, or the grace has passed. */
  restoreOutsideGrace: boolean
  /** `GET/PUT /v1/admin/settings`. */
  adminSettings: AdminSettingsResponse
  /** The exact body `PUT /v1/admin/settings` was last called with. */
  lastAdminSettingsBody: AdminSettingsRequest | null
  /** The exact body `PUT /v1/admin/users/{id}/role` was last called with. */
  lastMemberRoleBody: AdminRoleRequest | null
  /** `GET /v1/audit` — every row; the handler pages over this. */
  auditEntries: AuditLogEntry[]

  /** What `POST /v1/auth/password/reset-request` answers, and the address it was asked for. */
  lastResetLinkRequest: PasswordResetLinkRequest | null
  /** The single reset token `POST /v1/auth/password/reset` accepts. Anything else is 400 `reset-invalid`. */
  validResetToken: string
  /** The exact body `POST /v1/auth/password/reset` was last called with. */
  lastResetBody: PasswordResetRequest | null
  /** What `GET /v1/auth/invites/{token}` answers — always 200, the verdict is in the body. */
  invitePreview: InvitePreviewResponse
}

/** A typed failure a test can arm on a route: the status and the slug it answers. */
export interface SlugFailure {
  status: number
  slug: string
  title: string
  detail?: string
}

export const DEFAULT_ME: MeResponse = {
  userId: '00000000-0000-0000-0000-000000000001',
  email: 'test@yopmail.com',
  displayName: 'Test User',
  householdId: '00000000-0000-0000-0000-0000000000ff',
  role: 'ADMIN',
  totpEnabled: false,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Toronto',
  locale: 'en-CA',
  onboardingCompleted: true,
}

export const DEFAULT_PREFERENCES: Preferences = {
  region: null,
  locale: 'en-CA',
  timezone: 'America/Toronto',
  onboardingCompleted: true,
  currency: 'CAD',
  country: 'CA',
}

/* ------------------------------------------------------------- fixtures */

/* ------------------------------------------------- family E (step 205) */

/** Two events, newest first, one of them an unknown kind — a client must tolerate it. */
function makeSecurityEvents(): SecurityEventView[] {
  const now = Date.now()
  return [
    {
      id: 'sec-1',
      type: 'LOGIN_OK',
      at: new Date(now - 60_000).toISOString(),
      ip: '203.0.113.7',
      userAgent: 'Mozilla/5.0 (test)',
    },
    {
      id: 'sec-2',
      // Deliberately not one of the kinds listed in the schema's description:
      // the column is a plain string and later steps add kinds.
      type: 'SOMETHING_NEW',
      at: new Date(now - 3_600_000).toISOString(),
      ip: null,
      userAgent: null,
    },
  ]
}

const DEFAULT_TOTP_ENROLMENT: TotpEnrolment = {
  secret: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  otpauthUri:
    'otpauth://totp/Trove:test@yopmail.com?secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAA&issuer=Trove&digits=6&period=30',
  digits: 6,
  periodSeconds: 30,
}

const DEFAULT_RECOVERY_CODES = ['example-code-0001', 'example-code-0002', 'example-code-0003']

function makeDataExports(): DataExportRunResponse[] {
  return []
}

/** The admin themself plus one ordinary member and one sitting inside the deletion grace. */
function makeMembers(): AdminUserResponse[] {
  return [
    {
      id: DEFAULT_ME.userId,
      email: DEFAULT_ME.email,
      displayName: DEFAULT_ME.displayName,
      role: 'ADMIN',
      active: true,
      totpEnabled: false,
      createdAt: '2026-08-30T09:15:00Z',
    },
    {
      id: 'member-2',
      email: 'second@example.test',
      displayName: 'Second Member',
      role: 'MEMBER',
      active: true,
      totpEnabled: true,
      createdAt: '2026-08-31T09:15:00Z',
    },
    {
      id: 'member-3',
      email: 'departed@example.test',
      displayName: 'Departed Member',
      role: 'MEMBER',
      active: false,
      totpEnabled: false,
      createdAt: '2026-09-01T09:15:00Z',
    },
  ]
}

/**
 * Audit rows. One of them carries markup in its `path` on purpose: the audit
 * table must render it as TEXT, never as HTML.
 */
function makeAuditEntries(): AuditLogEntry[] {
  return [
    {
      id: 'audit-1',
      method: 'DELETE',
      path: '/api/v1/admin/users/{id}',
      status: 200,
      userId: DEFAULT_ME.userId,
      clientIp: '203.0.113.7',
      requestId: '99999999-8888-4777-8666-555555555555',
      durationMs: 42,
      at: '2026-09-04T18:30:00Z',
    },
    {
      id: 'audit-2',
      method: 'POST',
      path: '/api/v1/<img src=x onerror="alert(1)">/*',
      status: 404,
      userId: null,
      clientIp: null,
      requestId: '99999999-8888-4777-8666-555555555556',
      durationMs: 3,
      at: '2026-09-04T18:29:00Z',
    },
  ]
}


/**
 * Dates are relative to "now", never literals.
 *
 * The old fixture pinned both mock transactions to `2026-05-14`. Once real time
 * moved past that, `useDailySpendingTrend`'s rolling window dropped them both and
 * its test degraded into an always-zero assertion that still passed
 * (`docs/recovery/inventory/web.md` §6). A date-shaped fixture with a literal in
 * it is a test that stops testing on a date nobody wrote down.
 */
function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

const DEFAULT_NOTIFICATION_PREFERENCES: NotificationPreferencesResponse = {
  inAppEnabled: true,
  emailEnabled: false,
  emailAddress: null,
  // What every shipped configuration answers: SMTP is off, so there is no email channel.
  emailAvailable: false,
  weeklyDigestEnabled: true,
  subscriptionAlertEnabled: true,
  syncFailureAlertEnabled: true,
  budgetAlertThresholdPercent: 90,
  updatedAt: `${daysAgo(30)}T18:00:00Z`,
}

export const FIXTURE_DATES = {
  today: daysAgo(0),
  yesterday: daysAgo(1),
  twoDaysAgo: daysAgo(2),
}

const CHAT_SESSION = '00000000-0000-4000-8000-0000000000c1'

/** The default list `listChatSuggestions` answers. The screen never hardcodes these. */
export const DEFAULT_SUGGESTIONS = [
  'What did I spend the most on this month?',
  'Am I on track with my savings goals?',
]

/**
 * The default streamed body, as the operation's description pins it — including
 * a frame deliberately SPLIT ACROSS TWO CHUNKS, because that is the classic bug
 * and the default fixture should exercise it rather than a happy alignment.
 */
export const DEFAULT_STREAM_CHUNKS = [
  'data: {"choices":[{"delta":{"content":"You spent "}}]}\n\ndata: {"choices":[{"delta":{"content":"the mo',
  'st on Groceries."}}]}\n\ndata: [DONE]\n\n',
]

/** The whole answer those chunks spell, for an assertion that does not re-derive it. */
export const DEFAULT_STREAM_ANSWER = 'You spent the most on Groceries.'

/** The one household every fixture below belongs to — the scope of every row. */
const HOUSEHOLD = '00000000-0000-0000-0000-0000000000ff'
/** The one member every family-C fixture is attributed to, per the audit-only `createdBy` field. */
const MEMBER = '00000000-0000-0000-0000-000000000002'

const DEFAULT_DRY_RUN_RESULT: CategorisationResultResponse = {
  categoryId: 'cat-groceries',
  categoryName: 'Groceries',
  confidence: '0.990',
  source: null,
}

export const mswState: MswState = {
  authenticated: true,
  totpEnabled: false,
  registrationOpen: false,
  oidcEnabled: false,
  me: { ...DEFAULT_ME },
  preferences: { ...DEFAULT_PREFERENCES },
  refreshCalls: 0,
  meCalls: 0,
  refreshSucceeds: true,
  accounts: makeAccounts(),
  transactions: makeTransactions(),
  syncLogs: makeSyncLogs(),
  imports: {},
  dashboard: makeDashboard(),
  lastTransactionsQuery: null,
  lastTransactionPatch: null,
  lastCommitBody: null,
  lastImportUpload: null,
  syncCalls: 0,
  transactionsRateLimited: false,
  syncConflicts: false,
  uploadTooLarge: false,
  aiSettings: makeAiSettings(),
  aiModels: makeAiModels(),
  lastAiSettingsBody: null,
  aiModelsFailure: null,
  aiTestFailure: null,
  aiSettingsSaveFailure: null,
  chatSuggestions: [...DEFAULT_SUGGESTIONS],
  chatMessages: makeChatMessages(),
  insights: makeInsights(),
  lastChatBody: null,
  lastChatSessionHeader: null,
  chatSessionId: CHAT_SESSION,
  chatHistoryDeletes: [],
  chatStreamChunks: [...DEFAULT_STREAM_CHUNKS],
  chatFailure: null,
  chatAnswersJsonDespiteStream: false,
  chatStreamHangs: false,
  chatResponseHangs: false,
  receipts: makeReceipts(),
  lastReceiptPatch: null,
  lastReceiptReview: null,
  lastReceiptUpload: null,
  receiptUploadTooLarge: false,
  thumbnailFetches: 0,
  thumbnailMissing: false,
  receiptStatusWalk: [],
  receiptStatusPolls: 0,
  categories: makeCategories(),
  categoryDeleteConflict: false,
  categoryRules: makeCategoryRules(),
  categoryRuleConflict: false,
  categoryRulesRateLimited: false,
  lastReorderBody: null,
  lastDryRunBody: null,
  dryRunResult: DEFAULT_DRY_RUN_RESULT,
  budgetPeriods: makeBudgetPeriods(),
  currentBudgetPeriodId: 'budget-1',
  budgetPeriodConflict: false,
  budgetsRateLimited: false,
  zeroBasedViolationField: null,
  envelopesByPeriod: makeEnvelopesByPeriod(),
  envelopeDuplicateConflict: false,
  envelopesRateLimited: false,
  goals: makeGoals(),
  assets: makeAssets(),
  liabilities: makeLiabilities(),
  netWorthSeries: makeNetWorthSeries(),
  lastNetWorthRange: null,
  subscriptions: makeSubscriptions(),
  subscriptionAlerts: makeSubscriptionAlerts(),
  scanCalls: 0,
  taxLines: makeTaxLines(),
  lastTaxLineBody: null,
  taxExportRuns: makeTaxExportRuns(),
  taxExportPollsRemaining: 1,
  notifications: makeNotifications(),
  notificationPreferences: { ...DEFAULT_NOTIFICATION_PREFERENCES },
  lastNotificationPreferencesBody: null,
  webhooks: makeWebhooks(),
  lastWebhookCreateBody: null,
  webhookUrlRejected: false,
  webhookCapReached: false,
    stepUpPassword: 'correct-horse-battery',
  stepUpCode: '123456',
  lastStepUpKeys: [],
  lastTotpLoginBody: null,
  lastAccountDeleteVerb: null,
  lastAdminConflict: false,
  securityEvents: makeSecurityEvents(),
  totpEnrolment: { ...DEFAULT_TOTP_ENROLMENT },
  totpRecoveryCodes: [...DEFAULT_RECOVERY_CODES],
  totpConfirmCalls: 0,
  totpAlreadyEnabled: false,
  lastPasswordChangeSent: false,
  dataExports: makeDataExports(),
  dataExportPollsRemaining: 1,
  dataExportDailyCap: null,
  dataExportDownloadNotReady: false,
  members: makeMembers(),
  restoreOutsideGrace: false,
  adminSettings: { registrationOpen: false, scheduledSyncEnabled: false },
  lastAdminSettingsBody: null,
  lastMemberRoleBody: null,
  auditEntries: makeAuditEntries(),
  lastResetLinkRequest: null,
  validResetToken: 'good-reset-token',
  lastResetBody: null,
  invitePreview: { valid: true, email: 'invitee@example.test', expiresAt: '2026-09-07T14:00:00Z' },
}

export function resetMswState(overrides: Partial<MswState> = {}): void {
  Object.assign(mswState, {
    authenticated: true,
    totpEnabled: false,
    registrationOpen: false,
    oidcEnabled: false,
    me: { ...DEFAULT_ME },
    preferences: { ...DEFAULT_PREFERENCES },
    refreshCalls: 0,
    meCalls: 0,
    refreshSucceeds: true,
    accounts: makeAccounts(),
    transactions: makeTransactions(),
    syncLogs: makeSyncLogs(),
    imports: {},
    dashboard: makeDashboard(),
    lastTransactionsQuery: null,
    lastTransactionPatch: null,
    lastCommitBody: null,
    lastImportUpload: null,
    syncCalls: 0,
    transactionsRateLimited: false,
    syncConflicts: false,
    uploadTooLarge: false,
    aiSettings: makeAiSettings(),
    aiModels: makeAiModels(),
    lastAiSettingsBody: null,
    aiModelsFailure: null,
    aiTestFailure: null,
    aiSettingsSaveFailure: null,
    chatSuggestions: [...DEFAULT_SUGGESTIONS],
    chatMessages: makeChatMessages(),
    insights: makeInsights(),
    lastChatBody: null,
    lastChatSessionHeader: null,
    chatSessionId: CHAT_SESSION,
    chatHistoryDeletes: [],
    chatStreamChunks: [...DEFAULT_STREAM_CHUNKS],
    chatFailure: null,
    chatAnswersJsonDespiteStream: false,
    chatStreamHangs: false,
    chatResponseHangs: false,
    receipts: makeReceipts(),
    lastReceiptPatch: null,
    lastReceiptReview: null,
    lastReceiptUpload: null,
    receiptUploadTooLarge: false,
    thumbnailFetches: 0,
    thumbnailMissing: false,
    receiptStatusWalk: [],
    receiptStatusPolls: 0,
    categories: makeCategories(),
    categoryDeleteConflict: false,
    categoryRules: makeCategoryRules(),
    categoryRuleConflict: false,
    categoryRulesRateLimited: false,
    lastReorderBody: null,
    lastDryRunBody: null,
    dryRunResult: DEFAULT_DRY_RUN_RESULT,
    budgetPeriods: makeBudgetPeriods(),
    currentBudgetPeriodId: 'budget-1',
    budgetPeriodConflict: false,
    budgetsRateLimited: false,
    zeroBasedViolationField: null,
    envelopesByPeriod: makeEnvelopesByPeriod(),
    envelopeDuplicateConflict: false,
    envelopesRateLimited: false,
    goals: makeGoals(),
    assets: makeAssets(),
    liabilities: makeLiabilities(),
    netWorthSeries: makeNetWorthSeries(),
    lastNetWorthRange: null,
    subscriptions: makeSubscriptions(),
    subscriptionAlerts: makeSubscriptionAlerts(),
    scanCalls: 0,
    taxLines: makeTaxLines(),
    lastTaxLineBody: null,
    taxExportRuns: makeTaxExportRuns(),
    taxExportPollsRemaining: 1,
    notifications: makeNotifications(),
    notificationPreferences: { ...DEFAULT_NOTIFICATION_PREFERENCES },
    lastNotificationPreferencesBody: null,
    webhooks: makeWebhooks(),
    lastWebhookCreateBody: null,
    webhookUrlRejected: false,
    webhookCapReached: false,
    stepUpPassword: 'correct-horse-battery',
    stepUpCode: '123456',
    lastStepUpKeys: [],
    lastTotpLoginBody: null,
    lastAccountDeleteVerb: null,
    lastAdminConflict: false,
    securityEvents: makeSecurityEvents(),
    totpEnrolment: { ...DEFAULT_TOTP_ENROLMENT },
    totpRecoveryCodes: [...DEFAULT_RECOVERY_CODES],
    totpConfirmCalls: 0,
    totpAlreadyEnabled: false,
    lastPasswordChangeSent: false,
    dataExports: makeDataExports(),
    dataExportPollsRemaining: 1,
    dataExportDailyCap: null,
    dataExportDownloadNotReady: false,
    members: makeMembers(),
    restoreOutsideGrace: false,
    adminSettings: { registrationOpen: false, scheduledSyncEnabled: false },
    lastAdminSettingsBody: null,
    lastMemberRoleBody: null,
    auditEntries: makeAuditEntries(),
    lastResetLinkRequest: null,
    validResetToken: 'good-reset-token',
    lastResetBody: null,
    invitePreview: { valid: true, email: 'invitee@example.test', expiresAt: '2026-09-07T14:00:00Z' },
  } satisfies MswState)
  Object.assign(mswState, overrides)
}


/* --------------------------------------------------------------- helpers */

export function ok<T>(data: T, status = 200) {
  return HttpResponse.json<ApiResponse<T>>({ success: true, data }, { status })
}

export function fail(
  status: number,
  slug: string,
  title: string,
  extra: Partial<ErrorResponse> = {},
  headers: Record<string, string> = {}
) {
  const error: ErrorResponse = {
    type: `https://trove.finance/errors/${slug}`,
    title,
    status,
    detail: title,
    instance: '/api/test',
    requestId: 'test-request-id',
    ...extra,
  }
  return HttpResponse.json<ApiResponse<never>>({ success: false, error }, { status, headers })
}

/**
 * The closed empty-success body: literally `{"success": true}` with NO `data`
 * key. `deleteAccount`, `deleteTransaction` and `undoImport` all answer this, and
 * a client that reads `data` off one is reading a key the server never sends.
 */
export function okEmpty(status = 200) {
  return HttpResponse.json({ success: true } as const, { status })
}

/**
 * Categories, contract-shaped. Two system rows (no `householdId`, `isSystem`
 * true — no delete or rename offered on either) plus a household PARENT and
 * CHILD pair, so the tree-rendering, re-parent and delete-when-in-use
 * assertions all have something real to exercise. `cat-groceries`/
 * `cat-transport` ids are used elsewhere (transaction fixtures, envelopes) —
 * kept stable across families.
 */
function makeCategories(): CategoryResponse[] {
  return [
    {
      id: 'cat-groceries',
      householdId: null,
      slug: 'food.groceries',
      name: 'Groceries',
      icon: null,
      parentId: null,
      isSystem: true,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
    },
    {
      id: 'cat-transport',
      householdId: null,
      slug: 'transport',
      name: 'Transport',
      icon: null,
      parentId: null,
      isSystem: true,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
    },
    {
      id: 'cat-household-parent',
      householdId: HOUSEHOLD,
      slug: 'household.hobbies',
      name: 'Hobbies',
      icon: null,
      parentId: null,
      isSystem: false,
      createdAt: '2026-09-02T00:00:00Z',
      updatedAt: '2026-09-02T00:00:00Z',
    },
    {
      id: 'cat-household-child',
      householdId: HOUSEHOLD,
      slug: 'household.hobbies.golf',
      name: 'Golf',
      icon: null,
      parentId: 'cat-household-parent',
      isSystem: false,
      createdAt: '2026-09-02T00:00:00Z',
      updatedAt: '2026-09-02T00:00:00Z',
    },
  ]
}

/** Two household merchant rules, in priority order (`priority` ascending). */
function makeCategoryRules(): CategoryRuleResponse[] {
  return [
    {
      id: 'rule-1',
      householdId: HOUSEHOLD,
      merchant: 'STARBUCKS',
      matchType: 'EXACT',
      categoryId: 'cat-groceries',
      categoryName: 'Groceries',
      confidence: '0.950',
      source: 'RULE',
      priority: 0,
      createdAt: '2026-09-03T00:00:00Z',
      updatedAt: '2026-09-03T00:00:00Z',
    },
    {
      id: 'rule-2',
      householdId: HOUSEHOLD,
      merchant: 'UBER',
      matchType: 'PREFIX',
      categoryId: 'cat-transport',
      categoryName: 'Transport',
      confidence: '0.900',
      source: 'RULE',
      priority: 1,
      createdAt: '2026-09-03T00:00:00Z',
      updatedAt: '2026-09-03T00:00:00Z',
    },
  ]
}

/** One health block, computed the way the server would for a mid-month read. */
function envelopeHealth(allocated: string, spent: string): EnvelopeHealth {
  const a = Number(allocated)
  const s = Number(spent)
  const pct = a > 0 ? Math.round((s / a) * 100) : 0
  return {
    healthPercentage: pct,
    paceMarkerPercentage: 50,
    overPace: pct > 110,
    remainingAmount: (a - s).toFixed(4),
    expectedSpend: (a * 0.5).toFixed(4),
  }
}

function makeBudgetPeriods(): BudgetPeriodResponse[] {
  return [
    {
      id: 'budget-1',
      householdId: HOUSEHOLD,
      createdBy: DEFAULT_ME.userId,
      month: '2026-09-01',
      totalIncome: '3200.0000',
      currency: 'CAD',
      budgetType: 'FLEXIBLE',
      rolledOverAt: null,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-04T14:00:00Z',
    },
  ]
}

function makeEnvelopesByPeriod(): Record<string, EnvelopeResponse[]> {
  return {
    'budget-1': [
      {
        id: 'env-1',
        householdId: HOUSEHOLD,
        periodId: 'budget-1',
        categoryId: 'cat-groceries',
        categoryName: 'Groceries',
        allocated: '600.0000',
        spent: '210.5500',
        rolloverAmount: '0.0000',
        currency: 'CAD',
        health: envelopeHealth('600.0000', '210.5500'),
        createdAt: '2026-09-01T10:00:00Z',
        updatedAt: '2026-09-04T14:00:00Z',
      },
    ],
  }
}

function makeGoals(): GoalResponse[] {
  return [
    {
      id: 'goal-1',
      householdId: HOUSEHOLD,
      createdBy: DEFAULT_ME.userId,
      name: 'Emergency Fund',
      targetAmount: '10000.0000',
      currentAmount: '2500.0000',
      deadline: '2026-12-31',
      currency: 'CAD',
      achieved: false,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-04T14:00:00Z',
    },
  ]
}


/**
 * One transaction, contract-shaped. Every field `TransactionResponse` declares is
 * present, so a screen that reads `transferGroupId`, `manual`, `pending`,
 * `tags` or `importBatchId` is exercised against the real shape rather than
 * against `undefined`.
 */
function txn(over: Partial<TransactionResponse> & Pick<TransactionResponse, 'id'>): TransactionResponse {
  return {
    householdId: HOUSEHOLD,
    accountId: 'acct-1',
    createdBy: null,
    providerTransactionId: `sf-${over.id}`,
    amount: '10.00',
    currency: 'CAD',
    type: 'DEBIT',
    description: null,
    merchantName: null,
    date: FIXTURE_DATES.twoDaysAgo,
    postedAt: null,
    pending: false,
    manual: false,
    categoryId: null,
    transferGroupId: null,
    importBatchId: null,
    tags: null,
    notes: null,
    createdAt: '2026-09-04T14:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
    ...over,
  }
}

function makeTransactions(): TransactionResponse[] {
  return [
    txn({
      id: 'txn-1',
      date: FIXTURE_DATES.yesterday,
      amount: '42.10',
      type: 'DEBIT',
      description: 'Card purchase',
      merchantName: 'Corner Market',
      categoryId: 'cat-groceries',
    }),
    txn({
      id: 'txn-2',
      date: FIXTURE_DATES.twoDaysAgo,
      amount: '1800.00',
      type: 'CREDIT',
      description: 'Payroll',
      merchantName: 'Employer Inc',
    }),
    // A hand-entered row on the MANUAL account: the only kind DELETE accepts.
    txn({
      id: 'txn-manual',
      accountId: 'acct-manual',
      date: FIXTURE_DATES.twoDaysAgo,
      amount: '9.25',
      type: 'DEBIT',
      merchantName: 'Cash lunch',
      manual: true,
      providerTransactionId: null,
    }),
    // Filler, so a page boundary is reachable and page two is a real assertion.
    ...Array.from({ length: 4 }, (_, i) =>
      txn({
        id: `txn-old-${i + 1}`,
        date: `2026-08-${String(20 - i).padStart(2, '0')}`,
        amount: `${11 + i}.00`,
        merchantName: `Older merchant ${i + 1}`,
      })
    ),
  ]
}

function makeDashboard(): DashboardTodayResponse {
  return {
    date: FIXTURE_DATES.today,
    // Deliberately NOT the runner's zone: the screen has to say whose day this is.
    timezone: 'America/Toronto',
    currency: 'CAD',
    spentToday: '12.50',
    transactionsToday: 1,
    spentThisMonth: '300.00',
    incomeThisMonth: '3200.00',
    netThisMonth: '2900.00',
    // Two currencies, never added together — there is no rate in this product.
    byCurrency: [
      {
        currency: 'CAD',
        spentToday: '12.50',
        transactionsToday: 1,
        spentThisMonth: '300.00',
        incomeThisMonth: '3200.00',
        netThisMonth: '2900.00',
      },
      {
        currency: 'USD',
        spentToday: '4.00',
        transactionsToday: 2,
        spentThisMonth: '88.00',
        incomeThisMonth: '150.00',
        netThisMonth: '62.00',
      },
    ],
  }
}


function acct(
  over: Partial<AccountResponse> & Pick<AccountResponse, 'id' | 'sourceKind' | 'displayName'>
): AccountResponse {
  return {
    householdId: HOUSEHOLD,
    createdBy: null,
    institution: null,
    accountType: 'CHECKING',
    currency: 'CAD',
    syncStatus: 'PENDING',
    lastSyncedAt: null,
    errorMessage: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
    ...over,
  }
}

function makeAccounts(): AccountResponse[] {
  return [
    acct({
      id: 'acct-1',
      sourceKind: 'SIMPLEFIN',
      displayName: 'Everyday Chequing',
      institution: 'Test Bank',
      accountType: 'CHECKING',
      currency: 'CAD',
      syncStatus: 'OK',
      lastSyncedAt: '2026-09-03T11:00:00Z',
    }),
    // The only kind `POST /v1/transactions` accepts.
    acct({
      id: 'acct-manual',
      sourceKind: 'MANUAL',
      displayName: 'Cash wallet',
      accountType: 'OTHER',
      currency: 'CAD',
    }),
    // A second currency and a failed sync, so "never blend currencies" and
    // "never signal state by colour alone" are both testable.
    acct({
      id: 'acct-csv',
      sourceKind: 'CSV',
      displayName: 'US Savings',
      institution: 'Cross-border Bank',
      accountType: 'SAVINGS',
      currency: 'USD',
      syncStatus: 'ERROR',
      errorMessage: 'The last import could not read two rows.',
    }),
  ]
}

function syncLog(
  over: Partial<SyncLogResponse> & Pick<SyncLogResponse, 'id' | 'accountId'>
): SyncLogResponse {
  return {
    householdId: HOUSEHOLD,
    sourceKind: 'SIMPLEFIN',
    status: 'SUCCESS',
    transactionsFetched: 12,
    transactionsNew: 12,
    transactionsUpdated: 0,
    transactionsSkipped: 0,
    transactionsFailed: 0,
    errorMessage: null,
    startedAt: '2026-09-03T11:00:00Z',
    completedAt: '2026-09-03T11:00:03Z',
    ...over,
  }
}

function makeSyncLogs(): Record<string, SyncLogResponse[]> {
  return {
    'acct-1': [
      syncLog({ id: 'sync-2', accountId: 'acct-1' }),
      syncLog({
        id: 'sync-1',
        accountId: 'acct-1',
        status: 'FAILED',
        transactionsFetched: 0,
        transactionsNew: 0,
        errorMessage: 'The bridge did not answer in time.',
        startedAt: '2026-09-02T11:00:00Z',
        completedAt: '2026-09-02T11:00:30Z',
      }),
    ],
    'acct-manual': [],
    'acct-csv': [],
  }
}

/** A CSV batch in `PREVIEW`, with an ambiguous date column the member must resolve. */
export function makeImportPreview(over: Partial<ImportPreviewResponse> = {}): ImportPreviewResponse {
  return {
    id: 'import-1',
    householdId: HOUSEHOLD,
    accountId: 'acct-csv',
    sourceKind: 'CSV',
    status: 'PREVIEW',
    originalFilename: 'statement-2026-09.csv',
    rowCount: 4,
    newCount: 2,
    duplicateCount: 1,
    fuzzyDuplicateCount: 0,
    invalidCount: 1,
    mapping: {
      dateColumn: 'Date',
      descriptionColumn: 'Description',
      amountColumn: 'Amount',
      debitColumn: null,
      creditColumn: null,
      currencyColumn: null,
      balanceColumn: 'Balance',
      dateFormat: null,
      delimiter: ',',
      charset: 'UTF-8',
      hasHeader: true,
    },
    dateFormatCandidates: [
      {
        format: 'dd/MM/yyyy',
        sampleValues: ['03/09/2026', '11/09/2026'],
        interpretedDates: ['2026-09-03', '2026-09-11'],
      },
      {
        format: 'MM/dd/yyyy',
        sampleValues: ['03/09/2026', '11/09/2026'],
        interpretedDates: ['2026-03-09', '2026-11-09'],
      },
    ],
    rows: [
      {
        ordinal: 1,
        date: '2026-09-03',
        description: 'GROCERY STORE',
        merchantName: 'Grocery Store',
        amount: '42.5000',
        currency: 'USD',
        type: 'DEBIT',
        verdict: 'NEW',
        reason: null,
      },
      {
        ordinal: 2,
        date: '2026-09-04',
        description: 'COFFEE',
        merchantName: 'Coffee',
        amount: '4.7500',
        currency: 'USD',
        type: 'DEBIT',
        verdict: 'NEW',
        reason: null,
      },
      {
        ordinal: 3,
        date: '2026-09-05',
        description: 'GROCERY STORE',
        merchantName: 'Grocery Store',
        amount: '42.5000',
        currency: 'USD',
        type: 'DEBIT',
        verdict: 'DUPLICATE',
        reason: null,
      },
      {
        ordinal: 4,
        date: null,
        description: 'BROKEN ROW',
        merchantName: null,
        amount: null,
        currency: 'USD',
        type: null,
        verdict: 'INVALID',
        reason: 'The date cell could not be read.',
      },
    ],
    warnings: ['A running-balance column was recognised and is not imported.'],
    createdAt: '2026-09-05T03:20:00Z',
    ...over,
  }
}

/* ==========================================================================
 * Family D (step 204) fixtures — coach, insights, AI settings, receipts.
 * Every body below is typed from `@api/types`, so a shape that drifts from
 * `openapi/trove-core.yaml` fails `tsc` rather than passing a test.
 * ======================================================================== */

/**
 * `AiSettingsResponse` — note there is no `apiKey` field to set: the schema does
 * not have one, so a test cannot accidentally assert a key is rendered because a
 * fixture supplied one.
 */
export function makeAiSettings(over: Partial<AiSettingsResponse> = {}): AiSettingsResponse {
  return {
    enabled: false,
    endpointUrl: null,
    hasApiKey: false,
    model: null,
    visionModel: null,
    allowPrivateEndpoint: false,
    privacyTier: 'CLOUD_OK',
    connectTimeoutMs: 5000,
    readTimeoutMs: 120000,
    lastTestAt: null,
    lastTestOk: null,
    updatedAt: null,
    ...over,
  }
}

/** A member who has configured an endpoint and can actually use the coach. */
export function makeConfiguredAiSettings(over: Partial<AiSettingsResponse> = {}): AiSettingsResponse {
  return makeAiSettings({
    enabled: true,
    endpointUrl: 'https://ai.example.test/v1',
    hasApiKey: true,
    model: 'qwen2.5:14b',
    visionModel: 'qwen2.5-vl:7b',
    // The coach payload is always SENSITIVE, so LOCAL_ONLY is what it needs.
    privacyTier: 'LOCAL_ONLY',
    updatedAt: '2026-09-04T16:40:55.802Z',
    ...over,
  })
}

function makeAiModels(): AiModel[] {
  return [
    { id: 'qwen2.5:14b', ownedBy: 'library', created: 1735689600 },
    { id: 'qwen2.5-vl:7b', ownedBy: 'library', created: 1735689600 },
  ]
}

function makeChatMessages(): CoachMessageResponse[] {
  return [
    {
      id: '00000000-0000-4000-8000-0000000000d1',
      sessionId: CHAT_SESSION,
      role: 'USER',
      content: 'What did I spend the most on this month?',
      model: null,
      createdAt: '2026-09-05T18:04:09Z',
    },
    {
      id: '00000000-0000-4000-8000-0000000000d2',
      sessionId: CHAT_SESSION,
      role: 'ASSISTANT',
      content: 'Groceries, across fourteen purchases.',
      model: 'qwen2.5:14b',
      createdAt: '2026-09-05T18:04:11Z',
    },
  ]
}

function makeInsights(): InsightResponse[] {
  return [
    {
      id: '00000000-0000-4000-8000-0000000000e1',
      weekStart: '2026-08-31',
      content: 'You spent less than the week before, mostly on dining out.',
      delivered: false,
      createdAt: '2026-08-31T08:30:04Z',
      deliveredAt: null,
    },
  ]
}

/** One receipt in full. Every field `ReceiptResponse` declares is present. */
export function makeReceipt(
  over: Partial<ReceiptResponse> & Pick<ReceiptResponse, 'id'>
): ReceiptResponse {
  return {
    status: 'COMPLETE',
    source: 'UPLOAD',
    uploadSizeBytes: 148320,
    extractedMerchant: 'Blue Bottle Coffee',
    extractedAmount: '12.3400',
    extractedCurrency: 'CAD',
    extractedDate: '2026-06-05',
    gstAmount: '0.5900',
    pstAmount: '0.8600',
    hstAmount: '0.0000',
    confidence: '0.940',
    reviewRequired: false,
    matchedTransactionId: null,
    thumbnailUrl: `/api/v1/receipts/${over.id}/thumbnail`,
    lineItems: [],
    errorMessage: null,
    processingStartedAt: '2026-09-05T14:00:01Z',
    processingCompletedAt: '2026-09-05T14:00:09Z',
    createdAt: '2026-09-05T14:00:00Z',
    updatedAt: '2026-09-05T14:00:09Z',
    ...over,
  }
}

function makeReceipts(): ReceiptResponse[] {
  return [
    makeReceipt({
      id: 'rcpt-1',
      lineItems: [
        {
          id: '00000000-0000-4000-8000-0000000000e9',
          description: 'Oat milk latte',
          quantity: '1.0000',
          unitPrice: '5.7500',
          totalPrice: '5.7500',
          taxable: true,
        },
      ],
    }),
    // The ordinary outcome on an install with no vision model: stored, and the
    // fields are the member's to fill in. NOT an error state.
    makeReceipt({
      id: 'rcpt-review',
      status: 'COMPLETE',
      reviewRequired: true,
      extractedMerchant: null,
      extractedAmount: null,
      extractedCurrency: null,
      extractedDate: null,
      confidence: null,
      gstAmount: null,
      pstAmount: null,
      hstAmount: null,
    }),
    // A second currency, so "never blend currencies" stays testable here too.
    makeReceipt({
      id: 'rcpt-usd',
      extractedMerchant: 'Corner Deli',
      extractedAmount: '8.9900',
      extractedCurrency: 'USD',
      gstAmount: null,
      pstAmount: null,
      hstAmount: null,
    }),
    ...Array.from({ length: 3 }, (_, i) =>
      makeReceipt({
        id: `rcpt-old-${i + 1}`,
        extractedMerchant: `Older merchant ${i + 1}`,
        extractedAmount: `${11 + i}.0000`,
      })
    ),
  ]
}

/** The list projects a summary from the full row — the same fields the server sends. */
function summaryOf(r: ReceiptResponse): ReceiptSummaryResponse {
  return {
    id: r.id,
    status: r.status,
    source: r.source,
    extractedMerchant: r.extractedMerchant,
    extractedAmount: r.extractedAmount,
    extractedCurrency: r.extractedCurrency,
    extractedDate: r.extractedDate,
    confidence: r.confidence,
    reviewRequired: r.reviewRequired,
    createdAt: r.createdAt,
  }
}

/** A one-pixel JPEG, so the image routes answer real bytes with a real type. */
const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xdb, 0x00, 0x43, 0x00, 0xff, 0xd9])

function jpeg(headers: Record<string, string> = {}) {
  return new HttpResponse(JPEG_BYTES, {
    status: 200,
    headers: {
      'Content-Type': 'image/jpeg',
      'X-Content-Type-Options': 'nosniff',
      ...headers,
    },
  })
}

/** A typed failure, as `SlugFailure` describes it. */
function slugFail(f: SlugFailure) {
  return fail(f.status, f.slug, f.title, f.detail ? { detail: f.detail } : {})
}

/* --------------------------------------------------- family C (step 203) */

/** Two currencies on purpose — every "never blend currencies" assertion in this family needs one. */
function makeAssets(): AssetResponse[] {
  return [
    {
      id: 'asset-chequing',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      name: 'Everyday Chequing',
      assetType: 'CASH',
      value: '5230.5000',
      currency: 'CAD',
      accountId: null,
      createdAt: `${daysAgo(30)}T14:00:00Z`,
      updatedAt: `${daysAgo(30)}T14:00:00Z`,
    },
    {
      id: 'asset-brokerage',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      name: 'Brokerage account',
      assetType: 'INVESTMENT',
      value: '12000.0000',
      currency: 'USD',
      accountId: null,
      createdAt: `${daysAgo(30)}T14:00:00Z`,
      updatedAt: `${daysAgo(1)}T14:00:00Z`,
    },
  ]
}

function makeLiabilities(): LiabilityResponse[] {
  return [
    {
      id: 'liability-visa',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      name: 'Visa Credit Card',
      liabilityType: 'CREDIT_CARD',
      balance: '1250.0000',
      interestRate: '19.9900',
      currency: 'CAD',
      accountId: null,
      createdAt: `${daysAgo(30)}T14:00:00Z`,
      updatedAt: `${daysAgo(30)}T14:00:00Z`,
    },
  ]
}

/** One series per currency (F2) — never rendered as one blended line. */
function makeNetWorthSeries(): NetWorthSeriesResponse {
  return {
    primaryCurrency: 'CAD',
    series: [
      {
        currency: 'CAD',
        points: [
          {
            snapshotDate: daysAgo(7),
            totalAssets: '5100.0000',
            totalLiabilities: '1300.0000',
            netWorth: '3800.0000',
          },
          {
            snapshotDate: daysAgo(0),
            totalAssets: '5230.5000',
            totalLiabilities: '1250.0000',
            netWorth: '3980.5000',
          },
        ],
      },
      {
        currency: 'USD',
        points: [
          {
            snapshotDate: daysAgo(0),
            totalAssets: '12000.0000',
            totalLiabilities: '0.0000',
            netWorth: '12000.0000',
          },
        ],
      },
    ],
  }
}

function makeNetWorthSnapshot(): NetWorthSnapshotResponse[] {
  return mswState.netWorthSeries.series.map((s) => {
    const latest = s.points[s.points.length - 1]
    return {
      id: `snap-${s.currency}`,
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      snapshotDate: daysAgo(0),
      totalAssets: latest?.totalAssets ?? '0.0000',
      totalLiabilities: latest?.totalLiabilities ?? '0.0000',
      netWorth: latest?.netWorth ?? '0.0000',
      currency: s.currency,
      createdAt: new Date().toISOString(),
    }
  })
}

/** One `AMBER` (price changed), one `GREEN`, and one already-`cancelled` row still readable by id. */
function makeSubscriptions(): SubscriptionResponse[] {
  return [
    {
      id: 'sub-spotify',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      merchantName: 'SPOTIFY',
      categoryId: null,
      normalizedAmount: '11.9900',
      previousAmount: '9.9900',
      currency: 'CAD',
      billingCycle: 'MONTHLY',
      nextChargeDate: daysAgo(-10),
      signal: 'AMBER',
      priceChanged: true,
      firstSeenDate: daysAgo(180),
      lastSeenDate: daysAgo(20),
      cancelled: false,
      createdAt: `${daysAgo(180)}T02:00:00Z`,
      updatedAt: `${daysAgo(1)}T02:00:00Z`,
    },
    {
      id: 'sub-netflix',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      merchantName: 'NETFLIX',
      categoryId: null,
      normalizedAmount: '15.4900',
      previousAmount: null,
      currency: 'USD',
      billingCycle: 'MONTHLY',
      nextChargeDate: daysAgo(-5),
      signal: 'GREEN',
      priceChanged: false,
      firstSeenDate: daysAgo(200),
      lastSeenDate: daysAgo(25),
      cancelled: false,
      createdAt: `${daysAgo(200)}T02:00:00Z`,
      updatedAt: `${daysAgo(25)}T02:00:00Z`,
    },
    {
      id: 'sub-old-gym',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      merchantName: 'CITY GYM',
      categoryId: null,
      normalizedAmount: '45.0000',
      previousAmount: null,
      currency: 'CAD',
      billingCycle: 'MONTHLY',
      nextChargeDate: daysAgo(90),
      signal: 'RED',
      priceChanged: false,
      firstSeenDate: daysAgo(400),
      lastSeenDate: daysAgo(120),
      cancelled: true,
      createdAt: `${daysAgo(400)}T02:00:00Z`,
      updatedAt: `${daysAgo(120)}T02:00:00Z`,
    },
  ]
}

function makeSubscriptionAlerts(): SubscriptionAlertResponse[] {
  return [
    {
      id: 'sub-alert-spotify',
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      subscriptionId: 'sub-spotify',
      alertType: 'PRICE_INCREASE',
      alertMessage: 'SPOTIFY went up from 9.99 CAD to 11.99 CAD.',
      isRead: false,
      createdAt: `${daysAgo(1)}T02:00:00Z`,
    },
  ]
}

const CYCLE_TO_MONTHLY: Record<SubscriptionResponse['billingCycle'], number> = {
  WEEKLY: 52 / 12,
  BIWEEKLY: 26 / 12,
  MONTHLY: 1,
  QUARTERLY: 1 / 3,
  ANNUAL: 1 / 12,
}

/**
 * Computed from the CURRENT `mswState.subscriptions` on every call, so a scan
 * or a cancel that changes the list is reflected here too — the same
 * relationship the real aggregate query has to the table it reads.
 */
function computeSubscriptionSummary(): SubscriptionSummaryResponse {
  const active = mswState.subscriptions.filter((s) => !s.cancelled)
  const byCurrencyMap = new Map<string, { total: number; active: number; flagged: number }>()
  for (const s of active) {
    const entry = byCurrencyMap.get(s.currency) ?? { total: 0, active: 0, flagged: 0 }
    entry.total += Number(s.normalizedAmount) * CYCLE_TO_MONTHLY[s.billingCycle]
    entry.active += 1
    if (s.signal !== 'GREEN') entry.flagged += 1
    byCurrencyMap.set(s.currency, entry)
  }
  const byCurrency = Array.from(byCurrencyMap.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([currency, v]) => ({
      currency: currency as SubscriptionCurrencyTotalsCurrency,
      monthlyRunRate: v.total.toFixed(4),
      activeCount: v.active,
      flaggedCount: v.flagged,
    }))

  const todayIso = daysAgo(0)
  const in14 = daysAgo(-14)
  const byDate = new Map<string, SubscriptionResponse[]>()
  for (const s of active) {
    if (!s.nextChargeDate) continue
    if (s.nextChargeDate < todayIso || s.nextChargeDate > in14) continue
    const list = byDate.get(s.nextChargeDate) ?? []
    list.push(s)
    byDate.set(s.nextChargeDate, list)
  }
  const next14Days = Array.from(byDate.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, subs]) => {
      const totalsMap = new Map<string, number>()
      for (const s of subs) {
        totalsMap.set(s.currency, (totalsMap.get(s.currency) ?? 0) + Number(s.normalizedAmount))
      }
      return {
        date,
        subscriptions: subs,
        totals: Array.from(totalsMap.entries()).map(([currency, amount]) => ({
          currency: currency as SubscriptionCurrencyTotalsCurrency,
          amount: amount.toFixed(4),
        })),
      }
    })

  return {
    currency: mswState.me.currency,
    activeCount: active.length,
    flaggedCount: active.filter((s) => s.signal !== 'GREEN').length,
    byCurrency,
    next14Days,
  }
}
type SubscriptionCurrencyTotalsCurrency = SubscriptionResponse['currency']

/** Categories, contract-shaped for the tax-lines list: one system, one household, one already overridden. */
function makeTaxLines(): TaxLineMappingResponse[] {
  return [
    {
      categoryId: 'cat-groceries',
      categorySlug: 'food.groceries',
      categoryName: 'Groceries',
      jurisdiction: 'CA',
      lineCode: '',
      lineLabel: '',
      deductible: false,
      overridden: false,
    },
    {
      categoryId: 'cat-transport',
      categorySlug: 'business.software',
      categoryName: 'Software',
      jurisdiction: 'CA',
      lineCode: '8810',
      lineLabel: 'T2125 line 8810 — Office expenses',
      deductible: true,
      overridden: true,
    },
  ]
}

/** One `COMPLETE`, one `GENERATING` (the poll-to-completion assertion needs this one), one `FAILED`. */
function makeTaxExportRuns(): TaxExportRunResponse[] {
  return [
    {
      id: 'run-complete',
      userId: MEMBER,
      taxYear: 2025,
      jurisdiction: 'CA',
      format: 'CSV',
      status: 'COMPLETE',
      fileSizeBytes: 4096,
      errorMessage: null,
      createdAt: `${daysAgo(2)}T14:00:00Z`,
      updatedAt: `${daysAgo(2)}T14:00:02Z`,
    },
    {
      id: 'run-generating',
      userId: MEMBER,
      taxYear: 2024,
      jurisdiction: 'CA',
      format: 'PDF',
      status: 'GENERATING',
      fileSizeBytes: null,
      errorMessage: null,
      createdAt: `${daysAgo(0)}T14:00:00Z`,
      updatedAt: `${daysAgo(0)}T14:00:01Z`,
    },
    {
      id: 'run-failed',
      userId: MEMBER,
      taxYear: 2023,
      jurisdiction: 'CA',
      format: 'CSV',
      status: 'FAILED',
      fileSizeBytes: null,
      errorMessage: 'Export generation could not be queued',
      createdAt: `${daysAgo(10)}T14:00:00Z`,
      updatedAt: `${daysAgo(10)}T14:00:02Z`,
    },
  ]
}

function makeNotifications(): NotificationResponse[] {
  return [
    {
      id: 'notif-budget',
      type: 'BUDGET_THRESHOLD',
      title: 'Groceries is at 92% of its September budget',
      body: 'You have spent CAD 368.00 of the CAD 400.00 budgeted for Groceries this month.',
      actionUrl: '/budgets',
      read: false,
      dismissed: false,
      createdAt: `${daysAgo(0)}T18:00:00Z`,
      readAt: null,
      dismissedAt: null,
    },
    {
      id: 'notif-receipt',
      type: 'RECEIPT_PROCESSED',
      title: 'A receipt needs a look',
      body: 'One of your receipts finished processing and needs a quick review.',
      actionUrl: '/receipts',
      read: true,
      dismissed: false,
      createdAt: `${daysAgo(1)}T18:00:00Z`,
      readAt: `${daysAgo(1)}T19:00:00Z`,
      dismissedAt: null,
    },
    {
      id: 'notif-sync',
      type: 'SYNC_FAILED',
      title: 'An account sync failed',
      body: 'Your connected account could not be synced this time.',
      actionUrl: '/accounts',
      read: false,
      dismissed: false,
      createdAt: `${daysAgo(2)}T18:00:00Z`,
      readAt: null,
      dismissedAt: null,
    },
  ]
}


function makeWebhooks(): WebhookResponse[] {
  return [
    {
      id: 'hook-1',
      targetUrl: 'https://hooks.example.test/trove',
      enabled: true,
      eventTypes: ['budget.threshold_crossed'],
      hasSecret: true,
      createdAt: `${daysAgo(10)}T18:00:00Z`,
      updatedAt: `${daysAgo(10)}T18:00:00Z`,
    },
  ]
}

/** A tiny real CSV/PDF-shaped body, so the download routes answer real bytes with a real type. */
const CSV_BYTES = new TextEncoder().encode('date,merchant,amount,currency\n2025-01-01,Example,10.00,CAD\n')
const PDF_BYTES = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34])

function taxExportFile(run: TaxExportRunResponse, headers: Record<string, string> = {}) {
  const isCsv = run.format === 'CSV'
  return new HttpResponse(isCsv ? CSV_BYTES : PDF_BYTES, {
    status: 200,
    headers: {
      'Content-Type': isCsv ? 'text/csv' : 'application/pdf',
      'X-Content-Type-Options': 'nosniff',
      'Content-Disposition': `attachment; filename="tax-export-${run.taxYear}.${isCsv ? 'csv' : 'pdf'}"`,
      ...headers,
    },
  })
}

/** A few real bytes with a ZIP magic number. See `taxExportFile` for why not a Blob. */
const ZIP_BYTES = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x74, 0x72, 0x6f, 0x76, 0x65])

let invites: Invite[] = []

/** New-row id suffix for family B's fixtures — unique per call, never reset (ids never need to repeat). */
let idSeq = 100


/* ------------------------------------------------ family E step-up helpers */

/**
 * The step-up check `POST /v1/me/export` and the two self-deletion twins share.
 *
 * Records only WHICH KEYS arrived, never their values — a fixture that kept the
 * password would be the very leak those screens are built to avoid. Returns a
 * 403 response when the proof is refused, or `null` when it is accepted.
 */
async function recordStepUp(request: Request) {
  const body = (await request.json()) as StepUpRequest
  mswState.lastStepUpKeys = Object.keys(body ?? {}).sort()
  const passwordOk = body?.password === mswState.stepUpPassword
  const codeOk = !mswState.me.totpEnabled || body?.code === mswState.stepUpCode
  if (passwordOk && codeOk) return null
  return fail(403, 'step-up-required', 'Forbidden', {
    detail: 'That password or code was not accepted.',
  })
}

/**
 * The two admin RECOVERY actions refuse a SELF-target with 403
 * `step-up-required`, so that "an admin route never acts on the admin's own
 * security state" is one rule rather than a list of exceptions.
 */
function adminActionRefusal(id: string) {
  if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
  if (id === mswState.me.userId) {
    return fail(403, 'step-up-required', 'Forbidden', {
      detail:
        'An administrator cannot use this on their own account. Use your own account security settings.',
    })
  }
  if (!mswState.members.some((m) => m.id === id)) return fail(404, 'not-found', 'Not Found')
  return null
}

/* -------------------------------------------------------------- handlers */

export const handlers = [
  /* ---- §4.1 auth ---- */

  http.get('*/v1/auth/me', () => {
    mswState.meCalls += 1
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    return ok(mswState.me)
  }),

  http.post('*/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string }
    if (body.password === 'wrong') return fail(401, 'invalid-credentials', 'Unauthorized')
    if (body.password === 'ratelimited') return fail(429, 'rate-limited', 'Too Many Requests')
    if (mswState.totpEnabled) return ok<LoginResponse>({ challengeToken: 'challenge-token' })
    mswState.authenticated = true
    return ok<LoginResponse>({
      user: {
        id: mswState.me.userId,
        email: body.email,
        displayName: mswState.me.displayName,
        householdId: mswState.me.householdId,
        role: mswState.me.role,
        totpEnabled: mswState.me.totpEnabled,
      },
    })
  }),

  http.post('*/v1/auth/login/totp', async ({ request }) => {
    // Contract-typed. `challengeToken` is OPTIONAL: when it is absent the server
    // reads the `__Host-trove_totp_challenge` cookie, which is what the OIDC
    // callback leaves for a browser that never held a token. A fixture that
    // demanded the token would have hidden exactly the defect step 205 fixes.
    const body = (await request.json()) as TotpLoginRequest
    mswState.lastTotpLoginBody = body
    if (body.code !== '123456') return fail(401, 'invalid-credentials', 'Unauthorized')
    mswState.authenticated = true
    return ok<LoginResponse>({
      user: {
        id: mswState.me.userId,
        email: mswState.me.email,
        displayName: mswState.me.displayName,
        householdId: mswState.me.householdId,
        role: mswState.me.role,
        totpEnabled: true,
      },
    })
  }),

  http.post('*/v1/auth/register', async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string; displayName: string }
    if (body.password.length < 12) {
      return fail(400, 'validation-failed', 'Bad Request', {
        fieldErrors: [{ field: 'password', message: 'Use at least 12 characters.' }],
      })
    }
    mswState.authenticated = true
    mswState.me = { ...mswState.me, email: body.email, displayName: body.displayName }
    return ok<LoginResponse>(
      {
        user: {
          id: mswState.me.userId,
          email: body.email,
          displayName: body.displayName,
          householdId: mswState.me.householdId,
          role: mswState.me.role,
          totpEnabled: false,
        },
      },
      201
    )
  }),

  http.post('*/v1/auth/refresh', () => {
    mswState.refreshCalls += 1
    if (!mswState.refreshSucceeds) return fail(401, 'unauthorized', 'Unauthorized')
    return ok(null)
  }),

  http.post('*/v1/auth/logout', () => {
    mswState.authenticated = false
    return ok(null)
  }),

  http.get('*/v1/auth/providers', () =>
    ok<AuthProviders>({
      local: true,
      oidc: {
        enabled: mswState.oidcEnabled,
        startUrl: mswState.oidcEnabled ? 'https://trove.test/api/v1/auth/start' : null,
        displayName: mswState.oidcEnabled ? 'Authentik' : null,
      },
      registrationOpen: mswState.registrationOpen,
    })
  ),

  /** §4.2 / gap P1-1 — the one route that is NOT enveloped. */
  http.get('*/v1/status', () =>
    HttpResponse.json({ status: 'UP', version: 'test', time: '2026-09-03T12:00:00Z' })
  ),

  /* ---- §4.2 users / preferences / admin ---- */

  http.get('*/v1/me/preferences', () => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    return ok(mswState.preferences)
  }),

  http.put('*/v1/me/preferences', async ({ request }) => {
    // Spec semantics: an explicit null is a no-op on timezone/locale/onboardingCompleted
    // (like absence) but clears `region` — see `PreferencesUpdateRequest`'s description.
    const patch = (await request.json()) as PreferencesPatch
    const next = { ...mswState.preferences }
    if (patch.timezone != null) next.timezone = patch.timezone
    if (patch.locale != null) next.locale = patch.locale
    if (patch.onboardingCompleted != null) next.onboardingCompleted = patch.onboardingCompleted
    if ('region' in patch) next.region = patch.region ?? null
    mswState.preferences = next
    return ok(mswState.preferences)
  }),

  http.put('*/v1/me', async ({ request }) => {
    const patch = (await request.json()) as { displayName?: string; timezone?: string }
    if (patch.displayName !== undefined) mswState.me = { ...mswState.me, displayName: patch.displayName }
    if (patch.timezone !== undefined) {
      mswState.me = { ...mswState.me, timezone: patch.timezone }
      mswState.preferences = { ...mswState.preferences, timezone: patch.timezone }
    }
    return ok(mswState.me)
  }),

  http.get('*/v1/admin/invites', () => ok(invites)),

  http.post('*/v1/admin/invites', async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as { email?: string }
    const invite: Invite = {
      id: `invite-${invites.length + 1}`,
      email: body.email ?? null,
      token: `token-${invites.length + 1}`,
      url: `https://trove.test/invite/token-${invites.length + 1}`,
      expiresAt: '2026-09-06T12:00:00Z',
      createdAt: '2026-09-03T12:00:00Z',
      acceptedAt: null,
      createdByEmail: mswState.me.email,
    }
    invites = [...invites, invite]
    return ok(invite, 201)
  }),

  /**
   * A revoked invite is NOT removed from the listing — the contract says its
   * `token` and `url` "go null in the listing", which is the whole of defect
   * the copy-link button had: it rendered the literal string "null".
   */
  http.delete('*/v1/admin/invites/:id', ({ params }) => {
    if (!invites.some((i) => i.id === params.id)) return fail(404, 'not-found', 'Not Found')
    invites = invites.map((i) => (i.id === params.id ? { ...i, token: null, url: null } : i))
    return okEmpty()
  }),

  /* ==========================================================================
   * Family B (step 202) — contract-derived. Every body below is typed from
   * `@api/types`, so a shape that drifts from `openapi/trove-core.yaml` fails
   * `tsc`, not just a test.
   * ======================================================================== */

  /* ---------------------------------------------------------- categories */

  http.get('*/v1/categories', () => ok(mswState.categories)),

  http.post('*/v1/categories', async ({ request }) => {
    const body = (await request.json()) as CategoryRequest
    if (!body.name || !body.name.trim()) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'name', message: 'A category needs a name.' }],
      })
    }
    if (body.parentId && !mswState.categories.some((c) => c.id === body.parentId)) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'parentId', message: 'That parent category does not exist.' }],
      })
    }
    const category: CategoryResponse = {
      id: `cat-${idSeq++}`,
      householdId: HOUSEHOLD,
      slug: `household.${body.name.toLowerCase().replace(/\s+/g, '-')}`,
      name: body.name,
      icon: body.icon ?? null,
      parentId: body.parentId ?? null,
      isSystem: false,
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    }
    mswState.categories = [...mswState.categories, category]
    return ok(category, 201)
  }),

  http.put('*/v1/categories/:id', async ({ request, params }) => {
    const existing = mswState.categories.find((c) => c.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    if (existing.isSystem) {
      return fail(403, 'forbidden', 'Forbidden', {
        detail: 'A system category cannot be renamed or re-parented.',
      })
    }
    const body = (await request.json()) as CategoryRequest
    if (!body.name || !body.name.trim()) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'name', message: 'A category needs a name.' }],
      })
    }
    if (body.parentId === existing.id) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'parentId', message: 'A category cannot be its own ancestor.' }],
      })
    }
    const updated: CategoryResponse = {
      ...existing,
      name: body.name,
      icon: body.icon ?? null,
      parentId: body.parentId ?? null,
      updatedAt: '2026-09-05T00:00:01Z',
    }
    mswState.categories = mswState.categories.map((c) => (c.id === updated.id ? updated : c))
    return ok(updated)
  }),

  http.delete('*/v1/categories/:id', ({ params }) => {
    const existing = mswState.categories.find((c) => c.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    if (existing.isSystem) {
      return fail(403, 'forbidden', 'Forbidden', {
        detail: 'A system category cannot be deleted.',
      })
    }
    const inUse =
      mswState.categoryDeleteConflict ||
      mswState.categoryRules.some((r) => r.categoryId === existing.id)
    if (inUse) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'That category is still used by one of your merchant rules. Repoint or delete the rule first.',
      })
    }
    mswState.categories = mswState.categories.filter((c) => c.id !== existing.id)
    return okEmpty()
  }),

  /* ------------------------------------------------------- merchant rules */

  http.get('*/v1/categories/rules', () => {
    if (mswState.categoryRulesRateLimited) {
      return fail(429, 'rate-limited', 'Too Many Requests', {}, { 'Retry-After': '10' })
    }
    return ok([...mswState.categoryRules].sort((a, b) => a.priority - b.priority))
  }),

  http.post('*/v1/categories/rules', async ({ request }) => {
    const body = (await request.json()) as CategoryRuleRequest
    if (!body.merchant || !body.merchant.trim()) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'merchant', message: 'A rule needs a merchant pattern.' }],
      })
    }
    if (!mswState.categories.some((c) => c.id === body.categoryId)) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'categoryId', message: 'That category does not exist.' }],
      })
    }
    const normalised = body.merchant.trim().toUpperCase()
    const dup =
      mswState.categoryRuleConflict ||
      mswState.categoryRules.some((r) => r.merchant.toUpperCase() === normalised)
    if (dup) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This household already has a rule for that merchant. Update it instead.',
      })
    }
    const category = mswState.categories.find((c) => c.id === body.categoryId)
    const rule: CategoryRuleResponse = {
      id: `rule-${idSeq++}`,
      householdId: HOUSEHOLD,
      merchant: normalised,
      matchType: body.matchType,
      categoryId: body.categoryId,
      categoryName: category?.name ?? null,
      confidence: body.confidence ?? '0.900',
      source: 'RULE',
      priority: mswState.categoryRules.length,
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    }
    mswState.categoryRules = [...mswState.categoryRules, rule]
    return ok(rule, 201)
  }),

  http.put('*/v1/categories/rules/:id', async ({ request, params }) => {
    const existing = mswState.categoryRules.find((r) => r.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    const body = (await request.json()) as CategoryRuleRequest
    const normalised = body.merchant.trim().toUpperCase()
    const dup =
      mswState.categoryRuleConflict ||
      mswState.categoryRules.some((r) => r.id !== existing.id && r.merchant.toUpperCase() === normalised)
    if (dup) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'Another rule already matches that merchant.',
      })
    }
    const category = mswState.categories.find((c) => c.id === body.categoryId)
    const updated: CategoryRuleResponse = {
      ...existing,
      merchant: normalised,
      matchType: body.matchType,
      categoryId: body.categoryId,
      categoryName: category?.name ?? existing.categoryName,
      confidence: body.confidence ?? existing.confidence,
      updatedAt: '2026-09-05T00:00:01Z',
    }
    mswState.categoryRules = mswState.categoryRules.map((r) => (r.id === updated.id ? updated : r))
    return ok(updated)
  }),

  http.delete('*/v1/categories/rules/:id', ({ params }) => {
    const existing = mswState.categoryRules.find((r) => r.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    mswState.categoryRules = mswState.categoryRules.filter((r) => r.id !== existing.id)
    return okEmpty()
  }),

  http.post('*/v1/categories/rules/reorder', async ({ request }) => {
    const body = (await request.json()) as ReorderRulesRequest
    mswState.lastReorderBody = body
    const missing = body.orderedIds.some(
      (id) => !mswState.categoryRules.some((r) => r.id === id)
    )
    if (missing) return fail(404, 'not-found', 'Not Found')
    const byId = new Map(mswState.categoryRules.map((r) => [r.id, r]))
    mswState.categoryRules = body.orderedIds.map((id, index) => {
      const rule = byId.get(id)
      // `missing` above guarantees every id resolves; the `as` documents that,
      // it does not paper over a real gap.
      return { ...(rule as CategoryRuleResponse), priority: index }
    })
    return okEmpty()
  }),

  http.post('*/v1/categories/rules/dry-run', async ({ request }) => {
    const body = (await request.json()) as DryRunRequest
    mswState.lastDryRunBody = body
    if (!body.merchant || !body.merchant.trim()) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'merchant', message: 'Enter a merchant to test.' }],
      })
    }
    return ok(mswState.dryRunResult)
  }),

  /* -------------------------------------------------------------- budgets */

  http.get('*/v1/budgets', () => {
    if (mswState.budgetsRateLimited) {
      return fail(429, 'rate-limited', 'Too Many Requests', {}, { 'Retry-After': '8' })
    }
    return ok([...mswState.budgetPeriods].sort((a, b) => (a.month < b.month ? 1 : -1)))
  }),

  http.get('*/v1/budgets/current', () => {
    if (mswState.budgetsRateLimited) {
      return fail(429, 'rate-limited', 'Too Many Requests', {}, { 'Retry-After': '8' })
    }
    if (!mswState.currentBudgetPeriodId) return fail(404, 'not-found', 'Not Found')
    const period = mswState.budgetPeriods.find((p) => p.id === mswState.currentBudgetPeriodId)
    if (!period) return fail(404, 'not-found', 'Not Found')
    return ok(period)
  }),

  http.get('*/v1/budgets/:id', ({ params }) => {
    const period = mswState.budgetPeriods.find((p) => p.id === params.id)
    if (!period) return fail(404, 'not-found', 'Not Found')
    return ok(period)
  }),

  http.post('*/v1/budgets', async ({ request }) => {
    const body = (await request.json()) as BudgetPeriodRequest
    const month = `${body.month.slice(0, 7)}-01`
    const conflict =
      mswState.budgetPeriodConflict || mswState.budgetPeriods.some((p) => p.month === month)
    if (conflict) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This household already has a budget period for that month.',
      })
    }
    const period: BudgetPeriodResponse = {
      id: `budget-${idSeq++}`,
      householdId: HOUSEHOLD,
      createdBy: DEFAULT_ME.userId,
      month,
      totalIncome: body.totalIncome,
      currency: body.currency ?? DEFAULT_ME.currency,
      budgetType: body.budgetType ?? 'FLEXIBLE',
      rolledOverAt: null,
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    }
    mswState.budgetPeriods = [...mswState.budgetPeriods, period]
    mswState.envelopesByPeriod = { ...mswState.envelopesByPeriod, [period.id]: [] }
    if (!mswState.currentBudgetPeriodId) mswState.currentBudgetPeriodId = period.id
    return ok(period, 201)
  }),

  http.put('*/v1/budgets/:id', async ({ request, params }) => {
    const existing = mswState.budgetPeriods.find((p) => p.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    const body = (await request.json()) as BudgetPeriodUpdateRequest
    if (mswState.zeroBasedViolationField === 'totalIncome') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [
          { field: 'totalIncome', message: 'Envelope allocations must add up to total income on a zero-based period.' },
        ],
      })
    }
    const updated: BudgetPeriodResponse = {
      ...existing,
      totalIncome: body.totalIncome,
      budgetType: body.budgetType,
      updatedAt: '2026-09-05T00:00:01Z',
    }
    mswState.budgetPeriods = mswState.budgetPeriods.map((p) => (p.id === updated.id ? updated : p))
    return ok(updated)
  }),

  http.delete('*/v1/budgets/:id', ({ params }) => {
    const existing = mswState.budgetPeriods.find((p) => p.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    mswState.budgetPeriods = mswState.budgetPeriods.filter((p) => p.id !== existing.id)
    const rest = { ...mswState.envelopesByPeriod }
    delete rest[existing.id]
    mswState.envelopesByPeriod = rest
    if (mswState.currentBudgetPeriodId === existing.id) mswState.currentBudgetPeriodId = null
    return okEmpty()
  }),

  /* ------------------------------------------------------------ envelopes */

  http.get('*/v1/budgets/:id/envelopes', ({ params }) => {
    if (mswState.envelopesRateLimited) {
      return fail(429, 'rate-limited', 'Too Many Requests', {}, { 'Retry-After': '8' })
    }
    const period = mswState.budgetPeriods.find((p) => p.id === params.id)
    if (!period) return fail(404, 'not-found', 'Not Found')
    return ok(mswState.envelopesByPeriod[String(params.id)] ?? [])
  }),

  http.get('*/v1/budgets/:id/envelopes/:envelopeId', ({ params }) => {
    const period = mswState.budgetPeriods.find((p) => p.id === params.id)
    if (!period) return fail(404, 'not-found', 'Not Found')
    const envelope = (mswState.envelopesByPeriod[String(params.id)] ?? []).find(
      (e) => e.id === params.envelopeId
    )
    if (!envelope) return fail(404, 'not-found', 'Not Found')
    return ok(envelope)
  }),

  http.post('*/v1/budgets/:id/envelopes', async ({ request, params }) => {
    const period = mswState.budgetPeriods.find((p) => p.id === params.id)
    if (!period) return fail(404, 'not-found', 'Not Found')
    const body = (await request.json()) as EnvelopeRequest
    const category = mswState.categories.find((c) => c.id === body.categoryId)
    if (!category) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'categoryId', message: 'That category does not exist.' }],
      })
    }
    const existingForPeriod = mswState.envelopesByPeriod[period.id] ?? []
    const dup =
      mswState.envelopeDuplicateConflict ||
      existingForPeriod.some((e) => e.categoryId === body.categoryId)
    if (dup) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This period already budgets that category.',
      })
    }
    if (mswState.zeroBasedViolationField === 'allocated') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [
          { field: 'allocated', message: 'Envelope allocations must add up to total income on a zero-based period.' },
        ],
      })
    }
    const currency = body.currency ?? period.currency
    const envelope: EnvelopeResponse = {
      id: `env-${idSeq++}`,
      householdId: HOUSEHOLD,
      periodId: period.id,
      categoryId: body.categoryId,
      categoryName: category.name,
      allocated: body.allocated,
      spent: '0.0000',
      rolloverAmount: '0.0000',
      currency,
      health: envelopeHealth(body.allocated, '0.0000'),
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    }
    mswState.envelopesByPeriod = {
      ...mswState.envelopesByPeriod,
      [period.id]: [...existingForPeriod, envelope],
    }
    return ok(envelope, 201)
  }),

  http.put('*/v1/budgets/:id/envelopes/:envelopeId', async ({ request, params }) => {
    const periodId = String(params.id)
    const existing = (mswState.envelopesByPeriod[periodId] ?? []).find(
      (e) => e.id === params.envelopeId
    )
    if (!existing) return fail(404, 'not-found', 'Not Found')
    const body = (await request.json()) as EnvelopeUpdateRequest
    if (mswState.zeroBasedViolationField === 'allocated') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [
          { field: 'allocated', message: 'Envelope allocations must add up to total income on a zero-based period.' },
        ],
      })
    }
    const updated: EnvelopeResponse = {
      ...existing,
      allocated: body.allocated,
      health: envelopeHealth(body.allocated, existing.spent),
      updatedAt: '2026-09-05T00:00:01Z',
    }
    mswState.envelopesByPeriod = {
      ...mswState.envelopesByPeriod,
      [periodId]: (mswState.envelopesByPeriod[periodId] ?? []).map((e) =>
        e.id === updated.id ? updated : e
      ),
    }
    return ok(updated)
  }),

  http.delete('*/v1/budgets/:id/envelopes/:envelopeId', ({ params }) => {
    const periodId = String(params.id)
    const existing = (mswState.envelopesByPeriod[periodId] ?? []).find(
      (e) => e.id === params.envelopeId
    )
    if (!existing) return fail(404, 'not-found', 'Not Found')
    mswState.envelopesByPeriod = {
      ...mswState.envelopesByPeriod,
      [periodId]: (mswState.envelopesByPeriod[periodId] ?? []).filter((e) => e.id !== existing.id),
    }
    return okEmpty()
  }),

  /* ----------------------------------------------------------------- goals */

  http.get('*/v1/goals', () => ok(mswState.goals)),

  http.post('*/v1/goals', async ({ request }) => {
    const body = (await request.json()) as GoalRequest
    if (!body.name || !body.name.trim()) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'name', message: 'A goal needs a name.' }],
      })
    }
    const goal: GoalResponse = {
      id: `goal-${idSeq++}`,
      householdId: HOUSEHOLD,
      createdBy: DEFAULT_ME.userId,
      name: body.name,
      targetAmount: body.targetAmount,
      currentAmount: '0.0000',
      deadline: body.deadline ?? null,
      currency: body.currency ?? DEFAULT_ME.currency,
      achieved: false,
      createdAt: '2026-09-05T00:00:00Z',
      updatedAt: '2026-09-05T00:00:00Z',
    }
    mswState.goals = [...mswState.goals, goal]
    return ok(goal, 201)
  }),

  http.put('*/v1/goals/:id', async ({ request, params }) => {
    const existing = mswState.goals.find((g) => g.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    const body = (await request.json()) as GoalUpdateRequest
    const updated: GoalResponse = {
      ...existing,
      name: body.name,
      targetAmount: body.targetAmount,
      currentAmount: body.currentAmount,
      deadline: body.deadline ?? null,
      achieved: Number(body.currentAmount) >= Number(body.targetAmount),
      updatedAt: '2026-09-05T00:00:01Z',
    }
    mswState.goals = mswState.goals.map((g) => (g.id === updated.id ? updated : g))
    return ok(updated)
  }),

  http.delete('*/v1/goals/:id', ({ params }) => {
    const existing = mswState.goals.find((g) => g.id === params.id)
    if (!existing) return fail(404, 'not-found', 'Not Found')
    mswState.goals = mswState.goals.filter((g) => g.id !== existing.id)
    return okEmpty()
  }),

  /* ==========================================================================
   * Family A (step 201) — contract-derived. Every body below is typed from
   * `@api/types`, so a shape that drifts from `openapi/trove-core.yaml` fails
   * `tsc`, not just a test.
   * ======================================================================== */

  /* ---------------------------------------------------------- dashboard */

  http.get('*/v1/dashboard/today', () => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    return ok(mswState.dashboard)
  }),

  /* ------------------------------------------------------- transactions */

  /**
   * Paged and enveloped: the 200 requires BOTH `data` and `meta`. The whole
   * query string is recorded so a test can assert that the pager sent `page=1`
   * and kept its filters, rather than asserting that "something rendered".
   */
  http.get('*/v1/transactions', ({ request }) => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    const url = new URL(request.url)
    mswState.lastTransactionsQuery = Object.fromEntries(url.searchParams.entries())

    if (mswState.transactionsRateLimited) {
      return fail(429, 'rate-limited', 'Too Many Requests', {}, { 'Retry-After': '12' })
    }

    const page = Number(url.searchParams.get('page') ?? '0')
    const limit = Number(url.searchParams.get('limit') ?? '50')
    if (Number.isNaN(page) || page < 0 || Number.isNaN(limit) || limit < 1 || limit > 500) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'limit', message: 'Rows per page must be between 1 and 500.' }],
      })
    }

    const from = url.searchParams.get('from')
    const to = url.searchParams.get('to')
    const type = url.searchParams.get('type')
    const accountId = url.searchParams.get('accountId')
    const categoryId = url.searchParams.get('categoryId')
    const q = url.searchParams.get('q')
    const sort = url.searchParams.get('sort') ?? 'date,desc'

    let rows = [...mswState.transactions]
    if (from) rows = rows.filter((t) => t.date >= from)
    if (to) rows = rows.filter((t) => t.date <= to)
    if (type) rows = rows.filter((t) => t.type === type)
    if (accountId) rows = rows.filter((t) => t.accountId === accountId)
    if (categoryId) rows = rows.filter((t) => t.categoryId === categoryId)
    if (q) {
      const needle = q.toLowerCase()
      rows = rows.filter((t) =>
        [t.merchantName, t.description, t.notes].some((f) => f?.toLowerCase().includes(needle))
      )
    }
    const dir = sort.endsWith('asc') ? 1 : -1
    const field = sort.startsWith('amount') ? 'amount' : 'date'
    rows.sort((a, b) =>
      field === 'amount'
        ? (Number(a.amount) - Number(b.amount)) * dir
        : a.date.localeCompare(b.date) * dir
    )

    const total = rows.length
    const meta: PaginationMeta = { total, page, limit }
    return HttpResponse.json<ApiResponse<TransactionResponse[]>>({
      success: true,
      data: rows.slice(page * limit, page * limit + limit),
      meta,
    })
  }),

  /** Manual entry. A non-`MANUAL` account is 409, exactly as the operation says. */
  http.post('*/v1/transactions', async ({ request }) => {
    const body = (await request.json()) as ManualTransactionRequest
    const account = mswState.accounts.find((a) => a.id === body.accountId)
    if (!account) return fail(404, 'not-found', 'Not Found')
    if (account.sourceKind !== 'MANUAL') {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'Transactions can only be entered by hand on a manual account.',
      })
    }
    if (!/^\d+(\.\d{1,4})?$/.test(body.amount) || Number(body.amount) <= 0) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'amount', message: 'Enter an amount greater than zero.' }],
      })
    }
    const created = txn({
      id: `txn-new-${mswState.transactions.length + 1}`,
      accountId: account.id,
      currency: account.currency,
      amount: body.amount,
      date: body.date,
      type: body.type ?? 'DEBIT',
      merchantName: body.merchant ?? null,
      categoryId: body.categoryId ?? null,
      notes: body.notes ?? null,
      manual: true,
      providerTransactionId: null,
    })
    mswState.transactions = [created, ...mswState.transactions]
    return ok(created, 201)
  }),

  http.get('*/v1/transactions/:id', ({ params }) => {
    const found = mswState.transactions.find((t) => t.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    return ok(found)
  }),

  /**
   * Presence-meaningful. The body is stored VERBATIM so a test can assert that
   * the client sent only what changed — a client that spreads its whole form
   * state in here would show up as extra keys, and an explicit `null` (unlinking
   * a transfer) is a different request from an absent key.
   */
  http.patch('*/v1/transactions/:id', async ({ params, request }) => {
    const body = (await request.json()) as TransactionPatchRequest
    mswState.lastTransactionPatch = body
    const idx = mswState.transactions.findIndex((t) => t.id === params.id)
    if (idx === -1) return fail(404, 'not-found', 'Not Found')
    const next = { ...mswState.transactions[idx] }
    if ('categoryId' in body) next.categoryId = body.categoryId ?? null
    if ('merchantName' in body) next.merchantName = body.merchantName ?? null
    if ('notes' in body) next.notes = body.notes ?? null
    if ('tags' in body) next.tags = body.tags ?? null
    if ('transferGroupId' in body) next.transferGroupId = body.transferGroupId ?? null
    mswState.transactions = mswState.transactions.map((t, i) => (i === idx ? next : t))
    return ok(next)
  }),

  http.delete('*/v1/transactions/:id', ({ params }) => {
    const found = mswState.transactions.find((t) => t.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    if (!found.manual) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'Only a hand-entered transaction can be deleted.',
      })
    }
    mswState.transactions = mswState.transactions.filter((t) => t.id !== params.id)
    return okEmpty()
  }),

  /* ------------------------------------------------------------ accounts */

  /**
   * Declared BEFORE the `/v1/accounts/:id` pattern so the literal path wins:
   * `msw` matches in registration order, and `exchange-setup-token` would
   * otherwise be read as an account id.
   */
  http.post('*/v1/accounts/exchange-setup-token', async ({ request }) => {
    const body = (await request.json()) as { sourceKind: string; setupToken: string }
    if (body.setupToken === 'rejected-egress') {
      return fail(400, 'outbound-url-rejected', 'Bad Request')
    }
    if (body.setupToken.trim() === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'setupToken', message: 'Paste the token from your bridge.' }],
      })
    }
    return ok(
      {
        accountIds: ['acct-1'],
        syncLogs: [syncLog({ id: 'sync-exchange', accountId: 'acct-1', transactionsNew: 2 })],
      },
      201
    )
  }),

  /** A bare array — the operation states this list is not paged. */
  http.get('*/v1/accounts', () => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    return ok(mswState.accounts)
  }),

  http.post('*/v1/accounts', async ({ request }) => {
    const body = (await request.json()) as AccountRequest
    if (!body.displayName || body.displayName.trim() === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'displayName', message: 'Give the account a name.' }],
      })
    }
    const created = acct({
      id: `acct-new-${mswState.accounts.length + 1}`,
      sourceKind: body.sourceKind,
      displayName: body.displayName,
      institution: body.institution ?? null,
      accountType: body.accountType,
      currency: body.currency ?? mswState.me.currency,
    })
    mswState.accounts = [created, ...mswState.accounts]
    mswState.syncLogs = { ...mswState.syncLogs, [created.id]: [] }
    return ok(created, 201)
  }),

  http.get('*/v1/accounts/:id/sync-logs', ({ params }) => {
    const id = String(params.id)
    if (!mswState.accounts.some((a) => a.id === id)) return fail(404, 'not-found', 'Not Found')
    return ok(mswState.syncLogs[id] ?? [])
  }),

  http.post('*/v1/accounts/:id/sync', ({ params }) => {
    const id = String(params.id)
    mswState.syncCalls += 1
    const account = mswState.accounts.find((a) => a.id === id)
    if (!account) return fail(404, 'not-found', 'Not Found')
    if (mswState.syncConflicts) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'A sync is already running for this account.',
      })
    }
    const run = syncLog({ id: `sync-${mswState.syncCalls}`, accountId: id, transactionsNew: 3 })
    mswState.syncLogs = { ...mswState.syncLogs, [id]: [run, ...(mswState.syncLogs[id] ?? [])] }
    mswState.accounts = mswState.accounts.map((a) =>
      a.id === id ? { ...a, syncStatus: 'OK', lastSyncedAt: run.completedAt, errorMessage: null } : a
    )
    return ok(run)
  }),

  http.get('*/v1/accounts/:id', ({ params }) => {
    const found = mswState.accounts.find((a) => a.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    return ok(found)
  }),

  http.put('*/v1/accounts/:id', async ({ params, request }) => {
    const body = (await request.json()) as AccountUpdateRequest
    const idx = mswState.accounts.findIndex((a) => a.id === params.id)
    if (idx === -1) return fail(404, 'not-found', 'Not Found')
    if (!body.displayName || body.displayName.trim() === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'displayName', message: 'Give the account a name.' }],
      })
    }
    const next: AccountResponse = {
      ...mswState.accounts[idx],
      displayName: body.displayName,
      institution: body.institution ?? null,
      accountType: body.accountType,
    }
    mswState.accounts = mswState.accounts.map((a, i) => (i === idx ? next : a))
    return ok(next)
  }),

  /** Cascades: the account's transactions go with it, which is what the copy warns about. */
  http.delete('*/v1/accounts/:id', ({ params }) => {
    const id = String(params.id)
    if (!mswState.accounts.some((a) => a.id === id)) return fail(404, 'not-found', 'Not Found')
    mswState.accounts = mswState.accounts.filter((a) => a.id !== id)
    mswState.transactions = mswState.transactions.filter((t) => t.accountId !== id)
    return okEmpty()
  }),

  /* ------------------------------------------------------------- imports */

  http.post('*/v1/imports', async ({ request }) => {
    if (mswState.uploadTooLarge) {
      return fail(413, 'upload-too-large', 'Payload Too Large', {
        detail: 'That file is larger than this server will store.',
      })
    }
    const form = await request.formData()
    const file = form.get('file')
    const accountId = form.get('accountId')
    const sourceKind = form.get('sourceKind')
    // `instanceof File` is unreliable here: the part msw parses comes from
    // undici's realm, not jsdom's, so the two `File` constructors are different
    // objects and the check is false for a genuine upload. Read the name.
    const filename =
      file !== null && typeof file === 'object' && 'name' in file
        ? String((file as { name: unknown }).name)
        : String(file ?? '')
    mswState.lastImportUpload = {
      filename,
      accountId: accountId === null ? null : String(accountId),
      sourceKind: sourceKind === null ? null : String(sourceKind),
    }
    if (accountId === null) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'accountId', message: 'Choose the account these rows belong to.' }],
      })
    }
    const batch = makeImportPreview({
      accountId: String(accountId),
      originalFilename: filename,
    })
    mswState.imports = { ...mswState.imports, [batch.id]: batch }
    return ok(batch, 201)
  }),

  http.get('*/v1/imports/:id/preview', ({ params }) => {
    const batch = mswState.imports[String(params.id)]
    if (!batch) return fail(404, 'not-found', 'Not Found')
    return ok(batch)
  }),

  http.post('*/v1/imports/:id/commit', async ({ params, request }) => {
    const id = String(params.id)
    const batch = mswState.imports[id]
    if (!batch) return fail(404, 'not-found', 'Not Found')
    const raw = await request.text()
    mswState.lastCommitBody = raw === '' || raw === 'null' ? null : (JSON.parse(raw) as ImportCommitRequest)
    if (batch.status !== 'PREVIEW') {
      return fail(409, 'conflict', 'Conflict', { detail: 'This import has already been committed.' })
    }
    mswState.imports = {
      ...mswState.imports,
      [id]: { ...batch, status: 'COMMITTED', rows: [] },
    }
    return ok(
      syncLog({
        id: `sync-import-${id}`,
        accountId: batch.accountId,
        sourceKind: 'CSV',
        transactionsFetched: batch.rowCount,
        transactionsNew: batch.newCount,
        transactionsSkipped: batch.duplicateCount,
        transactionsFailed: batch.invalidCount,
        status: batch.invalidCount > 0 ? 'PARTIAL_FAILURE' : 'SUCCESS',
        errorMessage: batch.invalidCount > 0 ? 'Some rows could not be read.' : null,
      })
    )
  }),

  /* ==========================================================================
   * Family D (step 204) — the AI surface. Contract-derived, like family A.
   * ======================================================================== */

  /* ------------------------------------------------------------ ai settings */

  /** The order below mirrors the spec's own, so the file reads as the document does. */
  http.get('*/v1/ai/settings', () => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    // Never a 404: a member who has saved nothing gets the defaults.
    return ok(mswState.aiSettings)
  }),

  /**
   * A REPLACE. The body is stored VERBATIM so a test can assert the form resent
   * the whole shape rather than only what changed — and that it never sent a key
   * it did not mean to.
   */
  http.put('*/v1/ai/settings', async ({ request }) => {
    const body = (await request.json()) as AiSettingsUpdateRequest
    mswState.lastAiSettingsBody = body
    if (mswState.aiSettingsSaveFailure) return slugFail(mswState.aiSettingsSaveFailure)
    if (body.enabled && (!body.endpointUrl || !body.model)) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'model', message: 'Choose a model before turning the coach on.' }],
      })
    }
    if (body.endpointUrl && !/^https:\/\//i.test(body.endpointUrl) && !body.allowPrivateEndpoint) {
      return fail(400, 'outbound-url-rejected', 'Bad Request', {
        detail: 'Your endpoint must use https unless private targets are allowed.',
      })
    }
    if (body.apiKey !== undefined && body.apiKey !== null && body.apiKey.trim() === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'apiKey', message: 'Use Forget key to remove a stored key.' }],
      })
    }
    // The key is write-only: it is recorded as a BOOLEAN and the value is
    // dropped here, exactly as the server drops it into an encrypted column.
    mswState.aiSettings = {
      ...mswState.aiSettings,
      enabled: body.enabled,
      endpointUrl: body.endpointUrl ?? null,
      model: body.model ?? null,
      visionModel: body.visionModel ?? null,
      allowPrivateEndpoint: body.allowPrivateEndpoint,
      privacyTier: body.privacyTier,
      connectTimeoutMs: body.connectTimeoutMs ?? mswState.aiSettings.connectTimeoutMs,
      readTimeoutMs: body.readTimeoutMs ?? mswState.aiSettings.readTimeoutMs,
      hasApiKey: body.apiKey ? true : mswState.aiSettings.hasApiKey,
      updatedAt: '2026-09-06T12:00:00Z',
    }
    return ok(mswState.aiSettings)
  }),

  /** Idempotent, and deliberately no 404. `EmptySuccess`. */
  http.delete('*/v1/ai/settings/api-key', () => {
    mswState.aiSettings = { ...mswState.aiSettings, hasApiKey: false }
    return okEmpty()
  }),

  /** No request body: the settings under test are the saved ones. */
  http.post('*/v1/ai/settings/test', () => {
    if (mswState.aiTestFailure) return slugFail(mswState.aiTestFailure)
    if (!mswState.aiSettings.endpointUrl || !mswState.aiSettings.model) {
      return fail(409, 'ai-disabled', 'Conflict', {
        detail: 'Save an endpoint and a chat model first.',
      })
    }
    mswState.aiSettings = {
      ...mswState.aiSettings,
      lastTestAt: '2026-09-06T12:00:02Z',
      lastTestOk: true,
    }
    return ok<AiConnectionTestResponse>({
      ok: true,
      endpointHost: 'ai.example.test',
      checkedAt: '2026-09-06T12:00:02Z',
      modelCount: mswState.aiModels.length,
      visionModelPresent: mswState.aiSettings.visionModel !== null,
    })
  }),

  /**
   * Needs a saved `endpointUrl` and NOTHING else — not `enabled`, and pointedly
   * not a `model`, which is what it is there to help choose.
   */
  http.get('*/v1/ai/models', () => {
    if (mswState.aiModelsFailure) return slugFail(mswState.aiModelsFailure)
    if (!mswState.aiSettings.endpointUrl) {
      return fail(409, 'ai-disabled', 'Conflict', { detail: 'Save an endpoint first.' })
    }
    return ok(mswState.aiModels)
  }),

  /* ------------------------------------------------------------------ coach */

  /**
   * `POST /v1/chat/completions`. `X-Session-Id` is set on EVERY response,
   * streaming or not, and the request's own header is recorded so a test can
   * assert the session round-trips.
   *
   * The streamed body is written from `mswState.chatStreamChunks` verbatim, one
   * `enqueue` per entry — which is what lets a case pin a frame split across two
   * chunk boundaries, an in-band error frame with no `[DONE]` after it, and a
   * stream that ends with neither.
   */
  http.post('*/v1/chat/completions', async ({ request }) => {
    mswState.lastChatSessionHeader = request.headers.get('X-Session-Id')
    mswState.lastChatBody = (await request.json()) as ChatCompletionRequest

    if (mswState.chatResponseHangs) {
      // Nothing is written at all — not even a status line — until the caller
      // gives up. The client's own `fetch` is what rejects, before any response
      // exists to read.
      await new Promise<void>((resolve) => {
        if (request.signal.aborted) resolve()
        else request.signal.addEventListener('abort', () => resolve(), { once: true })
      })
    }

    const sessionHeaders = { 'X-Session-Id': mswState.chatSessionId }

    // Everything decidable before the first byte is ordinary JSON, whatever
    // `stream` said — the operation is explicit about this.
    if (mswState.chatFailure) {
      const f = mswState.chatFailure
      return fail(f.status, f.slug, f.title, f.detail ? { detail: f.detail } : {}, sessionHeaders)
    }

    const streaming = new URL(request.url).searchParams.get('stream') === 'true'
    if (!streaming || mswState.chatAnswersJsonDespiteStream) {
      return HttpResponse.json<ApiResponse<ChatCompletionResponse>>(
        {
          success: true,
          data: {
            sessionId: mswState.chatSessionId,
            role: 'ASSISTANT',
            content: DEFAULT_STREAM_ANSWER,
            model: 'qwen2.5:14b',
            createdAt: '2026-09-05T18:04:11Z',
          },
        },
        { headers: sessionHeaders }
      )
    }

    const encoder = new TextEncoder()
    const chunks = [...mswState.chatStreamChunks]
    const hangs = mswState.chatStreamHangs
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
        // Left open on purpose when `chatStreamHangs`: only an abort ends it.
        if (!hangs) controller.close()
      },
    })
    return new HttpResponse(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', ...sessionHeaders },
    })
  }),

  /** Costs no AI call and needs no endpoint — a member with AI off still gets a list. */
  http.get('*/v1/chat/suggestions', () => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    return ok(mswState.chatSuggestions)
  }),

  /** PAGED: the 200 requires BOTH `data` and `meta`. */
  http.get('*/v1/chat/history', ({ request }) => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    const url = new URL(request.url)
    const sessionId = url.searchParams.get('sessionId')
    // Absent and empty are not the same thing: `?sessionId=` is 400.
    if (sessionId === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'sessionId', message: 'sessionId must be a uuid.' }],
      })
    }
    const page = Number(url.searchParams.get('page') ?? '0')
    const limit = Number(url.searchParams.get('limit') ?? '50')
    const rows = sessionId
      ? mswState.chatMessages.filter((m) => m.sessionId === sessionId)
      : mswState.chatMessages
    if (sessionId && rows.length === 0) return fail(404, 'not-found', 'Not Found')
    return HttpResponse.json<ApiResponse<CoachMessageResponse[]>>({
      success: true,
      data: rows.slice(page * limit, page * limit + limit),
      meta: { total: rows.length, page, limit } satisfies PaginationMeta,
    })
  }),

  /** `EmptySuccess`. Every call is recorded so the "all vs one" argument is assertable. */
  http.delete('*/v1/chat/history', ({ request }) => {
    const sessionId = new URL(request.url).searchParams.get('sessionId')
    if (sessionId === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'sessionId', message: 'sessionId must be a uuid.' }],
      })
    }
    mswState.chatHistoryDeletes.push(sessionId)
    mswState.chatMessages = sessionId
      ? mswState.chatMessages.filter((m) => m.sessionId !== sessionId)
      : []
    return okEmpty()
  }),

  /** PAGED, newest first, and a safe GET: reading it delivers nothing. */
  http.get('*/v1/insights', ({ request }) => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '0')
    const limit = Number(url.searchParams.get('limit') ?? '50')
    const rows = mswState.insights
    return HttpResponse.json<ApiResponse<InsightResponse[]>>({
      success: true,
      data: rows.slice(page * limit, page * limit + limit),
      meta: { total: rows.length, page, limit } satisfies PaginationMeta,
    })
  }),

  /* --------------------------------------------------------------- receipts */

  /**
   * Registered before the by-id patterns so that `upload` is never matched as a
   * receipt id: msw resolves handlers in registration order.
   */
  http.post('*/v1/receipts/upload', async ({ request }) => {
    if (mswState.receiptUploadTooLarge) {
      return fail(413, 'upload-too-large', 'Payload Too Large', {
        detail: 'That image is larger than this server will store.',
      })
    }
    const form = await request.formData()
    const file = form.get('file')
    const source = form.get('source')
    // `instanceof File` is unreliable across msw's realm — read the fields.
    const named = file !== null && typeof file === 'object' ? (file as File) : null
    mswState.lastReceiptUpload = {
      filename: named?.name ?? String(file ?? ''),
      type: named?.type ?? '',
      source: source === null ? null : String(source),
    }
    if (named === null || named.name === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'file', message: 'Choose a JPEG or PNG image.' }],
      })
    }
    if (named.type !== '' && !['image/jpeg', 'image/png'].includes(named.type)) {
      return fail(400, 'validation-error', 'Bad Request', {
        detail: 'Only JPEG and PNG images are accepted.',
      })
    }
    const created = makeReceipt({
      id: `rcpt-new-${mswState.receipts.length + 1}`,
      status: 'UPLOADED',
      reviewRequired: true,
      extractedMerchant: null,
      extractedAmount: null,
      extractedCurrency: null,
      extractedDate: null,
      confidence: null,
      gstAmount: null,
      pstAmount: null,
      hstAmount: null,
      lineItems: [],
      source: (source === 'SCAN' ? 'SCAN' : 'UPLOAD') as ReceiptResponse['source'],
    })
    mswState.receipts = [created, ...mswState.receipts]
    // 202, and three fields only: the row exists, its fields do not yet.
    return ok<ReceiptUploadResponse>(
      { id: created.id, status: created.status, createdAt: created.createdAt },
      202
    )
  }),

  /** PAGED and enveloped — there is no bare array on this API. */
  http.get('*/v1/receipts', ({ request }) => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '0')
    const limit = Number(url.searchParams.get('limit') ?? '50')
    if (Number.isNaN(page) || page < 0 || Number.isNaN(limit) || limit < 1 || limit > 200) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'limit', message: 'Rows per page must be between 1 and 200.' }],
      })
    }
    const rows = mswState.receipts.map(summaryOf)
    return HttpResponse.json<ApiResponse<ReceiptSummaryResponse[]>>({
      success: true,
      data: rows.slice(page * limit, page * limit + limit),
      meta: { total: rows.length, page, limit } satisfies PaginationMeta,
    })
  }),

  /** Four fields only. The walk lets a test drive a receipt to a terminal status. */
  http.get('*/v1/receipts/:id/status', ({ params }) => {
    mswState.receiptStatusPolls += 1
    const found = mswState.receipts.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    const next = mswState.receiptStatusWalk.length > 1
      ? mswState.receiptStatusWalk.shift()
      : mswState.receiptStatusWalk[0]
    const status = next ?? found.status
    if (next) {
      mswState.receipts = mswState.receipts.map((r) => (r.id === found.id ? { ...r, status } : r))
    }
    return ok<ReceiptStatusResponse>({
      id: found.id,
      status,
      reviewRequired: found.reviewRequired,
      confidence: found.confidence,
    })
  }),

  /** `Content-Disposition: attachment` with a server-built filename, and nosniff. */
  http.get('*/v1/receipts/:id/download', ({ params }) => {
    const found = mswState.receipts.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    return jpeg({ 'Content-Disposition': `attachment; filename="receipt-${found.id}.jpg"` })
  }),

  /** Authenticated, nosniff, and NO `Content-Disposition` — a thumbnail is rendered. */
  http.get('*/v1/receipts/:id/thumbnail', ({ params }) => {
    mswState.thumbnailFetches += 1
    const found = mswState.receipts.find((r) => r.id === params.id)
    if (!found || mswState.thumbnailMissing) return fail(404, 'not-found', 'Not Found')
    return jpeg()
  }),

  /** A DECISION, not a correction. `accepted: false` marks the receipt FAILED. */
  http.patch('*/v1/receipts/:id/review', async ({ params, request }) => {
    const body = (await request.json()) as ReceiptReviewRequest
    mswState.lastReceiptReview = body
    const found = mswState.receipts.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    if (!['COMPLETE', 'FAILED'].includes(found.status)) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This receipt is still being processed. Try again when it has finished.',
      })
    }
    const next: ReceiptResponse = body.accepted
      ? { ...found, reviewRequired: false }
      : { ...found, status: 'FAILED', reviewRequired: false }
    mswState.receipts = mswState.receipts.map((r) => (r.id === next.id ? next : r))
    return ok(next)
  }),

  http.get('*/v1/receipts/:id', ({ params }) => {
    const found = mswState.receipts.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    return ok(found)
  }),

  /**
   * Presence-meaningful in one direction only: an absent field is left alone and
   * there is NO way to clear one back to null. The body is stored verbatim so a
   * test can assert the screen sent only what the member changed.
   */
  http.patch('*/v1/receipts/:id', async ({ params, request }) => {
    const body = (await request.json()) as ReceiptPatchRequest
    mswState.lastReceiptPatch = body
    const found = mswState.receipts.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    if (!['COMPLETE', 'FAILED'].includes(found.status)) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This receipt is still being processed. Try again when it has finished.',
      })
    }
    if (Object.keys(body).length === 0) {
      return fail(400, 'validation-error', 'Bad Request', {
        detail: 'Change at least one field.',
      })
    }
    const next: ReceiptResponse = { ...found }
    if (body.extractedMerchant !== undefined) next.extractedMerchant = body.extractedMerchant
    if (body.extractedAmount !== undefined) next.extractedAmount = body.extractedAmount
    if (body.extractedCurrency !== undefined) next.extractedCurrency = body.extractedCurrency
    if (body.extractedDate !== undefined) next.extractedDate = body.extractedDate
    if (body.matchedTransactionId !== undefined) {
      next.matchedTransactionId = body.matchedTransactionId
    }
    mswState.receipts = mswState.receipts.map((r) => (r.id === next.id ? next : r))
    return ok(next)
  }),

  /** The row, its line items and the files behind them. `EmptySuccess`. */
  http.delete('*/v1/receipts/:id', ({ params }) => {
    const id = String(params.id)
    if (!mswState.receipts.some((r) => r.id === id)) return fail(404, 'not-found', 'Not Found')
    mswState.receipts = mswState.receipts.filter((r) => r.id !== id)
    return okEmpty()
  }),

  http.delete('*/v1/imports/:id', ({ params }) => {
    const id = String(params.id)
    const batch = mswState.imports[id]
    if (!batch) return fail(404, 'not-found', 'Not Found')
    if (batch.status !== 'COMMITTED') {
      return fail(409, 'conflict', 'Conflict', { detail: 'That import was never committed.' })
    }
    mswState.imports = { ...mswState.imports, [id]: { ...batch, status: 'UNDONE' } }
    return okEmpty()
  }),

  /* ------------------------------------------------- family C (step 203) */
  /* ---- net worth ---- */

  http.get('*/v1/networth/series', ({ request }) => {
    const url = new URL(request.url)
    mswState.lastNetWorthRange = url.searchParams.get('range')
    return ok(mswState.netWorthSeries)
  }),

  http.post('*/v1/networth/snapshot', () => ok(makeNetWorthSnapshot(), 201)),

  http.get('*/v1/networth/assets', () => ok(mswState.assets)),

  http.post('*/v1/networth/assets', async ({ request }) => {
    const body = (await request.json()) as AssetRequest
    if (body.name.trim() === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'name', message: 'Name is required.' }],
      })
    }
    const now = new Date().toISOString()
    const created: AssetResponse = {
      id: `asset-${idSeq++}`,
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      name: body.name,
      assetType: body.assetType,
      value: body.value,
      currency: body.currency ?? mswState.me.currency,
      accountId: body.accountId ?? null,
      createdAt: now,
      updatedAt: now,
    }
    mswState.assets = [...mswState.assets, created]
    return ok(created, 201)
  }),

  http.put('*/v1/networth/assets/:id', async ({ params, request }) => {
    const body = (await request.json()) as AssetUpdateRequest
    const found = mswState.assets.find((a) => a.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    const next: AssetResponse = {
      ...found,
      name: body.name,
      assetType: body.assetType,
      value: body.value,
      accountId: body.accountId ?? null,
      updatedAt: new Date().toISOString(),
    }
    mswState.assets = mswState.assets.map((a) => (a.id === next.id ? next : a))
    return ok(next)
  }),

  http.delete('*/v1/networth/assets/:id', ({ params }) => {
    const id = String(params.id)
    if (!mswState.assets.some((a) => a.id === id)) return fail(404, 'not-found', 'Not Found')
    mswState.assets = mswState.assets.filter((a) => a.id !== id)
    return okEmpty()
  }),

  http.get('*/v1/networth/liabilities', () => ok(mswState.liabilities)),

  http.post('*/v1/networth/liabilities', async ({ request }) => {
    const body = (await request.json()) as LiabilityRequest
    if (body.name.trim() === '') {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'name', message: 'Name is required.' }],
      })
    }
    const now = new Date().toISOString()
    const created: LiabilityResponse = {
      id: `liability-${idSeq++}`,
      householdId: HOUSEHOLD,
      createdBy: MEMBER,
      name: body.name,
      liabilityType: body.liabilityType,
      balance: body.balance,
      interestRate: body.interestRate ?? '0.0000',
      currency: body.currency ?? mswState.me.currency,
      accountId: body.accountId ?? null,
      createdAt: now,
      updatedAt: now,
    }
    mswState.liabilities = [...mswState.liabilities, created]
    return ok(created, 201)
  }),

  http.put('*/v1/networth/liabilities/:id', async ({ params, request }) => {
    const body = (await request.json()) as LiabilityUpdateRequest
    const found = mswState.liabilities.find((l) => l.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    const next: LiabilityResponse = {
      ...found,
      name: body.name,
      liabilityType: body.liabilityType,
      balance: body.balance,
      interestRate: body.interestRate ?? '0.0000',
      accountId: body.accountId ?? null,
      updatedAt: new Date().toISOString(),
    }
    mswState.liabilities = mswState.liabilities.map((l) => (l.id === next.id ? next : l))
    return ok(next)
  }),

  http.delete('*/v1/networth/liabilities/:id', ({ params }) => {
    const id = String(params.id)
    if (!mswState.liabilities.some((l) => l.id === id)) return fail(404, 'not-found', 'Not Found')
    mswState.liabilities = mswState.liabilities.filter((l) => l.id !== id)
    return okEmpty()
  }),

  /* ---- subscriptions ---- */

  http.get('*/v1/subscriptions', () => ok(mswState.subscriptions.filter((s) => !s.cancelled))),

  http.get('*/v1/subscriptions/summary', () => ok(computeSubscriptionSummary())),

  http.get('*/v1/subscriptions/alerts', () =>
    ok(mswState.subscriptionAlerts.filter((a) => !a.isRead))
  ),

  http.patch('*/v1/subscriptions/alerts/:id/read', ({ params }) => {
    const found = mswState.subscriptionAlerts.find((a) => a.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    const next = { ...found, isRead: true }
    mswState.subscriptionAlerts = mswState.subscriptionAlerts.map((a) =>
      a.id === next.id ? next : a
    )
    return ok(next)
  }),

  http.post('*/v1/subscriptions/scan', () => {
    mswState.scanCalls += 1
    return ok(mswState.subscriptions.filter((s) => !s.cancelled))
  }),

  http.get('*/v1/subscriptions/:id', ({ params }) => {
    const found = mswState.subscriptions.find((s) => s.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    return ok(found)
  }),

  /** SOFT delete — the row survives with `cancelled: true`. Never contacts anything. */
  http.delete('*/v1/subscriptions/:id', ({ params }) => {
    const id = String(params.id)
    const found = mswState.subscriptions.find((s) => s.id === id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    mswState.subscriptions = mswState.subscriptions.map((s) =>
      s.id === id ? { ...s, cancelled: true } : s
    )
    return okEmpty()
  }),

  /* ---- tax export ---- */

  http.get('*/v1/tax-export/lines', () => ok(mswState.taxLines)),

  http.put('*/v1/tax-export/lines/:categoryId', async ({ params, request }) => {
    const body = (await request.json()) as TaxLineMappingRequest
    mswState.lastTaxLineBody = body
    const found = mswState.taxLines.find((l) => l.categoryId === params.categoryId)
    if (!found) return fail(404, 'not-found', 'Not Found')
    const next: TaxLineMappingResponse = {
      ...found,
      jurisdiction: body.jurisdiction,
      lineCode: body.lineCode,
      lineLabel: body.lineLabel,
      deductible: body.deductible,
      overridden: true,
    }
    mswState.taxLines = mswState.taxLines.map((l) => (l.categoryId === next.categoryId ? next : l))
    return ok(next)
  }),

  http.get('*/v1/tax-export/runs', ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '0')
    const limit = Number(url.searchParams.get('limit') ?? '50')
    if (Number.isNaN(page) || page < 0 || Number.isNaN(limit) || limit < 1 || limit > 200) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'limit', message: 'Rows per page must be between 1 and 200.' }],
      })
    }
    // Advance every non-terminal run towards COMPLETE once the poll budget is
    // spent — the poll-to-completion assertion needs a run that is not ready
    // on its first fetch.
    const hasPending = mswState.taxExportRuns.some(
      (r) => r.status === 'PENDING' || r.status === 'GENERATING'
    )
    if (hasPending) {
      if (mswState.taxExportPollsRemaining > 0) {
        mswState.taxExportPollsRemaining -= 1
      } else {
        mswState.taxExportRuns = mswState.taxExportRuns.map((r) =>
          r.status === 'PENDING' || r.status === 'GENERATING'
            ? { ...r, status: 'COMPLETE', fileSizeBytes: 4096, updatedAt: new Date().toISOString() }
            : r
        )
      }
    }
    const rows = mswState.taxExportRuns
    return HttpResponse.json<ApiResponse<TaxExportRunResponse[]>>({
      success: true,
      data: rows.slice(page * limit, page * limit + limit),
      meta: { total: rows.length, page, limit } satisfies PaginationMeta,
    })
  }),

  http.post('*/v1/tax-export/runs', async ({ request }) => {
    const body = (await request.json()) as { taxYear: number; format: 'CSV' | 'PDF' }
    if (body.taxYear > new Date().getFullYear()) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'taxYear', message: 'The tax year cannot be in the future.' }],
      })
    }
    const now = new Date().toISOString()
    const created: TaxExportRunResponse = {
      id: `run-${idSeq++}`,
      userId: MEMBER,
      taxYear: body.taxYear,
      jurisdiction: 'CA',
      format: body.format,
      status: 'PENDING',
      fileSizeBytes: null,
      errorMessage: null,
      createdAt: now,
      updatedAt: now,
    }
    mswState.taxExportRuns = [created, ...mswState.taxExportRuns]
    return ok(created, 202)
  }),

  http.get('*/v1/tax-export/runs/:id', ({ params }) => {
    const found = mswState.taxExportRuns.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    return ok(found)
  }),

  http.delete('*/v1/tax-export/runs/:id', ({ params }) => {
    const id = String(params.id)
    if (!mswState.taxExportRuns.some((r) => r.id === id)) return fail(404, 'not-found', 'Not Found')
    mswState.taxExportRuns = mswState.taxExportRuns.filter((r) => r.id !== id)
    return okEmpty()
  }),

  /** A run that is not `COMPLETE` answers 409 `ExportNotReady` (`conflict`), read as "still generating". */
  http.get('*/v1/tax-export/runs/:id/download', ({ params }) => {
    const found = mswState.taxExportRuns.find((r) => r.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    if (found.status !== 'COMPLETE') {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This export is still generating.',
      })
    }
    return taxExportFile(found)
  }),

  /* ---- notifications ---- */

  http.get('*/v1/notifications', ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? '0')
    const limit = Number(url.searchParams.get('limit') ?? '50')
    if (Number.isNaN(page) || page < 0 || Number.isNaN(limit) || limit < 1 || limit > 200) {
      return fail(400, 'validation-error', 'Bad Request', {
        fieldErrors: [{ field: 'limit', message: 'Rows per page must be between 1 and 200.' }],
      })
    }
    const rows = mswState.notifications.filter((n) => !n.dismissed)
    return HttpResponse.json<ApiResponse<NotificationResponse[]>>({
      success: true,
      data: rows.slice(page * limit, page * limit + limit),
      meta: { total: rows.length, page, limit } satisfies PaginationMeta,
    })
  }),

  http.get('*/v1/notifications/unread-count', () =>
    ok({ unread: mswState.notifications.filter((n) => !n.read && !n.dismissed).length })
  ),

  http.patch('*/v1/notifications/:id/read', ({ params }) => {
    const found = mswState.notifications.find((n) => n.id === params.id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    const next: NotificationResponse = found.read
      ? found
      : { ...found, read: true, readAt: new Date().toISOString() }
    mswState.notifications = mswState.notifications.map((n) => (n.id === next.id ? next : n))
    return ok(next)
  }),

  http.post('*/v1/notifications/read-all', () => {
    let marked = 0
    mswState.notifications = mswState.notifications.map((n) => {
      if (n.read || n.dismissed) return n
      marked += 1
      return { ...n, read: true, readAt: new Date().toISOString() }
    })
    return ok({ marked })
  }),

  http.get('*/v1/notifications/preferences', () => ok(mswState.notificationPreferences)),

  http.put('*/v1/notifications/preferences', async ({ request }) => {
    const body = (await request.json()) as NotificationPreferencesUpdateRequest
    mswState.lastNotificationPreferencesBody = body
    const next: NotificationPreferencesResponse = {
      ...mswState.notificationPreferences,
      updatedAt: new Date().toISOString(),
    }
    if (body.inAppEnabled !== undefined) next.inAppEnabled = body.inAppEnabled
    if (body.emailEnabled !== undefined) next.emailEnabled = body.emailEnabled
    if (body.emailAddress !== undefined) next.emailAddress = body.emailAddress
    if (body.weeklyDigestEnabled !== undefined) next.weeklyDigestEnabled = body.weeklyDigestEnabled
    if (body.subscriptionAlertEnabled !== undefined) {
      next.subscriptionAlertEnabled = body.subscriptionAlertEnabled
    }
    if (body.syncFailureAlertEnabled !== undefined) {
      next.syncFailureAlertEnabled = body.syncFailureAlertEnabled
    }
    if (body.budgetAlertThresholdPercent !== undefined) {
      next.budgetAlertThresholdPercent = body.budgetAlertThresholdPercent
    }
    mswState.notificationPreferences = next
    return ok(next)
  }),

  /** Hides it from the inbox and the unread count. The row is retained (`dismissed: true`). */
  http.delete('*/v1/notifications/:id', ({ params }) => {
    const id = String(params.id)
    const found = mswState.notifications.find((n) => n.id === id)
    if (!found) return fail(404, 'not-found', 'Not Found')
    mswState.notifications = mswState.notifications.map((n) =>
      n.id === id ? { ...n, dismissed: true, dismissedAt: new Date().toISOString() } : n
    )
    return okEmpty()
  }),

  /* ---- webhooks ---- */

  http.get('*/v1/webhooks', () => ok(mswState.webhooks)),

  http.post('*/v1/webhooks', async ({ request }) => {
    const body = (await request.json()) as WebhookCreateRequest
    mswState.lastWebhookCreateBody = body
    if (mswState.webhookUrlRejected || !body.targetUrl.startsWith('https://')) {
      return fail(400, 'outbound-url-rejected', 'Bad Request', {
        detail: 'That address resolves to a private network range this server will not call.',
      })
    }
    if (mswState.webhookCapReached) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'You already have the maximum number of webhook subscriptions.',
      })
    }
    const now = new Date().toISOString()
    const created: WebhookResponse = {
      id: `hook-${idSeq++}`,
      targetUrl: body.targetUrl,
      enabled: true,
      eventTypes: body.eventTypes ?? [],
      hasSecret: true,
      createdAt: now,
      updatedAt: now,
    }
    mswState.webhooks = [...mswState.webhooks, created]
    return ok(created, 201)
  }),

  http.delete('*/v1/webhooks/:id', ({ params }) => {
    const id = String(params.id)
    if (!mswState.webhooks.some((w) => w.id === id)) return fail(404, 'not-found', 'Not Found')
    mswState.webhooks = mswState.webhooks.filter((w) => w.id !== id)
    return okEmpty()
  }),

  /* ==========================================================================
   * Family E (step 205) — auth completion, account security, data export,
   * self-deletion and the admin console. Every body below is typed from
   * `@api/types`, which projects the generated schema, so a mock that drifts
   * from `openapi/trove-core.yaml` fails `tsc` rather than passing a test.
   * ======================================================================== */

  /* ------------------------------------------------- auth completion */

  /** Always 200 — the verdict is in the body, never in the status. */
  http.get('*/v1/auth/invites/:token', () => ok<InvitePreviewResponse>(mswState.invitePreview)),

  http.post('*/v1/auth/password', async ({ request }) => {
    const body = (await request.json()) as PasswordChangeRequest
    mswState.lastPasswordChangeSent = true
    // A wrong current password is 403 `step-up-required`, NOT 401: a 401 would
    // send the client interceptor off to refresh a session that is perfectly good.
    if (body.currentPassword !== mswState.stepUpPassword) {
      return fail(403, 'step-up-required', 'Forbidden', {
        detail: 'That password was not accepted.',
      })
    }
    if (body.newPassword.length < 12) {
      return fail(400, 'password-policy', 'Bad Request', {
        fieldErrors: [{ field: 'newPassword', message: 'Use at least 12 characters.' }],
      })
    }
    return ok<CookieSession>({
      user: {
        id: mswState.me.userId,
        email: mswState.me.email,
        displayName: mswState.me.displayName,
        householdId: mswState.me.householdId,
        role: mswState.me.role,
        totpEnabled: mswState.me.totpEnabled,
      },
    })
  }),

  /** ADMIN-scoped: reset here is an admin minting a link, not an email to the member. */
  http.post('*/v1/auth/password/reset-request', async ({ request }) => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    const body = (await request.json()) as PasswordResetLinkRequest
    mswState.lastResetLinkRequest = body
    if (!mswState.members.some((m) => m.email === body.email)) {
      return fail(404, 'not-found', 'Not Found')
    }
    return ok<PasswordResetLinkResponse>({
      url: `https://trove.test/reset-password/${mswState.validResetToken}`,
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    })
  }),

  /** Public. Unknown, expired and already-used tokens are the identical 400 `reset-invalid`. */
  http.post('*/v1/auth/password/reset', async ({ request }) => {
    const body = (await request.json()) as PasswordResetRequest
    mswState.lastResetBody = body
    // The policy is checked BEFORE the token is consumed, so a refused password
    // leaves the link usable — hence this order.
    if (body.password.length < 12) {
      return fail(400, 'password-policy', 'Bad Request', {
        fieldErrors: [{ field: 'password', message: 'Use at least 12 characters.' }],
      })
    }
    if (body.token !== mswState.validResetToken) {
      return fail(400, 'reset-invalid', 'Bad Request', {
        detail: 'That reset link is not usable.',
      })
    }
    return okEmpty()
  }),

  http.post('*/v1/auth/totp/setup', async ({ request }) => {
    const body = (await request.json()) as TotpSetupRequest
    if (body.password !== mswState.stepUpPassword) {
      return fail(403, 'step-up-required', 'Forbidden', {
        detail: 'That password was not accepted.',
      })
    }
    if (mswState.totpAlreadyEnabled) {
      return fail(409, 'totp-already-enabled', 'Conflict')
    }
    return ok<TotpEnrolment>(mswState.totpEnrolment)
  }),

  http.post('*/v1/auth/totp/confirm', async ({ request }) => {
    const body = (await request.json()) as TotpConfirmRequest
    if (body.code !== mswState.stepUpCode) return fail(401, 'totp-invalid', 'Unauthorized')
    mswState.totpConfirmCalls += 1
    mswState.me = { ...mswState.me, totpEnabled: true }
    return ok<TotpConfirmResponse>({ recoveryCodes: [...mswState.totpRecoveryCodes] })
  }),

  /** The POST twin. Password AND code; either one wrong is 403 `step-up-required`. */
  http.post('*/v1/auth/totp/disable', async ({ request }) => {
    const body = (await request.json()) as TotpDisableRequest
    if (body.password !== mswState.stepUpPassword || body.code !== mswState.stepUpCode) {
      return fail(403, 'step-up-required', 'Forbidden', {
        detail: 'That password or code was not accepted.',
      })
    }
    mswState.me = { ...mswState.me, totpEnabled: false }
    return okEmpty()
  }),

  http.get('*/v1/me/security-events', () => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    return ok<SecurityEventView[]>(mswState.securityEvents)
  }),

  /* ------------------------------------------------- self-deletion */

  http.post('*/v1/me/delete', async ({ request }) => {
    mswState.lastAccountDeleteVerb = 'POST'
    const refusal = await recordStepUp(request)
    if (refusal) return refusal
    if (mswState.lastAdminConflict) {
      return fail(409, 'conflict', 'Conflict', {
        detail:
          'You are the only administrator of this household. Give another member the admin role first.',
      })
    }
    mswState.authenticated = false
    return okEmpty()
  }),

  /**
   * The DELETE twin is registered so that a client calling it is VISIBLE rather
   * than a 404 — the assertion is that the shipped client uses the POST form.
   */
  http.delete('*/v1/me', async ({ request }) => {
    mswState.lastAccountDeleteVerb = 'DELETE'
    const refusal = await recordStepUp(request)
    if (refusal) return refusal
    mswState.authenticated = false
    return okEmpty()
  }),

  /* ------------------------------------------------- data export */

  http.get('*/v1/me/export', ({ request }) => {
    if (!mswState.authenticated) return fail(401, 'unauthorized', 'Unauthorized')
    // Advance any non-terminal run once the poll budget runs out, so a test can
    // watch PENDING/GENERATING become COMPLETE without a real background worker.
    if (mswState.dataExports.some((r) => r.status === 'PENDING' || r.status === 'GENERATING')) {
      if (mswState.dataExportPollsRemaining > 0) {
        mswState.dataExportPollsRemaining -= 1
      } else {
        mswState.dataExports = mswState.dataExports.map((r) =>
          r.status === 'PENDING' || r.status === 'GENERATING'
            ? {
                ...r,
                status: 'COMPLETE',
                fileSizeBytes: 20480,
                expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
                updatedAt: new Date().toISOString(),
              }
            : r
        )
      }
    }
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 20)
    const rows = mswState.dataExports.slice(page * limit, page * limit + limit)
    return HttpResponse.json<ApiResponse<DataExportRunResponse[]>>({
      success: true,
      data: rows,
      meta: { total: mswState.dataExports.length, page, limit } satisfies PaginationMeta,
    })
  }),

  http.post('*/v1/me/export', async ({ request }) => {
    const refusal = await recordStepUp(request)
    if (refusal) return refusal
    if (mswState.dataExportDailyCap) {
      // One run per member per day, counted over the MEMBER's own local day. The
      // server's own message says when they may ask again; no client-side timer
      // ever guesses at it.
      return fail(409, 'conflict', 'Conflict', { detail: mswState.dataExportDailyCap })
    }
    const run: DataExportRunResponse = {
      id: `export-${mswState.dataExports.length + 1}`,
      userId: mswState.me.userId,
      status: 'PENDING',
      fileSizeBytes: null,
      errorMessage: null,
      expiresAt: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    mswState.dataExports = [run, ...mswState.dataExports]
    return ok<DataExportRunResponse>(run, 202)
  }),

  http.get('*/v1/me/export/:id/download', ({ params }) => {
    const run = mswState.dataExports.find((r) => r.id === params.id)
    if (!run) return fail(404, 'not-found', 'Not Found')
    // 409 covers BOTH a run that is not COMPLETE and one whose 24-hour link has
    // expired — never 404, because the run exists and belongs to the caller.
    if (mswState.dataExportDownloadNotReady || run.status !== 'COMPLETE') {
      return fail(409, 'conflict', 'Conflict', { detail: 'That export is not ready to download.' })
    }
    // A Uint8Array, not a jsdom `Blob`: MSW's node interceptor needs a body it
    // can stream, and a jsdom Blob has no `.stream()` — the same reason family C
    // hands `taxExportFile` raw bytes.
    return new HttpResponse(ZIP_BYTES, {
      status: 200,
      headers: {
        'Content-Type': 'application/zip',
        'Content-Disposition': 'attachment; filename="trove-export.zip"',
        'X-Content-Type-Options': 'nosniff',
      },
    })
  }),

  http.get('*/v1/me/export/:id', ({ params }) => {
    const run = mswState.dataExports.find((r) => r.id === params.id)
    if (!run) return fail(404, 'not-found', 'Not Found')
    return ok<DataExportRunResponse>(run)
  }),

  /* ------------------------------------------------- admin console */

  http.get('*/v1/admin/users', () => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    return ok<AdminUserResponse[]>(mswState.members)
  }),

  http.delete('*/v1/admin/users/:id/totp', ({ params }) => {
    const refusal = adminActionRefusal(String(params.id))
    if (refusal) return refusal
    mswState.members = mswState.members.map((m) =>
      m.id === params.id ? { ...m, totpEnabled: false } : m
    )
    return okEmpty()
  }),

  http.post('*/v1/admin/users/:id/unlock', ({ params }) => {
    const refusal = adminActionRefusal(String(params.id))
    if (refusal) return refusal
    return okEmpty()
  }),

  http.post('*/v1/admin/users/:id/restore', ({ params }) => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    if (!mswState.members.some((m) => m.id === params.id)) {
      return fail(404, 'not-found', 'Not Found')
    }
    if (mswState.restoreOutsideGrace) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'That account is past its restore window. Invite them again once the purge has run.',
      })
    }
    mswState.members = mswState.members.map((m) =>
      m.id === params.id ? { ...m, active: true } : m
    )
    return okEmpty()
  }),

  http.delete('*/v1/admin/users/:id', ({ params }) => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    if (!mswState.members.some((m) => m.id === params.id)) {
      return fail(404, 'not-found', 'Not Found')
    }
    if (mswState.lastAdminConflict) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This household would be left without an administrator.',
      })
    }
    mswState.members = mswState.members.map((m) =>
      m.id === params.id ? { ...m, active: false } : m
    )
    return okEmpty()
  }),

  /** The household always keeps one active ADMIN: demoting the last one is 409. */
  http.put('*/v1/admin/users/:id/role', async ({ params, request }) => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    const target = mswState.members.find((m) => m.id === params.id)
    if (!target) return fail(404, 'not-found', 'Not Found')
    const body = (await request.json()) as AdminRoleRequest
    mswState.lastMemberRoleBody = body
    const activeAdmins = mswState.members.filter((m) => m.role === 'ADMIN' && m.active).length
    const demotingLast =
      body.role === 'MEMBER' && target.role === 'ADMIN' && target.active && activeAdmins <= 1
    if (mswState.lastAdminConflict || demotingLast) {
      return fail(409, 'conflict', 'Conflict', {
        detail: 'This household would be left without an administrator.',
      })
    }
    const updated = { ...target, role: body.role }
    mswState.members = mswState.members.map((m) => (m.id === params.id ? updated : m))
    return ok<AdminUserResponse>(updated)
  }),

  http.get('*/v1/admin/settings', () => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    return ok<AdminSettingsResponse>(mswState.adminSettings)
  }),

  http.put('*/v1/admin/settings', async ({ request }) => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    const body = (await request.json()) as AdminSettingsRequest
    mswState.lastAdminSettingsBody = body
    // A partial update: an absent or null field leaves the flag alone.
    if (body.registrationOpen != null) {
      mswState.adminSettings = { ...mswState.adminSettings, registrationOpen: body.registrationOpen }
    }
    if (body.scheduledSyncEnabled != null) {
      mswState.adminSettings = {
        ...mswState.adminSettings,
        scheduledSyncEnabled: body.scheduledSyncEnabled,
      }
    }
    return ok<AdminSettingsResponse>(mswState.adminSettings)
  }),

  http.get('*/v1/audit', ({ request }) => {
    if (mswState.me.role !== 'ADMIN') return fail(403, 'forbidden', 'Forbidden')
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 20)
    const rows = mswState.auditEntries.slice(page * limit, page * limit + limit)
    return HttpResponse.json<ApiResponse<AuditLogEntry[]>>({
      success: true,
      data: rows,
      meta: { total: mswState.auditEntries.length, page, limit } satisfies PaginationMeta,
    })
  }),
]

export function resetInvites(): void {
  invites = []
}
