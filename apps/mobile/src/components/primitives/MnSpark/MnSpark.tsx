import React from 'react';
import { View } from 'react-native';
import Svg, { Polyline } from 'react-native-svg';
import { useTheme } from '@design/useTheme';

export interface MnSparkProps {
  data: number[];
  width?: number;
  height?: number;
  color?: string;
  testID?: string;
}

export function MnSpark({ data, width = 80, height = 32, color, testID }: MnSparkProps) {
  const t = useTheme();
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
      <Polyline points={points} fill="none" stroke={color ?? t.sage} strokeWidth={1.5} />
    </Svg>
  );
}
