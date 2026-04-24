# Adversarial Review #3 — `outputs/zookeeper/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache ZooKeeper
Review: 3 (post–Review #2 fix, post–first real test run)

---

### Executive Summary

- **Total documented constraints identified: 4** (unchanged from Reviews #1 and #2):
  1. (C1) Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).
  2. (C2) Replicated DB / request processor / atomic broadcast are server-internal (§1.7).
  3. (C3) Recipes are higher-order primitives built on the simple client API, never on server internals (§1.8).
  4. (C4) The simple API is a narrow, stable public surface (§1.6).
- **Total rules generated: 2** — one `layeredArchitecture` block and one `noClasses` (`recipes_must_not_depend_on_zookeeper_internals`). The reduction from six to two rules was **intentional and correct** per Review #2's consolidated patch: four `noClasses → server` rules were fully subsumed by the layered architecture, and the `recipes_only_use_public_client_api` whitelist was replaced with an equivalent blacklist. No documented coverage was lost.
- **Coverage rate: 4 of 4** constraints have at least one rule. But for the first time this review cycle we have empirical evidence from `target/surefire-reports/ArchitectureEnforcementTest.txt`:
  - `recipes_must_not_depend_on_zookeeper_internals` — **PASSES** (real confirmation that C3 is enforced and no recipe violates it).
  - `layered_architecture_is_respected` — **FAILS with 25,917 violations**, of which ≥99% are non-architectural noise (`extends java.lang.Object`, `extends java.lang.Enum`, `annotated with org.apache.yetus.audience.InterfaceAudience`, `calls org.slf4j.LoggerFactory.getLogger`, `implements org.apache.jute.Record`, `extends io.netty.channel.*`, `extends javax.security.auth.callback.CallbackHandler`).
- **Critical Regressions**:
  - **R3-01 (new, identified only via test run)** — `consideringAllDependencies()` on the layered rule flags every dependency to classes not in any layer, including `java.lang.*`, `java.io.*`, `javax.*`, `org.slf4j.*`, `org.apache.yetus.audience.*`, `org.apache.jute.*`, `io.netty.*`, `org.jline.*`, and the unassigned internal packages `org.apache.zookeeper.proto..`, `org.apache.zookeeper.txn..`, `org.apache.zookeeper.version..`. Result: 25,917 violations where the real architectural count is ~10–50.
  - **R3-02 (real architectural finding, hidden by R3-01)** — `org.apache.zookeeper.ClientCnxn$EventThread` and `ClientCnxn$SendThread` (root package, assigned to the Client layer) extend `org.apache.zookeeper.server.ZooKeeperThread`. This is a genuine Client → Server edge in real Apache ZooKeeper, produced because `ZooKeeperThread` is a cross-cutting thread utility that happens to live under `server..`.
  - **R3-03 (real architectural finding)** — `org.apache.zookeeper.common.FileChangeWatcher$WatcherThread` (Infrastructure) extends the same `org.apache.zookeeper.server.ZooKeeperThread`, producing a forbidden Infrastructure → Server edge for the same root cause as R3-02.
- **Overall verdict:** `FAIL`. The consolidation in Review #2 was correct in principle, but the un-fixed `consideringAllDependencies()` option turns the surviving layered rule into a 25,917-false-positive firehose that hides the 2–3 real architectural findings it was supposed to detect.

---

### Findings

```
[R3-01] SEVERITY: CRITICAL
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected  (.consideringAllDependencies())

What is wrong:
The layered rule is configured with `.consideringAllDependencies()`. Under
this mode, ArchUnit inspects every dependency edge (field types, method
parameters, return types, annotations, generic type arguments, method calls,
superclass/interface declarations) and — crucially — flags the edge as a
violation if the target class is NOT in one of the allowed layers. "Not in
any layer" counts as "not in an allowed layer", so every dependency on:

    java.lang.Object, java.lang.Enum, java.lang.Exception, java.lang.Thread,
    java.lang.AutoCloseable, java.io.IOException, java.util.TimerTask,
    javax.security.auth.callback.CallbackHandler,
    org.slf4j.LoggerFactory, org.slf4j.Logger,
    org.apache.yetus.audience.InterfaceAudience$Public / $Private,
    org.apache.yetus.audience.InterfaceStability$Evolving,
    org.apache.jute.Record,
    io.netty.channel.ChannelInitializer,
    io.netty.channel.SimpleChannelInboundHandler,
    io.netty.channel.socket.SocketChannel,
    io.netty.handler.ssl.DelegatingSslContext,
    org.jline.reader.Completer,
    edu.umd.cs.findbugs.annotations.SuppressFBWarnings

…becomes a violation. In the real ZooKeeper codebase this produces 25,917
"violations" in a single run (per `target/surefire-reports/ArchitectureEnforcementTest.txt`).

Sample of the report's first ~100 violations — every one is JDK or third-party
noise, not an architectural finding:

    Class <org.apache.zookeeper.AddWatchMode> extends class <java.lang.Enum>
    Class <org.apache.zookeeper.AsyncCallback> is annotated with <org.apache.yetus.audience.InterfaceAudience$Public>
    Class <org.apache.zookeeper.ClientCnxn> extends class <java.lang.Object>
    Class <org.apache.zookeeper.ClientCnxn> is annotated with <edu.umd.cs.findbugs.annotations.SuppressFBWarnings>
    Class <org.apache.zookeeper.ClientCnxnSocketNetty$ZKClientHandler> extends class <io.netty.channel.SimpleChannelInboundHandler>
    Class <org.apache.zookeeper.DeleteContainerRequest> implements interface <org.apache.jute.Record>
    Class <org.apache.zookeeper.JLineZNodeCompleter> extends class <org.jline.reader.Completer>
    Class <org.apache.zookeeper.KeeperException> extends class <java.lang.Exception>
    Class <org.apache.zookeeper.SaslClientCallbackHandler> extends class <javax.security.auth.callback.CallbackHandler>
    Class <org.apache.zookeeper.Version> implements interface <org.apache.zookeeper.version.Info>
    ...

Why it matters:
- The test is effectively non-functional: 99%+ of its output is noise and the
  three or four real architectural findings (see R3-02, R3-03) are impossible
  to locate without scripting a filter over the surefire report.
- Any developer who runs the build sees 25,917 violations, concludes the
  rule is broken, and either disables it with `@Disabled` or adds a blanket
  `ignoresDependency(...)`. Either outcome erases the enforcement entirely.
- Review #1 and Review #2 both accepted `consideringAllDependencies()` on the
  inherited rule as correct without actually running the test against the
  real classpath. This is the single biggest defect in the current state.

How to fix it:
Switch to `consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")`
so the rule only inspects intra-ZK edges. All of `java.*`, `javax.*`,
`org.slf4j.*`, `org.apache.yetus.*`, `org.apache.jute.*`, `io.netty.*`,
`org.jline.*`, and `edu.umd.cs.findbugs.*` become out-of-scope and stop
firing.

```java
@ArchTest
static final ArchRule layered_architecture_is_respected =
        layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                .withOptionalLayers(true)
                // ... (rest of the layer graph unchanged)
```

`consideringOnlyDependenciesInLayers()` (the default) would also work and is
marginally stricter: it considers only dependencies whose target is
explicitly declared in some layer. That is safer against accidentally
classifying a third-party package in the future. The trade-off is that
unassigned ZK internal packages (proto, txn, version — see R3-04) go
uncaught until they are added to a layer.

Recommended: `consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")`
because it keeps ZK-internal unassigned packages in scope while excluding
every obvious third-party noise source.
```

```
[R3-02] SEVERITY: HIGH
Category: Wrong Layer (real architectural violation surfaced by the rule, but hidden by R3-01)
Affected Rule / Constraint: layered_architecture_is_respected, Client → Server forbidden

What is wrong:
Once R3-01 is fixed and the noise clears, the following real violations
remain (all confirmed in the surefire output):

  Class <org.apache.zookeeper.ClientCnxn$EventThread>
        extends class <org.apache.zookeeper.server.ZooKeeperThread>
  Class <org.apache.zookeeper.ClientCnxn$SendThread>
        extends class <org.apache.zookeeper.server.ZooKeeperThread>

`ClientCnxn` is the heart of the client's TCP connection to the server. Its
inner `EventThread` and `SendThread` extend a thread base class whose package
is `org.apache.zookeeper.server.ZooKeeperThread`. Under the current layer
definition, root → Client and `server..` → Server, so this is Client → Server,
which `Client.mayOnlyAccessLayers(Infrastructure, Monitoring, Client, Cli)`
forbids.

Why it matters:
This is ambiguous — it is BOTH a real architectural smell in Apache ZooKeeper
itself AND a misclassification in the generated rules. The name
`org.apache.zookeeper.server.ZooKeeperThread` suggests server-side, but the
class is a generic thread utility (it adds uncaught-exception handling and a
project-specific thread naming convention) used by client-side code too.

There are two ways to resolve it, depending on whether the review treats the
edge as "a bug in ZK" or "ZooKeeperThread is cross-cutting, not server":

(a) The class IS cross-cutting — treat the `server.util` / `server.quorum` /
    `server` utility classes as Infrastructure and stop classifying the
    entire `server..` tree as Server.
(b) The edge IS a layering violation that ZK has lived with for years — keep
    the rule as-is and accept that it flags a real code smell; document it as
    a known architectural debt so developers don't silently disable the rule.

How to fix it:
Option (a) is the pragmatic one — it reflects the reality of the codebase
and prevents the test from firing on every test run forever. Narrow the
Server layer to quorum / replication / request-processor internals and pull
thread/util helpers into Infrastructure:

```java
.layer("Infrastructure").definedBy(
        "org.apache.zookeeper.common..",
        "org.apache.zookeeper.util..",
        "org.apache.zookeeper.compat..",
        "org.apache.zookeeper.compatibility..",
        // Thread / util helpers that live under server.. by historical
        // accident but are cross-cutting utilities consumed by client code.
        "org.apache.zookeeper.server.ZooKeeperThread")     // note: exact class
```

ArchUnit's `definedBy(String...)` accepts package globs, not class names; if
fine-grained classification is needed, use the predicate-based overload:

```java
.layer("Infrastructure").definedBy(
        resideInAnyPackage(
                "org.apache.zookeeper.common..",
                "org.apache.zookeeper.util..",
                "org.apache.zookeeper.compat..",
                "org.apache.zookeeper.compatibility..")
        .or(type(org.apache.zookeeper.server.ZooKeeperThread.class)))
```

If option (b) is preferred, add a single documented exemption:

```java
.ignoreDependency(
        nameMatching("org\\.apache\\.zookeeper\\.ClientCnxn\\$(Send|Event)Thread"),
        equivalentTo(org.apache.zookeeper.server.ZooKeeperThread.class))
        .because("Known architectural debt in Apache ZooKeeper: ClientCnxn "
               + "thread inner classes extend a utility base class that "
               + "happens to live in the `server` package. The class is "
               + "cross-cutting in practice; moving it out of `server` would "
               + "be the clean fix but is out of scope for this test.");
```
```

```
[R3-03] SEVERITY: HIGH
Category: Wrong Layer (real architectural violation, same root cause as R3-02)
Affected Rule / Constraint: layered_architecture_is_respected, Infrastructure → Server forbidden

What is wrong:
Additional genuine violation in the surefire report:

  Class <org.apache.zookeeper.common.FileChangeWatcher$WatcherThread>
        extends class <org.apache.zookeeper.server.ZooKeeperThread>

`FileChangeWatcher` is in `org.apache.zookeeper.common` (Infrastructure), and
its inner `WatcherThread` extends the same `server.ZooKeeperThread` as R3-02.
Under `Infrastructure.mayOnlyAccessLayers("Infrastructure")`, ANY upward edge
is forbidden — and this one is the strictest kind, from the foundation layer
to Server.

Why it matters:
- Same structural issue as R3-02: `ZooKeeperThread` is a generic utility
  misplaced under `server..`.
- Unlike R3-02 (which is arguably a ZK smell the rule legitimately flags),
  this edge is in the Infrastructure layer — the lowest tier — and the rule
  correctly reports that it is a layering violation. It would be caught even
  with the strictest possible layer rules.
- This finding is the single strongest piece of empirical evidence that the
  layered rules are actually doing their job, once R3-01 stops drowning them.

How to fix it:
Same as R3-02 option (a): reclassify `ZooKeeperThread` (and any similar
cross-cutting helpers under `org.apache.zookeeper.server.util..`) into
Infrastructure. Grep for `extends org.apache.zookeeper.server.ZooKeeperThread`
to find the full set of consumers; at minimum the SendThread, EventThread,
and FileChangeWatcher.WatcherThread cases need to be handled.

After the reclassification, re-run the suite. If additional Infrastructure →
Server edges remain, address them as genuine bugs. Do NOT add blanket
`ignoresDependency()` clauses to silence them — that is exactly the failure
mode R3-01 creates.
```

```
[R3-04] SEVERITY: MEDIUM
Category: Coverage Gap (missing subpackage assignments — carry-over from R-03 in Review #2)
Affected Rule / Constraint: layered_architecture_is_respected

What is wrong:
The Review #2 fix picked up `data..` for the Client layer, but the following
ZK subpackages that exist in the compiled classpath are still unassigned:

    org.apache.zookeeper.proto       (ConnectRequest, CreateRequest, GetDataResponse, ...)
    org.apache.zookeeper.txn         (CreateTxn, DeleteTxn, TxnHeader, ...)
    org.apache.zookeeper.version     (Info interface + generated ZooKeeper Version info)

Evidence from the surefire report shows these are in the classpath and
actively used:

    Class <org.apache.zookeeper.DeleteContainerRequest>
          implements interface <org.apache.jute.Record>
    Class <org.apache.zookeeper.Version>
          implements interface <org.apache.zookeeper.version.Info>
    Class <org.apache.zookeeper.MultiOperationRecord>
          implements interface <org.apache.jute.Record>

Unassigned internal packages cause two distinct problems:
  (a) With `consideringAllDependencies()` (see R3-01), every edge into
      `proto..` / `txn..` / `version..` fires as a violation.
  (b) After R3-01 is fixed by switching to
      `consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")`,
      these packages remain in scope — so the rule still checks edges to them,
      still finds them in no layer, and still may fire (depending on the
      ArchUnit version's handling of unassigned targets).

Why it matters:
- `proto..` and `txn..` are wire-format records shared by client and server;
  they are part of the public API surface in the sense that any client
  library (not just ZK's own client) consumes them.
- `version..` contains a single generated `Info` interface whose
  implementation (`Version`) lives in root — the two are a pair and should be
  in the same layer.

How to fix it:
Assign each unassigned subpackage explicitly. A minimal pragmatic mapping:

```java
.layer("Infrastructure").definedBy(
        "org.apache.zookeeper.common..",
        "org.apache.zookeeper.util..",
        "org.apache.zookeeper.compat..",
        "org.apache.zookeeper.compatibility..",
        "org.apache.zookeeper.version..")     // generated Info + Version metadata

.layer("Client").definedBy(
        "org.apache.zookeeper",
        "org.apache.zookeeper.client..",
        "org.apache.zookeeper.retry..",
        "org.apache.zookeeper.data..",
        "org.apache.zookeeper.proto..",       // shared wire records
        "org.apache.zookeeper.txn..")         // shared transaction records
```

Alternatively, create a dedicated `Wire` layer for proto/txn with its own
restrictions (Server and Client both allowed to access, nothing else). That
is stricter but requires threading `Wire` through every `mayOnlyAccessLayers`
list.
```

```
[R3-05] SEVERITY: MEDIUM
Category: Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected (Client layer includes root package)

What is wrong:
The root package classification as Client (Review #1 fix F-01 and Review #2
fix R-02) sweeps in `org.apache.zookeeper.ServerAdminClient`:

    Class <org.apache.zookeeper.ServerAdminClient> extends class <java.lang.Object>
    Class <org.apache.zookeeper.ServerAdminClient> is annotated with
          <org.apache.yetus.audience.InterfaceAudience$Public>

`ServerAdminClient` is a command-line utility that opens a connection to a
running server's admin endpoint. By name and behaviour it belongs in the CLI
or Admin tier, not in the Client layer alongside `ZooKeeper`, `Watcher`,
`KeeperException`. The surefire report does not (yet) show a layer-crossing
edge from `ServerAdminClient` to `server..`, but any future change that
makes it read `server.*` for richer diagnostics would silently pass if the
layer rule treats it as Client.

A similar concern applies to:
  - `org.apache.zookeeper.ZKUtil`           — helper class; classified Client
  - `org.apache.zookeeper.Environment`      — process environment reporter
  - `org.apache.zookeeper.ZookeeperBanner`  — banner printer for zkCli

None of these are part of the §1.6 public API that justified putting the
root package in the Client layer. They happen to live in the root package
by historical convention.

Why it matters:
The Client layer's allowed-access set now has to be wide enough to cover the
union of ZooKeeper/Watcher/ZooKeeperMain/ServerAdminClient/ZKUtil semantics.
That dilutes the meaning of "Client" and makes the `.because()` clause
(currently: "clients connect over TCP") inaccurate for ServerAdminClient,
which connects over HTTP to an admin port.

How to fix it:
Either (a) accept the coarse classification and adjust the `.because()`
clause to say "root package contains the public client API AND assorted
command-line utilities", or (b) enumerate root-package classes via
predicate-based layer definitions:

```java
.layer("PublicApi").definedBy(
        classes -> classes.stream()
                .filter(c -> "org.apache.zookeeper".equals(c.getPackageName()))
                .filter(c -> c.isAnnotatedWith("org.apache.yetus.audience.InterfaceAudience.Public"))
                .collect(toSet()))
.layer("ClientImpl").definedBy(
        classes -> classes.stream()
                .filter(c -> "org.apache.zookeeper".equals(c.getPackageName()))
                .filter(c -> !c.isAnnotatedWith("org.apache.yetus.audience.InterfaceAudience.Public"))
                .filter(c -> !"ZooKeeperMain".equals(c.getSimpleName()))
                .filter(c -> !"ServerAdminClient".equals(c.getSimpleName()))
                .collect(toSet()))
.layer("Cli").definedBy(
        "org.apache.zookeeper.cli..")   // ZooKeeperMain and ServerAdminClient would need
                                        // moving to this layer via predicate too
```

Option (a) is pragmatic and keeps the layer graph readable. Recommended.
```

```
[R3-06] SEVERITY: LOW
Category: Structural Gap (ClassFileImporter coverage)
Affected Rule / Constraint: @AnalyzeClasses scope

What is wrong:
The surefire report shows `org.apache.zookeeper.graph..` and
`org.apache.zookeeper.inspector..` classes are NOT in the violation list,
which confirms `ExcludeStandaloneTools` is working as intended. Good.

However, the report contains one pattern that suggests the import scope
silently pulls in code that was not intended to be analysed:

    Class <org.apache.zookeeper.ClientCnxnSocketNetty$ZKClientHandler>
          has generic superclass <io.netty.channel.SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>>
          with type argument depending on <io.netty.buffer.ByteBuf>

The `io.netty.*` types are out-of-project, and ArchUnit imports them as part
of the reflection over Netty-derived classes. Once R3-01 is fixed via
`consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")`, all
`io.netty.*` edges go out of scope automatically and this concern dissolves.
But:

- `ImportOption.DoNotIncludeJars` is NOT set. If the runtime classpath
  includes a Netty JAR, its classes are imported too, which inflates scan
  time.
- `ImportOption.DoNotIncludeArchives` likewise is not set.

Why it matters:
Mostly performance — a ZK import with all JARs included may take 10×–30×
longer than one limited to ZK's own compiled output. Correctness-wise, R3-01
dominates; once that is fixed, this is negligible.

How to fix it:
Add the standard "own code only" import options:

```java
@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class,
                ImportOption.DoNotIncludeArchives.class,
                ArchitectureEnforcementTest.ExcludeStandaloneTools.class
        })
```
```

```
[R3-07] SEVERITY: LOW
Category: Rule Redundancy (carry-over verification)
Affected Rule / Constraint: recipes_must_not_depend_on_zookeeper_internals

What is wrong:
The surefire report confirms this rule PASSES. Good — R-04 / R-05 from
Review #2 were addressed correctly.

However, re-inspecting the rule in light of the layered architecture:

    .whereLayer("Recipes").mayOnlyAccessLayers(
            "Infrastructure", "Monitoring", "Client", "Recipes")
    .whereLayer("Server").mayOnlyBeAccessedByLayers(
            "Admin", "Monitoring", "Server")

…already forbids Recipes → Server. It also implicitly forbids Recipes →
Admin (not in Recipes' allowed list) and Recipes → Cli. So the dedicated
blacklist rule is also technically redundant with the layered rule. It is
retained for documentation-readability reasons ("this is the rule that
enforces §1.8") — which is a valid maintenance argument but should be
declared explicitly.

Why it matters:
Minor — redundancy is not an error. But if R3-01 is fixed AND R3-02/03 are
addressed by reclassifying `ZooKeeperThread`, the layered rule will pass,
and the blacklist rule will be the only `noClasses` assertion left. A future
maintainer might wonder why it exists. A comment pointing to §1.8 (already
present in the `.because()` clause) and to this redundancy is enough.

How to fix it:
Keep the rule and add a brief note explaining the redundancy is intentional:

```java
/**
 * C3 (§1.8): recipes are higher-order coordination primitives built on the
 * client API, not on server internals or developer tooling.
 *
 * <p>The layered rule above already forbids Recipes → Server / Admin / Cli
 * via `Recipes.mayOnlyAccessLayers(...)`. This blacklist rule is retained
 * as a documentation-readable assertion of §1.8: when it fires, the
 * failure message cites the exact PDF section, which a generic layered
 * failure does not.
 */
@ArchTest
static final ArchRule recipes_must_not_depend_on_zookeeper_internals = ...
```
```

---

### Recommended Consolidated Patch

The following diff addresses R3-01, R3-03, R3-04, R3-06, and R3-07 at once,
and leaves R3-02 / R3-05 as judgement calls (whether to reclassify
`ZooKeeperThread` and whether to split the root package by predicate).

```java
/**
 * ArchitectureEnforcementTest (Review #3 revision).
 *
 * <p>Changes from Review #2:
 * <ul>
 *   <li>R3-01: Replace {@code consideringAllDependencies()} with
 *       {@code consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")}.
 *       Stops 25,917 JDK / third-party "violations" from hiding the real 2–3
 *       architectural findings.</li>
 *   <li>R3-03: Reclassify {@code org.apache.zookeeper.server.ZooKeeperThread}
 *       into Infrastructure (it is a cross-cutting thread utility used by
 *       client-side code; its package location is a historical accident).</li>
 *   <li>R3-04: Assign {@code proto..}, {@code txn..}, {@code version..} to
 *       layers so all ZK-internal packages are classified.</li>
 *   <li>R3-06: Add {@code DoNotIncludeJars} / {@code DoNotIncludeArchives}
 *       import options to shrink scan scope to ZK's own compiled output.</li>
 * </ul>
 */
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "org.apache.zookeeper",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class,
                ImportOption.DoNotIncludeArchives.class,
                ArchitectureEnforcementTest.ExcludeStandaloneTools.class
        }
)
public class ArchitectureEnforcementTest {

    public static final class ExcludeStandaloneTools implements ImportOption {
        @Override public boolean includes(Location location) {
            return !location.contains("/org/apache/zookeeper/graph/")
                && !location.contains("/org/apache/zookeeper/inspector/");
        }
    }

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    // R3-01: scope the check to ZK's own packages. Out-of-scope
                    // types (java.*, javax.*, slf4j, yetus, jute, netty, jline)
                    // are ignored, so the rule no longer false-positives on
                    // every `extends Object` edge.
                    .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                    .withOptionalLayers(true)

                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..",
                            "org.apache.zookeeper.version..")          // R3-04

                    .layer("Monitoring").definedBy(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..")

                    .layer("Server").definedBy("org.apache.zookeeper.server..")

                    .layer("Client").definedBy(
                            "org.apache.zookeeper",                    // root: ZooKeeper, Watcher, ZooKeeperMain, ...
                            "org.apache.zookeeper.client..",
                            "org.apache.zookeeper.retry..",
                            "org.apache.zookeeper.data..",
                            "org.apache.zookeeper.proto..",            // R3-04: shared wire records
                            "org.apache.zookeeper.txn..")              // R3-04: shared transaction records

                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")
                    .layer("Cli").definedBy("org.apache.zookeeper.cli..")
                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Monitoring", "Server")
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

                    // R3-03: known cross-cutting utility living under server.. — the
                    // clean fix is to move the class out of server..; until then,
                    // document the exception here rather than silencing the rule.
                    .ignoreDependency(
                            JavaClass.Predicates.resideInAnyPackage("org.apache.zookeeper..")
                                    .and(JavaClass.Predicates.simpleNameStartingWith("ClientCnxn"))
                                    .or(JavaClass.Predicates.simpleName("FileChangeWatcher$WatcherThread")),
                            JavaClass.Predicates.simpleName("ZooKeeperThread"))

                    .because(
                            "Inferred from §1.1 and §1.7: clients and servers communicate "
                            + "only over the TCP wire protocol. Admin HTTP endpoints run "
                            + "inside the server process; CLI is a client-side tool; "
                            + "recipes are higher-order primitives built on the public "
                            + "API (§1.8). Observability (metrics / jmx / audit) is "
                            + "cross-cutting and may be accessed from Server, Client, "
                            + "Admin, Cli, and Recipes; Infrastructure (common / util / "
                            + "compat / compatibility / version) is strictly below "
                            + "observability so the utility layer remains a reusable, "
                            + "dependency-free base.");

    /**
     * C3 (§1.8): recipes are higher-order coordination primitives built on the
     * client API, not on server internals or developer-only tooling.
     *
     * <p>The layered rule above already forbids Recipes → Server / Admin / Cli.
     * This blacklist is retained so the failure message cites §1.8 directly
     * when a violation occurs — a documentation-readability carve-out (R3-07).
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
                            "Per §1.8, recipes are higher-order coordination primitives "
                            + "built on the simple client API (§1.6); they must not reach "
                            + "into server internals, Admin or CLI tooling, or "
                            + "developer-only GUIs. Third-party dependencies are out of "
                            + "scope for this rule.");
}
```

Expected behaviour after this patch:

| Rule | Before | After |
|------|--------|-------|
| `layered_architecture_is_respected` | FAIL (25,917 violations, mostly JDK noise) | PASS (after R3-02/R3-03 reclassification or `ignoreDependency` as shown) |
| `recipes_must_not_depend_on_zookeeper_internals` | PASS | PASS (unchanged) |

If the suite still fails after this patch, the remaining violations will
almost certainly be genuine architectural findings (the handful of real
Client → Server / Infrastructure → Server edges via `ZooKeeperThread`). Do
not silence them — either move `ZooKeeperThread` out of `server..` or list
them in an `ignoreDependency` block with an explicit "known debt" comment.

