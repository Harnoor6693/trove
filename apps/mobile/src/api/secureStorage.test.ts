import type * as SecureStorageModule from './secureStorage';

type Deps = {
  storage: typeof SecureStorageModule;
  keychain: Map<string, string>;
  settings: { get(key: string): unknown; set(values: Record<string, unknown>): void };
};

/**
 * A fresh module registry per test: the install check runs once per app
 * launch, so each test gets its own "launch" with its own Keychain.
 * `Settings` is reached through react-native's lazy getter at call time, so it
 * resolves in the main registry — take it from there, and set the marker
 * explicitly in every test.
 */
function launch(setup: (deps: Omit<Deps, 'storage'>) => void): Deps {
  const settings = (require('react-native') as { Settings: Deps['settings'] }).Settings;
  let deps!: Deps;
  jest.isolateModules(() => {
    const keychain = (require('expo-secure-store') as { __store: Map<string, string> }).__store;
    setup({ keychain, settings });
    deps = { storage: require('./secureStorage'), keychain, settings };
  });
  return deps;
}

const server = { baseUrl: 'http://localhost:8080', allowInsecureLan: true, meta: null };
const isObject = (value: unknown): value is object => typeof value === 'object' && value !== null;

describe('secureStorage install check', () => {
  // Step 27.7: iOS keeps Keychain items when the app is deleted, so a
  // reinstall came back connected to the old server with its refresh token.
  it('forgets what a previous install left in the Keychain', async () => {
    const { storage, keychain, settings } = launch(({ keychain, settings }) => {
      keychain.set('trove_server', JSON.stringify(server));
      keychain.set('trove_session', JSON.stringify({ refreshToken: 'old', user: {} }));
      settings.set({ trove_installed: undefined });
    });

    expect(await storage.readJson('trove_server', isObject)).toBeNull();
    expect(keychain.has('trove_session')).toBe(false);
    expect(settings.get('trove_installed')).toBe(true);
  });

  it('keeps what this install stored', async () => {
    const { storage } = launch(({ keychain, settings }) => {
      keychain.set('trove_server', JSON.stringify(server));
      settings.set({ trove_installed: true });
    });

    expect(await storage.readJson('trove_server', isObject)).toEqual(server);
  });

  // Step 27 review: an unreadable marker cannot prove this is the same
  // install, so the check fails closed instead of keeping the old session.
  it('forgets the Keychain when the install marker is unreadable', async () => {
    const { storage, keychain, settings } = launch(({ keychain }) => {
      keychain.set('trove_server', JSON.stringify(server));
      keychain.set('trove_session', JSON.stringify({ refreshToken: 'old', user: {} }));
    });
    const get = jest.spyOn(settings, 'get').mockImplementation(() => {
      throw new Error('NSUserDefaults unreadable');
    });

    try {
      expect(await storage.readJson('trove_server', isObject)).toBeNull();
      expect(keychain.has('trove_session')).toBe(false);
    } finally {
      get.mockRestore();
    }
  });
});
