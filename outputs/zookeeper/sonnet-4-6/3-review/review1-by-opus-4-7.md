# Adversarial Review — `outputs/zookeeper/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache ZooKeeper

---

### Executive Summary

- **Total documented constraints identified: 4** (the PDF is light on architectural mandates — the rest of the Javadoc header is generator inference, not documentation):
  1. Clients connect to a ZooKeeper server exclusively over a TCP wire protocol — Java client and server are conceptually separate code paths (§1.1, §1.7).
  2. The server's replicated database, request processor, and atomic messaging / leader-election pipeline are server-internal (§1.7).
  3. Higher-level coordination primitives ("recipes") are built on top of the simple public client API (§1.8), never on server internals.
  4. The public client API (`create`, `delete`, `exists`, `getData`, `setData`, `getChildren`, `sync`) is a deliberately narrow, stable surface (§1.6).
- **Total rules generated: 10** (1 `layeredArchitecture` block + 9 `noClasses` rules).
- **Coverage rate: 2.5 of 4** documented constraints have partial enforcement:
  - C1 is enforced for `client..` and `retry..` but not for the actual client entry point `org.apache.zookeeper.ZooKeeper` (root package) — see F-01.
  - C2 is enforced one-way (`client → server`, `recipes → server`) but the reverse (`server → client-side code`) is covered only by the layered rule and **only for classes in the layered map**; root-package leaks are missed.
  - C3 is enforced by `recipes_must_not_depend_on_server` / `recipes_must_not_depend_on_api`, but the positive assertion that recipes use only the documented public API surface is missing.
  - C4 has **no enforcement at all** — there is no rule that isolates the public API surface (`org.apache.zookeeper` root) from server internals, because that package is not in any layer.
- **Critical Gaps**:
  - **C1 — Root `org.apache.zookeeper` is invisible to every rule.** `ZooKeeper`, `Watcher`, `AsyncCallback`, `KeeperException`, `ZooDefs`, `WatchedEvent`, `ClientCnxn`, and `ClientWatchManager` all live here. None of the layer globs match. See F-01.
  - **C2 — `org.apache.zookeeper.graph` and `org.apache.zookeeper.inspector` are declared "excluded" in the Javadoc but are NOT actually excluded from the importer.** They are imported and they are in no layer, so they can freely reach into server internals without being flagged. See F-02.
  - **C3 — Several layer-access restrictions block real, legitimate dependencies.** In particular, `audit → server` and `client → metrics` are both documented behaviours in real ZooKeeper but both will fail under the current rules. See F-03 and F-04.
- **Overall verdict:** `FAIL`

---

### Findings

```
[F-01] SEVERITY: CRITICAL
Category: Coverage Gap / Structural Gap
Affected Rule / Constraint: layered_architecture_is_respected, client_must_not_depend_on_server,
                            recipes_must_not_depend_on_server (constraint C1, C4)

What is wrong:
Every layer in `layeredArchitecture()` is defined with a trailing `..`, for
example `org.apache.zookeeper.client..`, `org.apache.zookeeper.server..`,
`org.apache.zookeeper.common..`. The `..` suffix matches the named package
and its sub-packages, but it does NOT match the root package
`org.apache.zookeeper` itself. Consequently every class directly under
`org.apache.zookeeper` is in no layer, including the most important classes
in the codebase:
  - `ZooKeeper`              (the client entry point — section 1.6 public API)
  - `ZooKeeperMain`          (CLI main class)
  - `Watcher`, `WatchedEvent`
  - `AsyncCallback`
  - `KeeperException`
  - `ZooDefs`, `CreateMode`
  - `ClientCnxn`, `ClientCnxnSocket`, `ClientWatchManager`

The `noClasses()`-based rules have the same blind spot: their `resideInAPackage`
predicates use `"..` globs or exact subpackage names and never target the root.

Why it matters:
- The PDF's strongest constraints (C1, C4) govern precisely these classes —
  they are the public client contract described in §1.6 and the client half
  of the client/server separation described in §1.1 and §1.7.
- A hypothetical `org.apache.zookeeper.server.quorum.Leader` dependency on
  `org.apache.zookeeper.ClientCnxn` is undetected: source is in layer "Server",
  but target is in no layer, so `layeredArchitecture()` issues no finding.
  The dedicated `client_must_not_depend_on_server` rule only targets
  `org.apache.zookeeper.client..` / `org.apache.zookeeper.retry..`, so the
  reverse leak to a root-package client class also slips through.
- Roughly 40+ public-surface classes are therefore exempt from every rule in
  the file.

How to fix it:
Add the root package explicitly (without the `..` suffix) to the Client layer,
and back-stop it with a dedicated `noClasses` assertion that enforces the
public-API → server isolation required by C1/C4.

```java
.layer("Client")
        .definedBy(
                "org.apache.zookeeper",                 // root-only: ZooKeeper, Watcher, ...
                "org.apache.zookeeper.client..",
                "org.apache.zookeeper.retry..")

// Back-stop: the public API surface must not leak server internals
@ArchTest
static final ArchRule public_api_must_not_depend_on_server = noClasses()
        .that().resideInAPackage("org.apache.zookeeper")     // root only
        .should().dependOnClassesThat(
                resideInAPackage("org.apache.zookeeper.server.."))
        .because("The simple public API described in §1.6 "
               + "(ZooKeeper, Watcher, KeeperException, AsyncCallback, ZooDefs) "
               + "is the stable contract applications compile against; server "
               + "internals must never appear in its transitive surface (§1.1, §1.7).");
```
```

```
[F-02] SEVERITY: CRITICAL
Category: Structural Gap (ClassFileImporter scope)
Affected Rule / Constraint: @AnalyzeClasses / ZOOKEEPER_CLASSES importer, Javadoc "Excluded Packages"

What is wrong:
The Javadoc header declares three packages as "excluded" — `graph`, `inspector`,
and `test` — with the explicit rationale that including them would introduce
false positives. Two of those three exclusions are only *aspirational*:
  - `ImportOption.DoNotIncludeTests` excludes classes under a `/test-classes/`
    (Maven) or `/test/` (Gradle) build folder. It does NOT exclude by Java
    package name. Whether `org.apache.zookeeper.test..` is excluded depends
    entirely on whether its compiled .class files happen to live under the
    test-classes folder.
  - `org.apache.zookeeper.graph` (the log-visualization / throughput-graph
    web-app) and `org.apache.zookeeper.inspector` (the Swing GUI log inspector)
    are shipped as production artifacts in `zookeeper-contrib`. They DO end
    up on the main classpath, they ARE picked up by the importer, and they
    are in NO layer in the rules. So they can freely `import` anything from
    `org.apache.zookeeper.server..` without any rule firing.

Why it matters:
- A class in `org.apache.zookeeper.graph` that directly references
  `org.apache.zookeeper.server.persistence.FileTxnLog` would sail through:
  source is in no layer (so `layeredArchitecture()` ignores it) and no
  `noClasses` rule targets it. The generator expected its own comment to be
  enforceable; it is not.
- The comment is self-contradictory: it says these packages are excluded, and
  it justifies that by "they would introduce false positive layer violations
  from GUI-specific imports." But because nothing actually excludes them, any
  real violation there is silently tolerated.

How to fix it:
Either (a) genuinely exclude graph/inspector from the importer via a custom
`ImportOption`, or (b) give them their own sentinel "Tools" layer with
`mayNotBeAccessedByAnyLayer()` and scope the `server`-isolation rule to
include them. The former matches the stated intent of the Javadoc header.

```java
private static final ImportOption EXCLUDE_STANDALONE_TOOLS = location ->
        !location.contains("/zookeeper/graph/")
        && !location.contains("/zookeeper/inspector/");

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = { ImportOption.DoNotIncludeTests.class,
                          ArchitectureEnforcementTest.class /* holder */ })
public class ArchitectureEnforcementTest {

    static final JavaClasses ZOOKEEPER_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(EXCLUDE_STANDALONE_TOOLS)
            .importPackages("org.apache.zookeeper");
    // ...
}
```

If instead the project wants graph/inspector to remain in scope, declare them
as a first-class "Tools" layer with both an outgoing and incoming restriction:

```java
.layer("Tools").definedBy(
        "org.apache.zookeeper.graph..",
        "org.apache.zookeeper.inspector..")
.whereLayer("Tools").mayNotBeAccessedByAnyLayer()
// and add Tools to the "server/client isolation" rules so they are enforced.
```
```

```
[F-03] SEVERITY: HIGH
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
                            (whereLayer("Monitoring").mayOnlyAccessLayers("Infrastructure","Monitoring"))

What is wrong:
The Monitoring layer is restricted to only depend on Infrastructure and
Monitoring. That forbids every Monitoring → Server edge — but in real
ZooKeeper the `org.apache.zookeeper.audit` module's core purpose is to emit
audit log entries describing server-side actions. `AuditHelper` and
`AuditEventFormatter` take `org.apache.zookeeper.server.Request` instances as
arguments and extract fields (path, type, session id) from them. That is a
compile-time Monitoring → Server dependency that the documentation actively
mandates, not forbids.

Why it matters:
- When the suite runs against the real codebase, this rule emits a
  false-positive violation on legitimate audit code.
- A developer reading the failure will either add `ignoresDependency(...)` or
  delete the rule. Both erode trust; the first also buries the real
  client/server isolation signal that the rule was supposed to protect.
- The `.because()` clause ("Monitoring observes the service but must not drive
  Server, Client, API, or Recipe logic") conflates *driving* (issuing
  write-like calls) with *observing* (reading server request objects to log
  them). Observing is legitimate and necessary.

How to fix it:
Relax the Monitoring→Server constraint (audit is by design a read-only
consumer of server events) and express the intent as a narrower, correct
"audit must not mutate server state" rule. ArchUnit cannot enforce "read-only"
directly, but the documentation does not actually forbid this edge — so drop
the outgoing restriction from Monitoring entirely, and keep only the reverse
(Server may access Monitoring):

```java
.whereLayer("Monitoring")
        .mayOnlyAccessLayers("Infrastructure", "Monitoring", "Server")
```

Alternatively split Monitoring into `Jmx`/`Metrics` (pure infrastructure — no
Server dep) and `Audit` (allowed to read `server.Request`):

```java
.layer("Observability").definedBy(
        "org.apache.zookeeper.jmx..",
        "org.apache.zookeeper.metrics..")
.layer("Audit").definedBy("org.apache.zookeeper.audit..")

.whereLayer("Observability").mayOnlyAccessLayers("Infrastructure", "Observability")
.whereLayer("Audit").mayOnlyAccessLayers(
        "Infrastructure", "Observability", "Server", "Audit")
```
```

```
[F-04] SEVERITY: HIGH
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
                            (whereLayer("Client").mayOnlyAccessLayers("Infrastructure","Client"))

What is wrong:
The Client layer (`client..`, `retry..`) is forbidden from accessing the
Monitoring layer (`jmx..`, `metrics..`, `audit..`). In real ZooKeeper, the
client-side session/heartbeat machinery (`ClientCnxn`, `ClientWatchManager`,
`StaticHostProvider`) publishes latency and failure counters via the
`org.apache.zookeeper.metrics` pluggable API — that is the whole point of
making metrics pluggable in the first place (so third-party collectors like
Prometheus can be wired in on both client and server). The `.because()` block
of `metrics_must_not_depend_on_jmx` even acknowledges the Prometheus use
case, yet the client is blocked from the metrics layer it is supposed to use.

Why it matters:
- Any Client class that records a counter via the metrics façade triggers a
  false-positive layer violation.
- The PDF never restricts clients from emitting metrics — this is a
  fabricated constraint.
- Combined with F-01 (root is unassigned), the fix is not as simple as "move
  ClientCnxn to Client", because ClientCnxn's metric references would then
  *also* fail — so this finding must be addressed in tandem with F-01.

How to fix it:
Permit Client → Monitoring (observability is cross-cutting):

```java
.whereLayer("Client").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Client")
```

Apply the same relaxation to Recipes (recipes may legitimately report
operation latencies) and to API (admin HTTP endpoints emit request metrics):

```java
.whereLayer("Recipes").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Client", "Recipes")
// (API already transitively permits Monitoring, so no change required.)
```
```

```
[F-05] SEVERITY: HIGH
Category: Wrong Layer / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
                            (layer "API" = admin + cli)

What is wrong:
The "API" layer groups `org.apache.zookeeper.admin..` (the server-side
administrative HTTP channel embedded in the server process — cf.
`JettyAdminServer` in real ZooKeeper) with `org.apache.zookeeper.cli..` (a
pure client-side command-line tool that opens a TCP connection like any
other client). These are deployed on opposite sides of the wire protocol:
  - `admin` is server code. It is instantiated inside a running ensemble
    node, runs a Jetty server, and calls *directly into* the server's
    `ZooKeeperServer`, `QuorumPeer`, and internal command handlers. Its
    natural placement is inside — or at least on top of — the Server layer.
  - `cli` is a client tool. It builds a `ZooKeeper` client, issues API calls,
    and never sees server internals.
Grouping them at layer 5 ("above Server, Client and Monitoring") has two
consequences, both wrong:
  (a) It grants `admin` permission to access `Server` (correct), but also
      grants `cli` that same permission (incorrect — CLI is just a client,
      it should not be allowed to compile-depend on server internals).
  (b) It grants `admin` permission to access `Client` (incorrect — admin
      runs inside the server, it should not link to `org.apache.zookeeper.client`).
The layered rule therefore weakens the constraint on both sub-packages to
the lowest common denominator of the two.

Why it matters:
- A real violation "a CLI command imports `ZooKeeperServer`" would be
  accepted under the current rules.
- A real violation "admin code reaches into the client connection
  implementation" would also be accepted.
- The PDF nowhere groups admin with CLI; this is pure generator inference
  and it conflates two unrelated concerns.

How to fix it:
Split API into two layers with distinct allowed-access sets:

```java
.layer("Admin").definedBy("org.apache.zookeeper.admin..")
.layer("Cli").definedBy("org.apache.zookeeper.cli..")

.whereLayer("Admin").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Server", "Admin")
.whereLayer("Cli").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Client", "Cli")

// And replace the two admin<->cli parallel rules, which are fabricated
// (see F-08), with a single assertion that CLI does not reach into server:
@ArchTest
static final ArchRule cli_must_not_depend_on_server = noClasses()
        .that().resideInAPackage("org.apache.zookeeper.cli..")
        .should().dependOnClassesThat(
                resideInAPackage("org.apache.zookeeper.server.."))
        .because("The CLI is a client-side tool (§1.6); it opens a TCP "
               + "connection to a server and must not link-depend on server "
               + "internals.");
```
```

```
[F-06] SEVERITY: HIGH
Category: Structural Gap
Affected Rule / Constraint: layered_architecture_is_respected
                            (missing `mayOnlyBeAccessedByLayers` clauses)

What is wrong:
Every `whereLayer(...)` clause uses only `mayOnlyAccessLayers(...)`, i.e.
restricts the *outgoing* dependencies of classes in that layer. There is no
reciprocal `mayOnlyBeAccessedByLayers(...)` clause, which means the
architecture rule imposes no constraint on who may reach into a layer. The
consequence is that classes in packages not assigned to any layer — in this
repository that is the root package `org.apache.zookeeper` (F-01),
`org.apache.zookeeper.graph`, and `org.apache.zookeeper.inspector` (F-02) —
may freely depend on `Server`, `Client`, `Admin`, and so on, because their
outgoing edges are not governed by any layer rule.

Why it matters:
- A class in `org.apache.zookeeper.graph.GraphMain` that imports
  `org.apache.zookeeper.server.persistence.FileTxnLog` internals is not
  flagged, because `graph` is outside every layer (its outgoing edges are
  unconstrained) and `Server` has no `mayOnlyBeAccessedByLayers` restriction
  (so its incoming edges are unconstrained).
- The same gap applies to the unmapped root package classes (`ZooKeeper`,
  `ZooKeeperMain`) — they can reach anywhere with no rule noticing.
- This is the structural reason why F-01 and F-02 silently erode so many
  assertions: rules that look as if they isolate Server actually only
  constrain explicitly-classified callers.

How to fix it:
For every layer whose contents the documentation describes as "internals",
add a `mayOnlyBeAccessedByLayers` clause. At minimum the Server layer should
declare who is allowed to reach into it (after splitting API per F-05):

```java
.whereLayer("Server").mayOnlyBeAccessedByLayers("Admin", "Server")
// i.e. only the admin HTTP endpoints (and the server itself) may touch
// server internals — neither Client, nor Cli, nor Recipes, nor Monitoring,
// nor any unmapped standalone tool may.
```

Combined with the explicit `Tools` layer from F-02, this forces `graph` and
`inspector` to either declare a legitimate reason (admin-like access) or
stay off server internals entirely.
```

```
[F-07] SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: constraint C3 (recipes are built on the public client API)

What is wrong:
`recipes_must_not_depend_on_server` and `recipes_must_not_depend_on_api`
together forbid recipes from touching `server..`, `admin..`, and `cli..`.
That is the negative half of constraint C3. But the positive half — "recipes
use ONLY the public client API" — is not asserted. The rules permit
`org.apache.zookeeper.recipes..` to depend on anything else, including
unmapped classes, internal utility packages that should not be public
surface, and Monitoring/Audit (the latter is also contradicted by the
layered rule, see F-04).

Why it matters:
- The PDF's §1.8 claim is prescriptive: recipes are higher-order primitives
  built "exclusively on top of the ZooKeeper Client API". A recipe that
  reached into `org.apache.zookeeper.common.X509Util` or
  `org.apache.zookeeper.server.util.OSMXBean` would violate the intent but
  would not trigger any rule.
- Without a positive whitelist, "don't depend on server" is not enough:
  anything outside `server/admin/cli` is implicitly allowed, including
  future packages that may be added.

How to fix it:
Add an explicit whitelist assertion — recipes may only depend on
infrastructure, the root public-API classes, the documented `client..`
package, and other recipes:

```java
@ArchTest
static final ArchRule recipes_only_use_public_client_api = classes()
        .that().resideInAPackage("org.apache.zookeeper.recipes..")
        .should().onlyDependOnClassesThat(
                resideInAnyPackage(
                        "java..", "javax..", "org.slf4j..",
                        "org.apache.zookeeper",               // public API root
                        "org.apache.zookeeper.client..",
                        "org.apache.zookeeper.common..",
                        "org.apache.zookeeper.util..",
                        "org.apache.zookeeper.data..",        // Stat, ACL, Id
                        "org.apache.zookeeper.recipes.."))
        .because("Per §1.8, recipes are higher-order coordination primitives "
               + "built exclusively on top of the simple client API (§1.6). "
               + "They must not depend on server internals, CLI/admin tooling, "
               + "or implementation-only utilities.");
```
```

```
[F-08] SEVERITY: MEDIUM
Category: Vacuous Rule / Fabricated Constraint
Affected Rule / Constraint: cli_must_not_depend_on_admin, admin_must_not_depend_on_cli,
                            metrics_must_not_depend_on_jmx, jmx_must_not_depend_on_metrics,
                            audit_must_not_depend_on_jmx_or_metrics

What is wrong:
Five of the nine `noClasses` rules are "parallel sibling isolation" rules —
three inside Monitoring (metrics ⊥ jmx ⊥ audit) and two inside API (cli ⊥
admin). None of these constraints appear anywhere in the PDF. The PDF does
not describe monitoring sub-components as orthogonal peers that must not
touch each other, nor does it describe admin and cli as peers at all (the
PDF does not mention either package).

Two problems:
  (a) Most or all of these rules are likely vacuous. `cli` is a client tool,
      `admin` is server-side — in the real codebase they share no edge in
      either direction. `jmx`, `metrics`, and `audit` are small utility
      packages whose internal dependency graph is mostly empty between them.
      Vacuous rules give the illusion of coverage without adding any.
  (b) Where the rules are *not* vacuous they produce false positives. In
      real ZooKeeper, `AuditEventImpl` records metrics about audit-log
      emission itself (latency, dropped events), so `audit → metrics` is a
      legitimate edge forbidden by `audit_must_not_depend_on_jmx_or_metrics`.

Why it matters:
- Rule count inflates without coverage gain; a reader scanning the file
  might think monitoring-layer isolation is documented when it isn't.
- The generator's `.because()` clauses claim "parallel monitoring
  sub-components" and "circular dependency within the Monitoring layer" as
  justifications — both are pure invention (the PDF says nothing about
  monitoring sub-layers, and none of these would form a cycle since the
  layered rule forbids upward edges already).

How to fix it:
Delete all five fabricated rules. Replace them with a single documented
constraint if one actually exists in the PDF — otherwise leave intra-layer
dependencies alone. Specifically remove:

```java
// DELETE:
//   cli_must_not_depend_on_admin
//   admin_must_not_depend_on_cli
//   metrics_must_not_depend_on_jmx
//   jmx_must_not_depend_on_metrics
//   audit_must_not_depend_on_jmx_or_metrics
```

Replace with at most the one assertion the PDF actually supports — that CLI
is a client-side tool and must not reach into server internals (already
given as part of F-05's fix).
```

```
[F-09] SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: infrastructure_must_not_depend_on_higher_layers

What is wrong:
The layered architecture already asserts
`.whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")` with
`consideringAllDependencies()`, which forbids Infrastructure → any higher
layer. The separate `infrastructure_must_not_depend_on_higher_layers` rule
enumerates the same 9 higher-layer packages as forbidden targets. The two
rules are semantically identical.

Why it matters:
Not an error — both rules pass or both fire in lockstep. But redundancy
obscures the source of truth: when a violation surfaces, developers may
relax one rule thinking it is enough, while the other still fires with a
less helpful message. It also doubles failure output noise.

How to fix it:
Delete `infrastructure_must_not_depend_on_higher_layers` and rely on the
layered rule. Keep the `.because()` rationale on the layered rule instead
(or move it into the layered `.because()` block for discoverability).
```

```
[F-10] SEVERITY: LOW
Category: Semantic Error (.because() accuracy)
Affected Rule / Constraint: several — client_must_not_depend_on_server,
                            recipes_must_not_depend_on_server,
                            the layered rule

What is wrong:
Multiple `.because()` clauses cite PDF section numbers that don't actually
contain the claimed rule:
  - The layered rule cites "section 1.7" for a prescribed
    "Infrastructure → Monitoring → Server / Client → API → Recipes" flow.
    §1.7 ("Implementation") describes the internal components of the server
    (request processor, atomic messaging, replicated database). It does not
    prescribe any layered stack of packages, let alone six named layers.
  - `client_must_not_depend_on_server` cites "section 1.7" for "the
    client-side TCP connection to a single server". The actual statement is
    in §1.1 ("Clients connect to a single ZooKeeper server") — §1.7
    describes server-side internals.
  - `recipes_must_not_depend_on_server` cites "section 1.8 (Uses)". §1.8
    does say "higher order operations" but is a TBD-laden placeholder. The
    prescriptive claim the rule makes ("built exclusively on the ZooKeeper
    Client API") is stronger than the PDF text.

Why it matters:
When these rules fire in CI, developers will go look up the cited section
and find it does not support the claim. That invites `ignoresDependency()`
escape hatches on the grounds that "the rule is over-stated".

How to fix it:
Re-anchor citations to sections that actually support the claim, and soften
claims where the PDF is silent:

```java
.because("Per §1.1 and §1.7, clients connect to ZooKeeper servers only "
       + "over a TCP connection; the Java client library must not "
       + "compile-depend on server internals so the two artefacts can "
       + "evolve and ship independently.");

// For recipes: keep §1.8 but note it is an inference:
.because("§1.8 describes recipes as higher-order operations built on top "
       + "of the simple API defined in §1.6; the rules therefore infer "
       + "that recipes may only compile against the public client API.");
```
```

```
[F-11] SEVERITY: LOW
Category: Dead Code / Cosmetic
Affected Rule / Constraint: static final JavaClasses ZOOKEEPER_CLASSES

What is wrong:
The `ZOOKEEPER_CLASSES` field is declared with a comment saying it is "used
when running rules programmatically outside the JUnit 5 runner". Nothing in
the file references it. All tests are bound to the runner via
`@AnalyzeClasses` / `@ArchTest`, which uses its own importer configured by
the annotation.

Why it matters:
Dead code. More subtly, the field is configured inconsistently with the
annotation: it uses `new ImportOption.DoNotIncludeTests()` without the
exclusions stated in the Javadoc (graph, inspector). If a future maintainer
uses this field to invoke rules manually, their results will differ from
the annotation-driven run.

How to fix it:
Delete the field, or wire it into the annotation and test runner so there
is one import path for both modes:

```java
// Delete:
//   static final JavaClasses ZOOKEEPER_CLASSES = ...
```

If programmatic access is truly needed, expose it through a factory method
that reuses the same `ImportOption` set as the annotation (including the
graph/inspector exclusions from F-02).
```

---

### Recommended Consolidated Patch

The following rewrite addresses F-01, F-02, F-03, F-04, F-05, F-06, F-08,
F-09, F-10, and F-11 together. F-07 (recipes positive whitelist) is added as
a standalone rule. Left for judgement: whether to hard-exclude `graph` /
`inspector` from the importer or classify them as `Tools` (both approaches
shown, pick one).

```java
/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * Inferred from the PDF documentation (Copyright © 2008-2013 The Apache
 * Software Foundation), which actually mandates only four things:
 *
 *   (C1) Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).
 *   (C2) Request processor / replicated DB / atomic broadcast are internal
 *        to the server and are not part of the client contract (§1.7).
 *   (C3) Recipes are higher-order primitives built on the simple client
 *        API, never on server internals (§1.8).
 *   (C4) The simple API (create/delete/exists/getData/setData/getChildren/
 *        sync) is a narrow, stable public surface (§1.6).
 *
 * Everything else below (layered stack, monitoring/audit separation) is
 * inference and is marked as such in the .because() clauses.
 *
 * Excluded artefacts:
 *   - src/test/java — handled by ImportOption.DoNotIncludeTests.
 *   - org.apache.zookeeper.graph / inspector — standalone tools, excluded
 *     by EXCLUDE_STANDALONE_TOOLS below.
 */
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ArchitectureEnforcementTest.ExcludeStandaloneTools.class })
public class ArchitectureEnforcementTest {

    /** Excludes graph/inspector standalone tools from the import scope. */
    public static final class ExcludeStandaloneTools implements ImportOption {
        @Override public boolean includes(Location location) {
            return !location.contains("/org/apache/zookeeper/graph/")
                && !location.contains("/org/apache/zookeeper/inspector/");
        }
    }

    // ---------------------------------------------------------------------
    // Layered Architecture
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .withOptionalLayers(true)

                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..")

                    .layer("Monitoring").definedBy(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..")

                    .layer("Server").definedBy("org.apache.zookeeper.server..")

                    .layer("Client").definedBy(
                            "org.apache.zookeeper",                  // root-only: ZooKeeper, Watcher, ...
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..")

                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")
                    .layer("Cli").definedBy("org.apache.zookeeper.cli..")
                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    // Outgoing edges.
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")
                    // Monitoring may observe Server (audit logs inspect Request objects).
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")
                    // Server may emit metrics/JMX/audit (§1.7 internals).
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")
                    // Client may emit metrics (pluggable metrics API).
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client")
                    // Admin is server-side: it uses Server internals but not Client.
                    .whereLayer("Admin").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server", "Admin")
                    // CLI is a client tool.
                    .whereLayer("Cli").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Cli")
                    // Recipes are pure clients.
                    .whereLayer("Recipes").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Recipes")

                    // Incoming edges — close the back door.
                    .whereLayer("Server").mayOnlyBeAccessedByLayers(
                            "Admin", "Monitoring", "Server")

                    .because("Inferred from §1.1/§1.7: clients talk to servers only "
                           + "over the TCP wire protocol. Admin HTTP endpoints run "
                           + "inside the server; CLI is a client tool; recipes are "
                           + "higher-order primitives over the public API (§1.8). "
                           + "Observability (metrics/jmx/audit) is cross-cutting and "
                           + "may be accessed from any tier.");

    // ---------------------------------------------------------------------
    // Documentation-specific carve-outs (C1, C3, C4)
    // ---------------------------------------------------------------------

    /** C4: The public API surface must not leak server internals. */
    @ArchTest
    static final ArchRule public_api_must_not_depend_on_server =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper")     // root only
                    .should().dependOnClassesThat(
                            resideInAPackage("org.apache.zookeeper.server.."))
                    .because("§1.6 describes the simple public API (create, delete, "
                           + "exists, getData, setData, getChildren, sync); its "
                           + "implementing classes (ZooKeeper, Watcher, "
                           + "KeeperException, AsyncCallback, ZooDefs) form the "
                           + "stable contract applications compile against and "
                           + "must not transitively drag in server internals.");

    /** C1: CLI is a client tool — must not touch server internals. */
    @ArchTest
    static final ArchRule cli_must_not_depend_on_server =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.cli..")
                    .should().dependOnClassesThat(
                            resideInAPackage("org.apache.zookeeper.server.."))
                    .because("§1.1 and §1.6: the CLI opens a TCP connection and "
                           + "submits API calls like any other client; a compile "
                           + "edge into the server would defeat the wire-protocol "
                           + "separation.");

    /** C3: Recipes use only the public client API and shared data types. */
    @ArchTest
    static final ArchRule recipes_only_use_public_client_api =
            classes()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().onlyDependOnClassesThat(
                            resideInAnyPackage(
                                    "java..", "javax..", "org.slf4j..",
                                    "org.apache.zookeeper",                  // public API root
                                    "org.apache.zookeeper.client..",
                                    "org.apache.zookeeper.common..",
                                    "org.apache.zookeeper.util..",
                                    "org.apache.zookeeper.data..",           // Stat/ACL/Id
                                    "org.apache.zookeeper.recipes.."))
                    .because("Per §1.8, recipes are higher-order coordination "
                           + "primitives built on the simple client API (§1.6); "
                           + "they must not reach into server internals, admin or "
                           + "CLI tooling, or implementation-only utilities.");
}
```

This rewrite:

- Maps the root package to the Client layer and adds a dedicated back-stop (F-01, F-04 coupling).
- Excludes `graph` and `inspector` from the importer as the original Javadoc intended (F-02).
- Permits `Monitoring → Server` so audit logging does not false-positive (F-03).
- Permits `Client → Monitoring` so client metric emission does not false-positive (F-04).
- Splits `API` into sibling `Admin` and `Cli` layers with distinct allowed-access matrices (F-05).
- Adds `Server.mayOnlyBeAccessedByLayers(Admin, Monitoring, Server)` to close the back door (F-06).
- Adds a positive `recipes → public-API-only` whitelist (F-07).
- Deletes the five fabricated parallel-sibling rules (F-08) and the redundant infrastructure rule (F-09).
- Rewrites `.because()` clauses to cite the sections that actually support each claim and to mark inferences as inferences (F-10).
- Deletes the unused `ZOOKEEPER_CLASSES` field (F-11).

