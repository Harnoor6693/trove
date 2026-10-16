import { localDateIso } from './localDate';

// Jest runs in Pacific/Auckland (src/test/globalSetup.js), where the local
// day is ahead of the UTC day for the first hours of every morning.
describe('localDateIso', () => {
  it('uses the local calendar day, not the UTC one', () => {
    const early = new Date(2026, 9, 5, 0, 30);
    expect(early.toISOString().slice(0, 10)).toBe('2026-10-04');
    expect(localDateIso(early)).toBe('2026-10-05');
  });

  it('pads month and day', () => {
    expect(localDateIso(new Date(2026, 0, 3, 12))).toBe('2026-01-03');
  });
});
