/**
 * The SSE reader, driven at the transport seam.
 *
 * This is the highest-risk code in the step, so it is tested the way
 * `client.test.ts` tests the refresh interceptor: against the real thing, at
 * the bottom, with plain frame text going in rather than a mocked reader
 * whose behaviour is the assertion. Every one of the endings the contract
 * describes has a case here, because the failure this file exists to prevent
 * is a reader that silently never fires.
 */

import {
  MAX_ANSWER_CHARS,
  MAX_FRAME_RESIDUE_CHARS,
  SSE_DONE,
  SseFrameBuffer,
  SseFrameTooLargeError,
  decodeCoachFrame,
  drain,
  fetchChunkSource,
  readSseStream,
  selectTransport,
  streamAuthHeaders,
  streamUrl,
  supportsFetchStreaming,
  xhrChunkSource,
  type ChunkSource,
} from './sse';
import { NoServerConnectedError } from './client';
import * as serverConfig from './serverConfig';

/** A chunk source over fixed strings, so a boundary can be put anywhere. */
async function* chunksOf(...chunks: string[]): AsyncGenerator<string> {
  for (const chunk of chunks) yield chunk;
}

const delta = (text: string) => `data: {"choices":[{"delta":{"content":${JSON.stringify(text)}}}]}`;

const errorFrame = (slug: string, status: number, detail = 'It went wrong.') =>
  `data: ${JSON.stringify({
    error: {
      type: `https://trove.finance/errors/${slug}`,
      title: slug,
      status,
      detail,
      instance: '/api/v1/chat/completions',
      requestId: 'req-1',
    },
  })}`;

describe('SseFrameBuffer', () => {
  it('splits frames on a blank line', () => {
    const buffer = new SseFrameBuffer();
    expect(buffer.push('data: one\n\ndata: two\n\n')).toEqual(['one', 'two']);
  });

  it('holds a frame that is split across two chunks until the delimiter arrives', () => {
    const buffer = new SseFrameBuffer();
    expect(buffer.push('data: {"choices":[{"del')).toEqual([]);
    expect(buffer.push('ta":{"content":"hi"}}]}\n\n')).toEqual([
      '{"choices":[{"delta":{"content":"hi"}}]}',
    ]);
  });

  it('accepts CRLF delimiters as well as LF', () => {
    const buffer = new SseFrameBuffer();
    expect(buffer.push('data: one\r\n\r\ndata: two\r\n\r\n')).toEqual(['one', 'two']);
  });

  it('joins the data lines of a multi-line frame', () => {
    const buffer = new SseFrameBuffer();
    expect(buffer.push('data: first\ndata: second\n\n')).toEqual(['first\nsecond']);
  });

  it('ignores keep-alive comments and unknown fields', () => {
    const buffer = new SseFrameBuffer();
    expect(buffer.push(': keep-alive\n\nevent: ping\nid: 4\n\ndata: real\n\n')).toEqual(['real']);
  });

  it('delivers a trailing frame the server wrote with no final blank line', () => {
    const buffer = new SseFrameBuffer();
    expect(buffer.push(`data: ${SSE_DONE}`)).toEqual([]);
    expect(buffer.flush()).toEqual([SSE_DONE]);
  });

  it('flushes nothing when the residue carries no data field', () => {
    const buffer = new SseFrameBuffer();
    buffer.push(': comment');
    expect(buffer.flush()).toEqual([]);
  });
});

describe('decodeCoachFrame', () => {
  it('reads a delta', () => {
    expect(decodeCoachFrame('{"choices":[{"delta":{"content":"Your "}}]}')).toEqual({
      kind: 'delta',
      content: 'Your ',
    });
  });

  it('reads the DONE sentinel', () => {
    expect(decodeCoachFrame(SSE_DONE)).toEqual({ kind: 'done' });
  });

  it('reads an in-band error and keeps its slug', () => {
    const frame = decodeCoachFrame(
      '{"error":{"type":"https://trove.finance/errors/ai-timeout","title":"Gateway Timeout","status":504,"detail":"…","instance":"/x"}}'
    );
    expect(frame.kind).toBe('error');
    if (frame.kind === 'error') {
      expect(frame.detail.type).toBe('https://trove.finance/errors/ai-timeout');
      expect(frame.detail.status).toBe(504);
    }
  });

  it('ignores a frame that is not JSON rather than throwing', () => {
    expect(decodeCoachFrame('not json at all')).toEqual({ kind: 'ignored' });
  });

  it('ignores a well-formed frame that carries no content', () => {
    expect(decodeCoachFrame('{"choices":[{"finish_reason":"stop"}]}')).toEqual({ kind: 'ignored' });
    expect(decodeCoachFrame('[1,2,3]')).toEqual({ kind: 'ignored' });
    expect(decodeCoachFrame('   ')).toEqual({ kind: 'ignored' });
  });
});

describe('readSseStream', () => {
  it('renders frames incrementally and ends on [DONE]', async () => {
    const seen: string[] = [];
    const outcome = await readSseStream(
      chunksOf(`${delta('You spent ')}\n\n`, `${delta('a lot.')}\n\n`, `data: ${SSE_DONE}\n\n`),
      { onDelta: (_piece, whole) => seen.push(whole) }
    );
    expect(seen).toEqual(['You spent ', 'You spent a lot.']);
    expect(outcome).toEqual({ content: 'You spent a lot.', ending: 'done', error: null });
  });

  it('reassembles a frame split across two chunks', async () => {
    const outcome = await readSseStream(
      chunksOf(
        'data: {"choices":[{"delta"',
        ':{"content":"joined"}}]}\n\n',
        `data: ${SSE_DONE}\n\n`
      )
    );
    expect(outcome.content).toBe('joined');
    expect(outcome.ending).toBe('done');
  });

  it('surfaces an in-band error frame with no [DONE] after it, and KEEPS what arrived', async () => {
    const outcome = await readSseStream(
      chunksOf(`${delta('Your groceries ')}\n\n`, `${errorFrame('ai-timeout', 504)}\n\n`)
    );
    expect(outcome.ending).toBe('error');
    expect(outcome.content).toBe('Your groceries ');
    expect(outcome.error?.type).toBe('https://trove.finance/errors/ai-timeout');
  });

  it('treats an answer that hit the cap as an ending, not a failure of the endpoint', async () => {
    const outcome = await readSseStream(
      chunksOf(`${delta('A long answer')}\n\n`, `${errorFrame('answer-too-long', 413)}\n\n`)
    );
    expect(outcome.ending).toBe('error');
    expect(outcome.content).toBe('A long answer');
    expect(outcome.error?.type).toBe('https://trove.finance/errors/answer-too-long');
  });

  it('reports a stream that ends with neither [DONE] nor an error, rather than hanging', async () => {
    const outcome = await readSseStream(chunksOf(`${delta('half an ans')}\n\n`));
    expect(outcome.ending).toBe('truncated');
    expect(outcome.content).toBe('half an ans');
    expect(outcome.error).toBeNull();
  });

  it('stops a body that never writes a frame delimiter, rather than growing for ever', async () => {
    // A proxy that rewrote the stream, or a transport handing back something
    // that is not SSE at all: no `[DONE]`, no error frame, no blank line. The
    // reader has to bound it — the alternative is one JS string growing until
    // the device runs out of memory, with nothing on screen changing.
    let pulled = 0;
    async function* endless(): AsyncGenerator<string> {
      for (let i = 0; i < 200; i += 1) {
        pulled += 1;
        yield 'x'.repeat(8 * 1024);
      }
      throw new Error('the reader never stopped');
    }
    const outcome = await readSseStream(endless());
    expect(outcome.ending).toBe('truncated');
    expect(outcome.error).toBeNull();
    // It stopped as soon as the residue passed the ceiling, not 200 chunks in.
    expect(pulled).toBeLessThanOrEqual(MAX_FRAME_RESIDUE_CHARS / (8 * 1024) + 1);
  });

  it('keeps the deltas that arrived before the delimiter ceiling was reached', async () => {
    async function* deltasThenGarbage(): AsyncGenerator<string> {
      yield `${delta('kept ')}\n\n`;
      yield 'y'.repeat(MAX_FRAME_RESIDUE_CHARS + 1);
    }
    const outcome = await readSseStream(deltasThenGarbage());
    expect(outcome.ending).toBe('truncated');
    expect(outcome.content).toBe('kept ');
  });

  it('bounds the answer itself, for a stream of deltas that never ends', async () => {
    const block = 'z'.repeat(16 * 1024);
    async function* forever(): AsyncGenerator<string> {
      for (let i = 0; i < 200; i += 1) {
        yield `${delta(block)}\n\n`;
      }
      throw new Error('the reader never stopped');
    }
    const outcome = await readSseStream(forever());
    expect(outcome.ending).toBe('truncated');
    // Everything that arrived is kept, and it stopped just past the ceiling.
    expect(outcome.content.length).toBeGreaterThan(MAX_ANSWER_CHARS);
    expect(outcome.content.length).toBeLessThanOrEqual(MAX_ANSWER_CHARS + block.length);
  });

  it('throws SseFrameTooLargeError from the buffer itself, and drops the residue', () => {
    const buffer = new SseFrameBuffer();
    expect(() => buffer.push('a'.repeat(MAX_FRAME_RESIDUE_CHARS + 1))).toThrow(
      SseFrameTooLargeError
    );
    // The residue is gone rather than kept for a delimiter that will not come.
    expect(buffer.flush()).toEqual([]);
  });

  it('leaves a frame just under the ceiling alone', () => {
    const buffer = new SseFrameBuffer();
    const long = 'a'.repeat(MAX_FRAME_RESIDUE_CHARS - 32);
    expect(buffer.push(`data: ${long}`)).toEqual([]);
    expect(buffer.push('\n\n')).toEqual([long]);
  });

  it('reads a [DONE] the server wrote without a trailing blank line', async () => {
    const outcome = await readSseStream(chunksOf(`${delta('done')}\n\ndata: ${SSE_DONE}`));
    expect(outcome.ending).toBe('done');
    expect(outcome.content).toBe('done');
  });

  it('stops consuming after [DONE]', async () => {
    let pulled = 0;
    async function* counted(): AsyncGenerator<string> {
      pulled += 1;
      yield `data: ${SSE_DONE}\n\n`;
      pulled += 1;
      yield `${delta('should never be read')}\n\n`;
    }
    const outcome = await readSseStream(counted());
    expect(outcome.content).toBe('');
    expect(pulled).toBe(1);
  });

  it('aborts mid-stream and keeps what arrived', async () => {
    const controller = new AbortController();
    let produced = 0;
    async function* aborting(): AsyncGenerator<string> {
      produced += 1;
      yield `${delta('first ')}\n\n`;
      // A real transport stops producing once its signal fires; modelling one
      // that keeps yielding would be testing a source that cannot exist.
      controller.abort();
    }
    const outcome = await readSseStream(aborting(), { signal: controller.signal });
    expect(outcome.ending).toBe('aborted');
    expect(outcome.content).toBe('first ');
    expect(produced).toBe(1);
  });

  it('returns immediately when the signal is already aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    let pulled = false;
    async function* never(): AsyncGenerator<string> {
      pulled = true;
      yield `data: ${SSE_DONE}\n\n`;
    }
    const outcome = await readSseStream(never(), { signal: controller.signal });
    expect(outcome.ending).toBe('aborted');
    expect(pulled).toBe(false);
  });

  it('skips a non-JSON frame and keeps reading the ones after it', async () => {
    const outcome = await readSseStream(
      chunksOf(
        'data: <html>a proxy error page</html>\n\n',
        `${delta('still here')}\n\n`,
        `data: ${SSE_DONE}\n\n`
      )
    );
    expect(outcome.ending).toBe('done');
    expect(outcome.content).toBe('still here');
  });
});

describe('drain', () => {
  it('joins every chunk of a body that was not a stream', async () => {
    await expect(drain(chunksOf('{"suc', 'cess":true}') as ChunkSource)).resolves.toBe(
      '{"success":true}'
    );
  });
});

describe('streamUrl', () => {
  afterEach(() => serverConfig.__resetServerConnectionForTests());

  it('refuses to build a URL when no server is connected', () => {
    serverConfig.__resetServerConnectionForTests();
    jest.spyOn(serverConfig, 'getBaseUrl').mockReturnValue(null);
    expect(() => streamUrl('/v1/chat/completions')).toThrow(NoServerConnectedError);
    jest.restoreAllMocks();
  });

  it('builds the same `<server>/api/v1/...` shape the axios instance uses', () => {
    jest.spyOn(serverConfig, 'getBaseUrl').mockReturnValue('https://trove.example.com');
    expect(streamUrl('/v1/chat/completions', { stream: 'true' })).toBe(
      'https://trove.example.com/api/v1/chat/completions?stream=true'
    );
    expect(streamUrl('/v1/chat/completions')).toBe(
      'https://trove.example.com/api/v1/chat/completions'
    );
    jest.restoreAllMocks();
  });
});

describe('streamAuthHeaders', () => {
  it('declares bearer mode and asks for the event stream', () => {
    const headers = streamAuthHeaders();
    expect(headers['X-Trove-Auth-Mode']).toBe('bearer');
    expect(headers['X-Requested-With']).toBe('XMLHttpRequest');
    expect(headers.Accept).toBe('text/event-stream');
  });

  it('carries an extra header through, e.g. the session id', () => {
    expect(streamAuthHeaders({ 'X-Session-Id': 'c-1' })['X-Session-Id']).toBe('c-1');
  });

  it('omits Authorization entirely when there is no access token', () => {
    // The in-memory token is null in a fresh test process; the header must be
    // absent rather than `Bearer null`.
    expect(streamAuthHeaders().Authorization).toBeUndefined();
  });
});

describe('supportsFetchStreaming / selectTransport', () => {
  const scope = globalThis as Record<string, unknown>;
  const saved = {
    fetch: scope.fetch,
    ReadableStream: scope.ReadableStream,
    Response: scope.Response,
  };

  afterEach(() => {
    scope.fetch = saved.fetch;
    scope.ReadableStream = saved.ReadableStream;
    scope.Response = saved.Response;
  });

  it('is false on a runtime whose Response has no body — which is React Native', () => {
    // `whatwg-fetch` over React Native's XHR builds no `body`, because Hermes
    // has no ReadableStream for it to build one from. This is the branch the
    // shipped app actually takes.
    scope.fetch = () => undefined;
    scope.ReadableStream = function ReadableStreamStub() {};
    scope.Response = function ResponseStub() {};
    (scope.Response as { prototype: object }).prototype = {};
    expect(supportsFetchStreaming()).toBe(false);
    expect(selectTransport()).toBe(xhrChunkSource);
  });

  it('is true when fetch, ReadableStream and a Response body all exist', () => {
    scope.fetch = () => undefined;
    scope.ReadableStream = function ReadableStreamStub() {};
    scope.Response = function ResponseStub() {};
    (scope.Response as { prototype: object }).prototype = { body: null };
    expect(supportsFetchStreaming()).toBe(true);
    expect(selectTransport()).toBe(fetchChunkSource);
  });

  it('is false when fetch itself is missing', () => {
    scope.fetch = undefined;
    expect(supportsFetchStreaming()).toBe(false);
  });

  it('is false when there is no ReadableStream', () => {
    scope.fetch = () => undefined;
    scope.ReadableStream = undefined;
    expect(supportsFetchStreaming()).toBe(false);
  });

  it('is false when there is no Response constructor', () => {
    scope.fetch = () => undefined;
    scope.ReadableStream = function ReadableStreamStub() {};
    scope.Response = undefined;
    expect(supportsFetchStreaming()).toBe(false);
  });
});

describe('fetchChunkSource', () => {
  const scope = globalThis as Record<string, unknown>;
  const savedFetch = scope.fetch;
  afterEach(() => {
    scope.fetch = savedFetch;
  });

  function fakeResponse(status: number, parts: string[] | null, headers: Record<string, string>) {
    const encoder = new TextEncoder();
    let index = 0;
    return {
      status,
      headers: {
        forEach(visit: (value: string, key: string) => void) {
          for (const [key, value] of Object.entries(headers)) visit(value, key);
        },
      },
      text: async () => (parts ?? []).join(''),
      body:
        parts === null
          ? undefined
          : {
              getReader: () => ({
                read: async () =>
                  index < parts.length
                    ? { done: false, value: encoder.encode(parts[index++]) }
                    : { done: true, value: undefined },
                cancel: () => undefined,
              }),
            },
    };
  }

  it('POSTs the body with the given headers and streams the reader out', async () => {
    const calls: Array<[string, RequestInit]> = [];
    scope.fetch = (url: string, init: RequestInit) => {
      calls.push([url, init]);
      return Promise.resolve(
        fakeResponse(200, [`${delta('a')}\n\n`, `data: ${SSE_DONE}\n\n`], {
          'Content-Type': 'text/event-stream',
          'X-Session-Id': 'c-9',
        })
      );
    };

    const response = await fetchChunkSource(
      { path: '/v1/chat/completions', body: '{"messages":[]}', headers: { 'X-Session-Id': 'c-9' } },
      'https://trove.example.com/api/v1/chat/completions?stream=true'
    );

    expect(calls[0][0]).toBe('https://trove.example.com/api/v1/chat/completions?stream=true');
    expect(calls[0][1].method).toBe('POST');
    expect(calls[0][1].body).toBe('{"messages":[]}');
    // Header names are lower-cased so a caller reads one name, not two.
    expect(response.headers['x-session-id']).toBe('c-9');
    expect(response.status).toBe(200);

    const outcome = await readSseStream(response.chunks);
    expect(outcome).toEqual({ content: 'a', ending: 'done', error: null });
  });

  it('falls back to one buffered chunk when a response unexpectedly has no reader', async () => {
    scope.fetch = () =>
      Promise.resolve(fakeResponse(409, null, { 'Content-Type': 'application/json' }));
    const response = await fetchChunkSource({ path: '/x', body: '{}' }, 'https://x/api/x');
    await expect(drain(response.chunks)).resolves.toBe('');
    expect(response.status).toBe(409);
  });
});

describe('xhrChunkSource', () => {
  const scope = globalThis as Record<string, unknown>;
  const savedXhr = scope.XMLHttpRequest;

  interface FakeXhr {
    listeners: Record<string, Array<() => void>>;
    readyState: number;
    status: number;
    responseText: string;
    responseType: string;
    sent: string | null;
    requestHeaders: Record<string, string>;
    aborted: boolean;
    open: () => void;
    setRequestHeader: (name: string, value: string) => void;
    addEventListener: (type: string, listener: () => void) => void;
    removeEventListener: () => void;
    getAllResponseHeaders: () => string;
    send: (body: string) => void;
    abort: () => void;
  }

  let last: FakeXhr;

  beforeEach(() => {
    // A constructor that RETURNS an object: `new` then yields that object, so
    // the fake needs no `this` at all and every handler closes over the one
    // instance the test drives.
    scope.XMLHttpRequest = function FakeXMLHttpRequest() {
      const instance: FakeXhr = {
        listeners: {},
        readyState: 0,
        status: 0,
        responseText: '',
        responseType: '',
        sent: null,
        requestHeaders: {},
        aborted: false,
        open: () => undefined,
        setRequestHeader: (name: string, value: string) => {
          instance.requestHeaders[name] = value;
        },
        addEventListener: (type: string, listener: () => void) => {
          (instance.listeners[type] ??= []).push(listener);
        },
        removeEventListener: () => undefined,
        getAllResponseHeaders: () =>
          'Content-Type: text/event-stream\r\nX-Session-Id: c-77\r\nnot-a-header-line',
        send: (body: string) => {
          instance.sent = body;
        },
        abort: () => {
          instance.aborted = true;
        },
      };
      last = instance;
      return instance;
    };
  });

  afterEach(() => {
    scope.XMLHttpRequest = savedXhr;
  });

  /** Push the next piece of `responseText` and fire the LOADING event. */
  const arrive = (text: string, readyState = 3) => {
    last.responseText += text;
    last.readyState = readyState;
    last.status = 200;
    for (const listener of last.listeners.readystatechange ?? []) listener();
  };

  it('delivers only the new tail of responseText on each arrival', async () => {
    const pending = xhrChunkSource(
      {
        path: '/v1/chat/completions',
        body: '{"messages":[]}',
        headers: { 'X-Session-Id': 'c-77' },
      },
      'https://trove.example.com/api/v1/chat/completions?stream=true'
    );
    arrive(`${delta('one ')}\n\n`);
    const response = await pending;

    expect(last.sent).toBe('{"messages":[]}');
    expect(last.requestHeaders['X-Session-Id']).toBe('c-77');
    expect(last.requestHeaders['Content-Type']).toBe('application/json');
    expect(response.headers['x-session-id']).toBe('c-77');
    expect(response.headers['content-type']).toBe('text/event-stream');

    const reading = readSseStream(response.chunks);
    // `responseText` accumulates; a reader that re-emitted the whole thing
    // would render "one one two".
    arrive(`${delta('two')}\n\n`);
    arrive(`data: ${SSE_DONE}\n\n`, 4);
    await expect(reading).resolves.toEqual({ content: 'one two', ending: 'done', error: null });
  });

  it('ends the chunk source when the request finishes without a [DONE]', async () => {
    const pending = xhrChunkSource({ path: '/x', body: '{}' }, 'https://x/api/x');
    arrive(`${delta('partial')}\n\n`);
    const response = await pending;
    const reading = readSseStream(response.chunks);
    arrive('', 4);
    await expect(reading).resolves.toMatchObject({ content: 'partial', ending: 'truncated' });
  });

  it('aborts the request when the signal fires', async () => {
    const controller = new AbortController();
    const pending = xhrChunkSource(
      { path: '/x', body: '{}', signal: controller.signal },
      'https://x/api/x'
    );
    arrive(`${delta('some')}\n\n`);
    const response = await pending;
    const reading = readSseStream(response.chunks, { signal: controller.signal });
    controller.abort();
    await expect(reading).resolves.toMatchObject({ content: 'some', ending: 'aborted' });
    expect(last.aborted).toBe(true);
  });

  it('rejects when the transport fails before the response was settled', async () => {
    const pending = xhrChunkSource({ path: '/x', body: '{}' }, 'https://x/api/x');
    for (const listener of last.listeners.error ?? []) listener();
    await expect(pending).rejects.toThrow('The connection to your Trove server failed.');
  });

  it('rejects when the request times out before the response was settled', async () => {
    const pending = xhrChunkSource({ path: '/x', body: '{}' }, 'https://x/api/x');
    for (const listener of last.listeners.timeout ?? []) listener();
    await expect(pending).rejects.toThrow('The connection to your Trove server timed out.');
  });

  it('surfaces a transport failure that happens after the stream opened', async () => {
    const pending = xhrChunkSource({ path: '/x', body: '{}' }, 'https://x/api/x');
    arrive(`${delta('half')}\n\n`);
    const response = await pending;
    const reading = readSseStream(response.chunks);
    for (const listener of last.listeners.error ?? []) listener();
    await expect(reading).rejects.toThrow('The connection to your Trove server failed.');
  });
});
