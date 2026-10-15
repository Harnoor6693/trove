package finance.trove.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The Apache HttpClient {@link DnsResolver} that makes {@link ResolvedTarget}'s pin real: it
 * answers with the address {@link OutboundUrlPolicy} already approved, and refuses to resolve
 * anything else at all.
 *
 * <p>This is the connect-time half of the DNS-rebinding defence
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5, §9 risk 9). Validation happening at time T
 * and the socket opening at T+1 is a hole whenever the attacker owns the nameserver and can
 * lower the TTL to zero; the fix is not to look the name up twice. Because the pin is installed
 * at the DNS layer rather than by rewriting the URL, the request still carries the real
 * {@code Host} header and the TLS handshake still uses the real SNI, so certificate validation
 * is unaffected.
 *
 * <p>Refusing every other hostname is deliberate: a 30x to a second host, or a client that
 * decides to resolve something on its own, gets an {@link UnknownHostException} rather than a
 * connection. Redirects are disabled on these clients anyway (§5.5) — this is the second lock.
 */
public final class PinnedDnsResolver implements DnsResolver {

    private final String host;
    private final InetAddress[] pinned;

    public PinnedDnsResolver(ResolvedTarget target) {
        this.host = target.host().toLowerCase(Locale.ROOT);
        this.pinned = new InetAddress[] {target.ip()};
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        if (requestedHost == null || !host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
            throw new UnknownHostException("This client is pinned to " + host
                + " and will not resolve " + requestedHost);
        }
        return pinned.clone();
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        resolve(requestedHost);
        return host;
    }
}
