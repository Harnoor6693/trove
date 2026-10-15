import React from 'react';
import { Linking } from 'react-native';
import { act, fireEvent, render, waitFor, within } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/queryClient';
import * as SecureStore from 'expo-secure-store';
import { router } from 'expo-router';
import SettingsScreen from './settings';
import { changePassword, disableTotp } from '@api/auth';
import { fetchMe, fetchSecurityEvents, updateMe } from '@api/me';
import { fetchPreferences, updatePreferences } from '@api/preferences';
import { fetchServerMeta } from '@api/meta';
import { StorageKeys } from '@api/secureStorage';
import { useAuthStore } from '@stores/authStore';
import { useUiStore } from '@stores/uiStore';
import type { MeResponse, UserPreferences } from '@api/types';

/**
 * The stores are **not** mocked here.
 *
 * The suite this replaces `jest.mock()`-ed both `authStore` and `uiStore` with
 * a function that synchronously ran the selector against a plain object, so it
 * never touched `useSyncExternalStore` and could not observe the
 * new-object-every-call selector bug it was nominally covering (mobile
 * inventory §4). Only the `src/api/*` modules are stubbed; every store read,
 * write and re-render below is real.
 */

jest.mock('@api/auth', () => ({
  changePassword: jest.fn(() => Promise.resolve()),
  disableTotp: jest.fn(() => Promise.resolve()),
  logout: jest.fn(() => Promise.resolve()),
}));
jest.mock('@api/me', () => ({
  fetchMe: jest.fn(),
  fetchSecurityEvents: jest.fn(),
  updateMe: jest.fn(),
}));
jest.mock('@api/preferences', () => ({
  fetchPreferences: jest.fn(),
  updatePreferences: jest.fn(),
}));
jest.mock('@api/meta', () => ({
  fetchServerMeta: jest.fn(),
  appVersion: jest.fn(() => '1.0.0'),
}));

const mockFetchMe = fetchMe as jest.MockedFunction<typeof fetchMe>;
const mockFetchPreferences = fetchPreferences as jest.MockedFunction<typeof fetchPreferences>;
const mockFetchEvents = fetchSecurityEvents as jest.MockedFunction<typeof fetchSecurityEvents>;
const mockFetchServerMeta = fetchServerMeta as jest.MockedFunction<typeof fetchServerMeta>;
const mockUpdateMe = updateMe as jest.MockedFunction<typeof updateMe>;
const mockUpdatePreferences = updatePreferences as jest.MockedFunction<typeof updatePreferences>;
const mockChangePassword = changePassword as jest.MockedFunction<typeof changePassword>;
const mockDisableTotp = disableTotp as jest.MockedFunction<typeof disableTotp>;
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
  timezone: 'America/Toronto',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const PREFERENCES: UserPreferences = {
  region: 'CA',
  locale: 'en-CA',
  timezone: 'America/Toronto',
  onboardingCompleted: true,
};

/**
 * The screen fires four queries on mount (`me`, `preferences`,
 * `security-events`, `server-meta`). Two rules follow from that, and both were
 * got wrong here before:
 *
 *  - **Every test renders through `renderSettings()`, which flushes those four
 *    inside `act()` before returning.** A test that renders and then reaches
 *    for server-derived text through `waitFor` is racing an async boundary on
 *    a 1-second budget, and loses on a slow runner. Flushing first makes the
 *    assertions synchronous facts rather than deadlines.
 *
 *    The previous attempt at this pointed React Query's `notifyManager` at
 *    `act()`. That is worse than doing nothing: `waitFor` already runs inside
 *    an outer async `act` scope, so a nested un-awaited `act(cb)` defers the
 *    query's notification until that outer scope closes — which only happens
 *    when `waitFor` resolves or times out. The re-render carrying the data was
 *    trapped behind the very `waitFor` waiting for it, and CI failed on
 *    `settings-server-version` exactly as that predicts.
 *
 *  - **Each client is cleared afterwards.** An unmounted query schedules a
 *    5-minute garbage-collection timeout; 22 tests' worth of those keep the
 *    Jest worker's event loop alive and produce "Jest did not exit one second
 *    after the test run has completed" plus a five-minute stall.
 *    `queryClient.clear()` destroys every query and cancels those timers.
 */
let qc: QueryClient;

async function mountSettings() {
  qc = createTestQueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <SettingsScreen />
    </QueryClientProvider>
  );
}

/**
 * Drain the in-flight queries and the notification that publishes their
 * results.
 *
 * A bare `await act(async () => {})` is not enough: React Query's
 * `notifyManager` batches observer updates behind `setTimeout(…, 0)`, a
 * macrotask, so a microtask flush leaves the re-render that carries the data
 * unqueued. The loop is bounded and self-terminating — the mocks resolve
 * immediately, so it is waiting on scheduler ticks, never on I/O.
 */
async function settleQueries() {
  await act(async () => {
    for (let tick = 0; tick < 20 && qc.isFetching() > 0; tick += 1) {
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    // One further tick for the batch that publishes the final result.
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

/** Render and settle the four mount queries. The default for every test. */
async function renderSettings() {
  const utils = await mountSettings();
  await settleQueries();
  return utils;
}

afterEach(() => {
  qc?.clear();
});

beforeEach(async () => {
  jest.clearAllMocks();
  await SecureStore.deleteItemAsync(StorageKeys.settings);
  mockFetchMe.mockResolvedValue(ME);
  mockFetchPreferences.mockResolvedValue(PREFERENCES);
  mockFetchEvents.mockResolvedValue([
    { id: 'e1', type: 'LOGIN_SUCCESS', at: '2026-09-01T10:00:00Z', ip: null, userAgent: null },
  ]);
  mockFetchServerMeta.mockResolvedValue({ status: 'UP', version: '1.2.3' });
  mockUpdateMe.mockResolvedValue({ ...ME, displayName: 'Ada Lovelace' });
  mockUpdatePreferences.mockResolvedValue({ ...PREFERENCES, locale: 'en-US' });

  useAuthStore.setState({
    status: 'authenticated',
    hydrated: true,
    server: {
      baseUrl: 'https://trove.example.com',
      allowInsecureLan: false,
      // The version recorded when the app paired with this server. The live
      // `GET /v1/status` below reports a newer one, so the tests can tell the
      // two apart instead of both reading as "not Unknown".
      meta: { status: 'UP', version: '0.9.0' },
    },
    user: {
      id: 'u1',
      email: ME.email,
      displayName: ME.displayName,
      householdId: ME.householdId,
      role: ME.role,
      totpEnabled: false,
    },
    me: null,
    preferences: null,
  });
  useUiStore.setState({
    theme: 'system',
    appLockEnabled: true,
    appLockTimeoutMinutes: 5,
    hydrated: true,
  });
});

describe('about section', () => {
  it('links to the source code — the AGPL-3.0 section 13 offer', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue();
    const { getByTestId } = await renderSettings();
    expect(within(getByTestId('settings-source-code')).getByText(/AGPL-3\.0/)).toBeTruthy();
    await fireEvent.press(getByTestId('settings-source-code'));
    expect(openURL).toHaveBeenCalledWith('https://github.com/Harnoor6693/trove');
    openURL.mockRestore();
  });
});

describe('server section', () => {
  it('shows the connected server and the live version from GET /v1/status', async () => {
    const { getByTestId } = await renderSettings();
    expect(getByTestId('settings-server-url').props.children).toBe('https://trove.example.com');
    // `1.2.3` is what the probe answered; `0.9.0` is what was stored when the
    // app paired. The live value wins, and the assertion is a synchronous fact
    // because `renderSettings()` already settled the query.
    expect(getByTestId('settings-server-version').props.children).toBe('1.2.3');
  });

  // `fetchServerMeta` swallows its own errors and resolves to null, so an
  // unreachable server must fall back to the version recorded at pair time
  // rather than reporting "Unknown" for a server the app has met before.
  it('falls back to the stored version when the live probe answers nothing', async () => {
    mockFetchServerMeta.mockResolvedValue(null);
    const { getByTestId } = await renderSettings();
    expect(getByTestId('settings-server-version').props.children).toBe('0.9.0');
  });

  it('routes to the connect screen to change server', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('settings-change-server-btn'));
    expect(mockPush).toHaveBeenCalledWith('/(auth)/connect');
  });

  it('disconnecting really clears the stored server', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('settings-disconnect-btn'));
    await waitFor(() => expect(useAuthStore.getState().status).toBe('disconnected'));
    expect(useAuthStore.getState().server).toBeNull();
  });
});

describe('account section', () => {
  it('shows the email from GET /v1/auth/me', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() =>
      expect(getByTestId('settings-email').props.children).toBe('ada@example.com')
    );
  });

  it('saves the display name and timezone through PUT /v1/me', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-display-name-input').props.value).toBe('Ada'));

    await fireEvent.changeText(getByTestId('settings-display-name-input'), 'Ada Lovelace');
    await fireEvent.press(getByTestId('settings-save-identity-btn'));

    await waitFor(() =>
      expect(mockUpdateMe).toHaveBeenCalledWith({
        displayName: 'Ada Lovelace',
        timezone: 'America/Toronto',
      })
    );
    await waitFor(() => expect(getByTestId('settings-notice')).toBeTruthy());
  });

  it('changes the password and says other sessions were revoked', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('settings-change-password-btn'));

    await fireEvent.changeText(getByTestId('settings-current-password-input'), 'old-password-12');
    await fireEvent.changeText(getByTestId('settings-new-password-input'), 'new-password-1234');
    await fireEvent.press(getByTestId('settings-submit-password-btn'));

    await waitFor(() =>
      expect(mockChangePassword).toHaveBeenCalledWith({
        currentPassword: 'old-password-12',
        newPassword: 'new-password-1234',
      })
    );
    await waitFor(() =>
      expect(getByTestId('settings-notice').props.children).toMatch(/signed out/i)
    );
  });

  it('surfaces a server error instead of claiming success', async () => {
    mockChangePassword.mockRejectedValue(new Error('Current password is incorrect'));

    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('settings-change-password-btn'));
    await fireEvent.changeText(getByTestId('settings-current-password-input'), 'wrong');
    await fireEvent.changeText(getByTestId('settings-new-password-input'), 'new-password-1234');
    await fireEvent.press(getByTestId('settings-submit-password-btn'));

    await waitFor(() =>
      expect(getByTestId('settings-error').props.children).toMatch(/Current password is incorrect/)
    );
  });

  it('offers enrolment when two-factor is off', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() =>
      expect(within(getByTestId('settings-totp-state')).getByText('Off')).toBeTruthy()
    );
    await fireEvent.press(getByTestId('settings-totp-enrol-btn'));
    expect(mockPush).toHaveBeenCalledWith('/(auth)/totp-enrol');
  });

  it('requires password and a live code to turn two-factor off (step-up, §1.5)', async () => {
    mockFetchMe.mockResolvedValue({ ...ME, totpEnabled: true });

    const { getByTestId } = await renderSettings();
    await waitFor(() =>
      expect(within(getByTestId('settings-totp-state')).getByText('On')).toBeTruthy()
    );

    await fireEvent.press(getByTestId('settings-totp-disable-btn'));
    await fireEvent.changeText(getByTestId('settings-totp-password-input'), 'my-password-12');
    await fireEvent.changeText(getByTestId('settings-totp-code-input'), '123456');
    await fireEvent.press(getByTestId('settings-totp-submit-btn'));

    await waitFor(() => expect(mockDisableTotp).toHaveBeenCalledWith('my-password-12', '123456'));
  });
});

describe('preferences section', () => {
  it('shows country and currency read-only — they are fixed at registration (§4.2)', async () => {
    const { getByTestId, queryByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-currency').props.children).toBe('CA · CAD'));
    expect(queryByTestId('settings-currency-input')).toBeNull();
  });

  // §4.2 states `locale ∈ {en-CA, en-US}`, so this is a two-way choice rather
  // than the free-text field it used to be — a typed `fr-CA` would have been
  // PUT straight at a 400.
  it('offers exactly the two locales the server accepts', async () => {
    const { getByTestId, queryByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-locale-en-CA')).toBeTruthy());
    expect(getByTestId('settings-locale-en-US')).toBeTruthy();
    expect(queryByTestId('settings-locale-input')).toBeNull();
  });

  // Nothing is selected — and Save is disabled — until the server has said
  // which locale the user is on. Seeding `en-CA` would show an unconfirmed
  // value and would discard a choice made before the query landed.
  it('selects nothing and disables Save until preferences load', async () => {
    // The one test that wants the *unsettled* screen, so it mounts without the
    // flush — then drains the four queries before finishing, or their state
    // updates land after the test and outside `act()`. RNTL 14's `render` is
    // async and lets resolved mocks land before it returns, so "unsettled" is
    // made explicit: preferences stay pending until the assertions are done.
    let release: (value: UserPreferences) => void = () => {};
    mockFetchPreferences.mockReturnValue(
      new Promise<UserPreferences>((resolve) => {
        release = resolve;
      })
    );
    const { getByTestId } = await mountSettings();
    expect(getByTestId('settings-locale-en-CA').props.accessibilityState.selected).toBe(false);
    expect(getByTestId('settings-locale-en-US').props.accessibilityState.selected).toBe(false);
    expect(getByTestId('settings-save-locale-btn').props.accessibilityState.disabled).toBe(true);
    release(PREFERENCES);
    await settleQueries();
  });

  it('marks the loaded locale as the selected choice', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() =>
      expect(getByTestId('settings-locale-en-CA').props.accessibilityState.selected).toBe(true)
    );
    expect(getByTestId('settings-locale-en-US').props.accessibilityState.selected).toBe(false);
  });

  it('saves the locale through PUT /v1/me/preferences', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() =>
      expect(getByTestId('settings-locale-en-CA').props.accessibilityState.selected).toBe(true)
    );

    await fireEvent.press(getByTestId('settings-locale-en-US'));
    // The selection has to land before Save is pressed — the mutation reads
    // the chosen locale, not the one the query loaded.
    await waitFor(() =>
      expect(getByTestId('settings-locale-en-US').props.accessibilityState.selected).toBe(true)
    );

    await fireEvent.press(getByTestId('settings-save-locale-btn'));

    await waitFor(() => expect(mockUpdatePreferences).toHaveBeenCalledWith({ locale: 'en-US' }));
  });

  it('previews amounts in the user’s own currency and locale', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() =>
      expect(
        getByTestId('settings-format-sample').props.children.replace(/[\u00a0\u202f]/g, ' ')
      ).toBe('Amounts look like $1,234.50')
    );
  });
});

describe('appearance', () => {
  // The old toggle wrote a value nothing read; this asserts the write lands in
  // the store `useTheme()` actually consumes.
  it('persists the theme choice to the store useTheme() reads', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('settings-theme-dark'));
    await waitFor(() => expect(useUiStore.getState().theme).toBe('dark'));
  });
});

describe('security section', () => {
  it('toggles the app lock and persists it', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent(getByTestId('settings-app-lock-toggle'), 'onToggle', false);
    await waitFor(() => expect(useUiStore.getState().appLockEnabled).toBe(false));
  });

  it('sets the background timeout', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('settings-app-lock-timeout-15'));
    await waitFor(() => expect(useUiStore.getState().appLockTimeoutMinutes).toBe(15));
  });

  it('lists recent security events', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-event-e1')).toBeTruthy());
  });
});

describe('data section', () => {
  // Step 215 replaced the `settings-data-deferred` placeholder with the real
  // thing. Both actions are step-up operations, so this card navigates rather
  // than collecting a password on a screen the member scrolls past.
  it('offers export and deletion, each as its own step-up screen', async () => {
    const { getByTestId, queryByTestId } = await renderSettings();
    expect(queryByTestId('settings-data-deferred')).toBeNull();

    await fireEvent.press(getByTestId('settings-export-btn'));
    expect(mockPush).toHaveBeenCalledWith('/(tabs)/account/export');

    await fireEvent.press(getByTestId('settings-delete-account-btn'));
    expect(mockPush).toHaveBeenCalledWith('/(tabs)/account/delete');
  });

  it('says what the archive holds and what deletion actually does, before either is tapped', async () => {
    const { getByTestId } = await renderSettings();
    // The archive is metadata only for receipts — the operation's own
    // description says so, and a member who expects their photographs in it
    // would find out only after downloading one.
    expect(getByTestId('settings-data-export-note').props.children).toMatch(
      /Receipt images are not in/
    );
    // Soft delete, seven-day admin restore, household keeps the ledger.
    const deleteNote = getByTestId('settings-delete-account-note').props.children;
    expect(deleteNote).toMatch(/seven days/);
    expect(deleteNote).toMatch(/household keeps its shared ledger/);
  });
});

describe('administration section', () => {
  it('shows the admin console entry point to an ADMIN', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-admin-section')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-admin-console-btn'));
    expect(mockPush).toHaveBeenCalledWith('/(tabs)/admin');
  });

  it('hides it from a MEMBER — a tidiness measure, not the control', async () => {
    mockFetchMe.mockResolvedValue({ ...ME, role: 'MEMBER' });
    const { queryByTestId, getByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-email')).toBeTruthy());
    await waitFor(() => expect(queryByTestId('settings-admin-section')).toBeNull());
  });
});

describe('sign out', () => {
  it('signs out and returns to login', async () => {
    const { getByTestId } = await renderSettings();
    await fireEvent.press(getByTestId('sign-out-btn'));
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'));
  });
});

describe('render stability', () => {
  // Every selector in this screen returns a primitive. An object-returning
  // selector re-renders forever under zustand v5 (`Object.is` equality), which
  // is what shipped here before.
  it('settles instead of re-rendering forever', async () => {
    const { getByTestId } = await renderSettings();
    await waitFor(() => expect(getByTestId('settings-email').props.children).toBe(ME.email));

    const renders: number[] = [];
    const unsubscribe = useAuthStore.subscribe(() => renders.push(1));
    await new Promise((resolve) => setTimeout(resolve, 50));
    unsubscribe();

    // A looping selector emits store updates without any input; a stable one
    // emits nothing once the queries have settled.
    expect(renders.length).toBe(0);
  });
});
