/**
 * ArchitectureEnforcementTest
 *
 * Enforces the documented Spring Framework layer hierarchy using ArchUnit rules.
 *
 * Layer Hierarchy (bottom to top):
 *   1. CoreContainer  - Foundation IoC/DI layer. Provides dependency injection, bean management,
 *                       application context, expression language, and shared utilities.
 *                       Packages: core, beans, context, expression, lang, util, stereotype
 *   2. AOP            - Aspect-Oriented Programming and Ahead-of-Time compilation support.
 *                       Sits directly above CoreContainer. Packages: aop, aot
 *   3. DataAccess     - Database and integration services layer (parallel to Web).
 *                       Provides JDBC, ORM, OXM, transactions, messaging, scheduling, caching.
 *                       Packages: dao, jdbc, orm, oxm, transaction, r2dbc, jms, jca, jndi,
 *                                 mail, messaging, scheduling, cache, format
 *   4. Web            - Web layer (parallel to DataAccess, must not cross-depend).
 *                       Provides HTTP, MVC, WebFlux, WebSocket, REST support.
 *                       Packages: web, http, ui, validation
 *   5. Miscellaneous  - Top-level extension and integration layer. Integrates security,
 *                       batch, scripting, JMX, EJB, and resilience capabilities.
 *                       Packages: scripting, jmx, ejb, instrument, resilience
 *
 * Cross-Cutting Support:
 *   - Test packages (test, tests, mock) are excluded from layered architecture rules
 *     because they are support infrastructure, not production layer participants.
 *     A dedicated rule verifies that test code does not leak into production layers.
 *
 * Excluded Packages and Rationale:
 *   - org.springframework.asm       : Repackaged ASM bytecode manipulation library.
 *                                     Not Spring-authored; internal tooling dependency only.
 *   - org.springframework.cglib     : Repackaged CGLIB proxy library.
 *                                     Not Spring-authored; used internally by spring-core.
 *   - org.springframework.objenesis : Repackaged Objenesis instantiation library.
 *                                     Not Spring-authored; used internally by spring-core.
 *   - org.springframework.javapoet  : Repackaged JavaPoet code-generation library.
 *                                     Build-time AOT processor only; not a runtime API.
 *   - org.springframework.protobuf  : Repackaged Protobuf descriptor classes.
 *                                     Not Spring-authored; codec support shim only.
 *   - org.springframework.build     : Internal Gradle/build-system utilities.
 *                                     Not part of any deployable module; build-time only.
 *   - org.springframework.docs      : Documentation code samples and Asciidoc snippets.
 *                                     Not production code; excluded to prevent false positives.
 */
package com.archunit.spring;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
    packages = "org.springframework",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ArchitectureEnforcementTest.ExcludeRepackagedAndBuildOnlyPackages.class
    }
)
public class ArchitectureEnforcementTest {

    // =========================================================================
    // Custom ImportOption: Strip repackaged third-party and build-only classes
    // =========================================================================

    /**
     * Excludes repackaged third-party libraries and build-only utilities from
     * class scanning. These packages contain non-Spring-authored code or
     * compile-time tooling that must not be subject to architectural rules.
     */
    public static final class ExcludeRepackagedAndBuildOnlyPackages implements ImportOption {

        private static final Pattern EXCLUDED_PATH_PATTERN = Pattern.compile(
            ".*/springframework/(asm|cglib|objenesis|javapoet|protobuf|build|docs)[/$].*"
        );

        @Override
        public boolean includes(Location location) {
            return !EXCLUDED_PATH_PATTERN.matcher(location.asURI().toString()).find();
        }
    }

    // =========================================================================
    // Layered Architecture Rules
    // =========================================================================

    @ArchTest
    static final ArchRule spring_layered_architecture_is_respected =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("CoreContainer").definedBy(
                "org.springframework.core..",
                "org.springframework.beans..",
                "org.springframework.context..",
                "org.springframework.expression..",
                "org.springframework.lang..",
                "org.springframework.util..",
                "org.springframework.stereotype.."
            )
            .layer("AOP").definedBy(
                "org.springframework.aop..",
                "org.springframework.aot.."
            )
            .layer("DataAccess").definedBy(
                "org.springframework.dao..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.oxm..",
                "org.springframework.transaction..",
                "org.springframework.r2dbc..",
                "org.springframework.jms..",
                "org.springframework.jca..",
                "org.springframework.jndi..",
                "org.springframework.mail..",
                "org.springframework.messaging..",
                "org.springframework.scheduling..",
                "org.springframework.cache..",
                "org.springframework.format.."
            )
            .layer("Web").definedBy(
                "org.springframework.web..",
                "org.springframework.http..",
                "org.springframework.ui..",
                "org.springframework.validation.."
            )
            .layer("Miscellaneous").definedBy(
                "org.springframework.scripting..",
                "org.springframework.jmx..",
                "org.springframework.ejb..",
                "org.springframework.instrument..",
                "org.springframework.resilience.."
            )
            .whereLayer("CoreContainer").mayNotAccessAnyLayer()
            .whereLayer("AOP").mayOnlyAccessLayers("CoreContainer")
            .whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "AOP")
            .whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "AOP")
            .whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP", "DataAccess", "Web")
            .because("The documented Spring Framework layer hierarchy requires CoreContainer at the foundation, AOP directly above it, DataAccess and Web as parallel peer layers above AOP, and Miscellaneous as the top-level extension layer. Lower layers must never depend on higher layers.");

    // =========================================================================
    // Fine-grained Intra-layer Rules: Parallel Layer Isolation
    // =========================================================================

    @ArchTest
    static final ArchRule data_access_layer_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.dao..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.oxm..",
                "org.springframework.transaction..",
                "org.springframework.r2dbc..",
                "org.springframework.jms..",
                "org.springframework.jca..",
                "org.springframework.jndi..",
                "org.springframework.mail..",
                "org.springframework.messaging..",
                "org.springframework.scheduling..",
                "org.springframework.cache..",
                "org.springframework.format.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http..",
                "org.springframework.ui..",
                "org.springframework.validation.."
            )
            .because("DataAccess and Web are parallel peer layers in the Spring architecture diagram. DataAccess modules must not introduce compile-time dependencies on Web layer classes to prevent tight coupling and circular cross-layer dependencies.");

    @ArchTest
    static final ArchRule web_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http..",
                "org.springframework.ui..",
                "org.springframework.validation.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.dao..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.oxm..",
                "org.springframework.transaction..",
                "org.springframework.r2dbc..",
                "org.springframework.jms..",
                "org.springframework.jca..",
                "org.springframework.jndi..",
                "org.springframework.mail..",
                "org.springframework.messaging..",
                "org.springframework.scheduling..",
                "org.springframework.cache..",
                "org.springframework.format.."
            )
            .because("DataAccess and Web are parallel peer layers in the Spring architecture diagram. Web layer modules must not import DataAccess layer classes directly; data binding must flow through shared CoreContainer abstractions.");

    // =========================================================================
    // Fine-grained Intra-layer Rules: Module-to-Module Isolation in DataAccess
    // =========================================================================

    @ArchTest
    static final ArchRule spring_jdbc_must_not_depend_on_spring_orm =
        noClasses()
            .that().resideInAPackage("org.springframework.jdbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
            .because("spring-jdbc provides foundational JDBC abstraction and must not depend on spring-orm's higher-level ORM integration, which itself builds on top of spring-jdbc. A dependency in this direction would introduce a circular module dependency.");

    @ArchTest
    static final ArchRule spring_orm_must_not_depend_on_spring_jdbc_directly =
        noClasses()
            .that().resideInAPackage("org.springframework.orm..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-orm provides ORM tool integration (Hibernate, JPA) and must interact with the database layer only through spring-tx transaction abstractions and spring-dao exception hierarchy, not through spring-jdbc APIs directly.");

    @ArchTest
    static final ArchRule spring_messaging_must_not_depend_on_spring_web =
        noClasses()
            .that().resideInAPackage("org.springframework.messaging..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .because("spring-messaging provides a generic messaging abstraction (STOMP, WebSocket message handling) that must remain decoupled from the Web layer. HTTP-specific messaging concerns belong in spring-webmvc or spring-webflux, not in spring-messaging itself.");

    @ArchTest
    static final ArchRule spring_jms_must_not_depend_on_spring_jdbc =
        noClasses()
            .that().resideInAPackage("org.springframework.jms..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-jms provides JMS messaging integration and must not depend on spring-jdbc database abstractions. Both are independent integration modules within the DataAccess layer and must remain isolated from each other.");

    @ArchTest
    static final ArchRule spring_r2dbc_must_not_depend_on_spring_jdbc =
        noClasses()
            .that().resideInAPackage("org.springframework.r2dbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-r2dbc provides reactive database access and must not depend on the blocking spring-jdbc API. Mixing reactive and blocking database APIs within a single dependency chain defeats the purpose of the reactive pipeline and creates hidden thread-blocking risks.");

    // =========================================================================
    // Fine-grained Intra-layer Rules: Module-to-Module Isolation in Web
    // =========================================================================

    @ArchTest
    static final ArchRule spring_webmvc_must_not_depend_on_spring_webflux =
        noClasses()
            .that().resideInAPackage("org.springframework.web.servlet..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.reactive..")
            .because("spring-webmvc is the blocking Servlet-based web framework and must not depend on the reactive spring-webflux model. Mixing these execution models in the same dependency chain would cause incompatible threading and lifecycle assumptions.");

    @ArchTest
    static final ArchRule spring_webflux_must_not_depend_on_spring_webmvc =
        noClasses()
            .that().resideInAPackage("org.springframework.web.reactive..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.servlet..")
            .because("spring-webflux is the non-blocking reactive web framework and must not depend on the Servlet-based spring-webmvc model. Coupling the reactive stack to Servlet APIs breaks portability on non-Servlet runtimes such as Netty.");

    // =========================================================================
    // Fine-grained Intra-layer Rules: CoreContainer Module Isolation
    // =========================================================================

    @ArchTest
    static final ArchRule spring_beans_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.beans..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-beans provides the foundational BeanFactory API and must not depend on the higher-level spring-context ApplicationContext. spring-context extends spring-beans, so an upward dependency from beans to context would create a circular module dependency.");

    @ArchTest
    static final ArchRule spring_core_must_not_depend_on_spring_beans =
        noClasses()
            .that().resideInAPackage("org.springframework.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.beans..")
            .because("spring-core provides the lowest-level utilities (type conversion, resource loading, env abstraction) and must not depend on spring-beans. spring-beans itself depends on spring-core, so any upward dependency would introduce a circular module dependency.");

    @ArchTest
    static final ArchRule spring_expression_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.expression..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-expression (SpEL) provides a standalone expression evaluation engine and must not depend on spring-context. SpEL is consumed by spring-context for annotation processing; a reverse dependency would create a circular module relationship.");

    // =========================================================================
    // Fine-grained Intra-layer Rules: AOP Layer Isolation
    // =========================================================================

    @ArchTest
    static final ArchRule spring_aop_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAPackage("org.springframework.aop..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.dao..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.transaction.."
            )
            .because("spring-aop provides generic proxy and aspect mechanisms that must remain decoupled from DataAccess concerns. Transaction management through AOP is wired by spring-tx and spring-context, not by spring-aop itself, to preserve clean layering.");

    @ArchTest
    static final ArchRule spring_aop_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAPackage("org.springframework.aop..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .because("spring-aop provides generic aspect infrastructure and must not contain dependencies on the Web layer. Web-specific AOP concerns (e.g., request-scoped proxies) are wired externally by the Web and Context layers, not by spring-aop itself.");

    // =========================================================================
    // Cross-cutting Rules: Test Code Containment
    // =========================================================================

    @ArchTest
    static final ArchRule production_layers_must_not_depend_on_test_packages =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.core..",
                "org.springframework.beans..",
                "org.springframework.context..",
                "org.springframework.expression..",
                "org.springframework.aop..",
                "org.springframework.aot..",
                "org.springframework.dao..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.transaction..",
                "org.springframework.web..",
                "org.springframework.http..",
                "org.springframework.messaging..",
                "org.springframework.scheduling..",
                "org.springframework.cache.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.test..",
                "org.springframework.tests..",
                "org.springframework.mock.."
            )
            .because("Production layer classes must never depend on test infrastructure packages. Test utilities are compile-time-only support code; any production dependency on them would make the framework undeployable without test-scope libraries on the runtime classpath.");
}
