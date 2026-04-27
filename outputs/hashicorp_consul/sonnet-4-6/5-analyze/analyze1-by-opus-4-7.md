# Violation Analysis #1 - Zero-violation baseline, loop terminates

Reviewer: opus-4-7
Scope: Dependencies Rules 128/128 PASS, Functions Rules 6/6 PASS, Contents Rules 3/3 PASS, Naming Rules 8/8 PASS = 0 violations after applying analysis#0 recommendations (N/A — first analyze-loop iteration; the rule file enters this loop after five rounds of adversarial review captured in `fix-history.md` Reviews #1–#5).
Previous count: N/A (first iteration of the analyze loop); current count: 0.
Results: 0 mapping error

---

## Section 1 — TL;DR

The arch-go test report for `outputs/hashicorp_consul/sonnet-4-6/arch-go.yml` reports **`Total Rules: 145, Succeeded: 145, Failed: 0`** with `Compliance: 100% (PASS)` and `Coverage: 0% (PASS)` (the `0%` is the configured `threshold.coverage: 0` floor, not a defect — see `arch-go.yml` lines 122-128 and `fix-history.md` Review #5). Every one of the 128 dependency, 6 function, 3 contents, and 8 naming rules emitted a `[PASS]` line; there are **0 violations** to triage and therefore **0 mapping errors**, **0 real violations**, and **0 uncertain patterns**. The dominant pattern is "no pattern" — the rule file faithfully encodes the five-layer / harness-tree / structural / naming policy documented in the YAML preamble and in the project's architecture diagram (`inputs/go/1_hashicorp_consul.jpg`, whose runtime constraints are explicitly out-of-scope per the preamble), and the codebase obeys every encoded constraint. **The analyze loop terminates on this iteration. Recommendation: ship.**

---

## Section 2 — Side-by-Side Delta vs. Previous Iteration

Not applicable — this is the first iteration of the analyze loop. For historical context, the preceding *review* loop's trajectory (tracked in `fix-history.md` Reviews #1–#5) drove the rule count and compliance state to their current values:

| Review marker | Compliance | Notes |
|---|---|---|
| post-Review-#1 | unknown (rule scaffolding) | Layer 3 `agent/**` rule patched to exclude `agent/consul/**`; Foundation/Internal-Framework gaps closed; `lib` `contentsRules` no-op removed |
| post-Review-#2 | unknown | Diagram constraints declared out of scope; harness-tree rules (`test-integ`, `testing/deployer`, `testrpc`, `tools`, `internal/testing`) added; directory-root mirrors duplicated; `troubleshoot/**` prose aligned |
| post-Review-#3 | unknown | `internal/tools/**` and `grpcmocks/**` dependency rules added; root-mirror coverage extended to `lib`, `agent/consul`, `internal/storage`, `internal/resource`, `agent/cache`; `command/**` `troubleshoot/**` ban removed; `maxReturnValues` raised to 3 for `lib/**` and `internal/storage/**` |
| post-Review-#4 | unknown | 26 `agent/<leaf>` directory-root mirrors added; `connect/**` deny list expanded to mirror `api/**`; `agent/consul/fsm` `reporting` ban added; `lib`/`internal/storage` directory-root `maxReturnValues` mirrors added; Makefile `arch-check` re-resolves `arch-go` after install |
| post-Review-#5 | unknown | All four `contentsRules` switched to flat-boolean schema (`shouldOnlyContainStructs: true`); `agent/consul/state` interface ban removed (3 contents rules remain); `troubleshoot/**` and `command/**` rules tightened to strict-leaf isolation; `internal/protohcl` directory-root mirror added; vacuous `agent/proxycfg-sources` / `agent/rpc` mirrors deleted; parallel `Reconciler` naming for `agent/consul/controller` added; `multilimiter` naming switched to `*Limiter` wildcard; `version: 1` and explicit `threshold` block added; `internal/storage/**` rule split into per-backend rules; `internal/multicluster → internal/controller/**` ban added |
| **current (analyze #1)** | **100% (145/145 PASS)** | First test run; zero violations |

The rule total (145) and the per-category split (128 / 6 / 3 / 8) match what the post-Review-#5 fix would produce: 4 → 3 contents rules (state interface ban removed), 7 → 8 naming rules (parallel Reconciler for `agent/consul/controller` added), 127 → 128 dependency rules (net +1 from `internal/protohcl` root mirror added, two vacuous mirrors deleted, per-backend `internal/storage` split, and `internal/multicluster` peer ban). No regressions detected.

---

## Section 3 — Per-Pattern Triage

No violation patterns to triage. The `arch-go` test report contains zero `FAIL` lines and zero violation lines. Every one of the 145 rules emitted `[PASS]` and the summary table shows `Failed: 0` for all four rule categories.

For completeness, the four rule categories all reported clean:

| Category | Total | Passed | Failed |
|---|---:|---:|---:|
| Dependencies | 128 | 128 | 0 |
| Functions | 6 | 6 | 0 |
| Contents | 3 | 3 | 0 |
| Naming | 8 | 8 | 0 |
| **Total** | **145** | **145** | **0** |

There is nothing to cluster, count, or triage in this iteration.

---

## Section 4 — Real Violations

No real violations detected in this iteration.

The rule file's preamble (`arch-go.yml` lines 80-86) explicitly disclaims that the runtime / deployment-topology constraints implied by `inputs/go/1_hashicorp_consul.jpg` (control-plane vs. data-plane separation, sidecar-mediated app traffic, server-only RPC paths, app isolation from server control APIs) are *not* enforced by the YAML — they are not expressible as Go import-path predicates. CI PASS here therefore does not imply diagram conformance, and the absence of arch-go violations is not the same as a runtime-topology audit. Per the Phase-3 decision procedure (rule 11 in the methodology: "Cannot determine without reading more code → MARK AS UNCERTAIN"), the diagram constraints would be *uncertain* if they were in scope, but they are explicitly carved out of scope by the documentation itself, so they generate no findings here.

---

## Section 5 — Recommended Single-Shot Patch (Mapping Errors)

No mapping-error patch is required. The rule file is already in a consistent end-state.

Two LOW-severity defensive-consistency observations remain open from the historical review trajectory but are **not mapping errors** under the Phase-3 decision procedure — they do not let any documented edge slip through and they do not block any documented edge:

- **Coverage 0% threshold (intentional, documented).** `arch-go.yml` lines 122-128 set `threshold.coverage: 0` because arch-go's coverage metric is a package-touch heuristic (fraction of packages matched by *any* rule), not architectural-constraint coverage. Tightening this would not catch a real violation; loosening it is impossible (it is already at the floor). No action.

- **Forward-compat `agent/<leaf>` directory-root mirrors (intentional, documented).** Per `fix-history.md` Review #5 "FORWARD-COMPAT NOTE", 26 of the Layer-3 root-mirror rules duplicate enforcement against their `/**` siblings for currently-leaf directories. This is scaffolding for the day a leaf grows a sub-package; it is *not* a mapping error. No action.

If a future iteration of this loop ever surfaces a violation, the patch site for any `internal/multicluster → internal/controller/**` regression is the existing rule:

```yaml
# arch-go.yml — already-in-place anchor for the only encoded Layer-2 peer ban
dependenciesRules:
  - description: >
      internal/multicluster must not import internal/controller/** to break the
      Layer-2 peer cycle observed in Review #5. Other internal/** peer edges are
      intentionally not encoded here (see header). Adjust deny list narrowly if
      a documented cycle-breaking edge is required.
    package: "github.com/hashicorp/consul/internal/multicluster"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
        - "github.com/hashicorp/consul/internal/controller/**"
```

No edit required for analysis #1.

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

1. **`Coverage: 0% (PASS)` is by design, not a bug.** The `threshold.coverage: 0` floor was added in Review #5 because arch-go's "coverage" metric is the fraction of *packages* (not constraints) that any rule's `package:` glob matches — for a project this large with leaf-only `/**` patterns, the metric is meaningless and would FAIL CI on every refactor. Anyone hardening this file should leave the threshold at 0 and rely on the per-rule PASS list, not the coverage percent.

2. **Diagram constraints are deliberately out of scope (documented).** `inputs/go/1_hashicorp_consul.jpg` shows runtime topology (Consul Servers as Raft cluster, Consul Clients on each host, sidecar Proxies mediating App A ↔ App B traffic). None of these are Go import-path predicates and arch-go cannot enforce them. A reviewer who sees "100% PASS" must not infer "the runtime topology is correct" — see `arch-go.yml` lines 80-86 and the §Section-4 paragraph above. The standard escape hatch (depguard, `go mod graph`, or a custom CI check) is suggested in the YAML header.

3. **Static-import rules cannot prove transitive violations.** The YAML header (lines 75-78) is explicit: a shim package in an "allowed" prefix can re-export types from a forbidden prefix without arch-go noticing. This is a known limitation of the tool and not a defect in this file. If a critical edge needs transitive enforcement, pair the arch-go rule with `go mod graph` post-processing or a depguard rule.

4. **`agent/consul/state` interface ban was deliberately removed in Review #5.** The original ban contradicted the `Store` naming rule (which requires structs to implement an interface called `Store`). Re-adding the contents-rule interface ban without first deleting or renaming the `Store` interface in `agent/consul/state` would degrade the naming check to a no-op. Do not reintroduce it without first reading `fix-history.md` Review #5 §"Findings" and §"Fix".

5. **The `internal/multicluster → internal/controller/**` ban is the only encoded Layer-2 peer cycle break.** Other `internal/*` peer edges are intentionally permitted because the project never agreed to a strict Layer-2 sub-DAG (header lines 22-28). If a new cycle shows up in CI via `go build`, prefer to add a single targeted ban rather than re-attempting a full pairwise matrix.

---

**End of Analysis #1. Loop terminates. Ship the rule file as-is.**
