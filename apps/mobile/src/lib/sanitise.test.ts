import { AxiosError, AxiosHeaders } from 'axios';
import { redactSecrets, sanitise } from './sanitise';

/**
 * The rule readiness gap P4-20 states: an `AxiosError` carrying an
 * `Authorization` header must never reach a log sink. Release builds keep
 * console output in logcat / os_log, where any app with log access can read
 * it.
 */
function axiosErrorWithToken(): AxiosError {
  const headers = new AxiosHeaders();
  headers.set('Authorization', 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig');
  headers.set('Cookie', '__Host-trove_refresh=super-secret');
  const config = {
    headers,
    method: 'post',
    url: '/v1/auth/login?token=leaked',
    baseURL: 'https://trove.example.com/api',
    data: JSON.stringify({ email: 'a@b.co', password: 'hunter2hunter2' }),
  };
  return new AxiosError(
    'Request failed with status code 401',
    'ERR_BAD_REQUEST',
    config as never,
    {},
    {
      status: 401,
      data: { success: false, error: { status: 401, detail: 'Unauthorized' } },
      statusText: '',
      headers: {},
      config: config as never,
    } as never
  );
}

describe('sanitise', () => {
  it('keeps only code, message, status, method and path', () => {
    const result = sanitise('LOGIN_FAILED', axiosErrorWithToken());
    expect(Object.keys(result).sort()).toEqual(['code', 'message', 'method', 'status', 'url']);
    expect(result.status).toBe(401);
    expect(result.method).toBe('POST');
  });

  it('never lets an Authorization header or a token reach the output', () => {
    const serialised = JSON.stringify(sanitise('LOGIN_FAILED', axiosErrorWithToken()));
    expect(serialised).not.toMatch(/eyJ/);
    expect(serialised).not.toMatch(/Bearer /);
    expect(serialised).not.toMatch(/super-secret/);
    expect(serialised).not.toMatch(/hunter2/);
    expect(serialised).not.toMatch(/leaked/);
  });

  it('strips the query string, which can carry tokens or search terms', () => {
    const result = sanitise('X', axiosErrorWithToken());
    expect(result.url).toBe('/v1/auth/login');
  });

  it('does not return, reference or spread the original error object', () => {
    const original = axiosErrorWithToken();
    const result = sanitise('X', original) as unknown as Record<string, unknown>;
    expect(result).not.toBe(original);
    expect(result.config).toBeUndefined();
    expect(result.response).toBeUndefined();
    expect(result.request).toBeUndefined();
  });

  it.each([
    [undefined, 'Unknown error'],
    [null, 'Unknown error'],
    ['plain string failure', 'plain string failure'],
  ])('handles a non-Error value %p', (input, expected) => {
    expect(sanitise('X', input).message).toBe(expected);
  });

  it('handles a plain Error', () => {
    expect(sanitise('X', new Error('boom'))).toEqual({ code: 'X', message: 'boom' });
  });
});

describe('redactSecrets', () => {
  it.each([
    ['Authorization: Bearer abc.def.ghi', 'abc.def.ghi'],
    ['token=abcdef123456', 'abcdef123456'], // gitleaks:allow
    ['{"password":"hunter2hunter2"}', 'hunter2hunter2'],
    ['refreshToken: rt_123456', 'rt_123456'],
    ['Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig', 'eyJhbGciOiJIUzI1NiJ9'],
  ])('redacts the secret in %p', (input, secret) => {
    const redacted = redactSecrets(input);
    expect(redacted).not.toContain(secret);
    expect(redacted).toMatch(/\[redacted/);
  });

  it('leaves ordinary text alone', () => {
    expect(redactSecrets('Network request failed')).toBe('Network request failed');
  });
});
