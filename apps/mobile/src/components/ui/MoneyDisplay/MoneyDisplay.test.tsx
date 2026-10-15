import React from 'react';
import { render } from '@testing-library/react-native';
import { MoneyDisplay } from './MoneyDisplay';
import { useAuthStore } from '@stores/authStore';
import type { LocaleCode, MeResponse, UserPreferences } from '@api/types';

/**
 * `MoneyDisplay` reads the user's currency and locale from the real store —
 * the store is not mocked, so a regression that stops it consuming the
 * profile shows up here rather than passing against a stub.
 */
function signIn(currency: 'CAD' | 'USD', locale: LocaleCode) {
  const me: MeResponse = {
    userId: 'u1',
    email: 'a@b.co',
    displayName: 'A',
    householdId: 'h1',
    role: 'MEMBER',
    totpEnabled: false,
    currency,
    country: currency === 'CAD' ? 'CA' : 'US',
    timezone: 'America/Toronto',
    locale,
    onboardingCompleted: true,
  };
  const preferences: UserPreferences = {
    region: currency === 'CAD' ? 'CA' : 'US',
    locale,
    timezone: 'America/Toronto',
    onboardingCompleted: true,
  };
  useAuthStore.setState({ me, preferences });
}

function normalise(text: string): string {
  return text.replace(/[\u00a0\u202f]/g, ' ');
}

afterEach(() => {
  useAuthStore.setState({ me: null, preferences: null });
});

describe('MoneyDisplay', () => {
  it('renders a decimal string in the user’s currency', () => {
    signIn('CAD', 'en-CA');
    const { getByTestId } = render(<MoneyDisplay amount="1234.50" testID="amount" />);
    expect(normalise(getByTestId('amount').props.children)).toBe('$1,234.50');
  });

  it('renders the same amount differently for a USD user', () => {
    signIn('USD', 'en-US');
    const { getByTestId } = render(<MoneyDisplay amount="1234.50" testID="amount" />);
    expect(normalise(getByTestId('amount').props.children)).toBe('$1,234.50');
  });

  it('marks a foreign-currency row explicitly', () => {
    signIn('CAD', 'en-CA');
    const { getByTestId } = render(<MoneyDisplay amount="10.00" currency="USD" testID="amount" />);
    expect(normalise(getByTestId('amount').props.children)).toBe('US$10.00');
  });

  it('accepts a Money wrapper', () => {
    signIn('CAD', 'en-CA');
    const { getByTestId } = render(
      <MoneyDisplay amount={{ amount: '3.50', currency: 'CAD' }} testID="amount" />
    );
    expect(normalise(getByTestId('amount').props.children)).toBe('$3.50');
  });

  it('shows a placeholder rather than guessing a currency before the profile loads', () => {
    const { getByTestId } = render(<MoneyDisplay amount="1234.50" testID="amount" />);
    expect(getByTestId('amount').props.children).toBe('—');
  });

  // `/v1/auth/me` carries `locale` and `timezone` as well as
  // `/v1/me/preferences`, so one authenticated call is enough to format
  // correctly — the first screen after login must not render in the device
  // locale while preferences are still in flight.
  it('formats from /v1/auth/me alone, before preferences have loaded', () => {
    signIn('USD', 'en-US');
    useAuthStore.setState({ preferences: null });
    const { getByTestId } = render(<MoneyDisplay amount="1234.50" testID="amount" />);
    expect(normalise(getByTestId('amount').props.children)).toBe('$1,234.50');
  });

  it('colours by sign only when asked', () => {
    signIn('CAD', 'en-CA');
    const neutral = render(<MoneyDisplay amount="-5.00" testID="a" />).getByTestId('a');
    const coloured = render(<MoneyDisplay amount="-5.00" colorBySign testID="b" />).getByTestId(
      'b'
    );
    expect(JSON.stringify(neutral.props.style)).not.toEqual(JSON.stringify(coloured.props.style));
  });
});
