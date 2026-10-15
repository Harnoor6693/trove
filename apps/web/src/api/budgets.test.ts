/**
 * The budgets/envelopes API layer, at the level the screens do not exercise:
 * every operation reachable directly, not only through whichever path a
 * screen's happy path takes.
 */
import { describe, expect, it } from 'vitest'
import {
  createEnvelope,
  deleteBudgetPeriod,
  deleteEnvelope,
  getBudgetPeriod,
  getCurrentBudget,
  getEnvelope,
  listBudgetPeriods,
  listEnvelopes,
  updateBudgetPeriod,
  updateEnvelope,
} from './budgets'
import { mswState, resetMswState } from '@/test/msw/handlers'

describe('getCurrentBudget', () => {
  it('maps a 404 (no period this month) to null, not a thrown error', async () => {
    resetMswState()
    mswState.currentBudgetPeriodId = null
    await expect(getCurrentBudget()).resolves.toBeNull()
  })

  it('returns the period when one exists for the caller current month', async () => {
    resetMswState()
    const period = await getCurrentBudget()
    expect(period?.id).toBe('budget-1')
  })
})

describe('listBudgetPeriods / getBudgetPeriod', () => {
  it('lists every period and fetches one directly by id', async () => {
    resetMswState()
    const periods = await listBudgetPeriods()
    expect(periods.some((p) => p.id === 'budget-1')).toBe(true)

    const one = await getBudgetPeriod('budget-1')
    expect(one.month).toBe('2026-09-01')
  })

  it('404s for a period outside the household', async () => {
    resetMswState()
    await expect(getBudgetPeriod('nope')).rejects.toMatchObject({ status: 404, code: 'not-found' })
  })
})

describe('updateBudgetPeriod / deleteBudgetPeriod', () => {
  it('replaces totalIncome and budgetType', async () => {
    resetMswState()
    const updated = await updateBudgetPeriod('budget-1', {
      totalIncome: '6000.00',
      budgetType: 'ROLLOVER',
    })
    expect(updated.totalIncome).toBe('6000.00')
    expect(updated.budgetType).toBe('ROLLOVER')
  })

  it('removes the period and its envelopes', async () => {
    resetMswState()
    await deleteBudgetPeriod('budget-1')
    expect(mswState.budgetPeriods.some((p) => p.id === 'budget-1')).toBe(false)
    expect(mswState.envelopesByPeriod['budget-1']).toBeUndefined()
  })
})

describe('envelopes', () => {
  it('lists, fetches, creates, updates and deletes an envelope', async () => {
    resetMswState()
    const before = await listEnvelopes('budget-1')
    expect(before).toHaveLength(1)

    const fetched = await getEnvelope('budget-1', before[0].id)
    expect(fetched.categoryName).toBe('Groceries')

    const created = await createEnvelope('budget-1', { categoryId: 'cat-transport', allocated: '150.00' })
    expect(created.categoryId).toBe('cat-transport')
    expect(created.spent).toBe('0.0000')

    const updated = await updateEnvelope('budget-1', created.id, { allocated: '200.00' })
    expect(updated.allocated).toBe('200.00')

    await deleteEnvelope('budget-1', created.id)
    expect(mswState.envelopesByPeriod['budget-1']?.some((e) => e.id === created.id)).toBe(false)
  })
})
