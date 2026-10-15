import React from 'react';
import { AxiosError } from 'axios';
import { fireEvent, render, screen } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { StepUpForm } from './StepUpForm';
import { toApiFailure } from '@api/errors';

function refusal(
  status: number,
  slug: string,
  detail: string,
  headers: Record<string, string> = {}
) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const config = { headers: {} } as any;
  return toApiFailure(
    new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
      data: {
        success: false,
        error: { type: `https://trove.finance/errors/${slug}`, title: slug, status, detail },
      },
      status,
      statusText: '',
      headers,
      config,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any),
    'fallback'
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('asks for the password alone when no second factor is enrolled', () => {
  render(<StepUpForm testID="s" totpEnabled={false} submitLabel="Go" onSubmit={jest.fn()} />);
  expect(screen.getByTestId('s-password')).toBeTruthy();
  expect(screen.queryByTestId('s-code')).toBeNull();
});

it('asks for the code too when one is, with one-time-code autofill and a numeric keyboard', () => {
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={jest.fn()} />);
  const code = screen.getByTestId('s-code');
  expect(code.props.autoComplete).toBe('one-time-code');
  expect(code.props.keyboardType).toBe('number-pad');
  expect(screen.getByLabelText('Two-factor code')).toBeTruthy();
});

// `null` means "we do not know": `me` is fetched best-effort and a failed
// fetch is swallowed. Treating that as "no second factor" hides the only input
// an enrolled member could answer the 403 with.
it('offers the code field, optionally, when the factor set is not known yet', () => {
  const onSubmit = jest.fn();
  render(<StepUpForm testID="s" totpEnabled={null} submitLabel="Go" onSubmit={onSubmit} />);
  expect(screen.getByTestId('s-code')).toBeTruthy();
  expect(screen.getByLabelText('Two-factor code')).toBeTruthy();
  expect(screen.getByText('Two-factor code (if you use one)')).toBeTruthy();

  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(onSubmit).toHaveBeenCalledWith({ password: 'correct-horse-battery' });
});

it('sends the code typed into that optional field', () => {
  const onSubmit = jest.fn();
  render(<StepUpForm testID="s" totpEnabled={null} submitLabel="Go" onSubmit={onSubmit} />);
  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.changeText(screen.getByTestId('s-code'), '000000');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(onSubmit).toHaveBeenCalledWith({ password: 'correct-horse-battery', code: '000000' });
});

it('sends only the fields the member actually filled — never empty strings for the server to interpret', () => {
  const onSubmit = jest.fn();
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={onSubmit} />);
  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(onSubmit).toHaveBeenCalledWith({ password: 'correct-horse-battery' });
});

it('sends the code as well when one was typed', () => {
  const onSubmit = jest.fn();
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={onSubmit} />);
  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.changeText(screen.getByTestId('s-code'), ' 000000 ');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(onSubmit).toHaveBeenCalledWith({ password: 'correct-horse-battery', code: '000000' });
});

it('allows a code-only step-up, for a member who signs in through an identity provider', () => {
  const onSubmit = jest.fn();
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={onSubmit} />);
  fireEvent.changeText(screen.getByTestId('s-code'), '000000');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(onSubmit).toHaveBeenCalledWith({ code: '000000' });
});

it('cannot be submitted empty', () => {
  const onSubmit = jest.fn();
  render(<StepUpForm testID="s" totpEnabled={false} submitLabel="Go" onSubmit={onSubmit} />);
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(onSubmit).not.toHaveBeenCalled();
});

it('clears both fields the instant it submits, so no credential survives the request', () => {
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={jest.fn()} />);
  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.changeText(screen.getByTestId('s-code'), '000000');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(screen.getByTestId('s-password').props.value).toBe('');
  expect(screen.getByTestId('s-code').props.value).toBe('');
});

it('never writes the credential to the secure store', () => {
  const setItem = SecureStore.setItemAsync as jest.Mock;
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={jest.fn()} />);
  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.changeText(screen.getByTestId('s-code'), '000000');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(setItem).not.toHaveBeenCalled();
});

it('never renders the credential back into the tree once submitted', () => {
  render(<StepUpForm testID="s" totpEnabled submitLabel="Go" onSubmit={jest.fn()} />);
  fireEvent.changeText(screen.getByTestId('s-password'), 'correct-horse-battery');
  fireEvent.press(screen.getByTestId('s-submit'));
  expect(JSON.stringify(screen.toJSON())).not.toContain('correct-horse-battery');
});

it('reads 403 step-up-required as "that was not accepted", saying nothing about which was wrong', () => {
  render(
    <StepUpForm
      testID="s"
      totpEnabled
      submitLabel="Go"
      failure={refusal(403, 'step-up-required', 'Step-up authentication required.')}
      onSubmit={jest.fn()}
    />
  );
  expect(screen.getByTestId('s-error').props.children).toBe(
    'That password or code was not accepted.'
  );
});

it('reads a 429 as a wait, with the seconds the limiter asked for', () => {
  render(
    <StepUpForm
      testID="s"
      totpEnabled={false}
      submitLabel="Go"
      failure={refusal(429, 'rate-limited', 'Too many requests.', { 'retry-after': '30' })}
      onSubmit={jest.fn()}
    />
  );
  expect(screen.getByTestId('s-error').props.children).toBe(
    'Too many attempts. Try again in 30 seconds.'
  );
});

it('still says wait when a proxy stripped Retry-After', () => {
  render(
    <StepUpForm
      testID="s"
      totpEnabled={false}
      submitLabel="Go"
      failure={refusal(429, 'rate-limited', 'Too many requests.')}
      onSubmit={jest.fn()}
    />
  );
  expect(screen.getByTestId('s-error').props.children).toBe(
    'Too many attempts. Wait a moment and try again.'
  );
});

it('falls back to the server’s own detail for anything else', () => {
  render(
    <StepUpForm
      testID="s"
      totpEnabled={false}
      submitLabel="Go"
      failure={refusal(409, 'conflict', 'You can ask for another export after 2026-09-19.')}
      onSubmit={jest.fn()}
    />
  );
  expect(screen.getByTestId('s-error').props.children).toBe(
    'You can ask for another export after 2026-09-19.'
  );
});

it('shows a per-field validation message inline when the 400 carried one', () => {
  const failure = refusal(400, 'validation-error', 'Invalid request.');
  failure.fieldErrors = [{ field: 'password', message: 'Use at least 12 characters.' }];
  render(
    <StepUpForm
      testID="s"
      totpEnabled={false}
      submitLabel="Go"
      failure={failure}
      onSubmit={jest.fn()}
    />
  );
  expect(screen.getByTestId('s-password-error').props.children).toBe('Use at least 12 characters.');
});
