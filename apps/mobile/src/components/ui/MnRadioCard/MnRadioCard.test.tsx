import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { MnRadioCard } from './MnRadioCard';

describe('MnRadioCard', () => {
  it('renders label and description', async () => {
    const { getByText } = await render(
      <MnRadioCard
        label="Option A"
        description="Best choice"
        selected={false}
        onSelect={jest.fn()}
      />
    );
    expect(getByText('Option A')).toBeTruthy();
    expect(getByText('Best choice')).toBeTruthy();
  });

  it('calls onSelect when pressed', async () => {
    const onSelect = jest.fn();
    const { getByText } = await render(
      <MnRadioCard label="Pick me" selected={false} onSelect={onSelect} />
    );
    await fireEvent.press(getByText('Pick me'));
    expect(onSelect).toHaveBeenCalled();
  });
});
