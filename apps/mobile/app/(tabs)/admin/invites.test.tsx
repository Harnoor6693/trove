import React from 'react';
import { AxiosError } from 'axios';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import AdminInvitesScreen from './invites';
import { createInvite, fetchInvites, revokeInvite } from '@api/admin';
import type { InviteResponse } from '@api/types';

/**
 * Invitations — `GET/POST /v1/admin/invites`, `DELETE /v1/admin/invites/{id}`.
 *
 * **The recorded debt this suite exists for.** `InviteResponse.token` and
 * `.url` are nullable and go null the moment an invitation is accepted,
 * revoked or expired. A client that types them as `string` renders the literal
 * word "null" at an admin and offers a copy control for a link that cannot be
 * redeemed — which is worse than showing nothing, because the admin then hands
 * that link to somebody.
 */

jest.mock('@api/admin', () => ({
  fetchInvites: jest.fn(),
  createInvite: jest.fn(),
  revokeInvite: jest.fn(),
}));

const mockInvites = fetchInvites as jest.MockedFunction<typeof fetchInvites>;
const mockCreate = createInvite as jest.MockedFunction<typeof createInvite>;
const mockRevoke = revokeInvite as jest.MockedFunction<typeof revokeInvite>;

const USABLE: InviteResponse = {
  id: 'i1',
  email: null,
  token: 'aW52aXRlLXRva2Vu',
  url: 'https://trove.example.test/invite/aW52aXRlLXRva2Vu',
  expiresAt: '2026-09-22T14:00:00Z',
  createdAt: '2026-09-19T14:00:00Z',
  acceptedAt: null,
  createdByEmail: 'admin@example.test',
};

/** Accepted: `token` and `url` are null, which is how the state is read. */
const ACCEPTED: InviteResponse = {
  id: 'i2',
  email: 'invitee@example.test',
  token: null,
  url: null,
  expiresAt: '2026-09-20T14:00:00Z',
  createdAt: '2026-09-17T14:00:00Z',
  acceptedAt: '2026-09-18T09:00:00Z',
  createdByEmail: 'admin@example.test',
};

/** Revoked or expired: never accepted, but no longer redeemable either. */
const SPENT: InviteResponse = {
  id: 'i3',
  email: null,
  token: null,
  url: null,
  expiresAt: '2026-09-18T14:00:00Z',
  createdAt: '2026-09-15T14:00:00Z',
  acceptedAt: null,
  createdByEmail: 'admin@example.test',
};

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

function textOf(testID: string): string {
  const { children } = screen.getByTestId(testID).props as { children: unknown };
  return (Array.isArray(children) ? children : [children]).join('');
}

let client: ReturnType<typeof createTestQueryClient>;

async function renderScreen() {
  client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <AdminInvitesScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockInvites.mockResolvedValue([USABLE, ACCEPTED, SPENT]);
  mockCreate.mockResolvedValue(USABLE);
  mockRevoke.mockResolvedValue(undefined);
});

afterEach(() => {
  client.clear();
});

describe('the role gate', () => {
  it('shows a MEMBER the clean 403 state instead of the invitations', async () => {
    mockInvites.mockRejectedValue(refused(403, 'forbidden', 'Administrator role required.'));
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-forbidden')).toBeTruthy());
    expect(screen.queryByTestId('admin-invite-i1')).toBeNull();
  });

  it('shows an ADMIN the invitations', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i1')).toBeTruthy());
    expect(screen.queryByTestId('admin-forbidden')).toBeNull();
  });
});

describe('a spent invitation', () => {
  it('renders no link and never the literal string "null"', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i2')).toBeTruthy());

    expect(screen.queryByTestId('admin-invite-i2-url')).toBeNull();
    expect(screen.queryByTestId('admin-invite-i3-url')).toBeNull();
    expect(JSON.stringify(screen.toJSON())).not.toMatch(/>null</);
  });

  // Offering "Revoke" on an invitation that is already spent would send a
  // request the server answers 404 or 409, for no change the admin wanted.
  it('offers no copy or revoke control once the link is gone', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i2')).toBeTruthy());

    expect(screen.queryByTestId('admin-invite-i2-revoke')).toBeNull();
    expect(screen.queryByTestId('admin-invite-i3-revoke')).toBeNull();
    expect(screen.getByTestId('admin-invite-i1-revoke')).toBeTruthy();
  });

  it('says which of the two spent states it is in', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i2')).toBeTruthy());

    expect(textOf('admin-invite-i2-spent')).toContain('has been used');
    expect(textOf('admin-invite-i3-spent')).toContain('no longer usable');
  });

  // The state is read from `token`/`url` being non-null, the way the server
  // computes it — not from comparing `expiresAt` to the device's clock, which
  // is a different clock and can be wrong.
  it('labels the state from the nullability, not from the device clock', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i1')).toBeTruthy());

    const stateOf = (id: string, label: string) =>
      within(screen.getByTestId(`admin-invite-${id}-state`)).getByText(label);
    expect(stateOf('i1', 'Usable')).toBeTruthy();
    expect(stateOf('i2', 'Accepted')).toBeTruthy();
    expect(stateOf('i3', 'Expired or revoked')).toBeTruthy();
  });
});

describe('a usable invitation', () => {
  it('shows the link the server sent, selectable so it can be copied', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i1-url')).toBeTruthy());

    const link = screen.getByTestId('admin-invite-i1-url');
    expect(link.props.children).toBe(USABLE.url);
    expect(link.props.selectable).toBe(true);
  });

  it('revokes it and refetches the list, because the link goes null with the clock', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i1-revoke')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-invite-i1-revoke'));
    await waitFor(() => expect(mockRevoke).toHaveBeenCalledWith('i1'));
    await waitFor(() => expect(mockInvites).toHaveBeenCalledTimes(2));
  });

  it('explains a refused revoke on the row it was refused for', async () => {
    mockRevoke.mockRejectedValue(refused(404, 'not-found', 'No such invitation.'));
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-i1-revoke')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-invite-i1-revoke'));
    await waitFor(() => expect(screen.getByTestId('admin-invite-i1-error')).toBeTruthy());
    expect(textOf('admin-invite-i1-error')).toContain('No such invitation.');
  });
});

describe('creating an invitation', () => {
  // `{}` is the normal body: an invitation with no address can be redeemed by
  // anyone holding the link, and `role` defaults to MEMBER.
  it('sends an empty body when no address and no role were chosen', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-create')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-invite-create'));
    await waitFor(() => expect(mockCreate).toHaveBeenCalledWith({}));
  });

  it('binds the invitation to a trimmed address when one is given', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-email')).toBeTruthy());

    await fireEvent.changeText(
      screen.getByTestId('admin-invite-email'),
      '  invitee@example.test  '
    );
    await fireEvent.press(screen.getByTestId('admin-invite-create'));
    await waitFor(() => expect(mockCreate).toHaveBeenCalledWith({ email: 'invitee@example.test' }));
  });

  it('sends the ADMIN role only when it was asked for', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-role-toggle')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-invite-role-toggle'));
    await fireEvent.press(screen.getByTestId('admin-invite-create'));
    await waitFor(() => expect(mockCreate).toHaveBeenCalledWith({ role: 'ADMIN' }));
  });

  // The list, not the creation response, is where a live link is read from —
  // the contract says so at `listInvites`, because redeemability is a
  // question about now.
  it('refetches the list after a create rather than trusting the 201 body', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-create')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-invite-create'));
    await waitFor(() => expect(mockInvites).toHaveBeenCalledTimes(2));
  });

  it('explains a refused create instead of failing silently', async () => {
    mockCreate.mockRejectedValue(refused(400, 'validation-error', 'Email is not an address.'));
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invite-create')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-invite-create'));
    await waitFor(() => expect(screen.getByTestId('admin-invite-create-error')).toBeTruthy());
    expect(textOf('admin-invite-create-error')).toContain('Email is not an address.');
  });
});

describe('an empty household', () => {
  it('says so rather than rendering an empty card', async () => {
    mockInvites.mockResolvedValue([]);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invites-empty')).toBeTruthy());
  });
});
