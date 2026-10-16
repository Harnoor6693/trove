import React from 'react';
import { configure } from '@testing-library/react-native';

// Load-fragility fix (step 27, ⚙ 39): RNTL's `waitFor`/`findBy*` default to a
// 1000ms timeout and Jest's own per-test timeout defaults to 5000ms. Both are
// tight enough that a CPU-starved machine (Spotlight indexing a fresh
// node_modules pushed load to 22 on 10 cores during step 26) blows them on
// otherwise-correct tests — 16 of 1392 `test:ci` tests timed out under that
// load and all passed in isolation. Raise both once, here, for every test:
// no per-file/per-call config, no touching the flaky tests themselves.
configure({ asyncUtilTimeout: 10000 });
jest.setTimeout(15000);

// expo-secure-store — hardware keychain; mock for Node test runner.
// An in-memory map rather than bare jest.fn()s, so a test that writes a value
// and then reads it back exercises the real round trip.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    WHEN_UNLOCKED_THIS_DEVICE_ONLY: 'whenUnlockedThisDeviceOnly',
    getItemAsync: jest.fn((key: string) => Promise.resolve(store.get(key) ?? null)),
    setItemAsync: jest.fn((key: string, value: string) => {
      store.set(key, value);
      return Promise.resolve();
    }),
    deleteItemAsync: jest.fn((key: string) => {
      store.delete(key);
      return Promise.resolve();
    }),
    __store: store,
  };
});

// react-native `Settings` (iOS NSUserDefaults) has no native module under
// Jest. In-memory, with the install marker already set, so every test runs
// as an established install unless it clears the marker itself.
jest.mock('react-native/Libraries/Settings/Settings', () => {
  const values: Record<string, unknown> = { trove_installed: true };
  return {
    __esModule: true,
    default: {
      get: (key: string) => values[key],
      set: (next: Record<string, unknown>) => Object.assign(values, next),
    },
  };
});

// expo-local-authentication — biometric unlock; default to "available + enrolled + success"
jest.mock('expo-local-authentication', () => ({
  hasHardwareAsync: jest.fn(() => Promise.resolve(true)),
  isEnrolledAsync: jest.fn(() => Promise.resolve(true)),
  // SecurityLevel: 0 NONE, 1 SECRET (passcode), 2 BIOMETRIC
  getEnrolledLevelAsync: jest.fn(() => Promise.resolve(2)),
  authenticateAsync: jest.fn(() => Promise.resolve({ success: true })),
  AuthenticationType: { FINGERPRINT: 1, FACIAL_RECOGNITION: 2, IRIS: 3 },
}));

// expo-linking
jest.mock('expo-linking', () => ({
  openSettings: jest.fn(() => Promise.resolve()),
  createURL: jest.fn((path: string) => `trove://${path}`),
  parse: jest.fn(() => ({})),
}));

// expo-constants — __esModule: true so `import Constants from 'expo-constants'` gets .default correctly.
// `apiBaseUrl` is empty by default, matching a release build: the app is
// unconnected until a test (or a user) supplies a server.
jest.mock('expo-constants', () => ({
  __esModule: true,
  default: {
    expoConfig: {
      version: '1.0.0',
      extra: { apiBaseUrl: '', eas: { projectId: 'test-project' } },
    },
  },
}));

// expo-router — stable mock object shared between useRouter() and router singleton
const mockRouter = { push: jest.fn(), replace: jest.fn(), back: jest.fn() };

jest.mock('expo-router', () => ({
  useRouter: jest.fn(() => mockRouter),
  useSegments: jest.fn(() => []),
  useLocalSearchParams: jest.fn(() => ({})),
  Link: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Stack: Object.assign(({ children }: { children: React.ReactNode }) => <>{children}</>, {
    Screen: () => null,
  }),
  Tabs: Object.assign(({ children }: { children: React.ReactNode }) => <>{children}</>, {
    Screen: () => null,
  }),
  router: mockRouter,
  Slot: () => null,
}));

// expo-image-picker
jest.mock('expo-image-picker', () => ({
  // The real enum's values (build/ImagePicker.types.d.ts).
  UIImagePickerPreferredAssetRepresentationMode: {
    Automatic: 'automatic',
    Compatible: 'compatible',
    Current: 'current',
  },
  launchCameraAsync: jest.fn(() =>
    Promise.resolve({ canceled: false, assets: [{ uri: 'file://test.jpg' }] })
  ),
  launchImageLibraryAsync: jest.fn(() =>
    Promise.resolve({ canceled: false, assets: [{ uri: 'file://lib.jpg' }] })
  ),
  requestMediaLibraryPermissionsAsync: jest.fn(() =>
    Promise.resolve({ status: 'granted', granted: true })
  ),
  requestCameraPermissionsAsync: jest.fn(() =>
    Promise.resolve({ status: 'granted', granted: true })
  ),
}));

// expo-file-system — an in-memory stand-in for the new `File`/`Paths` API.
// `write` and `delete` are prototype mocks, so a test reads what was written
// (and to which uri) from `mock.calls` / `mock.contexts`. `exists` tracks the
// write so the helper's `finally` behaves as it would on a device.
jest.mock('expo-file-system', () => {
  class MockFile {
    uri: string;
    exists = false;
    constructor(...parts: (string | { uri: string })[]) {
      this.uri = parts
        .map((p) => (typeof p === 'string' ? p : p.uri))
        .reduce((acc, p) => (acc === '' ? p : `${acc.replace(/\/$/, '')}/${p}`), '');
    }
    get name(): string {
      return this.uri.slice(this.uri.lastIndexOf('/') + 1);
    }
    static pickFileAsync = jest.fn(() => Promise.resolve({ canceled: true, result: null }));
  }
  (MockFile.prototype as unknown as Record<string, unknown>).write = jest.fn(function (
    this: MockFile
  ) {
    this.exists = true;
  });
  (MockFile.prototype as unknown as Record<string, unknown>).delete = jest.fn(function (
    this: MockFile
  ) {
    this.exists = false;
  });
  // `list` answers what is in the cache (empty unless a test says otherwise).
  return { File: MockFile, Paths: { cache: { uri: 'file:///cache/', list: jest.fn(() => []) } } };
});

// expo-sharing
jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn(() => Promise.resolve(true)),
  shareAsync: jest.fn(() => Promise.resolve()),
}));

// expo-splash-screen
jest.mock('expo-splash-screen', () => ({
  preventAutoHideAsync: jest.fn(() => Promise.resolve()),
  hideAsync: jest.fn(() => Promise.resolve()),
}));

// victory-native charts — SVG heavy; swap for plain View stubs in tests
jest.mock('victory-native', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { View } = require('react-native');
  return {
    VictoryLine: (props: object) => <View testID="victory-line" {...props} />,
    VictoryChart: (props: object) => <View testID="victory-chart" {...props} />,
    VictoryAxis: (props: object) => <View testID="victory-axis" {...props} />,
    VictoryBar: (props: object) => <View testID="victory-bar" {...props} />,
    VictoryArea: (props: object) => <View testID="victory-area" {...props} />,
  };
});

// react-native-svg — __esModule: true so `import Svg from 'react-native-svg'` gets the fn, not the module object
jest.mock('react-native-svg', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { View } = require('react-native');
  const Svg = (props: object) => <View testID="svg" {...props} />;
  const Line = (props: object) => <View testID="svg-line" {...props} />;
  const Polyline = (props: object) => <View testID="svg-polyline" {...props} />;
  const Circle = (props: object) => <View testID="svg-circle" {...props} />;
  const Rect = (props: object) => <View testID="svg-rect" {...props} />;
  const Path = (props: object) => <View testID="svg-path" {...props} />;
  const G = (props: object) => <View testID="svg-g" {...props} />;
  return { __esModule: true, default: Svg, Svg, Line, Polyline, Circle, Rect, Path, G };
});

// logError — silent in tests; tests can spyOn the export when behavior matters.
// Both alias paths need their own entry because each module specifier is
// resolved separately by jest.
jest.mock('@/lib/logError', () => ({ logError: jest.fn() }));
jest.mock('@lib/logError', () => ({ logError: jest.fn() }));

// NOTE: `axios` is deliberately NOT mocked globally any more.
//
// The old setup replaced `axios.create()` with an object of bare `jest.fn()`s
// whose `interceptors.request.use` discarded the handler it was given. That
// made the request/response interceptors — where the bearer token, the base
// URL and the whole single-flight refresh live — structurally untestable, in
// exactly the way the mobile inventory calls out for the over-mocked store
// tests. Tests now either stub the `src/api/*` endpoint modules (screens) or
// drive a real axios instance through a mock adapter (`client.test.ts`).
