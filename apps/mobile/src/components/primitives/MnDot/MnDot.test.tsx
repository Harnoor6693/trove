import React from 'react';
import { render } from '@testing-library/react-native';
import { MnDot } from './MnDot';

describe('MnDot', () => {
  it('renders with default size and color', () => {
    const { getByTestId } = render(<MnDot testID="dot" />);
    const dot = getByTestId('dot');
    expect(dot.props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ width: 8, borderRadius: 4 })])
    );
  });

  it('accepts custom color and size', () => {
    const { getByTestId } = render(<MnDot testID="dot" color="#ff0000" size={16} />);
    const dot = getByTestId('dot');
    expect(JSON.stringify(dot.props.style)).toContain('#ff0000');
  });
});
