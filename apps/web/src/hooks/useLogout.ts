/**
 * The logout action, wired for real.
 *
 * Defect I2: `authStore.logout` was fully implemented and imported by zero
 * components — there was no sign-out control anywhere in the app, which is half
 * of why a self-hoster who could not finish onboarding was permanently stuck.
 * This hook is the single caller, used by the nav, Settings, the onboarding
 * footer and every diagnostic screen.
 *
 * It clears the react-query cache as well as the session: household financial
 * data must not survive in memory for whoever uses the browser next.
 */
import { useCallback } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@stores/authStore'

export function useLogout(): () => Promise<void> {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const logout = useAuthStore((s) => s.logout)

  return useCallback(async () => {
    await logout()
    queryClient.clear()
    navigate('/login', { replace: true })
  }, [logout, queryClient, navigate])
}
