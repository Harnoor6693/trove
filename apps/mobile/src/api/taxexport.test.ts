import { calls, failureOf, reply, replyOk, replyError, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests, setSession } from './client';
import {
  createTaxExportRun,
  deleteTaxExportRun,
  fetchTaxExportRun,
  fetchTaxExportRunBytes,
  fetchTaxExportRunFile,
  fetchTaxExportRuns,
  fetchTaxLines,
  setTaxLine,
} from './taxexport';
import type { TaxExportRunResponse, TaxLineMappingResponse } from './types';

const BASE = 'https://trove.example.com';

const LINE: TaxLineMappingResponse = {
  categoryId: 'c1',
  categorySlug: 'business.software',
  categoryName: 'Software',
  jurisdiction: 'CA',
  lineCode: '8810',
  lineLabel: 'T2125 line 8810 — Office expenses',
  deductible: true,
  overridden: false,
};

const RUN: TaxExportRunResponse = {
  id: 'run1',
  userId: 'u1',
  taxYear: 2025,
  jurisdiction: 'CA',
  format: 'CSV',
  status: 'PENDING',
  fileSizeBytes: null,
  errorMessage: null,
  createdAt: '2026-09-05T14:00:00Z',
  updatedAt: '2026-09-05T14:00:00Z',
};

const META = { total: 3, page: 0, limit: 20 };

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchTaxLines', () => {
  it('unwraps the bare array, no jurisdiction param when omitted', async () => {
    replyOk([LINE]);
    await expect(fetchTaxLines()).resolves.toEqual([LINE]);
    expect(calls()).toEqual(['GET /v1/tax-export/lines']);
    expect(seen[0].params).toBeUndefined();
  });

  it('carries an empty lineCode/lineLabel row rather than dropping the category', async () => {
    const blank: TaxLineMappingResponse = {
      ...LINE,
      lineCode: '',
      lineLabel: '',
      deductible: false,
      overridden: false,
    };
    replyOk([blank]);
    const result = await fetchTaxLines('CA');
    expect(result[0].lineCode).toBe('');
    expect(seen[0].params).toEqual({ jurisdiction: 'CA' });
  });

  it('surfaces a 400 validation-error for an out-of-enum jurisdiction', async () => {
    replyError(400, 'validation-error', 'Rejected.');
    const failure = await failureOf(fetchTaxLines());
    expect(failure.status).toBe(400);
  });
});

describe('setTaxLine', () => {
  it('puts the caller-own override to the encoded category path', async () => {
    replyOk({ ...LINE, lineCode: '9270', overridden: true });
    const result = await setTaxLine('c1', {
      jurisdiction: 'CA',
      lineCode: '9270',
      lineLabel: 'Other',
      deductible: true,
    });
    expect(result.overridden).toBe(true);
    expect(calls()).toEqual(['PUT /v1/tax-export/lines/c1']);
  });

  it('surfaces 404 for a category outside the caller’s reach', async () => {
    replyError(404, 'not-found', 'No such category.');
    const failure = await failureOf(
      setTaxLine('other', { jurisdiction: 'CA', lineCode: '1', lineLabel: 'x', deductible: false })
    );
    expect(failure.status).toBe(404);
  });
});

describe('fetchTaxExportRuns', () => {
  it('unwraps data+meta — a paged operation', async () => {
    replyOk([RUN], META);
    await expect(fetchTaxExportRuns()).resolves.toEqual({ data: [RUN], meta: META });
    expect(calls()).toEqual(['GET /v1/tax-export/runs']);
  });

  it('throws when the server omits meta on a paged route', async () => {
    reply(() => ({ status: 200, data: { success: true, data: [RUN] } }));
    await expect(fetchTaxExportRuns()).rejects.toThrow(/pagination metadata/);
  });

  it('sends page/limit as query params', async () => {
    replyOk([RUN], META);
    await fetchTaxExportRuns({ page: 1, limit: 10 });
    expect(seen[0].params).toEqual({ page: 1, limit: 10 });
  });
});

describe('createTaxExportRun', () => {
  it('posts {taxYear, format} and answers 202 PENDING', async () => {
    reply(() => ({ status: 202, data: { success: true, data: RUN } }));
    const result = await createTaxExportRun({ taxYear: 2025, format: 'CSV' });
    expect(result.status).toBe('PENDING');
    expect(calls()).toEqual(['POST /v1/tax-export/runs']);
  });

  it('surfaces a 400 for a future tax year', async () => {
    replyError(400, 'validation-error', 'taxYear may not be in the future.');
    const failure = await failureOf(createTaxExportRun({ taxYear: 2099, format: 'CSV' }));
    expect(failure.status).toBe(400);
  });
});

describe('fetchTaxExportRun', () => {
  it('gets the encoded run id path', async () => {
    replyOk({ ...RUN, status: 'COMPLETE', fileSizeBytes: 4096 });
    const result = await fetchTaxExportRun('run1');
    expect(result.status).toBe('COMPLETE');
    expect(calls()).toEqual(['GET /v1/tax-export/runs/run1']);
  });
});

describe('deleteTaxExportRun', () => {
  it('deletes and expects EmptySuccess, safe in any status', async () => {
    replyOk(undefined);
    await expect(deleteTaxExportRun('run1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/tax-export/runs/run1']);
  });
});

describe('fetchTaxExportRunFile', () => {
  beforeEach(async () => {
    // A bearer session, so the assertion that the token never reaches the URL
    // is meaningful — there IS a token in play, and it must stay in a header.
    await setSession(
      {
        accessToken: 'secret-access-token',
        refreshToken: 'secret-refresh-token',
        expiresIn: 900,
      },
      {
        id: 'u1',
        email: 'member@example.test',
        displayName: 'Member',
        householdId: 'h1',
        role: 'MEMBER',
        totpEnabled: false,
      }
    );
  });

  it('fetches a CSV run as text through `api`, with the token in the header and never in the URL', async () => {
    reply(() => ({
      status: 200,
      data: 'date,amount,category\n2026-01-01,12.34,Software\n',
      headers: { 'content-type': 'text/csv' },
    }));
    const file = await fetchTaxExportRunFile('run1', 'CSV');
    expect(file.contentType).toBe('text/csv');
    expect(file.text).toContain('Software');
    expect(calls()).toEqual(['GET /v1/tax-export/runs/run1/download']);
    // No token, in any form, anywhere in the request URL or its query params.
    expect(seen[0].url).toBe('/v1/tax-export/runs/run1/download');
    expect(seen[0].url).not.toMatch(/token|secret-access-token/i);
    expect(seen[0].params).toBeUndefined();
    // The credential travels in the header the shared client attaches.
    const headers = seen[0].headers as unknown as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer secret-access-token');
  });

  it('fetches a PDF run as bytes and reports only the size — no text to render', async () => {
    const bytes = new Uint8Array([0x25, 0x50, 0x44, 0x46]).buffer; // "%PDF"
    reply(() => ({ status: 200, data: bytes, headers: { 'content-type': 'application/pdf' } }));
    const file = await fetchTaxExportRunFile('run1', 'PDF');
    expect(file.contentType).toBe('application/pdf');
    expect(file.text).toBeNull();
    expect(file.byteLength).toBe(4);
  });

  it('a run that is not COMPLETE answers 409 conflict — "still generating", not a failure', async () => {
    replyError(409, 'conflict', 'This export is not ready yet.');
    const failure = await failureOf(fetchTaxExportRunFile('run1', 'CSV'));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
  });

  it('refuses a CSV request whose response Content-Type disagrees, rather than rendering it as the export', async () => {
    reply(() => ({
      status: 200,
      data: '<html>captive portal</html>',
      headers: { 'content-type': 'text/html' },
    }));
    await expect(fetchTaxExportRunFile('run1', 'CSV')).rejects.toThrow(
      'That export could not be read.'
    );
  });

  it('refuses a PDF request whose response Content-Type disagrees, rather than reporting a false byte count', async () => {
    const bytes = new Uint8Array([0x3c, 0x68, 0x74, 0x6d, 0x6c]).buffer; // "<html"
    reply(() => ({ status: 200, data: bytes, headers: { 'content-type': 'text/html' } }));
    await expect(fetchTaxExportRunFile('run1', 'PDF')).rejects.toThrow(
      'That export could not be read.'
    );
  });

  it('defaults to the requested type when the adapter does not surface a Content-Type header at all', async () => {
    reply(() => ({ status: 200, data: 'x', headers: {} }));
    const file = await fetchTaxExportRunFile('run1', 'CSV');
    expect(file.contentType).toBe('text/csv');
  });

  it('encodes a path-hostile run id rather than letting it change the route', async () => {
    reply(() => ({ status: 200, data: 'x', headers: { 'content-type': 'text/csv' } }));
    await fetchTaxExportRunFile('../runs/other/download', 'CSV');
    expect(seen[0].url).toBe('/v1/tax-export/runs/..%2Fruns%2Fother%2Fdownload/download');
  });
});

describe('fetchTaxExportRunBytes — the bytes the share sheet gets', () => {
  beforeEach(async () => {
    await setSession(
      { accessToken: 'secret-access-token', refreshToken: 'secret-refresh-token', expiresIn: 900 },
      {
        id: 'u1',
        email: 'member@example.test',
        displayName: 'Member',
        householdId: 'h1',
        role: 'MEMBER',
        totpEnabled: false,
      }
    );
  });

  it('fetches either format as an arraybuffer through `api`, token in the header only', async () => {
    const bytes = new Uint8Array([0x61, 0x2c, 0x62]).buffer;
    reply(() => ({ status: 200, data: bytes, headers: { 'content-type': 'text/csv' } }));
    const out = await fetchTaxExportRunBytes('run1', 'CSV');
    expect(Array.from(new Uint8Array(out))).toEqual([0x61, 0x2c, 0x62]);
    expect(calls()).toEqual(['GET /v1/tax-export/runs/run1/download']);
    expect(seen[0].responseType).toBe('arraybuffer');
    expect(seen[0].url).not.toMatch(/token|secret-access-token/i);
    expect(seen[0].params).toBeUndefined();
    const headers = seen[0].headers as unknown as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer secret-access-token');
  });

  it('refuses a body whose Content-Type is not the format asked for', async () => {
    const bytes = new Uint8Array([0x3c, 0x68]).buffer;
    reply(() => ({ status: 200, data: bytes, headers: { 'content-type': 'text/html' } }));
    await expect(fetchTaxExportRunBytes('run1', 'PDF')).rejects.toThrow(
      'That export could not be read.'
    );
  });

  it('a run that is not COMPLETE answers 409 conflict', async () => {
    replyError(409, 'conflict', 'This export is not ready yet.');
    const failure = await failureOf(fetchTaxExportRunBytes('run1', 'PDF'));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
  });
});
