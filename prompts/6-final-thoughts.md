# Prompt: Final-Thoughts Calibration Pass

You are the senior architecture analyst. The loop has terminated: the ArchUnit test file produces `Results: 0 mapping error` and all `@ArchTest` rules pass against the real build. The user is now asking — explicitly or implicitly — "does this file match the ground-truth documentation?"

Your job in this step is **calibration**, not celebration. A passing test run only proves that the codebase does not break the encoded rules in the currently-imported classpath. It does **not** prove that the encoded rules are a complete and faithful translation of the documentation. Your final-thoughts document must make that distinction honest and explicit.

Refusing to claim certainty you don't have is the point of this prompt. If you find yourself writing "yes, 100%", stop and reread the documentation — you are almost certainly glossing over a judgment call.

Project Name - e.g., zookeeper
Model Name - e.g., gemini3-flash, sonnet-4-6
Reviewer Model Name - e.g., opus-4-7
Terminating Iteration - the iteration number at which `Results: 0 mapping error` was reached

## Inputs

### Architecture Documentation

[PASTE THE SAME ARCHITECTURE DOCUMENTATION USED THROUGHOUT THE LOOP]

### Package Structure

[PASTE THE TOP-LEVEL PACKAGE LIST]

### Final ArchUnit Rules

[PASTE THE CONTENT OF outputs\[Project Name]\[Model Name]\ArchitectureEnforcementTest.java — the final passing version]

### Full Loop History

[PASTE THE `Results:` HEADER LINE FROM EVERY analyzeN-by-[Reviewer].md SO THE CALIBRATION CAN CITE THE TRAJECTORY]

---

## Output File

```
outputs\[Project Name]\[Model Name]\6-final\final-thoughts-by-[Reviewer Model Name].md
```

`e.g. outputs\[Project Name]\[Model Name]\6-final\final-thoughts-by-[Reviewer Model Name].md`

Valid Markdown. No Java code blocks required (the rule file is already final — this document is about its *relationship to documentation*, not its contents).

---

## Calibration Methodology

Work through the following phases **in order**. Each phase answers one honest question about the final rule file.

---

### PHASE 1 — What can I vouch for?

1. List only claims that the test run *actually* proves. Acceptable examples:
   - "All four `@ArchTest` rules pass against the imported classpath."
   - "Every top-level package in the package structure is assigned to exactly one layer."
   - "The specific documented prohibition `X` is encoded by rule `Y`."
   - "Each `ignoreDependency` is class-pair narrow, not package-wide."

2. Unacceptable examples (never write these):
   - "The rule file faithfully encodes the documentation."
   - "There are no architectural issues."
   - "The rules are correct."

A claim is vouchable if and only if it can be verified without reading the documentation interpretively. "Rule `whereLayer("Server").mayNotBeAccessedByAnyLayer()` is present in the file" is vouchable; "that rule correctly encodes §1.1" is *not* — it is interpretation.

---

### PHASE 2 — Where am I extrapolating from thin documentation?

3. **Enumerate the silences.** For each architectural question the rule file answers, check whether the documentation answers it. If the documentation does not, record the silence and the choice you made:
   - "Can Tools access Recipes? Doc silent. Rule allows."
   - "Can Recipes access Server? Doc silent. Rule forbids."
   - "Is `audit` client-side or server-side? Doc silent. Classified as Support by naming convention."

4. **Enumerate the invented labels.** List every layer / predicate name in the rule file that does not appear verbatim in the documentation. These are your encodings, not the document's words.

5. **Enumerate the inferred rules.** List every `@ArchTest` rule whose justification is inferred rather than cited. Cite which documentation passage you inferred from (if any) and mark clearly that this is inference.

6. **Enumerate the carve-outs.** For each `ignoreDependency`, ask: "Does the documentation mention this edge?" If not, the rationale is your reasoning, not the document's authority. Say so.

7. **Enumerate the per-class judgments.** If the rule file contains class-level predicates (shared-utility lists, root-tool exclusions, etc.), list every individual class that has been classified by judgment, not by package. Each of these is a place where a domain expert could disagree.

---

### PHASE 3 — Scope and sensitivity

8. **Import scope.** Note that "zero violations" is scoped to whatever the pom actually imports. If the build excludes modules (e.g., contrib, integration-tests, generated), classes there are not covered and could produce new edges. Say so.

9. **Sensitivity untested.** A passing run only shows the rules don't fire *on the current code*. It does not show that the rules would fire on a counterfactual bad change. Unless the user has mutation-tested the rules, note that the *sensitivity* of the rule file is unproven — only its current false-positive rate (zero) is proven.

---

### PHASE 4 — State the strongest claim you can actually support

10. Write one sentence of the form:

    > The rule file, the codebase, and my reading of the documentation all agree at a fixed point.

    This or an equivalent three-way-consistency phrasing is the strongest claim that a loop termination justifies. It is deliberately weaker than "the rule file matches the documentation" because a three-way agreement among `{rules, code, my reading}` leaves open the possibility that *my reading* is wrong. That's the honest position.

11. Add no more than two corollaries that genuinely follow from test evidence, not from interpretation. Typically:

    - "Every layer-crossing edge in the codebase has been either documented via a `because(...)` rationale or moved inside a layer via reclassification — there is no silent suppression."
    - "No edge that the documentation explicitly forbids is permitted by the current rules."

---

### PHASE 5 — What would upgrade confidence

12. List the *specific, actionable* steps that would convert judgment into authority, in descending order of impact. Realistic examples:
    - Review by a project committer / PMC member on the enumerated judgment calls.
    - An ADR or design-notes document that supplements the primary documentation.
    - Extending the import scope to cover currently-excluded modules.
    - Mutation testing: inject a known violation, confirm it fires, remove it, confirm it clears.

13. Be explicit that none of these is *required* to ship the rule file; all of them would *upgrade* confidence from "fixed point reached" to "formally validated". The current state is shippable with caveats.

---

### PHASE 6 — Recommendation to ship

14. Recommend shipping the rule file **with caveats added to its Javadoc header**. The caveats should include:
    - A note that the layer partition is an operational encoding, not a direct documentation quote.
    - A note listing each class-pair `ignoreDependency` carve-out and inviting project-maintainer review.
    - A pointer to the specific documentation section(s) the rules enforce.

15. These caveats make the file honest about its own limits so future maintainers do not mistake "test passes" for "architecture validated".

---

## Output Format

Structure the final-thoughts file exactly as follows.

### Header

```
# Final Thoughts - [Project Name] ArchUnit Rules

Reviewer: [Reviewer Model Name]
Subject: [path to the final rule file]
Iteration terminating the loop: analysis[Terminating Iteration] (`Results: 0 mapping error` after patch)
Ground truth documents consulted: [list the documentation files]
```

### Section 1 — Verdict

One paragraph. State the fixed-point outcome plainly, then state that it is strictly weaker than "matches the documentation". Promise the honest calibration that follows.

### Section 2 — What I am confident about

Numbered list. Only vouchable claims per Phase 1. Keep it short — if it is longer than the next section, you are probably overclaiming.

### Section 3 — What I am NOT confident about

Six subsections (one per Phase 2 / 3 enumeration), each with a short bulleted list. Order of subsections:

1. Documentation silences
2. Invented labels
3. Inferred rules
4. Undocumented carve-outs
5. Import-scope conditionality
6. Per-class judgment calls

Each bullet names a specific choice and the documentation passage (or lack thereof) it relies on.

### Section 4 — The strongest claim I can actually support

One sentence quoting the three-way-consistency phrasing, plus at most two corollaries. No more.

### Section 5 — What would upgrade confidence

Numbered list, descending impact. Specific and actionable.

### Section 6 — Recommendation

State the ship / do-not-ship decision and the caveats to add to the Javadoc header. Short.

---

## Anti-Patterns to Avoid

- Writing "100% certain" or "correct" or "validates the documentation" anywhere in the document. Those phrases are almost always overclaims.
- Confusing "zero violations in the current build" with "rules are right". The former is a measurement; the latter is a judgment.
- Omitting the per-class judgment list because the list is long. The long list is exactly the point — it shows how many micro-decisions the rule file embeds.
- Treating the four `ignoreDependency` clauses as "officially sanctioned" because they made the tests pass. They are as documented as their `because(...)` clauses and no more.
- Recommending further rule-file edits in this document. That belongs in an `analysisN.md`, not a `final-thoughts.md`. The loop is over; this document reports on what was shipped, not what to change next.
- Writing a celebratory tone. The reader has already seen that the tests pass; they are here for calibration, not reassurance.

---

## Relationship to the Rest of the Loop

- `1-*.md` through `3-*.md`: rule generation / compilation fixes / adversarial review. Feed into the first rule file.
- `4-analyze-java.md`: the iterative triage loop. Runs until `Results: 0 mapping error`.
- **`5-final-thought.md` (this prompt)**: runs exactly once, after the loop terminates. Produces `final-thoughts-by-[Reviewer].md`.

This is the last document in the chain. Its readers will be future
maintainers, PRs reviewers, and anyone who wonders later whether the
architecture tests in this repo are load-bearing or decorative. Give
them an honest answer.
