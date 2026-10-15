import * as SecureStore from 'expo-secure-store';
import { StorageKeys } from '@api/secureStorage';
import { useUiStore } from './uiStore';

beforeEach(async () => {
  await SecureStore.deleteItemAsync(StorageKeys.settings);
  useUiStore.setState({
    theme: 'system',
    appLockEnabled: true,
    appLockTimeoutMinutes: 5,
    hydrated: false,
  });
});

describe('defaults', () => {
  it('follows the OS appearance until told otherwise', () => {
    expect(useUiStore.getState().theme).toBe('system');
  });

  it('requires unlock by default — a finance app must not stay open forever', () => {
    expect(useUiStore.getState().appLockEnabled).toBe(true);
    expect(useUiStore.getState().appLockTimeoutMinutes).toBe(5);
  });
});

describe('persistence', () => {
  it('survives a restart', async () => {
    await useUiStore.getState().setTheme('dark');
    await useUiStore.getState().setAppLockEnabled(false);
    await useUiStore.getState().setAppLockTimeoutMinutes(15);

    useUiStore.setState({
      theme: 'system',
      appLockEnabled: true,
      appLockTimeoutMinutes: 5,
      hydrated: false,
    });
    await useUiStore.getState().hydrate();

    expect(useUiStore.getState().theme).toBe('dark');
    expect(useUiStore.getState().appLockEnabled).toBe(false);
    expect(useUiStore.getState().appLockTimeoutMinutes).toBe(15);
  });

  it('writes each field without clobbering the others', async () => {
    await useUiStore.getState().setTheme('light');
    await useUiStore.getState().setAppLockTimeoutMinutes(1);

    const raw = await SecureStore.getItemAsync(StorageKeys.settings);
    expect(JSON.parse(raw ?? '{}')).toEqual({
      theme: 'light',
      appLockEnabled: true,
      appLockTimeoutMinutes: 1,
    });
  });

  it('falls back to defaults when nothing is stored', async () => {
    await useUiStore.getState().hydrate();
    expect(useUiStore.getState().theme).toBe('system');
    expect(useUiStore.getState().hydrated).toBe(true);
  });

  it('falls back to defaults when the stored blob is corrupt', async () => {
    await SecureStore.setItemAsync(StorageKeys.settings, 'not json');
    await useUiStore.getState().hydrate();
    expect(useUiStore.getState().theme).toBe('system');
    expect(useUiStore.getState().appLockEnabled).toBe(true);
  });
});
