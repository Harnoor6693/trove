import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { IMPORT_VERDICT_LABEL, IMPORT_VERDICT_VARIANT } from '@lib/labels';
import type { ImportPreviewResponse } from '@api/types';

/**
 * What committing this batch would do — shown **before** the commit button.
 *
 * `POST /v1/imports` writes nothing; this is the whole point of the preview
 * step, and the counts (`newCount`, `duplicateCount`, `fuzzyDuplicateCount`,
 * `invalidCount`) plus the per-row `reason` for a rejected row are what the
 * member decides on. Every warning the server sent is rendered too — it never
 * quotes file content, so there is nothing to redact.
 */
export interface ImportPreviewSummaryProps {
  preview: ImportPreviewResponse;
  testID?: string;
}

export function ImportPreviewSummary({
  preview,
  testID = 'import-preview-summary',
}: ImportPreviewSummaryProps) {
  const theme = useTheme();
  const format = useUserFormat();

  const counts: Array<{ key: keyof typeof IMPORT_VERDICT_LABEL; value: number }> = [
    { key: 'NEW', value: preview.newCount },
    { key: 'DUPLICATE', value: preview.duplicateCount },
    { key: 'FUZZY_DUPLICATE', value: preview.fuzzyDuplicateCount },
    { key: 'INVALID', value: preview.invalidCount },
  ];

  return (
    <MnCard style={[styles.card, { backgroundColor: theme.card }]} testID={testID}>
      <Text style={[styles.file, { color: theme.ink }]} numberOfLines={1}>
        {preview.originalFilename ?? 'Uploaded file'}
      </Text>
      <Text style={[styles.meta, { color: theme.ink3 }]} testID={`${testID}-rows`}>
        {format.number(preview.rowCount)} rows · {preview.sourceKind} · {preview.status}
      </Text>

      <View style={styles.counts}>
        {counts.map(({ key, value }) => (
          <View key={key} style={styles.count} testID={`${testID}-${key}`}>
            <MnPill label={IMPORT_VERDICT_LABEL[key]} variant={IMPORT_VERDICT_VARIANT[key]} />
            <Text style={[styles.countValue, { color: theme.ink }]}>{format.number(value)}</Text>
          </View>
        ))}
      </View>

      {preview.warnings.map((warning, index) => (
        <Text
          key={warning}
          accessibilityRole="alert"
          style={[styles.warning, { color: theme.amber }]}
          testID={`${testID}-warning-${index}`}
        >
          {warning}
        </Text>
      ))}

      {preview.rows
        .filter((row) => row.verdict === 'INVALID' && row.reason !== null)
        .map((row) => (
          <Text
            key={row.ordinal}
            style={[styles.reason, { color: theme.red }]}
            testID={`${testID}-reason-${row.ordinal}`}
          >
            Row {format.number(row.ordinal)}: {row.reason}
          </Text>
        ))}
    </MnCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: Space.s2 },
  file: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.md },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  counts: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s4, marginTop: Space.s2 },
  count: { alignItems: 'flex-start', gap: Space.s1 },
  countValue: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg },
  warning: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.xs },
  reason: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
});
