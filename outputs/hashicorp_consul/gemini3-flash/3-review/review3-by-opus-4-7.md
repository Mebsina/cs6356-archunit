# Adversarial Review #3 — `outputs/hashicorp_consul/gemini3-flash/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: gemini3-flash
Project: HashiCorp Consul
Round: 3 (after fixes from Review #2)

---

## Executive Summary

- **Total documented constraints identified:** ~20 distinct architectural invariants (each often expanded into many sibling rules — e.g. the intra-`agent/**` client/server split is one invariant materialised as ~36 per-leaf rules):
  1. Foundation (`lib`, `sdk`, `types`, `version`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging`, `acl`) ↛ `agent`/`api`/`command`/`internal`/`connect`/`envoyextensions`/`troubleshoot`
  2. Foundation **may** depend on `proto/**` and `proto-public/**` (preamble lines 14–18 — softer-than-strict layering)
  3. `proto/**`, `proto-public/**` ↛ higher Consul layers
  4. `internal/**` ↛ `agent`/`api`/`command`
  5. `internal/storage/raft` ↮ `internal/storage/inmem` (mutual ban — pluggability)
  6. `connect`, `connect/**` (Layer-3 peer) ↛ `agent`/`api`/`command`
  7. `envoyextensions/**` (Layer-3 peer) ↛ `agent`/`api`/`command`
  8. `troubleshoot/**` (Layer-3 peer) ↛ `agent`/`command`
  9. `agent`, `agent/**` ↛ `api`/`command`
  10. Intra-agent client/server split: non-server `agent/<leaf>` ↛ `agent/consul/**`
  11. `api`, `api/**` ↛ `internal`/`agent`/`command`
  12. `command`, `command/**` ↛ `internal/**`
  13. `grpcmocks/**` ↛ all production layers
  14. Production code (every layer) ↛ harness trees (`testing/deployer/**`, `test-integ/**`, `testrpc`, `tools/**`, `internal/testing/**`, `internal/tools/**`)
  15. Naming — Backend implementations should be `*Backend`
  16. Naming — Reconciler implementations should be `*Reconciler`
  17. Contents — `proto/**` should be data-only (no hand-written interfaces / functions)
  18. Contents — `proto-public/**` same as above
  19. Contents — `grpcmocks/**` no hand-written functions
  20. Function-rule budgets for `internal/**` (`maxLines`, `maxParameters`) and `lib/**` (`maxReturnValues`)

- **Total rules generated:** 87 in `arch-go.yml` (78 dependency rules + 3 contents rules + 3 naming rules + 2 functions rules + 1 threshold block) plus the `Makefile`.

- **Effectively enforcing rules: 0.** The arch-go test report attached to this review is **a YAML unmarshal failure**:

  ```
  Error: yaml: unmarshal errors:
    line 1207: cannot unmarshal !!map into string
    line 1216: cannot unmarshal !!map into string
    line 1225: cannot unmarshal !!map into string
  ```

  These three lines are the `internal: "*Backend"` / `internal: "*Reconciler"` keys inside `structsThatImplement:` — the “v2 structured form” introduced by Round-2’s F14 fix. The arch-go binary actually in use (whatever its version) rejects the map and aborts at parse time, **before** any rule is loaded. Every dependency, contents, naming, and function rule below is therefore unenforced — including all 78 dependency rules whose comments claim to encode the layer hierarchy. The CI pipeline now produces a hard parse error rather than a silent drift, but the **net architectural enforcement is zero**.

- **Coverage rate (semantic, ignoring the parse failure):** ~17 / 20 documented constraints have a corresponding rule in source — but **0 / 20 are actually evaluated** because the file does not parse.

### Critical Gaps (open in Round 3)

1. **CRITICAL — YAML fails to parse.** The Round-2 “v2 schema” fix to the naming rules turned `structsThatImplement: "*Backend"` into a map (`{ internal: "*Backend" }`); the installed arch-go binary expects a string at that position and aborts. Fix-history.md still records the previous round as a successful migration to “v2’s structured form,” but the actual binary in use is the v1.5.4 fork (which is what the `command -v arch-go` short-circuit in the Makefile will pick up if any prior install left a v1 binary on PATH). Until this lands, **every other finding in this review is moot in CI**.
2. **HIGH — `shouldNotContainFunctions: true` on `proto/**`, `proto-public/**`, and `grpcmocks/**` is overly broad.** Once the YAML parses, these three contents rules will fire on essentially every generated `*.pb.go` and every mock file (each contains free funcs like `init()`, `file_xxx_proto_init()`, registration helpers, factory funcs). The reviewer of Round 1 added these rules to satisfy a documented “data-only” intent, but the rule predicate is incorrect for the actual code generator output.
3. **HIGH — Harness defense-in-depth misses `command/**`.** All 11 harness-deny rules cover `agent/**`, `internal/**`, `api/**`, `connect/**`, `envoyextensions/**`, `troubleshoot/**`, `lib/**`, `sdk/**`, `proto/**`, `proto-public/**`, and `acl/**` — but not `command/**`. A CLI command importing `testing/deployer/sprawl` or `test-integ/topoutil` for a “release-engineering helper” is the single most likely real-world harness leak in a Consul-shaped codebase, and it is uncovered.
4. **HIGH — Harness defense-in-depth misses every production *root* package.** All 11 harness-deny rules use only the `**` form; under arch-go’s glob semantics that does not match the populated root packages `agent`, `api`, `connect`, `lib`, `acl`. So `agent/agent.go → testrpc` slips through every harness rule, even though the same root-vs-`**` asymmetry was already caught and fixed by Round-2 F4 / F7 for the layered rules. The same bug class re-emerged in the new harness block.
5. **HIGH — Harness packages that live *inside* `agent/**` are not blacklisted.** `agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, and `agent/xds/validateupstream-test` are harness packages by name and intent but are matched as **origins** by the `agent/**` rules (so they themselves get scanned for layer leaks) and are **never named in any deny-list** (so production agent code may freely import them). The YAML acknowledges this in the comment on lines 1154–1158 but takes no action.

### Overall verdict: **`FAIL`**

Ten of the twelve findings below are CRITICAL, HIGH, or MEDIUM. The headline is the parse failure: a Round-2 fix for a hypothetical v2 schema concern (Review #2 F14) introduced the worst possible regression — instead of catching architectural drift, arch-go now refuses to read the file at all. The remaining nine findings are coverage holes that survived two rounds of review, plus one new HIGH (overly-broad contents rules) that is currently masked by the parse failure but will surface immediately once it is fixed.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Glob Syntax Error / Schema Mismatch (Vacuous Rule × N)
Affected Rule / Constraint: All three `namingRules` (lines 1200–1226), and by
                            transitive arch-go behavior: every other rule in
                            the file.

What is wrong:
The three naming rules use `structsThatImplement` as a YAML map:

    structsThatImplement:
      internal: "*Backend"

The arch-go binary actually in use (per the test report) expects
`structsThatImplement` to be a YAML string, not a map. arch-go aborts
during YAML unmarshal with three errors — one per offending line:

    Error: yaml: unmarshal errors:
      line 1207: cannot unmarshal !!map into string
      line 1216: cannot unmarshal !!map into string
      line 1225: cannot unmarshal !!map into string

Crucially, arch-go's loader is all-or-nothing: a single unmarshal error
exits the program before ANY rule is evaluated. This means that not just
the naming rules, but every dependenciesRule, every contentsRule, and
every functionsRule in this file is presently **vacuous** — none of them
ran during the test that produced the report attached to this review.

Why this contradicts Review #2:
Review #2 F14 recommended migrating to the “v2 structured form”
`structsThatImplement: { internal: "*Backend" }`, on the rationale that
the v2 documentation showed the map shape. The migration was done — and
the arch-go binary in CI rejected it. The binary in question is almost
certainly the v1 fork: the Makefile's `command -v arch-go` check
short-circuits the `go install` step whenever ANY arch-go binary is
already on PATH, so a previously-installed v1 (`fdaines/arch-go@v1.5.4`,
which is what the Round-1 / Round-2 Makefiles wrote out, and which is
what the v1 docs document as the supported shape) survives the rewrite.
The v1 schema for `structsThatImplement` is a flat string.

Why it matters:
- Zero rules are enforced. The 78 dependency rules, 3 contents rules,
  3 naming rules, and 2 function rules in this file are all dead code.
- The CI gate the Makefile invokes will exit non-zero with a YAML
  unmarshal error — depending on the surrounding CI's tolerance, this
  is either a hard build failure (preferable) or, more dangerously, a
  step that gets shelved as a "known broken" arch-go config the team
  routinely overrides.
- fix-history.md records Round 2's F14 migration as completed; the
  next round should not assume the v2 map form is in production until
  it is verified against the actual installed binary.

How to fix it:
Revert all three naming rules to the flat-string form (which is what
both v1 and v2.1.2 actually accept — the v2 map form is real but
optional, and the conservative interoperable encoding is the string).

```yaml
namingRules:
  - description: >
      All structs in internal/storage (root) that implement the Backend
      interface should be named *Backend.
    package: "github.com/hashicorp/consul/internal/storage"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      All structs in internal/storage/** that implement the Backend
      interface should be named *Backend.
    package: "github.com/hashicorp/consul/internal/storage/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      All structs in internal/controller/** that implement the Reconciler
      interface should be named *Reconciler.
    package: "github.com/hashicorp/consul/internal/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"
```

Concurrently, harden the Makefile so a stale v1 binary cannot
silently override the intended v2 install. Either pin to the
arch-go.org v2 module by deleting any existing `arch-go` first, or
verify the installed version after `command -v` succeeds:

```makefile
.PHONY: arch-check
arch-check:
	@echo "==> Verifying arch-go (v2)..."
	@if command -v arch-go >/dev/null 2>&1; then \
	  arch-go --version 2>/dev/null | grep -q "^v2\\." || \
	    go install github.com/arch-go/arch-go/v2@latest; \
	else \
	  go install github.com/arch-go/arch-go/v2@latest; \
	fi
	...
```

After applying both changes, run `arch-go --verbose` and confirm the
report shows non-empty `[PASS]` / `[FAIL]` lines for every rule in the
file (today the same command exits with the unmarshal error before any
rule is reached).
```

```
[F2] SEVERITY: HIGH
Category: Overly Broad Rule
Affected Rule / Constraint: contentsRules — `proto/**` (lines 1167–1173),
                            `proto-public/**` (lines 1175–1180),
                            `grpcmocks/**` (lines 1182–1186)

What is wrong:
All three contents rules set `shouldNotContainFunctions: true`. The
intent — “generated proto and mock packages should hold only data
structures, not hand-written logic” — is reasonable, but the predicate
arch-go evaluates is "this package has zero top-level function
declarations." That predicate is false for *every* generated protobuf
file. A typical Consul `*.pb.go` declares:

    func (x *Foo) Reset()                   // method (probably ok)
    func (x *Foo) String() string           // method
    func (x *Foo) ProtoMessage()            // method
    func init()                             // free function — VIOLATION
    func file_xxx_proto_init()              // free function — VIOLATION
    func init() / func RegisterFooServer()  // grpc-go: free funcs

Generated mocks (e.g. mockery output for `grpcmocks/proto-public/pbacl`)
add several more:
    func NewACLClient(t mockConstructorTestingT) *ACLClient    // free func
    func (m *ACLClient) EXPECT() *ACLClient_Expecter           // method
    func (m *ACLClient) Login(...) ... { return ... }          // method

Why it matters:
Once F1 is fixed and the YAML loads, every Consul `proto/**`,
`proto-public/**`, and `grpcmocks/**` package will report at least
one "function should not exist" violation, fail the rule, and (because
`threshold.compliance: 100` is set) hard-fail CI. The author intends
"no hand-written behavior in generated code" — that is a real and
useful invariant, but `shouldNotContainFunctions` does not encode it.

Two concrete consequences:
- Generated `init()` registration functions in EVERY pb.go become
  layer violations — there is no clean way to suppress them without
  excluding every generated file from arch-go entirely, which then
  invalidates all the layer rules that depend on `proto/**` matching.
- Mock packages will fail the moment they are linted, even though the
  generated code is correct and idiomatic.

How to fix it:
Drop the function ban for proto / mocks; keep the interface ban only.
The “generated, not hand-written” intent is more accurately encoded
by `shouldOnlyContainStructs: false` (which arch-go does not actually
enforce for generated code) plus a build-time check that no `*.go`
under `proto/**` is committed without the `// Code generated by ...`
header. Within arch-go, restrict the contents rules to claims that
are actually true:

```yaml
contentsRules:
  - description: >
      Generated proto packages should not declare hand-written interfaces;
      generated grpc service interfaces are scanned but should be the
      only interfaces present.
    package: "github.com/hashicorp/consul/proto/**"
    shouldNotContainInterfaces: true   # remove if grpc service interfaces are needed

  - description: >
      Generated public proto packages should not declare hand-written
      interfaces.
    package: "github.com/hashicorp/consul/proto-public/**"
    shouldNotContainInterfaces: true

  # NOTE: deliberately removed `shouldNotContainFunctions: true` — generated
  # proto and mock files contain init()/registration funcs that would
  # cause every package in proto/**, proto-public/**, and grpcmocks/** to
  # fail. Pair this with a `make generate-check` step or a presubmit hook
  # that asserts every .go file under proto*/** has a `Code generated`
  # header.

  - description: >
      Mock packages may contain helper free functions (constructors,
      EXPECT helpers); they should not declare interfaces.
    package: "github.com/hashicorp/consul/grpcmocks/**"
    shouldNotContainInterfaces: true
```

(If `shouldNotContainInterfaces: true` itself proves false on at least
one generated package — likely the grpc service stub interfaces — drop
the entire contentsRules block as not-fit-for-purpose and replace it
with a `goimports`/`go vet`-based generated-code presubmit.)
```

```
[F3] SEVERITY: HIGH
Category: Coverage Gap (harness defense missing for the CLI)
Affected Rule / Constraint: Harness defense-in-depth block (lines 1000–1152)

What is wrong:
The “Harness defense-in-depth” block enumerates 11 production layers
that must not depend on the test/codegen harness trees. The list:

  agent/**, internal/**, api/**, connect/**, envoyextensions/**,
  troubleshoot/**, lib/**, sdk/**, proto/**, proto-public/**, acl/**.

Conspicuously missing: `command/**` and `command`. The CLI is the layer
most likely to drift into a “handy test helper just for a release
script” pattern, and Consul's `command/operator/**`, `command/snapshot/**`,
and `command/troubleshoot/**` already wrap functionality from
`internal/**` and `agent/**` — exactly the boundary at which a
harness import would feel naturally justified by the author.

Concretely uncovered today:
  - command/operator/usage      → tools/internal-grpc-proxy
  - command/snapshot/decode     → testing/deployer/topology
  - command/troubleshoot/proxy  → test-integ/topoutil
  - command/operator            → testrpc
  - command/services/register   → internal/testing/golden
  - command/operator/utilization → internal/tools/protoc-gen-grpc-clone

All six edges pass under the present rules.

Why it matters:
A `command/*` package importing a test harness is the most consequential
class of harness leak: CLI binaries pull the harness into the shipped
release. `testing/deployer/sprawl` instantiates whole Consul clusters
in-process; `internal/tools/...` are protoc plugins. Either appearing
in a release binary is a serious supply-chain and binary-size issue.

How to fix it:
Add `command/**` and `command` to the harness defense block:

```yaml
- description: command/** must not depend on harness trees.
  package: "github.com/hashicorp/consul/command/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer"
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools/**"

- description: command (root) must not depend on harness trees.
  package: "github.com/hashicorp/consul/command"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer"
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools/**"
```
```

```
[F4] SEVERITY: HIGH
Category: Glob Syntax Error / Coverage Gap (root packages bypass)
Affected Rule / Constraint: Every harness defense rule (lines 1000–1152)
                            uses only the `**` form.

What is wrong:
arch-go's `**` glob requires at least one extra path segment after the
prefix (this was the central insight behind Review #2 F4). The harness
defense block uses these origin patterns:

  agent/**, internal/**, api/**, connect/**, envoyextensions/**,
  troubleshoot/**, lib/**, sdk/**, proto/**, proto-public/**, acl/**.

The package list (`1_hashicorp_consul.txt`) confirms each of the
following is itself a populated root package with `.go` files (not just
a directory containing sub-packages):

  Line   4: github.com/hashicorp/consul/agent
  Line  83: github.com/hashicorp/consul/api
  Line 185: github.com/hashicorp/consul/connect
  Line 229: github.com/hashicorp/consul/lib
  Line   2: github.com/hashicorp/consul/acl

So the harness defense rules do NOT match `agent/agent.go`,
`api/api.go`, `connect/connect.go`, `lib/lib.go`, or `acl/acl.go`.
A file in any of those root packages can import `testrpc`,
`testing/deployer/sprawl`, or `tools/internal-grpc-proxy` and pass.

Why it matters:
The Round-2 fix introduced this rule block AS the centerpiece response
to Review #2 F5. The exact bug class that Round-2 closed for the
layer rules (root-vs-`**` asymmetry — F4 in Review #2) was re-introduced
inside the new harness block. The defense is leaky precisely at the
populated roots.

How to fix it:
Add a root-mirror rule for each affected origin (5 new rules). The
deny-list payload is identical to the `**` rule above each one:

```yaml
- description: agent root package must not depend on harness trees.
  package: "github.com/hashicorp/consul/agent"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer"
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools/**"

- description: api root package must not depend on harness trees.
  package: "github.com/hashicorp/consul/api"
  shouldNotDependsOn: { ... same payload ... }

- description: connect root package must not depend on harness trees.
  package: "github.com/hashicorp/consul/connect"
  shouldNotDependsOn: { ... same payload ... }

- description: lib root package must not depend on harness trees.
  package: "github.com/hashicorp/consul/lib"
  shouldNotDependsOn: { ... same payload ... }

- description: acl root package must not depend on harness trees.
  package: "github.com/hashicorp/consul/acl"
  shouldNotDependsOn: { ... same payload ... }
```

(`internal`, `proto`, `proto-public`, `envoyextensions`, `sdk`,
`troubleshoot` do not appear as standalone root packages in the
package list, so no root-mirror is needed for those origins.)
```

```
[F5] SEVERITY: HIGH
Category: Coverage Gap (harness packages live inside production tree)
Affected Rule / Constraint: agent/** harness defense rule (lines 1000–1014)

What is wrong:
The `agent/**` harness deny lists six target patterns
(testing/deployer/**, test-integ/**, testrpc, tools/**,
internal/testing/**, internal/tools/**) — but it does NOT list:

  - github.com/hashicorp/consul/agent/grpc-external/testutils    (line 41)
  - github.com/hashicorp/consul/agent/grpc-middleware/testutil   (line 46)
  - github.com/hashicorp/consul/agent/xds/testcommon             (line 81)
  - github.com/hashicorp/consul/agent/xds/validateupstream-test  (line 82)
  - github.com/hashicorp/consul/agent/mock                       (line 52)
  - github.com/hashicorp/consul/agent/grpc-external/testutils

These are harness packages BY NAME (the words "testutils", "testutil",
"testcommon", "test-test", "mock" make the intent unambiguous), and
they live inside `agent/**`. The current `agent/**` harness rule
prevents `agent/dns → testrpc` (good) but does NOT prevent
`agent/dns → agent/grpc-external/testutils` or
`agent/checks → agent/mock` (bad).

The YAML acknowledges this gap in the comment on lines 1154–1158:

  # NOTE: agent/<leaf>/testutils and agent/xds/testcommon are themselves
  # harness packages that LIVE inside agent/**. They are matched by the
  # `agent/**` rule above and will surface as violations until they are
  # either (a) moved under a `//go:build testharness` tag, or (b)
  # refactored out of the agent tree. Track this via an exception
  # comment if needed.

The comment confuses the issue. These packages being matched as
ORIGINS by the layer rule (the rule that says agent/** ↛ api) is fine
— they probably comply because they are agent code. The real gap is
that they are NOT named as TARGETS in any deny-list. Production agent
code can import the in-tree harness with no architectural feedback.

Why it matters:
A test harness inside `agent/**` is exactly the kind of code that
production should not depend on at runtime. `agent/grpc-external/testutils`
defines fake grpc servers and connection factories; pulling that into
`agent/dns` at compile time pulls the test scaffolding into every
release binary. The intra-agent harness leak is invisible to every
existing rule today.

How to fix it:
Append the four in-tree harness packages to the `agent/**` harness
deny rule (and add an `agent` root-mirror rule per F4):

```yaml
- description: >
    Agent layer must not depend on integration / build / test harness
    trees, including the in-tree harness packages under agent/**.
  package: "github.com/hashicorp/consul/agent/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer"
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools/**"
      # In-tree harness packages — same deny treatment.
      - "github.com/hashicorp/consul/agent/grpc-external/testutils"
      - "github.com/hashicorp/consul/agent/grpc-middleware/testutil"
      - "github.com/hashicorp/consul/agent/xds/testcommon"
      - "github.com/hashicorp/consul/agent/xds/validateupstream-test"
      - "github.com/hashicorp/consul/agent/mock"
```

(Repeat the in-tree harness target list on the new `agent` root-mirror
rule from F4. Other layers — internal/**, api/**, command/** — should
also include these targets, since e.g. `api → agent/grpc-external/testutils`
is just as wrong as `agent/dns → agent/grpc-external/testutils`.)

For the agent harness packages themselves — they are matched as
origins by the `agent/**` rule and will appear in coverage. If they
legitimately import `api/**` (say, the api types they are mocking),
move them under a `//go:build testharness` build tag and update the
arch-go invocation: arch-go honors build tags via the standard Go
loader, so `arch-go -tags=testharness` would scan them while a
plain `arch-go` would not. The current YAML does not need this
escape hatch yet, but the comment on lines 1154–1158 should not
imply the gap is fixed.
```

```
[F6] SEVERITY: MEDIUM
Category: Coverage Gap (grpcmocks → harness)
Affected Rule / Constraint: grpcmocks/** dependency rule (lines 951–966)

What is wrong:
The new grpcmocks/** rule (added in Round 2 in response to F8) bans
agent/api/command/internal/connect/envoyextensions/troubleshoot — but
not the harness trees. So:

  grpcmocks/proto-public/pbresource → testing/deployer/sprawl   PASS
  grpcmocks/proto-public/pbacl       → tools/internal-grpc-proxy  PASS

The harness defense block at the bottom of the file has 11 origin rules
(agent, internal, api, connect, envoyextensions, troubleshoot, lib,
sdk, proto, proto-public, acl) — `grpcmocks/**` was not added.

Why it matters:
A mock package that takes a runtime dependency on a test harness creates
a compile-time edge from any test importing the mock into the harness;
that pulls the harness into every test binary that pulls the mock.
The original `grpcmocks/**` rule was added precisely to prevent the
mock tree from collecting transitive layer dependencies — the harness
deny is the same shape of concern, missed.

How to fix it:
Either extend the existing grpcmocks/** rule's deny-list with the
harness targets, or add a 12th harness-deny rule. The latter is more
consistent with the rest of the file:

```yaml
- description: grpcmocks/** must not depend on harness trees.
  package: "github.com/hashicorp/consul/grpcmocks/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer"
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools/**"
```
```

```
[F7] SEVERITY: MEDIUM
Category: Vacuous Rule / Module-scope mismatch
Affected Rule / Constraint: namingRule for `internal/storage` (lines 1201–1208)

What is wrong:
The first naming rule targets the package `github.com/hashicorp/consul/internal/storage`
(the root). For this rule to do useful work, two things must be true:

  (a) The Backend interface must be defined in the `internal/storage`
      root package (so that the structsThatImplement matcher actually
      evaluates a real interface).
  (b) At least one struct in `internal/storage` (root) must implement
      Backend so that the rule's check has a target to evaluate against.

In Consul as of Round-2's package list, `internal/storage` is the
package that DEFINES Backend (interface declaration) — so (a) is true
— but the IMPLEMENTERS live in `internal/storage/raft` (line 222) and
`internal/storage/inmem` (line 221). The root package itself does not
host implementations; it hosts the interface.

The arch-go semantics for `interfaceImplementationNamingRule.package`
are: "for structs declared in the `package` glob, the ones that
implement the matching interface should be named `*Backend`."  If
no struct in the root implements Backend, the rule passes vacuously
— it never gets to evaluate.

The second naming rule (`internal/storage/**`) DOES cover the
implementers, so this is salvageable. But the FIRST rule, taken in
isolation, is structurally incapable of producing a violation —
it is a no-op once F1 is fixed.

Why it matters:
If a future contributor refactors Backend's declaration to a sub-package
(e.g. moves the interface to `internal/storage/types`), the first rule
silently becomes dead — the interface no longer lives in the rule's
`package:` glob, and arch-go has no way to know. There is no signal
that the rule is doing nothing.

How to fix it:
Either delete the first rule (the second covers all real implementers),
OR change the rule's package pattern to "wherever Backend is defined,
look at structs across all of `internal/storage/**` that implement it":

```yaml
# Option A — delete the first naming rule entirely; the
# `internal/storage/**` rule below already covers implementers in
# raft/, inmem/, and any future sibling.

# Option B — keep the first rule but make it a redundant safety net
# whose vacuous-pass is acknowledged.
- description: >
    [Redundant safety net] Structs in internal/storage (root) that
    implement Backend should be named *Backend.  Currently the root
    package contains the Backend interface but no implementers; this
    rule passes vacuously and exists only to keep the Backend constraint
    honored if implementers are ever moved up to the root.
  package: "github.com/hashicorp/consul/internal/storage"
  interfaceImplementationNamingRule:
    structsThatImplement: "*Backend"
    shouldHaveSimpleNameEndingWith: "Backend"
```

(Option A is the cleaner fix; Option B documents intent for readers.)
```

```
[F8] SEVERITY: MEDIUM
Category: Module Scope / Makefile defect (cross-cutting risk)
Affected Rule / Constraint: Makefile `arch-check` target

What is wrong:
The Makefile copies `arch-go.yml` into `$(PROJECT_ROOT)`:

    @cp -f "$(ARCH_CONFIG)" "$(PROJECT_ROOT)/arch-go.yml"
    ...
    rm -f "$(PROJECT_ROOT)/arch-go.yml"

This is silently destructive in two ways:

  1. If `$(PROJECT_ROOT)/arch-go.yml` already exists (some teams keep
     a project-local arch-go.yml for different rule sets, or the
     consul module root may eventually have one), `cp -f` overwrites
     it without warning and `rm -f` deletes it on exit — even on a
     successful run.
  2. If arch-go is interrupted (Ctrl-C, OOM kill, or `set -e` in a
     parent script), the `rm -f` does NOT run. The staged YAML is
     left behind in the consul checkout, and any subsequent build
     invocation that respects `arch-go.yml` (or any IDE that lints
     it on save) is now reading a YAML the developer did not write.

Why it matters:
- Data loss: a developer's local arch-go.yml is overwritten and
  deleted, no backup, no warning.
- Polluted working tree: an aborted run leaves `arch-go.yml` staged in
  the consul module root, which is awkward in a clean-tree-required
  repo (e.g. `git status` becomes noisy mid-debug).
- The Round-2 fix correctly removed the non-existent
  `arch-go check --config` invocation, but the staging-and-cleanup
  approach is its own footgun. arch-go has long had an open issue
  about needing `--config`; the workaround should at minimum protect
  the user's tree.

How to fix it:
Refuse to stage if a local arch-go.yml exists, and clean up via a
trap so abnormal exits also restore the tree.

```makefile
.PHONY: arch-check
arch-check:
	@echo "==> Verifying arch-go installation..."
	@command -v arch-go >/dev/null 2>&1 || \
	  go install github.com/arch-go/arch-go/v2@latest
	@if [ -e "$(PROJECT_ROOT)/arch-go.yml" ]; then \
	  echo "ERROR: $(PROJECT_ROOT)/arch-go.yml already exists. Refusing to overwrite." >&2; \
	  echo "Move it aside first, or set ARCH_CONFIG to that file directly." >&2; \
	  exit 1; \
	fi
	@echo "==> Staging $(ARCH_CONFIG) into $(PROJECT_ROOT)/arch-go.yml..."
	@trap 'rm -f "$(PROJECT_ROOT)/arch-go.yml"' EXIT; \
	  cp -f "$(ARCH_CONFIG)" "$(PROJECT_ROOT)/arch-go.yml" && \
	  cd "$(PROJECT_ROOT)" && arch-go --verbose
```

The `trap '... EXIT'` runs the cleanup on success, failure, and
SIGINT/SIGTERM, eliminating the leftover-YAML class of bugs.
```

```
[F9] SEVERITY: MEDIUM
Category: Vacuous Threshold / Compliance gate
Affected Rule / Constraint: threshold block (lines 103–105)

What is wrong:
The threshold block sets `compliance: 100` and `coverage: 0`. The
`compliance: 100` part requires that 100% of evaluated rules pass —
which is the natural strict gate. But it's worth noting that compliance
is computed against the rules that actually loaded; if (per F1) the
YAML fails to parse, arch-go never reports a compliance percentage at
all. The compliance gate cannot be the safety net that distinguishes
"all rules passing" from "no rules ran" — only the binary's exit code
can.

Concretely, today's run produces:

    Error: yaml: unmarshal errors:
      line 1207: cannot unmarshal !!map into string
      line 1216: cannot unmarshal !!map into string
      line 1225: cannot unmarshal !!map into string
    (process exits non-zero, no compliance line printed)

If a future CI script grep'd for "compliance: 100" in the report to
declare success, it would fail-fast (good). If it grep'd for "FAIL"
(more common), it would fail-fast too. But if it merely checked
`arch-go ... | tee report.txt && [ $? -eq 0 ]`, it depends on
whether the binary exits non-zero on parse failure (yes — verified by
the test report).

The `coverage: 0` part is unchanged from Round 2 and the comment now
correctly labels it "DISABLED — re-enable as a regression sentinel
once a baseline is measured." That comment is accurate, but the actual
sentinel work was deferred. Until coverage is set to a real baseline,
a future PR that deletes 30 rules will still show "0% coverage gate
PASS."

Why it matters:
The two threshold values together do less than they appear to:
- `compliance: 100` is right when rules load, useless when they don't.
- `coverage: 0` is a documented but unactioned TODO.

Combined, the threshold block does NOT detect "the rules silently stopped
applying," which is precisely the failure mode this round of review is
fighting. A real safety gate would set `coverage: <baseline-2>` once
the file parses, AND the CI script around the Makefile should `grep`
the report for an explicit `Compliance:` line and fail if it's
missing — i.e. defense against parse failures.

How to fix it:
1. After F1 lands, run `arch-go --json` once and read out the
   coverage percentage. Set `coverage: <measured - 2>`.
2. Add a CI assertion that the report contains a "Compliance: " line.
   Today the Makefile pipes arch-go output directly to stderr; capture
   it and grep:

```makefile
.PHONY: arch-check
arch-check:
	@... (existing checks) ...
	@cd "$(PROJECT_ROOT)" && arch-go --verbose | tee /tmp/arch-go-report.txt
	@grep -q "^Compliance:" /tmp/arch-go-report.txt || \
	  (echo "FAIL: arch-go did not print a Compliance line; YAML may have failed to parse." >&2 && exit 1)
```

Or, if `--json` is preferred, parse the JSON and assert
`.summary.compliance >= 100` — the failure mode of "no JSON because
parse error" then surfaces as "JSON parse error in CI," which is
strictly better than the current silent zero.
```

```
[F10] SEVERITY: LOW
Category: Comment Accuracy / Description Mismatch
Affected Rule / Constraint: contentsRules block (lines 1167–1186)

What is wrong:
The descriptions for both proto contents rules say:

  "Generated proto packages must not contain hand-written interfaces or
   complex functions; they are reserved for data structures."

The phrase "complex functions" is doing a lot of work that the rule
does not actually encode. `shouldNotContainFunctions: true` is a
boolean: zero functions allowed, regardless of complexity. The phrase
suggests a complexity threshold — there is none. Same shape on the
grpcmocks rule.

Why it matters:
A maintainer reading the description will read it as "no hand-written
high-complexity functions" and (correctly) assume that boilerplate
generated `init()`s are exempt. The rule's actual semantics ARE
"any function counts" — see F2.

How to fix it:
Either tighten the description to match the rule (and accept the
false-positive cost — see F2 for why this is wrong) or, better,
combine F2 (drop the function ban) and F10 (rewrite the description
to reflect the looser rule):

```yaml
contentsRules:
  - description: >
      Generated proto packages should not declare hand-written
      interfaces. (Generated free functions like init() and gRPC
      registration helpers are out of scope for arch-go and are
      enforced by the generator's `Code generated by ...` header.)
    package: "github.com/hashicorp/consul/proto/**"
    shouldNotContainInterfaces: true
```

(Or delete the rule entirely — the "data-only" intent is better
enforced by `goimports`-style presubmit checks on `*.pb.go`.)
```

```
[F11] SEVERITY: LOW
Category: Rule Redundancy
Affected Rule / Constraint: Per-leaf intra-agent rules (lines 594–914)
                            vs. the agent/** layer rule (lines 572–578)

What is wrong:
The intra-agent block contains 35+ explicit rules of the shape:

  package: "github.com/hashicorp/consul/agent/<leaf>"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"

This is correct, but it is also strictly redundant with a single
rule of the form:

  package: "github.com/hashicorp/consul/agent/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"

— EXCEPT for the false-positive on `agent/consul/**` itself (which
matches as origin under the `agent/**` glob and would deny-self).

The fix arch-go supports for self-deny is `shouldOnlyDependsOn` with an
allow-list, but a simpler reading of the diagram says: rather than
enumerate every `agent/<leaf>` independently, a single rule plus a
single carve-out is more maintainable. Adding a new leaf today
requires writing a new rule (or two if the leaf has a populated root
AND a `**` subtree); after a refactor that one always forgets, the
new leaf is silently uncovered (the same bug class as F4 in Review #2).

Why it matters:
Maintainability and quiet drift, not enforcement. The current rules ARE
correct (modulo F1); but each new agent leaf added in the next 12 months
needs to remember to add two more rules to this block, and there is no
test that fails when they don't.

How to fix it:
Replace the 35+ leaf rules with one rule that uses the agent/** glob
plus a self-exception. arch-go does not have an explicit "exclude these
origins" syntax, but a near-equivalent is to say "agent/** does not
depend on agent/consul" and accept that agent/consul/** as an origin
will trivially pass that rule (a package never depends on itself — by
arch-go's semantics, the same-package edge is not flagged).

Concretely, replace lines 594–914 with:

```yaml
# Replaces 35+ per-leaf rules. arch-go does not flag a package's
# dependency on its own subtree; agent/consul/** as an origin is
# matched here but trivially complies (it cannot import itself).
- description: >
    Non-server agent code must not depend on the agent/consul/** subtree
    (Raft FSM, state store, server RPC). Per the diagram, agent/** is
    split into a server side (agent/consul/**) and a client side
    (every other agent/<leaf>); this rule encodes that split with one
    glob instead of 35+.
  package: "github.com/hashicorp/consul/agent/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"

- description: same as above, for the agent root package.
  package: "github.com/hashicorp/consul/agent"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"
```

If, on review, the team finds that some agent/consul/** sub-package
DOES legitimately need to import another agent/consul/** sub-package
(possible — server FSM helpers calling server state code), the
dependency is intra-`agent/consul/**` and the consolidated rule above
matches it, but the deny-list does not list `agent/consul/**` as a
target for `agent/consul/**` origins, so it remains compliant.

Validate by running `arch-go --verbose` after the consolidation and
diffing against the per-leaf report — the violation count should be
identical.

(This is LOW severity because the per-leaf rules are correct; the
consolidation is a maintainability cleanup, not a correctness fix.)
```

```
[F12] SEVERITY: LOW
Category: Comment Accuracy
Affected Rule / Constraint: Foundation preamble (lines 11–18) vs.
                            the foundation deny-lists.

What is wrong:
The Foundation preamble was rewritten in Round 2 (per fix-history.md)
to align with the actual rule semantics. The new wording (line 13):

  "Anything in this layer must not import the agent, api, command,
   internal, connect, envoyextensions, or troubleshoot layers."

is correct for the most part. But the very next paragraph (lines
14–18) softens it:

  "Foundation packages ARE permitted to depend on Layer-2 generated
   proto packages (proto/**, proto-public/**) for serialization helpers."

That part is reflected accurately in the rules (no Foundation deny-list
includes proto/**). However, the preamble does NOT list `acl/**` as a
peer Foundation member (it only appears in the bullet list of L1
packages), and yet the lib/sdk/types/etc. deny-lists do NOT ban
`acl/**`. So the implicit invariant "Foundation peers may depend on
each other" is encoded in the rules but NOT documented.

Symmetrically, `acl` is the only Foundation member whose rule bans
`internal/**` (added in Round 2 fix to F3). All other Foundation
members ban it too, so this is consistent — but the preamble does not
explain why acl is treated identically to lib (lines 28–29 lump them
together).

Why it matters:
A future contributor adding a new Foundation member (say,
`util/randutil`) needs to pattern-match the existing rules. The
preamble does not say "use this 12-target deny list as a template" —
the contributor will copy the wrong neighbor and miss `internal/**`,
`connect`, etc.

How to fix it:
Add a "template" comment block to the Foundation preamble:

```yaml
# Foundation rule template (mirror this for any new Foundation member):
#   - description: <name> must not depend on higher layers.
#     package: "github.com/hashicorp/consul/<name>"
#     shouldNotDependsOn:
#       internal:
#         - "github.com/hashicorp/consul/agent"
#         - "github.com/hashicorp/consul/agent/**"
#         - "github.com/hashicorp/consul/api"
#         - "github.com/hashicorp/consul/api/**"
#         - "github.com/hashicorp/consul/internal"
#         - "github.com/hashicorp/consul/internal/**"
#         - "github.com/hashicorp/consul/command"
#         - "github.com/hashicorp/consul/command/**"
#         - "github.com/hashicorp/consul/connect"
#         - "github.com/hashicorp/consul/connect/**"
#         - "github.com/hashicorp/consul/envoyextensions/**"
#         - "github.com/hashicorp/consul/troubleshoot/**"
#
# (proto/** and proto-public/** are intentionally absent — Foundation
# members may use them for serialization helpers; see lines 14–18.)
```

Then explicitly state in the preamble that Foundation members may
import each other freely (acl, lib, sdk, etc. all sit at the same
layer). This is currently the implicit reading of the rules, but it
should not be left implicit.
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

| ID  | Severity | Category | Fix scope |
|-----|----------|----------|-----------|
| F1  | CRITICAL | Schema Mismatch / Vacuous Rule × N | `structsThatImplement: { internal: "*X" }` map form crashes the YAML loader on the actual installed binary; revert to flat-string form, AND harden the Makefile so a stale v1 binary cannot survive `command -v` |
| F2  | HIGH     | Overly Broad Rule | `shouldNotContainFunctions: true` will fire on every generated `init()` and registration helper in `proto/**`, `proto-public/**`, `grpcmocks/**` — once F1 is fixed, these three rules become a CI bombshell |
| F3  | HIGH     | Coverage Gap (harness) | Harness defense-in-depth has no `command/**` or `command` rule — CLI is the layer most likely to leak `testing/deployer/**`, `tools/**`, `internal/tools/**` into a release binary |
| F4  | HIGH     | Glob Syntax Error / Coverage Gap | All 11 harness-deny rules use `**` only; `agent`, `api`, `connect`, `lib`, `acl` ROOT packages bypass every harness rule — same root-vs-`**` bug class Review #2 fixed for the layer rules |
| F5  | HIGH     | Coverage Gap (in-tree harness) | `agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock` are harness packages that LIVE inside `agent/**` and are not enumerated as deny targets — production agent code may import them |
| F6  | MEDIUM   | Coverage Gap | `grpcmocks/**` has a layer deny-list but no harness deny-list — mocks may import `testing/deployer/**`, `tools/**`, etc. |
| F7  | MEDIUM   | Vacuous Rule | The `internal/storage` (root) naming rule has no implementing structs to evaluate; either delete it or document it as a redundant safety net |
| F8  | MEDIUM   | Module Scope (Makefile) | `cp -f`/`rm -f` of `arch-go.yml` into `$(PROJECT_ROOT)` overwrites a user's existing config without warning and leaves the file behind on Ctrl-C; gate on existing file and use `trap` |
| F9  | MEDIUM   | Threshold (defense in depth) | The `compliance: 100` gate cannot detect "the YAML failed to parse" — add a CI assertion that the report contains a `Compliance:` line; also actually set a non-zero coverage baseline |
| F10 | LOW      | Comment Accuracy | "complex functions" in contents-rule descriptions misrepresents `shouldNotContainFunctions: true` (which is "any function") |
| F11 | LOW      | Rule Redundancy | 35+ per-leaf agent rules are strictly subsumed by a single `agent/**` rule (modulo trivial intra-`agent/consul/**` self-deny which arch-go does not flag); consolidate for maintainability |
| F12 | LOW      | Comment Accuracy | Foundation preamble lacks a "rule template" block for adding new Foundation members; current implicit "copy thy neighbor" approach is a footgun |

**5 of 12 findings are CRITICAL or HIGH.** Round 2's fixes successfully closed the Round-1 bugs that were closed (F2 R2 broadening of connect/envoy/troubleshoot; F3 R2 acl deny-list; F4 R2 agent leaf-root mirrors; F7 R2 internal/storage root mirrors). However, the Round-2 attempt to migrate to a v2 naming-rule schema introduced a fatal regression — the YAML no longer parses on the actual installed binary, so **the test report attached to this round is a parse error, not a ruleset evaluation**. Three new HIGH findings (overly broad contents rules; missing command/** harness deny; root-vs-`**` bug re-emerging in the new harness block) are also genuinely new since Round 2.

---

### What to fix first

If only one round of edits is possible, apply this priority order:

1. **F1** — revert the naming rules to the flat-string form so the YAML parses; harden the Makefile so a stale v1 binary cannot survive `command -v`. Until this lands, every other rule in the file is dead code and the test report cannot be trusted.
2. **F2** — fix the overly broad `shouldNotContainFunctions: true` on `proto/**`, `proto-public/**`, `grpcmocks/**` BEFORE F1 lands; otherwise the moment F1 is fixed, CI fails red on every generated `init()`.
3. **F3 + F4 + F5** — close the three remaining harness-defense holes: add `command/**` and `command`; add root-mirror rules for `agent`/`api`/`connect`/`lib`/`acl`; enumerate the in-tree `agent/<leaf>/testutils` packages as deny targets.
4. **F8 + F9** — make the Makefile non-destructive (refuse to clobber a local arch-go.yml; trap-clean on exit) and add a CI assertion that arch-go produced a `Compliance:` line, so a future regression to F1's failure mode is loud.
5. **F6, F7** — close the remaining MEDIUM coverage gaps (grpcmocks harness; redundant naming rule).

Findings F10, F11, F12 are documentation / maintainability polish and can be batched in a final hygiene pass.

---

**End of Review #3.**
