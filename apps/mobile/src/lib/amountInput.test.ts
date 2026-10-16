import { normaliseAmountInput } from './amountInput';

describe('normaliseAmountInput', () => {
  it.each([
    ['12,34', '12.34'],
    ['  12,5 ', '12.5'],
    ['1,234', '1234'],
    ['1,234,567', '1234567'],
    ['1,234.50', '1234.50'],
    ['1.234,56', '1234.56'],
    ['12.34', '12.34'],
    ['-12,34', '-12.34'],
    ['', ''],
    ['abc', 'abc'],
  ])('%j -> %j', (raw, expected) => {
    expect(normaliseAmountInput(raw)).toBe(expected);
  });
});
