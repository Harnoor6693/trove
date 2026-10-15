import React from 'react';
import { Tabs } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '@design/useTheme';
import {
  TodayIcon,
  TxIcon,
  BudgetIcon,
  CoachIcon,
  SettingsIcon,
  NetWorthIcon,
  SubscriptionsIcon,
  ReceiptIcon,
} from '@components/icons';

/**
 * Eight tabs.
 *
 * Two changes from the previous set:
 *  - **Tax is gone.** The tax engine is cut (owner decision); a tax *export*
 *    lands as a screen, not a tab, in 21b.
 *  - **Receipts is declared.** `receipts/` had no `<Tabs.Screen>` entry, so
 *    expo-router auto-mounted the directory as an untitled ninth tab (mobile
 *    inventory §1).
 *
 * Tab-bar height comes from the safe-area inset rather than the hardcoded
 * `height: 84 / paddingBottom: 28` this file used to carry, which collided
 * with `edgeToEdgeEnabled=true` on Android (readiness gap P4-48).
 */
export default function TabLayout() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: theme.sage,
        tabBarInactiveTintColor: theme.ink3,
        tabBarStyle: {
          backgroundColor: theme.card,
          borderTopColor: theme.line,
          height: 56 + insets.bottom,
          paddingBottom: insets.bottom,
        },
        headerShown: false,
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: 'Today',
          tabBarButtonTestID: 'tab-today',
          tabBarIcon: ({ color }) => <TodayIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="transactions"
        options={{
          title: 'Transactions',
          tabBarButtonTestID: 'tab-transactions',
          tabBarIcon: ({ color }) => <TxIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="budgets"
        options={{
          title: 'Budgets',
          tabBarButtonTestID: 'tab-budgets',
          tabBarIcon: ({ color }) => <BudgetIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="net-worth"
        options={{
          title: 'Net Worth',
          tabBarButtonTestID: 'tab-net-worth',
          tabBarIcon: ({ color }) => <NetWorthIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="subscriptions"
        options={{
          title: 'Subs',
          tabBarButtonTestID: 'tab-subscriptions',
          tabBarIcon: ({ color }) => <SubscriptionsIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="receipts"
        options={{
          title: 'Receipts',
          tabBarButtonTestID: 'tab-receipts',
          tabBarIcon: ({ color }) => <ReceiptIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="coach"
        options={{
          title: 'Coach',
          tabBarButtonTestID: 'tab-coach',
          tabBarIcon: ({ color }) => <CoachIcon color={color} />,
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: 'Settings',
          tabBarButtonTestID: 'tab-settings',
          tabBarIcon: ({ color }) => <SettingsIcon color={color} />,
        }}
      />
      {/* Step 215, family E. Two stacks that are reached from a Settings card,
          not from the tab bar — data export and account deletion, and the
          admin console. `href: null` is how expo-router is told a route is
          navigable but not a tab; leaving the directories undeclared is what
          auto-mounted `receipts/` as an untitled ninth tab (mobile inventory
          §1), which is the defect this file already fixed once. Still eight
          visible tabs. */}
      <Tabs.Screen name="account" options={{ href: null }} />
      <Tabs.Screen name="admin" options={{ href: null }} />
    </Tabs>
  );
}
