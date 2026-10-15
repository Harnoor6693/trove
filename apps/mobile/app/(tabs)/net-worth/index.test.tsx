import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import NetWorthScreen from './index';
import { fetchNetWorthSeries, takeNetWorthSnapshot } from '@api/networth';
import type { NetWorthSeriesResponse } from '@api/types';

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

const mockSeries = fetchNetWorthSeries as jest.MockedFunction<typeof fetchNetWorthSeries>;
const mockSnapshot = takeNetWorthSnapshot as jest.MockedFunction<typeof takeNetWorthSnapshot>;

const SERIES: NetWorthSeriesResponse = {
  primaryCurrency: 'CAD',
  series: [
    {
      currency: 'CAD',
      points: [
        {
          snapshotDate: '2026-09-04',
          totalAssets: '5230.5000',
          totalLiabilities: '1250.0000',
          netWorth: '3980.5000',
        },
      ],
    },
    {
      currency: 'USD',
      points: [
        {
          snapshotDate: '2026-09-04',
          totalAssets: '900.0000',
          totalLiabilities: '0.0000',
          netWorth: '900.0000',
        },
      ],
    },
  ],
};

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <NetWorthScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('renders one series card per currency and never a combined total', async () => {
  mockSeries.mockResolvedValue(SERIES);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('net-worth-series-CAD')).toBeTruthy());
  expect(screen.getByTestId('net-worth-series-USD')).toBeTruthy();
  // The absence of any combined/blended figure on screen at all.
  expect(screen.queryByTestId('net-worth-series-total')).toBeNull();
  expect(screen.queryByText(/combined/i)).toBeNull();
});

it('shows the chart once the series has loaded', async () => {
  mockSeries.mockResolvedValue(SERIES);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('net-worth-chart')).toBeTruthy());
});

it('plots each currency by its own snapshotDate, not by array position, in its own chart', async () => {
  // Two currencies with different history lengths and different dates — a
  // USD account added partway through the range (NetWorthSeries.points is
  // "empty when this currency has no snapshots in range").
  mockSeries.mockResolvedValue({
    primaryCurrency: 'CAD',
    series: [
      {
        currency: 'CAD',
        points: [
          {
            snapshotDate: '2026-08-01',
            totalAssets: '1.0000',
            totalLiabilities: '0.0000',
            netWorth: '1.0000',
          },
          {
            snapshotDate: '2026-09-04',
            totalAssets: '2.0000',
            totalLiabilities: '0.0000',
            netWorth: '2.0000',
          },
        ],
      },
      {
        currency: 'USD',
        points: [
          {
            snapshotDate: '2026-09-04',
            totalAssets: '3.0000',
            totalLiabilities: '0.0000',
            netWorth: '3.0000',
          },
        ],
      },
    ],
  });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('net-worth-chart-CAD')).toBeTruthy());
  expect(screen.getByTestId('net-worth-chart-USD')).toBeTruthy();

  const lines = screen.getAllByTestId('victory-line');
  const cadLine = lines[0].props as { data: { x: number; y: number }[] };
  const usdLine = lines[1].props as { data: { x: number; y: number }[] };

  // x is the real day timestamp, not the array index — the CAD series'
  // second point and the USD series' only point share the same real date
  // and must therefore share the same x, even though their array indices
  // (1 vs 0) differ.
  expect(cadLine.data[0].x).toBe(new Date('2026-08-01T00:00:00').getTime());
  expect(cadLine.data[1].x).toBe(new Date('2026-09-04T00:00:00').getTime());
  expect(usdLine.data[0].x).toBe(new Date('2026-09-04T00:00:00').getTime());
  expect(cadLine.data[1].x).toBe(usdLine.data[0].x);
});

it('is empty when there are no snapshots in range', async () => {
  mockSeries.mockResolvedValue({ primaryCurrency: 'CAD', series: [] });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('net-worth-series-empty')).toBeTruthy());
});

it('takes a snapshot and shows a confirmation', async () => {
  mockSeries.mockResolvedValue({ primaryCurrency: 'CAD', series: [] });
  mockSnapshot.mockResolvedValue([]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('net-worth-snapshot-btn')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('net-worth-snapshot-btn'));

  await waitFor(() => expect(mockSnapshot).toHaveBeenCalled());
  await waitFor(() => expect(screen.getByTestId('net-worth-snapshot-success')).toBeTruthy());
});

it('changes range on chip press', async () => {
  mockSeries.mockResolvedValue({ primaryCurrency: 'CAD', series: [] });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('net-worth-range-3M')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('net-worth-range-3M'));

  await waitFor(() => expect(mockSeries).toHaveBeenCalledWith('3M'));
});

it('labels the time axis with the local calendar day (B5-09; Jest runs in Pacific/Auckland)', async () => {
  {
    mockSeries.mockResolvedValue({
      primaryCurrency: 'CAD',
      series: [
        {
          currency: 'CAD',
          points: [
            {
              snapshotDate: '2026-10-05',
              totalAssets: '3.0000',
              totalLiabilities: '0.0000',
              netWorth: '3.0000',
            },
            {
              snapshotDate: '2026-10-06',
              totalAssets: '4.0000',
              totalLiabilities: '0.0000',
              netWorth: '4.0000',
            },
          ],
        },
      ],
    });
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('net-worth-chart-CAD')).toBeTruthy());

    const timeAxis = screen
      .getAllByTestId('victory-axis')
      .find((axis) => !(axis.props as { dependentAxis?: boolean }).dependentAxis)!;
    const tickFormat = (timeAxis.props as { tickFormat: (t: number) => string }).tickFormat;
    expect(tickFormat(new Date('2026-10-05T00:00:00').getTime())).toMatch(/\b5\b/);
  }
});
