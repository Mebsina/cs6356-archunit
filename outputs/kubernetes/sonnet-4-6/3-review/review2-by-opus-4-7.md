# Adversarial Review #2 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 2

---

## Executive Summary

- **Total documented constraints identified: 27.** Eight high-level invariants come from the Kubernetes "Components" reference page (control-plane vs. node split, kube-apiserver as the only API surface, cloud-controller-manager optionality, kube-proxy optionality, container-runtime CRI boundary, etcd persistence, addons as separate processes, separate-binary deployability). Nineteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`); the symmetric Layer-4 non-coupling lattice across controller/scheduler/kubelet/proxy/kubeapiserver; controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider leaf-isolation; intra-layer fences (scheduler/framework, kubelet/container, kubelet/types, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`, `Storage`); util / apis function-complexity caps.
- **Total rules generated: 31** — 25 `dependenciesRules` + 4 `namingRules` + 2 `functionsRules`. The contents-rules family is now empty (correctly, after [F10/F12] of review #1).
- **Coverage rate (constraint → rule mapping): 24/27** documented constraints have a rule that *would* fire if arch-go were actually scanning the upstream `k8s.io/kubernetes` module. The other three constraints (registry → kubeapiserver isolation, cmd-side completeness for `cmd/kubectl-convert`, util → leaf-component isolation) are gaps below.
- **Effective coverage rate from arch-go itself: still 0%.** The test report footer reads `COVERAGE RATE 0% [FAIL]`. This is unchanged from review #1 and is the single most important defect in this round: the rewrite from review #1 corrected the schema, asymmetries, and missing rules, but the rule set is still being evaluated against either an empty checkout or the wrong module path. Compliance can only be `100%` because nothing matches the globs. The schema fixes that were promised in `fix-history.md` are *unverifiable* until a non-zero coverage is observed.
- **Critical Gaps** (constraints with zero or vacuous enforcement at the time the report was produced):
  - **`COVERAGE RATE 0% [FAIL]` is a structural failure** — see [F1]. The Makefile copies `arch-go.yml` to `$(PROJECT_ROOT)` but never verifies that `$(PROJECT_ROOT)/go.mod` declares `module k8s.io/kubernetes`. The report's `100%` compliance is therefore the trivial 100% you get from scanning zero packages, not architectural validation.
  - **The `functionsRules` are silently passing**, see [F2]. Real upstream `pkg/apis/**` validators (e.g. `validation.ValidatePodSpec`, `validation.ValidateStatefulSetSpec`) and real `pkg/util/iptables/iptables.go` helpers exceed the 200-line / 100-line caps; that the rules `[PASS]` is itself the smoking gun the rule never saw the code.
  - **Test helper packages are still inside the rule globs** ([F3]). `pkg/scheduler/testing`, `pkg/controller/testutil`, `pkg/registry/registrytest` are explicitly named in the YAML header as exempt but are matched by `pkg/scheduler/**`, `pkg/controller/**`, `pkg/registry/**`. Once enforcement actually runs they will produce false positives; the header is documentation that contradicts the rules.
  - **`pkg/registry/**` does not deny `pkg/kubeapiserver/**`** ([F4]). Layer 3 → Layer 4 inversion. The header explicitly lists kubeapiserver as a Layer-4 component; the parallel apis/api/util rules all deny kubeapiserver; only registry has the gap.
  - **`cmd/kubectl-convert` is unconstrained** ([F5]). The package `k8s.io/kubernetes/cmd/kubectl-convert` is in the package list (line 28) but matches no `package:` glob — `cmd/kubectl/**` matches `cmd/kubectl/X` only, not the sibling binary `cmd/kubectl-convert`. A wrong import in this binary would pass.
  - **Asymmetric cmd-side fences** ([F6]). `cmd/kubelet/**` denies controlplane/kubeapiserver/scheduler but *not* `pkg/controller/**` or `pkg/proxy/**`; `cmd/kube-proxy/**` denies controller/scheduler/kubelet but *not* `pkg/kubeapiserver/**` or `pkg/controlplane/**`; `cmd/kube-controller-manager/**` denies scheduler/kubelet/proxy but *not* `pkg/controlplane/**` or `pkg/kubeapiserver/**` (asymmetric with `cmd/cloud-controller-manager/**`, which does deny controlplane).
  - **Naming rules use simple-name interface matching that is too generic** ([F7]). `structsThatImplement: "Manager"` matches *every* interface named `Manager` in `pkg/kubelet/**` regardless of which sub-package defines it (`volumemanager.Manager`, `configmap.Manager`, `secret.Manager`, `cm.ContainerManager`, …). The rule cannot distinguish "the kubelet's canonical Manager" from arbitrary unrelated `Manager` interfaces, and it likely cross-fires with type aliases. Same for `Interface`, `Storage`.
- **Overall verdict: `FAIL`.** The corrections from review #1 are real but cannot be validated against an actual k8s checkout because of the [F1] regression / unfixed Makefile gap; until that is corrected, the new rules are formally indistinguishable from the previous round's vacuous ones.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope
Affected Rule / Constraint: All 31 rules; Makefile target `arch-check`.

What is wrong:
The arch-go run in the test report has `COVERAGE RATE 0% [FAIL]`. arch-go's "coverage"
is the percentage of packages in the scanned module that are matched by at least one
`package:` glob in the config. The YAML contains a deliberate catch-all rule

    package: "k8s.io/kubernetes/pkg/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/cmd/**"

which alone, against the upstream `k8s.io/kubernetes` module, must match well over a
hundred packages out of the ~430 listed in `5_kubernetes_kubernetes.txt`. A 0% rate is
mathematically incompatible with that rule firing against the real module. Only two
explanations remain:

  (a) `arch-go check` ran against a different module than `k8s.io/kubernetes` (i.e. the
      go.mod under PROJECT_ROOT declares some other module — likely the empty stub
      created when PROJECT_ROOT defaulted to $(CURDIR), the directory containing the
      Makefile and arch-go.yml itself), or
  (b) `arch-go check` ran against an empty/synthetic module that happens to have a
      go.mod but no Go source under any of the matched paths.

Either way, every `[PASS]` line in the report is the trivial pass produced by zero
matches, not architectural validation. The Makefile actively hides this:

    @if [ ! -f "$(PROJECT_ROOT)/go.mod" ]; then \
        echo "[arch-go] ERROR: No go.mod found at PROJECT_ROOT=$(PROJECT_ROOT)."; \
        ...
    fi
    @cp "$(CURDIR)/arch-go.yml" "$(PROJECT_ROOT)/arch-go.yml"
    @cd "$(PROJECT_ROOT)" && $(ARCH_GO_BIN) check

The check verifies *that* a go.mod exists but not *which* module it declares. A go.mod
declaring `module example.com/stub` satisfies the file-existence test, copies the YAML
into place, and runs arch-go against zero matching packages. Compliance prints 100%.
Coverage prints 0%. CI is green. Nothing has been validated.

`fix-history.md` line 3 explicitly states: "F13's Makefile module-path sanity check is
left for follow-up since the source-of-truth target for this review pass is `arch-go.yml`
only." That deferral is the proximate cause of the 0% coverage in this round.

Why it matters:
This is the single defect that invalidates *every other* `[PASS]` in the report,
including the schema-correctness fixes from review #1 and every rule listed below this
finding. Until a non-zero coverage rate is produced against the upstream module, no rule
in this YAML has been demonstrated to do anything; the architectural review of the rules
themselves is necessarily a paper exercise. A CI pipeline gating merges on this
output is gating on a constant.

How to fix it:
Two changes are required and they go together. First, harden the Makefile so it refuses
to run against the wrong module. Second, fail the build if arch-go reports coverage
below a configured floor. (arch-go does not yet have a `--min-coverage` flag, so wrap
its output.)

```make
# Replace the existing arch-check target body. Add the module-path check and the
# coverage floor.

ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes
ARCH_GO_MIN_COVERAGE   := 30

arch-check: install-arch-go
	@if [ ! -f "$(PROJECT_ROOT)/go.mod" ]; then \
		echo "[arch-go] ERROR: No go.mod at PROJECT_ROOT=$(PROJECT_ROOT)."; \
		echo "[arch-go] Set PROJECT_ROOT to the kubernetes repository root."; \
		exit 1; \
	fi
	@cd "$(PROJECT_ROOT)" && \
		MOD=$$(go list -m 2>/dev/null); \
		if [ "$$MOD" != "$(ARCH_GO_EXPECTED_MODULE)" ]; then \
			echo "[arch-go] FAIL: expected module $(ARCH_GO_EXPECTED_MODULE), got '$$MOD'"; \
			echo "[arch-go] Refusing to run against the wrong module."; \
			exit 1; \
		fi
	@cp "$(CURDIR)/arch-go.yml" "$(PROJECT_ROOT)/arch-go.yml"
	@cd "$(PROJECT_ROOT)" && $(ARCH_GO_BIN) check | tee /tmp/arch-go.out; \
		STATUS=$${PIPESTATUS[0]}; \
		COV=$$(grep -E 'COVERAGE RATE' /tmp/arch-go.out | grep -oE '[0-9]+' | head -1); \
		if [ -z "$$COV" ] || [ "$$COV" -lt "$(ARCH_GO_MIN_COVERAGE)" ]; then \
			echo "[arch-go] FAIL: coverage $$COV%% below floor $(ARCH_GO_MIN_COVERAGE)%%."; \
			echo "[arch-go] Either widen the rules or verify the module is being scanned."; \
			exit 1; \
		fi; \
		exit $$STATUS
```

Then re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm the report
prints `COVERAGE RATE NN% [PASS]` with NN >= 30. Until that line is in the report, do
not treat this round as a fix for review #1 — it is review #1 with a corrected schema
that has not been exercised.
```

```
[F2] SEVERITY: CRITICAL
Category: Vacuous Rule (in current run) | Pending Empirical Validation
Affected Rule / Constraint: `functionsRules` for `pkg/util/**` (maxReturnValues: 5,
maxLines: 100) and `pkg/apis/**` (maxReturnValues: 8, maxLines: 200).

What is wrong:
The `[PASS]` lines for these two rules in the report are not credible against the real
codebase. Concrete counterexamples in upstream kubernetes:

  - `pkg/apis/core/validation/validation.go: ValidatePodSpec(...)` — single function
    well over 200 lines, comprising ~30 named field-level checks.
  - `pkg/apis/core/validation/validation.go: ValidatePersistentVolumeClaim(...)` — 200+
    lines, > 8 distinct returns once you count the implicit returns from `field.ErrorList`
    accumulation paths.
  - `pkg/util/iptables/iptables.go: (*runner).restoreInternal(...)` — well over 100 lines.
  - `pkg/util/taints/taints.go: ParseTaints(...)` — generally close to or over 100 lines
    depending on the release branch.

If the rule fired against the real module, both would produce a non-trivial number of
violations. They didn't. Combined with [F1], the `[PASS]` is therefore a no-op pass: the
rule's matching pattern picked up zero functions in zero packages.

Even granting [F1] is fixed, both caps are still arguable for the upstream codebase:
- maxLines: 100 for `pkg/util/**` is unreasonably tight; many util packages have helpers
  in the 100–250 line range that are *not* signs of refactoring debt — they are
  state-machine implementations (iptables, ipvs, conntrack) that legitimately don't
  decompose smaller.
- maxReturnValues: 8 for `pkg/apis/**` is realistic for *Go return values* (number of
  values returned per `return` statement) but is **not** the same as the number of
  return paths or named fields. Verify which of those arch-go is counting before
  trusting the cap.

Why it matters:
The function-complexity rules are the most likely class to actually catch quality
regressions when wired correctly, and the most likely to produce noisy false positives
when not. Currently they do neither — they sit on a 100% compliance line that is
structurally impossible to fail.

How to fix it:
1. Resolve [F1] first.
2. Run `arch-go check` and confirm the rule produces violations in real upstream code.
   If it doesn't, the rule is either wrong-grained or arch-go's `maxReturnValues` is
   counting something other than what was assumed.
3. If, after [F1], the rule produces hundreds of upstream violations, decide whether
   to (a) baseline existing violations with a `# baseline:` directive, (b) loosen the
   limits to a value that flags only outliers (e.g. maxLines: 250 for util,
   maxLines: 350 for apis), or (c) drop the rule since legacy validators predate the
   conventions it encodes. Pick (a) or (b); (c) is a regression in coverage.

```yaml
# After empirical baseline:
functionsRules:
  - description: >
      Shared utility functions in pkg/util/** are simple helpers — outliers above the
      cap are indicators of misplaced state machines that belong in their own component.
    package: "k8s.io/kubernetes/pkg/util/**"
    maxReturnValues: 5
    maxLines: 250          # was 100; calibrated against upstream baseline
    # If arch-go grows --baseline, list known violations there rather than relaxing.

  - description: >
      Validation functions in pkg/apis/** collect field errors and return them. Cap is
      a refactor signal, not a hard upper bound — known outlier ValidatePodSpec is
      grandfathered.
    package: "k8s.io/kubernetes/pkg/apis/**"
    maxReturnValues: 8
    maxLines: 350          # was 200; calibrated against upstream baseline
```

The exact numbers are placeholders pending the run; the point is that *one of* (a) the
rule must fire on real code, or (b) the limits must be calibrated against real code,
or (c) the rule must be removed. The current state — where the rule asserts a tight
cap that is silently vacuous — is the worst combination.
```

```
[F3] SEVERITY: CRITICAL
Category: Coverage Gap | Header Lies (carryover from review #1 [F8])
Affected Rule / Constraint: `pkg/scheduler/**` rule (line 184), `pkg/controller/**`
rule (line 170), `pkg/registry/**` rule (line 153), header SCOPE OF ANALYSIS comment
(lines 49–66), test-helper packages.

What is wrong:
The header acknowledges (lines 57–66):

    Test helpers in particular WILL be subjected to the rules below if their
    parent glob matches (e.g., `pkg/controller/**` matches `pkg/controller/testutil`).
    Treat any false positives surfaced by test-only packages as a signal to either
    narrow the rule or accept the coupling for that helper.

That is an honest description, but it is *not* a fix. Three concrete first-party test
helper packages are inside in-scope rule globs:

| Test helper                                      | Matched by glob                           |
|--------------------------------------------------|-------------------------------------------|
| `k8s.io/kubernetes/pkg/scheduler/testing`        | `pkg/scheduler/**` (deny: controller, kubelet, proxy, kubeapiserver, controlplane) |
| `k8s.io/kubernetes/pkg/controller/testutil`      | `pkg/controller/**` (deny: scheduler, kubelet, proxy, kubeapiserver, controlplane) |
| `k8s.io/kubernetes/pkg/registry/registrytest`    | `pkg/registry/**` (deny: controller, kubelet, proxy, scheduler, controlplane)      |

Each of these helpers exists *to set up cross-layer test environments*. Once [F1] is
fixed and these rules actually fire, the helpers will produce immediate violations
that block the build, even though the imports are correct. Operators will then either
disable the rule, add a wide allow-list, or carve out the helper paths — all of which
weaken the rule for production code as well.

`pkg/volume/testing` is technically not matched by any current glob (the
`pkg/volume/**` rule is too narrow to make this matter, and the global `pkg/** → cmd/**`
rule does not block the cross-component imports), so volume/testing will not fire false
positives — but the asymmetry between the four "test helper" paths is already a sign
the issue was thought about and not resolved.

Why it matters:
The classic outcome of an in-tree CI rule that produces immediate false positives is
that the team disables the rule entirely rather than disable it case-by-case. arch-go
v1 has no `excludedPackages` directive, so the only mechanical fix is to narrow the
`package:` glob — which is uglier YAML but the only forward-compatible option.

How to fix it:
arch-go does not support exclusion at the rule level. Two options:

(a) Encode the exclusion mechanically by *positively* listing the in-scope sub-trees,
    omitting the test helper:

```yaml
  - description: >
      Scheduler component. Test-helper sub-tree pkg/scheduler/testing is intentionally
      excluded — it exists to wire cross-layer integration tests.
    package: "k8s.io/kubernetes/pkg/scheduler/{framework,profile,metrics,util}/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
  # Verify arch-go supports brace expansion in package globs; if not, split the rule
  # into one entry per sub-tree.

  - description: Controller manager. Test-helper sub-tree pkg/controller/testutil excluded.
    package: "k8s.io/kubernetes/pkg/controller/{bootstrap,certificates,...}/**"
    shouldNotDependsOn: { ... }

  - description: Registry. Test-helper sub-tree pkg/registry/registrytest excluded.
    package: "k8s.io/kubernetes/pkg/registry/{rbac,resource}/**"
    shouldNotDependsOn: { ... }
```

(b) Keep the broad `**` globs but plan to either (i) move the helpers under a `testing/`
    sub-tree that the rule excludes (`pkg/{scheduler,controller,registry}/internaltesting/...`)
    or (ii) wait for arch-go to grow `excludedPackages:`. This is a code-side fix, not a
    config-side fix. Document the plan in the YAML header so the false positives are
    expected.

The current state — header acknowledges the issue, rules ignore the issue — is the
worst of both worlds: when the rules finally run, the violations will surprise the team.

Important: arch-go's `package:` glob support for brace expansion is **not documented**
in fdaines/arch-go v1.5.x. Verify before relying on it; if unsupported, generate one
rule per sub-tree mechanically.
```

```
[F4] SEVERITY: HIGH
Category: Coverage Gap | Asymmetric Layer Isolation
Affected Rule / Constraint: Header Layer 3 ("Registry ... Must not depend on component
layers (controller, kubelet, proxy, scheduler) or control plane"); rule at line 153.

What is wrong:
The `pkg/registry/**` rule denies controller, kubelet, proxy, scheduler, controlplane
(line 156–160) but **not** `pkg/kubeapiserver/**`. The header is consistent with the
other Layer-2 rules (which all deny kubeapiserver), the Layer-1 rule (which denies
kubeapiserver), and the four parallel-component rules (which all deny each other and
controlplane). Only registry has the gap.

The header itself classifies kubeapiserver as a Layer-4 component:

    Layer 4 - Component Packages
      - API Server Support  (pkg/kubeapiserver/**)
      - ...

Layer 3 (registry) → Layer 4 (kubeapiserver) is an inversion the header forbids in
prose ("Must not depend on component layers"), but the YAML allows.

Why it matters:
A registry package importing pkg/kubeapiserver would be a real architectural inversion:
registry sits below kubeapiserver in the assembly graph (controlplane wires
registry → kubeapiserver in its API server constructor). A registry/foo package
adding `import "k8s.io/kubernetes/pkg/kubeapiserver/admission"` would silently pass
this rule today.

How to fix it:
Add `pkg/kubeapiserver/**` to the registry deny list:

```yaml
  - description: >
      Registry packages (pkg/registry/**) implement storage-backed resource handlers
      for the API server. They must not import component packages (controller manager,
      kubelet, proxy, scheduler, kubeapiserver) or controlplane assembly, which depend
      on the registry instead.
    package: "k8s.io/kubernetes/pkg/registry/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"      # ADD
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

Verify this passes against upstream before merging — if real registry packages today
import kubeapiserver/options or kubeapiserver/admission, decide whether to fix the
source or carve a narrow exception. Do not lock in the inversion.
```

```
[F5] SEVERITY: HIGH
Category: Coverage Gap | Glob Sibling Mismatch
Affected Rule / Constraint: cmd-side rules at lines 432–440; package
`k8s.io/kubernetes/cmd/kubectl-convert` (line 28 of the package list).

What is wrong:
The kubectl entry-point rule uses

    package: "k8s.io/kubernetes/cmd/kubectl/**"

The glob `cmd/kubectl/**` matches packages whose import path begins with
`cmd/kubectl/` (i.e. literal `cmd/kubectl/<anything>`). It does **not** match the
sibling binary `cmd/kubectl-convert` — which is its own first-party tool listed in the
package structure file. The same gap exists for any future `cmd/kubectl-foo` plugin.

`cmd/kubectl-convert` is a kubectl-adjacent binary that ships in the Kubernetes
release. It is just as much a "client-side binary" as `cmd/kubectl` and is subject to
the same architectural constraints (no controller/scheduler/kubelet/proxy/controlplane/
kubeapiserver imports). The rule does not match it.

The same shape may be true for `cmd/kubemark` (line 31) — kubemark is a hollow-node
test binary that lives next to the others; it has no rule. Less critical but worth
flagging.

Why it matters:
A wrong import in `cmd/kubectl-convert/main.go` (e.g. accidentally importing
`pkg/kubelet/cm`) would not match any rule and would pass arch-go silently. The
intent of the kubectl rule is "the kubectl client family", not just "the kubectl
binary".

How to fix it:
Either widen the glob to match all kubectl-family binaries, or add a sibling rule.
The first is cleaner because it auto-covers future kubectl-family plugins:

```yaml
  - description: >
      Client-side kubectl-family binaries (cmd/kubectl, cmd/kubectl-convert, future
      cmd/kubectl-* plugins) must not import server-side packages.
    package: "k8s.io/kubernetes/cmd/kubectl*/**"     # was: cmd/kubectl/**
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
```

If arch-go's globbing does not support `kubectl*` as an internal-segment wildcard,
fall back to two rules:

```yaml
  - package: "k8s.io/kubernetes/cmd/kubectl/**"
    shouldNotDependsOn: { internal: [ ... ] }
  - package: "k8s.io/kubernetes/cmd/kubectl-convert/**"
    shouldNotDependsOn: { internal: [ ... ] }
```

(arch-go `**` is the "match any path segments" wildcard; mid-segment `*` works for
`fdaines/arch-go` per its tests, but verify before relying on it. The two-rule form is
schema-safe.)
```

```
[F6] SEVERITY: HIGH
Category: Asymmetric cmd-side Fences
Affected Rule / Constraint: rules at lines 386 (`cmd/kubelet/**`), 374
(`cmd/kube-proxy/**`), 407 (`cmd/kube-controller-manager/**`).

What is wrong:
The cmd-side rules are not symmetric with each other or with their pkg-side analogues.
Three concrete asymmetries:

(1) `cmd/kubelet/**` (line 386) denies controlplane, kubeapiserver, scheduler. It does
    NOT deny `pkg/controller/**` or `pkg/proxy/**`. The kubelet binary is a node-side
    binary; it has no business importing pkg/controller/* (that would mean dragging the
    controller-manager's reconciliation loops into the kubelet binary) or
    pkg/proxy/* (that would mean dragging the kube-proxy backend graph into the
    kubelet). This pattern was applied to cmd/kube-proxy and cmd/kube-controller-manager
    but not here.

(2) `cmd/kube-proxy/**` (line 374) denies controller, scheduler, kubelet. It does NOT
    deny `pkg/kubeapiserver/**` or `pkg/controlplane/**`. kube-proxy is a node-side
    binary that talks to the apiserver via clientset — it should never import the
    apiserver assembly graph. The pkg-side rule (`pkg/proxy/**` denies kubeapiserver,
    controlplane) is in place; the cmd-side rule that runs the binary is missing it.

(3) `cmd/kube-controller-manager/**` (line 407) denies scheduler, kubelet, proxy. It
    does NOT deny `pkg/controlplane/**` or `pkg/kubeapiserver/**`. Compare with
    `cmd/cloud-controller-manager/**` (line 419) which DOES deny controlplane. There is
    no documented reason why kube-controller-manager — also a separate binary that
    talks to apiserver via clientset — would be allowed to back-import the apiserver
    assembly while cloud-controller-manager is not.

Why it matters:
Each cmd/X tree becomes the import root of its compiled binary. A wrong import in
cmd/kubelet/app dragging in pkg/controller code means the kubelet binary now compiles
with the entire controller-manager graph, defeating the whole point of separate
binaries. The same drift in cmd/kube-controller-manager → pkg/controlplane would mean
the controller-manager binary now embeds the apiserver assembly graph.

How to fix it:
Make the cmd-side rules complete with respect to the documented "separate binaries"
invariant:

```yaml
  - description: >
      The kubelet binary tree (cmd/kubelet/**) is a node-side process. It must not
      import sibling-binary packages (pkg/controller for kube-controller-manager,
      pkg/proxy for kube-proxy) and must not import control-plane assembly.
    package: "k8s.io/kubernetes/cmd/kubelet/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/controller/**"      # ADD
        - "k8s.io/kubernetes/pkg/proxy/**"           # ADD

  - description: >
      The kube-proxy binary tree (cmd/kube-proxy/**) is a node-side process. It must
      not import sibling-binary packages or control-plane assembly.
    package: "k8s.io/kubernetes/cmd/kube-proxy/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"   # ADD
        - "k8s.io/kubernetes/pkg/controlplane/**"    # ADD

  - description: >
      The kube-controller-manager binary tree must not import sibling-binary packages
      or apiserver assembly. It talks to the apiserver via clientset, never via
      direct package import.
    package: "k8s.io/kubernetes/cmd/kube-controller-manager/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"   # ADD
        - "k8s.io/kubernetes/pkg/controlplane/**"    # ADD
```

Apply the same uniform "separate binary" invariant to `cmd/kubeadm/**` (currently denies
controller, scheduler, kubelet, proxy, controlplane — should also deny kubeapiserver,
since kubeadm uses generated manifests and clientset, not direct apiserver imports):

```yaml
  - description: kubeadm uses generated manifests and clientset only.
    package: "k8s.io/kubernetes/cmd/kubeadm/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"   # ADD
```

(Verify against upstream — kubeadm has historically reached into pkg/kubeapiserver/options
for shared flag definitions; if true today, decide whether to fix the source or accept
the dependency. Do not silently allow it.)
```

```
[F7] SEVERITY: HIGH
Category: Overly Broad / Overly Narrow (interface-name resolution semantics)
Affected Rule / Constraint: All four `namingRules` (lines 471–517) — `Interface`,
`Manager`, `Proxier`, `Storage`.

What is wrong:
arch-go's `interfaceImplementationNamingRule.structsThatImplement` matches by **simple
interface name** (no package qualifier). That has two failure modes for this
configuration:

(1) **`Manager` is one of the most reused identifiers in the kubelet tree.** The
    package list shows at least 13 sub-packages whose API includes a `Manager` (or
    similarly-named) interface or façade:
        pkg/kubelet/cm           (ContainerManager)
        pkg/kubelet/configmap    (Manager)
        pkg/kubelet/secret       (Manager)
        pkg/kubelet/clustertrustbundle (Manager)
        pkg/kubelet/podcertificate (Manager)
        pkg/kubelet/certificate  (Manager — and CertificateManager)
        pkg/kubelet/checkpointmanager (Checkpointer)
        pkg/kubelet/eviction     (Manager)
        pkg/kubelet/images       (Manager — and ImageManager)
        pkg/kubelet/runtimeclass (Manager)
        pkg/kubelet/status       (Manager)
        pkg/kubelet/token        (Manager)
        pkg/kubelet/volumemanager (VolumeManager)
    Because arch-go matches by simple name, *every* interface literally named `Manager`
    in pkg/kubelet/** is tied to the same rule, regardless of which sub-package
    declared it. A struct that implements `secret.Manager` (intentionally NOT named
    `*Manager` in some helper) and `images.Manager` simultaneously is checked against
    the same rule. False positives are likely; false negatives are likely too (a
    struct intended to implement a *different* Manager — e.g. a wrapper that adapts
    the secret cache — must still be named `*Manager`, even if it is intentionally
    a "Cache" shape).

(2) **`Interface` is a generic placeholder that appears in dozens of pkg/controller
    sub-packages** (the Kubernetes idiom is `package job; type Interface interface { ... }`
    so users can write `job.Interface`). The rule "structs implementing Interface in
    pkg/controller/** must end with Controller" therefore matches structs across
    every sub-package's `Interface` declaration. This is probably intended, but it is
    not what the rule's *description* claims — the description says "the canonical
    controller Interface", which implies a single named type. arch-go cannot
    distinguish a `pkg/controller/job.Interface` from a `pkg/controller/podautoscaler.Interface`
    by package; the rule applies uniformly. Verify on a real run that both produce the
    same `*Controller` enforcement and that this matches the team's intent.

(3) **`Storage` is also non-unique.** `pkg/registry/registrytest` declares a `Storage`
    interface; many sub-packages do too; some are wrappers (`UpdateStorage`,
    `CreateStorage`); a `Storage` interface in `pkg/printers/storage` is unrelated to
    REST storage. Without package qualifiers, the rule sweeps too widely.

(4) **`Proxier` happens to be unique** — it is declared in `pkg/proxy/types.go` and
    nowhere else with that simple name in the matched globs. The Proxier rule is
    fine in this codebase (today). It is the only one of the four that is robust to
    the simple-name semantics.

Why it matters:
The four naming rules are now in the correct schema (vs. review #1 [F1]) but the
schema's matching semantics are coarser than the rules' descriptions imply. Two
outcomes are possible once these rules actually run (post-[F1] of this round):

  - Cross-package interface name collisions cause unexpected violations on structs
    that the team did not intend to constrain (false positives — most likely under
    `Manager`, `Storage`, `Interface`).
  - Important controller / manager / storage implementations that *don't* implement
    the simple-named interface in question (because they implement a more specific
    interface, like `Reconciler` or `Provider`) escape entirely (false negatives).

How to fix it:
There is no clean fix in arch-go v1.5.x — the schema doesn't support package-qualified
interface names. Three pragmatic mitigations:

(a) Narrow the `package:` scope so the rule only sees one declaration of the interface
    name. For example, instead of one rule for all of `pkg/kubelet/**`, write per-
    sub-package rules that each match exactly one `Manager` declaration:

```yaml
  - description: >
      Volume manager implementations must end with Manager.
    package: "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "VolumeManager"      # use the qualified-shaped name
      shouldHaveSimpleNameEndingWith: "Manager"

  - description: Image manager implementations must end with Manager.
    package: "k8s.io/kubernetes/pkg/kubelet/images/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "ImageManager"
      shouldHaveSimpleNameEndingWith: "Manager"
  # ... per sub-package
```

  This is verbose, but each rule is unambiguous about *which* Manager interface it
  applies to. The strict-mode trade-off is verbosity vs. precision.

(b) Drop the `Manager` rule (which is over-broad due to the name collision) and keep
    the `Controller`, `Proxier`, `Storage` rules with a comment that calls out the
    simple-name semantics:

```yaml
  - description: >
      NOTE: arch-go matches structsThatImplement by simple interface name, not
      package-qualified name. This rule applies to *every* interface literally named
      `Storage` in pkg/registry/**, including unintended ones; verify the rule's
      effective scope by inspecting which structs it flags before relying on it.
    package: "k8s.io/kubernetes/pkg/registry/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Storage"
      shouldHaveSimpleNameEndingWith: "Storage"
```

(c) Replace the four naming rules with a single semantic check that is unambiguous:
    "exported structs in pkg/controller/{cronjob,job,daemon,deployment,...}/**
    that have a `Run(ctx)` method must end with `Controller`". arch-go does not
    support method-signature-based selection, so this is not directly expressible —
    it would require a custom analyzer rather than arch-go.

Recommended path: (a) for the kubelet `Manager` family (because it's the one most
likely to misfire), with explicit per-sub-package rules; (b) for the others, with the
disclaimer comment so a future maintainer doesn't assume more precision than the
schema offers.
```

```
[F8] SEVERITY: MEDIUM
Category: Coverage Gap (Layer 1 → leaf-component leak)
Affected Rule / Constraint: `pkg/util/**` rule at line 93–104; header Layer 1 ("must
not import any other pkg/** layer above it").

What is wrong:
The Layer 1 rule denies api/apis/registry/controller/kubelet/proxy/scheduler/
kubeapiserver/controlplane (lines 96–104). The header explicitly says util "must not
import any other pkg/** layer above it (including api, apis, registry)". Two layers
above are not in the deny list:

  - `pkg/volume/**` (the leaf-component subsystem with its own dedicated rule at line 258)
  - `pkg/credentialprovider/**` (the leaf-component subsystem at line 272)

If the YAML treats volume and credentialprovider as Layer-4-ish leaves (which is what
their own rules suggest — both deny everything above them in the lattice), then util,
the *lowest* layer, must not import them either. A `pkg/util/foo` package that grew
an import on `pkg/volume/util` would silently pass.

The same applies to `pkg/kubectl` (the in-tree kubectl library), `pkg/kubemark`, and
`pkg/printers/**`, all of which are higher-layer leaves not covered by the util deny
list.

Why it matters:
Slow-rot architectural drift. The whole point of pinning util at the bottom is that it
must not have transitive dependencies on anything above. This is the rule whose
violation tends to bite during dependency upgrades, vendor reshuffles, and builds where
build-tool packages start cherry-picking helpers from leaves that "happen to be there".

How to fix it:
Either expand the util deny list explicitly, or add a single broad
"`pkg/util/**` denies `pkg/{anything-not-vendor-or-util}/**`" rule. The latter is
cleaner but harder to express in arch-go's positive-glob model. The pragmatic fix is
to enumerate:

```yaml
  - description: >
      Shared utility packages (pkg/util/**) are the lowest internal layer. They must
      not import any higher pkg/** layer including api/apis types, registry, component
      layers, controlplane, and the leaf-component subsystems (volume,
      credentialprovider, kubectl, kubemark, printers, security).
    package: "k8s.io/kubernetes/pkg/util/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/api/**"
        - "k8s.io/kubernetes/pkg/apis/**"
        - "k8s.io/kubernetes/pkg/registry/**"
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/volume/**"            # ADD
        - "k8s.io/kubernetes/pkg/credentialprovider/**" # ADD
        - "k8s.io/kubernetes/pkg/kubectl"              # ADD (single package)
        - "k8s.io/kubernetes/pkg/kubemark"             # ADD
        - "k8s.io/kubernetes/pkg/printers/**"          # ADD
        - "k8s.io/kubernetes/pkg/security/**"          # ADD
        - "k8s.io/kubernetes/pkg/securitycontext"      # ADD
```

(Some of these — kubectl, kubemark — may today legitimately not be referenced by util
at all; the rule is preventive. Run against upstream to confirm zero false positives
before merging.)
```

```
[F9] SEVERITY: MEDIUM
Category: Coverage Gap (subsystems still unconstrained)
Affected Rule / Constraint: package list packages plugin/pkg/auth, pkg/auth/nodeidentifier,
pkg/security/**, pkg/securitycontext, pkg/serviceaccount, pkg/probe/**, pkg/printers/**,
pkg/kubectl, pkg/kubemark, pkg/features, pkg/fieldpath, pkg/capabilities,
pkg/certauthorization, pkg/client/conditions, pkg/client/tests, pkg/cluster/ports,
pkg/routes, pkg/generated/**.

What is wrong:
After the review-#1 fixes, every package under `pkg/**` is at least matched by the
catch-all `pkg/** → cmd/**` rule (line 289), which is a real gain. But the
component-isolation rules — the ones that actually encode "X must not import Y where
Y is a higher layer" — still skip every subsystem in the list above. None of them is
constrained against importing pkg/controller/**, pkg/scheduler/**, pkg/kubelet/**,
pkg/proxy/**, pkg/kubeapiserver/**, or pkg/controlplane/**.

In particular:
- `plugin/pkg/auth` is matched by NO rule (the catch-all is `pkg/**`, not `plugin/**`).
  It can import any pkg/X with no objection.
- `pkg/security/**`, `pkg/securitycontext`, `pkg/serviceaccount` — security primitives
  used by both apiserver and kubelet. They should be leaf-side (one-way: components
  import security, not the reverse). Currently nothing prevents `pkg/security/foo`
  from importing `pkg/kubelet/**`.
- `pkg/printers/**` and `pkg/kubectl` — kubectl-side libraries that should be
  client-shaped. Today nothing prevents them from importing `pkg/controlplane/**` etc.

Why it matters:
The catch-all rule covers `pkg → cmd` only. The architectural-drift class that matters
most — `pkg/leaf → pkg/component` — has no constraint on these subsystems. The
review-#1 [F5] gap was *partially* closed (volume + credentialprovider) and is now
*partially* still open.

How to fix it:
Add a single broad "leaf-package isolation" rule for the unconstrained subsystems and
a separate one for `plugin/**`:

```yaml
  - description: >
      Security and auth primitives are leaf packages used by components; they must not
      back-import any component layer.
    package: "k8s.io/kubernetes/pkg/{security,securitycontext,serviceaccount,probe,auth}/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/cmd/**"

  - description: >
      kubectl-side libraries (pkg/kubectl, pkg/printers, pkg/kubemark) are client-shaped
      and must not import server-side component packages.
    package: "k8s.io/kubernetes/pkg/{kubectl,printers,kubemark}/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      In-tree auth plugins (plugin/pkg/auth/**) are used by kubeapiserver/admission;
      they must not import component or controlplane packages.
    package: "k8s.io/kubernetes/plugin/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/cmd/**"
```

(Verify the brace-expansion glob is supported by arch-go before merging; if not, split
into one rule per subsystem.)
```

```
[F10] SEVERITY: MEDIUM
Category: Vacuous Rule (intra-layer kubelet/types pattern with no effective sub-tree)
Affected Rule / Constraint: rule at line 332 — `package: "k8s.io/kubernetes/pkg/kubelet/types"`.

What is wrong:
The rule targets the literal package `pkg/kubelet/types` (no `/**`). The header
acknowledges this is intentional — it's a leaf "type-only" package. That's fine in
principle, but the rule's deny list includes globs that already cannot match anything
from a leaf type-only package:

    - "k8s.io/kubernetes/pkg/kubelet/server/**"
    - "k8s.io/kubernetes/pkg/kubelet/kuberuntime/**"
    - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
    - "k8s.io/kubernetes/pkg/kubelet/config/**"

A leaf type package that imports anything cyclical with the kubelet's higher-level
sub-packages would create a Go-compile-time import cycle in any well-formed
build (kubelet/server/**, kubelet/kuberuntime/**, etc., all import kubelet/types
themselves). The rule is *de facto* a redundant statement of an invariant the Go
compiler already enforces, similar to review-#1 [F10] for the now-deleted
"no main()" rules.

Why it matters:
Not a critical defect — but a maintenance smell. A reader of this YAML might mistake
the rule for a real architectural fence and not realize Go's import-cycle prohibition
already covers it. After [F1] is fixed and the report shows the rule firing against
real code, expect zero violations *forever*; that's the signal it never could.

How to fix it:
Either drop the rule, or scope it to a non-vacuous direction. The non-vacuous direction
would be to deny all *kubelet sub-packages* from `pkg/kubelet/types`, not just the
four named ones — but again, Go's cycle prohibition handles that. The cleanest fix is
deletion with a comment explaining why:

```yaml
  # The pkg/kubelet/types leaf is a type-only package. Go's import-cycle prohibition
  # already prevents it from importing any pkg/kubelet sub-package that imports
  # kubelet/types. No arch-go rule is necessary.
  #
  # If a future refactor moves type definitions out of leaf packages, reinstate a
  # dedicated rule here.
```

If the rule is kept for documentation purposes, leave a comment explaining that the
deny list intentionally lists only the most likely directions of accidental import,
not an exhaustive set.
```

```
[F11] SEVERITY: MEDIUM
Category: Header Inaccuracy (description claim vs. enforced rules)
Affected Rule / Constraint: top-level `description:` (lines 70–78); header Layer 4
prose (lines 17–25) "fully symmetric non-coupling lattice".

What is wrong:
The top-level description advertises:

    Rules prevent parallel component layers (controller, kubelet, proxy, scheduler,
    kubeapiserver) from importing each other...

That is now true *for the pkg-side rules* (after the review-#1 [F2] fixes). But it is
NOT true for the cmd-side rules: see [F6]. The description claims symmetry across the
layer, while the cmd-side fences encoding the binary boundaries are still asymmetric.

Similarly, the Layer-4 prose says "fully symmetric non-coupling lattice", but the
registry rule (Layer 3) does not deny kubeapiserver (see [F4]). A reader trusting the
prose will mis-estimate the YAML's coverage.

Why it matters:
Documentation drift. A future maintainer extending the rules in good faith uses the
prose as a contract; the YAML reads more like a 90% implementation of the prose than
a 100% one. This is the same class as the review-#1 [F8] header-lies-about-exclusion
finding — corrected for that exact lie, but reintroduced as "header overstates
symmetry".

How to fix it:
Either tighten the YAML to match the prose (apply [F4], [F6]) or soften the prose
to reflect what's enforced. The first is preferred. If the prose is softened, name
the asymmetries explicitly so they don't get rediscovered:

```yaml
description: >
  Enforces the Kubernetes component architecture as documented at
  https://kubernetes.io/docs/concepts/overview/components/. Pkg-side rules form a
  symmetric Layer-4 non-coupling lattice across controller/scheduler/kubelet/proxy/
  kubeapiserver. Cmd-side rules block cross-binary coupling: kubectl is client-only;
  kubeadm uses generated manifests; kube-apiserver/cloud-controller-manager/
  kube-controller-manager/kube-scheduler/kube-proxy/kubelet may not import each
  other's pkg/** subtrees. Naming rules apply interface-scoped suffix conventions to
  Controller/Manager/Proxier/Storage implementations; arch-go's simple-name interface
  resolution may produce false positives where the same interface name is reused
  across sub-packages — see in-line comments on each rule.
```
```

```
[F12] SEVERITY: LOW
Category: Redundancy
Affected Rule / Constraint: `pkg/controlplane/**` deny on `cmd/**` (line 249) AND
global `pkg/** → cmd/**` deny (line 292).

What is wrong:
The `pkg/controlplane/**` rule denies cmd/** as a "belt-and-braces" entry (header
comment line 243: "The cmd/** ban is also enforced globally below."). The global
`pkg/**` rule on line 289 already covers this case (every package under pkg/** is a
strict superset of pkg/controlplane/**), so the controlplane→cmd deny is fully
subsumed.

Why it matters:
Cosmetic. Redundant rules are maintenance cost: a future change to the global rule
would reasonably forget to update the local one, leading to confusion if the two
diverge. Not a correctness issue.

How to fix it:
Drop the cmd/** entry from the controlplane rule, since the global rule covers it:

```yaml
  - description: >
      Control plane assembly packages (pkg/controlplane/**) compose API, controller,
      scheduler, and kubeapiserver layers, but must not import node-side components
      (kubelet, proxy). The cmd/** ban is enforced globally by the pkg/** → cmd/**
      catch-all below.
    package: "k8s.io/kubernetes/pkg/controlplane/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        # cmd/** is covered by the global pkg/** rule below.
```
```

```
[F13] SEVERITY: LOW
Category: Documentation / Minor Inaccuracy
Affected Rule / Constraint: namingRules description for `pkg/kubelet/**` (lines
484–488).

What is wrong:
The description says:

    Structs in pkg/kubelet/** that implement a Manager interface (the kubelet's
    manager-based subsystem façade, e.g. VolumeManager, ImageManager, CertificateManager)

But the rule's `structsThatImplement: "Manager"` matches *only* interfaces with the
literal simple name `Manager` — it does NOT match `VolumeManager`, `ImageManager`,
or `CertificateManager` (those are different simple names, not `Manager`). The
description is misleading: it implies the rule covers the whole "manager-suffixed
interfaces" family, but the rule's literal semantics cover only `Manager`-by-itself.

Same shape concern for the registry rule — the description says "Strategy, Registry,
and helper types are not constrained by this rule" (true) but does not warn that
`Storage` is matched by simple name only and may inadvertently include unrelated
`Storage` interfaces.

Why it matters:
The description is the comment a future maintainer reads when deciding whether to
extend the rule. Misleading prose increases the odds of a wrong extension.

How to fix it:
Tighten the description, or — better — go with the per-sub-package approach in [F7]
and rewrite the description per rule.

```yaml
  - description: >
      Structs in pkg/kubelet/** that implement an interface literally named `Manager`
      (the kubelet idiom for sub-system façades; the simple type name `Manager` is
      reused across volumemanager, configmap, secret, images, status, certificate,
      eviction, runtimeclass, token, etc.) must be suffixed with 'Manager'. Sub-system
      interfaces with longer names (VolumeManager, ImageManager, CertificateManager,
      ContainerManager) are NOT covered by this rule because arch-go matches by simple
      interface name only. Authors of new sub-system façades should either name the
      interface `Manager` to fall under this rule, or add a per-sub-package rule.
    package: "k8s.io/kubernetes/pkg/kubelet/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Manager"
      shouldHaveSimpleNameEndingWith: "Manager"
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

The schema fixes from review #1 are real and present in `arch-go.yml`. They are also
**unverifiable** in the current report because the arch-go run executed against a
module with zero matching packages (`COVERAGE RATE 0%`). The order of operations for
review #2 is therefore inverted relative to review #1: until the report can demonstrate
non-zero coverage, the rules' correctness is a paper claim.

**Order of fixes** (highest leverage first):

1. **[F1]** — fix the Makefile so it verifies the module is `k8s.io/kubernetes` and
   the report's coverage rate is above a non-trivial floor. Without this, every other
   "[PASS]" in the report is meaningless.
2. **[F2]** — once [F1] is in place, re-run the function-rules and confirm they fire
   against real upstream code; calibrate the limits or grandfather known outliers.
3. **[F3]** — narrow the Layer-3/Layer-4 globs so test helpers (pkg/scheduler/testing,
   pkg/controller/testutil, pkg/registry/registrytest) no longer match the production
   rules. arch-go has no exclusion mechanism, so the only fix is positive
   sub-tree enumeration.
4. **[F4], [F5], [F6]** — close the asymmetric isolation gaps (registry → kubeapiserver,
   cmd/kubectl-convert coverage, cmd/kubelet & cmd/kube-proxy & cmd/kube-controller-manager
   missing denies).
5. **[F7]** — narrow the naming rules' scopes so the simple-name interface semantics
   don't silently misfire on collisions (especially for `Manager` and `Interface`).
6. **[F8], [F9]** — extend Layer-1 and leaf-subsystem coverage to volume,
   credentialprovider, security, serviceaccount, plugin/**.
7. **[F10]–[F13]** — clean up the redundant kubelet/types rule, redundant
   controlplane→cmd entry, misleading descriptions, and overstated header symmetry.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:
- The coverage line reads `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The functions-rule for `pkg/apis/**` (with the current `maxLines: 200`) actually
  produces violations against `validation.ValidatePodSpec` and similar — that is the
  empirical proof the rule is no longer vacuous.
- The cmd/kubelet, cmd/kube-proxy, cmd/kube-controller-manager rules produce zero
  violations (they should, post-fix).
- The naming rules surface a non-zero number of structs they actually scope to;
  zero matches indicates [F7]-class scope mismatches.

Until the coverage line is non-zero AND the function rules show empirical
violations on a known-violating fork, this report should not be a CI gate.

---

**End of Review #2.**
