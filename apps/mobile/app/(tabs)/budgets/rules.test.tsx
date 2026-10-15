import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import RulesScreen from './rules';
import { fetchCategories } from '@api/categories';
import {
  createCategoryRule,
  deleteCategoryRule,
  dryRunCategoryRule,
  fetchCategoryRules,
  reorderCategoryRules,
  updateCategoryRule,
} from '@api/categories';
import type { CategoryResponse, CategoryRuleResponse } from '@api/types';

jest.mock('@api/categories', () => ({
  fetchCategories: jest.fn(),
  fetchCategoryRules: jest.fn(),
  createCategoryRule: jest.fn(),
  updateCategoryRule: jest.fn(),
  deleteCategoryRule: jest.fn(),
  reorderCategoryRules: jest.fn(),
  dryRunCategoryRule: jest.fn(),
}));

const mockCategories = fetchCategories as jest.MockedFunction<typeof fetchCategories>;
const mockRules = fetchCategoryRules as jest.MockedFunction<typeof fetchCategoryRules>;
const mockCreate = createCategoryRule as jest.MockedFunction<typeof createCategoryRule>;
const mockUpdate = updateCategoryRule as jest.MockedFunction<typeof updateCategoryRule>;
const mockDelete = deleteCategoryRule as jest.MockedFunction<typeof deleteCategoryRule>;
const mockReorder = reorderCategoryRules as jest.MockedFunction<typeof reorderCategoryRules>;
const mockDryRun = dryRunCategoryRule as jest.MockedFunction<typeof dryRunCategoryRule>;

const CATEGORY: CategoryResponse = {
  id: 'c1',
  householdId: 'h1',
  slug: 'food.coffee',
  name: 'Coffee Shops',
  icon: null,
  parentId: null,
  isSystem: false,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

function rule(overrides: Partial<CategoryRuleResponse>): CategoryRuleResponse {
  return {
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
    ...overrides,
  };
}

const RULE_1 = rule({ id: 'r1', merchant: 'STARBUCKS', priority: 0 });
const RULE_2 = rule({ id: 'r2', merchant: 'BLUE BOTTLE', priority: 1 });
const RULE_3 = rule({ id: 'r3', merchant: 'PEETS', priority: 2 });

function renderScreen() {
  const client = createTestQueryClient();
  render(
    <QueryClientProvider client={client}>
      <RulesScreen />
    </QueryClientProvider>
  );
  return client;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockCategories.mockResolvedValue([CATEGORY]);
});

describe('rules render in the order the server returns', () => {
  it('lists them top to bottom exactly as listCategoryRules answered', async () => {
    mockRules.mockResolvedValue([RULE_1, RULE_2, RULE_3]);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rule-row-r1')).toBeTruthy());

    const serialised = JSON.stringify(screen.toJSON());
    const positions = ['STARBUCKS', 'BLUE BOTTLE', 'PEETS'].map((merchant) =>
      serialised.indexOf(merchant)
    );
    expect(positions.every((p) => p >= 0)).toBe(true);
    expect(positions).toEqual([...positions].sort((a, b) => a - b));
  });
});

describe('move up/down — reachable without a drag gesture', () => {
  it('gives each control an accessible name naming the merchant and direction', async () => {
    mockRules.mockResolvedValue([RULE_1, RULE_2, RULE_3]);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rule-row-r2')).toBeTruthy());

    const up = screen.getByTestId('rule-move-up-r2');
    const down = screen.getByTestId('rule-move-down-r2');
    expect(up.props.accessibilityLabel).toBe('Move BLUE BOTTLE up');
    expect(down.props.accessibilityLabel).toBe('Move BLUE BOTTLE down');
  });

  it('disables the top row’s up control and the bottom row’s down control', async () => {
    mockRules.mockResolvedValue([RULE_1, RULE_2, RULE_3]);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rule-row-r1')).toBeTruthy());

    expect(screen.getByTestId('rule-move-up-r1').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('rule-move-down-r3').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('rule-move-down-r1').props.accessibilityState.disabled).toBe(false);
  });

  it('sends the declared ReorderRulesRequest body with the new order', async () => {
    mockRules.mockResolvedValue([RULE_1, RULE_2, RULE_3]);
    mockReorder.mockResolvedValue(undefined);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rule-move-down-r1')).toBeTruthy());

    fireEvent.press(screen.getByTestId('rule-move-down-r1'));

    await waitFor(() =>
      expect(mockReorder).toHaveBeenCalledWith({ orderedIds: ['r2', 'r1', 'r3'] })
    );
  });
});

describe('creating and editing a rule', () => {
  it('creates with merchant/matchType/categoryId/confidence', async () => {
    mockRules.mockResolvedValue([]);
    mockCreate.mockResolvedValue(RULE_1);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rules-new-toggle')).toBeTruthy());

    fireEvent.press(screen.getByTestId('rules-new-toggle'));
    fireEvent.changeText(screen.getByTestId('rule-create-form-merchant'), 'Starbucks');
    fireEvent.press(screen.getByTestId('rule-create-form-category-c1'));
    fireEvent.press(screen.getByTestId('rule-create-form-submit'));

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith({
        merchant: 'Starbucks',
        matchType: 'EXACT',
        categoryId: 'c1',
        confidence: undefined,
      })
    );
  });

  it('reads the duplicate-merchant 409 in plain words', async () => {
    mockRules.mockResolvedValue([RULE_1]);
    mockCreate.mockRejectedValue(
      Object.assign(new Error('conflict'), {
        isAxiosError: true,
        response: {
          status: 409,
          data: {
            success: false,
            error: {
              type: 'https://trove.finance/errors/conflict',
              title: 'Conflict',
              status: 409,
              detail: 'Already have a rule for that merchant.',
            },
          },
        },
      })
    );
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rules-new-toggle')).toBeTruthy());
    fireEvent.press(screen.getByTestId('rules-new-toggle'));
    fireEvent.changeText(screen.getByTestId('rule-create-form-merchant'), 'Starbucks');
    fireEvent.press(screen.getByTestId('rule-create-form-category-c1'));
    fireEvent.press(screen.getByTestId('rule-create-form-submit'));

    await waitFor(() =>
      expect(
        screen.getByText(
          'A rule for that merchant already exists. Edit it instead of creating a second one.'
        )
      ).toBeTruthy()
    );
  });

  it('edits an existing rule', async () => {
    mockRules.mockResolvedValue([RULE_1]);
    mockUpdate.mockResolvedValue({ ...RULE_1, confidence: '0.900' });
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rule-edit-r1')).toBeTruthy());

    fireEvent.press(screen.getByTestId('rule-edit-r1'));
    fireEvent.changeText(screen.getByTestId('rule-edit-form-r1-confidence'), '0.900');
    fireEvent.press(screen.getByTestId('rule-edit-form-r1-submit'));

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('r1', {
        merchant: 'STARBUCKS',
        matchType: 'EXACT',
        categoryId: 'c1',
        confidence: '0.900',
      })
    );
  });

  it('deletes a rule after a confirm press', async () => {
    mockRules.mockResolvedValue([RULE_1]);
    mockDelete.mockResolvedValue(undefined);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rule-delete-r1')).toBeTruthy());

    fireEvent.press(screen.getByTestId('rule-delete-r1'));
    fireEvent.press(screen.getByTestId('rule-delete-confirm-r1'));
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('r1'));
  });
});

describe('dry run — a preview that saves nothing', () => {
  it('shows the returned category, confidence and source without ever calling a save', async () => {
    mockRules.mockResolvedValue([]);
    mockDryRun.mockResolvedValue({
      categoryId: 'c1',
      categoryName: 'Coffee Shops',
      confidence: '0.990',
      source: 'RULE',
    });
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rules-dry-run-input')).toBeTruthy());

    fireEvent.changeText(screen.getByTestId('rules-dry-run-input'), 'STARBUCKS');
    fireEvent.press(screen.getByTestId('rules-dry-run-submit'));

    await waitFor(() => expect(screen.getByTestId('rules-dry-run-result')).toBeTruthy());
    expect(screen.getByText('Coffee Shops')).toBeTruthy();
    expect(screen.getByText('Confidence 0.990')).toBeTruthy();
    expect(mockCreate).not.toHaveBeenCalled();
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('labels a null source as a built-in default rather than inventing a household rule', async () => {
    mockRules.mockResolvedValue([]);
    mockDryRun.mockResolvedValue({
      categoryId: 'c1',
      categoryName: 'Uncategorised',
      confidence: '0.100',
      source: null,
    });
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('rules-dry-run-input')).toBeTruthy());
    fireEvent.changeText(screen.getByTestId('rules-dry-run-input'), 'UNKNOWN MERCHANT');
    fireEvent.press(screen.getByTestId('rules-dry-run-submit'));

    await waitFor(() => expect(screen.getByText('Built-in default')).toBeTruthy());
  });
});
