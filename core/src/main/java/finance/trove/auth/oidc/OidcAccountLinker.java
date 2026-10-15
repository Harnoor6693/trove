package finance.trove.auth.oidc;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.TimeProperties;
import finance.trove.households.Household;
import finance.trove.households.InstallHousehold;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides which Trove account an OpenID identity belongs to (§1.5; §9 risk 27).
 *
 * <p>Separated from the servlet success handler on purpose: the rule this class implements is the
 * one an adversarial reviewer flagged as a takeover in the pre-collapse code, and it needs to be
 * testable without an identity provider, a redirect or a browser. {@code OidcAccountLinkerTest}
 * exercises every branch directly; an end-to-end {@code OidcLinkIT} would need a live IdP to
 * issue the tokens, which no test environment here has.
 *
 * <p>The order of the checks is the design:
 * <ol>
 *   <li><strong>An existing link wins.</strong> {@code (provider, sub)} is the only stable,
 *       provider-guaranteed identity; once linked, the email claim is irrelevant and may change
 *       freely at the provider without affecting anything.</li>
 *   <li><strong>Auto-link requires {@code email_verified: true}.</strong> The pre-collapse handler
 *       read {@code getEmail()} and nothing else, which meant an attacker who could get any IdP
 *       account issued with a household member's address logged in as that member. Absent is
 *       treated as false: a provider that does not say the address is verified has not said it.
 *       </li>
 *   <li><strong>Otherwise a password proves the match.</strong> Not a refusal — a legitimate first
 *       link at a provider with no verification claim looks exactly like this.</li>
 *   <li><strong>No local account falls to registration policy.</strong> Closed by default, so an
 *       identity provider with open enrolment cannot become an open door into somebody's finance
 *       server (gap P2-7).</li>
 * </ol>
 */
@Service
public class OidcAccountLinker {

    private static final Logger LOG = LoggerFactory.getLogger(OidcAccountLinker.class);

    private final OidcLinkRepository links;
    private final UserRepository users;
    private final InstallHousehold installHousehold;
    private final TimeProperties time;
    private final SecurityEventRecorder securityEvents;

    public OidcAccountLinker(OidcLinkRepository links, UserRepository users,
                             InstallHousehold installHousehold, TimeProperties time,
                             SecurityEventRecorder securityEvents) {
        this.links = links;
        this.users = users;
        this.installHousehold = installHousehold;
        this.time = time;
        this.securityEvents = securityEvents;
    }

    /**
     * @param provider      the registration id, {@code oidc}
     * @param subject       the ID token's {@code sub}; the only key ever looked up
     * @param rawEmail      the {@code email} claim, or null
     * @param emailVerified the {@code email_verified} claim; null is treated as false
     */
    @Transactional
    public OidcLinkOutcome resolve(String provider, String subject, String rawEmail,
                                   Boolean emailVerified) {
        Optional<OidcLink> existing = links.findByProviderAndProviderSub(provider, subject);
        if (existing.isPresent()) {
            Optional<User> linked = users.findAccount(existing.get().getUserId())
                .filter(User::isActive);
            if (linked.isPresent()) {
                return new OidcLinkOutcome.Linked(linked.get(), false);
            }
            // The link points at an account that has been deleted or deactivated. Falling through
            // to the email branch would silently re-link the identity to whoever now holds that
            // address, so this is a refusal.
            LOG.warn("An OIDC link points at an account that is gone; refusing");
            return new OidcLinkOutcome.Denied();
        }

        String email = rawEmail == null || rawEmail.isBlank()
            ? null
            : rawEmail.strip().toLowerCase(Locale.ROOT);
        if (email == null) {
            LOG.warn("An OIDC identity carried no email claim; nothing to match on");
            return new OidcLinkOutcome.Denied();
        }

        Optional<User> local = users.findByEmailIgnoreCase(email).filter(User::isActive);
        if (local.isPresent()) {
            if (!Boolean.TRUE.equals(emailVerified)) {
                LOG.warn("Refusing to auto-link an OIDC identity whose email is not verified");
                securityEvents.record(SecurityEventType.LOGIN_FAIL, local.get().getId(),
                    Map.of("stage", "oidc", "reason", "email-not-verified"));
                return new OidcLinkOutcome.LinkRequired(email);
            }
            links.save(new OidcLink(local.get().getId(), provider, subject, email));
            LOG.info("Auto-linked a verified OIDC identity to an existing account");
            return new OidcLinkOutcome.Linked(local.get(), true);
        }

        return registerNew(provider, subject, email, emailVerified);
    }

    /** Links a provider identity to an account whose password has just been re-proved. */
    @Transactional
    public void linkAfterPasswordProof(User user, String provider, String subject, String email) {
        if (links.findByProviderAndProviderSub(provider, subject).isEmpty()) {
            links.save(new OidcLink(user.getId(), provider, subject, email));
        }
    }

    private OidcLinkOutcome registerNew(String provider, String subject, String email,
                                        Boolean emailVerified) {
        if (!Boolean.TRUE.equals(emailVerified)) {
            // Creating an account from an unverified address would let anyone with control of an
            // IdP squat an address a household member might later want to use.
            LOG.warn("Refusing to create an account from an unverified OIDC address");
            return new OidcLinkOutcome.Denied();
        }
        Optional<Household> open = installHousehold.findOpenForRegistration();
        if (open.isEmpty()) {
            return new OidcLinkOutcome.Denied();
        }
        Household household = open.get();
        User created = new User(household.getId(), UserRole.MEMBER, email, displayNameFor(email),
            Jurisdiction.CA, CurrencyCode.CAD, defaultZone());
        users.save(created);
        links.save(new OidcLink(created.getId(), provider, subject, email));
        LOG.info("Created an account from a verified OIDC identity under open registration");
        return new OidcLinkOutcome.Registered(created);
    }

    private static String displayNameFor(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String cleaned = local.replace('.', ' ').replace('_', ' ').strip();
        if (cleaned.isEmpty()) {
            return "Member";
        }
        String truncated = cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
        return Character.toUpperCase(truncated.charAt(0)) + truncated.substring(1);
    }

    private ZoneId defaultZone() {
        try {
            return ZoneId.of(time.defaultTimezone());
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }
}
