# Adversarial Review of Generated ArchUnit Rules — spring-framework / sonnet-4-6

Reviewer: opus-4-7
Review #: 1

---

## Phase 1 — Coverage Audit

### 1.1 Constraint Inventory (from `inputs\java\2_spring-projects_spring-framework.pdf`)

The documentation (GeeksforGeeks "Spring Framework Architecture", Apr 18 2026) contains:

- **Architecture diagram** showing the following layered stack (top → bottom):
  - **Top row (three parallel peer layers)**: `Data Access / Integration` | `Web layer` | `Miscellaneous layer`
  - `AOP` | `Aspect` row (AOP directly above Core Container)
  - `Core Container` row (Core, beans, Context, spEL)
  - `Test` row (foundational, bottom-wide)

- **Layer composition called out in prose**:
  - **Core Container**: Spring Core, Spring Beans, Spring Context, Spring Expression Language (SpEL)
  - **Data Access/Integration**: Spring JDBC, Spring ORM, Spring Data, Spring Transaction *(diagram also shows JMS)*
  - **Web Layer**: Spring MVC, Spring WebFlux, Spring Web Services *(diagram also shows Servlet, Portlet, Struts)*
  - **Miscellaneous**: Spring Security, Spring Integration, Spring Batch, Spring Cloud
  - **Test**: foundational cross-cutting layer

Derived architectural constraints:

| # | Constraint |
|---|---|
| C1 | CoreContainer (`core`, `beans`, `context`, `expression`) sits at the bottom of the production stack and must not depend on any higher layer |
| C2 | AOP sits directly above CoreContainer; AOP may use CoreContainer but not DataAccess / Web / Miscellaneous |
| C3 | Data Access / Integration may use CoreContainer and AOP, but must not depend on Web or Miscellaneous |
| C4 | Web may use CoreContainer and AOP, but must not depend on Data Access or Miscellaneous |
| C5 | **Data Access, Web, and Miscellaneous are three *parallel* peer layers in the top row** — none of them should depend on the other two |
| C6 | Miscellaneous (Security / Integration / Batch / Cloud) may use CoreContainer and AOP but must not depend on Data Access or Web |
| C7 | **Test is a bottom-wide foundational layer**; production modules may sit on top of it (i.e. higher layers depend on Test, not the other way around) — but more practically, *production compile-time code* should not depend on test utilities |
| C8 | Within Core Container: `core` ← `beans` ← `context`; SpEL is consumed by context (so `expression` may not depend on `context`) |
| C9 | Within Data Access: `orm` depends on `jdbc` is *idiomatic*; `jdbc` must not depend on `orm` |
| C10 | Within Web: `spring-webmvc` (Servlet) and `spring-webflux` (Reactive) must not cross-depend |
| C11 | Core framework principles explicitly named: **Dependency Injection** (realised by CoreContainer) and **Aspect-Oriented Programming** (realised by the `aop` module). AOT (Ahead-of-Time) is **not** mentioned anywhere in the documentation |

### 1.2 Rule Inventory (generated code)

| # | Rule name |
|---|---|
| R1 | `spring_layered_architecture_is_respected` (5-layer layered architecture) |
| R2 | `data_access_layer_must_not_depend_on_web_layer` |
| R3 | `web_layer_must_not_depend_on_data_access_layer` |
| R4 | `spring_jdbc_must_not_depend_on_spring_orm` |
| R5 | `spring_orm_must_not_depend_on_spring_jdbc_directly` |
| R6 | `spring_messaging_must_not_depend_on_spring_web` |
| R7 | `spring_jms_must_not_depend_on_spring_jdbc` |
| R8 | `spring_r2dbc_must_not_depend_on_spring_jdbc` |
| R9 | `spring_webmvc_must_not_depend_on_spring_webflux` |
| R10 | `spring_webflux_must_not_depend_on_spring_webmvc` |
| R11 | `spring_beans_must_not_depend_on_spring_context` |
| R12 | `spring_core_must_not_depend_on_spring_beans` |
| R13 | `spring_expression_must_not_depend_on_spring_context` |
| R14 | `spring_aop_must_not_depend_on_data_access_layer` |
| R15 | `spring_aop_must_not_depend_on_web_layer` |
| R16 | `production_layers_must_not_depend_on_test_packages` |

### 1.3 Gap Matrix

| Constraint | Rule(s) | Status |
|---|---|---|
| C1 (CoreContainer at bottom) | R1 (`whereLayer("CoreContainer").mayNotAccessAnyLayer()`), R11, R12 | Partial — see F3 (aot placement) |
| C2 (AOP above CoreContainer) | R1, R14, R15 | OK — but see F3 (aot conflated with aop) |
| C3 (DataAccess isolation from Web/Misc) | R1, R2 | Partial — no DataAccess↛Misc rule |
| C4 (Web isolation from DataAccess/Misc) | R1, R3 | Partial — no Web↛Misc rule |
| C5 (Misc is peer of DataAccess/Web) | **NONE** — R1 puts Misc *above* DA/Web | **CRITICAL GAP — see F1** |
| C6 (Misc may not depend on DA/Web) | **NONE** — R1 actively *allows* Misc → DA and Misc → Web | **CRITICAL GAP — see F1** |
| C7 (Test containment) | R16 | Partial — see F10 |
| C8 (intra-CoreContainer) | R11, R12, R13 | Gap — missing `core ↛ context`, `core ↛ expression`, `beans ↛ expression` |
| C9 (`jdbc ↛ orm`) | R4 | OK |
| C10 (webmvc / webflux isolation) | R9, R10 | OK |
| C11 (AOP is Aspect-Oriented, not AOT) | **NONE** | **CRITICAL GAP — see F3** |

### 1.4 Package Coverage (from `inputs\java\2_spring-projects_spring-framework.txt`)

44 top-level packages under `org.springframework` are listed. Cross-reference:

**Packages listed in the input but NOT matched by any layer glob:**

| Package | Status |
|---|---|
| `org.springframework.contextsupport` | **INVISIBLE TO ARCHUNIT** — the glob `org.springframework.context..` does **not** match sibling package `contextsupport`. Any violations originating from or targeting this package will silently escape. (If this was meant to represent `context.support`, the input file is misleading, but the rules still fail to cover the literal package name in the input.) |
| `org.springframework.test`, `org.springframework.tests`, `org.springframework.mock` | Deliberately unassigned; they are referenced only by R16. Because the layered architecture uses `consideringOnlyDependenciesInLayers()`, dependencies *into* these packages are silently ignored — see F10 |
| `org.springframework.asm`, `cglib`, `objenesis`, `javapoet`, `protobuf`, `build`, `docs` | Excluded by the custom `ImportOption` (intentional) |

**Packages assigned to the wrong layer (see F1, F3, F4, F5, F6, F7 below for specifics):**

| Package | Current assignment | Correct per documentation |
|---|---|---|
| `aot` | `AOP` (treated as Aspect-Oriented Programming peer) | Should be `CoreContainer` (used by `beans`, `context`) or its own cross-cutting bucket — AOT is **not** AOP |
| `messaging`, `scheduling` | `DataAccess` | Cross-cutting / foundational; `scheduling` is used by Web (WebSocket, async MVC) and `messaging` is a peer used by Web (WebSocket sub-protocols) |
| `validation`, `ui`, `format` | `Web` / `DataAccess` | Cross-cutting (`format` is used by Web for data binding; `validation` is consumed by both Web and `messaging`) |
| `oxm` | `DataAccess` | Consumed by Web (`MarshallingHttpMessageConverter`, `MarshallingView`) — treating as pure DA causes every XML-over-HTTP dependency to be flagged |
| `instrument` | `Miscellaneous` | Used by CoreContainer (`org.springframework.context.weaving.DefaultContextLoadTimeWeaver` implements `org.springframework.instrument.classloading.LoadTimeWeaver`) — layering is inverted |

---

## Phase 2 — Rule Precision Audit

- **Vacuous**: none of the rules are structurally impossible to trigger — the surefire report confirms all 16 ran, and the 4 failing ones produced real output.
- **Overly broad**: R5 (see F2), R2/R3 (see F4/F5).
- **Overly narrow**: R11/R12/R13 omit the `core ↛ context`, `core ↛ expression`, `beans ↛ expression` sibling combinations (F8).
- **`ignoresDependency()`**: not used.

---

## Phase 3 — Semantic Correctness Audit

- **Layer direction** in R1 is arrow-correct (lower layers may not access higher), but the **layer model itself is wrong** (Misc is above DA/Web instead of peer — see F1; `aot` conflated with `aop` — see F3).
- **Parallel layer isolation**: DA↔Web are covered by explicit R2/R3, but the Misc↔DA and Misc↔Web peer relationships are not enforced anywhere (F1).
- **`because()` accuracy**: R5's rationale is fabricated and contradicts documented Spring design (F2). R16's rationale claims `spring-test` is "test-scope-only / not runtime", which is factually incorrect — `spring-test` is a published production artifact (F10).
- **Importer scope**: The `ImportOption` regex `.*/springframework/(asm|cglib|objenesis|javapoet|protobuf|build|docs)[/$].*` uses `[/$]` as a literal-character class. It happens to work for the current inputs (path separator after a package component is always `/`), but the `$` side is meaningless here — see F9.

---

## Phase 4 — Structural & Completeness Audit

- **Intra-layer**: F8 covers the missing CoreContainer pairs. There is also no rule preventing `spring-tx ← spring-jdbc` or `spring-tx ← spring-orm` reverse edges, but the documentation does not demand them.
- **Transitivity**: The `layeredArchitecture` API prevents transitive bypasses within its layer set, so no new transitivity gap is introduced. However, `aot` being in the AOP layer means every CoreContainer→aot access is flagged as a violation (F3), which is the source of most of the 1 164 R1 violations.
- **Test scope**: `@AnalyzeClasses(..., importOptions = { ImportOption.DoNotIncludeTests.class, ... })` correctly strips `target/test-classes`, so test-source false positives are prevented — OK.
- **Rule redundancy**: R2/R3 fully duplicate what R1 already enforces between the DataAccess and Web layers (once the layer model is correct); keeping them is not harmful, but they amplify the same false positives (F4/F5).

---

## Executive Summary

- **Total documented constraints identified**: 11 (C1–C11)
- **Total rules generated**: 16 (R1–R16)
- **Coverage rate**: 6 / 11 constraints fully covered (C2, C8-partial, C9, C10 fully; C3, C4 partially). 3 constraints (C5, C6, C11) have **zero** correct enforcement.
- **Critical gaps**:
  1. **C5 / C6 — the Miscellaneous layer is modelled as the top of the stack and explicitly permitted to reach into DataAccess and Web**, whereas the architecture diagram draws Misc as a *parallel peer* of DA and Web. As a result, Spring Security / Integration / Batch / Cloud code (and the `scripting`, `jmx`, `ejb`, `instrument`, `resilience` surrogates the generator picked) can freely cross-cut DA and Web with zero enforcement.
  2. **C11 — `org.springframework.aot` is placed in the `AOP` layer.** AOT (Ahead-Of-Time compilation) is a distinct technology from Aspect-Oriented Programming; the documentation never mentions AOT. This single mis-categorisation is responsible for ~80 % of the 1 164 R1 violations (every `beans`/`context` class that implements `RuntimeHintsRegistrar`, extends `ValueCodeGeneratorDelegates`, etc. is flagged as an illegal upward dependency).
- **Secondary (HIGH) gaps**: `orm → jdbc` is *forbidden* by R5 contradicting documented design (62 documented legitimate violations); `messaging`, `scheduling`, `oxm`, `format`, `validation`, `instrument` are all mis-layered, producing the 86 + 552 cross-peer false positives seen in R2/R3.
- **Overall verdict**: **FAIL**

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Wrong Layer / Structural Gap
Affected Rule / Constraint: R1 `spring_layered_architecture_is_respected`; Constraints C5, C6

What is wrong:
The diagram on page 1 of the PDF draws Data Access / Integration, Web, and Miscellaneous as
three parallel boxes in the SAME top row. R1 instead stacks Miscellaneous ABOVE DA and Web
with `whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP", "DataAccess", "Web")`,
i.e. it explicitly *permits* Misc → DA and Misc → Web.

Why it matters:
Every documented Miscellaneous-layer concern (Spring Security, Spring Integration, Spring Batch,
Spring Cloud, and — per this generator's mapping — `scripting`, `jmx`, `ejb`, `instrument`,
`resilience`) may now freely depend on `transaction`, `jdbc`, `web`, `http`, etc. without any
test failure. The parallel-peer contract the diagram encodes (none of the three may import the
other two) is entirely unenforced.

How to fix it:
Re-model the top row as three peers and add explicit cross-peer rules:

```java
@ArchTest
static final ArchRule spring_layered_architecture_is_respected =
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("CoreContainer").definedBy(
            "org.springframework.core..",
            "org.springframework.beans..",
            "org.springframework.context..",
            "org.springframework.contextsupport..",   // see F11
            "org.springframework.expression..",
            "org.springframework.lang..",
            "org.springframework.util..",
            "org.springframework.stereotype..",
            "org.springframework.aot..",              // see F3
            "org.springframework.instrument..",       // see F7
            "org.springframework.format..",           // cross-cutting formatter support
            "org.springframework.validation..",       // cross-cutting validator SPI
            "org.springframework.ui..",               // cross-cutting view-model SPI
            "org.springframework.scheduling.."        // cross-cutting scheduling SPI
        )
        .layer("AOP").definedBy("org.springframework.aop..")
        .layer("DataAccess").definedBy(
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
            "org.springframework.cache.."
        )
        .layer("Web").definedBy(
            "org.springframework.web..",
            "org.springframework.http.."
        )
        .layer("Messaging").definedBy(
            "org.springframework.messaging.."         // documented Spring Integration peer
        )
        .layer("Miscellaneous").definedBy(
            "org.springframework.scripting..",
            "org.springframework.jmx..",
            "org.springframework.ejb..",
            "org.springframework.resilience.."
        )
        .whereLayer("CoreContainer").mayNotAccessAnyLayer()
        .whereLayer("AOP").mayOnlyAccessLayers("CoreContainer")
        .whereLayer("DataAccess").mayOnlyAccessLayers("CoreContainer", "AOP")
        .whereLayer("Web").mayOnlyAccessLayers("CoreContainer", "AOP")
        .whereLayer("Messaging").mayOnlyAccessLayers("CoreContainer", "AOP")
        .whereLayer("Miscellaneous").mayOnlyAccessLayers("CoreContainer", "AOP")
        .because("Per the Spring Framework architecture diagram, DataAccess, Web, and Miscellaneous are parallel top-row peer layers. None may depend on the others.");

// explicit peer-isolation assertions
@ArchTest
static final ArchRule misc_layer_must_not_depend_on_data_access_layer =
    noClasses().that().resideInAnyPackage(
            "org.springframework.scripting..", "org.springframework.jmx..",
            "org.springframework.ejb..", "org.springframework.resilience..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.oxm..",
            "org.springframework.transaction..", "org.springframework.r2dbc..",
            "org.springframework.jms..", "org.springframework.jca..",
            "org.springframework.jndi..", "org.springframework.mail..",
            "org.springframework.cache..");

@ArchTest
static final ArchRule misc_layer_must_not_depend_on_web_layer =
    noClasses().that().resideInAnyPackage(
            "org.springframework.scripting..", "org.springframework.jmx..",
            "org.springframework.ejb..", "org.springframework.resilience..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..", "org.springframework.http..");
```
```

```
[F2] SEVERITY: HIGH
Category: Overly Broad / Semantic Error
Affected Rule / Constraint: R5 `spring_orm_must_not_depend_on_spring_jdbc_directly`

What is wrong:
R5 forbids every dependency from `org.springframework.orm..` to `org.springframework.jdbc..`.
The `.because()` clause claims spring-orm "must interact with the database layer only through
spring-tx transaction abstractions and spring-dao exception hierarchy, not through spring-jdbc
APIs directly." This is a fabricated constraint — it is not stated anywhere in the PDF, and it
contradicts documented Spring design. Spring ORM's JPA and Hibernate integration is BUILT ON
`JdbcTransactionObjectSupport`, `ConnectionHolder`, `DataSourceUtils`, and
`SQLExceptionTranslator` from spring-jdbc.

Why it matters:
The surefire report shows 62 violations, every one of them a legitimate, documented Spring
design decision, e.g.:
  - `JpaTransactionManager$JpaTransactionObject extends JdbcTransactionObjectSupport`
  - `HibernateExceptionTranslator.jdbcExceptionTranslator: SQLExceptionTranslator`
  - `JpaTransactionManager.doBegin(...)` calls `new ConnectionHolder(...)`, `setTimeoutInSeconds`
  - `HibernateJpaDialect.beginTransaction(...)` calls `DataSourceUtils.prepareConnectionForTransaction`
Removing R5 is the correct fix — the PDF only forbids the *reverse* edge (`jdbc → orm`), which
is already enforced by R4.

How to fix it:
DELETE R5. Keep R4 only, which correctly enforces the one directional constraint the
documentation implies. (If a future doc update ever forbids `orm → jdbc`, a new rule should
be added with an accurate `.because()` clause.)
```

```
[F3] SEVERITY: CRITICAL
Category: Wrong Layer
Affected Rule / Constraint: R1 — `org.springframework.aot` placed in the `AOP` layer

What is wrong:
`aot` (Ahead-Of-Time compilation / runtime hints) is lumped into the `AOP` layer alongside
`org.springframework.aop`. AOT and AOP are unrelated Spring technologies. The PDF only ever
mentions AOP ("Aspect-Oriented Programming", listed as one of the two core principles); AOT
is absent from the documentation entirely. In practice, `aot` is consumed by `beans` and
`context` (two CoreContainer modules) and is effectively a CoreContainer-level facility.

Why it matters:
Putting `aot` above CoreContainer causes R1 to flag every legitimate `beans → aot` and
`context → aot` edge as a layering violation — this single mis-categorisation produces the
bulk of the 1 164 R1 violations visible in surefire, for example:
  - `BeanUtilsRuntimeHints implements RuntimeHintsRegistrar`
  - `JakartaAnnotationsRuntimeHints implements RuntimeHintsRegistrar`
  - `BeanDefinitionPropertyValueCodeGeneratorDelegates$* implements ValueCodeGenerator$Delegate`
  - `ApplicationContextInitializationCodeGenerator` references `GeneratedClass`, `GenerationContext`
  - `EventListener` is annotated with `@Reflective` (from `aot.hint.annotation`)
All of these are the intended Spring 6 native-image integration — none are violations.

How to fix it:
Move `aot` into CoreContainer and remove it from AOP (see the `definedBy` block in F1). As a
defensive secondary rule, explicitly forbid `aot` and `aop` cross-dependencies, since they are
unrelated technologies:

```java
@ArchTest
static final ArchRule aot_must_not_depend_on_aop =
    noClasses().that().resideInAPackage("org.springframework.aot..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.aop..")
        .because("AOT (Ahead-Of-Time / native-image hints) and AOP (Aspect-Oriented Programming) are unrelated technologies and must remain decoupled.");
```
```

```
[F4] SEVERITY: HIGH
Category: Wrong Layer / Overly Broad
Affected Rule / Constraint: R2 `data_access_layer_must_not_depend_on_web_layer`

What is wrong:
R2 places `messaging`, `scheduling`, `oxm`, `format`, `validation` on opposite sides of the
DA/Web peer boundary even though they are cross-cutting. The 86 R2 violations in surefire
are dominated by:
  - `spring-messaging` classes referencing `org.springframework.validation.Validator`
    (e.g. `PayloadMethodArgumentResolver`, `DefaultMessageHandlerMethodFactory`,
    `SimpAnnotationMethodMessageHandler`) — `validation` is a cross-cutting Spring SPI, not a
    Web-only facility.
  - `org.springframework.orm.jpa.support.*` referencing `org.springframework.web.*` —
    `OpenEntityManagerInViewFilter`, `AsyncRequestInterceptor`, `OpenEntityManagerInViewInterceptor`
    are *intentional* Web↔JPA integration points (Open-EntityManager-in-View pattern) and
    belong in a Web-JPA bridge sub-layer, not in the DA layer unconditionally.

Why it matters:
Legitimate, documented Spring integrations fail the build. A user writing their own
`PayloadMethodArgumentResolver` would have to either (a) ignore the test or (b) re-implement
validation just to stay green.

How to fix it:
Re-classify `validation`, `ui`, `format`, `scheduling` into CoreContainer (cross-cutting SPIs;
see F1). Split `messaging` into its own peer layer (see F1). For the OpenEntityManager-in-View
bridge, prefer `resideInAPackage("org.springframework.orm..")
  .andShould().not().dependOnClassesThat().resideInAPackage("org.springframework.web..")`
but `ignoreDependency`-list the specific support-bridge classes, e.g.:

```java
@ArchTest
static final ArchRule data_access_layer_must_not_depend_on_web_layer =
    noClasses()
        .that().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..", "org.springframework.oxm..",
            "org.springframework.transaction..", "org.springframework.r2dbc..",
            "org.springframework.jms..", "org.springframework.jca..",
            "org.springframework.jndi..", "org.springframework.mail..",
            "org.springframework.cache..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..")
        .because("DataAccess and Web are parallel top-row peer layers per the documented diagram.");
```
(Note `validation`, `ui`, `format`, `messaging`, `scheduling` removed from BOTH sides.)
```

```
[F5] SEVERITY: HIGH
Category: Wrong Layer / Overly Broad
Affected Rule / Constraint: R3 `web_layer_must_not_depend_on_data_access_layer`

What is wrong:
R3 mirrors R2 and inherits the same mis-layering. 552 violations in surefire, mostly:
  - `spring-websocket` (`org.springframework.web.socket..`) referencing
    `org.springframework.messaging..` (e.g. `SubProtocolWebSocketHandler implements MessageHandler`,
    `WebSocketStompClient extends StompClientSupport`). STOMP-over-WebSocket is an **intentional**
    documented Spring integration — WebSocket messaging is a joint Web/Messaging concern.
  - `web.servlet.resource.CachingResourceResolver` referencing `org.springframework.cache.*` —
    `cache` is a cross-cutting abstraction, not a DA-exclusive concern.
  - `http.converter.xml.MarshallingHttpMessageConverter` referencing
    `org.springframework.oxm.*` — the documented XML-marshalling integration for HTTP.
  - Multiple `web.*` classes referencing `org.springframework.format.support.*` — `format` is
    the documented data-binding facility used *by* Web.

Why it matters:
Same as F4 — documented integrations fail the build.

How to fix it:
Narrow R3 to the *true* DA peers (`dao`, `jdbc`, `orm`, `oxm`-if-kept, `transaction`, `r2dbc`,
`jms`, `jca`, `jndi`, `mail`), remove `messaging`/`scheduling`/`validation`/`ui`/`format` from
the DA side entirely, and rely on the Messaging-peer layer from F1:

```java
@ArchTest
static final ArchRule web_layer_must_not_depend_on_data_access_layer =
    noClasses()
        .that().resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.dao..", "org.springframework.jdbc..",
            "org.springframework.orm..",
            "org.springframework.transaction..", "org.springframework.r2dbc..",
            "org.springframework.jms..", "org.springframework.jca..",
            "org.springframework.jndi..", "org.springframework.mail..")
        .because("DataAccess and Web are parallel top-row peer layers per the documented diagram. Cross-cutting SPIs (oxm, format, validation, messaging, scheduling, cache) are foundational and intentionally usable by Web.");
```

Then either keep `oxm` and `cache` out of the peer isolation altogether (making them cross-cutting), or add narrow, one-off rules that match only the true server-side DA concerns.
```

```
[F6] SEVERITY: MEDIUM
Category: Wrong Layer
Affected Rule / Constraint: R1 — `messaging` placed in DataAccess

What is wrong:
`org.springframework.messaging` is listed in the DA layer. The documentation lists "Spring
Integration" (the messaging-oriented module) inside the **Miscellaneous** row, not DA.
Additionally, the codebase shows `spring-messaging` sits side-by-side with `spring-websocket`
and `spring-webmvc.messaging-converter` — i.e. it is a peer of Web, not a descendant of DA.

Why it matters:
Treating `messaging` as DA produces two families of false positives:
  (a) messaging → validation: 86 × R2 violations.
  (b) web → messaging: 552 × R3 violations.
Both are *intended* integrations.

How to fix it:
Promote `messaging` to its own peer layer (see the `Messaging` layer introduced in F1). Keep
the narrower R6 (`messaging ↛ web/http`) as an intra-peer isolation rule if still desired —
the surefire run shows R6 passed with zero violations, indicating the intent is achievable.
```

```
[F7] SEVERITY: MEDIUM
Category: Wrong Layer
Affected Rule / Constraint: R1 — `instrument` placed in Miscellaneous

What is wrong:
`org.springframework.instrument` hosts the LTW (Load-Time Weaver) SPI. `org.springframework.
context.weaving.DefaultContextLoadTimeWeaver` implements `org.springframework.instrument.
classloading.LoadTimeWeaver`; in surefire this appears as an R1 violation:
`Class <org.springframework.context.weaving.DefaultContextLoadTimeWeaver> implements interface
 <org.springframework.instrument.classloading.LoadTimeWeaver>`. CoreContainer (via the
`context.weaving` sub-package) must access `instrument`, yet R1 forbids it because `instrument`
was put in the top-level `Miscellaneous` layer.

Why it matters:
A legitimate Spring 5/6 dependency is flagged on every build.

How to fix it:
Move `org.springframework.instrument..` into CoreContainer (see F1 definedBy block). This is
consistent with the fact that the `spring-instrument` jar is a foundational module for LTW.
```

```
[F8] SEVERITY: MEDIUM
Category: Overly Narrow / Structural Gap
Affected Rule / Constraint: R11, R12, R13 (intra-CoreContainer)

What is wrong:
The documented CoreContainer dependency chain is `core ← beans ← context` with `expression`
consumed by `context`. The generator wrote rules for:
  - beans ↛ context (R11)
  - core ↛ beans (R12)
  - expression ↛ context (R13)
but omitted the equally-implied rules:
  - core ↛ context (transitively already enforced, but worth asserting directly)
  - core ↛ expression
  - beans ↛ expression

Why it matters:
Without the direct-edge assertions, a refactor that introduces, say, a `core` → `expression`
dependency would not trip a dedicated test; the only protection would come from the
`layeredArchitecture` rule, which — as F3/F4/F5 show — is swamped by noise. A dedicated
`noClasses()` rule gives a concise, fast-fail signal.

How to fix it:
Add three rules:

```java
@ArchTest
static final ArchRule spring_core_must_not_depend_on_spring_context =
    noClasses().that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.context..")
        .because("spring-core is foundational; ApplicationContext (spring-context) is built on top of it.");

@ArchTest
static final ArchRule spring_core_must_not_depend_on_spring_expression =
    noClasses().that().resideInAPackage("org.springframework.core..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.expression..")
        .because("SpEL is built on top of spring-core utilities, not the other way around.");

@ArchTest
static final ArchRule spring_beans_must_not_depend_on_spring_expression =
    noClasses().that().resideInAPackage("org.springframework.beans..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.expression..")
        .because("spring-beans is lower than SpEL in the Core Container hierarchy; @Value/SpEL wiring belongs in spring-context.");
```
```

```
[F9] SEVERITY: LOW
Category: Semantic Error (importer scope)
Affected Rule / Constraint: `ExcludeRepackagedAndBuildOnlyPackages`

What is wrong:
The regex is `.*/springframework/(asm|cglib|objenesis|javapoet|protobuf|build|docs)[/$].*`.
The `[/$]` character class matches a literal `/` or a literal `$`. The `$` branch is never
useful here: the path separator in a file: or jar: URI is always `/`, and `$` only appears
inside class names (after the package segment), by which point `[/$]` has already had a
chance to fail the match.
Worse, this regex is also brittle: on Windows absolute-path URLs like
`file:///C:/.../springframework/asm/...`, the `.*/springframework/` prefix works, but a
developer running under a classpath where `springframework` is not preceded by `/` (e.g.
class names arriving over module paths, or a jar resolved as `jar:file:/.../`) is currently
handled only because `asURI()` normalises to forward slashes. This is fine today but fragile.

Why it matters:
No observed correctness issue today. Maintenance risk: the `$` alternative is a red herring,
and the regex does not explicitly handle the common `jar:file:...!/org/springframework/...`
form (although it still works because of the greedy `.*` prefix).

How to fix it:
Simplify to `[/]` (or just `/`) and drop the misleading `$`:

```java
private static final Pattern EXCLUDED_PATH_PATTERN = Pattern.compile(
    ".*/org/springframework/(asm|cglib|objenesis|javapoet|protobuf|build|docs)/.*"
);
```

For additional safety, compare against `location.contains(...)` or a slash-normalised
class-name form rather than `asURI().toString()`, e.g.

```java
@Override
public boolean includes(Location location) {
    return !location.contains("/org/springframework/asm/")
        && !location.contains("/org/springframework/cglib/")
        && !location.contains("/org/springframework/objenesis/")
        && !location.contains("/org/springframework/javapoet/")
        && !location.contains("/org/springframework/protobuf/")
        && !location.contains("/org/springframework/build/")
        && !location.contains("/org/springframework/docs/");
}
```
```

```
[F10] SEVERITY: MEDIUM
Category: Semantic Error (because-clause accuracy) / Structural Gap
Affected Rule / Constraint: R16 `production_layers_must_not_depend_on_test_packages`

What is wrong:
(a) `org.springframework.test` is a production module (`spring-test`) that ships as a regular
Maven artifact; the `because()` clause ("compile-time-only support code... any production
dependency on them would make the framework undeployable without test-scope libraries on the
runtime classpath") is therefore factually incorrect.
(b) The rule only covers 15 packages (forgets `aop`, `aot`, `oxm`, `r2dbc`, `jms`, `jca`,
`jndi`, `mail`, `ui`, `validation`, `expression`, `lang`, `util`, `stereotype`, `format`,
`ejb`, `scripting`, `jmx`, `instrument`, `resilience`, `contextsupport`, `messaging` — i.e. it
misses more packages than it covers). Any production class in one of the missed packages can
depend on `org.springframework.test..` with zero test failure.
(c) Because the `layeredArchitecture` rule uses `consideringOnlyDependenciesInLayers()` and
`test`/`tests`/`mock` are NOT declared as layers, dependencies from the declared production
layers into `test` are already silently ignored by R1 — the explicit R16 is the only line of
defence, and it leaks through every un-enumerated production package.

Why it matters:
A developer could introduce `org.springframework.scripting.bsh.BshScriptUtils` → `org.spring
framework.test.util.ReflectionTestUtils` today and no test would fail. This is the exact
class of regression R16 was written to prevent.

How to fix it:
Re-state R16 using a negative selector (resides in production = resides in `org.springframework`
but *not* in test/mock/excluded):

```java
@ArchTest
static final ArchRule production_code_must_not_depend_on_spring_test_packages =
    noClasses()
        .that().resideInAPackage("org.springframework..")
        .and().resideOutsideOfPackages(
            "org.springframework.test..",
            "org.springframework.tests..",
            "org.springframework.mock..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.test..",
            "org.springframework.tests..",
            "org.springframework.mock..")
        .because("`spring-test` / `spring-mock` ship as testing utilities; production Spring modules must not compile-depend on them, so users can exclude them at runtime.");
```
```

```
[F11] SEVERITY: MEDIUM
Category: Coverage Gap (package coverage)
Affected Rule / Constraint: R1 — `org.springframework.contextsupport` not covered

What is wrong:
Line 9 of `inputs\java\2_spring-projects_spring-framework.txt` lists the top-level package
`org.springframework.contextsupport`. R1's CoreContainer glob `org.springframework.context..`
does **not** match this sibling package (AntPathMatcher / ArchUnit package-matching treats
`context..` as `context` and its sub-packages; `contextsupport` is a different package name).

Why it matters:
Every class under `org.springframework.contextsupport` is invisible to the layered
architecture rule; because R1 uses `consideringOnlyDependenciesInLayers()`, any dependency
into or out of `contextsupport` is silently ignored — including, e.g., a `contextsupport`
class depending on `org.springframework.web.*` or `org.springframework.jdbc.*`.

How to fix it:
Add `"org.springframework.contextsupport.."` to the CoreContainer `definedBy(...)` list (see
F1 fix). If the input file's `contextsupport` entry is actually a rendering artifact of
`context.support`, this change is still safe (the glob simply matches zero classes in that
case).
```

```
[F12] SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: R2 / R3 vs R1 after fixing F1

What is wrong:
Once F1 is applied (layered architecture puts DA and Web as true peers with
`mayOnlyAccessLayers("CoreContainer","AOP")` each), R1 already forbids DA↔Web edges in
both directions, making R2 and R3 formally redundant.

Why it matters:
Not an error — redundancy provides clearer failure output. But it doubles reporting noise
when the constraint is violated (each cross-edge appears in three rules' violation lists).
Indicates the generator did not reason about coverage, but that is purely cosmetic.

How to fix it:
Keep R2 and R3 for readability and for faster-to-diagnose failure messages; alternatively,
remove them and let R1 do the work. No functional change either way.
```

---

## Severity Totals

| Severity | Count |
|---|---|
| CRITICAL | 2 (F1, F3) |
| HIGH     | 3 (F2, F4, F5) |
| MEDIUM   | 5 (F6, F7, F8, F10, F11) |
| LOW      | 2 (F9, F12) |
| **Total** | **12** |

The three critical/high false-negative findings (F1, F3) and three critical/high false-positive findings (F2, F4, F5) collectively account for essentially all 1 864 surefire violations (1 164 + 86 + 552 + 62). Fixing F1 + F3 alone should clear the majority of R1's 1 164 noise; adding F4 + F5 handles R2/R3; removing R5 (F2) clears the remaining 62.
