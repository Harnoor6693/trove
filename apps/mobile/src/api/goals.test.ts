import { calls, failureOf, replyError, replyOk, reset } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import { createGoal, deleteGoal, fetchGoals, updateGoal } from './goals';
import type { GoalResponse } from './types';

const BASE = 'https://trove.example.com';

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

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchGoals', () => {
  it('unwraps the bare array — not paged', async () => {
    replyOk([GOAL]);
    await expect(fetchGoals()).resolves.toEqual([GOAL]);
    expect(calls()).toEqual(['GET /v1/goals']);
  });
});

describe('createGoal', () => {
  it('posts name/targetAmount/currency/deadline and returns the created goal (201)', async () => {
    replyOk(GOAL);
    const result = await createGoal({ name: 'Emergency Fund', targetAmount: '10000.00' });
    expect(result).toEqual(GOAL);
    expect(calls()).toEqual(['POST /v1/goals']);
  });

  it('surfaces a 400 validation-error with fieldErrors', async () => {
    replyError(400, 'validation-error', 'Rejected.', {
      fieldErrors: [{ field: 'name', message: 'Name is required.' }],
    });
    const failure = await failureOf(createGoal({ name: '', targetAmount: '10.00' }));
    expect(failure.status).toBe(400);
    expect(failure.fieldErrors).toEqual([{ field: 'name', message: 'Name is required.' }]);
  });
});

describe('updateGoal', () => {
  it('puts the full replace body and returns the updated goal', async () => {
    replyOk({ ...GOAL, currentAmount: '5000.0000' });
    const result = await updateGoal('g1', {
      name: 'Emergency Fund',
      targetAmount: '10000.00',
      currentAmount: '5000.00',
      deadline: '2026-12-31',
    });
    expect(result.currentAmount).toBe('5000.0000');
    expect(calls()).toEqual(['PUT /v1/goals/g1']);
  });

  it('surfaces 404 for another household’s goal', async () => {
    replyError(404, 'not-found', 'No such goal.');
    const failure = await failureOf(
      updateGoal('other', {
        name: 'x',
        targetAmount: '1.00',
        currentAmount: '0.00',
      })
    );
    expect(failure.status).toBe(404);
  });
});

describe('deleteGoal', () => {
  it('deletes and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteGoal('g1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/goals/g1']);
  });
});
