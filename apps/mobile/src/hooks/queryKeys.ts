/**
 * The query keys for step 211's four resources, in one module.
 *
 * They live here rather than beside their hooks because the invalidation graph
 * crosses resources: deleting an account cascades to its transactions, a sync
 * and an import commit both write transactions, and every one of the three
 * changes the dashboard. Each hook file would otherwise have to import the
 * next one's keys, and `useAccounts` ⇄ `useTransactions` would be a cycle.
 *
 * **The shape, which the other four families copy.** One root array per
 * resource (`['accounts']`); `list` for the collection, optionally carrying the
 * filters that make it a distinct query; the bare id for one member
 * (`['accounts', id]`); sub-resources hanging off the member
 * (`['accounts', id, 'sync-logs']`). Invalidating the root therefore reaches
 * every view of that resource — which is what a mutation wants — while a
 * detail screen still owns a key nothing else touches.
 */

import type { TransactionQuery } from '@api/types';

export const accountKeys = {
  all: () => ['accounts'] as const,
  list: () => ['accounts', 'list'] as const,
  detail: (id: string) => ['accounts', id] as const,
  syncLogs: (id: string) => ['accounts', id, 'sync-logs'] as const,
};

export const transactionKeys = {
  all: () => ['transactions'] as const,
  list: (query: TransactionQuery) => ['transactions', 'list', query] as const,
  detail: (id: string) => ['transactions', id] as const,
};

export const importKeys = {
  all: () => ['imports'] as const,
  preview: (id: string) => ['imports', id, 'preview'] as const,
};

export const dashboardKeys = {
  all: () => ['dashboard'] as const,
  today: () => ['dashboard', 'today'] as const,
};

/**
 * Step 212, family B. `categories` covers both the tree (`list`) and the
 * merchant rules (`rules`) — they are two resources on the wire
 * (`/v1/categories` vs `/v1/categories/rules`) but a rule always names a
 * category, so a category edit and a rule edit are invalidated together
 * under one root rather than each owning a sibling module that has to import
 * the other's keys.
 */
export const categoryKeys = {
  all: () => ['categories'] as const,
  list: () => ['categories', 'list'] as const,
  rules: () => ['categories', 'rules'] as const,
};

/**
 * `list` is the household's periods; `current` is the distinct
 * `GET /v1/budgets/current` query (its 404 is a different screen state, not
 * "the same list, filtered"); `detail`/`envelopes` hang off one period. A
 * period write invalidates `all()`, which reaches `current` too, because
 * React Query matches key prefixes — `['budgets']` is a prefix of
 * `['budgets', 'current']`.
 */
export const budgetKeys = {
  all: () => ['budgets'] as const,
  list: () => ['budgets', 'list'] as const,
  current: () => ['budgets', 'current'] as const,
  detail: (id: string) => ['budgets', id] as const,
  envelopes: (id: string) => ['budgets', id, 'envelopes'] as const,
  envelope: (id: string, envelopeId: string) => ['budgets', id, 'envelopes', envelopeId] as const,
};

export const goalKeys = {
  all: () => ['goals'] as const,
  list: () => ['goals', 'list'] as const,
};
