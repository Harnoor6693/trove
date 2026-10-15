package finance.trove.ai.chat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Who a coach answer is for, and everything about them that shapes it (collapse step 13).
 *
 * <p>Every field comes from the {@code users} row — never from a request body, a path or a header.
 * {@code ArchUnit} bans a request DTO field named {@code userId} and {@code SourceConventionTest}
 * bans reading {@code X-User-Id}, and this record is where that discipline becomes structural: the
 * context builder and the insight generator take one of these and have no other way to learn whose
 * ledger they are describing.
 *
 * <p>It exists because the two callers reach the same facts by different routes. The chat routes
 * build one from {@code @AuthenticationPrincipal TroveUserDetails} plus the caller's own
 * {@code users} row; the weekly job builds one per recipient from its own enumeration, on a
 * background thread with no principal at all. Making that a record rather than six parameters is
 * what stops the second caller from quietly dropping the currency, which is exactly finding F1 —
 * the source hard-coded a Canadian-dollar currency literal in the context fallback and a
 * Canadian jurisdiction literal in the weekly job.
 *
 * @param userId      the member, from a signed claim or from the job's own recipient query
 * @param householdId the ledger to read. Present because the FIGURES are the household's shared
 *                    ledger even though the conversation is one member's
 * @param displayName what to call them in the prompt
 * @param currency    their own {@code users.currency}. Never a literal: a household holding two
 *                    currencies is reported per currency and never blended, because no exchange
 *                    rate exists anywhere in this system (findings F1/F2)
 * @param country     their own {@code users.country}, CA or US — never the literal {@code "CA"}
 *                    the source's weekly job sent for every member on earth (inventory D5)
 * @param zone        their own {@code users.timezone}, which decides what "this month" and "this
 *                    week" mean for them (readiness gap row 15)
 */
public record CoachAudience(UUID userId, UUID householdId, String displayName,
                            CurrencyCode currency, Jurisdiction country, ZoneId zone) {

    public CoachAudience {
        if (userId == null || householdId == null || currency == null || country == null
            || zone == null) {
            throw new IllegalArgumentException(
                "a coach audience needs a member, a household, a currency, a jurisdiction and a "
                    + "timezone; every one of them is a column on the member's own users row");
        }
    }

    /** Something to address them by when the display name is blank. */
    public String salutation() {
        return displayName == null || displayName.isBlank() ? "the member" : displayName;
    }
}
