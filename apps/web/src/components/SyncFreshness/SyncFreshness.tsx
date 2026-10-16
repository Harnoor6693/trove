import { useUserFormat } from '@hooks/useUserFormat'
import type { AccountResponse } from '@api/types'

/**
 * How fresh a bank connection is (C-01r): "Synced 3 hours ago", with the exact
 * time in the user's own zone as the element's title and `dateTime`.
 *
 * Only a SimpleFIN account gets the relative wording, because only a SimpleFIN
 * account is refreshed by the scheduled sync. Every other kind keeps the line
 * it always had, so turning the switch on shows nothing new there.
 */
export function SyncFreshness({
  account,
}: {
  account: Pick<AccountResponse, 'sourceKind' | 'lastSyncedAt'>
}) {
  const format = useUserFormat()
  const at = account.lastSyncedAt
  if (!at) return <>Never synced</>
  if (account.sourceKind !== 'SIMPLEFIN') return <>Last synced {format.formatDateTime(at)}</>
  return (
    <time dateTime={at} title={format.formatDateTime(at)}>
      Synced {format.formatRelativeTime(at)}
    </time>
  )
}
