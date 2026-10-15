package finance.trove.common.time;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.time.*}.
 *
 * <p>{@code TROVE_DEFAULT_TIMEZONE} is the zone used for a user who has not chosen one yet — at
 * first boot, and for the admin account {@code FirstRunBootstrap} creates. It is <em>not</em>
 * {@code TZ}: the container clock stays UTC and every {@code @Scheduled} trigger fires on UTC
 * (§5.6); this is only the fallback for per-user "today" and "this month" boundaries
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6, gap P2-15).
 *
 * @param defaultTimezone an IANA zone id such as {@code America/Toronto}; defaults to {@code UTC}
 */
@ConfigurationProperties(prefix = "trove.time")
public record TimeProperties(String defaultTimezone) {

    public TimeProperties {
        if (defaultTimezone == null || defaultTimezone.isBlank()) {
            defaultTimezone = "UTC";
        }
    }
}
