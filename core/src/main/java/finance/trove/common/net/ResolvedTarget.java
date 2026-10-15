package finance.trove.common.net;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * A destination that {@link OutboundUrlPolicy} has already approved, carrying the exact IP the
 * HTTP client must connect to.
 *
 * <p>This record is the mechanism that closes the DNS-rebinding TOCTOU
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8/§5.5, §9 risk 9). Validating a URL and then
 * handing the <em>URL</em> to an HTTP client means two independent lookups: an attacker who
 * controls the authoritative nameserver answers a public address for the check and
 * {@code 169.254.169.254} for the connection. Passing this record instead means the client
 * connects to {@link #ip()} — the address that was actually validated — while still sending
 * {@code host} in the {@code Host} header and in the TLS SNI, so certificate validation and
 * virtual hosting still work.
 *
 * @param host         the hostname from the URL, used for {@code Host}/SNI and never re-resolved
 * @param ip           the pinned address to connect to; one of {@link #allAddresses()}
 * @param port         the effective port, defaulted from the scheme when the URL omitted it
 * @param uri          the normalised URI that was validated
 * @param allAddresses every address the name resolved to; all of them passed the policy
 */
public record ResolvedTarget(String host, InetAddress ip, int port, URI uri,
                             List<InetAddress> allAddresses) {

    public ResolvedTarget {
        Objects.requireNonNull(host, "host cannot be null");
        Objects.requireNonNull(ip, "ip cannot be null");
        Objects.requireNonNull(uri, "uri cannot be null");
        allAddresses = List.copyOf(Objects.requireNonNull(allAddresses, "allAddresses"));
        if (allAddresses.isEmpty()) {
            throw new IllegalArgumentException("a resolved target has at least one address");
        }
    }

    public String scheme() {
        return uri.getScheme();
    }

    /** Whether the approved scheme is {@code https}. */
    public boolean secure() {
        return "https".equals(uri.getScheme());
    }

    /** {@code https://host:port} — the origin, without the path, for logs and audit rows. */
    public String origin() {
        return scheme() + "://" + host + ":" + port;
    }
}
