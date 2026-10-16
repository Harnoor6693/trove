import React from 'react';
import { Text } from 'react-native';
import { render } from '@testing-library/react-native';
import { FormScroll, KeyboardAvoid, keyboardScrollProps } from './FormScroll';

describe('FormScroll', () => {
  it('keeps focused inputs above the keyboard and lets a first tap reach buttons (B5-07)', async () => {
    const { getByTestId, getByText } = await render(
      <FormScroll testID="form">
        <Text>field</Text>
      </FormScroll>
    );
    expect(getByText('field')).toBeTruthy();
    const props = getByTestId('form').props;
    expect(props.automaticallyAdjustKeyboardInsets).toBe(true);
    expect(props.keyboardShouldPersistTaps).toBe('handled');
    expect(props.keyboardDismissMode).toBe('interactive');
  });

  it('exposes the same props for list-based forms', () => {
    expect(keyboardScrollProps.keyboardShouldPersistTaps).toBe('handled');
    expect(keyboardScrollProps.automaticallyAdjustKeyboardInsets).toBe(true);
  });

  it('KeyboardAvoid renders its children', async () => {
    const { getByText } = await render(
      <KeyboardAvoid>
        <Text>child</Text>
      </KeyboardAvoid>
    );
    expect(getByText('child')).toBeTruthy();
  });
});
