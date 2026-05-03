# Adversarial Architecture Review #3 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: sonnet-4-6
**Reviewer Model**: opus-4-7
**Round**: 3 (after fixes from Review #2)
**Source under review**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`
**Surefire report**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`

- **Violations**: 2 / 33 failing tests (209 total)
- **Issues**: 12

---

## Executive Summary

- Total documented constraints identified: 0 architectural constraints are present in `inputs/java/7_apache_kafka.pdf` (it documents Kafka **Streams** runtime semantics only). All 33 rules remain inferences from package naming, as honestly disclosed in every `because()` clause (REA-NEW-01 from Review #2 still holds — good).
- Total rules generated: 33 (was 32 in Round 2)
- Coverage rate: N/A — the PDF does not enumerate any cross-package constraint that could be cross-checked.
- **Critical Gaps**:
  - **REGR-04**: 12 distinct `org.apache.kafka.server.*` sub-packages (~100 production classes) are silently absent from every layer definition. They are exempt from the `kafka_layered_architecture` rule (because `consideringOnlyDependenciesInLayers()` ignores classes outside any layer) and they fail the `every_production_class_must_be_in_a_layer` guard. This is a NEW regression introduced by the LAY-NEW-01 "extract per-layer constants" refactor: the constants were copy-pasted from the previous hand-written list and inherited the same incompleteness.
  - **MOD-03 / MOD-04**: The Round-2 DTO-sharing carve-out (`* -> clients.admin`) only listed three predicates (`common`, `controller`, `image`). It missed `metadata -> clients.admin` (~25 violations) and `security -> clients.admin` (~4 violations), so the same architectural pattern fires on the layered rule.
  - **MOD-05 / MOD-06 / MOD-07 / MOD-08 / MOD-09**: Five additional cross-layer SPI/aggregation patterns that Round 2's MOD-01 ignoreDependency block did not enumerate (`server.config → storage / raft / network`, `server.metrics → image / metadata / controller`, `server.util → metadata`, `storage → server.log.remote.metadata.storage`, `common → clients (non-admin)`).
- Overall verdict: `FAIL`

**Trajectory across rounds**:
| Round | Failing tests | Total violations |
|-------|---------------|------------------|
| 1 | 9 / 24 | 2,213 |
| 2 | 4 / 32 | 1,071 |
| 3 | 2 / 33 | 209 |

The numbers are converging, but the remaining 209 violations are not noise — they reveal real architectural patterns (shared DTOs, SPI inversions, declarative config aggregation, metrics that observe higher-layer state) that the strict 5-layer model still cannot represent. Continuing to whittle them down with `ignoreDependency` clauses is sustainable; declaring victory is not.

---

## Findings

### REGR-04 — `server.*` sub-package coverage hole (regression of LAY-NEW-01)

```
[REGR-04] SEVERITY: CRITICAL
Category: Coverage Gap / Vacuous Layer Definition
Affected Rule / Constraint: every_production_class_must_be_in_a_layer (failing) AND kafka_layered_architecture (silently bypassed)
```

**What is wrong**:
The `every_production_class_must_be_in_a_layer` guard reports **100 violations**. They cluster under twelve `org.apache.kafka.server.*` sub-packages that are present in the scanned classpath but **absent from `CORE_PACKAGES` and `SERVER_PACKAGES`**:

| Sub-package | Example classes | Likely layer |
|-------------|-----------------|--------------|
| `server.controller..` | `ControllerRegistrationManager$*` | Server (broker-side controller wiring) |
| `server.log.remote..` | `TopicPartitionLog` | Core (shared abstraction) |
| `server.log.remote.quota..` | `RLMQuotaManager`, `RLMQuotaMetrics`, `RLMQuotaManagerConfig` | Server (broker runtime) |
| `server.logger..` | `Log4jCoreController`, `LoggingController`, `NoOpController` | Server (admin runtime) |
| `server.mutable..` | `BoundedList`, `BoundedListTooLongException` | Core (data structure) |
| `server.network..` | `BrokerEndPoint`, `EndpointReadyFutures`, `KafkaAuthorizerServerInfo`, `ConnectionDisconnectListener` | Core (shared types/SPIs) |
| `server.partition..` | `AlterPartitionManager`, `PartitionListener`, `Replica*State` | Server (broker runtime) |
| `server.policy..` | `AlterConfigPolicy`, `CreateTopicPolicy` | Core (policy SPIs) |
| `server.purgatory..` | `DelayedOperation`, `DelayedFuturePurgatory`, `TopicPartitionOperationKey` | Core (broker utility framework) |
| `server.quota..` | `ClientQuotaCallback` (SPI), `ClientQuotaManager` (runtime) | mixed — split required |
| `server.replica..` | `Replica`, `ReplicaState` | Server (broker runtime) |
| `server.telemetry..` | `ClientTelemetry`, `ClientTelemetryReceiver`, `ClientTelemetryExporterProvider` | Core (KIP-714 SPIs) |

**Why it matters**:
This is a **double failure**, not a single one:
1. The guard rule itself fails (visible in surefire), which is the symptom.
2. **More importantly**, every class in those packages is also exempt from `kafka_layered_architecture`. ArchUnit's `consideringOnlyDependenciesInLayers()` silently ignores any source/target class whose package is not assigned to a layer. So `ControllerRegistrationManager` could call any `streams` class and the layered rule would not notice. The guard exists to catch exactly this failure mode (LAY-01 from Review #1) — it is doing its job, but the refactor in LAY-NEW-01 did not actually fix the underlying enumeration; it merely centralised the incomplete list.

The Review-#2 LAY-NEW-01 fix introduced `CORE_PACKAGES`/`SERVER_PACKAGES` constants and routed both the layered rule and the guard through them. That refactor is structurally correct — but it copied the **same incomplete enumeration** from the old hand-written list, so the guard now catches what the layered rule already missed.

**How to fix it**:
Two options. **Recommended is Option B** because it makes the guard genuinely tight:

**Option A — enumerate every existing `server.*` sub-package** (faithful to current intent):

```java
// Add to CORE_PACKAGES (shared library / SPI definitions):
"org.apache.kafka.server.mutable..",
"org.apache.kafka.server.network..",          // BrokerEndPoint, EndpointReadyFutures
"org.apache.kafka.server.policy..",           // AlterConfigPolicy SPI, CreateTopicPolicy SPI
"org.apache.kafka.server.purgatory..",        // DelayedOperation framework
"org.apache.kafka.server.telemetry..",        // KIP-714 SPI
"org.apache.kafka.server.log.remote..",       // TopicPartitionLog (shared abstraction)

// Add to SERVER_PACKAGES (broker runtime):
"org.apache.kafka.server.controller..",       // ControllerRegistrationManager, broker-side wiring
"org.apache.kafka.server.logger..",           // Log4jCoreController runtime
"org.apache.kafka.server.partition..",        // AlterPartitionManager, ReplicaState mutators
"org.apache.kafka.server.replica..",          // Replica, ReplicaState
"org.apache.kafka.server.quota..",            // ClientQuotaManager runtime (callback SPI also lives here; accept the coupling)
"org.apache.kafka.server.log.remote.quota..", // RLMQuotaManager, RLMQuotaMetrics
```

**Option B — make `server..` the catch-all for SERVER and explicitly exclude Core sub-packages** (more robust to future additions):

```java
// SERVER becomes a glob, with the Core sub-packages already in CORE_PACKAGES.
// Layer membership is by listed-most-specific match, but layeredArchitecture()
// does NOT support nested-package precedence — it treats every match equally.
// To get this to work cleanly, define Server with .definedBy(...) using a glob
// AND add ignoreDependency clauses for the Core->Server "false positives" that
// would otherwise appear. Concretely:

private static final String[] SERVER_PACKAGES = {
    "org.apache.kafka.server..",        // catch-all
    "org.apache.kafka.controller..",
    "org.apache.kafka.network.."
};

// Then, for every Core sub-package under server.*, exclude it from Server with
// a DescribedPredicate, OR (cleaner) keep Core sub-packages explicit and accept
// the duplication as today, BUT add this guard so future server.X packages are
// caught at scan time:

@ArchTest
public static final ArchRule server_subpackages_must_be_classified =
    classes()
        .that().resideInAPackage("org.apache.kafka.server..")
        .should().resideInAnyPackage(ALL_LAYER_PACKAGES)
        .because("Every server.* sub-package must be explicitly assigned to either" +
                 " Core (shared utilities/SPIs) or Server (broker runtime). The" +
                 " consideringOnlyDependenciesInLayers() mode silently exempts any" +
                 " unclassified package, so an unrouted server.X is invisible to the" +
                 " layered rule and will not surface until this guard fires.");
```

Either option fixes the symptom. Option A is mechanical and matches the file's existing style; Option B is more defensive but requires deciding whether `layeredArchitecture()` will allow overlapping layer definitions (it does — a class can be in multiple layers — but that creates accidental allowed paths). Pick A unless you're prepared to audit the overlap semantics.

---

### MOD-03 — `metadata → clients.admin` shared-DTO pattern was missed

```
[MOD-03] SEVERITY: HIGH
Category: Overly Narrow / Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture (ignoreDependency block, MOD-02 scope)
```

**What is wrong**:
Round 2 added three `ignoreDependency` clauses for "shared DTO" usage of `clients.admin.*`:
```
common      -> clients.admin..
controller  -> clients.admin..
image       -> clients.admin..
```
But the surefire report shows **~25 identical-pattern violations** from `metadata.KafkaConfigSchema` and `metadata.ScramCredentialData` using `ConfigEntry`, `ConfigEntry$ConfigSource`, `ConfigEntry$ConfigType`, and `ScramMechanism`. Examples (line numbers from surefire):

- L28: `metadata.KafkaConfigSchema.TRANSLATE_CONFIG_SOURCE_MAP` — generic type `Map<ConfigEntry$ConfigSource, ...>`
- L40-66: `metadata.KafkaConfigSchema.{getStaticOrDefaultConfig,resolveEffectiveTopicConfig,toConfigEntry,translateConfigType,...}` — uses `ConfigEntry`, all `ConfigSource` enum constants, all `ConfigType` enum constants
- L67-68: `metadata.ScramCredentialData.toRecord(String, ScramMechanism)`

These are the **same pattern** as MOD-02: `clients.admin` enums and small value types are reused as wire-format primitives by the metadata layer. Round 2's enumeration of `{common, controller, image}` was incomplete.

**Why it matters**:
25 of the 109 layered violations come from this single missed predicate. They are not real architectural defects — the enums are shared by design — but they drown the signal in the surefire report and will tempt the next iteration to either delete legitimate metadata-side code or paste over the rule.

**How to fix it**:
Add to the `kafka_layered_architecture` ignoreDependency block:

```java
// MOD-03: metadata.KafkaConfigSchema and metadata.ScramCredentialData reuse
// clients.admin.{ConfigEntry,ScramMechanism} enums as wire-format primitives.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
```

---

### MOD-04 — `security → clients.admin` shared-DTO pattern was missed

```
[MOD-04] SEVERITY: HIGH
Category: Overly Narrow / Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture (ignoreDependency block, MOD-02 scope)
```

**What is wrong**:
`security.CredentialProvider` calls `ScramMechanism.mechanismName()` and accepts `ScramMechanism` parameters (surefire L73-76). `security..` is in CORE; `clients.admin..` is in API. Same shared-DTO pattern as MOD-02/MOD-03.

**Why it matters**:
4 layered violations come from this. Removing the dependency would mean removing SCRAM support from `CredentialProvider`, which is wrong; widening MOD-02 to cover `security` is the intended fix.

**How to fix it**:
Add one more `ignoreDependency` clause:

```java
// MOD-04: security.CredentialProvider uses clients.admin.ScramMechanism as a
// wire-format enum (same shared-DTO pattern as MOD-02).
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.security.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
```

A more durable alternative: collapse MOD-02/03/04 into a single rule that lets *any Core layer* read `clients.admin.*` enums:

```java
.ignoreDependency(
    DescribedPredicate.describe(
        "is in any Core or Consensus package (shared DTO consumer)",
        (JavaClass c) -> c.getPackageName().startsWith("org.apache.kafka.common")
                     || c.getPackageName().startsWith("org.apache.kafka.security")
                     || c.getPackageName().startsWith("org.apache.kafka.metadata")
                     || c.getPackageName().startsWith("org.apache.kafka.image")
                     || c.getPackageName().startsWith("org.apache.kafka.controller")),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
```

---

### MOD-05 — `server.config → storage` declarative-config aggregation

```
[MOD-05] SEVERITY: HIGH
Category: Coverage Gap (SPI / aggregation pattern)
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
`server.config.AbstractKafkaConfig` and `server.config.DynamicProducerStateManagerConfig` aggregate `ConfigDef` instances and reconfigurable property keys from the storage layer:

- L18, L82: `AbstractKafkaConfig.{loggableValue,...}` calls `storage.internals.log.LogConfig.{configType,configNames}`
- L19, L29: `DynamicProducerStateManagerConfig` field+ctor type `storage.internals.log.ProducerStateManagerConfig`
- L78-81: `AbstractKafkaConfig.logCleaner*()` reads `storage.internals.log.CleanerConfig` constants
- L83-89: `DynamicProducerStateManagerConfig.{validateReconfiguration, reconfigure}` calls `storage.internals.log.ProducerStateManagerConfig.{producerIdExpirationMs, setProducerIdExpirationMs, transactionVerificationEnabled, ...}`
- L122-123, 126: `AbstractKafkaConfig.<clinit>` and `DynamicBrokerConfig.<clinit>` read `LogConfig.SERVER_CONFIG_DEF`, `CleanerConfig.CONFIG_DEF`, `LogCleaner.RECONFIGURABLE_CONFIGS`

**Why it matters**:
~20 of the 109 layered violations. The pattern is a deliberate KIP design choice: dynamic broker config aggregates `ConfigDef` from every component that exposes one. The Round-2 MOD-01 carve-out for `server.config -> metadata` covered the metadata side of the same pattern but missed `server.config -> storage`.

**How to fix it**:
```java
// MOD-05: server.config aggregates ConfigDef instances from storage.internals.log
// (LogConfig, CleanerConfig, ProducerStateManagerConfig) for AbstractKafkaConfig
// and DynamicBrokerConfig. This is the documented declarative-config aggregation pattern.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
```

---

### MOD-06 — `server.metrics → image / metadata / controller`

```
[MOD-06] SEVERITY: HIGH
Category: Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
`server.metrics` is in CORE. Three classes report higher-layer state:
- `BrokerServerMetrics` exposes `MetadataProvenance` (image-layer type) — L20-23, L30, L91-94
- `NodeMetrics` calls `controller.QuorumFeatures.defaultSupportedFeatureMap()` — L24
- `NodeMetrics` reads `metadata.VersionRange` — L25-26, L31

This is the "metrics layer observes higher layers" pattern: metrics packages are typically classified as low-level utilities but they need to *observe* the runtime state they are reporting on.

**Why it matters**:
~7 layered violations. Either `server.metrics` should not be in Core (it could move to Server), or these specific dependencies need an exemption. Demoting `server.metrics` to Server would be wrong because it is published as a public utility (`KafkaMetricsGroup`, `TimeRatio`) consumed by every layer.

**How to fix it**:
Exempt the observability dependency direction:

```java
// MOD-06: server.metrics observes higher-layer runtime state for reporting.
// BrokerServerMetrics reports MetadataProvenance; NodeMetrics reports
// QuorumFeatures and metadata.VersionRange.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.metrics.."),
    DescribedPredicate.describe(
        "is image / metadata / controller (metrics observation targets)",
        (JavaClass c) -> c.getPackageName().startsWith("org.apache.kafka.image")
                     || c.getPackageName().startsWith("org.apache.kafka.metadata")
                     || c.getPackageName().startsWith("org.apache.kafka.controller")))
```

Note: this carve-out is broad enough that you may want to scope it further (e.g. only `BrokerServerMetrics`/`NodeMetrics`) once the file stabilises.

---

### MOD-07 — `server.util → metadata`

```
[MOD-07] SEVERITY: HIGH
Category: Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
`server.util.NetworkPartitionMetadataClient` accepts `metadata.MetadataCache` as a constructor parameter and calls `MetadataCache.getPartitionLeaderEndpoint(...)` (surefire L27, L32, L95). `server.util` is in CORE; `metadata` is in CONSENSUS.

**Why it matters**:
3 layered violations. This is a small SPI surface but it follows the same pattern as MOD-01's `server.config -> metadata` exception.

**How to fix it**:
```java
// MOD-07: server.util.NetworkPartitionMetadataClient depends on metadata.MetadataCache
// (SAM-style consumer of metadata state). Same SPI inversion as MOD-01 server.config.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.util.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."))
```

---

### MOD-08 — `storage → server.log.remote.metadata.storage` (generated message types)

```
[MOD-08] SEVERITY: HIGH
Category: Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
`storage.internals.log.ProducerStateManager.{readSnapshot, writeSnapshot}` constructs and calls ~20 methods on `server.log.remote.metadata.storage.generated.ProducerSnapshot` and its inner `ProducerEntry` (surefire L96-117). `storage..` is in STORAGE; `server.log.remote.metadata.storage..` is in SERVER.

**Why it matters**:
22 of the 109 layered violations. The generated message classes were placed under `server.log.remote.metadata.storage.generated` because that is where the schema lives, but they are used by the storage layer for on-disk format. This is an architectural mis-classification of the *generated* sub-package — it is genuinely a Core/Storage concern, not Server.

**How to fix it**:
Two valid choices. **Recommended is the second** (move generated to its own Core sub-package allow):

```java
// MOD-08 — Option 1: blanket exemption (less precise)
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."))

// MOD-08 — Option 2 (preferred): only the .generated sub-package, which contains
// schema-derived value classes that are layer-agnostic.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.generated.."))
```

Also add a documentation NOTE near `SERVER_PACKAGES` explaining that the `.generated` sub-package is exempt because it holds schema-derived DTOs.

---

### MOD-09 — `common → clients` (non-admin) was missed

```
[MOD-09] SEVERITY: HIGH
Category: Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
The Round-2 carve-out `common -> clients.admin..` covers admin enums, but several `common.*` classes depend on **other** `clients.*` sub-packages:

- L33-34: `common.MessageFormatter.writeTo(ConsumerRecord<byte[],byte[]>, PrintStream)` — `clients.consumer.ConsumerRecord`
- L35-36: `common.requests.ApiVersionsResponse.controllerApiVersions(NodeApiVersions, ...)` — `clients.NodeApiVersions`
- L37-38: `common.requests.ShareFetchRequest$Builder.build(short)` — `clients.consumer.internals.ShareAcquireMode`
- L39: `common.security.authenticator.SaslClientAuthenticator.receiveKafkaResponse()` — `clients.NetworkClient.parseResponse(...)`

**Why it matters**:
7 layered violations. `common` is the foundational utility layer; if it can read arbitrary `clients.*` types, the architectural intent that "clients depends on common, not the reverse" is broken. But these specific dependencies are real and intentional (`MessageFormatter` is a documented SPI for `ConsumerRecord` formatting; `ApiVersionsResponse.controllerApiVersions` is shared between client and broker; `SaslClientAuthenticator` reuses `NetworkClient` parsing logic; `ShareFetchRequest` shares enums with the consumer-internals share-group code).

**How to fix it**:
Either accept these as another shared-DTO pattern (recommended) or move the offending classes to `clients`. Pragmatic exemption:

```java
// MOD-09: common.MessageFormatter, common.requests.{ApiVersionsResponse,ShareFetchRequest},
// and common.security.authenticator.SaslClientAuthenticator share types with clients
// (ConsumerRecord, NodeApiVersions, ShareAcquireMode, NetworkClient.parseResponse).
// These are intentional cross-layer reuses; widening MOD-02 to cover non-admin clients.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.common.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
```

If you want this tighter, restrict by source class via a `DescribedPredicate` matching the four named classes only.

---

### FP-AUTH-01 — `metadata.authorizer → controller` exempted by one rule but not the other

```
[FP-AUTH-01] SEVERITY: MEDIUM
Category: Inconsistency / Overly Broad
Affected Rule / Constraint: kafka_layered_architecture vs metadata_must_not_depend_on_server_runtime
```

**What is wrong**:
Round 2 added `.and().resideOutsideOfPackage("org.apache.kafka.metadata.authorizer..")` to `metadata_must_not_depend_on_server_runtime` (FP-NEW-01 fix). That correctly suppresses the false positive on the *intra-Consensus* rule.

But `kafka_layered_architecture` was **not** updated with the equivalent exemption, so the same four call sites still fire there:

- L69-70: `metadata.authorizer.AclMutator.{createAcls,deleteAcls}` parameter `controller.ControllerRequestContext`
- L71-72: `metadata.authorizer.ClusterMetadataAuthorizer.{createAcls,deleteAcls}` constructs `controller.ControllerRequestContext(...)`

**Why it matters**:
4 of the 109 layered violations are this. More importantly, the inconsistency means a future reader will see one rule "approve" the dependency and another "reject" it, and may try to suppress the `metadata.authorizer.*` classes entirely or revert the FP-NEW-01 exception.

**How to fix it**:
Add the matching exemption to the layered rule:

```java
// FP-AUTH-01: metadata.authorizer.{AclMutator,ClusterMetadataAuthorizer} are
// controller-side ACL implementations that legitimately use controller types.
// Mirror of the FP-NEW-01 exception on metadata_must_not_depend_on_server_runtime.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.authorizer.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.controller.."))
```

---

### MOD-10 — `server.log.remote.storage → server.log.remote.metadata.storage`

```
[MOD-10] SEVERITY: MEDIUM
Category: Coverage Gap (Core → Server upward)
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
`server.log.remote.storage.RemoteLogManager.createRemoteLogMetadataManager()` constructs `server.log.remote.metadata.storage.ClassLoaderAwareRemoteLogMetadataManager` (surefire L90). The source package is in CORE; the target is in SERVER. This is one Core→Server violation.

**Why it matters**:
The Round-2 documentation explicitly mentions `server.log.remote.storage` as the *SPI* and `server.log.remote.metadata.storage` as the broker runtime. But the SPI side instantiates the runtime-side default implementation — that is the standard "factory in SPI module returns runtime instance" pattern. Either accept it (most pragmatic) or move `RemoteLogManager.createRemoteLogMetadataManager()` to a wiring class in the Server layer.

**How to fix it**:
```java
// MOD-10: RemoteLogManager (SPI side, in Core) instantiates the default
// ClassLoaderAwareRemoteLogMetadataManager (runtime side, in Server) as a
// factory method. Standard SPI-with-default-impl pattern.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."))
```

---

### LAY-NEW-03 — Coverage guard regressed from 44 → 100 violations

```
[LAY-NEW-03] SEVERITY: MEDIUM
Category: Test Diagnostic Quality
Affected Rule / Constraint: every_production_class_must_be_in_a_layer
```

**What is wrong**:
The guard worsened between rounds: Round 2 reported 44 orphaned classes, Round 3 reports 100. The increase is a **good thing** — it means the rule got tighter — but the surefire output is now extremely long because the same package name list (~30 packages) is printed for every one of the 100 violating classes (~5 KB per violation, ~500 KB total). This is a noise-vs-signal problem: a CI reader will scroll past hundreds of lines of identical text and miss the unique class name on each line.

**Why it matters**:
A future reviewer looking at a fresh failure will not see at a glance "12 sub-packages are unrouted"; they will see 100 near-identical paragraphs. They will probably copy the offending class names one by one to figure out the pattern (which I had to do here).

**How to fix it**:
Replace the naive `resideInAnyPackage(ALL_LAYER_PACKAGES)` with a `DescribedPredicate` that summarises the criterion in the failure message:

```java
@ArchTest
public static final ArchRule every_production_class_must_be_in_a_layer =
    classes()
        .that().resideInAPackage("org.apache.kafka..")
        .and().resideOutsideOfPackages(
            "org.apache.kafka.test..",
            "org.apache.kafka.jmh..",
            "org.apache.kafka.trogdor..")
        .should(new ArchCondition<JavaClass>(
            "reside in one of the " + ALL_LAYER_PACKAGES.length + " enumerated layer packages") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String pkg = item.getPackageName();
                boolean inLayer = Arrays.stream(ALL_LAYER_PACKAGES).anyMatch(p ->
                    p.endsWith("..")
                        ? pkg.equals(p.substring(0, p.length() - 2)) || pkg.startsWith(p.substring(0, p.length() - 1))
                        : pkg.equals(p));
                if (!inLayer) {
                    events.add(SimpleConditionEvent.violated(item,
                        "Class " + item.getName() + " is in package " + pkg
                            + " which is not assigned to any layer (Core/Storage/Consensus/Server/API)."
                            + " Add the package to the appropriate XXX_PACKAGES constant."));
                }
            }
        })
        .because("...");
```

This shrinks each violation line to one short sentence and tells the next reader exactly what to do.

---

### REA-NEW-02 — `because()` clauses on intra-layer rules still confuse "inferred" with "documented"

```
[REA-NEW-02] SEVERITY: LOW
Category: Maintainability / `because()` clause accuracy
Affected Rule / Constraint: streams_must_not_depend_on_connect, connect_must_not_depend_on_streams (and several siblings)
```

**What is wrong**:
Most `because()` clauses now correctly start with "Inferred from package naming." (good — REA-NEW-01 from Round 2 holds). But a few mix the inferred-status disclaimer with definite assertions about Kafka design:

- `streams_must_not_depend_on_connect`: "Such a dependency would force all Streams users to transitively pull in Connect." — true in fact, but stated as a definite consequence, not an inference.
- `clients_admin_must_not_depend_on_streams`: "doing so would force every admin-tool user to transitively depend on the stream processing library." — same pattern.

This is not wrong, but it slightly muddies the disclaimer. A consistent voice would help.

**How to fix it**:
Adopt one of two patterns and apply uniformly:

- **Pattern A** (current intent): "Inferred from package naming. Rationale: …"
- **Pattern B** (cleanest): "Inferred from package naming. If this rule is wrong, KIP-XXX is the source of truth."

Either is fine; pick one and apply to all 33 rules.

---

### POS-01 — Confirmed successful Round-2 fixes

```
[POS-01] SEVERITY: (informational)
```

The following Round-2 fixes are confirmed working in Round 3:

- **REGR-01** (config/deferred/logger/queue/timeline restored to Core): no orphaned classes from these packages in the surefire report. The 100 orphans are entirely under `server.*` sub-packages (REGR-04). ✔
- **REGR-02** (`metadata_must_not_depend_on_image` deletion + `image_must_not_depend_on_metadata_publisher_internals` replacement): rule passes; no inverted-direction false positives. ✔
- **REGR-03** (`tools_must_not_depend_on_connect_except_plugin_path` restored with predicate exclusion): rule passes; `ConnectPluginPath` exception works as intended. ✔
- **MOD-01 partial** (server.config→metadata, server.log.remote.storage→storage, server→clients, raft→clients, snapshot→raft): all five carve-outs are firing — none of those specific dependency pairs appear in the remaining 109 violations. ✔
- **MOD-02 partial** (common/controller/image → clients.admin): no violations reported for those three source layers; only `metadata` and `security` are still uncovered (MOD-03/MOD-04). ✔
- **FP-NEW-01** (metadata.authorizer exclusion on the intra-Consensus rule): rule passes. (But the layered rule still fires — FP-AUTH-01.) ✔ (partial)
- **LAY-NEW-01** (per-layer constants extraction): structurally correct refactor. The shared constants did their job; the failure is incompleteness of the lists, not the wiring (REGR-04). ✔ (refactor only)
- **LAY-NEW-02** (DescribedPredicate replacement of bare-package ignoreDependency): correct and well-documented. ✔
- **REA-NEW-01** (because() honesty): all 33 rules now disclose inference-vs-documentation status. ✔
- **SCOPE-NEW-01** (TrogdorAndJmhExclusion): no jmh/trogdor classes appear in any violation list. ✔
- **COV-NEW-01 / TRANS-NEW-01** (FUTURE-PROOFING ONLY: prefix on guard rules): clear and consistent. ✔

---

## Recommended Patch (consolidated)

Apply these in order — REGR-04 is the prerequisite (otherwise the unrouted `server.*` sub-packages are still invisible to the layered rule, even after the MOD-* fixes).

```java
// 1) REGR-04: enumerate every existing server.* sub-package.
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
    // REGR-04 additions:
    "org.apache.kafka.server.mutable..",
    "org.apache.kafka.server.network..",
    "org.apache.kafka.server.policy..",
    "org.apache.kafka.server.purgatory..",
    "org.apache.kafka.server.telemetry..",
    "org.apache.kafka.server.log.remote.."
};

private static final String[] SERVER_PACKAGES = {
    "org.apache.kafka.server",
    "org.apache.kafka.server.share..",
    "org.apache.kafka.server.transaction..",
    "org.apache.kafka.server.log.remote.metadata.storage..",
    "org.apache.kafka.controller..",
    "org.apache.kafka.network..",
    // REGR-04 additions:
    "org.apache.kafka.server.controller..",
    "org.apache.kafka.server.logger..",
    "org.apache.kafka.server.partition..",
    "org.apache.kafka.server.replica..",
    "org.apache.kafka.server.quota..",
    "org.apache.kafka.server.log.remote.quota.."
};

// 2) MOD-03 / MOD-04 / MOD-05 / MOD-06 / MOD-07 / MOD-08 / MOD-09 / MOD-10 / FP-AUTH-01:
// add to the ignoreDependency block of kafka_layered_architecture.
.ignoreDependency(   // MOD-03
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
.ignoreDependency(   // MOD-04
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.security.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
.ignoreDependency(   // MOD-05
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
.ignoreDependency(   // MOD-06 (broad — narrow once stable)
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.metrics.."),
    DescribedPredicate.describe(
        "is image / metadata / controller (metrics observation targets)",
        (JavaClass c) -> c.getPackageName().startsWith("org.apache.kafka.image")
                     || c.getPackageName().startsWith("org.apache.kafka.metadata")
                     || c.getPackageName().startsWith("org.apache.kafka.controller")))
.ignoreDependency(   // MOD-07
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.util.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."))
.ignoreDependency(   // MOD-08 (preferred narrow form: only .generated)
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.generated.."))
.ignoreDependency(   // MOD-09
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.common.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
.ignoreDependency(   // MOD-10
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."))
.ignoreDependency(   // FP-AUTH-01
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.authorizer.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.controller.."))
```

After applying the above, expected outcome:
- `every_production_class_must_be_in_a_layer`: 0 violations (REGR-04 closed).
- `kafka_layered_architecture`: residual close to zero. Any remaining violations should be a small set of newly-discovered cross-layer patterns (or the more aggressive variant of MOD-08/MOD-09 will be needed if the narrow forms above are not sufficient).
- All other 31 rules: continue passing.

---

## Severity Tally

| Severity | Count | IDs |
|----------|-------|-----|
| CRITICAL | 1 | REGR-04 |
| HIGH | 7 | MOD-03, MOD-04, MOD-05, MOD-06, MOD-07, MOD-08, MOD-09 |
| MEDIUM | 3 | FP-AUTH-01, MOD-10, LAY-NEW-03 |
| LOW | 1 | REA-NEW-02 |
| Informational | 1 | POS-01 (positive findings) |
| **Total** | **12** | |

---

## Closing Note

The model has converged on the right shape: per-layer package constants, honest `because()` clauses, JMH/Trogdor exclusion, and a coverage guard rule. What remains is the tedious enumeration work of (a) every `server.*` sub-package and (b) every cross-layer dependency pair that Kafka's KIP-driven design *intentionally* permits but that a strict 5-layer model rejects. After Round 4 fixes (REGR-04 + the eight new ignoreDependency clauses), the test should pass cleanly, and any further regressions will surface either as new orphaned packages (caught by the guard) or as new cross-layer patterns introduced by KIPs (caught by the layered rule). At that point, the suite becomes *useful as a regression detector* rather than a noise generator — which is the point of architecture testing.
