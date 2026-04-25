# In-Depth Architectural Enforcement Comparison: Spring Framework

## 1. Executive Summary

**gemini3-flash** produced the objectively superior ArchUnit enforcement suite for the Spring Framework. The decisive factor is **gemini3-flash**'s strict adherence to the documented architectural ground truth, utilizing granular, intra-layer isolation rules and precise dependency allowlists to resolve violations. Conversely, **sonnet-4-6** allowed existing code violations to dictate the architecture, fabricating synthetic layers (e.g., `AopConsumers`) and improperly reassigning cross-cutting SPIs (like `oxm`) into the Core Container to brute-force a passing build state.

## 2. Architectural Layer Model Analysis

**gemini3-flash** defined eight distinct, highly accurate layers that map cleanly to the documented Spring architecture diagram: `BaseUtilities`, `Core`, `Beans`, `Aop`, `Context`, `DataAccess`, `Web`, and `MiscServices`. It correctly localized third-party dependencies by using an `ExcludeRepackagedAndTestPackages` import option and a strict `no_unmapped_spring_packages` rule to ensure all production code resides within these defined boundaries.

**Example:**
```java
.layer("DataAccess").definedBy(
    "org.springframework.dao..", "org.springframework.jdbc..",
    "org.springframework.orm..", "org.springframework.transaction..",
    "org.springframework.r2dbc..", "org.springframework.oxm..",
    "org.springframework.jca..")
```

**sonnet-4-6** defined seven layers but severely distorted the model to accommodate compile-time violations. It bloated `CoreContainer` by inappropriately relocating `oxm` (Object/XML Mapping), `cache`, and `validation` into it. Furthermore, it invented a synthetic layer, `AopConsumers`, which is entirely absent from the Spring architecture documentation.

**Example:**
```java
.layer("AopConsumers").definedBy(
    "org.springframework.scheduling.annotation..",
    "org.springframework.validation.beanvalidation.."
)
```

Both models correctly identified and isolated third-party and build-only repackaged utilities (`asm`, `cglib`, `objenesis`, `javapoet`, etc.).

## 3. Alignment with Ground Truth (Primary Dimension)

**gemini3-flash** captured the true intent of the documentation by encoding inferred architectural constraints. It enforced strict intra-layer isolation, maintaining the purity of abstractions. For example, it identified that ORM implementations should only communicate with JDBC via specific datasource and exception-translation bridges, tightly restricting the dependency rather than allowing blanket access.

**Example Rule:**
```java
@ArchTest
public static final ArchRule orm_only_integrates_with_jdbc_via_datasource_and_exception_translation = noClasses()
    .that().resideInAPackage("org.springframework.orm..")
    .should().dependOnClassesThat(
        resideInAPackage("org.springframework.jdbc..")
            .and(not(resideInAnyPackage(
                "org.springframework.jdbc.datasource..",
                "org.springframework.jdbc.support.."))))
```

**sonnet-4-6** relied heavily on broad layer definitions and numerous `.ignoreDependency()` carve-outs (eight in total) to suppress genuine architectural violations. Rather than modeling the true relationships, it exempted them.

**Example:**
```java
.ignoreDependency(
    resideInAPackage("org.springframework.cache.."),
    resideInAPackage("org.springframework.aop..")
)
```

Additionally, **gemini3-flash** implemented a `no_unmapped_spring_packages` rule to prevent new packages from bypassing architectural checks, a critical enforcement mechanism entirely absent from **sonnet-4-6**.

## 4. Methodological Analysis (Fix History)

The `fix-history.md` logs reveal stark contrasts in methodology.

**gemini3-flash** required 10 iterations (9 fixes) but demonstrated a surgical, investigative approach. When encountering regressions (e.g., Review 7), it refined rules via targeted allowlists ("Expanded ORM-to-JDBC allowlist to include org.springframework.jdbc.support.."). It also refined its sanity checks, replacing a single aggregate rule with 15 individual `@ArchTest` per-module presence checks to ensure precise failure reporting.

**sonnet-4-6** required fewer iterations (6 generations) but utilized a flawed "brute-force passing state" methodology. Its log explicitly details moving modules into incorrect layers merely to satisfy dependencies. In Review 2, it notes: "Created AopConsumers layer for scheduling.annotation and validation.beanvalidation (F13)." In Review 3, it relocated `oxm` and `cache` from `DataAccess` to `CoreContainer` to resolve web-layer access violations (F30). This approach achieves a passing test suite at the expense of architectural truth.

## 5. Final Scored Verdict

**gemini3-flash** is unequivocally the superior model for architectural enforcement. It successfully navigated the complexities of the Spring Framework by implementing deep, granular isolation rules and maintaining the integrity of the documented architecture. **sonnet-4-6** may be preferable only in legacy systems where rapid suppression of violations is prioritized over architectural purity, but it fails as a rigid enforcement gate.

| Dimension | gemini3-flash | sonnet-4-6 |
|---|---|---|
| Architectural Layer Model Accuracy | 5/5 | 2/5 |
| Ground Truth Alignment | 5/5 | 2/5 |
| ArchUnit DSL Competency | 4/5 | 4/5 |
| Violation Resolution Methodology | 5/5 | 2/5 |
| **Total** | **19/20** | **10/20** |
