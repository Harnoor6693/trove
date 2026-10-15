import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  // Every spec here drives the same real local server, and there is only one
  // seeded account (the local topology's FirstRunBootstrap admin, closed
  // registration). auth-flow/onboarding-to-today/smoke all read or mutate
  // that one account's server-side state (onboardingCompleted in
  // particular), so running spec files concurrently races: e.g. smoke.spec's
  // login could observe (or complete) onboarding mid-flight through
  // onboarding-to-today.spec's own run. One worker, file order, no races.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    ignoreHTTPSErrors: true,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
});
