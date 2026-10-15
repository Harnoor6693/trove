import {
  ACCOUNT_SYNC_STATUS_LABEL,
  ACCOUNT_SYNC_STATUS_VARIANT,
  ACCOUNT_TYPES,
  ACCOUNT_TYPE_LABEL,
  IMPORT_VERDICT_LABEL,
  IMPORT_VERDICT_VARIANT,
  SOURCE_KIND_LABEL,
  SYNC_RUN_STATUS_LABEL,
  SYNC_RUN_STATUS_VARIANT,
  TRANSACTION_TYPES,
  TRANSACTION_TYPE_LABEL,
  isOutflow,
} from './labels';
import type {
  AccountSyncStatus,
  AccountType,
  ImportRowVerdict,
  SourceKind,
  SyncRunStatus,
  TransactionType,
} from '@api/types';

/**
 * These lists are written out rather than derived from the maps under test:
 * a test that reads its expectation off the thing it is testing would pass
 * however wrong the map became. They are the enums exactly as
 * `openapi/trove-core.yaml` declares them.
 */
const SOURCE_KINDS: SourceKind[] = ['SIMPLEFIN', 'OFX', 'CSV', 'MANUAL'];
const ACCOUNT_TYPE_VALUES: AccountType[] = [
  'CHECKING',
  'SAVINGS',
  'CREDIT_CARD',
  'INVESTMENT',
  'OTHER',
];
const ACCOUNT_SYNC_STATUSES: AccountSyncStatus[] = ['PENDING', 'SYNCING', 'OK', 'ERROR'];
const SYNC_RUN_STATUSES: SyncRunStatus[] = ['RUNNING', 'SUCCESS', 'PARTIAL_FAILURE', 'FAILED'];
const TRANSACTION_TYPE_VALUES: TransactionType[] = [
  'DEBIT',
  'CREDIT',
  'TRANSFER',
  'FEE',
  'INTEREST',
  'REFUND',
  'OTHER',
];
const VERDICTS: ImportRowVerdict[] = ['NEW', 'DUPLICATE', 'FUZZY_DUPLICATE', 'INVALID'];

function labelsFor(map: Record<string, string>, keys: string[]): string[] {
  return keys.map((key) => map[key]);
}

describe('every contract enum has a human label', () => {
  it.each([
    ['SourceKind', SOURCE_KIND_LABEL, SOURCE_KINDS],
    ['AccountType', ACCOUNT_TYPE_LABEL, ACCOUNT_TYPE_VALUES],
    ['AccountSyncStatus', ACCOUNT_SYNC_STATUS_LABEL, ACCOUNT_SYNC_STATUSES],
    ['SyncRunStatus', SYNC_RUN_STATUS_LABEL, SYNC_RUN_STATUSES],
    ['TransactionType', TRANSACTION_TYPE_LABEL, TRANSACTION_TYPE_VALUES],
    ['ImportRowVerdict', IMPORT_VERDICT_LABEL, VERDICTS],
  ])('%s', (_name, map, keys) => {
    const labels = labelsFor(map as Record<string, string>, keys as string[]);
    expect(labels).toHaveLength((keys as string[]).length);
    for (const label of labels) {
      expect(typeof label).toBe('string');
      expect(label).not.toBe('');
      // Never a raw SCREAMING_SNAKE token in front of a member.
      expect(label).not.toMatch(/^[A-Z_]+$/);
    }
  });
});

describe('state is worded, and the colour is an extra', () => {
  it('gives every account sync status a pill variant', () => {
    for (const status of ACCOUNT_SYNC_STATUSES) {
      expect(ACCOUNT_SYNC_STATUS_VARIANT[status]).toBeDefined();
    }
    expect(ACCOUNT_SYNC_STATUS_LABEL.ERROR).toBe('Sync failed');
  });

  it('gives every run status a pill variant, and says "Failed" out loud', () => {
    for (const status of SYNC_RUN_STATUSES) {
      expect(SYNC_RUN_STATUS_VARIANT[status]).toBeDefined();
    }
    expect(SYNC_RUN_STATUS_LABEL.FAILED).toBe('Failed');
    expect(SYNC_RUN_STATUS_LABEL.PARTIAL_FAILURE).toBe('Partly failed');
  });

  it('gives every import verdict a pill variant', () => {
    for (const verdict of VERDICTS) {
      expect(IMPORT_VERDICT_VARIANT[verdict]).toBeDefined();
    }
  });
});

describe('the picker orders', () => {
  it('offers every account type once', () => {
    expect([...ACCOUNT_TYPES].sort()).toEqual([...ACCOUNT_TYPE_VALUES].sort());
  });

  it('offers every transaction type once', () => {
    expect([...TRANSACTION_TYPES].sort()).toEqual([...TRANSACTION_TYPE_VALUES].sort());
  });
});

describe('isOutflow', () => {
  it('treats a debit and a fee as money leaving', () => {
    expect(isOutflow('DEBIT')).toBe(true);
    expect(isOutflow('FEE')).toBe(true);
  });

  it('treats everything else as not an outflow', () => {
    expect(isOutflow('CREDIT')).toBe(false);
    expect(isOutflow('REFUND')).toBe(false);
    expect(isOutflow('INTEREST')).toBe(false);
    expect(isOutflow('TRANSFER')).toBe(false);
    expect(isOutflow('OTHER')).toBe(false);
  });
});
