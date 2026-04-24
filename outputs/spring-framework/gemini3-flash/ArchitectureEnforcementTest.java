package org.springframework;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchitectureEnforcementTest enforces the architectural boundaries of the Spring Framework.
 *
 * DOCUMENTED LAYER HIERARCHY
 * --------------------------
 * The following hierarchy is enforced from bottom to top:
 *
 * 1. Base Utilities: The foundational layer containing utilities, language extensions, 
 *    stereotypes, and validation logic.
 *    - org.springframework.util..
 *    - org.springframework.lang..
 *    - org.springframework.stereotype..
 *    - org.springframework.validation..
 *
 * 2. Core Container: The heart of the framework providing IoC (Inversion of Control) 
 *    and DI (Dependency Injection).
 *    - org.springframework.core..
 *    - org.springframework.beans..
 *    - org.springframework.context..
 *    - org.springframework.expression..
 *
 * 3. AOP & Instrumentation: Support for Aspect-Oriented Programming and bytecode 
 *    instrumentation.
 *    - org.springframework.aop..
 *    - org.springframework.aot..
 *    - org.springframework.instrument..
 *
 * 4. Data Access & Integration: Modules for database interactions (JDBC, ORM, R2DBC) 
 *    and transaction management.
 *    - org.springframework.dao..
 *    - org.springframework.jdbc..
 *    - org.springframework.orm..
 *    - org.springframework.transaction..
 *    - org.springframework.r2dbc..
 *
 * 5. Web Layer: Web-related components including MVC, WebFlux, and HTTP support.
 *    - org.springframework.web..
 *    - org.springframework.http..
 *    - org.springframework.ui..
 *
 * 6. Miscellaneous Services: Specialized integration services.
 *    - org.springframework.jms..
 *    - org.springframework.mail..
 *    - org.springframework.messaging..
 *    - org.springframework.scheduling..
 *    - org.springframework.cache..
 *    - org.springframework.jmx..
 *
 * EXCLUDED PACKAGES RATIONALE
 * ---------------------------
 * * org.springframework.asm..: Repackaged third-party library for bytecode manipulation.
 * * org.springframework.cglib..: Repackaged third-party library for proxy generation.
 * * org.springframework.objenesis..: Repackaged library for object instantiation.
 * * org.springframework.javapoet..: Generated code utility for AOT processing.
 * * org.springframework.test..: Test framework classes and integration test support.
 * * org.springframework.tests..: Internal testing utilities.
 * * org.springframework.mock..: Mock objects for testing environments.
 * * org.springframework.build..: Build-time specific utilities.
 * * org.springframework.docs..: Documentation-specific classes.
 */
@AnalyzeClasses(packages = "org.springframework", importOptions = {
    ImportOption.DoNotIncludeTests.class,
    ArchitectureEnforcementTest.ExcludeRepackagedAndTestPackages.class
})
public class ArchitectureEnforcementTest {

    public static class ExcludeRepackagedAndTestPackages implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("org/springframework/asm") &&
                   !location.contains("org/springframework/cglib") &&
                   !location.contains("org/springframework/objenesis") &&
                   !location.contains("org/springframework/javapoet") &&
                   !location.contains("org/springframework/test") &&
                   !location.contains("org/springframework/tests") &&
                   !location.contains("org/springframework/mock") &&
                   !location.contains("org/springframework/build") &&
                   !location.contains("org/springframework/docs");
        }
    }

    // =================================================================================================
    // LAYERED ARCHITECTURE RULES
    // =================================================================================================

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringAllDependencies()
        .layer("BaseUtilities").definedBy("org.springframework.util..", "org.springframework.lang..", "org.springframework.stereotype..", "org.springframework.validation..")
        .layer("CoreContainer").definedBy("org.springframework.core..", "org.springframework.beans..", "org.springframework.context..", "org.springframework.expression..")
        .layer("AopInstrumentation").definedBy("org.springframework.aop..", "org.springframework.aot..", "org.springframework.instrument..")
        .layer("DataAccess").definedBy("org.springframework.dao..", "org.springframework.jdbc..", "org.springframework.orm..", "org.springframework.transaction..", "org.springframework.r2dbc..")
        .layer("Web").definedBy("org.springframework.web..", "org.springframework.http..", "org.springframework.ui..")
        .layer("MiscServices").definedBy("org.springframework.jms..", "org.springframework.mail..", "org.springframework.messaging..", "org.springframework.scheduling..", "org.springframework.cache..", "org.springframework.jmx..")
        
        .whereLayer("Web").mayNotBeAccessedByAnyLayer()
        .whereLayer("MiscServices").mayOnlyBeAccessedByLayers("Web")
        .whereLayer("DataAccess").mayOnlyBeAccessedByLayers("Web", "MiscServices")
        .whereLayer("AopInstrumentation").mayOnlyBeAccessedByLayers("DataAccess", "Web", "MiscServices", "CoreContainer")
        .whereLayer("CoreContainer").mayOnlyBeAccessedByLayers("AopInstrumentation", "DataAccess", "Web", "MiscServices")
        .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers("CoreContainer", "AopInstrumentation", "DataAccess", "Web", "MiscServices")
        .because("The framework must follow a strict downward dependency flow to ensure maintainability and modularity.");

    @ArchTest
    public static final ArchRule parallel_layers_web_and_dataaccess_are_independent = noClasses()
        .that().resideInAPackage("org.springframework.web..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
        .because("The Web layer should remain agnostic of specific ORM implementations to prevent tight coupling.");

    // =================================================================================================
    // FINE-GRAINED INTRA-LAYER RULES
    // =================================================================================================

    @ArchTest
    public static final ArchRule beans_should_not_depend_on_context = noClasses()
        .that().resideInAPackage("org.springframework.beans..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
        .because("The Bean definition and factory layer is a dependency of the ApplicationContext, and a reverse dependency would create a circular reference.");

    @ArchTest
    public static final ArchRule core_should_not_depend_on_beans = noClasses()
        .that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.beans..")
        .because("Core utilities and ASM-related processing should not have knowledge of high-level bean management.");

    @ArchTest
    public static final ArchRule jdbc_should_not_depend_on_orm = noClasses()
        .that().resideInAPackage("org.springframework.jdbc..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
        .because("The JDBC abstraction layer should remain a low-level alternative to ORM and must not depend on it.");

    @ArchTest
    public static final ArchRule aop_should_not_depend_on_dataaccess = noClasses()
        .that().resideInAPackage("org.springframework.aop..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.transaction..")
        .because("Base AOP infrastructure should be independent of specific cross-cutting concerns like transactions, which themselves build upon AOP.");
}
