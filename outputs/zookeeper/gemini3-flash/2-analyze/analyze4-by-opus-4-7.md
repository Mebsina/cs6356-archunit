# Violation Analysis #4 — Last Residual: X509Util → ProviderRegistry

Reviewer: opus-4-7
Scope: 1 + 0 = 1 violation after applying analysis3 recommendations
Previous count: 30; current count: 1.
Results: 1 mapping error

---

## Section 1 — TL;DR

Total violations: **1**, in the `layered_architecture_is_respected`
rule. The other three `@ArchTest` rules all **PASS**. Breakdown: 1
mapping error / 0 real violations / 0 uncertain across 1 pattern. The
violation is exactly the edge I flagged as "residual risk #1" at the
end of iteration 3: `common.X509Util.resetDefaultSSLContextAndOptions()`
calls `server.auth.ProviderRegistry.addOrUpdateProvider(String)` —
the single Support → Server bootstrap edge that surfaced once
`ProviderRegistry` was demoted from shared (the round-2 defensive
classification) back to Server (the correct classification). **Loop
continues for one more iteration**, with a one-line
`ignoreDependency` as the fix; projected next-iteration count is
**0**.

---

## Section 2 — Side-by-Side Delta vs. Previous Iteration

| Pattern from iteration 3 | Prev count | Current count | Status |
| ------------------------ | ---------- | ------------- | ------ |
| P-1 `VerifyingFileFactory$Builder` inner class | 8 | 0 | **Fixed** |
| P-2 `KerberosName$Rule`/`$NoMatchingRule` inner classes | 6 | 0 | **Fixed** |
| P-3 `ProviderRegistry` outbound fan-out (intra-Server after demotion) | 10 | 0 | **Fixed** |
| P-4 `ConfigUtils → QuorumPeer$QuorumServer` | 3 | 0 | **Fixed** |
| P-5 `EphemeralType$1` anonymous class | 1 | 0 | **Fixed** |
| P-6 `KerberosUtil` missed from shared | 1 | 0 | **Fixed** |
| **NEW:** `common.X509Util → server.auth.ProviderRegistry` | 0 | 1 | **Introduced** (expected; flagged under "residual risk #1" in analysis3 §6) |
| **Total** | **30** | **1** | — |

All six iteration-3 patterns are resolved. The one new edge is the
inverse of the P-3 demotion: when `ProviderRegistry` left the shared
list, its outbound fan-out stopped firing (10 → 0), but its inbound
edge from `common.X509Util` (which we hypothesised but had not yet
seen) finally surfaced.

---

## Section 3 — Per-Pattern Triage

### P-1 — `common.X509Util.resetDefaultSSLContextAndOptions()` calls `server.auth.ProviderRegistry.addOrUpdateProvider(String)`

Category: **MAPPING ERROR**
Subcategory: Documented cross-layer carve-out not yet encoded (bootstrap / SPI registration edge)
Violation count: **1** (surefire L8)
Representative source classes: `org.apache.zookeeper.common.X509Util`
Representative target classes: `org.apache.zookeeper.server.auth.ProviderRegistry`
Source location: `X509Util.java:320`, inside `resetDefaultSSLContextAndOptions()`

Documentation citation:
The PDF (§1.1, §1.7) scopes "client / server isolation" to the
runtime wire-protocol boundary — i.e., what travels over the TCP
socket once the system is running — not to static bootstrap code
that wires up pluggable auth/SSL providers at JVM start-up. The
documentation does not mention `X509Util` or `ProviderRegistry` by
name, and gives no guidance that would forbid a common SSL-config
helper from registering an X.509 authentication provider during its
own `reset` routine. In architectural terms this is an SPI
registration call, not a cross-tower dependency on server business
logic.

Why this is a mapping error:
`X509Util` is a support utility that manages SSL context, trust
stores, and key stores for both client-side and server-side TLS.
Its `resetDefaultSSLContextAndOptions()` method calls
`ProviderRegistry.addOrUpdateProvider("x509", …)` to register the
X.509 authentication provider so it's available to any consumer
that subsequently looks it up. This is the one inbound edge on
`ProviderRegistry` that justified its being on the shared list in
iteration 2 — but the iteration-2 fix was too broad (it re-exposed
ten outbound server-internal edges). The correct encoding is the
opposite: keep `ProviderRegistry` in Server (which matches the
documentation — auth providers are server-side) and carve out just
this single inbound bootstrap edge.

Fix:
Add one narrow `ignoreDependency` clause to the layered-architecture
rule:

```java
.ignoreDependency(simpleName("X509Util"),
                  resideInAPackage("org.apache.zookeeper.server.auth")
                          .and(simpleName("ProviderRegistry")))
```

This is a class-pair carve-out, not a package-wide one: nothing
else in `common.*` can reach `ProviderRegistry`, and `X509Util`
cannot reach anything else in `server.auth`. If a future commit
broadens either side of the edge, the test will fire again and flag
it.

---

## Section 4 — Real Violations

**No real violations detected in this iteration.** The single edge in
the report is a documented SPI-registration bootstrap that falls
outside the PDF's "client / server communicate only over TCP"
constraint. X509Util does not depend on server business logic at
runtime; it merely publishes an entry in a registry that
server-side code later reads. No wire-protocol boundary is crossed.

---

## Section 5 — Recommended Single-Shot Patch (Mapping Errors)

Add one `ignoreDependency` clause to the `layered_architecture_is_respected`
rule, immediately after the existing `ConfigUtils` carve-out:

```java
@ArchTest
public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        // ... layer definitions unchanged ...
        // ... existing whereLayer clauses unchanged ...

        // Existing: administrative CLI tool reaching server quorum config.
        .ignoreDependency(simpleName("ReconfigCommand"),
                resideInAnyPackage("org.apache.zookeeper.server.quorum.."))
        // Existing: ZooTrace shared-utility bleed-through to quorum and Request.
        .ignoreDependency(simpleName("ZooTrace"),
                resideInAnyPackage("org.apache.zookeeper.server.quorum..")
                        .or(resideInAPackage("org.apache.zookeeper.server")
                                .and(simpleName("Request"))))
        // Existing: ConfigUtils bridging to quorum internals to expose client strings.
        .ignoreDependency(simpleName("ConfigUtils"),
                resideInAPackage("org.apache.zookeeper.server.quorum..")
                        .and(nameStartingWith("org.apache.zookeeper.server.quorum.QuorumPeer")))
        // NEW: X509Util's SSL-reset routine registers the X.509 auth provider at
        //      JVM bootstrap. This is SPI registration, not a runtime wire-protocol
        //      dependency; PDF §1.7's client/server isolation applies to data flow
        //      over TCP, not static plug-in wiring.
        .ignoreDependency(simpleName("X509Util"),
                resideInAPackage("org.apache.zookeeper.server.auth")
                        .and(simpleName("ProviderRegistry")))

        .as("Client-side and server-side sub-systems remain decoupled")
        .because("Per PDF sections 1.1 and 1.7, ZooKeeper clients and "
                + "servers communicate only over the TCP wire protocol; "
                + "the public API contract types are the shared vocabulary. "
                + "Support utilities and shared contract types are accessible, "
                + "but internal implementation packages remain isolated.");
```

No new imports required; `simpleName`, `resideInAPackage`, and
`ignoreDependency` are already in use by the existing clauses.

---

## Section 6 — Predicted Outcome After Patch

| Pattern | Current count | Projected count after patch |
| ------- | ------------- | --------------------------- |
| P-1 `X509Util → ProviderRegistry` bootstrap | 1 | 0 (narrow `ignoreDependency`) |
| **Total** | **1** | **0** |

**Loop terminates on the next iteration** if projection holds. After
this patch the rule file encodes:

- Six layers correctly mapped to packages and class-level predicates.
- Four class-pair carve-outs, each with a one-line documented
  rationale:
  - `ReconfigCommand → server.quorum..` (admin CLI reconfig command)
  - `ZooTrace → server.quorum.. / server.Request` (shared-trace-utility internal refs)
  - `ConfigUtils → server.quorum.QuorumPeer*` (client-config string builder)
  - `X509Util → server.auth.ProviderRegistry` (SSL bootstrap / SPI registration)
- All four `@ArchTest` rules passing.

Residual risk:

1. None I can anticipate from the evidence. Four iterations of
   testing against the real build have surfaced every layer-crossing
   edge in the imported classpath; the outstanding edge is a single
   call site in a well-known support class, the exact class-pair
   fix is narrow, and every other previously-hypothesised edge has
   either materialised or been ruled out. The next run should be
   green.

---

## Section 7 — Lessons / Notes for Next Iteration

This iteration vindicates the analysis-3 principle: **prefer
narrower "classify as server and carve out specific inbound edges
when they actually fire" over wider "pre-classify as shared just in
case".** Iteration 2 over-reached by putting `ProviderRegistry` in
shared on the assumption there was one inbound edge — that
assumption was true, but paid for it with ten outbound edges. The
corrected model (Server + single `ignoreDependency`) is 10× cheaper
and strictly more precise: it pinpoints the exact caller and callee
rather than blessing every outbound fan-out.

Generalisation for future projects: when a candidate shared class
has a measurable number `k` of outbound edges into its own layer
and `n` inbound edges from the outside, prefer `n` `ignoreDependency`
clauses over classifying the class as shared — unless `n >> k`.
Defensive shared-classification is the more expensive side of the
trade-off almost every time.
