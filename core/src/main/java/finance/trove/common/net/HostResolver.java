package finance.trove.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The single point at which Trove asks the operating system to resolve a hostname.
 *
 * <p>It exists as an interface for one reason: {@link OutboundUrlPolicy}'s defence against DNS
 * rebinding is only meaningful if it can be tested, and that means a test needs a resolver that
 * answers a public address the first time and a private one the second. There is no production
 * implementation other than {@link #SYSTEM}.
 */
@FunctionalInterface
public interface HostResolver {

    /** The real resolver: every A and AAAA record the system resolver returns. */
    HostResolver SYSTEM = InetAddress::getAllByName;

    /**
     * Resolves every address for {@code host}.
     *
     * @throws UnknownHostException if the name does not resolve
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
