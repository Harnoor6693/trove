/**
 * `/v1/me` — §4.2. Identity fields the user may change after registration.
 *
 * `currency` and `country` are deliberately absent: they are set once at
 * registration and never edited, because every historical transaction, budget and
 * export assumes a stable reporting currency (§4.2).
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  MeResponse,
  SecurityEventView,
  StepUpRequest,
  UpdateProfileRequest,
} from './types'

/** `PUT /v1/me` — `{displayName, timezone}`. */
export async function updateProfile(req: UpdateProfileRequest): Promise<MeResponse> {
  const res = await api.put<ApiResponse<MeResponse>>('/v1/me', req)
  return unwrap(res)
}

/* ==========================================================================
 * Family E (step 205) — the caller's own security history and self-deletion.
 * The data-export runs live in `./dataexport.ts`.
 * ======================================================================== */

/**
 * `GET /v1/me/security-events` — the caller's own recent security activity,
 * newest first, capped server-side. Scoped to the principal, never to a path
 * variable: somebody who suspects their password is known needs to see where
 * their account has been signed in from without asking an admin — and an admin
 * is exactly the person a household member might not want to ask.
 *
 * `data` is a bare array; `meta` is absent because this list is bounded, not
 * paged. `type` is a plain string, not an enum — an unknown kind must render.
 */
export async function getMySecurityEvents(): Promise<SecurityEventView[]> {
  const res = await api.get<ApiResponse<SecurityEventView[]>>('/v1/me/security-events')
  return unwrap(res)
}

/**
 * `POST /v1/me/delete` — delete the caller's own account, under step-up.
 *
 * The POST twin of `DELETE /v1/me`, and deliberately: a DELETE that carries a
 * request body is legal but poorly supported, and an intermediary that silently
 * drops this body turns a deliberate, irreversible decision into an unexplained
 * 403. The spec's own words: "**This is the one that always works.**"
 *
 * A soft delete — the account is deactivated and stamped, every session revoked
 * immediately, and an administrator can restore it for a seven-day grace before
 * a scheduled job erases the row. The household's last `ADMIN` is refused 409
 * `conflict`. Answers the closed `EmptySuccess`.
 *
 * The `StepUpRequest` passed here is never logged, cached or retained.
 */
export async function deleteMyAccount(body: StepUpRequest): Promise<void> {
  const res = await api.post<ApiResponse<never>>('/v1/me/delete', body)
  unwrapVoid(res)
}
