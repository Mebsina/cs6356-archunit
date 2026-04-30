/**
 * ArchitectureEnforcementTest — Apache Kafka.
 *
 * SCOPE OF THE INPUT DOCUMENTATION
 * --------------------------------
 * The supplied PDF (the Kafka Streams "Architecture" page from the Apache
 * Kafka website) documents Streams' RUNTIME parallelism model only:
 *   - Stream Partitions and Tasks
 *   - Threading Model
 *   - Local State Stores
 *   - Fault Tolerance
 * It contains exactly ONE structural claim about cross-module dependencies:
 *
 *   "Kafka Streams simplifies application development by building on the
 *    Kafka producer and consumer libraries"   (PDF page 1, paragraph 1)
 *
 * Every other constraint enforced below is INFERRED from the standard
 * Apache Kafka monorepo layout (KIP-500 controller/broker split, KIP-405
 * tiered-storage SPI, Connect plugin isolation, etc.) and is marked as
 * such in its .because() clause.
 *
 * EXCLUDED PACKAGES
 * -----------------
 *   - org.apache.kafka.test    : MockConsumer / MockProducer / TestUtils
 *                                (verified test-only in clients/src/test).
 *   - org.apache.kafka.jmh     : JMH benchmarks (build-time only).
 *   - org.apache.kafka.tiered  : Tiered-storage E2E test harness;
 *                                verified test-only in storage/src/test.
 *                                (See F-12: confirm before relying on this.)
 *   - org.apache.kafka.message : Message protocol code generator (build-tool
 *                                only); the runtime message classes live
 *                                under org.apache.kafka.common.message.*
 *                                (See F-10.)
 *
 * NOTE ON CLASSPATH AND EMPTY-LAYER TOLERANCE
 * -------------------------------------------
 * The Maven pom.xml declares published kafka-* artefacts on the test
 * classpath so the importer always sees real bytecode. .withOptionalLayers
 * (true) on the layered rule and .allowEmptyShould(true) on the noClasses
 * rules tolerate any single missing module. The non-emptiness sentinel
 * `layers_are_non_empty_after_classpath_setup` (F-11) fails noisily if
 * NONE of the major layers can be populated.
 */
package com.archunit.kafka;

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
        ArchitectureEnforcementTest.ExcludeBenchmarksAndTestSupport.class
    }
)
public class ArchitectureEnforcementTest {

    // =========================================================================
    // Custom ImportOption: strip benchmark, test-support, and tiered harness
    // =========================================================================

    public static final class ExcludeBenchmarksAndTestSupport implements ImportOption {

        // F-12: verified against kafka-3.7.0 source tree:
        //   - org.apache.kafka.jmh    -> jmh-benchmarks/src/main/java (JMH only)
        //   - org.apache.kafka.test   -> clients/src/test/java/...   (MockConsumer, TestUtils)
        //   - org.apache.kafka.tiered -> storage/src/test/java/...   (TieredStorageTestHarness)
        // No production classes live under any of these prefixes.
        @Override
        public boolean includes(Location location) {
            return !location.contains("/org/apache/kafka/jmh/")
                && !location.contains("/org/apache/kafka/test/")
                && !location.contains("/org/apache/kafka/tiered/");
        }
    }

    // =========================================================================
    // Layered architecture (F-02 + F-04 + F-07 + F-08)
    // =========================================================================
    //
    // Layer            Members                                            Source
    // -----            -------                                            ------
    // Core             common, timeline, deferred, config, logger         INFERRED
    // Infrastructure   network, queue, raft, snapshot, storage, security  INFERRED
    // Metadata         metadata, coordinator, controller, image           INFERRED
    // Clients          clients, admin, api                                PDF p.1 (Clients)
    // Streams          streams                                            PDF p.1
    // Connect          connect                                            INFERRED
    // Server           server (broker runtime)                            INFERRED
    // Shell            shell (KRaft metadata REPL)                        INFERRED
    // Tooling          tools, trogdor                                     INFERRED
    //
    // withOptionalLayers(true) tolerates layers that are empty because the
    // corresponding module isn't on the local classpath; the
    // layers_are_non_empty_after_classpath_setup sentinel (F-11) detects
    // a fully misconfigured classpath.
    @ArchTest
    static final ArchRule kafka_layered_architecture_is_respected =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)

            // F-02: top-level config / logger are Core primitives.
            .layer("Core").definedBy(
                "org.apache.kafka.common..",
                "org.apache.kafka.timeline..",
                "org.apache.kafka.deferred..",
                "org.apache.kafka.config..",
                "org.apache.kafka.logger.."
            )

            // F-02: security primitives (SASL, SCRAM, authorizer SPI) belong
            // below Server/Metadata in the dependency hierarchy.
            // F-08: image is a Metadata-layer concern, not Infrastructure.
            .layer("Infrastructure").definedBy(
                "org.apache.kafka.network..",
                "org.apache.kafka.queue..",
                "org.apache.kafka.raft..",
                "org.apache.kafka.snapshot..",
                "org.apache.kafka.storage..",
                "org.apache.kafka.security.."
            )

            // F-08: image holds KRaft cluster-state value objects
            // (TopicsImage, BrokerRegistration, …); it sits above raft/storage
            // and is consumed by metadata/controller — i.e. it is Metadata.
            .layer("Metadata").definedBy(
                "org.apache.kafka.metadata..",
                "org.apache.kafka.coordinator..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.image.."
            )

            // F-02: top-level admin / api are public-facing client surfaces.
            .layer("Clients").definedBy(
                "org.apache.kafka.clients..",
                "org.apache.kafka.admin..",
                "org.apache.kafka.api.."
            )

            .layer("Streams").definedBy("org.apache.kafka.streams..")
            .layer("Connect").definedBy("org.apache.kafka.connect..")
            .layer("Server").definedBy("org.apache.kafka.server..")

            // F-07: Shell is operationally distinct from tools/trogdor and
            // does NOT depend on Streams or Connect; split into its own layer.
            .layer("Shell").definedBy("org.apache.kafka.shell..")

            // F-07: Tooling = tools + trogdor. tools.StreamsResetter
            // legitimately uses kafka-streams; trogdor uses kafka-clients only.
            .layer("Tooling").definedBy(
                "org.apache.kafka.tools..",
                "org.apache.kafka.trogdor.."
            )

            .whereLayer("Core")           .mayNotAccessAnyLayer()
            .whereLayer("Infrastructure") .mayOnlyAccessLayers("Core")
            .whereLayer("Metadata")       .mayOnlyAccessLayers("Core", "Infrastructure")

            // F-04: Clients depends only on Core; Metadata is broker-side
            // and is NOT on kafka-clients's compile classpath.
            .whereLayer("Clients")        .mayOnlyAccessLayers("Core")

            // F-04: Per C-S1, Streams depends only on Clients + Core.
            .whereLayer("Streams")        .mayOnlyAccessLayers("Core", "Clients")

            // F-04: Connect runtime talks to brokers via the public client
            // surface only.
            .whereLayer("Connect")        .mayOnlyAccessLayers("Core", "Clients")

            // Server (broker) legitimately uses Infrastructure and Metadata;
            // it does NOT compile-depend on Clients.
            .whereLayer("Server")         .mayOnlyAccessLayers(
                    "Core", "Infrastructure", "Metadata"
            )

            // F-07: Shell reads KRaft snapshots and the metadata image; it
            // uses the AdminClient. It does NOT use Streams or Connect.
            .whereLayer("Shell")          .mayOnlyAccessLayers(
                    "Core", "Infrastructure", "Metadata", "Clients"
            )

            // F-07: Tooling = tools + trogdor; the union of their needs is
            // Core + Clients + Streams (tools.StreamsResetter only).
            .whereLayer("Tooling")        .mayOnlyAccessLayers(
                    "Core", "Clients", "Streams"
            )

            // F-06: Server is reachable only from itself; this single
            // back-stop subsumes the deleted tooling_must_not_depend_on_
            // broker_internals rule.
            .whereLayer("Server").mayOnlyBeAccessedByLayers("Server")

            .because("INFERRED from the standard Apache Kafka monorepo layout " +
                "(KIP-500 controller/broker split, KIP-405 tiered-storage SPI, " +
                "Connect plugin isolation). The supplied PDF documents Streams' " +
                "parallelism model only and does not prescribe these module " +
                "boundaries; they are CI-level enforcement of the established " +
                "kafka-* artefact split. In particular, kafka-tools / " +
                "kafka-metadata-shell / trogdor are client-side processes that " +
                "talk to a running broker over the wire protocol; the " +
                "Server.mayOnlyBeAccessedByLayers(\"Server\") back-stop above " +
                "forbids them (and any other non-broker layer) from compile-" +
                "depending on broker internals.");

    // =========================================================================
    // Layer-coverage sentinel (F-02)
    // =========================================================================
    //
    // Mirrors the layered rule's .definedBy() globs so a future top-level
    // org.apache.kafka.X package that is NOT placed in any layer fails this
    // rule loudly (consideringOnlyDependenciesInLayers() would otherwise
    // silently filter out edges from the unmapped package).
    @ArchTest
    static final ArchRule every_kafka_package_belongs_to_a_layer =
        classes()
            .that().resideInAPackage("org.apache.kafka..")
            .and().resideOutsideOfPackages(
                "org.apache.kafka.jmh..",
                "org.apache.kafka.test..",
                "org.apache.kafka.tiered..",
                // F-10: org.apache.kafka.message is the message generator
                // (build tool only); the runtime classes live under
                // org.apache.kafka.common.message.*
                "org.apache.kafka.message.."
            )
            .should().resideInAnyPackage(
                "org.apache.kafka.common..",
                "org.apache.kafka.timeline..",
                "org.apache.kafka.deferred..",
                "org.apache.kafka.config..",
                "org.apache.kafka.logger..",
                "org.apache.kafka.network..",
                "org.apache.kafka.queue..",
                "org.apache.kafka.raft..",
                "org.apache.kafka.snapshot..",
                "org.apache.kafka.storage..",
                "org.apache.kafka.security..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.coordinator..",
                "org.apache.kafka.controller..",
                "org.apache.kafka.image..",
                "org.apache.kafka.clients..",
                "org.apache.kafka.admin..",
                "org.apache.kafka.api..",
                "org.apache.kafka.streams..",
                "org.apache.kafka.connect..",
                "org.apache.kafka.server..",
                "org.apache.kafka.shell..",
                "org.apache.kafka.tools..",
                "org.apache.kafka.trogdor.."
            )
            .allowEmptyShould(true)
            .because("F-02: every production package must be assigned to " +
                "exactly one layer. consideringOnlyDependenciesInLayers() " +
                "drops edges whose endpoint is in no layer, so a new " +
                "top-level org.apache.kafka.X package that is missing from " +
                "the layered rule's .definedBy() clauses silently bypasses " +
                "every cross-layer assertion.");

    // =========================================================================
    // Positive C-S1 rule (PDF-grounded) — replaces the prior allow-list rule
    // =========================================================================
    //
    // F-03: the previous streams_uses_only_kafka_client_libraries used
    // onlyDependOnClassesThat() with a third-party allow-list that omitted
    // legitimate Streams runtime dependencies (Jackson, compression codecs).
    // The negative formulation below does not require an exhaustive
    // allow-list, so it does not produce false positives when Streams
    // legitimately uses Jackson / Zstd / LZ4 / Snappy / etc.
    @ArchTest
    static final ArchRule streams_must_not_depend_on_broker_internals =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.streams..")
            .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "org.apache.kafka.server..",
                    "org.apache.kafka.controller..",
                    "org.apache.kafka.coordinator..",
                    "org.apache.kafka.metadata..",
                    "org.apache.kafka.raft..",
                    "org.apache.kafka.snapshot..",
                    "org.apache.kafka.image..",
                    "org.apache.kafka.storage..",
                    "org.apache.kafka.network..",
                    "org.apache.kafka.queue.."
                )
            .allowEmptyShould(true)
            .because("Per page 1 of the supplied Kafka Streams architecture " +
                "page: 'Kafka Streams simplifies application development by " +
                "building on the Kafka producer and consumer libraries'. " +
                "A compile-time edge from Streams into broker-side modules " +
                "(server, raft, controller, coordinator, metadata, snapshot, " +
                "storage, image, network, queue) would force every Streams " +
                "application to ship the broker classpath, contradicting the " +
                "documented client-library positioning.");

    // =========================================================================
    // Intra-layer sibling isolation (the layered API cannot express these)
    // =========================================================================
    // These are INFERRED from the standard Kafka module layout — the PDF says
    // nothing about them.

    @ArchTest
    static final ArchRule metadata_must_not_depend_on_controller =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.metadata..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
            .allowEmptyShould(true)
            .because("INFERRED: the metadata module provides record types and " +
                "the cluster image consumed by the controller; the reverse " +
                "edge would form a cycle.");

    @ArchTest
    static final ArchRule metadata_must_not_depend_on_coordinator =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.metadata..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
            .allowEmptyShould(true)
            .because("INFERRED: the metadata module is a primitive used by the " +
                "coordinator; the reverse edge would form a cycle.");

    @ArchTest
    static final ArchRule controller_must_not_depend_on_coordinator =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.controller..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
            .allowEmptyShould(true)
            .because("INFERRED: the KRaft controller manages cluster-wide state; " +
                "the group/transaction coordinator is broker-local. The two " +
                "are independent state machines.");

    @ArchTest
    static final ArchRule coordinator_must_not_depend_on_controller =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.coordinator..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
            .allowEmptyShould(true)
            .because("INFERRED: the coordinator runs on every broker and must " +
                "not link the KRaft controller (KIP-500 separation between " +
                "broker-local and controller-global concerns).");

    @ArchTest
    static final ArchRule tools_must_not_depend_on_trogdor =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.tools..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.trogdor..")
            .allowEmptyShould(true)
            .because("INFERRED: kafka-tools must not bundle Trogdor agents; " +
                "both are independent operational utilities.");

    @ArchTest
    static final ArchRule trogdor_must_not_depend_on_tools =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.trogdor..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
            .allowEmptyShould(true)
            .because("INFERRED: Trogdor must not bundle administrative CLI " +
                "tooling.");

    // =========================================================================
    // Layer-non-emptiness sentinel (F-11)
    // =========================================================================
    //
    // withOptionalLayers(true) masks a typo in any layer glob (the layer
    // becomes empty and the access matrix is silently disabled). This rule
    // back-stops by failing if NONE of the major layers can be populated,
    // signalling a Maven dependency-resolution problem of the F-01 / F-05
    // shape.
    @ArchTest
    static final ArchRule layers_are_non_empty_after_classpath_setup =
        classes()
            .that().resideInAnyPackage(
                "org.apache.kafka.common..",
                "org.apache.kafka.streams..",
                "org.apache.kafka.clients..",
                "org.apache.kafka.connect..",
                "org.apache.kafka.server..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.tools.."
            )
            .should().bePublic()
            .orShould().bePackagePrivate()
            .because("F-11: the layered rule uses withOptionalLayers(true), " +
                "which masks empty layers caused by a typo or a missing " +
                "Maven dependency. This rule fails noisily when NONE of the " +
                "major layers can be populated, signalling a build-config " +
                "regression of the F-01 / F-05 shape from review #2.");

    // =========================================================================
    // What was deleted (and why)
    // =========================================================================
    //
    // streams_uses_only_kafka_client_libraries
    //     -> replaced by streams_must_not_depend_on_broker_internals (F-03,
    //        F-04). The original used onlyDependOnClassesThat() with a too-
    //        narrow allow-list and produced false positives against the
    //        published kafka-streams JAR (no Jackson, no compression codecs).
    //
    // tooling_must_not_depend_on_broker_internals
    //     -> deleted (F-06). Subsumed by Server.mayOnlyBeAccessedByLayers
    //        ("Server") and by Tooling/Shell.mayOnlyAccessLayers omitting
    //        Server.
    //
    // tools_must_not_depend_on_shell, shell_must_not_depend_on_tools,
    // trogdor_must_not_depend_on_shell, shell_must_not_depend_on_trogdor
    //     -> deleted (F-07). After Shell is split out of Tooling into its
    //        own layer, these are subsumed by the layered rule (Shell is
    //        not in Tooling.mayOnlyAccessLayers, and vice versa).
}
