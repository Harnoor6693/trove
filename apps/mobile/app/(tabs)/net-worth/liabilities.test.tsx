import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import LiabilitiesScreen from './liabilities';
import { createLiability, deleteLiability, fetchLiabilities, updateLiability } from '@api/networth';
import type { LiabilityResponse } from '@api/types';

jest.mock('@api/networth', () => ({
  fetchNetWorthSeries: jest.fn(),
  takeNetWorthSnapshot: jest.fn(),
  fetchAssets: jest.fn(),
  createAsset: jest.fn(),
  updateAsset: jest.fn(),
  deleteAsset: jest.fn(),
  fetchLiabilities: jest.fn(),
  createLiability: jest.fn(),
  updateLiability: jest.fn(),
  deleteLiability: jest.fn(),
}));

const mockList = fetchLiabilities as jest.MockedFunction<typeof fetchLiabilities>;
const mockCreate = createLiability as jest.MockedFunction<typeof createLiability>;
const mockUpdate = updateLiability as jest.MockedFunction<typeof updateLiability>;
const mockDelete = deleteLiability as jest.MockedFunction<typeof deleteLiability>;

const LIABILITY: LiabilityResponse = {
  id: 'l1',
  householdId: 'h1',
  createdBy: 'u1',
  name: 'Visa Credit Card',
  liabilityType: 'CREDIT_CARD',
  balance: '1250.0000',
  interestRate: '19.9900',
  currency: 'CAD',
  accountId: null,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <LiabilitiesScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists liabilities with balance and interest rate', async () => {
  mockList.mockResolvedValue([LIABILITY]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('liability-row-l1')).toBeTruthy());
  expect(screen.getByTestId('liability-balance-l1')).toBeTruthy();
  expect(screen.getByText(/19.9900% interest/)).toBeTruthy();
});

it('creates a liability, defaulting interest rate to zero when left blank', async () => {
  mockList.mockResolvedValue([]);
  mockCreate.mockResolvedValue(LIABILITY);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('liabilities-new-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('liabilities-new-toggle'));
  await fireEvent.changeText(
    screen.getByTestId('liabilities-create-form-name'),
    'Visa Credit Card'
  );
  await fireEvent.changeText(screen.getByTestId('liabilities-create-form-rate'), '');
  await fireEvent.changeText(screen.getByTestId('liabilities-create-form-balance'), '1250.00');
  await fireEvent.press(screen.getByTestId('liabilities-create-form-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith({
      name: 'Visa Credit Card',
      liabilityType: 'CREDIT_CARD',
      balance: '1250.00',
      interestRate: '0',
    })
  );
});

it('edits a liability without sending currency', async () => {
  mockList.mockResolvedValue([LIABILITY]);
  mockUpdate.mockResolvedValue({ ...LIABILITY, balance: '1100.0000' });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('liability-edit-l1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('liability-edit-l1'));
  await fireEvent.changeText(screen.getByTestId('liability-edit-form-l1-balance'), '1100.00');
  await fireEvent.press(screen.getByTestId('liability-edit-form-l1-submit'));

  await waitFor(() =>
    expect(mockUpdate).toHaveBeenCalledWith('l1', {
      name: 'Visa Credit Card',
      liabilityType: 'CREDIT_CARD',
      balance: '1100.00',
      interestRate: '19.9900',
      accountId: null,
    })
  );
});

it('edits a liability that is linked to a connected account without unlinking it', async () => {
  const linked: LiabilityResponse = { ...LIABILITY, id: 'l2', accountId: 'acct-9' };
  mockList.mockResolvedValue([linked]);
  mockUpdate.mockResolvedValue({ ...linked, balance: '1100.0000' });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('liability-edit-l2')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('liability-edit-l2'));
  await fireEvent.changeText(screen.getByTestId('liability-edit-form-l2-balance'), '1100.00');
  await fireEvent.press(screen.getByTestId('liability-edit-form-l2-submit'));

  // `LiabilityUpdateRequest` is a full replace: omitting `accountId` would
  // silently sever the connected-account link (spec: omit/null means "leave
  // unlinked"). The edit form must carry the row's existing accountId forward.
  await waitFor(() =>
    expect(mockUpdate).toHaveBeenCalledWith('l2', {
      name: 'Visa Credit Card',
      liabilityType: 'CREDIT_CARD',
      balance: '1100.00',
      interestRate: '19.9900',
      accountId: 'acct-9',
    })
  );
});

it('lets a create form record a manually-entered liability in the household other currency', async () => {
  mockList.mockResolvedValue([]);
  mockCreate.mockResolvedValue(LIABILITY);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('liabilities-new-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('liabilities-new-toggle'));
  await fireEvent.changeText(screen.getByTestId('liabilities-create-form-name'), 'US Card');
  await fireEvent.changeText(screen.getByTestId('liabilities-create-form-rate'), '');
  await fireEvent.changeText(screen.getByTestId('liabilities-create-form-balance'), '300.00');
  await fireEvent.press(screen.getByTestId('liabilities-create-form-currency-USD'));
  await fireEvent.press(screen.getByTestId('liabilities-create-form-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith({
      name: 'US Card',
      liabilityType: 'CREDIT_CARD',
      balance: '300.00',
      interestRate: '0',
      currency: 'USD',
    })
  );
});

it('requires a second press to delete', async () => {
  mockList.mockResolvedValue([LIABILITY]);
  mockDelete.mockResolvedValue(undefined);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('liability-delete-l1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('liability-delete-l1'));
  expect(mockDelete).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByTestId('liability-delete-confirm-l1'));
  await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('l1'));
});
