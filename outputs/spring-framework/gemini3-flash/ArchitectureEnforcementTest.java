package org.springframework;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchitectureEnforcementTest enforces the architectural boundaries of the Spring Framework.
 *
 * DOCUMENTED LAYER HIERARCHY (Top to Bottom)
 * --------------------------
 * 1. Web Layer / Misc Services: The entry points and specialized integration services.
 * 2. Data Access: Persistence and transaction management.
 * 3. AOP & Instrumentation: Cross-cutting concerns and bytecode manipulation.
 * 4. Core Container: The IoC foundation (context, beans, expression).
 * 5. Base Utilities: Common low-level utilities (util, lang).
 *
 * EXCLUDED PACKAGES RATIONALE
 * ---------------------------
 * * org.springframework.asm/cglib/objenesis: Repackaged third-party libraries.
 * * org.springframework.test/mock: production test-support modules (spring-test, spring-mock).
 *   Note: ImportOption.DoNotIncludeTests removes src/test/java output, while these 
 *   exclusions remove production test-infrastructure modules.
 */
@AnalyzeClasses(packages = "org.springframework", importOptions = {
    ImportOption.DoNotIncludeTests.class,
    ArchitectureEnforcementTest.ExcludeRepackagedAndTestPackages.class
})
public class ArchitectureEnforcementTest {

    public static class ExcludeRepackagedAndTestPackages implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("org/springframework/asm")
                && !location.contains("org/springframework/cglib")
                && !location.contains("org/springframework/objenesis")
                && !location.contains("org/springframework/javapoet")
                && !location.contains("org/springframework/test")
                && !location.contains("org/springframework/tests")
                && !location.contains("org/springframework/mock")
                && !location.contains("org/springframework/build")
                && !location.contains("org/springframework/docs");
        }
    }

    // =========================================================================
    // LAYERED ARCHITECTURE
    // =========================================================================

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("BaseUtilities").definedBy(
            "org.springframework.util..", "org.springframework.lang..")
        .layer("CoreContainer").definedBy(
            "org.springframework.core..", "org.springframework.beans..",
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.validation..",
            "org.springframework.format..", "org.springframework.scripting..",
            "org.springframework.ejb..", "org.springframework.jndi..",
            "org.springframework.contextsupport..")
        .layer("AopInstrumentation").definedBy(
            "org.springframework.aop..", "org.springframework.aot..",
            "org.springframework.instrument..")
        .layer("DataAccess").definedBy(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..")
        .layer("Web").definedBy(
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..")
        .layer("MiscServices").definedBy(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.jmx..",
            "org.springframework.resilience..")

        .whereLayer("MiscServices").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("MiscServices")
        .whereLayer("DataAccess").mayOnlyBeAccessedByLayers("Web", "MiscServices")
        .whereLayer("AopInstrumentation").mayOnlyBeAccessedByLayers(
            "DataAccess", "Web", "MiscServices")
        .whereLayer("CoreContainer").mayOnlyBeAccessedByLayers(
            "AopInstrumentation", "DataAccess", "Web", "MiscServices")
        .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers(
            "CoreContainer", "AopInstrumentation", "DataAccess", "Web", "MiscServices")
        .because("Spring Framework enforces a strict downward dependency flow.");

    @ArchTest
    public static final ArchRule no_unmapped_spring_packages = classes()
        .that().resideInAPackage("org.springframework..")
        .and().resideOutsideOfPackages(
            "org.springframework.asm..", "org.springframework.cglib..",
            "org.springframework.objenesis..", "org.springframework.javapoet..",
            "org.springframework.test..", "org.springframework.tests..",
            "org.springframework.mock..", "org.springframework.build..",
            "org.springframework.docs..")
        .should().resideInAnyPackage(
            "org.springframework.util..", "org.springframework.lang..",
            "org.springframework.core..", "org.springframework.beans..",
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.validation..",
            "org.springframework.format..", "org.springframework.scripting..",
            "org.springframework.ejb..", "org.springframework.jndi..",
            "org.springframework.contextsupport..",
            "org.springframework.aop..", "org.springframework.aot..",
            "org.springframework.instrument..",
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..",
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.jmx..",
            "org.springframework.resilience..")
        .because("Every production Spring package must be mapped to a layer.");

    // =========================================================================
    // PARALLEL-LAYER ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule web_and_dataaccess_are_isolated = noClasses()
        .that().resideInAnyPackage("org.springframework.web..",
                                   "org.springframework.http..",
                                   "org.springframework.ui..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("Web-layer components must reach persistence only through a service/MiscServices facade.");

    // =========================================================================
    // INTRA-CORE-CONTAINER ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule core_is_standalone = noClasses()
        .that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..",
            "org.springframework.expression..")
        .because("spring-core is the foundation of the Core Container.");

    @ArchTest
    public static final ArchRule beans_is_below_context = noClasses()
        .that().resideInAPackage("org.springframework.beans..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.context..", "org.springframework.expression..")
        .because("spring-beans must not know about spring-context or spring-expression.");

    @ArchTest
    public static final ArchRule expression_is_leaf_within_core_container = noClasses()
        .that().resideInAPackage("org.springframework.expression..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..")
        .because("SpEL depends on spring-core only.");

    // =========================================================================
    // INTRA-DATA-ACCESS ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule jdbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.jdbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("JDBC is a low-level alternative to ORM/R2DBC; it must stay independent.");

    @ArchTest
    public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.r2dbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.oxm..")
        .because("R2DBC is a peer of JDBC/ORM, not a client of them.");

    @ArchTest
    public static final ArchRule transaction_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.transaction..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("The transaction abstraction must not leak knowledge of specific resource APIs.");

    // =========================================================================
    // INTRA-MISC-SERVICES ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule misc_services_do_not_depend_on_each_other = slices()
        .matching("org.springframework.(jms|mail|messaging|scheduling|cache|jmx|resilience)..")
        .should().notDependOnEachOther()
        .because("Each Miscellaneous Service must be independently pluggable.");
}

