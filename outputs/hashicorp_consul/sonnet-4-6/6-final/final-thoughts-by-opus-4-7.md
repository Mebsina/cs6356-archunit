# Final Thoughts - hashicorp_consul ArchUnit Rules

Reviewer: opus-4-7
Subject: `outputs/hashicorp_consul/sonnet-4-6/arch-go.yml`
Iteration terminating the loop: analyze1 (`Results: 0 mapping error`; no patch was applied — the rule file entered the analyze loop already at zero violations after five rounds of pre-loop adversarial review captured in `fix-history.md`)
Ground truth documents consulted: `inputs/go/1_hashicorp_consul.jpg` (a single runtime-topology diagram showing CONSUL SERVERS — Leader/Follower — connected over a CONTROL PATH to per-host CONSUL CLIENTs, with sidecar PROXYs mediating App A ↔ App B traffic on the DATA PATH), `inputs/go/1_hashicorp_consul.txt` (290-line top-level package inventory of `github.com/hashicorp/consul/...`)

---

## Section 1 — Verdict

A fixed point has been reached: the rule file, the `github.com/hashicorp/consul` import graph (at the packages currently in scope of `arch-go`), and my reading of the single architecture diagram all three agree — the arch-go report is `Total Rules: 145, Succeeded: 145, Failed: 0` (`128/6/3/8` for dependencies / functions / contents / naming) and no documented prohibition is obviously left un-encoded. This is strictly weaker than "the rule file matches the documentation" — and unusually so for this project, because the only documentation supplied is a runtime-topology picture (control plane vs. data plane, server cluster, client agent, sidecar proxy), and arch-go matches Go import paths only. The five-layer module-prefix taxonomy (Foundation → Internal Framework → Agent → Server → API/CLI/Connect) is therefore an *operational encoding* layered on top of a deployment diagram that says nothing about Go packages, plus a partial intra-server DAG and a handful of harness/test-tree carve-outs. What follows is an honest accounting of which claims survive that gap and which do not.

---

## Section 2 — What I am confident about

Claims directly verifiable against the shipped YAML or the current `arch-go` report; no interpretive reading of the diagram is required:

1. All 145 rules in `arch-go.yml` emit `[PASS]` against the imported package set; the summary reports `Compliance: 100% (PASS)`. (`Coverage: 0% (PASS)` is the configured `threshold.coverage: 0` floor, intentional, documented in the YAML header.)
2. Every top-level package listed in `inputs/go/1_hashicorp_consul.txt` (`acl`, `agent`, `agent/consul`, `api`, `command`, `connect`, `envoyextensions`, `grpcmocks`, `internal`, `ipaddr`, `lib`, `logging`, `proto`, `proto-public`, `sdk`, `sentinel`, `service_os`, `snapshot`, `testing`, `test-integ`, `testrpc`, `tlsutil`, `tools`, `troubleshoot`, `types`, `version`, plus the module root) is assigned to exactly one of the five layers, the harness/support tree, or the explicit "out of scope" exclusion list at the top of the YAML.
3. Every `dependenciesRules` entry has a non-empty `description:` field, and every entry's `shouldNotDependsOn.internal:` deny list is a literal package-prefix list (no `**…**` wildcards that would silently expand). There is no rule with an empty deny list.
4. The schema is pinned (`version: 1`) and the thresholds are explicit (`compliance: 100`, `coverage: 0`); behaviour does not depend on which arch-go binary CI happens to install. The Makefile carries an `arch-version-check` target asserting the v1 fork.
5. All four `contentsRules` use the flat-boolean schema (`shouldOnlyContainStructs: true`) — earlier revisions used the nested-list shape that arch-go silently dropped (Review #5 finding). The three surviving rules (`proto/**`, `proto-public/**`, `grpcmocks/**`) are real, non-vacuous structural checks.
6. Every Layer-3 single-directory `agent/<leaf>` subtree has both a `/**` rule and a directory-root mirror rule (the `/**` glob may not match the directory root per the generator contract). The two known false mirrors removed in Review #5 (`agent/proxycfg-sources`, `agent/rpc`) are gone, replaced by inline comment markers explaining the deletion.
7. The diagram constraints (control-plane vs. data-plane separation, sidecar mediation, app-to-app traffic isolation, server-only RPC paths) are explicitly documented in the YAML header (lines 80–86) as **out of scope** for arch-go enforcement; no rule claims to encode them and CI PASS does not imply diagram conformance.

---

## Section 3 — What I am NOT confident about

### 3.1 Documentation silences

Questions the rule file had to answer that the documentation does not. The diagram is the *only* architectural artefact supplied; it shows runtime topology and is silent on essentially every Go-package question:

- **Is there even a layered package architecture?** Diagram silent. Encoded as a five-band stack (Foundation → Internal Framework → Agent → Server → API/CLI/Connect) by reviewer judgment from package-name conventions alone.
- **Where do `agent/consul/auth`, `agent/consul/authmethod`, `agent/consul/autopilotevents`, `agent/consul/multilimiter`, `agent/consul/rate`, `agent/consul/reporting`, `agent/consul/servercert`, `agent/consul/stream`, `agent/consul/wanfed`, `agent/consul/watch`, `agent/consul/xdscapacity` live?** Diagram silent. All swept into Layer 4 (Server / Consensus) by virtue of their `agent/consul/**` prefix.
- **May `agent/structs` and `agent/config` be imported by `agent/consul/**`?** Diagram silent. Rule allows it (these are deliberately *not* on the Layer-4 deny list); the YAML treats `agent/structs` as a "neutral shared package" between Agent and Server.
- **May `command/**` import `agent/**` (non-server) at all?** Diagram silent. Rule allows it (CLI entrypoints embed the agent), but bans `agent/consul/**`, `internal/**`, and `envoyextensions/**`. This is "asymmetric Layer-5 isolation"; my coinage, not the diagram's.
- **May `troubleshoot/**` import `api/**`?** Diagram silent. Strict-leaf rule forbids it (mirrors the `connect/**` posture). A diagnostics tool that wraps the public API would be a defensible alternative interpretation.
- **May `internal/*` peers import each other?** Diagram silent. The YAML deliberately permits Layer-2 peer imports (`internal/* → internal/*`) with the *single* exception `internal/multicluster → internal/controller/**`. The "Foundation only" prose that would normally constrain a Layer-2 framework is documented as aspirational, not enforced.
- **Is `envoyextensions/**` Layer 2 or Layer 3?** Diagram silent. Top-level `envoyextensions/**` is Layer 2 (Internal Framework); `agent/envoyextensions/**` is Layer 3 (Agent). The two trees are allowed to overlap in concept; the duplication is flagged as a "design smell but not separately linted here".
- **Are `grpcmocks/**`, `internal/testing/**`, `internal/tools/**`, `test-integ/**`, `testing/deployer/**`, `testrpc`, `tools/**`, `lib/testhelpers`, `sdk/testutil/**`, `agent/mock`, `agent/routine-leak-checker`, `agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `internal/storage/conformance`, `internal/protohcl/testproto`, `internal/resource/protoc-gen-*`, `internal/resource/demo`, `internal/controller/controllermock`, `internal/controller/controllertest` part of the layer chart at all?** Diagram silent. All classified as "harness / test-only / build-time / generated" and listed in the YAML's "EXCLUDED / SPECIAL-SCOPE PACKAGES" comment block; some carry narrow outbound bans (no `agent/consul/**`), others are excluded entirely.
- **Are intra-`agent/consul` cycles forbidden?** Diagram silent. Only a partial DAG is encoded (`state`, `fsm`, `discoverychain`); the rest of the server tree (`stream`, `watch`, `auth`, `xdscapacity`, `controller`, …) is delegated to `go build` cycle detection, not arch-go.

### 3.2 Invented labels

Every layer name in the YAML is my coinage; **none** appears in the diagram, which contains only the words "CONSUL SERVERS", "CONSUL CLIENT", "PROXY", "APP A", "APP B", "CONTROL PLANE", "DATA PLANE", "FOLLOWER", "LEADER":

- `Foundation`, `Internal Framework`, `Agent / Control Plane`, `Server / Consensus`, `API / CLI / Connect` — all five layer names are mine. The diagram does say "CONTROL PLANE" and "CONSUL SERVERS", but it uses those terms for *runtime topology*, not for a Go module-prefix layering.
- The "harness / support trees" group (`test-integ`, `testing/deployer`, `testrpc`, `tools`, `internal/testing`, `grpcmocks`, `internal/tools`) is a label I introduced.
- The "neutral shared package" framing for `agent/structs` and `agent/config` is mine; no ground-truth document calls it that.
- The "asymmetric Layer-5 leaf isolation" framing — `api/**`/`connect/**`/`troubleshoot/**` strict, `command/**` partially permissive — is mine.
- The "single targeted Layer-2 peer ban" framing for `internal/multicluster → internal/controller/**` is mine; it is described in the YAML as the only encoded peer-cycle break.
- "Forward-compat scaffolding" as a label for the 26 vacuous Layer-3 `/**` rules paired with their root mirrors is mine.

### 3.3 Inferred rules

Every rule whose justification rests on inference rather than a documentation citation. Effectively this is the entire file: nothing in `1_hashicorp_consul.jpg` prescribes Go import-path constraints, so all 145 rules are inferred to some degree. The strongest ones I can defend by *idiomatic Go layering convention plus package-naming evidence*:

- The five-layer dependency direction (lower → higher forbidden) — inferred from the universal Go convention that `internal/`, `lib/`, `proto/`, `sdk/`, `types/` are lower-level than service code, and that `api/`, `command/`, `connect/`, `troubleshoot/` are public-facing leaves.
- `agent/consul/**` (server) must not import `command/**`, `api/**`, `connect/**`, `troubleshoot/**` — inferred from the diagram's separation of "CONSUL SERVERS" from leaves like the CLI / public client; the diagram does not name `agent/consul`, but the package-name convention strongly suggests this is the server.
- `agent/grpc-internal/**` and `agent/grpc-middleware/**` must not import `agent/grpc-external/**` or `agent/xds/**` — inferred from the `internal`/`external` naming split; no documentation mentions the gRPC stacks at all.
- `agent/cache/**`, `agent/cache-types`, `agent/proxycfg/**` must not reach `agent/xds/**` — inferred from the typical proxycfg-feeds-xds pipeline direction; not in the diagram.
- `agent/consul/state` and `agent/consul/fsm` deny lists for the partial server DAG (`discoverychain`, `prepared_query`, `gateways`, `usagemetrics`, `wanfed`, `reporting`) — inferred from "state and fsm sit at the bottom of the server's internal DAG" reasoning.
- `agent/consul/discoverychain` must not import `state` or `fsm` — inferred ("computes routing, receives data via arguments to remain testable").
- All four `contentsRules` (`proto/**`, `proto-public/**`, `grpcmocks/**` should only contain structs) — inferred from "these are generator output, not hand-written code" plus the BRITTLENESS caveat that protoc/mockgen upgrades may flip the result with no architectural change.
- All eight `namingRules` (`Store`, `Backend`, `Resolver`, `Provider`, `Reconciler` in two packages, `Limiter` in two packages) — inferred from observed implementation patterns in the listed packages, not from any prescribed naming convention document.
- All six `functionsRules` (`maxReturnValues: 2` for `agent/consul/state` and `agent/consul/discoverychain`; `maxReturnValues: 3` for `lib/**`, `lib`, `internal/storage/**`, `internal/storage`) — inferred from "state-store accessors should decompose into structs"; the documentation has no opinion on function signatures.
- The single Layer-2 peer ban (`internal/multicluster → internal/controller/**`) — inferred from "multicluster is invoked via framework callbacks; a direct import would create the most likely Layer-2 peer cycle"; the diagram does not mention either package.

### 3.4 Undocumented carve-outs

`arch-go` has no `ignoreDependency` primitive; the equivalent constructs in this file are (a) intentionally narrowed deny lists, (b) per-rule scope splits, and (c) the entire "EXCLUDED / SPECIAL-SCOPE PACKAGES" header block. **None** is granted by the diagram; every one is reviewer judgment with rationale only in `description:` and inline comments:

- **`internal/storage/conformance`** carries a *relaxed* deny list (only `command/**` and `troubleshoot/**`) instead of the full Layer-2 framework guard, because conformance suites legitimately need cross-package fixtures. arch-go v1 does not support per-rule exclude globs, so this is implemented as a separate rule that overrides the broader `internal/storage/inmem` and `internal/storage/raft` posture.
- **The 26 forward-compat `/**` mirrors** (`agent/ae/**`, `agent/auto-config/**`, `agent/blockingquery/**`, `agent/cacheshim/**`, `agent/checks/**`, `agent/configentry/**`, `agent/debug/**`, `agent/dns/**`, `agent/envoyextensions/**`, `agent/exec/**`, `agent/leafcert/**`, `agent/local/**`, `agent/log-drop/**`, `agent/metadata/**`, `agent/metrics/**`, `agent/mock/**`, `agent/netutil/**`, `agent/pool/**`, `agent/proxycfg/**`, `agent/proxycfg-glue/**`, `agent/router/**`, `agent/routine-leak-checker/**`, `agent/submatview/**`, `agent/systemd/**`, `agent/token/**`, `agent/uiserver/**`) match zero packages today. Their root mirrors carry the only active enforcement; the `/**` siblings are scaffolding for the day a leaf grows a sub-package. This is a maintenance trade-off — duplicating rules for forward compatibility — that the diagram does not discuss.
- **`agent/consul` is allowed to import `agent/structs`, `agent/config`, `agent/connect`, `agent/pool`, `agent/router`, etc.** — by deliberate omission from the Layer-4 deny list. The "neutral shared package" treatment is reviewer judgment.
- **`command/**` is allowed to import `agent/**` (non-server)** — by deliberate omission from the `command/**` deny list. The asymmetry vs. `api/**`/`connect/**`/`troubleshoot/**` is reviewer judgment grounded in "CLI entrypoints embed the agent".
- **`agent/envoyextensions/**` is allowed to import `envoyextensions/**`** — explicitly noted in the rule's `description` as a "design smell but not separately linted here". The diagram does not mention either tree.
- **The module-root rule (`github.com/hashicorp/consul`) bans only the test harness** (`test-integ/**`, `testing/deployer/**`, `testrpc`) — not the broader Layer-5 set. This is reviewer judgment about what `main.go` may legitimately import.
- **`internal/tools/**` may import `internal/testing/**`?** No: the rule specifically denies that edge in addition to the product layers. This negative judgment is reviewer-coined.

### 3.5 Import-scope conditionality

Zero-violations is scoped to whatever `arch-go` discovers from the module's import graph at the time CI runs. Specifically:

- **arch-go evaluates Go import paths only.** Any constraint expressible only in terms of runtime topology, build tags, generated bindings, reflection, codegen plug-ins, or `init()` side effects is invisible. The diagram's runtime constraints fall in this category and are explicitly disclaimed in the YAML header.
- **Test-file scope.** arch-go visits `_test.go` files unless excluded; the YAML does not exclude them, but several test-only packages (`grpcmocks`, `internal/testing`, `lib/testhelpers`, `sdk/testutil`, `agent/mock`, `agent/routine-leak-checker`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/grpc-*/testutils`, `internal/protohcl/testproto`, `internal/resource/demo`, `internal/resource/protoc-gen-*`, `internal/controller/controllermock`, `internal/controller/controllertest`, `internal/storage/conformance`) carry either narrowed rules or no rule at all. If a test-only package later acquires production-relevant imports, no rule will fire.
- **Generated code.** `proto/**`, `proto-public/**`, and `grpcmocks/**` are subject to `shouldOnlyContainStructs: true`. The YAML header carries an explicit BRITTLENESS warning that protoc / mockgen upgrades may emit helpers reclassified as functions, flipping CI to FAIL with no architectural change.
- **Transitive shim laundering.** The YAML header (lines 75–78 and 102–105) is explicit: a package in an "allowed" prefix can re-export types from a forbidden prefix without arch-go noticing. Stable choke-points (`agent/structs` adapters, proto re-exports, `lib/safe`) are called out as candidates for paired enforcement via `depguard` or `go mod graph`; that pairing is *not* in this file.
- **Multi-module repos.** `github.com/hashicorp/consul` ships some sub-modules (`api`, `sdk`, `proto-public`, `envoyextensions`, `troubleshoot`, etc., depending on the snapshot of `go.mod`). Whether `arch-go` runs against all of them or only the root module depends on the Makefile target (`arch-check`) and which `go.mod` is in effect. If a sub-module's `go.mod` excludes a tree, classes there are not covered.
- **Coverage threshold is 0% by design** because arch-go's coverage metric is the fraction of *packages* matched by some rule's glob (a heuristic), not the fraction of architectural *constraints* the rules cover. Raising it would not catch a real violation; it would only shift CI noise.

### 3.6 Per-class judgment calls

`arch-go` rules operate on package prefixes, not on individual types — so there are no `name(...)`-level per-class predicates here. The equivalent micro-decisions in this file are **per-package classifications** that a Consul maintainer could plausibly disagree with. Approximate count:

- **Single Layer-2 peer ban target (1):** `internal/multicluster → internal/controller/**`. Picked by reviewer judgment as "the most likely peer cycle". A maintainer might pick a different cycle break, or a different set of bans entirely.
- **Conformance-suite carve-out (1):** `internal/storage/conformance` runs with the relaxed deny list (`command/**`, `troubleshoot/**` only). A maintainer might instead exclude conformance from arch-go entirely, or apply the full Layer-2 guard.
- **`agent/structs` and `agent/config` "neutral shared package" picks (2):** Both packages are in Layer 3 by prefix, but their rules ban `agent/consul/**`, `agent/proxycfg/**`, and `agent/xds/**` to keep them neutral. A maintainer might argue `agent/structs` is properly shared with the server (it actually is, in the live import graph) and that the ban is too tight, or alternatively that `agent/config` should also ban `agent/grpc-*/**`.
- **gRPC/XDS layer-internal isolation picks (4):** `agent/grpc-internal/**`, `agent/grpc-middleware/**`, `agent/cache/**`, and `agent/proxycfg/**` each carry an extra ban on `agent/xds/**` (and proxycfg also bans `agent/cache/**`). These four "internal Layer-3 sub-DAG" decisions are reviewer-coined; the diagram does not mention any of them.
- **Server-tree DAG picks (3):** `agent/consul/state`, `agent/consul/fsm`, `agent/consul/discoverychain` each carry a hand-written deny list naming five-to-seven server peer packages. The other ~20 `agent/consul/*` peers are *not* encoded — their cycle detection is delegated to `go build`. A maintainer with the import graph in hand might extend or contract these picks substantially.
- **Layer-1 / Layer-2 / Layer-3 directory-root mirror count (~50 root-mirror rules):** Each is a judgment that the corresponding `/**` rule does not match the directory root by itself. Whether the v1 generator contract really requires every one of these is reviewer-asserted (per the Layer-3 FORWARD-COMPAT NOTE) and partly empirical; a maintainer who tested the binary directly might find some redundant.
- **Naming-rule package picks (8):** Each `interfaceImplementationNamingRule` is scoped to one package and one suffix (`Store` in `agent/consul/state`, `Backend` in `internal/storage/**`, `Resolver` in `agent/grpc-internal/resolver`, `Provider` in `agent/connect/ca`, `Reconciler` in `internal/controller/**` *and* `agent/consul/controller`, `Limiter` in `agent/consul/rate` and `agent/consul/multilimiter`). The `agent/consul/multilimiter` rule had to switch to a `*Limiter` wildcard in Review #5 because the `RequestLimiter` interface lives in `agent/consul/rate` — a same-package interface lookup degraded to a no-op. A maintainer might prefer different conventions in any of these packages.
- **`maxReturnValues` picks (6):** `2` for `agent/consul/state` and `agent/consul/discoverychain`, `3` for `lib/**`/`lib` and `internal/storage/**`/`internal/storage`. The split between "2" and "3" is reviewer-coined; a maintainer with an opinion about idiomatic `(T, U, error)` returns might pick differently in either direction.

**Total: roughly 75 individual per-package classifications and bans where a Consul committer could reasonably disagree.** None of them changes the zero-violation outcome — the loop iterated to internal consistency, not to validation against an authoritative committer review.

---

## Section 4 — The strongest claim I can actually support

> The rule file, the currently-imported `github.com/hashicorp/consul` import graph, and my reading of `1_hashicorp_consul.jpg` plus the `1_hashicorp_consul.txt` package inventory all agree at a fixed point.

Two corollaries that follow from test evidence alone (not interpretation):

1. No layer-crossing edge in the imported import graph is silently permitted by an empty deny list or a `**` wildcard: every layer-crossing import has either been explicitly listed under `shouldNotDependsOn` (and the codebase obeys it), explicitly omitted from a deny list (and the rule's `description:` field documents the omission), or moved into a harness/test-tree carve-out with its own narrowed rule. There is no rule with an empty `shouldNotDependsOn:` block.
2. The runtime-topology constraints implied by the diagram (control vs. data plane, sidecar mediation, server-only RPC paths, app traffic isolation) are documented as out of scope in the YAML header rather than silently elided; CI PASS is bounded by the disclaimer and does not claim diagram conformance.

Nothing in the test evidence supports a stronger claim.

---

## Section 5 — What would upgrade confidence

Descending by impact:

1. **Consul maintainer / committer review of the ~75 per-package judgments** enumerated in §3.6. Especially the single Layer-2 peer ban target (`internal/multicluster → internal/controller/**`), the conformance-suite carve-out, the "neutral shared package" treatment of `agent/structs` and `agent/config`, the gRPC/XDS internal-isolation picks, and the partial intra-`agent/consul` DAG. A maintainer can settle in 30 minutes what the diagram cannot settle at all.
2. **An ADR or `docs/architecture/*.md` addendum** written by the Consul team that names the five layers (or whatever five-or-other layers they actually believe in), assigns each `agent/<leaf>`/`internal/<leaf>` package to one, and documents which intra-server cycles are forbidden. That addendum becomes the real ground truth for the next iteration of this loop and would let many of the §3.1 silences and §3.2 invented labels collapse into citations.
3. **Pair every `shouldNotDependsOn` choke-point with an external check** (`depguard` config, `go mod graph` post-processing, or a custom analyzer) for transitive shim-laundering — explicitly recommended by the YAML header itself but not in this file. Highest-value targets: `agent/structs` adapters, proto re-exports, `lib/safe`, and any `agent/<leaf>` that re-exports `agent/consul` types.
4. **Mutation testing.** Inject a known-bad edge (e.g., a `command/agent` file that imports `agent/consul/state` directly) and confirm `arch-go` fails; revert and confirm it passes. Currently the false-negative rate is unmeasured — only the false-positive rate (zero, on the current graph) is proven. The vacuous Layer-3 `/**` mirrors (§3.4) are particularly worth stress-testing; if a future child package is added under e.g. `agent/dns/internal`, the `/**` rule should fire and this should be confirmed by mutation, not hoped for.
5. **Extend the rule scope to currently-excluded trees.** Many test/codegen/mock packages are documented as out of scope; a maintainer who decides which of those should be in scope (e.g., should `internal/storage/conformance` ban `agent/consul/**` after all?) would let several rules tighten meaningfully.
6. **Encode an explicit intra-`agent/consul` DAG.** The current file enforces only `state`, `fsm`, and `discoverychain`; new cycles in `stream`/`watch`/`auth`/`xdscapacity`/`controller` are caught by `go build`, not by arch-go. Either expand the deny lists pairwise, or accept the delegation in writing.
7. **Pin `arch-go` v1 binary in CI** (not just in the Makefile `arch-version-check` target) so a developer running `arch-go/arch-go` v2 cannot accidentally re-evaluate this YAML against the v2 schema, where `interfaceImplementationNamingRule` has a different shape.

None of items 1–7 is required to ship. All of them would upgrade confidence from "three-way fixed point" to "formally validated by the Consul project itself".

---

## Section 6 — Recommendation

**Ship, with the caveats below added to the YAML header comment block so the file is honest about its own limits.**

Caveats to add to the header (the file already carries most of these in scattered comments — this is the consolidated form a future maintainer should see at the top):

> **A note on authority.** The five-band layering encoded below (Foundation → Internal Framework → Agent / Control Plane → Server / Consensus → API / CLI / Connect) is an *operational encoding* derived from idiomatic Go packaging conventions and the package inventory in `inputs/go/1_hashicorp_consul.txt`, not a direct quotation from any architecture document. The single ground-truth artefact supplied (`inputs/go/1_hashicorp_consul.jpg`) shows runtime topology only — Consul Server cluster (Leader/Follower), per-host Consul Client, sidecar Proxy, App A/B traffic — and prescribes none of these Go module-prefix bands. The five layer names, the harness-tree grouping, the asymmetric Layer-5 leaf isolation, the "neutral shared package" treatment of `agent/structs` and `agent/config`, and the partial intra-`agent/consul` DAG are reviewer judgment.
>
> **A note on carve-outs.** This file contains (a) one targeted Layer-2 peer ban (`internal/multicluster → internal/controller/**`) chosen as the most likely cycle break, (b) one relaxed deny list for `internal/storage/conformance`, (c) ~26 forward-compat `/**` rules for currently-leaf `agent/<name>` directories whose root mirrors carry the only active enforcement, (d) a documented "EXCLUDED / SPECIAL-SCOPE PACKAGES" block listing every test/mock/codegen tree intentionally outside the layer chart, and (e) several deliberate omissions from deny lists (e.g., `command/**` may import `agent/**` non-server). None of these carve-outs is granted by the diagram; each is reviewer judgment with rationale in the affected rule's `description` field. Project maintainers are invited to re-examine the list; any carve-out the team disagrees with should be tightened in a follow-up rev, not preserved by precedent.
>
> **A note on scope.** Zero-violations is scoped to whatever `arch-go` evaluates against the current `go.mod` import graph. Runtime topology from the diagram (control plane vs. data plane, sidecar mediation, app isolation, server-only RPC paths) is explicitly out of scope and cannot be enforced by import-path predicates; CI PASS does not imply diagram conformance. Transitive shim laundering is not detected by static import rules and should be paired with `go mod graph` or `depguard` checks at the choke-point packages (`agent/structs` adapters, proto re-exports, `lib/safe`). Generated-code `contentsRules` (`proto/**`, `proto-public/**`, `grpcmocks/**`) are sensitive to protoc/mockgen version upgrades — pin generator versions and regenerate before bumping. The arch-go schema is pinned to `version: 1` (fdaines/arch-go v1.5.4); the Makefile `arch-version-check` target asserts this binary in CI, but developers running arch-go/arch-go v2 locally will see different `interfaceImplementationNamingRule` semantics.

No further rule-file edits are recommended in this document; that belongs in an `analysisN.md`, not a final-thoughts file. This loop is over.

---

**End of Final Thoughts.**
