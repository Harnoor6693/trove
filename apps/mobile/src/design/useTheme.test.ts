import { renderHook } from '@testing-library/react-native';
import { useColorScheme } from 'react-native';
import { useTheme } from './useTheme';
import { Colors, DarkColors } from './tokens';
import { useUiStore } from '@stores/uiStore';

jest.mock('react-native/Libraries/Utilities/useColorScheme', () => ({
  __esModule: true,
  default: jest.fn(() => 'light'),
}));

const mockScheme = useColorScheme as jest.MockedFunction<typeof useColorScheme>;

function setPreference(theme: 'system' | 'light' | 'dark') {
  useUiStore.setState({ theme });
}

beforeEach(() => {
  mockScheme.mockReturnValue('light');
  setPreference('system');
});

describe('useTheme', () => {
  it('follows the OS scheme on "system"', async () => {
    mockScheme.mockReturnValue('dark');
    expect((await renderHook(() => useTheme())).result.current).toBe(DarkColors);

    mockScheme.mockReturnValue('light');
    expect((await renderHook(() => useTheme())).result.current).toBe(Colors);
  });

  // The Settings toggle used to write `uiStore.theme` while this hook read
  // only `useColorScheme()`, so the control changed a value nothing consumed.
  it('honours an explicit dark preference over a light OS scheme', async () => {
    mockScheme.mockReturnValue('light');
    setPreference('dark');
    expect((await renderHook(() => useTheme())).result.current).toBe(DarkColors);
  });

  it('honours an explicit light preference over a dark OS scheme', async () => {
    mockScheme.mockReturnValue('dark');
    setPreference('light');
    expect((await renderHook(() => useTheme())).result.current).toBe(Colors);
  });
});
