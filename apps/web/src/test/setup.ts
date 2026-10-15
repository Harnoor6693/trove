import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest'
import { server } from './mswServer'
import { resetInvites, resetMswState } from './msw/handlers'
import { __resetRefreshState } from '@api/client'
import { __resetAuthStore } from '@stores/authStore'

// jsdom's Storage is incomplete — provide a complete one.
const _store: Record<string, string> = {}
const localStorageMock: Storage = {
  getItem: (key: string) => _store[key] ?? null,
  setItem: (key: string, value: string) => {
    _store[key] = String(value)
  },
  removeItem: (key: string) => {
    delete _store[key]
  },
  clear: () => {
    Object.keys(_store).forEach((k) => delete _store[k])
  },
  get length() {
    return Object.keys(_store).length
  },
  key: (index: number) => Object.keys(_store)[index] ?? null,
}
Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock, writable: true })

// jsdom does not implement scrollIntoView.
if (typeof Element !== 'undefined' && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  localStorage.clear()
  // Assigning '' does not clear a cookie — each one has to be expired by name,
  // or the CSRF-token case leaks its header into every later test.
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0]?.trim()
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
  resetMswState()
  resetInvites()
  __resetRefreshState()
  __resetAuthStore()
})
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
