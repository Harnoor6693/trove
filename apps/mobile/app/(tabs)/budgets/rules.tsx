import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure, type ApiFailure } from '@api/errors';
import { MAPPING_SOURCE_LABEL, MATCH_TYPES, MATCH_TYPE_LABEL } from '@lib/labels';
import {
  useCategories,
  useCategoryRules,
  useCreateCategoryRule,
  useDeleteCategoryRule,
  useDryRunCategoryRule,
  useReorderCategoryRules,
  useUpdateCategoryRule,
} from '@hooks/useCategories';
import type { CategoryRuleResponse, MatchType } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * `/v1/categories/rules*` — the household's merchant rules, in the ORDER
 * `listCategoryRules` returns them (`priority` ascending). Reorder is by
 * move-up/move-down buttons with accessible names (there is no drag gesture,
 * which a screen-reader user could not reach). The buttons call
 * `POST /v1/categories/rules/reorder`.
 */
export default function CategoryRulesScreen() {
  const theme = useTheme();
  const router = useRouter();
  const rules = useCategoryRules();
  const categories = useCategories();
  const reorder = useReorderCategoryRules();
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const reorderFailure =
    reorder.error == null ? null : toApiFailure(reorder.error, 'Could not reorder your rules.');

  const move = (index: number, direction: -1 | 1) => {
    const ordered = rules.data ?? [];
    const target = index + direction;
    if (target < 0 || target >= ordered.length) return;
    const nextIds = ordered.map((rule) => rule.id);
    const [moved] = nextIds.splice(index, 1);
    nextIds.splice(target, 0, moved);
    reorder.mutate({ orderedIds: nextIds });
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="rules-screen">
      <FormScroll contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="rules-back" />
          <MnEyebrow>Merchant rules</MnEyebrow>
          <MnBtn
            label={creating ? 'Cancel' : 'New rule'}
            variant="secondary"
            onPress={() => setCreating((v) => !v)}
            testID="rules-new-toggle"
          />
        </View>

        <DryRunCard testID="rules-dry-run" />

        {creating ? (
          <RuleForm
            mode="create"
            categories={categories.data ?? []}
            onDone={() => setCreating(false)}
            testID="rule-create-form"
          />
        ) : null}

        {reorderFailure === null ? null : (
          <Text
            accessibilityRole="alert"
            style={[styles.error, { color: theme.red }]}
            testID="rules-reorder-error"
          >
            {failureMessage(reorder.error, reorderFailure)}
          </Text>
        )}

        <ScreenState
          isLoading={rules.isLoading}
          error={rules.error}
          isEmpty={!rules.isLoading && rules.error == null && (rules.data ?? []).length === 0}
          emptyMessage="No merchant rules yet."
          fallbackMessage="Could not load your merchant rules."
          onRetry={() => void rules.refetch()}
          testID="rules"
        >
          <View testID="rule-list">
            {(rules.data ?? []).map((rule, index) =>
              editingId === rule.id ? (
                <RuleForm
                  key={rule.id}
                  mode="edit"
                  rule={rule}
                  categories={categories.data ?? []}
                  onDone={() => setEditingId(null)}
                  testID={`rule-edit-form-${rule.id}`}
                />
              ) : (
                <RuleRow
                  key={rule.id}
                  rule={rule}
                  index={index}
                  count={(rules.data ?? []).length}
                  reorderPending={reorder.isPending}
                  onMoveUp={() => move(index, -1)}
                  onMoveDown={() => move(index, 1)}
                  onEdit={() => setEditingId(rule.id)}
                />
              )
            )}
          </View>
        </ScreenState>
      </FormScroll>
    </SafeAreaView>
  );
}

function RuleRow({
  rule,
  index,
  count,
  reorderPending,
  onMoveUp,
  onMoveDown,
  onEdit,
}: {
  rule: CategoryRuleResponse;
  index: number;
  count: number;
  reorderPending: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onEdit: () => void;
}) {
  const theme = useTheme();
  const remove = useDeleteCategoryRule();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this rule.');

  return (
    <View testID={`rule-row-${rule.id}`}>
      <View style={[styles.row, { borderBottomColor: theme.line }]}>
        <View style={styles.moveColumn}>
          <TouchableOpacity
            onPress={onMoveUp}
            disabled={index === 0 || reorderPending}
            accessibilityRole="button"
            accessibilityLabel={`Move ${rule.merchant} up`}
            accessibilityState={{ disabled: index === 0 || reorderPending }}
            style={[styles.moveBtn, (index === 0 || reorderPending) && styles.moveBtnDisabled]}
            testID={`rule-move-up-${rule.id}`}
          >
            <Text style={{ color: theme.ink2 }}>▲</Text>
          </TouchableOpacity>
          <TouchableOpacity
            onPress={onMoveDown}
            disabled={index === count - 1 || reorderPending}
            accessibilityRole="button"
            accessibilityLabel={`Move ${rule.merchant} down`}
            accessibilityState={{ disabled: index === count - 1 || reorderPending }}
            style={[
              styles.moveBtn,
              (index === count - 1 || reorderPending) && styles.moveBtnDisabled,
            ]}
            testID={`rule-move-down-${rule.id}`}
          >
            <Text style={{ color: theme.ink2 }}>▼</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.rowMain}>
          <Text style={[styles.merchant, { color: theme.ink }]}>{rule.merchant}</Text>
          <View style={styles.rowMeta}>
            <MnPill label={MATCH_TYPE_LABEL[rule.matchType]} variant="blue" />
            <Text style={[styles.category, { color: theme.ink3 }]}>
              → {rule.categoryName ?? 'Unknown category'}
            </Text>
          </View>
        </View>

        <View style={styles.rowActions}>
          <MnBtn label="Edit" variant="ghost" onPress={onEdit} testID={`rule-edit-${rule.id}`} />
          {confirmingDelete ? (
            <MnBtn
              label="Confirm"
              variant="danger"
              loading={remove.isPending}
              onPress={() => remove.mutate(rule.id)}
              testID={`rule-delete-confirm-${rule.id}`}
            />
          ) : (
            <MnBtn
              label="Delete"
              variant="danger"
              onPress={() => setConfirmingDelete(true)}
              testID={`rule-delete-${rule.id}`}
            />
          )}
        </View>
      </View>
      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`rule-delete-error-${rule.id}`}
        >
          {failureMessage(remove.error, deleteFailure)}
        </Text>
      )}
    </View>
  );
}

function RuleForm({
  mode,
  rule,
  categories,
  onDone,
  testID,
}: {
  mode: 'create' | 'edit';
  rule?: CategoryRuleResponse;
  categories: { id: string; name: string }[];
  onDone: () => void;
  testID: string;
}) {
  const theme = useTheme();
  const create = useCreateCategoryRule();
  const update = useUpdateCategoryRule(rule?.id ?? '');

  const [merchant, setMerchant] = useState(rule?.merchant ?? '');
  const [matchType, setMatchType] = useState<MatchType>(rule?.matchType ?? 'EXACT');
  const [categoryId, setCategoryId] = useState(rule?.categoryId ?? '');
  const [confidence, setConfidence] = useState(rule?.confidence ?? '');

  const pending = mode === 'create' ? create : update;
  const failure: ApiFailure | null =
    pending.error == null ? null : toApiFailure(pending.error, 'Could not save that rule.');

  const submit = () => {
    const body = {
      merchant: merchant.trim(),
      matchType,
      categoryId,
      confidence: confidence.trim() === '' ? undefined : normaliseAmountInput(confidence),
    };
    if (mode === 'create') {
      create.mutate(body, { onSuccess: onDone });
    } else {
      update.mutate(body, { onSuccess: onDone });
    }
  };

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.label, { color: theme.ink2 }]}>Merchant</Text>
      <TextInput
        value={merchant}
        onChangeText={setMerchant}
        accessibilityLabel="Merchant"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-merchant`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'merchant')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Match type</Text>
      <View style={styles.chips}>
        {MATCH_TYPES.map((value) => (
          <TouchableOpacity
            key={value}
            onPress={() => setMatchType(value)}
            accessibilityRole="radio"
            accessibilityLabel={MATCH_TYPE_LABEL[value]}
            accessibilityState={{ selected: matchType === value }}
            style={styles.chip}
            testID={`${testID}-matchtype-${value}`}
          >
            <MnPill
              label={MATCH_TYPE_LABEL[value]}
              variant={matchType === value ? 'sage' : 'muted'}
            />
          </TouchableOpacity>
        ))}
      </View>

      <Text style={[styles.label, { color: theme.ink2 }]}>Category</Text>
      <View style={styles.chips}>
        {categories.map((choice) => (
          <TouchableOpacity
            key={choice.id}
            onPress={() => setCategoryId(choice.id)}
            accessibilityRole="radio"
            accessibilityLabel={choice.name}
            accessibilityState={{ selected: categoryId === choice.id }}
            style={styles.chip}
            testID={`${testID}-category-${choice.id}`}
          >
            <MnPill label={choice.name} variant={categoryId === choice.id ? 'sage' : 'muted'} />
          </TouchableOpacity>
        ))}
      </View>
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'categoryId')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Confidence (optional)</Text>
      <Text style={[styles.hint, { color: theme.ink3 }]}>Leave blank to default to 0.900.</Text>
      <TextInput
        value={confidence}
        onChangeText={setConfidence}
        keyboardType="decimal-pad"
        accessibilityLabel="Confidence"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-confidence`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'confidence')} />}

      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failure.status === 409
            ? 'A rule for that merchant already exists. Edit it instead of creating a second one.'
            : failureMessage(pending.error, failure)}
        </Text>
      )}

      <View style={styles.pair}>
        <MnBtn
          label="Save"
          style={styles.grow}
          loading={pending.isPending}
          disabled={merchant.trim() === '' || categoryId === ''}
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

/**
 * "Test this rule against merchant X" — `POST /v1/categories/rules/dry-run`.
 * Writes nothing. Renders exactly what `CategorisationResultResponse`
 * returns (`categoryName`, `confidence`, `source`) and nothing invented —
 * the response carries no `matchType` of its own, since a dry run can resolve
 * through the built-in community tier or the `Uncategorised` fallback, which
 * has no match type to report.
 */
function DryRunCard({ testID }: { testID: string }) {
  const theme = useTheme();
  const [merchant, setMerchant] = useState('');
  const dryRun = useDryRunCategoryRule();
  const failure =
    dryRun.error == null ? null : toApiFailure(dryRun.error, 'Could not test that merchant.');

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.label, { color: theme.ink2 }]}>Test a merchant</Text>
      <Text style={[styles.hint, { color: theme.ink3 }]}>
        See what your rules would resolve this to, without saving anything.
      </Text>
      <TextInput
        value={merchant}
        onChangeText={setMerchant}
        accessibilityLabel="Merchant to test"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-input`}
      />
      <MnBtn
        label="Test"
        variant="secondary"
        loading={dryRun.isPending}
        disabled={merchant.trim() === ''}
        onPress={() => dryRun.mutate({ merchant: merchant.trim() })}
        testID={`${testID}-submit`}
      />
      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failureMessage(dryRun.error, failure)}
        </Text>
      )}
      {dryRun.data ? (
        <View style={styles.dryRunResult} testID={`${testID}-result`}>
          <Text style={[styles.category, { color: theme.ink }]}>{dryRun.data.categoryName}</Text>
          <MnPill label={`Confidence ${dryRun.data.confidence}`} variant="blue" />
          {dryRun.data.source === null ? (
            <MnPill label="Built-in default" variant="muted" />
          ) : (
            <MnPill label={MAPPING_SOURCE_LABEL[dryRun.data.source]} variant="sage" />
          )}
        </View>
      ) : null}
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
  content: { padding: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  header: { gap: Space.s2 },
  card: { gap: Space.s2 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    gap: Space.s3,
  },
  moveColumn: { gap: Space.s1 },
  moveBtn: {
    minWidth: 44,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  moveBtnDisabled: { opacity: 0.3 },
  rowMain: { flex: 1, gap: Space.s1 },
  rowMeta: { flexDirection: 'row', alignItems: 'center', gap: Space.s2 },
  merchant: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  category: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  rowActions: { gap: Space.s2 },
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
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: { minHeight: 44, justifyContent: 'center' },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  dryRunResult: { flexDirection: 'row', alignItems: 'center', gap: Space.s2, flexWrap: 'wrap' },
});
