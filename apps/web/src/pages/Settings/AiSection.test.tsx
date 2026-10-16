/**
 * The AI endpoint section.
 *
 * The load-bearing test in this file is the one that asserts a NEGATIVE: the API
 * key never appears in the rendered output, in any form, at any point. The
 * server has no route that returns it, so a key on this screen could only ever
 * be one the client kept — which is the defect these assertions exist to catch.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { makeAiSettings, makeConfiguredAiSettings, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { AiSection } from './AiSection'

const TYPED_KEY = 'sk-typed-by-the-member-not-a-real-key'

beforeEach(() => {
  useAuthStore.setState({
    status: 'authenticated',
    user: {
      userId: mswState.me.userId,
      email: mswState.me.email,
      displayName: mswState.me.displayName,
      householdId: mswState.me.householdId,
      role: mswState.me.role,
      totpEnabled: false,
      currency: mswState.me.currency,
      country: mswState.me.country,
      timezone: mswState.me.timezone,
      locale: mswState.me.locale,
      onboardingCompleted: true,
    },
  })
})

describe('AI settings — the API key is never echoed', () => {
  it('shows only WHETHER a key is stored, never a value and never an invented mask', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings({ hasApiKey: true }) })
    const { container } = renderWithProviders(<AiSection />)

    expect(await screen.findByTestId('ai-has-key')).toHaveTextContent('One is stored')
    // The field itself is empty on every render: there is nothing to seed it from.
    expect(screen.getByLabelText('API key')).toHaveValue('')
    // No stand-in either. A row of dots that is not the length of the real key
    // is a lie about the member's own secret.
    expect(container.textContent).not.toMatch(/•{3,}|\*{3,}/)
  })

  it('never puts a typed key on the screen or in a snapshot after it is sent', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings({ hasApiKey: false }) })
    const user = userEvent.setup()
    const { container } = renderWithProviders(<AiSection />)

    const field = await screen.findByLabelText('API key')
    await user.type(field, TYPED_KEY)
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    await waitFor(() => expect(screen.getByTestId('ai-saved')).toBeInTheDocument())
    // The key went to the server…
    expect(mswState.lastAiSettingsBody?.apiKey).toBe(TYPED_KEY)
    // …the server recorded only that one exists…
    expect(mswState.aiSettings.hasApiKey).toBe(true)
    // …and no trace of the value survives on the page.
    expect(screen.getByLabelText('API key')).toHaveValue('')
    expect(container.innerHTML).not.toContain(TYPED_KEY)
    expect(container.textContent).not.toContain(TYPED_KEY)
  })

  it('omits `apiKey` entirely rather than sending a blank one, which is a 400', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings({ hasApiKey: true }) })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    await screen.findByLabelText('API key')
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    await waitFor(() => expect(mswState.lastAiSettingsBody).not.toBeNull())
    // Absent leaves the stored key alone; blank would be refused, not a clear.
    expect(mswState.lastAiSettingsBody).not.toHaveProperty('apiKey')
    expect(mswState.aiSettings.hasApiKey).toBe(true)
  })

  it('leaves no copy of the typed key in the query client, mutation cache included', async () => {
    // The page is not the only place a client can keep a secret. TanStack Query
    // KEEPS a settled mutation's `variables` — its reducer's `success` branch
    // spreads the previous state and never clears them — and the `Mutation`
    // object stays in the mutation cache for its gc window after that. A key
    // passed as a mutation variable would therefore still be readable from a
    // state dump or a devtools panel minutes after the save, which is exactly
    // the "the client kept a copy of my secret" defect the write-only field
    // exists to prevent. So the key travels out of band, and this asserts it.
    resetMswState({ aiSettings: makeConfiguredAiSettings({ hasApiKey: false }) })
    const user = userEvent.setup()
    const { client } = renderWithProviders(<AiSection />)

    await user.type(await screen.findByLabelText('API key'), TYPED_KEY)
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    // It really was sent — this does not pass because nothing happened.
    await waitFor(() => expect(mswState.lastAiSettingsBody?.apiKey).toBe(TYPED_KEY))
    await waitFor(() => expect(screen.getByTestId('ai-saved')).toBeInTheDocument())

    const mutations = client.getMutationCache().getAll()
    expect(mutations.length).toBeGreaterThan(0)
    for (const mutation of mutations) {
      expect(JSON.stringify(mutation.state.variables ?? null)).not.toContain(TYPED_KEY)
    }
    // …and no other corner of either cache holds it either.
    expect(JSON.stringify(mutations.map((m) => m.state))).not.toContain(TYPED_KEY)
    expect(JSON.stringify(client.getQueryCache().getAll().map((q) => q.state))).not.toContain(
      TYPED_KEY
    )
  })

  it('clears a stored key through its own route', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings({ hasApiKey: true }) })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    await user.click(await screen.findByRole('button', { name: 'Forget the stored key' }))
    await waitFor(() => expect(mswState.aiSettings.hasApiKey).toBe(false))
    await waitFor(() => expect(screen.getByTestId('ai-has-key')).toHaveTextContent('None'))
  })
})

describe('AI settings — the replace, the picker and the tier', () => {
  it('sends the WHOLE shape, because PUT is a replace and an omitted field is written null', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings() })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    await screen.findByLabelText('Endpoint URL')
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    await waitFor(() => expect(mswState.lastAiSettingsBody).not.toBeNull())
    const body = mswState.lastAiSettingsBody
    // The three the contract requires on every save…
    expect(body).toMatchObject({
      enabled: true,
      allowPrivateEndpoint: false,
      privacyTier: 'LOCAL_ONLY',
    })
    // …and everything else resent, so nothing the member did not touch is nulled.
    expect(body?.endpointUrl).toBe('https://ai.example.test/v1')
    expect(body?.model).toBe('qwen2.5:14b')
    expect(body?.visionModel).toBe('qwen2.5-vl:7b')
    expect(body?.readTimeoutMs).toBe(120000)
  })

  it('populates the model picker from the endpoint’s own list, before a model is chosen', async () => {
    // An endpoint saved, no model yet — `listAiModels` works in exactly this state.
    resetMswState({
      aiSettings: makeAiSettings({ endpointUrl: 'https://ai.example.test/v1', model: null }),
    })
    renderWithProviders(<AiSection />)

    const picker = await screen.findByTestId('ai-model-select')
    expect(picker).toHaveTextContent('qwen2.5:14b')
    expect(picker).toHaveValue('')
  })

  it('falls back to a text field, and explains why, when no endpoint is saved yet', async () => {
    resetMswState({ aiSettings: makeAiSettings() })
    renderWithProviders(<AiSection />)

    await screen.findByLabelText('Endpoint URL')
    expect(screen.queryByTestId('ai-model-select')).not.toBeInTheDocument()
    expect(screen.getByText(/Trove will offer the models it serves/i)).toBeInTheDocument()
  })

  it('says what each privacy tier does to a coach question', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings({ privacyTier: 'LOCAL_ONLY' }) })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    expect(await screen.findByTestId('ai-tier-note')).toHaveTextContent(/what the coach needs/i)

    await user.click(screen.getByRole('radio', { name: 'Somebody else’s server' }))
    expect(screen.getByTestId('ai-tier-note')).toHaveTextContent(/coach is refused/i)
  })
})

describe('AI settings — the failures the operations declare', () => {
  it('shows the egress refusal in the member’s words, not a generic failure', async () => {
    resetMswState({
      aiSettings: makeAiSettings(),
      aiSettingsSaveFailure: {
        status: 400,
        slug: 'outbound-url-rejected',
        title: 'Bad Request',
        detail: 'Your endpoint must use https unless private targets are allowed.',
      },
    })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    const url = await screen.findByLabelText('Endpoint URL')
    await user.type(url, 'http://192.168.1.10:11434/v1')
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    expect(await screen.findByTestId('ai-save-error')).toHaveTextContent(
      'Your endpoint must use https unless private targets are allowed.'
    )
  })

  it('does not pre-validate the URL beyond obvious shape — the server’s policy decides', async () => {
    resetMswState({ aiSettings: makeAiSettings() })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    const url = await screen.findByLabelText('Endpoint URL')
    await user.type(url, 'http://localhost:11434/v1')
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    // The request was actually made rather than blocked client-side: the egress
    // policy is the server's and is re-evaluated on every outbound call anyway.
    await waitFor(() =>
      expect(mswState.lastAiSettingsBody?.endpointUrl).toBe('http://localhost:11434/v1')
    )
  })

  it('reports a connection test with the host it reached, and never a key', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings() })
    const user = userEvent.setup()
    const { container } = renderWithProviders(<AiSection />)

    await user.click(await screen.findByRole('button', { name: 'Test the connection' }))
    const ok = await screen.findByTestId('ai-test-ok')
    expect(ok).toHaveTextContent('ai.example.test')
    expect(container.textContent).not.toMatch(/sk-/)
  })

  it('translates a 502 ai-endpoint-unauthorized into a settings problem the member can fix', async () => {
    resetMswState({
      aiSettings: makeConfiguredAiSettings(),
      aiTestFailure: { status: 502, slug: 'ai-endpoint-unauthorized', title: 'Bad Gateway' },
    })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    await user.click(await screen.findByRole('button', { name: 'Test the connection' }))
    expect(await screen.findByTestId('ai-test-error')).toHaveTextContent(
      /rejected the stored API key/i
    )
  })

  it('reads a 409 on the model list as "nothing to contact yet", not "the feature is off"', async () => {
    resetMswState({
      aiSettings: makeConfiguredAiSettings(),
      aiModelsFailure: { status: 409, slug: 'ai-disabled', title: 'Conflict' },
    })
    renderWithProviders(<AiSection />)

    expect(await screen.findByTestId('ai-models-error')).toHaveTextContent(
      /Save an endpoint address first/i
    )
  })
})

describe('AI settings — numeric fields (B4-05)', () => {
  it('refuses non-numeric timeouts with a field error and sends nothing', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings({ hasApiKey: true }) })
    const user = userEvent.setup()
    renderWithProviders(<AiSection />)

    const field = await screen.findByLabelText('Connect timeout (milliseconds)')
    await user.clear(field)
    await user.type(field, 'abc')
    await user.click(screen.getByRole('button', { name: 'Save AI settings' }))

    expect(await screen.findByText('Enter a whole number.')).toBeInTheDocument()
    expect(mswState.lastAiSettingsBody).toBeNull()
    expect(screen.queryByTestId('ai-saved')).not.toBeInTheDocument()
  })
})
