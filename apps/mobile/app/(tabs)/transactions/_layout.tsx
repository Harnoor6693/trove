import React from 'react';
import { Stack } from 'expo-router';

/**
 * The Transactions tab is a nested stack, exactly the way `receipts/` already
 * is.
 *
 * **The navigation decision for this family, made in one place.** Accounts,
 * manual entry, the transaction detail and the import wizard all live under
 * this stack rather than becoming tabs of their own: the tab bar already
 * carries its eight entries, a ninth would not fit a phone, and accounts are
 * what a ledger is filtered by, so they belong behind it.
 *
 * `app/(tabs)/_layout.tsx` is therefore **unchanged** — its
 * `<Tabs.Screen name="transactions">` entry already names this directory, and
 * a directory with a `_layout` is one route to the tab navigator however many
 * screens are inside it. That entry is what stops expo-router auto-mounting
 * the directory as an extra untitled tab, which is a defect this app shipped
 * once and fixed (mobile inventory §1).
 */
export default function TransactionsLayout() {
  return <Stack screenOptions={{ headerShown: false }} />;
}
