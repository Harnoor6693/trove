import { device, element, by, expect as detoxExpect } from 'detox';

describe('Connect to a server', () => {
  beforeAll(async () => {
    await device.launchApp({ newInstance: true, delete: true });
  });

  afterAll(async () => {
    await device.terminateApp();
  });

  it('should refuse an http address on a public host', async () => {
    await element(by.id('connect-url-input')).replaceText('http://example.com');
    await element(by.id('connect-submit-btn')).tap();
    await detoxExpect(element(by.id('connect-error-text'))).toBeVisible();
  });

  it('should offer an opt-in for http on the local network', async () => {
    await detoxExpect(element(by.id('connect-allow-http-toggle'))).toBeVisible();
  });

  it('should report an address that is not a Trove server', async () => {
    await element(by.id('connect-url-input')).replaceText('https://example.com');
    await element(by.id('connect-submit-btn')).tap();
    await detoxExpect(element(by.id('connect-error-text'))).toBeVisible();
  });
});
