import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import {
  ACCOUNT_SYNC_STATUS_LABEL,
  ACCOUNT_SYNC_STATUS_VARIANT,
  ACCOUNT_TYPE_LABEL,
  SOURCE_KIND_LABEL,
} from '@lib/labels';
import type { AccountResponse } from '@api/types';

/**
 * One row of the accounts list.
 *
 * The account's **own** currency is shown, never the member's reporting one:
 * `AccountResponse.currency` is the denomination every transaction stored
 * against this account was written in, and there is no exchange rate anywhere
 * in this product. The card therefore states the code beside the type rather
 * than leaving the reader to assume.
 *
 * Sync state is a word (`ACCOUNT_SYNC_STATUS_LABEL`) with a colour behind it,
 * not a colour alone, and `errorMessage` — the reason the last run failed — is
 * rendered rather than swallowed.
 *
 * **There is no balance on this card because the contract carries none.**
 * `AccountResponse` has no `balance` or current-value property, and no other
 * operation in the accounts family exposes one, so a figure here could only be
 * computed on the phone from a page of transactions — a number the server
 * never agreed to, in a product where nothing is ever summed across
 * currencies. The gap is reported in step 211's openIssues for the
 * orchestrator to weigh; it is not something a client fills in.
 */
export interface AccountCardProps {
  account: AccountResponse;
  onPress?: (id: string) => void;
  testID?: string;
}

export function AccountCard({ account, onPress, testID }: AccountCardProps) {
  const theme = useTheme();
  const format = useUserFormat();
  const status = ACCOUNT_SYNC_STATUS_LABEL[account.syncStatus];

  return (
    <TouchableOpacity
      style={[styles.card, { backgroundColor: theme.card, borderColor: theme.line }]}
      onPress={onPress ? () => onPress(account.id) : undefined}
      disabled={onPress === undefined}
      accessibilityRole="button"
      accessibilityLabel={`${account.displayName}, ${
        ACCOUNT_TYPE_LABEL[account.accountType]
      }, ${account.currency}, ${status}`}
      testID={testID ?? `account-row-${account.id}`}
    >
      <View style={styles.headline}>
        <Text style={[styles.name, { color: theme.ink }]} numberOfLines={1}>
          {account.displayName}
        </Text>
        <MnPill
          label={status}
          variant={ACCOUNT_SYNC_STATUS_VARIANT[account.syncStatus]}
          testID={`account-status-${account.id}`}
        />
      </View>

      <Text style={[styles.meta, { color: theme.ink3 }]} numberOfLines={1}>
        {[
          account.institution,
          ACCOUNT_TYPE_LABEL[account.accountType],
          account.currency,
          SOURCE_KIND_LABEL[account.sourceKind],
        ]
          .filter((part): part is string => typeof part === 'string' && part !== '')
          .join(' · ')}
      </Text>

      <Text style={[styles.meta, { color: theme.ink3 }]} testID={`account-synced-${account.id}`}>
        {account.lastSyncedAt === null
          ? 'Never synced'
          : `Last synced ${format.date(account.lastSyncedAt, 'dateTime')}`}
      </Text>

      {account.errorMessage !== null && account.errorMessage !== '' ? (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`account-error-${account.id}`}
        >
          {account.errorMessage}
        </Text>
      ) : null}
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
    borderRadius: Radius.lg,
    padding: Space.s4,
    gap: Space.s1,
    minHeight: 44,
  },
  headline: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.s3,
  },
  name: { flex: 1, fontFamily: FontFamily.displaySemi, fontSize: FontSize.md },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.xs, marginTop: Space.s1 },
});
