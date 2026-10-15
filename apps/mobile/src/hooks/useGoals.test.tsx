import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { goalKeys, useCreateGoal, useDeleteGoal, useGoals, useUpdateGoal } from './useGoals';
import { createGoal, deleteGoal, fetchGoals, updateGoal } from '@api/goals';
import type { GoalResponse } from '@api/types';

jest.mock('@api/goals', () => ({
  fetchGoals: jest.fn(),
  createGoal: jest.fn(),
  updateGoal: jest.fn(),
  deleteGoal: jest.fn(),
}));

const mockList = fetchGoals as jest.MockedFunction<typeof fetchGoals>;
const mockCreate = createGoal as jest.MockedFunction<typeof createGoal>;
const mockUpdate = updateGoal as jest.MockedFunction<typeof updateGoal>;
const mockDelete = deleteGoal as jest.MockedFunction<typeof deleteGoal>;

const GOAL: GoalResponse = {
  id: 'g1',
  householdId: 'h1',
  createdBy: 'u1',
  name: 'Emergency Fund',
  targetAmount: '10000.0000',
  currentAmount: '2500.0000',
  deadline: '2026-12-31',
  currency: 'CAD',
  achieved: false,
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

describe('goalKeys', () => {
  it('one root, one list', () => {
    expect(goalKeys.all()).toEqual(['goals']);
    expect(goalKeys.list()).toEqual(['goals', 'list']);
  });
});

describe('useGoals', () => {
  it('returns the bare array — not paged', async () => {
    mockList.mockResolvedValue([GOAL]);
    const { result } = await renderHook(() => useGoals(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([GOAL]));
  });
});

describe('useCreateGoal', () => {
  it('creates and invalidates the goals list', async () => {
    mockCreate.mockResolvedValue(GOAL);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCreateGoal(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ name: 'Emergency Fund', targetAmount: '10000.00' });
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual(['goals', 'list']);
  });
});

describe('useUpdateGoal', () => {
  it('PUTs the full replace body against the id it was built for', async () => {
    mockUpdate.mockResolvedValue({ ...GOAL, currentAmount: '5000.0000' });
    const { provider } = withClient();
    const { result } = await renderHook(() => useUpdateGoal('g1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({
        name: 'Emergency Fund',
        targetAmount: '10000.00',
        currentAmount: '5000.00',
      });
    });
    expect(mockUpdate).toHaveBeenCalledWith('g1', {
      name: 'Emergency Fund',
      targetAmount: '10000.00',
      currentAmount: '5000.00',
    });
  });
});

describe('useDeleteGoal', () => {
  it('invalidates the goals list', async () => {
    mockDelete.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useDeleteGoal(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('g1');
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual(['goals', 'list']);
  });
});
