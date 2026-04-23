# Adversarial Review #3 — `outputs/zookeeper/gemini3-flash/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: gemini3-flash
Project: Apache ZooKeeper
Round: 3 (post round-2 revision)

---

### Executive Summary

The revision adopts the consolidated patch from round 2 essentially verbatim.
Nearly every prior finding is resolved; only one issue remains, but it is a
**CRITICAL** false-positive — and it is an error I introduced in the round-2
patch that the author accepted. It must be fixed before this test can run
green on the real ZooKeeper codebase.

| Prior Finding | Status |
|---------------|--------|
| R1-F01 Root package invisible | **Fixed** (root folded into `Client` layer) |
| R1-F02 Fabricated `Compatibility` isolation | **Fixed** |
| R1-F03 Javadoc header inverted | **Fixed** (diagram matches rules) |
| R1-F04 Vacuous `server_should_not_depend_on_recipes` | **Fixed** |
| R1-F05 Post-F01 FP on shared exceptions | **Partially fixed** — see F-R3-01 below |
| R1-F06 `graph` misclassified | **Not fixed** (author flagged as judgement call) |
| R1-F07 Client-side footprint not enumerated | **Fixed** |
| R1-F08 Jute classpath entry / misleading comment | **Fixed** (pom entry removed, comment gone) |
| R1-F09 Redundant `noClasses` rules | **Fixed** (only one remains, see F-R3-02) |
| R1-F10 Generic `.because()` | **Fixed** (all clauses cite PDF §1.1/§1.7/§1.8) |
| R2-N01 `ClientApi → Client` falsely forbidden | **Fixed** (layers merged) |
| R2-N02 Contradiction between layered and fine-grained rules | **Fixed** (`Server.mayNotBeAccessedByAnyLayer()`) |
| R2-N03 Recipes falsely barred from `admin` | **Fixed** |
| R2-N04 Redundant `cli_should_not_depend_on_server` | **Fixed** (deleted) |
| R2-N05 Dead `zookeeper-jute` classpath entry | **Fixed** |

**Remaining findings:**
- **F-R3-01 (CRITICAL)** — Server now cannot reference the public-API root package, but real ZooKeeper server code throws `org.apache.zookeeper.KeeperException` and imports `Watcher`, `WatchedEvent`, `CreateMode`, `ZooDefs`, `AsyncCallback` constantly. Because root was merged into `Client` and `Server.mayNotBeAccessedByAnyLayer()` forbids every inbound edge, the CI build will emit hundreds of spurious violations.
- **F-R3-02 (LOW)** — `public_api_must_not_leak_server_types` is now fully subsumed by `Server.mayNotBeAccessedByAnyLayer()`. Kept for readability; acceptable but worth noting.
- **F-R3-03 (LOW)** — The `test` production package listed in `inputs/java/3_apache_zookeeper.txt` is neither assigned to a layer nor documented as excluded in the Javadoc. `ImportOption.DoNotIncludeTests` handles it only if the classes happen to live under `src/test/java`.

**Overall verdict:** `FAIL` — one CRITICAL false-positive remains. Once F-R3-01 is addressed, the next review is likely to land at `PASS WITH WARNINGS`.

---

### Findings

```
[F-R3-01] SEVERITY: CRITICAL
Category: Overly Narrow (false-positive generator)
Affected Rule / Constraint: layered_architecture_is_respected —
                            .whereLayer("Server").mayNotBeAccessedByAnyLayer()
                            combined with
                            .layer("Client").definedBy("org.apache.zookeeper", ...)

What is wrong:
The `Client` layer is defined as
    root (`org.apache.zookeeper`)
    + `org.apache.zookeeper.client..`
    + `org.apache.zookeeper.admin..`
    + `org.apache.zookeeper.retry..`
and `.whereLayer("Server").mayNotBeAccessedByAnyLayer()` forbids any inbound
edge into Server. Conversely — and this is the subtle part — Client being
defined to include root means Server ← Client edges are also forbidden by
the reverse constraint on Client:
    .whereLayer("Client").mayOnlyBeAccessedByLayers("Recipes", "Tools")
i.e. `Server → Client` is forbidden.

But the root subpackage of `Client` is the public API — it contains
`org.apache.zookeeper.KeeperException`, `Watcher`, `WatchedEvent`,
`CreateMode`, `ZooDefs`, `AsyncCallback`, `Quotas`, `ClientInfo`, etc. —
and the server code depends on every one of these by necessity:

    package org.apache.zookeeper.server;
    import org.apache.zookeeper.KeeperException;
    import org.apache.zookeeper.Watcher;
    import org.apache.zookeeper.WatchedEvent;
    import org.apache.zookeeper.CreateMode;
    import org.apache.zookeeper.ZooDefs;
    import org.apache.zookeeper.AsyncCallback;
    import org.apache.zookeeper.Quotas;
    public class DataTree { ... }

    package org.apache.zookeeper.server;
    import org.apache.zookeeper.KeeperException;
    public abstract class ServerCnxn { ...throws KeeperException... }

These are the shared wire-protocol contract types that both sides of the
TCP connection agree on. The PDF (§1.4: "Znodes maintain a stat structure
that includes version numbers..."; §1.6 the Simple API) depicts these as
the shared vocabulary, not as one-sided client implementation.

With the current rule, every `server.*` → root edge is a `Layer Violation`.
A fresh run against zookeeper-server/target/classes will report violations
in, conservatively, 200+ classes (every server class that throws
`KeeperException`, processes a `Watcher`, or uses a `CreateMode`).

Why it matters:
This is the single blocker preventing the test from running green against
the real codebase. The round-2 patch (which this file implements) solved
`ClientApi → Client` by merging them, but in doing so silently recreated
R1-F01's other half: now Server cannot depend on the public contract that
lives in the same package as the client implementation. The packages
cannot be cleanly split by `..`-globs because root is a single physical
package containing both roles.

How to fix it:
Re-introduce a dedicated "PublicApi" layer for the root package only,
permit Server to depend on it, and model the Client ↔ PublicApi edges as
bidirectional (a simple layered DAG cannot capture this, but
`layeredArchitecture()` supports it by listing each direction in the
respective `mayOnlyBeAccessedByLayers` clause):

```java
.layer("Support").definedBy(
        "org.apache.zookeeper.common..",
        "org.apache.zookeeper.util..",
        "org.apache.zookeeper.metrics..",
        "org.apache.zookeeper.jmx..",
        "org.apache.zookeeper.audit..",
        "org.apache.zookeeper.compat..",
        "org.apache.zookeeper.compatibility..")
.layer("PublicApi").definedBy("org.apache.zookeeper")         // root only
.layer("Client").definedBy(
        "org.apache.zookeeper.client..",
        "org.apache.zookeeper.admin..",
        "org.apache.zookeeper.retry..")
.layer("Server").definedBy("org.apache.zookeeper.server..")
.layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
.layer("Tools").definedBy(
        "org.apache.zookeeper.cli..",
        "org.apache.zookeeper.inspector..",
        "org.apache.zookeeper.graph..")

.whereLayer("Tools").mayNotBeAccessedByAnyLayer()
.whereLayer("Recipes").mayOnlyBeAccessedByLayers("Tools")
.whereLayer("Client").mayOnlyBeAccessedByLayers(
        "PublicApi",                   // root internally references client impl
        "Recipes", "Tools")
.whereLayer("PublicApi").mayOnlyBeAccessedByLayers(
        "Client", "Server",            // both sides share the contract
        "Recipes", "Tools")
.whereLayer("Server").mayOnlyBeAccessedByLayers("PublicApi")
```

Notes on the bidirectional `PublicApi ↔ Client`:
- `PublicApi → Client`: `org.apache.zookeeper.ZooKeeper` (root) uses
  `org.apache.zookeeper.client.HostProvider` — allowed by
  `Client.mayOnlyBeAccessedByLayers("PublicApi", ...)`.
- `Client → PublicApi`: `org.apache.zookeeper.client.ZooKeeperSaslClient`
  uses `org.apache.zookeeper.Login` — allowed by
  `PublicApi.mayOnlyBeAccessedByLayers("Client", ...)`.

And update `public_api_must_not_leak_server_types` to re-target the new
layer name (the rule remains useful because it expresses an explicit
one-way ban in a single sentence with a specific `.because()`):

```java
@ArchTest
public static final ArchRule public_api_must_not_leak_server_types =
    noClasses()
        .that().resideInAPackage("org.apache.zookeeper")   // PublicApi
        .should().dependOnClassesThat(
                resideInAPackage("org.apache.zookeeper.server.."))
        .because("The public client API is the stable contract applications "
               + "compile against; server-side types must never appear in "
               + "its transitive surface.");
```

(`Server.mayOnlyBeAccessedByLayers("PublicApi")` alone would also permit
PublicApi → Server, which we explicitly want to forbid; this fine-grained
rule reverses that single direction. Alternatively, drop the fine-grained
rule and change the layered rule to `Server.mayNotBeAccessedByAnyLayer()`
— but then Server cannot throw `KeeperException` either, reintroducing
F-R3-01. The asymmetry is intentional.)
```

```
[F-R3-02] SEVERITY: LOW
Category: Rule Redundancy (acceptable)
Affected Rule / Constraint: public_api_must_not_leak_server_types

What is wrong:
As currently written (with `Server.mayNotBeAccessedByAnyLayer()` and root
folded into `Client`), the `public_api_must_not_leak_server_types` rule is
fully subsumed: any root → server edge is already caught by the layered
rule because root is in `Client`, and `Client → Server` is forbidden by
`Server.mayNotBeAccessedByAnyLayer()`.

Why it matters:
Not a correctness bug, but if F-R3-01 is fixed as proposed above (by
introducing a separate `PublicApi` layer and allowing `PublicApi → Server`
via `Server.mayOnlyBeAccessedByLayers("PublicApi")`), this rule *becomes
necessary* — so keeping it is correct defensively. Readers should just be
aware that today it is a documentation rule with no unique enforcement
power.

How to fix it:
No code change required. Once F-R3-01 is addressed, this finding
disappears on its own. If F-R3-01 is NOT addressed (and the author accepts
the CI failures), consider deleting this rule to reduce noise in the
violation report.
```

```
[F-R3-03] SEVERITY: LOW
Category: Coverage Gap (documentation only)
Affected Rule / Constraint: package assignment — org.apache.zookeeper.test

What is wrong:
The package list `inputs/java/3_apache_zookeeper.txt` advertises
`org.apache.zookeeper.test` as a first-class package. The reviewed file
does not assign it to any layer. Exclusion today is implicit, via
`ImportOption.DoNotIncludeTests`, which keys off the compiled-class path
(`/test-classes/` for Maven, `/test/` for Gradle). In the actual
zookeeper-server module, `org.apache.zookeeper.test.*` classes live under
`zookeeper-server/src/test/java`, so they are correctly excluded — but
that fact is not documented anywhere in the file.

Why it matters:
- A future maintainer who moves or duplicates a `org.apache.zookeeper.test`
  class into `src/main/java` (a common mistake when wiring test utilities
  into production) will have that class silently become part of *no
  layer*, at which point every rule is bypassed for it.
- The round-2 revision correctly removed an incorrect Javadoc claim that
  `org.apache.zookeeper.test..` was explicitly excluded. But it did not
  replace it with anything. A small rationale comment is worth the
  two-line cost.

How to fix it:
Add one sentence to the Javadoc header and, optionally, a defensive rule:

```java
/**
 * ...
 * Excluded packages:
 *   - org.apache.zookeeper.test.. — contains JUnit fixtures and benchmarks
 *     that live under src/test/java; excluded via
 *     ImportOption.DoNotIncludeTests.
 */
```

Optional defensive rule that fails loudly if test code leaks into the
main classpath:

```java
@ArchTest
public static final ArchRule test_package_must_not_ship_in_production =
    noClasses()
        .that().resideInAPackage("org.apache.zookeeper.test..")
        .should().haveSimpleNameNotEndingWith("")      // i.e. "should not exist"
        .because("org.apache.zookeeper.test.* is test-scope fixtures and "
               + "benchmarks; ImportOption.DoNotIncludeTests excludes them "
               + "when compiled under src/test/java. Any such class found "
               + "on the main classpath indicates a broken build.");
```

(If using the defensive rule, guard it with a comment explaining that it
should NEVER fire — its only job is to fail loudly if someone
accidentally promotes a `test.*` class to production.)
```

---

### Going Forward

If F-R3-01 is fixed using the suggested 6-layer topology (Support,
PublicApi, Client, Server, Recipes, Tools) the rule set becomes:

1. Documentation-aligned (6 layers, two cross-cutting concerns explicitly
   represented: public-API contract sharing and tool isolation).
2. Free of false positives on the real ZooKeeper codebase (modulo the
   F-R1-06 judgement call on `graph`).
3. Free of false negatives for any constraint actually supported by the
   PDF (§1.1, §1.4, §1.6, §1.7, §1.8).

At that point the only remaining risks are the two author judgement calls
flagged in round 1 (F-06 `graph` classification, F-08 Jute import scope)
and the minor documentation gap F-R3-03. None of those are blockers.
