package finance.trove.networth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code range} query parameter of {@code GET /v1/networth/series} to
 * {@link NetWorthRange}.
 *
 * <p>Spring Boot auto-registers any {@link Converter} bean it finds — no edit to
 * {@code WebConfig} or any other shared configuration class is needed for this to take effect,
 * which matters because those files are off limits for this step (concurrent worktrees own them
 * jointly this wave). Registering the converter as a bean, rather than relying on the default
 * {@code String -> Enum} conversion, is what lets the wire values ({@code "3M"}, {@code "1Y"}) be
 * spelled differently from the Java constant names ({@code THREE_MONTHS}, {@code ONE_YEAR}), which
 * they must be: none of the four wire values is a legal Java identifier.
 */
@Component
final class NetWorthRangeConverter implements Converter<String, NetWorthRange> {

    @Override
    public NetWorthRange convert(String source) {
        return NetWorthRange.fromWireValue(source);
    }
}
