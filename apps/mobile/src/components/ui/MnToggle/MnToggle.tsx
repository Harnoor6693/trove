import React from 'react';
import { Switch, View, Text, StyleSheet } from 'react-native';
import { useTheme } from '@design/useTheme';
import { FontFamily, FontSize } from '@design/tokens';

export interface MnToggleProps {
  label: string;
  value: boolean;
  onToggle: (v: boolean) => void;
  testID?: string;
}

export function MnToggle({ label, value, onToggle, testID }: MnToggleProps) {
  const t = useTheme();
  return (
    <View style={styles.row} testID={testID}>
      <Text style={[styles.label, { color: t.ink }]}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onToggle}
        accessibilityLabel={label}
        // The row's own `testID` isn't tappable (no `onPress`); Detox and
        // RNTL both need to reach the actual `Switch` (see
        // `app/(tabs)/admin/index.test.tsx`'s note on this same component).
        // A distinct suffix, not the same id as the row, keeps
        // `getByTestId`/`by.id` unambiguous for both.
        testID={testID ? `${testID}-switch` : undefined}
        trackColor={{ false: t.line, true: t.sageSoft }}
        thumbColor={value ? t.sage : t.ink3}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 4,
  },
  label: { fontFamily: FontFamily.body, fontSize: FontSize.md },
});
