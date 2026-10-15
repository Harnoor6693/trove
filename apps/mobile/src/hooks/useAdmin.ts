/**
 * React Query bindings for the administration surface.
 *
 * One root key per resource (`['admin', 'users']`, `['admin', 'invites']`,
 * `['admin', 'settings']`, `['admin', 'audit']`), because React Query
 * prefix-matches: invalidating `['admin','users']` reaches every view of the
 * member list, while the audit pager still owns a key nothing else touches.
 *
 * **The reset link is never cached.** `requestPasswordResetLink` is a
 * mutation, and its result is a live single-use credential for somebody else's
 * account; the card that shows it resets the mutation the moment the admin
 * dismisses it, so the link does not sit in the mutation cache for the life of
 * the screen. It is not a query, because a query would refetch it — minting a
 * second link and silently invalidating the one the admin just read out.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  clearHouseholdMemberTotp,
  createInvite,
  deleteHouseholdMember,
  fetchAdminSettings,
  fetchAuditLog,
  fetchHouseholdMembers,
  fetchInvites,
  requestPasswordResetLink,
  restoreHouseholdMember,
  revokeInvite,
  unlockHouseholdMember,
  updateAdminSettings,
} from '@api/admin';
import type {
  AdminSettingsRequest,
  AdminSettingsResponse,
  AdminUserResponse,
  AuditLogEntry,
  AuditLogQuery,
  CreateInviteRequest,
  InviteResponse,
  Page,
  PasswordResetLinkRequest,
  PasswordResetLinkResponse,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const adminKeys = {
  all: () => ['admin'] as const,
  users: () => ['admin', 'users'] as const,
  invites: () => ['admin', 'invites'] as const,
  settings: () => ['admin', 'settings'] as const,
  audit: (query: AuditLogQuery) => ['admin', 'audit', query] as const,
};

/** How many audit rows a page of the pager asks for. */
export const AUDIT_PAGE_SIZE = 20;

// --- members ---------------------------------------------------------------

/** `GET /v1/admin/users`. A 403 `forbidden` here is the server's role gate, and it is a screen state, not a retry. */
export function useHouseholdMembers(enabled = true): UseQueryResult<AdminUserResponse[]> {
  return useQuery({
    queryKey: adminKeys.users(),
    queryFn: fetchHouseholdMembers,
    enabled,
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

function memberAction(
  client: ReturnType<typeof useQueryClient>,
  action: (id: string) => Promise<void>
) {
  return {
    mutationFn: action,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: adminKeys.users() });
    },
  };
}

/** `DELETE /v1/admin/users/{id}` — soft delete. 409 `conflict` for the last admin, or for a member already inside the grace. */
export function useDeleteHouseholdMember(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation(memberAction(client, deleteHouseholdMember));
}

/** `POST /v1/admin/users/{id}/restore` — inside the grace only; 409 `conflict` outside it, or for somebody never deleted. */
export function useRestoreHouseholdMember(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation(memberAction(client, restoreHouseholdMember));
}

/** `POST /v1/admin/users/{id}/unlock`. A SELF target is 403 `step-up-required`. */
export function useUnlockHouseholdMember(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation(memberAction(client, unlockHouseholdMember));
}

/** `DELETE /v1/admin/users/{id}/totp`. A SELF target is 403 `step-up-required`. */
export function useClearHouseholdMemberTotp(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation(memberAction(client, clearHouseholdMemberTotp));
}

/** `POST /v1/auth/password/reset-request` — mints a one-hour single-use link. Not a query: a refetch would mint a second one. */
export function usePasswordResetLink(): UseMutationResult<
  PasswordResetLinkResponse,
  Error,
  PasswordResetLinkRequest
> {
  return useMutation({ mutationFn: requestPasswordResetLink });
}

// --- invitations -----------------------------------------------------------

/** `GET /v1/admin/invites`. The list, not the create response, is where a still-usable link is read from. */
export function useInvites(enabled = true): UseQueryResult<InviteResponse[]> {
  return useQuery({
    queryKey: adminKeys.invites(),
    queryFn: fetchInvites,
    enabled,
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/admin/invites` — `{}` is the normal body. */
export function useCreateInvite(): UseMutationResult<InviteResponse, Error, CreateInviteRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateInviteRequest) => createInvite(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: adminKeys.invites() });
    },
  });
}

/** `DELETE /v1/admin/invites/{id}` — the row stays, with `token` and `url` now null. */
export function useRevokeInvite(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => revokeInvite(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: adminKeys.invites() });
    },
  });
}

// --- settings --------------------------------------------------------------

/** `GET /v1/admin/settings`. */
export function useAdminSettings(enabled = true): UseQueryResult<AdminSettingsResponse> {
  return useQuery({
    queryKey: adminKeys.settings(),
    queryFn: fetchAdminSettings,
    enabled,
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

/** `PUT /v1/admin/settings` — partial; the response is the settings after the write, so it seeds the cache directly. */
export function useUpdateAdminSettings(): UseMutationResult<
  AdminSettingsResponse,
  Error,
  AdminSettingsRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AdminSettingsRequest) => updateAdminSettings(body),
    onSuccess: (settings) => {
      client.setQueryData(adminKeys.settings(), settings);
    },
  });
}

// --- audit -----------------------------------------------------------------

/**
 * `GET /v1/audit`, one page at a time.
 *
 * A pager rather than an infinite list: an audit log is read by jumping around
 * it, and `meta.total` is what tells the reader how much there is — an
 * append-only "load more" hides that. `placeholderData` keeps the previous
 * page on screen while the next one loads so the list does not blink back to
 * its loading state on every page turn.
 */
export function useAuditLog(page: number, enabled = true): UseQueryResult<Page<AuditLogEntry>> {
  const query: AuditLogQuery = { page, limit: AUDIT_PAGE_SIZE };
  return useQuery({
    queryKey: adminKeys.audit(query),
    queryFn: () => fetchAuditLog(query),
    enabled,
    placeholderData: (previous) => previous,
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

/** The number of pages `meta` describes, at least one so an empty log still renders "1 of 1". */
export function auditPageCount(meta: Page<AuditLogEntry>['meta']): number {
  if (meta.limit <= 0) return 1;
  return Math.max(1, Math.ceil(meta.total / meta.limit));
}
