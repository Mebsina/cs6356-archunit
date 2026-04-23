/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * ZooKeeper is logically split into two sibling sub-systems communicating
 * only via the wire protocol:
 *
 *                     +-------------------+
 *                     |    Tooling/CLI    |   (cli, inspector, graph)
 *                     +---------+---------+
 *                               |
 *   +-------------+     +-------v-------+     +---------------+
 *   |   Recipes   | --> |  Client API   |     | Server Core   |
 *   |  (recipes)  |     | (client,admin,|     |   (server)    |
 *   +-------------+     |  retry, root) |     +-------+-------+
 *                       +-------+-------+             |
 *                               |                     |
 *                       +-------v---------------------v-------+
 *                       |             Support / Common        |
 *                       |  (common, util, metrics, jmx,       |
 *                       |   audit, compat, compatibility)     |
 *                       +-------------------------------------+
 *
 * Rules enforced:
 *  - Client-side packages must never import server packages, and vice versa.
 *  - Recipes are pure clients; they depend on the public API only.
 *  - Tooling may depend on client-side packages but not on server internals.
 *  - All layers may depend on Support; Support depends on nothing above it.
 */
package org.apache.zookeeper;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;

@AnalyzeClasses(packages = "org.apache.zookeeper", importOptions = { ImportOption.DoNotIncludeTests.class })
public class ArchitectureEnforcementTest {

        // --- Layered Architecture ---

        @ArchTest
        public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
                        .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                        .layer("Support").definedBy(
                                        "org.apache.zookeeper.common..",
                                        "org.apache.zookeeper.util..",
                                        "org.apache.zookeeper.metrics..",
                                        "org.apache.zookeeper.jmx..",
                                        "org.apache.zookeeper.audit..",
                                        "org.apache.zookeeper.compat..",
                                        "org.apache.zookeeper.compatibility..")
                        .layer("Server").definedBy("org.apache.zookeeper.server..")
                        .layer("ClientApi").definedBy("org.apache.zookeeper")
                        .layer("Client").definedBy(
                                        "org.apache.zookeeper.client..",
                                        "org.apache.zookeeper.admin..",
                                        "org.apache.zookeeper.retry..")
                        .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
                        .layer("Tools").definedBy(
                                        "org.apache.zookeeper.cli..",
                                        "org.apache.zookeeper.inspector..",
                                        "org.apache.zookeeper.graph..")

                        .whereLayer("Tools").mayNotBeAccessedByAnyLayer()
                        .whereLayer("Recipes").mayOnlyBeAccessedByLayers("Tools")
                        .whereLayer("Client").mayOnlyBeAccessedByLayers("Recipes", "Tools")
                        .whereLayer("ClientApi").mayOnlyBeAccessedByLayers("Client", "Recipes", "Tools", "Server")
                        .whereLayer("Server").mayOnlyBeAccessedByLayers("ClientApi")
                        .as("The architectural sub-systems should remain decoupled and respect the public API boundaries")
                        .because("ZooKeeper's scalability and stability rely on a clear separation between client contracts and server implementations.");

        // --- Fine-grained Intra-layer Rules ---

        @ArchTest
        public static final ArchRule public_api_must_not_depend_on_server = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                        .as("The public client API must not leak server internals")
                        .because("org.apache.zookeeper.ZooKeeper, Watcher, and KeeperException are "
                                        + "the stable contract consumed by applications; server-side types "
                                        + "must never appear in their transitive surface.");

        @ArchTest
        public static final ArchRule server_must_not_depend_on_client_impl = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.server..")
                        .should().dependOnClassesThat(resideInAnyPackage(
                                        "org.apache.zookeeper.client..",
                                        "org.apache.zookeeper.admin..",
                                        "org.apache.zookeeper.retry..",
                                        "org.apache.zookeeper.recipes.."))
                        .as("Server internals must not reach into client or recipes packages")
                        .because("Per PDF section 1.7, client and server communicate only over the wire "
                                        + "protocol; neither should link-depend on the other's implementation.");

        @ArchTest
        public static final ArchRule recipes_only_depend_on_public_api_or_support = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.recipes..")
                        .should().dependOnClassesThat(resideInAnyPackage(
                                        "org.apache.zookeeper.server..",
                                        "org.apache.zookeeper.admin..",
                                        "org.apache.zookeeper.cli..",
                                        "org.apache.zookeeper.inspector..",
                                        "org.apache.zookeeper.graph.."))
                        .as("Recipes must be built on the public client API only")
                        .because("Per PDF section 1.8, recipes are higher-order operations built on "
                                        + "top of the simple client API, not on server internals or tools.");

        @ArchTest
        public static final ArchRule cli_should_not_depend_on_server = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper.cli..")
                        .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                        .as("The CLI tool should not depend on server internals")
                        .because("The CLI is a client-facing tool and should only use public client or recipe APIs.");
}
