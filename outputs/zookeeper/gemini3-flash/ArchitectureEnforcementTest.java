/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * ZooKeeper is logically split into client- and server-side sub-systems
 * that share only a narrow public-API contract and communicate at runtime
 * via the TCP wire protocol:
 *
 *                     +-------------------+
 *                     |    Tooling/CLI    |   cli, inspector, graph
 *                     +---------+---------+
 *                               |
 *   +-------------+     +-------v-------+
 *   |   Recipes   | --> |    Client     |   client, admin, retry
 *   |  (recipes)  |     +-------+-------+
 *   +------+------+             |
 *          |                    |
 *          v                    v
 *          +--------+------------+--------+
 *                   |  PublicApi  |                   root:
 *                   |   (shared   |          ZooKeeper, KeeperException,
 *                   |   contract) |     Watcher, CreateMode, ZooDefs, ...
 *                   +------+------+
 *                          ^
 *                          |
 *                   +------+------+
 *                   |   Server    |   server (isolated; only reaches down
 *                   +------+------+       to PublicApi and Support)
 *                          |
 *                          v
 *                   +------+------------------------------+
 *                   |             Support                 |
 *                   |  (common, util, metrics, jmx,       |
 *                   |   audit, compat, compatibility)     |
 *                   +-------------------------------------+
 *
 * Allowed edges (summary):
 *   - Support      is reachable from every layer; it reaches PublicApi.
 *   - PublicApi    is reachable from every layer; it reaches Client and Support.
 *   - Client       reaches PublicApi and Support; reachable by PublicApi,
 *                  Recipes, Tools.
 *   - Server       reaches PublicApi and Support; not reachable from any layer.
 *   - Recipes      reach Client, PublicApi, Support; reachable only by Tools.
 *   - Tools        reach everything except Server; not reachable from any layer.
 *
 * Excluded packages:
 *   - org.apache.zookeeper.test..  JUnit fixtures under src/test/java,
 *                                   excluded via DoNotIncludeTests.
 */
package org.apache.zookeeper;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "org.apache.zookeeper", importOptions = { ImportOption.DoNotIncludeTests.class })
public class ArchitectureEnforcementTest {

        // --- Predicates for Shared Utilities & Root-level Tools ---

        private static final DescribedPredicate<JavaClass> shared_server_utilities = resideInAPackage(
                        "org.apache.zookeeper.server")
                        .and(simpleName("ZooKeeperThread")
                                        .or(simpleName("ByteBufferInputStream"))
                                        .or(simpleName("ZooTrace"))
                                        .or(simpleName("ExitCode"))
                                        .or(simpleName("EphemeralType"))
                                        .or(simpleName("PathParentIterator")))
                        .as("shared utilities historically placed in org.apache.zookeeper.server");

        private static final DescribedPredicate<JavaClass> public_api_classes = resideInAPackage("org.apache.zookeeper")
                        .and(not(simpleName("ZooKeeperMain")))
                        .and(not(simpleName("ZooKeeperMain$MyCommandOptions")))
                        .as("public API contract types in the root package");

        private static final DescribedPredicate<JavaClass> tools_classes = resideInAnyPackage(
                        "org.apache.zookeeper.cli..",
                        "org.apache.zookeeper.inspector..",
                        "org.apache.zookeeper.graph..")
                        .or(simpleName("ZooKeeperMain"))
                        .or(simpleName("ZooKeeperMain$MyCommandOptions"))
                        .as("CLI and tooling classes");

        private static final DescribedPredicate<JavaClass> server_internal_classes = resideInAPackage(
                        "org.apache.zookeeper.server")
                        .and(not(shared_server_utilities))
                        .or(resideInAnyPackage(
                                        "org.apache.zookeeper.server.admin..",
                                        "org.apache.zookeeper.server.command..",
                                        "org.apache.zookeeper.server.embedded..",
                                        "org.apache.zookeeper.server.persistence..",
                                        "org.apache.zookeeper.server.quorum..",
                                        "org.apache.zookeeper.server.watch..",
                                        "org.apache.zookeeper.audit.."))
                        .as("server-side implementation classes");

        // --- Layered Architecture ------------------------------------------------

        @ArchTest
        public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
                        .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                        .layer("Support").definedBy(
                                        resideInAnyPackage(
                                                        "org.apache.zookeeper.common..",
                                                        "org.apache.zookeeper.util..",
                                                        "org.apache.zookeeper.metrics..",
                                                        "org.apache.zookeeper.jmx..",
                                                        "org.apache.zookeeper.compat..",
                                                        "org.apache.zookeeper.compatibility..",
                                                        "org.apache.zookeeper.server.auth..",
                                                        "org.apache.zookeeper.server.metric..",
                                                        "org.apache.zookeeper.server.util..")
                                                        .or(shared_server_utilities))
                        .layer("Server").definedBy(server_internal_classes)
                        .layer("PublicApi").definedBy(public_api_classes)
                        .layer("Client").definedBy(
                                        "org.apache.zookeeper.client..",
                                        "org.apache.zookeeper.admin..",
                                        "org.apache.zookeeper.retry..")
                        .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
                        .layer("Tools").definedBy(tools_classes)

                        .whereLayer("Tools").mayNotBeAccessedByAnyLayer()
                        .whereLayer("Recipes").mayOnlyBeAccessedByLayers("Tools")
                        .whereLayer("Client").mayOnlyBeAccessedByLayers(
                                        "PublicApi",
                                        "Recipes", "Tools")
                        .whereLayer("PublicApi").mayOnlyBeAccessedByLayers(
                                        "Support",
                                        "Client", "Server",
                                        "Recipes", "Tools")
                        .whereLayer("Server").mayNotBeAccessedByAnyLayer()

                        // Handle legitimate administrative CLI tool reaching server quorum config
                        .ignoreDependency(simpleName("ReconfigCommand"),
                                        resideInAnyPackage("org.apache.zookeeper.server.quorum.."))

                        .as("Client-side and server-side sub-systems remain decoupled")
                        .because("Per PDF sections 1.1 and 1.7, ZooKeeper clients and "
                                        + "servers communicate only over the TCP wire protocol; "
                                        + "the public API contract types are the shared vocabulary. "
                                        + "Support utilities and shared contract types are accessible, "
                                        + "but internal implementation packages remain isolated.");

        // --- Fine-grained public-API carve-out -----------------------------------

        @ArchTest
        public static final ArchRule public_api_must_not_leak_server_types = noClasses()
                        .that(public_api_classes)
                        .should().dependOnClassesThat(server_internal_classes)
                        .as("The public client API must not leak server internals")
                        .because("The stable public API contract must never appear to depend on server-side implementation types.");

        // --- Recipe Isolation ----------------------------------------------------

        @ArchTest
        public static final ArchRule recipe_modules_are_independent = slices()
                        .matching("org.apache.zookeeper.recipes.(*)..")
                        .should().notDependOnEachOther()
                        .because("Each recipe (lock, queue, leader) is an independent coordination primitive.");

        // --- Build Integrity -----------------------------------------------------

        @ArchTest
        public static final ArchRule test_package_must_not_ship_in_production = noClasses()
                        .should().resideInAPackage("org.apache.zookeeper.test..")
                        .because("Test-scope fixtures and benchmarks must not land on the main production classpath.");
}
