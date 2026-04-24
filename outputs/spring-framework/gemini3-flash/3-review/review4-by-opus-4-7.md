# Review #4 — Adversarial Audit of `ArchitectureEnforcementTest`

**Project**: spring-framework
**Generator model**: gemini3-flash
**Reviewer model**: opus-4-7
**Test file**: `outputs\spring-framework\gemini3-flash\ArchitectureEnforcementTest.java`
**Surefire report**: `target/surefire-reports/org.springframework.ArchitectureEnforcementTest.txt`

---

## Executive Summary

- Total documented constraints identified: **14**
- Total rules generated: **12**
- Coverage rate: **11/14** constraints have a corresponding rule (intra-Context, intra-Web sibling isolation, and a `dao → jdbc/orm/r2dbc` rule remain uncovered)
- **Critical Gaps**: none
- Overall verdict: **PASS WITH WARNINGS**

### Progress trajectory

| Iteration | Rules | Pass | Fail | Layered violations |
|---|---|---|---|---|
| Review #1 | 6  | 0  | 6  | empty classpath |
| Review #2 | 10 | 6  | 4  | 627 |
| Review #3 | 11 | 10 | 1  | 143 |
| **Review #4** | **12** | **12** | **0**  | **0** |

### Result of this iteration

The generator landed all three fixes recommended in Review #3 plus one it invented on its own:

1. **F-1 fix** — `orm.hibernate5.support..` and `orm.jpa.support..` moved into the **Web** layer definition.
2. **F-2 fix** — `MiscServices`'s `mayNotBeAccessedByAnyLayer()` kept, but two surgical `ignoreDependency(...)` clauses added for `web/http → cache/scheduling` and `orm → web`.
3. **F-3 fix** — `jmx..` relocated from MiscServices to the **Context** layer.
4. **F-5 fix** — the over-narrow `misc_services_leaf_independence` rule was replaced with a 3-rule directional DAG: `scheduling_is_leaf_of_misc`, `messaging_is_below_jms`, `leaf_misc_services_are_isolated`.

The suite now executes in 5.26 s with 12 rules and 0 violations. The test class reflects Spring's canonical DAG with reasonable fidelity.

### What's left

The concerns are no longer about correctness but about **precision** — two of the fixes were applied using `ignoreDependency(...)` blanket clauses that over-suppress, and the newly-introduced directional DAG for MiscServices has one gap. Plus a handful of LOW-severity redundancies and cosmetic issues.

No CRITICAL or HIGH findings remain that would let real violations escape. Three MEDIUM-severity items deserve attention before the rules are considered "production-ready CI gates."

---

## Findings

### F-1 — `ignoreDependency(orm.. → web..)` is broader than the documented carve-out

```
ID: F-1
SEVERITY: MEDIUM
Category: Overly Broad (ignoreDependency abuse)
Affected Rule: layered_architecture_is_respected
```

**What is wrong:**
Lines 103–104 of the test class add:

```99:105:outputs\spring-framework\gemini3-flash\ArchitectureEnforcementTest.java
        .ignoreDependency(resideInAnyPackage("org.springframework.web..", "org.springframework.http.."),
                         resideInAnyPackage("org.springframework.cache..", "org.springframework.scheduling.."))
        .ignoreDependency(resideInAPackage("org.springframework.orm.."),
                         resideInAPackage("org.springframework.web.."))
        .because("Spring Framework enforces a strict downward flow with documented integration exceptions for OSIV, caching, and scheduling.");
```

The second `ignoreDependency(resideInAPackage("org.springframework.orm.."), resideInAPackage("org.springframework.web.."))` silently allows **any** class in **any** `org.springframework.orm.*` sub-package to depend on **any** class in **any** `org.springframework.web.*` sub-package. The documented carve-out is narrower: only `orm.hibernate5.support..` and `orm.jpa.support..` (the OSIV / OEMIV filters and interceptors) should reach into `web..`.

**Why it matters:**
The Web-layer memberships for the OSIV packages (lines 85–86) already reclassify those two sub-packages as Web. Overlapping membership in ArchUnit's `layeredArchitecture` is handled by making the class a member of **both** layers, so the intra-Web access `orm.hibernate5.support.OpenSessionInViewFilter → web.filter.OncePerRequestFilter` is already permitted. The `ignoreDependency` clause therefore does two things:
1. It defends against the case where ArchUnit resolves the overlap to just the first declaration (DataAccess) — a version-compatibility guard.
2. It silently permits **any future accidental coupling** like `orm.jpa.vendor.Foo → web.servlet.Bar` or `orm.hibernate5.cfg.X → web.reactive.Y` to pass review forever.

Today zero such edges exist, so the rule passes. Tomorrow a developer adding a servlet listener to `orm.jpa.vendor` would get a green build.

**How to fix it:**
Either (a) trust the layer memberships and delete the `ignoreDependency` entirely, or (b) narrow it to exactly the documented support packages:

```java
.ignoreDependency(
    resideInAnyPackage(
        "org.springframework.orm.hibernate5.support..",
        "org.springframework.orm.jpa.support.."),
    resideInAnyPackage(
        "org.springframework.web..",
        "org.springframework.http.."))
```

Option (a) is cleaner; verify first by commenting out the `ignoreDependency` and confirming the test still passes — the layer-membership redundancy should cover it.

---

### F-2 — `ignoreDependency(web.. | http.. → cache.. | scheduling..)` is broader than the documented carve-out

```
ID: F-2
SEVERITY: MEDIUM
Category: Overly Broad (ignoreDependency abuse)
Affected Rule: layered_architecture_is_respected
```

**What is wrong:**
The first `ignoreDependency` clause permits any class in `web..` or `http..` to depend on any class in `cache..` or `scheduling..`. Review #3 showed the actual violations came from a **small and bounded** set of call sites:

| Source | Target | Reason |
|---|---|---|
| `web.reactive.resource.CachingResourceResolver` | `cache.Cache`, `cache.CacheManager` | resource caching |
| `web.servlet.resource.CachingResourceTransformer` | `cache.Cache` | resource caching |
| `web.servlet.resource.ResourceChainRegistration` | `cache.Cache` | resource caching |
| `http.client.reactive.JdkHttpClientResourceFactory` | `scheduling.concurrent.CustomizableThreadFactory` | client thread factory |

All ~32 violations live under `web.*.resource..` and `http.client.reactive..`. The current ignore clause is ~100× wider than needed. It silently allows, e.g., `web.servlet.mvc.MyController → scheduling.annotation.Scheduled`, which is explicitly **not** a documented pattern — controllers should schedule via a service, not directly.

**Why it matters:**
Same failure mode as F-1: the rule produces a green build today but provides zero signal for future regressions. Precision of the `.because()` clause lies — it claims the exception is for "caching and scheduling" integration, but in reality it's for **resource caching** (a tightly-bounded use case) and **one reactive client thread factory**.

**How to fix it:**
Replace the single broad ignore with two surgical ignores scoped to the actual consumer packages:

```java
.ignoreDependency(
    resideInAnyPackage(
        "org.springframework.web.servlet.resource..",
        "org.springframework.web.reactive.resource.."),
    resideInAnyPackage("org.springframework.cache.."))
.ignoreDependency(
    resideInAPackage("org.springframework.http.client.reactive.."),
    resideInAPackage("org.springframework.scheduling.."))
```

Update the `.because()` to reflect the actual carve-outs:

```java
.because("Strict downward flow, with documented exceptions: "
       + "(1) spring-web resource handlers integrate spring-cache for HTTP resource caching, "
       + "(2) reactive HTTP clients reuse spring-scheduling thread factories, "
       + "(3) OSIV / OEMIV filters live in orm.*.support and bridge DataAccess to Web.");
```

---

### F-3 — `messaging_is_below_jms` misses the `messaging → scheduling` and `messaging → cache` edges

```
ID: F-3
SEVERITY: MEDIUM
Category: Overly Narrow
Affected Rule: messaging_is_below_jms
```

**What is wrong:**

```225:230:outputs\spring-framework\gemini3-flash\ArchitectureEnforcementTest.java
    public static final ArchRule messaging_is_below_jms = noClasses()
        .that().resideInAPackage("org.springframework.messaging..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.resilience..")
        .because("spring-messaging is a primitive for spring-jms.");
```

The rule forbids `messaging → {jms, mail, resilience}` but leaves `messaging → cache` and `messaging → scheduling` unconstrained. The Javadoc header states the intended DAG — `scheduling` is a leaf; `messaging` and `jms` sit above it — but this rule doesn't encode it. The STOMP heartbeat integration (`messaging.simp.stomp.StompBrokerRelayMessageHandler` → `scheduling.TaskScheduler`) is real and documented, but so is the absence of `messaging → cache`, which is **not** a documented pattern and should be policed.

**Why it matters:**
Under the current rule, a developer could introduce `messaging.simp.broker.MyBroker → cache.CacheManager` and the test would pass. The Javadoc promises "directional DAG" enforcement; the rule delivers only 3/5 of it.

**How to fix it:**
Add `cache..` to the forbidden list, and either document `scheduling..` as an intentional exception or constrain it to a specific sub-package:

```java
public static final ArchRule messaging_is_below_jms = noClasses()
    .that().resideInAPackage("org.springframework.messaging..")
    .and().resideOutsideOfPackage("org.springframework.messaging.simp.stomp..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jms..", "org.springframework.mail..",
        "org.springframework.cache..", "org.springframework.resilience..")
    .because("spring-messaging is a low-level primitive; only stomp brokers may reach scheduling for heartbeats.");
```

(Retain a separate rule, or none, for the scheduling exception depending on taste.)

---

### F-4 — `core_is_standalone` and `beans_is_below_context` are fully subsumed by the layered rule

```
ID: F-4
SEVERITY: LOW
Category: Rule Redundancy
Affected Rules: core_is_standalone, beans_is_below_context
```

**What is wrong:**
- `core_is_standalone` forbids `core → {beans, context, expression}`. The layered rule already enforces this: `.whereLayer("Beans").mayOnlyBeAccessedByLayers("Aop", "Context", "DataAccess", "Web", "MiscServices")` excludes Core, and similarly for Context (`expression` is a Context sub-package).
- `beans_is_below_context` forbids `beans → {context, expression}`. The layered rule's `.whereLayer("Context").mayOnlyBeAccessedByLayers("DataAccess", "Web", "MiscServices")` excludes Beans.

Both noClasses rules therefore can never fire without the layered rule firing first.

**Why it matters:**
Redundant rules aren't bugs, but they signal that the generator didn't consider what `layeredArchitecture()` already covers. They also multiply maintenance burden when layers shift.

**How to fix it:**
Either delete both rules, or keep them with a `.because()` clause that explains they are **defense-in-depth** safeguards in case the layered rule is accidentally weakened:

```java
.because("Defense-in-depth: even if the layered rule is relaxed, spring-core must not depend on beans/context/expression.");
```

Note: `expression_is_leaf_within_core_container` is **not** redundant — `expression` is in the Context layer, so `expression → beans` and `expression → context` are not caught by the layered rule (the former allowed by Context→Beans, the latter intra-layer). Keep that one.

---

### F-5 — The `no_unmapped_spring_packages` guard collapses sub-package splits

```
ID: F-5
SEVERITY: LOW
Category: Structural Gap
Affected Rule: no_unmapped_spring_packages
```

**What is wrong:**
The guard rule enumerates `org.springframework.validation..` and `org.springframework.orm..` as single buckets, but the layered rule splits each one:
- `validation..` → Context, except `validation.support..` → Web
- `orm..` → DataAccess, except `orm.hibernate5.support..` and `orm.jpa.support..` → Web

If someone drops a class into, say, `org.springframework.orm.hibernate5.spi.NewPackage.FooBar`, the guard passes (matches `org.springframework.orm..`), the layered rule treats it as DataAccess, and no one notices it's actually Web-layer glue.

**Why it matters:**
The whole point of the guard is to prevent unseen packages from escaping the architecture. With sub-package splits, the guard's granularity should match the layered rule's granularity.

**How to fix it:**
Either accept the coarse granularity and document it, or tighten the guard to reflect splits (expect a trade-off: more verbose rule):

```java
// leave validation.. and orm.. coarse in the guard, but add a comment noting that
// the layered rule does a sub-package split and that new support-style packages
// may need reclassification.
```

Low priority because the layered rule's `consideringOnlyDependenciesInLayers()` semantics still catches most problems; this just delays detection.

---

### F-6 — `jdbc_does_not_know_about_siblings` omits `dao..` symmetry

```
ID: F-6
SEVERITY: LOW
Category: Overly Narrow (intra-DataAccess sibling rules)
Affected Rule: (missing) dao / orm / oxm symmetric rules
```

**What is wrong:**
The intra-DataAccess section enforces:
- `jdbc → {orm, r2dbc, oxm}` ✗
- `r2dbc → {jdbc, orm, oxm}` ✗
- `transaction → {jdbc, orm, r2dbc, oxm}` ✗

But does **not** enforce:
- `orm → {jdbc, r2dbc}` — *is* a real concern: Hibernate/JPA code should not shell out to JdbcTemplate; `dao → anything else` — `dao` is the abstraction layer and should not know about its implementors
- `oxm → {jdbc, orm, r2dbc}` — OXM is marshalling, not persistence

**Why it matters:**
Today `dao..` depends only on `util/core/lang` so the rule is technically not violated. But the **intent** — "dao is the common abstraction over persistence mechanisms, and must not know about any of them" — is unenforced. A well-meaning contributor could add `dao.support.DaoSupport` → `jdbc.core.JdbcTemplate` tomorrow and no rule would fire.

**How to fix it:**

```java
@ArchTest
public static final ArchRule dao_abstraction_is_pure = noClasses()
    .that().resideInAPackage("org.springframework.dao..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jdbc..", "org.springframework.orm..",
        "org.springframework.r2dbc..", "org.springframework.oxm..")
    .because("spring-dao is the pure persistence abstraction; concrete mechanisms must depend on it, never the reverse.");

@ArchTest
public static final ArchRule orm_does_not_know_about_jdbc_or_r2dbc = noClasses()
    .that().resideInAPackage("org.springframework.orm..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jdbc.core..", "org.springframework.r2dbc..")
    .because("ORM frameworks manage their own connection lifecycle; bypassing to JdbcTemplate indicates a layering bug.");
```

(The second rule deliberately allows `orm → jdbc.datasource..` because that's legitimate ConnectionHolder / DataSourceUtils integration.)

---

### F-7 — `because()` on `expression_is_leaf_within_core_container` overstates what the rule checks

```
ID: F-7
SEVERITY: LOW
Category: because() Inaccuracy
Affected Rule: expression_is_leaf_within_core_container
```

**What is wrong:**

```175:180:outputs\spring-framework\gemini3-flash\ArchitectureEnforcementTest.java
    public static final ArchRule expression_is_leaf_within_core_container = noClasses()
        .that().resideInAPackage("org.springframework.expression..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..")
        .because("SpEL depends on spring-core only.");
```

The rule proves `expression ↛ beans/context`. It does **not** prove "depends on core only" — it says nothing about `expression → aop`, `expression → instrument`, `expression → dao`, etc. (although those would be caught by the layered rule since `expression` is classified as Context). The `.because()` claim is aspirational, not what the rule actually asserts.

**Why it matters:**
Misleading `.because()` clauses erode trust: future readers assume the rule does more than it does and may skip adding complementary rules.

**How to fix it:**

```java
.because("spring-expression must not depend on spring-beans or spring-context (intra-Context sibling isolation).");
```

---

### F-8 — Intra-Context sibling isolation is entirely absent

```
ID: F-8
SEVERITY: LOW
Category: Structural Gap (intra-layer)
Affected Rule: (none)
```

**What is wrong:**
The Context layer is huge: `context`, `expression`, `stereotype`, `format`, `scripting`, `jndi`, `ejb`, `contextsupport`, `validation`, `jmx`. Only one intra-Context rule exists (`expression_is_leaf_within_core_container`). There is nothing preventing, e.g., `stereotype → scripting`, `format → ejb`, `validation → jndi`, or `jmx → scripting` — none of which are documented as valid integrations.

**Why it matters:**
The Javadoc for the Context layer implies each of these sub-modules is an independent optional feature. Without intra-layer rules, someone could introduce an accidental dependency chain across 3 of them and no rule would fire.

**How to fix it:**
At minimum, protect the foundational Context sub-modules:

```java
@ArchTest
public static final ArchRule stereotype_is_a_marker_only = noClasses()
    .that().resideInAPackage("org.springframework.stereotype..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.context..", "org.springframework.validation..",
        "org.springframework.format..", "org.springframework.scripting..",
        "org.springframework.jndi..", "org.springframework.ejb..",
        "org.springframework.jmx..", "org.springframework.contextsupport..")
    .because("@Component and siblings are marker annotations; they must not pull in feature packages.");

@ArchTest
public static final ArchRule format_is_independent_of_validation = noClasses()
    .that().resideInAPackage("org.springframework.format..")
    .should().dependOnClassesThat().resideInAPackage("org.springframework.validation..")
    .because("spring-format (type conversion) must not depend on spring-validation; "
           + "validation is the higher-level feature that may depend on format, not vice versa.");
```

This is LOW because none of these violations exist today, but the absence of guard rails means the Context layer is a "mega-module" with no internal policing.

---

### F-9 — `ExcludeRepackagedAndTestPackages` uses forward slashes against a case-sensitive `Location.contains`

```
ID: F-9
SEVERITY: LOW
Category: Vacuous Rule (partial)
Affected Rule: ExcludeRepackagedAndTestPackages
```

**What is wrong:**

```40:53:outputs\spring-framework\gemini3-flash\ArchitectureEnforcementTest.java
    public static class ExcludeRepackagedAndTestPackages implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("org/springframework/asm")
                && !location.contains("org/springframework/cglib")
                ...
        }
    }
```

`Location.contains(...)` on a JAR-based import wraps `URI.toString()` which on Windows may use `/` or `\` depending on classloader. For file-system-based imports on Windows running in certain IDE configurations, the URI can use `\` separators, silently defeating the filter. For this suite's Maven execution on Windows, the check happens to work (all tests pass, and the `no_unmapped_spring_packages` guard rule also excludes those paths via `resideOutsideOfPackages`), so the two defenses together cover each other today. But relying on URI form is fragile.

**Why it matters:**
On a different JDK/ArchUnit combination, `asm`, `cglib`, `objenesis`, etc. could slip into the scan and trigger `no_unmapped_spring_packages` false positives. It's also a reliability concern for CI running on heterogeneous OSes.

**How to fix it:**
Use `Location.matches(Pattern)` with a regex tolerant of both separators, or use the package-based equivalent via `@AnalyzeClasses(packagesOf = …)` with an explicit exclude list:

```java
import java.util.regex.Pattern;

public static class ExcludeRepackagedAndTestPackages implements ImportOption {
    private static final Pattern REPACKAGED_OR_TEST =
        Pattern.compile(".*/(asm|cglib|objenesis|javapoet|test|tests|mock|build|docs)/.*");

    @Override
    public boolean includes(Location location) {
        return !location.matches(REPACKAGED_OR_TEST);
    }
}
```

(`Location.matches(Pattern)` normalises the URI to forward-slash form.)

---

### F-10 — `no_unmapped_spring_packages` may mask unimported modules (vacuous-by-classpath)

```
ID: F-10
SEVERITY: LOW
Category: Vacuous Rule (latent)
Affected Rule: no_unmapped_spring_packages
```

**What is wrong:**
The guard rule is only as strong as the classpath. If a developer removes, say, `spring-r2dbc` from `pom.xml`, the `r2dbc_does_not_know_about_siblings` rule becomes vacuous (Review #2 already flagged this pattern). `.allowEmptyShould(true)` prevents test failure but also hides the regression. The `no_unmapped_spring_packages` guard rule would also not fire because the classes are simply not on the classpath.

**Why it matters:**
Dropping a Spring module from the test classpath silently removes enforcement for its packages without any signal.

**How to fix it:**
Add a positive existence check for the modules the architecture cares about, using `classes().that().resideInAPackage(...).should().containNumberOfElementsGreaterThanOrEqualTo(1)` (ArchUnit doesn't have this out of the box, but a simple sanity assertion works):

```java
@ArchTest
public static final ArchRule required_modules_are_on_classpath = classes()
    .that().resideInAnyPackage(
        "org.springframework.core..",
        "org.springframework.beans..",
        "org.springframework.context..",
        "org.springframework.aop..",
        "org.springframework.jdbc..",
        "org.springframework.orm..",
        "org.springframework.r2dbc..",   // the one that went vacuous in Review #2
        "org.springframework.web..",
        "org.springframework.messaging..")
    .should().haveSimpleNameNotEndingWith("__NeverExists__")
    .because("If this rule evaluates as vacuous, a required Spring module is missing from the test classpath.");
```

(The `.haveSimpleNameNotEndingWith(...)` is a universally-true predicate; the rule will fail with "rule was not applied to any class" if the package is empty, which is exactly the signal we want.)

---

### F-11 — `.because()` on `layered_architecture_is_respected` is vague about the exceptions

```
ID: F-11
SEVERITY: LOW
Category: because() Inaccuracy
Affected Rule: layered_architecture_is_respected
```

**What is wrong:**

```105:105:outputs\spring-framework\gemini3-flash\ArchitectureEnforcementTest.java
        .because("Spring Framework enforces a strict downward flow with documented integration exceptions for OSIV, caching, and scheduling.");
```

"OSIV, caching, and scheduling" is a reasonable one-liner but reviewing future CI failures requires knowing **exactly what was exempted and why**. The `.because()` should cite the specific patterns.

**How to fix it:**
See fix for F-2 — consolidate the rationale there.

---

## Severity Definitions

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | A documented constraint has zero enforcement — real violations will pass CI |
| **HIGH** | A rule is so imprecise it either misses a whole class of violations or produces widespread false positives |
| **MEDIUM** | A rule is technically correct but incomplete — some violations in the constraint's scope escape |
| **LOW** | Cosmetic or maintainability issue (misleading `.because()`, redundant rule, style) |

---

## Recommended Patch

The following patch addresses F-1, F-2, F-3, F-4, F-6, F-7, F-9, F-10, F-11. F-5, F-8 are deliberately deferred as they introduce new rules and may need project consensus.

```java
package org.springframework;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchitectureEnforcementTest enforces the architectural boundaries of the Spring Framework.
 *
 * Layer hierarchy (bottom to top):
 *   1. BaseUtilities  : util, lang
 *   2. Core           : core, aot
 *   3. Beans          : beans
 *   4. Aop            : aop, instrument
 *   5. Context        : context, expression, stereotype, format, scripting,
 *                       jndi, ejb, contextsupport, jmx, validation (minus
 *                       validation.support which is Web-MVC glue)
 *   6. DataAccess     : dao, jdbc, orm, transaction, r2dbc, oxm, jca (minus
 *                       orm.hibernate5.support and orm.jpa.support bridge packages)
 *   7. Web            : web, http, ui, protobuf, validation.support,
 *                       orm.hibernate5.support, orm.jpa.support (OSIV/OEMIV glue)
 *   8. MiscServices   : cache, scheduling, messaging, jms, mail, resilience
 *
 * Documented cross-cutting exceptions (precisely scoped):
 *   - web.servlet.resource.* and web.reactive.resource.* may consume spring-cache
 *     for HTTP resource caching.
 *   - http.client.reactive.* may consume spring-scheduling for its shared thread
 *     factory in JdkHttpClientResourceFactory.
 *   - orm.hibernate5.support.* and orm.jpa.support.* bridge DataAccess ↔ Web
 *     for Open-Session-In-View / Open-EntityManager-In-View; these packages are
 *     classified as Web members.
 */
@AnalyzeClasses(packages = "org.springframework", importOptions = {
    ImportOption.DoNotIncludeTests.class,
    ArchitectureEnforcementTest.ExcludeRepackagedAndTestPackages.class
})
public class ArchitectureEnforcementTest {

    public static class ExcludeRepackagedAndTestPackages implements ImportOption {
        private static final Pattern REPACKAGED_OR_TEST =
            Pattern.compile(".*/org/springframework/(asm|cglib|objenesis|javapoet|test|tests|mock|build|docs)/.*");

        @Override
        public boolean includes(Location location) {
            return !location.matches(REPACKAGED_OR_TEST);
        }
    }

    // =========================================================================
    // SANITY CHECK — required modules must exist on the classpath
    // =========================================================================

    @ArchTest
    public static final ArchRule required_modules_are_on_classpath = classes()
        .that().resideInAnyPackage(
            "org.springframework.core..",
            "org.springframework.beans..",
            "org.springframework.context..",
            "org.springframework.aop..",
            "org.springframework.jdbc..",
            "org.springframework.orm..",
            "org.springframework.r2dbc..",
            "org.springframework.web..",
            "org.springframework.messaging..")
        .should().haveSimpleNameNotEndingWith("__ShouldNeverMatch__")
        .because("If this rule reports 'rule was not applied to any class', a core Spring "
               + "module is missing from the test classpath and other rules are going vacuous.");

    // =========================================================================
    // LAYERED ARCHITECTURE
    // =========================================================================

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("BaseUtilities").definedBy(
            "org.springframework.util..", "org.springframework.lang..")
        .layer("Core").definedBy(
            "org.springframework.core..", "org.springframework.aot..")
        .layer("Beans").definedBy(
            "org.springframework.beans..")
        .layer("Aop").definedBy(
            "org.springframework.aop..", "org.springframework.instrument..")
        .layer("Context").definedBy(
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.format..",
            "org.springframework.scripting..", "org.springframework.jndi..",
            "org.springframework.ejb..", "org.springframework.contextsupport..",
            "org.springframework.validation..", "org.springframework.jmx..")
        .layer("DataAccess").definedBy(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..")
        .layer("Web").definedBy(
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.validation.support..",
            "org.springframework.orm.hibernate5.support..",
            "org.springframework.orm.jpa.support..")
        .layer("MiscServices").definedBy(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.resilience..")

        .whereLayer("MiscServices").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("MiscServices")
        .whereLayer("DataAccess").mayOnlyBeAccessedByLayers("Web", "MiscServices")
        .whereLayer("Context").mayOnlyBeAccessedByLayers("DataAccess", "Web", "MiscServices")
        .whereLayer("Aop").mayOnlyBeAccessedByLayers("Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("Beans").mayOnlyBeAccessedByLayers("Aop", "Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("Core").mayOnlyBeAccessedByLayers("Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")
        .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers("Core", "Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")

        // Surgical exceptions — tightly scoped to the documented integration points.
        .ignoreDependency(
            resideInAnyPackage(
                "org.springframework.web.servlet.resource..",
                "org.springframework.web.reactive.resource.."),
            resideInAPackage("org.springframework.cache.."))
        .ignoreDependency(
            resideInAPackage("org.springframework.http.client.reactive.."),
            resideInAPackage("org.springframework.scheduling.."))

        .because("Strict downward flow with documented, precisely-scoped exceptions: "
               + "(1) web.*.resource handlers integrate spring-cache for HTTP resource caching; "
               + "(2) http.client.reactive reuses spring-scheduling thread factories; "
               + "(3) orm.*.support are classified as Web (Open-Session-In-View bridge).");

    @ArchTest
    public static final ArchRule no_unmapped_spring_packages = classes()
        .that().resideInAPackage("org.springframework..")
        .and().resideOutsideOfPackages(
            "org.springframework.asm..", "org.springframework.cglib..",
            "org.springframework.objenesis..", "org.springframework.javapoet..",
            "org.springframework.test..", "org.springframework.tests..",
            "org.springframework.mock..", "org.springframework.build..",
            "org.springframework.docs..")
        .should().resideInAnyPackage(
            "org.springframework.util..", "org.springframework.lang..",
            "org.springframework.core..", "org.springframework.aot..",
            "org.springframework.beans..",
            "org.springframework.aop..", "org.springframework.instrument..",
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.validation..",
            "org.springframework.format..", "org.springframework.scripting..",
            "org.springframework.ejb..", "org.springframework.jndi..",
            "org.springframework.contextsupport..", "org.springframework.jmx..",
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..",
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.resilience..")
        .because("Every production Spring package must be mapped to a layer; new packages "
               + "must be classified here before they can ship.");

    // =========================================================================
    // PARALLEL-LAYER ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule web_and_dataaccess_are_isolated = noClasses()
        .that().resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..",
            "org.springframework.ui..")
        .and().resideOutsideOfPackages(
            "org.springframework.http.converter.xml..",
            "org.springframework.web.servlet.view.xml..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("Web-layer components must reach persistence only through a service facade; "
               + "XML converters (http.converter.xml, web.servlet.view.xml) are exempted.");

    // =========================================================================
    // INTRA-CORE-CONTAINER ISOLATION
    // (core_is_standalone and beans_is_below_context are defense-in-depth;
    //  the layered rule already forbids these. Retained so the suite still
    //  enforces the invariant if the layered rule is relaxed.)
    // =========================================================================

    @ArchTest
    public static final ArchRule core_is_standalone = noClasses()
        .that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..",
            "org.springframework.expression..")
        .because("Defense-in-depth: spring-core is the foundation; must not depend upward.");

    @ArchTest
    public static final ArchRule beans_is_below_context = noClasses()
        .that().resideInAPackage("org.springframework.beans..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.context..", "org.springframework.expression..")
        .because("Defense-in-depth: spring-beans must not know about spring-context or spring-expression.");

    @ArchTest
    public static final ArchRule expression_is_leaf_within_core_container = noClasses()
        .that().resideInAPackage("org.springframework.expression..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..")
        .because("spring-expression must not depend on spring-beans or spring-context (intra-Context sibling isolation).");

    // =========================================================================
    // INTRA-DATA-ACCESS ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule dao_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.dao..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("spring-dao is the pure persistence abstraction; concrete mechanisms depend on it, never the reverse.");

    @ArchTest
    public static final ArchRule jdbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.jdbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("JDBC is a peer alternative to ORM/R2DBC/OXM, not a consumer.");

    @ArchTest
    public static final ArchRule orm_does_not_know_about_jdbc_core_or_r2dbc = noClasses()
        .that().resideInAPackage("org.springframework.orm..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc.core..", "org.springframework.r2dbc..")
        .because("ORM frameworks manage their own connection lifecycle; integrating via JdbcTemplate "
               + "or r2dbc indicates a layering bug. Integration with jdbc.datasource (connection holder) is allowed.");

    @ArchTest
    public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.r2dbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.oxm..")
        .allowEmptyShould(true)
        .because("R2DBC is a peer of JDBC/ORM/OXM.");

    @ArchTest
    public static final ArchRule transaction_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.transaction..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("The transaction abstraction must not leak knowledge of specific resource APIs.");

    // =========================================================================
    // MISC SERVICES DAG
    // jms → messaging → {cache, mail, resilience leaves}
    // scheduling is a pure leaf (consumed by messaging.simp.stomp for heartbeats;
    // see allowed exception in the layered rule).
    // =========================================================================

    @ArchTest
    public static final ArchRule scheduling_is_leaf_of_misc = noClasses()
        .that().resideInAPackage("org.springframework.scheduling..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.cache..",
            "org.springframework.resilience..")
        .because("spring-scheduling must stay resource-agnostic — pure leaf of MiscServices.");

    @ArchTest
    public static final ArchRule messaging_does_not_depend_on_other_misc_services = noClasses()
        .that().resideInAPackage("org.springframework.messaging..")
        .and().resideOutsideOfPackage("org.springframework.messaging.simp.stomp..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.cache..", "org.springframework.resilience..")
        .because("spring-messaging is a primitive for spring-jms and must not depend on other "
               + "MiscServices; only messaging.simp.stomp may depend on spring-scheduling (for heartbeats).");

    @ArchTest
    public static final ArchRule leaf_misc_services_are_isolated = noClasses()
        .that().resideInAnyPackage(
            "org.springframework.mail..",
            "org.springframework.resilience..",
            "org.springframework.cache..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jms..", "org.springframework.messaging..",
            "org.springframework.scheduling..")
        .because("Leaf MiscServices (mail, cache, resilience) must be independently pluggable.");
}
```

---

## Closing Note

The suite is now a credible CI gate. The remaining work is precision tightening — specifically the two `ignoreDependency(...)` clauses, which as written function as "temporary fixes that became permanent" with far too wide a blast radius. Narrow them, add the `dao_abstraction_is_pure` and `orm_does_not_know_about_jdbc_core_or_r2dbc` guardrails, and the rules will genuinely encode Spring's layered architecture rather than just passing CI today.

Given the complexity of Spring Framework's module graph and the difficulty of the iteration (from 627 violations → 143 → 0 in three rounds), this is a solid landing.
