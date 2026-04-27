# Final Thoughts - hashicorp_consul ArchUnit Rules

Reviewer: opus-4-7
Subject: `outputs/hashicorp_consul/gemini3-flash/arch-go.yml`
Iteration terminating the loop: analysis1 (`Results: 0 mapping error` after the post-Review-#3 patch already in place; no analyze-loop edit was required)
Ground truth documents consulted:
- `inputs/go/1_hashicorp_consul.jpg` (HashiCorp Consul deployment-topology diagram — Consul Servers as Raft Leader+Followers, Consul Clients on each host, sidecar Proxies, App A / App B; labelled Control Plane / Data Plane)
- `inputs/go/1_hashicorp_consul.txt` (flat listing of 290 Go package paths under `github.com/hashicorp/consul/...`)
- Loop history: `outputs/hashicorp_consul/gemini3-flash/fix-history.md` (Reviews #1–#3) and `outputs/hashicorp_consul/gemini3-flash/5-analyze/analyze1-by-opus-4-7.md`

Note: this project uses `arch-go` (a Go static-analysis tool driven by a YAML rule file) rather than ArchUnit on the JVM, so where the prompt says "Javadoc header" I am writing about the YAML preamble doc-block, and where it says "`@ArchTest` rules" I am writing about the entries under `dependenciesRules` / `contentsRules` / `namingRules` / `functionsRules`. The calibration logic is identical.

---

## Section 1 — Verdict

The loop reached a fixed point on the first analyze iteration: `arch-go` reports `Total Rules: 66, Succeeded: 66, Failed: 0` (`Compliance: 100% (PASS)`, `Coverage: 0% (PASS)` against an intentionally floored `threshold.coverage: 0`), with zero violations across 58 dependency rules, 2 function rules, 3 contents rules, and 3 naming rules. That outcome is strictly weaker than "the rule file matches the documentation": it proves only that the codebase as currently imported does not break the rules that the YAML actually encodes — it does not prove that the YAML is a complete or faithful translation of the documentation, nor that the documentation is rich enough to be translated. The remaining sections enumerate exactly where I am extrapolating, where I am inventing, and what would have to happen to upgrade "fixed point reached" into "formally validated".

---

## Section 2 — What I am confident about

These are the claims that follow from the test artifact and a literal reading of the YAML, with no interpretive layer added.

1. The arch-go run reports `Total Rules: 66, Succeeded: 66, Failed: 0` — i.e., every rule the YAML emits passes against the imported set of `.go` files.
2. The YAML parses against the installed binary: a `Compliance:` line is printed, which the Makefile's `grep -qE '^Compliance[: ]'` sentinel asserts (`fix-history.md` Review #3, F9). A YAML-parse failure would have produced no compliance line and a hard CI fail.
3. Every top-level package in `inputs/go/1_hashicorp_consul.txt` is matched by at least one `package:` glob in the YAML, except `test-integ/**`, which is intentionally and explicitly out of scope (`arch-go.yml` lines 60–65).
4. Both a `<layer>` root rule and a `<layer>/**` subtree rule exist for every layer that has a populated root package (`agent`, `api`, `lib`, `sdk`, `acl`, `internal`, `connect`, `command`), closing the arch-go-glob requirement that `**` matches at least one extra path segment.
5. The intra-agent server-vs-client split is encoded as a single `agent/** ↛ {agent/consul, agent/consul/**}` pair (subtree + root mirror), not as 35 per-leaf rules.
6. The Raft↔inmem pluggability ban is symmetric: both `internal/storage/raft/** ↛ internal/storage/inmem(/**)` and `internal/storage/inmem/** ↛ internal/storage/raft(/**)` are present (4 rules, counting root mirrors).
7. Naming rules use the flat-string `structsThatImplement: "*Backend"` / `"*Reconciler"` form, not the v2 structured-map form — required because the v1.5.4 fork still occasionally on PATH rejects the map and silently drops every rule (`fix-history.md` Review #3, F1).
8. The three contents rules each set `shouldNotContainInterfaces: true` and *only* that key — `shouldNotContainFunctions: true` was deliberately removed because every generated `*.pb.go` and mockery package declares `init()` / `Register*Server` / EXPECT helpers as free functions (`fix-history.md` Review #3, F2).
9. The harness defense-in-depth block enumerates the same nine harness-tree targets (`testing/deployer`, `testing/deployer/**`, `test-integ`, `test-integ/**`, `testrpc`, `tools`, `tools/**`, `internal/testing/**`, `internal/tools/**`) on every production layer's deny-list, and additionally lists the five in-tree harness packages (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`) as deny targets on every layer where they could leak.
10. `threshold.coverage` is set to `0` (not omitted), and the preamble explicitly labels this `DISABLED — re-enable as regression sentinel once a baseline is measured` rather than passing it off as a measured value.

That is the floor. Everything below this section is interpretation.

---

## Section 3 — What I am NOT confident about

### 3.1 Documentation silences

The ground-truth documentation file `1_hashicorp_consul.jpg` is a *runtime-topology* diagram. It depicts:

- Three Consul Servers (one Leader, two Followers) communicating via Raft.
- A Consul Client on each application host.
- A sidecar Proxy beside each application (App A, App B).
- A "Control Plane / Control Path" label and a "Data Plane / Data Path" label.

It says **nothing** about Go package structure, layering, or import direction. Every dependency rule in the YAML therefore answers a question the documentation never asked. The silences include:

- **Is there a "Foundation" layer?** Doc silent. The YAML invents one and assigns 15 packages to it (`lib`, `lib/**`, `sdk`, `sdk/**`, `types`, `version`, `version/**`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging`, `logging/**`, `acl`, `acl/**`).
- **Is `proto/**` / `proto-public/**` a layer above or below Foundation?** Doc silent. The YAML places them at "Layer 2 — Data Plane / Protos" but *also* explicitly permits Foundation members to import them ("softer-than-strict-layering interpretation"; preamble lines 14–18, F11 in Review #2). Both directions are inferred.
- **Is `internal/**` the "Domain Core"?** Doc silent. The package name `internal` is a Go visibility convention (only consumable by sibling packages of the parent), not a documented architectural label.
- **Are `connect/**`, `envoyextensions/**`, `troubleshoot/**` peers of `internal/**` at "Layer 3"?** Doc silent. The YAML treats them as cross-cutting peers and bans Layer-4/5/6 deps from each. The diagram does show "Proxy" in the data-plane band, but does not say `connect/proxy` is a Layer-3 peer of `internal/`.
- **Is `agent/consul/**` server-side and the rest of `agent/<leaf>` client-side?** The diagram *does* show "CONSUL SERVERS" and "CONSUL CLIENT" as distinct boxes, so the conceptual server/client split is documented; however, the *encoding* — that a Go package literally named `agent/consul` is the server side and every other `agent/<leaf>` is the client side — is a code-naming inference, not a quote.
- **Is the API package the "Public client library" (Layer 5) and `command/**` the "CLI / Consumer" (Layer 6)?** Doc silent. There is no Go-package-vs-layer mapping anywhere in the diagram.
- **Should `command/**` be banned from importing `internal/**` directly?** Doc silent. The YAML enforces this on the rationale "the CLI should consume the public Agent or API surface" — that rationale is plausible but is the reviewer's, not the document's.
- **Should `api/**` be banned from importing `internal/**`?** Doc silent.
- **Should the Raft and in-memory storage backends be mutually unaware?** The diagram shows Raft consensus among Consul Servers but does not name an in-memory backend at all. The whole "pluggability" framing is an inference from the existence of `internal/storage/raft` and `internal/storage/inmem` as sibling Go packages.
- **Are test harnesses (`testing/deployer/**`, `test-integ/**`, `testrpc`, `tools/**`, `internal/testing/**`, `internal/tools/**`) supposed to be unimportable from production?** Doc silent. Universally good practice, but not documented for *this project*.
- **Are the five in-tree agent harness packages (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`) test-only?** Doc silent. Inferred from the suffixes `testutils` / `testutil` / `testcommon` / `*-test` / `mock`.
- **Is `grpcmocks/**` a sibling mock tree?** Doc silent. The path-prefix `grpcmocks` is the only signal; the architectural intent is inferred.
- **Are `Backend` and `Reconciler` the canonical interfaces in `internal/storage` and `internal/controller`?** The diagram does not name any interface. This is purely a codebase-driven inference (Review #1, F16: replaced the original `Store` / `Repository` names that did not exist in Consul).

### 3.2 Invented labels

None of the following appear verbatim in the documentation; all are encodings introduced by the rule file:

- "Foundation", "Data Plane / Protos", "Domain Core (Internal)", "Control Plane (Agent)", "Interface (API)", "Consumer (Command)" — all six layer names.
- "Layer 1" / "Layer 2" / "Layer 3" / "Layer 4" / "Layer 5" / "Layer 6" — the numeric stratification.
- "Cross-cutting peers at Layer 3" — the framing that lets `connect`, `envoyextensions`, `troubleshoot` coexist with `internal/**`.
- "Pluggability" — the label justifying the symmetric raft↔inmem ban.
- "Harness defense-in-depth" — the framing for the harness deny-lists.
- "Foundation peers" — the framing for the 15-package Foundation membership.
- "intra-agent client/server split" — the rule label. The diagram has the words "CONSUL CLIENT" and "CONSUL SERVERS"; the rule label is a re-phrasing.
- "in-tree harness packages" — the framing for the five `agent/<leaf>/{testutils,testutil,testcommon,validateupstream-test,mock}` deny targets.
- "softer-than-strict-layering interpretation" — the preamble's own phrase for the Foundation→`proto/**` allowance, openly admitting it is an interpretation.
- "[Redundant safety net]" — the description label on the `internal/storage` root naming rule, openly admitting that rule is vacuous today.

### 3.3 Inferred rules

Effectively every rule in the file is inferred. The diagram contributes no import-direction predicates; the rules are inferred from a combination of (a) the package-name listing in `1_hashicorp_consul.txt`, (b) idiomatic Go layering conventions, and (c) the reviewer's prior knowledge of HashiCorp Consul's source organization. Specific examples — each marked clearly as inference:

- **`lib/**` and 14 sibling Foundation rules ↛ {agent, api, internal, command, connect, envoyextensions, troubleshoot}** — inferred from the package names; the doc never says "lib must not import internal".
- **`proto/**` and `proto-public/**` ↛ higher layers** — inferred; the diagram's "Data Plane" label is about runtime traffic, not the proto package.
- **`internal/** ↛ {agent, api, command}`** — inferred from Go's `internal/` visibility convention; the doc does not name `internal`.
- **`internal/resource/** ↛ {agent, api, command}`** — inferred (Review #1 replaced an impossible `shouldOnlyContain` stack here).
- **`internal/storage/raft/** ↔ internal/storage/inmem/**` symmetric ban** — inferred from the sibling-directory layout; the diagram's Raft boxes do not imply an in-memory alternative exists.
- **`connect`, `connect/**`, `envoyextensions/**`, `troubleshoot/**` ↛ {agent, api, command}`** — inferred. The diagram shows "Proxy" boxes but does not classify Go packages.
- **`agent/** ↛ {api, command}`** — inferred. The diagram shows the agent runs on a host but does not say `agent/dns` cannot import `api/watch`.
- **`agent/** ↛ agent/consul/**` (intra-agent client/server)** — *partly* documented (the diagram does separate "CONSUL CLIENT" and "CONSUL SERVERS"). The Go-package mapping (`agent/consul/**` = server, every other `agent/<leaf>/` = client) is the inferred half.
- **`api/** ↛ {internal, agent, command}`** — inferred.
- **`grpcmocks/** ↛ all production layers and harness trees`** — inferred.
- **`command/** ↛ internal/**`** — inferred (the documented expectation that CLI consumes the public Agent/API surface is not in the diagram; it is reviewer-stated).
- **All harness-deny rules** — inferred from naming conventions (`testing/`, `test-integ/`, `testrpc`, `tools/`, `internal/testing/`, `internal/tools/`) and from the five in-tree `*testutil*` / `*test*` / `*mock*` package names.
- **Naming rules: `*Backend` in `internal/storage(/**)`, `*Reconciler` in `internal/controller/**`** — inferred from a codebase scan, not from the documentation. The original `Store` / `Repository` names did not even exist in Consul (Review #1, F16).
- **Functions rules: `maxLines: 80`, `maxParameters: 6` for `internal/**`; `maxReturnValues: 2` for `lib/**`** — pure judgment. No documentation passage suggests these specific bounds; they are coarse proxies for `maxComplexity` (which arch-go does not support; Review #1, F2).
- **Contents rules: `shouldNotContainInterfaces: true` on `proto/**`, `proto-public/**`, `grpcmocks/**`** — inferred from "these trees are generated, hand-written interfaces would be out of place".

### 3.4 Undocumented carve-outs

`arch-go` has no `ignoreDependency` directive analogous to ArchUnit's; carve-outs in this file are expressed as either deliberate omissions from a deny-list, by-design vacuous rules, or preamble-level scope notes. Each carve-out below relies on the reviewer's reasoning, not the document's authority:

- **Foundation members may import `proto/**` and `proto-public/**`** (preamble lines 14–18, lines 140–141). Documentation says nothing about whether `lib` may use generated proto types. Rationale ("they are pure data carriers, not 'higher' logic") is the reviewer's.
- **`agent/consul/**` is matched by the `agent/**` glob as origin but trivially complies because a package never imports itself** (preamble lines 626–629). This relies on undocumented arch-go semantics; if the tool's matcher ever changes to include self-imports, the rule changes meaning silently.
- **The five in-tree harness packages (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`) are deny targets, not deny origins** (preamble lines 1083–1091). The doc does not mention these packages at all; their classification as test-only is name-based.
- **`shouldNotContainFunctions: true` was *not* applied to the three generated/mock trees** (preamble lines 1101–1110) — this is a carve-out from a stricter rule that would have failed CI on every `*.pb.go`. The carve-out is well-justified but is the reviewer's reasoning, not the document's.
- **`threshold.coverage: 0` deliberately disables the package-touch coverage gate** (preamble lines 96–105). Documentation does not require any coverage threshold; the disable is a reviewer choice with a documented follow-up workflow.
- **`internal/storage` root naming rule is intentionally vacuous** (preamble lines 1150–1157, label `[Redundant safety net]`). It is retained for forward compatibility, not because any documented requirement mandates it.
- **`test-integ/**` is the only subtree truly outside any rule's `package:` glob** (preamble lines 60–65). This too is a reviewer judgment; the doc does not say integration tests are out of scope.
- **`proto/private/**`, `sdk/testutil/**`, `lib/testhelpers`, `agent/mock`** were once (incorrectly) labelled as excluded; the corrected scope note (preamble lines 60–67) leaves them in scope. The classification flip itself was a reviewer judgment, not a documented one.

### 3.5 Import-scope conditionality

"Zero violations" is scoped to whatever the build's classpath actually contains. Specifically:

- The scan covers `.go` files under whatever directory is staged as `$(PROJECT_ROOT)/arch-go.yml`'s neighbour and walked by `arch-go --verbose`. The `Makefile` requires `PROJECT_ROOT` to contain `go.mod`, so vendored code outside that module is not covered.
- `test-integ/**` is excluded by *no* `package:` glob naming it as origin (the only subtree about which this is true). Anything under `test-integ/**` could in principle import any production package and produce no `arch-go` finding.
- External Consul modules with their own `go.mod` (e.g., `consul-enterprise`, `consul-k8s`, ecosystem repositories under the same GitHub org) are out of scope by definition.
- `_test.go` files *are* in scope; the rule file relies on the discipline that test helpers crossing layer boundaries are hidden under either build-tags or the documented out-of-scope subtrees. There is no automated check that this discipline has been followed for files written after this run.
- A new top-level package added under `github.com/hashicorp/consul/<newdir>` that does not match any existing glob will simply be invisible to every rule. The Foundation rule template comment (preamble lines 120–142) mitigates this by giving contributors a copy-paste pattern, but does not enforce its use.

### 3.6 Sensitivity untested (per-class judgment calls)

Because `arch-go` is package-glob-only (it does not have ArchUnit's class-level `belongs-to-package-X` predicate), there are no per-class predicates in the rule file — but there are many *per-package* judgment calls. Each one is a place where a Consul committer could disagree:

Foundation membership (15 individual classification decisions):
- `lib`, `lib/**` — classified Foundation.
- `sdk`, `sdk/**` — classified Foundation.
- `types` — classified Foundation.
- `version`, `version/**` — classified Foundation.
- `ipaddr` — classified Foundation.
- `sentinel` — classified Foundation.
- `service_os` — classified Foundation.
- `snapshot` — classified Foundation.
- `tlsutil` — classified Foundation.
- `logging`, `logging/**` — classified Foundation.
- `acl`, `acl/**` — classified Foundation. (Notable: `acl` is also reachable from the runtime "Data Plane" semantically; the YAML treats it purely as a Foundation primitive.)

Layer-3 cross-cutting peers (4 individual decisions):
- `internal/**` — classified Domain Core.
- `connect`, `connect/**` — classified Layer-3 peer (NOT a CLI subdir, despite `command/connect/` existing).
- `envoyextensions/**` — classified Layer-3 peer.
- `troubleshoot/**` — classified Layer-3 peer (with a *narrower* deny-list than the others — only banned from `agent` and `command`, not from `api`, because `command/troubleshoot/*` is the legitimate caller).

Server / client agent partition:
- `agent/consul/**` — classified server-side (the Raft / FSM / state-store side).
- All other `agent/<leaf>/` — classified client-side.

Harness classifications (test-only, no production import allowed):
- `testing/deployer`, `testing/deployer/**`
- `test-integ`, `test-integ/**`
- `testrpc`
- `tools`, `tools/**`
- `internal/testing/**`
- `internal/tools/**`
- `agent/grpc-external/testutils`
- `agent/grpc-middleware/testutil`
- `agent/xds/testcommon`
- `agent/xds/validateupstream-test`
- `agent/mock`

Generated / mock tree classifications:
- `proto/**` — generated, banned from declaring hand-written interfaces.
- `proto-public/**` — generated, same restriction.
- `grpcmocks/**` — mock, same restriction; also banned from production-layer and harness deps.

Storage-backend pluggability decision:
- `internal/storage/raft(/**)` and `internal/storage/inmem(/**)` — classified mutually unaware. The diagram does not show this.

Naming-rule scoping decisions:
- `*Backend` enforced only in `internal/storage` and `internal/storage/**` (root rule labelled redundant).
- `*Reconciler` enforced only in `internal/controller/**`.

Functions-rule bounds:
- `internal/**`: `maxLines: 80`, `maxParameters: 6`.
- `lib/**`: `maxReturnValues: 2`.

Sensitivity is also formally unproven. A passing run shows the rules do not fire on the *current* code; it does not show that a counterfactual bad change (e.g., `lib/file.go` importing `agent/consul/state`) would be detected. Mutation testing has not been run. The rules' false-positive rate is observed to be 0; their false-negative rate is unmeasured.

---

## Section 4 — The strongest claim I can actually support

> **The rule file, the codebase, and my reading of the documentation all agree at a fixed point.**

This is deliberately weaker than "the rule file matches the documentation" because the three-way agreement among `{rules, code, my reading}` leaves open the possibility that *my reading* of a runtime-topology diagram-as-code-layer-spec is wrong. Two corollaries follow strictly from test evidence:

1. Every layer-crossing edge that the codebase currently exercises is either permitted by a layer's deny-list omissions (i.e., the rules positively allow it) or is encoded as Foundation-allowed `proto/**` / `proto-public/**` access — there is no silent suppression via skipped or vacuous rules (the v1.5.4 silent-drop failure mode was specifically closed in Review #3, F1, and is now defended by the Makefile's `Compliance:` sentinel).
2. The single architectural constraint the diagram does textually distinguish (CONSUL CLIENT vs. CONSUL SERVERS) is encoded as the `agent/** ↛ agent/consul(/**)` rule pair. The mapping from "client/server boxes" to "Go package names" is reviewer-supplied; whether the encoding is correct depends on whether `agent/consul` is in fact the only server-side subtree.

---

## Section 5 — What would upgrade confidence

Listed in descending order of impact. None is a precondition to shipping; each would convert a category of judgment into authority.

1. **Review by a HashiCorp Consul committer / maintainer** on the layer-partition decisions enumerated in §3.6. Specifically: confirm-or-deny that (a) `acl/**` is purely Foundation rather than a Layer-3 cross-cutting peer, (b) `connect`/`envoyextensions`/`troubleshoot` are correctly framed as Layer-3 peers rather than belonging to a separate "service-mesh" stratum, (c) `agent/consul/**` is the *only* server-side subtree under `agent/**`, (d) `command/**` is correctly forbidden from importing `internal/**` (this is a strong claim about CLI architecture).
2. **An ADR or design-notes document** that supplements the topology diagram with explicit code-layer policy. The current state is that one runtime-topology JPG is being asked to underwrite 66 import-path rules; an architect-authored markdown document, even a short one, would make most of §3.1–§3.4 disappear.
3. **Mutation testing.** Inject a known-bad import (e.g., add a `_ "github.com/hashicorp/consul/agent/consul/state"` blank import to `agent/dns/dns.go`), confirm `arch-go` fires, then revert and confirm clean. Repeat for one representative per layer pair. This converts "0 false positives observed" into "rules are sensitive on at least these N axes".
4. **Extend the import scope** to cover the currently-excluded `test-integ/**` subtree. It is presently invisible to every rule as origin; if any of its packages drift into being imported by production code, the rule file will not catch it.
5. **Re-enable the coverage threshold** once a baseline is measured. Run `arch-go --json` once, read the actual measured coverage, and pin `threshold.coverage` to `(measured − 2pts)`. The current `0` floor cannot detect a regression in package-touch coverage.
6. **Move naming rules to the v2 structured-map form** (`structsThatImplement: { internal: "*Backend" }`) once every CI environment has been verified on `arch-go/v2`. The current flat-string form is the conservative interoperable encoding; the structured-map form gives more precise scoping of the rule's universe.
7. **Adopt a `gocyclo` / `gocognit` pre-commit hook** to actually enforce the cyclomatic-complexity bound that arch-go cannot. The current `maxLines: 80` + `maxParameters: 6` is a coarse proxy.
8. **Move test-helper trees behind a `//go:build testharness` tag** and run `arch-go -tags=testharness` only for test pipelines. This makes the in-tree harness exclusion structural rather than a manually-maintained deny-target list.

---

## Section 6 — Recommendation

**Ship the rule file with caveats added to its YAML preamble** (`arch-go.yml` lines 1–105 already form a substantial preamble doc-block; the additions below should sit at the top, above `version: 1`, so a reader sees the calibration before the rules).

Caveats to add:

1. **The layer partition (Foundation → Protos → Domain Core → Agent → API → Command) is an operational encoding**, inferred from the package-name listing in `inputs/go/1_hashicorp_consul.txt`, *not* a direct documentation quote. The only architectural document available is `inputs/go/1_hashicorp_consul.jpg`, which is a runtime-topology diagram (Consul Servers in a Raft cluster, Consul Clients on each host, sidecar Proxies, App A / App B; labelled Control Plane / Data Plane). It is silent on Go package layering.
2. **The following carve-outs are reviewer judgments, not documented requirements**, and should be reviewed by a HashiCorp Consul maintainer:
   - Foundation members are permitted to import `proto/**` and `proto-public/**` (softer-than-strict-layering interpretation).
   - The five in-tree harness packages (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`) are deny targets across all production layers.
   - The intra-agent server/client encoding (`agent/consul/**` = server-side, all other `agent/<leaf>/` = client-side).
   - The symmetric `internal/storage/raft ↔ internal/storage/inmem` pluggability ban.
   - `threshold.coverage: 0` is intentionally disabled until a baseline is measured.
3. **Pointer to documentation**: the rules in this file enforce a code-layer policy *inferred from* `inputs/go/1_hashicorp_consul.jpg` and `inputs/go/1_hashicorp_consul.txt`; readers seeking the authoritative architectural specification should consult an ADR or maintainer rather than reading the diagram as a layering contract.

These caveats make the file honest about its own limits so that a future maintainer reading "Total Rules: 66, Succeeded: 66, Failed: 0" does not mistake "tests pass" for "architecture validated". The rule file is shippable in its current form; the caveats are a documentation update only.
