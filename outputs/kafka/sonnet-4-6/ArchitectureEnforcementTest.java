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
 *    org.apache.kafka.server published as shared libraries rather than
 *    broker-runtime code.
 *    NOTE (REGR-01): config, deferred, logger, queue, and timeline were
 *    incorrectly declared empty in Review #1 based on a grep that only
 *    checks packages appearing as violation sources. These packages contain
 *    44 real production classes (BrokerReconfigurable, DeferredEventQueue,
 *    StateChangeLogger, KafkaEventQueue, TimelineHashMap, SnapshotRegistry, etc.)
 *    and are restored to Core here.
 *    Packages:
 *      - org.apache.kafka.common..                    (shared types, serialization)
 *      - org.apache.kafka.security..                  (auth, ACL, SSL primitives)
 *      - org.apache.kafka.config..                    (BrokerReconfigurable)
 *      - org.apache.kafka.deferred..                  (DeferredEvent, DeferredEventQueue)
 *      - org.apache.kafka.logger..                    (StateChangeLogger)
 *      - org.apache.kafka.queue..                     (EventQueue, KafkaEventQueue)
 *      - org.apache.kafka.timeline..                  (TimelineHashMap, SnapshotRegistry)
 *      - org.apache.kafka.server.common..             (ApiMessageAndVersion, MetadataVersion)
 *      - org.apache.kafka.server.util..               (Scheduler, ShutdownableThread)
 *      - org.apache.kafka.server.metrics..            (KafkaMetricsGroup, TimeRatio)
 *      - org.apache.kafka.server.fault..              (FaultHandler, FaultHandlerException)
 *      - org.apache.kafka.server.authorizer..         (Authorizer SPI, AuthorizationResult)
 *      - org.apache.kafka.server.immutable..          (ImmutableMap, ImmutableNavigableSet)
 *      - org.apache.kafka.server.config..             (ConfigSynonym, ServerLogConfigs)
 *      - org.apache.kafka.server.record..             (record utilities)
 *      - org.apache.kafka.server.storage.log..        (storage log abstractions)
 *      - org.apache.kafka.server.log.remote.storage.. (RemoteStorageManager SPI)
 *
 * 2. Storage
 *    Manages durable persistence of log segments and state store snapshots.
 *    Packages:
 *      - org.apache.kafka.storage..  (log segment and store abstractions)
 *      - org.apache.kafka.snapshot.. (state store snapshot management)
 *    NOTE (MOD-01): snapshot.SnapshotReader returns Iterator<raft.Batch<T>>,
 *    making snapshot -> raft a deliberate API surface. This is excluded via
 *    ignoreDependency on the layered rule.
 *
 * 3. Consensus
 *    KRaft consensus protocol engine, metadata records, and the cluster image.
 *    Depends on Core and Storage.
 *    Packages:
 *      - org.apache.kafka.raft..     (KRaft consensus protocol engine)
 *      - org.apache.kafka.metadata.. (broker/topic/partition metadata records)
 *      - org.apache.kafka.image..    (point-in-time cluster metadata image)
 *    NOTE (REGR-02): metadata -> image is the documented KIP-500 direction:
 *    metadata.publisher.* classes implement image.publisher.MetadataPublisher.
 *    The rule metadata_must_not_depend_on_image (added in Review #1) was
 *    inverted and has been replaced by image_must_not_depend_on_metadata_publisher_internals.
 *    NOTE (MAP-03): storage -> metadata.properties and storage -> metadata
 *    (ConfigRepository SAM) are legitimate uses excluded via ignoreDependency.
 *
 * 4. Server
 *    Broker runtime (broker lifecycle classes, KRaft controller quorum, NIO
 *    network layer). The coordinator package appears in the listing but is
 *    empty in the scanned classpath and is excluded from the Server layer.
 *    NOTE (MOD-01): several known SPI inversions and outbound-RPC patterns
 *    are excluded via ignoreDependency on the layered rule.
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
 *    Client-facing and operator-facing surface. The api and admin packages are
 *    empty in the scanned classpath. The admin client lives in clients.admin.
 *    NOTE (MOD-02): clients.admin.* enums (ScramMechanism, AlterConfigOp$OpType,
 *    ConfigEntry$ConfigSource, FeatureUpdate$UpgradeType) are used as shared DTO
 *    types by common.requests, controller, and image. These uses are excluded via
 *    ignoreDependency on the layered rule.
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
 *       JMH benchmark classes; excluded via TrogdorAndJmhExclusion ImportOption.
 *
 *   - org.apache.kafka.trogdor
 *       Fault-injection and distributed test coordination framework; excluded
 *       via TrogdorAndJmhExclusion ImportOption.
 *
 *   - org.apache.kafka.api, org.apache.kafka.admin, org.apache.kafka.coordinator,
 *     org.apache.kafka.tiered, org.apache.kafka.message
 *       These packages appear in the package listing but contain zero classes in
 *       the scanned classpath. They are omitted from layer definitions to prevent
 *       vacuous rules. Re-verify by direct classpath inspection before assuming
 *       emptiness if the scan scope changes.
 */
package org.archunit.kafka;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Arrays;
import java.util.stream.Stream;

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
    // LAYER PACKAGE CONSTANTS (LAY-NEW-01)
    // Shared between the layeredArchitecture() rule and the every-class guard
    // so that both stay in sync when packages are added or removed.
    // =========================================================================

    // REGR-01: config, deferred, logger, queue, timeline restored — they were
    // incorrectly declared empty in Review #1. Each contains real production classes.
    // REGR-04: six additional server.* sub-packages restored — they contain ~100
    // production classes that were orphaned because the enumeration was incomplete.
    private static final String[] CORE_PACKAGES = {
        "org.apache.kafka.common..",
        "org.apache.kafka.security..",
        "org.apache.kafka.config..",
        "org.apache.kafka.deferred..",
        "org.apache.kafka.logger..",
        "org.apache.kafka.queue..",
        "org.apache.kafka.timeline..",
        "org.apache.kafka.server.common..",
        "org.apache.kafka.server.util..",
        "org.apache.kafka.server.metrics..",
        "org.apache.kafka.server.fault..",
        "org.apache.kafka.server.authorizer..",
        "org.apache.kafka.server.immutable..",
        "org.apache.kafka.server.config..",
        "org.apache.kafka.server.record..",
        "org.apache.kafka.server.storage.log..",
        "org.apache.kafka.server.log.remote.storage..",
        // REGR-04: shared library / SPI sub-packages under server.*
        "org.apache.kafka.server.mutable..",        // BoundedList data structure
        "org.apache.kafka.server.network..",        // BrokerEndPoint, EndpointReadyFutures SPIs
        "org.apache.kafka.server.policy..",         // AlterConfigPolicy, CreateTopicPolicy SPIs
        "org.apache.kafka.server.purgatory..",      // DelayedOperation framework
        "org.apache.kafka.server.telemetry..",      // KIP-714 ClientTelemetry SPIs
        // REGR-08: narrow glob to top-level only; sub-packages listed explicitly below
        "org.apache.kafka.server.log.remote",       // TopicPartitionLog (top-level only)
        // REGR-05: RLMQuotaManager/RLMQuotaMetrics helpers consumed by server.log.remote.storage (Core)
        "org.apache.kafka.server.log.remote.quota..",
        // REGR-07: ClientQuotaCallback SPI + QuotaType enum consumed by Core/Consensus
        "org.apache.kafka.server.quota.."
    };

    private static final String[] STORAGE_PACKAGES = {
        "org.apache.kafka.storage..",
        "org.apache.kafka.snapshot.."
    };

    private static final String[] CONSENSUS_PACKAGES = {
        "org.apache.kafka.raft..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.image.."
    };

    // REGR-04: six broker-runtime server.* sub-packages added.
    // NOTE: server.log.remote.metadata.storage.generated is a schema-derived DTO
    // sub-package used by the storage layer; storage -> .generated is excluded via
    // ignoreDependency (MOD-08).
    private static final String[] SERVER_PACKAGES = {
        "org.apache.kafka.server",
        "org.apache.kafka.server.share..",
        "org.apache.kafka.server.transaction..",
        "org.apache.kafka.server.log.remote.metadata.storage..",
        "org.apache.kafka.controller..",
        "org.apache.kafka.network..",
        // REGR-04: broker runtime server.* sub-packages
        "org.apache.kafka.server.controller..",     // ControllerRegistrationManager, broker-side wiring
        "org.apache.kafka.server.logger..",         // Log4jCoreController runtime
        "org.apache.kafka.server.partition..",      // AlterPartitionManager, ReplicaState mutators
        "org.apache.kafka.server.replica.."         // Replica, ReplicaState
        // REGR-05 removed: server.log.remote.quota → moved to CORE_PACKAGES
        // REGR-07 removed: server.quota → moved to CORE_PACKAGES
    };

    private static final String[] API_PACKAGES = {
        "org.apache.kafka.clients..",
        "org.apache.kafka.connect..",
        "org.apache.kafka.streams..",
        "org.apache.kafka.tools..",
        "org.apache.kafka.shell.."
    };

    /** All production layer packages combined; used by the every-class guard. */
    private static final String[] ALL_LAYER_PACKAGES = Stream.of(
            CORE_PACKAGES, STORAGE_PACKAGES, CONSENSUS_PACKAGES,
            SERVER_PACKAGES, API_PACKAGES)
        .flatMap(Arrays::stream)
        .toArray(String[]::new);

    // =========================================================================
    // LAYERED ARCHITECTURE RULE
    // Enforces the five-layer hierarchy: Core < Storage < Consensus < Server < API.
    //
    // ignoreDependency clauses address known legitimate cross-layer patterns that
    // are intrinsic to Kafka's KIP-driven design and cannot be reconciled with a
    // strict linear layer model:
    //
    //   MAP-03: storage -> metadata.properties (bootstrap identity helper) and
    //           storage -> metadata.ConfigRepository (SAM / dependency inversion)
    //   MOD-01: server.config -> metadata (SPI inversion)
    //           server.log.remote.storage -> storage (SPI inversion)
    //           server -> clients (broker outbound RPC via KafkaClient/NetworkClient)
    //           raft -> clients (KafkaNetworkChannel inter-broker comms)
    //           snapshot -> raft (SnapshotReader iterator API exposes raft.Batch<T>)
    //   MOD-02: controller, image -> clients.admin (shared DTO enums)
    //   MOD-03: metadata -> clients.admin (KafkaConfigSchema, ScramCredentialData use
    //           ConfigEntry, ConfigSource, ScramMechanism as wire-format primitives)
    //   MOD-04: security -> clients.admin (CredentialProvider uses ScramMechanism)
    //   MOD-05: server.config -> storage (AbstractKafkaConfig aggregates LogConfig,
    //           CleanerConfig, ProducerStateManagerConfig ConfigDef instances)
    //   MOD-06: server.metrics -> image/metadata/controller (metrics observe runtime state)
    //   MOD-07: server.util -> metadata (NetworkPartitionMetadataClient uses MetadataCache)
    //   MOD-08: storage -> server.log.remote.metadata.storage.generated (schema-derived DTOs)
    //   MOD-09: common -> clients (MessageFormatter, ApiVersionsResponse, ShareFetchRequest,
    //           SaslClientAuthenticator share types with clients; subsumes MOD-02 common clause)
    //   MOD-10: server.log.remote.storage -> server.log.remote.metadata.storage (SPI factory)
    //   FP-AUTH-01: metadata.authorizer -> controller (controller-side ACL implementations)
    // =========================================================================

    @ArchTest
    public static final ArchRule kafka_layered_architecture =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()

            .layer("Core").definedBy(CORE_PACKAGES)
            .layer("Storage").definedBy(STORAGE_PACKAGES)
            .layer("Consensus").definedBy(CONSENSUS_PACKAGES)
            .layer("Server").definedBy(SERVER_PACKAGES)
            .layer("API").definedBy(API_PACKAGES)

            .whereLayer("API").mayNotBeAccessedByAnyLayer()
            .whereLayer("Server").mayOnlyBeAccessedByLayers("API")
            .whereLayer("Consensus").mayOnlyBeAccessedByLayers("Server", "API")
            .whereLayer("Storage").mayOnlyBeAccessedByLayers("Consensus", "Server", "API")
            .whereLayer("Core").mayOnlyBeAccessedByLayers("Storage", "Consensus", "Server", "API")

            // MAP-03: storage -> metadata.properties (MetaProperties bootstrap identity helper)
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.properties.."))
            // MAP-03: storage -> metadata.ConfigRepository (SAM interface for dependency inversion).
            // LAY-NEW-02: intentionally class-exact (PREC-02) — matches only ConfigRepository,
            // not any other top-level org.apache.kafka.metadata.* class.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
                DescribedPredicate.describe(
                    "is org.apache.kafka.metadata.ConfigRepository",
                    (JavaClass c) -> c.getName().equals("org.apache.kafka.metadata.ConfigRepository")))
            // MOD-01: server.config implements interfaces defined in the metadata layer (SPI inversion)
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."))
            // MOD-01: server.log.remote.storage implements storage.internals interfaces (SPI inversion)
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.storage.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
            // MOD-01 (narrowed — OVR-01): broker outbound RPC targets a documented set of
            // clients types. Listing them explicitly preserves detection of unintended new
            // server.* → clients.* dependencies (e.g., server.replica → clients.consumer).
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.."),
                DescribedPredicate.describe(
                    "is a documented broker-outbound-RPC type in clients",
                    (JavaClass c) -> {
                        String n = c.getName();
                        return n.equals("org.apache.kafka.clients.KafkaClient")
                            || n.startsWith("org.apache.kafka.clients.KafkaClient$")
                            || n.equals("org.apache.kafka.clients.NetworkClient")
                            || n.startsWith("org.apache.kafka.clients.NetworkClient$")
                            || n.equals("org.apache.kafka.clients.NodeApiVersions")
                            || n.equals("org.apache.kafka.clients.ClientResponse")
                            || n.equals("org.apache.kafka.clients.ClientRequest")
                            || n.equals("org.apache.kafka.clients.RequestCompletionHandler")
                            || n.equals("org.apache.kafka.clients.Metadata")
                            || n.startsWith("org.apache.kafka.clients.Metadata$")
                            || n.equals("org.apache.kafka.clients.MetadataUpdater")
                            || n.equals("org.apache.kafka.clients.ApiVersions")
                            || n.equals("org.apache.kafka.clients.ManualMetadataUpdater")
                            || n.equals("org.apache.kafka.clients.CommonClientConfigs")
                            || n.startsWith("org.apache.kafka.clients.CommonClientConfigs$");
                    }))
            // MOD-01: KRaft Raft engine uses KafkaClient for inter-broker network communication
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
            // MOD-01: SnapshotReader's iterator API surface exposes raft.Batch<T> types
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.snapshot.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."))
            // MOD-02: clients.admin DTOs used as wire-format primitives by controller and image
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.controller.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.image.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
            // MOD-03: metadata.KafkaConfigSchema and metadata.ScramCredentialData use
            // clients.admin.{ConfigEntry,ScramMechanism} enums as wire-format primitives
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
            // MOD-04: security.CredentialProvider uses clients.admin.ScramMechanism (shared DTO)
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.security.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
            // MOD-05: server.config aggregates ConfigDef from storage.internals.log
            // (LogConfig, CleanerConfig, ProducerStateManagerConfig) for dynamic broker config
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
            // MOD-06 (idiomatic — PREC-01): server.metrics observes higher-layer state for
            // reporting (BrokerServerMetrics reports MetadataProvenance; NodeMetrics reports
            // QuorumFeatures). Using resideInAnyPackage avoids the unanchored startsWith gotcha.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.metrics.."),
                JavaClass.Predicates.resideInAnyPackage(
                    "org.apache.kafka.image..",
                    "org.apache.kafka.metadata..",
                    "org.apache.kafka.controller.."))
            // MOD-07: server.util.NetworkPartitionMetadataClient uses metadata.MetadataCache SAM
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.util.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."))
            // MOD-08 (narrow): storage uses schema-derived generated DTOs from the tiered-metadata
            // server module (ProducerSnapshot, ProducerEntry) for on-disk snapshot format
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.generated.."))
            // MOD-09 (narrowed — OVR-02, part 1): clients.admin enums used as wire-format
            // primitives by common.* classes (subsumes MOD-02's common -> clients.admin clause).
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.common.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
            // MOD-09 (narrowed — OVR-02, part 2): four documented common.* classes that share
            // types with non-admin clients. A fifth crossing will fire and require a decision.
            .ignoreDependency(
                DescribedPredicate.describe(
                    "is one of four common.* classes that share types with clients",
                    (JavaClass c) -> {
                        String n = c.getName();
                        return n.equals("org.apache.kafka.common.MessageFormatter")
                            || n.equals("org.apache.kafka.common.requests.ApiVersionsResponse")
                            || n.startsWith("org.apache.kafka.common.requests.ApiVersionsResponse$")
                            || n.equals("org.apache.kafka.common.requests.ShareFetchRequest")
                            || n.startsWith("org.apache.kafka.common.requests.ShareFetchRequest$")
                            || n.equals("org.apache.kafka.common.security.authenticator.SaslClientAuthenticator");
                    }),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
            // MOD-10: RemoteLogManager (SPI side, Core) instantiates the default
            // ClassLoaderAwareRemoteLogMetadataManager (runtime side, Server) — standard factory pattern
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.storage.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."))
            // FP-AUTH-01: metadata.authorizer.{AclMutator,ClusterMetadataAuthorizer} are
            // controller-side ACL implementations deliberately wired into the controller pipeline.
            // Mirror of the FP-NEW-01 exception on metadata_must_not_depend_on_server_runtime.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.authorizer.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.controller.."))
            // REGR-06: server.purgatory is a generic delayed-operation framework (Core) but hosts
            // storage-aware subclasses (DelayedRemoteFetch, DelayedRemoteListOffsets,
            // ListOffsetsPartitionStatus) that intentionally reference storage result types.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.purgatory.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
            // REGR-08: TopicPartitionLog (top-level server.log.remote) returns
            // Optional<storage.internals.log.UnifiedLog> as part of its public API.
            // Source predicate uses bare package (no ..) so storage and quota sub-packages
            // are unaffected by this carve-out.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote"),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
            // MOD-05-WIDEN: AbstractKafkaConfig and DynamicBrokerConfig static aggregation
            // also reads ConfigDef / RECONFIGURABLE_CONFIGS from network (SocketServerConfigs,
            // SocketServer), raft (KRaftConfigs, MetadataLogConfig, QuorumConfig), and the
            // top-level server package (DynamicThreadPool). Same pattern as MOD-05.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.network.."))
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."))
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server"))
            // REGR-09: server.quota runtime managers (ClientQuotaManager,
            // ControllerMutationQuotaManager) consume network.Session for principal-based
            // throttling. Session is a small authentication-context value type that is
            // genuinely cross-cutting. Knock-on of Round-4 REGR-07 reclassification of
            // server.quota from Server to Core. Narrow form used to preserve detection of
            // any future server.quota → network.SocketServer regression.
            .ignoreDependency(
                JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.quota.."),
                DescribedPredicate.describe(
                    "is org.apache.kafka.network.Session (authentication context value type)",
                    (JavaClass c) -> c.getName().equals("org.apache.kafka.network.Session")))

            .because("Inferred from package naming conventions (not explicitly stated in the" +
                     " supplied Kafka Streams Architecture PDF). A five-layer hierarchy is assumed:" +
                     " Core, Storage, Consensus, Server, and API. Known SPI inversions, shared DTO" +
                     " types, and outbound-RPC patterns are excluded via ignoreDependency. Validate" +
                     " against KIP-500 / KIP-405 / design.html before treating this as authoritative.");

    // =========================================================================
    // LAYER COVERAGE GUARD (LAY-01 / LAY-NEW-03)
    // Ensures that every scanned production class belongs to at least one defined
    // layer. Uses ALL_LAYER_PACKAGES (derived from the per-layer constants above)
    // so this guard and the layeredArchitecture() definition stay in sync (LAY-NEW-01).
    // Uses ArchCondition rather than resideInAnyPackage() so that each violation
    // prints one short diagnostic sentence instead of the full package list (LAY-NEW-03).
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
            .should(new ArchCondition<JavaClass>(
                "reside in one of the " + ALL_LAYER_PACKAGES.length + " enumerated layer packages") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    String pkg = item.getPackageName();
                    boolean inLayer = Arrays.stream(ALL_LAYER_PACKAGES).anyMatch(p ->
                        p.endsWith("..")
                            ? pkg.equals(p.substring(0, p.length() - 2))
                              || pkg.startsWith(p.substring(0, p.length() - 1))
                            : pkg.equals(p));
                    if (!inLayer) {
                        events.add(SimpleConditionEvent.violated(item,
                            "Class " + item.getName() + " in package [" + pkg + "] is not" +
                            " assigned to any layer. Add the package to the appropriate" +
                            " CORE_PACKAGES / SERVER_PACKAGES / etc. constant."));
                    }
                }
            })
            .because("Inferred from package naming. New top-level packages must be explicitly" +
                     " assigned to a layer; otherwise consideringOnlyDependenciesInLayers() will" +
                     " silently exempt their classes from all dependency checks.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: API LAYER
    // The API layer contains several independently-deployable modules that must
    // not form sibling cross-dependencies. Rules cover both directions for each
    // pair where meaningful.
    // NOTE (REGR-03): tools_must_not_depend_on_connect was deleted in Review #1
    // instead of being scoped. It is restored here with an explicit exclusion
    // for the ConnectPluginPath CLI family, which legitimately depends on Connect.
    // =========================================================================

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. Kafka Streams is a standalone stream processing" +
                     " library; it must not depend on the Kafka Connect framework, which is a" +
                     " separate data-integration runtime. Such a dependency would force all Streams" +
                     " users to transitively pull in Connect.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. Kafka Connect is a general-purpose connector" +
                     " runtime with no architectural dependency on Kafka Streams. Importing Streams" +
                     " types from Connect would create a circular sibling coupling.");

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: Streams does not" +
                     " currently depend on tools, but a future Streams feature that imports a" +
                     " tool's helper class would create an unintended sibling coupling. This rule" +
                     " guards against that regression.");

    @ArchTest
    public static final ArchRule streams_must_not_depend_on_shell =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: Kafka Streams must not" +
                     " import the admin shell runtime. This rule guards against a future regression" +
                     " where a Streams feature accidentally pulls in shell utilities.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: The Kafka Connect" +
                     " framework must not depend on broker-management CLI tools. Connect manages" +
                     " its own REST-based tooling and must remain independently deployable.");

    @ArchTest
    public static final ArchRule connect_must_not_depend_on_shell =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.connect..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: The Kafka Connect" +
                     " framework must not import the interactive admin shell. These are parallel" +
                     " API-layer modules with unrelated responsibilities.");

    @ArchTest
    public static final ArchRule tools_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. Kafka CLI tools are broker-management" +
                     " utilities that communicate via AdminClient or the protocol layer. They must" +
                     " not import the Kafka Streams library, which is an application-level" +
                     " stream processing framework unrelated to cluster tooling.");

    // REGR-03: Restored from deletion. The original rule was incorrectly deleted
    // entirely in Review #1 instead of being scoped. ConnectPluginPath and
    // ManifestWorkspace are explicitly excluded as documented exceptions.
    @ArchTest
    public static final ArchRule tools_must_not_depend_on_connect_except_plugin_path =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .and(DescribedPredicate.describe(
                "are not part of the ConnectPluginPath CLI family",
                (JavaClass clazz) ->
                    !clazz.getName().startsWith("org.apache.kafka.tools.ConnectPluginPath")
                 && !clazz.getName().startsWith("org.apache.kafka.tools.ManifestWorkspace")))
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. The connect-plugin-path CLI" +
                     " (ConnectPluginPath, ManifestWorkspace) is the documented exception: its" +
                     " sole purpose is to inspect Kafka Connect plugin classpaths. All other" +
                     " tools must remain decoupled from the Connect runtime.");

    @ArchTest
    public static final ArchRule tools_must_not_depend_on_shell =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: Command-line tools and" +
                     " the interactive shell are parallel operator-facing modules. Tools must not" +
                     " import shell internals; this rule guards against future regressions.");

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
            .because("Inferred from package naming. The Kafka shell operates on broker metadata" +
                     " and cluster topology. Kafka Connect is a separate runtime for data-integration" +
                     " connectors; importing Connect types into the shell would couple two unrelated" +
                     " operational subsystems.");

    @ArchTest
    public static final ArchRule shell_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.shell..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: The interactive shell" +
                     " and CLI tools are parallel operator-facing modules. The shell must not" +
                     " import tool implementations; this rule guards against future regressions.");

    // MAP-01: The admin client physically lives at org.apache.kafka.clients.admin.
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

    // TRANS-01: Anti-cycle guards — prevent transitive bypass of streams/connect
    // isolation through the clients package.
    @ArchTest
    public static final ArchRule clients_must_not_depend_on_streams =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: The clients package" +
                     " is the foundational producer/consumer library; it must not import Kafka" +
                     " Streams, which depends on clients. A reverse dependency would create a" +
                     " cycle and force Streams onto every client user transitively.");

    @ArchTest
    public static final ArchRule clients_must_not_depend_on_connect =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.clients..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: The clients package" +
                     " must not import the Kafka Connect framework. Connect depends on clients;" +
                     " a reverse dependency would create a cycle and pull Connect infrastructure" +
                     " into every Kafka client.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: SERVER LAYER
    // NOTE: coordinator_must_not_depend_on_controller is absent because
    // org.apache.kafka.coordinator.. contains no classes in the scanned classpath
    // (failOnEmptyShould would fail the rule with an empty subject set).
    // =========================================================================

    @ArchTest
    public static final ArchRule controller_must_not_depend_on_coordinator =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.controller..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
            .because("Inferred from package naming. FUTURE-PROOFING ONLY: org.apache.kafka.coordinator" +
                     " is empty in the current scanned classpath, so this rule is vacuous today." +
                     " It guards against a future regression where group-coordinator classes are added" +
                     " under coordinator.. and controller starts depending on them.");

    @ArchTest
    public static final ArchRule network_must_not_depend_on_controller =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.network..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
            .because("Inferred from package naming. The NIO network layer (SocketServer, Acceptors," +
                     " Processors) is a generic I/O multiplexer and must not import controller" +
                     " internals. Doing so would make the network layer aware of high-level cluster" +
                     " management logic that should be opaque to it.");

    // NAR-01: network -> broker-runtime server classes also forbidden.
    @ArchTest
    public static final ArchRule network_must_not_depend_on_server_runtime =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.network..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.coordinator.."  // FUTURE-PROOFING ONLY: empty in current scan
            )
            .because("Inferred from package naming. The NIO network layer must remain agnostic of" +
                     " broker request handling, share-group logic, transaction management, KRaft" +
                     " controller operations, and group coordinator state machines.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES: CONSENSUS LAYER
    // NOTE (REGR-02): metadata_must_not_depend_on_image was inverted and has
    // been removed. The KIP-500 direction is metadata -> image: metadata.publisher.*
    // implements image.publisher.MetadataPublisher and receives image.MetadataImage
    // in callbacks. image_must_not_depend_on_metadata_publisher_internals below
    // encodes the correct constraint.
    // =========================================================================

    @ArchTest
    public static final ArchRule raft_must_not_depend_on_metadata =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata..")
            .because("Inferred from package naming. The KRaft Raft engine replicates opaque records" +
                     " and must not be aware of the semantic content of those records (metadata" +
                     " changes). Importing metadata types into Raft would destroy reusability.");

    @ArchTest
    public static final ArchRule raft_must_not_depend_on_image =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.raft..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
            .because("Inferred from package naming. The cluster metadata image is a point-in-time" +
                     " snapshot assembled from the Raft log. Raft must not import image types; the" +
                     " image layer is a higher-level consumer of the log.");

    // NAR-02: Raft must not depend on the Server-layer modules above it.
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
            .because("Inferred from package naming. Raft is a general-purpose consensus log and" +
                     " must not depend on broker runtime classes, KRaft controller logic, or NIO" +
                     " networking. Raft may use server.common/util/fault utilities (in Core).");

    // REGR-02: This is the correct direction — image must not depend on metadata
    // publisher implementation classes (publishers are consumers of image, not producers).
    // The core image types may legitimately depend on metadata record types.
    @ArchTest
    public static final ArchRule image_must_not_depend_on_metadata_publisher_internals =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.image..")
            .and().resideOutsideOfPackage("org.apache.kafka.image.publisher..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata.publisher..")
            .because("Inferred from package naming. The image layer materialises metadata records" +
                     " into a queryable snapshot; image core types (outside image.publisher) must" +
                     " not depend on metadata.publisher implementation classes. Publishers are" +
                     " consumers of the image, not producers of it.");

    // FP-NEW-01: Added resideOutsideOfPackage exclusion for metadata.authorizer.* which
    // contains the controller-side ACL implementations (StandardAuthorizer, AclMutator,
    // ClusterMetadataAuthorizer) that are deliberately wired into the controller's
    // request handling pipeline via ControllerRequestContext.
    @ArchTest
    public static final ArchRule metadata_must_not_depend_on_server_runtime =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.metadata..")
            .and().resideOutsideOfPackage("org.apache.kafka.metadata.authorizer..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.server",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.transaction..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.network.."
            )
            .because("Inferred from package naming. Metadata is a low-level Consensus-layer" +
                     " component; it may use server.common/util/fault shared utilities (in Core)" +
                     " but must not depend on the broker request-handling runtime. Exception:" +
                     " metadata.authorizer.* contains the controller-side ACL implementation" +
                     " (StandardAuthorizer, AclMutator, ClusterMetadataAuthorizer) which is" +
                     " deliberately wired into the controller's request handling pipeline via" +
                     " ControllerRequestContext.");

    // COV-02: image must not import broker runtime classes.
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
    // After MAP-02, server.common/util/fault/authorizer/immutable/config are
    // part of Core, so common -> those sub-packages is Core -> Core and is
    // no longer restricted.
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
                     " foundation; importing storage or snapshot types would propagate a persistence" +
                     " coupling to every Kafka module that depends on common.");

    @ArchTest
    public static final ArchRule common_must_not_depend_on_consensus =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.raft..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.image.."
            )
            .because("Inferred from package naming. The common package must be free of" +
                     " Consensus-layer types. Importing Raft or metadata classes into common" +
                     " would force every client and server module to transitively depend on" +
                     " the KRaft consensus engine.");

    // MAP-02 / FP-02: targets only broker-runtime packages. server.authorizer,
    // server.common, server.util, etc. are in Core and are therefore allowed targets.
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
            .because("Inferred from package naming. The common package may use the public" +
                     " server.authorizer SPI and server.common/util utilities (all in Core)," +
                     " but must not import the broker runtime, KRaft controller, or NIO networking.");

    // MAP-03: metadata.. removed from the target list. storage -> metadata.properties
    // and metadata.ConfigRepository are legitimate and excluded via ignoreDependency on
    // the layered rule. Only raft and image are blocked here.
    @ArchTest
    public static final ArchRule storage_must_not_depend_on_consensus =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka.raft..",
                "org.apache.kafka.image.."
            )
            .because("Inferred from package naming. The storage layer must not import Raft" +
                     " consensus internals or the cluster metadata image. Storage may legitimately" +
                     " use metadata.properties (bootstrap identity) and the metadata.ConfigRepository" +
                     " SAM (dependency inversion), which are excluded from this rule.");

    // MAP-02: targets only broker-runtime packages. server.common/util/fault/etc. are
    // in Core and are therefore allowed targets for the storage layer.
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
            .because("Inferred from package naming. The storage layer implements durable" +
                     " persistence and must remain independent of the broker lifecycle, KRaft" +
                     " controller logic, and NIO networking. Storage may use server.common/util/fault" +
                     " shared utilities (in Core) but must not import the broker runtime.");

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
                     " dependency hierarchy.");
}
