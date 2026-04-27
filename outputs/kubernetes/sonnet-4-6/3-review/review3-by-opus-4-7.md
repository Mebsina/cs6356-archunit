# Adversarial Review #3 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 3

---

## Executive Summary

- **Total documented constraints identified: 25.** Ten high-level invariants come from the Kubernetes "Components" reference page (control-plane vs. node split, kube-apiserver as the only API surface, kube-scheduler / kube-controller-manager / cloud-controller-manager / kubelet / kube-proxy as separate binaries, container-runtime CRI boundary, etcd persistence, addons as separate processes, kubectl as a client). Fifteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`) one-way; Layer-2 peer ban (`pkg/api/**` ↔ `pkg/apis/**`); Layer-3 (registry) → Layer-4 ban; the symmetric Layer-4 non-coupling lattice; controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider / security / securitycontext / serviceaccount / probe / kubectl / kubemark / printers / plugin leaf-isolation; intra-layer fences (kubelet/container, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`, `Storage`); util / apis function-complexity caps.
- **Total rules generated: 85** — 68 `dependenciesRules` + 15 `namingRules` + 2 `functionsRules` (matches the test report's totals).
- **Coverage rate (constraint → rule mapping): ~22/25** documented constraints have a rule that *would* fire if arch-go were actually scanning `k8s.io/kubernetes`. The gap below the line of full coverage is concentrated in three structural defects: (a) bare parent-package exclusion introduced by the per-sub-package fan-out; (b) asymmetric `cmd/kube-apiserver/**` fence; (c) `cmd/kubemark/**` has no rule at all.
- **Effective coverage rate from arch-go itself: still 0%.** The test report footer reads `COVERAGE RATE 0% [FAIL]` — identical to review #2. The Makefile in this round was hardened with `ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes` and `ARCH_GO_MIN_COVERAGE := 30`, but the report submitted as input to this review still shows 0% coverage and 100% compliance. Either the test was run by a method that bypassed the Makefile, or the Makefile's coverage gate was disabled when the report was captured. Either way, every `[PASS]` line in the report is a paper claim — no rule has been demonstrated to fire against the upstream module yet.
- **Critical Gaps** (constraints with zero or vacuous enforcement at the time the report was produced):
  - **`COVERAGE RATE 0% [FAIL]` is unchanged from review #2** ([F1]). 85 of 85 rules `[PASS]`-ing against zero matched packages is the exact symptom flagged in review #2, and the remediation written into the Makefile has not been demonstrated. The report is structurally indistinguishable from a vacuous run.
  - **The per-sub-package fan-out introduced bare-package gaps** ([F2]). The YAML now has 32 rules under `pkg/controller/<name>/**`, 4 under `pkg/scheduler/<name>/**`, 2 under `pkg/registry/<name>/**`. arch-go's `**` glob does NOT match the bare parent path (the YAML itself acknowledges this — lines 124-125 list both `pkg/kubectl` and `pkg/kubectl/**`). So bare `pkg/scheduler` (where the canonical `Scheduler` struct and `Run` method live), bare `pkg/controller` (where the controller framework helpers live), and bare `pkg/registry` (top-level registry helpers) are NOT matched by any rule of their parent layer. A `pkg/scheduler/scheduler.go` that adds `import "k8s.io/kubernetes/pkg/kubelet/types"` would silently pass.
  - **`cmd/kube-apiserver/**` is asymmetric** ([F3]). It denies only `pkg/kubelet/**` and `pkg/proxy/**`. It does NOT deny `pkg/scheduler/**` or `pkg/controller/**`, even though kube-scheduler and kube-controller-manager are documented as separate binaries (header Layer 6 lines 7-12) that must not be dragged into the apiserver binary. By contrast, `cmd/kube-controller-manager/**` denies all four siblings plus controlplane and kubeapiserver. The asymmetry contradicts the top-level `description:` claim of cmd-side cross-binary symmetry.
  - **`cmd/kubemark/**` has no rule** ([F5]). The package list (line 31-32) shows `cmd/kubemark` and `cmd/kubemark/app` exist as a first-party Kubernetes binary. Every other documented binary (kube-apiserver, kube-controller-manager, kube-scheduler, kube-proxy, kubelet, cloud-controller-manager, kubectl, kubectl-convert, kubeadm) has a dedicated cmd-side rule. cmd/kubemark is the only one missing — a wrong import in `cmd/kubemark/app` would not match any glob.
- **Overall verdict: `FAIL`.** The improvements between rounds are real (per-sub-package enumeration, Makefile guards, leaf-subsystem coverage, naming-rule narrowing) but the round-3 test report still cannot demonstrate that any rule fires, and the per-sub-package fan-out introduced new bare-package gaps that did not exist in the broad-glob rules of round 1. The verdict carries through from review #2 not because the schema is broken but because the empirical signal is unchanged.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope (carryover from review #2 [F1])
Affected Rule / Constraint: All 85 rules; Makefile target `arch-check`.

What is wrong:
The supplied test report still has `COVERAGE RATE 0% [FAIL]` — byte-for-byte the same
condition flagged as critical in review #2. The Makefile in this round has been
hardened with the recommended `ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes` and
`ARCH_GO_MIN_COVERAGE := 30` guards (lines 33–38, 100–130 of `Makefile`), and
fix-history.md credits those changes to F1. But the report submitted as input
documents a run where:

  (a) coverage is 0%, and
  (b) all 85 rules pass.

If `make arch-check` is what produced this report, then the coverage floor of 30%
should have aborted the run with `[arch-go] FAIL: COVERAGE RATE 0%% is below the
floor of 30%%.` and exited 1. Since the report shows the rule-by-rule passes
without that abort message, only two explanations remain:

  1. The report was captured by running `arch-go check` directly against a stub
     module, bypassing the Makefile target. The Makefile guards exist on disk but
     were never invoked.
  2. The Makefile target was invoked but PROJECT_ROOT pointed at a directory
     whose `go list -m` returned a string that arch-go nevertheless accepted, and
     the post-run coverage parser failed to reject it. In that case, the
     `[arch-go] FAIL: COVERAGE RATE 0%%` message is somewhere upstream of where
     this review reads. Either way, the report itself is the only artifact this
     review consumes — and it shows 0%.

Independently of that, there is a latent bug in the Makefile that survives
regardless of how the run was launched. Line 113-114 reads:

    $(ARCH_GO_BIN) check | tee "$$OUT_FILE"; \
    STATUS=$$?; \

The Makefile recipe is a POSIX `sh` script (Make uses /bin/sh by default; the
Makefile sets no `SHELL := /bin/bash` and turns on no `pipefail`). After a
pipeline, POSIX `$?` is the exit status of the last command in the pipe, which is
`tee`. `tee` essentially never fails, so STATUS is always 0. When arch-go reports
violations in real code, the Makefile will not propagate that exit code; only the
secondary coverage check at lines 117-129 can fail the build. If the coverage
check is ever satisfied (which is the goal!), arch-go violations will silently
pass. The recommended fix in review #2 used `STATUS=$${PIPESTATUS[0]}` which
requires bash; the implemented version uses POSIX-portable `STATUS=$$?` and
sacrifices propagation in exchange.

Why it matters:
This is the single defect that invalidates *every other* `[PASS]` in the report
and the new `function_rules`, `naming_rules`, and per-sub-package
`dependencies_rules` introduced this round. Until a non-zero coverage rate is
observed *and* arch-go's exit code propagates through the Makefile, no rule in
this YAML has been demonstrated to do anything; the architectural review of
the rules themselves is necessarily a paper exercise. CI gating on this report
is gating on a constant.

The new bare-package gap [F2] below is a concrete prediction: even after [F1] is
fully resolved and coverage rises above the 30% floor, two of the three
parallel-component subsystems' main packages (pkg/scheduler, pkg/controller,
pkg/registry) will still escape architectural scrutiny because of the per-
sub-package fan-out. [F1] hides [F2].

How to fix it:
Two changes are needed: (a) demonstrate that `make arch-check` actually fails
when run against the upstream module with the guards engaged, and (b) repair
the pipe-status capture so arch-go violations propagate.

```make
# Replace the arch-check recipe with a pipeline-status-aware variant.
# Use bash explicitly so $${PIPESTATUS[0]} is available, OR avoid the pipe.

SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

arch-check: install-arch-go
	@if [ ! -f "$(PROJECT_ROOT)/go.mod" ]; then \
		echo "[arch-go] ERROR: No go.mod at PROJECT_ROOT=$(PROJECT_ROOT)."; \
		exit 1; \
	fi
	@cd "$(PROJECT_ROOT)" && \
		MOD=$$(go list -m 2>/dev/null); \
		if [ "$$MOD" != "$(ARCH_GO_EXPECTED_MODULE)" ]; then \
			echo "[arch-go] FAIL: expected module $(ARCH_GO_EXPECTED_MODULE), got '$$MOD'"; \
			exit 1; \
		fi
	@cp "$(CURDIR)/arch-go.yml" "$(PROJECT_ROOT)/arch-go.yml"
	@cd "$(PROJECT_ROOT)" && \
		OUT_FILE=$$(mktemp -t arch-go.XXXXXX.out); \
		set -o pipefail; \
		$(ARCH_GO_BIN) check | tee "$$OUT_FILE"; \
		STATUS=$$?; \
		COV=$$(grep -E 'COVERAGE RATE' "$$OUT_FILE" | grep -oE '[0-9]+' | head -n1); \
		if [ -z "$$COV" ] || [ "$$COV" -lt "$(ARCH_GO_MIN_COVERAGE)" ]; then \
			echo "[arch-go] FAIL: coverage $$COV%% below floor $(ARCH_GO_MIN_COVERAGE)%%."; \
			rm -f "$$OUT_FILE"; \
			exit 1; \
		fi; \
		rm -f "$$OUT_FILE"; \
		exit $$STATUS
```

Then re-run against an actual upstream checkout and ensure the report contains
`COVERAGE RATE NN% [PASS]` with NN ≥ 30 *before* declaring [F1] fixed. Until
that line is in the report, this round is review #2 with extra Makefile YAML —
not a fix.
```

```
[F2] SEVERITY: CRITICAL
Category: Coverage Gap | Glob Semantics
Affected Rule / Constraint: 32 controller per-sub-package rules (lines 211–572),
4 scheduler per-sub-package rules (lines 577–622), 2 registry per-sub-package
rules (lines 178–204), and the dedicated rules for `pkg/kubectl/**` (line 783),
`pkg/kubemark/**` (line 810), `pkg/securitycontext/**` (line 742). Header Layer
3 / Layer 4 prose (lines 19-32) and top-level description (lines 75-93).

What is wrong:
The fix for review #2 [F3] (test-helper packages bleeding into rule globs)
replaced the broad globs `pkg/scheduler/**`, `pkg/controller/**`, `pkg/registry/**`
with per-sub-package enumerations:

    pkg/scheduler/{framework,profile,metrics,util}/**     (4 rules)
    pkg/controller/<32 named sub-packages>/**             (32 rules)
    pkg/registry/{rbac,resource}/**                       (2 rules)

This correctly excludes `pkg/scheduler/testing`, `pkg/controller/testutil`, and
`pkg/registry/registrytest`. But it also excludes the **bare parent packages**:

  - `k8s.io/kubernetes/pkg/scheduler`        (line 208 of package list)
  - `k8s.io/kubernetes/pkg/controller`       (line 86)
  - `k8s.io/kubernetes/pkg/registry`         (line 203)

arch-go's `**` glob is path-style and requires at least one path segment after
the prefix. `k8s.io/kubernetes/pkg/scheduler/framework/**` matches
`k8s.io/kubernetes/pkg/scheduler/framework/X`, `.../X/Y`, etc.; it does NOT
match the literal `k8s.io/kubernetes/pkg/scheduler` (parent of framework).
The YAML itself documents this fact: lines 124–125 of the util-layer deny list
read

    - "k8s.io/kubernetes/pkg/kubectl"
    - "k8s.io/kubernetes/pkg/kubectl/**"

Both entries are needed because `pkg/kubectl/**` does NOT match the bare
`pkg/kubectl`. The same is true for `pkg/kubemark`, `pkg/securitycontext`. The
author understands the convention but applied it inconsistently — the
per-sub-package fan-out for controller / scheduler / registry never lists the
bare parent.

Concrete consequences in upstream Kubernetes:

  - `k8s.io/kubernetes/pkg/scheduler/scheduler.go` declares `package scheduler`
    (the bare `pkg/scheduler` package) and contains the canonical `Scheduler`
    struct and the main `Run(ctx context.Context)` scheduling loop. This is
    THE most architecturally significant file in the scheduler subsystem. It
    is matched by zero `dependencies_rules` other than the global `pkg/** →
    cmd/**` catch-all. Adding `import "k8s.io/kubernetes/pkg/kubelet/types"`
    or `import "k8s.io/kubernetes/pkg/controller/job"` to it today would pass
    arch-go.

  - `pkg/controller/controller_utils.go` (and similar helpers in the bare
    `pkg/controller` package) is the controller framework's home. It exposes
    `KeyFunc`, `RemoveTaintOffNode`, `WaitForCacheSync` adapters etc. used by
    every reconciler. A wrong import (e.g., `pkg/scheduler/framework/types`)
    would silently pass.

  - `pkg/registry/wrap.go`, `pkg/registry/generic/registry.go` patterns sit in
    the bare `pkg/registry` package in Kubernetes' history; today the bare
    package is mostly thin, but it is structurally unconstrained.

The same gap exists for the dedicated rules at lines 742, 783, 810:

  - Line 742: `package: "k8s.io/kubernetes/pkg/securitycontext/**"` — the bare
    `pkg/securitycontext` (line 216 of package list) is the package that
    actually contains the public `securitycontext.NewSecurityContext` and
    `securitycontext.HasPrivilegedRequest`-style helpers. It is unconstrained
    against importing component layers.

  - Line 783: `package: "k8s.io/kubernetes/pkg/kubectl/**"` — the bare
    `pkg/kubectl` (line 136 of package list) is unconstrained. (In modern
    upstream this is mostly empty since most kubectl moved to staging, but
    the rule shape is wrong regardless.)

  - Line 810: `package: "k8s.io/kubernetes/pkg/kubemark/**"` — the bare
    `pkg/kubemark` (line 180 of package list) is unconstrained, even though
    the util-layer deny list lists both `pkg/kubemark` and `pkg/kubemark/**`.

Why it matters:
This regresses coverage compared to the broad-glob rules of round 1. Before
review #2 [F3], `pkg/scheduler/**` did at least matter for sub-tree coverage
(though it incorrectly captured test helpers). After the per-sub-package
fan-out, the test-helper false-positive risk is gone but the bare-package
constraint went with it. The top-level `description:` (lines 75–93) advertises
"a symmetric Layer-4 non-coupling lattice across controller, scheduler,
kubelet, proxy, and kubeapiserver"; that is now structurally false because
two of the five lattice nodes (controller and scheduler) have unconstrained
bare packages.

How to fix it:
Add the bare parent path as a separate rule entry alongside each
per-sub-package rule, mirroring the convention used in the util deny list
(lines 124–127). Two structural options:

Option A — add a single bare-parent rule per subsystem (cleanest):

```yaml
  - description: >
      Bare pkg/scheduler package (the Scheduler struct and main Run loop).
      Same Layer-4 non-coupling lattice as the per-sub-package rules below.
    package: "k8s.io/kubernetes/pkg/scheduler"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Bare pkg/controller package (controller framework helpers like
      controller_utils.go). Same Layer-4 non-coupling lattice as the per-
      sub-package rules below. Test helpers live in pkg/controller/testutil
      and remain intentionally unmatched.
    package: "k8s.io/kubernetes/pkg/controller"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Bare pkg/registry package. Test helper pkg/registry/registrytest is
      intentionally unmatched.
    package: "k8s.io/kubernetes/pkg/registry"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

Option B — augment each existing dedicated leaf rule similarly so the bare
path joins its `**` partner:

```yaml
  - description: >
      kubectl in-tree library (bare pkg/kubectl plus its sub-tree).
    package: "k8s.io/kubernetes/pkg/kubectl"     # NEW
    shouldNotDependsOn:
      internal: { ...same as below... }

  - description: >
      kubectl in-tree library sub-tree.
    package: "k8s.io/kubernetes/pkg/kubectl/**"
    shouldNotDependsOn:
      internal: { ... }

  # repeat for pkg/kubemark and pkg/securitycontext
```

Option A is cleaner. Either way, ensure every dedicated rule that uses a
`<X>/**` glob also has a sibling rule for the bare `<X>` package — and verify
empirically that bare packages with real source files (`pkg/scheduler`,
`pkg/controller`, `pkg/securitycontext`) actually appear in the post-fix
arch-go output as covered.
```

```
[F3] SEVERITY: CRITICAL
Category: Asymmetric cmd-side Fence | Coverage Gap
Affected Rule / Constraint: rule at lines 925–933 — `cmd/kube-apiserver/**`.
Top-level description (lines 80–86) and header Layer 6 prose (lines 7–12).

What is wrong:
The cmd/kube-apiserver rule denies only two siblings:

```yaml
  package: "k8s.io/kubernetes/cmd/kube-apiserver/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/kubelet/**"
      - "k8s.io/kubernetes/pkg/proxy/**"
```

Compare with `cmd/kube-controller-manager/**` (lines 935–948), which denies
scheduler, kubelet, proxy, kubeapiserver, AND controlplane. Compare with
`cmd/cloud-controller-manager/**` (lines 950–963), which denies all five
(controller, scheduler, kubelet, proxy, kubeapiserver, controlplane). The
kube-apiserver binary is just as much a "separate binary" as the others — it
should not be allowed to import `pkg/scheduler/**` or `pkg/controller/**`
either, since those internals belong to kube-scheduler and
kube-controller-manager respectively.

This was identified as part of the cmd-side asymmetry in review #2 [F6] but
the fix-history's [F6] resolution (lines 7–8 of fix-history.md) only added
denies to cmd/kubelet/**, cmd/kube-proxy/**, cmd/kube-controller-manager/**,
and cmd/kubeadm/**. cmd/kube-apiserver/** was inadvertently left out of
that list.

The asymmetry contradicts the top-level `description:` claim (lines 80–86):

    Cmd-side rules block cross-binary coupling: kubectl and kubectl-convert
    are client-only; kubeadm uses generated manifests and clientset only;
    kube-apiserver/cloud-controller-manager/kube-controller-manager/
    kube-scheduler/kube-proxy/kubelet binaries may not import each other's
    pkg/** subtrees and may not back-import controlplane or kubeapiserver
    assembly.

The description explicitly names kube-apiserver as part of the symmetric set
that "may not import each other's pkg/** subtrees" — but the rule does not
match the description.

Why it matters:
A wrong import in `cmd/kube-apiserver/app/server.go` adding
`import "k8s.io/kubernetes/pkg/scheduler/framework"` (e.g., to share a
typed configuration struct) would silently pass arch-go even though it
means the apiserver binary now compiles with the scheduler plugin graph as
a dependency. That's exactly the cross-binary coupling regression the rule
file claims to prevent. Same for `pkg/controller/**`. Even more concerning:
nothing prevents `cmd/kube-apiserver/**` from importing `pkg/controlplane/**`
either, despite that being the *expected* import direction (which is fine)
— the issue is the rule is silent on every Layer-4 sibling except the two
node-side ones.

How to fix it:
Make the cmd/kube-apiserver rule symmetric with the other Layer-4 binary
fences:

```yaml
  - description: >
      The kube-apiserver binary tree (cmd/kube-apiserver/**) is the API
      server entry point. It must not import sibling-binary packages
      (kube-scheduler, kube-controller-manager) or node-side packages
      (kubelet, kube-proxy). The apiserver binary may legitimately
      compose pkg/controlplane/** and pkg/kubeapiserver/**, which is
      its exact role.
    package: "k8s.io/kubernetes/cmd/kube-apiserver/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"      # ADD
        - "k8s.io/kubernetes/pkg/controller/**"     # ADD
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
```

Verify against upstream before merging — if today's `cmd/kube-apiserver/app`
actually has a real import on `pkg/controller/<something>` (e.g., a shared
admission helper), decide whether to fix the source or carve a narrow
exception. Do not silently allow it.
```

```
[F4] SEVERITY: HIGH
Category: Glob Sibling Mismatch | Coverage Gap
Affected Rule / Constraint: cmd-side rule set (lines 885–1008); package list
line 31–32 (`cmd/kubemark`, `cmd/kubemark/app`).

What is wrong:
Every documented first-party Kubernetes binary has a `cmd/<binary>/**` rule
EXCEPT `cmd/kubemark`. The rule set covers:

  - cmd/kube-scheduler/**
  - cmd/kube-proxy/**
  - cmd/kubelet/**
  - cmd/kube-apiserver/**
  - cmd/kube-controller-manager/**
  - cmd/cloud-controller-manager/**
  - cmd/kubectl/**
  - cmd/kubectl-convert/**
  - cmd/kubeadm/**

The package list shows `k8s.io/kubernetes/cmd/kubemark` (line 31) and
`k8s.io/kubernetes/cmd/kubemark/app` (line 32) exist as a first-party
hollow-node test binary. There is no rule whose `package:` matches them.
Even the global `pkg/** → cmd/**` catch-all (line 840–843) does not — that
rule constrains `pkg/**` packages from importing cmd/**, not the reverse.

Why it matters:
cmd/kubemark is admittedly a special case: it is a hollow-node simulator
that legitimately needs to import `pkg/kubemark/**` and many parts of
`pkg/kubelet/**` (the simulator faking a kubelet). So the rule for it
will look different from the production binaries. But "different" is not
the same as "missing" — leaving cmd/kubemark entirely uncovered means
any wrong import there (e.g., `pkg/scheduler/framework` because some
benchmark wanted to inspect typed scheduler state) silently passes. The
asymmetry — every other binary has a fence, kubemark has none — is the
defect.

The same reasoning applies to a smaller degree to the other tooling
binaries listed in the package structure (`cmd/clicheck`, `cmd/dependencycheck`,
`cmd/dependencyverifier`, `cmd/fieldnamedocscheck`, `cmd/gendocs`,
`cmd/genfeaturegates`, `cmd/genkubedocs`, `cmd/genman`,
`cmd/genswaggertypedocs`, `cmd/genutils`, `cmd/genyaml`, `cmd/gotemplate`,
`cmd/import-boss`, `cmd/importverifier`, `cmd/preferredimports`,
`cmd/prune-junit-xml`). These are build-tooling binaries; leaving them
unconstrained is defensible (they are deliberately permissive), but the
YAML should say so explicitly. Today the SCOPE OF ANALYSIS header (lines
65–71) lists only `vendor/**`, `third_party/**`, `test/**`, `pkg/generated/**`,
`hack/**`, `build/**`, `cluster/**` as the unconstrained first-party paths —
it omits the dozen-plus build-tooling cmd/<name> packages, leaving the
reader to assume they are constrained when they are not.

How to fix it:
Add a `cmd/kubemark/**` rule and update the SCOPE comment. A starting
point for the kubemark rule:

```yaml
  - description: >
      The kubemark hollow-node simulator binary tree (cmd/kubemark/**)
      legitimately imports pkg/kubemark/** and parts of pkg/kubelet/** to
      fake a kubelet. It must not import sibling production-binary
      packages (controller, scheduler, kubeapiserver, controlplane) or
      cross into kube-proxy internals.
    package: "k8s.io/kubernetes/cmd/kubemark/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

Verify against upstream before merging; cmd/kubemark/app today imports
parts of the cloud-controller-manager loop and historical leaves. If a
real import is found, decide on source-side vs config-side handling.

For the SCOPE comment, add a sentence acknowledging the build-tooling
cmd binaries are unconstrained:

```yaml
# SCOPE OF ANALYSIS
#
#   ...
#   The following first-party paths fall outside any rule glob below; they
#   are unconstrained because no `package:` matches them:
#
#     - vendor/**, third_party/**         (vendored / forked dependencies)
#     - test/**                            (integration / e2e suites)
#     - pkg/generated/**                   (generated openapi)
#     - hack/**, build/**, cluster/**      (tooling and packaging)
#     - cmd/clicheck, cmd/dependencycheck, cmd/dependencyverifier,
#       cmd/fieldnamedocscheck, cmd/gendocs, cmd/genfeaturegates,
#       cmd/genkubedocs, cmd/genman, cmd/genswaggertypedocs, cmd/genutils,
#       cmd/genyaml, cmd/gotemplate, cmd/import-boss, cmd/importverifier,
#       cmd/preferredimports, cmd/prune-junit-xml
#       (build/release tooling binaries; intentionally permissive)
```
```

```
[F5] SEVERITY: HIGH
Category: Vacuous Rule (in current run) | Empirical Validation Gap
Affected Rule / Constraint: `functionsRules` (lines 1198–1225) — pkg/util/**
maxReturnValues=5/maxLines=250 and pkg/apis/** maxReturnValues=8/maxLines=350.

What is wrong:
The fix-history claims the limits were calibrated against the upstream baseline
("state-machine helpers, ValidatePodSpec"). The test report still shows both
rules `[PASS]`, which would be the expected outcome only if (a) the calibration
is correct, OR (b) the rules never saw a single function — i.e., zero coverage
(see [F1]). Given the report shows `COVERAGE 0%`, it is structurally
impossible to distinguish (a) from (b) at this point.

Concrete counterexamples that should still violate the new caps even after
calibration, in current upstream `master`:

  - pkg/apis/core/validation/validation.go: `ValidatePodSpec(...)`,
    `ValidatePodTemplateSpec(...)`, `ValidatePersistentVolumeSpec(...)` —
    each is well over 350 lines after several releases of additions.
  - pkg/apis/networking/validation/validation.go: `validateIngressBackend`,
    `ValidateNetworkPolicySpec` — over 350 lines including the helpers
    they inline.
  - pkg/util/iptables/iptables.go: `(*runner).restoreInternal(...)` —
    well over 250 lines depending on the release.
  - pkg/util/ipvs/proxier.go (when present in a release branch) — same
    shape.
  - pkg/util/iptables/iptables.go: a number of helpers exceed 5 return
    values once you count `(string, error)` plus deferred-cleanup tuples
    threaded through the state machine.

If, post-[F1], any of these pass without violation, the rule is mis-counting
something (likely arch-go's `maxReturnValues` is per-statement, not
per-signature, and the team's mental model is misaligned). If they violate
and the team accepts the violations, the rule is doing its job. The current
state — passing in a 0%-coverage run — is uninformative.

Additionally, `maxReturnValues: 8` for `pkg/apis/**` is generous but worth
sanity-checking: arch-go's documented semantics for `maxReturnValues` is
"largest tuple-arity in any return statement," which for typical Go is
≤ 4 across nearly all signatures. The "8" cap can only fire on extreme
multi-value returns (rare). Verify the rule is actually scoped to what the
team thinks it is.

Why it matters:
Function-complexity rules are the most likely class to actually catch quality
regressions when wired correctly, and the most likely to produce noise when
not. A rule that passes on 0% coverage is identical in CI signal to no rule
at all. The fix-history's "calibrated against baselines" claim is impossible
to verify from the test report supplied to this review.

How to fix it:
1. Resolve [F1] first.
2. Re-run `arch-go check` against an actual k8s.io/kubernetes checkout and
   confirm the function rules surface ≥ 1 violation in `pkg/apis/core/validation`
   (likely many).
3. Either accept the violations as a refactor backlog (preferred — the rule
   is then doing its job), or relax the caps further until they only flag
   true outliers, OR delete the rule. The current state — tight cap that
   silently passes — is the worst combination.

If the empirical run shows the rules do produce violations, no YAML change
is needed; if they pass, the limits are too generous and should be tightened
until they bite a known offender:

```yaml
functionsRules:
  - description: >
      Shared utility functions in pkg/util/** ... [updated calibration after
      empirical run]: cap raised from 250 → NN to grandfather <list of upstream
      outliers>.
    package: "k8s.io/kubernetes/pkg/util/**"
    maxReturnValues: 5
    maxLines: NN          # set after observing real violations

  - description: >
      Validation functions in pkg/apis/** ... [updated]: cap raised from 350
      → MM to grandfather ValidatePodSpec / ValidatePersistentVolumeClaim /
      ValidateStatefulSetSpec.
    package: "k8s.io/kubernetes/pkg/apis/**"
    maxReturnValues: 8
    maxLines: MM
```

Until the caps have been demonstrated to bite *some* known outlier, the
rules remain unverified.
```

```
[F6] SEVERITY: HIGH
Category: Test-helper False Positive (carryover from review #2 [F3], not closed
for volume)
Affected Rule / Constraint: rule at lines 690–702 — `pkg/volume/**`. Package list
line 258 (`k8s.io/kubernetes/pkg/volume/testing`).

What is wrong:
The fix for review #2 [F3] removed test helpers from the controller, scheduler,
and registry rule globs by switching to per-sub-package enumeration. The
volume rule, however, still uses the broad glob:

```yaml
  package: "k8s.io/kubernetes/pkg/volume/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/controller/**"
      - "k8s.io/kubernetes/pkg/scheduler/**"
      ...
      - "k8s.io/kubernetes/cmd/**"
```

This glob matches `k8s.io/kubernetes/pkg/volume/testing` (line 258 of the
package list). The header SCOPE OF ANALYSIS comment (line 63) acknowledges:

    - pkg/volume/testing          (already outside the volume rule's scope)

That comment is **wrong**. `pkg/volume/testing` IS inside `pkg/volume/**` and
will produce a false positive once the rules actually fire. In upstream,
`pkg/volume/testing/testing.go` and friends import `pkg/controller/util` and
similar helpers to build fake reconciler harnesses — exactly the cross-layer
imports the rule forbids.

The fix-history.md round-2 entry confirms the volume rule was added *before*
the test-helper-exclusion design was applied; the exclusion was applied to
controller/scheduler/registry but volume was not retroactively updated.
The result is a documentation lie of exactly the kind review #1 [F8] flagged
and that fix-history.md round-2 [F3] claimed to close.

Why it matters:
Once [F1] is resolved and rules start firing, `pkg/volume/testing` will
trigger immediate false positives that block the build, even though the
imports are correct for a test helper. The team's likely response is to
disable the rule rather than rewrite it — the same failure mode that
motivated the review #2 [F3] fix.

How to fix it:
Apply the same per-sub-package enumeration used for controller/scheduler/
registry, omitting `pkg/volume/testing` from the listed sub-trees. The
volume sub-packages from the package list (lines 243–260) excluding
`testing` are: configmap, csi, csimigration, downwardapi, emptydir, fc,
flexvolume, git_repo, hostpath, image, iscsi, local, nfs, projected, secret,
util, validation. Plus the bare `pkg/volume` package (line 242).

```yaml
# Replace the single broad volume rule with per-sub-package rules.
# Test helper sub-tree pkg/volume/testing is intentionally excluded.

  - description: Bare pkg/volume package (volume plugin framework root).
    package: "k8s.io/kubernetes/pkg/volume"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/cmd/**"

  - description: Volume sub-tree configmap.
    package: "k8s.io/kubernetes/pkg/volume/configmap/**"
    shouldNotDependsOn: { internal: [ ...same... ] }

  - description: Volume sub-tree csi.
    package: "k8s.io/kubernetes/pkg/volume/csi/**"
    shouldNotDependsOn: { internal: [ ...same... ] }

  # ... 16 more entries for the remaining sub-trees ...
```

Or, alternatively, accept that `pkg/volume/testing` is a test helper and
document that it produces expected false positives the team will whitelist
post-[F1]. Either is acceptable; the current state — comment claims it's
out of scope, glob says it's in scope — is not.

Update the header comment at line 63 either way:

```yaml
#     - pkg/volume/testing          (excluded; cross-layer integration
#                                    helpers — same treatment as the
#                                    controller/scheduler/registry test
#                                    helpers above)
```
```

```
[F7] SEVERITY: HIGH
Category: Coverage Gap | Description Inaccuracy
Affected Rule / Constraint: top-level `description:` block (lines 75–93);
rule for `pkg/registry/<rbac,resource>/**` (lines 178–204); the scheduler
sub-package rules (lines 577–622).

What is wrong:
The top-level description claims:

    Pkg-side rules form a symmetric Layer-4 non-coupling lattice across
    controller, scheduler, kubelet, proxy, and kubeapiserver, with
    Layer-3 (registry) blocked from every Layer-4 sibling and Layer-5
    (controlplane).

After [F2], this is structurally false in two places:

  (1) The Layer-4 lattice is incomplete because `pkg/scheduler` (bare)
      and `pkg/controller` (bare) are unconstrained. (See [F2].)

  (2) The "Layer-3 blocked from every Layer-4 sibling" claim is enforced
      only for `pkg/registry/rbac/**` and `pkg/registry/resource/**`.
      The bare `pkg/registry` package and any future registry sub-tree
      (e.g., `pkg/registry/core`, `pkg/registry/apps`, `pkg/registry/batch`,
      etc., which are common in upstream) are NOT enforced by the
      per-sub-package rules. The claim of universal Layer-3 isolation
      depends on the registry sub-trees being limited to {rbac, resource}.

The package structure file lists only `pkg/registry`, `pkg/registry/rbac`,
`pkg/registry/registrytest`, `pkg/registry/resource` — but the package
list itself is acknowledged as a top-level summary, not a complete tree.
Real upstream Kubernetes has `pkg/registry/core/**`, `pkg/registry/apps/**`,
`pkg/registry/batch/**`, `pkg/registry/networking/**`, `pkg/registry/storage/**`,
`pkg/registry/policy/**`, `pkg/registry/certificates/**`,
`pkg/registry/admissionregistration/**`, etc. — at least a dozen sub-trees.

If the per-sub-package fan-out for registry was driven by the truncated
package list rather than upstream reality, then most of the registry tree
is unconstrained. A `pkg/registry/core/pod/storage/storage.go` adding
`import "k8s.io/kubernetes/pkg/scheduler/framework"` would silently pass.

Same shape concern for scheduler: package list shows only framework, profile,
metrics, util sub-packages, but real upstream has at least `pkg/scheduler/apis`,
`pkg/scheduler/backend`, `pkg/scheduler/internal` (and historically more).
The four listed sub-package rules cover only a fraction of the scheduler
tree.

Why it matters:
The per-sub-package fan-out trades a small false-positive risk (test
helpers) for a large false-negative risk (any sub-tree not in the package
list is unconstrained). For a project the size of Kubernetes, the package
list given to this review is a known-incomplete summary, not a directory
tree. The fan-out should be derived from the actual filesystem, not a
documentation-summary file.

How to fix it:
Two complementary fixes are needed:

(a) Re-derive the per-sub-package rule sets from the actual upstream
filesystem, not from the truncated `5_kubernetes_kubernetes.txt` package
summary. Specifically:

```bash
# Generate the actual sub-package list once, mechanically, on a real
# kubernetes checkout:
for sub in $(go list ./pkg/registry/... 2>/dev/null | sort -u); do
  echo "  - description: Registry sub-tree $sub."
  echo "    package: \"$sub/**\""
  ...
done
```

(b) Revert to the broad glob and use a separate explicit-deny mechanism for
test helpers. arch-go has no `excludedPackages:`, but you can keep the test
helper packages out of scope by writing them as their own rule whose deny
list is empty:

```yaml
  # Broad rule, intentional.
  - description: Registry. Sub-tree-wide non-coupling lattice.
    package: "k8s.io/kubernetes/pkg/registry/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

… and accept that `pkg/registry/registrytest` will fail. Then resolve the
registrytest false positives at the source (move it to `pkg/registry/internal/registrytest`,
or fix its imports) — a one-time cleanup that benefits the codebase, rather
than a permanent YAML workaround.

The fan-out approach as currently written is intermediate: it neither blanket-
covers nor mechanically excludes; it covers a documentation-summary fraction
of the real tree.
```

```
[F8] SEVERITY: MEDIUM
Category: Naming Rule Coverage Gap
Affected Rule / Constraint: rule at lines 1077–1083 — `pkg/kubelet/cm/**`
ContainerManager → `Manager`.

What is wrong:
The rule says structs implementing `ContainerManager` in pkg/kubelet/cm/**
must be suffixed with `Manager`. The simple-name interface match resolves
to the canonical interface in `pkg/kubelet/cm/types.go`. Fine — the rule
does what the description says.

But `pkg/kubelet/cm` has many sub-packages with their own `Manager`
interfaces, none of which are matched by simple name `ContainerManager`:

    pkg/kubelet/cm/cpumanager        (type Manager interface)
    pkg/kubelet/cm/memorymanager     (type Manager interface)
    pkg/kubelet/cm/topologymanager   (type Manager interface)
    pkg/kubelet/cm/devicemanager     (type Manager interface)
    pkg/kubelet/cm/dra               (type Manager interface)

Each declares a `Manager` interface (simple name `Manager`) following the
kubelet idiom. None of these are constrained by the cm rule (which targets
`ContainerManager`), and none are constrained by any of the other twelve
kubelet manager rules (which target specific sub-packages by path, not
`pkg/kubelet/cm/<sub-manager>`). So the kubelet's most architecturally
significant resource managers — CPU, memory, topology, device, DRA — are
all uncovered by any naming rule.

The architectural documentation does not strictly require this — kubelet's
suffix convention is folklore, not a hard contract — but the YAML's
description (lines 1078–1083) implies cm's manager family is constrained
by this rule, and a future maintainer reading the YAML would believe
cpumanager.Manager implementations are required to end in `Manager`. They
are not.

Why it matters:
Coverage gap that the YAML's description hides. A new struct in
`pkg/kubelet/cm/cpumanager` named `cpuPolicy` (implementing Manager)
would not violate any rule. If the team's intent is the kubelet
manager-suffix convention applies to ALL kubelet resource managers, it
should be encoded.

How to fix it:
Either (a) add per-sub-package rules for the cm sub-managers, or (b) tighten
the description so the gap is explicit:

```yaml
  - description: >
      CPU manager implementations in pkg/kubelet/cm/cpumanager must be
      suffixed with 'Manager'.
    package: "k8s.io/kubernetes/pkg/kubelet/cm/cpumanager/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Manager"
      shouldHaveSimpleNameEndingWith: "Manager"

  # repeat for memorymanager, topologymanager, devicemanager, dra
```

Verify the per-sub-package interface-name resolution doesn't collide
with the existing `pkg/kubelet/cm/**` rule (which targets ContainerManager,
not Manager — should be fine in practice, but worth confirming with a
real run after [F1] is resolved).
```

```
[F9] SEVERITY: MEDIUM
Category: Vacuous Rule (in current upstream layout)
Affected Rule / Constraint: rule at lines 780–791 — `pkg/kubectl/**`.

What is wrong:
The rule constrains `pkg/kubectl/**` from importing component layers. The
package list line 136 lists only the bare `pkg/kubectl` (no sub-packages).
In modern upstream Kubernetes, `pkg/kubectl` was largely migrated to
`staging/src/k8s.io/kubectl` years ago; what remains in the in-tree
`pkg/kubectl` is mostly a thin compatibility shim with no sub-trees.

The glob `pkg/kubectl/**` requires at least one path segment after
`pkg/kubectl/`. If `pkg/kubectl` has no sub-packages in the upstream
checkout, this rule matches **zero packages** and is fully vacuous —
it cannot fire even on a deliberately violating commit. (The bare
`pkg/kubectl` package, which is what the rule is supposed to constrain,
is not matched by `pkg/kubectl/**`. See [F2].)

The rule still passes in the test report because there are no matched
packages and therefore no source files to violate it. Once the
`pkg/kubectl` migration to staging is complete, this rule is permanently
no-op until pkg/kubectl/** sub-packages are reintroduced.

Same shape concern for `pkg/kubemark/**` (line 810) — the package list
shows only the bare `pkg/kubemark` (line 180) — though pkg/kubemark
historically does have sub-trees, so this is more of a "verify
empirically" concern than a definitive vacuous rule.

Why it matters:
A rule that cannot fire is dead weight. A future maintainer trying to
understand the kubectl architectural fence will read this rule, mistake
it for active enforcement, and not realize it constrains zero packages.

How to fix it:
Convert to a bare-path rule (which addresses [F2] simultaneously):

```yaml
  - description: >
      Kubectl in-tree library (pkg/kubectl, the bare compatibility shim)
      is client-shaped and must not import server-side component packages.
      Note: most of pkg/kubectl has been migrated to staging/src/k8s.io/kubectl;
      the in-tree package is intentionally minimal.
    package: "k8s.io/kubernetes/pkg/kubectl"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Kubectl in-tree library sub-tree (currently empty in upstream master,
      but reserved for future sub-packages).
    package: "k8s.io/kubernetes/pkg/kubectl/**"
    shouldNotDependsOn:
      internal: { ...same... }
```

The two-rule pattern matches the convention used at lines 124–125 of the
util-deny list and ensures both bare and sub-tree are covered.
```

```
[F10] SEVERITY: MEDIUM
Category: Description Inaccuracy
Affected Rule / Constraint: top-level `description:` block (lines 75–93);
header Layer-4 prose (lines 25–27).

What is wrong:
Two material discrepancies between the description / header prose and the
actual rules (independent of [F2], [F3], [F7] above):

  (1) The header lines 25–27 say:

      "These are parallel component layers. They form a fully symmetric
      non-coupling lattice: no Layer-4 sibling may import any other Layer-4
      sibling, and none may import Layer-5 controlplane."

      But the documentation page (Kubernetes Components) does NOT actually
      say this. The page says the components run on different
      hosts (control-plane vs. nodes) and are deployed as separate binaries.
      It says nothing about a "fully symmetric non-coupling lattice." The
      "lattice" framing was inferred by the YAML's author and presented as
      if it were documented. A reviewer reading only the header would
      conclude the architectural source-of-truth mandates the lattice; it
      doesn't.

      That's not necessarily wrong — encoding the separate-binary
      deployability invariant as a non-coupling lattice is a reasonable
      design choice. But it should be presented as the YAML's design
      decision, not as a documentation citation.

  (2) Line 91–93 says:

      "arch-go's simple-name interface resolution may produce false
      positives where the same simple interface name is reused across
      sub-packages — see in-line comments on each naming rule for the
      trade-off."

      The kubelet manager rules at lines 1053–1147 do mostly carry
      sub-package-narrowing comments. But the controller `Interface` rule
      (line 1033–1042), the proxy `Proxier` rule (line 1155–1164), and the
      registry `Storage` rule (line 1176–1185) all use `pkg/<X>/**` globs
      that span many sub-packages, each of which may declare its own
      interface with the same simple name. The in-line BRITTLENESS notes
      describe the issue but don't resolve it. The description's "see in-line
      comments" is an admission-of-known-defect, not a fix.

Why it matters:
Documentation drift. A maintainer extending the rules in good faith uses the
prose as a contract; the YAML reads more like a near-implementation than a
full one. This is the same class of issue as review #2 [F11] — flagged in
that round, partially closed, partially recurred in this round.

How to fix it:
Tighten the description to distinguish documentation citations from
design choices, and make the BRITTLENESS notes resolved-in-config rather
than acknowledged-in-comment:

```yaml
description: >
  Enforces the Kubernetes component architecture as documented at
  https://kubernetes.io/docs/concepts/overview/components/. The
  documentation specifies the cluster topology (control-plane vs. node)
  and the separate-binary deployability of kube-apiserver, kube-scheduler,
  kube-controller-manager, cloud-controller-manager, kubelet, kube-proxy,
  kubectl, and kubeadm. This YAML encodes that separate-binary invariant
  as a Layer-4 non-coupling lattice across controller/scheduler/kubelet/
  proxy/kubeapiserver — a design decision, not a direct documentation
  citation. The lattice is enforced for the non-bare paths of each Layer-4
  subsystem (see [F2] for the bare-path coverage gap that the round-2
  per-sub-package fan-out introduced). Cmd-side rules block cross-binary
  coupling for all production Kubernetes binaries except cmd/kubemark
  (a hollow-node test simulator with permissive scope; see [F4]).
```

For the BRITTLENESS comments, either narrow the rule scope to a single
sub-package (which the kubelet rules already do) or add an explicit list
of expected matches the reviewer should manually verify on a real run.
The current state — note acknowledges the issue but the rule is unchanged —
is documentation theater.
```

```
[F11] SEVERITY: LOW
Category: Redundancy / Inconsistency
Affected Rule / Constraint: util-layer deny list (lines 110–131);
dedicated rules at lines 728–778, 783, 810, 742.

What is wrong:
The util-layer deny list (lines 110–131) lists eight subsystems with
explicit `<X>` and `<X>/**` pairs:

    "k8s.io/kubernetes/pkg/kubectl"
    "k8s.io/kubernetes/pkg/kubectl/**"
    "k8s.io/kubernetes/pkg/kubemark"
    "k8s.io/kubernetes/pkg/kubemark/**"
    ...
    "k8s.io/kubernetes/pkg/securitycontext"
    "k8s.io/kubernetes/pkg/securitycontext/**"

This convention correctly handles the bare-path glob limitation (see [F2]).

But the dedicated subsystem rules at lines 728–778, 783, 810, 742 use only
the `<X>/**` form:

    line 728: package: "k8s.io/kubernetes/pkg/security/**"
    line 742: package: "k8s.io/kubernetes/pkg/securitycontext/**"
    line 755: package: "k8s.io/kubernetes/pkg/serviceaccount/**"
    line 769: package: "k8s.io/kubernetes/pkg/probe/**"
    line 783: package: "k8s.io/kubernetes/pkg/kubectl/**"
    line 796: package: "k8s.io/kubernetes/pkg/printers/**"
    line 810: package: "k8s.io/kubernetes/pkg/kubemark/**"
    line 823: package: "k8s.io/kubernetes/plugin/**"

Each of these subsystems has a bare package in the package list (`pkg/security`,
`pkg/securitycontext`, `pkg/serviceaccount` is line 217, etc.) that the rule
glob does NOT match. Same as [F2] for the registry/scheduler/controller
sub-trees, but for leaf subsystems.

Why it matters:
Internally inconsistent. The author's util-layer convention says "to
constrain pkg/X, list both pkg/X and pkg/X/**", but the dedicated subsystem
rules don't follow it. The bare packages slip through.

How to fix it:
Either (a) add the bare-path entries to every dedicated rule, mirroring the
util convention; or (b) write a single rule per subsystem with the bare path
matching multiple `package:` entries:

```yaml
  - description: >
      Security primitives (pkg/security and its sub-tree). Same constraints
      apply to the bare package as to the sub-packages.
    package: "k8s.io/kubernetes/pkg/security"      # bare
    shouldNotDependsOn:
      internal: [ ... ]

  - description: Sub-tree of pkg/security.
    package: "k8s.io/kubernetes/pkg/security/**"
    shouldNotDependsOn:
      internal: [ ... ]
```

This is cosmetic until [F2] is acted on, at which point making it consistent
across the YAML is the right fix.
```

```
[F12] SEVERITY: LOW
Category: Redundancy
Affected Rule / Constraint: rule at lines 845–862 (`pkg/kubelet/container/**`)
and the broad `pkg/kubelet/**` rule (lines 628–641).

What is wrong:
The intra-layer kubelet/container rule constrains `pkg/kubelet/container/**`
from importing `pkg/kubelet/server/**`, `pkg/kubelet/config/**`,
`pkg/kubelet/volumemanager/**`. These are sibling sub-packages of
container under the kubelet tree.

Separately, the broad `pkg/kubelet/**` rule denies importing the Layer-4
siblings (controller, scheduler, proxy, kubeapiserver, controlplane). The
two rules don't overlap (the intra-layer rule constrains intra-kubelet
imports; the broad rule constrains cross-component imports), so they're
complementary, not redundant. Fine.

But: Go's import-cycle prohibition already prevents kubelet/container
from importing kubelet/server (server imports container itself, directly
or transitively, in upstream master), kubelet/volumemanager (which depends
on container.Runtime), and probably kubelet/config (config feeds container).
So the intra-layer rule is partially redundant with Go's compile-time
checks — same shape as the deleted kubelet/types rule from review #2 [F10]
that fix-history correctly removed.

Verify on the actual import graph that the intra-layer rule fires on a
direction the compiler doesn't already prohibit. If it doesn't, the rule
is documentation, not enforcement.

Why it matters:
Maintenance smell. Same class as the deleted kubelet/types rule. Not a
correctness defect.

How to fix it:
Two options:

(a) Verify via `go mod why` / `go list` whether each forbidden import
direction is already a build cycle. Drop directions that the compiler
handles. Keep only the directions that are real architectural fences.

(b) Replace with a more concrete intra-layer fence — for example, "the
container abstraction layer must not import any kubelet sub-package that
defines a config type". This is harder to express in arch-go but
genuinely informative.

If neither, the rule is fine as documentation; just add a comment that
several entries duplicate Go's import-cycle handling.
```

```
[F13] SEVERITY: LOW
Category: Description Inaccuracy
Affected Rule / Constraint: SCOPE OF ANALYSIS header comment (lines 49–73).

What is wrong:
The header (lines 49–73) lists "first-party paths that fall outside any
rule glob" as:

    - vendor/**, third_party/**         (vendored / forked dependencies)
    - test/**                            (integration / e2e suites)
    - pkg/generated/**                   (generated openapi)
    - hack/**, build/**, cluster/**      (tooling and packaging)

The list is incomplete. Other first-party paths that match no glob in
the YAML and are therefore unconstrained:

  - cmd/clicheck, cmd/dependencycheck, cmd/dependencyverifier,
    cmd/fieldnamedocscheck, cmd/gendocs, cmd/genfeaturegates,
    cmd/genkubedocs, cmd/genman, cmd/genswaggertypedocs, cmd/genutils,
    cmd/genyaml, cmd/gotemplate, cmd/import-boss, cmd/importverifier,
    cmd/preferredimports, cmd/prune-junit-xml, cmd/kubemark
    (build tooling and the kubemark simulator — see [F4])
  - pkg/auth/nodeidentifier, pkg/capabilities, pkg/certauthorization,
    pkg/client/conditions, pkg/client/tests, pkg/cluster/ports,
    pkg/features, pkg/fieldpath, pkg/routes, pkg/windows/service
    (utility leaf packages with no specific rule; covered only by the
    broad pkg/** → cmd/** catch-all)
  - The bare `pkg/scheduler`, `pkg/controller`, `pkg/registry` packages
    (see [F2])
  - The bare `pkg/security`, `pkg/securitycontext`, `pkg/serviceaccount`,
    `pkg/probe`, `pkg/kubectl`, `pkg/kubemark`, `pkg/printers`, `pkg/volume`,
    `pkg/credentialprovider` packages (see [F11])

Why it matters:
Misleading documentation in the YAML header. A maintainer reading the
SCOPE OF ANALYSIS section forms an incorrect mental model of which paths
are constrained. They will not realize that, e.g., `pkg/auth/nodeidentifier`
or the bare `pkg/scheduler` package can adopt cross-component imports
silently.

How to fix it:
Expand the SCOPE OF ANALYSIS section with the omitted paths and re-derive
the list from a real `go list ./...` against the upstream module rather than
from intuition. A short script run during YAML generation can keep the
list consistent.

```yaml
# SCOPE OF ANALYSIS
#
#   ...
#
#   First-party paths covered by NO `package:` glob below (i.e., violations
#   in these paths are invisible to arch-go):
#
#     # vendored / generated / non-source paths (intentional)
#     - vendor/**, third_party/**
#     - pkg/generated/**
#     - hack/**, build/**, cluster/**
#     - test/**
#
#     # build/release tooling cmd binaries (intentional, deliberately permissive)
#     - cmd/clicheck, cmd/dependencycheck, cmd/dependencyverifier,
#       cmd/fieldnamedocscheck, cmd/gendocs, cmd/genfeaturegates,
#       cmd/genkubedocs, cmd/genman, cmd/genswaggertypedocs, cmd/genutils,
#       cmd/genyaml, cmd/gotemplate, cmd/import-boss, cmd/importverifier,
#       cmd/preferredimports, cmd/prune-junit-xml
#
#     # bare parent packages of per-sub-package fan-out groups
#     # — UNINTENDED: see review-#3 [F2]; bare-path entries to be added
#     - pkg/scheduler  (the canonical Scheduler struct lives here)
#     - pkg/controller (controller framework helpers)
#     - pkg/registry   (top-level registry helpers)
#
#     # leaf-subsystem bare packages
#     # — UNINTENDED: see review-#3 [F11]
#     - pkg/security, pkg/securitycontext, pkg/serviceaccount, pkg/probe,
#       pkg/kubectl, pkg/kubemark, pkg/printers, pkg/volume,
#       pkg/credentialprovider
#
#     # unconstrained leaf utilities (only covered by pkg/** -> cmd/** catch-all)
#     - pkg/auth/nodeidentifier, pkg/capabilities, pkg/certauthorization,
#       pkg/client/conditions, pkg/client/tests, pkg/cluster/ports,
#       pkg/features, pkg/fieldpath, pkg/routes, pkg/windows/service
#
#     # node-side test simulator binary
#     # — UNINTENDED: see review-#3 [F4]; cmd/kubemark/** rule to be added
#     - cmd/kubemark
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

The structural improvements between rounds — schema correctness ([F1] of round 1),
parallel-component lattice symmetry ([F2] of round 1), test-helper isolation via
per-sub-package fan-out ([F3] of round 2), Makefile coverage floor ([F1] of round
2) — are real and visible in the YAML. **But the test report submitted with this
round is byte-for-byte identical in compliance signal to round 2: 100% pass on
0% coverage.** The schema fixes from round 1 and the symmetry fixes from round 2
remain unverifiable until a non-zero coverage rate is observed against
`k8s.io/kubernetes`.

**Order of fixes** (highest leverage first):

1. **[F1]** — re-run `make arch-check` against an actual upstream `k8s.io/kubernetes`
   checkout and capture the report. Confirm `COVERAGE RATE NN% [PASS]` with NN ≥ 30
   AND that the `STATUS=$$?` pipeline-status bug doesn't hide arch-go violations.
   Until this is done, every other finding below is a paper claim.
2. **[F2]** — add bare-path entries (`pkg/scheduler`, `pkg/controller`,
   `pkg/registry`) for each per-sub-package fan-out group. The round-2 fix for
   test-helper exclusion was correct in principle but introduced a regression
   that swallows the most architecturally significant packages of three
   subsystems.
3. **[F3]** — add `pkg/scheduler/**` and `pkg/controller/**` to the
   `cmd/kube-apiserver/**` deny list to make it symmetric with
   `cmd/kube-controller-manager/**` and `cmd/cloud-controller-manager/**`.
4. **[F4]** — add the missing `cmd/kubemark/**` rule (and document
   build-tooling cmd binaries in the SCOPE OF ANALYSIS header).
5. **[F5]** — empirically verify the function-complexity caps fire against
   real upstream code (`ValidatePodSpec` and friends). If they pass silently
   after [F1] is fixed, the caps are misconfigured.
6. **[F6]** — apply the same per-sub-package treatment to `pkg/volume/**`
   that controller/scheduler/registry received, OR document `pkg/volume/testing`
   as an expected false positive.
7. **[F7]** — re-derive the per-sub-package rule lists from an actual
   `go list ./...` on a real upstream checkout, not from the truncated
   package summary file.
8. **[F8]** — extend the kubelet cm naming rules to cover cpumanager,
   memorymanager, topologymanager, devicemanager, dra sub-managers if the
   suffix convention is intended to apply to them.
9. **[F9]–[F13]** — clean up vacuous rules, redundant rules, and misleading
   header comments.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:
- `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The function rules surface ≥ 1 violation in `pkg/apis/core/validation` (the
  empirical proof they are no longer vacuous).
- The bare-path additions from [F2] are reflected in the rule-by-rule report
  (i.e., a line beginning `Packages matching pattern 'k8s.io/kubernetes/pkg/scheduler'`
  rather than only `'.../pkg/scheduler/framework/**'`).
- The kube-apiserver, cloud-controller-manager, kube-controller-manager rules
  produce zero violations against current upstream master (they should, post-fix).

Until those four conditions are met, this report should not be a CI gate; it
is a cosmetic banner.

---

**End of Review #3.**
