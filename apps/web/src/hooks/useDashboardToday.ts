import { useQuery } from '@tanstack/react-query'
import { getToday } from '@api/dashboard'

export const dashboardTodayKey = ['dashboard', 'today'] as const

export function useDashboardToday() {
  return useQuery({
    queryKey: dashboardTodayKey,
    queryFn: getToday,
    staleTime: 5 * 60 * 1000,
  })
}
