# Adversarial Review of Generated ArchUnit Rules — spring-framework / sonnet-4-6

Reviewer: opus-4-7
Review #: 3

> **Context**: Review #2 raised 15 findings (1 CRITICAL, 4 HIGH, 5 MEDIUM, 5 LOW). All were applied in `fix-history.md` step 4 — the file is now at 26 522 bytes / 23 `@ArchTest` rules with a new `Messaging` foundation layer, a new `AopConsumers` layer, and two new `ignoreDependency()` blocks. The new surefire report is only 284 lines (down from 1006) and shows 3 remaining failures producing 232 total violations (down from 946).
>
> **Review #3 does not repeat Reviews #1/#2**. It focuses on (a) defects introduced by the Review #2 fix itself, and (b) pre-existing gaps that now dominate the violation surface.

---

## Phase 1 — Coverage Audit (fresh pass)

### 1.1 Rule Inventory (23 `@ArchTest` rules)

Unchanged from the Review #2 fix output. New since Review #2: `spring_dao_must_not_depend_on_other_data_access_modules`, `spring_transaction_must_not_depend_on_specific_persistence_modules`, `aop_must_not_depend_on_aot`. Deleted: `spring_messaging_must_not_depend_on_spring_web` (R6 subsumed by F15 Option A).

### 1.2 Surefire outcome (23 tests, 3 failures)

| Rule | Violations | Delta vs. Review #2 | Dominant pattern |
|---|---:|---:|---|
| `spring_layered_architecture_is_respected` | **159** | −736 | `context.annotation → aop.{framework,config,scope,support,autoproxy}` (15), `context.event → aop` (9), `context.config/annotation → jmx` (10+), `jndi → aop` (7), `validation.annotation → aop` (2), `scheduling.quartz → {jdbc, transaction}` (5), `messaging.rsocket → aop` (4), `web.*.script → scripting` (4), `Web/Messaging → AopConsumers` (5), `Web/Messaging → oxm/cache` (60+) |
| `web_layer_must_not_depend_on_data_access_layer` | **56** | **+54 regression** | `web → oxm` (15) and `web/http → cache` (35+) |
| `aop_must_not_depend_on_aot` | **17** | **+17 new rule fails immediately** | `aop.aspectj.annotation.AspectJ*AotProcessor` (5 each), `aop.scope.ScopedProxyBeanRegistrationAotProcessor` (6), `aop.framework.CglibAopProxy.buildProxy → aot.AotDetector` (1) |
| Other 20 rules | 0 | — | — |

**Total violations: 232, down from 946 (−76 %). Net rules failing: 3 (was 4).**

### 1.3 Newly-visible implicit constraints (C19–C24)

With the larger noise sources gone, five more documented bridge patterns surface:

| # | Constraint |
|---|---|
| C19 | `spring-aop` has **AOT runtime hint processors** (`AspectJAdvisorBeanRegistrationAotProcessor`, `AspectJBeanFactoryInitializationAotProcessor`, `ScopedProxyBeanRegistrationAotProcessor`) and `CglibAopProxy` uses `AotDetector` — AOP has a *real, documented* dependency on AOT. |
| C20 | `spring-oxm` is used by `http.converter.xml`, `web.servlet.view.xml`, `messaging.converter` — it is a cross-cutting XML codec, not a DA module. |
| C21 | `spring-cache` is used by `web.reactive.resource`, `web.servlet.resource` — Web resource caching treats Spring cache as a shared SPI. |
| C22 | `spring-context`'s event/config/annotation sub-packages genuinely use `aop.*` beyond just `TargetSource` — including `ProxyFactory`, `AopConfigUtils`, `ScopedProxyUtils`, `AopProxyUtils`, `AopUtils`, `AutoProxyUtils`. |
| C23 | `spring-context` exposes `@EnableMBeanExport`, which pulls `jmx.export.annotation.AnnotationMBeanExporter` and `jmx.support.*` into `context.annotation` / `context.config`. |
| C24 | `spring-messaging.rsocket.service` uses `aop.framework.ProxyFactory` to build RSocket service client proxies. |

---

## Phase 2 — Rule Precision Audit

5. **F28 — new rule is fundamentally vacuous-wrong**: `aop_must_not_depend_on_aot` (F21 in Review #2) was added "for symmetry" but the premise is false — see F28 below.
6. **F29 — a new `ignoreDependency()` glob is malformed**: the F14 carve-out uses `"org.springframework.aop"` (no `..`), which only matches the top-level `aop` package, missing all AOP sub-packages — see F29 below.
7. **Overly broad — R2**: unchanged from Review #2, but the new violations reveal a latent inconsistency between the `because()` clause and the `DATA_ACCESS_PEER` constant — see F30 below.
8. **Vacuous `spring_dao_must_not_depend_on_other_data_access_modules` and `spring_transaction_must_not_depend_on_specific_persistence_modules`** (both new F20 rules) — both pass (0 violations). The current Spring Framework is already compliant, so these rules only guard against future regressions. Not a defect, but worth noting they fire zero in this codebase.

---

## Phase 3 — Semantic Correctness Audit

9. **F28** (see below) — the `aop_must_not_depend_on_aot` rule's premise contradicts observable code.
10. **F30** — R2's `because()` clause promises that `oxm` and `cache` are foundational/CoreContainer, but they are actually in `DATA_ACCESS_PEER`.
11. **F31–F33** — three additional CoreContainer packages are transitively AOP / DataAccess consumers (jndi, scheduling.quartz, validation.annotation).
12. **F34–F35** — Messaging (foundation) and peer layers (Web, DataAccess) have documented needs to access AOP and AopConsumers respectively.

---

## Phase 4 — Structural & Completeness Audit

13. **F36, F37, F38** — additional context/peer → {aop, jmx, scripting} bridges not covered by existing `ignoreDependency()` blocks.
14. The `AopConsumers` layer position is conceptually incorrect: it was placed *above* AOP, but its real consumers are the *peer* layers (Web, Messaging), not CoreContainer. An `AopConsumers` that only AOP depends on is a misnomer.

---

## Executive Summary

- **Total documented constraints identified**: 24 (C1–C24). C19–C24 are implicit bridges only visible after Review #2's fixes reduced noise.
- **Total rules generated**: 23
- **Coverage of explicit diagram constraints (C1–C11)**: 11 / 11 ✓
- **Coverage of implicit bridge constraints (C12–C24)**: partial — C12, C13, C14, C18, C15 addressed via F15/F16/F19/F14 fixes from Review #2; C19 is actively *violated* by the new rule added in Review #2 (F21 → F28 regression); C20–C24 remain unaddressed.
- **New critical defects introduced by Review #2 fix**:
  1. **F28** — the brand-new `aop_must_not_depend_on_aot` rule produces 17 violations on commit. Spring AOP legitimately uses `AotDetector` and `RuntimeHints`; the rule's claim that AOT and AOP are "unrelated" is empirically false.
  2. **F29** — the `ignoreDependency` for the F14 context→aop carve-out uses `"org.springframework.aop"` without `..`, so it only covers the ~3 classes that implement `aop.TargetSource` directly and misses the ~30+ deeper `aop.framework/config/scope/support/autoproxy` consumers in `context.annotation`.
  3. **F30** — R2 regressed from 2 → 56 violations. `oxm` and `cache` were not moved out of `DATA_ACCESS_PEER`, but the rule's `because()` clause states they are in CoreContainer. Either the clause is lying, or the package assignment is.
- **Overall verdict**: **FAIL** — same failure count as Review #2 (3 rules), but one of those failures is a self-inflicted regression in a brand-new rule (F28), and another is an unambiguous 28× regression (F30). The remaining `spring_layered_architecture_is_respected` count dropped substantially (895 → 159), but the distribution reveals 6+ additional architectural blind spots.

---

## Findings

```
[F28] SEVERITY: CRITICAL
Category: Fabricated Constraint / Rule Will Always Fail
Affected Rule / Constraint: aop_must_not_depend_on_aot (added in Review #2 fix as F21)

What is wrong:
The rule claims "AOT and AOP are unrelated Spring technologies". Surefire reports 17 direct
compile-time dependencies from `spring-aop` to `spring-aot`:

  - `aop.aspectj.annotation.AspectJAdvisorBeanRegistrationAotProcessor$AspectJAdvisorContribution.applyTo`
      uses `aot.generate.GenerationContext.getRuntimeHints()`,
      `aot.hint.ReflectionHints.registerType(...)`, `aot.hint.RuntimeHints.reflection()`,
      `aot.hint.MemberCategory.ACCESS_DECLARED_FIELDS`
  - `aop.aspectj.annotation.AspectJBeanFactoryInitializationAotProcessor$AspectContribution.applyTo`
      uses `aot.generate.GenerationContext`, `aot.hint.ReflectionHints.registerMethod(...)`,
      `aot.hint.RuntimeHints.reflection()`, `aot.hint.ExecutableMode.INVOKE`
  - `aop.framework.CglibAopProxy.buildProxy` calls `aot.AotDetector.useGeneratedArtifacts()`
  - `aop.scope.ScopedProxyBeanRegistrationAotProcessor$ScopedProxyBeanRegistrationCodeFragments`
      uses `aot.generate.GenerationContext`, `aot.generate.GeneratedMethods.add(...)`,
      `aot.generate.GeneratedMethod.toMethodReference()`,
      `aot.generate.MethodReference.toCodeBlock()`

These are the Spring 6 native-image (GraalVM) support for AOP: proxy metadata needs to be
recorded as runtime hints so the GraalVM image generator does not strip them. This is a
**documented** feature, not a mistake.

Why it matters:
The rule is not merely noisy — it asserts a false architectural constraint. The review prompt
specifies that a rule representing "fabricated constraints not supported by the documentation
or the code" is a severe defect (equivalent to Review #1's F2 for R5).

How to fix it:
Delete the rule entirely.

```java
// DELETE:
@ArchTest
static final ArchRule aop_must_not_depend_on_aot = ... ;
```

The companion rule `aot_must_not_depend_on_aop` (F3 fix) still passes and can remain. The
bidirectional symmetry argument was never supported by documentation — Spring explicitly
requires that AOP registers AOT hints, but nothing in the documentation says AOT must register
AOP hints.
```

```
[F29] SEVERITY: CRITICAL
Category: Malformed Glob / Rule Precision
Affected Rule / Constraint: spring_layered_architecture_is_respected — F14 ignoreDependency

What is wrong:
The F14 carve-out (Review #2) reads:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.springframework.context.annotation.."),
    JavaClass.Predicates.resideInAPackage("org.springframework.aop")
)
```

The target glob `"org.springframework.aop"` (no `..`) in ArchUnit's `resideInAPackage` predicate
matches **only** classes whose package is *exactly* `org.springframework.aop` — for example
`org.springframework.aop.TargetSource` or `org.springframework.aop.Advisor`. It does **not**
match sub-packages such as `org.springframework.aop.framework`, `org.springframework.aop.config`,
`org.springframework.aop.scope`, `org.springframework.aop.support`, or
`org.springframework.aop.framework.autoproxy`.

The surefire output for R1 lists 15+ CoreContainer → AOP violations from
`context.annotation.*` that the carve-out was intended to cover but does not:

  - `AspectJAutoProxyRegistrar.registerBeanDefinitions → aop.config.AopConfigUtils.*`
  - `AutoProxyRegistrar.registerBeanDefinitions → aop.config.AopConfigUtils.*`
  - `CommonAnnotationBeanPostProcessor.buildLazyResourceProxy → aop.framework.ProxyFactory`
  - `ConfigurationClassEnhancer$BeanMethodInterceptor → aop.scope.ScopedProxyFactoryBean`
  - `ConfigurationClassPostProcessor.enhanceConfigurationClasses → aop.framework.autoproxy.AutoProxyUtils`
  - `ConfigurationClassUtils.checkConfigurationClassCandidate → aop.framework.AopInfrastructureBean`
  - `ContextAnnotationAutowireCandidateResolver.buildLazyResolutionProxy → aop.framework.ProxyFactory`
  - `AnnotationConfigUtils.processCommonDefinitionAnnotations → aop.framework.autoproxy.AutoProxyUtils`
  - `ScopedProxyCreator.createScopedProxy → aop.scope.ScopedProxyUtils`
  - `ResourceElementResolver.buildLazyResourceProxy → aop.framework.ProxyFactory`

Why it matters:
The F14 fix text (see `fix-history.md`) explicitly describes the carve-out as targeting the 3
`implements aop.TargetSource` cases but was *intended* to cover the full `context.annotation`
→ AOP bridge. The missing `..` makes the carve-out effectively a no-op for every violation it
was meant to suppress.

How to fix it:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.springframework.context.annotation.."),
    JavaClass.Predicates.resideInAPackage("org.springframework.aop..")
)
```

Also broaden the source side: similar violations appear from `context.event.*` (see F36) and
`context.config.*` (MBeanExport), so the cleanest form is:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAnyPackage(
        "org.springframework.context.annotation..",
        "org.springframework.context.event..",
        "org.springframework.context.config.."
    ),
    JavaClass.Predicates.resideInAPackage("org.springframework.aop..")
)
```

Verify the `resideInAnyPackage` predicate constructor exists on `JavaClass.Predicates`; if not,
add two separate `.ignoreDependency()` calls.

This single fix eliminates ~30 of the 159 R1 violations.
```

```
[F30] SEVERITY: CRITICAL
Category: Regression (28× worse) / because()-vs-selector inconsistency
Affected Rule / Constraint: web_layer_must_not_depend_on_data_access_layer (R2-inverse)

What is wrong:
The rule's `because()` clause says:

  "Cross-cutting SPIs (format, validation root, scheduling root, ui, jndi) are in
   CoreContainer and are accessible to Web without violating this rule."

But the rule's target selector `DATA_ACCESS_PEER` **still contains** `org.springframework.oxm..`
and `org.springframework.cache..`. Surefire logs 56 violations, all of them Web→oxm or
Web→cache:

  - `web.reactive.resource.CachingResourceResolver → cache.Cache`, `cache.CacheManager`,
    `cache.concurrent.ConcurrentMapCache`
  - `web.servlet.resource.CachingResourceResolver` — same
  - `web.servlet.resource.CachingResourceTransformer` — same
  - `web.reactive.resource.CachingResourceTransformer` — same
  - `web.reactive.config.ResourceChainRegistration` — same
  - `web.servlet.config.annotation.ResourceChainRegistration` — same
  - `http.converter.xml.MarshallingHttpMessageConverter → oxm.Marshaller`, `oxm.Unmarshaller`
  - `web.servlet.view.xml.MarshallingView → oxm.Marshaller`

Review #1's R3 `because()` clause originally listed "oxm, format, validation, cache" as the
cross-cutting exception set. The Review #1 fix moved `format` and `validation` root out of DA
(correctly) but neither the Review #1 nor the Review #2 fix moved `oxm` or `cache` anywhere.
Review #2's R2/R3 `because()` clause was rewritten and forgot to list `oxm` and `cache` — but
the rule *selector* was also not updated. The result: clause and selector disagree, 56
legitimate cross-cutting dependencies are flagged.

Why it matters:
This is the biggest single regression since the original file. Review #2 baseline R3 had 2
violations; Review #3 baseline has 56 (28× worse). The entire resource-caching and XML-view
subsystem of spring-webmvc and spring-webflux is failing.

How to fix it:
Two workable options.

**Option A (preferred)** — move `oxm` and `cache` to CoreContainer, matching the prose of the
current `because()` clauses:

```java
.layer("CoreContainer").definedBy(
    ...
    "org.springframework.oxm..",       // cross-cutting XML codec SPI
    "org.springframework.cache..",     // cross-cutting caching SPI
    ...
)
.layer("DataAccess").definedBy(DATA_ACCESS_PEER)   // DATA_ACCESS_PEER now excludes oxm, cache

// And edit the constant:
private static final String[] DATA_ACCESS_PEER = {
    "org.springframework.dao..",
    "org.springframework.jdbc..",
    "org.springframework.orm..",
    // "org.springframework.oxm..",       // moved to CoreContainer
    "org.springframework.transaction..",
    "org.springframework.r2dbc..",
    "org.springframework.jms..",
    "org.springframework.jca..",
    "org.springframework.mail..",
    // "org.springframework.cache..",     // moved to CoreContainer
};
```

Re-verify intra-DA rules: R4 (`jdbc ↛ orm`), NEW-DAO-ordering, and NEW-TX-ordering all still
need to work. None of them reference `oxm` on the source side (only as target of `dao ↛`),
which is fine.

Updates to `because()` clauses should match: already correct in R2/R3 after this change.

**Option B** — keep `oxm` and `cache` in DataAccess, and *narrow* both R2 and R3 (and R1
implicitly via `ignoreDependency`) to exclude them as targets:

```java
.should().dependOnClassesThat().resideInAnyPackage(
    "org.springframework.dao..",
    "org.springframework.jdbc..",
    "org.springframework.orm..",
    // oxm, cache allowed cross-layer
    "org.springframework.transaction..",
    "org.springframework.r2dbc..",
    "org.springframework.jms..",
    "org.springframework.jca..",
    "org.springframework.mail.."
)
```

Option A is simpler and matches the intent (both `oxm.Marshaller` and `cache.Cache` are
genuinely SPI-style interfaces with implementations pulled in separately).
```

```
[F31] SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: spring_layered_architecture_is_respected — `scheduling.quartz` in CoreContainer

What is wrong:
The Review #2 fix carved `scheduling` into several CoreContainer sub-packages to avoid F13:

```java
"org.springframework.scheduling",
"org.springframework.scheduling.concurrent..",
"org.springframework.scheduling.config..",
"org.springframework.scheduling.support..",
"org.springframework.scheduling.quartz..",
```

But `scheduling.quartz` is **not** a pure CoreContainer package. Surefire shows 5 violations:

  - `scheduling.quartz.LocalDataSourceJobStore.getConnection →
     jdbc.datasource.DataSourceUtils.doGetConnection`
  - `scheduling.quartz.LocalDataSourceJobStore.closeConnection →
     jdbc.datasource.DataSourceUtils.releaseConnection`
  - `scheduling.quartz.LocalDataSourceJobStore.initialize →
     jdbc.support.JdbcUtils.*`
  - `scheduling.quartz.SchedulerAccessor.registerJobsAndTriggers →
     transaction.PlatformTransactionManager.*`, `transaction.TransactionDefinition.withDefaults`
  - `scheduling.quartz.SchedulerAccessor.setTransactionManager →
     transaction.PlatformTransactionManager`

Quartz job persistence uses JDBC for durable job stores and `PlatformTransactionManager` for
transactional job scheduling.

Why it matters:
CoreContainer may not access any declared layer, so every Quartz job-store dependency on
`jdbc` or `transaction` (DA) fails R1.

How to fix it:
Remove `scheduling.quartz` from CoreContainer. It belongs in DataAccess (it is essentially a
scheduling-over-JDBC integration), or — if the codebase wants to keep it under "scheduling" —
in a new `SchedulingIntegrations` peer layer.

```java
.layer("CoreContainer").definedBy(
    ...
    "org.springframework.scheduling",
    "org.springframework.scheduling.concurrent..",
    "org.springframework.scheduling.config..",
    "org.springframework.scheduling.support..",
    // "org.springframework.scheduling.quartz..",  // REMOVED: uses jdbc+transaction
    ...
)
.layer("DataAccess").definedBy(
    ...existing...,
    "org.springframework.scheduling.quartz.."    // ADDED: Quartz integration is DA
)
```

Note: DataAccess is allowed to access CoreContainer, so `scheduling.quartz → scheduling.*`
(within the same scheduling umbrella) becomes a DA→CoreContainer edge, which is allowed.
```

```
[F32] SEVERITY: HIGH
Category: Wrong Layer (incomplete F13 carve-out)
Affected Rule / Constraint: spring_layered_architecture_is_respected — `validation.annotation` in CoreContainer

What is wrong:
Review #2's F13 fix carved `validation.beanvalidation` into AopConsumers but left
`validation.annotation` in CoreContainer. Surefire shows 2 violations in that sub-package:

  - `validation.annotation.ValidationAnnotationUtils.determineValidationGroups →
     aop.framework.AopProxyUtils.proxiedUserInterfaces`
  - `validation.annotation.ValidationAnnotationUtils.determineValidationGroups →
     aop.support.AopUtils.isAopProxy`

`ValidationAnnotationUtils` inspects whether a validated target is an AOP proxy so it can
unwrap to the real class before resolving `@Validated` groups. This is the same class-of-issue
as F13 — AOP-backed validation annotation processing must sit above AOP.

Why it matters:
Same severity and shape as F13; `validation.annotation` is partially AOP-dependent and cannot
live in CoreContainer without producing CoreContainer→AOP violations.

How to fix it:
Move `validation.annotation` into the existing `AopConsumers` layer:

```java
.layer("AopConsumers").definedBy(
    "org.springframework.scheduling.annotation..",
    "org.springframework.validation.annotation..",      // ADDED (F32)
    "org.springframework.validation.beanvalidation.."
)
```

And remove it from CoreContainer:

```java
.layer("CoreContainer").definedBy(
    ...
    "org.springframework.validation",
    // "org.springframework.validation.annotation..",   // REMOVED (F32)
    "org.springframework.validation.method..",
    "org.springframework.validation.support..",
    ...
)
```
```

```
[F33] SEVERITY: HIGH
Category: Wrong Layer / Missing Carve-out
Affected Rule / Constraint: spring_layered_architecture_is_respected — `jndi` in CoreContainer

What is wrong:
Review #2's F19 fix moved `jndi` from DataAccess to CoreContainer to resolve 8+ consumers.
Surefire shows `jndi` itself depends heavily on `aop.*` — 7 R1 violations:

  - `jndi.JndiObjectTargetSource implements aop.TargetSource`
  - `jndi.JndiObjectFactoryBean$JndiObjectProxyFactory.createJndiObjectProxy → aop.framework.ProxyFactory`
    (via `<init>`, `addAdvice`, `addInterface`, `setInterfaces`, `getProxy`, `setTargetSource`)

`JndiObjectFactoryBean` builds a JNDI-lookup proxy using Spring AOP's `ProxyFactory`. JNDI
therefore depends on AOP.

Why it matters:
CoreContainer→AOP is forbidden. The F19 fix traded one mis-classification for another (DA→CoreContainer noise for CoreContainer→AOP noise).

How to fix it:
Either move `jndi` into a position that can see AOP (Option A below), or add an
`ignoreDependency` (Option B). Option A is architecturally cleaner because the F14 fix already
accepts that CoreContainer has AOP consumers in practice.

**Option A** — add an explicit `ignoreDependency` for jndi → aop:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.springframework.jndi.."),
    JavaClass.Predicates.resideInAPackage("org.springframework.aop..")
)
```

Document rationale: JNDI lookups optionally produce AOP-backed lazy proxies.

**Option B** (consider together with F39) — introduce an explicit `JavaEEBridge` layer
between CoreContainer and AOP for `jndi` and `instrument` and similar cross-cutting JavaEE
SPIs that need AOP. Over-engineering for the few classes involved.

Recommend Option A.
```

```
[F34] SEVERITY: HIGH
Category: Wrong Layer / Messaging placement too low
Affected Rule / Constraint: spring_layered_architecture_is_respected — Messaging → AOP forbidden

What is wrong:
Review #2's F15 Option A demoted `messaging` to a foundation layer with
`.mayOnlyAccessLayers("CoreContainer")`. Surefire shows `messaging.rsocket.service.*`
legitimately uses `aop.framework.*`:

  - `messaging.rsocket.service.RSocketExchangeBeanRegistrationAotProcessor$AotContribution
     .applyTo → aop.framework.AopProxyUtils.completeJdkProxyInterfaces`
  - `messaging.rsocket.service.RSocketServiceProxyFactory$ServiceMethodInterceptor.invoke →
     aop.framework.ReflectiveMethodInvocation.getProxy`, instanceof `aop.framework.ReflectiveMethodInvocation`
  - `messaging.rsocket.service.RSocketServiceProxyFactory.createClient →
     aop.framework.ProxyFactory.getProxy`

`RSocketServiceProxyFactory` is the public API for building RSocket service client stubs — it
explicitly uses Spring AOP to create the client proxy.

Why it matters:
F15 Option A solved the DA→Messaging and Web→Messaging bridges at the cost of Messaging→AOP.
The choice traded one violation bucket for a smaller one (down from hundreds to 4), but it is
still a false-positive class that prevents `spring-messaging` from being checkin-clean.

How to fix it:
Grant Messaging access to AOP:

```java
.whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer", "AOP")
```

This is architecturally fine — Messaging sits above CoreContainer and can use AOP. The only
constraint that matters is "Messaging ↛ {DataAccess, Web, Miscellaneous, AopConsumers}",
which this change preserves.

After this change, re-order the layers so AOP is declared before Messaging (so the
`mayOnlyAccessLayers("CoreContainer", "AOP")` reference resolves):

```java
.layer("CoreContainer").definedBy(...)
.layer("AOP").definedBy("org.springframework.aop..")
.layer("Messaging").definedBy("org.springframework.messaging..")
.layer("AopConsumers").definedBy(...)
.layer("DataAccess").definedBy(...)
.layer("Web").definedBy(...)
.layer("Miscellaneous").definedBy(...)

.whereLayer("CoreContainer").mayNotAccessAnyLayer()
.whereLayer("AOP").mayOnlyAccessLayers("CoreContainer")
.whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer", "AOP")
.whereLayer("AopConsumers").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging")
.whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")
.whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")
.whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")
```

Note: the peer layers now also gain access to AopConsumers — see F35.
```

```
[F35] SEVERITY: HIGH
Category: Wrong Layer / AopConsumers position
Affected Rule / Constraint: spring_layered_architecture_is_respected — peer → AopConsumers forbidden

What is wrong:
The Review #2 fix placed `AopConsumers` at `.mayOnlyAccessLayers("CoreContainer", "Messaging",
"AOP")`, i.e. the layer can see those three, but nothing else can see *AopConsumers*. In
reality the peer layers and Messaging are the main consumers of
`validation.beanvalidation.OptionalValidatorFactoryBean`:

  - `web.reactive.config.WebFluxConfigurationSupport.webFluxValidator →
     validation.beanvalidation.OptionalValidatorFactoryBean.<init>`
  - `web.servlet.config.annotation.WebMvcConfigurationSupport.mvcValidator →
     validation.beanvalidation.OptionalValidatorFactoryBean.<init>`
  - `messaging.simp.config.AbstractMessageBrokerConfiguration.simpValidator →
     validation.beanvalidation.OptionalValidatorFactoryBean.<init>`
  - `web.method.annotation.HandlerMethodValidator.from →
     validation.beanvalidation.MethodValidationAdapter.*`

These are 4 violations in R1, all of them Web→AopConsumers or Messaging→AopConsumers.

Why it matters:
The AopConsumers layer was introduced to *escape* CoreContainer→AOP violations for exactly
these classes. If the peer layers can't consume AopConsumers, the split is semantically
broken — the AopConsumers layer exists but no-one can use it.

How to fix it:
Let Messaging and all peer layers access AopConsumers:

```java
.whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer", "AOP")
.whereLayer("AopConsumers").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging")
.whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")
.whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")
.whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP", "Messaging", "AopConsumers")
```

This is conceptually the right model: AopConsumers is the set of annotation-driven AOP wiring
pieces (`@Async` advisor, `@Validated` method validation, `@EnableScheduling` post-processor).
All three peer layers plus Messaging need to be able to *bean-wire* them in their config
classes. AopConsumers → peer is still forbidden (unchanged), preserving the uni-directional
nature.
```

```
[F36] SEVERITY: MEDIUM
Category: Missing Carve-out
Affected Rule / Constraint: spring_layered_architecture_is_respected — `context.event` → `aop.*`

What is wrong:
Review #2's F14 carve-out was scoped to `context.annotation`, but `context.event.*` contains
at least 9 more R1 violations against `aop.*`:

  - `context.event.ApplicationListenerMethodAdapter.<init> → aop.support.AopUtils.getMostSpecificMethod`
  - `context.event.AbstractApplicationEventMulticaster.addApplicationListener →
     aop.framework.AopProxyUtils.getSingletonTarget`
  - `context.event.AbstractApplicationEventMulticaster.retrieveApplicationListeners →
     aop.framework.AopProxyUtils.getSingletonTarget`
  - `context.event.EventListenerMethodProcessor.afterSingletonsInstantiated →
     aop.framework.autoproxy.AutoProxyUtils.determineTargetClass`,
     `aop.scope.ScopedProxyUtils.getTargetBeanName`,
     `aop.scope.ScopedProxyUtils.isScopedTarget`,
     references `aop.scope.ScopedObject`
  - `context.event.EventListenerMethodProcessor.processBean →
     aop.support.AopUtils.selectInvocableMethod`
  - `context.event.GenericApplicationListenerAdapter.resolveDeclaredEventType →
     aop.support.AopUtils.getTargetClass`

These are all proxy-unwrapping helpers for `@EventListener` resolution on AOP-proxied beans.

Why it matters:
Same class-of-issue as F14, same fix shape. These ~9 R1 violations disappear if the carve-out
is broadened (as recommended in F29).

How to fix it:
Already covered by the broadened `ignoreDependency` in F29:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAnyPackage(
        "org.springframework.context.annotation..",
        "org.springframework.context.event..",
        "org.springframework.context.config.."
    ),
    JavaClass.Predicates.resideInAPackage("org.springframework.aop..")
)
```
```

```
[F37] SEVERITY: MEDIUM
Category: Missing Carve-out
Affected Rule / Constraint: spring_layered_architecture_is_respected — `context.*` → `jmx.*`

What is wrong:
`context.annotation.*` and `context.config.*` reference the JMX Miscellaneous layer to
implement `@EnableMBeanExport`:

  - `context.annotation.EnableMBeanExport.registration → jmx.support.RegistrationPolicy`
  - `context.annotation.MBeanExportConfiguration.mbeanExporter →
     jmx.export.annotation.AnnotationMBeanExporter.<init>`
  - `context.annotation.MBeanExportConfiguration.setupDomain/setupRegistrationPolicy/setupServer →
     jmx.export.annotation.AnnotationMBeanExporter.*`, `jmx.support.RegistrationPolicy.*`
  - `context.config.MBeanExportBeanDefinitionParser.parseInternal →
     jmx.export.annotation.AnnotationMBeanExporter`, `jmx.support.RegistrationPolicy.*`
  - `context.config.MBeanServerBeanDefinitionParser.parseInternal →
     jmx.support.MBeanServerFactoryBean`

About 10 R1 violations. This is a direct CoreContainer→Miscellaneous edge, i.e., the diagram's
bottom layer depending on the top-right peer. Like F14, it's not architectural drift — it's an
intentional Spring feature (`@EnableMBeanExport` is in the public API of `spring-context`).

Why it matters:
CoreContainer→Miscellaneous is forbidden (CoreContainer may not access any layer). Without a
carve-out or a relocation, 10 R1 violations persist.

How to fix it:
Add an `ignoreDependency`:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAnyPackage(
        "org.springframework.context.annotation..",
        "org.springframework.context.config.."
    ),
    JavaClass.Predicates.resideInAPackage("org.springframework.jmx..")
)
```

With rationale: `@EnableMBeanExport` is a documented public API of spring-context that wires
the `AnnotationMBeanExporter` from the `jmx` Miscellaneous sub-module. The same pattern could
apply to `context.annotation.EnableCaching`/`EnableScheduling` but those references target
the CoreContainer-resident roots (scheduling/cache) and are already allowed.
```

```
[F38] SEVERITY: MEDIUM
Category: Missing Carve-out / Peer-to-Peer Bridge
Affected Rule / Constraint: spring_layered_architecture_is_respected — `web.*.script` → `scripting.*`

What is wrong:
`web.reactive.result.view.script.ScriptTemplateView` and
`web.servlet.view.script.ScriptTemplateView` are the Spring view implementations that let
JavaScript/Groovy/Kotlin/etc. scripting engines render web views. They reference
`scripting.support.StandardScriptUtils.retrieveEngineByName` and
`scripting.support.StandardScriptEvalException.<init>` — 4 R1 violations, all Web→Miscellaneous.

`scripting` is in the Miscellaneous peer layer; Web→Miscellaneous is forbidden.

Why it matters:
`ScriptTemplateView` is a long-standing documented Spring MVC/WebFlux feature. Forbidding the
bridge prevents spring-webmvc and spring-webflux from compiling clean.

How to fix it:
Two options. Preferred:

**Option A** — narrow the Web→Misc bridge via `ignoreDependency` on R1:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAnyPackage(
        "org.springframework.web.servlet.view.script..",
        "org.springframework.web.reactive.result.view.script.."
    ),
    JavaClass.Predicates.resideInAPackage("org.springframework.scripting..")
)
```

**Option B** — move `scripting.support` to CoreContainer (it's a thin wrapper around
`javax.script.*`). Risky because `scripting.*` is named as Miscellaneous in the PDF.

Pick Option A; document as a narrow bridge.
```

```
[F39] SEVERITY: LOW
Category: Structural Observation
Affected Rule / Constraint: Overall layering model

What is wrong (observation, not rule defect):
The Review #1–#3 fix cycle has produced the pattern below:

  CoreContainer (lowest) — broad, contains many shared SPIs
      ↑
   AOP
      ↑
   Messaging              ←── also consumes AOP (F34)
      ↑
   AopConsumers           ←── needs to be reachable by Web/Messaging/DA (F35)
      ↑
   DataAccess  Web  Miscellaneous  (parallel peers)

with 3 `ignoreDependency()` exceptions currently (F14 — context.annotation→aop, F16 —
orm.jpa.support→web) and 4–5 more proposed (F29/F36 broaden F14, F37 add
context→jmx, F38 add web.script→scripting, F33 add jndi→aop).

The observation: the documented 2-row GFG diagram (CoreContainer at bottom, peers on top)
cannot capture the real Spring architecture without 8+ carve-outs. This review accepts the
carve-outs as the pragmatic resolution. An alternative worth documenting (but not pursuing
here) is a dependency-graph-derived layering that matches the code without any
`ignoreDependency()`.

Why it matters:
The `ignoreDependency` list will continue to grow with each Spring Framework release. Future
maintainers should note this and reassess whether the current rule set remains the
right artifact.

How to fix it:
No code change. Add a one-line comment to the class Javadoc:

```java
/**
 * ...
 * NOTE: the current layered model requires 8+ `ignoreDependency()` carve-outs for
 * documented Spring bridge APIs (JmsMessaging, OpenEntityManagerInView, EnableMBeanExport,
 * ScriptTemplateView, Context AOP consumers, JNDI proxies). Future maintainers should expect
 * additional carve-outs as Spring adds new bridge modules.
 */
```
```

```
[F40] SEVERITY: LOW
Category: because()-clause inaccuracy
Affected Rule / Constraint: data_access_layer_must_not_depend_on_web_layer, web_layer_must_not_depend_on_data_access_layer

What is wrong:
R2's `because()` clause mentions `org.springframework.orm.jpa.support` as the sole bridge,
but after the F30 fix is applied, R2 will also implicitly allow `oxm` and `cache` (they leave
DA entirely). The prose and the selector should stay in sync — Review #2 drifted here.

Why it matters:
Documentation accuracy only. Does not affect test outcome.

How to fix it:
After F30 Option A is applied, amend R2's `because()`:

  "DataAccess and Web are parallel top-row peer layers per the documented Spring Framework
   architecture diagram. Cross-cutting SPIs (format, validation root, scheduling root, ui,
   jndi, oxm, cache) are in CoreContainer and are accessible to Web. The only per-class
   bridge is `org.springframework.orm.jpa.support` (Open-EntityManager-in-View pattern),
   which is excluded from this rule."

Mirror a similar edit on R3.
```

```
[F41] SEVERITY: LOW
Category: Vacuous Rules (for this codebase)
Affected Rule / Constraint: spring_dao_must_not_depend_on_other_data_access_modules, spring_transaction_must_not_depend_on_specific_persistence_modules

What is wrong:
Both rules were introduced by Review #2's F20 fix. Both produce 0 violations in the current
Spring Framework codebase — neither spring-dao nor spring-tx currently imports any of the
forbidden packages.

Why it matters:
Not a defect. These rules act as regression guards (they will catch any future commit that
drifts). However, the review prompt's methodology rates "rules that will never fire" as a
concern. In this case the rules *could* fire (they are not structurally impossible), they just
don't fire today. Reasonable.

How to fix it:
No change. Add a one-line comment clarifying that the rule is intentionally a regression
guard:

```java
// F20: guard rail — currently 0 violations, preserves the documented tx/dao invariant.
@ArchTest
static final ArchRule spring_transaction_must_not_depend_on_specific_persistence_modules = ...
```
```

---

## Severity Totals (Review #3 only — new findings)

| Severity | Count | IDs |
|---|---:|---|
| CRITICAL | 3 | F28 (fabricated AOP↛AOT rule), F29 (malformed ignoreDependency glob), F30 (R2 regression 28×) |
| HIGH     | 5 | F31 (scheduling.quartz), F32 (validation.annotation), F33 (jndi→aop), F34 (Messaging→AOP), F35 (peers→AopConsumers) |
| MEDIUM   | 3 | F36 (context.event→aop), F37 (context→jmx), F38 (web.script→scripting) |
| LOW      | 3 | F39 (structural observation), F40 (because drift), F41 (vacuous guard-rails) |
| **Total** | **14** | |

---

## Regression / Improvement Scoreboard

| Metric | Review #1 baseline | After Review #1 fix (Review #2 input) | After Review #2 fix (Review #3 input) | Delta over 2 cycles |
|---|---:|---:|---:|---|
| Total `@ArchTest` rules | 16 | 21 | 23 | +7 |
| Tests run | 16 | 21 | 23 | +7 |
| Tests passing | 12 | 17 | 20 | **+8** |
| Tests failing | 4 | 4 | 3 | **−1** |
| Total violations | 1 864 | 946 (−49 %) | 232 (−88 %) | **−88 %** |
| Defects found this review | — | 15 (R2) | **14 (R3)** | — |
| Regressions introduced this cycle | — | 1 (F13) | **2 (F28, F30)** | — |

**Biggest single levers now available**:
1. **F29** (fix the `ignoreDependency` glob) — eliminates ~30 R1 violations with a 2-character change.
2. **F30 Option A** (move `oxm`+`cache` to CoreContainer) — eliminates R2's 56 violations **and** the Web→{oxm,cache} R1 violations (~25), for ~80 violations removed.
3. **F28** (delete `aop_must_not_depend_on_aot`) — eliminates 17 violations by removing an incorrect rule.
4. **F31** (move `scheduling.quartz` to DataAccess) — eliminates 5 R1 violations.
5. **F34 + F35** (allow Messaging→AOP and peers→AopConsumers) — eliminates ~8 R1 violations.

Applying all five recommended fixes together would cut the remaining 232 violations to roughly **~50**, with the rest covered by F33, F36, F37, F38 carve-outs — leaving an expected **~5 violations or fewer**, most of which can be closed with additional narrow `ignoreDependency` entries.

**Verdict: FAIL, but with a clear path to GREEN in one more fix cycle.**
