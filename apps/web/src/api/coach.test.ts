/**
 * The coach API layer, at the level the screens do not exercise: the
 * non-streaming ask, the JSON-despite-`stream=true` case the operation
 * explicitly allows, and the two paged reads' `meta` guards.
 */
import { describe, expect, it } from 'vitest'
import {
  deleteChatHistory,
  listChatHistory,
  listChatSuggestions,
  listInsights,
  sendCoachTurn,
  streamCoachTurn,
} from './coach'
import { mswState, resetMswState } from '@/test/msw/handlers'

describe('sendCoachTurn — the non-streaming ask', () => {
  it('sends exactly one user turn and reads the session off X-Session-Id', async () => {
    resetMswState()
    const answer = await sendCoachTurn({ question: 'What did I spend?', sessionId: null })

    expect(mswState.lastChatBody).toEqual({
      messages: [{ role: 'user', content: 'What did I spend?' }],
    })
    // `X-Session-Id` is on EVERY response, streaming or not.
    expect(answer.sessionId).toBe(mswState.chatSessionId)
    expect(answer.answer.role).toBe('ASSISTANT')
  })

  it('sends the session back as a HEADER, which is where the operation declares it', async () => {
    resetMswState()
    await sendCoachTurn({ question: 'Again', sessionId: mswState.chatSessionId })
    expect(mswState.lastChatSessionHeader).toBe(mswState.chatSessionId)
  })

  it('omits the header entirely to start a new conversation', async () => {
    resetMswState()
    await sendCoachTurn({ question: 'New', sessionId: null })
    expect(mswState.lastChatSessionHeader).toBeNull()
  })

  it('raises the typed slug rather than a generic failure', async () => {
    resetMswState({ chatFailure: { status: 409, slug: 'ai-disabled', title: 'Conflict' } })
    await expect(sendCoachTurn({ question: 'x', sessionId: null })).rejects.toMatchObject({
      status: 409,
      code: 'ai-disabled',
    })
  })
})

describe('streamCoachTurn — reading the body by its content type, not by what was asked', () => {
  it('accepts a JSON answer to a stream=true request, as the operation allows', async () => {
    resetMswState({ chatAnswersJsonDespiteStream: true })
    const deltas: string[] = []
    const result = await streamCoachTurn({
      question: 'x',
      sessionId: null,
      onDelta: (text) => deltas.push(text),
    })

    expect(result.wasStreamed).toBe(false)
    expect(result.endedBy).toBe('done')
    expect(result.content).toBe('You spent the most on Groceries.')
    // Reported as one complete delta rather than as a stream that produced nothing.
    expect(deltas).toEqual(['You spent the most on Groceries.'])
  })

  it('rejects a pre-stream failure with an ApiError, before any body is read', async () => {
    resetMswState({
      chatFailure: { status: 403, slug: 'ai-policy-blocked', title: 'Forbidden' },
    })
    await expect(
      streamCoachTurn({ question: 'x', sessionId: null })
    ).rejects.toMatchObject({ status: 403, code: 'ai-policy-blocked' })
  })
})

describe('the paged reads and the destructive delete', () => {
  it('consumes the meta listChatHistory is required to send', async () => {
    resetMswState()
    const page = await listChatHistory({ page: 0, limit: 50 })
    expect(page.meta).toEqual({ total: 2, page: 0, limit: 50 })
    expect(page.rows).toHaveLength(2)
  })

  it('consumes the meta listInsights is required to send', async () => {
    resetMswState()
    const page = await listInsights({ page: 0, limit: 10 })
    expect(page.meta.total).toBe(1)
  })

  it('OMITS sessionId rather than sending it blank — `?sessionId=` is 400, not "all"', async () => {
    resetMswState()
    await deleteChatHistory(null)
    // The handler records `null` for an absent parameter and would have answered
    // 400 for an empty one; a rejection here would mean the client sent blank.
    expect(mswState.chatHistoryDeletes).toEqual([null])
    expect(mswState.chatMessages).toEqual([])
  })

  it('names one conversation when given one', async () => {
    resetMswState()
    await deleteChatHistory(mswState.chatSessionId)
    expect(mswState.chatHistoryDeletes).toEqual([mswState.chatSessionId])
  })

  it('reads the suggestions as a bare array — the operation sends no meta', async () => {
    resetMswState()
    await expect(listChatSuggestions()).resolves.toEqual(mswState.chatSuggestions)
  })
})
