# Adversarial Review #7 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 7

---

## Executive Summary

- **Total documented constraints identified: 25.** Ten high-level invariants come from the Kubernetes "Components" reference page (`https://kubernetes.io/docs/concepts/overview/components/`): control-plane vs. node split; kube-apiserver as the only API surface; etcd persistence; kube-scheduler / kube-controller-manager / cloud-controller-manager / kubelet / kube-proxy as separate binaries; Container runtime as the CRI boundary; addons as separate processes; kubectl/kubeadm as clients. Fifteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`) one-way; Layer-2 peer ban (`pkg/api/**` ↔ `pkg/apis/**`); Layer-3 (registry) → Layer-4 ban; the Layer-4 non-coupling lattice (controller / scheduler / kubelet / proxy / kubeapiserver); Layer-5 controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider / security / securitycontext / serviceaccount / probe / kubectl / kubemark / printers / plugin leaf-isolation; intra-layer fences (kubelet/container, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`); util / apis function-complexity caps.
- **Total rules generated: 100** — 79 `dependenciesRules` + 19 `namingRules` + 2 `functionsRules` (matches the test-report totals exactly). Round 7 is one rule smaller than round 6 because the `cmd/kubectl/**` rule was retained but the round-6 [F2] sweep collapsed nothing else; the test report shows 100 rules total.
- **Coverage rate (constraint → rule mapping): ~22/25 on paper.** Round 7 closes ALL eight findings from round 6: [F1] is operations-only and is unchanged; [F2] mechanical sweep was applied — every `<X>/**` deny entry across the YAML now has a paired `<X>` bare-path entry where `<X>` is a real Go package; [F3] subsumed by [F2] — the four missing util-deny bare paths (kubelet, proxy, kubeapiserver, controlplane) are now present (lines 244-253); [F4] subsumed by [F2] — the intra-layer fences `pkg/kubelet/container` and `pkg/proxy/util` got bare-path companions on the OBJECT side (lines 1502-1509, 1546-1553); [F5] subsumed by [F2] — cmd/kubemark per-backend deny list gained the four bare-path entries (lines 1953-1960); [F6] resolved — the vacuous `pkg/kubectl/**` rule was deleted (line 1352-1356 carries the deletion note); [F7] still vacuous due to [F1]; [F8] resolved — top-level `description:` and SCOPE OF ANALYSIS prose accurately advertise the new bare + `/**` companion convention on BOTH SUBJECT and OBJECT sides.
- **Effective coverage rate from arch-go itself: still 0%.** The test-report footer reads `COVERAGE RATE 0% [FAIL]` — byte-for-byte identical to rounds 2, 3, 4, 5, and 6. This is the **sixth consecutive round** with the same empirical state. 100 of 100 rules `[PASS]`-ing against zero matched packages is the same vacuous-pass symptom that has gated nothing in six submissions. The round-6 [F2] mechanical sweep is structurally correct but cannot be empirically validated without a non-zero coverage run.
- **Critical Gaps** (constraints with zero or vacuous enforcement in the current report):
  - **`COVERAGE RATE 0% [FAIL]` is unchanged from rounds 2-6** ([F1]). The Makefile is structurally correct (bash + `pipefail` + 30% floor + module identity check). Until this is fixed, every paper finding remains paper.
  - **NEW SYSTEMIC GAP: the pkg/apis/** rule and the 20 pkg/api fan-out rules deny only the seven mid-tier subsystems (registry, controller, kubelet, proxy, scheduler, kubeapiserver, controlplane) but OMIT all leaf-component subsystems (volume, credentialprovider, security, securitycontext, serviceaccount, probe, printers, kubectl, kubemark, plugin)** ([F2]). The util-layer rule correctly denies all leaf-component subsystems (lines 254-271); the Layer-2 rules do not. Per the YAML's own layer model, Layer 2 (pkg/apis, pkg/api) sits BELOW the leaf-component layer in the import DAG — Layer-2 must not import Layer-leaf any more than util must. So a file in `pkg/apis/core/validation` could `import "k8s.io/kubernetes/pkg/volume"` (or `pkg/security`, `pkg/credentialprovider`, etc.) and the rule would not fire. Same for every pkg/api/<sub> sub-package. Affects 21 of the 79 dependency rules.
  - **`pkg/api/testing` (line 53 of the package list) has zero subject coverage** ([F3]). The pkg/api fan-out approach (review #4 [F5]) deliberately enumerated 10 sub-packages and excluded `pkg/api/testing` to permit its legitimate clientset / informer / controller-test imports. But no compensating rule was added. Consequence: `pkg/api/testing` is the ONLY first-party pkg/api/<sub> package whose Layer-2 → upper-layer imports are completely unconstrained. It can import `pkg/scheduler`, `pkg/kubelet`, `pkg/proxy` directly — exactly the imports the Layer-2 peer/inversion ban was written to forbid — and arch-go reports nothing.
  - **Documented "leaf utility packages with no specific rule" are entirely unenforced** ([F4]). The SCOPE OF ANALYSIS comment at lines 132-135 lists `pkg/auth/nodeidentifier`, `pkg/capabilities`, `pkg/certauthorization`, `pkg/client/conditions`, `pkg/client/tests`, `pkg/cluster/ports`, `pkg/features`, `pkg/fieldpath`, `pkg/routes`, `pkg/windows/service` as "covered only by the broad pkg/** -> cmd/** catch-all". But the pkg/** → cmd/** catch-all only forbids importing `cmd/**` — it doesn't forbid importing component layers. So `pkg/auth/nodeidentifier` (used by kube-apiserver authentication) could `import "k8s.io/kubernetes/pkg/scheduler"` or `import "k8s.io/kubernetes/pkg/kubelet"` and arch-go would not flag it. Similarly for `pkg/cluster/ports`, `pkg/routes`, etc. Ten leaf packages are completely unconstrained as subjects.
- **Overall verdict: `FAIL`.** Round 7 (= round 6 in fix history terms — the latest snapshot) is structurally the cleanest YAML of the seven-round series. The [F2] mechanical sweep from round 6 is well-executed — every deny-list `<X>/**` pattern is paired with a bare `<X>` entry where `<X>` is a real package. The vacuous `pkg/kubectl/**` rule was deleted. The description prose is accurate on the SUBJECT/OBJECT bare-path convention. **But three structural gaps remain**: the Layer-2 rules don't ban leaf-component subsystems; `pkg/api/testing` has zero subject coverage; and ten leaf packages (auth, capabilities, cluster/ports, routes, etc.) have no rule at all. Plus the empirical 0% coverage gate from rounds 2-6 has not budged for the sixth consecutive round.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope (carryover from review #6 [F1] / #5 [F1] / #4 [F1] / #3 [F1])
Affected Rule / Constraint: All 100 rules; Makefile target `arch-check`.

What is wrong:
The supplied test report still has `COVERAGE RATE 0% [FAIL]` — same as
rounds 2, 3, 4, 5, and 6. The Makefile is structurally correct from
round 4:

  - Lines 28-29: SHELL := /bin/bash; .SHELLFLAGS := -eu -o pipefail -c
  - Lines 41, 46: ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes;
                  ARCH_GO_MIN_COVERAGE := 30
  - Lines 121-123: set -o pipefail; arch-go check | tee;
                   STATUS=$${PIPESTATUS[0]}
  - Lines 133-139: aborts on coverage < 30%

But the test report submitted as input to this round documents a run
where:
  (a) coverage is 0%, and
  (b) all 100 rules pass.

If `make arch-check` had run against an actual `k8s.io/kubernetes`
checkout, the COVERAGE RATE would be on the order of 60-90% (arch-go
reports the fraction of scanned packages matched by at least one rule,
and the rules in this YAML cover virtually every pkg/** and cmd/**
path in upstream). 0% is the signature of one of:

  (i)   PROJECT_ROOT pointed at a stub directory whose go.mod declares
        `module k8s.io/kubernetes` but contains no Go source files.
  (ii)  The Makefile target was bypassed; `arch-go check` was invoked
        from a directory whose go.mod is not k8s.io/kubernetes.
  (iii) ARCH_GO_MIN_COVERAGE was overridden to 0 at invocation time.
  (iv)  The captured artifact is from a stale pre-coverage-floor run
        whose [PASS] / [FAIL] table was written before the coverage
        floor check fired.

This is the SIXTH consecutive round with the same empirical state.
Each round has changed the rule set (28 → 85 → 99 → 152 → 101 → 100)
without producing a single empirically demonstrated firing rule. None
of the round-2/3/4/5/6/7 fixes — the kubelet manager bare-path move,
the per-API-group registry fan-out, the per-sub-package pkg/api
fan-out, the cmd-side symmetry, the option-(B) fan-out rollback, the
OBJECT-side bare-path mechanical sweep — has been empirically
validated.

Why it matters:
[F2], [F3], [F4] in this report predict concrete unfixed defects in
the round-7 YAML. Until [F1] is resolved, no prediction can be
confirmed and the team cannot tell which round-7 additions are doing
real work versus which are vacuous.

The signal-to-noise of the architectural review is gated on this
single operations step. SIX rounds of review are downstream of an
empirical condition that has not been satisfied once. The cumulative
review effort across rounds 1-7 amounts to a paper analysis of a
configuration file that has never been exercised against the code it
is meant to constrain.

How to fix it:
This is an operations problem, not a YAML problem. The fix is
procedural, unchanged from review #6 [F1] / #5 [F1] / #4 [F1] /
#3 [F1]:

  1. Clone or check out actual upstream `k8s.io/kubernetes` (the
     module, not the docs repository). Verify with
     `cd <checkout> && go list -m`. The output must read exactly
     `k8s.io/kubernetes`.

  2. Run `make arch-check PROJECT_ROOT=<that-checkout>`. Capture the
     full output.

  3. Confirm the report contains `COVERAGE RATE NN% [PASS]` with
     NN ≥ 30.

  4. Confirm the rule-by-rule table now contains [FAIL] lines for at
     least the predicted offenders:

       - `pkg/apis/core/validation.ValidatePodSpec` → maxLines=350
         violation
       - `pkg/util/iptables.(*runner).restoreInternal` →
         maxLines=250 violation
       - At least one cross-layer test-helper false positive from
         `pkg/scheduler/testing` / `pkg/controller/testutil` /
         `pkg/registry/registrytest` / `pkg/volume/testing`
         (option-B tradeoff documented at lines 71-76 of arch-go.yml)

  5. Only after observing those expected violations and either fixing
     them in source or carving narrow exceptions in arch-go.yml
     should the report be treated as a real CI gate.

If you cannot run against full upstream, run against a partial mirror
that contains at minimum:
  - pkg/util/iptables (≥1 file)
  - pkg/apis/core/validation (≥1 file)
  - pkg/controller/{deployment,job,cronjob,statefulset} (canonical
    controllers)
  - pkg/scheduler/framework
  - pkg/registry/rbac (canonical Storage/REST handler)
  - cmd/kubelet, cmd/kube-apiserver, cmd/kubectl

Until the four steps are completed, the YAML changes from rounds 1-7
are unverified.
```

```
[F2] SEVERITY: HIGH
Category: Coverage Gap | Layer-2 deny list incomplete
Affected Rule / Constraint:
  - pkg/apis/** rule (lines 282-299)
  - 20 pkg/api fan-out rules (lines 318-717): pkg/api/job, /legacyscheme,
    /node, /persistentvolume, /persistentvolumeclaim, /pod,
    /resourceclaimspec, /service, /servicecidr, /storage — each with
    bare + /** pair.

What is wrong:
The pkg/apis/** rule (and each of the 20 pkg/api/<sub> sibling rules)
denies the seven mid-tier subsystems:

  - pkg/registry / pkg/registry/**
  - pkg/controller / pkg/controller/**
  - pkg/kubelet / pkg/kubelet/**
  - pkg/proxy / pkg/proxy/**
  - pkg/scheduler / pkg/scheduler/**
  - pkg/kubeapiserver / pkg/kubeapiserver/**
  - pkg/controlplane / pkg/controlplane/**

But OMITS every leaf-component subsystem that the util-layer rule
(lines 235-271) correctly forbids:

  ✗ pkg/volume / pkg/volume/**
  ✗ pkg/credentialprovider / pkg/credentialprovider/**
  ✗ pkg/kubectl
  ✗ pkg/kubemark / pkg/kubemark/**
  ✗ pkg/printers / pkg/printers/**
  ✗ pkg/probe / pkg/probe/**
  ✗ pkg/security / pkg/security/**
  ✗ pkg/securitycontext / pkg/securitycontext/**
  ✗ pkg/serviceaccount / pkg/serviceaccount/**
  ✗ plugin/**

Per the YAML's own layer model (lines 5-44):

  - Layer 1 (pkg/util/**) sits at the bottom and must not import
    higher pkg layers, INCLUDING the leaf-component subsystems
    (volume, credentialprovider, kubectl, printers, probe, security,
    securitycontext, serviceaccount). The util-layer rule enforces
    this with all leaf-component subsystems in its deny list.

  - Layer 2 (pkg/api/** and pkg/apis/**) sits above util and below
    the leaf-component subsystems in the import DAG. By the same
    layering principle, Layer-2 packages must not import the leaf-
    component subsystems either. But the Layer-2 rules' deny lists
    DON'T enforce this — they only deny the seven mid-tier
    subsystems and the Layer-2 peer (pkg/api/** ↔ pkg/apis/**).

The util-rule and the Layer-2 rules are therefore asymmetric on
exactly the constraint the YAML's layer model says they should
share. The asymmetry is silent in the test report (every rule is
listed individually; one cannot eyeball which deny list is
incomplete).

Concrete proof of impact:

  Example 1 — pkg/apis silently imports pkg/volume:
    File: pkg/apis/core/validation/validation.go
    Add:  import "k8s.io/kubernetes/pkg/volume"
    Reality: pkg/volume (bare, line 242 of package list) declares
             VolumePlugin, Mounter, Unmounter — high-level kubelet
             plumbing.
    Rule: pkg/apis/** deny list does NOT contain pkg/volume.
          arch-go reports [PASS].
    Documented constraint violated: Layer 2 → leaf-component import
    direction is the reverse of the documented Layer-2-as-foundation
    invariant.

  Example 2 — pkg/api silently imports pkg/security:
    File: pkg/api/pod/util.go
    Add:  import "k8s.io/kubernetes/pkg/security/apparmor"
    Reality: pkg/security/apparmor (line 215) declares apparmor
             helpers used by kubelet.
    Rule: pkg/api/pod / pkg/api/pod/** deny lists do NOT contain
          pkg/security or pkg/security/**. arch-go reports [PASS].
    Documented constraint violated: same as above.

  Example 3 — pkg/apis silently imports pkg/credentialprovider:
    File: pkg/apis/core/validation/validation.go
    Add:  import "k8s.io/kubernetes/pkg/credentialprovider"
    Reality: pkg/credentialprovider (bare, line 124) declares
             image-pull credential helpers used by kubelet.
    Rule: pkg/apis/** deny list does NOT contain
          pkg/credentialprovider. arch-go reports [PASS].

  Example 4 — pkg/api silently imports pkg/printers:
    File: pkg/api/service/util.go
    Add:  import "k8s.io/kubernetes/pkg/printers"
    Reality: pkg/printers (bare, line 181) is client-shaped output
             formatting.
    Rule: pkg/api/service / pkg/api/service/** deny lists do NOT
          contain pkg/printers. arch-go reports [PASS].

  Example 5 — pkg/api silently imports plugin/**:
    File: pkg/api/legacyscheme/scheme.go
    Add:  import "k8s.io/kubernetes/plugin/pkg/auth"
    Reality: plugin/pkg/auth (line 262) is an in-tree auth plugin
             loaded by kubeapiserver/admission.
    Rule: pkg/api/legacyscheme / pkg/api/legacyscheme/** deny lists
          do NOT contain plugin/**. arch-go reports [PASS].

The defect is structurally identical to round-6 [F3] (which the round-7
patch closed by adding bare-path companions to the util-layer rule's
deny list). But round-6 [F3] addressed only one rule (pkg/util/**);
round 7 missed propagating the same insight to the pkg/apis/** rule
and the 20 pkg/api fan-out rules.

The util-layer rule has the correct shape:

```yaml
package: "k8s.io/kubernetes/pkg/util/**"
shouldNotDependsOn:
  internal:
    - "k8s.io/kubernetes/pkg/api/**"
    - "k8s.io/kubernetes/pkg/apis/**"
    - "k8s.io/kubernetes/pkg/registry"          # mid-tier
    - "k8s.io/kubernetes/pkg/registry/**"
    # ... (other mid-tier with bare + /** pairs)
    - "k8s.io/kubernetes/pkg/volume"            # leaf-component
    - "k8s.io/kubernetes/pkg/volume/**"
    - "k8s.io/kubernetes/pkg/credentialprovider"
    - "k8s.io/kubernetes/pkg/credentialprovider/**"
    - "k8s.io/kubernetes/pkg/kubectl"
    - "k8s.io/kubernetes/pkg/kubectl/**"
    - "k8s.io/kubernetes/pkg/kubemark"
    - "k8s.io/kubernetes/pkg/kubemark/**"
    - "k8s.io/kubernetes/pkg/printers"
    - "k8s.io/kubernetes/pkg/printers/**"
    - "k8s.io/kubernetes/pkg/probe"
    - "k8s.io/kubernetes/pkg/probe/**"
    - "k8s.io/kubernetes/pkg/security"
    - "k8s.io/kubernetes/pkg/security/**"
    - "k8s.io/kubernetes/pkg/securitycontext"
    - "k8s.io/kubernetes/pkg/securitycontext/**"
    - "k8s.io/kubernetes/pkg/serviceaccount"
    - "k8s.io/kubernetes/pkg/serviceaccount/**"
```

This shape needs to be replicated in the pkg/apis/** rule and in
each of the 20 pkg/api fan-out rules.

Why it matters:
This finding is the Layer-2 analogue of the round-6 [F2] systemic
bare-path defect. Every Layer-2 rule (21 of the 79 dependency rules,
26.6% of the dependency-rule corpus) has an incomplete deny list.
The constraint the YAML's layer model asserts — Layer 2 sits below
the leaf-component subsystems in the import DAG — is unenforced. Once
[F1] is resolved and coverage rises above 0%, the most common type of
silent breach predicted by the YAML's own architecture will be
exactly this: Layer-2 validation code reaching for kubelet plumbing
helpers (volume, security, credentialprovider) and arch-go reporting
[PASS].

The util-layer rule had this gap fixed in round 5 ([F6] for probe)
and round 7 ([F3] for kubelet/proxy/kubeapiserver/controlplane bare
paths). The symmetric fix to the Layer-2 rules has not been applied
in any of the seven rounds.

How to fix it:
For the pkg/apis/** rule (lines 282-299), append the leaf-component
deny entries:

```yaml
- description: >
    Internal API type packages (pkg/apis/**) ... [existing description]
  package: "k8s.io/kubernetes/pkg/apis/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/api/**"
      # ... existing 14 mid-tier entries ...
      - "k8s.io/kubernetes/pkg/controlplane"
      - "k8s.io/kubernetes/pkg/controlplane/**"
      # ADDED leaf-component subsystems:
      - "k8s.io/kubernetes/pkg/volume"
      - "k8s.io/kubernetes/pkg/volume/**"
      - "k8s.io/kubernetes/pkg/credentialprovider"
      - "k8s.io/kubernetes/pkg/credentialprovider/**"
      - "k8s.io/kubernetes/pkg/kubectl"
      - "k8s.io/kubernetes/pkg/kubemark"
      - "k8s.io/kubernetes/pkg/kubemark/**"
      - "k8s.io/kubernetes/pkg/printers"
      - "k8s.io/kubernetes/pkg/printers/**"
      - "k8s.io/kubernetes/pkg/probe"
      - "k8s.io/kubernetes/pkg/probe/**"
      - "k8s.io/kubernetes/pkg/security"
      - "k8s.io/kubernetes/pkg/security/**"
      - "k8s.io/kubernetes/pkg/securitycontext"
      - "k8s.io/kubernetes/pkg/securitycontext/**"
      - "k8s.io/kubernetes/pkg/serviceaccount"
      - "k8s.io/kubernetes/pkg/serviceaccount/**"
      - "k8s.io/kubernetes/plugin/**"
```

Apply the SAME mechanical patch to each of the 20 pkg/api fan-out
rules (job, legacyscheme, node, persistentvolume, persistentvolumeclaim,
pod, resourceclaimspec, service, servicecidr, storage — both bare and
/** for each). They all share the same canonical deny shape.

Note that pkg/kubectl appears as bare-only in the leaf-component
list (no /** companion) because the round-6 [F6] sweep deleted the
vacuous pkg/kubectl/** rule; only the bare pkg/kubectl package exists
in the snapshot. plugin/** has no bare pkg/plugin (only sub-paths
exist), so the /** glob alone is sufficient.

Acceptance test (after [F1] is closed):

  Add to pkg/apis/core/validation/validation.go:
    import _ "k8s.io/kubernetes/pkg/volume"
  Run `make arch-check`. Expected: [FAIL] for the pkg/apis/** rule
  citing pkg/volume as the offending import. Pre-fix: [PASS].

  Add to pkg/api/pod/util.go:
    import _ "k8s.io/kubernetes/pkg/security"
  Run `make arch-check`. Expected: [FAIL] for the pkg/api/pod and
  pkg/api/pod/** rules. Pre-fix: [PASS].

If the acceptance tests do not fire post-fix, the deny-list patch
is incomplete — locate the missed leaf-component entries.

Mechanical sweep size: 21 rules × 17 added entries = 357 new lines
in the YAML. Idempotent and trivially scriptable.
```

```
[F3] SEVERITY: HIGH
Category: Coverage Gap | Subject not covered
Affected Rule / Constraint: pkg/api fan-out (lines 318-717). The
package `k8s.io/kubernetes/pkg/api/testing` (line 53 of the package
list) has zero subject coverage.

What is wrong:
Round 4 [F5] introduced the pkg/api per-sub-package fan-out
specifically to exclude `pkg/api/testing` from the Layer-2 →
upper-layer ban. The justification (line 305-310 of arch-go.yml):

    "Review #4 [F5]: the broad `pkg/api/**` rule was replaced with
    per-sub-package enumeration so that the cross-layer test helper
    `pkg/api/testing` (which legitimately reaches into clientset /
    informer factories / controller and registry sub-packages to
    build fake reconciler harnesses) is excluded by positive
    sub-tree enumeration — same treatment as the controller /
    scheduler / registry / volume test helpers."

But the controller / scheduler / registry / volume test helpers in
round 5 [F2] option (B) were RE-ABSORBED into broad globs and
documented as expected-false-positive sources to be carved out
later. The pkg/api fan-out was retained — the only fan-out that
survived round 5 — because "its bare + `/**` per sub-package shape
was already correct and was not flagged in review #5".

The unintended consequence: `pkg/api/testing` is now the ONLY
first-party pkg/api/<sub> package whose Layer-2 → upper-layer
imports are completely unconstrained. It is matched by NO `package:`
glob in any rule. Even the global `pkg/** → cmd/**` catch-all (lines
1467-1474) only forbids cmd-imports, not Layer-4 / Layer-5 imports.

Concrete proof of impact:

  Example 1 — pkg/api/testing silently imports pkg/scheduler:
    File: pkg/api/testing/fixtures.go (hypothetical or real)
    Add:  import "k8s.io/kubernetes/pkg/scheduler"
    Reality: pkg/scheduler is real (line 208).
    Rules covering pkg/api/testing as subject: NONE (the 20 pkg/api
    fan-out rules enumerate job, legacyscheme, node, persistentvolume,
    persistentvolumeclaim, pod, resourceclaimspec, service,
    servicecidr, storage — but NOT testing).
    arch-go reports [PASS] (vacuously — the subject is unmatched).

  Example 2 — pkg/api/testing silently imports pkg/kubelet:
    File: pkg/api/testing/objects.go (hypothetical)
    Add:  import "k8s.io/kubernetes/pkg/kubelet"
    Reality: pkg/kubelet (bare) declares the canonical Kubelet
             struct. NO Layer-2 helper, even a test helper, should
             reach for the Kubelet struct directly.
    Rules covering pkg/api/testing as subject: NONE.
    arch-go reports [PASS].

The intent of the round 4 [F5] fan-out was to PERMIT pkg/api/testing
to cross layers for fake-reconciler construction (which the YAML
documented as legitimate), not to leave it completely unenforced.
But "permit some imports" and "permit ALL imports" are the same in
arch-go: the only way to express "constrain X but allow exception Y"
is to explicitly include X as a subject and explicitly omit Y from
the deny list, OR to enumerate X's siblings as subjects. The latter
is what was done — but pkg/api/testing was supposed to be EXCLUDED,
which means it falls outside the rule entirely.

The "expected false positive sources" approach (review #5 [F2]
option B) for the four other test helpers (pkg/scheduler/testing,
pkg/controller/testutil, pkg/registry/registrytest,
pkg/volume/testing) at least keeps them in scope under broad globs —
once empirically discovered, narrow exceptions can be carved.
pkg/api/testing has no such fallback; it's outside every rule.

Why it matters:
This is a small absolute coverage gap (1 of ~431 packages). But the
gap is in exactly the type of helper that historically does abuse
cross-layer access (test fixtures that build entire fake clusters
in-memory). And the gap is INVISIBLE in the rule-by-rule report:
arch-go's [PASS] table only lists rules; missing subjects produce no
output at all. A maintainer reading the YAML would assume the pkg/api
fan-out covers all pkg/api sub-packages (the descriptions don't say
"all but testing"); a maintainer reading the report sees only [PASS]
lines for the 10 enumerated sub-packages.

How to fix it:
Two options.

(a) Bring pkg/api/testing into scope as an "expected false positive
    source" under a broad pkg/api/** rule (review #5 [F2] option B
    treatment, applied retroactively):

```yaml
# Replace the 20 pkg/api fan-out rules (lines 318-717) with a single
# broad rule paired with documentation of pkg/api/testing as a known
# expected-false-positive source.
- description: >
    pkg/api/** is Layer-2 (foundational API helpers). Must not import
    apis peer, registry, component layers, or controlplane.
    pkg/api/testing is a known expected-false-positive source under
    this broad glob (it legitimately reaches into clientset/informer/
    controller for fake-reconciler construction); track its actual
    cross-layer imports per review #5 [F2] option (B) — either move
    to internal/testing upstream or carve a narrow per-helper
    exception once the empirical run reveals the actual offenders.
  package: "k8s.io/kubernetes/pkg/api/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/apis/**"
      - "k8s.io/kubernetes/pkg/registry"
      - "k8s.io/kubernetes/pkg/registry/**"
      - "k8s.io/kubernetes/pkg/controller"
      - "k8s.io/kubernetes/pkg/controller/**"
      - "k8s.io/kubernetes/pkg/kubelet"
      - "k8s.io/kubernetes/pkg/kubelet/**"
      - "k8s.io/kubernetes/pkg/proxy"
      - "k8s.io/kubernetes/pkg/proxy/**"
      - "k8s.io/kubernetes/pkg/scheduler"
      - "k8s.io/kubernetes/pkg/scheduler/**"
      - "k8s.io/kubernetes/pkg/kubeapiserver"
      - "k8s.io/kubernetes/pkg/kubeapiserver/**"
      - "k8s.io/kubernetes/pkg/controlplane"
      - "k8s.io/kubernetes/pkg/controlplane/**"
      # ... plus leaf-component entries from [F2] sweep ...
```

    Net: -19 rules (20 → 1), +1 known false-positive source. This
    reduces the YAML size meaningfully and brings pkg/api/testing
    into scope.

(b) Add a dedicated subject rule for pkg/api/testing with a narrow
    deny list that permits its known legitimate imports:

```yaml
- description: >
    pkg/api/testing is a cross-layer test helper that legitimately
    builds fake reconciler harnesses from clientset / informer /
    controller helpers. It must not import production node-side
    components (kubelet, proxy) or control-plane assembly.
  package: "k8s.io/kubernetes/pkg/api/testing"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/kubelet"
      - "k8s.io/kubernetes/pkg/kubelet/**"
      - "k8s.io/kubernetes/pkg/proxy"
      - "k8s.io/kubernetes/pkg/proxy/**"
      - "k8s.io/kubernetes/pkg/controlplane"
      - "k8s.io/kubernetes/pkg/controlplane/**"
      - "k8s.io/kubernetes/pkg/kubeapiserver"
      - "k8s.io/kubernetes/pkg/kubeapiserver/**"
```

    Net: +1 rule, no fan-out collapse. Most surgical fix.

Option (a) is structurally cleaner and consistent with the round-5
option (B) treatment of the other four test helpers. Option (b) is
more conservative and explicit about pkg/api/testing's permitted
imports. Recommendation: option (a) — collapse the 20-rule fan-out
to a single broad rule and document pkg/api/testing as a known
expected-false-positive source. The current 20-rule fan-out is a
maintenance burden disproportionate to the benefit ([F5] below).
```

```
[F4] SEVERITY: HIGH
Category: Coverage Gap | Documented leaf packages with no rule
Affected Rule / Constraint: 10+ leaf packages enumerated in SCOPE OF
ANALYSIS at lines 132-135 — pkg/auth/nodeidentifier, pkg/capabilities,
pkg/certauthorization, pkg/client/conditions, pkg/client/tests,
pkg/cluster/ports, pkg/features, pkg/fieldpath, pkg/routes,
pkg/windows/service.

What is wrong:
The SCOPE OF ANALYSIS comment at lines 131-135 explicitly lists ten
leaf utility packages that have no specific rule:

    "leaf utility packages with no specific rule (covered only by
    the broad pkg/** -> cmd/** catch-all):
      - pkg/auth/nodeidentifier, pkg/capabilities, pkg/certauthorization,
        pkg/client/conditions, pkg/client/tests, pkg/cluster/ports,
        pkg/features, pkg/fieldpath, pkg/routes, pkg/windows/service"

The author asserts coverage via the global catch-all. But the
catch-all (lines 1467-1474) is:

```yaml
package: "k8s.io/kubernetes/pkg/**"
shouldNotDependsOn:
  internal:
    - "k8s.io/kubernetes/cmd/**"
```

This rule denies ONLY imports of `cmd/**`. It does NOT deny imports
of `pkg/scheduler`, `pkg/kubelet`, `pkg/controller`, `pkg/proxy`,
`pkg/controlplane`, `pkg/kubeapiserver`, or any leaf-component
subsystem. So all ten leaf packages above are constrained against
importing `cmd/**` (which they would never do anyway — Go's
package-main rule prevents importing main packages) but are
COMPLETELY unconstrained against importing every other architectural
layer.

Concrete proof of impact:

  Example 1 — pkg/auth/nodeidentifier silently imports pkg/scheduler:
    File: pkg/auth/nodeidentifier/identifier.go
    Add:  import "k8s.io/kubernetes/pkg/scheduler"
    Reality: pkg/auth/nodeidentifier is used by kube-apiserver
             authentication to identify node clients. It is a leaf
             helper above pkg/util but below the component layers.
             A scheduler import is architecturally absurd.
    Rules covering pkg/auth/nodeidentifier as subject: ONLY the
    pkg/** → cmd/** catch-all, which forbids only cmd imports.
    arch-go reports [PASS].

  Example 2 — pkg/cluster/ports silently imports pkg/kubelet:
    File: pkg/cluster/ports/ports.go
    Add:  import "k8s.io/kubernetes/pkg/kubelet"
    Reality: pkg/cluster/ports declares port-number constants used
             by control-plane assembly. It is a constants-only leaf
             package.
    Rules covering pkg/cluster/ports as subject: ONLY catch-all.
    arch-go reports [PASS].

  Example 3 — pkg/routes silently imports pkg/scheduler:
    File: pkg/routes/profiling.go
    Add:  import "k8s.io/kubernetes/pkg/scheduler"
    Reality: pkg/routes mounts HTTP debug routes (pprof, OpenAPI)
             on the apiserver. It is consumed by kubeapiserver only.
    Rules covering pkg/routes as subject: ONLY catch-all.
    arch-go reports [PASS].

  Example 4 — pkg/features silently imports pkg/controller:
    File: pkg/features/kube_features.go
    Add:  import "k8s.io/kubernetes/pkg/controller"
    Reality: pkg/features declares Kubernetes feature gates. It is
             a foundational helper that should be imported by every
             component but should import no component itself.
    Rules covering pkg/features as subject: ONLY catch-all.
    arch-go reports [PASS].

  Example 5 — pkg/windows/service silently imports pkg/kubelet:
    File: pkg/windows/service/service.go
    Add:  import "k8s.io/kubernetes/pkg/kubelet"
    Reality: pkg/windows/service is a Windows-platform helper for
             service-mode binaries. It should be a leaf utility,
             not import the kubelet package.
    Rules covering pkg/windows/service as subject: ONLY catch-all.
    arch-go reports [PASS].

The author's "covered only by the catch-all" claim is misleading.
The catch-all covers these packages as subjects of the cmd-import
ban only. Effectively, ten leaf packages have NO Layer-4 / Layer-3
/ Layer-5 / leaf-component isolation enforcement.

Why it matters:
These are exactly the kind of leaf packages where developers might
casually `import "k8s.io/kubernetes/pkg/kubelet"` to grab a constant
or a type that isn't in `vendor/k8s.io/api/`. The architectural
intent is clear (these are leaves; they should not climb the layer
stack); the rule does not enforce it.

Note that some of these packages are arguably MORE sensitive than
the leaf-component subsystems already covered:

  - pkg/auth/nodeidentifier — used by every authentication path in
    kube-apiserver. A scheduler import here could bloat the apiserver
    binary with scheduler-only dependencies.
  - pkg/cluster/ports — port-number constants. Should be constants
    only.
  - pkg/features — feature gates. Should be a self-contained
    flag-registration mechanism.
  - pkg/routes — HTTP route registration. Should depend only on
    apiserver primitives.

Each of these had a specific architectural purpose articulated in
upstream commits; none should pull in component layers.

How to fix it:
Add a leaf-isolation rule covering all ten packages. Three sub-options:

(a) Single rule, list each leaf package as bare + (where applicable)
    /**:

```yaml
- description: >
    Cross-cutting leaf packages (auth/nodeidentifier, capabilities,
    certauthorization, client, cluster/ports, features, fieldpath,
    routes, windows/service) sit above pkg/util in the import DAG
    and below the component / control-plane layers. They are
    foundational helpers consumed by apiserver / kubelet / scheduler
    and must not back-import any component or control-plane package.
  package: "k8s.io/kubernetes/pkg/auth/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/controller"
      - "k8s.io/kubernetes/pkg/controller/**"
      - "k8s.io/kubernetes/pkg/scheduler"
      - "k8s.io/kubernetes/pkg/scheduler/**"
      - "k8s.io/kubernetes/pkg/kubelet"
      - "k8s.io/kubernetes/pkg/kubelet/**"
      - "k8s.io/kubernetes/pkg/proxy"
      - "k8s.io/kubernetes/pkg/proxy/**"
      - "k8s.io/kubernetes/pkg/kubeapiserver"
      - "k8s.io/kubernetes/pkg/kubeapiserver/**"
      - "k8s.io/kubernetes/pkg/controlplane"
      - "k8s.io/kubernetes/pkg/controlplane/**"

# Repeat the same shape for each leaf package — pkg/capabilities (no
# /** companion needed; it's a single leaf package), pkg/cluster/ports,
# pkg/features, pkg/fieldpath, pkg/routes, pkg/windows/service,
# pkg/certauthorization, pkg/client/** (covers conditions and tests).
```

(b) Use the same broad-glob approach used for kubectl etc. — one rule
    per package family.

(c) Acknowledge the gap explicitly in the SCOPE OF ANALYSIS comment
    if the author intends not to cover these packages, and document
    the rationale (e.g., "these are pre-existing well-behaved
    leaves that have never imported component layers in upstream
    history; not worth a rule").

Recommendation: option (a) for the security-sensitive packages
(pkg/auth/**, pkg/cluster/ports, pkg/routes, pkg/features) and
option (c) for the trivial constants packages (pkg/capabilities,
pkg/certauthorization, pkg/fieldpath, pkg/windows/service,
pkg/client/**).

Acceptance test (after [F1] is closed):

  Add to pkg/auth/nodeidentifier/identifier.go:
    import _ "k8s.io/kubernetes/pkg/scheduler"
  Run `make arch-check`. Expected: [FAIL] for the pkg/auth/** rule
  citing pkg/scheduler. Pre-fix: [PASS].
```

```
[F5] SEVERITY: MEDIUM
Category: Rule Redundancy / Maintenance Burden
Affected Rule / Constraint: pkg/api fan-out (lines 318-717) — 20
nearly-identical rules.

What is wrong:
The pkg/api fan-out consists of 20 rules with the SAME 14-entry deny
list (the 14 entries are pkg/apis/**, pkg/registry, pkg/registry/**,
pkg/controller, pkg/controller/**, pkg/kubelet, pkg/kubelet/**,
pkg/proxy, pkg/proxy/**, pkg/scheduler, pkg/scheduler/**,
pkg/kubeapiserver, pkg/kubeapiserver/**, pkg/controlplane,
pkg/controlplane/**). Each pkg/api sub-package gets two rules: bare
and /**. With 10 sub-packages enumerated (job, legacyscheme, node,
persistentvolume, persistentvolumeclaim, pod, resourceclaimspec,
service, servicecidr, storage), that's 20 rules.

Each rule has identical deny content; the only difference is the
`package:` field.

The justification for the fan-out (review #4 [F5]) was to exclude
the test helper pkg/api/testing. But:

  1. Round 5 [F2] option (B) explicitly chose to keep test helpers
     in scope under broad globs and document them as expected-false-
     positive sources. The pkg/api fan-out was retained "because its
     bare + `/**` per sub-package shape was already correct" — but
     that justification doesn't address why 20 rules are needed when
     1 broad rule plus 1 documented exception would suffice.

  2. The round-7 [F3] finding (above) shows that the fan-out
     approach has the side effect of leaving pkg/api/testing
     completely uncovered as a subject.

  3. Adding the [F2] leaf-component sweep to each fan-out rule will
     turn each 14-entry deny list into a 31-entry deny list,
     bloating the YAML by 21 × (31 - 14) = 357 lines and 21 × 17 =
     357 entries that are pure copy-paste.

  4. Each future architectural change (e.g., adding a new leaf
     subsystem to the deny list) requires editing 21 places.

Why it matters:
Lower severity than [F2-F4] — redundancy is a maintenance cost, not
a coverage gap. But it exacerbates [F2] (every Layer-2 leaf-component
ban must be added in 21 places) and [F3] (the fan-out is the reason
pkg/api/testing has zero coverage). Collapsing the fan-out to a
single broad rule resolves all three findings simultaneously.

How to fix it:
Apply review #5 [F2] option (B) treatment to pkg/api too — collapse
the 20 fan-out rules into a single broad rule plus a documented
expected-false-positive note for pkg/api/testing:

```yaml
- description: >
    pkg/api/** is Layer-2 (foundational API helpers — pod template
    helpers for batch.Job, legacyscheme registration, node taint
    helpers, persistentvolume helpers, etc.). Must not import the
    Layer-2 peer (pkg/apis/**), registry, component layers
    (controller, kubelet, proxy, scheduler, kubeapiserver), or
    Layer-5 controlplane assembly. pkg/api/testing is an expected
    false-positive source under this broad glob (it legitimately
    builds fake reconciler harnesses from clientset / informer /
    controller helpers); track its cross-layer imports per review
    #5 [F2] option (B) and either move it to internal/testing
    upstream or carve a narrow per-helper exception once the
    empirical run reveals the actual offenders.
  package: "k8s.io/kubernetes/pkg/api/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/apis/**"
      - "k8s.io/kubernetes/pkg/registry"
      - "k8s.io/kubernetes/pkg/registry/**"
      - "k8s.io/kubernetes/pkg/controller"
      - "k8s.io/kubernetes/pkg/controller/**"
      - "k8s.io/kubernetes/pkg/kubelet"
      - "k8s.io/kubernetes/pkg/kubelet/**"
      - "k8s.io/kubernetes/pkg/proxy"
      - "k8s.io/kubernetes/pkg/proxy/**"
      - "k8s.io/kubernetes/pkg/scheduler"
      - "k8s.io/kubernetes/pkg/scheduler/**"
      - "k8s.io/kubernetes/pkg/kubeapiserver"
      - "k8s.io/kubernetes/pkg/kubeapiserver/**"
      - "k8s.io/kubernetes/pkg/controlplane"
      - "k8s.io/kubernetes/pkg/controlplane/**"
      # leaf-component subsystems (per [F2] sweep):
      - "k8s.io/kubernetes/pkg/volume"
      - "k8s.io/kubernetes/pkg/volume/**"
      - "k8s.io/kubernetes/pkg/credentialprovider"
      - "k8s.io/kubernetes/pkg/credentialprovider/**"
      - "k8s.io/kubernetes/pkg/kubectl"
      - "k8s.io/kubernetes/pkg/kubemark"
      - "k8s.io/kubernetes/pkg/kubemark/**"
      - "k8s.io/kubernetes/pkg/printers"
      - "k8s.io/kubernetes/pkg/printers/**"
      - "k8s.io/kubernetes/pkg/probe"
      - "k8s.io/kubernetes/pkg/probe/**"
      - "k8s.io/kubernetes/pkg/security"
      - "k8s.io/kubernetes/pkg/security/**"
      - "k8s.io/kubernetes/pkg/securitycontext"
      - "k8s.io/kubernetes/pkg/securitycontext/**"
      - "k8s.io/kubernetes/pkg/serviceaccount"
      - "k8s.io/kubernetes/pkg/serviceaccount/**"
      - "k8s.io/kubernetes/plugin/**"
```

Net change: -19 rules (20 fan-out rules deleted, 1 broad rule
added). Plus pkg/api/testing now in scope as a documented expected-
false-positive source. Plus the [F2] leaf-component sweep is
absorbed into the single new rule rather than 21 separate edits.

Note: the broad pkg/api/** glob does NOT match a hypothetical bare
pkg/api package. Per the package list, no bare pkg/api exists (only
sub-packages), so a bare-path companion is not needed (consistent
with how pkg/apis is handled — no bare-path companion in any rule
because the bare path doesn't exist).

This is a structural simplification, not a coverage change. Combined
with [F2] and [F3], it eliminates 19 rules, adds 1 rule with the
correct deny shape, and brings pkg/api/testing into scope.
```

```
[F6] SEVERITY: MEDIUM
Category: Vacuous Rule (carryover from review #6 [F7] / #5 [F7] / #4 [F8] / #3 [F5])
Affected Rule / Constraint: functionsRules (lines 2261-2294) —
pkg/util/** maxReturnValues=5/maxLines=250 and pkg/apis/**
maxReturnValues=8/maxLines=350.

What is wrong:
Carryover from rounds 3-6. Both function rules `[PASS]` in the report.
With COVERAGE RATE 0%, this means either (a) the calibration is
correct AND the rules are scanning real upstream code, or (b) they
scanned zero functions. Cannot disambiguate.

The author retained the round-4 prediction text predicting specific
upstream functions expected to violate post-coverage:

  pkg/apis/core/validation.{ValidatePodSpec, ValidatePodTemplateSpec,
                            ValidatePersistentVolumeSpec}
  pkg/apis/networking/validation.{validateIngressBackend,
                                  ValidateNetworkPolicySpec}
  pkg/util/iptables.(*runner).restoreInternal

Round 7 retained these predictions unchanged. They cannot be
confirmed until [F1] is resolved.

Why it matters:
Same as previous rounds. Function-complexity rules are the most
likely class to catch quality regressions when correctly wired, and
the most likely to produce noise when not. A rule that passes on 0%
coverage is identical in CI signal to no rule at all.

The pkg/apis/** maxLines=350 cap is also at-risk of being too
generous. Looking at upstream master at HEAD-of-time:

  - pkg/apis/core/validation.ValidatePodSpec is ~330 lines (rough
    estimate; it has changed across releases).
  - pkg/apis/core/validation.ValidatePersistentVolumeSpec is ~250
    lines.

If maxLines=350 is set conservatively above the actual upstream
maximum, the rule passes for every function and never bites — the
opposite of the documented intent ("functions exceeding these
limits indicate validation logic that should be split into composable
validators rather than written as monolithic if-else chains").

How to fix it:
Resolve [F1] (operations fix). Re-run against actual upstream.
Confirm the predicted functions surface as violations. If they pass
without violation, the limits are too generous and should be
tightened until they bite a known offender (e.g., maxLines=200 if
no util function exceeds 200 lines; maxLines=300 if no apis function
exceeds 300).

If the run shows that no function in pkg/util/** exceeds maxLines=250,
EITHER:
  (a) the cap is too generous — tighten to a value below the largest
      observed function;
  (b) the test predictions were wrong — investigate whether
      iptables.(*runner).restoreInternal has been refactored upstream.

Same algorithm for pkg/apis/**.

Without an empirical run, this finding cannot move from MEDIUM to
either CLOSED or HIGH. The function rules are stuck in limbo until
[F1] is resolved.
```

```
[F7] SEVERITY: MEDIUM
Category: Test File Scope
Affected Rule / Constraint: All 79 dependency rules. The arch-go
config and the Makefile do not exclude `*_test.go` files.

What is wrong:
arch-go's default behavior is to scan ALL `.go` files in the module,
including `_test.go`. Neither the YAML nor the Makefile passes any
flag to exclude tests; the default is in effect.

In the Kubernetes codebase, test files routinely cross-import for
test fixture construction:

  - pkg/util/iptables/iptables_test.go often imports
    pkg/util/iptables/testing for fake runners. If the test helper
    weren't a sibling, it could legitimately reach for kubelet
    types.
  - pkg/apis/core/validation/validation_test.go imports
    k8s.io/api/core/v1 (external) and may reach for pkg/api/pod
    helpers. The pkg/apis/** deny list forbids pkg/api/**, so this
    legitimate test import would fail.
  - pkg/scheduler/profile/profile_test.go could import
    pkg/scheduler/testing (a documented expected false positive
    source).

When [F1] is resolved and coverage rises above 0%, these test-file
imports will all be scanned. Some will trigger rule violations that
are arguably legitimate (test code reaching for test fixtures from
across layers).

The author has acknowledged the four canonical test helpers as
"expected false positive sources" (lines 71-76: pkg/scheduler/testing,
pkg/controller/testutil, pkg/registry/registrytest, pkg/volume/testing)
under the broad globs. But:

  1. Other test files in the constrained subjects can legitimately
     import from across layers WITHOUT routing through those four
     test helpers.
  2. The author has not committed to one of the two viable approaches:
     (a) exclude `*_test.go` from analysis via a Makefile flag,
     (b) accept test-induced false positives and carve them out
     individually.

Why it matters:
Once [F1] is resolved, the rule-by-rule report may include test-
induced [FAIL] lines that the team must triage. If the volume is
high (e.g., dozens of test files in pkg/apis importing pkg/api), the
report's signal will be drowned in noise. Low severity because it's
a triage-cost issue, not a coverage gap; but it should be addressed
before [F1] resolution to avoid spending engineering time on
false-positive triage.

How to fix it:
Two viable options:

(a) Exclude test files from arch-go analysis. arch-go does not
    natively support test exclusion via the YAML, but the Makefile
    can use `--exclude` patterns at invocation time (verify support
    in the installed arch-go version). Alternatively, run two
    arch-go invocations with different rule sets — production code
    with full constraints, test code with relaxed constraints.

(b) Accept test-file analysis and carve narrow exceptions per
    directory. Document which test directories legitimately cross
    layers. This is more work upfront but more architecturally
    accurate (test code SHOULD respect layering except where
    explicitly justified).

Recommendation: (a) for the initial post-[F1] run to establish a
green baseline, then evaluate whether (b) provides additional value.

Add to the Makefile (lines 119-122, before the arch-go check):

```makefile
# Exclude test files from arch-go analysis (review #7 [F7]).
# Test files legitimately cross layers for fixture construction;
# treating them as production code produces high-volume false
# positives. Re-evaluate per-directory once the production rule
# corpus stabilises.
ARCH_GO_EXCLUDES := --exclude '*_test.go'

@cd "$(PROJECT_ROOT)" && \
  ...
  $(ARCH_GO_BIN) check $(ARCH_GO_EXCLUDES) | tee "$$OUT_FILE"; \
  ...
```

If arch-go does not support `--exclude` at the invocation level,
add a note to the SCOPE OF ANALYSIS section documenting that test
files are scanned and that test-induced false positives must be
triaged manually.

Acceptance test (after [F1] is closed):
  Run `make arch-check` and observe whether the rule-by-rule report
  contains [FAIL] lines for `pkg/util/iptables/iptables_test.go` or
  similar. If yes, the test exclusion is needed; if no, the test
  files happen to be well-behaved and exclusion is optional.
```

```
[F8] SEVERITY: LOW
Category: Cosmetic | Description Drift
Affected Rule / Constraint: Top-level `description:` (lines 139-212),
SCOPE OF ANALYSIS comment (lines 46-136).

What is wrong:
The top-level description and the SCOPE OF ANALYSIS comment in round
7 are accurate on the subject + object bare-path convention (closed
review #6 [F8]). They do NOT mention:

  1. The Layer-2 deny-list gap on leaf-component subsystems
     (review #7 [F2]). A maintainer reading the prose forms the
     impression that pkg/apis/** and pkg/api/** are constrained
     against importing the same set of subsystems as pkg/util/**.
     They are not — the Layer-2 rules omit ten leaf-component
     entries.

  2. The pkg/api/testing zero-coverage case (review #7 [F3]). The
     SCOPE OF ANALYSIS comment lists pkg/api/testing as
     "intentionally excluded" via the fan-out (line 76 implicitly,
     and the round-4 [F5] description), but does not document that
     this exclusion leaves pkg/api/testing with no Layer-2 → upper-
     layer enforcement at all.

  3. The ten unconstrained leaf packages (review #7 [F4]). The
     SCOPE OF ANALYSIS comment lists them as "covered only by the
     broad pkg/** -> cmd/** catch-all" — but the catch-all only
     forbids cmd-imports, not Layer-4 / Layer-5 / leaf-component
     imports. The phrasing implies coverage that does not exist.

Why it matters:
Same root cause as round 6 [F8] — prose drifts ahead of mechanical
fixes. Lower severity than the structural findings because it's
fixable with a description edit. Worth documenting because three
rounds of review have iterated on the description without catching
these phrasing issues.

How to fix it:
After [F2], [F3], [F4] are applied, update the SCOPE OF ANALYSIS
comment to:

  (a) Strike the ten leaf packages from "covered only by catch-all"
      and either add per-leaf rules (per [F4]) or document them as
      explicitly out of scope.

  (b) Document pkg/api/testing as covered under the broad pkg/api/**
      rule (per [F3] / [F5] option (a)) or via a dedicated rule
      (option (b)).

  (c) Add a note alongside the Layer-2 description that the deny
      lists now include leaf-component subsystems, mirroring the
      util-layer convention.

Until [F2-F4] are applied, add a TODO comment at the top of the
SCOPE OF ANALYSIS block:

```yaml
# TODO (review #7 [F2] / [F3] / [F4]):
#   - Layer-2 deny lists (pkg/apis/**, pkg/api fan-out) currently
#     omit leaf-component subsystems (volume, security, etc.) that
#     the util-layer correctly bans. Apply the same shape to Layer-2.
#   - pkg/api/testing has zero subject coverage; bring it into scope
#     under a broad pkg/api/** rule with documented expected-false-
#     positive treatment, OR add a dedicated rule.
#   - The "leaf utility packages with no specific rule" enumeration
#     at lines 132-135 is unconstrained against component imports;
#     the global pkg/** → cmd/** catch-all does not provide the
#     coverage the comment claims.
```

After the fixes are applied, strip the TODO and update the prose to
match the new shape. The description becomes accurate without
further edits once [F2-F4] are closed.
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

Round 7 is the cleanest YAML of the seven-round series. The round-6
[F2] mechanical sweep — applying bare + `/**` companion pairs to
every deny-list entry on the OBJECT side — is well executed across
all 79 dependency rules. The vacuous `pkg/kubectl/**` rule was
deleted; the description prose accurately reflects the new shape.
The util-layer deny list (lines 235-271) is the canonical example of
a complete leaf-isolation rule.

**But three structural gaps remain:**

1. The Layer-2 rules (pkg/apis/** and 20 pkg/api fan-out rules) deny
   only the seven mid-tier subsystems. They omit every leaf-component
   subsystem (volume, security, credentialprovider, printers, probe,
   securitycontext, serviceaccount, kubectl, kubemark, plugin) that
   the util-layer rule correctly forbids. Per the YAML's own layer
   model, Layer-2 must not import leaf-component subsystems any more
   than util must. The asymmetry affects 21 of the 79 dependency rules.

2. `pkg/api/testing` has zero subject coverage. The pkg/api fan-out
   was introduced specifically to exclude it (round 4 [F5]); the
   exclusion is total — no compensating rule was added. It is the
   only first-party Layer-2 helper whose Layer-2 → upper-layer
   imports are completely unconstrained.

3. Ten leaf packages explicitly listed in the SCOPE OF ANALYSIS
   comment as "covered only by the broad pkg/** -> cmd/** catch-all"
   are in fact unconstrained against everything except cmd imports.
   pkg/auth/nodeidentifier, pkg/cluster/ports, pkg/routes,
   pkg/features, etc. can silently import pkg/scheduler / pkg/kubelet
   / pkg/proxy / pkg/controller / pkg/controlplane and arch-go
   reports nothing.

Plus the empirical 0% coverage gate from rounds 2-6 has not budged
for the sixth consecutive round. The round-7 YAML changes — like
those of rounds 2-6 — are unverified.

**Order of fixes** (highest leverage first):

1. **[F1]** — re-run `make arch-check PROJECT_ROOT=/path/to/upstream/k8s.io/kubernetes`
   and capture the report. Confirm `COVERAGE RATE NN% [PASS]` with
   NN ≥ 30%. The Makefile is correct; what's missing is the run.
   **This is the single highest-leverage fix in the entire 7-round
   series — it has been the same recommendation for six consecutive
   rounds.**

2. **[F3] + [F5]** (combined) — collapse the 20-rule pkg/api fan-out
   into a single broad `pkg/api/**` rule. This simultaneously closes
   [F3] (brings pkg/api/testing into scope as an expected-false-
   positive source) and [F5] (eliminates 19 redundant rules). Net
   change: -19 rules.

3. **[F2]** — add the leaf-component deny entries (volume,
   credentialprovider, kubectl, kubemark, printers, probe, security,
   securitycontext, serviceaccount, plugin) to the new collapsed
   pkg/api/** rule AND to the pkg/apis/** rule. The util-layer rule
   shows the correct shape. With [F3]+[F5] applied first, this is a
   2-rule edit instead of a 21-rule edit.

4. **[F4]** — add per-leaf-package isolation rules for pkg/auth/**,
   pkg/cluster/ports, pkg/routes, pkg/features at minimum. Optional:
   add for pkg/capabilities, pkg/certauthorization, pkg/fieldpath,
   pkg/windows/service, pkg/client/** (these are smaller / less
   sensitive and could be documented as out-of-scope instead).

5. **[F7]** — add `--exclude '*_test.go'` to the Makefile's arch-go
   invocation, OR document test-file analysis as accepted (with
   per-directory exception carve-outs) in the SCOPE OF ANALYSIS
   block.

6. **[F6]** — empirically verify the function-complexity caps fire
   (post-[F1]). Tighten if they don't bite.

7. **[F8]** — update the description and SCOPE OF ANALYSIS comment
   to match the new rule shape after [F2-F5] are applied.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:

- `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The rule-by-rule report contains [FAIL] lines for the [F2] / [F4]
  acceptance tests:
  - Add `import "k8s.io/kubernetes/pkg/volume"` to a file in
    pkg/apis/core/validation → expect [FAIL] for the pkg/apis/**
    rule citing pkg/volume.
  - Add `import "k8s.io/kubernetes/pkg/scheduler"` to a file in
    pkg/auth/nodeidentifier → expect [FAIL] for the new pkg/auth/**
    rule.
  - Add `import "k8s.io/kubernetes/pkg/kubelet"` to a file in
    pkg/api/testing → expect [FAIL] for the new broad pkg/api/**
    rule (or the dedicated pkg/api/testing rule).
- The function rules surface ≥ 1 violation in `pkg/apis/core/validation`
  (predicted: ValidatePodSpec).
- The kubelet manager naming rules surface ≥ 1 struct each
  (predicted: `volumeManager`, `imageManager`, `containerManagerImpl`,
  etc.).

Until those four empirical conditions are met, this report should
not be a CI gate; it is a cosmetic banner. Round 7 is the most
structurally sound of the seven-round series — the OBJECT-side
bare-path sweep was the highest-leverage YAML edit since round 1 —
but the Layer-2 deny list is incomplete on the leaf-component axis,
pkg/api/testing has zero subject coverage, ten leaf packages are
unconstrained, and the empirical state is unchanged for the sixth
consecutive round.

---

**End of Review #7.**
