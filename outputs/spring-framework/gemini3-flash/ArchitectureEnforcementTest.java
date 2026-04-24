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

/**
 * ArchitectureEnforcementTest enforces the architectural boundaries of the Spring Framework.
 *
 * Layer hierarchy (bottom to top):
 * 1. BaseUtilities  : util, lang
 * 2. Core           : core, aot
 * 3. Beans          : beans
 * 4. Aop            : aop, instrument
 * 5. Context        : context, expression, stereotype, format, scripting,
 *                     jndi, ejb, contextsupport, validation (except
 *                     validation.support which is Web-MVC glue)
 * 6. DataAccess     : dao, jdbc, orm, transaction, r2dbc, oxm, jca
 * 7. Web            : web, http, ui, protobuf, validation.support
 * 8. MiscServices   : cache, scheduling, messaging, jms, mail, jmx, resilience
 *                     (cache, scheduling, messaging may be consumed by Web
 *                      and DataAccess)
 *
 * EXCLUDED PACKAGES RATIONALE
 * ---------------------------
 * * org.springframework.asm/cglib/objenesis: Repackaged third-party libraries.
 * * org.springframework.test/mock: production test-support modules (spring-test, spring-mock).
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
        .layer("Core").definedBy(
            "org.springframework.core..", "org.springframework.aot..")
        .layer("Beans").definedBy(
            "org.springframework.beans..")
        .layer("Aop").definedBy(
            "org.springframework.aop..", "org.springframework.instrument..")
        .layer("Context").definedBy(
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.format..",
            "org.springframework.scripting..", "org.springframework.jndi..",
            "org.springframework.ejb..", "org.springframework.contextsupport..",
            "org.springframework.validation..")
        .layer("DataAccess").definedBy(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..")
        .layer("Web").definedBy(
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.validation.support..")
        .layer("MiscServices").definedBy(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.jmx..",
            "org.springframework.resilience..")

        .whereLayer("MiscServices").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("MiscServices")
        .whereLayer("DataAccess").mayOnlyBeAccessedByLayers("Web", "MiscServices")
        .whereLayer("Context").mayOnlyBeAccessedByLayers("DataAccess", "Web", "MiscServices")
        .whereLayer("Aop").mayOnlyBeAccessedByLayers("Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("Beans").mayOnlyBeAccessedByLayers("Aop", "Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("Core").mayOnlyBeAccessedByLayers("Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers("Core", "Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")
        .because("Spring's canonical layering is util -> core+aot -> beans -> aop+instrument -> context -> data/web/misc.");

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
        .and().resideOutsideOfPackages(
            "org.springframework.http.converter.xml..",
            "org.springframework.web.servlet.view.xml..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("Web-layer components must reach persistence only through a service facade; XML converters are exempted.");

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
        .because("JDBC is a low-level alternative to ORM/R2DBC.");

    @ArchTest
    public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.r2dbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.oxm..")
        .allowEmptyShould(true)
        .because("R2DBC is a peer of JDBC/ORM.");

    @ArchTest
    public static final ArchRule transaction_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.transaction..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("The transaction abstraction must not leak knowledge of specific resource APIs.");

    @ArchTest
    public static final ArchRule data_access_is_agnostic_of_web_except_support = noClasses()
        .that().resideInAnyPackage("org.springframework.dao..",
                                   "org.springframework.jdbc..",
                                   "org.springframework.orm..",
                                   "org.springframework.transaction..",
                                   "org.springframework.r2dbc..",
                                   "org.springframework.oxm..",
                                   "org.springframework.jca..")
        .and().resideOutsideOfPackages(
            "org.springframework.orm.hibernate5.support..",
            "org.springframework.orm.jpa.support..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..", "org.springframework.ui..",
            "org.springframework.http..")
        .because("Only the documented Open-Session-In-View glue may reach into the Web layer.");

    // =========================================================================
    // INTRA-MISC-SERVICES ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule misc_services_leaf_independence = noClasses()
        .that().resideInAnyPackage("org.springframework.mail..", 
                                   "org.springframework.cache..", 
                                   "org.springframework.jmx..",
                                   "org.springframework.resilience..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.messaging..",
            "org.springframework.scheduling..")
        .because("Leaf Miscellaneous Services should not pull in complex messaging or scheduling modules.");
}


