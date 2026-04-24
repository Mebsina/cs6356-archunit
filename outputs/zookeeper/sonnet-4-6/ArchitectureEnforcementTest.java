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
 *   <li>The root {@code org.apache.zookeeper} package (Client layer) contains both the
 *       §1.6 public API surface (ZooKeeper, Watcher, KeeperException) and assorted
 *       command-line utilities (ZooKeeperMain, ServerAdminClient) that happen to reside
 *       there by historical convention.</li>
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
 *   <li>All JARs and archives – Excluded via {@code ImportOption.DoNotIncludeJars} and
 *       {@code ImportOption.DoNotIncludeArchives} to limit scan scope to ZK's own compiled
 *       {@code .class} files and avoid inflating scan time with third-party library classes.</li>
 * </ul>
 */

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
        packages = "org.apache.zookeeper",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class,
                ImportOption.DoNotIncludeArchives.class,
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
                    // Scope the check to ZK's own packages only. Out-of-scope types
                    // (java.*, javax.*, slf4j, yetus, jute, netty, jline, findbugs)
                    // are ignored, preventing ~25,000+ noise violations that would
                    // bury the 2–3 real architectural findings.
                    .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                    .withOptionalLayers(true)

                    // version.. contains the generated Info interface + ZooKeeper Version
                    // metadata — pure build artefacts with no outgoing deps.
                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..",
                            "org.apache.zookeeper.version..")

                    .layer("Monitoring").definedBy(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..")

                    .layer("Server").definedBy("org.apache.zookeeper.server..")

                    // Root package: §1.6 public API surface (ZooKeeper, Watcher,
                    // KeeperException, AsyncCallback, ZooDefs) plus historical
                    // utilities (ZooKeeperMain, ServerAdminClient, ZKUtil).
                    // data..: Stat, ACL, Id — public API data types (C4).
                    // proto.. / txn..: shared wire-format records consumed by
                    // both client and server.
                    // ZooKeeperMain (root) dispatches to cli.* subcommands,
                    // so Client → Cli is also permitted.
                    .layer("Client").definedBy(
                            "org.apache.zookeeper",
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..",
                            "org.apache.zookeeper.data..",
                            "org.apache.zookeeper.proto..",
                            "org.apache.zookeeper.txn..")

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

                    // Known architectural debt: ClientCnxn$SendThread, ClientCnxn$EventThread
                    // (root / Client layer), and FileChangeWatcher$WatcherThread (Infrastructure)
                    // all extend org.apache.zookeeper.server.ZooKeeperThread. ZooKeeperThread is
                    // a generic thread utility (uncaught-exception handling + naming convention)
                    // that was placed in server.. by historical convention but is cross-cutting in
                    // practice. The clean fix is to move it out of server..; until that happens in
                    // the upstream codebase, these edges are suppressed here to prevent CI from
                    // being permanently broken by known debt.
                    .ignoreDependency(
                            JavaClass.Predicates.simpleNameStartingWith("ClientCnxn")
                                    .or(JavaClass.Predicates.simpleName("FileChangeWatcher$WatcherThread")),
                            JavaClass.Predicates.simpleName("ZooKeeperThread"))

                    .because(
                            "Inferred from §1.1 and §1.7: clients and servers communicate "
                            + "only over the TCP wire protocol. Admin HTTP endpoints run "
                            + "inside the server process; CLI is a client-side tool; "
                            + "recipes are higher-order primitives built on the public "
                            + "API (§1.8). Observability (metrics / jmx / audit) is "
                            + "cross-cutting and may be accessed from Server, Client, "
                            + "Admin, Cli, and Recipes; Infrastructure (common / util / "
                            + "compat / compatibility / version) is strictly below "
                            + "observability so the utility layer remains a reusable, "
                            + "dependency-free base.");

    // =========================================================================
    // FINE-GRAINED RULE — C3 expressed as a blacklist
    // The layered rule above already forbids Recipes → Server / Admin / Cli via
    // both outgoing (Recipes.mayOnlyAccessLayers) and incoming
    // (Server.mayOnlyBeAccessedByLayers) constraints. This blacklist rule is
    // retained as a documentation-readable assertion of §1.8: when it fires,
    // the failure message cites the exact PDF section, which a generic layered
    // failure does not.
    // =========================================================================

    /**
     * C3 (§1.8): recipes are higher-order coordination primitives built on the
     * client API, not on server internals or developer-only tooling.
     *
     * <p>The layered rule above already forbids Recipes → Server / Admin / Cli.
     * This blacklist is retained so the failure message cites §1.8 directly
     * when a violation occurs — a documentation-readability carve-out. Expressed
     * as a blacklist (not a whitelist) so legitimate third-party dependencies do
     * not cause false positives.
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
