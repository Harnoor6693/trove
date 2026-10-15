// Imported first: it installs the mock adapter on `axios.defaults`.
import { calls, failureOf, reply, replyError, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { setSession, __resetClientForTests } from './client';
import { fetchImageAsDataUri, toBase64 } from './binary';

const BASE = 'https://trove.example.com';
const USER = {
  id: 'u1',
  email: 'a@b.co',
  displayName: 'A',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('toBase64', () => {
  const encode = (text: string) => toBase64(new TextEncoder().encode(text));

  it('matches the reference encoding at every padding length', () => {
    // Three inputs whose lengths are 0, 1 and 2 mod 3 — the three branches.
    expect(encode('any carnal pleasure.')).toBe('YW55IGNhcm5hbCBwbGVhc3VyZS4=');
    expect(encode('any carnal pleasure')).toBe('YW55IGNhcm5hbCBwbGVhc3VyZQ==');
    expect(encode('any carnal pleasur')).toBe('YW55IGNhcm5hbCBwbGVhc3Vy');
  });

  it('encodes bytes above 0x7f, which a JPEG is full of', () => {
    expect(toBase64(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe('/9j/4A==');
  });

  it('encodes an empty array as an empty string', () => {
    expect(toBase64(new Uint8Array([]))).toBe('');
  });
});

describe('fetchImageAsDataUri', () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);

  it('asks for bytes and builds a data URI from the served content type', async () => {
    reply(() => ({ status: 200, data: jpeg.buffer, headers: { 'content-type': 'image/jpeg' } }));
    await expect(fetchImageAsDataUri('/v1/receipts/r1/thumbnail')).resolves.toBe(
      'data:image/jpeg;base64,/9j/4A=='
    );
    expect(seen[0].responseType).toBe('arraybuffer');
    expect(calls()).toEqual(['GET /v1/receipts/r1/thumbnail']);
  });

  it('carries the credential in a header and NEVER in the URL', async () => {
    await setSession(
      { accessToken: 'access-abc', refreshToken: 'refresh-abc', expiresIn: 900 },
      USER
    );
    reply(() => ({ status: 200, data: jpeg.buffer, headers: { 'content-type': 'image/jpeg' } }));
    await fetchImageAsDataUri('/v1/receipts/r1/download');

    const request = seen[0];
    expect(request.headers?.Authorization).toBe('Bearer access-abc');
    expect(request.url).toBe('/v1/receipts/r1/download');
    expect(`${request.baseURL ?? ''}${request.url ?? ''}`).not.toContain('access-abc');
    expect(request.params).toBeUndefined();
  });

  it('accepts a PNG the route is allowed to serve', async () => {
    reply(() => ({ status: 200, data: jpeg.buffer, headers: { 'content-type': 'image/png' } }));
    await expect(fetchImageAsDataUri('/v1/x')).resolves.toContain('data:image/png;base64,');
  });

  it('refuses to build a data URI for a content type the route may not serve', async () => {
    // `nosniff` is on these responses because a body whose bytes are not the
    // type the header claims is a stored-XSS vector. The client half of that
    // is not honouring a type this route cannot legitimately return — and
    // "not honouring" means refusing, not quietly relabelling it as JPEG,
    // which is what this function used to do while its comment claimed
    // otherwise. Families C and E pull their exports through this same seam.
    reply(() => ({ status: 200, data: jpeg.buffer, headers: { 'content-type': 'text/html' } }));
    const failure = await failureOf(fetchImageAsDataUri('/v1/x'));
    expect(failure.message).toBe('That image could not be read.');
  });

  it('refuses a JSON body that arrived where an image was expected', async () => {
    reply(() => ({
      status: 200,
      data: jpeg.buffer,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    }));
    const failure = await failureOf(fetchImageAsDataUri('/v1/x'));
    expect(failure.message).toBe('That image could not be read.');
  });

  it('honours a charset parameter on a type it does accept', async () => {
    reply(() => ({
      status: 200,
      data: jpeg.buffer,
      headers: { 'content-type': 'IMAGE/JPEG; charset=binary' },
    }));
    await expect(fetchImageAsDataUri('/v1/x')).resolves.toContain('data:image/jpeg;base64,');
  });

  it('defaults to JPEG when the response carries no content type', async () => {
    reply(() => ({ status: 200, data: jpeg.buffer, headers: {} }));
    await expect(fetchImageAsDataUri('/v1/x')).resolves.toContain('data:image/jpeg;base64,');
  });

  it('accepts a typed-array body as well as an ArrayBuffer', async () => {
    reply(() => ({ status: 200, data: jpeg, headers: { 'content-type': 'image/jpeg' } }));
    await expect(fetchImageAsDataUri('/v1/x')).resolves.toBe('data:image/jpeg;base64,/9j/4A==');
  });

  it('rejects an empty body rather than rendering a blank image', async () => {
    reply(() => ({
      status: 200,
      data: new Uint8Array([]).buffer,
      headers: { 'content-type': 'image/jpeg' },
    }));
    const failure = await failureOf(fetchImageAsDataUri('/v1/x'));
    expect(failure.message).toBe('That image could not be read.');
  });

  it('rejects a body that is not bytes at all', async () => {
    reply(() => ({ status: 200, data: { success: true }, headers: {} }));
    const failure = await failureOf(fetchImageAsDataUri('/v1/x'));
    expect(failure.message).toBe('That image could not be read.');
  });

  it("surfaces a 404 for another household's receipt", async () => {
    replyError(404, 'not-found', 'No such receipt.');
    const failure = await failureOf(fetchImageAsDataUri('/v1/receipts/nope/thumbnail'));
    expect(failure.status).toBe(404);
    expect(failure.code).toBe('not-found');
  });
});
