/**
 * ArchitectureEnforcementTest
 *
 * <p>Enforces the architectural constraints of Apache ZooKeeper derived from
 * the official architecture documentation (Copyright © 2008-2013 The Apache
 * Software Foundation). The PDF mandates four concrete constraints:
 *
 * <ol>
 *   <li><b>C1</b> – Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).</li>
 *   <li><b>C2</b> – The request processor, replicated database, and atomic-broadcast
 *       pipeline are internal to the server (§1.7).</li>
 *   <li><b>C3</b> – Recipes are higher-order primitives built on the simple client API,
 *       never on server internals (§1.8).</li>
 *   <li><b>C4</b> – The simple API surface (create / delete / exists / getData / setData /
 *       getChildren / sync) is a narrow, stable public contract (§1.6).</li>
 * </ol>
 *
 * <p>Everything else (layered stack, monitoring / audit separation) is inference from the
 * implementation structure and is marked as such in the {@code .because()} clauses.
 *
 * <p>The layer graph is a <b>lattice</b>, not a linear stack:
 * <ul>
 *   <li><b>Infrastructure</b> is the bottom; no upward edges are permitted.</li>
 *   <li><b>Monitoring</b> is cross-cutting; it may read Server internals (audit log
 *       entries inspect {@code server.Request} objects) but is otherwise peer to Server.</li>
 *   <li><b>Server</b> and <b>Client</b> are parallel tiers communicating only over the
 *       wire; neither may compile-depend on the other.</li>
 *   <li><b>Admin</b> runs inside the Server process (Admin → Server is legal);
 *       <b>Cli</b> and <b>Recipes</b> run against the Client side.</li>
 * </ul>
 *
 * <h2>Excluded Packages and Rationale</h2>
 * <ul>
 *   <li>{@code org.apache.zookeeper.graph} – Standalone log-graph visualisation tool.
 *       A build-only developer utility with its own dependency tree (Swing, etc.) that
 *       does not participate in the production service architecture. Excluded via
 *       {@link ExcludeStandaloneTools} to prevent false-positive layer violations.</li>
 *   <li>{@code org.apache.zookeeper.inspector} – Standalone GUI inspector used for
 *       development-time debugging. Like {@code graph}, it is a build-only utility
 *       never deployed as part of the server or client runtime. Excluded via
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
     * Excludes {@code org.apache.zookeeper.graph} and {@code .inspector}
     * standalone tools. The match is a URI-substring match: ArchUnit normalises
     * all class locations (filesystem paths AND JAR entries) to forward-slashed
     * URIs, so one pattern covers both Maven {@code target/classes} layouts and
     * shaded-JAR entries.
     */
    public static final class ExcludeStandaloneTools implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/org/apache/zookeeper/graph/")
                && !location.contains("/org/apache/zookeeper/inspector/");
        }
    }

    // =========================================================================
    // LAYERED ARCHITECTURE — single authoritative rule for all layer constraints
    // =========================================================================

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .withOptionalLayers(true)

                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..")

                    .layer("Monitoring").definedBy(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..")

                    .layer("Server").definedBy("org.apache.zookeeper.server..")

                    // Root package contains the §1.6 public API surface (ZooKeeper, Watcher,
                    // KeeperException, AsyncCallback, ZooDefs). data.. holds Stat/ACL/Id which
                    // are also part of that public contract (C4). ZooKeeperMain also lives in the
                    // root package and dispatches to cli.* subcommands, so Client → Cli is permitted.
                    .layer("Client").definedBy(
                            "org.apache.zookeeper",                // root: ZooKeeper, Watcher, ZooKeeperMain, ...
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..",
                            "org.apache.zookeeper.data..")         // Stat, ACL, Id — public API data types

                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")
                    .layer("Cli").definedBy("org.apache.zookeeper.cli..")
                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    // Infrastructure is the absolute foundation; no upward edges.
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

                    // Monitoring may observe Server-internal objects (audit reads Request).
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")

                    // Server emits metrics / JMX / audit events (§1.7 internals).
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")

                    // Client may emit metrics (pluggable metrics API). ZooKeeperMain (root)
                    // dispatches to cli.* subcommands, so Client → Cli is also permitted.
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Cli")

                    // Admin is server-side: accesses Server internals but not Client.
                    .whereLayer("Admin").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server", "Admin")

                    // CLI is a client tool: connects over TCP like any other client.
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
                            "Inferred from §1.1 and §1.7: clients and servers communicate "
                            + "only over the TCP wire protocol. Admin HTTP endpoints run inside "
                            + "the server process; CLI is a client-side tool; recipes are "
                            + "higher-order primitives built on the public API (§1.8). "
                            + "Observability (metrics / jmx / audit) is cross-cutting and may "
                            + "be accessed from Server, Client, Admin, Cli, and Recipes; "
                            + "Infrastructure (common / util / compat / compatibility) is strictly "
                            + "below observability so the utility layer remains a reusable, "
                            + "dependency-free base.");

    // =========================================================================
    // FINE-GRAINED RULE — C3 expressed as a blacklist (R-05)
    // The layered rule above already forbids Recipes → Server / Admin / Cli via
    // both outgoing and incoming constraints. This rule makes the §1.8 mandate
    // explicit and is narrower than a whitelist so legitimate third-party
    // dependencies do not cause false positives.
    // =========================================================================

    /**
     * C3 (§1.8): recipes are higher-order coordination primitives built on the
     * client API, not on server internals or developer-only tooling. Expressed as
     * a blacklist so legitimate third-party libraries do not trip the rule.
     */
    @ArchTest
    static final ArchRule recipes_must_not_depend_on_zookeeper_internals =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.apache.zookeeper.server..",
                            "org.apache.zookeeper.admin..",
                            "org.apache.zookeeper.cli..",
                            "org.apache.zookeeper.graph..",
                            "org.apache.zookeeper.inspector.."))
                    .because(
                            "Per §1.8, recipes are higher-order coordination primitives built on "
                            + "the simple client API (§1.6); they must not reach into server "
                            + "internals, Admin or CLI tooling, or developer-only GUIs. "
                            + "Third-party dependencies are out of scope for this rule.");
}
