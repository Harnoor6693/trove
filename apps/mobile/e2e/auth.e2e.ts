import { device, element, by, expect as detoxExpect } from 'detox';
import { connectToDevServer } from './helpers';

describe('Auth screens', () => {
  beforeAll(async () => {
    // The app has no baked-in server (readiness gap P4-19) — every launch
    // lands on `(auth)/connect` first, dev default pre-filled but not
    // auto-submitted, so the suite has to drive that tap itself.
    await device.launchApp({ newInstance: true });
    await connectToDevServer();
  });

  afterAll(async () => {
    await device.terminateApp();
  });

  it('should show email and password inputs and a submit button', async () => {
    await detoxExpect(element(by.id('login-screen'))).toBeVisible();
    await detoxExpect(element(by.id('login-email-input'))).toBeVisible();
    await detoxExpect(element(by.id('login-password-input'))).toBeVisible();
    await detoxExpect(element(by.id('login-submit-btn'))).toBeVisible();
  });

  it('should show one enumeration-safe error for invalid credentials', async () => {
    await element(by.id('login-email-input')).replaceText('bad@example.com');
    await element(by.id('login-password-input')).replaceText('wrongpassword');
    await element(by.id('login-submit-btn')).tap();
    await detoxExpect(element(by.id('login-error-text'))).toBeVisible();
  });

  it('should offer a route back to the connect screen', async () => {
    await detoxExpect(element(by.id('change-server-link'))).toBeVisible();
  });
});
