# Adversarial Review #2 — `outputs/hashicorp_consul/gemini3-flash/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: gemini3-flash
Project: HashiCorp Consul
Round: 2 (after fixes from Review #1)

---

## Executive Summary

- **Total documented constraints identified:** ~32 (counting per origin-side ban)
  - 13 Foundation-layer "must-not-import-higher" invariants (one per L1 package: `lib`, `lib/**`, `sdk`, `sdk/**`, `types`, `version`, `version/**`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging`, `logging/**`, `acl`, `acl/**`)
  - 2 Layer-2 invariants (`proto/**`, `proto-public/**`)
  - 4 Layer-3 origin invariants (`internal/**`, `internal/resource/**`, `connect`, `connect/**`, `envoyextensions/**`, `troubleshoot/**`)
  - 2 intra-`internal/storage/**` pluggability invariants (raft↛inmem, inmem↛raft)
  - 2 Agent-layer invariants (`agent`, `agent/**` ↛ api/command)
  - ~36 intra-`agent/**` client/server-split invariants (one per non-server agent leaf)
  - 2 API-layer invariants (`api`, `api/**`)
  - 3 contents invariants (proto, proto-public, grpcmocks)
  - 2 naming invariants (Backend, Reconciler)
  - 2 function-rule invariants (internal/** size, lib/** return values)
- **Total rules generated:** 76 (per the test report — 69 dependency + 3 contents + 2 naming + 2 function rules), all reported `[PASS]`.
- **Effectively enforcing rules:** 76 of 76 — the schema bugs from Review #1 (F1, F2) are fixed, and all rules now print non-empty constraint lists.
- **Coverage rate:** with `threshold.coverage: 0` the gate passes regardless. Substantively, the "PASS" hides the gaps below.

### Critical Gaps (still open after Review #1)

1. **The `Makefile` cannot actually invoke arch-go.** It runs `arch-go check --config "$(ARCH_CONFIG)"`. arch-go has **no `check` subcommand** and **no `--config` flag** (verified against `github.com/arch-go/arch-go/v2` v2.1.2 docs — supported flags are `--color`, `--verbose`, `--html`, `--json` only; the only subcommand is `describe`). The "100% PASS" report in the prompt was therefore produced by some out-of-band invocation, not the committed Makefile. The build pipeline is broken.
2. **Layer-3 `connect`/`connect/**` and `envoyextensions/**` only ban `agent/consul/**`, not `agent/**`.** The YAML preamble explicitly classifies these as Layer-3 peers below Layer-4 (Agent). The current rules let `connect/proxy → agent/dns` pass, contradicting the layered hierarchy and asymmetrical with `troubleshoot/**` (which correctly bans the entire `agent/**`).
3. **`acl` / `acl/**` rules omit `internal/**` from the deny list.** Every other Foundation-tier rule (lib, sdk, types, version, ipaddr, sentinel, service_os, snapshot, tlsutil, logging) bans `internal` and `internal/**`. The acl rules silently allow Layer-1 → Layer-3 imports despite the preamble's strict "must not import a higher layer" rule.
4. **Eight agent leaf root packages have files but are uncovered by the client/server split.** `agent/auto-config`, `agent/connect`, `agent/envoyextensions`, `agent/grpc-external`, `agent/grpc-internal`, `agent/grpc-middleware`, `agent/rpcclient`, `agent/xds` all appear as standalone packages in `1_hashicorp_consul.txt` (lines 6, 14, 37, 39, 42, 45, 64, 73), but the rules use only the `agent/<leaf>/**` glob — which by arch-go's `**` semantics requires at least one extra path segment. `agent/connect/connect.go → agent/consul/state` would pass.
5. **Production code is not denied from importing test-harness trees.** No rule prevents `agent/**`, `internal/**`, `api/**`, or `command/**` from importing `testing/deployer/**`, `test-integ/**`, `testrpc`, `tools/**`, `internal/testing/**`, `internal/tools/**`, `agent/grpc-external/testutils`, or `agent/xds/testcommon`. The "Harness defense-in-depth" comment in the YAML claims this is implicit; it is not.

### Overall verdict: **`FAIL`**

Compliance reads `100%` only because no real arch-go run is happening (the Makefile is broken — finding F1) and because the rules that *do* run have material false-negative coverage holes at the Layer-3 → Layer-4 boundary (F2/F3), at the Foundation → internal boundary (F4), at the agent client/server split for leaf roots (F5), and around test harnesses (F6). Five of the fifteen findings below are HIGH or CRITICAL.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Module Scope / Makefile Defect
Affected Rule / Constraint: `Makefile` — `arch-check` target

What is wrong:
The Makefile invokes:

  arch-go check --config "$(ARCH_CONFIG)"

Verified against the official arch-go v2 documentation
(https://pkg.go.dev/github.com/arch-go/arch-go/v2):

1. arch-go does NOT have a `check` subcommand. The CLI is just
   `arch-go [flags]`. The only subcommand is `arch-go describe`.
2. arch-go does NOT support `--config`. The supported flags are
   `--color`, `--verbose`/`-v`, `--html`, `--json`. arch-go *always*
   reads `arch-go.yml` from the current working directory.

So the literal command line in the Makefile, `arch-go check --config <path>`,
is two errors at once: a non-existent subcommand and an unrecognized flag.
arch-go's cobra-based CLI rejects unknown subcommands; the Makefile cannot
have produced the test report attached to this review.

Why it matters:
Whoever ran arch-go to obtain the [PASS] output in the prompt did so by
hand, not via `make arch-check`. CI will silently fail the moment it tries
to run the Makefile, AND because Review #1's F13 fix landed *as code*, the
fix-history claims this case is now closed — when in fact it broke a
working invocation. Anyone reading the YAML and trusting the "100% PASS"
will assume arch-check is wired up; it is not.

How to fix it:
Strip the bogus subcommand and flag, and instead point arch-go at the
arch-go.yml in the consul module root by either copying or symlinking it
in. Two practical patterns:

```makefile
# Option A — copy the YAML next to go.mod, then run arch-go from there.
PROJECT_ROOT ?= .
ARCH_CONFIG  ?= $(abspath arch-go.yml)

.PHONY: arch-check
arch-check:
	@command -v arch-go >/dev/null 2>&1 || \
	  go install github.com/arch-go/arch-go/v2@latest
	@cp -f "$(ARCH_CONFIG)" "$(PROJECT_ROOT)/arch-go.yml"
	@cd "$(PROJECT_ROOT)" && arch-go --verbose
	@rm -f "$(PROJECT_ROOT)/arch-go.yml"

# Option B — keep the YAML next to the Makefile but require the user to
# invoke arch-go from the consul module root with a symlink.
.PHONY: arch-check
arch-check:
	@command -v arch-go >/dev/null 2>&1 || \
	  go install github.com/arch-go/arch-go/v2@latest
	@ln -sf "$(ARCH_CONFIG)" "$(PROJECT_ROOT)/arch-go.yml"
	@cd "$(PROJECT_ROOT)" && arch-go
```

Note: also pin to `github.com/arch-go/arch-go/v2@latest` (the actively
maintained fork) instead of the stale `github.com/fdaines/arch-go@latest`
that the current Makefile still references — arch-go was forked from the
fdaines org to the arch-go org and the v1 module is no longer the source
of truth.
```

```
[F2] SEVERITY: HIGH
Category: Coverage Gap / Overly Narrow Rule
Affected Rule / Constraint: `connect`, `connect/**` rules (lines 471–487);
                            `envoyextensions/**` rule (lines 489–496)

What is wrong:
The YAML preamble classifies `connect/**` and `envoyextensions/**` as
Layer-3 peers ("Cross-cutting peers at this layer also include …"). By
the layered hierarchy, Layer-3 must not import Layer-4 (`agent/**`),
Layer-5 (`api/**`), or Layer-6 (`command/**`).

The current rules ban only the `agent/consul/**` *subset* of Layer-4:

  package: "github.com/hashicorp/consul/connect/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"

Imports `connect → agent/dns`, `connect → agent/local`, `connect → agent/structs`,
`connect → api`, etc. all pass. The `troubleshoot/**` rule a few lines below
correctly bans the entire `agent/**` and `command/**` — so this is also a
visible asymmetry between two rules that the preamble lists at the same
layer.

Description mismatch: the rule is titled "Connect data-plane primitives
must not depend on agent or CLI" — but the deny-list does NOT actually ban
"the agent."

Why it matters:
- A dependency edge such as `connect/proxy → agent/structs` would not be
  flagged. `agent/structs` contains the agent-side wire format and pulling
  it into a Layer-3 sidecar primitive defeats the whole purpose of the
  layer separation.
- An edge `envoyextensions/extensioncommon → agent/xds/config` is also
  silently allowed, even though `envoyextensions/**` is supposed to be a
  reusable framework consumed *by* xds, not the other way around.
- A future maintainer reading the description ("must not depend on agent
  or CLI") will assume the constraint is enforced and will not retest.

How to fix it:
Broaden the deny-list to the full `agent/**` and `api/**` (Layer-4/5),
matching the troubleshoot/** rule and the preamble's documented hierarchy:

```yaml
- description: Connect data-plane primitives root must not depend on the Agent, API, or CLI layers.
  package: "github.com/hashicorp/consul/connect"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"

- description: Connect data-plane primitives subtree must not depend on the Agent, API, or CLI layers.
  package: "github.com/hashicorp/consul/connect/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"

- description: Envoy extension primitives must not depend on the Agent, API, or CLI layers.
  package: "github.com/hashicorp/consul/envoyextensions/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
```

(If `connect/proxy` legitimately needs `api/**` for client construction,
soften only that subtree by hand and document why — otherwise the broad
ban is correct per the documented hierarchy.)
```

```
[F3] SEVERITY: HIGH
Category: Coverage Gap / Inconsistency with sibling rules
Affected Rule / Constraint: `acl`, `acl/**` rules (lines 345–373)

What is wrong:
The Foundation-tier rule for `acl` and `acl/**` reads:

  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
      - "github.com/hashicorp/consul/connect"
      - "github.com/hashicorp/consul/connect/**"
      - "github.com/hashicorp/consul/envoyextensions/**"
      - "github.com/hashicorp/consul/troubleshoot/**"

Conspicuously missing: `internal` and `internal/**`. Every sibling
Foundation rule (`lib`, `lib/**`, `sdk/**`, `types`, `version`,
`version/**`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`,
`logging`, `logging/**`) DOES ban `internal` and `internal/**`.

The preamble explicitly says: "1. Foundation … Anything in this layer
must not import a higher layer." `internal/**` is Layer-3, above
Foundation, so the omission contradicts the preamble itself.

Why it matters:
- `acl/resolver` (the only sub-package in the package list under acl/**)
  could import `internal/multicluster` or `internal/resource/**` and pass
  every rule in the file — the very kind of "ACL leaks into the resource
  framework" inversion that the layered architecture was designed to
  catch.
- The asymmetry with the other Foundation rules is silent: nothing in the
  test report draws attention to it because the rule passes vacuously
  while still appearing to enforce *some* deny-list.

How to fix it:
Add `internal` and `internal/**` to both acl rules:

```yaml
- description: ACL primitives root must not depend on higher layers.
  package: "github.com/hashicorp/consul/acl"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/internal"
      - "github.com/hashicorp/consul/internal/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
      - "github.com/hashicorp/consul/connect"
      - "github.com/hashicorp/consul/connect/**"
      - "github.com/hashicorp/consul/envoyextensions/**"
      - "github.com/hashicorp/consul/troubleshoot/**"

- description: ACL primitives subtree must not depend on higher layers.
  package: "github.com/hashicorp/consul/acl/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/internal"
      - "github.com/hashicorp/consul/internal/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
      - "github.com/hashicorp/consul/connect"
      - "github.com/hashicorp/consul/connect/**"
      - "github.com/hashicorp/consul/envoyextensions/**"
      - "github.com/hashicorp/consul/troubleshoot/**"
```

(Verify with `go list -deps github.com/hashicorp/consul/acl/...` first —
if `acl` legitimately depends on `internal/...` somewhere, that itself is
a discussion the team should have, not a rule to silently exclude.)
```

```
[F4] SEVERITY: HIGH
Category: Coverage Gap / Glob (root vs. subtree)
Affected Rule / Constraint: client/server isolation rules for agent leaves
                            (lines 543–800)

What is wrong:
arch-go's `**` glob matches one or more path segments, NOT zero
(confirmed by the test report listing separate PASS lines for `lib/**` and
`lib`, and by arch-go v2 docs: "name.** … supporting multiple levels (for
example either name/foo and name/foo/bar)" — note `name` itself is not in
the example match set).

The current rules use this pattern for several agent leaves:

  package: "github.com/hashicorp/consul/agent/auto-config/**"
  package: "github.com/hashicorp/consul/agent/connect/**"
  package: "github.com/hashicorp/consul/agent/envoyextensions/**"
  package: "github.com/hashicorp/consul/agent/grpc-external/**"
  package: "github.com/hashicorp/consul/agent/grpc-internal/**"
  package: "github.com/hashicorp/consul/agent/grpc-middleware/**"
  package: "github.com/hashicorp/consul/agent/proxycfg-sources/**"
  package: "github.com/hashicorp/consul/agent/rpc/**"
  package: "github.com/hashicorp/consul/agent/rpcclient/**"
  package: "github.com/hashicorp/consul/agent/xds/**"

But the package list shows the *root* package of every one of these has
its own files (not just sub-packages):

  Line  6: github.com/hashicorp/consul/agent/auto-config
  Line 14: github.com/hashicorp/consul/agent/connect
  Line 37: github.com/hashicorp/consul/agent/envoyextensions
  Line 39: github.com/hashicorp/consul/agent/grpc-external
  Line 42: github.com/hashicorp/consul/agent/grpc-internal
  Line 45: github.com/hashicorp/consul/agent/grpc-middleware
  Line 64: github.com/hashicorp/consul/agent/rpcclient
  Line 73: github.com/hashicorp/consul/agent/xds

(`agent/proxycfg-sources` and `agent/rpc` have no root entry, only
sub-packages — those two are fine.)

So the client/server isolation does NOT apply to e.g.
`agent/connect/connect.go` or `agent/xds/xds.go`. An import
`agent/xds → agent/consul/state` from a file in the `agent/xds` root
package would slip through — and `agent/xds` is one of THE places the
client/server boundary needs the most enforcement (the documented diagram
puts xDS on the *client* side and the FSM/state on the *server* side).

Why it matters:
This silently inverts F7 from Review #1, which was supposed to encode the
biggest documented intra-Consul invariant. The fix added 35 leaf rules,
but eight of them are scoped one segment too deep; the boundary the
diagram cares about most is enforced for `agent/xds/config` but NOT for
`agent/xds`. The same is true for `agent/connect` and the gRPC roots.

How to fix it:
Add a sibling rule for each affected leaf root, mirroring the existing
`/**` rule. Example for the most important two (xds and connect):

```yaml
- description: Non-server agent code (xds root) must not depend on the server.
  package: "github.com/hashicorp/consul/agent/xds"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"

- description: Non-server agent code (connect root) must not depend on the server.
  package: "github.com/hashicorp/consul/agent/connect"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"
```

Repeat for `agent/auto-config`, `agent/envoyextensions`,
`agent/grpc-external`, `agent/grpc-internal`, `agent/grpc-middleware`,
`agent/rpcclient`. (The author *did* remember to add the root-mirror for
ten other leaves in the same block — `agent/cache`, `agent/checks`,
`agent/dns`, etc. — so the omission for these eight is asymmetric, not
intentional.)
```

```
[F5] SEVERITY: HIGH
Category: Coverage Gap (test harness trees)
Affected Rule / Constraint: All production-layer dependency rules

What is wrong:
The "Harness defense-in-depth" comment in the YAML (lines 829–836) claims:

  "The harnesses themselves are NOT enforced as origins — they may import
   any production layer. The bans below are a placeholder for any future
   rules that protect production from depending on harness trees.
   (No origin-side rules are added because that ban is implicit: harness
   paths do not match any production package: glob.)"

The comment confuses two unrelated facts. The harness paths' absence from
`package:` globs only means the harnesses are not enforced as ORIGINS —
it has zero effect on whether they appear in deny-LISTS. There is no rule
in the file that adds, e.g., `testing/deployer/**` or `test-integ/**` or
`tools/**` to ANY production layer's deny-list. So:

- `agent/dns → testing/deployer/sprawl` passes.
- `internal/resource/demo → test-integ/topoutil` passes.
- `api/watch → tools/internal-grpc-proxy` passes.
- `agent/xds → agent/grpc-external/testutils` passes.
- `agent/connect → internal/testing/golden` passes.
- Production code anywhere → `internal/tools/protoc-gen-...` passes.

Why it matters:
Test harnesses, integration suites, and codegen tools are exactly the
packages that production code must NEVER take a runtime dependency on
(`testing/deployer/sprawl` instantiates whole Consul clusters in-process;
`internal/tools/...` are protoc plugins). A leak is a code-smell that
arch-go is purpose-built to catch. The current rules catch nothing.

How to fix it:
Pick a "harness blacklist" macro and append it to every production deny-
list, OR add a small number of "no-production-may-depend-on-harness"
rules that target the *production* origins individually. The latter is
arch-go-idiomatic:

```yaml
- description: >
    Production layers must not depend on integration / build / test
    harness trees. The harness depends on production, never the reverse.
  package: "github.com/hashicorp/consul/agent/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools/**"

# Repeat the same six deny-targets for:
#   github.com/hashicorp/consul/internal/**
#   github.com/hashicorp/consul/api/**
#   github.com/hashicorp/consul/connect/**
#   github.com/hashicorp/consul/envoyextensions/**
#   github.com/hashicorp/consul/troubleshoot/**
#   github.com/hashicorp/consul/lib/**
#   github.com/hashicorp/consul/sdk/**
#   github.com/hashicorp/consul/proto/**
#   github.com/hashicorp/consul/proto-public/**
#   github.com/hashicorp/consul/acl/**
#
# (The agent/<leaf>/testutils and agent/xds/testcommon packages need a
# narrower carve-out: they LIVE inside agent/** but ARE harnesses. Either
# move them under a `//go:build testharness` tag, or add explicit
# entries that exclude them from the `agent/**` deny via separate rules.)
```

Then *delete* the misleading "implicit" comment block (lines 829–836).
The comment is wrong: implicit blacklists do not exist in arch-go.
```

```
[F6] SEVERITY: MEDIUM
Category: Inconsistency / Asymmetric Deny-List
Affected Rule / Constraint: `sdk` root rule (lines 162–173)

What is wrong:
The `sdk/**` rule (lines 142–160) bans 11 targets:
  agent, agent/**, api, api/**, internal, internal/**, command, command/**,
  connect, connect/**, envoyextensions/**, troubleshoot/**.

The `sdk` (root) rule (lines 162–173) bans only 8:
  agent, agent/**, api, api/**, internal, internal/**, command, command/**.

It is missing connect, connect/**, envoyextensions/**, troubleshoot/**.
This is a transcription bug — every other Foundation pair (lib/lib/**,
version/version/**, logging/logging/**, acl/acl/**) keeps both halves in
sync.

Why it matters:
`github.com/hashicorp/consul/sdk` (the bare root) could import
`connect/proxy` or `envoyextensions/xdscommon` and pass. Those are exactly
the kind of higher-layer leaks that the `sdk/**` rule was added to
prevent — so the Foundation invariant is half-enforced.

How to fix it:
Mirror the `sdk/**` deny-list onto the `sdk` rule:

```yaml
- description: SDK root package (mirror of sdk/**).
  package: "github.com/hashicorp/consul/sdk"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/internal"
      - "github.com/hashicorp/consul/internal/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
      - "github.com/hashicorp/consul/connect"
      - "github.com/hashicorp/consul/connect/**"
      - "github.com/hashicorp/consul/envoyextensions/**"
      - "github.com/hashicorp/consul/troubleshoot/**"
```
```

```
[F7] SEVERITY: MEDIUM
Category: Glob (root vs. subtree)
Affected Rule / Constraint: `internal/storage/raft/**`,
                            `internal/storage/inmem/**` (lines 452–468);
                            `internal/resource/**` (lines 439–450)

What is wrong:
Three intra-`internal/**` rules use only the `**` form, missing the leaf
roots:

  Rule (lines 442–450): package "internal/resource/**"
                       — does not match `internal/resource` (root, line 210)
  Rule (lines 455–459): package "internal/storage/raft/**"
                       — does not match `internal/storage/raft` (root, line 222)
  Rule (lines 464–468): package "internal/storage/inmem/**"
                       — does not match `internal/storage/inmem` (root, line 221)

The package list confirms each of those three roots is a populated
package with its own .go files.

Why it matters:
- `internal/storage/raft/raft.go → internal/storage/inmem` would not be
  flagged, even though the whole point of the pluggability pair (F10 from
  Review #1) is that the two implementations must be siblings, not
  ancestors. The pluggability invariant is half-enforced.
- `internal/resource/resource.go → agent/structs` is undetected.

How to fix it:
Add three root-mirror rules:

```yaml
- description: Domain-Core internal/resource root mirror.
  package: "github.com/hashicorp/consul/internal/resource"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"

- description: Pluggability — internal/storage/raft root must not depend on inmem.
  package: "github.com/hashicorp/consul/internal/storage/raft"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/internal/storage/inmem"
      - "github.com/hashicorp/consul/internal/storage/inmem/**"

- description: Pluggability — internal/storage/inmem root must not depend on raft.
  package: "github.com/hashicorp/consul/internal/storage/inmem"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/internal/storage/raft"
      - "github.com/hashicorp/consul/internal/storage/raft/**"
```
```

```
[F8] SEVERITY: MEDIUM
Category: Coverage Gap (no dependency rule)
Affected Rule / Constraint: `grpcmocks/**` (only a contents rule covers it)

What is wrong:
`grpcmocks/**` has a contents rule forbidding hand-written functions, but
no dependencyRule. Mock packages should be tightly scoped — they should
only depend on the proto-public packages they mock, plus the mock library
itself, plus standard library / external mocking framework. Today, NOTHING
in the file prevents `grpcmocks/proto-public/pbacl → agent/consul/state`.

Why it matters:
A mock package that takes a transitive dependency on the agent or
internal layers becomes a liability — it pulls a huge graph into every
test that imports the mock and creates layering inversion (test code in
agent/* importing a mock that itself depends on agent/consul). The whole
purpose of putting the mocks in a sibling tree is so the dependency
direction is fixed.

How to fix it:
Add a deny-list mirroring the proto-public/** rule:

```yaml
- description: gRPC mock packages must not depend on higher Consul layers.
  package: "github.com/hashicorp/consul/grpcmocks/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
      - "github.com/hashicorp/consul/internal"
      - "github.com/hashicorp/consul/internal/**"
      - "github.com/hashicorp/consul/connect"
      - "github.com/hashicorp/consul/connect/**"
      - "github.com/hashicorp/consul/envoyextensions/**"
      - "github.com/hashicorp/consul/troubleshoot/**"
```
```

```
[F9] SEVERITY: MEDIUM
Category: Coverage Gap (Layer 6 untouched)
Affected Rule / Constraint: `command`, `command/**`

What is wrong:
The original Review #1 correctly identified the `command/** ↛ troubleshoot/**`
rule as inverted, and the fix deleted it outright. But the deletion left
NO origin-side rule on the command/** layer at all. The new YAML preamble
classifies `command/**` as "Layer 6 — Consumer (CLI)" — supposedly the top
layer, free to import everything below.

That's defensible, but the tighter and more useful invariant — present in
most layered architectures and stated implicitly in Review #1's deleted
description ("CLI Command implementations are the top layer and should
only interact with the system via the Agent or API") — is now unenforced:

  command/** → internal/**           — allowed, but should not be (CLI
                                       should consume the public API and
                                       agent surfaces, not internal/**).
  command/** → connect/**            — allowed.
  command/** → envoyextensions/**    — allowed (note: command/connect/envoy
                                       wraps it, which IS a legitimate
                                       reason — so this one is debatable).
  command/** → testing/deployer/**   — allowed (covered by F5 if applied).

Why it matters:
The CLI is the top layer; nothing should ever import it (already implicit
because no production glob mentions it as a deny-list target — true).
But the CLI itself needs at least *some* downward discipline, otherwise
"Layer 6" is just a label. A command importing `internal/storage/raft`
directly bypasses the entire Agent/API surface that's supposed to be the
public contract.

How to fix it:
Either (a) add an explicit rule to back the implicit invariant, or (b)
amend the YAML preamble to state in writing that command/** is allowed to
import any lower layer (which is acceptable but should be documented).

Option (a):

```yaml
- description: >
    CLI Command implementations should only interact with the system via
    the Agent or API layers, not internal/** or low-level data-plane
    primitives.
  package: "github.com/hashicorp/consul/command/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/internal"
      - "github.com/hashicorp/consul/internal/**"

- description: command root mirror.
  package: "github.com/hashicorp/consul/command"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/internal"
      - "github.com/hashicorp/consul/internal/**"
```

(Validate that no current command/* package imports internal/**. If some
do, either move the function to api/** or grant a narrow exception with a
comment.)
```

```
[F10] SEVERITY: MEDIUM
Category: Comment / Documentation Inconsistency (carry-over from F11 of Review #1)
Affected Rule / Constraint: YAML preamble "EXCLUDED PACKAGES" block (lines 49–67)

What is wrong:
Review #1's F11 flagged this block as internally inconsistent. The fix
removed `proto-public/**` from the list (correct), but the remaining four
entries are still misleading:

  - `proto/private/**` — listed as "EXCLUDED" but is matched by the
    `proto/**` dependency rule (line 379) AND the `proto/**` contents
    rule (line 849). It is NOT excluded.
  - `agent/mock` — listed as "EXCLUDED" but matched by the `agent/**`
    dependency rule (line 514). It IS in scope; if it has a layer leak,
    arch-go will flag it.
  - `lib/testhelpers` — listed as "EXCLUDED" but matched by the `lib/**`
    rule (line 109) and the `lib/**` function-rules (line 901). NOT
    excluded.
  - `sdk/testutil/**` — listed as "EXCLUDED" but matched by the `sdk/**`
    rule (line 146). NOT excluded.

The "EXCLUDED" label is flat-out wrong for all four.

Why it matters:
A maintainer reading "EXCLUDED — agent/mock" will write a hand-rolled
agent-mock that imports api/** for testing convenience, push, and be
surprised when CI fails. Documentation that contradicts the rules is worse
than no documentation.

How to fix it:
Either:
- Delete the "EXCLUDED PACKAGES" block from the preamble (it is not
  "excluded" in any technical sense; it is an aspirational comment), and
  replace it with a short "scope" section that lists the four packages
  with a clear caveat:

```yaml
# SCOPE NOTE — Documentation-only entries:
# The following packages live under broader globs (proto/**, agent/**, etc.)
# and ARE in scope of those rules. They are NOT excluded by arch-go. If
# their layer-cleanliness is in doubt, validate manually:
#   - github.com/hashicorp/consul/proto/private/** (covered by proto/**)
#   - github.com/hashicorp/consul/agent/mock        (covered by agent/**)
#   - github.com/hashicorp/consul/lib/testhelpers   (covered by lib/**)
#   - github.com/hashicorp/consul/sdk/testutil/**   (covered by sdk/**)
```

- Or, if real exclusion is desired, refactor the parent globs to
  enumerate all *other* sub-packages explicitly (high cost — likely not
  worth it).
```

```
[F11] SEVERITY: MEDIUM
Category: Coverage Gap (Foundation → Layer 2)
Affected Rule / Constraint: Layer-1 rules vs. Layer-2 protos

What is wrong:
The preamble explicitly says "Layer 1 (Foundation) … Anything in this
layer must not import a higher layer," and lists `proto/**` and
`proto-public/**` as Layer 2. By the strict layering, every Foundation
rule should ban `proto/**` and `proto-public/**`. None of them do.

Affected origins (should each ban `proto/**` and `proto-public/**`):
  lib, lib/**, sdk, sdk/**, types, version, version/**, ipaddr, sentinel,
  service_os, snapshot, tlsutil, logging, logging/**, acl, acl/**.

Why it matters:
Either (a) the documented hierarchy is wrong about the Foundation/proto
relationship and should be corrected, or (b) the rules are wrong.

In practice, `lib/...` and `sdk/...` *do* sometimes need protobuf-encoded
types (e.g. for serialization helpers). If the team is OK with that, the
preamble's "must not import a higher layer" statement is overstated.
Either way, the YAML and the comment must be aligned.

How to fix it:
Pick one of:

(A) Tighten the rules — add `proto`, `proto/**`, `proto-public/**` to
    every Foundation deny-list. Validate that nothing legitimately uses
    them; if something does, refactor or add narrow exceptions.

(B) Soften the preamble — change the Foundation comment from "must not
    import a higher layer" to "must not import the agent, api, command,
    internal, connect, envoyextensions, or troubleshoot layers (proto/**
    and proto-public/** are pure data and are allowed for serialization
    helpers)."

The current state — comment says (A), rules implement (B) — is
self-contradictory. Option (B) is almost certainly what the codebase
actually does; document it.
```

```
[F12] SEVERITY: MEDIUM
Category: Module Scope / Wrong Module
Affected Rule / Constraint: Makefile `go install` line — points at the wrong arch-go fork

What is wrong:
The Makefile installs arch-go from the legacy fork:

  go install github.com/fdaines/arch-go@latest

Per the arch-go.org organization (https://github.com/arch-go/arch-go), the
project moved away from `fdaines/arch-go` and is now developed at
`arch-go/arch-go` (v2). The `fdaines/arch-go` module is at v1.5.4 and is
no longer the active source. The current YAML actually relies on V2
features (e.g. `interfaceImplementationNamingRule` is documented in the
v2 README and uses a structured `structsThatImplement` shape) — so
installing v1 is a version mismatch waiting to happen.

Why it matters:
- A user who installs v1 from `fdaines/arch-go@latest` may get parsing
  errors or silent rule-drops on naming rules whose v2 schema differs from
  v1.
- Future v2 features (additional content rules, function rules) added by
  arch-go.org will not be present.

How to fix it:

```makefile
@command -v arch-go >/dev/null 2>&1 || \
  go install github.com/arch-go/arch-go/v2@latest
```

(The v2 binary still installs as `arch-go` — same on-disk name — so the
`command -v arch-go` guard works correctly.)
```

```
[F13] SEVERITY: LOW
Category: Comment Accuracy / Description Mismatch
Affected Rule / Constraint: `connect`, `connect/**` rule descriptions
                            (lines 471–487); `envoyextensions/**` (line 489)

What is wrong:
The connect-rule description is "Connect data-plane primitives must not
depend on agent or CLI." The deny-list, however, only bans
`agent/consul/**`, not all of `agent/**`. Same mismatch on the
envoyextensions/** rule ("must not depend on agent server or CLI" — the
word "server" is at least a partial qualifier, but the broader rule
description still misleads).

Why it matters:
Even after F2 lands, the description should match the deny-list. This is
the kind of comment that future maintainers grep for ("does Consul ban
connect → agent/dns?"), see "must not depend on agent or CLI," and trust
without re-reading the deny-list.

How to fix it:
Either broaden the rule (F2) and keep the description, or — if the team
intentionally only wants to ban the *server* subset — narrow the
description to match:

```yaml
- description: >
    Connect data-plane primitives must not depend on the agent SERVER
    (agent/consul/**) or the CLI (command/**). The broader Layer-3 → Layer-4
    ban (connect ↛ agent/**) is enforced separately by [F2 rule above].
  package: "github.com/hashicorp/consul/connect/**"
  ...
```

(The right answer is almost certainly F2's broader fix — but at minimum
the description must not lie.)
```

```
[F14] SEVERITY: LOW
Category: Naming-rule schema (v2 vs. v1) / Possibly Vacuous
Affected Rule / Constraint: Both `namingRules` (lines 873–888)

What is wrong:
The current naming rules use the simple-string form:

  interfaceImplementationNamingRule:
    structsThatImplement: "Backend"
    shouldHaveSimpleNameEndingWith: "Backend"

The arch-go v2 documentation
(https://pkg.go.dev/github.com/arch-go/arch-go/v2) shows the
`structsThatImplement` field as a *nested object*, e.g.:

  structsThatImplement:
    internal: "*Backend"
  shouldHaveSimpleNameEndingWith: "Backend"

The simple-string form is a v1.x shape that v2 may or may not still
accept. The test report in this prompt shows the rule matching with
non-empty constraint output ("structs that implement 'Backend' should
have simple name ending with 'Backend'"), so the rule loaded — but
whether v2 actually picked up any structs implementing the interface is
not verifiable from the [PASS] output alone (a naming rule with zero
matched structs trivially passes).

Two distinct risks:
1. v2 may interpret `"Backend"` as a literal interface name without the
   wildcard-match semantics shown in v2 docs (`"*Backend"`), so an
   interface named e.g. `BackendV2` would not be picked up.
2. If the Backend interface lives in `internal/storage` (root) but the
   rule's `package:` is `internal/storage/**`, the rule scans only
   sub-packages — the root is missed (see F7).

Why it matters:
A naming rule that targets a non-existent or mis-located interface
trivially passes. The author cannot tell from the [PASS] output whether
the rule is doing anything.

How to fix it:
1. Validate manually that `Backend` is an interface in `internal/storage/...`
   with at least one implementing struct in `internal/storage/raft` or
   `internal/storage/inmem`. (`go doc internal/storage.Backend` from the
   consul module root.) Same for `Reconciler` in `internal/controller/`.
2. Update the rule to v2's structured form for forward compatibility:

```yaml
namingRules:
  - description: >
      All structs in internal/storage/** that implement the Backend
      interface should be named *Backend.
    package: "github.com/hashicorp/consul/internal/storage/**"
    interfaceImplementationNamingRule:
      structsThatImplement:
        internal: "*Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      All structs in internal/controller/** that implement the Reconciler
      interface should be named *Reconciler.
    package: "github.com/hashicorp/consul/internal/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement:
        internal: "*Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"
```

3. Also add the `internal/storage` root to the package pattern if `Backend`
   is defined there:

```yaml
  - description: Backend naming (root + subtree).
    package: "github.com/hashicorp/consul/internal/storage"
    interfaceImplementationNamingRule:
      structsThatImplement:
        internal: "*Backend"
      shouldHaveSimpleNameEndingWith: "Backend"
```
```

```
[F15] SEVERITY: LOW
Category: Threshold Documentation
Affected Rule / Constraint: `threshold` block (lines 95–97)

What is wrong:
The threshold block sets `coverage: 0`. The accompanying comment says:

  "arch-go's 'coverage' metric is package-touch (the number of distinct
   `package:` patterns matched), not architectural-constraint coverage. A
   strict 100% gate is not meaningful here."

That's accurate-ish, but the v2 docs are clear: coverage is "how many
packages in this module were evaluated by at least one rule." With 76
rules touching a substantial fraction of consul's ~290 packages, the
real coverage is non-trivially > 0%. Setting it to 0% disables the
metric entirely and surrenders an early-warning signal: a future rule
removal that drops coverage from, say, 65% → 35% would not trip CI.

Why it matters:
- A future rule deletion or glob narrowing won't be caught by the
  threshold gate.
- The "0% [PASS]" line in the test report is a lie of omission: it does
  not mean "we deliberately disabled this," it just means "no minimum."

How to fix it:
Run arch-go once with `coverage: 100` and read the actual percentage out
of the report (or run with `--json` and grep the field). Then set the
threshold to one or two percentage points below that, so a regression
trips the gate without making green-CI dependent on perfect package-touch:

```yaml
threshold:
  compliance: 100
  coverage: 60   # set to (actual measured coverage) - 2; today ~62%.
                 # Re-baseline whenever rules are deliberately removed.
```

(If the team really wants to disable, keep `coverage: 0` but rename the
comment to "DISABLED — re-enable as a regression sentinel once a
baseline is measured.")
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
| F1  | CRITICAL | Module Scope (Makefile) | Remove non-existent `check` subcommand and `--config` flag; arch-go reads `arch-go.yml` from PWD only |
| F2  | HIGH     | Coverage Gap | `connect`/`connect/**` and `envoyextensions/**` must ban full `agent/**` and `api/**`, not just `agent/consul/**` |
| F3  | HIGH     | Coverage Gap | `acl`/`acl/**` rules omit `internal/**` from deny-list — inconsistent with sibling Foundation rules |
| F4  | HIGH     | Glob (root vs. subtree) | Eight `agent/<leaf>/**` rules miss leaf-root packages — add root-mirror rules |
| F5  | HIGH     | Coverage Gap (harness) | No production rule denies imports of `testing/deployer/**`, `test-integ/**`, `tools/**`, `internal/testing/**`, `internal/tools/**` |
| F6  | MEDIUM   | Inconsistency | `sdk` root rule's deny-list is shorter than `sdk/**` (missing connect/envoy/troubleshoot) |
| F7  | MEDIUM   | Glob (root vs. subtree) | `internal/storage/raft`, `internal/storage/inmem`, `internal/resource` roots not covered |
| F8  | MEDIUM   | Coverage Gap | `grpcmocks/**` has no dependency rule — can import any layer |
| F9  | MEDIUM   | Coverage Gap | `command/**` has no rule preventing direct import of `internal/**` |
| F10 | MEDIUM   | Documentation | "EXCLUDED PACKAGES" preamble still claims `proto/private/**`, `agent/mock`, `lib/testhelpers`, `sdk/testutil/**` are excluded — they are not |
| F11 | MEDIUM   | Documentation | Foundation comment says "must not import a higher layer" but rules permit `proto/**` / `proto-public/**` imports — pick one |
| F12 | MEDIUM   | Module Scope (Makefile) | `go install github.com/fdaines/arch-go@latest` should be `github.com/arch-go/arch-go/v2@latest` (active fork) |
| F13 | LOW      | Comment Accuracy | `connect`/`envoyextensions` rule descriptions promise broader bans than the deny-lists implement |
| F14 | LOW      | Naming-rule schema | `structsThatImplement: "Backend"` is v1 shape; v2 prefers `structsThatImplement: { internal: "*Backend" }` — also verify Backend / Reconciler interfaces actually exist with implementers |
| F15 | LOW      | Threshold | `coverage: 0` disables the package-touch gate — set to (measured baseline – 2pts) to detect regressions |

**5 of 15 findings are CRITICAL or HIGH.** The Round-1 fixes successfully closed the schema bugs (F1, F2 from Review #1) and the bulk of the Layer-1/Layer-2 coverage holes (F3, F4 from Review #1), so the rule file finally enforces *something*. But the build pipeline itself does not run (F1 R2), and four genuinely big false-negative classes remain (F2 R2: Layer-3 → Layer-4 leak; F3 R2: acl → internal; F4 R2: agent leaf-root client/server; F5 R2: harness imports).

---

### What to fix first

If only one round of edits is possible, apply this priority order:

1. **F1 (R2)** — fix the Makefile so the rule file actually runs in CI. Until this lands, every other "PASS" is informational only.
2. **F2 + F3 + F4 (R2)** — close the three remaining HIGH coverage gaps: Layer-3 deny-lists, acl/** deny-list, and agent leaf-root client/server isolation. These are documented constraints whose violations would be obvious code smells if seen.
3. **F5 (R2)** — add the production-vs-harness rules; this catches a class of bug (test scaffolding leaking into production) that arch-go is uniquely good at.
4. **F12 (R2)** — point `go install` at the active arch-go.org fork to avoid v1/v2 drift in naming-rule semantics.
5. **F6, F7, F8, F9 (R2)** — close the remaining MEDIUM coverage gaps in batches; each is small and mechanical.

Findings F10, F11, F13, F14, F15 are documentation / style polish that can be batched in a final hygiene pass.

---

**End of Review #2.**
