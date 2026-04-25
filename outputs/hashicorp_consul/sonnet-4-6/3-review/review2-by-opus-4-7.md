### Executive Summary

- **Total documented constraints identified:** 24  
  - **4** from the architecture image (`inputs/go/1_hashicorp_consul.jpg`): data-plane vs control-plane separation, control-path directionality, proxy mediation for applications, and control-vs-data logic separation.  
  - **20** from the layered model and comments in `outputs/hashicorp_consul/sonnet-4-6/arch-go.yml` (five-layer ordering, foundation / internal / agent / server / leaf boundaries, parallel agent vs `agent/consul`, listed exclusions, intra-`agent/consul` edges for `state` / `fsm` / `discoverychain`, acknowledgement of transitivity limits, and expectations around generated vs hand-written code).

- **Total rules generated:** 89 (per `arch-go` report: 73 dependency rules, 4 function rules, 5 contents rules, 7 naming rules).

- **Coverage rate (constraint → at least one corresponding `dependenciesRules` / `contentsRules` / `namingRules` / `functionsRules` assertion):** **15 / 24** constraints have meaningful automated coverage at import or file level. **9** constraints are either **not representable** as static imports, **not enforced**, or **only partially** aligned with the deny lists.

- **Critical gaps** (constraints with zero or materially misleading enforcement):
  1. **Image C1–C4:** Runtime topology (servers, clients, proxies, apps) is not mapped to import-level rules; nothing enforces “data plane does not reach server state” or “apps only talk via proxy abstractions” in Go packages.
  2. **Invisible packages:** Several import paths in `inputs/go/1_hashicorp_consul.txt` are not the `package:` target of any rule (module root, integration/test harness trees, `internal/testing/**`, `tools/**`), so regressions there never trip `arch-go`.
  3. **Foundation deny-list skew:** Some Layer-1 `package:` rules omit `shouldNotDependsOn` entries that sibling foundation rules include (e.g. `types` vs `lib/**`), so the same architectural sentence in the header is enforced unevenly.
  4. **Glob edge cases:** Patterns of the form `…/**` may fail to attach to the **parent** package directory (e.g. `github.com/hashicorp/consul/api` vs `github.com/hashicorp/consul/api/**`, same for `command`, `connect`, `acl`, `logging`, `agent/grpc-internal`, `agent/grpc-middleware`, `internal/controller/**` vs the root `internal/controller` package). If the matcher requires a segment after `/`, those packages have **no** dependency rule.

- **Tooling note:** `Coverage: 0% [FAIL]` in the report is **arch-go’s internal coverage heuristic**, not “fraction of documented constraints encoded.” It does not invalidate passing rules but should not be read as architectural completeness.

- **Overall verdict:** `FAIL`

---

### Findings

```
[1] SEVERITY: CRITICAL
Category: Coverage Gap
Affected Rule / Constraint: Image C1–C4 (operational boundaries: control vs data plane, client–server control path, proxy mediation, application isolation).

What is wrong:
The supplied diagram describes **deployment/runtime** roles (Consul servers, clients, sidecar proxies, applications) and **communication paths**, not the `github.com/hashicorp/consul/...` package layering in `arch-go.yml`. None of the four diagram-level constraints is expressed as a `dependenciesRules` entry keyed to proxy vs agent vs server **packages** (and static import analysis cannot see RPC-only coupling).

Why it matters:
A team could introduce a direct import from a package that conceptually belongs on the “application / data path” side into `github.com/hashicorp/consul/agent/consul/**` (or the opposite) and still receive **100% rule compliance**, because the diagram’s boundaries are not in the rule set.

How to fix it:
Treat the image as **non-automatable** unless you define an explicit package taxonomy (e.g. map `agent/xds/**` and `connect/**` to “data plane”) and add `shouldNotDependsOn` pairs for those paths, **or** accept diagram-only constraints as out-of-scope for `arch-go` and document that CI does not enforce them. No small YAML snippet fixes the missing mapping; the fix is either **documentation scope** or **a new package-level model** derived from the diagram.
```

```
[2] SEVERITY: CRITICAL
Category: Coverage Gap | Structural Gap
Affected Rule / Constraint: H2 (Foundation completeness); package list in `inputs/go/1_hashicorp_consul.txt` vs union of all `package:` globs.

What is wrong:
Multiple first-party packages in the structure file are **never** the subject of a `dependenciesRules` `package:` pattern (hence no outbound dependency constraints from those trees). Examples: `github.com/hashicorp/consul` (module root), `github.com/hashicorp/consul/testing/deployer/**`, `github.com/hashicorp/consul/test-integ/**`, `github.com/hashicorp/consul/testrpc`, `github.com/hashicorp/consul/tools/internal-grpc-proxy`, `github.com/hashicorp/consul/internal/testing/errors`, `github.com/hashicorp/consul/internal/testing/golden`. The YAML header **names** several of these as excluded or test-only, but exclusion is **commentary only**—there is no `arch-go` mechanism in this Makefile/config that removes them from the module graph or attaches “allow anything” rules.

Why it matters:
A forbidden import introduced only from e.g. `test-integ` glue code into `agent/consul/state` would not be attributed to any **source** rule if no rule targets `test-integ/**`. Conversely, nothing stops “excluded” packages from becoming production entry points in a future refactor while staying invisible.

How to fix it:
Either (a) add explicit `dependenciesRules` for each non-excluded production subtree that should obey the same layer model, or (b) use `arch-go`’s supported exclude flags / file patterns (if your version supports them) so CI **fails** if excluded paths gain production imports, or (c) narrow `1_hashicorp_consul.txt` to “packages we assert architecture on” and document that other trees are unchecked. Example for one harness (tighten lists to your real policy):

```yaml
  - description: >
      Integration test harness packages must not import server consensus internals.
    package: "github.com/hashicorp/consul/test-integ/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
```

Repeat for `testing/deployer/**`, `testrpc`, `tools/**`, and `internal/testing/**` as needed—or explicitly mark them as out of scope in the assignment rubric instead of in YAML comments only.
```

```
[3] SEVERITY: HIGH
Category: Overly Narrow | Coverage Gap
Affected Rule / Constraint: H2 (Layer 1 foundation must not depend on higher layers); comparison across `lib/**`, `types`, `acl/**`, `proto/**`, `proto-public/**`, `logging/**`.

What is wrong:
Foundation rules are **not symmetric**. For example, `github.com/hashicorp/consul/lib/**` forbids `github.com/hashicorp/consul/troubleshoot/**` and `github.com/hashicorp/consul/envoyextensions/**`, while `github.com/hashicorp/consul/types` omits both. Similarly, `github.com/hashicorp/consul/acl/**` omits `troubleshoot/**` (present on `lib/**` and `tlsutil`). `github.com/hashicorp/consul/logging/**` omits `envoyextensions/**` and `troubleshoot/**` relative to stricter peers. Under a literal reading of the header (“Foundation … no internal deps”), these omissions are **holes**, not intentional peer imports.

Why it matters:
`github.com/hashicorp/consul/types` could add `import "github.com/hashicorp/consul/envoyextensions/xdscommon"` (or `troubleshoot/...`) and pass the current `types` rule while violating the spirit of “lowest layer.” The gap is **class-wide** for any foundation package with a shorter deny list than `lib/**`.

How to fix it:
Normalize every Layer-1 `shouldNotDependsOn.internal` list to the **same** superset (agent, internal, command, api, connect, envoyextensions, troubleshoot, and any other leaf namespaces you treat as non-foundation), or generate it from a YAML anchor to avoid drift:

```yaml
  - description: >
      types must not import any non-foundation consul namespace (aligned with lib/**).
    package: "github.com/hashicorp/consul/types"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

Apply the same alignment to `acl/**`, `proto/**`, `proto-public/**`, `logging/**`, and any other foundation subtree whose list is shorter than `lib/**`.
```

```
[4] SEVERITY: HIGH
Category: Coverage Gap | Glob Syntax Error
Affected Rule / Constraint: H4–H5 (all production subtrees under `api`, `command`, `connect`, `acl`, `logging`, `agent/grpc-internal`, `agent/grpc-middleware`, `internal/controller`).

What is wrong:
Many rules use `package: ".../**"` without a companion `package: "..."` line for the **directory root** import path. In path-style matchers, `foo/**` sometimes matches only strict descendants (`foo/bar`, not `foo`). The package list explicitly includes roots such as `github.com/hashicorp/consul/api`, `github.com/hashicorp/consul/command`, `github.com/hashicorp/consul/connect`, `github.com/hashicorp/consul/acl`, `github.com/hashicorp/consul/logging`, `github.com/hashicorp/consul/agent/grpc-internal`, `github.com/hashicorp/consul/agent/grpc-middleware`, and `github.com/hashicorp/consul/internal/controller`.

Why it matters:
If the matcher excludes the root package, **every** `dependenciesRules` block for that subtree is a no-op for the `.go` files in the root directory itself. That is a classic silent false negative (violations in the exact package the human thinks is “the API” or “the gRPC internal stack”).

How to fix it:
For every subtree where the repo has both `…/pkg` and `…/pkg/sub` packages, duplicate the rule with an exact root pattern **or** confirm in `arch-go` documentation that `/**` includes the prefix package and remove this finding if proven inclusive. Defensive YAML:

```yaml
  - description: Public api root package must match leaf dependency constraints.
    package: "github.com/hashicorp/consul/api"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

Mirror the same “root + /**” pairing for `command`, `connect`, `acl`, `logging`, `agent/grpc-internal`, `agent/grpc-middleware`, and `internal/controller` if those roots contain imports you intend to constrain.
```

```
[5] SEVERITY: MEDIUM
Category: Structural Gap | Transitivity
Affected Rule / Constraint: H1 (dependencies only point downward); header note on transitivity.

What is wrong:
`shouldNotDependsOn` only inspects **direct** imports. Any constraint of the form “Layer A must not depend on Layer C” is not enforced if the code uses `A → B → C` with `B` in a permitted band (already acknowledged in the YAML header, but still an architectural blind spot).

Why it matters:
Refactors can introduce a small “shim” package in an allowed prefix that re-exports types from a forbidden prefix. CI stays green while the dependency graph in terms of **types** or **behavior** crosses the intended wall.

How to fix it:
Keep the header’s honesty: pair `arch-go` with `go mod graph` / custom scripting for critical edges, or add **additional** rules on the intermediate package `B` if it is stable enough to name. No single YAML edit removes the fundamental limit.
```

```
[6] SEVERITY: MEDIUM
Category: Coverage Gap
Affected Rule / Constraint: Phase 16 / H4 (test vs production scope); Makefile `arch-check` target.

What is wrong:
The Makefile runs plain `arch-go check` with no `--exclude-files` (or equivalent) for `*_test.go`. The YAML header admits test-only paths but does not configure test-file exclusion. Cross-layer imports in tests (legitimate or accidental) are evaluated under the same rules as production.

Why it matters:
Either you get **false positives** when a test imports a distant helper to set up state, or—if maintainers habitually weaken rules to silence tests—you get **false negatives** for real production coupling copied from test patterns.

How to fix it:
If your `arch-go` version supports file excludes, wire them into the Make target after verifying policy:

```makefile
	@cd "$(PROJECT_ROOT)" && arch-go check --exclude-files '*_test.go'
```

If excludes are unsupported, document that **all** `dependenciesRules` are test-inclusive and accept the false-positive risk, or split test helpers into dedicated packages with explicit, looser rules.
```

```
[7] SEVERITY: MEDIUM
Category: Vacuous Rule | Overly Broad
Affected Rule / Constraint: `contentsRules` for `github.com/hashicorp/consul/internal/controller` (lines ~1059–1067).

What is wrong:
The rule sets `shouldOnlyContain` with `interfaces: true`, `structs: true`, `functions: true`—which is effectively “non-empty Go packages may contain the union of everything Go packages contain.” It does not restrict imports, file layout, or concrete vs interface balance in a non-vacuous way.

Why it matters:
It inflates “contents rule count” and creates a false sense that `internal/controller` is structurally guarded when the predicate is tautological for normal packages.

How to fix it:
Replace with a **real** structural constraint (e.g. forbid `functions: true` in specific subpackages, forbid `implementation` packages in the root controller package, or drop the rule). Example of a non-vacuous direction:

```yaml
  - description: >
      internal/controller root should define contracts; avoid concrete reconciler implementations here.
    package: "github.com/hashicorp/consul/internal/controller"
    shouldNotContainAnyOf:
      - implementations: true
```

(Confirm the exact `contentsRules` schema keys for your `arch-go` version before applying.)
```

```
[8] SEVERITY: MEDIUM
Category: Semantic Error | Overly Narrow
Affected Rule / Constraint: Layer 5 `github.com/hashicorp/consul/troubleshoot/**` vs `github.com/hashicorp/consul/agent/**` (non-`agent/consul`).

What is wrong:
`troubleshoot/**` forbids `command/**` and `agent/consul/**` only. It does **not** forbid imports of other `github.com/hashicorp/consul/agent/...` trees (DNS, xds, grpc-external, etc.). The rule comment claims troubleshoot may depend on “api and agent packages,” which is broader than what is denied—but nothing **denies** an import of `agent/consul` beyond the explicit `agent/consul/**` entry (already covered). The asymmetry is: **any** deep agent import except `agent/consul` is allowed, including packages that are arguably “server-adjacent” depending on refactor.

Why it matters:
If the product intent is “troubleshoot stays a leaf diagnostic tool,” the current rule set under-constrains imports into large parts of the agent stack. If the intent is “troubleshoot may freely use the client agent,” the header should say so explicitly; today the diagram does not justify the exact allow-list.

How to fix it:
Decide the intended surface: either add explicit `shouldNotDependsOn` for agent subtrees that must remain unreachable from `troubleshoot/**`, or document that **all** non-server agent packages are fair game and remove ambiguity from the YAML comment so it matches the deny list verbatim.
```

```
[9] SEVERITY: LOW
Category: Wrong Layer | Redundancy
Affected Rule / Constraint: `github.com/hashicorp/consul/version` vs `github.com/hashicorp/consul/version/**`; overlapping `agent/consul/state` / `lib/**` / `internal/storage/**` across multiple rule types.

What is wrong:
`version` and `version/**` duplicate the same dependency assertion. Multiple rule types (dependencies + contents + naming + functions) target the same packages (`agent/consul/state`, `internal/storage/**`, `lib/**`), which is defensible but increases maintenance cost.

Why it matters:
Drift: future edits might update one duplicate block and not the other, reintroducing asymmetry.

How to fix it:
Collapse `version` rules into a single `package: "github.com/hashicorp/consul/version/**"` if that glob is inclusive of the root `version` package in your matcher, or keep both but factor shared deny lists via YAML anchors. Low priority cleanup.
```

```
[10] SEVERITY: LOW
Category: Coverage Gap
Affected Rule / Constraint: Header exclusion list for `grpcmocks/**`.

What is wrong:
`grpcmocks/**` has a **contents** rule (“generated mocks”) but **no** `dependenciesRules` entry. The header states mock packages are excluded from “production rules,” yet the contents rule still constrains them.

Why it matters:
Minor inconsistency between comments and rule inventory; not a security issue, but reviewers may assume mocks are wholly unconstrained when they are not.

How to fix it:
Either add a one-line comment above `contentsRules` for `grpcmocks/**` clarifying “structural hygiene only, not layer model,” or move mock packages into a separate optional profile. Optional—no YAML strictly required.
```

```
[11] SEVERITY: LOW
Category: Overly Broad (tooling / portability)
Affected Rule / Constraint: `outputs/hashicorp_consul/sonnet-4-6/Makefile` `arch-check` / `arch-check-verbose`.

What is wrong:
The Makefile uses POSIX shell constructs (`which`, `[ -z "$(ARCH_GO_BIN)" ]`, `$(shell which ...)`) and `go install` assuming a Unix-like `PATH` layout. On a stock Windows developer shell without GNU Make + sh, these targets may not run as written even though the Go module is Windows-capable.

Why it matters:
Local or CI jobs that invoke `make arch-check` on Windows can fail before `arch-go` runs, unrelated to architecture quality.

How to fix it:
Document “requires MSYS2 / Git Bash / WSL” or provide a `pwsh`/`cmd` alternative that uses `where.exe` and PowerShell conditionals. Out of scope for `arch-go.yml` itself; still a defect in the **delivered enforcement bundle** paired with this review.
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

### Phase trace (methodology checklist — condensed)

| Phase | Outcome |
|-------|---------|
| **1 — Coverage audit** | Constraint inventory: **24** (diagram + YAML header). Rule inventory: **89** distinct checks. Gap matrix: diagram constraints **not** mapped to packages (**Finding 1**); several `1_hashicorp_consul.txt` paths unmatched by any `package:` (**Finding 2**); foundation deny-list drift (**Finding 3**); possible `/**` root mismatch (**Finding 4**). |
| **2 — Rule precision** | Vacuous / near-vacuous contents rule on `internal/controller` root (**Finding 7**). No `..` glob syntax errors detected. Makefile does not use `arch-go` `allow:` / `--ignore` abuse (not applicable to this YAML schema). |
| **3 — Semantic correctness** | Spot-checked: lower layers use `shouldNotDependsOn` to forbid upward imports; `command/**` vs `agent/consul/**`, `connect/**`, `api/**`, and server rules align with the header’s narrative. `troubleshoot/**` policy vs comment (**Finding 8**). Module path in all rules matches `github.com/hashicorp/consul`; `go:build` scope not verifiable from inputs alone (**Finding 6** for test files). |
| **4 — Structural completeness** | Intra-`agent/consul` edges for `state`, `fsm`, `discoverychain` are explicitly modeled. Transitive blind spot acknowledged (**Finding 5**). Redundant / overlapping patterns noted (**Finding 9**). |

---

*Reviewer model: opus-4-7 · Review iteration: #2 · Target rules: `outputs/hashicorp_consul/sonnet-4-6/arch-go.yml` + paired Makefile (as supplied).*
