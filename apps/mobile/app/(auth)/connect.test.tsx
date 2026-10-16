import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { router } from 'expo-router';
import ConnectScreen from './connect';
import { probeServer } from '@api/meta';
import { useAuthStore } from '@stores/authStore';
import { __resetServerConnectionForTests, getServerConnection } from '@api/serverConfig';

jest.mock('@api/meta', () => ({
  probeServer: jest.fn(),
}));

const mockProbe = probeServer as jest.MockedFunction<typeof probeServer>;
const mockReplace = router.replace as jest.Mock;

beforeEach(() => {
  jest.clearAllMocks();
  __resetServerConnectionForTests();
  useAuthStore.setState({
    status: 'disconnected',
    hydrated: true,
    server: null,
    user: null,
    me: null,
    preferences: null,
  });
  mockProbe.mockResolvedValue({ ok: true, meta: { status: 'UP', version: '1.0.0' } });
});

describe('ConnectScreen', () => {
  it('probes and stores a valid https server, then moves on to sign-in', async () => {
    const { getByTestId } = await render(<ConnectScreen />);
    await fireEvent.changeText(getByTestId('connect-url-input'), 'trove.example.com');
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() => expect(mockProbe).toHaveBeenCalledWith('https://trove.example.com'));
    await waitFor(() => expect(getServerConnection()?.baseUrl).toBe('https://trove.example.com'));
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/login');
  });

  it('refuses http to a public host without ever dialling it', async () => {
    const { getByTestId } = await render(<ConnectScreen />);
    await fireEvent.changeText(getByTestId('connect-url-input'), 'http://trove.example.com');
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() => expect(getByTestId('connect-error-text')).toBeTruthy());
    expect(mockProbe).not.toHaveBeenCalled();
    expect(getServerConnection()).toBeNull();
  });

  it('refuses http to a LAN host until the user opts in, then allows it', async () => {
    const { getByTestId } = await render(<ConnectScreen />);
    await fireEvent.changeText(getByTestId('connect-url-input'), 'http://192.168.1.9:8080');
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() =>
      expect(getByTestId('connect-error-text').props.children).toMatch(/Allow http on my network/)
    );
    expect(mockProbe).not.toHaveBeenCalled();

    await fireEvent(getByTestId('connect-allow-http-toggle'), 'onToggle', true);
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() => expect(mockProbe).toHaveBeenCalledWith('http://192.168.1.9:8080'));
    await waitFor(() => expect(getServerConnection()?.allowInsecureLan).toBe(true));
  });

  it('does not store anything when the address is not a Trove server', async () => {
    mockProbe.mockResolvedValue({
      ok: false,
      code: 'NOT_TROVE',
      message: 'Something answered at that address, but it isn’t a Trove server.',
    });

    const { getByTestId } = await render(<ConnectScreen />);
    await fireEvent.changeText(getByTestId('connect-url-input'), 'https://blog.example.com');
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() =>
      expect(getByTestId('connect-error-text').props.children).toMatch(/isn’t a Trove server/)
    );
    expect(getServerConnection()).toBeNull();
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('does not store anything when the server version is incompatible', async () => {
    mockProbe.mockResolvedValue({
      ok: false,
      code: 'INCOMPATIBLE',
      message: 'That server is version 9.0.0. This app only speaks 1.x — update the app.',
    });

    const { getByTestId } = await render(<ConnectScreen />);
    await fireEvent.changeText(getByTestId('connect-url-input'), 'https://trove.example.com');
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() =>
      expect(getByTestId('connect-error-text').props.children).toMatch(/only speaks 1\.x/)
    );
    expect(getServerConnection()).toBeNull();
  });

  it('surfaces the certificate policy rather than offering an override', async () => {
    mockProbe.mockResolvedValue({
      ok: false,
      code: 'TLS_UNTRUSTED',
      message: 'That server’s certificate isn’t trusted. Use Let’s Encrypt or Tailscale.',
    });

    const { getByTestId, queryByText } = await render(<ConnectScreen />);
    await fireEvent.changeText(getByTestId('connect-url-input'), 'https://trove.example.com');
    await fireEvent.press(getByTestId('connect-submit-btn'));

    await waitFor(() =>
      expect(getByTestId('connect-error-text').props.children).toMatch(/certificate/)
    );
    expect(queryByText(/trust anyway|ignore/i)).toBeNull();
  });

  it('says so, and does not leave a rejected promise, when the device cannot save the server (B5-15)', async () => {
    const original = useAuthStore.getState().connectServer;
    useAuthStore.setState({
      connectServer: jest.fn(() => Promise.reject(new Error('keychain is full'))),
    });
    try {
      const { getByTestId } = await render(<ConnectScreen />);
      await fireEvent.changeText(getByTestId('connect-url-input'), 'https://trove.example.com');
      await fireEvent.press(getByTestId('connect-submit-btn'));

      await waitFor(() =>
        expect(getByTestId('connect-error-text').props.children).toMatch(/save/i)
      );
      expect(mockReplace).not.toHaveBeenCalled();
    } finally {
      useAuthStore.setState({ connectServer: original });
    }
  });

  it('rejects an empty address', async () => {
    const { getByTestId } = await render(<ConnectScreen />);
    await fireEvent.press(getByTestId('connect-submit-btn'));
    await waitFor(() => expect(getByTestId('connect-error-text')).toBeTruthy());
    expect(mockProbe).not.toHaveBeenCalled();
  });
});
