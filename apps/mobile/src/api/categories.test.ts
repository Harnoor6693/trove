// Imported first: installs the mock adapter `client.ts` copies at
// module-evaluation time (see the file header of `@/test/httpMock`).
import { calls, failureOf, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
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
} from './categories';
import type { CategoryResponse, CategoryRuleResponse } from './types';

const BASE = 'https://trove.example.com';

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

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchCategories', () => {
  it('unwraps the bare array the operation declares — this list is not paged', async () => {
    replyOk([CATEGORY]);
    await expect(fetchCategories()).resolves.toEqual([CATEGORY]);
    expect(calls()).toEqual(['GET /v1/categories']);
  });
});

describe('createCategory', () => {
  it('posts name/parentId/icon and returns the created category (201)', async () => {
    replyOk(CATEGORY);
    const result = await createCategory({ name: 'Coffee Shops', parentId: null, icon: 'coffee' });
    expect(result).toEqual(CATEGORY);
    expect(calls()).toEqual(['POST /v1/categories']);
    expect(seen[0].data).toBe(
      JSON.stringify({ name: 'Coffee Shops', parentId: null, icon: 'coffee' })
    );
  });

  it('surfaces a 400 validation-error with fieldErrors', async () => {
    replyError(400, 'validation-error', 'Rejected.', {
      fieldErrors: [{ field: 'name', message: 'Name is required.' }],
    });
    const failure = await failureOf(createCategory({ name: '', parentId: null, icon: null }));
    expect(failure.status).toBe(400);
    expect(failure.fieldErrors).toEqual([{ field: 'name', message: 'Name is required.' }]);
  });
});

describe('updateCategory', () => {
  it('puts to the category path and returns the updated row', async () => {
    replyOk({ ...CATEGORY, name: 'Renamed' });
    const result = await updateCategory('c1', { name: 'Renamed', parentId: null, icon: 'coffee' });
    expect(result.name).toBe('Renamed');
    expect(calls()).toEqual(['PUT /v1/categories/c1']);
  });

  it('surfaces 403 forbidden on a system category', async () => {
    replyError(403, 'forbidden', 'A system category cannot be renamed.');
    const failure = await failureOf(
      updateCategory('sys1', { name: 'x', parentId: null, icon: null })
    );
    expect(failure.status).toBe(403);
    expect(failure.code).toBe('forbidden');
  });
});

describe('deleteCategory', () => {
  it('deletes and expects the closed EmptySuccess shape', async () => {
    replyOk(undefined);
    await expect(deleteCategory('c1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/categories/c1']);
  });

  it('surfaces 409 conflict when the category is still referenced by a rule', async () => {
    replyError(409, 'conflict', 'Still referenced by a merchant rule.');
    const failure = await failureOf(deleteCategory('c1'));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
  });
});

describe('fetchCategoryRules', () => {
  it('unwraps the bare array in priority order — not paged', async () => {
    replyOk([RULE]);
    await expect(fetchCategoryRules()).resolves.toEqual([RULE]);
    expect(calls()).toEqual(['GET /v1/categories/rules']);
  });
});

describe('createCategoryRule', () => {
  it('posts merchant/matchType/categoryId/confidence and returns the created rule (201)', async () => {
    replyOk(RULE);
    const result = await createCategoryRule({
      merchant: 'Starbucks',
      matchType: 'EXACT',
      categoryId: 'c1',
      confidence: '0.950',
    });
    expect(result).toEqual(RULE);
    expect(calls()).toEqual(['POST /v1/categories/rules']);
  });

  it('surfaces 409 conflict for a duplicate merchant', async () => {
    replyError(409, 'conflict', 'A rule for that merchant already exists.');
    const failure = await failureOf(
      createCategoryRule({ merchant: 'Starbucks', matchType: 'EXACT', categoryId: 'c1' })
    );
    expect(failure.status).toBe(409);
  });
});

describe('updateCategoryRule', () => {
  it('puts to the rule path and returns the updated row', async () => {
    replyOk({ ...RULE, confidence: '0.900' });
    const result = await updateCategoryRule('r1', {
      merchant: 'Starbucks',
      matchType: 'EXACT',
      categoryId: 'c1',
      confidence: '0.900',
    });
    expect(result.confidence).toBe('0.900');
    expect(calls()).toEqual(['PUT /v1/categories/rules/r1']);
  });
});

describe('deleteCategoryRule', () => {
  it('deletes and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteCategoryRule('r1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/categories/rules/r1']);
  });
});

describe('reorderCategoryRules', () => {
  it('posts the declared ReorderRulesRequest body and expects EmptySuccess, not the list', async () => {
    replyOk(undefined);
    await expect(reorderCategoryRules({ orderedIds: ['r2', 'r1'] })).resolves.toBeUndefined();
    expect(calls()).toEqual(['POST /v1/categories/rules/reorder']);
    expect(seen[0].data).toBe(JSON.stringify({ orderedIds: ['r2', 'r1'] }));
  });

  it('surfaces the whole-request refusal (400) rather than a partial reorder', async () => {
    replyError(400, 'validation-error', 'One id does not belong to this household.');
    const failure = await failureOf(reorderCategoryRules({ orderedIds: ['not-mine'] }));
    expect(failure.status).toBe(400);
  });
});

describe('dryRunCategoryRule', () => {
  it('posts the merchant and returns the CategorisationResultResponse, writing nothing', async () => {
    replyOk({ categoryId: 'c1', categoryName: 'Coffee Shops', confidence: '0.990', source: null });
    const result = await dryRunCategoryRule({ merchant: 'STARBUCKS' });
    expect(result).toEqual({
      categoryId: 'c1',
      categoryName: 'Coffee Shops',
      confidence: '0.990',
      source: null,
    });
    expect(calls()).toEqual(['POST /v1/categories/rules/dry-run']);
  });
});
