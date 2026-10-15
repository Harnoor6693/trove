import React from 'react';
import { AxiosError } from 'axios';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import AdminMembersScreen from './index';
import {
  clearHouseholdMemberTotp,
  deleteHouseholdMember,
  fetchAdminSettings,
  fetchHouseholdMembers,
  requestPasswordResetLink,
  restoreHouseholdMember,
  unlockHouseholdMember,
  updateAdminSettings,
} from '@api/admin';
import { useAuthStore } from '@stores/authStore';
import type { AdminUserResponse, MeResponse } from '@api/types';

jest.mock('@api/admin', () => ({
  fetchHouseholdMembers: jest.fn(),
  deleteHouseholdMember: jest.fn(),
  restoreHouseholdMember: jest.fn(),
  unlockHouseholdMember: jest.fn(),
  clearHouseholdMemberTotp: jest.fn(),
  requestPasswordResetLink: jest.fn(),
  fetchAdminSettings: jest.fn(),
  updateAdminSettings: jest.fn(),
  fetchInvites: jest.fn(),
  createInvite: jest.fn(),
  revokeInvite: jest.fn(),
  fetchAuditLog: jest.fn(),
}));

const mockMembers = fetchHouseholdMembers as jest.MockedFunction<typeof fetchHouseholdMembers>;
const mockDelete = deleteHouseholdMember as jest.MockedFunction<typeof deleteHouseholdMember>;
const mockRestore = restoreHouseholdMember as jest.MockedFunction<typeof restoreHouseholdMember>;
const mockUnlock = unlockHouseholdMember as jest.MockedFunction<typeof unlockHouseholdMember>;
const mockClearTotp = clearHouseholdMemberTotp as jest.MockedFunction<
  typeof clearHouseholdMemberTotp
>;
const mockResetLink = requestPasswordResetLink as jest.MockedFunction<
  typeof requestPasswordResetLink
>;
const mockSettings = fetchAdminSettings as jest.MockedFunction<typeof fetchAdminSettings>;
const mockSaveSettings = updateAdminSettings as jest.MockedFunction<typeof updateAdminSettings>;
const mockPush = router.push as jest.Mock;

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: true,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Halifax',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const SELF: AdminUserResponse = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  role: 'ADMIN',
  active: true,
  totpEnabled: true,
  createdAt: '2026-08-30T09:15:00Z',
};

const OTHER: AdminUserResponse = {
  id: 'u2',
  email: 'grace@example.com',
  displayName: 'Grace',
  role: 'MEMBER',
  active: true,
  totpEnabled: true,
  createdAt: '2026-08-31T09:15:00Z',
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
      <AdminMembersScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({ me: ME });
  mockMembers.mockResolvedValue([SELF, OTHER]);
  mockSettings.mockResolvedValue({ registrationOpen: false });
});

afterEach(() => {
  client.clear();
});

describe('the role gate', () => {
  it('shows a MEMBER the clean 403 state instead of the console', async () => {
    mockMembers.mockRejectedValue(refused(403, 'forbidden', 'Administrator role required.'));
    mockSettings.mockRejectedValue(refused(403, 'forbidden', 'Administrator role required.'));
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-forbidden')).toBeTruthy());
    expect(screen.queryByTestId('admin-member-u2')).toBeNull();
  });

  it('shows an ADMIN the console', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2')).toBeTruthy());
    expect(screen.queryByTestId('admin-forbidden')).toBeNull();
  });
});

describe('members', () => {
  it('lists the household with role, state and second-factor at a glance', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2')).toBeTruthy());
    // `MnPill` renders its label as a child `<Text>`, not as a prop on the view.
    expect(within(screen.getByTestId('admin-member-u2-state')).getByText('Active')).toBeTruthy();
    expect(within(screen.getByTestId('admin-member-u2')).getByText('MEMBER')).toBeTruthy();
    expect(within(screen.getByTestId('admin-member-u2')).getByText('2FA')).toBeTruthy();
    expect(screen.getByText('grace@example.com')).toBeTruthy();
  });

  it('removes a member only after a confirmation press', async () => {
    mockDelete.mockResolvedValue(undefined);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-member-u2-delete'));
    expect(mockDelete).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByTestId('admin-member-u2-delete-confirm'));
    await waitFor(() => expect(mockDelete.mock.calls[0][0]).toBe('u2'));
  });

  it('explains the last-admin 409 rather than showing a bare failure', async () => {
    mockDelete.mockRejectedValue(
      refused(409, 'conflict', 'This is the household’s only administrator.')
    );
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2')).toBeTruthy());
    await fireEvent.press(screen.getByTestId('admin-member-u2-delete'));
    await fireEvent.press(screen.getByTestId('admin-member-u2-delete-confirm'));
    await waitFor(() =>
      expect(screen.getByTestId('admin-member-u2-error').props.children).toBe(
        'This is the household’s only administrator.'
      )
    );
  });

  it('offers restore, and the grace explanation, for an inactive member', async () => {
    mockMembers.mockResolvedValue([SELF, { ...OTHER, active: false }]);
    mockRestore.mockResolvedValue(undefined);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2-restore')).toBeTruthy());
    expect(textOf('admin-member-u2-grace')).toMatch(/seven days/);
    await fireEvent.press(screen.getByTestId('admin-member-u2-restore'));
    await waitFor(() => expect(mockRestore.mock.calls[0][0]).toBe('u2'));
  });

  it('shows the 409 a restore outside the grace answers', async () => {
    mockMembers.mockResolvedValue([SELF, { ...OTHER, active: false }]);
    mockRestore.mockRejectedValue(
      refused(409, 'conflict', 'That account’s restore window has passed.')
    );
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2-restore')).toBeTruthy());
    await fireEvent.press(screen.getByTestId('admin-member-u2-restore'));
    await waitFor(() =>
      expect(screen.getByTestId('admin-member-u2-error').props.children).toBe(
        'That account’s restore window has passed.'
      )
    );
  });

  it('unlocks another member and clears their second factor', async () => {
    mockUnlock.mockResolvedValue(undefined);
    mockClearTotp.mockResolvedValue(undefined);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2-unlock')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-member-u2-unlock'));
    await waitFor(() => expect(mockUnlock.mock.calls[0][0]).toBe('u2'));
    await fireEvent.press(screen.getByTestId('admin-member-u2-clear-totp'));
    await waitFor(() => expect(mockClearTotp.mock.calls[0][0]).toBe('u2'));
  });

  it('does not offer those two on the admin’s own row, and says why', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u1')).toBeTruthy());
    expect(screen.queryByTestId('admin-member-u1-unlock')).toBeNull();
    expect(screen.queryByTestId('admin-member-u1-clear-totp')).toBeNull();
    expect(textOf('admin-member-u1-self')).toMatch(/never acts on the admin.s own security state/);
  });

  it('still renders the 403 step-up-required in the member’s own words if the server sends one', async () => {
    mockUnlock.mockRejectedValue(
      refused(403, 'step-up-required', 'An admin route does not act on your own account.')
    );
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2-unlock')).toBeTruthy());
    await fireEvent.press(screen.getByTestId('admin-member-u2-unlock'));
    await waitFor(() =>
      expect(screen.getByTestId('admin-member-u2-error').props.children).toMatch(
        /can’t change their own security settings from here/
      )
    );
  });
});

describe('the password reset link', () => {
  it('mints it, shows it once as selectable text, and drops it on Done', async () => {
    mockResetLink.mockResolvedValue({
      url: 'https://trove.example.test/reset-password/cmVzZXQ',
      expiresAt: '2026-09-18T19:30:00Z',
    });
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2-reset-link')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('admin-member-u2-reset-link'));
    await waitFor(() => expect(screen.getByTestId('admin-reset-url')).toBeTruthy());
    expect(mockResetLink.mock.calls[0][0]).toEqual({ email: 'grace@example.com' });
    expect(screen.getByTestId('admin-reset-url').props.selectable).toBe(true);

    await fireEvent.press(screen.getByTestId('admin-reset-done'));
    await waitFor(() => expect(screen.queryByTestId('admin-reset-url')).toBeNull());
    // And it is not in any query cache — it is a mutation result, reset away.
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((q) => q.state.data)
      )
    ).not.toContain('cmVzZXQ');
  });

  it('shows the 404 an address outside this household gets', async () => {
    mockResetLink.mockRejectedValue(refused(404, 'not-found', 'No such member.'));
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-member-u2-reset-link')).toBeTruthy());
    await fireEvent.press(screen.getByTestId('admin-member-u2-reset-link'));
    await waitFor(() =>
      expect(screen.getByTestId('admin-reset-error').props.children).toBe('No such member.')
    );
  });
});

describe('household settings and navigation', () => {
  it('writes the registration flag', async () => {
    mockSaveSettings.mockResolvedValue({ registrationOpen: true });
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-registration-toggle')).toBeTruthy());
    // `MnToggle` is a `<Switch>` inside the labelled row; pressing the row does
    // nothing, which is why this drives the switch's own event.
    await fireEvent(screen.getByLabelText('Anyone may sign up'), 'valueChange', true);
    await waitFor(() =>
      expect(mockSaveSettings.mock.calls[0][0]).toEqual({ registrationOpen: true })
    );
  });

  it('opens invitations and the audit log as stack screens', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('admin-invites-link')).toBeTruthy());
    await fireEvent.press(screen.getByTestId('admin-invites-link'));
    expect(mockPush).toHaveBeenCalledWith('/(tabs)/admin/invites');
    await fireEvent.press(screen.getByTestId('admin-audit-link'));
    expect(mockPush).toHaveBeenCalledWith('/(tabs)/admin/audit');
  });
});
