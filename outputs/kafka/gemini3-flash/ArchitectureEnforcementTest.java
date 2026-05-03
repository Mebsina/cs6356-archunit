import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Kafka Architecture Enforcement Test
 *
 * This test suite enforces the architectural integrity of the Apache Kafka project by
 * defining a strict layered structure and preventing circular or illegal dependencies
 * between modules.
 *
 * Layer Hierarchy (from Top to Bottom):
 *
 * * Application: High-level frameworks and streaming libraries that leverage client APIs.
 *   - org.apache.kafka.streams..
 *   - org.apache.kafka.connect..
 *
 * * Client: Public interfaces and core client logic for external system interaction.
 *   - org.apache.kafka.clients..
 *   - org.apache.kafka.admin..
 *   - org.apache.kafka.api..
 *
 * * Server: Core broker-side logic, cluster management, and coordination protocols.
 *   - org.apache.kafka.server..
 *   - org.apache.kafka.controller..
 *   - org.apache.kafka.coordinator..
 *   - org.apache.kafka.raft..
 *   - org.apache.kafka.metadata..
 *
 * * Infrastructure: Low-level abstractions for networking, security, and data storage.
 *   - org.apache.kafka.network..
 *   - org.apache.kafka.storage..
 *   - org.apache.kafka.security..
 *   - org.apache.kafka.tiered..
 *
 * * Support: Fundamental utilities, configuration, and shared data structures.
 *   - org.apache.kafka.common..
 *   - org.apache.kafka.config..
 *   - org.apache.kafka.message..
 *   - org.apache.kafka.queue..
 *   - org.apache.kafka.deferred..
 *   - org.apache.kafka.logger..
 *   - org.apache.kafka.timeline..
 *   - org.apache.kafka.image..
 *   - org.apache.kafka.snapshot..
 *
 * Excluded Packages and Rationale:
 *
 * - org.apache.kafka.test.. : Contains test utilities, mocks, and integration test frameworks.
 * - org.apache.kafka.jmh.. : Benchmarking code for performance analysis.
 * - org.apache.kafka.tools.. : Standalone CLI tools for cluster administration.
 * - org.apache.kafka.trogdor.. : Fault injection framework for durability testing.
 * - org.apache.kafka.shell.. : Metadata shell used for interactive debugging.
 */
@AnalyzeClasses(packages = "org.apache.kafka", importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class ArchitectureEnforcementTest {

    // --------------------------------------------------------------------------------
    // Layered Architecture Rules
    // --------------------------------------------------------------------------------

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Application").definedBy("org.apache.kafka.streams..", "org.apache.kafka.connect..")
        .layer("Client").definedBy("org.apache.kafka.clients..", "org.apache.kafka.admin..", "org.apache.kafka.api..")
        .layer("Server").definedBy("org.apache.kafka.server..", "org.apache.kafka.controller..", "org.apache.kafka.coordinator..", "org.apache.kafka.raft..", "org.apache.kafka.metadata..")
        .layer("Infrastructure").definedBy("org.apache.kafka.network..", "org.apache.kafka.storage..", "org.apache.kafka.security..", "org.apache.kafka.tiered..")
        .layer("Support").definedBy(
            "org.apache.kafka.common..", "org.apache.kafka.config..", "org.apache.kafka.message..",
            "org.apache.kafka.queue..", "org.apache.kafka.deferred..", "org.apache.kafka.logger..",
            "org.apache.kafka.timeline..", "org.apache.kafka.image..", "org.apache.kafka.snapshot.."
        )

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        .whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Server", "Client", "Application")
        .whereLayer("Support").mayOnlyBeAccessedByLayers("Infrastructure", "Server", "Client", "Application")
        .because("Higher layers should provide abstractions while lower layers provide foundational implementations.");

    // --------------------------------------------------------------------------------
    // Fine-grained Intra-layer and Module-to-Module Rules
    // --------------------------------------------------------------------------------

    @ArchTest
    public static final ArchRule streams_should_not_depend_on_connect = noClasses()
        .that().resideInAPackage("org.apache.kafka.streams..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("Kafka Streams and Kafka Connect are parallel application frameworks and should remain decoupled.");

    @ArchTest
    public static final ArchRule connect_should_not_depend_on_streams = noClasses()
        .that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
        .because("Kafka Connect and Kafka Streams are parallel application frameworks and should remain decoupled.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_server = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
        .because("Storage components should be independent of high-level broker management logic to allow for modularity.");

    @ArchTest
    public static final ArchRule raft_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("The Raft consensus layer is a fundamental primitive that the Controller uses, not vice versa.");

    @ArchTest
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.admin..")
        .because("The core client library should not depend on the administrative client to maintain a minimal footprint.");
}
