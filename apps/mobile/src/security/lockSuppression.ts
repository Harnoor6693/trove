/**
 * Marks stretches where the app is about to be sent to the background by the
 * system's own UI: the camera app, the photo library, a permission prompt.
 * `AppLock` ignores those trips, so "Immediately" does not re-lock the app and
 * throw away the photo the member is in the middle of taking (B5-12).
 */
let depth = 0;

export function isLockSuppressed(): boolean {
  return depth > 0;
}

export async function withLockSuppressed<T>(run: () => Promise<T>): Promise<T> {
  depth += 1;
  try {
    return await run();
  } finally {
    depth -= 1;
  }
}
