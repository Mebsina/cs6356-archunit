### Executive Summary

- **Total documented constraints identified:** 30
  - 4 from `inputs/go/1_hashicorp_consul.jpg` (control vs data plane, Raft-only consensus on the server tier, sidecar-mediated app traffic, app isolation from server-side control APIs).
  - 26 from the YAML preamble: five-band layer ordering; the asymmetric Layer-2 peer-import policy (peers allowed except `internal/multicluster -> internal/controller/**`); Layer-3 client vs Layer-4 server isolation including the partial intra-`agent/consul` DAG (`state`, `fsm`, `discoverychain`); Layer-5 leaf isolation for `api/**`, `connect/**`, `troubleshoot/**`, plus the deliberately permissive `command/**`; harness/test-tree exclusion bans (`test-integ`, `testing/deployer`, `testrpc`, `tools`, `internal/testing`, `internal/tools`, module root); Layer-3 isolation pairs (`agent/cache/** -> agent/proxycfg|xds`, `agent/structs -> agent/consul|proxycfg|xds`, gRPC trees `-> grpc-external|xds`, `agent/proxycfg -> agent/xds`); generated-code structural hygiene for `proto/**`, `proto-public/**`, `grpcmocks/**`; naming conventions for `Store`, `Backend`, `Resolver`, `Provider`, `Reconciler` (framework + server), and `Limiter`; function-arity caps for `lib`, `internal/storage`, `agent/consul/state`, `agent/consul/discoverychain`; explicit `version: 1` and `threshold:` block; Makefile module-path / arch-go-version assertion targets.
- **Total rules generated:** 145 (128 dependency, 3 contents, 8 naming, 6 function — per the test report).
- **Coverage rate:** ~25 / 30 constraints have a working, non-vacuous arch-go rule. The 4 diagram-level runtime constraints are explicitly waived in the header. Of the remaining 26 doc constraints, the major net-new defect in this iteration is the **loss** of the `internal/storage/**` framework catch-all when Review #5 split it into per-backend rules (any new backend slips through), plus a few comment / scope inconsistencies around the conformance-suite carve-out.
- **Critical Gaps:**
  1. **`internal/storage/**` catch-all dependency rule was deleted, not re-introduced.** Review #5 replaced the single `internal/storage/**` Layer-2 rule with three named-backend rules (`inmem`, `raft`, `conformance`) plus the existing root mirror. There is now **no rule that catches a new storage backend** added at `internal/storage/<bolt|pebble|etcd>`. The `Backend` naming rule (line 1861) and the `maxReturnValues: 3` function rule (line 1965) still use `internal/storage/**`; the dependency rule is the only one that doesn't. A new sibling backend that imports `agent/consul/**` would PASS the dependency check.
  2. **`internal/storage/conformance` is now under-constrained.** The new conformance carve-out only bans `command/**` and `troubleshoot/**`. It explicitly permits imports of `agent/**`, `api/**`, `connect/**`, and (per the absence of any internal ban beyond the listed two) every other internal tree. The header itself describes conformance as a test contract suite; none of the architecture text suggests it should be allowed to import `agent/consul/state` or `agent/grpc-internal/**`.
  3. **`internal/storage/**`-scoped Backend naming and `maxReturnValues: 3` rules now inadvertently apply to `internal/storage/conformance`.** Review #5's split was applied only to the dependency rule. The other two `internal/storage/**` rules still match the conformance suite, even though the same review's rationale ("conformance suites legitimately need to import test fixtures and helpers across packages") applies symmetrically to test-helper signatures and types. The split is half-done.
  4. **`agent/cache/**` rule description over-promises coverage.** The description (line 783) reads "agent/cache and agent/cache-types must not import …" but the `package:` glob is `github.com/hashicorp/consul/agent/cache/**`, which does NOT match `agent/cache-types` (hyphen, not slash). A separate single-package rule covers `agent/cache-types` (line 813). The over-promising description sets reviewer expectations that the `/**` rule covers both.
- **Tool-result note:** The test report shows 145/145 PASS. Of those, ~30 are documented forward-compatibility scaffolding (the Layer-3 `/**` rules for currently-leaf agent directories, kept on purpose per the YAML's FORWARD-COMPAT NOTE). Independent constraint coverage is therefore meaningfully smaller than the raw rule count.
- **Overall verdict:** **PASS WITH WARNINGS** — the file enforces all of its load-bearing dependency edges, generated-code hygiene rules now use the correct flat-boolean schema, leaf isolation on `api/**`, `connect/**`, `troubleshoot/**`, and the tightened `command/**` is in place, and the version + threshold blocks are now explicit. The findings below are real defects, but most are MEDIUM/LOW; only [F01] is a true CRITICAL coverage regression introduced by Review #5.

---

### Findings

```
[F01] SEVERITY: CRITICAL
Category: Coverage Gap
Affected Rule / Constraint: Review #5's split of `internal/storage/**` into per-backend dependency rules. Constraint: "internal/storage backends are Layer-2 framework and must not import Layer 3-5."

What is wrong:
The Review #5 fix replaced this single rule:

    - package: "github.com/hashicorp/consul/internal/storage/**"
      shouldNotDependsOn:
        internal:
          - "github.com/hashicorp/consul/agent/**"
          - "github.com/hashicorp/consul/command/**"
          - "github.com/hashicorp/consul/api/**"
          - "github.com/hashicorp/consul/connect/**"
          - "github.com/hashicorp/consul/troubleshoot/**"

with three named-backend rules: `internal/storage/inmem`, `internal/storage/raft`, plus a relaxed `internal/storage/conformance` rule, and the pre-existing `internal/storage` root mirror. The `**` glob is gone.

Today the `inputs/go/1_hashicorp_consul.txt` package list only contains those three subpackages, so the lost glob currently lines up 1:1 with the named rules. But the moment a contributor adds a new backend (e.g. `internal/storage/bolt`, `internal/storage/pebble`, `internal/storage/cache`, or any non-test sibling), there will be **no dependency rule that matches it**. A new backend that imports `agent/consul/state`, `command/operator/raft`, or any other forbidden Layer 3-5 path will pass CI.

Why it matters:
The whole reason the file uses `/**` globs everywhere else (e.g. `agent/grpc-internal/**`, `internal/controller/**`, `internal/resource/**`) is forward-compatibility — adding a sub-package later does not silently drop out of the rule. By removing `internal/storage/**` entirely, Review #5 traded a real, brittleness-prone false positive (one test-only conformance suite) for a structural blind spot (every future backend). The conformance suite could have been excluded by adding a dedicated carve-out rule alongside the catch-all, not by deleting the catch-all.

Notably, the same fix kept the catch-all for the *naming* (`Backend`) and *function-arity* (`maxReturnValues: 3`) rules at `internal/storage/**`. The split is asymmetric and only the dependency rule lost the glob.

How to fix it:
Re-introduce the catch-all `internal/storage/**` dependency rule and keep the conformance carve-out as an additive narrower rule (the named per-backend `inmem`/`raft` rules then become redundant and can be deleted):

```yaml
  - description: >
      All non-test internal/storage packages (current backends inmem, raft,
      and any future siblings) are Layer-2 framework and must not import
      product layers (agent, command, api, connect, troubleshoot). The
      conformance test suite carries an explicit relaxation below; any other
      sub-package falls under this catch-all.
    package: "github.com/hashicorp/consul/internal/storage/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  - description: >
      internal/storage/conformance is a cross-cutting test contract suite
      (header EXCLUDED list). It is explicitly out of scope for the
      `internal/storage/**` Layer-2 framework guard above; it must still not
      import the CLI (command/**) or diagnostics tooling (troubleshoot/**)
      so the storage contract stays decoupled from operator surfaces.
    package: "github.com/hashicorp/consul/internal/storage/conformance"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

Note: arch-go evaluates **all matching rules**, so when both the catch-all and the carve-out match the same package, the carve-out **does not loosen** the catch-all — both deny lists are AND-merged at evaluation. That means writing it this way still leaves `agent/**`, `api/**`, `connect/**` banned in conformance via the catch-all. If the policy intent really is to allow `agent/**` imports from conformance (the Review #5 rationale), then the catch-all must use a more restricted pattern instead, e.g. `internal/storage/{inmem,raft}/**` if arch-go supports brace alternation, or fall back to enumerating the productive backends individually plus the root mirror — but **not** by silently dropping the wildcard. The current state hides the policy choice.
```

```
[F02] SEVERITY: HIGH
Category: Overly Broad | Semantic Error
Affected Rule / Constraint: `internal/storage/conformance` rule (lines 524-536). Constraint: Layer-2 framework isolation for the conformance test suite.

What is wrong:
The conformance carve-out only forbids `command/**` and `troubleshoot/**`:

    - package: "github.com/hashicorp/consul/internal/storage/conformance"
      shouldNotDependsOn:
        internal:
          - "github.com/hashicorp/consul/command/**"
          - "github.com/hashicorp/consul/troubleshoot/**"

That permits, for example:
- `internal/storage/conformance` -> `github.com/hashicorp/consul/agent/consul/state`  (server internals)
- `internal/storage/conformance` -> `github.com/hashicorp/consul/agent/grpc-internal/balancer`  (Layer 3)
- `internal/storage/conformance` -> `github.com/hashicorp/consul/api/watch`  (Layer 5)
- `internal/storage/conformance` -> `github.com/hashicorp/consul/connect/proxy`  (Layer 5)
- `internal/storage/conformance` -> `github.com/hashicorp/consul/internal/multicluster`  (peer Layer 2)

A storage-conformance test suite has no plausible reason to import any of those. The Review #5 rationale ("conformance suites legitimately need to import test fixtures and helpers across packages") justifies relaxing the **catch-all** product-layer guard, not opening every door.

Why it matters:
The conformance package is a contract suite that backends run against to prove they obey the storage interface. If a future PR sneaks an `agent/consul/state` import into conformance to "borrow" a helper, the storage contract becomes dependent on the server tree — exactly the inversion the Layer-2 ban exists to prevent. The current rule will not catch that.

How to fix it:
Tighten the conformance rule to only carve out what's actually needed. Reasonable defaults:

```yaml
  - description: >
      internal/storage/conformance is a cross-cutting test contract suite.
      It MAY import Foundation primitives (lib, sdk, types, proto, etc.) and
      sibling internal/storage backends for parameterised testing, but it
      MUST NOT reach into the agent (any subtree), the CLI (command/**),
      the public APIs (api/**, connect/**), or operator tooling
      (troubleshoot/**) — none of which a storage interface contract should
      depend on. Internal framework siblings (controller, resource,
      multicluster) are also out of scope for a storage-contract suite.
    package: "github.com/hashicorp/consul/internal/storage/conformance"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
        - "github.com/hashicorp/consul/internal/controller/**"
        - "github.com/hashicorp/consul/internal/resource/**"
        - "github.com/hashicorp/consul/internal/multicluster"
```

Run the check; if any current import legitimately needs one of these, list the offender and either refactor or carve a single explicit, named exception (e.g. `internal/resource/resourcetest` only) rather than re-loosen the global rule.
```

```
[F03] SEVERITY: MEDIUM
Category: Structural Gap | Semantic Error
Affected Rule / Constraint: `Backend` naming rule (lines 1858-1864) and `maxReturnValues: 3` function rule (lines 1962-1966) — both still scoped to `internal/storage/**`.

What is wrong:
Review #5 split the dependency rule on `internal/storage/**` into per-backend rules to exclude `internal/storage/conformance` from product-layer constraints. The same review left the other two `internal/storage/**` rules untouched:

    - package: "github.com/hashicorp/consul/internal/storage/**"
      interfaceImplementationNamingRule:
        structsThatImplement: "Backend"
        shouldHaveSimpleNameEndingWith: "Backend"

    - package: "github.com/hashicorp/consul/internal/storage/**"
      maxReturnValues: 3

These continue to match `internal/storage/conformance`. The same rationale that motivated the dependency-rule split — "conformance is a test-only suite outside the five-layer chart" — applies symmetrically here:

- The conformance suite may legitimately define mock backends with names like `MockBackend` (which actually satisfies the rule) but also test scaffolding like `BackendVerifier`, `BackendHarness`, `RecordingStore`, etc. that **implement** `Backend` to drive tests but are not themselves backends. Those would fail the naming rule.
- A test-helper function `func RunConformance(b Backend) (results, errors, warnings, error)` (4 returns) would fail the `maxReturnValues: 3` rule. Test helpers commonly return more values than production code.

Why it matters:
Inconsistent treatment of the conformance suite across the three rule families — two enforce as if it's product code, one explicitly carves it out. Reviewers cannot tell which policy is the intended one. The split is only half-done.

How to fix it:
Either re-merge the dependency rule with the other two (keep the conformance suite under all three and accept any constraint failures as bugs to fix in the suite) or split all three rules together. The latter, consistent with Review #5's direction:

```yaml
namingRules:
  - description: >
      Pluggable storage backend implementations in inmem and raft must be
      suffixed with 'Backend' (InmemBackend, RaftBackend). The conformance
      suite is excluded — it defines test scaffolding, not backends.
    package: "github.com/hashicorp/consul/internal/storage/inmem"
    interfaceImplementationNamingRule:
      structsThatImplement: "Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: same as above, scoped to the raft backend.
    package: "github.com/hashicorp/consul/internal/storage/raft"
    interfaceImplementationNamingRule:
      structsThatImplement: "Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

functionsRules:
  - description: >
      Storage backend functions in inmem and raft prefer small signatures;
      up to three return values are allowed where (value, auxiliary, error)
      is clearer than a result struct. The conformance suite is excluded.
    package: "github.com/hashicorp/consul/internal/storage/inmem"
    maxReturnValues: 3

  - description: same as above, scoped to the raft backend.
    package: "github.com/hashicorp/consul/internal/storage/raft"
    maxReturnValues: 3

  - description: >
      internal/storage root package return-signature guard (kept; covers the
      directory root only, not sub-packages).
    package: "github.com/hashicorp/consul/internal/storage"
    maxReturnValues: 3
```

If [F01] is fixed by re-introducing the catch-all dependency rule, then keep the catch-all for naming and functionsRules too and re-evaluate whether the conformance suite actually fails them (today, almost certainly not — but the policy intent should be explicit, not accidental).
```

```
[F04] SEVERITY: MEDIUM
Category: Comment Accuracy | Coverage Gap
Affected Rule / Constraint: `agent/cache/**` rule description (lines 782-784).

What is wrong:
The description on the `agent/cache/**` rule reads:

    description: >
      agent/cache and agent/cache-types must not import agent/proxycfg,
      agent/xds, the server, CLI, public API, or Connect client packages.
    package: "github.com/hashicorp/consul/agent/cache/**"

The `package:` glob does not match `agent/cache-types` — globs split on `/`, not on prefix. `cache-types` is a sibling top-level directory of `cache`, not a child. Today the file does carry a *separate* rule for `agent/cache-types` (lines 810-822), so coverage is intact, but the description on the `cache/**` rule misleads readers into thinking that one rule covers both packages.

Same issue, lower stakes, on the `agent/cache` root-mirror rule (lines 796-808): description says "agent/cache root must match agent/cache/** Layer 3 outbound guards" but uses the same prose pattern. Minor.

Why it matters:
- A maintainer who removes the standalone `agent/cache-types` rule (e.g. as part of a "redundant rule" cleanup) will assume the `cache/**` rule still covers it. It does not.
- arch-go surfaces the rule description in CI summaries; an architectural-review reader who only sees the description will mis-attribute coverage.

How to fix it:
Rewrite the description to match what the rule actually covers, and add a forward pointer to the sibling rule:

```yaml
  - description: >
      agent/cache (Layer 3) must not import agent/proxycfg, agent/xds, the
      server, CLI, public API, Connect client, or troubleshoot packages.
      NOTE: agent/cache-types is a SEPARATE top-level directory (hyphen,
      not slash); it is covered by its own rule below, not by this glob.
    package: "github.com/hashicorp/consul/agent/cache/**"
```

Apply the same clarifying edit to the `agent/cache` root-mirror description and to the `agent/cache-types` description (point back to `cache/** + cache root` for symmetry).
```

```
[F05] SEVERITY: MEDIUM
Category: Coverage Gap (forward-compat)
Affected Rule / Constraint: `agent/cache-types` (line 813), `agent/connect/ca`-related single-directory rules, and `internal/multicluster` (line 596).

What is wrong:
The file consistently pairs every Layer-2/Layer-3 subtree with both a `/**` glob and a directory-root mirror — that pattern is restated in the FORWARD-COMPAT NOTE on lines 1254-1263 and again in many individual rule descriptions ("`/**` may not match the directory root import path; this duplicate keeps the root-package import path constrained"). A handful of rules have only the root and no `/**` sibling. The most architecturally significant of these:

- `github.com/hashicorp/consul/agent/cache-types` — single-package rule, no `/**`. If a child package `agent/cache-types/health`, `agent/cache-types/intentions`, etc. is added later (very plausible — `cache-types` is conceptually a directory of cache type registrations), the new sub-package will not be covered by this rule.
- `github.com/hashicorp/consul/internal/multicluster` — single-package rule, no `/**`. multicluster is documented as orchestrating resource and storage backends; subpackages like `internal/multicluster/v2`, `internal/multicluster/types` are plausible follow-ups. The carefully-chosen `internal/controller/**` ban that closes the most likely Layer-2 peer cycle would not apply to any sub-package.

Less significant but worth flagging for symmetry with the rest of the file:
- `github.com/hashicorp/consul/internal/dnsutil`, `internal/protoutil`, `internal/radix`, `internal/resourcehcl` — all carry only a root-package rule with no `/**` sibling. If any of them gain a sub-package, the Layer-2 framework guard does not extend.

Why it matters:
The file has explicitly chosen a "/**" + root pattern for every other tree. The exceptions above will silently drop new sub-packages out of enforcement. The file is internally inconsistent about this convention.

How to fix it:
Add `/**` siblings for every single-directory Layer-2/Layer-3 rule that today has only the root form. Two examples (apply the same to the others):

```yaml
  - description: >
      agent/cache-types (and any future sub-packages) must not import agent/proxycfg,
      agent/xds, the server, CLI, public API, or Connect client packages.
    package: "github.com/hashicorp/consul/agent/cache-types/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
        - "github.com/hashicorp/consul/agent/proxycfg/**"
        - "github.com/hashicorp/consul/agent/xds/**"

  - description: >
      internal/multicluster sub-packages must inherit the multicluster
      Layer-2 ban including the targeted internal/controller/** peer ban.
    package: "github.com/hashicorp/consul/internal/multicluster/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
        - "github.com/hashicorp/consul/internal/controller/**"
```

Today these `/**` rules will match zero packages and pass vacuously, which is consistent with the Review #4 forward-compat scaffolding pattern already documented in the FORWARD-COMPAT NOTE. Either add them and extend the FORWARD-COMPAT NOTE list, or document the inconsistency explicitly so future readers do not assume the missing sub-packages are already covered.
```

```
[F06] SEVERITY: MEDIUM
Category: Vacuous Rule | Semantic Error
Affected Rule / Constraint: `proto/**` `shouldOnlyContainStructs: true` rule (lines 1813-1822) vs `proto/private/prototest`.

What is wrong:
The `inputs/go/1_hashicorp_consul.txt` package list (line 258) includes `github.com/hashicorp/consul/proto/private/prototest`. The header explicitly documents this as a TEST HELPER ("`proto/private/**/prototest : Test helper; not production code.`", line 50 of the YAML). The file then applies a `shouldOnlyContainStructs: true` `contentsRules` rule to all of `proto/**`, which the test report shows passing.

Two possibilities, both bad:

1. **`prototest` actually violates `shouldOnlyContainStructs` in real consul code** (it contains free assertion functions like `AssertElementsMatch`, `AssertDeepEqual`). In that case the PASS in the test report contradicts the actual source, and either arch-go is not evaluating sub-packages of `proto/**` after all (a serious bug in the file's coverage assumptions) or the rule is being silently bypassed.

2. **`prototest` happens to be all-structs in the version pinned to the inputs**, in which case the rule will spontaneously start failing the moment a maintainer adds a new helper function — a brittleness already documented for the protoc / mockgen brittleness, but **also** caused by `prototest` evolving as a test fixture, which is unrelated to a "generator upgrade" and therefore not covered by the existing BRITTLENESS note.

The header itself names `prototest` as out of scope for the layer chart but the contentsRules glob ignores that scoping. arch-go does not support per-rule exclude-globs in v1, so the documentation-only exclusion does not constrain the tool.

Why it matters:
- If (1) is true, every `contentsRules` PASS is suspect because evaluation is not reaching all matched packages.
- If (2) is true, `prototest` is a CI flake waiting to happen — the next test-helper function added there will fail the build with no architectural change.

Either case undermines the very PASS this file relies on for `proto/**` content hygiene.

How to fix it:
Scope the contents rule to the generated proto packages explicitly, excluding the test helper. arch-go v1 does not support negation in `package:`, so list the real generated trees:

```yaml
contentsRules:

  # Replace the single proto/** rule with one targeting the generated
  # private subtree only, leaving prototest (a test helper named in the
  # header EXCLUDED list) out of scope.
  - description: >
      Generated private protobuf packages should contain only struct
      definitions and their generated method sets. Hand-written interfaces
      or free functions are policy violations. The proto/private/prototest
      sibling is a test helper and is intentionally NOT covered (see
      EXCLUDED / SPECIAL-SCOPE PACKAGES in the header).
    package: "github.com/hashicorp/consul/proto/private/pb*/**"
    shouldOnlyContainStructs: true
```

If arch-go's glob engine does not accept the `pb*` prefix wildcard (the README claims it does — `*` is a single-segment wildcard), enumerate the 13 `pb*` subpackages explicitly. Either way, the rule must stop matching `prototest`.

While here, run the check locally against the real consul source to confirm whether the current `proto/**` PASS is real or vacuous; if it is vacuous, [F06] becomes CRITICAL and the same investigation is owed to `proto-public/**` and `grpcmocks/**`.
```

```
[F07] SEVERITY: MEDIUM
Category: Overly Broad
Affected Rule / Constraint: `command/**` rule (lines 1733-1738) and root mirror (lines 1743-1748).

What is wrong:
The Review #5 fix tightened `command/**` to add bans on `internal/**` and `envoyextensions/**`. The remaining deny list:

    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"

That still permits CLI subpackages to import **any non-server agent subtree**:
- `command/operator/raft` -> `agent/grpc-internal/balancer`  (Layer 3 internal gRPC stack)
- `command/connect/envoy` -> `agent/proxycfg-glue`  (Layer 3 proxy config glue)
- `command/troubleshoot/proxy` -> `agent/xds/extensionruntime`  (Layer 3 XDS internals)
- `command/snapshot/restore` -> `agent/cache-types`  (Layer 3 cache type registry)
- `command/peering/establish` -> `agent/rpc/peering`  (Layer 3 peering RPC)

The rule's own description (line 1727: "command/** ... must reach the agent for entrypoints") justifies allowing `agent` for CLI commands that embed the agent (`command/agent`). It does **not** justify letting any of the 80+ command sub-packages reach into arbitrary Layer-3 internals.

Compare to `connect/**`, `api/**`, and `troubleshoot/**`, all of which now ban the entire `agent/**` tree. `command/**` is the lone Layer-5 leaf that retains broad inward access.

Why it matters:
The Layer-5 leaf-isolation policy stated in the header ("These are leaf packages and must not be imported by inner layers") implies the symmetric reading too: leaves should not reach into inner layers either. The current `command/**` rule encodes that asymmetrically. New CLI features that link directly to internal Layer-3 subsystems (instead of going through the public RPC contract) will pass CI silently.

How to fix it:
Either narrow the agent allowance to only the entrypoint shim (`command/agent` and a small carve-out), or document the trade-off prominently in both the rule description and the top-level `description:` block. A concrete approach using arch-go v1's allow/deny semantics:

```yaml
  - description: >
      Most CLI command subpackages must not import the inner agent stack.
      The single legitimate `agent` consumer is command/agent (which embeds
      the agent for the `consul agent` entrypoint). Every other command/*
      that needs runtime Consul state must call the public api/** or RPC
      contract, not import agent internals.
    package: "github.com/hashicorp/consul/command/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/agent/grpc-internal/**"
        - "github.com/hashicorp/consul/agent/grpc-external/**"
        - "github.com/hashicorp/consul/agent/grpc-middleware/**"
        - "github.com/hashicorp/consul/agent/proxycfg/**"
        - "github.com/hashicorp/consul/agent/proxycfg-glue/**"
        - "github.com/hashicorp/consul/agent/proxycfg-sources/**"
        - "github.com/hashicorp/consul/agent/cache/**"
        - "github.com/hashicorp/consul/agent/cache-types"
        - "github.com/hashicorp/consul/agent/xds/**"
        - "github.com/hashicorp/consul/agent/leafcert/**"
        - "github.com/hashicorp/consul/agent/local/**"
        - "github.com/hashicorp/consul/agent/rpc/**"
        - "github.com/hashicorp/consul/agent/rpcclient/**"
        - "github.com/hashicorp/consul/agent/submatview/**"
        - "github.com/hashicorp/consul/agent/router/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
```

Run `arch-go check` against the real consul source first; if `command/agent` itself imports any of the listed Layer-3 packages, accept the allow (it's the entrypoint), but every other command/* that pulls them in is the policy bug this rule should expose. If a wholesale ban on `agent/**` is preferable and the only legitimate exception is `command/agent`, restructure as a per-subpackage rule allowing `command/agent -> agent/**` and denying `agent/**` from every other `command/*` (arch-go cannot express this directly; the `command/**` rule will need to enumerate the agent-importing subpackages individually, which is itself a useful audit).
```

```
[F08] SEVERITY: LOW
Category: Coverage Gap | Structural Gap
Affected Rule / Constraint: Intra-`agent/consul` peer DAG (lines 1649-1690).

What is wrong:
The intra-server DAG is encoded for three packages only:
- `agent/consul/state` bans `fsm`, `discoverychain`, `prepared_query`, `gateways`, `usagemetrics`, `wanfed`, `reporting`.
- `agent/consul/fsm` bans the same minus `state` (i.e. fsm may import state).
- `agent/consul/discoverychain` bans `state` and `fsm`.

The header acknowledges this is partial ("the rest of the server tree (stream, watch, auth, xdscapacity, controller, etc.) is not a full pairwise matrix here. Cycle detection across the server tree is delegated to `go build` / import-graph analysis in CI"). Concrete missing constraints:

- **`agent/consul/auth`**, **`authmethod`**, **`controller`**, **`autopilotevents`**, **`reporting`**, **`watch`**, **`xdscapacity`** have no peer-isolation rules at all. Any of them can freely import each other and any of `state`/`fsm`/`discoverychain`/`prepared_query`/`gateways`/`usagemetrics`/`wanfed`.
- **`agent/consul/state`** does not ban `auth`, `authmethod`, `controller`, `multilimiter`, `rate`, `servercert`, `stream`, `watch`, `xdscapacity` — any of which importing `state` is fine, but `state` importing them creates exactly the cycles the partial DAG exists to catch.
- **`agent/consul/fsm`** has the same gap.

Why it matters:
The header says "delegate to `go build`" — but `go build` only catches cycles at compile time. Layered correctness (e.g. "`state` must be a sink, never a source") cannot be expressed in `go build`; only an explicit deny list does that. The partial DAG today catches three named cycles and silently allows every other intra-server mis-coupling. A new contributor adding a `state -> auth` edge will not be caught.

How to fix it:
This is documented as a deliberate scope trade-off, so the appropriate fix is incremental: add the most architecturally significant missing edges. Concrete suggestions:

```yaml
  - description: >
      agent/consul/state extension: state is the server-layer sink and must
      not import auth, controller, watch, xdscapacity, or multilimiter.
      These are all higher-tier server modules that build on state; an
      import in the reverse direction is an intra-server cycle.
    package: "github.com/hashicorp/consul/agent/consul/state"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/auth"
        - "github.com/hashicorp/consul/agent/consul/authmethod"
        - "github.com/hashicorp/consul/agent/consul/autopilotevents"
        - "github.com/hashicorp/consul/agent/consul/controller"
        - "github.com/hashicorp/consul/agent/consul/multilimiter"
        - "github.com/hashicorp/consul/agent/consul/rate"
        - "github.com/hashicorp/consul/agent/consul/watch"
        - "github.com/hashicorp/consul/agent/consul/xdscapacity"
        - "github.com/hashicorp/consul/agent/consul/fsm"
        - "github.com/hashicorp/consul/agent/consul/discoverychain"
        - "github.com/hashicorp/consul/agent/consul/prepared_query"
        - "github.com/hashicorp/consul/agent/consul/gateways"
        - "github.com/hashicorp/consul/agent/consul/usagemetrics"
        - "github.com/hashicorp/consul/agent/consul/wanfed"
        - "github.com/hashicorp/consul/agent/consul/reporting"
```

If verifying that against real consul reveals existing imports the new ban would flag (e.g. `state -> stream` is intentional), keep `stream` out of the deny list, but the others are the real candidates. The header's "delegate to `go build`" framing should be tightened to "delegate to `go build` PLUS this extended state/fsm DAG."
```

```
[F09] SEVERITY: LOW
Category: Coverage Gap
Affected Rule / Constraint: Module-root `github.com/hashicorp/consul` rule (lines 459-467).

What is wrong:
The single rule on the module root only forbids dependencies on test harnesses (`test-integ/**`, `testing/deployer/**`, `testrpc`). It says nothing about the layer chart. Per the package list (line 1), `github.com/hashicorp/consul` is a real top-level Go package — typically the `main` package for the `consul` binary.

That means the root package can legally import:
- `agent/consul/**` (Layer 4 server)
- Both `command/**` and `agent/**` together (which would tangle CLI and agent at the entrypoint level)
- `internal/**` (Layer 2)
- `envoyextensions/**` (Layer 2)

Reading the rule charitably, "module-root must wire everything together" is fine for `main`. But arch-go has no way to distinguish `package main` from `package consul` at this path; the rule applies to whatever Go code lives at the directory root.

Why it matters:
If the `main.go` at the root grows non-`main` library code (a not-uncommon Go anti-pattern), that library code escapes every Layer rule. A subtler concern: the file's whole reason for the harness ban is "production entrypoints must not couple to test-only trees." The same rationale ("the entrypoint is the choke point of the whole binary") applies symmetrically to the layer chart — the entrypoint is the one place where every layer gets wired together, so the rule is necessarily permissive, but it should explicitly waive the layer chart with a comment, not silently drop it.

How to fix it:
Document the deliberate omission and consider the asymmetric ban suggestion:

```yaml
  - description: >
      Module-root glue at github.com/hashicorp/consul is the binary's main
      package and legitimately wires every layer together at startup.
      It is therefore EXEMPT from the five-layer chart, but must not depend
      on integration harness packages as libraries (production entrypoints
      must not couple to test-only trees). Adding non-main library code at
      this path is discouraged; that code should live in agent/** or one of
      the leaf trees instead. arch-go cannot enforce package-main vs
      package-library at the same directory; this is reviewer-enforced.
    package: "github.com/hashicorp/consul"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/test-integ/**"
        - "github.com/hashicorp/consul/testing/deployer/**"
        - "github.com/hashicorp/consul/testrpc"
        - "github.com/hashicorp/consul/internal/testing/**"
        - "github.com/hashicorp/consul/internal/tools/**"
        - "github.com/hashicorp/consul/sdk/testutil/**"
        - "github.com/hashicorp/consul/lib/testhelpers"
```

The expanded harness ban (added entries for `internal/testing`, `internal/tools`, `sdk/testutil`, `lib/testhelpers`) closes the same class of "production code reaches into test-only helpers" defect that the existing `test-integ`/`testing/deployer`/`testrpc` ban already catches.
```

```
[F10] SEVERITY: LOW
Category: Redundancy
Affected Rule / Constraint: ~30 forward-compat `/**` rules for currently-leaf agent directories (lines 883-1241 spanning many subtrees), already documented in the FORWARD-COMPAT NOTE comment block.

What is wrong:
The Review #4 + Review #5 fixes documented 26 `/**` rules paired with directory-root mirrors that match zero packages today. The FORWARD-COMPAT NOTE on lines 1254-1263 explicitly enumerates them and tags them as "scaffolding for currently-leaf directories," which is a defensible design choice for forward-compatibility.

Two follow-on quibbles:

1. **The note's enumerated list is incomplete.** The note lists 26 directories: `agent/ae`, `auto-config`, `blockingquery`, `cacheshim`, `checks`, `configentry`, `debug`, `dns`, `envoyextensions`, `exec`, `leafcert`, `local`, `log-drop`, `metadata`, `metrics`, `mock`, `netutil`, `pool`, `proxycfg`, `proxycfg-glue`, `router`, `routine-leak-checker`, `submatview`, `systemd`, `token`, `uiserver`. But the test report shows additional `/**` rules with the same property (zero matching sub-packages today): the 26 above plus at least `agent/cache-types` (no `/**` sibling but the description mentions it), and the `agent/connect/**` rule (which currently only matches `agent/connect/ca` — this one has a real sub-package, so it's not vacuous, but the line between "leaf today" and "single-sub-package today" is fuzzy in the note).
2. **The naming-rule and function-rule scaffolding is silently treated the same way without being called out.** The same FORWARD-COMPAT NOTE rationale arguably applies to `lib/**`, `internal/storage/**` (which is now per-backend for dependency rules — see [F03] — but `**` for naming/functions), and `proto/**`/`proto-public/**`/`grpcmocks/**` contents rules. The note should clarify which `/**` rules are forward-compat scaffolding vs which are actively load-bearing today.

Why it matters:
LOW because the design is defensible and explicitly documented. Filed for completeness so the next reviewer does not re-flag the 30 vacuous scaffolds without knowing they were intentionally retained.

How to fix it:
Update the FORWARD-COMPAT NOTE to (a) list the actual current set of zero-match `/**` rules verified against `inputs/go/1_hashicorp_consul.txt`, and (b) explicitly call out which `/**` rules in *every* rule family (dependenciesRules, contentsRules, namingRules, functionsRules) are scaffolding vs load-bearing. No code change required.
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
| **1 — Coverage audit** | 30 documented constraints, 145 generated rules. Critical gap: `internal/storage/**` catch-all dependency rule was deleted (not re-introduced) when conformance was carved out — any new backend escapes the Layer-2 ban ([F01]). High gap: conformance carve-out is too permissive ([F02]). Other coverage holes: forward-compat scaffolding inconsistency on `agent/cache-types` and `internal/multicluster` ([F05]); module-root rule only bans test harnesses, not the layer chart ([F09]). |
| **2 — Rule precision** | `proto/**` `shouldOnlyContainStructs` matches the documented test-only `prototest` helper — either vacuous or a false-positive timebomb ([F06]). The `internal/storage/conformance` carve-out is asymmetric (banned only `command/**` and `troubleshoot/**`, allowing the entire rest of the codebase) ([F02]). The `Backend` naming rule and `maxReturnValues: 3` function rule for `internal/storage/**` still match conformance, inconsistent with the dependency-rule split ([F03]). No `..` style glob errors. The `agent/consul/multilimiter` `*Limiter` wildcard verified against arch-go's `interfaceImplementationNamingRule` docs — supported syntax. |
| **3 — Semantic correctness** | `agent/cache/**` rule description over-promises by claiming it covers `agent/cache-types` (which is a sibling top-level directory, not a child) ([F04]). Layer-5 leaf isolation: `command/**` is broadly permissive of inner agent subtrees compared to the symmetric posture of `connect/**`, `api/**`, `troubleshoot/**` ([F07]). Module scope: `version: 1`, `threshold: { compliance: 100, coverage: 0 }`, and Makefile `arch-version-check` are now in place — Review #5 fixes verified intact. |
| **4 — Structural completeness** | Intra-`agent/consul` peer DAG remains partial — only `state`/`fsm`/`discoverychain` encoded; `auth`, `authmethod`, `controller`, `watch`, `xdscapacity`, etc. are unconstrained ([F08]). Forward-compat `/**` redundancy noted ([F10]). Naming, contents, and function rules cover the documented conventions; the only structural concern is the `internal/storage/**` half-split ([F03]). |

---

*Reviewer model: opus-4-7 · Review iteration: #6 · Target rules: `outputs/hashicorp_consul/sonnet-4-6/arch-go.yml` + paired Makefile (post-Review-#5 fixes).*
