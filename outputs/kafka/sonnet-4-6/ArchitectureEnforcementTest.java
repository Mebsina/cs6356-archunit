/**
 * ArchitectureEnforcementTest.java
 *
 * Enforces the architectural constraints of Apache Kafka as described in the
 * Kafka Streams Architecture documentation and derived from the project's
 * top-level package structure.
 *
 * -------------------------------------------------------------------------
 * LAYER HIERARCHY (bottom to top)
 * -------------------------------------------------------------------------
 *
 * 1. Core (bottom)
 *    The foundational layer containing shared utilities, protocol message
 *    definitions, configuration primitives, logging abstractions, deferred
 *    execution, event queuing, timeline utilities, and security primitives.
 *    No other production layer may be imported from here.
 *    Packages:
 *      - org.apache.kafka.common     (shared data types, serialization, utils)
 *      - org.apache.kafka.message    (generated protocol message schemas)
 *      - org.apache.kafka.config     (configuration key/value primitives)
 *      - org.apache.kafka.logger     (logging facade and context)
 *      - org.apache.kafka.deferred   (deferred/async result primitives)
 *      - org.apache.kafka.queue      (event queue abstractions)
 *      - org.apache.kafka.timeline   (versioned timeline data structures)
 *      - org.apache.kafka.security   (auth, ACL, and SSL primitives)
 *
 * 2. Storage
 *    Manages durable persistence of log segments, state store changelog
 *    topics (used by Kafka Streams for fault-tolerant local state), and
 *    tiered (remote) storage. Depends on Core only.
 *    Packages:
 *      - org.apache.kafka.storage    (log segment and store abstractions)
 *      - org.apache.kafka.snapshot   (state store snapshot management)
 *      - org.apache.kafka.tiered     (tiered/remote storage integration)
 *
 * 3. Consensus
 *    Implements the KRaft (Kafka Raft) consensus protocol and the metadata
 *    cluster image that replaces ZooKeeper. Raft provides the replicated log
 *    over which metadata changes are committed; the metadata and image layers
 *    build the in-memory cluster state view on top of that log.
 *    Depends on Core and Storage.
 *    Packages:
 *      - org.apache.kafka.raft       (KRaft consensus protocol engine)
 *      - org.apache.kafka.metadata   (broker/topic/partition metadata)
 *      - org.apache.kafka.image      (point-in-time cluster metadata image)
 *
 * 4. Server
 *    The broker runtime: the controller quorum (KRaft leader election and
 *    metadata propagation), group/transaction coordinators, the network
 *    I/O layer (Acceptors, Processors, SocketServer), and the broker-side
 *    server logic. This layer orchestrates Storage and Consensus subsystems.
 *    Depends on Core, Storage, and Consensus.
 *    Packages:
 *      - org.apache.kafka.server     (broker lifecycle and request handling)
 *      - org.apache.kafka.controller (KRaft controller quorum management)
 *      - org.apache.kafka.coordinator(group and transaction coordinators)
 *      - org.apache.kafka.network    (NIO network layer, channel builders)
 *
 * 5. API (top)
 *    Client-facing and operator-facing surface: the producer/consumer client
 *    library, the Admin client, Kafka Streams stream processing DSL and
 *    Processor API, Kafka Connect (source/sink connectors), command-line
 *    tools, and the interactive shell. These components depend on all lower
 *    layers but must not form circular dependencies among themselves.
 *    Packages:
 *      - org.apache.kafka.api        (public API contracts)
 *      - org.apache.kafka.clients    (producer, consumer, admin client impl)
 *      - org.apache.kafka.admin      (admin client high-level API)
 *      - org.apache.kafka.connect    (Kafka Connect framework)
 *      - org.apache.kafka.streams    (Kafka Streams DSL and runtime)
 *      - org.apache.kafka.tools      (command-line tooling)
 *      - org.apache.kafka.shell      (interactive Kafka shell)
 *
 * -------------------------------------------------------------------------
 * EXCLUDED PACKAGES AND RATIONALE
 * -------------------------------------------------------------------------
 *
 *   - org.apache.kafka.test
 *       Test utility classes (e.g., MockProducer, TestUtils) that exist
 *       exclusively to support unit and integration tests. They are compiled
 *       into a separate test-fixtures artifact and must not be treated as
 *       production code subject to layering rules.
 *
 *   - org.apache.kafka.jmh
 *       JMH (Java Microbenchmark Harness) benchmark classes. These are
 *       build-time performance measurement utilities that depend on JMH
 *       annotations and infrastructure not present at production runtime.
 *       Including them would produce false-positive layering violations.
 *
 *   - org.apache.kafka.trogdor
 *       Trogdor is a fault-injection and distributed test coordination
 *       framework used only in system/integration test environments. It has
 *       its own agent/coordinator model unrelated to production architecture
 *       and would introduce spurious cross-layer dependency noise if scanned.
 */
package org.archunit.kafka;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {
        ImportOption.DoNotIncludeTests.class
    }
)
public class ArchitectureEnforcementTest {

    // =========================================================================
    // SHARED IMPORTED CLASSES
    // Used by fine-grained rules that need explicit package-level control.
    // Excludes test, jmh, and trogdor packages from analysis scope.
    // =========================================================================

    private static final JavaClasses KAFKA_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests.INSTANCE)
        .withImportOption(location -> !location.contains("/test/")
                                   && !location.contains("/jmh/")
                                   && !location.contains("/trogdor/"))
        .importPackages("org.apache.kafka");

    // =========================================================================
    // LAYERED ARCHITECTURE RULE
    // Enforces the five-layer hierarchy described in the file header.
    // Lower layers must not depend on higher layers. Parallel sibling layers
    // inside the API tier are restricted from cross-accessing each other via
    // the fine-grained noClasses() rules defined further below.
    // =========================================================================

    @ArchTest
    public static final ArchRule kafka_layered_architecture =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()

            .layer("Core").definedBy(
                "org.apache.kafka.common..",
                "org.apache.kafka.message..",
                "org.apache.kafka.config..",
                "org.apache.kafka.logger..",
                "org.apache.kafka.deferred..",
                "org.apache.kafka.queue..",
                "org.apache.kafka.timeline..",
                "org.apache.kafka.security.."
            )
            .layer("Storage").definedBy(
                "org.apache.kafka.storage..",
                "org.apache.kafka.snapshot..",
                "org.apache.kafka.tiered.."
            )
            .layer("Consensus").definedBy(
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image.."
            )
            .layer("Server").definedBy(
                "org.apache.kafka.server..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.coordinator..",
                "org.apache.kafka.network.."
            )
            .layer("API").definedBy(
                "org.apache.kafka.api..",
                "org.apache.kafka.clients..",
                "org.apache.kafka.admin..",
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

            .because("The Kafka architecture defines a strict five-layer hierarchy: Core, Storage," +
                     " Consensus, Server, and API. Each layer may only depend on layers below it." +
                     " This prevents the Core and Storage subsystems from acquiring coupling to" +
                     " broker, coordinator, or client logic, which would undermine modularity" +
                     " and make independent evolution of the persistence and protocol layers impossible.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: API LAYER
    // Parallel modules within the API layer serve distinct client-facing
    // concerns (stream processing, connector framework, admin operations,
    // tooling). They must not import each other to prevent circular
    // dependencies and to preserve clean separation of deployable artifacts.
    // =========================================================================

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Kafka Streams is a standalone stream processing library that runs as an" +
                     " embedded library inside application processes. It must not take a compile-time" +
                     " dependency on the Kafka Connect framework, which is a separate runtime for" +
                     " connector-based data integration. A dependency in this direction would" +
                     " force all Streams users to transitively pull in Connect infrastructure.");

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_admin =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.admin..")
            .because("Kafka Streams manages its internal topics (changelog, repartition) through" +
                     " the lower-level AdminClient in the clients package and must not import the" +
                     " higher-level admin package. A dependency on org.apache.kafka.admin would" +
                     " couple Streams to admin-specific DTOs and create an unintended sibling" +
                     " dependency within the API layer.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("The Kafka Connect framework is a general-purpose data integration runtime" +
                     " for source and sink connectors. It has no architectural dependency on Kafka" +
                     " Streams. Importing Streams types from Connect would create a circular sibling" +
                     " coupling at the top of the dependency hierarchy and bloat the Connect runtime" +
                     " artifact with stream-processing libraries.");

    @ArchTest
    public static final ArchRule clients_must_not_depend_on_admin =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.admin..")
            .because("The clients package contains the foundational producer, consumer, and low-level" +
                     " AdminClient implementations. The admin package contains a higher-level admin" +
                     " API built on top of the clients layer. Allowing clients to import admin types" +
                     " would create an upward dependency that inverts the intended hierarchy and" +
                     " introduces circular coupling.");

    @ArchTest
    public static final ArchRule admin_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.admin..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("The admin package provides cluster management operations (topic creation," +
                     " consumer group management, ACL administration) and must not depend on the" +
                     " Kafka Streams runtime. Streams-specific types have no place in generic" +
                     " administrative operations and would inflate the admin artifact footprint.");

    @ArchTest
    public static final ArchRule admin_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.admin..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Admin-layer operations are broker-centric and must remain independent of the" +
                     " Kafka Connect framework. Connect has its own REST-based management surface;" +
                     " importing Connect types into the admin package would couple two independently" +
                     " deployable subsystems without architectural justification.");

    @ArchTest
    public static final ArchRule shell_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.shell..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("The Kafka shell is an interactive administrative REPL for inspecting broker" +
                     " and metadata state. It targets cluster operations and must not import Kafka" +
                     " Streams internals. A dependency on Streams would pull a large stream" +
                     " processing runtime into a lightweight administrative utility.");

    @ArchTest
    public static final ArchRule shell_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.shell..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("The Kafka shell operates on broker metadata and cluster topology. Kafka Connect" +
                     " is a separate runtime for data integration connectors. There is no architectural" +
                     " reason for the shell to import Connect classes, and doing so would couple" +
                     " two unrelated operational subsystems.");

    @ArchTest
    public static final ArchRule tools_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Kafka command-line tools (consumer-groups, topics, configs, etc.) are" +
                     " broker-management utilities that communicate via the AdminClient or the" +
                     " protocol layer. They must not import the Kafka Streams library, which is an" +
                     " application-level stream processing framework unrelated to cluster tooling.");

    @ArchTest
    public static final ArchRule tools_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Command-line tools operate at the broker management level and must remain" +
                     " decoupled from the Kafka Connect framework. Connect manages its own" +
                     " REST-based tooling; importing Connect types here would create an unintended" +
                     " dependency between two parallel API-layer subsystems.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: SERVER LAYER
    // The controller, coordinator, and network sub-modules within the Server
    // layer serve distinct roles. Cross-dependencies between them would create
    // tight coupling within the broker runtime and hinder independent testing
    // and replacement of individual server components.
    // =========================================================================

    @ArchTest
    public static final ArchRule controller_must_not_depend_on_coordinator =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.controller..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
            .because("The KRaft controller is responsible for managing cluster-level metadata" +
                     " (topic/partition assignments, broker epochs) via the Raft consensus log." +
                     " The coordinator manages consumer group state and transactions at the" +
                     " broker level. These are independent concerns; the controller importing" +
                     " coordinator types would conflate cluster management with group coordination" +
                     " and introduce a circular dependency within the Server layer.");

    @ArchTest
    public static final ArchRule coordinator_must_not_depend_on_controller =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.coordinator..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
            .because("The group and transaction coordinator handles consumer group membership," +
                     " offset commits, and transaction state machines. It must not import KRaft" +
                     " controller internals, as the coordinator communicates with the controller" +
                     " only through the server-layer request handling path and metadata propagation," +
                     " not through direct class-level coupling.");

    @ArchTest
    public static final ArchRule network_must_not_depend_on_controller =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.network..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
            .because("The network layer (SocketServer, Acceptors, Processors) is a generic NIO" +
                     " I/O multiplexer that routes requests to handler components. It must not" +
                     " import controller-specific types; doing so would make the network layer" +
                     " aware of high-level cluster management logic it should treat as opaque" +
                     " request handlers registered through the server layer.");

    @ArchTest
    public static final ArchRule network_must_not_depend_on_coordinator =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.network..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
            .because("The network layer handles raw socket I/O and request demultiplexing and must" +
                     " remain agnostic of the coordinator's group management or transaction protocol" +
                     " logic. Coupling network I/O to coordinator internals would prevent reuse of" +
                     " the network layer across different broker roles and deployment configurations.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: CONSENSUS LAYER
    // The Raft consensus engine is the foundational distributed log that the
    // metadata and image layers build upon. Raft must not import its consumers
    // to preserve its role as a general-purpose replicated log abstraction.
    // =========================================================================

    @ArchTest
    public static final ArchRule raft_must_not_depend_on_metadata =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata..")
            .because("The KRaft Raft engine is a general-purpose distributed consensus log. It" +
                     " replicates opaque records and must not be aware of the semantic content of" +
                     " those records (i.e., metadata changes). Importing metadata types into the" +
                     " Raft layer would destroy its reusability and create an upward dependency" +
                     " from the protocol engine to its consumers.");

    @ArchTest
    public static final ArchRule raft_must_not_depend_on_image =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
            .because("The cluster metadata image is a point-in-time snapshot of broker, topic," +
                     " and partition state assembled from the Raft log. The Raft engine itself" +
                     " must not import image types; the image layer is a higher-level consumer of" +
                     " the Raft log, not a dependency that the log engine should know about.");

    @ArchTest
    public static final ArchRule metadata_must_not_depend_on_server =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.metadata..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
            .because("The metadata layer maintains the authoritative in-memory view of cluster" +
                     " state (topics, partitions, replicas, configs). It must not import broker" +
                     " server classes, which are at a higher layer responsible for request handling" +
                     " and lifecycle management. An upward dependency here would prevent the" +
                     " metadata layer from being used by the controller independently of the broker.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: CORE LAYER ISOLATION
    // Core packages are the foundation of the entire dependency graph.
    // They must not acquire dependencies on any layer above them. These rules
    // guard the most critical boundary: once Core imports a higher-layer type,
    // every module in the system transitively inherits that coupling.
    // =========================================================================

    @ArchTest
    public static final ArchRule common_must_not_depend_on_storage =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.storage..",
                "org.apache.kafka.snapshot..",
                "org.apache.kafka.tiered.."
            )
            .because("The common package is the universal utility foundation shared by every other" +
                     " Kafka module. It must not import storage, snapshot, or tiered-storage types." +
                     " Any such dependency would propagate a storage coupling to every Kafka module" +
                     " that imports common, making it impossible to use common utilities in" +
                     " lightweight clients without dragging in persistence infrastructure.");

    @ArchTest
    public static final ArchRule common_must_not_depend_on_consensus =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image.."
            )
            .because("The common package must be free of consensus-layer types. Importing Raft or" +
                     " metadata classes into common would force every client and server module to" +
                     " transitively depend on the KRaft consensus engine, violating the separation" +
                     " between the universal utility layer and the server-side metadata protocol.");

    @ArchTest
    public static final ArchRule common_must_not_depend_on_server_layer =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.coordinator..",
                "org.apache.kafka.network.."
            )
            .because("The common package supplies foundational types (record schemas, serializers," +
                     " configuration keys, error codes) to all other modules. Importing server," +
                     " controller, coordinator, or network types here would create a circular" +
                     " foundation dependency that makes it impossible to build the client library" +
                     " without also compiling the entire broker runtime.");

    @ArchTest
    public static final ArchRule storage_must_not_depend_on_consensus =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image.."
            )
            .because("The storage layer manages the physical log structure, index files, and" +
                     " state store persistence. It must not import Raft consensus or metadata" +
                     " image types; the relationship between storage and consensus is mediated" +
                     " by the server layer. Allowing storage to import consensus types would" +
                     " collapse the Storage and Consensus layers into a single tangled module.");

    @ArchTest
    public static final ArchRule storage_must_not_depend_on_server_layer =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.coordinator..",
                "org.apache.kafka.network.."
            )
            .because("The storage layer implements durable persistence and must remain independent" +
                     " of broker lifecycle management, KRaft controller logic, group coordination," +
                     " and NIO networking. Coupling storage to server-layer classes would prevent" +
                     " the storage subsystem from being tested, benchmarked, or embedded in" +
                     " lightweight tooling outside the full broker runtime.");

    @ArchTest
    public static final ArchRule storage_must_not_depend_on_api_layer =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.clients..",
                "org.apache.kafka.admin..",
                "org.apache.kafka.connect..",
                "org.apache.kafka.streams..",
                "org.apache.kafka.tools..",
                "org.apache.kafka.shell.."
            )
            .because("The storage layer must not import client or application-layer types. It is a" +
                     " low-level persistence subsystem; importing producer/consumer clients, admin" +
                     " APIs, Streams, or Connect classes here would invert the dependency hierarchy" +
                     " and make the persistence foundation aware of the application layers it serves.");
}
