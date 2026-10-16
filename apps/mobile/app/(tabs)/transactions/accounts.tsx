import React, { useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { AccountCard } from '@components/mobile/AccountCard/AccountCard';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import {
  ACCOUNT_TYPES,
  ACCOUNT_TYPE_LABEL,
  CREATABLE_SOURCE_KINDS,
  SOURCE_KIND_LABEL,
} from '@lib/labels';
import { fieldError, toApiFailure } from '@api/errors';
import { useAccounts, useCreateAccount, useExchangeSetupToken } from '@hooks/useAccounts';
import type { AccountType, SourceKind } from '@api/types';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * The accounts surface — `GET /v1/accounts`, `POST /v1/accounts` and
 * `POST /v1/accounts/exchange-setup-token`.
 *
 * Each account shows its institution, its product type, **the currency it is
 * denominated in**, its sync status as a word and when it last synced. The
 * currency is the account's, never the member's reporting one: there is no
 * exchange rate in this product, so a household holding both currencies sees
 * each account in the one its transactions were written in.
 *
 * **No balance is shown, because the contract carries none.**
 * `AccountResponse` has no `balance` or current-value field — see the
 * openIssue raised for step 211 — and a figure computed on the phone from a
 * page of transactions would be a number the server never agreed to. Nothing
 * here fabricates one.
 *
 * **Three source kinds can be created here.** `MANUAL` is the hand-entry
 * account; `CSV` and `OFX` are the rows a statement upload attaches itself to,
 * and `POST /v1/imports` *requires* an `accountId` of the file's own kind for
 * a CSV, so without them the import surface would have no target and no way to
 * make one. `SIMPLEFIN` is absent on purpose: that row is created by the
 * credential exchange below, never by hand.
 *
 * **The setup token is a credential.** It is typed into component state, sent
 * once in the request body, and cleared in `onSettled` whether the call
 * succeeded or not. It is never written to the secure store, never put in a
 * URL, never logged, and the failure path renders the server's own message —
 * which the operation guarantees never echoes the token or the decoded URL —
 * rather than an error object that might carry it.
 */
export default function AccountsScreen() {
  const theme = useTheme();
  const router = useRouter();

  const accounts = useAccounts();
  const create = useCreateAccount();
  const exchange = useExchangeSetupToken();

  const [showAdd, setShowAdd] = useState(false);
  const [showConnect, setShowConnect] = useState(false);

  // `exchange.reset()` (see `submitToken`) is what evicts the setup token from
  // the mutation cache, and it also clears the mutation's own `data`/`error`.
  // The two things the member still has to see are therefore copied into local
  // state first: the outcome the exchange reported, and the raw error the
  // failure message is built from.
  const [connectedCount, setConnectedCount] = useState<number | null>(null);
  const [exchangeError, setExchangeError] = useState<unknown>(null);

  const [displayName, setDisplayName] = useState('');
  const [institution, setInstitution] = useState('');
  const [accountType, setAccountType] = useState<AccountType>('CHECKING');
  const [sourceKind, setSourceKind] = useState<SourceKind>('MANUAL');
  const [setupToken, setSetupToken] = useState('');

  const createFailure =
    create.error === null || create.error === undefined
      ? null
      : toApiFailure(create.error, 'Could not add that account.');
  const exchangeFailure =
    exchangeError === null || exchangeError === undefined
      ? null
      : toApiFailure(exchangeError, 'Could not connect that bank.');

  const submitAccount = () => {
    create.mutate(
      {
        // MANUAL, CSV or OFX. A `SIMPLEFIN` account is never created here —
        // that row comes from the credential exchange. A `CSV`/`OFX` account
        // has no uploaded file until one is imported against it, so
        // `POST /v1/accounts/{id}/sync` answers 200 with a `FAILED` sync_log
        // ("This account has no uploaded file to import"), not a 409. The 409
        // "no source registered" applies only to `MANUAL` accounts; the hint
        // below tells the member to import a file first.
        sourceKind,
        displayName: displayName.trim(),
        institution: institution.trim() === '' ? null : institution.trim(),
        accountType,
      },
      {
        onSuccess: () => {
          setDisplayName('');
          setInstitution('');
          setSourceKind('MANUAL');
          setShowAdd(false);
        },
      }
    );
  };

  const submitToken = () => {
    setConnectedCount(null);
    setExchangeError(null);
    exchange.mutate(
      { sourceKind: 'SIMPLEFIN', setupToken: setupToken.trim() },
      {
        // Cleared on every path, not only on success: a token left in state
        // after a failure is a credential sitting in memory for as long as the
        // screen is mounted.
        //
        // `exchange.reset()` is the other half of that, and it is not
        // optional. `mutate` hands the token to React Query, which keeps it as
        // `mutation.state.variables` in the app-level `MutationCache`; the
        // single client in `src/api/queryClient.ts` (provided by
        // `app/_layout.tsx`) overrides `defaultOptions.queries` only, so
        // mutations keep v5's five-minute `gcTime` and the credential would
        // stay reachable in the heap for five minutes after this screen
        // cleared its own field and unmounted. `reset()` drops `variables` and
        // `data` immediately instead of waiting that timer out.
        onSettled: () => {
          setSetupToken('');
          exchange.reset();
        },
        onSuccess: (result) => {
          setConnectedCount(result.accountIds.length);
          setShowConnect(false);
        },
        onError: (err) => setExchangeError(err),
      }
    );
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="accounts-screen">
      <FormScroll contentContainerStyle={styles.content}>
        <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="accounts-back" />
        <MnEyebrow>Accounts</MnEyebrow>

        <View style={styles.actions}>
          <MnBtn
            label={showAdd ? 'Cancel' : 'Add a manual account'}
            variant="secondary"
            style={styles.grow}
            onPress={() => setShowAdd((open) => !open)}
            testID="accounts-add-toggle"
          />
          <MnBtn
            label={showConnect ? 'Cancel' : 'Connect a bank'}
            variant="ghost"
            style={styles.grow}
            onPress={() => setShowConnect((open) => !open)}
            testID="accounts-connect-toggle"
          />
        </View>

        {showAdd ? (
          <MnCard style={[styles.card, { backgroundColor: theme.card }]} testID="accounts-add-form">
            <Labelled
              label="Name"
              value={displayName}
              onChangeText={setDisplayName}
              error={createFailure === null ? null : fieldError(createFailure, 'displayName')}
              testID="accounts-add-name"
            />
            <Labelled
              label="Institution"
              value={institution}
              onChangeText={setInstitution}
              error={createFailure === null ? null : fieldError(createFailure, 'institution')}
              testID="accounts-add-institution"
            />
            <Text style={[styles.label, { color: theme.ink2 }]}>Kind</Text>
            <View style={styles.chips}>
              {CREATABLE_SOURCE_KINDS.map((value) => (
                <TouchableOpacity
                  key={value}
                  onPress={() => setSourceKind(value)}
                  accessibilityRole="radio"
                  accessibilityLabel={SOURCE_KIND_LABEL[value]}
                  accessibilityState={{ selected: sourceKind === value }}
                  style={styles.chip}
                  testID={`accounts-add-source-${value}`}
                >
                  <MnPill
                    label={SOURCE_KIND_LABEL[value]}
                    variant={sourceKind === value ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
            </View>
            <Text style={[styles.hint, { color: theme.ink3 }]} testID="accounts-add-source-hint">
              {sourceKind === 'MANUAL'
                ? 'A manual account is the only kind you can enter a transaction on by hand.'
                : 'A file account is what a statement upload is imported into. It stays unsynced until you import a file against it.'}
            </Text>

            <Text style={[styles.label, { color: theme.ink2 }]}>Type</Text>
            <View style={styles.chips}>
              {ACCOUNT_TYPES.map((value) => (
                <TouchableOpacity
                  key={value}
                  onPress={() => setAccountType(value)}
                  accessibilityRole="radio"
                  accessibilityLabel={ACCOUNT_TYPE_LABEL[value]}
                  accessibilityState={{ selected: accountType === value }}
                  style={styles.chip}
                  testID={`accounts-add-type-${value}`}
                >
                  <MnPill
                    label={ACCOUNT_TYPE_LABEL[value]}
                    variant={accountType === value ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
            </View>
            <Text style={[styles.hint, { color: theme.ink3 }]}>
              The account takes your own reporting currency; an account always keeps the currency it
              was created with.
            </Text>
            {createFailure === null ? null : (
              <Text
                accessibilityRole="alert"
                style={[styles.error, { color: theme.red }]}
                testID="accounts-add-error"
              >
                {createFailure.status === 409
                  ? 'An account like this already exists in your household.'
                  : failureMessage(create.error, createFailure)}
              </Text>
            )}
            <MnBtn
              label="Add account"
              fullWidth
              loading={create.isPending}
              disabled={displayName.trim() === ''}
              onPress={submitAccount}
              testID="accounts-add-submit"
            />
          </MnCard>
        ) : null}

        {showConnect ? (
          <MnCard
            style={[styles.card, { backgroundColor: theme.card }]}
            testID="accounts-connect-form"
          >
            <Labelled
              label="SimpleFIN setup token"
              value={setupToken}
              onChangeText={setSetupToken}
              secure
              error={exchangeFailure === null ? null : fieldError(exchangeFailure, 'setupToken')}
              testID="accounts-connect-token"
            />
            <Text style={[styles.hint, { color: theme.ink3 }]}>
              One-time token from your bridge. It is sent once and never stored on this device.
            </Text>
            {exchangeFailure === null ? null : (
              <Text
                accessibilityRole="alert"
                style={[styles.error, { color: theme.red }]}
                testID="accounts-connect-error"
              >
                {exchangeFailure.code === 'outbound-url-rejected'
                  ? `That token was refused: ${exchangeFailure.message}`
                  : failureMessage(exchangeError, exchangeFailure)}
              </Text>
            )}
            <MnBtn
              label="Connect"
              fullWidth
              loading={exchange.isPending}
              disabled={setupToken.trim() === ''}
              onPress={submitToken}
              testID="accounts-connect-submit"
            />
          </MnCard>
        ) : null}

        {/* Outside the collapsible card on purpose: the form closes on success,
            and the member still needs to see what the exchange did. */}
        {connectedCount === null ? null : (
          <Text
            accessibilityRole="alert"
            style={[styles.hint, { color: theme.sage }]}
            testID="accounts-connect-result"
          >
            Connected {connectedCount} accounts.
          </Text>
        )}

        <ScreenState
          isLoading={accounts.isLoading}
          error={accounts.error}
          isEmpty={
            !accounts.isLoading && accounts.error === null && (accounts.data ?? []).length === 0
          }
          emptyMessage="No accounts yet. Add a manual one or connect a bank."
          fallbackMessage="Could not load your accounts."
          onRetry={() => void accounts.refetch()}
          testID="accounts"
        >
          <FlatList
            scrollEnabled={false}
            data={accounts.data ?? []}
            keyExtractor={(item) => item.id}
            contentContainerStyle={styles.list}
            renderItem={({ item }) => (
              <AccountCard
                account={item}
                onPress={(id) => router.push(`/(tabs)/transactions/account/${id}`)}
              />
            )}
            testID="accounts-list"
          />
        </ScreenState>
      </FormScroll>
    </SafeAreaView>
  );
}

function Labelled({
  label,
  value,
  onChangeText,
  error,
  secure = false,
  testID,
}: {
  label: string;
  value: string;
  onChangeText: (next: string) => void;
  error: string | null;
  secure?: boolean;
  testID: string;
}) {
  const theme = useTheme();
  return (
    <View style={styles.field}>
      <Text style={[styles.label, { color: theme.ink2 }]} nativeID={`${testID}-label`}>
        {label}
      </Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel={label}
        accessibilityLabelledBy={`${testID}-label`}
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.paper, borderColor: theme.line },
        ]}
        testID={testID}
      />
      {error === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {error}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, gap: Space.s3, paddingBottom: Space.s12 },
  actions: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  card: { gap: Space.s2 },
  list: { gap: Space.s3 },
  field: { gap: Space.s1 },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  hint: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: { minHeight: 44, justifyContent: 'center' },
  input: {
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
