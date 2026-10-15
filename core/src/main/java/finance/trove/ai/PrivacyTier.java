package finance.trove.ai;

/**
 * How far a member lets their own data travel to their own AI endpoint
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, owner decision 9; schema V16
 * {@code ai_settings.privacy_tier}).
 *
 * <p>This is a statement about what <strong>Trove sends</strong>, not a claim about what the
 * endpoint does with it. Trove cannot verify an endpoint's behaviour and does not pretend to: the
 * member says whether the host at the other end is theirs, and the gateway holds back the payloads
 * that answer says it should.
 *
 * <p>The source's four-value {@code PrivacyTier} ({@code LOCAL_FAST}, {@code LOCAL_QUALITY},
 * {@code CLOUD}, {@code HIGH}) was two orthogonal ideas — how big the model is, and where it runs —
 * pushed into one enum, and the model half belonged to a multi-provider router that no longer
 * exists. Only the "where does this run" half is kept.
 */
public enum PrivacyTier {

    /**
     * The endpoint is a private model the member controls — Ollama on the LAN, or on this very
     * box. Payloads classified {@link AiSensitivity#SENSITIVE} may be sent to it.
     */
    LOCAL_ONLY,

    /**
     * The endpoint is somebody else's server and the member accepts that. Ordinary payloads go;
     * {@link AiSensitivity#SENSITIVE} ones are refused with {@code ai-policy-blocked} rather than
     * quietly sent.
     */
    CLOUD_OK;

    /** Whether a payload of {@code sensitivity} may be sent to an endpoint at this tier. */
    public boolean permits(AiSensitivity sensitivity) {
        return this == LOCAL_ONLY || sensitivity != AiSensitivity.SENSITIVE;
    }
}
