import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { router } from 'expo-router';
import { AdminCard } from './AdminCard';
import { useAuthStore } from '@stores/authStore';
import type { MeResponse } from '@api/types';

const mockPush = router.push as jest.Mock;

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: false,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Halifax',
  locale: 'en-CA',
  onboardingCompleted: true,
};

beforeEach(() => {
  jest.clearAllMocks();
  // The store is real — this card's whole behaviour is one selector on it.
  useAuthStore.setState({ me: null });
});

it('renders nothing before the server has said what role this account has', async () => {
  await render(<AdminCard />);
  expect(screen.queryByTestId('settings-admin-section')).toBeNull();
});

it('renders nothing for a MEMBER', async () => {
  useAuthStore.setState({ me: { ...ME, role: 'MEMBER' } });
  await render(<AdminCard />);
  expect(screen.queryByTestId('settings-admin-section')).toBeNull();
});

it('shows the console entry point to an ADMIN and navigates to the stack, not a tab', async () => {
  useAuthStore.setState({ me: ME });
  await render(<AdminCard />);
  expect(screen.getByTestId('settings-admin-section')).toBeTruthy();
  await fireEvent.press(screen.getByTestId('settings-admin-console-btn'));
  expect(mockPush).toHaveBeenCalledWith('/(tabs)/admin');
});

it('reacts to the role arriving after first paint', async () => {
  await render(<AdminCard />);
  expect(screen.queryByTestId('settings-admin-section')).toBeNull();
  // `GET /v1/auth/me` resolves; the store is the single source of the role.
  await act(() => useAuthStore.setState({ me: ME }));
  expect(screen.getByTestId('settings-admin-section')).toBeTruthy();
});
