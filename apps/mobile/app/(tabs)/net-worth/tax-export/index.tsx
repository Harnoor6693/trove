import React, { useEffect, useRef, useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure, type ApiFailure } from '@api/errors';
import { useUserFormat } from '@lib/useUserFormat';
import {
  isTerminalTaxExportStatus,
  useCreateTaxExportRun,
  useDeleteTaxExportRun,
  useShareTaxExportRun,
  useTaxExportRun,
  useTaxExportRunFile,
  useTaxExportRuns,
} from '@hooks/useTaxExport';
import type { TaxExportFile } from '@api/taxexport';
import type { TaxExportFormat, TaxExportRunResponse } from '@api/types';
import { keyboardScrollProps } from '@components/ui/FormScroll/FormScroll';

const CURRENT_YEAR = new Date().getFullYear();
const FORMATS: TaxExportFormat[] = ['CSV', 'PDF'];

const STATUS_LABEL: Record<TaxExportRunResponse['status'], string> = {
  PENDING: 'Queued',
  GENERATING: 'Generating…',
  COMPLETE: 'Ready',
  FAILED: 'Failed',
};

const STATUS_VARIANT: Record<TaxExportRunResponse['status'], 'muted' | 'sage' | 'red'> = {
  PENDING: 'muted',
  GENERATING: 'muted',
  COMPLETE: 'sage',
  FAILED: 'red',
};

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

/**
 * `/v1/tax-export/runs*` — an EXPORT, not an engine. Every figure this screen
 * shows (`taxYear`, `format`, `fileSizeBytes`) is metadata about a file the
 * member's own ledger produced; nothing here computes or displays a tax
 * figure of any kind.
 */
export default function TaxExportRunsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const [creating, setCreating] = useState(false);
  const { data, isLoading, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useTaxExportRuns();

  const rows = data?.pages.flatMap((page) => page.data) ?? [];

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="tax-export-screen"
    >
      <View style={styles.header}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="tax-export-back"
        />
        <MnEyebrow>Tax export</MnEyebrow>
        <MnBtn
          label={creating ? 'Cancel' : 'New export'}
          variant="secondary"
          onPress={() => setCreating((v) => !v)}
          testID="tax-export-new-toggle"
        />
      </View>

      <MnBtn
        label="Category tax lines"
        variant="ghost"
        onPress={() => router.push('/(tabs)/net-worth/tax-export/lines')}
        style={styles.navBtn}
        testID="tax-export-nav-lines"
      />

      <Text style={[styles.note, { color: theme.ink3 }]}>
        Your export carries your own amounts and category labels. It is a record of your household’s
        ledger for the year you pick — it does not calculate anything owed.
      </Text>

      {creating ? (
        <View style={styles.formWrap}>
          <CreateRunForm onDone={() => setCreating(false)} testID="tax-export-create-form" />
        </View>
      ) : null}

      <ScreenState
        isLoading={isLoading}
        error={error}
        isEmpty={rows.length === 0}
        emptyMessage="No exports yet."
        fallbackMessage="Could not load your export runs."
        onRetry={() => void refetch()}
        testID="tax-export-runs"
      >
        <FlatList
          {...keyboardScrollProps}
          data={rows}
          keyExtractor={(run) => run.id}
          contentContainerStyle={styles.list}
          onEndReachedThreshold={0.4}
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          renderItem={({ item }) => <TaxExportRunRow run={item} formatDate={format.date} />}
          ListFooterComponent={
            isFetchingNextPage ? (
              <Text style={[styles.footer, { color: theme.ink3 }]} testID="tax-export-loading-more">
                Loading more…
              </Text>
            ) : undefined
          }
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function CreateRunForm({ onDone, testID }: { onDone: () => void; testID: string }) {
  const theme = useTheme();
  const create = useCreateTaxExportRun();
  const [taxYear, setTaxYear] = useState(String(CURRENT_YEAR));
  const [taxFormat, setTaxFormat] = useState<TaxExportFormat>('CSV');

  const failure: ApiFailure | null =
    create.error == null ? null : toApiFailure(create.error, 'Could not start that export.');

  const submit = () => {
    const year = Number(taxYear.trim());
    if (!Number.isInteger(year)) return;
    create.mutate({ taxYear: year, format: taxFormat }, { onSuccess: onDone });
  };

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.label, { color: theme.ink2 }]}>Tax year</Text>
      <TextInput
        value={taxYear}
        onChangeText={setTaxYear}
        keyboardType="number-pad"
        accessibilityLabel="Tax year"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-year`}
      />

      <Text style={[styles.label, { color: theme.ink2 }]}>Format</Text>
      <View style={styles.choices}>
        {FORMATS.map((option) => (
          <TouchableOpacity
            key={option}
            onPress={() => setTaxFormat(option)}
            accessibilityRole="radio"
            accessibilityState={{ selected: taxFormat === option }}
            accessibilityLabel={option}
            style={[
              styles.chip,
              {
                borderColor: taxFormat === option ? theme.sage : theme.line,
                backgroundColor: taxFormat === option ? theme.sageSoft : 'transparent',
              },
            ]}
            testID={`${testID}-format-${option}`}
          >
            <Text
              style={[styles.chipText, { color: taxFormat === option ? theme.sage : theme.ink2 }]}
            >
              {option}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      <Text style={[styles.note, { color: theme.ink3 }]}>
        This covers your household’s whole ledger for {taxYear || 'that year'}, 1 January to 31
        December. Your household mates’ spending will be in it — it is your export alone to keep or
        delete.
      </Text>

      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failureMessage(create.error, failure)}
        </Text>
      )}

      <View style={styles.pair}>
        <MnBtn
          label="Start export"
          style={styles.grow}
          loading={create.isPending}
          disabled={taxYear.trim() === ''}
          onPress={submit}
          testID={`${testID}-submit`}
        />
        <MnBtn
          label="Cancel"
          variant="ghost"
          style={styles.grow}
          onPress={onDone}
          testID={`${testID}-cancel`}
        />
      </View>
    </MnCard>
  );
}

function TaxExportRunRow({
  run,
  formatDate,
}: {
  run: TaxExportRunResponse;
  formatDate: (iso: string | null | undefined, style?: 'short' | 'medium' | 'long') => string;
}) {
  const theme = useTheme();
  const listDone = isTerminalTaxExportStatus(run.status);
  const polled = useTaxExportRun(run.id, !listDone);
  // A terminal status is final, so a list row that already says COMPLETE or
  // FAILED wins over the polled row — polling is off by then, and the polled
  // cache may still hold the PENDING row the create mutation seeded.
  const current = listDone ? run : (polled.data ?? run);
  const remove = useDeleteTaxExportRun();
  const fileRequest = useTaxExportRunFile();
  const share = useShareTaxExportRun();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [viewResult, setViewResult] = useState<TaxExportFile | null>(null);

  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this export.');
  const viewFailure =
    fileRequest.error == null
      ? null
      : toApiFailure(fileRequest.error, 'Could not open that export.');
  const shareFailure =
    share.error == null ? null : toApiFailure(share.error, 'Could not save or share that export.');
  const stillGenerating =
    (viewFailure !== null && viewFailure.status === 409) ||
    (shareFailure !== null && shareFailure.status === 409);

  const onView = () => {
    setViewResult(null);
    fileRequest.mutate(
      { id: run.id, format: current.format },
      { onSuccess: (file) => setViewResult(file) }
    );
  };

  // The exported ledger is the member's whole tax year in cleartext, held in
  // this mutation's cache and in `viewResult` component state. Neither should
  // outlive the preview being on screen: dismissing it clears both, and an
  // unmount (navigating away with the preview still open) does the same.
  const onDismissPreview = () => {
    setViewResult(null);
    fileRequest.reset();
  };

  const fileRequestRef = useRef(fileRequest);
  useEffect(() => {
    fileRequestRef.current = fileRequest;
  });
  useEffect(() => {
    return () => {
      fileRequestRef.current.reset();
    };
  }, []);

  return (
    <MnCard style={styles.card} testID={`tax-export-run-${run.id}`}>
      <View style={styles.rowHeader}>
        <Text style={[styles.name, { color: theme.ink }]}>
          {current.taxYear} · {current.format}
        </Text>
        <MnPill label={STATUS_LABEL[current.status]} variant={STATUS_VARIANT[current.status]} />
      </View>
      <Text style={[styles.meta, { color: theme.ink3 }]}>
        Started {formatDate(current.createdAt, 'medium')}
        {current.fileSizeBytes !== null ? ` · ${formatBytes(current.fileSizeBytes)}` : ''}
      </Text>
      {current.status === 'FAILED' && current.errorMessage !== null && (
        <Text
          style={[styles.error, { color: theme.red }]}
          accessibilityRole="alert"
          testID={`tax-export-run-${run.id}-failed`}
        >
          {current.errorMessage}
        </Text>
      )}

      {current.status === 'COMPLETE' && (
        <MnBtn
          label="View"
          variant="secondary"
          loading={fileRequest.isPending}
          onPress={onView}
          testID={`tax-export-run-${run.id}-view`}
        />
      )}
      {current.status === 'COMPLETE' && (
        <MnBtn
          label="Save or share"
          variant="secondary"
          loading={share.isPending}
          onPress={() =>
            share.mutate({ id: run.id, format: current.format, taxYear: current.taxYear })
          }
          testID={`tax-export-share-${run.id}`}
        />
      )}
      {(current.status === 'PENDING' || current.status === 'GENERATING') && (
        <Text
          style={[styles.note, { color: theme.ink3 }]}
          testID={`tax-export-run-${run.id}-generating`}
        >
          Still generating — check back shortly.
        </Text>
      )}

      {stillGenerating && (
        <Text
          style={[styles.note, { color: theme.ink3 }]}
          testID={`tax-export-run-${run.id}-not-ready`}
        >
          Still generating — check back shortly.
        </Text>
      )}
      {viewFailure !== null && !stillGenerating && (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`tax-export-run-${run.id}-view-error`}
        >
          {failureMessage(fileRequest.error, viewFailure)}
        </Text>
      )}
      {shareFailure !== null && shareFailure.status !== 409 && (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`tax-export-run-${run.id}-share-error`}
        >
          {failureMessage(share.error, shareFailure)}
        </Text>
      )}

      {viewResult !== null && viewResult.contentType === 'text/csv' && (
        <View
          style={[styles.preview, { borderColor: theme.line, backgroundColor: theme.paperDim }]}
          testID={`tax-export-run-${run.id}-preview`}
        >
          <Text
            selectable
            style={[styles.previewText, { color: theme.ink }]}
            accessibilityLabel="Export preview"
          >
            {viewResult.text}
          </Text>
        </View>
      )}
      {viewResult !== null && viewResult.contentType === 'application/pdf' && (
        <Text
          style={[styles.note, { color: theme.ink3 }]}
          testID={`tax-export-run-${run.id}-pdf-info`}
        >
          Your PDF export is ready ({formatBytes(viewResult.byteLength)}). This app can’t preview a
          PDF — use Save or share to open it in another app or keep a copy.
        </Text>
      )}
      {viewResult !== null && (
        <MnBtn
          label="Dismiss"
          variant="ghost"
          onPress={onDismissPreview}
          testID={`tax-export-run-${run.id}-dismiss`}
        />
      )}

      <View style={styles.pair}>
        {confirmingDelete ? (
          <MnBtn
            label="Confirm delete"
            variant="danger"
            style={styles.grow}
            loading={remove.isPending}
            onPress={() => remove.mutate(run.id)}
            testID={`tax-export-run-${run.id}-delete-confirm`}
          />
        ) : (
          <MnBtn
            label="Delete"
            variant="danger"
            style={styles.grow}
            onPress={() => setConfirmingDelete(true)}
            testID={`tax-export-run-${run.id}-delete`}
          />
        )}
      </View>
      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`tax-export-run-${run.id}-delete-error`}
        >
          {failureMessage(remove.error, deleteFailure)}
        </Text>
      )}
    </MnCard>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: { padding: Space.s4, gap: Space.s2 },
  navBtn: { marginHorizontal: Space.s4, alignSelf: 'flex-start' },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginHorizontal: Space.s4 },
  list: { paddingHorizontal: Space.s4, gap: Space.s3, paddingTop: Space.s3 },
  card: { gap: Space.s2, marginBottom: Space.s3 },
  formWrap: { marginHorizontal: Space.s4 },
  rowHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  name: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  input: {
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: {
    paddingHorizontal: Space.s3,
    paddingVertical: Space.s2,
    borderRadius: Radius.md,
    borderWidth: 1,
    minHeight: 44,
    justifyContent: 'center',
  },
  chipText: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  footer: {
    textAlign: 'center',
    paddingVertical: Space.s4,
    fontFamily: FontFamily.body,
    fontSize: FontSize.sm,
  },
  preview: { borderWidth: 1, borderRadius: Radius.md, padding: Space.s3, maxHeight: 200 },
  previewText: { fontFamily: FontFamily.mono, fontSize: FontSize.xs },
});
