import * as LocalAuthentication from 'expo-local-authentication';
import { logError } from '@lib/logError';

export interface BiometricResult {
  success: boolean;
  error?: string;
}

/**
 * Returns true when the device has biometric hardware AND a biometric
 * (Face ID, Touch ID, or fingerprint) is enrolled.
 *
 * Hardware errors are logged via `logError` so they're visible in dev and
 * observable in production — they are then mapped to a benign `false` so
 * callers can degrade gracefully (no biometric option shown).
 */
export async function checkBiometricAvailability(): Promise<boolean> {
  try {
    const hasHardware = await LocalAuthentication.hasHardwareAsync();
    if (!hasHardware) return false;
    const isEnrolled = await LocalAuthentication.isEnrolledAsync();
    return isEnrolled;
  } catch (err) {
    logError('BIOMETRIC_HARDWARE_ERROR', err);
    return false;
  }
}

/**
 * Prompt the user for a biometric unlock. Returns a result object — never
 * throws — so callers can branch on `result.success`. Native errors are
 * logged and surfaced as `{ success: false, error: <stringified> }`.
 */
export async function authenticateWithBiometrics(
  promptMessage = 'Unlock Trove'
): Promise<BiometricResult> {
  try {
    const result = await LocalAuthentication.authenticateAsync({
      promptMessage,
      fallbackLabel: 'Use Password',
      cancelLabel: 'Cancel',
      disableDeviceFallback: false,
    });
    if (result.success) {
      return { success: true };
    }
    return {
      success: false,
      error: 'error' in result ? result.error : 'authentication_failed',
    };
  } catch (err) {
    logError('BIOMETRIC_AUTH_ERROR', err);
    return { success: false, error: String(err) };
  }
}
