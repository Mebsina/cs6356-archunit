# Violation Analysis #1 - Zero-violation baseline, loop terminates

Reviewer: opus-4-7
Scope: `layered_architecture_is_respected` = 0 violations, `recipes_must_not_depend_on_zookeeper_internals` = 0 violations → 0 violations total after applying analysis#0 recommendations (N/A — first analyze-loop iteration)
Previous count: N/A (first iteration of the analyze loop); current count: 0.
Results: 0 mapping error

---

## Section 1 — TL;DR

The surefire report for `ArchitectureEnforcementTest` shows `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.798 s`. Both `@ArchTest` rules pass empirically, with zero violation lines to triage. There are **0 mapping errors**, **0 real violations**, and **0 uncertain patterns**. The analyze loop terminates on this iteration: the rule file faithfully encodes the four documented constraints (C1, C2, C3 enforced; C4 intentionally documented as out-of-scope for a fragile signature assertion — see `review6-by-opus-4-7.md` §R6-04), and the codebase obeys every encoded constraint. **Recommendation: ship.**

---

## Section 2 — Side-by-Side Delta vs. Previous Iteration

Not applicable — this is the first iteration of the analyze loop. For historical context, the preceding *review* loop's trajectory (tracked in `fix-history.md` and `review{1..7}-by-opus-4-7.md`) was:

| Iteration marker | Violations | Notes |
|---|---:|---|
| pre-Review-#3 surefire | 25 917 | `consideringAllDependencies()` bug |
| pre-Review-#4 surefire | 1 561 | after scope fix |
| pre-Review-#5 surefire | 161 | after Protocol layer introduced |
| pre-Review-#6 surefire | 17 | after root-package split |
| **current** | **0** | after R6-01 + R6-02 + R6-03 Option B + R6-07 + compile fix |

---

## Section 3 — Per-Pattern Triage

No violation patterns to triage. Both rules reported 0 failures.

---

## Section 4 — Real Violations

No real violations detected in this iteration.

---

## Section 5 — Recommended Single-Shot Patch (Mapping Errors)

No mapping-error patch is required. The rule file is already in a consistent end-state.

Two LOW-severity defensive-consistency observations from `review7-by-opus-4-7.md` (R7-01, R7-02) remain open but are **not mapping errors** under the Phase-3 decision procedure — they do not cause any current or forbidden-by-documentation edge to slip through. They are stylistic hardening for future codebase evolution:

- **R7-01 (optional):** the ignore-target `name("org.apache.zookeeper.ZKUtil")` at line 321 does not cover `ZKUtil$*` anonymous inner classes (the sibling `VerifyingFileFactory` entry uses `nameMatching("…VerifyingFileFactory(\\$.*)?")`). Harmonising would be a one-line change: `nameMatching("org\\.apache\\.zookeeper\\.ZKUtil(\\$.*)?")` inside the existing `or(...)` block (variance-safe because `or(...)` accepts both `DescribedPredicate<HasName>` and `DescribedPredicate<JavaClass>` branches).

- **R7-02 (optional):** `CLIENT_PROTOCOL_TYPES` matches only the exact name `ZKClientConfig` (no nested-type coverage). Because `name(...)` returns `DescribedPredicate<HasName>` and cannot be assigned directly to `DescribedPredicate<JavaClass>` (this was the root cause of the step-8 compile error), the extension must keep the `describe(...)` wrapper and only widen the lambda:

```java
private static final DescribedPredicate<JavaClass> CLIENT_PROTOCOL_TYPES =
        DescribedPredicate.describe(
                "client.ZKClientConfig and any nested types (cross-tier SASL config carrier)",
                c -> c.getName().matches("org\\.apache\\.zookeeper\\.client\\.ZKClientConfig(\\$.*)?"));
```

Neither change is required for the analyze loop's termination.

---

## Section 6 — Predicted Outcome After Patch

| Pattern | Current count | Projected count after patch |
| ------- | ------------- | --------------------------- |
| (none)  | 0             | 0                           |
| **Total** | **0**       | **0**                       |

`M = 0` and there are no real violations. **Loop terminates on this iteration.**

Per the prompt's §Loop-Termination step 4: *"If `Results: 0 mapping error` and Section 4 says 'No real violations', stop. The rule file faithfully encodes the documentation and the codebase obeys it."*

---

## Section 7 — Lessons / Notes for Next Iteration

No next iteration. For historical record and to prevent regressions if the rule file is later edited:

1. **Variance gotcha.** `HasName.Predicates.name(...)` and `nameMatching(...)` return `DescribedPredicate<HasName>`, which cannot be assigned directly to a `DescribedPredicate<JavaClass>` field because Java generics are invariant. The two working idioms in this file are (a) wrap in `or(...)` varargs (variance-safe via `? super T` inference) and (b) use `DescribedPredicate.describe(String, Predicate<JavaClass>)` with a hand-written lambda. Do not drop a bare `name(...)` into a `DescribedPredicate<JavaClass>` field assignment without wrapping.

2. **`ZKClientConfig` is the only cross-tier carve-out from `client..`.** It is a SASL / configuration carrier whose `client` package location is historical. Any future class added to `client..` with a genuinely cross-tier role should extend `CLIENT_PROTOCOL_TYPES`, not be moved out of the package.

3. **`ZKUtil` is a grab-bag.** It currently lives in Client (because the `ZooKeeper`-parametered methods dominate) but its pure static helpers (`aclToString`, `validateFileInput`) are called from Monitoring and Server. The correct long-term fix is upstream: split the formatters into a Protocol-layer helper. Until then, the class-wide `name("...ZKUtil")` entry in the cross-cutting ignore list is the documented compromise.

4. **Two cli → server edges (`GetConfigCommand → ConfigUtils`, `ReconfigCommand → QuorumPeerConfig|quorum.flexible..`) are the last honest architectural smell.** They are suppressed by narrow `ignoreDependency(source, target)` pairs precisely so a *new* cli → server edge still fails the build. If the upstream team relocates `ConfigUtils` / `QuorumPeerConfig` out of `server..`, the two suppressions can be deleted.

5. **C4 is intentionally not asserted.** The seven-method public API (§1.6) is documented in the class-level Javadoc; adding a brittle `methods().that()...should()...` rule was considered in reviews #4, #5, and #6 and rejected each time. Do not add one solely for constraint-number completeness.

---

**End of Analysis #1. Ship it.**
