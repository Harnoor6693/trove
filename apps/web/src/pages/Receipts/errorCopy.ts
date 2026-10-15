/**
 * The copy for a refused receipt operation, in one place.
 *
 * Four surfaces make these calls — the list, the upload box, the detail dialog's
 * Save, and the review verdict — and they must speak with one voice. The switch
 * covers exactly the slugs the receipt operations narrow `ErrorResponse.type` to
 * and invents copy for nothing else.
 *
 * It lives in its own module rather than being exported from a component file,
 * which would lose fast refresh and fail the lint gate.
 */
import type { ApiError } from '@api/client'

/** The 409 both patch routes answer while the extraction pipeline is still running. */
const PIPELINE_RUNNING =
  'This receipt is still being read. Wait for it to finish, then try again — a change made now would be overwritten by the pipeline.'

export function receiptErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'upload-too-large':
      return (
        error.detail ??
        'That image is larger than this server will store. Your household also has a total storage allowance, which an abandoned import preview can use up.'
      )
    case 'validation-error':
      return (
        error.fieldErrors[0]?.message ??
        error.detail ??
        'That image was refused. Only JPEG and PNG are accepted, and the file has to really be the format it claims.'
      )
    case 'conflict':
      return error.detail ?? PIPELINE_RUNNING
    case 'not-found':
      return 'That receipt is not in this household any more. It may have been deleted.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'That request was refused. Reload the page and try again.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again — your session is fine.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'That did not go through.'
  }
}

/**
 * What a receipt's status means to a member, in their words.
 *
 * `COMPLETE` with `reviewRequired` is the ORDINARY outcome on an install with no
 * vision model — "the image is stored and the fields are yours to fill in" — so
 * it is never phrased as a failure. `FAILED` after a rejected review is the
 * member's own decision, not a fault, and the image is still stored either way.
 */
export function receiptStatusLabel(status: string, reviewRequired: boolean): string {
  switch (status) {
    case 'UPLOADED':
      return 'Stored, waiting to be read'
    case 'VALIDATING':
      return 'Checking the image'
    case 'PARSING':
      return 'Reading the image'
    case 'EXTRACTING':
      return 'Pulling out the fields'
    case 'MATCHING':
      return 'Looking for a matching transaction'
    case 'TAX_SPLIT':
      return 'Working out the tax split'
    case 'SCORING':
      return 'Scoring what it read'
    case 'COMPLETE':
      return reviewRequired ? 'Needs your review' : 'Done'
    case 'FAILED':
      return 'Not usable'
    default:
      return status
  }
}
