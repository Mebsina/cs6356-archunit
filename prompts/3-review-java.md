# Prompt: Adversarial Review of Generated ArchUnit Rules

You are a senior adversarial architecture reviewer with deep ArchUnit expertise. Your sole objective is to find every defect in the generated rules — gaps that let violations slip through, rules that will never fire, and mappings that misrepresent the actual codebase. Optimism is a bug.

Project Name - e.g., spring-framework
Model Name - e.g., gemini3-flash, sonnet-4-6
Reviewer Model Name - e.g., opus-4-7

## Inputs

### Architecture Documentation
[PASTE THE SAME ARCHITECTURE DOCUMENTATION USED IN PROMPT 1]

### Package Structure
[PASTE THE SAME TOP-LEVEL PACKAGE LIST USED IN PROMPT 1]

### Generated ArchUnit Rules
[PASTE THE CONTENT OF outputs\[Project Name]\[Model Name]\ArchitectureEnforcementTest.java]

---

## Output Format

```
outputs\[Project Name]\[Model Name]\3-review\review#-by-[Reviewer Model Name].md
```

`e.g. outputs\[Project Name]\[Model Name]\3-review\review1-by-[Reviewer Model Name].md`

The file must be valid Markdown. Use the exact heading and template structure defined in the **Output Format** section below. The Recommended Patch code block must be fenced with ` ```java ` so it renders correctly and can be copied directly into the test class.

---

## Review Methodology

Work through the following checklist **in order**. Each section must be completed before moving to the next — findings in earlier sections affect the validity of later ones.

---

### PHASE 1 — Coverage Audit (False Negatives)

These are the highest-severity findings: constraints the documentation requires but the rules do not enforce.

1. **Constraint Inventory**: Extract every architectural constraint stated or implied in the documentation (dependency directions, layer isolation, naming conventions, module boundaries, prohibited dependency pairs). Number them. This is your ground truth list.

2. **Rule Inventory**: Extract every distinct assertion in the generated code. Number them separately.

3. **Gap Matrix**: For each constraint in step 1, identify which rule (if any) covers it. Flag any constraint with no corresponding rule as a **CRITICAL GAP**.

4. **Package Coverage**: Cross-reference every package in the package structure against the layer definitions. Identify:
   - Packages present in the codebase but **absent from all layer definitions** (invisible to ArchUnit — violations in these packages go undetected)
   - Packages assigned to a layer but **absent from the actual package structure** (rules that can never trigger)
   - Packages assigned to the **wrong layer** based on their naming, location, or documented responsibility

---

### PHASE 2 — Rule Precision Audit (False Positives & Vacuous Rules)

5. **Vacuous Rules**: For each rule, determine whether it could ever produce a violation given the actual package structure. Flag rules that are structurally impossible to violate (e.g., a `noClasses().that().resideInPackage("x")` rule where package `x` contains no classes that could possibly depend on the restricted target).

6. **Overly Broad Rules**: Identify rules whose package globs are so wide they would flag legitimate, documented dependencies as violations. Provide a concrete example of a legitimate call that would be incorrectly blocked.

7. **Overly Narrow Rules**: Identify rules whose globs are so specific they miss obvious sibling packages that should be equally constrained. Example: a rule covering `..service.impl..` but not `..service.support..` when both are implementation packages.

8. **`ignoresDependency()` Abuse**: If any ignore clauses are present, scrutinize each one. Does it suppress a real violation rather than a legitimate cross-cutting concern? Would removing it cause a test failure that should actually be a test failure?

---

### PHASE 3 — Semantic Correctness Audit

9. **Layer Direction Errors**: Verify the `layeredArchitecture()` definition encodes dependency arrows in the correct direction. Confirm that "allowed to access" relationships match the documented hierarchy exactly, not inverted.

10. **Parallel Layer Isolation**: Identify all layers that the documentation states must be isolated from each other (e.g., Web and Persistence). Verify a specific rule blocks each cross-cutting pair — not just that the layered architecture implicitly prevents it, but that a dedicated assertion exists or the layered config makes it untestable to bypass.

11. **`because()` Clause Accuracy**: For each `.because()` clause, verify it accurately cites the architectural rationale. Flag clauses that are generic placeholders ("because of layered architecture"), factually incorrect, or that describe the wrong constraint.

12. **`ClassFileImporter` Scope**: Verify the importer scope is neither too wide (importing unrelated modules, inflating scan time and causing false positives) nor too narrow (missing modules whose violations would go undetected). Check that `withImportOption` exclusions don't accidentally exclude production classes.

---

### PHASE 4 — Structural & Completeness Audit

13. **Intra-layer Module Rules**: For every pair of same-layer modules the documentation warns should not depend on each other, verify a specific `noClasses()` rule exists. The `layeredArchitecture()` API does not enforce intra-layer isolation — only explicit rules do.

14. **Transitivity Gaps**: Identify cases where a direct dependency is blocked but a transitive bypass exists. Example: `A` cannot depend on `C` directly, but no rule prevents `A → B → C` where `B` is in the same layer as `A`.

15. **Test vs. Production Scope**: Confirm that rules apply only to production code unless the documentation explicitly requires constraints on test code. Rules that inadvertently scan test packages produce false positives on test doubles and stubs.

16. **Rule Redundancy**: Identify rules that are fully subsumed by another rule (one makes the other unreachable). Redundant rules are not errors, but they indicate the generator did not reason carefully about coverage.

---

## Output Format

Structure your findings exactly as follows:

### Executive Summary
- Total documented constraints identified: N
- Total rules generated: N
- Coverage rate: N/N constraints have a corresponding rule
- **Critical Gaps** (constraints with zero coverage): list them
- Overall verdict: `PASS` / `PASS WITH WARNINGS` / `FAIL`

---

### Findings

For each issue, use this template:

```
[ID] SEVERITY: CRITICAL | HIGH | MEDIUM | LOW
Category: Coverage Gap | Wrong Layer | Vacuous Rule | Overly Broad | Overly Narrow | Semantic Error | Structural Gap
Affected Rule / Constraint: [rule name or constraint number from Phase 1]

What is wrong:
[Precise description of the defect]

Why it matters:
[The specific architectural violation that would go undetected, or the legitimate dependency that would be incorrectly flagged. Name actual packages where possible.]

How to fix it:
[The corrected or additional ArchUnit rule, written as valid Java code. If the fix is a deletion, explain what replaces it.]
```

---

### Severity Definitions

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | A documented constraint has zero enforcement — real violations will pass CI |
| **HIGH** | A rule is so imprecise it either misses a whole class of violations or produces widespread false positives |
| **MEDIUM** | A rule is technically correct but incomplete — some violations in the constraint's scope escape |
| **LOW** | Cosmetic or maintainability issue (misleading `.because()`, redundant rule, style) |

---
