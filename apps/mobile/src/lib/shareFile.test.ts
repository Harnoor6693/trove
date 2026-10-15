import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { SHARE_UNAVAILABLE_MESSAGE, shareBytes, sweepStaleExports } from './shareFile';

type Written = { uri: string; exists: boolean };

const write = (File.prototype as unknown as { write: jest.Mock }).write;
const remove = (File.prototype as unknown as { delete: jest.Mock }).delete;
const isAvailable = Sharing.isAvailableAsync as jest.Mock;
const share = Sharing.shareAsync as jest.Mock;

beforeEach(() => {
  jest.clearAllMocks();
  isAvailable.mockResolvedValue(true);
  share.mockResolvedValue(undefined);
  (Paths.cache.list as jest.Mock).mockReturnValue([]);
});

describe('shareBytes', () => {
  it('writes the exact bytes to a cache file and hands its file:// uri to the share sheet', async () => {
    const bytes = new Uint8Array([0x25, 0x50, 0x44, 0x46]).buffer;
    await shareBytes(bytes, 'tax-export-2025.pdf', 'application/pdf', 'com.adobe.pdf');

    expect(write).toHaveBeenCalledTimes(1);
    expect(Array.from(write.mock.calls[0][0] as Uint8Array)).toEqual([0x25, 0x50, 0x44, 0x46]);
    const file = write.mock.contexts[0] as Written;
    expect(file.uri).toBe('file:///cache/tax-export-2025.pdf');

    expect(share).toHaveBeenCalledWith('file:///cache/tax-export-2025.pdf', {
      mimeType: 'application/pdf',
      UTI: 'com.adobe.pdf',
      dialogTitle: 'tax-export-2025.pdf',
    });
  });

  it('deletes the file once the share sheet returns', async () => {
    await shareBytes(new ArrayBuffer(2), 'trove-export.zip', 'application/zip');
    expect(remove).toHaveBeenCalledTimes(1);
    expect((remove.mock.contexts[0] as Written).uri).toBe('file:///cache/trove-export.zip');
    expect((remove.mock.contexts[0] as Written).exists).toBe(false);
    // Ordering: the delete happens after the share, never before it.
    expect(remove.mock.invocationCallOrder[0]).toBeGreaterThan(share.mock.invocationCallOrder[0]);
  });

  it('deletes the file even when the share sheet rejects, and passes the rejection on', async () => {
    share.mockRejectedValueOnce(new Error('share failed'));
    await expect(shareBytes(new ArrayBuffer(1), 'a.csv', 'text/csv')).rejects.toThrow(
      'share failed'
    );
    expect(remove).toHaveBeenCalledTimes(1);
  });

  it('throws a member-facing message and writes nothing when sharing is unavailable', async () => {
    isAvailable.mockResolvedValueOnce(false);
    await expect(shareBytes(new ArrayBuffer(1), 'a.csv', 'text/csv')).rejects.toThrow(
      SHARE_UNAVAILABLE_MESSAGE
    );
    expect(write).not.toHaveBeenCalled();
    expect(share).not.toHaveBeenCalled();
  });

  it('sanitises a path-hostile name so the file stays inside the cache directory', async () => {
    await shareBytes(new ArrayBuffer(1), '../x', 'text/csv');
    const uri = (write.mock.contexts[0] as Written).uri;
    expect(uri).toBe('file:///cache/_x');
    expect(share.mock.calls[0][1].dialogTitle).toBe('_x');
  });

  it('falls back to a fixed name when nothing safe is left', async () => {
    await shareBytes(new ArrayBuffer(1), '...', 'text/csv');
    expect((write.mock.contexts[0] as Written).uri).toBe('file:///cache/download');
  });
});

/** What `Paths.cache.list()` answers: a leftover from a run the app was killed in, plus unrelated files. */
function cacheHolding(...names: string[]): File[] {
  const entries = names.map((n) => new File(Paths.cache, n));
  entries.forEach((f) => ((f as unknown as { exists: boolean }).exists = true));
  (Paths.cache.list as jest.Mock).mockReturnValue(entries);
  return entries;
}

describe('sweepStaleExports', () => {
  it('deletes an export a killed app left in the cache, and nothing else', () => {
    cacheHolding(
      'trove-export.zip',
      'tax-export-2025.csv',
      'tax-export-2024.pdf',
      'http-cache',
      'receipt.jpg',
      'tax-export-2025.txt'
    );
    sweepStaleExports();
    const deleted = remove.mock.contexts.map((c) => (c as Written).uri).sort();
    expect(deleted).toEqual([
      'file:///cache/tax-export-2024.pdf',
      'file:///cache/tax-export-2025.csv',
      'file:///cache/trove-export.zip',
    ]);
  });

  it('never throws — a sweep that fails must not stop the app or the next share', () => {
    (Paths.cache.list as jest.Mock).mockImplementationOnce(() => {
      throw new Error('cache unreadable');
    });
    expect(() => sweepStaleExports()).not.toThrow();
  });

  it('runs at the start of every share, before the new file is written', async () => {
    const [stale] = cacheHolding('tax-export-2024.csv');
    await shareBytes(new ArrayBuffer(1), 'tax-export-2025.csv', 'text/csv');
    expect(remove.mock.contexts[0]).toBe(stale);
    expect(remove.mock.invocationCallOrder[0]).toBeLessThan(write.mock.invocationCallOrder[0]);
  });
});
