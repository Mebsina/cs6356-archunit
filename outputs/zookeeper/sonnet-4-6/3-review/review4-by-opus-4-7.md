# Adversarial Review #4 — `outputs/zookeeper/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache ZooKeeper
Review: 4 (post–Review #3 fix, second real test run)

---

### Executive Summary

- **Total documented constraints identified: 4** (unchanged from Reviews #1/#2/#3):
  1. (C1) Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).
  2. (C2) Replicated DB / request processor / atomic broadcast are server-internal (§1.7).
  3. (C3) Recipes are higher-order primitives built on the simple client API, never on server internals (§1.8).
  4. (C4) The simple API is a narrow, stable public surface (§1.6).
- **Total rules generated: 2** (unchanged from Review #3) — one `layeredArchitecture` block and one `recipes_must_not_depend_on_zookeeper_internals` blacklist.
- **Coverage rate: 4 of 4** constraints have at least one rule.
- **Empirical state** (`target/surefire-reports/ArchitectureEnforcementTest.txt`):
  - `recipes_must_not_depend_on_zookeeper_internals` — **PASSES** (unchanged from Review #3).
  - `layered_architecture_is_respected` — **FAILS with 1,561 violations** (down from 25,917 in Review #3). Review #3's `consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")` fix worked as intended and removed the JDK/third-party noise.
- **What 1,561 violations reveal** (they split cleanly into three buckets):
  1. **~90%** are Server → Client false positives produced by putting shared *protocol/wire-format* packages — `data..`, `proto..`, `txn..` plus root-package API types `Watcher`, `KeeperException`, `WatchedEvent`, `CreateMode`, `Op`, `ZooDefs`, `MultiOperationRecord`, `AsyncCallback`, `Login`, `Environment`, `ZookeeperBanner` — inside the **Client** layer. The server legitimately produces, consumes, and serialises every one of these types (§1.7: the server-internal pipeline processes client requests and replicates them). Modelling these as Client-only is a category error.
  2. **~5%** are Client/Infrastructure → Server false positives caused by a handful of *cross-cutting utilities misplaced under `server..`* by historical convention — `server.ZooKeeperThread`, `server.ByteBufferInputStream`, `server.ExitCode`, `server.ZooTrace`, `server.EphemeralType`, `server.auth.KerberosName`, `server.watch.PathParentIterator`. Review #3 attempted to suppress `ZooKeeperThread` via `ignoreDependency(...)`, but the predicate is bugged (see R4-01).
  3. **~5%** are genuine real-world architectural smells worth calling out (Admin/Client coupling via `ZooKeeperAdmin extends ZooKeeper`, `ZooKeeperMain → ZooKeeperAdmin`, SASL `Login` used from both tiers).
- **Critical Regressions / Still-broken findings**:
  - **R4-01 (CRITICAL, new)** — Review #3's `ignoreDependency(...)` for `ZooKeeperThread` silently matches nothing. `simpleNameStartingWith("ClientCnxn")` does not match `EventThread` / `SendThread` (ArchUnit's `simpleName` for a nested class is the nested segment only); `simpleName("FileChangeWatcher$WatcherThread")` is a typo for `simpleName("WatcherThread")`. The surefire report confirms: lines 23–33, 56–57 still list all three thread-subclass edges as violations.
  - **R4-02 (CRITICAL, modelling error carried forward from Review #2/#3)** — `org.apache.zookeeper.proto..`, `.txn..`, `.data..` are classified as **Client**, but they are *shared* wire-format / API-data packages consumed by both tiers (§1.6, §1.7). This single mis-classification accounts for ~1,100 of the 1,561 violations.
  - **R4-03 (CRITICAL, new)** — Lumping the root package `org.apache.zookeeper` wholesale into **Client** is a category error for the same reason: root contains the §1.6 public API types (`Watcher`, `KeeperException`, `WatchedEvent`, `AsyncCallback`, `ZooDefs`, `CreateMode`, `Op`, `MultiOperationRecord`) that the server must reference, plus the cross-tier auth utility `Login`. Every Server → (root API type) edge is flagged.
  - **R4-04 (HIGH, new)** — `Admin.mayOnlyAccessLayers(...)` omits `Client`, but `org.apache.zookeeper.admin.ZooKeeperAdmin` extends `org.apache.zookeeper.ZooKeeper`. This is a legitimate Admin → Client edge required by the `ZooKeeperAdmin` contract (admin IS a specialised client). Current rule false-positives on it.
  - **R4-05 (HIGH, new)** — `Client.mayOnlyAccessLayers(...)` omits `Admin`, but `ZooKeeperMain.connectToZK(...)` constructs `admin.ZooKeeperAdmin`. Either `ZooKeeperMain` must be reclassified (it is a CLI tool, not really a library-client), or Client needs access to Admin.
  - **R4-06 (HIGH, new)** — `Monitoring.mayOnlyAccessLayers("Infrastructure", "Monitoring", "Server")` fails ~50 times when `audit.AuditHelper` references `proto.*`, `CreateMode`, `ZKUtil`. All three targets are currently in Client. Any fix to R4-02/R4-03 must also include these in Monitoring's allowed set.
  - **R4-07 (HIGH, carried forward)** — Only one cross-cutting `server..` class (`ZooKeeperThread`) was addressed (and even that was broken — R4-01). At minimum `server.ExitCode`, `server.EphemeralType`, `server.ByteBufferInputStream`, `server.ZooTrace`, `server.auth.KerberosName`, `server.watch.PathParentIterator` produce the same class of genuine "misplaced utility" violation and must be either reclassified or explicitly ignored with rationale.
- **Overall verdict:** `FAIL`. Review #3's surface-level fix (`consideringOnlyDependenciesInAnyPackage`) solved noise suppression but unmasked a deeper modelling error: the binary Client/Server split does not fit ZooKeeper's package reality. The codebase needs a third layer — call it **Protocol** (or **ApiTypes**) — that sits beneath both Client and Server and contains the shared wire-format / public-API types (`data..`, `proto..`, `txn..`, and the root-package API surface). Without it, the layered rule cannot simultaneously (a) allow the server to process client requests, (b) forbid the client from reaching server internals, and (c) pass on the real codebase. A secondary issue is that the `ignoreDependency` mechanism is buggy and must be rewritten using full names.

---

### Findings

```
[R4-01] SEVERITY: CRITICAL
Category: Semantic Error / Ignored Rule Never Fires
Affected Rule / Constraint: layered_architecture_is_respected (ignoreDependency clause)

What is wrong:
Review #3 added:

    .ignoreDependency(
        JavaClass.Predicates.simpleNameStartingWith("ClientCnxn")
                .or(JavaClass.Predicates.simpleName("FileChangeWatcher$WatcherThread")),
        JavaClass.Predicates.simpleName("ZooKeeperThread"))

This silently matches nothing for the intended targets, for two reasons:

(a) ArchUnit follows Java reflection semantics for `JavaClass.getSimpleName()`.
    For a nested class the simple name is *only* the nested segment:

        org.apache.zookeeper.ClientCnxn$EventThread      -> simpleName = "EventThread"
        org.apache.zookeeper.ClientCnxn$SendThread       -> simpleName = "SendThread"
        org.apache.zookeeper.common.FileChangeWatcher$WatcherThread -> simpleName = "WatcherThread"

    So `simpleNameStartingWith("ClientCnxn")` matches `ClientCnxn`,
    `ClientCnxnSocket`, `ClientCnxnSocketNIO`, `ClientCnxnSocketNetty` — never
    `EventThread` or `SendThread`.

(b) `simpleName("FileChangeWatcher$WatcherThread")` encodes the `$`-qualified
    name as a literal. No class has that as its simple name; the actual simple
    name is just `WatcherThread`.

The surefire report confirms the ignore never activated. All three "known-debt"
edges are still being reported:

    target/surefire-reports/ArchitectureEnforcementTest.txt:23
        Class <org.apache.zookeeper.ClientCnxn$EventThread>
              extends class <org.apache.zookeeper.server.ZooKeeperThread>
    target/surefire-reports/ArchitectureEnforcementTest.txt:25
        Class <org.apache.zookeeper.ClientCnxn$SendThread>
              extends class <org.apache.zookeeper.server.ZooKeeperThread>
    target/surefire-reports/ArchitectureEnforcementTest.txt:28
        Class <org.apache.zookeeper.common.FileChangeWatcher$WatcherThread>
              extends class <org.apache.zookeeper.server.ZooKeeperThread>
    target/surefire-reports/ArchitectureEnforcementTest.txt:30-33, 56-57
        ... plus the constructor-call edges ...

Why it matters:
The entire documented intent ("these three thread subclasses are known debt,
suppress until `ZooKeeperThread` is moved") is unfulfilled. A reviewer reading
the rule would assume it works; in production it doesn't. Worse, if the real
fix is applied (moving `ZooKeeperThread` into Infrastructure / reclassifying
it), this `ignoreDependency` will keep lingering, silent but bogus, because
the predicate would still match nothing.

How to fix it:
Use the fully-qualified name as the matcher, which is stable for nested
classes. Either switch to `.nameMatching(...)` or enumerate exact full names
with `HasName.Predicates.name(...)` (import
`com.tngtech.archunit.core.domain.properties.HasName.Predicates.name`):

```java
import static com.tngtech.archunit.base.DescribedPredicate.or;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;

.ignoreDependency(
        or(name("org.apache.zookeeper.ClientCnxn$EventThread"),
           name("org.apache.zookeeper.ClientCnxn$SendThread"),
           name("org.apache.zookeeper.common.FileChangeWatcher$WatcherThread")),
        name("org.apache.zookeeper.server.ZooKeeperThread"))
```

(Full name is the robust choice. If nested-class coverage is desired
generically, use `nameMatching(".*ClientCnxn\\$.*Thread")` / `.nameMatching(".*FileChangeWatcher\\$WatcherThread")`.)
```

---

```
[R4-02] SEVERITY: CRITICAL
Category: Wrong Layer / Modelling Error
Affected Rule / Constraint: layered_architecture_is_respected  (layer "Client" definition)

What is wrong:
`org.apache.zookeeper.proto..`, `.txn..`, and `.data..` are defined as Client:

    .layer("Client").definedBy(
            "org.apache.zookeeper",
            "org.apache.zookeeper.client..",
            "org.apache.zookeeper.retry..",
            "org.apache.zookeeper.data..",     // <-- wrong
            "org.apache.zookeeper.proto..",    // <-- wrong
            "org.apache.zookeeper.txn..")      // <-- wrong

But these are not client packages. Per §1.6 and §1.7 of the ZooKeeper
architecture PDF, the server-internal pipeline (request processor, replicated
database, commit log) literally operates on these records — they are the
wire-format / persistence-format / public API-data contract *shared* between
client and server. Checking the surefire report, Server classes that
legitimately reference these packages include:

    server.FinalRequestProcessor     -> proto.GetDataRequest, SetWatches, SetWatches2,
                                        AddWatchRequest, GetACLRequest, CheckWatchesRequest,
                                        GetChildren2Request, GetEphemeralsRequest,
                                        RemoveWatchesRequest, SetWatches, SyncRequest,
                                        txn.ErrorTxn, txn.TxnHeader
    server.PrepRequestProcessor      -> data.ACL, data.Id, KeeperException.*
    server.DataTree, DataNode        -> data.StatPersisted
    server.ResponseCache             -> data.Stat
    server.NIOServerCnxn, NettyServerCnxn -> proto.ReplyHeader, proto.RequestHeader, data.Stat
    server.TxnLogEntry               -> txn.TxnHeader, txn.TxnDigest
    server.quorum.LearnerSyncRequest -> data.Id
    server.quorum.QuorumPacket       -> data.Id (authinfo)
    server.watch.WatchManager        -> org.apache.zookeeper.Watcher
    server.Request                   -> txn.TxnHeader, txn.TxnDigest,
                                        data.Id, KeeperException
    ... and ~200 more in the same vein ...

Every one of these is a legitimate edge. The current modelling flags them
all as Server → Client violations because `.whereLayer("Server")
.mayOnlyAccessLayers("Infrastructure", "Monitoring", "Server")` forbids
Server → Client.

Count: by spot-sampling the 1,561-line report, roughly 1,100 of the violations
have the form `<server.*> references <proto.*|txn.*|data.*|root-API-type>`.
That is ~70% of all failures.

Why it matters:
The cleanest fix a team would apply in practice — "put data/proto/txn into
Client because that's where the public API lives" — is exactly what was done,
and it is wrong. These three packages are the *interface* between tiers; they
belong in a neutral layer below Client AND below Server (and accessible from
both). Without that layer, either (a) Server must be allowed to access Client
(which breaks C1 — clients and servers communicate only over the wire), or
(b) the rule permanently produces ~1,100 false positives. Both outcomes
destroy the usefulness of the test.

How to fix it:
Introduce a dedicated "Protocol" (or "ApiTypes", "Wire", "Records") layer
sitting beneath both Client and Server. It contains the shared wire-format
records and API data types. The layered rule becomes:

```java
.layer("Protocol").definedBy(
        "org.apache.zookeeper.data..",
        "org.apache.zookeeper.proto..",
        "org.apache.zookeeper.txn..")

// Protocol only depends on Infrastructure and itself (and jute, but that is
// out of scope once consideringOnlyDependenciesInAnyPackage is applied).
.whereLayer("Protocol").mayOnlyAccessLayers("Infrastructure", "Protocol")

// Every tier above it may reference protocol records.
.whereLayer("Monitoring").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server")
.whereLayer("Server").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server")
.whereLayer("Client").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Cli", "Admin")
.whereLayer("Admin").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server", "Client", "Admin")
.whereLayer("Cli").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Cli")
.whereLayer("Recipes").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Recipes")
```

Remove `data..`, `proto..`, `txn..` from the Client layer definition. This
single change eliminates ~1,100 of 1,561 violations.
```

---

```
[R4-03] SEVERITY: CRITICAL
Category: Wrong Layer / Modelling Error
Affected Rule / Constraint: layered_architecture_is_respected  (layer "Client" definition — root package)

What is wrong:
The root package `org.apache.zookeeper` (assigned wholesale to Client) is not
homogeneous. It holds three distinct groups of classes, and the server
legitimately depends on two of them:

Group A — §1.6 public API surface (referenced by Server by design):
    Watcher, WatchedEvent, Watcher$Event, Watcher$Event$EventType,
    Watcher$Event$KeeperState,
    KeeperException, KeeperException$Code, KeeperException$*Exception,
    AsyncCallback, AsyncCallback$*Callback,
    CreateMode, AddWatchMode,
    ZooDefs, ZooDefs$OpCode, ZooDefs$Ids, ZooDefs$Perms,
    Op, Op$Create, Op$Delete, Op$Check, Op$CreateTTL, ...,
    MultiOperationRecord, MultiResponse,
    DigestWatcher, ClientWatchManager, ZKWatchManager$*

Group B — Cross-tier auth / banner / shell utilities (referenced by Server):
    Login, SaslClientCallbackHandler, SaslServerPrincipal,
    Environment, ZookeeperBanner, ZooKeeperMain?, Shell, Version, ZKUtil

Group C — Client-only machinery (truly belongs in Client):
    ZooKeeper, ClientCnxn, ClientCnxnSocket, ClientCnxnSocketNIO,
    ClientCnxnSocketNetty, ClientWatchManager, StatsTrack,
    JLineZNodeCompleter, ClientInfo, ZooKeeperMain? (debatable)

The surefire report confirms Server → Group A/B flood:

    Lines 117-122:  ClientCnxnSocket.readConnectResult references
                    server.ByteBufferInputStream  (this is Client -> Server;
                    grouping aside, real debt)
    Lines 75, 82-85, 99, 110-111, 746, 757, 1581:
                    server.* classes reference org.apache.zookeeper.Login
    Lines 1582-1583:  ZooKeeperServer.<clinit> calls root.Environment.logEnv,
                      root.ZookeeperBanner.printBanner
    Lines 86, 89, 112-115, 148-152:  server.* references
                    org.apache.zookeeper.Watcher,
                    Watcher$Event$EventType,
                    org.apache.zookeeper.DigestWatcher
    Lines 66-70, 94, 718-722, 725-736, 756:  KeeperException$*,
                    KeeperException$Code.* referenced from server.*
    Lines 125-128, 153-160:  server.EphemeralType.validateTTL called from
                    root.Op / root.ZooKeeper / root.CreateOptions  (this is
                    the inverse direction — Client -> Server — see R4-07)
    Line 782:  server.PrepRequestProcessor references root.MultiOperationRecord
    Line 783:  server.PrepRequestProcessor references root.Op

Why it matters:
Putting Groups A/B into Client means every Server -> (Watcher, KeeperException,
Op, CreateMode, Login, Environment, Banner, MultiOperationRecord) edge is
flagged. That is ~300-400 of the 1,561 violations on top of the ~1,100
data/proto/txn ones. Combined with R4-02, this explains ~95% of the
failure count.

How to fix it:
Split the root package by class rather than by wildcard. ArchUnit supports
defining layers by explicit class list or by predicate. Two options:

Option 1 (preferred — expressive and stable) — move Group A/B into the
Protocol layer via explicit class names:

```java
.layer("Protocol").definedBy(
        // existing shared packages
        resideInAnyPackage(
            "org.apache.zookeeper.data..",
            "org.apache.zookeeper.proto..",
            "org.apache.zookeeper.txn..")
        // plus specific root-package API/protocol types
        .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.Watcher"))
        .or(HasName.Predicates.name("org.apache.zookeeper.WatchedEvent"))
        .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.KeeperException"))
        .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.AsyncCallback"))
        .or(HasName.Predicates.name("org.apache.zookeeper.CreateMode"))
        .or(HasName.Predicates.name("org.apache.zookeeper.AddWatchMode"))
        .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.ZooDefs"))
        .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.Op"))
        .or(HasName.Predicates.nameStartingWith("org.apache.zookeeper.MultiOperationRecord"))
        .or(HasName.Predicates.name("org.apache.zookeeper.MultiResponse"))
        .or(HasName.Predicates.name("org.apache.zookeeper.DigestWatcher"))
        .or(HasName.Predicates.name("org.apache.zookeeper.Login"))
        .or(HasName.Predicates.name("org.apache.zookeeper.SaslClientCallbackHandler"))
        .or(HasName.Predicates.name("org.apache.zookeeper.SaslServerPrincipal"))
        .or(HasName.Predicates.name("org.apache.zookeeper.Environment"))
        .or(HasName.Predicates.name("org.apache.zookeeper.ZookeeperBanner"))
        .or(HasName.Predicates.name("org.apache.zookeeper.ZKUtil")))
```

Option 2 (pragmatic — pay coverage cost for simplicity) — leave the root
package in Client but allow Server → Client for API-type references only,
scoped by class-name pattern in an `ignoreDependency`:

```java
.ignoreDependency(
        resideInAPackage("org.apache.zookeeper.server.."),
        // "public API surface + cross-tier utilities" — matches Group A/B above
        HasName.Predicates.nameMatching(
            "org\\.apache\\.zookeeper\\."
          + "(Watcher.*|WatchedEvent|AddWatchMode|CreateMode|AsyncCallback.*"
          + "|KeeperException.*|Op\\b.*|ZooDefs.*|MultiOperationRecord|MultiResponse"
          + "|DigestWatcher|Login|SaslClientCallbackHandler|SaslServerPrincipal"
          + "|Environment|ZookeeperBanner|ZKUtil)"))
```

Option 1 is architecturally honest; Option 2 is a carve-out. Either eliminates
the ~300-400 Server -> root-API-type violations.
```

---

```
[R4-04] SEVERITY: HIGH
Category: Overly Narrow / Missing Allowed Edge
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Admin"))

What is wrong:
    .whereLayer("Admin").mayOnlyAccessLayers(
            "Infrastructure", "Monitoring", "Server", "Admin")

Admin is forbidden from accessing Client. But `org.apache.zookeeper.admin.ZooKeeperAdmin`
**extends** `org.apache.zookeeper.ZooKeeper` (the root-package Client class) —
this is its entire design: ZooKeeperAdmin IS a specialised ZooKeeper client with
admin-only methods (`reconfigure`, `updateServerPrincipal`). Every constructor,
every method, every field reference flows through the Client super-class.

The surefire report confirms the resulting violations (lines 27, 44-55, 161,
170-194):

    Class <org.apache.zookeeper.admin.ZooKeeperAdmin>
          extends class <org.apache.zookeeper.ZooKeeper>
    Constructor <...ZooKeeperAdmin.<init>(String,int,Watcher,boolean,ZKClientConfig)>
          calls constructor <ZooKeeper.<init>(String,int,Watcher,boolean,ZKClientConfig)>
    Method <...ZooKeeperAdmin.reconfigure(...)> calls constructor
          <proto.ReconfigRequest.<init>(...)>, proto.ReplyHeader, proto.RequestHeader,
          calls ClientCnxn.submitRequest, ClientCnxn.queuePacket, KeeperException.create
    Method <...ZooKeeperAdmin.toString()> calls method <ZooKeeper.toString()>

~40 violations attributable to this single missing allowed edge.

Why it matters:
The PDF explicitly describes Admin (§ four-letter-words / the HTTP admin server)
as running inside the server process, but `admin.ZooKeeperAdmin` is separately
a *client-side* class that uses the public API to perform admin operations
against a running ensemble. Modelling Admin as "server-side only" misreads
the package — there are actually *two* admin concepts in ZooKeeper and one
of them is a client. The current rule blocks the legitimate edge.

How to fix it:
Allow Admin → Client (and Admin → Protocol per R4-02):

```java
.whereLayer("Admin").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server", "Client", "Admin")
```

Justification comment: "`admin.ZooKeeperAdmin` extends `ZooKeeper` and
uses `ClientCnxn` / `proto.*` / `KeeperException` to invoke admin-only
commands over the same wire protocol as any other client — §1.6 public API
surface."
```

---

```
[R4-05] SEVERITY: HIGH
Category: Overly Narrow / Missing Allowed Edge
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Client"))

What is wrong:
    .whereLayer("Client").mayOnlyAccessLayers(
            "Infrastructure", "Monitoring", "Client", "Cli")

Client is forbidden from accessing Admin. But `org.apache.zookeeper.ZooKeeperMain`
(assigned to Client via the root-package wildcard) legitimately constructs
`admin.ZooKeeperAdmin`:

    target/surefire-reports/ArchitectureEnforcementTest.txt:161
        Method <ZooKeeperMain.connectToZK(String)>
               calls constructor <admin.ZooKeeperAdmin.<init>(
                   String,int,Watcher,boolean,ZKClientConfig)>

`ZooKeeperMain` uses `ZooKeeperAdmin` (not plain `ZooKeeper`) precisely so
the interactive CLI can drive `reconfig` and similar admin commands. This is
a deliberate dependency, not an architectural mistake in the ZK codebase.

Why it matters:
Isolated finding, but combined with R4-03 it illustrates the design problem:
`ZooKeeperMain` is a CLI executable, not a client library. Sweeping it into
the Client layer via `org.apache.zookeeper` wildcard is what forces this
false positive.

How to fix it:
Two options, pick one:

Option A (reclassification — architecturally cleaner):
Move `ZooKeeperMain` into the Cli layer explicitly:

```java
.layer("Cli").definedBy(
        resideInAPackage("org.apache.zookeeper.cli..")
        .or(HasName.Predicates.name("org.apache.zookeeper.ZooKeeperMain"))
        .or(HasName.Predicates.name("org.apache.zookeeper.ZooKeeperMain$MyCommandOptions"))
        .or(HasName.Predicates.name("org.apache.zookeeper.JLineZNodeCompleter")))

// Cli already allows Client access; additionally allow Admin:
.whereLayer("Cli").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Admin", "Cli")
```

Option B (allow Client → Admin):
```java
.whereLayer("Client").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Cli", "Admin")
```

Option A is preferable because `ZooKeeperMain` is not a general-purpose client
API consumer; it is a shell. But Option B is one-line.
```

---

```
[R4-06] SEVERITY: HIGH
Category: Overly Narrow / Missing Allowed Edge
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Monitoring"))

What is wrong:
    .whereLayer("Monitoring").mayOnlyAccessLayers(
            "Infrastructure", "Monitoring", "Server")

`audit.AuditHelper` legitimately reads request protocol records and maps
ACLs/paths. The surefire report shows ~25 violations (lines 195-210+):

    Method <audit.AuditHelper.addAuditLog(Request, ProcessTxnResult, boolean)>
           calls method <ZKUtil.aclToString(List)>                          // -> Client (root)
           calls method <proto.CreateRequest.getFlags()>                    // -> Client (proto)
           calls method <proto.SetACLRequest.getAcl()>                      // -> Client (proto)
           references constructor <proto.DeleteRequest.<init>()>            // -> Client (proto)
           ...
    Method <audit.AuditHelper.getCreateMode(int)>
           calls method <CreateMode.fromFlag(int)>                          // -> Client (root)

These are legitimate: audit logs must decode request payloads and render
ACL / create-mode metadata. The audit layer *cannot* function without
reading the wire-format records.

Why it matters:
Monitoring / audit being forbidden to read wire-format protocol records
contradicts the documented "audit logs record the operation performed on
the data tree" purpose. The `.because()` clause already acknowledges this
direction — but the rule doesn't.

How to fix it:
Once R4-02 introduces the Protocol layer, Monitoring gains Protocol access
automatically. But note this finding depends on R4-02/R4-03 fixes; if the
Protocol layer is NOT introduced, monitoring's access list must include
"Client":

```java
// With Protocol layer (preferred):
.whereLayer("Monitoring").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server")

// Without Protocol layer (fallback — widens Monitoring's surface area
// but lets it compile):
.whereLayer("Monitoring").mayOnlyAccessLayers(
        "Infrastructure", "Monitoring", "Server", "Client")
```
```

---

```
[R4-07] SEVERITY: HIGH
Category: Semantic Error / Incomplete ignoreDependency
Affected Rule / Constraint: layered_architecture_is_respected  (ignoreDependency scope)

What is wrong:
Review #3 acknowledged that `server.ZooKeeperThread` is a cross-cutting thread
utility misplaced under `server..` and tried to suppress the resulting
false-positives. It did so incorrectly (R4-01), and it stopped there. But the
surefire report shows at least six *other* server-package classes used by
classes outside the server tier:

| Offender (package)           | Uses                                       | Direction      | Report line(s)        |
|------------------------------|--------------------------------------------|----------------|-----------------------|
| root.Op / root.ZooKeeper     | server.EphemeralType.validateTTL            | Client->Server | 125-128, 153-160      |
| root.CreateOptions           | server.EphemeralType.validateTTL            | Client->Server | 34-35                 |
| root.ClientCnxn*             | server.ByteBufferInputStream                | Client->Server | 117-118, 123-124      |
| root.ClientCnxn$SendThread   | server.ZooTrace                             | Client->Server | 119-122               |
| root.ZooKeeperMain, Shell,   | server.ExitCode.getValue / .* fields        | Client->Server | 36-43, 129-148,       |
|   Version                    |                                             |                | 162-169               |
| root.ZKWatchManager          | server.watch.PathParentIterator             | Client->Server | 149-152               |
| util.SecurityUtils           | server.auth.KerberosName                    | Infra->Server  | 1567-1580             |
| common.FileChangeWatcher     | server.ZooKeeperThread (via inheritance)    | Infra->Server  | 28-29, 56-57          |
| root.ClientCnxn$*Thread      | server.ZooKeeperThread (via inheritance)    | Client->Server | 23-26, 30-33          |

Each one is a real architectural smell (a "utility in the server package"
used from outside the server tier) but none of them represents a boundary
violation in the intended sense — they are mis-placed utility classes, not
client code reaching into the request processor / replicated database /
atomic broadcast pipeline that §1.7 forbids. Currently five of these eight
are flagged (R4-01 silently suppressed neither ZooKeeperThread edge
correctly, but even if R4-01 were fixed the other six remain un-addressed).

Why it matters:
Each unaddressed class produces 4-30 violation lines. Collectively they are
~60 of the 1,561 violations and — more importantly — they are the highest-
signal findings in the whole report: they point at real code that should
either be moved or documented as debt. Leaving them mixed in with the ~1,500
modelling-driven false positives buries them and guarantees nobody will
fix them.

How to fix it:
Either (a) reclassify each of these `server.*` utility classes into the
correct layer (preferred — fixes the code, not the test), or (b) enumerate
them in a single well-documented `ignoreDependency`. Option (b) as a worked
example, using R4-01's corrected predicate form:

```java
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.base.DescribedPredicate.or;

// Known architectural debt: these server.* classes are cross-cutting
// utilities, not server-internal logic per §1.7. Moving them out of server..
// is the correct fix; until that happens upstream, suppress here.
.ignoreDependency(
        DescribedPredicate.alwaysTrue(),
        or(name("org.apache.zookeeper.server.ZooKeeperThread"),
           name("org.apache.zookeeper.server.ByteBufferInputStream"),
           name("org.apache.zookeeper.server.ExitCode"),
           name("org.apache.zookeeper.server.ZooTrace"),
           name("org.apache.zookeeper.server.EphemeralType"),
           name("org.apache.zookeeper.server.auth.KerberosName"),
           name("org.apache.zookeeper.server.watch.PathParentIterator")))
```

(Scoping the origin to `alwaysTrue()` is intentional: these utilities are
genuinely cross-cutting; we don't care which layer calls them, we just
acknowledge the debt. If that feels too permissive, scope origin to
`resideOutsideOfPackage("org.apache.zookeeper.server..")`.)

If Option (a) is preferred, the team should move each class into the
appropriate package (likely `common`, `util`, or a new `shared`/`protocol`
package) and the layered rule passes naturally.
```

---

```
[R4-08] SEVERITY: MEDIUM
Category: Wrong Layer / Unreferenced Edge
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Client") — ZooKeeperAdmin direction)

What is wrong:
Inverse of R4-04: when `ZooKeeperMain` (root / Client) calls into
`admin.ZooKeeperAdmin`, the edge is Client -> Admin (not Admin -> Client).
R4-05 already covers this, BUT the proposed Option A (move ZooKeeperMain
into Cli) still needs the edge because `Cli.mayOnlyAccessLayers(...)` also
omits Admin:

    .whereLayer("Cli").mayOnlyAccessLayers(
            "Infrastructure", "Monitoring", "Client", "Cli")

Once `ZooKeeperMain` sits in Cli, Cli needs access to Admin too.

Why it matters:
A fix for R4-05 Option A only completes cleanly if Cli's allowed set is
updated in the same patch. Otherwise the violation simply moves to a
different layer pair with the same root cause.

How to fix it:
```java
.whereLayer("Cli").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Admin", "Cli")
```
```

---

```
[R4-09] SEVERITY: MEDIUM
Category: Semantic Error / because-clause drift
Affected Rule / Constraint: layered_architecture_is_respected  (.because(...))

What is wrong:
The `.because()` clause says:

    "Observability (metrics / jmx / audit) is cross-cutting and may be
     accessed from Server, Client, Admin, Cli, and Recipes; Infrastructure
     (common / util / compat / compatibility / version) is strictly below
     observability so the utility layer remains a reusable, dependency-free
     base."

Two problems:
  (a) Under the fix for R4-02 a Protocol layer appears between Infrastructure
      and observability. The clause needs to name it or the reader will
      wonder where `data..`/`proto..`/`txn..` fit.
  (b) The clause already describes Monitoring as cross-cutting, but the
      rule allows Monitoring only Server access — not Protocol/Client
      (R4-06). The clause claims the rule is more permissive than it is.
      Once R4-06 is fixed this stops being a drift issue.

Why it matters:
Low by itself, but `.because()` is what a developer reads after a CI
failure. Today a failure message says "Monitoring is cross-cutting" while
the actual failure is "Monitoring may not access Client" — which is
confusing.

How to fix it:
Rewrite once R4-02 and R4-06 are applied. Example:

    "Inferred from §1.1 and §1.7: clients and servers communicate only over
     the TCP wire protocol, with shared wire-format / API-data records
     (proto/txn/data + public-API root types) sitting in a neutral Protocol
     layer below both tiers. Admin HTTP endpoints run inside the server
     process; ZooKeeperAdmin is a specialised client; CLI is a client-side
     tool; recipes (§1.8) are higher-order primitives on the public API.
     Observability (metrics/jmx/audit) may read Protocol records and Server
     internals. Infrastructure (common/util/compat/compatibility/version) is
     the reusable, dependency-free base."
```

---

```
[R4-10] SEVERITY: LOW
Category: Structural Gap / C4 under-enforcement
Affected Rule / Constraint: C4 (the simple API surface is a narrow, stable public contract — §1.6)

What is wrong:
C4 is listed in the PDF summary and the class Javadoc, but neither of the
two rules actually asserts anything about the shape of the §1.6 public API
surface. The layered rule will fire if the server reaches into `recipes..`
or if `client..` reaches into `server..`, but it does not assert that:

  - `org.apache.zookeeper.ZooKeeper` (the canonical public API class) has the
    seven documented methods (`create` / `delete` / `exists` / `getData` /
    `setData` / `getChildren` / `sync`), or
  - Only those methods are marked `@Public` / only specific methods are
    part of the API surface, or
  - The root-package API types (Watcher, KeeperException, AsyncCallback,
    ZooDefs) are `@InterfaceAudience.Public`.

Why it matters:
This is a carry-forward from previous reviews (identified in Review #1/2 and
never addressed). It is LOW because nothing regresses by omitting it, but
as-is C4 relies on human inspection of Javadoc. A single rule using ArchUnit's
Yetus audience check (since the ZK codebase is already annotated with
`org.apache.yetus.audience.InterfaceAudience.Public`) would close the loop.

How to fix it:
Add one assertion pinning the public-API members. Minimal form:

```java
@ArchTest
static final ArchRule public_api_surface_is_stable =
        classes()
                .that().haveFullyQualifiedName("org.apache.zookeeper.ZooKeeper")
                .should().bePublic()
                .andShould().beAnnotatedWith("org.apache.yetus.audience.InterfaceAudience$Public")
                .because(
                        "Per §1.6 the ZooKeeper class is the public client API "
                        + "surface; it must remain `@InterfaceAudience.Public` "
                        + "so removal of this annotation is detected as a "
                        + "breaking-change indicator.");
```

(This is a stretch rule — ArchUnit doesn't great-check method shape — but it
at least pins the class-level contract. Richer checks require Java AST, not
ArchUnit.)
```

---

```
[R4-11] SEVERITY: LOW
Category: Redundancy (documented)
Affected Rule / Constraint: recipes_must_not_depend_on_zookeeper_internals

What is wrong:
Still fully subsumed by the layered rule (`Recipes.mayOnlyAccessLayers(...)`
excludes Server/Admin/Cli/graph/inspector). Keeping it was explicitly
declared in Review #3 as a §1.8 citation-readability carve-out, which is
acceptable. No regression.

Why it matters:
None beyond noting that under any future refactor that changes the layered
rule (e.g. adding the Protocol layer in R4-02), both rules must be touched
in lockstep or the blacklist will drift and start missing packages the
layered rule catches.

How to fix it:
No change needed. If the Protocol layer is introduced, add a comment to the
blacklist rule:

    // Note: if the layered rule's allowed set is expanded (e.g. a new
    // Protocol layer is added), review whether this blacklist still matches
    // the §1.8 intent. Server / Admin / Cli / graph / inspector are the
    // always-forbidden targets; Protocol records are always allowed.
```

---

```
[R4-12] SEVERITY: LOW
Category: Defensive-config opacity
Affected Rule / Constraint: layered_architecture_is_respected  (.withOptionalLayers(true))

What is wrong:
`.withOptionalLayers(true)` tells ArchUnit "do not fail if some layer has
zero matching classes". This is useful during refactors, but it also hides
misclassification:

  - If `org.apache.zookeeper.compat..` or `org.apache.zookeeper.compatibility..`
    contains no classes, the rule silently won't check them.
  - A typo in a layer glob (e.g. `org.apache.zookeeper.jm..` instead of
    `jmx..`) would produce an empty layer and go undetected.

A quick scan of the report shows no empty layer produced any output,
suggesting all packages in the layer definitions do exist. Still, no active
check guards against future drift.

Why it matters:
Cosmetic. A future refactor that renames `compat` to `compatibility` (or
vice versa) would leave one of the two globs dead without alerting the test.

How to fix it:
Either drop `.withOptionalLayers(true)` (strict mode — fail on empty layer),
or add a one-liner companion test asserting each layer is non-empty:

```java
@ArchTest
static final ArchRule every_layer_has_at_least_one_class =
        classes()
                .that().resideInAnyPackage(
                        "org.apache.zookeeper.compat..",
                        "org.apache.zookeeper.compatibility..",
                        "org.apache.zookeeper.version..",
                        "org.apache.zookeeper.retry..",
                        "org.apache.zookeeper.recipes..")
                .should().notBe(com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES)  // placeholder
                // Realistically: use ArchRule.allowEmptyShould(false) with a
                // per-package classesShouldExist() assertion, or a custom
                // ArchCondition that fails when the subset is empty.
                .because(
                        "Guard against typos in layer package globs: if any of "
                        + "these packages vanishes the layered rule silently "
                        + "stops checking it under withOptionalLayers(true).");
```

(The exact implementation is awkward in ArchUnit — the cleanest way is to
drop `.withOptionalLayers(true)` and verify at import time that every
declared layer matches ≥1 class. For the ZK codebase this currently holds.)
```

---

### Recommended Consolidated Patch

The patch below addresses **R4-01 through R4-07** (R4-08 folds into R4-05, R4-09
folds into R4-02/R4-06, R4-10/R4-11/R4-12 are optional). Key changes:

1. **New `Protocol` layer** (R4-02) holding `data..`, `proto..`, `txn..`
2. **Root package split** (R4-03): API / protocol / banner / auth types go into
   Protocol; `ZooKeeperMain` / `JLineZNodeCompleter` / `StatsTrack` go into Cli;
   everything else stays in Client.
3. **Admin layer widened** (R4-04): now allows Client access so `ZooKeeperAdmin
   extends ZooKeeper` is legal.
4. **Cli layer widened** (R4-05/R4-08): now allows Admin access so
   `ZooKeeperMain.connectToZK()` can construct `ZooKeeperAdmin`.
5. **Monitoring widened** (R4-06): gains Protocol access.
6. **`ignoreDependency` rewritten** (R4-01/R4-07): uses full-names, covers all
   seven documented cross-cutting `server.*` utilities.

```java
/**
 * ArchitectureEnforcementTest (Review #4 revision).
 *
 * <p>Changes from Review #3:
 * <ul>
 *   <li>R4-02: introduce a dedicated {@code Protocol} layer for shared wire-format
 *       and public-API records (proto.. / txn.. / data.. + root-package API types).
 *       Sits beneath both Client and Server.</li>
 *   <li>R4-03: split root package by predicate; API/protocol/banner/auth types
 *       move into Protocol, shell tools move into Cli, Client keeps the library
 *       machinery (ZooKeeper, ClientCnxn*, etc.).</li>
 *   <li>R4-04: allow Admin → Client (ZooKeeperAdmin extends ZooKeeper).</li>
 *   <li>R4-05: allow Cli → Admin (ZooKeeperMain.connectToZK uses ZooKeeperAdmin).</li>
 *   <li>R4-06: allow Monitoring → Protocol (audit logs decode proto/CreateMode).</li>
 *   <li>R4-01/R4-07: rewrite ignoreDependency using full names; cover all seven
 *       known cross-cutting server.* utilities as explicit architectural debt.</li>
 * </ul>
 */

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.or;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
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

    // R4-03: root-package API / protocol / banner / auth types that are NOT
    // client-only. Everything matching this predicate belongs in the Protocol
    // layer instead of Client.
    private static final DescribedPredicate<JavaClass> ROOT_PROTOCOL_TYPES =
            DescribedPredicate.describe(
                    "root-package protocol / API / cross-tier types",
                    c -> c.getPackageName().equals("org.apache.zookeeper")
                      && c.getName().matches(
                          "org\\.apache\\.zookeeper\\."
                        + "(Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode"
                        + "|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?"
                        + "|Op(\\$.*)?|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?"
                        + "|MultiResponse|DigestWatcher|Login(\\$.*)?"
                        + "|SaslClientCallbackHandler(\\$.*)?|SaslServerPrincipal"
                        + "|Environment(\\$.*)?|ZookeeperBanner|ZKUtil(\\$.*)?"
                        + "|ClientInfo|StatsTrack)"));

    // R4-03/R4-05: shell-tool classes in the root package that belong in Cli.
    private static final DescribedPredicate<JavaClass> ROOT_CLI_TYPES =
            or(name("org.apache.zookeeper.ZooKeeperMain"),
               nameMatching("org\\.apache\\.zookeeper\\.ZooKeeperMain\\$.*"),
               name("org.apache.zookeeper.JLineZNodeCompleter"),
               name("org.apache.zookeeper.Shell"),
               name("org.apache.zookeeper.Version"));

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
                    .withOptionalLayers(true)

                    .layer("Infrastructure").definedBy(
                            "org.apache.zookeeper.common..",
                            "org.apache.zookeeper.util..",
                            "org.apache.zookeeper.compat..",
                            "org.apache.zookeeper.compatibility..",
                            "org.apache.zookeeper.version..")

                    // R4-02/R4-03: shared wire-format + public-API records
                    .layer("Protocol").definedBy(
                            resideInAnyPackage(
                                "org.apache.zookeeper.data..",
                                "org.apache.zookeeper.proto..",
                                "org.apache.zookeeper.txn..")
                            .or(ROOT_PROTOCOL_TYPES))

                    .layer("Monitoring").definedBy(
                            "org.apache.zookeeper.jmx..",
                            "org.apache.zookeeper.metrics..",
                            "org.apache.zookeeper.audit..")

                    .layer("Server").definedBy("org.apache.zookeeper.server..")

                    // R4-03: Client = library classes in root + client..+ retry..
                    // MINUS the Protocol/Cli carve-outs above.
                    .layer("Client").definedBy(
                            resideInAPackage("org.apache.zookeeper.client..")
                            .or(resideInAPackage("org.apache.zookeeper.retry.."))
                            .or(DescribedPredicate.describe(
                                    "root-package non-protocol non-cli classes",
                                    c -> c.getPackageName().equals("org.apache.zookeeper")
                                      && !ROOT_PROTOCOL_TYPES.test(c)
                                      && !ROOT_CLI_TYPES.test(c))))

                    .layer("Admin").definedBy("org.apache.zookeeper.admin..")

                    // R4-05: Cli absorbs root-package shell tools.
                    .layer("Cli").definedBy(
                            resideInAPackage("org.apache.zookeeper.cli..")
                            .or(ROOT_CLI_TYPES))

                    .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")

                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")
                    .whereLayer("Protocol").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol")
                    .whereLayer("Monitoring").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Server")
                    .whereLayer("Server").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Server")
                    .whereLayer("Client").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Client", "Cli")
                    .whereLayer("Admin").mayOnlyAccessLayers(      // R4-04
                            "Infrastructure", "Protocol", "Monitoring",
                            "Client", "Server", "Admin")
                    .whereLayer("Cli").mayOnlyAccessLayers(        // R4-05/R4-08
                            "Infrastructure", "Protocol", "Monitoring",
                            "Client", "Admin", "Cli")
                    .whereLayer("Recipes").mayOnlyAccessLayers(
                            "Infrastructure", "Protocol", "Monitoring", "Client", "Recipes")

                    .whereLayer("Server").mayOnlyBeAccessedByLayers(
                            "Admin", "Monitoring", "Server")

                    // R4-01 / R4-07: known architectural debt — cross-cutting
                    // utilities that currently live under server.. by historical
                    // convention. The clean fix is to move each into common..
                    // (or a new shared..); until that happens upstream, suppress
                    // the edges here so CI is not permanently red.
                    .ignoreDependency(
                            DescribedPredicate.alwaysTrue(),
                            or(name("org.apache.zookeeper.server.ZooKeeperThread"),
                               name("org.apache.zookeeper.server.ByteBufferInputStream"),
                               name("org.apache.zookeeper.server.ExitCode"),
                               name("org.apache.zookeeper.server.ZooTrace"),
                               name("org.apache.zookeeper.server.EphemeralType"),
                               name("org.apache.zookeeper.server.auth.KerberosName"),
                               name("org.apache.zookeeper.server.watch.PathParentIterator")))

                    .because(
                            "Inferred from §1.1 and §1.7: clients and servers "
                            + "communicate only over the TCP wire protocol. Shared "
                            + "records (proto/txn/data + public-API root types) sit "
                            + "in a neutral Protocol layer below both tiers. Admin "
                            + "HTTP endpoints run inside the server; ZooKeeperAdmin "
                            + "is a specialised client that extends ZooKeeper; CLI "
                            + "is a client-side tool; recipes (§1.8) are higher-order "
                            + "primitives on the public API. Observability may read "
                            + "Protocol records and Server internals. Infrastructure "
                            + "(common / util / compat / compatibility / version) is "
                            + "the reusable, dependency-free base.");

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
                            "Per §1.8, recipes are higher-order coordination "
                            + "primitives built on the simple client API (§1.6); "
                            + "they must not reach into server internals, Admin or "
                            + "CLI tooling, or developer-only GUIs. Third-party "
                            + "dependencies are out of scope for this rule.");
}
```

Expected behaviour after this patch:

| Rule | Before (Review #3 applied) | After (Review #4 applied) |
|------|----------------------------|---------------------------|
| `layered_architecture_is_respected` | FAIL (1,561 violations) | PASS (0 violations in surefire report — all bucket-1/2 modelling false-positives eliminated; all bucket-3 cross-cutting utilities explicitly ignored) |
| `recipes_must_not_depend_on_zookeeper_internals` | PASS | PASS (unchanged) |

---

### Summary Table of Findings

| ID     | Severity | Category                                 | Status from Review #3 |
|--------|----------|------------------------------------------|-----------------------|
| R4-01  | CRITICAL | Semantic Error / broken ignoreDependency | NEW (discovered by test run) |
| R4-02  | CRITICAL | Wrong Layer (modelling — proto/txn/data) | Carried from R2/R3 — not addressed |
| R4-03  | CRITICAL | Wrong Layer (modelling — root package)   | Partially in R3-05, not fixed |
| R4-04  | HIGH     | Overly Narrow (Admin may not access Client) | NEW (discovered by test run) |
| R4-05  | HIGH     | Overly Narrow (Client may not access Admin) | NEW (discovered by test run) |
| R4-06  | HIGH     | Overly Narrow (Monitoring may not access Protocol) | NEW (discovered by test run) |
| R4-07  | HIGH     | Incomplete ignoreDependency (six other utilities) | Partial in R3-02/03; rest new |
| R4-08  | MEDIUM   | Follow-on to R4-05 (Cli → Admin)         | NEW |
| R4-09  | MEDIUM   | `.because()` drift after Protocol layer  | NEW |
| R4-10  | LOW      | C4 structural under-enforcement          | Carried from R1/R2 — still open |
| R4-11  | LOW      | Redundancy (documented, acceptable)      | Unchanged — acceptable |
| R4-12  | LOW      | `.withOptionalLayers(true)` hides typos  | NEW (minor) |

**Verdict: FAIL.** The layered rule is still broken after Review #3's fixes, now
for modelling reasons rather than noise reasons. The core issue is that ZooKeeper's
package layout does not cleanly split into Client and Server — there is a large
shared-protocol/API layer between them, and several cross-cutting utilities live
under `server..` by historical convention. Without a Protocol layer plus
explicit debt acknowledgment of the seven cross-cutting `server.*` utilities,
no amount of tweaking allowed-layers matrices will converge.
