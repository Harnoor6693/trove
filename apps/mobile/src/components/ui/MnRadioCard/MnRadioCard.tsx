import React from 'react';
import { TouchableOpacity, View, Text, StyleSheet, TouchableOpacityProps } from 'react-native';
import { Colors, Radius, Space, FontFamily, FontSize } from '@design/tokens';
import { MnDot } from '@components/primitives/MnDot/MnDot';

export interface MnRadioCardProps extends TouchableOpacityProps {
  label: string;
  description?: string;
  selected: boolean;
  onSelect: () => void;
}

export function MnRadioCard({
  label,
  description,
  selected,
  onSelect,
  style,
  ...props
}: MnRadioCardProps) {
  return (
    <TouchableOpacity
      style={[styles.card, selected && styles.selected, style]}
      onPress={onSelect}
      activeOpacity={0.8}
      accessibilityRole="radio"
      accessibilityLabel={description ? `${label}, ${description}` : label}
      accessibilityState={{ checked: selected }}
      {...props}
    >
      <View style={styles.row}>
        <View style={styles.text}>
          <Text style={styles.label}>{label}</Text>
          {description && <Text style={styles.desc}>{description}</Text>}
        </View>
        <MnDot color={selected ? Colors.sage : Colors.line} size={12} />
      </View>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: Space.s4,
    borderRadius: Radius.md,
    borderWidth: 1,
    borderColor: Colors.line,
    marginBottom: Space.s2,
  },
  selected: { borderColor: Colors.sage, backgroundColor: Colors.sageSoft },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  text: { flex: 1 },
  label: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.md, color: Colors.ink },
  desc: { fontFamily: FontFamily.body, fontSize: FontSize.sm, color: Colors.ink3, marginTop: 2 },
});
