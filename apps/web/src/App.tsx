import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { useAuthStore } from '@stores/authStore'
import { router } from './router'
import '@styles/index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

export default function App() {
  // Probe /me once at app mount so AuthGuard has a hydrated auth state on its
  // first render. Without this, AuthGuard's first render decides Navigate-to-
  // login before the /me probe could confirm the cookie-backed session — which
  // loops the user back to /login right after a successful OIDC callback.
  useEffect(() => {
    void useAuthStore.getState().hydrate()
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} useTransitions={false} />
    </QueryClientProvider>
  )
}
