import React from 'react';
import { render } from '@testing-library/react-native';
import { MnSpark } from './MnSpark';

describe('MnSpark', () => {
  it('renders SVG polyline when data has 2+ points', async () => {
    const { getByTestId } = await render(<MnSpark data={[10, 20, 15, 30]} testID="spark" />);
    expect(getByTestId('spark')).toBeTruthy();
  });

  it('renders empty view when data has < 2 points', async () => {
    const { getByTestId } = await render(<MnSpark data={[10]} testID="spark-empty" />);
    expect(getByTestId('spark-empty')).toBeTruthy();
  });
});
