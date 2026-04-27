# Adversarial Review #1 — `outputs/hashicorp_consul/gemini3-flash/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: gemini3-flash
Project: HashiCorp Consul
Round: 1

---

## Executive Summary

- **Total documented constraints identified:** 18
  - 6 inter-layer hierarchy invariants (C1–C6 in the YAML preamble)
  - 7 exclusion declarations (C7a–C7g in the preamble's "EXCLUDED PACKAGES" block)
  - 1 intra-layer storage isolation (Raft ↔ inmem) — C8
  - 2 naming conventions (Store, Repository) — C12, C13
  - 2 structural conventions for Internal/Proto/Mocks (interfaces/structs/functions) — C9, C10, C11
  - 2 function-quality conventions (complexity ≤ 15, return values ≤ 2) — C14, C15
- **Total rules generated:** 14 (7 dependency + 3 contents + 2 naming + 2 functions), per the test report.
- **Effectively enforcing rules:** **9 of 14**. The other 5 are *silent no-ops* — arch-go parses them, the test report prints `[PASS]`, but the constraint list is empty (`should comply with []`, `should have []`).
- **Coverage rate:** ~7 of 18 constraints have a working corresponding rule.

### Critical Gaps

1. **All three `contentsRules` are vacuous.** They use the wrong arch-go schema (`shouldOnlyContain: [- interfaces: true, ...]` and `shouldNotContainAnyOf: [- interfaces: true, ...]`). Neither field name exists in arch-go. The valid schema is **flat booleans directly on the rule object**: `shouldOnlyContainInterfaces: true`, `shouldNotContainFunctions: true`, etc. Proof: the test report shows `should comply with []` for every contents rule.
2. **`functionsRules.maxComplexity` is not a real arch-go field.** arch-go's `FunctionRule` schema only supports `maxParameters`, `maxReturnValues`, `maxPublicFunctionPerFile`, and `maxLines`. There is no cyclomatic-complexity rule. Proof: the test report shows `should have []` for the `internal/**` complexity rule, while the sibling `lib/**` rule (which uses the correct `maxReturnValues`) prints `should have ['at most 2 return values']`.
3. **Layer 1 (Foundation) coverage is incomplete.** The preamble names `types` and `version/**` as Foundation but no `dependenciesRules` block targets them. Add to that the *implicit* Foundation-tier root-level packages that are not in the preamble at all: `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging/**`. None has a rule.
4. **Layer 2 (Data Plane / Protos) has no dependency enforcement.** `proto/**` and `proto-public/**` should never import higher layers. Currently the only rule attached to `proto/**` is the broken contents rule; `proto-public/**` is **completely uncovered** despite being named in the preamble's layer hierarchy.
5. **Major top-level production packages are completely uncovered:** `acl/**`, `connect/**`, `envoyextensions/**`, `troubleshoot/**` (other than as a deny-target of `command/**`). These are not test/tooling exclusions — they are production code listed in `1_hashicorp_consul.txt`.
6. **The `command/**` rule is semantically inverted / wrong.** It denies `command/**` from importing `troubleshoot/**`, but `command/troubleshoot/...` (the CLI subcommand for troubleshoot) is the natural client of `troubleshoot/...`. Either the rule is encoding the wrong direction or the codebase happens to satisfy a fragile invented constraint that has no architectural basis.
7. **Documentation contradiction.** The YAML preamble lists `proto-public/**` simultaneously as Layer 2 and as an "EXCLUDED" package. Reviewers cannot tell whether enforcement is intentional or not.
8. **Coverage is `0% [FAIL]` in the test report.** The author did not add a `threshold` block, so arch-go's default 100% coverage threshold is in effect and the build is in fact **failing** the coverage gate. The "100% PASS" in the compliance row is misleading because the coverage gate has actually failed.

### Overall verdict: **`FAIL`**

The compliance row reads `100%` only because (a) 5 of the 14 rules were silently dropped due to schema errors, and (b) the surviving 9 rules cover so few packages that even the legitimate cross-layer imports the documentation cares about (e.g. anything originating in `acl/**`, `connect/**`, `envoyextensions/**`, `troubleshoot/**`, `types`, `version/**`, `proto-public/**`) cannot trip them. The "all green" output is structurally meaningless until the schema bugs and coverage holes are repaired. The coverage row already says `FAIL`.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Glob Syntax Error / Vacuous Rule (schema invalid)
Affected Rule / Constraint: All three `contentsRules` (lines 131–157) — C9, C10, C11

What is wrong:
The YAML uses a non-existent arch-go schema. arch-go's contents rules are flat booleans directly on the rule object — there is no `shouldOnlyContain` or `shouldNotContainAnyOf` field. The supported field names are:
  shouldNotContainInterfaces / shouldNotContainStructs / shouldNotContainFunctions / shouldNotContainMethods
  shouldOnlyContainInterfaces / shouldOnlyContainStructs / shouldOnlyContainFunctions / shouldOnlyContainMethods

What you wrote:
  shouldOnlyContain:
    - interfaces: true
    - structs: true
    - functions: true

What arch-go expected (mutually exclusive — pick at most one `shouldOnly*`):
  shouldOnlyContainStructs: true   # for example

The arch-go YAML decoder silently ignores unknown keys. The tool then sees a `contentsRules` entry with **no** active checks and emits `[PASS] - Packages matching pattern '...' should complies with []` — the empty list is the smoking gun. Three rules in the file produce that line: `internal/resource/**`, `proto/**`, `grpcmocks/**`.

Why it matters:
Each "PASS" is meaningless. The author's stated intent was:
- C9: `internal/resource/**` may only contain interfaces, structs, functions (which is logically a no-op even if the schema were right — every Go declaration is one of those).
- C10: `proto/**` must not contain hand-written interfaces or functions (a real, valuable invariant for generated proto packages).
- C11: `grpcmocks/**` must not contain functions (a real, valuable invariant for mock packages).

C10 and C11 are real constraints that today **detect nothing**. A maintainer who hand-edits a generated proto file to add a method or a complex factory function will not be flagged. This is exactly the class of regression contents rules are designed to catch.

How to fix it:
Replace the three rules with the correct flat-boolean schema. Note also: a single rule cannot stack multiple `shouldOnly*` flags — `shouldOnlyContainInterfaces: true` AND `shouldOnlyContainStructs: true` are mutually exclusive (the rule says "only X"). For C9 the safest fix is to delete the rule outright (it was logically a no-op anyway). For C10 and C11 the correct form is:

```yaml
contentsRules:
  - description: Generated proto packages must not contain hand-written interfaces or functions.
    package: "github.com/hashicorp/consul/proto/**"
    shouldNotContainInterfaces: true
    shouldNotContainFunctions: true

  - description: Generated proto-public packages must not contain hand-written interfaces or functions.
    package: "github.com/hashicorp/consul/proto-public/**"
    shouldNotContainInterfaces: true
    shouldNotContainFunctions: true

  - description: Mock packages must not contain hand-written functions.
    package: "github.com/hashicorp/consul/grpcmocks/**"
    shouldNotContainFunctions: true
```

(After the fix, run the report again and confirm the rules now print `should comply with ['should not contain interfaces', 'should not contain functions']` — non-empty constraint lists.)
```

```
[F2] SEVERITY: CRITICAL
Category: Glob Syntax Error / Vacuous Rule (schema invalid)
Affected Rule / Constraint: `functionsRules` for `internal/**` (lines 184–189) — C14

What is wrong:
arch-go's `FunctionRule` schema does not have a `maxComplexity` field. The supported fields are: `maxParameters`, `maxReturnValues`, `maxPublicFunctionPerFile`, `maxLines`. Cyclomatic-complexity enforcement is simply not a feature of arch-go (v1.x or arch-go v2). The author appears to have invented the field by analogy with ArchUnit's Java-side complexity checks.

The decoder silently ignores `maxComplexity: 15` and the rule reduces to "no constraints." Proof: the test report says `Functions in packages matching pattern 'github.com/hashicorp/consul/internal/**' should have []` — empty list. Compare against the sibling rule for `lib/**` (which uses the correct `maxReturnValues`) which prints `should have ['at most 2 return values']` — non-empty.

Why it matters:
The C14 invariant ("internal-layer functions should have cyclomatic complexity ≤ 15") is currently unenforced. Any function added to `internal/**` with arbitrary complexity will pass CI. This is a 100% false-negative class.

How to fix it:
arch-go cannot enforce cyclomatic complexity. Use a *related* available metric to approximate the maintainability invariant, or document the gap and pair it with a `gocyclo` / `gocognit` pre-commit hook. The closest available approximations are:

```yaml
functionsRules:
  - description: >
      Internal-layer functions should be small and focused. arch-go does not
      support cyclomatic complexity directly; use a line-budget proxy plus a
      gocyclo pre-commit hook for the actual complexity bound.
    package: "github.com/hashicorp/consul/internal/**"
    maxLines: 80
    maxParameters: 6
```

If complexity enforcement is non-negotiable, add a separate CI step:

```makefile
arch-check: arch-go-check complexity-check

.PHONY: complexity-check
complexity-check:
	@gocyclo -over 15 ./internal/... && echo "OK" || (echo "FAIL: cyclomatic > 15 in internal/**" && exit 1)
```

Either way, the current `maxComplexity: 15` line must be **deleted** (it is dead code that lies to the reader).
```

```
[F3] SEVERITY: CRITICAL
Category: Coverage Gap (Foundation layer incomplete)
Affected Rule / Constraint: C1 (Foundation must not depend on higher layers)

What is wrong:
The preamble's Layer 1 enumeration says: `lib/**`, `sdk/**`, `types`, `version`. The `dependenciesRules` only attach to `lib/**` and `sdk/**`. Neither `types` nor `version/**` (`version`, `version/versiontest`) has a rule — and the package list also contains additional Foundation-tier root packages the preamble forgot: `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging`, `logging/monitor`. None of these are matched by any `package:` glob.

Why it matters:
A new import of `github.com/hashicorp/consul/agent/consul/state` from inside `github.com/hashicorp/consul/types`, `github.com/hashicorp/consul/ipaddr`, `github.com/hashicorp/consul/tlsutil`, etc. is not a violation of any rule. These are exactly the bedrock packages whose layering is most important to keep clean — they sit at the bottom of the dependency tree and any upward edge from them is by definition a layer cycle.

How to fix it:
Add one rule per uncovered Foundation-tier package, mirroring the existing `lib/**` block. Prefer one rule per *root* package (so `types` is `package: "github.com/hashicorp/consul/types"`, not `types/**`, since `types` is a leaf):

```yaml
dependenciesRules:
  - description: Foundation root package `types` must not depend on higher layers.
    package: "github.com/hashicorp/consul/types"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  - description: Foundation root package `version` must not depend on higher layers.
    package: "github.com/hashicorp/consul/version/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  # Repeat for: ipaddr, sentinel, service_os, snapshot, tlsutil, logging/**
  # (Validate the deny list against `go list -deps` output before merging — some of
  # these may legitimately need a single narrow exception.)
```
```

```
[F4] SEVERITY: CRITICAL
Category: Coverage Gap (Layer 2 has no dependency rule)
Affected Rule / Constraint: C2 (Data Plane / Protos must not depend on higher layers)

What is wrong:
The preamble lists `proto/**` and `proto-public/**` as Layer 2 (Data Plane). The only rule that targets `proto/**` is the broken `contentsRules` entry (see F1). There is **no** `dependenciesRules` block forbidding `proto/**` from importing `agent/**`, `api/**`, `command/**`, `internal/**`, `connect/**`, `envoyextensions/**`, etc. `proto-public/**` is completely uncovered — neither contents rules nor dependency rules touch it. (And the preamble simultaneously labels `proto-public/**` as "Layer 2" *and* as "EXCLUDED" — see F11.)

Why it matters:
Generated proto code that accidentally takes a hand-written dependency on `internal/multicluster` or `agent/structs` would not be flagged. For the proto packages, this is exactly the regression class arch-go is best at catching, because protos are conceptually side-effect-free data.

How to fix it:
Add explicit dependency rules for `proto/**` and `proto-public/**` (drop the latter's "EXCLUDED" listing — see F11):

```yaml
dependenciesRules:
  - description: Generated proto packages must not depend on higher Consul layers.
    package: "github.com/hashicorp/consul/proto/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  - description: Generated public proto packages must not depend on higher Consul layers.
    package: "github.com/hashicorp/consul/proto-public/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```
```

```
[F5] SEVERITY: CRITICAL
Category: Coverage Gap (top-level production packages uncovered)
Affected Rule / Constraint: C1–C6 layering for `acl/**`, `connect/**`, `envoyextensions/**`, `troubleshoot/**`

What is wrong:
The package list contains four major top-level production trees that are **not mentioned in the preamble layer hierarchy at all** and **not targeted by any rule**:

- `github.com/hashicorp/consul/acl`, `acl/resolver` — ACL primitives.
- `github.com/hashicorp/consul/connect`, `connect/certgen`, `connect/proxy` — Connect (service-mesh) library + sidecar proxy.
- `github.com/hashicorp/consul/envoyextensions/extensioncommon`, `envoyextensions/xdscommon` — Envoy extension primitives.
- `github.com/hashicorp/consul/troubleshoot/ports`, `troubleshoot/proxy`, `troubleshoot/validate` — Troubleshooting library (only mentioned as a deny target for `command/**`, never as an origin).

These are real production packages. The author appears to have collapsed the hierarchy to "lib | sdk | proto | internal | agent | api | command" and ignored the rest.

Why it matters:
- `acl/**` is conceptually Foundation-tier or Internal-Framework-tier and must not import `agent/**` or `command/**`. With no rule, it can.
- `connect/**` is conceptually a peer of the agent (data-plane primitives) and must not import `command/**`, `agent/consul/**`, etc. With no rule, it can.
- `envoyextensions/**` is reusable framework code under `internal/**` semantics but lives at the root. With no rule, it can import the agent.
- `troubleshoot/**` is an SDK library consumed by the `command/troubleshoot/...` CLI. As an origin, it must not depend on `agent/**` or `command/**`. With no rule, it can.

Today, an import edge of the form `github.com/hashicorp/consul/troubleshoot/proxy → github.com/hashicorp/consul/agent/consul/state` would pass CI without a peep.

How to fix it:
Add origin-side dependency rules for each:

```yaml
dependenciesRules:
  - description: ACL primitives must not depend on higher layers.
    package: "github.com/hashicorp/consul/acl/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  - description: Connect data-plane primitives must not depend on agent server, API, or CLI.
    package: "github.com/hashicorp/consul/connect/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/api/**"     # Reconsider: connect/proxy may legitimately import api
        - "github.com/hashicorp/consul/command/**"

  - description: Envoy extension primitives must not depend on agent server or CLI.
    package: "github.com/hashicorp/consul/envoyextensions/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/command/**"

  - description: Troubleshoot library must not depend on agent or CLI.
    package: "github.com/hashicorp/consul/troubleshoot/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
```

(Validate each deny list against the actual import graph before merging — if `connect/proxy` legitimately imports `api/**`, soften the rule to only deny `agent/consul/**`.)
```

```
[F6] SEVERITY: HIGH
Category: Semantic Error (likely inverted) / Overly Broad
Affected Rule / Constraint: `command/**` rule (lines 119–126) — invents C-extra: command/** ↛ troubleshoot/**

What is wrong:
The rule states "CLI Command implementations are the top layer and should only interact with the system via the Agent or API. They must not depend on the troubleshooting utilities to avoid circular dependencies." But:

1. **`command/troubleshoot/...`** (subpackages: `command/troubleshoot/ports`, `command/troubleshoot/proxy`, `command/troubleshoot/upstreams`) are the CLI sub-commands that wrap the `troubleshoot/...` library. The natural dependency direction is `command/troubleshoot/* → troubleshoot/*`. Forbidding it is the **inverse** of how the codebase is structured.
2. The `command/**` glob *includes* `command/troubleshoot/**` itself. So the rule says `command/troubleshoot/* → troubleshoot/*` is forbidden — almost certainly a mis-encoding.
3. The "circular dependency" rationale is invented — there is no documented bidirectional edge that would create a cycle.

The fact that the rule currently passes is more likely an artifact of arch-go's import-graph snapshotting (e.g. some Consul branches do not have these CLI commands wired yet, or the analysis subset does not include the `command/troubleshoot/**` files) than a sign the rule is correct.

Why it matters:
- If the rule is currently passing because `command/troubleshoot/**` is empty in the analyzed branch, it will start failing the moment the CLI is wired up — *for a legitimate import* — and a future maintainer will be tempted to "fix" the architecture instead of the rule.
- If the rule is currently passing because the CLI does not yet import `troubleshoot/**`, it freezes a brittle, undocumented constraint that has nothing to do with C1–C6.

How to fix it:
**Delete this rule.** The `command/**` layer has no documented internal dependency restrictions in the preamble — it is the topmost layer and is free to import from any lower layer (which is exactly what a CLI does). If a *real* directional constraint is needed, encode it from the *other* origin — e.g. forbid `troubleshoot/**` from importing `command/**`:

```yaml
# Replace the existing command/** rule with this troubleshoot/** rule.
dependenciesRules:
  - description: >
      Troubleshoot library must not depend on the CLI commands that wrap it
      (the natural direction is command/troubleshoot/* → troubleshoot/*).
    package: "github.com/hashicorp/consul/troubleshoot/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/command/**"
```

(This is also covered by F5's troubleshoot rule.)
```

```
[F7] SEVERITY: HIGH
Category: Coverage Gap (intra-agent client/server split missing)
Affected Rule / Constraint: C4 (Agent layer); the documented "Consul Servers vs. Consul Clients" split shown in `1_hashicorp_consul.jpg`

What is wrong:
The architecture image and Consul's well-known internal structure split the `agent/**` tree into:
- *Server-side* code under `agent/consul/**` (Raft FSM, state store, server RPC handlers — the boxes labeled `LEADER` / `FOLLOWER`).
- *Client-side* code: every other `agent/...` subtree (DNS, xDS, gRPC external/internal, proxycfg, local, dns, etc.) — the boxes labeled `CONSUL CLIENT`.

The diagram shows the two communicate **only** through Raft replication and RPC, and the documented invariant is that non-server agent code **must not** import `agent/consul/**` directly (otherwise the client/server split collapses).

The current rule for `agent/**` only denies `api/**` and `command/**`. It says *nothing* about `agent/consul/**`. Worse, since the rule's `package:` glob is `agent/**`, the same rule covers *both* the client side and the server side, so it cannot encode "non-server agent ↛ agent/consul" without splitting.

Why it matters:
A new edge such as `agent/dns → agent/consul/state` is exactly the kind of layer-violation that arch-go is supposed to catch, and it is the single largest documented intra-Consul boundary. Currently it is invisible.

How to fix it:
arch-go's package-glob model cannot express "agent/** but not agent/consul/**" in one block. The standard pattern is: enumerate every non-server `agent/<leaf>/` and add a `shouldNotDependsOn: agent/consul/**` to each. Example skeleton (replicate per leaf):

```yaml
dependenciesRules:
  - description: Non-server agent code (DNS) must not depend on the server.
    package: "github.com/hashicorp/consul/agent/dns/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"

  - description: Non-server agent code (xDS) must not depend on the server.
    package: "github.com/hashicorp/consul/agent/xds/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"

  - description: Non-server agent code (proxycfg) must not depend on the server.
    package: "github.com/hashicorp/consul/agent/proxycfg/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"

  # …repeat for every non-server agent/<leaf> in the package list:
  # ae, auto-config, blockingquery, cache, cache-types, cacheshim, checks,
  # config, configentry, connect, debug, envoyextensions, exec,
  # grpc-external/**, grpc-internal/**, grpc-middleware/**, leafcert, local,
  # log-drop, metadata, metrics, netutil, pool, proxycfg-glue,
  # proxycfg-sources/**, router, routine-leak-checker, rpc/**, rpcclient/**,
  # structs, structs/aclfilter, submatview, systemd, token, uiserver
```

This is verbose but matches arch-go's design. The alternative is a single allow-list for *server* code only, but `shouldOnlyDependsOn` is even more brittle and not what the documentation calls for.
```

```
[F8] SEVERITY: HIGH
Category: Vacuous Rule (logically impossible) / Coverage Gap
Affected Rule / Constraint: `contentsRules` for `internal/resource/**` (lines 132–140) — C9

What is wrong:
Even ignoring the schema bug from F1, the *intent* of the rule is impossible:

  shouldOnlyContain:
    - interfaces: true
    - structs: true
    - functions: true

`shouldOnlyContain*` flags are **mutually exclusive** by construction — "this package may only contain X" means "only X." Stacking three different `shouldOnly*` flags asks arch-go to enforce three contradictory invariants simultaneously, which is a no-op even semantically (every Go declaration is one of interface/struct/function/method, so a conjunction of "only interfaces AND only structs AND only functions" matches nothing).

Why it matters:
The rule's description says "Interface definitions for core logic should belong in the internal domain packages." That is a *rationale* for adding *some* check in the package, but the rule does not encode any check.

How to fix it:
Either delete the rule, or pick a single concrete invariant. Two reasonable replacements:

```yaml
# Option A — every internal/resource/* file must also live under a *Repository* /
# *Resource* type, captured by a naming rule (already in the file as C13).
# In that case, delete the contents rule entirely and rely on C13.

# Option B — internal/resource/** must not embed agent-tier handlers.
#            (Cannot be expressed with contents rules — promote to a
#            dependenciesRules entry instead.)
dependenciesRules:
  - description: internal/resource/** must not depend on the agent layer.
    package: "github.com/hashicorp/consul/internal/resource/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command/**"
```

In either case the broken contents rule must be deleted; do not leave the misleading entry in place.
```

```
[F9] SEVERITY: HIGH
Category: Structural Gap (default coverage threshold)
Affected Rule / Constraint: arch-go threshold/coverage configuration

What is wrong:
The YAML has no `threshold:` block. arch-go's default thresholds are `compliance: 100, coverage: 100`. The test report confirms the gate is active and currently failing:
  COVERAGE RATE | 0% [FAIL]
The summary `Compliance: 100% (PASS)` is followed by `Coverage: 0% (FAIL)` — the build is in fact failing the coverage gate, not passing.

A naive reader who sees `Failed: 0` in the summary table will mistake this for green CI when arch-go actually returns a non-zero exit code on coverage failure (depending on flags). Even if `arch-go check` happens to exit 0 in this configuration, the `0% [FAIL]` line is broadcast in any CI log.

Why it matters:
- Either the author intends 100% coverage (in which case CI is broken now and *every* added rule will need to lift coverage in lockstep), or the author intends a relaxed threshold (in which case the `[FAIL]` is misleading and should be silenced explicitly).
- Without an explicit threshold block, future changes to the default in arch-go will silently change the gate.

How to fix it:
Add an explicit `threshold` block at the top of the file, between `description:` and `dependenciesRules:`. For a project this large with leaf-only globs, coverage is dominated by the *number of distinct package patterns matched*, which is not the same as architectural-constraint coverage. The pragmatic setting is:

```yaml
version: 1
description: Architectural rules for HashiCorp Consul enforcing a layered structure.
threshold:
  compliance: 100
  coverage: 0   # arch-go's coverage metric is package-touch, not constraint-coverage
                # — see the "Coverage Audit" comment in the file header.

dependenciesRules:
  ...
```

(Document the `coverage: 0` as intentional — the upstream "compliance" gate is what gates the build. If the team wants real coverage tracking, count constraints manually as an offline metric, not inside arch-go.)
```

```
[F10] SEVERITY: MEDIUM
Category: Coverage Gap (intra-`internal` Layer 2 isolation)
Affected Rule / Constraint: C8 (Storage Raft must not depend on inmem) and analogous intra-`internal/**` constraints

What is wrong:
The only intra-`internal/**` rule is `internal/storage/raft/** ↛ internal/storage/inmem/**`. Other plausible intra-layer constraints are missing:
- `internal/storage/inmem/** ↛ internal/storage/raft/**` (the **reverse** of the stated rule — pluggability is symmetric).
- `internal/multicluster ↛ internal/controller/**` (multicluster is a controller *consumer*, not a controller — and the analogous edge in the sonnet-4-6 review was flagged as the only encoded Layer-2 peer-cycle break).
- `internal/protohcl/** ↛ internal/resource/**` and other framework-internal peers.

Why it matters:
The "Storage implementations must not have cross-dependencies to ensure pluggability" rationale on the Raft rule is one-directional, which is half-coverage. A new import of `internal/storage/raft` from inside `internal/storage/inmem` would not trip any rule.

How to fix it:
Add the symmetric rule and consider any other documented intra-`internal` edges:

```yaml
dependenciesRules:
  - description: >
      Storage implementations must not have cross-dependencies. The in-memory
      implementation must not depend on the Raft implementation (symmetric to
      the existing raft → inmem rule).
    package: "github.com/hashicorp/consul/internal/storage/inmem/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal/storage/raft/**"
```
```

```
[F11] SEVERITY: MEDIUM
Category: Comment / Documentation Inconsistency
Affected Rule / Constraint: YAML preamble (lines 9–45)

What is wrong:
The preamble is internally inconsistent:
1. **`proto-public/**` is listed both as Layer 2 ("Data Plane / Protos") AND as "EXCLUDED PACKAGES."** A reviewer cannot tell which is the source of truth.
2. **The "Excluded packages" comment is decorative — it has no effect.** arch-go has no `--exclude` field in this file, the Makefile passes no `--exclude-files`, and the package `proto/private/**` (listed as excluded) is in fact *targeted* by the `proto/**` contents rule (broken though it is).
3. **`grpcmocks/**`** is listed as excluded but is also targeted by a contents rule. If the intent is "no rules," delete the rule; if the intent is "limited rules," remove from the exclusion list.
4. **`agent/mock`, `lib/testhelpers`, `sdk/testutil/**`** are listed as excluded but are *covered* by the broader `agent/**`, `lib/**`, `sdk/**` dependency rules — the exclusion is a no-op unless those specific subtrees are removed from the parent globs.

Why it matters:
The mismatch between the comment's intent and the actual rule globs makes future maintenance high-risk: someone reading "EXCLUDED — agent/mock" will assume mocks are safe to break layering, then be surprised by a CI failure. Documentation that contradicts the configuration is worse than no documentation.

How to fix it:
Either:
- Delete the "EXCLUDED PACKAGES" block from the preamble (it is aspirational only), and add real exclusions via narrower globs in the rules themselves; or
- Use arch-go's package-pattern *negation*. arch-go does not natively support "`X/**` except `X/Y/**`," so the practical fix is to enumerate explicit subtrees in the deny-target list rather than `**`. Example rewrite for `lib/**`:

```yaml
# Drop lib/testhelpers from the rule by enumerating siblings. Not pretty, but
# it's the only way to express "lib/** except lib/testhelpers" in arch-go.
dependenciesRules:
  - description: Foundation lib/* (excluding testhelpers) must not depend on higher layers.
    package: "github.com/hashicorp/consul/lib"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        # ...
  - description: Foundation lib/channels must not depend on higher layers.
    package: "github.com/hashicorp/consul/lib/channels"
    shouldNotDependsOn: { internal: [ ... ] }
  # ...repeat for each lib/<leaf> except testhelpers...
```

If that explosion is unacceptable, either keep `lib/**` and accept that `lib/testhelpers` is in scope (and ensure it really doesn't import the agent), or strip the exclusion claim from the preamble.

In either path, **delete the duplicate "Layer 2 = proto-public" / "EXCLUDED = proto-public" entry**: pick one.
```

```
[F12] SEVERITY: MEDIUM
Category: Test vs. Production Scope
Affected Rule / Constraint: General — `_test.go` scope and harness packages

What is wrong:
arch-go scans all `.go` files including `_test.go` by default. The Makefile invokes `arch-go check` with no flags. Two consequences:

1. **False positives risk.** A test helper inside `agent/...` that imports `api/**` for testing-only purposes would be flagged by the `agent/** ↛ api/**` rule. Many Consul test helpers do exactly this (e.g. assertion utilities that build `*api.Catalog`).
2. **False negatives.** The harness packages `testing/deployer/**`, `test-integ/**`, `testrpc`, and `tools/**` are real production-style code (binaries, integration suites, sprawl) but are not matched by any `package:` glob, so any layer violation inside them is invisible.

Why it matters:
The author appears to know about this — the preamble's "EXCLUDED PACKAGES" block lists `test-integ/**`, `sdk/testutil/**`, `lib/testhelpers`, `agent/mock` — but the *exclusion is comment-only*. Nothing in the YAML or Makefile actually excludes them, and there is no mention of `_test.go` scoping at all.

How to fix it:
arch-go's `go-files-only` analysis cannot directly skip `_test.go`, but it does honor `//go:build` constraints at the package level — so test-only files marked with `//go:build test` are effectively excluded. The pragmatic fix is:

1. Document the limitation in the YAML header.
2. Add explicit rules for harness trees (or add an explicit "do not enforce" comment so the next author does not re-add them by accident):

```yaml
# Add to file header:
# SCOPE NOTE:
#   arch-go scans all .go files (including _test.go). Test helpers that
#   intentionally cross layer boundaries should be moved under a build tag
#   `//go:build testhelpers` (currently only lib/testhelpers, agent/mock follow
#   this pattern). Harness trees test-integ/**, testing/deployer/**, testrpc,
#   tools/** are *not* enforced by any rule — they are integration / build
#   utilities and live outside the layer hierarchy. Add a rule below if a
#   specific harness invariant matters.

# And one example harness rule (defense-in-depth):
dependenciesRules:
  - description: >
      Integration test harnesses must not be imported by production layers.
      The harness depends on production, never the reverse.
    package: "github.com/hashicorp/consul/testing/deployer/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/internal/**"
```

(`testing/deployer/**` is a sprawl harness that *is* allowed to import everything, so the rule above protects the *reverse* direction. The actual deny direction needs to be encoded on every production package — which they are, transitively, by F3/F4/F5.)
```

```
[F13] SEVERITY: MEDIUM
Category: Module Scope / Makefile Defect
Affected Rule / Constraint: `Makefile` — `arch-check` target

What is wrong:
The Makefile defaults `PROJECT_ROOT` to `.`, then runs `cd $(PROJECT_ROOT) && arch-go check`. arch-go reads `arch-go.yml` from the current working directory. There are two problems:

1. **`arch-go.yml` lives at `outputs/hashicorp_consul/gemini3-flash/arch-go.yml`, not at the consul module root.** Running `make arch-check` with `PROJECT_ROOT=.` runs arch-go from `outputs/hashicorp_consul/gemini3-flash/` — but that directory is *not* a Go module (no `go.mod`). arch-go cannot resolve any `github.com/hashicorp/consul/...` packages and the analysis silently scans nothing. (This may be the actual reason the report shows `Coverage: 0% [FAIL]` — the file-system root has no Go code in it.)
2. **Running with `PROJECT_ROOT=/path/to/consul` does not solve it either**, because that directory has no `arch-go.yml`. arch-go would then read whatever `arch-go.yml` is at the consul root (probably none), not the file under `outputs/`.

Why it matters:
The Makefile, as written, cannot produce a valid arch-go run. The "100% PASS" result was either generated by a manual workflow that copied `arch-go.yml` into the consul root, or by running arch-go from a directory that was specifically set up — but the Makefile does not encode that workflow.

How to fix it:
arch-go supports `--config` to point at a custom YAML location. Update the Makefile to copy or symlink the YAML into the consul root, **or** invoke arch-go with `--config`:

```makefile
PROJECT_ROOT ?= .
ARCH_CONFIG  ?= $(abspath arch-go.yml)

.PHONY: arch-check
arch-check:
	@echo "==> Verifying arch-go installation..."
	@command -v arch-go >/dev/null 2>&1 || go install github.com/fdaines/arch-go@latest
	@echo "==> Running arch-go check in $(PROJECT_ROOT) using $(ARCH_CONFIG)..."
	@cd $(PROJECT_ROOT) && arch-go check --config "$(ARCH_CONFIG)"

.PHONY: help
help:
	@echo "Consul Architecture Check Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  arch-check    Run arch-go using $(ARCH_CONFIG) against $(PROJECT_ROOT)"
	@echo ""
	@echo "Variables:"
	@echo "  PROJECT_ROOT  Consul module root (must contain go.mod)"
	@echo "  ARCH_CONFIG   Absolute path to arch-go.yml (defaults to ./arch-go.yml)"
	@echo ""
	@echo "Example:"
	@echo "  make arch-check PROJECT_ROOT=/path/to/consul ARCH_CONFIG=$(abspath arch-go.yml)"
```

(Verify that `--config` is supported in your pinned arch-go version. If not, the alternative is `cp arch-go.yml $(PROJECT_ROOT)/arch-go.yml && cd $(PROJECT_ROOT) && arch-go check`.)
```

```
[F14] SEVERITY: LOW
Category: Comment Accuracy / Description Mismatch
Affected Rule / Constraint: `dependenciesRules` for `internal/storage/raft/**` (lines 88–96)

What is wrong:
The description reads "Specifically, the Raft storage implementation must not depend on the in-memory implementation." The rule encodes only that direction. The architecture-of-pluggability rationale would normally also forbid the reverse (`inmem ↛ raft`). The comment promises a *specific* one-way invariant but does not call out that the symmetric edge is intentionally not enforced (or is not yet observed in the codebase).

Why it matters:
A future maintainer reading the comment will assume "pluggability symmetry" is enforced and may not add the symmetric rule when needed. See F10 for the missing symmetric rule.

How to fix it:
Either add the symmetric rule (F10) or update the comment:

```yaml
  - description: >
      Pluggability: the Raft storage implementation must not depend on the
      in-memory implementation. (The symmetric direction inmem ↛ raft is
      enforced by a separate rule below.)
    package: "github.com/hashicorp/consul/internal/storage/raft/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal/storage/inmem/**"
```
```

```
[F15] SEVERITY: LOW
Category: Glob Pattern (subtree vs. leaf root)
Affected Rule / Constraint: All `lib/**`, `sdk/**`, `internal/**`, `agent/**`, `api/**`, `command/**`, `proto/**`, `proto-public/**` rules

What is wrong:
arch-go's `**` pattern matches *one or more path segments*. The pattern `github.com/hashicorp/consul/lib/**` matches `lib/channels`, `lib/file`, etc., but it does **not** match the root package `github.com/hashicorp/consul/lib` itself (no trailing segment). For the root packages that have files of their own (`lib`, `sdk`, `agent`, `api`, `command`, `internal`), the rule misses the root.

Verify by reading the package list:
- Line 229: `github.com/hashicorp/consul/lib` — the root has its own files (e.g. `lib.go`).
- Line 4: `github.com/hashicorp/consul/agent` — root with its own files.
- Line 83: `github.com/hashicorp/consul/api` — root with its own files.
- Line 85: `github.com/hashicorp/consul/command` — root with its own files.

If `github.com/hashicorp/consul/agent/agent.go` imports `github.com/hashicorp/consul/api`, the `agent/**` rule does **not** flag it.

Why it matters:
This is a classic off-by-one in arch-go glob matching. Even the documented Consul invariant "the agent package itself must not import api" is not enforced by the current rule — only the *children* of agent are.

How to fix it:
Pair every `**` rule with a matching root rule. The double-rule pattern is:

```yaml
  - description: Agent root package must not depend on api or command.
    package: "github.com/hashicorp/consul/agent"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/api"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/command"

  - description: Agent subtree must not depend on api or command.
    package: "github.com/hashicorp/consul/agent/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/api"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/command"
```

(Apply the same root-mirror pattern to every existing rule. The deny-list also needs both `X/**` and `X` entries.)
```

```
[F16] SEVERITY: LOW
Category: Naming Rule / Possible Vacuous
Affected Rule / Constraint: Both `namingRules` (lines 162–179) — C12, C13

What is wrong:
The naming rules are formally correct in syntax, but each enforces a convention that may not match any types in the codebase:
- C12 — `internal/storage/**` structs that implement the `Store` interface must end with `Store`. Verify that:
  1. There is at least one interface called `Store` in `internal/storage/**`.
  2. There is at least one struct *implementing* it in the same subtree.
  Otherwise the rule is structurally vacuous (no instances to check).
- C13 — same shape with `Repository` in `internal/resource/**`. The Consul codebase's `internal/resource/**` does **not** typically expose a `Repository` interface — the equivalent type is `Backend` or `Resource`. The rule may target a non-existent interface and silently pass for everyone.

Why it matters:
A naming rule that targets a non-existent interface trivially passes for the wrong reason. The author appears to have generalized from "common Go idioms" rather than reading the actual codebase. Even where the interface exists, the rule may collide with the broken `contentsRules` for `internal/resource/**` from F8.

How to fix it:
Verify both interfaces exist before keeping the rules. If they do not exist, retarget to actual Consul interfaces:

```yaml
namingRules:
  - description: >
      All structs in internal/storage/** that implement the Backend interface
      should be named *Backend (matching the actual Consul storage abstraction).
    package: "github.com/hashicorp/consul/internal/storage/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      All structs in internal/controller/** that implement the Reconciler
      interface should be named *Reconciler.
    package: "github.com/hashicorp/consul/internal/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"
```

(Confirm via `grep -r "type .* interface" internal/{storage,resource,controller}` before merging — naming rules are only useful if they target a real interface.)
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

### Findings Summary

| ID | Severity | Category | Fix scope |
|----|----------|----------|-----------|
| F1 | CRITICAL | Glob/Schema | All 3 `contentsRules` are no-ops; rewrite to flat-boolean schema |
| F2 | CRITICAL | Glob/Schema | `maxComplexity` is not a real arch-go field; delete/replace with `maxLines` proxy |
| F3 | CRITICAL | Coverage Gap | Foundation: add rules for `types`, `version/**`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging/**` |
| F4 | CRITICAL | Coverage Gap | Layer 2: add `dependenciesRules` for `proto/**` and `proto-public/**` |
| F5 | CRITICAL | Coverage Gap | Add origin rules for `acl/**`, `connect/**`, `envoyextensions/**`, `troubleshoot/**` |
| F6 | HIGH | Semantic Error | Delete/invert the `command/** ↛ troubleshoot/**` rule (likely wrong direction) |
| F7 | HIGH | Coverage Gap | Add per-leaf `agent/<non-server> ↛ agent/consul/**` rules |
| F8 | HIGH | Vacuous Rule | `internal/resource/**` `shouldOnlyContain` stack is impossible; delete or replace with dependency rule |
| F9 | MEDIUM | Threshold | Add explicit `threshold: { compliance: 100, coverage: 0 }` block |
| F10 | MEDIUM | Coverage Gap | Add symmetric `internal/storage/inmem ↛ raft` rule |
| F11 | MEDIUM | Documentation | Resolve the `proto-public/**` Layer-2-vs-Excluded contradiction |
| F12 | MEDIUM | Test Scope | Document `_test.go` scoping; add harness rules for `testing/deployer/**`, `test-integ/**` |
| F13 | MEDIUM | Module Scope | Makefile cannot find `arch-go.yml` from `PROJECT_ROOT` — add `--config` flag |
| F14 | LOW | Comment | `internal/storage/raft` rule comment promises symmetry it doesn't deliver |
| F15 | LOW | Glob | `**` does not match the root package; add root mirrors |
| F16 | LOW | Naming | Verify `Store` and `Repository` interfaces actually exist in target packages |

**5 of 16 findings are CRITICAL or HIGH and must be fixed before this rule file delivers any architectural enforcement.**

---

### What to fix first

If only one round of edits is possible, apply this priority order:

1. **F1 + F2** — fix the schema. Without these, **5 of 14 rules are silent no-ops** and the `100% PASS` result is fictional.
2. **F3 + F4 + F5** — close the Layer 1 / Layer 2 / orphan-package coverage holes. These are the largest false-negative classes.
3. **F6** — delete the inverted `command/** ↛ troubleshoot/**` rule before it traps a future CLI subcommand author.
4. **F7** — encode the documented "non-server agent ↛ agent/consul/**" boundary, the single largest intra-Consul invariant shown in `1_hashicorp_consul.jpg`.
5. **F9 + F13** — make the build infrastructure work (threshold block + Makefile `--config` flag), or future fixes are invisible.

Findings F8, F10, F11, F12, F14, F15, F16 are correctness / hygiene improvements that can be batched in a follow-up round.

---

**End of Review #1.**
