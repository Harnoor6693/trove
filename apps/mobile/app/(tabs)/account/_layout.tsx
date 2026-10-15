import React from 'react';
import { Stack } from 'expo-router';

/**
 * The account stack — data export and self-deletion (step 215, family E).
 *
 * A stack behind a Settings card, not a tab: `app/(tabs)/_layout.tsx` declares
 * this directory with `href: null` so expo-router knows the route without
 * auto-mounting it as a ninth tab, which is the defect that file already fixed
 * once for `receipts/`.
 */
export default function AccountLayout() {
  return <Stack screenOptions={{ headerShown: false }} />;
}
