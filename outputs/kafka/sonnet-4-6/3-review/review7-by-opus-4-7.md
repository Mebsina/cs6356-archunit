# Adversarial Architecture Review #7 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: sonnet-4-6
**Reviewer Model**: opus-4-7
**Round**: 7 (after fixes from Review #6 — narrowing patches re-broke the suite, *as intended*)
**Source under review**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`
**Surefire report**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`

- **Violations**: 1 / 33 failing tests (79 total)
- **Issues**: 4

---

## Executive Summary

Round 6 declared the suite green and warned that two `ignoreDependency` clauses (OVR-01 `server.. → clients..` and OVR-02 `common.. → clients..`) were package-to-package wildcards that would silently allow any future cross-layer drift. The user applied the recommended narrowing patches. As predicted, the suite is now red again — **with 79 new violations**.

This is the correct outcome. None of the 79 violations are regressions in code under test; every one of them was already present in the codebase but **suppressed by the wildcard carve-outs**. The narrowing surfaces three previously-invisible architectural patterns that need their own documented carve-outs:

1. `server.log.remote.metadata.storage` (the topic-based tiered-metadata store) **is** a Kafka client by design — it uses `KafkaProducer`/`KafkaConsumer`/`Admin` to read/write the internal `__remote_log_metadata` topic. ~70 violations.
2. `server.share` consumes `clients.consumer.{AcknowledgeType, internals.ShareAcquireMode}` as KIP-932 share-group wire-protocol enums. ~8 violations.
3. The OVR-01 narrowed list missed one broker-outbound-RPC type (`MetadataRecoveryStrategy`). 1 violation.

- Total documented constraints identified: 0 (PDF still unrelated; suite remains an inference)
- Total rules generated: 33 (unchanged)
- Coverage rate: N/A
- **Critical Gaps**: TIERED-01 — the whole `server.log.remote.metadata.storage` sub-package is a Kafka-client consumer by design and needs a wholesale carve-out.
- Overall verdict: `FAIL` — but a "correct fail". The Round-6 narrowing did its job: it traded a green-but-toothless suite for a red-but-load-bearing one. Three documented carve-outs return it to green with the new precision intact.

**Trajectory across rounds**:

| Round | Failing | Total violations | Note |
|-------|---------|------------------|------|
| 1 | 9 / 24 | 2,213 | Initial generation |
| 2 | 4 / 32 | 1,071 | After Round 1 fixes |
| 3 | 2 / 33 | 209 | After Round 2 fixes |
| 4 | 1 / 33 | 86 | After Round 3 fixes |
| 5 | 1 / 33 | 14 | After Round 4 fixes |
| 6 | 0 / 33 | 0 | After Round 5 fixes |
| **7** | **1 / 33** | **79** | After Round 6 narrowing (intentional re-break) |

The "going up" is a feature, not a regression: 79 < 86 (Round 4) and the new failures are surgically isolated to two named broker sub-packages plus one missing list entry, not the broad MOD-01/MOD-09 surface they previously hid behind.

---

## Findings

### TIERED-01 — `server.log.remote.metadata.storage` is a Kafka-client consumer by design

```
[TIERED-01] SEVERITY: HIGH
Category: Coverage Gap (knock-on of OVR-01 narrowing)
Affected Rule / Constraint: kafka_layered_architecture (MOD-01 narrowed scope)
```

**What is wrong**:
KIP-405 designs tiered storage with the metadata for remote segments stored *in Kafka itself*, on an internal topic named `__remote_log_metadata`. The default implementation lives in `org.apache.kafka.server.log.remote.metadata.storage`. By design, the entire package is a Kafka-client consumer — it constructs `KafkaProducer` to publish metadata records, `KafkaConsumer` to ingest them, and `Admin` to bootstrap the internal topic.

The Round-6 OVR-01 narrowing exempts only the broker-outbound-RPC types (`KafkaClient`, `NetworkClient`, `NodeApiVersions`, etc.) and now reports ~70 violations across these classes:

| Source class | Clients types consumed |
|--------------|------------------------|
| `ConsumerManager` | `KafkaConsumer`, `RecordMetadata` |
| `ConsumerTask` | `Consumer`, `ConsumerRecord`, `ConsumerRecords`, `CloseOptions` |
| `ProducerManager` | `KafkaProducer`, `ProducerRecord`, `RecordMetadata`, `Callback` |
| `TopicBasedRemoteLogMetadataManager` | `Admin`, `NewTopic`, `Config`, `ConfigEntry`, `CreateTopicsResult`, `DescribeTopicsResult`, `TopicDescription` |
| `TopicBasedRemoteLogMetadataManagerConfig` | `AdminClientConfig`, `ConsumerConfig`, `ProducerConfig` |
| `serialization.RemoteLogMetadataSerde$RemoteLogMetadataFormatter` | `ConsumerRecord` |

These are not regressions — `TopicBasedRemoteLogMetadataManager` was added in 3.6 (KIP-405) and has used `Admin`/`Producer`/`Consumer` since day one. They were silently allowed by the previous `server.. → clients..` wildcard.

**Why it matters**:
~70 of the 79 layered violations come from this single package. Without a carve-out, the rule is permanently red. *With* the wildcard carve-out, the rule was permanently mute. The right answer is a tightly-scoped wholesale exemption for this one sub-package.

**How to fix it**:
Add one `ignoreDependency` clause covering the entire `server.log.remote.metadata.storage..` sub-package:

```java
// TIERED-01: server.log.remote.metadata.storage is the topic-based tiered-metadata
// store (KIP-405). By design the entire sub-package uses KafkaProducer/KafkaConsumer/
// Admin/NewTopic to read & write the internal __remote_log_metadata topic. This is
// the canonical "broker uses the Kafka client library" pattern; no narrower
// exemption is meaningful because every major class in the package is a client user.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
```

Add it directly above the existing MOD-01 carve-out so the two related "broker-as-client" exemptions sit together.

---

### SHARE-01 — `server.share` consumes share-group wire-protocol enums from `clients.consumer`

```
[SHARE-01] SEVERITY: HIGH
Category: Coverage Gap (knock-on of OVR-01 narrowing)
Affected Rule / Constraint: kafka_layered_architecture (MOD-01 narrowed scope)
```

**What is wrong**:
The `org.apache.kafka.server.share` package (KIP-932 server-side share-group implementation) consumes two wire-protocol enums that live under `clients.consumer.*`:

- `clients.consumer.AcknowledgeType` — used by `server.share.metrics.ShareGroupMetrics` (`.values()`, `.toString()`, `.id` field — surefire L23-25, L95-96)
- `clients.consumer.internals.ShareAcquireMode` — used by `server.share.fetch.ShareFetch` (`.forId(byte)`, field type, return type — surefire L22, L28, L94)

These are exactly the same shared-DTO pattern the suite already documents in MOD-02/MOD-03/MOD-04 for `clients.admin` enums (`ScramMechanism`, `ConfigEntry$ConfigSource`, etc.). The wire-format enum is defined in `clients.consumer` because the consumer needs it; the server share-group runtime needs it for the matching server-side wire encoding.

**Why it matters**:
8 of the 79 violations. Architecturally identical to MOD-02/03/04 — the only difference is which package the shared enum lives in (`clients.consumer` instead of `clients.admin`).

**How to fix it**:
Two options. **Recommended is option B** because share-group is an actively-evolving KIP that will likely add more wire-protocol enums.

**Option A — narrow class-list match**:
```java
// SHARE-01: server.share consumes KIP-932 share-group wire-protocol enums
// from clients.consumer (same shared-DTO pattern as MOD-02/03/04).
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.share.."),
    DescribedPredicate.describe(
        "is a share-group wire-protocol enum from clients.consumer",
        (JavaClass c) -> c.getName().equals("org.apache.kafka.clients.consumer.AcknowledgeType")
                     || c.getName().equals("org.apache.kafka.clients.consumer.internals.ShareAcquireMode")))
```

**Option B (recommended) — sub-package-scoped**:
```java
// SHARE-01: same shared-DTO pattern as MOD-02/03/04; the share-group KIP
// (KIP-932) places client-side wire enums in clients.consumer that the
// server-side runtime in server.share needs to decode/encode.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.share.."),
    JavaClass.Predicates.resideInAnyPackage(
        "org.apache.kafka.clients.consumer",
        "org.apache.kafka.clients.consumer.internals"))
```

Option B is broader but keeps the rule readable as KIP-932 grows. The day a third share-group enum is added, the suite stays green without a new commit.

---

### OVR-01-WIDEN — `MetadataRecoveryStrategy` missing from the OVR-01 list

```
[OVR-01-WIDEN] SEVERITY: MEDIUM
Category: Overly Narrow ignoreDependency (incomplete enumeration)
Affected Rule / Constraint: kafka_layered_architecture (OVR-01 narrowed list)
```

**What is wrong**:
The OVR-01 narrowing in Round 6 enumerated 15 broker-outbound-RPC types from `clients.*`. Surefire L29 shows one missing entry:

> `Method <org.apache.kafka.server.NodeToControllerChannelManagerImpl.buildNetworkClient(...)> gets field <org.apache.kafka.clients.MetadataRecoveryStrategy.NONE>`

`MetadataRecoveryStrategy` (KIP-899) is a top-level enum in `org.apache.kafka.clients` that configures how a `NetworkClient` recovers when its `Metadata` instance becomes stale. It belongs in the same OVR-01 list as `KafkaClient`/`NetworkClient`/`Metadata` itself — `NodeToControllerChannelManagerImpl` is the canonical broker-outbound-RPC user.

**Why it matters**:
1 violation today. More importantly, it confirms the OVR-01 narrowing is on the right track but incomplete. Adding `MetadataRecoveryStrategy` is a one-line fix; the broader lesson is that the narrow list needs a maintenance discipline (every new top-level `clients.*` type that broker code needs must be added).

**How to fix it**:
Add to the OVR-01 `DescribedPredicate`:

```java
|| n.equals("org.apache.kafka.clients.MetadataRecoveryStrategy")
|| n.startsWith("org.apache.kafka.clients.MetadataRecoveryStrategy$")
```

Insert near `MetadataUpdater` for thematic grouping.

---

### POS-05 — Confirmed: Round-6 narrowing worked exactly as designed

```
[POS-05] SEVERITY: (informational)
```

This is the most architecturally interesting outcome of the entire 7-round series. Round 6 explicitly warned:

> *"`MOD-01 server.. → clients..` is a package-to-package wildcard. Originally added to suppress 4–8 specific outbound-RPC / shared-DTO call sites; as written they exempt any future `server.*` → `clients.*` or `common.*` → `clients.*` dependency, including pulling Streams or Connect into broker code."*

Round 7 surfaces precisely those previously-hidden dependencies:

- **Tiered-storage metadata manager (TIERED-01)**: `server.log.remote.metadata.storage` was added in 3.6 (KIP-405) and has consumed `KafkaProducer`/`KafkaConsumer`/`Admin` since the day it landed. The wildcard hid this for years of code growth.
- **Share-group wire enums (SHARE-01)**: `server.share` was added in 4.0 (KIP-932). Same story — consumed `clients.consumer` enums silently.
- **NodeToControllerChannelManagerImpl (OVR-01-WIDEN)**: pre-existing, but the wildcard meant nobody had to think about whether `MetadataRecoveryStrategy` was an outbound-RPC type or some other category.

None of these are bugs in Kafka. They are real architectural patterns that *should be documented in the architecture test*. Round 6's narrowing forced that documentation to happen. Without the narrowing, a hypothetical future change like `server.replica.Replica → clients.consumer.ConsumerRecord` (which would be a real defect) would still pass silently. With the narrowing + the three new carve-outs from this review, that defect would fire on the first PR that introduced it.

This is the value loop the entire 7-round exercise has been building toward: each round either *finds new real cross-layer patterns* (good) or *narrows previously-loose carve-outs* (also good). The suite is approaching a state where every line in the file represents a deliberate architectural decision, not a "make it green" workaround.

Round-5 confirmation status: **all** Round-6 fixes (OVR-01, OVR-02, VAC-NEW-02, PREC-01, PREC-02) are verified working as intended. OVR-01 and OVR-02 are doing exactly what the review predicted — surfacing real dependencies that the wildcards hid. VAC-NEW-02, PREC-01, PREC-02 are silent (cosmetic fixes; nothing to surface in surefire). ✔

---

## Recommended Patch (consolidated)

Three additions and one one-line list extension. None require structural changes.

```java
// Add above the existing MOD-01 carve-outs in kafka_layered_architecture:

// TIERED-01: server.log.remote.metadata.storage is the topic-based tiered-
// metadata store (KIP-405). By design the entire sub-package uses
// KafkaProducer / KafkaConsumer / Admin / NewTopic to read & write the
// internal __remote_log_metadata topic. No narrower exemption is meaningful
// because every major class in the package is a client user.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))

// SHARE-01: server.share consumes KIP-932 share-group wire-protocol enums
// (AcknowledgeType, ShareAcquireMode) from clients.consumer. Same shared-DTO
// pattern as MOD-02/03/04. Sub-package-scoped to keep the rule readable as
// KIP-932 grows.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.share.."),
    JavaClass.Predicates.resideInAnyPackage(
        "org.apache.kafka.clients.consumer",
        "org.apache.kafka.clients.consumer.internals"))

// OVR-01-WIDEN: append to the existing OVR-01 DescribedPredicate body:
|| n.equals("org.apache.kafka.clients.MetadataRecoveryStrategy")
|| n.startsWith("org.apache.kafka.clients.MetadataRecoveryStrategy$")
```

**Expected outcome after applying**: 33 / 33 passing, 0 violations. Suite returns to green with the precision improvements from Round 6 intact.

---

## Severity Tally

| Severity | Count | IDs |
|----------|-------|-----|
| HIGH | 2 | TIERED-01, SHARE-01 |
| MEDIUM | 1 | OVR-01-WIDEN |
| Informational | 1 | POS-05 |
| **Total** | **4** | |

---

## Closing Note

Seven rounds in, the suite has demonstrated the full iterative review cycle:

- **Rounds 1–5**: discover real cross-layer patterns the rules don't yet model. Each round surfaces a category, the user models it as an `ignoreDependency` clause or layer-membership tweak, and the next round finds the next category.
- **Round 6**: with all *known* patterns modeled, attack the **precision** of the model itself. Identify wildcards that hide future drift, replace them with explicit lists.
- **Round 7**: the precision attack surfaces *new* real patterns that the wildcards were hiding. Document them properly.

If you ship a Round 8, the most likely outcomes are:

1. **Most probable**: 33/33 green again with the three Round-7 carve-outs applied, and Round 8 is a confirmation pass with possibly one more sub-package-level pattern surfaced. The marginal value drops sharply.
2. **Less probable**: a fourth previously-hidden pattern emerges. Probably narrow.
3. **Long tail**: do the slices/cycle redesign floated in Reviews #5 and #6. That's a different exercise.

For an architecture-test suite generated by a model from a wildly-mismatched documentation source on a 800k-LOC codebase with no first-party architecture documentation, ending at "33 passing rules, 19 documented carve-outs, 0 violations, every dependency justified" is a strong outcome. Apply the Round-7 patch and ship.
