import { test, expect } from "@playwright/test";

/**
 * Optional-OIDC sign-in.
 *
 * Rewritten: the old spec drove an Authentik-hosted form (`input[name='uidField']`)
 * and waited for `/dashboard`, a route that does not exist. Authentik is no longer
 * a hard dependency (owner decision) — OIDC is one optional provider, advertised
 * by `GET /v1/auth/providers`, and the button only renders when it is configured.
 * So this spec skips itself unless an OIDC provider is actually enabled.
 */
const BASE = process.env.TROVE_E2E_BASE ?? "http://localhost:5173";

test("the OIDC button appears only when a provider is configured", async ({ page, request }) => {
  const res = await request.get(`${BASE}/api/v1/auth/providers`);
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  const oidcEnabled: boolean = body?.data?.oidc?.enabled ?? false;

  await page.goto(`${BASE}/login`);
  await expect(page.getByLabel(/email/i)).toBeVisible();

  const button = page.getByRole("link", { name: /continue with/i });
  if (oidcEnabled) {
    await expect(button).toBeVisible();
  } else {
    await expect(button).toHaveCount(0);
  }
});
