/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * Documented Layer Hierarchy (Bottom to Top):
 * 1. Support/Infrastructure: Common utilities, metrics, JMX, and auditing.
 * 2. Core Server: Request processing, atomic broadcast, and replicated database.
 * 3. Client API: Low-level client implementations and administrative tools.
 * 4. Recipes/Abstractions: Higher-level coordination primitives (locks, queues).
 * 5. Tooling & CLI: Interactive shells and inspection tools.
 * 6. Compatibility: Layers maintaining backward compatibility with older versions.
 *
 * Excluded Packages:
 * - org.apache.zookeeper.test..: Excluded as it contains test suites, benchmarks, and internal testing utilities
 *   that are not part of the production architectural constraints.
 */
package org.apache.zookeeper;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

@AnalyzeClasses(packages = "org.apache.zookeeper", importOptions = { ImportOption.DoNotIncludeTests.class })
public class ArchitectureEnforcementTest {

        // --- Layered Architecture ---

        @ArchTest
        public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
                        .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                        .layer("Support").definedBy("org.apache.zookeeper.common..",
                                        "org.apache.zookeeper.util..",
                                        "org.apache.zookeeper.metrics..",
                                        "org.apache.zookeeper.jmx..",
                                        "org.apache.zookeeper.audit..")
                        .layer("Server").definedBy("org.apache.zookeeper.server..")
                        .layer("Client").definedBy("org.apache.zookeeper.client..",
                                        "org.apache.zookeeper.admin..",
                                        "org.apache.zookeeper.retry..")
                        .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
                        .layer("Tools").definedBy("org.apache.zookeeper.cli..",
                                        "org.apache.zookeeper.inspector..",
                                        "org.apache.zookeeper.graph..")
                        .layer("Compatibility").definedBy("org.apache.zookeeper.compat..",
                                        "org.apache.zookeeper.compatibility..")

                        .whereLayer("Compatibility").mayNotBeAccessedByAnyLayer()
                        .whereLayer("Tools").mayNotBeAccessedByAnyLayer()
                        .whereLayer("Recipes").mayOnlyBeAccessedByLayers("Tools")
                        .whereLayer("Client").mayOnlyBeAccessedByLayers("Recipes", "Tools", "Compatibility")
                        .whereLayer("Server").mayOnlyBeAccessedByLayers("Compatibility")
                        .whereLayer("Support").mayBeAccessedByAllLayers()
                        .as("The architectural layers should follow the defined dependency hierarchy")
                        .because("Maintaining strict layer boundaries prevents cyclic dependencies and ensures system maintainability.");

        // --- Fine-grained Intra-layer Rules ---

        @ArchTest
        public static final ArchRule server_should_not_depend_on_client = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.server..")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.client.."))
                        .as("The server core should be independent of the client implementation")
                        .because("The server handles coordination logic and should not rely on client-side state management or APIs.");

        @ArchTest
        public static final ArchRule server_should_not_depend_on_recipes = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.server..")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.recipes.."))
                        .as("The server core should not depend on higher-level recipes")
                        .because("Recipes are client-side abstractions built on top of the ZooKeeper API and do not belong in the server.");

        @ArchTest
        public static final ArchRule client_should_not_depend_on_server = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.client..")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                        .as("The client implementation should not depend on server internals")
                        .because("Clients interact with the server via the network protocol and should remain decoupled from server-side logic.");

        @ArchTest
        public static final ArchRule recipes_should_not_depend_on_server = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.recipes..")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                        .as("Recipes should be purely client-side and not depend on server internals")
                        .because("Recipes leverage the public client API to implement distributed primitives.");

        @ArchTest
        public static final ArchRule cli_should_not_depend_on_server = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.cli..")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                        .as("The CLI tool should not depend on server internals")
                        .because("The CLI is a client-facing tool and should only use public client or recipe APIs.");
}
