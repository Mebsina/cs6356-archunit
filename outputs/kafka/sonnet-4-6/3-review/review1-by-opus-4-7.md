# Adversarial Review #1 — Apache Kafka ArchUnit Rules

**Project**: kafka  
**Generator Model**: sonnet-4-6  
**Reviewer Model**: opus-4-7  
**Review**: #1  
**File reviewed**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`  
**Surefire evidence**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`  
**Architecture documentation**: `inputs/java/7_apache_kafka.pdf`  
**Package structure**: `inputs/java/7_apache_kafka.txt`

---

- **Violations**: 9 / 24 failing tests (2,213 total)
- **Issues**: 18

---

## Executive Summary

- **Total documented constraints identified**: **0** in the actual PDF (see CRIT-01 below). The test file's "constraints" are fabricated by the generator from package names alone.
- **Total rules generated**: 24 (1 layered architecture + 23 fine-grained `noClasses()` rules).
- **Coverage rate**: not applicable in the conventional sense, because the PDF contains no architectural constraints to cover. Against the *fabricated* layering the file declares for itself, the coverage is internally inconsistent (see VAC findings).
- **Critical Gaps**:
  - **CRIT-01** The PDF supplies zero layering or dependency-direction constraints. The entire 5-layer hierarchy is invented, so every "documented constraint" claim is unsubstantiated.
  - **CRIT-02** The `org.apache.kafka.server..` glob is treated as a single high layer, but the real Kafka codebase uses `org.apache.kafka.server.common.*`, `org.apache.kafka.server.util.*`, `org.apache.kafka.server.metrics.*`, `org.apache.kafka.server.fault.*`, `org.apache.kafka.server.authorizer.*`, `org.apache.kafka.server.immutable.*`, `org.apache.kafka.server.config.*`, `org.apache.kafka.server.common.serialization.*` as **shared low-level libraries** depended upon by `metadata`, `image`, `raft`, `storage`, and `common`. Treating all of `server..` as the highest non-API layer produces 1,467 false-positive violations on the layered rule alone.
  - **CRIT-03** The `org.apache.kafka.admin..`, `org.apache.kafka.api..`, `org.apache.kafka.coordinator..`, `org.apache.kafka.tiered..`, `org.apache.kafka.deferred..`, `org.apache.kafka.timeline..`, `org.apache.kafka.config..`, `org.apache.kafka.logger..`, `org.apache.kafka.queue..`, and `org.apache.kafka.message..` selectors are **vacuous** against the actual scanned classpath (zero classes match `that()`). Three rules (`admin_must_not_depend_on_streams`, `admin_must_not_depend_on_connect`, `coordinator_must_not_depend_on_controller`) failed at runtime with ArchUnit's `failOnEmptyShould` precisely because of this. Every additional rule built on these globs is silently dead.
  - **CRIT-04** The Apache Kafka admin client physically lives in `org.apache.kafka.clients.admin..`, **not** `org.apache.kafka.admin..`. The "API" layer mapping is therefore wrong: `admin` should not be a separate layer member, and rules involving `admin..` enforce nothing.
- **Overall verdict**: **FAIL**.
  - 9 of 24 tests fail.
  - 1,467 violations on the layered rule indicate the layer model does not describe the actual code at all.
  - 3 rules are demonstrably vacuous; another ~7 rules fire on globs that contain no production classes.
  - All `because()` clauses cite a "documentation" that doesn't exist.

---

## Findings

### COV-01

```
SEVERITY: CRITICAL
Category: Coverage Gap (root cause)
Affected Rule / Constraint: ALL — entire test class
```

**What is wrong:**
The supplied architecture document (`inputs/java/7_apache_kafka.pdf`) is the four-page Kafka Streams "Architecture" section of the official docs. Its content is exclusively: Stream Partitions and Tasks, Threading Model, Local State Stores, Fault Tolerance. It describes runtime concepts of one library (Kafka Streams) — **it does not describe a layered package architecture for `org.apache.kafka.*` at all**. The generator invented an entire 5-layer model from package names in the `.txt` file and then produced rules for it.

**Why it matters:**
Every `.because("...")` clause in the test class cites architectural rationale that is **not in the input documentation**. The reviewer cannot use the PDF to validate any constraint, and downstream maintainers will mistake fabricated invariants for documented intent. This is a coverage gap of *unknown* size — the real Kafka architecture (KIP-500, KIP-405, etc.) imposes constraints (e.g., the metadata `image` must be immutable; KRaft must not depend on ZooKeeper packages; clients must not import broker packages) that this file makes no attempt to enforce because the generator never read source-of-truth docs.

**How to fix it:**
Either (a) replace the input PDF with actual Kafka design documentation (KIPs, the `architecture.html` for the broker, the `design.html` for the protocol) and regenerate, or (b) treat this test class as a *speculative* boundary check and rewrite all `because()` clauses to say "inferred from package names" rather than "as documented". A code-only fix:

```java
.because("This constraint is inferred from package naming conventions, not from " +
         "the supplied architecture document. Validate against authoritative " +
         "Kafka design docs (KIPs) before relying on it in CI.");
```

---

### MAP-01 — `admin` layer member is a phantom package

```
SEVERITY: CRITICAL
Category: Vacuous Rule / Wrong Layer
Affected Rule / Constraint: API layer definition; rules
  - streams_must_not_depend_on_admin
  - clients_must_not_depend_on_admin
  - admin_must_not_depend_on_streams
  - admin_must_not_depend_on_connect
  - storage_must_not_depend_on_api_layer (admin glob inside)
```

**What is wrong:**
`org.apache.kafka.admin..` contains **zero production classes** in the scanned codebase (verified: `grep -E "^(Class|Method|Constructor|Field) <org\.apache\.kafka\.admin\." surefire.txt` → 0 hits). The Apache Kafka admin client lives at `org.apache.kafka.clients.admin..` (e.g., `AlterConfigOp`, `ConfigEntry`, `ScramMechanism`, `FeatureUpdate` — all of which appear as *targets* in the layered-architecture violation list under that path, not under `admin..`). The runtime confirms this: `admin_must_not_depend_on_streams` and `admin_must_not_depend_on_connect` failed with "Rule '…' failed to check any classes" because `that().resideInAPackage("org.apache.kafka.admin..")` matched nothing.

**Why it matters:**
- Three rules are silently dead — they currently *fail* (because of `failOnEmptyShould`), but if someone "fixes" them by adding `.allowEmptyShould(true)` the rules will pass without enforcing anything.
- The intended constraint — that the admin client should not pull in Streams or Connect — is **completely unenforced** because the constraint targets the wrong package.
- Worse: the existing rule `clients_must_not_depend_on_admin` is *inverted* relative to reality. In real Kafka, the admin DSL **lives inside** `clients.admin`, so "clients must not depend on admin" is structurally impossible to violate by definition (the admin sub-package is part of clients).

**How to fix it:**
Remove `admin` from the API layer members and either drop the admin-related rules or retarget them at the actual package:

```java
// In the layeredArchitecture() block, REMOVE "org.apache.kafka.admin.."
// from the API layer (it is empty) and treat clients.admin as part of the
// clients package — which it already is via "org.apache.kafka.clients..".

@ArchTest
public static final ArchRule clients_admin_must_not_depend_on_streams =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
        .because("The AdminClient must not pull in the Kafka Streams runtime; " +
                 "doing so would force every admin-tool user to ship Streams.");

@ArchTest
public static final ArchRule clients_admin_must_not_depend_on_connect =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("The AdminClient must not pull in the Kafka Connect runtime.");

// DELETE: admin_must_not_depend_on_streams
// DELETE: admin_must_not_depend_on_connect
// DELETE: streams_must_not_depend_on_admin (use clients.admin instead, or remove)
// DELETE: clients_must_not_depend_on_admin (structurally impossible — admin IS in clients)
```

---

### MAP-02 — `org.apache.kafka.server..` glob is too coarse; mis-layers shared utilities

```
SEVERITY: CRITICAL
Category: Wrong Layer / Overly Broad
Affected Rule / Constraint: kafka_layered_architecture, common_must_not_depend_on_server_layer,
                            metadata_must_not_depend_on_server, storage_must_not_depend_on_server_layer
```

**What is wrong:**
The Server layer is defined as `org.apache.kafka.server..` (plus `controller`, `coordinator`, `network`). But in the real Kafka modular layout, `org.apache.kafka.server.*` is a **shared utility / common-types module** containing:
- `org.apache.kafka.server.common.*` — `ApiMessageAndVersion`, `MetadataVersion`, `KRaftVersion`, `OffsetAndEpoch`, `Feature`, `FinalizedFeatures`, `TopicIdPartition`, `CheckpointFile`, `serialization.AbstractApiMessageSerde`, `serialization.RecordSerde`
- `org.apache.kafka.server.util.*` — `Scheduler`, `KafkaScheduler`, `ShutdownableThread`, `InterBrokerSendThread`, `timer.Timer`, `timer.TimerTask`
- `org.apache.kafka.server.metrics.*` — `KafkaMetricsGroup`, `TimeRatio`
- `org.apache.kafka.server.fault.*` — `FaultHandler`, `FaultHandlerException`
- `org.apache.kafka.server.authorizer.*` — `Authorizer`, `AuthorizationResult`, `AuthorizableRequestContext`, `AclDeleteResult`
- `org.apache.kafka.server.immutable.*` — `ImmutableMap`, `ImmutableNavigableSet`
- `org.apache.kafka.server.config.*` — `ConfigSynonym`, `DelegationTokenManagerConfigs`, `ServerLogConfigs`
- `org.apache.kafka.server.log.remote.storage.*` — `RemoteStorageManager`, `RemoteStorageMetrics`
- `org.apache.kafka.server.record.*`, `org.apache.kafka.server.storage.log.*`, `org.apache.kafka.server.share.*`, `org.apache.kafka.server.transaction.*`

These sub-packages are intentionally depended upon by `raft`, `metadata`, `image`, `snapshot`, `storage`, `controller`, and even `common.requests.RequestContext` (which implements `server.authorizer.AuthorizableRequestContext`). The surefire log proves this — 1,467 layered violations, 268 `metadata→server` violations, 361 `storage→server` violations, and 7 `common→server` violations are all this single mismodelling.

**Why it matters:**
The `kafka_layered_architecture` rule produces 1,467 violations on a single test run; the signal-to-noise ratio is so low that no real architectural breakage would ever be noticed amid the false positives. Every fine-grained rule that references `org.apache.kafka.server..` as a forbidden target inherits the same defect.

**How to fix it:**
Split the `server..` glob into "true server runtime" (high layer) and "server-shared" (low layer), and put the shared sub-packages alongside Core. Concretely:

```java
.layer("Core").definedBy(
    "org.apache.kafka.common..",
    "org.apache.kafka.message..",
    "org.apache.kafka.config..",
    "org.apache.kafka.logger..",
    "org.apache.kafka.deferred..",
    "org.apache.kafka.queue..",
    "org.apache.kafka.timeline..",
    "org.apache.kafka.security..",
    "org.apache.kafka.server.common..",
    "org.apache.kafka.server.util..",
    "org.apache.kafka.server.metrics..",
    "org.apache.kafka.server.fault..",
    "org.apache.kafka.server.authorizer..",
    "org.apache.kafka.server.immutable..",
    "org.apache.kafka.server.config..",
    "org.apache.kafka.server.record..",
    "org.apache.kafka.server.storage.log..",
    "org.apache.kafka.server.log.remote.storage.."
)
.layer("Server").definedBy(
    "org.apache.kafka.server",                 // top-level server package only (broker lifecycle)
    "org.apache.kafka.server.share..",         // share-group runtime
    "org.apache.kafka.server.transaction..",   // txn manager
    "org.apache.kafka.server.log.remote.metadata.storage..",
    "org.apache.kafka.controller..",
    "org.apache.kafka.coordinator..",
    "org.apache.kafka.network.."
)
```

…and rewrite `common_must_not_depend_on_server_layer`, `metadata_must_not_depend_on_server`, and `storage_must_not_depend_on_server_layer` to target only the *runtime* server sub-packages, not the shared utility sub-packages. For example:

```java
@ArchTest
public static final ArchRule metadata_must_not_depend_on_server_runtime =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.server",                // top-level only
            "org.apache.kafka.server.share..",
            "org.apache.kafka.server.transaction.."
        )
        .because("Metadata is a low-level layer; it may use server.common/util/fault/authorizer "
               + "shared utilities but must not depend on the broker request-handling runtime.");
```

---

### MAP-03 — `metadata`, `image`, `raft` mis-classified relative to `storage`

```
SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: kafka_layered_architecture; storage_must_not_depend_on_consensus
```

**What is wrong:**
The model says `Storage < Consensus`, but `storage.LogManager` depends on `metadata.ConfigRepository` and `metadata.properties.MetaProperties` (both flagged as violations of `storage_must_not_depend_on_consensus`). In reality, `org.apache.kafka.metadata.properties` (`MetaProperties`, `PropertiesUtils`) is a **bootstrap/identity library** used by storage to load `meta.properties` files; it is not part of the consensus subsystem. Similarly, `org.apache.kafka.metadata.ConfigRepository` is a SAM interface that decouples storage from broker metadata — exactly the kind of dependency-inversion target that is supposed to *cross* layers safely.

**Why it matters:**
- 7 violations on `storage_must_not_depend_on_consensus` are all legitimate downward use of stable interfaces or identity utilities.
- The `kafka_layered_architecture` rule will never go green until either the layer order changes or `metadata.properties` is re-classified.

**How to fix it:**
Either move these sub-packages out of the Consensus layer:

```java
.layer("Core").definedBy(
    /* … existing … */,
    "org.apache.kafka.metadata.properties.."
)

@ArchTest
public static final ArchRule storage_must_not_depend_on_consensus =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.raft..",
            "org.apache.kafka.image..",
            "org.apache.kafka.metadata.."
        )
        .ignoreDependency(
            resideInAPackage("org.apache.kafka.storage.."),
            resideInAnyPackage(
                "org.apache.kafka.metadata.properties..",   // identity / meta.properties
                "org.apache.kafka.metadata"                  // the ConfigRepository SAM
            ))
        .because("Storage may use metadata identity helpers (MetaProperties) and the "
               + "ConfigRepository contract for dependency inversion, but must not import "
               + "consensus log internals or the metadata image.");
```

Or, more honestly, drop the rule because the documented input does not actually require this isolation.

---

### COV-02 — `image` upward dependency on `server.*` not enforced

```
SEVERITY: HIGH
Category: Coverage Gap
Affected Rule / Constraint: (none — rule missing)
```

**What is wrong:**
There is no rule preventing `org.apache.kafka.image..` from depending on the Server layer, even though the file's own declared hierarchy puts Image (Consensus) below Server. The layered rule catches it, but no fine-grained rule exists, so once layers are reorganized to fix the false positives, image-on-server upward dependencies could slip through.

**Why it matters:**
Per the surefire log, classes in `org.apache.kafka.image.*` (`MetadataLoader`, `MetadataBatchLoader`, `MetadataLoaderMetrics`, `SnapshotEmitter`, `SnapshotGenerator`, `ImageWriterOptions`, `RaftSnapshotWriter`, `UnwritableMetadataException`, `MetadataVersionChange`, `FeaturesImage`, `ScramImage`, `TopicsImage`) reference `org.apache.kafka.server.fault.*`, `org.apache.kafka.server.common.*`, and `org.apache.kafka.server.immutable.*`. After the MAP-02 split these are legitimate Core deps; but the Image package has zero dedicated isolation rule today.

**How to fix it:**

```java
@ArchTest
public static final ArchRule image_must_not_depend_on_server_runtime =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.image..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.server",
            "org.apache.kafka.server.share..",
            "org.apache.kafka.server.transaction..",
            "org.apache.kafka.controller..",
            "org.apache.kafka.network.."
        )
        .because("The cluster image is a low-level metadata snapshot. It may use shared "
               + "server.common/util/fault types but must not import broker runtime classes.");
```

---

### COV-03 — `connect → streams`, `streams → connect` only one direction enforced for several pairs

```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: streams_must_not_depend_on_connect (yes), connect_must_not_depend_on_streams (yes),
                            BUT shell↔tools, connect↔shell, connect↔tools, streams↔shell, streams↔tools missing
```

**What is wrong:**
The intra-API isolation matrix is irregular:
- `streams ↔ connect`: both directions ✔
- `streams ↔ admin`: only `streams→admin` ✘ (and admin pkg is empty anyway — see MAP-01)
- `clients ↔ admin`: only `clients→admin` ✘ (and structurally invalid — see MAP-01)
- `shell ↔ streams`, `shell ↔ connect`: only `shell→…` ✘
- `tools ↔ streams`, `tools ↔ connect`: only `tools→…` ✘
- **Completely missing**: `streams → tools`, `streams → shell`, `connect → tools`, `connect → shell`, `tools → shell`, `shell → tools`

**Why it matters:**
A "Streams must not import command-line tools" violation would be silently tolerated. By the file's own logic, all six API-layer modules should form a complete isolation matrix (or at least an asymmetric one with documented direction).

**How to fix it:**
Add the missing reverse-direction rules, or replace the ten one-off rules with a single matrix-style assertion:

```java
@ArchTest
public static final ArchRule api_layer_siblings_must_not_cross_depend =
    slices().matching("org.apache.kafka.(streams|connect|tools|shell)..")
        .namingSlices("$1")
        .should().notDependOnEachOther()
        .because("The streams, connect, tools and shell API-layer modules are " +
                 "independently deployable and must not form sibling cycles.");
```
(Requires `import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;`.)

---

### FP-01 — `tools_must_not_depend_on_connect` is factually wrong

```
SEVERITY: HIGH
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: tools_must_not_depend_on_connect
```

**What is wrong:**
The rule fails with 92 violations, all coming from `org.apache.kafka.tools.ConnectPluginPath` and `org.apache.kafka.tools.ManifestWorkspace`. These are the **`connect-plugin-path` CLI**, a tool whose entire raison d'être is to inspect Kafka Connect plugin classpaths (`PluginType`, `PluginSource`, `PluginScanResult`, `PluginUtils`, `ReflectionScanner`, `ServiceLoaderScanner`, `ClassLoaderFactory`, `DelegatingClassLoader`). The dependency from `tools` to `connect.runtime.isolation` is **the entire purpose** of that tool.

**Why it matters:**
This rule will never be satisfiable as long as `connect-plugin-path` ships in `tools/`. Maintainers will either delete the rule or paper over it with `allowEmptyShould(true)`, leaving genuine accidental couplings undetected.

**How to fix it:**
Either drop the rule, or scope it to exclude the `ConnectPluginPath` family — but the cleanest option is to recognize the legitimate dependency:

```java
@ArchTest
public static final ArchRule tools_must_not_depend_on_connect_except_plugin_path =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.tools..")
        .and().areNotAssignableTo(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "ConnectPluginPath family",
                clazz -> !clazz.getName().startsWith("org.apache.kafka.tools.ConnectPluginPath")
                      && !clazz.getName().startsWith("org.apache.kafka.tools.ManifestWorkspace")))
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("The `connect-plugin-path` CLI is the documented exception: its sole purpose " +
                 "is to inspect Kafka Connect plugin classpaths. Other tools must remain " +
                 "decoupled from the Connect runtime.");
```

---

### FP-02 — `common → server.authorizer` is by design

```
SEVERITY: HIGH
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: common_must_not_depend_on_server_layer
```

**What is wrong:**
All 7 violations are `org.apache.kafka.common.requests.RequestContext` implementing `org.apache.kafka.server.authorizer.AuthorizableRequestContext` and `org.apache.kafka.common.requests.DeleteAclsResponse` consuming `org.apache.kafka.server.authorizer.AclDeleteResult`. `server.authorizer` is the **public, pluggable Authorizer SPI** — it has been deliberately decoupled from the broker runtime so that pluggable Authorizers can be loaded without dragging in broker classes. `common.requests` legitimately implements this SPI for serialization.

**Why it matters:**
Treating `server.authorizer` as off-limits to `common` blocks the standard authorization plug-in mechanism and obscures the fact that `server.authorizer` is actually a bottom-layer SPI.

**How to fix it:**
Either move `server.authorizer..` into Core (preferred — see MAP-02), or carve it out of the prohibition:

```java
@ArchTest
public static final ArchRule common_must_not_depend_on_server_runtime =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.common..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.server",
            "org.apache.kafka.server.share..",
            "org.apache.kafka.server.transaction..",
            "org.apache.kafka.controller..",
            "org.apache.kafka.coordinator..",
            "org.apache.kafka.network.."
        )
        .because("Common may use the public server.authorizer SPI and shared server.common/util " +
                 "utilities, but must not pull in the broker runtime, controller, coordinator, " +
                 "or NIO networking layers.");
```

---

### VAC-01 — Five rules whose `that()` selector matches no production class

```
SEVERITY: HIGH
Category: Vacuous Rule
Affected Rule / Constraint:
  - admin_must_not_depend_on_streams       (admin..    is empty)
  - admin_must_not_depend_on_connect       (admin..    is empty)
  - coordinator_must_not_depend_on_controller (coordinator.. is empty)
  - streams_must_not_depend_on_admin       (target empty)
  - clients_must_not_depend_on_admin       (target empty AND inverted, see MAP-01)
```

**What is wrong:**
Verified by `grep -E "^(Class|Method|Constructor|Field) <org\.apache\.kafka\.(admin|coordinator|api|tiered|deferred|timeline|config|logger|queue|message)\." surefire.txt` returning **zero hits** for each of those packages. Three of these rules already fail at runtime with "Rule '…' failed to check any classes" because of ArchUnit's `failOnEmptyShould` safety net; the other two pass only because their `that()` does match (e.g., `streams..`) but their `dependOnClassesThat()` target is empty.

**Why it matters:**
Each "failed to check any classes" failure will be tempting to silence with `.allowEmptyShould(true)` or by setting `archRule.failOnEmptyShould = false` globally. Either remediation hides genuine problems and leaves the test class lying about its coverage.

**How to fix it:**
Delete the vacuous rules (or fix their package selectors after MAP-01). At minimum, audit each `..` glob against actual class membership before claiming it as enforcement. Concretely:

```java
// DELETE: admin_must_not_depend_on_streams
// DELETE: admin_must_not_depend_on_connect
// DELETE: coordinator_must_not_depend_on_controller
// DELETE: streams_must_not_depend_on_admin
// DELETE: clients_must_not_depend_on_admin
// (Re-add as clients.admin variants if needed — see MAP-01.)
```

---

### VAC-02 — Layered-architecture members that contribute no classes

```
SEVERITY: MEDIUM
Category: Vacuous Rule (layer composition)
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong:**
Layer members `org.apache.kafka.api..`, `org.apache.kafka.admin..`, `org.apache.kafka.coordinator..`, `org.apache.kafka.tiered..`, `org.apache.kafka.deferred..`, `org.apache.kafka.timeline..`, `org.apache.kafka.config..`, `org.apache.kafka.logger..`, `org.apache.kafka.queue..`, `org.apache.kafka.message..` are **all empty in the scanned codebase** (verified the same way as VAC-01).

**Why it matters:**
The layered diagram in the test file's javadoc is misleading — half the layer members are phantoms. Anyone reading this file thinks Kafka has, say, an `api` package and a separate `admin` package; in reality the API surface is in `clients..`, `clients.admin..`, `streams..`, `connect..`, `tools..`, `shell..`. This makes the test class an unreliable map of the architecture.

**How to fix it:**
Remove empty members from the layer definitions, or replace the package list with the actual sub-packages. Example for the API layer:

```java
.layer("API").definedBy(
    "org.apache.kafka.clients..",   // includes clients.admin, clients.consumer, clients.producer
    "org.apache.kafka.connect..",
    "org.apache.kafka.streams..",
    "org.apache.kafka.tools..",
    "org.apache.kafka.shell.."
)
```

Equivalent prunings should be done for Core (drop `config`, `logger`, `deferred`, `queue`, `timeline`, `message`) and Storage (drop `tiered`) — or, if those packages legitimately exist in a wider build that this scan misses, broaden the importer scope so they appear.

---

### VAC-03 — Several `should().…` targets are also empty

```
SEVERITY: MEDIUM
Category: Vacuous Rule
Affected Rule / Constraint:
  - storage_must_not_depend_on_api_layer  (admin.. inside the OR list is empty)
  - common_must_not_depend_on_storage     (tiered.. is empty)
  - storage_must_not_depend_on_consensus  (image.. has very few classes; see also MAP-03)
```

**What is wrong:**
Empty members of `resideInAnyPackage(...)` produce silent dead branches: the rule still passes for the other targets, but the suspect package is unprotected.

**Why it matters:**
Maintainers might believe `storage → admin` is enforced. It isn't — there are no admin classes to depend on. If `org.apache.kafka.admin..` is later populated, the rule doesn't need to change but it's currently not enforcing anything for that package.

**How to fix it:**
Strip empty packages from `resideInAnyPackage(...)` lists, or replace them with the real package names (`org.apache.kafka.clients.admin..`, etc.). For example:

```java
@ArchTest
public static final ArchRule storage_must_not_depend_on_api_layer =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.clients..",          // includes clients.admin
            "org.apache.kafka.connect..",
            "org.apache.kafka.streams..",
            "org.apache.kafka.tools..",
            "org.apache.kafka.shell.."
        )
        .because("Storage is a low-level persistence layer and must not import the client " +
                 "or application layer.");
```

---

### NAR-01 — `network_must_not_depend_on_*` rules don't cover `server` itself

```
SEVERITY: MEDIUM
Category: Overly Narrow / Coverage Gap
Affected Rule / Constraint: network_must_not_depend_on_controller, network_must_not_depend_on_coordinator
```

**What is wrong:**
The fine-grained Server-layer rules forbid `network → controller` and `network → coordinator`, but not `network → server` (top-level broker). Per the file's own model, `network` is supposed to be a request-multiplexer that knows nothing about the broker runtime — yet it can freely depend on `org.apache.kafka.server.AssignmentsManager`, `org.apache.kafka.server.BrokerLifecycleManager`, etc. without violating any rule.

**Why it matters:**
A `network → server.X` coupling is exactly the failure mode the network-isolation rationale describes ("must remain agnostic of the coordinator's group management or transaction protocol logic"). Yet the rule list omits the most common case.

**How to fix it:**

```java
@ArchTest
public static final ArchRule network_must_not_depend_on_server_runtime =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.network..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.server",
            "org.apache.kafka.server.share..",
            "org.apache.kafka.server.transaction..",
            "org.apache.kafka.controller..",
            "org.apache.kafka.coordinator.."
        )
        .because("The NIO network layer must remain agnostic of broker request handling, " +
                 "share-group, transaction, controller, and coordinator runtime classes.");
```

---

### NAR-02 — Consensus-layer isolation only covers `raft → metadata` and `raft → image`

```
SEVERITY: MEDIUM
Category: Overly Narrow
Affected Rule / Constraint: raft_must_not_depend_on_metadata, raft_must_not_depend_on_image
```

**What is wrong:**
The file claims "raft must not import its consumers", but only `metadata` and `image` are blocked. By symmetry the model also wants:
- `raft → controller`, `raft → coordinator`, `raft → server` (high layer)
- `metadata → image` (image is a consumer of metadata records)
- `image → controller`, `image → coordinator`, `image → server`
- `metadata → controller`, `metadata → coordinator`

None of these are captured outside the layered rule, which is dominated by the false positives in MAP-02.

**Why it matters:**
After MAP-02 is fixed, the layered rule will tighten and most of these will be caught. But even today, `metadata → image` upward bypass is undetected.

**How to fix it:**

```java
@ArchTest
public static final ArchRule metadata_must_not_depend_on_image =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
        .because("Image is built on top of metadata; metadata must not import image types.");

@ArchTest
public static final ArchRule raft_must_not_depend_on_higher_layers =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.controller..",
            "org.apache.kafka.coordinator..",
            "org.apache.kafka.network..",
            "org.apache.kafka.server"      // top-level only, NOT server.common/util/...
        )
        .because("Raft is a general-purpose consensus log; it must not depend on broker " +
                 "request handling, controller logic, or coordinator state machines.");
```

---

### TRANS-01 — Transitive bypass via `clients`

```
SEVERITY: MEDIUM
Category: Transitivity Gap
Affected Rule / Constraint: tools_must_not_depend_on_streams, shell_must_not_depend_on_streams
```

**What is wrong:**
Direct dependencies from `tools`/`shell` to `streams` are blocked, but if `clients` ever imports `streams` (no rule prevents that), then `tools → clients → streams` works around it transparently. The file has `clients_must_not_depend_on_admin` but no `clients_must_not_depend_on_streams` or `clients_must_not_depend_on_connect`.

**Why it matters:**
The Streams artifact already depends on clients; a reverse `clients → streams` would make Streams a hard dependency of every client and reach `tools/shell` transitively without firing any rule.

**How to fix it:**

```java
@ArchTest
public static final ArchRule clients_must_not_depend_on_streams =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.streams..")
        .because("The clients package is the foundational producer/consumer; it must not " +
                 "import Streams (which depends on clients).");

@ArchTest
public static final ArchRule clients_must_not_depend_on_connect =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("The clients package must not import Connect (which depends on clients).");
```

---

### SCOPE-01 — `KAFKA_CLASSES` field is dead code

```
SEVERITY: LOW
Category: Structural Gap
Affected Rule / Constraint: KAFKA_CLASSES (lines 122–127)
```

**What is wrong:**
`KAFKA_CLASSES` is defined as a manually-imported `JavaClasses` instance with custom location filters (`!location.contains("/test/")`, `!location.contains("/jmh/")`, `!location.contains("/trogdor/")`), but **no rule actually consumes it**. Every `@ArchTest` rule is a static `ArchRule` and ArchUnit's JUnit runner injects classes from `@AnalyzeClasses`, which is configured with `packages = "org.apache.kafka"` and only the standard `DoNotIncludeTests` import option. The bespoke `/jmh/` and `/trogdor/` exclusions are **never applied**.

**Why it matters:**
- Reading the test file makes it appear that JMH benchmarks and Trogdor are excluded from rule evaluation. They are not. If the build classpath contains `org.apache.kafka.jmh.*` or `org.apache.kafka.trogdor.*` at test time, those classes will be evaluated and could trigger violations the file's javadoc claims are excluded.
- The dead field is a code-smell that suggests the author intended a different rule structure.

**How to fix it:**
Either delete the dead field or wire up the exclusions through `@AnalyzeClasses`:

```java
public class TrogdorAndJmhExclusion implements ImportOption {
    @Override
    public boolean includes(Location location) {
        return !location.contains("/jmh/") && !location.contains("/trogdor/");
    }
}

@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        TrogdorAndJmhExclusion.class
    }
)
public class ArchitectureEnforcementTest {
    // delete KAFKA_CLASSES — it is dead code
    // ...
}
```

---

### LAY-01 — `consideringOnlyDependenciesInLayers()` masks exits to non-layered packages

```
SEVERITY: LOW
Category: Semantic Error
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong:**
`consideringOnlyDependenciesInLayers()` instructs ArchUnit to ignore any dependency where the *target* class is not in any defined layer. Combined with the layer mappings, this silently drops every dependency on classes living in packages like `org.apache.kafka.test..`, `org.apache.kafka.jmh..`, `org.apache.kafka.trogdor..` (intended), but also any future top-level package added to the codebase that the file forgets to map. It also drops *self-loops* between, e.g., `clients` and an unmapped `clients.foo.bar` if a future refactor introduces one outside the existing globs.

**Why it matters:**
This is a fail-open default. As Kafka adds modules, the rule silently expands its blind spot. Combined with VAC-02's empty layer members, the layered rule provides much weaker guarantees than the verbose javadoc suggests.

**How to fix it:**
Enumerate every production top-level package explicitly and drop `consideringOnlyDependenciesInLayers()`, OR add a guard rule:

```java
@ArchTest
public static final ArchRule every_production_class_must_be_in_a_layer =
    classes()
        .that().resideInAPackage("org.apache.kafka..")
        .and().resideOutsideOfPackages(
            "org.apache.kafka.test..",
            "org.apache.kafka.jmh..",
            "org.apache.kafka.trogdor..")
        .should().resideInAnyPackage(
            // every glob from every layer, repeated — keep in sync!
            "org.apache.kafka.common..", "org.apache.kafka.security..",
            "org.apache.kafka.storage..", "org.apache.kafka.snapshot..",
            "org.apache.kafka.raft..", "org.apache.kafka.metadata..", "org.apache.kafka.image..",
            "org.apache.kafka.server..", "org.apache.kafka.controller..",
            "org.apache.kafka.coordinator..", "org.apache.kafka.network..",
            "org.apache.kafka.clients..", "org.apache.kafka.connect..",
            "org.apache.kafka.streams..", "org.apache.kafka.tools..", "org.apache.kafka.shell.."
        )
        .because("New top-level packages must be explicitly assigned to a layer; otherwise " +
                 "consideringOnlyDependenciesInLayers() will silently exempt them.");
```
(Requires `import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;`.)

---

### REA-01 — Every `because()` clause cites a non-existent document

```
SEVERITY: LOW
Category: Semantic Error / Misleading rationale
Affected Rule / Constraint: ALL 24 rules
```

**What is wrong:**
Every `.because("...")` clause states architectural rationale as if it were sourced from `inputs/java/7_apache_kafka.pdf`. That PDF contains only the Kafka *Streams* runtime overview; it documents none of these constraints. Some clauses are also factually wrong (e.g., the rationale for `tools_must_not_depend_on_connect` directly contradicts the existence of `connect-plugin-path`).

**Why it matters:**
If a rule fails in CI five years from now, an engineer will read the `because()` text, look in the cited Kafka Streams PDF, find nothing, and either delete the rule or override it. The rationale must be honest about its provenance.

**How to fix it:**
Rewrite all `because()` clauses to cite the source-of-truth (a KIP, a design.html section, or "inferred from package naming convention"). Example:

```java
.because("Inferred from package naming. The Kafka Streams Architecture PDF supplied as " +
         "input does not document this constraint; validate against KIP-405 / KIP-500 / " +
         "design.html before treating this as authoritative.");
```

---

### RED-01 — `connect_must_not_depend_on_streams` and `streams_must_not_depend_on_connect` redundant with the layered rule once intra-API rules are added

```
SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: All ten intra-API-layer rules
```

**What is wrong:**
The intra-API rules duplicate one another at fine granularity. Once the recommended `slices().notDependOnEachOther()` matrix from COV-03 is added, the explicit pairs `streams_must_not_depend_on_connect`, `connect_must_not_depend_on_streams`, `tools_must_not_depend_on_streams`, `tools_must_not_depend_on_connect`, `shell_must_not_depend_on_streams`, `shell_must_not_depend_on_connect` become redundant.

**Why it matters:**
Not a correctness bug — but ten near-identical rules with elaborate `because()` paragraphs make the test class painful to maintain and obscure which constraint is the canonical one.

**How to fix it:**
Replace the ten one-off rules with the single slice-based assertion shown in COV-03 and a small number of asymmetric exception rules where directionality matters (e.g., `streams → connect` is forbidden but a one-way `connect-plugin-path → connect` is allowed).

---

## Severity Roll-up

| Severity | Count | Findings |
|----------|------:|----------|
| CRITICAL | 4 | COV-01, MAP-01, MAP-02, (also CRIT-03 captured in VAC-01/VAC-02) |
| HIGH     | 5 | MAP-03, COV-02, FP-01, FP-02, VAC-01 |
| MEDIUM   | 5 | COV-03, VAC-02, VAC-03, NAR-01, NAR-02, TRANS-01 |
| LOW      | 4 | SCOPE-01, LAY-01, REA-01, RED-01 |

**Bottom line:** the test class enforces an architecture that does not exist in the supplied documentation, against package globs that don't match the actual codebase, producing both a flood of false-positive violations and a bench of silently dead rules. It must be regenerated after (1) supplying real Kafka design documentation and (2) auditing every package glob against `grep -E "^Class <…>" surefire.txt` to confirm the selectors actually match production classes.
