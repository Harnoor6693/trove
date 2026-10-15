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
import { Colors, FontFamily, FontSize, Radius, Space } from '@design/tokens';

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
  const vs = variantStyle[variant];
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

const variantStyle: Record<
  BtnVariant,
  { container: ViewStyle; text: TextStyle; spinnerColor: string }
> = {
  primary: {
    container: { backgroundColor: Colors.sage },
    text: { color: '#fff' },
    spinnerColor: '#fff',
  },
  secondary: {
    container: { backgroundColor: Colors.sageSoft },
    text: { color: Colors.sage },
    spinnerColor: Colors.sage,
  },
  ghost: {
    container: { backgroundColor: 'transparent', borderWidth: 1, borderColor: Colors.line },
    text: { color: Colors.ink },
    spinnerColor: Colors.ink,
  },
  danger: {
    container: { backgroundColor: Colors.red },
    text: { color: '#fff' },
    spinnerColor: '#fff',
  },
};

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
