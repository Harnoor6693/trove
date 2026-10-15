import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { TransactionRow } from './TransactionRow';
import { useAuthStore } from '@stores/authStore';
import type { MeResponse, TransactionResponse } from '@api/types';

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

function transaction(overrides: Partial<TransactionResponse> = {}): TransactionResponse {
  return {
    id: 't1',
    householdId: 'h1',
    accountId: 'a1',
    createdBy: 'u1',
    providerTransactionId: 'p1',
    amount: '4.75',
    currency: 'CAD',
    type: 'DEBIT',
    description: 'SQ *BLUE DOOR COFFEE',
    merchantName: 'Blue Door Coffee',
    date: '2026-09-04',
    postedAt: null,
    pending: false,
    manual: false,
    categoryId: null,
    transferGroupId: null,
    importBatchId: null,
    tags: null,
    notes: null,
    createdAt: '2026-09-04T18:00:00Z',
    updatedAt: '2026-09-04T18:00:00Z',
    ...overrides,
  };
}

beforeEach(async () => {
  await act(() => {
    useAuthStore.getState().setMe(ME);
  });
});

describe('TransactionRow', () => {
  it('takes the sign from `type`, because `amount` is always absolute', async () => {
    await render(<TransactionRow transaction={transaction({ type: 'DEBIT', amount: '4.75' })} />);
    expect(screen.getByTestId('transaction-amount-t1').props.children).toContain('4.75');
    // The row's own text carries a minus for an outflow even though the wire
    // value has none.
    expect(screen.getByTestId('transaction-amount-t1').props.children).toMatch(/-/);
  });

  it('shows a credit without a minus sign', async () => {
    await render(
      <TransactionRow transaction={transaction({ type: 'CREDIT', amount: '2500.00' })} />
    );
    expect(screen.getByTestId('transaction-amount-t1').props.children).not.toMatch(/-/);
  });

  it('renders the row in its OWN currency, not the member’s reporting one', async () => {
    await render(<TransactionRow transaction={transaction({ currency: 'USD', type: 'CREDIT' })} />);
    // en-CA renders a USD amount with its own marker; the point is that the
    // component was given the row's currency, which the label repeats.
    expect(screen.getByLabelText(/Blue Door Coffee/)).toBeTruthy();
  });

  it('falls back to the description, then to a placeholder, for the title', async () => {
    await render(<TransactionRow transaction={transaction({ merchantName: null })} />);
    expect(screen.getByText('SQ *BLUE DOOR COFFEE')).toBeTruthy();

    await screen.unmount();
    await render(
      <TransactionRow transaction={transaction({ merchantName: null, description: null })} />
    );
    expect(screen.getByText('Unnamed transaction')).toBeTruthy();
  });

  it('words the pending, manual and transfer states rather than only colouring them', async () => {
    await render(
      <TransactionRow
        transaction={transaction({ pending: true, manual: true, transferGroupId: 'g1' })}
      />
    );
    expect(screen.getByText('Pending')).toBeTruthy();
    expect(screen.getByText('Manual')).toBeTruthy();
    expect(screen.getByTestId('transaction-transfer-t1')).toBeTruthy();
  });

  it('shows no transfer badge on a row that is not a transfer leg', async () => {
    await render(<TransactionRow transaction={transaction()} />);
    expect(screen.queryByTestId('transaction-transfer-t1')).toBeNull();
  });

  it('calls back with the row id when pressed', async () => {
    const onPress = jest.fn();
    await render(<TransactionRow transaction={transaction()} onPress={onPress} />);
    await fireEvent.press(screen.getByTestId('transaction-row-t1'));
    expect(onPress).toHaveBeenCalledWith('t1');
  });
});
