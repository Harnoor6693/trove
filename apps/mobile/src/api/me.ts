/**
 * Identity endpoints — §4.1 `GET /v1/auth/me` and §4.2 `PUT /v1/me`.
 *
 * `currency` and `country` are identity fields: set once at registration and
 * not editable afterwards, because every historical transaction, budget and
 * export assumes a stable reporting currency (§4.2). They are read from here,
 * never written.
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import type {
  ApiResponse,
  MeResponse,
  SecurityEvent,
  StepUpRequest,
  UpdateMeRequest,
} from './types';

/** `GET /v1/auth/me` — also the biometric-unlock revalidation call. */
export async function fetchMe(): Promise<MeResponse> {
  const response = await api.get<ApiResponse<MeResponse>>('/v1/auth/me');
  return unwrap(response.data);
}

/** `PUT /v1/me` — the only identity fields a user may change (§4.2). */
export async function updateMe(body: UpdateMeRequest): Promise<MeResponse> {
  const response = await api.put<ApiResponse<MeResponse>>('/v1/me', body);
  return unwrap(response.data);
}

/** `GET /v1/me/security-events` — self-service "recent activity" (gap P2-17). */
export async function fetchSecurityEvents(): Promise<SecurityEvent[]> {
  const response = await api.get<ApiResponse<SecurityEvent[]>>('/v1/me/security-events');
  return unwrap(response.data);
}

/**
 * `POST /v1/me/delete` — delete the caller's own account, under step-up.
 *
 * **The POST twin, not `DELETE /v1/me`.** Both operations exist, with the same
 * body, the same step-up, the same last-`ADMIN` refusal and the same effect,
 * and the contract names the reason: a DELETE carrying a request body is legal
 * but poorly supported, RFC 9110 leaves its semantics undefined, and
 * intermediaries drop the body without saying so. The step-up here needs a
 * password or a code, so a dropped body would turn a deliberate, irreversible
 * decision into an unexplained 403. This is the one that always works — the
 * same pairing the app now uses for `POST /v1/auth/totp/disable`.
 *
 * **What it does**, so a caller can say it before it calls: the account is
 * *soft* deleted — deactivated, stamped, and every session of it revoked
 * immediately, so the very next request with an existing token is refused. An
 * administrator can restore it for a grace period (seven days by default);
 * after that a scheduled job erases the row for good. The **household keeps
 * its shared ledger** — transactions, accounts, categories, rules, budgets,
 * envelopes, goals, net-worth snapshots, assets, liabilities, subscriptions
 * and receipts, images included — with the departed member's name removed from
 * the rows that recorded who entered them. Security-event history is kept with
 * the user id removed, because an incident review must not be erasable by
 * deleting an account. Erased with the member: their password, second factor,
 * identity links, sessions, reset links, AI settings, notification preferences
 * and inbox, webhook subscriptions, coach conversations and insights, their
 * tax-line overrides, and every export run of theirs with its file on disk.
 *
 * **The step-up factor set is resolved from what the account has**, which is
 * why both fields of `StepUpRequest` are optional: a member with a local
 * password proves it (plus a code where TOTP is enrolled); a member who signs
 * in only through an identity provider proves the code alone; a member with
 * neither is refused 403 `step-up-required` and needs an admin-issued reset
 * link first.
 *
 * **Two refusals.** A wrong password or code is 403 `step-up-required` and
 * records a failed-login security event. The household's last `ADMIN` is 409
 * `conflict`: an install whose only administrator is gone has no way back in.
 *
 * `body` is a credential — passed through, never retained here, never logged,
 * never cached.
 */
export async function deleteMyAccount(body: StepUpRequest): Promise<void> {
  const response = await api.post<ApiResponse<never>>('/v1/me/delete', body);
  expectSuccess(response.data);
}
