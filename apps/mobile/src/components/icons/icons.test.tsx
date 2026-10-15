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
  it('renders TodayIcon', () => {
    const { getByTestId } = render(<TodayIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders TxIcon', () => {
    const { getByTestId } = render(<TxIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders BudgetIcon', () => {
    const { getByTestId } = render(<BudgetIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders CoachIcon', () => {
    const { getByTestId } = render(<CoachIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders SettingsIcon', () => {
    const { getByTestId } = render(<SettingsIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders NetWorthIcon', () => {
    const { getByTestId } = render(<NetWorthIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders SubscriptionsIcon', () => {
    const { getByTestId } = render(<SubscriptionsIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('renders ReceiptIcon', () => {
    const { getByTestId } = render(<ReceiptIcon />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('accepts custom color and size', () => {
    const { getByTestId } = render(<TodayIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('NetWorthIcon accepts custom color and size', () => {
    const { getByTestId } = render(<NetWorthIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('SubscriptionsIcon accepts custom color and size', () => {
    const { getByTestId } = render(<SubscriptionsIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });

  it('ReceiptIcon accepts custom color and size', () => {
    const { getByTestId } = render(<ReceiptIcon color="#ff0000" size={32} />);
    expect(getByTestId('svg')).toBeTruthy();
  });
});
