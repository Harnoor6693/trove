/**
 * `/v1/admin/invites` — §4.1, readiness gap P2-7.
 *
 * Registration is closed by default; a household ADMIN mints a single-use,
 * 72-hour invite link and hands the URL over out of band. No SMTP is required, so
 * the response must carry an accept URL the admin can copy.
 *
 * Accepting an invite is not a route of its own: the `/invite/:token` page posts
 * `POST /v1/auth/register` with `inviteToken`, which is what pre-binds the new
 * user's `household_id` (§1.5). The token IS checkable before registration, but
 * not from here: `previewInvite` (`GET /v1/auth/invites/{token}`) is a PUBLIC
 * route, so it sits with the other unauthenticated calls in `api/auth.ts` while
 * every route in this file is admin-scoped. `/invite/:token` calls it before it
 * shows the form. (Until step 205 this comment said no such endpoint existed,
 * which was true of the spec as it stood then.)
 */
import { api, unwrap, unwrapVoid } from './client'
import type { ApiResponse, CreateInviteRequest, Invite } from './types'

export async function listInvites(): Promise<Invite[]> {
  const res = await api.get<ApiResponse<Invite[]>>('/v1/admin/invites')
  return unwrap(res)
}

export async function createInvite(req: CreateInviteRequest = {}): Promise<Invite> {
  const res = await api.post<ApiResponse<Invite>>('/v1/admin/invites', req)
  return unwrap(res)
}

export async function revokeInvite(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<null>>(`/v1/admin/invites/${id}`)
  unwrapVoid(res)
}
