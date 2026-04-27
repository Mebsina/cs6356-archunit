# Adversarial Review #4 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 4

---

## Executive Summary

- **Total documented constraints identified: 25.** Ten high-level invariants are drawn from the Kubernetes "Components" reference page (`https://kubernetes.io/docs/concepts/overview/components/`): control-plane vs. node split, kube-apiserver as the only API surface, etcd persistence, kube-scheduler / kube-controller-manager / cloud-controller-manager / kubelet / kube-proxy as separate binaries, container runtime as the CRI boundary, addons as separate processes, kubectl/kubeadm as clients. Fifteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`) one-way; Layer-2 peer ban (`pkg/api/**` ↔ `pkg/apis/**`); Layer-3 (registry) → Layer-4 ban; the Layer-4 non-coupling lattice (controller / scheduler / kubelet / proxy / kubeapiserver); Layer-5 controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider / security / securitycontext / serviceaccount / probe / kubectl / kubemark / printers / plugin leaf-isolation; intra-layer fences (kubelet/container, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`, `Storage`); util / apis function-complexity caps.
- **Total rules generated: 99** — 82 `dependenciesRules` + 15 `namingRules` + 2 `functionsRules` (matches the test-report totals).
- **Coverage rate (constraint → rule mapping): ~22/25.** Round 4 closes the bare-package gap for `pkg/scheduler` / `pkg/controller` / `pkg/registry` (review #3 [F2]), the cmd-side asymmetry for `cmd/kube-apiserver/**` (review #3 [F3]), the missing `cmd/kubemark/**` rule (review #3 [F4]), the `pkg/volume/**` test-helper bleed via per-sub-package fan-out (review #3 [F6]), the cm sub-managers naming gap (review #3 [F8]), and the bare-path companions for the leaf subsystems (review #3 [F11]). The Makefile recipe is now bash-aware with `set -o pipefail` and `STATUS=$${PIPESTATUS[0]}` (review #3 [F1] partially), and the SCOPE comment is more honest. **But the round-4 fix carried over the bare-path insight inconsistently.** It was applied to `pkg/scheduler` / `pkg/controller` / `pkg/registry` and to the leaf subsystems, but it was *not* applied to the four other Layer-4/5 main components (`pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`, `pkg/controlplane`) nor to the canonical-interface-bearing kubelet manager directories (`pkg/kubelet/volumemanager`, `pkg/kubelet/images`, `pkg/kubelet/certificate`, `pkg/kubelet/cm`, the cm sub-managers, and the eight other named-manager paths). Those are the very directories where the canonical interface and its `*Impl`/`*Manager` struct are declared — the rules pointed at `<X>/**` therefore miss the only struct each rule was written to constrain.
- **Effective coverage rate from arch-go itself: still 0%.** The test report footer reads `COVERAGE RATE 0% [FAIL]` — byte-for-byte identical in compliance signal to rounds 2 and 3. The Makefile in round 4 is now correctly `bash` + `pipefail` + `PIPESTATUS[0]` (which closes the latent `STATUS=$?` bug review #3 [F1] called out), and the coverage floor of 30% is still in place. Yet the artifact submitted as input to this review still shows zero matched packages. Either (a) the run was not re-launched against an upstream checkout, (b) `PROJECT_ROOT` pointed at a stub with `module k8s.io/kubernetes` declared but zero source files, or (c) the floor was overridden at invocation time. The empirical signal is unchanged from round 2.
- **Critical Gaps** (constraints with zero or vacuous enforcement in the current report):
  - **`COVERAGE RATE 0% [FAIL]` is unchanged from rounds 2 and 3** ([F1]). 99 of 99 rules `[PASS]`-ing against zero matched packages is the same vacuous-pass symptom three rounds running. The Makefile is now structurally correct, but the report is not.
  - **The bare-package fix from round 3 was applied to scheduler/controller/registry/leaves but NOT to the other four Layer-4/5 main components** ([F2]). `pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`, and `pkg/controlplane` all exist as packages in the package list (lines 137, 189, 131, 121 respectively) and contain canonical top-level files (`pkg/kubelet/kubelet.go`, `pkg/proxy/types.go`, `pkg/kubeapiserver/<top-level>.go`, `pkg/controlplane/instance.go` historically). None of those four bare paths are matched by any rule today; only their `**` sub-trees are. Review #3 [F2] was closed for three subsystems and left open for four others; the round-4 description claims "a fully symmetric Layer-4 non-coupling lattice" while four nodes of the lattice are still structurally exempt at the bare path.
  - **The 12+ kubelet `*manager` naming rules and the `pkg/kubelet/cm` ContainerManager rule are vacuous for the canonical implementer** ([F3]). Each of those rules uses `<X>/**` (e.g., `pkg/kubelet/volumemanager/**`, `pkg/kubelet/cm/**`, `pkg/kubelet/cm/cpumanager/**`). arch-go's `**` glob does not match the bare `<X>` package, but every canonical `Manager`/`ContainerManager` interface and its primary implementing struct (`type volumeManager struct { ... }`, `type containerManagerImpl struct { ... }`, `type policyManager struct { ... }`) lives in the bare package, not in any sub-sub-package. The naming rules are therefore aimed precisely at directories that contain only auxiliary helpers (cache, reconciler, populator, internal types). The structs the rules were written to constrain are unmatched.
  - **The `Storage` naming rule on `pkg/registry/**` will produce massive false positives the moment coverage rises above 0%** ([F4]). Every REST handler in upstream `pkg/registry/<group>/<resource>/storage` implements `rest.Storage` (the `Storage` simple name from `staging/src/k8s.io/apiserver/pkg/registry/rest/rest.go`) but is named `REST`, `*REST`, or `<Resource>REST`, not `<Resource>Storage`. The rule will flag every one of them as a violation. The author acknowledged the simple-name brittleness in a comment but did not narrow the rule.
  - **The `pkg/api/**` deny rule still includes the test helper `pkg/api/testing`** ([F5]). The round-3 fan-out treatment was applied to controller/scheduler/registry and (in round 4) to volume, but not to `pkg/api`. Once enforcement actually fires, `pkg/api/testing` will produce immediate cross-layer false positives.
- **Overall verdict: `FAIL`.** Round 4 contains the largest pile of correct YAML changes since round 1. But the empirical signal is unchanged ([F1]), and the central insight from review #3 [F2] (bare-path companions to every `<X>/**` rule) was applied half-systematically — kubelet/proxy/kubeapiserver/controlplane and the kubelet manager directories are the half it missed. As a result the documented "symmetric Layer-4 non-coupling lattice" still has four uncovered bare nodes, the kubelet manager naming convention is unverifiable on its canonical implementations, and a major naming rule (`Storage`) is set up to fail the entire registry tree the moment coverage rises.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope (carryover from review #3 [F1])
Affected Rule / Constraint: All 99 rules; Makefile target `arch-check`.

What is wrong:
The supplied test report still has `COVERAGE RATE 0% [FAIL]` — same as rounds
2 and 3. Round 4 has correctly fixed the Makefile recipe:

  - Line 28-29: SHELL := /bin/bash; .SHELLFLAGS := -eu -o pipefail -c
  - Lines 41, 46: ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes;
                  ARCH_GO_MIN_COVERAGE := 30
  - Lines 121-123: set -o pipefail; arch-go check | tee; STATUS=$${PIPESTATUS[0]}
  - Lines 133-139: aborts on coverage < 30%

These changes close review #3 [F1]'s pipe-status latent bug (`STATUS=$$?` after
`tee` was always 0). Good. But the test report submitted as input to this round
documents a run where:

  (a) coverage is 0%, and
  (b) all 99 rules pass.

If `make arch-check` is what produced this report against an actual upstream
`k8s.io/kubernetes` checkout, the COVERAGE RATE should be on the order of 70-90%
(arch-go reports the fraction of scanned packages that were matched by at least
one rule, and the rules cover most of pkg/** and cmd/**). 0% is the signature
of a run against a directory that arch-go could parse but contained no
matching source files — typical of:

  1. PROJECT_ROOT pointing at the `outputs/kubernetes/sonnet-4-6/` directory
     (where this YAML lives). There is no go.mod there → the Makefile aborts
     before arch-go runs. So this is NOT what happened.
  2. PROJECT_ROOT pointing at some staging directory whose `go.mod` declares
     `module k8s.io/kubernetes` but contains no actual Go source files (a
     stub layout). The module-identity check passes, arch-go scans zero
     matching packages, every rule passes vacuously.
  3. The Makefile target was bypassed entirely; `arch-go check` was invoked
     from a directory whose `go.mod` is not k8s.io/kubernetes. arch-go does
     not enforce a module match itself; it just reports 0% coverage.
  4. ARCH_GO_MIN_COVERAGE was overridden to 0 at invocation time
     (`make arch-check ARCH_GO_MIN_COVERAGE=0`), bypassing the floor.

Critically, the recipe at lines 119-148 ALSO prints the rule-by-rule pass
table BEFORE deciding whether to abort. So even when the floor fires, the
captured artifact contains the table — which is what reviewers see. This
makes a 0%-coverage / 100%-compliance report indistinguishable from a real
green run by visual inspection alone. Only the `[arch-go] FAIL: COVERAGE
RATE 0%% is below the floor` message disambiguates them, and that message is
not present in the report submitted.

Why it matters:
Three rounds of rule additions, naming refinements, and per-sub-package
fan-outs are now in the YAML. The rule count has grown from 28 (round 1) to
85 (round 3) to 99 (round 4). NONE of these rules has been demonstrated to
fire against the upstream module. The architectural review is necessarily a
paper exercise; CI gating on this report is gating on a constant.

The new bare-path gaps and naming-rule vacuity below ([F2], [F3], [F4]) are
concrete predictions that can only be confirmed empirically. Until [F1] is
resolved, the team cannot tell which of those predictions actually fire and
which silently pass.

How to fix it:
This is an operations problem, not a YAML problem. The fix is procedural:

  1. Clone or check out actual upstream `k8s.io/kubernetes` (the module, not
     the docs/diagrams). Verify with `cd <checkout> && go list -m`. The
     output must read exactly `k8s.io/kubernetes`.

  2. Run `make arch-check PROJECT_ROOT=<that-checkout>` from this directory.
     Capture the full output.

  3. Confirm the report contains `COVERAGE RATE NN% [PASS]` with NN ≥ 30
     (typically 60-90% with the current ruleset).

  4. Confirm the rule-by-rule table now contains `[FAIL]` lines for at least
     these expected violations:

       - pkg/api/testing → pkg/controller/* (test helper, see [F5])
       - pkg/registry/<group>/<resource>/storage REST struct → naming
         violation (see [F4]; many hundreds expected)
       - pkg/apis/core/validation.ValidatePodSpec → maxLines=350
         (see review #3 [F5]; expected to violate)

  5. Only after observing those expected violations and either fixing them
     in source or carving narrow exceptions in arch-go.yml should the
     report be treated as a real CI gate.

Until those four steps are completed, the YAML changes from rounds 1-4 are
unverified. Replace the empty success of "99/99 PASS at 0% coverage" with
real signal.
```

```
[F2] SEVERITY: CRITICAL
Category: Coverage Gap | Glob Semantics (extension of review #3 [F2])
Affected Rule / Constraint: rules at lines 883 (`pkg/kubelet/**`), 897
(`pkg/proxy/**`), 911 (`pkg/kubeapiserver/**`), 930 (`pkg/controlplane/**`).
Top-level description (lines 102-134) and header Layer-4 / Layer-5 prose
(lines 19-32).

What is wrong:
Review #3 [F2] flagged that arch-go's `**` glob does not match the bare
parent path, so `<X>/**` rules silently exempt the bare `<X>` package. The
fix in round 4 added bare-path sibling rules for:

  - pkg/scheduler          (line 781)
  - pkg/controller         (line 394)
  - pkg/registry           (line 242)
  - pkg/volume             (line 951)
  - pkg/credentialprovider (line 1151)
  - pkg/security           (line 1187)
  - pkg/securitycontext    (line 1217)
  - pkg/serviceaccount     (line 1244)
  - pkg/probe              (line 1270)
  - pkg/kubectl            (line 1301)
  - pkg/printers           (line 1326)
  - pkg/kubemark           (line 1352)

Excellent — that is twelve new bare-path entries. But the other Layer-4 and
Layer-5 main components were not given the same treatment:

  - pkg/kubelet            (line 137 of package list — exists)
                           Rule at line 883 covers only `pkg/kubelet/**`.
                           BARE `pkg/kubelet` is unconstrained.

  - pkg/proxy              (line 189 of package list — exists)
                           Rule at line 897 covers only `pkg/proxy/**`.
                           BARE `pkg/proxy` is unconstrained.

  - pkg/kubeapiserver      (line 131 of package list — exists)
                           Rule at line 911 covers only `pkg/kubeapiserver/**`.
                           BARE `pkg/kubeapiserver` is unconstrained.

  - pkg/controlplane       (line 121 of package list — exists)
                           Rule at line 930 covers only `pkg/controlplane/**`.
                           BARE `pkg/controlplane` is unconstrained.

These are not auxiliary leaves. They contain the canonical top-level files
of the Layer-4 / Layer-5 main components:

  - `pkg/kubelet/kubelet.go` declares `type Kubelet struct { ... }`, the
    main `Run`/`syncLoop` methods, and the kubelet's main configuration
    glue. This is the most architecturally significant file in the entire
    kubelet subsystem. It is matched by zero `dependenciesRules` other
    than the global `pkg/** → cmd/**` catch-all (line 1395). Adding
    `import "k8s.io/kubernetes/pkg/scheduler/framework"` or
    `import "k8s.io/kubernetes/pkg/controller/job"` to it today would
    silently pass arch-go.

  - `pkg/proxy/types.go` declares the `Proxier` interface, ServicePort,
    ServiceMap, and the core data carriers shared across iptables, ipvs,
    nftables, winkernel backends. This is also where the canonical
    `Proxier` interface used by the naming rule lives — a wrong
    cross-component import here would also silently pass.

  - `pkg/kubeapiserver/insecure_serving.go`, `pkg/kubeapiserver/options/...`
    historically had top-level files in the bare `pkg/kubeapiserver`
    package. The bare path is unconstrained.

  - `pkg/controlplane/instance.go` (historical) / `pkg/controlplane/<top>`
    composes the Generic API server with admission and authentication —
    the `Layer 5 → Layer 4` direction is the LEGITIMATE direction, but
    the `pkg/controlplane → kubelet/proxy` ban is the rule's stated
    purpose. The bare package is exempt from that ban today.

The same insight applies to `pkg/api` — the rule at line 210 covers
`pkg/api/**` but no bare `pkg/api` package exists in the package list, so
that one is fine by accident.

Why it matters:
The top-level description block (lines 102-134) advertises:

    Layer-3 (registry) is blocked from every Layer-4 sibling and Layer-5
    (controlplane). [...] kube-apiserver/cloud-controller-manager/
    kube-controller-manager/kube-scheduler/kube-proxy/kubelet binaries
    may not import each other's pkg/** subtrees [...]

The "every Layer-4 sibling" claim is structurally false: kubelet, proxy,
kubeapiserver, controlplane all have unconstrained bare packages today.
The header Layer-4 prose at lines 19-32 says "no Layer-4 sibling may import
any other Layer-4 sibling" — same defect, same prose.

Worse, this is precisely the same gap review #3 [F2] flagged. The fix
applied was correct in shape but selective in coverage. The reviewer's
recommendation in [F2] of round 3 was explicit: "ensure every dedicated
rule that uses a `<X>/**` glob also has a sibling rule for the bare `<X>`
package." That recommendation was implemented for ten subsystems and
skipped for these four.

How to fix it:
Add four bare-path sibling rules, mirroring the convention used for
`pkg/scheduler` (line 781), `pkg/controller` (line 394), and `pkg/registry`
(line 242). Drop them next to the existing `<X>/**` rules:

```yaml
  - description: >
      Bare pkg/kubelet package (the canonical Kubelet struct and main
      Run/syncLoop in pkg/kubelet/kubelet.go). Same Layer-4 non-coupling
      lattice as the pkg/kubelet/** rule below; the `**` glob does not
      match the bare path (see header note lines 68-75).
    package: "k8s.io/kubernetes/pkg/kubelet"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Bare pkg/proxy package (the canonical Proxier interface in
      pkg/proxy/types.go and the shared ServicePort/ServiceMap data
      carriers). Same Layer-4 non-coupling lattice as the pkg/proxy/**
      rule below.
    package: "k8s.io/kubernetes/pkg/proxy"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Bare pkg/kubeapiserver package. Same Layer-4 non-coupling lattice
      as the pkg/kubeapiserver/** rule below.
    package: "k8s.io/kubernetes/pkg/kubeapiserver"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      Bare pkg/controlplane package. Same Layer-5 → node-side ban as the
      pkg/controlplane/** rule below; bare path is required because `**`
      does not match it.
    package: "k8s.io/kubernetes/pkg/controlplane"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
```

After adding these four rules, re-derive the SCOPE OF ANALYSIS comment so
it no longer lists the four bare paths as "covered only by the broad
pkg/** -> cmd/** catch-all."

Verify post-fix that the rule-by-rule table contains four new lines beginning
`Packages matching pattern 'k8s.io/kubernetes/pkg/kubelet'` (no `**`) etc. —
that is the empirical confirmation the bare paths are now matched.
```

```
[F3] SEVERITY: CRITICAL
Category: Vacuous Rule | Glob Semantics
Affected Rule / Constraint: 13 kubelet manager naming rules at lines 1645,
1653, 1661, 1673, 1681, 1689, 1697, 1705, 1713, 1721, 1729, 1737, 1745,
1753, 1761, 1769, 1777 (the cm ContainerManager rule and twelve sub-package
Manager rules). The `Proxier` rule at line 1794 has the same issue.

What is wrong:
The kubelet manager naming rules in round 4 read, e.g.:

```yaml
  - description: >
      Volume manager implementations in pkg/kubelet/volumemanager must be
      suffixed with 'Manager'.
    package: "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "VolumeManager"
      shouldHaveSimpleNameEndingWith: "Manager"
```

Same pattern repeats for `pkg/kubelet/images/**`, `pkg/kubelet/certificate/**`,
`pkg/kubelet/cm/**`, `pkg/kubelet/cm/cpumanager/**`, `pkg/kubelet/cm/memorymanager/**`,
`pkg/kubelet/cm/topologymanager/**`, `pkg/kubelet/cm/devicemanager/**`,
`pkg/kubelet/cm/dra/**`, `pkg/kubelet/configmap/**`, `pkg/kubelet/secret/**`,
`pkg/kubelet/status/**`, `pkg/kubelet/eviction/**`, `pkg/kubelet/token/**`,
`pkg/kubelet/runtimeclass/**`, `pkg/kubelet/clustertrustbundle/**`,
`pkg/kubelet/podcertificate/**`. And the proxy rule at line 1794:

```yaml
    package: "k8s.io/kubernetes/pkg/proxy/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Proxier"
      shouldHaveSimpleNameEndingWith: "Proxier"
```

The header at lines 68-75 of the YAML acknowledges that `<X>/**` does not
match the bare path `<X>`. This insight was applied to the dependency
rules (review #3 [F2], partially closed in round 4 — see [F2] above). It
was NOT applied to the naming rules.

But the canonical interface declaration AND its primary implementing struct
for each of these subsystems are in the BARE package, not in any
sub-package:

  - pkg/kubelet/volumemanager/volume_manager.go declares
    `type VolumeManager interface { ... }` and `type volumeManager struct { ... }`.
    Sub-packages of volumemanager (cache, populator, reconciler, metrics)
    contain auxiliary types but NOT the canonical implementer. The rule
    at line 1645 (`pkg/kubelet/volumemanager/**`) scans only those
    auxiliaries. The struct the rule was written to constrain is invisible
    to it.

  - pkg/kubelet/cm/container_manager.go (or container_manager_linux.go)
    declares `type ContainerManager interface { ... }` and
    `type containerManagerImpl struct { ... }`. Sub-packages of cm
    (cpumanager, memorymanager, topologymanager, devicemanager, dra,
    helpers, fake) contain auxiliary managers. The rule at line 1673
    (`pkg/kubelet/cm/**`) scans only the auxiliaries.

  - pkg/kubelet/cm/cpumanager/cpu_manager.go declares
    `type Manager interface { ... }` and `type manager struct { ... }`.
    The rule at line 1681 (`pkg/kubelet/cm/cpumanager/**`) does not
    match the bare cpumanager package. Same for memorymanager,
    topologymanager, devicemanager, dra.

  - pkg/kubelet/images/image_manager.go declares
    `type ImageManager interface { ... }` and `type imageManager struct { ... }`.
    The rule at line 1653 (`pkg/kubelet/images/**`) does not match it.

  - pkg/kubelet/certificate/{certificate_manager,server,kubelet}.go
    declares the canonical `Manager` interface and its implementing
    structs. The rule at line 1661 (`pkg/kubelet/certificate/**`) does
    not match them; the sub-packages of certificate (csr, bootstrap)
    contain helpers only.

  - Same for configmap, secret, status, eviction, token, runtimeclass,
    clustertrustbundle, podcertificate.

  - pkg/proxy/types.go declares `type Proxier interface { ... }`. The
    rule at line 1794 (`pkg/proxy/**`) scans the iptables, ipvs,
    nftables, winkernel sub-packages where the actual `Proxier` structs
    live (named `iptables.Proxier`, `ipvs.Proxier`, etc.). The
    iptables/ipvs/nftables/winkernel structs ARE matched by the rule
    (their package paths are sub-paths of pkg/proxy/) — so this case is
    fine; the bare path mostly contains interface declarations and data
    types, not implementers. So the proxy rule is a HIGH-severity
    structural concern rather than CRITICAL — verify empirically.

Concrete consequence: every kubelet manager naming rule was added to
encode the kubelet's "Manager-suffix convention applies to canonical
implementers." But the rules cannot fire on the canonical implementers
because those structs are in the bare package, not any sub-tree the rule
matches. A new struct in pkg/kubelet/volumemanager named `vmReconciler`
that implements VolumeManager would — by definition of the rule — be a
naming violation. But the rule never sees it.

Concretely: review #2 [F7] motivated the per-sub-package narrowing
("kubelet manager simple names collide across sub-packages, so each rule
narrows to a single sub-package"). The narrowing was correct in motivation
but the path glob was written one level too deep — `<X>/**` instead of
just `<X>` (or both).

Why it matters:
The kubelet manager-suffix convention is one of the few naming patterns the
YAML explicitly encodes (review #1 [F2] fix). Of the 14+ kubelet manager
rules, ALL of them (except possibly the broad pkg/controller/** Interface
rule, which works correctly because controller sub-packages each declare
their own Interface and the /** glob matches each of those sub-packages
as a path segment) are vacuous on their primary target. The naming
section of the YAML gives the visual impression of comprehensive
manager-suffix enforcement; the actual coverage is auxiliary helper
structs (caches, reconcilers) that mostly don't even implement the
constrained interface to begin with.

The same shape may apply to the registry `Storage` rule at line 1815
(`pkg/registry/**`) — but `**` does match sub-packages with paths like
`pkg/registry/<group>/<resource>/storage`, so the registry case is the
opposite problem ([F4] — too broad rather than too narrow).

How to fix it:
Each kubelet manager naming rule needs a sibling on the bare path. Either
duplicate each rule:

```yaml
  - description: >
      Volume manager implementations in the bare pkg/kubelet/volumemanager
      package (where the canonical VolumeManager interface and its primary
      implementer live). Same constraint as the sub-tree rule below; the
      `**` glob does not match the bare path.
    package: "k8s.io/kubernetes/pkg/kubelet/volumemanager"
    interfaceImplementationNamingRule:
      structsThatImplement: "VolumeManager"
      shouldHaveSimpleNameEndingWith: "Manager"

  - description: >
      Volume manager implementations in pkg/kubelet/volumemanager
      sub-packages. Most sub-packages contain auxiliary types (cache,
      populator, reconciler) that don't implement VolumeManager and
      therefore don't trigger this rule.
    package: "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "VolumeManager"
      shouldHaveSimpleNameEndingWith: "Manager"
```

… and repeat for every one of the 14 kubelet manager rules and the cm
sub-manager rules (cpumanager, memorymanager, topologymanager, devicemanager,
dra). That doubles the naming-rule count to ~30, but each pair guards a
distinct path.

Or, cleaner: change the existing rule's package to the bare path
(omitting `/**`), since for these subsystems the canonical interface and
its implementer are in the bare package. The auxiliary sub-packages
typically don't implement the constrained interface — and on the rare
case they do (a fake or test helper), an additional rule can be added.
This option is less defensive but reads better:

```yaml
  - description: >
      Volume manager implementations in pkg/kubelet/volumemanager (the
      bare package where the canonical VolumeManager interface and its
      `volumeManager` implementer live).
    package: "k8s.io/kubernetes/pkg/kubelet/volumemanager"
    interfaceImplementationNamingRule:
      structsThatImplement: "VolumeManager"
      shouldHaveSimpleNameEndingWith: "Manager"
```

Verify post-fix on a real upstream run: at least one struct should be
flagged or explicitly recognised per rule. A naming rule whose effective
match set is empty is documentation theater.
```

```
[F4] SEVERITY: HIGH
Category: Overly Broad | False Positive Cascade
Affected Rule / Constraint: rule at lines 1809-1818 — pkg/registry/**
`Storage` naming rule.

What is wrong:
The rule reads:

```yaml
    package: "k8s.io/kubernetes/pkg/registry/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Storage"
      shouldHaveSimpleNameEndingWith: "Storage"
```

The author flagged the simple-name brittleness in the description ("verify
the rule's effective scope by inspecting which structs it flags before
relying on it"). But the rule is in production. The simple name `Storage`
in upstream Kubernetes maps to `staging/src/k8s.io/apiserver/pkg/registry/rest/rest.go`'s
`type Storage interface { ... }` — the canonical REST storage interface.
EVERY REST handler in the registry tree implements it.

The Kubernetes naming convention for REST handlers is NOT `<Resource>Storage`.
It is:

  - `REST`           (the most common — `type REST struct { ... }`)
  - `<Resource>REST` (when multiple REST types exist in the same package,
                     e.g., `PodStorage`, `PodStatusREST`, `PodEphemeralContainersREST`)
  - `StatusREST`, `BindingREST`, `LogREST`, `ProxyREST`, `EvictionREST`,
    `EphemeralContainersREST`, `ResizeREST` — sub-resource handlers
  - `Storage`        (only the multiplexer struct that aggregates the
                     REST handlers, e.g., `PodStorage`, `ServiceStorage`)

Counting upstream pkg/registry/core/pod/storage/storage.go alone, there are
~7 REST-shaped structs:

  - type REST struct                  → flagged (does NOT end with "Storage")
  - type StatusREST struct            → flagged
  - type BindingREST struct           → flagged
  - type LogREST struct               → flagged
  - type ProxyREST struct             → flagged
  - type EvictionREST struct          → flagged
  - type EphemeralContainersREST struct → flagged
  - type PodStorage struct            → passes (the aggregator)

Each of these implements rest.Storage. Each is in pkg/registry/core/pod/storage,
matched by `pkg/registry/**`. The rule will report 7 violations from this
one file. Multiply by ~50 resources × ~5 sub-resources average = **hundreds
of false-positive violations** the moment coverage rises above 0%.

Why it matters:
This rule's only effect once enforcement fires is to flag legitimate
upstream Kubernetes code as architecturally incorrect. The author's
acknowledgment-comment at lines 1813-1814 ("arch-go's simple-name interface
resolution may match unintended `Storage` interfaces") admits the issue
without resolving it — same shape as review #3 [F10] called out for other
naming rules.

The team's rational response to "arch-go fails build with 200+ Storage
naming violations" is to disable the rule. That is the same disabling
failure mode that led to round 1's vacuous rules.

How to fix it:
Three options, in order of preference:

(a) Tighten the rule to a specific sub-package where `Storage` is the
canonical implementer-suffix (i.e., the multiplexer-aggregator pattern).
Looking at upstream, that's typically the `<resource>/storage` package —
but the aggregator uses suffix `Storage` while the individual REST
handlers use `REST`. The cleanest fix is to match BOTH suffixes:

This is not directly expressible in arch-go's `shouldHaveSimpleNameEndingWith`
(which is a single string). The next-best is to drop this rule and replace
it with a structural one — but arch-go's `contentsRules` were already
deleted in round 1 because they don't fit. So the next-best is to delete
the rule and document the convention as a code-review checklist item:

```yaml
  # Removed: previously a `Storage` simple-name interfaceImplementationNamingRule
  # over pkg/registry/**. Removed because the upstream convention uses suffix
  # `REST` for individual handlers and `Storage` only for the aggregator
  # struct, which arch-go's single-suffix matcher cannot express. Track
  # naming via PR review.
```

(b) Narrow the rule to the aggregator-only locations using a glob that
matches each `<resource>/storage` directory rather than every registry
package. Even this is incomplete because there are individual REST
handlers in `<resource>/storage` too:

```yaml
  # Tighter — but still too broad; do not use without empirical verification:
    package: "k8s.io/kubernetes/pkg/registry/**/storage"
```

(c) Empirical-first approach: delete the rule, then run arch-go without
it, and re-introduce a rule only if a real naming-discipline failure is
observed.

Either way, the current rule should not be in CI. Confirm by running
`make arch-check` against an upstream checkout: if the rule fires with
hundreds of violations on legitimate REST handlers, [F4] is confirmed.

```yaml
# Concrete fix: delete the rule.
# (Remove the entry at lines 1809-1818.)
```
```

```
[F5] SEVERITY: HIGH
Category: Test-helper False Positive (analogue of review #3 [F6])
Affected Rule / Constraint: rule at lines 206-220 — `pkg/api/**`. Package
list line 53 (`k8s.io/kubernetes/pkg/api/testing`).

What is wrong:
The round-3 fix for review #2 [F3] (test-helper bleed) replaced the broad
globs `pkg/scheduler/**`, `pkg/controller/**`, `pkg/registry/**` with
per-sub-package enumerations to exclude `pkg/scheduler/testing`,
`pkg/controller/testutil`, `pkg/registry/registrytest`. The round-4 fix for
review #3 [F6] applied the same treatment to `pkg/volume/**` (excluding
`pkg/volume/testing`).

The same exclusion was NOT applied to `pkg/api/**`. The package list at
line 53 shows `k8s.io/kubernetes/pkg/api/testing` exists — a cross-layer
test helper that legitimately imports things the rule denies (typically
clientset, informer factories, scheme registration, and helpers from
controller/registry sub-packages used to build fake reconciler harnesses).

The rule reads:

```yaml
    package: "k8s.io/kubernetes/pkg/api/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/apis/**"
        - "k8s.io/kubernetes/pkg/registry/**"
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

`pkg/api/testing` is matched by the `pkg/api/**` glob and will trigger a
false positive on its `pkg/registry/...` and `pkg/controller/...` imports
once enforcement fires.

The same shape applies to the SCOPE OF ANALYSIS comment at lines 61-66.
That comment now correctly lists `pkg/scheduler/testing`,
`pkg/controller/testutil`, `pkg/registry/registrytest`, and (round-4)
`pkg/volume/testing` as intentionally excluded by positive sub-tree
enumeration. It does NOT list `pkg/api/testing`. The exclusion was
forgotten.

Why it matters:
Once [F1] is resolved and rules start firing, `pkg/api/testing` will
produce immediate false positives on its cross-layer imports. The team's
likely response is to disable the rule rather than rewrite it — same
failure mode as review #3 [F6].

How to fix it:
Apply the same per-sub-package enumeration used for controller/scheduler/
registry/volume. The pkg/api sub-packages from the package list (lines
43-53) excluding `testing` are: job, legacyscheme, node, persistentvolume,
persistentvolumeclaim, pod, resourceclaimspec, service, servicecidr,
storage. Replace the single broad `pkg/api/**` rule with eleven
per-sub-package rules:

```yaml
  - description: >
      pkg/api/job sub-tree (pod template helpers for batch.Job).
    package: "k8s.io/kubernetes/pkg/api/job/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/apis/**"
        - "k8s.io/kubernetes/pkg/registry/**"
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: pkg/api/legacyscheme.
    package: "k8s.io/kubernetes/pkg/api/legacyscheme/**"
    shouldNotDependsOn: { internal: [ ...same... ] }

  # ... 8 more entries: node, persistentvolume, persistentvolumeclaim, pod,
  # resourceclaimspec, service, servicecidr, storage ...

  # Also bare-path entries for sub-packages that are themselves single Go
  # packages (most of these are leaf packages with no sub-tree, so the
  # bare path matters):
  - description: pkg/api/job (bare package).
    package: "k8s.io/kubernetes/pkg/api/job"
    shouldNotDependsOn: { internal: [ ...same... ] }

  # ... repeat for each ...
```

Then update the SCOPE OF ANALYSIS comment at lines 61-66 to acknowledge
`pkg/api/testing` as excluded:

```yaml
#     - pkg/api/testing            (excluded; cross-layer test helpers —
#                                    same treatment as the controller /
#                                    scheduler / registry / volume test
#                                    helpers above)
```

Alternatively, if pkg/api leaf packages are unlikely to ever import
component layers and the team prefers to keep the broad rule, accept that
`pkg/api/testing` will trigger false positives and document them as
expected. The current state — broad rule, no exclusion, no documented
expected-false-positives — is the worst combination.
```

```
[F6] SEVERITY: HIGH
Category: Vacuous Rule | Predictive Coverage
Affected Rule / Constraint: 8 registry sub-tree rules at lines 280-379
(core, apps, batch, networking, storage, policy, certificates,
admissionregistration); 3 scheduler sub-tree rules at lines 837-871
(apis, backend, internal).

What is wrong:
Round 4 expanded the registry per-sub-package fan-out from 2 sub-trees
(rbac, resource — the only ones in the package list) to 10 sub-trees.
The added 8 are based on upstream knowledge, not the package list:

  pkg/registry/core/**                  (line 280)
  pkg/registry/apps/**                  (line 293)
  pkg/registry/batch/**                 (line 306)
  pkg/registry/networking/**            (line 319)
  pkg/registry/storage/**               (line 331)
  pkg/registry/policy/**                (line 343)
  pkg/registry/certificates/**          (line 356)
  pkg/registry/admissionregistration/** (line 368)

None of these appear in the package list (lines 203-206 list only
`pkg/registry`, `pkg/registry/rbac`, `pkg/registry/registrytest`, and
`pkg/registry/resource`). Same for scheduler: round 4 added apis (line
838), backend (line 850), internal (line 862) — none appear in the
package list (lines 208-213 list only framework, metrics, profile,
testing, util).

The header comment at lines 232-234 acknowledges this:

    Sub-trees not explicitly listed here remain structurally unconstrained —
    re-derive this list from `go list ./pkg/registry/...` against an
    upstream checkout when adding new API groups.

But the comment is reactive, not preventive. The current package list is
the truthful input; if it's an incomplete summary of what's in upstream
master, then the rules either over-fit (vacuous on packages that don't
exist) or under-fit (miss new packages added since the snapshot).

The test report (`COVERAGE RATE 0%`) provides no evidence one way or the
other — every rule passes vacuously because no scanned package matched.

In actual upstream Kubernetes (e.g., `kubernetes/kubernetes@master` as of
2025), these eight registry sub-trees and three scheduler sub-trees DO
exist:

  - pkg/registry/core, apps, batch, networking, storage, policy,
    certificates, admissionregistration ✓
  - pkg/scheduler/apis, backend, internal ✓ (apis — autoscaling/v1
    config types; backend — queue/cache; internal — the cache implementer)

So the rules are correctly predictive — IF the test were run against
actual upstream. They are vacuous in the run that produced this report
because that run scanned a different module / a stub / an older snapshot.

Why it matters:
The package list given to this review (lines 203-206 / 208-213) does
NOT contain these sub-trees. The reviewer cannot verify the rules are
correctly aimed without access to a real `go list ./pkg/registry/...`
output from a real upstream checkout. The rules are paper-correct but
empirically unverified, and this round's test report cannot
disambiguate "rule scopes a real package and finds zero violations" from
"rule scopes a non-existent package and trivially passes."

If the package list reflects a different kubernetes branch where these
sub-trees did NOT exist, then 11 of the 84 dependency rules are
vacuous. That's 13% of the dependency-rule count.

How to fix it:
Two-part fix:

(a) Re-derive the per-sub-package rule list MECHANICALLY from a real
upstream checkout, not from the truncated `5_kubernetes_kubernetes.txt`
package summary:

```bash
# In a clone of github.com/kubernetes/kubernetes at the version targeted
# by these rules:
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

Use the resulting lists to drive the YAML's per-sub-package fan-out. The
header comment at line 232-234 should mention this explicitly:

```yaml
# Re-derive these sub-tree lists from `go list ./pkg/registry/...`,
# `go list ./pkg/scheduler/...`, etc. against the targeted upstream
# checkout. The lists below were derived against ${commit-sha-of-target}
# on ${date}.
```

(b) Alternatively (cleaner — recommended): revert to the broad globs
`pkg/registry/**`, `pkg/scheduler/**`, `pkg/controller/**`, `pkg/volume/**`
and accept that the four test helpers (`pkg/registry/registrytest`,
`pkg/scheduler/testing`, `pkg/controller/testutil`, `pkg/volume/testing`)
will produce expected false positives. Document those four expected
violations in the YAML and either fix the test helpers' imports
(preferred — move them into `internal/testing` sub-trees) or carve narrow
exceptions per-helper:

```yaml
  - description: >
      Registry sub-tree-wide non-coupling lattice. Test helper sub-tree
      pkg/registry/registrytest is intentionally exempt — see SCOPE OF
      ANALYSIS header. We accept its (small, known) cross-layer imports
      as expected false positives until the helper is moved to
      pkg/registry/internal/registrytest.
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

The maintenance burden of keeping a 32-entry controller fan-out / 10-entry
registry fan-out / 17-entry volume fan-out manually synchronised against
upstream is real. The benefit (excluding 4 test-helper directories) is
small. Reconsider.
```

```
[F7] SEVERITY: MEDIUM
Category: Overly Broad | False Positive
Affected Rule / Constraint: rule at lines 1582-1597 — `cmd/kubemark/**`.

What is wrong:
The new round-4 cmd/kubemark rule (closing review #3 [F4]) reads:

```yaml
    package: "k8s.io/kubernetes/cmd/kubemark/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/**"           # <-- broad; will misfire
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

The description acknowledges that cmd/kubemark legitimately imports
`pkg/kubemark/**` and parts of `pkg/kubelet/**` (correctly NOT in the
deny list). But the package list line 196 shows
`k8s.io/kubernetes/pkg/proxy/kubemark` — a kube-proxy implementation
specifically built to support kubemark's hollow-node simulation. In
upstream, `cmd/kubemark/app/hollow_node.go` instantiates a
`HollowProxy` from `pkg/proxy/kubemark`. The deny on `pkg/proxy/**`
will fire as a false positive on that legitimate import.

Why it matters:
Once enforcement fires (post [F1]), the cmd/kubemark rule produces an
immediate false positive blocking the build. The team's likely response
is to either:
  (a) Add an exception in arch-go.yml that enumerates pkg/proxy/kubemark
      as allowed.
  (b) Disable the rule.

The author's intent — block cmd/kubemark from importing kube-proxy
PRODUCTION backends (iptables, ipvs, nftables, winkernel) while allowing
the kubemark-specific shim — is reasonable. arch-go's `internal:` deny
list takes path-prefix patterns, but you cannot express "deny pkg/proxy/**
EXCEPT pkg/proxy/kubemark/**" directly. The workaround is per-backend
denies:

How to fix it:
Replace the broad `pkg/proxy/**` deny with explicit per-backend denies
that exclude `pkg/proxy/kubemark`:

```yaml
  - description: >
      The kubemark hollow-node simulator binary tree (cmd/kubemark/**)
      legitimately imports pkg/kubemark/** and parts of pkg/kubelet/**
      to fake a kubelet, AND pkg/proxy/kubemark to fake a kube-proxy —
      that's the simulator's whole purpose. It must NOT import sibling
      production-binary packages (controller, scheduler, kubeapiserver,
      controlplane) nor production kube-proxy backends (iptables, ipvs,
      nftables, winkernel). Review #4 [F7]: the original `pkg/proxy/**`
      ban over-blocked the legitimate `pkg/proxy/kubemark` import.
    package: "k8s.io/kubernetes/cmd/kubemark/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"     # production backend
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"         # production backend
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"     # production backend
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"    # production backend
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"
```

This still allows `pkg/proxy/kubemark`, `pkg/proxy/util`, `pkg/proxy/runner`,
`pkg/proxy/healthcheck`, etc. — which are also legitimate. If any of those
should also be denied to cmd/kubemark, add them per-backend; do not use
the broad glob.

Verify against upstream `cmd/kubemark/app/hollow_node.go` what
`pkg/proxy/<sub>` paths it imports today, then decide whether to deny
each one or allow it.
```

```
[F8] SEVERITY: MEDIUM
Category: Vacuous Rule (carryover from review #3 [F5])
Affected Rule / Constraint: `functionsRules` (lines 1842-1875) —
pkg/util/** maxReturnValues=5/maxLines=250 and pkg/apis/**
maxReturnValues=8/maxLines=350.

What is wrong:
Carryover from review #3 [F5]. Both rules `[PASS]` in the report. With
COVERAGE RATE 0%, this means either (a) the calibration is correct AND
the rules are actually scanning real upstream code, or (b) they scanned
zero functions. Cannot disambiguate.

The author updated the description text in round 4 to predict the
specific upstream functions expected to violate post-coverage:

  - pkg/apis/core/validation.{ValidatePodSpec, ValidatePodTemplateSpec,
    ValidatePersistentVolumeSpec}
  - pkg/apis/networking/validation.{validateIngressBackend,
    ValidateNetworkPolicySpec}
  - pkg/util/iptables.(*runner).restoreInternal

These are correct predictions. They cannot be confirmed until [F1] is
resolved.

Why it matters:
Same as review #3 [F5]. Function-complexity rules are the most likely
class to catch quality regressions when correctly wired, and the most
likely to produce noise when not. A rule that passes on 0% coverage is
identical in CI signal to no rule at all.

How to fix it:
Resolve [F1] (operations fix). Re-run against actual upstream. Confirm
the predicted functions surface as violations. If they pass without
violation, the limits are too generous and should be tightened until
they bite a known offender.

```yaml
functionsRules:
  - description: >
      Shared utility functions in pkg/util/** ... [updated calibration after
      empirical run]: cap raised from 250 → NN to grandfather <list of
      observed violators>.
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

The author's note at lines 1832-1841 acknowledges this — "If the rules
pass after coverage rises above 0%, the caps are too generous and should
be tightened until they bite a known offender." Good. Just needs the
empirical step.
```

```
[F9] SEVERITY: LOW
Category: Description Inaccuracy
Affected Rule / Constraint: top-level `description:` block (lines 102-134),
header Layer-4/5 prose (lines 19-32).

What is wrong:
The top-level description (line 109-111) says:

    This YAML encodes that separate-binary invariant as a Layer-4
    non-coupling lattice across controller / scheduler / kubelet /
    proxy / kubeapiserver

The header at lines 25-27 says:

    They form a fully symmetric non-coupling lattice: no Layer-4 sibling
    may import any other Layer-4 sibling, and none may import Layer-5
    controlplane.

After [F2] (bare paths missing for kubelet/proxy/kubeapiserver/controlplane),
this claim is false:

  - pkg/kubelet (bare) is unconstrained — could import pkg/scheduler/**
    silently.
  - pkg/proxy (bare) is unconstrained.
  - pkg/kubeapiserver (bare) is unconstrained.
  - pkg/controlplane (bare) is unconstrained — could import pkg/kubelet/**
    silently. The header at lines 14-17 specifically warns "Must not depend
    on node-side code (kubelet, proxy)" for controlplane — the bare path
    exception silently breaks that warning.

The description (lines 102-134) was updated in round 4 to mention the
bare-path coverage convention and to credit the bare-path additions to
"review #3 [F2], [F11]". Five lines of header (68-75) now explain that
arch-go's `**` glob doesn't match the bare prefix. But the description
text still claims a "fully symmetric" lattice without disclaimer.

Why it matters:
A maintainer reading the prose forms the wrong mental model. The fix for
[F2] above will close most of this gap; what remains is a documentation
honesty issue.

How to fix it:
After [F2] is fixed (bare-path rules added for kubelet/proxy/kubeapiserver/
controlplane), the prose at lines 102-134 will become accurate without
further edits. If [F2] is not fixed, the prose should say so:

```yaml
description: >
  Enforces the Kubernetes component architecture. [...] This YAML encodes
  the separate-binary invariant as a Layer-4 non-coupling lattice across
  controller/scheduler/kubelet/proxy/kubeapiserver. The lattice is enforced
  for the **non-bare** paths of each Layer-4 subsystem; the bare parent
  packages of `pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`, and
  `pkg/controlplane` are NOT yet covered (review #4 [F2]); the
  `pkg/scheduler`, `pkg/controller`, and `pkg/registry` bare paths
  ARE covered (review #3 [F2]).
```

Once the four bare-path rules are added, drop the disclaimer.
```

```
[F10] SEVERITY: LOW
Category: Description Inaccuracy | SCOPE Inventory Drift
Affected Rule / Constraint: SCOPE OF ANALYSIS header comment (lines 46-99).

What is wrong:
The header comment was rewritten in round 4 to enumerate the test helpers
intentionally excluded by positive enumeration:

    pkg/scheduler/testing       (excluded; cross-layer integration helpers)
    pkg/controller/testutil     (excluded; cross-layer integration helpers)
    pkg/registry/registrytest   (excluded; cross-layer integration helpers)
    pkg/volume/testing          (excluded by the per-sub-package fan-out;
                                  same treatment as the controller /
                                  scheduler / registry test helpers)

It also enumerates the build-tooling cmd binaries and the leaf utility
packages with no specific rule. Most of this is correct.

But the SCOPE block does NOT mention three categories of paths the
round-4 YAML still leaves uncovered:

  (1) pkg/api/testing — see [F5]. The pkg/api/** rule does match it, so
      it's the opposite of "excluded": it's an unintended INclusion that
      will trigger false positives.

  (2) The bare paths pkg/kubelet, pkg/proxy, pkg/kubeapiserver,
      pkg/controlplane — see [F2]. These are unintentionally outside the
      `<X>/**` rules' scope.

  (3) The bare paths of every kubelet manager directory
      (pkg/kubelet/volumemanager, /images, /certificate, /cm, /configmap,
      /secret, /status, /eviction, /token, /runtimeclass, /clustertrustbundle,
      /podcertificate) — see [F3]. Those bare paths are where the
      canonical interfaces and implementing structs live, but the naming
      rules use `<X>/**` and miss them.

The SCOPE comment also contains a minor inaccuracy at lines 81-82:

    - vendor/**, third_party/**         (vendored / forked dependencies)
    - test/**                            (integration / e2e suites)

The `test/**` entry implies test/** is unconstrained because it falls
outside any rule glob. True — but the package list (lines 263-411)
shows test/integration/scheduler, test/integration/controller, etc.,
which DO contain meaningful Go code that legitimately reaches across
layers. Documenting `test/**` as "out of scope" is fine; documenting it
without acknowledging that arch-go scans all .go files including
*_test.go in the rule-matched packages is incomplete.

Why it matters:
Documentation drift. A maintainer reading the SCOPE block forms an
incorrect mental model of which paths are constrained. The bare-path
omission ([F2], [F3]) is the most material — they will not realize that,
e.g., `pkg/kubelet/kubelet.go` or `pkg/proxy/types.go` can adopt
cross-component imports silently.

How to fix it:
After [F2] and [F3] are fixed, expand the SCOPE comment to be exhaustive
and accurate:

```yaml
# SCOPE OF ANALYSIS
#
#   ...
#
#   First-party paths covered by NO `package:` glob below (i.e., violations
#   in these paths are invisible to arch-go):
#
#     # vendored / generated / non-source paths (intentional)
#     - vendor/**, third_party/**         (vendored / forked dependencies)
#     - pkg/generated/**                   (generated openapi)
#     - hack/**, build/**, cluster/**      (tooling and packaging)
#     - test/**                            (integration / e2e suites; arch-go
#                                            still scans *_test.go files in
#                                            rule-matched packages by default)
#
#     # build/release tooling cmd binaries (intentional, deliberately permissive)
#     - cmd/clicheck, cmd/dependencycheck, cmd/dependencyverifier,
#       cmd/fieldnamedocscheck, cmd/gendocs, cmd/genfeaturegates,
#       cmd/genkubedocs, cmd/genman, cmd/genswaggertypedocs, cmd/genutils,
#       cmd/genyaml, cmd/gotemplate, cmd/import-boss, cmd/importverifier,
#       cmd/preferredimports, cmd/prune-junit-xml
#
#     # leaf utility packages with no specific rule (covered only by the
#     # broad pkg/** -> cmd/** catch-all)
#     - pkg/auth/nodeidentifier, pkg/capabilities, pkg/certauthorization,
#       pkg/client/conditions, pkg/client/tests, pkg/cluster/ports,
#       pkg/features, pkg/fieldpath, pkg/routes, pkg/windows/service
#
#     # Test helpers excluded by positive enumeration:
#     - pkg/scheduler/testing       (cross-layer integration helpers)
#     - pkg/controller/testutil     (cross-layer integration helpers)
#     - pkg/registry/registrytest   (cross-layer integration helpers)
#     - pkg/volume/testing          (cross-layer integration helpers)
#     # NOTE: pkg/api/testing is NOT excluded — see review #4 [F5].
#       Will produce false positives once enforcement fires.
```

If [F2] is not yet fixed, also list the four uncovered bare paths
(`pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`, `pkg/controlplane`) and
the kubelet manager bare paths from [F3] under a "UNINTENDED" sub-heading
so a future maintainer sees them.
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

The structural progress between rounds 3 and 4 is genuine and visible:

- Bare-path companions added for 12 subsystems (review #3 [F2], [F11] —
  closed for those subsystems).
- `cmd/kube-apiserver/**` deny list now symmetric with kube-controller-manager
  and cloud-controller-manager (review #3 [F3] — closed).
- `cmd/kubemark/**` rule added (review #3 [F4] — closed in shape, but with
  the new false-positive [F7] above).
- `pkg/volume/**` per-sub-package fan-out applied, excluding `pkg/volume/testing`
  (review #3 [F6] — closed).
- cm sub-managers (cpumanager, memorymanager, topologymanager, devicemanager,
  dra) added to the naming rule set (review #3 [F8] — closed in shape).
- Makefile recipe now bash-aware with `set -o pipefail` and
  `STATUS=$${PIPESTATUS[0]}` (review #3 [F1]'s pipe-status latent bug —
  closed; but the empirical 0% coverage is unchanged).
- Registry fan-out expanded from 2 sub-trees to 10 (review #3 [F7] —
  closed in shape, but still predictive against the truncated package list,
  see [F6] above).

**But the test report submitted with this round is byte-for-byte identical
in compliance signal to rounds 2 and 3: 100% pass on 0% coverage.**
Three rounds of fixes have not produced a single demonstrated rule firing
against actual upstream code. The Makefile is now correct; the operations
step (point at a real checkout, capture a non-zero coverage report) has
not been performed.

**Order of fixes** (highest leverage first):

1. **[F1]** — re-run `make arch-check PROJECT_ROOT=/path/to/upstream/k8s.io/kubernetes`
   and capture the report. Confirm `COVERAGE RATE NN% [PASS]` with NN ≥ 30.
   Until this is done, every other finding is a paper claim. **The Makefile
   is correct; what's missing is the run.**

2. **[F2]** — add bare-path rules for `pkg/kubelet`, `pkg/proxy`,
   `pkg/kubeapiserver`, `pkg/controlplane`, mirroring the bare-path pattern
   already used for `pkg/scheduler` / `pkg/controller` / `pkg/registry`.
   Four 8-line YAML entries.

3. **[F3]** — change the kubelet manager naming rules from `<X>/**` to
   `<X>` (or add the `<X>` companion). Same fix shape, applied to ~14
   naming rules (volumemanager, images, certificate, cm, cm/cpumanager,
   cm/memorymanager, cm/topologymanager, cm/devicemanager, cm/dra,
   configmap, secret, status, eviction, token, runtimeclass,
   clustertrustbundle, podcertificate). Without this, the kubelet
   naming convention is unverifiable on its primary targets.

4. **[F4]** — delete the `pkg/registry/**` `Storage` naming rule, OR
   tighten it to specifically exclude `<Resource>REST`-named handlers.
   The rule as written will produce hundreds of false positives once
   coverage rises above 0%.

5. **[F5]** — apply the per-sub-package fan-out treatment to `pkg/api/**`
   to exclude `pkg/api/testing`, OR document the test helper as an
   expected false positive.

6. **[F6]** — re-derive the predictive sub-tree lists from a real
   `go list ./pkg/registry/...` and `go list ./pkg/scheduler/...` against
   the targeted upstream commit. Or revert to the broad globs and accept
   four documented test-helper false positives.

7. **[F7]** — replace the broad `pkg/proxy/**` deny in `cmd/kubemark/**`
   with per-backend denies that allow `pkg/proxy/kubemark`.

8. **[F8]** — empirically verify the function-complexity caps fire (post-[F1]).

9. **[F9]–[F10]** — clean up description prose and SCOPE comment to match
   the actual rule coverage after [F2] and [F3] are fixed.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:
- `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The `pkg/registry/**` `Storage` naming rule has been deleted or tightened
  ([F4]); empirical run does NOT show hundreds of REST-handler violations.
- The kubelet manager naming rules now match at least one struct each
  ([F3]); the rule-by-rule report contains lines beginning
  `Packages matching pattern 'k8s.io/kubernetes/pkg/kubelet/volumemanager'`
  (no `**`).
- The four new bare-path rules from [F2] are reflected in the report
  (lines beginning `Packages matching pattern 'k8s.io/kubernetes/pkg/kubelet'`,
  `'.../pkg/proxy'`, `'.../pkg/kubeapiserver'`, `'.../pkg/controlplane'`).
- The function rules surface ≥ 1 violation in `pkg/apis/core/validation`.

Until those four conditions are met, this report should not be a CI gate;
it is a cosmetic banner.

---

**End of Review #4.**
