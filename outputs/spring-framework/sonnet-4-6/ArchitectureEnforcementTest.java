/**
 * ArchitectureEnforcementTest
 *
 * Enforces the documented Spring Framework layer hierarchy using ArchUnit rules.
 * Updated after Review #4 (reviewer: opus-4-7) to resolve all new findings.
 *
 * NOTE: the current layered model requires 8 ignoreDependency() carve-outs for documented
 * Spring bridge APIs. Future maintainers should expect additional carve-outs as Spring adds
 * new bridge modules. (F39)
 *
 * ignoreDependency carve-outs (documented Spring bridges):
 *   - context.{annotation,event,config} -> aop..           (F29/F36: @EnableAspectJAutoProxy, @EventListener)
 *   - orm.jpa.support -> web..                             (F16: Open-EntityManager-in-View)
 *   - jndi -> aop..                                        (F33: JndiObjectFactoryBean proxies)
 *   - context.{annotation,config} -> jmx..                 (F37: @EnableMBeanExport)
 *   - web.{servlet,reactive}.view.script -> scripting..    (F38: ScriptTemplateView)
 *   - validation -> aop..                                  (F42: ValidationAnnotationUtils proxy unwrap)
 *   - cache -> aop..                                       (F43: @EnableCaching advice wiring)
 *   - cache.transaction -> transaction..                   (F44: TransactionAwareCacheDecorator)
 *
 * Layer Hierarchy (bottom to top):
 *   1. CoreContainer  - Foundation IoC/DI layer.
 *                       Packages: core, beans, context, contextsupport, expression, lang,
 *                       util, stereotype, aot, instrument, format, ui, jndi,
 *                       oxm (cross-cutting XML codec SPI; F30),
 *                       cache (cross-cutting caching SPI; F30),
 *                       scheduling root + concurrent/config/support sub-packages
 *                         (quartz removed to DataAccess per F31),
 *                       validation root + annotation/method/support sub-packages
 *                         (validation.annotation restored from AopConsumers per F42;
 *                          validation.beanvalidation remains in AopConsumers)
 *   2. AOP            - Aspect-Oriented Programming. Packages: aop
 *   3. Messaging      - Messaging abstraction. Above AOP to allow RSocket service proxies (F34).
 *                       Mutual access with AopConsumers (F45): Messaging wires
 *                       OptionalValidatorFactoryBean; AopConsumers annotation config uses messaging.
 *                       Packages: messaging
 *   4. AopConsumers   - SYNTHETIC (not in Spring docs). AOP-backed annotation subpackages (F13).
 *                       Reachable by Messaging and all peer layers (F35/F45).
 *                       Packages: scheduling.annotation, validation.beanvalidation
 *                       (validation.annotation moved back to CoreContainer in F42)
 *   -- Peer layers (all may access CoreContainer, AOP, Messaging, AopConsumers) --
 *   5. DataAccess     - Packages: dao, jdbc, orm, transaction, r2dbc, jms, jca, mail,
 *                                 scheduling.quartz (Quartz uses jdbc+tx; F31)
 *   6. Web            - Packages: web, http
 *   7. Miscellaneous  - Packages: scripting, jmx, ejb, resilience
 *
 * Key corrections applied versus Review #3 state (Review #4):
 *   - validation.annotation restored to CoreContainer; ignoreDependency(validation->aop) added (F42)
 *   - ignoreDependency(cache->aop) added for @EnableCaching AOP-backed advisors (F43)
 *   - ignoreDependency(cache.transaction->transaction) added for TransactionAwareCacheDecorator (F44)
 *   - Messaging.mayOnlyAccessLayers updated to include AopConsumers (F45)
 *   - Javadoc updated to reflect F42-F45 changes (F46)
 *
 * Excluded Packages and Rationale:
 *   - org.springframework.asm       : Repackaged ASM library; not Spring-authored.
 *   - org.springframework.cglib     : Repackaged CGLIB library; not Spring-authored.
 *   - org.springframework.objenesis : Repackaged Objenesis library; not Spring-authored.
 *   - org.springframework.javapoet  : Repackaged JavaPoet library; build-time only.
 *   - org.springframework.protobuf  : Repackaged Protobuf classes; not Spring-authored.
 *   - org.springframework.build     : Internal Gradle build utilities; not deployable.
 *   - org.springframework.docs      : Documentation samples; not production code.
 */
package com.archunit.spring;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
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
    // Named package-set constants (F26 retained; F30: oxm+cache removed from DA; F31: quartz added)
    // =========================================================================

    // DataAccess peer: oxm+cache moved to CoreContainer (F30), scheduling.quartz added (F31)
    private static final String[] DATA_ACCESS_PEER = {
        "org.springframework.dao..",
        "org.springframework.jdbc..",
        "org.springframework.orm..",
        "org.springframework.transaction..",
        "org.springframework.r2dbc..",
        "org.springframework.jms..",
        "org.springframework.jca..",
        "org.springframework.mail..",
        "org.springframework.scheduling.quartz.."  // F31: Quartz uses jdbc+tx; belongs in DA
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

            // CoreContainer: F30 adds oxm+cache; F31 removes quartz; F42 restores validation.annotation
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
                "org.springframework.jndi..",
                "org.springframework.oxm..",          // F30: cross-cutting XML codec SPI
                "org.springframework.cache..",        // F30: cross-cutting caching SPI
                // scheduling: root + sub-packages except annotation (AopConsumers) and quartz (DA)
                "org.springframework.scheduling",
                "org.springframework.scheduling.concurrent..",
                "org.springframework.scheduling.config..",
                "org.springframework.scheduling.support..",
                // validation: root + all sub-packages except beanvalidation (AopConsumers)
                // validation.annotation RESTORED from AopConsumers (F42): ValidationAnnotationUtils
                // is a utility used by DataBinder, Messaging, and WebFlux; cannot live in AopConsumers.
                "org.springframework.validation",
                "org.springframework.validation.annotation..",
                "org.springframework.validation.method..",
                "org.springframework.validation.support.."
            )

            // AOP: declared before Messaging so Messaging can reference it (F34)
            .layer("AOP").definedBy(
                "org.springframework.aop.."
            )

            // Messaging: above AOP to allow RSocket service proxy creation (F34)
            .layer("Messaging").definedBy(
                "org.springframework.messaging.."
            )

            // AopConsumers: validation.annotation removed (F42 reverted F32).
            // Only truly AOP-extending post-processors remain here.
            .layer("AopConsumers").definedBy(
                "org.springframework.scheduling.annotation..",
                "org.springframework.validation.beanvalidation.."
            )

            // DataAccess: scheduling.quartz added (F31)
            .layer("DataAccess").definedBy(DATA_ACCESS_PEER)

            .layer("Web").definedBy(WEB_PEER)

            .layer("Miscellaneous").definedBy(MISC_PEER)

            // Layer access rules (F34/F35: reordered; peers include AopConsumers)
            .whereLayer("CoreContainer").mayNotAccessAnyLayer()
            .whereLayer("AOP").mayOnlyAccessLayers("CoreContainer")
            // F45: Messaging and AopConsumers have mutual access — by design.
            //   Messaging->AopConsumers: AbstractMessageBrokerConfiguration wires OptionalValidatorFactoryBean.
            //   AopConsumers->Messaging: allowed by mayOnlyAccessLayers, no current edge exists.
            .whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer", "AOP", "AopConsumers")  // F34 + F45
            .whereLayer("AopConsumers").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging")
            .whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")     // F35
            .whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")           // F35
            .whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")  // F35

            // F29/F36: fixed glob aop->aop.. ; broadened source to context.annotation+event+config
            .ignoreDependency(
                resideInAnyPackage(
                    "org.springframework.context.annotation..",
                    "org.springframework.context.event..",
                    "org.springframework.context.config.."
                ),
                resideInAPackage("org.springframework.aop..")
            )

            // F16 (retained): orm.jpa.support is the documented Open-EntityManager-in-View bridge
            .ignoreDependency(
                resideInAPackage("org.springframework.orm.jpa.support.."),
                resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
            )

            // F33: JndiObjectFactoryBean builds AOP-backed lazy proxies via ProxyFactory
            .ignoreDependency(
                resideInAPackage("org.springframework.jndi.."),
                resideInAPackage("org.springframework.aop..")
            )

            // F37: @EnableMBeanExport is a documented public API of spring-context wiring spring-jmx
            .ignoreDependency(
                resideInAnyPackage(
                    "org.springframework.context.annotation..",
                    "org.springframework.context.config.."
                ),
                resideInAPackage("org.springframework.jmx..")
            )

            // F38: ScriptTemplateView (web.servlet/reactive) uses scripting.support utilities
            .ignoreDependency(
                resideInAnyPackage(
                    "org.springframework.web.servlet.view.script..",
                    "org.springframework.web.reactive.result.view.script.."
                ),
                resideInAPackage("org.springframework.scripting..")
            )

            // F42: validation.annotation.ValidationAnnotationUtils defensively unwraps AOP proxies
            //      via AopUtils.isAopProxy / AopProxyUtils.proxiedUserInterfaces.
            //      validation.annotation lives in CoreContainer; CoreContainer->AOP requires a carve-out.
            .ignoreDependency(
                resideInAPackage("org.springframework.validation.."),
                resideInAPackage("org.springframework.aop..")
            )

            // F43: @EnableCaching wires AOP-backed cache advisors.
            //      cache.interceptor is a StaticMethodMatcherPointcut; CacheProxyFactoryBean is an
            //      AbstractSingletonProxyFactoryBean. Cache advice IS AOP — documented Spring feature.
            .ignoreDependency(
                resideInAPackage("org.springframework.cache.."),
                resideInAPackage("org.springframework.aop..")
            )

            // F44: TransactionAwareCacheDecorator integrates cache eviction with spring-tx synchronization
            //      by implementing TransactionSynchronization (cache.transaction -> transaction.support).
            .ignoreDependency(
                resideInAPackage("org.springframework.cache.transaction.."),
                resideInAPackage("org.springframework.transaction..")
            )

            .because("Per the Spring Framework architecture diagram, CoreContainer is the foundation, AOP is above it, Messaging is above AOP (RSocket proxies use ProxyFactory), AopConsumers bridge AOP features for annotation-driven SPIs, and DataAccess/Web/Miscellaneous are parallel top-row peer layers. Documented bridge APIs are accommodated via ignoreDependency carve-outs.");

    // =========================================================================
    // Fine-grained Rules: Parallel Peer Isolation (DataAccess / Web)
    // =========================================================================

    // F16: orm.jpa.support excluded (Open-EntityManager-in-View documented bridge)
    // F30/F40: because() updated — oxm+cache moved to CoreContainer, now accessible to Web
    @ArchTest
    static final ArchRule data_access_layer_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAnyPackage(DATA_ACCESS_PEER)
            .and().resideOutsideOfPackage("org.springframework.orm.jpa.support..")
            .should().dependOnClassesThat().resideInAnyPackage(WEB_PEER)
            .because("DataAccess and Web are parallel top-row peer layers per the documented Spring Framework architecture diagram. Cross-cutting SPIs (format, validation root, scheduling root, ui, jndi, oxm, cache) are in CoreContainer and are accessible to Web. The only documented per-class bridge is org.springframework.orm.jpa.support (Open-EntityManager-in-View pattern), which is excluded from this rule.");

    // F40: because() updated to reflect oxm+cache in CoreContainer
    @ArchTest
    static final ArchRule web_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(WEB_PEER)
            .should().dependOnClassesThat().resideInAnyPackage(DATA_ACCESS_PEER)
            .because("DataAccess and Web are parallel top-row peer layers per the documented Spring Framework architecture diagram. Cross-cutting SPIs (format, validation root, scheduling root, ui, jndi, oxm, cache) are in CoreContainer and are directly accessible to Web without violating this rule.");

    // =========================================================================
    // Fine-grained Rules: Parallel Peer Isolation (Miscellaneous)
    // =========================================================================

    @ArchTest
    static final ArchRule misc_layer_must_not_depend_on_data_access_layer =
        noClasses()
            .that().resideInAnyPackage(MISC_PEER)
            .should().dependOnClassesThat().resideInAnyPackage(DATA_ACCESS_PEER)
            .because("Miscellaneous is a parallel peer layer to DataAccess. JMX, scripting, EJB, and resilience extensions must not compile-depend on DataAccess layer modules. JNDI access is permitted via CoreContainer.");

    @ArchTest
    static final ArchRule misc_layer_must_not_depend_on_web_layer =
        noClasses()
            .that().resideInAnyPackage(MISC_PEER)
            .should().dependOnClassesThat().resideInAnyPackage(WEB_PEER)
            .because("Miscellaneous is a parallel peer layer to Web. JMX, scripting, EJB, and resilience extensions must not compile-depend on Web layer modules.");

    // =========================================================================
    // Fine-grained Rules: Module-to-Module Isolation in DataAccess
    // =========================================================================

    @ArchTest
    static final ArchRule spring_jdbc_must_not_depend_on_spring_orm =
        noClasses()
            .that().resideInAPackage("org.springframework.jdbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
            .because("spring-jdbc provides foundational JDBC abstraction and must not depend on spring-orm's higher-level ORM integration, which itself builds on spring-jdbc. A reverse dependency creates a circular module dependency.");

    // F41: guard rail — currently 0 violations; preserves the documented dao invariant against future drift.
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

    // F41: guard rail — currently 0 violations; preserves the documented tx abstraction invariant.
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
            .because("spring-tx provides the generic transaction abstraction (PlatformTransactionManager, TransactionTemplate). Specific persistence modules depend on spring-tx; the reverse direction creates a circular dependency.");

    @ArchTest
    static final ArchRule spring_jms_must_not_depend_on_spring_jdbc =
        noClasses()
            .that().resideInAPackage("org.springframework.jms..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-jms provides JMS messaging integration and must not depend on spring-jdbc. Both are independent DataAccess modules and must remain isolated from each other.");

    @ArchTest
    static final ArchRule spring_r2dbc_must_not_depend_on_spring_jdbc =
        noClasses()
            .that().resideInAPackage("org.springframework.r2dbc..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("spring-r2dbc provides reactive database access and must not depend on the blocking spring-jdbc API. Mixing reactive and blocking database APIs introduces hidden thread-blocking risks.");

    // =========================================================================
    // Fine-grained Rules: Module-to-Module Isolation in Web
    // =========================================================================

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
            .because("spring-beans provides the foundational BeanFactory API and must not depend on spring-context. spring-context extends spring-beans; a reverse dependency creates a circular module dependency.");

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
            .because("spring-expression (SpEL) is consumed by spring-context; a reverse dependency creates a circular module relationship.");

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

    // F3 retained: defensive rule — aot must not depend on aop
    @ArchTest
    static final ArchRule aot_must_not_depend_on_aop =
        noClasses()
            .that().resideInAPackage("org.springframework.aot..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.aop..")
            .because("AOT (Ahead-Of-Time compilation / native-image runtime hints) is consumed by AOP modules to register proxy hints; the reverse direction is not documented and must not occur.");

    // F28: aop_must_not_depend_on_aot DELETED — AOP legitimately uses AotDetector and RuntimeHints
    // for GraalVM native-image support (AspectJAdvisorBeanRegistrationAotProcessor, CglibAopProxy).
    // The bidirectional symmetry argument was not supported by documentation or the codebase.

    // =========================================================================
    // Cross-cutting Rules: Test Code Containment
    // =========================================================================

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
