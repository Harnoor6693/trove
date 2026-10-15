/**
 * The coach conversation, and the rest of `/v1/chat/*` and `/v1/insights`.
 *
 * ## What holds the transcript, and what does not
 *
 * The transcript is **local component state**, not a query cache, because it
 * is written a token at a time by a stream rather than replaced by a fetch.
 * The server is still the record: `GET /v1/chat/history` rehydrates it, and
 * `messages` on the way out is never this array — the server takes the last
 * `user` turn and rebuilds the rest from its own store, so sending the
 * transcript back would let this app put words into the model's mouth.
 *
 * ## Nothing here mints an id
 *
 * A turn's React key is its index. The conversation's id is the server's,
 * arriving in `X-Session-Id` on every response; the `crypto.randomUUID` /
 * `react-native-get-random-values` polyfill the pre-collapse app carried to
 * mint one is deleted, and this module is why it is not needed.
 *
 * ## Every ending of a stream is a state, not a toast
 *
 *  - `'done'` — the answer is complete.
 *  - `'error'` — a frame carried an RFC 7807 problem and there was no
 *    `[DONE]` after it. **What arrived stays on the screen**, and the slug
 *    chooses the sentence underneath it. `answer-too-long` is the case that
 *    proves the rule: the endpoint did not fail, the answer simply outran the
 *    length the server stores.
 *  - `'truncated'` — the body ended with neither. Said plainly rather than
 *    left as a spinner that never stops.
 *  - `'aborted'` — the member pressed Stop, or the screen went away.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type UseInfiniteQueryResult,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  deleteChatHistory,
  fetchChatHistory,
  fetchChatSuggestions,
  fetchInsights,
  streamCoachAnswer,
} from '@api/coach';
import { slugOf, toApiFailure, type ApiFailure } from '@api/errors';
import type { SseTransport, StreamEnding } from '@api/sse';
import type { CoachMessageResponse, InsightResponse, Page } from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const coachKeys = {
  all: () => ['coach'] as const,
  suggestions: () => ['coach', 'suggestions'] as const,
  history: (sessionId: string | null) => ['coach', 'history', sessionId] as const,
  insights: () => ['coach', 'insights'] as const,
};

/** One turn as the screen renders it. Lower-case roles, matching `CoachBubble`. */
export interface CoachTurn {
  role: 'user' | 'assistant';
  content: string;
  /** True while this assistant turn is still being written by the stream. */
  streaming: boolean;
}

/** What the screen says under the transcript when a stream did not simply finish. */
export interface CoachNotice {
  /** The error slug, or `'truncated'` / `'aborted'` for the endings with no slug. */
  code: string;
  message: string;
  /** True when the answer above it is a partial one that is being kept. */
  partial: boolean;
}

/**
 * Copy for the slugs this operation can really emit, in band or out.
 *
 * Keyed off the slug and never off the status: `ai-disabled` and `conflict`
 * are both 409, `ai-policy-blocked` and a refused CSRF header are both 403.
 * The server's own `detail` is the fallback for anything not listed, which is
 * what keeps this from being a second, drifting copy of the error table.
 */
export function coachNoticeCopy(
  code: string | null,
  detail: string,
  retryAfterSeconds: number | null = null
): string {
  switch (code) {
    case 'ai-disabled':
      return 'The coach needs your own AI endpoint. Add one in Settings to start asking questions.';
    case 'ai-policy-blocked':
      return 'Your privacy tier does not allow sending your ledger to this endpoint. Set it to local-only in Settings.';
    case 'ai-unreachable':
      return 'Your AI endpoint could not be reached. Check that it is running and reachable from your server.';
    case 'ai-endpoint-unauthorized':
      return 'Your AI endpoint rejected the stored API key. Enter it again in Settings.';
    case 'ai-model-missing':
      return 'Your AI endpoint does not serve the model you chose. Pick another one in Settings.';
    case 'ai-timeout':
      return 'Your AI endpoint did not answer in time. What arrived is kept below.';
    case 'outbound-url-rejected':
      return 'Your saved AI endpoint is no longer allowed by this server. Check the address in Settings.';
    case 'answer-too-long':
      return 'The answer reached the length this server stores, so it stops here. Everything above is kept.';
    case 'rate-limited':
      // The limiter sends a real number and the coach is the surface it bites
      // most often, so the number is shown — worded the way the receipt detail
      // screen words the same limiter, so the two do not tell the member
      // different things. "In a moment" is the fallback for a proxy that
      // stripped `Retry-After`.
      return `Too many questions for the moment. Ask again${
        retryAfterSeconds === null ? ' in a moment' : ` in ${retryAfterSeconds} seconds`
      }.`;
    case 'truncated':
      return 'The answer stopped before it finished. Everything that arrived is kept below.';
    case 'aborted':
      return 'Stopped. Everything that arrived is kept below.';
    default:
      return detail;
  }
}

export interface UseCoach {
  turns: CoachTurn[];
  sessionId: string | null;
  isStreaming: boolean;
  /** A refusal that arrived before the stream opened, normalised. */
  failure: ApiFailure | null;
  /** How the last stream ended, when it was not a clean finish. */
  notice: CoachNotice | null;
  ending: StreamEnding | null;
  ask: (question: string) => Promise<void>;
  stop: () => void;
  /** Replace the transcript with a conversation loaded from the server. */
  adoptHistory: (sessionId: string, messages: CoachMessageResponse[]) => void;
  /** Forget the local transcript and start a new conversation. */
  reset: () => void;
}

export interface UseCoachOptions {
  /** Test seam, passed through to `streamCoachAnswer`. */
  transport?: SseTransport;
}

export function useCoach({ transport }: UseCoachOptions = {}): UseCoach {
  const [turns, setTurns] = useState<CoachTurn[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const [notice, setNotice] = useState<CoachNotice | null>(null);
  const [ending, setEnding] = useState<StreamEnding | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const mounted = useRef(true);

  // Unmounting aborts an open stream. Without this the reader keeps consuming
  // the body of a screen nobody is looking at and calls `setState` on a gone
  // component every time a token lands.
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      abortRef.current?.abort();
    };
  }, []);

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    setTurns([]);
    setSessionId(null);
    setFailure(null);
    setNotice(null);
    setEnding(null);
    setIsStreaming(false);
  }, []);

  const adoptHistory = useCallback((id: string, messages: CoachMessageResponse[]) => {
    setSessionId(id);
    setFailure(null);
    setNotice(null);
    setEnding(null);
    setTurns(
      messages.map((message) => ({
        // The stored role is upper case (`ChatTurnRole`); the bubble's is
        // lower case. The two spellings are not interchangeable and this is
        // the one place they meet.
        role: message.role === 'ASSISTANT' ? 'assistant' : 'user',
        content: message.content,
        streaming: false,
      }))
    );
  }, []);

  const ask = useCallback(
    async (question: string) => {
      const trimmed = question.trim();
      if (trimmed === '' || isStreaming) return;

      const controller = new AbortController();
      abortRef.current = controller;
      setFailure(null);
      setNotice(null);
      setEnding(null);
      setIsStreaming(true);
      // The question and an empty assistant turn go up together, so the
      // stream has somewhere to write and the member sees their own question
      // immediately rather than after the first token.
      setTurns((current) => [
        ...current,
        { role: 'user', content: trimmed, streaming: false },
        { role: 'assistant', content: '', streaming: true },
      ]);

      const writeAnswer = (whole: string, streaming: boolean) => {
        if (!mounted.current) return;
        setTurns((current) => {
          const next = [...current];
          const last = next.length - 1;
          if (last < 0 || next[last].role !== 'assistant') return current;
          next[last] = { role: 'assistant', content: whole, streaming };
          return next;
        });
      };

      try {
        const result = await streamCoachAnswer({
          question: trimmed,
          sessionId,
          signal: controller.signal,
          transport,
          onDelta: (_delta, whole) => writeAnswer(whole, true),
        });

        if (!mounted.current) return;
        writeAnswer(result.content, false);
        if (result.sessionId !== null) setSessionId(result.sessionId);
        setEnding(result.ending);

        if (result.ending === 'error') {
          const code = slugOf(result.error?.type) ?? 'ai-unreachable';
          setNotice({
            code,
            message: coachNoticeCopy(
              code,
              result.error?.detail ?? 'The answer stopped before it finished.'
            ),
            partial: result.content !== '',
          });
        } else if (result.ending === 'truncated' || result.ending === 'aborted') {
          setNotice({
            code: result.ending,
            message: coachNoticeCopy(result.ending, ''),
            partial: result.content !== '',
          });
        }
      } catch (err) {
        if (!mounted.current) return;
        // A refusal decided before the stream opened: an unknown session, AI
        // switched off, the privacy tier, a malformed body, the limiter. The
        // empty assistant turn is dropped — there is no partial answer to keep.
        setTurns((current) => {
          const next = [...current];
          if (next.length > 0 && next[next.length - 1].role === 'assistant') next.pop();
          return next;
        });
        const normalised = toApiFailure(err, 'The coach could not answer that.');
        setFailure(normalised);
        setNotice({
          code: normalised.code ?? 'unknown',
          message: coachNoticeCopy(
            normalised.code,
            normalised.message,
            normalised.retryAfterSeconds
          ),
          partial: false,
        });
      } finally {
        if (mounted.current) setIsStreaming(false);
        abortRef.current = null;
      }
    },
    [isStreaming, sessionId, transport]
  );

  return { turns, sessionId, isStreaming, failure, notice, ending, ask, stop, adoptHistory, reset };
}

/**
 * `GET /v1/chat/suggestions` — what seeds the empty state.
 *
 * The screen hardcodes no prompts. This list costs no AI call and needs no
 * configured endpoint, so it answers even for a member with AI switched off,
 * which is exactly the member who most needs something to look at.
 */
export function useChatSuggestions(): UseQueryResult<string[]> {
  return useQuery({
    queryKey: coachKeys.suggestions(),
    queryFn: fetchChatSuggestions,
    staleTime: 5 * 60_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/chat/history` — paged, newest conversation first. */
export function useChatHistory(
  sessionId: string | null = null,
  enabled = true
): UseInfiniteQueryResult<InfiniteData<Page<CoachMessageResponse>>> {
  return useInfiniteQuery({
    queryKey: coachKeys.history(sessionId),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      // `sessionId` is omitted rather than sent empty: `?sessionId=` is a 400,
      // not "every conversation".
      fetchChatHistory(
        sessionId === null || sessionId === ''
          ? { page: pageParam as number }
          : { sessionId, page: pageParam as number }
      ),
    getNextPageParam: nextChatPage,
    enabled,
    retry: retryUnlessRefused,
  });
}

/** Shared pager rule for the two paged coach lists. */
export function nextChatPage<T>(last: Page<T>): number | undefined {
  const { page, limit, total } = last.meta;
  if (limit <= 0) return undefined;
  return (page + 1) * limit < total ? page + 1 : undefined;
}

/**
 * `DELETE /v1/chat/history` — one conversation, or **all of them** when no id
 * is given.
 *
 * Destructive and irreversible; the screen confirms and says which of the two
 * it is about to do before this runs.
 */
export function useDeleteChatHistory(): UseMutationResult<void, Error, string | null> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (sessionId: string | null) => deleteChatHistory(sessionId),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: coachKeys.all() });
    },
  });
}

/**
 * `GET /v1/insights` — the weekly notes, paged, newest first.
 *
 * A safe GET: reading this page does not set `delivered`.
 */
export function useInsights(): UseInfiniteQueryResult<InfiniteData<Page<InsightResponse>>> {
  return useInfiniteQuery({
    queryKey: coachKeys.insights(),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchInsights({ page: pageParam as number }),
    getNextPageParam: nextChatPage,
    staleTime: 5 * 60_000,
    retry: retryUnlessRefused,
  });
}
