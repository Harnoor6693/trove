/**
 * Human labels for the contract's enums.
 *
 * Every one of these is a total `Record` over the generated union rather than a
 * function with a `default` branch, so if the spec widens an enum,
 * `npm run typecheck` fails here instead of the app rendering a raw
 * `SCREAMING_SNAKE` token at a member.
 *
 * State is *worded* here, never signalled by colour alone: a failed sync says
 * "Failed", a partial one says what was refused, and the pill colour is an
 * extra on top of the word rather than the whole message.
 */

import type {
  AccountSyncStatus,
  AccountType,
  BudgetType,
  ImportRowVerdict,
  MappingSource,
  MatchType,
  ReceiptSource,
  ReceiptStatus,
  SourceKind,
  SyncRunStatus,
  TransactionType,
} from '@api/types';

export const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  CHECKING: 'Chequing',
  SAVINGS: 'Savings',
  CREDIT_CARD: 'Credit card',
  INVESTMENT: 'Investment',
  OTHER: 'Other',
};

/** The order the add-account picker offers, so the list is stable across renders. */
export const ACCOUNT_TYPES: AccountType[] = [
  'CHECKING',
  'SAVINGS',
  'CREDIT_CARD',
  'INVESTMENT',
  'OTHER',
];

export const SOURCE_KIND_LABEL: Record<SourceKind, string> = {
  SIMPLEFIN: 'Bank connection',
  OFX: 'OFX file',
  CSV: 'CSV file',
  MANUAL: 'Manual',
};

/**
 * The source kinds a member can create an account for by hand, in the order
 * the add-account picker offers them.
 *
 * `SIMPLEFIN` is deliberately absent: that row is created by
 * `POST /v1/accounts/exchange-setup-token`, not by hand. `CSV` and `OFX` are
 * here because `POST /v1/accounts` accepts them — the contract calls them "the
 * row that a later credential exchange or file upload attaches itself to" —
 * and because `POST /v1/imports` requires an `accountId` of the file's own
 * kind for a CSV. Without them the import pipeline would have no target
 * account and no way to make one.
 */
export const CREATABLE_SOURCE_KINDS: SourceKind[] = ['MANUAL', 'CSV', 'OFX'];

/** The source kinds a statement file can be imported into. */
export const IMPORTABLE_SOURCE_KINDS: SourceKind[] = ['CSV', 'OFX'];

export const ACCOUNT_SYNC_STATUS_LABEL: Record<AccountSyncStatus, string> = {
  PENDING: 'Never synced',
  SYNCING: 'Syncing now',
  OK: 'Up to date',
  ERROR: 'Sync failed',
};

export const SYNC_RUN_STATUS_LABEL: Record<SyncRunStatus, string> = {
  RUNNING: 'Running',
  SUCCESS: 'Succeeded',
  PARTIAL_FAILURE: 'Partly failed',
  FAILED: 'Failed',
};

export const TRANSACTION_TYPE_LABEL: Record<TransactionType, string> = {
  DEBIT: 'Debit',
  CREDIT: 'Credit',
  TRANSFER: 'Transfer',
  FEE: 'Fee',
  INTEREST: 'Interest',
  REFUND: 'Refund',
  OTHER: 'Other',
};

/** The order the type filter and the manual-entry picker offer. */
export const TRANSACTION_TYPES: TransactionType[] = [
  'DEBIT',
  'CREDIT',
  'TRANSFER',
  'FEE',
  'INTEREST',
  'REFUND',
  'OTHER',
];

export const IMPORT_VERDICT_LABEL: Record<ImportRowVerdict, string> = {
  NEW: 'New',
  DUPLICATE: 'Duplicate',
  FUZZY_DUPLICATE: 'Possible duplicate',
  INVALID: 'Rejected',
};

type PillVariant = 'default' | 'muted' | 'sage' | 'amber' | 'red' | 'blue';

export const ACCOUNT_SYNC_STATUS_VARIANT: Record<AccountSyncStatus, PillVariant> = {
  PENDING: 'muted',
  SYNCING: 'blue',
  OK: 'sage',
  ERROR: 'red',
};

export const SYNC_RUN_STATUS_VARIANT: Record<SyncRunStatus, PillVariant> = {
  RUNNING: 'blue',
  SUCCESS: 'sage',
  PARTIAL_FAILURE: 'amber',
  FAILED: 'red',
};

export const IMPORT_VERDICT_VARIANT: Record<ImportRowVerdict, PillVariant> = {
  NEW: 'sage',
  DUPLICATE: 'muted',
  FUZZY_DUPLICATE: 'amber',
  INVALID: 'red',
};

/**
 * Which transaction types are money leaving the household.
 *
 * `amount` is always the absolute value and `type` carries the direction, so a
 * screen that wants a sign has to ask this rather than look for a minus sign
 * that is never there.
 */
export function isOutflow(type: TransactionType): boolean {
  return type === 'DEBIT' || type === 'FEE';
}

// ---------------------------------------------------------------------------
// Receipts (step 214)
// ---------------------------------------------------------------------------

/**
 * The pipeline's stages, worded.
 *
 * A total `Record` over `ReceiptStatus` like every map above, so a widened
 * enum is a type error here rather than a raw `TAX_SPLIT` shown to a member.
 *
 * **`COMPLETE` does not mean "nothing to do".** Whether a human should look at
 * the fields is `reviewRequired`, a separate boolean, and `COMPLETE` with
 * `reviewRequired: true` is the ordinary outcome on an install with no vision
 * model — the image is stored and the fields are the member's to fill in. That
 * is why there is no `REVIEW_REQUIRED` entry here: the server has no such
 * status, whatever this app used to think.
 */
export const RECEIPT_STATUS_LABEL: Record<ReceiptStatus, string> = {
  UPLOADED: 'Uploaded',
  VALIDATING: 'Checking the image',
  PARSING: 'Reading the text',
  EXTRACTING: 'Pulling out the fields',
  MATCHING: 'Looking for a transaction',
  TAX_SPLIT: 'Working out the tax',
  SCORING: 'Scoring the result',
  COMPLETE: 'Complete',
  FAILED: 'Failed',
};

export const RECEIPT_STATUS_VARIANT: Record<ReceiptStatus, PillVariant> = {
  UPLOADED: 'muted',
  VALIDATING: 'blue',
  PARSING: 'blue',
  EXTRACTING: 'blue',
  MATCHING: 'blue',
  TAX_SPLIT: 'blue',
  SCORING: 'blue',
  COMPLETE: 'sage',
  FAILED: 'red',
};

/** How the image reached the household. */
export const RECEIPT_SOURCE_LABEL: Record<ReceiptSource, string> = {
  UPLOAD: 'Uploaded file',
  SCAN: 'Phone camera',
};

// ---------------------------------------------------------------------------
// Categories, merchant rules, budgets, envelopes, goals (step 212)
// ---------------------------------------------------------------------------

export const BUDGET_TYPE_LABEL: Record<BudgetType, string> = {
  FLEXIBLE: 'Flexible',
  ZERO_BASED: 'Zero-based',
  ROLLOVER: 'Rollover',
};

/**
 * The order the budget-type picker offers. `FLEXIBLE` first — it is the
 * server's own default when the field is omitted or null.
 */
export const BUDGET_TYPES: BudgetType[] = ['FLEXIBLE', 'ZERO_BASED', 'ROLLOVER'];

/** One line under the picker, so "zero-based" reads as a rule rather than a label. */
export const BUDGET_TYPE_HINT: Record<BudgetType, string> = {
  FLEXIBLE: 'Envelopes can add up to more or less than your income.',
  ZERO_BASED: 'Every dollar of income must be allocated to an envelope.',
  ROLLOVER: 'Unspent envelope balances carry into next month.',
};

export const MATCH_TYPE_LABEL: Record<MatchType, string> = {
  EXACT: 'Exact match',
  PREFIX: 'Starts with',
};

/** The order the rule form's match-type picker offers. */
export const MATCH_TYPES: MatchType[] = ['EXACT', 'PREFIX'];

export const MAPPING_SOURCE_LABEL: Record<MappingSource, string> = {
  RULE: 'Your rule',
  USER: 'Learned from a correction',
  AI: 'AI categorised',
};
