import React from 'react';
import { render } from '@testing-library/react-native';
import { MnEyebrow } from './MnEyebrow';

describe('MnEyebrow', () => {
  it('renders uppercase text', async () => {
    const { getByText } = await render(<MnEyebrow>spending</MnEyebrow>);
    expect(getByText('SPENDING')).toBeTruthy();
  });
});
