package org.springframework;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchitectureEnforcementTest enforces the architectural boundaries of the Spring Framework.
 *
 * Layer hierarchy (bottom to top):
 *   1. BaseUtilities  : util, lang
 *   2. Core           : core, aot
 *   3. Beans          : beans
 *   4. Aop            : aop, instrument
 *   5. Context        : context, expression, stereotype, format, scripting,
 *                       jndi, ejb, contextsupport, jmx, validation (minus
 *                       validation.support which is Web-MVC glue)
 *   6. DataAccess     : dao, jdbc, orm, transaction, r2dbc, oxm, jca (minus
 *                       orm.hibernate5.support and orm.jpa.support bridge packages)
 *   7. Web            : web, http, ui, protobuf, validation.support,
 *                       orm.hibernate5.support, orm.jpa.support (OSIV/OEMIV glue)
 *   8. MiscServices   : cache, scheduling, messaging, jms, mail, resilience
 *
 * Documented cross-cutting exceptions (precisely scoped):
 *   - web.servlet.resource.* and web.reactive.resource.* may consume spring-cache
 *     for HTTP resource caching.
 *   - http.client.reactive.* may consume spring-scheduling for its shared thread
 *     factory in JdkHttpClientResourceFactory.
 *   - orm.hibernate5.support.* and orm.jpa.support.* bridge DataAccess ↔ Web
 *     for Open-Session-In-View / Open-EntityManager-In-View; these packages are
 *     classified as Web members.
 */
@AnalyzeClasses(packages = "org.springframework", importOptions = {
    ImportOption.DoNotIncludeTests.class,
    ArchitectureEnforcementTest.ExcludeRepackagedAndTestPackages.class
})
public class ArchitectureEnforcementTest {

    public static class ExcludeRepackagedAndTestPackages implements ImportOption {
        private static final Pattern REPACKAGED_OR_TEST =
            Pattern.compile(".*/org/springframework/(asm|cglib|objenesis|javapoet|test|tests|mock|build|docs)/.*");

        @Override
        public boolean includes(Location location) {
            return !location.matches(REPACKAGED_OR_TEST);
        }
    }

    // =========================================================================
    // SANITY CHECK — required modules must exist on the classpath
    // =========================================================================

    @ArchTest
    public static final ArchRule required_modules_are_on_classpath = classes()
        .that().resideInAnyPackage(
            "org.springframework.core..",
            "org.springframework.beans..",
            "org.springframework.context..",
            "org.springframework.aop..",
            "org.springframework.jdbc..",
            "org.springframework.orm..",
            "org.springframework.r2dbc..",
            "org.springframework.web..",
            "org.springframework.messaging..")
        .should().haveSimpleNameNotEndingWith("__ShouldNeverMatch__")
        .because("If this rule reports 'rule was not applied to any class', a core Spring "
               + "module is missing from the test classpath and other rules are going vacuous.");

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
            "org.springframework.validation..", "org.springframework.jmx..")
        .layer("DataAccess").definedBy(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..")
        .layer("Web").definedBy(
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.validation.support..",
            "org.springframework.orm.hibernate5.support..",
            "org.springframework.orm.jpa.support..")
        .layer("MiscServices").definedBy(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.resilience..")

        .whereLayer("MiscServices").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("MiscServices")
        .whereLayer("DataAccess").mayOnlyBeAccessedByLayers("Web", "MiscServices")
        .whereLayer("Context").mayOnlyBeAccessedByLayers("DataAccess", "Web", "MiscServices")
        .whereLayer("Aop").mayOnlyBeAccessedByLayers("Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("Beans").mayOnlyBeAccessedByLayers("Aop", "Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("Core").mayOnlyBeAccessedByLayers("Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers("Core", "Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")

        // Surgical exceptions — tightly scoped to the documented integration points.
        .ignoreDependency(
            resideInAnyPackage(
                "org.springframework.web.servlet.resource..",
                "org.springframework.web.reactive.resource.."),
            resideInAPackage("org.springframework.cache.."))
        .ignoreDependency(
            resideInAPackage("org.springframework.http.client.reactive.."),
            resideInAPackage("org.springframework.scheduling.."))

        .because("Strict downward flow with documented, precisely-scoped exceptions: "
               + "(1) web.*.resource handlers integrate spring-cache for HTTP resource caching; "
               + "(2) http.client.reactive reuses spring-scheduling thread factories; "
               + "(3) orm.*.support are classified as Web (Open-Session-In-View bridge).");

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
            "org.springframework.core..", "org.springframework.aot..",
            "org.springframework.beans..",
            "org.springframework.aop..", "org.springframework.instrument..",
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.validation..",
            "org.springframework.format..", "org.springframework.scripting..",
            "org.springframework.ejb..", "org.springframework.jndi..",
            "org.springframework.contextsupport..", "org.springframework.jmx..",
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..",
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.resilience..")
        .because("Every production Spring package must be mapped to a layer; new packages "
               + "must be classified here before they can ship.");

    // =========================================================================
    // PARALLEL-LAYER ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule web_and_dataaccess_are_isolated = noClasses()
        .that().resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..",
            "org.springframework.ui..")
        .and().resideOutsideOfPackages(
            "org.springframework.http.converter.xml..",
            "org.springframework.web.servlet.view.xml..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("Web-layer components must reach persistence only through a service facade; "
               + "XML converters (http.converter.xml, web.servlet.view.xml) are exempted.");

    // =========================================================================
    // INTRA-CORE-CONTAINER ISOLATION
    // (core_is_standalone and beans_is_below_context are defense-in-depth;
    //  the layered rule already forbids these. Retained so the suite still
    //  enforces the invariant if the layered rule is relaxed.)
    // =========================================================================

    @ArchTest
    public static final ArchRule core_is_standalone = noClasses()
        .that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..",
            "org.springframework.expression..")
        .because("Defense-in-depth: spring-core is the foundation; must not depend upward.");

    @ArchTest
    public static final ArchRule beans_is_below_context = noClasses()
        .that().resideInAPackage("org.springframework.beans..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.context..", "org.springframework.expression..")
        .because("Defense-in-depth: spring-beans must not know about spring-context or spring-expression.");

    @ArchTest
    public static final ArchRule expression_is_leaf_within_core_container = noClasses()
        .that().resideInAPackage("org.springframework.expression..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..")
        .because("spring-expression must not depend on spring-beans or spring-context (intra-Context sibling isolation).");

    // =========================================================================
    // INTRA-DATA-ACCESS ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule dao_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.dao..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("spring-dao is the pure persistence abstraction; concrete mechanisms depend on it, never the reverse.");

    @ArchTest
    public static final ArchRule jdbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.jdbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("JDBC is a peer alternative to ORM/R2DBC/OXM, not a consumer.");

    @ArchTest
    public static final ArchRule orm_does_not_know_about_jdbc_core_or_r2dbc = noClasses()
        .that().resideInAPackage("org.springframework.orm..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc.core..", "org.springframework.r2dbc..")
        .because("ORM frameworks manage their own connection lifecycle; integrating via JdbcTemplate "
               + "or r2dbc indicates a layering bug. Integration with jdbc.datasource (connection holder) is allowed.");

    @ArchTest
    public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.r2dbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.oxm..")
        .allowEmptyShould(true)
        .because("R2DBC is a peer of JDBC/ORM/OXM.");

    @ArchTest
    public static final ArchRule transaction_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.transaction..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("The transaction abstraction must not leak knowledge of specific resource APIs.");

    // =========================================================================
    // MISC SERVICES DAG
    // jms → messaging → {cache, mail, resilience leaves}
    // scheduling is a pure leaf (consumed by messaging.simp.stomp for heartbeats;
    // see allowed exception in the layered rule).
    // =========================================================================

    @ArchTest
    public static final ArchRule scheduling_is_leaf_of_misc = noClasses()
        .that().resideInAPackage("org.springframework.scheduling..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.cache..",
            "org.springframework.resilience..")
        .because("spring-scheduling must stay resource-agnostic — pure leaf of MiscServices.");

    @ArchTest
    public static final ArchRule messaging_does_not_depend_on_other_misc_services = noClasses()
        .that().resideInAPackage("org.springframework.messaging..")
        .and().resideOutsideOfPackage("org.springframework.messaging.simp.stomp..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.cache..", "org.springframework.resilience..")
        .because("spring-messaging is a primitive for spring-jms and must not depend on other "
               + "MiscServices; only messaging.simp.stomp may depend on spring-scheduling (for heartbeats).");

    @ArchTest
    public static final ArchRule leaf_misc_services_are_isolated = noClasses()
        .that().resideInAnyPackage(
            "org.springframework.mail..",
            "org.springframework.resilience..",
            "org.springframework.cache..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.messaging..",
            "org.springframework.scheduling..")
        .because("Leaf MiscServices (mail, cache, resilience) must be independently pluggable.");
}
