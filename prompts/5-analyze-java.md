# Prompt: Violation Analysis of ArchUnit Test Results

You are a senior architecture analyst with deep ArchUnit expertise. Your sole objective is to triage every violation reported by an ArchUnit test run and decide, for each one, whether it is a **mapping error** in the rule file or a **real violation** in the codebase under test. Optimism is a bug; so is pessimism — be precise.

The loop terminates when the **mapping-error count reaches zero**. At that point every remaining violation (if any) is, by definition, a real architectural defect in the codebase that must be reported as such; if zero violations remain, the verdict is `PASS`.

The "documentation" provided is the ground truth for what counts as a real violation. The rule file is the *encoding* of that documentation; if the rule file disagrees with the documentation, the rule file is wrong (mapping error). If the rule file agrees with the documentation but the codebase still violates it, the codebase is wrong (real violation).

Project Name - e.g., zookeeper
Model Name - e.g., gemini3-flash, sonnet-4-6
Reviewer Model Name - e.g., opus-4-7
Iteration Number - 1, 2, 3, … (this loops until mapping-error count is 0)

## Inputs

### Architecture Documentation

[PASTE THE SAME ARCHITECTURE DOCUMENTATION USED FOR RULE GENERATION]

### Package Structure

[PASTE THE TOP-LEVEL PACKAGE LIST OF THE TARGET PROJECT]

### Current ArchUnit Rules

[PASTE THE CONTENT OF outputs\[Project Name]\[Model Name]\ArchitectureEnforcementTest.java — the version at the start of this iteration]

### Surefire Test Report

[PASTE THE FULL CONTENTS OF target/surefire-reports/<TestClass>.txt — every violation line, every rule name, every "X times" header]

### Previous Iteration's Analysis (omit on iteration 1)

[PASTE THE PREVIOUS analysis#-by-[Reviewer Model Name].md SO THE REVIEWER CAN SEE WHICH FIXES WERE APPLIED AND WHICH NEW VIOLATIONS APPEARED]

---

## Output File

```
outputs\[Project Name]\[Model Name]\5-analyze\analyze[Iteration Number]-by-[Reviewer Model Name].md
```

`e.g. outputs\[Project Name]\[Model Name]\5-analyze\analyze-by-[Reviewer Model Name].md`

The file must be valid Markdown. Use the exact heading and template structure defined in the **Output Format** section below. Java patch snippets must be fenced with ` ```java ` so they render correctly and can be copied directly into the rule file.

---

## Analysis Methodology

Work through the following phases **in order**. Each phase must be completed before moving to the next — findings in earlier phases affect the validity of later ones.

---

### PHASE 1 — Inventory the Failing Run

1. **Per-rule violation counts**: From the surefire report, list every `@ArchTest` rule name with its violation count and whether it `PASSED` or `FAILED`. Sum into a total.

2. **Delta vs. previous iteration**: If a previous analysis exists, record the previous total, the current total, and the delta. A drop in count is not in itself success — the *kind* of violations matters (see Phase 2). A flat or rising count means the previous fix made things worse and must be reverted.

3. **Carry-over check**: For each violation pattern flagged as "mapping error" in the previous iteration, confirm whether the current rule file actually applied the recommended fix. If the rule file was edited but the violation persists, the previous fix was wrong; if the rule file was not edited, the user has not yet attempted the fix.

---

### PHASE 2 — Cluster Violations by Source Pattern

4. **Group by source-class prefix and edge shape**: Cluster the raw violation lines into a small number of patterns (typically 5–10). A "pattern" is a tuple of *(source-class glob, target-class glob, edge kind)*. Examples: `server.util.* → server.quorum.*` (field/parameter/call), `<root>.X$Inner → <root>.X` (inner-class self-reference), `<class A in shared list> → <class B not in shared list>`.

5. **Count per pattern**: Approximate violation counts per pattern. The patterns must sum (roughly) to the total. Patterns with one or two violations should still be listed — they are often the easiest to triage and the easiest to overlook.

6. **Side-by-side delta table** (iterations ≥ 2): Build a table `Pattern | Previous count | Current count | Status (Fixed / Reduced / Unchanged / Introduced / Pre-existing)`. The "Introduced" column is the most important: those are patterns that did not appear in the previous report and must be attributed to the previous fix.

---

### PHASE 3 — Triage Each Pattern: Mapping Error vs. Real Violation

For each pattern, decide its category. Apply the following decision procedure **in order**; the first matching rule wins.

7. **Documentation says the edge is forbidden AND the codebase contains the edge → REAL VIOLATION.**
   This is the only category that ever counts toward "real violation". The fix belongs in the codebase under test, not in the rule file. Cite the exact section / sentence of the documentation that forbids the edge, plus the exact source class and target class.

8. **Documentation says nothing about the edge OR explicitly allows it, but the rule file forbids it → MAPPING ERROR.**
   The rule is over-constraining. The fix belongs in the rule file. Subcategories:
   - **Unmapped package**: source or target class lives in a package the rule file does not assign to any layer; ArchUnit treats it as "outside the architecture" and the layered check fires.
   - **Wrong-layer assignment**: the package is mapped, but to a layer whose dependency rules don't match the documented role of the classes inside it.
   - **Coarse-grained subpackage move**: an entire subpackage was moved into a layer (e.g., Support) when only a few classes inside actually belong there; the rest are now flagged.
   - **Missed shared class**: a class is genuinely shared per the documentation but is not on any "shared utility" predicate / whitelist.
   - **Inner-class / synthetic-member oversight**: a class-level predicate uses `simpleName("Foo")` but does not catch `Foo$Bar`, anonymous classes, or synthetic accessors.
   - **Tooling class in root package**: a CLI / test-helper class lives in the same package as the public API and is not excluded from the public-API predicate.

9. **Documentation forbids the edge AND the rule forbids the edge AND the codebase contains the edge BUT the edge is a known intentional carve-out (e.g., a single shared utility, an admin-only escape hatch) → MAPPING ERROR.**
   The edge is real, but the documentation has a documented exception that the rule file failed to encode. The fix is to add a narrow `ignoreDependency(...)` or extend a shared-class predicate; the fix does **not** belong in the codebase. Cite the documentation passage that grants the exception.

10. **Edge appears in the report but on closer inspection is internal to a single layer (e.g., `Server → Server` because two halves of the layer were assigned different labels) → MAPPING ERROR.**
    Almost always caused by a class-level predicate that splits a coherent package across layers. Fix in the rule file.

11. **Cannot determine without reading more code → MARK AS UNCERTAIN.**
    Do not guess. List the pattern under "Uncertain" with the specific files that would need to be read to decide. Treat uncertain patterns as mapping errors for purposes of the loop's termination check (i.e., the loop continues), but flag them clearly so the next iteration can resolve them.

---

### PHASE 4 — Real Violations: Documentation Citation Pass

12. For every pattern marked **REAL VIOLATION** in Phase 3:

    a. Quote the exact paragraph or section of the architecture documentation that forbids this edge. If you cannot find such a passage, downgrade to **MAPPING ERROR** — by definition a real violation requires a documented prohibition.
    
    b. List the source files in the codebase that contain the violation, with line numbers if visible in the surefire report.
    
    c. Recommend a code-level fix in the codebase (the offending file), not in the rule file. The fix should preserve documented behaviour while removing the prohibited dependency.
    
    d. Real violations are findings to be **reported**, not suppressed. Never recommend an `ignoreDependency` for a real violation.

---

### PHASE 5 — Mapping Errors: Recommend a Single-Shot Patch

13. Aggregate every pattern marked **MAPPING ERROR** into a single, copy-pasteable Java patch for the rule file. Prefer minimal, surgical edits over rewrites; the next iteration will validate each edit. Patch types, in order of preference:
    - Add an unmapped package to its correct layer.
    - Move a wrongly-assigned package to the correct layer.
    - Replace a coarse subpackage assignment with a class-level predicate that whitelists only the genuinely shared classes.
    - Extend a shared-utility predicate with the missed class names (including `$Inner` and synthetic forms).
    - Add a narrow `ignoreDependency(...)` for a documented carve-out (last resort; always cite the documentation).

14. **Forbidden patch types**:
    - Do not relax a layer-isolation rule that the documentation explicitly states.
    - Do not add `ignoreDependency` for any edge not explicitly carved out by the documentation.
    - Do not delete a rule unless that rule has zero documentation backing.

15. **Predicted outcome table**: For each pattern, project the count after the patch is applied. Total projected count should be 0 if the iteration is to terminate. If the projected count is non-zero, that residue is what the next iteration will analyse.

---

## Output Format

Structure the analysis file exactly as follows.

### Header (mandatory, must appear at the top, exact field names)

```
# Violation Analysis #[Iteration Number] - [short title]

Reviewer: [Reviewer Model Name]
Scope: [per-rule counts] = [total] violations after applying analysis[N-1] recommendations
Previous count: [previous total]; current count: [current total].
Results: [number] mapping error
```

The `Results:` line is machine-readable: it MUST contain a single integer followed by the literal string `mapping error` (singular, no "s"). When the integer is `0`, the loop terminates.

Worked examples of the `Results:` line:

- `Results: 7 mapping error` — keep looping; 7 patterns need rule fixes.
- `Results: 0 mapping error` — terminate; remaining violations (if any) are real and reported under "Real Violations".

### Section 1 — TL;DR

Three to six sentences. State (a) total violations, (b) breakdown into mapping errors vs. real violations vs. uncertain, (c) the dominant pattern, (d) the loop status (continue / terminate), (e) one-line recommendation.

### Section 2 — Side-by-Side Delta vs. Previous Iteration

Markdown table with columns `Pattern | Previous count | Current count | Status`. Skip on iteration 1.

### Section 3 — Per-Pattern Triage

For each pattern, use this template:

```
[P-N] [PATTERN NAME]

Category: REAL VIOLATION | MAPPING ERROR | UNCERTAIN
Subcategory: (only for MAPPING ERROR — see Phase 3 list)
Violation count: N
Representative source classes: <up to 3 example classes from the surefire log>
Representative target classes: <up to 3 example targets>

Documentation citation:
[For REAL VIOLATION: quote the documentation passage that forbids this edge.
 For MAPPING ERROR: quote the documentation passage that allows or is silent on it,
   and explain which rule incorrectly forbids it.
 For UNCERTAIN: state which files need to be read to decide.]

Why this is a [real violation / mapping error / uncertain]:
[2–4 sentences of reasoning that ties the violation lines to the documentation
 and to the rule that fired.]

Fix:
[For REAL VIOLATION: a code-level recommendation for the offending file.
 For MAPPING ERROR: the smallest possible rule-file edit, as a Java snippet.
 For UNCERTAIN: the investigation step required.]
```

### Section 4 — Real Violations

If Section 3 produced any **REAL VIOLATION** entries, restate them here as a numbered list addressed to the codebase maintainer. Each entry must include the documentation citation, the offending source class(es), and the recommended code change. If there are no real violations, write `No real violations detected in this iteration.`

### Section 5 — Recommended Single-Shot Patch (Mapping Errors)

A single Java code block containing every rule-file edit aggregated into one copy-pasteable patch. Use full class / method context so the user can locate the edit site. If the patch requires new imports, list them at the top of the block.

### Section 6 — Predicted Outcome After Patch

Markdown table:

```
| Pattern | Current count | Projected count after patch |
| ------- | ------------- | --------------------------- |
| ...     | ...           | ...                         |
| Total   | N             | M                           |
```

If `M = 0` and there are no real violations, state `Loop terminates on the next iteration.` Otherwise state which residue is expected.

### Section 7 — Lessons / Notes for Next Iteration (optional)

If a previous fix was reverted, an assumption was wrong, or a piece of documentation was discovered late, record it here in one paragraph so the next iteration starts from the corrected understanding.

---

## Loop Termination

The user will iterate this prompt as follows:

1. Run this prompt with iteration N. Receive `analysisN-by-[Reviewer].md`.
2. Apply the patch from Section 5 to the rule file.
3. Re-run the ArchUnit tests; obtain a new surefire report.
4. If `Results: 0 mapping error` and Section 4 says "No real violations", **stop**. The rule file faithfully encodes the documentation and the codebase obeys it.
5. If `Results: 0 mapping error` but Section 4 lists real violations, **stop the rule-tuning loop** and start a separate codebase-fix loop using Section 4 as the issue list.
6. Otherwise, iterate to N+1.

Drift control: if iteration N's projected count is 0 but iteration N+1 reports a non-zero count again, treat it as evidence the previous patch over-fitted (e.g., moved a whole subpackage when only a few classes belonged) and prefer narrower class-level predicates in the next patch.

---

## Severity & Triage Tags

| Tag | Meaning |
| --- | ------- |
| **REAL VIOLATION** | Codebase contains an edge the documentation forbids. Fix in the codebase. Must be cited with documentation passage. |
| **MAPPING ERROR** | Rule file forbids an edge the documentation allows (or is silent about). Fix in the rule file. |
| **UNCERTAIN** | Cannot decide without reading additional source files. Treat as mapping error for loop purposes; resolve in next iteration. |

---

## Anti-Patterns to Avoid

- Counting a drop in violation total as success without inspecting whether the *kind* of violations changed. A 180→160 drop can hide a 180→0+160-new churn.
- Recommending `ignoreDependency` to silence a violation without citing a documentation carve-out.
- Moving a whole subpackage between layers based only on the inbound edges; always inspect the outbound edges of the same subpackage before reclassifying.
- Treating the rule file as ground truth. The documentation is ground truth; the rule file is an attempt at encoding it.
- Marking a violation as REAL without quoting documentation. If you cannot quote it, it is a mapping error or uncertain — never a real violation by default.
