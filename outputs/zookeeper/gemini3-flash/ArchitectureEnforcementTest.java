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

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = { ImportOption.DoNotIncludeTests.class })
public class ArchitectureEnforcementTest {

    // --- Layered Architecture ------------------------------------------------

    @ArchTest
    public static final ArchRule layered_architecture_is_respected =
        layeredArchitecture()
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
            .layer("Client").definedBy(
                    "org.apache.zookeeper",                // root (public API)
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
            .whereLayer("Server").mayNotBeAccessedByAnyLayer()

            .as("Client-side and server-side sub-systems remain decoupled")
            .because("Per PDF sections 1.1 and 1.7, ZooKeeper clients and "
                   + "servers communicate only over the TCP wire protocol; "
                   + "recipes are higher-order primitives built on the "
                   + "public client API (section 1.8); tooling sits above "
                   + "— never below — the public API.");

    // --- Fine-grained public-API carve-out -----------------------------------
    // Documentation-anchored; more specific .because() than the layered rule.

    @ArchTest
    public static final ArchRule public_api_must_not_leak_server_types =
        noClasses()
            .that().resideInAPackage("org.apache.zookeeper")     // root only
            .should().dependOnClassesThat(
                    resideInAPackage("org.apache.zookeeper.server.."))
            .because("The public client API (ZooKeeper, Watcher, "
                   + "KeeperException, CreateMode, ZooDefs, AsyncCallback, "
                   + "ClientCnxn) is the stable contract applications "
                   + "compile against; server-side types must never appear "
                   + "in its transitive surface.");

    // --- Recipes & tooling isolation -----------------------------------------

    @ArchTest
    public static final ArchRule recipes_only_depend_on_client_or_support =
        noClasses()
            .that().resideInAPackage("org.apache.zookeeper.recipes..")
            .should().dependOnClassesThat(resideInAnyPackage(
                    "org.apache.zookeeper.server..",
                    "org.apache.zookeeper.cli..",
                    "org.apache.zookeeper.inspector..",
                    "org.apache.zookeeper.graph.."))
            .because("Per PDF section 1.8, recipes are higher-order "
                   + "operations built on the simple public client API "
                   + "(including admin), not on server internals or tools.");
}
