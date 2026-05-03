/**
 * ArchitectureEnforcementTest.java
 *
 * Enforces architectural boundaries of Apache Kafka inferred from the project's
 * package structure. The supplied architecture document (inputs/java/7_apache_kafka.pdf)
 * describes only Kafka Streams runtime semantics (stream partitions, threading,
 * state stores, fault tolerance) and does not directly document a layered package
 * hierarchy for org.apache.kafka.*. All layer assignments and dependency constraints
 * below are inferred from package naming conventions and the Kafka module layout.
 * Validate against authoritative Kafka design documentation (KIPs, design.html)
 * before treating any rule as CI-authoritative.
 *
 * -------------------------------------------------------------------------
 * LAYER HIERARCHY (bottom to top)
 * -------------------------------------------------------------------------
 *
 * 1. Core (bottom)
 *    Foundational shared utilities depended upon by every other layer.
 *    Includes the common types, security primitives, and the sub-packages of
 *    org.apache.kafka.server that are published as shared libraries rather than
 *    broker-runtime code (common, util, metrics, fault, authorizer, immutable,
 *    config, record, storage.log, log.remote.storage).
 *    NOTE: org.apache.kafka.message, config, logger, deferred, queue, timeline
 *    appear in the package listing but contain no classes in the scanned
 *    classpath; they are omitted from layer definitions to avoid vacuous rules.
 *    Packages:
 *      - org.apache.kafka.common..                    (shared types, serialization)
 *      - org.apache.kafka.security..                  (auth, ACL, SSL primitives)
 *      - org.apache.kafka.server.common..             (ApiMessageAndVersion, MetadataVersion, OffsetAndEpoch)
 *      - org.apache.kafka.server.util..               (Scheduler, ShutdownableThread, Timer)
 *      - org.apache.kafka.server.metrics..            (KafkaMetricsGroup, TimeRatio)
 *      - org.apache.kafka.server.fault..              (FaultHandler, FaultHandlerException)
 *      - org.apache.kafka.server.authorizer..         (Authorizer SPI, AuthorizationResult, AclDeleteResult)
 *      - org.apache.kafka.server.immutable..          (ImmutableMap, ImmutableNavigableSet)
 *      - org.apache.kafka.server.config..             (ConfigSynonym, ServerLogConfigs)
 *      - org.apache.kafka.server.record..             (record utilities)
 *      - org.apache.kafka.server.storage.log..        (storage log abstractions)
 *      - org.apache.kafka.server.log.remote.storage.. (RemoteStorageManager SPI)
 *
 * 2. Storage
 *    Manages durable persistence of log segments and state store snapshots.
 *    The tiered package appears in the listing but is empty in the scanned
 *    classpath and is therefore excluded.
 *    Depends on Core.
 *    Packages:
 *      - org.apache.kafka.storage..  (log segment and store abstractions)
 *      - org.apache.kafka.snapshot.. (state store snapshot management)
 *
 * 3. Consensus
 *    KRaft (Kafka Raft) consensus protocol engine, metadata record definitions,
 *    and the point-in-time cluster metadata image built from the Raft log.
 *    Depends on Core and Storage.
 *    Packages:
 *      - org.apache.kafka.raft..     (KRaft consensus protocol engine)
 *      - org.apache.kafka.metadata.. (broker/topic/partition metadata records)
 *      - org.apache.kafka.image..    (point-in-time cluster metadata image)
 *    NOTE: org.apache.kafka.metadata.properties (MetaProperties, PropertiesUtils)
 *    is a bootstrap identity library also depended upon by Storage. Dependencies
 *    from Storage to metadata.properties and to the top-level metadata package
 *    (ConfigRepository SAM) are acknowledged legitimate uses and are excluded
 *    from the layered rule via ignoreDependency.
 *
 * 4. Server
 *    Broker runtime: KRaft controller quorum, NIO network I/O layer, and the
 *    broker lifecycle classes at the top-level server package. The coordinator
 *    package appears in the listing but is empty in the scanned classpath and
 *    is therefore excluded from the layer definition.
 *    Depends on Core, Storage, and Consensus.
 *    Packages:
 *      - org.apache.kafka.server                         (broker lifecycle, top-level only)
 *      - org.apache.kafka.server.share..                 (share-group runtime)
 *      - org.apache.kafka.server.transaction..           (transaction manager)
 *      - org.apache.kafka.server.log.remote.metadata.storage.. (tiered metadata storage)
 *      - org.apache.kafka.controller..                   (KRaft controller quorum)
 *      - org.apache.kafka.network..                      (NIO SocketServer, Processors)
 *
 * 5. API (top)
 *    Client-facing and operator-facing surface. The api and admin packages
 *    appear in the listing but are empty in the scanned classpath. The admin
 *    client physically lives in org.apache.kafka.clients.admin and is therefore
 *    covered by the clients.. glob. The tools package legitimately depends on
 *    connect for the connect-plugin-path CLI and is exempted from that rule.
 *    Packages:
 *      - org.apache.kafka.clients.. (producer, consumer, admin client incl. clients.admin)
 *      - org.apache.kafka.connect.. (Kafka Connect framework)
 *      - org.apache.kafka.streams.. (Kafka Streams DSL and runtime)
 *      - org.apache.kafka.tools..   (command-line tooling)
 *      - org.apache.kafka.shell..   (interactive Kafka shell)
 *
 * -------------------------------------------------------------------------
 * EXCLUDED PACKAGES AND RATIONALE
 * -------------------------------------------------------------------------
 *
 *   - org.apache.kafka.test
 *       Test utility classes (MockProducer, TestUtils) compiled into a
 *       test-fixtures artifact; not production code subject to layering rules.
 *
 *   - org.apache.kafka.jmh
 *       JMH benchmark classes; build-time performance measurement utilities
 *       not present at production runtime. Excluded via TrogdorAndJmhExclusion.
 *
 *   - org.apache.kafka.trogdor
 *       Fault-injection and distributed test coordination framework used only
 *       in system/integration test environments. Excluded via TrogdorAndJmhExclusion.
 *
 *   - org.apache.kafka.api, org.apache.kafka.admin, org.apache.kafka.coordinator,
 *     org.apache.kafka.tiered, org.apache.kafka.message, org.apache.kafka.config,
 *     org.apache.kafka.logger, org.apache.kafka.deferred, org.apache.kafka.queue,
 *     org.apache.kafka.timeline
 *       All of these packages appear in the package listing but contain zero
 *       classes in the scanned classpath. They are omitted from layer definitions
 *       to prevent vacuous rules that either fail (failOnEmptyShould) or silently
 *       enforce nothing.
 */
package org.archunit.kafka;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ArchitectureEnforcementTest.TrogdorAndJmhExclusion.class
    }
)
public class ArchitectureEnforcementTest {

    /**
     * Excludes JMH benchmarks and the Trogdor test framework from analysis.
     * These packages contain no production code but would generate spurious
     * layering violations if scanned (SCOPE-01).
     */
    public static final class TrogdorAndJmhExclusion implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/jmh/") && !location.contains("/trogdor/");
        }
    }

    // =========================================================================
    // LAYERED ARCHITECTURE RULE
    // Enforces the five-layer hierarchy: Core < Storage < Consensus < Server < API.
    // Layer definitions have been corrected from the initial generation:
    //   - MAP-01: org.apache.kafka.admin removed (empty; admin client is clients.admin)
    //   - MAP-02: org.apache.kafka.server split into Core shared-util sub-packages
    //             and Server runtime-only sub-packages to eliminate 1,467 false positives
    //   - VAC-02: Empty layer members removed (api, admin, coordinator, tiered, message,
    //             config, logger, deferred, queue, timeline)
    //   - MAP-03: storage -> metadata.properties and storage -> metadata (ConfigRepository)
    //             are acknowledged legitimate uses; excluded via ignoreDependency
    // =========================================================================

    @ArchTest
    public static final ArchRule kafka_layered_architecture =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()

            .layer("Core").definedBy(
                "org.apache.kafka.common..",
                "org.apache.kafka.security..",
                "org.apache.kafka.server.common..",
                "org.apache.kafka.server.util..",
                "org.apache.kafka.server.metrics..",
                "org.apache.kafka.server.fault..",
                "org.apache.kafka.server.authorizer..",
                "org.apache.kafka.server.immutable..",
                "org.apache.kafka.server.config..",
                "org.apache.kafka.server.record..",
                "org.apache.kafka.server.storage.log..",
                "org.apache.kafka.server.log.remote.storage.."
            )
            .layer("Storage").definedBy(
                "org.apache.kafka.storage..",
                "org.apache.kafka.snapshot.."
            )
            .layer("Consensus").definedBy(
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image.."
            )
            .layer("Server").definedBy(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.server.log.remote.metadata.storage..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network.."
            )
            .layer("API").definedBy(
                "org.apache.kafka.clients..",
                "org.apache.kafka.connect..",
                "org.apache.kafka.streams..",
                "org.apache.kafka.tools..",
                "org.apache.kafka.shell.."
            )

            .whereLayer("API").mayNotBeAccessedByAnyLayer()
            .whereLayer("Server").mayOnlyBeAccessedByLayers("API")
            .whereLayer("Consensus").mayOnlyBeAccessedByLayers("Server", "API")
            .whereLayer("Storage").mayOnlyBeAccessedByLayers("Consensus", "Server", "API")
            .whereLayer("Core").mayOnlyBeAccessedByLayers("Storage", "Consensus", "Server", "API")

            // MAP-03: storage.LogManager -> metadata.ConfigRepository (SAM interface) and
            // storage -> metadata.properties.MetaProperties (bootstrap identity helper) are
            // deliberate downward uses via stable interfaces; not true layering violations.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.properties.."))
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata"))

            .because("Inferred from package naming conventions (not explicitly stated in the supplied" +
                     " Kafka Streams Architecture PDF). A five-layer hierarchy is assumed: Core," +
                     " Storage, Consensus, Server, and API. Each layer may only depend on layers" +
                     " below it. Validate against KIP-500 / KIP-405 / design.html before treating" +
                     " this as authoritative.");

    // =========================================================================
    // LAYER COVERAGE GUARD (LAY-01)
    // Ensures that every scanned production class belongs to at least one
    // defined layer. Without this, consideringOnlyDependenciesInLayers() silently
    // ignores classes in unmapped packages, masking future architectural drift
    // when new top-level packages are added to the codebase.
    // =========================================================================

    @ArchTest
    public static final ArchRule every_production_class_must_be_in_a_layer =
        classes()
            .that().resideInAPackage("org.apache.kafka..")
            .and().resideOutsideOfPackages(
                "org.apache.kafka.test..",
                "org.apache.kafka.jmh..",
                "org.apache.kafka.trogdor.."
            )
            .should().resideInAnyPackage(
                "org.apache.kafka.common..",
                "org.apache.kafka.security..",
                "org.apache.kafka.server..",
                "org.apache.kafka.storage..",
                "org.apache.kafka.snapshot..",
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network..",
                "org.apache.kafka.clients..",
                "org.apache.kafka.connect..",
                "org.apache.kafka.streams..",
                "org.apache.kafka.tools..",
                "org.apache.kafka.shell.."
            )
            .because("Inferred from package naming. New top-level packages must be explicitly" +
                     " assigned to a layer; otherwise consideringOnlyDependenciesInLayers() will" +
                     " silently exempt their classes from all dependency checks.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: API LAYER
    // The API layer contains several independently-deployable modules that must
    // not form sibling cross-dependencies. Rules cover both directions for each
    // pair. NOTE: tools -> connect is intentionally ABSENT (FP-01): the
    // connect-plugin-path CLI (ConnectPluginPath, ManifestWorkspace) in the tools
    // package legitimately depends on connect.runtime.isolation by design.
    // NOTE: The phantom admin package (MAP-01) is replaced by clients.admin rules.
    // =========================================================================

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. Kafka Streams is a standalone stream processing" +
                     " library embedded in application processes; it must not depend on the Kafka" +
                     " Connect framework, which is a separate data-integration runtime. Such a" +
                     " dependency would force all Streams users to transitively pull in Connect.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. Kafka Connect is a general-purpose connector" +
                     " runtime with no architectural dependency on Kafka Streams. Importing Streams" +
                     " types from Connect would create a circular sibling coupling at the top of the" +
                     " dependency hierarchy and bloat the Connect artifact.");

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .because("Inferred from package naming. Kafka Streams is an embeddable stream processing" +
                     " library and must not import command-line broker-management tools. Such a" +
                     " dependency would pull operational utilities into the application library.");

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_shell =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
            .because("Inferred from package naming. The Kafka shell is an administrative REPL for" +
                     " cluster inspection; Kafka Streams must not import shell utilities, as there" +
                     " is no architectural relationship between stream processing and the admin shell.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .because("Inferred from package naming. The Kafka Connect framework must not depend on" +
                     " broker-management CLI tools. Connect manages its own REST-based tooling and" +
                     " must remain independently deployable from the tools artifact.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_shell =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
            .because("Inferred from package naming. The Kafka Connect framework must not import the" +
                     " interactive admin shell. These are parallel API-layer modules with unrelated" +
                     " responsibilities that must remain independently deployable.");

    @ArchTest
    public static final ArchRule tools_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. Kafka CLI tools are broker-management utilities" +
                     " that communicate via AdminClient or the protocol layer. They must not import" +
                     " the Kafka Streams library, which is an application-level stream processing" +
                     " framework unrelated to cluster tooling.");

    @ArchTest
    public static final ArchRule tools_must_not_depend_on_shell =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
            .because("Inferred from package naming. Command-line tools and the interactive shell are" +
                     " parallel operator-facing modules. Tools must not import shell internals;");

    @ArchTest
    public static final ArchRule shell_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.shell..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. The Kafka shell is an administrative REPL for" +
                     " inspecting broker and metadata state. It must not import the Kafka Streams" +
                     " runtime, which is an application-level library unrelated to cluster admin.");

    @ArchTest
    public static final ArchRule shell_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.shell..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. The Kafka shell operates on broker metadata and" +
                     " cluster topology. Kafka Connect is a separate runtime for data-integration" +
                     " connectors; importing Connect types into the shell would couple two unrelated" +
                     " operational subsystems.");

    @ArchTest
    public static final ArchRule shell_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.shell..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .because("Inferred from package naming. The interactive shell and CLI tools are parallel" +
                     " operator-facing modules. The shell must not import tool implementations;");

    // MAP-01: The admin client physically lives at org.apache.kafka.clients.admin,
    // not in a separate org.apache.kafka.admin package (which is empty). Rules
    // are therefore written against clients.admin rather than admin.
    @ArchTest
    public static final ArchRule clients_admin_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients.admin..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. The AdminClient must not pull in the Kafka" +
                     " Streams runtime; doing so would force every admin-tool user to transitively" +
                     " depend on the stream processing library.");

    @ArchTest
    public static final ArchRule clients_admin_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients.admin..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. The AdminClient must not pull in the Kafka" +
                     " Connect runtime, which is a separate independently-deployable framework.");

    // TRANS-01: Prevent transitive bypass of streams/connect isolation through clients.
    // Streams already depends on clients; a reverse clients -> streams cycle would
    // make Streams a hard transitive dependency of tools and shell.
    @ArchTest
    public static final ArchRule clients_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. The clients package is the foundational" +
                     " producer/consumer library; it must not import Kafka Streams, which depends" +
                     " on clients. A reverse dependency would create a cycle and force Streams onto" +
                     " every client user transitively.");

    @ArchTest
    public static final ArchRule clients_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. The clients package must not import the Kafka" +
                     " Connect framework. Connect depends on clients; a reverse dependency would" +
                     " create a cycle and pull Connect infrastructure into every Kafka client.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: SERVER LAYER
    // The controller and network sub-modules serve distinct roles and must not
    // cross-import each other or the broker runtime server classes.
    // NOTE: coordinator_must_not_depend_on_controller is removed (VAC-01) because
    // org.apache.kafka.coordinator.. contains no classes in the scanned classpath,
    // causing ArchUnit to fail the rule with failOnEmptyShould.
    // =========================================================================

    @ArchTest
    public static final ArchRule controller_must_not_depend_on_coordinator =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.controller..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
            .because("Inferred from package naming. The KRaft controller manages cluster-level" +
                     " metadata (topic/partition assignments, broker epochs) via the Raft log." +
                     " Importing coordinator types would conflate cluster management with group" +
                     " coordination and create intra-Server coupling.");

    @ArchTest
    public static final ArchRule network_must_not_depend_on_controller =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.network..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
            .because("Inferred from package naming. The NIO network layer (SocketServer, Acceptors," +
                     " Processors) is a generic I/O multiplexer and must not import controller" +
                     " internals. Doing so would make the network layer aware of high-level cluster" +
                     " management logic that should be opaque to it.");

    // NAR-01: network -> server (broker lifecycle) also forbidden, not just controller/coordinator.
    @ArchTest
    public static final ArchRule network_must_not_depend_on_server_runtime =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.network..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.coordinator.."
            )
            .because("Inferred from package naming. The NIO network layer must remain agnostic of" +
                     " broker request handling, share-group logic, transaction management, KRaft" +
                     " controller operations, and group coordinator state machines. Coupling the" +
                     " network layer to any of these would prevent its reuse across broker roles.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: CONSENSUS LAYER
    // The Raft engine is a general-purpose replicated log that must not import
    // its consumers. Metadata must not import the image built on top of it.
    // After MAP-02, server.common/util/fault are in Core and are therefore
    // allowed targets for Raft, metadata, and image.
    // =========================================================================

    @ArchTest
    public static final ArchRule raft_must_not_depend_on_metadata =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata..")
            .because("Inferred from package naming. The KRaft Raft engine replicates opaque records" +
                     " and must not be aware of the semantic content of those records (metadata" +
                     " changes). Importing metadata types into Raft would destroy reusability and" +
                     " create an upward dependency from the protocol engine to its consumers.");

    @ArchTest
    public static final ArchRule raft_must_not_depend_on_image =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
            .because("Inferred from package naming. The cluster metadata image is a point-in-time" +
                     " snapshot assembled from the Raft log. Raft must not import image types; the" +
                     " image layer is a higher-level consumer of the log, not a Raft dependency.");

    // NAR-02: Raft also must not depend on the Server-layer modules above it.
    @ArchTest
    public static final ArchRule raft_must_not_depend_on_higher_layers =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.controller..",
                "org.apache.kafka.network..",
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction.."
            )
            .because("Inferred from package naming. Raft is a general-purpose consensus log and must" +
                     " not depend on broker request handling, KRaft controller logic, or coordinator" +
                     " state machines. Raft may use server.common/util/fault shared utilities" +
                     " (which are in Core) but must not import the broker runtime.");

    // NAR-02: metadata -> image upward dependency is also unguarded without this rule.
    @ArchTest
    public static final ArchRule metadata_must_not_depend_on_image =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.metadata..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
            .because("Inferred from package naming. The cluster image is built on top of metadata" +
                     " records; metadata must not import image types, which would create an upward" +
                     " circular dependency within the Consensus layer.");

    // MAP-02 / FP-02: renamed from metadata_must_not_depend_on_server. After splitting
    // server.common/util/fault/authorizer into Core, metadata -> those sub-packages is
    // a legitimate Consensus -> Core dependency. Only the broker runtime is forbidden.
    @ArchTest
    public static final ArchRule metadata_must_not_depend_on_server_runtime =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.metadata..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network.."
            )
            .because("Inferred from package naming. Metadata is a low-level Consensus-layer" +
                     " component; it may use server.common/util/fault shared utilities (in Core)" +
                     " but must not depend on the broker request-handling runtime, KRaft controller" +
                     " operations, or NIO networking.");

    // COV-02: image has no dedicated isolation rule in the initial generation.
    @ArchTest
    public static final ArchRule image_must_not_depend_on_server_runtime =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.image..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network.."
            )
            .because("Inferred from package naming. The cluster metadata image is a low-level" +
                     " Consensus-layer snapshot. It may use server.common/util/fault/immutable" +
                     " shared utilities (in Core) but must not import broker runtime classes," +
                     " controller logic, or NIO networking.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: CORE LAYER ISOLATION
    // Core packages are the foundation of the entire dependency graph.
    // After MAP-02, server.common/util/fault/authorizer/immutable/config are
    // part of Core, so common -> those packages is allowed (Core -> Core).
    // The rules below restrict Core from depending on higher layers only.
    // =========================================================================

    // VAC-03: tiered.. removed (empty in scanned classpath).
    @ArchTest
    public static final ArchRule common_must_not_depend_on_storage =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.storage..",
                "org.apache.kafka.snapshot.."
            )
            .because("Inferred from package naming. The common package is the universal utility" +
                     " foundation shared by every Kafka module. Importing storage or snapshot types" +
                     " would propagate a persistence coupling to every consumer of common, making it" +
                     " impossible to use common utilities in lightweight clients without dragging in" +
                     " the full persistence infrastructure.");

    @ArchTest
    public static final ArchRule common_must_not_depend_on_consensus =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image.."
            )
            .because("Inferred from package naming. The common package must be free of Consensus-layer" +
                     " types. Importing Raft or metadata classes into common would force every client" +
                     " and server module to transitively depend on the KRaft consensus engine.");

    // MAP-02 / FP-02: renamed from common_must_not_depend_on_server_layer. After moving
    // server.authorizer, server.common, server.util, etc. to Core, common ->
    // those sub-packages is Core -> Core and is no longer forbidden. Only the
    // actual broker runtime packages remain off-limits.
    @ArchTest
    public static final ArchRule common_must_not_depend_on_server_runtime =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network.."
            )
            .because("Inferred from package naming. The common package supplies foundational types" +
                     " (record schemas, serializers, error codes) to all modules. It may use the" +
                     " public server.authorizer SPI and server.common/util utilities (all in Core)," +
                     " but must not import the broker runtime, KRaft controller, or NIO networking.");

    // MAP-03: metadata.. removed from the target list. storage -> metadata.properties
    // (MetaProperties) and storage -> metadata (ConfigRepository SAM) are legitimate
    // downward uses of stable interfaces and identity utilities; they are also excluded
    // from the layered rule via ignoreDependency above.
    @ArchTest
    public static final ArchRule storage_must_not_depend_on_consensus =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.raft..",
                "org.apache.kafka.image.."
            )
            .because("Inferred from package naming. The storage layer manages physical log structure" +
                     " and persistence. It must not import Raft consensus internals or the cluster" +
                     " metadata image; those are higher-layer components. Storage may legitimately" +
                     " use metadata.properties (bootstrap identity) and the metadata.ConfigRepository" +
                     " SAM (dependency inversion), which are excluded from this rule.");

    // MAP-02: server.. changed to only the runtime sub-packages. After moving
    // server.common/util/fault/authorizer etc. to Core, storage -> those is fine.
    @ArchTest
    public static final ArchRule storage_must_not_depend_on_server_layer =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network.."
            )
            .because("Inferred from package naming. The storage layer implements durable persistence" +
                     " and must remain independent of the broker lifecycle, KRaft controller logic," +
                     " and NIO networking. Storage may use server.common/util/fault shared utilities" +
                     " (in Core) but must not import the broker request-handling runtime.");

    // VAC-03: admin.. replaced by clients.. (admin client is in clients.admin).
    @ArchTest
    public static final ArchRule storage_must_not_depend_on_api_layer =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.clients..",
                "org.apache.kafka.connect..",
                "org.apache.kafka.streams..",
                "org.apache.kafka.tools..",
                "org.apache.kafka.shell.."
            )
            .because("Inferred from package naming. The storage layer is a low-level persistence" +
                     " subsystem and must not import client or application-layer types. Importing" +
                     " producer/consumer clients, Connect, Streams, or CLI tools would invert the" +
                     " dependency hierarchy and make the persistence foundation aware of the" +
                     " application layers it serves.");
}
