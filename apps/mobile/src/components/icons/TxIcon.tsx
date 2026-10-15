import React from 'react';
import Svg, { Path } from 'react-native-svg';
import type { ColorValue } from 'react-native';

export function TxIcon({ color = '#8c8578', size = 24 }: { color?: ColorValue; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M7 20L17 4M17 4H11M17 4V10"
        stroke={color}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Path
        d="M17 20L7 4M7 4H13M7 4V10"
        stroke={color}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}
