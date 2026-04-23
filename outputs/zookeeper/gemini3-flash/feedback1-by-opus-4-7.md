# Adversarial Review — `outputs/zookeeper/gemini3-flash/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: gemini3-flash
Project: Apache ZooKeeper

---

### Executive Summary

- **Total documented constraints identified: 4**
  1. Clients connect to servers exclusively over a TCP/wire protocol — the Java client and server implementations are conceptually separate (doc §1.1, §1.7).
  2. The server is a replicated state machine (request processor → atomic broadcast → replicated database) — these pieces are internal to the server tier and are not part of the client contract (§1.7).
  3. Higher-level coordination primitives ("recipes" / synchronization primitives) are implemented *on top of* the simple public client API — they are clients of ZooKeeper, not a part of the core server (§1.1, §1.5, §1.8).
  4. The public client API (create/delete/exists/getData/setData/getChildren/sync) is a deliberately narrow, stable surface that other layers depend on (§1.6).
- **Total rules generated: 6** (1 `layeredArchitecture` block + 5 `noClasses` rules).
- **Coverage rate: 2 of 4** documented constraints have real enforcement. Constraints (3) and (4) are only *partially* enforced because the public API package (`org.apache.zookeeper` root) is not assigned to any layer — see F-01.
- **Critical Gaps**:
  - **C1 — Root package `org.apache.zookeeper` is invisible to every rule.** This is where `ZooKeeper`, `ZooKeeperMain`, `Watcher`, `KeeperException`, `ClientCnxn`, `ClientWatchManager`, `CreateMode`, `ZooDefs`, and `AsyncCallback` live. None of the layer globs match it, so the core client API is unconstrained.
  - **C2 — Compatibility layer constraint is fabricated and will produce false positives.** `mayNotBeAccessedByAnyLayer()` on `Compatibility` contradicts the actual usage of `org.apache.zookeeper.compat.*` / `org.apache.zookeeper.compatibility.*` by the server.
  - **C3 — The hierarchy stated in the Javadoc header ("Client API sits above Core Server") is inverted vs. what the rules encode** ("Server may only be accessed by Compatibility" — i.e. Client may not access Server). Either the comment or the rules are wrong; both cannot be right.
- **Overall verdict:** `FAIL`

---

### Findings

```
[F-01] SEVERITY: CRITICAL
Category: Coverage Gap / Structural Gap
Affected Rule / Constraint: layered_architecture_is_respected (constraint #4 — public API surface)

What is wrong:
Every layer in `layeredArchitecture()` is defined with the `..` suffix
(e.g. `org.apache.zookeeper.client..`, `org.apache.zookeeper.server..`). The `..`
glob matches the named package and its subpackages, but it does NOT match the
root package `org.apache.zookeeper` itself. Consequently every class directly
under `org.apache.zookeeper` — including `ZooKeeper`, `ZooKeeperMain`,
`Watcher`, `WatchedEvent`, `KeeperException`, `CreateMode`, `ZooDefs`,
`ClientCnxn`, `ClientWatchManager`, `AsyncCallback` — is in no layer.

Why it matters:
These are the most important classes in the whole codebase. A server class that
imports `org.apache.zookeeper.ClientCnxn` would be a textbook layering
violation, and nothing in this test would catch it: the source class's layer is
unknown, and the target class's layer is unknown, so `layeredArchitecture()`
issues no finding. The same is true for the hand-written fine-grained rules:
`server_should_not_depend_on_client` matches only `...client..`, so a server
dependency on `org.apache.zookeeper.ClientCnxn` slips through. In effect ~40
public-surface classes are exempt from every rule in the file.

How to fix it:
Introduce an explicit "ClientApi" layer for the root package and re-wire the
allowed-access matrix. Because `definedBy(...)` glob patterns don't match the
root directly, either list the exact package or use `resideInAPackage` with an
explicit predicate.

```java
.layer("ClientApi").definedBy("org.apache.zookeeper")          // root only
.layer("Client").definedBy("org.apache.zookeeper.client..",
                            "org.apache.zookeeper.admin..",
                            "org.apache.zookeeper.retry..")
// ...
.whereLayer("ClientApi").mayOnlyBeAccessedByLayers(
        "Client", "Recipes", "Tools", "Compatibility", "Server")
.whereLayer("Server").mayOnlyBeAccessedByLayers("ClientApi", "Compatibility")
```

Also add an explicit assertion that root-level classes don't leak server deps:

```java
@ArchTest
public static final ArchRule public_api_must_not_depend_on_server = noClasses()
    .that().resideInAPackage("org.apache.zookeeper")            // root only
    .should().dependOnClassesThat(resideInAPackage("org.apache.zookeeper.server.."))
    .as("The public client API must not leak server internals")
    .because("org.apache.zookeeper.ZooKeeper / Watcher / KeeperException are "
           + "the stable contract consumed by applications; server-side types "
           + "must never appear in their transitive surface.");
```
```

```
[F-02] SEVERITY: CRITICAL
Category: Semantic Error / Overly Broad
Affected Rule / Constraint: layered_architecture_is_respected — `whereLayer("Compatibility").mayNotBeAccessedByAnyLayer()`

What is wrong:
The rule forbids *any* layer from accessing the Compatibility layer
(`org.apache.zookeeper.compat..`, `org.apache.zookeeper.compatibility..`).
The PDF documentation never mentions either package, so this is a fabricated
constraint. In the actual ZooKeeper codebase, `org.apache.zookeeper.server.*`
imports `org.apache.zookeeper.compat.ProviderRegistry`, and
`org.apache.zookeeper.common.*` / `org.apache.zookeeper.server.*` reference
`org.apache.zookeeper.compatibility.ZooKeeperVersion`. Those references are
legitimate — "compatibility" packages exist precisely to be consumed by the
rest of the system.

Why it matters:
Running this rule on the real ZooKeeper classpath will emit false-positive
violations on normal, correct code. A failing CI build that flags legitimate
dependencies forces developers to either add `ignoresDependency()` mufflers or
disable the rule — both of which erode trust in the test.

How to fix it:
Delete the "Compatibility" layer constraint outright (nothing in the PDF
supports it) and fold `compat` / `compatibility` into the Support tier, which
is where bottom-of-stack utilities belong:

```java
.layer("Support").definedBy(
        "org.apache.zookeeper.common..",
        "org.apache.zookeeper.util..",
        "org.apache.zookeeper.metrics..",
        "org.apache.zookeeper.jmx..",
        "org.apache.zookeeper.audit..",
        "org.apache.zookeeper.compat..",
        "org.apache.zookeeper.compatibility..")
// and remove both the "Compatibility" layer and
// the corresponding .whereLayer("Compatibility")... lines
```
```

```
[F-03] SEVERITY: HIGH
Category: Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected — direction vs. Javadoc header

What is wrong:
The Javadoc header states:

    "Documented Layer Hierarchy (Bottom to Top):
       1. Support/Infrastructure
       2. Core Server
       3. Client API
       4. Recipes/Abstractions
       5. Tooling & CLI
       6. Compatibility"

In a layered architecture "bottom to top" means higher layers depend on lower
layers. That would allow Client → Server and Recipes → Server. But the rules
encode the opposite: `.whereLayer("Server").mayOnlyBeAccessedByLayers("Compatibility")`
forbids Client → Server, Recipes → Server, and Tools → Server. Either the
header comment is wrong or the rule is wrong; they cannot both be right.

Why it matters:
- If a future maintainer trusts the header, they will believe Client → Server
  is legal and architect new client code against server internals. CI will
  then mysteriously fail and the `.because()` clause on the layered rule
  ("prevents cyclic dependencies and ensures system maintainability") gives
  them no hint why.
- The correct documented intent from the PDF is exactly "clients and servers
  are separate, linked only by the wire protocol" (§1.7). That matches the
  rule, not the Javadoc header.

How to fix it:
Rewrite the header to stop describing ZK as a classical layered stack. It is
in fact two sibling towers — client and server — sharing a public API and a
common support core. Suggested header:

```java
/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * ZooKeeper is logically split into two sibling sub-systems communicating
 * only via the wire protocol:
 *
 *                     +-------------------+
 *                     |    Tooling/CLI    |   (cli, inspector, graph)
 *                     +---------+---------+
 *                               |
 *   +-------------+     +-------v-------+     +---------------+
 *   |   Recipes   | --> |  Client API   |     | Server Core   |
 *   |  (recipes)  |     | (client,admin,|     |   (server)    |
 *   +-------------+     |  retry, root) |     +-------+-------+
 *                       +-------+-------+             |
 *                               |                     |
 *                       +-------v---------------------v-------+
 *                       |             Support / Common        |
 *                       |  (common, util, metrics, jmx,       |
 *                       |   audit, compat, compatibility)     |
 *                       +-------------------------------------+
 *
 * Rules enforced:
 *  - Client-side packages must never import server packages, and vice versa.
 *  - Recipes are pure clients; they depend on the public API only.
 *  - Tooling may depend on client-side packages but not on server internals.
 *  - All layers may depend on Support; Support depends on nothing above it.
 */
```
```

```
[F-04] SEVERITY: HIGH
Category: Vacuous Rule
Affected Rule / Constraint: server_should_not_depend_on_recipes

What is wrong:
The three recipes packages (`org.apache.zookeeper.recipes.lock`,
`org.apache.zookeeper.recipes.queue`, `org.apache.zookeeper.recipes.leader` /
`election`) live in three separate Maven modules that declare `zookeeper` as a
dependency. The `zookeeper-server` module does NOT declare the recipes as a
dependency — it cannot, because that would be a cycle. Therefore no class
under `org.apache.zookeeper.server..` can physically contain a reference to a
recipes class, and this rule is structurally impossible to violate.

Why it matters:
Vacuous rules create the illusion of coverage. The generator wrote down a
constraint that reads plausibly ("server shouldn't depend on recipes") without
noticing that the Maven dependency graph already prevents it and the JVM would
fail with `NoClassDefFoundError` before ArchUnit even ran. Worse, when
combined with F-01, this hides the fact that no rule actually prevents the
*reverse* leak at the source level (e.g. a recipe reaching into
`org.apache.zookeeper.server.quorum` types that happen to be on the test
classpath).

How to fix it:
Delete `server_should_not_depend_on_recipes`. The layered architecture already
forbids it (Recipes is not in the "may access Server" list), so removing it
does not reduce coverage — it just removes dead rule text. If you want a
positive assertion that recipes use *only* the public API, replace it with:

```java
@ArchTest
public static final ArchRule recipes_only_depend_on_public_api_or_support =
    noClasses()
        .that().resideInAPackage("org.apache.zookeeper.recipes..")
        .should().dependOnClassesThat(
            resideInAnyPackage(
                "org.apache.zookeeper.server..",
                "org.apache.zookeeper.admin..",
                "org.apache.zookeeper.cli..",
                "org.apache.zookeeper.inspector..",
                "org.apache.zookeeper.graph.."))
        .as("Recipes must be built on the public client API only")
        .because("Per PDF §1.8, recipes are higher-order operations built on "
               + "top of the simple client API, not on server internals or tools.");
```
```

```
[F-05] SEVERITY: HIGH
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected — `whereLayer("Server").mayOnlyBeAccessedByLayers("Compatibility")`

What is wrong:
The Support layer (`common`, `util`, `metrics`, `jmx`, `audit`) and the Client
layer (`client`, `admin`, `retry`) both depend on types in the public API
package `org.apache.zookeeper` (root — see F-01), and many of those root
classes in turn reference server internals (e.g. `ZooKeeperMain` references
`org.apache.zookeeper.server.ExitCode`, `KeeperException` references
`org.apache.zookeeper.server.util.*` in some ZK versions). Because root is
unassigned (F-01), those transitive edges aren't caught — but the instant F-01
is fixed by assigning root to a layer, this rule immediately starts producing
false positives on legitimate shared constants/exceptions.

Why it matters:
Fixing F-01 and leaving F-05 as-written will turn a broken-but-silent test
into a broken-but-loudly-failing test. Reviewers need to know these findings
are coupled.

How to fix it:
After assigning root to `ClientApi`, permit the legitimate narrow exceptions
explicitly, or move the shared types (exceptions, enums, constants) into the
Support layer. The minimal-surgery fix is to allow ClientApi ↔ Server for
those specific types:

```java
.whereLayer("Server").mayOnlyBeAccessedByLayers("ClientApi", "Compatibility")
// and assert the reverse direction separately:

@ArchTest
public static final ArchRule server_must_not_depend_on_client_impl = noClasses()
    .that().resideInAPackage("org.apache.zookeeper.server..")
    .should().dependOnClassesThat(resideInAnyPackage(
            "org.apache.zookeeper.client..",
            "org.apache.zookeeper.admin..",
            "org.apache.zookeeper.retry..",
            "org.apache.zookeeper.recipes.."))
    .as("Server internals must not reach into client or recipes packages")
    .because("Per PDF §1.7, client and server communicate only over the wire "
           + "protocol; neither should link-depend on the other's implementation.");
```
```

```
[F-06] SEVERITY: MEDIUM
Category: Wrong Layer
Affected Rule / Constraint: Tools layer includes `org.apache.zookeeper.graph..`

What is wrong:
`org.apache.zookeeper.graph` in the real ZooKeeper codebase is the
**log-visualization / throughput-graph web-app** — a Jetty-based server. It
is not a CLI tool; it is a standalone application that imports server-side
log-reading utilities. Grouping it with `cli` and `inspector` under "Tools"
with `mayNotBeAccessedByAnyLayer()` is harmless (nothing accesses it), but
the bigger issue is `cli_should_not_depend_on_server` does not apply to
`graph`, which arguably needs server-log access and therefore should have its
own exception.

Why it matters:
Two sub-issues:
- `graph` being in Tools means `graph → server` is forbidden by the layered
  rule, which will emit false positives against legitimate log readers.
- `inspector` is an SWT/GUI tool (zookeeper-contrib) that has historically
  been shipped separately and may not even be on the classpath; treating it
  as first-class production code is misleading.

How to fix it:
Split "Tools" into "Cli" and "Analytics", and scope the rules accordingly:

```java
.layer("Cli").definedBy("org.apache.zookeeper.cli..",
                        "org.apache.zookeeper.inspector..")
.layer("Analytics").definedBy("org.apache.zookeeper.graph..")
// ...
.whereLayer("Cli").mayNotBeAccessedByAnyLayer()
.whereLayer("Analytics").mayNotBeAccessedByAnyLayer()
// Analytics may legitimately read server logs/snapshots:
// drop the analytics→server restriction, but keep it for Cli:

@ArchTest
public static final ArchRule cli_should_not_depend_on_server_internals =
    noClasses()
        .that().resideInAPackage("org.apache.zookeeper.cli..")
        .should().dependOnClassesThat(
            resideInAPackage("org.apache.zookeeper.server..")
            .and(not(resideInAPackage("org.apache.zookeeper.server.admin.."))))
        .because("CLI commands use the public client API; admin.* is the "
               + "only server-side surface they may touch.");
```
```

```
[F-07] SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: constraint #1 (clients connect only via the wire protocol)

What is wrong:
The documentation's strongest, most unambiguous constraint is that client and
server communicate only through the network protocol — i.e. neither side
should carry a compile-time dependency on the other. The reviewed file
enforces one half (`server → client` via `server_should_not_depend_on_client`
and `client → server` via `client_should_not_depend_on_server`), but only the
`org.apache.zookeeper.client..` sub-tree is treated as "client". The actual
client-side footprint includes:
  - `org.apache.zookeeper`       (root — see F-01)
  - `org.apache.zookeeper.admin` (classified as "Client", fine)
  - `org.apache.zookeeper.retry` (classified as "Client", fine)
  - `org.apache.zookeeper.recipes` (effectively a client)

Only three of those are in any "client-ish" layer, and the root (the biggest
one) is in none.

Why it matters:
Constraint #1 is the constraint with the strongest documentary support, and
the rules enforce it only partially. A hypothetical leak
`org.apache.zookeeper.server.quorum.Leader → org.apache.zookeeper.ZooKeeper`
is undetected because `ZooKeeper` is in the root package (F-01).

How to fix it:
Augment the fix from F-01 with a dedicated symmetric rule that groups every
client-side package and forbids any server dep on any of them, keyed to the
PDF rationale:

```java
@ArchTest
public static final ArchRule server_must_not_depend_on_client_side = noClasses()
    .that().resideInAPackage("org.apache.zookeeper.server..")
    .should().dependOnClassesThat(resideInAnyPackage(
            "org.apache.zookeeper",               // public API root
            "org.apache.zookeeper.client..",
            "org.apache.zookeeper.admin..",
            "org.apache.zookeeper.retry..",
            "org.apache.zookeeper.recipes.."))
    .as("Server must be ignorant of client-side code")
    .because("PDF §1.7: clients and servers communicate only via the TCP "
           + "wire protocol; a compile-time edge from server to any "
           + "client-side package would violate that separation.");
```
```

```
[F-08] SEVERITY: MEDIUM
Category: Structural Gap (ClassFileImporter Scope)
Affected Rule / Constraint: `@AnalyzeClasses(packages = "org.apache.zookeeper", importOptions = { ImportOption.DoNotIncludeTests.class })`

What is wrong:
Two issues with the import scope:
1. `ImportOption.DoNotIncludeTests` excludes classes located under a
   `/test-classes/` (Maven) or `/test/` (Gradle) path. It does NOT exclude
   based on the Java package name. The header comment claims
   "org.apache.zookeeper.test.. is excluded" — but that exclusion only works
   if ZooKeeper's test sources happen to live under src/test/java. In the
   actual repo, some `org.apache.zookeeper.test.*` classes live under
   zookeeper-server/src/test/java (so they're excluded), but the package list
   in `inputs/java/3_apache_zookeeper.txt` lists `org.apache.zookeeper.test`
   as if it were a first-class production package. The comment is therefore
   misleading or wrong depending on where the classes actually land.
2. The pom declares `zookeeper-jute/target/classes` on the classpath, but
   Jute types live in package `org.apache.jute.*`, outside the scanned root
   `org.apache.zookeeper`. Adding jute to the classpath has no effect on
   analysis and should be removed or the import scope widened.

Why it matters:
- A future reader trusting the comment may write rules targeting
  `org.apache.zookeeper.test..` that silently match nothing.
- Misleading classpath entries hide the real coupling between
  `org.apache.zookeeper.server.persistence` and `org.apache.jute.Record`.

How to fix it:
Either scope the importer to include Jute and assert Jute is only consumed by
server persistence:

```java
@AnalyzeClasses(
    packages = { "org.apache.zookeeper", "org.apache.jute" },
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ImportOption.DoNotIncludeJars.class
    })
public class ArchitectureEnforcementTest {

    @ArchTest
    public static final ArchRule jute_only_used_by_server_persistence = noClasses()
        .that().resideOutsideOfPackages(
                "org.apache.zookeeper.server.persistence..",
                "org.apache.zookeeper.server.quorum..",
                "org.apache.jute..")
        .should().dependOnClassesThat(resideInAPackage("org.apache.jute.."))
        .because("Jute is a serialization runtime; only the server "
               + "persistence/quorum layer should touch its types directly.");
}
```

And drop the misleading sentence from the Javadoc header about
`org.apache.zookeeper.test..` — either the package is test code (then the
import option handles it and listing it is noise) or it is production code
(then excluding it is a conscious choice that needs its own rationale).
```

```
[F-09] SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: server_should_not_depend_on_client,
                            client_should_not_depend_on_server,
                            recipes_should_not_depend_on_server,
                            cli_should_not_depend_on_server

What is wrong:
Every one of these four `noClasses()` rules is already fully subsumed by
the `layeredArchitecture()` assertion:
- `whereLayer("Server").mayOnlyBeAccessedByLayers("Compatibility")` covers
  client/recipes/cli → server.
- `whereLayer("Client").mayOnlyBeAccessedByLayers("Recipes","Tools","Compatibility")`
  covers server → client.

Why it matters:
Not an error, but a signal that the generator did not reason about coverage
vs. redundancy. Redundant assertions obscure which rule is the "source of
truth" when one needs to be relaxed with an `ignoresDependency()` later, and
they double the noise in failure output.

How to fix it:
Delete the four redundant `noClasses` rules, OR delete the matching
`.whereLayer()` clauses, but keep only one authoritative form of each
constraint. A good pattern is: `layeredArchitecture()` for cross-layer
direction, and `noClasses()` only for intra-layer or documentation-specific
carve-outs (like the `Jute` or `public-API` rules proposed above).
```

```
[F-10] SEVERITY: LOW
Category: Semantic Error (`because()` accuracy)
Affected Rule / Constraint: layered_architecture_is_respected

What is wrong:
The `.because()` clause reads:
    "Maintaining strict layer boundaries prevents cyclic dependencies and
     ensures system maintainability."

This is a generic boilerplate sentence. It cites neither the PDF nor any
ZooKeeper-specific rationale (client/server separation, wire-protocol
contract, replicated state machine isolation).

Why it matters:
When the rule fires, developers see this sentence in the failure message. A
generic rationale gives them no actionable information and — worse — may lead
them to add `ignoresDependency()` rather than fix the code, because the rule
"doesn't really say anything specific." Per PHASE 3 item 11 of the review
methodology, `.because()` should cite the architectural rationale.

How to fix it:
Replace with a specific, source-cited rationale:

```java
.because("Per the ZooKeeper architecture overview (§1.1, §1.7): clients and "
       + "servers communicate only over the TCP wire protocol; recipes are "
       + "higher-order primitives built on top of the simple client API "
       + "(§1.6, §1.8); and tooling sits on top of — never below — the "
       + "public API. A compile-time edge across these boundaries would "
       + "break the stated separation and is disallowed.");
```
```

---

### Recommended Consolidated Patch

Below is a single, ready-to-paste rewrite that addresses F-01, F-02, F-03,
F-05, F-07, F-08, F-09 and F-10 at once. F-04 and F-06 require judgement
calls (keep `graph` as an app? require Jute on the importer?) that are left
for the author.

```java
/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * ZooKeeper is logically split into two sibling sub-systems communicating
 * only via the TCP wire protocol, layered on a common support core:
 *
 *                     +-------------------+
 *                     |    Tooling/CLI    |   cli, inspector, graph
 *                     +---------+---------+
 *                               |
 *   +-------------+     +-------v-------+     +---------------+
 *   |   Recipes   | --> |  ClientApi    |     |    Server     |
 *   |  (recipes)  |     | (root +       |     |   (server)    |
 *   +-------------+     |  client,admin,|     +-------+-------+
 *                       |  retry)       |             |
 *                       +-------+-------+             |
 *                               |                     |
 *                       +-------v---------------------v-------+
 *                       |             Support                 |
 *                       |  (common, util, metrics, jmx,       |
 *                       |   audit, compat, compatibility)     |
 *                       +-------------------------------------+
 *
 * Excluded:
 *  - classes under src/test/java (handled by ImportOption.DoNotIncludeTests;
 *    no additional package-based exclusion is needed or attempted).
 */
package org.apache.zookeeper;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = { ImportOption.DoNotIncludeTests.class })
public class ArchitectureEnforcementTest {

    // --- Layered Architecture -------------------------------------------------

    @ArchTest
    public static final ArchRule layered_architecture_is_respected =
        layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
            .layer("Support").definedBy(
                    "org.apache.zookeeper.common..",
                    "org.apache.zookeeper.util..",
                    "org.apache.zookeeper.metrics..",
                    "org.apache.zookeeper.jmx..",
                    "org.apache.zookeeper.audit..",
                    "org.apache.zookeeper.compat..",
                    "org.apache.zookeeper.compatibility..")
            .layer("Server").definedBy("org.apache.zookeeper.server..")
            .layer("ClientApi").definedBy(
                    "org.apache.zookeeper",                       // root only
                    "org.apache.zookeeper.client..",
                    "org.apache.zookeeper.admin..",
                    "org.apache.zookeeper.retry..")
            .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
            .layer("Tools").definedBy(
                    "org.apache.zookeeper.cli..",
                    "org.apache.zookeeper.inspector..",
                    "org.apache.zookeeper.graph..")

            .whereLayer("Tools").mayNotBeAccessedByAnyLayer()
            .whereLayer("Recipes").mayOnlyBeAccessedByLayers("Tools")
            .whereLayer("ClientApi").mayOnlyBeAccessedByLayers("Recipes", "Tools")
            .whereLayer("Server").mayNotBeAccessedByAnyLayer()

            .as("ZooKeeper client-side and server-side code are isolated")
            .because("Per the ZooKeeper architecture overview (§1.1, §1.7): "
                   + "clients and servers communicate only over the TCP wire "
                   + "protocol; recipes are higher-order primitives built on "
                   + "the simple client API (§1.6, §1.8); tooling sits above "
                   + "— never below — the public API.");

    // --- Client/server isolation (documentation-specific carve-outs) ---------

    @ArchTest
    public static final ArchRule public_api_must_not_depend_on_server =
        noClasses()
            .that().resideInAPackage("org.apache.zookeeper")      // root only
            .should().dependOnClassesThat(
                    resideInAPackage("org.apache.zookeeper.server.."))
            .because("The public client API (ZooKeeper, Watcher, KeeperException, "
                   + "CreateMode, ZooDefs, AsyncCallback, ClientCnxn) is the "
                   + "stable contract applications compile against; server-side "
                   + "types must never appear in its transitive surface.");

    @ArchTest
    public static final ArchRule server_must_not_depend_on_client_side =
        noClasses()
            .that().resideInAPackage("org.apache.zookeeper.server..")
            .should().dependOnClassesThat(resideInAnyPackage(
                    "org.apache.zookeeper.client..",
                    "org.apache.zookeeper.admin..",
                    "org.apache.zookeeper.retry..",
                    "org.apache.zookeeper.recipes..",
                    "org.apache.zookeeper.cli..",
                    "org.apache.zookeeper.inspector..",
                    "org.apache.zookeeper.graph.."))
            .because("Per §1.7, the server is a replicated state machine that "
                   + "receives requests over the wire; it must never "
                   + "compile-depend on any client-side or tooling package.");
}
```

This rewrite:
- Collapses the four redundant `noClasses` rules into two documentation-specific ones (F-09).
- Puts the public-API root package on the map (F-01, F-07).
- Drops the fabricated "Compatibility layer is inaccessible" rule (F-02).
- Aligns the Javadoc header with what the rules actually enforce (F-03).
- Replaces generic `.because()` boilerplate with PDF-anchored rationales (F-10).
- Keeps `consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")` so Jute and third-party deps stay out of scope (F-08 caveat).
