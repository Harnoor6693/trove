import React from 'react';
import Svg, { Path, Circle } from 'react-native-svg';
import type { ColorValue } from 'react-native';

interface IconProps {
  color?: ColorValue;
  size?: number;
}

export function NetWorthIcon({ color = '#1b1a17', size = 22 }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 22 22" fill="none">
      <Path
        d="M2 16 L7 10 L12 13 L17 6"
        stroke={color}
        strokeWidth={1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Circle cx="17" cy="6" r="2" fill={color} />
    </Svg>
  );
}
