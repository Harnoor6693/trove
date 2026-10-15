import { calls, failureOf, replyOk, replyError, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import {
  createAsset,
  createLiability,
  deleteAsset,
  deleteLiability,
  fetchAssets,
  fetchLiabilities,
  fetchNetWorthSeries,
  takeNetWorthSnapshot,
  updateAsset,
  updateLiability,
} from './networth';
import type { AssetResponse, LiabilityResponse, NetWorthSeriesResponse } from './types';

const BASE = 'https://trove.example.com';

const ASSET: AssetResponse = {
  id: 'a1',
  householdId: 'h1',
  createdBy: 'u1',
  name: 'Everyday Chequing',
  assetType: 'CASH',
  value: '5230.5000',
  currency: 'CAD',
  accountId: null,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

const LIABILITY: LiabilityResponse = {
  id: 'l1',
  householdId: 'h1',
  createdBy: 'u1',
  name: 'Visa Credit Card',
  liabilityType: 'CREDIT_CARD',
  balance: '1250.0000',
  interestRate: '19.9900',
  currency: 'CAD',
  accountId: null,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

const SERIES: NetWorthSeriesResponse = {
  primaryCurrency: 'CAD',
  series: [
    {
      currency: 'CAD',
      points: [
        {
          snapshotDate: '2026-09-04',
          totalAssets: '5230.5000',
          totalLiabilities: '1250.0000',
          netWorth: '3980.5000',
        },
      ],
    },
    {
      currency: 'USD',
      points: [
        {
          snapshotDate: '2026-09-04',
          totalAssets: '900.0000',
          totalLiabilities: '0.0000',
          netWorth: '900.0000',
        },
      ],
    },
  ],
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchNetWorthSeries', () => {
  it('returns one series per currency present — never a blended total', async () => {
    replyOk(SERIES);
    const result = await fetchNetWorthSeries();
    expect(result.series).toHaveLength(2);
    expect(result.series.map((s) => s.currency)).toEqual(['CAD', 'USD']);
    // The absence of any blended/combined figure: nothing in the response
    // shape carries a total across currencies at all.
    expect(result).not.toHaveProperty('totalNetWorth');
    expect(calls()).toEqual(['GET /v1/networth/series']);
  });

  it('sends the range as a query param when given', async () => {
    replyOk(SERIES);
    await fetchNetWorthSeries('1Y');
    expect(seen[0].params).toEqual({ range: '1Y' });
  });

  it('surfaces a 400 validation-error for an out-of-enum range', async () => {
    replyError(400, 'validation-error', 'Rejected.');
    const failure = await failureOf(fetchNetWorthSeries());
    expect(failure.status).toBe(400);
  });
});

describe('takeNetWorthSnapshot', () => {
  it('posts with no body and returns one row per currency', async () => {
    replyOk([
      {
        id: 's1',
        householdId: 'h1',
        createdBy: 'u1',
        snapshotDate: '2026-09-04',
        totalAssets: '5230.5000',
        totalLiabilities: '1250.0000',
        netWorth: '3980.5000',
        currency: 'CAD',
        createdAt: '2026-09-04T23:30:00Z',
      },
    ]);
    const result = await takeNetWorthSnapshot();
    expect(result).toHaveLength(1);
    expect(calls()).toEqual(['POST /v1/networth/snapshot']);
  });

  it('surfaces a refused CSRF header as 403', async () => {
    replyError(403, 'forbidden', 'CSRF header missing.');
    const failure = await failureOf(takeNetWorthSnapshot());
    expect(failure.status).toBe(403);
  });
});

describe('assets', () => {
  it('fetchAssets unwraps the bare array', async () => {
    replyOk([ASSET]);
    await expect(fetchAssets()).resolves.toEqual([ASSET]);
    expect(calls()).toEqual(['GET /v1/networth/assets']);
  });

  it('createAsset posts and returns the created row', async () => {
    replyOk(ASSET);
    const result = await createAsset({
      name: 'Everyday Chequing',
      assetType: 'CASH',
      value: '5230.50',
    });
    expect(result).toEqual(ASSET);
    expect(calls()).toEqual(['POST /v1/networth/assets']);
  });

  it('updateAsset puts to the encoded id path', async () => {
    replyOk({ ...ASSET, value: '5400.0000' });
    const result = await updateAsset('a1', {
      name: 'Everyday Chequing',
      assetType: 'CASH',
      value: '5400.00',
    });
    expect(result.value).toBe('5400.0000');
    expect(calls()).toEqual(['PUT /v1/networth/assets/a1']);
  });

  it('updateAsset encodes a path-hostile id rather than letting it change the route', async () => {
    replyOk(ASSET);
    await updateAsset('../liabilities/x', { name: 'n', assetType: 'CASH', value: '1.00' });
    expect(seen[0].url).toBe('/v1/networth/assets/..%2Fliabilities%2Fx');
  });

  it('deleteAsset expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteAsset('a1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/networth/assets/a1']);
  });

  it('surfaces 404 for an asset in another household', async () => {
    replyError(404, 'not-found', 'No such asset.');
    const failure = await failureOf(deleteAsset('other'));
    expect(failure.status).toBe(404);
  });
});

describe('liabilities', () => {
  it('fetchLiabilities unwraps the bare array', async () => {
    replyOk([LIABILITY]);
    await expect(fetchLiabilities()).resolves.toEqual([LIABILITY]);
    expect(calls()).toEqual(['GET /v1/networth/liabilities']);
  });

  it('createLiability posts and returns the created row', async () => {
    replyOk(LIABILITY);
    const result = await createLiability({
      name: 'Visa Credit Card',
      liabilityType: 'CREDIT_CARD',
      balance: '1250.00',
    });
    expect(result).toEqual(LIABILITY);
    expect(calls()).toEqual(['POST /v1/networth/liabilities']);
  });

  it('updateLiability puts the full replace body', async () => {
    replyOk({ ...LIABILITY, balance: '1100.0000' });
    const result = await updateLiability('l1', {
      name: 'Visa Credit Card',
      liabilityType: 'CREDIT_CARD',
      balance: '1100.00',
    });
    expect(result.balance).toBe('1100.0000');
    expect(calls()).toEqual(['PUT /v1/networth/liabilities/l1']);
  });

  it('deleteLiability expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteLiability('l1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/networth/liabilities/l1']);
  });
});
