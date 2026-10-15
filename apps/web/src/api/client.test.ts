import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mswServer'
import {
  api,
  ApiError,
  CSRF_COOKIE_NAME,
  CSRF_HEADER_NAME,
  sessionFetch,
  sessionHeaders,
  unwrap,
} from './client'
import { streamCoachTurn } from './coach'
import { fetchAuthenticatedFile } from './binary'
import { DEFAULT_ME, mswState, ok, fail } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'

describe('api client — headers', () => {
  it('sends X-Requested-With on every request', async () => {
    let seen: string | null = null
    server.use(
      http.get('*/v1/categories', ({ request }) => {
        seen = request.headers.get('X-Requested-With')
        return ok([])
      })
    )
    await api.get('/v1/categories')
    expect(seen).toBe('XMLHttpRequest')
  })

  it('echoes the XSRF-TOKEN cookie back as the X-XSRF-TOKEN header', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=abc123`
    let seen: string | null = null
    server.use(
      http.get('*/v1/categories', ({ request }) => {
        seen = request.headers.get(CSRF_HEADER_NAME)
        return ok([])
      })
    )
    await api.get('/v1/categories')
    expect(seen).toBe('abc123')
  })
})

describe('api client — ApiError', () => {
  it('types the §5.4 error body, including camelCase fieldErrors', async () => {
    server.use(
      http.post('*/v1/auth/register', () =>
        fail(400, 'validation-failed', 'Bad Request', {
          detail: 'Password is too short',
          fieldErrors: [{ field: 'password', message: 'Use at least 12 characters.' }],
        })
      )
    )
    const error = await api
      .post('/v1/auth/register', {}, { skipAuthRefresh: true })
      .then(() => null)
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(400)
    expect(apiError.code).toBe('validation-failed')
    expect(apiError.detail).toBe('Password is too short')
    expect(apiError.fieldError('password')).toBe('Use at least 12 characters.')
    expect(apiError.requestId).toBe('test-request-id')
  })

  it('flags an unreachable server as a network error rather than a status', async () => {
    server.use(http.get('*/v1/categories', () => HttpResponse.error()))
    const error = await api
      .get('/v1/categories')
      .then(() => null)
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).isNetworkError).toBe(true)
  })
})

describe('api client — refresh behaviour', () => {
  it('does NOT refresh on the initial /v1/auth/me hydrate 401 (defect I4)', async () => {
    mswState.authenticated = false
    await useAuthStore.getState().hydrate()

    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('anonymous')
    // An anonymous 401 is not an error, and must not look like an expired session.
    expect(useAuthStore.getState().hydrationError).toBeNull()
  })

  it('coalesces concurrent 401s into a single refresh (single-flight)', async () => {
    let attempts = 0
    server.use(
      http.get('*/v1/categories', () => {
        attempts += 1
        // Every request 401s until the refresh lands, then all replays succeed.
        return mswState.refreshCalls === 0 ? fail(401, 'unauthorized', 'Unauthorized') : ok([])
      })
    )

    const results = await Promise.all([
      api.get('/v1/categories'),
      api.get('/v1/categories'),
      api.get('/v1/categories'),
    ])

    expect(mswState.refreshCalls).toBe(1)
    expect(results).toHaveLength(3)
    expect(attempts).toBe(6) // three 401s, then three successful replays
  })

  it('marks the session expired when the refresh itself fails', async () => {
    mswState.refreshSucceeds = false
    server.use(http.get('*/v1/categories', () => fail(401, 'unauthorized', 'Unauthorized')))

    await expect(api.get('/v1/categories')).rejects.toBeInstanceOf(ApiError)
    expect(mswState.refreshCalls).toBe(1)
    expect(useAuthStore.getState().status).toBe('expired')
  })

  it('gives up after one replay rather than looping on a 401', async () => {
    let calls = 0
    server.use(
      http.get('*/v1/categories', () => {
        calls += 1
        return fail(401, 'unauthorized', 'Unauthorized')
      })
    )
    await expect(api.get('/v1/categories')).rejects.toBeInstanceOf(ApiError)
    expect(mswState.refreshCalls).toBe(1)
    expect(calls).toBe(2) // the original and exactly one replay
    expect(useAuthStore.getState().status).toBe('expired')
  })

  it('does refresh for /v1/auth/me when the caller opts in', async () => {
    // Proves the flag, not the path, is what suppresses the refresh: the same
    // endpoint behaves differently depending on whether the caller knows a
    // session should exist.
    let calls = 0
    server.use(
      http.get('*/v1/auth/me', () => {
        calls += 1
        return calls === 1 ? fail(401, 'unauthorized', 'Unauthorized') : ok(DEFAULT_ME)
      })
    )
    await api.get('/v1/auth/me', { skipAuthRefresh: false })
    expect(mswState.refreshCalls).toBe(1)
  })

  it('never refreshes for /v1/auth/login, which is how a session begins', async () => {
    server.use(http.post('*/v1/auth/login', () => fail(401, 'invalid-credentials', 'Unauthorized')))
    await expect(
      api.post('/v1/auth/login', { email: 'a@b.c', password: 'x' })
    ).rejects.toBeInstanceOf(ApiError)
    expect(mswState.refreshCalls).toBe(0)
  })
})

describe('api client — unwrap', () => {
  it('peels the ApiResponse envelope', async () => {
    const res = await api.get('/v1/categories')
    // The fixture (`makeCategories`, step 202) is two system rows plus a
    // household parent/category pair, so the tree-rendering tests have
    // something real to nest.
    expect(unwrap(res)).toHaveLength(4)
  })

  it('raises when a 2xx carries success:false', () => {
    expect(() =>
      unwrap({
        data: { success: false, error: { type: 'x', title: 'Nope', status: 409 } },
        status: 200,
      } as never)
    ).toThrow(ApiError)
  })

  it('raises rather than handing a component undefined data', () => {
    expect(() => unwrap({ data: { success: true }, status: 200 } as never)).toThrow(ApiError)
  })
})

/**
 * `sessionFetch` is the SECOND transport in this app, and it carries the same
 * defences as the axios instance above: both CSRF headers, `credentials:
 * 'include'`, and the one single-flight refresh. None of that is shared code
 * with the interceptors, so none of it is covered by the cases above — and this
 * is the seam families C and E are told to reuse for the tax and data exports,
 * so a regression that dropped `X-Requested-With` here would send every non-JSON
 * mutation without its CSRF header. These cases go through the REAL call sites
 * (`streamCoachTurn`, `fetchAuthenticatedFile`) so a helper that stopped routing
 * through `sessionFetch` fails them too.
 */
describe('api client — sessionFetch, the fetch twin', () => {
  it('sends both CSRF defences on the streamed completion POST', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=stream-token`
    let requestedWith: string | null = null
    let csrf: string | null = null
    server.use(
      http.post('*/v1/chat/completions', ({ request }) => {
        requestedWith = request.headers.get('X-Requested-With')
        csrf = request.headers.get(CSRF_HEADER_NAME)
        return new HttpResponse('data: [DONE]\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'X-Session-Id': 'sess-1' },
        })
      })
    )

    await streamCoachTurn({ question: 'Anything', sessionId: null })

    expect(requestedWith).toBe('XMLHttpRequest')
    expect(csrf).toBe('stream-token')
  })

  it('sends both CSRF defences on the authenticated binary GET', async () => {
    document.cookie = `${CSRF_COOKIE_NAME}=image-token`
    let requestedWith: string | null = null
    let csrf: string | null = null
    let credentials: RequestCredentials | undefined
    server.use(
      http.get('*/v1/receipts/:id/thumbnail', ({ request }) => {
        requestedWith = request.headers.get('X-Requested-With')
        csrf = request.headers.get(CSRF_HEADER_NAME)
        credentials = request.credentials
        return new HttpResponse(new Uint8Array([0xff, 0xd8, 0xff]), {
          status: 200,
          headers: { 'Content-Type': 'image/jpeg', 'X-Content-Type-Options': 'nosniff' },
        })
      })
    )

    const file = await fetchAuthenticatedFile('/v1/receipts/rcpt-1/thumbnail')

    expect(requestedWith).toBe('XMLHttpRequest')
    expect(csrf).toBe('image-token')
    expect(file.contentType).toBe('image/jpeg')
    expect(credentials).toBe('include')
  })

  it('cannot have its CSRF header overridden by a caller', () => {
    document.cookie = `${CSRF_COOKIE_NAME}=real-token`
    // A later family passing this key — by accident or by copying a snippet —
    // must not be able to turn the header `CsrfRequiresHeaderFilter` demands
    // into something the server refuses, because the only symptom would be a
    // 403 with no local signal at all.
    const headers = sessionHeaders({
      'Content-Type': 'application/json',
      'X-Requested-With': 'fetch',
    })
    expect(headers['X-Requested-With']).toBe('XMLHttpRequest')
    expect(headers[CSRF_HEADER_NAME]).toBe('real-token')
    expect(headers['Content-Type']).toBe('application/json')
  })

  it('runs the shipped single-flight refresh ONCE on a 401 and replays', async () => {
    let calls = 0
    server.use(
      http.get('*/v1/receipts/:id/thumbnail', () => {
        calls += 1
        if (calls === 1) return fail(401, 'unauthorized', 'Unauthorized')
        return new HttpResponse(new Uint8Array([0xff, 0xd8]), {
          status: 200,
          headers: { 'Content-Type': 'image/jpeg' },
        })
      })
    )

    const file = await fetchAuthenticatedFile('/v1/receipts/rcpt-1/thumbnail')

    expect(mswState.refreshCalls).toBe(1)
    expect(calls).toBe(2) // the original and exactly one replay
    expect(file.contentType).toBe('image/jpeg')
  })

  it('marks the session expired when the refresh itself fails, rather than looping', async () => {
    mswState.refreshSucceeds = false
    let calls = 0
    server.use(
      http.get('*/v1/receipts/:id/thumbnail', () => {
        calls += 1
        return fail(401, 'unauthorized', 'Unauthorized')
      })
    )

    await expect(fetchAuthenticatedFile('/v1/receipts/rcpt-1/thumbnail')).rejects.toBeInstanceOf(
      ApiError
    )
    expect(mswState.refreshCalls).toBe(1)
    expect(calls).toBe(1) // no replay when the refresh never succeeded
    expect(useAuthStore.getState().status).toBe('expired')
  })

  it('treats a 429 as a wait-and-retry status, never as an expiry', async () => {
    server.use(
      http.get('*/v1/receipts/:id/thumbnail', () =>
        fail(
          429,
          'rate-limited',
          'Too Many Requests',
          { detail: 'Slow down.' },
          { 'Retry-After': '30' }
        )
      )
    )

    const error = await fetchAuthenticatedFile('/v1/receipts/rcpt-1/thumbnail')
      .then(() => null)
      .catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(429)
    expect((error as ApiError).code).toBe('rate-limited')
    // A 429 is NOT an expired session: no refresh, no logout.
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).not.toBe('expired')
  })

  it('rejects a non-2xx as the same ApiError every axios call site already handles', async () => {
    server.use(
      http.get('*/v1/receipts/:id/thumbnail', () =>
        fail(404, 'not-found', 'Not Found', { detail: 'No such receipt.' })
      )
    )

    await expect(sessionFetch('/v1/receipts/nope/thumbnail')).rejects.toBeInstanceOf(ApiError)
  })
})
