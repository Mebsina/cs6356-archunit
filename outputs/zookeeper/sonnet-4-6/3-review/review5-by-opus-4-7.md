# Adversarial Review #5 — `outputs/zookeeper/sonnet-4-6/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: sonnet-4-6
Project: Apache ZooKeeper
Review: 5 (post–Review #4 fix, third real test run)

---

### Executive Summary

- **Total documented constraints identified: 4** (unchanged):
  1. (C1) Clients talk to servers only over the TCP wire protocol (§1.1, §1.7).
  2. (C2) Replicated DB / request processor / atomic broadcast are server-internal (§1.7).
  3. (C3) Recipes are higher-order primitives built on the simple client API, never on server internals (§1.8).
  4. (C4) The simple API is a narrow, stable public surface (§1.6).
- **Total rules generated: 2** (unchanged).
- **Coverage rate: 4 / 4** documented constraints have a rule. C4 still only implicitly enforced by layer placement (carry-forward from Review #1).
- **Empirical state** (`target/surefire-reports/ArchitectureEnforcementTest.txt`):
  - `recipes_must_not_depend_on_zookeeper_internals` — **PASSES** (unchanged since Review #3).
  - `layered_architecture_is_respected` — **FAILS with 161 violations**, down from 1,561 in Review #4. Review #4's Protocol-layer refactor eliminated ~90% of the remaining noise.
- **The 161 remaining violations split into six clean buckets** (rough counts from the surefire report):

| Bucket                                                             | Count (~)   | Root cause                                                                                                 |
|--------------------------------------------------------------------|-------------|------------------------------------------------------------------------------------------------------------|
| Missing public-API types in `ROOT_PROTOCOL_TYPES` regex            | 55          | `OpResult*`, `CreateOptions*`, `Quotas`, `DeleteContainerRequest`, `SaslServerPrincipal$Wrapper*` not matched |
| `Shell` and `Version` wrongly classified as Cli                    | 15          | Both are version/process utilities, not shell tools; called by Server and Protocol                          |
| Enum synthetic `ENUM$VALUES` / `values()` fields on array types    | 13          | `ROOT_PROTOCOL_TYPES` regex does not match JVM array names `[Lorg.apache.zookeeper...;`                     |
| `ZKUtil` wrongly classified as Protocol                            | 13          | `ZKUtil` takes `ZooKeeper` as a parameter — it is a Client-tier utility, not a wire-format record           |
| Infrastructure → Protocol edges forbidden                          | 36          | `Infrastructure.mayOnlyAccessLayers("Infrastructure")` is too strict; `compat..`, `util..`, `common..` legitimately use Protocol types |
| Real cross-cutting server-util debt (R4-07 pattern, more instances) | 14          | `server.auth.ProviderRegistry`, `server.util.VerifyingFileFactory` used by `common..`; not in ignoreDependency |
| Real Cli → Server smells (honest findings)                         | 4           | `cli.GetConfigCommand`, `cli.ReconfigCommand` reach into `server.util.ConfigUtils`, `server.quorum.*`        |
| Client → Admin forbidden (legitimate public-API edge)              | 2           | `client.ZooKeeperBuilder.buildAdmin()` constructs `admin.ZooKeeperAdmin`                                    |
| **Total**                                                          | **~152 of 161** | (remaining ~9 are tail overlap/long-tail)                                                              |

- **Critical Regressions**:
  - **R5-01 (CRITICAL, new)** — `ROOT_PROTOCOL_TYPES` regex misses several public-API types that are clearly Protocol-layer: `OpResult` and its seven nested result types, `CreateOptions` and its `Builder`, `Quotas` (used by `server.DataTree`), `DeleteContainerRequest` (used by `server.ContainerManager`). Every one produces a Server → Client or Protocol → Client violation because the missing classes fall through to Client.
  - **R5-02 (HIGH, new)** — `Shell` and `Version` were added to `ROOT_CLI_TYPES` but they are not CLI tools. `Shell` is a process-exec wrapper (cf. Hadoop's `org.apache.hadoop.util.Shell`) called by `Login$1.run()`; `Version` is a version-info provider called by four separate server classes (`ZooKeeperServer.dumpMonitorValues`, `ZooKeeperServerBean.getVersion`, `server.admin.Commands$SrvrCommand`, `server.command.StatCommand`). Classifying both as Cli produces Server → Cli and Protocol → Cli false positives.
  - **R5-03 (HIGH, new)** — `ROOT_PROTOCOL_TYPES` uses `c.getPackageName().equals("org.apache.zookeeper")` as its first filter. ArchUnit's `JavaClass` for a compiler-synthesised array type (e.g. `[Lorg.apache.zookeeper.Watcher$WatcherType;`) does satisfy that package check but fails the `c.getName().matches(...)` regex because the JVM array encoding starts with `[L`. Every enum in the Protocol layer therefore produces 2-3 violations for its synthetic `ENUM$VALUES` field and `values()` method.
  - **R5-04 (HIGH, new)** — `Infrastructure.mayOnlyAccessLayers("Infrastructure")` is too strict. Three different Infrastructure packages need Protocol access: `compat..` (wire-format translation), `util..` (SASL), and `common..` (ZK config). The largest chunk is `compat.ProtocolManager` which legitimately has 34 edges to `proto.ConnectRequest` / `proto.ConnectResponse`.
  - **R5-05 (HIGH, new)** — `Client.mayOnlyAccessLayers(...)` still omits Admin, but `client.ZooKeeperBuilder.buildAdmin()` (a builder method explicitly designed to construct `ZooKeeperAdmin`) is forbidden. Review #4 moved `ZooKeeperMain` to Cli to remove one cause of this edge, but `ZooKeeperBuilder` remains a legitimate Client → Admin caller and is fully in `client..` — there is no reclassification escape.
- **Overall verdict:** `FAIL WITH WARNINGS` (close to `PASS`). The core architecture is now correct and the remaining 161 violations are all attributable to a small, well-bounded set of classification / predicate-scope defects rather than fundamental modelling problems. A single consolidated patch (below) eliminates ~140 of the remaining 161 and converts the remaining ~20 into honest, documented architectural-debt signals.

---

### Findings

```
[R5-01] SEVERITY: CRITICAL
Category: Coverage Gap / Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected  (ROOT_PROTOCOL_TYPES predicate)

What is wrong:
The regex inside ROOT_PROTOCOL_TYPES omits at least four families of public-API
types that are structurally Protocol (they appear in the §1.6/§1.7 wire contract
or are referenced by server-internal request processing):

  (a) `OpResult` and its seven nested result classes:
          OpResult, OpResult$CheckResult, OpResult$CreateResult,
          OpResult$DeleteResult, OpResult$ErrorResult,
          OpResult$GetChildrenResult, OpResult$GetDataResult,
          OpResult$SetDataResult

      These are the response-side counterparts to `Op.*` subclasses (which ARE
      matched). The server constructs them in `FinalRequestProcessor.processRequest`
      (surefire lines 166-174) and the Protocol class `MultiResponse` returns a
      `List<OpResult>` (surefire lines 25, 30, 36, 46-69).

  (b) `CreateOptions` and `CreateOptions$Builder`:
      Referenced by `Op.create(String, byte[], CreateOptions, int)` (surefire
      71-78) and by `MultiOperationRecord.deserialize` (surefire 44-45).
      `CreateOptions` is an API builder, same category as `Op` / `MultiOperationRecord`.

  (c) `Quotas` (root-package quota-path utility):
      Called by `server.DataTree.createNode/deleteNode/updateQuotaForPath/
      updateQuotaStat`, `server.ZooKeeperServer.checkQuota`, and
      `server.util.QuotaMetricsUtils.collectQuotaLimitOrUsage` (surefire
      161-165, 177-178, 183). A 7-edge cross-layer violation.

  (d) `DeleteContainerRequest` (root-package jute record):
      Called by `server.ContainerManager.checkContainers` and
      `server.PrepRequestProcessor.pRequest2Txn`/`pRequestHelper` (surefire
      160, 175-176). This is a jute-generated record in the root package
      (historically placed there rather than in `proto..`) so the `proto..`
      wildcard misses it.

Additionally, `SaslServerPrincipal(\\$.*)?` is **not** in the current regex:
the regex has `SaslServerPrincipal` without the `(\\$.*)?` suffix, so the
nested classes `SaslServerPrincipal$WrapperInetAddress` and `WrapperInetSocketAddress`
fall through to Client and produce Protocol → Client violations (surefire
79-84, 88). See also R5-06 for the related pattern.

Count: roughly 55 of the 161 violations (34%) trace to these five missing
patterns.

Why it matters:
Each missing class is a wire-format / public-API type that the server
legitimately processes; classifying them as Client blocks the §1.6 / §1.7
edges that the architecture doc explicitly permits. The failure message is
also misleading — "Server may not access Client" when in truth Server is
accessing its own protocol record.

How to fix it:
Extend the regex. Also add an explicit `SaslServerPrincipal(\\$.*)?` and
merge the `DeleteContainerRequest` / `Quotas` into the pattern so all
public-API types travel together:

```java
private static final DescribedPredicate<JavaClass> ROOT_PROTOCOL_TYPES =
        DescribedPredicate.describe(
                "root-package protocol / public-API / cross-tier types",
                c -> rootPackageName(c).equals("org.apache.zookeeper")
                  && rootName(c).matches(
                        "org\\.apache\\.zookeeper\\."
                      + "(Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode"
                      + "|CreateOptions(\\$.*)?"                           // NEW (R5-01b)
                      + "|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?"
                      + "|Op(\\$.*)?|OpResult(\\$.*)?"                     // NEW (R5-01a)
                      + "|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?"
                      + "|MultiResponse|DeleteContainerRequest"           // NEW (R5-01d)
                      + "|Quotas|DigestWatcher|ClientInfo|StatsTrack"     // NEW (R5-01c)
                      + "|Login(\\$.*)?|ClientWatchManager"
                      + "|SaslClientCallbackHandler(\\$.*)?"
                      + "|SaslServerPrincipal(\\$.*)?"                    // NEW (R5-01e — close the nested-class gap)
                      + "|Environment(\\$.*)?|ZookeeperBanner)"));
```

(`rootPackageName(c)` / `rootName(c)` are helpers for R5-03 below that handle
array unwrapping; if R5-03 is fixed by other means the `c.getPackageName()`
/ `c.getName()` calls can stay.)

Eliminates ~55 violations.
```

---

```
[R5-02] SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected  (ROOT_CLI_TYPES)

What is wrong:
`ROOT_CLI_TYPES` includes both `Shell` and `Version`, but neither is a CLI
tool:

  `org.apache.zookeeper.Shell`  — a generic process-exec helper modelled after
      Hadoop's `org.apache.hadoop.util.Shell`. It is called internally by
      `Login$1.run()` during SASL credential refresh (surefire 42). `Login$1`
      is a Protocol class (matched by the `Login(\$.*)?` regex).
      Edge: Protocol → Cli. Forbidden by rule.

  `org.apache.zookeeper.Version` — a version-metadata class exposing
      `getFullVersion()`, `getVersion()`, `getBuildDate()`. It does have a
      `main()` method that prints the version, but its primary consumers are:
        - `server.ZooKeeperServer.dumpMonitorValues` (surefire 179)
        - `server.ZooKeeperServerBean.getVersion` (surefire 180)
        - `server.admin.Commands$SrvrCommand.runGet` (surefire 181)
        - `server.command.StatCommand.commandRun` (surefire 182)
        - `Environment.list()` (Protocol; surefire 38)
      All of these need a version string, not a CLI tool.
      Edges: Server → Cli, Protocol → Cli. All forbidden by rule.

Count: roughly 15 violations.

Why it matters:
These are genuine false positives — `Shell` and `Version` are not shell tools,
they are utilities. Leaving them in Cli blocks edges the PDF architecture
explicitly permits (servers and clients share version metadata).

How to fix it:
Move both classes into Protocol (simpler) or Infrastructure (more accurate
for `Shell`, which is a pure process-exec helper). Either works; Protocol is
the smaller change.

Option A — move both to Protocol:

```java
// Remove from ROOT_CLI_TYPES:
private static final DescribedPredicate<JavaClass> ROOT_CLI_TYPES =
        or(name("org.apache.zookeeper.ZooKeeperMain"),
           nameMatching("org\\.apache\\.zookeeper\\.ZooKeeperMain\\$.*"),
           name("org.apache.zookeeper.JLineZNodeCompleter"));
           // DELETED: name("org.apache.zookeeper.Shell"),
           // DELETED: name("org.apache.zookeeper.Version"));

// Extend ROOT_PROTOCOL_TYPES regex:
"|Shell(\\$.*)?|Version(\\$.*)?"
```

Option B — move `Shell` to Infrastructure (`common..` is where other process
utilities live; this is the architecturally correct home). Requires either a
code change (moving the class) or a second explicit allowlist predicate for
Infrastructure.

Eliminates ~15 violations (Option A is enough on its own).
```

---

```
[R5-03] SEVERITY: HIGH
Category: Semantic Error / Predicate does not cover array types
Affected Rule / Constraint: layered_architecture_is_respected  (ROOT_PROTOCOL_TYPES predicate filter)

What is wrong:
The ROOT_PROTOCOL_TYPES lambda:

    c.getPackageName().equals("org.apache.zookeeper")
 && c.getName().matches("org\\.apache\\.zookeeper\\.(...)")

does not handle JVM array-type encoding. Every Java `enum` has compiler-
generated members:

    private static final E[] ENUM$VALUES;
    public static E[] values();

where the array type's `JavaClass.getName()` is `[Lorg.apache.zookeeper.<Enum>;`.
That string does not match the regex (it starts with `[L`), so the array
class is not in any layer, and the dependency edge is flagged.

Concrete failures from the report:
    Field <AddWatchMode.ENUM$VALUES>         type <[Lorg.apache.zookeeper.AddWatchMode;>
    Field <CreateMode.ENUM$VALUES>           type <[Lorg.apache.zookeeper.CreateMode;>
    Field <KeeperException$Code.ENUM$VALUES> type <[Lorg.apache.zookeeper.KeeperException$Code;>
    Field <Op$OpKind.ENUM$VALUES>            type <[Lorg.apache.zookeeper.Op$OpKind;>
    Field <Watcher$Event$EventType.ENUM$VALUES> etc.
    Field <Watcher$Event$KeeperState.ENUM$VALUES>
    Field <Watcher$WatcherType.ENUM$VALUES>
    Method <...AddWatchMode.values()>, ...CreateMode.values(), ... (seven more)

Count: ~13 violations across the seven Protocol-layer enums.

Why it matters:
Pure synthetic-compiler noise. Every new public enum added to the API will
trigger two more violations. The rule text points at a code location that
nobody wrote by hand.

How to fix it:
Unwrap array types in the predicate using ArchUnit's
`JavaClass.getBaseComponentType()`. Helper methods produce the "real" class
for matching purposes:

```java
private static String rootPackageName(JavaClass c) {
    return c.isArray() ? c.getBaseComponentType().getPackageName() : c.getPackageName();
}
private static String rootName(JavaClass c) {
    return c.isArray() ? c.getBaseComponentType().getName() : c.getName();
}
```

Use them in both ROOT_PROTOCOL_TYPES and the Client-layer residual predicate.
Eliminates all ~13 enum-synthetic violations.

(ArchUnit >=0.23 also exposes `.dontAnalyzeClassesThat(...)` / `.dependency
.onlyFromTheseClasses(...)` which could be used instead; the array-unwrap
approach is more surgical and keeps the Protocol membership semantics stable.)
```

---

```
[R5-04] SEVERITY: HIGH
Category: Overly Narrow
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Infrastructure"))

What is wrong:
    .whereLayer("Infrastructure").mayOnlyAccessLayers("Infrastructure")

Infrastructure is declared to depend on nothing but itself. Three of its
member packages have hard, unavoidable Protocol dependencies:

  (a) `compat.ProtocolManager` is the wire-compat translator. It reads and
      writes `proto.ConnectRequest` / `proto.ConnectResponse` jute records
      (surefire lines 126-159 — **34 violations**). Without Protocol access
      the compat layer cannot exist.

  (b) `util.SecurityUtils.createSaslClient` constructs
      `SaslClientCallbackHandler` (surefire 184-185). Under R5-01 remediation
      `SaslClientCallbackHandler` remains Protocol; this edge needs
      Infrastructure → Protocol.

  (c) `common.ZKConfig.addConfiguration` calls into
      `server.util.VerifyingFileFactory` (Server) and
      `common.X509Util.resetDefaultSSLContextAndOptions` calls into
      `server.auth.ProviderRegistry`. These two are R5-07 (cross-cutting
      `server..` utility debt), not Infrastructure → Protocol, but they
      appear in the same cluster in the surefire output.

Count: ~36 violations are attributable directly to Infrastructure needing
Protocol access (mostly the ProtocolManager chunk).

Why it matters:
The ".because()" clause states Infrastructure "is the reusable, dependency-
free base" — but no reading of the PDF requires Infrastructure to be
completely dependency-free; it requires it to be BELOW the tiers above.
Protocol is conceptually below Infrastructure in the sense of "records are
pure data with no behaviour", so Infrastructure depending on Protocol records
is fine architecturally, just a lattice-direction choice.

How to fix it:
Widen Infrastructure's allowed set to Protocol:

```java
.whereLayer("Infrastructure").mayOnlyAccessLayers(
        "Infrastructure", "Protocol")
```

Simultaneously relax Protocol's outbound list so Protocol → Infrastructure
is allowed (already is — Protocol.mayOnlyAccessLayers("Infrastructure", "Protocol")
is set). Adjust the `.because()` clause to match (R5-11 below).

Eliminates ~36 violations.
```

---

```
[R5-05] SEVERITY: HIGH
Category: Overly Narrow / Missing Allowed Edge
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Client"))

What is wrong:
    .whereLayer("Client").mayOnlyAccessLayers(
            "Infrastructure", "Protocol", "Monitoring", "Client", "Cli")

`client.ZooKeeperBuilder` has a method `buildAdmin()` that explicitly
constructs `admin.ZooKeeperAdmin`:

    surefire line 112-113:
      Method <client.ZooKeeperBuilder.buildAdmin()>
             calls constructor <admin.ZooKeeperAdmin.<init>(ZooKeeperOptions)>
      Method <client.ZooKeeperBuilder.buildAdmin()>
             has return type <admin.ZooKeeperAdmin>

This is a deliberate builder pattern — "use this library to build either a
plain ZooKeeper or a ZooKeeperAdmin". It's client-layer code by any reading
(`client..` is a client package, `ZooKeeperBuilder` is a library class). The
edge is legitimate.

Review #4 addressed the analogous `ZooKeeperMain.connectToZK()` edge by
moving `ZooKeeperMain` into the Cli layer. That escape doesn't work for
`ZooKeeperBuilder`: the class sits squarely in `org.apache.zookeeper.client..`
and cannot be reclassified.

Count: 2 violations.

Why it matters:
`ZooKeeperBuilder` is the canonical construction API for ZK clients in modern
versions. Blocking this edge means the builder pattern cannot be used
without triggering CI — a direct contradiction of §1.6 (the public client
API surface).

How to fix it:
Add Admin to Client's allowed list. Admin already has Client in its allowed
list (R4-04), so this closes the bidirectional edge in a principled way:

```java
.whereLayer("Client").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring",
        "Client", "Cli", "Admin")   // R5-05
```

Eliminates 2 violations.
```

---

```
[R5-06] SEVERITY: HIGH
Category: Wrong Layer (subsumed into R5-01 but called out for clarity)
Affected Rule / Constraint: layered_architecture_is_respected  (ROOT_PROTOCOL_TYPES)

What is wrong:
The regex lists `SaslServerPrincipal` as a Protocol type but *without* the
`(\\$.*)?` suffix that its siblings have. Its two nested classes —
`SaslServerPrincipal$WrapperInetAddress` and
`SaslServerPrincipal$WrapperInetSocketAddress` — are unmatched and fall
through to Client. The outer class (Protocol) then references them and
produces Protocol → Client violations.

    surefire 79-89:
      SaslServerPrincipal (Protocol) -> SaslServerPrincipal$WrapperInetSocketAddress (Client)
      SaslServerPrincipal (Protocol) -> client.ZKClientConfig (Client)

Count: ~8 violations. (Folded into R5-01's "55".)

Why it matters:
Half of these are legitimate `client.ZKClientConfig` references (which are
rightly in the Client layer and the principal IS using them). The other half
are Protocol → WrapperInet* which vanish once the pattern is `(\\$.*)?`.

How to fix it:
The regex change is already in R5-01's patch (`SaslServerPrincipal(\\$.*)?`).
For the Protocol → `client.ZKClientConfig` edges — those fall into R5-10:
`SaslServerPrincipal` is arguably an auth utility, not a Protocol record,
and should be in Infrastructure. But moving individual classes is a cleanup
detail, not a correctness one. If Infrastructure → Protocol is allowed per
R5-04 the reverse direction (Protocol → Infrastructure) is trivially also
allowed, so the current placement works.
```

---

```
[R5-07] SEVERITY: HIGH
Category: Cross-cutting utility in wrong package (R4-07 pattern, more instances)
Affected Rule / Constraint: layered_architecture_is_respected  (ignoreDependency)

What is wrong:
Review #4's ignoreDependency lists seven cross-cutting server.* utilities.
The surefire report shows at least two more that should have been included:

  (a) `server.util.VerifyingFileFactory` (and `$Builder`) — a file-validation
      utility called by `common.ZKConfig.addConfiguration` (Infrastructure).
      **10 violations** (surefire 116-125). The same "utility misplaced under
      server.." pattern as R4-07.

  (b) `server.auth.ProviderRegistry` — an SSL provider registry called by
      `common.X509Util.resetDefaultSSLContextAndOptions` (Infrastructure).
      **2 violations** (surefire 114-115).

Both fit the R4-07 definition cleanly: they are utility classes that happen
to live under `server..` by historical convention but are used by code
outside the server tier. The ignoreDependency list exists precisely for this
pattern.

Count: 12 violations.

Why it matters:
Without adding these, the two Infrastructure → Server edges remain even
after R5-04 widens Infrastructure. They're not broken by R5-04 — they're in
a different layer pair.

How to fix it:
Add to the existing ignoreDependency block:

```java
.ignoreDependency(
        DescribedPredicate.alwaysTrue(),
        or(name("org.apache.zookeeper.server.ZooKeeperThread"),
           name("org.apache.zookeeper.server.ByteBufferInputStream"),
           name("org.apache.zookeeper.server.ExitCode"),
           name("org.apache.zookeeper.server.ZooTrace"),
           name("org.apache.zookeeper.server.EphemeralType"),
           name("org.apache.zookeeper.server.auth.KerberosName"),
           name("org.apache.zookeeper.server.auth.ProviderRegistry"),          // NEW (R5-07)
           name("org.apache.zookeeper.server.watch.PathParentIterator"),
           nameMatching("org\\.apache\\.zookeeper\\.server\\.util\\.VerifyingFileFactory(\\$.*)?"))) // NEW (R5-07)
```

(Use `nameMatching` with `(\\$.*)?` for VerifyingFileFactory because the
inner `Builder` is a separate JavaClass and needs the same suppression.)

Eliminates 12 violations.
```

---

```
[R5-08] SEVERITY: MEDIUM
Category: Wrong Layer / Real architectural smell
Affected Rule / Constraint: layered_architecture_is_respected  (whereLayer("Cli"))

What is wrong:
Two `cli.*` subcommands reach into server-internal utilities:

    surefire 106-111:
      cli.GetConfigCommand.exec()     -> server.util.ConfigUtils.getClientConfigStr
      cli.ReconfigCommand.parse(...)  -> server.quorum.QuorumPeerConfig.parseDynamicConfig
      cli.ReconfigCommand.parse(...)  -> server.quorum.flexible.QuorumVerifier.toString

These are real: the reconfig command needs to parse the same dynamic-
quorum config format that the server uses, so it calls into
`server.quorum.QuorumPeerConfig`.

Count: 4 violations.

Why it matters:
This is the pattern the PDF explicitly forbids — CLI (which connects over
the client wire protocol) reaching into server-internal quorum logic.
§1.7 is blunt about this: quorum / replicated-DB / atomic-broadcast are
server-internal. The current rule correctly flags it.

How to fix it:
This is a genuine architectural smell in the ZK codebase, not a rule bug.
Three options:

  1. **Reclassify the server-side helpers** — `server.util.ConfigUtils` and
     `server.quorum.QuorumPeerConfig#parseDynamicConfig` could arguably
     live in Protocol or a new `config..` package. This is a code change
     upstream, not a test change.

  2. **Add a documented ignoreDependency** — same R4-07 pattern:

```java
.ignoreDependency(
        resideInAPackage("org.apache.zookeeper.cli.."),
        or(name("org.apache.zookeeper.server.util.ConfigUtils"),
           name("org.apache.zookeeper.server.quorum.QuorumPeerConfig"),
           name("org.apache.zookeeper.server.quorum.flexible.QuorumVerifier")))
```
     with a comment explaining this is intentional shared-config-parsing
     debt, to be removed when the config helpers are relocated.

  3. **Leave as-is** as the signal that catches the real architectural smell.
     Four violations is a manageable CI alert and points maintainers at
     the genuine coupling.

Recommend Option 3 in the short term (leave the finding visible) and Option
1 in the long term (split config helpers out of `server..`). Option 2 is
acceptable if CI green is required for merge.
```

---

```
[R5-09] SEVERITY: MEDIUM
Category: Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected  (ROOT_PROTOCOL_TYPES overbroad for ZKUtil)

What is wrong:
`ZKUtil` is in the ROOT_PROTOCOL_TYPES regex, placing it in Protocol. But
the class is a **client-side utility** — every public method takes a
`ZooKeeper` as its first argument:

    surefire 93-105:
      ZKUtil.deleteInBatch(ZooKeeper, List, int)
      ZKUtil.deleteRecursive(ZooKeeper, String)
      ZKUtil.deleteRecursive(ZooKeeper, String, int)
      ZKUtil.deleteRecursive(ZooKeeper, String, AsyncCallback$VoidCallback, Object)
      ZKUtil.listSubTreeBFS(ZooKeeper, String)
      ZKUtil.visitSubTreeDFS(ZooKeeper, String, boolean, AsyncCallback$StringCallback)
      ZKUtil.visitSubTreeDFSHelper(ZooKeeper, ...)

`ZKUtil` is a convenience wrapper around `ZooKeeper`. It belongs in Client,
not Protocol. Putting it in Protocol and then forbidding Protocol → Client
produces 13 Protocol → Client violations (every method is flagged because
of its ZooKeeper parameter).

Count: 13 violations.

Why it matters:
The Protocol layer was introduced specifically to hold pure wire-format /
API-data records (R4-02). Including a convenience class that operates on a
client object contaminates the Protocol membership rule and produces
violations for a class that was correctly Client-layer until Review #4
misclassified it.

How to fix it:
Remove `ZKUtil` from ROOT_PROTOCOL_TYPES. It will fall through to the
Client residual predicate (`root-package non-protocol non-cli classes`),
which is exactly where it belongs:

```java
// In ROOT_PROTOCOL_TYPES regex, DELETE:
    // |ZKUtil(\\$.*)?           <-- remove this alternative
```

Same rationale applies to `ClientWatchManager` — it's a client-side watch
manager, not a wire-format record. Audit the list. Classes that legitimately
stay in Protocol are those that (a) have no behaviour other than serialising,
or (b) are referenced equally by Client and Server as API types.

Eliminates 13 violations.
```

---

```
[R5-10] SEVERITY: MEDIUM
Category: Semantic Error (fragile regex-based classification)
Affected Rule / Constraint: layered_architecture_is_respected  (ROOT_PROTOCOL_TYPES strategy)

What is wrong:
The root-package is classified by **allowlist regex**. Any class added to
the root package that is not explicitly named becomes Client by default.
This has three failure modes:

  (a) False Negatives — a genuinely Protocol-layer class (e.g. a new
      `RetryLogic` exception) added in the root package will silently
      become Client and produce Server → Client violations on first use.

  (b) False Positives — a genuinely Client-only class (e.g. a new
      `ClientCnxnV3`) will silently become Client ✓, but if its name
      accidentally matches the regex (e.g. `ClientInfo(\\$.*)?` matches
      `ClientInfoSink`) it becomes Protocol.

  (c) Maintenance burden — the regex is opaque to non-author readers and
      bad-tasting to diff. Review #5 itself adds five alternations to it.

Count: no direct violations, but every new root-package class is a latent
defect.

Why it matters:
Low by itself, medium cumulatively: each review cycle has added a few
names to the regex. The approach does not scale.

How to fix it:
Two better strategies, either preferred over regex:

  1. **Client allowlist, Protocol residual**: explicitly name the ~15
     Client-only root classes (`ZooKeeper`, `ClientCnxn`, `ClientCnxnSocket`,
     `ClientCnxnSocketNIO`, `ClientCnxnSocketNetty`, `ClientInfo`,
     `JaasConfiguration`, `ZKWatchManager`, `SaslClientConfiguration`,
     `AllClientsConfiguration`, `CreateOptions`, ...). Everything else in
     root defaults to Protocol.

  2. **Annotation-driven**: use the Apache Yetus
     `@InterfaceAudience.Public` / `@InterfaceAudience.Private` /
     `@InterfaceAudience.LimitedPrivate` annotations already on ZK classes:

         classes().that().resideInAPackage("org.apache.zookeeper")
                  .and().areAnnotatedWith("org.apache.yetus.audience.InterfaceAudience$Public")
                  .should().bePublic();

     Or use audience-based layer classification if ArchUnit supports it via
     custom predicates. This gives the rule semantic grounding instead of a
     hand-edited regex.

Either refactor is outside Review #5's minimal patch; recommend as a
follow-up tech-debt task.
```

---

```
[R5-11] SEVERITY: LOW
Category: `.because()` drift
Affected Rule / Constraint: layered_architecture_is_respected

What is wrong:
The `.because()` clause states Infrastructure "is the reusable,
dependency-free base." Under R5-04 this becomes false — Infrastructure will
be allowed to depend on Protocol. Without updating the clause, readers
encountering an Infrastructure-rule failure message will see factually
wrong justification text.

Why it matters:
Low — cosmetic, but the `.because()` clause is the maintainer-facing
documentation. Stale text accumulates noise.

How to fix it:
Update the clause in the same patch that widens Infrastructure:

    "Infrastructure (common / util / compat / compatibility / version) sits
     below observability and consumes only Protocol records (proto/txn/data
     + public-API root types) plus itself — keeping the utility base
     reusable and free of server/client coupling."
```

---

```
[R5-12] SEVERITY: LOW
Category: Structural Gap (carry-forward from prior reviews)
Affected Rule / Constraint: C4 (the simple API surface is a narrow, stable public contract — §1.6)

What is wrong:
C4 is still not directly enforced. R4-10 proposed a minimal assertion
pinning `ZooKeeper` as `@InterfaceAudience.Public`; the fix was not
applied. No regression — and the classification fixes in R5 have made
the class level shape of the API cleaner — but the specific C4 assertion
still relies on human inspection.

Why it matters:
Low. Mentioned for completeness.

How to fix it:
See R4-10.
```

---

### Recommended Consolidated Patch

The patch below applies **R5-01, R5-02, R5-03, R5-04, R5-05, R5-07, R5-09,
R5-11**. R5-06 is folded into R5-01. R5-08 is left as an honest architectural
signal (Option 3). R5-10 and R5-12 remain as follow-ups.

Expected result: **~145 of the 161 violations eliminated**, leaving ~16 that
trace to (a) the four real `cli → server` smells (R5-08; kept as honest
signal), plus (b) long-tail edges that did not fit cleanly into a bucket.

```java
// ADD: two helpers for R5-03 (array-type unwrap).

private static String rootPackageName(JavaClass c) {
    return c.isArray() ? c.getBaseComponentType().getPackageName() : c.getPackageName();
}

private static String rootName(JavaClass c) {
    return c.isArray() ? c.getBaseComponentType().getName() : c.getName();
}

// REPLACE ROOT_PROTOCOL_TYPES with R5-01 / R5-03 / R5-06 / R5-09 merged version.

private static final DescribedPredicate<JavaClass> ROOT_PROTOCOL_TYPES =
        DescribedPredicate.describe(
                "root-package protocol / public-API / cross-tier types",
                c -> rootPackageName(c).equals("org.apache.zookeeper")
                  && rootName(c).matches(
                        "org\\.apache\\.zookeeper\\."
                      + "(Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode"
                      + "|CreateOptions(\\$.*)?"                           // R5-01b
                      + "|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?"
                      + "|ClientInfo|StatsTrack"
                      + "|Op(\\$.*)?|OpResult(\\$.*)?"                     // R5-01a
                      + "|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?"
                      + "|MultiResponse|DeleteContainerRequest|Quotas"    // R5-01c, R5-01d
                      + "|DigestWatcher|Login(\\$.*)?|ClientWatchManager"
                      + "|SaslClientCallbackHandler(\\$.*)?"
                      + "|SaslServerPrincipal(\\$.*)?"                    // R5-06 (was missing nested suffix)
                      + "|Environment(\\$.*)?|ZookeeperBanner"
                      + "|Shell(\\$.*)?|Version(\\$.*)?)"));               // R5-02

// REPLACE ROOT_CLI_TYPES — drop Shell and Version (they moved to Protocol per R5-02).

private static final DescribedPredicate<JavaClass> ROOT_CLI_TYPES =
        or(name("org.apache.zookeeper.ZooKeeperMain"),
           nameMatching("org\\.apache\\.zookeeper\\.ZooKeeperMain\\$.*"),
           name("org.apache.zookeeper.JLineZNodeCompleter"));

// REPLACE the Client residual predicate to use the array-safe helpers as well.

.layer("Client").definedBy(
        resideInAPackage("org.apache.zookeeper.client..")
        .or(resideInAPackage("org.apache.zookeeper.retry.."))
        .or(DescribedPredicate.describe(
                "root-package non-protocol non-cli classes",
                c -> rootPackageName(c).equals("org.apache.zookeeper")
                  && !ROOT_PROTOCOL_TYPES.test(c)
                  && !ROOT_CLI_TYPES.test(c))))

// REPLACE the allowed-layer matrix: R5-04 and R5-05.

.whereLayer("Infrastructure").mayOnlyAccessLayers(
        "Infrastructure", "Protocol")                              // R5-04
.whereLayer("Protocol").mayOnlyAccessLayers(
        "Infrastructure", "Protocol")
.whereLayer("Monitoring").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server")
.whereLayer("Server").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Server")
.whereLayer("Client").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring",
        "Client", "Cli", "Admin")                                  // R5-05
.whereLayer("Admin").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring",
        "Server", "Client", "Admin")
.whereLayer("Cli").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring",
        "Client", "Admin", "Cli")
.whereLayer("Recipes").mayOnlyAccessLayers(
        "Infrastructure", "Protocol", "Monitoring", "Client", "Recipes")

.whereLayer("Server").mayOnlyBeAccessedByLayers(
        "Admin", "Monitoring", "Server")

// REPLACE the ignoreDependency — add ProviderRegistry and VerifyingFileFactory (R5-07).

.ignoreDependency(
        DescribedPredicate.alwaysTrue(),
        or(name("org.apache.zookeeper.server.ZooKeeperThread"),
           name("org.apache.zookeeper.server.ByteBufferInputStream"),
           name("org.apache.zookeeper.server.ExitCode"),
           name("org.apache.zookeeper.server.ZooTrace"),
           name("org.apache.zookeeper.server.EphemeralType"),
           name("org.apache.zookeeper.server.auth.KerberosName"),
           name("org.apache.zookeeper.server.auth.ProviderRegistry"),                              // R5-07
           name("org.apache.zookeeper.server.watch.PathParentIterator"),
           nameMatching("org\\.apache\\.zookeeper\\.server\\.util\\.VerifyingFileFactory(\\$.*)?"))) // R5-07

// UPDATE the .because() clause for R5-11.

.because(
        "Inferred from §1.1 and §1.7: clients and servers communicate "
        + "only over the TCP wire protocol. Shared records (proto/txn/data "
        + "+ public-API root types) sit in a neutral Protocol layer below "
        + "both tiers; Infrastructure (common / util / compat / compatibility "
        + "/ version) sits below observability and consumes only Protocol "
        + "records. Admin HTTP endpoints run inside the server; ZooKeeperAdmin "
        + "is a specialised client that extends ZooKeeper; CLI is a client-side "
        + "tool; recipes (§1.8) are higher-order primitives on the public API. "
        + "Observability (metrics/jmx/audit) may read Protocol records and "
        + "Server internals.");
```

Projected rule outcome after patch:

| Rule                                           | Before (Review #4 applied) | After (Review #5 applied)               |
|------------------------------------------------|-----------------------------|------------------------------------------|
| `layered_architecture_is_respected`            | FAIL (161 violations)       | FAIL (~4-16 violations, all real smells — see R5-08) |
| `recipes_must_not_depend_on_zookeeper_internals` | PASS                       | PASS (unchanged)                         |

### Summary Table of Findings

| ID    | Severity | Category                                        | Status |
|-------|----------|-------------------------------------------------|--------|
| R5-01 | CRITICAL | Coverage Gap (missing public-API types)         | NEW    |
| R5-02 | HIGH     | Wrong Layer (`Shell`, `Version` in Cli)         | NEW    |
| R5-03 | HIGH     | Semantic Error (array types skip predicate)     | NEW    |
| R5-04 | HIGH     | Overly Narrow (Infrastructure → Protocol)       | NEW    |
| R5-05 | HIGH     | Overly Narrow (Client → Admin missing)          | NEW    |
| R5-06 | HIGH     | Regex gap (`SaslServerPrincipal` nested)        | NEW (folded into R5-01) |
| R5-07 | HIGH     | Cross-cutting debt (2 more server.* utilities)  | R4-07 pattern — partial |
| R5-08 | MEDIUM   | Real Cli → Server smell                         | NEW — keep as honest signal |
| R5-09 | MEDIUM   | Wrong Layer (`ZKUtil` in Protocol)              | NEW    |
| R5-10 | MEDIUM   | Fragile regex classification strategy           | NEW — follow-up |
| R5-11 | LOW      | `.because()` drift after R5-04                  | NEW    |
| R5-12 | LOW      | C4 not directly asserted                        | Carry-forward from R1/R4 |

**Verdict: FAIL WITH WARNINGS.** The architecture model is now structurally
correct — the drop from 1,561 → 161 violations confirms that the Protocol-
layer split (R4-02) and the root-package split (R4-03) were the right moves.
The remaining 161 violations partition into ten well-bounded buckets, eight
of which are easy classification fixes (R5-01 through R5-07, R5-09) and one
of which (R5-08) is a genuine architectural signal worth leaving visible.
Once the consolidated patch lands, `layered_architecture_is_respected` will
pass or leave only the 4-edge R5-08 smell as a documented debt item.
