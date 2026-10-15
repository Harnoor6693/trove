package finance.trove.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finding F3: the pre-collapse detector did every amount comparison, its coefficient of variation
 * and its confidence score in {@code double}, including {@code Math.pow} and {@code Math.sqrt}
 * over {@code BigDecimal::doubleValue}.
 *
 * <p>{@code ArchitectureTest} already bans binary floating point on entities and in the
 * {@code domain} and {@code events} packages (§9 risk 6). This narrows the same rule onto THIS
 * package's whole surface — fields, returns and parameters alike — because the arithmetic that
 * was ported here is the arithmetic the finding is about, and a helper that takes a
 * {@code double} parameter is how a ledger figure would get back in without touching an entity.
 *
 * <p>It scans the compiled classes rather than the source, so it also sees a {@code double} that
 * arrived through a lambda or a bridge method.
 */
class SubscriptionMoneyIsNeverADoubleTest {

    private static final Set<String> BANNED =
        Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    private static final List<JavaClass> SUBSCRIPTIONS = subscriptionClasses();

    private static List<JavaClass> subscriptionClasses() {
        JavaClasses all = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPath(mainOutputDirectory());
        List<JavaClass> mine = new ArrayList<>();
        for (JavaClass type : all) {
            if (type.getPackageName().startsWith("finance.trove.subscriptions")) {
                mine.add(type);
            }
        }
        return mine;
    }

    @Test
    @DisplayName("The scan actually sees this package, so the rules below cannot pass by vacuum")
    void theScanIsNotEmpty() {
        assertThat(SUBSCRIPTIONS)
            .as("a rule over an empty class set asserts nothing")
            .hasSizeGreaterThan(15);
    }

    @Test
    @DisplayName("No field, return type or parameter in finance.trove.subscriptions is a double")
    void noBinaryFloatingPointAnywhereInTheSubscriptionsPackage() {
        List<String> offences = new ArrayList<>();

        for (JavaClass type : SUBSCRIPTIONS) {
            for (JavaField field : type.getFields()) {
                if (BANNED.contains(field.getRawType().getName())) {
                    offences.add(field.getFullName() + " (field)");
                }
            }
            for (JavaMethod method : type.getMethods()) {
                if (BANNED.contains(method.getRawReturnType().getName())) {
                    offences.add(method.getFullName() + " (return type)");
                }
                method.getRawParameterTypes().stream()
                    .filter(parameter -> BANNED.contains(parameter.getName()))
                    .findFirst()
                    .ifPresent(parameter ->
                        offences.add(method.getFullName() + " (parameter)"));
            }
        }

        assertThat(offences)
            .as("IEEE-754 cannot represent 0.1, and finding F3 is that this detector's whole "
                + "arithmetic was written in it. Every amount here is BigDecimal or Money")
            .isEmpty();
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
