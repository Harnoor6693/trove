/**
 * The categories/rules API layer, at the level the screens do not exercise
 * end to end: the rule mutations and the reorder body, called directly.
 */
import { describe, expect, it } from 'vitest'
import {
  createCategory,
  createCategoryRule,
  deleteCategory,
  deleteCategoryRule,
  listCategories,
  listCategoryRules,
  reorderCategoryRules,
  updateCategory,
  updateCategoryRule,
} from './categories'
import { mswState, resetMswState } from '@/test/msw/handlers'

describe('categories', () => {
  it('lists, creates, updates and deletes a household category', async () => {
    resetMswState()
    const before = await listCategories()
    expect(before.length).toBeGreaterThan(0)

    const created = await createCategory({ name: 'Pets', parentId: null, icon: null })
    expect(created.isSystem).toBe(false)

    const renamed = await updateCategory(created.id, { name: 'Pet care', parentId: null, icon: null })
    expect(renamed.name).toBe('Pet care')

    await deleteCategory(created.id)
    expect(mswState.categories.some((c) => c.id === created.id)).toBe(false)
  })

  it('403s renaming a system category', async () => {
    resetMswState()
    await expect(
      updateCategory('cat-groceries', { name: 'x', parentId: null, icon: null })
    ).rejects.toMatchObject({ status: 403, code: 'forbidden' })
  })
})

describe('merchant rules', () => {
  it('lists, updates and deletes a rule', async () => {
    resetMswState()
    const rules = await listCategoryRules()
    expect(rules[0].merchant).toBe('STARBUCKS')

    const updated = await updateCategoryRule(rules[0].id, {
      merchant: 'STARBUCKS CANADA',
      matchType: 'EXACT',
      categoryId: rules[0].categoryId,
    })
    expect(updated.merchant).toBe('STARBUCKS CANADA')

    await deleteCategoryRule(rules[0].id)
    expect(mswState.categoryRules.some((r) => r.id === rules[0].id)).toBe(false)
  })

  it('creates a rule with the server default confidence when none is sent', async () => {
    resetMswState()
    const created = await createCategoryRule({
      merchant: 'NEW MERCHANT',
      matchType: 'PREFIX',
      categoryId: 'cat-transport',
    })
    expect(created.confidence).toBe('0.900')
  })

  it('reorders by sending the full ordered id list', async () => {
    resetMswState()
    await reorderCategoryRules({ orderedIds: ['rule-2', 'rule-1'] })
    expect(mswState.categoryRules.find((r) => r.id === 'rule-2')?.priority).toBe(0)
    expect(mswState.categoryRules.find((r) => r.id === 'rule-1')?.priority).toBe(1)
  })
})
