import React from 'react';
import { act, render, screen } from '@testing-library/react-native';
import { SyncLogRow } from './SyncLogRow';
import { useAuthStore } from '@stores/authStore';
import type { MeResponse, SyncLogResponse } from '@api/types';

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

function log(overrides: Partial<SyncLogResponse> = {}): SyncLogResponse {
  return {
    id: 'l1',
    householdId: 'h1',
    accountId: 'a1',
    sourceKind: 'SIMPLEFIN',
    status: 'SUCCESS',
    transactionsFetched: 12,
    transactionsNew: 12,
    transactionsUpdated: 0,
    transactionsSkipped: 0,
    transactionsFailed: 0,
    errorMessage: null,
    startedAt: '2026-09-04T14:00:00Z',
    completedAt: '2026-09-04T14:00:03Z',
    ...overrides,
  };
}

beforeEach(() => {
  act(() => {
    useAuthStore.getState().setMe(ME);
  });
});

describe('SyncLogRow', () => {
  it('states the run outcome as a word', () => {
    render(<SyncLogRow log={log({ status: 'SUCCESS' })} />);
    expect(screen.getByText('Succeeded')).toBeTruthy();
  });

  it('renders a FAILED run’s reason, which is the point of the history', () => {
    render(
      <SyncLogRow log={log({ status: 'FAILED', errorMessage: 'The bridge did not answer.' })} />
    );
    expect(screen.getByText('Failed')).toBeTruthy();
    const alert = screen.getByTestId('sync-log-error-l1');
    expect(alert.props.children).toBe('The bridge did not answer.');
    expect(alert.props.accessibilityRole).toBe('alert');
  });

  it('renders a PARTIAL_FAILURE with its non-zero refused count', () => {
    render(
      <SyncLogRow
        log={{
          ...log(),
          status: 'PARTIAL_FAILURE',
          transactionsFailed: 2,
          errorMessage: 'Two rows had no amount.',
        }}
      />
    );
    expect(screen.getByText('Partly failed')).toBeTruthy();
    expect(screen.getByTestId('sync-log-error-l1')).toBeTruthy();
  });

  it('shows no reason row on a clean run', () => {
    render(<SyncLogRow log={log()} />);
    expect(screen.queryByTestId('sync-log-error-l1')).toBeNull();
  });

  it('announces the run for a screen reader', () => {
    render(<SyncLogRow log={log()} />);
    expect(screen.getByLabelText(/Succeeded, .*12 new/)).toBeTruthy();
  });
});
