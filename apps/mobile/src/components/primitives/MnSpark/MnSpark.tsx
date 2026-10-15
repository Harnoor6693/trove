import React from 'react';
import { View } from 'react-native';
import Svg, { Polyline } from 'react-native-svg';
import { Colors } from '@design/tokens';

export interface MnSparkProps {
  data: number[];
  width?: number;
  height?: number;
  color?: string;
  testID?: string;
}

export function MnSpark({
  data,
  width = 80,
  height = 32,
  color = Colors.sage,
  testID,
}: MnSparkProps) {
  if (data.length < 2) return <View testID={testID} style={{ width, height }} />;

  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;
  const step = width / (data.length - 1);

  const points = data
    .map((v, i) => `${i * step},${height - ((v - min) / range) * (height - 4) - 2}`)
    .join(' ');

  return (
    <Svg width={width} height={height} testID={testID}>
      <Polyline points={points} fill="none" stroke={color} strokeWidth={1.5} />
    </Svg>
  );
}
