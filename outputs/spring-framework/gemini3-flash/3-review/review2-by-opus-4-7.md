# Adversarial Review of Generated ArchUnit Rules — spring-framework / gemini3-flash

Reviewer: opus-4-7
Review: #2

---

## Executive Summary

- **Total documented constraints identified:** 18 (6 layer-hierarchy constraints + 9 package-placement constraints + 3 intra-layer prohibitions — same inventory as Review #1)
- **Total rules generated:** 10 (1 `layeredArchitecture` + 1 mapping-completeness guard + 8 `noClasses`/`slices` rules)
- **Coverage rate:** 14 / 18 constraints have a corresponding rule
- **Test harness status:** 10 rules execute against **9,000+ production classes** (6.478 s — Review #1's classpath blocker is fixed).
- **Test outcome:** 6 pass / **4 fail**:
  1. `layered_architecture_is_respected` — **627 violations**
  2. `web_and_dataaccess_are_isolated` — **17 violations**
  3. `r2dbc_does_not_know_about_siblings` — **0 classes checked (vacuous rule)**
  4. `misc_services_do_not_depend_on_each_other` — **3 slice violations**

- **Critical Gaps in the architectural *model*:**
  - G-1: `AopInstrumentation` layer is placed *above* `CoreContainer`, but real Spring has `beans`/`context` routinely implementing/depending on `aop`, `aot`, and `instrument` types (`BeanUtilsRuntimeHints implements RuntimeHintsRegistrar`, `DefaultContextLoadTimeWeaver implements LoadTimeWeaver`, `CommonAnnotationBeanPostProcessor$1 implements TargetSource`, …). The 627 violations are not architectural debt in Spring — they are architectural debt in the generated rule.
  - G-2: The `oxm` package is *intentionally* consumed by `http.converter.xml` and `web.servlet.view.xml` (17 violations). The parallel-layer rule wrongly forbids it.
  - G-3: `validation.support.BindingAwareConcurrentModel` extends `ui.ConcurrentModel` and `BindingAwareModelMap` extends `ui.ExtendedModelMap` — `validation` (currently CoreContainer) directly extends Web classes. The layering misclassifies either `validation.support` or the whole `ui` package.
  - G-4: `orm.hibernate5.support.OpenSessionInViewFilter` / `orm.jpa.support.OpenEntityManagerInViewFilter` — DataAccess classes that inherit from `web.filter.OncePerRequestFilter`. The rule's "DataAccess may not access Web" is correct *in principle* but the documented Open-Session-In-View pattern explicitly requires the reverse — the rule needs a surgical exception, not blanket silence.
  - G-5: `misc_services_do_not_depend_on_each_other` ignores documented directional dependencies: `spring-jms` is **designed** to build on `spring-messaging`; `StompBrokerRelay` is **designed** to use `spring-scheduling`. Forbidding all cross-dependencies contradicts Spring's own module DAG.
  - G-6: `r2dbc` is not on the runtime classpath, making its sibling-isolation rule vacuous (fails with "failed to check any classes"). Either add the module or mark `.allowEmptyShould(true)`.

- **Overall verdict:** **FAIL**

The rules now *actually execute*, but the execution exposes that the architectural model itself is wrong. Multiple rules produce either systematic false positives (627 layered + 17 web/oxm + 3 slice) or vacuous passes (r2dbc). Fixing the remaining findings requires re-drawing layer boundaries, not just tweaking globs.

---

## Delta from Review #1

| Item from Review #1                         | Status in Review #2                              |
|---------------------------------------------|--------------------------------------------------|
| F-1 Empty classpath                         | Fixed — harness imports real production classes |
| F-2 Unmapped packages                       | Fixed — `no_unmapped_spring_packages` now guards, and test passes |
| F-3 AOP layer direction inverted            | **Still broken** — now producing 627 concrete violations (see F-1 below) |
| F-4 `validation` mis-classified             | **Partially worse** — validation's CoreContainer placement now collides with `ui` (Web) via `BindingAwareConcurrentModel` (see F-3 below) |
| F-5 Web ⟂ DataAccess too narrow             | Fixed — but over-corrected by including `oxm` (see F-2 below) |
| F-6 Missing intra-CoreContainer rules       | Fixed — `core_is_standalone`, `beans_is_below_context`, `expression_is_leaf_within_core_container` all pass |
| F-7 aop→dataaccess redundant                | Rewritten as `transaction_abstraction_is_pure` etc. — passes |
| F-8 Web/MiscServices ordering               | Rule updated (MiscServices now top), but documentation and rule still telegraph different orderings |
| F-9 Missing DataAccess intra-layer          | Partially fixed — `r2dbc` rule is vacuous |
| F-10 MiscServices pluggability              | Over-enforced — misses documented directional dependencies (see F-5 below) |

---

## Findings

### F-1
```
SEVERITY: CRITICAL
Category: Wrong Layer / Semantic Error
Affected Rule / Constraint: layered_architecture_is_respected
```

**What is wrong:**
The rule places `AopInstrumentation` (aop + aot + instrument) *above* `CoreContainer`, meaning CoreContainer classes may not reach into it. Runtime shows this assumption to be systematically false — **627 violations**, all stemming from the same four mis-classifications:

| Mis-placed package | Sample violation | Correct home |
|--------------------|------------------|--------------|
| `org.springframework.aot..` | `beans.BeanUtilsRuntimeHints implements aot.hint.RuntimeHintsRegistrar`; `beans.factory.aot.InstanceSupplierCodeGenerator has parameter of type aot.generate.GenerationContext` (hundreds of occurrences) | `spring-core` physically ships the `org.springframework.aot` package. It is a *dependency of* CoreContainer, not a consumer. |
| `org.springframework.instrument..` | `context.weaving.DefaultContextLoadTimeWeaver implements instrument.classloading.LoadTimeWeaver`; `context.weaving.LoadTimeWeaverAwareProcessor` uses `instrument.classloading.LoadTimeWeaver` | Load-time weaving is designed as a *plug-in SPI* invoked by `spring-context`. |
| `org.springframework.aop..` (from CoreContainer consumers) | `CommonAnnotationBeanPostProcessor$1 implements aop.TargetSource`; `ContextAnnotationAutowireCandidateResolver$1 implements aop.TargetSource`; `scripting.support.RefreshableScriptTargetSource extends aop.target.dynamic.BeanFactoryRefreshableTargetSource`; `validation.beanvalidation.MethodValidationPostProcessor extends aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor`; `jndi.JndiObjectTargetSource implements aop.TargetSource` | `spring-aop` has always sat *between* `spring-beans` and `spring-context`. `spring-context` has a hard compile-time dependency on `spring-aop`. |
| `org.springframework.scripting..`, `org.springframework.jndi..` (CoreContainer members that use aop) | see above — every `scripting`/`jndi` bridge to `aop` | Either both should move to an `AopConsumers` sub-layer above AopInstrumentation, or `aop` needs to be below `context`. |

The rule's `.because("strict downward dependency flow")` clause is therefore false: Spring's own documented flow is `util` → `core+aot` → `beans` → `aop+instrument` → `context` (+ `scripting`, `jndi`, `format`, `validation`).

**Why it matters:**
Every one of the 627 violations is a first-class, documented Spring design pattern (runtime hints, AOT generation, post-processors using AOP proxies, JNDI target sources, load-time weaving, bean-validation advisors). The generated rule would require the Spring team to rewrite 627 production use sites to pass CI. In practice this means the rule will be disabled on day one.

**How to fix it:**
Redraw the hierarchy so AOP-family packages are reachable by CoreContainer, and split CoreContainer into `core/beans` (below AOP) and `context` (above AOP). Concrete patch:

```java
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
        "org.springframework.validation..")  // see F-3 for validation caveat
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
    .whereLayer("Context").mayOnlyBeAccessedByLayers(
        "DataAccess", "Web", "MiscServices")
    .whereLayer("Aop").mayOnlyBeAccessedByLayers(
        "Context", "DataAccess", "Web", "MiscServices")
    .whereLayer("Beans").mayOnlyBeAccessedByLayers(
        "Aop", "Context", "DataAccess", "Web", "MiscServices")
    .whereLayer("Core").mayOnlyBeAccessedByLayers(
        "Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")
    .whereLayer("BaseUtilities").mayOnlyBeAccessedByLayers(
        "Core", "Beans", "Aop", "Context", "DataAccess", "Web", "MiscServices")
    .because("Spring's canonical layering is util → core+aot → beans → aop+instrument → context → data/web/misc.");
```

---

### F-2
```
SEVERITY: HIGH
Category: Overly Broad
Affected Rule / Constraint: web_and_dataaccess_are_isolated
```

**What is wrong:**
The rule forbids `web..`/`http..`/`ui..` → `dao`/`jdbc`/`orm`/`r2dbc`/`oxm`. Runtime shows 17 violations, **every single one of which involves `oxm`**:

- `http.converter.xml.MarshallingHttpMessageConverter` uses `oxm.Marshaller`/`oxm.Unmarshaller` as its entire reason for existing.
- `web.servlet.view.xml.MarshallingView` uses `oxm.Marshaller` for view rendering.

These are by-design integration points shipped inside `spring-web` and `spring-webmvc`. They are not architectural violations — they are the *documented* way to emit/consume XML over HTTP.

**Why it matters:**
`oxm` was inherited from Review #1's recommendation where I grouped it under DataAccess alongside ORM. That placement is defensible (marshalling is a persistence/serialization concern), but `http.converter.xml.*` and `web.servlet.view.xml.*` must be allowed to depend on it. Net effect: the rule produces 17 false positives on production classes, none of which can be relocated.

**How to fix it:**
Either (a) move `oxm` out of the parallel-isolation target list, or (b) explicitly exempt the XML integration sub-packages. Option (a) is simpler and matches the docs:

```java
@ArchTest
public static final ArchRule web_and_dataaccess_are_isolated = noClasses()
    .that().resideInAnyPackage("org.springframework.web..",
                               "org.springframework.http..",
                               "org.springframework.ui..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.dao..", "org.springframework.jdbc..",
        "org.springframework.orm..", "org.springframework.r2dbc..")
    .because("Web-layer components must reach relational/document persistence only through a service facade; XML marshalling via spring-oxm is an explicit exception.");
```

If stricter policing of `oxm` is desired, add a dedicated rule that exempts the two documented integration classes:

```java
@ArchTest
public static final ArchRule only_xml_converters_may_use_oxm_from_web = noClasses()
    .that().resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
    .and().resideOutsideOfPackages(
        "org.springframework.http.converter.xml..",
        "org.springframework.web.servlet.view.xml..")
    .should().dependOnClassesThat().resideInAPackage("org.springframework.oxm..")
    .because("Only the XML converter and view sub-packages may bridge HTTP to spring-oxm.");
```

---

### F-3
```
SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected (validation placement)
```

**What is wrong:**
`validation` is classified in CoreContainer, yet runtime shows:

- `validation.support.BindingAwareConcurrentModel extends ui.ConcurrentModel`
- `validation.support.BindingAwareModelMap extends ui.ExtendedModelMap`

`ui` lives in the Web layer. Layer direction forbids CoreContainer → Web, so these are violations — but the classes themselves are production code shipped in `spring-context`/`spring-web` integration. Either:

1. `validation.support..` belongs in the Web layer, or
2. `ui..` belongs in CoreContainer (unlikely — `ui` clearly contains MVC model types).

Spring's own module graph is closer to option (1) in spirit: `BindingAwareModelMap` is Web-MVC glue that happens to live under `org.springframework.validation.support` because it uses the validation `DataBinder`.

**Why it matters:**
These 3 violations cannot be resolved by relocating the classes (that would be a Spring refactor). They can only be silenced by fixing the layer definitions.

**How to fix it:**
Narrow `validation`'s layer membership so that `validation.support..` (the Web-MVC glue) is exempt:

```java
.layer("Context").definedBy(
    "org.springframework.context..",
    "org.springframework.expression..",
    "org.springframework.stereotype..",
    "org.springframework.format..",
    "org.springframework.scripting..",
    "org.springframework.jndi..",
    "org.springframework.ejb..",
    "org.springframework.contextsupport..",
    "org.springframework.validation..")
.layer("Web").definedBy(
    "org.springframework.web..", "org.springframework.http..",
    "org.springframework.ui..", "org.springframework.protobuf..",
    "org.springframework.validation.support..")  // <-- carve-out
```

Then reassert that everything in `org.springframework.validation..` *except* `validation.support..` sits in Context. (ArchUnit's layer definitions are order-insensitive, but the more-specific glob will take precedence only if it lists `validation.support..` first on the Web layer. Verify empirically.)

Alternative: mint a dedicated rule that whitelists just the documented extensions:

```java
@ArchTest
public static final ArchRule validation_is_below_web_except_support = noClasses()
    .that().resideInAPackage("org.springframework.validation..")
    .and().resideOutsideOfPackages("org.springframework.validation.support..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.web..", "org.springframework.ui..",
        "org.springframework.http..")
    .because("The validation API is Core; only validation.support is Web-MVC glue.");
```

---

### F-4
```
SEVERITY: HIGH
Category: Overly Broad / Wrong Layer
Affected Rule / Constraint: layered_architecture_is_respected (MiscServices may not be accessed by any layer)
```

**What is wrong:**
`mayNotBeAccessedByAnyLayer()` on MiscServices forbids any layer from depending on it. But the Web layer *intentionally* depends on `cache`:

- `web.reactive.config.ResourceChainRegistration` constructs `cache.concurrent.ConcurrentMapCache`
- `web.reactive.resource.CachingResourceResolver` takes `cache.CacheManager` / `cache.Cache`
- `web.servlet.resource.CachingResourceResolver`, `CachingResourceTransformer`, and `ResourceChainRegistration` — same pattern.

These lines show up in the 627 violations attributed to the layered rule. `spring-web` legitimately uses `spring-context-support` / `spring-context` cache abstractions for HTTP resource caching.

Additionally, MiscServices contains `messaging`, which DataAccess classes may in principle route through (e.g., transactional messaging). The blanket ban is too strict.

**Why it matters:**
Web/DataAccess → cache (and potentially → messaging, → scheduling) are documented integration patterns. Banning them breaks the build.

**How to fix it:**
Loosen the isolation and separate the truly-top layer:

```java
.whereLayer("MiscServices").mayOnlyBeAccessedByLayers("Web", "DataAccess")
```

Or split MiscServices into a `SharedInfra` sub-layer (cache, scheduling, messaging-core, jmx) that Web/DataAccess may freely consume, and an `EndOfChain` sub-layer (jms, mail) that nothing else may consume.

---

### F-5
```
SEVERITY: HIGH
Category: Overly Broad
Affected Rule / Constraint: misc_services_do_not_depend_on_each_other
```

**What is wrong:**
The rule `slices().matching("org.springframework.(jms|mail|messaging|scheduling|cache|jmx|resilience)..").should().notDependOnEachOther()` forbids *all* cross-slice dependencies. Runtime shows 3 directional violations that are *documented design*:

- **jms → messaging (62+ references).** `spring-jms` was explicitly refactored in Spring 4 to build on `spring-messaging`: `JmsMessagingTemplate extends AbstractMessagingTemplate`, `JmsMessageOperations extends MessageReceivingOperations / MessageSendingOperations / MessageRequestReplyOperations`, etc. The Spring reference documentation explicitly lists `spring-messaging` as a transitive dependency of `spring-jms`.
- **jms → scheduling (3 references).** `DefaultMessageListenerContainer$AsyncMessageListenerInvoker implements SchedulingAwareRunnable`, `initialize()` instanceof-checks `SchedulingTaskExecutor`. These are intentional.
- **messaging → scheduling (40+ references).** STOMP/SimpMessaging uses `TaskScheduler`/`ThreadPoolTaskScheduler`/`ThreadPoolTaskExecutor` for heartbeats and relay timeouts. Intentional.

**Why it matters:**
The "each Miscellaneous Service must be independently pluggable" rationale is simply wrong for Spring: the DAG within `spring-jms`/`spring-messaging`/`spring-scheduling` is part of the public API.

**How to fix it:**
Replace the slice rule with explicit one-directional prohibitions that encode the real DAG:

```java
// spring-jms is designed to build on spring-messaging; no rule needed for that edge.
// Forbid only the non-documented edges:

@ArchTest
public static final ArchRule mail_is_independent = noClasses()
    .that().resideInAPackage("org.springframework.mail..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jms..", "org.springframework.messaging..",
        "org.springframework.scheduling..", "org.springframework.cache..",
        "org.springframework.jmx..", "org.springframework.resilience..")
    .because("spring-mail is a leaf MiscServices module.");

@ArchTest
public static final ArchRule cache_is_independent = noClasses()
    .that().resideInAPackage("org.springframework.cache..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jms..", "org.springframework.mail..",
        "org.springframework.messaging..", "org.springframework.scheduling..",
        "org.springframework.jmx..", "org.springframework.resilience..")
    .because("spring-cache abstractions must stay resource-agnostic.");

@ArchTest
public static final ArchRule jmx_is_independent = noClasses()
    .that().resideInAPackage("org.springframework.jmx..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jms..", "org.springframework.mail..",
        "org.springframework.messaging..", "org.springframework.cache..",
        "org.springframework.resilience..")
    .because("spring-jmx must not pull in unrelated MiscServices.");

@ArchTest
public static final ArchRule resilience_is_independent = noClasses()
    .that().resideInAPackage("org.springframework.resilience..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jms..", "org.springframework.mail..",
        "org.springframework.messaging..", "org.springframework.cache..",
        "org.springframework.jmx..", "org.springframework.scheduling..")
    .because("spring-resilience is a leaf MiscServices module.");

// spring-scheduling is allowed to be consumed but must not depend on the higher MiscServices:
@ArchTest
public static final ArchRule scheduling_is_leaf_of_misc = noClasses()
    .that().resideInAPackage("org.springframework.scheduling..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jms..", "org.springframework.mail..",
        "org.springframework.messaging..", "org.springframework.cache..",
        "org.springframework.jmx..", "org.springframework.resilience..")
    .because("spring-scheduling must not depend on higher Misc services.");
```

---

### F-6
```
SEVERITY: HIGH
Category: Wrong Layer / Structural Gap
Affected Rule / Constraint: layered_architecture_is_respected (DataAccess → Web)
```

**What is wrong:**
Runtime shows DataAccess → Web violations that correspond to Spring's long-standing Open-Session-In-View / Open-EntityManager-In-View pattern:

- `orm.hibernate5.support.OpenSessionInViewFilter extends web.filter.OncePerRequestFilter`
- `orm.hibernate5.support.OpenSessionInViewInterceptor implements web.context.request.AsyncWebRequestInterceptor`
- `orm.hibernate5.support.AsyncRequestInterceptor implements web.context.request.async.CallableProcessingInterceptor`
- `orm.jpa.support.OpenEntityManagerInViewFilter extends web.filter.OncePerRequestFilter`
- `orm.jpa.support.OpenEntityManagerInViewInterceptor implements web.context.request.AsyncWebRequestInterceptor`

These classes *must* live in `orm.*.support` because they bridge a Hibernate/JPA `Session`/`EntityManager` into a Servlet filter chain. The layered rule blanket-forbids DataAccess → Web, so they all fail.

**Why it matters:**
Six production classes are violating the rule simultaneously, and the correct resolution is to exempt the `support` sub-packages — not to move them.

**How to fix it:**
Split the DataAccess layer or carve out `*.support..` sub-packages into Web:

```java
.layer("DataAccess").definedBy(
    "org.springframework.dao..",
    "org.springframework.jdbc..",
    "org.springframework.orm..",
    "org.springframework.transaction..",
    "org.springframework.r2dbc..",
    "org.springframework.oxm..",
    "org.springframework.jca..")
.layer("DataAccessWebBridge").definedBy(
    "org.springframework.orm.hibernate5.support..",
    "org.springframework.orm.jpa.support..")
...
.whereLayer("DataAccessWebBridge").mayOnlyBeAccessedByLayers("Web", "MiscServices")
```

Then ensure `DataAccess` proper may NOT reach Web, but `DataAccessWebBridge` may.

Alternatively, a targeted rule:

```java
@ArchTest
public static final ArchRule data_access_is_agnostic_of_web_except_support = noClasses()
    .that().resideInAnyPackage("org.springframework.dao..",
                               "org.springframework.jdbc..",
                               "org.springframework.orm..",
                               "org.springframework.transaction..",
                               "org.springframework.r2dbc..",
                               "org.springframework.oxm..",
                               "org.springframework.jca..")
    .and().resideOutsideOfPackages(
        "org.springframework.orm.hibernate5.support..",
        "org.springframework.orm.jpa.support..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.web..", "org.springframework.ui..",
        "org.springframework.http..")
    .because("Only the documented Open-Session-In-View glue may reach into the Web layer.");
```

---

### F-7
```
SEVERITY: MEDIUM
Category: Vacuous Rule
Affected Rule / Constraint: r2dbc_does_not_know_about_siblings
```

**What is wrong:**
The rule fails with *"failed to check any classes"* — no `org.springframework.r2dbc..` class was imported. This is not the rule's logical fault; it's a scanner-scope issue: the `r2dbc` module is missing from the runtime classpath, while `jdbc_does_not_know_about_siblings` successfully examines `jdbc`.

**Why it matters:**
Right now the rule contributes zero enforcement. The sibling package `spring-r2dbc` is one of Spring's official DataAccess modules; silently skipping it means a future `r2dbc → jdbc` introduction will pass CI.

**How to fix it:**
Pick one:

1. Add `spring-r2dbc` as a test dependency / classpath element so classes are actually imported.
2. Add `.allowEmptyShould(true)` to the rule so absence-of-classes is explicit, not an assertion failure.
3. Or both — allow empty while intentionally declaring the module optional:

```java
@ArchTest
public static final ArchRule r2dbc_does_not_know_about_siblings = noClasses()
    .that().resideInAPackage("org.springframework.r2dbc..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework.jdbc..", "org.springframework.orm..",
        "org.springframework.oxm..")
    .allowEmptyShould(true)
    .because("R2DBC is a peer of JDBC/ORM, not a client of them. Rule is inert when spring-r2dbc is not on the classpath.");
```

Option 1 is preferable — the whole point of this harness is to enforce on production classes.

---

### F-8
```
SEVERITY: MEDIUM
Category: Wrong Layer (knock-on from F-1)
Affected Rule / Constraint: layered_architecture_is_respected (scripting, jndi, ejb classification)
```

**What is wrong:**
`scripting`, `jndi`, and `ejb` are listed under CoreContainer. Runtime shows them participating in documented patterns that cross the (mis-drawn) CoreContainer/AopInstrumentation boundary:

- `scripting.support.RefreshableScriptTargetSource extends aop.target.dynamic.BeanFactoryRefreshableTargetSource`
- `jndi.JndiObjectTargetSource implements aop.TargetSource`
- `ejb.*` (no violations in this run, but the same pattern is likely once the module appears)

Under the patch in F-1, these packages would sit in the `Context` layer (above `Aop`), which naturally allows the access. The current rule's "CoreContainer ∋ scripting + jndi" is a symptom of F-1, not an independent bug — but worth calling out: whoever picks up the fix must re-classify all three together.

**Why it matters:**
These contribute an estimated 20-40 of the 627 layered violations and obscure the signal of the real problem.

**How to fix it:**
Move `scripting`, `jndi`, `ejb`, `format`, `validation`, `contextsupport` into a new `Context` layer above `Aop` as shown in F-1.

---

### F-9
```
SEVERITY: MEDIUM
Category: Wrong Layer (knock-on from F-1)
Affected Rule / Constraint: layered_architecture_is_respected (beans → aot is routine)
```

**What is wrong:**
Spring's AOT subsystem is structured so that `spring-core` *ships* `org.springframework.aot` and `spring-beans`/`spring-context` *consume* it heavily. Sample violations from this run:

- `beans.factory.aot.BeanDefinitionPropertiesCodeGenerator` calls `aot.generate.ValueCodeGenerator.scoped(..)`
- `beans.factory.aot.BeanRegistrationCodeGenerator` has a parameter of type `aot.generate.GeneratedMethods`
- `context.aot.ApplicationContextInitializationCodeGenerator` uses `aot.generate.GenerationContext`
- `context.aot.CglibClassHandler` uses `aot.generate.GenerationContext` and `aot.generate.GeneratedFiles`

The rule classifies `aot` in AopInstrumentation. That is factually wrong: AOT has nothing to do with AOP (it's Ahead-of-Time compilation support, shipped in spring-core).

**Why it matters:**
Roughly half of the 627 violations involve `aot.*` symbols. Re-classifying `aot` to the `Core` layer alongside `core` (as in F-1) removes the bulk of false positives in one stroke.

**How to fix it:**
See F-1 — place `aot` in `Core`:

```java
.layer("Core").definedBy(
    "org.springframework.core..", "org.springframework.aot..")
```

---

### F-10
```
SEVERITY: LOW
Category: Documentation Drift
Affected Rule / Constraint: layered_architecture_is_respected (Javadoc header)
```

**What is wrong:**
The Javadoc now declares the hierarchy top-to-bottom as:

```
1. Web Layer / Misc Services (top)
2. Data Access
3. AOP & Instrumentation
4. Core Container
5. Base Utilities (bottom)
```

But the rule treats MiscServices as the true top (`mayNotBeAccessedByAnyLayer`) and Web as accessible only by MiscServices. Also the Javadoc lumps Web and MiscServices together in the same bullet, which implies they are peers, when in fact the rule makes MiscServices strictly higher.

**Why it matters:**
A maintainer cannot tell from the Javadoc whether Web or MiscServices is higher. Also the Javadoc still omits AOT/validation sub-layering entirely.

**How to fix it:**
Rewrite the Javadoc header to match the rule verbatim and enumerate the canonical ordering after applying F-1:

```
Layer hierarchy (bottom to top):
  1. BaseUtilities  : util, lang
  2. Core           : core, aot
  3. Beans          : beans
  4. Aop            : aop, instrument
  5. Context        : context, expression, stereotype, format, scripting,
                      jndi, ejb, contextsupport, validation (except
                      validation.support which is Web-MVC glue)
  6. DataAccess     : dao, jdbc, orm, transaction, r2dbc, oxm, jca
  7. Web            : web, http, ui, protobuf, validation.support
  8. MiscServices   : cache, scheduling, messaging, jms, mail, jmx, resilience
                      (cache, scheduling, messaging may be consumed by Web
                       and DataAccess)
```

---

### F-11
```
SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: no_unmapped_spring_packages vs. layered_architecture_is_respected
```

**What is wrong:**
`no_unmapped_spring_packages` explicitly lists every package that must be mapped. This is maintenance-friendly but now duplicates the layer definitions — any change to a layer requires mirroring the change here. The tighter alternative would be a regex rule that flags anything not matching the union of layer definitions.

**Why it matters:**
Pure maintainability concern — no functional defect.

**How to fix it:**
Either accept the redundancy (it does add a forward-compatibility safety net) or consolidate by exposing the layer-package list as a `static final String[]` and referencing it from both the layered definition and the completeness guard.

---

## Coverage Matrix — Updated

| # | Constraint                                                            | Rule                                 | Status (Review #2) |
|---|-----------------------------------------------------------------------|--------------------------------------|--------------------|
| 1 | BaseUtilities is the foundation                                       | layered                              | Broken — see F-1 |
| 2 | CoreContainer depends only on BaseUtilities                           | layered + intra                      | Incoherent — F-1, F-3 |
| 3 | AopInstrumentation above CoreContainer                                | layered                              | **Architecturally wrong** — F-1 |
| 4 | DataAccess depends only on lower layers                               | layered                              | Partial — F-6 (OSIV pattern) |
| 5 | Web depends only on lower layers                                      | layered                              | Partial — F-4 (cache usage) |
| 6 | MiscServices placement                                                | layered                              | Over-strict — F-4 |
| 7 | `oxm`, `jca`, `jndi`, `ejb`, `format`, `scripting`, `protobuf`, `resilience`, `contextsupport` placement | layered + no_unmapped | Mapped ✓ (but placements partly wrong, see F-3, F-8) |
| 8 | Web ⟂ DataAccess (parallel isolation)                                 | web_and_dataaccess_are_isolated      | Over-broad — F-2 |
| 9 | `beans ⟶ context` forbidden                                           | beans_is_below_context               | **Covered ✓** |
| 10 | `core ⟶ beans/context/expression` forbidden                           | core_is_standalone                   | **Covered ✓** |
| 11 | `expression ⟶ beans/context` forbidden                                | expression_is_leaf_within_core_container | **Covered ✓** |
| 12 | `jdbc ⟶ orm/r2dbc/oxm` forbidden                                      | jdbc_does_not_know_about_siblings    | **Covered ✓** |
| 13 | `r2dbc ⟶ jdbc/orm/oxm` forbidden                                      | r2dbc_does_not_know_about_siblings   | Vacuous — F-7 |
| 14 | `transaction ⟶ jdbc/orm/r2dbc/oxm` forbidden                          | transaction_abstraction_is_pure      | **Covered ✓** |
| 15 | MiscServices pluggability                                             | misc_services_do_not_depend_on_each_other | Over-broad — F-5 |
| 16 | Tests + repackaged libraries excluded                                  | ExcludeRepackagedAndTestPackages     | **Covered ✓** |
| 17 | Every production package mapped                                        | no_unmapped_spring_packages          | **Covered ✓** |
| 18 | Rules actually execute against production classes                      | harness                              | **Covered ✓** |

---

## Severity Summary

| Severity | Count | IDs |
|----------|-------|-----|
| CRITICAL | 1     | F-1 |
| HIGH     | 5     | F-2, F-3, F-4, F-5, F-6 |
| MEDIUM   | 3     | F-7, F-8, F-9 |
| LOW      | 2     | F-10, F-11 |

**Final verdict: FAIL.** The harness is now operational and the intra-layer rules (core/beans/context/expression/jdbc/transaction) are *correct* and *passing*. The overall layered-architecture rule is, however, modelled incorrectly: placing `aop`/`aot`/`instrument` above `context`/`beans` reverses Spring's canonical DAG and produces 627 production-code violations. Until F-1 is addressed — which requires re-drawing the layer graph, not a localized edit — the primary invariant will either fire continuously or be disabled.
