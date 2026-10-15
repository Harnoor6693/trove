import { describe, expect, it } from 'vitest'
import { server } from '@/test/mswServer'
import { fail, mswState } from '@/test/msw/handlers'
import { http, HttpResponse } from 'msw'
import { ApiError } from '@api/client'
import { selectIsAuthenticated, useAuthStore } from './authStore'

describe('authStore', () => {
  it('starts hydrating so the guard never decides before /v1/auth/me answers', () => {
    expect(useAuthStore.getState().status).toBe('hydrating')
  })

  it('goes authenticated with the camelCase identity from /v1/auth/me', async () => {
    await useAuthStore.getState().hydrate()
    const state = useAuthStore.getState()
    expect(state.status).toBe('authenticated')
    expect(selectIsAuthenticated(state)).toBe(true)
    expect(state.user?.userId).toBe('00000000-0000-0000-0000-000000000001')
    expect(state.user?.displayName).toBe('Test User')
    expect(state.user?.role).toBe('ADMIN')
    expect(state.user?.currency).toBe('CAD')
    expect(state.hydrationError).toBeNull()
  })

  it('treats an anonymous 401 as "anonymous", not as an error', async () => {
    mswState.authenticated = false
    await useAuthStore.getState().hydrate()
    const state = useAuthStore.getState()
    expect(state.status).toBe('anonymous')
    expect(state.user).toBeNull()
    expect(state.hydrationError).toBeNull()
  })

  it('surfaces a 5xx as a hydration error instead of bouncing to /login', async () => {
    server.use(http.get('*/v1/auth/me', () => fail(500, 'internal-server-error', 'Server Error')))
    await useAuthStore.getState().hydrate()
    const state = useAuthStore.getState()
    expect(state.status).toBe('anonymous')
    expect(state.hydrationError).toBeInstanceOf(ApiError)
    expect(state.hydrationError?.status).toBe(500)
  })

  it('surfaces an unreachable server as a hydration error', async () => {
    server.use(http.get('*/v1/auth/me', () => HttpResponse.error()))
    await useAuthStore.getState().hydrate()
    expect(useAuthStore.getState().hydrationError?.isNetworkError).toBe(true)
  })

  it('coalesces concurrent hydrate() callers into one probe (StrictMode guard)', async () => {
    await Promise.all([
      useAuthStore.getState().hydrate(),
      useAuthStore.getState().hydrate(),
      useAuthStore.getState().hydrate(),
    ])
    expect(mswState.meCalls).toBe(1)
  })

  it('markExpired distinguishes an expired session from an anonymous one', () => {
    useAuthStore.getState().markExpired()
    expect(useAuthStore.getState().status).toBe('expired')
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('logout clears the session even when the server call fails', async () => {
    await useAuthStore.getState().hydrate()
    expect(useAuthStore.getState().status).toBe('authenticated')
    server.use(http.post('*/v1/auth/logout', () => HttpResponse.error()))
    await useAuthStore.getState().logout()
    expect(useAuthStore.getState().status).toBe('anonymous')
    expect(useAuthStore.getState().user).toBeNull()
  })
})
