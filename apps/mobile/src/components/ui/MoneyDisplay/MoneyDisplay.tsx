import React from 'react';
import { Text, StyleSheet, type TextProps } from 'react-native';
import { useTheme } from '@design/useTheme';
import { FontSize, FontFamily } from '@design/tokens';
import { parseDecimal, useUserFormat, type MoneyOptions } from '@lib/useUserFormat';
import type { Decimal, Money } from '@api/types';

/**
 * Renders an amount in the signed-in user's currency and locale.
 *
 * `amount` is a decimal string (`"12.34"`) or a `Money` wrapper, matching what
 * the server sends (§5.4). The component no longer takes cents, no longer
 * defaults to CAD, and no longer pins `en-CA` — all three came from
 * `useUserFormat()` now, which reads the user's own preferences.
 */
export interface MoneyDisplayProps extends TextProps, MoneyOptions {
  amount: Decimal | Money | null | undefined;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Colour positive amounts green and negative red. Off by default. */
  colorBySign?: boolean;
}

type SizeKey = NonNullable<MoneyDisplayProps['size']>;

const sizeMap: Record<SizeKey, number> = {
  sm: FontSize.sm,
  md: FontSize.md,
  lg: FontSize.lg,
  xl: FontSize.xl,
};

function signOf(amount: Decimal | Money | null | undefined): number {
  if (amount === null || amount === undefined) return 0;
  const raw = typeof amount === 'string' ? amount : amount.amount;
  // The same strict parse the formatter uses, so the colour and the number can
  // never disagree about whether a value was readable at all.
  const parsed = parseDecimal(raw);
  return parsed === null ? 0 : Math.sign(parsed);
}

export function MoneyDisplay({
  amount,
  currency,
  hideCents,
  signed,
  size = 'md',
  colorBySign = false,
  style,
  ...props
}: MoneyDisplayProps) {
  const format = useUserFormat();
  const t = useTheme();
  const sign = signOf(amount);
  const color = !colorBySign ? t.ink : sign > 0 ? t.sage : sign < 0 ? t.red : t.ink;

  return (
    <Text style={[styles.base, { fontSize: sizeMap[size], color }, style]} {...props}>
      {format.money(amount, { currency, hideCents, signed })}
    </Text>
  );
}

const styles = StyleSheet.create({
  base: { fontFamily: FontFamily.displaySemi },
});
