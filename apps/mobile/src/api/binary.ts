/**
 * The authenticated binary fetch — the second of the two mechanics step 214
 * lands for the families that follow.
 *
 * Family D uses it for the receipt image and its thumbnail; **family C reuses
 * it for the tax export and family E for the data export**, which is why it is
 * a module of its own rather than three lines inside `receipts.ts`.
 *
 * ## Why an `<Image source={{uri}}>` against the route cannot work
 *
 * `GET /v1/receipts/{id}/thumbnail` and `.../download` return `image/jpeg`
 * **behind the bearer session**. Nothing is presigned, there is no public path
 * and there is deliberately no token in a query string — the spec says so in
 * both operations, because a photograph of the household's spending reachable
 * without a session is a way to read the ledger. React Native's `Image` issues
 * its own request from native code and cannot carry the `Authorization`
 * header, so handing it the path yields a 401 and a broken image.
 *
 * The three things this module therefore refuses to do, each of which is the
 * shortcut somebody reaches for first:
 *
 *  - **No token in a URL.** Not in a query string, not in a fragment. The
 *    credential travels in a header, on a request the axios instance built.
 *  - **No `Linking.openURL` of an API route.** That hands the path to the
 *    system browser, which has no session, and leaks the id into the browser's
 *    own history.
 *  - **Nothing written to disk and nothing cached at module scope.** The bytes
 *    become a `data:` URI held by the caller — a React Query entry that lives
 *    and dies with the screen — so there is no file to clean up and nothing
 *    that could outlive `clearSession()`. `expo-file-system` is not a
 *    dependency of this app and adding one is not this step's to make.
 */

import { api } from './client';

/** The image types the receipt routes serve. Anything else is not rendered. */
const RENDERABLE = ['image/jpeg', 'image/png'];

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/**
 * Base64 for a byte array.
 *
 * Written out rather than reached for: Hermes has no `btoa`, React Native
 * exposes its own encoder only as a deep internal path (`react-native/
 * Libraries/Utilities/binaryToBase64`) that is not part of its public surface,
 * and adding a dependency is out of scope for a client step. Twenty lines and
 * a unit test are cheaper than either.
 */
export function toBase64(bytes: Uint8Array): string {
  let out = '';
  const full = bytes.length - (bytes.length % 3);
  for (let i = 0; i < full; i += 3) {
    const triple = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    out +=
      BASE64_ALPHABET[(triple >> 18) & 63] +
      BASE64_ALPHABET[(triple >> 12) & 63] +
      BASE64_ALPHABET[(triple >> 6) & 63] +
      BASE64_ALPHABET[triple & 63];
  }
  const remaining = bytes.length - full;
  if (remaining === 1) {
    const chunk = bytes[full] << 16;
    out += BASE64_ALPHABET[(chunk >> 18) & 63] + BASE64_ALPHABET[(chunk >> 12) & 63] + '==';
  } else if (remaining === 2) {
    const chunk = (bytes[full] << 16) | (bytes[full + 1] << 8);
    out +=
      BASE64_ALPHABET[(chunk >> 18) & 63] +
      BASE64_ALPHABET[(chunk >> 12) & 63] +
      BASE64_ALPHABET[(chunk >> 6) & 63] +
      '=';
  }
  return out;
}

/** Normalise whatever the XHR adapter handed back into bytes. */
function asBytes(payload: unknown): Uint8Array {
  if (payload instanceof Uint8Array) return payload;
  if (payload instanceof ArrayBuffer) return new Uint8Array(payload);
  if (ArrayBuffer.isView(payload)) {
    const view = payload as ArrayBufferView;
    return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
  }
  throw new Error('That image could not be read.');
}

/**
 * The type to put in the `data:` URI, or a refusal.
 *
 * `nosniff` is on these responses for a reason: a body whose bytes are not the
 * type the header claims is a stored-XSS vector against this very API. The
 * client half of that is **refusing** to build a `data:` URI for a type the
 * route is not allowed to serve — not quietly relabelling it as JPEG, which is
 * what this function used to do while its own comment claimed otherwise. The
 * receipt routes serve `image/jpeg`; the tax and data exports family C and
 * family E will pull through this same seam are the reason the guard has to be
 * real rather than documentation.
 *
 * A response with **no** content type at all is a different thing from a
 * response that names one this route may not serve: it is an adapter that did
 * not surface the header, and the download route's own answer is always JPEG,
 * so that one defaults rather than refuses.
 */
function contentTypeOf(headers: unknown): string {
  if (typeof headers !== 'object' || headers === null) return RENDERABLE[0];
  const raw = (headers as Record<string, unknown>)['content-type'];
  if (typeof raw !== 'string' || raw.trim() === '') return RENDERABLE[0];
  const type = raw.split(';')[0].trim().toLowerCase();
  if (!RENDERABLE.includes(type)) throw new Error('That image could not be read.');
  return type;
}

/**
 * Fetch a `/v1/...` path that answers with an image and return it as a
 * `data:` URI an `<Image>` can render.
 *
 * Goes through `api`, so it carries the bearer token from the in-memory
 * session, resolves the base URL the same way every other call does, throws
 * the same `NoServerConnectedError` on an unpaired app, and shares the one
 * single-flight refresh on a 401. Nothing here reads the token.
 */
export async function fetchImageAsDataUri(path: string): Promise<string> {
  const response = await api.get<ArrayBuffer>(path, { responseType: 'arraybuffer' });
  // Checked before the bytes are encoded: there is no reason to base64 a body
  // this function is going to refuse.
  const type = contentTypeOf(response.headers);
  const bytes = asBytes(response.data);
  if (bytes.length === 0) throw new Error('That image could not be read.');
  return `data:${type};base64,${toBase64(bytes)}`;
}
