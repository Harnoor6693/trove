import * as LocalAuthentication from 'expo-local-authentication';
import { checkBiometricAvailability, authenticateWithBiometrics } from './useBiometric';
import { logError } from '@lib/logError';

jest.mock('expo-local-authentication', () => ({
  hasHardwareAsync: jest.fn(),
  isEnrolledAsync: jest.fn(),
  authenticateAsync: jest.fn(),
}));

jest.mock('@lib/logError', () => ({
  logError: jest.fn(),
}));

const mockedHasHardware = LocalAuthentication.hasHardwareAsync as jest.Mock;
const mockedIsEnrolled = LocalAuthentication.isEnrolledAsync as jest.Mock;
const mockedAuthenticate = LocalAuthentication.authenticateAsync as jest.Mock;
const mockedLogError = logError as jest.Mock;

describe('checkBiometricAvailability', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('returns false when no biometric hardware is present', async () => {
    mockedHasHardware.mockResolvedValueOnce(false);
    await expect(checkBiometricAvailability()).resolves.toBe(false);
    expect(mockedIsEnrolled).not.toHaveBeenCalled();
  });

  it('returns false when hardware exists but no biometric is enrolled', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(false);
    await expect(checkBiometricAvailability()).resolves.toBe(false);
  });

  it('returns true when hardware is present and a biometric is enrolled', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    await expect(checkBiometricAvailability()).resolves.toBe(true);
  });

  it('returns false (does not throw) when the hardware check itself rejects', async () => {
    mockedHasHardware.mockRejectedValueOnce(new Error('boom'));
    await expect(checkBiometricAvailability()).resolves.toBe(false);
  });

  it('logs the error via logError when the hardware check rejects', async () => {
    const err = new Error('hardware-fail');
    mockedHasHardware.mockRejectedValueOnce(err);
    await checkBiometricAvailability();
    expect(mockedLogError).toHaveBeenCalledWith('BIOMETRIC_HARDWARE_ERROR', err);
  });
});

describe('authenticateWithBiometrics', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('returns success when the platform reports success', async () => {
    mockedAuthenticate.mockResolvedValueOnce({ success: true });
    const result = await authenticateWithBiometrics();
    expect(result.success).toBe(true);
    expect(mockedAuthenticate).toHaveBeenCalledWith(
      expect.objectContaining({
        promptMessage: 'Unlock Trove',
        fallbackLabel: 'Use Password',
      })
    );
  });

  it('passes a custom prompt message through to the platform', async () => {
    mockedAuthenticate.mockResolvedValueOnce({ success: true });
    await authenticateWithBiometrics('Reauthenticate');
    expect(mockedAuthenticate).toHaveBeenCalledWith(
      expect.objectContaining({ promptMessage: 'Reauthenticate' })
    );
  });

  it('returns failure with the platform error code when not successful', async () => {
    mockedAuthenticate.mockResolvedValueOnce({ success: false, error: 'user_cancel' });
    const result = await authenticateWithBiometrics();
    expect(result.success).toBe(false);
    expect(result.error).toBe('user_cancel');
  });

  it('returns failure with a generic error when the platform omits one', async () => {
    mockedAuthenticate.mockResolvedValueOnce({ success: false });
    const result = await authenticateWithBiometrics();
    expect(result.success).toBe(false);
    expect(result.error).toBe('authentication_failed');
  });

  it('does not throw when the platform throws', async () => {
    mockedAuthenticate.mockRejectedValueOnce(new Error('hardware_error'));
    const result = await authenticateWithBiometrics();
    expect(result.success).toBe(false);
    expect(result.error).toContain('hardware_error');
  });

  it('logs the error via logError when the platform throws', async () => {
    const err = new Error('hardware_error');
    mockedAuthenticate.mockRejectedValueOnce(err);
    await authenticateWithBiometrics();
    expect(mockedLogError).toHaveBeenCalledWith('BIOMETRIC_AUTH_ERROR', err);
  });
});
