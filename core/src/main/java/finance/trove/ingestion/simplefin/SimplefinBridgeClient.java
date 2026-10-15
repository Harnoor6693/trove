package finance.trove.ingestion.simplefin;

import finance.trove.app.HttpClientConfig;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.net.ResolvedTarget;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.ingestion.simplefin.dto.SimplefinAccountSet;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The only code that talks to a SimpleFIN bridge ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5,
 * §6 "SimpleFIN (optional)"; gap P2-10; {@code docs/recovery/01-FINDINGS.md} G2).
 *
 * <h2>Every call is validated and pinned</h2>
 * Both entry points put their URL through {@link OutboundUrlPolicy} and then build the client with
 * {@link HttpClientConfig.PinnedRestClientFactory#builderFor(ResolvedTarget, java.time.Duration,
 * java.time.Duration)}, so the socket opens to the address the policy approved rather than to a
 * second lookup's answer. That is what closes G2: the pre-collapse exchanger validated the decoded
 * setup URL with {@code decodedUrl.startsWith("http")} and then <em>stored the result encrypted</em>,
 * turning one bad token into a standing SSRF primitive with no validation on any later call. Here
 * the STORED access URL is re-checked on every fetch, because a stored URL is still a URL an
 * attacker once influenced, and because the name behind it can start resolving somewhere else.
 *
 * <h2>A SimpleFIN access URL carries HTTP Basic credentials in its userinfo</h2>
 * The protocol's access URL is {@code https://USER:PASS@bridge.example.com/simplefin} — the
 * credential is the {@code user:pass} <em>before</em> the host, not part of the path.
 * {@link OutboundUrlPolicy} refuses any URL with userinfo outright, and it is right to: a URL that
 * smuggles credentials past a human reader is exactly what that rule exists to stop. So the
 * credential is taken OFF the URL here, before the policy ever sees it — {@link #split} — and put
 * back on the wire as an {@code Authorization: Basic} header on the pinned client. The policy
 * therefore still governs the destination (scheme, resolved address, no userinfo), and the bridge
 * still gets the credential it requires. Without this the very first fetch after a successful
 * claim would be refused, after the one-time setup token had already been spent.
 *
 * <h2>Nothing about the URLs is ever logged, returned, or put in a message</h2>
 * The decoded setup URL is a one-time credential and the access URL embeds one. Neither appears in
 * a log line, in an exception message, in an error {@code detail} or in any response body — and
 * neither does the userinfo taken off it, nor the credential-free URL the policy was given.
 * {@link #redact} is what enforces that on the one message that is written elsewhere and could
 * otherwise quote a URL back: {@code OutboundUrlPolicy}'s own "the URL must be absolute: …"
 * refusal echoes what it was given.
 *
 * <h2>The response is capped</h2>
 * A bridge is a third party on the public internet. {@link SimplefinProperties#maxResponseBytes()}
 * bounds what is read (gap P2-10), so an answer that never ends fails the call instead of
 * exhausting the heap of a process that also holds the household's ledger.
 */
@Component
public class SimplefinBridgeClient {

    private static final Logger LOG = LoggerFactory.getLogger(SimplefinBridgeClient.class);

    /** What is quoted instead of a URL in any message that escapes this class. */
    static final String REDACTED = "<the SimpleFIN URL>";

    /** A claim response is one URL; anything larger is not one. */
    static final int MAX_ACCESS_URL_BYTES = 4096;

    private final OutboundUrlPolicy egress;
    private final HttpClientConfig.PinnedRestClientFactory clients;
    private final SimplefinProperties properties;
    private final ObjectMapper objectMapper;

    public SimplefinBridgeClient(OutboundUrlPolicy egress,
                                 HttpClientConfig.PinnedRestClientFactory clients,
                                 SimplefinProperties properties, ObjectMapper objectMapper) {
        this.egress = egress;
        this.clients = clients;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Spends a one-time claim URL and returns the permanent access URL.
     *
     * <p>An empty POST, exactly as SimpleFIN specifies, and <strong>the response body IS the access
     * URL</strong> — with credentials embedded in it. The caller encrypts it immediately and never
     * lets it out again.
     *
     * @param claimUrl the URL the base64 setup token decoded to
     * @return the permanent access URL, trimmed
     * @throws OutboundUrlPolicyException  if the URL fails the egress policy — 400
     *                                     {@code outbound-url-rejected}, naming the rule and never
     *                                     the address or the URL
     * @throws ConflictException           if the bridge says the token has already been spent
     * @throws BankSourceConnectionException if the bridge could not be reached or refused us
     */
    public String claimAccessUrl(String claimUrl) {
        Credentialed split = split(claimUrl);
        Endpoint endpoint = check(claimUrl, split);
        RestClient client = pinned(endpoint);
        String accessUrl;
        try {
            accessUrl = client.post()
                .uri(endpoint.target().uri())
                .header(HttpHeaders.CONTENT_LENGTH, "0")
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 403 || status.value() == 410) {
                        throw new ConflictException("That SimpleFIN setup token has already been "
                            + "used. Generate a new one from your bridge.");
                    }
                    if (!status.is2xxSuccessful()) {
                        throw new BankSourceConnectionException(
                            "The SimpleFIN bridge answered HTTP " + status.value()
                                + " to the setup-token exchange.");
                    }
                    return new String(readCapped(response.getBody(), MAX_ACCESS_URL_BYTES),
                        StandardCharsets.UTF_8).trim();
                });
        } catch (ConflictException | BankSourceConnectionException e) {
            throw e;
        } catch (RestClientException e) {
            throw unreachable(e, claimUrl, split);
        }
        if (accessUrl == null || accessUrl.isBlank()) {
            throw new BankSourceConnectionException(
                "The SimpleFIN bridge accepted the setup token but returned no access URL.");
        }
        return accessUrl;
    }

    /**
     * Reads the accounts an access URL reaches, from {@code from} onwards.
     *
     * <p><strong>The window is sent to the bridge</strong> as {@code start-date}, which is the
     * second half of plugins.md's finding that {@code SimplefinClient} fetched
     * {@code {accessUrl}/accounts} with no date parameter at all and filtered the institution's
     * whole history in memory. On an account with years of history that is the bridge's entire
     * ledger, decoded into objects, to keep three days of it.
     *
     * @param accessUrl the stored access URL, decrypted by the caller and re-checked here
     * @param from      inclusive start of the window
     */
    public SimplefinAccountSet fetchAccounts(String accessUrl, LocalDate from) {
        Credentialed split = split(accessUrl);
        Endpoint endpoint = check(accessUrl, split);
        URI accounts = URI.create(stripTrailingSlash(endpoint.target().uri().toString())
            + "/accounts?start-date=" + from.atStartOfDay(ZoneOffset.UTC).toEpochSecond());
        try {
            byte[] body = pinned(endpoint).get()
                .uri(accounts)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 401 || status.value() == 403) {
                        throw new BankSourceConnectionException("The SimpleFIN bridge refused "
                            + "these credentials. Reconnect the account.");
                    }
                    if (!status.is2xxSuccessful()) {
                        throw new BankSourceConnectionException(
                            "The SimpleFIN bridge answered HTTP " + status.value() + ".");
                    }
                    return readCapped(response.getBody(), properties.maxResponseBytes());
                });
            SimplefinAccountSet set =
                objectMapper.readValue(body, SimplefinAccountSet.class);
            return set == null ? new SimplefinAccountSet(java.util.List.of(), null) : set;
        } catch (BankSourceConnectionException e) {
            throw e;
        } catch (JacksonException e) {
            throw new BankSourceConnectionException(
                "The SimpleFIN bridge's answer was not the account list this server understands.");
        } catch (RestClientException e) {
            throw unreachable(e, accessUrl, split);
        }
    }

    /**
     * Validates a URL, and rewrites the refusal so it cannot quote the URL back.
     *
     * <p>{@code OutboundUrlPolicy}'s messages name the RULE and the host, which is what a member
     * needs. One of them — "The URL must be absolute: …" — echoes the whole string it was given,
     * and for a setup token that string is the credential. Rather than editing a validator three
     * other features share, the one caller whose input is a secret sanitises what comes back.
     */
    private Endpoint check(String url, Credentialed split) {
        try {
            return new Endpoint(egress.check(split.url()), split.userInfo());
        } catch (OutboundUrlPolicyException e) {
            throw new OutboundUrlPolicyException(redactAll(e.getMessage(), url, split),
                e.getCause());
        }
    }

    /**
     * A client that can reach exactly {@code endpoint}, carrying its credential if it has one.
     *
     * <p>The header is a default on the builder rather than a per-request one so that it cannot be
     * forgotten on a call added later. Redirects are off in {@code HttpClientConfig}'s shared
     * settings, which is what keeps a bridge from bouncing the credential to a third party.
     */
    private RestClient pinned(Endpoint endpoint) {
        RestClient.Builder builder = clients.builderFor(endpoint.target(),
            properties.connectTimeout(), properties.readTimeout());
        if (endpoint.userInfo() != null) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, basic(endpoint.userInfo()));
        }
        return builder.build();
    }

    /** {@code Basic base64(user:pass)} — built here and never logged, returned or asserted on. */
    private static String basic(String userInfo) {
        return "Basic " + Base64.getEncoder().encodeToString(
            userInfo.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Splits a URL into the destination the egress policy judges and the credential it carried.
     *
     * <p><strong>A SimpleFIN access URL is {@code https://user:pass@host/simplefin}.</strong>
     * {@link OutboundUrlPolicy} refuses userinfo unconditionally, so leaving it on would refuse
     * every real bridge on the first fetch after the one-time token had already been spent. The
     * removal is done on the RAW string — {@code trimmed.substring} either side of the raw
     * userinfo — rather than by rebuilding the URI from its decoded parts, because the multi-part
     * {@link URI} constructor re-encodes and would turn a {@code %2F} in the path into a separator.
     *
     * <p>Anything this method cannot confidently take apart is handed back UNCHANGED, so the
     * policy sees the original string and produces its own refusal. It never widens the policy in
     * any other respect either: a URL with no userinfo is returned as it arrived, and one with
     * surrounding whitespace — which the policy refuses outright — is not tidied up on the way.
     */
    static Credentialed split(String url) {
        if (url == null) {
            return new Credentialed(null, null);
        }
        String trimmed = url.trim();
        if (trimmed.length() != url.length()) {
            // OutboundUrlPolicy refuses a URL with leading or trailing whitespace. Handing it the
            // trimmed form would quietly accept one it would otherwise have refused, so anything
            // that is not already trimmed goes to the policy exactly as it arrived.
            return new Credentialed(url, null);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            // swallow-ok: a URL that does not parse is the policy's refusal to give, not ours, and
            // the exception's message quotes the credential.
            return new Credentialed(url, null);
        }
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo == null || uri.getScheme() == null || uri.getHost() == null) {
            return new Credentialed(url, null);
        }
        String prefix = uri.getScheme() + "://";
        String credentials = rawUserInfo + "@";
        if (!trimmed.startsWith(prefix + credentials)) {
            return new Credentialed(url, null);
        }
        return new Credentialed(prefix + trimmed.substring(prefix.length() + credentials.length()),
            uri.getUserInfo());
    }

    /**
     * A URL taken apart: where to connect, and what to authenticate with.
     *
     * @param url      the URL with any credentials removed — the only form the policy, the pinned
     *                 client's base URL and every {@code /accounts} URI derived from it ever see
     * @param userInfo the decoded {@code user:pass}, or {@code null} when the URL carried none
     */
    record Credentialed(String url, String userInfo) {
    }

    /** An approved destination and the credential the URL it came from carried. */
    private record Endpoint(ResolvedTarget target, String userInfo) {
    }

    /** Reads at most {@code max} bytes, and fails rather than truncating silently. */
    static byte[] readCapped(InputStream body, long max) throws IOException {
        byte[] bytes = body.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
        if (bytes.length > max) {
            throw new BankSourceConnectionException("The SimpleFIN bridge's answer is larger than "
                + "the " + max + " bytes this server will read.");
        }
        return bytes;
    }

    /**
     * Reports a transport failure without letting the URL out.
     *
     * <p><strong>The cause is deliberately dropped.</strong> An HTTP client's exception message —
     * and every frame of its stack that mentions a request — quotes the request URI, and for these
     * two calls that URI <em>is</em> the credential. Keeping the cause would put it into any log
     * line that prints the throwable, which is exactly what the global handler does for an
     * unexpected 5xx. What an operator needs instead is the sanitised reason, which is logged here,
     * once.
     */
    private static BankSourceConnectionException unreachable(RestClientException e, String secret,
                                                            Credentialed split) {
        LOG.warn("A SimpleFIN bridge call failed: {}", redactAll(e.getMessage(), secret, split));
        return new BankSourceConnectionException("The SimpleFIN bridge could not be reached.");
    }

    /**
     * Removes all three spellings of the secret: the URL as it arrived, the credential-free URL
     * the policy and the HTTP client were actually given, and the {@code user:pass} itself.
     */
    private static String redactAll(String message, String original, Credentialed split) {
        String cleaned = redact(message, original);
        if (split != null) {
            cleaned = redact(cleaned, split.url());
            cleaned = redact(cleaned, split.userInfo());
        }
        return cleaned;
    }

    static String redact(String message, String secret) {
        if (message == null) {
            return "";
        }
        String cleaned = message;
        if (secret != null && !secret.isBlank()) {
            cleaned = cleaned.replace(secret, REDACTED).replace(secret.trim(), REDACTED);
        }
        return cleaned;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
