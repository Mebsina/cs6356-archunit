# Adversarial Architecture Review #4 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: sonnet-4-6
**Reviewer Model**: opus-4-7
**Round**: 4 (after fixes from Review #3)
**Source under review**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`
**Surefire report**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`

- **Violations**: 1 / 33 failing tests (86 total)
- **Issues**: 6

---

## Executive Summary

- Total documented constraints identified: 0 (PDF still describes only Kafka Streams runtime semantics; every rule remains an inference, honestly disclosed).
- Total rules generated: 33 (unchanged from Round 3)
- Coverage rate: N/A
- **Critical Gaps**:
  - **REGR-05** (the dominant signal): the Round-3 routing of the six newly-added `server.*` sub-packages **misclassified four of them**. Specifically: `server.purgatory` (placed in Core) contains storage-aware delayed-operation subclasses; `server.log.remote.quota` (placed in Server) is a Core-side dependency of `RemoteLogManager`; `server.quota` (placed in Server) hosts the public `ClientQuotaCallback` SPI and `QuotaType` value enum that Core/Consensus consume; `server.log.remote` (placed in Core) contains `TopicPartitionLog` which exposes `storage.internals.log.UnifiedLog`. Together these account for ~62 of the 86 remaining violations.
  - **MOD-05-WIDEN**: the Round-3 `server.config -> storage` carve-out fixed only one of three targets the same `AbstractKafkaConfig` aggregates from. `network`, `raft`, and `server` (top-level for `DynamicThreadPool`) are still flagged.
- Overall verdict: `FAIL` — but **trivially close to passing**. All 6 issues are local re-classifications or one-line `ignoreDependency` additions; no structural rework is required.

**Trajectory across rounds**:
| Round | Failing tests | Total violations |
|-------|---------------|------------------|
| 1 | 9 / 24 | 2,213 |
| 2 | 4 / 32 | 1,071 |
| 3 | 2 / 33 | 209 |
| **4** | **1 / 33** | **86** |

The remaining 86 violations all live on `kafka_layered_architecture`; every other rule passes. The shape of the failure is now narrow and architectural (4 sub-package mis-routings + 1 incomplete carve-out), not systemic.

---

## Findings

### REGR-05 — `server.log.remote.quota` misclassified as Server

```
[REGR-05] SEVERITY: CRITICAL
Category: Wrong Layer (regression of REGR-04)
Affected Rule / Constraint: kafka_layered_architecture (SERVER_PACKAGES, my Round-3 advice)
```

**What is wrong**:
In Round 3 I recommended placing `org.apache.kafka.server.log.remote.quota..` in `SERVER_PACKAGES` based on the names `RLMQuotaManager`/`RLMQuotaMetrics` sounding like "broker runtime quota management". The surefire output proves that was wrong: the package is consumed almost exclusively by `org.apache.kafka.server.log.remote.storage.RemoteLogManager` (and `RemoteLogReader`) — both of which are in **Core**. Concrete callers (surefire L19-21, L29-33, L45-63):

- `RemoteLogManager.<init>` constructs `RLMQuotaMetrics` (×2)
- `RemoteLogManager` fields `copyQuotaMetrics`, `fetchQuotaMetrics`, `rlmCopyQuotaManager`, `rlmFetchQuotaManager`
- `RemoteLogManager.createRLMCopyQuotaManager`, `createRLMFetchQuotaManager`, `copyQuotaManagerConfig`, `fetchQuotaManagerConfig`, `updateCopyQuota`, `updateFetchQuota`, `getFetchThrottleTimeMs`, `fetchThrottleTimeSensor`, `RLMCopyTask.copyLogSegmentsToRemote`
- `RemoteLogReader` field `quotaManager`, parameter, `call()`

Every caller is in Core, no caller is in Server. That is the definition of "wrong layer".

**Why it matters**:
~20 of the 86 layered violations come from this single mis-routing. The dependency direction (Core → Server) is the wrong way around the entire layer model, but the *real* semantics (an SPI module's helper utility) are perfectly Core-internal.

**How to fix it**:
Move the package from `SERVER_PACKAGES` to `CORE_PACKAGES`:

```java
private static final String[] CORE_PACKAGES = {
    // ... existing entries ...
    "org.apache.kafka.server.log.remote..",      // (already present)
    "org.apache.kafka.server.log.remote.quota..", // REGR-05: helper utilities consumed by
                                                  //         server.log.remote.storage (Core)
};

private static final String[] SERVER_PACKAGES = {
    // ... remove this entry: ...
    // "org.apache.kafka.server.log.remote.quota.."
};
```

---

### REGR-06 — `server.purgatory` misclassified as Core (storage-aware subclasses)

```
[REGR-06] SEVERITY: CRITICAL
Category: Wrong Layer / Coverage Gap
Affected Rule / Constraint: kafka_layered_architecture (CORE_PACKAGES, my Round-3 advice)
```

**What is wrong**:
In Round 3 I recommended placing `org.apache.kafka.server.purgatory..` in CORE because the framework class names (`DelayedOperation`, `DelayedFuturePurgatory`, `TopicPartitionOperationKey`) suggest a generic delayed-operation utility. Reality: the *same package* also contains storage-specific subclasses that import `storage.internals.log` heavily:

- `DelayedRemoteFetch` — fields & methods reference `LogReadResult`, `RemoteLogReadResult`, `FetchPartitionStatus`, `RemoteStorageFetchInfo`, `FetchDataInfo`, `LogOffsetMetadata` (surefire L22-25, L34-41, L64-82)
- `DelayedRemoteListOffsets` — references `AsyncOffsetReadFutureHolder`, `OffsetResultHolder$FileRecordsOrError` (surefire L83-93)
- `ListOffsetsPartitionStatus` (and `$Builder`) — generic types `Optional<AsyncOffsetReadFutureHolder<OffsetResultHolder$FileRecordsOrError>>` (surefire L26-27, L38-41, L94-97)

These three classes legitimately need storage types — the *purpose* of `DelayedRemoteFetch` is to wrap a `RemoteLogReadResult`. They are storage-aware by design.

**Why it matters**:
~35 of the 86 layered violations come from this single mis-routing. Of all the issues in this round, this is the largest by violation count.

**How to fix it**:
Two options. **Recommended: option B**, because it preserves the conceptual classification of `purgatory` as a Core utility framework while accepting that storage-coupled subclasses live alongside it.

**Option A — move the entire package to Server**:
```java
private static final String[] CORE_PACKAGES = {
    // ... remove ...
    // "org.apache.kafka.server.purgatory.."
};
private static final String[] SERVER_PACKAGES = {
    // ... add ...
    "org.apache.kafka.server.purgatory..",
};
```
Trade-off: this hides the fact that `DelayedOperation`, `DelayedOperationKey`, `DelayedOperationPurgatory` are general-purpose utilities — they get incorrectly labelled as broker-runtime.

**Option B (recommended) — keep in Core, add carve-out**:
```java
// REGR-06: server.purgatory is a generic delayed-operation framework (Core),
// but DelayedRemoteFetch / DelayedRemoteListOffsets / ListOffsetsPartitionStatus
// are storage-aware delayed-operation subclasses that intentionally reference
// storage.internals.log result types. Keep the package in Core and exempt the
// crossing.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.purgatory.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
```

If you want this tighter, restrict by source class:
```java
.ignoreDependency(
    DescribedPredicate.describe(
        "is a storage-aware DelayedOperation subclass",
        (JavaClass c) -> c.getName().equals("org.apache.kafka.server.purgatory.DelayedRemoteFetch")
                     || c.getName().equals("org.apache.kafka.server.purgatory.DelayedRemoteListOffsets")
                     || c.getName().startsWith("org.apache.kafka.server.purgatory.ListOffsetsPartitionStatus")),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
```

---

### REGR-07 — `server.quota` misclassified as Server (SPI + value enum)

```
[REGR-07] SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: kafka_layered_architecture (SERVER_PACKAGES, my Round-3 advice)
```

**What is wrong**:
In Round 3 I noted `server.quota` is "mixed — split required" and the user (correctly, given that note) put it in `SERVER_PACKAGES`. But the actual content makes that wrong:

- `server.quota.ClientQuotaCallback` — public SPI interface (consumed by `metadata.publisher.DynamicTopicClusterQuotaPublisher`, surefire L18, L28, L42)
- `server.quota.QuotaType` — value enum with `RLM_COPY` / `RLM_FETCH` constants (consumed by `server.log.remote.storage.RemoteLogManager`, surefire L52, L55)
- `server.quota.ClientQuotaManager`, `ControllerMutationQuotaManager`, `ReplicationQuotaManager`, `ThrottledChannel`, etc. — these *are* runtime classes

Two of the consumers live in Core (`server.log.remote.storage`) and Consensus (`metadata.publisher`). The package-level classification "Server" is incompatible with this consumption pattern.

**Why it matters**:
~5 of the 86 violations. Smaller by count than REGR-05/06, but the symptom is identical: a higher layer cannot consume a lower-layer SPI/enum because the SPI's package is incorrectly labelled as "higher".

**How to fix it**:
The cleanest move is to reclassify the whole package as Core (the SPI and value enum are publicly consumed; the runtime classes alongside them are an internal implementation detail that doesn't need to be in a separate layer):

```java
private static final String[] CORE_PACKAGES = {
    // ... add ...
    "org.apache.kafka.server.quota..",          // REGR-07: ClientQuotaCallback SPI + QuotaType enum
};
private static final String[] SERVER_PACKAGES = {
    // ... remove ...
    // "org.apache.kafka.server.quota.."
};
```

If you'd rather keep the runtime classes in Server, add two carve-outs instead of moving:

```java
// REGR-07 (alternative): keep server.quota in Server but exempt the two known
// SPI/enum consumers in Core and Consensus.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.quota.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.publisher.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.quota.."))
```

The "move to Core" approach is preferred: simpler, fewer special cases, and matches the intent of how Kafka exposes these classes publicly (`ClientQuotaCallback` is in the public API surface).

---

### REGR-08 — `server.log.remote` misclassified as Core (TopicPartitionLog references storage)

```
[REGR-08] SEVERITY: HIGH
Category: Wrong Layer / Overly Broad Glob
Affected Rule / Constraint: kafka_layered_architecture (CORE_PACKAGES, my Round-3 advice)
```

**What is wrong**:
In Round 3 I recommended `org.apache.kafka.server.log.remote..` in CORE under the description "TopicPartitionLog shared abstraction". The glob (`..`) makes that include every sub-package of `server.log.remote`, including `server.log.remote.storage` (already separately listed in Core, fine) and `server.log.remote.quota` (now incorrectly Server, see REGR-05). Independent of those sub-packages, the **top-level** `server.log.remote.TopicPartitionLog` itself returns `Optional<storage.internals.log.UnifiedLog>` (surefire L44) — Core → Storage upward.

**Why it matters**:
1 violation directly (TopicPartitionLog). But more importantly the Round-3 glob `server.log.remote..` is a footgun: **anything** added under `server.log.remote.X` will be silently classified as Core forever. After REGR-05 moves `server.log.remote.quota..` to Core anyway, this becomes a non-issue, but the principle stands: explicit narrow listings are safer than `..` globs over multi-purpose namespaces.

**How to fix it**:
Replace the glob with a top-level-only entry, then add an explicit carve-out for `TopicPartitionLog`:

```java
private static final String[] CORE_PACKAGES = {
    // ... change ...
    // OLD: "org.apache.kafka.server.log.remote..",
    "org.apache.kafka.server.log.remote",        // top-level only (TopicPartitionLog)
    "org.apache.kafka.server.log.remote.storage..",  // already present
    "org.apache.kafka.server.log.remote.quota..",    // after REGR-05 fix
};

// REGR-08: TopicPartitionLog is a remote-log abstraction that exposes a
// storage UnifiedLog as part of its public API. Same factory-on-SPI pattern
// as MOD-10.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote"),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
```

If you find narrowing `..` to bare-package too disruptive, just keep the glob and add the carve-out:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote"),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
```

(`server.log.remote.storage` and `server.log.remote.quota` won't match this source predicate because they live in sub-packages, so the carve-out is safely scoped.)

---

### MOD-05-WIDEN — `server.config` aggregates configs from network, raft, and server-top-level

```
[MOD-05-WIDEN] SEVERITY: HIGH
Category: Overly Narrow ignoreDependency
Affected Rule / Constraint: kafka_layered_architecture (MOD-05 carve-out)
```

**What is wrong**:
The Round-3 MOD-05 fix added `ignoreDependency(server.config.. -> storage..)` to cover `AbstractKafkaConfig`'s static aggregation of `LogConfig`, `CleanerConfig`, `ProducerStateManagerConfig`. The same mechanism aggregates `ConfigDef` instances from three other source layers — this was missed:

- `AbstractKafkaConfig.<clinit>` reads `network.SocketServerConfigs.CONFIG_DEF` (surefire L98)
- `AbstractKafkaConfig.<clinit>` reads `raft.KRaftConfigs.CONFIG_DEF`, `raft.MetadataLogConfig.CONFIG_DEF`, `raft.QuorumConfig.CONFIG_DEF` (L99-101)
- `AbstractKafkaConfig.listenerListToEndPoints` calls `network.SocketServerConfigs.listenerListToEndPoints` (L43)
- `DynamicBrokerConfig.<clinit>` reads `network.SocketServer.RECONFIGURABLE_CONFIGS` (L102)
- `DynamicBrokerConfig.<clinit>` reads `server.DynamicThreadPool.RECONFIGURABLE_CONFIGS` (L103) — Core → Server top-level

**Why it matters**:
~6 violations. Same architectural pattern as MOD-05; same fix shape. Leaving it un-suppressed means CI noise blocks the same rule that's otherwise close to green.

**How to fix it**:
Add three more `ignoreDependency` clauses (or one broader clause):

```java
// MOD-05-WIDEN: AbstractKafkaConfig and DynamicBrokerConfig also aggregate
// ConfigDef / RECONFIGURABLE_CONFIGS from network (SocketServerConfigs, SocketServer),
// raft (KRaftConfigs, MetadataLogConfig, QuorumConfig), and the top-level server
// package (DynamicThreadPool). Same declarative-config aggregation as MOD-05.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.network.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server"))   // top-level only
```

A more durable single-clause variant:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    DescribedPredicate.describe(
        "is a ConfigDef-providing class in any layer (declarative-config aggregation)",
        (JavaClass c) -> c.getPackageName().startsWith("org.apache.kafka.storage")
                     || c.getPackageName().startsWith("org.apache.kafka.network")
                     || c.getPackageName().startsWith("org.apache.kafka.raft")
                     || c.getName().equals("org.apache.kafka.server.DynamicThreadPool")))
```

If you go with the single-clause variant, you can delete the existing MOD-05 clause (it's subsumed).

---

### LAY-NEW-04 — `every_production_class_must_be_in_a_layer` custom condition is silent on success but un-validated

```
[LAY-NEW-04] SEVERITY: LOW
Category: Test Diagnostic Quality
Affected Rule / Constraint: every_production_class_must_be_in_a_layer
```

**What is wrong**:
Round 3's LAY-NEW-03 fix replaced the verbose `resideInAnyPackage(...)` with a custom `ArchCondition<JavaClass>` that emits one short sentence per violation. The rule passed in Round 4, so we cannot directly observe the new failure-message format. That's a *good* outcome (zero orphaned classes), but it also means the message-quality fix is currently un-tested. The next time the guard fires, the format may turn out to need iteration.

**Why it matters**:
Cosmetic — the rule works correctly. Worth a note so a future failure is diagnosed quickly.

**How to fix it**:
No code change required. Optionally add a short JUnit-level smoke test that exercises the custom `ArchCondition` against a synthetic `JavaClass` whose package is not in `ALL_LAYER_PACKAGES`, asserting the produced event message contains "is in package … which is not assigned to any layer". This is overkill for an architecture-test file; flag and move on.

---

### POS-02 — Confirmed successful Round-3 fixes

```
[POS-02] SEVERITY: (informational)
```

The following Round-3 fixes are confirmed working in Round 4 (zero matching violations in surefire):

- **REGR-04** (12 server.* sub-packages enumerated): every-class guard rule passes; orphan count = 0. ✔ (with the four mis-routings still to fix per REGR-05/06/07/08)
- **MOD-03** (`metadata → clients.admin`): no `KafkaConfigSchema`/`ScramCredentialData` violations. ✔
- **MOD-04** (`security → clients.admin`): no `CredentialProvider` violations. ✔
- **MOD-05** (`server.config → storage`): the storage targets are gone from surefire (network/raft/server top-level remain — see MOD-05-WIDEN). ✔ (partial)
- **MOD-06** (`server.metrics → image/metadata/controller`): no `BrokerServerMetrics`/`NodeMetrics` violations. ✔
- **MOD-07** (`server.util → metadata`): no `NetworkPartitionMetadataClient` violations. ✔
- **MOD-08** (`storage → server.log.remote.metadata.storage.generated`): no `ProducerStateManager` violations. ✔
- **MOD-09** (`common → clients`): no `MessageFormatter`/`ApiVersionsResponse`/`ShareFetchRequest`/`SaslClientAuthenticator` violations. ✔
- **MOD-10** (`server.log.remote.storage → server.log.remote.metadata.storage`): no `RemoteLogManager.createRemoteLogMetadataManager` violation. ✔
- **FP-AUTH-01** (`metadata.authorizer → controller`): no `AclMutator`/`ClusterMetadataAuthorizer` violations. ✔
- **LAY-NEW-03** (custom guard message): rule passes; format unobserved (LAY-NEW-04). ✔ (but unverified)

Of 11 Round-3 fixes applied, **10 are fully verified working**, 1 is partially verified (MOD-05 missed network/raft/server-top), and 1 is un-observed because the underlying rule passes (LAY-NEW-03).

---

## Recommended Patch (consolidated)

Apply in any order — the four REGR-* fixes are independent of each other and of MOD-05-WIDEN.

```java
// ============================================================================
// REGR-05 + REGR-07 + REGR-08 + REGR-04 (refined): rebalanced layer contents.
// ============================================================================
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
    "org.apache.kafka.server.mutable..",
    "org.apache.kafka.server.network..",
    "org.apache.kafka.server.policy..",
    "org.apache.kafka.server.purgatory..",
    "org.apache.kafka.server.telemetry..",
    // REGR-08: narrow the glob to top-level only; the two real sub-packages
    // (server.log.remote.storage, server.log.remote.quota) are listed explicitly.
    "org.apache.kafka.server.log.remote",
    // REGR-05: helper utilities consumed by server.log.remote.storage (Core)
    "org.apache.kafka.server.log.remote.quota..",
    // REGR-07: ClientQuotaCallback SPI + QuotaType enum + runtime classes
    "org.apache.kafka.server.quota.."
};

private static final String[] SERVER_PACKAGES = {
    "org.apache.kafka.server",
    "org.apache.kafka.server.share..",
    "org.apache.kafka.server.transaction..",
    "org.apache.kafka.server.log.remote.metadata.storage..",
    "org.apache.kafka.controller..",
    "org.apache.kafka.network..",
    "org.apache.kafka.server.controller..",
    "org.apache.kafka.server.logger..",
    "org.apache.kafka.server.partition..",
    "org.apache.kafka.server.replica.."
    // REGR-05 removed: "org.apache.kafka.server.log.remote.quota.."
    // REGR-07 removed: "org.apache.kafka.server.quota.."
};

// ============================================================================
// REGR-06 + REGR-08 + MOD-05-WIDEN: add to kafka_layered_architecture's
// ignoreDependency block.
// ============================================================================

// REGR-06: server.purgatory is a generic delayed-operation framework but
// hosts storage-aware DelayedRemoteFetch / DelayedRemoteListOffsets /
// ListOffsetsPartitionStatus that intentionally reference storage result types.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.purgatory.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))

// REGR-08: TopicPartitionLog (top-level server.log.remote) returns
// Optional<storage.internals.log.UnifiedLog> as part of its public API.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote"),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))

// MOD-05-WIDEN: AbstractKafkaConfig & DynamicBrokerConfig static aggregation
// also reads ConfigDef / RECONFIGURABLE_CONFIGS from network, raft, and the
// top-level server package (DynamicThreadPool).
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.network.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server"))
```

**Expected outcome after applying**:
- `kafka_layered_architecture`: 0 violations.
- `every_production_class_must_be_in_a_layer`: 0 violations (no orphans introduced — REGR-08's bare-package switch covers `TopicPartitionLog`, the only top-level `server.log.remote` class).
- All 33 rules green.

---

## Severity Tally

| Severity | Count | IDs |
|----------|-------|-----|
| CRITICAL | 2 | REGR-05, REGR-06 |
| HIGH | 3 | REGR-07, REGR-08, MOD-05-WIDEN |
| LOW | 1 | LAY-NEW-04 |
| Informational | 1 | POS-02 |
| **Total** | **6** | |

---

## Closing Note

Round 3 → Round 4 is the cleanest jump in the series: 209 → 86 violations, 12 → 6 issues, all the cross-layer SPI/DTO patterns from Round 3 are now closed. Every Round-4 finding is either a re-classification I got wrong in Round 3 (REGR-05/06/07/08 — accept my mea culpa) or an enumeration-completeness miss in MOD-05 that's structurally identical to fixes already in place. After applying the consolidated patch above, the suite should run green and become genuinely useful as a regression detector — failures will mean either a new orphaned `server.X` package (caught by the guard) or a new cross-layer pattern (caught by the layered rule), not noise from misconfiguration.

One meta-observation worth recording in the file's header: the four sub-package mis-routings I made in Round 3 all stemmed from inferring layer membership **from the package name alone** rather than from "who depends on classes in this package". The latter signal is more reliable for Kafka, where package names like `server.quota` or `server.log.remote.quota` describe topic, not architectural layer. A future maintainer adding a new `server.X` sub-package would do well to ask "which existing layer's classes call into this one?" before assigning layer membership.
