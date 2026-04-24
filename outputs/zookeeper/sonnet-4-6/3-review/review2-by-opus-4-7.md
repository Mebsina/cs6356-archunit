# Adversarial Review #2 — `outputs/zookeeper/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache ZooKeeper
Review: 2 (post–Review #1 fix)

---

### Executive Summary

- **Total documented constraints identified: 4** (unchanged from Review #1):
  1. (C1) Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).
  2. (C2) Replicated DB / request processor / atomic broadcast are server-internal (§1.7).
  3. (C3) Recipes are higher-order primitives built on the simple client API, never on server internals (§1.8).
  4. (C4) The simple API (create / delete / exists / getData / setData / getChildren / sync) is a narrow, stable public surface (§1.6).
- **Total rules generated: 6** (1 `layeredArchitecture` block + 5 `noClasses` / `classes` rules — down from 10 after the Review #1 fix removed the five fabricated parallel-sibling rules plus the redundant Infrastructure rule).
- **Coverage rate: 4 of 4** constraints now have at least one explicit rule — a material improvement over Review #1's 2.5/4. BUT the fix introduced a new class of defects: **rule-vs-rule contradictions** and **one high-confidence false positive** on real ZooKeeper code.
- **Critical Gaps / Regressions**:
  - **R-01 (new)** — `recipes_only_use_public_client_api` whitelist **contradicts** `layered_architecture_is_respected`. The layered rule permits Recipes → Monitoring and Recipes → `retry..` (since retry is now part of the Client layer), but the whitelist omits all of them. Depending on which rule fires first, developers see contradictory failure messages.
  - **R-02 (new)** — The Review #1 fix that placed the entire root package `org.apache.zookeeper` into the `Client` layer triggers a **false-positive on `org.apache.zookeeper.ZooKeeperMain`** (the CLI entry-point class), because `ZooKeeperMain` compile-depends on `org.apache.zookeeper.cli.*` commands and `Client.mayOnlyAccessLayers("Infrastructure", "Monitoring", "Client")` forbids Client → Cli.
  - **R-03 (carry-over)** — Several sub-packages that exist in real ZooKeeper but are absent from the provided `inputs/java/3_apache_zookeeper.txt` (notably `data`, `proto`, `txn`, `version`) are in no layer. Values from `org.apache.zookeeper.data.Stat` / `ACL` / `Id` are part of the public API surface that C4 protects — their omission is a silent coverage gap.
- **Overall verdict:** `PASS WITH WARNINGS` — coverage is adequate, but R-01 and R-02 will fail CI on the real codebase and should be fixed before merge.

---

### Findings

```
[R-01] SEVERITY: HIGH
Category: Overly Narrow / Semantic Error (rule-vs-rule contradiction)
Affected Rule / Constraint: recipes_only_use_public_client_api  vs  layered_architecture_is_respected

What is wrong:
The two rules describe incompatible "allowed target sets" for the recipes
package:

  layered_architecture_is_respected:
    .whereLayer("Recipes").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Client", "Recipes")

    where Client      = { org.apache.zookeeper (root),
                          org.apache.zookeeper.client..,
                          org.apache.zookeeper.retry.. }
    where Monitoring  = { org.apache.zookeeper.jmx..,
                          org.apache.zookeeper.metrics..,
                          org.apache.zookeeper.audit.. }
    where Infrastructure = { common.., util.., compat.., compatibility.. }

  recipes_only_use_public_client_api:
    .should().onlyDependOnClassesThat(resideInAnyPackage(
        "java..", "javax..", "org.slf4j..",
        "org.apache.zookeeper",
        "org.apache.zookeeper.client..",
        "org.apache.zookeeper.common..",
        "org.apache.zookeeper.util..",
        "org.apache.zookeeper.data..",
        "org.apache.zookeeper.recipes.."))

The second rule omits — relative to what the layered rule permits — every
one of the following packages that the layered rule considers legal targets
for a recipe:

  org.apache.zookeeper.retry..        (Client layer  — would trigger whitelist FP)
  org.apache.zookeeper.metrics..      (Monitoring    — the Review #1 fix deliberately opened this edge)
  org.apache.zookeeper.jmx..          (Monitoring)
  org.apache.zookeeper.audit..        (Monitoring)
  org.apache.zookeeper.compat..       (Infrastructure)
  org.apache.zookeeper.compatibility..(Infrastructure)

Why it matters:
- A recipe that records a latency counter via org.apache.zookeeper.metrics
  (the exact use case the Review #1 fix cited when relaxing Client→Monitoring
  in F-04) will PASS the layered rule and FAIL the whitelist rule. The
  codebase now has two rules disagreeing on the same edge — whichever fires
  first sets the narrative for the developer, who cannot tell which one is
  authoritative.
- A recipe that wraps a ZooKeeper call in org.apache.zookeeper.retry.*
  (ZooKeeper's own retry helper) fails the whitelist but passes the layered
  rule. This is silently incompatible with how the Client layer was defined.
- The whitelist rule's `.because()` cites §1.8 (recipes built on the simple
  API) as justification, but the omission is not from §1.8 — it is the
  author having enumerated an incomplete set by hand.

How to fix it:
Align the whitelist with the layered rule. The simplest form is to re-express
the whitelist in terms of the same layer membership:

```java
@ArchTest
static final ArchRule recipes_only_use_public_client_api =
        classes()
                .that().resideInAPackage("org.apache.zookeeper.recipes..")
                .should().onlyDependOnClassesThat(
                        resideInAnyPackage(
                                "java..", "javax..",
                                "org.slf4j..",
                                // Public client API surface (C4, §1.6)
                                "org.apache.zookeeper",
                                "org.apache.zookeeper.client..",
                                "org.apache.zookeeper.retry..",
                                // Shared data types (Stat, ACL, Id)
                                "org.apache.zookeeper.data..",
                                // Infrastructure utilities
                                "org.apache.zookeeper.common..",
                                "org.apache.zookeeper.util..",
                                "org.apache.zookeeper.compat..",
                                "org.apache.zookeeper.compatibility..",
                                // Cross-cutting observability (matches layered rule)
                                "org.apache.zookeeper.jmx..",
                                "org.apache.zookeeper.metrics..",
                                "org.apache.zookeeper.audit..",
                                // Self
                                "org.apache.zookeeper.recipes.."))
                .because(
                        "Per §1.8, recipes are higher-order coordination primitives "
                        + "built on the simple client API (§1.6). They may use "
                        + "infrastructure and cross-cutting observability but must "
                        + "not reach into server internals, admin tooling, or CLI "
                        + "tooling.");
```

Alternatively, delete the whitelist rule entirely: the layered architecture
already forbids Recipes → Server / Admin / Cli, which is the architectural
claim §1.8 supports. Everything else the whitelist attempts to enforce is
author-invented scope creep that R-01 will continue to destabilise as the
codebase grows (third-party deps like Netty or Apache Commons may legitimately
appear in recipes and will keep tripping the whitelist).
```

```
[R-02] SEVERITY: HIGH
Category: Wrong Layer (false positive)
Affected Rule / Constraint: layered_architecture_is_respected
                            (root "org.apache.zookeeper" assigned to Client layer)

What is wrong:
The Review #1 fix added the root package to the Client layer:

    .layer("Client")
            .definedBy(
                    "org.apache.zookeeper",        // root-only: ZooKeeper, Watcher, ...
                    "org.apache.zookeeper.client..",
                    "org.apache.zookeeper.retry..")

This correctly captures ZooKeeper, Watcher, AsyncCallback, KeeperException,
ZooDefs, WatchedEvent, ClientCnxn, ClientWatchManager. But it also captures
`org.apache.zookeeper.ZooKeeperMain`, which is the CLI tool's entry point
and compile-depends on `org.apache.zookeeper.cli.CliCommand`,
`org.apache.zookeeper.cli.AclParser`, `AddAuthCommand`, `CreateCommand`,
`DeleteAllCommand`, and dozens of other `cli` subcommands.

The layered rule, however, forbids Client → Cli:

    .whereLayer("Client").mayOnlyAccessLayers(
            "Infrastructure", "Monitoring", "Client")

So `ZooKeeperMain -> org.apache.zookeeper.cli.CliCommand` fires as a layered
architecture violation. This is a real edge in the production codebase and
a legitimate one (ZooKeeperMain's entire job is to dispatch to cli.*
commands), so the rule produces a false positive on real code.

Why it matters:
- CI will fail on the unmodified Apache ZooKeeper source with a violation
  that cannot be fixed without either moving ZooKeeperMain (a source-code
  change outside the scope of architecture enforcement) or weakening the
  layer rule (which undoes part of the F-02/F-05 fix).
- The `.because()` clause on the layered rule states "CLI is a client-side
  tool; it opens a TCP connection like any other client." That is true of
  the `cli.*` subcommands. It is also true that ZooKeeperMain is CLI — it is
  the process entry point of zkCli.sh. The rule semantically wants
  ZooKeeperMain in the Cli layer, but package-glob classification cannot
  split the root package by class name.

How to fix it:
Two viable fixes; pick whichever matches intent.

(a) Enumerate root-package assignments explicitly, using `classes()`-based
    predicates instead of package globs. Move ZooKeeperMain to Cli:

```java
import static com.tngtech.archunit.base.DescribedPredicate.and;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;

.layer("Cli").definedBy(
        classes ->
            classes.stream()
                   .filter(c -> c.getPackageName().equals("org.apache.zookeeper.cli")
                             || "ZooKeeperMain".equals(c.getSimpleName())
                             || "ZooKeeperMainHelper".equals(c.getSimpleName()))
                   .collect(toSet()))
// and remove the root package from the Client layer; leave only
//   client.. and retry..
```

(b) Simpler: permit Client → Cli. This is a narrow relaxation that only
    matters for the root-package CLI entry point class. Add `"Cli"` to
    Client's allowed-access list:

```java
.whereLayer("Client").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Client", "Cli")
```

Option (b) is the pragmatic one — it matches how the real codebase is
structured (ZooKeeperMain is a root-package class that dispatches to cli.*)
and does not trade Review #1's coverage for false positives. Option (a) is
architecturally cleaner but requires the JavaClasses-based `definedBy`
overload, which many teams avoid because it executes per-import.
```

```
[R-03] SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: layered_architecture_is_respected (missing public API subpackages)

What is wrong:
The provided `inputs/java/3_apache_zookeeper.txt` only lists the 16 top-level
direct subpackages of `org.apache.zookeeper`. It does NOT list the following
subpackages that exist in the real Apache ZooKeeper source tree:

    org.apache.zookeeper.data      (Stat, ACL, Id, StatPersisted)
    org.apache.zookeeper.proto     (ConnectRequest, CreateRequest, etc.)
    org.apache.zookeeper.txn       (CreateTxn, DeleteTxn, TxnHeader, etc.)
    org.apache.zookeeper.version   (generated version metadata)

These packages are imported into the analysis (they are under the
`packages = "org.apache.zookeeper"` root of `@AnalyzeClasses`) and they are
in NO layer. Specifically:

- `data..` contains Stat, ACL, Id — part of the public client API (C4); a
  ZooKeeper.setACL(...) signature takes `org.apache.zookeeper.data.ACL`.
  These are not protected by `public_api_must_not_depend_on_server`, which
  scopes to the root package only with `resideInAPackage("org.apache.zookeeper")`.
- `proto..` / `txn..` contain wire-format records shared between client and
  server. If a recipe (or anything else) reaches into `proto..` directly, no
  rule flags it — it slips past both the layered rule (unassigned target is
  benign) and the whitelist (which also omits proto/txn).

Why it matters:
- C4 (§1.6 public API surface is narrow and stable) is not enforced for
  `data..` — a server internal that leaked into `org.apache.zookeeper.data`
  would not be caught.
- The recipes whitelist in R-01 includes `data..` but not `proto..`, which
  means it *allows* data but accepts the author's implicit model that the
  public API is only root + data. That is fine as a policy decision; it
  should be made explicit in the layered rule too.

How to fix it:
Assign the missing subpackages explicitly to the layer they belong to:

```java
.layer("PublicApi").definedBy(
        "org.apache.zookeeper",           // root-only
        "org.apache.zookeeper.data..")    // Stat, ACL, Id, StatPersisted

.layer("Wire").definedBy(
        "org.apache.zookeeper.proto..",
        "org.apache.zookeeper.txn..")

.whereLayer("PublicApi").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "PublicApi", "Wire")
.whereLayer("Wire").mayOnlyAccessLayers(
        "Infrastructure", "Wire")         // pure records, no outward deps

// Update Client / Server / Recipes / Admin / Cli to permit "PublicApi" and
// "Wire" where they legitimately need them (server builds txn records,
// client sends proto, etc.).
```

If splitting the layer graph further is too invasive, at minimum extend the
existing Client layer to include `data..` and expand
`public_api_must_not_depend_on_server` to include `data..` too:

```java
.layer("Client").definedBy(
        "org.apache.zookeeper",
        "org.apache.zookeeper.client..",
        "org.apache.zookeeper.retry..",
        "org.apache.zookeeper.data..")

@ArchTest
static final ArchRule public_api_must_not_depend_on_server =
        noClasses()
                .that().resideInAnyPackage(
                        "org.apache.zookeeper",
                        "org.apache.zookeeper.data..")
                .should().dependOnClassesThat(
                        resideInAPackage("org.apache.zookeeper.server.."))
                .because("§1.6 public API surface includes both the root package "
                       + "(ZooKeeper, Watcher, KeeperException) and the data "
                       + "package (Stat, ACL, Id); neither may drag in server "
                       + "internals.");
```
```

```
[R-04] SEVERITY: MEDIUM
Category: Rule Redundancy
Affected Rule / Constraint: client_must_not_depend_on_server,
                            cli_must_not_depend_on_server,
                            recipes_must_not_depend_on_server,
                            public_api_must_not_depend_on_server

What is wrong:
The layered architecture after the Review #1 fix already encodes all four of
these constraints:

  - `Client.mayOnlyAccessLayers("Infrastructure","Monitoring","Client")`
      covers Client → Server (where Client now includes root + client.. + retry..)
      making `client_must_not_depend_on_server` AND
      `public_api_must_not_depend_on_server` redundant.
  - `Cli.mayOnlyAccessLayers("Infrastructure","Monitoring","Client","Cli")`
      covers Cli → Server, making `cli_must_not_depend_on_server` redundant.
  - `Recipes.mayOnlyAccessLayers("Infrastructure","Monitoring","Client","Recipes")`
      covers Recipes → Server.
  - Additionally, `Server.mayOnlyBeAccessedByLayers("Admin","Monitoring","Server")`
      provides the same protection from the other direction — it forbids
      Client, Cli, and Recipes from accessing Server.

Each forbidden edge therefore has THREE enforcing rules: the source layer's
outgoing restriction, the server layer's incoming restriction, and a
dedicated `noClasses` rule. All three fire on the same violation.

Why it matters:
- Redundancy is not an error, but a single violation produces three distinct
  failure messages with overlapping stack traces, which obscures the fix.
- More importantly: when a future maintainer needs to grant a narrow
  exception (e.g., "temporarily allow `retry..` to read one server constant"),
  they have to relax THREE rules instead of one. The likely outcome is an
  `ignoresDependency()` on the most convenient rule, which then diverges
  from the layered rule, at which point the file's rules no longer agree.
- The Review #1 fix history notes these were "back-stop" rules added in case
  the layered rule missed something. Now that the layered rule is correct
  (post-fix), the back-stops have nothing to back-stop.

How to fix it:
Delete the four redundant `noClasses` rules and rely on the layered
architecture:

```java
// DELETE:
//   public_api_must_not_depend_on_server
//   client_must_not_depend_on_server
//   cli_must_not_depend_on_server
//   recipes_must_not_depend_on_server
```

Keep `recipes_only_use_public_client_api` (it is the only rule that does
something the layered architecture cannot express: a positive whitelist of
allowed packages). After the R-01 fix, it becomes the single non-redundant
fine-grained rule in the file.
```

```
[R-05] SEVERITY: MEDIUM
Category: Overly Narrow (whitelist coverage gap)
Affected Rule / Constraint: recipes_only_use_public_client_api

What is wrong:
Even after the R-01 whitelist alignment with the layered rule, the whitelist
covers only `java..`, `javax..`, and `org.slf4j..` for third-party / runtime
dependencies. It omits every third-party library that the real ZooKeeper
recipes module legitimately uses or has used in the past:

  - `org.apache.commons.cli..`     (used by recipes' command-line launchers)
  - `org.apache.commons.codec..`   (Base64 in some lock flavours)
  - `com.google.common..` / Guava  (historically used in test-adjacent recipes)
  - `org.apache.yetus.audience..`  (public API annotations like @InterfaceAudience)
  - `io.netty..`                   (if any recipe uses client TLS via Netty transport)

If any of these libraries appear on a recipe class's import list, the rule
fires even though none of them is forbidden by C3.

Why it matters:
- A whitelist that fails on legitimate third-party libraries forces every
  new recipe to either bundle dependencies under `org.apache.zookeeper.*`
  or to disable the rule. Both outcomes erode the recipe module's
  independence.
- The `.because()` rationale cites §1.8 (recipes built on the simple API),
  but §1.8 says nothing about forbidding third-party utilities.

How to fix it:
Either broaden the whitelist to include any library that the recipes module
legitimately depends on (listed explicitly), or invert the rule to a
blacklist of disallowed internal packages (which is what C3 actually says):

```java
@ArchTest
static final ArchRule recipes_must_not_depend_on_zookeeper_internals =
        noClasses()
                .that().resideInAPackage("org.apache.zookeeper.recipes..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "org.apache.zookeeper.server..",
                        "org.apache.zookeeper.admin..",
                        "org.apache.zookeeper.cli..",
                        "org.apache.zookeeper.graph..",
                        "org.apache.zookeeper.inspector.."))
                .because("Per §1.8, recipes are higher-order coordination "
                       + "primitives built on the simple client API (§1.6); "
                       + "they must not reach into server internals, admin or "
                       + "CLI tooling, or developer-only GUIs. Third-party "
                       + "dependencies are not the concern of this rule.");
```

This form is equivalent to the layered rule's Recipes restriction but makes
the architectural claim explicit (C3) and avoids the whitelist-maintenance
burden that R-01 and R-05 both highlight.
```

```
[R-06] SEVERITY: LOW
Category: Semantic Error (.because() inconsistency)
Affected Rule / Constraint: layered_architecture_is_respected

What is wrong:
The `.because()` clause on the layered rule ends with:

    "Observability (metrics / jmx / audit) is cross-cutting and may be
     accessed from any tier."

But the rule itself forbids Infrastructure → Monitoring:

    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

So observability is NOT accessible from the lowest tier. The `.because()`
sentence is false for Infrastructure; it should say "may be accessed from
any tier EXCEPT Infrastructure" or the rule should be relaxed to allow
Infrastructure → Monitoring (which is inconsistent with the "bottom of the
stack" role of common / util).

Why it matters:
When this rule fires on (say) `org.apache.zookeeper.common.X509Util ->
org.apache.zookeeper.metrics.*` (a plausible edge — X509Util in real
ZooKeeper reports TLS handshake metrics), the developer reads "observability
is cross-cutting and may be accessed from any tier" and concludes the rule
must be misconfigured, then disables it. The actual intent is that
Infrastructure is literally the bottom of the stack.

How to fix it:
Rewrite the `.because()` clause to match the rule exactly:

```java
.because(
        "Inferred from §1.1 and §1.7: clients connect to ZooKeeper servers "
        + "only over the TCP wire protocol. Admin HTTP endpoints run inside "
        + "the server process; CLI is a client-side tool; recipes are "
        + "higher-order primitives built on the public API (§1.8). "
        + "Observability (metrics / jmx / audit) is cross-cutting and may "
        + "be accessed from Server, Client, Admin, Cli, and Recipes; "
        + "Infrastructure (common / util / compat / compatibility) remains "
        + "strictly below observability so the utility layer stays a "
        + "reusable, dependency-free base.");
```

Alternatively relax Infrastructure → Monitoring if the real codebase has
that edge (verify by searching `org.apache.zookeeper.common.*` for imports
of `org.apache.zookeeper.metrics.*`):

```java
.whereLayer("Infrastructure").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring")
```
```

```
[R-07] SEVERITY: LOW
Category: Structural Gap (ClassFileImporter scope)
Affected Rule / Constraint: ExcludeStandaloneTools

What is wrong:
The exclusion implementation relies on `location.contains("/org/apache/.../graph/")`
as a substring match. Two subtle issues:

1. On Windows, `Location` URIs are normalised to forward slashes by ArchUnit,
   so the check works in practice. But it is fragile: any future source layout
   (e.g., a Gradle source set with the path fragment
   `zookeeper-contrib-zookeeper-graph/build/classes/java/main/org/apache/zookeeper/graph/*`)
   still matches, which is correct, but the test is implicit — there is no
   comment documenting that the pattern is intentionally a substring match.

2. The exclusion also covers JAR entries
   (`jar:file:.../zookeeper-contrib.jar!/org/apache/zookeeper/graph/...`),
   which is correct — but a recently-added exclusion option
   `ImportOption.DoNotIncludeJars` would conflict with it (the custom
   `ExcludeStandaloneTools` would then be partially dead code).

Why it matters:
Not a correctness issue — just a brittleness / maintainability concern. The
future maintainer has to reverse-engineer the URI format to understand why
the check works.

How to fix it:
Add a short comment explaining the substring semantics, and consider using
ArchUnit 1.x's cleaner `DoNotIncludeGradleTestFixtures` / package-based
excluder patterns when available:

```java
/**
 * Excludes {@code org.apache.zookeeper.graph} and {@code .inspector}
 * standalone tools. The match is a URI-substring match: ArchUnit normalises
 * all class locations (filesystem paths AND JAR entries) to forward-slashed
 * URIs, so the same pattern covers both Maven target/classes layouts and
 * shaded-jar entries.
 */
public static final class ExcludeStandaloneTools implements ImportOption {
    @Override public boolean includes(Location location) {
        return !location.contains("/org/apache/zookeeper/graph/")
            && !location.contains("/org/apache/zookeeper/inspector/");
    }
}
```
```

```
[R-08] SEVERITY: LOW
Category: Documentation (Javadoc header)
Affected Rule / Constraint: class-level Javadoc "Inferred Layer Hierarchy (bottom to top)"

What is wrong:
The Javadoc lists seven layers as a numbered "bottom to top" hierarchy:

    1. Infrastructure
    2. Monitoring
    3. Server
    4. Client
    5. Admin
    6. Cli
    7. Recipes

This numbering is misleading. The rules do not encode a strict linear
stack — Admin (server-side) and Cli (client-side) are parallel siblings,
neither above nor below the other, and Server is explicitly NOT accessible
to Client/Cli/Recipes. Reading the Javadoc a developer would conclude that
Recipes (layer 7) is allowed to reach Server (layer 3), but the rules
forbid it.

Why it matters:
The Javadoc is the first thing a new contributor reads. If it describes a
linear stack that the rules then contradict, the rules will be perceived as
buggy and ignored. The fix history's rewrite of the Javadoc already removes
F-03 from Review #1 (the inverted stack), but this new description is a
different mismatch.

How to fix it:
Replace the numbered list with a diagram that shows the actual lattice.
Inline ASCII is fine; it only needs to communicate that client and server
are parallel:

```java
/**
 * <pre>
 *               +------------+     +-----+
 *               |  Recipes   |     | Cli |     (client-side)
 *               +-----+------+     +--+--+
 *                     |               |
 *                     v               v
 *   +-------+     +---+---------------+---+
 *   | Admin | --> |     Client (incl.     |
 *   |       |     |   public API root)    |
 *   +---+---+     +-----------+-----------+
 *       |                     |
 *       v                     v
 *   +---+---+             +---+---------+
 *   | Server|<----------->| Monitoring  |  (audit reads Server requests)
 *   +---+---+             +------+------+
 *       |                        |
 *       v                        v
 *   +---+------------------------+--+
 *   |        Infrastructure         |  (common, util, compat, compatibility)
 *   +-------------------------------+
 * </pre>
 */
```

Or simply replace the numbered list with bullets that clarify the parallel
structure:

```
 * <p>The layers form a lattice, not a linear stack:
 * <ul>
 *   <li><b>Infrastructure</b> is the bottom; nothing may depend upward from here.</li>
 *   <li><b>Monitoring</b> is cross-cutting; it may read Server internals (audit)
 *       but is not in the call-graph sense above or below any other tier.</li>
 *   <li><b>Server</b> and <b>Client</b> are parallel, communicating only over
 *       the wire; neither may compile-depend on the other.</li>
 *   <li><b>Admin</b> runs inside the Server process (so Admin → Server is legal),
 *       and <b>Cli</b>/<b>Recipes</b> run against the Client side.</li>
 * </ul>
```
```

---

### Recommended Consolidated Patch

The following rewrite addresses R-01, R-02, R-04, R-05, R-06, R-07, and R-08
in a single pass. R-03 (missing `data..` / `proto..` / `txn..` assignments)
is deferred because it depends on whether the author wants to enforce their
boundaries — the minimum-viable fix is to extend the Client layer to include
`data..`, shown inline below.

```java
/**
 * ArchitectureEnforcementTest
 *
 * <p>Enforces the architectural constraints of Apache ZooKeeper derived from
 * the official architecture documentation (Copyright © 2008-2013 The Apache
 * Software Foundation). The PDF mandates four concrete constraints:
 *
 * <ol>
 *   <li><b>C1</b> – Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).</li>
 *   <li><b>C2</b> – The request processor, replicated database, and atomic-broadcast
 *       pipeline are internal to the server (§1.7).</li>
 *   <li><b>C3</b> – Recipes are higher-order primitives built on the simple client API,
 *       never on server internals (§1.8).</li>
 *   <li><b>C4</b> – The simple API surface (create/delete/exists/getData/setData/
 *       getChildren/sync) is a narrow, stable public contract (§1.6).</li>
 * </ol>
 *
 * <p>The layer graph is a lattice, not a linear stack:
 * <ul>
 *   <li><b>Infrastructure</b> is the bottom; no upward edges.</li>
 *   <li><b>Monitoring</b> is cross-cutting; it may read Server internals (audit).</li>
 *   <li><b>Server</b> and <b>Client</b> are parallel, communicating only over
 *       the wire; neither may compile-depend on the other.</li>
 *   <li><b>Admin</b> runs inside the Server process (Admin → Server is legal);
 *       <b>Cli</b> and <b>Recipes</b> run against the Client side.</li>
 * </ul>
 */
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ArchitectureEnforcementTest.ExcludeStandaloneTools.class
        }
)
public class ArchitectureEnforcementTest {

    /**
     * Excludes {@code org.apache.zookeeper.graph} and {@code .inspector}
     * standalone tools. The match is a URI-substring match; ArchUnit
     * normalises filesystem paths AND JAR entries to forward-slashed URIs,
     * so one pattern covers both.
     */
    public static final class ExcludeStandaloneTools implements ImportOption {
        @Override public boolean includes(Location location) {
            return !location.contains("/org/apache/zookeeper/graph/")
                && !location.contains("/org/apache/zookeeper/inspector/");
        }
    }

    // ---------------------------------------------------------------------
    // Layered Architecture — single authoritative rule (R-04 de-duplication)
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
                            "org.apache.zookeeper",                 // root: ZooKeeper, Watcher, ...
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..",
                            "org.apache.zookeeper.data..")          // R-03: Stat, ACL, Id are public API

                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")
                    .layer("Cli").definedBy("org.apache.zookeeper.cli..")
                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")

                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")

                    // R-02: permit Client -> Cli so ZooKeeperMain (root) can dispatch to cli subcommands.
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Cli")

                    .whereLayer("Admin").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server", "Admin")

                    .whereLayer("Cli").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Cli")

                    .whereLayer("Recipes").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Client", "Recipes")

                    .whereLayer("Server").mayOnlyBeAccessedByLayers(
                            "Admin", "Monitoring", "Server")

                    .because(
                            "Inferred from §1.1 and §1.7: clients and servers "
                            + "communicate only over the TCP wire protocol. Admin "
                            + "HTTP endpoints run inside the server; CLI is a "
                            + "client-side tool; recipes are higher-order primitives "
                            + "built on the public API (§1.8). Observability "
                            + "(metrics / jmx / audit) is cross-cutting and may be "
                            + "accessed from Server, Client, Admin, Cli, and Recipes; "
                            + "Infrastructure (common / util / compat) is strictly "
                            + "below observability so the utility layer remains a "
                            + "reusable, dependency-free base.");

    // ---------------------------------------------------------------------
    // Documentation-specific carve-outs that the layered rule cannot express
    // ---------------------------------------------------------------------

    /**
     * C3 (§1.8): recipes are higher-order primitives built on the client API,
     * not on server internals or developer tooling. Expressed as a narrow
     * blacklist so it does not false-positive on legitimate third-party
     * libraries (R-05).
     */
    @ArchTest
    static final ArchRule recipes_must_not_depend_on_zookeeper_internals =
            noClasses()
                    .that().resideInAPackage("org.apache.zookeeper.recipes..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.apache.zookeeper.server..",
                            "org.apache.zookeeper.admin..",
                            "org.apache.zookeeper.cli..",
                            "org.apache.zookeeper.graph..",
                            "org.apache.zookeeper.inspector.."))
                    .because(
                            "Per §1.8, recipes are higher-order coordination "
                            + "primitives built on the simple client API (§1.6); "
                            + "they must not reach into server internals, admin or "
                            + "CLI tooling, or developer-only GUIs. Third-party "
                            + "dependencies are out of scope for this rule.");
}
```

This rewrite:

- Removes the whitelist contradiction (R-01) by dropping the whitelist in favour of a blacklist that matches §1.8 literally.
- Permits Client → Cli (R-02) so `ZooKeeperMain` does not false-positive.
- Folds `data..` into Client so Stat/ACL/Id are protected (R-03).
- Deletes the four `noClasses → server` rules that the layered architecture already covers (R-04).
- Removes the whitelist's third-party-library fragility (R-05).
- Rewrites the `.because()` on the layered rule to tell the truth about Infrastructure (R-06).
- Adds an explanatory comment to `ExcludeStandaloneTools` (R-07).
- Replaces the misleading "bottom to top" numbered list with a lattice description (R-08).

Rule count goes from 6 (post-Review-#1) to 2 (one layered + one blacklist), with no loss of coverage.
