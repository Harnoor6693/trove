package finance.trove.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tripwires of {@code docs/recovery/06-COLLAPSE-PLAN.md} §9 that are properties of the source
 * <em>text</em> rather than of the bytecode, and so cannot be expressed as an ArchUnit rule.
 *
 * <p>Three of them:
 * <ul>
 *   <li><strong>Client-supplied identity (risk 2).</strong> {@code @RequestHeader("X-User-Id")}
 *       is the G1 defect: the gateway trusted a header for the caller's identity, so anyone could
 *       read anyone's ledger by editing one header. The annotation's value is a compile-time
 *       constant that survives into the class file, but matching it through ArchUnit means
 *       walking parameter annotations of every controller method — a source scan says the same
 *       thing in five lines and fails with the file and line number.</li>
 *   <li><strong>Swallowed exceptions.</strong> ArchUnit cannot see inside a method body at all.
 *       A {@code catch (Exception e)} that only logs is how a failed write becomes a silent
 *       success — the shape behind D4's dropped category write-back and the source
 *       {@code RateLimitFilter}'s "failing open". Catching {@code Exception} is allowed where it
 *       is genuinely right, but it has to be either rethrown or justified in a
 *       {@code // swallow-ok:} comment that says why the failure is safe to drop.</li>
 *   <li><strong>A currency literal outside {@code CurrencyCode} (risk 5, finding F1).</strong>
 *       {@code "CAD"} and {@code "USD"} are string constants that ArchUnit sees only as opaque
 *       constant-pool entries with no field or class to anchor a rule to — the source shipped
 *       twelve executable sites across ingestion, subscriptions, budgets and net worth that
 *       defaulted a row's currency to a literal instead of reading {@code users.currency} or the
 *       row it describes, and every one of them is a string a bytecode-level rule cannot see
 *       coming. Requested independently by both collapse step 8 and step 10, each of which hit
 *       finding F1's shape in its own module and found no rule stopping the next one.</li>
 * </ul>
 *
 * <p>The scan reads {@code core/src/main/java}, located from the compiled-class directory rather
 * than from the working directory, so it works the same under Surefire, an IDE and a
 * {@code -pl core} reactor build.
 */
class SourceConventionTest {

    /** Headers that must never be an input to an authorisation decision (§9 risk 2). */
    private static final Pattern BANNED_IDENTITY_HEADER = Pattern.compile(
        "@RequestHeader\\s*\\(\\s*(?:name\\s*=\\s*|value\\s*=\\s*)?\"(?i:X-(?:Trove-)?User-Id)\"");

    /**
     * The forwarding header. Reading it by hand is the gap P2-5 defect: the pre-collapse
     * {@code RateLimitFilter} and {@code AuditLogFilter} both did
     * {@code getHeader("X-Forwarded-For").split(",")[0]}, which takes the LEFTMOST value of a
     * header any client can send — so a caller chose its own rate-limit bucket, its own
     * login-lockout bucket, and the address written into the audit trail against it. Deciding the
     * address is Tomcat's {@code RemoteIpValve}'s job, from a configured proxy list, and reading
     * the result is {@code ClientIpResolver}'s.
     */
    private static final Pattern FORWARDING_HEADER =
        Pattern.compile("\"(?i:X-Forwarded-For|X-Real-IP|Forwarded)\"");

    /** The catch clause that needs a justification. */
    private static final Pattern BROAD_CATCH =
        Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?(?:java\\.lang\\.)?Exception\\s");

    private static final Pattern RETHROW = Pattern.compile("\\bthrow\\b");

    private static final String JUSTIFICATION = "// swallow-ok:";

    /** How far after a {@code catch (Exception} a rethrow or justification may appear. */
    private static final int JUSTIFICATION_WINDOW_LINES = 12;

    /** A currency named as a string literal rather than through {@code CurrencyCode} (risk 5). */
    private static final Pattern CURRENCY_LITERAL = Pattern.compile("\"(?:CAD|USD)\"");

    /**
     * Every file outside {@code CurrencyCode} that names {@code "CAD"} or {@code "USD"} today,
     * with the exact number of mentions it is allowed and why. Each one, verified by hand, is a
     * comment — a javadoc citing a default this collapse already removed, or a wire-format
     * example — never a live default a runtime path can execute.
     *
     * <p>The count is checked, not just the file: a hit beyond a listed file's allowed number
     * still fails the test, so a real literal added later cannot hide behind an old comment's
     * exemption.
     */
    private static final Map<String, AllowedCurrencyMentions> CURRENCY_LITERAL_ALLOWLIST =
        Map.ofEntries(
            Map.entry("OfxNormalizer.java", new AllowedCurrencyMentions(1,
                "javadoc citing the removed `DEFAULT_CURRENCY = \"USD\"` default")),
            Map.entry("ExchangeSetupTokenService.java", new AllowedCurrencyMentions(1,
                "javadoc citing the removed \"CAD\" fallback when the bridge declared no "
                    + "currency")),
            Map.entry("CsvValues.java", new AllowedCurrencyMentions(1,
                "comment describing a trailing \"CAD\" currency symbol in bank-exported text, "
                    + "not a coded default")),
            Map.entry("DeduplicationService.java", new AllowedCurrencyMentions(1,
                "javadoc citing finding F4's removed \"CAD\" default for a row with no "
                    + "currency")),
            Map.entry("SubscriptionSummaryResponse.java", new AllowedCurrencyMentions(1,
                "javadoc citing finding F1's removed hardcoded \"CAD\" summary currency")),
            Map.entry("DetectedSubscription.java", new AllowedCurrencyMentions(1,
                "javadoc citing finding F1's removed \"CAD\" column default")),
            Map.entry("Money.java", new AllowedCurrencyMentions(1,
                "javadoc JSON wire-format example (\"currency\":\"CAD\"), not a coded default")),
            Map.entry("BudgetPeriod.java", new AllowedCurrencyMentions(1,
                "javadoc citing the removed literal \"CAD\" period default")),
            Map.entry("SavingsGoal.java", new AllowedCurrencyMentions(1,
                "javadoc citing the removed literal \"CAD\" goal default")),
            Map.entry("Asset.java", new AllowedCurrencyMentions(1,
                "javadoc citing the removed literal \"CAD\" asset default")));

    /** One file's allowed count of currency-literal mentions, and why they are allowed. */
    private record AllowedCurrencyMentions(int count, String reason) { }

    private static final List<Path> SOURCES = mainSources();

    @Test
    @DisplayName("The scan actually found the production sources")
    void theScanHasSomethingToScan() {
        assertThat(SOURCES)
            .as("core/src/main/java should contain the classes these conventions constrain")
            .hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("No controller reads X-User-Id or X-Trove-User-Id (§9 risk 2)")
    void noControllerTrustsAClientSuppliedIdentityHeader() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SOURCES) {
            offenders.addAll(identityHeaderOffenders(source.getFileName().toString(),
                read(source)));
        }

        assertThat(offenders)
            .as("identity comes from the authenticated principal, never from a request header — "
                + "a header the client sets is a header the client can change (G1, §9 risk 2)")
            .isEmpty();
    }

    @Test
    @DisplayName("No production class reads X-Forwarded-For by hand (gap P2-5)")
    void noClassParsesAForwardingHeader() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SOURCES) {
            offenders.addAll(forwardingHeaderOffenders(source.getFileName().toString(),
                read(source)));
        }

        assertThat(offenders)
            .as("the caller's address is resolved once, by ClientIpResolver, from the socket "
                + "AFTER the trusted-proxy valve. A class that reads the header itself takes the "
                + "leftmost value — the one the client wrote — and reintroduces the spoof the "
                + "valve exists to close (§1.5, §5.1)")
            .isEmpty();
    }

    @Test
    @DisplayName("Every catch (Exception ...) rethrows or carries a // swallow-ok: justification")
    void noExceptionIsSilentlySwallowed() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SOURCES) {
            offenders.addAll(swallowedExceptionOffenders(source.getFileName().toString(),
                read(source)));
        }

        assertThat(offenders)
            .as("a catch (Exception) that only logs turns a failed write into a silent success. "
                + "Rethrow it, or write a // swallow-ok: comment in the block saying why the "
                + "failure is safe to drop")
            .isEmpty();
    }

    @Test
    @DisplayName("No \"CAD\"/\"USD\" string literal outside CurrencyCode or an allowlisted "
        + "comment (§9 risk 5)")
    void currencyIsAnEnumAndNeverALiteral() {
        List<String> offenders = new ArrayList<>();
        for (Path source : SOURCES) {
            String name = source.getFileName().toString();
            if (name.equals("CurrencyCode.java")) {
                continue;
            }
            List<String> hits = currencyLiteralOffenders(name, read(source));
            int allowed = CURRENCY_LITERAL_ALLOWLIST.containsKey(name)
                ? CURRENCY_LITERAL_ALLOWLIST.get(name).count() : 0;
            if (hits.size() > allowed) {
                offenders.addAll(hits.subList(allowed, hits.size()));
            }
        }

        assertThat(offenders)
            .as("currency comes from the row it describes or from users.currency, never a "
                + "literal — finding F1 shipped twelve executable \"CAD\" sites before this "
                + "collapse, requested independently by both step 8 and step 10. A hit beyond "
                + "an allowlisted file's counted, justified mentions is a NEW one and must be "
                + "fixed, not exempted")
            .isEmpty();
    }

    @Test
    @DisplayName("Both detectors actually detect — the rules are not passing vacuously")
    void theDetectorsCatchWhatTheyClaimTo() {
        // Neither rule has a subject in core/src/main today. A convention test that has never
        // seen a violation is indistinguishable from a broken regex, so both detectors are run
        // here against source they are supposed to reject and source they are supposed to allow.
        assertThat(identityHeaderOffenders("Fake.java", List.of(
            "    public Dto get(@RequestHeader(\"X-User-Id\") UUID userId) {")))
            .hasSize(1);
        assertThat(identityHeaderOffenders("Fake.java", List.of(
            "    public Dto get(@RequestHeader(name = \"x-trove-user-id\") UUID userId) {")))
            .hasSize(1);
        assertThat(identityHeaderOffenders("Fake.java", List.of(
            "    public Dto get(@RequestHeader(\"X-Request-Id\") String requestId) {",
            "    public Dto get(@AuthenticationPrincipal TroveUserDetails principal) {")))
            .isEmpty();

        assertThat(forwardingHeaderOffenders("Fake.java", List.of(
            "        String ip = request.getHeader(\"X-Forwarded-For\").split(\",\")[0];")))
            .as("the exact expression the pre-collapse gateway used")
            .hasSize(1);
        assertThat(forwardingHeaderOffenders("Fake.java", List.of(
            "     * never a hand-parsed \"X-Forwarded-For\" (gap P2-5)",
            "        // \"X-Forwarded-For\" is the valve's input, not ours")))
            .as("a javadoc or a comment naming the header is how the rule explains itself")
            .isEmpty();

        assertThat(swallowedExceptionOffenders("Fake.java", List.of(
            "        } catch (Exception e) {",
            "            LOG.warn(\"ignored\", e);",
            "        }")))
            .hasSize(1);
        assertThat(swallowedExceptionOffenders("Fake.java", List.of(
            "        } catch (Exception e) {",
            "            throw new IllegalStateException(e);",
            "        }")))
            .isEmpty();
        assertThat(swallowedExceptionOffenders("Fake.java", List.of(
            "        } catch (Exception e) {",
            "            // swallow-ok: the caller re-enqueues on the next sweep.",
            "            LOG.debug(\"ignored\", e);",
            "        }")))
            .isEmpty();
        assertThat(swallowedExceptionOffenders("Fake.java", List.of(
            "        } catch (IOException e) {",
            "            LOG.warn(\"ignored\", e);",
            "        }")))
            .as("a narrow catch needs no justification")
            .isEmpty();

        assertThat(currencyLiteralOffenders("Fake.java", List.of(
            "        return currency == null ? \"CAD\" : currency;")))
            .as("the exact shape finding F1 named: a literal default when nothing else says "
                + "otherwise")
            .hasSize(1);
        assertThat(currencyLiteralOffenders("Fake.java", List.of(
            "        row.setCurrency(user.getCurrency());")))
            .as("reading the currency from the row or the user is exactly what the rule allows")
            .isEmpty();
    }

    private static List<String> identityHeaderOffenders(String name, List<String> lines) {
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (BANNED_IDENTITY_HEADER.matcher(lines.get(i)).find()) {
                offenders.add(name + ":" + (i + 1) + " " + lines.get(i).trim());
            }
        }
        return offenders;
    }

    private static List<String> forwardingHeaderOffenders(String name, List<String> lines) {
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // The javadoc of ClientIpResolver and of the filters it replaced names the header on
            // purpose, to say why it is not read; a comment is not a code path.
            if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                continue;
            }
            if (FORWARDING_HEADER.matcher(line).find()) {
                offenders.add(name + ":" + (i + 1) + " " + line.trim());
            }
        }
        return offenders;
    }

    private static List<String> currencyLiteralOffenders(String name, List<String> lines) {
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (CURRENCY_LITERAL.matcher(lines.get(i)).find()) {
                offenders.add(name + ":" + (i + 1) + " " + lines.get(i).trim());
            }
        }
        return offenders;
    }

    private static List<String> swallowedExceptionOffenders(String name, List<String> lines) {
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (BROAD_CATCH.matcher(lines.get(i)).find() && !isJustified(lines, i)) {
                offenders.add(name + ":" + (i + 1) + " " + lines.get(i).trim());
            }
        }
        return offenders;
    }

    /**
     * A broad catch is acceptable if, within the following few lines, the block either throws or
     * explains itself. The window is deliberately small: a justification twenty lines below the
     * catch is not a justification a reviewer will read.
     */
    private static boolean isJustified(List<String> lines, int catchLine) {
        int end = Math.min(lines.size(), catchLine + JUSTIFICATION_WINDOW_LINES);
        for (int i = catchLine; i < end; i++) {
            String line = lines.get(i);
            if (line.contains(JUSTIFICATION)) {
                return true;
            }
            if (RETHROW.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> read(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }

    /**
     * {@code core/src/main/java}, derived from {@code core/target/classes} so the scan does not
     * depend on the process working directory.
     */
    private static List<Path> mainSources() {
        Path classes;
        try {
            classes = Paths.get(finance.trove.app.TroveApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the compiled classes directory", e);
        }
        Path moduleRoot = classes.getParent().getParent();
        Path sourceRoot = moduleRoot.resolve("src").resolve("main").resolve("java");

        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + sourceRoot, e);
        }
    }
}
