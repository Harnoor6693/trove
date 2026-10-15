/**
 * Path-parameter safety for every `/v1/...{id}` call in this app.
 *
 * Two separate defects live here, and both are real on a phone:
 *
 *  1. **An id reaches these modules from outside the app.** The import screen
 *     invites the member to paste a batch id, and `app.config.ts` declares the
 *     `trove` URL scheme, so `useLocalSearchParams()` on the transaction,
 *     account and import screens is also reachable from a deep link the member
 *     did not compose. A value is therefore untrusted input, not a uuid.
 *  2. **Template interpolation is not URL construction.** axios concatenates
 *     `baseURL` and the path as strings, and the platform's URL layer then
 *     resolves dot segments before the request goes out: an id of
 *     `../accounts/<uuid>` turns `DELETE /v1/imports/{id}` — "undo this
 *     import" — into `DELETE /v1/accounts/<uuid>`, which the contract
 *     documents as deleting the account *and every transaction on it*, sent
 *     with the member's own bearer token under a confirmation that promised
 *     something else entirely.
 *
 * `pathSegment` closes (2) for every caller: an encoded segment can no longer
 * contain `/` or `.` runs that mean anything to a resolver. `isUuid` closes
 * (1) at the edge, so a pasted or deep-linked value that is not an id is a
 * screen state rather than a request.
 */

/** The canonical 8-4-4-4-12 hex form every id in this contract is written in. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Is this string an id the contract could have issued?
 *
 * Used by screens before they navigate or query — never as a substitute for
 * `pathSegment`, which every call site applies regardless.
 */
export function isUuid(value: string): boolean {
  return UUID.test(value);
}

/**
 * Encode one value for use as a single path segment.
 *
 * `encodeURIComponent` escapes `/`, so nothing a caller passes can add a
 * segment, and a traversal payload arrives at the server as the literal
 * nonsense it is — a 404 on the route the caller actually named — instead of
 * being resolved into a different operation.
 */
export function pathSegment(value: string): string {
  return encodeURIComponent(value);
}

/**
 * The token out of a link the member pasted (step 215, family E).
 *
 * Two of this contract's tokens are handed to a person as a **web** URL, not
 * as a token, because the server has one public origin and no idea a phone app
 * exists: a reset link is `{publicUrl}/reset-password/{token}` and an
 * invitation is `{publicUrl}/invite/{token}`. In both the token is the last
 * path segment, so taking that segment handles the whole link, a link with a
 * trailing slash, and a bare token pasted on its own — and the member is not
 * asked to do string surgery on a credential.
 *
 * Query and fragment are dropped: neither carries the token, and a pasted link
 * that picked up a tracking parameter would otherwise be sent as part of it
 * and refused as invalid — which, on a route whose only answer is one
 * undifferentiated "not valid", is indistinguishable from a spent link.
 *
 * This is extraction, not validation. The result still goes through
 * `pathSegment` at the call site like every other externally-supplied value.
 */
export function tokenFromLink(pasted: string): string {
  const trimmed = pasted.trim();
  if (trimmed === '') return '';
  const withoutQuery = trimmed.split(/[?#]/)[0];
  const segments = withoutQuery.split('/').filter((segment) => segment !== '');
  return segments.length === 0 ? '' : segments[segments.length - 1];
}
