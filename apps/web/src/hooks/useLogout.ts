/**
 * The logout action, wired for real.
 *
 * Defect I2: `authStore.logout` was fully implemented and imported by zero
 * components — there was no sign-out control anywhere in the app, which is half
 * of why a self-hoster who could not finish onboarding was permanently stuck.
 * This hook is the single caller, used by the nav, Settings, the onboarding
 * footer and every diagnostic screen.
 *
 * The react-query cache is cleared by the auth store whenever a session ends
 * (sign-out or expiry), so household data does not outlive the session.
 */
import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@stores/authStore'

export function useLogout(): () => Promise<void> {
  const navigate = useNavigate()
  const logout = useAuthStore((s) => s.logout)

  return useCallback(async () => {
    await logout()
    navigate('/login', { replace: true })
  }, [logout, navigate])
}
