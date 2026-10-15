import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import type { DashboardCurrencyTotals } from '@api/types';

/**
 * One currency's dashboard subtotals.
 *
 * The dashboard renders **one of these per entry in `byCurrency`**, each
 * labelled with its own code, and never a sum across them. There is no
 * exchange rate anywhere in this product, so adding a CAD subtotal to a USD
 * one would be inventing one — every amount here is passed to `MoneyDisplay`
 * with `totals.currency`, not with the member's reporting currency.
 */
export interface CurrencyTotalsCardProps {
  totals: DashboardCurrencyTotals;
  testID?: string;
}

export function CurrencyTotalsCard({ totals, testID }: CurrencyTotalsCardProps) {
  const theme = useTheme();
  const format = useUserFormat();
  const id = testID ?? `today-currency-${totals.currency}`;

  return (
    <MnCard style={[styles.card, { backgroundColor: theme.card }]} testID={id}>
      <Text
        style={[styles.code, { color: theme.ink3 }]}
        accessibilityLabel={`Totals in ${totals.currency}`}
        testID={`${id}-code`}
      >
        {totals.currency}
      </Text>

      <Figure
        label="Spent today"
        amount={totals.spentToday}
        currency={totals.currency}
        testID={`${id}-spent-today`}
        emphasis
      />
      <Text style={[styles.count, { color: theme.ink3 }]} testID={`${id}-count`}>
        {format.number(totals.transactionsToday)} transactions today
      </Text>

      <Figure
        label="Spent this month"
        amount={totals.spentThisMonth}
        currency={totals.currency}
        testID={`${id}-spent-month`}
      />
      <Figure
        label="Income this month"
        amount={totals.incomeThisMonth}
        currency={totals.currency}
        testID={`${id}-income-month`}
      />
      <Figure
        label="Net this month"
        amount={totals.netThisMonth}
        currency={totals.currency}
        testID={`${id}-net-month`}
      />
    </MnCard>
  );
}

function Figure({
  label,
  amount,
  currency,
  testID,
  emphasis = false,
}: {
  label: string;
  amount: string;
  currency: DashboardCurrencyTotals['currency'];
  testID: string;
  emphasis?: boolean;
}) {
  const theme = useTheme();
  return (
    <View style={styles.figure}>
      <Text style={[styles.label, { color: theme.ink2 }]}>{label}</Text>
      <MoneyDisplay
        amount={amount}
        currency={currency}
        size={emphasis ? 'xl' : 'md'}
        testID={testID}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  card: { gap: Space.s2 },
  code: { fontFamily: FontFamily.mono, fontSize: FontSize.xs, letterSpacing: 1 },
  figure: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    gap: Space.s3,
  },
  label: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  count: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
});
