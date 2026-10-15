/**
 * A mutation whose request body is a CREDENTIAL, and therefore never a mutation
 * variable.
 *
 * TanStack Query keeps `variables` on a settled mutation for the gc window after
 * its last observer detaches, so a password, a TOTP code or a `StepUpRequest`
 * passed the ordinary way stays readable from
 * `queryClient.getMutationCache().getAll()` minutes after the dialog that
 * collected it has closed. `useCreateWebhookMutation` and
 * `useUpdateAiSettingsMutation` already dodge this for one field each with a ref;
 * family E has five call sites that need it for the WHOLE body — change password,
 * TOTP enrol, TOTP confirm, TOTP disable, and the two step-up operations — so the
 * dodge is generalised here rather than copied five times.
 *
 * The mutation's own `variables` type is `void`. There is nothing to leak.
 *
 * ## The RESPONSE lives in the same cache entry
 *
 * `variables` is only half of a settled mutation's state: `data` sits in the
 * same entry under the same gc rule, and three of this family's responses ARE
 * one-time secrets — the TOTP secret from `setupTotp`, the recovery codes from
 * `confirmTotp`, and the minted reset link `useMintResetLinkMutation` shows an
 * admin once. `gcTime: 0` drops the whole entry, body and response alike, as
 * soon as the last observer detaches — which is the dialog unmounting, by its
 * button, by Escape or by a click on the backdrop, so it holds for all three
 * where a `reset()` in one `onClose` handler would only cover the button.
 * Nothing reads a mutation's state after its dialog is gone; while it is open
 * the observer keeps the entry alive exactly as before.
 */
import { useRef } from 'react'
import { useMutation, type UseMutationOptions } from '@tanstack/react-query'
import type { ApiError } from '@api/client'

export function useCredentialMutation<TData, TBody>(
  fn: (body: TBody) => Promise<TData>,
  options: Omit<UseMutationOptions<TData, ApiError, void>, 'mutationFn'> = {}
) {
  const bodyRef = useRef<TBody | null>(null)

  const mutation = useMutation<TData, ApiError, void>({
    gcTime: 0,
    ...options,
    mutationFn: () => fn(bodyRef.current as TBody),
    // Cleared here rather than inside `mutationFn` so a retry still carries it,
    // and cleared on failure as well as success — a refused password must not
    // linger any longer than an accepted one.
    onSettled: (...args) => {
      bodyRef.current = null
      options.onSettled?.(...args)
    },
  })

  /** Run the mutation with a body that is held out of band and dropped when it settles. */
  function submit(body: TBody, mutateOptions?: Parameters<typeof mutation.mutate>[1]): void {
    bodyRef.current = body
    mutation.mutate(undefined, mutateOptions)
  }

  return { ...mutation, submit }
}
