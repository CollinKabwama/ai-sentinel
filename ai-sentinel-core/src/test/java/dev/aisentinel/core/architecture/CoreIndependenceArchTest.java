package dev.aisentinel.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the framework independence of {@code ai-sentinel-core}: the decision core must compile and run without a
 * servlet container, Spring, or a reactive runtime. Integration-specific types belong in the Spring Boot starter.
 */
class CoreIndependenceArchTest {

    /**
     * Broad package trees (not brittle leaf packages). {@code reactor..} covers {@code reactor.core},
     * {@code reactor.netty}, {@code reactor.util}, etc. {@code org.springframework..} covers WebFlux and Security.
     */
    private static final String[] FORBIDDEN_PACKAGES = {
        "org.springframework..",
        "jakarta.servlet..",
        "javax.servlet..",
        "reactor.."
    };

    private static JavaClasses coreProductionClasses;

    @BeforeAll
    static void importCoreClasses() {
        coreProductionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("dev.aisentinel");
    }

    @Test
    void coreDoesNotDependOnServletSpringOrReactiveTypes() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("dev.aisentinel..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_PACKAGES)
            .because("ai-sentinel-core is the framework-independent decision core; servlet, Spring, and reactive "
                + "adapters live outside this module");

        rule.check(coreProductionClasses);
    }

    @Test
    void importedCoreClassesAreNotEmpty() {
        // A silently empty import would make the rule above vacuously true.
        assertThat(coreProductionClasses.size()).isGreaterThan(50);
    }

    @Test
    void ruleDetectsAForbiddenPackageThatCoreActuallyUses() {
        // Sanity check on the rule mechanism itself: SLF4J is a real core dependency, so the same rule shape must
        // report a violation for it. If this passes silently, the rule above proves nothing.
        EvaluationResult result = noClasses()
            .that().resideInAPackage("dev.aisentinel..")
            .should().dependOnClassesThat().resideInAnyPackage("org.slf4j..")
            .evaluate(coreProductionClasses);

        assertThat(result.hasViolation()).isTrue();
    }
}
