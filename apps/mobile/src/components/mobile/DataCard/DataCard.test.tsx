import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { router } from 'expo-router';
import { DataCard } from './DataCard';

const mockPush = router.push as jest.Mock;

beforeEach(() => {
  jest.clearAllMocks();
});

it('replaces the deferred placeholder with two real entry points', async () => {
  await render(<DataCard />);
  expect(screen.queryByTestId('settings-data-deferred')).toBeNull();
  expect(screen.getByTestId('settings-export-btn')).toBeTruthy();
  expect(screen.getByTestId('settings-delete-account-btn')).toBeTruthy();
});

it('sends each action to its own step-up screen rather than collecting a password here', async () => {
  await render(<DataCard />);
  await fireEvent.press(screen.getByTestId('settings-export-btn'));
  expect(mockPush).toHaveBeenCalledWith('/(tabs)/account/export');
  await fireEvent.press(screen.getByTestId('settings-delete-account-btn'));
  expect(mockPush).toHaveBeenCalledWith('/(tabs)/account/delete');
});

it('says receipt images are not in the archive, which the operation’s own description requires', async () => {
  await render(<DataCard />);
  expect(screen.getByTestId('settings-data-export-note').props.children).toMatch(
    /Receipt images are not in the archive/
  );
});

it('states the consequence of deletion in text, not only in the button’s verb', async () => {
  await render(<DataCard />);
  const note = screen.getByTestId('settings-delete-account-note').props.children;
  expect(note).toMatch(/signs you out everywhere/);
  expect(note).toMatch(/within seven days/);
  expect(note).toMatch(/erased for good/);
  expect(note).toMatch(/household keeps its shared ledger/);
});

it('marks the destructive action as destructive', async () => {
  await render(<DataCard />);
  // `MnBtn` renders its label as the accessibility label; the danger variant is
  // what stops it reading as the card's default action.
  expect(screen.getByLabelText('Delete my account')).toBeTruthy();
  expect(screen.getByLabelText('Export my data')).toBeTruthy();
});
