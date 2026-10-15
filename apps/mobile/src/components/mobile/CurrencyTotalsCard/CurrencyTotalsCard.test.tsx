import React from 'react';
import { act, render, screen } from '@testing-library/react-native';
import { CurrencyTotalsCard } from './CurrencyTotalsCard';
import { useAuthStore } from '@stores/authStore';
import type { DashboardCurrencyTotals, MeResponse } from '@api/types';

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: false,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Toronto',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const USD_TOTALS: DashboardCurrencyTotals = {
  currency: 'USD',
  spentToday: '40.00',
  transactionsToday: 1,
  spentThisMonth: '90.00',
  incomeThisMonth: '0.00',
  netThisMonth: '-90.00',
};

beforeEach(() => {
  act(() => {
    useAuthStore.getState().setMe(ME);
  });
});

describe('CurrencyTotalsCard', () => {
  it('labels the card with its own currency code', () => {
    render(<CurrencyTotalsCard totals={USD_TOTALS} />);
    expect(screen.getByTestId('today-currency-USD-code').props.children).toBe('USD');
  });

  it('renders every figure the schema carries', () => {
    render(<CurrencyTotalsCard totals={USD_TOTALS} />);
    expect(screen.getByTestId('today-currency-USD-spent-today')).toBeTruthy();
    expect(screen.getByTestId('today-currency-USD-spent-month')).toBeTruthy();
    expect(screen.getByTestId('today-currency-USD-income-month')).toBeTruthy();
    expect(screen.getByTestId('today-currency-USD-net-month')).toBeTruthy();
    expect(screen.getByTestId('today-currency-USD-count').props.children).toEqual([
      '1',
      ' transactions today',
    ]);
  });

  it('formats a USD subtotal in USD even though the member reports in CAD', () => {
    render(<CurrencyTotalsCard totals={USD_TOTALS} />);
    const rendered = screen.getByTestId('today-currency-USD-spent-today').props.children as string;
    // The formatter was given the entry's own currency; an en-CA reader sees
    // the USD marker rather than a bare dollar sign that would read as CAD.
    expect(rendered).toContain('40.00');
    expect(rendered).toContain('US');
  });

  it('announces the card for a screen reader by its currency', () => {
    render(<CurrencyTotalsCard totals={USD_TOTALS} />);
    expect(screen.getByLabelText('Totals in USD')).toBeTruthy();
  });
});
