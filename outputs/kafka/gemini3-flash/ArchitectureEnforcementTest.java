import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Kafka Architecture Enforcement Test
 *
 * Five-layer model derived from Kafka's actual module topology. The model is
 * non-strict; canonical layer membership and the full list of cross-jar
 * carve-outs live in the layered_architecture_is_respected rule's because()
 * clause. Update both places together.
 */
@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class ArchitectureEnforcementTest {

    // ---------------------------------------------------------------------
    // Single source of truth for carve-out package globs. Reused by the
    // layered rule's Server predicate AND by the explicit noClasses() rules
    // below, so the two cannot drift apart.
    // ---------------------------------------------------------------------

    private static final String METADATA_PROPERTIES_PKG =
        "org.apache.kafka.metadata.properties..";
    private static final String GENERATED_DTOS_PKG =
        "org.apache.kafka.server.log.remote.metadata.storage.generated..";

    /** Server layer = controller/metadata/raft/image/snapshot/server.{log,share,…}
     *  MINUS the carve-outs that have been promoted to Support / GeneratedDtos. */
    private static final DescribedPredicate<JavaClass> SERVER_LAYER_PREDICATE =
        resideInAnyPackage(
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
            .and(not(resideInAPackage(METADATA_PROPERTIES_PKG)))
            .and(not(resideInAPackage(GENERATED_DTOS_PKG)))
            .as("Server (controller, metadata\\properties, raft, image, snapshot, server.{log\\generated, share, purgatory, network, quota})");

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")
        .layer("Application").definedBy(
            "org.apache.kafka.streams..",
            "org.apache.kafka.connect..")
        .layer("Client").definedBy(
            "org.apache.kafka.clients..")
        .layer("Server").definedBy(SERVER_LAYER_PREDICATE)
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
            METADATA_PROPERTIES_PKG)
        .layer("GeneratedDtos").definedBy(GENERATED_DTOS_PKG)

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Client").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Support", "Infrastructure")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Client", "Infrastructure")
        .whereLayer("GeneratedDtos").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Client", "Infrastructure", "Support")
        .because("Layer model derived from Kafka's actual module topology. Documented cross-jar carve-outs: (1) common.requests <-> clients.admin DTOs, (2) server.util.InterBrokerSendThread wraps clients.KafkaClient, (3) security.CredentialProvider takes clients.admin.ScramMechanism, (4) storage.LogManager reads metadata.properties.{MetaProperties,PropertiesUtils} and the metadata.ConfigRepository SPI, (5) storage.ProducerStateManager reads/writes the generated ProducerSnapshot message under server.log.remote.metadata.storage.generated. Carve-outs (4) and (5) are realized by promoting metadata.properties.. and the .generated.. sub-tree out of Server using the SERVER_LAYER_PREDICATE.");

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
        .should().dependOnClassesThat(
            resideInAPackage("org.apache.kafka.controller..")
                .and(not(simpleNameEndingWith("ControllerRequestContext"))))
        .because("metadata is the controller's domain model. The ONLY allowed back-edge is metadata.authorizer.{ClusterMetadataAuthorizer,AclMutator} forwarding ACL mutations through controller.ControllerRequestContext.");

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
    public static final ArchRule storage_may_reference_metadata_only_via_spi_or_properties =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat(
                resideInAPackage("org.apache.kafka.metadata..")
                    .and(not(simpleNameEndingWith("ConfigRepository")))
                    .and(not(resideInAPackage(METADATA_PROPERTIES_PKG))))
            .because("LogManager is allowed to depend on metadata.ConfigRepository (SPI) and metadata.properties.{MetaProperties, PropertiesUtils} (value types read from each log dir's meta.properties file at startup). Any OTHER storage->metadata reference is a regression.");

    @ArchTest
    public static final ArchRule metadata_must_keep_config_repository_spi =
        classes()
            .that().haveSimpleName("ConfigRepository")
            .and().resideInAPackage("org.apache.kafka.metadata..")
            .should().beInterfaces()
            .because("storage.LogManager treats metadata.ConfigRepository as the ONLY allowed cross-edge into metadata. If this class is renamed or made non-interface, update storage_may_reference_metadata_only_via_spi_or_properties in lockstep.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_clients = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
        .because("Storage must not pull in the public clients API. The only Infrastructure->Client edge is security.CredentialProvider <-> clients.admin.ScramMechanism (security, not storage).");

    @ArchTest
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer paths must not pull in admin-only types.");
}
