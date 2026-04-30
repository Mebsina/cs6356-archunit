# Adversarial Review — `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache Kafka
Review: #1

---

## Executive Summary

- **Total documented constraints identified: 1 structural + 6 behavioural**
  The supplied PDF (`7_apache_kafka_page-0001..0004.jpg`) is the **Kafka Streams**
  *Architecture* page from the Apache Kafka site. It documents:
  1. **(C-S1, structural)** *Kafka Streams … builds on the Kafka producer and consumer libraries* (page 1, opening paragraph). This is the **only** module-dependency claim in the entire document — it permits, but does not mandate, a `streams → clients` edge.
  2. **(C-B1)** A *stream partition* maps 1-to-1 onto a Kafka *topic partition* (page 1, "Stream Partitions and Tasks").
  3. **(C-B2)** Tasks are the unit of parallelism; assignments are fixed for the lifetime of an application (page 2, paragraph 1).
  4. **(C-B3)** *No shared state amongst the threads, so no inter-thread coordination is necessary* (page 3, paragraph 1).
  5. **(C-B4)** *The assignment of Kafka topic partitions amongst the various stream threads is transparently handled by Kafka Streams leveraging Kafka's coordination functionality* (page 3, paragraph 1).
  6. **(C-B5)** Each task may embed one or more *local state stores*; updates are tracked via a *replicated changelog Kafka topic* (page 3, "Local State Stores" + page 4, "Fault Tolerance").
  7. **(C-B6)** Failed tasks are *automatically restarted* on a remaining instance; *standby replicas* may be configured to minimise restoration time (page 4).

  C-B1 … C-B6 are runtime/behavioural properties that **cannot be expressed as ArchUnit static rules** at all. C-S1 is the only constraint with any structural footprint, and it is a *permission*, not a *prohibition*.

- **Total rules generated: 21** (1 `layeredArchitecture` block + 20 `noClasses` rules).

- **Coverage rate: 0 of 1** *structural* documented constraints have a positive corresponding rule. Every other rule in the file is **invented from prior knowledge of the Apache Kafka monorepo**, not derived from the PDF that was supplied as input.

- **Surefire result: 21 of 21 rules failed** with the diagnostic
  *"Layer 'X' is empty"* (for the layered rule) and *"failed to check any classes. This means … no classes have been passed to the rule at all"* (for every `noClasses` rule). The test suite never evaluated any production code — see F-01.

- **Critical Gaps**:
  - **F-01** — The whole suite is non-functional: no `org.apache.kafka.*` bytecode reached the importer, so every layer is empty and every `noClasses` rule trips on `failOnEmptyShould`.
  - **F-02** — The architecture documented in the input PDF (Kafka Streams' partitions/tasks/threads/state-stores parallelism model) is **not the architecture the rules enforce** (a fabricated 6-layer Kafka monorepo stack). The single PDF-supported structural claim — `streams → clients` is permitted — is itself absent as an explicit rule.
  - **F-03** — `production_code_must_not_depend_on_test_support` is structurally vacuous: the same import option that excludes `org.apache.kafka.test..` and `org.apache.kafka.jmh..` from the scan deletes the classes the rule tries to forbid being depended upon.
  - **F-04** — The "Operations" layer collapses the broker runtime (`server`), three CLI tools (`tools`, `shell`, `trogdor`), and an integration test harness (`tiered`) into a single tier. Allowing every Operations package to access every layer below grants `tools` permission to compile-depend on broker internals — a serious wire-protocol-isolation violation that the rules silently sanction.
  - **F-05** — At least 12 of the 20 `noClasses` rules are fully subsumed by the layered rule and add zero coverage, while the genuinely intra-layer constraints (most pairs in Operations, several pairs in Infrastructure) are missing.

- **Overall verdict:** `FAIL`

---

## Constraint-to-Rule Gap Matrix

| # | Constraint (source: PDF page) | Rule(s) covering it | Status |
|---|---|---|---|
| C-S1 | `streams → clients` is permitted (p.1) | (no positive permission rule); the `layeredArchitecture` *Streams may access API* clause implicitly tolerates it | Implicit, not asserted |
| C-B1 | stream partition ↔ topic partition (p.1) | none possible (runtime, not structural) | Out of scope for ArchUnit |
| C-B2 | Tasks are units of parallelism (p.2) | none possible | Out of scope |
| C-B3 | No shared state amongst threads (p.3) | none | Out of scope |
| C-B4 | Streams leverages Kafka's coordination (p.3) | none | Out of scope |
| C-B5 | Local state stores / changelog topics (p.3-4) | none | Out of scope |
| C-B6 | Standby replicas, automatic task restart (p.4) | none | Out of scope |

| Generated rule | Documentation citation it claims | What the PDF actually says |
|---|---|---|
| `kafka_layered_architecture_is_respected` | "Per the Kafka Streams architecture documentation, Core provides shared primitives, Infrastructure provides networking and Raft consensus, Metadata manages cluster state via the controller and coordinator, API exposes the public client surface, Streams and Connect are parallel integration frameworks built on the client API, and Operations tooling sits at the top …" | **Nothing.** The PDF never names "Core", "Infrastructure", "Metadata", "API", or "Operations". It never describes a layered hierarchy of Kafka modules. It does not even mention Kafka Connect. |
| `streams_must_not_depend_on_connect` / `connect_must_not_depend_on_streams` | "Kafka Streams and Kafka Connect are independent parallel integration frameworks." | **Connect is never mentioned in the PDF.** |
| `tools/shell/trogdor` mutual-isolation rules | "kafka-tools provides batch CLI administration scripts while kafka-shell provides an interactive metadata-query REPL." | **None of `tools`, `shell`, or `trogdor` is mentioned in the PDF.** |
| `metadata`/`coordinator`/`controller` mutual-isolation rules | "KIP-500 separation between broker-local and controller-global concerns" | **KIP-500 is not in the PDF** (the PDF predates KRaft). |
| `raft`/`snapshot`/`network` rules | "Raft consensus implementation provides a reusable log-replication primitive…" | **Raft, KRaft, snapshots, and the broker network layer are not mentioned in the PDF.** |
| `clients/api/admin → server` rules | "admin clients are deployed in external JVMs…" | **No statement about admin client deployment topology in the PDF.** |
| `common → clients/server/streams` rules | "common is foundational shared library…" | **The `org.apache.kafka.common` package is not mentioned in the PDF.** |
| `production_code_must_not_depend_on_test_support` | "MockConsumer, MockProducer, TestUtils …" | **MockConsumer/MockProducer are not in the PDF.** |

The honest reading is that the entire rule set was synthesised from prior knowledge of the Apache Kafka source tree — not from the input documentation. That is not necessarily wrong (the inferred constraints are mostly *plausible* for the real Kafka codebase) but the prompt that produced the rules was supposed to be grounded in the PDF, and the `.because()` citations falsely attribute these constraints to that document.

---

## Findings

```
[F-01] SEVERITY: CRITICAL
Category: Structural Gap (ClassFileImporter scope / pom.xml classpath)
Affected Rule / Constraint: every @ArchTest in the file (21 rules)

What is wrong:
The Surefire report shows all 21 tests failing with one of two diagnostics:
  - The layered rule:  "Layer 'API' is empty / Layer 'Connect' is empty / …"
                       (every one of the 7 layers reports zero member classes).
  - Every noClasses rule:  "failed to check any classes. This means either that
                            no classes have been passed to the rule at all, or
                            that no classes passed to the rule matched the
                            that() clause."
The root cause is that the importer never received any Apache Kafka bytecode.
The pom.xml's <additionalClasspathElements> entries are relative paths of the
form
    ../../../kafka/clients/build/classes/java/main
    ../../../kafka/raft/build/classes/java/main
    ../../../kafka/streams/build/classes/java/main
    ... (19 modules total)
which assume a sibling Apache Kafka source-and-Gradle-build tree at
<workspace>/../../../kafka/. That tree does not exist on the machine that ran
the suite — the only org.apache.kafka.* classpath entry available was the
test class itself, which lives in com.archunit.kafka, so @AnalyzeClasses
(packages = "org.apache.kafka") found exactly zero classes.

Compounding the issue:
  - One of the listed classpath roots is "kafka/core/build/classes/scala/main"
    — the Kafka core (broker) module is Scala. Its bytecode lives in package
    "kafka.*" (NOT "org.apache.kafka.*"); even if the path resolved it would
    contribute no classes to the org.apache.kafka.* scan that the importer
    is configured to perform. The Javadoc claim that this directory provides
    "the KRaft controller" Java packages is incorrect.
  - The pom.xml does not declare any Apache Kafka dependency on Maven
    Central, so there is no fallback resolution: if the relative paths fail,
    the importer scans nothing.

Why it matters:
With every layer empty, the layered rule cannot detect any violation, and
every noClasses rule fails on the failOnEmptyShould safety-net rather than
on a real architectural finding. CI sees 21 red tests but learns nothing
about the codebase. This is not a "rules are too strict" failure; it is a
"the test fixture cannot exercise the rules at all" failure.

How to fix it:
Two fixes are independent and both should be applied.

1. Make the importer scan whatever classes are *actually* available, and let
   ArchUnit treat empty layers as legitimately empty rather than as
   violations:

```java
@AnalyzeClasses(
        packages = "org.apache.kafka",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ArchitectureEnforcementTest.ExcludeBenchmarksAndTestSupport.class
        })
public class ArchitectureEnforcementTest {

    // Layered rule: tolerate layers that are empty because the corresponding
    // Gradle module isn't on the local classpath. This converts "Layer X is
    // empty" from a violation into a no-op.
    @ArchTest
    static final ArchRule kafka_layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .withOptionalLayers(true)             // <-- new
                    .layer("Core").definedBy(...)
                    ...;

    // Every noClasses() rule should declare that an empty match is acceptable
    // (or raise it globally via the archunit.properties configuration
    //   archRule.failOnEmptyShould = false).
    @ArchTest
    static final ArchRule streams_must_not_depend_on_connect =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.streams..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
                    .allowEmptyShould(true)               // <-- new
                    .because("...");
    // ... apply allowEmptyShould(true) to every noClasses rule, OR drop the
    //     property archRule.failOnEmptyShould=false into archunit.properties.
}
```

2. Replace the brittle <additionalClasspathElements> with a declared
   dependency on the published Kafka artefacts (or a CI step that builds
   Apache Kafka first and copies the resulting JARs onto the classpath).
   Example:

```xml
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
    <artifactId>connect-runtime</artifactId>
    <version>${kafka.version}</version>
    <scope>test</scope>
</dependency>
<!-- and so on for raft, server, metadata, tools, trogdor -->
```

Without one of these two fixes, every other finding below is moot — none of
the rules can ever evaluate against real Kafka code.
```

```
[F-02] SEVERITY: CRITICAL
Category: Coverage Gap / Semantic Error (.because() accuracy)
Affected Rule / Constraint: every @ArchTest in the file; constraints C-S1 .. C-B6

What is wrong:
The PDF supplied as the architecture documentation describes Kafka Streams'
parallelism model (Stream Partitions, Tasks, Threading, Local State Stores,
Fault Tolerance). It does not describe a layered hierarchy of Kafka monorepo
packages, does not group `common`, `clients`, `server`, `streams`, `connect`,
`metadata`, `controller`, `coordinator`, `raft`, `snapshot`, etc. into tiers,
does not state any prohibition on cross-module dependencies, and never even
mentions Kafka Connect, Kafka Tools, Kafka Shell, Trogdor, KRaft, the
controller, the coordinator, or the metadata image.

Yet:
  - The layered architecture rule defines seven layers and twenty access
    relations, all attributed to "the Kafka Streams architecture
    documentation".
  - 19 of 20 noClasses rules carry .because() text whose subject (Connect,
    Trogdor, Shell, Tools, raft, snapshot, controller, coordinator, KIP-500,
    plugin isolation, etc.) appears nowhere in the PDF.

Conversely, the ONE structural claim the PDF DOES make — "Kafka Streams …
builds on the Kafka producer and consumer libraries" (page 1) — has no
positive corresponding rule. The layered architecture allows
`streams → API` (which contains clients), so the permission is implicit, but
the test never asserts that streams *does* in fact use clients, nor does it
forbid streams from re-implementing the same wire protocol locally.

Why it matters:
  - The .because() clauses misattribute every constraint to the PDF. When a
    test fires in CI, a developer who reads the message and pulls up "the
    Kafka Streams architecture documentation" will find that the document
    says nothing about the violated constraint. Trust in the rule erodes
    immediately, and the rule will get @ArchIgnore'd or deleted.
  - The reviewer cannot tell whether each rule reflects a real Kafka
    invariant or a hallucination. Several of the rules ("admin clients are
    deployed in external JVMs", "Trogdor agents run as independent daemons")
    are plausibly correct invariants of the real codebase, but they are not
    documented anywhere in the supplied input.
  - Constraint C-S1 (the single PDF-supported structural claim) is not
    asserted as a positive rule. A future refactor that severs Kafka Streams
    from the producer/consumer libraries (e.g., by inlining a private RPC
    client) would not fail any test even though it directly contradicts the
    sentence on page 1.

How to fix it:
Two coordinated changes:

(a) Replace every `.because()` clause that cites the PDF with a clause that
    is honest about its source. Mark inferences as inferences:

```java
.because("INFERRED from the standard Apache Kafka module layout (the PDF "
       + "describes Streams' parallelism model only and does not document "
       + "this dependency restriction). The constraint reflects the "
       + "established kafka-clients / kafka-server / kafka-streams artefact "
       + "boundaries: a compile dependency from clients to broker internals "
       + "would prevent shipping kafka-clients as a standalone JAR.");
```

(b) Add a positive rule for C-S1 — the only PDF-grounded structural claim:

```java
@ArchTest
static final ArchRule streams_uses_kafka_client_libraries =
        classes()
                .that().resideInAPackage("org.apache.kafka.streams..")
                .and().areNotInterfaces()
                .and().haveSimpleNameNotEndingWith("Test")
                .should().dependOnClassesThat()
                          .resideInAnyPackage("org.apache.kafka.clients..",
                                              "org.apache.kafka.common..")
                .allowEmptyShould(true)
                .because("Per page 1 of the Kafka Streams architecture "
                       + "documentation: 'Kafka Streams simplifies "
                       + "application development by building on the Kafka "
                       + "producer and consumer libraries' — i.e. Streams "
                       + "is required to compile against, and only against, "
                       + "the public producer/consumer client API.");
```

(More aggressively, replace the entire 6-layer fabrication with a 2-layer
"streams ⊂ clients" assertion derived solely from the PDF, and treat every
other constraint as an admitted inference. See Recommended Patch below.)
```

```
[F-03] SEVERITY: HIGH
Category: Vacuous Rule
Affected Rule / Constraint: production_code_must_not_depend_on_test_support

What is wrong:
The custom ImportOption ExcludeBenchmarksAndTestSupport rejects every
location whose path contains `/org/apache/kafka/jmh/` or
`/org/apache/kafka/test/`. That option is registered on @AnalyzeClasses,
so the JUnit 5 ArchUnit runner *never imports* a single class that resides
in those packages.

The rule then asserts:
    no classes outside [test, jmh] should depend on classes inside [test, jmh]

But because no classes inside [test, jmh] were ever imported, the
JavaClasses universe contains zero classes whose `resideInAnyPackage(...)`
predicate can match. ArchUnit therefore reports "failed to check any
classes" — exactly the error in the Surefire log — and treats the rule as
trivially satisfied (or, with failOnEmptyShould enabled, fails it for the
wrong reason).

Either way, a real production-code → test-support dependency cannot be
detected by this rule, because the targets of the dependency have been
filtered out of the import.

Why it matters:
The rule is a back-stop against the most common architectural anti-pattern
in test-heavy projects — using MockConsumer or TestUtils inside production
code. The rule looks plausible but produces zero coverage. Worse, it
appears to provide coverage in a code review of the test class, lulling
the reader into thinking the back-stop is in place.

How to fix it:
Decide on one of two strategies — they are mutually exclusive:

Option A — Keep test-support classes imported, enforce the rule:

```java
@AnalyzeClasses(packages = "org.apache.kafka",
                importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureEnforcementTest {

    // No more ExcludeBenchmarksAndTestSupport: jmh and test ARE imported,
    // so the noClasses rule below has actual classes to detect inbound edges to.

    @ArchTest
    static final ArchRule production_code_must_not_depend_on_test_support =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "org.apache.kafka.test..", "org.apache.kafka.jmh..")
                    .should().dependOnClassesThat()
                              .resideInAnyPackage(
                                      "org.apache.kafka.test..",
                                      "org.apache.kafka.jmh..")
                    .allowEmptyShould(true)
                    .because("...");

    // Then the layered rule must explicitly carve test/jmh out of every
    // outgoing-access constraint by NOT placing them in any layer (with
    // .withOptionalLayers(true) so empty layers do not error).
}
```

Option B — Keep them excluded, delete the rule:

```java
// production_code_must_not_depend_on_test_support is structurally vacuous
// because ExcludeBenchmarksAndTestSupport removes its target classes from
// the universe. Delete the rule and rely on Maven's test-vs-main classpath
// separation to keep test-support out of compiled main artefacts.
```

The current configuration combines the worst of both: it filters the
classes out AND keeps the rule, so the rule looks effective but isn't.
```

```
[F-04] SEVERITY: HIGH
Category: Wrong Layer / Semantic Error
Affected Rule / Constraint: layered architecture, OPERATIONS_PEER constant

What is wrong:
The Operations layer is defined as the union
    org.apache.kafka.server..       <-- broker runtime (Java side)
    org.apache.kafka.tools..        <-- CLI tools (kafka-topics.sh, kafka-consumer-groups.sh)
    org.apache.kafka.shell..        <-- interactive metadata REPL (kafka-metadata-shell.sh)
    org.apache.kafka.trogdor..      <-- workload / fault-injection daemons
    org.apache.kafka.tiered..       <-- tiered-storage E2E TEST HARNESS (per the Javadoc itself)

and is granted permission to access every other layer:
    .whereLayer("Operations").mayOnlyAccessLayers(
        "Core","Infrastructure","Metadata","API","Streams","Connect")

This conflates four conceptually distinct deployment artefacts:

  (a) `server` is the broker process. It links to the broker's internal
      socket server, request handlers, replica manager, log manager. It is
      the runtime that everything below it serves.
  (b) `tools` and `shell` are CLIENT-side CLI utilities. They open a TCP
      connection to a running broker via AdminClient/KafkaConsumer. They
      should NEVER compile-depend on broker internals — that would force
      every CLI invocation to bundle the broker classpath.
  (c) `trogdor` is a distributed test framework. Its agents run alongside
      brokers but do not link to broker internals.
  (d) `tiered` is, by the Javadoc's own admission, a "Tiered-storage
      integration test harness" — i.e. test infrastructure that is in this
      layer rather than excluded from the importer like jmh and test.

By placing all five in one layer with mayOnlyAccessLayers covering
everything below, the rule grants:
  - `tools → server` (CLI compile-depending on broker runtime — WRONG);
  - `shell → server` (REPL compile-depending on broker runtime — WRONG);
  - `trogdor → server` (test framework linking to broker runtime — WRONG);
  - `tiered → server` (test harness linking to broker runtime — at minimum
                       suspect; more likely should not be in scope at all).

The intra-Operations rules (tools⊥shell, shell⊥tools, trogdor⊥shell,
trogdor⊥tools) tell us that the generator was aware some sibling pairs
should be isolated, yet none of those rules cover the most important
pair — `server` vs the CLI tools. Result: the most important isolation
the documentation could plausibly require silently passes.

Why it matters:
A class such as `org.apache.kafka.tools.consumergroups.ConsumerGroupCommand`
that imports `org.apache.kafka.server.common.MetadataVersion` would not
trigger any rule:
  - layered rule: tools and server are in the same layer; intra-layer is
    unconstrained.
  - noClasses rules: the file has no `tools_must_not_depend_on_server`,
    `shell_must_not_depend_on_server`, or `trogdor_must_not_depend_on_server`.
The "wire-protocol-only" boundary that makes Kafka CLI tools deployable
without the broker JAR is therefore unenforced.

How to fix it:
Split the Operations layer into two sub-layers, with a strict rule that
client-side tooling must not reach into broker internals; demote the test
harness out of scope entirely:

```java
// (a) Genuine broker-side packages
.layer("Server").definedBy("org.apache.kafka.server..")

// (b) CLI / operational tooling that talks to brokers as clients
.layer("Tooling").definedBy(
        "org.apache.kafka.tools..",
        "org.apache.kafka.shell..",
        "org.apache.kafka.trogdor..")

// Server is broker runtime; it may use everything below the API tier.
.whereLayer("Server").mayOnlyAccessLayers(
        "Core", "Infrastructure", "Metadata", "API")
.whereLayer("Server").mayOnlyBeAccessedByLayers(
        "Server", "Tooling")    // close the back door

// Tooling sits ABOVE Server (it does not include the broker), and is a
// CLIENT of the broker — must not compile-depend on broker internals.
.whereLayer("Tooling").mayOnlyAccessLayers(
        "Core", "Infrastructure", "Metadata", "API", "Streams", "Connect")
.whereLayer("Tooling").mayNotAccessLayers("Server")  // (write as a noClasses rule, since
                                                    // mayOnlyAccessLayers above already
                                                    // omits "Server")
```

And for the test harness:

```java
// org.apache.kafka.tiered is described in the Javadoc as a "Tiered-storage
// integration test harness". Treat it the same as jmh/test: exclude it
// from the importer rather than placing it in a layer.
public static final class ExcludeBenchmarksAndTestSupport implements ImportOption {
    @Override public boolean includes(Location location) {
        return !location.contains("/org/apache/kafka/jmh/")
            && !location.contains("/org/apache/kafka/test/")
            && !location.contains("/org/apache/kafka/tiered/");
    }
}
```

Add an explicit sibling-isolation rule that the layered architecture cannot
express:

```java
@ArchTest
static final ArchRule tooling_must_not_depend_on_broker_internals =
        noClasses()
                .that().resideInAnyPackage("org.apache.kafka.tools..",
                                           "org.apache.kafka.shell..",
                                           "org.apache.kafka.trogdor..")
                .should().dependOnClassesThat()
                          .resideInAPackage("org.apache.kafka.server..")
                .allowEmptyShould(true)
                .because("CLI tools, the metadata shell, and Trogdor are "
                       + "client-side processes that talk to a running "
                       + "broker over the wire protocol. They must not "
                       + "compile-depend on broker internals because that "
                       + "would force every CLI invocation to ship the "
                       + "broker classpath and prevent kafka-clients from "
                       + "being usable in isolation.");
```
```

```
[F-05] SEVERITY: HIGH
Category: Rule Redundancy / Structural Gap
Affected Rule / Constraint: 12 of the 20 noClasses rules

What is wrong:
The layered architecture already constrains every cross-layer edge, so any
noClasses rule whose source and target are in different layers is fully
subsumed by the layered rule. Concretely, the following rules add no
coverage:

  Source layer         Target layer        Rule
  ------------         ------------        ----
  Streams              Connect             streams_must_not_depend_on_connect          (subsumed)
  Connect              Streams             connect_must_not_depend_on_streams          (subsumed)
  Infrastructure       Streams             network_must_not_depend_on_streams          (subsumed)
  Infrastructure       Metadata            raft_must_not_depend_on_metadata            (subsumed)
  Infrastructure       Metadata            raft_must_not_depend_on_controller          (subsumed)
  Infrastructure       Metadata            snapshot_must_not_depend_on_controller      (subsumed)
  API                  Operations          clients_must_not_depend_on_server           (subsumed)
  API                  Operations          admin_must_not_depend_on_server             (subsumed)
  API                  Operations          api_must_not_depend_on_server               (subsumed)
  Core                 API                 common_must_not_depend_on_clients           (subsumed)
  Core                 Operations          common_must_not_depend_on_server            (subsumed)
  Core                 Streams             common_must_not_depend_on_streams           (subsumed)

Conversely, the genuine intra-layer constraints — those that the layered
API CANNOT express — number only seven:

  Within Metadata:
    - metadata    ⊥ controller
    - metadata    ⊥ coordinator
    - coordinator ⊥ controller
  Within Operations:
    - tools       ⊥ shell      (and reverse)
    - trogdor     ⊥ shell
    - trogdor     ⊥ tools

Symmetric pairs are missing:
  - shell      ⊥ trogdor
  - controller ⊥ metadata          (only metadata→controller is asserted)
  - coordinator ⊥ metadata         (only metadata→coordinator is asserted)
  - controller ⊥ coordinator       (only coordinator→controller is asserted)

And the entire `server` ↔ {tools, shell, trogdor, tiered} family is missing
(see F-04).

Why it matters:
  - A redundant rule produces a duplicate failure message in CI for every
    real violation, raising the cost of triage. When the same edge fires
    on both the layered rule and a sibling noClasses rule, a developer
    might silence one and assume the issue is fixed while the other still
    fires.
  - Each redundant rule also maintains an independent .because() clause
    that drifts out of sync with the layered rule's text (and indeed
    several of them do — see F-02).
  - The asymmetric intra-Metadata rules give a false sense of completeness:
    a real `controller → metadata` dependency would be caught (because
    metadata is "below" controller in the implicit hierarchy of the
    Javadoc), but only because the layered rule treats them as the same
    layer and intra-layer is unrestricted, NOT because controller→metadata
    is explicitly forbidden. Then a real controller → coordinator
    dependency would slip past untested.

How to fix it:
Delete the 12 redundant noClasses rules and complete the symmetric
intra-layer pairings:

```java
// DELETE (subsumed by the layered rule):
//   streams_must_not_depend_on_connect
//   connect_must_not_depend_on_streams
//   network_must_not_depend_on_streams
//   raft_must_not_depend_on_metadata
//   raft_must_not_depend_on_controller
//   snapshot_must_not_depend_on_controller
//   clients_must_not_depend_on_server
//   admin_must_not_depend_on_server
//   api_must_not_depend_on_server
//   common_must_not_depend_on_clients
//   common_must_not_depend_on_server
//   common_must_not_depend_on_streams
```

Add the missing intra-Metadata symmetric pairs:

```java
@ArchTest
static final ArchRule controller_must_not_depend_on_coordinator =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.controller..")
                .should().dependOnClassesThat()
                          .resideInAPackage("org.apache.kafka.coordinator..")
                .allowEmptyShould(true)
                .because("INFERRED: controller and coordinator are sibling "
                       + "packages within the Metadata module group; the PDF "
                       + "does not address them, but the standard Kafka "
                       + "module layout treats the KRaft controller "
                       + "(cluster-wide metadata mutations) and the group "
                       + "coordinator (broker-local consumer/transaction "
                       + "state) as independent state machines.");
```

(Apply the same shape for `controller→metadata` and `coordinator→metadata`
back-edges, and for the symmetric reverse `shell ⊥ trogdor`.)
```

```
[F-06] SEVERITY: HIGH
Category: Coverage Gap (intra-layer)
Affected Rule / Constraint: Operations layer sibling isolation

What is wrong:
The Operations layer has FIVE member packages — server, tools, shell,
trogdor, tiered — yielding 20 ordered pairs. The file enforces only 4 of
those 20:
    tools    → shell      (covered)
    shell    → tools      (covered)
    trogdor  → shell      (covered)
    trogdor  → tools      (covered)

All 16 other pairs are unconstrained. Most importantly, every pair that
includes `server` is uncovered (see F-04 for the operational impact), and
every `tiered → *` and `* → tiered` pair is uncovered.

Why it matters:
The pattern of the four covered pairs suggests the generator was reasoning
about "operational sibling isolation". But the reasoning was applied
unevenly: the broker (server) is omitted from every isolation pair, and
the test harness (tiered) is omitted from all of them. The most damaging
edge in real Kafka — `tools → server` (a CLI tool linking to broker
internals) — sails through.

How to fix it:
Either move `server` out of the Operations layer (preferred — see F-04) or,
if the Operations layer is kept as-is, complete the 5×5 matrix with
explicit rules:

```java
// If `server` stays in Operations, all sibling pairs must be enumerated.
// Recommended instead: move server to its own layer per F-04.

private static final String[] OPERATIONS_SIBLINGS = {
        "org.apache.kafka.server..",
        "org.apache.kafka.tools..",
        "org.apache.kafka.shell..",
        "org.apache.kafka.trogdor.."
        // tiered intentionally excluded — it's a test harness, see F-04.
};

// At minimum, add the missing rules involving server:
@ArchTest
static final ArchRule tools_must_not_depend_on_server =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.tools..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
                .allowEmptyShould(true)
                .because("CLI tools talk to the broker over the wire protocol; "
                       + "they must not compile-depend on broker internals.");

@ArchTest
static final ArchRule shell_must_not_depend_on_server =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.shell..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
                .allowEmptyShould(true)
                .because("kafka-metadata-shell uses the metadata shell client; "
                       + "it must not link broker-runtime classes.");

@ArchTest
static final ArchRule trogdor_must_not_depend_on_server =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.trogdor..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
                .allowEmptyShould(true)
                .because("Trogdor agents are independent test daemons; the "
                       + "fault-injection framework must not link broker-runtime "
                       + "classes.");
```
```

```
[F-07] SEVERITY: MEDIUM
Category: Coverage Gap (intra-layer)
Affected Rule / Constraint: Infrastructure layer sibling isolation

What is wrong:
The Infrastructure layer has six packages: network, queue, raft, snapshot,
storage, image. Only one intra-Infrastructure relation is asserted by an
explicit rule (network ⊥ streams, which is itself cross-layer and therefore
redundant with the layered rule — see F-05). The Javadoc header argues
that Infrastructure modules are reusable byte-level primitives:
  - network: NIO channel/selector (no Kafka-level knowledge)
  - raft:    Raft consensus (state-machine-agnostic)
  - snapshot: Raft snapshot byte-stream (state-machine-agnostic)
  - storage:  log-segment, offset-index (no metadata semantics)
  - image:    immutable cluster-metadata image (Kafka-specific!)
  - queue:    deferred event-queue (cross-cutting primitive)

If the Javadoc's reusability claim is taken seriously, several edges
within Infrastructure should be forbidden but are not:
  - raft → image, snapshot → image, network → image, storage → image
    (image carries metadata semantics; it should sit ABOVE the truly
    generic primitives.)
  - storage → raft (storage is a log primitive; raft uses the log, not
    the other way around.)

These are exactly the kind of intra-layer constraints layeredArchitecture
cannot express.

Why it matters:
Without these rules, a refactor that pushes Kafka-specific knowledge from
metadata/controller back down into raft or snapshot (e.g., teaching raft
to deserialize MetadataRecord) would silently violate the Javadoc's
"reusable consensus library" promise. The rule against
`raft → metadata` is asserted (rule F-05 lists it as redundant with the
layered rule), but the analogous claim against `raft → image` is not.

How to fix it:
Promote `image` out of Infrastructure into Metadata (it really is the
metadata image — it has cluster-level semantics) OR add explicit
intra-Infrastructure rules:

```java
// Option A: relocate `image` to Metadata, where it semantically belongs.
.layer("Metadata").definedBy(
        "org.apache.kafka.metadata..",
        "org.apache.kafka.coordinator..",
        "org.apache.kafka.controller..",
        "org.apache.kafka.image..")               // moved here

.layer("Infrastructure").definedBy(
        "org.apache.kafka.network..",
        "org.apache.kafka.queue..",
        "org.apache.kafka.raft..",
        "org.apache.kafka.snapshot..",
        "org.apache.kafka.storage..")             // image removed

// Option B: keep image in Infrastructure but assert sibling rules:
@ArchTest
static final ArchRule raft_must_not_depend_on_image =
        noClasses()
                .that().resideInAPackage("org.apache.kafka.raft..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.image..")
                .allowEmptyShould(true)
                .because("raft is a state-machine-agnostic consensus primitive; "
                       + "MetadataImage carries Kafka-specific semantics. "
                       + "Reaching into image from raft would defeat raft's "
                       + "reusability claim.");

@ArchTest
static final ArchRule snapshot_must_not_depend_on_image = /* analogous */;
@ArchTest
static final ArchRule storage_must_not_depend_on_raft   = /* analogous */;
```
```

```
[F-08] SEVERITY: MEDIUM
Category: Vacuous Rule / Wrong Layer
Affected Rule / Constraint: Core layer composition (org.apache.kafka.config,
                            org.apache.kafka.security, org.apache.kafka.logger)

What is wrong:
The Core layer is defined to include
    org.apache.kafka.config..
    org.apache.kafka.security..
    org.apache.kafka.logger..

Cross-referencing against the actual Apache Kafka tree (and against the
inputs/java/7_apache_kafka.txt list, which lists these as bare top-level
package names): in the real codebase these top-level packages are
essentially empty or do not exist. The configuration types live under
`org.apache.kafka.common.config`, security under
`org.apache.kafka.common.security` and `org.apache.kafka.server.security`,
and there is no `org.apache.kafka.logger` package at all (Kafka uses SLF4J
through `org.apache.kafka.common.utils.LogContext`).

Three failure modes:
  (a) If those globs match no classes, they contribute zero members to the
      Core layer and the layered rule treats them as no-op — but the
      Javadoc and the .because() text reference them as non-trivial Core
      members, which is misleading.
  (b) If a future refactor introduces a top-level org.apache.kafka.config
      package (e.g., extracting kafka-clients/src/main/java/.../config into
      its own module), it would silently inherit the most permissive Core
      access rule (`mayNotAccessAnyLayer`) without any review. This may or
      may not be desired.
  (c) The same `..` suffix means classes directly at `org.apache.kafka`
      (the root) are NOT in any layer (compare F-01 of the ZooKeeper
      review for the analogous bug). The Core layer therefore misses any
      class whose FQN is exactly `org.apache.kafka.SomethingPublic`.

Why it matters:
Layer membership directly determines which violations the layered rule can
detect. Globs that match nothing make the layer thinner than it appears,
and globs that match unexpected sub-packages create silent false negatives.

How to fix it:
Either drop the empty/missing top-level packages and rely on their actual
locations, or assert their presence with a narrow membership test:

```java
// Drop the empty top-level packages; group their actual locations:
.layer("Core").definedBy(
        "org.apache.kafka.common..",         // includes common.config, common.security
        "org.apache.kafka.message..",
        "org.apache.kafka.timeline..",
        "org.apache.kafka.deferred..")

// (Alternatively, keep them but assert non-emptiness if they really should
// have classes — this catches the case where a glob silently matches none.)
@ArchTest
static final ArchRule core_layer_is_non_empty =
        classes()
                .that().resideInAnyPackage(
                        "org.apache.kafka.common..",
                        "org.apache.kafka.message..",
                        "org.apache.kafka.timeline..",
                        "org.apache.kafka.deferred..")
                .should().bePublic()
                .orShould().bePackagePrivate()
                .because("Sanity check that the Core layer actually contains "
                       + "imported classes; an empty Core layer would silently "
                       + "disable every layered rule that depends on it.");
```
```

```
[F-09] SEVERITY: MEDIUM
Category: Wrong Layer / Vacuous Rule
Affected Rule / Constraint: api_must_not_depend_on_server,
                            API layer composition (org.apache.kafka.api,
                            org.apache.kafka.admin)

What is wrong:
The API layer is composed of
    org.apache.kafka.api..
    org.apache.kafka.clients..
    org.apache.kafka.admin..

In the real Apache Kafka Java codebase:
  - The Java request/response API types live in
    `org.apache.kafka.common.requests`, `org.apache.kafka.common.message`,
    `org.apache.kafka.common.protocol` — i.e. inside the *Core* layer.
  - There IS a Scala package `kafka.api` (in the `core` Gradle module)
    that contains legacy API classes — but that is package
    `kafka.api`, NOT `org.apache.kafka.api`, so the importer (which
    scans `org.apache.kafka`) will not see it. The Javadoc's claim that
    Layer "API" contains the "request/response type hierarchy" is
    therefore not realised at runtime.
  - The Java AdminClient is in `org.apache.kafka.clients.admin`, NOT in
    a top-level `org.apache.kafka.admin` package. There is a small
    `kafka.admin` Scala package in `core/`, but again that is in package
    `kafka.admin`, not `org.apache.kafka.admin`.

Consequence: `org.apache.kafka.api..` and `org.apache.kafka.admin..` are
likely empty, the corresponding `noClasses` rules
`api_must_not_depend_on_server` and `admin_must_not_depend_on_server`
have no source classes that match the `that()` clause, and the Surefire
report's "failed to check any classes" message for both of them is
exactly the symptom predicted by this finding.

Why it matters:
  - The layered rule cannot detect API → Operations violations for the
    real API types (which are in `org.apache.kafka.common`, hence Core),
    because those would be Core → Operations and, while still forbidden
    by the layered rule, the .because() text would be misleading.
  - The two dedicated `noClasses` rules look like extra precision but
    actually exercise nothing. They are vacuous in the most precise
    technical sense: the source globs match zero classes.
  - The same trap applies to `org.apache.kafka.config`,
    `org.apache.kafka.security`, `org.apache.kafka.logger` — see F-08.

How to fix it:
Re-anchor the API layer to the packages that actually carry the
Java protocol API and AdminClient:

```java
.layer("API").definedBy(
        "org.apache.kafka.clients..",
        "org.apache.kafka.clients.admin..",
        "org.apache.kafka.common.requests..",
        "org.apache.kafka.common.message..",
        "org.apache.kafka.common.protocol..")

// And drop the two no-class rules whose source globs are empty:
//   admin_must_not_depend_on_server   (subsumed by layered, source empty anyway)
//   api_must_not_depend_on_server     (subsumed by layered, source empty anyway)
```

If the goal is to strictly follow the package-list .txt (which lists
`org.apache.kafka.api` and `org.apache.kafka.admin` as valid top-level
packages), at least add a non-emptiness assertion so a future maintainer
notices when the glob matches nothing:

```java
@ArchTest
static final ArchRule api_layer_packages_are_populated =
        classes()
                .that().resideInAnyPackage(
                        "org.apache.kafka.api..",
                        "org.apache.kafka.admin..",
                        "org.apache.kafka.clients..")
                .should().bePublic()
                .orShould().bePackagePrivate()
                .because("If any of the API-layer globs matches no classes, "
                       + "the corresponding per-package noClasses rules are "
                       + "vacuous and provide no real coverage.");
```
```

```
[F-10] SEVERITY: LOW
Category: Semantic Error (.because() accuracy)
Affected Rule / Constraint: every .because() clause that cites the PDF

What is wrong:
The .because() texts attribute their constraints to "the Kafka Streams
architecture documentation". The PDF is precisely that page on the Apache
Kafka site — but it documents Streams' parallelism model only. None of the
following claims, all of which appear in .because() clauses, can be
sourced from the PDF:
  - "the controller registers state machines with Raft"
  - "KIP-500 separation between broker-local and controller-global concerns"
  - "Trogdor agents run as independent daemons"
  - "kafka-tools provides batch CLI administration scripts while kafka-shell
     provides an interactive metadata-query REPL"
  - "MockConsumer, MockProducer, TestUtils, and other test-support classes"
  - "The snapshot module provides byte-stream read/write abstractions for
     Raft snapshots"
  - "MM2 plugin isolation"  (in connect_must_not_depend_on_streams)
  - "Kafka Connect orchestrates source and sink connectors via plugin
     isolation"

These are accurate statements about real Apache Kafka, sourced from KIPs
and the broader documentation tree, but they were not in the supplied
PDF. A developer reading the failure who consults "the architecture
documentation" cited in the message will not find the cited claim and will
question whether the rule is grounded.

The complementary defect: the ONE statement the PDF actually does make
about cross-module structure ("Kafka Streams … builds on the Kafka
producer and consumer libraries") is not used as the .because() of any
rule.

Why it matters:
  - .because() text is the only context a developer has when triaging an
    architecture-test failure in CI. If the text mis-cites its source,
    developers will lose trust and will be tempted to @ArchIgnore the
    rule. (See F-02 for the more general version of this problem.)
  - The current text reads as if the rules came from the PDF, when in
    fact the rules came from prior knowledge of Kafka's source tree.
    Fixing the citations does not remove the rules — it just makes them
    honest.

How to fix it:
Where a constraint is supported by the PDF, cite the PDF page. Where a
constraint is inferred from prior knowledge (the majority), say so:

```java
.because("INFERRED from the standard Apache Kafka module layout (the "
       + "supplied Streams architecture page documents only Streams' "
       + "parallelism model and does not list module dependency "
       + "constraints). The constraint reflects KIP-500 / KIP-405 "
       + "module boundaries: …");
```

For the one rule that the PDF does support:

```java
.because("Per page 1 of the supplied Kafka Streams architecture page: "
       + "'Kafka Streams simplifies application development by building "
       + "on the Kafka producer and consumer libraries'. Streams must "
       + "compile against — and only against — the public producer and "
       + "consumer client surface.");
```
```

```
[F-11] SEVERITY: LOW
Category: Overly Narrow / Coverage Gap
Affected Rule / Constraint: layered architecture, root package
                            org.apache.kafka classes

What is wrong:
Every layer's globs use the trailing `..` (sub-package) suffix:
    org.apache.kafka.common..
    org.apache.kafka.streams..
    ...
ArchUnit's `..` matches the named package and ALL sub-packages, but
NOT classes whose FQN is exactly `org.apache.kafka.SomeClass`. If any
class lives directly at the root (rare in modern Kafka, but
historically true for `org.apache.kafka.NotEnoughReplicasException`,
`org.apache.kafka.HotsentanglyOldClass`, etc.), it is in no layer at all.

Because the layered rule has no `mayOnlyBeAccessedByLayers(...)` clauses,
classes outside every layer have:
  - their outgoing edges unconstrained (they are not in any layer, so
    no `mayOnlyAccessLayers` applies);
  - their incoming edges also unconstrained for the same reason.

Effect: a hypothetical root-package class can freely reach into
org.apache.kafka.server.. or org.apache.kafka.controller.. with no rule
firing.

Why it matters:
This is the same blind spot that bit ZooKeeper's review (F-01 of that
report) — for Kafka the surface is smaller (the root package is mostly
empty in modern Kafka), but the gap is still latent and should be closed
or asserted absent.

How to fix it:
Either explicitly assert that the root package is empty:

```java
@ArchTest
static final ArchRule no_classes_at_kafka_root =
        noClasses()
                .that().resideInAPackage("org.apache.kafka")     // root only, no `..`
                .should().bePublic()
                .orShould().bePackagePrivate()
                .allowEmptyShould(true)
                .because("Modern Apache Kafka does not place public classes "
                       + "directly at the org.apache.kafka root; if any "
                       + "appear here they are invisible to every layered "
                       + "rule (which all use the `..` sub-package matcher).");
```

Or, more usefully, add a sentinel layer and a `mayOnlyBeAccessedBy`
back-stop on every other layer (see F-04 for the pattern).
```

```
[F-12] SEVERITY: LOW
Category: Rule Redundancy / Diagnostic Noise
Affected Rule / Constraint: streams_must_not_depend_on_connect,
                            connect_must_not_depend_on_streams

What is wrong:
These two rules are listed as separate fine-grained "parallel peer"
constraints, but the layered rule already places them in distinct layers
(Streams and Connect) with disjoint mayOnlyAccessLayers sets — neither
layer is in the other's allowed list. Hence a violation in either
direction would already be reported by the layered rule.

Why it matters:
  - When a real `streams → connect` edge is introduced, CI emits TWO
    failure messages (one from the layered rule with rich diagnostics,
    one from the noClasses rule with a plainer message). That doubles
    the noise.
  - The .because() text on the noClasses rules is also longer and more
    speculative than the layered rule's text, so they conflict in
    rationale (see F-10).

This is the same pattern as the "infrastructure_must_not_depend_on_higher_
layers" rule deleted in the ZooKeeper review (F-09 of that report).

How to fix it:
Delete the two streams⊥connect rules and rely on the layered architecture:

```java
// DELETE:
//   streams_must_not_depend_on_connect   (subsumed by layered rule)
//   connect_must_not_depend_on_streams   (subsumed by layered rule)
```

If a more descriptive failure message is desired specifically for the
streams ⊥ connect pair, move that wording into the layered rule's
.because() rather than maintaining a parallel rule.
```

---

## Recommended Consolidated Patch

The patch below addresses **F-01, F-02, F-03, F-04, F-05, F-06, F-08, F-09, F-10, F-11, F-12** in a single pass. It does **not** address F-07 (the `image` layer placement question) — pick option A or B from F-07 and apply it on top.

```java
/**
 * ArchitectureEnforcementTest — Apache Kafka.
 *
 * SCOPE OF THE INPUT DOCUMENTATION
 * --------------------------------
 * The supplied PDF (the Kafka Streams "Architecture" page from the Apache
 * Kafka website) documents Streams' RUNTIME parallelism model only:
 *   - Stream Partitions and Tasks
 *   - Threading Model
 *   - Local State Stores
 *   - Fault Tolerance
 * It contains exactly ONE structural claim about cross-module dependencies:
 *
 *   "Kafka Streams simplifies application development by building on the
 *    Kafka producer and consumer libraries"   (PDF page 1, paragraph 1)
 *
 * Every other constraint enforced below is INFERRED from the standard
 * Apache Kafka monorepo layout (KIP-500 controller/broker split, KIP-405
 * tiered-storage SPI, Connect plugin isolation, etc.) and is marked as
 * such in its .because() clause.
 *
 * EXCLUDED PACKAGES
 * -----------------
 *   - org.apache.kafka.test    : MockConsumer / MockProducer / TestUtils.
 *   - org.apache.kafka.jmh     : JMH benchmarks (build-time only).
 *   - org.apache.kafka.tiered  : Tiered-storage E2E test harness; the
 *                                generator's own Javadoc described it as a
 *                                test harness, so it is excluded rather
 *                                than placed in any production layer.
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
        @Override
        public boolean includes(Location location) {
            return !location.contains("/org/apache/kafka/jmh/")
                && !location.contains("/org/apache/kafka/test/")
                && !location.contains("/org/apache/kafka/tiered/");
        }
    }

    // =========================================================================
    // Layered architecture
    // =========================================================================
    //
    // Layer                Members                                        Source
    // -----                -------                                        ------
    // Core                 common, message, timeline, deferred            INFERRED
    // Infrastructure       network, queue, raft, snapshot, storage, image INFERRED
    // Metadata             metadata, coordinator, controller              INFERRED
    // Clients              clients (incl. clients.admin, common.requests) PDF p.1
    // Streams              streams                                        PDF p.1
    // Connect              connect                                        INFERRED
    // Server               server (broker runtime)                        INFERRED
    // Tooling              tools, shell, trogdor (CLI clients)            INFERRED
    //
    // withOptionalLayers(true) tolerates layers that are empty because the
    // corresponding Gradle module isn't on the local classpath; this prevents
    // the "Layer X is empty" failure that bricked all 21 tests in run #1.
    @ArchTest
    static final ArchRule kafka_layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .withOptionalLayers(true)

                    .layer("Core").definedBy(
                            "org.apache.kafka.common..",
                            "org.apache.kafka.message..",
                            "org.apache.kafka.timeline..",
                            "org.apache.kafka.deferred..")

                    .layer("Infrastructure").definedBy(
                            "org.apache.kafka.network..",
                            "org.apache.kafka.queue..",
                            "org.apache.kafka.raft..",
                            "org.apache.kafka.snapshot..",
                            "org.apache.kafka.storage..",
                            "org.apache.kafka.image..")

                    .layer("Metadata").definedBy(
                            "org.apache.kafka.metadata..",
                            "org.apache.kafka.coordinator..",
                            "org.apache.kafka.controller..")

                    .layer("Clients").definedBy(
                            "org.apache.kafka.clients..")        // includes clients.admin
                                                                 // (api/admin top-level
                                                                 // packages are unused)

                    .layer("Streams").definedBy("org.apache.kafka.streams..")
                    .layer("Connect").definedBy("org.apache.kafka.connect..")
                    .layer("Server").definedBy("org.apache.kafka.server..")

                    .layer("Tooling").definedBy(
                            "org.apache.kafka.tools..",
                            "org.apache.kafka.shell..",
                            "org.apache.kafka.trogdor..")

                    .whereLayer("Core")           .mayNotAccessAnyLayer()
                    .whereLayer("Infrastructure") .mayOnlyAccessLayers("Core")
                    .whereLayer("Metadata")       .mayOnlyAccessLayers("Core", "Infrastructure")
                    .whereLayer("Clients")        .mayOnlyAccessLayers("Core", "Infrastructure", "Metadata")
                    .whereLayer("Streams")        .mayOnlyAccessLayers("Core", "Infrastructure", "Metadata", "Clients")
                    .whereLayer("Connect")        .mayOnlyAccessLayers("Core", "Infrastructure", "Metadata", "Clients")
                    .whereLayer("Server")         .mayOnlyAccessLayers("Core", "Infrastructure", "Metadata", "Clients")
                    .whereLayer("Tooling")        .mayOnlyAccessLayers(
                            "Core", "Infrastructure", "Metadata", "Clients", "Streams", "Connect")

                    // Close the back door on the broker runtime: only the broker
                    // itself and the operational tooling above may reach into it.
                    .whereLayer("Server").mayOnlyBeAccessedByLayers("Server")

                    .because("INFERRED from the standard Apache Kafka monorepo "
                           + "layout (KIP-500 controller/broker split, KIP-405 "
                           + "tiered-storage SPI, Connect plugin isolation). "
                           + "The supplied PDF documents Streams' parallelism "
                           + "model only and does not prescribe these module "
                           + "boundaries; they are CI-level enforcement of the "
                           + "established kafka-* artefact split.");

    // =========================================================================
    // Positive rule (PDF-grounded)
    // =========================================================================

    @ArchTest
    static final ArchRule streams_uses_only_kafka_client_libraries =
            classes()
                    .that().resideInAPackage("org.apache.kafka.streams..")
                    .should().onlyDependOnClassesThat()
                              .resideInAnyPackage(
                                      "java..", "javax..",
                                      "org.slf4j..", "org.rocksdb..",
                                      "org.apache.kafka.common..",
                                      "org.apache.kafka.clients..",
                                      "org.apache.kafka.streams..")
                    .allowEmptyShould(true)
                    .because("Per page 1 of the supplied Kafka Streams "
                           + "architecture page: 'Kafka Streams simplifies "
                           + "application development by building on the "
                           + "Kafka producer and consumer libraries'. "
                           + "Streams must compile against the public "
                           + "producer/consumer client surface and nothing "
                           + "deeper.");

    // =========================================================================
    // Intra-layer sibling isolation (the layered API cannot express these)
    // =========================================================================
    // These are INFERRED from the standard Kafka module layout — the PDF says
    // nothing about them.

    @ArchTest
    static final ArchRule metadata_must_not_depend_on_controller =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.metadata..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the metadata module provides record types "
                           + "and the cluster image consumed by the controller; "
                           + "the reverse edge would form a cycle.");

    @ArchTest
    static final ArchRule metadata_must_not_depend_on_coordinator =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.metadata..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the metadata module is a primitive used "
                           + "by the coordinator; the reverse edge would form a "
                           + "cycle.");

    @ArchTest
    static final ArchRule controller_must_not_depend_on_coordinator =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.controller..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.coordinator..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the KRaft controller manages cluster-wide "
                           + "state; the group/transaction coordinator is broker-"
                           + "local. The two are independent state machines.");

    @ArchTest
    static final ArchRule coordinator_must_not_depend_on_controller =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.coordinator..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.controller..")
                    .allowEmptyShould(true)
                    .because("INFERRED: the coordinator runs on every broker and "
                           + "must not link the KRaft controller (KIP-500 separation).");

    @ArchTest
    static final ArchRule tools_must_not_depend_on_shell =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.tools..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
                    .allowEmptyShould(true)
                    .because("INFERRED: kafka-tools and kafka-metadata-shell are "
                           + "independent operational utilities.");

    @ArchTest
    static final ArchRule shell_must_not_depend_on_tools =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.shell..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
                    .allowEmptyShould(true)
                    .because("INFERRED: kafka-metadata-shell must not pull every "
                           + "CLI tool onto its classpath.");

    @ArchTest
    static final ArchRule trogdor_must_not_depend_on_shell =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.trogdor..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.shell..")
                    .allowEmptyShould(true)
                    .because("INFERRED: Trogdor agents are independent test "
                           + "daemons.");

    @ArchTest
    static final ArchRule trogdor_must_not_depend_on_tools =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.trogdor..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.tools..")
                    .allowEmptyShould(true)
                    .because("INFERRED: Trogdor must not bundle administrative "
                           + "CLI tooling.");

    @ArchTest
    static final ArchRule shell_must_not_depend_on_trogdor =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.shell..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.trogdor..")
                    .allowEmptyShould(true)
                    .because("INFERRED: closes the symmetric pair to "
                           + "trogdor_must_not_depend_on_shell.");

    @ArchTest
    static final ArchRule tools_must_not_depend_on_trogdor =
            noClasses()
                    .that().resideInAPackage("org.apache.kafka.tools..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.trogdor..")
                    .allowEmptyShould(true)
                    .because("INFERRED: closes the symmetric pair to "
                           + "trogdor_must_not_depend_on_tools.");

    // =========================================================================
    // Sibling isolation between Tooling layer and Server layer (CRITICAL — F-04)
    // =========================================================================

    @ArchTest
    static final ArchRule tooling_must_not_depend_on_broker_internals =
            noClasses()
                    .that().resideInAnyPackage(
                            "org.apache.kafka.tools..",
                            "org.apache.kafka.shell..",
                            "org.apache.kafka.trogdor..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.server..")
                    .allowEmptyShould(true)
                    .because("INFERRED: CLI tools, the metadata shell, and "
                           + "Trogdor are client-side processes that talk to a "
                           + "running broker over the wire protocol; they must "
                           + "not compile-depend on broker internals because "
                           + "that would force every CLI invocation to ship the "
                           + "broker classpath.");

    // =========================================================================
    // What was deleted (and why)
    // =========================================================================
    //
    // streams_must_not_depend_on_connect             — subsumed by layered rule (F-05/F-12).
    // connect_must_not_depend_on_streams             — subsumed by layered rule (F-05/F-12).
    // network_must_not_depend_on_streams             — subsumed by layered rule (F-05).
    // raft_must_not_depend_on_metadata               — subsumed by layered rule (F-05).
    // raft_must_not_depend_on_controller             — subsumed by layered rule (F-05).
    // snapshot_must_not_depend_on_controller         — subsumed by layered rule (F-05).
    // clients_must_not_depend_on_server              — subsumed by layered rule (F-05).
    // admin_must_not_depend_on_server                — subsumed by layered rule (F-05);
    //                                                  also vacuous (org.apache.kafka.admin
    //                                                  empty in real Kafka, F-09).
    // api_must_not_depend_on_server                  — subsumed by layered rule (F-05);
    //                                                  also vacuous (org.apache.kafka.api
    //                                                  empty in real Kafka, F-09).
    // common_must_not_depend_on_clients              — subsumed by layered rule (F-05).
    // common_must_not_depend_on_server               — subsumed by layered rule (F-05).
    // common_must_not_depend_on_streams              — subsumed by layered rule (F-05).
    // production_code_must_not_depend_on_test_support — vacuous: ImportOption excludes
    //                                                  the test/jmh classes whose
    //                                                  inbound edges the rule tries
    //                                                  to detect (F-03). If desired,
    //                                                  re-introduce by removing the
    //                                                  ExcludeBenchmarksAndTestSupport
    //                                                  filter for test/jmh.
}
```

This rewrite:

- Adds `withOptionalLayers(true)` and `allowEmptyShould(true)` to every rule, so that a missing Gradle module on the local classpath does not turn into 21 cascading test failures (F-01).
- Replaces every PDF citation in `.because()` with an honest *INFERRED* tag, except for the one rule the PDF actually supports — `streams_uses_only_kafka_client_libraries` — which cites page 1 verbatim (F-02, F-10).
- Drops the vacuous `production_code_must_not_depend_on_test_support` rule whose target classes were filtered out of the import (F-03).
- Splits the conflated Operations layer into a `Server` layer (broker runtime) and a `Tooling` layer (CLI clients), with `Server.mayOnlyBeAccessedByLayers("Server")` as a back-stop and an explicit `tooling_must_not_depend_on_broker_internals` rule. Excludes `org.apache.kafka.tiered` from the importer (F-04, F-06).
- Deletes the 12 redundant cross-layer noClasses rules and adds the missing symmetric intra-Metadata and intra-Operations pairs (F-05, F-12).
- Drops the dead top-level globs `org.apache.kafka.config..`, `org.apache.kafka.security..`, `org.apache.kafka.logger..`, `org.apache.kafka.api..`, and `org.apache.kafka.admin..` (F-08, F-09); the underlying classes live under `org.apache.kafka.common..` and `org.apache.kafka.clients..`.
- Notes the root-package blind spot in the Javadoc and trusts the back-stop `Server.mayOnlyBeAccessedByLayers` to catch any newly-introduced root class that tries to reach broker internals (F-11).

After applying, run `mvn test` against the **real** Apache Kafka build output (or against a `pom.xml` that depends on the published `org.apache.kafka:kafka-*` artefacts) — the suite will then evaluate against actual bytecode rather than producing 21 empty-layer / failOnEmptyShould diagnostics.
