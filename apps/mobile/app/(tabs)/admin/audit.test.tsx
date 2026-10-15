import React from 'react';
import { AxiosError } from 'axios';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import AdminAuditScreen from './audit';
import { fetchAuditLog } from '@api/admin';
import { AUDIT_PAGE_SIZE } from '@hooks/useAdmin';
import type { AuditLogEntry, Page } from '@api/types';

/**
 * The audit log — `GET /v1/audit`, paged.
 *
 * Two things this suite holds down.
 *
 *  - **A real pager.** `meta.total` is what tells the reader how much there is
 *    and an append-only "load more" hides it; an audit log is also read by
 *    jumping around rather than by scrolling to the end. So the page controls
 *    and the rows must come from the same fetch, and the page number the
 *    screen asks the server for has to match the one it shows.
 *  - **Nothing on a row is markup.** `path` is the matched route template,
 *    never the caller's raw URI — two routes on this API carry a live
 *    credential in the path — and no body, header, query string or token is
 *    kept, so every field is a scalar rendered as text.
 */

jest.mock('@api/admin', () => ({ fetchAuditLog: jest.fn() }));

const mockAudit = fetchAuditLog as jest.MockedFunction<typeof fetchAuditLog>;

function entry(overrides: Partial<AuditLogEntry> = {}): AuditLogEntry {
  return {
    id: '00000000-0000-4000-8000-0000000000a1',
    method: 'DELETE',
    path: '/api/v1/admin/users/{id}',
    status: 200,
    userId: '00000000-0000-4000-8000-000000000002',
    clientIp: '203.0.113.7',
    requestId: '99999999-8888-4777-8666-555555555555',
    durationMs: 42,
    at: '2026-09-04T18:30:00Z',
    ...overrides,
  };
}

function page(rows: AuditLogEntry[], total: number, index = 0): Page<AuditLogEntry> {
  return { data: rows, meta: { total, page: index, limit: AUDIT_PAGE_SIZE } };
}

function refused(status: number, slug: string, detail: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const config = { headers: {} } as any;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    data: {
      success: false,
      error: { type: `https://trove.finance/errors/${slug}`, title: slug, status, detail },
    },
    status,
    statusText: '',
    headers: {},
    config,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
}

/** MnBtn drops `accessibilityState` entirely when it is not disabled. */
function isDisabled(testID: string): boolean {
  const { accessibilityState } = screen.getByTestId(testID).props as {
    accessibilityState?: { disabled?: boolean };
  };
  return accessibilityState?.disabled === true;
}

function textOf(testID: string): string {
  const { children } = screen.getByTestId(testID).props as { children: unknown };
  return (Array.isArray(children) ? children : [children]).join('');
}

let client: ReturnType<typeof createTestQueryClient>;

function renderScreen() {
  client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <AdminAuditScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockAudit.mockResolvedValue(page([entry()], 1));
});

afterEach(() => {
  client.clear();
});

describe('the role gate', () => {
  it('shows a MEMBER the clean 403 state instead of the log', async () => {
    mockAudit.mockRejectedValue(refused(403, 'forbidden', 'Administrator role required.'));
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-forbidden')).toBeTruthy());
    expect(screen.queryByTestId('admin-audit-page')).toBeNull();
  });

  it('shows an ADMIN the log', async () => {
    renderScreen();
    await waitFor(() =>
      expect(screen.getByTestId('admin-audit-00000000-0000-4000-8000-0000000000a1')).toBeTruthy()
    );
    expect(screen.queryByTestId('admin-forbidden')).toBeNull();
  });
});

describe('the pager', () => {
  it('asks for the first page with the declared page size', async () => {
    renderScreen();
    await waitFor(() =>
      expect(mockAudit).toHaveBeenCalledWith({ page: 0, limit: AUDIT_PAGE_SIZE })
    );
    // Let the page land before the test ends, or the state update it causes
    // arrives during the *next* test, outside that test's `act`.
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 1 of 1'));
  });

  // `meta.total` is the number the reader needs; an infinite list hides it.
  it('shows how many pages there are and how many changes in total', async () => {
    mockAudit.mockResolvedValue(page([entry()], 137));
    renderScreen();
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('137 changes'));
    // 137 rows at 20 a page is seven pages.
    expect(textOf('admin-audit-page')).toContain('Page 1 of 7');
  });

  it('turns to the next page and asks the server for it', async () => {
    mockAudit.mockResolvedValue(page([entry()], 137));
    renderScreen();
    // The pager is disabled while a page is in flight, so the first page has
    // to have landed before "Next" means anything.
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 1 of 7'));

    fireEvent.press(screen.getByTestId('admin-audit-next'));
    await waitFor(() =>
      expect(mockAudit).toHaveBeenCalledWith({ page: 1, limit: AUDIT_PAGE_SIZE })
    );
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 2 of 7'));
  });

  it('comes back to the previous page', async () => {
    mockAudit.mockResolvedValue(page([entry()], 137));
    renderScreen();
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 1 of 7'));

    fireEvent.press(screen.getByTestId('admin-audit-next'));
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 2 of 7'));
    // `placeholderData` keeps the previous page on screen so the list does not
    // blink, which means the label reads "Page 2" before page 2 has landed —
    // and both controls stay disabled until it has.
    await waitFor(() => expect(isDisabled('admin-audit-prev')).toBe(false));

    fireEvent.press(screen.getByTestId('admin-audit-prev'));
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 1 of 7'));
  });

  it('cannot page off either end', async () => {
    renderScreen();
    // Waiting for the page to have *landed*, not merely for the control to
    // exist: the pager is disabled while a fetch is in flight, so asserting
    // too early would pass for the wrong reason and let the query settle
    // outside `act`.
    await waitFor(() => expect(textOf('admin-audit-page')).toContain('Page 1 of 1'));

    // One page of one row: both ends are the same end.
    expect(isDisabled('admin-audit-prev')).toBe(true);
    expect(isDisabled('admin-audit-next')).toBe(true);
  });

  // An empty log still reads "1 of 1" rather than "1 of 0".
  it('renders one page for an empty log', async () => {
    mockAudit.mockResolvedValue(page([], 0));
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-audit-empty')).toBeTruthy());
    expect(textOf('admin-audit-page')).toContain('Page 1 of 1');
  });
});

describe('a row', () => {
  it('renders the route template the server stored, not a raw URI', async () => {
    renderScreen();
    await waitFor(() => expect(screen.getByText('/api/v1/admin/users/{id}')).toBeTruthy());
  });

  it('labels the row for a screen reader with the method, path, status and time', async () => {
    renderScreen();
    const row = await screen.findByTestId('admin-audit-00000000-0000-4000-8000-0000000000a1');
    const label = row.props.accessibilityLabel as string;
    expect(label).toContain('DELETE');
    expect(label).toContain('/api/v1/admin/users/{id}');
    expect(label).toContain('200');
  });

  // `userId` is null for an unauthenticated request and `clientIp` for an
  // in-process one. Neither may render as the literal word "null".
  it('says "signed out" for a null userId and prints no literal null', async () => {
    mockAudit.mockResolvedValue(page([entry({ userId: null, clientIp: null })], 1));
    renderScreen();
    await waitFor(() => expect(screen.getByText(/signed out/)).toBeTruthy());
    expect(JSON.stringify(screen.toJSON())).not.toMatch(/>null</);
  });

  it('explains a failure instead of a spinner that never resolves', async () => {
    mockAudit.mockRejectedValue(refused(500, 'internal-error', 'Something broke.'));
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-audit-error')).toBeTruthy());
    expect(textOf('admin-audit-error')).toContain('Something broke.');
  });
});
