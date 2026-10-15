import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { QlModal } from './QlModal'

describe('QlModal accessibility (B4-08)', () => {
  it('keeps Tab focus inside the dialog', async () => {
    const user = userEvent.setup()
    render(
      <>
        <button>behind</button>
        <QlModal isOpen onClose={() => {}} title="One">
          <input aria-label="first" />
        </QlModal>
      </>
    )
    const dialog = screen.getByRole('dialog')
    screen.getByLabelText('first').focus()
    for (let i = 0; i < 5; i++) {
      await user.tab()
      expect(dialog).toContainElement(document.activeElement as HTMLElement)
    }
    for (let i = 0; i < 5; i++) {
      await user.tab({ shift: true })
      expect(dialog).toContainElement(document.activeElement as HTMLElement)
    }
  })

  it('gives each open modal its own title id and label', () => {
    render(
      <>
        <QlModal isOpen onClose={() => {}} title="One">a</QlModal>
        <QlModal isOpen onClose={() => {}} title="Two">b</QlModal>
      </>
    )
    const ids = screen.getAllByRole('heading').map((h) => h.id)
    expect(new Set(ids).size).toBe(2)
    expect(screen.getByRole('dialog', { name: 'One' })).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Two' })).toBeInTheDocument()
  })

  it('Escape closes only the top modal and scroll stays locked until the last closes', async () => {
    const user = userEvent.setup()
    const closeA = vi.fn()
    const closeB = vi.fn()
    const { rerender } = render(
      <>
        <QlModal isOpen onClose={closeA} title="A">a</QlModal>
        <QlModal isOpen onClose={closeB} title="B">b</QlModal>
      </>
    )
    await user.keyboard('{Escape}')
    expect(closeB).toHaveBeenCalledTimes(1)
    expect(closeA).not.toHaveBeenCalled()

    rerender(
      <>
        <QlModal isOpen onClose={closeA} title="A">a</QlModal>
        <QlModal isOpen={false} onClose={closeB} title="B">b</QlModal>
      </>
    )
    expect(document.body.style.overflow).toBe('hidden')
    await user.keyboard('{Escape}')
    expect(closeA).toHaveBeenCalledTimes(1)

    rerender(
      <>
        <QlModal isOpen={false} onClose={closeA} title="A">a</QlModal>
        <QlModal isOpen={false} onClose={closeB} title="B">b</QlModal>
      </>
    )
    expect(document.body.style.overflow).toBe('')
  })
})
