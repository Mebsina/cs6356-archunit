# Adversarial Review #4 — `outputs/hashicorp_consul/gemini3-flash/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: gemini3-flash
Project: HashiCorp Consul
Round: 4 (after fixes from Review #3)

---

## Executive Summary

- **Total documented constraints identified:** ~24 (counting peer-isolation pairs and harness-target classes individually):
  1. Foundation (`lib`, `sdk`, `types`, `version`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging`, `acl` — both root and `**`) ↛ Layers 3/4/5/6
  2. Foundation **may** depend on Layer-2 protos (softer-than-strict layering — preamble lines 14–18)
  3. Layer 2 (`proto/**`, `proto-public/**`) ↛ higher layers
  4. `internal/**` (Layer 3) ↛ `agent`/`api`/`command`
  5. `internal/storage/raft` ↮ `internal/storage/inmem` (mutual ban — pluggability)
  6. Layer-3 peers `connect`, `connect/**` ↛ `agent`/`api`/`command`
  7. Layer-3 peer `envoyextensions/**` ↛ `agent`/`api`/`command`
  8. Layer-3 peer `troubleshoot/**` ↛ `agent`/`command` (note: api intentionally absent — flagged in F4)
  9. Layer 4 `agent`, `agent/**` ↛ `api`/`command`
  10. Intra-agent client/server split: non-server `agent/<leaf>` ↛ `agent/consul/**` (consolidated to one glob in Round 3)
  11. Layer 5 `api`, `api/**` ↛ `internal`/`agent`/`command`
  12. Layer 6 `command`, `command/**` ↛ `internal/**`
  13. Production code (every layer) ↛ harness trees (`testing/deployer/**`, `test-integ/**`, `testrpc`, `tools/**`, `internal/testing/**`, `internal/tools/**`)
  14. Production code ↛ in-tree harness packages (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`)
  15. Mock packages `grpcmocks/**` ↛ all production layers
  16. Mock packages `grpcmocks/**` ↛ harness trees
  17. **(Newly identified, F1)** Production code ↛ `grpcmocks/**` — production must not depend on mocks
  18. **(Newly identified, F2)** Production code ↛ in-tree test helpers (`lib/testhelpers`, `sdk/testutil/**`, `internal/controller/controllermock`, `internal/controller/controllertest`, `internal/resource/resourcetest`, `internal/storage/conformance`, `internal/protohcl/testproto`, `proto/private/prototest`, `version/versiontest`)
  19. Naming — Backend implementers should be named `*Backend`
  20. Naming — Reconciler implementers should be named `*Reconciler`
  21. Contents — `proto/**`, `proto-public/**`, `grpcmocks/**` should not declare hand-written interfaces
  22. Function-rule budgets for `internal/**` (`maxLines`, `maxParameters`)
  23. Function-rule budgets for `lib/**` (`maxReturnValues`)
  24. (Implied) Threshold gate `compliance: 100`

- **Total rules generated:** 66 evaluated rules per the test report (58 dependency + 3 contents + 3 naming + 2 functions). The YAML file declares ~73 rules — minor difference between physical YAML entries and evaluated lines, likely due to glob deduplication by arch-go.

- **Effectively enforcing rules:** 66 of 66 — every rule the YAML declares is loaded and produces `[PASS]` against the current Consul codebase. The Round-3 fix to revert `structsThatImplement` to flat-string form successfully unblocked the parser (Review #3 F1 closed). The Makefile hardening (v2 binary check, refuse-to-clobber, `trap`-based cleanup, `Compliance:` sentinel grep) successfully closes Review #3 F8 / F9.

- **Coverage rate (semantic):** 22 / 24 documented constraints have a corresponding rule in source. The two newly identified gaps are F1 (production ↛ `grpcmocks/**`) and F2 (production ↛ in-tree test helpers).

### Critical Gaps (open in Round 4)

1. **HIGH — Production code can freely import `grpcmocks/**`.** The mock tree appears as the *origin* (`package:` glob) in three rules but is never enumerated as a *target* in any deny-list. So `agent/dns → grpcmocks/proto-public/pbdns`, `api → grpcmocks/proto-public/pbresource`, and `command/operator → grpcmocks/proto-public/pbserverdiscovery` all pass — pulling mockery-generated test scaffolding into release binaries.

2. **HIGH — Eight in-tree test-helper packages are not enumerated as deny targets on any production rule.** The Round-3 fix correctly named the *agent-rooted* helpers (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`) but missed the *parallel* helpers in every other tree: `lib/testhelpers`, `sdk/testutil`, `sdk/testutil/retry`, `internal/controller/controllermock`, `internal/controller/controllertest`, `internal/resource/resourcetest`, `internal/storage/conformance`, `internal/protohcl/testproto`, `proto/private/prototest`, `version/versiontest`. Production agent / api / command / internal / connect / envoy / troubleshoot code may freely import every one of these.

3. **MEDIUM — The Round-3 consolidation of the intra-agent client/server split rests on an unverified arch-go semantic.** The new single-rule form (`agent/**` ↛ `agent/consul/**`) lists `agent/consul/**` as a deny target while *also* matching `agent/consul/**` as origins. The YAML comment (lines 626–628) and `fix-history.md` Round-3 entry both claim "arch-go does not flag a package's dependency on its own subtree" — that claim is not part of arch-go's documented behavior, and a future intra-server import (e.g. `agent/consul/fsm → agent/consul/state`) would, by standard arch-go matching semantics, fire the rule and break CI.

4. **MEDIUM — `troubleshoot/**` is the only Layer-3 peer that does not deny `api`/`api/**`.** `connect`, `connect/**`, and `envoyextensions/**` all deny the api layer; `troubleshoot/**` denies only `agent` and `command`. Either the asymmetry is documentation-and-rule consistent (current state) or it is a real gap; the architecture preamble's "Layer-3 peer" classification implies the latter.

5. **MEDIUM — Two layer rules target packages that don't exist.** `package: "github.com/hashicorp/consul/sdk"` (line 200) and `package: "github.com/hashicorp/consul/internal"` (line 474) are root rules for trees whose roots are unpopulated in `1_hashicorp_consul.txt` — there is no `sdk` or `internal` package, only their `**` subtrees. Both rules pass vacuously.

### Overall verdict: **`PASS WITH WARNINGS`**

Round 3 closed every CRITICAL and HIGH finding from Round 2 / Round 3 (the parse failure is gone, the harness defense covers `command/**`, the populated root packages are now mirrored, and the in-tree agent harness packages are deny targets). The remaining defects are residual coverage gaps — two HIGH (F1, F2 — production importing mocks and in-tree test helpers), three MEDIUM (F3, F4, F5), and four LOW polish items (F6–F9). None are catastrophic; the file is now a useful CI gate, but the "100% PASS" is misleading because the gaps below would not produce a violation today even if they existed in code.

---

## Findings

```
[F1] SEVERITY: HIGH
Category: Coverage Gap (production ↛ mocks)
Affected Rule / Constraint: grpcmocks/** as deny target — missing on every
                            production layer (agent, api, internal,
                            command, connect, envoyextensions, troubleshoot,
                            proto, proto-public, lib, sdk, acl).

What is wrong:
`grpcmocks/**` appears as the *origin* glob in three rules
(lines 685, 1070, 1132 — layer-deny, harness-deny, contents) but it
is never enumerated as a *target* in any `shouldNotDependsOn` block.
Concretely, `grep -n "consul/grpcmocks" arch-go.yml` returns three
hits — all on `package:` lines, none on deny-list entries.

The grpcmocks tree contains six packages (lines 190–195 of
1_hashicorp_consul.txt):
  - grpcmocks/proto-public/pbacl
  - grpcmocks/proto-public/pbconnectca
  - grpcmocks/proto-public/pbdataplane
  - grpcmocks/proto-public/pbdns
  - grpcmocks/proto-public/pbresource
  - grpcmocks/proto-public/pbserverdiscovery

Each is mockery-generated test scaffolding. Production code (agent,
api, command, internal, etc.) must not depend on them at compile
time, otherwise the mocks are pulled into the shipped binary.

Today's PASS rules permit:
  - agent/grpc-external      → grpcmocks/proto-public/pbacl
  - agent/rpc/peering        → grpcmocks/proto-public/pbserverdiscovery
  - api                      → grpcmocks/proto-public/pbresource
  - command/operator         → grpcmocks/proto-public/pbdns
  - internal/multicluster    → grpcmocks/proto-public/pbresource
  - command/connect/envoy    → grpcmocks/proto-public/pbdataplane

All six edges silently pass.

Why it matters:
This is the same shape of leak as Review #3 F5 (in-tree agent
harness packages were not deny targets). Round 3 closed the
agent-rooted harnesses; the symmetric class for the mock tree was
not addressed. Mocks are a strictly-test-time concern; pulling
them into a release binary inflates the binary, registers
test-only gRPC handlers at process start (via `init()`), and
silently tightens the production build's coupling to mockery's
generated API surface — a refactor that drops a mock method then
breaks production.

How to fix it:
Add `grpcmocks` and `grpcmocks/**` as deny targets on every
non-test production layer rule. The cleanest patch is one new
rule per layer (mirroring the harness defense-in-depth block).
Below is a single concentrated rule plus the additions for the
remaining production layers:

```yaml
# ----- Production code must not depend on the mock tree -----
- description: >
    Production layers must not depend on grpcmocks/**. Mock packages
    are mockery-generated test scaffolding; pulling them into a
    release binary inflates the binary and registers test-only
    handlers at process start.
  package: "github.com/hashicorp/consul/agent/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

- description: agent root (mirror) must not depend on grpcmocks/**.
  package: "github.com/hashicorp/consul/agent"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

- description: api/** must not depend on grpcmocks/**.
  package: "github.com/hashicorp/consul/api/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

- description: api root (mirror) must not depend on grpcmocks/**.
  package: "github.com/hashicorp/consul/api"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

- description: command/** must not depend on grpcmocks/**.
  package: "github.com/hashicorp/consul/command/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

- description: command root (mirror) must not depend on grpcmocks/**.
  package: "github.com/hashicorp/consul/command"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

- description: internal/** must not depend on grpcmocks/**.
  package: "github.com/hashicorp/consul/internal/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/grpcmocks"
      - "github.com/hashicorp/consul/grpcmocks/**"

# (Repeat the same payload for connect/connect**, envoyextensions/**,
# troubleshoot/**, lib/lib**, sdk/**, acl/acl**, proto/**, proto-public/**.)
```

A simpler one-rule encoding is also acceptable — extend each
existing harness-deny block to include `grpcmocks` and
`grpcmocks/**` as targets. The deny-list layouts are identical;
adding two lines per existing harness rule is the lowest-churn
patch.
```

```
[F2] SEVERITY: HIGH
Category: Coverage Gap (in-tree test helpers outside agent/**)
Affected Rule / Constraint: harness defense-in-depth block — every
                            production layer rule.

What is wrong:
Round 3 enumerated the five agent-rooted harness packages
(`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`,
`agent/xds/testcommon`, `agent/xds/validateupstream-test`,
`agent/mock`) on every harness rule that could leak them. The
fix correctly closed Review #3 F5 for the agent tree.

But the *parallel* class — in-tree test helpers that live OUTSIDE
agent/** — was not addressed. `grep` against the package list
shows nine such packages (lines from 1_hashicorp_consul.txt):

  Line 198: github.com/hashicorp/consul/internal/controller/controllermock
  Line 199: github.com/hashicorp/consul/internal/controller/controllertest
  Line 207: github.com/hashicorp/consul/internal/protohcl/testproto
  Line 217: github.com/hashicorp/consul/internal/resource/resourcetest
  Line 220: github.com/hashicorp/consul/internal/storage/conformance
  Line 241: github.com/hashicorp/consul/lib/testhelpers
  Line 258: github.com/hashicorp/consul/proto/private/prototest
  Line 269: github.com/hashicorp/consul/sdk/testutil
  Line 270: github.com/hashicorp/consul/sdk/testutil/retry
  Line 290: github.com/hashicorp/consul/version/versiontest

Each is named with an unambiguous test-/conformance-/mock-/golden-
helper suffix. None of them appears as a deny target anywhere in
the YAML. Production code that imports them silently passes:

  - agent/dns          → lib/testhelpers                     (PASS)
  - api                → sdk/testutil                        (PASS)
  - command/operator   → sdk/testutil/retry                  (PASS)
  - agent/proxycfg     → internal/resource/resourcetest      (PASS)
  - agent/consul/state → internal/storage/conformance        (PASS)
  - internal/multicluster → internal/controller/controllermock (PASS)
  - command/version    → version/versiontest                 (PASS)

The YAML preamble at lines 66–67 ALREADY identifies two of these
(`sdk/testutil/**`, `lib/testhelpers`) as "documentation-only
entries covered by parent globs." That comment is correct about
*origin* coverage but says nothing about *target* coverage. As
deny targets they are absent from every rule.

Why it matters:
Production code importing a `*test*` / `*conformance*` /
`*mock*` package pulls test-only types and helpers into the
release binary, the same supply-chain / binary-size concern as
the agent in-tree harnesses. The diagram does not differentiate
between "agent-rooted" and "non-agent-rooted" harness packages;
the architectural rule is identical.

How to fix it:
Treat the nine packages above as additional deny targets on
every production-layer harness rule (adding them to the same
blocks that already list the agent-rooted harnesses). To avoid
duplicating a 23-line list eleven times, a YAML-anchor pattern is
the clean encoding — but if anchors are off-limits, just append:

```yaml
# Append to every existing harness-deny rule (agent, api,
# command, connect, envoyextensions, troubleshoot, lib, sdk,
# proto, proto-public, acl, internal — and the matching root
# mirrors).
- "github.com/hashicorp/consul/lib/testhelpers"
- "github.com/hashicorp/consul/sdk/testutil"
- "github.com/hashicorp/consul/sdk/testutil/**"
- "github.com/hashicorp/consul/internal/controller/controllermock"
- "github.com/hashicorp/consul/internal/controller/controllertest"
- "github.com/hashicorp/consul/internal/resource/resourcetest"
- "github.com/hashicorp/consul/internal/storage/conformance"
- "github.com/hashicorp/consul/internal/protohcl/testproto"
- "github.com/hashicorp/consul/proto/private/prototest"
- "github.com/hashicorp/consul/version/versiontest"
```

NOTE on self-overlap: when added to the harness-deny rule for
`internal/**` (origin), `internal/controller/controllermock`
matches both origin and target. By the same arch-go semantic the
Round-3 consolidation relies on (F3 — see below for caveats),
this is supposed to "trivially comply." For correctness, the
safer encoding is to add these targets only to the rules whose
origin glob does NOT overlap (e.g. on the agent/**, api/**,
command/**, connect/**, envoyextensions/**, troubleshoot/**, lib,
sdk, proto/**, proto-public/**, acl rules), and to leave the
internal/** ↛ internal/testing/internal/tools harness gap to F6
below.
```

```
[F3] SEVERITY: MEDIUM
Category: Vacuous Rule / Semantic Risk
Affected Rule / Constraint: Consolidated intra-agent client/server rule
                            (lines 630–648) and the supporting comment
                            at lines 624–629.

What is wrong:
The Round-3 consolidation replaced 35+ per-leaf rules with a
single `agent/**` rule whose deny list is `agent/consul`,
`agent/consul/**`. Crucially, the SAME `agent/**` glob ALSO
matches the `agent/consul/<sub>` packages as ORIGINS — so the
rule reads, for origin `agent/consul/state`, "you must not depend
on `agent/consul/**`."

The YAML comment defends this with:

  # arch-go does not flag a package's dependency on its own
  # subtree, so `agent/consul/**` as an origin is matched here
  # but trivially complies (it cannot import itself).

That claim is not part of arch-go's documented behavior. arch-go
evaluates each origin's imports against the deny-list pattern
set; an origin `agent/consul/state` whose imports include
`agent/consul/fsm` (a sibling, NOT the same package) WOULD match
the `agent/consul/**` deny target. The "a package never depends
on itself" semantic only excludes the exact-equality case
(`agent/consul/state → agent/consul/state`), which is impossible
in Go anyway.

The test report PASSES today; that is empirical evidence about
the *current* code (Consul's `agent/consul/<sub>` packages
apparently do not cross-import in a way that would trigger this
rule, OR they do but arch-go's matcher has an implementation
detail not visible from documentation). Either way, the
consolidation introduced a hidden landmine: a future PR that
makes `agent/consul/fsm` import `agent/consul/state` is, under
standard arch-go semantics, a violation — even though that
edge is intra-server and architecturally fine.

The 35+ per-leaf rules the consolidation replaced did NOT have
this overreach: they listed origin = `agent/<leaf>` for each
non-server leaf, so `agent/consul/<sub>` was never an origin and
intra-server cross-imports were never evaluated.

Why it matters:
This is a tradeoff that was not surfaced as such. The Round-3
fix-history calls the consolidation a maintainability win
("adding a new agent leaf no longer requires writing a new rule")
— that benefit is real, but it comes at the cost of fragility
to a future intra-`agent/consul/**` cross-import. Neither the
preamble nor the comment block warns the reader.

There are two fix options that recover the original semantics:

How to fix it:
Option A — keep the consolidated form but verify and document
the arch-go semantic empirically. Add a representative test case
to a CI script (artificially introduce an `agent/consul/state →
agent/consul/fsm` import in a throwaway branch and confirm
arch-go does NOT flag it). If arch-go flags it, revert to
Option B; if it does not, codify the actual semantic in a
comment with a reference to the arch-go source.

Option B — restore origin-side specificity by enumerating the
non-server agent leaves at the GLOB level (not per-leaf), via
arch-go's lack of support for "exclude" patterns notwithstanding.
Two clean encodings:

```yaml
# Option B.1 — list the four populated agent/<top-level> leaves
# explicitly so agent/consul/** is never an origin.
- description: >
    Non-server agent code (the client side of the architecture
    diagram) must not depend on the agent/consul/** server
    subtree. This rule enumerates the populated origin leaves so
    that intra-agent/consul/** imports are out of scope.
  package: "github.com/hashicorp/consul/agent/proxycfg/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent/consul"
      - "github.com/hashicorp/consul/agent/consul/**"
# (Repeat for agent/dns, agent/checks, agent/xds/**,
# agent/grpc-external/**, agent/rpc/**, etc. — i.e. the original
# Round-2 per-leaf list. Maintainability cost is real but the
# correctness is unambiguous.)

# Option B.2 — accept the consolidation as the production rule
# but add an explicit note that the SHOULD-FLAG case
# (agent/consul/<sub> → agent/consul/<sib>) is currently masked
# by an arch-go implementation detail, and a future arch-go
# release may surface it. Track via a CI flag that runs both
# rule sets and diffs results.
```

If the team chooses to keep the Round-3 consolidation, the
minimum required change is to fix the comment so a future reader
does not believe arch-go has a special "subtree self-skip" rule.
Replace lines 626–628 with:

```yaml
  # arch-go evaluates each origin's imports against the deny-list
  # patterns; an agent/consul/<sub> package whose imports include
  # another agent/consul/<sib> package WOULD, by standard
  # semantics, be flagged. The empirical evidence today is that
  # agent/consul/<sub> packages do not cross-import in a way that
  # triggers this rule, but a future intra-server import may
  # surface a false positive against this consolidated rule. If
  # that happens, revert to per-leaf origin rules — see Round-2's
  # 35+ leaf entries.
```
```

```
[F4] SEVERITY: MEDIUM
Category: Coverage Gap (Layer-3 peer asymmetric with sibling rules)
Affected Rule / Constraint: troubleshoot/** dependency rule
                            (lines 585–594).

What is wrong:
The three Layer-3 peer rules (connect/**, envoyextensions/**,
troubleshoot/**) all encode the same architectural invariant:
"Layer-3 must not depend on Layer-4 (agent), Layer-5 (api), or
Layer-6 (command)."  But the deny-lists are not identical:

  connect/**          → bans agent/**, api/**, command/**
  envoyextensions/**  → bans agent/**, api/**, command/**
  troubleshoot/**     → bans agent/**,  ___ ,  command/** (api missing)

Lines 585–594 of the YAML:

  - description: >
      Troubleshoot library must not depend on agent or CLI...
    package: "github.com/hashicorp/consul/troubleshoot/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent"
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command"
        - "github.com/hashicorp/consul/command/**"

The description hand-waves the asymmetry by listing only "agent
or CLI" — but the YAML preamble (lines 35–40) explicitly groups
troubleshoot/** with connect and envoyextensions as Layer-3
peers, and the layered hierarchy says NO Layer-3 peer may import
Layer-5 (api).

Today this lets:
  - troubleshoot/proxy   → api/watch        (PASS)
  - troubleshoot/ports   → api              (PASS)
  - troubleshoot/validate → api/watch       (PASS)

If the asymmetry is intentional (i.e. troubleshoot legitimately
calls into the public api/** for service lookups), the rule
should be made consistent by tightening the description AND the
preamble to call out troubleshoot's special status. Otherwise
this is an enforcement gap.

Why it matters:
The architecture diagram is a strict layered model; the preamble
classifies troubleshoot as a Layer-3 peer. Either one of the
rules or the preamble is wrong. Today the rule says "troubleshoot
may depend on api/**" while the preamble says "no Layer-3 peer
may depend on a higher layer." A reader who trusts the preamble
would assume troubleshoot/proxy → api is forbidden; the YAML
silently allows it.

How to fix it:
If the architectural intent is strict isolation (most likely):

```yaml
- description: >
    Layer-3 peer troubleshoot/** must not depend on Layer-4
    (agent), Layer-5 (api), or Layer-6 (command). The natural
    direction is command/troubleshoot/* → troubleshoot/* and
    agent/proxycfg → troubleshoot/*, never the reverse.
  package: "github.com/hashicorp/consul/troubleshoot/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/agent"
      - "github.com/hashicorp/consul/agent/**"
      - "github.com/hashicorp/consul/api"
      - "github.com/hashicorp/consul/api/**"
      - "github.com/hashicorp/consul/command"
      - "github.com/hashicorp/consul/command/**"
```

If troubleshoot DOES need api (verify via `go list -deps`), the
fix is in the preamble — call out troubleshoot's exception
explicitly so the asymmetry is documented:

```yaml
# 3. Domain Core (Internal): ...
#    Cross-cutting peers at this layer also include:
#    - github.com/hashicorp/consul/connect, connect/**
#    - github.com/hashicorp/consul/envoyextensions/**
#    - github.com/hashicorp/consul/troubleshoot/**     (* api allowed
#                                                       — see below)
#
# Exception: troubleshoot/** is permitted to depend on api/** as
# a public-Go-client consumer (it makes Consul API calls to
# inspect proxy and gateway state). All OTHER Layer-3 peers
# observe the strict "no higher layer" rule.
```

Pick one direction. The current state — strict preamble,
permissive rule, mismatched description — is the worst of both
worlds for a future maintainer.
```

```
[F5] SEVERITY: MEDIUM
Category: Vacuous Rule
Affected Rule / Constraint: Two layer rules whose package: target does
                            not exist as a Go package in the module.

What is wrong:
The package list `1_hashicorp_consul.txt` does NOT contain a
top-level `github.com/hashicorp/consul/sdk` or
`github.com/hashicorp/consul/internal` line — only their
subtrees (sdk/freeport, sdk/iptables, sdk/testutil/...,
internal/controller/..., internal/storage/..., etc.).

But the YAML declares two root-mirror rules whose `package:`
glob targets exactly those nonexistent root packages:

  Line 200:  package: "github.com/hashicorp/consul/sdk"      # vacuous
  Line 474:  package: "github.com/hashicorp/consul/internal" # vacuous

Both rules pass with zero evaluation work because no Go package
matches the origin glob. The test report shows both rules
producing `[PASS]`:

  [PASS] - Packages matching pattern '...consul/sdk' should
           ['not depend on internal packages that matches [[...]]']
  [PASS] - Packages matching pattern '...consul/internal' should
           ['not depend on internal packages that matches [[...]]']

The PASS is meaningless — there is nothing to check.

Why it matters:
A vacuous rule looks like coverage in the report and in
fix-history.md. Future readers counting rules will conclude
"sdk root and internal root are independently constrained" when
in fact they are not. If a future contributor adds Go files to
`sdk/sdk.go` or `internal/internal.go`, the rules will suddenly
start producing results — but until then they are dead code.

Note: this is the *layer-deny* mirror rule, not the harness-deny
mirror. The harness-deny rules correctly omit `sdk` and
`internal` as roots (they only mirror agent, api, connect, lib,
acl) because the Round-3 review specifically verified those five
have populated root packages. The layer-deny block was not
audited the same way and accidentally introduced two more
mirrors than the package list supports.

How to fix it:
Delete both rules:

```yaml
# DELETE lines 199–214 (sdk root mirror) — sdk is not a populated
# package. The sdk/** rule already covers every actual package.

# DELETE lines 473–482 (internal root mirror) — internal is not
# a populated package. The internal/** rule already covers
# every actual package.
```

Alternatively, gate them behind a "this is a forward-compatibility
safety net" comment block so the vacuous-pass is documented (the
same treatment the Round-3 fix gave the internal/storage root
naming rule, lines 1149–1158).
```

```
[F6] SEVERITY: MEDIUM
Category: Coverage Gap (intra-internal harness)
Affected Rule / Constraint: internal/** harness-deny rule
                            (lines 803–818).

What is wrong:
Every other production layer's harness-deny rule (api/**, lib/**,
command/**, connect/**, etc.) lists `internal/testing/**` and
`internal/tools/**` as deny targets. The `internal/**` rule does
NOT — it lists `testing/deployer/**`, `test-integ/**`, `testrpc`,
`tools/**`, and the five agent in-tree harnesses, but it omits
the two `internal/`-rooted harness trees.

This is intentional — origin `internal/**` overlaps with target
`internal/testing/**` and `internal/tools/**`, so adding them
would, by the same Round-3 reasoning that drove F3, create a
self-overlap on `internal/testing/golden → internal/testing/errors`
or `internal/tools/protoc-gen-grpc-clone → ...`. The Round-3
authors quite reasonably left them off rather than risk a false
positive.

But the omission means production internal code can freely
import internal-rooted test harnesses:

  - internal/multicluster        → internal/testing/golden    (PASS)
  - internal/controller          → internal/testing/errors    (PASS)
  - internal/resource/reaper     → internal/tools/protoc-gen-consul-rate-limit (PASS)
  - internal/storage/raft        → internal/testing/golden    (PASS)

`internal/testing/golden` provides golden-file test helpers, and
`internal/tools/...` are protoc plugins. Production code should
not import either at runtime.

Why it matters:
The same pattern the Round-3 review caught for `agent/**`
(in-tree harnesses living under the production glob) recurs for
`internal/**`. Round 3 fixed the agent variant by enumerating
`agent/grpc-external/testutils` etc. as deny targets and
relying on arch-go's "self-import skip" semantic — exactly the
same mechanism that F3 above warns is unverified.

How to fix it:
There are two options; pick based on the F3 verdict:

Option A (matches F3 Option A — assume arch-go's self-overlap
semantic works): just add the two targets:

```yaml
- description: >
    Internal/** must not depend on harness trees, including the
    internal-rooted harness packages internal/testing/** and
    internal/tools/**. Origin/target overlap is intentional — a
    package never imports itself, and an internal/<X> ↛
    internal/testing/<Y> edge IS a real violation that should be
    surfaced.
  package: "github.com/hashicorp/consul/internal/**"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/testing/deployer"
      - "github.com/hashicorp/consul/testing/deployer/**"
      - "github.com/hashicorp/consul/test-integ"
      - "github.com/hashicorp/consul/test-integ/**"
      - "github.com/hashicorp/consul/testrpc"
      - "github.com/hashicorp/consul/tools"
      - "github.com/hashicorp/consul/tools/**"
      - "github.com/hashicorp/consul/internal/testing"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools"
      - "github.com/hashicorp/consul/internal/tools/**"
      - "github.com/hashicorp/consul/agent/grpc-external/testutils"
      - "github.com/hashicorp/consul/agent/grpc-middleware/testutil"
      - "github.com/hashicorp/consul/agent/xds/testcommon"
      - "github.com/hashicorp/consul/agent/xds/validateupstream-test"
      - "github.com/hashicorp/consul/agent/mock"
```

Option B (avoid relying on the unverified semantic): add a more
specific origin-side rule that excludes the harness origins:

```yaml
# Layer-deny + harness-deny variant scoped to non-harness internal/**.
# Note: arch-go has no native "exclude-origin" syntax, so this is
# encoded by listing the populated NON-HARNESS subtrees explicitly.
- description: >
    Production internal code must not depend on internal-rooted
    harness packages.
  package: "github.com/hashicorp/consul/internal/controller"
  shouldNotDependsOn:
    internal:
      - "github.com/hashicorp/consul/internal/testing"
      - "github.com/hashicorp/consul/internal/testing/**"
      - "github.com/hashicorp/consul/internal/tools"
      - "github.com/hashicorp/consul/internal/tools/**"

# Repeat for internal/controller/**, internal/dnsutil,
# internal/gossip/**, internal/multicluster, internal/protohcl,
# internal/protoutil, internal/radix, internal/resource(/**),
# internal/resourcehcl, internal/storage(/**) — i.e. all the
# non-harness internal subtrees from 1_hashicorp_consul.txt.
```

Option A is simpler; Option B is more conservative. Pick to
match F3.
```

```
[F7] SEVERITY: LOW
Category: Comment Accuracy / Misleading Documentation
Affected Rule / Constraint: Lines 626–628 (intra-agent comment) and
                            line 1083 (agent harness comment) and the
                            Round-3 fix-history.md entry that codifies
                            the same claim.

What is wrong:
Three places in the YAML (and one in fix-history.md) state or
imply the same claim:

  Lines 626–628:
  # arch-go does not flag a package's dependency on its own
  # subtree, so `agent/consul/**` as an origin is matched here
  # but trivially complies (it cannot import itself).

  Lines 1086–1090 (paraphrased):
  # ... they are now also listed as DENY TARGETS on every layer's
  # harness rule above so production code in any layer cannot
  # import them. ...

  fix-history.md (Round 3):
  > leveraging arch-go's "a package never depends on itself"
  > semantics so agent/consul/** as origin trivially complies

The literal "a package never depends on itself" claim is true
(Go does not let a package import itself by name). But the
EXTRAPOLATION — "therefore agent/consul/<sub> as origin
trivially complies with a deny on agent/consul/**" — is NOT true.
arch-go evaluates each origin's imports against the deny-list
patterns; agent/consul/state importing agent/consul/fsm is two
DIFFERENT packages, both matching agent/consul/**, and the
import would normally be flagged.

Why it matters:
A future reader of the YAML or fix-history will trust the
comment, conclude that "consolidating origin globs that overlap
with deny targets is safe," and apply the same pattern to
internal/**, lib/**, etc. Each application risks a false negative
or false positive depending on the actual code.

How to fix it:
Restate the comment in terms that are demonstrably true. For the
intra-agent comment (lines 626–628):

```yaml
  # The previous 40+ per-leaf rules have been consolidated to a
  # single `agent/**` glob plus the `agent` root mirror. Origin
  # `agent/consul/<sub>` packages match this rule and are
  # evaluated for imports of agent/consul or agent/consul/**.
  # Today's Consul codebase happens not to trigger this — no
  # agent/consul/<sub> imports a sibling agent/consul/<other>
  # package — but a future intra-server cross-import (e.g.
  # agent/consul/fsm → agent/consul/state) WOULD fire this rule.
  # If that becomes a maintenance pain, revert to the per-leaf
  # rule list. See Review #4 F3.
```

For the agent harness comment (lines 1083–1090), no change
needed — that comment is correct.

For fix-history.md, append a Review-#4 entry that retracts the
"trivially complies" claim:

```text
Review #4
Findings: ...
Fix: ... corrected the Round-3 fix-history claim that the
  consolidated agent/** rule "leverages arch-go's 'a package
  never depends on itself' semantics" — the actual semantic is
  narrower (Go forbids self-import only); the consolidated rule
  is currently green because Consul's agent/consul/<sub>
  packages do not cross-import in a way that triggers it, NOT
  because arch-go skips overlapping origin/target pairs.
```

This is LOW severity because the rule still works in practice;
it is HIGH if the team relies on the same pattern elsewhere
(see F2 and F6 for cases where the same misconception
propagates).
```

```
[F8] SEVERITY: LOW
Category: Description Cross-Reference / Maintainability
Affected Rule / Constraint: Internal/resource/** description
                            (line 484–486).

What is wrong:
The description for the internal/resource/** rule reads:

  - description: >
      internal/resource/** must not depend on the agent, api, or
      command layers. (Replaces the previous vacuous
      shouldOnlyContain stack — see F8.)

The "see F8" reference is a finding ID from a previous review
round (Review #1's F8, where the original `shouldOnlyContain`
stack was diagnosed as vacuous). A reader of arch-go.yml in
isolation cannot resolve that ID — the YAML does not point at
the source of the reference, and the review folder is structured
as `3-review/review#-by-*.md`.

Why it matters:
Cosmetic, but the file accumulates references to historical
review IDs that ALSO appear in unrelated reviews — F8 is also
the ID of Review #2's Makefile finding and Review #3's Makefile
finding. A future reader who wants to "look up F8" has three
candidates and no way to know which.

How to fix it:
Either expand the reference to be self-contained:

```yaml
  - description: >
      internal/resource/** must not depend on the agent, api, or
      command layers. (Replaces the original `shouldOnlyContain`
      stack from the initial generation pass — `shouldOnly*`
      flags are mutually exclusive in arch-go's parser, so a
      stack of three of them was structurally vacuous.)
```

…or drop the cross-reference entirely. The same applies to the
"see F11/F12 in Review #N" comments scattered through the
file (lines 16–18, 121–142, 629, 749–757, 859, 1064, 1111).
These are useful for review continuity but become noise once the
review series ends.
```

```
[F9] SEVERITY: LOW
Category: Rule Redundancy / Maintainability
Affected Rule / Constraint: Foundation deny-list block (lines 143–418).

What is wrong:
The Foundation block contains 16 distinct rules (lib, lib/**,
sdk, sdk/**, types, version, version/**, ipaddr, sentinel,
service_os, snapshot, tlsutil, logging, logging/**, acl, acl/**),
each with an essentially identical 12-target deny-list:

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

The same 12-target list is duplicated 16 times — that is 192
lines of identical YAML. The Round-3 fix correctly added a
"Foundation rule template" comment (lines 121–142) explaining
the canonical encoding for new Foundation members, but the
existing rules remain stamped out by hand.

If a future architectural change adds, say, `multicluster/**`
as a new "must not be imported by Foundation" target, the
template comment must be updated AND every one of the 16 rules
must be edited consistently. That's a maintenance hazard:
arch-go has no validation that the rules in a "Foundation
group" share a deny-list.

Why it matters:
Cosmetic / maintainability. The current state is correct; future
edits are error-prone.

How to fix it:
arch-go's YAML loader is plain go-yaml; it supports anchors and
aliases. Refactor the duplicated deny-list into a single anchor:

```yaml
# Foundation deny-list anchor — every Foundation rule below MUST
# reference *foundation_deny to stay consistent. arch-go's parser
# resolves anchors before evaluation, so this has no behavioral
# effect.
_foundation_deny: &foundation_deny
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

dependenciesRules:
  - description: Foundation lib subtree must not depend on higher layers.
    package: "github.com/hashicorp/consul/lib/**"
    shouldNotDependsOn:
      internal: *foundation_deny

  - description: Foundation lib root must not depend on higher layers.
    package: "github.com/hashicorp/consul/lib"
    shouldNotDependsOn:
      internal: *foundation_deny

  # ... (14 more rules, each with `internal: *foundation_deny`)
```

Important: verify that arch-go's loader supports YAML aliases.
Most go-yaml-based loaders do, but if arch-go custom-parses
the deny-list (e.g. via mapstructure with no anchor expansion),
this change would silently break. Do a one-line probe by adding
the anchor and running `arch-go --verbose`; the report should
show the full materialized deny-list per rule. If it does not,
fall back to a pre-processing step (`yq` or a Makefile target
that emits the YAML from a template).

Same pattern applies to the harness-deny block (the 9-target
harness list is duplicated ~13 times).
```

```
[F10] SEVERITY: LOW
Category: Threshold Configuration (deferred work)
Affected Rule / Constraint: threshold block (lines 103–105).

What is wrong:
The threshold block has been carrying `coverage: 0` for three
review rounds (since Round 2 introduced it). The comment
correctly labels this as "DISABLED — re-enable as a regression
sentinel once a baseline is measured" and prescribes the workflow
("run `arch-go --json` once, read the actual coverage
percentage, and set the threshold to (measured value − 2pts)").

Three review rounds in, the work has not been done. The current
test report includes:

  | COMPLIANCE RATE    | 100% [PASS] |
  | COVERAGE RATE      |   0% [PASS] |

The coverage gate passes vacuously regardless of whether a
future PR deletes 30 rules. As long as `coverage: 0` stands,
the threshold block does NOT detect "the rules silently stopped
applying" — exactly the failure mode Review #3 F9 was worried
about.

Why it matters:
The Round-3 Makefile hardening (the `Compliance:`-line grep)
catches the YAML-fails-to-parse failure mode. But it does NOT
catch the rules-silently-deleted failure mode. A non-zero
coverage threshold would.

How to fix it:
Run arch-go once, capture the coverage percentage, and lock it
in:

```bash
arch-go --json | jq '.summary.coverage'   # e.g. 87
```

Then in arch-go.yml:

```yaml
threshold:
  compliance: 100
  coverage: 85          # measured 87 - 2 = 85
```

The `--json` flag is verified against arch-go v2.1.2. If
`.summary.coverage` is not the exact JSON field, the Makefile
should read it from the report:

```makefile
@coverage=$$(arch-go --json | jq '.summary.coverage'); \
  test "$$coverage" -ge 85 || ( \
    echo "FAIL: coverage $$coverage < 85" >&2; exit 1)
```

Or, set a non-zero floor and let arch-go's own gate enforce it.
This is a 1-line YAML change plus a one-time measurement. It has
been deferred for three reviews; do it now.
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
| F1  | HIGH     | Coverage Gap (production ↛ mocks) | `grpcmocks/**` is never a deny target on any production layer — `agent/dns → grpcmocks/proto-public/pbdns` and 5+ similar edges silently pass; pulls test-only mockery scaffolding into release binaries |
| F2  | HIGH     | Coverage Gap (in-tree test helpers outside agent/**) | 9 in-tree test helpers (`lib/testhelpers`, `sdk/testutil(/**)`, `internal/controller/controllermock`, `internal/controller/controllertest`, `internal/resource/resourcetest`, `internal/storage/conformance`, `internal/protohcl/testproto`, `proto/private/prototest`, `version/versiontest`) are not enumerated as deny targets — production code may freely import them |
| F3  | MEDIUM   | Vacuous Rule / Semantic Risk | The Round-3 consolidation of intra-agent rules relies on an unverified arch-go semantic ("a package never depends on itself" — true; "therefore overlapping origin/target globs trivially comply" — does NOT follow); future intra-`agent/consul/**` cross-imports would surface as false-positive failures |
| F4  | MEDIUM   | Coverage Gap (Layer-3 peer asymmetry) | `troubleshoot/**` deny-list omits `api`/`api/**`; `connect/**` and `envoyextensions/**` (sibling Layer-3 peers) deny it. Either troubleshoot has a documented exception (preamble does not say so) or the asymmetry is a real gap |
| F5  | MEDIUM   | Vacuous Rule | `package: "github.com/hashicorp/consul/sdk"` (line 200) and `package: "github.com/hashicorp/consul/internal"` (line 474) target packages that don't exist in the module — both rules pass vacuously |
| F6  | MEDIUM   | Coverage Gap (intra-internal harness) | The `internal/**` harness-deny rule omits `internal/testing/**` and `internal/tools/**` as targets; production internal code may freely import internal-rooted harness packages |
| F7  | LOW      | Comment Accuracy / Misleading Documentation | Lines 626–628 and the Round-3 fix-history claim "arch-go does not flag a package's dependency on its own subtree" — that claim is not part of arch-go's documented behavior, only the literal Go-self-import case is excluded; correct the comment so future reuse of the pattern (F2, F6) is grounded in fact |
| F8  | LOW      | Description Cross-Reference / Maintainability | Description on line 485 references "see F8" — an ambiguous review-finding ID also used in three unrelated reviews; expand the reference to be self-contained or drop it |
| F9  | LOW      | Rule Redundancy / Maintainability | The 12-target Foundation deny-list is duplicated 16 times (192 lines); refactor to a YAML anchor + alias, or pre-process from a template, to make additions error-resistant |
| F10 | LOW      | Threshold Configuration (deferred work) | `coverage: 0` has stood for three review rounds with the "DISABLED — re-enable once a baseline is measured" comment; do the measurement and lock in the floor — protects against the rules-silently-deleted failure mode that the `Compliance:`-line grep does not catch |

**2 of 10 findings are HIGH; 4 are MEDIUM; 4 are LOW.** Round 3 closed every CRITICAL finding from the prior rounds (the YAML now parses, the Makefile is hardened, the harness defense covers `command/**` and the populated production roots, the in-tree agent harnesses are deny targets). The two new HIGH findings (F1, F2) are coverage gaps that escaped previous rounds because they involve sibling-tree deny targets not yet in the standard harness payload — `grpcmocks/**` and the nine non-agent in-tree test helpers. The MEDIUM findings (F3, F4, F5, F6) are residual precision concerns: an unverified arch-go semantic, an unjustified Layer-3 asymmetry, two vacuous root rules, and the same self-overlap class that F3 warns about. The LOW findings are documentation polish and one-line YAML refactors.

---

### What to fix first

If only one round of edits is possible, apply this priority order:

1. **F1** — add `grpcmocks` and `grpcmocks/**` as deny targets on every production-layer rule. This is the easiest CRITICAL-class plug; it is exactly the same shape of fix as Round 3's in-tree-agent-harness fix, but for the mock tree.
2. **F2** — append the nine in-tree test helpers to every production-layer harness-deny rule. Same shape as F1.
3. **F3** — verify (empirically, with a one-line throwaway commit) whether arch-go flags `agent/consul/state → agent/consul/fsm` against the consolidated rule. If it does, revert to the per-leaf rules; if it does not, codify the actual semantic in a comment with a source-code reference. Either outcome retires the unverified claim.
4. **F4 + F5 + F6** — close the three remaining MEDIUM coverage gaps (troubleshoot api ban, vacuous sdk/internal root rules, internal-rooted harness packages).
5. **F10** — measure coverage and set a non-zero floor. One-line YAML change; protects against rules-silently-deleted regressions.

Findings F7, F8, F9 are documentation and maintainability polish and can be batched in a final hygiene pass.

---

**End of Review #4.**
