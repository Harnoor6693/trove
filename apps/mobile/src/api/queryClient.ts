import { QueryClient } from '@tanstack/react-query';

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
