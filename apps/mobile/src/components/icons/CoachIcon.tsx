import React from 'react';
import Svg, { Path, Circle } from 'react-native-svg';
import type { ColorValue } from 'react-native';

export function CoachIcon({ color = '#8c8578', size = 24 }: { color?: ColorValue; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2v10z"
        stroke={color}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Circle cx="9" cy="10" r="1" fill={color} />
      <Circle cx="12" cy="10" r="1" fill={color} />
      <Circle cx="15" cy="10" r="1" fill={color} />
    </Svg>
  );
}
