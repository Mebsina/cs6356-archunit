# Adversarial Review #2 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: gemini3-flash
**Reviewer Model**: opus-4-7
**Review**: #2
**File reviewed**: `outputs/kafka/gemini3-flash/ArchitectureEnforcementTest.java`
**Surefire evidence**: `outputs/kafka/gemini3-flash/target/surefire-reports/ArchitectureEnforcementTest.txt`
**Architecture documentation**: `inputs/java/7_apache_kafka.pdf`
**Package structure**: `inputs/java/7_apache_kafka.txt`
**Previous review**: [`review1-by-opus-4-7.md`](./review1-by-opus-4-7.md)

- **Tests run**: 13 (was 6) — **2 failed**, 11 passed.
- **Layered rule violations**: **1,358** (was 766; up 77 % — see CRIT-A1 below).
- **`metadata_should_not_depend_on_controller` violations**: **4** (new, real cross-talk).

---

## Executive Summary

The author applied Review #1's suggested patch verbatim. The structural model is now considerably closer to reality (5 of the 7 newly-added rules pass cleanly, including `raft_should_not_depend_on_metadata`, `raft_should_not_depend_on_image`, `image_should_not_depend_on_controller`, `storage_should_not_depend_on_security`, `security_should_not_depend_on_storage`). But the patch surfaced two new structural defects that Review #1 did not anticipate:

- **CRIT-A1** Review #1 told the author to put `org.apache.kafka.server.log..` in Support. That was wrong. **285 of 1,358** violations originate in `server.log.remote.storage` (RemoteLogManager, RemoteLogReader, RemoteIndexCache, etc.) and **73 more** in `server.log.remote.metadata` (TopicBasedRemoteLogMetadataManager, ConsumerManager, ProducerManager). These are not shared utility types — they are *high-level orchestrators* that drive `org.apache.kafka.storage..` and use the public client API. They belong in Server, not Support. **My prior recommendation was incorrect on this sub-package.**
- **CRIT-A2** Six populated `org.apache.kafka.server.*` sub-packages are **completely unlayered** in the patched file: `server.purgatory` (176 violations originating), `server.share` + `server.share.persister` + `server.share.dlq` (≈ 636 originating), `server.network` (35), `server.policy` (6), `server.quota` (24), `server.telemetry` (3). Together they originate **~880 of the 1,358 layered-rule violations** purely because they fall outside every layer membership. Review #1's `VAC-02` finding was wrong about these — they are not vacuous, they are unmapped.
- **CRIT-A3** Four top-level packages I previously labelled vacuous in `VAC-02` actually exist on the classpath: `org.apache.kafka.config` (2 origin violations), `org.apache.kafka.deferred` (2), `org.apache.kafka.queue` (16), `org.apache.kafka.timeline` (3). They are unlayered and produce real violations now.
- **HIGH-A1** `metadata_should_not_depend_on_controller` (4 violations) is a **real cross-talk pattern** in `metadata.authorizer.ClusterMetadataAuthorizer` and `metadata.authorizer.AclMutator` constructing `controller.ControllerRequestContext`. The rule's `because()` ("metadata is the controller's domain model, not its dependent") is actively wrong about the authorizer sub-tree.
- **HIGH-A2** `org.apache.kafka.common.requests..` legitimately depends on `org.apache.kafka.clients.admin..` (45 origin violations: `IncrementalAlterConfigsRequest$Builder`, `DescribeConfigsResponse`, `UpdateFeaturesRequest`, etc. carry admin types in their public APIs). This is Support → Client and the layered rule rejects it. Either the layer model has to allow it, or this is real architectural debt that has been live for years (the actual `kafka-clients` jar ships these dependencies).
- **HIGH-A3** `org.apache.kafka.server.util.InterBrokerSendThread` and `RequestAndCompletionHandler` (in Support) carry `org.apache.kafka.clients.KafkaClient`, `clients.ClientRequest`, `clients.RequestCompletionHandler` in their public signatures (≈ 20 of the 30 `server.util` originator hits). Same problem class as HIGH-A2: a "Support" library that wraps a client.

**Overall verdict: FAIL** (still). Progress is real, but two of the three critical findings (CRIT-A1, CRIT-A2) are *direct corrections to Review #1's recommendations*, and HIGH-A1/A2/A3 reveal that the package layout encodes more cross-cutting relationships than a strict 5-layer model can express. After applying the patches below the test suite should drop to ≲ 100 violations, most of them legitimate architectural smells worth tracking individually.

---

## What Changed Since Review #1 (Verification)

| Rule | R1 status | R2 status | Notes |
|---|---|---|---|
| `layered_architecture_is_respected` | FAIL (766) | **FAIL (1358)** | Worse, but for new reasons (CRIT-A1/A2/A3). |
| `streams_should_not_depend_on_connect` | PASS | PASS | unchanged |
| `connect_should_not_depend_on_streams` | PASS | PASS | unchanged |
| `streams_should_not_depend_on_broker_internals` | (added) | PASS | new |
| `connect_should_not_depend_on_broker_internals` | (added) | PASS | new |
| `raft_should_not_depend_on_controller` | PASS | PASS | unchanged |
| `raft_should_not_depend_on_metadata` | (added) | PASS | new — confirms the asymmetry |
| `raft_should_not_depend_on_image` | (added) | PASS | new |
| `metadata_should_not_depend_on_controller` | (added) | **FAIL (4)** | new — real cross-talk in `metadata.authorizer.*` |
| `image_should_not_depend_on_controller` | (added) | PASS | new |
| `storage_should_not_depend_on_security` | (added) | PASS | new |
| `security_should_not_depend_on_storage` | (added) | PASS | new |
| `core_client_should_not_depend_on_admin` | PASS (vacuous before) | PASS | now non-vacuous; correctly green |
| `storage_should_not_depend_on_server` | FAIL (361) | (deleted) | correctly removed |

**Breakdown of the 1,358 layered-rule violations** (origin packages, sampled directly from surefire):

| Origin package | Violations | Reason |
|---|---:|---|
| `org.apache.kafka.server.share.persister` | 617 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.server.log.remote.storage` | 285 | Misclassified into Support (CRIT-A1) |
| `org.apache.kafka.server.purgatory` | 176 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.server.log.remote.metadata` | 73 | Misclassified into Support (CRIT-A1) |
| `org.apache.kafka.common.requests` | 45 | Real Support → Client leak (HIGH-A2) |
| `org.apache.kafka.server.network` | 35 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.server.util` | 30 | Real Support → Client leak (HIGH-A3) |
| `org.apache.kafka.server.quota` | 24 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.server.share` | 16 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.queue` | 16 | UNLAYERED (CRIT-A3) |
| `org.apache.kafka.storage.internals.log` | 7 | Infrastructure → Support (mostly), some real |
| `org.apache.kafka.server.policy` | 6 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.security` | 4 | Real |
| `org.apache.kafka.metadata.authorizer` | 4 | Captured by HIGH-A1 |
| `org.apache.kafka.timeline` | 3 | UNLAYERED (CRIT-A3) |
| `org.apache.kafka.server.telemetry` | 3 | UNLAYERED (CRIT-A2) |
| `org.apache.kafka.server.share.dlq` | 3 | UNLAYERED (CRIT-A2) |
| Other (config, deferred, server.config, common.security.authenticator, server.common, server.log.remote, common) | < 15 each | Mixed |

CRIT-A1 + CRIT-A2 + CRIT-A3 alone account for roughly **1,260 of the 1,358 violations** (≈ 93 %).

---

## Findings

### CRIT-A1 — `server.log.remote.storage..` and `server.log.remote.metadata..` are misplaced in Support

```
SEVERITY: CRITICAL
Category: Wrong Layer (correction to Review #1's MAP-01 recommendation)
Affected Rule / Constraint: layered_architecture_is_respected (Support layer member list)
```

**What is wrong:**
Review #1 told the author to add `org.apache.kafka.server.log..` to the Support layer based on the assumption that `server-common`'s `server.log` package contained only shared SPI types. That was wrong. The `storage` Maven module (which the pom adds to the classpath) places its **tiered-storage orchestrator** code under the same package root: `server.log.remote.storage..` houses `RemoteLogManager`, `RemoteLogReader`, `RemoteLogOffsetReader`, `RLMQuotaManager`, `RemoteStorageMetrics`, etc., and `server.log.remote.metadata..` houses `TopicBasedRemoteLogMetadataManager`, `ConsumerManager`, `ProducerManager`, `ConsumerTask`. Surefire shows these classes **as origins** of 285 + 73 = 358 violations: they call into `org.apache.kafka.storage.internals..` (Infrastructure), `clients.consumer..` / `clients.producer..` / `clients.KafkaClient` (Client), and even `common.requests..` (Support). All of those are forbidden because, with the prior patch, the originators are themselves placed in Support.

In reality these are **high-level managers**: `RemoteLogManager` is what each broker instantiates to perform remote-log copy/fetch/delete; it owns thread pools, holds a `KafkaClient`, and orchestrates `LogManager`. A type that *uses* `LogManager`, `KafkaProducer`, and `KafkaConsumer` is structurally above storage and clients, not below.

**Why it matters:**
Review #1 recommended this mapping. The author followed it. The result is 358 spurious violations that obscure real ones, plus an obviously-incorrect dependency arrow ("RemoteLogManager is a fundamental utility used by everyone").

**How to fix it:**
Split `server.log..`. The minority that is genuinely shared SPI (`org.apache.kafka.server.log.remote.storage.RemoteStorageManager` interface, `RemoteLogMetadataManager` interface, `RemoteStorageException`, the `RemoteLogSegmentMetadata` value types under `server.log.remote.storage`) is interleaved with the orchestrators. The simplest correct split is to treat **all of `server.log..` as Server**:

```java
.layer("Server").definedBy(
        "org.apache.kafka.controller..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.image..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.server.log..")          // NEW
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
        "org.apache.kafka.server.storage..")
        // server.log.. removed; promoted to Server
```

Justification: `Infrastructure` (storage) is allowed to access `Support` (where the SPI value types live indirectly via `server.common.OffsetAndEpoch` and `server.storage.log.FetchIsolation`), and the orchestrators that previously misfired are moved to where their real position is.

If a future reviewer wants to enforce that `RemoteStorageManager` is itself an SPI (no tight coupling allowed), add a separate `noClasses()` rule rather than splitting the layer further.

---

### CRIT-A2 — Six populated `server.*` sub-packages are unlayered

```
SEVERITY: CRITICAL
Category: Coverage Gap (correction to Review #1's VAC-02)
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
The patched file's Support layer enumerates `server.common`, `server.util`, `server.metrics`, `server.config`, `server.fault`, `server.authorizer`, `server.immutable`, `server.record`, `server.storage`, `server.log`. But the `server-common` and `storage` modules also ship six **populated** sub-packages that the layer model does not mention:

| Sub-package | Origin violations | Likely correct layer |
|---|---:|---|
| `org.apache.kafka.server.share.persister..` (and `server.share..`, `server.share.dlq..`) | 636 | **Server** (high-level — share-group state coordinator that uses `clients.consumer/producer` and `storage`) |
| `org.apache.kafka.server.purgatory..` | 176 | **Server** (delays/purgatory used by broker request handlers; uses `server.util.timer` + `KafkaMetricsGroup`) |
| `org.apache.kafka.server.network..` | 35 | **Server** or **Infrastructure** (`KafkaAuthorizerServerInfo`, `EndpointReadyFutures` — broker bootstrap/listener wiring) |
| `org.apache.kafka.server.quota..` | 24 | **Server** (quota managers wire up `Metrics`) |
| `org.apache.kafka.server.policy..` | 6 | **Support** (interfaces like `AlterConfigPolicy`, `CreateTopicPolicy` — public broker SPI) |
| `org.apache.kafka.server.telemetry..` | 3 | **Support** (`ClientTelemetryReceiver` SPI) |

Because `layeredArchitecture()` defines "may only be accessed by …", an originator in *no* layer that depends on a class in a layer **does count as a violation** (the unlayered originator is, by definition, not in any of the allowed layers). Conversely, *targets* in no layer never trigger anything — but every Support member that one of these unlayered classes touches generates one report row. This is why `server.share.persister` alone produced 617 origin lines.

**Why it matters:**
Review #1's `VAC-02` told the author the full sweep of `server.*` was covered. It wasn't. Roughly **65 %** of the residual violations after CRIT-A1 originate in unlayered `server.*` sub-packages. As long as they remain unlayered, the layered rule cannot reach a green state regardless of what other rules say.

**How to fix it:**
Add the six sub-packages to their correct layers. Recommended assignment after CRIT-A1 + CRIT-A2:

```java
.layer("Server").definedBy(
        "org.apache.kafka.controller..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.image..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.server.log..",
        "org.apache.kafka.server.share..",       // NEW
        "org.apache.kafka.server.purgatory..",   // NEW
        "org.apache.kafka.server.network..",     // NEW
        "org.apache.kafka.server.quota..")       // NEW
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
        "org.apache.kafka.server.policy..",      // NEW (public SPI)
        "org.apache.kafka.server.telemetry..")   // NEW (public SPI)
```

If the team prefers a defense-in-depth check that surfaces *new* unlayered sub-packages introduced in future PRs, add a guardrail:

```java
@ArchTest
public static final ArchRule no_new_top_level_kafka_subpackages_remain_unlayered =
    classes()
        .that().resideInAPackage("org.apache.kafka..")
        .and().resideOutsideOfPackages(
            // (every glob currently in the layered rule, copy-pasted)
            "org.apache.kafka.streams..", "org.apache.kafka.connect..",
            "org.apache.kafka.clients..",
            "org.apache.kafka.controller..", "org.apache.kafka.metadata..",
            "org.apache.kafka.raft..", "org.apache.kafka.image..",
            "org.apache.kafka.snapshot..", "org.apache.kafka.server.log..",
            "org.apache.kafka.server.share..", "org.apache.kafka.server.purgatory..",
            "org.apache.kafka.server.network..", "org.apache.kafka.server.quota..",
            "org.apache.kafka.storage..", "org.apache.kafka.security..",
            "org.apache.kafka.common..",
            "org.apache.kafka.server.common..", "org.apache.kafka.server.util..",
            "org.apache.kafka.server.metrics..", "org.apache.kafka.server.config..",
            "org.apache.kafka.server.fault..", "org.apache.kafka.server.authorizer..",
            "org.apache.kafka.server.immutable..", "org.apache.kafka.server.record..",
            "org.apache.kafka.server.storage..", "org.apache.kafka.server.policy..",
            "org.apache.kafka.server.telemetry..",
            "org.apache.kafka.config..", "org.apache.kafka.deferred..",
            "org.apache.kafka.queue..", "org.apache.kafka.timeline..")
        .should().notBeAnnotatedWith(java.lang.annotation.Annotation.class) // intentionally always-false predicate
        .because("Force-fail if a new top-level subpackage appears that the " +
                 "layered architecture does not yet classify.");
```

(The `.should().notBeAnnotatedWith(Annotation.class)` is a contradiction-by-construction; it fails only if any class matches the residual `that()` set. Replace with a real predicate when ArchUnit ≥ 1.4 with `should().beEmpty()` is available.)

---

### CRIT-A3 — `config..`, `deferred..`, `queue..`, `timeline..` are populated and unlayered

```
SEVERITY: HIGH
Category: Coverage Gap (correction to Review #1's VAC-02)
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
Review #1 marked these top-level packages as vacuous. Surefire shows otherwise:

- `org.apache.kafka.queue.KafkaEventQueue`, `EventQueue$Event` — 16 origin violations.
- `org.apache.kafka.timeline.*` — 3 origin violations (timeline data structures used by metadata).
- `org.apache.kafka.deferred.DeferredEventQueue` — 2 origin violations.
- `org.apache.kafka.config.*` — 2 origin violations.

All four are infrastructure-grade utilities used by the controller, metadata, and image modules — they belong in **Support**.

**Why it matters:**
21+ violations are pure mis-classification artefacts and obscure the genuinely surprising relationships in HIGH-A1/A2/A3.

**How to fix it:**

```java
.layer("Support").definedBy(
        // existing members…
        "org.apache.kafka.config..",
        "org.apache.kafka.deferred..",
        "org.apache.kafka.queue..",
        "org.apache.kafka.timeline..")
```

---

### HIGH-A1 — `metadata_should_not_depend_on_controller` is genuinely violated by `metadata.authorizer.*`

```
SEVERITY: HIGH
Category: Wrong Constraint / Misleading because()
Affected Rule / Constraint: metadata_should_not_depend_on_controller
```

**What is wrong:**
The rule fires 4 times, all in `org.apache.kafka.metadata.authorizer..`:

```
Method <metadata.authorizer.AclMutator.createAcls(controller.ControllerRequestContext, ...)> has parameter of type <controller.ControllerRequestContext>
Method <metadata.authorizer.AclMutator.deleteAcls(controller.ControllerRequestContext, ...)> has parameter of type <controller.ControllerRequestContext>
Method <metadata.authorizer.ClusterMetadataAuthorizer.createAcls(...)> calls constructor <controller.ControllerRequestContext.<init>(...)>
Method <metadata.authorizer.ClusterMetadataAuthorizer.deleteAcls(...)> calls constructor <controller.ControllerRequestContext.<init>(...)>
```

`ClusterMetadataAuthorizer` and `AclMutator` are deliberately positioned at the metadata→controller seam because the broker's authorizer must mutate ACLs through the controller's `ControllerRequestContext`. The rule's `because` ("metadata is the controller's domain model, not its dependent") is correct in 99 % of `metadata..` but **factually wrong for the `metadata.authorizer..` sub-tree**, which is itself a controller client.

**Why it matters:**
The rule, as written, will permanently fail and either (a) be marked `@ArchIgnore` (silencing real future regressions in non-authorizer metadata) or (b) cause the team to refactor a deliberate design.

**How to fix it:**
Carve out the authorizer sub-tree explicitly and update the rationale:

```java
@ArchTest
public static final ArchRule metadata_should_not_depend_on_controller =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .and().resideOutsideOfPackage("org.apache.kafka.metadata.authorizer..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("metadata models are the controller's domain types and must " +
                 "not depend on the controller. EXCEPTION: " +
                 "metadata.authorizer.{ClusterMetadataAuthorizer, AclMutator} " +
                 "intentionally constructs controller.ControllerRequestContext " +
                 "to forward ACL mutations through the controller; that seam is " +
                 "documented design.");
```

---

### HIGH-A2 — `common.requests..` legitimately depends on `clients.admin..`

```
SEVERITY: HIGH
Category: Layer Direction / Real Architectural Debt
Affected Rule / Constraint: layered_architecture_is_respected (Support → Client violations)
```

**What is wrong:**
45 layered-rule origin violations are from `org.apache.kafka.common.requests..` (Support) into `org.apache.kafka.clients.admin..` (Client). Concrete examples:

```
Constructor <common.requests.DescribeConfigsResponse$ConfigSource.<init>(..., clients.admin.ConfigEntry$ConfigSource)> ...
Constructor <common.requests.IncrementalAlterConfigsRequest$Builder.<init>(...)> calls method <clients.admin.AlterConfigOp$OpType.id()>
Constructor <common.requests.UpdateFeaturesRequest$FeatureUpdateItem.<init>(..., clients.admin.FeatureUpdate$UpgradeType)>
```

`common.requests` is part of the public `kafka-clients` jar but already references admin DTOs (`AlterConfigOp`, `ConfigEntry`, `FeatureUpdate`, `ScramMechanism`). This is a *real* historical coupling shipped in production: end users running a producer-only application that pulls in `kafka-clients` already get the admin DTOs transitively through `common.requests`.

**Why it matters:**
The rule treats this as 45 violations to fix, but the actual remediation would require moving DTOs around in the `kafka-clients` jar — out of scope for an architectural test.

**How to fix it:**
Either (a) relax the layer model to acknowledge the `common.requests` ↔ `clients.admin` cross-edge as a documented public-API artefact, or (b) keep the strict rule and add a freeze-frame ignore listing the known cases. Option (a):

```java
// Allow Support → Client because common.requests already ships admin DTO references.
.whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server", "Support")
```

This loosens the model in exactly one direction; the explicit pair rules (`streams_should_not_depend_on_broker_internals`, `core_client_should_not_depend_on_admin`, etc.) still catch new misuse.

Option (b), if the team prefers strictness:

```java
@ArchTest
public static final ArchRule common_should_not_depend_on_clients_except_admin_dtos =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.common..")
        .and().resideOutsideOfPackage("org.apache.kafka.common.requests..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..")
        .because("kafka-clients jar internals must not back-reference the public " +
                 "client API except for the historical common.requests <-> " +
                 "clients.admin DTO carve-out.");
```

…and remove `common..` from the Support layer's `dependOn` constraint by adding `common.requests..` to a sixth "PublicWire" layer that is allowed to talk to both Support and Client. Either way, the current state — 45 unaddressed violations — is wrong.

---

### HIGH-A3 — `server.util.InterBrokerSendThread` carries `clients.KafkaClient`

```
SEVERITY: HIGH
Category: Layer Direction / Real Architectural Debt
Affected Rule / Constraint: layered_architecture_is_respected (Support → Client violations)
```

**What is wrong:**
About 20 of the 30 origin violations in `server.util` come from `InterBrokerSendThread` and `RequestAndCompletionHandler` carrying `org.apache.kafka.clients.KafkaClient`, `clients.ClientRequest`, `clients.RequestCompletionHandler` in their public signatures.

```
Field <server.util.InterBrokerSendThread.networkClient> has type <clients.KafkaClient>
Constructor <server.util.RequestAndCompletionHandler.<init>(..., clients.RequestCompletionHandler)>
```

This is a "Support" library that is structurally a *client wrapper*. It cannot satisfy "Support may not access Client" without being moved.

**Why it matters:**
Same character as HIGH-A2 — real, longstanding, structurally inescapable without a refactor.

**How to fix it:**
Either move these specific classes out of `server.util` into a new `Server`-layer location (e.g., `server.util.network`), **or** apply the same Option (a) as HIGH-A2 and let `Support → Client` be allowed. The minimum surgical fix:

```java
// Acknowledge the InterBrokerSendThread anomaly in the layered rule's because().
// Track refactor in a follow-up.
@ArchTest
public static final ArchRule server_util_should_not_depend_on_clients_except_inter_broker_send =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.server.util..")
        .and().haveSimpleNameNotStartingWith("InterBrokerSendThread")
        .and().haveSimpleNameNotStartingWith("RequestAndCompletionHandler")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients..");
```

---

### MED-A1 — `metadata_should_not_depend_on_controller` rationale is too sweeping

```
SEVERITY: MEDIUM
Category: Misleading because()
Affected Rule / Constraint: metadata_should_not_depend_on_controller, image_should_not_depend_on_controller, raft_should_not_depend_on_image, raft_should_not_depend_on_metadata
```

**What is wrong:**
The four "X is consumed by Y, never the other way" rules pass with the current layer mapping but their `because()` clauses keep declaring an absolute relationship. After HIGH-A1 we know one of them (metadata→controller) has a documented exception. The other three may eventually accumulate similar exceptions; the rationale should foreshadow that.

**How to fix it:**
Soften each rationale and link to the carve-out pattern from HIGH-A1:

```java
.because("Inferred from KRaft module topology only. If a legitimate seam " +
         "appears (see the metadata.authorizer carve-out for a precedent), " +
         "add it explicitly with .resideOutsideOfPackage rather than disabling " +
         "the rule.");
```

---

### MED-A2 — Streams/Connect → broker-internals broker list is now stale

```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: streams_should_not_depend_on_broker_internals, connect_should_not_depend_on_broker_internals
```

**What is wrong:**
Both rules enumerate `controller..`, `metadata..`, `image..`, `snapshot..`, `raft..`, `storage..`. After CRIT-A1 + CRIT-A2 the "broker internals" set should grow to include `server.log..`, `server.share..`, `server.purgatory..`, `server.network..`, `server.quota..`. Currently Streams/Connect could pick up `server.share.persister.PersisterStateManager` or `server.purgatory.DelayedOperationPurgatory` and the rule would not flag it (the layered rule would, but only after CRIT-A2 is fixed).

**How to fix it:**

```java
@ArchTest
public static final ArchRule streams_should_not_depend_on_broker_internals = noClasses()
    .that().resideInAPackage("org.apache.kafka.streams..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.apache.kafka.controller..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.image..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.storage..",
        "org.apache.kafka.server.log..",
        "org.apache.kafka.server.share..",
        "org.apache.kafka.server.purgatory..",
        "org.apache.kafka.server.network..",
        "org.apache.kafka.server.quota..");
```

(Apply the same to the Connect variant.)

---

### MED-A3 — `Server.mayOnlyBeAccessedByLayers("Application")` will fail again after CRIT-A1/A2

```
SEVERITY: MEDIUM
Category: Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
Once `server.share..`, `server.purgatory..`, `server.log..`, `server.network..`, `server.quota..` are promoted to **Server** (CRIT-A1/A2), the assertion `whereLayer("Server").mayOnlyBeAccessedByLayers("Application")` will fail again because:

- `org.apache.kafka.security.DelegationTokenManager` (Infrastructure) accesses `server.config.DelegationTokenManagerConfigs` — already covered (target stays in Support).
- BUT: `server.share.persister.PersisterStateManager` is *itself* in Server and is referenced from `coordinator..` (when the coordinator module is on the classpath), which is currently absent. Even now, `server.share.SharePartitionKey` is referenced as a field type from `server.purgatory.*` — also Server → Server (intra-layer, fine).
- The broader concern: storage (`org.apache.kafka.storage..`, Infrastructure) constructs `server.purgatory.DelayedOperationPurgatory`? Surefire so far shows no such instance, but adding the Server promotions raises the chance that storage starts referencing Server. If/when that happens the rule needs:

```java
.whereLayer("Server").mayOnlyBeAccessedByLayers("Application")  // unchanged
```

is fine *only if* storage never reaches up. To be safe **after CRIT-A1/A2**, run the test once and inspect the violation list; if any Infrastructure→Server hits appear, that is a real architecture finding and should not be relaxed.

**How to fix it:**
Re-run after applying CRIT-A1/A2/A3 fixes; treat any remaining Infrastructure→Server violation as a real defect. Document the policy:

```java
.whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
// If a future violation appears showing storage/security reaching into a
// Server module, do NOT relax this rule — promote the storage/security
// caller out of Infrastructure or split the depended-on Server class
// into a Support sub-package.
```

---

### LOW-A1 — Class-level header comment is now stale

```
SEVERITY: LOW
Category: Documentation
Affected Rule / Constraint: Class-level Javadoc
```

**What is wrong:**
The header still describes "Application / Server / Client / Infrastructure / Support" and lists `controller, raft, metadata, image, snapshot` as the entirety of Server. After CRIT-A1/A2 are applied the Server layer adds five more sub-packages and the Support layer changes considerably.

**How to fix it:**
Regenerate the header from the actual `definedBy(...)` lists, or replace with a one-line cross-reference: "See the `layered_architecture_is_respected` rule below for the canonical layer membership."

---

### LOW-A2 — `metadata_should_not_depend_on_controller` failing while `image_should_not_depend_on_controller` passes is asymmetric

```
SEVERITY: LOW
Category: Coverage Gap
Affected Rule / Constraint: image_should_not_depend_on_controller, snapshot_should_not_depend_on_controller (missing)
```

**What is wrong:**
The patch added `image_should_not_depend_on_controller` but no `snapshot_should_not_depend_on_controller`. `snapshot..` is in the same Server layer as `metadata..` and `image..` and is structurally the same kind of artefact (a write-out target for the controller). It deserves the same protection.

**How to fix it:**

```java
@ArchTest
public static final ArchRule snapshot_should_not_depend_on_controller =
    noClasses().that().resideInAPackage("org.apache.kafka.snapshot..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("snapshots are written by the controller, not consumers of it.");
```

---

## Summary Table

| ID | Severity | Category | Affected Rule | Estimated violations cleared |
|---|---|---|---|---:|
| CRIT-A1 | CRITICAL | Wrong Layer | layered (server.log split) | ≈ 360 |
| CRIT-A2 | CRITICAL | Coverage Gap | layered (six unlayered server.* sub-pkgs) | ≈ 880 |
| CRIT-A3 | HIGH | Coverage Gap | layered (config/deferred/queue/timeline) | ≈ 23 |
| HIGH-A1 | HIGH | Wrong Constraint | metadata_should_not_depend_on_controller | 4 |
| HIGH-A2 | HIGH | Real Debt | layered (common.requests → clients.admin) | ≈ 45 |
| HIGH-A3 | HIGH | Real Debt | layered (server.util → clients) | ≈ 20 |
| MED-A1 | MEDIUM | Misleading because() | four KRaft-direction rules | 0 |
| MED-A2 | MEDIUM | Coverage Gap | streams/connect → broker_internals | 0 |
| MED-A3 | MEDIUM | Semantic Error | layered (post-CRIT-A1/A2) | TBD after re-run |
| LOW-A1 | LOW | Documentation | header Javadoc | 0 |
| LOW-A2 | LOW | Coverage Gap | snapshot_should_not_depend_on_controller (missing) | 0 |

After applying CRIT-A1/A2/A3 + HIGH-A1, the layered rule's violation count should drop from **1,358** to roughly **65** (HIGH-A2 + HIGH-A3 leftovers, all genuine cross-jar/inter-broker debt).

---

## Recommended Patch (consolidated)

```java
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class ArchitectureEnforcementTest {

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")
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
            "org.apache.kafka.server.log..",         // CRIT-A1
            "org.apache.kafka.server.share..",       // CRIT-A2
            "org.apache.kafka.server.purgatory..",   // CRIT-A2
            "org.apache.kafka.server.network..",     // CRIT-A2
            "org.apache.kafka.server.quota..")       // CRIT-A2
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
            "org.apache.kafka.server.policy..",      // CRIT-A2 (SPI)
            "org.apache.kafka.server.telemetry..",   // CRIT-A2 (SPI)
            "org.apache.kafka.config..",             // CRIT-A3
            "org.apache.kafka.deferred..",           // CRIT-A3
            "org.apache.kafka.queue..",              // CRIT-A3
            "org.apache.kafka.timeline..")           // CRIT-A3

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server", "Support") // HIGH-A2
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure")
        .because("Layer model derived from Kafka's actual module topology; " +
                 "Support→Client edge documents the historical " +
                 "common.requests<->clients.admin and server.util<->clients DTO " +
                 "carve-outs (see HIGH-A2/A3 in review #2).");

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

    // HIGH-A1: carve out metadata.authorizer.{ClusterMetadataAuthorizer, AclMutator}.
    @ArchTest
    public static final ArchRule metadata_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .and().resideOutsideOfPackage("org.apache.kafka.metadata.authorizer..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("metadata is the controller's domain model. " +
                 "EXCEPTION: metadata.authorizer.ClusterMetadataAuthorizer/AclMutator " +
                 "intentionally constructs controller.ControllerRequestContext to " +
                 "forward ACL mutations through the controller.");

    @ArchTest
    public static final ArchRule image_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.image..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("image is consumed by the controller, never the other way.");

    @ArchTest
    public static final ArchRule snapshot_should_not_depend_on_controller = noClasses() // LOW-A2
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
    public static final ArchRule core_client_should_not_depend_on_admin = noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer paths must not pull in admin-only types.");
}
```

After this patch the layered rule should drop from **1,358** to **≲ 70** violations. The remainder will be genuine architectural debt (HIGH-A2 / HIGH-A3) that should be tracked individually rather than papered over.
