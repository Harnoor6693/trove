package finance.trove.common.time;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The install-default {@link UserTimeZoneProvider}: {@code TROVE_DEFAULT_TIMEZONE}, or UTC.
 *
 * <p>Registered {@code @ConditionalOnMissingBean} would be the Boot idiom, but the replacement in
 * collapse step 3 is a {@code @Primary} repository-backed provider, so this stays an ordinary
 * component and step 3 marks its own bean primary.
 *
 * <p>An unparseable zone id fails the context refresh rather than silently falling back to UTC:
 * a typo in {@code TROVE_DEFAULT_TIMEZONE} would otherwise show up months later as month
 * boundaries in the wrong place, which is exactly the class of bug gap P2-15 is about.
 */
@Component
public class DefaultUserTimeZoneProvider implements UserTimeZoneProvider {

    private final ZoneId zone;

    public DefaultUserTimeZoneProvider(TimeProperties properties) {
        String configured = properties.defaultTimezone();
        try {
            this.zone = ZoneId.of(configured);
        } catch (DateTimeException e) {
            throw new IllegalStateException("TROVE_DEFAULT_TIMEZONE is not a valid IANA zone id: '"
                + configured + "'. Use a name such as America/Toronto or UTC.", e);
        }
    }

    @Override
    public ZoneId zoneOf(UUID userId) {
        return zone;
    }
}
