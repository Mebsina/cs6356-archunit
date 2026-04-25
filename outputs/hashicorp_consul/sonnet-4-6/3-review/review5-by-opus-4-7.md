### Executive Summary

- **Total documented constraints identified:** 30
  - 4 from `inputs/go/1_hashicorp_consul.jpg` (control-plane vs data-plane, server-only consensus, sidecar-mediated app traffic, app-isolation from server control APIs).
  - 26 from the YAML preamble: five-layer dependency ordering; "Foundation only" Layer-2 narrative; Layer-3 client vs Layer-4 server isolation; Layer-5 leaf isolation for `api/**`, `command/**`, `connect/**`, `troubleshoot/**`; harness/test-tree exclusion bans; intra-`agent/consul` partial DAG (`state`, `fsm`, `discoverychain`); generated-code structural constraints (proto, proto-public, grpcmocks, `agent/consul/state` interface ban); naming conventions (`Store`, `Backend`, `Resolver`, `Provider`, `Reconciler`, `Limiter`); function-arity caps for `lib`, `internal/storage`, `agent/consul/state`, `agent/consul/discoverychain`; Makefile module-path assertion.
- **Total rules generated:** 144 (127 dependency, 4 contents, 7 naming, 6 function — per the test report).
- **Coverage rate:** ~17 / 30 constraints have a working, non-vacuous arch-go rule; **4** diagram constraints are explicitly waived; **5** generated/structural constraints are silently broken because all four `contentsRules` use an unrecognised schema (`shouldNotContainAnyOf` / `shouldOnlyContain` instead of the documented `shouldNotContainInterfaces` / `shouldOnlyContainStructs` flat booleans); several Layer-5 leaf rules are written as documented under-constraints.
- **Critical Gaps:**
  1. **All four `contentsRules` are vacuous due to wrong schema.** arch-go (both `fdaines/arch-go` v1.5.4 and `arch-go/arch-go/v2` v2.x) requires flat boolean keys such as `shouldNotContainInterfaces: true` and `shouldOnlyContainStructs: true`. The generated YAML uses `shouldNotContainAnyOf: [interfaces: true]` and `shouldOnlyContain: [structs: true, interfaces: false, functions: false]`, which arch-go silently ignores. The test report's `should complies with []` (empty bracket list) is the smoking gun; compare to dependency rules which report a non-empty constraint list.
  2. **Layer-5 leaf isolation is policy-broken.** `troubleshoot/**` only forbids `command/**` and `agent/consul/**`; `command/**` only forbids `agent/consul/**`. Both leaves can freely import `internal/**`, `envoyextensions/**`, all non-server `agent/**` packages, and arbitrary cross-leaf paths (`troubleshoot/**` → `agent/grpc-internal/balancer`, `command/operator` → `internal/storage/raft`, etc.) without any rule firing.
  3. **`internal/protohcl` root has no rule.** The package list includes `github.com/hashicorp/consul/internal/protohcl` (a root Go package, line 206) plus the `testproto` subpackage. The file's own generator contract ("`/**` may not apply to the directory root") is enforced everywhere else with explicit root mirrors (`acl`, `lib`, `internal/storage`, `internal/resource`, `internal/controller`, …), but the root `internal/protohcl` package was missed.
  4. **Vacuous root-mirror rules for `agent/proxycfg-sources` and `agent/rpc`.** Neither path exists as a Go package in `inputs/go/1_hashicorp_consul.txt` — only `agent/proxycfg-sources/catalog`, `…/local`, `agent/rpc/middleware`, `…/operator`, `…/peering` exist. The Review #4 fix added these "root mirrors" without verifying the package list. They cannot fire.
- **Tool-result note:** The test report's `Coverage: 0% [FAIL]` is arch-go's heuristic for the fraction of packages touched by *any* rule, not architectural-constraint coverage. The header documents this. However, `Coverage: 0%` is also consistent with the contents-rule schema break above: arch-go reports zero contents-rule coverage because the rules carry no checks.
- **Overall verdict:** **FAIL**

---

### Findings

```
[F01] SEVERITY: CRITICAL
Category: Vacuous Rule | Glob/Schema Syntax Error
Affected Rule / Constraint: All four `contentsRules` blocks (state interface ban; proto/**, proto-public/**, grpcmocks/** structs-only). Documented constraints L12 and L13 from the preamble.

What is wrong:
The four contents rules use a YAML shape arch-go does not recognise:

    contentsRules:
      - package: "github.com/hashicorp/consul/agent/consul/state"
        shouldNotContainAnyOf:
          - interfaces: true
      - package: "github.com/hashicorp/consul/proto/**"
        shouldOnlyContain:
          - structs: true
          - interfaces: false
          - functions: false

The arch-go schema (documented for fdaines/arch-go v1.5.4 and arch-go/arch-go v2.x) requires **flat boolean keys** at the rule level:

    contentsRules:
      - package: "..."
        shouldNotContainInterfaces: true
        shouldOnlyContainStructs: true
        shouldOnlyContainFunctions: false

The unknown keys `shouldNotContainAnyOf` and `shouldOnlyContain` are silently dropped during YAML decoding. The test report confirms this: every dependency rule prints its constraint, e.g. `should ['not depend on internal packages that matches [...]']`, but every contents rule prints `should complies with []` — the empty `[]` is arch-go reporting "no checks active for this package." All four rules pass vacuously.

Why it matters:
- The architectural ban "no interfaces in `agent/consul/state`" is **not enforced**. Real consul `agent/consul/state` defines several Go interfaces today; a fixed-schema rule would surface that immediately.
- The "structs only" hygiene policy on `proto/**`, `proto-public/**`, and `grpcmocks/**` is **not enforced**. Hand-written helpers (functions) can be added to those packages without CI reporting anything.
- The PASS in the report is misleading; reviewers will conclude the contents constraints are honoured when they are not even being checked.

How to fix it:

```yaml
contentsRules:

  - description: >
      agent/consul/state must not declare interface types; the state store is
      a concrete subsystem and should not become a dependency magnet via wide
      interface APIs. NOTE: arch-go does not distinguish exported from
      unexported interfaces; if testing-seam interfaces must remain in the
      package, move them to a sibling _test package or a typed mock helper.
    package: "github.com/hashicorp/consul/agent/consul/state"
    shouldNotContainInterfaces: true

  - description: >
      Generated private protobuf packages should contain only struct
      definitions (and their generated method sets). Hand-written interfaces
      or free functions in proto/** are policy violations.
    package: "github.com/hashicorp/consul/proto/**"
    shouldOnlyContainStructs: true

  - description: >
      Public generated protobuf packages mirror the proto/** policy.
    package: "github.com/hashicorp/consul/proto-public/**"
    shouldOnlyContainStructs: true

  - description: >
      grpcmocks packages contain mock structs and their generated methods only
      (mockgen output). Hand-written interfaces or free functions are not
      permitted.
    package: "github.com/hashicorp/consul/grpcmocks/**"
    shouldOnlyContainStructs: true
```

After this change, arch-go's report should print non-empty constraint lists for the four packages and may flag pre-existing interfaces in `agent/consul/state` — fix or scope-narrow the rule before merging, but do not leave the schema broken.
```

```
[F02] SEVERITY: CRITICAL
Category: Coverage Gap | Semantic Error
Affected Rule / Constraint: Layer-5 leaf isolation for `troubleshoot/**` and `command/**` (header L5: "API / CLI / Connect ... must not be imported by inner layers" and the implied dual: leaves themselves must not reach into Layer-2 or unrelated Layer-3 code).

What is wrong:
- The current `troubleshoot/**` rule denies only `command/**` and `agent/consul/**`. The rule's own description openly states: "Imports of api/**, agent/** (excluding agent/consul/**), envoyextensions/**, and internal/** are not blocked here."
- The current `command/**` rule denies only `agent/consul/**`.

That is, two of the three Layer-5 leaves can legally import:
- `github.com/hashicorp/consul/internal/storage/raft`
- `github.com/hashicorp/consul/internal/controller/cache`
- `github.com/hashicorp/consul/envoyextensions/xdscommon`
- `github.com/hashicorp/consul/agent/grpc-internal/balancer`
- `github.com/hashicorp/consul/agent/proxycfg`

… all of which the architecture text positions as inner-layer or peer-leaf code that a CLI / diagnostics utility should not depend on. The contrast with the third leaf is stark: `connect/**` correctly forbids `agent/**`, `internal/**`, `envoyextensions/**`, `command/**`, `api/**`, `troubleshoot/**`, and `api/**` correctly forbids the same. Two leaves carry leaf-style isolation; two do not.

Why it matters:
- A future `command/operator/raft` enhancement that links directly to `internal/storage/raft` (instead of going through the public RPC contract) would pass CI and silently break the "CLI uses RPC, not internal types" boundary.
- A `troubleshoot/proxy` upgrade that imports `internal/multicluster` for partition resolution would pass CI and silently extend the Layer-5 leaf into the Internal Framework.

The README narrative claims Layer-5 are leaves; the rules encode "leaf-ish for connect/api, freely-coupled for command/troubleshoot." The two are not the same architecture.

How to fix it:
Either (A) tighten the leaves to match `connect/**`/`api/**`, or (B) explicitly document the exception and split `command/**` into the entry-point that legitimately needs `agent/**` (`command/agent`) versus the rest that should be stricter.

Option A — symmetric leaf isolation:

```yaml
  - description: >
      troubleshoot/** is a leaf SDK consumed by humans and other tools. It
      must not depend on agent (any subtree), the server, internal framework
      packages, envoyextensions, command, or api.
    package: "github.com/hashicorp/consul/troubleshoot/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"

  - description: >
      command/** (CLI) must not bypass the public boundary into framework
      internals or envoy primitives. agent/consul/** is already banned;
      add internal/** and envoyextensions/**. Any package that legitimately
      embeds the agent should be split into command/agent and excepted.
    package: "github.com/hashicorp/consul/command/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
```

If the real consul codebase already violates this, run the check, list the offenders, and either refactor or carve a single explicit exception (do not loosen the global rule).
```

```
[F03] SEVERITY: HIGH
Category: Coverage Gap | Glob Syntax Error
Affected Rule / Constraint: `internal/protohcl/**` rule; documented Layer-2 isolation for the root `internal/protohcl` package (line 206 of `inputs/go/1_hashicorp_consul.txt`).

What is wrong:
The file's own generator contract — repeated in every other "root mirror" rule's description — states that `package: ".../foo/**"` may not match the directory-root import path `.../foo`. Every other Layer-2 framework subtree carries an explicit root-mirror rule (see `internal/storage` ↔ `internal/storage/**`, `internal/resource` ↔ `internal/resource/**`, `internal/controller` ↔ `internal/controller/**`, plus the dedicated single-line entries for `internal/multicluster`, `internal/dnsutil`, `internal/protoutil`, `internal/radix`, `internal/resourcehcl`).

`internal/protohcl` is missing. Only `internal/protohcl/**` is configured, which (per the contract) covers `internal/protohcl/testproto` but not the root `github.com/hashicorp/consul/internal/protohcl` package itself.

Why it matters:
A new file under the root `internal/protohcl` package that imports `github.com/hashicorp/consul/agent/consul/state` (or any other Layer 3-5 path) would not be reported by any rule. The protohcl package is small, and its real role (Protocol Buffer ↔ HCL bridge) is a textbook Layer-2 framework concern; an upward import there is exactly the regression these rules exist to catch.

How to fix it:

```yaml
  - description: >
      internal/protohcl root package must match internal/protohcl/** Layer-2
      framework outbound guards. The /** glob may not match the directory
      root import path per the generator contract; this duplicate keeps
      Layer-2 isolation enforced on `internal/protohcl` itself.
    package: "github.com/hashicorp/consul/internal/protohcl"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```
```

```
[F04] SEVERITY: HIGH
Category: Vacuous Rule
Affected Rule / Constraint: Root-mirror rules for `agent/proxycfg-sources` and `agent/rpc`.

What is wrong:
The Review #4 fix added directory-root mirror rules for "every single-directory Layer-3 agent leaf." Two of those mirrors target paths that **do not exist** as Go packages in `inputs/go/1_hashicorp_consul.txt`:

- `github.com/hashicorp/consul/agent/proxycfg-sources` — only `…/proxycfg-sources/catalog` (line 57) and `…/proxycfg-sources/local` (line 58) exist.
- `github.com/hashicorp/consul/agent/rpc` — only `…/agent/rpc/middleware` (line 61), `…/agent/rpc/operator` (line 62), and `…/agent/rpc/peering` (line 63) exist.

Both root mirrors will match zero packages and can never fire. They inflate the rule count (and the test report's "passed" tally) without enforcing anything.

Why it matters:
Beyond the cosmetic noise, they suggest that the generator was applied mechanically to a hand-curated list of "Layer-3 leaves" without intersecting against the actual package list. That same blind spot may hide other incorrect mirrors and (more importantly) gives a false sense of coverage — the "144/144 PASS" report includes two PASSes that were impossible to fail.

How to fix it:
Delete both rules. Their `/**` siblings already cover the real subpackages.

```yaml
# DELETE:
#   - package: "github.com/hashicorp/consul/agent/proxycfg-sources"
#   - package: "github.com/hashicorp/consul/agent/rpc"
```

If the intent is forward-compatibility (a future PR adds a root package), document it explicitly so reviewers do not flag the rule as dead next time.
```

```
[F05] SEVERITY: HIGH
Category: Semantic Error | Internal Contradiction
Affected Rule / Constraint: `agent/consul/state` `contentsRules` interface ban (broken — see [F01]) vs `namingRules` interface implementation rule for the same package.

What is wrong:
Two rules in the file simultaneously:

1. `contentsRules` declares "no interface declarations of any kind in `agent/consul/state`" (currently broken; will become enforceable when [F01] is fixed).
2. `namingRules` declares "structs in `agent/consul/state` that implement `Store` should have a simple name ending with `Store`."

The `namingRules` `interfaceImplementationNamingRule.structsThatImplement` field references an interface name. arch-go's naming rule searches for that interface in the rule's package scope and validates struct names that implement it. If the interface ban becomes effective, there will be **no `Store` interface in the package**, and the naming rule degrades to silent no-op (it cannot find any structs that implement an interface that has been removed).

Either:
- The Store interface lives in `agent/consul/state` today (likely, given consul's actual codebase), in which case fixing [F01] will start failing CI on the contents rule with no architectural change, or
- The Store interface lives elsewhere, in which case the naming rule's documentation ("structs in agent/consul/state that implement Store") misrepresents what arch-go is checking.

Why it matters:
The two rules cannot both be enforced as written. Whichever is "correct" makes the other meaningless. Reviewers will trust both because both currently PASS, but the test report's PASS is hiding a logical contradiction.

How to fix it:
Pick one architecture:

- **Keep the Store interface, drop the no-interface ban.** Replace the contents rule with a narrower one (e.g. forbid only public types ending in a specific shape) or remove it entirely and rely on code review.
- **Remove the Store interface from `agent/consul/state` and move it to a sibling consumer package.** Then drop the naming rule (no interface to validate against in this scope).

Example of the first fix:

```yaml
contentsRules:
  # REMOVED: interfaces ban on agent/consul/state — conflicted with the Store
  # naming rule. Re-introduce only with a precise predicate (e.g. forbid only
  # *exported* interfaces) once arch-go supports it.
```
```

```
[F06] SEVERITY: MEDIUM
Category: Coverage Gap (naming) | Wrong Layer
Affected Rule / Constraint: `namingRules` Reconciler suffix scoped to `internal/controller/**` only.

What is wrong:
The Reconciler naming rule targets `package: "github.com/hashicorp/consul/internal/controller/**"`. The package list includes a **second** controller framework rooted at the server layer: `github.com/hashicorp/consul/agent/consul/controller` (line 20). It is a sibling of `agent/consul/state`, `agent/consul/fsm`, etc. Per the documented architecture, the framework Reconciler contract is the only Reconciler convention enforced. Server-side controller types in `agent/consul/controller` are unconstrained.

Why it matters:
If `agent/consul/controller` defines or implements a Reconciler interface (very likely given the name), the naming convention "all Reconciler implementors must end in `Reconciler`" is not enforced for server-side reconcilers. A class of inconsistent names (e.g. `PeeringHandler` that implements `Reconciler`) escapes detection.

How to fix it:
Add a parallel rule, or generalise the package scope:

```yaml
  - description: >
      Server-side controller reconciler implementations in agent/consul/controller
      must follow the same naming convention as the framework reconcilers
      in internal/controller/**.
    package: "github.com/hashicorp/consul/agent/consul/controller"
    interfaceImplementationNamingRule:
      structsThatImplement: "Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"
```

If the server-side Reconciler interface lives in a different name (e.g. `Handler`, `Controller`), use that interface and adjust the suffix accordingly — verify against the real source tree before merging.
```

```
[F07] SEVERITY: MEDIUM
Category: Vacuous Rule | Naming
Affected Rule / Constraint: `namingRules` "Limiter" suffix for `agent/consul/multilimiter`.

What is wrong:
The rule asserts "structs that implement `RequestLimiter` should end with `Limiter`" and scopes it to `agent/consul/multilimiter`. arch-go's `interfaceImplementationNamingRule` resolves the interface name in the rule's package scope (or as a same-package interface). The `RequestLimiter` interface lives in `agent/consul/rate`, not in `agent/consul/multilimiter` (the previous rule for `rate` confirms that). If `multilimiter` does not declare its own `RequestLimiter` interface, the rule cannot find one to match against and degrades to a no-op (which is consistent with the PASS in the test report).

Why it matters:
The rule was added in the Review #1 fix specifically because the description mentioned `multilimiter` but the rule did not. Splitting it without re-checking the interface scope means CI passes a rule that is never actually testing anything in `multilimiter`.

How to fix it:
Either:
- Reference the interface using arch-go v1's full pattern syntax (`structsThatImplement: "*Limiter"`) so the rule keys on a name pattern instead of a same-package interface, or
- Move both packages under one rule using a glob and confirm the interface lookup spans the glob:

```yaml
  - description: >
      Rate-limiter struct names across agent/consul/{rate,multilimiter} must
      end with "Limiter" to align with the existing pattern.
    package: "github.com/hashicorp/consul/agent/consul/multilimiter"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Limiter"
      shouldHaveSimpleNameEndingWith: "Limiter"
```

(Verify the chosen syntax against the installed arch-go version — both v1.5.4 and v2 support the simple-string form, but the wildcard interpretation differs.)
```

```
[F08] SEVERITY: MEDIUM
Category: Structural Gap | Module Scope
Affected Rule / Constraint: Top-level YAML schema; missing `version: 1` and `threshold:` blocks.

What is wrong:
The canonical arch-go YAML — documented for both `fdaines/arch-go` v1.5.4 and `arch-go/arch-go` v2.x — opens with:

    version: 1
    threshold:
      compliance: 100
      coverage: 100

The generated file omits both. arch-go currently accepts the file (per the PASSing report) but its behaviour falls back to defaults. Two concrete risks:

1. **Threshold drift across versions.** The test report shows `Coverage: 0% [FAIL]` while the overall summary reports PASS. That is only possible because no explicit coverage threshold is set; a future arch-go upgrade that defaults `coverage >= 1%` will fail the build with no architectural change.
2. **Schema-version drift.** The `version: 1` declaration is the only signal that selects between v1-style and v2-style schemas. If the team runs arch-go v2 (the actively-maintained `arch-go/arch-go` fork), the YAML becomes ambiguous: most fields parse identically, but the `namingRules.structsThatImplement` shape changed from a flat string to a nested `internal:` / `external:` / `standard:` map. Without `version: 1`, behaviour is implementation-defined.

Why it matters:
The Makefile installs `github.com/fdaines/arch-go@latest` (v1.5.4 today). If a developer or CI image installs `github.com/arch-go/arch-go/v2/...` instead — a natural mistake given the URL similarity and the fact that v2 is the maintained line — the seven naming rules either fail to parse or silently change semantics. The lack of an explicit version makes that failure mode invisible until a real interface check breaks.

How to fix it:

```yaml
version: 1
threshold:
  compliance: 100
  coverage: 0
```

(Set `coverage: 0` only if the team has explicitly accepted the "coverage metric is not architectural completeness" reading documented in the header. Otherwise pick a real number and resize the rule scope until it is met.)

Add the same module-binary check to the Makefile:

```makefile
.PHONY: arch-version-check
arch-version-check:
	@cd "$(PROJECT_ROOT)" && arch-go --version | grep -E '^arch-go v1\.' \
	  || (echo "ERROR: this YAML targets fdaines/arch-go v1; got a different version"; exit 1)
```
```

```
[F09] SEVERITY: MEDIUM
Category: Coverage Gap | False Positive Risk
Affected Rule / Constraint: `internal/storage/**` rule scope vs the test-only `internal/storage/conformance` package.

What is wrong:
The header's "EXCLUDED / SPECIAL-SCOPE PACKAGES" list explicitly tags `internal/storage/conformance` as a "Storage conformance test suite" outside the five-layer chart. Yet the `internal/storage/**` Layer-2 framework rule (and its `maxReturnValues: 3` function rule, and its `Backend` naming rule) all match the conformance package because `…/conformance` is a strict descendant of `…/storage`.

Why it matters:
Conformance test suites legitimately need to import test fixtures, mock backends, and helpers that live outside Layer 2. If a future PR adds an import from `internal/storage/conformance` to a test helper under (say) `agent/structs/aclfilter` to validate ACL behaviour against the storage conformance contract, the framework rule will fail — even though the package is annotated as "test-only" in the header. The mitigation today is "we got lucky": no such import exists. The structural problem is that documentation-only exclusion lists do not constrain arch-go.

How to fix it:
arch-go does not support per-rule exclude globs in v1. Mirror the existing harness-tree pattern: add an explicit override that scopes the framework rule to the non-test storage prefixes:

```yaml
  # Replace the single internal/storage/** rule with two scoped rules so the
  # conformance suite is not held to product-only constraints.
  - description: >
      internal/storage backends (in-memory, raft) must not import product layers.
      Excludes the test-conformance suite; see internal/storage/conformance below.
    package: "github.com/hashicorp/consul/internal/storage/inmem"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
  - description: same as above, scoped to the raft backend.
    package: "github.com/hashicorp/consul/internal/storage/raft"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
  - description: >
      internal/storage/conformance is a cross-cutting test contract. It is
      explicitly out of scope for product-layer rules but must not import
      command/** or troubleshoot/** to keep CLI/diagnostics decoupled.
    package: "github.com/hashicorp/consul/internal/storage/conformance"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

Apply the same pattern to the `Backend` naming rule and the `maxReturnValues: 3` function rule if their guarantees should also be relaxed for the conformance suite.
```

```
[F10] SEVERITY: MEDIUM
Category: Semantic Error | Comment Accuracy
Affected Rule / Constraint: Top-level `description:` field (lines 114-122).

What is wrong:
The top-level description claims:

    "Lower layers must not import higher layers. Parallel sub-layers (agent
    vs server state) must not cross-import."

The actual rule set explicitly **does not** ban Layer-2 peer imports (`internal/* → internal/*`), and the long preamble documents this as a deliberate relaxation ("Peer imports among internal/** packages and from internal/** into Foundation are intentionally NOT forbidden here; the original prose 'depends on Foundation only' is therefore aspirational."). The top-level description is the only string arch-go surfaces in CI summaries and in the YAML's first 20 lines. A reader who only sees that field will believe a stricter contract is in force than what arch-go actually checks.

Why it matters:
The description sets reviewer expectations. If the in-prose contract diverges from the encoded contract, the rule set is silently mis-advertised. The same drift bit prior reviews — Layer-3 `agent/**` was originally documented as banning `agent/consul/**` while the deny list omitted it.

How to fix it:

```yaml
description: >
  Enforces static import-path layering for github.com/hashicorp/consul along
  five bands: Foundation -> Internal Framework -> Agent / Control Plane ->
  Server / Consensus -> API / CLI / Connect. Lower bands must not import
  higher bands. Layer-2 peer imports (internal/** -> internal/**) are
  intentionally NOT forbidden by this file; the "Foundation only" prose in
  the architecture documentation is enforced via code review and go mod
  graph, not arch-go. Layer-5 leaves carry asymmetric isolation: api/** and
  connect/** are strict leaves, command/** and troubleshoot/** are
  intentionally permissive (see the per-rule comments). Generated protobuf
  stubs, mock packages, test helpers, build-time codegen binaries, and
  diagram-level runtime topology are documented as out of scope.
```
```

```
[F11] SEVERITY: LOW
Category: Redundancy | Vacuous Rule
Affected Rule / Constraint: Layer-3 `/**` rules whose corresponding directory has no subpackages in the input.

What is wrong:
Many of the new directory-root mirrors added by Review #4 are paired with `/**` rules that match zero packages in `inputs/go/1_hashicorp_consul.txt`. Examples (verified against the package list):

- `agent/ae/**`, `agent/auto-config/**`, `agent/blockingquery/**`, `agent/cacheshim/**`, `agent/checks/**`, `agent/configentry/**`, `agent/debug/**`, `agent/dns/**`, `agent/envoyextensions/**`, `agent/exec/**`, `agent/leafcert/**`, `agent/local/**`, `agent/log-drop/**`, `agent/metadata/**`, `agent/metrics/**`, `agent/mock/**`, `agent/netutil/**`, `agent/pool/**`, `agent/proxycfg/**`, `agent/proxycfg-glue/**`, `agent/router/**`, `agent/routine-leak-checker/**`, `agent/submatview/**`, `agent/systemd/**`, `agent/token/**`, `agent/uiserver/**` — every one of these is a leaf directory in the input file with no child packages.

The rules pass vacuously today; the corresponding root rules cover the actual code. The `/**` siblings are forward-compatibility scaffolding, not active enforcement.

Why it matters:
Reviewer cost: 26 of the 144 reported PASSes are double-counted enforcement. The test report's compliance rate looks twice as good as the architectural reality. If a reviewer wants to know "how many independent constraints am I trusting," subtract the 26 duplicates.

How to fix it:
Pick a convention and apply it consistently. Either:
- **Drop the `/**` rule** when the directory is currently a leaf (and add it back when subpackages appear). Smaller file, no double counting.
- **Keep both** but add a visible "// LEAF: /** is forward-compatibility only" tag on the rule's description so the test report inflation is intentional, not an artefact.

This is the only finding in this review where the current design is defensible; it is filed LOW for that reason.
```

```
[F12] SEVERITY: LOW
Category: Maintainability | Dead Code
Affected Rule / Constraint: `Makefile` line 22 — `ARCH_GO_BIN := $(shell command -v arch-go 2>/dev/null || echo "")`.

What is wrong:
`ARCH_GO_BIN` is captured at make-parse time and never referenced by any recipe. Each of `arch-check` and `arch-check-verbose` immediately re-resolves the binary via a shell-local `bin="$(command -v arch-go ...)"`. The header comment above the variable explains *why* the recipes re-resolve, but the variable itself is no longer used anywhere — it is dead code.

Why it matters:
Pure cosmetics; the Makefile works as intended. A future maintainer who tries to "simplify" by switching the recipes back to `$(ARCH_GO_BIN)` will reintroduce the install-then-fail bug Review #4 fixed. Marking the variable as unused (or deleting it and moving the rationale into a leading comment block) prevents accidental regression.

How to fix it:

```makefile
# arch-go must be re-resolved inside each recipe's shell so that targets
# that install arch-go in the same `make` invocation see the freshly
# installed binary. Capturing $(shell command -v arch-go) at parse time
# would lock in an empty value when the binary is missing on first run.
# (No make-level variable is needed; each recipe runs `command -v` itself.)
ARCH_GO_INSTALL := go install github.com/fdaines/arch-go@latest
```

(Delete the `ARCH_GO_BIN := …` line.)
```

```
[F13] SEVERITY: LOW
Category: Wrong Layer | Coverage Gap
Affected Rule / Constraint: `internal/multicluster` (Layer 2) vs `proto-public/pbmulticluster/v2` (Layer 1 / generated).

What is wrong:
The header lists `internal/multicluster` as a Layer-2 internal framework package and `proto-public/pbmulticluster/v2` as a Foundation-layer generated stub. Both are explicitly named multicluster — they describe the same architectural concept but are stratified into different layers with no cross-rule that asserts the relationship. Per the rules:

- Foundation `proto-public/**` cannot import `internal/**`. Good.
- Layer-2 `internal/multicluster` can freely import `proto-public/pbmulticluster/v2`. Good.

But there is no rule that forbids `internal/multicluster` from importing **other** Layer-2 multicluster-adjacent code (e.g. `internal/controller/**`, `internal/storage/**`) — see [F02] in Review #4 (Layer-2 peer-DAG gap). The same gap matters specifically here because multicluster touches storage, controller, and resource simultaneously and is the single most likely place for a Layer-2 peer cycle to appear.

Why it matters:
Re-emphasising what prior reviews flagged in general: the most architecturally significant Layer-2 peer cycles will form around multicluster and resource. The current rule set explicitly opts out of detecting them (per the header narrative). This is policy, not a defect — but a LOW finding is appropriate to record that a single targeted ban would close the most likely real-world gap without committing to a full Layer-2 DAG.

How to fix it:

```yaml
  - description: >
      internal/multicluster orchestrates resource and storage backends; to
      keep the multicluster control plane testable in isolation, it must not
      import the controller framework directly (it should be invoked via
      framework callbacks). This is the single Layer-2 peer ban this file
      enforces; the rest of the Layer-2 peer DAG is documented but not
      automated (see header).
    package: "github.com/hashicorp/consul/internal/multicluster"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal/controller/**"
```

(Verify against the real source tree — if `internal/multicluster` already imports `internal/controller`, choose a different Layer-2 peer ban that closes a real edge.)
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

### Phase Trace (methodology checklist — condensed)

| Phase | Outcome |
|-------|---------|
| **1 — Coverage audit** | 30 documented constraints, 144 generated rules. Critical gaps: contents-rule schema is wrong ([F01]); Layer-5 leaf isolation broken on `troubleshoot/**` and `command/**` ([F02]); root-mirror missing for `internal/protohcl` ([F03]); two vacuous root mirrors for `agent/proxycfg-sources` and `agent/rpc` ([F04]). |
| **2 — Rule precision** | Vacuous-rule findings: [F01] (4 contents rules), [F04] (2 root mirrors), [F07] (multilimiter naming rule), [F11] (26 forward-compat `/**` rules). False-positive risk: [F09] (`internal/storage/conformance`). No `..` style glob errors. Schema misuse on contents rules is the single largest precision defect. |
| **3 — Semantic correctness** | Top-level description misrepresents Layer-2 enforcement ([F10]). State-package interface ban contradicts state-package Store naming rule ([F05]). Module-scope and threshold defaults rely on tool implicit behaviour with no `version: 1` and no `threshold:` block ([F08]). Dependency directions on the working dependency rules are correct (lower bands forbid upper bands; harness trees correctly excluded from server consensus). |
| **4 — Structural completeness** | Reconciler naming convention misses `agent/consul/controller` ([F06]). Layer-2 peer DAG intentionally not encoded — re-flagged at LOW with a single targeted ban suggestion ([F13]). Forward-compat `/**` redundancy noted ([F11]). Makefile dead-variable noted ([F12]). |

---

*Reviewer model: opus-4-7 · Review iteration: #5 · Target rules: `outputs/hashicorp_consul/sonnet-4-6/arch-go.yml` + paired Makefile (post-Review-#4 fixes).*
