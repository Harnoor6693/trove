package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Readiness gap P2-5, the half that decides what every other per-address control is measuring.
 *
 * <p>The pre-collapse limiter and audit filter both did
 * {@code request.getHeader("X-Forwarded-For").split(",")[0]}. That takes the <strong>leftmost</strong>
 * value of a header any client can send, so a caller that prefixed its own hop chose which
 * rate-limit bucket it landed in, which login-lockout bucket counted its failures, and which
 * address was written into the audit trail against it. On the owner's public-internet decision
 * that made every per-IP control in the application a suggestion.
 *
 * <p>The fix is Tomcat's {@code RemoteIpValve}, configured from {@code TROVE_TRUSTED_PROXIES} with
 * the compose network and nothing else. It walks the header from the <strong>right</strong> and
 * stops at the first hop that is not a trusted proxy, so the address it settles on is the one the
 * outermost trusted proxy actually saw.
 *
 * <p><strong>How the untrusted case is reachable from a test.</strong> Every request in this suite
 * arrives on loopback, and loopback is a trusted proxy — as it must be, or nothing in a compose
 * network could forward anything. So "an untrusted peer's claim is ignored" is asserted in the
 * form it actually takes in production: a chain whose leftmost entry is the attacker's invention
 * and whose rightmost is what the real proxy appended. The valve must take the appended one. That
 * is precisely the request Traefik produces when a client sends its own {@code X-Forwarded-For}.
 */
class ClientIpIT extends AuthFlowSupport {

    /**
     * The address rows are read back from. Reading {@code security_event} rather than adding a
     * debug endpoint: it is one of the three consumers the resolver exists for, so the assertion
     * is about the real write path.
     */
    private String recordedAddressForFailedLoginFrom(String forwardedFor) {
        String email = uniqueEmail("client-ip");
        postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", "a-wrong-passphrase"),
            FORWARDED_FOR, forwardedFor);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT host(ip) AS ip FROM login_attempt WHERE email_hash = ? ORDER BY at DESC",
            finance.trove.auth.local.LoginAttemptRepository.hashEmail(email));
        assertThat(rows).as("the failed attempt must have been recorded at all").isNotEmpty();
        return (String) rows.get(0).get("ip");
    }

    @Test
    @DisplayName("A forwarded address from a trusted proxy is honoured")
    void aTrustedProxysClaimIsHonoured() {
        assertThat(recordedAddressForFailedLoginFrom("203.0.113.42"))
            .as("behind Traefik the socket peer is Traefik; without the valve every row in "
                + "login_attempt, security_event and audit_log would say so, and the per-address "
                + "gate would count the whole internet as one client")
            .isEqualTo("203.0.113.42");
    }

    @Test
    @DisplayName("A client's own X-Forwarded-For is overridden by what the proxy appended")
    void aClientsOwnClaimIsIgnored() {
        // What a spoofing client produces once Traefik has handled it: the client's invented
        // leftmost value, then the address Traefik really saw.
        assertThat(recordedAddressForFailedLoginFrom("10.0.0.1, 203.0.113.99"))
            .as("the pre-collapse code took the FIRST value, so this request would have been "
                + "recorded as 10.0.0.1 and bucketed with the compose network")
            .isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("A chain of untrusted hops resolves to the one nearest this server")
    void theNearestUntrustedHopWins() {
        assertThat(recordedAddressForFailedLoginFrom("198.51.100.1, 198.51.100.2, 203.0.113.7"))
            .as("every hop left of the nearest one is hearsay: only the last was observed by "
                + "something this server believes")
            .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("A trailing trusted hop is skipped over, not taken as the client")
    void trustedHopsAreSkipped() {
        assertThat(recordedAddressForFailedLoginFrom("203.0.113.7, 172.18.0.4"))
            .as("172.18.0.4 is a docker bridge address and therefore a proxy, not a client; "
                + "taking it would put every request behind a second internal hop into one bucket")
            .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("With no forwarded header at all the socket peer is used")
    void withNoHeaderTheSocketPeerIsUsed() {
        String email = uniqueEmail("client-ip-direct");
        // outsideTheContract is not needed: this is an ordinary login. What is unusual is only
        // that it deliberately carries no X-Forwarded-For, which BaseIT would otherwise add.
        postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", "a-wrong-passphrase"),
            FORWARDED_FOR, "");

        assertThat(jdbcTemplate.queryForList(
            "SELECT host(ip) AS ip FROM login_attempt WHERE email_hash = ?",
            finance.trove.auth.local.LoginAttemptRepository.hashEmail(email)))
            .as("an empty header must not produce a null address or an exception; the valve "
                + "falls back to the socket, which here is loopback")
            .isNotEmpty()
            .allSatisfy(row -> assertThat((String) row.get("ip")).isNotBlank());
    }

    @Test
    @DisplayName("The address the security-event recorder writes is the same resolved one")
    void securityEventsCarryTheResolvedAddress() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "client-ip-event");

        loginBearer(member.email(), MEMBER_PASSWORD);

        assertThat(jdbcTemplate.queryForList(
            "SELECT host(ip) AS ip FROM security_event WHERE user_id = ? AND type = 'LOGIN_OK'",
            member.userId()))
            .as("one resolver, three consumers: a security_event row that disagreed with a "
                + "login_attempt row about the same request would make an incident review guess")
            .isNotEmpty()
            .allSatisfy(row -> assertThat((String) row.get("ip")).isEqualTo(clientAddress()));
    }
}
