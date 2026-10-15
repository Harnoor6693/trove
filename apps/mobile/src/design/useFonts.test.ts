import { renderHook } from '@testing-library/react-native';
import { useTroveFonts } from './useFonts';

jest.mock('expo-font', () => ({ useFonts: jest.fn(() => [true, null]) }));
jest.mock('@expo-google-fonts/fraunces', () => ({
  Fraunces_300Light: 'f300',
  Fraunces_600SemiBold: 'f600',
}));
jest.mock('@expo-google-fonts/inter', () => ({
  Inter_400Regular: 'i400',
  Inter_500Medium: 'i500',
  Inter_600SemiBold: 'i600',
}));
jest.mock('@expo-google-fonts/jetbrains-mono', () => ({
  JetBrainsMono_400Regular: 'jb400',
}));

describe('useTroveFonts', () => {
  it('calls useFonts with all required fonts', async () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { useFonts } = require('expo-font');
    await renderHook(() => useTroveFonts());
    expect(useFonts).toHaveBeenCalledWith(
      expect.objectContaining({
        Fraunces_300Light: expect.anything(),
        Inter_400Regular: expect.anything(),
        JetBrainsMono_400Regular: expect.anything(),
      })
    );
  });

  it('returns the font loading state', async () => {
    const { result } = await renderHook(() => useTroveFonts());
    expect(result.current[0]).toBe(true);
  });
});
