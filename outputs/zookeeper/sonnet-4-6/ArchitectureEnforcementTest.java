/**
 * ArchitectureEnforcementTest
 *
 * <p>Enforces the architectural constraints of Apache ZooKeeper using ArchUnit rules
 * derived from the official ZooKeeper architecture documentation
 * (Copyright © 2008-2013 The Apache Software Foundation).
 *
 * <h2>Documented Layer Hierarchy (bottom to top)</h2>
 * <ol>
 *   <li><b>Infrastructure</b> – Foundational, cross-cutting utilities with no dependency on
 *       any production layer. Provides common helpers, compatibility shims, and general-purpose
 *       utilities consumed by every other layer.
 *       Packages: {@code org.apache.zookeeper.common},
 *                 {@code org.apache.zookeeper.util},
 *                 {@code org.apache.zookeeper.compat},
 *                 {@code org.apache.zookeeper.compatibility}</li>
 *
 *   <li><b>Monitoring</b> – Observability and management support for the running service.
 *       Covers JMX bean registration, pluggable metrics collection (used by Prometheus and
 *       others), and audit-log emission. Depends only on Infrastructure.
 *       Packages: {@code org.apache.zookeeper.jmx},
 *                 {@code org.apache.zookeeper.metrics},
 *                 {@code org.apache.zookeeper.audit}</li>
 *
 *   <li><b>Server</b> – Core distributed coordination server as described in the
 *       "ZooKeeper Components" diagram: the replicated in-memory data tree, transaction log,
 *       snapshots, request processor pipeline, and the atomic-messaging / leader-election
 *       (quorum) protocol. Depends on Infrastructure and Monitoring.
 *       Package: {@code org.apache.zookeeper.server}</li>
 *
 *   <li><b>Client</b> – Client-side connectivity and retry logic that opens exactly one TCP
 *       connection to a ZooKeeper server to submit requests and receive watch events (as
 *       described in section 1.7 of the documentation). Depends on Infrastructure.
 *       Packages: {@code org.apache.zookeeper.client},
 *                 {@code org.apache.zookeeper.retry}</li>
 *
 *   <li><b>API</b> – Public-facing interfaces: the administrative REST/command channel
 *       ({@code admin}) and the interactive command-line interface ({@code cli}). Both expose
 *       operations over the simple API primitives (create, delete, exists, getData, setData,
 *       getChildren, sync) defined in section 1.6. Depends on Client, Server, Monitoring, and
 *       Infrastructure.
 *       Packages: {@code org.apache.zookeeper.admin},
 *                 {@code org.apache.zookeeper.cli}</li>
 *
 *   <li><b>Recipes</b> – Higher-level distributed coordination primitives (leader election,
 *       distributed lock, distributed queue) built exclusively on top of the ZooKeeper client
 *       API, as described in section 1.8 ("Uses"). Must not reach into Server internals.
 *       Package: {@code org.apache.zookeeper.recipes}</li>
 * </ol>
 *
 * <h2>Excluded Packages and Rationale</h2>
 * <ul>
 *   <li>{@code org.apache.zookeeper.graph} – Standalone log-graph visualisation tool.
 *       It is a build-only developer utility with its own dependency tree (Swing, etc.) and
 *       does not participate in the production service architecture. Including it would
 *       introduce false positive layer violations from GUI-specific imports.</li>
 *
 *   <li>{@code org.apache.zookeeper.inspector} – Standalone GUI inspector used for
 *       development-time debugging and exploration of a live ZooKeeper ensemble. Like
 *       {@code graph}, it is a build-only utility that is never deployed as part of the
 *       server or client runtime.</li>
 *
 *   <li>{@code org.apache.zookeeper.test} – Test infrastructure, stubs, and mock
 *       implementations. Test-support classes are not production artifacts and would create
 *       spurious dependency violations if included in layer definitions (e.g., test helpers
 *       that intentionally reach across layers for setup purposes).</li>
 * </ul>
 */

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class ArchitectureEnforcementTest {

    /**
     * ClassFileImporter for the project root package.
     * Used when running rules programmatically outside the JUnit 5 runner.
     * The JUnit 5 runner uses the {@code @AnalyzeClasses} annotation above instead.
     */
    static final JavaClasses ZOOKEEPER_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.apache.zookeeper");

    // =========================================================================
    // LAYERED ARCHITECTURE RULES
    // Enforces that lower layers never depend on higher layers, as described
    // in the ZooKeeper Components architecture diagram.
    // =========================================================================

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .withOptionalLayers(true)

                    .layer("Infrastructure")
                            .definedBy(
                                    "org.apache.zookeeper.common..",
                                    "org.apache.zookeeper.util..",
                                    "org.apache.zookeeper.compat..",
                                    "org.apache.zookeeper.compatibility..")

                    .layer("Monitoring")
                            .definedBy(
                                    "org.apache.zookeeper.jmx..",
                                    "org.apache.zookeeper.metrics..",
                                    "org.apache.zookeeper.audit..")

                    .layer("Server")
                            .definedBy("org.apache.zookeeper.server..")

                    .layer("Client")
                            .definedBy(
                                    "org.apache.zookeeper.client..",
                                    "org.apache.zookeeper.retry..")

                    .layer("API")
                            .definedBy(
                                    "org.apache.zookeeper.admin..",
                                    "org.apache.zookeeper.cli..")

                    .layer("Recipes")
                            .definedBy("org.apache.zookeeper.recipes..")

                    // Infrastructure is the absolute foundation; nothing it contains
                    // should depend on any higher layer.
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

                    // Monitoring observes the service but must not drive Server,
                    // Client, API, or Recipe logic.
                    .whereLayer("Monitoring").mayOnlyAccessLayers("Infrastructure", "Monitoring")

                    // Server implements the coordination protocol and may use
                    // Infrastructure and Monitoring, but must not depend on the Client
                    // library, the API layer, or Recipes.
                    .whereLayer("Server").mayOnlyAccessLayers("Infrastructure", "Monitoring", "Server")

                    // Client connects to a single server over TCP (section 1.7) and
                    // must not reach into Server internals, the API layer, or Recipes.
                    .whereLayer("Client").mayOnlyAccessLayers("Infrastructure", "Client")

                    // API (admin + cli) is the topmost service boundary before Recipes
                    // and must not depend on Recipes, which are consumer-side primitives.
                    .whereLayer("API").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server", "Client", "API")

                    // Recipes are higher-level coordination primitives built on the
                    // ZooKeeper Client API (section 1.8); they must not reach into
                    // Server internals directly.
                    .whereLayer("Recipes").mayOnlyAccessLayers(
                            "Infrastructure", "Client", "Recipes")

                    .because(
                            "The ZooKeeper Components design (section 1.7) prescribes a strict "
                            + "bottom-up dependency flow: Infrastructure → Monitoring → Server / "
                            + "Client → API → Recipes. Lower layers must remain stable and "
                            + "independent of higher-level concerns to support the high-performance, "
                            + "replicated coordination service described in the documentation.");

    // =========================================================================
    // FINE-GRAINED INTRA-LAYER RULES
    // Module-to-module constraints within layers that prevent circular
    // dependencies and enforce parallel-layer isolation.
    // =========================================================================

    /** CLI must not depend on the Admin module (parallel packages within the API layer). */
    @ArchTest
    static final ArchRule cli_must_not_depend_on_admin =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.cli..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.admin.."))
                    .because(
                            "The CLI and Admin modules are parallel sub-components of the API layer. "
                            + "CLI is an interactive command-line front-end while Admin exposes a "
                            + "server-side administrative HTTP channel; coupling them would create a "
                            + "circular dependency path through the API layer.");

    /** Admin must not depend on the CLI module (parallel packages within the API layer). */
    @ArchTest
    static final ArchRule admin_must_not_depend_on_cli =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.admin..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.cli.."))
                    .because(
                            "Admin and CLI are parallel sub-components of the API layer. "
                            + "Admin must not import CLI classes to avoid a circular dependency "
                            + "that would prevent either module from being deployed independently.");

    /** Client layer must not import Server internals. */
    @ArchTest
    static final ArchRule client_must_not_depend_on_server =
            noClasses()
                    .that().resideInAnyPackage(
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                    .because(
                            "The Client layer represents the client-side TCP connection to a single "
                            + "ZooKeeper server (section 1.7). Importing Server internals would "
                            + "conflate client and server boundaries, preventing the client library "
                            + "from being distributed independently of the server.");

    /** Recipes must not reach into Server internals directly. */
    @ArchTest
    static final ArchRule recipes_must_not_depend_on_server =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
                    .because(
                            "Recipes (leader election, distributed lock, queue) are higher-level "
                            + "coordination primitives built exclusively on the ZooKeeper Client API "
                            + "(section 1.8). Direct access to Server internals would break the "
                            + "abstraction and create an undocumented coupling to server-side "
                            + "implementation details.");

    /** Recipes must not depend on the Admin or CLI API modules. */
    @ArchTest
    static final ArchRule recipes_must_not_depend_on_api =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.apache.zookeeper.admin..",
                            "org.apache.zookeeper.cli.."))
                    .because(
                            "Recipes are consumer-side coordination primitives that must only rely on "
                            + "the stable Client API. Depending on Admin or CLI modules would invert "
                            + "the documented layer hierarchy and introduce deployment-time coupling "
                            + "to administrative tooling.");

    /** Metrics module must not depend on the JMX module (parallel Monitoring sub-components). */
    @ArchTest
    static final ArchRule metrics_must_not_depend_on_jmx =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.metrics..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.jmx.."))
                    .because(
                            "Metrics and JMX are parallel monitoring sub-components. The pluggable "
                            + "metrics API must remain independent of the JMX management layer so "
                            + "that alternative metrics providers (e.g., Prometheus) can be "
                            + "substituted without pulling in JMX dependencies.");

    /** JMX module must not depend on the Metrics module (parallel Monitoring sub-components). */
    @ArchTest
    static final ArchRule jmx_must_not_depend_on_metrics =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.jmx..")
                    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.metrics.."))
                    .because(
                            "JMX and Metrics are parallel monitoring sub-components. Allowing JMX to "
                            + "depend on the Metrics API would create a circular dependency within "
                            + "the Monitoring layer and couple the JMX management interface to a "
                            + "specific metrics implementation.");

    /** Audit module must not depend on JMX or Metrics (parallel Monitoring sub-components). */
    @ArchTest
    static final ArchRule audit_must_not_depend_on_jmx_or_metrics =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.audit..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics.."))
                    .because(
                            "Audit logging is a parallel concern within the Monitoring layer alongside "
                            + "JMX and Metrics. Coupling audit to either of those sub-components "
                            + "would make audit log emission contingent on unrelated observability "
                            + "infrastructure and introduce intra-layer circular dependencies.");

    /** Infrastructure packages must not depend on any higher layer. */
    @ArchTest
    static final ArchRule infrastructure_must_not_depend_on_higher_layers =
            noClasses()
                    .that().resideInAnyPackage(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..",
                            "org.apache.zookeeper.server..",
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..",
                            "org.apache.zookeeper.admin..",
                            "org.apache.zookeeper.cli..",
                            "org.apache.zookeeper.recipes.."))
                    .because(
                            "Infrastructure packages (common, util, compat, compatibility) form the "
                            + "absolute foundation of ZooKeeper and are consumed by every other layer. "
                            + "Any upward dependency would create a circular reference that prevents "
                            + "the foundational utilities from being built and tested in isolation, "
                            + "contradicting ZooKeeper's design goal of a stable, reusable base.");
}
