# Adversarial Review #1 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 1

---

## Executive Summary

- **Total documented constraints identified: 17** — 6 component-isolation constraints derived from the Kubernetes "Components" reference page (control-plane vs. node, kube-apiserver as the only API surface, cloud-controller-manager optionality, container runtime as third-party CRI boundary, kube-proxy as optional, addons as separate processes), plus 11 layer/peer-isolation invariants encoded in the file's own header comment (Layer 1 util → Layer 6 cmd, parallel-component non-coupling, cmd→pkg one-way direction, plus the four enumerated "excluded" subtrees).
- **Total rules generated: 28** — 17 `dependenciesRules` + 4 `contentsRules` + 5 `namingRules` + 2 `functionsRules` (matches the test-report tallies).
- **Coverage rate: ~5/17 constraints have an effective, non-vacuous rule.** Of the 17 documented constraints, only the four parallel-component peer-isolation rules (kubelet, scheduler, proxy, kubeapiserver) and the controlplane→cmd rule fire actual `dependenciesRules` checks. The remaining 12 are either incompletely encoded (asymmetric peer denies, missing layers) or expressed exclusively via `contentsRules` / `namingRules` / `functionsRules` whose YAML schema arch-go silently turns into empty constraint lists (see [F1] and the report lines `should comply with []` / `should have []` / `should complies with []`).
- **Critical Gaps** (constraints with zero or vacuous enforcement at the time the report was produced):
  - **All 4 `contentsRules`**, **all 5 `namingRules`**, and **both `functionsRules` are vacuous**. The arch-go test report literally prints `should comply with []` / `should complies with []` / `should have []` for each of them — the constraint list is empty because the YAML keys used (`shouldNotContainAnyOf:`, `shouldOnlyContain:`, `structs.shouldMatchAny:`, `interfaces.shouldMatchAny:`, `functions.shouldNotHaveMoreThanAnyOf:`) are not part of the arch-go schema. The PASSes are no-ops.
  - **`pkg/controller/**` deny list is asymmetric** with respect to `pkg/scheduler/**` and `pkg/kubeapiserver/**`. Scheduler→controller is forbidden; controller→scheduler is **not**. Kubeapiserver→controller is forbidden; controller→kubeapiserver is **not**. The header explicitly classifies these as parallel non-coupling component layers, so the gap is real.
  - **Layer-1 (`pkg/util/**`) does not deny Layer-2 (`pkg/apis/**`, `pkg/api/**`)**, and **Layer-2 (`pkg/apis/**`) does not deny Layer-2 peer (`pkg/api/**`)**, contradicting the file's own bottom-to-top hierarchy in the header.
  - **`pkg/api/**` deny list omits `pkg/registry/**`** while the parallel `pkg/apis/**` rule denies it. There is no documented justification for the asymmetry; if Layer 2 is foundational, both rules should mirror.
  - **`pkg/controlplane/**` only denies `cmd/**`**, which is an architecturally pointless direction (controlplane importing cmd is a build-impossibility in idiomatic Go layouts and effectively never happens). The *real* upper-bound — controlplane must not import `kubelet`/`proxy` (node-side) — is unenforced.
  - **`pkg/volume/**`, `plugin/pkg/auth/**`, `pkg/credentialprovider/**`, `pkg/securitycontext`, `pkg/security/**`, `pkg/printers/**`** — entire subsystems with no rule at all. The package list contains 47+ packages under `pkg/volume/**` and `pkg/credentialprovider/**`; none are matched by any `package:` glob in the YAML.
  - **`cmd/kube-controller-manager`, `cmd/kube-apiserver`, `cmd/cloud-controller-manager`, `cmd/kubeadm`, `cmd/kubectl`** have no `dependenciesRules` entry. The diagram explicitly enumerates kube-apiserver, kube-controller-manager, cloud-controller-manager, kubectl, and kubeadm as first-class binaries; the YAML constrains only kube-scheduler, kube-proxy, and kubelet.
  - **`cmd/kube-scheduler` rule targets the bare main package**, not `cmd/kube-scheduler/app` where the actual wiring (and any cross-binary coupling) lives. Bare `cmd/X` packages in the kubernetes layout are typically two-line `func main() { app.NewXCommand().Execute() }` files; constraining them is mechanically vacuous.
- **0% Coverage rate from arch-go itself**: the report footer reads `COVERAGE RATE 0% [FAIL]`. Even granting the convention that arch-go's "coverage" is a heuristic, 0/N packages reaching any rule is a smoke alarm — combined with 11/28 rules having empty constraint lists (see [F1]), the test run is **structurally unable to detect violations**. Treating this report as a green light would be a false positive at the CI level.
- **Overall verdict: `FAIL`.**

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Vacuous Rule | Glob/Schema Syntax Error
Affected Rule / Constraint: All 4 `contentsRules`, all 5 `namingRules`, both `functionsRules` (11 of 28 generated rules).

What is wrong:
The YAML keys used for the contents, naming, and functions rules are not part of arch-go's
schema (neither fdaines/arch-go v1.5.x — which the Makefile pins via
`go install github.com/fdaines/arch-go@latest` — nor the arch-go/arch-go v2 fork). Specifically:

1. `contentsRules` (lines 357–406) use:
       shouldNotContainAnyOf:
         - functions:
             - "main"
   and
       shouldOnlyContain:
         - interfaces: {}
   The schema actually expects flat boolean keys at the rule level:
       shouldOnlyContainStructs: true
       shouldOnlyContainInterfaces: true
       shouldNotContainStructs: true
       shouldNotContainInterfaces: true
       shouldNotContainFunctions: true
       shouldNotContainMethods: true
   The nested-list shape used here is silently parsed into an empty constraint list.

2. `namingRules` (lines 410–492) use:
       structs:
         shouldMatchAny:
           - ".*Controller$"
   and
       interfaces:
         shouldMatchAny:
           - ".*Manager$"
   The schema actually expects:
       interfaceImplementationNamingRule:
         structsThatImplement: "<interface name>"
         shouldHaveSimpleNameStartingWith: "<prefix>"
         shouldHaveSimpleNameEndingWith: "<suffix>"
   The `structs.shouldMatchAny` / `interfaces.shouldMatchAny` shape is not an arch-go key.

3. `functionsRules` (lines 497–523) use:
       functions:
         shouldNotHaveMoreThanAnyOf:
           - return: 5
   The schema actually expects flat top-level integer keys:
       maxParameters: 3
       maxReturnValues: 3
       maxLines: 100
       maxPublicFunctionPerFile: 3

The arch-go test report is the smoking gun. Every contents/naming/functions rule prints
literally:
    [PASS] - Functions in packages matching pattern '...' should have []
    [PASS] - Packages matching pattern '...' should comply with []
    [PASS] - Packages matching pattern '...' should complies with []
The empty `[]` is the parsed (i.e. extracted) constraint list. There is nothing to violate,
so every rule passes regardless of source.

Why it matters:
Eleven of twenty-eight rules — 39% of the entire ruleset, including every single naming
convention, every contents constraint, and both function-complexity guards — are not
checking anything. The architecture document specifically calls out the controller suffix
convention and the manager-based kubelet subsystem; both are silently disabled. The 100%
compliance line in the test report is therefore meaningless: any source change can ship
without these eleven checks ever objecting. A `func main()` accidentally added to
`pkg/apis/core` would not be caught; a struct named `Foo` placed in `pkg/controller/job`
would not be caught; a 200-line return-everywhere validator in `pkg/apis/admission` would
not be caught.

How to fix it:
Rewrite all eleven rules using the documented arch-go v1.5.x schema. Concretely:

```yaml
# contentsRules — flat boolean keys, not nested lists
contentsRules:
  - description: API type packages must not contain main entry points.
    package: "k8s.io/kubernetes/pkg/apis/**"
    shouldNotContainFunctions: true   # if you really want to forbid all functions
    # OR (more useful): forbid main-in-library by relying on Go itself —
    #   `func main` only compiles in `package main`, so this rule is effectively
    #   covered by Go's own build rules and may be deleted.

  - description: Scheduler framework defines extension-point interfaces only.
    package: "k8s.io/kubernetes/pkg/scheduler/framework"
    shouldOnlyContainInterfaces: true

# namingRules — interfaceImplementationNamingRule with structsThatImplement
namingRules:
  - description: Reconciler implementations in pkg/controller/** must end with Controller.
    package: "k8s.io/kubernetes/pkg/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Interface"           # name of the controller interface
      shouldHaveSimpleNameEndingWith: "Controller"

  - description: Kubelet manager structs must end with Manager.
    package: "k8s.io/kubernetes/pkg/kubelet/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Manager"
      shouldHaveSimpleNameEndingWith: "Manager"

# functionsRules — flat integer keys
functionsRules:
  - description: Util functions are simple helpers.
    package: "k8s.io/kubernetes/pkg/util/**"
    maxReturnValues: 5
    maxLines: 100

  - description: API validation functions must compose, not branch.
    package: "k8s.io/kubernetes/pkg/apis/**"
    maxReturnValues: 8
    maxLines: 200
```

Verify each rewritten rule produces a non-empty constraint list in the `arch-go check`
output (look for `should match X` or `should have at most N`, never `[]`).
Re-run on the actual k8s.io/kubernetes module and inspect the violation count — the F2
function rule in particular (`pkg/apis/** maxReturnValues: 8`) is *expected* to fail
against real Kubernetes (e.g. `validation.ValidatePodSpec`, `ValidateStatefulSetSpec`
have far more than eight return points). If it doesn't fail, the rule is still vacuous.
```

```
[F2] SEVERITY: CRITICAL
Category: Coverage Gap | Semantic Error (asymmetric peer isolation)
Affected Rule / Constraint: Header layer 4 ("parallel component layers ... must not depend on each other"); rules at YAML lines 164–192 (`pkg/controller/**` and `pkg/scheduler/**`).

What is wrong:
The five "parallel component" subsystems — controller, scheduler, kubelet, proxy,
kubeapiserver — must form a fully symmetric non-coupling lattice. The generated rules do
not. Concretely:

| Source            | Denies controller | Denies scheduler | Denies kubelet | Denies proxy | Denies kubeapiserver | Denies controlplane |
|-------------------|-------------------|------------------|----------------|--------------|----------------------|---------------------|
| `pkg/controller`  |        —          |   **MISSING**    |       ✓        |      ✓       |    **MISSING**       |          ✓          |
| `pkg/scheduler`   |        ✓          |       —          |       ✓        |      ✓       |    **MISSING**       |          ✓          |
| `pkg/kubelet`     |        ✓          |       ✓          |       —        |      ✓       |        ✓             |          ✓          |
| `pkg/proxy`       |        ✓          |       ✓          |       ✓        |      —       |        ✓             |          ✓          |
| `pkg/kubeapiserv` |        ✓          |       ✓          |       ✓        |      ✓       |        —             |   **MISSING**       |

Three asymmetries:
- `pkg/controller/** → pkg/scheduler/**` is permitted (rule at line 170 omits scheduler).
- `pkg/controller/** → pkg/kubeapiserver/**` is permitted (rule at line 170 omits kubeapiserver).
- `pkg/scheduler/** → pkg/kubeapiserver/**` is permitted (rule at line 185 omits kubeapiserver).
- `pkg/kubeapiserver/** → pkg/controlplane/**` is permitted (rule at line 236 omits controlplane).

Why it matters:
A controller package that takes a direct import on a scheduler internal type — for
example `pkg/controller/podautoscaler` calling into
`k8s.io/kubernetes/pkg/scheduler/framework` — is the exact "cross-binary coupling" the
header rules are advertised to prevent. The controller-manager and the scheduler are
separate binaries by design (kube-controller-manager vs kube-scheduler); a
controller→scheduler import means the controller-manager build pulls in the scheduler
plugin graph, which is precisely the architectural drift this YAML claims to forbid.
Likewise for controller→kubeapiserver (controller-manager talks to the API server via
clientset, never via direct package import) and scheduler→kubeapiserver.

How to fix it:
Make every parallel-component rule list the other four siblings AND `controlplane`.

```yaml
  - description: Controller must not import sibling component layers.
    package: "k8s.io/kubernetes/pkg/controller/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"          # ADD
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"      # ADD
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: Scheduler must not import sibling component layers.
    package: "k8s.io/kubernetes/pkg/scheduler/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"      # ADD
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: kubeapiserver support must not import controlplane assembly.
    package: "k8s.io/kubernetes/pkg/kubeapiserver/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"       # ADD
```

(Verify against the real graph first; if Kubernetes today actually has, say, a
controller→kubeapiserver import you'd be locking in, file a normalization issue or use a
narrow `allow:` style override rather than weakening the rule.)
```

```
[F3] SEVERITY: CRITICAL
Category: Coverage Gap | Wrong Layer
Affected Rule / Constraint: Header Layer 5 — "Control Plane (pkg/controlplane/**) ... Depends on API, kubeapiserver, controller, and scheduler layers." Rule at line 247.

What is wrong:
The `pkg/controlplane/**` rule denies only `cmd/**`. cmd→pkg is a one-way import
direction in any well-formed Go layout (cmd packages contain `func main` and are leaf
nodes in the import graph), so `pkg/controlplane/**` importing `cmd/**` is a build-time
impossibility for non-cyclical reasons. The rule is *de facto* vacuous.

Meanwhile the layer description says controlplane is allowed to depend on
"API, kubeapiserver, controller, and scheduler" — the implicit upper bound is that it
**must not depend on `kubelet`, `proxy`, or anything in `pkg/kubelet/**` /
`pkg/proxy/**`**. That bound is unenforced. A controlplane package that imports
`pkg/kubelet/types` to hard-code a node concept would pass.

Why it matters:
Control-plane packages live on the apiserver / controller-manager binaries and must not
take a node-side dependency on `kubelet` or `proxy`. The rule as written enforces a
direction that the Go compiler already enforces and ignores the direction the documentation
explicitly forbids.

How to fix it:
Replace the `cmd/**` deny with the actual upper bounds. The cmd/** check, if you really
want belt-and-braces, is harmless to keep alongside.

```yaml
  - description: >
      Control plane assembly packages may compose API, controller, scheduler,
      and kubeapiserver layers, but must not import node-side components.
    package: "k8s.io/kubernetes/pkg/controlplane/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/cmd/**"                # OK to keep, but documents the
                                                     # impossible direction
```
```

```
[F4] SEVERITY: HIGH
Category: Coverage Gap (Layer 1 / Layer 2 hierarchy)
Affected Rule / Constraint: Header bottom-to-top hierarchy: Layer 1 util → Layer 2 apis/api → Layer 3 registry → Layer 4 components → Layer 5 controlplane → Layer 6 cmd. Rules at lines 92–125.

What is wrong:
The "Shared Utilities" rule on line 97 denies controller/kubelet/proxy/scheduler/
kubeapiserver/controlplane/registry but does not deny `pkg/apis/**` or `pkg/api/**`.
The header lists util as Layer 1 and apis/api as Layer 2, so by the file's own rules a
util package importing an API type is a layer inversion. Likewise the "API Types" rule on
line 116 does not deny `pkg/api/**`, even though the header treats `pkg/api` and
`pkg/apis` as separate Layer-2 peers (line 18 vs line 30); peers are explicitly required
to not depend on each other in the parallel-component bullet, but the analogue is missing
for Layer 2.

Why it matters:
A shared utility (e.g. a new helper added under `pkg/util/labels`) that grows a real
import on `pkg/apis/core/v1` to "just check a typed selector" is exactly the kind of
slow rot architectural enforcement is meant to catch. Today, no rule fires.

How to fix it:
```yaml
  - description: >
      Shared utility packages are the lowest internal layer. They must not import any
      higher pkg/** layer including api/apis types.
    package: "k8s.io/kubernetes/pkg/util/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/api/**"            # ADD
        - "k8s.io/kubernetes/pkg/apis/**"           # ADD
        - "k8s.io/kubernetes/pkg/registry/**"
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      pkg/apis/** is a Layer-2 peer of pkg/api/** and must not import it.
    package: "k8s.io/kubernetes/pkg/apis/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/api/**"            # ADD
        - "k8s.io/kubernetes/pkg/registry/**"
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

(Test the rules against the actual graph before committing. If today's Kubernetes really
does have a `pkg/util/X → pkg/apis/...` edge, decide whether to fix the source or carve a
narrow exception — but do not lock in the inversion silently.)
```

```
[F5] SEVERITY: HIGH
Category: Coverage Gap (entire subsystems unconstrained)
Affected Rule / Constraint: Whole-component coverage; package list contains `pkg/volume/**` (19 packages), `pkg/credentialprovider/**` (3), `plugin/pkg/auth`, `pkg/security/**`, `pkg/securitycontext`, `pkg/printers/**`, `pkg/probe/**`, `pkg/serviceaccount`, `pkg/auth/nodeidentifier`, `pkg/cluster/ports`, `pkg/capabilities`, `pkg/certauthorization`, `pkg/client/conditions`.

What is wrong:
Sweeping every `package:` glob in arch-go.yml against the package list shows that the
following first-party Go packages are matched by **no rule** (so they are invisible to
arch-go and to architectural enforcement entirely):

- `k8s.io/kubernetes/pkg/volume/**` (csi, csimigration, configmap, downwardapi, emptydir,
  fc, flexvolume, git_repo, hostpath, image, iscsi, local, nfs, projected, secret, util,
  validation) — 19 packages, none of which are constrained against importing controller,
  kubelet, proxy, scheduler, or controlplane.
- `k8s.io/kubernetes/pkg/credentialprovider/**` — 3 packages.
- `k8s.io/kubernetes/plugin/pkg/auth`.
- `k8s.io/kubernetes/pkg/security/**` and `k8s.io/kubernetes/pkg/securitycontext`.
- `k8s.io/kubernetes/pkg/printers/**`, `k8s.io/kubernetes/pkg/probe/**`,
  `k8s.io/kubernetes/pkg/serviceaccount`, `k8s.io/kubernetes/pkg/routes`,
  `k8s.io/kubernetes/pkg/client/conditions`, `k8s.io/kubernetes/pkg/client/tests`,
  `k8s.io/kubernetes/pkg/auth/nodeidentifier`, `k8s.io/kubernetes/pkg/capabilities`,
  `k8s.io/kubernetes/pkg/certauthorization`, `k8s.io/kubernetes/pkg/cluster/ports`,
  `k8s.io/kubernetes/pkg/features`, `k8s.io/kubernetes/pkg/fieldpath`,
  `k8s.io/kubernetes/pkg/kubectl`, `k8s.io/kubernetes/pkg/kubemark`.

Why it matters:
`pkg/volume` is a *major* subsystem used by the kubelet (CSI drivers, in-tree volume
plugins) and is exactly the kind of leaf module that should not back-import
`pkg/controller/**` or `pkg/scheduler/**`. Today, an `in-tree volume plugin` accidentally
importing `pkg/controller/job` would pass arch-go. The 0% coverage line in the report is
a direct consequence of patterns like `pkg/util/**`, `pkg/apis/**`, `pkg/registry/**`,
`pkg/scheduler/**`, etc. matching only a fraction of `pkg/**`.

How to fix it:
Either (a) extend explicit `package:` globs to each unconstrained subsystem with an
appropriate deny list, or (b) add a single broad "no pkg/** package may import cmd/**"
catch-all and per-subsystem peer rules. Concretely:

```yaml
  - description: Volume plugins must not depend on control-plane components.
    package: "k8s.io/kubernetes/pkg/volume/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/cmd/**"

  - description: Credential providers are leaf utilities for the kubelet only.
    package: "k8s.io/kubernetes/pkg/credentialprovider/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/cmd/**"

  # Belt-and-braces: nothing under pkg/** may import cmd/**.
  - description: pkg/** packages must never import cmd entry points.
    package: "k8s.io/kubernetes/pkg/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/cmd/**"
```

The last rule subsumes the existing `pkg/controlplane/** → cmd/**` rule and replaces it
with one global guarantee, which is what the architectural narrative is actually trying
to express.
```

```
[F6] SEVERITY: HIGH
Category: Coverage Gap (cmd-side binaries)
Affected Rule / Constraint: Diagram-stated binaries (kube-apiserver, kube-controller-manager, cloud-controller-manager, kubectl, kubeadm) plus the file's own header that names them; rules at lines 320–352.

What is wrong:
The cmd-layer rules only target `cmd/kube-scheduler`, `cmd/kube-proxy`, and `cmd/kubelet`.
The architecture page on which this YAML is based (image
`5_kubernetes_kubernetes_page-0001.jpg`) explicitly lists kube-apiserver,
kube-controller-manager, and cloud-controller-manager as control-plane binaries, and
kubectl/kubeadm are documented in the file's own Layer 6 comment (lines 7–10). None of
these binaries have a rule. In particular:

- `cmd/kube-controller-manager` may import `cmd/kube-scheduler/app` or `pkg/scheduler/**`
  with no objection, despite the entire purpose of the parallel-component isolation
  framing.
- `cmd/kube-apiserver` may import `pkg/kubelet/**` directly without firing any rule.
- `cmd/cloud-controller-manager` is unconstrained even though the diagram marks it as
  *optional* and out-of-tree-friendly — the very case where reaching back into in-tree
  packages should be flagged.

Why it matters:
Cross-binary coupling is the most expensive class of architectural drift to undo, because
each `cmd/X` becomes the import root of its compiled binary. A wrong import in
`cmd/kube-apiserver/app/server.go` means the apiserver binary now drags in the entire
node-side graph, which is a real historical category of regression in this codebase.

How to fix it:
Add symmetric rules for the missing binaries:

```yaml
  - description: kube-apiserver entry point must not import node-side packages.
    package: "k8s.io/kubernetes/cmd/kube-apiserver"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"

  - description: kube-controller-manager entry point must not import sibling binaries.
    package: "k8s.io/kubernetes/cmd/kube-controller-manager"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"

  - description: cloud-controller-manager must not import in-tree controller packages.
    package: "k8s.io/kubernetes/cmd/cloud-controller-manager"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: kubectl is a client; must not import server-side packages.
    package: "k8s.io/kubernetes/cmd/kubectl"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
```
```

```
[F7] SEVERITY: HIGH
Category: Overly Narrow (cmd target package shape)
Affected Rule / Constraint: Rules at lines 324, 335, 347 (the three cmd/<binary> rules).

What is wrong:
Each rule uses `package: "k8s.io/kubernetes/cmd/kube-scheduler"` (singular, no `/**`).
In the kubernetes layout, `cmd/kube-scheduler/scheduler.go` is a five-line `main` that
delegates to `cmd/kube-scheduler/app`. All real coupling — argument parsing, flag
registration, server wiring — happens in `cmd/kube-scheduler/app/server.go` and friends.
The current pattern matches only the bare main package, where direct cross-component
imports are rare to nonexistent.

Why it matters:
The rule looks like it forbids the kube-scheduler binary from importing controller code,
but it actually forbids only the trivial `func main` shim from doing so. A
`cmd/kube-scheduler/app/options/options.go` adding a wrong-binary import would not
match the pattern and would not fire.

How to fix it:
Use `/**` so the rule covers the whole binary tree:

```yaml
  - description: kube-scheduler binary tree must not import controller/kubelet/proxy.
    package: "k8s.io/kubernetes/cmd/kube-scheduler/**"     # was: cmd/kube-scheduler
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"

  - description: kube-proxy binary tree must not import controller/scheduler/kubelet.
    package: "k8s.io/kubernetes/cmd/kube-proxy/**"         # was: cmd/kube-proxy
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"

  - description: kubelet binary tree must not import controlplane/kubeapiserver/scheduler.
    package: "k8s.io/kubernetes/cmd/kubelet/**"            # was: cmd/kubelet
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
```

(Note: this is also why the test report's PASS for these three rules is unsurprising —
the bare `cmd/kube-scheduler` package likely has nothing but a `main` calling the app
package, so the rule was vacuously satisfied even before [F1] is considered.)
```

```
[F8] SEVERITY: MEDIUM
Category: Semantic Error (header lies about exclusions)
Affected Rule / Constraint: Header comment "EXCLUDED / UNCONSTRAINED PACKAGES" lines 37–73; rules at lines 152, 170, 185.

What is wrong:
The header comment claims four in-tree test-helper packages are excluded:

    - k8s.io/kubernetes/pkg/scheduler/testing
    - k8s.io/kubernetes/pkg/controller/testutil
    - k8s.io/kubernetes/pkg/volume/testing
    - k8s.io/kubernetes/pkg/registry/registrytest

In practice the YAML has *no exclusion mechanism* (no `excludedPackages:`,
no `excludedFiles:`, no Makefile `--exclude-files`). Three of those four packages
are matched by glob:

- `pkg/scheduler/testing`        is matched by `pkg/scheduler/**` (line 185)
- `pkg/controller/testutil`      is matched by `pkg/controller/**` (line 170)
- `pkg/registry/registrytest`    is matched by `pkg/registry/**`  (line 152)

Only `pkg/volume/testing` happens to escape because no rule covers `pkg/volume/**` at all
(see [F5]).

Why it matters:
- These packages legitimately import controller/kubelet/proxy/scheduler internals because
  they exist precisely to test cross-layer interactions. Once [F1] is fixed and the rules
  start firing, these packages will produce false positives.
- More fundamentally, the comment is a documentation lie — a future maintainer reading
  the header believes there is exclusion logic that does not exist. This is the worst kind
  of "as-documented" defect.

How to fix it:
Either mechanically encode the exclusions, or delete the misleading comment.

Option A — mechanical exclusion via a per-rule allow list (arch-go does not support package
exclusion at the rule level, so this is approximated with a sibling rule):

```yaml
# Document and accept: test-helper packages are intentionally permissive.
# These globs intentionally exclude *_test.go-style helpers from production rules.
# arch-go v1 has no `excludedPackages`, so the cleanest mitigation is to keep the
# helper paths out of the *target* glob:

  - description: Scheduler component (excluding test helpers).
    package: "k8s.io/kubernetes/pkg/scheduler/{framework,profile,metrics,util,..}/**"
    # listed positively to avoid matching `pkg/scheduler/testing`
    shouldNotDependsOn:
      internal:
        - ...
```

Option B — strip the misleading header comment until exclusion is mechanically
encoded, and accept the false positives in test helpers (with a CI bypass).
This is the recommended option until arch-go grows native exclusion.
```

```
[F9] SEVERITY: MEDIUM
Category: Overly Narrow (intra-layer single-package rules)
Affected Rule / Constraint: Rules at lines 265, 279, 293, 309 (`pkg/scheduler/framework`, `pkg/kubelet/container`, `pkg/kubelet/types`, `pkg/proxy/util`).

What is wrong:
All four intra-layer rules target a single package without a `/**` suffix. For example:

    package: "k8s.io/kubernetes/pkg/scheduler/framework"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/profile"
        - "k8s.io/kubernetes/pkg/scheduler/metrics"

The arch-go pattern matches the literal package `pkg/scheduler/framework` only — not its
real sub-packages such as `pkg/scheduler/framework/plugins/...`,
`pkg/scheduler/framework/runtime`, etc. Most of the framework's substance lives in the
sub-packages. A `pkg/scheduler/framework/plugins/foo` importing `pkg/scheduler/profile`
would not match this rule.

Likewise:
- `pkg/kubelet/container` is one package; the rule misses no siblings *only* because
  `container` is a leaf in this codebase. The single-package shape is brittle: when a
  sub-package is added (e.g. `container/v2/`), it silently slips out of scope.
- `pkg/kubelet/types`: same shape, same leaf-only behavior.
- `pkg/proxy/util`: rule targets the literal `pkg/proxy/util` only, missing legitimate
  utility sub-trees (none exist today, but the rule is one rename away from being silent).

Why it matters:
These rules look like layered intra-layer guarantees, but they are point-fixes with no
forward compatibility. Refactor or restructure under any of those four packages and the
guarantee disappears without notice.

How to fix it:
Add `/**` to every intra-layer rule and verify it still passes. Where a rule is
genuinely package-specific (e.g. `pkg/kubelet/types` is a leaf and you mean exactly that
package), add a comment that says so explicitly:

```yaml
  - description: Scheduler framework (and all sub-packages) must not back-import metrics or profile.
    package: "k8s.io/kubernetes/pkg/scheduler/framework/**"   # was: framework
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/profile"
        - "k8s.io/kubernetes/pkg/scheduler/metrics"

  - description: kubelet container abstractions must not depend on serving / config / volume manager.
    package: "k8s.io/kubernetes/pkg/kubelet/container/**"     # was: container
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/server/**"
        - "k8s.io/kubernetes/pkg/kubelet/config/**"
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"

  - description: kubelet/types is a leaf type-only package (no sub-packages by design).
    package: "k8s.io/kubernetes/pkg/kubelet/types"            # leaf — keep as-is
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/server/**"
        - "k8s.io/kubernetes/pkg/kubelet/kuberuntime/**"
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
        - "k8s.io/kubernetes/pkg/kubelet/config/**"

  - description: proxy/util must not import any backend implementation.
    package: "k8s.io/kubernetes/pkg/proxy/util/**"            # was: util
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"
```
```

```
[F10] SEVERITY: MEDIUM
Category: Vacuous Rule (`main` in library packages)
Affected Rule / Constraint: contentsRules C1, C2, C3 (lines 362–393) — "must not contain main()" in pkg/apis/**, pkg/util/**, pkg/registry/**.

What is wrong:
Even after [F1] is fixed (i.e. assume the schema were corrected to the working
`shouldNotContainFunctions: true` form), the *intent* of the three "no main" rules is
already enforced by the Go compiler. A `func main()` is only valid in `package main`,
and none of `pkg/apis/...`, `pkg/util/...`, `pkg/registry/...` declare `package main` —
they declare `package <leaf>`. Adding a `func main()` to a non-`main` package is a
compile error, not just a lint violation. The rule cannot trigger even on a deliberately
malicious commit.

Why it matters:
These three rules occupy 30+ lines of the contents block and produce three lines of "[PASS]"
in the report, conveying a sense of protection that the Go compiler already provides
unconditionally. They take the place of contents rules that *would* be useful (e.g.
"pkg/apis/** must not contain `init()` functions that mutate package state",
"pkg/registry/** must not declare panic-able package-level vars", "pkg/util/** must not
import generated openapi types").

How to fix it:
Delete C1/C2/C3 (or repurpose to non-trivial contents constraints). For example:

```yaml
  # If the intent was "no godoc-undocumented exported types in pkg/apis":
  - description: pkg/apis must declare only typed package contents.
    package: "k8s.io/kubernetes/pkg/apis/**"
    shouldNotContainMethods: false    # keep methods (validators) but...
    shouldNotContainFunctions: false  # ...adjust to your actual policy
```

(Verify against arch-go schema before merging — the right replacement is whatever
constraint you actually want to enforce; my suggestion is to remove unless something
substantive is intended.)
```

```
[F11] SEVERITY: MEDIUM
Category: Overly Broad (naming convention applied to entire subtree)
Affected Rule / Constraint: namingRules N1 (line 415) — "All exported struct types in pkg/controller/** must be suffixed with 'Controller'."

What is wrong:
Even if [F1] were fixed and the rule fired, the assertion is wrong on its face. `pkg/controller/**`
contains many exported struct types that are *not* controllers and *should not* be named
`*Controller`:
- worker structs: `Worker`, `Pool`
- option/config structs: `Options`, `Config`, `Spec`
- helpers and utilities: `Cache`, `Indexer`, `Informer`, `Reconciler` (the in-package
  reconciliation helper, distinct from the controller façade), `Lister`
- event handlers: `Handler`, `Listener`, `Watcher`

Forcing every exported struct in `pkg/controller/cronjob`, `pkg/controller/job`,
`pkg/controller/garbagecollector`, etc. to end in `Controller` would produce hundreds of
false positives.

Why it matters:
The architecture page does say controllers should be discoverable, but a *type-level*
suffix rule on every struct in the sub-tree is the wrong granularity. arch-go v1's
`interfaceImplementationNamingRule` is precisely designed to scope the suffix
requirement to *implementations of a given interface*, which is what's actually wanted
here.

How to fix it:
Scope the rule to types that implement the controller interface (or substitute the
correct interface name). With the schema fix from [F1]:

```yaml
  - description: >
      Structs in pkg/controller/** that implement the Controller interface
      must be suffixed with 'Controller'. (Helper, options, and worker structs
      are not constrained.)
    package: "k8s.io/kubernetes/pkg/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Interface"      # the canonical controller interface
                                              # in pkg/controller — verify the exact
                                              # name in the codebase before merging
      shouldHaveSimpleNameEndingWith: "Controller"
```

Apply the same scoping fix to N3 (proxy `Proxier`/`Runner`/`Provider`/`Handler`/`Config`),
N4 (kubelet `Manager` and friends), and N5 (registry `Registry`/`Storage`/`Strategy`/
`Interface`). All four are currently shotgun globs that will produce massive false
positives once [F1] is corrected.
```

```
[F12] SEVERITY: MEDIUM
Category: Overly Broad (contents rule)
Affected Rule / Constraint: contentsRules C4 (line 398) — "pkg/scheduler/framework should only contain interfaces."

What is wrong:
The scheduler framework legitimately exports many *structs* alongside its interfaces:
`CycleState`, `WaitingPod`, `QueuedPodInfo`, `Resource`, `NodeInfo`,
`PluginToStatus`, etc. They are not implementation backends to be relegated to plugin
sub-packages — they are the framework's data carriers and have to live next to the
interfaces they thread through.

Why it matters:
Once [F1] is fixed and `shouldOnlyContainInterfaces: true` actually fires, the rule will
flag every struct in the framework, none of which are architectural mistakes. The team
would have to either disable the rule or move the data carriers to a separate package,
neither of which is a worthwhile outcome.

How to fix it:
Either drop C4 entirely or replace it with a more precise constraint:

```yaml
# Option 1: Drop C4. The "framework only contains interfaces" framing is wrong.

# Option 2: Forbid only struct types whose names suggest concrete plugin
#           implementations (anti-impl-leak). arch-go does not support this directly;
#           consider a custom static check or relax the rule.

# Option 3: If the goal is "no Plugin implementations in framework", scope by interface:
  - description: >
      pkg/scheduler/framework declares interfaces and supporting data carriers; concrete
      Plugin implementations must live in framework/plugins/**, not framework root.
    package: "k8s.io/kubernetes/pkg/scheduler/framework"
    interfaceImplementationNamingRule:
      structsThatImplement: "Plugin"
      shouldHaveSimpleNameStartingWith: "_disallowed_"   # forces zero matches in
                                                          # framework root
```

(Option 3 is awkward; Option 1 is cleaner.)
```

```
[F13] SEVERITY: LOW
Category: Glob/Module Scope (path matching)
Affected Rule / Constraint: All `package:` patterns; module path declared in `go.mod`.

What is wrong:
Every `package:` glob is module-prefixed: `k8s.io/kubernetes/pkg/util/**` etc. This is
correct for arch-go and matches the `module k8s.io/kubernetes` declaration in the
upstream go.mod. However, the Makefile copies arch-go.yml to `$(PROJECT_ROOT)` and runs
`arch-go check` from there. If `PROJECT_ROOT` is left at its default (`$(CURDIR)` =
the directory containing the Makefile, i.e. `outputs/kubernetes/sonnet-4-6/`), there is
no `go.mod` and the Makefile's own check (`if [ ! -f $(PROJECT_ROOT)/go.mod ]`)
short-circuits with an error. The 0% coverage line in the report suggests arch-go
nevertheless ran in a context where no `pkg/util/**` package was found — which is the
expected outcome if arch-go was run inside the wrong module, or if the patterns failed
to match anything.

Why it matters:
The report is structurally indistinguishable between "the rules are correct but were run
in the wrong module" and "the rules are wrong and run in the right module." Both produce
100% compliance and 0% coverage. Without a separate verification (e.g. running on a
known-violating commit), the report is not a meaningful CI signal.

How to fix it:
Add a Makefile sanity check that the `go.mod` actually declares the expected module path
and that arch-go found at least one matching package:

```make
arch-check: install-arch-go
    @cd "$(PROJECT_ROOT)" && \
        MOD=$$(go list -m); \
        if [ "$$MOD" != "k8s.io/kubernetes" ]; then \
            echo "[arch-go] FAIL: expected module k8s.io/kubernetes, got $$MOD"; \
            exit 1; \
        fi
    @cd "$(PROJECT_ROOT)" && $(ARCH_GO_BIN) check; \
        STATUS=$$?; \
        ...
```

And add an integration smoke test that runs arch-go against a deliberately violating fork
to confirm a non-vacuous FAIL is producible. (No source change to arch-go.yml itself.)
```

```
[F14] SEVERITY: LOW
Category: Redundancy / Documentation
Affected Rule / Constraint: Header text vs. enforced rules.

What is wrong:
The header layer chart is detailed and accurate to the kubernetes documentation, but
the enforcement coverage table below repeatedly diverges from the chart — most clearly
in the "EXCLUDED / UNCONSTRAINED PACKAGES" block, which lists eight categories
(`vendor/**`, `test/**`, `pkg/generated/**`, `hack/**`, `build/**`, `cluster/**`,
`third_party/**`, `vendorlang.org/**`) as "excluded" though arch-go has no exclusion
mechanism in this file. The reader is led to believe the file does more than it does.

Why it matters:
Misleading documentation in a generated config is a classic source of future
mis-investments — a maintainer assumes the author handled the test exclusion and writes
a new rule that "now also" excludes them, leading to inconsistencies.

How to fix it:
Replace the EXCLUDED block with one of:
- A statement that arch-go matches packages by `package:` glob only and that any path
  not present in *some* glob is implicitly out of scope.
- A "CI-side exclusion" callout if exclusion is performed by a wrapper script (it
  isn't, today, per the Makefile).

```yaml
# CORRECTED HEADER:
#
# SCOPE OF ANALYSIS
#
#   arch-go scans every Go package in the module declared by `go.mod`. There is no
#   per-package exclusion mechanism in this file; packages are exempt from rules
#   only by virtue of not being matched by any `package:` glob below. Test helpers,
#   vendored code, and generated code are **not** explicitly excluded — they are
#   simply outside the rule globs. Adding a glob that matches them will subject
#   them to the rule.
#
```
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

## Recommendation

**Do not ship this configuration.** Three of the four rule families (contents, naming,
functions — eleven of twenty-eight rules) are vacuous due to schema errors documented in
[F1]; the dependency rule family contains four asymmetries ([F2], [F3], [F4]) and one
whole-subsystem coverage gap ([F5]) that a real violation could slip through; the
cmd-side rules cover three of the documented binaries while leaving the other six
unconstrained ([F6]) and target the wrong package shape for those they do cover ([F7]).

**Order of fixes** (highest leverage first):

1. **[F1]** — rewrite contents/naming/functions rules to the correct arch-go schema.
   Confirm the report no longer prints `should comply with []` / `should have []`.
2. **[F2], [F3], [F4]** — close the dependency-rule asymmetries. After this step the
   report's compliance number (whatever it lands on) is meaningful.
3. **[F5]** — add coverage for `pkg/volume/**`, `pkg/credentialprovider/**`, and the
   long tail of unmatched first-party packages, or add a single broad
   `pkg/** must not import cmd/**` catch-all.
4. **[F6], [F7]** — extend cmd-side rules to all binaries with `/**` patterns.
5. **[F8]–[F14]** — clean up overly narrow / overly broad / redundant rules and
   re-baseline the report against a known-violating commit to confirm it can FAIL.

Re-run `arch-go check` against the upstream `k8s.io/kubernetes` checkout and confirm:
- `Coverage` rises to a non-zero value (the upstream module has thousands of packages;
  an empirically reasonable target after these fixes is **>= 30%**).
- The functionsRules max-return-values rule for `pkg/apis/**` (after [F1]) actually
  produces violations against `validation.ValidatePodSpec` and similar — that is the
  empirical proof the rule is no longer vacuous.

Until both criteria are met, the test report is not a CI gate; it is a cosmetic banner.

---

**End of Review #1.**
