/**
 * `/v1/admin/*` and `/v1/audit` — the household admin console (§4.2, step 205).
 *
 * Every operation here declares `x-trove-required-role: ADMIN`. The client-side
 * role gate is COSMETIC: the server refuses regardless, and a MEMBER who reaches
 * an admin screen must see a clean 403 rather than a broken page.
 *
 * Two facts the shapes cannot express, both from the operations' own prose:
 *  - `deleteHouseholdMember` is the SAME soft delete `POST /v1/me/delete` takes —
 *    one guard, one lock, one event — so the two cannot drift. A member of
 *    another household is 404 (never 403), and the household's last `ADMIN` is
 *    409 `conflict`.
 *  - `unlockHouseholdMember` and `clearHouseholdMemberTotp` refuse a SELF-target
 *    with 403 `step-up-required`, so that "an admin route never acts on the
 *    admin's own security state" is one rule rather than a list of exceptions.
 *
 * The invitation routes live in `./invites.ts`, where they already shipped.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  AdminSettingsRequest,
  AdminSettingsResponse,
  AdminUserResponse,
  ApiResponse,
  AuditLogEntry,
  AuditLogQuery,
  Page,
  PaginationMeta,
} from './types'

/** `GET /v1/admin/users` — the household's members, oldest first. Bare array; not paged. */
export async function listHouseholdMembers(): Promise<AdminUserResponse[]> {
  const res = await api.get<ApiResponse<AdminUserResponse[]>>('/v1/admin/users')
  return unwrap(res)
}

/**
 * `DELETE /v1/admin/users/{id}` — the soft delete. The member stays listed with
 * `active: false` until the grace ends, which is how an admin finds them again.
 * Deleting somebody already inside that window is 409, not a second deletion.
 */
export async function deleteHouseholdMember(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/admin/users/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}

/**
 * `POST /v1/admin/users/{id}/restore` — the other half of the soft delete.
 * A member who was never deleted, or whose grace has passed, is 409 `conflict`.
 * Their sessions are NOT brought back: they were revoked and cannot be un-revoked.
 */
export async function restoreHouseholdMember(id: string): Promise<void> {
  const res = await api.post<ApiResponse<never>>(
    `/v1/admin/users/${encodeURIComponent(id)}/restore`
  )
  unwrapVoid(res)
}

/**
 * `POST /v1/admin/users/{id}/unlock` — clears the long lock repeated sign-in
 * failures produce. It does NOT change the password. A self-target is 403
 * `step-up-required`: an admin locked out of their own account uses the server
 * console, which needs shell access — a credential a stolen session does not confer.
 */
export async function unlockHouseholdMember(id: string): Promise<void> {
  const res = await api.post<ApiResponse<never>>(`/v1/admin/users/${encodeURIComponent(id)}/unlock`)
  unwrapVoid(res)
}

/**
 * `DELETE /v1/admin/users/{id}/totp` — the lost-phone escape hatch. Records the
 * removal against the admin as actor and the member as subject. A self-target is
 * 403 `step-up-required`: `POST /v1/auth/totp/disable` demands the password AND
 * a live code, so an admin who could clear their own factor from here would need
 * neither, and the strongest control on the account would be removable by the
 * weakest credential that reaches it.
 */
export async function clearHouseholdMemberTotp(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(
    `/v1/admin/users/${encodeURIComponent(id)}/totp`
  )
  unwrapVoid(res)
}

/** `GET /v1/admin/settings` — read off the household row, not the boot-time environment. */
export async function getAdminSettings(): Promise<AdminSettingsResponse> {
  const res = await api.get<ApiResponse<AdminSettingsResponse>>('/v1/admin/settings')
  return unwrap(res)
}

/** `PUT /v1/admin/settings` — a partial update; an absent or null field leaves the flag alone. */
export async function updateAdminSettings(
  body: AdminSettingsRequest
): Promise<AdminSettingsResponse> {
  const res = await api.put<ApiResponse<AdminSettingsResponse>>('/v1/admin/settings', body)
  return unwrap(res)
}

/**
 * `GET /v1/audit?page&limit` — one row per MUTATING request the install handled,
 * newest first. PAGED. Reads are not recorded.
 *
 * `path` is the matched route template (`/api/v1/admin/users/{id}`), never the
 * caller's raw URI: two routes on this API carry a live credential in the path,
 * and a table of URIs would be a permanent plaintext copy of every invitation
 * token ever issued.
 */
export async function listAuditLog(q: AuditLogQuery = {}): Promise<Page<AuditLogEntry>> {
  const params: Record<string, number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<AuditLogEntry[]>>('/v1/audit', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) throw new Error('GET /v1/audit answered without the required pagination meta')
  return { rows, meta: meta as PaginationMeta }
}
