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
 *   |   Recipes   | --> |    Client     |     |    Server     |
 *   |  (recipes)  |     |  (root + api  |     |   (server)    |
 *   +-------------+     |   + admin +   |     +-------+-------+
 *                       |    retry)     |             |
 *                       +-------+-------+             |
 *                               |                     |
 *                       +-------v---------------------v-------+
 *                       |             Support                 |
 *                       |  (common, util, metrics, jmx,       |
 *                       |   audit, compat, compatibility)     |
 *                       +-------------------------------------+
 *
 * Excluded packages:
 *   - org.apache.zookeeper.test.. — contains JUnit fixtures and benchmarks
 *     that live under src/test/java; excluded via ImportOption.DoNotIncludeTests.
 */
package org.apache.zookeeper;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "org.apache.zookeeper", importOptions = { ImportOption.DoNotIncludeTests.class })
public class ArchitectureEnforcementTest {

        // --- Layered Architecture ------------------------------------------------

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
                        .layer("PublicApi").definedBy("org.apache.zookeeper") // root only
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
                        .whereLayer("Client").mayOnlyBeAccessedByLayers(
                                        "PublicApi", // root internally references client impl
                                        "Recipes", "Tools")
                        .whereLayer("PublicApi").mayOnlyBeAccessedByLayers(
                                        "Support", // audit/compatibility/common touch root types
                                        "Client", "Server", // both sides share the contract (KeeperException, Watcher)
                                        "Recipes", "Tools")
                        .whereLayer("Server").mayNotBeAccessedByAnyLayer()

                        .as("Client-side and server-side sub-systems remain decoupled")
                        .because("Per PDF sections 1.1 and 1.7, ZooKeeper clients and "
                                        + "servers communicate only over the TCP wire protocol; "
                                        + "the public API contract types (Version, KeeperException, "
                                        + "Watcher, Op, CreateMode, ZooDefs) are the shared vocabulary "
                                        + "that every other layer — including support utilities that "
                                        + "report version, audit operations, or describe error codes — "
                                        + "must be free to reference. Only Server implementation packages "
                                        + "must not leak upward into the public API.");

        // --- Fine-grained public-API carve-out -----------------------------------
        // Documentation-only restatement of the layered rule with a contract-specific
        // rationale;
        // kept so that a PublicApi -> Server violation surfaces with the specific
        // public-API rationale
        // rather than a generic 'Server may not be accessed' message.

        @ArchTest
        public static final ArchRule public_api_must_not_leak_server_types = noClasses()
                        .that().resideInAPackage("org.apache.zookeeper") // root only
                        .should().dependOnClassesThat(
                                        resideInAPackage("org.apache.zookeeper.server.."))
                        .because("The public client API (ZooKeeper, Watcher, "
                                        + "KeeperException, CreateMode, ZooDefs, AsyncCallback, "
                                        + "ClientCnxn) is the stable contract applications "
                                        + "compile against; server-side types must never appear "
                                        + "in its transitive surface.");

        // --- Recipe Isolation ----------------------------------------------------

        @ArchTest
        public static final ArchRule recipe_modules_are_independent = slices()
                        .matching("org.apache.zookeeper.recipes.(*)..")
                        .should().notDependOnEachOther()
                        .because("Each recipe (lock, queue, leader) is an independent "
                                        + "coordination primitive; they must not compile-depend "
                                        + "on one another.");

        // --- Build Integrity -----------------------------------------------------

        @ArchTest
        public static final ArchRule test_package_must_not_ship_in_production = noClasses()
                        .should().resideInAPackage("org.apache.zookeeper.test..")
                        .because("org.apache.zookeeper.test.* is test-scope fixtures and "
                                        + "benchmarks. ImportOption.DoNotIncludeTests excludes them "
                                        + "when compiled under src/test/java, so the importer "
                                        + "should never see them. Any class found here on the main "
                                        + "classpath indicates a broken build or a fixture "
                                        + "accidentally promoted to production.");
}
