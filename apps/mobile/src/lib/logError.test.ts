import { AxiosError, AxiosHeaders } from 'axios';

// `src/test/setup.tsx` mocks `@lib/logError` for every other suite; this one
// tests the real module, so it opts out.
jest.unmock('@lib/logError');

// eslint-disable-next-line @typescript-eslint/no-var-requires
const { logError } = require('./logError') as typeof import('./logError');

describe('logError', () => {
  let consoleError: jest.SpyInstance<typeof console.error>;

  beforeEach(() => {
    consoleError = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('writes the code and a redacted payload — never the error object itself', () => {
    const headers = new AxiosHeaders();
    headers.set('Authorization', 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.sig');
    const config = { headers, method: 'get', url: '/v1/auth/me' };
    const error = new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config as never, {}, {
      status: 401,
      data: { secret: 'do-not-log-me' },
      statusText: '',
      headers: {},
      config: config as never,
    } as never);

    logError('SESSION_CHECK_FAILED', error);

    expect(consoleError).toHaveBeenCalledTimes(1);
    const [tag, payload] = consoleError.mock.calls[0] as [string, string];
    expect(tag).toBe('[SESSION_CHECK_FAILED]');
    expect(typeof payload).toBe('string');
    expect(payload).not.toMatch(/eyJ/);
    expect(payload).not.toMatch(/do-not-log-me/);
    expect(JSON.parse(payload)).toEqual({
      code: 'SESSION_CHECK_FAILED',
      message: 'Unauthorized',
      status: 401,
      method: 'GET',
      url: '/v1/auth/me',
    });
  });

  it('handles a value that is not an Error at all', () => {
    logError('WEIRD', 'something went wrong');
    const [, payload] = consoleError.mock.calls[0] as [string, string];
    expect(JSON.parse(payload)).toEqual({ code: 'WEIRD', message: 'something went wrong' });
  });
});
