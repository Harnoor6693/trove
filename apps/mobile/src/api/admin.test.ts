import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
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
} from './admin';
import type { AdminUserResponse, AuditLogEntry, InviteResponse } from './types';

const BASE = 'https://trove.example.com';

const MEMBER: AdminUserResponse = {
  id: 'u2',
  email: 'member@example.test',
  displayName: 'Grace',
  role: 'MEMBER',
  active: true,
  totpEnabled: false,
  createdAt: '2026-08-30T09:15:00Z',
};

const LIVE_INVITE: InviteResponse = {
  id: 'c1',
  email: 'invitee@example.test',
  token: 'aW52aXRlLXRva2Vu',
  url: 'https://trove.example.test/invite/aW52aXRlLXRva2Vu',
  expiresAt: '2026-09-21T14:00:00Z',
  createdAt: '2026-09-18T14:00:00Z',
  acceptedAt: null,
  createdByEmail: 'admin@example.test',
};

const AUDIT_ROW: AuditLogEntry = {
  id: 'a1',
  method: 'DELETE',
  path: '/api/v1/admin/users/{id}',
  status: 200,
  userId: 'u1',
  clientIp: '203.0.113.7',
  requestId: 'req-9',
  durationMs: 42,
  at: '2026-09-18T18:30:00Z',
};

/** The closed `EmptySuccess` shape: `{"success": true}` with no `data` key. */
function replyEmptySuccess() {
  reply(() => ({ status: 200, data: { success: true } }));
}

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('members', () => {
  it('lists the household, deleted members included with active:false', async () => {
    replyOk([MEMBER, { ...MEMBER, id: 'u3', active: false }]);
    const members = await fetchHouseholdMembers();
    expect(members.map((m) => m.active)).toEqual([true, false]);
    expect(calls()).toEqual(['GET /v1/admin/users']);
  });

  it('renders a MEMBER’s 403 as the server’s own role refusal, not a retry', async () => {
    replyError(403, 'forbidden', 'Administrator role required.');
    const failure = await failureOf(fetchHouseholdMembers());
    expect(failure.status).toBe(403);
    expect(failure.code).toBe('forbidden');
  });

  it('soft-deletes a member and reads the closed EmptySuccess shape', async () => {
    replyEmptySuccess();
    await expect(deleteHouseholdMember('u2')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/admin/users/u2']);
  });

  it('refuses the household’s last ADMIN with 409 conflict', async () => {
    replyError(409, 'conflict', 'This is the household’s only administrator.');
    const failure = await failureOf(deleteHouseholdMember('u1'));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
  });

  it('restores a member inside the grace', async () => {
    replyEmptySuccess();
    await expect(restoreHouseholdMember('u2')).resolves.toBeUndefined();
    expect(calls()).toEqual(['POST /v1/admin/users/u2/restore']);
  });

  it('refuses a restore outside the grace with 409 conflict', async () => {
    replyError(409, 'conflict', 'That account’s restore window has passed.');
    const failure = await failureOf(restoreHouseholdMember('u2'));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
  });

  it('unlocks a member', async () => {
    replyEmptySuccess();
    await unlockHouseholdMember('u2');
    expect(calls()).toEqual(['POST /v1/admin/users/u2/unlock']);
  });

  it('clears a member’s second factor', async () => {
    replyEmptySuccess();
    await clearHouseholdMemberTotp('u2');
    expect(calls()).toEqual(['DELETE /v1/admin/users/u2/totp']);
  });

  it('refuses a SELF target on an admin security route with 403 step-up-required', async () => {
    replyError(403, 'step-up-required', 'An admin route does not act on your own account.');
    const failure = await failureOf(unlockHouseholdMember('u1'));
    expect(failure.status).toBe(403);
    expect(failure.code).toBe('step-up-required');
  });

  it('gives 404, never 403, for another household’s id', async () => {
    replyError(404, 'not-found', 'No such member.');
    const failure = await failureOf(deleteHouseholdMember('u-other'));
    expect(failure.status).toBe(404);
  });

  it('encodes an id rather than letting it walk the path', async () => {
    replyEmptySuccess();
    await unlockHouseholdMember('../../me/delete');
    expect(calls()).toEqual(['POST /v1/admin/users/..%2F..%2Fme%2Fdelete/unlock']);
  });
});

describe('requestPasswordResetLink', () => {
  it('mints a link for an address in the admin’s own household', async () => {
    replyOk({
      url: 'https://trove.example.test/reset-password/cmVzZXQ',
      expiresAt: '2026-09-18T19:30:00Z',
    });
    const link = await requestPasswordResetLink({ email: 'member@example.test' });
    expect(link.url).toContain('/reset-password/');
    expect(calls()).toEqual(['POST /v1/auth/password/reset-request']);
    expect(JSON.parse(seen[0].data as string)).toEqual({ email: 'member@example.test' });
  });

  it('is 404 for an address in another household', async () => {
    replyError(404, 'not-found', 'No such member.');
    const failure = await failureOf(requestPasswordResetLink({ email: 'nobody@example.test' }));
    expect(failure.status).toBe(404);
  });
});

describe('invitations', () => {
  it('lists invites with a live token and url', async () => {
    replyOk([LIVE_INVITE]);
    const invites = await fetchInvites();
    expect(invites[0].url).toContain('/invite/');
    expect(calls()).toEqual(['GET /v1/admin/invites']);
  });

  it('types a spent invite’s token and url as null rather than a string', async () => {
    const spent: InviteResponse = {
      ...LIVE_INVITE,
      token: null,
      url: null,
      acceptedAt: '2026-09-18T15:00:00Z',
    };
    replyOk([spent]);
    const [invite] = await fetchInvites();
    expect(invite.token).toBeNull();
    expect(invite.url).toBeNull();
  });

  it('sends {} by default — the normal create body', async () => {
    reply(() => ({ status: 201, data: { success: true, data: LIVE_INVITE } }));
    await createInvite();
    expect(calls()).toEqual(['POST /v1/admin/invites']);
    expect(JSON.parse(seen[0].data as string)).toEqual({});
  });

  it('binds an invite to an address and a role when asked to', async () => {
    reply(() => ({ status: 201, data: { success: true, data: LIVE_INVITE } }));
    await createInvite({ email: 'invitee@example.test', role: 'ADMIN' });
    expect(JSON.parse(seen[0].data as string)).toEqual({
      email: 'invitee@example.test',
      role: 'ADMIN',
    });
  });

  it('revokes an invite', async () => {
    replyEmptySuccess();
    await revokeInvite('c1');
    expect(calls()).toEqual(['DELETE /v1/admin/invites/c1']);
  });
});

describe('settings', () => {
  it('reads the registration flag', async () => {
    replyOk({ registrationOpen: false });
    await expect(fetchAdminSettings()).resolves.toEqual({ registrationOpen: false });
    expect(calls()).toEqual(['GET /v1/admin/settings']);
  });

  it('writes it and returns the settings after the write', async () => {
    replyOk({ registrationOpen: true });
    await expect(updateAdminSettings({ registrationOpen: true })).resolves.toEqual({
      registrationOpen: true,
    });
    expect(calls()).toEqual(['PUT /v1/admin/settings']);
    expect(JSON.parse(seen[0].data as string)).toEqual({ registrationOpen: true });
  });
});

describe('audit log', () => {
  it('is paged — rows and totals come from one fetch', async () => {
    replyOk([AUDIT_ROW], { page: 1, limit: 20, total: 137 });
    const page = await fetchAuditLog({ page: 1, limit: 20 });
    expect(page.data[0].path).toBe('/api/v1/admin/users/{id}');
    expect(page.meta.total).toBe(137);
    expect(seen[0].params).toEqual({ page: 1, limit: 20 });
    expect(calls()).toEqual(['GET /v1/audit']);
  });

  it('rejects a page that arrived without its metadata rather than truncating silently', async () => {
    replyOk([AUDIT_ROW]);
    await expect(fetchAuditLog()).rejects.toThrow(/pagination metadata/);
  });

  it('surfaces an out-of-range page as the server’s 400, not an empty list', async () => {
    replyError(400, 'validation-error', 'page must be within range', {
      fieldErrors: [{ field: 'page', message: 'must be within range' }],
    });
    const failure = await failureOf(fetchAuditLog({ page: 99 }));
    expect(failure.status).toBe(400);
    expect(failure.fieldErrors).toEqual([{ field: 'page', message: 'must be within range' }]);
  });
});
