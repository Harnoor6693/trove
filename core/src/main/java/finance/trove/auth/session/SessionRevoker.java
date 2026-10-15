package finance.trove.auth.session;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a refresh-token family in a transaction of its own.
 *
 * <p>This class exists because of a bug that only an integration test can see. Reuse detection in
 * {@link RefreshTokenService#rotate} does two things: it revokes the family, and it refuses the
 * request by throwing. Both happened inside {@code rotate}'s own transaction — so the throw rolled
 * the revocation back. The security event survived (it is written {@code REQUIRES_NEW}), the log
 * line was printed, the caller got its 401, and the family stayed <strong>live</strong>. A stolen
 * token's successor kept working, which is precisely the outcome family revocation exists to
 * prevent, while every signal said it had been handled.
 *
 * <p>A unit test with an in-memory repository cannot catch this: there is no transaction to roll
 * back. {@code RefreshTokenIT#replayOutsideTheGraceWindowBurnsTheFamily} is what found it, by
 * presenting the successor afterwards and getting a 200.
 *
 * <p>Separate bean rather than a {@code REQUIRES_NEW} annotation on a private method, because
 * {@code @Transactional} is proxy-based: a self-invocation would not start a new transaction and
 * the bug would be back, silently, in the same shape.
 */
@Service
public class SessionRevoker {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRevoker.class);

    private final RefreshTokenRepository tokens;

    public SessionRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Ends every unrevoked token in {@code familyId}, and commits whatever the caller does next.
     *
     * @return how many rows were revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamilyNow(UUID familyId, Instant now) {
        int revoked = tokens.revokeFamily(familyId, now);
        LOG.debug("Revoked {} token(s) in family {}", revoked, familyId);
        return revoked;
    }
}
