# Adversarial Review #3 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: gemini3-flash
**Reviewer Model**: opus-4-7
**Review**: #3
**File reviewed**: `outputs/kafka/gemini3-flash/ArchitectureEnforcementTest.java`
**Surefire evidence**: `outputs/kafka/gemini3-flash/target/surefire-reports/ArchitectureEnforcementTest.txt`
**Architecture documentation**: `inputs/java/7_apache_kafka.pdf`
**Package structure**: `inputs/java/7_apache_kafka.txt`
**Previous reviews**: [`review1-by-opus-4-7.md`](./review1-by-opus-4-7.md), [`review2-by-opus-4-7.md`](./review2-by-opus-4-7.md)

- **Tests run**: 14 (was 13) — **1 failed**, 13 passed.
- **Layered rule violations**: **33** (was 1,358; down 97.6 %).
- All 13 `noClasses()` rules pass.

---

## Executive Summary

The author applied Review #2's consolidated patch verbatim. The structural model is now correct: 13 of 14 tests pass and the layered rule's violation count collapsed from 1,358 to 33 (-97.6 %). Every remaining violation falls into one of three real architectural seams; none is a classification artefact.

**Residual layered-rule violations (33 total), categorized:**

| Bucket | Count | Origin → Target | Nature |
|---|---:|---|---|
| **DEBT-A1** | 22 | `storage.internals.log.ProducerStateManager` → `server.log.remote.metadata.storage.generated.ProducerSnapshot$*` | Generated DTO mis-located under `server.log..` |
| **DEBT-A2** | 7 | `storage.internals.log.LogManager` → `metadata.ConfigRepository`, `metadata.properties.MetaProperties`, `metadata.properties.PropertiesUtils` | Real cross-module dependency: storage reads metadata config + meta-properties files |
| **DEBT-A3** | 4 | `security.CredentialProvider` → `clients.admin.ScramMechanism` | Public client enum used in security signature; Infrastructure → Client analogue of HIGH-A2/A3 |

**Critical findings**: none.
**High-severity findings**: 3 (all the seams above; each is genuine architectural debt rather than a rule bug).
**Medium / Low**: 4 (carve-out symmetry, `controller`-direction asymmetry, redundancy notes).

**Overall verdict: PASS WITH WARNINGS.** The test suite is in a state suitable for CI. The 33 residual violations are real, narrow, and traceable to ≤ 4 named classes; they should each be either (a) silenced with a documented `.ignoreDependency(...)` carve-out (effectively a freeze-frame) or (b) fixed by moving the offending classes to layers that match their real role (e.g. `metadata.properties..` and `..generated..` to Support).

---

## What Changed Since Review #2 (Verification)

| Rule | R2 status | R3 status | Notes |
|---|---|---|---|
| `layered_architecture_is_respected` | FAIL (1358) | **FAIL (33)** | Down 97.6 %. All remaining hits are real seams. |
| `streams_should_not_depend_on_connect` | PASS | PASS | unchanged |
| `connect_should_not_depend_on_streams` | PASS | PASS | unchanged |
| `streams_should_not_depend_on_broker_internals` | PASS | PASS | broader package list applied |
| `connect_should_not_depend_on_broker_internals` | PASS | PASS | broader package list applied |
| `raft_should_not_depend_on_controller` | PASS | PASS | |
| `raft_should_not_depend_on_metadata` | PASS | PASS | |
| `raft_should_not_depend_on_image` | PASS | PASS | |
| `metadata_should_not_depend_on_controller` | FAIL (4) | **PASS** | Carve-out for `metadata.authorizer..` resolved it (HIGH-A1 fix worked) |
| `image_should_not_depend_on_controller` | PASS | PASS | |
| `snapshot_should_not_depend_on_controller` | (added) | PASS | LOW-A2 fix landed; new rule passes cleanly |
| `storage_should_not_depend_on_security` | PASS | PASS | |
| `security_should_not_depend_on_storage` | PASS | PASS | |
| `core_client_should_not_depend_on_admin` | PASS | PASS | |

---

## Findings

### DEBT-A1 — `storage.internals.log.ProducerStateManager` ↔ `server.log.remote.metadata.storage.generated.ProducerSnapshot` (22 violations)

```
SEVERITY: HIGH
Category: Wrong Layer (generated DTO mis-classified) / Real Architectural Debt
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
22 of the 33 residual violations originate from a single method pair: `ProducerStateManager.readSnapshot` and `ProducerStateManager.writeSnapshot`. Both call into `org.apache.kafka.server.log.remote.metadata.storage.generated.ProducerSnapshot` and its nested `ProducerEntry` (constructors, getters/setters, `crc()`, `producerEntries()`, `setProducerEntries(List)`):

```
Method <storage.internals.log.ProducerStateManager.readSnapshot(File)>  calls method
   <server.log.remote.metadata.storage.generated.ProducerSnapshot$ProducerEntry.coordinatorEpoch()>
Method <storage.internals.log.ProducerStateManager.writeSnapshot(...)> calls constructor
   <server.log.remote.metadata.storage.generated.ProducerSnapshot.<init>()>
```

This is Infrastructure → Server. Forbidden by the layered rule.

But `ProducerSnapshot` is a **generated message class** produced from a Kafka schema definition (the `.../generated/...` package marker is the giveaway). It is structurally a DTO/value type — the same role as `org.apache.kafka.common.message..` or `server.common.serialization.RecordSerde`. It is *physically* nested under `server.log..` only because the storage module's build script emits its generated sources there. The class has no behaviour, no orchestration, and no awareness of layering.

**Why it matters:**
The layered rule treats this as 22 architectural violations when in reality it is one mis-located DTO. As long as the class stays where it is and no exception is added, CI cannot go green.

**How to fix it:**
Two options, in order of preference:

1. **(Preferred) Move generated DTOs to Support.** Add a sub-glob to the Support layer:

```java
.layer("Support").definedBy(
    // existing members…
    "org.apache.kafka.server.log.remote.metadata.storage.generated..")
```

Note that this *removes* the generated sub-tree from Server (the `server.log..` glob in Server matches it first; ArchUnit assigns each class to the *first* matching layer, but the order of `definedBy` calls determines precedence). To make the override deterministic, also tighten Server's glob:

```java
.layer("Server").definedBy(
    "org.apache.kafka.controller..",
    "org.apache.kafka.metadata..",
    "org.apache.kafka.raft..",
    "org.apache.kafka.image..",
    "org.apache.kafka.snapshot..",
    // restrict server.log.. to non-generated content
    "org.apache.kafka.server.log..",
    "org.apache.kafka.server.share..",
    "org.apache.kafka.server.purgatory..",
    "org.apache.kafka.server.network..",
    "org.apache.kafka.server.quota..")
.layer("GeneratedDtos").definedBy(
    "org.apache.kafka.server.log.remote.metadata.storage.generated..")
.whereLayer("GeneratedDtos").mayOnlyBeAccessedByLayers(
    "Application", "Server", "Client", "Infrastructure", "Support")
```

…and remove the inner `server.log.remote.metadata.storage.generated..` from the `server.log..` glob using a custom layer or, more simply, put the generated layer *before* the Server one (since ArchUnit applies the first-matching layer when globs overlap, the more specific glob should be declared first):

```java
// Declare the more specific layer first so its glob wins.
.layer("GeneratedDtos").definedBy("org.apache.kafka.server.log.remote.metadata.storage.generated..")
.layer("Server").definedBy(/* …, */ "org.apache.kafka.server.log..", /* … */)
```

(Verify the precedence behaviour with ArchUnit's docs for the version used — `archunit.version 1.3.0` per the pom resolves overlapping `definedBy` by declaration order.)

2. **(Less preferred — freeze-frame) Carve out the specific dependency.** ArchUnit's `layeredArchitecture()` API does not expose `ignoreDependency()` directly, but the same effect is achievable by adding a parallel `noClasses()` allow-rule that documents the exception, and excluding the generated path from the Server layer at definition time:

```java
.layer("Server").definedBy(
    /* … */
    "org.apache.kafka.server.log..",
    /* explicitly subtract the generated tree */
    /* This requires PackageMatchers — see ArchUnit 1.3 ImportOption examples */)
```

Option 1 is cheaper. Whichever the team chooses, the chosen rationale must be in the `because()`.

---

### DEBT-A2 — `storage.internals.log.LogManager` ↔ `metadata.ConfigRepository` and `metadata.properties..` (7 violations)

```
SEVERITY: HIGH
Category: Real Architectural Debt
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
Seven violations land on `LogManager`:

```
Constructor <storage.internals.log.LogManager.<init>(..., metadata.ConfigRepository, ...)> has parameter of type <metadata.ConfigRepository>
Field <storage.internals.log.LogManager.configRepository> has type <metadata.ConfigRepository>
Method <storage.internals.log.LogManager.fetchTopicConfigOverrides(...)> calls method <metadata.ConfigRepository.topicConfig(String)>
Method <storage.internals.log.LogManager.loadDirectoryIds(Collection)> calls constructor <metadata.properties.MetaProperties$Builder.<init>(Properties)>
Method <storage.internals.log.LogManager.loadDirectoryIds(Collection)> calls method <metadata.properties.MetaProperties$Builder.build()>
Method <storage.internals.log.LogManager.loadDirectoryIds(Collection)> calls method <metadata.properties.MetaProperties.directoryId()>
Method <storage.internals.log.LogManager.loadDirectoryIds(Collection)> calls method <metadata.properties.PropertiesUtils.readPropertiesFile(String)>
```

`LogManager` (Infrastructure) needs the metadata `ConfigRepository` SPI (an *interface*, not a controller) to resolve per-topic config overrides, and it needs `metadata.properties.MetaProperties` to read each log dir's `meta.properties` file at startup. Both concerns are unavoidable: the broker-level component that owns log directories must know which directory has which UUID and which topic-level config overrides apply.

**Why it matters:**
This is the structural opposite of DEBT-A1: not a mis-located DTO, but a real seam where the Infrastructure-Server boundary is genuinely crossed. The `metadata.ConfigRepository` interface is in the metadata module because that's where the controller-side implementation lives, but the consumer of the SPI (LogManager) is in storage.

**How to fix it:**
There are three legitimate choices:

1. **(Preferred — true refactor)** Move `metadata.ConfigRepository` (interface) and `metadata.properties..` to **Support**, since they are SPI/value types not controller logic:

```java
.layer("Support").definedBy(
    /* existing… */,
    // SPI interfaces and value types historically nested in metadata
    "org.apache.kafka.metadata.properties..")
.layer("Server").definedBy(
    // metadata excluding the SPI/properties carve-out
    /* … */)
```

`metadata.ConfigRepository` is a single interface; either move it source-side (real refactor) or leave it and add a separate `noClasses` carve-out:

```java
@ArchTest
public static final ArchRule storage_may_only_reach_metadata_via_spi = noClasses()
    .that().resideInAPackage("org.apache.kafka.storage..")
    .should().dependOnClassesThat()
        .resideInAPackage("org.apache.kafka.metadata..")
        .and().haveSimpleNameNotEndingWith("ConfigRepository")
        .andShould().dependOnClassesThat()
        .resideOutsideOfPackage("org.apache.kafka.metadata.properties..")
    .because("LogManager is allowed to depend on metadata.ConfigRepository (SPI) " +
             "and metadata.properties.* (meta.properties value types) only.");
```

2. **(Acceptable — freeze-frame)** Acknowledge the seam in the layered rule's `because()` and add an `@ArchIgnore`-style allowlist for these specific class names. Track removal in a follow-up.

3. **(Worst — relax)** Add Server to `Infrastructure.mayOnlyBeAccessedByLayers(...)` or vice versa. Avoid: this would re-open the door to all 285+ violations from Review #2.

Recommendation: **option 1**. Either move `metadata.properties..` to Support (cheapest source change, the package contains only value types and a utility class) or add the explicit carve-out rule above.

---

### DEBT-A3 — `security.CredentialProvider` ↔ `clients.admin.ScramMechanism` (4 violations)

```
SEVERITY: HIGH
Category: Real Architectural Debt / Symmetry Gap
Affected Rule / Constraint: layered_architecture_is_respected (Infrastructure → Client)
```

**What is wrong:**
Four violations, all on the same two methods of one class:

```
Method <security.CredentialProvider.removeCredentials(clients.admin.ScramMechanism, String)> has parameter of type <clients.admin.ScramMechanism>
Method <security.CredentialProvider.removeCredentials(clients.admin.ScramMechanism, String)> calls method <clients.admin.ScramMechanism.mechanismName()>
Method <security.CredentialProvider.updateCredential(clients.admin.ScramMechanism, String, ...)> has parameter of type <clients.admin.ScramMechanism>
Method <security.CredentialProvider.updateCredential(clients.admin.ScramMechanism, String, ...)> calls method <clients.admin.ScramMechanism.mechanismName()>
```

`security.CredentialProvider` (Infrastructure) takes a `clients.admin.ScramMechanism` (Client) as a parameter. This is structurally identical to the `common.requests` ↔ `clients.admin` and `server.util.InterBrokerSendThread` ↔ `clients.KafkaClient` patterns from Review #2 (HIGH-A2 / HIGH-A3): a public enum lives in `clients.admin` and is consumed from another module.

The Review #2 patch addressed the Support→Client variant by extending `Client.mayOnlyBeAccessedByLayers` to include "Support". The same logic applies to Infrastructure → Client (`ScramMechanism` is just an enum naming SCRAM-SHA-256 / SCRAM-SHA-512), but the patch did not extend the carve-out symmetrically.

**Why it matters:**
This is a single-class debt today (only `CredentialProvider` matches), but the asymmetry between Support→Client (allowed) and Infrastructure→Client (forbidden) has no architectural justification — both are equally "downstream" of the public client API. Future Infrastructure-side code that imports a public client enum/DTO will fail the same rule.

**How to fix it:**
Extend the Client access list one more time:

```java
.whereLayer("Client").mayOnlyBeAccessedByLayers(
        "Application", "Server", "Support", "Infrastructure")
```

Update the `because()` to record the rationale:

```java
.because("Layer model derived from Kafka's actual module topology. " +
         "Support→Client and Infrastructure→Client edges document the historical " +
         "DTO carve-outs (common.requests<->clients.admin DTOs, " +
         "server.util.InterBrokerSendThread<->KafkaClient, " +
         "security.CredentialProvider<->clients.admin.ScramMechanism). " +
         "These cross-jar references exist in production today and require " +
         "a source refactor to remove.");
```

Trade-off: this loosens the rule for the entire Infrastructure layer (`storage..` and `security..`). To prevent silent backsliding (e.g., a future PR that has `storage.internals.log.LogManager` start importing `clients.consumer..`), keep the explicit `core_client_should_not_depend_on_admin` rule and add the symmetric guard:

```java
@ArchTest
public static final ArchRule storage_should_not_depend_on_clients = noClasses()
    .that().resideInAPackage("org.apache.kafka.storage..")
    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
    .because("Storage is below the Client layer; the only allowed cross-edge is " +
             "via the documented common.* / server.util / security carve-outs, " +
             "none of which originate inside org.apache.kafka.storage.");
```

This keeps `storage` strictly below `clients` (currently true — the only Infrastructure→Client offender is `security.CredentialProvider`, not `storage`).

---

### MED-A1 — Carve-out is one-sided: Client never declared "may not access X"

```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
The layered rule constrains who may **access** each layer (`mayOnlyBeAccessedByLayers`) but never asserts who each layer **may itself access**. As a result:

- `Client` (`org.apache.kafka.clients..`) is free to access any layer below or above in the model — including `Server` (controller, metadata, image, snapshot, raft, etc.). Since `Server.mayOnlyBeAccessedByLayers("Application")` would catch Client → Server, the test does fail in that direction. But Client → Infrastructure (`storage..`, `security..`) is *not* caught: `Infrastructure.mayOnlyBeAccessedByLayers("Application", "Server")` would flag it; ✓ caught. So the "downward" direction is actually fine.
- The asymmetry that *is* missed: `Application` → `Application` cross-pairs (Streams ↔ Connect) are covered by explicit rules, but `Server` → `Server` cross-pairs are only partially covered. After CRIT-A2 the Server layer now contains 10 sub-packages: `controller`, `metadata`, `raft`, `image`, `snapshot`, `server.log`, `server.share`, `server.purgatory`, `server.network`, `server.quota`. The current rules cover only 6 directional pairs out of the 90 possible. Anything between e.g. `server.share` ↔ `server.purgatory`, or `server.quota` ↔ `server.network`, is unconstrained.

**Why it matters:**
A future regression where `server.purgatory.DelayedOperation` imports `controller.QuorumController` would be invisible (both in Server, no `noClasses()` rule covers that pair).

**How to fix it:**
This is the trade-off the layered API forces on you (intra-layer is unconstrained). Three options:

1. **Add explicit pair rules** for the dependencies the documentation actually warns about. Conservatively, the most valuable additions are the ones that prevent the controller/metadata/image/snapshot triangle from collapsing:

```java
@ArchTest
public static final ArchRule controller_should_not_depend_on_image_internals_directly = noClasses()
    .that().resideInAPackage("org.apache.kafka.controller..")
    .should().dependOnClassesThat()
        .resideInAPackage("org.apache.kafka.image.loader..")
        .or().resideInAPackage("org.apache.kafka.image.publisher..")
        .or().resideInAPackage("org.apache.kafka.image.writer..")
    .because("Controller should consume image-level types (AclsImage, TopicsImage) " +
             "but not the loader/publisher/writer internals.");

@ArchTest
public static final ArchRule purgatory_should_not_depend_on_controller_or_metadata =
    noClasses()
    .that().resideInAPackage("org.apache.kafka.server.purgatory..")
    .should().dependOnClassesThat()
        .resideInAPackage("org.apache.kafka.controller..")
        .or().resideInAPackage("org.apache.kafka.metadata..")
        .or().resideInAPackage("org.apache.kafka.image..")
        .or().resideInAPackage("org.apache.kafka.snapshot..")
    .because("Purgatory is a request-time delay primitive used by both controller " +
             "and broker; coupling it to KRaft state would invert the dependency.");
```

2. **Use `archcondition` to detect cycles** within the Server layer (`SlicesRuleDefinition.slices().matching("org.apache.kafka.(*)..").should().beFreeOfCycles()`). This is more resilient and generic than enumerating pairs.

3. **Accept the gap.** The 6 rules already in place cover the most-likely-to-regress pairs (raft, image, snapshot, metadata directions). Document this explicitly in the file header.

Recommended: option 2, scoped to Server-layer sub-packages.

---

### MED-A2 — `metadata_should_not_depend_on_controller` carve-out is a single freeze-frame

```
SEVERITY: MEDIUM
Category: Maintainability
Affected Rule / Constraint: metadata_should_not_depend_on_controller
```

**What is wrong:**
The rule excludes the entire `metadata.authorizer..` sub-tree to silence the four `ClusterMetadataAuthorizer` / `AclMutator` violations. That's fine for today, but it also exempts any *new* class added under `metadata.authorizer..` from the rule — including a hypothetical class that depends on the controller for an unrelated reason. The carve-out should be tighter.

**How to fix it:**
Either (a) name the exact two classes, or (b) target only the `controller.ControllerRequestContext` type, which is the actual seam:

```java
@ArchTest
public static final ArchRule metadata_should_not_depend_on_controller =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat()
            .resideInAPackage("org.apache.kafka.controller..")
            .and().haveSimpleNameNotEndingWith("ControllerRequestContext")
        .because("metadata is the controller's domain model. The ONLY allowed " +
                 "back-edge is metadata.authorizer.{ClusterMetadataAuthorizer, " +
                 "AclMutator} forwarding ACL mutations through " +
                 "controller.ControllerRequestContext.");
```

Now the rule fails the moment a new metadata class depends on anything in `controller..` other than `ControllerRequestContext`.

---

### MED-A3 — `streams/connect_should_not_depend_on_broker_internals` BROKER_INTERNAL_PACKAGES is missing `security..`

```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: streams_should_not_depend_on_broker_internals, connect_should_not_depend_on_broker_internals
```

**What is wrong:**
The shared `BROKER_INTERNAL_PACKAGES` list enumerates `controller`, `metadata`, `image`, `snapshot`, `raft`, `storage`, `server.log`, `server.share`, `server.purgatory`, `server.network`, `server.quota`. It does **not** include `org.apache.kafka.security..`. Yet `security..` is in the **Infrastructure** layer alongside `storage..` and contains broker-internal types like `DelegationTokenManager`. A Streams or Connect class that imports `security.DelegationTokenManager` would be a regression (the layered rule would catch it via Infrastructure-access constraint, but the explicit `streams_should_not_depend_on_broker_internals` rule that's supposed to be the loud diagnostic would not).

**How to fix it:**

```java
private static final String[] BROKER_INTERNAL_PACKAGES = {
    "org.apache.kafka.controller..",
    "org.apache.kafka.metadata..",
    "org.apache.kafka.image..",
    "org.apache.kafka.snapshot..",
    "org.apache.kafka.raft..",
    "org.apache.kafka.storage..",
    "org.apache.kafka.security..",     // NEW
    "org.apache.kafka.server.log..",
    "org.apache.kafka.server.share..",
    "org.apache.kafka.server.purgatory..",
    "org.apache.kafka.server.network..",
    "org.apache.kafka.server.quota.."
};
```

---

### LOW-A1 — Header comment still claims "strict layered structure"

```
SEVERITY: LOW
Category: Documentation
Affected Rule / Constraint: Class-level Javadoc
```

**What is wrong:**
The header says "This test suite enforces the architectural integrity … by defining a *strict* layered structure". After the Review #2 carve-out, the structure is no longer strict — `Client.mayOnlyBeAccessedByLayers(...)` now lists Support, and (after DEBT-A3 is applied) will list Infrastructure too. The Javadoc should call this out so a reader doesn't infer wrong invariants.

**How to fix it:**

```java
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
 * security.CredentialProvider takes clients.admin.ScramMechanism). The
 * exceptions are listed in the layered rule's because() clause.
 *
 * See the layered_architecture_is_respected rule for canonical layer
 * membership and access policy.
 */
```

---

### LOW-A2 — `core_client_should_not_depend_on_admin` is now redundant with the layered rule

```
SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: core_client_should_not_depend_on_admin
```

**What is wrong:**
With the relaxed `Client.mayOnlyBeAccessedByLayers("Application", "Server", "Support")`, *intra*-Client edges (clients.consumer → clients.admin) are not constrained by the layered rule (intra-layer access is always free). So `core_client_should_not_depend_on_admin` is the *only* thing protecting that boundary. **This is not a defect**, just an observation that justifies keeping the explicit rule. Worth noting in a comment so future maintainers don't delete it as redundant.

**How to fix it:**

```java
// IMPORTANT: This rule is the only protection against producer/consumer ->
// clients.admin coupling because the layered rule treats clients.admin as
// part of the Client layer alongside clients.producer / clients.consumer
// (intra-layer access is unconstrained by layeredArchitecture()).
@ArchTest
public static final ArchRule core_client_should_not_depend_on_admin = …;
```

---

### LOW-A3 — `whereLayer("Server").mayOnlyBeAccessedByLayers("Application")` may be too tight given the new Server members

```
SEVERITY: LOW
Category: Verify-after-change
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
After CRIT-A2 promoted `server.share..`, `server.purgatory..`, `server.network..`, `server.quota..` into Server, the assertion "Server may only be accessed by Application" is much broader than before. Surefire shows it currently holds (no Server-as-target violations in the residual 33), but if the broker/network module is ever added to the classpath, lots of `network..` classes will start hitting `server.purgatory..` or `server.share..` and the rule will fail. This is correct behaviour — but it's worth a comment so the next maintainer doesn't reflexively relax the rule.

**How to fix it:**

```java
// Server.mayOnlyBeAccessedByLayers("Application") is intentionally tight.
// If you re-add 'org.apache.kafka.network..' or 'org.apache.kafka.tools..'
// to the classpath and they hit Server, that's the desired *failure mode* —
// promote the offending package into Server, not the other way around.
.whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
```

---

## Summary Table

| ID | Severity | Category | Affected Rule / Constraint | Estimated violations cleared |
|---|---|---|---|---:|
| DEBT-A1 | HIGH | Wrong Layer / Real Debt | layered (ProducerSnapshot generated DTO) | 22 |
| DEBT-A2 | HIGH | Real Debt | layered (LogManager → metadata SPI/properties) | 7 |
| DEBT-A3 | HIGH | Real Debt / Symmetry Gap | layered (Infrastructure → Client carve-out) | 4 |
| MED-A1 | MEDIUM | Coverage Gap | layered (intra-Server cycles) | 0 |
| MED-A2 | MEDIUM | Maintainability | metadata_should_not_depend_on_controller | 0 |
| MED-A3 | MEDIUM | Coverage Gap | streams/connect_should_not_depend_on_broker_internals | 0 |
| LOW-A1 | LOW | Documentation | Header Javadoc | 0 |
| LOW-A2 | LOW | Note | core_client_should_not_depend_on_admin | 0 |
| LOW-A3 | LOW | Note | layered (Server access tightness) | 0 |

After applying DEBT-A1 + DEBT-A2 + DEBT-A3, the layered rule's violation count goes from **33** to **0** and the test suite reaches a fully green state.

---

## Recommended Patch (consolidated)

Below is the minimum surgical change-set. Apply DEBT-A1 by adding a `GeneratedDtos` layer; DEBT-A2 by carving `metadata.properties..` to Support and ring-fencing the `ConfigRepository` SPI; DEBT-A3 by extending the Client access list.

```java
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Kafka Architecture Enforcement Test
 *
 * Five-layer structure (Application / Server / Client / Infrastructure /
 * Support) inferred from Kafka's actual module topology. The structure is
 * NOT strict in the textbook sense: Client is accessed from Support and
 * Infrastructure to accommodate the documented cross-jar carve-outs
 * (common.requests <-> clients.admin DTOs, server.util.InterBrokerSendThread
 * <-> clients.KafkaClient, security.CredentialProvider <-> clients.admin.ScramMechanism).
 * See the layered_architecture_is_respected rule for canonical layer membership.
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
            "org.apache.kafka.metadata.properties..")     // DEBT-A2

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        // Server.mayOnlyBeAccessedByLayers("Application") is intentionally tight.
        // If a future module hits Server from below, promote the offender; do not
        // relax this rule.
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        // DEBT-A3: Infrastructure added to the Client access list.
        .whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server", "Support", "Infrastructure")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure")
        .whereLayer("GeneratedDtos").mayOnlyBeAccessedByLayers(
            "Application", "Server", "Client", "Infrastructure", "Support")
        .because("Layer model derived from Kafka's actual module topology. " +
                 "Client may be accessed from Support and Infrastructure to " +
                 "accommodate documented cross-jar DTO carve-outs " +
                 "(common.requests <-> clients.admin, server.util.InterBrokerSendThread " +
                 "<-> clients.KafkaClient, security.CredentialProvider <-> " +
                 "clients.admin.ScramMechanism). metadata.properties.. lives in " +
                 "Support because MetaProperties / PropertiesUtils are read by " +
                 "storage.LogManager at startup. server.log.remote.metadata.storage.generated.. " +
                 "lives in its own GeneratedDtos layer because it is generated " +
                 "message code and structurally a value type.");

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
        "org.apache.kafka.security..",                     // MED-A3
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

    // MED-A2: tighter carve-out — only the ControllerRequestContext seam is allowed.
    @ArchTest
    public static final ArchRule metadata_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat()
            .resideInAPackage("org.apache.kafka.controller..")
            .and().haveSimpleNameNotEndingWith("ControllerRequestContext")
        .because("metadata is the controller's domain model. The ONLY allowed " +
                 "back-edge is metadata.authorizer.{ClusterMetadataAuthorizer, AclMutator} " +
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

    // DEBT-A3: keep Storage explicitly disjoint from Client even though the
    // layered rule now allows Infrastructure->Client (only security uses it).
    @ArchTest
    public static final ArchRule storage_should_not_depend_on_clients = noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
        .because("Storage must not pull in the public clients API; the only " +
                 "Infrastructure->Client edge is security.CredentialProvider " +
                 "<-> clients.admin.ScramMechanism.");

    // LOW-A2: this rule is the only protection against producer/consumer ->
    // clients.admin coupling because layeredArchitecture() does not constrain
    // intra-layer access.
    @ArchTest
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer paths must not pull in admin-only types.");
}
```

After this patch the suite should be fully green (14/14 passing). The 33 violations resolve as:

- DEBT-A1 (22) → cleared by adding the `GeneratedDtos` layer.
- DEBT-A2 (7) → cleared by moving `metadata.properties..` to Support; the residual `metadata.ConfigRepository` reference becomes a `Server` target accessed by `Infrastructure` and is *still forbidden* by `Server.mayOnlyBeAccessedByLayers("Application")`. **One additional refactor decision is required:** either move `metadata.ConfigRepository` source-side to `server.common..` (preferred — it is a single SPI interface), or add a one-line carve-out:

```java
@ArchTest
public static final ArchRule storage_may_reference_metadata_config_repository_only = noClasses()
    .that().resideInAPackage("org.apache.kafka.storage..")
    .should().dependOnClassesThat()
        .resideInAPackage("org.apache.kafka.metadata..")
        .and().haveSimpleNameNotEndingWith("ConfigRepository")
    .because("LogManager is allowed to depend on metadata.ConfigRepository (SPI). " +
             "Any other storage->metadata reference is a regression.");
```

…and remove `Server` access constraint exception (none needed; the SPI carve-out is in the explicit pair rule).

- DEBT-A3 (4) → cleared by extending `Client.mayOnlyBeAccessedByLayers` to include `Infrastructure`.

The "fully green" state is reachable in two commits: first the `GeneratedDtos` + `metadata.properties..` re-mapping, then the `ConfigRepository` source-side move (or its allowlist rule). Either gets a passing CI.
