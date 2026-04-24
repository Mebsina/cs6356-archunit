# Violation Analysis — `ArchitectureEnforcementTest` on Real ZooKeeper

Reviewer: opus-4-7
Scope: 180 reported violations across two failing rules
(`layered_architecture_is_respected` — 145; `public_api_must_not_leak_server_types` — 35)

---

## TL;DR

**Mostly a mapping mismatch with architectural reality, NOT genuine bugs in ZooKeeper.**

- ~95% of the 180 violations are structural artefacts of how ZooKeeper's
  `org.apache.zookeeper.server` package evolved: over the years it accumulated
  three distinct kinds of code — true server internals, shared utilities
  that happen to live under `server.*`, and cross-cutting concerns
  (auditing, metrics counters) that are in fact server-scoped. Our rules
  treat all of `server..` as one monolithic layer, so legitimate uses of
  the shared utilities register as violations.
- ~5% are **real mapping errors** in our rules that need fixing: the
  `audit` package is mapped to Support but is definitionally server-side;
  `ZooKeeperMain` is mapped to PublicApi but is definitionally a CLI tool.
- **Zero** of the violations are bugs the ZooKeeper project should fix.
  Every edge reported is a deliberate design choice by the ZK maintainers.

Verdict: the test is currently failing because our **rules are modelling a
documented architecture that the real code has outgrown**, not because
the real code is wrong. Fix the rules, not the code.

---

## The Violations, Grouped by Root Cause

The 180 reported edges collapse into six distinct patterns. Once you see
the pattern, the fix is obvious.

### Pattern A — Shared utility classes placed directly under `org.apache.zookeeper.server` (67 violations)

| Class under `server` (root) | Used by | Reality |
|------------------------------|---------|---------|
| `server.ZooKeeperThread` | `ClientCnxn$EventThread`, `ClientCnxn$SendThread`, `common.FileChangeWatcher$WatcherThread` | Thread subclass intended for both client and server |
| `server.ByteBufferInputStream` | `ClientCnxn$SendThread`, `ClientCnxnSocket` | Shared I/O utility for the wire protocol |
| `server.ZooTrace` | `ClientCnxn$SendThread` | Shared tracing helper |
| `server.ExitCode` | `ZooKeeperMain`, `Version`, `Shell` | Shared process-exit code enum |
| `server.EphemeralType` | `ZooKeeper`, `Op$Create`, `Op$CreateTTL`, `CreateOptions`, `cli.CreateCommand` | Validates TTL semantics for `CreateMode`; used by client API, CLI, and server |
| `server.watch.PathParentIterator` | `ZKWatchManager.addPersistentWatches` | Shared path iteration for persistent watches |

**Assessment:** These seven classes are the primary source of "client →
server" noise. Not one of them is a true server internal — they are
utilities that happen to sit under `server` for historical reasons.
Fixing their import locations upstream is out of scope; the rule must
account for the current layout.

### Pattern B — Shared sub-packages under `server.*` (48 violations)

| Sub-package | Nature |
|--------------|--------|
| `server.auth..` (`KerberosName`, `ProviderRegistry`) | SASL/auth machinery consumed by both `util.SecurityUtils` and `common.X509Util`. Genuinely shared. |
| `server.metric..` (`SimpleCounter`, `AvgMinMaxCounter`, `AvgMinMaxPercentileCounter`, `SimpleCounterSet`, `AvgMinMaxCounterSet`, `AvgMinMaxPercentileCounterSet`) | Concrete counter implementations consumed by `metrics.impl.DefaultMetricsProvider`. The metrics SPI lives in `metrics..` but the impls live in `server.metric..`. Genuinely shared. |
| `server.util..` (`ConfigUtils`, `VerifyingFileFactory`) | Generic config/file utilities used by `common.ZKConfig` and `cli.GetConfigCommand`. Genuinely shared. |

**Assessment:** These three sub-packages are misnamed. They are in the
`server` namespace but they serve as shared utilities. In a cleaner
redesign they would live under `common`. Our rules should treat them as
Support, not Server.

### Pattern C — `audit` package legitimately needs server types (30 violations)

All 30 violations emitted by `audit.AuditHelper` and `audit.ZKAuditProvider`
reference genuine server-only types: `server.Request`, `server.DataTree`,
`server.ServerCnxn`, `server.ServerCnxnFactory`.

| Method | Uses |
|--------|------|
| `AuditHelper.addAuditLog` | `server.Request`, `server.DataTree.ProcessTxnResult` |
| `AuditHelper.log` | `server.Request`, `server.ServerCnxn` |
| `AuditHelper.logMultiOperation` | `server.Request`, `server.DataTree.ProcessTxnResult` |
| `AuditHelper.getCreateModes`, `getResult` | `server.Request`, `server.DataTree.ProcessTxnResult` |
| `ZKAuditProvider.getZKUser` | `server.ServerCnxnFactory` |

**Assessment:** This is a **real mapping error in our rules.** The `audit`
package is definitionally about auditing **server-side** operations — it
records every `Request` the server processes. It has no meaningful use on
the client side. It should be in the Server layer, not Support.

### Pattern D — CLI genuinely needs server types (5 violations)

| Edge | Reason |
|------|--------|
| `cli.CreateCommand.exec → server.EphemeralType.toEphemeralOwner / .TTL` | (Same Pattern A utility) |
| `cli.GetConfigCommand.exec → server.util.ConfigUtils.getClientConfigStr` | (Same Pattern B sub-package) |
| `cli.ReconfigCommand.parse → server.quorum.QuorumPeerConfig.parseDynamicConfig` | Reconfigure command inherently manipulates server quorum config |
| `cli.ReconfigCommand.parse → server.quorum.flexible.QuorumVerifier.toString()` | Same |

**Assessment:** 3 of 5 dissolve once Pattern A and Pattern B are handled.
The remaining 2 edges to `server.quorum..` are genuine: `ReconfigCommand`
is a privileged CLI that rewrites server-side quorum configuration. The
documentation does not rule this out (§1.7 describes the quorum as server
internal but doesn't forbid administrative tooling from reaching it). A
narrow `ignoresDependency(...).ofType(ReconfigCommand.class)` or a CLI
sub-layer exception is appropriate.

### Pattern E — `ZooKeeperMain` is classified as PublicApi but is really a CLI tool (19 violations)

All of these are `ZooKeeperMain` → `cli.*`:

| Target type | Count |
|-------------|-------|
| `cli.CliCommand` | 5 |
| `cli.CommandFactory` / `cli.CommandFactory$Command` | 3 |
| `cli.CliException` | 3 |
| `cli.CommandNotFoundException` | 1 |
| `cli.MalformedCommandException` | 3 |
| `cli.CliCommand$commandMapCli` field | 1 |
| Other | 3 |

**Assessment:** `ZooKeeperMain` is the entry-point class for the
interactive ZooKeeper shell — it is, by definition, a tool. Our rule
assigns it to PublicApi because it sits in the root package. This is a
**real mapping error** caused by ZooKeeper putting a tool class in the
root package alongside the contract types. The fix is not to move
ZooKeeperMain (we can't modify ZK), but to tell ArchUnit that this one
class should be treated as a Tool.

### Pattern F — `ZooKeeperMain` also uses `server.ExitCode` (11 violations)

Same class as Pattern E, but reaching down into `server.ExitCode`. Once
ZooKeeperMain is reclassified to Tools (Pattern E fix) AND `ExitCode` is
reclassified to Support (Pattern A fix), these violations disappear
automatically.

---

## Is this a Bug in ZooKeeper, or a Bug in our Rules?

| Question | Answer |
|----------|--------|
| Are any of these 180 violations caused by the ZK team putting a class in the wrong place? | No. Every edge is intentional. |
| Are any caused by the ZK documentation claiming an architecture the code doesn't have? | Partly — the PDF's "clients and servers communicate only over the TCP wire protocol" is a *runtime* statement that doesn't hold at *compile time* because shared utilities live under `server..`. |
| Are any caused by our rules misclassifying a package? | Yes — `audit` is Support but should be Server; `ZooKeeperMain` is treated as PublicApi but is a Tool. |
| Are any caused by our rules treating `server..` as monolithic? | Yes — most of them. Three sub-packages (`server.auth`, `server.metric`, `server.util`) plus six specific classes directly under `server.*` are shared utilities masquerading as server internals. |

---

## Recommended Fix

Restructure the layer definitions so the actual layout is reflected:

```java
.layer("Support").definedBy(
        "org.apache.zookeeper.common..",
        "org.apache.zookeeper.util..",
        "org.apache.zookeeper.metrics..",
        "org.apache.zookeeper.jmx..",
        "org.apache.zookeeper.compat..",
        "org.apache.zookeeper.compatibility..",
        // Shared utilities historically placed under `server.*`:
        "org.apache.zookeeper.server.auth..",      // SASL/auth used by client too
        "org.apache.zookeeper.server.metric..",    // counter impls used by metrics SPI
        "org.apache.zookeeper.server.util..")      // generic config/file utilities
.layer("Server").definedBy(
        "org.apache.zookeeper.server.admin..",
        "org.apache.zookeeper.server.command..",
        "org.apache.zookeeper.server.embedded..",
        "org.apache.zookeeper.server.persistence..",
        "org.apache.zookeeper.server.quorum..",
        "org.apache.zookeeper.server.watch..",
        "org.apache.zookeeper.server.backup..",
        "org.apache.zookeeper.audit..")            // audit is a server-side concern
.layer("PublicApi").definedBy("org.apache.zookeeper")
.layer("Client").definedBy(
        "org.apache.zookeeper.client..",
        "org.apache.zookeeper.admin..",
        "org.apache.zookeeper.retry..")
.layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
.layer("Tools").definedBy(
        "org.apache.zookeeper.cli..",
        "org.apache.zookeeper.inspector..",
        "org.apache.zookeeper.graph..")
```

Three additional tweaks:

### 1. Handle the six shared-utility classes directly under `server.*`

The classes `ZooKeeperThread`, `ByteBufferInputStream`, `ZooTrace`,
`ExitCode`, `EphemeralType`, and `PathParentIterator` are in `server`
itself, not a sub-package, so a prefix glob cannot peel them out. Two
options:

**Option 1 (recommended): allow a narrow set of `server.*` simple names
from other layers.**

```java
private static final DescribedPredicate<JavaClass> shared_server_utilities =
        resideInAPackage("org.apache.zookeeper.server")
            .and(have(simpleName("ZooKeeperThread")
                 .or(simpleName("ByteBufferInputStream"))
                 .or(simpleName("ZooTrace"))
                 .or(simpleName("ExitCode"))
                 .or(simpleName("EphemeralType"))
                 .or(simpleName("PathParentIterator"))))
            .as("shared utilities historically placed in org.apache.zookeeper.server");
```

and reference it in the `.ignoreDependency(...)` clause of the layered
rule and the fine-grained rule.

**Option 2: accept these six edges as architectural debt and document
them.**

Add `.allowEmptyShould(true)` is NOT what we want — instead, add a
comment above the rule plus a per-class `ignoreDependency` list.

### 2. Handle `ZooKeeperMain` being a tool that lives in root

Since we cannot move the class, tell ArchUnit this one class is a Tool:

```java
.layer("PublicApi")
    .definedBy(JavaClass.Predicates
        .resideInAPackage("org.apache.zookeeper")
        .and(not(simpleName("ZooKeeperMain"))
             .and(not(simpleName("ZooKeeperMain$MyCommandOptions")))))
.layer("Tools")
    .definedBy(JavaClass.Predicates
        .resideInAnyPackage(
            "org.apache.zookeeper.cli..",
            "org.apache.zookeeper.inspector..",
            "org.apache.zookeeper.graph..")
        .or(simpleName("ZooKeeperMain"))
        .or(simpleName("ZooKeeperMain$MyCommandOptions")))
```

Note: `layeredArchitecture()` accepts class-level predicates via
`definedBy(DescribedPredicate<JavaClass>)` overload; the code above is
schematic. Confirm the exact API for the archunit version in use.

### 3. Narrow the remaining `cli.ReconfigCommand → server.quorum.*`

The only genuinely cross-layer edge that remains after the restructure
is the reconfigure CLI reaching into server quorum config. Two options:

- **Ignore it with a documented rationale** (one edge, one `ignoreDependency`).
- **Accept it as legal** by noting that administrative CLI tools may
  legitimately reach into server configuration, and add a dedicated
  `ReconfigAdmin` exception.

My recommendation: document-and-ignore. It is a single, narrowly-scoped
exception that does not generalise to "CLI may call server freely".

---

## Expected Outcome After the Fix

| Pattern | Violations before | Violations after |
|---------|-------------------|------------------|
| A — shared classes directly in `server.*` | 67 | 0 (via per-class predicate or ignoreDependency) |
| B — shared sub-packages (`server.auth/metric/util`) | 48 | 0 (moved to Support) |
| C — `audit` → server types | 30 | 0 (`audit` moved to Server) |
| D — CLI utility overlap | 5 | 2 (`ReconfigCommand` remains; ignore with rationale) |
| E — `ZooKeeperMain` → cli | 19 | 0 (class reclassified to Tools) |
| F — `ZooKeeperMain` → `server.ExitCode` | 11 | 0 (cascades from A and E) |
| **Total** | **180** | **~2 documented exceptions** |

Both rules should pass after the restructure, with two documented
`ignoreDependency` entries for the `ReconfigCommand` edges.

---

## Lesson

The review methodology ran for seven rounds against the PDF documentation
and produced a rule set that precisely encoded the documented intent. But
the documentation describes the architecture as the ZooKeeper team wants
you to **think** about it — client vs. server separation, a narrow public
API — while the compile-time layout of the repo diverges from that ideal
in three specific ways:

1. Shared utilities accumulated under `server.*` rather than `common/util`.
2. `audit` is functionally server-side but its package name suggests a
   cross-cutting concern.
3. The public client API shares a package with one standalone tool
   (`ZooKeeperMain`).

No amount of adversarial PDF review can reveal (1)–(3); they only surface
when you run the rules against `target/classes`. This is the value of
closing the loop with a real build, which is exactly what just happened.
The next revision of the rules should encode the **actual** layout, with
the three-line comments explaining why each deviation from the PDF's
stated ideal exists.
