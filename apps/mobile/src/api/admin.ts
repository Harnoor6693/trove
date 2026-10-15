/**
 * Administration — the twelve household-admin operations under
 * `/v1/admin/*`, plus `GET /v1/audit` and the admin-scoped password-reset
 * minting route.
 *
 * **Every route here requires the `ADMIN` role**, and the gate is the
 * server's. A MEMBER who reaches one gets 403 `forbidden`, which is a clean
 * state for the screen to render — the client-side role check that hides the
 * console is cosmetic, never the control.
 *
 * Three refusals this surface makes, which the screens have to say out loud
 * rather than fold into "something went wrong":
 *
 *  - **404, not 403, for another household's row.** Every lookup is scoped by
 *    the caller's own household id from the signed token, so "not yours" and
 *    "does not exist" are the same answer and neither confirms an id is real.
 *  - **409 `conflict` for the household's last `ADMIN`**, on both the admin
 *    delete and the self delete. An install whose only administrator is gone
 *    has no way back in: registration is closed by default, and invites and
 *    the settings flag are themselves admin routes.
 *  - **403 `step-up-required` for a SELF target** on `unlock` and on
 *    `clear TOTP`. "An admin route never acts on the admin's own security
 *    state" is one rule rather than a list of exceptions: `DELETE
 *    /v1/auth/totp` demands the password *and* a live code before it removes a
 *    second factor, so an admin who could clear their own from here would make
 *    the account's strongest control removable by the weakest credential that
 *    reaches it.
 *
 * Password reset lives here rather than in `auth.ts` because it *is* an admin
 * operation: this product has no SMTP on a default install, so reset is an
 * admin minting a one-hour single-use link and handing it over out of band. A
 * public route that minted reset links with no second channel to deliver them
 * through would simply be a reset oracle.
 */

import { api } from './client';
import { pathSegment } from './paths';
import { expectSuccess, unwrap, unwrapPage } from './errors';
import type {
  AdminSettingsRequest,
  AdminSettingsResponse,
  AdminUserResponse,
  ApiResponse,
  AuditLogEntry,
  AuditLogQuery,
  CreateInviteRequest,
  InviteResponse,
  Page,
  PasswordResetLinkRequest,
  PasswordResetLinkResponse,
} from './types';

// --- members ---------------------------------------------------------------

/**
 * `GET /v1/admin/users` — the household's members, oldest first, as a bare
 * array. A member inside the deletion grace is still here with
 * `active: false`; that is how an admin finds them again to restore them.
 */
export async function fetchHouseholdMembers(): Promise<AdminUserResponse[]> {
  const response = await api.get<ApiResponse<AdminUserResponse[]>>('/v1/admin/users');
  return unwrap(response.data);
}

/**
 * `DELETE /v1/admin/users/{id}` — a **soft** delete down the same path
 * `DELETE /v1/me` takes. The member is deactivated and stamped, their sessions
 * are revoked immediately, and they remain listed with `active: false` until
 * the grace period ends. Deleting somebody already inside that window is 409
 * `conflict`, not a second deletion.
 */
export async function deleteHouseholdMember(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/admin/users/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * `POST /v1/admin/users/{id}/restore` — the other half of the soft delete.
 * A member who was never deleted, or whose grace has passed, is 409
 * `conflict`. Their sessions are **not** brought back: they sign in again.
 */
export async function restoreHouseholdMember(id: string): Promise<void> {
  const response = await api.post<ApiResponse<never>>(`/v1/admin/users/${pathSegment(id)}/restore`);
  expectSuccess(response.data);
}

/** `POST /v1/admin/users/{id}/unlock` — clears a sign-in lock. Does not change the password. A self-target is 403 `step-up-required`. */
export async function unlockHouseholdMember(id: string): Promise<void> {
  const response = await api.post<ApiResponse<never>>(`/v1/admin/users/${pathSegment(id)}/unlock`);
  expectSuccess(response.data);
}

/** `DELETE /v1/admin/users/{id}/totp` — the lost-phone escape hatch. A self-target is 403 `step-up-required`. */
export async function clearHouseholdMemberTotp(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/admin/users/${pathSegment(id)}/totp`);
  expectSuccess(response.data);
}

/**
 * `POST /v1/auth/password/reset-request` — mints `{url, expiresAt}` for a
 * member of the calling admin's **own** household; any other address is 404.
 *
 * The link is single-use, lives one hour, and **this response is the only time
 * it is readable**. It is a live credential for somebody else's account: it is
 * never cached, never logged and never persisted by this app.
 */
export async function requestPasswordResetLink(
  body: PasswordResetLinkRequest
): Promise<PasswordResetLinkResponse> {
  const response = await api.post<ApiResponse<PasswordResetLinkResponse>>(
    '/v1/auth/password/reset-request',
    body
  );
  return unwrap(response.data);
}

// --- invitations -----------------------------------------------------------

/**
 * `GET /v1/admin/invites` — newest first, bare array.
 *
 * **Read the link from here, not from the creation response**: `token` and
 * `url` are populated only while an invitation can still be redeemed and go
 * null once it is accepted, revoked or expired. "Still redeemable" is a
 * question about now, so the answer changes with the clock and not only with a
 * write.
 */
export async function fetchInvites(): Promise<InviteResponse[]> {
  const response = await api.get<ApiResponse<InviteResponse[]>>('/v1/admin/invites');
  return unwrap(response.data);
}

/**
 * `POST /v1/admin/invites` — 201 with the token and link. The body is optional
 * and `{}` is the normal case: an invite with no `email` can be redeemed by
 * anyone holding the link, one with an email is bound to that address, and
 * `role` defaults to `MEMBER`. Valid for 72 hours.
 */
export async function createInvite(body: CreateInviteRequest = {}): Promise<InviteResponse> {
  const response = await api.post<ApiResponse<InviteResponse>>('/v1/admin/invites', body);
  return unwrap(response.data);
}

/** `DELETE /v1/admin/invites/{id}` — a revoked invite stops previewing as valid and its `token`/`url` go null. */
export async function revokeInvite(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/admin/invites/${pathSegment(id)}`);
  expectSuccess(response.data);
}

// --- settings --------------------------------------------------------------

/** `GET /v1/admin/settings` — read from the household row, so an admin's runtime decision is what the sign-in page reflects. */
export async function fetchAdminSettings(): Promise<AdminSettingsResponse> {
  const response = await api.get<ApiResponse<AdminSettingsResponse>>('/v1/admin/settings');
  return unwrap(response.data);
}

/** `PUT /v1/admin/settings` — partial; an absent or null `registrationOpen` leaves the flag alone. */
export async function updateAdminSettings(
  body: AdminSettingsRequest
): Promise<AdminSettingsResponse> {
  const response = await api.put<ApiResponse<AdminSettingsResponse>>('/v1/admin/settings', body);
  return unwrap(response.data);
}

// --- audit -----------------------------------------------------------------

/**
 * `GET /v1/audit` — one page of the household's mutating requests, newest
 * first. Paged, so `meta` is required and the page controls and the rows come
 * from the same fetch.
 *
 * `path` is the matched route template, never the caller's raw URI, and no
 * body, header, query string or token is stored — so a row carries nothing a
 * client should ever render as markup.
 */
export async function fetchAuditLog(query: AuditLogQuery = {}): Promise<Page<AuditLogEntry>> {
  const response = await api.get<ApiResponse<AuditLogEntry[]>>('/v1/audit', { params: query });
  return unwrapPage(response.data);
}
