# Violation Analysis #2 — Post-Remap Results

Reviewer: opus-4-7
Scope: 158 + 2 = 160 violations after applying analysis1 recommendations
Previous count: 180; current count: 160.

---

## TL;DR

**Still mostly mapping errors, but the failure profile has completely shifted.**
The previous 180 violations (client/support reaching into `server..` for
shared utilities) are almost entirely gone — the remap successfully
absorbed them. The new 160 violations expose a second, deeper tangle that
the first analysis missed:

1. **My round-1 advice to lift `server.util..` and `server.auth..` wholesale
   into Support was too coarse.** Those two subpackages are heterogeneous:
   they contain a handful of genuinely shared utilities PLUS a larger
   population of server-internal classes (authentication providers that
   use `ServerCnxn`, JVM-pause monitors that read `ServerConfig`, quota
   metrics that walk `DataTree`, snapshot serializers that consume
   `ZooKeeperServer`, etc.). By moving the whole subpackage into Support,
   we now see those internal classes as Support→Server violations. This
   is my error, not the author's. Please accept my apology.

2. **Three `server.*` subpackages are still unmapped.** `server.controller..`
   is not in any layer; classes there depend on `server.quorum..`,
   `server.NIOServerCnxn`, etc., and light up the layered rule because
   they fall through to "no layer → Server".

3. **Two more root-package classes act as tooling but were not excluded
   from PublicApi.** `ZooKeeperMain$MyWatcher` (nested class) and the
   stand-alone `JLineZNodeCompleter` are in `org.apache.zookeeper` but
   are functionally CLI plumbing.

4. **Two "shared" utility classes have internal references the shared-list
   missed.** `EphemeralType` (already in the shared-utility list) calls
   `EphemeralTypeEmulate353` (not in the list); `ZooTrace` (in the list)
   references `server.quorum.LearnerHandler` and `server.Request`.

5. **One "shared" class under `server.watch..` was missed.** 
   `server.watch.PathParentIterator` is used by the PublicApi class
   `ZKWatchManager`; it has to be classified as Support, not Server.

**Real violations of ZooKeeper code: zero.** Every reported edge is an
intentional design choice in the ZK repo. The surviving 160 violations
are still artefacts of imprecise package-to-layer mapping.

Recommended verdict for the current file: **FAIL due to mapping errors;
no action required in ZK code.**

---

## Side-by-Side: What Changed

| Source pattern | Round-1 count (180) | Round-2 count (160) | Status |
|----------------|---------------------|---------------------|--------|
| Shared classes directly in `server.*` (ZooKeeperThread, ByteBufferInputStream, ZooTrace, ExitCode, EphemeralType, PathParentIterator) | 67 | ~5 | **Mostly fixed** (2 bleed-throughs for ZooTrace internals; EphemeralType helper unlisted) |
| `server.auth..` / `server.metric..` / `server.util..` sub-packages reached from client/support | 48 | 0 | **Fixed** |
| `audit → server.*` | 30 | 0 | **Fixed** (audit is now in Server) |
| `ReconfigCommand → server.quorum..` | 5 | 0 | **Fixed** via `ignoreDependency` |
| `ZooKeeperMain → cli..` | 19 | 0 | **Fixed** (ZooKeeperMain now in Tools) |
| `ZooKeeperMain → server.ExitCode` cascade | 11 | 0 | **Fixed** |
| **NEW: `server.util..` → server-internal types** | 0 | ~50 | **Introduced by round-1 advice** |
| **NEW: `server.auth..` → server-internal types** | 0 | ~30 | **Introduced by round-1 advice** |
| **NEW: `server.controller..` → server-internal types** | 0 | ~35 | Pre-existing; surfaced now that other noise is gone |
| **NEW: `ZKWatchManager → server.watch.PathParentIterator`** | 0 (was hidden in pattern A/F) | 2 | Shared utility lives in a `server..` subpackage, not the `server` root |
| **NEW: `ZooKeeperMain$MyWatcher` / `JLineZNodeCompleter` leakage** | 0 | ~7 | More root-package tool classes to exclude from PublicApi |
| **NEW: `EphemeralType → EphemeralTypeEmulate353` and `ZooTrace → LearnerHandler/Request`** | 0 | ~6 | Shared-list classes have internal refs the list missed |

The 180 → 160 shift is not a 20-violation reduction; it's a near-complete
rewrite of the violation set. Round 1's edges are resolved; a previously
invisible second wave is now the top failure.

---

## Breakdown by Root Cause

### Cause A — `server.util..` blanket-move to Support was too coarse (≈50 violations)

Relevant classes actually live in `server.util.*`:

| Class | What it depends on (all server-internal) | Nature |
|-------|-------------------------------------------|--------|
| `server.util.JvmPauseMonitor` | `server.ServerConfig`, `server.quorum.QuorumPeerConfig`, `server.ServerMetrics` | **Server-internal** — monitors server JVM |
| `server.util.RequestPathMetricsCollector` | `server.Request.op2String` (16 call sites) | **Server-internal** — aggregates request metrics |
| `server.util.QuotaMetricsUtils` | `server.DataNode`, `server.DataTree` | **Server-internal** — walks the server tree |
| `server.util.SerializeUtils` | `server.DataTree`, `server.TxnLogEntry`, `server.ZooKeeperServer` | **Server-internal** — serialises server state |
| `server.util.LogChopper` | `server.TxnLogEntry`, `server.persistence.FileTxnLog` | **Server-internal** — edits the server transaction log |
| `server.util.MessageTracker` | `server.quorum.Leader.getPacketType` | **Server-internal** — tracks quorum messaging |
| `server.util.ConfigUtils` | `server.quorum.QuorumPeer$QuorumServer` | **Shared-ish** — also called by `cli.GetConfigCommand` |
| `server.util.VerifyingFileFactory` | (only `slf4j.Logger`) | **Genuinely shared** — called by `common.ZKConfig` |

Only **two** of the eight classes in `server.util.*` are actually shared
with non-server layers. The round-1 advice to move the whole subpackage
into Support was wrong.

**Correct fix:** keep `server.util..` in Server and pull out specific
classes via a class-level predicate:

```java
private static final DescribedPredicate<JavaClass> shared_server_util_classes =
        resideInAPackage("org.apache.zookeeper.server.util")
            .and(simpleName("ConfigUtils")
                 .or(simpleName("VerifyingFileFactory"))
                 .or(simpleName("VerifyingFileFactory$Builder")))
            .as("shared configuration / file helpers under server.util");
```

and reference it in the Support definition alongside the existing
`shared_server_utilities` predicate.

### Cause B — `server.auth..` blanket-move to Support was also too coarse (≈30 violations)

| Class | What it depends on | Nature |
|-------|--------------------|--------|
| `server.auth.DigestAuthenticationProvider` | `server.ServerCnxn` | **Server-internal** |
| `server.auth.EnsembleAuthenticationProvider` | `server.ServerCnxn`, `server.ServerMetrics` | **Server-internal** |
| `server.auth.IPAuthenticationProvider` | `server.ServerCnxn` | **Server-internal** |
| `server.auth.KeyAuthenticationProvider` | `server.ZooKeeperServer`, `server.ZKDatabase` | **Server-internal** |
| `server.auth.SASLAuthenticationProvider` | `server.ServerCnxn` | **Server-internal** |
| `server.auth.X509AuthenticationProvider` | `server.ServerCnxn` | **Server-internal** |
| `server.auth.ServerAuthenticationProvider` / `$ServerObjs` | `server.ServerCnxn`, `server.ZooKeeperServer` | **Server-internal** |
| `server.auth.AuthenticationProvider` (interface) | `server.ServerCnxn` | **Server-internal** |
| `server.auth.ProviderRegistry` | `server.ZooKeeperServer` + called by `common.X509Util` | **Shared-ish** |
| `server.auth.KerberosName` | (none internal) | **Genuinely shared** — used by `util.SecurityUtils` |

Again, only **two** classes under `server.auth..` are shared, and one
(`ProviderRegistry`) is borderline: it accepts `ZooKeeperServer` server
types internally but is called by `common.X509Util` from Support. The
rest of the subpackage is pure server-side auth.

**Correct fix:**

```java
private static final DescribedPredicate<JavaClass> shared_server_auth_classes =
        resideInAPackage("org.apache.zookeeper.server.auth")
            .and(simpleName("KerberosName")
                 .or(simpleName("ProviderRegistry")))
            .as("shared authentication helpers under server.auth");
```

and drop `server.auth..` from Support's subpackage list.

### Cause C — `server.controller..` is in no layer (≈35 violations)

The `server.controller..` subpackage was never mapped. These classes:

- `ControllableConnection extends NIOServerCnxn`
- `ControllableConnectionFactory extends NIOServerCnxnFactory`
- `ControllerServerConfig extends QuorumPeerConfig`
- `ControllerService` (holds `ServerCnxnFactory`, `QuorumPeer`, `QuorumPeerConfig`)
- `ZooKeeperServerController` (holds `QuorumPeer`, reaches into `SessionTracker`, `ZooKeeperServer`)

are all **server-test infrastructure** — a test-only controllable server
used by ZK's integration tests. In a strict Maven build with
`ImportOption.DoNotIncludeTests`, these classes would be excluded because
they live under `src/test/java` of `zookeeper-server`. They're being
imported here because either (a) the test classpath is being scanned, or
(b) they live in `src/main/java` for historical reasons and the PDF
architecture simply doesn't address them.

**Two options:**

1. **Treat as Server** (simplest) — add `"org.apache.zookeeper.server.controller.."`
   to `server_internal_classes`. The controller → server-quorum edges
   then stay inside Server and do not violate the layered rule.

2. **Treat as test infrastructure** — explicitly exclude via
   `ImportOption`. Example:

   ```java
   ImportOption DO_NOT_INCLUDE_SERVER_CONTROLLER = loc ->
           !loc.contains("/server/controller/");
   ```

   and register it on `@AnalyzeClasses`.

Option 1 is recommended because the controller classes are clearly
server-side by reference graph and their "in / not in test classpath"
status depends on repo build configuration.

### Cause D — More root-package tool classes (≈7 violations)

Root-package classes currently mis-classified as PublicApi:

- `ZooKeeperMain$MyWatcher` (inner class, 5 edges to outer `ZooKeeperMain`)
- `JLineZNodeCompleter` (1 edge to `ZooKeeperMain.getCommands()`)
- The outer-class constructor edges listed as `has parameter of type <ZooKeeperMain>`
  (2 of them) for MyWatcher's synthetic constructor

`MyWatcher` exists solely as the `Watcher` plumbing for the CLI main
class. `JLineZNodeCompleter` is the JLine-based tab-completer for the CLI
shell. Both are tool classes that happen to live in `org.apache.zookeeper`.

**Correct fix:** broaden the `public_api_classes` exclusion list and the
`tools_classes` inclusion list:

```java
private static final DescribedPredicate<JavaClass> root_tool_classes =
        simpleName("ZooKeeperMain")
            .or(simpleNameStartingWith("ZooKeeperMain$"))
            .or(simpleName("JLineZNodeCompleter"))
            .as("CLI plumbing classes that reside in the root package");

private static final DescribedPredicate<JavaClass> public_api_classes =
        resideInAPackage("org.apache.zookeeper")
            .and(not(root_tool_classes))
            .as("public API contract types in the root package");

private static final DescribedPredicate<JavaClass> tools_classes =
        resideInAnyPackage(
                "org.apache.zookeeper.cli..",
                "org.apache.zookeeper.inspector..",
                "org.apache.zookeeper.graph..")
            .or(resideInAPackage("org.apache.zookeeper").and(root_tool_classes))
            .as("CLI and tooling classes");
```

(Use the ArchUnit helper `HasName.Predicates.nameStartingWith(...)` if
`simpleNameStartingWith` is not available in the installed version; see
the archunit docs for the exact identifier.)

### Cause E — `ZKWatchManager → server.watch.PathParentIterator` (2 violations)

`PathParentIterator` is a generic path-iteration utility used by the
PublicApi class `ZKWatchManager` for persistent watches. It lives in
`server.watch.*` (server subpackage) which we correctly classified as
Server. But the class is genuinely shared.

The round-1 `shared_server_utilities` predicate only peels classes out of
the root `server` package (via `resideInAPackage("org.apache.zookeeper.server")`,
no `..`), so subpackage-residents like `PathParentIterator` are not covered.

**Correct fix:** extend the predicate to accept one deeper level and
whitelist specifically:

```java
private static final DescribedPredicate<JavaClass> shared_server_utilities =
        (resideInAPackage("org.apache.zookeeper.server")
            .and(simpleName("ZooKeeperThread")
                 .or(simpleName("ByteBufferInputStream"))
                 .or(simpleName("ZooTrace"))
                 .or(simpleName("ExitCode"))
                 .or(simpleName("EphemeralType"))
                 .or(simpleName("EphemeralTypeEmulate353")))    // NEW
        ).or(
            resideInAPackage("org.apache.zookeeper.server.watch")
                .and(simpleName("PathParentIterator"))          // NEW
        ).as("shared utilities placed under org.apache.zookeeper.server.*");
```

### Cause F — `EphemeralType → EphemeralTypeEmulate353` and `ZooTrace → quorum.LearnerHandler / server.Request` (≈5 violations)

`EphemeralType` is on the shared list, so it is classified as Support.
But it has a static initializer and a `get(long)` method that delegate
to `EphemeralTypeEmulate353` (also in `server` root, not on the shared
list) — so it falls into Server. Support → Server.

`ZooTrace` is on the shared list. But its `logQuorumPacket` method takes
a `server.quorum.QuorumPacket` and calls `server.quorum.LearnerHandler`;
its `logRequest` method takes a `server.Request`. Support → Server.

These are both Pattern-A bleed-throughs — "shared" classes that are
shared *mostly* but not entirely. Two options:

1. **Declare them as Server and `ignoreDependency` the shared call sites**
   (heavy, fragile).
2. **Live with them via `ignoreDependency(resideInAPackage("...server").and(simpleName("ZooTrace")), ...)`**
   clauses that acknowledge the known overlap.
3. **Add `EphemeralTypeEmulate353` to the shared list** (fixes the
   `EphemeralType` side cleanly); for `ZooTrace`, add narrow
   `ignoreDependency` calls for its two internal edges.

Recommended: option 3. One additional `simpleName("EphemeralTypeEmulate353")`
in the shared list and two `ignoreDependency` calls for the `ZooTrace`
bleed-throughs.

---

## Real violations (ZK code bugs)

**Zero.** Every edge surfaced is intentional ZK design:

- Authentication providers using `ServerCnxn` — yes, that is their job.
- JVM pause monitor reading `ServerConfig` — yes, it monitors the server.
- Quota metrics walking `DataTree` — yes, that is what quotas are.
- Serialization utilities handling `TxnLogEntry` — yes, snapshots are
  server state.
- `server.controller.*` extending real `NIOServerCnxn` — yes, it is a
  controllable drop-in for testing.
- `ZKWatchManager` using `PathParentIterator` — yes, the iterator works
  for both client persistent watches and server watch registries.

Nothing in this file says "the ZooKeeper maintainers should fix their
code." Every fix belongs on the rule side.

---

## Recommended Single-Shot Patch

Revert the overzealous subpackage moves, introduce class-level predicates
for the genuinely shared classes, map `server.controller`, and broaden
the root-tool exclusion:

```java
// Shared classes in server root
private static final DescribedPredicate<JavaClass> shared_server_utilities =
        resideInAPackage("org.apache.zookeeper.server")
            .and(simpleName("ZooKeeperThread")
                 .or(simpleName("ByteBufferInputStream"))
                 .or(simpleName("ZooTrace"))
                 .or(simpleName("ExitCode"))
                 .or(simpleName("EphemeralType"))
                 .or(simpleName("EphemeralTypeEmulate353"))    // NEW
                 .or(simpleName("EphemeralType$1")))           // NEW (static init anon class)
            .as("shared utilities directly in org.apache.zookeeper.server");

// Shared classes that live in server subpackages
private static final DescribedPredicate<JavaClass> shared_server_subpackage_utilities =
        (resideInAPackage("org.apache.zookeeper.server.util")
            .and(simpleName("ConfigUtils")
                 .or(simpleName("VerifyingFileFactory"))
                 .or(simpleName("VerifyingFileFactory$Builder"))))
        .or(resideInAPackage("org.apache.zookeeper.server.auth")
                .and(simpleName("KerberosName")
                     .or(simpleName("ProviderRegistry"))))
        .or(resideInAPackage("org.apache.zookeeper.server.watch")
                .and(simpleName("PathParentIterator")))
        .or(resideInAPackage("org.apache.zookeeper.server.metric"))   // whole subpkg ok
        .as("shared utilities under server.* subpackages");

// Root-package tool classes (not part of public API contract)
private static final DescribedPredicate<JavaClass> root_tool_classes =
        simpleName("ZooKeeperMain")
            .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.ZooKeeperMain$"))
            .or(simpleName("JLineZNodeCompleter"))
            .as("CLI plumbing classes that reside in the root package");

private static final DescribedPredicate<JavaClass> public_api_classes =
        resideInAPackage("org.apache.zookeeper")
            .and(not(root_tool_classes))
            .as("public API contract types in the root package");

private static final DescribedPredicate<JavaClass> tools_classes =
        resideInAnyPackage(
                "org.apache.zookeeper.cli..",
                "org.apache.zookeeper.inspector..",
                "org.apache.zookeeper.graph..")
            .or(resideInAPackage("org.apache.zookeeper").and(root_tool_classes))
            .as("CLI and tooling classes");

// Server internals: everything under server.* that is not on the shared lists
private static final DescribedPredicate<JavaClass> server_internal_classes =
        resideInAnyPackage("org.apache.zookeeper.server..",
                           "org.apache.zookeeper.audit..")
            .and(not(shared_server_utilities))
            .and(not(shared_server_subpackage_utilities))
            .as("server-side implementation classes");
```

And the Support layer definition becomes:

```java
.layer("Support").definedBy(
        resideInAnyPackage(
                "org.apache.zookeeper.common..",
                "org.apache.zookeeper.util..",
                "org.apache.zookeeper.metrics..",
                "org.apache.zookeeper.jmx..",
                "org.apache.zookeeper.compat..",
                "org.apache.zookeeper.compatibility..")
            .or(shared_server_utilities)
            .or(shared_server_subpackage_utilities))
```

Plus two narrow `ignoreDependency` calls for the `ZooTrace → server.*`
bleed-throughs:

```java
.ignoreDependency(simpleName("ZooTrace"),
                  resideInAnyPackage("org.apache.zookeeper.server.quorum..")
                      .or(resideInAPackage("org.apache.zookeeper.server")
                              .and(simpleName("Request"))))
```

---

## Expected Outcome

| Pattern | Before this round | After this patch |
|---------|-------------------|------------------|
| `server.util..` → server-internal | ~50 | 0 (moved back to Server) |
| `server.auth..` → server-internal | ~30 | 0 (moved back to Server) |
| `server.controller..` → server-internal | ~35 | 0 (now in Server) |
| `ZooKeeperMain$MyWatcher` / `JLineZNodeCompleter` leakage | ~7 | 0 (reclassified to Tools) |
| `ZKWatchManager → server.watch.PathParentIterator` | 2 | 0 (PathParentIterator in Support) |
| `EphemeralType → EphemeralTypeEmulate353` | ~3 | 0 (both in Support) |
| `ZooTrace → server.quorum/Request` | ~3 | 0 (ignoreDependency) |
| **Total** | **160** | **0** |

The file should then PASS. If it still produces a handful of remaining
edges after applying this patch, it is almost certainly one more
unmapped `server.*` subpackage — easy to track down by grepping the
failure list for `server\.(\w+)\.`. We are at the tail of the distribution.

---

## Apology & Lesson

The round-1 advice to pull `server.auth..`, `server.metric..`, and
`server.util..` wholesale into Support was based on observed
**client/support → server** edges, without looking at the outbound
edges *from* those subpackages. Those outbound edges (auth providers
reaching into `ServerCnxn`, serialize utils handling `DataTree`) are
what's lighting up in round 2.

The correct approach — visible in the patch above — is to keep
`server.*` subpackages in Server by default and carve out specific
shared classes with class-level predicates. Subpackage-level partitions
are easier to maintain but too coarse for `server.auth` and
`server.util`.

This is exactly the kind of mistake that a PDF review cannot catch and
a real-build review reveals immediately — which is why we ran the test.
The round-1 analysis identified the right six patterns; the round-1
prescription was wrong on the granularity. Both are now visible.
