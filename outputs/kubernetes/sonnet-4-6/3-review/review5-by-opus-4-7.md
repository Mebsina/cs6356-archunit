# Adversarial Review #5 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 5

---

## Executive Summary

- **Total documented constraints identified: 25.** Ten high-level invariants come from the Kubernetes "Components" reference page (`https://kubernetes.io/docs/concepts/overview/components/`): control-plane vs. node split; kube-apiserver as the only API surface; etcd persistence; kube-scheduler / kube-controller-manager / cloud-controller-manager / kubelet / kube-proxy as separate binaries; Container runtime as the CRI boundary; addons as separate processes; kubectl/kubeadm as clients. Fifteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`) one-way; Layer-2 peer ban (`pkg/api/**` ↔ `pkg/apis/**`); Layer-3 (registry) → Layer-4 ban; the Layer-4 non-coupling lattice (controller / scheduler / kubelet / proxy / kubeapiserver); Layer-5 controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider / security / securitycontext / serviceaccount / probe / kubectl / kubemark / printers / plugin leaf-isolation; intra-layer fences (kubelet/container, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`); util / apis function-complexity caps.
- **Total rules generated: 152** — 131 `dependenciesRules` + 19 `namingRules` + 2 `functionsRules` (matches the test-report totals).
- **Coverage rate (constraint → rule mapping): ~22/25 on paper, materially less in effective reach.** Round 5 closes four of the seven open findings from round 4: the four Layer-4/5 bare-path rules (`pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`, `pkg/controlplane` — review #4 [F2]); the kubelet manager naming rules now scope to the bare sub-package (review #4 [F3]); the `Storage` naming rule was deleted (review #4 [F4]); and `pkg/api/**` was fanned out per-sub-package to exclude `pkg/api/testing` (review #4 [F5]). The cmd/kubemark per-backend deny treatment from review #4 [F7] is also in place. **But the bare-path insight from rounds 3-4 was applied selectively at the top of each subsystem and was NOT pushed down into the per-sub-package fan-outs.** Every `pkg/controller/<X>/**` rule, every `pkg/scheduler/<X>/**` rule, every `pkg/registry/<X>/**` rule, and every `pkg/volume/<X>/**` rule now silently exempts the bare canonical sub-package — exactly where the canonical controller / scheduler / volume / registry struct lives. The pkg/api fan-out is the only one that did this correctly (it pairs each `<X>/**` with a bare `<X>` rule); the round-5 author did not propagate that pattern to the other four subsystems. Plus three cmd rules (`cmd/cloud-controller-manager/**`, `cmd/kubectl/**`, `cmd/kubectl-convert/**`) are completely vacuous against the supplied package list because those binaries appear only as bare paths in the snapshot.
- **Effective coverage rate from arch-go itself: still 0%.** The test report footer reads `COVERAGE RATE 0% [FAIL]` — byte-for-byte identical to rounds 2, 3, and 4. The Makefile in round 5 is unchanged from round 4 (correctly bash + `pipefail` + `PIPESTATUS[0]` + 30%-floor), but the captured run still scans zero matched packages. The empirical signal is unchanged from round 2 — *four rounds running*. 152 of 152 rules `[PASS]`-ing against zero matched packages is the same vacuous-pass symptom that has gated nothing in the last four submissions.
- **Critical Gaps** (constraints with zero or vacuous enforcement in the current report):
  - **`COVERAGE RATE 0% [FAIL]` is unchanged from rounds 2-4** ([F1]). The Makefile is structurally correct; the run that produced this report still scans zero matching source files. Until this is fixed, every paper finding remains paper.
  - **The bare-path insight was applied at the top of each subsystem but NOT to per-sub-package fan-outs** ([F2]). `pkg/controller/deployment` (the package containing `DeploymentController`), `pkg/controller/job` (containing `JobController`), `pkg/scheduler/framework` (containing the canonical `Plugin` interface), `pkg/scheduler/profile`, `pkg/registry/rbac`, `pkg/registry/resource`, `pkg/volume/csi`, `pkg/volume/iscsi`, and ~60 other canonical bare paths are unconstrained by any Layer-4 / Layer-3 / leaf rule — only by the global `pkg/** → cmd/**` catch-all. Every fan-out of the form `pkg/<subsystem>/<X>/**` silently exempts `pkg/<subsystem>/<X>` itself, which is precisely where the canonical `*Controller` / `Plugin` / `Storage` / `Volume` declarations live. The pkg/api fan-out demonstrates the correct pattern (paired bare + `/**` rules per sub-package); the controller/scheduler/registry/volume fan-outs do not.
  - **Three cmd rules are completely vacuous against this snapshot** ([F3]). `cmd/cloud-controller-manager` (package list line 5), `cmd/kubectl` (line 27), and `cmd/kubectl-convert` (line 28) appear only as bare paths in the supplied package list — none has any sub-package under it. The rules `cmd/cloud-controller-manager/**`, `cmd/kubectl/**`, and `cmd/kubectl-convert/**` therefore match zero packages and pass vacuously regardless of source content. Even the cmd binaries that DO have an `/app` sub-package (cmd/kubelet, cmd/kube-apiserver, cmd/kube-controller-manager, cmd/kube-scheduler, cmd/kube-proxy, cmd/kubeadm, cmd/kubemark) leave the bare entry-point path (`cmd/kubelet/main.go`) outside any rule, so a `main.go` could silently `import "k8s.io/kubernetes/pkg/scheduler/framework"` and pass.
  - **Eleven scheduler/registry per-sub-package rules target packages that do not exist in the supplied snapshot** ([F4], carryover from review #4 [F6]). `pkg/scheduler/{apis,backend,internal}/**` and `pkg/registry/{core,apps,batch,networking,storage,policy,certificates,admissionregistration}/**` are not in the package list (lines 203-213). The rules are therefore predictive — they pass not because the actual code is clean but because the targeted packages are not in the snapshot. Combined with [F2] and [F1], this means the bulk of the registry / scheduler / controller / volume fan-out is not actually enforcing anything against the input.
- **Overall verdict: `FAIL`.** Round 5 closes four open findings from round 4 with surgical YAML edits — that progress is real and visible. But the central insight from the bare-path analysis (`<X>/**` does not match bare `<X>`) was applied at one level of the package tree and missed at the next level down. The pkg/api fan-out from review #4 [F5] — bare + `/**` per sub-package — is the right shape; the controller / scheduler / registry / volume fan-outs were not updated to match, so the canonical `*Controller`, `Plugin`, REST handler, and Volume struct paths in the bare per-sub-package nodes remain structurally unreachable by the Layer-3 / Layer-4 / leaf rules. The empirical 0% coverage gate from rounds 2-4 has not budged. The artifact is now wider on paper (152 vs. 99 rules) and structurally narrower in effective enforcement than the round-4 author intended.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope (carryover from review #4 [F1] / #3 [F1])
Affected Rule / Constraint: All 152 rules; Makefile target `arch-check`.

What is wrong:
The supplied test report still has `COVERAGE RATE 0% [FAIL]` — same as
rounds 2, 3, and 4. The Makefile remains structurally correct from round 4:

  - Lines 28-29: SHELL := /bin/bash; .SHELLFLAGS := -eu -o pipefail -c
  - Lines 41, 46: ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes;
                  ARCH_GO_MIN_COVERAGE := 30
  - Lines 121-123: set -o pipefail; arch-go check | tee; STATUS=$${PIPESTATUS[0]}
  - Lines 133-139: aborts on coverage < 30%

But the test report submitted as input to this round documents a run where:
  (a) coverage is 0%, and
  (b) all 152 rules pass.

If `make arch-check` had run against an actual `k8s.io/kubernetes` checkout,
the COVERAGE RATE would be on the order of 60-90% (arch-go reports the
fraction of scanned packages matched by at least one rule, and the rules
in this YAML cover virtually every pkg/** and cmd/** path in upstream).
0% is the signature of one of:

  (i)   PROJECT_ROOT pointed at a stub directory whose go.mod declares
        `module k8s.io/kubernetes` but contains no Go source files.
  (ii)  The Makefile target was bypassed; `arch-go check` was invoked
        from a directory whose go.mod is not k8s.io/kubernetes.
  (iii) ARCH_GO_MIN_COVERAGE was overridden to 0 at invocation time.
  (iv)  The captured artifact is from a stale pre-coverage-floor run.

In any of those cases, the rule-by-rule [PASS] table is generated BEFORE
the coverage-floor check fires. So the captured artifact shows 152 [PASS]
lines and a footer reading `COVERAGE RATE 0% [FAIL]` at the bottom — and
a casual review treats it as a green run.

This is the FOURTH consecutive round with the same empirical state. Each
round has added more rules (28 → 85 → 99 → 152) without producing a
single empirically demonstrated firing rule. None of the round-2/3/4/5
fixes — the 32-entry controller fan-out, the 17-entry volume fan-out,
the kubelet manager bare-path move, the per-API-group registry fan-out,
the per-sub-package pkg/api fan-out, the cmd-side symmetry — has been
empirically validated.

Why it matters:
Three findings in this report ([F2], [F3], [F4]) are concrete predictions
of how the new round-5 YAML will actually behave once enforcement fires.
Until [F1] is resolved, those predictions cannot be confirmed and the
team cannot tell which of the round-5 additions are doing real work.
The signal-to-noise of the architectural review is gated on this single
operations step.

How to fix it:
This is an operations problem, not a YAML problem. The fix is procedural,
unchanged from review #4 [F1]:

  1. Clone or check out actual upstream `k8s.io/kubernetes` (the module,
     not the docs repository). Verify with `cd <checkout> && go list -m`.
     The output must read exactly `k8s.io/kubernetes`.

  2. Run `make arch-check PROJECT_ROOT=<that-checkout>`. Capture the
     full output.

  3. Confirm the report contains `COVERAGE RATE NN% [PASS]` with NN ≥ 30.

  4. Confirm the rule-by-rule table now contains [FAIL] lines for at least
     the predicted offenders:

       - `pkg/apis/core/validation.ValidatePodSpec` → maxLines=350 violation
         (review #3 [F5], #4 [F8] prediction)
       - `pkg/util/iptables.(*runner).restoreInternal` → maxLines=250
         violation (predicted at lines 2197-2201 of arch-go.yml)
       - `pkg/api/testing` → cross-layer false-positive once fan-out fires
         — wait, round-5 fixed this; pkg/api/testing is now excluded.
         Instead, confirm pkg/api/testing's expected legitimate cross-layer
         imports DO NOT appear in the failure list.

  5. Only after observing those expected violations and either fixing them
     in source or carving narrow exceptions in arch-go.yml should the
     report be treated as a real CI gate.

If you cannot run against full upstream, run against a partial mirror that
contains at minimum:
  - pkg/util/iptables (≥1 file)
  - pkg/apis/core/validation (≥1 file)
  - pkg/controller/{deployment,job,cronjob,statefulset} (canonical
    controllers — needed to verify [F2])
  - pkg/scheduler/framework (needed to verify [F2])
  - pkg/registry/core/pod/storage (needed to verify ex-[F4] is actually
    deleted and not silently re-added)
  - cmd/kubelet, cmd/kube-apiserver, cmd/kubectl (needed to verify [F3])

Until the four steps are completed, the YAML changes from rounds 1-5 are
unverified.
```

```
[F2] SEVERITY: CRITICAL
Category: Coverage Gap | Glob Semantics (extension of #4 [F2], #3 [F2])
Affected Rule / Constraint: ~60 per-sub-package fan-out rules across
controller / scheduler / registry / volume — see below.

What is wrong:
Review #3 [F2] established that arch-go's `**` glob matches one or more
path segments and does NOT match the bare parent path. Round 4 fixed this
at the *top* of each subsystem (added bare paths for `pkg/scheduler`,
`pkg/controller`, `pkg/registry`, `pkg/volume`, leaf subsystems). Round 5
extended that fix to four more (`pkg/kubelet`, `pkg/proxy`,
`pkg/kubeapiserver`, `pkg/controlplane`) and to the kubelet manager naming
rules. Excellent.

But the same insight applies one level down — to the per-sub-package
fan-outs *inside* each top-level subsystem. The round-5 author missed this:

  Controller fan-out (lines 683-1044, 32 rules):
    pkg/controller/bootstrap/**             — bare pkg/controller/bootstrap     UNMATCHED
    pkg/controller/certificates/**          — bare pkg/controller/certificates  UNMATCHED
    pkg/controller/clusterroleaggregation/**— bare pkg/controller/...           UNMATCHED
    pkg/controller/cronjob/**               — bare pkg/controller/cronjob       UNMATCHED
    pkg/controller/daemon/**                — bare pkg/controller/daemon        UNMATCHED
    pkg/controller/deployment/**            — bare pkg/controller/deployment    UNMATCHED  *
    pkg/controller/devicetainteviction/**   — bare pkg/...devicetainteviction   UNMATCHED
    pkg/controller/disruption/**            — bare pkg/controller/disruption    UNMATCHED
    pkg/controller/endpoint/**              — bare pkg/controller/endpoint      UNMATCHED
    pkg/controller/endpointslice/**         — bare pkg/...endpointslice         UNMATCHED
    pkg/controller/endpointslicemirroring/**— bare pkg/...endpointslicemirroring UNMATCHED
    pkg/controller/garbagecollector/**      — bare pkg/...garbagecollector      UNMATCHED
    pkg/controller/history/**               — bare pkg/controller/history       UNMATCHED
    pkg/controller/job/**                   — bare pkg/controller/job           UNMATCHED  *
    pkg/controller/namespace/**             — bare pkg/controller/namespace     UNMATCHED
    pkg/controller/nodeipam/**              — bare pkg/controller/nodeipam      UNMATCHED
    pkg/controller/nodelifecycle/**         — bare pkg/...nodelifecycle         UNMATCHED  *
    pkg/controller/podautoscaler/**         — bare pkg/...podautoscaler         UNMATCHED
    pkg/controller/podgc/**                 — bare pkg/controller/podgc         UNMATCHED
    pkg/controller/replicaset/**            — bare pkg/controller/replicaset    UNMATCHED  *
    pkg/controller/replication/**           — bare pkg/controller/replication   UNMATCHED
    pkg/controller/resourceclaim/**         — bare pkg/...resourceclaim         UNMATCHED
    pkg/controller/resourcepoolstatusrequest/** — bare pkg/...resourcepoolsta...UNMATCHED
    pkg/controller/resourcequota/**         — bare pkg/...resourcequota         UNMATCHED
    pkg/controller/serviceaccount/**        — bare pkg/...serviceaccount        UNMATCHED
    pkg/controller/servicecidrs/**          — bare pkg/controller/servicecidrs  UNMATCHED
    pkg/controller/statefulset/**           — bare pkg/controller/statefulset   UNMATCHED  *
    pkg/controller/storageversiongc/**      — bare pkg/...storageversiongc      UNMATCHED
    pkg/controller/storageversionmigrator/**— bare pkg/...storageversionmigrator UNMATCHED
    pkg/controller/tainteviction/**         — bare pkg/controller/tainteviction UNMATCHED
    pkg/controller/ttl/**                   — bare pkg/controller/ttl           UNMATCHED
    pkg/controller/ttlafterfinished/**      — bare pkg/...ttlafterfinished      UNMATCHED
    pkg/controller/validatingadmissionpolicystatus/** — bare ditto              UNMATCHED

  Scheduler fan-out (lines 1070-1151, 7 rules):
    pkg/scheduler/framework/**     — bare pkg/scheduler/framework               UNMATCHED  *
    pkg/scheduler/profile/**       — bare pkg/scheduler/profile                 UNMATCHED  *
    pkg/scheduler/metrics/**       — bare pkg/scheduler/metrics                 UNMATCHED  *
    pkg/scheduler/util/**          — bare pkg/scheduler/util                    UNMATCHED  *
    pkg/scheduler/apis/**          — bare pkg/scheduler/apis                    UNMATCHED (also non-existent in snapshot — see [F4])
    pkg/scheduler/backend/**       — bare pkg/scheduler/backend                 UNMATCHED (also non-existent — see [F4])
    pkg/scheduler/internal/**      — bare pkg/scheduler/internal                UNMATCHED (also non-existent — see [F4])

  Registry fan-out (lines 537-659, 9 rules):
    pkg/registry/rbac/**           — bare pkg/registry/rbac                     UNMATCHED  *
    pkg/registry/resource/**       — bare pkg/registry/resource                 UNMATCHED  *
    pkg/registry/core/**           — non-existent in snapshot (see [F4])
    pkg/registry/apps/**           — non-existent in snapshot (see [F4])
    pkg/registry/batch/**          — non-existent in snapshot (see [F4])
    pkg/registry/networking/**     — non-existent in snapshot (see [F4])
    pkg/registry/storage/**        — non-existent in snapshot (see [F4])
    pkg/registry/policy/**         — non-existent in snapshot (see [F4])
    pkg/registry/certificates/**   — non-existent in snapshot (see [F4])
    pkg/registry/admissionregistration/** — non-existent in snapshot (see [F4])

  Volume fan-out (lines 1300-1484, 17 rules):
    pkg/volume/configmap/**        — bare pkg/volume/configmap                  UNMATCHED  *
    pkg/volume/csi/**              — bare pkg/volume/csi                        UNMATCHED  *
    pkg/volume/csimigration/**     — bare pkg/volume/csimigration               UNMATCHED  *
    pkg/volume/downwardapi/**      — bare pkg/volume/downwardapi                UNMATCHED  *
    pkg/volume/emptydir/**         — bare pkg/volume/emptydir                   UNMATCHED  *
    pkg/volume/fc/**               — bare pkg/volume/fc                         UNMATCHED  *
    pkg/volume/flexvolume/**       — bare pkg/volume/flexvolume                 UNMATCHED  *
    pkg/volume/git_repo/**         — bare pkg/volume/git_repo                   UNMATCHED  *
    pkg/volume/hostpath/**         — bare pkg/volume/hostpath                   UNMATCHED  *
    pkg/volume/image/**            — bare pkg/volume/image                      UNMATCHED  *
    pkg/volume/iscsi/**            — bare pkg/volume/iscsi                      UNMATCHED  *
    pkg/volume/local/**            — bare pkg/volume/local                      UNMATCHED  *
    pkg/volume/nfs/**              — bare pkg/volume/nfs                        UNMATCHED  *
    pkg/volume/projected/**        — bare pkg/volume/projected                  UNMATCHED  *
    pkg/volume/secret/**           — bare pkg/volume/secret                     UNMATCHED  *
    pkg/volume/util/**             — bare pkg/volume/util                       UNMATCHED  *
    pkg/volume/validation/**       — bare pkg/volume/validation                 UNMATCHED  *

(*)  Confirmed against the supplied package list (lines 87-120 for
     controller, 209-213 for scheduler, 204-206 for registry,
     243-260 for volume). Where the bare path appears in the package
     list and `<X>/**` does not match it, that bare path is a real
     uncovered Go package.

The pkg/api fan-out (lines 241-500) is the model that should have been
followed:

```yaml
  - description: pkg/api/job sub-tree
    package: "k8s.io/kubernetes/pkg/api/job/**"
    shouldNotDependsOn: { internal: [...] }

  - description: pkg/api/job (bare package)
    package: "k8s.io/kubernetes/pkg/api/job"
    shouldNotDependsOn: { internal: [...] }
```

Each sub-package gets a *pair* of rules — one for the bare path, one for
the `/**` sub-tree. This is exactly the round-3 [F2] / round-4 [F2]
insight applied at the second level. It was applied to pkg/api in round 4
[F5] but not propagated to controller / scheduler / registry / volume.

Concrete proof of impact:

  - `pkg/controller/deployment/deployment_controller.go` declares
    `type DeploymentController struct { ... }` and `func (dc *DeploymentController) Run(...)`.
    The package path is `k8s.io/kubernetes/pkg/controller/deployment`
    (bare). Rule `pkg/controller/deployment/**` does NOT match this path.
    Adding `import "k8s.io/kubernetes/pkg/scheduler/framework"` to
    `deployment_controller.go` would silently pass — a Layer-4 lattice
    violation arch-go cannot see.

  - `pkg/scheduler/framework/cycle_state.go`, `pkg/scheduler/framework/types.go`,
    `pkg/scheduler/framework/interface.go` declare the canonical `Plugin`,
    `PluginFactory`, `Framework`, `Handle` types. Package path is the
    bare `k8s.io/kubernetes/pkg/scheduler/framework`. Rule
    `pkg/scheduler/framework/**` does NOT match. Adding
    `import "k8s.io/kubernetes/pkg/controller/garbagecollector"` here
    would silently pass.

  - `pkg/registry/rbac/escalation_check.go` and friends sit in the bare
    `pkg/registry/rbac` package. Rule `pkg/registry/rbac/**` does not
    match. Layer-3 → Layer-4 inversion is unenforced for the canonical
    rbac registry helpers.

  - `pkg/volume/csi/csi_plugin.go` declares `type csiPlugin struct` and
    is the entry point of the CSI volume plugin. Package path is bare
    `pkg/volume/csi`. Rule `pkg/volume/csi/**` does not match.

  - Same pattern repeats for ALL 32 controller sub-packages, all 4
    canonical scheduler sub-packages (framework, profile, metrics, util),
    both registry sub-packages that exist in the snapshot (rbac,
    resource), and all 17 volume sub-packages (most of which are
    single-Go-package leaves, so the `/**` glob matches *nothing at all*
    against this snapshot — see [F4]).

Why it matters:
The Layer-4 non-coupling lattice and the Layer-3 → Layer-4 inversion ban
are the centerpiece architectural rules in this YAML — they encode the
documented "separate binaries" invariant from the Kubernetes Components
page. Round 5 nominally enforces these for ~50 controller / scheduler /
registry / volume sub-packages but actually exempts the canonical bare
path of every one of them. The numerical coverage rate (152 rules,
COVERAGE RATE NN%) will look good on a real run — every rule will report
[PASS] because the bare paths it should have covered are not in its
match set. The defect is invisible in the report's compliance summary.

This is the same defect class as review #3 [F2] (caught and fixed for
the top of each subsystem) and review #4 [F3] (caught and fixed for
kubelet managers). It was not generalized into a universal rule:

   "Every `<X>/**` rule must have a sibling rule on bare `<X>`."

Until that rule is universal, the per-sub-package fan-out approach
introduces *more* coverage gaps than it closes by virtue of replacing
the broad `pkg/controller/**` parent rule (which would match
`pkg/controller/deployment` as a sub-path) with finely-scoped rules
that don't match the bare sub-package.

Worse: the round-5 author DELETED the broad `pkg/controller/**`,
`pkg/scheduler/**`, `pkg/registry/**`, `pkg/volume/**` rules. Those
broad rules WOULD have matched the bare canonical sub-packages because
arch-go's `**` matches one or more segments and `pkg/controller/**`
matches `pkg/controller/deployment` as a one-segment-extension. Replacing
the broad rule with per-sub-package fan-out *loses* that coverage unless
each fan-out entry pairs bare + `/**`.

How to fix it:
Two options.

(A) Pair every per-sub-package rule with a bare-path companion. Same shape
    as pkg/api fan-out. Doubles the rule count for these four subsystems
    (~60 → ~120). Concrete patch shape:

```yaml
  - description: >
      Controller manager sub-tree pkg/controller/deployment.
    package: "k8s.io/kubernetes/pkg/controller/deployment/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Bare pkg/controller/deployment package (where DeploymentController
      lives). Same Layer-4 non-coupling lattice as the /** rule above;
      the `**` glob does not match the bare path.
    package: "k8s.io/kubernetes/pkg/controller/deployment"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

  Repeat for all 32 controller sub-packages, the 7 scheduler sub-packages
  (or just the 4 that exist in the snapshot — see [F4]), the 2 registry
  sub-packages that exist in the snapshot (rbac, resource), and the 17
  volume sub-packages.

(B) Revert to the broad globs `pkg/controller/**`, `pkg/scheduler/**`,
    `pkg/registry/**`, `pkg/volume/**` and accept that the four test
    helpers (`pkg/scheduler/testing`, `pkg/controller/testutil`,
    `pkg/registry/registrytest`, `pkg/volume/testing`) will produce
    expected false positives on their cross-layer imports. Document
    those four expected violations in the YAML and either fix the test
    helpers in upstream (move them to `pkg/.../internal/testing`) or
    carve narrow exceptions per-helper using one rule per helper. This
    was option (b) in review #4 [F6]; the round-5 author retained the
    fan-out from round 4. The maintenance burden of keeping a 32-entry
    controller fan-out / 17-entry volume fan-out manually synchronised
    against upstream — AND extending each entry with a bare-path
    companion — is real. Reverting to four broad rules + four test-helper
    exceptions is much smaller. Concrete patch shape for option (B):

```yaml
  # Replace the 32-entry controller fan-out (lines 668-1044) with:
  - description: >
      Controller manager packages must not import sibling Layer-4
      components or Layer-5 controlplane. Test helper sub-tree
      pkg/controller/testutil is intentionally exempt — see SCOPE OF
      ANALYSIS header. We accept its (small, known) cross-layer imports
      as expected false positives until the helper is moved to
      pkg/controller/internal/testutil. See review #5 [F2].
    package: "k8s.io/kubernetes/pkg/controller/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  # ... same for scheduler/**, registry/**, volume/**
```

Either option closes the gap. (B) is shorter, lower-maintenance, and the
test-helper false positives are tractable; (A) is more defensive but
more code. Pick one and apply it consistently.

After the fix, verify on a real upstream run that the rule-by-rule
report contains lines beginning:
  Packages matching pattern 'k8s.io/kubernetes/pkg/controller/deployment'
  Packages matching pattern 'k8s.io/kubernetes/pkg/scheduler/framework'
  Packages matching pattern 'k8s.io/kubernetes/pkg/volume/csi'
(no `**`). Their presence is the empirical confirmation the bare paths
are now in scope.
```

```
[F3] SEVERITY: CRITICAL
Category: Vacuous Rule | Glob Semantics
Affected Rule / Constraint: cmd rules at lines 1865 (cmd/cloud-controller-manager/**),
1879 (cmd/kubectl/**), 1895 (cmd/kubectl-convert/**); plus bare-path
gaps at lines 1792 (cmd/kube-scheduler/**), 1805 (cmd/kube-proxy/**),
1819 (cmd/kubelet/**), 1837 (cmd/kube-apiserver/**), 1851
(cmd/kube-controller-manager/**), 1910 (cmd/kubeadm/**), 1932 (cmd/kubemark/**).

What is wrong:
Cross-referencing the cmd/** rules against the supplied package list
(lines 5-39):

  cmd/cloud-controller-manager        (line 5 — bare path only)
  cmd/kube-apiserver                  (line 22)
    cmd/kube-apiserver/app            (line 23)
  cmd/kube-controller-manager         (line 24)
    cmd/kube-controller-manager/app   (line 25)
    cmd/kube-controller-manager/names (line 26)
  cmd/kubectl                         (line 27 — bare path only)
  cmd/kubectl-convert                 (line 28 — bare path only)
  cmd/kubelet                         (line 29)
    cmd/kubelet/app                   (line 30)
  cmd/kubemark                        (line 31)
    cmd/kubemark/app                  (line 32)
  cmd/kube-proxy                      (line 33)
    cmd/kube-proxy/app                (line 34)
  cmd/kube-scheduler                  (line 35)
    cmd/kube-scheduler/app            (line 36)
  cmd/kubeadm                         (line 19)
    cmd/kubeadm/app                   (line 20)
    cmd/kubeadm/test                  (line 21)

Three of the rules target binaries that appear ONLY as bare paths in the
snapshot:

  Rule line 1865: package "k8s.io/kubernetes/cmd/cloud-controller-manager/**"
                 — package list shows ONLY bare cmd/cloud-controller-manager.
                 Rule matches ZERO packages.

  Rule line 1879: package "k8s.io/kubernetes/cmd/kubectl/**"
                 — package list shows ONLY bare cmd/kubectl.
                 Rule matches ZERO packages.

  Rule line 1895: package "k8s.io/kubernetes/cmd/kubectl-convert/**"
                 — package list shows ONLY bare cmd/kubectl-convert.
                 Rule matches ZERO packages.

Each of these three rules is a 9-line entry that contributes nothing to
enforcement against this snapshot. Even on a real upstream run, where
e.g. `cmd/kubectl` may be a single-file main package with no
sub-packages, the rule still matches nothing.

For the seven rules that target binaries with `<X>/app` and similar
sub-packages — kubelet, kube-apiserver, kube-controller-manager,
kube-scheduler, kube-proxy, kubeadm, kubemark — the bulk of the wiring
is in `<X>/app`, which IS matched by `cmd/<X>/**`. But the bare
`cmd/<X>` package contains the binary entry point (`main.go`). It is
unmatched. A `main.go` containing
`import "k8s.io/kubernetes/pkg/scheduler/framework"` would silently pass
arch-go for any of these seven binaries.

The bare-path insight ([F2]) applies symmetrically here. Round 5 added
bare-path rules for `pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`,
`pkg/controlplane` (review #4 [F2]) but did not extend the convention
to the cmd binaries. cmd is exactly the layer where "the documented
'separate binaries' invariant" is supposed to be enforced — the bare
binary path is structurally the most important file to constrain, and
it is uncovered.

Why it matters:
The cmd-layer rules (lines 1788-1942) are 16 of the 131 dependency
rules. Three are vacuous on this snapshot (cmd/cloud-controller-manager,
cmd/kubectl, cmd/kubectl-convert); seven leave the bare entry point of
the substantial binaries (cmd/kubelet/main.go, cmd/kube-apiserver/main.go,
etc.) uncovered. Roughly 60% of the cmd-layer enforcement is paper
coverage.

The description claim at lines 119-145 of arch-go.yml advertises a
fully symmetric cmd → pkg fence:

   "kube-apiserver/cloud-controller-manager/kube-controller-manager/
    kube-scheduler/kube-proxy/kubelet binaries may not import each
    other's pkg/** subtrees"

This claim is false for cloud-controller-manager (rule vacuous against
this snapshot) and partially false for the others (bare main.go is
unconstrained).

How to fix it:
Three sub-fixes:

(a) For cmd/cloud-controller-manager, cmd/kubectl, cmd/kubectl-convert
    — replace each `<X>/**` rule with TWO rules: `<X>` and `<X>/**`,
    same as the pkg/api fan-out pattern:

```yaml
  - description: >
      cloud-controller-manager binary tree (cmd/cloud-controller-manager/**)
      ... [existing description]
    package: "k8s.io/kubernetes/cmd/cloud-controller-manager/**"
    shouldNotDependsOn:
      internal: [...]

  - description: >
      Bare cmd/cloud-controller-manager package — main.go entry point.
      Same fence as the /** rule above.
    package: "k8s.io/kubernetes/cmd/cloud-controller-manager"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

  Repeat for cmd/kubectl and cmd/kubectl-convert.

(b) For cmd/kubelet, cmd/kube-apiserver, cmd/kube-controller-manager,
    cmd/kube-scheduler, cmd/kube-proxy, cmd/kubeadm, cmd/kubemark — add
    a bare-path companion rule alongside each existing `<X>/**` rule.
    Same shape as (a). Seven additional 9-line entries.

(c) Alternative (cleaner): replace each pair (bare + `/**`) with a single
    rule using a more permissive glob, if arch-go supports it. arch-go's
    `<X>` and `<X>/**` semantic is fixed; there is no `<X>{,/**}` shortcut.
    So this option is not available. (a) and (b) are the only fixes.

After the fix, verify on a real upstream run that the rule-by-rule
report contains lines beginning:
  Packages matching pattern 'k8s.io/kubernetes/cmd/kubelet'
  Packages matching pattern 'k8s.io/kubernetes/cmd/kubectl'
  Packages matching pattern 'k8s.io/kubernetes/cmd/cloud-controller-manager'
(no `**`).
```

```
[F4] SEVERITY: HIGH
Category: Vacuous Rule | Predictive Coverage (carryover from #4 [F6])
Affected Rule / Constraint: 11 per-sub-package rules at lines 563
(pkg/registry/core/**), 576 (pkg/registry/apps/**), 589 (pkg/registry/batch/**),
601 (pkg/registry/networking/**), 613 (pkg/registry/storage/**),
626 (pkg/registry/policy/**), 638 (pkg/registry/certificates/**),
651 (pkg/registry/admissionregistration/**), 1120
(pkg/scheduler/apis/**), 1132 (pkg/scheduler/backend/**), 1144
(pkg/scheduler/internal/**).

What is wrong:
Round 4 added eight per-API-group registry sub-tree rules (core, apps,
batch, networking, storage, policy, certificates, admissionregistration)
and three scheduler sub-tree rules (apis, backend, internal). None of
these eleven targeted packages appear in the supplied package list:

  Package list lines 203-206 list ONLY:
    pkg/registry              (line 203 — bare)
    pkg/registry/rbac         (line 204)
    pkg/registry/registrytest (line 205 — test helper, intentionally excluded)
    pkg/registry/resource     (line 206)

  Package list lines 208-213 list ONLY:
    pkg/scheduler             (line 208 — bare)
    pkg/scheduler/framework   (line 209)
    pkg/scheduler/metrics     (line 210)
    pkg/scheduler/profile     (line 211)
    pkg/scheduler/testing     (line 212 — test helper, intentionally excluded)
    pkg/scheduler/util        (line 213)

There is no `pkg/registry/core`, no `pkg/registry/apps`, no
`pkg/scheduler/apis`, no `pkg/scheduler/backend`, no `pkg/scheduler/internal`,
etc. in the snapshot.

The header comment at lines 511-514 acknowledges this proactively:

    Sub-trees not explicitly listed here remain structurally unconstrained —
    re-derive this list from `go list ./pkg/registry/...` against an
    upstream checkout when adding new API groups.

But that comment frames the problem as "if the upstream grows, update the
list." The actual current state is the *opposite*: the rules already
include eleven entries that are PREDICTED from upstream knowledge
without being verified against the input. Against the supplied package
list, these eleven rules match zero packages and pass vacuously.

If the supplied package list is a faithful summary of what's in the
target upstream checkout, then 11 of the 152 rules are vacuous (not
counting [F2]'s wider impact). If the package list is a truncated
summary of an upstream checkout that does contain pkg/registry/core
etc., then the rules might fire on a real run — but without an
empirical run ([F1]), we cannot tell.

Why it matters:
This is the same finding as review #4 [F6]. The round-5 author retained
the eleven predictive rules unchanged. Together with [F2], the
combined effect is:

  - Layer-3 enforcement against pkg/registry/core, apps, batch, ...:
    rules pass vacuously because the targeted packages don't exist in
    the snapshot.
  - Even if the targeted packages did exist, [F2] applies — the bare
    pkg/registry/core would be unconstrained because `pkg/registry/core/**`
    does not match it.

So even on a real upstream run where pkg/registry/core/{pod,service,...}
exist, the bare canonical Storage/REST handler files in pkg/registry/core
itself remain uncovered.

How to fix it:
Same as review #4 [F6], updated. Two-part fix:

(a) Re-derive the per-sub-package rule list MECHANICALLY from a real
    upstream checkout of the targeted commit:

```bash
# In a clone of github.com/kubernetes/kubernetes at the version targeted
# by these rules (e.g., master @ 2025-04-26):
go list -e ./pkg/registry/... 2>/dev/null \
  | grep -v '^k8s.io/kubernetes/pkg/registry/registrytest' \
  | sort -u

go list -e ./pkg/scheduler/... 2>/dev/null \
  | grep -v '^k8s.io/kubernetes/pkg/scheduler/testing' \
  | sort -u

go list -e ./pkg/controller/... 2>/dev/null \
  | grep -v '^k8s.io/kubernetes/pkg/controller/testutil' \
  | sort -u

go list -e ./pkg/volume/... 2>/dev/null \
  | grep -v '^k8s.io/kubernetes/pkg/volume/testing' \
  | sort -u
```

   Use the resulting lists as ground truth. Annotate the YAML with the
   commit SHA / date the lists were derived against:

```yaml
# Per-sub-package fan-out lists below were derived against
# kubernetes/kubernetes commit ${SHA} on ${DATE} via
#   go list -e ./pkg/{registry,scheduler,controller,volume}/...
# Re-derive when bumping the target version.
```

(b) Or, much simpler: revert to broad globs (option (B) of [F2]). One
    `pkg/registry/**` rule replaces eight predictive sub-tree rules and
    their bare-path companions; one `pkg/scheduler/**` rule replaces
    the three predictive scheduler sub-tree rules; one
    `pkg/controller/**` rule replaces the 32-entry controller fan-out;
    one `pkg/volume/**` rule replaces the 17-entry volume fan-out. The
    four test-helper sub-trees (registrytest, scheduler/testing,
    controller/testutil, volume/testing) become four documented expected
    false positives.

Recommend (b). The fan-out approach has now produced two carryover
findings ([F2] = bare-path systemic gap, [F4] = predictive non-existent
packages). The maintenance burden of keeping ~70 fan-out entries
manually synchronised against upstream — AND adding bare-path
companions per [F2] — exceeds the benefit (excluding 4 test helpers).
Option (b) is the pragmatic fix.
```

```
[F5] SEVERITY: MEDIUM
Category: Coverage Gap | Glob Semantics (extension of [F2] to intra-layer rules)
Affected Rule / Constraint: rule at line 1760 (pkg/kubelet/container/**)
and rule at line 1775 (pkg/proxy/util/**).

What is wrong:
Two intra-layer rules use `<X>/**` and miss the bare path:

  Line 1760: package "k8s.io/kubernetes/pkg/kubelet/container/**"
             - bare pkg/kubelet/container is at line 147 of the package
               list. Rule does not match it.

  Line 1775: package "k8s.io/kubernetes/pkg/proxy/util/**"
             - bare pkg/proxy/util is at line 201 of the package list.
               Rule does not match it.

The kubelet/container rule's stated purpose (line 1752-1759) is to keep
"the kubelet container abstraction layer" from importing the HTTP
server, config loader, or volume manager. The canonical Runtime
interface is declared in pkg/kubelet/container/runtime.go (the bare
package), not in any sub-package — so the rule's primary target is
unmatched. (The rule's own description acknowledges that some of these
forbidden directions are already enforced by Go's import-cycle
prohibition, so the impact is partly muted in practice. But [F2]'s
generic principle still applies.)

The proxy/util rule's stated purpose is to keep proxy utilities from
importing specific backend implementations. Helpers and shared types
that live directly in pkg/proxy/util/utils.go etc. — the bare package
— are the most natural place for a backend import to creep in. The
rule does not see them.

Why it matters:
Smaller-impact analogues of [F2]. Adding `import "k8s.io/kubernetes/pkg/kubelet/server"`
to a file in the bare pkg/kubelet/container package would silently
pass — same kind of breach as the broader [F2] case, but the surface
area is smaller because pkg/kubelet/container has fewer files and the
canonical Runtime interface itself is unlikely to grow such an import.

How to fix it:
Add bare-path companions, mirroring the [F2] (A) fix:

```yaml
  - description: >
      Bare pkg/kubelet/container package (where the canonical Runtime
      interface lives in pkg/kubelet/container/runtime.go). Same intra-
      layer fence as the /** rule below.
    package: "k8s.io/kubernetes/pkg/kubelet/container"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/server/**"
        - "k8s.io/kubernetes/pkg/kubelet/config/**"
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"

  - description: >
      The kubelet container abstraction sub-tree
      (pkg/kubelet/container/**) ... [existing description]
    package: "k8s.io/kubernetes/pkg/kubelet/container/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/server/**"
        - "k8s.io/kubernetes/pkg/kubelet/config/**"
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"

  - description: >
      Bare pkg/proxy/util package. Same implementation-agnostic fence
      as the /** rule below.
    package: "k8s.io/kubernetes/pkg/proxy/util"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"

  - description: >
      The proxy utility tree (pkg/proxy/util/**) ... [existing description]
    package: "k8s.io/kubernetes/pkg/proxy/util/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"
```

If [F2] is fixed via option (B) (revert to broad globs at the top
level), this rule pair can be left as-is — the broad pkg/kubelet/** /
pkg/proxy/** parent rules already cover the bare paths transitively.
But option (B) doesn't speak to the intra-layer fences specifically;
the intra-layer fences need their own bare-path companions either way.
```

```
[F6] SEVERITY: MEDIUM
Category: Coverage Gap (minor inconsistency)
Affected Rule / Constraint: rule at lines 170-207 (pkg/util/** deny list).

What is wrong:
The pkg/util/** deny list (lines 178-207) blocks pkg/util/** from
importing the leaf-component subsystems explicitly:

  pkg/api/**, pkg/apis/**
  pkg/registry, pkg/registry/**
  pkg/controller, pkg/controller/**
  pkg/kubelet/**
  pkg/proxy/**
  pkg/scheduler, pkg/scheduler/**
  pkg/kubeapiserver/**
  pkg/controlplane/**
  pkg/volume, pkg/volume/**
  pkg/credentialprovider, pkg/credentialprovider/**
  pkg/kubectl, pkg/kubectl/**
  pkg/kubemark, pkg/kubemark/**
  pkg/printers, pkg/printers/**
  pkg/security, pkg/security/**
  pkg/securitycontext, pkg/securitycontext/**
  pkg/serviceaccount, pkg/serviceaccount/**

The leaf-component subsystem `pkg/probe` (a separate rule at line 1606)
is missing from the pkg/util/** deny list. pkg/probe is at the same
architectural level as pkg/security, pkg/securitycontext, etc., and its
absence from the util-layer deny list is inconsistent.

The description at lines 170-176 says util "must not import any other
pkg/** layer above it (including api, apis, registry, leaf-component
subsystems such as volume, credentialprovider, kubectl, kubemark,
printers, security, securitycontext)." Probe is a leaf-component
subsystem with its own dedicated leaf-isolation rule (line 1606); the
description should include it and the deny list should reference it.

Why it matters:
Low-impact gap. pkg/util packages are unlikely to import pkg/probe in
practice (the dependency graph would be backwards — probe uses util,
not the other way around). But the asymmetry between rules (probe gets
its own leaf-isolation rule, but is invisible to the util deny) is a
maintainability concern: a future maintainer reading the deny list
will model probe differently from the other leaf subsystems.

How to fix it:
Add pkg/probe to the pkg/util/** deny list, alongside the other leaf
subsystems:

```yaml
  - description: >
      Shared utility packages (pkg/util/**) are the lowest internal
      layer. ... including ... probe ... (leaf-component subsystems).
    package: "k8s.io/kubernetes/pkg/util/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/api/**"
        - "k8s.io/kubernetes/pkg/apis/**"
        # ... (existing entries) ...
        - "k8s.io/kubernetes/pkg/probe"          # added
        - "k8s.io/kubernetes/pkg/probe/**"       # added
        - "k8s.io/kubernetes/pkg/serviceaccount"
        - "k8s.io/kubernetes/pkg/serviceaccount/**"
```

Update the description text to explicitly enumerate "probe" alongside
the other leaf-component subsystems.
```

```
[F7] SEVERITY: MEDIUM
Category: Vacuous Rule (carryover from review #4 [F8] / #3 [F5])
Affected Rule / Constraint: functionsRules (lines 2204-2238) —
pkg/util/** maxReturnValues=5/maxLines=250 and pkg/apis/**
maxReturnValues=8/maxLines=350.

What is wrong:
Carryover from review #4 [F8]. Both function rules `[PASS]` in the
report. With COVERAGE RATE 0%, this means either (a) the calibration is
correct AND the rules are scanning real upstream code, or (b) they
scanned zero functions. Cannot disambiguate.

The author updated the description text in round 4 to predict the
specific upstream functions expected to violate post-coverage:

  pkg/apis/core/validation.{ValidatePodSpec, ValidatePodTemplateSpec,
                            ValidatePersistentVolumeSpec}
  pkg/apis/networking/validation.{validateIngressBackend,
                                  ValidateNetworkPolicySpec}
  pkg/util/iptables.(*runner).restoreInternal

Round 5 retained these predictions unchanged. They cannot be confirmed
until [F1] is resolved.

Why it matters:
Same as review #4 [F8] / #3 [F5]. Function-complexity rules are the
most likely class to catch quality regressions when correctly wired,
and the most likely to produce noise when not. A rule that passes on
0% coverage is identical in CI signal to no rule at all.

How to fix it:
Resolve [F1] (operations fix). Re-run against actual upstream. Confirm
the predicted functions surface as violations. If they pass without
violation, the limits are too generous and should be tightened until
they bite a known offender.

If the run shows that no function in pkg/util/** exceeds maxLines=250,
EITHER:
  (a) the cap is too generous — tighten to a value below the largest
      observed function (e.g., 200 if the largest observed util fn is
      198 lines);
  (b) the test predictions were wrong — investigate whether
      iptables.(*runner).restoreInternal has been refactored upstream.

Same algorithm for pkg/apis/**:
  (a) tighten to bite ValidatePodSpec OR
  (b) investigate the gap.
```

```
[F8] SEVERITY: LOW
Category: Description Inaccuracy
Affected Rule / Constraint: top-level `description:` block (lines 116-160),
header SCOPE OF ANALYSIS comment (lines 46-114), Layer-4 prose
(lines 19-32).

What is wrong:
Round 5 added bare-path companions for kubelet/proxy/kubeapiserver/
controlplane (review #4 [F2] — closed) and made the kubelet manager
naming rules use the bare path (review #4 [F3] — closed). The
description block was correctly updated to reflect those fixes
(lines 124-145).

But the description block continues to describe the controller /
scheduler / registry / volume per-sub-package fan-outs without
mentioning that the bare canonical sub-packages of those fan-outs
are uncovered ([F2] above). The reader forms the wrong mental model
of which paths are constrained.

Specific phrasing issues:

  Lines 124-128: "The lattice is enforced for both the bare parent path
  of each Layer-4 subsystem and its per-sub-package fan-out, so the
  canonical implementations (e.g., pkg/scheduler/scheduler.go in the
  bare pkg/scheduler package, pkg/kubelet/kubelet.go in the bare
  pkg/kubelet package, and pkg/proxy/types.go in the bare pkg/proxy
  package) are not silently exempt."

  This is *true at one level* (the top-level subsystem's bare path is
  now covered). It is *false at the next level down*: the canonical
  implementations in `pkg/controller/deployment/deployment_controller.go`
  (bare pkg/controller/deployment), `pkg/scheduler/framework/types.go`
  (bare pkg/scheduler/framework), `pkg/registry/rbac/escalation_check.go`
  (bare pkg/registry/rbac), and `pkg/volume/csi/csi_plugin.go` (bare
  pkg/volume/csi) ARE silently exempt.

  Lines 146-149: "Test-helper sub-packages (...) are intentionally
  excluded by positive sub-tree enumeration (review #4 [F5] added
  pkg/api/testing to the per-sub-package fan-out)."

  Mostly accurate, but obscures the cost: positive sub-tree enumeration
  excluded *all* the test helpers AND inadvertently the bare canonical
  sub-packages of every other sub-tree ([F2]).

The header SCOPE block at lines 46-114 enumerates "First-party paths
covered by NO `package:` glob below" — vendor, generated, hack, build,
cluster, test, build-tooling cmd binaries, leaf utility packages with no
specific rule. It does NOT list the ~60 bare per-sub-package nodes that
[F2] identifies as uncovered.

Why it matters:
A maintainer reading the prose forms an incorrect mental model of
coverage. The Critical Gap section of [F2] above is the actionable
finding; this is the documentation companion.

How to fix it:
After [F2] is fixed (option A or option B), the description and SCOPE
block become accurate without further edits if option (B) is chosen,
or need a one-line addition for option (A) ("the per-sub-package fan-
out includes a bare-path companion for each sub-package, mirroring the
pkg/api convention from review #4 [F5]").

Until [F2] is fixed, add a disclaimer at the top of the description:

```yaml
description: >
  Enforces the Kubernetes component architecture. ... [existing prose]
  ...
  KNOWN GAP — review #5 [F2]: the per-sub-package fan-outs for
  pkg/controller, pkg/scheduler, pkg/registry, and pkg/volume use
  `<X>/**` globs without bare-path companions. The canonical
  pkg/controller/<X>, pkg/scheduler/<X>, pkg/registry/<X>, pkg/volume/<X>
  packages — where the canonical *Controller / Plugin / Storage / Volume
  declarations live — are therefore unconstrained. The pkg/api fan-out
  (review #4 [F5]) shows the correct pattern: pair each `<X>/**` rule
  with a bare `<X>` rule.
```

And update the SCOPE block at lines 81-99 to list the bare per-sub-
package nodes under a "UNINTENDED — KNOWN GAP" sub-heading so a future
maintainer sees them.

After [F2] is fixed (whichever option), strip the disclaimer.
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

The structural progress between rounds 4 and 5 is real and visible:

- Bare-path companions added for `pkg/kubelet`, `pkg/proxy`,
  `pkg/kubeapiserver`, `pkg/controlplane` (review #4 [F2] — closed).
- Kubelet manager naming rules switched to bare-path scope, matching the
  canonical interface declarations (review #4 [F3] — closed).
- The `Storage` simple-name registry naming rule was deleted, avoiding
  the predicted cascade of REST-handler false positives (review #4 [F4]
  — closed).
- pkg/api/** replaced with per-sub-package fan-out, excluding
  pkg/api/testing (review #4 [F5] — closed). This fan-out is the
  *correct* shape: bare + `/**` per sub-package.
- cmd/kubemark deny list narrowed from broad pkg/proxy/** to per-backend
  denies (review #4 [F7] — closed).

These five fixes are good. The rule count grew from 99 to 152 with no
descriptions left behind. But two systemic patterns were introduced or
left in place:

1. **The pkg/api fan-out pattern (bare + `/**` per sub-package) was not
   propagated** to the pkg/controller, pkg/scheduler, pkg/registry, and
   pkg/volume fan-outs. Result: ~60 bare canonical sub-package paths
   are unconstrained — exactly the paths where the canonical
   `*Controller`, `Plugin`, REST handler, and Volume struct live. This
   replaces a top-level coverage gap (which round 4 closed) with a
   one-level-deeper coverage gap that round 5 does not see ([F2]).

2. **Three cmd rules are vacuous against this snapshot** —
   `cmd/cloud-controller-manager/**`, `cmd/kubectl/**`,
   `cmd/kubectl-convert/**` match zero packages because those binaries
   appear only as bare paths in the package list. Seven other cmd rules
   leave the binary's bare entry-point uncovered ([F3]).

3. **The empirical 0% coverage gate from rounds 2-4 has not budged.**
   For the fourth consecutive round, every rule passes vacuously
   against zero matched packages ([F1]). Three rounds of sophisticated
   YAML editing have not produced a single empirically demonstrated
   firing rule.

**Order of fixes** (highest leverage first):

1. **[F1]** — re-run `make arch-check PROJECT_ROOT=/path/to/upstream/k8s.io/kubernetes`
   and capture the report. Confirm `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
   The Makefile is correct; what's missing is the run. **This is the
   single highest-leverage fix in the entire 5-round series.**

2. **[F2]** — choose option (A) or option (B):
   - (A) add bare-path companions to all ~60 per-sub-package fan-out
     entries (controller, scheduler, registry, volume). Mirrors the
     pkg/api pattern from review #4 [F5]. Doubles the affected rule count.
   - (B) revert to broad globs (`pkg/controller/**`, `pkg/scheduler/**`,
     `pkg/registry/**`, `pkg/volume/**`) and accept four documented
     test-helper false positives. Drops ~60 rules.
   Recommended: (B). Smaller, lower-maintenance, no recurring
   "did the new sub-package get added to the YAML?" question.

3. **[F3]** — add bare-path companions to all 10 cmd rules (or at least
   the three vacuous ones — cmd/cloud-controller-manager,
   cmd/kubectl, cmd/kubectl-convert).

4. **[F4]** — re-derive the predictive sub-tree lists from a real
   `go list ./pkg/{registry,scheduler,controller,volume}/...` against
   the targeted upstream commit (or fold into [F2] option B by
   deleting the predictive entries entirely).

5. **[F5]** — add bare-path companions for `pkg/kubelet/container` and
   `pkg/proxy/util` intra-layer rules.

6. **[F6]** — add `pkg/probe` and `pkg/probe/**` to the pkg/util/**
   deny list for consistency with the other leaf-component subsystems.

7. **[F7]** — empirically verify the function-complexity caps fire
   (post-[F1]).

8. **[F8]** — clean up description prose and SCOPE comment to match the
   actual rule coverage after [F2] / [F3] are fixed.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:
- `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The rule-by-rule report contains lines beginning
  `Packages matching pattern 'k8s.io/kubernetes/pkg/controller/deployment'`,
  `'.../pkg/scheduler/framework'`, `'.../pkg/registry/rbac'`,
  `'.../pkg/volume/csi'` (no `**`) — empirical confirmation that [F2]
  is closed for the canonical bare paths.
- The cmd-layer report contains lines beginning
  `Packages matching pattern 'k8s.io/kubernetes/cmd/kubelet'`,
  `'.../cmd/kubectl'`, `'.../cmd/cloud-controller-manager'` (no `**`)
  — empirical confirmation that [F3] is closed.
- The function rules surface ≥ 1 violation in `pkg/apis/core/validation`
  (predicted: ValidatePodSpec).
- The kubelet manager naming rules surface ≥ 1 struct each (predicted:
  `volumeManager`, `imageManager`, `containerManagerImpl`, etc., all of
  which already end in `Manager` — so the rules pass on real code, not
  vacuously).

Until those four empirical conditions are met, this report should not
be a CI gate; it is a cosmetic banner. The round-5 YAML is materially
more sophisticated than the round-2 YAML, but the empirical state is
unchanged.

---

**End of Review #5.**
