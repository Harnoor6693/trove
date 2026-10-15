import React from 'react';
import { Text, StyleSheet, TextProps } from 'react-native';
import { Colors, FontSize, FontFamily, Space } from '@design/tokens';

export function MnEyebrow({ style, children, ...props }: TextProps) {
  return (
    <Text style={[styles.eyebrow, style]} {...props}>
      {typeof children === 'string' ? children.toUpperCase() : children}
    </Text>
  );
}

const styles = StyleSheet.create({
  eyebrow: {
    fontSize: FontSize.xs,
    fontFamily: FontFamily.bodySemi,
    color: Colors.ink3,
    letterSpacing: 1,
    marginBottom: Space.s1,
  },
});
