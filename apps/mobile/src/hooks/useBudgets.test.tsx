import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  budgetKeys,
  useBudgetPeriod,
  useBudgetPeriods,
  useCreateBudgetPeriod,
  useCreateEnvelope,
  useCurrentBudgetPeriod,
  useDeleteBudgetPeriod,
  useDeleteEnvelope,
  useEnvelope,
  useEnvelopes,
  useUpdateBudgetPeriod,
  useUpdateEnvelope,
} from './useBudgets';
import {
  createBudgetPeriod,
  createEnvelope,
  deleteBudgetPeriod,
  deleteEnvelope,
  fetchBudgetPeriod,
  fetchBudgetPeriods,
  fetchCurrentBudgetPeriod,
  fetchEnvelope,
  fetchEnvelopes,
  updateBudgetPeriod,
  updateEnvelope,
} from '@api/budgets';
import type { BudgetPeriodResponse, EnvelopeResponse } from '@api/types';

jest.mock('@api/budgets', () => ({
  fetchBudgetPeriods: jest.fn(),
  createBudgetPeriod: jest.fn(),
  fetchCurrentBudgetPeriod: jest.fn(),
  fetchBudgetPeriod: jest.fn(),
  updateBudgetPeriod: jest.fn(),
  deleteBudgetPeriod: jest.fn(),
  fetchEnvelopes: jest.fn(),
  createEnvelope: jest.fn(),
  fetchEnvelope: jest.fn(),
  updateEnvelope: jest.fn(),
  deleteEnvelope: jest.fn(),
}));

const mockList = fetchBudgetPeriods as jest.MockedFunction<typeof fetchBudgetPeriods>;
const mockCreate = createBudgetPeriod as jest.MockedFunction<typeof createBudgetPeriod>;
const mockCurrent = fetchCurrentBudgetPeriod as jest.MockedFunction<
  typeof fetchCurrentBudgetPeriod
>;
const mockOne = fetchBudgetPeriod as jest.MockedFunction<typeof fetchBudgetPeriod>;
const mockUpdate = updateBudgetPeriod as jest.MockedFunction<typeof updateBudgetPeriod>;
const mockDelete = deleteBudgetPeriod as jest.MockedFunction<typeof deleteBudgetPeriod>;
const mockEnvelopes = fetchEnvelopes as jest.MockedFunction<typeof fetchEnvelopes>;
const mockCreateEnvelope = createEnvelope as jest.MockedFunction<typeof createEnvelope>;
const mockEnvelope = fetchEnvelope as jest.MockedFunction<typeof fetchEnvelope>;
const mockUpdateEnvelope = updateEnvelope as jest.MockedFunction<typeof updateEnvelope>;
const mockDeleteEnvelope = deleteEnvelope as jest.MockedFunction<typeof deleteEnvelope>;

const PERIOD: BudgetPeriodResponse = {
  id: 'p1',
  householdId: 'h1',
  createdBy: 'u1',
  month: '2026-09-01',
  totalIncome: '5000.0000',
  currency: 'CAD',
  budgetType: 'ROLLOVER',
  rolledOverAt: null,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

const ENVELOPE: EnvelopeResponse = {
  id: 'e1',
  householdId: 'h1',
  periodId: 'p1',
  categoryId: 'c1',
  categoryName: 'Groceries',
  allocated: '400.0000',
  spent: '250.0000',
  rolloverAmount: '0.0000',
  currency: 'CAD',
  health: {
    healthPercentage: 62,
    paceMarkerPercentage: 50,
    overPace: true,
    remainingAmount: '150.0000',
    expectedSpend: '200.0000',
  },
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
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

describe('budgetKeys', () => {
  it('current and list sit under the same root a period write invalidates', () => {
    expect(budgetKeys.all()).toEqual(['budgets']);
    expect(budgetKeys.list()).toEqual(['budgets', 'list']);
    expect(budgetKeys.current()).toEqual(['budgets', 'current']);
    expect(budgetKeys.detail('p1')).toEqual(['budgets', 'p1']);
    expect(budgetKeys.envelopes('p1')).toEqual(['budgets', 'p1', 'envelopes']);
    expect(budgetKeys.envelope('p1', 'e1')).toEqual(['budgets', 'p1', 'envelopes', 'e1']);
  });
});

describe('useBudgetPeriods', () => {
  it('returns the bare array — not paged', async () => {
    mockList.mockResolvedValue([PERIOD]);
    const { result } = await renderHook(() => useBudgetPeriods(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([PERIOD]));
  });
});

describe('useCurrentBudgetPeriod', () => {
  it('resolves the period when one exists', async () => {
    mockCurrent.mockResolvedValue(PERIOD);
    const { result } = await renderHook(() => useCurrentBudgetPeriod(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(PERIOD));
  });

  it('settles into isError on a 404 rather than retrying it — the caller reads this as its empty state', async () => {
    const notFound = Object.assign(new Error('not found'), {
      isAxiosError: true,
      response: { status: 404, data: {} },
    });
    mockCurrent.mockRejectedValue(notFound);
    const { result } = await renderHook(() => useCurrentBudgetPeriod(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(mockCurrent).toHaveBeenCalledTimes(1);
  });
});

describe('useBudgetPeriod', () => {
  it('fetches by id and stays idle without one', async () => {
    mockOne.mockResolvedValue(PERIOD);
    const { result } = await renderHook(() => useBudgetPeriod('p1'), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(PERIOD));

    const idle = await renderHook(() => useBudgetPeriod(''), { wrapper });
    expect(idle.result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateBudgetPeriod', () => {
  it('creates and invalidates the whole budgets root, reaching current and list', async () => {
    mockCreate.mockResolvedValue(PERIOD);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCreateBudgetPeriod(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ month: '2026-09-01', totalIncome: '5000.00' });
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual(['budgets']);
  });
});

describe('useUpdateBudgetPeriod', () => {
  it('PUTs against the id it was built for', async () => {
    mockUpdate.mockResolvedValue({ ...PERIOD, totalIncome: '5200.0000' });
    const { provider } = withClient();
    const { result } = await renderHook(() => useUpdateBudgetPeriod('p1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ totalIncome: '5200.00', budgetType: 'ROLLOVER' });
    });
    expect(mockUpdate).toHaveBeenCalledWith('p1', {
      totalIncome: '5200.00',
      budgetType: 'ROLLOVER',
    });
  });
});

describe('useDeleteBudgetPeriod', () => {
  it('invalidates the budgets root', async () => {
    mockDelete.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useDeleteBudgetPeriod(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('p1');
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual(['budgets']);
  });
});

describe('useEnvelopes / useEnvelope', () => {
  it('fetch the period’s envelopes and one by id, idle without ids', async () => {
    mockEnvelopes.mockResolvedValue([ENVELOPE]);
    mockEnvelope.mockResolvedValue(ENVELOPE);

    const list = await renderHook(() => useEnvelopes('p1'), { wrapper });
    await waitFor(() => expect(list.result.current.data).toEqual([ENVELOPE]));

    const one = await renderHook(() => useEnvelope('p1', 'e1'), { wrapper });
    await waitFor(() => expect(one.result.current.data).toEqual(ENVELOPE));

    const idleList = await renderHook(() => useEnvelopes(''), { wrapper });
    const idleOne = await renderHook(() => useEnvelope('p1', ''), { wrapper });
    expect(idleList.result.current.fetchStatus).toBe('idle');
    expect(idleOne.result.current.fetchStatus).toBe('idle');
  });

  it('carries the server’s own health verbatim — no client-side recomputation', async () => {
    mockEnvelopes.mockResolvedValue([ENVELOPE]);
    const { result } = await renderHook(() => useEnvelopes('p1'), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.[0].health).toEqual(ENVELOPE.health);
  });
});

describe('useCreateEnvelope / useUpdateEnvelope / useDeleteEnvelope', () => {
  it('create invalidates only this period’s envelope list', async () => {
    mockCreateEnvelope.mockResolvedValue(ENVELOPE);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCreateEnvelope('p1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ categoryId: 'c1', allocated: '400.00' });
    });
    expect(mockCreateEnvelope).toHaveBeenCalledWith('p1', {
      categoryId: 'c1',
      allocated: '400.00',
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'budgets',
      'p1',
      'envelopes',
    ]);
  });

  it('update PUTs allocated only against the period/envelope pair it was built for', async () => {
    mockUpdateEnvelope.mockResolvedValue({ ...ENVELOPE, allocated: '450.0000' });
    const { provider } = withClient();
    const { result } = await renderHook(() => useUpdateEnvelope('p1', 'e1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ allocated: '450.00' });
    });
    expect(mockUpdateEnvelope).toHaveBeenCalledWith('p1', 'e1', { allocated: '450.00' });
  });

  it('delete takes the envelope id and invalidates that period’s envelope list', async () => {
    mockDeleteEnvelope.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useDeleteEnvelope('p1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('e1');
    });
    expect(mockDeleteEnvelope).toHaveBeenCalledWith('p1', 'e1');
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'budgets',
      'p1',
      'envelopes',
    ]);
  });
});
