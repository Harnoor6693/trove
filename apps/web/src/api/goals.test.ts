/** The goals API layer, at the level the screens do not exercise end to end. */
import { describe, expect, it } from 'vitest'
import { createGoal, deleteGoal, listGoals, updateGoal } from './goals'
import { mswState, resetMswState } from '@/test/msw/handlers'

describe('goals', () => {
  it('lists, creates, updates and deletes a goal', async () => {
    resetMswState()
    const before = await listGoals()
    expect(before).toHaveLength(1)

    const created = await createGoal({ name: 'New car', targetAmount: '20000.00' })
    expect(created.currentAmount).toBe('0.0000')
    expect(created.achieved).toBe(false)

    const updated = await updateGoal(created.id, {
      name: created.name,
      targetAmount: created.targetAmount,
      currentAmount: created.targetAmount,
      deadline: null,
    })
    // `achieved` is derived server-side from currentAmount >= targetAmount.
    expect(updated.achieved).toBe(true)

    await deleteGoal(created.id)
    expect(mswState.goals.some((g) => g.id === created.id)).toBe(false)
  })
})
