/**
 * ArchitectureEnforcementTest
 *
 * <p>Enforces the architectural constraints of Apache ZooKeeper using ArchUnit rules
 * derived from the official ZooKeeper architecture documentation
 * (Copyright © 2008-2013 The Apache Software Foundation).
 *
 * <p>The PDF documentation actually mandates four concrete constraints:
 * <ol>
 *   <li><b>C1</b> – Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).</li>
 *   <li><b>C2</b> – The request processor, replicated database, and atomic-broadcast pipeline
 *       are internal to the server and are not part of the client contract (§1.7).</li>
 *   <li><b>C3</b> – Recipes are higher-order primitives built on the simple client API,
 *       never on server internals (§1.8).</li>
 *   <li><b>C4</b> – The simple API (create / delete / exists / getData / setData /
 *       getChildren / sync) is a narrow, stable public surface (§1.6).</li>
 * </ol>
 *
 * <p>Everything else (layered stack, monitoring / audit separation) is inference from the
 * implementation structure and is marked as such in the {@code .because()} clauses.
 *
 * <h2>Inferred Layer Hierarchy (bottom to top)</h2>
 * <ol>
 *   <li><b>Infrastructure</b> – Foundational, cross-cutting utilities.
 *       Packages: {@code org.apache.zookeeper.common},
 *                 {@code org.apache.zookeeper.util},
 *                 {@code org.apache.zookeeper.compat},
 *                 {@code org.apache.zookeeper.compatibility}</li>
 *   <li><b>Monitoring</b> – Observability and management support (JMX, pluggable metrics,
 *       audit logging). Monitoring may observe Server-internal request objects (audit).
 *       Packages: {@code org.apache.zookeeper.jmx},
 *                 {@code org.apache.zookeeper.metrics},
 *                 {@code org.apache.zookeeper.audit}</li>
 *   <li><b>Server</b> – Core coordination server: replicated in-memory data tree,
 *       transaction log, request processor, and atomic-broadcast / leader-election (§1.7).
 *       Package: {@code org.apache.zookeeper.server}</li>
 *   <li><b>Client</b> – Public client API (C4) and client-side connectivity.
 *       The root {@code org.apache.zookeeper} package contains the stable surface
 *       described in §1.6 (ZooKeeper, Watcher, KeeperException, AsyncCallback, ZooDefs).
 *       Packages: {@code org.apache.zookeeper} (root),
 *                 {@code org.apache.zookeeper.client},
 *                 {@code org.apache.zookeeper.retry}</li>
 *   <li><b>Admin</b> – Server-side administrative HTTP channel (runs inside the ensemble
 *       node, accesses {@code ZooKeeperServer} directly).
 *       Package: {@code org.apache.zookeeper.admin}</li>
 *   <li><b>Cli</b> – Client-side interactive command-line tool (opens a TCP connection
 *       like any other client; must not link-depend on server internals).
 *       Package: {@code org.apache.zookeeper.cli}</li>
 *   <li><b>Recipes</b> – Higher-level coordination primitives (leader election,
 *       distributed lock, queue) built exclusively on the public client API (§1.8).
 *       Package: {@code org.apache.zookeeper.recipes}</li>
 * </ol>
 *
 * <h2>Excluded Packages and Rationale</h2>
 * <ul>
 *   <li>{@code org.apache.zookeeper.graph} – Standalone log-graph visualisation tool.
 *       A build-only developer utility with its own dependency tree (Swing, etc.) that
 *       does not participate in the production service architecture. Excluded via
 *       {@link ExcludeStandaloneTools} to prevent false-positive layer violations.</li>
 *   <li>{@code org.apache.zookeeper.inspector} – Standalone GUI inspector used for
 *       development-time debugging. Like {@code graph}, it is a build-only utility
 *       that is never deployed as part of the server or client runtime. Excluded via
 *       {@link ExcludeStandaloneTools}.</li>
 *   <li>Test classes – Excluded via {@code ImportOption.DoNotIncludeTests}, which
 *       filters classes compiled into the Maven {@code test-classes} output directory.</li>
 * </ul>
 */

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ArchitectureEnforcementTest.ExcludeStandaloneTools.class
        }
)
public class ArchitectureEnforcementTest {

    /**
     * Excludes the {@code graph} and {@code inspector} standalone tools from the
     * analysis scope, matching the intent stated in the Javadoc header above.
     */
    public static final class ExcludeStandaloneTools implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/org/apache/zookeeper/graph/")
                && !location.contains("/org/apache/zookeeper/inspector/");
        }
    }

    // =========================================================================
    // LAYERED ARCHITECTURE RULES
    // Enforces that lower layers never depend on higher layers as inferred from
    // the ZooKeeper Components diagram and the client/server separation in §1.7.
    // =========================================================================

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .withOptionalLayers(true)

                    .layer("Infrastructure")
                            .definedBy(
                                    "org.apache.zookeeper.common..",
                                    "org.apache.zookeeper.util..",
                                    "org.apache.zookeeper.compat..",
                                    "org.apache.zookeeper.compatibility..")

                    .layer("Monitoring")
                            .definedBy(
                                    "org.apache.zookeeper.jmx..",
                                    "org.apache.zookeeper.metrics..",
                                    "org.apache.zookeeper.audit..")

                    .layer("Server")
                            .definedBy("org.apache.zookeeper.server..")

                    .layer("Client")
                            .definedBy(
                                    "org.apache.zookeeper",        // root-only: ZooKeeper, Watcher, ...
                                    "org.apache.zookeeper.client..",
                                    "org.apache.zookeeper.retry..")

                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")
                    .layer("Cli").definedBy("org.apache.zookeeper.cli..")
                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    // Infrastructure is the absolute foundation.
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

                    // Monitoring may observe Server-internal objects (audit reads Request).
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")

                    // Server emits metrics/JMX/audit events (§1.7 internals).
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")

                    // Client may emit metrics via the pluggable metrics API.
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client")

                    // Admin is server-side: accesses Server internals but not Client.
                    .whereLayer("Admin").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server", "Admin")

                    // CLI is a client tool: connects over TCP, no server-internal access.
                    .whereLayer("Cli").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Cli")

                    // Recipes are pure clients; they may emit metrics for latency tracking.
                    .whereLayer("Recipes").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Recipes")

                    // Close the back door: only Admin, Monitoring, and Server itself
                    // may reach into Server internals.
                    .whereLayer("Server").mayOnlyBeAccessedByLayers(
                            "Admin", "Monitoring", "Server")

                    .because(
                            "Inferred from §1.1 and §1.7: clients connect to ZooKeeper servers "
                            + "only over the TCP wire protocol. Admin HTTP endpoints run inside "
                            + "the server process; CLI is a client-side tool; recipes are "
                            + "higher-order primitives built on the public API (§1.8). "
                            + "Observability (metrics / jmx / audit) is cross-cutting and may "
                            + "be accessed from any tier.");

    // =========================================================================
    // FINE-GRAINED RULES — Documentation-specific constraints (C1, C3, C4)
    // =========================================================================

    /**
     * C4: The public API surface (root package) must not transitively expose server
     * internals to callers.
     */
    @ArchTest
    static final ArchRule public_api_must_not_depend_on_server =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                    .because(
                            "§1.6 describes the simple public API (create, delete, exists, getData, "
                            + "setData, getChildren, sync); its implementing classes (ZooKeeper, "
                            + "Watcher, KeeperException, AsyncCallback, ZooDefs) form the stable "
                            + "contract applications compile against and must not transitively drag "
                            + "in server internals (§1.1, §1.7).");

    /** C1/C2: The client library must not compile-depend on server internals. */
    @ArchTest
    static final ArchRule client_must_not_depend_on_server =
            noClasses()
                    .that().resideInAnyPackage(
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                    .because(
                            "Per §1.1 and §1.7, clients connect to ZooKeeper servers only over the "
                            + "TCP wire protocol. The Java client library must not compile-depend on "
                            + "server internals so the two artefacts can evolve and ship "
                            + "independently.");

    /** C1: CLI is a client-side tool and must not link-depend on server internals. */
    @ArchTest
    static final ArchRule cli_must_not_depend_on_server =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.cli..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                    .because(
                            "Per §1.1 and §1.6, the CLI opens a TCP connection and submits API calls "
                            + "like any other client; a compile edge into the server would defeat the "
                            + "wire-protocol separation described in §1.7.");

    /** C3: Recipes must not reach into server internals. */
    @ArchTest
    static final ArchRule recipes_must_not_depend_on_server =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                    .because(
                            "§1.8 describes recipes as higher-order operations built on top of the "
                            + "simple API defined in §1.6; the rules therefore infer that recipes may "
                            + "only compile against the public client API, never against server "
                            + "internals.");

    /** C3: Recipes may only depend on the documented public client API and shared utilities. */
    @ArchTest
    static final ArchRule recipes_only_use_public_client_api =
            classes()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().onlyDependOnClassesThat(
                            resideInAnyPackage(
                                    "java..", "javax..", "org.slf4j..",
                                    "org.apache.zookeeper",           // public API root (§1.6)
                                    "org.apache.zookeeper.client..",
                                    "org.apache.zookeeper.common..",
                                    "org.apache.zookeeper.util..",
                                    "org.apache.zookeeper.data..",    // Stat, ACL, Id
                                    "org.apache.zookeeper.recipes.."))
                    .because(
                            "Per §1.8, recipes are higher-order coordination primitives built "
                            + "exclusively on the simple client API (§1.6); they must not depend on "
                            + "server internals, Admin or CLI tooling, or implementation-only "
                            + "utilities.");
}
