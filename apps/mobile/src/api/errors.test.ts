import { AxiosError, AxiosHeaders } from 'axios';
import { fieldError, isUnauthorized, slugOf, toApiFailure, unwrap } from './errors';
import type { ApiResponse, ErrorDetail } from './types';
import { ErrorSlug } from './types';

/**
 * The error contract, asserted against the shape `trove-core` actually emits.
 *
 * `core/src/main/java/finance/trove/common/dto/ErrorResponse.java` is a record
 * of `{type, title, status, detail, instance, requestId, fieldErrors}` where
 * `type` is `https://trove.finance/errors/<slug>` and `fieldErrors` is a
 * **list** of `{field, message}` pairs. Both of those were modelled wrongly in
 * the first draft of this client, and both are load-bearing: the slug is the
 * only way to tell `credentials-invalid` from `account-locked`, and a map
 * -shaped `fieldErrors` reads `undefined` for every field.
 */

const FALLBACK = 'Something went wrong.';

function errorBody(error: ErrorDetail): ApiResponse<never> {
  return { success: false, error };
}

function axiosFailure(status: number, data: unknown, headers: Record<string, string> = {}) {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, {}, {
    status,
    data,
    statusText: '',
    headers: new AxiosHeaders(headers),
    config: { headers: new AxiosHeaders() },
  } as never);
}

describe('slugOf', () => {
  it('takes the trailing segment of the problem-details URI', () => {
    expect(slugOf('https://trove.finance/errors/credentials-invalid')).toBe('credentials-invalid');
  });

  it('returns a bare slug unchanged', () => {
    expect(slugOf('not-found')).toBe('not-found');
  });

  it.each([undefined, ''])('returns null for %p', (value) => {
    expect(slugOf(value)).toBeNull();
  });
});

describe('toApiFailure', () => {
  it('reads the slug, status, detail and requestId out of the envelope', () => {
    const failure = toApiFailure(
      axiosFailure(
        401,
        errorBody({
          type: 'https://trove.finance/errors/credentials-invalid',
          title: 'Unauthorized',
          status: 401,
          detail: 'Email or password is incorrect.',
          requestId: 'req-7',
        })
      ),
      FALLBACK
    );

    expect(failure.status).toBe(401);
    expect(failure.code).toBe(ErrorSlug.credentialsInvalid);
    expect(failure.message).toBe('Email or password is incorrect.');
    expect(failure.requestId).toBe('req-7');
    expect(failure.fieldErrors).toEqual([]);
  });

  // `credentials-invalid` and `account-locked` share a status, so a screen
  // that branched on status alone could not tell them apart (§4.1 slugs).
  it('distinguishes account-locked from credentials-invalid', () => {
    const locked = toApiFailure(
      axiosFailure(
        429,
        errorBody({
          type: 'https://trove.finance/errors/account-locked',
          title: 'Too Many Requests',
          status: 429,
        })
      ),
      FALLBACK
    );
    expect(locked.code).toBe(ErrorSlug.accountLocked);
  });

  it('keeps fieldErrors as the list of {field, message} the server sends', () => {
    const failure = toApiFailure(
      axiosFailure(
        400,
        errorBody({
          type: 'https://trove.finance/errors/validation-failed',
          title: 'Bad Request',
          status: 400,
          fieldErrors: [
            { field: 'password', message: 'must be at least 12 characters' },
            { field: 'password', message: 'is a commonly used password' },
            { field: 'email', message: 'must be a well-formed email address' },
          ],
        })
      ),
      FALLBACK
    );

    expect(failure.fieldErrors).toHaveLength(3);
    expect(fieldError(failure, 'email')).toBe('must be a well-formed email address');
    // Two failures on one field: the first is shown, the second is not lost.
    expect(fieldError(failure, 'password')).toBe('must be at least 12 characters');
    expect(fieldError(failure, 'displayName')).toBeNull();
  });

  it('falls back to the X-Request-Id header when the body carries no requestId', () => {
    const failure = toApiFailure(
      axiosFailure(500, errorBody({ title: 'Internal Server Error', status: 500 }), {
        'x-request-id': 'req-from-header',
      }),
      FALLBACK
    );
    expect(failure.requestId).toBe('req-from-header');
  });

  it('falls back to title, then to the caller’s message', () => {
    expect(
      toApiFailure(axiosFailure(409, errorBody({ title: 'Conflict', status: 409 })), FALLBACK)
        .message
    ).toBe('Conflict');
    expect(toApiFailure(axiosFailure(500, null), FALLBACK).message).toBe(FALLBACK);
  });

  it('reads a bare problem-details body written without the envelope', () => {
    // Spring's own `/error` entry point, or a reverse proxy, can answer
    // without the `ApiResponse` wrapper.
    const failure = toApiFailure(
      axiosFailure(404, {
        type: 'https://trove.finance/errors/not-found',
        title: 'Not Found',
        status: 404,
        detail: 'No such receipt.',
      }),
      FALLBACK
    );
    expect(failure.code).toBe('not-found');
    expect(failure.message).toBe('No such receipt.');
  });

  it('reports a network failure without inventing a status', () => {
    const offline = new AxiosError('Network Error', 'ERR_NETWORK');
    const failure = toApiFailure(offline, FALLBACK);
    expect(failure.status).toBeNull();
    expect(failure.code).toBeNull();
    expect(failure.message).toMatch(/Could not reach your Trove server/);
  });

  it('passes a plain Error’s message through', () => {
    expect(toApiFailure(new Error('No refresh token'), FALLBACK).message).toBe('No refresh token');
  });

  it('uses the fallback for a thrown non-Error', () => {
    expect(toApiFailure('boom', FALLBACK).message).toBe(FALLBACK);
  });
});

describe('unwrap', () => {
  it('returns the data of a successful envelope', () => {
    expect(unwrap({ success: true, data: { id: 'r1' } })).toEqual({ id: 'r1' });
  });

  it('throws the server’s detail when success is false', () => {
    expect(() =>
      unwrap(errorBody({ title: 'Forbidden', status: 403, detail: 'Admins only.' }))
    ).toThrow('Admins only.');
  });

  it('throws when data is missing even on success — never returns undefined', () => {
    expect(() => unwrap({ success: true } as ApiResponse<unknown>)).toThrow();
  });
});

describe('isUnauthorized', () => {
  it('is true only for a 401 response', () => {
    expect(isUnauthorized(axiosFailure(401, null))).toBe(true);
    expect(isUnauthorized(axiosFailure(403, null))).toBe(false);
    expect(isUnauthorized(new Error('nope'))).toBe(false);
  });
});
