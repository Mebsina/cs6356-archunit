# Adversarial Review #4 — `outputs/zookeeper/gemini3-flash/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: gemini3-flash
Project: Apache ZooKeeper
Round: 4 (post round-3 revision)

---

### Executive Summary

The file now applies the round-3 consolidated patch essentially verbatim,
including the new `PublicApi` layer, the bidirectional `PublicApi ↔ Client`
trust edges, and a defensive `test_package_must_not_ship_in_production` rule.
Nearly every prior finding is resolved.

Three issues remain. Two of them are bugs I seeded in the round-3 patch that
the author trusted verbatim — I apologise for the churn.

| Prior Finding | Status |
|---------------|--------|
| R1-F01 / R2-N01 / R3-F01 Root package / Client split / Server-to-root | **Fixed** |
| R1-F02 Fabricated Compatibility isolation | **Fixed** |
| R1-F03 Javadoc header inverted | **Fixed** |
| R1-F04 Vacuous server→recipes | **Fixed** |
| R1-F05 Post-F01 FP on shared exceptions | **Fixed** |
| R1-F06 `graph` misclassification | **Not fixed** (standing author judgement call) |
| R1-F07 Client-side footprint | **Fixed** |
| R1-F08 Jute classpath / misleading comment | **Fixed** |
| R1-F09 Redundant `noClasses` | **Partially fixed** — two remain, see F-R4-04 |
| R1-F10 Generic `.because()` | **Fixed** |
| R2-N02 Layered vs. fine-grained contradiction | **Fixed** (intentionally asymmetric; see F-R4-03 for a simplification) |
| R2-N03 Recipes barred from admin | **Fixed** |
| R2-N04 Redundant CLI rule | **Fixed** |
| R2-N05 Dead Jute classpath entry | **Fixed** |
| R3-F01 Server → root blocked | **Fixed** |
| R3-F02 Documentation rule | **Fixed** (kept; now carries real enforcement weight — see F-R4-03) |
| R3-F03 Missing `test..` Javadoc rationale | **Fixed** |

**Remaining findings:**

- **F-R4-01 (HIGH)** — `Support → PublicApi` is forbidden by the layered rule
  but is a real, documented edge: `org.apache.zookeeper.compatibility.ZooKeeperVersion`
  uses `org.apache.zookeeper.Version`; `org.apache.zookeeper.audit.AuditHelper`
  uses `org.apache.zookeeper.Op` and `KeeperException`. Running against real
  ZK will emit these as violations.
- **F-R4-02 (MEDIUM)** — `test_package_must_not_ship_in_production` is
  vacuous: the condition `haveSimpleNameNotEndingWith("")` is never satisfied
  (every string ends with the empty string), so the rule cannot fire even if
  test classes are on the main classpath. My round-3 patch proposed this
  idiom and it does not work.
- **F-R4-03 (LOW)** — `Server.mayOnlyBeAccessedByLayers("PublicApi")` +
  `public_api_must_not_leak_server_types` is more subtle than necessary.
  `Server.mayNotBeAccessedByAnyLayer()` achieves the same effective behaviour
  with one rule instead of two.
- **F-R4-04 (LOW)** — `recipes_only_depend_on_client_or_support` is now fully
  subsumed by the layered rule. Acceptable as documentation, but noted.

**Overall verdict:** `FAIL` — because of F-R4-01 only. All other issues are
cleanup or cosmetic. Fixing F-R4-01 (a one-line change) should flip this to
`PASS WITH WARNINGS`.

---

### Findings

```
[F-R4-01] SEVERITY: HIGH
Category: Overly Narrow (false-positive generator)
Affected Rule / Constraint: layered_architecture_is_respected —
                            .whereLayer("PublicApi").mayOnlyBeAccessedByLayers(
                                "Client", "Server", "Recipes", "Tools")

What is wrong:
The Support layer (common, util, metrics, jmx, audit, compat, compatibility)
is NOT in `PublicApi`'s access list, so every edge from a Support package to
a root class is a violation. But real Support-layer code depends on root
types by design:

  package org.apache.zookeeper.compatibility;
  import org.apache.zookeeper.Version;                 // root
  public class ZooKeeperVersion { ... }

  package org.apache.zookeeper.audit;
  import org.apache.zookeeper.KeeperException;         // root
  import org.apache.zookeeper.Op;                      // root
  public final class AuditHelper { ... }

  package org.apache.zookeeper.common;
  import org.apache.zookeeper.Environment;             // root
  public class X509Util { ... }                         // (or similar)

These are legitimate edges: `compatibility.ZooKeeperVersion` is literally
a version-reporting wrapper around `org.apache.zookeeper.Version`, and
`audit.AuditHelper` needs `Op` / `KeeperException` to describe audited
operations. Forbidding them produces false positives on unchanged,
shipped-for-years code.

Why it matters:
This is the last remaining blocker that will prevent the test from running
green against zookeeper-server/target/classes. Until it is fixed, CI will
emit several dozen `Layer Violation` entries whose only remedy is either
an `ignoresDependency()` escape hatch (bad) or loosening the rule (the
right answer).

How to fix it:
Add "Support" to PublicApi's access list:

```java
.whereLayer("PublicApi").mayOnlyBeAccessedByLayers(
        "Support",                     // audit/compatibility/common touch root types
        "Client", "Server",
        "Recipes", "Tools")
```

Rationale for the `.because()` update (optional):

```java
.because("... The public API contract types (Version, KeeperException, "
       + "Watcher, Op, CreateMode, ZooDefs) are the shared vocabulary "
       + "that every other layer — including support utilities that "
       + "report version, audit operations, or describe error codes — "
       + "must be free to reference. Only Server implementation packages "
       + "must not leak upward into the public API.");
```

No other layer's constraints need to change; Support's own
`mayNotBeAccessedByAnyLayer` is (deliberately) unspecified, so every layer
may continue to depend on Support unchanged.
```

```
[F-R4-02] SEVERITY: MEDIUM
Category: Vacuous Rule
Affected Rule / Constraint: test_package_must_not_ship_in_production

What is wrong:
The rule reads:

    noClasses()
        .that().resideInAPackage("org.apache.zookeeper.test..")
        .should().haveSimpleNameNotEndingWith("")     // "should not exist"

The condition `haveSimpleNameNotEndingWith("")` is never satisfied by any
class, because every Java simple name trivially ends with the empty string.
So for a `noClasses().that(A).should(B)` assertion to fail, some class must
satisfy BOTH A AND B. Since B is unsatisfiable, no class ever does, so the
rule is structurally impossible to violate even if `test..` classes land
on the main classpath.

Why it matters:
The author took the round-3 patch in good faith. The rule reads as
documentation that CI will fail if test fixtures leak into production, but
it will do no such thing. Worse than no rule: gives a false sense of
coverage.

How to fix it:
Replace with the idiomatic "should not reside in package X" form, which
ArchUnit supports directly:

```java
@ArchTest
public static final ArchRule test_package_must_not_ship_in_production =
    noClasses()
        .should().resideInAPackage("org.apache.zookeeper.test..")
        .because("org.apache.zookeeper.test.* is test-scope fixtures and "
               + "benchmarks. ImportOption.DoNotIncludeTests excludes them "
               + "when compiled under src/test/java, so the importer "
               + "should never see them. Any class found here on the main "
               + "classpath indicates a broken build or a fixture "
               + "accidentally promoted to production.");
```

This version fails with a clear message if any class in the imported set
resides in `org.apache.zookeeper.test..`. It is also cheaper to evaluate
(no per-class predicate evaluation beyond the package check).
```

```
[F-R4-03] SEVERITY: LOW
Category: Rule Simplification
Affected Rule / Constraint: layered_architecture_is_respected —
                            .whereLayer("Server").mayOnlyBeAccessedByLayers("PublicApi")
                            combined with
                            public_api_must_not_leak_server_types

What is wrong:
The current design encodes "nobody may access Server" in two steps:
  1. layered rule says only PublicApi may access Server.
  2. fine-grained rule forbids PublicApi → Server specifically.
Net effect: zero layers may access Server — which is exactly what
`Server.mayNotBeAccessedByAnyLayer()` would say directly. The two-step
phrasing survives from round 3 (where I mistakenly wrote it that way),
and asks a reader of the layered rule alone to internalise the PublicApi
exception before looking elsewhere.

Why it matters:
Not incorrect — both rules fire identically — but cleaner is better.
`Server.mayNotBeAccessedByAnyLayer()` also preserves Server's ability to
reach OUTWARDS (Server → PublicApi, Server → Support); outbound edges are
governed by the target layer's access list, not by the source layer's
"mayNotBeAccessedByAnyLayer()".

How to fix it:
Simplify the layered rule and demote the fine-grained rule to
documentation-only:

```java
.whereLayer("Server").mayNotBeAccessedByAnyLayer()
```

And, in the comment above `public_api_must_not_leak_server_types`,
replace "more specific .because() than the layered rule" with
"documentation-only restatement of the layered rule with a
contract-specific rationale; kept so that a PublicApi → Server violation
surfaces with the specific public-API rationale rather than a generic
'Server may not be accessed' message."

If the author prefers a single source of truth, delete
`public_api_must_not_leak_server_types` entirely once the layered rule is
simplified — both rules would then say the same thing.
```

```
[F-R4-04] SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: recipes_only_depend_on_client_or_support

What is wrong:
After the round-2/3 changes, the layered rule already forbids:
  - recipes → server   (`Server.mayOnlyBeAccessedByLayers("PublicApi")`)
  - recipes → cli/inspector/graph   (`Tools.mayNotBeAccessedByAnyLayer()`)
which is exactly the set that `recipes_only_depend_on_client_or_support`
forbids. The fine-grained rule adds no enforcement.

Why it matters:
Not wrong, but if someone later changes the layered rule (e.g. to carve
out a `graph → server` exception per F-R1-06), they must remember to
update this rule too. One source of truth is easier to maintain.

How to fix it:
Either delete `recipes_only_depend_on_client_or_support`, or repurpose it
to document a constraint the layered rule does NOT already enforce. One
useful repurpose: forbid recipes → other-recipe-subpackage coupling
(i.e. `recipes.lock` should not depend on `recipes.queue`):

```java
@ArchTest
public static final ArchRule recipe_modules_are_independent =
    slices().matching("org.apache.zookeeper.recipes.(*)..")
            .should().notDependOnEachOther()
            .because("Each recipe (lock, queue, leader) is an independent "
                   + "coordination primitive; they must not compile-depend "
                   + "on one another.");
```

(requires `import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;`)

This expresses a real documented constraint (recipes are independent
modules shipped as separate Maven artifacts) and is not already covered
by the layered rule.
```

---

### Summary

- One-line fix to close F-R4-01: add `"Support"` to `PublicApi`'s access
  list. This alone is the difference between a red and green CI run.
- One-method rewrite for F-R4-02: use
  `noClasses().should().resideInAPackage("...test..")` instead of the
  never-true `haveSimpleNameNotEndingWith("")` idiom.
- Everything else is cleanup that does not affect correctness today.

Once F-R4-01 is applied, this file is ready to run against the real
zookeeper build. A subsequent round would be cosmetic only.
