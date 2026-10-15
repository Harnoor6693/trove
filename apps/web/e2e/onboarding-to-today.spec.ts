import { test, expect } from '@playwright/test'

/**
 * First run, end to end, against a real `trove-core`.
 *
 * Rewritten for the step-20a flow. What changed, and why the old spec could not
 * pass: it drove a `/login` page that had no email or password field (the page
 * was one "Continue with Authentik" link), then walked six onboarding steps —
 * Welcome, Deployment, AI Mode, SimpleFIN, First Budget, Complete — of which two
 * no longer exist and one hard-blocked on a paid third-party token.
 *
 * The flow now is: sign in with a local account → three ungated onboarding steps
 * → Today. The load-bearing assertion is the third one: `onboardingCompleted` is
 * written at the end of step 1, so a reload from step 2 lands on Today rather
 * than back at the start of setup. That is the property that makes it impossible
 * to be locked inside onboarding.
 *
 * Requires a running stack and a seeded user:
 *   TROVE_E2E_BASE, TROVE_E2E_EMAIL, TROVE_E2E_PASSWORD.
 */
const BASE = process.env.TROVE_E2E_BASE ?? 'http://localhost:5173'
const EMAIL = process.env.TROVE_E2E_EMAIL ?? 'test@yopmail.com'
const PASSWORD = process.env.TROVE_E2E_PASSWORD ?? ''

test.describe('first-run onboarding to Today', () => {
  test.skip(!PASSWORD, 'TROVE_E2E_PASSWORD not set')

  test('signs in, completes three ungated steps, and lands on Today', async ({ page }) => {
    await page.goto(`${BASE}/login`)

    // The local-account form: no identity provider is required to sign in.
    await page.getByLabel(/email/i).fill(EMAIL)
    await page.getByLabel(/password/i).fill(PASSWORD)
    await page.getByRole('button', { name: /sign in/i }).click()

    // AuthGuard sends a user whose preferences say onboardingCompleted=false here.
    await page.waitForURL(/\/onboarding/, { timeout: 30_000 })
    await expect(page.getByText(/Step 1 of 3/)).toBeVisible()

    // An escape hatch is present on every step (defect I2).
    await expect(page.getByRole('button', { name: /sign out/i })).toBeVisible()

    // Step 1 — country and currency are shown as fixed facts from registration;
    // timezone and locale are the editable parts.
    await expect(page.getByTestId('onboarding-country')).toBeVisible()
    await page.getByRole('radio', { name: /Canadian English/i }).check()
    await page.getByRole('button', { name: /continue/i }).click()

    // Step 2 — connect data. Nothing here gates progress.
    await expect(page.getByText(/Step 2 of 3/)).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('button', { name: /skip for now/i }).first()).toBeVisible()
    await expect(page.getByTestId('onboarding-import-pending')).toBeVisible()

    // The property the whole redesign exists for: onboardingCompleted was written
    // at the end of step 1, so reloading mid-flow lands in the app, not back in
    // setup. Nobody can be trapped here.
    await page.goto(`${BASE}/`)
    await expect(page.getByTestId('today-heading')).toBeVisible({ timeout: 30_000 })

    // Go back and finish the optional remainder.
    await page.goto(`${BASE}/onboarding`)
    await page.getByRole('button', { name: /continue/i }).click()
    await expect(page.getByText(/Step 2 of 3/)).toBeVisible()
    await page.getByRole('button', { name: /^continue$/i }).click()

    // Step 3 — the "Finish setting up Trove" checklist.
    await expect(page.getByText(/Step 3 of 3/)).toBeVisible()
    await expect(page.getByTestId('finish-setup-checklist')).toBeVisible()
    await page.getByRole('button', { name: /go to today/i }).click()

    await page.waitForURL(`${BASE}/`, { timeout: 30_000 })
    await expect(page.getByTestId('today-heading')).toBeVisible()

    // Every amount on Today carries the currency the server sent with it — no
    // bare '$', and nothing rendered from a fixture.
    const net = page.getByTestId('today-net')
    await expect(net).toBeVisible()
    await expect(net).toHaveAttribute('data-currency', /^(CAD|USD)$/)

    // The sign-out control exists on an authenticated route (defect I2).
    await expect(page.getByRole('button', { name: /sign out/i })).toBeVisible()
  })

  test('a mistyped URL renders a branded 404, not the router overlay', async ({ page }) => {
    await page.goto(`${BASE}/no-such-page`)
    await expect(page.getByText(/doesn't exist/i)).toBeVisible()
  })
})
