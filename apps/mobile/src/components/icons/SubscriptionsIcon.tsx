import React from 'react';
import Svg, { Rect, Path } from 'react-native-svg';
import type { ColorValue } from 'react-native';

interface IconProps {
  color?: ColorValue;
  size?: number;
}

export function SubscriptionsIcon({ color = '#1b1a17', size = 22 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 22 22" fill="none">
      <Rect x="3" y="5" width="16" height="12" rx="2" stroke={color} strokeWidth={1.6} />
      <Path d="M3 9h16" stroke={color} strokeWidth={1.6} strokeLinecap="round" />
      <Path d="M7 14h4" stroke={color} strokeWidth={1.6} strokeLinecap="round" />
    </Svg>
  );
}
