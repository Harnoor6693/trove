/**
 * Redaction for anything that reaches a log sink.
 *
 * Release builds keep `console.error` output in logcat / os_log, and an
 * `AxiosError` carries `config.headers.Authorization`, the request body and
 * the full response body on it. Handing one of those to `console.error`
 * writes the user's bearer token — and whatever ledger rows the failing call
 * returned — into a device log any other app with log access can read
 * (readiness gap P4-20).
 *
 * `sanitise()` therefore never returns the original object. It extracts four
 * scalar fields and scrubs each of them.
 */

/** Fields that survive sanitisation. Nothing else is ever logged. */
export interface SanitisedError {
  code: string;
  message: string;
  status?: number;
  method?: string;
  /** Request path only — query strings can carry tokens or search terms. */
  url?: string;
}

const REDACTED = '[redacted]';

/** Key names whose following value is a secret, in any punctuation style. */
const SECRET_KEY =
  /(?:password|passcode|secret|token|apikey|api_key|authorization|challengetoken|refreshtoken|accesstoken)/;

const SECRET_PATTERNS: ReadonlyArray<
  readonly [RegExp, (match: string, ...groups: string[]) => string]
> = [
  // `Bearer eyJ...`
  [/\bBearer\s+[\w.~+/-]+=*/gi, () => `Bearer ${REDACTED}`],
  // A bare JWT anywhere in a string.
  [/\beyJ[\w-]*\.[\w-]*\.[\w-]*/g, () => '[redacted-jwt]'],
  // `"password":"..."`, `refreshToken=...`, `apiKey: ...` and friends.
  [
    new RegExp(`(${SECRET_KEY.source}\\W{1,3})[^\\s,;)}\\]"']+`, 'gi'),
    (_match, prefix) => `${prefix}${REDACTED}`,
  ],
];

/** Scrub secret-shaped substrings out of a free-text string. */
export function redactSecrets(text: string): string {
  let out = text;
  for (const [pattern, replacement] of SECRET_PATTERNS) {
    out = out.replace(pattern, replacement);
  }
  return out;
}

interface AxiosLikeError {
  message?: unknown;
  response?: { status?: unknown };
  config?: { method?: unknown; url?: unknown; baseURL?: unknown };
}

function asRecord(value: unknown): AxiosLikeError | null {
  return typeof value === 'object' && value !== null ? (value as AxiosLikeError) : null;
}

/** Strip query and fragment; keep the path so a failure is still locatable. */
function pathOnly(url: unknown): string | undefined {
  if (typeof url !== 'string' || url === '') return undefined;
  const withoutFragment = url.split('#')[0];
  const withoutQuery = withoutFragment.split('?')[0];
  // An absolute URL keeps its origin; a relative one is already a path.
  return redactSecrets(withoutQuery);
}

/**
 * Reduce any thrown value to the four scalars that are safe to log.
 *
 * The input is never returned, referenced or spread into the result, so no
 * header, cookie, request body or response body can escape through it.
 */
export function sanitise(code: string, error: unknown): SanitisedError {
  const record = asRecord(error);

  let message: string;
  if (record && typeof record.message === 'string') {
    message = record.message;
  } else if (typeof error === 'string') {
    message = error;
  } else if (error === undefined || error === null) {
    message = 'Unknown error';
  } else {
    message = Object.prototype.toString.call(error);
  }

  const status = typeof record?.response?.status === 'number' ? record.response.status : undefined;
  const method =
    typeof record?.config?.method === 'string' ? record.config.method.toUpperCase() : undefined;
  const url = pathOnly(record?.config?.url);

  const result: SanitisedError = { code, message: redactSecrets(message) };
  if (status !== undefined) result.status = status;
  if (method !== undefined) result.method = method;
  if (url !== undefined) result.url = url;
  return result;
}
