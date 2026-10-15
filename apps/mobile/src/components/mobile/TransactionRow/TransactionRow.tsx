import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { TRANSACTION_TYPE_LABEL, isOutflow } from '@lib/labels';
import type { TransactionResponse } from '@api/types';

/**
 * One row of the ledger.
 *
 * `amount` is the absolute value and `type` carries the direction — that is the
 * convention every ingested row uses — so the sign shown here comes from
 * `isOutflow(type)` and never from looking for a minus in the string. The
 * amount is rendered in the **row's own** `currency`, which is the account's,
 * not the member's reporting currency: the two differ on a household holding
 * both, and there is no rate to convert between them.
 *
 * `pending`, `manual` and a transfer link are all stated in words as well as
 * being visible, so a screen reader reaches them.
 */
export interface TransactionRowProps {
  transaction: TransactionResponse;
  onPress?: (id: string) => void;
  testID?: string;
}

export function TransactionRow({ transaction, onPress, testID }: TransactionRowProps) {
  const theme = useTheme();
  const format = useUserFormat();
  const title = transaction.merchantName ?? transaction.description ?? 'Unnamed transaction';
  const typeLabel = TRANSACTION_TYPE_LABEL[transaction.type];
  const signedAmount = isOutflow(transaction.type) ? `-${transaction.amount}` : transaction.amount;

  return (
    <TouchableOpacity
      style={[styles.row, { borderBottomColor: theme.line }]}
      onPress={onPress ? () => onPress(transaction.id) : undefined}
      disabled={onPress === undefined}
      accessibilityRole="button"
      accessibilityLabel={`${title}, ${format.money(signedAmount, {
        currency: transaction.currency,
      })}, ${typeLabel}, ${format.date(transaction.date, 'medium')}`}
      testID={testID ?? `transaction-row-${transaction.id}`}
    >
      <View style={styles.main}>
        <Text style={[styles.title, { color: theme.ink }]} numberOfLines={1}>
          {title}
        </Text>
        <Text style={[styles.meta, { color: theme.ink3 }]} numberOfLines={1}>
          {format.date(transaction.date, 'medium')} · {typeLabel}
        </Text>
        <View style={styles.badges}>
          {transaction.pending ? <MnPill label="Pending" variant="amber" /> : null}
          {transaction.manual ? <MnPill label="Manual" variant="muted" /> : null}
          {transaction.transferGroupId !== null ? (
            <MnPill
              label="Transfer"
              variant="blue"
              testID={`transaction-transfer-${transaction.id}`}
            />
          ) : null}
        </View>
      </View>
      <MoneyDisplay
        amount={signedAmount}
        currency={transaction.currency}
        size="sm"
        colorBySign
        testID={`transaction-amount-${transaction.id}`}
      />
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.s3,
    paddingVertical: Space.s3,
    minHeight: 44,
    borderBottomWidth: 1,
  },
  main: { flex: 1, gap: 2 },
  title: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  badges: { flexDirection: 'row', gap: Space.s1, marginTop: 2 },
});
