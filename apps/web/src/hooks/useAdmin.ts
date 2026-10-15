/**
 * The household admin console — members, settings and the audit log
 * (§4.2, step 205).
 *
 * Every query here is `enabled`-gated on the caller's role from the server's own
 * `me` response, purely to avoid a pointless 403 round trip on a member's
 * Settings page. **The gate is cosmetic**: the server refuses regardless, and a
 * MEMBER who reaches an admin surface must see a clean 403 state, which is why
 * `retry: false` is set — a 403 is not a transient failure to retry into.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  clearHouseholdMemberTotp,
  deleteHouseholdMember,
  getAdminSettings,
  listAuditLog,
  listHouseholdMembers,
  restoreHouseholdMember,
  unlockHouseholdMember,
  updateAdminSettings,
} from '@api/admin'
import type { ApiError } from '@api/client'
import type {
  AdminSettingsRequest,
  AdminSettingsResponse,
  AdminUserResponse,
  AuditLogEntry,
  AuditLogQuery,
  Page,
} from '@api/types'

export const adminKey = ['admin'] as const
export const householdMembersKey = ['admin', 'users'] as const
export const adminSettingsKey = ['admin', 'settings'] as const
export const auditLogKey = (q: AuditLogQuery) => ['admin', 'audit', q] as const

/** `GET /v1/admin/users` — oldest first, inactive members included. */
export function useHouseholdMembers(enabled: boolean) {
  return useQuery<AdminUserResponse[], ApiError>({
    queryKey: householdMembersKey,
    queryFn: listHouseholdMembers,
    enabled,
    retry: false,
  })
}

/** One factory for the four member actions — they differ only in the call they make. */
function useMemberAction(action: (id: string) => Promise<void>) {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: action,
    onSuccess: () => void qc.invalidateQueries({ queryKey: householdMembersKey }),
  })
}

/** `DELETE /v1/admin/users/{id}` — soft delete. 409 on the last admin, 404 on another household. */
export const useDeleteMemberMutation = () => useMemberAction(deleteHouseholdMember)

/** `POST /v1/admin/users/{id}/restore` — inside the grace only; 409 outside it. */
export const useRestoreMemberMutation = () => useMemberAction(restoreHouseholdMember)

/** `POST /v1/admin/users/{id}/unlock` — 403 `step-up-required` on a self-target. */
export const useUnlockMemberMutation = () => useMemberAction(unlockHouseholdMember)

/** `DELETE /v1/admin/users/{id}/totp` — 403 `step-up-required` on a self-target. */
export const useClearMemberTotpMutation = () => useMemberAction(clearHouseholdMemberTotp)

/** `GET /v1/admin/settings`. */
export function useAdminSettings(enabled: boolean) {
  return useQuery<AdminSettingsResponse, ApiError>({
    queryKey: adminSettingsKey,
    queryFn: getAdminSettings,
    enabled,
    retry: false,
  })
}

/** `PUT /v1/admin/settings` — partial; an absent field leaves the flag alone. */
export function useUpdateAdminSettingsMutation() {
  const qc = useQueryClient()
  return useMutation<AdminSettingsResponse, ApiError, AdminSettingsRequest>({
    mutationFn: updateAdminSettings,
    onSuccess: (settings) => qc.setQueryData(adminSettingsKey, settings),
  })
}

/** `GET /v1/audit?page&limit` — paged, newest first, mutations only. */
export function useAuditLog(q: AuditLogQuery, enabled: boolean) {
  return useQuery<Page<AuditLogEntry>, ApiError>({
    queryKey: auditLogKey(q),
    queryFn: () => listAuditLog(q),
    enabled,
    retry: false,
    placeholderData: keepPreviousData,
  })
}
