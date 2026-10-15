import React from 'react';
import Svg, { Path } from 'react-native-svg';
import type { ColorValue } from 'react-native';

export function ReceiptIcon({
  color = '#8c8578',
  size = 24,
}: {
  color?: ColorValue;
  size?: number;
}) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M6 3v18l2-1.5L10 21l2-1.5L14 21l2-1.5L18 21V3H6z"
        stroke={color}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <Path d="M9 8h6M9 12h6" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
    </Svg>
  );
}
