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
 *   <li><b>Protocol</b> holds the shared wire-format records ({@code proto..}, {@code txn..},
 *       {@code data..}) and the root-package public-API / cross-tier types (Watcher,
 *       KeeperException, ZooDefs, Op, Login, etc.) consumed by both Client and Server.
 *       Sits below both tiers.</li>
 *   <li><b>Server</b> and <b>Client</b> are parallel tiers communicating only over the
 *       wire; neither may compile-depend on the other.</li>
 *   <li><b>Monitoring</b> is cross-cutting; it may read Protocol records and Server
 *       internals (audit logs decode request payloads).</li>
 *   <li><b>Admin</b> is dual-natured: its HTTP channel runs inside the Server process,
 *       but {@code ZooKeeperAdmin} extends {@code ZooKeeper} and issues admin commands
 *       over the client wire protocol — so Admin may access both Server and Client.</li>
 *   <li><b>Cli</b> is a client-side shell tool; it may access Client and Admin but not
 *       Server internals. Root-package shell classes (ZooKeeperMain, JLineZNodeCompleter,
 *       Shell, Version) are classified here.</li>
 *   <li><b>Recipes</b> are higher-order primitives built on the Client API (§1.8).</li>
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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.or;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
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

    // -------------------------------------------------------------------------
    // Root-package classification helpers (R4-03)
    //
    // The root package org.apache.zookeeper is not homogeneous. Three predicates
    // carve it into the correct layers; the residual goes to Client.
    // -------------------------------------------------------------------------

    /**
     * Root-package types that are part of the shared Protocol / public-API surface
     * consumed by both Client and Server: wire-event types, exceptions, operation
     * descriptors, ACL/ZooDefs constants, cross-tier auth, and assorted utilities
     * that the server legitimately references.
     */
    private static final DescribedPredicate<JavaClass> ROOT_PROTOCOL_TYPES =
            DescribedPredicate.describe(
                    "root-package protocol / public-API / cross-tier types",
                    c -> c.getPackageName().equals("org.apache.zookeeper")
                      && c.getName().matches(
                            "org\\.apache\\.zookeeper\\."
                          + "(Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode"
                          + "|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?|ClientInfo"
                          + "|Op(\\$.*)?|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?|StatsTrack"
                          + "|MultiResponse|DigestWatcher|Login(\\$.*)?|ClientWatchManager"
                          + "|SaslClientCallbackHandler(\\$.*)?|SaslServerPrincipal"
                          + "|Environment(\\$.*)?|ZookeeperBanner|ZKUtil(\\$.*)?)"));

    /**
     * Root-package shell-tool classes that belong in the Cli layer rather than
     * in the Client library: the interactive CLI entry point and its helpers.
     */
    private static final DescribedPredicate<JavaClass> ROOT_CLI_TYPES =
            or(name("org.apache.zookeeper.ZooKeeperMain"),
               nameMatching("org\\.apache\\.zookeeper\\.ZooKeeperMain\\$.*"),
               name("org.apache.zookeeper.JLineZNodeCompleter"),
               name("org.apache.zookeeper.Shell"),
               name("org.apache.zookeeper.Version"));

    // =========================================================================
    // LAYERED ARCHITECTURE — single authoritative rule for all layer constraints
    // =========================================================================

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    // Scope to ZK's own packages only; java.*, javax.*, slf4j, yetus,
                    // jute, netty, jline, findbugs are all out-of-scope.
                    .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                    .withOptionalLayers(true)

                    // version.. contains the generated Info interface + ZooKeeper Version
                    // metadata — pure build artefacts with no meaningful outgoing deps.
                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..",
                            "org.apache.zookeeper.version..")

                    // Shared wire-format records (proto/txn/data) and root-package
                    // API / cross-tier types (Watcher, KeeperException, ZooDefs, Op,
                    // Login, Environment, ZookeeperBanner, ZKUtil, …) consumed
                    // legitimately by both Client and Server.
                    .layer("Protocol").definedBy(
                            resideInAnyPackage(
                                    "org.apache.zookeeper.data..",
                                    "org.apache.zookeeper.proto..",
                                    "org.apache.zookeeper.txn..")
                            .or(ROOT_PROTOCOL_TYPES))

                    .layer("Monitoring").definedBy(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..")

                    .layer("Server").definedBy("org.apache.zookeeper.server..")

                    // Client = ZooKeeper, ClientCnxn*, and related library classes in the
                    // root package (minus the Protocol and Cli carve-outs) plus client.. /
                    // retry.. sub-packages.
                    .layer("Client").definedBy(
                            resideInAPackage("org.apache.zookeeper.client..")
                            .or(resideInAPackage("org.apache.zookeeper.retry.."))
                            .or(DescribedPredicate.describe(
                                    "root-package non-protocol non-cli classes",
                                    c -> c.getPackageName().equals("org.apache.zookeeper")
                                      && !ROOT_PROTOCOL_TYPES.test(c)
                                      && !ROOT_CLI_TYPES.test(c))))

                    // Admin is dual-natured: ZooKeeperAdmin extends ZooKeeper (Client),
                    // and the HTTP admin channel runs inside the Server process.
                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")

                    // Cli absorbs root-package shell tools (ZooKeeperMain, Shell, Version,
                    // JLineZNodeCompleter) in addition to org.apache.zookeeper.cli.*.
                    .layer("Cli").definedBy(
                            resideInAPackage("org.apache.zookeeper.cli..")
                            .or(ROOT_CLI_TYPES))

                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    // Infrastructure is the absolute foundation; no upward edges.
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

                    // Protocol only depends on Infrastructure (it contains pure records/types).
                    .whereLayer("Protocol").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol")

                    // Monitoring may read Protocol records (audit decodes proto/CreateMode)
                    // and Server internals (audit observes server.Request objects).
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Server")

                    // Server processes Protocol records and emits Monitoring events.
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Server")

                    // Client uses Protocol types and metrics; ZooKeeperMain (now in Cli)
                    // was responsible for the Admin edge, so Client no longer needs Admin.
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Client", "Cli")

                    // Admin → Server (HTTP channel) AND Admin → Client (ZooKeeperAdmin
                    // extends ZooKeeper and submits commands over the client wire).
                    .whereLayer("Admin").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring",
                            "Server", "Client", "Admin")

                    // Cli → Admin so ZooKeeperMain.connectToZK() can construct ZooKeeperAdmin.
                    .whereLayer("Cli").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring",
                            "Client", "Admin", "Cli")

                    // Recipes are pure client-side; they may use Protocol records and metrics.
                    .whereLayer("Recipes").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Client", "Recipes")

                    // Close the back door: only Admin, Monitoring, and Server itself
                    // may reach into Server internals.
                    .whereLayer("Server").mayOnlyBeAccessedByLayers(
                            "Admin", "Monitoring", "Server")

                    // Known architectural debt: the following server.* classes are cross-cutting
                    // utilities that happen to live under server.. by historical convention.
                    // They are used by code outside the server tier (Client, Infrastructure).
                    // The clean fix is to move each into common.. or util.. upstream;
                    // until that happens, suppress the resulting false-positive violations
                    // here with full-name predicates (ArchUnit simpleName is the nested
                    // segment only, so full names are required for nested classes).
                    .ignoreDependency(
                            DescribedPredicate.alwaysTrue(),
                            or(name("org.apache.zookeeper.server.ZooKeeperThread"),
                               name("org.apache.zookeeper.server.ByteBufferInputStream"),
                               name("org.apache.zookeeper.server.ExitCode"),
                               name("org.apache.zookeeper.server.ZooTrace"),
                               name("org.apache.zookeeper.server.EphemeralType"),
                               name("org.apache.zookeeper.server.auth.KerberosName"),
                               name("org.apache.zookeeper.server.watch.PathParentIterator")))

                    .because(
                            "Inferred from §1.1 and §1.7: clients and servers communicate "
                            + "only over the TCP wire protocol. Shared records (proto/txn/data "
                            + "+ public-API root types) sit in a neutral Protocol layer below "
                            + "both tiers. Admin HTTP endpoints run inside the server; "
                            + "ZooKeeperAdmin is a specialised client that extends ZooKeeper; "
                            + "CLI is a client-side tool; recipes (§1.8) are higher-order "
                            + "primitives on the public API. Observability (metrics/jmx/audit) "
                            + "may read Protocol records and Server internals. Infrastructure "
                            + "(common / util / compat / compatibility / version) is the "
                            + "reusable, dependency-free base.");

    // =========================================================================
    // FINE-GRAINED RULE — C3 expressed as a blacklist
    // The layered rule above already forbids Recipes → Server / Admin / Cli via
    // both outgoing (Recipes.mayOnlyAccessLayers) and incoming
    // (Server.mayOnlyBeAccessedByLayers) constraints. This blacklist is retained
    // as a documentation-readable assertion of §1.8: when it fires, the failure
    // message cites the exact PDF section, which a generic layered failure does not.
    //
    // NOTE: if the layered rule's allowed set is expanded (e.g. a new layer is
    // added), review whether this blacklist still matches the §1.8 intent.
    // Server / Admin / Cli / graph / inspector are the always-forbidden targets;
    // Protocol records are always allowed for recipes.
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
