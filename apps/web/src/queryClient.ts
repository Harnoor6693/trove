import { QueryClient } from '@tanstack/react-query'

/** The one app-wide client; a module so the auth store can clear it when a session ends. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})
