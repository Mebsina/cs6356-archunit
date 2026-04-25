### Executive Summary

- **Total documented constraints identified:** 28 (five-layer dependency ordering and peer rules from the YAML preamble; harness/scaffolding import bans; partial intra-`agent/consul` acyclicity; Makefile/module invocation contracts; four runtime/control-data-plane constraints read off a typical Consul reference diagram for `inputs/go/1_hashicorp_consul.jpg`, noting the YAML explicitly waives automating those).
- **Total rules generated:** 109 (94 `dependenciesRules`, 4 `functionsRules`, 4 `contentsRules`, 7 `namingRules`, per the supplied arch-go execution summary).
- **Coverage rate:** Approximately **20 / 28** constraints have a direct, meaningful `shouldNotDependsOn` (or equivalent) analogue on the real package list; **4** diagram-level constraints are intentionally out of scope per comments (**0 / 4** enforced); **~4** additional constraints are only weakly or unevenly enforced (Layer-2 “Foundation only” wording, `connect/**` vs `agent/**`, leaf-only packages vs `/**` subjects, and arch-go’s own **Coverage rate: 0% [FAIL]** heuristic, which must not be confused with architectural completeness).
- **Critical gaps** (constraints with zero or unreliable enforcement):
  1. **Runtime / deployment topology** from `inputs/go/1_hashicorp_consul.jpg` (control vs data plane, mediated proxy paths, isolation of app traffic from server control APIs) — **no** `package:`/`shouldNotDependsOn` encoding; comments disclaim scope.
  2. **Layer 2 “depends on Foundation only”** (header narrative) — **no** pairwise or blanket ban on `internal/* → internal/*` imports; `internal/controller` may depend on `internal/storage`, `internal/resource`, etc. without any rule firing if those imports stay inside `internal/**`.
  3. **Leaf `agent/…` packages** whose only import path is the directory root (e.g. `github.com/hashicorp/consul/agent/ae`, `…/agent/leafcert`, `…/agent/proxycfg`, `…/agent/structs`) are guarded only by patterns of the form `…/agent/<name>/**`. Per this repository’s own generator guidance (`prompts/1-generate-go.md`: `…/service/**` matches **sub-packages** of `service`), those rules may match **zero** packages, so **imports from `agent/consul/**` in those roots would not be checked** unless arch-go’s implementation differs from that documented contract.
  4. **`github.com/hashicorp/consul/connect/**` as a public SDK** — rules forbid `agent/consul/**`, `command/**`, and `troubleshoot/**` but **do not** forbid other `agent/**` paths; a dependency such as `connect → github.com/hashicorp/consul/agent/structs` would **pass** static checks while violating the stated “client-facing library” intent in the header.
- **Overall verdict:** **FAIL** — compliance is 100% on evaluated rules, but several **high-severity coverage holes**, ambiguous glob coverage on leaf directories, the tool’s failed coverage metric, and intentional non-coverage of diagram constraints mean the rule set does **not** prove adherence to the union of documentation, diagram, and “pessimistic” static guarantees.

---

### Findings

[01] SEVERITY: CRITICAL  
Category: Coverage Gap  
Affected Rule / Constraint: Diagram constraints (typical Consul architecture in `inputs/go/1_hashicorp_consul.jpg`: servers vs clients vs sidecars, control-plane RPC vs dataplane traffic, “no backdoor” control paths); Phase 1 constraints D1–D4

What is wrong:  
The generated `arch-go.yml` **disclaims** enforcing deployment/runtime topology and contains **no** rules keyed to runtime roles (data plane vs control plane vs app). All enforcement is **import-path prefix** layering.

Why it matters:  
A change can satisfy every `dependenciesRules` entry while violating the diagram (for example, consolidating control logic under an `agent/**` package that proxies in ways the diagram forbids). CI would still report PASS.

How to fix it:  
Either narrow the **documented claim of conformance** to “module-prefix layering only,” or introduce an explicit **package taxonomy** (new directory prefixes or tags) plus matching `shouldNotDependsOn` rules, or non–arch-go checks. No small YAML snippet fully replaces the diagram.

```yaml
# Example direction only — requires agreed package prefixes first.
# dependenciesRules:
#   - description: Dataplane-facing packages must not import server consensus.
#     package: "github.com/hashicorp/consul/<dataplane-prefix>/**"
#     shouldNotDependsOn:
#       internal:
#         - "github.com/hashicorp/consul/agent/consul/**"
```

---

[02] SEVERITY: CRITICAL  
Category: Coverage Gap / Semantic Error  
Affected Rule / Constraint: Header Layer 2 — “Internal Framework (depends on Foundation only)”; implied `internal/* → internal/*` restriction

What is wrong:  
The preamble describes Layer 2 as depending on **Foundation only**. In `dependenciesRules`, Layer-2 subjects (`internal/controller/**`, `internal/storage/**`, `internal/resource/**`, etc.) **forbid** upward imports (`agent/**`, `command/**`, `api/**`, `connect/**`, `troubleshoot/**`) but **never forbid** peer imports among `github.com/hashicorp/consul/internal/...` packages (nor `envoyextensions/**` from `internal/...` except where coincidentally blocked).

Why it matters:  
`internal/controller` can import `internal/storage`, `internal/resource`, `internal/gossip/...`, etc. If the documentation’s “Foundation only” is taken literally, that is a **false negative** factory: the rules **encode a different architecture** than the prose.

How to fix it:  
Pick one story: **(A)** revise the header to “Layer 2 must not import Layers 3–5” (matches the YAML), or **(B)** add explicit bans, e.g. `internal/controller/**` `shouldNotDependsOn` `internal/storage/**`, `internal/resource/**`, … — only after confirming the real repo does not already violate the stricter model.

```yaml
  # Illustrative peer ban — validate against real imports before enabling.
  - description: >
      internal/controller must not depend on internal/storage directly
      (example peer-layer isolation — adjust to match agreed DAG).
    package: "github.com/hashicorp/consul/internal/controller/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal/storage/**"
```

---

[03] SEVERITY: HIGH  
Category: Overly Narrow / Vacuous Rule  
Affected Rule / Constraint: Phase 2 items 5–7; `prompts/1-generate-go.md` glob note; packages `github.com/hashicorp/consul/agent/ae`, `…/agent/leafcert`, `…/agent/proxycfg`, `…/agent/structs`, and many other **single-directory** agent trees in `inputs/go/1_hashicorp_consul.txt`

What is wrong:  
Numerous Layer-3 rules use only `package: "github.com/hashicorp/consul/agent/<segment>/**"` **without** a sibling `package: "github.com/hashicorp/consul/agent/<segment>"` entry. This repository’s generator instructions state that `internal/service/**` matches **sub-packages** of `service` — which strongly suggests `agent/ae/**` does **not** match `…/agent/ae` when `ae` has no subpackages in the package list.

Why it matters:  
If the tool follows that contract, **dependency rules for those agent subtrees never run on the only package that exists**, so forbidden imports of `github.com/hashicorp/consul/agent/consul/**` (or `api/**`, etc.) from `agent/ae` would **not** be reported.

How to fix it:  
For every leaf-only directory in `1_hashicorp_consul.txt`, add an explicit **non-`/**` rule mirroring the `/**` rule** (the file already does this correctly for `lib` vs `lib/**`, `acl` vs `acl/**`, `internal/controller` vs `internal/controller/**`, etc.).

```yaml
  - description: >
      agent/ae (Layer 3) must not import the server, CLI, public API, or
      Connect client packages (root package; glob /** may not apply here).
    package: "github.com/hashicorp/consul/agent/ae"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

---

[04] SEVERITY: HIGH  
Category: Coverage Gap / Semantic Error  
Affected Rule / Constraint: Layer 5 `connect/**` “clean client-facing library”; rules `package: "github.com/hashicorp/consul/connect/**"` and `connect` root

What is wrong:  
`connect/**` rules forbid `agent/consul/**`, `command/**`, and `troubleshoot/**` but **do not** list `github.com/hashicorp/consul/agent/**` (excluding consul) or `internal/**`. Therefore **`connect` may import `github.com/hashicorp/consul/agent/structs` or `github.com/hashicorp/consul/internal/...`** without violation.

Why it matters:  
The header positions `connect/**` as a **leaf** client SDK comparable in spirit to `api/**`. The `api/**` rules correctly ban **all** `agent/**` and `internal/**`. The asymmetry is a **large hole** for “SDK purity.”

How to fix it:  
Align `connect/**` with `api/**` outbound denials (at minimum `agent/**` and `internal/**`, minus any deliberate exceptions documented with rationale).

```yaml
  - description: >
      connect SDK must not import agent, internal framework, CLI, api, or
      troubleshoot packages (treat like api leaf for static enforcement).
    package: "github.com/hashicorp/consul/connect/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
```

---

[05] SEVERITY: HIGH  
Category: Structural Gap / Transitivity Gaps  
Affected Rule / Constraint: Phase 4 items 14–15; YAML header admission on shims and transitivity

What is wrong:  
The header already admits that a package in an “allowed” prefix can **re-export** types from a forbidden prefix, creating **transitive** violations arch-go cannot see. There are **no** companion rules on stable choke-point packages, and no `go mod graph` / custom lint integration.

Why it matters:  
Example sketch: `lib/safe` (allowed) wraps types from `agent/consul/state`; Foundation packages import `lib/safe` only — **no** `shouldNotDependsOn` edge fires on the importing package.

How to fix it:  
Add **pairwise** rules on known shims, or enforce **forbidden imports** via `go vet` / staticcheck custom analyzers / depguard in addition to arch-go.

---

[06] SEVERITY: MEDIUM  
Category: Overly Broad / Semantic Error  
Affected Rule / Constraint: `contentsRules` for `github.com/hashicorp/consul/agent/consul/state` — `shouldNotContainAnyOf: [interfaces: true]`

What is wrong:  
The rule bans **any** `interface` declarations in `agent/consul/state`. Small private interfaces (testing seams, minimal read-only views) are common in large stores and may be **legitimate** even if the narrative prefers interfaces elsewhere.

Why it matters:  
Tightening the store package for “no dependency magnet” is good policy, but a **binary** interface ban risks **false positives** on benign unexported interfaces and encourages moving types solely to satisfy the linter rather than improving boundaries.

How to fix it:  
Narrow the rule (if arch-go supports it in your version) to exported interfaces only, or replace with a **custom** check that flags **large** public interfaces or interfaces consumed outside the package.

---

[07] SEVERITY: MEDIUM  
Category: Overly Broad  
Affected Rule / Constraint: `contentsRules` / `shouldOnlyContain` on `proto/**`, `proto-public/**`, `grpcmocks/**` (structs only; interfaces and functions false)

What is wrong:  
Generated protobuf Go and gRPC mocks routinely include **methods** (functions on types), registration helpers, and sometimes hand-maintained glue. The YAML comment already warns that **tool upgrades** may reclassify declarations and cause churn.

Why it matters:  
These rules are **fragile**: a benign regeneration can flip CI to FAIL even when architecture is unchanged.

How to fix it:  
Pin generator versions, add allow-listed file globs if the tool supports exclusions, or scope `shouldOnlyContain` to specific generated subdirectories only.

---

[08] SEVERITY: MEDIUM  
Category: Structural Gap  
Affected Rule / Constraint: Intra-`agent/consul` DAG (header: “only partially encoded”); rules for `state`, `fsm`, `discoverychain`

What is wrong:  
Only a **slice** of server internals is constrained (`state`, `fsm`, `discoverychain`). Other `agent/consul/*` packages (e.g. `stream`, `watch`, `xdscapacity`, `auth`, `controller`, … per `1_hashicorp_consul.txt`) participate in the same DAG risks but have **no** pairwise `shouldNotDependsOn` rules.

Why it matters:  
New cycles or forbidden upward imports between server subsystems can form **outside** the three watched packages.

How to fix it:  
Extend the documented DAG with agreed edges, or generate **cycle detection** (`go build`/import graph) in CI instead of hand-maintaining a partial matrix.

---

[09] SEVERITY: MEDIUM  
Category: Wrong Layer / Incomplete Intra-layer Matrix  
Affected Rule / Constraint: `agent/consul/fsm` `shouldNotDependsOn` list vs `agent/consul/state` list

What is wrong:  
`agent/consul/state` forbids importing `github.com/hashicorp/consul/agent/consul/reporting` among others, but the **`fsm` rule’s deny list omits `reporting`** while still forbidding other peers. If the intended DAG is “fsm below reporting,” the YAML is **inconsistent**; if not, the asymmetry is unexplained.

Why it matters:  
Reviewers cannot tell whether **fsm → reporting** is intentionally allowed or an **accidental omission** — optimism is a bug.

How to fix it:  
Reconcile lists with the real import graph; document each omitted edge explicitly in comments, or add/remove `shouldNotDependsOn` entries to match the agreed DAG.

---

[10] SEVERITY: MEDIUM  
Category: Test vs. Production Scope  
Affected Rule / Constraint: Phase 4 item 16; Makefile note on `*_test.go`

What is wrong:  
`arch-go check` analyzes **Go sources** including tests unless excluded. The Makefile explicitly notes upstream arch-go may not support `--exclude-files`. Many `shouldNotDependsOn` policies are **production-layer** narratives but apply to test code too.

Why it matters:  
Test-only imports that legitimately cross layers for fakes (or accidental `agent/consul` imports in tests under `agent/**`) produce **false positives**, or conversely **mask** production violations if tests are the only consumers analyzed under a matched package.

How to fix it:  
If/when supported, add invocation flags or split configs for `*_test.go`; otherwise document that **test imports are first-class** under these rules and adjust expectations.

---

[11] SEVERITY: LOW  
Category: Semantic Error / Maintainability  
Affected Rule / Constraint: `troubleshoot/**` rule vs comment (“may import `agent/**` except `agent/consul/**` and `api/**`”)

What is wrong:  
The comment claims **`api/**` is in scope** for troubleshoot, but the `shouldNotDependsOn` list only bans `command/**` and `agent/consul/**`. **`troubleshoot` may import `internal/**`** without violating the rule, which the prose does not discuss.

Why it matters:  
Misaligned comments cause **wrong mental models** during review and refactors.

How to fix it:  
Edit the description to match the actual deny list, or extend the deny list to match the intended policy.

---

[12] SEVERITY: LOW  
Category: Redundancy  
Affected Rule / Constraint: Multiple rule types targeting `github.com/hashicorp/consul/agent/consul/state`

What is wrong:  
`agent/consul/state` appears across **dependencies**, **contents**, **naming**, and **functions** rules. That is coherent, not a bug, but **duplicates cognitive load** and risks contradictory policy if one block is edited without the others.

Why it matters:  
Maintainability and drift risk, not immediate false negatives.

How to fix it:  
Keep as-is, or group under a single YAML anchor / documented “state package policy” section for synchronized edits.

---

[13] SEVERITY: LOW  
Category: Structural Gap  
Affected Rule / Constraint: `functionsRules` — `lib/**` and `internal/storage/**` `maxReturnValues`

What is wrong:  
The same **`/**` vs directory-root** ambiguity as dependency rules: `github.com/hashicorp/consul/lib` (root) may not match `lib/**` per the generator’s sub-package wording, so **return-value limits might not apply** to the root `lib` package.

Why it matters:  
Low severity (policy is stylistic), but it is **inconsistent** with the file’s own pattern of duplicating root + `/**` elsewhere.

How to fix it:  

```yaml
  - description: >
      lib root package return-signature guard (pair with lib/**).
    package: "github.com/hashicorp/consul/lib"
    maxReturnValues: 3
```

---

[14] SEVERITY: LOW  
Category: Glob Syntax Error / Tooling  
Affected Rule / Constraint: Makefile `ARCH_GO_BIN := $(shell command -v arch-go …)`  

What is wrong:  
`ARCH_GO_BIN` is expanded **once** when `make` reads the Makefile. If the first target installs arch-go, a later recipe might still see an **empty** `ARCH_GO_BIN` in the same invocation depending on how targets are ordered (minor footgun).

Why it matters:  
Unlikely in the documented `arch-check` flow, but confusing for contributors extending the Makefile.

How to fix it:  
Re-resolve `arch-go` inside the recipe with `command -v arch-go` instead of relying solely on the top-level variable.

---

### Severity Definitions

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | A documented constraint has zero enforcement — real violations will pass CI |
| **HIGH** | A rule is so imprecise it either misses a whole class of violations or produces widespread false positives |
| **MEDIUM** | A rule is technically correct but incomplete — some violations in the constraint's scope escape |
| **LOW** | Cosmetic or maintainability issue (misleading comment, redundant rule, style) |

---

### Reviewer note on inputs

Architecture diagram file: `inputs/go/1_hashicorp_consul.jpg` (binary image in workspace). This review treats **runtime topology** constraints as present **only at the level of a typical Consul diagram** and cross-checks them against the YAML’s explicit **non-goals**; it does not OCR the image. Package ground truth: `inputs/go/1_hashicorp_consul.txt`. Generated artifacts reviewed: `outputs\hashicorp_consul\sonnet-4-6\arch-go.yml` and the supplied Makefile excerpt. **arch-go** was not executed in this environment (Go toolchain not available on the review host), so glob matching behavior is flagged where the **project’s own prompt contract** conflicts with exhaustive leaf coverage.
