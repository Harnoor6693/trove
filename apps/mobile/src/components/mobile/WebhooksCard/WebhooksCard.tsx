import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { useCreateWebhook, useDeleteWebhook, useWebhooks } from '@hooks/useWebhooks';
import { toApiFailure, type ApiFailure } from '@api/errors';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import type { WebhookResponse } from '@api/types';

/**
 * The Webhooks card on Settings — a security surface, not a plain toggle.
 *
 * **The secret is never shown, anywhere, at any time.** The server's own
 * create response does not carry it — `hasSecret` is the whole of what the
 * API says about it — so there is no show-once value for this card to hold,
 * copy, or leave behind in a query cache; the field the member types into is
 * component state that is cleared the moment the create settles, on every
 * path and not only on success.
 */
export function WebhooksCard() {
  const theme = useTheme();
  const webhooks = useWebhooks();
  const create = useCreateWebhook();
  const remove = useDeleteWebhook();
  const [adding, setAdding] = useState(false);
  const [targetUrl, setTargetUrl] = useState('');
  const [secret, setSecret] = useState('');
  const [createError, setCreateError] = useState<unknown>(null);

  const createFailure: ApiFailure | null =
    createError == null ? null : toApiFailure(createError, 'That webhook could not be created.');

  const submit = () => {
    setCreateError(null);
    create.mutate(
      { targetUrl: targetUrl.trim(), secret },
      {
        onSuccess: () => {
          setTargetUrl('');
          setSecret('');
          setAdding(false);
        },
        onError: (err) => setCreateError(err),
        // The secret must not sit in the mutation cache after this settles —
        // same reason `AiSettingsCard` resets after every API-key save.
        onSettled: () => {
          setSecret('');
          create.reset();
        },
      }
    );
  };

  return (
    <View testID="settings-webhooks-section">
      <MnEyebrow style={styles.section}>Webhooks</MnEyebrow>
      <MnCard style={styles.card}>
        <Text style={[styles.note, { color: theme.ink3 }]}>
          Trove will POST to an HTTPS URL you control when your events happen, signed with a secret
          only you hold. The secret is never shown again after you set it — not here, not anywhere.
        </Text>

        {webhooks.isLoading && (
          <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-webhooks-loading">
            Loading your webhooks…
          </Text>
        )}
        {webhooks.error != null && (
          <View testID="settings-webhooks-list-error">
            <Text
              style={[styles.note, { color: theme.red }]}
              accessibilityRole="alert"
              testID="settings-webhooks-list-error-text"
            >
              {toApiFailure(webhooks.error, 'Could not load your webhooks.').message}
            </Text>
            <MnBtn
              label="Retry"
              variant="ghost"
              onPress={() => void webhooks.refetch()}
              testID="settings-webhooks-list-retry"
            />
          </View>
        )}
        {webhooks.data !== undefined &&
          webhooks.data.map((webhook) => (
            <WebhookRow
              key={webhook.id}
              webhook={webhook}
              onDelete={() => remove.mutate(webhook.id)}
              deleteFailure={
                remove.error != null && remove.variables === webhook.id
                  ? toApiFailure(remove.error, 'Could not delete this webhook.')
                  : null
              }
              deletePending={remove.isPending && remove.variables === webhook.id}
            />
          ))}
        {webhooks.data !== undefined && webhooks.data.length === 0 && !adding && (
          <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-webhooks-empty">
            No webhooks yet.
          </Text>
        )}

        {adding ? (
          <View style={styles.form} testID="settings-webhooks-form">
            <Text style={[styles.label, { color: theme.ink2 }]}>Target URL</Text>
            <TextInput
              value={targetUrl}
              onChangeText={setTargetUrl}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              placeholder="https://hooks.example.com/trove"
              placeholderTextColor={theme.ink3}
              accessibilityLabel="Webhook target URL"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-webhooks-url"
            />

            <Text style={[styles.label, { color: theme.ink2 }]}>Secret</Text>
            <Text style={[styles.note, { color: theme.ink3 }]}>
              At least 16 characters. Shown to no one, including you, once saved.
            </Text>
            <TextInput
              value={secret}
              onChangeText={setSecret}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
              accessibilityLabel="Webhook secret"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-webhooks-secret"
            />

            {createFailure !== null && (
              <Text
                style={[styles.note, { color: theme.red }]}
                accessibilityRole="alert"
                testID="settings-webhooks-error"
              >
                {createFailure.message}
              </Text>
            )}

            <View style={styles.pair}>
              <MnBtn
                label="Add webhook"
                style={styles.grow}
                loading={create.isPending}
                disabled={targetUrl.trim() === '' || secret.length < 16}
                onPress={submit}
                testID="settings-webhooks-submit"
              />
              <MnBtn
                label="Cancel"
                variant="ghost"
                style={styles.grow}
                onPress={() => {
                  setAdding(false);
                  setTargetUrl('');
                  setSecret('');
                  setCreateError(null);
                }}
                testID="settings-webhooks-cancel"
              />
            </View>
          </View>
        ) : (
          <MnBtn
            label="Add a webhook"
            variant="secondary"
            onPress={() => setAdding(true)}
            style={styles.rowBtn}
            testID="settings-webhooks-add-toggle"
          />
        )}
      </MnCard>
    </View>
  );
}

function WebhookRow({
  webhook,
  onDelete,
  deleteFailure,
  deletePending,
}: {
  webhook: WebhookResponse;
  onDelete: () => void;
  deleteFailure: ApiFailure | null;
  deletePending: boolean;
}) {
  const theme = useTheme();
  const [confirming, setConfirming] = useState(false);

  return (
    <View style={[styles.row, { borderColor: theme.line }]} testID={`webhook-row-${webhook.id}`}>
      <View style={styles.rowMain}>
        <Text style={[styles.url, { color: theme.ink }]} numberOfLines={1}>
          {webhook.targetUrl}
        </Text>
        <View style={styles.rowMeta}>
          <MnPill
            label={webhook.enabled ? 'Enabled' : 'Disabled'}
            variant={webhook.enabled ? 'sage' : 'muted'}
          />
          <Text style={[styles.note, { color: theme.ink3 }]}>
            {webhook.eventTypes.length === 0 ? 'All events' : webhook.eventTypes.join(', ')}
          </Text>
        </View>
        {deleteFailure !== null && (
          <Text
            style={[styles.note, { color: theme.red }]}
            accessibilityRole="alert"
            testID={`webhook-row-${webhook.id}-delete-error`}
          >
            {deleteFailure.message}
          </Text>
        )}
      </View>
      {confirming ? (
        <MnBtn
          label="Confirm"
          variant="danger"
          loading={deletePending}
          onPress={onDelete}
          testID={`webhook-row-${webhook.id}-delete-confirm`}
        />
      ) : (
        <MnBtn
          label="Delete"
          variant="danger"
          onPress={() => setConfirming(true)}
          testID={`webhook-row-${webhook.id}-delete`}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4, gap: Space.s2 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s1 },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s3,
    textTransform: 'uppercase',
  },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
    minHeight: 44,
  },
  form: { gap: Space.s1 },
  pair: { flexDirection: 'row', gap: Space.s2, marginTop: Space.s3 },
  grow: { flex: 1 },
  rowBtn: { marginTop: Space.s2 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: Space.s2,
    borderTopWidth: 1,
    paddingTop: Space.s2,
    marginTop: Space.s2,
  },
  rowMain: { flex: 1, gap: Space.s1 },
  url: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  rowMeta: { flexDirection: 'row', alignItems: 'center', gap: Space.s2 },
});
