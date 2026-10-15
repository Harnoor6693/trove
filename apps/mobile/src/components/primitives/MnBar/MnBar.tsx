import React from 'react';
import { View, StyleSheet, ViewProps } from 'react-native';
import { useTheme } from '@design/useTheme';
import type { Colors } from '@design/tokens';

export interface MnBarProps extends ViewProps {
  value: number;
  max: number;
  color?: string;
}

function barColor(t: { [K in keyof typeof Colors]: string }, pct: number): string {
  if (pct >= 90) return t.red;
  if (pct >= 70) return t.amber;
  return t.sage;
}

export function MnBar({ value, max, color, style, testID, ...props }: MnBarProps) {
  const t = useTheme();
  const pct = max === 0 ? 0 : Math.min(100, (value / max) * 100);
  // A falsy / non-string color override silently bypasses the threshold logic;
  // surface that in dev so the caller can fix it instead of debugging a white bar.
  if (__DEV__ && color !== undefined && (typeof color !== 'string' || color === '')) {
    // eslint-disable-next-line no-console
    console.warn(
      `[MnBar] color override is invalid (got ${JSON.stringify(color)}); falling back to threshold colors.`
    );
  }
  const validOverride = typeof color === 'string' && color !== '';
  const fillColor = validOverride ? color : barColor(t, pct);
  return (
    <View style={[styles.track, { backgroundColor: t.line }, style]} testID={testID} {...props}>
      <View
        testID={testID ? `${testID}-fill` : undefined}
        style={[styles.fill, { width: `${pct}%` as `${number}%`, backgroundColor: fillColor }]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
});
