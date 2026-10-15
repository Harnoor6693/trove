/**
 * A receipt image, fetched through the session and rendered from an object URL
 * that is revoked when this component goes.
 *
 * **This is not an `<img src="/api/…">`.** `GET /v1/receipts/{id}/thumbnail` is
 * authenticated: it is a photograph of the household's spending, and an
 * unauthenticated image path would be a way to read it without a session.
 * Nothing is presigned and there is no token in a query string, so the bytes
 * have to be fetched with the caller's own credentials — which is what
 * `useReceiptImage` does, and why it is the only place in the app that creates
 * an object URL for a rendered image.
 *
 * The alternative text is the MERCHANT AND DATE, not "receipt": a screen reader
 * user scanning a list needs to know which receipt this is, and a dozen images
 * all announced as "receipt" are a dozen images they cannot tell apart.
 */
import { useReceiptImage } from '@hooks/useReceiptImage'
import { useUserFormat } from '@hooks/useUserFormat'
import styles from './Receipts.module.css'

export interface ReceiptThumbnailProps {
  /** A path on this API, from `thumbnailPathFor`. Null when there is no thumbnail. */
  path: string | null
  merchant: string | null
  date: string | null
  className?: string
  'data-testid'?: string
}

export function ReceiptThumbnail({
  path,
  merchant,
  date,
  className,
  'data-testid': testId,
}: ReceiptThumbnailProps) {
  const image = useReceiptImage(path)
  const format = useUserFormat()

  const who = merchant ?? 'an unnamed merchant'
  const when = date ? format.formatDate(date) : 'no date yet'
  const alt = `Receipt from ${who}, ${when}`

  if (image.url === null) {
    return (
      <span
        className={className ?? styles.thumbPlaceholder}
        data-testid={testId}
        role="img"
        aria-label={image.isLoading ? `Loading the image of ${alt}` : `No image for ${alt}`}
      />
    )
  }

  return (
    <img
      className={className ?? styles.thumb}
      src={image.url}
      alt={alt}
      data-testid={testId}
    />
  )
}
