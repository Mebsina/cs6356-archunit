# Adversarial Review #4 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: gemini3-flash
**Reviewer Model**: opus-4-7
**Review**: #4
**File reviewed**: `outputs/kafka/gemini3-flash/ArchitectureEnforcementTest.java`
**Surefire evidence**: `outputs/kafka/gemini3-flash/target/surefire-reports/ArchitectureEnforcementTest.txt`
**Architecture documentation**: `inputs/java/7_apache_kafka.pdf`
**Package structure**: `inputs/java/7_apache_kafka.txt`
**Previous reviews**: [`review1-by-opus-4-7.md`](./review1-by-opus-4-7.md), [`review2-by-opus-4-7.md`](./review2-by-opus-4-7.md), [`review3-by-opus-4-7.md`](./review3-by-opus-4-7.md)

- **Tests run**: 16 (was 14) — **2 failed**, 14 passed.
- **Layered rule violations**: **29** (was 33).
- **`storage_may_reference_metadata_config_repository_only` violations**: **4** (newly added rule, fails on first run).

---

## Executive Summary

The author applied Review #3's patch verbatim, including the new `GeneratedDtos` layer, the `metadata.properties..` re-mapping into Support, the `Infrastructure → Client` carve-out, and the two new strictness guards (`storage_should_not_depend_on_clients`, `storage_may_reference_metadata_config_repository_only`). Surefire shows 14/16 passing, but the two remaining failures are **both caused by structural defects in my own Review #3 recommendations**:

- **CRIT-D1** Review #3 advised "declare the more-specific generated layer first so its glob wins." That advice is wrong. ArchUnit ≥ 1.x's `layeredArchitecture().layer(...).definedBy(packageIdentifier...)` does **not** resolve overlapping layer definitions by declaration order — a class that matches more than one layer's package globs is treated as belonging to **all** matching layers, and access checks fire for each of them. As a result, `metadata.properties.MetaProperties` is now in both `Server` (via `metadata..`) and `Support` (via `metadata.properties..`); `server.log.remote.metadata.storage.generated.ProducerSnapshot` is in both `Server` (via `server.log..`) and `GeneratedDtos`. The Server-access constraint (`mayOnlyBeAccessedByLayers("Application")`) fires on every reference from `storage.internals.log.*` (Infrastructure) to either set, accounting for **all 29 of the residual layered-rule violations**.

- **CRIT-D2** Review #3's `storage_may_reference_metadata_config_repository_only` rule excluded only the `*ConfigRepository` predicate from `metadata..`. It did not also exclude `metadata.properties..`, even though Review #3 *simultaneously* recommended `metadata.properties..` as a documented carve-out into Support. The rule therefore re-flags the four `LogManager.loadDirectoryIds` references that the layered rule was meant to allow.

Both defects are **rule-implementation bugs**, not architectural defects in the Kafka codebase. The actual `org.apache.kafka.*` package graph is unchanged from Review #3 and remains in a healthy state — the four real seams identified in Review #3 (`storage.LogManager → metadata.ConfigRepository`, `storage.LogManager → metadata.properties.*`, `storage.ProducerStateManager → ...generated.ProducerSnapshot`, `security.CredentialProvider → clients.admin.ScramMechanism`) still hold; only `security.CredentialProvider → clients.admin` is now correctly silenced (DEBT-A3 fix worked, 0 hits in surefire). The remaining three seams need a different ArchUnit construction — one that actually subtracts the carve-out sub-packages from the broader layer.

**Overall verdict: FAIL** — 2 of 16 tests fail. The failures are entirely caused by Review #3's incorrect ArchUnit usage and are fully resolvable with predicate-based layer definitions (CRIT-D1 fix) plus a single additional `.and(not(...))` clause on the metadata SPI rule (CRIT-D2 fix). After applying the patch in this review, the suite should reach 16/16 green.

---

## What Changed Since Review #3 (Verification)

| Rule | R3 status | R4 status | Notes |
|---|---|---|---|
| `layered_architecture_is_respected` | FAIL (33) | **FAIL (29)** | -4 hits because Infrastructure→Client carve-out cleared the 4 `security.CredentialProvider` violations. Remaining 29 = 22 ProducerSnapshot + 7 LogManager → metadata. **All caused by overlapping `definedBy` globs (CRIT-D1).** |
| `streams_should_not_depend_on_connect` | PASS | PASS | |
| `connect_should_not_depend_on_streams` | PASS | PASS | |
| `streams_should_not_depend_on_broker_internals` | PASS | PASS | |
| `connect_should_not_depend_on_broker_internals` | PASS | PASS | |
| `raft_should_not_depend_on_controller` | PASS | PASS | |
| `raft_should_not_depend_on_metadata` | PASS | PASS | |
| `raft_should_not_depend_on_image` | PASS | PASS | |
| `metadata_should_not_depend_on_controller` | PASS (sub-package carve-out) | PASS (now uses tighter `simpleNameEndingWith("ControllerRequestContext")` predicate per Review #3 MED-A2) | Successfully tightened. |
| `image_should_not_depend_on_controller` | PASS | PASS | |
| `snapshot_should_not_depend_on_controller` | PASS | PASS | |
| `storage_should_not_depend_on_security` | PASS | PASS | |
| `security_should_not_depend_on_storage` | PASS | PASS | |
| `storage_should_not_depend_on_clients` | (added) | PASS | New rule from Review #3 DEBT-A3 — passes cleanly, confirming `storage..` is fully disjoint from `clients..`. |
| `storage_may_reference_metadata_config_repository_only` | (added) | **FAIL (4)** | New rule from Review #3 DEBT-A2 — fails because the predicate is missing the `metadata.properties..` carve-out (CRIT-D2). |
| `core_client_should_not_depend_on_admin` | PASS | PASS | |

**Breakdown of the 29 residual layered violations:**

| Origin → Target | Count | Why it fires (after Review #3 patch) |
|---|---:|---|
| `storage.internals.log.LogManager` → `metadata.ConfigRepository` | 3 | `metadata..` glob → Server layer; `Server.mayOnlyBeAccessedByLayers("Application")` rejects Infrastructure access. *Not* covered by the `metadata.properties..` Support carve-out (this class is at `metadata.ConfigRepository`, not under `metadata.properties..`). |
| `storage.internals.log.LogManager` → `metadata.properties.MetaProperties / Builder / PropertiesUtils` | 4 | The class matches BOTH `metadata..` (Server) and `metadata.properties..` (Support). ArchUnit fires on the Server membership and the access constraint rejects Infrastructure → Server even though it would accept Infrastructure → Support. **CRIT-D1**. |
| `storage.internals.log.ProducerStateManager` → `server.log.remote.metadata.storage.generated.ProducerSnapshot$*` | 22 | The class matches BOTH `server.log..` (Server) and `server.log.remote.metadata.storage.generated..` (GeneratedDtos). Same precedence problem. **CRIT-D1**. |

**`storage_may_reference_metadata_config_repository_only` violations (4):**

| Reference | Why it fires |
|---|---|
| `LogManager → metadata.properties.MetaProperties$Builder.<init>(Properties)` | predicate matches `metadata..` AND simple name (`Builder`) does not end with `ConfigRepository` → flagged. |
| `LogManager → metadata.properties.MetaProperties$Builder.build()` | same |
| `LogManager → metadata.properties.MetaProperties.directoryId()` | same |
| `LogManager → metadata.properties.PropertiesUtils.readPropertiesFile(String)` | same |

Same root cause: Review #3 added `metadata.properties..` as a Support carve-out for the layered rule but **did not propagate that exception to the explicit `noClasses()` SPI rule**.

---

## Findings

### CRIT-D1 — Overlapping `definedBy` globs do not honour declaration order

```
SEVERITY: CRITICAL
Category: ArchUnit Misuse (correction to Review #3 DEBT-A1 / DEBT-A2 advice)
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
Review #3 advised:

> "Declare the more-specific generated layer FIRST so its glob wins. (Verify the precedence behaviour with ArchUnit's docs for the version used — `archunit.version 1.3.0` per the pom resolves overlapping `definedBy` by declaration order.)"

That advice is **wrong** for `layeredArchitecture()` in ArchUnit 1.x. The `LayerDefinition.definedBy(String... packageIdentifiers)` API performs set-membership matching: a class is added to *every* layer whose `definedBy` glob it matches, regardless of declaration order. The "first match wins" hint applies to predicate-driven matchers in some other parts of ArchUnit, not to `layeredArchitecture()`.

Empirical evidence in surefire:

- `org.apache.kafka.metadata.properties.MetaProperties$Builder` matches both `metadata..` (Server) and `metadata.properties..` (Support). Surefire shows it triggering the `Server.mayOnlyBeAccessedByLayers("Application")` constraint when accessed from `storage..` — i.e. ArchUnit treated it as a Server class even though Support was declared.
- `org.apache.kafka.server.log.remote.metadata.storage.generated.ProducerSnapshot` matches both `server.log..` (Server) and the GeneratedDtos glob (declared first). Surefire reports 22 Server-access violations on it — same set-membership behaviour.

**Why it matters:**
The "GeneratedDtos / metadata.properties move" recommended in Review #3 produces zero suppression effect. All 29 residual layered-rule violations stem from this single API misuse.

**How to fix it:**
Use the **predicate-based** overload, `LayerDefinition.definedBy(DescribedPredicate<? super JavaClass>)`, which lets the Server layer explicitly subtract the carve-out sub-packages:

```java
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;

private static DescribedPredicate<JavaClass> serverLayerExcludingCarveOuts() {
    return resideInAnyPackage(
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
        .and(not(resideInAPackage("org.apache.kafka.metadata.properties..")))
        .and(not(resideInAPackage(
            "org.apache.kafka.server.log.remote.metadata.storage.generated..")));
}
```

Then declare:

```java
.layer("Server").definedBy(serverLayerExcludingCarveOuts())
.layer("Support").definedBy(
        // existing globs, including metadata.properties..
        "org.apache.kafka.metadata.properties..",
        // …)
.layer("GeneratedDtos").definedBy(
        "org.apache.kafka.server.log.remote.metadata.storage.generated..")
```

After this change, `metadata.properties.*` and `...generated.*` classes are members of *exactly one* layer each (Support and GeneratedDtos respectively), the Server-access constraint no longer fires on them, and the 29 layered-rule violations resolve to zero.

Verification step: at the bottom of the file, add a sanity guard so a future maintainer cannot reintroduce overlapping globs:

```java
@ArchTest
public static final ArchRule no_class_belongs_to_more_than_one_layer =
    classes()
        .that().resideInAnyPackage("org.apache.kafka.metadata.properties..")
        .should().resideOutsideOfPackages(
            "org.apache.kafka.controller..",
            // (anything else that the Server predicate now subtracts)
            "org.apache.kafka.server.log.remote.metadata.storage.generated..")
        .because("Layer carve-outs must remain non-overlapping; if you re-add " +
                 "a metadata.properties.. class to metadata..*, update " +
                 "serverLayerExcludingCarveOuts() to subtract it again.");
```

(This is a smoke test against future regressions; it is not strictly required for correctness.)

---

### CRIT-D2 — `storage_may_reference_metadata_config_repository_only` re-flags the `metadata.properties..` carve-out

```
SEVERITY: HIGH
Category: Rule Logic Defect (correction to Review #3 DEBT-A2 advice)
Affected Rule / Constraint: storage_may_reference_metadata_config_repository_only
```

**What is wrong:**
Review #3's recommended rule:

```java
@ArchTest
public static final ArchRule storage_may_reference_metadata_config_repository_only = noClasses()
    .that().resideInAPackage("org.apache.kafka.storage..")
    .should().dependOnClassesThat(
        resideInAPackage("org.apache.kafka.metadata..")
        .and(not(simpleNameEndingWith("ConfigRepository"))))
    .because("LogManager is allowed to depend on metadata.ConfigRepository (SPI). " +
             "Any other storage->metadata reference is a regression.");
```

…explicitly carves out `*ConfigRepository` simple names but not `metadata.properties..`, even though the *same* Review #3 patch declared `metadata.properties..` a Support member because `LogManager.loadDirectoryIds` legitimately uses `MetaProperties$Builder` and `PropertiesUtils`. The result is that the rule fails on exactly those legitimate uses.

**Why it matters:**
The two carve-outs (the layered rule's Support placement of `metadata.properties..` and the explicit SPI rule) must agree, or the test contradicts itself. Today, they don't.

**How to fix it:**
Add a second `.and(not(...))` to subtract the `metadata.properties..` sub-tree:

```java
@ArchTest
public static final ArchRule storage_may_reference_metadata_only_via_spi_or_properties = noClasses()
    .that().resideInAPackage("org.apache.kafka.storage..")
    .should().dependOnClassesThat(
        resideInAPackage("org.apache.kafka.metadata..")
        .and(not(simpleNameEndingWith("ConfigRepository")))
        .and(not(resideInAPackage("org.apache.kafka.metadata.properties.."))))
    .because("LogManager is allowed to depend on metadata.ConfigRepository (SPI) " +
             "and metadata.properties.{MetaProperties, PropertiesUtils} (value " +
             "types read from each log dir's meta.properties file at startup). " +
             "Any OTHER storage->metadata reference is a regression.");
```

The rule's name is also updated to reflect both carve-outs (`storage_may_reference_metadata_only_via_spi_or_properties`).

---

### MED-D1 — `storage_may_reference_metadata_*` rule will silently pass if no `*ConfigRepository` class exists

```
SEVERITY: MEDIUM
Category: Vacuous-becoming-Vacuous Risk
Affected Rule / Constraint: storage_may_reference_metadata_config_repository_only
```

**What is wrong:**
The exception is `simpleNameEndingWith("ConfigRepository")`. Today exactly one class matches: `org.apache.kafka.metadata.ConfigRepository`. If a future refactor renames this interface (e.g., to `ConfigRepositoryProvider` or `BrokerConfigSource`), the predicate will no longer match it, and **the rule will fail on every storage→metadata.ConfigRepository* reference** even though the design intent is unchanged. The rule is a freeze-frame on a class name, not on the architectural seam.

**Why it matters:**
A refactor that the team considers semantically neutral will trip CI for non-architectural reasons.

**How to fix it:**
Either (a) name the class fully-qualified in the carve-out, or (b) place an explicit forwarding interface where storage can refer to it. Option (a):

```java
.should().dependOnClassesThat(
    resideInAPackage("org.apache.kafka.metadata..")
    .and(not(JavaClass.Predicates.equivalentTo(
        org.apache.kafka.metadata.ConfigRepository.class)))
    .and(not(resideInAPackage("org.apache.kafka.metadata.properties.."))))
```

…but this requires the test classpath to *contain* the `ConfigRepository` class at compile time. That is true here because the `metadata` module is on the classpath, but this would not work if the file were ever moved to a project that doesn't depend on the metadata module.

Option (b), portable: keep the simple-name predicate, add a guardrail rule that asserts the SPI class is still where it is supposed to be:

```java
@ArchTest
public static final ArchRule metadata_must_keep_config_repository_spi =
    classes()
        .that().haveSimpleName("ConfigRepository")
        .and().resideInAPackage("org.apache.kafka.metadata..")
        .should().beInterfaces()
        .because("storage.LogManager treats metadata.ConfigRepository as the " +
                 "ONLY allowed cross-edge into metadata. If this class is " +
                 "renamed or made non-interface, update " +
                 "storage_may_reference_metadata_only_via_spi_or_properties " +
                 "in lockstep.");
```

This converts the implicit dependency into an explicit one; the test fails loudly if the SPI is renamed.

---

### MED-D2 — Header comment overstates the carve-out list

```
SEVERITY: LOW
Category: Documentation
Affected Rule / Constraint: Class-level Javadoc
```

**What is wrong:**
The header now says:

> "the structure is NOT strict in the textbook sense: Client is allowed to be accessed from Support and Infrastructure to accommodate documented cross-jar DTO relationships (e.g., common.requests carries clients.admin types, server.util.InterBrokerSendThread wraps clients.KafkaClient, and security.CredentialProvider takes clients.admin.ScramMechanism)."

But the layered rule's `because()` no longer mentions all three; it now cites only the high-level rationale. The two should agree, or both should reference a single canonical location.

**How to fix it:**
Either (a) move the carve-out list into the `because()` (so both render in failure messages) or (b) shorten the Javadoc to point at the rule:

```java
/**
 * Kafka Architecture Enforcement Test
 *
 * Five-layer model derived from the actual Kafka module topology. Several
 * cross-jar DTO carve-outs make the model non-strict; canonical layer
 * membership and the full list of accepted carve-outs live in the
 * 'layered_architecture_is_respected' rule's because() clause. Update both
 * places together.
 */
```

---

### LOW-D1 — Two failing tests is a regression vs. Review #3

```
SEVERITY: LOW
Category: Process / Self-correction note
Affected Rule / Constraint: Both failing rules
```

**What is wrong:**
Review #3 declared "PASS WITH WARNINGS" with one failing rule (33 violations). Review #4's surefire shows two failing rules (29 + 4 violations). Numerically the new rule is a regression. Both failures, however, originate in Review #3's recommended patch, not in the underlying Kafka codebase.

**Why it matters:**
This is worth recording because it changes the way to evaluate "review progress": a review that introduces a new strictness rule and then fails it because its own carve-out set was incomplete is not free progress — it costs the next reviewer extra triage. The lesson, both for me and for any subsequent reviews:

1. New strictness rules and the corresponding carve-out sub-package lists must be derived from the same source-of-truth predicate. In practice that means defining one `static final DescribedPredicate<JavaClass> METADATA_CARVE_OUT = ...` and reusing it in both the layered rule and any explicit `noClasses()` rule.
2. When recommending an ArchUnit construction, claim a specific behaviour (e.g., "more-specific glob wins") only if it is verified in that version of the library, not inferred from other libraries' conventions.

**How to fix it:**
The patch below applies (1). No code change required for the meta-lesson.

---

## Summary Table

| ID | Severity | Category | Affected Rule / Constraint | Estimated violations cleared |
|---|---|---|---|---:|
| CRIT-D1 | CRITICAL | ArchUnit Misuse (overlapping `definedBy`) | layered_architecture_is_respected | 29 |
| CRIT-D2 | HIGH | Rule Logic Defect (missing carve-out) | storage_may_reference_metadata_config_repository_only | 4 |
| MED-D1 | MEDIUM | Vacuous-becoming risk | storage_may_reference_metadata_config_repository_only | 0 |
| MED-D2 | LOW | Documentation | Header Javadoc | 0 |
| LOW-D1 | LOW | Process | Both failing rules | 0 |

After applying CRIT-D1 + CRIT-D2 the suite reaches 16/16 green.

---

## Recommended Patch (consolidated)

```java
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
    // below, so the two cannot drift apart (Review #4 LOW-D1).
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
            .as("Server (controller, metadata\\properties, raft, image, snapshot, " +
                "server.{log\\generated, share, purgatory, network, quota})");

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")
        .layer("Application").definedBy(
            "org.apache.kafka.streams..",
            "org.apache.kafka.connect..")
        .layer("Client").definedBy(
            "org.apache.kafka.clients..")
        .layer("Server").definedBy(SERVER_LAYER_PREDICATE)            // CRIT-D1
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
            METADATA_PROPERTIES_PKG)                                  // CRIT-D1
        .layer("GeneratedDtos").definedBy(GENERATED_DTOS_PKG)         // CRIT-D1

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Client").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Support", "Infrastructure")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Client", "Infrastructure")
        .whereLayer("GeneratedDtos").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Client", "Infrastructure", "Support")
        .because("Layer model derived from Kafka's actual module topology. " +
                 "Documented cross-jar carve-outs: " +
                 "(1) common.requests <-> clients.admin DTOs, " +
                 "(2) server.util.InterBrokerSendThread wraps clients.KafkaClient, " +
                 "(3) security.CredentialProvider takes clients.admin.ScramMechanism, " +
                 "(4) storage.LogManager reads metadata.properties.{MetaProperties,PropertiesUtils} " +
                 "and the metadata.ConfigRepository SPI, " +
                 "(5) storage.ProducerStateManager reads/writes the generated " +
                 "ProducerSnapshot message under server.log.remote.metadata.storage.generated. " +
                 "Carve-outs (4) and (5) are realized by promoting metadata.properties.. and " +
                 "the .generated.. sub-tree out of Server using the SERVER_LAYER_PREDICATE.");

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
        .because("metadata is the controller's domain model. The ONLY allowed " +
                 "back-edge is metadata.authorizer.{ClusterMetadataAuthorizer,AclMutator} " +
                 "forwarding ACL mutations through controller.ControllerRequestContext.");

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

    // CRIT-D2: include the metadata.properties.. carve-out alongside *ConfigRepository.
    @ArchTest
    public static final ArchRule storage_may_reference_metadata_only_via_spi_or_properties =
        noClasses()
            .that().resideInAPackage("org.apache.kafka.storage..")
            .should().dependOnClassesThat(
                resideInAPackage("org.apache.kafka.metadata..")
                    .and(not(simpleNameEndingWith("ConfigRepository")))
                    .and(not(resideInAPackage(METADATA_PROPERTIES_PKG))))
            .because("LogManager is allowed to depend on metadata.ConfigRepository " +
                     "(SPI) and metadata.properties.{MetaProperties, PropertiesUtils} " +
                     "(value types read from each log dir's meta.properties file at " +
                     "startup). Any OTHER storage->metadata reference is a regression.");

    // MED-D1 guardrail: SPI class identity check.
    @ArchTest
    public static final ArchRule metadata_must_keep_config_repository_spi =
        classes()
            .that().haveSimpleName("ConfigRepository")
            .and().resideInAPackage("org.apache.kafka.metadata..")
            .should().beInterfaces()
            .because("storage.LogManager treats metadata.ConfigRepository as the " +
                     "ONLY allowed cross-edge into metadata. If this class is " +
                     "renamed or made non-interface, update " +
                     "storage_may_reference_metadata_only_via_spi_or_properties " +
                     "in lockstep.");

    @ArchTest
    public static final ArchRule storage_should_not_depend_on_clients = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
        .because("Storage must not pull in the public clients API. The only " +
                 "Infrastructure->Client edge is security.CredentialProvider " +
                 "<-> clients.admin.ScramMechanism (security, not storage).");

    @ArchTest
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer paths must not pull in admin-only types.");
}
```

Expected outcome after this patch:

| Rule | Status |
|---|---|
| `layered_architecture_is_respected` | PASS (0 violations — CRIT-D1 cleared) |
| 12 single-direction `noClasses()` rules + `streams/connect_should_not_depend_on_*` | PASS (unchanged) |
| `storage_may_reference_metadata_only_via_spi_or_properties` | PASS (CRIT-D2 cleared) |
| `metadata_must_keep_config_repository_spi` | PASS (MED-D1 guardrail) |
| `storage_should_not_depend_on_clients` | PASS (unchanged) |
| **Total** | **17/17 green** |

(Net change: +1 rule from MED-D1, -1 rule renamed from `…_config_repository_only` to `…_via_spi_or_properties`. The 17 count assumes both are kept.)

If the `JavaClass.Predicates`-based Server layer fails to compile due to ArchUnit version skew, the equivalent fall-back is to declare `Server` via two parallel `definedBy(...)` calls (one per non-overlapping glob set) and split the layer model into seven layers with `metadata.properties..` and the `.generated..` tree as their own first-class members. The predicate approach is cleaner and is preferred.
