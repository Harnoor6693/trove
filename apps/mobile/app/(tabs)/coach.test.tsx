import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import CoachScreen from './coach';
import {
  deleteChatHistory,
  fetchChatHistory,
  fetchChatSuggestions,
  fetchInsights,
  streamCoachAnswer,
} from '@api/coach';
import { fetchAiSettings } from '@api/ai';
import { ApiProblemError } from '@api/errors';
import type { AiSettingsResponse, CoachMessageResponse, InsightResponse } from '@api/types';

jest.mock('@api/coach', () => ({
  streamCoachAnswer: jest.fn(),
  fetchChatSuggestions: jest.fn(),
  fetchChatHistory: jest.fn(),
  deleteChatHistory: jest.fn(),
  fetchInsights: jest.fn(),
  MAX_QUESTION_CHARS: 4000,
}));

jest.mock('@api/ai', () => ({
  fetchAiSettings: jest.fn(),
  fetchAiModels: jest.fn(),
  updateAiSettings: jest.fn(),
  deleteAiApiKey: jest.fn(),
  testAiConnection: jest.fn(),
}));

const mockSettings = fetchAiSettings as jest.MockedFunction<typeof fetchAiSettings>;
const mockStream = streamCoachAnswer as jest.MockedFunction<typeof streamCoachAnswer>;
const mockSuggestions = fetchChatSuggestions as jest.MockedFunction<typeof fetchChatSuggestions>;
const mockHistory = fetchChatHistory as jest.MockedFunction<typeof fetchChatHistory>;
const mockDeleteHistory = deleteChatHistory as jest.MockedFunction<typeof deleteChatHistory>;
const mockInsights = fetchInsights as jest.MockedFunction<typeof fetchInsights>;

const READY: AiSettingsResponse = {
  enabled: true,
  endpointUrl: 'https://ai.example.test/v1',
  hasApiKey: true,
  model: 'qwen2.5:14b',
  visionModel: null,
  allowPrivateEndpoint: false,
  privacyTier: 'LOCAL_ONLY',
  connectTimeoutMs: 5000,
  readTimeoutMs: 120000,
  lastTestAt: null,
  lastTestOk: null,
  updatedAt: '2026-09-04T16:40:55.802Z',
};

const OFF: AiSettingsResponse = {
  ...READY,
  enabled: false,
  endpointUrl: null,
  hasApiKey: false,
  model: null,
  privacyTier: 'CLOUD_OK',
};

const STORED_TURNS: CoachMessageResponse[] = [
  {
    id: 'd-1',
    sessionId: 'c-1',
    role: 'USER',
    content: 'What did I spend the most on?',
    model: null,
    createdAt: '2026-09-05T18:04:00Z',
  },
  {
    id: 'd-2',
    sessionId: 'c-1',
    role: 'ASSISTANT',
    content: 'Groceries.',
    model: 'qwen2.5:14b',
    createdAt: '2026-09-05T18:04:11Z',
  },
];

const INSIGHT: InsightResponse = {
  id: 'e-1',
  weekStart: '2026-08-31',
  content: 'You spent less than the week before.',
  delivered: false,
  createdAt: '2026-08-31T08:30:04Z',
  deliveredAt: null,
};

const EMPTY_PAGE = { data: [], meta: { total: 0, page: 0, limit: 50 } };

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function createWrapper() {
  const qc = createTestQueryClient();
  clients.push(qc);
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  mockSettings.mockResolvedValue(READY);
  mockSuggestions.mockResolvedValue(['What did I spend the most on this month?']);
  mockHistory.mockResolvedValue(EMPTY_PAGE);
  mockInsights.mockResolvedValue(EMPTY_PAGE);
  mockDeleteHistory.mockResolvedValue(undefined);
  mockStream.mockResolvedValue({
    content: 'Groceries, mostly.',
    ending: 'done',
    error: null,
    sessionId: 'c-1',
  });
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('CoachScreen when AI is not configured', () => {
  it('shows the configure state and LINKS to the AI settings card', async () => {
    mockSettings.mockResolvedValue(OFF);
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });

    await waitFor(() => expect(getByTestId('coach-needs-endpoint')).toBeTruthy());
    fireEvent.press(getByTestId('coach-open-ai-settings'));
    expect((useRouter() as unknown as { push: jest.Mock }).push).toHaveBeenCalledWith(
      '/(tabs)/settings'
    );
  });

  it('does not render a composer that could only ever fail', async () => {
    mockSettings.mockResolvedValue(OFF);
    const { getByTestId, queryByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-needs-endpoint')).toBeTruthy());
    expect(queryByTestId('coach-input')).toBeNull();
    expect(queryByTestId('coach-send')).toBeNull();
  });

  it('still shows the server suggestions, which need no endpoint', async () => {
    mockSettings.mockResolvedValue(OFF);
    const { getByTestId, getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-suggestions')).toBeTruthy());
    expect(getByText('What did I spend the most on this month?')).toBeTruthy();
  });

  it('names the privacy tier as the reason when that is what blocks the coach', async () => {
    mockSettings.mockResolvedValue({ ...READY, privacyTier: 'CLOUD_OK' });
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-needs-endpoint')).toBeTruthy());
    const alert = getByTestId('coach-needs-endpoint').findByProps({ accessibilityRole: 'alert' });
    expect(String(alert.props.children)).toContain('local-only');
  });

  it('asks for no chat history while the coach cannot run', async () => {
    mockSettings.mockResolvedValue(OFF);
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-needs-endpoint')).toBeTruthy());
    expect(mockHistory).not.toHaveBeenCalled();
  });
});

describe('CoachScreen when AI is configured', () => {
  it('seeds the empty state from the SERVER, hardcoding no prompts', async () => {
    const { getByTestId, getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-input')).toBeTruthy());
    await waitFor(() => expect(getByText('What did I spend the most on this month?')).toBeTruthy());
    expect(mockSuggestions).toHaveBeenCalled();
  });

  it('puts a chosen suggestion into the composer rather than sending it blind', async () => {
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-suggestion-0')).toBeTruthy());
    fireEvent.press(getByTestId('coach-suggestion-0'));
    expect(getByTestId('coach-input').props.value).toBe('What did I spend the most on this month?');
    expect(mockStream).not.toHaveBeenCalled();
  });

  it('sends the question and shows both turns', async () => {
    const { getByTestId, getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-input')).toBeTruthy());

    fireEvent.changeText(getByTestId('coach-input'), 'Where did my money go?');
    fireEvent.press(getByTestId('coach-send'));

    await waitFor(() => expect(getByText('Groceries, mostly.')).toBeTruthy());
    expect(getByText('Where did my money go?')).toBeTruthy();
    expect(mockStream.mock.calls[0][0].question).toBe('Where did my money go?');
  });

  it('caps the composer at the length one question may be', async () => {
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-input')).toBeTruthy());
    expect(getByTestId('coach-input').props.maxLength).toBe(4000);
  });

  it('announces the STATE, not the transcript, for a screen reader', async () => {
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-status')).toBeTruthy());
    // One small polite region. A live region over the bubbles would re-read
    // the whole answer on every token.
    expect(getByTestId('coach-status').props.accessibilityLiveRegion).toBe('polite');

    fireEvent.changeText(getByTestId('coach-input'), 'q');
    fireEvent.press(getByTestId('coach-send'));
    await waitFor(() =>
      expect(getByTestId('coach-status').props.children).toBe('Answer complete.')
    );
  });

  it('offers Stop while an answer is streaming, and Ask when it is not', async () => {
    // A box rather than a bare `let`: TypeScript narrows a variable only
    // assigned inside a callback to `never` at the call site.
    const pending: { release: () => void } = { release: () => undefined };
    mockStream.mockImplementation(
      () =>
        new Promise((resolve) => {
          pending.release = () =>
            resolve({ content: 'done', ending: 'done', error: null, sessionId: 'c-1' });
        })
    );

    const { getByTestId, queryByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-input')).toBeTruthy());
    fireEvent.changeText(getByTestId('coach-input'), 'q');
    fireEvent.press(getByTestId('coach-send'));

    await waitFor(() => expect(getByTestId('coach-stop')).toBeTruthy());
    expect(queryByTestId('coach-send')).toBeNull();
    expect(getByTestId('coach-stop').props.accessibilityLabel).toBe('Stop the coach answering');

    pending.release();
    await waitFor(() => expect(getByTestId('coach-send')).toBeTruthy());
  });

  it('KEEPS the partial answer and names the slug on an in-band failure', async () => {
    mockStream.mockResolvedValue({
      content: 'As far as I got',
      ending: 'error',
      error: {
        type: 'https://trove.finance/errors/answer-too-long',
        title: 'Payload Too Large',
        status: 413,
        detail: 'Capped.',
      },
      sessionId: 'c-1',
    });

    const { getByTestId, getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-input')).toBeTruthy());
    fireEvent.changeText(getByTestId('coach-input'), 'a long one');
    fireEvent.press(getByTestId('coach-send'));

    await waitFor(() => expect(getByTestId('coach-notice')).toBeTruthy());
    expect(String(getByTestId('coach-notice').props.children)).toContain(
      'Everything above is kept'
    );
    // The half-answer is still on the screen, which is the whole point.
    expect(getByText('As far as I got')).toBeTruthy();
  });

  it('explains a refusal that arrived before the stream opened', async () => {
    mockStream.mockRejectedValue(
      new ApiProblemError(409, {
        type: 'https://trove.finance/errors/ai-disabled',
        status: 409,
        detail: 'No endpoint.',
      })
    );
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-input')).toBeTruthy());
    fireEvent.changeText(getByTestId('coach-input'), 'q');
    fireEvent.press(getByTestId('coach-send'));

    await waitFor(() =>
      expect(String(getByTestId('coach-notice').props.children)).toContain(
        'needs your own AI endpoint'
      )
    );
  });

  it('rehydrates the most recent conversation from the server', async () => {
    mockHistory.mockResolvedValue({
      data: STORED_TURNS,
      meta: { total: 2, page: 0, limit: 50 },
    });
    const { getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByText('Groceries.')).toBeTruthy());
    expect(getByText('What did I spend the most on?')).toBeTruthy();
  });

  it('continues the rehydrated conversation with ITS session id', async () => {
    mockHistory.mockResolvedValue({
      data: STORED_TURNS,
      meta: { total: 2, page: 0, limit: 50 },
    });
    const { getByTestId, getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByText('Groceries.')).toBeTruthy());

    fireEvent.changeText(getByTestId('coach-input'), 'And last month?');
    fireEvent.press(getByTestId('coach-send'));
    await waitFor(() => expect(mockStream).toHaveBeenCalled());
    expect(mockStream.mock.calls[0][0].sessionId).toBe('c-1');
  });

  it('confirms before clearing history, and says what is erased', async () => {
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-clear-start')).toBeTruthy());

    fireEvent.press(getByTestId('coach-clear-start'));
    const warning = String(getByTestId('coach-clear-confirm-text').props.children);
    expect(warning).toContain('every coach conversation');
    expect(warning).toContain('cannot be undone');
    expect(mockDeleteHistory).not.toHaveBeenCalled();

    fireEvent.press(getByTestId('coach-clear-confirm'));
    await waitFor(() => expect(mockDeleteHistory).toHaveBeenCalledWith(null));
  });

  it('clears the local transcript once the server has forgotten it', async () => {
    mockHistory.mockResolvedValue({
      data: STORED_TURNS,
      meta: { total: 2, page: 0, limit: 50 },
    });
    const { getByTestId, getByText, queryByText } = render(<CoachScreen />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByText('Groceries.')).toBeTruthy());

    fireEvent.press(getByTestId('coach-clear-start'));
    fireEvent.press(getByTestId('coach-clear-confirm'));
    await waitFor(() => expect(queryByText('Groceries.')).toBeNull());
  });

  it('lets the member back out of clearing history', async () => {
    const { getByTestId, queryByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('coach-clear-start')).toBeTruthy());
    fireEvent.press(getByTestId('coach-clear-start'));
    fireEvent.press(getByTestId('coach-clear-cancel'));
    expect(queryByTestId('coach-clear-confirm')).toBeNull();
    expect(mockDeleteHistory).not.toHaveBeenCalled();
  });
});

describe('CoachScreen insights', () => {
  it('shows the weekly notes and marks nothing delivered', async () => {
    mockInsights.mockResolvedValue({ data: [INSIGHT], meta: { total: 1, page: 0, limit: 50 } });
    const { getByTestId, getByText } = render(<CoachScreen />, { wrapper: createWrapper() });
    fireEvent.press(getByTestId('coach-segment-insights'));

    await waitFor(() => expect(getByTestId('coach-insight-e-1')).toBeTruthy());
    expect(getByText('You spent less than the week before.')).toBeTruthy();
  });

  it('explains an empty insights page rather than showing a blank tab', async () => {
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    fireEvent.press(getByTestId('coach-segment-insights'));
    await waitFor(() => expect(getByTestId('coach-insights-empty')).toBeTruthy());
  });

  it('marks the selected segment for assistive technology', async () => {
    const { getByTestId } = render(<CoachScreen />, { wrapper: createWrapper() });
    expect(getByTestId('coach-segment-chat').props.accessibilityState.selected).toBe(true);
    fireEvent.press(getByTestId('coach-segment-insights'));
    await waitFor(() =>
      expect(getByTestId('coach-segment-insights').props.accessibilityState.selected).toBe(true)
    );
  });
});
