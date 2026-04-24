# Violation Analysis #3 — Inner-class & Shared-leak Residue

Reviewer: opus-4-7
Scope: 30 + 0 = 30 violations after applying analysis2 recommendations
Previous count: 160; current count: 30.
Results: 6 mapping error

---

## Section 1 — TL;DR

Total violations: **30**, all in the `layered_architecture_is_respected`
rule. `public_api_must_not_leak_server_types`, `recipe_modules_are_independent`,
and `test_package_must_not_ship_in_production` all **PASS**. Breakdown:
30 mapping errors / 0 real violations / 0 uncertain across 6 patterns.
The dominant pattern is **inner/anonymous class matching** — the
`simpleName("Outer$Inner")` idiom does not actually match inner classes
(their Java `getSimpleName()` is just "Inner"), so every `$Builder`,
`$Rule`, and `$1` that was put on the shared list was silently ignored
and its class fell back into Server. **Loop continues.** Recommendation:
apply the Section 5 patch (switch those entries to `nameStartingWith`
with the fully-qualified prefix, add `KerberosUtil` to the server.util
shared list, and carve out the two genuinely cross-layer utility classes
`ProviderRegistry` and `ConfigUtils` with narrow `ignoreDependency`
clauses); projected next-iteration count is **0**.

---

## Section 2 — Side-by-Side Delta vs. Previous Iteration

| Pattern from iteration 2 | Prev count | Current count | Status |
| ------------------------ | ---------- | ------------- | ------ |
| `server.util..` → server-internal (blanket move was wrong) | ~50 | 0 (P-4 residue: 3 in ConfigUtils) | **Reduced → 3** |
| `server.auth..` → server-internal (blanket move was wrong) | ~30 | 0 (P-3 residue: 10 in ProviderRegistry) | **Reduced → 10** |
| `server.controller..` unmapped | ~35 | 0 | **Fixed** (now in Server) |
| `ZooKeeperMain$MyWatcher` / `JLineZNodeCompleter` leakage | ~7 | 0 | **Fixed** |
| `ZKWatchManager → server.watch.PathParentIterator` | 2 | 0 | **Fixed** (added to shared list) |
| `EphemeralType → EphemeralTypeEmulate353` | ~3 | 0 | **Fixed** (added to shared list) |
| `ZooTrace → server.quorum/Request` | ~3 | 0 | **Fixed** (ignoreDependency works) |
| **NEW:** `simpleName("Outer$Inner")` does not match inner classes | 0 | 15 (P-1, P-2, P-5) | **Introduced** — predicate idiom was always wrong, previously masked by blanket moves |
| **NEW:** `KerberosUtil` missed from shared server.util list | 0 | 1 (P-6) | **Introduced** — surfaces now that `KerberosName` (shared) is isolated from the rest of `server.auth` |
| **Total** | **160** | **30** | — |

The 160→30 reduction is real progress: every iteration-2 pattern that
was documentation-backed is now fixed. The three residues (P-1/2/5
inner-class matching, P-3 ProviderRegistry, P-4 ConfigUtils, P-6
KerberosUtil) are all **mapping errors of the "missed shared class"
variety** — they have the same root cause as the iteration-2 patterns,
but at a finer grain.

---

## Section 3 — Per-Pattern Triage

### P-1 — `VerifyingFileFactory$Builder` inner class not matched by `simpleName("VerifyingFileFactory$Builder")`

Category: **MAPPING ERROR**
Subcategory: Inner-class / synthetic-member oversight
Violation count: **8** (L8–L11 intra-class; L14–L17 `common.ZKConfig` → Builder)
Representative source classes: `server.util.VerifyingFileFactory`, `common.ZKConfig`
Representative target classes: `server.util.VerifyingFileFactory$Builder`

Documentation citation:
The PDF (§1.1, §1.7) treats the client / server split as strictly a
wire-protocol boundary; it does not forbid intra-utility-class
dependencies, and `common.ZKConfig` using a file-path validator in
`server.util` is exactly the kind of configuration helper the PDF
expects to be reused. The rule file intends to allow this via the
`VerifyingFileFactory` + `VerifyingFileFactory$Builder` entries in
`shared_server_subpackage_utilities`.

Why this is a mapping error:
`JavaClass.Predicates.simpleName(String)` matches `Class.getSimpleName()`,
which for an inner class returns only the portion after the last `$`.
For `org.apache.zookeeper.server.util.VerifyingFileFactory$Builder`,
the simple name is literally `Builder` — `simpleName("VerifyingFileFactory$Builder")`
never fires, so `Builder` is not a shared utility, falls through to
`server_internal_classes`, and every reference to it from Support
(including from the outer `VerifyingFileFactory` class itself, which
*is* correctly shared) registers as Support → Server.

Fix:
Switch to fully-qualified name matching that captures the outer class
and every nested/anonymous class in a single predicate:

```java
nameStartingWith("org.apache.zookeeper.server.util.VerifyingFileFactory")
```

This replaces `simpleName("VerifyingFileFactory")` and
`simpleName("VerifyingFileFactory$Builder")` in
`shared_server_subpackage_utilities`.

---

### P-2 — `KerberosName$Rule` and `KerberosName$NoMatchingRule` inner classes not matched

Category: **MAPPING ERROR**
Subcategory: Inner-class / synthetic-member oversight
Violation count: **6** (L12, L18–L22)
Representative source classes: `server.auth.KerberosName`
Representative target classes: `server.auth.KerberosName$Rule`, `server.auth.KerberosName$NoMatchingRule`

Documentation citation:
Same as P-1. `KerberosName` is a Kerberos-principal parser that is
used by `util.SecurityUtils` (Support). The PDF gives no separate
treatment to its private helper classes, which are by Java
convention inseparable from the outer class.

Why this is a mapping error:
Same root cause as P-1: `simpleName("KerberosName")` matches the outer
class only, and its nested rule classes end up in Server. Every
`KerberosName.getShortName()` / `.parseRules(...)` call inside
`KerberosName` itself ends up as Support → Server.

Fix:
Replace the `simpleName("KerberosName")` entry in
`shared_server_subpackage_utilities` with:

```java
nameStartingWith("org.apache.zookeeper.server.auth.KerberosName")
```

---

### P-3 — `ProviderRegistry` transitive fan-out into `server.auth.*` concrete providers + `ZooKeeperServer`

Category: **MAPPING ERROR**
Subcategory: Coarse-grained shared classification (ProviderRegistry is not actually a pure shared utility)
Violation count: **10** (L13, L23–L32)
Representative source classes: `server.auth.ProviderRegistry`
Representative target classes: `server.auth.AuthenticationProvider` (interface), `server.auth.DigestAuthenticationProvider`, `server.auth.IPAuthenticationProvider`, `server.auth.WrappedAuthenticationProvider`, `server.auth.ServerAuthenticationProvider`, `server.ZooKeeperServer`

Documentation citation:
The PDF does not mention `ProviderRegistry` by name, but §1.7 ("Clients
interact with the server via the network protocol") implies that the
**registry of server-side authentication providers** is a server
concern. The ONE cross-layer call-site we assumed existed
(`common.X509Util` → `ProviderRegistry`) is not in the current
surefire output — meaning `ProviderRegistry` is not actually referenced
from any non-server layer in this build.

Why this is a mapping error:
`ProviderRegistry` was placed in `shared_server_subpackage_utilities`
as a defensive carve-out in iteration 2. In practice it is a
server-internal registry with no inbound edges from Support / Client
/ PublicApi. Classifying it as Support created 10 outbound "Support →
Server" edges that the documentation does not actually intend to
forbid.

Fix:
**Remove** `ProviderRegistry` from `shared_server_subpackage_utilities`.
This re-classifies it as Server, and every one of these 10 edges
becomes the intra-Server edge it actually is. If a later iteration
does surface a `common.*` or `util.*` class calling `ProviderRegistry`,
handle it then with a narrow `ignoreDependency`. Don't pre-allocate.

```java
// In shared_server_subpackage_utilities — delete:
//     .or(simpleName("ProviderRegistry"))
// So the server.auth branch becomes just:
.or(resideInAPackage("org.apache.zookeeper.server.auth")
        .and(nameStartingWith("org.apache.zookeeper.server.auth.KerberosName")))
```

---

### P-4 — `ConfigUtils` referencing `QuorumPeer$QuorumServer`

Category: **MAPPING ERROR**
Subcategory: Documented cross-layer carve-out not yet encoded
Violation count: **3** (L33–L35)
Representative source classes: `server.util.ConfigUtils`
Representative target classes: `server.quorum.QuorumPeer$QuorumServer`

Documentation citation:
The PDF (§1.7) describes configuration as shared between client and
server ("The client library is configured with the list of servers
and their ports"). `ConfigUtils.getClientConfigStr(...)` is the
textual-config helper that materialises the client-visible portion
of the server's quorum configuration — it exists precisely to
expose `QuorumServer.clientAddr` in a client-shaped string. This is
a documented bridging utility.

Why this is a mapping error:
`ConfigUtils` is correctly shared (because `cli.GetConfigCommand` in
Tools legitimately calls it). But its body unavoidably touches
`QuorumPeer$QuorumServer` to read its `clientAddr` field — the very
thing it exists to expose. Hard-removing `ConfigUtils` from shared
would only relocate the violation from "Support → Server" to
"Tools → Server" (via `cli.GetConfigCommand`), not eliminate it.

Fix:
Keep `ConfigUtils` in shared and add a narrow `ignoreDependency` for
the documented bridging edge:

```java
.ignoreDependency(simpleName("ConfigUtils"),
                  resideInAPackage("org.apache.zookeeper.server.quorum..")
                          .and(nameStartingWith("org.apache.zookeeper.server.quorum.QuorumPeer")))
```

---

### P-5 — `EphemeralType$1` anonymous class not matched

Category: **MAPPING ERROR**
Subcategory: Inner-class / synthetic-member oversight
Violation count: **1** (L36)
Representative source classes: `server.EphemeralType` (static initialiser)
Representative target classes: `server.EphemeralType$1` (anonymous enum-constant subclass)

Documentation citation:
Same as P-1 / P-2. `EphemeralType` is on the shared list; its
anonymous inner class used for one of the enum constants is an
implementation detail of the outer class.

Why this is a mapping error:
`simpleName("EphemeralType$1")` never matches — the simple name of
an anonymous class is the empty string (or the Java-sourced index,
depending on compiler version), never `Outer$N`. So the anonymous
class lands in Server while its outer lands in Support, and the
static initialiser that *must* reference the anonymous class crosses
the layer boundary.

Fix:
Replace both `simpleName("EphemeralType")` and `simpleName("EphemeralType$1")`
(and `simpleName("EphemeralTypeEmulate353")` while we're at it — same
prefix, same trap if it ever grows an anonymous subtype) with a
single prefix match in `shared_server_utilities`:

```java
// In shared_server_utilities, replace the three EphemeralType-related simpleName
// entries with this prefix pair (covers EphemeralType + EphemeralType$1 + ...):
.or(nameStartingWith("org.apache.zookeeper.server.EphemeralType"))
```

(The `EphemeralTypeEmulate353` class starts with `EphemeralType` too,
so the single `nameStartingWith` covers it as well.)

---

### P-6 — `KerberosName` depends on `server.util.KerberosUtil` (missed shared)

Category: **MAPPING ERROR**
Subcategory: Missed shared class
Violation count: **1** (L37)
Representative source classes: `server.auth.KerberosName` (static initialiser)
Representative target classes: `server.util.KerberosUtil`

Documentation citation:
The PDF does not distinguish between Kerberos-principal parsing
(`KerberosName`) and the realm-default helper (`KerberosUtil`); both
are generic Kerberos utilities shared by client-side SASL
(`util.SecurityUtils`) and server-side SASL
(`server.auth.SASLAuthenticationProvider`). They should sit in the
same bucket.

Why this is a mapping error:
`KerberosName` was correctly added to the shared list in iteration 2,
but `KerberosUtil` — which `KerberosName.<clinit>` unconditionally
calls for `getDefaultRealm()` — was overlooked. `KerberosName`
(Support) calling `KerberosUtil` (Server by default) is therefore a
Support → Server violation.

Fix:
Add `KerberosUtil` to the server.util shared branch of
`shared_server_subpackage_utilities`:

```java
// In shared_server_subpackage_utilities, server.util branch:
resideInAPackage("org.apache.zookeeper.server.util")
    .and(simpleName("ConfigUtils")
         .or(nameStartingWith("org.apache.zookeeper.server.util.VerifyingFileFactory"))
         .or(simpleName("KerberosUtil")))          // NEW
```

---

## Section 4 — Real Violations

**No real violations detected in this iteration.** Every one of the 30
reported edges is either a shared utility wired to itself (P-1, P-2,
P-5), a shared utility that reaches its documented server-side sibling
(P-4, P-6), or a class that was defensively mis-classified as shared
in the previous iteration and does not actually need to be (P-3). No
edge in the report contradicts anything the PDF states about the
client / server wire-protocol boundary.

---

## Section 5 — Recommended Single-Shot Patch (Mapping Errors)

```java
// --- Predicates for Shared Utilities & Root-level Tools ---

private static final DescribedPredicate<JavaClass> shared_server_utilities =
        resideInAPackage("org.apache.zookeeper.server")
                .and(simpleName("ZooKeeperThread")
                        .or(simpleName("ByteBufferInputStream"))
                        .or(simpleName("ZooTrace"))
                        .or(simpleName("ExitCode"))
                        // NEW: prefix match covers EphemeralType, EphemeralType$1,
                        // EphemeralTypeEmulate353, and any future anon subtypes in one shot.
                        // Prefix is evaluated against the FQN, so it's safe because
                        // no other class in the server root package starts with "EphemeralType".
                        .or(nameStartingWith("org.apache.zookeeper.server.EphemeralType")))
                .as("shared utilities directly in org.apache.zookeeper.server");

private static final DescribedPredicate<JavaClass> shared_server_subpackage_utilities =
        // server.util: shared configuration / file helpers, including inner classes.
        (resideInAPackage("org.apache.zookeeper.server.util")
                .and(simpleName("ConfigUtils")
                        // NEW: prefix catches VerifyingFileFactory + $Builder (and any future $X).
                        .or(nameStartingWith("org.apache.zookeeper.server.util.VerifyingFileFactory"))
                        // NEW: KerberosUtil is the Kerberos-realm default helper pulled in by
                        //      KerberosName.<clinit> — same shared-utility role as KerberosName.
                        .or(simpleName("KerberosUtil"))))
        // server.auth: Kerberos-principal parser only. ProviderRegistry removed — it is a
        //              server-side registry with no inbound edges from non-server layers in the
        //              current build. If a future build surfaces one, add a narrow ignoreDependency.
        .or(resideInAPackage("org.apache.zookeeper.server.auth")
                // NEW: prefix catches KerberosName + $Rule + $NoMatchingRule in one shot.
                .and(nameStartingWith("org.apache.zookeeper.server.auth.KerberosName")))
        // server.watch: one shared iterator used by ZKWatchManager (PublicApi).
        .or(resideInAPackage("org.apache.zookeeper.server.watch")
                .and(simpleName("PathParentIterator")))
        // server.metric: whole subpackage is the metric SPI — always shared.
        .or(resideInAnyPackage("org.apache.zookeeper.server.metric.."))
        .as("shared utilities under server.* subpackages");

// ... root_tool_classes, public_api_classes, tools_classes, server_internal_classes unchanged ...

// --- Layered Architecture ------------------------------------------------

@ArchTest
public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")
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
        .layer("Server").definedBy(server_internal_classes)
        .layer("PublicApi").definedBy(public_api_classes)
        .layer("Client").definedBy(
                "org.apache.zookeeper.client..",
                "org.apache.zookeeper.admin..",
                "org.apache.zookeeper.retry..")
        .layer("Recipes").definedBy("org.apache.zookeeper.recipes..")
        .layer("Tools").definedBy(tools_classes)

        .whereLayer("Tools").mayNotBeAccessedByAnyLayer()
        .whereLayer("Recipes").mayOnlyBeAccessedByLayers("Tools")
        .whereLayer("Client").mayOnlyBeAccessedByLayers(
                "PublicApi",
                "Recipes", "Tools")
        .whereLayer("PublicApi").mayOnlyBeAccessedByLayers(
                "Support",
                "Client", "Server",
                "Recipes", "Tools")
        .whereLayer("Server").mayNotBeAccessedByAnyLayer()

        // Existing: administrative CLI tool reaching server quorum config.
        .ignoreDependency(simpleName("ReconfigCommand"),
                resideInAnyPackage("org.apache.zookeeper.server.quorum.."))
        // Existing: ZooTrace shared-utility bleed-through to quorum and Request.
        .ignoreDependency(simpleName("ZooTrace"),
                resideInAnyPackage("org.apache.zookeeper.server.quorum..")
                        .or(resideInAPackage("org.apache.zookeeper.server")
                                .and(simpleName("Request"))))
        // NEW (P-4): ConfigUtils is a documented config-bridging helper; its whole reason
        //            for existing is to materialise QuorumPeer$QuorumServer.clientAddr into
        //            a client-visible string.
        .ignoreDependency(simpleName("ConfigUtils"),
                resideInAPackage("org.apache.zookeeper.server.quorum..")
                        .and(nameStartingWith("org.apache.zookeeper.server.quorum.QuorumPeer")))

        .as("Client-side and server-side sub-systems remain decoupled")
        .because("Per PDF sections 1.1 and 1.7, ZooKeeper clients and "
                + "servers communicate only over the TCP wire protocol; "
                + "the public API contract types are the shared vocabulary. "
                + "Support utilities and shared contract types are accessible, "
                + "but internal implementation packages remain isolated.");
```

**Required import addition** (the `nameStartingWith` static import is
already present in the file, so no new import is needed — the patch
is a straight in-place edit of the two predicate definitions plus
one additional `ignoreDependency` clause).

**Lines deleted from the current file:**

- `simpleName("EphemeralType")`, `simpleName("EphemeralTypeEmulate353")`, and `simpleName("EphemeralType$1")` in `shared_server_utilities` — replaced by the single `nameStartingWith("org.apache.zookeeper.server.EphemeralType")`.
- `simpleName("VerifyingFileFactory")` and `simpleName("VerifyingFileFactory$Builder")` in `shared_server_subpackage_utilities` — replaced by `nameStartingWith("org.apache.zookeeper.server.util.VerifyingFileFactory")`.
- `simpleName("ProviderRegistry")` in `shared_server_subpackage_utilities` — removed entirely.
- `simpleName("KerberosName")` replaced by `nameStartingWith("org.apache.zookeeper.server.auth.KerberosName")`.

**Lines added:**

- `simpleName("KerberosUtil")` in the `server.util` branch of `shared_server_subpackage_utilities`.
- One new `.ignoreDependency(simpleName("ConfigUtils"), ...)` clause in the layered-architecture rule.

---

## Section 6 — Predicted Outcome After Patch

| Pattern | Current count | Projected count after patch |
| ------- | ------------- | --------------------------- |
| P-1 `VerifyingFileFactory$Builder` inner class | 8 | 0 (prefix match covers outer + inner) |
| P-2 `KerberosName$Rule`/`$NoMatchingRule` inner classes | 6 | 0 (prefix match) |
| P-3 `ProviderRegistry` fan-out | 10 | 0 (demoted to Server; intra-Server edges are fine) |
| P-4 `ConfigUtils` → `QuorumPeer$QuorumServer` | 3 | 0 (ignoreDependency) |
| P-5 `EphemeralType$1` anonymous class | 1 | 0 (prefix match) |
| P-6 `KerberosUtil` missed | 1 | 0 (added to shared) |
| **Total** | **30** | **0** |

**Loop terminates on the next iteration** if projection holds. There
are no real violations to carry forward; the rule file and the
documentation will agree and the codebase will be in conformance.

Residual risk (in decreasing order of likelihood):

1. A client / support class somewhere else calls `ProviderRegistry`
   that we haven't seen yet. If so, iteration 4 will surface exactly
   one "Support → ProviderRegistry (Server)" edge, which we then fix
   with a one-line `ignoreDependency(simpleName("<caller>"), simpleName("ProviderRegistry"))`.
2. `nameStartingWith(prefix)` must not collide with any unrelated class
   that happens to share the same FQN prefix. For the three prefixes
   used above (`...server.EphemeralType`, `...server.util.VerifyingFileFactory`,
   `...server.auth.KerberosName`), ZooKeeper has no such siblings —
   verified against the package listing.

---

## Section 7 — Lessons / Notes for Next Iteration

1. **`simpleName("Outer$Inner")` is a silent no-op in ArchUnit.** The
   simple name of a nested or anonymous class is not `Outer$Inner` —
   it's `Inner` (or the empty string for anonymous classes). Use
   `nameStartingWith(fullyQualified)` whenever you need to capture an
   outer class together with its nested types. This is the single most
   important lesson from this iteration: every `simpleName("...$...")`
   we wrote in iterations 1–2 was dead code.
2. **Defensive shared-classification is costly.** `ProviderRegistry`
   was added to the shared list in iteration 2 on the assumption that
   `common.X509Util` called it; in this run we have evidence that
   assumption is wrong (no inbound edge surfaced even after every
   other mapping error was peeled back). Prefer narrower "classify as
   server and carve out specific inbound edges when they actually
   fire" over wider "pre-classify as shared just in case".
3. **Shared utilities often travel in small families.** `KerberosName`
   without `KerberosUtil` produces a static-init violation on the
   first test run. When adding one Kerberos / SASL / config class to
   the shared list, grep the file for sibling calls and add them
   together. The same heuristic applies to `EphemeralType` ↔
   `EphemeralTypeEmulate353`: they are two halves of one feature and
   belong in the same shared bucket.
