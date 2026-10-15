import { AppState } from 'react-native';
import { focusManager, QueryClient } from '@tanstack/react-query';

// React Native has no window focus: without this `refetchOnWindowFocus` never
// fires, and a screen left open through an hour in the background keeps its
// stale numbers (B5-06). `@react-native-community/netinfo` is not a
// dependency, so `onlineManager` is left at its default (always online); wiring
// it is a separate decision because it adds a native module.
focusManager.setEventListener((handleFocus) => {
  const subscription = AppState.addEventListener('change', (state) => {
    handleFocus(state === 'active');
  });
  return () => subscription.remove();
});

/**
 * The **only** `QueryClient` in the app. A second, module-scope client used to
 * wrap the receipt-capture modal, so an upload invalidated a cache the receipt
 * list never read and a captured receipt stayed invisible until the next cold
 * start (mobile inventory §4). One client, one cache.
 *
 * It lives in its own module so `authStore` can empty it when a session ends
 * (B5-02) without importing the router layout.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 60_000, retry: 2 },
  },
});
