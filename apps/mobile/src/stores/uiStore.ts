/**
 * Device-local preferences: appearance and the app lock.
 *
 * These are not server state — they describe this handset, so they are not
 * part of `/v1/me/preferences`. They are persisted through `secureStorage`
 * because `expo-secure-store` is the only key/value store this app depends
 * on; nothing here is secret.
 *
 * `theme` used to be written by Settings and read by nothing at all —
 * `useTheme()` looked only at the OS colour scheme, so the dark-mode toggle
 * changed a value no screen consumed. `useTheme()` now reads this store.
 */

import { create } from 'zustand';
import { readJson, StorageKeys, writeJson } from '@api/secureStorage';
import { logError } from '@lib/logError';

export type ThemePreference = 'system' | 'light' | 'dark';

/** Minutes in the background before the app lock engages. */
export const APP_LOCK_TIMEOUT_CHOICES = [0, 1, 5, 15, 60] as const;
export type AppLockTimeout = (typeof APP_LOCK_TIMEOUT_CHOICES)[number];

interface PersistedSettings {
  theme: ThemePreference;
  appLockEnabled: boolean;
  appLockTimeoutMinutes: AppLockTimeout;
}

const DEFAULTS: PersistedSettings = {
  theme: 'system',
  // On by default: this is a finance app, and a device that stays unlocked
  // forever after one login is below the bar (readiness gap P4-20).
  appLockEnabled: true,
  appLockTimeoutMinutes: 5,
};

interface UiState extends PersistedSettings {
  hydrated: boolean;
  hydrate: () => Promise<void>;
  setTheme: (theme: ThemePreference) => Promise<void>;
  setAppLockEnabled: (enabled: boolean) => Promise<void>;
  setAppLockTimeoutMinutes: (minutes: AppLockTimeout) => Promise<void>;
}

function isPersistedSettings(value: unknown): value is Partial<PersistedSettings> {
  return typeof value === 'object' && value !== null;
}

export const useUiStore = create<UiState>()((set, get) => {
  async function persist(patch: Partial<PersistedSettings>): Promise<void> {
    const next: PersistedSettings = {
      theme: get().theme,
      appLockEnabled: get().appLockEnabled,
      appLockTimeoutMinutes: get().appLockTimeoutMinutes,
      ...patch,
    };
    set(patch);
    try {
      await writeJson(StorageKeys.settings, next);
    } catch (err) {
      logError('SETTINGS_PERSIST_FAILED', err);
    }
  }

  return {
    ...DEFAULTS,
    hydrated: false,

    hydrate: async () => {
      const stored = await readJson<Partial<PersistedSettings>>(
        StorageKeys.settings,
        isPersistedSettings
      );
      set({
        theme: stored?.theme ?? DEFAULTS.theme,
        appLockEnabled: stored?.appLockEnabled ?? DEFAULTS.appLockEnabled,
        appLockTimeoutMinutes: stored?.appLockTimeoutMinutes ?? DEFAULTS.appLockTimeoutMinutes,
        hydrated: true,
      });
    },

    setTheme: (theme) => persist({ theme }),
    setAppLockEnabled: (appLockEnabled) => persist({ appLockEnabled }),
    setAppLockTimeoutMinutes: (appLockTimeoutMinutes) => persist({ appLockTimeoutMinutes }),
  };
});
