package finance.trove.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import finance.trove.common.repository.UserKeyedRepository;
import finance.trove.common.repository.UserOwnedRepository;
import finance.trove.common.repository.UserScopedRepository;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The monolith's architecture tripwires ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9).
 *
 * <p>The rules below are the ones that make sense while the module is a skeleton; they are the
 * regression gate for the mechanical package renames of collapse steps 2–15, where a moved class
 * that keeps its old {@code package} line, or a stray Jackson 2 / MinIO import carried over from
 * a source pom, is exactly the mistake that would otherwise pass compilation.
 *
 * <p>Collapse step 2 adds the rules whose subject now exists: no {@code findById}/{@code findAll}
 * on a household-scoped repository (risk 2), no {@code RestClient.builder()} outside
 * {@code HttpClientConfig} (risk 9), no {@code @Component} on an {@code OncePerRequestFilter}
 * (risk 8), no {@code java.util.Date} and no binary floating point in the domain (risk 6).
 * {@link SourceConventionTest} carries the two rules that are about source text rather than
 * bytecode — the banned identity headers of risk 2, and swallowed exceptions.
 *
 * <p>Still deliberately absent, because the code they constrain does not exist: no
 * {@code SecurityFilterChain} outside {@code finance.trove.app} (risk 1 — there is only one
 * today, and the rule is worth having once a domain package could add a second), and no
 * {@code "CAD"}/{@code "USD"} literal outside {@code CurrencyCode} (risk 5 — nothing but
 * {@code CurrencyCode} mentions a currency yet, so the rule would pass vacuously and its first
 * real subject arrives with step 6).
 */
class ArchitectureTest {

    /**
     * Only this module's own compiled output, resolved from the code source rather than the
     * JVM classpath so that Surefire's manifest-only classpath jar cannot hide it.
     */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPath(mainOutputDirectory());

    /**
     * Package prefixes of modules that are deleted in collapse step 24. A class under one of
     * these has been copied across without the package rename §2 requires. The target package
     * names of §1.3 that merely resemble them — {@code finance.trove.ingestion},
     * {@code finance.trove.categorisation}, {@code finance.trove.common},
     * {@code finance.trove.budgets}, {@code finance.trove.receipts},
     * {@code finance.trove.subscriptions}, {@code finance.trove.notifications},
     * {@code finance.trove.ai.client} — are deliberately absent from this list.
     */
    /** The only class allowed to call {@code RestClient.builder()} (§5.5); nested types included. */
    private static final String HTTP_CLIENT_CONFIG = "finance.trove.app.HttpClientConfig";

    /**
     * §5.1's final filter chain, by name. Each is registered exactly once, explicitly, by
     * {@code SecurityConfig} or {@code ObservabilityConfig}.
     */
    private static final String[] EXPECTED_FILTERS = {
        "finance.trove.common.observability.RequestIdMdcFilter",
        "finance.trove.auth.jwt.CookieOrBearerJwtAuthFilter",
        "finance.trove.app.ratelimit.RateLimitFilter",
        "finance.trove.auth.jwt.CsrfRequiresHeaderFilter",
        "finance.trove.auth.jwt.CsrfCookieFilter",
        "finance.trove.app.audit.AuditLogFilter",
    };

    private static final String[] LEGACY_PACKAGES = {
        "finance.trove.gateway..",
        "finance.trove.tax..",
        "finance.trove.report..",
        "finance.trove.modelmanager..",
        "finance.trove.budget..",
        "finance.trove.receipt..",
        "finance.trove.subscription..",
        "finance.trove.notification..",
        "finance.trove.privacy..",
        "finance.trove.plugin..",
        "finance.trove.model..",
        "finance.trove.ai.gateway..",
    };

    @Test
    @DisplayName("Every production class lives under finance.trove")
    void allClassesLiveUnderFinanceTrove() {
        assertThat(PRODUCTION_CLASSES).isNotEmpty();

        classes().should().resideInAnyPackage("finance.trove..")
            .because("the monolith owns one package root (§1.3); anything else is unreachable "
                + "by component scanning and invisible to these rules")
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Nothing depends on MinIO — object storage is a local disk volume now")
    void noMinioDependency() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("io.minio..")
            .because("MinIO is dropped (§1.1); receipts and exports live on the trove_data "
                + "volume through LocalDiskStorage")
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Jackson 3 only — no com.fasterxml.jackson.databind")
    void noJackson2Databind() {
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("com.fasterxml.jackson.databind..")
            .because("Spring Boot 4.1 runs Jackson 3 (tools.jackson.*); a Jackson 2 databind "
                + "import means a second ObjectMapper and a second wire format (§1.2). "
                + "com.fasterxml.jackson.annotation.* is unchanged in Jackson 3 and stays legal")
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("No class carries a package from a module that the collapse deletes")
    void noLegacyModulePackages() {
        classes().should().resideOutsideOfPackages(LEGACY_PACKAGES)
            .because("moved code must be renamed into its §1.3 package in the same commit (§2)")
            .check(PRODUCTION_CLASSES);

        noClasses().should().dependOnClassesThat().resideInAnyPackage(LEGACY_PACKAGES)
            .because("the deleted modules are not on the monolith's classpath and must not "
                + "come back as a dependency (§8)")
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("A household-scoped repository never inherits or declares an unscoped finder")
    void householdScopedRepositoriesHaveNoUnscopedFinders() {
        // The base interfaces extend Repository, not CrudRepository, so findById/findAll are not
        // inherited — that is the mechanism (§1.5). This rule catches the other half: a
        // subinterface declaring one by hand, which Spring Data would happily implement as an
        // unscoped query and which is a cross-household IDOR on every by-id route (§9 risk 2).
        noMethods()
            .that().areDeclaredInClassesThat().areAssignableTo(UserScopedRepository.class)
            .or().areDeclaredInClassesThat().areAssignableTo(UserOwnedRepository.class)
            .or().areDeclaredInClassesThat().areAssignableTo(UserKeyedRepository.class)
            .should().haveNameMatching("findById|findAll|deleteById|existsById|getById|"
                + "getReferenceById|findAllById|deleteAll|count")
            .because("every query on a scoped repository takes householdId (or userId); an "
                + "unscoped finder hands one household another household's rows (§9 risk 2)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    /**
     * Repository interfaces allowed to extend Spring Data's {@code Repository} marker directly
     * instead of one of the three scoped bases.
     *
     * <p>One entry, and it needs its justification here rather than in a commit message:
     * {@code HouseholdRepository} is the repository over the tenancy root <em>itself</em>. The
     * scoped bases exist to force a {@code householdId} parameter onto every query over a
     * household-<em>owned</em> table; this table is the household, so the parameter would be the
     * primary key and the rule would be circular. The id it is called with always comes from the
     * signed token's {@code hid} claim, never from a path variable or a body.
     *
     * <p>Anything added here is a decision to opt a table out of tenancy enforcement, which is
     * the mistake §9 risk 2 is about. The test below fails if a name in this set does not
     * resolve, so an entry cannot rot into a silent blanket exemption after a rename.
     */
    private static final Set<String> UNSCOPED_REPOSITORY_ALLOWLIST =
        Set.of("finance.trove.households.HouseholdRepository");

    @Test
    @DisplayName("Every repository extends one of the three scoped bases, or is allowlisted")
    void everyRepositoryIsScopedOrDeliberatelyExempt() {
        // The two rules below catch a repository that inherits the unscoped finder set and one
        // that declares an unscoped finder on a scoped base. Neither sees the third shape: an
        // interface extending org.springframework.data.repository.Repository directly and
        // hand-declaring findById. Spring Data implements that as an unscoped query, ArchUnit
        // saw nothing, and it is exactly the regression §9 risk 2 says ArchUnit retires — steps
        // 5-15 run in parallel worktrees whose agents may not edit this file, so the hole has to
        // be closed here or not at all.
        classes()
            .that().resideInAPackage("finance.trove..")
            .and().areInterfaces()
            .and().areAssignableTo("org.springframework.data.repository.Repository")
            .and(new DescribedPredicate<JavaClass>("are not allowlisted") {
                @Override
                public boolean test(JavaClass candidate) {
                    return !UNSCOPED_REPOSITORY_ALLOWLIST.contains(candidate.getFullName());
                }
            })
            .should().beAssignableTo(UserScopedRepository.class)
            .orShould().beAssignableTo(UserOwnedRepository.class)
            .orShould().beAssignableTo(UserKeyedRepository.class)
            .because("a repository that extends the bare Repository marker can hand-declare "
                + "findById, which Spring Data implements as an unscoped query over a "
                + "household_id-bearing table — the precise shape of the G1 IDOR (§9 risk 2). "
                + "Extend UserScopedRepository, UserOwnedRepository or UserKeyedRepository, or "
                + "add the interface to UNSCOPED_REPOSITORY_ALLOWLIST with its reason")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);

        assertThat(UNSCOPED_REPOSITORY_ALLOWLIST)
            .as("an allowlist entry that no longer names a real interface is a blanket exemption "
                + "nobody can see; a rename must break this, not silently widen the rule")
            .allSatisfy(name -> assertThat(PRODUCTION_CLASSES.contain(name))
                .as("%s", name)
                .isTrue());
    }

    @Test
    @DisplayName("No repository extends JpaRepository or CrudRepository directly")
    void noRepositoryInheritsTheUnscopedFinderSet() {
        // The rule above bans the unscoped finder NAMES. This one bans the way they come back by
        // accident: extending JpaRepository inherits findById, findAll, deleteById and count in
        // one line, and inherited methods are not declared anywhere for the name rule to see.
        // Every Trove repository extends UserScopedRepository, UserOwnedRepository, or the bare
        // Spring Data Repository marker and declares exactly the finders it wants.
        noClasses()
            .that().resideInAPackage("finance.trove..")
            .and().areInterfaces()
            .should().beAssignableTo("org.springframework.data.jpa.repository.JpaRepository")
            .orShould().beAssignableTo("org.springframework.data.repository.CrudRepository")
            .orShould().beAssignableTo("org.springframework.data.repository.PagingAndSortingRepository")
            .because("inheriting the full CRUD surface hands every later step an unscoped "
                + "finder to reach for by accident, which is the structural cause of the G1 "
                + "IDOR (§9 risk 2)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("No controller outside auth/users/households touches a credential entity")
    void credentialEntitiesAreNotReachableFromOtherControllers() {
        // Credentials, refresh tokens and TOTP secrets are the auth package's business. A
        // controller in, say, finance.trove.transactions that referenced one would be either a
        // second authentication path outside the security chain (§9 risk 1) or a leak of a
        // secret into a DTO. Both are cheap to prevent now and expensive to find later.
        noClasses()
            .that().resideOutsideOfPackages("finance.trove.auth..", "finance.trove.users..",
                "finance.trove.households..", "finance.trove.app..")
            .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .or().resideOutsideOfPackages("finance.trove.auth..", "finance.trove.users..",
                "finance.trove.households..", "finance.trove.app..")
            .and().areAnnotatedWith("org.springframework.stereotype.Controller")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.local.LocalCredential")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.session.RefreshToken")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.totp.TotpCredential")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.reset.PasswordResetToken")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.oidc.OidcLink")
            .because("credential entities belong to the auth package; a controller elsewhere "
                + "holding one is either a second authentication path outside the security "
                + "chain (§9 risk 1) or a secret on its way into a DTO")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("As of today NO controller anywhere touches a credential entity")
    void noControllerAtAllTouchesACredentialEntity() {
        // The rule above is a regression gate for packages that do not exist yet, so on its own
        // it passes vacuously. This one is the same rule with the exemption removed, and it has
        // real subjects right now: the four controllers of collapse step 3. Every one of them
        // goes through a service, so a credential entity never reaches a request-handling method
        // and can never be serialised into a response by accident.
        noClasses()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.local.LocalCredential")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.session.RefreshToken")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.totp.TotpCredential")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.reset.PasswordResetToken")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                "finance.trove.auth.oidc.OidcLink")
            .because("a controller holding a password hash or a token hash is one careless "
                + "return statement away from serialising it")
            .check(PRODUCTION_CLASSES);

        assertThat(PRODUCTION_CLASSES.stream()
            .filter(candidate -> candidate.isAnnotatedWith(
                "org.springframework.web.bind.annotation.RestController"))
            .toList())
            .as("the rule above needs real subjects or it proves nothing")
            .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Only finance.trove.app declares a SecurityFilterChain (§9 risk 1)")
    void onlyTheAppPackageDeclaresTheSecurityChain() {
        noClasses()
            .that().resideOutsideOfPackage("finance.trove.app")
            .should().beAnnotatedWith(
                "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity")
            .because("a package that declares its own chain has opted out of every rule in the "
                + "real one — the M1 pattern (§9 risk 1)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);

        noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("finance.trove.app")
            .should().haveRawReturnType(
                "org.springframework.security.web.SecurityFilterChain")
            .because("there is one chain, and it is finance.trove.app.SecurityConfig's (§5.1)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("The scoped repository bases exist and take no unscoped finder themselves")
    void theScopedRepositoryBasesAreTheOnlyFinderSource() {
        assertThat(UserScopedRepository.class.getMethods())
            .noneSatisfy(method -> assertThat(method.getName())
                .isIn("findById", "findAll", "deleteById", "existsById"));
        assertThat(UserOwnedRepository.class.getMethods())
            .noneSatisfy(method -> assertThat(method.getName())
                .isIn("findById", "findAll", "deleteById", "existsById"));
        assertThat(UserKeyedRepository.class.getMethods())
            .noneSatisfy(method -> assertThat(method.getName())
                .isIn("findById", "findAll", "deleteById", "existsById"));
        assertThat(UserScopedRepository.class.getMethods())
            .anySatisfy(method -> assertThat(method.getName())
                .isEqualTo("findByIdAndHouseholdId"));
        assertThat(UserKeyedRepository.class.getMethods())
            .as("an entity whose primary key IS the user id is still scoped by it")
            .anySatisfy(method -> assertThat(method.getName()).isEqualTo("findByUserId"));
    }

    @Test
    @DisplayName("No legacy date type is a field, parameter or return type — java.time only")
    void noLegacyDateApiInAnySignature() {
        // The rule's target is a legacy date type that gets STORED or PASSED AROUND. That is what
        // makes a per-user date boundary impossible to reason about (gap P2-15): java.util.Date is
        // mutable, prints in the JVM's default zone, and silently mixes "an instant" with "a
        // calendar day".
        //
        // Converting at the edge of a third-party API is the opposite of that problem — it is how
        // the legacy type is kept OUT of the domain. JJWT 0.12's builder takes java.util.Date and
        // has no Instant overload; pgjdbc's parameter binding takes java.sql.Timestamp. Both
        // conversions are total, timezone-free and immediately discarded. The original rule banned
        // the dependency outright, which cannot distinguish `Date.from(instant)` at a boundary
        // from `new Date()` in a service, so it is expressed below as what it means.
        String[] legacy = {
            "java.util.Date", "java.util.Calendar", "java.sql.Date", "java.sql.Timestamp",
        };

        noFields()
            .should().haveRawType(legacy[0])
            .orShould().haveRawType(legacy[1])
            .orShould().haveRawType(legacy[2])
            .orShould().haveRawType(legacy[3])
            .because("a stored legacy date is a mutable instant with a hidden default timezone "
                + "(gap P2-15)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);

        noMethods()
            .should().haveRawReturnType(legacy[0])
            .orShould().haveRawReturnType(legacy[1])
            .orShould().haveRawReturnType(legacy[2])
            .orShould().haveRawReturnType(legacy[3])
            .because("a legacy date handed to a caller is a legacy date in the caller (gap P2-15)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);

        noMethods()
            .should().haveRawParameterTypes(legacy[0])
            .orShould().haveRawParameterTypes(legacy[1])
            .orShould().haveRawParameterTypes(legacy[2])
            .orShould().haveRawParameterTypes(legacy[3])
            .because("an API that accepts a legacy date invites its callers to hold one")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Nothing reads the wall clock through a legacy date type")
    void noLegacyWallClockReads() {
        // The dangerous calls, as opposed to the conversions. `new Date()` and
        // `Calendar.getInstance()` both read the JVM's clock in the JVM's default zone, which is
        // exactly the thing UserClock exists to replace (gap P2-15): "today" is per user, from
        // users.timezone, never from the container's TZ.
        classes().should(new ArchCondition<JavaClass>("not read the wall clock via a legacy "
            + "date type") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
                    String owner = call.getTargetOwner().getFullName();
                    if (("java.util.Date".equals(owner)
                        && call.getTarget().getRawParameterTypes().isEmpty())
                        || "java.util.GregorianCalendar".equals(owner)) {
                        events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " reads the clock through new " + owner
                                + "() — use UserClock (gap P2-15)"));
                    }
                }
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if ("java.util.Calendar".equals(call.getTargetOwner().getFullName())) {
                        events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " uses java.util.Calendar, which resolves "
                                + "\"today\" in the JVM's default zone (gap P2-15)"));
                    }
                }
            }
        }).check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Legacy date types appear only where a third-party API demands one")
    void legacyDatesOnlyAtNamedBoundaries() {
        // Not a style rule: it is the list of places where the exemption above is allowed to be
        // used, so that adding a sixth one is a decision somebody makes on purpose. JJWT's builder
        // and parser take java.util.Date; pgjdbc's parameter binding takes java.sql.Timestamp.
        Set<String> allowed = Set.of(
            "finance.trove.auth.jwt.JwtIssuerService",
            "finance.trove.auth.jwt.JwtVerifier",
            "finance.trove.auth.local.LoginAttemptRepository");

        classes().should(new ArchCondition<JavaClass>("convert to a legacy date type only in "
            + allowed) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (allowed.contains(item.getFullName())) {
                    return;
                }
                for (JavaClass dependency : item.getDirectDependenciesFromSelf().stream()
                    .map(dep -> dep.getTargetClass()).toList()) {
                    String name = dependency.getFullName();
                    if ("java.util.Date".equals(name) || "java.util.Calendar".equals(name)
                        || "java.sql.Date".equals(name) || "java.sql.Timestamp".equals(name)) {
                        events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " touches " + name + "; only the JJWT and "
                                + "pgjdbc boundaries may, and they convert immediately "
                                + "(gap P2-15)"));
                    }
                }
            }
        }).check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("No float or double field in the domain — money is BigDecimal (§9 risk 6)")
    void noBinaryFloatingPointInTheDomain() {
        noFields()
            .that().areDeclaredInClassesThat()
            .resideInAnyPackage("finance.trove..domain..", "finance.trove..events..")
            .or().areAnnotatedWith("jakarta.persistence.Entity")
            .or().areDeclaredInClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
            .or().areDeclaredInClassesThat().areAnnotatedWith("jakarta.persistence.Embeddable")
            .should().haveRawType(float.class)
            .orShould().haveRawType(double.class)
            .orShould().haveRawType(Float.class)
            .orShould().haveRawType(Double.class)
            .because("IEEE-754 cannot represent 0.1; a ledger stored in a double is wrong by "
                + "construction (§9 risk 6)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("No domain or entity method returns a float or double either")
    void noBinaryFloatingPointOnDomainApis() {
        noMethods()
            .that().areDeclaredInClassesThat()
            .resideInAnyPackage("finance.trove..domain..", "finance.trove..events..")
            .should().haveRawReturnType(float.class)
            .orShould().haveRawReturnType(double.class)
            .orShould().haveRawReturnType(Float.class)
            .orShould().haveRawReturnType(Double.class)
            .because("a double-returning helper is how a BigDecimal amount loses precision on "
                + "its way to a caller (§9 risk 6)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Only HttpClientConfig constructs a RestClient (§9 risk 9)")
    void onlyHttpClientConfigBuildsHttpClients() {
        classes().should(new ArchCondition<JavaClass>("not construct a RestClient outside "
            + HTTP_CLIENT_CONFIG) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getFullName().startsWith(HTTP_CLIENT_CONFIG)) {
                    return;
                }
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    String target = call.getTargetOwner().getFullName();
                    String name = call.getName();
                    boolean forbidden = "org.springframework.web.client.RestClient".equals(target)
                        && ("builder".equals(name) || "create".equals(name));
                    if (forbidden) {
                        events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " calls RestClient." + name + "() — every "
                                + "outbound client is built in " + HTTP_CLIENT_CONFIG
                                + " so that timeouts and the pinned DNS resolver cannot be "
                                + "skipped (§5.5, §9 risk 9)"));
                    }
                }
            }
        }).check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("No OncePerRequestFilter is a @Component — one registration each (§9 risk 8)")
    void noServletFilterIsAlsoAComponent() {
        noClasses()
            .that().areAssignableTo(OncePerRequestFilter.class)
            .should().beAnnotatedWith(Component.class)
            .because("a filter that is both a bean and a Filter is registered twice by Boot — "
                + "the bug that ran every filter twice in seven services (§9 risk 8). Filters "
                + "are registered explicitly, once, by a FilterRegistrationBean")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Every servlet filter this application adds is constructed, never scanned")
    void everyFilterIsConstructedExplicitly() {
        // The companion of noServletFilterIsAlsoAComponent: that one bans the annotation, this
        // one names the filters and asserts they exist, so a filter deleted in a refactor fails
        // here instead of silently leaving the chain (§5.1's order is only a fact while every
        // member of it is present).
        for (String filter : EXPECTED_FILTERS) {
            assertThat(PRODUCTION_CLASSES.contain(filter))
                .as("%s is part of §5.1's filter chain", filter)
                .isTrue();
            assertThat(PRODUCTION_CLASSES.get(filter).isAnnotatedWith(Component.class))
                .as("%s must be constructed by SecurityConfig, not scanned: a filter that is "
                    + "both a bean and a Filter is registered twice by Boot (§9 risk 8)", filter)
                .isFalse();
        }
    }

    @Test
    @DisplayName("The common package depends on no other finance.trove domain package (§1.3)")
    void commonDependsOnNoDomainPackage() {
        noClasses()
            .that().resideInAPackage("finance.trove.common..")
            .should().dependOnClassesThat(new DescribedPredicate<>("reside in a domain package") {
                @Override
                public boolean test(JavaClass candidate) {
                    String name = candidate.getPackageName();
                    return name.startsWith("finance.trove.")
                        && !name.startsWith("finance.trove.common");
                }
            })
            .because("every package may depend on common; common may depend on none of them, or "
                + "the collapse recreates the cycle it is removing (§1.3)")
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Configuration properties are records under finance.trove, scanned from one place")
    void configurationPropertiesAreRecords() {
        classes()
            .that().areAnnotatedWith("org.springframework.boot.context.properties."
                + "ConfigurationProperties")
            .should().beRecords()
            .because("a record's canonical constructor is where a bad value fails the context "
                + "refresh instead of surfacing on first use (§1.6)")
            .allowEmptyShould(true)
            .check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Only the AI gateway reaches the model client (§5 item 5, §9 risk 10)")
    void onlyTheGatewayReachesTheAiClient() {
        noClasses().that().resideOutsideOfPackages("finance.trove.ai", "finance.trove.ai.client")
            .should().dependOnClassesThat()
            .resideInAPackage("finance.trove.ai.client..")
            .because("every outbound AI call goes through AiGateway; a second path to a model "
                + "endpoint would skip the egress policy, the pinned resolver and the audit row "
                + "(§9 risk 10)")
            .check(PRODUCTION_CLASSES);
    }

    private static Path mainOutputDirectory() {
        try {
            return Paths.get(finance.trove.app.TroveApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the compiled classes directory", e);
        }
    }
}
