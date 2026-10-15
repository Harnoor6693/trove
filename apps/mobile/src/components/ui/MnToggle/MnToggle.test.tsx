import React from 'react';
import { render, fireEvent } from '@testing-library/react-native';
import { MnToggle } from './MnToggle';

describe('MnToggle', () => {
  it('renders label', () => {
    const { getByText } = render(<MnToggle label="Dark mode" value={false} onToggle={jest.fn()} />);
    expect(getByText('Dark mode')).toBeTruthy();
  });

  it('calls onToggle when switched', () => {
    const onToggle = jest.fn();
    const { UNSAFE_getByType } = render(<MnToggle label="x" value={false} onToggle={onToggle} />);
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { Switch } = require('react-native');
    fireEvent(UNSAFE_getByType(Switch), 'valueChange', true);
    expect(onToggle).toHaveBeenCalledWith(true);
  });
});
