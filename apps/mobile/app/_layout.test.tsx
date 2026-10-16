import React from 'react';
import * as fs from 'fs';
import * as path from 'path';
import { render, waitFor } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { useRouter, useSegments } from 'expo-router';
import { File, Paths } from 'expo-file-system';
import RootLayout from './_layout';
import TabLayout from './(tabs)/_layout';
import { useAuthStore } from '@stores/authStore';
import { useUiStore } from '@stores/uiStore';

interface CapturedScreen {
  name: string;
  href?: string | null;
}

const capturedScreens: CapturedScreen[] = [];

/** The names that get a button in the tab bar — everything not declared `href: null`. */
const visibleTabNames = (): string[] =>
  capturedScreens.filter((screen) => screen.href !== null).map((screen) => screen.name);

/** The names declared only so expo-router knows the route, deliberately kept off the bar. */
const hiddenRouteNames = (): string[] =>
  capturedScreens.filter((screen) => screen.href === null).map((screen) => screen.name);

jest.mock('expo-router', () => {
  const actual = jest.requireActual('expo-router') as Record<string, unknown>;
  return {
    ...actual,
    useRouter: jest.fn(() => ({ replace: jest.fn(), push: jest.fn(), back: jest.fn() })),
    useSegments: jest.fn(() => []),
    Slot: () => null,
    Tabs: Object.assign(({ children }: { children: React.ReactNode }) => <>{children}</>, {
      Screen: ({ name, options }: { name: string; options?: { href?: string | null } }) => {
        capturedScreens.push({ name, href: options?.href });
        return null;
      },
    }),
  };
});

jest.mock('@design/useFonts', () => ({
  useTroveFonts: jest.fn(() => [true, null]),
}));

const mockUseSegments = useSegments as jest.Mock;
const mockUseRouter = useRouter as jest.Mock;

function withRouter() {
  const replace = jest.fn();
  mockUseRouter.mockReturnValue({ replace, push: jest.fn(), back: jest.fn() });
  return replace;
}

beforeEach(() => {
  jest.clearAllMocks();
  capturedScreens.length = 0;
  mockUseSegments.mockReturnValue([]);
  // `RootLayout` calls both stores' `hydrate()` on mount. Left real, each
  // test races a SecureStore read that resolves *after* the assertions and
  // then overwrites the status the test just set — so the routing assertions
  // would be timing-dependent, and the late store write lands outside
  // `act()`. These suites are about routing; hydration has its own tests in
  // `src/stores/authStore.test.ts`.
  useUiStore.setState({
    theme: 'system',
    appLockEnabled: false,
    appLockTimeoutMinutes: 5,
    hydrated: true,
    hydrate: jest.fn(async () => {}),
  });
  useAuthStore.setState({
    status: 'disconnected',
    hydrated: true,
    server: null,
    user: null,
    me: null,
    preferences: null,
    hydrate: jest.fn(async () => {}),
  });
});

describe('TabLayout', () => {
  async function renderTabs() {
    await render(
      <SafeAreaProvider
        initialMetrics={{
          frame: { x: 0, y: 0, width: 390, height: 844 },
          insets: { top: 47, left: 0, right: 0, bottom: 34 },
        }}
      >
        <TabLayout />
      </SafeAreaProvider>
    );
  }

  // Tax is cut with the tax engine (owner decision); Receipts had no
  // `<Tabs.Screen>` entry at all and rendered as an untitled ninth tab
  // (mobile inventory §1).
  it('declares eight tabs — no Tax, and Receipts is no longer undeclared', async () => {
    await renderTabs();
    expect(visibleTabNames()).toEqual([
      'index',
      'transactions',
      'budgets',
      'net-worth',
      'subscriptions',
      'receipts',
      'coach',
      'settings',
    ]);
  });

  // Step 215, family E. The two stacks reached from a Settings card have to be
  // declared or expo-router auto-mounts each as a further untitled tab — the
  // exact defect the Receipts entry above already fixed once. `href: null` is
  // how a route is declared *without* becoming a ninth and tenth button, so
  // the assertion that matters is that they are declared AND invisible.
  it('declares the account and admin stacks as routes but not as tabs', async () => {
    await renderTabs();
    expect(hiddenRouteNames()).toEqual(['account', 'admin']);
    expect(visibleTabNames()).toHaveLength(8);
  });
});

describe('RootLayout routing', () => {
  it('sweeps an export a killed app left in the cache, once, on launch', async () => {
    const stale = new File(Paths.cache, 'trove-export.zip');
    (Paths.cache.list as jest.Mock).mockReturnValueOnce([stale]);
    withRouter();
    await render(<RootLayout />);
    const remove = (File.prototype as unknown as { delete: jest.Mock }).delete;
    expect(remove).toHaveBeenCalledTimes(1);
    expect(remove.mock.contexts[0]).toBe(stale);
  });

  it('renders nothing until the stored server and session have been read', async () => {
    useAuthStore.setState({ hydrated: false });
    const replace = withRouter();
    const { toJSON } = await render(<RootLayout />);
    expect(toJSON()).toBeNull();
    expect(replace).not.toHaveBeenCalled();
  });

  it('sends a first-launch app to the connect screen, not to login', async () => {
    const replace = withRouter();
    await render(<RootLayout />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/(auth)/connect'));
  });

  it('does not bounce a user who is already on the connect screen', async () => {
    mockUseSegments.mockReturnValue(['(auth)', 'connect']);
    const replace = withRouter();
    await render(<RootLayout />);
    await waitFor(() => expect(replace).not.toHaveBeenCalled());
  });

  it('sends a connected but signed-out app to login', async () => {
    useAuthStore.setState({
      status: 'anonymous',
      server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    });
    mockUseSegments.mockReturnValue(['(tabs)']);
    const replace = withRouter();
    await render(<RootLayout />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/(auth)/login'));
  });

  // Step 27.7: a cold start from `trove://reset-password?token=…` rendered the
  // guard once with no segments (the router had not applied the launch URL
  // yet), and that first pass replaced the reset screen with login.
  it('lets a cold-start reset link land instead of replacing it with login', async () => {
    useAuthStore.setState({
      status: 'anonymous',
      server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    });
    mockUseSegments.mockReturnValue([]);
    const replace = withRouter();
    const { rerender } = await render(<RootLayout />);
    mockUseSegments.mockReturnValue(['(auth)', 'reset-password']);
    await rerender(<RootLayout />);
    await waitFor(() => expect(replace).not.toHaveBeenCalled());
  });

  it('sends a signed-in app out of the auth group', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
      user: {
        id: 'u1',
        email: 'a@b.co',
        displayName: 'A',
        householdId: 'h1',
        role: 'MEMBER',
        totpEnabled: false,
      },
    });
    mockUseSegments.mockReturnValue(['(auth)', 'login']);
    const replace = withRouter();
    await render(<RootLayout />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/(tabs)/'));
  });

  it('leaves a signed-in app alone inside the tabs', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
      user: {
        id: 'u1',
        email: 'a@b.co',
        displayName: 'A',
        householdId: 'h1',
        role: 'MEMBER',
        totpEnabled: false,
      },
    });
    mockUseSegments.mockReturnValue(['(tabs)']);
    const replace = withRouter();
    await render(<RootLayout />);
    await waitFor(() => expect(replace).not.toHaveBeenCalled());
  });
});

describe('one QueryClient', () => {
  // A second, module-scope `new QueryClient()` inside the receipts screen used
  // to give the capture modal its own cache, so an upload invalidated queries
  // the receipt list never read (mobile inventory §4).
  it('is constructed exactly once in the whole app', () => {
    const root = path.join(__dirname, '..');
    const files: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          // src/test/ is Jest-only infrastructure (shared mocks, the test
          // QueryClient factory) rather than app code — it never ships, so
          // it's outside what "the whole app" means here.
          if (entry.name !== 'node_modules' && full !== path.join(root, 'src', 'test')) {
            walk(full);
          }
        } else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) {
          files.push(full);
        }
      }
    };
    walk(path.join(root, 'app'));
    walk(path.join(root, 'src'));

    const constructions = files.flatMap((file) => {
      const source = fs.readFileSync(file, 'utf-8');
      const count = source.split('new QueryClient(').length - 1;
      return Array.from({ length: count }, () => path.relative(root, file));
    });

    expect(constructions).toEqual(['src/api/queryClient.ts']);
  });
});
