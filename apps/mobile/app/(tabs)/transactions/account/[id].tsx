import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { SyncLogRow } from '@components/mobile/SyncLogRow/SyncLogRow';
import { syncFreshness } from '@components/mobile/AccountCard/AccountCard';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import {
  ACCOUNT_SYNC_STATUS_LABEL,
  ACCOUNT_SYNC_STATUS_VARIANT,
  ACCOUNT_TYPES,
  ACCOUNT_TYPE_LABEL,
  SOURCE_KIND_LABEL,
  SYNC_RUN_STATUS_LABEL,
} from '@lib/labels';
import { fieldError, toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import {
  useAccount,
  useAccountSyncLogs,
  useDeleteAccount,
  useSyncAccount,
  useUpdateAccount,
} from '@hooks/useAccounts';
import type { AccountType } from '@api/types';

/**
 * One account — rename, sync, sync history, disconnect, and the way in to an
 * import.
 *
 * **Sync is not a promise that resolves to success.** `POST .../sync` answers
 * **200** with the `sync_log` row it wrote, and a run that could not reach the
 * source ends `FAILED` with `errorMessage` set while one that could not accept
 * every row ends `PARTIAL_FAILURE` — neither is an HTTP error, because the
 * request itself succeeded in recording what happened. This screen therefore
 * reads `status` off the returned log rather than treating a settled mutation
 * as "synced". The 409 is the separate case: the account is already `SYNCING`,
 * or no source is registered for its kind, and the two are told apart by the
 * server's `detail` rather than by the slug.
 *
 * **Disconnecting says what it does.** `DELETE /v1/accounts/{id}` removes the
 * account *and every transaction stored against it*, and it is the only way a
 * synced transaction is ever deleted. The confirmation says so in those words.
 */
export default function AccountDetailScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const params = useLocalSearchParams<{ id?: string }>();
  // Reachable from a `trove://` deep link as well as from the accounts list,
  // so the id is untrusted input: anything that is not an id the contract
  // could have issued leaves every query idle and renders the state below
  // instead of becoming part of a request path.
  const routeId = typeof params.id === 'string' ? params.id : '';
  const id = isUuid(routeId) ? routeId : '';

  const account = useAccount(id);
  const logs = useAccountSyncLogs(id);
  const update = useUpdateAccount(id);
  const sync = useSyncAccount();
  const remove = useDeleteAccount();

  const [displayName, setDisplayName] = useState<string | null>(null);
  const [institution, setInstitution] = useState<string | null>(null);
  const [accountType, setAccountType] = useState<AccountType | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const updateFailure =
    update.error === null || update.error === undefined
      ? null
      : toApiFailure(update.error, 'Could not save that change.');
  const syncFailure =
    sync.error === null || sync.error === undefined
      ? null
      : toApiFailure(sync.error, 'Could not start a sync.');
  const deleteFailure =
    remove.error === null || remove.error === undefined
      ? null
      : toApiFailure(remove.error, 'Could not disconnect this account.');

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="account-detail-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="account-detail-back"
        />

        {id === '' ? (
          <Text
            accessibilityRole="alert"
            style={[styles.error, { color: theme.red }]}
            testID="account-detail-invalid-id"
          >
            That is not an account id, so there is nothing to show here.
          </Text>
        ) : null}

        <ScreenState
          isLoading={account.isLoading}
          error={account.error}
          fallbackMessage="Could not load this account."
          onRetry={() => void account.refetch()}
          testID="account-detail"
        >
          {account.data === undefined ? null : (
            <View style={styles.body}>
              <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
                <MnEyebrow>{SOURCE_KIND_LABEL[account.data.sourceKind]}</MnEyebrow>
                <Text style={[styles.title, { color: theme.ink }]}>{account.data.displayName}</Text>
                <Text style={[styles.meta, { color: theme.ink3 }]}>
                  {ACCOUNT_TYPE_LABEL[account.data.accountType]} · {account.data.currency}
                </Text>
                <MnPill
                  label={ACCOUNT_SYNC_STATUS_LABEL[account.data.syncStatus]}
                  variant={ACCOUNT_SYNC_STATUS_VARIANT[account.data.syncStatus]}
                  testID="account-detail-status"
                />
                <Text
                  style={[styles.meta, { color: theme.ink3 }]}
                  accessibilityLabel={syncFreshness(account.data, format).label}
                  testID="account-detail-synced"
                >
                  {syncFreshness(account.data, format).text}
                </Text>
                {account.data.errorMessage === null || account.data.errorMessage === '' ? null : (
                  <Text
                    accessibilityRole="alert"
                    style={[styles.error, { color: theme.red }]}
                    testID="account-detail-error-message"
                  >
                    {account.data.errorMessage}
                  </Text>
                )}
              </MnCard>

              <MnBtn
                label={account.data.syncStatus === 'SYNCING' ? 'Syncing…' : 'Sync now'}
                variant="secondary"
                fullWidth
                loading={sync.isPending}
                disabled={account.data.syncStatus === 'SYNCING'}
                onPress={() => sync.mutate(id)}
                testID="account-sync"
              />
              {sync.data === undefined ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[
                    styles.meta,
                    { color: sync.data.status === 'SUCCESS' ? theme.sage : theme.amber },
                  ]}
                  testID="account-sync-outcome"
                >
                  {SYNC_RUN_STATUS_LABEL[sync.data.status]} ·{' '}
                  {format.number(sync.data.transactionsNew)} new ·{' '}
                  {format.number(sync.data.transactionsFailed)} refused
                  {sync.data.errorMessage === null ? '' : ` · ${sync.data.errorMessage}`}
                </Text>
              )}
              {syncFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="account-sync-error"
                >
                  {syncFailure.status === 409
                    ? `This account cannot sync right now: ${syncFailure.message}`
                    : failureMessage(sync.error, syncFailure)}
                </Text>
              )}

              {/* The per-account ledger. `listTransactions` declares an
                  `accountId` filter and this is the affordance that reaches
                  it: the ledger screen reads the parameter off its own route
                  and passes it straight into the query, so the rows come from
                  the server filtered rather than from a page filtered here. */}
              <MnBtn
                label="View this account's transactions"
                variant="ghost"
                fullWidth
                onPress={() =>
                  router.push({ pathname: '/(tabs)/transactions', params: { accountId: id } })
                }
                testID="account-to-transactions"
              />

              <MnBtn
                label="Import a statement file"
                variant="ghost"
                fullWidth
                onPress={() => router.push('/(tabs)/transactions/import/new')}
                testID="account-to-import"
              />

              <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
                <MnEyebrow>Rename</MnEyebrow>
                <Labelled
                  label="Name"
                  value={displayName ?? account.data.displayName}
                  onChangeText={setDisplayName}
                  error={updateFailure === null ? null : fieldError(updateFailure, 'displayName')}
                  testID="account-edit-name"
                />
                <Labelled
                  label="Institution"
                  value={institution ?? account.data.institution ?? ''}
                  onChangeText={setInstitution}
                  error={updateFailure === null ? null : fieldError(updateFailure, 'institution')}
                  testID="account-edit-institution"
                />
                <Text style={[styles.label, { color: theme.ink2 }]}>Type</Text>
                <View style={styles.chips}>
                  {ACCOUNT_TYPES.map((value) => {
                    const selected = (accountType ?? account.data.accountType) === value;
                    return (
                      <TouchableOpacity
                        key={value}
                        onPress={() => setAccountType(value)}
                        accessibilityRole="radio"
                        accessibilityLabel={ACCOUNT_TYPE_LABEL[value]}
                        accessibilityState={{ selected }}
                        style={styles.chip}
                        testID={`account-edit-type-${value}`}
                      >
                        <MnPill
                          label={ACCOUNT_TYPE_LABEL[value]}
                          variant={selected ? 'sage' : 'muted'}
                        />
                      </TouchableOpacity>
                    );
                  })}
                </View>
                <Text style={[styles.hint, { color: theme.ink3 }]}>
                  The source and the currency cannot be changed — every transaction already stored
                  here was written in that currency.
                </Text>
                {updateFailure === null ? null : (
                  <Text
                    accessibilityRole="alert"
                    style={[styles.error, { color: theme.red }]}
                    testID="account-edit-error"
                  >
                    {failureMessage(update.error, updateFailure)}
                  </Text>
                )}
                <MnBtn
                  label="Save"
                  fullWidth
                  loading={update.isPending}
                  onPress={() =>
                    update.mutate({
                      displayName: displayName ?? account.data.displayName,
                      institution:
                        institution === null
                          ? account.data.institution
                          : institution.trim() === ''
                            ? null
                            : institution.trim(),
                      accountType: accountType ?? account.data.accountType,
                    })
                  }
                  testID="account-edit-submit"
                />
              </MnCard>

              <MnEyebrow>Sync history</MnEyebrow>
              <ScreenState
                isLoading={logs.isLoading}
                error={logs.error}
                isEmpty={!logs.isLoading && logs.error === null && (logs.data ?? []).length === 0}
                emptyMessage="This account has not been synced yet."
                fallbackMessage="Could not load the sync history."
                onRetry={() => void logs.refetch()}
                testID="account-sync-logs"
              >
                <View testID="account-sync-log-list">
                  {(logs.data ?? []).map((log) => (
                    <SyncLogRow key={log.id} log={log} />
                  ))}
                </View>
              </ScreenState>

              {deleteFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="account-delete-error"
                >
                  {failureMessage(remove.error, deleteFailure)}
                </Text>
              )}

              {confirmingDelete ? (
                <View style={styles.confirm}>
                  <Text
                    style={[styles.meta, { color: theme.ink }]}
                    testID="account-delete-confirm-copy"
                  >
                    Disconnecting this account deletes it and every transaction stored against it.
                    Those transactions cannot be recovered.
                  </Text>
                  <View style={styles.pair}>
                    <MnBtn
                      label="Disconnect and delete"
                      variant="danger"
                      style={styles.grow}
                      loading={remove.isPending}
                      onPress={() => remove.mutate(id, { onSuccess: () => router.back() })}
                      testID="account-delete-confirm"
                    />
                    <MnBtn
                      label="Keep it"
                      variant="ghost"
                      style={styles.grow}
                      onPress={() => setConfirmingDelete(false)}
                      testID="account-delete-cancel"
                    />
                  </View>
                </View>
              ) : (
                <MnBtn
                  label="Disconnect account"
                  variant="danger"
                  fullWidth
                  onPress={() => setConfirmingDelete(true)}
                  testID="account-delete"
                />
              )}
            </View>
          )}
        </ScreenState>
      </ScrollView>
    </SafeAreaView>
  );
}

function Labelled({
  label,
  value,
  onChangeText,
  error,
  testID,
}: {
  label: string;
  value: string;
  onChangeText: (next: string) => void;
  error: string | null;
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
  body: { gap: Space.s3 },
  card: { gap: Space.s2 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  hint: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  field: { gap: Space.s1 },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  input: {
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: { minHeight: 44, justifyContent: 'center' },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  confirm: { gap: Space.s2 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
