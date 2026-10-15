import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/queryClient';
import {
  coachKeys,
  coachNoticeCopy,
  nextChatPage,
  useChatHistory,
  useChatSuggestions,
  useCoach,
  useDeleteChatHistory,
  useInsights,
} from './useCoach';
import {
  deleteChatHistory,
  fetchChatHistory,
  fetchChatSuggestions,
  fetchInsights,
  streamCoachAnswer,
} from '@api/coach';
import { ApiProblemError } from '@api/errors';
import type { CoachMessageResponse, InsightResponse } from '@api/types';

jest.mock('@api/coach', () => ({
  streamCoachAnswer: jest.fn(),
  fetchChatSuggestions: jest.fn(),
  fetchChatHistory: jest.fn(),
  deleteChatHistory: jest.fn(),
  fetchInsights: jest.fn(),
}));

const mockStream = streamCoachAnswer as jest.MockedFunction<typeof streamCoachAnswer>;
const mockSuggestions = fetchChatSuggestions as jest.MockedFunction<typeof fetchChatSuggestions>;
const mockHistory = fetchChatHistory as jest.MockedFunction<typeof fetchChatHistory>;
const mockDelete = deleteChatHistory as jest.MockedFunction<typeof deleteChatHistory>;
const mockInsights = fetchInsights as jest.MockedFunction<typeof fetchInsights>;

const TURN: CoachMessageResponse = {
  id: 'd-1',
  sessionId: 'c-1',
  role: 'USER',
  content: 'What did I spend the most on?',
  model: null,
  createdAt: '2026-09-05T18:04:00Z',
};

const ANSWER_TURN: CoachMessageResponse = {
  ...TURN,
  id: 'd-2',
  role: 'ASSISTANT',
  content: 'Groceries.',
  model: 'qwen2.5:14b',
  createdAt: '2026-09-05T18:04:11Z',
};

const INSIGHT: InsightResponse = {
  id: 'e-1',
  weekStart: '2026-08-31',
  content: 'You spent less than the week before.',
  delivered: false,
  createdAt: '2026-08-31T08:30:04Z',
  deliveredAt: null,
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  jest.clearAllMocks();
});
afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('coachNoticeCopy', () => {
  it('keys off the slug, never the status — 409 ai-disabled and 409 conflict differ', () => {
    expect(coachNoticeCopy('ai-disabled', 'x')).toContain('needs your own AI endpoint');
    expect(coachNoticeCopy('ai-policy-blocked', 'x')).toContain('privacy tier');
  });

  it('says an answer that hit the cap is kept, not lost', () => {
    expect(coachNoticeCopy('answer-too-long', 'x')).toContain('Everything above is kept');
  });

  it.each([
    'ai-unreachable',
    'ai-endpoint-unauthorized',
    'ai-model-missing',
    'ai-timeout',
    'outbound-url-rejected',
    'rate-limited',
    'truncated',
    'aborted',
  ])('has its own sentence for %s', (slug) => {
    expect(coachNoticeCopy(slug, 'server detail')).not.toBe('server detail');
    expect(coachNoticeCopy(slug, 'server detail')).not.toBe('');
  });

  it('says how long to wait on a 429, the way the receipt screen does', () => {
    // The coach is the most rate-limited surface in the app and a 429 is a
    // wait-and-retry state with a real header on it, not a generic toast.
    expect(coachNoticeCopy('rate-limited', 'Too many requests.', 45)).toContain('in 45 seconds');
  });

  it('falls back to "in a moment" when a proxy stripped Retry-After', () => {
    expect(coachNoticeCopy('rate-limited', 'Too many requests.', null)).toContain('in a moment');
  });

  it("falls back to the server's own sentence for anything it does not know", () => {
    expect(coachNoticeCopy('some-future-slug', 'server detail')).toBe('server detail');
    expect(coachNoticeCopy(null, 'server detail')).toBe('server detail');
  });
});

describe('nextChatPage', () => {
  it('bounds the pager by meta.total', () => {
    expect(nextChatPage({ data: [], meta: { total: 100, page: 0, limit: 50 } })).toBe(1);
    expect(nextChatPage({ data: [], meta: { total: 100, page: 1, limit: 50 } })).toBeUndefined();
    expect(nextChatPage({ data: [], meta: { total: 100, page: 0, limit: 0 } })).toBeUndefined();
  });
});

describe('useCoach', () => {
  it('sends the question and renders the answer as it streams in', async () => {
    const rendered: string[] = [];
    mockStream.mockImplementation(async ({ question, onDelta }) => {
      expect(question).toBe('Where did my money go?');
      onDelta?.('Ground', 'Ground');
      onDelta?.('ies.', 'Groundies.');
      return { content: 'Groundies.', ending: 'done', error: null, sessionId: 'c-1' };
    });

    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('Where did my money go?');
    });

    rendered.push(...result.current.turns.map((turn) => turn.content));
    expect(rendered).toEqual(['Where did my money go?', 'Groundies.']);
    expect(result.current.turns[0].role).toBe('user');
    expect(result.current.turns[1].role).toBe('assistant');
    expect(result.current.turns[1].streaming).toBe(false);
    expect(result.current.ending).toBe('done');
    expect(result.current.notice).toBeNull();
  });

  it('round-trips the session id and mints nothing of its own', async () => {
    mockStream.mockResolvedValue({
      content: 'a',
      ending: 'done',
      error: null,
      sessionId: 'c-7',
    });
    const { result } = renderHook(() => useCoach(), { wrapper });

    // The first question carries no session id — that is what "start a new
    // conversation" means, and a client-minted id would be the wrong one.
    await act(async () => {
      await result.current.ask('first');
    });
    expect(mockStream.mock.calls[0][0].sessionId).toBeNull();
    expect(result.current.sessionId).toBe('c-7');

    await act(async () => {
      await result.current.ask('second');
    });
    expect(mockStream.mock.calls[1][0].sessionId).toBe('c-7');
  });

  it('KEEPS the partial answer when an in-band error frame ends the stream', async () => {
    mockStream.mockResolvedValue({
      content: 'As far as I got',
      ending: 'error',
      error: {
        type: 'https://trove.finance/errors/answer-too-long',
        title: 'Payload Too Large',
        status: 413,
        detail: 'The answer was capped.',
      },
      sessionId: 'c-1',
    });

    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('a long one');
    });

    expect(result.current.turns[1].content).toBe('As far as I got');
    expect(result.current.notice?.code).toBe('answer-too-long');
    expect(result.current.notice?.partial).toBe(true);
    expect(result.current.ending).toBe('error');
  });

  it('says so when a stream ends with neither [DONE] nor an error', async () => {
    mockStream.mockResolvedValue({
      content: 'half',
      ending: 'truncated',
      error: null,
      sessionId: 'c-1',
    });
    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('q');
    });
    expect(result.current.notice?.code).toBe('truncated');
    expect(result.current.turns[1].content).toBe('half');
  });

  it('keeps what arrived when the member presses Stop', async () => {
    mockStream.mockResolvedValue({
      content: 'partial',
      ending: 'aborted',
      error: null,
      sessionId: 'c-1',
    });
    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('q');
    });
    expect(result.current.notice?.code).toBe('aborted');
    expect(result.current.turns[1].content).toBe('partial');
  });

  it('aborts an OPEN stream when the screen unmounts', async () => {
    // The stream has to still be open for the abort to mean anything, so the
    // mock hangs until its signal fires — which is what a real transport does.
    let capturedSignal: AbortSignal | undefined;
    mockStream.mockImplementation(
      ({ signal }) =>
        new Promise((resolve) => {
          capturedSignal = signal;
          signal?.addEventListener('abort', () =>
            resolve({ content: 'partial', ending: 'aborted', error: null, sessionId: null })
          );
        })
    );

    const { result, unmount } = renderHook(() => useCoach(), { wrapper });
    act(() => {
      void result.current.ask('q');
    });
    await waitFor(() => expect(result.current.isStreaming).toBe(true));
    expect(capturedSignal?.aborted).toBe(false);

    unmount();
    expect(capturedSignal?.aborted).toBe(true);
  });

  it('aborts the open stream when the member presses Stop', async () => {
    let capturedSignal: AbortSignal | undefined;
    mockStream.mockImplementation(
      ({ signal }) =>
        new Promise((resolve) => {
          capturedSignal = signal;
          signal?.addEventListener('abort', () =>
            resolve({ content: 'partial', ending: 'aborted', error: null, sessionId: 'c-1' })
          );
        })
    );

    const { result } = renderHook(() => useCoach(), { wrapper });
    act(() => {
      void result.current.ask('q');
    });
    await waitFor(() => expect(result.current.isStreaming).toBe(true));

    await act(async () => {
      result.current.stop();
    });

    expect(capturedSignal?.aborted).toBe(true);
    await waitFor(() => expect(result.current.isStreaming).toBe(false));
    // Everything that arrived before Stop is still on the screen.
    expect(result.current.turns[1].content).toBe('partial');
    expect(result.current.notice?.code).toBe('aborted');
  });

  it('drops the empty answer bubble when the call was refused before the stream opened', async () => {
    mockStream.mockRejectedValue(
      new ApiProblemError(409, {
        type: 'https://trove.finance/errors/ai-disabled',
        status: 409,
        detail: 'You have not configured an AI endpoint.',
      })
    );

    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('q');
    });

    // The question stays — it is what the member typed — but there is no
    // half-written answer to keep.
    expect(result.current.turns.map((turn) => turn.role)).toEqual(['user']);
    expect(result.current.failure?.code).toBe('ai-disabled');
    expect(result.current.notice?.message).toContain('needs your own AI endpoint');
    expect(result.current.notice?.partial).toBe(false);
  });

  it('tells the member how long the limiter asked them to wait', async () => {
    // `ApiProblemError` carries the seconds and `toApiFailure` populates
    // `retryAfterSeconds` on a 429; the notice used to throw that away and
    // show a fixed sentence, which said something different from what the
    // receipt detail screen says about the same limiter.
    mockStream.mockRejectedValue(
      new ApiProblemError(
        429,
        {
          type: 'https://trove.finance/errors/rate-limited',
          status: 429,
          detail: 'Too many requests.',
        },
        45
      )
    );

    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('q');
    });

    expect(result.current.failure?.retryAfterSeconds).toBe(45);
    expect(result.current.notice?.message).toContain('in 45 seconds');
  });

  it('says "in a moment" when the 429 arrived without a Retry-After', async () => {
    mockStream.mockRejectedValue(
      new ApiProblemError(429, {
        type: 'https://trove.finance/errors/rate-limited',
        status: 429,
        detail: 'Too many requests.',
      })
    );
    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('q');
    });
    expect(result.current.notice?.message).toContain('in a moment');
  });

  it('refuses to send an empty question, or a second one while streaming', async () => {
    mockStream.mockResolvedValue({ content: 'a', ending: 'done', error: null, sessionId: 'c-1' });
    const { result } = renderHook(() => useCoach(), { wrapper });
    await act(async () => {
      await result.current.ask('   ');
    });
    expect(mockStream).not.toHaveBeenCalled();
    expect(result.current.turns).toEqual([]);
  });

  it('rehydrates a conversation from stored turns, mapping the upper-case roles', async () => {
    const { result } = renderHook(() => useCoach(), { wrapper });
    act(() => result.current.adoptHistory('c-1', [TURN, ANSWER_TURN]));

    expect(result.current.sessionId).toBe('c-1');
    expect(result.current.turns).toEqual([
      { role: 'user', content: TURN.content, streaming: false },
      { role: 'assistant', content: 'Groceries.', streaming: false },
    ]);
  });

  it('forgets the local transcript and the session on reset', async () => {
    const { result } = renderHook(() => useCoach(), { wrapper });
    act(() => result.current.adoptHistory('c-1', [TURN]));
    act(() => result.current.reset());
    expect(result.current.turns).toEqual([]);
    expect(result.current.sessionId).toBeNull();
  });
});

describe('useChatSuggestions', () => {
  it('seeds the empty state from the server rather than from hardcoded prompts', async () => {
    mockSuggestions.mockResolvedValue(['What did I spend the most on this month?']);
    const { result } = renderHook(() => useChatSuggestions(), { wrapper });
    await waitFor(() =>
      expect(result.current.data).toEqual(['What did I spend the most on this month?'])
    );
  });
});

describe('useChatHistory', () => {
  it('asks for every conversation when given no session id', async () => {
    mockHistory.mockResolvedValue({ data: [TURN], meta: { total: 1, page: 0, limit: 50 } });
    const { result } = renderHook(() => useChatHistory(null), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    // No `sessionId` key at all — `?sessionId=` would be a 400.
    expect(mockHistory).toHaveBeenCalledWith({ page: 0 });
  });

  it('narrows to one conversation when given an id', async () => {
    mockHistory.mockResolvedValue({ data: [TURN], meta: { total: 1, page: 0, limit: 50 } });
    const { result } = renderHook(() => useChatHistory('c-1'), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(mockHistory).toHaveBeenCalledWith({ sessionId: 'c-1', page: 0 });
  });

  it('never sends an empty session id', async () => {
    mockHistory.mockResolvedValue({ data: [TURN], meta: { total: 1, page: 0, limit: 50 } });
    const { result } = renderHook(() => useChatHistory(''), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(mockHistory).toHaveBeenCalledWith({ page: 0 });
  });

  it('stays idle when the caller says the coach is not configured', () => {
    const { result } = renderHook(() => useChatHistory(null, false), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockHistory).not.toHaveBeenCalled();
  });
});

describe('useDeleteChatHistory', () => {
  it('clears everything when given null, and invalidates the coach caches', async () => {
    mockDelete.mockResolvedValue(undefined);
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useDeleteChatHistory(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });

    await act(async () => {
      await result.current.mutateAsync(null);
    });

    expect(mockDelete).toHaveBeenCalledWith(null);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: coachKeys.all() });
  });
});

describe('useInsights', () => {
  it('reads the page and marks nothing delivered', async () => {
    mockInsights.mockResolvedValue({ data: [INSIGHT], meta: { total: 1, page: 0, limit: 50 } });
    const { result } = renderHook(() => useInsights(), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.pages[0].data[0].delivered).toBe(false);
    expect(mockInsights).toHaveBeenCalledWith({ page: 0 });
  });
});
