import React from 'react';
import { View, Text, StyleSheet, ViewProps } from 'react-native';
import { Colors, FontSize, FontFamily, Space, Radius } from '@design/tokens';

type PillVariant = 'default' | 'muted' | 'sage' | 'amber' | 'red' | 'blue';

export interface MnPillProps extends ViewProps {
  label: string;
  variant?: PillVariant;
}

const variantColors: Record<PillVariant, { bg: string; text: string }> = {
  default: { bg: Colors.paperDim, text: Colors.ink2 },
  // muted uses Colors.line as background for higher contrast against
  // paper/paperDim surfaces; the previous shared paperDim made the pill
  // nearly invisible on subscription cancelled states.
  muted: { bg: Colors.line, text: Colors.ink3 },
  sage: { bg: Colors.sageSoft, text: Colors.sage },
  amber: { bg: Colors.amberSoft, text: Colors.amber },
  red: { bg: Colors.redSoft, text: Colors.red },
  blue: { bg: Colors.blueSoft, text: Colors.blue },
};

export function MnPill({ label, variant = 'default', style, ...props }: MnPillProps) {
  const { bg, text } = variantColors[variant];
  return (
    <View style={[styles.pill, { backgroundColor: bg }, style]} {...props}>
      <Text style={[styles.label, { color: text }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    paddingHorizontal: Space.s2,
    paddingVertical: 2,
    borderRadius: Radius.sm,
    alignSelf: 'flex-start',
  },
  label: { fontSize: FontSize.xs, fontFamily: FontFamily.bodySemi },
});
