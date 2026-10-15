import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccountCard } from './AccountCard';
import { useAuthStore } from '@stores/authStore';
import type { AccountResponse, MeResponse } from '@api/types';

/**
 * The store is real, as everywhere else in this suite: `useUserFormat` reads
 * the member's currency and locale out of it, and a mocked store would let a
 * component pass while formatting money in a currency the member does not use.
 */
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

function account(overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id: 'a1',
    householdId: 'h1',
    createdBy: 'u1',
    sourceKind: 'SIMPLEFIN',
    displayName: 'Everyday Chequing',
    institution: 'Example Credit Union',
    accountType: 'CHECKING',
    currency: 'CAD',
    syncStatus: 'OK',
    lastSyncedAt: '2026-09-04T14:00:00Z',
    errorMessage: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
    ...overrides,
  };
}

beforeEach(async () => {
  await act(() => {
    useAuthStore.getState().setMe(ME);
  });
});

describe('AccountCard', () => {
  it('shows the institution, the type and the account’s OWN currency', async () => {
    await render(<AccountCard account={account({ currency: 'USD' })} />);
    // One meta line: institution · type · currency · source.
    expect(
      screen.getByText('Example Credit Union · Chequing · USD · Bank connection')
    ).toBeTruthy();
    // The account's currency, not the member's reporting one (CAD above).
  });

  it('states the sync status in words, not only in colour', async () => {
    await render(<AccountCard account={account({ syncStatus: 'ERROR' })} />);
    expect(screen.getByTestId('account-status-a1')).toBeTruthy();
    expect(screen.getByText('Sync failed')).toBeTruthy();
  });

  it('says "never synced" rather than showing a blank date', async () => {
    await render(<AccountCard account={account({ lastSyncedAt: null })} />);
    expect(screen.getByTestId('account-synced-a1').props.children).toBe('Never synced');
  });

  it('renders the last failure’s reason as an alert', async () => {
    await render(<AccountCard account={account({ errorMessage: 'The bridge did not answer.' })} />);
    const alert = screen.getByTestId('account-error-a1');
    expect(alert.props.children).toBe('The bridge did not answer.');
    expect(alert.props.accessibilityRole).toBe('alert');
  });

  it('hides the error row when there is no failure to report', async () => {
    await render(<AccountCard account={account()} />);
    expect(screen.queryByTestId('account-error-a1')).toBeNull();
  });

  it('carries an accessible label naming the account, type, currency and state', async () => {
    await render(<AccountCard account={account()} />);
    expect(screen.getByLabelText('Everyday Chequing, Chequing, CAD, Up to date')).toBeTruthy();
  });

  it('calls back with the account id when pressed', async () => {
    const onPress = jest.fn();
    await render(<AccountCard account={account()} onPress={onPress} />);
    await fireEvent.press(screen.getByTestId('account-row-a1'));
    expect(onPress).toHaveBeenCalledWith('a1');
  });

  it('is inert when no handler was given', async () => {
    await render(<AccountCard account={account()} />);
    expect(screen.getByTestId('account-row-a1').props.accessibilityState.disabled).toBe(true);
  });
});
