import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { router } from 'expo-router';
import { CategoriesRulesCard } from './CategoriesRulesCard';

describe('CategoriesRulesCard', () => {
  it('opens the Categories screen', async () => {
    await render(<CategoriesRulesCard />);
    await fireEvent.press(screen.getByTestId('settings-open-categories'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/categories');
  });

  it('opens the Merchant rules screen', async () => {
    await render(<CategoriesRulesCard />);
    await fireEvent.press(screen.getByTestId('settings-open-rules'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/rules');
  });
});
