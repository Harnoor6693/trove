import React from 'react';
import { View, StyleSheet, ViewProps } from 'react-native';
import { useTheme } from '@design/useTheme';
import { Radius, Space } from '@design/tokens';

export function MnCard({ style, children, ...props }: ViewProps) {
  const t = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: t.card }, style]} {...props}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: Radius.lg,
    padding: Space.s4,
    shadowColor: '#000',
    shadowOpacity: 0.04,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
    elevation: 2,
  },
});
