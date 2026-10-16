import { defaultTaxYear } from './taxYear';

afterEach(() => {
  jest.useRealTimers();
});

describe('defaultTaxYear', () => {
  it.each([
    ['2026-01-05T12:00:00Z', 2025],
    ['2026-04-20T12:00:00Z', 2025],
    ['2026-05-02T12:00:00Z', 2026],
    ['2026-12-31T12:00:00Z', 2026],
  ])('on %s defaults to %s', (iso, expected) => {
    jest.useFakeTimers().setSystemTime(new Date(iso));
    expect(defaultTaxYear('America/Toronto')).toBe(expected);
  });

  it('reads the month in the member time zone, not the device one', () => {
    // 01:00 UTC on 1 May is still 30 April in Toronto, so still filing season.
    jest.useFakeTimers().setSystemTime(new Date('2026-05-01T01:00:00Z'));
    expect(defaultTaxYear('America/Toronto')).toBe(2025);
    expect(defaultTaxYear('Pacific/Auckland')).toBe(2026);
    // 03:00 UTC on 1 Jan is still 31 Dec in Toronto: the old year, in season anyway.
    jest.setSystemTime(new Date('2027-01-01T03:00:00Z'));
    expect(defaultTaxYear('America/Toronto')).toBe(2026);
  });

  it('falls back to the device calendar for an unknown zone', () => {
    jest.useFakeTimers().setSystemTime(new Date('2026-07-15T12:00:00Z'));
    expect(defaultTaxYear('Not/AZone')).toBe(2026);
  });
});
