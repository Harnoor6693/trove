import { device, element, by, expect as detoxExpect } from 'detox';

/**
 * NOT RUNNABLE YET. `trove://dev-login` is a route the app does not implement —
 * there is no deep-link sign-in shortcut anywhere in `app/`, and every
 * assertion below needs an authenticated session to reach the tab bar.
 *
 * Detox is `workflow_dispatch`-only in `mobile-ci.yml`, so this file does not
 * gate anything today. Step 21b, which builds the data screens these tabs
 * lead to, owns the fixture that makes it real: either a seeded server the
 * harness signs into through `(auth)/connect` + `(auth)/login`, or a
 * `__DEV__`-only deep link that this comment stops anyone from assuming
 * already exists.
 *
 * Skipped (not deleted): jest.config.js's testMatch globs every e2e/**\/*.e2e.ts,
 * so an unskipped file here fails every local `npm run test:e2e:ios` run for a
 * fixture gap this file already documents as belonging to step 21b, not step 27.
 * Un-skip once that fixture lands.
 */
describe.skip('Tab navigation', () => {
  beforeAll(async () => {
    await device.launchApp({ newInstance: true });
    await device.openURL({ url: 'trove://dev-login' });
  });

  afterAll(async () => {
    await device.terminateApp();
  });

  it('should show net worth tab button', async () => {
    await detoxExpect(element(by.id('tab-net-worth'))).toBeVisible();
  });

  it('should show subscriptions tab button', async () => {
    await detoxExpect(element(by.id('tab-subscriptions'))).toBeVisible();
  });

  // Receipts used to render as an undeclared ninth tab; Tax is cut with the
  // tax engine.
  it('should show receipts tab button', async () => {
    await detoxExpect(element(by.id('tab-receipts'))).toBeVisible();
  });

  it('should not show a tax tab', async () => {
    await detoxExpect(element(by.id('tab-tax'))).not.toBeVisible();
  });

  it('should navigate to settings', async () => {
    await element(by.id('tab-settings')).tap();
    await detoxExpect(element(by.id('settings-screen'))).toBeVisible();
  });
});
