import { useColorScheme } from 'react-native';
import { useUiStore } from '@stores/uiStore';
import { Colors, DarkColors } from './tokens';

/**
 * The colour palette for the current appearance setting.
 *
 * This used to read `useColorScheme()` alone, which is why the Settings
 * dark-mode toggle was inert: it wrote `uiStore.theme` and nothing ever read
 * it. The stored preference now wins, falling back to the OS scheme on
 * `'system'`.
 *
 * Caveat worth knowing: most screens still import the static `Colors` object
 * from `@design/tokens` directly rather than calling this hook, so switching
 * the theme repaints only the surfaces that use `useTheme()`. Converting the
 * rest is readiness gap P4-49 (SHOULD), not part of this step.
 */
export function useTheme() {
  const scheme = useColorScheme();
  const preference = useUiStore((s) => s.theme);
  const dark = preference === 'dark' || (preference === 'system' && scheme === 'dark');
  return dark ? DarkColors : Colors;
}
