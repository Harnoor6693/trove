import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { SYNC_RUN_STATUS_LABEL, SYNC_RUN_STATUS_VARIANT } from '@lib/labels';
import type { SyncLogResponse } from '@api/types';

/**
 * One run in an account's sync history.
 *
 * A failed run's **reason** is the point of this row. `errorMessage` is set on
 * `FAILED` and on `PARTIAL_FAILURE` and null on `SUCCESS`, and a history that
 * showed only a red dot would tell the member their bank connection is broken
 * without telling them what to do about it — so the message is rendered, with
 * `accessibilityRole="alert"`, and the status is a word before it is a colour.
 */
export interface SyncLogRowProps {
  log: SyncLogResponse;
  testID?: string;
}

export function SyncLogRow({ log, testID }: SyncLogRowProps) {
  const theme = useTheme();
  const format = useUserFormat();
  const status = SYNC_RUN_STATUS_LABEL[log.status];

  return (
    <View
      style={[styles.row, { borderBottomColor: theme.line }]}
      accessibilityLabel={`${status}, ${format.date(log.startedAt, 'dateTime')}, ${
        log.transactionsNew
      } new`}
      testID={testID ?? `sync-log-${log.id}`}
    >
      <View style={styles.headline}>
        <Text style={[styles.when, { color: theme.ink }]}>
          {format.date(log.startedAt, 'dateTime')}
        </Text>
        <MnPill label={status} variant={SYNC_RUN_STATUS_VARIANT[log.status]} />
      </View>
      <Text style={[styles.counts, { color: theme.ink3 }]}>
        {format.number(log.transactionsFetched)} fetched · {format.number(log.transactionsNew)} new
        · {format.number(log.transactionsUpdated)} updated ·{' '}
        {format.number(log.transactionsSkipped)} skipped · {format.number(log.transactionsFailed)}{' '}
        refused
      </Text>
      {log.errorMessage !== null && log.errorMessage !== '' ? (
        <Text
          accessibilityRole="alert"
          style={[styles.reason, { color: theme.red }]}
          testID={`sync-log-error-${log.id}`}
        >
          {log.errorMessage}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { paddingVertical: Space.s3, borderBottomWidth: 1, gap: Space.s1 },
  headline: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.s3,
  },
  when: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  counts: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  reason: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.xs },
});
