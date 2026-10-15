/**
 * `/v1/me/preferences` — §4.2: `{region, locale, timezone, onboardingCompleted}`.
 *
 * `currency`/`country` moved OFF this resource onto `users` (read via
 * `/v1/auth/me`), so nothing here can change the reporting currency.
 */
import { api, unwrap } from './client'
import type { ApiResponse, Preferences, PreferencesPatch } from './types'

export async function getPreferences(): Promise<Preferences> {
  const res = await api.get<ApiResponse<Preferences>>('/v1/me/preferences')
  return unwrap(res)
}

/**
 * A patch — the spec's fields merge, with an explicit `null` a no-op except on
 * `region`, where it clears. `currency`/`country` are not accepted here even
 * though they are readable on `Preferences` — `PreferencesPatch` (not
 * `Partial<Preferences>`) is what keeps that unwritable at the type level.
 */
export async function putPreferences(patch: PreferencesPatch): Promise<Preferences> {
  const res = await api.put<ApiResponse<Preferences>>('/v1/me/preferences', patch)
  return unwrap(res)
}
