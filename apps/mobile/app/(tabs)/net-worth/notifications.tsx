import React from 'react';
import { FlatList, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import {
  useDismissNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
} from '@hooks/useNotifications';
import type { NotificationResponse, NotificationTypeCode } from '@api/types';

/**
 * The closed set of notification types. Deriving the copy map from the
 * generated `NotificationTypeCode` union means a sixth value added to the
 * contract is a compile error here, not a blank row on screen.
 */
const TYPE_LABEL: Record<NotificationTypeCode, string> = {
  BUDGET_THRESHOLD: 'Budget',
  SUBSCRIPTION_PRICE_CHANGE: 'Subscription',
  SYNC_FAILED: 'Sync',
  WEEKLY_DIGEST: 'Weekly digest',
  RECEIPT_PROCESSED: 'Receipt',
};

/**
 * `/v1/notifications*` — the caller's own inbox, paged. Reached from the
 * Settings notification card, not from a tab: this app's whole notification
 * story is polling, never a push.
 */
export default function NotificationsInboxScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const { data, isLoading, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useNotifications();
  const markAll = useMarkAllNotificationsRead();

  const rows = data?.pages.flatMap((page) => page.data) ?? [];
  const total = data?.pages[0]?.meta.total ?? 0;

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="notifications-inbox-screen"
    >
      <View style={styles.header}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="notifications-back"
        />
        <View>
          <Text style={[styles.title, { color: theme.ink }]}>Notifications</Text>
          {total > 0 && (
            <Text style={[styles.count, { color: theme.ink3 }]} testID="notifications-count">
              {format.number(total)} total
            </Text>
          )}
        </View>
        <MnBtn
          label="Mark all read"
          variant="secondary"
          loading={markAll.isPending}
          onPress={() => markAll.mutate()}
          testID="notifications-mark-all-read"
        />
      </View>

      <ScreenState
        isLoading={isLoading}
        error={error}
        isEmpty={rows.length === 0}
        emptyMessage="Nothing here yet."
        fallbackMessage="Could not load your notifications."
        onRetry={() => void refetch()}
        testID="notifications-inbox"
      >
        <FlatList
          data={rows}
          keyExtractor={(row) => row.id}
          contentContainerStyle={styles.list}
          onEndReachedThreshold={0.4}
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          renderItem={({ item }) => (
            <NotificationRow notification={item} formatDate={format.date} />
          )}
          ListFooterComponent={
            isFetchingNextPage ? (
              <Text
                style={[styles.footer, { color: theme.ink3 }]}
                testID="notifications-loading-more"
              >
                Loading more…
              </Text>
            ) : null
          }
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function NotificationRow({
  notification,
  formatDate,
}: {
  notification: NotificationResponse;
  formatDate: (iso: string | null | undefined, style?: 'short' | 'medium' | 'long') => string;
}) {
  const theme = useTheme();
  const markRead = useMarkNotificationRead();
  const dismiss = useDismissNotification();

  return (
    <View
      style={[
        styles.row,
        {
          borderBottomColor: theme.line,
          backgroundColor: notification.read ? 'transparent' : theme.sageSoft,
        },
      ]}
      testID={`notification-row-${notification.id}`}
    >
      <TouchableOpacity
        style={styles.rowMain}
        accessibilityRole="button"
        accessibilityLabel={`${TYPE_LABEL[notification.type]}: ${notification.title}${
          notification.read ? '' : ', unread'
        }`}
        onPress={() => {
          if (!notification.read) markRead.mutate(notification.id);
        }}
        testID={`notification-row-${notification.id}-open`}
      >
        <Text style={[styles.rowTitle, { color: theme.ink }]}>{notification.title}</Text>
        <Text style={[styles.rowBody, { color: theme.ink2 }]}>{notification.body}</Text>
        <Text style={[styles.rowMeta, { color: theme.ink3 }]}>
          {TYPE_LABEL[notification.type]} · {formatDate(notification.createdAt, 'medium')}
        </Text>
      </TouchableOpacity>
      <MnBtn
        label="Dismiss"
        variant="ghost"
        loading={dismiss.isPending}
        onPress={() => dismiss.mutate(notification.id)}
        testID={`notification-row-${notification.id}-dismiss`}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: Space.s4,
  },
  title: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.lg },
  count: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  list: { paddingHorizontal: Space.s4 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.s3,
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    minHeight: 64,
  },
  rowMain: { flex: 1, gap: 2 },
  rowTitle: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  rowBody: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  rowMeta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  footer: {
    textAlign: 'center',
    paddingVertical: Space.s4,
    fontFamily: FontFamily.body,
    fontSize: FontSize.sm,
  },
});
