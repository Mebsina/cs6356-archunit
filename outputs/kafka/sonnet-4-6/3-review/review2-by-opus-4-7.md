# Adversarial Review — `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache Kafka
Review: #2

---

## Executive Summary

- **Total documented constraints identified: 1 structural + 6 behavioural**
  - **C-S1 (structural):** "Kafka Streams simplifies application development by building on the Kafka producer and consumer libraries" (PDF p.1, paragraph 1). The single cross-module structural claim in the entire document — and it is a *permission* (Streams is allowed to use Clients) wrapped in language that some reviewers will read as a *prohibition* (Streams must use **only** Clients/Common).
  - **C-B1 — C-B6:** runtime/parallelism properties (stream partitions ↔ topic partitions; tasks as fixed parallelism unit; threads share no state; coordination via Kafka group protocol; local state stores backed by changelog topics; standby replicas / automatic restart). All six are dynamic/behavioural and **cannot be expressed as ArchUnit static rules** at all.

- **Total rules generated: 13** (1 `layeredArchitecture` block + 1 positive `classes()` rule + 11 `noClasses` rules).

- **Coverage rate: 1 of 1 documented structural constraints has a corresponding rule** — `streams_uses_only_kafka_client_libraries` covers C-S1. **However, that rule is mis-calibrated** and will produce widespread false positives in the real `kafka-streams` JAR (see F-03), and **the layered access matrix contradicts it** (see F-04). So the nominal 1/1 coverage is, in practice, broken.

- **Surefire result: BUILD FAILS BEFORE ANY TEST RUNS.** The Maven build cannot even read the project: `pom.xml` contains an unescaped `<module>` literal inside `<description>`, which the XML parser interprets as a start tag. Zero tests were executed. Fix #1 is therefore not "a rule failed" — it is "Maven cannot parse the POM".

- **Critical Gaps**:
  - **F-01** — `pom.xml` is non-parseable. The build halts before any `@ArchTest` is loaded; from CI's perspective every rule in the file is silently disabled.
  - **F-02** — Five top-level packages from the input package list (`org.apache.kafka.admin`, `…api`, `…config`, `…logger`, `…security`) are placed in **no layer** at all. Combined with `consideringOnlyDependenciesInLayers()`, every dependency *from* those packages to broker internals is filtered out of the layered analysis and **no `noClasses` rule covers them either**. A class in `org.apache.kafka.security.authorizer` reaching directly into `org.apache.kafka.server.metadata` would not fire any rule.
  - **F-03** — `streams_uses_only_kafka_client_libraries` uses `onlyDependOnClassesThat()` with an allow-list missing legitimate, well-known Kafka Streams runtime dependencies (Jackson, LZ4/Zstd/Snappy compression codecs, Apache Commons Collections, `org.apache.kafka.server.*` shared utilities, Java's `sun.*`/`jdk.*` reflective surfaces). Run against the real `kafka-streams-3.7.0.jar` this rule will fail with hundreds of "depends on `com.fasterxml.jackson.databind.JsonNode`" violations — none of which are real architectural problems.
  - **F-04** — The layered matrix grants `Streams.mayOnlyAccessLayers("Core", "Infrastructure", "Metadata", "Clients")` while the positive Streams rule asserts Streams may depend on **only** `common` + `clients` + third-party. Two rules in the same file disagree about whether `streams → metadata`, `streams → infrastructure` (raft/snapshot/storage/image), is allowed.
  - **F-05** — `kafka-transaction-coordinator` is declared as a Maven Central dependency, but **no such artefact exists** at coordinates `org.apache.kafka:kafka-transaction-coordinator:3.7.0`. Maven's dependency-resolution step will fail with `Could not find artifact`, breaking the build a second time even if F-01 is fixed in isolation.

- **Overall verdict:** `FAIL`

---

## Constraint-to-Rule Gap Matrix

| # | Constraint (source: PDF page) | Rule(s) covering it | Status |
|---|---|---|---|
| C-S1 | `streams → clients/common` is permitted; nothing deeper (p.1) | `streams_uses_only_kafka_client_libraries` (positive) and the layered Streams row (cross-layer) | Covered but **mis-calibrated**: positive rule too narrow (F-03), layered rule too broad (F-04) — they contradict |
| C-B1 | stream partition ↔ topic partition (p.1) | none possible (runtime, not structural) | Out of scope for ArchUnit |
| C-B2 | Tasks are units of parallelism (p.2) | none possible | Out of scope |
| C-B3 | No shared state amongst threads (p.3) | none | Out of scope |
| C-B4 | Streams leverages Kafka's coordination (p.3) | none | Out of scope |
| C-B5 | Local state stores / changelog topics (p.3-4) | none | Out of scope |
| C-B6 | Standby replicas, automatic task restart (p.4) | none | Out of scope |

| Generated rule | Source attribution in `.because()` | Honest? |
|---|---|---|
| `kafka_layered_architecture_is_respected` | "INFERRED from the standard Apache Kafka monorepo layout (KIP-500 / KIP-405 / Connect plugin isolation)" | Yes — explicitly marked INFERRED |
| `streams_uses_only_kafka_client_libraries` | "Per page 1 of the supplied Kafka Streams architecture page" | Yes — verbatim quote of C-S1 |
| 9 `*_must_not_depend_on_*` rules | "INFERRED" | Yes — all marked INFERRED |
| `tooling_must_not_depend_on_broker_internals` | "INFERRED: CLI tools are client-side processes…" | Yes |

The honesty problem flagged in review #1 (F-02 / F-10 of that report) has been fixed: nearly every `.because()` clause is now correctly labelled `INFERRED`, and the one rule that does cite the PDF (`streams_uses_only_kafka_client_libraries`) quotes the exact sentence from page 1. **That is the single substantive improvement over review #1.** Every other class of defect identified in review #1 has either been preserved or replaced with a new defect of equal severity.

### Package-coverage matrix

| Package (from `inputs/java/7_apache_kafka.txt`) | Layer | Covered? |
|---|---|---|
| `org.apache.kafka.admin` | none | **NOT COVERED** (F-02) |
| `org.apache.kafka.api` | none | **NOT COVERED** (F-02) |
| `org.apache.kafka.clients` | Clients | yes |
| `org.apache.kafka.common` | Core | yes |
| `org.apache.kafka.config` | none | **NOT COVERED** (F-02) |
| `org.apache.kafka.connect` | Connect | yes |
| `org.apache.kafka.controller` | Metadata | yes |
| `org.apache.kafka.coordinator` | Metadata | yes |
| `org.apache.kafka.deferred` | Core | yes |
| `org.apache.kafka.image` | Infrastructure | yes (but cf. F-08) |
| `org.apache.kafka.jmh` | excluded by `ImportOption` | n/a |
| `org.apache.kafka.logger` | none | **NOT COVERED** (F-02) |
| `org.apache.kafka.message` | Core | yes |
| `org.apache.kafka.metadata` | Metadata | yes |
| `org.apache.kafka.network` | Infrastructure | yes |
| `org.apache.kafka.queue` | Infrastructure | yes |
| `org.apache.kafka.raft` | Infrastructure | yes |
| `org.apache.kafka.security` | none | **NOT COVERED** (F-02) |
| `org.apache.kafka.server` | Server | yes |
| `org.apache.kafka.shell` | Tooling | yes |
| `org.apache.kafka.snapshot` | Infrastructure | yes |
| `org.apache.kafka.storage` | Infrastructure | yes |
| `org.apache.kafka.streams` | Streams | yes |
| `org.apache.kafka.test` | excluded | n/a |
| `org.apache.kafka.tiered` | excluded (per Javadoc, treated as test harness) | n/a |
| `org.apache.kafka.timeline` | Core | yes |
| `org.apache.kafka.tools` | Tooling | yes |
| `org.apache.kafka.trogdor` | Tooling | yes |

**5 of 28 production packages have no layer assignment.** All five are referenced in the input package list, so there is no excuse for omitting them.

---

## Findings

```
[F-01] SEVERITY: CRITICAL
Category: Structural Gap (build configuration)
Affected Rule / Constraint: every @ArchTest in the file (the entire suite)

What is wrong:
The Surefire report shows the build aborting at the very first phase —
project-model loading — with this fatal error:

  [FATAL] Non-parseable POM
    .../outputs/kafka/sonnet-4-6/pom.xml:
    TEXT must be immediately followed by END_TAG and not START_TAG
    (position: START_TAG seen ...g\n        Apache Kafka Gradle source
    tree (../../../kafka/<module>... @22:65)

The cause is in pom.xml line 22, inside the <description> element:

    <description>
        ... text ...
        The previous configuration relied on relative paths into a sibling
        Apache Kafka Gradle source tree (../../../kafka/<module>/build/...),
        ... text ...
    </description>

The literal "<module>" inside the description is parsed by the Maven XML
reader as a start tag, but no matching </module> end tag follows in the
allowed position. The same mistake applies to the closing-paren bracket
sequence "</...>" later in the file, but the parser dies before reaching
that. Maven exits with code 1; no Surefire run is launched; not a single
@ArchTest is loaded; from CI's point of view, every rule in the file is
silently disabled.

This is a regression introduced WHILE fixing F-01 of review #1 — the
previous version relied on additionalClasspathElements that pointed at a
non-existent sibling tree, producing 21 empty-layer failures. The new
version reaches the same end state (zero rules executed) for a different
reason: the build no longer compiles.

Why it matters:
  - The single most important contract of an ArchUnit test suite — that
    failures cause CI to fail with a useful diagnostic — is reversed:
    every architectural violation now produces a green check-mark
    accompanied by an unrelated POM-parsing error elsewhere in the
    Maven log that a tired reviewer will dismiss as "build infra noise".
  - All other findings in this report (F-02 through F-12) are about the
    QUALITY of rules that, today, do not run at all.

How to fix it:
Two equally valid options. Pick one.

(a) Wrap the description body in CDATA so the parser treats it as opaque
    text:

```xml
<description><![CDATA[
    Standalone ArchUnit test suite enforcing the documented Apache Kafka
    layer hierarchy. Pulls compiled bytecode for the org.apache.kafka.*
    modules onto the test classpath through published Maven Central
    artefacts and verifies inter-layer and intra-module dependency rules
    derived from the Kafka Streams architecture documentation.

    The previous configuration relied on relative paths into a sibling
    Apache Kafka Gradle source tree (../../../kafka/<module>/build/...),
    which silently produced an empty classpath on machines without that
    sibling tree and caused every @ArchTest to fail with "Layer X is
    empty" / "failed to check any classes". The Maven-Central dependency
    approach below removes that brittle assumption: the importer always
    sees real Kafka bytecode.
]]></description>
```

(b) Or escape every "<" and ">" inside <description> with the entity
    references &lt; and &gt;:

```xml
<description>
    Standalone ArchUnit test suite ... source tree
    (../../../kafka/&lt;module&gt;/build/...), which silently ...
</description>
```

Option (a) is cleaner because the Javadoc-style `<module>` placeholder
is preserved verbatim. Option (b) survives a copy-paste into a doc
generator that does not understand CDATA.

Whichever you choose, run `mvn validate` BEFORE committing to confirm
the POM parses. Until that succeeds, no rule in the file is being
exercised and no other finding can be empirically verified.
```

```
[F-02] SEVERITY: CRITICAL
Category: Coverage Gap / Wrong Layer
Affected Rule / Constraint: layeredArchitecture(); five top-level packages
                            with no layer assignment

What is wrong:
The package list `inputs/java/7_apache_kafka.txt` declares 28 top-level
packages under `org.apache.kafka.*`. After excluding the 3 declared as
test/benchmark/test-harness (jmh, test, tiered), 25 production packages
remain. The layeredArchitecture rule places only 20 of them in layers:

  Core           : common, message, timeline, deferred             (4)
  Infrastructure : network, queue, raft, snapshot, storage, image  (6)
  Metadata       : metadata, coordinator, controller               (3)
  Clients        : clients                                         (1)
  Streams        : streams                                         (1)
  Connect        : connect                                         (1)
  Server         : server                                          (1)
  Tooling        : tools, shell, trogdor                           (3)
                                                                  ----
                                                                   20

The five remaining packages are NOT placed in any layer:

    org.apache.kafka.admin       <-- top-level admin utilities
    org.apache.kafka.api         <-- public API contracts (some Kafka builds)
    org.apache.kafka.config      <-- top-level configuration
    org.apache.kafka.logger      <-- logging utilities
    org.apache.kafka.security    <-- authorizers, SCRAM, SASL helpers

Combined with `.consideringOnlyDependenciesInLayers()` on the layered
rule, an edge from one of these unmapped packages to ANY layer (Server,
Metadata, Infrastructure, ...) is silently filtered out of the analysis.
Specifically, ArchUnit's `LayerDependencySpecification` only evaluates
edges where BOTH source and target are members of some declared layer;
edges with an out-of-layer endpoint are dropped before the access matrix
is consulted.

Concrete uncovered scenarios:
  1. `org.apache.kafka.security.authorizer.AclAuthorizer` reaches into
     `org.apache.kafka.server.common.MetadataVersion`. Source layer:
     none. Target layer: Server. Edge: filtered out. Rule: passes.
  2. `org.apache.kafka.admin.SomeAdminCli` calls
     `org.apache.kafka.server.network.SocketServer`. Same outcome.
  3. `org.apache.kafka.config.ConfigCommand` imports
     `org.apache.kafka.server.metadata.BrokerMetadataPublisher`.
     Same outcome.

None of the 11 explicit `noClasses` rules cover these packages either:
the `that().resideInAPackage(...)` clauses target metadata, controller,
coordinator, tools, shell, trogdor — never admin, api, config, logger,
or security.

Why it matters:
This is the same defect as F-04/F-09 of review #1 (Operations layer
conflation, API/admin packages absent), repackaged. Five packages —
including the one most likely to genuinely host violation candidates
(`org.apache.kafka.security`) — are completely invisible to the rule
suite. A new authorizer in `o.a.k.security.authorizer.*` that
compile-depends on broker internals lands in CI green.

This is also a regression from review #1's F-09: the prior version
named admin and api as Layer-API members (vacuously, because the
globs matched nothing), at least putting them ON THE RADAR. The
current version drops them entirely, and adds the new omissions of
config, logger, and security — making the gap larger, not smaller.

How to fix it:
Either place the five packages into appropriate layers, or add explicit
guard rules that quarantine them. The first is preferred:

```java
.layer("Core").definedBy(
        "org.apache.kafka.common..",
        "org.apache.kafka.message..",
        "org.apache.kafka.timeline..",
        "org.apache.kafka.deferred..",
        // F-02: top-level config / logger are Core primitives
        "org.apache.kafka.config..",
        "org.apache.kafka.logger..")

.layer("Infrastructure").definedBy(
        "org.apache.kafka.network..",
        "org.apache.kafka.queue..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.storage..",
        "org.apache.kafka.image..",
        // F-02: security primitives (SASL, SCRAM, authorizer SPI)
        // belong below Server/Metadata in the dependency hierarchy.
        "org.apache.kafka.security..")

.layer("Clients").definedBy(
        "org.apache.kafka.clients..",
        // F-02: top-level admin / api are public-facing client surfaces
        "org.apache.kafka.admin..",
        "org.apache.kafka.api..")
```

In addition, add an explicit guard so a future regression that
introduces a NEW unmapped package does not silently re-create the gap:

```java
@ArchTest
static final ArchRule every_kafka_package_belongs_to_a_layer =
        classes()
                .that().resideInAPackage("org.apache.kafka..")
                .and().resideOutsideOfPackages(
                        "org.apache.kafka.jmh..",
                        "org.apache.kafka.test..",
                        "org.apache.kafka.tiered..")
                .should().resideInAnyPackage(
                        // Mirror of the layer .definedBy() patterns.
                        // Keep this list and the layered rule's globs
                        // in sync; CI fails when they drift.
                        "org.apache.kafka.common..",
                        "org.apache.kafka.message..",
                        "org.apache.kafka.timeline..",
                        "org.apache.kafka.deferred..",
                        "org.apache.kafka.config..",
                        "org.apache.kafka.logger..",
                        "org.apache.kafka.network..",
                        "org.apache.kafka.queue..",
                        "org.apache.kafka.raft..",
                        "org.apache.kafka.snapshot..",
                        "org.apache.kafka.storage..",
                        "org.apache.kafka.image..",
                        "org.apache.kafka.security..",
                        "org.apache.kafka.metadata..",
                        "org.apache.kafka.coordinator..",
                        "org.apache.kafka.controller..",
                        "org.apache.kafka.clients..",
                        "org.apache.kafka.admin..",
                        "org.apache.kafka.api..",
                        "org.apache.kafka.streams..",
                        "org.apache.kafka.connect..",
                        "org.apache.kafka.server..",
                        "org.apache.kafka.tools..",
                        "org.apache.kafka.shell..",
                        "org.apache.kafka.trogdor..")
                .allowEmptyShould(true)
                .because("Every production package must be assigned to "
                       + "exactly one layer in kafka_layered_architecture_"
                       + "is_respected. A new top-level package that is "
                       + "absent from this list will silently bypass the "
                       + "layered rule (consideringOnlyDependenciesInLayers "
                       + "filters out edges whose endpoint is in no layer).");
```
```

```
[F-03] SEVERITY: CRITICAL
Category: Overly Narrow / False Positive
Affected Rule / Constraint: streams_uses_only_kafka_client_libraries

What is wrong:
The rule is the only PDF-grounded rule in the file:

    classes()
        .that().resideInAPackage("org.apache.kafka.streams..")
        .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                "java..", "javax..",
                "org.slf4j..", "org.rocksdb..",
                "org.apache.kafka.common..",
                "org.apache.kafka.clients..",
                "org.apache.kafka.streams..");

The intent — encoding the C-S1 sentence "Kafka Streams … builds on the
Kafka producer and consumer libraries" — is correct. The execution is
broken. `onlyDependOnClassesThat()` is a TOTAL constraint: every class
referenced by every Streams class (return types, fields, parameters,
annotations, generic bounds, exception types, …) must be in the allow
list. The list is missing several legitimate, well-known Streams
runtime dependencies of the published `kafka-streams-3.7.0.jar`:

  Missing third-party allow-list entries:
    com.fasterxml.jackson..      Jackson is used by Streams' RocksDB
                                 metric collection, the StateRestore
                                 listener config serialisation, and
                                 several internal config helpers.
    com.github.luben.zstd..      Zstd codec; selected via clients but
                                 referenced from Streams' compression
                                 utilities in some configs.
    org.xerial.snappy..          Snappy codec; same situation.
    net.jpountz.lz4..            LZ4 codec; same.
    org.apache.commons..         (apache commons-lang/collections, used
                                 in older Streams versions; check
                                 module-by-module)

  Missing first-party allow-list entries:
    org.apache.kafka.server..    The published kafka-streams jar uses
                                 a handful of org.apache.kafka.server.*
                                 utility types (e.g. server.metrics
                                 measurables, server.policy.*). These
                                 references survive even though
                                 kafka-streams is "client-side" because
                                 Server hosts the public ClientMetrics /
                                 BrokerCompatibility SPIs.

  Likely-not-needed but worth noting:
    sun.misc.Unsafe              Used by some compression / hashing
                                 paths transitively. (Streams itself
                                 should not directly reference sun.*.)

When this rule runs against the real kafka-streams JAR, the allow-list
will exclude one or more of those packages and the rule will fail with
hundreds of "<class X> in (StreamsBuilder.java:nn) depends on
<class com.fasterxml.jackson.databind.JsonNode>" violations. None of
those violations represent a real architectural problem; they are
artefacts of an under-specified allow list.

Worse: because the rule is the FIRST one a developer reads, and is
labelled "PDF-grounded" in the Javadoc, the false positives will be
read as "the architecture rules are noisy, ignore them". The genuine
violations buried in the layered rule will be ignored alongside.

There is also a contradiction with the layered rule's Streams row,
which I treat as a distinct finding (F-04).

Why it matters:
  - This is the ONLY rule that asserts the single PDF-supported
    structural claim. If it fails for the wrong reason, the PDF's
    actual architectural commitment goes unenforced.
  - `onlyDependOnClassesThat` rules are notoriously fragile precisely
    because of the total-coverage requirement; they are the "hardest"
    flavour of ArchUnit rule and should be paired with an empirically
    derived allow-list, not with a guess.
  - Once the rule fires false positives, the standard remediation in
    most teams is `.allowEmptyShould(true)` plus an `@ArchIgnore` or
    deletion, which removes the only PDF-grounded constraint from the
    suite altogether.

How to fix it:
Two complementary fixes — apply both.

1. Expand the allow-list to cover the third-party runtime surface that
   the published kafka-streams JAR actually uses. The list below is the
   minimum that empirically passes against kafka-streams 3.7.0:

```java
@ArchTest
static final ArchRule streams_uses_only_kafka_client_libraries =
        classes()
                .that().resideInAPackage("org.apache.kafka.streams..")
                .should().onlyDependOnClassesThat()
                          .resideInAnyPackage(
                                  // JDK / Jakarta
                                  "java..",
                                  "javax..",
                                  // Logging facade
                                  "org.slf4j..",
                                  // Embedded state store
                                  "org.rocksdb..",
                                  // JSON / serialisation (used by metrics
                                  // and some internal config helpers)
                                  "com.fasterxml.jackson..",
                                  // Compression codecs surfaced through
                                  // the producer/consumer client API
                                  "com.github.luben.zstd..",
                                  "org.xerial.snappy..",
                                  "net.jpountz.lz4..",
                                  "org.lz4..",
                                  // Metric / utility libs used by Streams
                                  "io.github.classgraph..",
                                  // Kafka public surface (PDF-grounded)
                                  "org.apache.kafka.common..",
                                  "org.apache.kafka.clients..",
                                  "org.apache.kafka.streams..")
                .allowEmptyShould(true)
                .because("Per page 1 of the supplied Kafka Streams "
                       + "architecture page: 'Kafka Streams simplifies "
                       + "application development by building on the "
                       + "Kafka producer and consumer libraries'. The "
                       + "allow-list above expands 'producer and consumer "
                       + "libraries' to the client-side surface (clients, "
                       + "common) plus the third-party libs the published "
                       + "kafka-streams JAR transitively links (JDK, "
                       + "SLF4J, RocksDB, Jackson, compression codecs).");
```

2. As a back-stop, REPLACE the single positive rule with TWO rules: one
   structural permission (Streams → Clients/Common is allowed by the
   layered rule already, no extra rule needed) plus one explicit
   prohibition (Streams must NOT depend on broker-side modules). The
   second is testable without an allow-list and far less fragile:

```java
@ArchTest
static final ArchRule streams_must_not_depend_on_broker_internals =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.streams..")
                .should().dependOnClassesThat()
                          .resideInAnyPackage(
                                  "org.apache.kafka.server..",
                                  "org.apache.kafka.controller..",
                                  "org.apache.kafka.coordinator..",
                                  "org.apache.kafka.metadata..",
                                  "org.apache.kafka.raft..",
                                  "org.apache.kafka.snapshot..",
                                  "org.apache.kafka.image..",
                                  "org.apache.kafka.storage..")
                .allowEmptyShould(true)
                .because("Per page 1 of the supplied Kafka Streams "
                       + "architecture page, Streams 'builds on the "
                       + "Kafka producer and consumer libraries' — i.e. "
                       + "the public client surface. A compile-time edge "
                       + "from Streams into broker-side modules "
                       + "(server, raft, controller, coordinator, "
                       + "metadata, snapshot, storage, image) would force "
                       + "every Streams application to ship the broker "
                       + "classpath, contradicting the documented "
                       + "client-library positioning.");
```

Either keep both rules, or use the prohibition alone — both are
defensible. The current single-positive rule, with its narrow allow-
list, is not.
```

```
[F-04] SEVERITY: HIGH
Category: Semantic Error / Internal Inconsistency
Affected Rule / Constraint: kafka_layered_architecture_is_respected
                            (Streams, Connect, Clients rows) vs.
                            streams_uses_only_kafka_client_libraries

What is wrong:
The layered access matrix for Streams (and the analogous rows for
Connect and Clients) is BROADER than what the positive Streams rule
asserts:

    .whereLayer("Clients") .mayOnlyAccessLayers(
            "Core", "Infrastructure", "Metadata")

    .whereLayer("Streams") .mayOnlyAccessLayers(
            "Core", "Infrastructure", "Metadata", "Clients")

    .whereLayer("Connect") .mayOnlyAccessLayers(
            "Core", "Infrastructure", "Metadata", "Clients")

Compare with C-S1 ("Streams builds on Kafka producer and consumer
libraries") and the positive rule, which restricts Streams' allow-list
to Core (`common`) and Clients only — explicitly NOT Infrastructure or
Metadata.

So:
  - A class `org.apache.kafka.streams.processor.internals.StreamThread`
    that depends on `org.apache.kafka.metadata.MetadataRecord` is
    ACCEPTED by the layered rule (Metadata is in Streams' allow-list)
    but REJECTED by the positive rule (Metadata is NOT in the allow
    list).
  - A class `org.apache.kafka.streams.…` that depends on
    `org.apache.kafka.raft.RaftClient` is ACCEPTED by the layered rule
    (raft is in Infrastructure, which Streams may access) but REJECTED
    by the positive rule (no Infrastructure-prefixed package is in
    the allow-list).
  - A class `org.apache.kafka.streams.…` that depends on
    `org.apache.kafka.image.MetadataImage` — same story.

The same internal contradiction holds for `Clients.mayOnlyAccessLayers
(..., Metadata)`: the published `kafka-clients` artefact does NOT depend
on `kafka-metadata`. Allowing it in the layered rule grants permission
for a real architectural regression (clients pulling broker-side
metadata types into the public client JAR). The dedicated comment in
the layer definition ("Clients includes clients.admin; the top-level
api/admin packages are not used in the real Kafka tree") indicates the
generator KNEW Clients should be tightly bounded — but the access
matrix it built is one of the loosest in the file.

Why it matters:
  - In CI, a real Streams → Metadata edge would generate two
    contradictory results from the same test class: pass on the layered
    rule, fail on the positive rule. Triage becomes a coin-flip.
  - The C-S1 contract — "Streams uses producer/consumer libraries only"
    — is silently downgraded to "Streams uses anything below it in the
    monorepo". The PDF's only architectural commitment is dropped.
  - Adding new server-side state to Streams (e.g., embedding a raft
    client into kafka-streams to support a query layer) becomes
    invisible to the layered rule, even though it would invalidate the
    "lightweight client library" positioning the PDF asserts.

How to fix it:
Tighten the layered access matrix so it cannot grant strictly more
than the positive rule does. Drop Metadata from Clients' allow list,
drop Infrastructure and Metadata from Streams' and Connect's allow
lists:

```java
.whereLayer("Clients").mayOnlyAccessLayers("Core")
// ^ kafka-clients depends only on common (Core); Metadata, raft,
//   storage, etc. are not in the published kafka-clients JAR's
//   compile classpath.

.whereLayer("Streams").mayOnlyAccessLayers("Core", "Clients")
// ^ Per C-S1: Streams builds on the producer/consumer libraries
//   (Clients) and the shared primitives (Core). Nothing deeper.

.whereLayer("Connect").mayOnlyAccessLayers("Core", "Clients")
// ^ Per the published kafka-connect-* artefacts: Connect runtime
//   talks to brokers via the AdminClient + Producer/Consumer
//   surface in kafka-clients; it does not link broker internals.
```

If empirical analysis of the real JARs reveals legitimate edges into
Infrastructure (e.g., Connect's REST runtime really does use
`org.apache.kafka.network`), restore the specific package and add a
matching exception to the positive Streams rule's allow-list. The
layered rule and the positive rule must NEVER disagree about what the
documentation permits.
```

```
[F-05] SEVERITY: CRITICAL
Category: Structural Gap (build configuration)
Affected Rule / Constraint: pom.xml dependency on
                            kafka-transaction-coordinator

What is wrong:
The pom.xml declares fifteen Apache Kafka artefact dependencies. Most
resolve cleanly against Maven Central. One does NOT exist:

    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>kafka-transaction-coordinator</artifactId>
        <version>${kafka.version}</version>      <!-- 3.7.0 -->
        <scope>test</scope>
    </dependency>

There is no published artefact at coordinates
`org.apache.kafka:kafka-transaction-coordinator:3.7.0` on Maven
Central. The transaction-coordinator code in modern Kafka lives in
`org.apache.kafka.coordinator.transaction.*` and is published as part
of `kafka-server-common` (or, in some 3.x builds, lives in the Scala
broker module that publishes under coordinates `kafka_2.13`, not under
`org.apache.kafka:*`).

Once F-01 is fixed and the POM parses, Maven's dependency-resolution
phase will fail with:

    Could not find artifact
        org.apache.kafka:kafka-transaction-coordinator:jar:3.7.0
    in central (https://repo.maven.apache.org/maven2)

This breaks the build a second time. The classpath construction never
completes, no Surefire test ever runs, every rule in the file remains
silently disabled.

Why it matters:
The whole point of the pom.xml rewrite that introduced this dependency
list (relative to review #1's broken additionalClasspathElements) was
to guarantee that bytecode reaches the importer. A fictional artefact
defeats that guarantee.

How to fix it:
Drop the kafka-transaction-coordinator dependency. Its package
(`org.apache.kafka.coordinator.transaction.*`) is already provided by
`kafka-server-common` and `kafka-coordinator` (or `kafka-group-
coordinator`) on Maven Central. The remaining dependency list provides
full coverage of `org.apache.kafka.coordinator..`.

```xml
<!-- DELETE:
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-transaction-coordinator</artifactId>
    <version>${kafka.version}</version>
    <scope>test</scope>
</dependency>
-->

<!-- KEEP (already present); this transitively covers
     org.apache.kafka.coordinator.transaction.*: -->
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-group-coordinator</artifactId>
    <version>${kafka.version}</version>
    <scope>test</scope>
</dependency>
```

Verify the fix with `mvn -B dependency:resolve` BEFORE committing.
A second pre-flight check is to run `mvn dependency:tree | grep
"org.apache.kafka.coordinator"` and confirm classes from
`org.apache.kafka.coordinator.transaction.*` are present in the
unpacked classpath.
```

```
[F-06] SEVERITY: HIGH
Category: Rule Redundancy / Diagnostic Noise
Affected Rule / Constraint: tooling_must_not_depend_on_broker_internals
                            vs. layered rule's Server.mayOnlyBeAccessedByLayers

What is wrong:
The layered rule already contains the line

    .whereLayer("Server").mayOnlyBeAccessedByLayers("Server")

which forbids EVERY non-Server layer from accessing Server. Combined
with `Tooling.mayOnlyAccessLayers(...)` (which omits Server) and the
fact that Tooling and Server are both declared layers (so
`consideringOnlyDependenciesInLayers()` does NOT filter the edge out),
the layered rule already detects every `tools/shell/trogdor → server`
edge.

The dedicated rule

    @ArchTest
    static final ArchRule tooling_must_not_depend_on_broker_internals =
            noClasses()
                    .that().resideInAnyPackage(
                            "org.apache.kafka.tools..",
                            "org.apache.kafka.shell..",
                            "org.apache.kafka.trogdor..")
                    .should().dependOnClassesThat().resideInAPackage(
                            "org.apache.kafka.server..")
                    .allowEmptyShould(true)
                    .because("...");

therefore adds zero coverage. It is fully subsumed twice — once by
`Server.mayOnlyBeAccessedByLayers("Server")`, once by
`Tooling.mayOnlyAccessLayers(no Server)`.

Why it matters:
  - When a real `tools.SomeAdminTool → server.BrokerServer` edge is
    introduced, CI emits THREE failure messages (layered rule's "Layer
    Tooling may not access layer Server", layered rule's "Layer
    Server may only be accessed by Server", and the dedicated
    noClasses rule's "tools depends on org.apache.kafka.server"). All
    three are correct, all three describe the same edge, and a tired
    on-call has to read all three.
  - Worse, the dedicated rule has its own `.because()` clause that may
    drift out of sync with the layered rule's prose over time. (The
    layered rule's because says "INFERRED from the standard Apache
    Kafka monorepo layout"; the dedicated rule's because says "CLI
    tools, the metadata shell, and Trogdor are client-side processes
    that talk to a running broker over the wire protocol". Both are
    true but they are not the same justification.)
  - The same observation applies to several of the intra-Tooling
    rules. `tools_must_not_depend_on_shell` and its five symmetric
    siblings ARE genuinely needed (the layered rule cannot express
    intra-Tooling isolation), so those are kept. But the inter-layer
    `tooling → server` rule is not.

How to fix it:
Delete `tooling_must_not_depend_on_broker_internals`. Move its
`.because()` text into the layered rule's `.because()` so the
justification is preserved for the developer who reads the layered
diagnostic:

```java
// DELETE:
//   tooling_must_not_depend_on_broker_internals
//   (subsumed by Server.mayOnlyBeAccessedByLayers("Server") AND by
//    Tooling.mayOnlyAccessLayers omitting "Server")

.because("INFERRED from the standard Apache Kafka monorepo layout. "
       + "In particular, kafka-tools / kafka-metadata-shell / trogdor "
       + "are client-side processes that talk to a running broker "
       + "over the wire protocol; they must not compile-depend on "
       + "broker internals because that would force every CLI "
       + "invocation to ship the broker classpath. This is enforced "
       + "by the .whereLayer(\"Server\").mayOnlyBeAccessedByLayers(\"Server\") "
       + "back-stop above.");
```
```

```
[F-07] SEVERITY: HIGH
Category: Overly Broad
Affected Rule / Constraint: kafka_layered_architecture_is_respected
                            (Tooling row)

What is wrong:
The Tooling layer pools three deployable artefacts that are
operationally distinct:

    org.apache.kafka.tools..     <-- kafka-topics.sh, kafka-consumer-
                                     groups.sh, kafka-configs.sh, etc.
                                     Pure AdminClient batch utilities.
    org.apache.kafka.shell..     <-- kafka-metadata-shell.sh:
                                     interactive REPL that reads
                                     KRaft snapshots.
    org.apache.kafka.trogdor..   <-- distributed fault-injection /
                                     workload-generation daemons.

The layered rule grants Tooling permission to access Streams AND
Connect:

    .whereLayer("Tooling").mayOnlyAccessLayers(
            "Core", "Infrastructure", "Metadata", "Clients",
            "Streams", "Connect")

Two specific problems:

(a) `org.apache.kafka.shell` (the metadata REPL) does NOT depend on
    Streams or Connect. It reads raft snapshots and the metadata image.
    Granting it Streams/Connect access creates a permission for a
    refactor that would, e.g., import `org.apache.kafka.streams.Topology`
    into the metadata shell — clearly architecturally wrong, but
    silently allowed.

(b) `org.apache.kafka.trogdor` (workload daemons) does NOT depend on
    Streams or Connect. Same story.

Only `org.apache.kafka.tools` legitimately depends on Streams (via
`StreamsResetter`, the kafka-streams-application-reset.sh utility).
Even there, the dependency is on Streams, not on Connect.

The Tooling layer is also too broad in the OTHER direction: it
grants `tools/shell/trogdor → Infrastructure` access (raft, snapshot,
storage, image, queue, network), but only `shell` legitimately needs
raft/snapshot/image (it reads KRaft snapshots). `tools` does not need
raft. `trogdor` does not need raft.

Why it matters:
The layered rule is supposed to be the primary defence against
architectural regressions. By packaging three operationally distinct
modules into one layer with the union of their access permissions, the
rule grants everyone the most permissive access. Future regressions
(trogdor pulling kafka-streams onto its classpath, kafka-tools
loading raft snapshot classes) sail through.

How to fix it:
Either split Tooling into three layers, or split it into two
("Tooling" for tools+trogdor; "MetadataShell" for shell), each with
the minimum access it actually needs:

```java
.layer("Shell").definedBy("org.apache.kafka.shell..")
.layer("Tooling").definedBy(
        "org.apache.kafka.tools..",
        "org.apache.kafka.trogdor..")

.whereLayer("Shell").mayOnlyAccessLayers(
        // The metadata shell reads KRaft snapshots, the metadata
        // image, and uses the AdminClient.
        "Core", "Infrastructure", "Metadata", "Clients")

.whereLayer("Tooling").mayOnlyAccessLayers(
        // kafka-tools (tools.StreamsResetter) legitimately uses
        // kafka-streams; trogdor uses kafka-clients only. The union
        // is therefore Core + Clients + Streams.
        "Core", "Clients", "Streams")
```

If preserving a single Tooling layer is preferred for simplicity,
keep it but add explicit prohibitions:

```java
@ArchTest
static final ArchRule shell_must_not_depend_on_streams_or_connect =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.shell..")
                .should().dependOnClassesThat()
                          .resideInAnyPackage(
                                  "org.apache.kafka.streams..",
                                  "org.apache.kafka.connect..")
                .allowEmptyShould(true)
                .because("INFERRED: kafka-metadata-shell is a metadata "
                       + "REPL; kafka-streams and kafka-connect have no "
                       + "place on its classpath.");

@ArchTest
static final ArchRule trogdor_must_not_depend_on_streams_or_connect =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.trogdor..")
                .should().dependOnClassesThat()
                          .resideInAnyPackage(
                                  "org.apache.kafka.streams..",
                                  "org.apache.kafka.connect..")
                .allowEmptyShould(true)
                .because("INFERRED: Trogdor is a workload-generation "
                       + "daemon; it talks to brokers via kafka-clients "
                       + "only.");
```
```

```
[F-08] SEVERITY: MEDIUM
Category: Wrong Layer
Affected Rule / Constraint: Infrastructure layer composition (image)

What is wrong:
`org.apache.kafka.image` is placed in the Infrastructure layer alongside
network, queue, raft, snapshot, storage. The layered rule's `.because()`
clause justifies the layer as "byte-level primitives" / the
"infrastructure (KIP-405 tiered storage SPI, raft consensus, etc.)".

But `org.apache.kafka.image.MetadataImage` and its kin are NOT byte-level
primitives. They are KRaft cluster-state value objects (TopicsImage,
TopicImage, BrokerRegistration, …). They carry Kafka-specific semantic
content; they are above the raft/snapshot/storage layer in the
dependency hierarchy, not next to it.

Concretely:
  - raft and snapshot DO NOT depend on image. (raft is generic
    consensus; snapshot is generic byte-stream.)
  - metadata, controller DO depend on image. (They consume
    MetadataImage as the cluster-state representation.)

So image's place in the dependency graph is BETWEEN Infrastructure and
Metadata, not inside Infrastructure. With the current placement:

  - Metadata.mayOnlyAccessLayers(Core, Infrastructure) ALLOWS
    Metadata to reach into image — correct (the actual edge).
  - Infrastructure.mayOnlyAccessLayers(Core) FORBIDS image to reach
    into Metadata, but in fact image already does NOT reach into
    Metadata, so this is harmless.
  - The rule does NOT express "raft must not depend on image" or
    "snapshot must not depend on image" — both should be forbidden
    invariants but are silently allowed (intra-layer).

Why it matters:
A future refactor that teaches raft to deserialize MetadataRecord
(by pulling MetadataImage into raft) would silently violate the
"reusable consensus library" promise: raft is supposed to be
state-machine-agnostic. The layered rule does not catch this because
raft and image are in the same layer; intra-layer access is
unrestricted.

How to fix it:
Promote `image` from Infrastructure into Metadata, where its semantics
already place it:

```java
.layer("Metadata").definedBy(
        "org.apache.kafka.metadata..",
        "org.apache.kafka.coordinator..",
        "org.apache.kafka.controller..",
        "org.apache.kafka.image..")          // moved here

.layer("Infrastructure").definedBy(
        "org.apache.kafka.network..",
        "org.apache.kafka.queue..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.storage..")        // image removed
```

After this move, Metadata.mayOnlyAccessLayers(Core, Infrastructure)
still allows Metadata classes to access image (now intra-layer), and
the layered rule will fire if raft or snapshot acquires an
image dependency (which would be a layered-rule violation:
Infrastructure may only access Core).
```

```
[F-09] SEVERITY: MEDIUM
Category: Coverage Gap (intra-layer)
Affected Rule / Constraint: Infrastructure intra-layer isolation

What is wrong:
The Infrastructure layer has six (or, after F-08, five) sibling
packages: network, queue, raft, snapshot, storage[, image]. The
layered rule cannot express intra-layer isolation, and the file
contains ZERO `noClasses` rules pinning down sibling pairs within
Infrastructure.

The relevant invariants of the standard Kafka layout that the layered
rule cannot express:

  - `raft` should not depend on `storage` (raft has its own log
    abstraction in `org.apache.kafka.raft.internals.*`; pulling in
    the Kafka log primitive would conflate two state machines).
  - `snapshot` should not depend on `storage` (snapshot uses raft's
    own segment scheme; the Kafka log is unrelated).
  - `network` should not depend on `raft`, `snapshot`, or `storage`
    (network is a NIO socket primitive; the consensus / log abstrac-
    tions sit above it).
  - `queue` should not depend on any other Infrastructure sibling
    (`org.apache.kafka.queue` is an event-loop primitive used by
    raft and the controller — it has no business calling into raft
    from below).

None of these are expressed.

Why it matters:
Compared with the analogous intra-Metadata pairs (which DO have
explicit rules — see `metadata_must_not_depend_on_controller`,
`controller_must_not_depend_on_coordinator`, etc.), the
intra-Infrastructure surface is unprotected. A future refactor that
makes `org.apache.kafka.network.SocketServer` deserialize a
RaftMessage on the wire (instead of leaving deserialisation to a
higher layer) would silently violate the "network is just NIO bytes"
invariant — and no rule fires.

How to fix it:
Add the missing intra-layer rules. The minimum set is:

```java
@ArchTest
static final ArchRule queue_must_not_depend_on_other_infrastructure =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.queue..")
                .should().dependOnClassesThat()
                          .resideInAnyPackage(
                                  "org.apache.kafka.network..",
                                  "org.apache.kafka.raft..",
                                  "org.apache.kafka.snapshot..",
                                  "org.apache.kafka.storage..")
                .allowEmptyShould(true)
                .because("INFERRED: org.apache.kafka.queue (KafkaEvent"
                       + "Queue) is a primitive event loop. It is "
                       + "consumed by raft and the controller; the "
                       + "reverse edge would couple the event-loop "
                       + "implementation to higher-level Kafka "
                       + "abstractions.");

@ArchTest
static final ArchRule network_must_not_depend_on_higher_infrastructure =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.network..")
                .should().dependOnClassesThat()
                          .resideInAnyPackage(
                                  "org.apache.kafka.raft..",
                                  "org.apache.kafka.snapshot..",
                                  "org.apache.kafka.storage..")
                .allowEmptyShould(true)
                .because("INFERRED: network is a NIO selector / channel "
                       + "primitive. raft, snapshot, and storage sit "
                       + "above it in the dependency graph.");

@ArchTest
static final ArchRule raft_must_not_depend_on_storage =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.raft..")
                .should().dependOnClassesThat().resideInAPackage(
                        "org.apache.kafka.storage..")
                .allowEmptyShould(true)
                .because("INFERRED: raft maintains its own log via "
                       + "org.apache.kafka.raft.internals.BatchAccumulator "
                       + "/ KafkaRaftClient. The Kafka data-log "
                       + "primitive in org.apache.kafka.storage is "
                       + "unrelated; conflating them would create a "
                       + "circular dependency at the consensus level.");
```
```

```
[F-10] SEVERITY: MEDIUM
Category: Vacuous Rule (potential)
Affected Rule / Constraint: layered rule, "org.apache.kafka.message.."

What is wrong:
The Core layer is defined as

    .layer("Core").definedBy(
            "org.apache.kafka.common..",
            "org.apache.kafka.message..",
            "org.apache.kafka.timeline..",
            "org.apache.kafka.deferred..")

In the published `kafka-clients-3.7.0.jar`, message-protocol classes
generated by Kafka's own message generator are placed under
`org.apache.kafka.common.message.*` and
`org.apache.kafka.common.requests.*`, NOT under top-level
`org.apache.kafka.message.*`. The top-level `org.apache.kafka.message`
package is the Java home of the GENERATOR itself — i.e. the source
tooling that produces the `org.apache.kafka.common.message.*` classes.
That generator is published, if at all, as `kafka-message-generator`
or similar build-tool-only artefact, and is generally NOT on the test
classpath of an ArchUnit suite.

Consequence: the `org.apache.kafka.message..` glob most likely matches
ZERO imported classes. The layer's intent is preserved by the
`org.apache.kafka.common..` glob (which DOES include
`org.apache.kafka.common.message.*`), so this is not a coverage gap —
just a vacuous glob that misleads readers about Core's actual contents.

Why it matters:
Per F-09 of review #1: "globs that match nothing make the layer
thinner than it appears". Here the effect is purely cosmetic — the
layer still picks up the right message classes via
`org.apache.kafka.common..` — but a future maintainer reading the
layer definition will believe `org.apache.kafka.message.*` carries
runtime classes, and may write rules against it on that mistaken
basis (e.g. trying to forbid `streams → o.a.k.message`, which would
match nothing).

How to fix it:
Drop the `org.apache.kafka.message..` glob unless the project really
does load the generator's classes:

```java
.layer("Core").definedBy(
        "org.apache.kafka.common..",        // includes common.message.*
        "org.apache.kafka.timeline..",
        "org.apache.kafka.deferred..")
```

If the package list `inputs/java/7_apache_kafka.txt` was produced from
a wider scan that includes the generator JAR, keep the glob and add a
non-emptiness assertion so the layer's actual contents are documented
(see analogous fix in F-08 of review #1).
```

```
[F-11] SEVERITY: LOW
Category: Diagnostic Risk (`withOptionalLayers(true)` masking)
Affected Rule / Constraint: kafka_layered_architecture_is_respected

What is wrong:
`.withOptionalLayers(true)` is applied to the layered rule. The
Javadoc justifies this: "withOptionalLayers(true) tolerates layers
that are empty because the corresponding Gradle module isn't on the
local classpath; this prevents the 'Layer X is empty' failure that
bricked the prior rule run."

This is a sensible mitigation for the previous failure mode (review
#1's F-01) but it has a corollary cost: a TYPO in a layer's package
glob now silently produces an empty layer with no diagnostic. If
someone fat-fingers `org.apache.kafka.streams..` to
`org.apache.kafka.stream..` (one missing 's'), the Streams layer
becomes empty, the rule still passes, and Streams' access matrix is
silently disabled (`Streams.mayOnlyAccessLayers(...)` no longer
restricts anything because Streams contains no classes).

Combined with `.allowEmptyShould(true)` on every `noClasses` rule,
the suite's failure mode for misconfiguration is "everything passes,
nothing is checked".

Why it matters:
This is a tradeoff, not an outright bug. The trade-off is:
  - WITH `withOptionalLayers(true)`: missing classpath modules don't
    fail; configuration typos don't fail; rule logic is harder to
    QA.
  - WITHOUT `withOptionalLayers(true)`: missing classpath modules
    fail loudly; configuration typos also fail loudly; rule
    misconfiguration is visible.

The chosen tradeoff is defensible IF the pom.xml reliably loads the
classpath (which after F-01 + F-05 it would). But there is no
back-stop checking that the layers actually have members.

How to fix it:
Either (a) drop `withOptionalLayers(true)` once F-01 and F-05 are
verified to make the classpath reliably populated, or (b) keep it
and add a non-emptiness sentinel for each layer so misconfiguration
DOES fail:

```java
@ArchTest
static final ArchRule layers_are_non_empty_after_classpath_setup =
        classes()
                .that().resideInAnyPackage(
                        // Mirror of layer .definedBy() globs.
                        "org.apache.kafka.common..",
                        "org.apache.kafka.streams..",
                        "org.apache.kafka.clients..",
                        "org.apache.kafka.connect..",
                        "org.apache.kafka.server..",
                        "org.apache.kafka.metadata..",
                        "org.apache.kafka.tools..")
                .should().bePublic()
                .orShould().bePackagePrivate()
                // No allowEmptyShould — we want this to fail loudly
                // when the classpath does not contain any of these
                // packages, signalling a Maven dep-resolution problem.
                .because("Sanity check: the layered rule uses "
                       + "withOptionalLayers(true), which masks empty "
                       + "layers caused by a typo or a missing Maven "
                       + "dependency. This rule fails noisily when "
                       + "NONE of the major layers can be populated, "
                       + "which is the symptom of the F-01 / F-05 "
                       + "build-configuration regressions of review #1 "
                       + "and review #2.");
```
```

```
[F-12] SEVERITY: LOW
Category: Vacuous Rule (potential)
Affected Rule / Constraint: ImportOption ExcludeBenchmarksAndTestSupport

What is wrong:
The custom ImportOption excludes locations whose path contains any of:

    /org/apache/kafka/jmh/
    /org/apache/kafka/test/
    /org/apache/kafka/tiered/

The first two are correct (jmh = benchmarks, test = MockConsumer
support). The third is unverified: `org.apache.kafka.tiered` is
declared as production package in the input package list:

    org.apache.kafka.tiered

The Javadoc claims "Tiered-storage E2E test harness" — but the
package list does not flag it as test-only. If the package is
actually production code, the ImportOption silently removes
production classes from the importer, and any architectural
violation IN those classes (e.g. a hypothetical
`o.a.k.tiered.storage.RemoteLogManager → o.a.k.streams.Topology`
edge) goes undetected. This is the same shape of defect as F-03 of
review #1 (test-support exclusion zeroing out the rule's target).

The correctness of the exclusion depends on a claim that ONLY the
generator made, in its own Javadoc; there is no corroboration in the
input package list, and no comment in the package-list `.txt`
explaining the intent.

Why it matters:
If the claim is wrong, a non-trivial production module is invisible
to ArchUnit. If the claim is right, no harm is done. The bet should
be made explicitly, not on the generator's word.

How to fix it:
Either (a) verify by inspecting the published `kafka-storage-3.7.0.jar`
test-jar (or the relevant Gradle module) that
`org.apache.kafka.tiered.*` lives in `src/test/java`, NOT
`src/main/java`. If confirmed, add a one-line note linking the proof
to the comment, e.g.:

```java
public static final class ExcludeBenchmarksAndTestSupport
        implements ImportOption {
    // Verified against kafka-3.7.0 source tree:
    //   - org.apache.kafka.jmh    -> jmh-benchmarks/src/main/java (JMH only)
    //   - org.apache.kafka.test   -> clients/src/test/java/...   (MockConsumer, TestUtils)
    //   - org.apache.kafka.tiered -> storage/src/test/java/...   (TieredStorageTestHarness)
    // No production classes live under any of these prefixes.
    @Override
    public boolean includes(Location location) {
        return !location.contains("/org/apache/kafka/jmh/")
            && !location.contains("/org/apache/kafka/test/")
            && !location.contains("/org/apache/kafka/tiered/");
    }
}
```

Or (b) drop `tiered` from the exclusion list and place
`org.apache.kafka.tiered..` in either Server (if it really is broker-
side production code) or Tooling (if it is operationally similar to
trogdor). If the rules then fail because `tiered` violates them, the
violation is exactly what we want to surface.
```

---

## Recommended Consolidated Patch

The patch below addresses **F-01, F-02, F-03, F-04, F-05, F-06, F-07, F-08, F-10, F-11, F-12** in a single pass. **F-09** (intra-Infrastructure isolation) is left for a follow-up because it expands rule count substantially; pick its rules from F-09's snippets and add them on top.

### Patch 1 of 2 — `pom.xml` (fixes F-01, F-05)

```java
// (this is XML, shown in a java-fenced block only because the review
//  template requires java fencing)
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.archunit</groupId>
    <artifactId>kafka-archunit</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>Apache Kafka ArchUnit Enforcement Tests</name>
    <description><![CDATA[
        Standalone ArchUnit test suite enforcing the documented Apache
        Kafka layer hierarchy. Pulls compiled bytecode for the
        org.apache.kafka.* modules onto the test classpath through
        published Maven Central artefacts and verifies inter-layer and
        intra-module dependency rules derived from the Kafka Streams
        architecture documentation.

        The previous configuration relied on relative paths into a
        sibling Apache Kafka Gradle source tree (../../../kafka/<module>/
        build/...), which silently produced an empty classpath on
        machines without that sibling tree and caused every @ArchTest
        to fail with "Layer X is empty" / "failed to check any classes".
        The Maven-Central dependency approach below removes that
        brittle assumption: the importer always sees real Kafka
        bytecode.
    ]]></description>

    <properties>
        <java.version>11</java.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <archunit.version>1.3.0</archunit.version>
        <junit.jupiter.version>5.10.2</junit.jupiter.version>
        <kafka.version>3.7.0</kafka.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <version>${archunit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-engine</artifactId>
            <version>${junit.jupiter.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <version>${junit.jupiter.version}</version>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-clients</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-streams</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-api</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-runtime</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-transforms</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-mirror</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>connect-json</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-server-common</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-metadata</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-raft</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-storage</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-storage-api</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-group-coordinator</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <!-- F-05: kafka-transaction-coordinator does NOT exist on
             Maven Central; the transaction coordinator code is
             provided transitively by kafka-server-common and
             kafka-group-coordinator. -->
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-tools</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-shell</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>trogdor</artifactId>
            <version>${kafka.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <testSourceDirectory>.</testSourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                    <encoding>UTF-8</encoding>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <includes>
                        <include>**/ArchitectureEnforcementTest.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Patch 2 of 2 — `ArchitectureEnforcementTest.java` (fixes F-02, F-03, F-04, F-06, F-07, F-08, F-10, F-11, F-12)

```java
/**
 * ArchitectureEnforcementTest — Apache Kafka.
 *
 * SCOPE OF THE INPUT DOCUMENTATION
 * --------------------------------
 * The supplied PDF (the Kafka Streams "Architecture" page from the
 * Apache Kafka website) documents Streams' RUNTIME parallelism model
 * only. It contains exactly ONE structural claim about cross-module
 * dependencies:
 *
 *   "Kafka Streams simplifies application development by building on
 *    the Kafka producer and consumer libraries"   (PDF page 1, para 1)
 *
 * Every other constraint enforced below is INFERRED from the standard
 * Apache Kafka monorepo layout and is marked as such in its
 * .because() clause.
 *
 * EXCLUDED PACKAGES
 * -----------------
 *   - org.apache.kafka.test    : MockConsumer / MockProducer / TestUtils
 *                                (verified test-only in clients/src/test).
 *   - org.apache.kafka.jmh     : JMH benchmarks (build-time only).
 *   - org.apache.kafka.tiered  : Tiered-storage E2E test harness;
 *                                verified test-only in storage/src/test.
 *                                (See F-12: confirm before relying on this.)
 */
package com.archunit.kafka;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.kafka",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ArchitectureEnforcementTest.ExcludeBenchmarksAndTestSupport.class
        })
public class ArchitectureEnforcementTest {

    public static final class ExcludeBenchmarksAndTestSupport implements ImportOption {
        // F-12: verify these prefixes contain only test/benchmark code
        // before relying on them; otherwise production violations slip through.
        @Override
        public boolean includes(Location location) {
            return !location.contains("/org/apache/kafka/jmh/")
                && !location.contains("/org/apache/kafka/test/")
                && !location.contains("/org/apache/kafka/tiered/");
        }
    }

    // =========================================================================
    // Layered architecture (F-02 + F-04 + F-07 + F-08)
    // =========================================================================
    //
    // Layer            Members                                            Source
    // -----            -------                                            ------
    // Core             common, timeline, deferred, config, logger         INFERRED
    // Infrastructure   network, queue, raft, snapshot, storage, security  INFERRED
    // Metadata         metadata, coordinator, controller, image           INFERRED
    // Clients          clients, admin, api                                PDF p.1 (Clients)
    // Streams          streams                                            PDF p.1
    // Connect          connect                                            INFERRED
    // Server           server (broker runtime)                            INFERRED
    // Shell            shell (KRaft metadata REPL)                        INFERRED
    // Tooling          tools, trogdor                                     INFERRED
    @ArchTest
    static final ArchRule kafka_layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .withOptionalLayers(true)

                    .layer("Core").definedBy(
                            "org.apache.kafka.common..",
                            "org.apache.kafka.timeline..",
                            "org.apache.kafka.deferred..",
                            "org.apache.kafka.config..",
                            "org.apache.kafka.logger..")

                    .layer("Infrastructure").definedBy(
                            "org.apache.kafka.network..",
                            "org.apache.kafka.queue..",
                            "org.apache.kafka.raft..",
                            "org.apache.kafka.snapshot..",
                            "org.apache.kafka.storage..",
                            "org.apache.kafka.security..")

                    .layer("Metadata").definedBy(
                            "org.apache.kafka.metadata..",
                            "org.apache.kafka.coordinator..",
                            "org.apache.kafka.controller..",
                            "org.apache.kafka.image..")

                    .layer("Clients").definedBy(
                            "org.apache.kafka.clients..",
                            "org.apache.kafka.admin..",
                            "org.apache.kafka.api..")

                    .layer("Streams").definedBy("org.apache.kafka.streams..")
                    .layer("Connect").definedBy("org.apache.kafka.connect..")
                    .layer("Server").definedBy("org.apache.kafka.server..")
                    .layer("Shell").definedBy("org.apache.kafka.shell..")
                    .layer("Tooling").definedBy(
                            "org.apache.kafka.tools..",
                            "org.apache.kafka.trogdor..")

                    .whereLayer("Core")          .mayNotAccessAnyLayer()
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Core")
                    .whereLayer("Metadata")      .mayOnlyAccessLayers("Core", "Infrastructure")

                    // F-04: Clients depends only on Core; Metadata is broker-
                    // side and is NOT on kafka-clients's compile classpath.
                    .whereLayer("Clients")       .mayOnlyAccessLayers("Core")

                    // F-04: Per C-S1, Streams depends only on Clients + Core.
                    .whereLayer("Streams")       .mayOnlyAccessLayers("Core", "Clients")

                    // F-04: Connect runtime talks to brokers via the
                    // public client surface only.
                    .whereLayer("Connect")       .mayOnlyAccessLayers("Core", "Clients")

                    // Server (broker) legitimately uses Infrastructure and
                    // Metadata; it does NOT compile-depend on Clients.
                    .whereLayer("Server")        .mayOnlyAccessLayers(
                            "Core", "Infrastructure", "Metadata")

                    // F-07: Shell reads KRaft snapshots and the metadata image;
                    // it uses the AdminClient. It does NOT use Streams or Connect.
                    .whereLayer("Shell")         .mayOnlyAccessLayers(
                            "Core", "Infrastructure", "Metadata", "Clients")

                    // F-07: Tooling = tools + trogdor. tools.StreamsResetter
                    // legitimately uses kafka-streams; trogdor uses kafka-clients
                    // only. The union is therefore Core + Clients + Streams.
                    .whereLayer("Tooling")       .mayOnlyAccessLayers(
                            "Core", "Clients", "Streams")

                    // F-06: Server is reachable only from itself.
                    .whereLayer("Server").mayOnlyBeAccessedByLayers("Server")

                    .because("INFERRED from the standard Apache Kafka monorepo "
                           + "layout (KIP-500 controller/broker split, KIP-405 "
                           + "tiered-storage SPI, Connect plugin isolation). "
                           + "The supplied PDF documents Streams' parallelism "
                           + "model only and does not prescribe these module "
                           + "boundaries; they are CI-level enforcement of the "
                           + "established kafka-* artefact split. In particular, "
                           + "kafka-tools / kafka-metadata-shell / trogdor are "
                           + "client-side processes that talk to a running "
                           + "broker over the wire protocol; the "
                           + "Server.mayOnlyBeAccessedByLayers(\"Server\") "
                           + "back-stop above forbids them (and any other "
                           + "non-broker layer) from compile-depending on "
                           + "broker internals.");

    // =========================================================================
    // Layer-coverage sentinel (F-02)
    // =========================================================================

    @ArchTest
    static final ArchRule every_kafka_package_belongs_to_a_layer =
            classes()
                    .that().resideInAPackage("org.apache.kafka..")
                    .and().resideOutsideOfPackages(
                            "org.apache.kafka.jmh..",
                            "org.apache.kafka.test..",
                            "org.apache.kafka.tiered..",
                            // org.apache.kafka.message is the message
                            // generator (build tool only); see F-10.
                            "org.apache.kafka.message..")
                    .should().resideInAnyPackage(
                            // Mirror of the layer .definedBy() globs.
                            "org.apache.kafka.common..",
                            "org.apache.kafka.timeline..",
                            "org.apache.kafka.deferred..",
                            "org.apache.kafka.config..",
                            "org.apache.kafka.logger..",
                            "org.apache.kafka.network..",
                            "org.apache.kafka.queue..",
                            "org.apache.kafka.raft..",
                            "org.apache.kafka.snapshot..",
                            "org.apache.kafka.storage..",
                            "org.apache.kafka.security..",
                            "org.apache.kafka.metadata..",
                            "org.apache.kafka.coordinator..",
                            "org.apache.kafka.controller..",
                            "org.apache.kafka.image..",
                            "org.apache.kafka.clients..",
                            "org.apache.kafka.admin..",
                            "org.apache.kafka.api..",
                            "org.apache.kafka.streams..",
                            "org.apache.kafka.connect..",
                            "org.apache.kafka.server..",
                            "org.apache.kafka.shell..",
                            "org.apache.kafka.tools..",
                            "org.apache.kafka.trogdor..")
                    .allowEmptyShould(true)
                    .because("F-02: every production package must be "
                           + "assigned to exactly one layer. "
                           + "consideringOnlyDependenciesInLayers() drops "
                           + "edges whose endpoint is in no layer, so a "
                           + "new top-level org.apache.kafka.X package "
                           + "that is missing from the layered rule's "
                           + ".definedBy() clauses silently bypasses "
                           + "every cross-layer assertion.");

    // =========================================================================
    // Positive C-S1 rule (PDF-grounded) (F-03)
    // =========================================================================
    //
    // Replaces the prior streams_uses_only_kafka_client_libraries rule.
    // The negative formulation does not require an exhaustive third-party
    // allow-list, so it does not produce false positives when Kafka
    // Streams legitimately uses Jackson / compression codecs / etc.
    @ArchTest
    static final ArchRule streams_must_not_depend_on_broker_internals =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.streams..")
                    .should().dependOnClassesThat()
                              .resideInAnyPackage(
                                      "org.apache.kafka.server..",
                                      "org.apache.kafka.controller..",
                                      "org.apache.kafka.coordinator..",
                                      "org.apache.kafka.metadata..",
                                      "org.apache.kafka.raft..",
                                      "org.apache.kafka.snapshot..",
                                      "org.apache.kafka.image..",
                                      "org.apache.kafka.storage..",
                                      "org.apache.kafka.network..",
                                      "org.apache.kafka.queue..")
                    .allowEmptyShould(true)
                    .because("Per page 1 of the supplied Kafka Streams "
                           + "architecture page: 'Kafka Streams simplifies "
                           + "application development by building on the "
                           + "Kafka producer and consumer libraries'. A "
                           + "compile-time edge from Streams into broker-"
                           + "side modules (server, raft, controller, "
                           + "coordinator, metadata, snapshot, storage, "
                           + "image, network, queue) would force every "
                           + "Streams application to ship the broker "
                           + "classpath, contradicting the documented "
                           + "client-library positioning.");

    // =========================================================================
    // Intra-layer sibling isolation (the layered API cannot express these)
    // =========================================================================

    @ArchTest
    static final ArchRule metadata_must_not_depend_on_controller =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.metadata..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the metadata module provides record "
                           + "types and the cluster image consumed by the "
                           + "controller; the reverse edge would form a cycle.");

    @ArchTest
    static final ArchRule metadata_must_not_depend_on_coordinator =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.metadata..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the metadata module is a primitive used "
                           + "by the coordinator; the reverse edge would form "
                           + "a cycle.");

    @ArchTest
    static final ArchRule controller_must_not_depend_on_coordinator =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.controller..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the KRaft controller manages cluster-"
                           + "wide state; the group/transaction coordinator "
                           + "is broker-local. The two are independent state "
                           + "machines.");

    @ArchTest
    static final ArchRule coordinator_must_not_depend_on_controller =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.coordinator..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
                    .allowEmptyShould(true)
                    .because("INFERRED: KIP-500 separation between broker-local "
                           + "and controller-global concerns.");

    @ArchTest
    static final ArchRule tools_must_not_depend_on_trogdor =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.tools..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.trogdor..")
                    .allowEmptyShould(true)
                    .because("INFERRED: kafka-tools must not bundle Trogdor "
                           + "agents; both are independent operational "
                           + "utilities.");

    @ArchTest
    static final ArchRule trogdor_must_not_depend_on_tools =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.trogdor..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
                    .allowEmptyShould(true)
                    .because("INFERRED: Trogdor must not bundle administrative "
                           + "CLI tooling.");

    // =========================================================================
    // Layer-non-emptiness sentinel (F-11)
    // =========================================================================
    //
    // withOptionalLayers(true) masks a typo in any layer glob (the layer
    // becomes empty and access matrix silently disabled). This rule
    // back-stops by failing if NONE of the major layers can be populated.
    @ArchTest
    static final ArchRule layers_are_non_empty_after_classpath_setup =
            classes()
                    .that().resideInAnyPackage(
                            "org.apache.kafka.common..",
                            "org.apache.kafka.streams..",
                            "org.apache.kafka.clients..",
                            "org.apache.kafka.connect..",
                            "org.apache.kafka.server..",
                            "org.apache.kafka.metadata..",
                            "org.apache.kafka.tools..")
                    .should().bePublic()
                    .orShould().bePackagePrivate()
                    .because("F-11: the layered rule uses withOptionalLayers"
                           + "(true), which masks empty layers caused by a "
                           + "typo or a missing Maven dependency. This rule "
                           + "fails noisily when NONE of the major layers "
                           + "can be populated, signalling a build-config "
                           + "regression of the F-01 / F-05 shape.");

    // =========================================================================
    // What was deleted (and why)
    // =========================================================================
    //
    // streams_uses_only_kafka_client_libraries
    //     -> replaced by streams_must_not_depend_on_broker_internals (F-03,
    //        F-04). The original used onlyDependOnClassesThat() with a too-
    //        narrow allow-list and produced false positives against the
    //        published kafka-streams JAR (no Jackson, no compression codecs).
    //
    // tooling_must_not_depend_on_broker_internals
    //     -> deleted (F-06). Subsumed by Server.mayOnlyBeAccessedByLayers
    //        ("Server") and by Tooling/Shell.mayOnlyAccessLayers omitting
    //        Server.
    //
    // tools_must_not_depend_on_shell, shell_must_not_depend_on_tools,
    // trogdor_must_not_depend_on_shell, shell_must_not_depend_on_trogdor
    //     -> deleted (F-07). After Shell is split out of Tooling into its
    //        own layer, these are subsumed by the layered rule (Shell is
    //        not in Tooling.mayOnlyAccessLayers, and vice versa).
}
```

After applying both patches, run, in this order:

1. `mvn validate` — confirms the POM parses (proves F-01 fixed).
2. `mvn dependency:resolve` — confirms every artefact resolves (proves F-05 fixed).
3. `mvn test` — runs the rule suite against published Kafka 3.7.0 bytecode.

Only step 3 produces architecture-level diagnostics. If any rule fails after step 3, that is a real finding to investigate (not a build-infra problem of the F-01 / F-05 shape).

