import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { MnBtn } from './MnBtn';

describe('MnBtn', () => {
  it('renders label', () => {
    const { getByText } = render(<MnBtn label="Save" />);
    expect(getByText('Save')).toBeTruthy();
  });

  it('calls onPress when tapped', () => {
    const onPress = jest.fn();
    const { getByText } = render(<MnBtn label="Go" onPress={onPress} />);
    fireEvent.press(getByText('Go'));
    expect(onPress).toHaveBeenCalled();
  });

  it('shows ActivityIndicator when loading', () => {
    const { queryByText, UNSAFE_getByType } = render(<MnBtn label="Save" loading />);
    expect(queryByText('Save')).toBeNull();
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { ActivityIndicator } = require('react-native');
    expect(UNSAFE_getByType(ActivityIndicator)).toBeTruthy();
  });

  it('does not fire onPress when disabled', () => {
    const onPress = jest.fn();
    const { getByTestId } = render(<MnBtn label="x" onPress={onPress} disabled testID="btn" />);
    fireEvent.press(getByTestId('btn'));
    expect(onPress).not.toHaveBeenCalled();
  });
});
