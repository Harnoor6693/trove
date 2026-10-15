package finance.trove.common.plugin;

/**
 * Where a {@code connected_account}'s transactions come from
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V18, §2.2).
 *
 * <p>It replaces the old {@code connected_account.source_plugin VARCHAR(50)}, a free-text bean
 * name: a typo in that column produced an account that nothing could ever sync and nothing could
 * ever diagnose. A closed enum with a matching database CHECK makes the same mistake a compile
 * error on one side and a constraint violation on the other.
 *
 * <p>It lives in {@code common.plugin} rather than in {@code finance.trove.accounts} because
 * {@link BankSource} is keyed on it, and {@code ArchitectureTest} forbids {@code common} from
 * depending on any other {@code finance.trove} package — the SPI's own vocabulary has to be
 * inside the SPI's own package.
 */
public enum SourceKind {

    /** The SimpleFIN bridge: credentials exchanged once, then fetched over HTTPS (step 7). */
    SIMPLEFIN,

    /** An uploaded OFX/QFX file (step 7). */
    OFX,

    /** An uploaded CSV file with a per-account column mapping (step 7). */
    CSV,

    /**
     * A credential-less account with no source at all (gap P2-13). Nothing fetches for it, it
     * has no {@code config_encrypted}, and it is the only kind of account that accepts a
     * hand-entered transaction through {@code POST /v1/transactions}.
     */
    MANUAL
}
