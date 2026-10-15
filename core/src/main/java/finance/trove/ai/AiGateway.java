package finance.trove.ai;

import java.util.UUID;

/**
 * The seam collapse steps 13 (coach + insights), 14 (receipt vision) and 17 (categorisation
 * fallback) call. It is the only way into an AI endpoint from anywhere else in the monolith
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6; §9 risk 10).
 *
 * <p>One method, because the first thing every caller has to do is resolve <em>whose</em> AI this
 * is. The endpoint, the key, the models, the timeouts and the privacy tier are all per user (owner
 * decision 9), so there is no "the AI" to call — there is this member's, and a caller that did not
 * name a member would be reaching for an installation-wide endpoint that does not exist.
 *
 * <p><strong>Notes for later steps.</strong>
 * <ul>
 *   <li>{@code gateway.forUser(principal.getUserId())} always returns a handle; it never returns
 *       null and never throws. A member with no configuration returns a handle whose
 *       {@link AiHandle#isEnabled()} is false.</li>
 *   <li>Check {@link AiHandle#isEnabled()} and take the non-AI path, or call and catch
 *       {@link AiDisabledException} — both are supported and the second is not an error condition.
 *       AI is off by default on every install and no feature may require it: step 13's coach
 *       offers the settings screen, step 14's receipts fall back to manual entry, step 17's
 *       categorisation falls back to rules.</li>
 *   <li>Every call states its own {@link AiSensitivity}. Anything built from the household's
 *       ledger — coach context, a receipt image, a merchant description — is
 *       {@link AiSensitivity#SENSITIVE} and is refused with {@link AiPolicyBlockedException}
 *       against a {@link PrivacyTier#CLOUD_OK} endpoint.</li>
 *   <li>{@link AiHandle#chatStream} is what step 13's SSE controller drives: deltas arrive on the
 *       listener as they arrive on the socket, {@code onDone} corresponds to the {@code [DONE]}
 *       sentinel of the §4.11 wire contract, and {@code onError} is where its in-band error frame
 *       comes from. The same exception is then thrown, so the controller can stop and the audit
 *       row still gets written.</li>
 *   <li>The egress policy runs, and the address is re-pinned, on <em>every</em> call — not once at
 *       save time. A later step needs no check of its own.</li>
 *   <li>Every call writes one {@code ai_audit_log} row, success or failure. A later step does not
 *       need to, and must not write the prompt or the answer to it.</li>
 * </ul>
 */
public interface AiGateway {

    /**
     * A handle on {@code userId}'s own AI configuration.
     *
     * @param userId from {@code @AuthenticationPrincipal TroveUserDetails#getUserId()}, or the
     *               owner of the row being processed on a background thread. Never from a path,
     *               a body or a header
     */
    AiHandle forUser(UUID userId);
}
