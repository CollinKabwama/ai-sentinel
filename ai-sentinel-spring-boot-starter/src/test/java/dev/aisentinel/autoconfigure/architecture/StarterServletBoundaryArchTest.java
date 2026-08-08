package dev.aisentinel.autoconfigure.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet types are an HTTP adapter concern. Production starter code outside
 * {@code dev.aisentinel.autoconfigure.web} must stay on framework-neutral SPIs
 * ({@code HttpRequestView} / {@code EnforcementResponse}).
 */
class StarterServletBoundaryArchTest {

    private static final String[] SERVLET_PACKAGES = {
        "jakarta.servlet..",
        "javax.servlet.."
    };

    private static JavaClasses starterProductionClasses;

    @BeforeAll
    static void importStarterClasses() {
        starterProductionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("dev.aisentinel.autoconfigure");
    }

    @Test
    void servletTypesRemainConfinedToWebPackage() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("dev.aisentinel.autoconfigure..")
            .and().resideOutsideOfPackage("dev.aisentinel.autoconfigure.web..")
            .should().dependOnClassesThat().resideInAnyPackage(SERVLET_PACKAGES)
            .because("servlet APIs belong in the web adapter package only");

        rule.check(starterProductionClasses);
    }

    @Test
    void importedStarterClassesAreNotEmpty() {
        assertThat(starterProductionClasses.size()).isGreaterThan(20);
    }

    @Test
    void webPackageMayDependOnServlet() {
        JavaClasses webOnly = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("dev.aisentinel.autoconfigure.web");
        assertThat(webOnly.size()).isGreaterThan(0);

        boolean anyServletDependency = webOnly.stream().anyMatch(javaClass ->
            javaClass.getDirectDependenciesFromSelf().stream().anyMatch(dep -> {
                String name = dep.getTargetClass().getPackageName();
                return name.startsWith("jakarta.servlet") || name.startsWith("javax.servlet");
            }));
        assertThat(anyServletDependency)
            .as("web package should exercise servlet types so the confinement rule is meaningful")
            .isTrue();
    }
}
