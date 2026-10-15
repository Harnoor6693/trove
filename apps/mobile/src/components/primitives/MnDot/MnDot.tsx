import React from 'react';
import { View, ViewProps } from 'react-native';
import { useTheme } from '@design/useTheme';

export interface MnDotProps extends ViewProps {
  color?: string;
  size?: number;
}

export function MnDot({ color, size = 8, style, ...props }: MnDotProps) {
  const t = useTheme();
  return (
    <View
      style={[
        { width: size, height: size, borderRadius: size / 2, backgroundColor: color ?? t.sage },
        style,
      ]}
      {...props}
    />
  );
}
