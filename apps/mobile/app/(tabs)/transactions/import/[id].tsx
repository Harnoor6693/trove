import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ImportPreviewSummary } from '@components/mobile/ImportPreviewSummary/ImportPreviewSummary';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { IMPORT_VERDICT_LABEL, IMPORT_VERDICT_VARIANT } from '@lib/labels';
import { toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import { useCommitImport, useImportPreview, useUndoImport } from '@hooks/useImports';
import type { CsvColumnMappingRequest } from '@api/types';

/**
 * One import batch: review, correct, commit, undo.
 *
 * **Nothing has been written yet.** `POST /v1/imports` created the batch in
 * status `PREVIEW` and inserted no transaction; the counts and the reasons on
 * this screen are what committing *would* do, and they are shown before the
 * commit button rather than after it.
 *
 * **The mapping correction is the commit body.** There is no "update the
 * mapping" route on purpose: the member adjusts what the sniffer guessed — a
 * column, or the date format it could not choose between — and the adjustment
 * travels with `POST /v1/imports/{id}/commit`. Fields the body omits keep their
 * detected values, so this screen sends only what the member actually changed
 * and omits the rest.
 *
 * **`dateFormatCandidates` is a real decision.** It is populated only when the
 * file's dates are genuinely ambiguous between `dd/MM/yyyy` and `MM/dd/yyyy`,
 * and the server will not guess between them. Each candidate is offered with
 * the sample cells and what they would mean, so the member picks on evidence.
 *
 * **Undo appears only once there is something to undo.** The control is shown
 * for a `COMMITTED` batch and for nothing else: a `PREVIEW` batch has written
 * no row, so `DELETE /v1/imports/{id}` could only answer 409 there, and a
 * danger-styled button whose one possible outcome is a refusal is worse than
 * no button. It stays reachable after the wizard is gone because a transaction
 * that carries an `importBatchId` links straight back to this screen.
 */
export default function ImportBatchScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const params = useLocalSearchParams<{ id?: string }>();
  // The id can arrive from a pasted value or a `trove://` deep link, so it is
  // untrusted input: anything that is not an id the contract could have issued
  // becomes an empty id, which leaves every query idle and renders the state
  // below instead of putting the value into a request path.
  const routeId = typeof params.id === 'string' ? params.id : '';
  const id = isUuid(routeId) ? routeId : '';

  const preview = useImportPreview(id);
  const commit = useCommitImport(id);
  const undo = useUndoImport();

  const [dateFormat, setDateFormat] = useState<string | null>(null);
  const [dateColumn, setDateColumn] = useState<string | null>(null);
  const [amountColumn, setAmountColumn] = useState<string | null>(null);
  const [descriptionColumn, setDescriptionColumn] = useState<string | null>(null);
  const [confirmingUndo, setConfirmingUndo] = useState(false);

  const commitFailure =
    commit.error === null || commit.error === undefined
      ? null
      : toApiFailure(commit.error, 'Could not commit this batch.');
  const undoFailure =
    undo.error === null || undo.error === undefined
      ? null
      : toApiFailure(undo.error, 'Could not undo this batch.');

  /**
   * Only the corrections the member made. An untouched field is left out, so
   * the server keeps the value it detected rather than being handed the same
   * value back as if it had been chosen.
   */
  const buildMapping = (): CsvColumnMappingRequest | undefined => {
    const mapping: CsvColumnMappingRequest = {};
    if (dateFormat !== null) mapping.dateFormat = dateFormat;
    if (dateColumn !== null) mapping.dateColumn = dateColumn;
    if (amountColumn !== null) mapping.amountColumn = amountColumn;
    if (descriptionColumn !== null) mapping.descriptionColumn = descriptionColumn;
    return Object.keys(mapping).length === 0 ? undefined : mapping;
  };

  const onCommit = () => {
    const mapping = buildMapping();
    commit.mutate(mapping === undefined ? undefined : { mapping });
  };

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="import-batch-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="import-back" />
        <MnEyebrow>Import batch</MnEyebrow>

        {id === '' ? (
          <Text
            accessibilityRole="alert"
            style={[styles.error, { color: theme.red }]}
            testID="import-invalid-id"
          >
            That is not an import batch id, so there is nothing to open here.
          </Text>
        ) : null}

        <ScreenState
          isLoading={preview.isLoading}
          error={preview.error}
          fallbackMessage="Could not load this import batch."
          onRetry={() => void preview.refetch()}
          testID="import"
        >
          {preview.data === undefined ? null : (
            <View style={styles.stack}>
              <ImportPreviewSummary preview={preview.data} />

              {preview.data.mapping === undefined ? null : (
                <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
                  <MnEyebrow>Detected columns</MnEyebrow>
                  <Text
                    style={[styles.body, { color: theme.ink3 }]}
                    testID="import-mapping-detected"
                  >
                    Date: {preview.data.mapping.dateColumn ?? 'not detected'} · Amount:{' '}
                    {preview.data.mapping.amountColumn ?? 'not detected'} · Description:{' '}
                    {preview.data.mapping.descriptionColumn ?? 'not detected'} · Format:{' '}
                    {preview.data.mapping.dateFormat ?? 'not chosen'}
                  </Text>
                  <MappingField
                    label="Date column"
                    value={dateColumn ?? preview.data.mapping.dateColumn ?? ''}
                    onChangeText={setDateColumn}
                    testID="import-map-date-column"
                  />
                  <MappingField
                    label="Amount column"
                    value={amountColumn ?? preview.data.mapping.amountColumn ?? ''}
                    onChangeText={setAmountColumn}
                    testID="import-map-amount-column"
                  />
                  <MappingField
                    label="Description column"
                    value={descriptionColumn ?? preview.data.mapping.descriptionColumn ?? ''}
                    onChangeText={setDescriptionColumn}
                    testID="import-map-description-column"
                  />
                </MnCard>
              )}

              {preview.data.dateFormatCandidates.length === 0 ? null : (
                <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
                  <MnEyebrow>Which date format is this file?</MnEyebrow>
                  <Text style={[styles.body, { color: theme.ink3 }]}>
                    These dates read differently under each format. Pick the one that matches your
                    statement.
                  </Text>
                  {preview.data.dateFormatCandidates.map((candidate) => (
                    <TouchableOpacity
                      key={candidate.format}
                      onPress={() => setDateFormat(candidate.format)}
                      accessibilityRole="radio"
                      accessibilityLabel={`${candidate.format}, for example ${
                        candidate.sampleValues[0] ?? ''
                      } means ${candidate.interpretedDates[0] ?? ''}`}
                      accessibilityState={{ selected: dateFormat === candidate.format }}
                      style={styles.candidate}
                      testID={`import-date-format-${candidate.format}`}
                    >
                      <MnPill
                        label={candidate.format}
                        variant={dateFormat === candidate.format ? 'sage' : 'muted'}
                      />
                      <Text style={[styles.body, { color: theme.ink2 }]}>
                        {candidate.sampleValues.join(', ')} →{' '}
                        {candidate.interpretedDates.join(', ')}
                      </Text>
                    </TouchableOpacity>
                  ))}
                </MnCard>
              )}

              {preview.data.rows.length === 0 ? null : (
                <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
                  <MnEyebrow>Sample rows</MnEyebrow>
                  {preview.data.rows.map((row) => (
                    <View key={row.ordinal} style={styles.row} testID={`import-row-${row.ordinal}`}>
                      <View style={styles.rowMain}>
                        <Text style={[styles.rowTitle, { color: theme.ink }]} numberOfLines={1}>
                          {row.merchantName ?? row.description ?? 'Unnamed row'}
                        </Text>
                        <Text style={[styles.rowMeta, { color: theme.ink3 }]}>
                          {format.date(row.date, 'medium')}
                        </Text>
                      </View>
                      <MoneyDisplay amount={row.amount} currency={row.currency} size="sm" />
                      <MnPill
                        label={IMPORT_VERDICT_LABEL[row.verdict]}
                        variant={IMPORT_VERDICT_VARIANT[row.verdict]}
                      />
                    </View>
                  ))}
                </MnCard>
              )}

              {commitFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="import-commit-error"
                >
                  {commitFailure.status === 409
                    ? 'This batch is not waiting to be committed — it has already been committed or undone.'
                    : failureMessage(commit.error, commitFailure)}
                </Text>
              )}

              {commit.data === undefined ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.body, { color: theme.sage }]}
                  testID="import-commit-result"
                >
                  Committed · {format.number(commit.data.transactionsNew)} new ·{' '}
                  {format.number(commit.data.transactionsSkipped)} already there
                </Text>
              )}

              {preview.data.status === 'PREVIEW' ? (
                <MnBtn
                  label="Commit this batch"
                  fullWidth
                  loading={commit.isPending}
                  onPress={onCommit}
                  testID="import-commit"
                />
              ) : (
                <Text style={[styles.body, { color: theme.ink3 }]} testID="import-not-committable">
                  This batch is {preview.data.status.toLowerCase()}, so there is nothing left to
                  commit.
                </Text>
              )}

              {/* Undo exists only for a batch that has actually written rows.
                  `DELETE /v1/imports/{id}` answers 409 for anything else, and
                  every fresh upload lands here in `PREVIEW`, so an
                  unconditional danger control would be a guaranteed-failing
                  action on the primary path. A committed batch keeps it — and
                  a transaction that carries an `importBatchId` links back to
                  this screen, which is how undo stays reachable after the
                  wizard is gone. */}
              {preview.data.status !== 'COMMITTED' ? null : undoFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="import-undo-error"
                >
                  {undoFailure.status === 409
                    ? 'There is nothing to undo — this batch was never committed, or it has already been undone.'
                    : failureMessage(undo.error, undoFailure)}
                </Text>
              )}

              {preview.data.status !== 'COMMITTED' ? null : confirmingUndo ? (
                <View style={styles.confirm}>
                  <Text
                    style={[styles.body, { color: theme.ink }]}
                    testID="import-undo-confirm-copy"
                  >
                    Undoing removes the rows this batch added that nobody has edited since. Rows you
                    have changed by hand are kept.
                  </Text>
                  <View style={styles.pair}>
                    <MnBtn
                      label="Undo this import"
                      variant="danger"
                      style={styles.grow}
                      loading={undo.isPending}
                      onPress={() => undo.mutate(id)}
                      testID="import-undo-confirm"
                    />
                    <MnBtn
                      label="Keep it"
                      variant="ghost"
                      style={styles.grow}
                      onPress={() => setConfirmingUndo(false)}
                      testID="import-undo-cancel"
                    />
                  </View>
                </View>
              ) : (
                <MnBtn
                  label="Undo this import"
                  variant="danger"
                  fullWidth
                  onPress={() => setConfirmingUndo(true)}
                  testID="import-undo"
                />
              )}
            </View>
          )}
        </ScreenState>
      </ScrollView>
    </SafeAreaView>
  );
}

function MappingField({
  label,
  value,
  onChangeText,
  testID,
}: {
  label: string;
  value: string;
  onChangeText: (next: string) => void;
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
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, gap: Space.s3, paddingBottom: Space.s12 },
  stack: { gap: Space.s3 },
  body: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  card: { gap: Space.s2 },
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
  candidate: { gap: Space.s1, paddingVertical: Space.s2, minHeight: 44 },
  row: { flexDirection: 'row', alignItems: 'center', gap: Space.s3, paddingVertical: Space.s2 },
  rowMain: { flex: 1 },
  rowTitle: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  rowMeta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  confirm: { gap: Space.s2 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
