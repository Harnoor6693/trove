import { calls, failureOf, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import { deleteMyAccount, fetchMe, fetchSecurityEvents, updateMe } from './me';
import type { MeResponse, SecurityEvent, StepUpRequest } from './types';

/**
 * `/v1/me` — the caller's own account.
 *
 * The part that earns a suite of its own is `deleteMyAccount`. It is the one
 * irreversible thing a member can do from this app, it takes a credential, and
 * two of its properties are easy to get subtly wrong in a way no screen test
 * would catch: the **verb** and where the body travels.
 */

const BASE = 'https://trove.example.com';

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: true,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Halifax',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const EVENT: SecurityEvent = {
  id: 's1',
  type: 'LOGIN_SUCCEEDED',
  at: '2026-09-18T14:00:00Z',
  ip: '203.0.113.7',
  userAgent: 'Trove/1.0.0 (iPhone)',
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchMe', () => {
  it('unwraps the envelope from GET /v1/auth/me', async () => {
    replyOk(ME);
    await expect(fetchMe()).resolves.toEqual(ME);
    expect(calls()).toEqual(['GET /v1/auth/me']);
  });
});

describe('updateMe', () => {
  it('patches only what it was given', async () => {
    replyOk({ ...ME, displayName: 'Ada L' });
    await updateMe({ displayName: 'Ada L' });
    expect(JSON.parse(seen[0].data as string)).toEqual({ displayName: 'Ada L' });
  });
});

describe('fetchSecurityEvents', () => {
  // Declares no `meta`, so it is a bare list and not a page — reading it as
  // one would hand the screen `undefined` rows.
  it('reads the bare array GET /v1/me/security-events answers', async () => {
    replyOk([EVENT]);
    await expect(fetchSecurityEvents()).resolves.toEqual([EVENT]);
    expect(calls()).toEqual(['GET /v1/me/security-events']);
  });
});

describe('deleteMyAccount', () => {
  // A DELETE carrying a request body is legal but poorly supported: RFC 9110
  // leaves its semantics undefined and intermediaries drop the body without
  // saying so. The step-up here *is* the body, so a dropped one would turn a
  // deliberate, irreversible decision into an unexplained 403. The contract
  // ships the POST twin for exactly this, the same way it twins
  // `DELETE /v1/auth/totp`.
  it('uses POST /v1/me/delete, not the DELETE whose body an intermediary may drop', async () => {
    replyOk(undefined);
    await deleteMyAccount({ password: 'correct-horse-battery' });
    expect(calls()).toEqual(['POST /v1/me/delete']);
    expect(seen[0].method?.toUpperCase()).toBe('POST');
  });

  it('carries the step-up in the body and never in the URL, the query or a header', async () => {
    replyOk(undefined);
    await deleteMyAccount({ password: 'correct-horse-battery', code: '000000' });

    const request = seen[0];
    expect(JSON.parse(request.data as string)).toEqual({
      password: 'correct-horse-battery',
      code: '000000',
    });
    expect(request.url).toBe('/v1/me/delete');
    expect(JSON.stringify(request.params ?? {})).not.toContain('correct-horse-battery');
    expect(JSON.stringify(request.headers)).not.toContain('correct-horse-battery');
    expect(JSON.stringify(request.headers)).not.toContain('000000');
  });

  // Both fields are optional because the factor set is resolved from what the
  // account actually has: a member who signs in only through an identity
  // provider proves the code alone.
  it('accepts a code-only step-up and sends no empty password key', async () => {
    replyOk(undefined);
    const body: StepUpRequest = { code: '000000' };
    await deleteMyAccount(body);
    expect(JSON.parse(seen[0].data as string)).toEqual({ code: '000000' });
  });

  // `EmptySuccess` is the closed shape — literally `{"success":true}` with no
  // `data` key. Reading `data` off one is how a value-less operation starts
  // throwing on its own success.
  it('reads the closed EmptySuccess shape rather than looking for data', async () => {
    replyOk(undefined);
    await expect(deleteMyAccount({ password: 'x' })).resolves.toBeUndefined();
  });

  it('surfaces a wrong password or code as 403 step-up-required', async () => {
    replyError(403, 'step-up-required', 'That password or code was not accepted.');
    const failure = await failureOf(deleteMyAccount({ password: 'wrong' }));
    expect(failure.status).toBe(403);
    expect(failure.code).toBe('step-up-required');
  });

  // An install whose only administrator is gone has no way back in:
  // registration is closed by default, and invites and the settings flag are
  // themselves admin routes.
  it('surfaces the household’s last admin as 409 conflict, not as a generic failure', async () => {
    replyError(409, 'conflict', 'You are the last administrator of this household.');
    const failure = await failureOf(deleteMyAccount({ password: 'correct-horse-battery' }));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
    expect(failure.message).toContain('last administrator');
  });

  // A 429 is a wait-and-retry state with a real number on it. It must not be
  // read as a refused credential, and it must never end the session.
  it('keeps Retry-After off a 429 instead of flattening it into a failure', async () => {
    replyError(429, 'rate-limited', 'Too many requests.', { retryAfter: 30 });
    const failure = await failureOf(deleteMyAccount({ password: 'correct-horse-battery' }));
    expect(failure.status).toBe(429);
    expect(failure.retryAfterSeconds).toBe(30);
  });
});
