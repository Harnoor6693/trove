import { device, element, by, expect as detoxExpect } from 'detox';

describe('App smoke test', () => {
  beforeAll(async () => {
    await device.launchApp({ newInstance: true });
  });

  afterAll(async () => {
    await device.terminateApp();
  });

  // First launch is "connect to your server", not "sign in": every user runs
  // their own trove-core, so the app has no server until one is entered
  // (readiness gap P4-19).
  it('should display the connect screen on a fresh install', async () => {
    await detoxExpect(element(by.id('connect-screen'))).toBeVisible();
  });

  it('should render the server address field', async () => {
    await detoxExpect(element(by.id('connect-url-input'))).toBeVisible();
  });
});
