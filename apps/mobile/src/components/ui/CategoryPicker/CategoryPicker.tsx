import React from 'react';
import { StyleSheet, TouchableOpacity, View } from 'react-native';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { Space } from '@design/tokens';

export interface CategoryPickerProps {
  categories: { id: string; name: string }[];
  /** The chosen category id, or `''` for none. */
  value: string;
  onChange: (id: string) => void;
  /** Each pill's testID is `${testID}-category-${id}`. */
  testID: string;
}

/** The category pill row shared by the rule form and the transaction detail. */
export function CategoryPicker({ categories, value, onChange, testID }: CategoryPickerProps) {
  return (
    <View style={styles.chips}>
      {categories.map((choice) => (
        <TouchableOpacity
          key={choice.id}
          onPress={() => onChange(choice.id)}
          accessibilityRole="radio"
          accessibilityLabel={choice.name}
          accessibilityState={{ selected: value === choice.id }}
          style={styles.chip}
          testID={`${testID}-category-${choice.id}`}
        >
          <MnPill label={choice.name} variant={value === choice.id ? 'sage' : 'muted'} />
        </TouchableOpacity>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: { minHeight: 44, justifyContent: 'center' },
});
