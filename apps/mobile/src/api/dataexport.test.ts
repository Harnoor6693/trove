import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests, setSession } from './client';
import {
  createDataExport,
  fetchDataExport,
  fetchDataExportArchive,
  fetchDataExports,
} from './dataexport';
import type { AuthUser, DataExportRunResponse } from './types';

const BASE = 'https://trove.example.com';

const RUN: DataExportRunResponse = {
  id: 'e1',
  userId: 'u1',
  status: 'COMPLETE',
  fileSizeBytes: 20480,
  errorMessage: null,
  expiresAt: '2026-09-19T14:00:02Z',
  createdAt: '2026-09-18T14:00:00Z',
  updatedAt: '2026-09-18T14:00:02Z',
};

const USER: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  role: 'MEMBER',
  householdId: 'h1',
  totpEnabled: false,
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('createDataExport', () => {
  it('posts the step-up body to /v1/me/export and unwraps the 202 row', async () => {
    reply(() => ({ status: 202, data: { success: true, data: { ...RUN, status: 'PENDING' } } }));
    const run = await createDataExport({ password: 'correct-horse-battery' });
    expect(run.status).toBe('PENDING');
    expect(calls()).toEqual(['POST /v1/me/export']);
    expect(JSON.parse(seen[0].data as string)).toEqual({ password: 'correct-horse-battery' });
  });

  it('accepts a code-only step-up — both fields are optional because the factor set is the account’s', async () => {
    reply(() => ({ status: 202, data: { success: true, data: { ...RUN, status: 'PENDING' } } }));
    await createDataExport({ code: '000000' });
    expect(JSON.parse(seen[0].data as string)).toEqual({ code: '000000' });
  });

  it('surfaces a wrong password as 403 step-up-required rather than a generic failure', async () => {
    replyError(403, 'step-up-required', 'That password or code was not accepted.');
    const failure = await failureOf(createDataExport({ password: 'wrong' }));
    expect(failure.status).toBe(403);
    expect(failure.code).toBe('step-up-required');
  });

  it('surfaces the one-run-per-day cap as 409 conflict, with the server’s own timing', async () => {
    replyError(409, 'conflict', 'You can ask for another export after 2026-09-19.');
    const failure = await failureOf(createDataExport({ password: 'correct-horse-battery' }));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
    // The server says when; the client must not invent a timer of its own.
    expect(failure.message).toMatch(/2026-09-19/);
  });

  it('never puts the credential anywhere but the request body', async () => {
    reply(() => ({ status: 202, data: { success: true, data: RUN } }));
    await createDataExport({ password: 'correct-horse-battery', code: '000000' });
    const request = seen[0];
    expect(JSON.stringify(request.url)).not.toContain('correct-horse-battery');
    expect(JSON.stringify(request.params ?? {})).not.toContain('correct-horse-battery');
    expect(JSON.stringify(request.headers)).not.toContain('correct-horse-battery');
  });
});

describe('fetchDataExports', () => {
  it('is paged — it returns data and meta together', async () => {
    replyOk([RUN], { page: 0, limit: 20, total: 1 });
    const page = await fetchDataExports({ page: 0, limit: 20 });
    expect(page.data).toHaveLength(1);
    expect(page.meta.total).toBe(1);
    expect(seen[0].params).toEqual({ page: 0, limit: 20 });
  });

  it('refuses a 200 that arrived without its pagination metadata', async () => {
    replyOk([RUN]);
    await expect(fetchDataExports()).rejects.toThrow(/pagination metadata/);
  });
});

describe('fetchDataExport', () => {
  it('reads one run by id', async () => {
    replyOk(RUN);
    await expect(fetchDataExport('e1')).resolves.toEqual(RUN);
    expect(calls()).toEqual(['GET /v1/me/export/e1']);
  });

  it('percent-encodes the id rather than letting it walk the path', async () => {
    replyOk(RUN);
    await fetchDataExport('../admin/users');
    expect(calls()).toEqual(['GET /v1/me/export/..%2Fadmin%2Fusers']);
  });

  it('gives 404 for somebody else’s run — never 403, which would confirm the id is real', async () => {
    replyError(404, 'not-found', 'No such export run.');
    const failure = await failureOf(fetchDataExport('e9'));
    expect(failure.status).toBe(404);
  });
});

/** The error envelope as the bytes a binary response really carries. */
function encodedProblem(status: number, slug: string, detail: string): ArrayBuffer {
  const body = JSON.stringify({
    success: false,
    error: {
      type: `https://trove.finance/errors/${slug}`,
      title: slug,
      status,
      detail,
      instance: '/v1/me/export/e1/download',
      requestId: 'req-1',
    },
  });
  return new TextEncoder().encode(body).buffer as ArrayBuffer;
}

describe('fetchDataExportArchive', () => {
  function zip(bytes: number[], headers: Record<string, string>) {
    reply(() => ({ status: 200, data: new Uint8Array(bytes).buffer, headers }));
  }

  it('pulls the archive through `api` with the bearer token in a header and no token in the URL', async () => {
    await setSession(
      { accessToken: 'access-abc', refreshToken: 'refresh-xyz', expiresIn: 900 },
      USER
    );
    zip([80, 75, 3, 4], { 'content-type': 'application/zip', 'content-length': '4' });

    const archive = await fetchDataExportArchive('e1');
    expect(archive).toEqual({
      contentType: 'application/zip',
      byteLength: 4,
      bytes: expect.any(ArrayBuffer),
    });
    // The bytes themselves come back for the share sheet — the exact body.
    // (`toEqual` does not compare ArrayBuffer contents, hence the explicit view.)
    expect(Array.from(new Uint8Array(archive.bytes))).toEqual([80, 75, 3, 4]);

    const request = seen[0];
    expect(request.url).toBe('/v1/me/export/e1/download');
    expect(request.url).not.toContain('access-abc');
    expect(String(request.headers.Authorization)).toBe('Bearer access-abc');
    expect(request.responseType).toBe('arraybuffer');
  });

  it('measures the body when the adapter surfaced no Content-Length', async () => {
    zip([1, 2, 3, 4, 5], { 'content-type': 'application/zip' });
    await expect(fetchDataExportArchive('e1')).resolves.toEqual({
      contentType: 'application/zip',
      byteLength: 5,
      bytes: expect.any(ArrayBuffer),
    });
  });

  it('defaults the type when no header came back at all — an adapter gap, not a wrong body', async () => {
    zip([1, 2], {});
    await expect(fetchDataExportArchive('e1')).resolves.toEqual({
      contentType: 'application/zip',
      byteLength: 2,
      bytes: expect.any(ArrayBuffer),
    });
  });

  it('refuses a body whose Content-Type is not the archive — a captive portal must not read as "ready"', async () => {
    zip([60, 104, 116], { 'content-type': 'text/html' });
    await expect(fetchDataExportArchive('e1')).rejects.toThrow(/could not be read/);
  });

  // The refusal comes back down the BINARY path: `responseType: 'arraybuffer'`
  // makes axios skip its JSON transform for the error body too, so the problem
  // document arrives as raw bytes. A stub that handed back a parsed envelope
  // would prove nothing the real XHR adapter ever does — hence the encoded
  // body here.
  it('surfaces an unfinished or expired run as 409 conflict, which is a "still generating" state', async () => {
    reply(() => ({
      status: 409,
      headers: { 'content-type': 'application/problem+json' },
      data: encodedProblem(409, 'conflict', 'That export is still being generated.'),
    }));
    const failure = await failureOf(fetchDataExportArchive('e1'));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
    expect(failure.message).toBe('That export is still being generated.');
  });

  it('falls back to the generic refusal when the refusal carried no body at all', async () => {
    reply(() => ({ status: 409, data: new ArrayBuffer(0) }));
    const failure = await failureOf(fetchDataExportArchive('e1'), 'Could not fetch that archive.');
    expect(failure.code).toBeNull();
    expect(failure.message).toBe('Could not fetch that archive.');
  });

  it('falls back to the generic refusal when the binary body is not a problem document', async () => {
    reply(() => ({ status: 409, data: new Uint8Array([80, 75, 3, 4]).buffer }));
    const failure = await failureOf(fetchDataExportArchive('e1'), 'Could not fetch that archive.');
    expect(failure.status).toBe(409);
    expect(failure.code).toBeNull();
    expect(failure.message).toBe('Could not fetch that archive.');
  });
});
