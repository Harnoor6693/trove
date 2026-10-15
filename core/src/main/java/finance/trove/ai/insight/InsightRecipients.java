package finance.trove.ai.insight;

import finance.trove.ai.chat.CoachAudience;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import jakarta.persistence.EntityManager;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who gets a weekly insight ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6; inventory
 * model-manager D6).
 *
 * <h2>The bug this class exists to not repeat</h2>
 * The source enumerated recipients with
 * {@code CoachConversationRepository.findDistinctUserIds()} — every user with at least one chat
 * message. A member who never opened the coach therefore never received an insight, silently and
 * for ever, no matter how much ledger data they had; and the feature's whole purpose is to reach
 * people who are not already in the chat window. The honest set is the one §6 names and the one the
 * per-user endpoint makes possible: members whose <strong>own</strong> {@code ai_settings.enabled}
 * is true. A member with AI off has no endpoint to generate a note with and has said they do not
 * want one.
 *
 * <h2>Everything the note needs comes back with the id</h2>
 * Currency, jurisdiction and timezone are selected here rather than looked up per recipient inside
 * the loop, because they are what makes the note that member's own — findings F1 (the source's
 * hard-coded Canadian-dollar literal) and inventory D5 (its hard-coded Canadian jurisdiction) are
 * both "the job knew the user id and nothing else about the user". One query, one row per
 * recipient, no second lookup to forget.
 *
 * <h2>Paged</h2>
 * Keyset-free ordering by {@code user_id} with LIMIT/OFFSET is enough: the set changes only when
 * somebody turns AI on or off, and a member missed on one week's run gets the next one. Loading
 * every recipient of a large install into one list to iterate is what a job on a 2 GB container
 * must not do.
 *
 * <p>The query is native and lives here rather than on {@code AiSettingsRepository}, which belongs
 * to collapse step 12 and is merged, reviewed and not this step's to change — the same reason
 * {@code SubscriptionScanTargets} holds its own enumeration.
 */
@Component
public class InsightRecipients {

    private static final String ENABLED_MEMBERS = """
        SELECT s.user_id, u.household_id, u.display_name, u.currency, u.country, u.timezone
        FROM ai_settings s
        JOIN users u ON u.id = s.user_id
        WHERE s.enabled = true AND u.is_active = true
        ORDER BY s.user_id
        LIMIT :limit OFFSET :offset
        """;

    private static final Logger LOG = LoggerFactory.getLogger(InsightRecipients.class);

    private final EntityManager entityManager;

    public InsightRecipients(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * One page of recipients, ordered by user id.
     *
     * @return an empty list when the page is past the end, which is how the job's loop terminates
     */
    @Transactional(readOnly = true)
    public List<CoachAudience> page(int offset, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(ENABLED_MEMBERS)
            .setParameter("limit", limit)
            .setParameter("offset", offset)
            .getResultList();
        List<CoachAudience> recipients = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            recipients.add(new CoachAudience(
                (UUID) row[0],
                (UUID) row[1],
                (String) row[2],
                CurrencyCode.valueOf((String) row[3]),
                Jurisdiction.valueOf((String) row[4]),
                zoneOf((String) row[5])));
        }
        return recipients;
    }

    /**
     * The member's own zone, falling back to UTC exactly as {@code UserClock} does for a stored
     * value the JVM's tzdata no longer knows. The fallback is logged rather than silent: a member
     * whose week boundary quietly moved to UTC would get a note about the wrong seven days.
     */
    private static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            // swallow-ok: an unknown zone must not stop the whole run for every other recipient.
            LOG.warn("Unknown timezone '{}' on a weekly-insight recipient; using UTC for their "
                + "week boundary", timezone, e);
            return ZoneOffset.UTC;
        }
    }
}
