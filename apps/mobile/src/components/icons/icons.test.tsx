import React from 'react';
import { render } from '@testing-library/react-native';
import {
  TodayIcon,
  TxIcon,
  BudgetIcon,
  CoachIcon,
  SettingsIcon,
  NetWorthIcon,
  SubscriptionsIcon,
  ReceiptIcon,
} from './index';

describe('Icon components', () => {
  it('renders TodayIcon', async () => {
    const { getByTestId } = await render(<TodayIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders TxIcon', async () => {
    const { getByTestId } = await render(<TxIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders BudgetIcon', async () => {
    const { getByTestId } = await render(<BudgetIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders CoachIcon', async () => {
    const { getByTestId } = await render(<CoachIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders SettingsIcon', async () => {
    const { getByTestId } = await render(<SettingsIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders NetWorthIcon', async () => {
    const { getByTestId } = await render(<NetWorthIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders SubscriptionsIcon', async () => {
    const { getByTestId } = await render(<SubscriptionsIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders ReceiptIcon', async () => {
    const { getByTestId } = await render(<ReceiptIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('accepts custom color and size', async () => {
    const { getByTestId } = await render(<TodayIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('NetWorthIcon accepts custom color and size', async () => {
    const { getByTestId } = await render(<NetWorthIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('SubscriptionsIcon accepts custom color and size', async () => {
    const { getByTestId } = await render(<SubscriptionsIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('ReceiptIcon accepts custom color and size', async () => {
    const { getByTestId } = await render(<ReceiptIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });
});
