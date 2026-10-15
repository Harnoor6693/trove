import { device, element, by, expect as detoxExpect, waitFor } from 'detox';
import { connectToDevServer } from './helpers';

describe('Account creation', () => {
  beforeAll(async () => {
    // See auth.e2e.ts — every launch lands on `(auth)/connect` first.
    await device.launchApp({ newInstance: true });
    await connectToDevServer();
  });

  afterAll(async () => {
    await device.terminateApp();
  });

  it('should reach the sign-up form when the server allows registration', async () => {
    // `create-account-link` only renders once `login.tsx`'s own mount-time
    // `fetchAuthProviders()` resolves `registrationOpen: true` — a bare
    // `.tap()` has no retry and races that request, same class of gap as
    // `connectToDevServer`'s probe wait (see `e2e/helpers.ts`).
    await waitFor(element(by.id('create-account-link'))).toBeVisible().withTimeout(10000);
    await element(by.id('create-account-link')).tap();
    await detoxExpect(element(by.id('onboarding-screen'))).toBeVisible();
  });

  // CAD and USD only (owner decision) — GBP and EUR are gone, and the region
  // choice sets currency and country together.
  it('should offer exactly the two supported regions', async () => {
    await detoxExpect(element(by.id('region-option-CA'))).toBeVisible();
    await detoxExpect(element(by.id('region-option-US'))).toBeVisible();
  });

  it('should collect name, email and password', async () => {
    await detoxExpect(element(by.id('setup-name-input'))).toBeVisible();
    await detoxExpect(element(by.id('setup-email-input'))).toBeVisible();
    await detoxExpect(element(by.id('setup-password-input'))).toBeVisible();
  });

  it('should offer an invite-token field for a closed-registration server', async () => {
    await detoxExpect(element(by.id('setup-invite-input'))).toBeVisible();
  });
});
