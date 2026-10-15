import React from 'react';
import { Stack } from 'expo-router';

/**
 * The admin console stack — members and household settings, invitations, and
 * the audit log (step 215, family E).
 *
 * On a phone this is a stack behind a Settings card, not a tab:
 * `app/(tabs)/_layout.tsx` declares this directory with `href: null`, which
 * makes the route navigable without auto-mounting it as an extra tab.
 */
export default function AdminLayout() {
  return <Stack screenOptions={{ headerShown: false }} />;
}
