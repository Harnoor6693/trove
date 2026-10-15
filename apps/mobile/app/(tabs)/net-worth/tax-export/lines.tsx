import React, { useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure, type ApiFailure } from '@api/errors';
import { useSetTaxLine, useTaxLines } from '@hooks/useTaxExport';
import type { TaxLineMappingResponse } from '@api/types';

/**
 * `/v1/tax-export/lines*` — the caller's own effective category-to-tax-line
 * map. Free-text labels a human copies off a form; nothing here is
 * interpreted or computed. Overriding a line writes only the CALLER's own
 * row — a household mate's export is unaffected.
 */
export default function TaxLinesScreen() {
  const theme = useTheme();
  const router = useRouter();
  const lines = useTaxLines();
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="tax-lines-screen">
      <View style={styles.header}>
        <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="tax-lines-back" />
        <MnEyebrow>Category tax lines</MnEyebrow>
      </View>
      <Text style={[styles.note, { color: theme.ink3 }]}>
        The line code and label are yours to fill in from a form — Trove stores them as text and
        never calculates anything from them. Overriding one here only changes your own export.
      </Text>

      <ScreenState
        isLoading={lines.isLoading}
        error={lines.error}
        isEmpty={!lines.isLoading && lines.error == null && (lines.data ?? []).length === 0}
        emptyMessage="No categories to map yet."
        fallbackMessage="Could not load your tax lines."
        onRetry={() => void lines.refetch()}
        testID="tax-lines"
      >
        <FlatList
          data={lines.data ?? []}
          keyExtractor={(line) => line.categoryId}
          contentContainerStyle={styles.list}
          renderItem={({ item }) =>
            editingId === item.categoryId ? (
              <TaxLineForm
                line={item}
                onDone={() => setEditingId(null)}
                testID={`tax-line-form-${item.categoryId}`}
              />
            ) : (
              <TaxLineRow line={item} onEdit={() => setEditingId(item.categoryId)} />
            )
          }
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function TaxLineRow({ line, onEdit }: { line: TaxLineMappingResponse; onEdit: () => void }) {
  const theme = useTheme();
  const hasLine = line.lineCode !== '';

  return (
    <MnCard style={styles.card} testID={`tax-line-row-${line.categoryId}`}>
      <View style={styles.rowHeader}>
        <Text style={[styles.name, { color: theme.ink }]}>{line.categoryName}</Text>
        {line.overridden && <MnPill label="Your override" variant="sage" />}
      </View>
      {hasLine ? (
        <Text style={[styles.lineText, { color: theme.ink2 }]}>
          {line.lineCode} — {line.lineLabel}
          {line.deductible ? ' (deductible)' : ''}
        </Text>
      ) : (
        <Text style={[styles.lineText, { color: theme.ink3 }]}>No line mapped yet.</Text>
      )}
      <MnBtn
        label="Edit"
        variant="secondary"
        onPress={onEdit}
        testID={`tax-line-edit-${line.categoryId}`}
      />
    </MnCard>
  );
}

function TaxLineForm({
  line,
  onDone,
  testID,
}: {
  line: TaxLineMappingResponse;
  onDone: () => void;
  testID: string;
}) {
  const theme = useTheme();
  const setLine = useSetTaxLine();
  const [lineCode, setLineCode] = useState(line.lineCode);
  const [lineLabel, setLineLabel] = useState(line.lineLabel);
  const [deductible, setDeductible] = useState(line.deductible);

  const failure: ApiFailure | null =
    setLine.error == null ? null : toApiFailure(setLine.error, 'Could not save that line.');

  const submit = () => {
    setLine.mutate(
      {
        categoryId: line.categoryId,
        body: {
          jurisdiction: line.jurisdiction,
          lineCode: lineCode.trim(),
          lineLabel: lineLabel.trim(),
          deductible,
        },
      },
      { onSuccess: onDone }
    );
  };

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.name, { color: theme.ink }]}>{line.categoryName}</Text>

      <Text style={[styles.label, { color: theme.ink2 }]}>Line code</Text>
      <TextInput
        value={lineCode}
        onChangeText={setLineCode}
        accessibilityLabel="Tax line code"
        placeholder="e.g. 8810"
        placeholderTextColor={theme.ink3}
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-code`}
      />

      <Text style={[styles.label, { color: theme.ink2 }]}>Line label</Text>
      <TextInput
        value={lineLabel}
        onChangeText={setLineLabel}
        accessibilityLabel="Tax line label"
        placeholder="e.g. T2125 line 8810 — Office expenses"
        placeholderTextColor={theme.ink3}
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-label`}
      />

      <MnToggle
        label="Deductible"
        value={deductible}
        onToggle={setDeductible}
        testID={`${testID}-deductible`}
      />

      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failureMessage(setLine.error, failure)}
        </Text>
      )}

      <View style={styles.pair}>
        <MnBtn
          label="Save"
          style={styles.grow}
          loading={setLine.isPending}
          disabled={lineCode.trim() === '' || lineLabel.trim() === ''}
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

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: { padding: Space.s4, gap: Space.s2 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginHorizontal: Space.s4 },
  list: { paddingHorizontal: Space.s4, gap: Space.s3, paddingTop: Space.s3 },
  card: { gap: Space.s2, marginBottom: Space.s3 },
  rowHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  name: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  lineText: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  input: {
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
