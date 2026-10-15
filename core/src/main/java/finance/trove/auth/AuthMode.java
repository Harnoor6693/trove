package finance.trove.auth;

/**
 * How the caller wants its tokens delivered — the {@code X-Trove-Auth-Mode} header, part of the
 * login contract the shipped web and mobile clients were written against.
 *
 * <p>One login endpoint serves a browser and a phone, and they need opposite things. A browser
 * must not be able to read its own session token from JavaScript, so its tokens go out as
 * {@code HttpOnly} {@code __Host-} cookies and the body carries none. A phone has no cookie jar
 * worth relying on and a keystore that is better than one, so it takes the tokens in the body.
 *
 * <p><strong>The default is {@link #BEARER}</strong>, which is the safe direction to get wrong:
 * a caller that forgets the header gets tokens in a body it can ignore, whereas defaulting to
 * cookies would silently start setting session cookies for API clients that never asked for them.
 * The web client sends {@code cookie} explicitly on the three routes that mint a session
 * ({@code apps/web/src/api/auth.ts:26}); mobile sends {@code bearer} on every request
 * ({@code apps/mobile/src/api/client.ts:55}).
 */
public enum AuthMode {

    /** Tokens in {@code __Host-trove_access} / {@code __Host-trove_refresh}; body carries none. */
    COOKIE,

    /** Tokens in the response body; the client stores them itself. */
    BEARER;

    /** The header name, spelled exactly as both clients send it. */
    public static final String HEADER = "X-Trove-Auth-Mode";

    /**
     * Parses the header. Anything unrecognised — absent, empty, misspelled — is {@link #BEARER}.
     * An unparseable mode must not be a 400: it would turn a typo in a client header into an
     * un-loggable-in application, and the safe interpretation is available.
     */
    public static AuthMode from(String headerValue) {
        return headerValue != null && "cookie".equalsIgnoreCase(headerValue.strip())
            ? COOKIE
            : BEARER;
    }

    public boolean isCookie() {
        return this == COOKIE;
    }
}
