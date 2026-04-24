/**
 * ArchitectureEnforcementTest
 *
 * Enforces the documented Spring Framework layer hierarchy using ArchUnit rules.
 * Updated after Review #2 (reviewer: opus-4-7) to resolve all new CRITICAL/HIGH/MEDIUM/LOW findings.
 *
 * Layer Hierarchy (bottom to top):
 *   1. CoreContainer  - Foundation IoC/DI layer. Packages: core, beans, context, contextsupport,
 *                       expression, lang, util, stereotype, aot, instrument, format, ui, jndi,
 *                       scheduling (all sub-packages except scheduling.annotation),
 *                       validation (all sub-packages except validation.beanvalidation)
 *   2. Messaging      - Messaging abstraction foundation. Demoted from peer to foundation layer
 *                       (F15 Option A) so DataAccess/Web/Misc can legitimately use spring-messaging.
 *                       Packages: messaging
 *   3. AOP            - Aspect-Oriented Programming only. Packages: aop
 *   4. AopConsumers   - AOP-backed annotation subpackages of CoreContainer SPIs (F13 fix).
 *                       scheduling.annotation (@Async, @Scheduled) and validation.beanvalidation
 *                       (MethodValidationPostProcessor) legitimately extend aop.* classes.
 *                       Packages: scheduling.annotation, validation.beanvalidation
 *   -- Peer layers (all may only access CoreContainer, Messaging, AOP) --
 *   5. DataAccess     - Packages: dao, jdbc, orm, oxm, transaction, r2dbc, jms, jca, mail, cache
 *   6. Web            - Packages: web, http
 *   7. Miscellaneous  - Packages: scripting, jmx, ejb, resilience
 *
 * Key corrections applied versus Review #1 state (Review #2):
 *   - scheduling.annotation and validation.beanvalidation carved out to AopConsumers (F13)
 *   - ignoreDependency for context.annotation..*->aop (TargetSource implementations) (F14)
 *   - messaging demoted to foundation layer between CoreContainer and AOP (F15 Option A)
 *   - R6 (spring_messaging_must_not_depend_on_spring_web) deleted: subsumed by new layer order (F15)
 *   - ignoreDependency for orm.jpa.support..*->web..* (Open-EntityManager-in-View bridge) (F16)
 *   - R2 excludes orm.jpa.support via resideOutsideOfPackage (F16)
 *   - jndi moved from DataAccess to CoreContainer; removed from R2, R3, NEW-A DA lists (F17/F18/F19)
 *   - Added spring_dao_must_not_depend_on_other_data_access_modules (F20)
 *   - Added spring_transaction_must_not_depend_on_specific_persistence_modules (F20)
 *   - Added aop_must_not_depend_on_aot companion rule (F21)
 *   - F22 resolved by F15 Option A: messaging is now a foundation, not a peer
 *   - R9 broadened to include web.multipart.. (F23)
 *   - R16 uses negative-only selector (F24)
 *   - because() clauses in R2/R3 updated for accuracy (F25)
 *   - Named constants DATA_ACCESS_PEER, WEB_PEER, MISC_PEER introduced (F26)
 *   - F27 resolved by F15 Option A: web.socket..*->messaging.. now allowed
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

import com.tngtech.archunit.core.domain.JavaClass;
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
    // Named package-set constants (F26: reduce copy-paste risk across rules)
    // =========================================================================

    // DataAccess peer packages; jndi removed (F19: promoted to CoreContainer)
    private static final String[] DATA_ACCESS_PEER = {
        "org.springframework.dao..",
        "org.springframework.jdbc..",
        "org.springframework.orm..",
        "org.springframework.oxm..",
        "org.springframework.transaction..",
        "org.springframework.r2dbc..",
        "org.springframework.jms..",
        "org.springframework.jca..",
        "org.springframework.mail..",
        "org.springframework.cache.."
    };

    private static final String[] WEB_PEER = {
        "org.springframework.web..",
        "org.springframework.http.."
    };

    private static final String[] MISC_PEER = {
        "org.springframework.scripting..",
        "org.springframework.jmx..",
        "org.springframework.ejb..",
        "org.springframework.resilience.."
    };

    // =========================================================================
    // Custom ImportOption: Strip repackaged third-party and build-only classes
    // =========================================================================

    /**
     * Excludes repackaged third-party libraries and build-only utilities from class scanning.
     * Uses location.contains() for safety across file: and jar: URI schemes (F9 fix retained).
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
    // Layered Architecture Rule
    // =========================================================================

    @ArchTest
    static final ArchRule spring_layered_architecture_is_respected =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()

            // CoreContainer: lowest foundation (F19: jndi added; F13: scheduling/validation split)
            .layer("CoreContainer").definedBy(
                "org.springframework.core..",
                "org.springframework.beans..",
                "org.springframework.context..",
                "org.springframework.contextsupport..",
                "org.springframework.expression..",
                "org.springframework.lang..",
                "org.springframework.util..",
                "org.springframework.stereotype..",
                "org.springframework.aot..",
                "org.springframework.instrument..",
                "org.springframework.format..",
                "org.springframework.ui..",
                "org.springframework.jndi..",             // F19: JNDI is a cross-cutting JavaEE SPI
                // scheduling root + all sub-packages EXCEPT scheduling.annotation (F13)
                "org.springframework.scheduling",
                "org.springframework.scheduling.concurrent..",
                "org.springframework.scheduling.config..",
                "org.springframework.scheduling.support..",
                "org.springframework.scheduling.quartz..",
                // validation root + all sub-packages EXCEPT validation.beanvalidation (F13)
                "org.springframework.validation",
                "org.springframework.validation.annotation..",
                "org.springframework.validation.method..",
                "org.springframework.validation.support.."
            )

            // Messaging: demoted to foundation layer (F15 Option A, F27)
            // Allows DataAccess (jms), Web (web.socket), and Misc to legitimately use messaging
            .layer("Messaging").definedBy(
                "org.springframework.messaging.."
            )

            // AOP: Aspect-Oriented Programming only; aot is in CoreContainer (F3 retained)
            .layer("AOP").definedBy(
                "org.springframework.aop.."
            )

            // AopConsumers: AOP-backed annotation subpackages (F13)
            // scheduling.annotation and validation.beanvalidation extend aop.* classes
            .layer("AopConsumers").definedBy(
                "org.springframework.scheduling.annotation..",
                "org.springframework.validation.beanvalidation.."
            )

            // DataAccess: parallel peer; jndi removed (F19)
            .layer("DataAccess").definedBy(DATA_ACCESS_PEER)

            // Web: parallel peer
            .layer("Web").definedBy(WEB_PEER)

            // Miscellaneous: parallel peer
            .layer("Miscellaneous").definedBy(MISC_PEER)

            // Layer access rules
            .whereLayer("CoreContainer").mayNotAccessAnyLayer()
            .whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer")
            .whereLayer("AOP").mayOnlyAccessLayers("CoreContainer", "Messaging")
            .whereLayer("AopConsumers").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")
            .whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")
            .whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")
            .whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")

            // F14: context.annotation implements aop.TargetSource (@Lazy, @Resource proxies)
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.springframework.context.annotation.."),
                JavaClass.Predicates.resideInAPackage("org.springframework.aop")
            )

            // F16: orm.jpa.support is the documented Open-EntityManager-in-View bridge (DA→Web)
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.springframework.orm.jpa.support.."),
                JavaClass.Predicates.resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
            )

            .because("Per the Spring Framework architecture diagram, CoreContainer is the foundation, Messaging is a cross-cutting abstraction above it, AOP is next, AopConsumers sit above AOP, and DataAccess/Web/Miscellaneous are parallel top-row peer layers. No peer layer may depend on another peer layer.");

    // =========================================================================
    // Fine-grained Rules: Parallel Peer Isolation (DataAccess / Web)
    // =========================================================================

    // F16: orm.jpa.support excluded (Open-EntityManager-in-View is a documented DA/Web bridge)
    // F19: jndi removed from DA list; F25: because() updated
    @ArchTest
    static final ArchRule data_access_layer_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAnyPackage(DATA_ACCESS_PEER)
            .and().resideOutsideOfPackage("org.springframework.orm.jpa.support..")
            .should().dependOnClassesThat().resideInAnyPackage(WEB_PEER)
            .because("DataAccess and Web are parallel top-row peer layers per the documented Spring Framework architecture diagram. The sole documented exception is org.springframework.orm.jpa.support (Open-EntityManager-in-View pattern), which is an explicit Web/JPA bridge and is excluded from this rule.");

    // F19: jndi removed from DA list (jndi is now CoreContainer, so web->jndi is allowed)
    // F25: because() updated
    @ArchTest
    static final ArchRule web_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(WEB_PEER)
            .should().dependOnClassesThat().resideInAnyPackage(DATA_ACCESS_PEER)
            .because("DataAccess and Web are parallel top-row peer layers per the documented Spring Framework architecture diagram. Cross-cutting SPIs (format, validation root, scheduling root, ui, jndi) are in CoreContainer and are accessible to Web without violating this rule.");

    // =========================================================================
    // Fine-grained Rules: Parallel Peer Isolation (Miscellaneous)
    // =========================================================================

    // F19: jndi removed from DA list (jndi promoted to CoreContainer; ejb->jndi now allowed)
    @ArchTest
    static final ArchRule misc_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(MISC_PEER)
            .should().dependOnClassesThat().resideInAnyPackage(DATA_ACCESS_PEER)
            .because("Miscellaneous is a parallel peer layer to DataAccess per the documented Spring architecture diagram. JMX, scripting, EJB, and resilience extensions must not compile-depend on DataAccess layer modules. JNDI access is permitted via CoreContainer.");

    @ArchTest
    static final ArchRule misc_layer_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAnyPackage(MISC_PEER)
            .should().dependOnClassesThat().resideInAnyPackage(WEB_PEER)
            .because("Miscellaneous is a parallel peer layer to Web per the documented Spring architecture diagram. JMX, scripting, EJB, and resilience extensions must not compile-depend on Web layer modules.");

    // =========================================================================
    // Fine-grained Rules: Module-to-Module Isolation in DataAccess
    // =========================================================================

    @ArchTest
    static final ArchRule spring_jdbc_must_not_depend_on_spring_orm =
        noClasses()
            .that().resideInAPackage("org.springframework.jdbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
            .because("spring-jdbc provides foundational JDBC abstraction and must not depend on spring-orm's higher-level ORM integration, which itself builds on top of spring-jdbc. A dependency in this direction creates a circular module dependency.");

    // F20: dao is the DataAccessException hierarchy floor; all other DA modules depend on it
    @ArchTest
    static final ArchRule spring_dao_must_not_depend_on_other_data_access_modules =
        noClasses()
            .that().resideInAPackage("org.springframework.dao..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.oxm..",
                "org.springframework.transaction..",
                "org.springframework.r2dbc..",
                "org.springframework.jms..",
                "org.springframework.jca..",
                "org.springframework.mail..",
                "org.springframework.cache.."
            )
            .because("spring-dao is the DataAccessException hierarchy foundation; every other DataAccess module depends on dao, not the other way around.");

    // F20: spring-tx provides the generic transaction abstraction; specific persistence modules build on it
    @ArchTest
    static final ArchRule spring_transaction_must_not_depend_on_specific_persistence_modules =
        noClasses()
            .that().resideInAPackage("org.springframework.transaction..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.jdbc..",
                "org.springframework.orm..",
                "org.springframework.oxm..",
                "org.springframework.r2dbc..",
                "org.springframework.jms..",
                "org.springframework.jca..",
                "org.springframework.mail..",
                "org.springframework.cache.."
            )
            .because("spring-tx provides the generic transaction abstraction (PlatformTransactionManager, TransactionTemplate). Specific persistence modules depend on spring-tx; the reverse direction would create a circular dependency.");

    @ArchTest
    static final ArchRule spring_jms_must_not_depend_on_spring_jdbc =
        noClasses()
            .that().resideInAPackage("org.springframework.jms..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-jms provides JMS messaging integration and must not depend on spring-jdbc database abstractions. Both are independent modules within the DataAccess layer and must remain isolated from each other.");

    @ArchTest
    static final ArchRule spring_r2dbc_must_not_depend_on_spring_jdbc =
        noClasses()
            .that().resideInAPackage("org.springframework.r2dbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-r2dbc provides reactive database access and must not depend on the blocking spring-jdbc API. Mixing reactive and blocking database APIs defeats the purpose of the reactive pipeline and introduces hidden thread-blocking risks.");

    // =========================================================================
    // Fine-grained Rules: Module-to-Module Isolation in Web
    // =========================================================================

    // F23: broadened to include web.multipart.. (servlet-specific multipart handling)
    @ArchTest
    static final ArchRule spring_webmvc_must_not_depend_on_spring_webflux =
        noClasses()
            .that().resideInAnyPackage(
                "org.springframework.web.servlet..",
                "org.springframework.web.multipart.."
            )
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.reactive..")
            .because("spring-webmvc is the blocking Servlet-based web framework and must not depend on the reactive spring-webflux model. Mixing these execution models causes incompatible threading and lifecycle assumptions.");

    @ArchTest
    static final ArchRule spring_webflux_must_not_depend_on_spring_webmvc =
        noClasses()
            .that().resideInAPackage("org.springframework.web.reactive..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web.servlet..",
                "org.springframework.web.multipart.."
            )
            .because("spring-webflux is the non-blocking reactive web framework and must not depend on the Servlet-based spring-webmvc model. Coupling the reactive stack to Servlet APIs breaks portability on non-Servlet runtimes such as Netty.");

    // =========================================================================
    // Fine-grained Rules: CoreContainer Module Isolation
    // =========================================================================

    @ArchTest
    static final ArchRule spring_beans_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.beans..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-beans provides the foundational BeanFactory API and must not depend on the higher-level spring-context ApplicationContext. spring-context extends spring-beans; a reverse dependency creates a circular module dependency.");

    @ArchTest
    static final ArchRule spring_core_must_not_depend_on_spring_beans =
        noClasses()
            .that().resideInAPackage("org.springframework.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.beans..")
            .because("spring-core provides lowest-level utilities and must not depend on spring-beans. spring-beans depends on spring-core; a reverse dependency introduces a circular module dependency.");

    @ArchTest
    static final ArchRule spring_expression_must_not_depend_on_spring_context =
        noClasses()
            .that().resideInAPackage("org.springframework.expression..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
            .because("spring-expression (SpEL) is consumed by spring-context for annotation processing. A reverse dependency from expression to context creates a circular module relationship.");

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
            .because("spring-beans is lower than SpEL in the CoreContainer hierarchy. @Value/SpEL wiring belongs in spring-context, not spring-beans.");

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
            .should().dependOnClassesThat().resideInAnyPackage(WEB_PEER)
            .because("spring-aop provides generic aspect infrastructure and must not contain dependencies on the Web layer. Web-specific AOP concerns are wired externally by the Web and Context layers, not by spring-aop itself.");

    // F3 retained: defensive rule asserting AOT and AOP are unrelated technologies
    @ArchTest
    static final ArchRule aot_must_not_depend_on_aop =
        noClasses()
            .that().resideInAPackage("org.springframework.aot..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.aop..")
            .because("AOT (Ahead-Of-Time compilation / native-image runtime hints) and AOP (Aspect-Oriented Programming) are unrelated Spring technologies and must remain decoupled in both directions.");

    // F21: companion rule for bidirectional AOT/AOP decoupling
    @ArchTest
    static final ArchRule aop_must_not_depend_on_aot =
        noClasses()
            .that().resideInAPackage("org.springframework.aop..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.aot..")
            .because("AOT (Ahead-Of-Time / native-image runtime hints) and AOP (Aspect-Oriented Programming) are unrelated Spring technologies and must remain decoupled in both directions. A RuntimeHintsRegistrar must not appear in spring-aop.");

    // =========================================================================
    // Cross-cutting Rules: Test Code Containment
    // =========================================================================

    // F24: negative-only selector; @AnalyzeClasses already scopes to org.springframework
    @ArchTest
    static final ArchRule production_code_must_not_depend_on_spring_test_packages =
        noClasses()
            .that().resideOutsideOfPackages(
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
