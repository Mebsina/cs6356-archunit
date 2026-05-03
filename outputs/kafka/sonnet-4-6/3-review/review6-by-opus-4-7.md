# Adversarial Architecture Review #6 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: sonnet-4-6
**Reviewer Model**: opus-4-7
**Round**: 6 (after fixes from Review #5 — suite is now green)
**Source under review**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`
**Surefire report**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`

- **Violations**: 0 / 33 failing tests (0 total) — **all green**
- **Issues**: 6

---

## Executive Summary

The suite finally passes: **33 tests, 0 failures, 0 errors, 6.7s elapsed**. Every layer is enumerated, every production package has a layer, and every cross-layer dependency that fires is documented in an `ignoreDependency` clause.

That changes the review's purpose. Up to Round 5 the question was *"what real violations is the test missing?"*. From Round 6 onwards the question is *"what real violations would the test let through tomorrow?"*. With 19 `ignoreDependency` clauses accumulated over five rounds, several carve-outs are now wide enough that they suppress not just the originally-targeted dependencies but entire classes of future regressions.

- Total documented constraints identified: 0 (no upstream architecture doc; suite remains an inference)
- Total rules generated: 33 (unchanged)
- All rules pass (LAY guard, layered rule, 31 fine-grained intra-/cross-layer rules)
- **Critical Gaps** (rules that pass today but would silently allow real defects tomorrow):
  - **OVR-01 (HIGH)**: `MOD-01 server.. → clients..` and `MOD-09 common.. → clients..` are package-to-package wildcards. Originally added to suppress 4–8 specific outbound-RPC / shared-DTO call sites; as written they exempt **any** future `server.*` → `clients.*` or `common.*` → `clients.*` dependency, including pulling Streams or Connect into broker code.
  - **VAC-NEW-02 (MEDIUM)**: `controller_must_not_depend_on_coordinator` and the `coordinator..` target on `network_must_not_depend_on_server_runtime` are vacuous (`coordinator..` is empty in the scan). They neither pass-via-test nor are marked `FUTURE-PROOFING ONLY`. A reader will reasonably believe they are enforcing something they aren't.
  - **PREC-01 (LOW)**: `MOD-06`'s `startsWith("org.apache.kafka.image")` predicate would also match a hypothetical `org.apache.kafka.imagery` package — string-prefix without a dot anchor.
  - **PREC-02 (LOW)**: `MAP-03`'s "ConfigRepository (top-level SAM only)" predicate actually matches *any* top-level `org.apache.kafka.metadata.X` class, not specifically `ConfigRepository`. Description and behavior diverge.
- Overall verdict: `PASS WITH WARNINGS`. The suite is correct as a regression detector for the violations it currently sees, but several carve-outs need narrowing before the test becomes load-bearing in CI.

**Trajectory across rounds**:

| Round | Failing tests | Total violations | New issues |
|-------|---------------|------------------|------------|
| 1 | 9 / 24 | 2,213 | 18 |
| 2 | 4 / 32 | 1,071 | 11 |
| 3 | 2 / 33 | 209 | 12 |
| 4 | 1 / 33 | 86 | 6 |
| 5 | 1 / 33 | 14 | 2 |
| **6** | **0 / 33** | **0** | **6 (precision/maintainability)** |

---

## Findings

### OVR-01 — `MOD-01 server.. → clients..` is a package-to-package wildcard

```
[OVR-01] SEVERITY: HIGH
Category: Overly Broad ignoreDependency
Affected Rule / Constraint: kafka_layered_architecture (MOD-01 server -> clients carve-out, lines 320-322)
```

**What is wrong**:
The carve-out reads:
```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
```
The Round-2 rationale was *"broker initiates outbound RPCs to other brokers using the client library"* — meaning specifically the broker runtime depending on `KafkaClient`, `NetworkClient`, `NodeApiVersions`, and a handful of related types in `clients.*`.

As written, the predicate suppresses **every** dependency from any class anywhere under `org.apache.kafka.server..` (which now includes 24 sub-packages: `server.common`, `server.util`, `server.metrics`, `server.controller`, `server.partition`, `server.replica`, `server.purgatory`, `server.quota`, etc.) to **any** class anywhere under `org.apache.kafka.clients..` (which includes `clients.admin`, `clients.consumer`, `clients.producer`, `clients.consumer.internals`, etc.).

In particular, this carve-out silently allows:
- `server.replica.Replica` → `clients.consumer.ConsumerRecord` (would never be intentional)
- `server.controller.ControllerRegistrationManager` → `clients.producer.KafkaProducer` (would be a structural bug)
- Any future broker class importing any future `clients.*` class

**Why it matters**:
The original `MOD-01` justification covers about 15–20 known call sites (broker → `KafkaClient`, broker → `NetworkClient`, broker → `NodeApiVersions`, broker → `Metadata`). A targeted `DescribedPredicate` would still let those pass while preserving detection of unintended new server → clients dependencies. The current form means the rule has effectively "given up" on that direction entirely.

**How to fix it**:
Narrow the target to the specific outbound-RPC types:

```java
// MOD-01 (narrowed): broker outbound RPC uses a documented set of clients types.
// Listing them explicitly preserves detection of e.g. server.replica -> clients.consumer.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.."),
    DescribedPredicate.describe(
        "is a documented broker-outbound-RPC type in clients",
        (JavaClass c) -> {
            String n = c.getName();
            return n.equals("org.apache.kafka.clients.KafkaClient")
                || n.startsWith("org.apache.kafka.clients.KafkaClient$")
                || n.equals("org.apache.kafka.clients.NetworkClient")
                || n.startsWith("org.apache.kafka.clients.NetworkClient$")
                || n.equals("org.apache.kafka.clients.NodeApiVersions")
                || n.equals("org.apache.kafka.clients.ClientResponse")
                || n.equals("org.apache.kafka.clients.ClientRequest")
                || n.equals("org.apache.kafka.clients.RequestCompletionHandler")
                || n.equals("org.apache.kafka.clients.Metadata")
                || n.startsWith("org.apache.kafka.clients.Metadata$")
                || n.equals("org.apache.kafka.clients.MetadataUpdater")
                || n.equals("org.apache.kafka.clients.ApiVersions")
                || n.equals("org.apache.kafka.clients.ManualMetadataUpdater")
                || n.equals("org.apache.kafka.clients.CommonClientConfigs")
                || n.startsWith("org.apache.kafka.clients.CommonClientConfigs$");
        }))
```

If listing every type by hand is too brittle, a middle-ground compromise is to scope by *source sub-package* — only the broker-runtime sub-packages are intended to do outbound RPC, not e.g. `server.metrics` or `server.replica`:

```java
.ignoreDependency(
    DescribedPredicate.describe(
        "is in the broker runtime sub-packages that perform outbound RPC",
        (JavaClass c) -> {
            String pkg = c.getPackageName();
            return pkg.equals("org.apache.kafka.server")
                || pkg.startsWith("org.apache.kafka.server.share.")
                || pkg.startsWith("org.apache.kafka.server.transaction.")
                || pkg.startsWith("org.apache.kafka.server.controller.")
                || pkg.startsWith("org.apache.kafka.controller.")
                || pkg.startsWith("org.apache.kafka.network.");
        }),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
```

Either form is materially better than the current wildcard. Pick one.

---

### OVR-02 — `MOD-09 common.. → clients..` is a package-to-package wildcard

```
[OVR-02] SEVERITY: HIGH
Category: Overly Broad ignoreDependency
Affected Rule / Constraint: kafka_layered_architecture (MOD-09 carve-out, lines 374-376)
```

**What is wrong**:
The carve-out (which subsumed the earlier MOD-02 `common → clients.admin..` clause) reads:
```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.common.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
```
The original Round-3 rationale named four specific classes: `common.MessageFormatter`, `common.requests.ApiVersionsResponse`, `common.requests.ShareFetchRequest`, `common.security.authenticator.SaslClientAuthenticator`. Plus the inherited MOD-02 surface (admin DTOs).

As written, the carve-out suppresses **every** dependency from any class under `common..` (the largest package in Kafka — types like `Cluster`, `TopicPartition`, every wire-protocol class) to any class under `clients..`. The architectural intent that "clients depends on common, not the reverse" is no longer enforced anywhere.

**Why it matters**:
`common` is the foundational utility layer. Allowing it to read arbitrary `clients.*` types means a future regression where, say, `common.Node` accidentally references `clients.NetworkClient` will pass silently. That is exactly the cycle the layer model is trying to prevent.

**How to fix it**:
Two targeted clauses replace the one wildcard:

```java
// MOD-09 (narrowed, part 1): clients.admin enums used as wire-format primitives
// (subsumes MOD-02's common -> clients.admin clause).
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.common.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))

// MOD-09 (narrowed, part 2): four documented common -> clients (non-admin)
// classes. If a fifth crosses, it will fire and require a deliberate decision.
.ignoreDependency(
    DescribedPredicate.describe(
        "is one of the four common.* classes that share types with clients",
        (JavaClass c) -> {
            String n = c.getName();
            return n.equals("org.apache.kafka.common.MessageFormatter")
                || n.equals("org.apache.kafka.common.requests.ApiVersionsResponse")
                || n.startsWith("org.apache.kafka.common.requests.ApiVersionsResponse$")
                || n.equals("org.apache.kafka.common.requests.ShareFetchRequest")
                || n.startsWith("org.apache.kafka.common.requests.ShareFetchRequest$")
                || n.equals("org.apache.kafka.common.security.authenticator.SaslClientAuthenticator");
        }),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
```

This preserves the four legitimate exceptions and keeps the rule load-bearing for everything else.

---

### VAC-NEW-02 — `coordinator..` is empty; rules referring to it are vacuous and unmarked

```
[VAC-NEW-02] SEVERITY: MEDIUM
Category: Vacuous Rule / Misleading Documentation
Affected Rule / Constraint: controller_must_not_depend_on_coordinator (lines 651-658), network_must_not_depend_on_server_runtime (line 680)
```

**What is wrong**:
The file's own comment at the top of the intra-Server section (line 645-647) acknowledges:
> *"`coordinator_must_not_depend_on_controller` is absent because `org.apache.kafka.coordinator..` contains no classes in the scanned classpath (`failOnEmptyShould` would fail the rule with an empty subject set)."*

But two rules **still target** `org.apache.kafka.coordinator..`:

1. `controller_must_not_depend_on_coordinator` (line 651-658) — passes vacuously because no class in `coordinator..` exists for `controller..` to depend on. The rule's `because()` clause uses definite voice: *"Importing coordinator types would conflate cluster management with group coordination..."* — implying current enforcement, not future-proofing.
2. `network_must_not_depend_on_server_runtime` (line 680) lists `org.apache.kafka.coordinator..` as one of five `should().dependOnClassesThat().resideInAnyPackage(...)` targets. The other four are populated, so the rule is functional overall — but the `coordinator..` entry contributes nothing.

**Why it matters**:
A reader auditing the suite reasonably believes both targets enforce real constraints today. They do not. If `org.apache.kafka.coordinator..` is added in a future Kafka release without the maintainer noticing, both targets silently start "working" for the first time — possibly producing surprising failures, or possibly *not* producing failures the reader expected.

This is the same defect class as `streams_must_not_depend_on_tools` etc. (which **are** explicitly marked `FUTURE-PROOFING ONLY` in their `because()` clauses, per Round-2 COV-NEW-01/TRANS-NEW-01). The convention exists; these two rules just didn't get it.

**How to fix it**:
Two options. **Recommended is option B** (mark, don't delete) — vacuous-today rules become useful the moment the empty package is populated.

**Option A — delete `controller_must_not_depend_on_coordinator` and remove `coordinator..` from the network rule's target list**:
```java
// Delete the rule entirely. Update the section header comment to match.

// In network_must_not_depend_on_server_runtime, remove:
// "org.apache.kafka.coordinator.."
```

**Option B (recommended) — keep both, label honestly**:
```java
@ArchTest
public static final ArchRule controller_must_not_depend_on_coordinator =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.controller..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
        .because("Inferred from package naming. FUTURE-PROOFING ONLY: org.apache.kafka.coordinator" +
                 " is empty in the current scanned classpath, so this rule is vacuous today." +
                 " It guards against a future regression where group-coordinator classes are added" +
                 " under coordinator.. and controller starts depending on them.");

// And in network_must_not_depend_on_server_runtime, add an inline comment:
.should().dependOnClassesThat().resideInAnyPackage(
    "org.apache.kafka.server",
    "org.apache.kafka.server.share..",
    "org.apache.kafka.server.transaction..",
    "org.apache.kafka.controller..",
    "org.apache.kafka.coordinator.."  // FUTURE-PROOFING ONLY: empty in current scan
)
```

---

### PREC-01 — `MOD-06` package-prefix predicate is unanchored

```
[PREC-01] SEVERITY: LOW
Category: Predicate Precision
Affected Rule / Constraint: kafka_layered_architecture (MOD-06, lines 354-360)
```

**What is wrong**:
```java
DescribedPredicate.describe(
    "is image / metadata / controller (metrics observation targets)",
    (JavaClass c) -> c.getPackageName().startsWith("org.apache.kafka.image")
                 || c.getPackageName().startsWith("org.apache.kafka.metadata")
                 || c.getPackageName().startsWith("org.apache.kafka.controller"))
```
String-prefix without trailing-dot anchoring would also match hypothetical packages like `org.apache.kafka.imagery`, `org.apache.kafka.metadataX`, `org.apache.kafka.controllerExt`. None of these exist in Kafka today, so the bug is latent — but it's a textbook string-prefix gotcha.

**Why it matters**:
Vanishingly small risk in practice. Worth a one-line fix because the cost is zero and the fix removes a class of bug.

**How to fix it**:
Anchor each prefix with a trailing dot, plus an exact match for the bare package:

```java
DescribedPredicate.describe(
    "is in image / metadata / controller (metrics observation targets)",
    (JavaClass c) -> {
        String p = c.getPackageName();
        return p.equals("org.apache.kafka.image")     || p.startsWith("org.apache.kafka.image.")
            || p.equals("org.apache.kafka.metadata")  || p.startsWith("org.apache.kafka.metadata.")
            || p.equals("org.apache.kafka.controller")|| p.startsWith("org.apache.kafka.controller.");
    })
```

Equivalent ArchUnit-idiomatic form:
```java
JavaClass.Predicates.resideInAnyPackage(
    "org.apache.kafka.image..",
    "org.apache.kafka.metadata..",
    "org.apache.kafka.controller..")
```
The latter is preferable — it uses the framework's own glob semantics and removes the hand-written predicate entirely.

---

### PREC-02 — `MAP-03 ConfigRepository` predicate matches more than ConfigRepository

```
[PREC-02] SEVERITY: LOW
Category: Predicate Precision / Misleading Description
Affected Rule / Constraint: kafka_layered_architecture (MAP-03 second clause, lines 305-310)
```

**What is wrong**:
```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    DescribedPredicate.describe(
        "is org.apache.kafka.metadata.ConfigRepository (top-level SAM only)",
        (JavaClass c) -> c.getName().startsWith("org.apache.kafka.metadata.")
            && !c.getName().substring("org.apache.kafka.metadata.".length()).contains(".")))
```
The predicate's actual semantics: *"any class whose fully-qualified name starts with `org.apache.kafka.metadata.` and contains no further dots"*. This matches **any** top-level class in the `org.apache.kafka.metadata` package, not specifically `ConfigRepository`.

In practice today the only Kafka top-level `org.apache.kafka.metadata.X` types include `ConfigRepository`, `KafkaConfigSchema`, `MetadataCache`, `MockConfigRepository`, `Replica`, `ScramCredentialData`, `TopicIdPartition`, `VersionRange`, etc. Several of those are real types that storage might or might not legitimately depend on; the carve-out currently exempts all of them indiscriminately.

The description claims "ConfigRepository (top-level SAM only)". That's wrong — the predicate matches ~10 top-level metadata classes.

**Why it matters**:
1. **Correctness drift**: a future maintainer reading the predicate description believes only `ConfigRepository` is exempt. They will be surprised when other `metadata.*` top-level classes also pass.
2. **Documentation accuracy**: the LAY-NEW-02 comment a few lines above explicitly cautions *"Do NOT change to `metadata..` as that would suppress all storage→metadata violations"*. The implicit promise is that the current form is tighter than `metadata..`. It is — but only by excluding sub-packages, not by being class-specific.

**How to fix it**:
Either (a) match the exact class name, or (b) update the description to describe what the predicate actually matches.

**Option A (recommended) — class-name-exact match**:
```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    DescribedPredicate.describe(
        "is org.apache.kafka.metadata.ConfigRepository",
        (JavaClass c) -> c.getName().equals("org.apache.kafka.metadata.ConfigRepository")))
```

**Option B — accurate description for current behavior**:
```java
DescribedPredicate.describe(
    "is any top-level class directly in the org.apache.kafka.metadata package" +
    " (not in any sub-package). Today this set is dominated by ConfigRepository" +
    " — the bootstrap SAM that storage depends on — but also includes any other" +
    " top-level metadata class (KafkaConfigSchema, MetadataCache, Replica, etc.).",
    (JavaClass c) -> c.getName().startsWith("org.apache.kafka.metadata.")
        && !c.getName().substring("org.apache.kafka.metadata.".length()).contains("."))
```

Option A matches the original architectural intent. Option B is more honest if you intentionally want all top-level metadata classes exempt.

---

### POS-04 — Confirmed end-state of the iteration

```
[POS-04] SEVERITY: (informational)
```

After six rounds, the suite has reached a stable green state:

- **Test outcome**: 33 ArchTest rules, 0 failures, 0 errors, 6.7s runtime.
- **Layer coverage**: 5 layers (Core, Storage, Consensus, Server, API) cover every production package; the every-class guard rule reports 0 orphans.
- **Documented carve-outs**: 19 `ignoreDependency` clauses on the layered rule, each labelled with the finding ID (MAP-03, MOD-01..10, MOD-05-WIDEN, FP-AUTH-01, REGR-06, REGR-08, REGR-09) that introduced it.
- **Intra-/cross-layer rules**: 31 fine-grained `noClasses()` rules across the API, Server, Consensus, and Core layers. 8 are explicitly marked `FUTURE-PROOFING ONLY`; the rest catch real things.
- **Tooling**: 1 custom `ImportOption` (TrogdorAndJmhExclusion); 1 custom `ArchCondition` for diagnostic-friendly orphan reporting.
- **Honest disclosure**: every rule's `because()` clause discloses inference vs. documented status (REA-NEW-01).
- **Round-5 fix (REGR-09)**: confirmed — zero `server.quota → network.Session` violations remain.

The suite is now usable as a regression detector for the constraints it currently encodes, modulo the precision improvements in OVR-01/OVR-02 above.

---

## Recommended Patch (consolidated)

The suite passes today; these patches improve precision and documentation accuracy without changing pass/fail status.

```java
// =====================================================================
// OVR-01: narrow MOD-01 server -> clients to the documented outbound-RPC types.
// (See finding for the recommended source-package narrowing alternative.)
// =====================================================================
// Replace lines 320-322:
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.."),
    DescribedPredicate.describe(
        "is a documented broker-outbound-RPC type in clients",
        (JavaClass c) -> {
            String n = c.getName();
            return n.equals("org.apache.kafka.clients.KafkaClient")
                || n.startsWith("org.apache.kafka.clients.KafkaClient$")
                || n.equals("org.apache.kafka.clients.NetworkClient")
                || n.startsWith("org.apache.kafka.clients.NetworkClient$")
                || n.equals("org.apache.kafka.clients.NodeApiVersions")
                || n.equals("org.apache.kafka.clients.ClientResponse")
                || n.equals("org.apache.kafka.clients.ClientRequest")
                || n.equals("org.apache.kafka.clients.RequestCompletionHandler")
                || n.equals("org.apache.kafka.clients.Metadata")
                || n.startsWith("org.apache.kafka.clients.Metadata$")
                || n.equals("org.apache.kafka.clients.MetadataUpdater")
                || n.equals("org.apache.kafka.clients.ApiVersions")
                || n.equals("org.apache.kafka.clients.ManualMetadataUpdater")
                || n.equals("org.apache.kafka.clients.CommonClientConfigs")
                || n.startsWith("org.apache.kafka.clients.CommonClientConfigs$");
        }))

// =====================================================================
// OVR-02: split MOD-09 into the inherited MOD-02 admin clause + a
// narrow common-class predicate for the four documented exceptions.
// =====================================================================
// Replace lines 374-376 with TWO clauses:
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.common.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.admin.."))
.ignoreDependency(
    DescribedPredicate.describe(
        "is one of four common.* classes that share types with clients",
        (JavaClass c) -> {
            String n = c.getName();
            return n.equals("org.apache.kafka.common.MessageFormatter")
                || n.equals("org.apache.kafka.common.requests.ApiVersionsResponse")
                || n.startsWith("org.apache.kafka.common.requests.ApiVersionsResponse$")
                || n.equals("org.apache.kafka.common.requests.ShareFetchRequest")
                || n.startsWith("org.apache.kafka.common.requests.ShareFetchRequest$")
                || n.equals("org.apache.kafka.common.security.authenticator.SaslClientAuthenticator");
        }),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))

// =====================================================================
// VAC-NEW-02: mark the two coordinator-related rules as FUTURE-PROOFING ONLY.
// =====================================================================
// Replace because() of controller_must_not_depend_on_coordinator (line 655-658):
.because("Inferred from package naming. FUTURE-PROOFING ONLY: org.apache.kafka.coordinator" +
         " is empty in the current scanned classpath, so this rule is vacuous today." +
         " It guards against a future regression where group-coordinator classes are added" +
         " and controller starts depending on them.");

// In network_must_not_depend_on_server_runtime, add a comment next to the empty target:
"org.apache.kafka.coordinator.."  // FUTURE-PROOFING ONLY: empty in current scan

// =====================================================================
// PREC-01: replace MOD-06 hand-written prefix predicate with idiomatic glob.
// =====================================================================
// Replace lines 354-360:
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.metrics.."),
    JavaClass.Predicates.resideInAnyPackage(
        "org.apache.kafka.image..",
        "org.apache.kafka.metadata..",
        "org.apache.kafka.controller.."))

// =====================================================================
// PREC-02: tighten MAP-03 ConfigRepository predicate to literal class name.
// =====================================================================
// Replace lines 305-310:
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    DescribedPredicate.describe(
        "is org.apache.kafka.metadata.ConfigRepository",
        (JavaClass c) -> c.getName().equals("org.apache.kafka.metadata.ConfigRepository")))
```

**Expected outcome after applying**: still 33 / 33 passing, 0 violations. None of these patches change the pass/fail outcome of any current dependency. They reduce the carve-out surface area so that *future* unintended dependencies will fire instead of being silently allowed.

---

## Severity Tally

| Severity | Count | IDs |
|----------|-------|-----|
| HIGH | 2 | OVR-01, OVR-02 |
| MEDIUM | 1 | VAC-NEW-02 |
| LOW | 2 | PREC-01, PREC-02 |
| Informational | 1 | POS-04 |
| **Total** | **6** | |

---

## Closing Note — End of the Review Series?

The suite is green. The Round-6 findings are not regressions; they are precision improvements that make the green outcome more meaningful. Whether to ship a Round-7 cycle depends on what the suite is for:

1. **If the goal is "tests must pass in CI"** — you are done. Apply the OVR-01/OVR-02/VAC-NEW-02/PREC-01/PREC-02 patches as quality-of-life cleanups and ship.

2. **If the goal is "tests must be load-bearing — they must catch architectural regressions in PR review"** — apply the OVR-01/OVR-02 patches first (the two HIGH findings). Without those, the test will silently allow `server.replica → clients.consumer` or `common.Cluster → clients.NetworkClient` regressions, both of which would be real defects.

3. **If the goal is "the suite should grow with Kafka over time"** — schedule a Round-7 review against a fresh classpath snapshot in 6–12 months. The most likely failure modes are (a) new `org.apache.kafka.X` top-level package not assigned to a layer (caught by the every-class guard), and (b) new cross-layer pattern not covered by any `ignoreDependency` (caught by the layered rule). Both are clean signals.

If you do run a Round-7, the most productive *non-fix-the-failure* work would be to attempt the alternative architecture model floated at the end of Review #5: replace `layeredArchitecture()` + 19 carve-outs with a slice-based rule set (`SlicesRuleDefinition.slices().matching("org.apache.kafka.(*)..").should().beFreeOfCycles()` plus a small list of `noClasses()` rules for the high-value pairs). For Kafka's KIP-driven topology, slices-based tests usually carry less ceremony than strict layering and produce clearer failure messages. That is a redesign, not a fix; flag for the next exercise.
