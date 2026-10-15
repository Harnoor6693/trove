import { act, renderHook } from '@testing-library/react-native';
import { useAuthStore } from '@stores/authStore';
import type { MeResponse, UserPreferences } from '@api/types';
import { createUserFormat, parseDecimal, UNKNOWN_VALUE, useUserFormat } from './useUserFormat';

/**
 * Formatting follows the *user's* currency, locale and timezone — read from
 * `/v1/auth/me` and `/v1/me/preferences` (§4.2) — not the device's, and never
 * a hardcoded CAD. Amounts arrive as decimal strings (§5.4).
 *
 * Assertions normalise NBSP/narrow-NBSP because ICU uses them as currency and
 * group separators in some locales, and the exact codepoint varies by ICU
 * version.
 */
function normalise(text: string): string {
  // NBSP (U+00A0) and narrow NBSP (U+202F) — ICU uses them as currency and
  // group separators, and which one it picks varies by ICU version.
  return text.replace(/[\u00a0\u202f]/g, ' ');
}

const CANADIAN = createUserFormat({
  currency: 'CAD',
  locale: 'en-CA',
  timeZone: 'America/Toronto',
});
const AMERICAN = createUserFormat({
  currency: 'USD',
  locale: 'en-US',
  timeZone: 'America/New_York',
});

describe('money', () => {
  it('formats a CAD amount for a Canadian user', () => {
    expect(normalise(CANADIAN.money('1234.50'))).toBe('$1,234.50');
  });

  it('formats a USD amount for an American user', () => {
    expect(normalise(AMERICAN.money('1234.50'))).toBe('$1,234.50');
  });

  // The two currencies are both first-class and must stay distinguishable:
  // a USD row shown to a CAD user, and vice versa, must not read as "$".
  it('disambiguates USD shown to a Canadian user', () => {
    expect(normalise(CANADIAN.money('1234.50', { currency: 'USD' }))).toBe('US$1,234.50');
  });

  it('disambiguates CAD shown to an American user', () => {
    expect(normalise(AMERICAN.money('1234.50', { currency: 'CAD' }))).toBe('CA$1,234.50');
  });

  it('follows a non-English locale for the same currency', () => {
    const quebecois = createUserFormat({
      currency: 'CAD',
      locale: 'fr-CA',
      timeZone: 'America/Toronto',
    });
    expect(normalise(quebecois.money('1234.50'))).toBe('1 234,50 $');
  });

  it('formats a Money wrapper, honouring the currency it carries', () => {
    expect(normalise(CANADIAN.money({ amount: '9.99', currency: 'USD' }))).toBe('US$9.99');
  });

  it('renders negatives with the locale sign placement', () => {
    expect(normalise(CANADIAN.money('-12.34'))).toBe('-$12.34');
  });

  it('can hide cents for compact surfaces', () => {
    expect(normalise(CANADIAN.money('1234.50', { hideCents: true }))).toBe('$1,235');
  });

  it('can force a leading + on positive amounts', () => {
    expect(normalise(CANADIAN.money('12.34', { signed: true }))).toBe('+$12.34');
    expect(normalise(CANADIAN.money('-12.34', { signed: true }))).toBe('-$12.34');
  });

  it('keeps decimal-string precision rather than rounding through cents', () => {
    expect(normalise(CANADIAN.money('0.05'))).toBe('$0.05');
    expect(normalise(CANADIAN.money('1000000.01'))).toBe('$1,000,000.01');
  });

  it('renders a placeholder — never a hardcoded currency — before the profile loads', () => {
    const unknown = createUserFormat({ currency: null, locale: 'en-CA', timeZone: 'UTC' });
    expect(unknown.money('12.34')).toBe(UNKNOWN_VALUE);
  });

  it.each([null, undefined, '', 'not-a-number'])('renders a placeholder for %p', (value) => {
    expect(CANADIAN.money(value as string | null | undefined)).toBe(UNKNOWN_VALUE);
  });
});

describe('date', () => {
  it('formats a calendar date as the literal day the server sent', () => {
    // `2026-09-03` is a LocalDate. Parsing it as UTC midnight and rendering
    // it in America/Toronto would show 2 September.
    expect(CANADIAN.date('2026-09-03', 'medium')).toBe('Sep 3, 2026');
    expect(AMERICAN.date('2026-09-03', 'medium')).toBe('Sep 3, 2026');
  });

  it('renders an instant in the user’s timezone', () => {
    expect(normalise(CANADIAN.date('2026-09-03T02:30:00Z', 'dateTime'))).toMatch(/Sep 2, 2026/);
    expect(normalise(AMERICAN.date('2026-09-03T02:30:00Z', 'dateTime'))).toMatch(/Sep 2, 2026/);
  });

  it('renders the same instant differently across zones', () => {
    const tokyo = createUserFormat({ currency: 'USD', locale: 'en-US', timeZone: 'Asia/Tokyo' });
    expect(tokyo.date('2026-09-03T02:30:00Z', 'dateTime')).toMatch(/Sep 3, 2026/);
  });

  it.each([null, undefined, '', 'not-a-date'])('renders a placeholder for %p', (value) => {
    expect(CANADIAN.date(value as string | null | undefined)).toBe(UNKNOWN_VALUE);
  });
});

describe('number', () => {
  it('groups digits in the user’s locale', () => {
    expect(normalise(CANADIAN.number(1234567))).toBe('1,234,567');
    expect(
      normalise(
        createUserFormat({ currency: 'CAD', locale: 'fr-CA', timeZone: 'UTC' }).number(1234567)
      )
    ).toBe('1 234 567');
  });
});

describe('parseDecimal', () => {
  it.each([
    ['12.34', 12.34],
    ['-0.01', -0.01],
    ['0', 0],
  ])('parses %p', (input, expected) => {
    expect(parseDecimal(input)).toBe(expected);
  });

  it.each([null, undefined, '', '   ', 'abc'])('rejects %p', (input) => {
    expect(parseDecimal(input as string | null | undefined)).toBeNull();
  });
});

describe('parseDecimal is strict about what counts as an amount', () => {
  // `Number('')` and `Number('  ')` are both 0: a blank amount that slipped
  // through would render as a confident $0.00 balance. `trove-core` only ever
  // emits a plain decimal literal (§5.4), so nothing else is accepted.
  it.each(['1e3', '0x1f', 'Infinity', '-Infinity', 'NaN', '+12.34', '12.', '.5', '1,234.50'])(
    'rejects %p rather than coercing it',
    (input) => {
      expect(parseDecimal(input)).toBeNull();
    }
  );

  it('renders the placeholder — never a zero balance — for a blank amount', () => {
    expect(CANADIAN.money('')).toBe(UNKNOWN_VALUE);
    expect(CANADIAN.money('   ')).toBe(UNKNOWN_VALUE);
  });

  it('tolerates surrounding whitespace on an otherwise valid literal', () => {
    expect(parseDecimal(' 12.34 ')).toBe(12.34);
  });
});

/**
 * The hook's job on top of `createUserFormat` is choosing *where* the three
 * inputs come from. `/v1/me/preferences` is the write surface, so an edit in
 * Settings must show immediately; but `GET /v1/auth/me` carries `locale` and
 * `timezone` too (§4a contract point 1), which is what makes formatting
 * correct from the very first authenticated call — before preferences have
 * loaded, and on an unlock where only `me` has answered so far.
 */
describe('useUserFormat source precedence', () => {
  const ME: MeResponse = {
    userId: 'u1',
    email: 'a@b.co',
    displayName: 'A',
    householdId: 'h1',
    role: 'MEMBER',
    totpEnabled: false,
    currency: 'USD',
    country: 'US',
    timezone: 'America/New_York',
    locale: 'en-US',
    onboardingCompleted: true,
  };

  const PREFERENCES: UserPreferences = {
    region: 'BC',
    locale: 'en-CA',
    timezone: 'America/Vancouver',
    onboardingCompleted: true,
  };

  afterEach(async () => {
    // This `afterEach` runs before the library's own unmount hook, so the
    // hook container is still mounted and the store write re-renders it —
    // wrap it, or React logs "not wrapped in act(...)" for every case here.
    await act(() => {
      useAuthStore.setState({ me: null, preferences: null });
    });
  });

  it('falls back to the /v1/auth/me fields when preferences have not loaded', async () => {
    await act(() => useAuthStore.setState({ me: ME, preferences: null }));
    const { result } = await renderHook(() => useUserFormat());

    expect(result.current.currency).toBe('USD');
    expect(result.current.locale).toBe('en-US');
    expect(result.current.timeZone).toBe('America/New_York');
    // Proof it is actually used, not merely reported: 02:30Z is the previous
    // evening in New York.
    expect(result.current.date('2026-09-03T02:30:00Z', 'dateTime')).toMatch(/Sep 2, 2026/);
  });

  it('prefers /v1/me/preferences over /v1/auth/me once both have loaded', async () => {
    await act(() => useAuthStore.setState({ me: ME, preferences: PREFERENCES }));
    const { result } = await renderHook(() => useUserFormat());

    expect(result.current.locale).toBe('en-CA');
    expect(result.current.timeZone).toBe('America/Vancouver');
    // Currency is identity-only: it lives on `me` and preferences cannot move it.
    expect(result.current.currency).toBe('USD');
  });

  it('takes the timezone from preferences even when the locale comes from me', async () => {
    // The two are selected independently, so a partial preferences payload
    // must not drag the other field back to the `me` value.
    await act(() =>
      useAuthStore.setState({
        me: ME,
        preferences: { ...PREFERENCES, locale: undefined } as unknown as UserPreferences,
      })
    );
    const { result } = await renderHook(() => useUserFormat());

    expect(result.current.locale).toBe('en-US');
    expect(result.current.timeZone).toBe('America/Vancouver');
  });

  it('renders the placeholder rather than a guessed currency before me answers', async () => {
    await act(() => useAuthStore.setState({ me: null, preferences: null }));
    const { result } = await renderHook(() => useUserFormat());

    expect(result.current.currency).toBeNull();
    expect(result.current.money('12.34')).toBe(UNKNOWN_VALUE);
    // Dates still render: they need a locale and a zone, and the device has both.
    expect(result.current.date('2026-09-03')).not.toBe(UNKNOWN_VALUE);
  });
});
