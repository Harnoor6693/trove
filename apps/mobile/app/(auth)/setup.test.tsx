import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { AxiosError } from 'axios';
import { router, useLocalSearchParams } from 'expo-router';
import SetupScreen from './setup';
import { previewInvite, register } from '@api/auth';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { useAuthStore } from '@stores/authStore';

/**
 * Registration — `POST /v1/auth/register` (§4.1).
 *
 * The point of this screen in the collapse plan (§4.13, flagged *major*) is
 * that it **reaches the server**: the onboarding wizard it replaces wrote a
 * currency into SecureStore and told nobody, so a mobile-onboarded user had no
 * server-side currency at all. Everything below exists to hold that line —
 * the body actually sent, CAD/USD only, and the invite/registration slugs
 * §4.1 commits to.
 */

jest.mock('@api/auth', () => ({ register: jest.fn(), previewInvite: jest.fn() }));
jest.mock('@api/me', () => ({ fetchMe: jest.fn() }));
jest.mock('@api/preferences', () => ({ fetchPreferences: jest.fn() }));

const mockRegister = register as jest.MockedFunction<typeof register>;
const mockPreviewInvite = previewInvite as jest.MockedFunction<typeof previewInvite>;
const mockParams = useLocalSearchParams as unknown as jest.Mock;
const mockMe = fetchMe as jest.MockedFunction<typeof fetchMe>;
const mockPreferences = fetchPreferences as jest.MockedFunction<typeof fetchPreferences>;
const mockReplace = router.replace as jest.Mock;

const USER = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
};
const SESSION = { accessToken: 'a', refreshToken: 'r', expiresIn: 900, user: USER };
const ME = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
  currency: 'CAD' as const,
  country: 'CA' as const,
  timezone: 'America/Toronto',
  locale: 'en-CA' as const,
  onboardingCompleted: true,
};
const PREFERENCES = {
  region: 'CA',
  locale: 'en-CA' as const,
  timezone: 'America/Toronto',
  onboardingCompleted: true,
};

/** An `ApiResponse` error envelope carrying a problem-details `type` slug. */
function apiError(status: number, slug: string, detail: string, fieldErrors?: unknown): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, {}, {
    status,
    data: {
      success: false,
      error: { type: `https://trove.finance/errors/${slug}`, status, detail, fieldErrors },
    },
    statusText: '',
    headers: {},
    config: {} as never,
  } as never);
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({
    status: 'anonymous',
    hydrated: true,
    server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    user: null,
    me: null,
    preferences: null,
  });
  mockRegister.mockResolvedValue(SESSION);
  mockParams.mockReturnValue({});
  mockPreviewInvite.mockResolvedValue({
    valid: true,
    email: null,
    expiresAt: '2026-09-22T14:00:00Z',
  });
  mockMe.mockResolvedValue(ME);
  mockPreferences.mockResolvedValue(PREFERENCES);
});

type Query = (id: string) => never;

function fill(getByTestId: Query, password = 'correct horse battery') {
  fireEvent.changeText(getByTestId('setup-name-input'), '  Ada  ');
  fireEvent.changeText(getByTestId('setup-email-input'), '  ada@example.com  ');
  fireEvent.changeText(getByTestId('setup-password-input'), password);
}

async function submit(getByTestId: Query) {
  await act(async () => {
    fireEvent.press(getByTestId('setup-submit-btn'));
  });
}

describe('registration body', () => {
  it('sends currency, country and timezone to the server, not just to the device', async () => {
    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() => expect(mockRegister).toHaveBeenCalledTimes(1));
    const body = mockRegister.mock.calls[0][0];
    expect(body.currency).toBe('CAD');
    expect(body.country).toBe('CA');
    expect(typeof body.timezone).toBe('string');
    expect(body.timezone).not.toBe('');
    // Trimmed, so a keyboard's trailing space cannot create `" ada@…"`.
    expect(body.email).toBe('ada@example.com');
    expect(body.displayName).toBe('Ada');
  });

  it('sets currency and country together from the region choice', async () => {
    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    fireEvent.press(getByTestId('region-option-US'));
    await submit(getByTestId as never);

    await waitFor(() => expect(mockRegister).toHaveBeenCalledTimes(1));
    expect(mockRegister.mock.calls[0][0]).toMatchObject({ currency: 'USD', country: 'US' });
  });

  // CAD and USD are the only first-class currencies (owner decision); GBP and
  // EUR were dropped with the old wizard.
  it('offers exactly two regions', () => {
    const { getByTestId, queryByTestId } = render(<SetupScreen />);
    expect(getByTestId('region-option-CA')).toBeTruthy();
    expect(getByTestId('region-option-US')).toBeTruthy();
    expect(queryByTestId('region-option-GB')).toBeNull();
    expect(queryByTestId('region-option-EU')).toBeNull();
  });

  it('omits inviteToken entirely when the field is blank', async () => {
    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() => expect(mockRegister).toHaveBeenCalledTimes(1));
    expect(mockRegister.mock.calls[0][0]).not.toHaveProperty('inviteToken');
  });

  it('sends a trimmed inviteToken when one is pasted in', async () => {
    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    fireEvent.changeText(getByTestId('setup-invite-input'), '  inv-abc123  ');
    await submit(getByTestId as never);

    await waitFor(() => expect(mockRegister).toHaveBeenCalledTimes(1));
    expect(mockRegister.mock.calls[0][0].inviteToken).toBe('inv-abc123');
  });

  it('rejects a short password locally rather than spending a round trip', async () => {
    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never, 'short');
    await submit(getByTestId as never);

    expect(mockRegister).not.toHaveBeenCalled();
    expect(getByTestId('setup-error-text').props.children).toMatch(/at least 12 characters/);
  });
});

describe('after a successful registration', () => {
  it('authenticates, loads the profile, and lands on the tabs', async () => {
    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'));
    // Currency, locale and timezone drive every amount and date the app draws,
    // so they are loaded before the first screen paints.
    await waitFor(() => expect(useAuthStore.getState().me).toEqual(ME));
    await waitFor(() => expect(useAuthStore.getState().preferences).toEqual(PREFERENCES));
    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/');
  });
});

describe('server rejections', () => {
  it.each([
    ['registration-closed', /invite-only/i],
    ['invite-invalid', /isn’t valid/i],
    ['invite-expired', /expired/i],
    ['password-policy', /12–128 characters/],
  ])('rewrites the %s slug into copy a person can act on', async (slug, expected) => {
    mockRegister.mockRejectedValue(apiError(403, slug, 'Operator-facing text.'));

    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() => expect(getByTestId('setup-error-text').props.children).toMatch(expected));
  });

  it('falls back to the server’s own detail for an unrecognised slug', async () => {
    mockRegister.mockRejectedValue(apiError(503, 'service-unavailable', 'Trove is restarting.'));

    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(getByTestId('setup-error-text').props.children).toBe('Trove is restarting.')
    );
  });

  // `fieldErrors` is a LIST of {field, message} — `core`'s ErrorResponse.FieldError
  // — not a map. Reading it as a map showed nothing at all for every field.
  it('renders per-field validation messages from the list shape the server sends', async () => {
    mockRegister.mockRejectedValue(
      apiError(400, 'validation-failed', 'Some fields are invalid.', [
        { field: 'email', message: 'must be a well-formed email address' },
        { field: 'password', message: 'is a commonly used password' },
      ])
    );

    const { getByTestId, queryByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(getByTestId('setup-field-error-email').props.children).toBe(
        'must be a well-formed email address'
      )
    );
    expect(getByTestId('setup-field-error-password').props.children).toBe(
      'is a commonly used password'
    );
    expect(queryByTestId('setup-field-error-displayName')).toBeNull();
  });

  it('leaves the session anonymous when registration fails', async () => {
    mockRegister.mockRejectedValue(apiError(403, 'registration-closed', 'Closed.'));

    const { getByTestId } = render(<SetupScreen />);
    fill(getByTestId as never);
    await submit(getByTestId as never);

    await waitFor(() => expect(getByTestId('setup-error-text')).toBeTruthy());
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(mockReplace).not.toHaveBeenCalledWith('/(tabs)/');
  });
});

// Step 215, family E — `GET /v1/auth/invites/{token}`, the pre-registration
// preview, and the invite deep link it pairs with.
describe('invite preview', () => {
  async function pasteInvite(getByTestId: Query, value: string) {
    fireEvent.changeText(getByTestId('setup-invite-input'), value);
    await act(async () => {
      fireEvent(getByTestId('setup-invite-input'), 'blur');
    });
  }

  // `InviteResponse.url` is `{publicUrl}/invite/{token}` — a web address,
  // because the server has one public origin and no idea a phone app exists.
  // The member pastes what they were sent, not a token they had to extract.
  it('previews the token out of a pasted invite link', async () => {
    const { getByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'https://trove.example.test/invite/INVITE-TOKEN');

    expect(mockPreviewInvite).toHaveBeenCalledWith('INVITE-TOKEN');
    expect(getByTestId('setup-invite-valid')).toBeTruthy();
  });

  it('registers with the token it extracted, not with the whole link', async () => {
    const { getByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'https://trove.example.test/invite/INVITE-TOKEN');
    fill(getByTestId as Query);
    await submit(getByTestId as Query);

    expect(mockRegister).toHaveBeenCalledWith(
      expect.objectContaining({ inviteToken: 'INVITE-TOKEN' })
    );
  });

  // `trove://setup?token=…` — expo-router maps the app scheme onto this
  // route's search params, so the deep link needs no linking code here.
  it('seeds the field from the invite deep link', () => {
    mockParams.mockReturnValue({ token: 'DEEP-LINK-INVITE' });
    const { getByTestId } = render(<SetupScreen />);
    expect(getByTestId('setup-invite-input').props.value).toBe('DEEP-LINK-INVITE');
  });

  // A bound invitation must be redeemed with the address it names, so the
  // screen says which before the member picks a different one and is refused.
  it('names the address a bound invitation is tied to', async () => {
    mockPreviewInvite.mockResolvedValue({
      valid: true,
      email: 'invitee@example.test',
      expiresAt: '2026-09-22T14:00:00Z',
    });
    const { getByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'INVITE-TOKEN');

    expect(getByTestId('setup-invite-valid').props.children).toContain('invitee@example.test');
  });

  // The route is **always 200** — the verdict is in the body, never in the
  // status, so an unknown, revoked, used and expired token are one answer and
  // the route cannot be used to tell a real token from a near miss. The copy
  // must not pretend to tell them apart either.
  it('renders one undifferentiated refusal for a spent token, from a 200 body', async () => {
    mockPreviewInvite.mockResolvedValue({ valid: false, email: null, expiresAt: null });
    const { getByTestId, queryByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'SPENT-TOKEN');

    const message = getByTestId('setup-invite-invalid').props.children as string;
    expect(message).toContain('can’t be used');
    expect(message).not.toMatch(/expired|unknown|already|revoked/i);
    expect(queryByTestId('setup-invite-valid')).toBeNull();
  });

  // A red "invalid" caused by a dropped connection would send the member back
  // to their admin for a link that was fine. Registration still tells the
  // truth, so silence is the honest state.
  it('says nothing when the preview request itself fails', async () => {
    mockPreviewInvite.mockRejectedValue(new Error('offline'));
    const { getByTestId, queryByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'INVITE-TOKEN');

    expect(queryByTestId('setup-invite-invalid')).toBeNull();
    expect(queryByTestId('setup-invite-valid')).toBeNull();
  });

  // The token is a credential and it travels in the request PATH, which
  // `sanitise()` keeps verbatim (its redaction only fires on a `token=` /
  // `token:` key-value shape, never on a bare path segment). A failed preview
  // is exactly the case where the invitation is still redeemable, so nothing
  // about it may reach the device log, which any other app with log access can
  // read.
  it('never writes the invitation token to the device log when the request fails', async () => {
    const logged = jest.spyOn(console, 'error').mockImplementation(() => {});
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const config = { url: '/v1/auth/invites/INVITE-TOKEN', method: 'get', headers: {} } as any;
      mockPreviewInvite.mockRejectedValue(
        new AxiosError('Network Error', AxiosError.ERR_NETWORK, config)
      );
      const { getByTestId } = render(<SetupScreen />);
      await pasteInvite(getByTestId as Query, 'INVITE-TOKEN');

      expect(logged).not.toHaveBeenCalled();
      expect(JSON.stringify(logged.mock.calls)).not.toContain('INVITE-TOKEN');
    } finally {
      logged.mockRestore();
    }
  });

  // The verdict appears dynamically after the field loses focus and is the
  // only feedback that a pasted invitation works, so it is announced rather
  // than merely drawn.
  it('announces the verdict to assistive technology', async () => {
    const { getByTestId, rerender } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'INVITE-TOKEN');
    expect(getByTestId('setup-invite-valid').props.accessibilityRole).toBe('alert');

    mockPreviewInvite.mockResolvedValue({ valid: false, email: null, expiresAt: null });
    rerender(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'SPENT-TOKEN');
    expect(getByTestId('setup-invite-invalid').props.accessibilityRole).toBe('alert');
  });

  it('asks the server nothing when the field is empty', async () => {
    const { getByTestId, queryByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, '   ');

    expect(mockPreviewInvite).not.toHaveBeenCalled();
    expect(queryByTestId('setup-invite-valid')).toBeNull();
  });

  // The verdict belongs to the token that produced it — leaving a green
  // "this invitation is good" above a token the member has since edited is
  // how somebody registers against a link they no longer hold.
  it('drops a stale verdict as soon as the field is edited again', async () => {
    const { getByTestId, queryByTestId } = render(<SetupScreen />);
    await pasteInvite(getByTestId as Query, 'INVITE-TOKEN');
    expect(getByTestId('setup-invite-valid')).toBeTruthy();

    fireEvent.changeText(getByTestId('setup-invite-input'), 'INVITE-TOKEN-2');
    expect(queryByTestId('setup-invite-valid')).toBeNull();
  });
});
