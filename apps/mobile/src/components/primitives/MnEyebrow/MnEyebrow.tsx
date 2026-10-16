import React from 'react';
import { Text, StyleSheet, TextProps } from 'react-native';
import { useTheme } from '@design/useTheme';
import { FontSize, FontFamily, Space } from '@design/tokens';

export function MnEyebrow({ style, children, ...props }: TextProps) {
  const t = useTheme();
  return (
    <Text style={[styles.eyebrow, { color: t.ink3 }, style]} {...props}>
      {typeof children === 'string' ? children.toUpperCase() : children}
    </Text>
  );
}

const styles = StyleSheet.create({
  eyebrow: {
    fontSize: FontSize.xs,
    fontFamily: FontFamily.bodySemi,
    letterSpacing: 1,
    marginBottom: Space.s1,
  },
});
