import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import TaxLinesScreen from './lines';
import { fetchTaxLines, setTaxLine } from '@api/taxexport';
import type { TaxLineMappingResponse } from '@api/types';

jest.mock('@api/taxexport', () => ({
  fetchTaxLines: jest.fn(),
  setTaxLine: jest.fn(),
  fetchTaxExportRuns: jest.fn(),
  createTaxExportRun: jest.fn(),
  fetchTaxExportRun: jest.fn(),
  deleteTaxExportRun: jest.fn(),
  fetchTaxExportRunFile: jest.fn(),
}));

const mockLines = fetchTaxLines as jest.MockedFunction<typeof fetchTaxLines>;
const mockSetLine = setTaxLine as jest.MockedFunction<typeof setTaxLine>;

const BLANK_LINE: TaxLineMappingResponse = {
  categoryId: 'c1',
  categorySlug: 'business.software',
  categoryName: 'Software',
  jurisdiction: 'CA',
  lineCode: '',
  lineLabel: '',
  deductible: false,
  overridden: false,
};

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <TaxLinesScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('shows a category with no mapping yet rather than dropping it', async () => {
  mockLines.mockResolvedValue([BLANK_LINE]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-line-row-c1')).toBeTruthy());
  expect(screen.getByText('No line mapped yet.')).toBeTruthy();
});

it('sets an override for one category', async () => {
  mockLines.mockResolvedValue([BLANK_LINE]);
  mockSetLine.mockResolvedValue({
    ...BLANK_LINE,
    lineCode: '8810',
    lineLabel: 'Office',
    overridden: true,
  });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-line-edit-c1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-line-edit-c1'));
  await fireEvent.changeText(screen.getByTestId('tax-line-form-c1-code'), '8810');
  await fireEvent.changeText(screen.getByTestId('tax-line-form-c1-label'), 'Office');
  await fireEvent.press(screen.getByTestId('tax-line-form-c1-submit'));

  await waitFor(() =>
    expect(mockSetLine).toHaveBeenCalledWith('c1', {
      jurisdiction: 'CA',
      lineCode: '8810',
      lineLabel: 'Office',
      deductible: false,
    })
  );
});

it('shows the saved override on screen once the invalidated list refetches', async () => {
  // The screen reads with `useTaxLines()` (no argument, cache key
  // `['tax-export','lines','default']`) while the row being saved carries a
  // real `jurisdiction` (`'CA'`). A regression that invalidates only the
  // jurisdiction-keyed query would never refetch this list, so the row would
  // still show the old code with no error — this test would fail on that
  // regression because `fetchTaxLines` would only ever be called once.
  mockLines
    .mockResolvedValueOnce([BLANK_LINE])
    .mockResolvedValueOnce([
      { ...BLANK_LINE, lineCode: '8810', lineLabel: 'Office', overridden: true },
    ]);
  mockSetLine.mockResolvedValue({
    ...BLANK_LINE,
    lineCode: '8810',
    lineLabel: 'Office',
    overridden: true,
  });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-line-edit-c1')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-line-edit-c1'));
  await fireEvent.changeText(screen.getByTestId('tax-line-form-c1-code'), '8810');
  await fireEvent.changeText(screen.getByTestId('tax-line-form-c1-label'), 'Office');
  await fireEvent.press(screen.getByTestId('tax-line-form-c1-submit'));

  await waitFor(() => expect(mockLines).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(screen.getByText('8810 — Office')).toBeTruthy());
  expect(screen.getByText('Your override')).toBeTruthy();
});
