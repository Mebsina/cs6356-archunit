import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.Location;
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
 * This test suite enforces architectural constraints for Apache Kafka.
 * Note: The 5-layer model is derived from topological analysis of the package graph
 * as the primary documentation (7_apache_kafka.pdf) focuses exclusively on
 * Kafka Streams internals.
 */
@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ImportOption.DoNotIncludeJars.class,
        ArchitectureEnforcementTest.ExcludeBuildArtifacts.class
    })
public class ArchitectureEnforcementTest {

    static class ExcludeBuildArtifacts implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/jmh/") && !location.contains("/test/") && !location.contains("/trogdor/");
        }
    }

    private static final String METADATA_PROPERTIES_PKG = "org.apache.kafka.metadata.properties..";
    private static final String GENERATED_DTOS_PKG = "org.apache.kafka.server.log.remote.metadata.storage.generated..";

    private static final DescribedPredicate<JavaClass> SERVER_LAYER_PREDICATE =
        resideInAnyPackage(
                "org.apache.kafka.controller..",
                "org.apache.kafka.metadata..",
                "org.apache.kafka.raft..",
                "org.apache.kafka.image..",
                "org.apache.kafka.snapshot..",
                "org.apache.kafka.coordinator..",
                "org.apache.kafka.shell..",
                "org.apache.kafka.server.log..",
                "org.apache.kafka.server.share..",
                "org.apache.kafka.server.purgatory..",
                "org.apache.kafka.server.network..",
                "org.apache.kafka.server.quota..")
            .and(not(resideInAPackage(METADATA_PROPERTIES_PKG)))
            .and(not(resideInAPackage(GENERATED_DTOS_PKG)))
            .as("Server (controller, metadata, raft, image, snapshot, coordinator, server.*)");

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")
        .layer("Application").definedBy(
            "org.apache.kafka.streams..",
            "org.apache.kafka.connect..",
            "org.apache.kafka.tools..")
        .layer("Client").definedBy(
            "org.apache.kafka.clients..",
            "org.apache.kafka.admin..",
            "org.apache.kafka.api..")
        .layer("Server").definedBy(SERVER_LAYER_PREDICATE)
        .layer("Infrastructure").definedBy(
            "org.apache.kafka.storage..",
            "org.apache.kafka.security..",
            "org.apache.kafka.network..",
            "org.apache.kafka.tiered..")
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
            "org.apache.kafka.message..",
            "org.apache.kafka.logger..",
            METADATA_PROPERTIES_PKG)
        .layer("GeneratedDtos").definedBy(GENERATED_DTOS_PKG)

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        .whereLayer("Server").mayNotBeAccessedByAnyLayer()
        .whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server", "Support", "Infrastructure")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure", "GeneratedDtos")
        .whereLayer("GeneratedDtos").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure", "Support")
        .ignoreDependency(resideInAPackage("org.apache.kafka.storage.."), simpleNameEndingWith("ConfigRepository"))
        .because("Hierarchy inferred from topology. Support/Client allow cross-jar DTOs. LogManager->ConfigRepository is a documented SPI carve-out.");

    @ArchTest
    public static final ArchRule streams_must_depend_on_consumer_for_fault_tolerance = classes()
        .that().resideInAPackage("org.apache.kafka.streams.processor.internals..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.consumer..")
        .because("7_apache_kafka.pdf: 'Streams leverages the consumer client for fault tolerance.'");

    @ArchTest
    public static final ArchRule streams_should_not_depend_on_connect = noClasses()
        .that().resideInAPackage("org.apache.kafka.streams..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("Parallel application frameworks.");

    @ArchTest
    public static final ArchRule connect_should_not_depend_on_streams = noClasses()
        .that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
        .because("Parallel application frameworks.");

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
        .because("Application layer isolation.");

    @ArchTest
    public static final ArchRule connect_should_not_depend_on_broker_internals = noClasses()
        .that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAnyPackage(BROKER_INTERNAL_PACKAGES)
        .because("Application layer isolation.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("Raft is a primitive consumed by the controller.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_metadata = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata..")
        .because("Metadata consumed by raft callers.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_image = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
        .because("Raft independent of metadata image.");

    @ArchTest
    public static final ArchRule metadata_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat(
            resideInAPackage("org.apache.kafka.controller..")
                .and(not(simpleNameEndingWith("ControllerRequestContext"))))
        .because("Metadata is domain model; ControllerRequestContext is a documented seam.");

    @ArchTest
    public static final ArchRule image_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.image..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("Image consumed by controller.");

    @ArchTest
    public static final ArchRule snapshot_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.snapshot..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("Snapshots written by controller.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_security = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.security..")
        .because("Log storage independent of security.");

    @ArchTest
    public static final ArchRule security_should_not_depend_on_storage = noClasses()
        .that().resideInAPackage("org.apache.kafka.security..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.storage..")
        .because("Security primitives independent of storage.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_clients = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
        .because("Storage must not pull in public clients.");

    @ArchTest
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer paths must not pull in admin-only types.");
}
