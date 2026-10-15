import { EmptyState } from './EmptyState'

export interface NotConnectedYetProps {
  /** What this screen will show once it is wired, e.g. "Your transactions". */
  what: string
  /** The `trove-core` route that will supply it, e.g. `GET /v1/transactions`. */
  endpoint: string
}

/**
 * The honest placeholder for a screen whose data layer lands in step 20b.
 *
 * From the product review: *no number that could be mistaken for the user's money
 * may originate anywhere but the API*. A page with no live endpoint therefore
 * shows this — never a fixture, never a plausible-looking figure. The route stays
 * in the router so the nav does not lie about what exists, and the endpoint it is
 * waiting on is named so the gap is legible rather than mysterious.
 */
export function NotConnectedYet({ what, endpoint }: NotConnectedYetProps) {
  return (
    <EmptyState
      variant="pending"
      badge="Not connected yet (step 20b)"
      title={`${what} isn't wired up yet`}
      data-testid="not-connected-yet"
    >
      <p>
        This screen is waiting on <code>{endpoint}</code>. Nothing is shown here rather than
        placeholder figures, so no number on this page can be mistaken for your money.
      </p>
    </EmptyState>
  )
}
