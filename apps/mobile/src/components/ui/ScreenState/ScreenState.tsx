import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { NoServerConnectedError } from '@api/client';
import { toApiFailure, type ApiFailure } from '@api/errors';

/**
 * The four states every data-backed screen on this app has, in one component.
 *
 * This is the shape the other four families copy. A screen renders its query's
 * `isLoading`, `error` and emptiness through here and its own content as
 * `children`, so the four states look the same everywhere and — more
 * importantly — so the three states that are *not* the happy path cannot be
 * forgotten one screen at a time.
 *
 * Three failures get their own treatment rather than a generic toast, because
 * each is something the member can act on and none of them means what a
 * generic failure means:
 *
 *  - **`NoServerConnectedError`.** This app can be running with no server
 *    configured at all; that is a setup step, not an outage.
 *  - **429.** Every operation on this API except `getStatus` declares one, and
 *    it arrives with `Retry-After` in whole seconds. It is a wait-and-retry
 *    state with a real number in it. It must not clear the session and must
 *    not trigger a token refresh — neither happens here, and neither happens
 *    in `client.ts`, which only refreshes on a 401.
 *  - **Anything else.** The server's own `detail` text, normalised through the
 *    one error normaliser rather than read off `error.type` by hand.
 *
 * Every state is announced as text, not signalled by colour alone.
 */
export interface ScreenStateProps {
  isLoading: boolean;
  /** The query's `error`, or null. Normalised here; callers never parse it. */
  error?: unknown;
  /** True when the request succeeded and there is nothing to show. */
  isEmpty?: boolean;
  /** What to say when there is nothing to show. */
  emptyMessage?: string;
  /** Used when the failure body carries no `detail` or `title` of its own. */
  fallbackMessage?: string;
  onRetry?: () => void;
  testID?: string;
  children?: React.ReactNode;
}

/** The copy for a failure, given the normalised shape. Exported so it can be tested alone. */
export function failureMessage(error: unknown, failure: ApiFailure): string {
  if (error instanceof NoServerConnectedError) {
    return 'No Trove server is connected. Add one in Settings to see your data.';
  }
  if (failure.status === 429) {
    const seconds = failure.retryAfterSeconds;
    return seconds === null
      ? 'Too many requests. Wait a moment and try again.'
      : `Too many requests. Try again in ${seconds} seconds.`;
  }
  return failure.message;
}

export function ScreenState({
  isLoading,
  error = null,
  isEmpty = false,
  emptyMessage = 'Nothing here yet.',
  fallbackMessage = 'Could not load this.',
  onRetry,
  testID,
  children,
}: ScreenStateProps) {
  const theme = useTheme();

  if (error !== null && error !== undefined) {
    const failure = toApiFailure(error, fallbackMessage);
    return (
      <View style={styles.centre} testID={testID ? `${testID}-error` : undefined}>
        <Text
          accessibilityRole="alert"
          style={[styles.message, { color: theme.ink }]}
          testID={testID ? `${testID}-error-message` : undefined}
        >
          {failureMessage(error, failure)}
        </Text>
        {onRetry ? (
          <MnBtn
            label="Try again"
            variant="secondary"
            onPress={onRetry}
            testID={testID ? `${testID}-retry` : undefined}
          />
        ) : null}
      </View>
    );
  }

  if (isLoading) {
    return (
      <View style={styles.centre} testID={testID ? `${testID}-loading` : undefined}>
        <ActivityIndicator
          accessibilityLabel="Loading"
          accessibilityRole="progressbar"
          color={theme.sage}
        />
        <Text style={[styles.message, { color: theme.ink3 }]}>Loading…</Text>
      </View>
    );
  }

  if (isEmpty) {
    return (
      <View style={styles.centre} testID={testID ? `${testID}-empty` : undefined}>
        <Text style={[styles.message, { color: theme.ink3 }]}>{emptyMessage}</Text>
      </View>
    );
  }

  return <>{children}</>;
}

const styles = StyleSheet.create({
  centre: {
    alignItems: 'center',
    justifyContent: 'center',
    padding: Space.s6,
    gap: Space.s3,
  },
  message: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.sm,
    textAlign: 'center',
  },
});
