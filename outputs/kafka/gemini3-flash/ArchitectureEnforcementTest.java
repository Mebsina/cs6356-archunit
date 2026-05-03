import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.base.DescribedPredicate.not;

/**
 * Kafka Architecture Enforcement Test
 *
 * This test suite enforces the architectural integrity of the Apache Kafka
 * project by defining a five-layer structure (Application / Server / Client /
 * Infrastructure / Support) inferred from Kafka's actual module topology.
 *
 * The structure is NOT strict in the textbook sense: Client is allowed to be
 * accessed from Support and Infrastructure to accommodate documented
 * cross-jar DTO relationships (e.g., common.requests carries clients.admin
 * types, server.util.InterBrokerSendThread wraps clients.KafkaClient, and
 * security.CredentialProvider takes clients.admin.ScramMechanism).
 *
 * See the layered_architecture_is_respected rule for canonical layer
 * membership and access policy.
 */
@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class ArchitectureEnforcementTest {

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")
        // Declare the more-specific generated layer FIRST so its glob wins.
        .layer("GeneratedDtos").definedBy(
            "org.apache.kafka.server.log.remote.metadata.storage.generated..")
        .layer("Application").definedBy(
            "org.apache.kafka.streams..",
            "org.apache.kafka.connect..")
        .layer("Client").definedBy(
            "org.apache.kafka.clients..")
        .layer("Server").definedBy(
            "org.apache.kafka.controller..",
            "org.apache.kafka.metadata..",
            "org.apache.kafka.raft..",
            "org.apache.kafka.image..",
            "org.apache.kafka.snapshot..",
            "org.apache.kafka.server.log..",
            "org.apache.kafka.server.share..",
            "org.apache.kafka.server.purgatory..",
            "org.apache.kafka.server.network..",
            "org.apache.kafka.server.quota..")
        .layer("Infrastructure").definedBy(
            "org.apache.kafka.storage..",
            "org.apache.kafka.security..")
        .layer("Support").definedBy(
            "org.apache.kafka.common..",
            "org.apache.kafka.server.common..",
            "org.apache.kafka.server.util..",
            "org.apache.kafka.server.metrics..",
            "org.apache.kafka.server.config..",
            "org.apache.kafka.server.fault..",
            "org.apache.kafka.server.authorizer..",
            "org.apache.kafka.server.immutable..",
            "org.apache.kafka.server.record..",
            "org.apache.kafka.server.storage..",
            "org.apache.kafka.server.policy..",
            "org.apache.kafka.server.telemetry..",
            "org.apache.kafka.config..",
            "org.apache.kafka.deferred..",
            "org.apache.kafka.queue..",
            "org.apache.kafka.timeline..",
            "org.apache.kafka.metadata.properties..")

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        // Server.mayOnlyBeAccessedByLayers("Application") is intentionally tight.
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server", "Support", "Infrastructure")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure")
        .whereLayer("GeneratedDtos").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Client", "Infrastructure", "Support")
        .because("Layer model derived from Kafka's actual module topology. Client may be accessed from Support and Infrastructure to accommodate documented cross-jar DTO carve-outs. metadata.properties.. lives in Support because MetaProperties / PropertiesUtils are value types used by storage.LogManager. server.log.remote.metadata.storage.generated.. lives in GeneratedDtos as it contains generated message code.");

    @ArchTest
    public static final ArchRule streams_should_not_depend_on_connect = noClasses()
        .that().resideInAPackage("org.apache.kafka.streams..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("Streams and Connect are parallel application frameworks.");

    @ArchTest
    public static final ArchRule connect_should_not_depend_on_streams = noClasses()
        .that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
        .because("Streams and Connect are parallel application frameworks.");

    private static final String[] BROKER_INTERNAL_PACKAGES = {
        "org.apache.kafka.controller..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.image..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.storage..",
        "org.apache.kafka.security..",
        "org.apache.kafka.server.log..",
        "org.apache.kafka.server.share..",
        "org.apache.kafka.server.purgatory..",
        "org.apache.kafka.server.network..",
        "org.apache.kafka.server.quota.."
    };

    @ArchTest
    public static final ArchRule streams_should_not_depend_on_broker_internals = noClasses()
        .that().resideInAPackage("org.apache.kafka.streams..")
        .should().dependOnClassesThat().resideInAnyPackage(BROKER_INTERNAL_PACKAGES)
        .because("Streams talks to brokers via the public clients API only.");

    @ArchTest
    public static final ArchRule connect_should_not_depend_on_broker_internals = noClasses()
        .that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAnyPackage(BROKER_INTERNAL_PACKAGES)
        .because("Connect talks to brokers via the public clients API only.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("Raft is a primitive consumed by the controller, not vice versa.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_metadata = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata..")
        .because("metadata models are consumed by raft callers, not by raft itself.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_image = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
        .because("Raft must not depend on the metadata image data model.");

    @ArchTest
    public static final ArchRule metadata_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat(resideInAPackage("org.apache.kafka.controller..")
            .and(not(simpleNameEndingWith("ControllerRequestContext"))))
        .because("metadata is the controller's domain model. The ONLY allowed back-edge is metadata.authorizer.{ClusterMetadataAuthorizer, AclMutator} forwarding ACL mutations through controller.ControllerRequestContext.");

    @ArchTest
    public static final ArchRule image_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.image..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("image is consumed by the controller, never the other way.");

    @ArchTest
    public static final ArchRule snapshot_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.snapshot..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("snapshots are written by the controller, not consumers of it.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_security = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.security..")
        .because("Log storage is independent of authentication and delegation.");

    @ArchTest
    public static final ArchRule security_should_not_depend_on_storage = noClasses()
        .that().resideInAPackage("org.apache.kafka.security..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.storage..")
        .because("Security primitives must not couple to log-storage internals.");

    @ArchTest
    public static final ArchRule storage_may_reference_metadata_config_repository_only = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat(resideInAPackage("org.apache.kafka.metadata..")
            .and(not(simpleNameEndingWith("ConfigRepository"))))
        .because("LogManager is allowed to depend on metadata.ConfigRepository (SPI). Any other storage->metadata reference is a regression.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_clients = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
        .because("Storage must not pull in the public clients API; the only Infrastructure->Client edge is security.CredentialProvider <-> clients.admin.ScramMechanism.");

    @ArchTest
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer paths must not pull in admin-only types.");
}
