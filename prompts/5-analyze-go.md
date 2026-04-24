# Prompt: Violation Analysis of arch-go Test Results

You are a senior architecture analyst with deep arch-go expertise. Your sole objective is to triage every violation reported by an arch-go test run and decide, for each one, whether it is a **mapping error** in the rule config or a **real violation** in the codebase under test. Optimism is a bug; so is pessimism — be precise.

The loop terminates when the **mapping-error count reaches zero**. At that point every remaining violation (if any) is, by definition, a real architectural defect in the codebase that must be reported as such; if zero violations remain, the verdict is `PASS`.

The "documentation" provided is the ground truth for what counts as a real violation. The `arch-go.yml` file is the *encoding* of that documentation; if the rule config disagrees with the documentation, the config is wrong (mapping error). If the rule config agrees with the documentation but the codebase still violates it, the codebase is wrong (real violation).

Project Name - e.g., consul
Model Name - e.g., gemini3-flash, sonnet-4-6
Reviewer Model Name - e.g., opus-4-7
Iteration Number - 1, 2, 3, … (this loops until mapping-error count is 0)

## Inputs

### Architecture Documentation

[PASTE THE SAME ARCHITECTURE DOCUMENTATION USED FOR RULE GENERATION]

### Package Structure

[PASTE THE TOP-LEVEL PACKAGE LIST OF THE TARGET PROJECT]

### Current arch-go Rules

[PASTE THE CONTENT OF outputs\[Project Name]\[Model Name]\arch-go.yml — the version at the start of this iteration]

### arch-go Test Report

[PASTE THE FULL CONTENTS OF the arch-go check execution — every violation line, every rule description, every header]

### Previous Iteration's Analysis (omit on iteration 1)

[PASTE THE PREVIOUS analysis#-by-[Reviewer Model Name].md SO THE REVIEWER CAN SEE WHICH FIXES WERE APPLIED AND WHICH NEW VIOLATIONS APPEARED]

---

## Output File

```
outputs\[Project Name]\[Model Name]\5-analyze\analyze[Iteration Number]-by-[Reviewer Model Name].md
```

`e.g. outputs\[Project Name]\[Model Name]\5-analyze\analyze1-by-[Reviewer Model Name].md`

The file must be valid Markdown. Use the exact heading and template structure defined in the **Output Format** section below. YAML patch snippets must be fenced with ` ```yaml ` so they render correctly and can be copied directly into the `arch-go.yml` file.

---

## Analysis Methodology

Work through the following phases **in order**. Each phase must be completed before moving to the next — findings in earlier phases affect the validity of later ones.

---

### PHASE 1 — Inventory the Failing Run

1. **Per-rule violation counts**: From the arch-go report, list every rule description with its violation count and whether it `PASSED` or `FAILED`. Sum into a total.

2. **Delta vs. previous iteration**: If a previous analysis exists, record the previous total, the current total, and the delta. A drop in count is not in itself success — the *kind* of violations matters (see Phase 2). A flat or rising count means the previous fix made things worse and must be reverted.

3. **Carry-over check**: For each violation pattern flagged as "mapping error" in the previous iteration, confirm whether the current rule config actually applied the recommended fix. If the `arch-go.yml` was edited but the violation persists, the previous fix was wrong; if the config was not edited, the user has not yet attempted the fix.

---

### PHASE 2 — Cluster Violations by Source Pattern

4. **Group by source-package and target-package prefix**: Cluster the raw violation lines into a small number of patterns (typically 5–10). A "pattern" is a tuple of *(source-package glob, target-package glob, rule kind)*. Examples: `internal/service/** → internal/handler/**` (dependency rule), `<package A in shared list> → <package B not in shared list>`.

5. **Count per pattern**: Approximate violation counts per pattern. The patterns must sum (roughly) to the total. Patterns with one or two violations should still be listed — they are often the easiest to triage and the easiest to overlook.

6. **Side-by-side delta table** (iterations ≥ 2): Build a table `Pattern | Previous count | Current count | Status (Fixed / Reduced / Unchanged / Introduced / Pre-existing)`. The "Introduced" column is the most important: those are patterns that did not appear in the previous report and must be attributed to the previous fix.

---

### PHASE 3 — Triage Each Pattern: Mapping Error vs. Real Violation

For each pattern, decide its category. Apply the following decision procedure **in order**; the first matching rule wins.

7. **Documentation says the edge is forbidden AND the codebase contains the edge → REAL VIOLATION.**
   This is the only category that ever counts toward "real violation". The fix belongs in the codebase under test, not in the `arch-go.yml`. Cite the exact section / sentence of the documentation that forbids the edge, plus the exact source package and target package.

8. **Documentation says nothing about the edge OR explicitly allows it, but the rule config forbids it → MAPPING ERROR.**
   The rule is over-constraining. The fix belongs in the `arch-go.yml` config. Subcategories:
   - **Unmapped package**: source or target package is not properly covered by globs; arch-go triggers a catch-all or default behavior.
   - **Wrong-layer assignment**: the package is mapped, but to a layer whose dependency rules don't match the documented role of the packages inside it.
   - **Coarse-grained glob match**: a `**` glob pattern matched too broadly (e.g., `internal/**` instead of `internal/service/**`), catching packages that should not be constrained.
   - **Missed shared package**: a package is genuinely shared per the documentation but is not on any "shared utility" allow-list.
   - **Test file oversight**: a rule inadvertently targets `*_test.go` files when it shouldn't, producing false positives for test helpers.
   - **Tooling package in root module**: a CLI / test-helper package lives alongside core business logic but is not excluded from core rules.

9. **Documentation forbids the edge AND the rule forbids the edge AND the codebase contains the edge BUT the edge is a known intentional carve-out (e.g., a single shared utility, an admin-only escape hatch) → MAPPING ERROR.**
   The edge is real, but the documentation has a documented exception that the config failed to encode. The fix is to add a narrow `allow` rule or extend a shared-package glob; the fix does **not** belong in the codebase. Cite the documentation passage that grants the exception.

10. **Edge appears in the report but on closer inspection is internal to a single layer (e.g., `Service → Service` because two halves of the layer were inadvertently restricted) → MAPPING ERROR.**
    Almost always caused by overly broad `deny:` rules that prevent peers within a layer from interacting. Fix in the rule config.

11. **Cannot determine without reading more code → MARK AS UNCERTAIN.**
    Do not guess. List the pattern under "Uncertain" with the specific files that would need to be read to decide. Treat uncertain patterns as mapping errors for purposes of the loop's termination check (i.e., the loop continues), but flag them clearly so the next iteration can resolve them.

---

### PHASE 4 — Real Violations: Documentation Citation Pass

12. For every pattern marked **REAL VIOLATION** in Phase 3:

    a. Quote the exact paragraph or section of the architecture documentation that forbids this edge. If you cannot find such a passage, downgrade to **MAPPING ERROR** — by definition a real violation requires a documented prohibition.
    
    b. List the source files in the codebase that contain the violation, with line numbers if visible in the arch-go report.
    
    c. Recommend a code-level fix in the codebase (the offending file), not in the config file. The fix should preserve documented behaviour while removing the prohibited dependency.
    
    d. Real violations are findings to be **reported**, not suppressed. Never recommend an `allow:` exception for a real violation.

---

### PHASE 5 — Mapping Errors: Recommend a Single-Shot Patch

13. Aggregate every pattern marked **MAPPING ERROR** into a single, copy-pasteable YAML patch for the `arch-go.yml`. Prefer minimal, surgical edits over rewrites; the next iteration will validate each edit. Patch types, in order of preference:
    - Add an unmapped package to its correct rule.
    - Refine a glob pattern (e.g., `internal/**` to `internal/handler/**`) to exclude wrongly-assigned packages.
    - Add an explicit `allow:` entry for a documented carve-out (last resort; always cite the documentation).
    - Suggest excluding test files if tests are improperly constrained.

14. **Forbidden patch types**:
    - Do not relax a layer-isolation rule that the documentation explicitly states.
    - Do not add `allow:` entries for any edge not explicitly carved out by the documentation.
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

- `Results: 7 mapping error` — keep looping; 7 patterns need config fixes.
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
Representative source packages: <up to 3 example packages from the arch-go log>
Representative target packages: <up to 3 example targets>

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
 For MAPPING ERROR: the smallest possible config-file edit, as a YAML snippet.
 For UNCERTAIN: the investigation step required.]
```

### Section 4 — Real Violations

If Section 3 produced any **REAL VIOLATION** entries, restate them here as a numbered list addressed to the codebase maintainer. Each entry must include the documentation citation, the offending source package(s)/file(s), and the recommended code change. If there are no real violations, write `No real violations detected in this iteration.`

### Section 5 — Recommended Single-Shot Patch (Mapping Errors)

A single YAML block containing every rule-file edit aggregated into one copy-pasteable patch. Use full rule context so the user can locate the edit site. 

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
2. Apply the patch from Section 5 to the `arch-go.yml` file.
3. Re-run `arch-go check`; obtain a new test report.
4. If `Results: 0 mapping error` and Section 4 says "No real violations", **stop**. The rule config faithfully encodes the documentation and the codebase obeys it.
5. If `Results: 0 mapping error` but Section 4 lists real violations, **stop the rule-tuning loop** and start a separate codebase-fix loop using Section 4 as the issue list.
6. Otherwise, iterate to N+1.

Drift control: if iteration N's projected count is 0 but iteration N+1 reports a non-zero count again, treat it as evidence the previous patch over-fitted (e.g., used `**` too broadly) and prefer narrower package globs in the next patch.

---

## Severity & Triage Tags

| Tag | Meaning |
| --- | ------- |
| **REAL VIOLATION** | Codebase contains an edge the documentation forbids. Fix in the codebase. Must be cited with documentation passage. |
| **MAPPING ERROR** | Rule config forbids an edge the documentation allows (or is silent about). Fix in the `arch-go.yml` config. |
| **UNCERTAIN** | Cannot decide without reading additional source files. Treat as mapping error for loop purposes; resolve in next iteration. |

---

## Anti-Patterns to Avoid

- Counting a drop in violation total as success without inspecting whether the *kind* of violations changed. A 180→160 drop can hide a 180→0+160-new churn.
- Recommending `allow:` entries to silence a violation without citing a documentation carve-out.
- Adjusting broad `**` globs without inspecting the outbound edges of the matched subpackages before reclassifying.
- Treating the config file as ground truth. The documentation is ground truth; the config file is an attempt at encoding it.
- Marking a violation as REAL without quoting documentation. If you cannot quote it, it is a mapping error or uncertain — never a real violation by default.
