import React, { useEffect } from 'react';
import { Slot, useRouter, useSegments } from 'expo-router';
import { QueryClientProvider } from '@tanstack/react-query';
import * as SplashScreen from 'expo-splash-screen';
import { useTroveFonts } from '@design/useFonts';
import { AppLock } from '@/security/AppLock';
import { queryClient } from '@api/queryClient';
import { useAuthStore } from '@stores/authStore';
import { useUiStore } from '@stores/uiStore';
import { sweepStaleExports } from '@lib/shareFile';

SplashScreen.preventAutoHideAsync();

/**
 * Routes on `authStore.status`, and only once the persisted server and
 * session have been read — the old guard compared `isAuthed` against
 * `undefined` while the store initialised it to `false`, so the login form
 * flashed on every launch (readiness gap P4-48).
 */
function AuthGuard() {
  const status = useAuthStore((s) => s.status);
  const hydrated = useAuthStore((s) => s.hydrated);
  const router = useRouter();
  const segments = useSegments();

  useEffect(() => {
    if (!hydrated) return;
    const [group, screen] = segments as string[];
    const inAuth = group === '(auth)';

    if (status === 'disconnected') {
      if (screen !== 'connect') router.replace('/(auth)/connect');
      return;
    }
    if (status === 'anonymous') {
      // No segments yet means the router has not applied the launch URL, so a
      // cold-start `trove://reset-password?token=…` would be replaced by login
      // before it landed (step 27.7). Decide once the route is known.
      if (!inAuth && segments.length > 0) router.replace('/(auth)/login');
      return;
    }
    if (status === 'authenticated' && inAuth) {
      router.replace('/(tabs)/');
    }
  }, [status, hydrated, segments, router]);

  return <Slot />;
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useTroveFonts();
  const hydrateAuth = useAuthStore((s) => s.hydrate);
  const hydrateSettings = useUiStore((s) => s.hydrate);
  const authHydrated = useAuthStore((s) => s.hydrated);
  const settingsHydrated = useUiStore((s) => s.hydrated);

  useEffect(() => {
    void hydrateSettings();
    void hydrateAuth();
  }, [hydrateAuth, hydrateSettings]);

  // An export left in the cache by a share the app was killed during.
  useEffect(() => sweepStaleExports(), []);

  // A font that fails to load must not hang the splash screen forever, so a
  // font error counts as "done" and the app renders with system fonts.
  const fontsSettled = fontsLoaded || fontError !== null;
  const ready = fontsSettled && authHydrated && settingsHydrated;

  useEffect(() => {
    if (ready) void SplashScreen.hideAsync();
  }, [ready]);

  if (!ready) return null;

  return (
    <QueryClientProvider client={queryClient}>
      <AppLock>
        <AuthGuard />
      </AppLock>
    </QueryClientProvider>
  );
}
