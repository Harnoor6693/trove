import React from 'react';
import { render } from '@testing-library/react-native';
import { Text } from 'react-native';
import { MnCard } from './MnCard';

describe('MnCard', () => {
  it('renders children', async () => {
    const { getByText } = await render(
      <MnCard>
        <Text>Hello</Text>
      </MnCard>
    );
    expect(getByText('Hello')).toBeTruthy();
  });
});
