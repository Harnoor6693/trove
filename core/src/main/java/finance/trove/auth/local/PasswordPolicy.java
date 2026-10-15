package finance.trove.auth.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The password rules of readiness gap P2-4: length only, plus an offline deny-list.
 *
 * <p><strong>No composition rules.</strong> No "one uppercase, one digit, one symbol". Those rules
 * do not raise the entropy of the passwords people actually choose — they move everyone onto the
 * same handful of shapes ({@code Password1!}) while making a genuinely strong passphrase harder to
 * type. NIST SP 800-63B has recommended dropping them since 2017, and P2-4 adopts that: a 12
 * character floor, a 128 character ceiling, and a check against the strings that dominate every
 * credential-stuffing list.
 *
 * <p>The ceiling is not cosmetic either. argon2id is memory-hard on purpose; hashing an unbounded
 * input is a denial-of-service primitive that any anonymous caller can reach through
 * {@code /register}.
 *
 * <p>Two contextual checks beyond the list, because both produce passwords that survive every
 * length rule and none of the guessing: a password that contains the local part of its own email
 * address, and a password made of one short fragment repeated.
 */
@Component
public class PasswordPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordPolicy.class);
    private static final String DENY_LIST = "security/common-passwords.txt";

    /** Shortest fragment whose repetition counts as "one thing repeated". */
    private static final int MIN_REPEATED_UNIT = 1;

    /** Longest fragment worth testing for repetition; beyond this the repetition is not the weakness. */
    private static final int MAX_REPEATED_UNIT = 4;

    private final PasswordProperties properties;
    private final Set<String> denyList;

    public PasswordPolicy(PasswordProperties properties) {
        this.properties = properties;
        this.denyList = loadDenyList();
        LOG.info("Password policy: {}-{} characters, {} deny-list entries",
            properties.minLength(), properties.maxLength(), denyList.size());
    }

    /** Entries in the shipped deny-list. Test seam — a silently empty list would pass everything. */
    public int denyListSize() {
        return denyList.size();
    }

    /**
     * @param password the candidate, exactly as the user typed it
     * @param email    the account's address, used only for the contains-your-own-name check; may
     *                 be {@code null} where the address is not known yet
     * @throws PasswordPolicyException with a message meant to be shown to the account's owner
     */
    public void validate(String password, String email) {
        if (password == null || password.isEmpty()) {
            throw new PasswordPolicyException("A password is required.");
        }
        if (password.length() < properties.minLength()) {
            throw new PasswordPolicyException(
                "Password must be at least " + properties.minLength() + " characters. Length is "
                    + "the only requirement — a memorable phrase of four or five words is both "
                    + "stronger and easier to type than a short one with symbols in it.");
        }
        if (password.length() > properties.maxLength()) {
            throw new PasswordPolicyException(
                "Password must be at most " + properties.maxLength() + " characters.");
        }
        String normalised = password.trim().toLowerCase(Locale.ROOT);
        if (denyList.contains(normalised)) {
            throw new PasswordPolicyException(
                "This password appears on a list of commonly used passwords and would be among "
                    + "the first tried against this account. Choose another.");
        }
        if (isRepeatedFragment(normalised)) {
            throw new PasswordPolicyException(
                "This password is one short sequence repeated, which is no harder to guess than "
                    + "the sequence itself. Choose another.");
        }
        if (containsEmailLocalPart(normalised, email)) {
            throw new PasswordPolicyException(
                "Password must not contain your email address.");
        }
    }

    private static boolean containsEmailLocalPart(String normalisedPassword, String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        int at = email.indexOf('@');
        String local = (at > 0 ? email.substring(0, at) : email).toLowerCase(Locale.ROOT);
        return local.length() >= 4 && normalisedPassword.contains(local);
    }

    private static boolean isRepeatedFragment(String value) {
        for (int unit = MIN_REPEATED_UNIT; unit <= MAX_REPEATED_UNIT; unit++) {
            if (value.length() % unit != 0 || value.length() / unit < 3) {
                continue;
            }
            String head = value.substring(0, unit);
            boolean allSame = true;
            for (int i = unit; i < value.length(); i += unit) {
                if (!value.startsWith(head, i)) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> loadDenyList() {
        Set<String> entries = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(DENY_LIST);
        try (InputStream in = resource.getInputStream();
             BufferedReader reader =
                 new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    entries.add(trimmed);
                }
            }
        } catch (IOException e) {
            // Not recoverable: a policy that silently accepts every password because a resource
            // failed to load is worse than no policy, because the operator believes it is on.
            throw new UncheckedIOException(
                "Could not read the common-password deny-list at classpath:" + DENY_LIST, e);
        }
        return Set.copyOf(entries);
    }
}
