import React from 'react';
import { View, ViewProps } from 'react-native';
import { Colors } from '@design/tokens';

export interface MnDotProps extends ViewProps {
  color?: string;
  size?: number;
}

export function MnDot({ color = Colors.sage, size = 8, style, ...props }: MnDotProps) {
  return (
    <View
      style={[{ width: size, height: size, borderRadius: size / 2, backgroundColor: color }, style]}
      {...props}
    />
  );
}
