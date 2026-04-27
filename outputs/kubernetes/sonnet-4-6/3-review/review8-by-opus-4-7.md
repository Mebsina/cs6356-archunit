# Adversarial Review #8 — `outputs/kubernetes/sonnet-4-6/arch-go.yml`

Reviewer: opus-4-7
Reviewed model: sonnet-4-6
Project: kubernetes/kubernetes
Round: 8

---

## Executive Summary

- **Total documented constraints identified: 26.** Ten high-level invariants come from the Kubernetes "Components" reference page (`https://kubernetes.io/docs/concepts/overview/components/`): control-plane vs. node split; kube-apiserver as the only API surface; etcd persistence; kube-scheduler / kube-controller-manager / cloud-controller-manager / kubelet / kube-proxy as separate binaries; Container runtime as the CRI boundary; addons as separate processes; kubectl/kubeadm as clients. Sixteen more come from the YAML's own header chart: Layer 1 (`pkg/util/**`) → Layer 6 (`cmd/**`) one-way; Layer-2 peer ban (`pkg/api/**` ↔ `pkg/apis/**`); Layer-3 (registry) → Layer-4 ban; the Layer-4 non-coupling lattice (controller / scheduler / kubelet / proxy / kubeapiserver); Layer-5 controlplane → {kubelet, proxy} ban; cmd → pkg one-way; volume / credentialprovider / security / securitycontext / serviceaccount / probe / kubectl / kubemark / printers / plugin leaf-isolation; the round-7 nine cross-cutting leaf-isolation rules; intra-layer fences (kubelet/container, proxy/util); naming conventions (`Controller`, `Manager`, `Proxier`); util / apis function-complexity caps; cmd-binary cross-import fence; cmd/kubemark per-backend exception.

- **Total rules generated: 90** — 69 `dependenciesRules` + 19 `namingRules` + 2 `functionsRules` (matches the test-report totals exactly). Round 8 is 10 rules smaller than round 7 (100 → 90) thanks to the round-7 [F3]+[F5] collapse of the 20-rule pkg/api fan-out into a single broad `pkg/api/**` rule, partially offset by the +9 new cross-cutting leaf-isolation rules from [F4].

- **Coverage rate (constraint → rule mapping): 22/26 on paper.** Round 8 closes ALL eight findings from round 7: [F1] is operations-only and is unchanged (carryover); [F2] applied — the leaf-component subsystem deny entries (volume, credentialprovider, kubectl, kubemark, printers, probe, security, securitycontext, serviceaccount, plugin/**) are now present in BOTH the `pkg/apis/**` rule (lines 322-357) and the new collapsed `pkg/api/**` rule (lines 411-445); [F3] applied — the 20-rule `pkg/api` fan-out collapsed to a single broad `pkg/api/**` rule that brings `pkg/api/testing` into scope as a documented expected-false-positive source (lines 392-445); [F4] applied — nine new dedicated leaf-isolation rules for `pkg/auth/**`, `pkg/capabilities`, `pkg/certauthorization`, `pkg/client/**`, `pkg/cluster/ports`, `pkg/features`, `pkg/fieldpath`, `pkg/routes`, `pkg/windows/service` (lines 1220-1401); [F5] subsumed by [F3]; [F6] still vacuous due to [F1]; [F7] still unaddressed (no `--exclude` flag in Makefile); [F8] resolved — top-level `description:` and SCOPE OF ANALYSIS prose updated.

- **Effective coverage rate from arch-go itself: still 0%.** The test-report footer reads `COVERAGE RATE 0% [FAIL]` — byte-for-byte identical to rounds 2, 3, 4, 5, 6, and 7. This is the **seventh consecutive round** with the same empirical state. 90 of 90 rules `[PASS]`-ing against zero matched packages is the same vacuous-pass symptom that has gated nothing in seven submissions. Every round-7 [F2]/[F3]/[F4]/[F5] structural improvement is unverified.

- **Critical Gaps** (constraints with zero or vacuous enforcement in the current report):
  - **`COVERAGE RATE 0% [FAIL]` is unchanged from rounds 2-7** ([F1]). The Makefile is structurally correct (bash + `pipefail` + 30% floor + module identity check). Until this is fixed, every paper finding remains paper.
  - **NEW SYSTEMIC GAP: round 7 [F4] added nine leaf-isolation SUBJECT rules but did NOT propagate the OBJECT-side counterpart to the `pkg/util/**` / `pkg/apis/**` / `pkg/api/**` deny lists** ([F2]). The header at lines 220-227 explicitly states the new nine packages "sit above pkg/util in the import DAG and below the component / control-plane layers" — meaning util / apis / api should not back-import them, mirroring the leaf-component subsystem treatment. But none of the three Layer-1/Layer-2 deny lists contains `pkg/auth/**`, `pkg/routes`, or `pkg/windows/service` (the three with clear consumer semantics). The asymmetry is the exact same shape that round 6 [F2]/[F3] fixed for the leaf-component subsystems and that round 7 [F2] fixed for the Layer-2 rules. It was simply not propagated to the round-7 [F4] additions.
  - **`plugin/**` rule omits `pkg/kubeapiserver` / `pkg/kubeapiserver/**` from its deny list** ([F3]). Lines 1177-1190 forbid plugin imports of controller, scheduler, kubelet, proxy, controlplane, and cmd — but NOT kubeapiserver. The rule's own description (lines 1173-1176) declares "In-tree auth plugins (plugin/**) are **loaded by kubeapiserver/admission**" — which means kubeapiserver imports plugin, so plugin must not back-import kubeapiserver (cycle). Every other leaf rule (security, securitycontext, serviceaccount, probe, kubectl, printers) correctly denies pkg/kubeapiserver and pkg/kubeapiserver/**. plugin/** is the only leaf rule that doesn't, despite being the leaf with the strongest documented coupling to kubeapiserver.
  - **`pkg/proxy/util/**` intra-layer fence is overly narrow** ([F4]). It denies only the four production backends (iptables, ipvs, nftables, winkernel), but pkg/proxy contains six other implementation/runner packages: `pkg/proxy/conntrack`, `pkg/proxy/healthcheck`, `pkg/proxy/kubemark`, `pkg/proxy/metaproxier`, `pkg/proxy/metrics`, `pkg/proxy/runner`. Of these, `pkg/proxy/kubemark` (a fake backend) and `pkg/proxy/metaproxier` (a meta-Proxier composition) are unambiguous backend implementations that the "implementation-agnostic" util layer should not import. They are silently exempt.
  - **`pkg/kubelet/container/**` intra-layer fence is overly narrow** ([F5]). The rule denies imports of three high-level kubelet sub-packages (server, config, volumemanager) but the kubelet has ~30 other high-level sub-packages (kuberuntime, status, eviction, lifecycle, pleg, prober, pluginmanager, oom, pod, allocation, cm, …) that all consume `pkg/kubelet/container.Runtime`. Container is the canonical abstraction; it must not import any of its consumers. Go's import-cycle prohibition prevents it for the specific consumers, but the rule is documented architectural intent — and the documented intent leaves out 90% of the kubelet sub-tree.

- **Overall verdict: `FAIL`.** Round 8 is the cleanest YAML of the eight-round series. The round-7 [F3]+[F5] pkg/api fan-out collapse is a structural simplification that brings `pkg/api/testing` into scope, eliminates 19 redundant rules, and turns the round-7 [F2] leaf-component sweep into a 2-rule edit instead of a 21-rule edit. The round-7 [F4] cross-cutting leaf-isolation rules close the long-standing "covered only by the broad pkg/** -> cmd/** catch-all" gap from earlier rounds. **But two structural gaps remain (the OBJECT-side counterpart of [F4] in util/apis/api, and the plugin/** missing-kubeapiserver asymmetry), the two intra-layer fences are still narrow, and the empirical 0% coverage gate from rounds 2-7 has not budged for the seventh consecutive round.**

---

## Findings

```
[F1] SEVERITY: CRITICAL
Category: Structural Gap | Module Scope (carryover from review #7 [F1] / #6 [F1] / #5 [F1] / #4 [F1] / #3 [F1])
Affected Rule / Constraint: All 90 rules; Makefile target `arch-check`.

What is wrong:
The supplied test report still has `COVERAGE RATE 0% [FAIL]` — same as
rounds 2, 3, 4, 5, 6, and 7. The Makefile is structurally correct
since round 4:

  - Lines 28-29: SHELL := /bin/bash; .SHELLFLAGS := -eu -o pipefail -c
  - Lines 41, 46: ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes;
                  ARCH_GO_MIN_COVERAGE := 30
  - Lines 121-123: set -o pipefail; arch-go check | tee;
                   STATUS=$${PIPESTATUS[0]}
  - Lines 133-139: aborts on coverage < 30%

But the test report submitted as input to this round documents a run
where:
  (a) coverage is 0%, and
  (b) all 90 rules pass.

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

This is the SEVENTH consecutive round with the same empirical state.
Each round has changed the rule set (28 → 85 → 99 → 152 → 101 → 100 →
90) without producing a single empirically demonstrated firing rule.
None of the round-2/3/4/5/6/7/8 fixes — the kubelet manager bare-path
move, the per-API-group registry fan-out, the per-sub-package pkg/api
fan-out, the cmd-side symmetry, the option-(B) fan-out rollback, the
OBJECT-side bare-path mechanical sweep, the pkg/api fan-out collapse,
the nine cross-cutting leaf-isolation rules — has been empirically
validated.

Why it matters:
[F2], [F3], [F4], [F5] in this report predict concrete unfixed defects
in the round-8 YAML. Until [F1] is resolved, no prediction can be
confirmed and the team cannot tell which round-8 additions are doing
real work versus which are vacuous.

The signal-to-noise of the architectural review is gated on this
single operations step. SEVEN rounds of review are downstream of an
empirical condition that has not been satisfied once. The cumulative
review effort across rounds 1-8 amounts to a paper analysis of a
configuration file that has never been exercised against the code it
is meant to constrain.

How to fix it:
This is an operations problem, not a YAML problem. The fix is
procedural, unchanged from review #7 [F1] / #6 [F1] / #5 [F1] /
#4 [F1] / #3 [F1]:

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
         `pkg/registry/registrytest` / `pkg/volume/testing` /
         `pkg/api/testing` (the fifth was added in round 8 [F3]
         option-B)

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
  - pkg/auth/nodeidentifier (acceptance test for round-8 [F4])
  - pkg/api/testing (acceptance test for round-8 [F3])
  - cmd/kubelet, cmd/kube-apiserver, cmd/kubectl
  - plugin/pkg/auth (acceptance test for round-8 [F3] of this report)

Until the four steps are completed, the YAML changes from rounds 1-8
are unverified.
```

```
[F2] SEVERITY: HIGH
Category: Coverage Gap | OBJECT-side counterpart of round 7 [F4] not propagated
Affected Rule / Constraint:
  - pkg/util/** rule (lines 256-305)
  - pkg/apis/** rule (lines 310-357)
  - pkg/api/** rule (lines 393-445)

What is wrong:
Round 7 [F4] introduced nine new dedicated leaf-isolation rules for
the cross-cutting leaf packages (pkg/auth/**, pkg/capabilities,
pkg/certauthorization, pkg/client/**, pkg/cluster/ports, pkg/features,
pkg/fieldpath, pkg/routes, pkg/windows/service) — placing them
explicitly above pkg/util in the import DAG. The header at lines
221-226 of the round-8 description states:

    "Review #7 [F4]: nine cross-cutting leaf packages ... that were
    previously documented as 'covered only by the broad pkg/** -> cmd/**
    catch-all' each now have a dedicated leaf-isolation rule, because
    the catch-all forbids only cmd-imports and not component / control-
    plane back-imports."

And the rule-level description for each new leaf (e.g., pkg/auth/**
at lines 1220-1224) reads:

    "A leaf helper above pkg/util but below the component layers;
    must not back-import any component or control-plane package."

The SUBJECT-side rules were added correctly. But the OBJECT-side
counterpart — the symmetric ban that says "util / apis / api must
not back-import THESE leaves either, because they sit above" — was
NOT propagated to the three lower-layer rules whose deny lists
already enumerate the leaf-component subsystems.

Compare what the util-layer rule denies (lines 269-305):

  ✓ pkg/api/**, pkg/apis/**           (Layer-2)
  ✓ pkg/registry, pkg/registry/**     (Layer-3)
  ✓ pkg/controller, pkg/controller/** (Layer-4)
  ✓ pkg/kubelet, pkg/kubelet/**       (Layer-4)
  ✓ pkg/proxy, pkg/proxy/**           (Layer-4)
  ✓ pkg/scheduler, pkg/scheduler/**   (Layer-4)
  ✓ pkg/kubeapiserver/**              (Layer-4)
  ✓ pkg/controlplane/**               (Layer-5)
  ✓ pkg/volume, pkg/volume/**         (leaf-component)
  ✓ pkg/credentialprovider/**         (leaf-component)
  ✓ pkg/kubectl, pkg/kubectl/**       (leaf-component)
  ✓ pkg/kubemark, pkg/kubemark/**     (leaf-component)
  ✓ pkg/printers, pkg/printers/**     (leaf-component)
  ✓ pkg/probe, pkg/probe/**           (leaf-component)
  ✓ pkg/security, pkg/security/**     (leaf-component)
  ✓ pkg/securitycontext/**            (leaf-component)
  ✓ pkg/serviceaccount, ...           (leaf-component)

  ✗ pkg/auth/**                       (round-7 [F4] cross-cutting leaf)
  ✗ pkg/capabilities                  (round-7 [F4] cross-cutting leaf)
  ✗ pkg/certauthorization             (round-7 [F4] cross-cutting leaf)
  ✗ pkg/client/**                     (round-7 [F4] cross-cutting leaf)
  ✗ pkg/cluster/ports                 (round-7 [F4] cross-cutting leaf)
  ✗ pkg/features                      (round-7 [F4] cross-cutting leaf)
  ✗ pkg/fieldpath                     (round-7 [F4] cross-cutting leaf)
  ✗ pkg/routes                        (round-7 [F4] cross-cutting leaf)
  ✗ pkg/windows/service               (round-7 [F4] cross-cutting leaf)

The same nine entries are missing from the pkg/apis/** deny list
(lines 322-357) and from the new collapsed pkg/api/** deny list
(lines 411-445).

The structural defect is identical to round 6 [F2] (which fixed the
OBJECT-side bare-path companions for the seven mid-tier subsystems)
and round 7 [F2] (which fixed the OBJECT-side leaf-component sweep
on Layer-2 rules). Round 8 added the round-7 [F4] SUBJECT rules but
did not perform the matching OBJECT-side sweep on the pre-existing
Layer-1 / Layer-2 deny lists.

Concrete proof of impact:

  Example 1 — pkg/util/iptables silently imports pkg/auth/nodeidentifier:
    File: pkg/util/iptables/iptables.go (or any util sub-package)
    Add:  import _ "k8s.io/kubernetes/pkg/auth/nodeidentifier"
    Reality: pkg/auth/nodeidentifier (line 80 of package list) is
             used by kube-apiserver authentication. Util imports
             auth would be an upward reach across the import DAG.
    Rule: pkg/util/** deny list does NOT contain pkg/auth/** or
          pkg/auth. arch-go reports [PASS].
    Documented constraint violated: header at line 222 places
             pkg/auth/** above util in the DAG; util must not climb
             above itself.

  Example 2 — pkg/apis silently imports pkg/routes:
    File: pkg/apis/core/types.go
    Add:  import "k8s.io/kubernetes/pkg/routes"
    Reality: pkg/routes (line 207) registers HTTP debug routes
             on the apiserver. It is consumed by kubeapiserver only.
    Rule: pkg/apis/** deny list does NOT contain pkg/routes.
          arch-go reports [PASS].

  Example 3 — pkg/api silently imports pkg/windows/service:
    File: pkg/api/pod/util.go
    Add:  import "k8s.io/kubernetes/pkg/windows/service"
    Reality: pkg/windows/service (line 261) is a Windows-platform
             helper for service-mode binaries. Layer-2 importing
             a platform-specific runtime helper inverts the layer
             DAG.
    Rule: pkg/api/** deny list does NOT contain pkg/windows/service.
          arch-go reports [PASS].

  Example 4 — pkg/util silently imports plugin/**:
    Note: the util-layer rule already correctly denies plugin/**
    via the round-7 [F2] sweep (line — wait, actually lines
    269-305 of the round-8 YAML do NOT contain plugin/**). Let's
    check: indeed plugin/** is in the pkg/apis/** deny list (line
    357) and the pkg/api/** deny list (line 445) but is MISSING
    from the pkg/util/** deny list. So this is a fourth example:
    File: pkg/util/iptables/iptables.go
    Add:  import _ "k8s.io/kubernetes/plugin/pkg/auth"
    Rule: pkg/util/** deny list does NOT contain plugin/**.
          arch-go reports [PASS].

The util-layer rule was the canonical "complete leaf-isolation"
example used by [F2] of round 7 to derive the Layer-2 fix shape.
But the util-layer rule itself is NOT complete — it has the round-7
[F2] leaf-components but is missing both the round-7 [F4] cross-
cutting leaves AND plugin/**.

Why it matters:
The util-layer / Layer-2 rules are the FOUNDATIONAL deny lists in
the YAML. They define the "what may a low layer import" envelope.
Every higher-layer rule implicitly delegates the lower-bound
enforcement to these three rules. If util can silently import
auth / routes / windows/service / plugin/**, then the entire
"util sits at the bottom" claim is only partially enforced.

Three of the missing entries are particularly architecturally
suspect (pkg/auth/**, pkg/routes, pkg/windows/service): each is
a high-level consumer of util, not a foundational helper. The
remaining six (capabilities, certauthorization, client/**,
cluster/ports, features, fieldpath) are more ambiguous — pkg/features
in particular self-describes as "imported by every component but
importing no component itself", which arguably permits util to
import features (feature gates being a foundational mechanism).
The empirical [F1] run will resolve the ambiguity for the latter
six; the former three are unambiguous defects today.

The asymmetry also affects the YAML's self-consistency claim. The
round-8 description (lines 220-226) advertises the cross-cutting
leaf rules as a closure of the round-7 [F4] gap. But closing the
SUBJECT side without the OBJECT side is the same half-fix pattern
that round 6 [F2] called out as a systemic defect.

How to fix it:
Append the nine cross-cutting leaf entries (plus plugin/** for util)
to the pkg/util/**, pkg/apis/**, and pkg/api/** deny lists. The
util-layer rule already has plugin/** missing too, so add that.

```yaml
# pkg/util/** rule (lines 269-305) — append:
- "k8s.io/kubernetes/pkg/auth/**"
- "k8s.io/kubernetes/pkg/capabilities"      # ambiguous; verify post-[F1]
- "k8s.io/kubernetes/pkg/certauthorization" # ambiguous; verify post-[F1]
- "k8s.io/kubernetes/pkg/client/**"         # ambiguous; verify post-[F1]
- "k8s.io/kubernetes/pkg/cluster/ports"     # ambiguous; verify post-[F1]
- "k8s.io/kubernetes/pkg/features"          # ambiguous — pkg/features
                                            # advertises itself as
                                            # "imported by every component
                                            # but importing no component
                                            # itself"; the deny may need
                                            # to be omitted post-[F1] if
                                            # util/iptables legitimately
                                            # gates on features.
- "k8s.io/kubernetes/pkg/fieldpath"         # ambiguous; verify post-[F1]
- "k8s.io/kubernetes/pkg/routes"            # unambiguous defect
- "k8s.io/kubernetes/pkg/windows/service"   # unambiguous defect
- "k8s.io/kubernetes/plugin/**"             # unambiguous defect
                                            # (was missing from the
                                            # round-7 [F2] util sweep)

# pkg/apis/** rule (lines 322-357) — append the same 9 entries
# (plugin/** is already present, line 357).

# pkg/api/** rule (lines 411-445) — append the same 9 entries
# (plugin/** is already present, line 445).
```

If the team prefers to be conservative on the six ambiguous entries,
add only the three unambiguous ones (`pkg/auth/**`, `pkg/routes`,
`pkg/windows/service`) plus `plugin/**` (which is missing from
util-layer but present in apis/api), and document the six ambiguous
ones as "to be added post-[F1] if empirically clean".

Acceptance test (after [F1] is closed):

  Add to pkg/util/iptables/iptables.go:
    import _ "k8s.io/kubernetes/pkg/auth/nodeidentifier"
  Run `make arch-check`. Expected: [FAIL] for the pkg/util/** rule
  citing pkg/auth/nodeidentifier as the offending import. Pre-fix:
  [PASS].

  Add to pkg/apis/core/validation/validation.go:
    import _ "k8s.io/kubernetes/pkg/routes"
  Run `make arch-check`. Expected: [FAIL] for the pkg/apis/** rule
  citing pkg/routes. Pre-fix: [PASS].

  Add to pkg/util/iptables/iptables.go:
    import _ "k8s.io/kubernetes/plugin/pkg/auth"
  Run `make arch-check`. Expected: [FAIL] for the pkg/util/** rule
  citing plugin/pkg/auth. Pre-fix: [PASS].

If any of the three acceptance tests does NOT fire post-fix, the
deny-list patch is incomplete on that entry — locate the missed
entry.

Mechanical sweep size: 3 rules × ~9 added entries = ~27 new lines.
Trivially scriptable.
```

```
[F3] SEVERITY: HIGH
Category: Semantic Error | Asymmetric leaf rule
Affected Rule / Constraint: plugin/** rule (lines 1173-1190).

What is wrong:
The plugin/** rule denies six subsystems:

```yaml
package: "k8s.io/kubernetes/plugin/**"
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
    - "k8s.io/kubernetes/pkg/controlplane"
    - "k8s.io/kubernetes/pkg/controlplane/**"
    - "k8s.io/kubernetes/cmd/**"
```

Notice what is missing: `pkg/kubeapiserver` and `pkg/kubeapiserver/**`.

Now compare with every other leaf rule in the YAML:

  - pkg/security/**       (lines 941-957)   — denies kubeapiserver ✓
  - pkg/securitycontext/**(lines 982-997)   — denies kubeapiserver ✓
  - pkg/serviceaccount/** (lines 1020-1035) — denies kubeapiserver ✓
  - pkg/probe/**          (lines 1059-1074) — denies kubeapiserver ✓
  - pkg/printers/**       (lines 1122-1136) — denies kubeapiserver ✓
  - pkg/kubectl           (lines 1085-1099) — denies kubeapiserver ✓
  - pkg/kubemark/**       (lines 1159-1171) — denies kubeapiserver ✓
  - pkg/credentialprovider/** (lines 891-904) — denies kubeapiserver ✓
  - pkg/volume/**         (lines 853-866)   — denies kubeapiserver ✓

All nine other leaf rules deny pkg/kubeapiserver. The plugin/** rule
is the ONE outlier.

Now read the plugin/** rule's own description (lines 1173-1176):

    "In-tree auth plugins (plugin/**) are LOADED BY KUBEAPISERVER/
    ADMISSION; they must not back-import component layers,
    controlplane, or cmd entry points."

The description explicitly states that kubeapiserver loads plugins
(i.e., pkg/kubeapiserver/admission imports plugin/pkg/auth). The
direction is `kubeapiserver → plugin`. Therefore plugin must NOT
import kubeapiserver — that would create an import cycle.

But the deny list omits kubeapiserver. The constraint the description
asserts is the constraint the rule does not enforce. This is a
direct semantic contradiction between the rule's own prose and its
deny list.

Concrete proof of impact:

  Example 1 — plugin/pkg/auth silently imports kubeapiserver:
    File: plugin/pkg/auth/some_plugin.go
    Add:  import "k8s.io/kubernetes/pkg/kubeapiserver/admission"
    Reality: pkg/kubeapiserver/admission is the admission control
             wiring that kubeapiserver itself uses to register
             plugins. plugin/pkg/auth importing it is the cycle the
             description calls out.
    Rule: plugin/** deny list does NOT contain pkg/kubeapiserver
          or pkg/kubeapiserver/**. arch-go reports [PASS].
    Documented constraint violated: the description's "loaded by
             kubeapiserver/admission" claim implies the inverse
             direction is forbidden.

Note that Go's import-cycle prohibition would catch this at compile
time IF kubeapiserver/admission directly imports plugin/pkg/auth.
But the typical pattern is kubeapiserver/admission imports a
specific plugin sub-package (e.g., plugin/pkg/auth/authenticator/...)
while plugin/pkg/auth itself sits above and is the registration
point. A non-cyclic kubeapiserver → plugin path could exist while
the rule still permits plugin → kubeapiserver. arch-go catches what
Go's compiler doesn't.

Why it matters:
This is the only leaf rule with a documented kubeapiserver coupling
(every plugin is LOADED BY kubeapiserver) and is the leaf rule MOST
likely to develop a circular-import pattern. The other nine leaf
rules deny kubeapiserver as a defensive measure even though they
have weaker coupling. The one rule that needs the deny most has
it missing.

Severity HIGH because:
  1. The defect is a single missing entry — trivial to fix.
  2. The constraint is explicitly stated in the rule's own description.
  3. The asymmetry breaks the "every leaf denies kubeapiserver"
     pattern that the rest of the YAML maintains.
  4. plugin/pkg/auth is the only first-party path under plugin/**
     in the package list (line 262), so the rule has exactly one
     subject and it is the high-coupling one.

How to fix it:
Append two entries to the plugin/** rule's deny list:

```yaml
- description: >
    In-tree auth plugins (plugin/**) are loaded by kubeapiserver/admission;
    they must not back-import component layers, controlplane, or cmd entry
    points. They must also not back-import kubeapiserver itself, because
    that would create the same import cycle that motivates the leaf rule
    in the first place (review #8 [F3]).
  package: "k8s.io/kubernetes/plugin/**"
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
      - "k8s.io/kubernetes/pkg/kubeapiserver"        # ADDED
      - "k8s.io/kubernetes/pkg/kubeapiserver/**"     # ADDED
      - "k8s.io/kubernetes/pkg/controlplane"
      - "k8s.io/kubernetes/pkg/controlplane/**"
      - "k8s.io/kubernetes/cmd/**"
```

Acceptance test (after [F1] is closed):

  Add to plugin/pkg/auth/identifier.go (or wherever plugin/pkg/auth's
  Go files live):
    import _ "k8s.io/kubernetes/pkg/kubeapiserver/admission"
  Run `make arch-check`. Expected: [FAIL] for the plugin/** rule
  citing pkg/kubeapiserver/admission. Pre-fix: [PASS].
```

```
[F4] SEVERITY: MEDIUM
Category: Overly Narrow
Affected Rule / Constraint: pkg/proxy/util intra-layer fences
  (lines 1477-1492 bare; lines 1494-1509 /**).

What is wrong:
The pkg/proxy/util/** rule denies four production proxy backends:

  - pkg/proxy/iptables / **
  - pkg/proxy/ipvs / **
  - pkg/proxy/nftables / **
  - pkg/proxy/winkernel / **

The intent (per the rule's description, lines 1494-1498) is:

    "The proxy utility tree (pkg/proxy/util/**) provides shared
    network helpers for kube-proxy implementations. It must not
    import specific backend implementations ... to ensure the
    utility layer remains implementation-agnostic."

But the actual pkg/proxy package list (lines 189-202 of the package
file) contains six MORE proxy sub-packages that are also
implementation-side:

  - pkg/proxy/conntrack    (kernel conntrack helpers — borderline;
                            could legitimately be used by util)
  - pkg/proxy/healthcheck  (backend health checks — borderline;
                            could be considered util-adjacent)
  - pkg/proxy/kubemark     (FAKE proxy backend — unambiguous
                            implementation; util must not import)
  - pkg/proxy/metaproxier  (meta-Proxier composition — unambiguous
                            implementation; util must not import)
  - pkg/proxy/metrics      (proxy metrics — borderline; metrics
                            packages typically sit at util level)
  - pkg/proxy/runner       (proxy runner — unambiguous high-level
                            orchestrator; util must not import)

Of these six, three are unambiguous backend / implementation /
orchestrator packages that the "implementation-agnostic" util layer
should not import:

  - pkg/proxy/kubemark    — fake backend with fakeProxier struct
  - pkg/proxy/metaproxier — meta-Proxier composition (combines real
                            backends)
  - pkg/proxy/runner      — proxy runner (orchestrates the
                            Proxier event loop)

The remaining three (conntrack, healthcheck, metrics) are ambiguous;
util might legitimately use them.

Concrete proof of impact:

  Example 1 — pkg/proxy/util silently imports pkg/proxy/kubemark:
    File: pkg/proxy/util/utils.go
    Add:  import _ "k8s.io/kubernetes/pkg/proxy/kubemark"
    Reality: pkg/proxy/kubemark is a fake/test proxy. util importing
             it would couple the implementation-agnostic util layer
             to a test backend.
    Rule: pkg/proxy/util/** deny list does NOT contain
          pkg/proxy/kubemark. arch-go reports [PASS].

  Example 2 — pkg/proxy/util silently imports pkg/proxy/runner:
    File: pkg/proxy/util/utils.go
    Add:  import "k8s.io/kubernetes/pkg/proxy/runner"
    Reality: pkg/proxy/runner orchestrates the per-backend Proxier
             event loop. util importing runner inverts the layer
             relationship.
    Rule: pkg/proxy/util/** deny list does NOT contain
          pkg/proxy/runner. arch-go reports [PASS].

The defect is structurally identical to round 6 [F2] (which fixed
the OBJECT-side bare paths for the four production backends already
listed) — but the round-6 fix added bare-path companions for the
EXISTING entries; it did not extend the entry SET to cover the
other implementations.

Why it matters:
The intra-layer fence's stated intent is "util remains
implementation-agnostic". Listing only four of seven implementation
packages partially enforces that intent. A motivated developer
adding a util-side helper could legitimately reach for
pkg/proxy/runner or pkg/proxy/kubemark and the rule would not flag
it.

Severity MEDIUM because:
  1. The four production backends are the most-likely targets
     (the rule does cover the high-frequency cases).
  2. Go's compile-time cycle detection prevents some directions
     (most kubelet-style sub-package coupling).
  3. The risk of pkg/proxy/util importing kubemark or metaproxier
     is real but low-probability in practice.

How to fix it:
Append the three unambiguous implementation entries (and the bare-path
companions that already exist for the four production backends per
round 6 [F4]). Optionally append the three borderline entries with a
note explaining the ambiguity.

```yaml
- description: >
    The proxy utility tree (pkg/proxy/util/**) provides shared
    network helpers for kube-proxy implementations. It must not
    import specific backend implementations (iptables, ipvs,
    nftables, winkernel) NOR composition / runner / fake backends
    (kubemark, metaproxier, runner) to ensure the utility layer
    remains implementation-agnostic. Review #8 [F4]: kubemark,
    metaproxier, runner added; conntrack, healthcheck, metrics
    deferred until empirical run reveals legitimate util-side use.
  package: "k8s.io/kubernetes/pkg/proxy/util/**"
  shouldNotDependsOn:
    internal:
      # production backends
      - "k8s.io/kubernetes/pkg/proxy/iptables"
      - "k8s.io/kubernetes/pkg/proxy/iptables/**"
      - "k8s.io/kubernetes/pkg/proxy/ipvs"
      - "k8s.io/kubernetes/pkg/proxy/ipvs/**"
      - "k8s.io/kubernetes/pkg/proxy/nftables"
      - "k8s.io/kubernetes/pkg/proxy/nftables/**"
      - "k8s.io/kubernetes/pkg/proxy/winkernel"
      - "k8s.io/kubernetes/pkg/proxy/winkernel/**"
      # composition / runner / fake (review #8 [F4])
      - "k8s.io/kubernetes/pkg/proxy/kubemark"
      - "k8s.io/kubernetes/pkg/proxy/metaproxier"
      - "k8s.io/kubernetes/pkg/proxy/runner"
```

Mirror the same patch on the bare-path rule at lines 1477-1492.

Acceptance test (after [F1] is closed):

  Add to pkg/proxy/util/utils.go:
    import _ "k8s.io/kubernetes/pkg/proxy/kubemark"
  Run `make arch-check`. Expected: [FAIL] for the pkg/proxy/util/**
  rule. Pre-fix: [PASS].
```

```
[F5] SEVERITY: MEDIUM
Category: Overly Narrow
Affected Rule / Constraint: pkg/kubelet/container intra-layer fences
  (lines 1435-1448 bare; lines 1450-1466 /**).

What is wrong:
The pkg/kubelet/container/** rule denies imports of three
high-level kubelet sub-packages:

  - pkg/kubelet/server / **
  - pkg/kubelet/config / **
  - pkg/kubelet/volumemanager / **

The intent (per the rule's description, lines 1450-1457):

    "The kubelet container abstraction layer (pkg/kubelet/container/**)
    defines runtime interfaces and must not import the kubelet HTTP
    server, its config loader, or the volume manager, to maintain
    clean separation between the container runtime abstraction and
    the serving layer."

But pkg/kubelet has ~30 sub-packages that consume
`pkg/kubelet/container.Runtime`, ALL of which sit ABOVE container
in the kubelet's internal import DAG. The package list (lines
137-179) shows:

  Above-container kubelet sub-packages NOT listed in the deny:
    - pkg/kubelet/allocation       (resource allocation)
    - pkg/kubelet/cadvisor         (cadvisor wrappers)
    - pkg/kubelet/checkpointmanager (state checkpoints)
    - pkg/kubelet/clustertrustbundle (trust bundles)
    - pkg/kubelet/cm               (container manager)
    - pkg/kubelet/configmap        (configmap manager)
    - pkg/kubelet/eviction         (eviction manager)
    - pkg/kubelet/images           (image manager)
    - pkg/kubelet/kubeletconfig    (kubelet config helpers)
    - pkg/kubelet/kuberuntime      (CRI runtime impl)
    - pkg/kubelet/lifecycle        (lifecycle hooks)
    - pkg/kubelet/logs             (logs API)
    - pkg/kubelet/metrics          (metrics)
    - pkg/kubelet/nodeshutdown     (node-shutdown handler)
    - pkg/kubelet/nodestatus       (node status)
    - pkg/kubelet/oom              (OOM handlers)
    - pkg/kubelet/pleg             (pod lifecycle event generator)
    - pkg/kubelet/pluginmanager    (CSI / DRA plugin manager)
    - pkg/kubelet/pod              (pod manager)
    - pkg/kubelet/preemption       (admission preemption)
    - pkg/kubelet/prober           (liveness/readiness probes)
    - pkg/kubelet/runtimeclass     (runtime class manager)
    - pkg/kubelet/secret           (secret manager)
    - pkg/kubelet/stats            (stats provider)
    - pkg/kubelet/status           (status manager)
    - pkg/kubelet/userns           (user namespace)
    - pkg/kubelet/watchdog         (watchdog)
    - pkg/kubelet/winstats         (Windows stats)

All of these import (or transitively import) pkg/kubelet/container.
By the same architectural intent that motivates the existing three
denies, container should not import any of them either.

The rule's own description acknowledges this incompleteness (lines
1422-1428):

    "Note (review #3 [F12]): some of the directions below
    (container → server, container → volumemanager) are likely
    already prevented by Go's import-cycle handling because
    server/volumemanager depend on container.Runtime. The rule
    remains as documentation of the intended architectural fence;
    verify on a real upstream run which directions actually fire
    vs. duplicate compiler-enforced cycles."

So the author already understood that Go's cycle detection handles
the existing three. But they listed only three — not the other
~25 — even though all are equivalent under the same reasoning.

Concrete proof of impact:

  Example 1 — pkg/kubelet/container imports pkg/kubelet/kuberuntime:
    File: pkg/kubelet/container/runtime.go (or any container file)
    Add:  import _ "k8s.io/kubernetes/pkg/kubelet/kuberuntime"
    Reality: kuberuntime is the CRI runtime IMPLEMENTATION.
             container defines the abstract Runtime interface;
             kuberuntime implements it. The implementation must not
             leak into the abstraction.
    Rule: pkg/kubelet/container/** deny list does NOT contain
          pkg/kubelet/kuberuntime. arch-go reports [PASS] (assuming
          Go's import-cycle detection doesn't fire, which depends
          on whether kuberuntime → container is a direct or
          transitive import).

  Example 2 — pkg/kubelet/container imports pkg/kubelet/status:
    File: pkg/kubelet/container/runtime.go
    Add:  import _ "k8s.io/kubernetes/pkg/kubelet/status"
    Rule: deny list does NOT contain pkg/kubelet/status. arch-go
          reports [PASS].

  Example 3 — pkg/kubelet/container imports pkg/kubelet/eviction:
    File: pkg/kubelet/container/runtime.go
    Add:  import _ "k8s.io/kubernetes/pkg/kubelet/eviction"
    Rule: deny list does NOT contain pkg/kubelet/eviction. arch-go
          reports [PASS].

Why it matters:
The intra-layer fence is "documentation of the intended
architectural fence" by the author's own admission. Documentation
that lists 3 of 30 instances is mostly accurate, but it's a poor
template for future maintenance: when a new kubelet sub-package is
added, no one will remember to extend the deny list, and the
container abstraction will be silently coupled to it.

Severity MEDIUM because:
  1. Most of the directions are likely caught by Go's import-cycle
     detection (every above-container kubelet sub-package imports
     container.Runtime, so cycles are blocked).
  2. The defect manifests only at the documentation / future-proofing
     level, not as a real architectural escape.
  3. The fix is mechanical but verbose.

How to fix it:
Two options:

(a) Inverted-glob approach (preferred): forbid container from
    importing any pkg/kubelet/<sub> sub-package whose simple name
    is not container itself. arch-go does not support negative
    globs, so this requires enumerating each above-container
    sub-package. Mechanical but verbose.

(b) Acknowledge the partial coverage in the description and
    document that the three listed sub-packages are the
    most-defensive subset; rely on Go's cycle detection for
    the rest. This is what the rule effectively does today —
    making it explicit reduces the appearance of a coverage gap.

Recommendation: option (b) — extend the description to
acknowledge that Go's cycle detection enforces the rest,
explicitly call out the three listed as "documented architectural
intent" rather than "exhaustive enforcement", and add a
maintenance note that new container-consumer sub-packages should
be added if they don't directly import container.Runtime.

```yaml
- description: >
    The kubelet container abstraction layer (pkg/kubelet/container/**)
    defines runtime interfaces and must not import any kubelet
    sub-package that consumes pkg/kubelet/container.Runtime. The
    three listed below (server, config, volumemanager) are the
    architectural intent in documentation form; the other ~25
    above-container sub-packages (kuberuntime, status, eviction,
    lifecycle, pleg, prober, pluginmanager, oom, pod, allocation,
    cm, ...) are protected at compile time by Go's import-cycle
    prohibition because they all import container.Runtime
    directly. When adding a new kubelet sub-package, add it to
    this deny list if it does not directly import container.Runtime.
  package: "k8s.io/kubernetes/pkg/kubelet/container/**"
  shouldNotDependsOn:
    internal:
      - "k8s.io/kubernetes/pkg/kubelet/server"
      - "k8s.io/kubernetes/pkg/kubelet/server/**"
      - "k8s.io/kubernetes/pkg/kubelet/config"
      - "k8s.io/kubernetes/pkg/kubelet/config/**"
      - "k8s.io/kubernetes/pkg/kubelet/volumemanager"
      - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
```

Or option (a), enumerate fully:

```yaml
package: "k8s.io/kubernetes/pkg/kubelet/container/**"
shouldNotDependsOn:
  internal:
    # canonical three (intentional architectural fence):
    - "k8s.io/kubernetes/pkg/kubelet/server"
    - "k8s.io/kubernetes/pkg/kubelet/server/**"
    - "k8s.io/kubernetes/pkg/kubelet/config"
    - "k8s.io/kubernetes/pkg/kubelet/config/**"
    - "k8s.io/kubernetes/pkg/kubelet/volumemanager"
    - "k8s.io/kubernetes/pkg/kubelet/volumemanager/**"
    # above-container consumers (review #8 [F5] — most are
    # already enforced by Go's cycle detection; listing here
    # documents the architectural intent and protects against
    # future container-RTC refactors that break the cycles):
    - "k8s.io/kubernetes/pkg/kubelet/kuberuntime"
    - "k8s.io/kubernetes/pkg/kubelet/kuberuntime/**"
    - "k8s.io/kubernetes/pkg/kubelet/status"
    - "k8s.io/kubernetes/pkg/kubelet/status/**"
    - "k8s.io/kubernetes/pkg/kubelet/eviction"
    - "k8s.io/kubernetes/pkg/kubelet/eviction/**"
    - "k8s.io/kubernetes/pkg/kubelet/pleg"
    - "k8s.io/kubernetes/pkg/kubelet/pleg/**"
    - "k8s.io/kubernetes/pkg/kubelet/prober"
    - "k8s.io/kubernetes/pkg/kubelet/prober/**"
    - "k8s.io/kubernetes/pkg/kubelet/pluginmanager"
    - "k8s.io/kubernetes/pkg/kubelet/pluginmanager/**"
    - "k8s.io/kubernetes/pkg/kubelet/cm"
    - "k8s.io/kubernetes/pkg/kubelet/cm/**"
    - "k8s.io/kubernetes/pkg/kubelet/lifecycle"
    - "k8s.io/kubernetes/pkg/kubelet/lifecycle/**"
    - "k8s.io/kubernetes/pkg/kubelet/pod"
    - "k8s.io/kubernetes/pkg/kubelet/pod/**"
    - "k8s.io/kubernetes/pkg/kubelet/preemption"
    - "k8s.io/kubernetes/pkg/kubelet/preemption/**"
    - "k8s.io/kubernetes/pkg/kubelet/oom"
    - "k8s.io/kubernetes/pkg/kubelet/oom/**"
    # ... and so on for the remaining ~17 sub-packages.
```

Recommend (b) for documentation-clarity reasons; (a) for stricter
enforcement.
```

```
[F6] SEVERITY: MEDIUM
Category: Vacuous Rule (carryover from review #7 [F6] / #6 [F7] / #5 [F7] / #4 [F8] / #3 [F5])
Affected Rule / Constraint: functionsRules (lines 2200-2233) —
pkg/util/** maxReturnValues=5/maxLines=250 and pkg/apis/**
maxReturnValues=8/maxLines=350.

What is wrong:
Carryover from rounds 3-7. Both function rules `[PASS]` in the report.
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

Round 8 retained these predictions unchanged. They cannot be
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
Category: Test File Scope (carryover from review #7 [F7])
Affected Rule / Constraint: All 69 dependency rules. The arch-go
config and the Makefile do not exclude `*_test.go` files.

What is wrong:
arch-go's default behavior is to scan ALL `.go` files in the module,
including `_test.go`. Neither the YAML nor the Makefile passes any
flag to exclude tests; the default is in effect. This is unchanged
from review #7 [F7].

The round-7 [F3] collapse of the pkg/api fan-out aggravates the
risk: pkg/api/testing is now a documented expected-false-positive
SUBJECT under the broad pkg/api/** glob, but it's not the only
test-shaped subject. Other examples:

  - pkg/util/iptables/iptables_test.go imports
    pkg/util/iptables/testing for fake runners (already a sibling,
    so it is fine — but illustrates the pattern).
  - pkg/apis/core/validation/validation_test.go imports
    k8s.io/api/core/v1 (external) and may reach for pkg/api/pod
    helpers. The pkg/apis/** deny list forbids pkg/api/**, so this
    legitimate test import would [FAIL].
  - pkg/scheduler/profile/profile_test.go could import
    pkg/scheduler/testing (already a documented expected false
    positive source under the broad pkg/scheduler/** glob).
  - pkg/api/testing/objects.go (the entire package being a test
    helper) may legitimately import clientset / informer / controller
    helpers — the round-8 description acknowledges this at lines
    400-405 but does not exclude the test files.

When [F1] is resolved and coverage rises above 0%, these test-file
imports will all be scanned. Some will trigger rule violations that
are arguably legitimate (test code reaching for test fixtures from
across layers).

The author has acknowledged five canonical test helpers as
"expected false positive sources" (pkg/scheduler/testing,
pkg/controller/testutil, pkg/registry/registrytest,
pkg/volume/testing, pkg/api/testing — added in round 8 [F3])
under the broad globs. But:

  1. Other test files in the constrained subjects can legitimately
     import from across layers WITHOUT routing through those five
     test helpers.
  2. The author has not committed to one of the two viable approaches:
     (a) exclude `*_test.go` from analysis via a Makefile flag,
     (b) accept test-induced false positives and carve them out
     individually.

Why it matters:
Same as round 7 [F7]. Once [F1] is resolved, the rule-by-rule report
may include test-induced [FAIL] lines that the team must triage.
If the volume is high, the report's signal will be drowned in noise.
Low-medium severity because it's a triage-cost issue, not a coverage
gap; but it should be addressed before [F1] resolution to avoid
spending engineering time on false-positive triage.

Round 8 added pkg/api/testing as a fifth expected-false-positive
source — this is incremental progress on the option-(b) approach
but not yet a complete strategy.

How to fix it:
Two viable options, unchanged from round 7 [F7]:

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
# Exclude test files from arch-go analysis (review #8 [F7]).
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
Affected Rule / Constraint: Top-level `description:` (lines 156-246),
SCOPE OF ANALYSIS comment (lines 46-153), and per-rule descriptions
on pkg/util/**, pkg/apis/**, pkg/api/**, plugin/**, pkg/proxy/util,
pkg/kubelet/container.

What is wrong:
The top-level description and the SCOPE OF ANALYSIS comment are
accurate on the round-7 [F2] / [F3] / [F4] / [F5] additions. They
do NOT mention:

  1. The OBJECT-side gap from [F2] above (the round-7 [F4]
     cross-cutting leaves were added as SUBJECTS but not as OBJECTS
     in the util / apis / api deny lists). The header and per-rule
     descriptions repeatedly assert that util / apis / api sit
     "below" the cross-cutting leaves but do not enforce that ban.

  2. The plugin/** missing-kubeapiserver asymmetry from [F3]. The
     plugin/** rule's description explicitly states that plugins
     are "loaded by kubeapiserver/admission" but does not mention
     that the deny list omits the inverse direction.

  3. The pkg/proxy/util narrow-deny gap from [F4]. The rule's
     description claims util "must not import specific backend
     implementations" — but lists only four of seven implementation
     packages.

  4. The pkg/kubelet/container narrow-deny gap from [F5]. The rule
     itself acknowledges (lines 1422-1428) that the listed three
     directions are likely already covered by Go's cycle detection;
     the description does not list the ~25 other above-container
     sub-packages that are equivalent under the same reasoning.

Why it matters:
Same root cause as round 6 [F8] / round 7 [F8] — prose drifts
ahead of mechanical fixes. Lower severity than the structural
findings because it's fixable with a description edit. Worth
documenting because eight rounds of review have iterated on the
description without catching these phrasing issues.

How to fix it:
After [F2], [F3], [F4], [F5] are applied, update the top-level
description and per-rule descriptions to:

  (a) Note that the util / apis / api deny lists now include the
      round-7 [F4] cross-cutting leaves (matching the
      SCOPE OF ANALYSIS comment's claim that they "sit above
      pkg/util in the import DAG").

  (b) Note that the plugin/** deny list now includes
      pkg/kubeapiserver to match the round's documented loaded-by
      relationship and to mirror the every-other-leaf pattern.

  (c) Note that the pkg/proxy/util fence now includes kubemark,
      metaproxier, and runner.

  (d) Note that the pkg/kubelet/container fence's three-entry
      deny list is the documented architectural intent and that
      Go's cycle detection enforces the rest (or, alternatively,
      that the rule has been extended to enumerate all consumers).

Until [F2-F5] are applied, add a TODO comment at the top of the
SCOPE OF ANALYSIS block:

```yaml
# TODO (review #8 [F2] / [F3] / [F4] / [F5]):
#   - util / apis / api deny lists currently omit the round-7 [F4]
#     cross-cutting leaves (auth, capabilities, certauthorization,
#     client, cluster/ports, features, fieldpath, routes,
#     windows/service) plus plugin/** (util-only). The round-7 [F4]
#     SCOPE OF ANALYSIS placement asserts these sit ABOVE util in
#     the import DAG; the deny lists should reflect that.
#   - plugin/** deny list omits pkg/kubeapiserver / pkg/kubeapiserver/**
#     despite the rule's own description naming kubeapiserver/admission
#     as the loader. Every other leaf rule denies kubeapiserver.
#   - pkg/proxy/util fence omits proxy implementations: kubemark,
#     metaproxier, runner (and possibly conntrack, healthcheck,
#     metrics — verify post-[F1]).
#   - pkg/kubelet/container fence lists 3 of ~30 above-container
#     consumers; either extend or document that Go's cycle detection
#     enforces the rest.
```

After the fixes are applied, strip the TODO and update the prose to
match the new shape.
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

Round 8 is the cleanest YAML of the eight-round series. The round-7
[F3]+[F5] fan-out collapse trims 19 redundant rules, brings
`pkg/api/testing` into scope as a documented expected-false-positive
source, and reduces the future maintenance cost of the YAML. The
round-7 [F4] addition of nine cross-cutting leaf-isolation rules
closes a multi-round gap where ten leaf packages were unconstrained
against component back-imports. The OBJECT-side bare-path mechanical
sweep from round 6 [F2] is still well-executed across all 69
dependency rules.

**But four structural gaps remain:**

1. **The OBJECT-side counterpart of round-7 [F4] was not propagated
   to the util / apis / api deny lists.** The new nine cross-cutting
   leaves (pkg/auth/**, pkg/routes, pkg/windows/service, etc.) are
   present as SUBJECTS but missing as OBJECTS. Plus plugin/** is
   absent from the util-layer deny list (it's present in apis / api).
   Per the YAML's own placement claim ("a leaf helper above pkg/util
   but below the component layers"), util / apis / api must not
   back-import these — the same way they must not back-import the
   leaf-component subsystems already in the deny list.

2. **The plugin/** rule omits pkg/kubeapiserver from its deny list,**
   alone among the ten leaf rules. The rule's own description states
   plugins are "loaded by kubeapiserver/admission" — making the
   inverse direction a circular import. Every other leaf rule
   correctly denies kubeapiserver. The single-rule asymmetry is a
   trivial fix.

3. **pkg/proxy/util/** intra-layer fence covers only four of seven
   proxy implementation packages.** The rule's stated intent
   ("util remains implementation-agnostic") is partially enforced;
   pkg/proxy/kubemark, pkg/proxy/metaproxier, and pkg/proxy/runner
   are unambiguous backend / orchestrator packages that the rule
   should also forbid.

4. **pkg/kubelet/container/** intra-layer fence enumerates 3 of ~30
   above-container kubelet sub-packages.** The author's own comment
   acknowledges Go's cycle detection covers the rest. Either extend
   the deny list or update the description to credit Go's cycle
   detection as the actual enforcement mechanism.

Plus the empirical 0% coverage gate from rounds 2-7 has not budged
for the **seventh consecutive round**. The round-8 YAML changes —
like those of rounds 2-7 — are unverified.

**Order of fixes** (highest leverage first):

1. **[F1]** — re-run `make arch-check PROJECT_ROOT=/path/to/upstream/k8s.io/kubernetes`
   and capture the report. Confirm `COVERAGE RATE NN% [PASS]` with
   NN ≥ 30%. The Makefile is correct; what's missing is the run.
   **This is the single highest-leverage fix in the entire 8-round
   series — it has been the same recommendation for seven consecutive
   rounds.**

2. **[F3]** (single-rule, two-line fix) — append
   `pkg/kubeapiserver` and `pkg/kubeapiserver/**` to the plugin/**
   deny list. Smallest fix, biggest semantic correction.

3. **[F2]** — add the round-7 [F4] cross-cutting leaves (plus
   plugin/** for util-layer) to the pkg/util/**, pkg/apis/**, and
   pkg/api/** deny lists. The util-layer rule was the canonical
   "complete" deny list referenced by round 7 [F2]; it is not
   actually complete after round 7 [F4]. ~27 added entries across
   3 rules.

4. **[F4]** — extend pkg/proxy/util/** to include kubemark,
   metaproxier, runner (and possibly the three borderline entries
   conntrack, healthcheck, metrics depending on empirical findings).

5. **[F5]** — choose between extending pkg/kubelet/container/** to
   enumerate all above-container consumers or updating the
   description to credit Go's cycle detection. Recommendation: the
   description fix, since the existing three are sufficient as
   architectural intent and Go provides the actual enforcement.

6. **[F7]** — add `--exclude '*_test.go'` to the Makefile's arch-go
   invocation, OR document test-file analysis as accepted (with
   per-directory exception carve-outs) in the SCOPE OF ANALYSIS
   block.

7. **[F6]** — empirically verify the function-complexity caps fire
   (post-[F1]). Tighten if they don't bite.

8. **[F8]** — update the description and SCOPE OF ANALYSIS comment
   to match the new rule shape after [F2-F5] are applied.

Re-run `make arch-check PROJECT_ROOT=/path/to/kubernetes` and confirm:

- `COVERAGE RATE NN% [PASS]` with NN ≥ 30%.
- The rule-by-rule report contains [FAIL] lines for the [F2] / [F3]
  acceptance tests:
  - Add `import "k8s.io/kubernetes/pkg/auth/nodeidentifier"` to
    a file in pkg/util/iptables → expect [FAIL] for the
    pkg/util/** rule citing pkg/auth/nodeidentifier.
  - Add `import "k8s.io/kubernetes/pkg/routes"` to a file in
    pkg/apis/core/validation → expect [FAIL] for the pkg/apis/**
    rule citing pkg/routes.
  - Add `import "k8s.io/kubernetes/pkg/kubeapiserver/admission"`
    to a file in plugin/pkg/auth → expect [FAIL] for the plugin/**
    rule citing pkg/kubeapiserver/admission.
  - Add `import "k8s.io/kubernetes/pkg/proxy/kubemark"` to a file
    in pkg/proxy/util → expect [FAIL] for the pkg/proxy/util/**
    rule citing pkg/proxy/kubemark.
- The function rules surface ≥ 1 violation in `pkg/apis/core/validation`
  (predicted: ValidatePodSpec) and `pkg/util/iptables` (predicted:
  (*runner).restoreInternal).
- The 19 naming rules (controller `Interface`-implementer Controller
  suffix; per-kubelet-sub-pkg Manager suffix; pkg/proxy/** Proxier
  suffix) surface ≥ 1 struct each.

Until those four empirical conditions are met, this report should
not be a CI gate; it is a cosmetic banner. Round 8 is the most
structurally sound of the eight-round series — the round-7 [F3]+[F5]
fan-out collapse and round-7 [F4] cross-cutting leaf rules are the
highest-quality YAML edits since round 1 — but the OBJECT-side
counterpart of [F4] was not propagated to the lower-layer deny
lists, plugin/** has the kubeapiserver asymmetry, the two intra-layer
fences are narrow, and the empirical state is unchanged for the
seventh consecutive round.

---

**End of Review #8.**
