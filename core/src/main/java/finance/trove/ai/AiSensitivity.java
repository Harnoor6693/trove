package finance.trove.ai;

/**
 * How sensitive an outbound AI payload is, decided by the caller that builds it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6).
 *
 * <p>It is a parameter rather than something inferred inside the gateway on purpose. The source
 * carried a whole {@code libs/privacy-engine} — a classifier plus an AOP aspect — that guessed at
 * the tier of a payload it could not see the provenance of; the classification that actually
 * matters is "does this text contain the household's transactions", and only the code that
 * assembled the text knows. Steps 13, 14 and 17 each state it for their own calls.
 */
public enum AiSensitivity {

    /** Nothing derived from the household's ledger: a bare question, a model list, a health probe. */
    ORDINARY,

    /**
     * Carries the household's financial data — coach context, a receipt image, a merchant
     * description being categorised. Refused against a {@link PrivacyTier#CLOUD_OK} endpoint.
     */
    SENSITIVE
}
