import React from 'react';
import { act, render, screen } from '@testing-library/react-native';
import { ImportPreviewSummary } from './ImportPreviewSummary';
import { useAuthStore } from '@stores/authStore';
import type { ImportPreviewResponse, MeResponse } from '@api/types';

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

function preview(overrides: Partial<ImportPreviewResponse> = {}): ImportPreviewResponse {
  return {
    id: 'b1',
    householdId: 'h1',
    accountId: 'a1',
    sourceKind: 'CSV',
    status: 'PREVIEW',
    originalFilename: 'statement-2026-09.csv',
    rowCount: 42,
    newCount: 39,
    duplicateCount: 2,
    fuzzyDuplicateCount: 0,
    invalidCount: 1,
    dateFormatCandidates: [],
    rows: [],
    warnings: [],
    createdAt: '2026-09-05T03:20:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  act(() => {
    useAuthStore.getState().setMe(ME);
  });
});

describe('ImportPreviewSummary', () => {
  it('shows all four verdict counts before anything is committed', () => {
    render(<ImportPreviewSummary preview={preview()} />);
    expect(screen.getByTestId('import-preview-summary-NEW')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-DUPLICATE')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-FUZZY_DUPLICATE')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-INVALID')).toBeTruthy();
    expect(screen.getByText('39')).toBeTruthy();
    expect(screen.getByText('New')).toBeTruthy();
    expect(screen.getByText('Rejected')).toBeTruthy();
  });

  it('names the file and states the row count, kind and status', () => {
    render(<ImportPreviewSummary preview={preview()} />);
    expect(screen.getByText('statement-2026-09.csv')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-rows').props.children.join('')).toBe(
      '42 rows · CSV · PREVIEW'
    );
  });

  it('falls back to a placeholder when the upload carried no filename', () => {
    render(<ImportPreviewSummary preview={preview({ originalFilename: null })} />);
    expect(screen.getByText('Uploaded file')).toBeTruthy();
  });

  it('renders each server warning as an alert', () => {
    render(
      <ImportPreviewSummary
        preview={preview({ warnings: ['A balance column was recognised but is not used.'] })}
      />
    );
    const warning = screen.getByTestId('import-preview-summary-warning-0');
    expect(warning.props.accessibilityRole).toBe('alert');
    expect(warning.props.children).toBe('A balance column was recognised but is not used.');
  });

  it('shows why a rejected row cannot be accepted', () => {
    render(
      <ImportPreviewSummary
        preview={preview({
          rows: [
            {
              ordinal: 7,
              date: null,
              description: null,
              merchantName: null,
              amount: null,
              currency: 'CAD',
              type: null,
              verdict: 'INVALID',
              reason: 'No amount could be read from this row.',
            },
            {
              ordinal: 8,
              date: '2026-09-03',
              description: 'GROCERY STORE',
              merchantName: 'Grocery Store',
              amount: '42.5000',
              currency: 'CAD',
              type: 'DEBIT',
              verdict: 'NEW',
              reason: null,
            },
          ],
        })}
      />
    );
    expect(screen.getByTestId('import-preview-summary-reason-7')).toBeTruthy();
    // A row that is fine has no reason line.
    expect(screen.queryByTestId('import-preview-summary-reason-8')).toBeNull();
  });
});
