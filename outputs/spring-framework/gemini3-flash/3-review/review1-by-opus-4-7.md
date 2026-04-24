# Adversarial Review of Generated ArchUnit Rules — spring-framework / gemini3-flash

Reviewer: opus-4-7
Review: #1

---

## Executive Summary

- **Total documented constraints identified:** 18 (6 layer-hierarchy constraints + 9 package-placement constraints + 3 intra-layer prohibitions)
- **Total rules generated:** 6 (1 `layeredArchitecture`, 5 `noClasses`)
- **Coverage rate:** 7 / 18 constraints have a corresponding rule
- **Critical Gaps (constraints with zero coverage):**
  - G-1: Nine packages (`ejb`, `format`, `jca`, `jndi`, `oxm`, `protobuf`, `resilience`, `scripting`, `contextsupport`) present in the codebase are not assigned to any layer — they are invisible to the layered architecture rule.
  - G-2: The entire Spring Framework test suite actually ran against an **empty classpath** — every rule reports either "Layer X is empty" or "failed to check any classes". The generated harness enforces nothing today.
  - G-3: `org.springframework.validation` is mapped to BaseUtilities although `spring-validation` depends on beans/context — every legitimate dependency becomes a false positive.
  - G-4: The layered rule declares `CoreContainer` as an allowed consumer of `AopInstrumentation`, which inverts the documented hierarchy (AOP is *above* Core).
  - G-5: Parallel-layer isolation between Web and DataAccess is asserted only against `org.springframework.orm..` — `jdbc`, `transaction`, `r2dbc`, `dao` are all un-policed.
  - G-6: No rule blocks `core → context`, `core → expression`, `expression → beans`, or `context → web` etc.; intra-CoreContainer isolation is only partial.

- **Overall verdict:** **FAIL**

The test suite not only fails to enforce the documented architecture — as currently configured it does not examine a single production class (surefire shows 6/6 failures, all caused by empty layers / empty `should`). Combined with the layer-inversion error on `AopInstrumentation` and the mis-classification of `validation`, the rules would still be wrong even after the classpath is fixed.

---

## Findings

### F-1
```
SEVERITY: CRITICAL
Category: Vacuous Rule / Structural Gap
Affected Rule / Constraint: All 6 rules (entire surefire report)
```

**What is wrong:**
The surefire report shows every rule failed because no classes were evaluated:

- `layered_architecture_is_respected` — "Layer 'AopInstrumentation' is empty … Layer 'Web' is empty" (all 6 layers empty).
- `parallel_layers_web_and_dataaccess_are_independent`, `beans_should_not_depend_on_context`, `core_should_not_depend_on_beans`, `jdbc_should_not_depend_on_orm`, `aop_should_not_depend_on_dataaccess` — each fails with *"Rule … failed to check any classes"*.

`@AnalyzeClasses(packages = "org.springframework")` finds nothing at runtime because the `<additionalClasspathElements>` in `pom.xml` point to `../../../spring-core/build/classes/java/main` etc. — directories that do not exist relative to the review harness.

**Why it matters:**
Every rule is vacuous as-shipped. A PR that introduces an egregious violation (e.g., `org.springframework.core.SpringVersion` depending on `org.springframework.web.servlet.DispatcherServlet`) would still report "no classes matched" and the build would only fail on the empty-layer assertion, which a developer can silence trivially by adding `.allowEmptyShould(true)`. No architectural invariant is enforced today.

**How to fix it:**
Use a resolvable classpath. Either (a) depend on the Spring Framework published jars and scan them, or (b) point `<additionalClasspathElements>` at a known-good, checked-out Spring tree. Minimally, use Maven dependencies so that ArchUnit can load classes from the test classpath:

```java
@AnalyzeClasses(
    packages = "org.springframework",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ArchitectureEnforcementTest.ExcludeRepackagedAndTestPackages.class,
        ImportOption.DoNotIncludeJars.class   // remove once using real deps
    }
)
```

and in `pom.xml` add actual dependencies:

```xml
<dependency>
  <groupId>org.springframework</groupId>
  <artifactId>spring-core</artifactId>
  <version>${spring.version}</version>
</dependency>
<!-- ...beans, context, aop, jdbc, orm, tx, web, webmvc, webflux, messaging, jms, oxm, context-support -->
```

Until classes are actually imported, every other finding below is *theoretical* — but also inevitable the moment the classpath is fixed.

---

### F-2
```
SEVERITY: CRITICAL
Category: Coverage Gap
Affected Rule / Constraint: layered_architecture_is_respected (layer definitions)
```

**What is wrong:**
The package inventory lists the following root packages in the codebase:

```
contextsupport, ejb, format, jca, jndi, oxm, protobuf, resilience, scripting
```

None of them are assigned to any layer in `layeredArchitecture()`. Classes in these packages can therefore depend on anything, and nothing will be flagged if they violate the hierarchy.

**Why it matters:**
Several of these are not obscure:

- `org.springframework.oxm..` (Object/XML marshalling) is an entire module that by spec belongs to the Data Access & Integration layer.
- `org.springframework.format..` (ships in spring-context) belongs to Core Container.
- `org.springframework.jndi..`, `org.springframework.jca..`, `org.springframework.ejb..` are documented integration wrappers that should sit at DataAccess or MiscServices.
- `org.springframework.scripting..` and `org.springframework.resilience..` are part of Core Container / MiscServices.

If a maintainer adds a `spring-oxm` → `spring-webmvc` call tomorrow, the build will not flag it because `oxm` is simply not a layer member.

**How to fix it:**
Assign every package in the codebase inventory to a layer. At minimum:

```java
.layer("BaseUtilities").definedBy("org.springframework.util..", "org.springframework.lang..")
.layer("CoreContainer").definedBy(
    "org.springframework.core..", "org.springframework.beans..",
    "org.springframework.context..", "org.springframework.expression..",
    "org.springframework.stereotype..", "org.springframework.format..",
    "org.springframework.validation..", "org.springframework.ejb..",
    "org.springframework.jndi..", "org.springframework.scripting..",
    "org.springframework.contextsupport..")
.layer("AopInstrumentation").definedBy(
    "org.springframework.aop..", "org.springframework.aot..",
    "org.springframework.instrument..")
.layer("DataAccess").definedBy(
    "org.springframework.dao..", "org.springframework.jdbc..",
    "org.springframework.orm..", "org.springframework.transaction..",
    "org.springframework.r2dbc..", "org.springframework.oxm..",
    "org.springframework.jca..")
.layer("Web").definedBy(
    "org.springframework.web..", "org.springframework.http..",
    "org.springframework.ui..", "org.springframework.protobuf..")
.layer("MiscServices").definedBy(
    "org.springframework.jms..", "org.springframework.mail..",
    "org.springframework.messaging..", "org.springframework.scheduling..",
    "org.springframework.cache..", "org.springframework.jmx..",
    "org.springframework.resilience..")
```

Also add an explicit safeguard rule that will catch any *future* unmapped package:

```java
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
        "org.springframework.core..", "org.springframework.beans..",
        "org.springframework.context..", "org.springframework.expression..",
        "org.springframework.stereotype..", "org.springframework.format..",
        "org.springframework.validation..", "org.springframework.ejb..",
        "org.springframework.jndi..", "org.springframework.scripting..",
        "org.springframework.contextsupport..",
        "org.springframework.aop..", "org.springframework.aot..",
        "org.springframework.instrument..",
        "org.springframework.dao..", "org.springframework.jdbc..",
        "org.springframework.orm..", "org.springframework.transaction..",
        "org.springframework.r2dbc..", "org.springframework.oxm..",
        "org.springframework.jca..",
        "org.springframework.web..", "org.springframework.http..",
        "org.springframework.ui..", "org.springframework.protobuf..",
        "org.springframework.jms..", "org.springframework.mail..",
        "org.springframework.messaging..", "org.springframework.scheduling..",
        "org.springframework.cache..", "org.springframework.jmx..",
        "org.springframework.resilience..")
    .because("Every Spring package must be explicitly mapped to a layer so future additions cannot silently bypass the architecture.");
```

---

### F-3
```
SEVERITY: CRITICAL
Category: Semantic Error / Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected (AopInstrumentation access rule)
```

**What is wrong:**
The documented hierarchy places AOP & Instrumentation *above* Core Container (layer 3 > layer 2). The rule, however, states:

```java
.whereLayer("AopInstrumentation").mayOnlyBeAccessedByLayers(
    "DataAccess", "Web", "MiscServices", "CoreContainer")
```

Listing `CoreContainer` here tells ArchUnit that CoreContainer classes *may depend on* AopInstrumentation classes. That is a downward → upward dependency and directly contradicts the documented downward-only flow ("strict downward dependency flow" per the rule's own `.because()` clause).

**Why it matters:**
A developer could legally introduce `org.springframework.beans.factory.support.AbstractBeanFactory` → `org.springframework.aop.framework.ProxyFactory` and the test would pass. In practice this creates a dependency cycle between the two lowest architectural layers and undermines the entire hierarchy.

**How to fix it:**
Remove `CoreContainer` from the allowed accessors of `AopInstrumentation`:

```java
.whereLayer("AopInstrumentation").mayOnlyBeAccessedByLayers(
    "DataAccess", "Web", "MiscServices")
```

If the real constraint is that AOP genuinely *does* need a back-edge (Spring context does opportunistically use AOP at runtime), then the documentation should be corrected — but as written the rule contradicts the documentation.

---

### F-4
```
SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected (BaseUtilities definition)
```

**What is wrong:**
`org.springframework.validation..` is placed in the lowest layer, `BaseUtilities`. In the real codebase the validation package (`org.springframework.validation.beanvalidation`, `DataBinder`, etc.) depends on `org.springframework.beans..` and `org.springframework.context..`. With the current rule, *every one of those legitimate dependencies becomes a violation* because a BaseUtilities class may not access CoreContainer classes.

`org.springframework.stereotype..` is less clearly wrong (the annotations themselves have no bean dependencies today) but is conventionally documented as part of Core Container and has historically referenced `core.annotation`.

**Why it matters:**
The moment the classpath is fixed (see F-1), the layered rule will flood with false positives in `org.springframework.validation.*` such as:

- `DataBinder` → `org.springframework.beans.PropertyAccessor`
- `SmartValidator` → `org.springframework.core.MethodParameter`
- `beanvalidation.LocalValidatorFactoryBean` → `org.springframework.context.ApplicationContextAware`

All of these are *documented* architectural dependencies, not violations.

**How to fix it:**
Move `validation` and `stereotype` up to `CoreContainer`:

```java
.layer("BaseUtilities").definedBy(
    "org.springframework.util..", "org.springframework.lang..")
.layer("CoreContainer").definedBy(
    "org.springframework.core..", "org.springframework.beans..",
    "org.springframework.context..", "org.springframework.expression..",
    "org.springframework.stereotype..", "org.springframework.validation..")
```

---

### F-5
```
SEVERITY: HIGH
Category: Overly Narrow
Affected Rule / Constraint: parallel_layers_web_and_dataaccess_are_independent
```

**What is wrong:**
The rule name promises isolation between the Web and DataAccess layers, but the body only forbids `org.springframework.web..` → `org.springframework.orm..`:

```java
noClasses()
    .that().resideInAPackage("org.springframework.web..")
    .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
```

`jdbc`, `transaction`, `r2dbc`, and `dao` are siblings of `orm` inside DataAccess, and none of them are covered. Additionally, the reverse direction (DataAccess → Web) is unchecked.

**Why it matters:**
A controller that imports `org.springframework.jdbc.core.JdbcTemplate` directly (skipping a service layer) bypasses the intended parallel-layer isolation and the rule stays green. Worse, because the layered architecture lists Web as accessing DataAccess via the main hierarchy, the layered rule will also not catch this.

**How to fix it:**
Replace with a two-directional, multi-package rule:

```java
@ArchTest
public static final ArchRule web_and_dataaccess_are_isolated = noClasses()
    .that().resideInAnyPackage("org.springframework.web..",
                               "org.springframework.http..",
                               "org.springframework.ui..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.dao..", "org.springframework.jdbc..",
        "org.springframework.orm..", "org.springframework.transaction..",
        "org.springframework.r2dbc..", "org.springframework.oxm..")
    .because("Web-layer components must reach persistence only through a service/MiscServices facade, never directly.");
```

NOTE: if Spring's own `@Transactional` usage on Web controllers is considered legitimate in this architecture, then `transaction` must be excluded — but the decision needs to be explicit, not implicit.

---

### F-6
```
SEVERITY: HIGH
Category: Coverage Gap
Affected Rule / Constraint: Intra-CoreContainer isolation
```

**What is wrong:**
Inside CoreContainer (`core`, `beans`, `context`, `expression`) the documentation's clear intent is a strict internal order: `core` ⊂ `beans` ⊂ `context` and `expression` is side-by-side with `core`/`beans`. The rules enforce only two edges:

- `beans → context` (forbidden)
- `core → beans` (forbidden)

but silently allow:

- `core → context`
- `core → expression`
- `expression → beans`
- `expression → context`
- `beans → expression`

**Why it matters:**
A developer can reintroduce the classic Spring-1.x circular-dependency hazard (`core.ReflectionUtils` → `context.support`) and no rule blocks it.

**How to fix it:**
Add explicit rules:

```java
@ArchTest
public static final ArchRule core_is_standalone = noClasses()
    .that().resideInAPackage("org.springframework.core..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.beans..", "org.springframework.context..",
        "org.springframework.expression..")
    .because("spring-core is the foundation of the Core Container and must not depend on any of its siblings.");

@ArchTest
public static final ArchRule beans_is_below_context = noClasses()
    .that().resideInAPackage("org.springframework.beans..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.context..", "org.springframework.expression..")
    .because("spring-beans must not depend on spring-context or spring-expression.");

@ArchTest
public static final ArchRule expression_is_leaf_within_core_container = noClasses()
    .that().resideInAPackage("org.springframework.expression..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.beans..", "org.springframework.context..")
    .because("SpEL is self-contained; it may use spring-core only.");
```

---

### F-7
```
SEVERITY: HIGH
Category: Overly Narrow / Semantic Error
Affected Rule / Constraint: aop_should_not_depend_on_dataaccess
```

**What is wrong:**
The rule is named "aop should not depend on dataaccess" but only blocks `aop → transaction`:

```java
noClasses()
    .that().resideInAPackage("org.springframework.aop..")
    .should().dependOnClassesThat().resideInAPackage("org.springframework.transaction..")
```

`dao`, `jdbc`, `orm`, `r2dbc`, `oxm`, `jca` are not checked.

Additionally, this rule is **already implied** by the layered hierarchy (DataAccess sits above AopInstrumentation, so AOP cannot depend on DataAccess). Once F-1 (classpath) and F-4 are fixed, the layered rule would flag any such dependency. The dedicated rule is therefore redundant *and* narrow.

**Why it matters:**
Either leave it out or make it exhaustive. As-is it gives a false sense of intra-layer coverage.

**How to fix it:**
Delete the rule (redundant with the layered architecture) *or* widen it and use `resideOutsideOfPackage` to make intent explicit:

```java
@ArchTest
public static final ArchRule aop_does_not_depend_on_dataaccess = noClasses()
    .that().resideInAPackage("org.springframework.aop..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.dao..", "org.springframework.jdbc..",
        "org.springframework.orm..", "org.springframework.transaction..",
        "org.springframework.r2dbc..", "org.springframework.oxm..",
        "org.springframework.jca..")
    .because("AOP is a lower-level primitive than DataAccess; all DataAccess packages build on AOP, not the other way round.");
```

---

### F-8
```
SEVERITY: MEDIUM
Category: Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected (Web vs. MiscServices ordering)
```

**What is wrong:**
The documentation header in the Java file lists the layers bottom-to-top as:

```
5. Web Layer
6. Miscellaneous Services (topmost)
```

But the rule configures Web as the top-most layer:

```java
.whereLayer("Web").mayNotBeAccessedByAnyLayer()
.whereLayer("MiscServices").mayOnlyBeAccessedByLayers("Web")
```

i.e. MiscServices is *below* Web and Web is the top. This contradicts the file's own Javadoc.

**Why it matters:**
At minimum the Javadoc and the rule disagree — the reader cannot tell which version is canonical. If the Javadoc is correct, the rule is wrong (Web is not the top). If the rule is correct, the Javadoc must be amended. Either way a reviewer is misled.

**How to fix it:**
Pick one. If the *rule* is correct (MiscServices is shared infrastructure used by Web, e.g. `messaging`, `scheduling`), update the Javadoc ordering. Otherwise flip the rule:

```java
.whereLayer("MiscServices").mayNotBeAccessedByAnyLayer()
.whereLayer("Web").mayOnlyBeAccessedByLayers("MiscServices")
```

---

### F-9
```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: Missing DataAccess intra-layer rules
```

**What is wrong:**
The layered rule groups `dao`, `jdbc`, `orm`, `transaction`, `r2dbc` into a single DataAccess layer. The documentation states JDBC is "a low-level alternative to ORM" (implicit: `jdbc` should not know about `orm`). The generator enforces one direction (`jdbc → orm`) but misses:

- `jdbc → r2dbc` (JDBC must not depend on R2DBC)
- `r2dbc → orm`
- `transaction → jdbc / orm / r2dbc` (transaction abstraction is above specific data APIs)
- `orm → jdbc` direction is intentionally *allowed* (ORM typically builds on JDBC) but this should be made explicit.

**Why it matters:**
Without explicit intra-layer rules, circular dependencies inside DataAccess (e.g., `transaction` reaching into `jdbc.datasource.lookup`) will not be caught.

**How to fix it:**

```java
@ArchTest
public static final ArchRule jdbc_does_not_know_about_siblings = noClasses()
    .that().resideInAPackage("org.springframework.jdbc..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.orm..", "org.springframework.r2dbc..")
    .because("JDBC is a low-level alternative to ORM/R2DBC; it must stay independent of them.");

@ArchTest
public static final ArchRule transaction_abstraction_is_pure = noClasses()
    .that().resideInAPackage("org.springframework.transaction..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jdbc..", "org.springframework.orm..",
        "org.springframework.r2dbc..")
    .because("The transaction abstraction must not leak knowledge of specific resource APIs.");

@ArchTest
public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
    .that().resideInAPackage("org.springframework.r2dbc..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jdbc..", "org.springframework.orm..")
    .because("R2DBC is a peer of JDBC/ORM, not a client of them.");
```

---

### F-10
```
SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: Missing MiscServices intra-layer rules
```

**What is wrong:**
The layered rule lumps `jms`, `mail`, `messaging`, `scheduling`, `cache`, `jmx` into `MiscServices`. The documentation implies each is a specialized service that should not know about its siblings (e.g., `cache` should not depend on `jms`). No rule enforces this.

**Why it matters:**
Cross-contamination between cache and JMS, or between mail and scheduling, would pass CI.

**How to fix it:**
Add at minimum:

```java
@ArchTest
public static final ArchRule misc_services_do_not_depend_on_each_other = slices()
    .matching("org.springframework.(jms|mail|messaging|scheduling|cache|jmx)..")
    .should().notDependOnEachOther()
    .because("Each Miscellaneous Service must be usable in isolation; cross-dependencies break pluggability.");
```

(Requires `import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;`)

Note: if `messaging → jms` is considered acceptable, add a specific `ignoreDependency(...)`.

---

### F-11
```
SEVERITY: MEDIUM
Category: Overly Broad
Affected Rule / Constraint: layered_architecture_is_respected (consideringAllDependencies)
```

**What is wrong:**
`.consideringAllDependencies()` instructs ArchUnit to include external (non-project) and transitive dependencies. When the project has correct runtime dependencies on JDK types this is fine, but combined with the unmapped-package problem (F-2), any class in `oxm`, `jca`, `jndi`, etc. will be counted as "external" from the layered architecture's perspective and silently skipped.

**Why it matters:**
Less severe once F-2 is fixed. Until then it compounds the coverage gap: ArchUnit will treat `org.springframework.oxm.Marshaller` as *external*, not as a misplaced internal class.

**How to fix it:**
After fixing F-2, switch to `.consideringOnlyDependenciesInLayers()` unless there is an explicit need to police calls into JDK/third-party packages:

```java
layeredArchitecture()
    .consideringOnlyDependenciesInLayers()
    .layer(...)...
```

---

### F-12
```
SEVERITY: LOW
Category: Semantic Error (misleading .because())
Affected Rule / Constraint: core_should_not_depend_on_beans
```

**What is wrong:**
The `.because()` clause reads:

> Core utilities and **ASM-related processing** should not have knowledge of high-level bean management.

ASM processing happens in `org.springframework.asm..`, which is explicitly excluded from the scan. The rationale is garbled — this rule is about `org.springframework.core..`, not ASM.

**Why it matters:**
Low impact, but a confused rationale weakens review feedback when the rule fires.

**How to fix it:**

```java
.because("spring-core is the lowest layer of the Core Container; it must not know about bean-factory abstractions.");
```

---

### F-13
```
SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: aop_should_not_depend_on_dataaccess
```

**What is wrong:**
AopInstrumentation sits below DataAccess in the layered rule, so `layered_architecture_is_respected` already blocks `aop → transaction`. The dedicated rule is redundant (and, per F-7, narrower than its name).

**Why it matters:**
Not a correctness bug, but signals that the generator did not distinguish intra-layer concerns (which the layered rule cannot enforce) from cross-layer concerns (which it already enforces).

**How to fix it:**
Either delete (see F-7) or broaden.

---

### F-14
```
SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: ExcludeRepackagedAndTestPackages + ImportOption.DoNotIncludeTests
```

**What is wrong:**
Both `ImportOption.DoNotIncludeTests.class` and a custom exclusion of `org/springframework/test`, `org/springframework/tests`, `org/springframework/mock` are declared. `DoNotIncludeTests` already excludes `*/test-classes/*` locations, but it does not exclude the production packages under `org.springframework.test..`; the custom one does. They are complementary rather than redundant — however documenting that fact in a comment would save future maintainers the round-trip.

**Why it matters:**
Cosmetic.

**How to fix it:**
Add a comment on the import-option class explaining that `DoNotIncludeTests` removes `src/test/java` output while `ExcludeRepackagedAndTestPackages` removes Spring's *production* test-support modules (`spring-test`, `spring-mock`).

---

## Coverage Matrix (condensed)

| # | Constraint (from docs / package inventory)                           | Rule                                 | Status                     |
|---|-----------------------------------------------------------------------|--------------------------------------|----------------------------|
| 1 | BaseUtilities is the foundation (no upward deps)                      | layered                              | Partial — wrong layer for `validation`, `stereotype` (F-4) |
| 2 | CoreContainer depends only on BaseUtilities                           | layered + intra rules                | Partial — `core → context/expression` uncovered (F-6) |
| 3 | AopInstrumentation is above CoreContainer                             | layered                              | **Broken** — CoreContainer allowed to access AOP (F-3) |
| 4 | DataAccess depends only on lower layers                               | layered                              | Covered (once F-1, F-2 fixed) |
| 5 | Web depends only on lower layers                                      | layered                              | Covered (once F-1, F-2 fixed) |
| 6 | MiscServices placement                                                | layered                              | Documentation/rule disagree (F-8) |
| 7 | `oxm`, `jca`, `jndi`, `ejb`, `format`, `scripting`, `protobuf`, `resilience`, `contextsupport` placement | — | **Missing** (F-2) |
| 8 | Web ⟂ DataAccess (parallel isolation)                                 | parallel_layers_web_and_dataaccess   | Narrow (F-5) |
| 9 | `beans ⟶ context` forbidden                                           | beans_should_not_depend_on_context   | Covered |
| 10 | `core ⟶ beans` forbidden                                              | core_should_not_depend_on_beans      | Covered (but wrong `.because`, F-12) |
| 11 | `core ⟶ context/expression` forbidden                                 | —                                    | **Missing** (F-6) |
| 12 | `expression ⟶ beans/context` forbidden                                | —                                    | **Missing** (F-6) |
| 13 | `jdbc ⟶ orm` forbidden                                                | jdbc_should_not_depend_on_orm        | Covered |
| 14 | `jdbc ⟶ r2dbc`, `r2dbc ⟶ jdbc/orm`, `transaction ⟶ jdbc/orm/r2dbc`    | —                                    | **Missing** (F-9) |
| 15 | `aop ⟶ dataaccess (all sub-pkgs)` forbidden                           | aop_should_not_depend_on_dataaccess  | Narrow + redundant (F-7, F-13) |
| 16 | MiscServices sub-modules pluggable / non-coupled                      | —                                    | **Missing** (F-10) |
| 17 | Tests + repackaged libraries excluded from scan                        | ExcludeRepackagedAndTestPackages     | Covered (F-14 cosmetic) |
| 18 | Rules actually execute against production classes                      | —                                    | **Broken** (F-1) |

---

## Recommended Patch (drop-in replacement for the rule class body)

The patch addresses F-2, F-3, F-4, F-5, F-6, F-7, F-9, F-10, F-11, F-12. F-1 must also be fixed in `pom.xml` (dependencies), and F-8 requires a documentation decision.

```java
package org.springframework;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "org.springframework", importOptions = {
    ImportOption.DoNotIncludeTests.class,
    ArchitectureEnforcementTest.ExcludeRepackagedAndTestPackages.class
})
public class ArchitectureEnforcementTest {

    public static class ExcludeRepackagedAndTestPackages implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("org/springframework/asm")
                && !location.contains("org/springframework/cglib")
                && !location.contains("org/springframework/objenesis")
                && !location.contains("org/springframework/javapoet")
                && !location.contains("org/springframework/test")
                && !location.contains("org/springframework/tests")
                && !location.contains("org/springframework/mock")
                && !location.contains("org/springframework/build")
                && !location.contains("org/springframework/docs");
        }
    }

    // =========================================================================
    // LAYERED ARCHITECTURE
    // =========================================================================

    @ArchTest
    public static final ArchRule layered_architecture_is_respected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("BaseUtilities").definedBy(
            "org.springframework.util..", "org.springframework.lang..")
        .layer("CoreContainer").definedBy(
            "org.springframework.core..", "org.springframework.beans..",
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.validation..",
            "org.springframework.format..", "org.springframework.scripting..",
            "org.springframework.ejb..", "org.springframework.jndi..",
            "org.springframework.contextsupport..")
        .layer("AopInstrumentation").definedBy(
            "org.springframework.aop..", "org.springframework.aot..",
            "org.springframework.instrument..")
        .layer("DataAccess").definedBy(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..")
        .layer("Web").definedBy(
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..")
        .layer("MiscServices").definedBy(
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.jmx..",
            "org.springframework.resilience..")

        .whereLayer("MiscServices").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("MiscServices")
        .whereLayer("DataAccess").mayOnlyBeAccessedByLayers("Web", "MiscServices")
        .whereLayer("AopInstrumentation").mayOnlyBeAccessedByLayers(
            "DataAccess", "Web", "MiscServices")
        .whereLayer("CoreContainer").mayOnlyBeAccessedByLayers(
            "AopInstrumentation", "DataAccess", "Web", "MiscServices")
        .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers(
            "CoreContainer", "AopInstrumentation", "DataAccess", "Web", "MiscServices")
        .because("Spring Framework enforces a strict downward dependency flow.");

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
            "org.springframework.core..", "org.springframework.beans..",
            "org.springframework.context..", "org.springframework.expression..",
            "org.springframework.stereotype..", "org.springframework.validation..",
            "org.springframework.format..", "org.springframework.scripting..",
            "org.springframework.ejb..", "org.springframework.jndi..",
            "org.springframework.contextsupport..",
            "org.springframework.aop..", "org.springframework.aot..",
            "org.springframework.instrument..",
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.transaction..",
            "org.springframework.r2dbc..", "org.springframework.oxm..",
            "org.springframework.jca..",
            "org.springframework.web..", "org.springframework.http..",
            "org.springframework.ui..", "org.springframework.protobuf..",
            "org.springframework.jms..", "org.springframework.mail..",
            "org.springframework.messaging..", "org.springframework.scheduling..",
            "org.springframework.cache..", "org.springframework.jmx..",
            "org.springframework.resilience..")
        .because("Every production Spring package must be mapped to a layer.");

    // =========================================================================
    // PARALLEL-LAYER ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule web_and_dataaccess_are_isolated = noClasses()
        .that().resideInAnyPackage("org.springframework.web..",
                                   "org.springframework.http..",
                                   "org.springframework.ui..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("Web-layer components must reach persistence only through a service/MiscServices facade.");

    // =========================================================================
    // INTRA-CORE-CONTAINER ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule core_is_standalone = noClasses()
        .that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..",
            "org.springframework.expression..")
        .because("spring-core is the foundation of the Core Container.");

    @ArchTest
    public static final ArchRule beans_is_below_context = noClasses()
        .that().resideInAPackage("org.springframework.beans..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.context..", "org.springframework.expression..")
        .because("spring-beans must not know about spring-context or spring-expression.");

    @ArchTest
    public static final ArchRule expression_is_leaf_within_core_container = noClasses()
        .that().resideInAPackage("org.springframework.expression..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.beans..", "org.springframework.context..")
        .because("SpEL depends on spring-core only.");

    // =========================================================================
    // INTRA-DATA-ACCESS ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule jdbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.jdbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.orm..", "org.springframework.r2dbc..",
            "org.springframework.oxm..")
        .because("JDBC is a low-level alternative to ORM/R2DBC; it must stay independent.");

    @ArchTest
    public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
        .that().resideInAPackage("org.springframework.r2dbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.oxm..")
        .because("R2DBC is a peer of JDBC/ORM, not a client of them.");

    @ArchTest
    public static final ArchRule transaction_abstraction_is_pure = noClasses()
        .that().resideInAPackage("org.springframework.transaction..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..", "org.springframework.orm..",
            "org.springframework.r2dbc..", "org.springframework.oxm..")
        .because("The transaction abstraction must not leak knowledge of specific resource APIs.");

    // =========================================================================
    // INTRA-MISC-SERVICES ISOLATION
    // =========================================================================

    @ArchTest
    public static final ArchRule misc_services_do_not_depend_on_each_other = slices()
        .matching("org.springframework.(jms|mail|messaging|scheduling|cache|jmx|resilience)..")
        .should().notDependOnEachOther()
        .because("Each Miscellaneous Service must be independently pluggable.");
}
```

---

## Severity Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 3 (F-1, F-2, F-3) |
| HIGH     | 4 (F-4, F-5, F-6, F-7) |
| MEDIUM   | 4 (F-8, F-9, F-10, F-11) |
| LOW      | 3 (F-12, F-13, F-14) |

**Final verdict: FAIL.** The harness needs three critical fixes (classpath, unmapped packages, AOP layer inversion) before any of the other findings can even materialize as test failures.
