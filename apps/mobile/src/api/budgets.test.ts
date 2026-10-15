import { calls, failureOf, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
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
} from './budgets';
import type { BudgetPeriodResponse, EnvelopeResponse } from './types';

const BASE = 'https://trove.example.com';

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

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchBudgetPeriods', () => {
  it('unwraps the bare array — not paged', async () => {
    replyOk([PERIOD]);
    await expect(fetchBudgetPeriods()).resolves.toEqual([PERIOD]);
    expect(calls()).toEqual(['GET /v1/budgets']);
  });
});

describe('createBudgetPeriod', () => {
  it('posts the request and returns the created period (201)', async () => {
    replyOk(PERIOD);
    const result = await createBudgetPeriod({ month: '2026-09-01', totalIncome: '5000.00' });
    expect(result).toEqual(PERIOD);
    expect(calls()).toEqual(['POST /v1/budgets']);
  });

  it('surfaces 409 conflict when the household already has a period for that month', async () => {
    replyError(409, 'conflict', 'A period for that month already exists.');
    const failure = await failureOf(
      createBudgetPeriod({ month: '2026-09-01', totalIncome: '5000.00' })
    );
    expect(failure.status).toBe(409);
  });
});

describe('fetchCurrentBudgetPeriod', () => {
  it('resolves the period for the caller’s current month', async () => {
    replyOk(PERIOD);
    await expect(fetchCurrentBudgetPeriod()).resolves.toEqual(PERIOD);
    expect(calls()).toEqual(['GET /v1/budgets/current']);
  });

  it('surfaces a 404 — the caller’s own empty-state signal, not a generic failure', async () => {
    replyError(404, 'not-found', 'No budget period for this month.');
    const failure = await failureOf(fetchCurrentBudgetPeriod());
    expect(failure.status).toBe(404);
    expect(failure.code).toBe('not-found');
  });
});

describe('fetchBudgetPeriod', () => {
  it('gets one period by id', async () => {
    replyOk(PERIOD);
    await expect(fetchBudgetPeriod('p1')).resolves.toEqual(PERIOD);
    expect(calls()).toEqual(['GET /v1/budgets/p1']);
  });
});

describe('updateBudgetPeriod', () => {
  it('puts totalIncome/budgetType and returns the updated period', async () => {
    replyOk({ ...PERIOD, totalIncome: '5200.0000' });
    const result = await updateBudgetPeriod('p1', {
      totalIncome: '5200.00',
      budgetType: 'ROLLOVER',
    });
    expect(result.totalIncome).toBe('5200.0000');
    expect(calls()).toEqual(['PUT /v1/budgets/p1']);
  });

  it('surfaces the zero-based invariant as a 400 naming totalIncome', async () => {
    replyError(400, 'validation-error', 'Allocations no longer sum to income.', {
      fieldErrors: [{ field: 'totalIncome', message: 'Envelopes must sum to your total income.' }],
    });
    const failure = await failureOf(
      updateBudgetPeriod('p1', { totalIncome: '1.00', budgetType: 'ZERO_BASED' })
    );
    expect(failure.status).toBe(400);
    expect(failure.fieldErrors).toEqual([
      { field: 'totalIncome', message: 'Envelopes must sum to your total income.' },
    ]);
  });
});

describe('deleteBudgetPeriod', () => {
  it('deletes and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteBudgetPeriod('p1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/budgets/p1']);
  });
});

describe('fetchEnvelopes', () => {
  it('unwraps the bare array for one period — not paged', async () => {
    replyOk([ENVELOPE]);
    await expect(fetchEnvelopes('p1')).resolves.toEqual([ENVELOPE]);
    expect(calls()).toEqual(['GET /v1/budgets/p1/envelopes']);
  });
});

describe('createEnvelope', () => {
  it('posts categoryId/allocated/currency and returns the created envelope (201)', async () => {
    replyOk(ENVELOPE);
    const result = await createEnvelope('p1', { categoryId: 'c1', allocated: '400.00' });
    expect(result).toEqual(ENVELOPE);
    expect(calls()).toEqual(['POST /v1/budgets/p1/envelopes']);
  });

  it('surfaces 409 conflict for a category the period already budgets', async () => {
    replyError(409, 'conflict', 'This period already has an envelope for that category.');
    const failure = await failureOf(createEnvelope('p1', { categoryId: 'c1', allocated: '1.00' }));
    expect(failure.status).toBe(409);
  });

  it('surfaces the zero-based invariant as a 400 naming allocated', async () => {
    replyError(400, 'validation-error', 'Would break the sum invariant.', {
      fieldErrors: [{ field: 'allocated', message: 'Envelopes must sum to your total income.' }],
    });
    const failure = await failureOf(
      createEnvelope('p1', { categoryId: 'c1', allocated: '999999.00' })
    );
    expect(failure.fieldErrors).toEqual([
      { field: 'allocated', message: 'Envelopes must sum to your total income.' },
    ]);
  });
});

describe('fetchEnvelope', () => {
  it('gets one envelope with its live health', async () => {
    replyOk(ENVELOPE);
    await expect(fetchEnvelope('p1', 'e1')).resolves.toEqual(ENVELOPE);
    expect(calls()).toEqual(['GET /v1/budgets/p1/envelopes/e1']);
  });
});

describe('updateEnvelope', () => {
  it('puts allocated only and returns the updated envelope', async () => {
    replyOk({ ...ENVELOPE, allocated: '450.0000' });
    const result = await updateEnvelope('p1', 'e1', { allocated: '450.00' });
    expect(result.allocated).toBe('450.0000');
    expect(calls()).toEqual(['PUT /v1/budgets/p1/envelopes/e1']);
    expect(seen[0].data).toBe(JSON.stringify({ allocated: '450.00' }));
  });
});

describe('deleteEnvelope', () => {
  it('deletes and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteEnvelope('p1', 'e1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/budgets/p1/envelopes/e1']);
  });
});
