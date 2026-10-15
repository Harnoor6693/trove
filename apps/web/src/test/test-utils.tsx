import { type ReactNode } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: 0 },
      mutations: { retry: false },
    },
  })
}

export function renderWithProviders(ui: ReactNode, opts: { initialPath?: string } = {}) {
  const client = makeQueryClient()
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[opts.initialPath ?? '/']} useTransitions={false}>{ui}</MemoryRouter>
      </QueryClientProvider>
    ),
  }
}
