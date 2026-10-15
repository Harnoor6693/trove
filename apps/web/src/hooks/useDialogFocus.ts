/**
 * Dialog focus, for the modal surfaces this app opens over a page.
 *
 * `QlModal` already gives a dialog its role, its `aria-modal` and its Escape
 * handler, but it does not move focus. Without this hook a keyboard or screen
 * reader user opens a dialog and is left standing on the trigger behind it, and
 * on close is dropped at the top of the document.
 *
 * Attach the returned ref to an element inside the dialog. On open, focus moves
 * to the first focusable descendant (or to the container itself, which is given
 * `tabIndex={-1}` by the caller); on close, focus returns to whatever had it
 * before — which is the trigger, so the member's place in the page is kept.
 *
 * It is a hook rather than a change to `QlModal` because `QlModal` is shared
 * with every other family in this wave and a behaviour change there would land
 * in four branches at once.
 */
import { useEffect, useRef } from 'react'

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function useDialogFocus<T extends HTMLElement>(isOpen: boolean) {
  const containerRef = useRef<T | null>(null)
  const returnToRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!isOpen) return
    returnToRef.current = document.activeElement as HTMLElement | null
    // The dialog's content is portalled and mounts in the same commit, so a
    // frame is not needed — but the ref is read after paint either way.
    const container = containerRef.current
    if (container) {
      const first = container.querySelector<HTMLElement>(FOCUSABLE)
      ;(first ?? container).focus()
    }
    return () => {
      const returnTo = returnToRef.current
      if (returnTo && document.contains(returnTo)) returnTo.focus()
    }
  }, [isOpen])

  return containerRef
}
