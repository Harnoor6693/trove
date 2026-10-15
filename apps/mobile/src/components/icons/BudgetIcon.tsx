import React from 'react';
import Svg, { Circle, Path } from 'react-native-svg';
import type { ColorValue } from 'react-native';

export function BudgetIcon({
  color = '#8c8578',
  size = 24,
}: {
  color?: ColorValue;
  size?: number;
}) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Circle cx="12" cy="12" r="9" stroke={color} strokeWidth="1.5" />
      <Path d="M12 7v5l3 3" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
    </Svg>
  );
}
