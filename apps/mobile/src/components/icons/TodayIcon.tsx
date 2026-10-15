import React from 'react';
import Svg, { Rect, Line } from 'react-native-svg';
import type { ColorValue } from 'react-native';

export function TodayIcon({ color = '#8c8578', size = 24 }: { color?: ColorValue; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Rect x="3" y="4" width="18" height="17" rx="2" stroke={color} strokeWidth="1.5" />
      <Line x1="3" y1="9" x2="21" y2="9" stroke={color} strokeWidth="1.5" />
      <Line x1="8" y1="2" x2="8" y2="6" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
      <Line x1="16" y1="2" x2="16" y2="6" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
    </Svg>
  );
}
