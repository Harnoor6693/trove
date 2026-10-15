/**
 * The auth-completion half of `src/api/auth.ts` — step 215, family E.
 *
 * The login/register/refresh half is exercised through `client.test.ts` and
 * the sign-in screens; what is proved here is the three things this step
 * changed or added, each of which is a decision rather than a call:
 *
 *  - `disableTotp` goes to the **POST twin**, not `DELETE /v1/auth/totp`.
 *  - `previewInvite` is **always 200** and the verdict is in the body.
 *  - `redeemPasswordResetLink` reads the closed `EmptySuccess` shape and gives
 *    one answer, `reset-invalid`, for every bad token.
 */

import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import { disableTotp, previewInvite, redeemPasswordResetLink } from './auth';

const BASE = 'https://trove.example.com';

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('disableTotp', () => {
  it('uses POST /v1/auth/totp/disable — not the DELETE, whose body an intermediary may drop', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await disableTotp('correct-horse-battery', '000000');
    expect(calls()).toEqual(['POST /v1/auth/totp/disable']);
    expect(JSON.parse(seen[0].data as string)).toEqual({
      password: 'correct-horse-battery',
      code: '000000',
    });
  });

  it('never carries the password or code in the URL, the query or a header', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await disableTotp('correct-horse-battery', '123456');
    const request = seen[0];
    expect(request.url).toBe('/v1/auth/totp/disable');
    expect(JSON.stringify(request.params ?? {})).not.toContain('correct-horse-battery');
    expect(JSON.stringify(request.headers)).not.toContain('correct-horse-battery');
  });

  it('reads a wrong password or code as 403 step-up-required — one answer for either', async () => {
    replyError(403, 'step-up-required', 'That password or code was not accepted.');
    const failure = await failureOf(disableTotp('wrong', '000000'));
    expect(failure.status).toBe(403);
    expect(failure.code).toBe('step-up-required');
  });

  it('does not let the auth route trigger a token refresh', async () => {
    replyError(401, 'unauthorized', 'Not signed in.');
    await failureOf(disableTotp('correct-horse-battery', '000000'));
    // One request, not a refresh-and-retry: `skipAuthRefresh` is set on every
    // call in this module because a 401 from an auth route is the answer.
    expect(calls()).toEqual(['POST /v1/auth/totp/disable']);
  });
});

describe('previewInvite', () => {
  it('reads the verdict out of a 200 body for a usable token', async () => {
    replyOk({ valid: true, email: 'invitee@example.test', expiresAt: '2026-09-21T14:00:00Z' });
    const preview = await previewInvite('aW52aXRlLXRva2Vu');
    expect(preview.valid).toBe(true);
    expect(preview.email).toBe('invitee@example.test');
    expect(calls()).toEqual(['GET /v1/auth/invites/aW52aXRlLXRva2Vu']);
  });

  it('is still a 200 for an unknown, revoked, used or expired token — one answer for all four', async () => {
    replyOk({ valid: false, email: null, expiresAt: null });
    const preview = await previewInvite('not-a-real-token');
    expect(preview).toEqual({ valid: false, email: null, expiresAt: null });
  });

  it('encodes the token rather than letting a pasted value walk the path', async () => {
    replyOk({ valid: false, email: null, expiresAt: null });
    await previewInvite('../../admin/users');
    expect(calls()).toEqual(['GET /v1/auth/invites/..%2F..%2Fadmin%2Fusers']);
  });
});

describe('redeemPasswordResetLink', () => {
  it('posts the token and the new password and reads the closed EmptySuccess shape', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(
      redeemPasswordResetLink({ token: 'cmVzZXQ', password: 'a-long-enough-password' })
    ).resolves.toBeUndefined();
    expect(calls()).toEqual(['POST /v1/auth/password/reset']);
    expect(JSON.parse(seen[0].data as string)).toEqual({
      token: 'cmVzZXQ',
      password: 'a-long-enough-password',
    });
  });

  it('gives one 400 reset-invalid for an unknown, expired or spent token', async () => {
    replyError(400, 'reset-invalid', 'That reset link is no longer usable.');
    const failure = await failureOf(
      redeemPasswordResetLink({ token: 'gone', password: 'a-long-enough-password' })
    );
    expect(failure.status).toBe(400);
    expect(failure.code).toBe('reset-invalid');
  });

  it('surfaces a refused password as password-policy — the link is still usable afterwards', async () => {
    replyError(400, 'password-policy', 'That password was rejected.', {
      fieldErrors: [{ field: 'password', message: 'Use at least 12 characters.' }],
    });
    const failure = await failureOf(
      redeemPasswordResetLink({ token: 'cmVzZXQ', password: 'short' })
    );
    expect(failure.code).toBe('password-policy');
    expect(failure.fieldErrors[0].field).toBe('password');
  });
});
