package finance.trove.common.net;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * The one place the caller's address is decided ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5,
 * §5.1; readiness gap P2-5).
 *
 * <h2>Why this class exists rather than three call sites reading a header</h2>
 * Three controls key on the client address — the per-address login gate
 * ({@code LockoutService}), every {@code security_event} and {@code audit_log} row, and the rate
 * limiter — and the pre-collapse gateway resolved it independently in two of them, both times as
 * {@code request.getHeader("X-Forwarded-For").split(",")[0]}. That expression takes the
 * <em>leftmost</em> value of a header any client can send, so a caller who prefixed its own hop
 * chose which bucket it landed in and which address was recorded against it: every per-IP control
 * in the application was a suggestion. On the owner's public-internet decision that is a standing
 * gap, and it is fixed structurally rather than by remembering not to do it — {@code ArchitectureTest}
 * holds the fix, and {@code SourceConventionTest} already bans the identity-header equivalent.
 *
 * <h2>Where the value comes from</h2>
 * {@link HttpServletRequest#getRemoteAddr()} and nothing else. Behind Traefik that is Tomcat's
 * {@code RemoteIpValve} answering, configured from {@code TROVE_TRUSTED_PROXIES} with the compose
 * network only: the valve walks {@code X-Forwarded-For} from the <em>right</em> and stops at the
 * first hop that is not a trusted proxy, so the address it settles on is the one the outermost
 * trusted proxy actually saw. A client that prepends {@code X-Forwarded-For: 10.0.0.1} moves
 * nothing — its own address is appended to the right of that lie by the proxy it really came
 * through, and that is the value taken. With no proxy in front, the header is ignored entirely and
 * the socket peer is used.
 *
 * <p>So the correctness of every per-address control in this application rests on
 * {@code server.tomcat.remoteip.internal-proxies} listing the deployment's proxies and nothing
 * else. Widening it to a public range would hand the spoof back.
 *
 * @see finance.trove.app.ratelimit.RateLimitFilter
 */
public final class ClientIpResolver {

    /** Longest textual IPv6 address, {@code ::ffff:} form included; the column widths match. */
    private static final int MAX_LENGTH = 45;

    private ClientIpResolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The caller's address, or {@code null} when there is no usable one.
     *
     * <p>Null rather than a placeholder string, because every consumer already has to handle "no
     * request in scope" — a scheduled job, a console command, a test calling a service directly —
     * and an {@code inet} column takes a null but not an {@code "unknown"}.
     *
     * @param request the current request, or {@code null} outside one
     */
    public static String resolve(HttpServletRequest request) {
        return request == null ? null : normalise(request.getRemoteAddr());
    }

    /**
     * Trims the forms a servlet container can hand back that Postgres' {@code inet} will not take:
     * a bracketed IPv6 literal, an IPv6 zone index, surrounding whitespace, and anything
     * implausibly long.
     *
     * <p>Visible for testing, and deliberately conservative: this method's job is to leave a value
     * that either parses as an address or is dropped, never to guess at one.
     */
    static String normalise(String remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        String value = remoteAddress.strip();
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        // A link-local IPv6 address arrives as fe80::1%eth0; the scope is local to the machine
        // that produced it and is meaningless in a stored row or a bucket key.
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
