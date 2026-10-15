import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MAX_QUESTION_CHARS } from '@api/coach';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { CoachBubble } from '@components/mobile/CoachBubble/CoachBubble';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import {
  useChatHistory,
  useChatSuggestions,
  useCoach,
  useDeleteChatHistory,
  useInsights,
} from '@hooks/useCoach';
import { coachReadiness, useAiSettings } from '@hooks/useAiSettings';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';

/**
 * The coach: a conversation with the member's **own** AI endpoint over their
 * household's ledger, plus the weekly insights the same feature writes.
 *
 * ## When AI is off, this must not look broken
 *
 * `GET /v1/ai/settings` says whether an endpoint is configured, and the chat
 * routes answer 409 `ai-disabled` when it is not. Rendering a composer that
 * can only ever fail would be worse than useless, so the screen asks first and
 * shows an explicit "the coach needs your own AI endpoint" state with a route
 * to the AI card in Settings — which is why these two surfaces landed as one
 * entry. The suggestions still load: that list costs no AI call and needs no
 * endpoint, so even this state has something real in it.
 *
 * ## What goes up
 *
 * One `user` turn per question, never the transcript — the server rebuilds the
 * history from its own store, and accepting a client-supplied `assistant` turn
 * would let this screen put words into the model's mouth. The conversation id
 * is the server's, from `X-Session-Id`; nothing here mints one.
 */
type Segment = 'chat' | 'insights';

export default function CoachScreen() {
  const theme = useTheme();
  const router = useRouter();
  const [segment, setSegment] = useState<Segment>('chat');

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="coach-screen">
      <View style={styles.header}>
        <MnEyebrow>Coach</MnEyebrow>
        <View style={styles.segments}>
          <SegmentButton
            label="Chat"
            selected={segment === 'chat'}
            onPress={() => setSegment('chat')}
            testID="coach-segment-chat"
          />
          <SegmentButton
            label="Insights"
            selected={segment === 'insights'}
            onPress={() => setSegment('insights')}
            testID="coach-segment-insights"
          />
        </View>
      </View>
      {segment === 'chat' ? <ChatPane router={router} /> : <InsightsPane />}
    </SafeAreaView>
  );
}

function SegmentButton({
  label,
  selected,
  onPress,
  testID,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  testID: string;
}) {
  const theme = useTheme();
  return (
    <TouchableOpacity
      onPress={onPress}
      accessibilityRole="tab"
      accessibilityState={{ selected }}
      accessibilityLabel={label}
      testID={testID}
      style={[
        styles.segment,
        {
          borderColor: selected ? theme.sage : theme.line,
          backgroundColor: selected ? theme.sageSoft : 'transparent',
        },
      ]}
    >
      <Text style={[styles.segmentText, { color: selected ? theme.sage : theme.ink2 }]}>
        {label}
      </Text>
    </TouchableOpacity>
  );
}

function ChatPane({ router }: { router: ReturnType<typeof useRouter> }) {
  const theme = useTheme();
  const settings = useAiSettings();
  const block = coachReadiness(settings.data);
  const coach = useCoach();
  const suggestions = useChatSuggestions();
  // `coachReadiness` is null both for "ready" and for "not loaded yet", so the
  // history fetch waits for the row itself. Without that it fires on the first
  // render of a member who has no endpoint at all — a request that can only be
  // spent against the rate limiter.
  const settingsLoaded = settings.data !== undefined;
  const history = useChatHistory(null, settingsLoaded && block === null);
  const clearHistory = useDeleteChatHistory();
  const [question, setQuestion] = useState('');
  const [confirmingClear, setConfirmingClear] = useState(false);
  const rehydrated = useRef(false);

  // Rehydrate the most recent conversation once. The page spans every
  // conversation, most recently used first and chronological within each, so
  // the first row names the conversation to reopen and no second request is
  // needed to group it.
  // Memoised so the effect below does not see a new array identity on every
  // render — a fresh `[]` each time would re-run it for ever.
  const historyRows = useMemo(
    () => history.data?.pages.flatMap((page) => page.data) ?? [],
    [history.data]
  );
  useEffect(() => {
    if (rehydrated.current || historyRows.length === 0) return;
    rehydrated.current = true;
    const mostRecent = historyRows[0].sessionId;
    coach.adoptHistory(
      mostRecent,
      historyRows.filter((row) => row.sessionId === mostRecent)
    );
  }, [historyRows, coach]);

  if (settings.isLoading) {
    return <ScreenState isLoading testID="coach-settings" />;
  }

  if (block !== null) {
    return (
      <ScrollView contentContainerStyle={styles.content}>
        <MnCard style={styles.card} testID="coach-needs-endpoint">
          <Text style={[styles.title, { color: theme.ink }]}>
            The coach needs your own AI endpoint
          </Text>
          <Text style={[styles.body, { color: theme.ink2 }]} accessibilityRole="alert">
            {block === 'disabled'
              ? 'Trove never ships an AI service of its own. Point it at an endpoint you control — a local Ollama, or any OpenAI-compatible URL — and the coach can answer questions about your household ledger.'
              : block === 'no-model'
                ? 'Your endpoint is saved but no chat model has been chosen. Pick one and the coach can start.'
                : 'Every coach question carries figures from your ledger, so it is only sent to an endpoint you have marked local-only. Change your privacy tier to local-only to use the coach.'}
          </Text>
          <MnBtn
            label="Open AI settings"
            onPress={() => router.push('/(tabs)/settings')}
            style={styles.rowBtn}
            testID="coach-open-ai-settings"
          />
        </MnCard>
        <SuggestionList
          suggestions={suggestions.data ?? []}
          disabled
          onPick={() => undefined}
          caption="When the coach is set up, these are the questions it can start with."
        />
      </ScrollView>
    );
  }

  const canSend = question.trim() !== '' && !coach.isStreaming;

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <FlatList
        data={coach.turns}
        keyExtractor={(_turn, index) => String(index)}
        renderItem={({ item, index }) => (
          <CoachBubble
            role={item.role}
            content={item.content}
            streaming={item.streaming}
            testID={`coach-turn-${index}`}
          />
        )}
        contentContainerStyle={styles.transcript}
        ListEmptyComponent={
          <View style={styles.content}>
            <Text style={[styles.body, { color: theme.ink2 }]}>
              Ask about your own ledger — spending, budgets, subscriptions. The answer is written by
              the endpoint you configured, from figures your server assembles; nothing is sent
              anywhere else.
            </Text>
            <SuggestionList
              suggestions={suggestions.data ?? []}
              onPick={(text) => setQuestion(text)}
              caption="Try one of these:"
            />
          </View>
        }
      />

      {/* One small polite region for the *state*, so a screen reader hears
          "answering" and "finished" rather than the whole thread re-read on
          every token that lands. */}
      <Text
        accessibilityLiveRegion="polite"
        accessibilityRole="text"
        style={[styles.status, { color: theme.ink3 }]}
        testID="coach-status"
      >
        {coach.isStreaming
          ? 'The coach is answering.'
          : coach.ending === 'done'
            ? 'Answer complete.'
            : ''}
      </Text>

      {coach.notice !== null && (
        <Text
          style={[styles.notice, { color: theme.amber }]}
          accessibilityRole="alert"
          testID="coach-notice"
        >
          {coach.notice.message}
        </Text>
      )}

      <View style={[styles.composer, { borderTopColor: theme.line, backgroundColor: theme.card }]}>
        <TextInput
          value={question}
          onChangeText={setQuestion}
          multiline
          maxLength={MAX_QUESTION_CHARS}
          placeholder="Ask the coach…"
          placeholderTextColor={theme.ink3}
          accessibilityLabel="Your question for the coach"
          style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
          testID="coach-input"
        />
        {coach.isStreaming ? (
          <MnBtn
            label="Stop"
            variant="ghost"
            onPress={coach.stop}
            testID="coach-stop"
            accessibilityLabel="Stop the coach answering"
          />
        ) : (
          <MnBtn
            label="Ask"
            disabled={!canSend}
            onPress={() => {
              const text = question;
              setQuestion('');
              void coach.ask(text);
            }}
            testID="coach-send"
          />
        )}
      </View>

      <View style={styles.footerRow}>
        {confirmingClear ? (
          <>
            <Text
              style={[styles.body, { color: theme.ink2 }]}
              accessibilityRole="alert"
              testID="coach-clear-confirm-text"
            >
              This deletes every coach conversation on your server, and the questions and answers in
              them. It cannot be undone.
            </Text>
            <MnBtn
              label="Delete every conversation"
              variant="danger"
              loading={clearHistory.isPending}
              onPress={() =>
                clearHistory.mutate(null, {
                  onSuccess: () => {
                    coach.reset();
                    setConfirmingClear(false);
                  },
                })
              }
              testID="coach-clear-confirm"
            />
            <MnBtn
              label="Keep them"
              variant="ghost"
              onPress={() => setConfirmingClear(false)}
              testID="coach-clear-cancel"
            />
          </>
        ) : (
          <MnBtn
            label="Clear history"
            variant="ghost"
            onPress={() => setConfirmingClear(true)}
            testID="coach-clear-start"
          />
        )}
      </View>
    </KeyboardAvoidingView>
  );
}

function SuggestionList({
  suggestions,
  onPick,
  caption,
  disabled = false,
}: {
  suggestions: string[];
  onPick: (text: string) => void;
  caption: string;
  disabled?: boolean;
}) {
  const theme = useTheme();
  if (suggestions.length === 0) return null;
  return (
    <View style={styles.suggestions} testID="coach-suggestions">
      <Text style={[styles.caption, { color: theme.ink3 }]}>{caption}</Text>
      {suggestions.map((text, index) => (
        <TouchableOpacity
          key={text}
          disabled={disabled}
          onPress={() => onPick(text)}
          accessibilityRole="button"
          accessibilityLabel={text}
          accessibilityState={{ disabled }}
          style={[styles.suggestion, { borderColor: theme.line }]}
          testID={`coach-suggestion-${index}`}
        >
          <Text style={[styles.suggestionText, { color: theme.ink2 }]}>{text}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

function InsightsPane() {
  const theme = useTheme();
  const format = useUserFormat();
  const insights = useInsights();
  const rows = insights.data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <ScreenState
      isLoading={insights.isLoading}
      error={insights.error}
      isEmpty={rows.length === 0}
      emptyMessage="No weekly notes yet. They are written once a week for members whose AI is switched on."
      fallbackMessage="Could not load your insights."
      onRetry={() => void insights.refetch()}
      testID="coach-insights"
    >
      <FlatList
        data={rows}
        keyExtractor={(insight) => insight.id}
        onEndReachedThreshold={0.4}
        onEndReached={() => {
          if (insights.hasNextPage && !insights.isFetchingNextPage) void insights.fetchNextPage();
        }}
        contentContainerStyle={styles.content}
        renderItem={({ item }) => (
          <MnCard style={styles.card} testID={`coach-insight-${item.id}`}>
            <Text style={[styles.caption, { color: theme.ink3 }]}>
              Week of {format.date(item.weekStart, 'medium')}
            </Text>
            <Text style={[styles.body, { color: theme.ink }]}>{item.content}</Text>
          </MnCard>
        )}
      />
    </ScreenState>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  flex: { flex: 1 },
  header: { paddingHorizontal: Space.s4, paddingTop: Space.s4 },
  segments: { flexDirection: 'row', gap: Space.s2, marginTop: Space.s2 },
  segment: {
    paddingHorizontal: Space.s4,
    paddingVertical: Space.s2,
    borderRadius: Radius.md,
    borderWidth: 1,
    minHeight: 44,
    justifyContent: 'center',
  },
  segmentText: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  content: { padding: Space.s4 },
  card: { marginBottom: Space.s4 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg },
  body: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginTop: Space.s2 },
  caption: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs, letterSpacing: 1 },
  rowBtn: { marginTop: Space.s4 },
  transcript: { paddingVertical: Space.s3 },
  status: { fontFamily: FontFamily.body, fontSize: FontSize.xs, paddingHorizontal: Space.s4 },
  notice: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.sm,
    paddingHorizontal: Space.s4,
    paddingBottom: Space.s2,
  },
  composer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: Space.s2,
    padding: Space.s3,
    borderTopWidth: 1,
  },
  input: {
    flex: 1,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    maxHeight: 120,
    minHeight: 48,
  },
  suggestions: { marginTop: Space.s4, gap: Space.s2 },
  suggestion: {
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    minHeight: 44,
    justifyContent: 'center',
  },
  suggestionText: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  footerRow: { padding: Space.s3, gap: Space.s2 },
});
