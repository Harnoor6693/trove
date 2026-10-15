import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';

/**
 * One turn of the conversation.
 *
 * **The answer is text and is never interpreted.** It is rendered by a
 * `<Text>` and nothing else — never a `WebView`, never HTML, never a markdown
 * renderer — and no id, link or command is ever taken from it. That is the
 * contract's own rule for this operation, not a styling preference.
 *
 * The bubble is **not** an accessibility live region, even while it is being
 * written. A region that re-reads its contents would restate the whole answer
 * on every token that lands. The screen announces the *state* — answering,
 * finished, stopped — through one small polite region instead, which is a
 * sentence per change rather than a sentence per token.
 */
export interface CoachBubbleProps {
  role: 'user' | 'assistant';
  content: string;
  /** True while the stream is still writing into this bubble. */
  streaming?: boolean;
  testID?: string;
}

export function CoachBubble({ role, content, streaming = false, testID }: CoachBubbleProps) {
  const theme = useTheme();
  const isUser = role === 'user';
  return (
    <View
      style={[styles.wrapper, isUser ? styles.wrapperUser : styles.wrapperAssistant]}
      testID={testID ?? `bubble-wrapper-${role}`}
    >
      <View
        style={[
          styles.bubble,
          isUser
            ? { backgroundColor: theme.sage }
            : { backgroundColor: theme.card, borderWidth: 1, borderColor: theme.line },
        ]}
        accessible
        accessibilityRole="text"
        accessibilityLiveRegion="none"
        accessibilityLabel={`${isUser ? 'You asked' : 'The coach answered'}: ${content}`}
      >
        <Text
          style={[
            styles.text,
            isUser
              ? { fontFamily: FontFamily.body, color: theme.paper }
              : { fontFamily: FontFamily.display, color: theme.ink },
          ]}
        >
          {content}
        </Text>
        {streaming && (
          <Text
            style={[styles.caret, { color: theme.ink3 }]}
            accessibilityElementsHidden
            importantForAccessibility="no"
            testID="coach-bubble-streaming"
          >
            ▍
          </Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: { marginVertical: Space.s1, paddingHorizontal: Space.s4 },
  wrapperUser: { alignItems: 'flex-end' },
  wrapperAssistant: { alignItems: 'flex-start' },
  bubble: { maxWidth: '80%', padding: Space.s3, borderRadius: Radius.lg },
  text: { fontSize: FontSize.md },
  caret: { fontSize: FontSize.xs, marginTop: 2 },
});
