import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import GoalsScreen from './goals';
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

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <GoalsScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists goals with progress rendered only from currentAmount/targetAmount', async () => {
  mockList.mockResolvedValue([GOAL]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('goal-row-g1')).toBeTruthy());
  expect(screen.getByTestId('goal-current-g1')).toBeTruthy();
  expect(screen.getByTestId('goal-target-g1')).toBeTruthy();
});

it('creates a goal without accepting currentAmount or achieved', async () => {
  mockList.mockResolvedValue([]);
  mockCreate.mockResolvedValue(GOAL);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('goals-new-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('goals-new-toggle'));
  await fireEvent.changeText(screen.getByTestId('goals-create-form-name'), 'Emergency Fund');
  await fireEvent.changeText(screen.getByTestId('goals-create-form-target'), '10000.00');
  await fireEvent.press(screen.getByTestId('goals-create-form-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith({
      name: 'Emergency Fund',
      targetAmount: '10000.00',
      deadline: undefined,
    })
  );
});

it('edits a goal, sending the full replace body including currentAmount', async () => {
  mockList.mockResolvedValue([GOAL]);
  mockUpdate.mockResolvedValue({ ...GOAL, currentAmount: '5000.0000' });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('goal-edit-g1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('goal-edit-g1'));
  await fireEvent.changeText(screen.getByTestId('goal-edit-form-g1-current'), '5000.00');
  await fireEvent.press(screen.getByTestId('goal-edit-form-g1-submit'));

  await waitFor(() =>
    expect(mockUpdate).toHaveBeenCalledWith('g1', {
      name: 'Emergency Fund',
      targetAmount: '10000.0000',
      currentAmount: '5000.00',
      deadline: '2026-12-31',
    })
  );
});

it('requires a second press to delete', async () => {
  mockList.mockResolvedValue([GOAL]);
  mockDelete.mockResolvedValue(undefined);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('goal-delete-g1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('goal-delete-g1'));
  expect(mockDelete).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByTestId('goal-delete-confirm-g1'));
  await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('g1'));
});
