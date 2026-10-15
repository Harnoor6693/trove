// Imported first: it installs the mock adapter on `axios.defaults`.
import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { onSessionEnded, setSession, __resetClientForTests } from './client';
import {
  MAX_QUESTION_CHARS,
  SESSION_ID_HEADER,
  askCoach,
  deleteChatHistory,
  fetchChatHistory,
  fetchChatSuggestions,
  fetchInsights,
  oneUserTurn,
  streamCoachAnswer,
} from './coach';
import type { SseRequest, SseResponse, SseTransport } from './sse';
import type { ChatCompletionResponse, CoachMessageResponse, InsightResponse } from './types';

const BASE = 'https://trove.example.com';
const USER = {
  id: 'u1',
  email: 'a@b.co',
  displayName: 'A',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
};

const ANSWER: ChatCompletionResponse = {
  sessionId: 'c-1',
  role: 'ASSISTANT',
  content: 'You spent the most on Groceries this month.',
  model: 'qwen2.5:14b',
  createdAt: '2026-09-05T18:04:11Z',
};

const TURN: CoachMessageResponse = {
  id: 'd-1',
  sessionId: 'c-1',
  role: 'USER',
  content: 'What did I spend the most on this month?',
  model: null,
  createdAt: '2026-09-05T18:04:00Z',
};

const INSIGHT: InsightResponse = {
  id: 'e-1',
  weekStart: '2026-08-31',
  content: 'You spent less than the week before.',
  delivered: false,
  createdAt: '2026-08-31T08:30:04Z',
  deliveredAt: null,
};

const META = { total: 1, page: 0, limit: 50 };

const delta = (text: string) => `data: {"choices":[{"delta":{"content":${JSON.stringify(text)}}}]}`;

/** A transport that answers with fixed chunks, recording what it was asked. */
function transportOf(
  status: number,
  headers: Record<string, string>,
  chunks: string[],
  record: SseRequest[] = []
): SseTransport {
  return async (request): Promise<SseResponse> => {
    record.push(request);
    async function* body(): AsyncGenerator<string> {
      for (const chunk of chunks) yield chunk;
    }
    return { status, headers, chunks: body() };
  };
}

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('oneUserTurn', () => {
  it('builds exactly ONE user turn — the turn being sent, not the history', () => {
    expect(oneUserTurn('Why is my grocery spend up?')).toEqual({
      messages: [{ role: 'user', content: 'Why is my grocery spend up?' }],
    });
  });

  it('spells the outgoing role in lower case, unlike a stored turn', () => {
    expect(oneUserTurn('x').messages[0].role).toBe('user');
  });

  it('states the contract cap on one question', () => {
    expect(MAX_QUESTION_CHARS).toBe(4000);
  });
});

describe('askCoach (not streaming)', () => {
  it('posts one user turn and unwraps the answer', async () => {
    replyOk(ANSWER);
    await expect(askCoach('What did I spend the most on?')).resolves.toEqual(ANSWER);
    expect(calls()).toEqual(['POST /v1/chat/completions']);
    expect(JSON.parse(seen[0].data as string)).toEqual({
      messages: [{ role: 'user', content: 'What did I spend the most on?' }],
    });
  });

  it('sends the session id as a HEADER when continuing a conversation', async () => {
    replyOk(ANSWER);
    await askCoach('And last month?', 'c-1');
    expect(seen[0].headers?.[SESSION_ID_HEADER]).toBe('c-1');
  });

  it('omits the header entirely to start a new conversation', async () => {
    replyOk(ANSWER);
    await askCoach('A fresh question');
    expect(seen[0].headers?.[SESSION_ID_HEADER]).toBeUndefined();
  });

  it('never sends an empty session id, which would not mean "new"', async () => {
    replyOk(ANSWER);
    await askCoach('A fresh question', '');
    expect(seen[0].headers?.[SESSION_ID_HEADER]).toBeUndefined();
  });

  it.each([
    [409, 'ai-disabled'],
    [403, 'ai-policy-blocked'],
    [502, 'ai-unreachable'],
    [504, 'ai-timeout'],
    [404, 'not-found'],
  ])('surfaces %s %s with its slug', async (status, slug) => {
    replyError(status as number, slug as string, 'Refused.');
    const failure = await failureOf(askCoach('x'));
    expect(failure.status).toBe(status);
    expect(failure.code).toBe(slug);
  });
});

describe('streamCoachAnswer', () => {
  it('sends exactly one user turn, and the session id as a header', async () => {
    const record: SseRequest[] = [];
    const result = await streamCoachAnswer({
      question: 'Where did my money go?',
      sessionId: 'c-1',
      transport: transportOf(
        200,
        { 'content-type': 'text/event-stream', 'x-session-id': 'c-1' },
        [`${delta('Groceries.')}\n\n`, 'data: [DONE]\n\n'],
        record
      ),
    });

    expect(JSON.parse(record[0].body)).toEqual({
      messages: [{ role: 'user', content: 'Where did my money go?' }],
    });
    expect(record[0].headers?.[SESSION_ID_HEADER]).toBe('c-1');
    expect(result).toEqual({
      content: 'Groceries.',
      ending: 'done',
      error: null,
      sessionId: 'c-1',
    });
  });

  it('takes the session id the server issued when the request had none', async () => {
    const result = await streamCoachAnswer({
      question: 'First question',
      transport: transportOf(200, { 'content-type': 'text/event-stream', 'x-session-id': 'c-9' }, [
        'data: [DONE]\n\n',
      ]),
    });
    expect(result.sessionId).toBe('c-9');
  });

  it('mints nothing when the server sends no session id back', async () => {
    const result = await streamCoachAnswer({
      question: 'First question',
      transport: transportOf(200, { 'content-type': 'text/event-stream' }, ['data: [DONE]\n\n']),
    });
    expect(result.sessionId).toBeNull();
  });

  it('renders deltas incrementally', async () => {
    const seenDeltas: string[] = [];
    await streamCoachAnswer({
      question: 'q',
      onDelta: (_piece, whole) => seenDeltas.push(whole),
      transport: transportOf(200, { 'content-type': 'text/event-stream' }, [
        `${delta('You ')}\n\n`,
        `${delta('spent.')}\n\n`,
        'data: [DONE]\n\n',
      ]),
    });
    expect(seenDeltas).toEqual(['You ', 'You spent.']);
  });

  it('keeps the partial answer when the stream ends with an in-band error frame', async () => {
    const result = await streamCoachAnswer({
      question: 'q',
      transport: transportOf(200, { 'content-type': 'text/event-stream' }, [
        `${delta('As far as I got')}\n\n`,
        'data: {"error":{"type":"https://trove.finance/errors/answer-too-long","title":"Payload Too Large","status":413,"detail":"…","instance":"/api/v1/chat/completions"}}\n\n',
      ]),
    });
    expect(result.ending).toBe('error');
    expect(result.content).toBe('As far as I got');
    expect(result.error?.type).toBe('https://trove.finance/errors/answer-too-long');
  });

  it('reads the STATUS before treating the body as a stream — a 409 is JSON, not frames', async () => {
    const failure = await failureOf(
      streamCoachAnswer({
        question: 'q',
        transport: transportOf(409, { 'content-type': 'application/json' }, [
          JSON.stringify({
            success: false,
            error: {
              type: 'https://trove.finance/errors/ai-disabled',
              title: 'Conflict',
              status: 409,
              detail: 'You have not configured an AI endpoint.',
              instance: '/api/v1/chat/completions',
            },
          }),
        ]),
      })
    );
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('ai-disabled');
    expect(failure.message).toBe('You have not configured an AI endpoint.');
  });

  it('keeps Retry-After off a 429 so the screen can say how long to wait', async () => {
    const failure = await failureOf(
      streamCoachAnswer({
        question: 'q',
        transport: transportOf(429, { 'content-type': 'application/json', 'retry-after': '30' }, [
          JSON.stringify({
            success: false,
            error: {
              type: 'https://trove.finance/errors/rate-limited',
              title: 'Too Many Requests',
              status: 429,
              detail: 'Slow down.',
              instance: '/api/v1/chat/completions',
            },
          }),
        ]),
      })
    );
    expect(failure.status).toBe(429);
    expect(failure.code).toBe('rate-limited');
    expect(failure.retryAfterSeconds).toBe(30);
  });

  it('reads a refusal whose body is not JSON at all without crashing', async () => {
    const failure = await failureOf(
      streamCoachAnswer({
        question: 'q',
        transport: transportOf(502, { 'content-type': 'text/html' }, ['<html>bad gateway</html>']),
      })
    );
    expect(failure.status).toBe(502);
    expect(failure.code).toBeNull();
  });

  it('reads a 200 that is JSON rather than a stream as the ordinary envelope', async () => {
    // The server decides the content type, not the query parameter, so a
    // reader that assumed frames would look for them and find none.
    const seenDeltas: string[] = [];
    const result = await streamCoachAnswer({
      question: 'q',
      onDelta: (_piece, whole) => seenDeltas.push(whole),
      transport: transportOf(200, { 'content-type': 'application/json' }, [
        JSON.stringify({ success: true, data: ANSWER }),
      ]),
    });
    expect(result.ending).toBe('done');
    expect(result.content).toBe(ANSWER.content);
    expect(result.sessionId).toBe('c-1');
    expect(seenDeltas).toEqual([ANSWER.content]);
  });

  it('reports a 200 whose JSON envelope is unreadable rather than hanging', async () => {
    const failure = await failureOf(
      streamCoachAnswer({
        question: 'q',
        transport: transportOf(200, { 'content-type': 'application/json' }, ['not json']),
      })
    );
    expect(failure.status).toBe(200);
  });

  it('takes the single-flight refresh once on a 401, then retries', async () => {
    await setSession({ accessToken: 'stale', refreshToken: 'r-1', expiresIn: 900 }, USER);
    // `refreshAccessToken` goes through the real axios client, so the mock
    // adapter answers it — proving the stream path reuses the one refresh
    // rather than implementing a second.
    replyOk({ accessToken: 'fresh', refreshToken: 'r-2', expiresIn: 900 });

    const record: SseRequest[] = [];
    let attempt = 0;
    const transport: SseTransport = async (request) => {
      record.push(request);
      attempt += 1;
      async function* body(): AsyncGenerator<string> {
        yield attempt === 1 ? '{"success":false}' : 'data: [DONE]\n\n';
      }
      return {
        status: attempt === 1 ? 401 : 200,
        headers: { 'content-type': attempt === 1 ? 'application/json' : 'text/event-stream' },
        chunks: body(),
      };
    };

    const result = await streamCoachAnswer({ question: 'q', transport });
    expect(record).toHaveLength(2);
    expect(result.ending).toBe('done');
    expect(calls()).toEqual(['POST /v1/auth/refresh']);
  });

  it('ENDS THE SESSION on a 401 with no refresh token, as the interceptor would', async () => {
    // The streaming path is the one call that does not go through the axios
    // response interceptor. `refreshAccessToken` rejects before its try block
    // when there is no refresh token and therefore never ends anything, so
    // without this branch the member sits on an authenticated UI whose every
    // later request also 401s.
    const reasons: string[] = [];
    const unsubscribe = onSessionEnded((reason) => reasons.push(reason));
    try {
      let attempts = 0;
      const transport: SseTransport = async () => {
        attempts += 1;
        async function* body(): AsyncGenerator<string> {
          yield '{"success":false}';
        }
        return { status: 401, headers: { 'content-type': 'application/json' }, chunks: body() };
      };

      const failure = await failureOf(streamCoachAnswer({ question: 'q', transport }));
      expect(failure.status).toBe(401);
      expect(reasons).toEqual(['NO_REFRESH_TOKEN']);
      // Nothing was retried, and no refresh was even attempted.
      expect(attempts).toBe(1);
      expect(calls()).toEqual([]);
    } finally {
      unsubscribe();
    }
  });

  it('ENDS THE SESSION when the retry after a good refresh 401s again', async () => {
    await setSession({ accessToken: 'stale', refreshToken: 'r-1', expiresIn: 900 }, USER);
    replyOk({ accessToken: 'fresh', refreshToken: 'r-2', expiresIn: 900 });

    const reasons: string[] = [];
    const unsubscribe = onSessionEnded((reason) => reasons.push(reason));
    try {
      let attempts = 0;
      const transport: SseTransport = async () => {
        attempts += 1;
        async function* body(): AsyncGenerator<string> {
          yield '{"success":false}';
        }
        return { status: 401, headers: { 'content-type': 'application/json' }, chunks: body() };
      };

      const failure = await failureOf(streamCoachAnswer({ question: 'q', transport }));
      expect(failure.status).toBe(401);
      // Exactly one refresh and exactly one retry — never a loop.
      expect(attempts).toBe(2);
      expect(reasons).toEqual(['UNAUTHORIZED']);
    } finally {
      unsubscribe();
    }
  });

  it('does not retry a 429 — that would spend another request against the limiter', async () => {
    let attempts = 0;
    const transport: SseTransport = async () => {
      attempts += 1;
      async function* body(): AsyncGenerator<string> {
        yield '{"success":false}';
      }
      return { status: 429, headers: { 'content-type': 'application/json' }, chunks: body() };
    };
    await failureOf(streamCoachAnswer({ question: 'q', transport }));
    expect(attempts).toBe(1);
  });
});

describe('fetchChatSuggestions', () => {
  it('unwraps the bare array — this list is not paged', async () => {
    replyOk(['What did I spend the most on this month?']);
    await expect(fetchChatSuggestions()).resolves.toEqual([
      'What did I spend the most on this month?',
    ]);
    expect(calls()).toEqual(['GET /v1/chat/suggestions']);
  });
});

describe('fetchChatHistory', () => {
  it('consumes the operation as a page', async () => {
    replyOk([TURN], META);
    await expect(fetchChatHistory()).resolves.toEqual({ data: [TURN], meta: META });
    expect(calls()).toEqual(['GET /v1/chat/history']);
  });

  it('narrows to one conversation when given an id', async () => {
    replyOk([TURN], META);
    await fetchChatHistory({ sessionId: 'c-1', page: 0 });
    expect(seen[0].params).toEqual({ sessionId: 'c-1', page: 0 });
  });

  it('DROPS an empty sessionId rather than sending `?sessionId=`, which is a 400', async () => {
    replyOk([TURN], META);
    await fetchChatHistory({ sessionId: '', page: 0 });
    expect(seen[0].params).toEqual({ page: 0 });
  });
});

describe('deleteChatHistory', () => {
  it('deletes one conversation when given an id', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(deleteChatHistory('c-1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/chat/history']);
    expect(seen[0].params).toEqual({ sessionId: 'c-1' });
  });

  it('omits the parameter to clear every conversation', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await deleteChatHistory();
    expect(seen[0].params).toEqual({});
  });

  it('never sends an empty sessionId, which on THIS route would be a 400 rather than "all"', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await deleteChatHistory('');
    expect(seen[0].params).toEqual({});
  });
});

describe('fetchInsights', () => {
  it('consumes the operation as a page and marks nothing delivered', async () => {
    replyOk([INSIGHT], META);
    const page = await fetchInsights();
    expect(page).toEqual({ data: [INSIGHT], meta: META });
    expect(calls()).toEqual(['GET /v1/insights']);
    // A safe GET: reading it does not set `delivered`, and the client must
    // not pretend otherwise by writing the field locally.
    expect(page.data[0].delivered).toBe(false);
  });
});
