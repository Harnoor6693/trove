import { Platform } from 'react-native';
import { File } from 'expo-file-system';
import { IMPORT_FILE_REFUSED_MESSAGE, discardPickedFile, pickImportFile } from './importFilePicker';

const mockPick = File.pickFileAsync as unknown as jest.Mock;

function picked(uri: string, name: string, type = '') {
  mockPick.mockResolvedValueOnce({ canceled: false, result: { uri, name, type } });
}

beforeEach(() => {
  mockPick.mockReset();
  mockPick.mockResolvedValue({ canceled: true, result: null });
});

describe('pickImportFile', () => {
  it('opens the system picker for any type — Android has no registered OFX MIME type', async () => {
    await pickImportFile();
    expect(mockPick).toHaveBeenCalledWith({ mimeTypes: '*/*' });
  });

  it('a canceled pick resolves to null, not an error', async () => {
    await expect(pickImportFile()).resolves.toBeNull();
  });

  it('a picked CSV becomes an upload typed text/csv', async () => {
    picked('file:///tmp/statement.csv', 'statement.csv');
    await expect(pickImportFile()).resolves.toEqual({
      uri: 'file:///tmp/statement.csv',
      name: 'statement.csv',
      type: 'text/csv',
    });
  });

  it('a picked OFX or QFX is typed from its extension, case-insensitively', async () => {
    picked('file:///tmp/x.ofx', 'x.ofx');
    await expect(pickImportFile()).resolves.toEqual({
      uri: 'file:///tmp/x.ofx',
      name: 'x.ofx',
      type: 'application/x-ofx',
    });
    picked('file:///tmp/X.QFX', 'X.QFX');
    await expect(pickImportFile()).resolves.toEqual({
      uri: 'file:///tmp/X.QFX',
      name: 'X.QFX',
      type: 'application/x-qfx',
    });
  });

  it('reads the real file name out of an Android document uri', async () => {
    const uri =
      'content://com.android.externalstorage.documents/document/primary%3ADownload%2Fbank-xml.ofx';
    picked(uri, 'primary%3ADownload%2Fbank-xml.ofx');
    await expect(pickImportFile()).resolves.toEqual({
      uri,
      name: 'bank-xml.ofx',
      type: 'application/x-ofx',
    });
  });

  it('falls back to the provider MIME type when the uri carries no file name', async () => {
    const uri = 'content://com.android.providers.downloads.documents/document/msf%3A1000000034';
    picked(uri, 'msf%3A1000000034', 'text/comma-separated-values');
    await expect(pickImportFile()).resolves.toEqual({
      uri,
      name: 'statement.csv',
      type: 'text/csv',
    });
  });

  it('refuses anything that is not a CSV or OFX, before any upload', async () => {
    picked('file:///tmp/photo.jpg', 'photo.jpg', 'image/jpeg');
    await expect(pickImportFile()).rejects.toThrow(IMPORT_FILE_REFUSED_MESSAGE);
    expect(IMPORT_FILE_REFUSED_MESSAGE).toMatch(/Choose a CSV or OFX file/);
  });
  // `in` and bracket lookups walk the prototype chain: `'constructor' in {}` is
  // true, so these names used to pass the extension check and come back typed
  // as the Object function instead of being refused.
  it.each(['x.constructor', 'x.__proto__'])(
    'refuses %s — an extension is looked up as an own key, never through the prototype',
    async (name) => {
      picked(`file:///tmp/${name}`, name);
      await expect(pickImportFile()).rejects.toThrow(IMPORT_FILE_REFUSED_MESSAGE);
    }
  );

  it.each(['constructor', '__proto__'])(
    'refuses a nameless uri whose provider type is %s',
    async (type) => {
      picked('content://p/document/msf%3A1', 'msf%3A1', type);
      await expect(pickImportFile()).rejects.toThrow(IMPORT_FILE_REFUSED_MESSAGE);
    }
  );
});

describe('discardPickedFile', () => {
  const remove = (File.prototype as unknown as { delete: jest.Mock }).delete;

  beforeEach(() => {
    remove.mockClear();
  });
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('deletes the copy iOS made of the pick (a file:// uri in the app sandbox)', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    discardPickedFile({
      uri: 'file:///tmp/com.trove.app-Inbox/a.csv',
      name: 'a.csv',
      type: 'text/csv',
    });
    expect(remove).toHaveBeenCalledTimes(1);
    expect((remove.mock.contexts[0] as { uri: string }).uri).toBe(
      'file:///tmp/com.trove.app-Inbox/a.csv'
    );
  });

  it('never deletes a content:// uri — that is the member’s own original', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    discardPickedFile({ uri: 'content://p/document/1', name: 'a.csv', type: 'text/csv' });
    jest.replaceProperty(Platform, 'OS', 'android');
    discardPickedFile({ uri: 'content://p/document/1', name: 'a.csv', type: 'text/csv' });
    expect(remove).not.toHaveBeenCalled();
  });

  it('never deletes on Android, where the picker hands back the original, not a copy', () => {
    jest.replaceProperty(Platform, 'OS', 'android');
    discardPickedFile({ uri: 'file:///sdcard/Download/a.csv', name: 'a.csv', type: 'text/csv' });
    expect(remove).not.toHaveBeenCalled();
  });

  it('swallows a failed delete — cleanup must never turn a finished upload into an error', () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    remove.mockImplementationOnce(() => {
      throw new Error('gone');
    });
    expect(() =>
      discardPickedFile({ uri: 'file:///tmp/a.csv', name: 'a.csv', type: 'text/csv' })
    ).not.toThrow();
  });
});
