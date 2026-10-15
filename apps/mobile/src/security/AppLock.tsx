import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, type AppStateStatus, StyleSheet, Text, View } from 'react-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { authenticateWithBiometrics } from '@hooks/useBiometric';
import { logError } from '@lib/logError';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { useAuthStore } from '@stores/authStore';
import { useUiStore } from '@stores/uiStore';

/**
 * The app lock, and the screenshot/app-switcher privacy overlay.
 *
 * A finance app that stays open forever after one login is below the bar for
 * a lost or shared device (readiness gap P4-20). This gate sits above the
 * router and does three things:
 *
 *  1. **Locks on cold start.** `authStore.hydrate()` restores a stored session
 *     as `locked`, never `authenticated`, so the very first render after a
 *     launch is the unlock screen.
 *  2. **Locks after N minutes in the background** (default 5, user-settable in
 *     Settings › Security; `0` means "immediately").
 *  3. **Blanks the UI while the app is inactive or backgrounded**, so the
 *     ledger does not appear in the OS app switcher or in a screenshot taken
 *     from it.
 *
 * Unlocking is not cosmetic: it exchanges the stored refresh token for a live
 * access token and then re-reads `GET /v1/auth/me`, so a session revoked
 * server-side while the phone was in a pocket cannot be resumed by a
 * fingerprint (the "biometric proves the human, not the session" rule the old
 * login screen got right and is kept here).
 */
export function AppLock({ children }: { children: React.ReactNode }) {
  const theme = useTheme();
  const status = useAuthStore((s) => s.status);
  const lock = useAuthStore((s) => s.lock);
  const unlock = useAuthStore((s) => s.unlock);
  const setMe = useAuthStore((s) => s.setMe);
  const setPreferences = useAuthStore((s) => s.setPreferences);
  const appLockEnabled = useUiStore((s) => s.appLockEnabled);
  const settingsHydrated = useUiStore((s) => s.hydrated);
  const timeoutMinutes = useUiStore((s) => s.appLockTimeoutMinutes);

  const [obscured, setObscured] = useState(false);
  const [unlocking, setUnlocking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const backgroundedAt = useRef<number | null>(null);

  const revalidate = useCallback(async () => {
    // Best-effort: the session is already live, so a failure here must not
    // bounce the user back to the lock screen — the interceptor will handle a
    // genuine 401.
    try {
      const me = await fetchMe();
      setMe(me);
      const preferences = await fetchPreferences();
      setPreferences(preferences);
    } catch (err) {
      logError('SESSION_REVALIDATION_FAILED', err);
    }
  }, [setMe, setPreferences]);

  const runUnlock = useCallback(async () => {
    setError(null);
    setUnlocking(true);
    try {
      if (appLockEnabled) {
        // Always prompt. `disableDeviceFallback: false` in `useBiometric`
        // means a device with no enrolled biometrics, or a failed scan, falls
        // back to the device passcode. A device with neither stays locked: the
        // lock must never silently open (B5-04).
        const result = await authenticateWithBiometrics('Unlock Trove');
        if (!result.success) {
          setError(
            result.error === 'not_enrolled' || result.error === 'passcode_not_set'
              ? 'Set a device passcode or biometrics to use the app lock.'
              : 'Unlock failed. Try again.'
          );
          return;
        }
      }
      const ok = await unlock();
      if (!ok) {
        setError('Your session expired. Sign in again.');
        return;
      }
      await revalidate();
    } finally {
      setUnlocking(false);
    }
  }, [appLockEnabled, unlock, revalidate]);

  // Auto-unlock when the lock is switched off. A stored session is always
  // restored as `locked` so the token exchange happens exactly once, in one
  // place; with the lock disabled there is simply no biometric prompt in
  // front of it. This effect touches store actions only, never component
  // state, so it cannot cascade renders.
  useEffect(() => {
    if (status !== 'locked' || !settingsHydrated || appLockEnabled) return;
    let cancelled = false;
    void (async () => {
      const ok = await unlock();
      if (ok && !cancelled) await revalidate();
    })();
    return () => {
      cancelled = true;
    };
  }, [status, settingsHydrated, appLockEnabled, unlock, revalidate]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next: AppStateStatus) => {
      const leaving = next === 'background' || next === 'inactive';
      setObscured(leaving);

      if (leaving) {
        if (backgroundedAt.current === null) backgroundedAt.current = Date.now();
        return;
      }

      const since = backgroundedAt.current;
      backgroundedAt.current = null;
      if (since === null || !appLockEnabled) return;
      const elapsedMinutes = (Date.now() - since) / 60_000;
      if (elapsedMinutes >= timeoutMinutes) lock();
    });
    return () => subscription.remove();
  }, [appLockEnabled, timeoutMinutes, lock]);

  if (status === 'locked') {
    return (
      <View
        style={[styles.fill, styles.locked, { backgroundColor: theme.paper }]}
        testID="app-lock-screen"
      >
        <Text style={[styles.wordmark, { color: theme.ink }]}>trove</Text>
        <Text style={[styles.hint, { color: theme.ink3 }]}>
          {appLockEnabled ? 'Locked. Unlock to continue.' : 'Restoring your session…'}
        </Text>
        {error !== null && (
          <Text testID="app-lock-error" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}
        {appLockEnabled && (
          <MnBtn
            testID="app-lock-unlock-btn"
            label="Unlock"
            onPress={runUnlock}
            loading={unlocking}
            fullWidth
          />
        )}
      </View>
    );
  }

  return (
    <View style={styles.fill}>
      {children}
      {obscured && (
        <View
          testID="app-lock-privacy-overlay"
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, { backgroundColor: theme.paper }]}
        >
          <View style={styles.overlayInner}>
            <Text style={[styles.wordmark, { color: theme.ink }]}>trove</Text>
          </View>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  locked: { justifyContent: 'center', padding: Space.s6 },
  overlayInner: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  wordmark: {
    fontFamily: FontFamily.displaySemi,
    fontSize: FontSize.xxl,
    letterSpacing: -1,
    textAlign: 'center',
    marginBottom: Space.s4,
  },
  hint: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    textAlign: 'center',
    marginBottom: Space.s6,
  },
  error: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.sm,
    textAlign: 'center',
    marginBottom: Space.s4,
  },
});
