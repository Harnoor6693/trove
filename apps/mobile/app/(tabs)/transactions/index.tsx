import React, { useMemo, useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { TransactionRow } from '@components/mobile/TransactionRow/TransactionRow';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { TRANSACTION_TYPES, TRANSACTION_TYPE_LABEL } from '@lib/labels';
import { isUuid } from '@api/paths';
import { useAccount } from '@hooks/useAccounts';
import { useTransactions } from '@hooks/useTransactions';
import type { TransactionQuery, TransactionType } from '@api/types';

/**
 * The household ledger — `GET /v1/transactions`, paged.
 *
 * The list is a `FlatList` over **every page fetched so far**, not over page
 * one: the operation is paged and its `meta` carries `total`, so
 * `onEndReached` asks `useTransactions` for the next page until the ledger runs
 * out. A list fed from the first response alone would show the member 50 rows
 * and silently claim that was all of them.
 *
 * The filters this screen drives are the ones the operation declares: `q` and
 * `type` from the controls, `sort` fixed at `date,desc`, and `accountId` from
 * the route — "View transactions" on the account screen navigates here with
 * `?accountId=<id>`, which is the whole per-account ledger affordance, and the
 * chip it renders clears back to the household ledger. `from`, `to` and
 * `categoryId` are declared by the operation and are **not** wired to a
 * control in this build; that is written down in step 211's openIssues rather
 * than claimed here.
 *
 * Nothing is filtered client-side: a client-side filter over one page would
 * hide rows that are simply on page two.
 */
export default function TransactionsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();

  const params = useLocalSearchParams<{ accountId?: string }>();
  // From a route, so untrusted: anything that is not an id the contract could
  // have issued is ignored rather than sent as a filter.
  const accountId =
    typeof params.accountId === 'string' && isUuid(params.accountId) ? params.accountId : undefined;
  const account = useAccount(accountId ?? '');

  const [search, setSearch] = useState('');
  const [type, setType] = useState<TransactionType | undefined>(undefined);

  const query = useMemo<TransactionQuery>(
    () => ({
      q: search.trim() === '' ? undefined : search.trim(),
      type,
      accountId,
      sort: 'date,desc',
    }),
    [search, type, accountId]
  );

  const {
    data,
    isLoading,
    error,
    refetch,
    isRefetching,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useTransactions(query);

  const rows = useMemo(() => (data?.pages ?? []).flatMap((page) => page.data), [data]);
  const total = data?.pages[0]?.meta.total ?? 0;

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="transactions-screen"
    >
      <View style={styles.header}>
        <MnEyebrow>Transactions</MnEyebrow>
        <View style={styles.headerActions}>
          <MnBtn
            label="Accounts"
            variant="ghost"
            onPress={() => router.push('/(tabs)/transactions/accounts')}
            testID="transactions-to-accounts"
          />
          <MnBtn
            label="Add"
            variant="secondary"
            onPress={() => router.push('/(tabs)/transactions/new')}
            testID="transactions-add"
          />
        </View>
      </View>

      {accountId === undefined ? null : (
        <TouchableOpacity
          onPress={() => router.replace('/(tabs)/transactions')}
          accessibilityRole="button"
          accessibilityLabel="Showing one account only. Clear this filter."
          style={[styles.chip, styles.accountFilter]}
          testID="transactions-account-filter"
        >
          <MnPill
            label={`${account.data?.displayName ?? 'One account'} · show all`}
            variant="blue"
          />
        </TouchableOpacity>
      )}

      <TextInput
        value={search}
        onChangeText={setSearch}
        placeholder="Search merchant, description or notes"
        placeholderTextColor={theme.ink3}
        accessibilityLabel="Search transactions"
        style={[
          styles.search,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID="transactions-search"
      />

      <View style={styles.filters}>
        <TypeChip
          label="All"
          selected={type === undefined}
          onPress={() => setType(undefined)}
          testID="transactions-type-ALL"
        />
        {TRANSACTION_TYPES.map((value) => (
          <TypeChip
            key={value}
            label={TRANSACTION_TYPE_LABEL[value]}
            selected={type === value}
            onPress={() => setType(value)}
            testID={`transactions-type-${value}`}
          />
        ))}
      </View>

      <Text style={[styles.total, { color: theme.ink3 }]} testID="transactions-total">
        {format.number(total)} matching transactions
      </Text>

      <ScreenState
        isLoading={isLoading}
        error={error}
        isEmpty={!isLoading && error === null && rows.length === 0}
        emptyMessage="No transactions match this filter."
        fallbackMessage="Could not load your transactions."
        onRetry={() => void refetch()}
        testID="transactions"
      >
        <FlatList
          data={rows}
          keyExtractor={(item) => item.id}
          renderItem={({ item }) => (
            <TransactionRow
              transaction={item}
              onPress={(id) => router.push(`/(tabs)/transactions/${id}`)}
            />
          )}
          contentContainerStyle={styles.list}
          refreshing={isRefetching}
          onRefresh={() => void refetch()}
          onEndReachedThreshold={0.4}
          onEndReached={() => {
            // `hasNextPage` is derived from `meta.total`, so this stops at the
            // real end of the ledger rather than requesting empty pages.
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          ListFooterComponent={
            isFetchingNextPage ? (
              <Text
                style={[styles.footer, { color: theme.ink3 }]}
                testID="transactions-loading-more"
              >
                Loading more…
              </Text>
            ) : undefined
          }
          testID="transactions-list"
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function TypeChip({
  label,
  selected,
  onPress,
  testID,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  testID: string;
}) {
  return (
    <TouchableOpacity
      onPress={onPress}
      accessibilityRole="radio"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      style={styles.chip}
      testID={testID}
    >
      <MnPill label={label} variant={selected ? 'sage' : 'muted'} />
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: Space.s4,
    paddingTop: Space.s4,
  },
  headerActions: { flexDirection: 'row', gap: Space.s2 },
  search: {
    marginHorizontal: Space.s4,
    marginTop: Space.s3,
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  filters: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: Space.s2,
    paddingHorizontal: Space.s4,
    paddingTop: Space.s3,
  },
  chip: { minHeight: 44, justifyContent: 'center' },
  accountFilter: { paddingHorizontal: Space.s4, paddingTop: Space.s3, alignItems: 'flex-start' },
  total: {
    paddingHorizontal: Space.s4,
    paddingTop: Space.s2,
    fontFamily: FontFamily.body,
    fontSize: FontSize.xs,
  },
  list: { paddingHorizontal: Space.s4, paddingBottom: Space.s8 },
  footer: {
    textAlign: 'center',
    paddingVertical: Space.s4,
    fontFamily: FontFamily.body,
    fontSize: FontSize.xs,
  },
});
