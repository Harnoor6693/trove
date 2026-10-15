/**
 * Which way the money went, for one transaction — the ONE place this client
 * turns the server's `(amount, type)` pair into a signed figure.
 *
 * **Why this module exists.** `transaction.amount` is always the ABSOLUTE value
 * and `TransactionType` carries the direction; the server says so in as many
 * words (`core/.../TransactionType.java`: "The sign lives here, not on the
 * amount"). Three screens used to write `type === 'CREDIT' ? amount : -amount`
 * inline, which made every non-CREDIT row an outflow — so a REFUND, which the
 * server's own aggregates treat as money coming BACK (`DashboardService`:
 * `case REFUND -> row.getAmount().negate()` against a spend total `DEBIT` adds
 * to; `EnvelopeSpendService`: "spend is DEBIT less REFUND"), rendered with a
 * leading minus, as if more money had left the account.
 *
 * **What the contract does and does not state.** It states two directions and
 * declines to state three:
 *  - out — `DEBIT` ("money leaving the account") and `FEE` ("a bank or service
 *    charge");
 *  - in — `CREDIT` ("money entering the account") and `REFUND` ("a reversal of
 *    an earlier debit", netted against spend);
 *  - unstated — `INTEREST` is "interest earned **or** charged", a `TRANSFER`
 *    leg can be either side of a movement between the household's own accounts,
 *    and `OTHER` is by definition whatever a source could not map.
 *
 * For the third group this module returns `'unknown'` and the screens render the
 * magnitude the server sent, unsigned and untinted, with the type beside it. A
 * guessed sign on a row the contract refuses to give a direction to would be a
 * number that looks like the member's money and came from this app's arithmetic,
 * which is the one thing no screen here may do. It is recorded as a contract gap
 * rather than guessed.
 */
import type { TransactionType } from './types'

/** Out of the household, into it, or not stated by the contract. */
export type MoneyDirection = 'out' | 'in' | 'unknown'

/** The direction the contract gives this type, or `'unknown'` when it gives none. */
export function directionOf(type: TransactionType): MoneyDirection {
  switch (type) {
    case 'DEBIT':
    case 'FEE':
      return 'out'
    case 'CREDIT':
    case 'REFUND':
      return 'in'
    case 'TRANSFER':
    case 'INTEREST':
    case 'OTHER':
      return 'unknown'
  }
}

/**
 * The amount as it should be displayed: negative for an outflow, positive for an
 * inflow, and the server's own magnitude, unchanged, when the direction is not
 * stated. The value stays a DECIMAL STRING throughout — nothing here parses it
 * into a float, because the string the server sent is the money and a round-trip
 * through `Number` is a re-scaling this client has no business doing.
 */
export function signedAmount(row: { type: TransactionType; amount: string }): string {
  return directionOf(row.type) === 'out' ? `-${row.amount}` : row.amount
}

/**
 * Whether the figure may be tinted by sign. `false` for the unstated directions,
 * so an `INTEREST` row is not quietly coloured as income.
 */
export function isDirectional(type: TransactionType): boolean {
  return directionOf(type) !== 'unknown'
}
