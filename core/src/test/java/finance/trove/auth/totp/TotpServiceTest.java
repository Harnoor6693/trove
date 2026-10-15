package finance.trove.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.users.User;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Readiness gap P2-6's lifecycle: enrol, verify before enabling, recover, remove.
 *
 * <p>The ordering is the point. Enabling a second factor before one live code has proved the
 * authenticator works is how a user who scans a QR badly locks themselves out of their own finance
 * server permanently — there is no support desk and no email reset. Every assertion below is about
 * a step that must not be skippable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TotpServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String KEY =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Mock
    private TotpCredentialRepository credentials;

    @Mock
    private SecurityEventRecorder securityEvents;

    private final TotpGenerator generator = new TotpGenerator();
    private final FieldEncryptionService encryption = new FieldEncryptionService(KEY);
    private final TotpProperties properties = new TotpProperties("Trove", 6, 30, 1, 10);

    private TotpService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new TotpService(credentials, generator, encryption, properties, securityEvents);
        user = new User(UUID.randomUUID(), UserRole.MEMBER, "member@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
    }

    private TotpCredential enrolled() {
        return new TotpCredential(UUID.randomUUID(), encryption.encrypt("GEZDGNBVGY3TQOJQ"));
    }

    @Test
    @DisplayName("Enrolment stores an encrypted secret and leaves the factor DISABLED")
    void enrolmentDoesNotEnable() {
        when(credentials.findByUserId(any())).thenReturn(Optional.empty());

        TotpEnrolment enrolment = service.beginEnrolment(user);

        ArgumentCaptor<TotpCredential> saved = ArgumentCaptor.forClass(TotpCredential.class);
        verify(credentials).save(saved.capture());
        assertThat(saved.getValue().isEnabled())
            .as("verify-before-enable is the whole of gap P2-6")
            .isFalse();
        assertThat(saved.getValue().getSecretEncrypted())
            .as("a database dump must not hand over every second factor on the install")
            .isNotEqualTo(enrolment.secret())
            .startsWith("v1:");
        assertThat(encryption.decrypt(saved.getValue().getSecretEncrypted()))
            .isEqualTo(enrolment.secret());
        assertThat(enrolment.otpauthUri()).contains(enrolment.secret());
        assertThat(enrolment.digits()).isEqualTo(6);
        assertThat(enrolment.periodSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("Re-enrolling over a working factor is refused, not silently accepted")
    void refusesReEnrolmentWhileEnabled() {
        TotpCredential credential = enrolled();
        credential.enable(NOW, List.of("hash"));
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.beginEnrolment(user))
            .as("replacing a working factor with an unproven one is how a phone change becomes "
                + "a lockout")
            .isInstanceOf(TotpException.class)
            .hasMessageContaining("already enabled");
    }

    @Test
    @DisplayName("An abandoned enrolment can be restarted with a fresh secret")
    void restartingEnrolmentReplacesTheSecret() {
        TotpCredential credential = enrolled();
        String original = credential.getSecretEncrypted();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        service.beginEnrolment(user);

        assertThat(credential.getSecretEncrypted()).isNotEqualTo(original);
        assertThat(credential.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Confirmation needs a live code and only then issues the ten recovery codes")
    void confirmationEnablesAndMintsRecoveryCodes() {
        TotpCredential credential = enrolled();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));
        String code = generator.codeAt("GEZDGNBVGY3TQOJQ", generator.timeStep(NOW, 30), 6);

        List<String> recovery = service.confirmEnrolment(credential.getUserId(), code, NOW);

        assertThat(credential.isEnabled()).isTrue();
        assertThat(credential.getConfirmedAt()).isEqualTo(NOW);
        assertThat(recovery).hasSize(10).doesNotHaveDuplicates();
        assertThat(recovery).allSatisfy(value ->
            assertThat(value).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}"));
        assertThat(credential.getRecoveryCodesHashed())
            .as("codes are stored as hashes; there is no 'show my codes again'")
            .hasSize(10)
            .doesNotContainAnyElementsOf(recovery);
        verify(securityEvents).record(org.mockito.ArgumentMatchers.eq(SecurityEventType.TOTP_ENROL),
            any(), any());
    }

    @Test
    @DisplayName("A wrong code at confirmation leaves the factor disabled")
    void wrongConfirmationCodeDoesNotEnable() {
        TotpCredential credential = enrolled();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.confirmEnrolment(credential.getUserId(), "000000", NOW))
            .isInstanceOf(TotpException.class);
        assertThat(credential.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Confirming without having started enrolment is a conflict, not a 500")
    void confirmationRequiresEnrolment() {
        when(credentials.findByUserId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmEnrolment(UUID.randomUUID(), "123456", NOW))
            .isInstanceOf(TotpException.class)
            .hasMessageContaining("not set up");
    }

    /**
     * Stands in for the two conditional updates the way Postgres answers them: a hash is spent
     * once, a step only if it is later than the last one spent.
     */
    private Set<String> backSpendsWithTheStoredRow(TotpCredential credential) {
        Set<String> unspent = new HashSet<>(credential.getRecoveryCodesHashed());
        long[] lastStep = {credential.getLastUsedStep()};
        when(credentials.consumeRecoveryCode(eq(credential.getUserId()), anyString()))
            .thenAnswer(invocation -> unspent.remove(invocation.<String>getArgument(1)) ? 1 : 0);
        when(credentials.claimStep(eq(credential.getUserId()), anyLong()))
            .thenAnswer(invocation -> {
                long step = invocation.getArgument(1);
                if (step <= lastStep[0]) {
                    return 0;
                }
                lastStep[0] = step;
                return 1;
            });
        return unspent;
    }

    @Test
    @DisplayName("A recovery code works once and is then gone")
    void recoveryCodesAreSingleUse() {
        TotpCredential credential = enrolled();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));
        String code = generator.codeAt("GEZDGNBVGY3TQOJQ", generator.timeStep(NOW, 30), 6);
        List<String> recovery = service.confirmEnrolment(credential.getUserId(), code, NOW);
        String first = recovery.get(0);
        Set<String> unspent = backSpendsWithTheStoredRow(credential);

        assertThat(service.verify(credential.getUserId(), first, NOW)).isTrue();
        assertThat(unspent).hasSize(9).doesNotContain(TotpService.hash(first));
        assertThat(service.verify(credential.getUserId(), first, NOW))
            .as("a reusable recovery code is a permanent password that bypasses the factor")
            .isFalse();
        assertThat(unspent).hasSize(9);
        verify(securityEvents).record(eq(SecurityEventType.LOGIN_OK), eq(credential.getUserId()),
            eq(java.util.Map.of("factor", "recovery-code", "remainingRecoveryCodes", 9)));
    }

    @Test
    @DisplayName("A TOTP code is spent once: the second use of its step is refused")
    void aTotpCodeIsSpentOnce() {
        TotpCredential credential = enrolled();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));
        long step = generator.timeStep(NOW, 30);
        service.confirmEnrolment(credential.getUserId(),
            generator.codeAt("GEZDGNBVGY3TQOJQ", step, 6), NOW);
        backSpendsWithTheStoredRow(credential);
        String next = generator.codeAt("GEZDGNBVGY3TQOJQ", step + 1, 6);

        assertThat(service.verify(credential.getUserId(), next, NOW)).isTrue();
        assertThat(service.verify(credential.getUserId(), next, NOW))
            .as("the entity read here is not locked, so a code another request already spent "
                + "still matches it; the database's answer is the one that counts")
            .isFalse();
        verify(credentials, times(2)).claimStep(credential.getUserId(), step + 1);
    }

    @Test
    @DisplayName("Recovery codes are matched case- and separator-insensitively")
    void recoveryCodesAreForgivingToRetype() {
        TotpCredential credential = enrolled();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));
        String code = generator.codeAt("GEZDGNBVGY3TQOJQ", generator.timeStep(NOW, 30), 6);
        String recovery = service.confirmEnrolment(credential.getUserId(), code, NOW).get(0);
        backSpendsWithTheStoredRow(credential);

        assertThat(service.verify(credential.getUserId(),
            "  " + recovery.toLowerCase(java.util.Locale.ROOT) + "  ", NOW))
            .as("these are read off a screen and typed months later")
            .isTrue();
    }

    @Test
    @DisplayName("Verification against a disabled or absent factor is false, never an exception")
    void verifyingWithoutAnEnabledFactorIsFalse() {
        when(credentials.findByUserId(any())).thenReturn(Optional.empty());
        assertThat(service.verify(UUID.randomUUID(), "123456", NOW)).isFalse();

        when(credentials.findByUserId(any())).thenReturn(Optional.of(enrolled()));
        assertThat(service.verify(UUID.randomUUID(), "123456", NOW)).isFalse();
        assertThat(service.isEnabled(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Disabling records who did it, so an admin action is distinguishable")
    void disableRecordsTheActor() {
        UUID subject = UUID.randomUUID();
        UUID admin = UUID.randomUUID();

        service.disable(subject, admin);

        verify(credentials).deleteByUserId(subject);
        verify(securityEvents).record(
            org.mockito.ArgumentMatchers.eq(SecurityEventType.TOTP_DISABLE),
            org.mockito.ArgumentMatchers.eq(subject),
            org.mockito.ArgumentMatchers.eq(admin), any());
    }
}
