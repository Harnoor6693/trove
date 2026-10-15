import { useMutation, useQueryClient } from '@tanstack/react-query'
import { putPreferences } from '@api/preferences'
import { preferencesKey } from './usePreferences'
import type { ApiError } from '@api/client'
import type { Preferences, PreferencesPatch } from '@api/types'

export function useUpdatePreferencesMutation() {
  const qc = useQueryClient()
  return useMutation<Preferences, ApiError, PreferencesPatch>({
    mutationFn: putPreferences,
    onSuccess: (data) => {
      qc.setQueryData(preferencesKey, data)
    },
  })
}
