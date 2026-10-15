import { test, expect, type Page } from '@playwright/test'

/**
 * D1-01: several writes in a row from one signed-in browser.
 *
 * The server used to answer every successful authenticated request with a
 * Set-Cookie that deleted `XSRF-TOKEN`, so the next write went out with no
 * `X-XSRF-TOKEN` and was refused (201, 403, 201 for three `POST /v1/accounts`;
 * the import wizard's commit failed on first use). Server-side the fix is held by
 * `CsrfCookieLifecycleIT`; this spec holds it through a real browser cookie jar
 * and whatever sits between the browser and `trove-core` (Vite's proxy, or
 * Traefik in a deployed stack).
 *
 * Signs in through the real form. The writes are then sent from inside the page
 * with exactly the headers `src/api/client.ts` adds: `X-Requested-With`, and
 * `X-XSRF-TOKEN` read from `document.cookie` at send time, so a deleted cookie
 * means no header, as in the app. Driving the Accounts page instead would tie
 * the spec to the seeded user's onboarding state. It creates two manual accounts
 * and deletes them again: four writes, and the seeded user is left as it was.
 *
 * Requires a running stack and a seeded user:
 *   TROVE_E2E_BASE, TROVE_E2E_EMAIL, TROVE_E2E_PASSWORD.
 */
const BASE = process.env.TROVE_E2E_BASE ?? 'http://localhost:5173'
const EMAIL = process.env.TROVE_E2E_EMAIL ?? 'test@yopmail.com'
const PASSWORD = process.env.TROVE_E2E_PASSWORD ?? ''

/** One write, sent the way `client.ts` sends it. */
async function write(page: Page, method: string, path: string, body?: unknown) {
  return page.evaluate(
    async ({ method, path, body }) => {
      const cookie = document.cookie
        .split(';')
        .map((part) => part.trim())
        .find((part) => part.startsWith('XSRF-TOKEN='))
      const token = cookie ? decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) : null
      const headers: Record<string, string> = { 'X-Requested-With': 'XMLHttpRequest' }
      if (token) headers['X-XSRF-TOKEN'] = token
      if (body !== undefined) headers['Content-Type'] = 'application/json'
      const res = await fetch(`/api${path}`, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      const json = await res.json().catch(() => null)
      return { status: res.status, sentToken: token !== null, id: json?.data?.id as string | undefined }
    },
    { method, path, body }
  )
}

test.describe('cookie-session writes', () => {
  test.skip(!PASSWORD, 'TROVE_E2E_PASSWORD not set')

  test('consecutive writes all succeed and the XSRF-TOKEN cookie survives them', async ({ page }) => {
    await page.goto(`${BASE}/login`)
    await page.getByLabel(/email/i).fill(EMAIL)
    await page.getByLabel(/password/i).fill(PASSWORD)
    await page.getByRole('button', { name: /sign in/i }).click()
    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30_000 })

    const xsrf = async () =>
      (await page.context().cookies(BASE)).find((c) => c.name === 'XSRF-TOKEN')?.value
    expect(await xsrf(), 'the token is there right after login').toBeTruthy()

    const ids: string[] = []
    for (const name of ['D1-01 first', 'D1-01 second']) {
      const created = await write(page, 'POST', '/v1/accounts', {
        sourceKind: 'MANUAL',
        displayName: name,
        accountType: 'CHECKING',
      })
      expect(created, `creating "${name}"`).toMatchObject({ status: 201, sentToken: true })
      ids.push(created.id!)
      expect(await xsrf(), `the token survives creating "${name}"`).toBeTruthy()
    }

    for (const id of ids) {
      const deleted = await write(page, 'DELETE', `/v1/accounts/${id}`)
      expect(deleted, `deleting ${id}`).toMatchObject({ status: 200, sentToken: true })
    }
    expect(await xsrf(), 'the token survives all four writes').toBeTruthy()
  })
})
