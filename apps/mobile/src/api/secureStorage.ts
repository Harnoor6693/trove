/**
 * Typed JSON wrapper over `expo-secure-store`.
 *
 * Two behaviours matter here and are applied to every key:
 *
 *  - `keychainAccessible: WHEN_UNLOCKED_THIS_DEVICE_ONLY` (readiness gap
 *    P4-20). The default (`WHEN_UNLOCKED`) lets a value ride an encrypted
 *    iCloud/iTunes backup onto a different device; a refresh token for a
 *    household ledger should not leave the handset it was issued to.
 *  - Corrupt-blob recovery. A value that no longer parses is deleted rather
 *    than thrown, so a bad write from an older build cannot brick the app on
 *    every launch.
 */

import * as SecureStore from 'expo-secure-store';
import { Platform, Settings } from 'react-native';
import { logError } from '@lib/logError';

const OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};

const INSTALL_MARKER = 'trove_installed';
let installCheck: Promise<void> | undefined;

/**
 * iOS keeps Keychain items when the app is deleted, so a reinstall came back
 * connected to the old server, refresh token included (step 27.7). The app's
 * NSUserDefaults (react-native `Settings`) are deleted with it, so a missing
 * marker there means a new install: forget every key once per launch, before
 * the first read or write. Android deletes the Keystore with the app.
 * An unreadable marker cannot prove this is the same install, so that case
 * forgets too (fail closed) but writes no marker into unreadable defaults.
 */
function forgetPreviousInstall(): Promise<void> {
  installCheck ??= (async () => {
    if (Platform.OS !== 'ios') return;
    let installed: unknown;
    try {
      installed = Settings.get(INSTALL_MARKER);
    } catch (err) {
      logError('INSTALL_MARKER_UNREADABLE', err);
      await Promise.all(Object.values(StorageKeys).map(remove));
      return;
    }
    if (installed) return;
    await Promise.all(Object.values(StorageKeys).map(remove));
    Settings.set({ [INSTALL_MARKER]: true });
  })();
  return installCheck;
}

/**
 * Read and parse a stored JSON value.
 *
 * @param key      SecureStore key
 * @param isValid  shape guard; a value that fails it is treated as corrupt
 * @returns the value, or null when absent, unreadable or invalid
 */
export async function readJson<T>(
  key: string,
  isValid: (value: unknown) => value is T
): Promise<T | null> {
  await forgetPreviousInstall();
  let raw: string | null;
  try {
    raw = await SecureStore.getItemAsync(key, OPTIONS);
  } catch (err) {
    logError('SECURE_STORE_READ_FAILED', err);
    return null;
  }
  if (raw === null) return null;

  try {
    const parsed: unknown = JSON.parse(raw);
    if (isValid(parsed)) return parsed;
    logError('SECURE_STORE_SHAPE_INVALID', new Error(`Unexpected shape for ${key}`));
  } catch (err) {
    logError('SECURE_STORE_PARSE_FAILED', err);
  }
  await remove(key);
  return null;
}

/** Write a JSON value. One `setItemAsync` call, so the write is atomic. */
export async function writeJson(key: string, value: unknown): Promise<void> {
  await forgetPreviousInstall();
  await SecureStore.setItemAsync(key, JSON.stringify(value), OPTIONS);
}

/** Delete a key; never throws. */
export async function remove(key: string): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(key, OPTIONS);
  } catch (err) {
    logError('SECURE_STORE_DELETE_FAILED', err);
  }
}

export const StorageKeys = {
  /** `{ baseUrl, allowInsecureLan, meta }` — the connected Trove server. */
  server: 'trove_server',
  /** `{ refreshToken, user }` — the access token is never persisted. */
  session: 'trove_session',
  /** `{ theme, appLockEnabled, appLockTimeoutMinutes }`. */
  settings: 'trove_settings',
} as const;
