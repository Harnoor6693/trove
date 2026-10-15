/**
 * Preferences — §4.2 `GET`/`PUT /v1/me/preferences`.
 *
 * The body is `{region, locale, timezone, onboardingCompleted}`. `currency`
 * and `country` used to be assumed here and are not: they live on `users` and
 * are read from `GET /v1/auth/me` (§4.2, §3 V3).
 *
 * These three fields are what `useUserFormat()` formats money and dates with,
 * so the whole app's presentation follows the server, not the device locale.
 */

import { api } from './client';
import { unwrap } from './errors';
import type { ApiResponse, UserPreferences, UserPreferencesPatch } from './types';

export async function fetchPreferences(): Promise<UserPreferences> {
  const response = await api.get<ApiResponse<UserPreferences>>('/v1/me/preferences');
  return unwrap(response.data);
}

/** `PUT` is a partial patch (§4.2). */
export async function updatePreferences(patch: UserPreferencesPatch): Promise<UserPreferences> {
  const response = await api.put<ApiResponse<UserPreferences>>('/v1/me/preferences', patch);
  return unwrap(response.data);
}
