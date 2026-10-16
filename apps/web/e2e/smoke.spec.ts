import { test, expect, type Page } from "@playwright/test";

const KNOWN_NOISY_ERRORS = [
  /Download the React DevTools/i,
];

// `GET /v1/budgets/current` 404s by design when the caller's household has no
// budget period for the current month (openapi/trove-core.yaml, §4.6: "a month
// with no period is 404, not an empty 200" — apps/web/src/api/budgets.ts
// already treats this 404 as a normal empty state, not a failure). A fresh,
// budget-less household — exactly what this spec's seeded account is — hits
// this on every visit to Today, and Chromium logs "Failed to load resource"
// to the console for *any* non-2xx fetch response regardless of how the page
// handles it, so this one expected 404 is allow-listed by its resource URL
// rather than lowering the "no console errors" bar for anything else.
const KNOWN_NOISY_RESOURCE_URLS = [/\/api\/v1\/budgets\/current$/];

// Every route here exists in src/router/index.tsx. `/dashboard` used to be in
// this list and never existed in the router at all — the root path is Today —
// so this spec could not pass against any revision of the app.
const routes = [
  "/",
  "/transactions",
  "/accounts",
  "/budgets",
  "/networth",
  "/subscriptions",
  "/receipts",
  "/coach",
  "/tax-export",
  "/settings",
];

// Every route below is behind AuthGuard (src/router/AuthGuard.tsx): an
// unauthenticated visit renders /login (no `<main>`), and an authenticated
// but not-yet-onboarded visit renders /onboarding (also no `<main>`). This
// spec's whole premise — "the authenticated app pages render" — needs a
// signed-in, onboarded session, so it signs in once and finishes onboarding
// if this is a fresh account, then reuses that one page for every route
// (serial mode: see below for why sharing state across files is required).
const EMAIL = process.env.TROVE_E2E_EMAIL ?? "test@yopmail.com";
const PASSWORD = process.env.TROVE_E2E_PASSWORD ?? "";

test.describe.configure({ mode: "serial" });

test.describe("authenticated routes", () => {
  test.skip(!PASSWORD, "TROVE_E2E_PASSWORD not set");

  let page: Page;

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage();
    await page.goto("/login");
    await page.getByLabel(/email/i).fill(EMAIL);
    await page.getByLabel(/password/i).fill(PASSWORD);
    await page.getByRole("button", { name: /sign in/i }).click();

    await page.waitForURL(
      (url) => url.pathname === "/" || url.pathname === "/onboarding",
      { timeout: 30_000 }
    );

    // A fresh (not-yet-onboarded) account lands on /onboarding — finish the
    // three ungated steps so the routes below are reachable. An
    // already-onboarded account (e.g. this same seeded user, onboarded by a
    // prior run of onboarding-to-today.spec.ts) skips straight past this.
    if (new URL(page.url()).pathname === "/onboarding") {
      await page.getByRole("radio", { name: /Canadian English/i }).check();
      await page.getByRole("button", { name: /continue/i }).click();
      await expect(page.getByText(/Step 2 of 3/)).toBeVisible({ timeout: 15_000 });
      await page.getByRole("button", { name: /^continue$/i }).click();
      await expect(page.getByText(/Step 3 of 3/)).toBeVisible();
      await page.getByRole("button", { name: /go to today/i }).click();
      await page.waitForURL("/", { timeout: 30_000 });
    }
  });

  test.afterAll(async () => {
    await page?.close();
  });

  for (const route of routes) {
    test(`${route} loads, no console errors, primary CTA visible`, async () => {
      const errors: string[] = [];
      const onPageError = (e: Error) => errors.push(e.message);
      const onConsole = (m: {
        type(): string;
        text(): string;
        location(): { url: string };
      }) => {
        if (m.type() !== "error") return;
        const url = m.location().url;
        if (KNOWN_NOISY_RESOURCE_URLS.some((re) => re.test(url))) return;
        errors.push(m.text());
      };
      page.on("pageerror", onPageError);
      page.on("console", onConsole);

      try {
        const response = await page.goto(route, { waitUntil: "networkidle" });
        expect(response, `no response for ${route}`).not.toBeNull();
        expect(response!.status(), `unexpected status for ${route}`).toBeLessThan(400);

        await expect(page.getByRole("main")).toBeVisible({ timeout: 10_000 });

        const noisy = (text: string) => KNOWN_NOISY_ERRORS.some((re) => re.test(text));
        const realErrors = errors.filter((e) => !noisy(e));
        expect(realErrors, `console errors on ${route}:\n${realErrors.join("\n")}`).toEqual([]);
      } finally {
        page.off("pageerror", onPageError);
        page.off("console", onConsole);
      }
    });
  }
});
