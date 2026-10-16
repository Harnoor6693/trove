import React from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  type ScrollViewProps,
} from 'react-native';

/**
 * Keyboard behaviour shared by every form screen (B5-07):
 *
 * - `automaticallyAdjustKeyboardInsets` keeps the focused field above the
 *   keyboard on iOS;
 * - `keyboardShouldPersistTaps="handled"` lets the first tap on Save reach the
 *   button instead of only dismissing the keyboard;
 * - `keyboardDismissMode="interactive"` lets the member drag it away.
 *
 * Spread onto a `FlatList` for list-based forms.
 */
export const keyboardScrollProps = {
  automaticallyAdjustKeyboardInsets: true,
  keyboardShouldPersistTaps: 'handled',
  keyboardDismissMode: 'interactive',
} as const;

/**
 * Android has no `automaticallyAdjustKeyboardInsets` (and edge-to-edge ignores
 * `adjustResize`), so the view itself shrinks. Off on iOS, where the scroll
 * insets already do the job and padding would count the keyboard twice.
 */
export function KeyboardAvoid({ children }: { children: React.ReactNode }) {
  return (
    <KeyboardAvoidingView style={styles.flex} behavior="height" enabled={Platform.OS === 'android'}>
      {children}
    </KeyboardAvoidingView>
  );
}

/** A `ScrollView` for a form screen; use in place of a bare `ScrollView`. */
export function FormScroll(props: ScrollViewProps) {
  return (
    <KeyboardAvoid>
      <ScrollView {...keyboardScrollProps} {...props} />
    </KeyboardAvoid>
  );
}

const styles = StyleSheet.create({ flex: { flex: 1 } });
