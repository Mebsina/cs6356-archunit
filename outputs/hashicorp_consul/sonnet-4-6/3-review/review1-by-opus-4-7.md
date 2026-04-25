### Executive Summary
- Total documented constraints identified: 32 (8 from the architecture image as operational boundaries; 24 from the `arch-go.yml` header: layer ordering, “parallel sub-layers” isolation, exclusions, and the file’s additional intra-subsystem rules)
- Total rules generated: 44 (28 dependency + 6 contents + 6 naming + 4 function rules in `arch-go.yml`, matching the `arch-go` execution report)
- Coverage rate: 18/32 constraints have a corresponding, directionally correct rule; **14 constraints are only partially covered or not enforced at the package / dependency level**
- **Critical Gaps** (constraints with zero or materially incomplete enforcement at the `dependenciesRules` / glob level):
  - **Layer 3 client vs Layer 4 server (C6 / header)**: the broad `github.com/hashicorp/consul/agent/**` rule’s `shouldNotDependsOn` list omits `github.com/hashicorp/consul/agent/consul/**` despite the comment and header stating non-server agent code must not import the server.
  - **Layer 1 completeness (C1)**: `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, and `version` / `version/versiontest` are listed in the header as Foundation but have **no** `dependenciesRules` entry; violations there are invisible to these rules.
  - **Layer 2 completeness (C2)**: `internal/dnsutil`, `internal/gossip/**`, `internal/go-sso/**`, `internal/multicluster`, `internal/protohcl` (and subpackages), `internal/protoutil`, `internal/radix`, `internal/resourcehcl` are listed in the header as Internal Framework but have **no** dependency rules (only some overlap via overly broad `internal/**` naming, which does not constrain imports).
  - **“Internal framework depends on Foundation only” (C2) for `internal/resource` and `envoyextensions`**: `internal/resource/**` and `github.com/hashicorp/consul/envoyextensions/**` do not forbid e.g. `github.com/hashicorp/consul/api/**` and non-`consul` `github.com/hashicorp/consul/agent/**` paths, which contradicts a strict reading of the layer chart for Layer 2 vs 3/5.
- **Tooling note (not a layer defect but misleading in CI)**: the `arch-go` report shows `Coverage: 0% [FAIL]` even when all rules pass — that metric does **not** equate to “architectural constraint coverage”; it reflects the tool’s own coverage heuristics, not a gap matrix for your YAML.
- Overall verdict: `FAIL`

---

### Findings

```
[1] SEVERITY: CRITICAL
Category: Coverage Gap | Semantic Error
Affected Rule / Constraint: C6 (Layer 3 agent excluding server must not import `agent/consul`); `dependenciesRules` “Layer 3” block using `package: "github.com/hashicorp/consul/agent/**"`.

What is wrong:
The comment for the rule states that the client-side agent “must not import the agent/consul server packages,” but the YAML `shouldNotDependsOn.internal` list contains only `command/**`, `api/**`, `connect/**`, and `troubleshoot/**`. It does **not** list `github.com/hashicorp/consul/agent/consul/**`. A non-server package under `github.com/hashicorp/consul/agent/...` (e.g. `github.com/hashicorp/consul/agent/dns` or `github.com/hashicorp/consul/agent/envoyextensions`) can therefore import `github.com/hashicorp/consul/agent/consul/...` without that broad rule ever firing. Sub-rules (`agent/structs`, `agent/config`, `agent/rpcclient`, etc.) do **not** cover every `agent` subtree the way `agent/**` does, so the hole is structurally real.

Why it matters:
A core documented boundary (client / control agent vs `agent/consul` server) is the main “parallel sub-layer” split in the header. An invalid edge such as `import "github.com/hashicorp/consul/agent/consul/state"` from a package under `github.com/hashicorp/consul/agent/dns` would not be reported by the general Layer 3 rule, even though the architecture explicitly forbids it.

How to fix it:
arch-go v2-style dependency rules do not support “`agent/**` but not `agent/consul/**`” in one `package` pattern. Split enforcement so **every** `agent` subtree you care about either duplicates the `agent/consul/**` ban or replace the over-broad `agent/**` with explicit patterns for `agent` subtrees (dns, xds, grpc-*, etc.) and add `shouldNotDependsOn` for `github.com/hashicorp/consul/agent/consul/**` to each, **or** add a second check (custom script or complementary linter) for “importers under `agent` with package path not prefixed by `github.com/hashicorp/consul/agent/consul` must not import `github.com/hashicorp/consul/agent/consul/...`.”

Example additional YAML fragment (illustrative — you must list every non-server `agent` prefix you use):

```yaml
# Example: repeat per subtree or use a single documented list of client-only prefixes
# if arch-go in your version supports multiple package entries:
  - description: >
      agent/dns (example) must not import agent/consul server code.
    package: "github.com/hashicorp/consul/agent/dns"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
```

(Adjust to match your `arch-go` version’s schema; the key fix is **listing `agent/consul/**` for every non-server `agent` package** that the header treats as Layer 3.)
```

```
[2] SEVERITY: CRITICAL
Category: Coverage Gap
Affected Rule / Constraint: C1 (Foundation layer: no imports of Layers 2–5); header lists `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `version/**`.

What is wrong:
`dependenciesRules` only attach to `lib/**`, `sdk/**`, `types`, `acl/**`, `proto/**`, `proto-public/**`, and `logging/**`. The package list in `1_hashicorp_consul.txt` includes e.g. `github.com/hashicorp/consul/ipaddr`, `github.com/hashicorp/consul/sentinel`, `github.com/hashicorp/consul/service_os`, `github.com/hashicorp/consul/snapshot`, `github.com/hashicorp/consul/tlsutil`, `github.com/hashicorp/consul/version` — none have a `package` pattern. Any new import of `github.com/hashicorp/consul/agent/...` or `github.com/hashicorp/consul/api` from e.g. `ipaddr` would not be a violation of **any** dependency rule.

Why it matters:
Foundation invariants (stdlib + shared primitives only) are the bedrock of the diagram’s “bottom layer.” A regression in a root-level primitive package is exactly what static architecture checks are supposed to catch first.

How to fix it:
Add one `dependenciesRules` block per top-level Foundation package, mirroring the existing `lib/**` / `types` style:

```yaml
  - description: Foundation ipaddr must not import non-foundation consul code.
    package: "github.com/hashicorp/consul/ipaddr"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
# Repeat for sentinel, service_os, snapshot, tlsutil, version, version/versiontest
```

(Align the deny list with your strict definition of “higher layer,” e.g. whether `envoyextensions` is in scope for Foundation.)
```

```
[3] SEVERITY: CRITICAL
Category: Coverage Gap
Affected Rule / Constraint: C2 (Layer 2 Internal Framework — header lists `internal/multicluster`, `internal/dnsutil`, `internal/gossip/**`, `internal/go-sso/**`, `internal/protohcl`, `internal/protoutil`, `internal/radix`, `internal/resourcehcl`); compare to `package` patterns present only for `internal/controller/**`, `internal/storage/**`, `internal/resource/**`.

What is wrong:
Numerous `internal/...` trees that the header classifies as Layer 2 have **no** `dependenciesRules`. Examples from `1_hashicorp_consul.txt`: `github.com/hashicorp/consul/internal/dnsutil`, `github.com/hashicorp/consul/internal/gossip/librtt`, `github.com/hashicorp/consul/internal/gossip/libserf`, `github.com/hashicorp/consul/internal/go-sso/oidcauth`, `github.com/hashicorp/consul/internal/multicluster`, `github.com/hashicorp/consul/internal/protoutil`, `github.com/hashicorp/consul/internal/radix`, `github.com/hashicorp/consul/internal/resourcehcl` (and unlisted subpackages). None are matched by a dependency rule, so e.g. `github.com/hashicorp/consul/internal/dnsutil` could import `github.com/hashicorp/consul/api` or `github.com/hashicorp/consul/command/**` and pass.

Why it matters:
The architecture doc positions these as the reusable framework **below** the agent. Without rules, the layer chart is only enforced where the author remembered to add globs — a classic false-negative class for generated configs.

How to fix it:
Add `dependenciesRules` for each `internal/...` subtree, following the same deny lists as `internal/controller/**` (or a stricter variant you intend). Example:

```yaml
  - description: internal/dnsutil must not import product-layer packages.
    package: "github.com/hashicorp/consul/internal/dnsutil"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
# Similarly for internal/gossip/**, internal/go-sso/**, etc.
```

(If the real Consul graph legitimately needs narrow exceptions, document them explicitly rather than leaving the whole subtree unconstrained.)
```

```
[4] SEVERITY: HIGH
Category: Wrong Layer | Overly Broad (naming) | Medium (dependency nuance)
Affected Rule / Constraint: C2; rules for `github.com/hashicorp/consul/internal/resource/**` and `github.com/hashicorp/consul/envoyextensions/**` vs. peer rules for `internal/controller/**` and `internal/storage/**`.

What is wrong:
- **Dependencies**: `internal/resource/**` forbids `agent/consul/**` but not `github.com/hashicorp/consul/agent/structs` or `github.com/hashicorp/consul/api/**`. The header’s Layer 2 “depends on Foundation only” and parallel Layer 2 rules on `envoyextensions` (which forbid `api/**` and `agent/consul` but not generic `agent/**`) are **stricter in prose** than in YAML. Either the documentation overstates the rule, or the rules are too weak.
- **Naming**: `namingRules` with `package: "github.com/hashicorp/consul/internal/**"` applies the `Reconciler` suffix rule to **all** `internal` packages, not only `internal/controller/**` as the comment in the same section suggests.

Why it matters:
A framework package that accidentally takes a dependency on `github.com/hashicorp/consul/api` or a wide swath of `github.com/hashicorp/consul/agent/...` undermines the “leaf / API is outermost” model. The naming rule can spuriously require `*Reconciler` names in unrelated `internal` trees if a type ever implements a `Reconciler` interface there.

How to fix it:
Tighten or clarify:

```yaml
  - description: internal/resource must not import public API, CLI, or agent packages (adjust if your build truly needs a narrow exception).
    package: "github.com/hashicorp/consul/internal/resource/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

Narrow the naming rule:

```yaml
    package: "github.com/hashicorp/consul/internal/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"
```

(Validate against the real module before merging — if legitimate exceptions exist, encode them in `//` or arch-go’s equivalent rather than broad omissions.)
```

```
[5] SEVERITY: HIGH
Category: Structural Gap | Test vs. Production Scope
Affected Rule / Constraint: C7 (excluded test / tooling packages); General notes on `*_test.go` in methodology §16

What is wrong:
The header lists `internal/testing/**`, `tools/**`, `test-integ/**`, `testrpc`, `agent/mock`, `sdk/testutil/**`, and others as out-of-band. The YAML **does not** exclude them from the `package` glob lists — there is no arch-go `exclude` stanza in this file, and the Makefile only runs `arch-go check` with no `--exclude-files` (per the user-provided snippet). The tool therefore still evaluates production-style rules against any matched patterns if those paths import “forbidden” packages. Conversely, if a test helper is **not** matched by a rule, it is invisible (e.g. `testrpc` is outside `agent/**` and most Foundation globs for dependency purposes).

Why it matters:
- Test helpers that intentionally break layering can cause **false positives** in strict dependency rules.
- Unconstrained test / harness packages can hide **false negatives** if the team forgets the exclusion is only in comments.

How to fix it:
Use `arch-go`’s file-exclusion or package-exclude features for your version (e.g. exclude `*_test.go` if you intend dependency rules to apply to production only, and add explicit `dependenciesRules` **or** documented “allow” exceptions for test-only import paths). At minimum, mirror the header’s “EXCLUDED” list in actual configuration, not only in comments.

```yaml
# Example shape — confirm against arch-go version docs
# options:
#   excludeFiles: "**/*_test.go"
#   excludePackages:
#     - "github.com/hashicorp/consul/testrpc/..."
```

(If your `arch-go` build does not support this, state that limitation next to the Makefile so CI users know the scope.)
```

```
[6] SEVERITY: MEDIUM
Category: Overly Broad | Redundancy
Affected Rule / Constraint: `namingRules` for `RequestLimiter` / `agent/consul/multilimiter` (mentioned in text but not in `package` pattern)

What is wrong:
The `namingRules` description names both `agent/consul/rate` and `agent/consul/multilimiter`, but `package` is only `github.com/hashicorp/consul/agent/consul/rate`. The `agent/consul/multilimiter` subtree is not covered, so the stated convention is not enforced for that package. Separately, `structThatImplement: "RequestLimiter"` is a **very narrow** match — unrelated types ending with `Limiter` in `rate` may pass while semantically similar types in `multilimiter` are unchecked.

Why it matters:
Inconsistent naming between sibling limiter packages is a maintainability and discovery problem; the rule text promises more than the glob delivers.

How to fix it:
Add a second naming rule (or a wider glob if appropriate):

```yaml
  - description: multilimiter RequestLimiter implementors must use *Limiter suffix
    package: "github.com/hashicorp/consul/agent/consul/multilimiter"
    interfaceImplementationNamingRule:
      structsThatImplement: "RequestLimiter"
      shouldHaveSimpleNameEndingWith: "Limiter"
```

(Or generalize the package glob if all limiter subpackages share the interface set.)
```

```
[7] SEVERITY: MEDIUM
Category: Semantic Error | contents Rules
Affected Rule / Constraint: “lib must not contain interfaces that reference agent/server” (comment) vs. `github.com/hashicorp/consul/lib/**` `contentsRules`

What is wrong:
`shouldNotContainAnyOf: [interfaces: false]` is effectively a no-op for “forbid interfaces” (boolean `false` disables that facet in arch-go’s schema). The comment therefore claims a constraint the rule does not express. The `[PASS]` line in the test report is consistent with a vacuous check, not a semantic guarantee on cross-type references (which `contents` rules do not see anyway in the way a human reading “reference agent types” would expect).

Why it matters:
Reviewers and future maintainers will believe `lib` cannot define problematic interfaces, when the tool is only (at best) toggling a disabled interface check. Cross-package type “reference” is not what `contents` rules see.

How to fix it:
Either:
- **Delete** the misleading `contents` entry and rely on dependency + code review, or
- **Replace** with a check that your `arch-go` version actually supports (e.g. dependency-only enforcement), and update the description to what is mechanically enforced.

```yaml
# If the intent is truly "no interfaces in lib", and your tool supports it:
# shouldNotContainAnyOf:
#   - interfaces: true
# (Confirm semantics — many orgs *want* small interfaces in lib; do not flip blindly.)
```

For cross-type coupling, a **custom staticcheck / regexp** in CI is often more accurate than `contents` flags.
```

```
[8] SEVERITY: MEDIUM
Category: Structural Gap | Transitivity
Affected Rule / Constraint: C6 and intra-`agent` graph; methodology §15

What is wrong:
Where you forbid `A` → `C` but not `A` → `B` for some `B` in the same band, a compile-time bypass can still appear if a new indirection is introduced. This is a general limit of import-only static rules; this config does not add package-group analysis or reachability checking.

Why it matters:
A developer can split an illegal `agent/dns` → `agent/consul` import into `agent/dns` → `agent/custombridge` → `agent/consul` with `custombridge` only in a test build — still architecturally wrong if your policy is strict.

How to fix it:
Acknowledge the limitation in the review, and reserve **higher** tooling (e.g. forbidden import lists in `go vet` script, Bazel, or a custom `go list -deps` graph check) for critical boundaries like `agent/consul`.
```

```
[9] SEVERITY: LOW
Category: Redundancy | Semantic Error (comments)
Affected Rule / Constraint: `github.com/hashicorp/consul/agent/**` with overlapping specific children; `github.com/hashicorp/consul/agent/consul/fsm` comment

What is wrong:
The `github.com/hashicorp/consul/agent/**` pattern matches **all** `agent` code including `agent/consul` itself. The explicit block list omits `agent/consul` (see [1]) but not because of splitting — this makes the more specific `agent/structs` / `agent/config` rules **partially redundant** with a corrected broad rule for non-server code only. The `fsm` rule’s prose mentions “fsm must not import state in the reverse direction” while the actual `shouldNotDependsOn` set lists other `agent/consul/...` paths — the English does not line up with the list (even if the *graph* in Consul is still sound).

Why it matters:
Redundant rules are noisy; misleading comments are worse than silence because they train readers to distrust the file.

How to fix it:
- Refactor the broad `agent` rule and/or split by subtree so comments match YAML.
- Reword the `fsm` description to match the actual `shouldNotDependsOn` set (or add `state` to the list if the architecture truly requires the FSM package never to import the state store — do **not** guess: verify the real `go` graph first).
```

```
[10] SEVERITY: LOW
Category: Coverage Gap (image vs. generated YAML)
Affected Rule / Constraint: C-IMG1 / C-IMG2 (Control path vs. Data path; server authority)

What is wrong:
`inputs/go/1_hashicorp_consul.jpg` only encodes a runtime topology (servers, clients, proxies, apps) — it does not name Go packages. The generated rules map the diagram to `agent/consul` vs. general `agent` and leaf packages only **indirectly**. The diagram’s “Data Plane must not push control” nuance is not expressed as a distinct set of `dependenciesRules` (you only get it if the Go layering matches 1:1, which is not provable from the picture alone).

Why it matters:
If the course’s intent was “this diagram is the contract,” the YAML is a **separate, richer** contract (the header block). The diagram alone cannot justify the missing `agent/consul` edge in the broad `agent` rule (finding [1]) as “WYSIWYG from the image” — the gap is a defect against the **YAML** spec.

How to fix it:
Treat the diagram as context only; drive CI off the text header and package list, and add cross-links in `arch-go.yml` from diagram concepts to the concrete glob list you enforce (documentation hygiene, not a new rule).
```

---

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | A documented constraint has zero enforcement — real violations will pass CI |
| **HIGH** | A rule is so imprecise it either misses a whole class of violations or produces widespread false positives |
| **MEDIUM** | A rule is technically correct but incomplete — some violations in the constraint's scope escape |
| **LOW** | Cosmetic or maintainability issue (misleading comment, redundant rule, style) |
