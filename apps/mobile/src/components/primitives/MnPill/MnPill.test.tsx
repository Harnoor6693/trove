import React from 'react';
import { render } from '@testing-library/react-native';
import { MnPill } from './MnPill';

describe('MnPill', () => {
  it('renders label text', async () => {
    const { getByText } = await render(<MnPill label="Groceries" />);
    expect(getByText('Groceries')).toBeTruthy();
  });

  it('sage variant has sageSoft background', async () => {
    const { getByTestId } = await render(<MnPill label="x" variant="sage" testID="pill" />);
    expect(JSON.stringify(getByTestId('pill').props.style)).toContain('#dfe7dc');
  });

  it('red variant has redSoft background', async () => {
    const { getByTestId } = await render(<MnPill label="x" variant="red" testID="pill" />);
    expect(JSON.stringify(getByTestId('pill').props.style)).toContain('#fdecea');
  });

  it('muted variant uses Colors.line background and ink3 text for contrast', async () => {
    const { getByTestId, getByText } = await render(
      <MnPill label="Cancelled" variant="muted" testID="pill" />
    );
    // Colors.line = #e6e0d3
    expect(JSON.stringify(getByTestId('pill').props.style)).toContain('#e6e0d3');
    // Colors.ink3 = #8c8578
    expect(JSON.stringify(getByText('Cancelled').props.style)).toContain('#8c8578');
  });
});
