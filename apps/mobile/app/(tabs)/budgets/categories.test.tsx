import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import CategoriesScreen from './categories';
import { createCategory, deleteCategory, fetchCategories, updateCategory } from '@api/categories';
import type { CategoryResponse } from '@api/types';

jest.mock('@api/categories', () => ({
  fetchCategories: jest.fn(),
  createCategory: jest.fn(),
  updateCategory: jest.fn(),
  deleteCategory: jest.fn(),
}));

const mockList = fetchCategories as jest.MockedFunction<typeof fetchCategories>;
const mockCreate = createCategory as jest.MockedFunction<typeof createCategory>;
const mockUpdate = updateCategory as jest.MockedFunction<typeof updateCategory>;
const mockDelete = deleteCategory as jest.MockedFunction<typeof deleteCategory>;

function category(overrides: Partial<CategoryResponse> = {}): CategoryResponse {
  return {
    id: 'c1',
    householdId: 'h1',
    slug: 'food',
    name: 'Food',
    icon: null,
    parentId: null,
    isSystem: false,
    createdAt: '2026-09-04T14:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
    ...overrides,
  };
}

const SYSTEM: CategoryResponse = category({
  id: 'sys1',
  householdId: null as unknown as string,
  name: 'Uncategorised',
  isSystem: true,
});

const PARENT: CategoryResponse = category({ id: 'p1', name: 'Food' });
const CHILD: CategoryResponse = category({ id: 'c1', name: 'Coffee Shops', parentId: 'p1' });

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <CategoriesScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('the tree', () => {
  it('nests a child under its parent and hides destructive actions on a system category', async () => {
    mockList.mockResolvedValue([SYSTEM, PARENT, CHILD]);
    await renderScreen();

    await waitFor(() => expect(screen.getByTestId('category-node-p1')).toBeTruthy());
    expect(screen.getByTestId('category-node-c1')).toBeTruthy();
    expect(screen.getByTestId('category-node-sys1')).toBeTruthy();

    // A system category offers no edit/delete affordance at all.
    expect(screen.queryByTestId('category-edit-sys1')).toBeNull();
    expect(screen.queryByTestId('category-delete-sys1')).toBeNull();
    expect(screen.getByText('Built in')).toBeTruthy();

    // A household category does.
    expect(screen.getByTestId('category-edit-p1')).toBeTruthy();
    expect(screen.getByTestId('category-delete-p1')).toBeTruthy();
  });
});

describe('creating a category', () => {
  it('sends only name/parentId/icon — never id/householdId/slug', async () => {
    mockList.mockResolvedValue([PARENT]);
    mockCreate.mockResolvedValue(category({ id: 'c2', name: 'Coffee' }));
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('categories-new-toggle')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('categories-new-toggle'));
    await fireEvent.changeText(screen.getByTestId('category-create-form-name'), 'Coffee');
    await fireEvent.press(screen.getByTestId('category-create-form-parent-p1'));
    await fireEvent.press(screen.getByTestId('category-create-form-submit'));

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith({ name: 'Coffee', parentId: 'p1', icon: null })
    );
  });
});

describe('editing a category', () => {
  it('renames the category', async () => {
    mockList.mockResolvedValue([PARENT]);
    mockUpdate.mockResolvedValue({ ...PARENT, name: 'Groceries' });
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('category-edit-p1')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('category-edit-p1'));
    await fireEvent.changeText(screen.getByTestId('category-edit-form-name'), 'Groceries');
    await fireEvent.press(screen.getByTestId('category-edit-form-submit'));

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('p1', {
        name: 'Groceries',
        parentId: null,
        icon: null,
      })
    );
  });
});

describe('deleting a category still in use', () => {
  it('reads the 409 in the member’s own words', async () => {
    mockList.mockResolvedValue([PARENT]);
    mockDelete.mockRejectedValue(
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
              detail: 'Referenced by a rule.',
            },
          },
        },
      })
    );
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('category-delete-p1')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('category-delete-p1'));
    await fireEvent.press(screen.getByTestId('category-delete-confirm-p1'));

    await waitFor(() =>
      expect(
        screen.getByText(
          'This category is used by a merchant rule. Remove or repoint that rule first.'
        )
      ).toBeTruthy()
    );
  });
});
