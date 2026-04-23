# Prompt: Adversarial Review of Generated arch-go Rules

You are a senior adversarial architecture reviewer with deep arch-go expertise. Your sole objective is to find every defect in the generated rules — gaps that let violations slip through, rules that will never fire, and mappings that misrepresent the actual codebase. Optimism is a bug.

Project Name - e.g., consul
Reviewed Model Name - e.g., sonnet-4-6
Reviewer Model Name - e.g., opus-4-7

## Inputs

### Architecture Documentation
[PASTE THE SAME ARCHITECTURE DOCUMENTATION USED IN PROMPT 1]

### Package Structure
[PASTE THE SAME TOP-LEVEL PACKAGE LIST USED IN PROMPT 1]

### Generated arch-go Rules
[PASTE THE GENERATED arch-go YAML OR GO TEST CODE HERE]

---

## Output Format

```
outputs\[Project Name]\[Reviewed Model Name]\feedback#-by-[Reviewer Model Name].md
```

`e.g. outputs\consul\sonnet-4-6\feedback1-by-opus-4-7.md`

The file must be valid Markdown. Use the exact heading and template structure defined in the **Output Format** section below. The Recommended Patch code block must be fenced with ` ```yaml ` or ` ```go ` (matching what was generated) so it renders correctly and can be copied directly into the config or test file.

---

## Review Methodology

Work through the following checklist **in order**. Each section must be completed before moving to the next — findings in earlier sections affect the validity of later ones.

---

### PHASE 1 — Coverage Audit (False Negatives)

These are the highest-severity findings: constraints the documentation requires but the rules do not enforce.

1. **Constraint Inventory**: Extract every architectural constraint stated or implied in the documentation (dependency directions, layer isolation, naming conventions, module boundaries, prohibited dependency pairs). Number them. This is your ground truth list.

2. **Rule Inventory**: Extract every distinct assertion in the generated config or test file. Number them separately.

3. **Gap Matrix**: For each constraint in step 1, identify which rule (if any) covers it. Flag any constraint with no corresponding rule as a **CRITICAL GAP**.

4. **Package Coverage**: Cross-reference every Go import path in the package structure against the `pkg:` patterns in the generated rules. Identify:
   - Packages present in the codebase but **not matched by any `pkg:` glob** (invisible to arch-go — violations in these packages go undetected)
   - Packages targeted by a rule but **absent from the actual module** (rules that can never trigger)
   - Packages assigned to the **wrong layer or role** based on their import path, location, or documented responsibility

---

### PHASE 2 — Rule Precision Audit (False Positives & Vacuous Rules)

5. **Vacuous Rules**: For each rule, determine whether it could ever produce a violation given the actual package structure. Flag rules that are structurally impossible to violate — for example, a `deny:` targeting a package that does not exist in the module, or an `allow:` list that permits every package that could realistically be imported.

6. **Overly Broad Rules**: Identify rules whose `pkg:` globs are so wide they would flag legitimate, documented dependencies as violations. Provide a concrete example of a legitimate import that would be incorrectly blocked.

7. **Overly Narrow Rules**: Identify rules whose globs are so specific they miss obvious sibling packages that should be equally constrained. Example: a rule covering `**/service/impl/**` but not `**/service/support/**` when both are implementation packages.

8. **`allow:` List and `--ignore` Flag Abuse**: Scrutinize every `allow:` entry and any `--ignore` patterns used at invocation time. Does an entry suppress a real violation rather than a legitimate cross-cutting concern? Would removing it cause a test failure that should actually be a test failure?

9. **Glob Syntax Errors**: arch-go uses path-style `**` globs, not ArchUnit-style `..` globs. Flag any rule using incorrect glob syntax (e.g., `..service..` instead of `**/service/**`) that would silently match nothing.

---

### PHASE 3 — Semantic Correctness Audit

10. **Dependency Direction Errors**: Verify that every `dependency_rules` entry encodes the correct direction. Confirm that `allow:` and `deny:` entries match the documented dependency hierarchy exactly — not inverted. For each rule, state: *"Package A is permitted/denied to import Package B — does the documentation agree?"*

11. **Peer Layer Isolation**: arch-go provides **no implicit layered architecture enforcement** — every isolation pair must be an explicit rule. For every pair of same-layer or peer modules the documentation states must not depend on each other, verify a specific `deny:` entry exists. The absence of an `allow:` entry is not sufficient — verify a `deny:` entry actively blocks the relationship.

12. **Rule Description / Comment Accuracy**: If the generated config uses YAML comments (`#`) or Go test names to document rationale, verify they accurately reflect the constraint being enforced. Flag misleading, generic ("dependency rule"), or missing comments.

13. **Module Scope and Build Tag Scope**: Verify that the arch-go config or test file targets the correct Go module root as declared in `go.mod`. Check that no `//go:build` tags or test-only files are inadvertently included in or excluded from analysis. Confirm the root `pkg:` glob matches the actual module path — a mismatch silently scans nothing.

---

### PHASE 4 — Structural & Completeness Audit

14. **Intra-layer Module Rules**: For every pair of same-layer modules the documentation warns should not depend on each other, verify a specific `deny:` rule exists. arch-go provides no implicit intra-layer isolation — only explicit rules enforce it.

15. **Transitivity Gaps**: Identify cases where a direct dependency is blocked but a transitive bypass exists. Example: Package `A` cannot import `C` directly, but no rule prevents `A → B → C` where `B` is in the same layer as `A`.

16. **Test vs. Production Scope**: arch-go scans all `.go` files including `_test.go` by default. Confirm whether `dependency_rules` are intended to cover test files. If not, verify that the config or invocation excludes `*_test.go` files (e.g., via `--exclude-files`). Rules that inadvertently scan test files produce false positives on test helpers that legitimately reach across layers.

17. **Naming, Contents, and Function Rule Coverage**: arch-go can enforce naming conventions (`naming_rules`), struct and file contents (`contents_rules`), and function properties (`function_rules`). If the architecture documentation specifies naming conventions (e.g., *"all repositories must be named `*Repository`"*), structural conventions, or complexity limits, verify that the appropriate rule type covers them. Flag any convention stated in the docs that has no corresponding rule.

18. **Rule Redundancy**: Identify rules that are fully subsumed by another rule (one makes the other unreachable). Redundant rules are not errors, but they indicate the generator did not reason carefully about coverage.

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
Category: Coverage Gap | Wrong Layer | Vacuous Rule | Overly Broad | Overly Narrow | Semantic Error | Structural Gap | Glob Syntax Error
Affected Rule / Constraint: [rule name or constraint number from Phase 1]

What is wrong:
[Precise description of the defect]

Why it matters:
[The specific architectural violation that would go undetected, or the legitimate import that would be incorrectly flagged. Name actual import paths where possible.]

How to fix it:
[The corrected or additional arch-go rule, written as valid YAML or Go. If the fix is a deletion, explain what replaces it.]
```

---

### Severity Definitions

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | A documented constraint has zero enforcement — real violations will pass CI |
| **HIGH** | A rule is so imprecise it either misses a whole class of violations or produces widespread false positives |
| **MEDIUM** | A rule is technically correct but incomplete — some violations in the constraint's scope escape |
| **LOW** | Cosmetic or maintainability issue (misleading comment, redundant rule, style) |

---
