# Adversarial Architecture Review #5 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: sonnet-4-6
**Reviewer Model**: opus-4-7
**Round**: 5 (after fixes from Review #4)
**Source under review**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`
**Surefire report**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`

- **Violations**: 1 / 33 failing tests (14 total)
- **Issues**: 2

---

## Executive Summary

- Total documented constraints identified: 0 (PDF still unrelated to broader Kafka layering)
- Total rules generated: 33 (unchanged)
- Coverage rate: N/A
- **Critical Gaps**: A single coverage gap remains. **REGR-09**: every one of the 14 remaining violations is the same shape — `server.quota.{ClientQuotaManager, ControllerMutationQuotaManager}` (Core, after REGR-07's reclassification in Round 4) takes `org.apache.kafka.network.Session` (Server) as a parameter. This is a knock-on consequence of the REGR-07 reclassification: moving `server.quota` to Core was correct for the SPI/enum sub-set, but the same package also contains runtime quota managers that genuinely consume Server-layer types.
- Overall verdict: `FAIL` — but **one `ignoreDependency` clause away from green**.

**Trajectory across rounds**:
| Round | Failing tests | Total violations | New issues |
|-------|---------------|------------------|------------|
| 1 | 9 / 24 | 2,213 | 18 |
| 2 | 4 / 32 | 1,071 | 11 |
| 3 | 2 / 33 | 209 | 12 |
| 4 | 1 / 33 | 86 | 6 |
| **5** | **1 / 33** | **14** | **2** |

The remaining failure is narrow, local, and architecturally expected: the strict layered model continues to disagree with Kafka's reality that runtime managers in a "low" package legitimately consume runtime context from a "high" package. The fix is a one-liner.

---

## Findings

### REGR-09 — `server.quota → network.Session` (Core → Server, knock-on of REGR-07)

```
[REGR-09] SEVERITY: HIGH
Category: Coverage Gap (knock-on of Round-4 REGR-07 reclassification)
Affected Rule / Constraint: kafka_layered_architecture
```

**What is wrong**:
Round 4's REGR-07 fix moved `org.apache.kafka.server.quota..` from Server to Core because the package's *public* surface (`ClientQuotaCallback` SPI, `QuotaType` value enum) was being legitimately consumed by Core (`server.log.remote.storage`) and Consensus (`metadata.publisher`). That move was correct for those classes.

But the **same package** also contains the runtime quota managers (`ClientQuotaManager`, `ControllerMutationQuotaManager`, `ReplicationQuotaManager`, `ThrottledChannel`, etc.), and several of their methods take `org.apache.kafka.network.Session` as a parameter for principal-based throttling. `network` is — correctly — in SERVER. So we now have Core → Server.

Concrete callers (all 14 violations from surefire):

| Source class | Method | Target |
|--------------|--------|--------|
| `ClientQuotaManager` | `getOrCreateQuotaSensors(Session, String)` | reads `Session.principal`, `Session.sanitizedUser`; param type `Session` |
| `ClientQuotaManager` | `maxValueInQuotaWindow(Session, String)` | param type `Session` |
| `ClientQuotaManager` | `maybeRecordAndGetThrottleTimeMs(Session, String, double, long)` | param type `Session` |
| `ClientQuotaManager` | `recordAndGetThrottleTimeMs(Session, String, double, long)` | param type `Session` |
| `ClientQuotaManager` | `recordNoThrottle(Session, String, double)` | param type `Session` |
| `ClientQuotaManager` | `throttle(String, Session, ThrottleCallback, int)` | param type `Session` |
| `ClientQuotaManager` | `unrecordQuotaSensor(Session, String, double, long)` | param type `Session` |
| `ControllerMutationQuotaManager` | `newPermissiveQuotaFor(Session, String)` | param type `Session` |
| `ControllerMutationQuotaManager` | `newQuotaFor(Session, RequestHeader, short)` | param type `Session` |
| `ControllerMutationQuotaManager` | `newStrictQuotaFor(Session, String)` and overload | param type `Session` |
| `ControllerMutationQuotaManager` | `recordAndGetThrottleTimeMs(Session, String, double, long)` | param type `Session` |

**Why it matters**:
This is the *only* remaining violation pattern, and it accounts for all 14 of the 14 layered violations. Every other Round-4 fix is confirmed working (POS-03 below).

`network.Session` is a fundamental authentication context — name, principal, sanitized user, listener. Quota managers genuinely need it: throttling decisions are per-principal. Three architecturally honest options:

1. **Carve out the dependency** (smallest change, recommended). `Session` is a tiny value-style type that is genuinely cross-cutting; admitting a single Core → `network.Session` exception is the local minimum.
2. **Move `network.Session` (and friends) to Core** (correct semantically, large blast radius). `Session`, `BrokerEndPoint`, `EndpointReadyFutures` are SPI/value types that arguably belong with the rest of `server.network` (already Core in Round 3). But `network` *also* contains `SocketServer`, the NIO multiplexer, which is unambiguously Server runtime — splitting the package is a real refactor.
3. **Re-split `server.quota`** into a Core-side SPI/enum sub-package and a Server-side runtime sub-package. Same packaging refactor problem as option 2, just on the other side of the dependency.

Option 1 is the right call given the test is otherwise green and option 2/3 are upstream Kafka refactors.

**How to fix it**:
Add one `ignoreDependency` clause to `kafka_layered_architecture`:

```java
// REGR-09: server.quota runtime managers (ClientQuotaManager,
// ControllerMutationQuotaManager) consume network.Session for principal-based
// throttling. Session is a small authentication-context value type that is
// genuinely cross-cutting. Knock-on of Round-4 REGR-07's reclassification of
// server.quota from Server to Core.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.quota.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.network.."))
```

If you want this maximally tight (only the actual `Session` type, not the rest of `network`), use:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.quota.."),
    DescribedPredicate.describe(
        "is org.apache.kafka.network.Session (authentication context)",
        (JavaClass c) -> c.getName().equals("org.apache.kafka.network.Session")))
```

The narrow form is preferred — it leaves the door open to catch a future `ClientQuotaManager → SocketServer` regression, which would be a real architectural defect.

---

### POS-03 — Confirmed successful Round-4 fixes

```
[POS-03] SEVERITY: (informational)
```

All five Round-4 fixes are verified in the Round-5 surefire output (zero matching violations for any of them):

- **REGR-05** (`server.log.remote.quota` moved Server → Core): zero `RemoteLogManager → RLMQuota*` or `RemoteLogReader → RLMQuotaManager` violations remain. Core-internal now. ✔
- **REGR-06** (`server.purgatory` kept in Core + `ignoreDependency(server.purgatory.. → storage..)` carve-out): zero `DelayedRemoteFetch` / `DelayedRemoteListOffsets` / `ListOffsetsPartitionStatus → storage.internals.log.*` violations remain. ✔
- **REGR-07** (`server.quota` moved Server → Core): the SPI/enum consumers (`metadata.publisher.DynamicTopicClusterQuotaPublisher → ClientQuotaCallback`, `RemoteLogManager → QuotaType.RLM_*`) are all silent. The package's runtime managers' coupling to `network.Session` was *not* covered, hence REGR-09 — but REGR-07 itself is working as intended. ✔
- **REGR-08** (`server.log.remote..` glob narrowed to bare `server.log.remote` + carve-out for `TopicPartitionLog → storage..`): zero `TopicPartitionLog.unifiedLog()` violation. The bare-package source predicate correctly scoped the carve-out to `TopicPartitionLog` only without leaking to `server.log.remote.storage` or `server.log.remote.quota`. ✔
- **MOD-05-WIDEN** (three new `server.config → {network, raft, server}` clauses): zero `AbstractKafkaConfig.<clinit>` or `DynamicBrokerConfig.<clinit>` violations remain. ✔

Of the **23 cumulative `ignoreDependency`/layer-routing fixes** applied across Rounds 2–4, **22 are now verified silent** in surefire. Only one new pattern surfaced (REGR-09), and it is a direct knock-on of one of those 22 fixes rather than a new architectural blind spot.

Coverage-guard rule (`every_production_class_must_be_in_a_layer`) also passes — no orphan packages were introduced by the Round-4 layer rebalancing. ✔

---

## Recommended Patch (consolidated)

A single clause addition. No structural changes required.

```java
// Add to kafka_layered_architecture's ignoreDependency block:

// REGR-09: server.quota runtime managers (ClientQuotaManager,
// ControllerMutationQuotaManager) consume network.Session for principal-based
// throttling. Knock-on of Round-4 REGR-07 reclassification of server.quota
// from Server to Core. Use the narrow form to keep the door open to catching
// a future Core -> network.SocketServer regression.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.quota.."),
    DescribedPredicate.describe(
        "is org.apache.kafka.network.Session (authentication context value type)",
        (JavaClass c) -> c.getName().equals("org.apache.kafka.network.Session")))
```

**Expected outcome after applying**: 33 / 33 tests passing, 0 violations. End of the iteration.

---

## Severity Tally

| Severity | Count | IDs |
|----------|-------|-----|
| HIGH | 1 | REGR-09 |
| Informational | 1 | POS-03 |
| **Total** | **2** | |

---

## Closing Note

This is the cleanest review of the series and almost certainly the last that will produce a substantive finding. Five rounds in, the suite has converged: a five-layer architecture inferred from package naming + 19 documented `ignoreDependency` carve-outs for SPI inversions, shared DTOs, declarative-config aggregation, metrics observation, and a handful of small cross-package reuses. The Round-5 failure is a textbook example of why iterative architecture testing works — Round 4 made a structurally correct re-classification (`server.quota` → Core), and the next test run revealed a single missed implication (the runtime managers' `network.Session` parameter), which is a one-liner to fix.

Once the REGR-09 clause is added, the suite will be:

- 33 ArchTest rules, 0 violations
- 5 layers explicitly defined, all production packages covered by the every-class guard
- 19 documented `ignoreDependency` clauses (all justified by name and pattern in the file's header)
- 1 custom `ImportOption` for JMH/Trogdor exclusion
- 1 custom `ArchCondition` for diagnostic-friendly orphan reporting

That is a **useful** suite — failures will mean either a new orphan package (caught by the guard, with a clear "add to XXX_PACKAGES" message) or a new cross-layer pattern not yet enumerated (caught by the layered rule, with a specific source/target pair). For an architecture testing exercise on a codebase with no first-party architecture documentation and pervasive KIP-driven cross-layer patterns, that is about as good as a `layeredArchitecture()`-based approach can be.

If a future iteration adds a sixth review, the most productive thing it could do is *not* find more `ignoreDependency` candidates but instead question whether the five-layer model is the right shape at all. A slice-based "no cycles between top-level packages" + "no dependency from package P to package Q for known-bad pairs" model would carry less ceremony and probably give clearer signals on the remaining edge cases. That is a redesign, not a fix; flag for the next exercise.
