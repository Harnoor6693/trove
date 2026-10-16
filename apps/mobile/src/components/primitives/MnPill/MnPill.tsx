import React from 'react';
import { View, Text, StyleSheet, ViewProps } from 'react-native';
import { useTheme } from '@design/useTheme';
import type { Colors } from '@design/tokens';
import { FontSize, FontFamily, Space, Radius } from '@design/tokens';

type PillVariant = 'default' | 'muted' | 'sage' | 'amber' | 'red' | 'blue';

export interface MnPillProps extends ViewProps {
  label: string;
  variant?: PillVariant;
}

type Theme = { [K in keyof typeof Colors]: string };

const variantColors = (t: Theme): Record<PillVariant, { bg: string; text: string }> => ({
  default: { bg: t.paperDim, text: t.ink2 },
  // muted uses Colors.line as background for higher contrast against
  // paper/paperDim surfaces; the previous shared paperDim made the pill
  // nearly invisible on subscription cancelled states.
  muted: { bg: t.line, text: t.ink3 },
  sage: { bg: t.sageSoft, text: t.sage },
  amber: { bg: t.amberSoft, text: t.amber },
  red: { bg: t.redSoft, text: t.red },
  blue: { bg: t.blueSoft, text: t.blue },
});

export function MnPill({ label, variant = 'default', style, ...props }: MnPillProps) {
  const { bg, text } = variantColors(useTheme())[variant];
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
