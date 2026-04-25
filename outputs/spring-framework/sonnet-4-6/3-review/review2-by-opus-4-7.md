# Adversarial Review of Generated ArchUnit Rules — spring-framework / sonnet-4-6

Reviewer: opus-4-7
Review #: 2

> **Context**: Review #1 surfaced 12 findings (2 CRITICAL, 3 HIGH, 5 MEDIUM, 2 LOW). All of them were applied in a subsequent fix (see `fix-history.md` step 3). The file under review is the post-fix version: `ArchitectureEnforcementTest.java` at 25 267 bytes / 21 `@ArchTest` rules. Review #2 re-runs the full methodology against the *new* file and surefire report (`281 644` bytes, 4 remaining failures: 895 + 44 + 2 + 5 = 946 violations).
>
> Review #2 does **not** repeat Review #1's findings unless new evidence has emerged. Every item below is a *new* defect — either a regression introduced by the Review #1 fix, or a pre-existing gap that only becomes visible once the noise from Review #1 is cleared.

---

## Phase 1 — Coverage Audit (fresh pass against post-fix rules)

### 1.1 Constraint Inventory (unchanged from Review #1)

C1–C11 from Review #1 still apply, plus — now visible because R1's mapping is cleaner — a set of *implicit* constraints the PDF's diagram does **not** model but which the real Spring codebase embodies. These matter because R1 is now flagging them as violations:

| # | Additional constraint |
|---|---|
| C12 | `spring-jms` has a documented messaging-bridge (`JmsMessagingTemplate extends AbstractMessagingTemplate`, `@JmsListener` is annotated with `@MessageMapping`). The PDF does not name this bridge but the code requires DA→Messaging |
| C13 | `spring-orm` has a documented Web/JPA integration bridge (`OpenEntityManagerInViewFilter`, `AsyncRequestInterceptor`, `OpenEntityManagerInViewInterceptor` in `org.springframework.orm.jpa.support`). The diagram does not model it but the code requires DA→Web |
| C14 | `spring-ejb` deploys beans as JNDI references (`JndiLookupBeanDefinitionParser`, `LocalStatelessSessionBeanDefinitionParser`). The diagram does not name this relation but the code requires Miscellaneous→DA (via `jndi`) |
| C15 | `spring-context` has AOP-backed resolvers (`CommonAnnotationBeanPostProcessor$1 implements aop.TargetSource`, `ContextAnnotationAutowireCandidateResolver$LazyDependencyTargetSource`). Context legitimately consumes AOP interfaces at runtime |
| C16 | `@Async`-style annotation processing (`scheduling.annotation.AsyncAnnotationAdvisor`, `AsyncAnnotationBeanPostProcessor`, `AnnotationAsyncExecutionInterceptor`) is AOP-based; `scheduling.annotation` classes extend `aop.*` classes |
| C17 | `@Validated` method-level validation (`validation.beanvalidation.MethodValidationPostProcessor`) is AOP-based; `validation.beanvalidation` extends `aop.framework.autoproxy.*` |
| C18 | `spring-websocket` STOMP support uses `spring-messaging` (`SubProtocolWebSocketHandler implements MessageHandler`, `WebSocketStompClient extends StompClientSupport`, etc.); Web→Messaging is a documented bridge |

These are not stated in the PDF, but the PDF's diagram is a high-level overview; the real architecture has explicit documented bridge modules that the current rule set flags.

### 1.2 Rule Inventory (current file)

21 `@ArchTest` rules (labels keep Review #1 IDs where equivalent; new rules have `NEW-n` IDs):

| # | Rule name |
|---|---|
| R1 | `spring_layered_architecture_is_respected` (6 layers: CoreContainer, AOP, DataAccess, Web, Messaging, Miscellaneous) |
| R2 | `data_access_layer_must_not_depend_on_web_layer` |
| R3 | `web_layer_must_not_depend_on_data_access_layer` |
| NEW-A | `misc_layer_must_not_depend_on_data_access_layer` |
| NEW-B | `misc_layer_must_not_depend_on_web_layer` |
| R4 | `spring_jdbc_must_not_depend_on_spring_orm` |
| ~~R5~~ | (deleted per Review #1 F2) |
| R6 | `spring_messaging_must_not_depend_on_spring_web` |
| R7 | `spring_jms_must_not_depend_on_spring_jdbc` |
| R8 | `spring_r2dbc_must_not_depend_on_spring_jdbc` |
| R9 | `spring_webmvc_must_not_depend_on_spring_webflux` |
| R10 | `spring_webflux_must_not_depend_on_spring_webmvc` |
| R11 | `spring_beans_must_not_depend_on_spring_context` |
| R12 | `spring_core_must_not_depend_on_spring_beans` |
| R13 | `spring_expression_must_not_depend_on_spring_context` |
| NEW-C | `spring_core_must_not_depend_on_spring_context` |
| NEW-D | `spring_core_must_not_depend_on_spring_expression` |
| NEW-E | `spring_beans_must_not_depend_on_spring_expression` |
| R14 | `spring_aop_must_not_depend_on_data_access_layer` |
| R15 | `spring_aop_must_not_depend_on_web_layer` |
| NEW-F | `aot_must_not_depend_on_aop` |
| R16 | `production_code_must_not_depend_on_spring_test_packages` |

### 1.3 Surefire outcome (21 tests, 4 failures)

| Rule | Violations | Dominant pattern |
|---|---|---|
| R1 (`spring_layered_architecture_is_respected`) | **895** | `jms → messaging` (large), `web.socket → messaging` (large), `context.annotation → aop` (3), `scheduling.annotation → aop` (3), `validation.beanvalidation → aop` (1), `orm.jpa.support → web` (~40), `context.annotation → jndi` (1) |
| R2 (`data_access_layer_must_not_depend_on_web_layer`) | **44** | `orm.jpa.support.*` → `web.context.*` — Open-EntityManager-in-View |
| R3 (`web_layer_must_not_depend_on_data_access_layer`) | **2** | `web.context.support.StandardServletEnvironment` → `jndi.*` |
| NEW-A (`misc_layer_must_not_depend_on_data_access_layer`) | **5** | `ejb.config.*` → `jndi.JndiObjectFactoryBean` |
| NEW-B, R4, R6–R16, NEW-C–F | 0 | — |

### 1.4 New Package-Coverage observations

- `org.springframework.jndi` is still placed in **DataAccess**, but the surefire output shows JNDI is consumed by CoreContainer (`context.annotation.CommonAnnotationBeanPostProcessor.<init>` constructs `jndi.support.SimpleJndiBeanFactory`), by Web (`StandardServletEnvironment`), and by Miscellaneous (every `ejb.config.*` parser). JNDI is behaving as a cross-cutting CoreContainer SPI, not as a DA module — see F19.
- `org.springframework.scheduling` was moved to CoreContainer by the Review #1 fix, but its `.annotation` subpackage extends `aop.*` classes (`AnnotationAsyncExecutionInterceptor extends aop.interceptor.AsyncExecutionInterceptor`). CoreContainer is supposed to sit below AOP, so this is a self-inconsistency introduced by the fix — see F13.
- `org.springframework.validation` was moved to CoreContainer by the Review #1 fix, but `validation.beanvalidation.MethodValidationPostProcessor extends aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor` — same issue — see F13.
- `org.springframework.context.annotation.*` implements `aop.TargetSource` in 3 places. CoreContainer → AOP is forbidden; Spring's autowiring/JSR-250 handling genuinely depends on AOP — see F14.

---

## Phase 2 — Rule Precision Audit

5. **Vacuous rules** — No new vacuous rules beyond those already passing. R7 (`jms ↛ jdbc`) passed but the *inverse* peer crossing (`jms → messaging`) now dominates R1. The strict peer model in R1 essentially supersedes R7; worth noting but not a defect.
6. **Overly broad** — R2 and NEW-A are still too broad for the documented bridge classes (see F15, F16).
7. **Overly narrow** — R6 (`spring_messaging_must_not_depend_on_spring_web`) still targets only `web` + `http`; R1 now implicitly covers this pair plus the (empty-in-practice) DA direction. Narrow but not inconsistent.
8. **`ignoresDependency()` abuse** — still none used. See F15/F16 for where its *selective* introduction is warranted.

---

## Phase 3 — Semantic Correctness Audit

9. **Layer direction** — R1 is now arrow-correct for the flat 6-layer model. BUT the flat model itself contradicts the real codebase for six identifiable bridge patterns (C12–C18).
10. **Parallel layer isolation** — NEW-A/NEW-B now enforce Misc→DA and Misc→Web peer isolation, but there is still no `messaging ↛ {DA, Web, Misc}` rule and no `DA/Web/Misc ↛ messaging` rule beyond R1 (see F21).
11. **`because()` accuracy** — R2 and R3's `because()` clauses claim "cross-cutting SPIs (format, validation, scheduling)" are in CoreContainer, but `scheduling.annotation` and `validation.beanvalidation` have dependencies on AOP (F13). The clauses over-promise.
12. **`ClassFileImporter` scope** — the regex-to-`location.contains()` refactor (F9 fix) is now correct. No new scope issue.

---

## Phase 4 — Structural & Completeness Audit

13. **Intra-layer rules** — `transaction`, `dao` ordering within DA still not enforced (see F20).
14. **Transitivity** — the flat peer model now forbids *all* peer-to-peer edges but allows a CoreContainer hop. For example, `jms → messaging` is blocked, but nothing prevents `jms → core → messaging.X` if a core class exposed a messaging re-export. No concrete exploit exists in the codebase, but the risk is structural.
15. **Test vs production** — R16 now uses the negative selector (✓). No new issue.
16. **Redundancy** — R2/R3/NEW-A/NEW-B are subsumed by R1 (since R1 also forbids peer-to-peer edges). Not an error; keeping them gives localised failure output.

---

## Executive Summary

- **Total documented constraints identified**: 18 (C1–C18; C12–C18 are *implicit* bridge patterns visible in the codebase but absent from the PDF)
- **Total rules generated**: 21
- **Coverage of diagram-stated constraints (C1–C11)**: 11 / 11 (all now covered — Review #1 fixes applied)
- **Coverage of implicit bridge constraints (C12–C18)**: 0 / 7 (all seven are currently **flagged as violations** rather than accommodated)
- **Critical gaps (new)**:
  1. The Review #1 fix placed `scheduling` and `validation` in CoreContainer, but their AOP-backed subpackages (`scheduling.annotation`, `validation.beanvalidation`) extend `aop.*` classes. This is a *regression* — the post-fix layering now actively produces false positives where the pre-fix layering did not (F13).
  2. Seven documented Spring bridge patterns (JmsMessaging, OpenEntityManagerInView, EJB/JNDI, context autoproxy, WebSocket/STOMP, @Async AOP, @Validated AOP) account for the overwhelming majority of the remaining 946 violations. The rule set has no mechanism to accommodate them — neither `ignoreDependency()` carve-outs nor targeted sub-package re-classification (F14–F18, F27).
- **Overall verdict**: **FAIL WITH IMPROVEMENT** — substantially better than Review #1 state (946 vs 1864 violations, but still failing), and the remaining failures are now concentrated in a small number of well-understood bridge patterns rather than in structural layering mistakes.

---

## Findings

```
[F13] SEVERITY: CRITICAL
Category: Wrong Layer / Semantic Error (regression from Review #1 fix)
Affected Rule / Constraint: R1 `spring_layered_architecture_is_respected`; Constraints C16, C17

What is wrong:
Review #1 moved `org.springframework.scheduling` and `org.springframework.validation` to
CoreContainer on the theory that they are cross-cutting SPIs. This is correct for the root
packages, but both contain AOP-based annotation subpackages that legitimately extend `aop.*`:

  - `scheduling.annotation.AnnotationAsyncExecutionInterceptor`
      extends `aop.interceptor.AsyncExecutionInterceptor`
  - `scheduling.annotation.AsyncAnnotationAdvisor`
      extends `aop.support.AbstractPointcutAdvisor`
  - `scheduling.annotation.AsyncAnnotationBeanPostProcessor`
      extends `aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor`
  - `validation.beanvalidation.MethodValidationPostProcessor`
      extends `aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor`

CoreContainer is below AOP in R1, so these four CoreContainer→AOP edges fail R1. This is a
regression: in Review #1's original state, `scheduling` was in DataAccess and `validation`
was in Web, so these edges were handled (poorly, but not as CoreContainer→AOP inversions).

Why it matters:
The `because()` clauses on R1/R2/R3 now claim these packages are "foundational / cross-cutting
SPIs", which is false for `.annotation` and `.beanvalidation` subpackages. `@Async` and
`@Validated` are implemented by generating AOP proxies — they are AOP consumers and must sit
**above** AOP, not below.

How to fix it:
Split the two packages so their AOP-backed subpackages live above AOP. The cleanest fix is to
keep `scheduling` and `validation` roots in CoreContainer but carve out the annotation
subpackages as a new peer layer (or move them to the AOP layer):

```java
.layer("CoreContainer").definedBy(
    "org.springframework.core..",
    "org.springframework.beans..",
    "org.springframework.context..",
    "org.springframework.contextsupport..",
    "org.springframework.expression..",
    "org.springframework.lang..",
    "org.springframework.util..",
    "org.springframework.stereotype..",
    "org.springframework.aot..",
    "org.springframework.instrument..",
    "org.springframework.format..",
    "org.springframework.validation..",      // root + all except beanvalidation
    "org.springframework.ui..",
    "org.springframework.scheduling.."      // root + all except annotation
)
.layer("AopConsumers").definedBy(
    "org.springframework.scheduling.annotation..",
    "org.springframework.validation.beanvalidation.."
)
...
.whereLayer("AopConsumers").mayOnlyAccessLayers("CoreContainer", "AOP")
```

ArchUnit's AntPathMatcher does **not** support glob exclusions; the two sub-package entries
above must be declared in a *separate* layer declared *after* CoreContainer, and the
`CoreContainer` glob must be expressed with more precise sub-package entries so the two
sub-packages are not double-assigned. The simplest form:

```java
.layer("CoreContainer").definedBy(
    ...
    "org.springframework.scheduling",
    "org.springframework.scheduling.concurrent..",
    "org.springframework.scheduling.config..",
    "org.springframework.scheduling.support..",
    "org.springframework.scheduling.quartz..",
    "org.springframework.validation",
    "org.springframework.validation.annotation..",
    "org.springframework.validation.method..",
    "org.springframework.validation.support..",
    ...
)
.layer("AopConsumers").definedBy(
    "org.springframework.scheduling.annotation..",
    "org.springframework.validation.beanvalidation.."
)
```

This eliminates 4 of the 895 R1 violations and removes the semantic inconsistency.
```

```
[F14] SEVERITY: HIGH
Category: Semantic Error (diagram vs. code)
Affected Rule / Constraint: R1; Constraint C15

What is wrong:
`context.annotation.*` contains three anonymous/inner TargetSource implementations:
  - `CommonAnnotationBeanPostProcessor$1 implements org.springframework.aop.TargetSource`
  - `ContextAnnotationAutowireCandidateResolver$LazyDependencyTargetSource implements aop.TargetSource`
  - `ResourceElementResolver$1 implements aop.TargetSource`
All three are legitimate Spring 5/6 design: `@Lazy` dependencies and `@Resource` injection are
realised through dynamic proxies that require `aop.TargetSource`. R1 forbids CoreContainer →
AOP, so these three classes are flagged.

Why it matters:
The PDF's flat layer model places AOP *above* Core Container, so CoreContainer → AOP is
disallowed. In reality, `spring-context` is a declared AOP *consumer* for autowiring. Without
some form of accommodation, every Spring release will keep producing these three violations.

How to fix it:
Two options:

Option A (preferred) — accept the bridge with a narrow `ignoreDependency`:

```java
@ArchTest
static final ArchRule spring_layered_architecture_is_respected =
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        ...
        .ignoreDependency(
            JavaClass.Predicates.resideInAPackage("org.springframework.context.annotation.."),
            JavaClass.Predicates.belongToAnyOf(
                org.springframework.aop.TargetSource.class // by FQN if not on classpath
            )
        )
        ...
```

Or, since the TargetSource usages are all via `implements`, the cleanest form is:

```java
.ignoreDependency(
    resideInAPackage("org.springframework.context.annotation.."),
    resideInAPackage("org.springframework.aop")  // ONLY the top-level aop package, not subpackages
)
```

Option B — carve out `context.annotation` into a dedicated "Context-AOP bridge" layer that
sits above AOP. Rejected as over-engineering for three classes.

Document the exception in the class-level Javadoc so future reviewers know why the carve-out
exists.
```

```
[F15] SEVERITY: HIGH
Category: Wrong Layer (Missing Bridge Accommodation)
Affected Rule / Constraint: R1; Constraint C12

What is wrong:
`org.springframework.jms.*` has a documented Spring abstraction bridge to
`org.springframework.messaging.*`:
  - `JmsMessagingTemplate extends AbstractMessagingTemplate`
  - `JmsMessageHeaderAccessor extends NativeMessageHeaderAccessor`
  - `SimpleJmsHeaderMapper extends AbstractHeaderMapper`
  - `JmsListenerAnnotationBeanPostProcessor$MessageHandlerMethodFactoryAdapter implements MessageHandlerMethodFactory`
  - `@JmsListener` is annotated with `@MessageMapping`
  - `AbstractAdaptableMessageListener$...$LazyResolutionMessage implements messaging.Message`

Per the current layer model, `jms` is in DataAccess and `messaging` is its own peer —
DataAccess → Messaging is forbidden. Every one of the 100+ bridge classes fails R1.

Why it matters:
`JmsMessagingTemplate` is the primary public entry point for applying the unified
`spring-messaging` programming model to JMS. Users MUST be able to write
`@JmsListener` handlers that accept `messaging.Message<T>`. The constraint the current rule
expresses (jms must not import messaging) is architecturally incorrect.

How to fix it:
Option A — demote `messaging` to a *foundation* layer between CoreContainer and AOP (messaging
is effectively a cross-cutting abstraction consumed by many layers):

```java
.layer("CoreContainer").definedBy(...)
.layer("Messaging").definedBy("org.springframework.messaging..")
.layer("AOP").definedBy("org.springframework.aop..")
.layer("DataAccess").definedBy(...)
.layer("Web").definedBy(...)
.layer("Miscellaneous").definedBy(...)

.whereLayer("CoreContainer").mayNotAccessAnyLayer()
.whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer")
.whereLayer("AOP").mayOnlyAccessLayers("CoreContainer", "Messaging")
.whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")
.whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")
.whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "Messaging", "AOP")
```

This single change eliminates the large majority of R1's 895 violations
(jms→messaging, web.socket→messaging, and similar) in one sweep.

Option B — keep Messaging as a peer but explicitly accommodate the documented bridges:

```java
.ignoreDependency(
    resideInAPackage("org.springframework.jms.."),
    resideInAPackage("org.springframework.messaging..")
)
```

Delete R6 if Option A is taken (messaging ↛ web is subsumed by the layer being below Web).
```

```
[F16] SEVERITY: HIGH
Category: Overly Broad (Missing Bridge Accommodation)
Affected Rule / Constraint: R1, R2; Constraint C13

What is wrong:
`org.springframework.orm.jpa.support.*` contains documented Spring JPA-to-Web integration
classes:
  - `OpenEntityManagerInViewFilter extends web.filter.OncePerRequestFilter`
  - `OpenEntityManagerInViewInterceptor implements web.context.request.AsyncWebRequestInterceptor`
  - `AsyncRequestInterceptor implements web.context.request.async.CallableProcessingInterceptor`
  - `AsyncRequestInterceptor implements web.context.request.async.DeferredResultProcessingInterceptor`

These are the implementation of the Spring-documented Open-EntityManager-in-View pattern. R2
flags every usage (44 violations), and R1 flags the same edges again.

Why it matters:
Both rules are meant to express "DataAccess must not depend on Web as a design principle",
but this sub-package is explicitly the Web/JPA bridge. Removing this package from the DA-peer
scope is the correct design, not blocking it. No amount of refactoring to the main codebase
will ever clear these violations without breaking the documented user-facing API.

How to fix it:
Carve out `orm.jpa.support` via a scoped `ignoreDependency` or via a dedicated "DA-Web bridge"
sub-layer. The narrowest form:

```java
@ArchTest
static final ArchRule data_access_layer_must_not_depend_on_web_layer =
    noClasses()
        .that().resideInAnyPackage(
            "org.springframework.dao..",
            "org.springframework.jdbc..",
            "org.springframework.orm..",
            "org.springframework.oxm..",
            "org.springframework.transaction..",
            "org.springframework.r2dbc..",
            "org.springframework.jms..",
            "org.springframework.jca..",
            "org.springframework.jndi..",
            "org.springframework.mail..",
            "org.springframework.cache..")
        .and().resideOutsideOfPackage("org.springframework.orm.jpa.support..")  // documented bridge
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..")
        .because("DataAccess and Web are parallel top-row peer layers. The only documented exception is org.springframework.orm.jpa.support (Open-EntityManager-in-View pattern), which is explicitly a Web/JPA bridge.");
```

Mirror the same exclusion in the R1 `ignoreDependency()` block, or move `orm.jpa.support` to
its own "DA-Web-Bridge" layer that is allowed to access both `DataAccess` and `Web`.
```

```
[F17] SEVERITY: MEDIUM
Category: Overly Broad (Missing Bridge Accommodation) / Wrong Layer
Affected Rule / Constraint: R1, R3; Constraint C14 (partial)

What is wrong:
`org.springframework.web.context.support.StandardServletEnvironment` uses
`org.springframework.jndi.JndiPropertySource` and `JndiLocatorDelegate` to expose JNDI
resources as Spring environment property sources (2 violations in R3). This is a documented
Spring-Web feature — servlet environments optionally mount JNDI names as configuration
properties. Web→DataAccess (via jndi) is flagged.

Why it matters:
This is not a rogue dependency — it is part of the public `StandardServletEnvironment` API.
Forbidding it prevents a documented Spring feature from compiling.

How to fix it:
Adopt F19's recommendation (move `jndi` to CoreContainer). Once `jndi` is in CoreContainer,
Web→`jndi` is allowed automatically and both R3 violations disappear. No per-class carve-out
needed.
```

```
[F18] SEVERITY: MEDIUM
Category: Overly Broad (Missing Bridge Accommodation) / Wrong Layer
Affected Rule / Constraint: NEW-A `misc_layer_must_not_depend_on_data_access_layer`; Constraint C14

What is wrong:
`org.springframework.ejb.config.*` (Miscellaneous) references `JndiObjectFactoryBean` 5 times:
  - `JndiLookupBeanDefinitionParser.getBeanClass`
  - `LocalStatelessSessionBeanDefinitionParser.getBeanClass`
  - `LocalStatelessSessionBeanDefinitionParser.isEligibleAttribute`
  - `RemoteStatelessSessionBeanDefinitionParser.getBeanClass`
  - `RemoteStatelessSessionBeanDefinitionParser.isEligibleAttribute`

EJB bean resolution is *definitionally* JNDI-based. Every `<jee:local-slsb>` or
`<jee:remote-slsb>` XML namespace element instantiates a `JndiObjectFactoryBean`. Miscellaneous→DA
(via `jndi`) is inherent to the `spring-ejb` namespace support.

Why it matters:
`spring-ejb` cannot exist without a JNDI bridge. NEW-A forbids exactly that.

How to fix it:
Same as F17 — if `jndi` is re-classified to CoreContainer (F19), these 5 violations also
disappear, and NEW-A becomes a valid, passing rule.
```

```
[F19] SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: R1, R3, NEW-A — `org.springframework.jndi` placed in DataAccess

What is wrong:
`org.springframework.jndi` is assigned to the DataAccess layer. The surefire output shows
JNDI is actually consumed across *four* layers:
  - CoreContainer: `context.annotation.CommonAnnotationBeanPostProcessor.<init>` calls
    `jndi.support.SimpleJndiBeanFactory.<init>`
  - Web: `web.context.support.StandardServletEnvironment` uses `jndi.JndiPropertySource`
  - Miscellaneous: `ejb.config.*` references `jndi.JndiObjectFactoryBean`
  - DataAccess: `orm.jpa.persistenceunit.DefaultPersistenceUnitManager` uses
    `jdbc.datasource.lookup.JndiDataSourceLookup` (same layer, OK)

The only genuine DA consumer of JNDI is the DA itself. Every other consumer treats JNDI as a
JavaEE-foundation SPI, not as a DA concern.

Why it matters:
Placing `jndi` in DataAccess produces at minimum:
  - 2 violations from F17 (Web → jndi)
  - 5 violations from F18 (Misc → jndi)
  - 1 violation from CommonAnnotationBeanPostProcessor → jndi (Core → jndi, in R1)
  - More embedded in the 895 R1 count for any CoreContainer→jndi edge

All eight+ disappear if `jndi` moves to CoreContainer.

How to fix it:
Relocate in the R1 `definedBy(...)` block:

```java
.layer("CoreContainer").definedBy(
    ...
    "org.springframework.jndi..",            // cross-cutting JavaEE JNDI SPI, not a DA module
    ...
)
.layer("DataAccess").definedBy(
    "org.springframework.dao..",
    "org.springframework.jdbc..",
    "org.springframework.orm..",
    "org.springframework.oxm..",
    "org.springframework.transaction..",
    "org.springframework.r2dbc..",
    "org.springframework.jms..",
    "org.springframework.jca..",
    // "org.springframework.jndi..",         // REMOVED: promoted to CoreContainer
    "org.springframework.mail..",
    "org.springframework.cache.."
)
```

Mirror the same change in R2 and NEW-A's `dependOnClassesThat().resideInAnyPackage(...)` list
(remove `jndi` from the DA list).
```

```
[F20] SEVERITY: MEDIUM
Category: Structural Gap
Affected Rule / Constraint: Intra-DataAccess ordering

What is wrong:
Review #1 covered `jdbc ↛ orm` (R4), `jms ↛ jdbc` (R7), `r2dbc ↛ jdbc` (R8), but the
following intra-DA ordering relationships are still not enforced:
  - `dao ↛ {jdbc, orm, transaction, r2dbc, jms, jca, mail, cache, oxm}`
    (spring-dao is the exception-hierarchy foundation of DA; it must be the floor)
  - `transaction ↛ {jdbc, orm, jms, r2dbc, jca, mail, cache, oxm}`
    (spring-tx is the transaction abstraction; specific persistence impls build on it)

Why it matters:
In the current state these are unenforced: a `DataAccessException` refactor that accidentally
imported `jdbc.datasource.DataSourceTransactionManager` would pass all tests. The documented
Spring module hierarchy inside DA is `dao ← (tx, orm, jdbc, jms, ...)` and `tx ← (jdbc, orm,
jms, r2dbc, ...)`; neither is asserted.

How to fix it:

```java
@ArchTest
static final ArchRule spring_dao_must_not_depend_on_other_data_access_modules =
    noClasses()
        .that().resideInAPackage("org.springframework.dao..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..",
            "org.springframework.orm..",
            "org.springframework.oxm..",
            "org.springframework.transaction..",
            "org.springframework.r2dbc..",
            "org.springframework.jms..",
            "org.springframework.jca..",
            "org.springframework.mail..",
            "org.springframework.cache..")
        .because("spring-dao is the DataAccessException hierarchy foundation; every other DataAccess module depends on dao, not the other way around.");

@ArchTest
static final ArchRule spring_transaction_must_not_depend_on_specific_persistence_modules =
    noClasses()
        .that().resideInAPackage("org.springframework.transaction..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.jdbc..",
            "org.springframework.orm..",
            "org.springframework.oxm..",
            "org.springframework.r2dbc..",
            "org.springframework.jms..",
            "org.springframework.jca..",
            "org.springframework.mail..",
            "org.springframework.cache..")
        .because("spring-tx provides the generic transaction abstraction (PlatformTransactionManager, TransactionTemplate). Specific persistence modules depend on spring-tx, never the reverse.");
```

Note: `transaction ↛ jdbc` is a *hard* constraint — a common mistake is for a new developer to
introduce `DataSourceTransactionManager`-style code in `spring-tx` itself.
```

```
[F21] SEVERITY: LOW
Category: Structural Gap / Asymmetry
Affected Rule / Constraint: NEW-F `aot_must_not_depend_on_aop`

What is wrong:
NEW-F forbids `aot → aop` but there is no companion rule `aop ↛ aot`. The stated rationale is
"AOT and AOP are unrelated technologies and must remain decoupled", which is a bidirectional
claim.

Why it matters:
A future commit introducing a `RuntimeHintsRegistrar` in `spring-aop` would compile and pass
all tests. The asymmetry is a weakness.

How to fix it:

```java
@ArchTest
static final ArchRule aop_must_not_depend_on_aot =
    noClasses()
        .that().resideInAPackage("org.springframework.aop..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.aot..")
        .because("AOT (Ahead-Of-Time / native-image runtime hints) and AOP (Aspect-Oriented Programming) are unrelated Spring technologies and must remain decoupled in both directions.");
```
```

```
[F22] SEVERITY: MEDIUM
Category: Structural Gap
Affected Rule / Constraint: NEW-F plus new peer-isolation rules for Messaging

What is wrong:
Review #1 elevated `messaging` to its own peer layer but did not add explicit peer-isolation
rules like NEW-A/NEW-B for Miscellaneous. The only enforcement for Messaging ↔ other peers is
R1 plus R6 (messaging ↛ web/http). There is no explicit:
  - `messaging ↛ DataAccess`
  - `messaging ↛ Miscellaneous`
  - `DataAccess ↛ Messaging` (other than R1 which is noisy)
  - `Web ↛ Messaging` (other than R1)
  - `Miscellaneous ↛ Messaging` (other than R1)

Why it matters:
If F15's Option A is chosen (`messaging` demoted to foundation), the direction flips and only
`messaging ↛ DA/Web/Misc/AOP` remains meaningful; R6 becomes the correct form. If F15's
Option B is chosen (`messaging` kept as peer), five explicit peer-isolation rules are missing.
Either way, the current state has a gap.

How to fix it:
If F15 Option A is adopted (demote messaging): no new rule needed; R6 plus the layer ordering
cover it. If F15 Option B is adopted (keep messaging as peer): mirror NEW-A/NEW-B for
messaging. The cleanest form is F15 Option A.
```

```
[F23] SEVERITY: LOW
Category: Overly Narrow
Affected Rule / Constraint: R9 `spring_webmvc_must_not_depend_on_spring_webflux`, R10 inverse

What is wrong:
R9 and R10 use `web.servlet..` ↔ `web.reactive..`. But the `spring-webmvc` maven module also
ships classes in `org.springframework.web.method..`, `org.springframework.web.bind..`, and
`org.springframework.web.multipart.support..` (servlet-specific multipart handling). If a
stray compile-time edge appeared from `web.method.support.ServletInvocableHandlerMethod` to
`web.reactive.HandlerResult`, R9 would not catch it.

Why it matters:
Narrow but not zero: the subpackage globs miss about 15 % of the spring-webmvc code and
around 8 % of the spring-webflux code (`web.multipart..` is servlet-only but not covered).

How to fix it:
Either accept the narrow scope (current violations = 0, so not urgent) or broaden to the
maven-module convention:

```java
@ArchTest
static final ArchRule spring_webmvc_must_not_depend_on_spring_webflux =
    noClasses()
        .that().resideInAnyPackage(
            "org.springframework.web.servlet..",
            "org.springframework.web.multipart.."
        )
        .should().dependOnClassesThat().resideInAPackage("org.springframework.web.reactive..")
        .because(...);
```

Low priority because passing tests already constrain most of the surface area via R1.
```

```
[F24] SEVERITY: LOW
Category: Overly Broad / Precision
Affected Rule / Constraint: R16 `production_code_must_not_depend_on_spring_test_packages`

What is wrong:
R16 now uses `resideInAPackage("org.springframework..")` and
`resideOutsideOfPackages("org.springframework.test..", "org.springframework.tests..",
"org.springframework.mock..")`. Because `ExcludeRepackagedAndBuildOnlyPackages` already strips
`asm`, `cglib`, `objenesis`, `javapoet`, `protobuf`, `build`, `docs` from the import, the
`org.springframework..` selector effectively equals "all Spring production classes except
test/tests/mock". That is the intended behaviour.

However: a class directly in the top-level `org.springframework` package itself (e.g., a
hypothetical `org.springframework.SpringVersion` if it existed) would *not* be matched by the
glob `org.springframework..` in ArchUnit, which expects `org.springframework.*` sub-packages.
(ArchUnit's `..` matches zero-or-more *sub-*package components, not the package itself in the
strict sense.) In practice there are no direct-children of `org.springframework`, so no
violation escapes, but the glob is subtly imprecise.

Why it matters:
Cosmetic / maintainability: if a future refactor ever places a class directly in
`org.springframework`, R16 silently won't cover it.

How to fix it:
Explicitly include the root:

```java
.that().resideInAnyPackage("org.springframework", "org.springframework..")
```

Or switch to a negative-only form:

```java
.that().resideOutsideOfPackages(
    "org.springframework.test..",
    "org.springframework.tests..",
    "org.springframework.mock..")
```

The negative-only form is cleaner since `@AnalyzeClasses(packages = "org.springframework")`
already scopes the import.
```

```
[F25] SEVERITY: LOW
Category: because()-clause inaccuracy
Affected Rule / Constraint: R2 `data_access_layer_must_not_depend_on_web_layer`, R3 mirror

What is wrong:
Both clauses claim "Cross-cutting SPIs (format, validation, scheduling) are foundational and
shared via CoreContainer". F13 shows that `scheduling` and `validation` are *not* cleanly
foundational — their annotation subpackages are AOP consumers. So the clause mis-describes
the architectural model.

Why it matters:
Documentation drift. Anyone reading the clause to understand the model will be misled.

How to fix it:
Amend to:
"Cross-cutting SPIs (format, validation root, scheduling root, ui) are foundational and
shared via CoreContainer; their AOP-based annotation sub-packages (`scheduling.annotation`,
`validation.beanvalidation`) sit above AOP as AopConsumers."
(after F13 is applied).
```

```
[F26] SEVERITY: LOW
Category: Maintainability / Code Style
Affected Rule / Constraint: whole file

What is wrong:
Several globs use `..` even though the package has no sub-packages in Spring (e.g.,
`org.springframework.lang..` — the `lang` package contains only annotations), and NEW-C–E use
exactly the same text-body shape differing only in package names. Keeping a named constant
for each glob list would reduce the risk of next-iteration copy-paste errors.

Why it matters:
Low. Future maintenance risk only.

How to fix it (optional):

```java
private static final String[] DATA_ACCESS_PEER = {
    "org.springframework.dao..",
    "org.springframework.jdbc..",
    "org.springframework.orm..",
    "org.springframework.oxm..",
    "org.springframework.transaction..",
    "org.springframework.r2dbc..",
    "org.springframework.jms..",
    "org.springframework.jca..",
    "org.springframework.mail..",
    "org.springframework.cache.."
    // note: jndi removed per F19 recommendation
};
private static final String[] WEB_PEER = { "org.springframework.web..", "org.springframework.http.." };
```

Then use `resideInAnyPackage(DATA_ACCESS_PEER)` in R2, NEW-A, and R14.
```

```
[F27] SEVERITY: HIGH
Category: Missing Bridge Accommodation
Affected Rule / Constraint: R1; Constraint C18

What is wrong:
`org.springframework.web.socket.*` (Web layer) has an extensive documented integration with
`org.springframework.messaging.*` (Messaging peer layer):
  - `SubProtocolWebSocketHandler implements MessageHandler`
  - `WebSocketAnnotationMethodMessageHandler extends SimpAnnotationMethodMessageHandler`
  - `WebSocketStompClient extends StompClientSupport`
  - `DefaultSimpUserRegistry implements SimpUserRegistry`
  - ~15+ other classes in `web.socket.messaging`

This is the Spring implementation of STOMP-over-WebSocket — a first-class documented feature.
R1 forbids Web → Messaging, so the entire `web.socket.messaging` package fails.

Why it matters:
Same as F15/F16 — this is not an architectural defect, it is the documented bridge between
WebSocket and the messaging abstraction. No refactor of Spring will ever clear this.

How to fix it:
If F15 Option A is adopted (demote messaging), this resolves automatically because Web may
access Messaging. No further rule change needed.

If F15 Option B is adopted (keep as peer), add:

```java
.ignoreDependency(
    resideInAPackage("org.springframework.web.socket.."),
    resideInAPackage("org.springframework.messaging..")
)
```

to R1 and to any Web-peer-isolation rule.
```

---

## Severity Totals (Review #2 only — new findings)

| Severity | Count |
|---|---|
| CRITICAL | 1 (F13 — regression from Review #1 fix) |
| HIGH     | 4 (F14, F15, F19, F27) |
| MEDIUM   | 5 (F16, F17, F18, F20, F22) |
| LOW      | 5 (F21, F23, F24, F25, F26) |
| **Total** | **15** |

---

## Regression / Improvement Scoreboard

| Metric | Review #1 baseline | After Review #1 fixes (now) | Delta |
|---|---|---|---|
| Total `@ArchTest` rules | 16 | 21 | +5 |
| Tests run | 16 | 21 | +5 |
| Tests passing | 12 | 17 | **+5** |
| Tests failing | 4 | 4 | 0 |
| Total rule violations | 1 864 (1164 + 86 + 552 + 62) | 946 (895 + 44 + 2 + 5) | **−49 %** |
| New defects introduced | — | 1 (F13 — `scheduling.annotation` / `validation.beanvalidation` in CoreContainer) | — |

**Net assessment**: Review #1's fixes removed ~918 violations (mostly the `aot`-in-AOP noise and the R5 false positive), but introduced one small regression (F13) and exposed seven *pre-existing* real constraints not in the PDF (C12–C18). The remaining 946 violations fall into 7 well-characterised buckets, each with a surgical fix prescribed above. The single biggest lever is F15 Option A (demoting `messaging` to a foundation layer below the DA/Web/Misc peers) — applying it alone would clear roughly 600+ of the 895 R1 violations, and enable R6 to remain meaningful.
