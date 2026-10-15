/**
 * Save or share a file the app downloaded — the one place bytes touch disk.
 *
 * The bytes always come from the shared `api` client (which attaches and
 * refreshes the bearer token itself), so nothing here ever sees a credential
 * or a URL. They are written to a single file in the app's cache directory,
 * handed to the system share sheet (from which the member can save to Files /
 * Downloads or send to another app), and **deleted in `finally`** once the
 * sheet returns — a household archive or a tax year must not stay on the
 * device after the member is done with it.
 *
 * On Android the share target (Drive, Gmail, …) runs in Trove's own task with
 * its result sent back to Trove's activity, so `shareAsync` settles only when
 * the member is back in Trove — the target can still read the file while it
 * is open (observed on the Pixel_5_API_36 AVD, 29-MOBILE-FILES.md "Review
 * fixes"). An app killed mid-share skips `finally`; `sweepStaleExports` at
 * launch and before every share removes that leftover.
 *
 * ponytail: the whole file is held in memory once. Streaming via
 * `File.downloadFileAsync` is the upgrade path if archives outgrow memory,
 * and it would need the token passed as a header.
 */

import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';

export const SHARE_UNAVAILABLE_MESSAGE =
  'This device can’t open a share sheet, so the file could not be saved or shared.';

/** Keep only `[A-Za-z0-9._-]` and no leading dots, so a name can never leave the cache directory. */
function safeFileName(name: string): string {
  const cleaned = name.replace(/[^A-Za-z0-9._-]/g, '_').replace(/^\.+/, '');
  return cleaned === '' ? 'download' : cleaned;
}

/** The only names `shareBytes` is ever given; anything else in the cache is not ours. */
const EXPORT_FILE_NAME = /^(trove-export\.zip|tax-export-[A-Za-z0-9._-]*\.(csv|pdf))$/;

/**
 * Delete any export a previous share left behind — the app killed while the
 * share sheet was open skips the `finally` below. Runs at app start and before
 * every share. Best effort: it never throws.
 */
export function sweepStaleExports(): void {
  try {
    for (const entry of Paths.cache.list()) {
      if (entry instanceof File && EXPORT_FILE_NAME.test(entry.name)) entry.delete();
    }
  } catch {
    // An unreadable cache or a file already gone: the next sweep tries again.
  }
}

export async function shareBytes(
  bytes: ArrayBuffer,
  fileName: string,
  mimeType: string,
  uti?: string
): Promise<void> {
  if (!(await Sharing.isAvailableAsync())) throw new Error(SHARE_UNAVAILABLE_MESSAGE);
  sweepStaleExports();
  const name = safeFileName(fileName);
  const file = new File(Paths.cache, name);
  try {
    // `write` truncates an existing file (append defaults to false).
    file.write(new Uint8Array(bytes));
    await Sharing.shareAsync(file.uri, { mimeType, UTI: uti, dialogTitle: name });
  } finally {
    if (file.exists) file.delete();
  }
}
