# Adversarial Review #6 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 6

---

## Executive Summary

- **Total documented constraints identified: 25.** Ten high-level invariants come from the Kubernetes "Components" reference page (`https://kubernetes.io/docs/concepts/overview/components/`): control-plane vs. node split; kube-apiserver as the only API surface; etcd persistence; kube-scheduler / kube-controller-manager / cloud-controller-manager / kubelet / kube-proxy as separate binaries; Container runtime as the CRI boundary; addons as separate processes; kubectl/kubeadm as clients. Fifteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`) one-way; Layer-2 peer ban (`pkg/api/**` ↔ `pkg/apis/**`); Layer-3 (registry) → Layer-4 ban; the Layer-4 non-coupling lattice (controller / scheduler / kubelet / proxy / kubeapiserver); Layer-5 controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider / security / securitycontext / serviceaccount / probe / kubectl / kubemark / printers / plugin leaf-isolation; intra-layer fences (kubelet/container, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`); util / apis function-complexity caps.
- **Total rules generated: 101** — 80 `dependenciesRules` + 19 `namingRules` + 2 `functionsRules` (matches the test-report totals exactly). Round 6 reduced rule count from 152 → 101 by adopting review #5 [F2] option (B): reverted the 32-entry controller fan-out, the 7-entry scheduler fan-out, the 11-entry registry fan-out, and the 17-entry volume fan-out to broad globs paired with bare-path siblings. Each affected subsystem (controller, scheduler, registry, volume) now has exactly two rules — `<X>` and `<X>/**` — instead of ~30 fan-out entries with bare-path companions. This is the right architectural choice; the per-sub-package fan-outs were a maintenance liability.
- **Coverage rate (constraint → rule mapping): ~22/25 on paper, materially less in effective reach.** Round 6 closes ALL eight findings from round 5 ([F1] is the only one that is not closeable from the YAML — it is operations). Specifically: [F2] resolved via option (B) — broad globs reinstated for controller / scheduler / registry / volume, with bare-path siblings (lines 561-693, 828-857); [F3] resolved — every cmd binary now has bare + `/**` companion rules (lines 1196-1484); [F4] folded into [F2]'s rollback — the 11 predictive non-existent registry/scheduler sub-trees are gone; [F5] resolved — bare-path companions added for `pkg/kubelet/container` (lines 1124-1135) and `pkg/proxy/util` (lines 1155-1166); [F6] resolved — `pkg/probe` and `pkg/probe/**` added to the util-layer deny list (lines 229-230); [F7] still vacuous due to [F1]; [F8] resolved — description prose accurately reflects the current rule shape.
- **Effective coverage rate from arch-go itself: still 0%.** The test-report footer reads `COVERAGE RATE 0% [FAIL]` — byte-for-byte identical to rounds 2, 3, 4, and 5. This is the **fifth consecutive round** with the same empirical state. 101 of 101 rules `[PASS]`-ing against zero matched packages is the same vacuous-pass symptom that has gated nothing in five submissions.
- **Critical Gaps** (constraints with zero or vacuous enforcement in the current report):
  - **`COVERAGE RATE 0% [FAIL]` is unchanged from rounds 2-5** ([F1]). The Makefile is structurally correct (bash + `pipefail` + 30% floor + module identity check). Until this is fixed, every paper finding remains paper.
  - **NEW SYSTEMIC GAP: every deny list (`internal:` field) targets `<X>/**` patterns without bare-path companions, mirroring the exact bare-path glob-semantics defect that was fixed for the `package:` (subject) field across rounds 3-5 but never propagated to the `internal:` (object) field** ([F2]). The same arch-go glob semantics apply to both fields. So `pkg/util/**` denies `pkg/kubelet/**` (line 213) but NOT bare `pkg/kubelet`; `pkg/scheduler/**` denies `pkg/controller/**` (line 689) but NOT bare `pkg/controller` (where the canonical `controller_utils.go` framework helpers live). Concrete impact: a `pkg/scheduler/profile` source file can `import "k8s.io/kubernetes/pkg/controller"` (bare) and arch-go does not flag it, even though that's exactly the Layer-4 lattice violation the rule was written to catch. Affects ~70 of the 80 dependency rules.
  - **The `pkg/util/**` deny list is internally inconsistent on bare paths** ([F3]). Lines 207-236 include the bare path for `pkg/registry`, `pkg/controller`, `pkg/scheduler`, `pkg/volume`, `pkg/credentialprovider`, `pkg/kubectl`, `pkg/kubemark`, `pkg/printers`, `pkg/probe`, `pkg/security`, `pkg/securitycontext`, `pkg/serviceaccount` — but OMIT the bare paths for `pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`, `pkg/controlplane`. The author was aware of the bare-path issue (12 of the 16 affected subsystems are correctly listed) but missed four. This is not a glob-semantics blind spot — it is an inconsistency that was overlooked in the deny-list update.
  - **The intra-layer fences are bypassable via bare-path imports** ([F4]). `pkg/kubelet/container` denies `pkg/kubelet/server/**` (not bare `pkg/kubelet/server` where `server.go` lives); `pkg/proxy/util` denies `pkg/proxy/iptables/**` (not bare `pkg/proxy/iptables` where `proxier.go` and the canonical `Proxier` implementation live). These are leaf packages, so the bare path IS where the canonical implementation lives — the deny list misses exactly the file the rule was written to constrain.
- **Overall verdict: `FAIL`.** Round 6 is the cleanest YAML of the six-round series — option (B) of [F2] produced the right shape (101 rules vs. 152), the cmd-side bare-path companions are uniformly applied, and the description prose is now accurate. Five of the eight round-5 findings are closed surgically; the rule-count reduction shows the author followed the recommendation. **But the same bare-path insight that was applied to the `package:` field (subject) was never propagated to the `internal:` field (object).** The result is that ~70 of the 80 dependency rules can be silently bypassed by importing the bare canonical package (`pkg/kubelet`, `pkg/proxy`, `pkg/scheduler`, `pkg/controller`, `pkg/controlplane`, `pkg/kubeapiserver`, etc.) instead of any of its sub-packages — and bare canonical packages are exactly where the canonical implementations of those subsystems live. The numerical coverage rate looks like it should be high once [F1] is resolved; it will mask the asymmetric deny-list defect. Plus the empirical 0% coverage gate from rounds 2-5 has not budged for the fifth consecutive round.

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope (carryover from review #5 [F1] / #4 [F1] / #3 [F1])
Affected Rule / Constraint: All 101 rules; Makefile target `arch-check`.

What is wrong:
The supplied test report still has `COVERAGE RATE 0% [FAIL]` — same as
rounds 2, 3, 4, and 5. The Makefile is structurally correct from round 4:

  - Lines 28-29: SHELL := /bin/bash; .SHELLFLAGS := -eu -o pipefail -c
  - Lines 41, 46: ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes;
                  ARCH_GO_MIN_COVERAGE := 30
  - Lines 121-123: set -o pipefail; arch-go check | tee; STATUS=$${PIPESTATUS[0]}
  - Lines 133-139: aborts on coverage < 30%

But the test report submitted as input to this round documents a run where:
  (a) coverage is 0%, and
  (b) all 101 rules pass.

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
  (iv)  The captured artifact is from a stale pre-coverage-floor run
        whose [PASS] / [FAIL] table was written before the coverage
        floor check fired.

In any of those cases, the rule-by-rule [PASS] table is generated BEFORE
the coverage-floor check fires. So the captured artifact shows 101 [PASS]
lines and a footer reading `COVERAGE RATE 0% [FAIL]` at the bottom — and
a casual review treats it as a green run.

This is the FIFTH consecutive round with the same empirical state. Each
round has changed the rule set (28 → 85 → 99 → 152 → 101) without
producing a single empirically demonstrated firing rule. None of the
round-2/3/4/5/6 fixes — the kubelet manager bare-path move, the per-API-group
registry fan-out, the per-sub-package pkg/api fan-out, the cmd-side
symmetry, the option-(B) fan-out rollback — has been empirically validated.

Why it matters:
Three of the seven other findings in this report ([F2], [F3], [F4])
are concrete predictions of how the round-6 YAML will actually behave
once enforcement fires. Until [F1] is resolved, those predictions cannot
be confirmed and the team cannot tell which of the round-6 additions /
deletions are doing real work versus which are vacuous.

The signal-to-noise of the architectural review is gated on this single
operations step. Five rounds of review are downstream of an empirical
condition that has not been satisfied once.

How to fix it:
This is an operations problem, not a YAML problem. The fix is procedural,
unchanged from review #5 [F1] / #4 [F1] / #3 [F1]:

  1. Clone or check out actual upstream `k8s.io/kubernetes` (the module,
     not the docs repository). Verify with `cd <checkout> && go list -m`.
     The output must read exactly `k8s.io/kubernetes`.

  2. Run `make arch-check PROJECT_ROOT=<that-checkout>`. Capture the
     full output.

  3. Confirm the report contains `COVERAGE RATE NN% [PASS]` with NN ≥ 30.

  4. Confirm the rule-by-rule table now contains [FAIL] lines for at
     least the predicted offenders:

       - `pkg/apis/core/validation.ValidatePodSpec` → maxLines=350 violation
       - `pkg/util/iptables.(*runner).restoreInternal` → maxLines=250
         violation
       - At least one cross-layer test-helper false positive from
         `pkg/scheduler/testing` / `pkg/controller/testutil` /
         `pkg/registry/registrytest` / `pkg/volume/testing` (option-B
         tradeoff documented at lines 162-167 of arch-go.yml)

  5. Only after observing those expected violations and either fixing
     them in source or carving narrow exceptions in arch-go.yml should
     the report be treated as a real CI gate.

If you cannot run against full upstream, run against a partial mirror that
contains at minimum:
  - pkg/util/iptables (≥1 file)
  - pkg/apis/core/validation (≥1 file)
  - pkg/controller/{deployment,job,cronjob,statefulset} (canonical
    controllers)
  - pkg/scheduler/framework
  - pkg/registry/rbac (canonical Storage/REST handler)
  - cmd/kubelet, cmd/kube-apiserver, cmd/kubectl

Until the four steps are completed, the YAML changes from rounds 1-6 are
unverified.
```

```
[F2] SEVERITY: CRITICAL
Category: Coverage Gap | Glob Semantics (the bare-path insight extended to the deny-list/`internal:` field)
Affected Rule / Constraint: ~70 of the 80 dependency rules — every
rule whose `internal:` deny list contains a `<X>/**` pattern that
references a subsystem with a real bare-path Go package.

What is wrong:
Across rounds 3, 4, and 5, the YAML author correctly internalised that
arch-go's `**` glob requires at least one path segment after the prefix,
so `pkg/scheduler/**` does NOT match the bare `pkg/scheduler` package.
That insight was applied EXHAUSTIVELY to the `package:` (subject) field
of every rule:

  Lines 566 / 583     — pkg/registry (bare) + pkg/registry/**
  Lines 622 / 638     — pkg/controller (bare) + pkg/controller/**
  Lines 670 / 686     — pkg/scheduler (bare) + pkg/scheduler/**
  Lines 707 / 722     — pkg/kubelet (bare) + pkg/kubelet/**
  Lines 736 / 750     — pkg/proxy (bare) + pkg/proxy/**
  Lines 763 / 777     — pkg/kubeapiserver (bare) + pkg/kubeapiserver/**
  Lines 799 / 810     — pkg/controlplane (bare) + pkg/controlplane/**
  Lines 833 / 849     — pkg/volume (bare) + pkg/volume/**
  ... and ~10 more leaf-subsystem and cmd-binary pairs.

But the EXACT SAME glob semantics apply to the `internal:` (object)
field — the patterns that say which imports are forbidden. The author
overlooked this. Spot check the symmetric rules:

  pkg/util/** deny list (lines 207-236):
    "k8s.io/kubernetes/pkg/kubelet/**"      — but bare pkg/kubelet NOT denied
    "k8s.io/kubernetes/pkg/proxy/**"        — but bare pkg/proxy NOT denied
    "k8s.io/kubernetes/pkg/kubeapiserver/**" — but bare pkg/kubeapiserver NOT denied
    "k8s.io/kubernetes/pkg/controlplane/**" — but bare pkg/controlplane NOT denied

  pkg/apis/** deny list (lines 248-256):
    "k8s.io/kubernetes/pkg/api/**"          — only `**` form
    "k8s.io/kubernetes/pkg/registry/**"     — but bare pkg/registry NOT denied
    "k8s.io/kubernetes/pkg/controller/**"   — but bare pkg/controller NOT denied
    "k8s.io/kubernetes/pkg/kubelet/**"      — but bare pkg/kubelet NOT denied
    "k8s.io/kubernetes/pkg/proxy/**"        — but bare pkg/proxy NOT denied
    "k8s.io/kubernetes/pkg/scheduler/**"    — but bare pkg/scheduler NOT denied
    "k8s.io/kubernetes/pkg/kubeapiserver/**" — but bare pkg/kubeapiserver NOT denied
    "k8s.io/kubernetes/pkg/controlplane/**" — but bare pkg/controlplane NOT denied

  pkg/api/job (bare) deny list (lines 286-295):
    SAME 8-entry pattern, all in `<X>/**` form, none bare.
    Repeated identically in pkg/api/job/**, pkg/api/legacyscheme,
    pkg/api/legacyscheme/**, pkg/api/node, pkg/api/node/**, ...
    × 9 sub-packages × 2 (bare + /**) = 18 rules with the same gap.

  pkg/registry / pkg/registry/** Layer-3→4 ban (lines 569-574, 586-591):
    "k8s.io/kubernetes/pkg/controller/**"   — bare pkg/controller NOT denied
    "k8s.io/kubernetes/pkg/kubelet/**"      — bare pkg/kubelet NOT denied
    "k8s.io/kubernetes/pkg/proxy/**"        — bare pkg/proxy NOT denied
    "k8s.io/kubernetes/pkg/scheduler/**"    — bare pkg/scheduler NOT denied
    "k8s.io/kubernetes/pkg/kubeapiserver/**" — bare pkg/kubeapiserver NOT denied
    "k8s.io/kubernetes/pkg/controlplane/**" — bare pkg/controlplane NOT denied

  pkg/controller / pkg/controller/** Layer-4 lattice (lines 624-629, 640-645):
    "k8s.io/kubernetes/pkg/scheduler/**"    — bare pkg/scheduler NOT denied
    "k8s.io/kubernetes/pkg/kubelet/**"      — bare pkg/kubelet NOT denied
    "k8s.io/kubernetes/pkg/proxy/**"        — bare pkg/proxy NOT denied
    "k8s.io/kubernetes/pkg/kubeapiserver/**" — bare pkg/kubeapiserver NOT denied
    "k8s.io/kubernetes/pkg/controlplane/**" — bare pkg/controlplane NOT denied

  ... and so on for pkg/scheduler, pkg/kubelet, pkg/proxy,
  pkg/kubeapiserver, pkg/controlplane, pkg/volume, pkg/credentialprovider,
  pkg/kubectl, pkg/printers, pkg/kubemark, pkg/security,
  pkg/securitycontext, pkg/serviceaccount, pkg/probe, plugin, and every
  cmd-binary rule.

Concrete proof of impact (every example below uses an actual bare package
present in the supplied package list):

  Example 1 — Layer-4 lattice silent breach:
    File: pkg/scheduler/profile/profile.go
    Add:  import "k8s.io/kubernetes/pkg/controller"
    Reality: pkg/controller (bare, line 86 of package list) declares
             KeyFunc, RemoveTaintOffNode, NewControllerExpectations,
             WaitForCacheSync — the actual cross-layer help that a
             scheduler component might "want" to reach for.
    Rule: pkg/scheduler/** deny list (line 689) contains
          pkg/controller/** but NOT pkg/controller. The import path
          `k8s.io/kubernetes/pkg/controller` does NOT match
          `k8s.io/kubernetes/pkg/controller/**`. arch-go reports [PASS].
    Documented constraint violated: Layer-4 non-coupling lattice.

  Example 2 — Layer-3 → Layer-4 silent breach:
    File: pkg/registry/rbac/escalation_check.go
    Add:  import "k8s.io/kubernetes/pkg/scheduler"
    Reality: pkg/scheduler (bare, line 208) declares Scheduler struct,
             SchedulerOptions, the canonical Run(ctx) loop.
    Rule: pkg/registry/** deny list (line 590) contains
          pkg/scheduler/** but NOT pkg/scheduler.
    Documented constraint violated: Layer 3 → Layer 4 inversion ban.

  Example 3 — Util-layer silent breach:
    File: pkg/util/iptables/iptables.go
    Add:  import "k8s.io/kubernetes/pkg/kubelet"
    Reality: pkg/kubelet (bare, line 137) declares the Kubelet struct,
             SyncHandler, the canonical Run/syncLoop.
    Rule: pkg/util/** deny list (line 213) contains pkg/kubelet/** but
          NOT pkg/kubelet. (See [F3] for the inconsistency; the four
          subsystems where the bare path is missing from this deny list
          are exactly the four whose bare-path companion the round-5
          author added on the SUBJECT side but forgot to mirror here.)
    Documented constraint violated: Layer 1 → upper-layer one-way ban.

  Example 4 — Layer-2 peer ban silent breach:
    File: pkg/api/pod/util.go
    Add:  import "k8s.io/kubernetes/pkg/registry"
    Reality: pkg/registry (bare, line 203) declares helpers used by REST
             handlers.
    Rule: pkg/api/pod (bare, lines 414-425) deny list contains
          pkg/registry/** but NOT pkg/registry.
    Documented constraint violated: Layer-2 → Layer-3 inversion ban.

  Example 5 — Layer-4 lattice silent breach (kubelet → scheduler):
    File: pkg/kubelet/cm/cm.go
    Add:  import "k8s.io/kubernetes/pkg/scheduler"
    Reality: pkg/scheduler (bare) is real (see Example 1).
    Rule: pkg/kubelet/** deny list (line 726) contains pkg/scheduler/**
          but NOT pkg/scheduler.
    Documented constraint violated: Layer-4 non-coupling lattice.

  Example 6 — Layer-5 controlplane → kubelet silent breach:
    File: pkg/controlplane/instance.go
    Add:  import "k8s.io/kubernetes/pkg/kubelet"
    Reality: pkg/kubelet (bare) is real.
    Rule: pkg/controlplane/** deny list (line 813) contains
          pkg/kubelet/** but NOT pkg/kubelet.
    Documented constraint violated: Layer-5 → node-side ban.

  Example 7 — cmd binary silent breach:
    File: cmd/kube-apiserver/apiserver.go
    Add:  import "k8s.io/kubernetes/pkg/scheduler"
    Reality: pkg/scheduler (bare) is real.
    Rule: cmd/kube-apiserver/** deny list (line 1297) contains
          pkg/scheduler/** but NOT pkg/scheduler.
    Documented constraint violated: separate-binary deployability invariant.

For each of those bare-package targets — pkg/kubelet, pkg/proxy,
pkg/scheduler, pkg/controller, pkg/registry, pkg/controlplane,
pkg/kubeapiserver, pkg/volume, pkg/credentialprovider, pkg/kubectl,
pkg/printers, pkg/kubemark, pkg/security, pkg/securitycontext,
pkg/serviceaccount, pkg/probe — the bare path IS in the supplied
package list and IS where the canonical (or commonly-imported) helper
code lives. The deny lists' `<X>/**` patterns systematically exempt
all of them.

Why it matters:
This finding subsumes [F2], [F3], [F4] of review #5. Round 6 closed
those findings on the SUBJECT side (rule no longer exempts bare
canonical packages from being scanned). But the OBJECT side — what the
rule denies — was not updated symmetrically. The result is that the
Layer-4 non-coupling lattice (the centerpiece architectural invariant
in this YAML, encoding the documented "separate binaries" rule from the
Kubernetes Components page) is bypassable by importing the bare
canonical package of any sibling subsystem.

This affects roughly 70 of the 80 dependency rules. Specifically:

  1× pkg/util/**                          (4 missing bare denies)
  1× pkg/apis/**                          (7 missing bare denies)
  18× pkg/api fan-out                     (each missing 7 bare denies)
  2× pkg/registry / pkg/registry/**       (each missing 6 bare denies)
  2× pkg/controller / pkg/controller/**   (each missing 5 bare denies)
  2× pkg/scheduler / pkg/scheduler/**     (each missing 5 bare denies)
  2× pkg/kubelet / pkg/kubelet/**         (each missing 5 bare denies)
  2× pkg/proxy / pkg/proxy/**             (each missing 5 bare denies)
  2× pkg/kubeapiserver / pkg/kubeapiserver/** (each missing 5)
  2× pkg/controlplane / pkg/controlplane/** (each missing 2 — kubelet, proxy)
  2× pkg/volume / pkg/volume/**           (each missing 5 bare denies)
  2× pkg/credentialprovider / **          (each missing 5 bare denies)
  2× pkg/security / **                    (each missing 6 bare denies)
  2× pkg/securitycontext / **             (each missing 6 bare denies)
  2× pkg/serviceaccount / **              (each missing 6 bare denies)
  2× pkg/probe / **                       (each missing 6 bare denies)
  2× pkg/kubectl / **                     (each missing 6 bare denies)
  2× pkg/printers / **                    (each missing 6 bare denies)
  2× pkg/kubemark / **                    (each missing 5 bare denies)
  1× plugin/**                            (5 missing bare denies)
  2× pkg/kubelet/container / **           (each missing 3 bare sub-package denies)
  2× pkg/proxy/util / **                  (each missing 4 bare backend denies)
  20× cmd/<X> + cmd/<X>/**                (each missing 5-7 bare denies)

The defect is invisible in the report's compliance summary. Once
coverage rises above 0% (resolving [F1]), every rule in this YAML will
report [PASS] for any source file that imports the bare canonical
package of a forbidden subsystem rather than a sub-package — the
import path simply does not match the `<X>/**` pattern.

This is the same root cause as review #3 [F2], #4 [F2], #5 [F2]. It
was caught and fixed for the SUBJECT field three times; it has never
been caught for the OBJECT field. The author of round 6 added 51 new
bare-path subject rules without updating any of the deny lists.

How to fix it:
For every deny-list entry of the form `<X>/**`, add a sibling entry
`<X>` (the bare path), wherever `<X>` is a real Go package in the
module. Mechanical patch shape:

```yaml
# BEFORE (current)
package: "k8s.io/kubernetes/pkg/util/**"
shouldNotDependsOn:
  internal:
    - "k8s.io/kubernetes/pkg/api/**"
    - "k8s.io/kubernetes/pkg/apis/**"
    - "k8s.io/kubernetes/pkg/registry"
    - "k8s.io/kubernetes/pkg/registry/**"
    - "k8s.io/kubernetes/pkg/controller"
    - "k8s.io/kubernetes/pkg/controller/**"
    - "k8s.io/kubernetes/pkg/kubelet/**"      # MISSING bare pkg/kubelet
    - "k8s.io/kubernetes/pkg/proxy/**"        # MISSING bare pkg/proxy
    - "k8s.io/kubernetes/pkg/scheduler"
    - "k8s.io/kubernetes/pkg/scheduler/**"
    - "k8s.io/kubernetes/pkg/kubeapiserver/**" # MISSING bare pkg/kubeapiserver
    - "k8s.io/kubernetes/pkg/controlplane/**" # MISSING bare pkg/controlplane
    # ... rest is OK ...

# AFTER (corrected)
package: "k8s.io/kubernetes/pkg/util/**"
shouldNotDependsOn:
  internal:
    - "k8s.io/kubernetes/pkg/api/**"
    - "k8s.io/kubernetes/pkg/apis/**"
    - "k8s.io/kubernetes/pkg/registry"
    - "k8s.io/kubernetes/pkg/registry/**"
    - "k8s.io/kubernetes/pkg/controller"
    - "k8s.io/kubernetes/pkg/controller/**"
    - "k8s.io/kubernetes/pkg/kubelet"        # ADDED
    - "k8s.io/kubernetes/pkg/kubelet/**"
    - "k8s.io/kubernetes/pkg/proxy"          # ADDED
    - "k8s.io/kubernetes/pkg/proxy/**"
    - "k8s.io/kubernetes/pkg/scheduler"
    - "k8s.io/kubernetes/pkg/scheduler/**"
    - "k8s.io/kubernetes/pkg/kubeapiserver"  # ADDED
    - "k8s.io/kubernetes/pkg/kubeapiserver/**"
    - "k8s.io/kubernetes/pkg/controlplane"   # ADDED
    - "k8s.io/kubernetes/pkg/controlplane/**"
    # ... rest unchanged ...
```

Apply the same `<X>` + `<X>/**` pairing to EVERY deny-list entry across
the entire YAML. Concrete script (recommended):

```bash
# In a checkout of the YAML, for every deny-list entry of form
#   - "k8s.io/kubernetes/pkg/<X>/**"
# add an immediately preceding sibling
#   - "k8s.io/kubernetes/pkg/<X>"
# UNLESS the bare path was already added.
#
# This is mechanical and can be implemented as a one-pass YAML rewrite.
```

For the pkg/api/* fan-out (18 rules), apply the same patch to each of
the 18 deny lists (they are all identical by content; one batch
edit).

After the patch, re-verify on a real upstream run that imports of bare
canonical packages now produce [FAIL] lines. Concrete acceptance test:

  In a sandbox checkout, add:
    pkg/scheduler/profile/profile_test.go:
      package profile
      import _ "k8s.io/kubernetes/pkg/controller"  // bare
      func TestBarePathBreach(t *testing.T) {}

  Run `make arch-check`. The expected output is a [FAIL] line for the
  pkg/scheduler/** rule's deny list, citing `pkg/controller` (bare) as
  the offending import. Pre-fix, the run reports [PASS] for that rule.

If that acceptance test does not fire post-fix, the deny-list patch is
incomplete — locate the missed `<X>/**` entries.

Implementation sequence:
  1. First, apply the YAML patch (purely mechanical).
  2. Then, run the acceptance test against a sandbox checkout.
  3. Only then run against full upstream and confirm [F1] is closed.
```

```
[F3] SEVERITY: HIGH
Category: Coverage Gap | Inconsistency
Affected Rule / Constraint: pkg/util/** deny list (lines 207-236).

What is wrong:
The pkg/util/** deny list is the most carefully-curated rule in the
YAML. It explicitly lists bare-path companions for 12 of the 16 forbidden
subsystems:

  ✓ pkg/registry        (line 209, bare)  + pkg/registry/**        (210)
  ✓ pkg/controller      (line 211, bare)  + pkg/controller/**      (212)
  ✓ pkg/scheduler       (line 215, bare)  + pkg/scheduler/**       (216)
  ✓ pkg/volume          (line 219, bare)  + pkg/volume/**          (220)
  ✓ pkg/credentialprovider (line 221)     + ...                    (222)
  ✓ pkg/kubectl         (line 223, bare)  + pkg/kubectl/**         (224)
  ✓ pkg/kubemark        (line 225, bare)  + pkg/kubemark/**        (226)
  ✓ pkg/printers        (line 227, bare)  + pkg/printers/**        (228)
  ✓ pkg/probe           (line 229, bare)  + pkg/probe/**           (230)
  ✓ pkg/security        (line 231, bare)  + pkg/security/**        (232)
  ✓ pkg/securitycontext (line 233, bare)  + pkg/securitycontext/** (234)
  ✓ pkg/serviceaccount  (line 235, bare)  + pkg/serviceaccount/**  (236)

But OMITS the bare path for the four subsystems whose subject-side rule
got the bare-path treatment in round 5 ([F4]):

  ✗ pkg/kubelet         — line 213 has pkg/kubelet/** only
  ✗ pkg/proxy           — line 214 has pkg/proxy/** only
  ✗ pkg/kubeapiserver   — line 217 has pkg/kubeapiserver/** only
  ✗ pkg/controlplane    — line 218 has pkg/controlplane/** only

These four are EXACTLY the four subsystems for which review #4 [F2]
added bare-path SUBJECT rules. Round 5 added the subject rules
(`pkg/kubelet` bare, `pkg/proxy` bare, `pkg/kubeapiserver` bare,
`pkg/controlplane` bare) but did not propagate the same edit to the
corresponding object-side entries in the util-layer deny list. Round 6
inherited the gap unchanged.

This is the visible "tip" of the systemic [F2] problem: the author
clearly understands the bare-path issue (12 of 16 entries in the util
deny list have the right shape) but missed updating four of them, and
missed it everywhere else in the YAML.

Why it matters:
  - pkg/util/iptables/iptables.go ⊕ import "k8s.io/kubernetes/pkg/kubelet"
    silently passes (bare pkg/kubelet declares the canonical Kubelet
    struct in pkg/kubelet/kubelet.go).
  - pkg/util/labels/labels.go ⊕ import "k8s.io/kubernetes/pkg/proxy"
    silently passes (bare pkg/proxy declares the Proxier interface in
    pkg/proxy/types.go and ServicePort/ServiceMap helpers).
  - pkg/util/node/node.go ⊕ import "k8s.io/kubernetes/pkg/kubeapiserver"
    silently passes (bare pkg/kubeapiserver declares apiserver-side
    admission/auth top-level wiring).
  - pkg/util/oom/oom.go ⊕ import "k8s.io/kubernetes/pkg/controlplane"
    silently passes.

In each case, the rule was meant to forbid the import direction; the
glob just doesn't match it. (See [F2] for the wider impact across all
80 rules; this finding flags the four entries in the util deny list
that match neither pattern of [F2] nor the round-5 bare-path effort.)

How to fix it:
Add the four missing bare-path entries:

```yaml
  - description: >
      Shared utility packages (pkg/util/**) are the lowest internal layer.
      ... [existing description]
    package: "k8s.io/kubernetes/pkg/util/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/api/**"
        - "k8s.io/kubernetes/pkg/apis/**"
        - "k8s.io/kubernetes/pkg/registry"
        - "k8s.io/kubernetes/pkg/registry/**"
        - "k8s.io/kubernetes/pkg/controller"
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/kubelet"          # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/**"
        - "k8s.io/kubernetes/pkg/proxy"            # ADDED
        - "k8s.io/kubernetes/pkg/proxy/**"
        - "k8s.io/kubernetes/pkg/scheduler"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver"    # ADDED
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane"     # ADDED
        - "k8s.io/kubernetes/pkg/controlplane/**"
        - "k8s.io/kubernetes/pkg/volume"
        - "k8s.io/kubernetes/pkg/volume/**"
        # ... rest unchanged
```

This four-line addition is the highest-leverage incremental fix in the
YAML — it closes the most visible inconsistency without requiring the
broader [F2] sweep. After [F3] is applied, treat it as the "done"
template for [F2]: every other deny list in the YAML must be updated to
match the same pattern (bare + `/**` pair for every real package).

Note that pkg/api and pkg/apis appear only in `<X>/**` form in the util
deny list (lines 207-208) — that is correct because the package list
contains no bare `pkg/api` or `pkg/apis` package; both are
purely "container" path prefixes.
```

```
[F4] SEVERITY: HIGH
Category: Coverage Gap | Glob Semantics (intra-layer fence specialisation of [F2])
Affected Rule / Constraint: pkg/kubelet/container deny list (lines 1130-1135 / 1145-1150)
and pkg/proxy/util deny list (lines 1160-1166 / 1173-1179).

What is wrong:
The intra-layer fences in round 6 correctly added bare-path SUBJECT
companions (review #5 [F5] — closed):

  Lines 1124-1135: package "k8s.io/kubernetes/pkg/kubelet/container"  (bare)
  Lines 1137-1150: package "k8s.io/kubernetes/pkg/kubelet/container/**"
  Lines 1155-1166: package "k8s.io/kubernetes/pkg/proxy/util"          (bare)
  Lines 1168-1179: package "k8s.io/kubernetes/pkg/proxy/util/**"

But the OBJECT-side deny lists for both pairs use only `<X>/**`:

  pkg/kubelet/container deny list (both bare and `/**`):
    "k8s.io/kubernetes/pkg/kubelet/server/**"        # MISSING bare
    "k8s.io/kubernetes/pkg/kubelet/config/**"        # MISSING bare
    "k8s.io/kubernetes/pkg/kubelet/volumemanager/**" # MISSING bare

  pkg/proxy/util deny list (both bare and `/**`):
    "k8s.io/kubernetes/pkg/proxy/iptables/**"       # MISSING bare
    "k8s.io/kubernetes/pkg/proxy/ipvs/**"           # MISSING bare
    "k8s.io/kubernetes/pkg/proxy/nftables/**"       # MISSING bare
    "k8s.io/kubernetes/pkg/proxy/winkernel/**"      # MISSING bare

The seven bare paths above are ALL real packages in the supplied package
list:
  - pkg/kubelet/server         (line 169 — canonical kubelet HTTP server)
  - pkg/kubelet/config         (line 145 — canonical config loader)
  - pkg/kubelet/volumemanager  (line 177 — canonical VolumeManager)
  - pkg/proxy/iptables         (line 194 — canonical iptables Proxier impl)
  - pkg/proxy/ipvs             (line 195 — canonical ipvs Proxier impl)
  - pkg/proxy/nftables         (line 199 — canonical nftables Proxier impl)
  - pkg/proxy/winkernel        (line 202 — canonical winkernel Proxier impl)

For these intra-layer fences specifically, the bare path is where the
canonical implementation lives — these are leaf packages (mostly
single-Go-package leaves with a primary `*.go` file containing the
implementation). The deny list misses exactly the file the rule was
written to constrain.

Concrete proof of impact:

  File: pkg/kubelet/container/runtime.go
  Add:  import "k8s.io/kubernetes/pkg/kubelet/server"
  Reality: pkg/kubelet/server (bare) declares the kubelet HTTP server
           in pkg/kubelet/server/server.go.
  Rule: pkg/kubelet/container/** deny list contains
        pkg/kubelet/server/** but NOT pkg/kubelet/server.
        arch-go reports [PASS].
  Documented constraint violated: kubelet container abstraction must
        not depend on kubelet HTTP server (the exact stated purpose of
        the rule at lines 1137-1144).

  File: pkg/proxy/util/utils.go
  Add:  import "k8s.io/kubernetes/pkg/proxy/iptables"
  Reality: pkg/proxy/iptables (bare) declares the iptables Proxier
           in pkg/proxy/iptables/proxier.go.
  Rule: pkg/proxy/util/** deny list contains pkg/proxy/iptables/**
        but NOT pkg/proxy/iptables. arch-go reports [PASS].
  Documented constraint violated: proxy utility tree must remain
        backend-agnostic (the exact stated purpose of the rule at
        lines 1168-1172).

The author's own rationale comment at lines 1117-1123 acknowledges that
some intra-layer directions are already prevented by Go's import-cycle
prohibition. That is true for the container → server / volumemanager
direction (server and volumemanager already import container.Runtime,
so the reverse is a cycle — Go rejects it at compile time). But:

  - container → config is NOT cycle-prevented (config depends on
    container only via interface, in some upstream versions).
  - The proxy/util → iptables/ipvs/nftables/winkernel directions are
    NOT cycle-prevented; util is the lowest-level shared helper and the
    backends import it, but a backend-import in util is structurally
    possible and would be invisible to arch-go today.

So the intra-layer fences are not fully redundant against the Go
compiler, and the bare-path gap is a real coverage hole.

Why it matters:
This is [F2] specialised to the intra-layer rules. The intra-layer
rules are smaller in scope but more sensitive to the bare-path issue
because the intra-layer targets are leaf packages (single Go package),
where the canonical implementation IS the bare path.

How to fix it:
Add bare-path companions to both intra-layer deny lists. Mechanical
patch:

```yaml
  - description: >
      Bare pkg/kubelet/container package ... [existing description]
    package: "k8s.io/kubernetes/pkg/kubelet/container"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/server"            # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/server/**"
        - "k8s.io/kubernetes/pkg/kubelet/config"            # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/config/**"
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager"     # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"

  - description: >
      The kubelet container abstraction layer ... [existing description]
    package: "k8s.io/kubernetes/pkg/kubelet/container/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/kubelet/server"            # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/server/**"
        - "k8s.io/kubernetes/pkg/kubelet/config"            # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/config/**"
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager"     # ADDED
        - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"

  - description: >
      Bare pkg/proxy/util package ... [existing description]
    package: "k8s.io/kubernetes/pkg/proxy/util"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/proxy/iptables"            # ADDED
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"
        - "k8s.io/kubernetes/pkg/proxy/ipvs"                # ADDED
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
        - "k8s.io/kubernetes/pkg/proxy/nftables"            # ADDED
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"
        - "k8s.io/kubernetes/pkg/proxy/winkernel"           # ADDED
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"

  - description: >
      The proxy utility tree ... [existing description]
    package: "k8s.io/kubernetes/pkg/proxy/util/**"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/proxy/iptables"            # ADDED
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"
        - "k8s.io/kubernetes/pkg/proxy/ipvs"                # ADDED
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
        - "k8s.io/kubernetes/pkg/proxy/nftables"            # ADDED
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"
        - "k8s.io/kubernetes/pkg/proxy/winkernel"           # ADDED
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"
```

Acceptance test (after [F1] is closed):

  Add to pkg/proxy/util/utils.go:
    import _ "k8s.io/kubernetes/pkg/proxy/iptables"
  Run `make arch-check`. Expected: [FAIL] for the pkg/proxy/util/**
  rule, citing pkg/proxy/iptables (bare) as the offending import.
  Pre-fix: [PASS].
```

```
[F5] SEVERITY: MEDIUM
Category: Coverage Gap | Vacuous-Adjacent (cmd/kubemark specialisation of [F2])
Affected Rule / Constraint: cmd/kubemark deny list (lines 1454-1460)
and cmd/kubemark/** deny list (lines 1476-1484).

What is wrong:
The cmd/kubemark rules deliberately use a per-backend deny list rather
than a broad pkg/proxy/** ban (review #4 [F7] — closed correctly):

  - "k8s.io/kubernetes/pkg/proxy/iptables/**"
  - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
  - "k8s.io/kubernetes/pkg/proxy/nftables/**"
  - "k8s.io/kubernetes/pkg/proxy/winkernel/**"

This is the right shape (it permits cmd/kubemark to legitimately import
pkg/proxy/kubemark while denying the production backends). But the
four backend entries are all in `<X>/**` form. Each of the four bare
paths IS a real package in the supplied list:

  pkg/proxy/iptables       (line 194)
  pkg/proxy/ipvs           (line 195)
  pkg/proxy/nftables       (line 199)
  pkg/proxy/winkernel      (line 202)

These bare paths are exactly where the canonical Proxier implementations
live (e.g., pkg/proxy/iptables/proxier.go declares the canonical
iptables `Proxier` struct). cmd/kubemark could `import
"k8s.io/kubernetes/pkg/proxy/iptables"` to call directly into the
production iptables code path — exactly the import the rule was written
to forbid — and arch-go would not catch it.

Why it matters:
The cmd/kubemark rules are the only place in the YAML where a
per-backend deny list is used; this is the most surgical part of the
rule set. The structural intent — "kubemark may use pkg/proxy/kubemark
to fake a kube-proxy, but must not import the real backends" — is
defensible. But the bare-path gap means cmd/kubemark/main.go can
silently import pkg/proxy/iptables (canonical iptables backend) and
ship as a real kube-proxy in disguise.

Lower severity than [F2-F4] because cmd/kubemark is a narrow corner of
the cmd layer and the bare-path imports there are unlikely (the kubemark
authors know to use pkg/proxy/kubemark). But the rule, as written, does
not enforce the intent.

How to fix it:
Add bare-path entries to both cmd/kubemark rules:

```yaml
  - description: >
      Bare cmd/kubemark package (binary entry point main.go).
      ... [existing description]
    package: "k8s.io/kubernetes/cmd/kubemark"
    shouldNotDependsOn:
      internal:
        - "k8s.io/kubernetes/pkg/controller/**"
        - "k8s.io/kubernetes/pkg/scheduler/**"
        - "k8s.io/kubernetes/pkg/proxy/iptables"          # ADDED
        - "k8s.io/kubernetes/pkg/proxy/iptables/**"
        - "k8s.io/kubernetes/pkg/proxy/ipvs"              # ADDED
        - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
        - "k8s.io/kubernetes/pkg/proxy/nftables"          # ADDED
        - "k8s.io/kubernetes/pkg/proxy/nftables/**"
        - "k8s.io/kubernetes/pkg/proxy/winkernel"         # ADDED
        - "k8s.io/kubernetes/pkg/proxy/winkernel/**"
        - "k8s.io/kubernetes/pkg/kubeapiserver/**"
        - "k8s.io/kubernetes/pkg/controlplane/**"

  - description: >
      The kubemark hollow-node simulator binary tree ... [existing]
    package: "k8s.io/kubernetes/cmd/kubemark/**"
    shouldNotDependsOn:
      internal:
        # SAME pattern as above, add the four bare-path entries.
```

This is part of the broader [F2] mechanical sweep — once [F2] is
applied uniformly, this rule is also fixed. Listed separately because
the per-backend deny is a structurally interesting case worth
double-checking.
```

```
[F6] SEVERITY: MEDIUM
Category: Vacuous Rule | Reserved-for-future
Affected Rule / Constraint: pkg/kubectl/** rule (lines 1022-1033).

What is wrong:
The pkg/kubectl/** rule (line 1025) targets a glob that does NOT match
any package in the supplied package list. Cross-checking line 136 of
the package list, only the bare `pkg/kubectl` exists; there is no
`pkg/kubectl/<sub>` sub-package in the snapshot.

The author acknowledged this in the description at lines 1022-1024:

    "Kubectl in-tree library sub-tree (pkg/kubectl/**) — currently
    mostly empty in upstream master, reserved for any future in-tree
    sub-package."

Reserving rule slots for hypothetical future sub-packages is a defensible
choice, but the rule is currently vacuous against the input. If the
package list reflects upstream master at the targeted commit, this rule
matches zero packages and passes vacuously regardless of source content.
The rule contributes nothing to current enforcement.

Why it matters:
Less severe than [F2-F4] — vacuity is a "noise" cost, not a coverage
hole. But three rounds of review have not caught this; the rule has
been carried since round 3 ([F11]) without any package matching it.
Worth flagging because:

  (a) Once [F1] is resolved, this will be one of the rules that
      reports `Packages matching pattern '...kubectl/**' should not
      depend on...` against an empty package set. That's a noise line in
      the rule-by-rule report.
  (b) The companion bare-path rule (line 1012, pkg/kubectl) IS doing
      real work — it constrains the actual pkg/kubectl package. So the
      vacuous /** rule is purely cosmetic.

How to fix it:
Two options:

(a) Delete the pkg/kubectl/** rule entirely. The bare-path rule covers
    the only real package. If a sub-package is added in upstream later,
    re-add the /** rule then.

```yaml
# Replace lines 1022-1033 (the pkg/kubectl/** rule) with a comment:
# Note: pkg/kubectl/** is currently empty in upstream master — only
# the bare pkg/kubectl package exists (compatibility shim). When in-tree
# kubectl sub-packages are reintroduced, add a /** rule here.
```

(b) Keep the rule but document that it is reserved-for-future and
    expected to match zero packages until upstream changes.

Option (a) is cleaner. Option (b) is more defensive against future
upstream growth and consistent with the SCOPE OF ANALYSIS prose at
lines 104-119 of the YAML.
```

```
[F7] SEVERITY: MEDIUM
Category: Vacuous Rule (carryover from review #5 [F7] / #4 [F8] / #3 [F5])
Affected Rule / Constraint: functionsRules (lines 1746-1779) —
pkg/util/** maxReturnValues=5/maxLines=250 and pkg/apis/**
maxReturnValues=8/maxLines=350.

What is wrong:
Carryover from rounds 3-5. Both function rules `[PASS]` in the report.
With COVERAGE RATE 0%, this means either (a) the calibration is correct
AND the rules are scanning real upstream code, or (b) they scanned zero
functions. Cannot disambiguate.

The author retained the round-4 prediction text predicting specific
upstream functions expected to violate post-coverage:

  pkg/apis/core/validation.{ValidatePodSpec, ValidatePodTemplateSpec,
                            ValidatePersistentVolumeSpec}
  pkg/apis/networking/validation.{validateIngressBackend,
                                  ValidateNetworkPolicySpec}
  pkg/util/iptables.(*runner).restoreInternal

Round 6 retained these predictions unchanged. They cannot be confirmed
until [F1] is resolved.

Why it matters:
Same as previous rounds. Function-complexity rules are the most likely
class to catch quality regressions when correctly wired, and the most
likely to produce noise when not. A rule that passes on 0% coverage is
identical in CI signal to no rule at all.

How to fix it:
Resolve [F1] (operations fix). Re-run against actual upstream. Confirm
the predicted functions surface as violations. If they pass without
violation, the limits are too generous and should be tightened until
they bite a known offender (e.g., maxLines=200 if no util function
exceeds 200 lines; maxLines=300 if no apis function exceeds 300).

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
[F8] SEVERITY: LOW
Category: Description Inaccuracy
Affected Rule / Constraint: top-level description block (lines 129-183),
SCOPE OF ANALYSIS comment (lines 46-127).

What is wrong:
The description block at lines 129-183 was rewritten in round 6 to
reflect the option-(B) rollback (broad globs for controller / scheduler
/ registry / volume). The new prose accurately describes the SUBJECT
side of every rule: bare-path companions are mentioned, the test-helper
false-positive sources are documented, and the cmd-side bare-path
treatment is explained.

But the description block does NOT mention the systemic gap on the
OBJECT side ([F2]). A maintainer reading the prose forms the impression
that the Layer-4 lattice and Layer-3 → Layer-4 ban are fully enforced.
They are not — the deny lists exempt the bare canonical packages of
every forbidden subsystem.

Specific phrasing that becomes inaccurate once [F2] is understood:

  Lines 144-149: "Layer-3 (registry) is blocked from every Layer-4
  sibling and Layer-5 (controlplane). Cmd-side rules block cross-binary
  coupling and now use the same bare + `/**` pair convention for all
  10 cmd binaries (review #5 [F3]) so the bare main.go entry points
  ... cannot silently back-import sibling-binary internals."

  This is true on the SUBJECT side (the bare main.go is now in scope as
  a scanned subject). It is false on the OBJECT side: the bare
  main.go can still silently import bare pkg/scheduler /
  pkg/kubelet / pkg/proxy / etc. — the deny lists' `<X>/**` patterns
  do not match those bare imports.

  Lines 150-156: "kubectl and kubectl-convert are client-only;
  kubeadm uses generated manifests and clientset only;
  kube-apiserver/cloud-controller-manager/kube-controller-manager/
  kube-scheduler/kube-proxy/kubelet binaries may not import each
  other's pkg/** subtrees and may not back-import controlplane or
  kubeapiserver assembly"

  Specifically false: "may not import each other's pkg/** subtrees" is
  almost true — they may not import the `<X>/**` SUB-paths of each
  other's pkg, but they CAN import the bare pkg/<sibling> path.

The SCOPE OF ANALYSIS block at lines 46-127 enumerates "First-party
paths covered by NO `package:` glob below" — vendor, generated, hack,
build, cluster, test, build-tooling cmd binaries, leaf utility
packages with no specific rule. It does NOT mention the symmetric
gap: "First-party paths NOT in the deny list of any rule that should
constrain them" — i.e., the bare canonical packages of every forbidden
subsystem.

Why it matters:
A maintainer reading the prose forms an incorrect mental model of
coverage. Once [F2] is fixed, the prose becomes accurate without
further edits. Until [F2] is fixed, the prose actively misrepresents
what arch-go is enforcing.

How to fix it:
After [F2] is applied uniformly (every `<X>/**` deny entry pairs with
a bare `<X>` deny entry), the description prose at lines 129-183 is
already accurate — no edit needed.

If [F2] is being applied incrementally (e.g., [F3] / [F4] / [F5] one
at a time), add a disclaimer at the top of the description until the
sweep is complete:

```yaml
description: >
  Enforces the Kubernetes component architecture. ... [existing prose]
  ...
  KNOWN GAP — review #6 [F2]: each rule's deny list (`internal:`)
  uses `<X>/**` glob patterns. arch-go's `**` does not match the bare
  parent path, so an import of bare `pkg/scheduler` / `pkg/kubelet` /
  `pkg/proxy` / `pkg/controller` / `pkg/registry` / `pkg/controlplane`
  / `pkg/kubeapiserver` / `pkg/volume` is invisible to the deny list.
  This is the same bare-path issue that was fixed for the SUBJECT
  field in rounds 3-5; the OBJECT field has not yet been updated.
  The pkg/util/** deny list shows the correct shape for 12 of 16
  subsystems (lines 207-236, with `pkg/registry` paired with
  `pkg/registry/**`, etc.) — generalise that shape to every deny list
  in the YAML.
```

After the [F2] sweep is complete, strip the disclaimer.

Also update the SCOPE OF ANALYSIS block at lines 104-126 to include
a "Deny-list bare-path coverage" sub-section listing which bare
canonical packages are denied across every subsystem. The current
block lists only SUBJECT coverage gaps.

Optional: add a comment at the top of the YAML (above
`description:`) listing the [F2] mechanical patch as the next
priority, so a future reviewer notices the open work item:

```yaml
# TODO (review #6 [F2]): apply bare-path companions to every deny-list
# entry of form `<X>/**`. The `pkg/util/**` rule shows the correct shape
# for 12 of 16 subsystems; mechanical YAML rewrite needed across ~70
# rules.
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

The structural progress between rounds 5 and 6 is significant:

- The 152-rule fan-out approach was reverted to a 101-rule broad-glob
  approach (review #5 [F2] option B — closed). The bare canonical
  sub-packages of pkg/controller (32 entries), pkg/scheduler (7),
  pkg/registry (11), and pkg/volume (17) are now in scope for the
  Layer-4 lattice and Layer-3 → Layer-4 ban via broad globs paired with
  bare-path siblings. Round 6 is the cleanest YAML in the six-round
  series.
- Bare-path companions added for all 10 cmd binaries (review #5 [F3]
  — closed). The vacuous `cmd/cloud-controller-manager/**` /
  `cmd/kubectl/**` / `cmd/kubectl-convert/**` rules are now paired with
  effective bare-path companions.
- Bare-path companions added for the intra-layer fences pkg/kubelet/container
  and pkg/proxy/util (review #5 [F5] — closed on the subject side).
- pkg/probe added to the util-layer deny list (review #5 [F6] — closed).
- The 11 predictive non-existent registry/scheduler sub-trees are gone
  (review #5 [F4] — closed via the [F2] rollback).
- Description prose accurately reflects the new subject-side rule shape
  (review #5 [F8] — closed for the subject side).

These are real fixes. The author followed the recommendation from
review #5 [F2] (option B) cleanly and the YAML is materially smaller
and more maintainable for it.

But the **bare-path insight that was applied to the SUBJECT field of
every rule across rounds 3-5 was never propagated to the OBJECT field
(the deny list) anywhere in the YAML.** ~70 of the 80 dependency rules
have deny lists that only target `<X>/**` patterns; arch-go's `**` does
not match the bare parent path; so imports of bare `pkg/scheduler`,
`pkg/kubelet`, `pkg/proxy`, `pkg/controller`, `pkg/registry`,
`pkg/controlplane`, `pkg/kubeapiserver`, `pkg/volume`, etc. are
invisible to every Layer-4 / Layer-3 / Layer-5 / leaf-isolation /
cmd-side rule. The bare paths in question ARE the canonical
implementations of those subsystems.

The pkg/util/** deny list at lines 207-236 shows the author DID
internalise the bare-path issue at one point — 12 of 16 subsystems
have correct bare-path entries — but missed 4 of them (kubelet, proxy,
kubeapiserver, controlplane) and missed propagating the pattern to any
other rule in the YAML.

Plus the empirical 0% coverage gate from rounds 2-5 has not budged.

**Order of fixes** (highest leverage first):

1. **[F1]** — re-run `make arch-check PROJECT_ROOT=/path/to/upstream/k8s.io/kubernetes`
   and capture the report. Confirm `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
   The Makefile is correct; what's missing is the run. **This is the
   single highest-leverage fix in the entire 6-round series — it has
   been the same recommendation for five consecutive rounds.**

2. **[F2]** — mechanical YAML rewrite: for every deny-list entry of
   form `<X>/**`, add a sibling entry `<X>` (the bare path), wherever
   `<X>` is a real Go package in the module. The pkg/util/** deny list
   is the canonical example of the right shape; generalise it to all
   80 dependency rules. Estimated ~120-150 new entries across the
   YAML. Mechanical and idempotent.

3. **[F3]** — incremental fix to pkg/util/**: add the four missing
   bare-path entries (`pkg/kubelet`, `pkg/proxy`, `pkg/kubeapiserver`,
   `pkg/controlplane`). This is the smallest-scope subset of [F2] and
   should be done first as the canonical template.

4. **[F4]** — apply [F2] to the intra-layer fences. Three bare-path
   entries for pkg/kubelet/container; four for pkg/proxy/util.

5. **[F5]** — apply [F2] to cmd/kubemark per-backend deny list.
   Four bare-path entries.

6. **[F6]** — delete the vacuous pkg/kubectl/** rule (or document it
   as reserved-for-future).

7. **[F7]** — empirically verify the function-complexity caps fire
   (post-[F1]).

8. **[F8]** — add a [F2]-disclaimer to the description block until
   [F2] is uniformly applied; strip after.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:

- `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The rule-by-rule report contains [FAIL] lines for the [F2] / [F4]
  acceptance tests:
  - Add `import "k8s.io/kubernetes/pkg/controller"` to a file in
    pkg/scheduler/profile → expect [FAIL] for the pkg/scheduler/**
    rule citing pkg/controller (bare).
  - Add `import "k8s.io/kubernetes/pkg/proxy/iptables"` to a file in
    pkg/proxy/util → expect [FAIL] for the pkg/proxy/util/** rule.
- The function rules surface ≥ 1 violation in `pkg/apis/core/validation`
  (predicted: ValidatePodSpec).
- The kubelet manager naming rules surface ≥ 1 struct each
  (predicted: `volumeManager`, `imageManager`, `containerManagerImpl`,
  etc.).

Until those four empirical conditions are met, this report should not
be a CI gate; it is a cosmetic banner. Round 6 is the most structurally
sound of the six-round series, but the empirical state is unchanged
and the asymmetric deny-list defect undermines the headline
architectural invariant (Layer-4 non-coupling lattice).

---

**End of Review #6.**
