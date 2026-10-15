package finance.trove.auth.jwt;

/**
 * How a request proved its identity — the request attribute {@code trove.authSource} of
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.1.
 *
 * <p>It exists because CSRF only applies to one of the two: a cookie is attached by the browser
 * whether or not the page that triggered the request is ours, while an {@code Authorization}
 * header is only ever set by code the caller wrote. The pre-collapse {@code CsrfRequiresHeaderFilter}
 * rejected every mutating request without {@code X-Requested-With} unconditionally, which would
 * 403 mobile's bearer-only {@code POST /v1/auth/login} the moment the mobile client lands
 * (§2.1, major). Recording the source is what lets the guard apply to cookie traffic only.
 */
public enum AuthSource {

    /** The {@code __Host-trove_access} cookie — a browser session, CSRF-relevant. */
    COOKIE,

    /** An {@code Authorization: Bearer} header — mobile, CLI, tests. Not CSRF-reachable. */
    BEARER;

    /** Request attribute name; {@code CsrfRequiresHeaderFilter} reads it. */
    public static final String ATTRIBUTE = "trove.authSource";
}
