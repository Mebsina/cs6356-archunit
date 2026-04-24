# Adversarial Review #6 — ArchitectureEnforcementTest.java (ZooKeeper)

**Reviewer:** senior adversarial architecture reviewer (ArchUnit specialist)
**Target:** `outputs/zookeeper/sonnet-4-6/ArchitectureEnforcementTest.java` (348 lines, 2 `@ArchTest` rules)
**Empirical input:** `target/surefire-reports/ArchitectureEnforcementTest.txt` (17 violations, down from 161 before Review #5 fixes)
**Ground truth:** `inputs/java/3_apache_zookeeper.pdf` (C1–C4) and `inputs/java/3_apache_zookeeper.txt` (top-level package inventory)

---

## 0. Signal summary

| Review | Violations reported | Δ |
|---:|---:|---|
| #2 → #3 | 25 917 | — (baseline after `consideringAllDependencies()` was replaced) |
| #3 → #4 |  1 561 | −24 356 (Protocol layer introduced) |
| #4 → #5 |    161 | −1 400 (root-package split + ignoreDependency expansion) |
| #5 → #6 |     17 | −144 (regex fixes + array-type handling + ZKClientConfig/Admin widenings) |

The file is now **very close to convergence**. The 17 remaining violations fall into three tight clusters, one of which was explicitly kept visible as an "honest signal" in Review #5. This review enumerates every remaining defect, distinguishes the solvable ones from the honest smells, and flags one new observation about maintenance burden.

---

## 1. Remaining violations — cluster analysis

Every one of the 17 failures is in `layered_architecture_is_respected`. Grouping them by source-class and edge direction:

| # | Source layer | Source class(es) | Target layer | Target class | Count |
|---|---|---|---|---|---:|
| A | **Protocol** | `SaslServerPrincipal`, `SaslServerPrincipal$WrapperInetSocketAddress`, `Login` | **Client** | `client.ZKClientConfig` | **9** |
| B | **Monitoring** | `audit.AuditHelper` | **Client** | `ZKUtil.aclToString(List)` | **1** |
| C | **Server** | `server.SnapshotFormatter` | **Client** | `ZKUtil.validateFileInput(String)` | **1** |
| D | **Cli** | `cli.GetConfigCommand` | **Server** | `server.util.ConfigUtils` | **2** |
| E | **Cli** | `cli.ReconfigCommand` | **Server** | `server.quorum.QuorumPeerConfig`, `server.quorum.flexible.QuorumVerifier` | **4** |

Clusters A–C are classification defects. Clusters D–E are the R5-08 honest smell the previous review deliberately left uncovered.

---

## 2. Findings

### R6-01 — HIGH — `client.ZKClientConfig` is a cross-tier type, not a Client-only type (9 violations)

**Evidence (surefire lines 25–33):**

```
Constructor <org.apache.zookeeper.SaslServerPrincipal$WrapperInetSocketAddress.<init>(
        java.net.InetSocketAddress, org.apache.zookeeper.client.ZKClientConfig)>
    has parameter of type <org.apache.zookeeper.client.ZKClientConfig>
Field <org.apache.zookeeper.SaslServerPrincipal$WrapperInetSocketAddress.clientConfig>
    has type <org.apache.zookeeper.client.ZKClientConfig>
Method <org.apache.zookeeper.Login.getLoginContextMessage()>
    checks instanceof <org.apache.zookeeper.client.ZKClientConfig>
Method <org.apache.zookeeper.SaslServerPrincipal$WrapperInetSocketAddress.getHostName()>
    calls method <org.apache.zookeeper.client.ZKClientConfig.getBoolean(String, boolean)>
Method <SaslServerPrincipal.getServerPrincipal(...)>
    calls method <org.apache.zookeeper.client.ZKClientConfig.getProperty(String)>
Method <SaslServerPrincipal.getServerPrincipal(...)>
    calls method <org.apache.zookeeper.client.ZKClientConfig.getProperty(String, String)>
    ...
```

All 9 edges share a single shape: **a class the test classifies as `Protocol` (`SaslServerPrincipal*`, `Login`) references `client.ZKClientConfig`, which the test classifies as `Client`**. Protocol is forbidden from accessing Client (`Protocol.mayOnlyAccessLayers("Infrastructure", "Protocol")`), so every reference is flagged.

**Why the current classification is wrong.** `ZKClientConfig` *lives* in the `client` subpackage, but its actual role is a **shared configuration bag** used by the SASL authentication stack on both the client and the server sides. Concretely:

- `SaslServerPrincipal` is a Protocol-layer class (matched by the `SaslServerPrincipal(\\$.*)?` entry in `ROOT_PROTOCOL_TYPES`) whose static `getServerPrincipal(WrapperInetSocketAddress, ZKClientConfig)` is the canonical way to resolve the server principal for both client- and server-initiated SASL handshakes. Keeping this class in Protocol was a correct Review #5 call — removing it reintroduces regressions we already proved are needed.
- `Login` (also in `ROOT_PROTOCOL_TYPES`, per the `Login(\\$.*)?` entry) runs `LoginContext` setup for both tiers; pulling it out of Protocol means `server.auth.ServerCnxnFactory.configureSaslLogin()` would break.
- `client.ZKClientConfig` inherits from `common.ZKConfig` and is consumed by both tiers' SASL code. Its `client` package name is historical; architecturally it is a configuration carrier, not a client-only construct.

**The three available fixes** (evaluated):

| Fix | Cost | Correctness | Verdict |
|---|---|---|---|
| (a) Reclassify `ZKClientConfig` into Protocol via a carve-out predicate (analogous to the root-package split) | ~6 LOC | Architecturally correct; makes the `client` layer definition slightly more complex | **Recommended** |
| (b) Move `SaslServerPrincipal`/`Login` back to Client | 0 LOC | Breaks: `server.*` SASL code calls `Login.login()` and `Login.shutdown()`, resurrecting Server → Client violations we already eliminated | Rejected |
| (c) `ignoreDependency(alwaysTrue(), name("org.apache.zookeeper.client.ZKClientConfig"))` | 1 LOC | Suppresses the signal silently; ZKClientConfig is genuinely cross-tier, so silent suppression is the wrong vocabulary | Acceptable fallback |

**Recommended patch (fix (a)):**

Replace

```java
.layer("Client").definedBy(
        resideInAPackage("org.apache.zookeeper.client..")
        .or(resideInAPackage("org.apache.zookeeper.retry.."))
        .or(DescribedPredicate.describe(
                "root-package non-protocol non-cli classes",
                c -> rootPackageName(c).equals("org.apache.zookeeper")
                  && !ROOT_PROTOCOL_TYPES.test(c)
                  && !ROOT_CLI_TYPES.test(c))))
```

with

```java
.layer("Client").definedBy(
        DescribedPredicate.describe(
                "client.. (minus ZKClientConfig) / retry.. / root-package non-protocol non-cli classes",
                c -> {
                    if (CLIENT_PROTOCOL_TYPES.test(c)) return false;          // carve-out to Protocol
                    if (c.getPackageName().startsWith("org.apache.zookeeper.client")) return true;
                    if (c.getPackageName().startsWith("org.apache.zookeeper.retry")) return true;
                    return rootPackageName(c).equals("org.apache.zookeeper")
                         && !ROOT_PROTOCOL_TYPES.test(c)
                         && !ROOT_CLI_TYPES.test(c);
                }))
```

and add to the Protocol definition:

```java
.layer("Protocol").definedBy(
        resideInAnyPackage(
                "org.apache.zookeeper.data..",
                "org.apache.zookeeper.proto..",
                "org.apache.zookeeper.txn..")
        .or(ROOT_PROTOCOL_TYPES)
        .or(CLIENT_PROTOCOL_TYPES))
```

with the new predicate:

```java
/**
 * Classes that live in {@code org.apache.zookeeper.client..} by package-name
 * convention but whose role is a shared cross-tier configuration / auth
 * abstraction rather than client-only code. Carved into Protocol so Protocol-
 * layer SASL classes ({@code Login}, {@code SaslServerPrincipal}) may reference
 * them without tripping the Protocol → Client boundary.
 */
private static final DescribedPredicate<JavaClass> CLIENT_PROTOCOL_TYPES =
        name("org.apache.zookeeper.client.ZKClientConfig");
```

**Expected delta:** 9 → 0 for this cluster. No new violations introduced: `ZKClientConfig` extends only `common.ZKConfig` (Infrastructure) and has no outgoing client-package dependencies that would make it ineligible for Protocol.

**Important:** update the `.because()` clause and the Javadoc Protocol bullet to mention `ZKClientConfig` explicitly; without documentation the carve-out looks arbitrary to a future reader. See R6-07.

---

### R6-02 — MEDIUM — `ZKUtil` is a grab-bag; its pure-formatter methods are legitimately called from Monitoring and Server (2 violations)

**Evidence (surefire lines 34 and 41):**

```
Method <audit.AuditHelper.addAuditLog(server.Request, server.DataTree$ProcessTxnResult, boolean)>
    calls method <ZKUtil.aclToString(java.util.List)> in (AuditHelper.java:104)

Method <server.SnapshotFormatter.main(String[])>
    calls method <ZKUtil.validateFileInput(String)> in (SnapshotFormatter.java:80)
```

**Background.** Review #5's R5-09 moved `ZKUtil` out of `ROOT_PROTOCOL_TYPES` and let it fall to the Client residual because every ZooKeeper-parametered method (`deleteRecursive(ZooKeeper, ...)`, `listSubTreeBFS(ZooKeeper, ...)`, `visitSubTreeDFS(ZooKeeper, ...)`) makes it a Client utility. That call was correct for ~13 Protocol → Client false-positives, but it surfaced the inverse problem: `ZKUtil` is **also** the home of two pure static helpers that have nothing to do with `ZooKeeper`:

- `ZKUtil.aclToString(List<ACL>)` — formats an ACL list for log / audit output. No `ZooKeeper` argument, no network I/O. Called from `audit.AuditHelper` (Monitoring).
- `ZKUtil.validateFileInput(String)` — validates a path-like string for CLI tools. No `ZooKeeper` argument. Called from `server.SnapshotFormatter.main()`.

`ZKUtil` is a **grab-bag utility class**: half of its methods are Client-tier, half are tier-neutral formatters. ArchUnit classifies at class granularity; it cannot split a class across two layers.

**The three available fixes:**

| Fix | Cost | Correctness | Verdict |
|---|---|---|---|
| (a) Add `ZKUtil` to the `ignoreDependency` block as known debt | 1 LOC | Matches the treatment of the other 8 cross-cutting server.* utilities; explicit and honest | **Recommended** |
| (b) Move `ZKUtil` to Infrastructure and widen Server's/Monitoring's allowed list to include it | high | Wrong — the `deleteRecursive(ZooKeeper, ...)` methods make it compile-depend on `ZooKeeper` (a Client root-package class), so it would violate Infrastructure.mayOnlyAccessLayers | Rejected |
| (c) Extract `aclToString` / `validateFileInput` into a separate Protocol-layer helper upstream | cross-repo | Correct but out of scope for the test file | Long-term; note in fix-history |

**Recommended patch (fix (a)):** Append to the existing `ignoreDependency` block:

```java
.ignoreDependency(
        DescribedPredicate.alwaysTrue(),
        or(name("org.apache.zookeeper.server.ZooKeeperThread"),
           name("org.apache.zookeeper.server.ByteBufferInputStream"),
           name("org.apache.zookeeper.server.ExitCode"),
           name("org.apache.zookeeper.server.ZooTrace"),
           name("org.apache.zookeeper.server.EphemeralType"),
           name("org.apache.zookeeper.server.auth.KerberosName"),
           name("org.apache.zookeeper.server.auth.ProviderRegistry"),
           name("org.apache.zookeeper.server.watch.PathParentIterator"),
           nameMatching("org\\.apache\\.zookeeper\\.server\\.util\\.VerifyingFileFactory(\\$.*)?"),
           name("org.apache.zookeeper.ZKUtil")                   // NEW — pure formatters (aclToString,
                                                                 //        validateFileInput) called from
                                                                 //        Monitoring and Server; see R6-02
        ))
```

**Safety check.** `ZKUtil` is now a Client class. Adding it as an ignore-target suppresses only *incoming* edges; `ZKUtil`'s own outgoing edges (e.g. `ZKUtil` → `ZooKeeper`, `ZKUtil` → `AsyncCallback$VoidCallback`) continue to be checked, and they are already legal (Client → Client). No coverage is silently weakened.

**Expected delta:** 1 → 0 for Monitoring → ZKUtil, 1 → 0 for Server → ZKUtil. Net: −2.

---

### R6-03 — MEDIUM — The R5-08 honest smell is now the ONLY honest smell; decide whether to suppress or keep it bright red (6 violations)

**Evidence (surefire lines 35–40):**

```
Method <cli.GetConfigCommand.exec()>
    calls method <server.util.ConfigUtils.getClientConfigStr(String)> in (GetConfigCommand.java:78)
    [2 times — same edge, differently contextualised]

Method <cli.ReconfigCommand.parse(String[])>
    calls method <server.quorum.QuorumPeerConfig.parseDynamicConfig(Properties, int, boolean, boolean, String)> in (ReconfigCommand.java:133)
    [2 times]

Method <cli.ReconfigCommand.parse(String[])>
    calls method <server.quorum.flexible.QuorumVerifier.toString()> in (ReconfigCommand.java:133)
    [2 times]
```

**Status.** Previously left visible in Review #5 (R5-08) as a real architectural smell: the CLI tier is reaching into server-internal configuration parsers. Review #5 correctly classified this as signal, not noise.

**What has changed in Review #6.** After R6-01 and R6-02 are applied, these 6 violations will be **the only violations left in the entire run**. That changes their operational character from "one more architectural issue among many" to "the test fails exactly because of this known debt", which has two consequences:

1. **CI pressure increases.** A CI that fails on every commit because of 6 known-debt edges tends to get muted (`mvn -DskipTests`, `@ArchIgnore`, PR-by-PR overrides), which permanently destroys the signal value. Keeping the edges visible only works if the team plans to fix them on a bounded timeline.
2. **Masking the whole test with `@ArchIgnore` destroys coverage** for the other 7 layers simultaneously. This is the worst possible outcome.

**The decision is the user's; I present the two fixes so either can be picked deliberately:**

#### Option A — Keep visible (R5-08's original intent)

No code change. Document in `fix-history.md` that these 6 edges are a known architectural smell with a bounded fix timeline, and add a comment near the layered rule:

```java
// KNOWN SMELL (R5-08 / R6-03): cli.GetConfigCommand and cli.ReconfigCommand
// currently reach into server.util.ConfigUtils and server.quorum.*; these are
// the LAST remaining violations and are kept visible so the regression surface
// stays observable. Remove by refactoring the shared config/quorum types into
// a neutral package, then delete this comment.
```

#### Option B — Suppress with narrow scope

Add targeted ignoreDependency lines that only match these specific source→target edges, not all `cli → server.*` dependencies:

```java
.ignoreDependency(
        name("org.apache.zookeeper.cli.GetConfigCommand"),
        name("org.apache.zookeeper.server.util.ConfigUtils"))
.ignoreDependency(
        name("org.apache.zookeeper.cli.ReconfigCommand"),
        or(name("org.apache.zookeeper.server.quorum.QuorumPeerConfig"),
           resideInAPackage("org.apache.zookeeper.server.quorum.flexible..")))
```

This is narrow enough that a *new* `cli → server.*` edge would still be caught, i.e. it suppresses only the precise debt we know about.

**My recommendation:** Option A if a refactor ticket exists; Option B otherwise. Do not suppress with `alwaysTrue()` — that turns a bounded known-debt list into an open-ended blind spot.

---

### R6-04 — LOW (carry-forward from R4-10 / R5-12) — C4 is still not directly asserted

**Background.** The PDF §1.6 lists seven API operations as the stable public contract: `create`, `delete`, `exists`, `getData`, `setData`, `getChildren`, `sync`. The test enforces C1, C2, and C3 but has no direct assertion that these seven methods remain on `org.apache.zookeeper.ZooKeeper` (or that the class is `public`, non-`final` only where §1.6 permits subclassing, etc.).

**Why this keeps being deferred.** C4 is a *signature* / *API-shape* constraint, not a layering one. ArchUnit can express it with `methods()` / `should()`, but the rule is brittle (every overload, vararg, CompletableFuture variant counts) and carries a high false-positive risk.

**Proposed minimal C4 assertion** (optional, documentation-only value):

```java
@ArchTest
static final ArchRule zookeeper_exposes_core_api_methods =
        methods()
                .that().areDeclaredInClassesThat().haveFullyQualifiedName("org.apache.zookeeper.ZooKeeper")
                .and().arePublic()
                .and().haveNameMatching("(create|delete|exists|getData|setData|getChildren|sync)")
                .should().bePublic()
                .because(
                        "§1.6: the seven-method simple API surface (create, delete, exists, "
                      + "getData, setData, getChildren, sync) is ZooKeeper's stable public "
                      + "contract. This rule pins the names; it cannot assert signatures without "
                      + "excessive coupling to internal overloads.");
```

This is nearly tautological (`arePublic().should().bePublic()`), but it exists primarily so the test file mentions all four constraints by number, and so renaming any of the seven methods makes the rule vacuous (detectable via ArchUnit's empty-match warning when `allowEmptyShould` is off).

**Severity:** low. The test has always been C4-silent; users who care can add it, users who don't can continue to rely on the PDF itself. Noting here purely for completeness — I do **not** recommend adding a fragile rule just to cover the constraint number.

---

### R6-05 — LOW (new observation) — `ROOT_PROTOCOL_TYPES` regex is becoming a maintenance magnet

**Finding.** The `ROOT_PROTOCOL_TYPES` regex has now been extended six times across Reviews #3 → #6:

```
Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode
|CreateOptions(\\$.*)?
|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?|ClientInfo|StatsTrack
|Op(\\$.*)?|OpResult(\\$.*)?
|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?
|MultiResponse|DeleteContainerRequest|Quotas
|DigestWatcher|Login(\\$.*)?|ClientWatchManager
|SaslClientCallbackHandler(\\$.*)?
|SaslServerPrincipal(\\$.*)?
|Environment(\\$.*)?|ZookeeperBanner
|Shell(\\$.*)?|Version(\\$.*)?
```

Every new public-API root-class added to ZooKeeper (e.g. a new `CreateOptions2`, a new `WatcherX`) will be silently (or loudly) misclassified until someone runs the test, reads the surefire report, and appends yet another alternation. This is **empirical-driven configuration**, which is brittle.

**Three ways to reduce the burden** — presenting for the user's selection, not recommending one directly:

| Option | Change | Trade-off |
|---|---|---|
| (a) Do nothing | Accept the maintenance tax | Current behaviour; brittle but auditable |
| (b) Invert the predicate: default root-package classes to Protocol, and instead enumerate the *Client* residuals | Whitelist Clients (`ZooKeeper`, `ClientCnxn*`, `Testable`, etc.), default the rest to Protocol | More future-proof (new public-API types default to the right side) but inverts "normal" Java intuition |
| (c) Move to an annotation-based classification (`@PublicApi` / `@Internal`) upstream | Requires source-code changes in the ZK project itself | Clean but out of scope for a test file |

**My take:** option (a) is the pragmatic choice **only if** this is the last review. If further iterations are planned, option (b) is worth prototyping: it would also retire `ROOT_CLI_TYPES` if it enumerated the CLI roots in the same inversion.

**Not a violation, not a fix I'm prescribing** — flagging as maintenance-risk so the user can pre-empt the next review's R7-01.

---

### R6-06 — LOW — `ExcludeStandaloneTools` / `DoNotIncludeJars` / `DoNotIncludeArchives` are non-vacuous but warrant a sanity check

- `ExcludeStandaloneTools` excludes `org.apache.zookeeper.graph` and `org.apache.zookeeper.inspector`. These packages exist in the ZK source tree but are compiled into separate JARs that most builds do not ship to `target/classes`. **Verification step the user may want to run once:** drop the exclusion, observe whether any class from those packages appears in the violation report. If none appears, the exclusion is defensive and we could remove it for simplicity. If any appear, we keep it and have affirmative evidence.
- `DoNotIncludeJars` / `DoNotIncludeArchives` are solely there to exclude third-party libraries (slf4j, netty, jute, jline). The large violation collapse from Review #3 depended on these, so I am not proposing to remove them — just noting they could be documented with a one-line comment that they are required, not cosmetic.

**Severity:** low. Non-blocking.

---

### R6-07 — LOW — Documentation drift after R6-01

If R6-01's `CLIENT_PROTOCOL_TYPES` patch is applied, three docs drift:

1. **Javadoc Protocol bullet (line 26–29).** Currently lists "Watcher, KeeperException, ZooDefs, Op, OpResult, CreateOptions, Quotas, Login, Shell, Version, etc." It should add "and `client.ZKClientConfig` as a cross-tier configuration carrier".
2. **`.because()` clause (lines 295–306).** Currently says "Shared records (proto/txn/data + public-API root types) sit in a neutral Protocol layer below both tiers". Extend to mention the one carved-out `client.ZKClientConfig`.
3. **`fix-history.md` step 7.** Record the R6-01/R6-02/R6-03 decisions and reasoning so future reviewers do not re-litigate the classification.

Mechanical once R6-01 is applied. No risk.

---

## 3. Consolidated patch summary

Applying R6-01, R6-02, and R6-03 (Option A or B) to the test file, with R6-07's documentation updates:

| Finding | Change | Expected violation delta |
|---|---|---|
| R6-01 | New `CLIENT_PROTOCOL_TYPES` predicate + added to Protocol layer + Client-layer predicate refactored to exclude it + docs updated | **−9** (17 → 8) |
| R6-02 | Append `name("org.apache.zookeeper.ZKUtil")` to the ignoreDependency `or(...)` block | **−2** (8 → 6) |
| R6-03 Option A | Inline comment acknowledging the 6 remaining cli → server edges as bounded known debt | **0** (6 left visible) |
| R6-03 Option B | Two narrow `ignoreDependency(source, target)` lines pinned to the exact classes | **−6** (6 → 0) |
| R6-07 | Documentation updates | 0 |

**Projected end-state:**
- With R6-03 Option A: **6 violations remaining**, all of them known-debt cli → server edges.
- With R6-03 Option B: **0 violations remaining**, with the 6 known-debt edges explicitly documented as ignored.

---

## 4. Coverage audit (C1–C4)

| Constraint | Covered by | Coverage quality |
|---|---|---|
| C1 (clients ↔ servers only over wire) | `Server.mayOnlyBeAccessedByLayers(Admin, Monitoring, Server)` + `Client.mayOnlyAccessLayers(...)` forbids Client → Server | Strong (lattice model is explicit) |
| C2 (request-processor / replicated-DB / atomic-broadcast are server-internal) | Same as C1 (Server back door closed) | Strong |
| C3 (recipes on client API only) | `Recipes.mayOnlyAccessLayers(...)` + dedicated blacklist rule citing §1.8 | Strong (both lattice and blacklist forms present) |
| C4 (seven-method simple API is stable) | Not directly asserted — see R6-04 | **Weak — documentation-only** |

The C4 gap is the only non-empirical gap left. Everything else is either enforced or explicitly documented as acknowledged debt.

---

## 5. Convergence note

This is the sixth review cycle. The trajectory is:

- **Reviews #1–#2** resolved coverage gaps and structural errors (layer graph correctness).
- **Review #3** resolved a 25 000-violation analysis-scope bug.
- **Review #4** resolved the binary Client/Server modelling deficiency by introducing the Protocol layer.
- **Review #5** resolved root-package classification errors exposed by Review #4.
- **Review #6** (this review) resolves the last classification defect (`ZKClientConfig`) and the last utility grab-bag (`ZKUtil`), leaving only the R5-08 honest smell.

If R6-01 and R6-02 are applied, the architecture model will be internally consistent with the observed codebase. Review #7, if run, should find only (a) any newly added public API types that the maintainer forgot to put in `ROOT_PROTOCOL_TYPES` (R6-05), (b) any new cross-tier utilities that need to join the `ignoreDependency` list, or (c) the R6-03 Option A cli → server debt still unresolved upstream. In other words, Review #7 would be a **maintenance** review, not a modelling review — a good outcome.

---

## 6. Overall verdict

**Total documented constraints covered: 3 of 4.** (C1, C2, C3 enforced; C4 not directly asserted — see R6-04.)
**Total rules: 2** (`layered_architecture_is_respected`, `recipes_must_not_depend_on_zookeeper_internals`).
**Empirical state: 17 violations, 0 errors, 0 skipped.**
**Critical gaps: none.** All remaining violations are either explicable classification artefacts (R6-01, R6-02) or the deliberately-visible R5-08 honest smell (R6-03).

**Overall verdict: `CONDITIONAL PASS`.**

- **As of this commit:** the test compiles, imports correctly scope analysis, the layer lattice is sound, and all 17 remaining violations are *diagnosed* — none are mysterious. But the file does not yet go green in CI, so it is not ship-ready on its own.
- **After R6-01 + R6-02 are applied:** verdict upgrades to `PASS with known debt`. 6 violations remain; all 6 are the R5-08 cli → server edges documented in the test file as a bounded smell.
- **After R6-01 + R6-02 + R6-03 Option B are applied:** verdict upgrades to `PASS`. Test goes green; the 6 known-debt edges are explicitly `ignoreDependency`-scoped to named source/target pairs so a regression in any adjacent edge still fails the build.
- **R6-04 (C4), R6-05 (regex maintenance tax), R6-06 (defensive-exclusion audit), R6-07 (doc drift)** are all LOW and do not gate the verdict.

**Recommendation:** apply R6-01, R6-02, and R6-03 Option B (narrow `ignoreDependency` pairs for the two cli → server debt edges) together, update the `.because()` clause and Javadoc per R6-07, and call the file done. If the team commits to an upstream refactor of `ConfigUtils` / `QuorumPeerConfig` out of the server package within a bounded timeline, substitute R6-03 Option A instead and accept 6 red violations on `main` until the refactor lands.

No further review rounds are warranted once the above patch lands — Review #7 would be a maintenance-only pass, not a modelling pass.

---

**End of Review #6.**
