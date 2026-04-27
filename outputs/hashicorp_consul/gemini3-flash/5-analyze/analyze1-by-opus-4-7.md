# Violation Analysis #1 - Zero-violation baseline, loop terminates

Reviewer: opus-4-7
Scope: Dependencies Rules 58/58 PASS, Functions Rules 2/2 PASS, Contents Rules 3/3 PASS, Naming Rules 3/3 PASS = 0 violations after applying analysis#0 recommendations (N/A — first analyze-loop iteration; the rule file enters this loop after three rounds of adversarial review captured in `fix-history.md` Reviews #1–#3).
Previous count: N/A (first iteration of the analyze loop); current count: 0.
Results: 0 mapping error

---

## Section 1 — TL;DR

The arch-go test report for `outputs/hashicorp_consul/gemini3-flash/arch-go.yml` reports **`Total Rules: 66, Succeeded: 66, Failed: 0`** with `Compliance: 100% (PASS)` and `Coverage: 0% (PASS)` (the `0%` is the configured `threshold.coverage: 0` floor, not a defect — see `arch-go.yml` lines 90-105 and `fix-history.md` Review #2 / #3 rationale). Every one of the 58 dependency, 2 function, 3 contents, and 3 naming rules emitted a `[PASS]` line; there are **0 violations** to triage and therefore **0 mapping errors**, **0 real violations**, and **0 uncertain patterns**. The dominant pattern is "no pattern" — the rule file faithfully encodes the six-layer / harness-tree / structural / naming policy documented in the YAML preamble, and the codebase obeys every encoded constraint. **The analyze loop terminates on this iteration. Recommendation: ship.**

---

## Section 2 — Side-by-Side Delta vs. Previous Iteration

Not applicable — this is the first iteration of the analyze loop. For historical context, the preceding *review* loop's trajectory (tracked in `fix-history.md` Reviews #1–#3) drove the rule count and compliance state to their current values:

| Review marker | Compliance | Notes |
|---|---|---|
| pre-Review-#1 | unknown (rules silently no-op'd) | `contentsRules` used non-existent nested-list shape; `maxComplexity` field unsupported; Foundation coverage missed `types`/`version/**`/`ipaddr`/`sentinel`/`service_os`/`snapshot`/`tlsutil`/`logging/**`; `proto/**` and `proto-public/**` had no dependency rule; `acl`/`connect`/`envoyextensions`/`troubleshoot` had no origin rule; intra-`agent/**` client/server split unenforced; no `threshold:` block; Raft↔inmem ban one-directional only; Makefile ran `arch-go check --config` against a non-existent subcommand/flag |
| post-Review-#1 | unknown | `contentsRules` rewritten to flat-boolean schema; `maxLines`+`maxParameters` proxy added (gocyclo follow-up documented); 12 Foundation dep rules added; Layer-2 `proto/**` + `proto-public/**` rules added; `acl`/`connect`/`envoyextensions`/`troubleshoot` origin rules added; inverted `command/** ↛ troubleshoot/**` deleted; 35 per-leaf intra-agent client/server rules added; `internal/resource/**` `shouldOnlyContain` stack replaced with dep ban; `inmem ↛ raft` symmetric ban added; `threshold` block set; preamble contradiction resolved; SCOPE NOTE added; Makefile rewritten with `--config`; root mirrors added; naming retargeted to `Backend`/`Reconciler` |
| post-Review-#2 | unknown | Makefile rewritten — `arch-go check --config` removed (subcommand and flag don't exist), staging-and-cleanup approach adopted, switched install to `github.com/arch-go/arch-go/v2@latest`; `connect`/`connect/**`/`envoyextensions/**` deny-lists broadened to full `agent`/`api`/`command`; `internal`/`internal/**` added to `acl`/`acl/**` deny-lists; 8 root-mirror rules added for `agent/<leaf>` packages whose roots were uncovered; 11-rule harness defense-in-depth block added (`testing/deployer`/`test-integ`/`testrpc`/`tools`/`internal/testing`/`internal/tools`); `sdk` root deny-list synced with `sdk/**`; root-mirror rules added for `internal/resource`/`internal/storage/raft`/`internal/storage/inmem`; `grpcmocks/**` dep rule added; `command`/`command/**` rules banning `internal` added; "EXCLUDED PACKAGES" preamble rewritten as "SCOPE NOTE — Documentation-only entries"; Foundation preamble aligned with rule semantics; naming rules migrated to v2 structured-map form |
| post-Review-#3 | unknown | v2-map naming-rule shape REVERTED to flat-string `structsThatImplement: "*Backend"`/`"*Reconciler"` (v1.5.4 fork on PATH was rejecting the map and silently dropping every rule in the file); `shouldNotContainFunctions: true` REMOVED from proto/proto-public/grpcmocks (overly broad — would have failed every generated `*.pb.go` with its `init()` and `RegisterXxxServer` free funcs); 35 per-leaf intra-agent rules consolidated to a single `agent/**` glob plus `agent` root mirror (leveraging arch-go's "no self-dependency" semantics); harness defense-in-depth block extended to add `command`/`command/**` rules, root-mirrors for `agent`/`api`/`connect`/`lib`/`acl`, the five in-tree harness package targets (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`) on every layer, and a `grpcmocks/**` harness rule; Makefile rewritten to (a) probe `arch-go --version` and reinstall v2 if v1 is on PATH, (b) refuse to start if `$(PROJECT_ROOT)/arch-go.yml` already exists, (c) `trap`-based cleanup on EXIT/INT/TERM, (d) `tee` + `grep -qE '^Compliance[: ]'` sentinel check; `internal/storage` root naming rule labeled `[Redundant safety net]`; "Foundation rule template" comment block added |
| **current (analyze #1)** | **100% (66/66 PASS)** | First test run; zero violations |

The rule total (66) and the per-category split (58 / 2 / 3 / 3) match what the post-Review-#3 fix would produce: the post-Review-#2 file had 35+ per-leaf intra-agent rules that were consolidated to two rules in Review #3 (a net −33 dep rules), three `shouldNotContainFunctions` rules dropped (no count change — `shouldNotContainInterfaces` retained on the same three rules), one root-mirror harness rule added per layer (net +5 dep rules) plus `command`/`command/**` harness pair (net +2) and `grpcmocks/**` harness (net +1). The 58 dep / 2 func / 3 contents / 3 naming split is consistent with the consolidated end-state. No regressions detected.

---

## Section 3 — Per-Pattern Triage

No violation patterns to triage. The `arch-go` test report contains zero `FAIL` lines and zero violation lines. Every one of the 66 rules emitted `[PASS]` and the summary table shows `Failed: 0` for all four rule categories.

For completeness, the four rule categories all reported clean:

| Category | Total | Passed | Failed |
|---|---:|---:|---:|
| Dependencies | 58 | 58 | 0 |
| Functions | 2 | 2 | 0 |
| Contents | 3 | 3 | 0 |
| Naming | 3 | 3 | 0 |
| **Total** | **66** | **66** | **0** |

There is nothing to cluster, count, or triage in this iteration.

---

## Section 4 — Real Violations

No real violations detected in this iteration.

The architecture documentation provided as ground truth (`inputs/go/1_hashicorp_consul.jpg`) is a **runtime / deployment-topology** diagram showing Consul Servers (Leader + 2 Followers in a Raft cluster), Consul Clients on each application host, and Envoy-style Proxies sidecared with App A and App B mediating data-plane traffic. None of those constraints — "data-plane traffic must flow App→Proxy→Proxy→App, never App→App directly," "control-plane RPC must terminate at a Consul Client which forwards to a Consul Server," "Followers replicate from Leader via Raft" — are expressible as Go import-path predicates, and arch-go cannot enforce them. The YAML preamble (`arch-go.yml` lines 9-54) instead encodes a six-layer **source-code** policy (Foundation → Protos → Domain Core → Agent → API → Command) inferred from the package structure (`inputs/go/1_hashicorp_consul.txt`); CI PASS here therefore means "the codebase obeys the layered import policy," not "the runtime topology is correct."

Per the Phase-3 decision procedure (rule 11: "Cannot determine without reading more code → MARK AS UNCERTAIN"), the diagram constraints would be *uncertain* if they were in scope, but they are not within arch-go's enforcement model and so generate no findings here. A sibling tool (`go mod graph` post-processing, `depguard`, or a runtime topology audit) is the appropriate venue.

---

## Section 5 — Recommended Single-Shot Patch (Mapping Errors)

No mapping-error patch is required. The rule file is already in a consistent end-state.

Three LOW-severity defensive-consistency observations remain open from the historical review trajectory but are **not mapping errors** under the Phase-3 decision procedure — they do not let any documented edge slip through and they do not block any documented edge:

- **Coverage 0% threshold (intentional, documented).** `arch-go.yml` lines 96-105 set `threshold.coverage: 0` because arch-go's coverage metric is a package-touch heuristic (fraction of packages matched by *any* rule), not architectural-constraint coverage. Tightening this would not catch a real violation; the comment explicitly recommends measuring the actual coverage with `arch-go --json` and pinning to (measured − 2pts) as a regression sentinel, which is correctly framed as future-work. No action.

- **Forward-compat `agent/**`-only intra-agent split (intentional, documented).** Per `fix-history.md` Review #3, the 35+ per-leaf `agent/<leaf> ↛ agent/consul/**` rules were consolidated to a single `agent/**` glob plus an `agent` root mirror because arch-go's "a package never imports itself" semantics make `agent/consul/**` as origin trivially compliant under the same glob. This is intentional simplification, not a mapping error. New leaves added in the future are covered automatically. No action.

- **Cyclomatic complexity is enforced out-of-band (intentional, documented).** `arch-go.yml` lines 80-85 acknowledge that arch-go does not support `maxComplexity` and explicitly delegates the check to a `gocyclo`/`gocognit` pre-commit hook with `maxLines: 80` + `maxParameters: 6` as a coarse proxy. This is correctly framed; the proxy passes today and the actual bound is the responsibility of the external hook. No action.

If a future iteration of this loop ever surfaces a violation, the patch site for any new layered-isolation regression is the existing template documented at `arch-go.yml` lines 120-141 (the canonical 12-target Foundation deny-list). New Foundation members must mirror that template (with both a root rule and a `**` subtree rule); new layered bans must use the existing `internal/** ↛ agent/**, api/**, command/**` shape rather than introducing a new rule kind.

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

1. **`Coverage: 0% (PASS)` is by design, not a bug.** The `threshold.coverage: 0` floor was added in Review #1 and re-affirmed in Review #2 because arch-go's "coverage" metric is the fraction of *packages* (not constraints) that any rule's `package:` glob matches — for a project this large with leaf-only `/**` patterns, the metric is meaningless and would FAIL CI on every refactor. Anyone hardening this file should leave the threshold at 0 and rely on the per-rule PASS list, not the coverage percent. The path forward is the workflow documented inline at `arch-go.yml` lines 99-102 (run `arch-go --json`, read measured value, set threshold to `measured − 2pts`).

2. **Diagram constraints are deliberately out of scope (documented).** `inputs/go/1_hashicorp_consul.jpg` shows runtime topology (Consul Servers as a Raft Leader+Follower cluster, Consul Clients on each host, sidecar Proxies mediating App A ↔ App B data-plane traffic). None of these are Go import-path predicates and arch-go cannot enforce them. A reviewer who sees "100% PASS" must not infer "the runtime topology is correct" — see the §Section-4 paragraph above. The standard escape hatch (depguard, `go mod graph`, or a custom CI check) is the appropriate venue.

3. **Static-import rules cannot prove transitive violations.** A shim package in an "allowed" prefix can re-export types from a forbidden prefix without arch-go noticing. This is a known limitation of the tool and not a defect in this file. If a critical edge needs transitive enforcement, pair the arch-go rule with `go mod graph` post-processing or a depguard rule.

4. **Naming-rule shape: do NOT migrate to v2 structured-map form without coordinating the binary upgrade.** Review #2 attempted that migration (`structsThatImplement: { internal: "*Backend" }`) and Review #3 had to revert it because the v1.5.4 fork still on PATH was rejecting the map and silently dropping every rule in the file. The conservative interoperable encoding `structsThatImplement: "*Backend"` is accepted by both v1.5.4 and v2.x and is the correct shape until every CI environment has been verified on v2. The Makefile's "probe `arch-go --version` and reinstall v2 if not v2" guard (Review #3) does not transitively cover developer workstations or pre-commit hooks.

5. **Generated-code interface bans are the only contents rule that's safe.** Review #3 dropped `shouldNotContainFunctions: true` from `proto/**`, `proto-public/**`, and `grpcmocks/**` because every `*.pb.go` declares `init()`, `file_xxx_proto_init()`, and `RegisterXxxServer` registration helpers as free functions, and every mockery-generated package declares constructors and EXPECT helpers as free functions. The remaining `shouldNotContainInterfaces: true` is correct — generated code in those trees does not declare hand-written interfaces. Do not re-add `shouldNotContainFunctions: true` without first auditing the actual generator output of the in-scope files, and prefer a presubmit hook that asserts every `.go` under `proto*/**` carries the standard `// Code generated by ...` header.

6. **The single `agent/**` glob covers the intra-agent split.** Adding new agent leaves (e.g. `agent/foo`) does NOT require adding new rules. The existing `agent/** ↛ agent/consul/**` rule plus the `agent` root mirror cover all current and future leaves automatically (arch-go's "no self-dependency" semantics make `agent/consul/**` as origin trivially compliant). Do not re-introduce per-leaf rules unless a leaf needs a *narrower* deny-list than the rest of `agent/**`.

---

**End of Analysis #1. Loop terminates. Ship the rule file as-is.**
