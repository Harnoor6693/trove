import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { StepUpForm } from '@components/mobile/StepUpForm/StepUpForm';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import {
  useCreateDataExport,
  useDataExport,
  useDataExports,
  useShareDataExport,
} from '@hooks/useDataExport';
import { toApiFailure } from '@api/errors';
import { ErrorSlug, type DataExportRunResponse, type DataExportStatus } from '@api/types';
import { useUserFormat } from '@lib/useUserFormat';
import { useAuthStore } from '@stores/authStore';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * Full data export — `POST /v1/me/export`, polled through
 * `GET /v1/me/export/{id}`, then `GET /v1/me/export/{id}/download`.
 *
 * **What this screen must say before it asks for anything**, because the
 * operation's own description says it and a member cannot find it out any
 * other way:
 *
 *  - The rows are the **household's**; the run is the member's. The archive
 *    holds the whole shared ledger, not only what this member entered.
 *  - **Receipts are metadata only** — never the image bytes, never a storage
 *    key, never a URL. Images come from a receipt's own download.
 *  - **One run per day**, over the member's own local day. The server enforces
 *    it as a 409 that says when they may ask again; this screen shows that
 *    sentence rather than running a timer of its own.
 *  - The link **lives 24 hours**, then a sweep deletes the archive itself.
 *
 * **The download.** `downloadDataExport` serves `application/zip` behind the
 * bearer session — nothing is presigned and there is deliberately no token in
 * a URL — so the bytes come only through `api`. Nothing is fetched until the
 * member presses "Save or share" (the run row already carries the size and the
 * expiry); then `useShareDataExport` pulls the archive, writes it to the app
 * cache as `trove-export.zip`, opens the system share sheet — where the member
 * saves it to Files / Downloads or sends it elsewhere — and deletes the cache
 * copy when the sheet returns. A 409 (not ready, or the link expired) is shown
 * through `toApiFailure`. A button that opened a URL instead would either
 * strand the member at a 401 or put their access token in the system
 * browser's history.
 */

const STATUS_LABEL: Record<DataExportStatus, string> = {
  PENDING: 'Queued',
  GENERATING: 'Generating',
  COMPLETE: 'Ready',
  FAILED: 'Failed',
};

export default function DataExportScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  // `null` = not known yet — see `delete.tsx`: a best-effort `me` that never
  // arrived must not hide the code field from an enrolled member.
  const totpEnabled = useAuthStore((s) => s.me?.totpEnabled ?? null);

  const runs = useDataExports();
  const create = useCreateDataExport();
  const share = useShareDataExport();

  // The refusal is copied into local state and the mutation is then RESET on
  // every path — the same pattern `WebhooksCard` ships for its secret. Leaving
  // it unreset would keep the step-up credential alive in
  // `mutation.variables` for as long as the member sits reading the message.
  const [createError, setCreateError] = useState<unknown>(null);
  const [watchedId, setWatchedId] = useState<string | null>(null);
  const watched = useDataExport(watchedId ?? '', watchedId !== null);

  // The polled row wins over the page, which was fetched before the run began.
  const latest: DataExportRunResponse | null = watched.data ?? runs.data?.data[0] ?? null;

  const shareFailure =
    share.error == null ? null : toApiFailure(share.error, 'Could not save or share that export.');

  const createFailure =
    createError == null ? null : toApiFailure(createError, 'Could not start that export.');

  const start = (body: Parameters<typeof create.mutate>[0]) => {
    setCreateError(null);
    create.mutate(body, {
      onSuccess: (run) => setWatchedId(run.id),
      onError: (err) => setCreateError(err),
      onSettled: () => create.reset(),
    });
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="export-screen">
      <FormScroll contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Export your data</Text>

        <MnEyebrow style={styles.section}>What you get</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="export-contents">
            A ZIP archive holding a manifest, `transactions.csv`, and one JSON document per dataset:
            accounts, categories, merchant rules, budget periods, envelopes, savings goals,
            subscriptions, net-worth snapshots, assets, liabilities, receipts, coach conversations
            and notification preferences.
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="export-household-note">
            Your ledger is shared with your household, so the archive contains the household&rsquo;s
            transactions — not only the ones you entered.
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="export-receipts-note">
            Receipts are included as details only — merchant, amounts, the tax split, the matched
            transaction, file names. The photographs themselves are not in the archive; download
            those from each receipt.
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="export-limits-note">
            One export a day, and the download link works for 24 hours before the archive is
            deleted.
          </Text>
        </MnCard>

        <MnEyebrow style={styles.section}>Confirm it&rsquo;s you</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={[styles.note, { color: theme.ink3 }]}>
            An archive is a copy of everything in your account, so being signed in isn&rsquo;t
            enough on its own.
          </Text>
          <StepUpForm
            testID="export-step-up"
            totpEnabled={totpEnabled}
            submitLabel="Start export"
            pending={create.isPending}
            failure={createFailure}
            onSubmit={start}
          />
          {/* The one-run-per-day cap is a 409 whose `detail` names the day the
              member may ask again — the server's sentence, not a timer of this
              client's own. `StepUpForm` renders the step-up and 429 copy; this
              is the only refusal that needs its own line, because it is not
              about the credential at all. */}
          {createFailure?.code === ErrorSlug.conflict && (
            <Text
              testID="export-create-conflict"
              accessibilityRole="alert"
              style={[styles.note, { color: theme.red }]}
            >
              {createFailure.message}
            </Text>
          )}
        </MnCard>

        <MnEyebrow style={styles.section}>Your exports</MnEyebrow>
        <MnCard style={styles.card}>
          {runs.isLoading && (
            <Text style={[styles.note, { color: theme.ink3 }]} testID="export-runs-loading">
              Loading…
            </Text>
          )}
          {runs.error != null && (
            <Text
              testID="export-runs-error"
              accessibilityRole="alert"
              style={[styles.note, { color: theme.red }]}
            >
              {toApiFailure(runs.error, 'Could not load your exports.').message}
            </Text>
          )}
          {latest === null && !runs.isLoading && runs.error == null && (
            <Text style={[styles.note, { color: theme.ink3 }]} testID="export-runs-empty">
              You haven&rsquo;t exported anything yet.
            </Text>
          )}

          {latest !== null && (
            <View testID={`export-run-${latest.id}`}>
              <View style={styles.row}>
                <Text style={[styles.value, { color: theme.ink }]}>
                  {format.date(latest.createdAt, 'dateTime')}
                </Text>
                <MnPill
                  testID={`export-run-${latest.id}-status`}
                  label={STATUS_LABEL[latest.status]}
                  variant={
                    latest.status === 'COMPLETE'
                      ? 'sage'
                      : latest.status === 'FAILED'
                        ? 'muted'
                        : 'muted'
                  }
                />
              </View>

              {(latest.status === 'PENDING' || latest.status === 'GENERATING') && (
                <Text style={[styles.note, { color: theme.ink3 }]} testID="export-generating">
                  Still generating. This screen updates itself.
                </Text>
              )}
              {latest.status === 'FAILED' && (
                <Text
                  style={[styles.note, { color: theme.red }]}
                  testID="export-failed"
                  accessibilityRole="alert"
                >
                  {latest.errorMessage ?? 'That export could not be generated.'}
                </Text>
              )}
              {latest.status === 'COMPLETE' && (
                <>
                  <Text style={[styles.note, { color: theme.ink3 }]} testID="export-ready">
                    Ready
                    {latest.fileSizeBytes === null
                      ? ''
                      : ` · ${Math.max(1, Math.round(latest.fileSizeBytes / 1024))} KB`}
                    {latest.expiresAt === null
                      ? ''
                      : ` · link expires ${format.date(latest.expiresAt, 'dateTime')}`}
                  </Text>
                  <Text style={[styles.note, { color: theme.ink3 }]} testID="export-archive-note">
                    Save or share the ZIP while the link lasts — to Files, Downloads, or another
                    app. The copy this app makes for the share sheet is deleted afterwards.
                  </Text>
                  <MnBtn
                    testID="data-export-share"
                    label="Save or share"
                    variant="secondary"
                    fullWidth
                    style={styles.section}
                    loading={share.isPending}
                    onPress={() => share.mutate(latest.id)}
                  />
                  {shareFailure !== null && (
                    <Text
                      testID="data-export-share-error"
                      accessibilityRole="alert"
                      style={[styles.note, { color: theme.red }]}
                    >
                      {shareFailure.message}
                    </Text>
                  )}
                </>
              )}
            </View>
          )}
        </MnCard>

        <MnBtn
          testID="export-back-btn"
          label="Back to settings"
          variant="ghost"
          fullWidth
          style={styles.section}
          onPress={() => router.back()}
        />
      </FormScroll>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.xl, marginBottom: Space.s2 },
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
  value: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: Space.s2,
  },
});
