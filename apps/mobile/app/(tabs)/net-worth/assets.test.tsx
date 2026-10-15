import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import AssetsScreen from './assets';
import { createAsset, deleteAsset, fetchAssets, updateAsset } from '@api/networth';
import type { AssetResponse } from '@api/types';

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

const mockList = fetchAssets as jest.MockedFunction<typeof fetchAssets>;
const mockCreate = createAsset as jest.MockedFunction<typeof createAsset>;
const mockUpdate = updateAsset as jest.MockedFunction<typeof updateAsset>;
const mockDelete = deleteAsset as jest.MockedFunction<typeof deleteAsset>;

const ASSET: AssetResponse = {
  id: 'a1',
  householdId: 'h1',
  createdBy: 'u1',
  name: 'Everyday Chequing',
  assetType: 'CASH',
  value: '5230.5000',
  currency: 'CAD',
  accountId: null,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <AssetsScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists assets with value and currency', async () => {
  mockList.mockResolvedValue([ASSET]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('asset-row-a1')).toBeTruthy());
  expect(screen.getByTestId('asset-value-a1')).toBeTruthy();
});

it('creates an asset with the chosen type', async () => {
  mockList.mockResolvedValue([]);
  mockCreate.mockResolvedValue(ASSET);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('assets-new-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('assets-new-toggle'));
  await fireEvent.changeText(screen.getByTestId('assets-create-form-name'), 'Everyday Chequing');
  await fireEvent.press(screen.getByTestId('assets-create-form-type-INVESTMENT'));
  await fireEvent.changeText(screen.getByTestId('assets-create-form-value'), '5230.50');
  await fireEvent.press(screen.getByTestId('assets-create-form-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith({
      name: 'Everyday Chequing',
      assetType: 'INVESTMENT',
      value: '5230.50',
    })
  );
});

it('edits an asset without sending currency (not writable after creation)', async () => {
  mockList.mockResolvedValue([ASSET]);
  mockUpdate.mockResolvedValue({ ...ASSET, value: '5400.0000' });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('asset-edit-a1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('asset-edit-a1'));
  await fireEvent.changeText(screen.getByTestId('asset-edit-form-a1-value'), '5400.00');
  await fireEvent.press(screen.getByTestId('asset-edit-form-a1-submit'));

  await waitFor(() =>
    expect(mockUpdate).toHaveBeenCalledWith('a1', {
      name: 'Everyday Chequing',
      assetType: 'CASH',
      value: '5400.00',
      accountId: null,
    })
  );
});

it('edits an asset that is linked to a connected account without unlinking it', async () => {
  const linked: AssetResponse = { ...ASSET, id: 'a2', accountId: 'acct-1' };
  mockList.mockResolvedValue([linked]);
  mockUpdate.mockResolvedValue({ ...linked, value: '5400.0000' });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('asset-edit-a2')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('asset-edit-a2'));
  await fireEvent.changeText(screen.getByTestId('asset-edit-form-a2-value'), '5400.00');
  await fireEvent.press(screen.getByTestId('asset-edit-form-a2-submit'));

  // `AssetUpdateRequest` is a full replace: omitting `accountId` here would
  // silently sever the link (spec: omit/null means "leave unlinked"). The
  // edit form must carry the row's existing accountId forward.
  await waitFor(() =>
    expect(mockUpdate).toHaveBeenCalledWith('a2', {
      name: 'Everyday Chequing',
      assetType: 'CASH',
      value: '5400.00',
      accountId: 'acct-1',
    })
  );
});

it('lets a create form record a manually-entered asset in the household other currency', async () => {
  mockList.mockResolvedValue([]);
  mockCreate.mockResolvedValue(ASSET);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('assets-new-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('assets-new-toggle'));
  await fireEvent.changeText(screen.getByTestId('assets-create-form-name'), 'US Brokerage');
  await fireEvent.changeText(screen.getByTestId('assets-create-form-value'), '900.00');
  await fireEvent.press(screen.getByTestId('assets-create-form-currency-USD'));
  await fireEvent.press(screen.getByTestId('assets-create-form-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith({
      name: 'US Brokerage',
      assetType: 'CASH',
      value: '900.00',
      currency: 'USD',
    })
  );
});

it('requires a second press to delete', async () => {
  mockList.mockResolvedValue([ASSET]);
  mockDelete.mockResolvedValue(undefined);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('asset-delete-a1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('asset-delete-a1'));
  expect(mockDelete).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByTestId('asset-delete-confirm-a1'));
  await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('a1'));
});
