/**
 * The SSE reader, against the frame contract as `openapi/trove-core.yaml` pins
 * it. This is the highest-risk code in the wave, so it is tested twice: the pure
 * parser directly, and the whole reader against a real `ReadableStream` whose
 * chunk boundaries are chosen to break it.
 */
import { describe, expect, it, vi } from 'vitest'
import { parseSseBuffer, parseSseData, readChatEventStream, SSE_DONE } from './sse'

const encoder = new TextEncoder()

/** A stream that writes exactly these chunks, in order, then closes. */
function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
}

const delta = (text: string) => `data: {"choices":[{"delta":{"content":${JSON.stringify(text)}}}]}\n\n`
const DONE = `data: ${SSE_DONE}\n\n`
const errorFrame = (slug: string, status: number) =>
  `data: {"error":{"type":"https://trove.finance/errors/${slug}","title":"T","status":${status},"detail":"d","instance":"/api/v1/chat/completions","requestId":"r"}}\n\n`

describe('parseSseData', () => {
  it('reads the terminator', () => {
    expect(parseSseData(SSE_DONE)).toEqual({ kind: 'done' })
  })

  it('reads a delta frame', () => {
    expect(parseSseData('{"choices":[{"delta":{"content":"hi"}}]}')).toEqual({
      kind: 'delta',
      text: 'hi',
    })
  })

  it('reads an opening frame that carries no content as an empty delta, not an error', () => {
    expect(parseSseData('{"choices":[{"delta":{"role":"assistant"}}]}')).toEqual({
      kind: 'delta',
      text: '',
    })
  })

  it('reads an in-band error frame', () => {
    const event = parseSseData(
      '{"error":{"type":"https://trove.finance/errors/ai-timeout","status":504,"title":"Gateway Timeout"}}'
    )
    expect(event.kind).toBe('error')
    expect(event.kind === 'error' && event.error.type).toBe(
      'https://trove.finance/errors/ai-timeout'
    )
  })

  it('reports a non-JSON frame rather than throwing', () => {
    expect(parseSseData('<html>gateway</html>')).toEqual({
      kind: 'unparseable',
      raw: '<html>gateway</html>',
    })
  })
})

describe('parseSseBuffer', () => {
  it('returns only complete frames and keeps the remainder', () => {
    const { events, rest } = parseSseBuffer(`${delta('a')}data: {"choices":[{"del`)
    expect(events).toEqual([{ kind: 'delta', text: 'a' }])
    expect(rest).toBe('data: {"choices":[{"del')
  })

  it('reassembles a frame that was split across a chunk boundary', () => {
    const whole = delta('groceries')
    const cut = Math.floor(whole.length / 2)
    const first = parseSseBuffer(whole.slice(0, cut))
    expect(first.events).toEqual([])
    const second = parseSseBuffer(first.rest + whole.slice(cut))
    expect(second.events).toEqual([{ kind: 'delta', text: 'groceries' }])
    expect(second.rest).toBe('')
  })

  it('accepts CRLF frame delimiters', () => {
    const { events } = parseSseBuffer('data: {"choices":[{"delta":{"content":"x"}}]}\r\n\r\n')
    expect(events).toEqual([{ kind: 'delta', text: 'x' }])
  })

  it('ignores comment keep-alives and non-data lines', () => {
    const { events } = parseSseBuffer(`: keep-alive\n\nevent: ping\n\n${delta('y')}`)
    expect(events).toEqual([{ kind: 'delta', text: 'y' }])
  })

  it('joins several data lines in one frame, as the SSE format says', () => {
    const { events } = parseSseBuffer('data: {"choices":[{"delta":\ndata: {"content":"z"}}]}\n\n')
    expect(events).toEqual([{ kind: 'delta', text: 'z' }])
  })
})

describe('readChatEventStream', () => {
  it('renders incrementally and ends cleanly on [DONE]', async () => {
    const seen: string[] = []
    const result = await readChatEventStream(streamOf([delta('You spent '), delta('a lot.'), DONE]), {
      onDelta: (text) => seen.push(text),
    })
    expect(seen).toEqual(['You spent ', 'a lot.'])
    expect(result).toMatchObject({ content: 'You spent a lot.', endedBy: 'done', error: null })
  })

  it('reassembles a frame split across two chunks', async () => {
    const whole = delta('groceries')
    const cut = 20
    const result = await readChatEventStream(
      streamOf([whole.slice(0, cut), whole.slice(cut), DONE])
    )
    expect(result.content).toBe('groceries')
    expect(result.endedBy).toBe('done')
  })

  it('surfaces an IN-BAND error frame with no [DONE] after it, and KEEPS what arrived', async () => {
    const result = await readChatEventStream(
      streamOf([delta('Partial answer'), errorFrame('answer-too-long', 400)])
    )
    // The endpoint did not fail and the slug says what happened, so the text stays.
    expect(result.content).toBe('Partial answer')
    expect(result.endedBy).toBe('error')
    expect(result.error?.code).toBe('answer-too-long')
    expect(result.error?.status).toBe(400)
  })

  it('does not hang on a stream that ends with neither [DONE] nor an error frame', async () => {
    const result = await readChatEventStream(streamOf([delta('Cut off mid-')]))
    expect(result.content).toBe('Cut off mid-')
    expect(result.endedBy).toBe('truncated')
    expect(result.error).toBeNull()
  })

  it('reads a final frame that arrived without its trailing blank line', async () => {
    const result = await readChatEventStream(
      streamOf([delta('Done '), `data: ${SSE_DONE}`])
    )
    expect(result.content).toBe('Done ')
    expect(result.endedBy).toBe('done')
  })

  it('keeps a non-JSON frame out of the answer and reports it separately', async () => {
    const result = await readChatEventStream(
      streamOf([delta('real '), 'data: not json at all\n\n', delta('text'), DONE])
    )
    expect(result.content).toBe('real text')
    expect(result.unparseable).toEqual(['not json at all'])
    expect(result.endedBy).toBe('done')
  })

  it('reports an aborted read as aborted, keeping what arrived', async () => {
    const controller = new AbortController()
    const stream = new ReadableStream<Uint8Array>({
      start(ctl) {
        ctl.enqueue(encoder.encode(delta('half ')))
        controller.signal.addEventListener('abort', () => {
          ctl.error(new DOMException('The operation was aborted.', 'AbortError'))
        })
      },
    })
    const reading = readChatEventStream(stream, {
      onDelta: () => controller.abort(),
    })
    const result = await reading
    expect(result.endedBy).toBe('aborted')
    expect(result.content).toBe('half ')
    expect(result.error).toBeNull()
  })

  it('ends on the abort SIGNAL even when the read never settles', async () => {
    // The case that matters, and the one the fetch alone does not cover: some
    // runtimes — this app's own test environment included — leave a pending
    // `read()` unsettled when the request is aborted, so a reader that waits
    // only on the fetch hangs for ever on exactly the Stop control.
    const controller = new AbortController()
    const stream = new ReadableStream<Uint8Array>({
      start(ctl) {
        ctl.enqueue(encoder.encode(delta('half ')))
        // Never closed, never errored: only the signal can end this.
      },
    })
    const reading = readChatEventStream(stream, {
      signal: controller.signal,
      onDelta: () => controller.abort(),
    })
    const result = await reading
    expect(result.endedBy).toBe('aborted')
    expect(result.content).toBe('half ')
    expect(result.error).toBeNull()
  })

  it('throws rather than hanging when the response carried no body at all', async () => {
    await expect(readChatEventStream(null)).rejects.toThrow(/no body/i)
  })

  it('never renders a frame as markup: the delta is handed over as plain text', async () => {
    const onDelta = vi.fn()
    const result = await readChatEventStream(
      streamOf([delta('<img src=x onerror=alert(1)>'), DONE]),
      { onDelta }
    )
    // The reader returns exactly the characters the server sent. Escaping is
    // React's job at the render site; inventing any here would corrupt an answer
    // that legitimately contains angle brackets.
    expect(result.content).toBe('<img src=x onerror=alert(1)>')
    expect(onDelta).toHaveBeenCalledWith('<img src=x onerror=alert(1)>', '<img src=x onerror=alert(1)>')
  })
})
