/**
 * THE AUTHENTICATED BINARY FETCH — "fetch a non-JSON body through the session
 * and hand it to the user".
 *
 * Landed by step 204 for the receipt thumbnail and download, and reused by
 * family C (the tax export) and family E (the data export). Build one of these,
 * not three.
 *
 * ## Why a receipt image is not an `<img src>`
 *
 * `GET /v1/receipts/{id}/thumbnail` and `/download` answer `image/jpeg` **behind
 * the session**, with `X-Content-Type-Options: nosniff` and, on the download,
 * `Content-Disposition: attachment`. From the operation's own description:
 * *"a mobile client must therefore fetch the bytes with its own credentials
 * rather than handing the path to an image component"*. Nothing is presigned,
 * there is no public path, and there is no token in a query string — so a plain
 * `<img src="/api/v1/receipts/…">` is not merely unauthenticated, it is the shape
 * this API deliberately does not have.
 *
 * The bytes therefore come back as a `Blob`, are wrapped in an object URL, and
 * that URL is what an `<img>` renders. Which makes the revoke mandatory: an
 * object URL is a document-lifetime reference to a decoded image, so one per
 * list row that is never revoked is a leak that grows with every scroll. Callers
 * use `useObjectUrl` below rather than calling `URL.createObjectURL` themselves.
 */
import { sessionFetch } from './client'

export interface AuthenticatedFile {
  blob: Blob
  /** The server-built filename from `Content-Disposition`, when it sent one. */
  filename: string | null
  /** The `Content-Type` the server declared. Never sniffed — the route sends `nosniff`. */
  contentType: string
}

/**
 * `filename="…"` / `filename*=UTF-8''…` out of a `Content-Disposition` header.
 *
 * The name is used for a download prompt and for alternative text and nothing
 * else; it never becomes a path, a key or a URL. RFC 5987's `filename*` wins
 * when both are present, which is what the spec says and what a non-ASCII
 * merchant name needs.
 */
export function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header)
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      /* fall through to the plain form */
    }
  }
  const plain = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)/i.exec(header)
  const value = (plain?.[1] ?? plain?.[2])?.trim()
  return value ? value : null
}

/**
 * Fetch one non-JSON body through the session.
 *
 * Everything the session needs — the `__Host-` cookies, `X-Requested-With` and
 * the CSRF header — comes from `sessionFetch`, which also runs the shipped
 * single-flight refresh on a 401 and rejects a non-2xx as an `ApiError`. So a
 * 404 on another household's receipt, a 429 and an expired session all reach the
 * caller as the same error type every other screen already handles.
 */
export async function fetchAuthenticatedFile(
  path: string,
  init: { signal?: AbortSignal } = {}
): Promise<AuthenticatedFile> {
  const res = await sessionFetch(path, { method: 'GET', signal: init.signal })
  const blob = await res.blob()
  return {
    blob,
    filename: filenameFromDisposition(res.headers.get('content-disposition')),
    contentType: res.headers.get('content-type') ?? blob.type,
  }
}

/**
 * Hand a fetched file to the member as a save.
 *
 * The object URL is revoked on the next tick rather than kept: the browser has
 * already taken its own reference by the time the click returns, and a URL held
 * "just in case" is the leak this module exists to avoid.
 */
export function saveAuthenticatedFile(file: AuthenticatedFile, fallbackName: string): void {
  const url = URL.createObjectURL(file.blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = file.filename ?? fallbackName
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // A microtask is too early in Safari; a macrotask is late enough everywhere.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
