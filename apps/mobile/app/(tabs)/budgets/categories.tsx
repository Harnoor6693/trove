import React, { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
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
import {
  useCategories,
  useCreateCategory,
  useDeleteCategory,
  useUpdateCategory,
} from '@hooks/useCategories';
import type { CategoryResponse } from '@api/types';

interface CategoryNode {
  category: CategoryResponse;
  children: CategoryNode[];
}

/** Build the parent/child tree from the flat list `GET /v1/categories` returns. */
function buildTree(categories: CategoryResponse[]): CategoryNode[] {
  const byId = new Map<string, CategoryNode>();
  for (const category of categories) {
    byId.set(category.id, { category, children: [] });
  }
  const roots: CategoryNode[] = [];
  for (const category of categories) {
    const node = byId.get(category.id);
    if (node === undefined) continue;
    const parent = category.parentId === null ? undefined : byId.get(category.parentId);
    if (parent === undefined) {
      roots.push(node);
    } else {
      parent.children.push(node);
    }
  }
  return roots;
}

/**
 * `/v1/categories*` — the household's category tree: every system category
 * (seeded, read-only) plus this household's own. A system category
 * (`isSystem: true`, `householdId: null`) offers no rename/re-parent/delete
 * affordance at all — the server answers 403 `forbidden` for any of the
 * three, so the control is simply not shown rather than shown and refused.
 * An in-use household category (still referenced by a merchant rule) is 409
 * `conflict` on delete, worded here in the member's own terms.
 */
export default function CategoriesScreen() {
  const theme = useTheme();
  const router = useRouter();
  const categories = useCategories();
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const tree = useMemo(() => buildTree(categories.data ?? []), [categories.data]);
  const editingCategory = (categories.data ?? []).find((c) => c.id === editingId) ?? null;

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="categories-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <MnBtn
            label="Back"
            variant="ghost"
            onPress={() => router.back()}
            testID="categories-back"
          />
          <MnEyebrow>Categories</MnEyebrow>
          <MnBtn
            label={creating ? 'Cancel' : 'New category'}
            variant="secondary"
            onPress={() => setCreating((v) => !v)}
            testID="categories-new-toggle"
          />
        </View>

        {creating ? (
          <CategoryForm
            mode="create"
            categories={categories.data ?? []}
            onDone={() => setCreating(false)}
            testID="category-create-form"
          />
        ) : null}

        {editingCategory !== null ? (
          <CategoryForm
            mode="edit"
            category={editingCategory}
            categories={categories.data ?? []}
            onDone={() => setEditingId(null)}
            testID="category-edit-form"
          />
        ) : null}

        <ScreenState
          isLoading={categories.isLoading}
          error={categories.error}
          isEmpty={!categories.isLoading && categories.error == null && tree.length === 0}
          emptyMessage="No categories yet."
          fallbackMessage="Could not load your categories."
          onRetry={() => void categories.refetch()}
          testID="categories"
        >
          <View testID="category-tree">
            {tree.map((node) => (
              <CategoryTreeRow
                key={node.category.id}
                node={node}
                depth={0}
                onEdit={(id) => setEditingId(id)}
              />
            ))}
          </View>
        </ScreenState>
      </ScrollView>
    </SafeAreaView>
  );
}

function CategoryTreeRow({
  node,
  depth,
  onEdit,
}: {
  node: CategoryNode;
  depth: number;
  onEdit: (id: string) => void;
}) {
  const theme = useTheme();
  const remove = useDeleteCategory();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this category.');
  const { category } = node;

  return (
    <View testID={`category-node-${category.id}`}>
      <View style={[styles.row, { paddingLeft: Space.s4 * depth, borderBottomColor: theme.line }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.name, { color: theme.ink }]}>{category.name}</Text>
          {category.isSystem ? <MnPill label="Built in" variant="muted" /> : null}
        </View>
        {category.isSystem ? null : (
          <View style={styles.rowActions}>
            <MnBtn
              label="Edit"
              variant="ghost"
              onPress={() => onEdit(category.id)}
              testID={`category-edit-${category.id}`}
            />
            {confirmingDelete ? (
              <MnBtn
                label="Confirm"
                variant="danger"
                loading={remove.isPending}
                onPress={() => remove.mutate(category.id)}
                testID={`category-delete-confirm-${category.id}`}
              />
            ) : (
              <MnBtn
                label="Delete"
                variant="danger"
                onPress={() => setConfirmingDelete(true)}
                testID={`category-delete-${category.id}`}
              />
            )}
          </View>
        )}
      </View>
      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red, paddingLeft: Space.s4 * depth }]}
          testID={`category-delete-error-${category.id}`}
        >
          {deleteFailure.status === 409
            ? 'This category is used by a merchant rule. Remove or repoint that rule first.'
            : failureMessage(remove.error, deleteFailure)}
        </Text>
      )}
      {node.children.map((child) => (
        <CategoryTreeRow key={child.category.id} node={child} depth={depth + 1} onEdit={onEdit} />
      ))}
    </View>
  );
}

function CategoryForm({
  mode,
  category,
  categories,
  onDone,
  testID,
}: {
  mode: 'create' | 'edit';
  category?: CategoryResponse;
  categories: CategoryResponse[];
  onDone: () => void;
  testID: string;
}) {
  const theme = useTheme();
  const create = useCreateCategory();
  const update = useUpdateCategory(category?.id ?? '');

  const [name, setName] = useState(category?.name ?? '');
  const [parentId, setParentId] = useState<string | null>(category?.parentId ?? null);
  const [icon, setIcon] = useState(category?.icon ?? '');

  const pending = mode === 'create' ? create : update;
  const failure: ApiFailure | null =
    pending.error == null ? null : toApiFailure(pending.error, 'Could not save that category.');

  // A category cannot become its own ancestor, and it cannot pick itself as a
  // parent — the server would 400 `validation-error`, but there is no reason
  // to offer the choice the client already knows is wrong.
  const parentChoices = categories.filter((c) => c.id !== category?.id);

  const submit = () => {
    const body = { name: name.trim(), parentId, icon: icon.trim() === '' ? null : icon.trim() };
    if (mode === 'create') {
      create.mutate(body, { onSuccess: onDone });
    } else {
      update.mutate(body, { onSuccess: onDone });
    }
  };

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.label, { color: theme.ink2 }]}>Name</Text>
      <TextInput
        value={name}
        onChangeText={setName}
        accessibilityLabel="Category name"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-name`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'name')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Parent (optional)</Text>
      <View style={styles.chips}>
        <TouchableOpacity
          onPress={() => setParentId(null)}
          accessibilityRole="radio"
          accessibilityLabel="No parent"
          accessibilityState={{ selected: parentId === null }}
          style={styles.chip}
          testID={`${testID}-parent-none`}
        >
          <MnPill label="None" variant={parentId === null ? 'sage' : 'muted'} />
        </TouchableOpacity>
        {parentChoices.map((choice) => (
          <TouchableOpacity
            key={choice.id}
            onPress={() => setParentId(choice.id)}
            accessibilityRole="radio"
            accessibilityLabel={choice.name}
            accessibilityState={{ selected: parentId === choice.id }}
            style={styles.chip}
            testID={`${testID}-parent-${choice.id}`}
          >
            <MnPill label={choice.name} variant={parentId === choice.id ? 'sage' : 'muted'} />
          </TouchableOpacity>
        ))}
      </View>
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'parentId')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Icon (optional)</Text>
      <TextInput
        value={icon}
        onChangeText={setIcon}
        accessibilityLabel="Icon"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-icon`}
      />

      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failureMessage(pending.error, failure)}
        </Text>
      )}

      <View style={styles.pair}>
        <MnBtn
          label="Save"
          style={styles.grow}
          loading={pending.isPending}
          disabled={name.trim() === ''}
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
  content: { padding: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  header: { gap: Space.s2 },
  card: { gap: Space.s2 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    minHeight: 44,
    gap: Space.s2,
  },
  rowMain: { flexDirection: 'row', alignItems: 'center', gap: Space.s2, flex: 1 },
  rowActions: { flexDirection: 'row', gap: Space.s2 },
  name: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
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
});
