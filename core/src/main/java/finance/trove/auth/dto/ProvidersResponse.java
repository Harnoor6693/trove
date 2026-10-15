package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code GET /v1/auth/providers} — public, unauthenticated (handoff §4a point 3).
 *
 * <p>The login page renders itself from this and nothing else. Before the collapse it linked to
 * {@code /v1/auth/start} unconditionally, which 404s on the majority of installs that never
 * configure an identity provider; and it had no way at all to tell a user that registration is
 * closed, so the "create an account" path dead-ended in a 403 after they had typed a password.
 *
 * <p>{@code startUrl} and {@code displayName} are emitted as explicit {@code null} rather than
 * omitted when OIDC is off. The web client types {@code displayName} as {@code string | null} and
 * renders {@code oidc.displayName ?? 'your identity provider'}
 * ({@code apps/web/src/pages/Login/index.tsx:181}), so a present null is the shape it was written
 * against. The web client does not read {@code startUrl} at all — it hardcodes
 * {@code /api/v1/auth/start} ({@code apps/web/src/api/auth.ts:101}) — but the handoff contract
 * names the field and mobile may use it, so it is populated rather than dropped.
 *
 * @param registrationOpen whether self-serve signup without an invite is accepted right now; the
 *                         live value from {@code households.registration_open}, not the seed
 * @param local            whether password login exists at all; always true today, present so an
 *                         OIDC-only install stays expressible without a client change
 * @param oidc             the optional single-sign-on button
 */
public record ProvidersResponse(boolean registrationOpen, boolean local, Oidc oidc) {

    /**
     * @param enabled     whether the provider is configured on this install
     * @param startUrl    where to send the browser; absent when {@code enabled} is false
     * @param displayName the label for the button; absent when {@code enabled} is false
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Oidc(boolean enabled, String startUrl, String displayName) {

        public static Oidc disabled() {
            return new Oidc(false, null, null);
        }

        public static Oidc enabled(String startUrl, String displayName) {
            return new Oidc(true, startUrl, displayName);
        }
    }
}
