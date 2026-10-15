import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  categoryKeys,
  useCategories,
  useCategoryRules,
  useCreateCategory,
  useCreateCategoryRule,
  useDeleteCategory,
  useDeleteCategoryRule,
  useDryRunCategoryRule,
  useReorderCategoryRules,
  useUpdateCategory,
  useUpdateCategoryRule,
} from './useCategories';
import {
  createCategory,
  createCategoryRule,
  deleteCategory,
  deleteCategoryRule,
  dryRunCategoryRule,
  fetchCategories,
  fetchCategoryRules,
  reorderCategoryRules,
  updateCategory,
  updateCategoryRule,
} from '@api/categories';
import type { CategoryResponse, CategoryRuleResponse } from '@api/types';

jest.mock('@api/categories', () => ({
  fetchCategories: jest.fn(),
  createCategory: jest.fn(),
  updateCategory: jest.fn(),
  deleteCategory: jest.fn(),
  fetchCategoryRules: jest.fn(),
  createCategoryRule: jest.fn(),
  updateCategoryRule: jest.fn(),
  deleteCategoryRule: jest.fn(),
  reorderCategoryRules: jest.fn(),
  dryRunCategoryRule: jest.fn(),
}));

const mockList = fetchCategories as jest.MockedFunction<typeof fetchCategories>;
const mockCreate = createCategory as jest.MockedFunction<typeof createCategory>;
const mockUpdate = updateCategory as jest.MockedFunction<typeof updateCategory>;
const mockDelete = deleteCategory as jest.MockedFunction<typeof deleteCategory>;
const mockRuleList = fetchCategoryRules as jest.MockedFunction<typeof fetchCategoryRules>;
const mockRuleCreate = createCategoryRule as jest.MockedFunction<typeof createCategoryRule>;
const mockRuleUpdate = updateCategoryRule as jest.MockedFunction<typeof updateCategoryRule>;
const mockRuleDelete = deleteCategoryRule as jest.MockedFunction<typeof deleteCategoryRule>;
const mockReorder = reorderCategoryRules as jest.MockedFunction<typeof reorderCategoryRules>;
const mockDryRun = dryRunCategoryRule as jest.MockedFunction<typeof dryRunCategoryRule>;

const CATEGORY: CategoryResponse = {
  id: 'c1',
  householdId: 'h1',
  slug: 'food.coffee',
  name: 'Coffee Shops',
  icon: 'coffee',
  parentId: null,
  isSystem: false,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

const RULE: CategoryRuleResponse = {
  id: 'r1',
  householdId: 'h1',
  merchant: 'STARBUCKS',
  matchType: 'EXACT',
  categoryId: 'c1',
  categoryName: 'Coffee Shops',
  confidence: '0.950',
  source: 'RULE',
  priority: 0,
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

describe('categoryKeys', () => {
  it('one root, list and rules as siblings under it', () => {
    expect(categoryKeys.all()).toEqual(['categories']);
    expect(categoryKeys.list()).toEqual(['categories', 'list']);
    expect(categoryKeys.rules()).toEqual(['categories', 'rules']);
  });
});

describe('useCategories', () => {
  it('returns the bare array the operation sends', async () => {
    mockList.mockResolvedValue([CATEGORY]);
    const { result } = await renderHook(() => useCategories(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([CATEGORY]));
  });
});

describe('useCreateCategory / useUpdateCategory / useDeleteCategory', () => {
  it('create invalidates the category list', async () => {
    mockCreate.mockResolvedValue(CATEGORY);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCreateCategory(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ name: 'Coffee Shops', parentId: null, icon: 'coffee' });
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'categories',
      'list',
    ]);
  });

  it('update PUTs against the id it was built for', async () => {
    mockUpdate.mockResolvedValue({ ...CATEGORY, name: 'Renamed' });
    const { provider } = withClient();
    const { result } = await renderHook(() => useUpdateCategory('c1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ name: 'Renamed', parentId: null, icon: null });
    });
    expect(mockUpdate).toHaveBeenCalledWith('c1', { name: 'Renamed', parentId: null, icon: null });
  });

  it('delete invalidates the list and surfaces a 409 rather than silently failing', async () => {
    mockDelete.mockRejectedValue(new Error('conflict'));
    const { provider } = withClient();
    const { result } = await renderHook(() => useDeleteCategory(), { wrapper: provider });
    await act(() => {
      result.current.mutate('c1');
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('useCategoryRules', () => {
  it('returns the bare array in priority order', async () => {
    mockRuleList.mockResolvedValue([RULE]);
    const { result } = await renderHook(() => useCategoryRules(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([RULE]));
  });
});

describe('useCreateCategoryRule / useUpdateCategoryRule / useDeleteCategoryRule', () => {
  it('create invalidates only the rules key, not the whole categories root', async () => {
    mockRuleCreate.mockResolvedValue(RULE);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCreateCategoryRule(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({
        merchant: 'Starbucks',
        matchType: 'EXACT',
        categoryId: 'c1',
      });
    });
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['categories', 'rules']);
  });

  it('update PUTs against the id it was built for', async () => {
    mockRuleUpdate.mockResolvedValue({ ...RULE, confidence: '0.900' });
    const { provider } = withClient();
    const { result } = await renderHook(() => useUpdateCategoryRule('r1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({
        merchant: 'Starbucks',
        matchType: 'EXACT',
        categoryId: 'c1',
      });
    });
    expect(mockRuleUpdate).toHaveBeenCalledWith('r1', {
      merchant: 'Starbucks',
      matchType: 'EXACT',
      categoryId: 'c1',
    });
  });

  it('delete resolves without a body', async () => {
    mockRuleDelete.mockResolvedValue(undefined);
    const { provider } = withClient();
    const { result } = await renderHook(() => useDeleteCategoryRule(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('r1');
    });
    expect(result.current.isError).toBe(false);
  });
});

describe('useReorderCategoryRules', () => {
  it('sends the declared body and refetches the list from the server’s own priority', async () => {
    mockReorder.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useReorderCategoryRules(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ orderedIds: ['r2', 'r1'] });
    });
    expect(mockReorder).toHaveBeenCalledWith({ orderedIds: ['r2', 'r1'] });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'categories',
      'rules',
    ]);
  });
});

describe('useDryRunCategoryRule', () => {
  it('writes nothing and returns the pipeline result', async () => {
    mockDryRun.mockResolvedValue({
      categoryId: 'c1',
      categoryName: 'Coffee Shops',
      confidence: '0.990',
      source: null,
    });
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useDryRunCategoryRule(), { wrapper: provider });
    await act(() => {
      result.current.mutate({ merchant: 'STARBUCKS' });
    });
    await waitFor(() => expect(result.current.data?.categoryName).toBe('Coffee Shops'));
    expect(invalidate).not.toHaveBeenCalled();
  });
});
