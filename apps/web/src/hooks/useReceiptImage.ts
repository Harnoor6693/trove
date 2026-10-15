/**
 * An authenticated receipt image, as an object URL that is always revoked.
 *
 * A receipt image is not an `<img src>`: `GET /v1/receipts/{id}/thumbnail` is
 * behind the session, with `nosniff`, and nothing about it is presigned. So the
 * bytes are fetched through the shipped client and wrapped in an object URL —
 * and an object URL is a document-lifetime reference to a decoded image, so ONE
 * PER LIST ROW THAT IS NEVER REVOKED IS A LEAK THAT GROWS WITH EVERY SCROLL.
 *
 * This hook is the only place in the app that calls `URL.createObjectURL` for a
 * rendered image, and it revokes on three occasions: when the path changes, when
 * the component unmounts, and when a fetch that was already in flight resolves
 * after either of those. The in-flight request is aborted too, so a fast scroll
 * does not queue a hundred image fetches nobody will look at.
 *
 * It deliberately does NOT use React Query. A blob in the query cache would
 * outlive the component that asked for it, survive a route change, and still be
 * there after a logout — a photograph of the household's spending, held in
 * memory for the next person at the keyboard. State that must die with the
 * component belongs in the component.
 *
 * The one loaded result is stamped with the path it came from, and the return
 * value is derived from that stamp rather than reset by the effect: an effect
 * body that calls `setState` synchronously is a cascading render, and the lint
 * gate rejects one.
 */
import { useEffect, useState } from 'react'
import { fetchAuthenticatedFile } from '@api/binary'
import { toApiError, type ApiError } from '@api/client'

export interface ReceiptImage {
  /** An object URL to put in `<img src>`, or null while loading or on failure. */
  url: string | null
  isLoading: boolean
  error: ApiError | null
}

interface LoadedImage {
  /** The path this result came from. A result for any other path is ignored. */
  path: string
  url: string | null
  error: ApiError | null
}

/**
 * @param path A path on this API, as `thumbnailPathFor` returns it. `null`
 *             disables the hook — the receipt has no thumbnail, or the row that
 *             would name it has not loaded yet.
 */
export function useReceiptImage(path: string | null): ReceiptImage {
  const [loaded, setLoaded] = useState<LoadedImage | null>(null)

  useEffect(() => {
    if (path === null) return

    const controller = new AbortController()
    let objectUrl: string | null = null
    let cancelled = false

    fetchAuthenticatedFile(path, { signal: controller.signal })
      .then((file) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(file.blob)
        setLoaded({ path, url: objectUrl, error: null })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoaded({ path, url: null, error: toApiError(err) })
      })

    return () => {
      cancelled = true
      controller.abort()
      // Revoked whether the URL was rendered, or arrived after the unmount.
      if (objectUrl !== null) URL.revokeObjectURL(objectUrl)
    }
  }, [path])

  // A result for a path we are no longer showing is stale: its object URL has
  // already been revoked by the cleanup above, so rendering it would be a broken
  // image rather than the previous one.
  const current = loaded !== null && loaded.path === path ? loaded : null
  return {
    url: current?.url ?? null,
    isLoading: path !== null && current === null,
    error: current?.error ?? null,
  }
}
