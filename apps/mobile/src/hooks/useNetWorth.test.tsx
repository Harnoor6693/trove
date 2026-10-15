import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  netWorthKeys,
  useAssets,
  useCreateAsset,
  useDeleteAsset,
  useDeleteLiability,
  useLiabilities,
  useNetWorthSeries,
  useTakeNetWorthSnapshot,
  useUpdateAsset,
} from './useNetWorth';
import {
  createAsset,
  deleteAsset,
  deleteLiability,
  fetchAssets,
  fetchLiabilities,
  fetchNetWorthSeries,
  takeNetWorthSnapshot,
  updateAsset,
} from '@api/networth';
import type { AssetResponse, NetWorthSeriesResponse } from '@api/types';

jest.mock('@api/networth', () => ({
  fetchNetWorthSeries: jest.fn(),
  takeNetWorthSnapshot: jest.fn(),
  fetchAssets: jest.fn(),
  createAsset: jest.fn(),
  updateAsset: jest.fn(),
  deleteAsset: jest.fn(),
  fetchLiabilities: jest.fn(),
  createLiability: jest.fn(),
  updateLiability: jest.fn(),
  deleteLiability: jest.fn(),
}));

const mockSeries = fetchNetWorthSeries as jest.MockedFunction<typeof fetchNetWorthSeries>;
const mockSnapshot = takeNetWorthSnapshot as jest.MockedFunction<typeof takeNetWorthSnapshot>;
const mockAssets = fetchAssets as jest.MockedFunction<typeof fetchAssets>;
const mockCreateAsset = createAsset as jest.MockedFunction<typeof createAsset>;
const mockUpdateAsset = updateAsset as jest.MockedFunction<typeof updateAsset>;
const mockDeleteAsset = deleteAsset as jest.MockedFunction<typeof deleteAsset>;
const mockLiabilities = fetchLiabilities as jest.MockedFunction<typeof fetchLiabilities>;
const mockDeleteLiability = deleteLiability as jest.MockedFunction<typeof deleteLiability>;

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

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function withClient() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const invalidate = jest.spyOn(qc, 'invalidateQueries');
  const provider = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { qc, invalidate, provider };
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('netWorthKeys', () => {
  it('keys the series by range, defaulting to a stable literal when omitted', () => {
    expect(netWorthKeys.series()).toEqual(['networth', 'series', 'default']);
    expect(netWorthKeys.series('1Y')).toEqual(['networth', 'series', '1Y']);
    expect(netWorthKeys.assets()).toEqual(['networth', 'assets']);
    expect(netWorthKeys.liabilities()).toEqual(['networth', 'liabilities']);
  });
});

describe('useNetWorthSeries', () => {
  it('returns one entry per currency and never sums them', async () => {
    mockSeries.mockResolvedValue(SERIES);
    const { result } = renderHook(() => useNetWorthSeries(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(SERIES));
    expect(result.current.data?.series).toHaveLength(2);
    expect(result.current.data).not.toHaveProperty('totalNetWorth');
  });
});

describe('useTakeNetWorthSnapshot', () => {
  it('invalidates every net-worth query on success, not just one range', async () => {
    mockSnapshot.mockResolvedValue([]);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useTakeNetWorthSnapshot(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual(['networth']);
  });
});

describe('useAssets / mutations', () => {
  it('useAssets returns the bare array — not paged', async () => {
    mockAssets.mockResolvedValue([ASSET]);
    const { result } = renderHook(() => useAssets(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([ASSET]));
  });

  it('useCreateAsset invalidates the assets list', async () => {
    mockCreateAsset.mockResolvedValue(ASSET);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useCreateAsset(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ name: 'x', assetType: 'CASH', value: '1.00' });
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'networth',
      'assets',
    ]);
  });

  it('useUpdateAsset sends the id it was built for', async () => {
    mockUpdateAsset.mockResolvedValue(ASSET);
    const { provider } = withClient();
    const { result } = renderHook(() => useUpdateAsset('a1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ name: 'x', assetType: 'CASH', value: '1.00' });
    });
    expect(mockUpdateAsset).toHaveBeenCalledWith('a1', {
      name: 'x',
      assetType: 'CASH',
      value: '1.00',
    });
  });

  it('useDeleteAsset invalidates the assets list', async () => {
    mockDeleteAsset.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useDeleteAsset(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('a1');
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'networth',
      'assets',
    ]);
  });
});

describe('useLiabilities / useDeleteLiability', () => {
  it('useLiabilities returns the bare array — not paged', async () => {
    mockLiabilities.mockResolvedValue([]);
    const { result } = renderHook(() => useLiabilities(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([]));
  });

  it('useDeleteLiability invalidates the liabilities list', async () => {
    mockDeleteLiability.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useDeleteLiability(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('l1');
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'networth',
      'liabilities',
    ]);
  });
});
