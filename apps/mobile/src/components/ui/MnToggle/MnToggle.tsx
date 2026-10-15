import React from 'react';
import { Switch, View, Text, StyleSheet } from 'react-native';
import { Colors, FontFamily, FontSize } from '@design/tokens';

export interface MnToggleProps {
  label: string;
  value: boolean;
  onToggle: (v: boolean) => void;
  testID?: string;
}

export function MnToggle({ label, value, onToggle, testID }: MnToggleProps) {
  return (
    <View style={styles.row} testID={testID}>
      <Text style={styles.label}>{label}</Text>
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
        trackColor={{ false: Colors.line, true: Colors.sageSoft }}
        thumbColor={value ? Colors.sage : Colors.ink3}
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
  label: { fontFamily: FontFamily.body, fontSize: FontSize.md, color: Colors.ink },
});
