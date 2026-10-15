import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { VictoryAxis, VictoryChart, VictoryLine } from 'victory-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure } from '@api/errors';
import { parseDecimal, useUserFormat, type UserFormat } from '@lib/useUserFormat';
import { useNetWorthSeries, useTakeNetWorthSnapshot } from '@hooks/useNetWorth';
import type { NetWorthRange, NetWorthSeries } from '@api/types';
import { localDateIso } from '@lib/localDate';

/**
 * `snapshotDate` is a plain calendar date (`YYYY-MM-DD`, §12300). Parsed as
 * local midnight — same convention `useUserFormat`'s own `date()` uses — so
 * the chart's x-axis is a real timeline rather than an array index. Two
 * currencies with different history lengths (points is "oldest first,
 * empty when this currency has no snapshots in range" per `NetWorthSeries`)
 * then still line up by actual date instead of by position.
 */
function dayTimestamp(snapshotDate: string): number {
  return new Date(`${snapshotDate}T00:00:00`).getTime();
}

/**
 * The Net Worth tab's landing screen: one line per currency, never blended
 * (F2 — there is no exchange rate anywhere in this product). A household
 * holding both CAD and USD positions sees two lines and two totals, not one
 * converted figure.
 */
const RANGES: NetWorthRange[] = ['3M', '6M', '1Y', 'ALL'];

/** Colours assigned to each currency's line, in series order — this product supports at most CAD+USD. */
function lineColorFor(index: number, theme: ReturnType<typeof useTheme>): string {
  return index === 0 ? theme.sage : theme.blue;
}

export default function NetWorthScreen() {
  const theme = useTheme();
  const format = useUserFormat();
  const router = useRouter();
  const [range, setRange] = useState<NetWorthRange>('1Y');
  const series = useNetWorthSeries(range);
  const snapshot = useTakeNetWorthSnapshot();

  const snapshotFailure =
    snapshot.error == null ? null : toApiFailure(snapshot.error, 'Could not take a snapshot.');

  const data = series.data;
  const isEmpty = !series.isLoading && series.error == null && (data?.series.length ?? 0) === 0;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="net-worth-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <MnEyebrow>Net worth</MnEyebrow>
          <MnBtn
            label="Take a snapshot"
            variant="secondary"
            loading={snapshot.isPending}
            onPress={() => snapshot.mutate()}
            testID="net-worth-snapshot-btn"
          />
        </View>

        <View style={styles.rangeRow} accessibilityRole="tablist">
          {RANGES.map((option) => (
            <TouchableOpacity
              key={option}
              onPress={() => setRange(option)}
              accessibilityRole="tab"
              accessibilityState={{ selected: range === option }}
              accessibilityLabel={`${option} range`}
              style={[
                styles.rangeChip,
                {
                  borderColor: range === option ? theme.sage : theme.line,
                  backgroundColor: range === option ? theme.sageSoft : 'transparent',
                },
              ]}
              testID={`net-worth-range-${option}`}
            >
              <Text
                style={[styles.rangeText, { color: range === option ? theme.sage : theme.ink2 }]}
              >
                {option}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {snapshot.isSuccess && (
          <Text
            style={[styles.note, { color: theme.sage }]}
            accessibilityRole="alert"
            testID="net-worth-snapshot-success"
          >
            Snapshot taken.
          </Text>
        )}
        {snapshotFailure !== null && (
          <Text
            style={[styles.note, { color: theme.red }]}
            accessibilityRole="alert"
            testID="net-worth-snapshot-error"
          >
            {failureMessage(snapshot.error, snapshotFailure)}
          </Text>
        )}

        <ScreenState
          isLoading={series.isLoading}
          error={series.error}
          isEmpty={isEmpty}
          emptyMessage="No net-worth snapshots yet. Take one to start your history."
          fallbackMessage="Could not load your net-worth history."
          onRetry={() => void series.refetch()}
          testID="net-worth-series"
        >
          {data !== undefined && data.series.length > 0 && (
            <NetWorthChart series={data.series} theme={theme} format={format} />
          )}
        </ScreenState>

        <View style={styles.nav}>
          <MnBtn
            label="Assets"
            variant="ghost"
            onPress={() => router.push('/(tabs)/net-worth/assets')}
            testID="net-worth-nav-assets"
          />
          <MnBtn
            label="Liabilities"
            variant="ghost"
            onPress={() => router.push('/(tabs)/net-worth/liabilities')}
            testID="net-worth-nav-liabilities"
          />
          <MnBtn
            label="Tax export"
            variant="ghost"
            onPress={() => router.push('/(tabs)/net-worth/tax-export')}
            testID="net-worth-nav-tax-export"
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

/**
 * One line per currency (F2), plus a text alternative underneath carrying the
 * same numbers for a screen reader — a chart's SVG has nothing for one to
 * read.
 */
function NetWorthChart({
  series,
  theme,
  format,
}: {
  series: NetWorthSeries[];
  theme: ReturnType<typeof useTheme>;
  format: UserFormat;
}) {
  return (
    <View testID="net-worth-chart">
      {/*
       * One VictoryChart per currency, each with its own dependent axis
       * labelled in that series' own currency and its own real-date x-axis —
       * never one shared axis, which would let a CAD line and a USD line at
       * the same pixel height read as the same amount of money (F2).
       */}
      {series.map((one, index) => (
        <View key={one.currency} testID={`net-worth-chart-${one.currency}`}>
          <Text style={[styles.chartCurrency, { color: theme.ink2 }]}>{one.currency}</Text>
          <VictoryChart height={160} padding={{ top: 12, bottom: 32, left: 64, right: 16 }}>
            <VictoryAxis
              dependentAxis
              tickFormat={(t: number) =>
                format.money(String(t), { currency: one.currency, hideCents: true })
              }
            />
            <VictoryAxis
              scale={{ x: 'time' }}
              tickFormat={(t: number) => format.date(localDateIso(new Date(t)), 'short')}
            />
            <VictoryLine
              data={one.points.map((point) => ({
                x: dayTimestamp(point.snapshotDate),
                y: parseDecimal(point.netWorth) ?? 0,
              }))}
              style={{ data: { stroke: lineColorFor(index, theme) } }}
            />
          </VictoryChart>
        </View>
      ))}

      <View
        accessible
        accessibilityLabel={series
          .map((one) => {
            const latest = one.points[one.points.length - 1];
            return `${one.currency}: net worth ${latest?.netWorth ?? '0'} ${one.currency}`;
          })
          .join('. ')}
        style={styles.legend}
      >
        {series.map((one, index) => {
          const latest = one.points[one.points.length - 1];
          return (
            <MnCard
              key={one.currency}
              style={styles.legendCard}
              testID={`net-worth-series-${one.currency}`}
            >
              <View style={styles.legendHeader}>
                <View
                  style={[styles.dot, { backgroundColor: lineColorFor(index, theme) }]}
                  importantForAccessibility="no"
                />
                <Text style={[styles.legendLabel, { color: theme.ink2 }]}>{one.currency}</Text>
              </View>
              <MoneyDisplay amount={latest?.netWorth ?? '0'} currency={one.currency} size="lg" />
            </MnCard>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  rangeRow: { flexDirection: 'row', gap: Space.s2 },
  rangeChip: {
    paddingHorizontal: Space.s3,
    paddingVertical: Space.s2,
    borderRadius: Radius.md,
    borderWidth: 1,
    minHeight: 44,
    justifyContent: 'center',
  },
  rangeText: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  note: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  chartCurrency: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs, letterSpacing: 1 },
  legend: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s3, marginTop: Space.s3 },
  legendCard: { flexGrow: 1, gap: Space.s1 },
  legendHeader: { flexDirection: 'row', alignItems: 'center', gap: Space.s2 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  legendLabel: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs, letterSpacing: 1 },
  nav: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2, marginTop: Space.s4 },
});
