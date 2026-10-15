import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { MnToggle } from './MnToggle';

describe('MnToggle', () => {
  it('renders label', async () => {
    const { getByText } = await render(
      <MnToggle label="Dark mode" value={false} onToggle={jest.fn()} />
    );
    expect(getByText('Dark mode')).toBeTruthy();
  });

  it('calls onToggle when switched', async () => {
    const onToggle = jest.fn();
    const { getByLabelText } = await render(
      <MnToggle label="x" value={false} onToggle={onToggle} />
    );
    await fireEvent(getByLabelText('x'), 'valueChange', true);
    expect(onToggle).toHaveBeenCalledWith(true);
  });
});
