import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { MnBtn } from './MnBtn';

describe('MnBtn', () => {
  it('renders label', async () => {
    const { getByText } = await render(<MnBtn label="Save" />);
    expect(getByText('Save')).toBeTruthy();
  });

  it('calls onPress when tapped', async () => {
    const onPress = jest.fn();
    const { getByText } = await render(<MnBtn label="Go" onPress={onPress} />);
    await fireEvent.press(getByText('Go'));
    expect(onPress).toHaveBeenCalled();
  });

  it('shows ActivityIndicator when loading', async () => {
    const { queryByText, container } = await render(<MnBtn label="Save" loading />);
    expect(queryByText('Save')).toBeNull();
    expect(container.queryAll((node) => node.type === 'ActivityIndicator')).toHaveLength(1);
  });

  it('does not fire onPress when disabled', async () => {
    const onPress = jest.fn();
    const { getByTestId } = await render(
      <MnBtn label="x" onPress={onPress} disabled testID="btn" />
    );
    await fireEvent.press(getByTestId('btn'));
    expect(onPress).not.toHaveBeenCalled();
  });
});
