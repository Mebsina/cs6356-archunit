# Adversarial Architecture Review #8 — Apache Kafka ArchUnit Rules

**Project**: kafka
**Generator Model**: sonnet-4-6
**Reviewer Model**: opus-4-7
**Round**: 8 (after fixes from Review #7 — suite green again, this time with narrowed carve-outs intact)
**Source under review**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`
**Surefire report**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`

- **Violations**: 0 / 33 failing tests (0 total) — **all green**
- **Issues**: 4 (all maintainability / long-term durability — none are correctness defects)

---

## Executive Summary

Second consecutive green outcome. Unlike Round 6 (first-time green hiding two wildcard exemptions), Round 8 is green **with** the Round-6 precision narrowing intact and **with** the three Round-7 carve-outs (TIERED-01, SHARE-01, OVR-01-WIDEN) added on top. Every cross-layer dependency in the codebase that the layered rule fires on is now individually documented and justified.

This is the second consecutive review with no correctness findings. The remaining items are about **long-term durability** of the test as a CI artifact, not bugs in what it currently enforces.

- Total documented constraints identified: 0 (PDF still unrelated; suite remains a structured inference)
- Total rules generated: 33 (unchanged since Round 3)
- All rules pass; coverage guard reports 0 orphans
- **Critical Gaps**: None.
- Overall verdict: `PASS` — the suite is shippable. Items below are quality-of-life.

**Trajectory across rounds**:

| Round | Failing | Violations | New issues | Note |
|-------|---------|------------|------------|------|
| 1 | 9 / 24 | 2,213 | 18 | Initial generation |
| 2 | 4 / 32 | 1,071 | 11 | After Round 1 fixes |
| 3 | 2 / 33 | 209 | 12 | After Round 2 fixes |
| 4 | 1 / 33 | 86 | 6 | After Round 3 fixes |
| 5 | 1 / 33 | 14 | 2 | After Round 4 fixes |
| 6 | 0 / 33 | 0 | 6 (precision) | First-time green (with two wildcards) |
| 7 | 1 / 33 | 79 | 4 | Wildcards narrowed; real patterns surfaced |
| **8** | **0 / 33** | **0** | **4 (durability)** | Green again, this time honest |

The arc is now closed: pattern discovery (1–5) → precision attack (6) → forced documentation of hidden patterns (7) → stable, load-bearing green (8).

---

## Findings

### META-01 — 22 `ignoreDependency` clauses are inline; group them for navigability

```
[META-01] SEVERITY: MEDIUM
Category: Maintainability / Test Diagnostic Quality
Affected Rule / Constraint: kafka_layered_architecture (entire ignoreDependency block, lines 297-424)
```

**What is wrong**:
After eight rounds, the `kafka_layered_architecture` rule carries **22 documented `ignoreDependency` clauses** (counted: MAP-03 ×2, MOD-01 ×3 narrowed, MOD-02 ×2, MOD-03, MOD-04, MOD-05, MOD-05-WIDEN ×3, MOD-06, MOD-07, MOD-08, MOD-09 ×2 narrowed, MOD-10, FP-AUTH-01, REGR-06, REGR-08, REGR-09, TIERED-01, SHARE-01). They are correct, individually labelled, and span ~130 lines of fluent-API chaining.

The clauses are conceptually grouped (by finding ID prefix, semi-chronologically) but **not visually grouped** — there are no section comments separating, say, the "shared DTO" carve-outs from the "SPI inversion" ones from the "broker-as-client" ones. A new reader looking at the rule for the first time sees one wall of `.ignoreDependency(` calls.

**Why it matters**:
1. The next time a violation fires, the maintainer has to scan ~130 lines to find the relevant carve-out family.
2. New cross-layer patterns will likely fall into one of the existing categories. Without category headers, the maintainer is more likely to add an ad-hoc clause at the bottom rather than slot into the right group.
3. The header comment above the rule (lines 245-278) lists the categories but does not annotate which `.ignoreDependency` lines belong to which category — the mapping is name-based and easy to drift.

**How to fix it**:
Add inline section comments. Three suggested categories, in the order the existing clauses already roughly sit:

```java
// ─── Storage ↔ Metadata SPI/bootstrap (MAP-03) ─────────────────────────
.ignoreDependency( /* MAP-03: storage -> metadata.properties */ ...)
.ignoreDependency( /* MAP-03: storage -> metadata.ConfigRepository */ ...)

// ─── Broker-as-client / outbound RPC (MOD-01, OVR-01, TIERED-01) ───────
.ignoreDependency( /* MOD-01: server.config -> metadata SPI */ ...)
.ignoreDependency( /* MOD-01: server.log.remote.storage -> storage SPI */ ...)
.ignoreDependency( /* OVR-01 (narrowed): server -> 16 clients RPC types */ ...)
.ignoreDependency( /* MOD-01: raft -> clients KafkaNetworkChannel */ ...)
.ignoreDependency( /* MOD-01: snapshot -> raft.Batch */ ...)
.ignoreDependency( /* TIERED-01: server.log.remote.metadata.storage -> clients (KIP-405) */ ...)

// ─── Shared DTO / wire-protocol enums (MOD-02..04, MOD-09, SHARE-01) ───
.ignoreDependency( /* MOD-02: controller -> clients.admin */ ...)
.ignoreDependency( /* MOD-02: image -> clients.admin */ ...)
.ignoreDependency( /* MOD-03: metadata -> clients.admin */ ...)
.ignoreDependency( /* MOD-04: security -> clients.admin */ ...)
.ignoreDependency( /* MOD-09 part 1: common -> clients.admin */ ...)
.ignoreDependency( /* MOD-09 part 2: 4 named common.* -> clients */ ...)
.ignoreDependency( /* SHARE-01: server.share -> clients.consumer wire enums (KIP-932) */ ...)

// ─── Declarative-config aggregation (MOD-05, MOD-05-WIDEN) ─────────────
.ignoreDependency( /* MOD-05: server.config -> storage ConfigDef */ ...)
.ignoreDependency( /* MOD-05-WIDEN: server.config -> network ConfigDef */ ...)
.ignoreDependency( /* MOD-05-WIDEN: server.config -> raft ConfigDef */ ...)
.ignoreDependency( /* MOD-05-WIDEN: server.config -> server.DynamicThreadPool */ ...)

// ─── Cross-layer observation / wiring (MOD-06, MOD-07, MOD-08, MOD-10, FP-AUTH-01) ─
.ignoreDependency( /* MOD-06: server.metrics observes image/metadata/controller */ ...)
.ignoreDependency( /* MOD-07: server.util.NetworkPartitionMetadataClient -> metadata */ ...)
.ignoreDependency( /* MOD-08: storage -> generated DTOs */ ...)
.ignoreDependency( /* MOD-10: SPI factory pattern */ ...)
.ignoreDependency( /* FP-AUTH-01: metadata.authorizer -> controller */ ...)

// ─── Layer-rebalance knock-ons (REGR-06, REGR-08, REGR-09) ─────────────
.ignoreDependency( /* REGR-06: server.purgatory -> storage */ ...)
.ignoreDependency( /* REGR-08: server.log.remote (top-level) -> storage */ ...)
.ignoreDependency( /* REGR-09: server.quota -> network.Session */ ...)
```

Pure cosmetics — zero behavioral change. Worth doing while the rule is small enough to refactor cheaply.

---

### SCOPE-AUDIT-01 — TIERED-01 and SHARE-01 are the broadest remaining carve-outs; mark intentionally so

```
[SCOPE-AUDIT-01] SEVERITY: LOW
Category: Carve-out Audit / Documentation
Affected Rule / Constraint: kafka_layered_architecture (TIERED-01 and SHARE-01)
```

**What is wrong**:
Of the 22 carve-outs, two are now the broadest:

1. **TIERED-01**: `server.log.remote.metadata.storage.. → clients..` — entire-package to entire-package. By design (KIP-405), the whole sub-package is a Kafka-client consumer, so this is *justified*. But it also means a hypothetical future `server.log.remote.metadata.storage.SomeClass → clients.streams.KafkaStreams` would pass silently — a regression that would otherwise be a real architectural defect.

2. **SHARE-01**: `server.share.. → {clients.consumer, clients.consumer.internals}` — entire-package to two sub-packages. Justified by the wire-protocol enum sharing pattern, but `server.share.SomeFutureClass → clients.consumer.KafkaConsumer` (using the full client, not just an enum) would also pass.

These are explicit trade-offs the previous reviews accepted (Review #7 rationale: "no narrower exemption is meaningful because every major class in the package is a client user"; "sub-package-scoped to keep the rule readable as KIP-932 grows"). The trade-offs are sound. **The risk is forgetting they exist**: a future Round-N reviewer may try to "tighten" them without realising they were intentionally wide.

**Why it matters**:
Cosmetic, but it's the difference between "this looks like an oversight" and "this was a considered decision". Future maintainers — including future LLM reviewers — will reach for the loosest carve-outs first.

**How to fix it**:
Tag the two clauses with an explicit "INTENTIONALLY WHOLESALE" marker in the inline comment. Combined with META-01's section headers, this becomes self-documenting:

```java
// TIERED-01 — INTENTIONALLY WHOLESALE: see KIP-405. The entire
// server.log.remote.metadata.storage sub-package implements the topic-based
// remote-log-metadata store on top of the Kafka client library. Every public
// class is a Producer/Consumer/Admin user. Do not narrow this clause without
// reading KIP-405; narrowing will cascade-fail ~70 unrelated call sites.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.metadata.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))

// SHARE-01 — INTENTIONALLY WHOLESALE for clients.consumer{,.internals}: see
// KIP-932. server.share consumes the share-group wire-protocol enums
// (AcknowledgeType, ShareAcquireMode, plus future additions). The two
// clients.consumer sub-packages are exempt; clients.producer / clients.admin
// are NOT — those would still fire if accidentally pulled in.
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.share.."),
    JavaClass.Predicates.resideInAnyPackage(
        "org.apache.kafka.clients.consumer",
        "org.apache.kafka.clients.consumer.internals"))
```

---

### DOC-01 — Zero rules cite a real upstream Kafka design source; honest disclaimer is correct, but is the persistent fundamental gap

```
[DOC-01] SEVERITY: LOW
Category: Maintainability / Documentation Strength
Affected Rule / Constraint: All 33 rules (every `because()` clause)
```

**What is wrong**:
Every `because()` clause now correctly opens with *"Inferred from package naming"* and several reference KIPs by number in adjacent comments (KIP-405, KIP-500, KIP-714, KIP-899, KIP-932, KIP-714). But **no rule actually cites an upstream Kafka design document** as its primary justification. The KIP references are scaffolding-only — a maintainer wanting to verify whether `metadata → image` (REGR-02 from Round 2) or `image → metadata.publisher.*` is the *actually documented* direction must still go grep KIP-500 themselves.

This was the persistent root cause across all eight rounds: the supplied PDF (Kafka Streams runtime) is irrelevant to broader Kafka layering, so the rules have no authoritative anchor. Eight rounds of iteration have built a pragmatic consensus that matches *what is currently true in the bytecode*, but "currently true" is not the same as "intended". A KIP-driven refactor that intentionally inverts a dependency direction (e.g., a hypothetical KIP-1100 that moves `image.publisher.MetadataPublisher` to `metadata.publisher.MetadataPublisher`) would be flagged by the test as a regression — but it would actually be a *desired* change.

**Why it matters**:
This is not a defect in the test as a regression detector. It is a defect in the test as a **specification of intent**. The two are different roles:
- *Regression detector*: "this was true yesterday; alert me if it stops being true tomorrow." → ✓ The suite is fit for this.
- *Architectural specification*: "the Kafka design document says X; this test enforces X." → ✗ The suite is not fit for this; no rule cites a design document.

**How to fix it**:
This is a multi-week investment, not a code patch. The shape of the work:

1. For each of the 33 rules, identify the closest upstream design source (a KIP, `design.html` section, a public Apache Kafka mailing list discussion, or a PR description). Cite it in the `because()` clause. Examples:
   - `image_must_not_depend_on_metadata_publisher_internals` → cite KIP-500 §"Metadata Publisher" subsection.
   - `clients_must_not_depend_on_streams` → cite design.html §"Module Dependencies" or the `streams/` module's `build.gradle` exclusion.
   - `tools_must_not_depend_on_connect_except_plugin_path` → cite KIP-787 (ConnectPluginPath).

2. For rules that have *no* upstream citation (likely 5–10 of them), accept that they are pure inference and label them so explicitly in the `because()` clause: "PROJECT INFERENCE (no upstream design citation found): …".

3. For rules where the upstream documentation *contradicts* the current bytecode (likely 0–2 of them), the test should match the document, not the bytecode — and the bytecode should be filed as a Kafka bug.

This work converts the test from "a snapshot of what's true" to "a specification of what should be true". For a CS6356 deliverable that is probably out of scope; for production use it is the actual valuable next investment.

---

### POS-06 — Confirmed: all Round-7 fixes verified silent

```
[POS-06] SEVERITY: (informational)
```

All three Round-7 carve-outs are confirmed working in the Round-8 surefire (zero violations on any matching pattern):

- **TIERED-01** (`server.log.remote.metadata.storage.. → clients..`): 70 previously-firing violations are silent. ✔
- **SHARE-01** (`server.share.. → clients.consumer{,.internals}`): 8 previously-firing violations are silent. ✔
- **OVR-01-WIDEN** (`MetadataRecoveryStrategy` added): the one `NodeToControllerChannelManagerImpl.buildNetworkClient` violation is silent. ✔

Cumulative tally across all 8 rounds:
- 5 layers defined and stable
- 33 ArchTest rules
- 22 documented `ignoreDependency` clauses on the layered rule
- 1 custom `ImportOption` (TrogdorAndJmhExclusion)
- 1 custom `ArchCondition` (every-class guard with diagnostic-friendly message)
- 0 violations
- 7.8s test runtime

Round-6 precision improvements (OVR-01, OVR-02, VAC-NEW-02, PREC-01, PREC-02) are all still in effect. Round-7's three carve-outs were the last surfacing of previously-hidden patterns. The suite is in a stable equilibrium.

---

## Recommended Patch (consolidated)

All findings are quality-of-life. None are required for the test to pass. Apply if you intend the file to be a long-lived CI artifact.

```java
// META-01 + SCOPE-AUDIT-01: add five section-header comments to the
// ignoreDependency block, plus two "INTENTIONALLY WHOLESALE" markers on
// TIERED-01 and SHARE-01. (See finding text for the exact placement.)
//
// DOC-01: schedule (out-of-band) a KIP-citation pass over each rule's because()
// clause. Tag rules without an upstream citation as "PROJECT INFERENCE" rather
// than the current "Inferred from package naming" so the distinction between
// "we couldn't find a citation" and "we cited but the citation was unavailable"
// is explicit.
```

**Expected outcome after applying**: still 33 / 33 passing, 0 violations. No rule's behavior changes; only its commentary and structure improve.

---

## Severity Tally

| Severity | Count | IDs |
|----------|-------|-----|
| MEDIUM | 1 | META-01 |
| LOW | 2 | SCOPE-AUDIT-01, DOC-01 |
| Informational | 1 | POS-06 |
| **Total** | **4** | |

---

## Closing Note — End of the Useful Iteration

Eight rounds in, the marginal value of further rule-level review is approaching zero. The test:

- Catches the cross-layer dependencies it was designed to catch (verified by the deliberate Round-6 → Round-7 narrow-then-fix cycle, which surfaced three previously-hidden real patterns).
- Documents every exemption with a finding ID and an English-language rationale.
- Disclaims its inference-from-naming nature in every `because()` clause.
- Ships a custom `ArchCondition` with an actionable failure message for the one rule that's most likely to fire on a future Kafka package addition (the every-class guard).

If a Round 9 happens, the most productive uses would be:

1. **Apply META-01 + SCOPE-AUDIT-01 cosmetics**, run the test once to confirm no behavioral change, ship.
2. **Begin DOC-01** — pick the 5 most "structurally critical" rules (the `layeredArchitecture()` rule itself, the every-class guard, the three intra-API isolation rules for streams/connect/admin) and cite a real upstream source for each. Estimate ~30 minutes per rule for grep-and-cite work.
3. **Try the slices/cycle alternative model** (suggested in Reviews #5 and #6) on a separate file. Compare the failure-mode signal to the current layered model's. If clearer, swap.

For a CS6356 architecture-test exercise on a codebase with no first-party documentation, ending at "33 passing rules, 22 documented carve-outs, second consecutive zero-violation run, all hidden wildcards forced into explicit predicates" is a strong outcome. Apply the cosmetics, file the DOC-01 work as a follow-up if applicable, and ship.

Across all 8 rounds the suite went through 6 distinct shapes, each better than the last:

1. **Round 1**: 24 rules, 9 failing, 2,213 violations — generated from the wrong document.
2. **Round 2**: 32 rules, 4 failing, 1,071 violations — model-mismatch (5-layer too strict for SPI/DTO patterns) becomes visible.
3. **Round 3**: 33 rules, 2 failing, 209 violations — coverage gap regression caught (12 server.* sub-packages orphaned).
4. **Round 4**: 33 rules, 1 failing, 86 violations — four sub-package mis-routings corrected.
5. **Round 5**: 33 rules, 1 failing, 14 violations — single knock-on of the rebalance fixed.
6. **Round 6**: 33 rules, 0 failing, 0 violations (with 2 hidden wildcards).
7. **Round 7**: 33 rules, 1 failing, 79 violations — wildcards narrowed; 3 hidden patterns surface.
8. **Round 8**: 33 rules, 0 failing, 0 violations — patterns documented; suite green and load-bearing.

That's the full review-cycle arc, not just a count of bugs found. End of useful iteration; ship.
