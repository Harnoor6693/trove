import React from 'react';
import { TouchableOpacity, View, Text, StyleSheet, TouchableOpacityProps } from 'react-native';
import { useTheme } from '@design/useTheme';
import { Radius, Space, FontFamily, FontSize } from '@design/tokens';
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
  const t = useTheme();
  return (
    <TouchableOpacity
      style={[
        styles.card,
        { borderColor: selected ? t.sage : t.line },
        selected && { backgroundColor: t.sageSoft },
        style,
      ]}
      onPress={onSelect}
      activeOpacity={0.8}
      accessibilityRole="radio"
      accessibilityLabel={description ? `${label}, ${description}` : label}
      accessibilityState={{ checked: selected }}
      {...props}
    >
      <View style={styles.row}>
        <View style={styles.text}>
          <Text style={[styles.label, { color: t.ink }]}>{label}</Text>
          {description && <Text style={[styles.desc, { color: t.ink3 }]}>{description}</Text>}
        </View>
        <MnDot color={selected ? t.sage : t.line} size={12} />
      </View>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: Space.s4,
    borderRadius: Radius.md,
    borderWidth: 1,
    marginBottom: Space.s2,
  },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  text: { flex: 1 },
  label: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.md },
  desc: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginTop: 2 },
});
