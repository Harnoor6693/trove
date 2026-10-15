import React from 'react';
import {
  TouchableOpacity,
  Text,
  ActivityIndicator,
  StyleSheet,
  ViewStyle,
  TextStyle,
  TouchableOpacityProps,
} from 'react-native';
import { useTheme } from '@design/useTheme';
import type { Colors } from '@design/tokens';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';

type BtnVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export interface MnBtnProps extends TouchableOpacityProps {
  label: string;
  variant?: BtnVariant;
  loading?: boolean;
  fullWidth?: boolean;
}

export function MnBtn({
  label,
  variant = 'primary',
  loading,
  fullWidth,
  style,
  disabled,
  ...props
}: MnBtnProps) {
  const vs = variantStyle(useTheme(), variant);
  return (
    <TouchableOpacity
      style={[
        styles.btn,
        vs.container,
        fullWidth && styles.full,
        disabled && styles.disabled,
        style,
      ]}
      disabled={disabled || loading}
      activeOpacity={0.75}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: disabled || loading, busy: loading }}
      {...props}
    >
      {loading ? (
        <ActivityIndicator color={vs.spinnerColor} />
      ) : (
        <Text style={[styles.label, vs.text]}>{label}</Text>
      )}
    </TouchableOpacity>
  );
}

type Theme = { [K in keyof typeof Colors]: string };

function variantStyle(
  t: Theme,
  variant: BtnVariant
): { container: ViewStyle; text: TextStyle; spinnerColor: string } {
  // Label on a filled button is the theme's paper colour: it is the pair that
  // clears AA on sage and red in both themes (white on dark sage is 3.1:1).
  switch (variant) {
    case 'primary':
      return {
        container: { backgroundColor: t.sage },
        text: { color: t.paper },
        spinnerColor: t.paper,
      };
    case 'secondary':
      return {
        container: { backgroundColor: t.sageSoft },
        text: { color: t.sage },
        spinnerColor: t.sage,
      };
    case 'ghost':
      return {
        container: { backgroundColor: 'transparent', borderWidth: 1, borderColor: t.line },
        text: { color: t.ink },
        spinnerColor: t.ink,
      };
    case 'danger':
      return {
        container: { backgroundColor: t.red },
        text: { color: t.paper },
        spinnerColor: t.paper,
      };
  }
}

const styles = StyleSheet.create({
  btn: {
    paddingHorizontal: Space.s6,
    paddingVertical: Space.s3,
    borderRadius: Radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 48,
  },
  label: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.md },
  full: { alignSelf: 'stretch' },
  disabled: { opacity: 0.4 },
});
