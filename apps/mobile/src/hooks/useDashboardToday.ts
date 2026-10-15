/**
 * `GET /v1/dashboard/today`.
 *
 * The response carries the day and the zone it was computed in, so the screen
 * renders `date`/`timezone` from the payload rather than asking the device
 * what day it is. There is nothing to recompute here and nothing to convert:
 * `byCurrency` is one subtotal per currency and the entries are never added
 * together.
 */

import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { fetchDashboardToday } from '@api/dashboard';
import type { DashboardTodayResponse } from '@api/types';
import { dashboardKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { dashboardKeys } from './queryKeys';

export function useDashboardToday(): UseQueryResult<DashboardTodayResponse> {
  return useQuery({
    queryKey: dashboardKeys.today(),
    queryFn: fetchDashboardToday,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}
