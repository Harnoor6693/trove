package finance.trove.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.observability.RequestIdMdcFilter;
import finance.trove.users.UserRole;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

/**
 * What ends up in {@code audit_log}, and — more to the point — what does not.
 *
 * <p>{@link #theRouteTemplateIsRecordedAndNeverTheRawUri()} is the assertion this class exists for.
 * The gateway filter it replaces stored {@code request.getRequestURI()}, which on
 * {@code GET /v1/auth/invites/{token}} is a still-redeemable invitation token, in plaintext, in the
 * same database whose {@code household_invite.token_hash} column V9_2 encrypted precisely so that a
 * dump would not yield one.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogFilterTest {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID HOUSEHOLD = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String REQUEST_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    @Mock
    private AuditLogWriter writer;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String method, String uri, String template) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/api");
        request.setRemoteAddr("198.18.0.7");
        request.setAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
        if (template != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
        }
        return request;
    }

    private void authenticate() {
        TroveUserDetails principal = new TroveUserDetails(USER, HOUSEHOLD, UUID.randomUUID(),
            "member@trove.test", UserRole.MEMBER, Instant.now());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private AuditRecord captureFor(MockHttpServletRequest request) throws Exception {
        new AuditLogFilter(writer).doFilter(request, new MockHttpServletResponse(),
            mock(FilterChain.class));
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("The route template is recorded and never the raw URI")
    void theRouteTemplateIsRecordedAndNeverTheRawUri() throws Exception {
        String token = "a-still-redeemable-invitation-token";
        AuditRecord captured = captureFor(request("DELETE",
            "/api/v1/admin/invites/" + token, "/v1/admin/invites/{id}"));

        assertThat(captured.path())
            .isEqualTo("/api/v1/admin/invites/{id}")
            .doesNotContain(token);
    }

    @Test
    @DisplayName("With no matched handler the last path segment is redacted rather than stored")
    void anUnmatchedPathIsRedacted() throws Exception {
        AuditRecord captured = captureFor(
            request("POST", "/api/v1/auth/invites/secret-token", null));

        assertThat(captured.path())
            .as("a request the chain refused before dispatch has no template, and a heuristic "
                + "that tried to recognise a secret would eventually meet one it did not")
            .isEqualTo("/api/v1/auth/invites/*");
    }

    @Test
    @DisplayName("Reads are not recorded — the question is what changed")
    void readsAreNotRecorded() throws Exception {
        new AuditLogFilter(writer).doFilter(request("GET", "/api/v1/admin/users", "/v1/admin/users"),
            new MockHttpServletResponse(), mock(FilterChain.class));

        verify(writer, never()).write(any());
    }

    @Test
    @DisplayName("Every mutating method is recorded")
    void everyMutatingMethodIsRecorded() throws Exception {
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            AuditLogWriter perMethod = mock(AuditLogWriter.class);
            new AuditLogFilter(perMethod).doFilter(request(method, "/api/v1/me", "/v1/me"),
                new MockHttpServletResponse(), mock(FilterChain.class));
            verify(perMethod).write(any());
        }
    }

    @Test
    @DisplayName("The principal's user and household are recorded when there is one")
    void theSignedInAccountIsRecorded() throws Exception {
        authenticate();
        AuditRecord captured = captureFor(request("PUT", "/api/v1/me", "/v1/me"));

        assertThat(captured.userId()).isEqualTo(USER);
        assertThat(captured.householdId())
            .as("this is the column GET /v1/audit scopes on; without it an admin reads every "
                + "household's log")
            .isEqualTo(HOUSEHOLD);
    }

    @Test
    @DisplayName("An anonymous request is recorded with a null user, not skipped")
    void anonymousRequestsAreStillRecorded() throws Exception {
        AuditRecord captured = captureFor(request("POST", "/api/v1/auth/login", "/v1/auth/login"));

        assertThat(captured.userId()).isNull();
        assertThat(captured.householdId()).isNull();
        assertThat(captured.method()).isEqualTo("POST");
    }

    @Test
    @DisplayName("The address and the request id come from the resolver and the id filter")
    void contextIsCarried() throws Exception {
        AuditRecord captured = captureFor(request("POST", "/api/v1/me", "/v1/me"));

        assertThat(captured.clientIp()).isEqualTo("198.18.0.7");
        assertThat(captured.requestId()).isEqualTo(UUID.fromString(REQUEST_ID));
        assertThat(captured.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("A writer that throws never becomes the answer the caller receives")
    void aFailingWriterIsSwallowed() throws Exception {
        doThrow(new IllegalStateException("executor rejected")).when(writer).write(any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuditLogFilter(writer).doFilter(request("POST", "/api/v1/me", "/v1/me"), response,
            mock(FilterChain.class));

        assertThat(response.getStatus())
            .as("the response is already written and on its way; an audit failure must not "
                + "replace it")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("The status recorded is the one the response carries, with no body buffering")
    void theStatusIsReadFromTheResponseItself() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/me", "/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            ((MockHttpServletResponse) res).setStatus(409);
            res.getWriter().write("{\"success\":false}");
        };

        new AuditLogFilter(writer).doFilter(request, response, chain);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(409);
        assertThat(response.getContentAsString())
            .as("nothing wraps the response, so the body reaches the client untouched — a "
                + "ContentCachingResponseWrapper would buffer every byte of every response in "
                + "heap to learn a status the response already reports")
            .isEqualTo("{\"success\":false}");
    }
}
