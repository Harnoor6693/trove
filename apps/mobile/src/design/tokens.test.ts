import { Colors, DarkColors, Radius, FontFamily } from './tokens';

describe('design tokens', () => {
  it('Colors has required keys', () => {
    expect(Colors.paper).toBeDefined();
    expect(Colors.sage).toBe('#4a6b4f'); // QL design doc value
    expect(Colors.ink).toBeDefined();
  });

  it('DarkColors has same keys as Colors', () => {
    expect(Object.keys(DarkColors)).toEqual(Object.keys(Colors));
  });

  it('Radius has sm, md, lg, xl', () => {
    expect(Radius.sm).toBe(4);
    expect(Radius.xl).toBe(24);
  });

  it('FontFamily has display, body, mono keys', () => {
    expect(FontFamily.display).toBe('Fraunces_300Light');
    expect(FontFamily.body).toBe('Inter_400Regular');
    expect(FontFamily.mono).toBe('JetBrainsMono_400Regular');
  });
});
