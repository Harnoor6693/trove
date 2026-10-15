import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { AdminOnly } from '@components/mobile/AdminOnly/AdminOnly';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { auditPageCount, useAuditLog } from '@hooks/useAdmin';
import { toApiFailure } from '@api/errors';
import { useUserFormat } from '@lib/useUserFormat';

/**
 * The household's audit log — `GET /v1/audit`, paged.
 *
 * One row per mutating request the install handled: what was called, what it
 * answered, who was signed in, from where, and how long it took. Reads are not
 * recorded.
 *
 * **A real pager, not an infinite list.** `meta.total` is what tells the reader
 * how much there is, and an append-only "load more" hides it; an audit log is
 * also read by jumping around rather than by scrolling to the end.
 *
 * **Nothing here is markup.** `path` is the matched route template
 * (`/api/v1/admin/users/{id}`), never the caller's raw URI — two routes on this
 * API carry a live credential in the path, and a table that stored the URI
 * would be a permanent plaintext copy of every invitation token the install
 * ever issued. No body, header, query string or token is kept either, so every
 * field on a row is a scalar rendered as text.
 *
 * `userId` and `clientIp` are null when the server had neither — an
 * unauthenticated request has no user, an in-process one has no address — and
 * `userId` keeps the raw id of an account that was later deleted, because an
 * incident review must not be erasable by deleting an account.
 */
export default function AdminAuditScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const [page, setPage] = useState(0);
  const audit = useAuditLog(page);

  const meta = audit.data?.meta ?? null;
  const pageCount = meta === null ? 1 : auditPageCount(meta);

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="admin-audit-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={[styles.title, { color: theme.ink }]}>Audit log</Text>

        <AdminOnly error={audit.error}>
          <Text style={[styles.note, { color: theme.ink3 }]}>
            Every change this server made, newest first. Reads aren&rsquo;t recorded — the question
            this answers is what changed and who changed it.
          </Text>

          <MnCard style={styles.card}>
            {audit.isLoading && (
              <Text style={[styles.note, { color: theme.ink3 }]} testID="admin-audit-loading">
                Loading…
              </Text>
            )}
            {audit.error != null && (
              <Text
                testID="admin-audit-error"
                accessibilityRole="alert"
                style={[styles.note, { color: theme.red }]}
              >
                {toApiFailure(audit.error, 'Could not load the audit log.').message}
              </Text>
            )}
            {audit.data?.data.length === 0 && (
              <Text style={[styles.note, { color: theme.ink3 }]} testID="admin-audit-empty">
                Nothing has been changed on this server yet.
              </Text>
            )}
            {audit.data?.data.map((row) => (
              <View
                key={row.id}
                style={[styles.row, { borderColor: theme.line }]}
                testID={`admin-audit-${row.id}`}
                accessibilityLabel={`${row.method} ${row.path}, answered ${row.status}, ${format.date(row.at, 'dateTime')}`}
              >
                <View style={styles.head}>
                  <MnPill label={row.method} variant="muted" />
                  <MnPill
                    label={String(row.status)}
                    variant={row.status < 400 ? 'sage' : 'muted'}
                  />
                  <Text style={[styles.note, { color: theme.ink3 }]}>{row.durationMs} ms</Text>
                </View>
                <Text style={[styles.path, { color: theme.ink }]}>{row.path}</Text>
                <Text style={[styles.note, { color: theme.ink3 }]}>
                  {format.date(row.at, 'dateTime')}
                  {row.clientIp === null ? '' : ` · ${row.clientIp}`}
                  {row.userId === null ? ' · signed out' : ''}
                </Text>
              </View>
            ))}
          </MnCard>

          <View style={styles.pager}>
            <MnBtn
              testID="admin-audit-prev"
              label="Previous"
              variant="secondary"
              style={styles.grow}
              disabled={page === 0 || audit.isFetching}
              onPress={() => setPage((current) => Math.max(0, current - 1))}
            />
            <Text style={[styles.pageLabel, { color: theme.ink2 }]} testID="admin-audit-page">
              {`Page ${page + 1} of ${pageCount}`}
              {meta === null ? '' : ` · ${meta.total} changes`}
            </Text>
            <MnBtn
              testID="admin-audit-next"
              label="Next"
              variant="secondary"
              style={styles.grow}
              disabled={page + 1 >= pageCount || audit.isFetching}
              onPress={() => setPage((current) => current + 1)}
            />
          </View>
        </AdminOnly>

        <MnBtn
          testID="admin-audit-back"
          label="Back"
          variant="ghost"
          fullWidth
          style={styles.section}
          onPress={() => router.back()}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.xl, marginBottom: Space.s3 },
  section: { marginTop: Space.s4 },
  card: { marginTop: Space.s3, marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
  path: { fontFamily: FontFamily.mono, fontSize: FontSize.sm, marginTop: Space.s2 },
  row: { borderTopWidth: 1, paddingTop: Space.s3, marginTop: Space.s3 },
  head: { flexDirection: 'row', gap: Space.s2, alignItems: 'center', flexWrap: 'wrap' },
  pager: { flexDirection: 'row', alignItems: 'center', gap: Space.s2 },
  grow: { flex: 1, minHeight: 44 },
  pageLabel: { fontFamily: FontFamily.body, fontSize: FontSize.xs, textAlign: 'center' },
});
