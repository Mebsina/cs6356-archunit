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
 *   <li><b>Infrastructure</b> sits at the base; it consumes only Protocol records and
 *       itself — keeping the utility layer ({@code common}, {@code util}, {@code compat},
 *       {@code compatibility}, {@code version}) free of server/client coupling.</li>
 *   <li><b>Protocol</b> holds the shared wire-format records ({@code proto..}, {@code txn..},
 *       {@code data..}) and root-package public-API / cross-tier types (Watcher,
 *       KeeperException, ZooDefs, Op, OpResult, CreateOptions, Quotas, Login,
 *       Shell, Version, etc.) consumed by both Client and Server.</li>
 *   <li><b>Server</b> and <b>Client</b> are parallel tiers communicating only over the
 *       wire; neither may compile-depend on the other.</li>
 *   <li><b>Monitoring</b> is cross-cutting; it may read Protocol records and Server
 *       internals (audit logs decode request payloads).</li>
 *   <li><b>Admin</b> is dual-natured: its HTTP channel runs inside the Server process,
 *       but {@code ZooKeeperAdmin} extends {@code ZooKeeper} and issues admin commands
 *       over the client wire protocol — so Admin may access both Server and Client.</li>
 *   <li><b>Cli</b> is a client-side shell tool; it may access Client and Admin but not
 *       Server internals. Root-package shell classes (ZooKeeperMain, JLineZNodeCompleter)
 *       are classified here.</li>
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
    // Root-package classification helpers
    //
    // The root package org.apache.zookeeper is not homogeneous. Three predicates
    // carve it into the correct layers; the residual goes to Client.
    //
    // R5-03: JVM array types (e.g. [Lorg.apache.zookeeper.AddWatchMode; for
    // the compiler-synthesised ENUM$VALUES field) have a getName() that starts
    // with "[L" and do not match the regex. Unwrap via getBaseComponentType()
    // so enum synthetic members are classified with their enclosing class.
    // -------------------------------------------------------------------------

    /** Resolves the "real" package for a class, unwrapping JVM array types. */
    private static String rootPackageName(JavaClass c) {
        return c.isArray() ? c.getBaseComponentType().getPackageName() : c.getPackageName();
    }

    /** Resolves the "real" fully-qualified name for a class, unwrapping JVM array types. */
    private static String rootName(JavaClass c) {
        return c.isArray() ? c.getBaseComponentType().getName() : c.getName();
    }

    /**
     * Root-package types that belong in the Protocol layer: wire-event types,
     * exceptions, operation descriptors, result types, ACL / ZooDefs constants,
     * quota helpers, cross-tier auth, version metadata, process utilities, and
     * other types that the server legitimately references from the root package.
     *
     * <p>Uses the array-safe {@link #rootPackageName} / {@link #rootName}
     * helpers so compiler-synthesised enum array members ({@code ENUM$VALUES},
     * {@code values()}) are classified with their enclosing Protocol enum.
     *
     * <p>Note: {@code ZKUtil} is intentionally absent — every public method
     * takes a {@code ZooKeeper} argument and it belongs in the Client layer.
     */
    private static final DescribedPredicate<JavaClass> ROOT_PROTOCOL_TYPES =
            DescribedPredicate.describe(
                    "root-package protocol / public-API / cross-tier types",
                    c -> rootPackageName(c).equals("org.apache.zookeeper")
                      && rootName(c).matches(
                            "org\\.apache\\.zookeeper\\."
                          + "(Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode"
                          + "|CreateOptions(\\$.*)?"                        // OpResult request-side builder
                          + "|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?|ClientInfo|StatsTrack"
                          + "|Op(\\$.*)?|OpResult(\\$.*)?"                  // response-side multi-op results
                          + "|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?"
                          + "|MultiResponse|DeleteContainerRequest|Quotas"  // jute record + quota-path util
                          + "|DigestWatcher|Login(\\$.*)?|ClientWatchManager"
                          + "|SaslClientCallbackHandler(\\$.*)?"
                          + "|SaslServerPrincipal(\\$.*)?"                  // includes nested WrapperInet* classes
                          + "|Environment(\\$.*)?|ZookeeperBanner"
                          + "|Shell(\\$.*)?|Version(\\$.*)?)"));            // process-exec util + version metadata

    /**
     * Root-package shell-tool classes that belong in the Cli layer.
     * Shell and Version are NOT included here — they are process/version
     * utilities consumed by Server and Protocol, not interactive CLI tools.
     */
    private static final DescribedPredicate<JavaClass> ROOT_CLI_TYPES =
            or(name("org.apache.zookeeper.ZooKeeperMain"),
               nameMatching("org\\.apache\\.zookeeper\\.ZooKeeperMain\\$.*"),
               name("org.apache.zookeeper.JLineZNodeCompleter"));

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
                    // Infrastructure may access Protocol records (compat.ProtocolManager
                    // translates proto.ConnectRequest/Response; util.SecurityUtils uses
                    // SaslClientCallbackHandler).
                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..",
                            "org.apache.zookeeper.version..")

                    // Shared wire-format records (proto/txn/data) and root-package
                    // public-API / cross-tier types consumed by both Client and Server.
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

                    // Client = ZooKeeper, ClientCnxn*, ZKUtil, and related library
                    // classes in the root package (minus the Protocol and Cli
                    // carve-outs above) plus client.. / retry.. sub-packages.
                    // Uses array-safe helpers so enum synthetic members are classified
                    // alongside their enclosing class.
                    .layer("Client").definedBy(
                            resideInAPackage("org.apache.zookeeper.client..")
                            .or(resideInAPackage("org.apache.zookeeper.retry.."))
                            .or(DescribedPredicate.describe(
                                    "root-package non-protocol non-cli classes",
                                    c -> rootPackageName(c).equals("org.apache.zookeeper")
                                      && !ROOT_PROTOCOL_TYPES.test(c)
                                      && !ROOT_CLI_TYPES.test(c))))

                    // Admin is dual-natured: ZooKeeperAdmin extends ZooKeeper (Client),
                    // and the HTTP admin channel runs inside the Server process.
                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")

                    // Cli absorbs root-package shell tools (ZooKeeperMain, JLineZNodeCompleter)
                    // in addition to org.apache.zookeeper.cli.*.
                    // Shell and Version are Protocol (not CLI tools), see ROOT_CLI_TYPES.
                    .layer("Cli").definedBy(
                            resideInAPackage("org.apache.zookeeper.cli..")
                            .or(ROOT_CLI_TYPES))

                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    // Infrastructure consumes only Protocol records and itself.
                    .whereLayer("Infrastructure").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol")

                    // Protocol only depends on Infrastructure (pure records/types).
                    .whereLayer("Protocol").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol")

                    // Monitoring may read Protocol records (audit decodes proto/CreateMode)
                    // and Server internals (audit observes server.Request objects).
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Server")

                    // Server processes Protocol records and emits Monitoring events.
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Server")

                    // Client uses Protocol types and metrics. ZooKeeperBuilder.buildAdmin()
                    // constructs ZooKeeperAdmin, so Client → Admin is required.
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring",
                            "Client", "Cli", "Admin")

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
                    // utilities that happen to live under server.. by historical convention and
                    // are used by code outside the server tier (Client, Infrastructure, Protocol).
                    // The clean fix is to move each into common.., util.., or a shared package
                    // upstream; until that happens, suppress the violations here with full-name
                    // predicates so CI is not permanently red on known debt.
                    .ignoreDependency(
                            DescribedPredicate.alwaysTrue(),
                            or(name("org.apache.zookeeper.server.ZooKeeperThread"),
                               name("org.apache.zookeeper.server.ByteBufferInputStream"),
                               name("org.apache.zookeeper.server.ExitCode"),
                               name("org.apache.zookeeper.server.ZooTrace"),
                               name("org.apache.zookeeper.server.EphemeralType"),
                               name("org.apache.zookeeper.server.auth.KerberosName"),
                               name("org.apache.zookeeper.server.auth.ProviderRegistry"),
                               name("org.apache.zookeeper.server.watch.PathParentIterator"),
                               nameMatching("org\\.apache\\.zookeeper\\.server\\.util\\.VerifyingFileFactory(\\$.*)?")
                            ))

                    .because(
                            "Inferred from §1.1 and §1.7: clients and servers communicate "
                            + "only over the TCP wire protocol. Shared records (proto/txn/data "
                            + "+ public-API root types) sit in a neutral Protocol layer below "
                            + "both tiers; Infrastructure (common / util / compat / compatibility "
                            + "/ version) sits below observability and consumes only Protocol "
                            + "records — keeping the utility base reusable and free of "
                            + "server/client coupling. Admin HTTP endpoints run inside the server; "
                            + "ZooKeeperAdmin is a specialised client that extends ZooKeeper; "
                            + "CLI is a client-side tool; recipes (§1.8) are higher-order "
                            + "primitives on the public API. Observability (metrics/jmx/audit) "
                            + "may read Protocol records and Server internals.");

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
