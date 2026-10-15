import React, { useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure, type ApiFailure } from '@api/errors';
import { useAssets, useCreateAsset, useDeleteAsset, useUpdateAsset } from '@hooks/useNetWorth';
import { useUserFormat } from '@lib/useUserFormat';
import { CURRENCY_CODES, type AssetResponse, type CurrencyCode, type AssetType } from '@api/types';

const ASSET_TYPES: AssetType[] = ['CASH', 'INVESTMENT', 'REAL_ESTATE', 'VEHICLE', 'OTHER'];

const ASSET_TYPE_LABEL: Record<AssetType, string> = {
  CASH: 'Cash',
  INVESTMENT: 'Investment',
  REAL_ESTATE: 'Real estate',
  VEHICLE: 'Vehicle',
  OTHER: 'Other',
};

/**
 * `/v1/networth/assets*` — the household's shared ledger, CRUD. `currency` is
 * writable only at creation (defaults to the creating member's own); an edit
 * cannot change it, because it would reinterpret the figure already recorded.
 */
export default function AssetsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const assets = useAssets();
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="assets-screen">
      <View style={styles.header}>
        <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="assets-back" />
        <MnEyebrow>Assets</MnEyebrow>
        <MnBtn
          label={creating ? 'Cancel' : 'New asset'}
          variant="secondary"
          onPress={() => setCreating((v) => !v)}
          testID="assets-new-toggle"
        />
      </View>

      {creating ? (
        <AssetForm mode="create" onDone={() => setCreating(false)} testID="assets-create-form" />
      ) : null}

      <ScreenState
        isLoading={assets.isLoading}
        error={assets.error}
        isEmpty={!assets.isLoading && assets.error == null && (assets.data ?? []).length === 0}
        emptyMessage="No assets yet."
        fallbackMessage="Could not load your assets."
        onRetry={() => void assets.refetch()}
        testID="assets"
      >
        <FlatList
          data={assets.data ?? []}
          keyExtractor={(asset) => asset.id}
          contentContainerStyle={styles.list}
          renderItem={({ item }) =>
            editingId === item.id ? (
              <AssetForm
                mode="edit"
                asset={item}
                onDone={() => setEditingId(null)}
                testID={`asset-edit-form-${item.id}`}
              />
            ) : (
              <AssetRow asset={item} onEdit={() => setEditingId(item.id)} />
            )
          }
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function AssetRow({ asset, onEdit }: { asset: AssetResponse; onEdit: () => void }) {
  const theme = useTheme();
  const remove = useDeleteAsset();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this asset.');

  return (
    <MnCard style={styles.card} testID={`asset-row-${asset.id}`}>
      <View style={styles.rowHeader}>
        <Text style={[styles.name, { color: theme.ink }]}>{asset.name}</Text>
        <Text style={[styles.typeLabel, { color: theme.ink3 }]}>
          {ASSET_TYPE_LABEL[asset.assetType]}
        </Text>
      </View>
      <MoneyDisplay
        amount={asset.value}
        currency={asset.currency}
        size="lg"
        testID={`asset-value-${asset.id}`}
      />
      <View style={styles.pair}>
        <MnBtn
          label="Edit"
          variant="secondary"
          style={styles.grow}
          onPress={onEdit}
          testID={`asset-edit-${asset.id}`}
        />
        {confirmingDelete ? (
          <MnBtn
            label="Confirm delete"
            variant="danger"
            style={styles.grow}
            loading={remove.isPending}
            onPress={() => remove.mutate(asset.id)}
            testID={`asset-delete-confirm-${asset.id}`}
          />
        ) : (
          <MnBtn
            label="Delete"
            variant="danger"
            style={styles.grow}
            onPress={() => setConfirmingDelete(true)}
            testID={`asset-delete-${asset.id}`}
          />
        )}
      </View>
      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`asset-delete-error-${asset.id}`}
        >
          {failureMessage(remove.error, deleteFailure)}
        </Text>
      )}
    </MnCard>
  );
}

function AssetForm({
  mode,
  asset,
  onDone,
  testID,
}: {
  mode: 'create' | 'edit';
  asset?: AssetResponse;
  onDone: () => void;
  testID: string;
}) {
  const theme = useTheme();
  const create = useCreateAsset();
  const update = useUpdateAsset(asset?.id ?? '');

  const format = useUserFormat();
  const [name, setName] = useState(asset?.name ?? '');
  const [assetType, setAssetType] = useState<AssetType>(asset?.assetType ?? 'CASH');
  const [value, setValue] = useState(asset?.value ?? '');
  // Only meaningful in create mode: `AssetRequest.currency` explicit-null-or-
  // omitted means the creating member's own `users.currency` (spec). An
  // update never carries currency at all — see `AssetUpdateRequest`'s own
  // doc comment — the denomination is fixed once the row exists.
  const [currency, setCurrency] = useState<CurrencyCode | null>(null);

  const pending = mode === 'create' ? create : update;
  const error = pending.error;
  const failure: ApiFailure | null =
    error == null ? null : toApiFailure(error, 'Could not save that asset.');

  const submit = () => {
    if (mode === 'create') {
      create.mutate(
        {
          name: name.trim(),
          assetType,
          value: value.trim(),
          currency: currency ?? undefined,
        },
        { onSuccess: onDone }
      );
    } else {
      // A full replace (per `AssetUpdateRequest`): carry the row's existing
      // `accountId` forward so an edit that isn't touching the link doesn't
      // silently unlink a connected account.
      update.mutate(
        { name: name.trim(), assetType, value: value.trim(), accountId: asset?.accountId ?? null },
        { onSuccess: onDone }
      );
    }
  };

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.label, { color: theme.ink2 }]}>Name</Text>
      <TextInput
        value={name}
        onChangeText={setName}
        accessibilityLabel="Asset name"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-name`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'name')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Type</Text>
      <View style={styles.choices}>
        {ASSET_TYPES.map((type) => (
          <TouchableOpacity
            key={type}
            onPress={() => setAssetType(type)}
            accessibilityRole="radio"
            accessibilityState={{ selected: assetType === type }}
            accessibilityLabel={ASSET_TYPE_LABEL[type]}
            style={[
              styles.chip,
              {
                borderColor: assetType === type ? theme.sage : theme.line,
                backgroundColor: assetType === type ? theme.sageSoft : 'transparent',
              },
            ]}
            testID={`${testID}-type-${type}`}
          >
            <Text
              style={[styles.chipText, { color: assetType === type ? theme.sage : theme.ink2 }]}
            >
              {ASSET_TYPE_LABEL[type]}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {mode === 'create' && (
        <>
          <Text style={[styles.label, { color: theme.ink2 }]}>Currency</Text>
          <Text style={[styles.hint, { color: theme.ink3 }]}>
            Recorded in your own currency by default. Pick the household&apos;s other currency below
            if you hold this asset in it instead.
          </Text>
          <View style={styles.choices}>
            {CURRENCY_CODES.map((code) => {
              const selected = (currency ?? format.currency) === code;
              return (
                <TouchableOpacity
                  key={code}
                  onPress={() => setCurrency(code === format.currency ? null : code)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  accessibilityLabel={code === format.currency ? `${code} (your currency)` : code}
                  style={[
                    styles.chip,
                    {
                      borderColor: selected ? theme.sage : theme.line,
                      backgroundColor: selected ? theme.sageSoft : 'transparent',
                    },
                  ]}
                  testID={`${testID}-currency-${code}`}
                >
                  <Text style={[styles.chipText, { color: selected ? theme.sage : theme.ink2 }]}>
                    {code}
                    {code === format.currency ? ' (yours)' : ''}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </>
      )}

      <Text style={[styles.label, { color: theme.ink2 }]}>Value</Text>
      <TextInput
        value={value}
        onChangeText={setValue}
        keyboardType="decimal-pad"
        accessibilityLabel="Asset value"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-value`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'value')} />}

      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failureMessage(error, failure)}
        </Text>
      )}

      <View style={styles.pair}>
        <MnBtn
          label="Save"
          style={styles.grow}
          loading={pending.isPending}
          disabled={name.trim() === '' || value.trim() === ''}
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

function FieldErrorText({ message }: { message: string | null }) {
  const theme = useTheme();
  if (message === null) return null;
  return (
    <Text accessibilityRole="alert" style={[styles.error, { color: theme.red }]}>
      {message}
    </Text>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: { padding: Space.s4, gap: Space.s2 },
  list: { paddingHorizontal: Space.s4, gap: Space.s3 },
  card: { gap: Space.s2, marginBottom: Space.s3 },
  rowHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  name: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  typeLabel: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  hint: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
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
});
