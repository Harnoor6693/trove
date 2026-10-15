/**
 * Choosing a statement file on the device for `POST /v1/imports`.
 *
 * The picker is `expo-file-system`'s `File.pickFileAsync` — the system
 * document picker (Storage Access Framework on Android, the Files picker on
 * iOS). It is opened for any type (`*\/*`): Android has no registered MIME
 * type for OFX, so a narrow filter would grey the file out. The file's
 * extension decides instead, and the server sniffs the bytes anyway.
 *
 * Android hands back a document uri, not a path, so the name is read from the
 * last segment of the decoded uri (`primary:Download/bank.ofx` → `bank.ofx`).
 * When the uri carries no name at all (`msf:1000000034`), the provider's own
 * MIME type is the fallback. Anything that is neither a CSV nor an OFX/QFX is
 * refused here, with a member-facing message, before any upload — a photo sent
 * to the import endpoint would only come back as a 400.
 */

import { Platform } from 'react-native';
import { File } from 'expo-file-system';
import type { ImportFile } from '@api/imports';

export const IMPORT_FILE_REFUSED_MESSAGE =
  'Choose a CSV or OFX file (.csv, .ofx or .qfx) — that file is not a bank statement Trove can read.';

/** Extension → the declared type; every value is in the operation's accepted list. */
const TYPE_BY_EXTENSION: Record<string, string> = {
  csv: 'text/csv',
  ofx: 'application/x-ofx',
  qfx: 'application/x-qfx',
};

/** A provider's MIME type → the extension, for a uri with no usable name. */
const EXTENSION_BY_TYPE: Record<string, string> = {
  'text/csv': 'csv',
  'text/comma-separated-values': 'csv',
  'application/csv': 'csv',
  'application/x-ofx': 'ofx',
  'application/ofx': 'ofx',
  'application/x-qfx': 'qfx',
};

function displayName(raw: string): string {
  let name = raw;
  try {
    name = decodeURIComponent(raw);
  } catch {
    // A malformed escape: keep the raw name.
  }
  return name.slice(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
}

/**
 * Pick a CSV or OFX file. Resolves to null when the member cancels; rejects
 * with {@link IMPORT_FILE_REFUSED_MESSAGE} for any other kind of file.
 */
export async function pickImportFile(): Promise<ImportFile | null> {
  const picked = await File.pickFileAsync({ mimeTypes: '*/*' });
  if (picked.canceled) return null;
  const file = picked.result;
  const name = displayName(file.name);
  const dot = name.lastIndexOf('.');
  const extension = dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
  // Own keys only: `'constructor' in {}` is true through the prototype chain.
  if (Object.hasOwn(TYPE_BY_EXTENSION, extension)) {
    return { uri: file.uri, name, type: TYPE_BY_EXTENSION[extension] };
  }
  const providerType = (file.type ?? '').toLowerCase();
  const fromType = Object.hasOwn(EXTENSION_BY_TYPE, providerType)
    ? EXTENSION_BY_TYPE[providerType]
    : undefined;
  if (dot < 0 && fromType !== undefined) {
    return { uri: file.uri, name: `statement.${fromType}`, type: TYPE_BY_EXTENSION[fromType] };
  }
  throw new Error(IMPORT_FILE_REFUSED_MESSAGE);
}

/**
 * Remove the app's own copy of a picked statement once its upload has settled.
 *
 * iOS opens the picker with `asCopy: true` (expo-file-system's
 * `FilePickingUtils.swift`), so the bank statement is copied into the app's
 * tmp Inbox and would otherwise stay there until iOS purges tmp. Android
 * hands back the member's own document (`content://`), which is never
 * deleted — that is their original, not a copy.
 */
export function discardPickedFile(file: ImportFile): void {
  if (Platform.OS !== 'ios' || !file.uri.startsWith('file://')) return;
  try {
    new File(file.uri).delete();
  } catch {
    // Already gone; iOS purges tmp in any case. The upload's outcome stands.
  }
}
