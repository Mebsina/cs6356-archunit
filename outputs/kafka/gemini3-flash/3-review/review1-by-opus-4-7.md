# Adversarial Review #1 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: gemini3-flash
**Reviewer Model**: opus-4-7
**Review**: #1
**File reviewed**: `outputs/kafka/gemini3-flash/ArchitectureEnforcementTest.java`
**Surefire evidence**: `outputs/kafka/gemini3-flash/target/surefire-reports/ArchitectureEnforcementTest.txt`
**Architecture documentation**: `inputs/java/7_apache_kafka.pdf`
**Package structure**: `inputs/java/7_apache_kafka.txt`

- **Tests run**: 6 — **2 failed**, 4 passed.
- **Layered rule violations**: **766**.
- **`storage_should_not_depend_on_server` violations**: **361**.

---

## Executive Summary

- **Total documented constraints identified**: **0** in the supplied PDF (it is the four-page Kafka *Streams* "Architecture" article — Stream Partitions/Tasks, Threading Model, Local State Stores, Fault Tolerance — and contains **no** layered package architecture for `org.apache.kafka.*`). The five-layer hierarchy and every `because()` rationale in the file are invented by the generator from package names alone.
- **Total rules generated**: **6** — one `layeredArchitecture()` rule + five `noClasses()` rules.
- **Coverage rate**: not meaningful against the supplied PDF (the PDF mandates nothing). Against the *self-declared* layer model, only one cross-cutting pair (Streams ↔ Connect) and three single-direction edges are explicitly asserted; **dozens of intra-layer pairs are unconstrained**.
- **Critical Gaps**:
  - **CRIT-01** The `org.apache.kafka.server..` glob is treated as a top-of-stack "broker logic" layer, but the actual sub-packages on the classpath (`server.common`, `server.util`, `server.metrics`, `server.config`, `server.fault`, `server.authorizer`, `server.immutable`, `server.record`, `server.storage.log`, `server.log.remote.storage`) are **shared low-level libraries** that `metadata`, `raft`, `image`, `snapshot`, `storage`, `security`, and `common` all depend on. Treating `server..` as the highest non-Application layer caused the layered rule to fail with **766 violations**.
  - **CRIT-02** `org.apache.kafka.image..` and `org.apache.kafka.snapshot..` are placed in **Support**, but they reference `metadata..` and `raft..` (declared as **Server**) on every page of the surefire report. Either the layer mapping is wrong or these are KRaft metadata-layer artifacts that do not belong in Support at all.
  - **CRIT-03** `storage_should_not_depend_on_server` (361 violations) is empirically false: storage is contractually wired to `server.common.CheckpointFile`, `server.util.Scheduler`, `server.metrics.KafkaMetricsGroup`, `server.config.ServerLogConfigs`, `server.record.BrokerCompressionType`, `server.log.remote.storage.RemoteStorageManager`, `server.storage.log.FetchIsolation`. The rule encodes a relationship that does not, and was never intended to, hold.
  - **CRIT-04** `org.apache.kafka.admin..` is a phantom: the admin client lives at `org.apache.kafka.clients.admin..`. The "Client" layer membership and `core_client_should_not_depend_on_admin` rule never match anything in the real codebase.
  - **CRIT-05** Eight layer-member globs (`api..`, `admin..`, `config..`, `message..`, `queue..`, `deferred..`, `logger..`, `timeline..`, plus `coordinator..`, `network..`, `tiered..` from missing modules) contribute **zero** classes to the layered rule. The layer definitions look comprehensive but most of them are decorative.
  - **CRIT-06** No same-layer isolation rules exist for the dozens of pairs where the documentation (or even just package naming) implies decoupling: `controller↔raft↔coordinator↔metadata` inside Server; `network↔storage↔security↔tiered` inside Infrastructure; the entire Support layer (`common`, `image`, `snapshot`, `message`, `queue`, …) has no intra-layer constraint at all.
- **Overall verdict**: **FAIL**.
  - 2/6 tests fail and one of the passing tests (`core_client_should_not_depend_on_admin`) is functionally vacuous.
  - 766 + 361 = 1,127 violations from a 119-line test file is a sign the layer model does not describe the actual code, not a sign that the code is broken.
  - The surviving 4 rules cover ≤ 4 of the dozens of intra-layer pairs implied by the package list.

---

## Findings

### COV-01 — Documentation supplies no architectural constraints

```
SEVERITY: CRITICAL
Category: Coverage Gap (root cause)
Affected Rule / Constraint: ALL rules; every .because() clause
```

**What is wrong:**
The supplied PDF (`inputs/java/7_apache_kafka.pdf`) is the Kafka *Streams* Architecture page. Its sections are: Stream Partitions and Tasks, Threading Model, Local State Stores, Fault Tolerance. It contains **no** layered package model, **no** dependency direction rules, and **no** statements like "Storage must not depend on Server" or "Streams and Connect must remain decoupled". Every architectural claim in the test class — the five-layer hierarchy, the parallel-application warning, the "Raft is a primitive" rationale, the "minimal client footprint" claim — is **fabricated from package names**.

**Why it matters:**
Reviewers cannot validate any rule against the documentation, and downstream maintainers will mistake invented invariants for documented intent. Worse, the violations are silently shaped to whatever package layout the generator guessed at, not the architecture Kafka actually follows.

**How to fix it:**
Replace the PDF with authoritative Kafka design sources (KIP-500 for KRaft metadata, KIP-405 for tiered storage, the official `architecture.html` for the broker, `design.html` for the protocol) and regenerate. Until then, every `.because()` clause should be neutralized:

```java
.because("Inferred from package naming conventions only — the supplied PDF " +
         "does not specify any layered architecture. Validate against KIPs " +
         "and the official Kafka architecture documentation before relying " +
         "on this rule in CI.");
```

---

### MAP-01 — `server..` is misclassified as a high layer

```
SEVERITY: CRITICAL
Category: Wrong Layer / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected; storage_should_not_depend_on_server
```

**What is wrong:**
The rule places `org.apache.kafka.server..` in the **Server** layer (just below Application/Client) and asserts `mayOnlyBeAccessedByLayers("Application")`. But the sub-packages actually present on the classpath are the *server-common* shared utility module: `server.common` (CheckpointFile, OffsetAndEpoch, MetadataVersion, ApiMessageAndVersion, KRaftVersion, FaultHandlerException, serialization.RecordSerde), `server.util` (Scheduler, ShutdownableThread, KafkaScheduler), `server.metrics` (KafkaMetricsGroup, TimeRatio), `server.config` (ServerLogConfigs, QuotaConfig, ServerTopicConfigSynonyms, DelegationTokenManagerConfigs), `server.fault` (FaultHandler, FaultHandlerException), `server.authorizer` (AuthorizableRequestContext), `server.immutable` (ImmutableMap), `server.record` (BrokerCompressionType), `server.storage.log` (FetchIsolation), `server.log.remote.storage` (RemoteStorageManager, RemoteStorageMetrics).

These are **shared low-level types** that Kafka's `metadata`, `raft`, `image`, `snapshot`, `storage`, `security`, and even `common.requests` all import as a contract. Surefire confirms it: a sample of the 766 layered violations cites `common.requests.RequestContext implements server.authorizer.AuthorizableRequestContext`, `image.loader.MetadataLoader implements raft.RaftClient$Listener` and references `server.fault.FaultHandler`, `storage.internals.checkpoint.LeaderEpochCheckpointFile$Formatter implements server.common.CheckpointFile$EntryFormatter`, `storage.internals.log.LogCleaner$CleanerThread extends server.util.ShutdownableThread`.

**Why it matters:**
With `server..` placed above Infrastructure and Support, every legitimate use of `server.common.*`, `server.util.*`, `server.metrics.*`, etc. by lower modules is reported as a violation. The result: 766 false positives on the layered rule and 361 false positives on `storage_should_not_depend_on_server`. The rule cannot tell a real architecture violation from a routine dependency on shared utilities.

**How to fix it:**
Either split `server..` into the high-level broker layer (`server.broker..`, `server.network..` if/when those exist on the actual classpath) and the shared-library sub-tree (`server.common..`, `server.util..`, `server.metrics..`, `server.config..`, `server.fault..`, `server.authorizer..`, `server.immutable..`, `server.record..`, `server.storage..`, `server.log.remote..`) and put the shared sub-tree in **Support**, or remove `server..` from the layer model entirely and only assert specific cross-package rules. The minimum fix:

```java
.layer("Server").definedBy(
        // Only the genuinely high-level broker packages.
        // server.common/util/metrics/config/fault/authorizer/immutable/record/storage/log
        // are shared libraries and belong in Support.
        "org.apache.kafka.controller..",
        "org.apache.kafka.coordinator..")
.layer("Support").definedBy(
        "org.apache.kafka.common..", "org.apache.kafka.message..",
        "org.apache.kafka.queue..", "org.apache.kafka.deferred..",
        "org.apache.kafka.logger..", "org.apache.kafka.timeline..",
        "org.apache.kafka.server.common..", "org.apache.kafka.server.util..",
        "org.apache.kafka.server.metrics..", "org.apache.kafka.server.config..",
        "org.apache.kafka.server.fault..", "org.apache.kafka.server.authorizer..",
        "org.apache.kafka.server.immutable..", "org.apache.kafka.server.record..",
        "org.apache.kafka.server.storage..", "org.apache.kafka.server.log..")
```

And delete `storage_should_not_depend_on_server` outright.

---

### MAP-02 — `image..` and `snapshot..` are misplaced into Support

```
SEVERITY: CRITICAL
Category: Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected (Support layer member list)
```

**What is wrong:**
The Support layer is described as "fundamental utilities, configuration, and shared data structures" and includes `org.apache.kafka.image..` and `org.apache.kafka.snapshot..`. But:

- `image.AclsImage`, `image.ClusterImage`, `image.DelegationTokenImage`, `image.FeaturesImage`, `image.ScramImage`, `image.TopicImage`, `image.MetadataDelta`, `image.loader.MetadataLoader`, `image.publisher.SnapshotEmitter`, `image.writer.RaftSnapshotWriter` all reference **`org.apache.kafka.metadata..`** (StandardAcl, BrokerRegistration, ControllerRegistration, DelegationTokenData, PartitionRegistration, SupportedConfigChecker, ScramCredentialData, KafkaConfigSchema) and **`org.apache.kafka.raft..`** (RaftClient, LeaderAndEpoch, internals.RecordsIterator, internals.BatchAccumulator).
- `snapshot.RecordsSnapshotReader`, `snapshot.SnapshotReader`, `snapshot.RecordsSnapshotWriter`, `snapshot.NotifyingRawSnapshotWriter` reference `raft.Batch`, `raft.internals.RecordsIterator`, `raft.internals.BatchAccumulator`, and `server.common.OffsetAndEpoch`.

Both packages are **part of the KRaft metadata stack** (`metadata` Maven module), not "fundamental utilities". Putting them in Support inverts the dependency direction (Support cannot access Server), which is exactly what most of the 766 layered violations are reporting.

**Why it matters:**
This single mis-classification is responsible for hundreds of the 766 layered-rule violations in surefire. Even after fixing MAP-01, `image..` and `snapshot..` will continue to fail the layered check until they are moved out of Support.

**How to fix it:**
Move `image..` and `snapshot..` into the Server (KRaft metadata) layer:

```java
.layer("Server").definedBy(
        "org.apache.kafka.controller..",
        "org.apache.kafka.coordinator..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.image..",
        "org.apache.kafka.snapshot..")
```

and remove them from the Support `definedBy(...)` argument list.

---

### VAC-01 — `core_client_should_not_depend_on_admin` is functionally vacuous

```
SEVERITY: HIGH
Category: Vacuous Rule / Wrong Package
Affected Rule / Constraint: core_client_should_not_depend_on_admin
```

**What is wrong:**
The rule fires on `dependOnClassesThat().resideInAPackage("org.apache.kafka.admin..")`. In the actual Kafka classpath, the admin client lives at **`org.apache.kafka.clients.admin..`** (e.g., `AlterConfigOp`, `ConfigEntry`, `FeatureUpdate`, `ScramMechanism`, all of which appear as targets in surefire). No production class in the real codebase resides under the bare `org.apache.kafka.admin` package, so the target glob never matches and the rule passes regardless of what `clients..` actually does. The test passes silently because `failOnEmptyShould` only triggers when `that()` is empty, not when the `should` target is empty.

**Why it matters:**
The intended invariant ("the core client library should not depend on the administrative client to maintain a minimal footprint") is a reasonable design guideline, but as written the rule enforces nothing. The codebase could route `clients.consumer.*` calls through `clients.admin.*` and CI would stay green.

**How to fix it:**

```java
@ArchTest
public static final ArchRule core_client_should_not_depend_on_admin =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.clients..")
        .and().resideOutsideOfPackage("org.apache.kafka.clients.admin..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .because("Producer/consumer/network paths under org.apache.kafka.clients " +
                 "must not reach into the admin client so consumers shipping " +
                 "kafka-clients do not pull in admin-only types.");
```

The same correction must be applied wherever `org.apache.kafka.admin..` appears in the layered definition: replace the bare `admin..` with `clients.admin..`, or simply drop it (it is already covered by the `clients..` glob on the Client layer).

---

### VAC-02 — Eight layer-member globs match zero classes

```
SEVERITY: HIGH
Category: Vacuous Rule / Coverage Inflation
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
After cross-referencing the surefire violation list (which cites every class on the layered-rule scope) with the layer membership lists, the following globs contribute **no** production classes:

| Layer | Glob | Reason |
|---|---|---|
| Client | `org.apache.kafka.api..` | No such root package on the configured classpath |
| Client | `org.apache.kafka.admin..` | Phantom — admin lives at `clients.admin` (see VAC-01) |
| Server | `org.apache.kafka.coordinator..` | The `group-coordinator`/`transaction-coordinator` modules are not on the classpath in `pom.xml` |
| Infrastructure | `org.apache.kafka.network..` | The `network` module is not on the classpath |
| Infrastructure | `org.apache.kafka.tiered..` | Tiered storage code lives at `server.log.remote.storage` (see MAP-01) — there is no top-level `tiered` package |
| Support | `org.apache.kafka.config..` | Configs live at `common.config` and `server.config` |
| Support | `org.apache.kafka.message..` | Generated messages live at `common.message` |
| Support | `org.apache.kafka.queue..`, `deferred..`, `logger..`, `timeline..` | None of these top-level packages are on the configured classpath |

Verification: `grep -oE "<org\.apache\.kafka\.(coordinator\|tiered\|network\|api\|admin\|config\|message\|queue\|deferred\|logger\|timeline)\." surefire-report` returns **zero** hits.

**Why it matters:**
The layered model *appears* to cover 22+ packages but actually constrains roughly 11. A future class added under e.g. `org.apache.kafka.coordinator..` (when that module is added to the classpath) would need someone to remember the layer model exists. Worse, the "comprehensive looking" layer definition lulls reviewers into thinking the codebase is well-cordoned when it isn't.

**How to fix it:**
Either (a) remove the empty globs so the layer definition is honest about what it covers, or (b) add the missing Maven modules (`group-coordinator`, `transaction-coordinator`, `network`, etc.) to `pom.xml` `<additionalClasspathElement>` so the globs become non-empty. Recommended: do (a) now, do (b) when a future architect actually wants those modules constrained.

```java
.layer("Client").definedBy("org.apache.kafka.clients..")
.layer("Server").definedBy(
        "org.apache.kafka.controller..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.image..",
        "org.apache.kafka.snapshot..")
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
        "org.apache.kafka.server.log..")
```

---

### MAP-03 — `org.apache.kafka.controller..` lives in the metadata module, not a separate layer of its own

```
SEVERITY: MEDIUM
Category: Wrong Layer / Structural Gap
Affected Rule / Constraint: layered_architecture_is_respected; raft_should_not_depend_on_controller
```

**What is wrong:**
`org.apache.kafka.controller..` is mapped into Server alongside `metadata..`, `raft..`, and `coordinator..`. Within the `metadata` Maven module the controller types (e.g., `QuorumController`, `ControllerWriteEvent`, `ConfigurationControlManager`) **import** `metadata..`, `image..`, `snapshot..`, `raft..`, and `server.common..` heavily. By placing all of those in the same Server layer, the `layeredArchitecture()` API can never enforce the asymmetry implied by the rule's own `because`: "The Raft consensus layer is a fundamental primitive that the Controller uses, not vice versa."

**Why it matters:**
The single explicit rule `raft_should_not_depend_on_controller` covers one direction of one pair. The opposite direction (`controller → raft`) is allowed (correct), but **no rule** covers the equally important pairs `metadata → controller`, `coordinator → controller`, `image → controller`, `snapshot → controller`, `raft → metadata`, `raft → coordinator`. As a result, a future PR that introduces `org.apache.kafka.raft.RaftClient` calling into `org.apache.kafka.metadata.QuorumFeatures` (a known anti-pattern) would not be flagged.

**How to fix it:**
Add explicit `noClasses()` rules for each pair where the documentation (or the `because` clauses already in the file) implies asymmetry. Minimum set:

```java
@ArchTest
public static final ArchRule raft_should_not_depend_on_metadata =
    noClasses().that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata..")
        .because("Raft is the underlying consensus primitive; the metadata module " +
                 "consumes raft, not the other way around.");

@ArchTest
public static final ArchRule raft_should_not_depend_on_image =
    noClasses().that().resideInAPackage("org.apache.kafka.raft..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
        .because("Raft must not depend on the metadata image data model.");

@ArchTest
public static final ArchRule metadata_should_not_depend_on_controller =
    noClasses().that().resideInAPackage("org.apache.kafka.metadata..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("metadata models are consumed by the controller; reversing the " +
                 "dependency would make the data model couple to the orchestrator.");

@ArchTest
public static final ArchRule image_should_not_depend_on_controller =
    noClasses().that().resideInAPackage("org.apache.kafka.image..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("image is the controller's input/output, not a controller dependent.");
```

---

### COV-02 — Infrastructure-layer pairs are not isolated

```
SEVERITY: HIGH
Category: Coverage Gap / Structural Gap
Affected Rule / Constraint: layered_architecture_is_respected (intra-Infrastructure isolation)
```

**What is wrong:**
The Infrastructure layer enumerates `network..`, `storage..`, `security..`, `tiered..` and `layeredArchitecture()` does not enforce intra-layer isolation, so the rule allows arbitrary cross-talk between e.g. `storage` and `security`, or `network` and `storage`. The `because()` for the layered rule talks about "low-level abstractions for networking, security, and data storage" but provides nothing to keep them from collapsing into one another.

**Why it matters:**
The `org.apache.kafka.security..` package exists on the classpath (DelegationTokenManager, etc.) and the `org.apache.kafka.storage..` package exists too. There is no rule preventing a regression where `storage.internals.log.LogManager` reaches into `security.DelegationTokenManager`, or vice versa. The same applies to every other intra-layer pair that the file's own header comment implicitly describes as separate concerns.

**How to fix it:**
Add explicit pair rules (only for pairs whose globs are non-empty after applying VAC-02):

```java
@ArchTest
public static final ArchRule storage_should_not_depend_on_security =
    noClasses().that().resideInAPackage("org.apache.kafka.storage..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.security..")
        .because("Log storage is independent of authentication/delegation; " +
                 "auth concerns are layered above storage in the broker.");

@ArchTest
public static final ArchRule security_should_not_depend_on_storage =
    noClasses().that().resideInAPackage("org.apache.kafka.security..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.storage..")
        .because("Security primitives must not couple to log-storage internals.");
```

(Add `network` and `tiered` variants only if those modules are added to the classpath.)

---

### COV-03 — Support layer has no internal isolation

```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: layered_architecture_is_respected (Support layer)
```

**What is wrong:**
The Support layer is the "junk drawer" — it is supposed to hold "fundamental utilities, configuration, and shared data structures", but no rule prevents `common..` from importing `image..`, `image..` from importing `snapshot..`, etc. (Once MAP-02 is applied and image/snapshot move out of Support, this finding shrinks but does not disappear.) Within the corrected Support set there are still candidates that should not depend on each other:

- `common..` (the `org.apache.kafka.clients` Maven module's shared types) should not depend on any of the `server.*` sub-packages — those are server-side internals shipped in `server-common`.
- `server.common..` should not depend on `common..` either way (`common` ships with the public clients jar; `server-common` is broker-internal).

**Why it matters:**
A regression where `org.apache.kafka.common.requests.*` starts importing `server.common.MetadataVersion` would silently leak server-internal types into the public clients jar. Surefire already shows precursors of this (e.g., `common.requests.RequestContext` implementing `server.authorizer.AuthorizableRequestContext`).

**How to fix it:**

```java
@ArchTest
public static final ArchRule common_clients_jar_should_not_depend_on_server_internals =
    noClasses().that().resideInAPackage("org.apache.kafka.common..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
        .because("The kafka-clients jar ships org.apache.kafka.common.* to end " +
                 "users; reaching into server-side internals would leak broker " +
                 "types into the public client API.");
```

Note: this rule will currently fail (surefire shows `common.requests.RequestContext implements server.authorizer.AuthorizableRequestContext`). That is itself an architecture finding worth surfacing — Kafka's own `RequestContext` straddles the boundary — and the `because` clause is the correct place to record the rationale. If the team accepts the leak, replace `should()` with a narrower exception, e.g., `.should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..").andShould().resideOutsideOfPackages("org.apache.kafka.server.authorizer..")`.

---

### COV-04 — Application layer pairing rule is one-sided in spirit

```
SEVERITY: LOW
Category: Coverage Gap
Affected Rule / Constraint: streams_should_not_depend_on_connect, connect_should_not_depend_on_streams
```

**What is wrong:**
Both directions are covered for Streams ↔ Connect, which is good. The omission is that *neither* of these top-level frameworks should depend on the broker side (`server.*`, `controller..`, `metadata..`, `image..`, `snapshot..`, `raft..`) — they are clients of the broker via `clients..`. The layered rule notionally enforces this (Application can access anything below), but the model already misclassifies `server..`, `image..`, `snapshot..` (see MAP-01/MAP-02), so the implicit guarantee evaporates. There is no dedicated "Streams must not depend on broker internals" rule.

**Why it matters:**
A regression where `streams.processor.internals.*` starts importing `metadata..` or `controller..` would be invisible the moment the layered rule is fixed and the layered model is loosened. Explicit rules fail loudly at the regression, not on a model rebalance.

**How to fix it:**

```java
@ArchTest
public static final ArchRule streams_should_not_depend_on_broker_internals =
    noClasses().that().resideInAPackage("org.apache.kafka.streams..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.controller..",
            "org.apache.kafka.metadata..",
            "org.apache.kafka.image..",
            "org.apache.kafka.snapshot..",
            "org.apache.kafka.raft..",
            "org.apache.kafka.storage..")
        .because("Kafka Streams talks to brokers exclusively via the public " +
                 "clients API; pulling broker internals into Streams would " +
                 "couple a user-facing library to broker-internal classes.");

@ArchTest
public static final ArchRule connect_should_not_depend_on_broker_internals =
    noClasses().that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.controller..",
            "org.apache.kafka.metadata..",
            "org.apache.kafka.image..",
            "org.apache.kafka.snapshot..",
            "org.apache.kafka.raft..",
            "org.apache.kafka.storage..")
        .because("Kafka Connect must not import broker internals; it interacts " +
                 "with brokers through the clients API only.");
```

---

### SEM-01 — `whereLayer("Server").mayOnlyBeAccessedByLayers("Application")` blocks Client→Server

```
SEVERITY: HIGH
Category: Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
`mayOnlyBeAccessedByLayers("Application")` means the **Server** layer (with the file's mapping: server, controller, coordinator, raft, metadata) may only be accessed by Application. This forbids Client→Server. But the `clients` module legitimately depends on `server.common.*` types (e.g., `OffsetAndEpoch`, `MetadataVersion`, `ApiMessageAndVersion`, `serialization.RecordSerde`). Surefire shows these dependencies firing as violations from `clients..` and `common..` (Support/Client) into `server.common..` (Server in this model).

**Why it matters:**
Even if MAP-01 is fixed and `server.*` shared sub-packages are demoted to Support, the layered rule still bars Client from reaching into `controller..`, `metadata..`, `image..`, `snapshot..`. That's the right direction — but the *also-broken* claim that Client may not access Server **at all** has produced a large fraction of the 766 violations and will continue to do so until both MAP-01 and SEM-01 are addressed. After MAP-01, the right setting is:

```java
.whereLayer("Server").mayOnlyBeAccessedByLayers("Application", "Client")
```

…because `clients..` is allowed to use the public broker-side metadata types in `metadata.*` only via the public re-exports under `server.common.*` once those are moved to Support.

**How to fix it:**
Apply MAP-01/MAP-02 first; then update access rules to:

```java
.whereLayer("Application").mayNotBeAccessedByAnyLayer()
.whereLayer("Server").mayOnlyBeAccessedByLayers("Application")        // App → Server only (after MAP-01)
.whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server")
.whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
.whereLayer("Support").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure")
```

---

### SEM-02 — `because()` clauses misstate the architectural facts

```
SEVERITY: MEDIUM
Category: Semantic Error / Misleading Rationale
Affected Rule / Constraint: storage_should_not_depend_on_server, raft_should_not_depend_on_controller, core_client_should_not_depend_on_admin, layered_architecture_is_respected
```

**What is wrong:**

| Rule | Claim | Reality |
|---|---|---|
| `storage_should_not_depend_on_server` | "Storage components should be independent of high-level broker management logic to allow for modularity." | **False**. Storage is contractually wired to `server.common.CheckpointFile`, `server.util.Scheduler`, `server.metrics.KafkaMetricsGroup`, `server.config.ServerLogConfigs`, `server.record.BrokerCompressionType`, `server.log.remote.storage.RemoteStorageManager`, `server.storage.log.FetchIsolation`. The dependency exists by design. |
| `raft_should_not_depend_on_controller` | "The Raft consensus layer is a fundamental primitive that the Controller uses, not vice versa." | True in spirit, but the rule covers only one of ~6 directional pairs implied by that statement. See MAP-03. |
| `core_client_should_not_depend_on_admin` | "The core client library should not depend on the administrative client to maintain a minimal footprint." | True intent, **wrong package** — the rule never matches anything (VAC-01). |
| `layered_architecture_is_respected` | "Higher layers should provide abstractions while lower layers provide foundational implementations." | Generic placeholder; the actual broken assumption is that `server..` is a high layer (it isn't — see MAP-01). |

**Why it matters:**
Misleading `because()` text turns into commit-message rationale for the next reviewer who sees the rule fail. They will spend hours trying to make storage "modular" when the rule itself is wrong.

**How to fix it:**
Apply MAP-01/MAP-02/VAC-01 above (most of these clauses disappear once those rules are corrected). For surviving rules, replace the rationale with the true asymmetry:

```java
.because("In the actual Kafka codebase storage depends on server-common " +
         "(Scheduler, CheckpointFile, KafkaMetricsGroup); see MAP-01. The " +
         "previous version of this rule was structurally incorrect.");
```

---

### SCO-01 — `consideringAllDependencies()` magnifies the impact of every mis-mapping

```
SEVERITY: MEDIUM
Category: Importer Scope / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
The rule is built with `.consideringAllDependencies()`, which counts every single field, parameter, generic type argument, throws clause, annotation, etc. (the surefire log shows entries like "has generic parameter type `Map<…, …>` with type argument depending on …"). With the broken layer mapping (MAP-01/MAP-02), this option turns each leaky generic into another violation — hence the 766 count.

**Why it matters:**
The 766 number drowns out the genuinely-actionable findings. A reviewer cannot tell the few real layering errors from the legitimate generic-parameter dependencies.

**How to fix it:**
After MAP-01/MAP-02 are applied, switch to `.consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")` so JDK and third-party generic decorations are excluded:

```java
public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
    .consideringOnlyDependenciesInAnyPackage("org.apache.kafka..")
    // ... layer definitions ...
```

Or, if the team prefers all-deps for completeness, keep `consideringAllDependencies()` *but* fix the mapping so each remaining violation is a real one.

---

### SCO-02 — `pom.xml` excludes most modules; many globs cannot fire

```
SEVERITY: MEDIUM
Category: Importer Scope
Affected Rule / Constraint: All rules that mention `coordinator..`, `network..`, `tiered..`, `tools..`, `shell..`
```

**What is wrong:**
`outputs/kafka/gemini3-flash/pom.xml` only adds **clients, core, streams, connect/api, connect/runtime, raft, storage, metadata, server-common** to the classpath. Modules NOT on the classpath: `group-coordinator`, `transaction-coordinator`, `network`, `tools`, `shell`, `trogdor`, `tiered-storage` (where `tiered..` would actually be), `core` (Scala broker — currently empty for Java archtest purposes). The header comment of the test says it covers `org.apache.kafka.controller..`, `coordinator..`, `network..`, `tiered..` — but only `controller..` (in `metadata`) and `streams/connect` are actually scanned among those.

**Why it matters:**
Half of the layered-rule globs cannot fire (VAC-02 root cause), and the file silently drops constraints the user thinks are enforced.

**How to fix it:**
Either expand the classpath:

```xml
<additionalClasspathElement>../../../kafka/group-coordinator/build/classes/java/main</additionalClasspathElement>
<additionalClasspathElement>../../../kafka/transaction-coordinator/build/classes/java/main</additionalClasspathElement>
<additionalClasspathElement>../../../kafka/network/build/classes/java/main</additionalClasspathElement>
<additionalClasspathElement>../../../kafka/tools/build/classes/java/main</additionalClasspathElement>
<additionalClasspathElement>../../../kafka/server/build/classes/java/main</additionalClasspathElement>
```

…or shrink the layer definitions to match the modules actually present (preferred until coverage is widened).

---

### STR-01 — Missing rules for documented module-pair pairs

```
SEVERITY: HIGH
Category: Structural Gap
Affected Rule / Constraint: All same-layer pairs not enumerated above
```

**What is wrong:**
`layeredArchitecture()` does **not** enforce intra-layer isolation; it only enforces *across-layer* arrows. Yet Kafka's own module list implies many same-layer pairs that should not depend on each other:

- Inside the corrected Server (`controller`, `metadata`, `raft`, `image`, `snapshot`, `coordinator`):
  - `metadata → controller` (forbidden — inverted)
  - `image → controller` (forbidden — inverted)
  - `snapshot → controller` (forbidden — inverted)
  - `coordinator → controller`
  - `raft → metadata`, `raft → image`, `raft → snapshot`, `raft → coordinator`
- Inside Infrastructure (`storage`, `security`, plus `network`/`tiered` if added):
  - `security → storage`, `storage → security`
- Inside the corrected Support (`common`, `server.common`, …):
  - `common → server.*` (would leak server-internal types into the public clients jar; see COV-03)

The current file enforces exactly **one** of these pairs (`raft → controller`).

**Why it matters:**
Each missing pair is a future regression waiting to slip past CI.

**How to fix it:**
Add the rules listed in MAP-03, COV-02, COV-03, COV-04. At minimum, every `because()` clause that mentions decoupling should be backed by a corresponding `noClasses()` rule.

---

### LOW-01 — Header doc-comment lists packages excluded by default but not asserted

```
SEVERITY: LOW
Category: Misleading documentation
Affected Rule / Constraint: Class-level comment / @AnalyzeClasses
```

**What is wrong:**
The header comment lists `org.apache.kafka.test..`, `org.apache.kafka.jmh..`, `org.apache.kafka.tools..`, `org.apache.kafka.trogdor..`, `org.apache.kafka.shell..` as "excluded packages and rationale". Only `test..` is excluded by `ImportOption.DoNotIncludeTests` (and only because they happen to be classified as test classes by the importer); the others are excluded **only** because `pom.xml` does not put their modules on the classpath. If a future reviewer re-adds those modules, the rules will silently start scanning them. There is no `withImportOption` or `that().resideOutsideOfPackages(...)` clause that actually enforces the exclusion.

**Why it matters:**
The comment promises an architectural decision (exclusion) that is actually a build-classpath accident.

**How to fix it:**
Add an explicit `ImportOption` so the exclusion is part of the test, not the build:

```java
@AnalyzeClasses(
    packages = "org.apache.kafka",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ImportOption.DoNotIncludeJars.class,
        ExcludeKafkaSupportPackages.class
    })
public class ArchitectureEnforcementTest { ... }

static final class ExcludeKafkaSupportPackages implements ImportOption {
    private static final java.util.List<String> EXCLUDED = java.util.List.of(
        "org.apache.kafka.tools.",
        "org.apache.kafka.shell.",
        "org.apache.kafka.trogdor.",
        "org.apache.kafka.jmh.");
    @Override public boolean includes(com.tngtech.archunit.core.importer.Location location) {
        String s = location.asURI().toString();
        return EXCLUDED.stream().noneMatch(s::contains);
    }
}
```

---

### LOW-02 — `ImportOption.DoNotIncludeJars` may silently drop classes the user expects

```
SEVERITY: LOW
Category: Importer Scope
Affected Rule / Constraint: @AnalyzeClasses
```

**What is wrong:**
`ImportOption.DoNotIncludeJars` excludes any class loaded from a JAR. That's compatible with the current `pom.xml` (which adds compiled-classes directories, not JARs), but if a future maintainer ever depends on `kafka-clients-X.Y.jar` directly, ArchUnit will silently skip those classes and produce vacuous results. The header comment does not warn about this.

**Why it matters:**
The combination of `DoNotIncludeJars` + the unusual `additionalClasspathElement` setup is fragile. Re-running this in a fresh checkout with a different layout will silently remove most production classes from the import.

**How to fix it:**
Document the assumption inline:

```java
// NOTE: DoNotIncludeJars is correct ONLY because pom.xml lists
// compiled-classes directories under kafka/<module>/build/classes/java/main.
// If you change the build to depend on jar artifacts, remove this option
// or replace it with a custom ImportOption that includes Kafka jars.
```

---

### LOW-03 — Redundant `connect ↔ streams` pair vs. the layered rule

```
SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: streams_should_not_depend_on_connect, connect_should_not_depend_on_streams
```

**What is wrong:**
Both rules duplicate constraints already enforced by the layered architecture: Streams and Connect are both in **Application**, which `mayNotBeAccessedByAnyLayer`. So a `connect → streams` edge is already a violation of the layered rule. Keeping the explicit pair is fine (they fire with a clearer message), but the file does not document the redundancy.

**Why it matters:**
Not a defect, just maintenance debt.

**How to fix it:**
Either delete one direction (the layered rule covers it) or add a comment noting the explicit pair exists for clearer error messages:

```java
// Explicit pair: kept for clearer diagnostics. The layered rule above
// already forbids any cross-edge between Application members.
```

---

## Summary Table

| ID | Severity | Category | Rule / Constraint |
|---|---|---|---|
| COV-01 | CRITICAL | Coverage Gap | All rules — PDF mandates nothing |
| MAP-01 | CRITICAL | Wrong Layer | `server..` mis-classified as high layer |
| MAP-02 | CRITICAL | Wrong Layer | `image..`/`snapshot..` mis-classified as Support |
| VAC-01 | HIGH | Vacuous Rule | `core_client_should_not_depend_on_admin` |
| VAC-02 | HIGH | Vacuous Rule | 8+ empty layer-member globs |
| MAP-03 | MEDIUM | Wrong Layer / Structural Gap | `controller..` and intra-Server pairs |
| COV-02 | HIGH | Coverage Gap | Infrastructure intra-layer pairs |
| COV-03 | MEDIUM | Coverage Gap | Support intra-layer pairs (kafka-clients leakage) |
| COV-04 | LOW | Coverage Gap | Streams/Connect → broker-internals not asserted |
| SEM-01 | HIGH | Semantic Error | `Server.mayOnlyBeAccessedByLayers("Application")` blocks Client→Server |
| SEM-02 | MEDIUM | Semantic Error | `because()` clauses misstate the facts |
| SCO-01 | MEDIUM | Importer Scope | `consideringAllDependencies()` magnifies wrong mapping |
| SCO-02 | MEDIUM | Importer Scope | `pom.xml` classpath excludes most modules |
| STR-01 | HIGH | Structural Gap | Missing intra-layer module-pair rules |
| LOW-01 | LOW | Misleading docs | Header lists exclusions not actually enforced |
| LOW-02 | LOW | Importer Scope | `DoNotIncludeJars` fragility |
| LOW-03 | LOW | Redundancy | Streams/Connect explicit pair vs. layered rule |

---

## Recommended Patch (consolidated)

The corrections below assume MAP-01, MAP-02, VAC-01, VAC-02 are applied together.

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
            "org.apache.kafka.snapshot..")
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
            "org.apache.kafka.server.log..")

        .whereLayer("Application").mayNotBeAccessedByAnyLayer()
        .whereLayer("Server").mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Client").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "Server")
        .whereLayer("Support").mayOnlyBeAccessedByLayers("Application", "Server", "Client", "Infrastructure")
        .because("Inferred from package naming conventions only — the supplied PDF does not specify a layered architecture. Validate against KIPs (KIP-500, KIP-405) before relying in CI.");

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

    @ArchTest
    public static final ArchRule streams_should_not_depend_on_broker_internals = noClasses()
        .that().resideInAPackage("org.apache.kafka.streams..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.controller..",
            "org.apache.kafka.metadata..",
            "org.apache.kafka.image..",
            "org.apache.kafka.snapshot..",
            "org.apache.kafka.raft..",
            "org.apache.kafka.storage..")
        .because("Streams talks to brokers via the public clients API only.");

    @ArchTest
    public static final ArchRule connect_should_not_depend_on_broker_internals = noClasses()
        .that().resideInAPackage("org.apache.kafka.connect..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.controller..",
            "org.apache.kafka.metadata..",
            "org.apache.kafka.image..",
            "org.apache.kafka.snapshot..",
            "org.apache.kafka.raft..",
            "org.apache.kafka.storage..")
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
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("metadata is the controller's domain model, not its dependent.");

    @ArchTest
    public static final ArchRule image_should_not_depend_on_controller = noClasses()
        .that().resideInAPackage("org.apache.kafka.image..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
        .because("image is consumed by the controller, never the other way.");

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

This patch will not be green out-of-the-box: it surfaces real cross-package leakage (e.g., `common.requests.RequestContext implements server.authorizer.AuthorizableRequestContext`) that the previous broken rule silently drowned out alongside its 766 false positives. That is the point — every remaining violation will now be a real one.
