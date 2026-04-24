/**
 * ArchitectureEnforcementTest
 *
 * Enforces the documented Spring Framework layer hierarchy using ArchUnit rules.
 * Updated after Review #1 (reviewer: opus-4-7) to resolve all CRITICAL/HIGH/MEDIUM findings.
 *
 * Layer Hierarchy (bottom to top):
 *   1. CoreContainer  - Foundation IoC/DI layer. Provides dependency injection, bean management,
 *                       application context, expression language, shared utilities, AOT runtime
 *                       hints, load-time weaving SPI, and cross-cutting formatting/validation SPIs.
 *                       Packages: core, beans, context, contextsupport, expression, lang, util,
 *                                 stereotype, aot, instrument, format, validation, ui, scheduling
 *   2. AOP            - Aspect-Oriented Programming layer only (AOT is NOT AOP; F3 fix).
 *                       Packages: aop
 *   3. DataAccess     - Database and integration services layer. Parallel peer with Web, Messaging,
 *                       and Miscellaneous. Must not depend on any peer layer.
 *                       Packages: dao, jdbc, orm, oxm, transaction, r2dbc, jms, jca, jndi,
 *                                 mail, cache
 *   4. Web            - Web layer. Parallel peer with DataAccess, Messaging, and Miscellaneous.
 *                       Packages: web, http
 *   5. Messaging      - Messaging abstraction layer. Parallel peer with DataAccess, Web, Miscellaneous.
 *                       Packages: messaging
 *   6. Miscellaneous  - Parallel peer extension layer (NOT above DataAccess/Web; F1 fix).
 *                       Packages: scripting, jmx, ejb, resilience
 *
 * Key corrections applied versus initial generation (Review #1):
 *   - aot moved from AOP to CoreContainer: AOT != AOP; aot is consumed by beans/context (F3)
 *   - instrument moved from Miscellaneous to CoreContainer: LTW SPI used by context.weaving (F7)
 *   - format, validation, ui, scheduling moved to CoreContainer: cross-cutting SPIs (F4/F5)
 *   - contextsupport added to CoreContainer: glob context.. does not cover sibling (F11)
 *   - messaging promoted to its own peer layer: not a DataAccess concern (F6)
 *   - Miscellaneous is now a true parallel peer, not above DataAccess/Web (F1)
 *   - R5 (spring_orm_must_not_depend_on_spring_jdbc_directly) deleted: fabricated constraint (F2)
 *   - R2/R3 narrowed to true DA/Web peers, removing mis-classified cross-cutting packages (F4/F5)
 *   - Added misc_layer_must_not_depend_on_data_access_layer and misc_layer_must_not_depend_on_web_layer (F1)
 *   - Added aot_must_not_depend_on_aop: defensive AOT/AOP decoupling rule (F3)
 *   - Added spring_core_must_not_depend_on_spring_context/expression, spring_beans_must_not_depend_on_spring_expression (F8)
 *   - ImportOption replaced regex with location.contains() calls for URI-scheme safety (F9)
 *   - R16 replaced with negative-selector covering all org.springframework production packages (F10)
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
     * Excludes repackaged third-party libraries and build-only utilities from class scanning.
     * Uses location.contains() rather than a regex for safety across file: and jar: URI schemes,
     * and to avoid the misleading [/$] character class in the prior implementation (F9 fix).
     */
    public static final class ExcludeRepackagedAndBuildOnlyPackages implements ImportOption {

        @Override
        public boolean includes(Location location) {
            return !location.contains("/org/springframework/asm/")
                && !location.contains("/org/springframework/cglib/")
                && !location.contains("/org/springframework/objenesis/")
                && !location.contains("/org/springframework/javapoet/")
                && !location.contains("/org/springframework/protobuf/")
                && !location.contains("/org/springframework/build/")
                && !location.contains("/org/springframework/docs/");
        }
    }

    // =========================================================================
    // Layered Architecture Rules
    // =========================================================================

    @ArchTest
    static final ArchRule spring_layered_architecture_is_respected =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            // CoreContainer: lowest foundation layer (F1, F3, F4, F7, F11 fixes applied)
            .layer("CoreContainer").definedBy(
                "org.springframework.core..",
                "org.springframework.beans..",
                "org.springframework.context..",
                "org.springframework.contextsupport..",  // F11: sibling, not sub-package of context
                "org.springframework.expression..",
                "org.springframework.lang..",
                "org.springframework.util..",
                "org.springframework.stereotype..",
                "org.springframework.aot..",             // F3: AOT is CoreContainer, not AOP
                "org.springframework.instrument..",      // F7: LTW SPI used by context.weaving
                "org.springframework.format..",          // F4: cross-cutting formatter SPI
                "org.springframework.validation..",      // F4: cross-cutting validator SPI
                "org.springframework.ui..",              // F4: cross-cutting view-model SPI
                "org.springframework.scheduling.."      // F4: cross-cutting scheduling SPI
            )
            // AOP: Aspect-Oriented Programming only; aot removed (F3)
            .layer("AOP").definedBy(
                "org.springframework.aop.."
            )
            // DataAccess: parallel peer; messaging/scheduling/format/validation removed (F4/F6)
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
                "org.springframework.cache.."
            )
            // Web: parallel peer; ui/validation moved to CoreContainer (F5)
            .layer("Web").definedBy(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            // Messaging: promoted to own parallel peer layer (F6)
            .layer("Messaging").definedBy(
                "org.springframework.messaging.."
            )
            // Miscellaneous: parallel peer, NOT above DataAccess/Web (F1); instrument removed (F7)
            .layer("Miscellaneous").definedBy(
                "org.springframework.scripting..",
                "org.springframework.jmx..",
                "org.springframework.ejb..",
                "org.springframework.resilience.."
            )
            .whereLayer("CoreContainer").mayNotAccessAnyLayer()
            .whereLayer("AOP").mayOnlyAccessLayers("CoreContainer")
            .whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "AOP")
            .whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "AOP")
            .whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer", "AOP")
            // F1 fix: Miscellaneous is a peer, not above DataAccess/Web
            .whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP")
            .because("Per the Spring Framework architecture diagram, DataAccess, Web, Messaging, and Miscellaneous are parallel top-row peer layers. None of the peer layers may depend on another peer layer. CoreContainer forms the foundation; AOP sits directly above it.");

    // =========================================================================
    // Fine-grained Rules: Parallel Peer Isolation (DataAccess / Web)
    // =========================================================================

    // F4 fix: removed messaging, scheduling, format, oxm from DA side; removed validation, ui from Web side
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
                "org.springframework.cache.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .because("DataAccess and Web are parallel top-row peer layers per the documented Spring Framework architecture diagram. Cross-cutting SPIs (format, validation, scheduling) are foundational and shared via CoreContainer, not via the Web layer.");

    // F5 fix: Web side narrowed to web/http; DA side excludes oxm, cache (cross-cutting), messaging, scheduling
    @ArchTest
    static final ArchRule web_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.dao..",
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.transaction..",
                "org.springframework.r2dbc..",
                "org.springframework.jms..",
                "org.springframework.jca..",
                "org.springframework.jndi..",
                "org.springframework.mail.."
            )
            .because("DataAccess and Web are parallel top-row peer layers per the documented Spring Framework architecture diagram. Cross-cutting concerns (oxm, format, validation, cache) are foundational and intentionally usable by Web via CoreContainer.");

    // =========================================================================
    // Fine-grained Rules: Parallel Peer Isolation (Miscellaneous)
    // =========================================================================

    // F1 fix: explicit peer-isolation rules for Miscellaneous
    @ArchTest
    static final ArchRule misc_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.scripting..",
                "org.springframework.jmx..",
                "org.springframework.ejb..",
                "org.springframework.resilience.."
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
                "org.springframework.cache.."
            )
            .because("Miscellaneous is a parallel peer layer to DataAccess per the documented Spring architecture diagram. JMX, scripting, EJB, and resilience extensions must not compile-depend on DataAccess layer modules.");

    @ArchTest
    static final ArchRule misc_layer_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.scripting..",
                "org.springframework.jmx..",
                "org.springframework.ejb..",
                "org.springframework.resilience.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .because("Miscellaneous is a parallel peer layer to Web per the documented Spring architecture diagram. JMX, scripting, EJB, and resilience extensions must not compile-depend on Web layer modules.");

    // =========================================================================
    // Fine-grained Rules: Module-to-Module Isolation in DataAccess
    // =========================================================================

    @ArchTest
    static final ArchRule spring_jdbc_must_not_depend_on_spring_orm =
        noClasses()
            .that().resideInAPackage("org.springframework.jdbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
            .because("spring-jdbc provides foundational JDBC abstraction and must not depend on spring-orm's higher-level ORM integration, which itself builds on top of spring-jdbc. A dependency in this direction would create a circular module dependency.");

    // R5 (spring_orm_must_not_depend_on_spring_jdbc_directly) REMOVED per F2.
    // spring-orm's JPA/Hibernate integration legitimately uses JdbcTransactionObjectSupport,
    // ConnectionHolder, and DataSourceUtils from spring-jdbc. The PDF does not forbid this edge.

    @ArchTest
    static final ArchRule spring_messaging_must_not_depend_on_spring_web =
        noClasses()
            .that().resideInAPackage("org.springframework.messaging..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .because("spring-messaging provides a generic messaging abstraction (STOMP, broker relay) that must remain decoupled from the Web layer. HTTP-specific messaging bridges belong in spring-webmvc or spring-webflux, not in spring-messaging itself.");

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
            .because("spring-r2dbc provides reactive database access and must not depend on the blocking spring-jdbc API. Mixing reactive and blocking database APIs within a single dependency chain defeats the purpose of the reactive pipeline and introduces hidden thread-blocking risks.");

    // =========================================================================
    // Fine-grained Rules: Module-to-Module Isolation in Web
    // =========================================================================

    @ArchTest
    static final ArchRule spring_webmvc_must_not_depend_on_spring_webflux =
        noClasses()
            .that().resideInAPackage("org.springframework.web.servlet..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.reactive..")
            .because("spring-webmvc is the blocking Servlet-based web framework and must not depend on the reactive spring-webflux model. Mixing these execution models in the same dependency chain causes incompatible threading and lifecycle assumptions.");

    @ArchTest
    static final ArchRule spring_webflux_must_not_depend_on_spring_webmvc =
        noClasses()
            .that().resideInAPackage("org.springframework.web.reactive..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.servlet..")
            .because("spring-webflux is the non-blocking reactive web framework and must not depend on the Servlet-based spring-webmvc model. Coupling the reactive stack to Servlet APIs breaks portability on non-Servlet runtimes such as Netty.");

    // =========================================================================
    // Fine-grained Rules: CoreContainer Module Isolation
    // =========================================================================

    @ArchTest
    static final ArchRule spring_beans_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.beans..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-beans provides the foundational BeanFactory API and must not depend on the higher-level spring-context ApplicationContext. spring-context extends spring-beans, so an upward dependency creates a circular module dependency.");

    @ArchTest
    static final ArchRule spring_core_must_not_depend_on_spring_beans =
        noClasses()
            .that().resideInAPackage("org.springframework.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.beans..")
            .because("spring-core provides lowest-level utilities (type conversion, resource loading) and must not depend on spring-beans. spring-beans depends on spring-core, so a reverse dependency introduces a circular module dependency.");

    @ArchTest
    static final ArchRule spring_expression_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.expression..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-expression (SpEL) is a standalone evaluation engine consumed by spring-context for annotation processing. A reverse dependency from expression to context would create a circular module relationship.");

    // F8: three additional intra-CoreContainer rules omitted from initial generation
    @ArchTest
    static final ArchRule spring_core_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-core is the lowest foundation; ApplicationContext is built on top of core, beans, and expression. A direct dependency from core to context creates a circular module dependency.");

    @ArchTest
    static final ArchRule spring_core_must_not_depend_on_spring_expression =
        noClasses()
            .that().resideInAPackage("org.springframework.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.expression..")
            .because("SpEL is built on top of spring-core utilities, not the other way around. A dependency from core to expression inverts the documented CoreContainer module hierarchy.");

    @ArchTest
    static final ArchRule spring_beans_must_not_depend_on_spring_expression =
        noClasses()
            .that().resideInAPackage("org.springframework.beans..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.expression..")
            .because("spring-beans is lower than SpEL in the CoreContainer hierarchy. @Value/SpEL wiring belongs in spring-context, not spring-beans. A beans-to-expression dependency bypasses the intended context-level integration point.");

    // =========================================================================
    // Fine-grained Rules: AOP Layer Isolation
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
            .because("spring-aop provides generic proxy and aspect mechanisms that must remain decoupled from DataAccess concerns. Transaction management through AOP is wired by spring-tx and spring-context, not by spring-aop itself.");

    @ArchTest
    static final ArchRule spring_aop_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAPackage("org.springframework.aop..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.http.."
            )
            .because("spring-aop provides generic aspect infrastructure and must not contain dependencies on the Web layer. Web-specific AOP concerns (e.g., request-scoped proxies) are wired externally by the Web and Context layers, not by spring-aop itself.");

    // F3: defensive rule to assert AOT and AOP are unrelated technologies
    @ArchTest
    static final ArchRule aot_must_not_depend_on_aop =
        noClasses()
            .that().resideInAPackage("org.springframework.aot..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.aop..")
            .because("AOT (Ahead-Of-Time compilation / native-image runtime hints) and AOP (Aspect-Oriented Programming) are unrelated Spring technologies and must remain decoupled. AOT operates at build time on class metadata; AOP operates at runtime via dynamic proxies.");

    // =========================================================================
    // Cross-cutting Rules: Test Code Containment
    // =========================================================================

    // F10: replaced enumerated package list with negative-selector covering all production packages
    @ArchTest
    static final ArchRule production_code_must_not_depend_on_spring_test_packages =
        noClasses()
            .that().resideInAPackage("org.springframework..")
            .and().resideOutsideOfPackages(
                "org.springframework.test..",
                "org.springframework.tests..",
                "org.springframework.mock.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.test..",
                "org.springframework.tests..",
                "org.springframework.mock.."
            )
            .because("spring-test and spring-mock ship as testing utilities; production Spring modules must not compile-depend on them so users can exclude them at runtime without breaking the application.");
}
