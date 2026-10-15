import React, { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import { IMPORTABLE_SOURCE_KINDS, SOURCE_KIND_LABEL } from '@lib/labels';
import { useAccounts } from '@hooks/useAccounts';
import { useCreateImport } from '@hooks/useImports';
import { pickImportFile } from '@lib/importFilePicker';

/**
 * Step one of an import: choose the account the rows belong to, choose a file,
 * or pick up a batch that already exists.
 *
 * **The account is part of the upload, not an afterthought.**
 * `POST /v1/imports` states that `accountId` is **required for CSV** — a CSV
 * carries no account identity at all — and optional for OFX, where the file's
 * own `ACCTID` resolves it; it also states that the file and the account must
 * be the same kind, so the picker offers `CSV` and `OFX` accounts and nothing
 * else. A member with none is told where to make one (the accounts screen
 * creates both kinds) rather than being handed an upload button that could
 * only ever answer 400 `validation-error`.
 *
 * The one deliberate escape is "From the file" — the OFX-only path where the
 * server resolves the account from `ACCTID` itself. It is an explicit choice
 * rather than the default, because choosing it for a CSV is exactly the 400
 * this picker exists to prevent.
 *
 * "Choose a file" opens the system document picker (`src/lib/importFilePicker.ts`)
 * and uploads the CSV or OFX it returns; a canceled pick does nothing, and any
 * other kind of file is refused on screen before it is uploaded. The screen
 * also offers the route the contract explicitly supports for a batch started
 * elsewhere: open an existing batch by id and carry on from the preview. The
 * batch belongs to the household, so one uploaded on the web app is the same
 * batch here.
 *
 * A pasted batch id is untrusted input — the same screen is reachable through
 * the `trove` URL scheme — so it is checked against the id shape before it
 * becomes a route, and `src/api/paths.ts` encodes it again at the request.
 *
 * A 413 is its own state: the upload exceeded one of three ceilings, and the
 * member needs to be told to split the file rather than to "try again".
 */
export default function NewImportScreen() {
  const theme = useTheme();
  const router = useRouter();
  const accounts = useAccounts();
  const create = useCreateImport();
  const [batchId, setBatchId] = useState('');
  const [pickerMessage, setPickerMessage] = useState<string | null>(null);
  /** `null` = nothing chosen yet; `''` = the explicit "let the OFX file decide". */
  const [accountId, setAccountId] = useState<string | null>(null);

  const importable = useMemo(
    () => (accounts.data ?? []).filter((a) => IMPORTABLE_SOURCE_KINDS.includes(a.sourceKind)),
    [accounts.data]
  );

  const failure =
    create.error === null || create.error === undefined
      ? null
      : toApiFailure(create.error, 'Could not upload that file.');

  const choose = async () => {
    if (accountId === null) return;
    setPickerMessage(null);
    let file;
    try {
      file = await pickImportFile();
    } catch (err) {
      setPickerMessage(err instanceof Error ? err.message : 'Could not open that file.');
      return;
    }
    if (file === null) return;
    create.mutate(
      // The empty selection is the OFX escape: the field is left out entirely
      // rather than sent blank, because the operation reads absence as
      // "resolve it from the file" and a blank string as a bad id.
      accountId === '' ? { file } : { file, accountId },
      { onSuccess: (preview) => router.push(`/(tabs)/transactions/import/${preview.id}`) }
    );
  };

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="new-import-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="new-import-back"
        />
        <MnEyebrow>Import a statement</MnEyebrow>

        <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
          <Text style={[styles.body, { color: theme.ink2 }]}>
            A CSV or OFX file is read and previewed first. Nothing enters your ledger until you
            commit the batch.
          </Text>

          <Text style={[styles.label, { color: theme.ink2 }]}>Import into</Text>
          <ScreenState
            isLoading={accounts.isLoading}
            error={accounts.error}
            isEmpty={!accounts.isLoading && accounts.error === null && importable.length === 0}
            emptyMessage="No account here can take a statement file. Add a CSV or OFX account first — a file can only be imported into an account of its own kind."
            fallbackMessage="Could not load your accounts."
            onRetry={() => void accounts.refetch()}
            testID="new-import-accounts"
          >
            <View style={styles.chips} testID="new-import-account-picker">
              {importable.map((account) => (
                <TouchableOpacity
                  key={account.id}
                  onPress={() => setAccountId(account.id)}
                  accessibilityRole="radio"
                  accessibilityLabel={`${account.displayName}, ${SOURCE_KIND_LABEL[account.sourceKind]}`}
                  accessibilityState={{ selected: accountId === account.id }}
                  style={styles.chip}
                  testID={`new-import-account-${account.id}`}
                >
                  <MnPill
                    label={`${account.displayName} · ${SOURCE_KIND_LABEL[account.sourceKind]}`}
                    variant={accountId === account.id ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
              <TouchableOpacity
                onPress={() => setAccountId('')}
                accessibilityRole="radio"
                accessibilityLabel="From the file, OFX only"
                accessibilityState={{ selected: accountId === '' }}
                style={styles.chip}
                testID="new-import-account-from-file"
              >
                <MnPill
                  label="From the file (OFX only)"
                  variant={accountId === '' ? 'sage' : 'muted'}
                />
              </TouchableOpacity>
            </View>
          </ScreenState>

          {importable.length === 0 ? (
            <MnBtn
              label="Add an account for imports"
              variant="ghost"
              fullWidth
              onPress={() => router.push('/(tabs)/transactions/accounts')}
              testID="new-import-to-accounts"
            />
          ) : null}

          <MnBtn
            label="Choose a file"
            variant="secondary"
            fullWidth
            loading={create.isPending}
            disabled={accountId === null}
            onPress={() => void choose()}
            testID="new-import-choose"
          />
          {accountId === null ? (
            <Text style={[styles.body, { color: theme.ink3 }]} testID="new-import-account-required">
              Choose where the rows go first. A CSV file must name its account; an OFX file can
              carry its own.
            </Text>
          ) : null}
          {pickerMessage === null ? null : (
            <Text
              accessibilityRole="alert"
              style={[styles.body, { color: theme.amber }]}
              testID="new-import-picker-refused"
            >
              {pickerMessage}
            </Text>
          )}
          {failure === null ? null : (
            <Text
              accessibilityRole="alert"
              style={[styles.error, { color: theme.red }]}
              testID="new-import-error"
            >
              {failure.status === 413
                ? 'That file is too large to upload. Split it into smaller files, or commit or undo an earlier batch to free your allowance.'
                : failureMessage(create.error, failure)}
            </Text>
          )}
        </MnCard>

        <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
          <Text style={[styles.label, { color: theme.ink2 }]} nativeID="new-import-batch-label">
            Open an existing batch
          </Text>
          <Text style={[styles.body, { color: theme.ink3 }]}>
            Paste the batch id of an upload started on another device to review, commit or undo it
            here.
          </Text>
          <TextInput
            value={batchId}
            onChangeText={setBatchId}
            autoCapitalize="none"
            autoCorrect={false}
            accessibilityLabel="Import batch id"
            accessibilityLabelledBy="new-import-batch-label"
            style={[
              styles.input,
              { color: theme.ink, backgroundColor: theme.paper, borderColor: theme.line },
            ]}
            testID="new-import-batch-id"
          />
          {batchId.trim() === '' || isUuid(batchId.trim()) ? null : (
            <Text
              accessibilityRole="alert"
              style={[styles.error, { color: theme.red }]}
              testID="new-import-batch-id-error"
            >
              That is not a batch id. Copy the whole id from the upload you started.
            </Text>
          )}
          <MnBtn
            label="Open batch"
            fullWidth
            // Checked here as well as encoded at the request: a value that is
            // not an id has no business becoming part of a path.
            disabled={!isUuid(batchId.trim())}
            onPress={() => router.push(`/(tabs)/transactions/import/${batchId.trim()}`)}
            testID="new-import-open"
          />
        </MnCard>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, gap: Space.s3, paddingBottom: Space.s12 },
  card: { gap: Space.s2 },
  body: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
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
