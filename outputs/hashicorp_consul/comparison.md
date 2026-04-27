# In-Depth Architectural Enforcement Comparison: `hashicorp_consul`

**Models compared:** **`sonnet-4-6`** vs **`gemini3-flash`**
**Ground truth:** `inputs/go/1_hashicorp_consul.jpg` (Consul control-plane / data-plane runtime topology diagram)
**Build state:** Both suites achieve `0` architectural violations against the Consul codebase.

---

## 1. Executive Summary

**`sonnet-4-6` is the superior suite.** It produces a five-layer model that distinguishes the **Server / Consensus** tier (`agent/consul/**`) from the rest of the agent — a distinction the diagram makes explicit (the boxed *CONSUL SERVERS* leader/follower cluster sits above *CONSUL CLIENT*) — and goes further than `gemini3-flash` by encoding intra-server acyclicity (state, FSM, discoverychain) and seven targeted naming conventions (Store, Backend, Resolver, Provider, Reconciler×2, Limiter×2). `gemini3-flash` produces a flatter, more uniform six-layer model that collapses the server into a single `agent/**` glob and offers only three naming conventions, two of which target the same `Backend` interface. The decisive factor is **architectural fidelity over methodological tidiness**: `sonnet-4-6` captures *what Consul actually is* (a layered control plane with a Raft-backed server core), whereas `gemini3-flash` captures *what is easiest to enforce uniformly across all production trees*.

---

## 2. Architectural Layer Model Analysis

### 2.1 Layer count and topology

| Layer model | `sonnet-4-6` | `gemini3-flash` |
|---|---|---|
| Number of layers | **5** | **6** |
| Server / Consensus carved out as a distinct layer | **Yes** (Layer 4) | **No** (collapsed into Layer 4 *Agent*) |
| Foundation member peer imports | Allowed; documented | Allowed; documented |
| Internal-Framework peer-DAG ban | One targeted ban (`internal/multicluster ↛ internal/controller`) | None |
| Number of Layer-3 (agent) leaf rules | ~30 explicit + ~30 root-mirrors | 1 (`agent/**` + root mirror) |

`sonnet-4-6` declares its band hierarchy in the YAML header:

```7:46:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
#   Layer 1 - Foundation / Shared Primitives (no internal deps)
# ...
#   Layer 2 - Internal Framework  (must not import Layers 3-5)
# ...
#   Layer 3 - Agent / Control Plane  (depends on Layers 1-2)
# ...
#   Layer 4 - Server / Consensus  (depends on Layers 1-3)
#     Packages: agent/consul/**
# ...
#   Layer 5 - API / CLI / Connect  (depends on Layers 1-4)
#     Packages: api/**, command/**, connect/**, troubleshoot/**
```

`gemini3-flash` declares six layers in its header but the deeper structure is flatter:

```9:54:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
# LAYER HIERARCHY (Bottom to Top):
# -------------------------------
# 1. Foundation (Base Utilities): ...
# 2. Data Plane / Protos: ...
# 3. Domain Core (Internal): ...
#    Cross-cutting peers at this layer also include:
#    - github.com/hashicorp/consul/connect, connect/**
#    - github.com/hashicorp/consul/envoyextensions/**
#    - github.com/hashicorp/consul/troubleshoot/**
# 4. Control Plane (Agent): The core Consul agent daemon logic.
#    - github.com/hashicorp/consul/agent/**
#    Note: agent/** is split internally between:
#      - Server-side: agent/consul/** (Raft FSM, state store, server RPC).
#      - Client-side: every other agent/<leaf>/ (DNS, xDS, proxycfg, etc.).
# 5. Interface (API): ...
# 6. Consumer (Command): ...
```

The two preambles describe the same client/server split, but only `sonnet-4-6` *enforces* it as a distinct layer with its own outbound rules:

```1626:1647:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  # ---------------------------------------------------------------------------
  # Layer 4 - Server / Consensus: must not import Layer 5
  # ---------------------------------------------------------------------------
  - description: >
      The agent/consul server layer must not import command, api, connect, or
      troubleshoot packages. ...
    package: "github.com/hashicorp/consul/agent/consul/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

`gemini3-flash` declines to give `agent/consul` its own outbound rules; instead, it uses a single `agent/**` glob plus an `agent` root mirror that bans imports of `agent/consul`:

```630:647:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: >
      Non-server agent code (every agent/<leaf>) must not depend on the
      agent/consul/** server subtree (Raft FSM, state store, server RPC).
      agent/consul/** as an origin is matched by this glob but trivially
      complies because a package never imports itself.
    package: "github.com/hashicorp/consul/agent/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul"
        - "github.com/hashicorp/consul/agent/consul/**"
```

The relied-upon "a package never depends on itself" semantic is correct, but the consequence is that `agent/consul` itself receives **no outbound layer-leaf isolation** in `gemini3-flash`. Nothing in that file forbids `agent/consul/leader.go` from importing `command/**` or `api/**`.

### 2.2 Heterogeneous root packages

Both models recognise that arch-go's `**` glob does not match a directory's root import path. `sonnet-4-6` adds an explicit root mirror for nearly every Layer-3 single-directory leaf:

```1265:1297:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      agent/ae root mirror (pair with agent/ae/**).
    package: "github.com/hashicorp/consul/agent/ae"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  - description: >
      agent/auto-config root mirror (pair with agent/auto-config/**).
    package: "github.com/hashicorp/consul/agent/auto-config"
    ...
```

`gemini3-flash` uniformly adds *one* root mirror per top-level tree (e.g. `agent`, `api`, `lib`, `acl`, `command`) and relies on the parent glob to cover all sub-roots:

```609:616:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: Agent root package mirror.
    package: "github.com/hashicorp/consul/agent"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/api"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command"
        - "github.com/hashicorp/consul/command/**"
```

Both approaches are defensible. `sonnet-4-6` documents its trade-off explicitly: 26 paired `/**` rules are "forward-compatibility scaffolding" (header lines 1244–1264) — they currently match zero packages but will fire automatically the day a child is added under, e.g., `agent/ae/`. `gemini3-flash` consciously accepts that each new agent leaf will be picked up by the broad `agent/**` glob without requiring any rule update — at the cost of less per-leaf visibility in the YAML.

### 2.3 Build-only and third-party isolation

`sonnet-4-6` enumerates a long *EXCLUDED / SPECIAL-SCOPE PACKAGES* block (header lines 47–73) covering generated mocks (`grpcmocks/**`), `protoc-gen-*` binaries, `internal/tools/**`, conformance suites, and per-subtree test helpers. It then translates several into explicit outbound bans:

```446:457:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      grpcmocks packages must not import runtime product layers; mocks are build
      and test artefacts only.
    package: "github.com/hashicorp/consul/grpcmocks/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

`gemini3-flash` covers the same surface with an even broader **harness defense-in-depth** block: every production layer (agent, api, internal, command, connect, envoyextensions, troubleshoot, lib, sdk, proto, proto-public, acl, grpcmocks) is given an explicit deny-list against `testing/deployer/**`, `test-integ/**`, `testrpc`, `tools/**`, `internal/testing/**`, `internal/tools/**`, plus the **in-tree** harness packages (`agent/grpc-external/testutils`, `agent/grpc-middleware/testutil`, `agent/xds/testcommon`, `agent/xds/validateupstream-test`, `agent/mock`):

```759:782:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  # ----- Agent (Layer 4) -----
  - description: >
      Agent subtree must not depend on integration / build / test harness
      trees, including the in-tree harness packages that live under agent/**.
      The harness depends on production, never the reverse.
    package: "github.com/hashicorp/consul/agent/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/testing/deployer"
        - "github.com/hashicorp/consul/testing/deployer/**"
        - "github.com/hashicorp/consul/test-integ"
        - "github.com/hashicorp/consul/test-integ/**"
        - "github.com/hashicorp/consul/testrpc"
        - "github.com/hashicorp/consul/tools"
        - "github.com/hashicorp/consul/tools/**"
        - "github.com/hashicorp/consul/internal/testing/**"
        - "github.com/hashicorp/consul/internal/tools/**"
        - "github.com/hashicorp/consul/agent/grpc-external/testutils"
        - "github.com/hashicorp/consul/agent/grpc-middleware/testutil"
        - "github.com/hashicorp/consul/agent/xds/testcommon"
        - "github.com/hashicorp/consul/agent/xds/validateupstream-test"
        - "github.com/hashicorp/consul/agent/mock"
```

This is a **genuine `gemini3-flash` advantage**: `sonnet-4-6` only encodes outbound bans on the *harness* (e.g. `test-integ/** ↛ agent/consul/**`); it does not stop production code from accidentally importing harness trees. `gemini3-flash` enforces the bidirection.

### 2.4 Layer-model verdict

`sonnet-4-6` wins on **architectural granularity** (separate Server / Consensus tier, partial intra-server DAG, per-leaf root mirrors). `gemini3-flash` wins on **harness isolation** and **layer-template uniformity**. On balance, `sonnet-4-6` produces the more accurate map of the codebase; `gemini3-flash` produces the more uniform map of the build artefact universe. The diagram is *about the codebase*, not about the build artefacts.

---

## 3. Alignment with Ground Truth (Primary Dimension)

The architecture documentation (`1_hashicorp_consul.jpg`) is a runtime/deployment diagram — it shows *Consul Servers* (Leader + Followers) above *Consul Client*, with *Proxy* mediating between *App A/B* and the rest of the system, decomposed into a **Control Plane** band (top) and a **Data Plane** band (bottom). The diagram does **not** specify a code-layer hierarchy directly; both models had to *infer* a package-level layering consistent with the diagram's roles. Below, each architectural intent is examined and each model's encoding cited with concrete YAML snippets.

### 3.1 Intent: *Consul Servers* form a distinct consensus/storage tier above the agent client

The diagram explicitly boxes the leader+followers as **CONSUL SERVERS**, distinct from **CONSUL CLIENT**. In the codebase, this maps to `agent/consul/**` (Raft FSM, state store, server RPC) versus the rest of `agent/**`.

**`sonnet-4-6` encodes this as a separate Layer-4 outbound rule, AND as a Layer-3-to-Layer-4 inbound ban on every individual agent leaf.**

**Example Rule (Layer-4 outbound):**

```1626:1636:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      The agent/consul server layer must not import command, api, connect, or
      troubleshoot packages. The server is a library consumed by the agent
      entrypoint; it must remain independent of the CLI and public API client.
    package: "github.com/hashicorp/consul/agent/consul/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

**Example Rule (Layer-3 → Layer-4 inbound ban for one of ~30 agent leaves):**

```782:794:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      agent/cache and agent/cache-types must not import agent/proxycfg,
      agent/xds, the server, CLI, public API, or Connect client packages.
    package: "github.com/hashicorp/consul/agent/cache/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

**`gemini3-flash` encodes the same intent as a *single* `agent/**` glob with a same-tree carve-out**, relying on arch-go's "package never depends on itself" semantic to make `agent/consul/**` trivially comply as origin:

**Example Rule:**

```630:647:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: >
      Non-server agent code (every agent/<leaf>) must not depend on the
      agent/consul/** server subtree (Raft FSM, state store, server RPC).
      agent/consul/** as an origin is matched by this glob but trivially
      complies because a package never imports itself.
    package: "github.com/hashicorp/consul/agent/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul"
        - "github.com/hashicorp/consul/agent/consul/**"

  - description: >
      Same as above, for the agent root package (`agent/agent.go` etc.).
    package: "github.com/hashicorp/consul/agent"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul"
        - "github.com/hashicorp/consul/agent/consul/**"
```

**Assessment:** `gemini3-flash` correctly enforces the *client ↛ server* direction but does **not** enforce the *server ↛ Layer-5* direction at all — there is no rule in the file that prevents `agent/consul/leader.go` from importing `command/**` or `api/**`. `sonnet-4-6` enforces both directions. **`sonnet-4-6` captures the diagram's intent more faithfully.**

### 3.2 Intent: server-internal acyclicity (state ↔ FSM ↔ discovery chain)

The diagram labels the server cluster as a Raft consensus group; this *implies* — but does not state — that internal slices of the server (state store, Raft FSM, discovery chain) must not form import cycles among themselves.

**`sonnet-4-6` enforces a partial intra-server DAG via three explicit rules.**

**Example Rule:**

```1649:1691:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      agent/consul/state is the core state store and must not import any other
      agent/consul sub-package (except agent/consul/stream) to prevent circular
      dependencies inside the server layer.
    package: "github.com/hashicorp/consul/agent/consul/state"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/fsm"
        - "github.com/hashicorp/consul/agent/consul/discoverychain"
        - "github.com/hashicorp/consul/agent/consul/prepared_query"
        - "github.com/hashicorp/consul/agent/consul/gateways"
        - "github.com/hashicorp/consul/agent/consul/usagemetrics"
        - "github.com/hashicorp/consul/agent/consul/wanfed"
        - "github.com/hashicorp/consul/agent/consul/reporting"

  - description: >
      agent/consul/fsm (Raft finite-state machine) must not import these other
      agent/consul sub-packages ...
    package: "github.com/hashicorp/consul/agent/consul/fsm"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/discoverychain"
        - ...
        - "github.com/hashicorp/consul/agent/consul/reporting"

  - description: >
      agent/consul/discoverychain computes service routing graphs. It must not
      import the state store directly; it receives data via function arguments
      to keep it testable in isolation.
    package: "github.com/hashicorp/consul/agent/consul/discoverychain"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/state"
        - "github.com/hashicorp/consul/agent/consul/fsm"
```

**`gemini3-flash` does not encode any intra-server DAG rule.** A search of `gemini3-flash/arch-go.yml` finds no rule whose `package:` field is scoped to `agent/consul/state`, `agent/consul/fsm`, or `agent/consul/discoverychain`. The closest analogue is the storage-pluggability ban it does encode at the *framework* level:

**Example Rule (storage pluggability, NOT intra-server):**

```508:533:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: >
      Pluggability: the Raft storage implementation must not depend on the
      in-memory implementation. (Symmetric inmem ↛ raft enforced separately.)
    package: "github.com/hashicorp/consul/internal/storage/raft/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal/storage/inmem"
        - "github.com/hashicorp/consul/internal/storage/inmem/**"

  - description: >
      Pluggability: the in-memory storage implementation must not depend on
      the Raft implementation (symmetric to the existing raft → inmem rule).
    package: "github.com/hashicorp/consul/internal/storage/inmem/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal/storage/raft"
        - "github.com/hashicorp/consul/internal/storage/raft/**"
```

**Assessment:** `gemini3-flash`'s symmetric `raft ↔ inmem` ban is a *legitimate inferred* constraint — the documentation does not require it, but the inference is plausible (pluggable backends should not know about each other). `sonnet-4-6`'s intra-server DAG rules are an *equally plausible inference*, and they are the rules more directly implied by the diagram (Raft-backed server state machine implies state/FSM acyclicity). **Both models perform inference. `sonnet-4-6`'s inferences are tied to what the diagram *shows*; `gemini3-flash`'s inferences are tied to what the codebase *contains*.** Both are valid, but the diagram is the stated ground truth.

### 3.3 Intent: API/CLI/Connect leaves are public boundaries

Both models recognise `api/**`, `command/**`, `connect/**`, and `troubleshoot/**` as the outermost surface, but they handle the asymmetry between `command/**` (a CLI binary that legitimately embeds the agent) and the other three (pure SDKs/clients) differently.

**`sonnet-4-6` encodes asymmetric Layer-5 isolation explicitly.**

**Example Rule (`api/**` strict leaf):**

```1695:1708:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      The api package is the public HTTP client library for Consul and must not
      import any internal agent, server, or framework packages. ...
    package: "github.com/hashicorp/consul/api/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

**Example Rule (`command/**` partially permissive — `agent/**` allowed for entrypoints):**

```1723:1739:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      CLI command packages may import api, agent (entrypoints embed the agent),
      and troubleshoot/** libraries (e.g. command/troubleshoot wraps the
      troubleshoot SDK). They must NOT bypass the public boundary into the
      server, internal framework primitives, or envoy extension primitives:
      agent/consul/** is server-internal; internal/** is the Layer-2
      framework; envoyextensions/** is Layer-2 Envoy primitives. ...
    package: "github.com/hashicorp/consul/command/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/consul/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
```

**`gemini3-flash` treats `command/**` only as a "must-not-import-internal/**" rule** — it does not separately ban `agent/consul/**` or `envoyextensions/**`:

**Example Rule:**

```709:724:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: >
      CLI Command implementations should only interact with the system via
      the Agent or API layers, not internal/** or low-level data-plane
      primitives.
    package: "github.com/hashicorp/consul/command/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal"
        - "github.com/hashicorp/consul/internal/**"

  - description: command root mirror — must not depend on internal/**.
    package: "github.com/hashicorp/consul/command"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/internal"
        - "github.com/hashicorp/consul/internal/**"
```

**Assessment:** `sonnet-4-6`'s Layer-5 model is the more nuanced encoding: it acknowledges that the CLI must reach the agent (true: `command/agent/agent.go` instantiates `agent.Agent`), while still preventing the CLI from punching through to server-internal `agent/consul/**` or to Layer-2 envoy primitives. `gemini3-flash`'s rule does not stop a CLI command from importing `agent/consul/state` or `envoyextensions/extensioncommon` — both legitimate architectural concerns the diagram implies should be off-limits to a leaf binary.

### 3.4 Intent: Foundation libraries are pure primitives

Both models correctly designate `lib/**`, `sdk/**`, `types`, `version/**`, `ipaddr`, `sentinel`, `service_os`, `snapshot`, `tlsutil`, `logging`, and `acl/**` as Foundation. Where they differ is in the **completeness** of the Foundation deny-list template.

**`gemini3-flash` standardises a 12-target deny-list and applies it uniformly to every Foundation member**, including both root and `/**` forms:

**Example Rule:**

```143:177:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: >
      Foundation lib subtree must not depend on higher-level layers
      (agent, api, internal, command, connect, envoyextensions, troubleshoot).
    package: "github.com/hashicorp/consul/lib/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent"
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/internal"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/command"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/connect"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"

  - description: Foundation lib root package (mirror of lib/**).
    package: "github.com/hashicorp/consul/lib"
    shouldNotDependsOn:
      internal:
        ...
```

**`sonnet-4-6` uses a 7-target deny-list** (without explicit `agent`, `api`, `command`, `connect`, `internal` *roots*, relying on the `/**` glob alone):

**Example Rule:**

```155:169:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      Foundation packages (lib, sdk, types, ipaddr, logging, acl, proto, tlsutil,
      sentinel, service_os, snapshot, version) are the lowest layer and must not
      import any other internal consul package. ...
    package: "github.com/hashicorp/consul/lib/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/internal/**"
        - "github.com/hashicorp/consul/envoyextensions/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

**Assessment:** `gemini3-flash`'s template is **strictly more correct** under arch-go's documented contract that `/**` may not match the directory root. `sonnet-4-6`'s 7-target deny-list misses the case where, e.g., `lib/x.go` imports `github.com/hashicorp/consul/agent` (the directory root, not `agent/x`). In practice, this is unlikely to be exploited in a healthy Consul codebase — and `sonnet-4-6` adds `lib`-root and `acl`-root mirrors to compensate at the **origin** side — but `gemini3-flash`'s deny-list is the more defensible template. **This is the cleanest gain `gemini3-flash` posts in this dimension.**

### 3.5 Intent: legitimate exceptions and architectural debt

Neither model uses `.because()`-style annotation chains (arch-go YAML has no such field). Both models use `description:` strings to articulate intent. Where each handles documented exceptions differently:

**`sonnet-4-6` carves a single targeted Layer-2 peer-import ban** for the one peer-DAG edge it considers high-risk:

```587:604:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      internal/multicluster orchestrates resource and storage backends; to keep
      the multicluster control plane testable in isolation it must not import
      product layers (agent, command, api, connect, troubleshoot) AND must not
      import the controller framework directly. Multicluster is invoked via
      framework callbacks; a direct import would create the most likely
      Layer-2 peer cycle in this codebase. This is the single Layer-2 peer ban
      this file enforces; the rest of the Layer-2 peer DAG is documented in
      the header but intentionally not automated.
    package: "github.com/hashicorp/consul/internal/multicluster"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/connect/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
        - "github.com/hashicorp/consul/internal/controller/**"
```

**`sonnet-4-6` also carves an explicit relaxation for `internal/storage/conformance`** (a cross-cutting test contract suite):

```524:537:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      internal/storage/conformance is a cross-cutting test contract suite (see
      "EXCLUDED / SPECIAL-SCOPE PACKAGES" in the header). It is intentionally
      out of scope for full Layer-2 product-layer guards because conformance
      suites legitimately need to import test fixtures and helpers across
      packages, but it must not import the CLI (command/**) or diagnostics
      tooling (troubleshoot/**) so the storage contract stays decoupled from
      operator-facing surfaces.
    package: "github.com/hashicorp/consul/internal/storage/conformance"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/command/**"
        - "github.com/hashicorp/consul/troubleshoot/**"
```

**`gemini3-flash` does not encode any analogous targeted Layer-2 peer ban or conformance carve-out.** It applies the broad `internal/**` outbound rule uniformly:

**Example Rule:**

```460:471:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
  - description: >
      Domain Core (internal/**) must not depend on the higher-level
      Agent, API, or Command layers.
    package: "github.com/hashicorp/consul/internal/**"
    shouldNotDependsOn:
      internal:
        - "github.com/hashicorp/consul/agent"
        - "github.com/hashicorp/consul/agent/**"
        - "github.com/hashicorp/consul/api"
        - "github.com/hashicorp/consul/api/**"
        - "github.com/hashicorp/consul/command"
        - "github.com/hashicorp/consul/command/**"
```

The blanket `internal/**` rule is simpler but it does *not* prevent `internal/multicluster → internal/controller` (the cycle `sonnet-4-6` identifies as most likely), and it equally constrains the conformance suite that legitimately needs broader fixtures access.

### 3.6 Intent: naming conventions for documented abstractions

The Consul codebase has documented abstractions that follow conventional name suffixes. Each model translates these to `interfaceImplementationNamingRule` entries. **`sonnet-4-6` covers seven distinct abstractions; `gemini3-flash` covers two**.

**`sonnet-4-6` naming rules:**

```1845:1903:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
namingRules:

  - description: >
      All types in agent/consul/state that represent a read/write handle to a
      named data collection must be suffixed with 'Store' ...
    package: "github.com/hashicorp/consul/agent/consul/state"
    interfaceImplementationNamingRule:
      structsThatImplement: "Store"
      shouldHaveSimpleNameEndingWith: "Store"

  - description: >
      Types inside internal/storage that implement the storage backend interface
      must be suffixed with 'Backend' ...
    package: "github.com/hashicorp/consul/internal/storage/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      Resolver types inside agent/grpc-internal/resolver that satisfy the
      standard Resolver contract must be suffixed with 'Resolver' ...
    package: "github.com/hashicorp/consul/agent/grpc-internal/resolver"
    interfaceImplementationNamingRule:
      structsThatImplement: "Resolver"
      shouldHaveSimpleNameEndingWith: "Resolver"

  - description: >
      CA provider implementations inside agent/connect/ca must be suffixed
      with 'Provider' ...
    package: "github.com/hashicorp/consul/agent/connect/ca"
    interfaceImplementationNamingRule:
      structsThatImplement: "Provider"
      shouldHaveSimpleNameEndingWith: "Provider"

  - description: >
      Controller reconciler implementations inside internal/controller packages
      must be suffixed with 'Reconciler' ...
    package: "github.com/hashicorp/consul/internal/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"

  - description: >
      Server-side controller reconciler implementations in agent/consul/controller ...
    package: "github.com/hashicorp/consul/agent/consul/controller"
    interfaceImplementationNamingRule:
      structsThatImplement: "Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"

  - description: >
      Rate limiter implementations in agent/consul/rate must be suffixed with
      'Limiter' ...
    package: "github.com/hashicorp/consul/agent/consul/rate"
    interfaceImplementationNamingRule:
      structsThatImplement: "RequestLimiter"
      shouldHaveSimpleNameEndingWith: "Limiter"
```

Plus an `agent/consul/multilimiter` rule using a wildcard pattern:

```1914:1926:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
  - description: >
      Rate limiter implementations in agent/consul/multilimiter must be
      suffixed with 'Limiter'. ...
    package: "github.com/hashicorp/consul/agent/consul/multilimiter"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Limiter"
      shouldHaveSimpleNameEndingWith: "Limiter"
```

**`gemini3-flash` naming rules:**

```1149:1177:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
namingRules:
  - description: >
      [Redundant safety net] All structs in internal/storage (root) that
      implement the Backend interface should be named *Backend. ...
    package: "github.com/hashicorp/consul/internal/storage"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      All structs in internal/storage/** that implement the Backend interface
      should be named *Backend ...
    package: "github.com/hashicorp/consul/internal/storage/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Backend"
      shouldHaveSimpleNameEndingWith: "Backend"

  - description: >
      All structs in internal/controller/** that implement the Reconciler
      interface should be named *Reconciler.
    package: "github.com/hashicorp/consul/internal/controller/**"
    interfaceImplementationNamingRule:
      structsThatImplement: "*Reconciler"
      shouldHaveSimpleNameEndingWith: "Reconciler"
```

**Assessment:** `sonnet-4-6` enforces *seven* distinct naming conventions across the codebase (Store, Backend, Resolver, Provider, two Reconciler scopes, two Limiter scopes); `gemini3-flash` enforces *one and a half* (Backend twice — once vacuously at the root, plus Reconciler in `internal/controller/**`). The CA provider, gRPC resolver, state store, server-side reconciler, and rate-limiter conventions are not encoded by `gemini3-flash`. This is a substantial coverage gap on the *naming* axis.

### 3.7 Intent: structural constraints on generated code

Both models apply contents rules to `proto/**`, `proto-public/**`, and `grpcmocks/**`. They diverge on the predicate:

**`sonnet-4-6` uses `shouldOnlyContainStructs: true`**:

```1811:1840:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
contentsRules:

  - description: >
      Generated private protobuf packages should contain only struct
      definitions (and their generated method sets). ...
    package: "github.com/hashicorp/consul/proto/**"
    shouldOnlyContainStructs: true

  - description: >
      Public generated protobuf packages mirror the proto/** policy: structs
      and their generated method sets only. ...
    package: "github.com/hashicorp/consul/proto-public/**"
    shouldOnlyContainStructs: true

  - description: >
      grpcmocks packages contain mock structs and their generated methods only ...
    package: "github.com/hashicorp/consul/grpcmocks/**"
    shouldOnlyContainStructs: true
```

**`gemini3-flash` uses `shouldNotContainInterfaces: true` only** — it documents a deliberate *removal* of `shouldNotContainFunctions: true` because every `*.pb.go` declares `init()` and `RegisterXxxServer` as free functions:

```1112:1133:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
contentsRules:
  - description: >
      Generated proto packages should not declare hand-written interfaces.
      (Generated free functions like init() and gRPC registration helpers
      are out of scope for arch-go and are enforced separately by the
      generator's `Code generated by ...` header.)
    package: "github.com/hashicorp/consul/proto/**"
    shouldNotContainInterfaces: true

  - description: >
      Generated public proto packages should not declare hand-written
      interfaces. ...
    package: "github.com/hashicorp/consul/proto-public/**"
    shouldNotContainInterfaces: true

  - description: >
      Mock packages should not declare hand-written interfaces. ...
    package: "github.com/hashicorp/consul/grpcmocks/**"
    shouldNotContainInterfaces: true
```

**Assessment:** `gemini3-flash` is **technically more correct** here. `sonnet-4-6`'s `shouldOnlyContainStructs: true` is brittle against generated `init()` and `RegisterXxxServer` functions — `sonnet-4-6` even acknowledges this in its YAML header (`BRITTLENESS: protoc / protoc-gen-go upgrades may emit helpers that arch-go reclassifies as functions, flipping CI to FAIL with no architectural change.`) but elects to keep the rule. `gemini3-flash` makes the conservative call. **Point to `gemini3-flash`** on this single rule.

### 3.8 Intent: function-shape constraints

**`sonnet-4-6` constrains state/discoverychain to ≤2 return values and lib / internal/storage to ≤3** (with explicit root mirrors):

```1933:1974:outputs/hashicorp_consul/sonnet-4-6/arch-go.yml
functionsRules:

  - description: >
      Functions in agent/consul/state (the core state store) must return at most
      two values. ...
    package: "github.com/hashicorp/consul/agent/consul/state"
    maxReturnValues: 2

  - description: >
      Functions in agent/consul/discoverychain must return at most two values. ...
    package: "github.com/hashicorp/consul/agent/consul/discoverychain"
    maxReturnValues: 2

  - description: >
      lib utility functions should prefer small signatures; up to three return
      values are allowed for idiomatic (T, U, error) or similar helpers.
    package: "github.com/hashicorp/consul/lib/**"
    maxReturnValues: 3

  - description: >
      lib root package return-signature guard (pair with lib/**). ...
    package: "github.com/hashicorp/consul/lib"
    maxReturnValues: 3

  - description: >
      internal/storage backend functions should prefer small signatures; up to
      three return values are allowed where (value, auxiliary, error) is clearer
      than a result struct.
    package: "github.com/hashicorp/consul/internal/storage/**"
    maxReturnValues: 3

  - description: >
      internal/storage root package return-signature guard ...
    package: "github.com/hashicorp/consul/internal/storage"
    maxReturnValues: 3
```

**`gemini3-flash` uses a different heuristic — `maxLines: 80` + `maxParameters: 6` for `internal/**`, and `maxReturnValues: 2` for `lib/**`**:

```1186:1198:outputs/hashicorp_consul/gemini3-flash/arch-go.yml
functionsRules:
  - description: >
      Internal-layer functions should be small and focused. (arch-go does not
      support cyclomatic complexity; pair with gocyclo for the actual bound.)
    package: "github.com/hashicorp/consul/internal/**"
    maxLines: 80
    maxParameters: 6

  - description: >
      Foundation utility functions in the lib package must return at most
      two values (usually a result and an error) to maintain simplicity.
    package: "github.com/hashicorp/consul/lib/**"
    maxReturnValues: 2
```

**Assessment:** `sonnet-4-6` targets the function-shape constraint at the *specific subsystems* the documentation implies (state store, discovery chain — the same subsystems that get the intra-server DAG rule). `gemini3-flash` applies blanket `maxLines: 80 / maxParameters: 6` to `internal/**` — an arbitrary heuristic with no documentation support. The Consul documentation does not specify line counts or parameter counts; this is `gemini3-flash` *inventing* a constraint to fill the slot. **Point to `sonnet-4-6`** for documentation-grounded function rules.

### 3.9 Rules present in only one suite

**Present in `sonnet-4-6`, absent in `gemini3-flash`:**

- `internal/multicluster ↛ internal/controller/**` (single targeted Layer-2 peer ban). *Verdict: defensible inference.*
- Intra-server DAG (state/FSM/discoverychain). *Verdict: defensible inference; closely tracks the diagram's Raft-FSM-state-store decomposition.*
- Per-agent-leaf root mirrors for ~26 single-directory leaves. *Verdict: forward-compatibility scaffolding; explicitly documented.*
- Naming rules for Store, Resolver, Provider, server-side Reconciler, and two Limiter scopes. *Verdict: real coverage gap in `gemini3-flash`.*
- `internal/storage/conformance` carve-out. *Verdict: real coverage gap in `gemini3-flash`.*
- `maxReturnValues: 2` on `agent/consul/state` and `agent/consul/discoverychain`. *Verdict: targeted at the subsystems the diagram implies.*

**Present in `gemini3-flash`, absent in `sonnet-4-6`:**

- Inverse storage-pluggability ban (`internal/storage/inmem ↛ internal/storage/raft`). *Verdict: legitimate inference.*
- Production-layers ↛ harness defense-in-depth (every layer banned from importing `testing/deployer/**`, `test-integ/**`, etc., **plus in-tree harness like `agent/grpc-external/testutils`**). *Verdict: real coverage gap in `sonnet-4-6`.*
- 12-target Foundation deny-list with explicit root + `/**` enumeration. *Verdict: stricter encoding of the same intent.*

### 3.10 Ground-truth alignment verdict

Counting only architecturally significant items: `sonnet-4-6` adds **6** documentation-grounded constraints `gemini3-flash` omits; `gemini3-flash` adds **3** documentation-grounded constraints `sonnet-4-6` omits. **`sonnet-4-6` wins this dimension**, but not by a landslide — `gemini3-flash`'s harness-isolation block is genuinely useful and `sonnet-4-6` should have it.

---

## 4. Methodological Analysis (Fix History)

### 4.1 Iteration count and pace

| Metric | `sonnet-4-6` | `gemini3-flash` |
|---|---|---|
| Number of iterations | **5** | **3** |
| Schema-level reversals | **0** | **2** (v1 ↔ v2 `structsThatImplement`; `shouldNotContainFunctions` added then removed) |
| Major rule consolidations | 0 | 1 (35 per-leaf rules collapsed to one `agent/**` glob in R3) |
| Major rule expansions | 0 | 1 (1 placeholder harness comment expanded to 11 explicit rules in R2) |

Iteration count alone is misleading: lower is *not* better when the iterations contain dramatic rewrites. `sonnet-4-6`'s five iterations show **incremental refinement** — each fix narrows or tightens a previously documented decision. `gemini3-flash`'s three iterations show **course-correction** — each fix reverses an earlier interpretation that turned out wrong.

### 4.2 ArchUnit DSL mastery

Both models made initial DSL mistakes; both caught them. The character of the mistakes differs:

**`gemini3-flash` Review #1 findings** (its own first-iteration recap):

> *"All three `contentsRules` were silent no-ops because the YAML used the non-existent nested-list shape (`shouldOnlyContain: [- interfaces: true, ...]` / `shouldNotContainAnyOf: [- ...]`) instead of arch-go's flat-boolean keys, and the `internal/resource/**` rule additionally stacked mutually exclusive `shouldOnly*` flags; `functionsRules` used the non-existent `maxComplexity` field for `internal/**`, dropping that constraint to zero ..."*

This is two distinct **API hallucinations** in a single first-pass file: a fictional nested-list shape and a fictional `maxComplexity` field.

**`sonnet-4-6` Review #5 findings** (its own fifth-iteration recap):

> *"All four `contentsRules` were vacuous because the YAML used arch-go's unrecognised `shouldNotContainAnyOf` / `shouldOnlyContain` nested-list shape instead of the documented flat boolean keys (`shouldNotContainInterfaces` / `shouldOnlyContainStructs`) ..."*

Same nested-list hallucination, but it took until iteration 5 for `sonnet-4-6` to identify and correct it. The fix arrived later but came in a single targeted iteration.

**`gemini3-flash` Review #3 findings** describe the most damaging DSL incident:

> *"The Round-2 migration of `structsThatImplement` to the v2 structured-map form (`{ internal: "*Backend" }` / `"*Reconciler"`) caused the installed arch-go binary (a v1.5.4 fork still selected by the Makefile's `command -v arch-go` short-circuit) to abort the entire YAML unmarshal with three 'cannot unmarshal !!map into string' errors at lines 1207/1216/1225, leaving every dependency, contents, naming, and function rule in the file unenforced (zero rules evaluated in CI) ..."*

This is a regression: the Round-2 fix **broke the entire test suite silently** until Round-3 discovered it. The build did not fail — every rule was being skipped. `sonnet-4-6`'s history contains no analogous "every rule silently skipped" incident.

### 4.3 Surgical correction vs brute-force passing

**`sonnet-4-6`'s fixes are predominantly surgical.** Examples from the fix history:

- *Review #4:* "added directory-root `dependenciesRules` mirrors for every single-directory Layer-3 agent leaf listed above, preserving `proxycfg`'s XDS ban and `structs`' neutral-shared-package guards" — added rules without removing.
- *Review #5:* "deleted the vacuous `agent/proxycfg-sources` and `agent/rpc` root mirrors (replaced with comment markers explaining why)" — removed rules that *cannot* fire because the paths do not exist as Go packages.
- *Review #5:* "switched the `agent/consul/multilimiter` naming rule to use the `*Limiter` wildcard pattern instead of a same-package interface lookup" — fixed a vacuous rule by *strengthening* it, not deleting it.

**`gemini3-flash`'s fixes mix surgical with broad-stroke**:

- *Review #1:* "added 35 per-leaf rules covering the documented intra-agent client/server split" — granular addition.
- *Review #3:* "consolidated the 35+ per-leaf intra-agent client/server rules into a single `agent/**` rule plus the `agent` root mirror" — wholesale deletion of the Round-1 work.
- *Review #2:* "rewrote the Makefile to drop the non-existent `arch-go check --config` invocation" — corrected an earlier hallucination.
- *Review #3:* "rewrote the Makefile `arch-check` target to (a) probe `arch-go --version` and reinstall from `github.com/arch-go/arch-go/v2@latest` whenever the on-PATH binary is not v2 (defeating the stale-v1 short-circuit)" — second Makefile rewrite to recover from a Round-2 install bug.

The **35-rules-then-collapse-to-1 cycle** between `gemini3-flash` Reviews #1 and #3 is the canonical "brute-force a passing state" red flag. A more disciplined approach would have used the broad glob from the start. To `gemini3-flash`'s credit, the eventual collapsed form is more maintainable than `sonnet-4-6`'s 60-rule explicit enumeration — but the journey there was not surgical.

### 4.4 Regression patterns

**`gemini3-flash` exhibits two clear regressions:**

1. *R2 → R3 schema regression:* `structsThatImplement` migrated to v2 map form, then reverted to v1 string form when the installed binary was v1.5.4. Caught **only** because every rule silently stopped firing.
2. *R2 → R3 contents regression:* `shouldNotContainFunctions: true` introduced in R1 was kept through R2, removed in R3 once the `init()` / `RegisterXxxServer` issue was understood.

**`sonnet-4-6`'s history shows no equivalent regressions.** The `contentsRules` schema fix in R5 was the first time those rules were corrected — they had been silently no-ops since R1, but they were not *broken* by an intermediate fix. Across all five iterations, no rule's enforcement strength oscillated.

### 4.5 Methodological verdict

`sonnet-4-6` demonstrates **incremental discipline**: each iteration adds or tightens, never reverses. `gemini3-flash` demonstrates **iterative correction**: it course-corrects effectively, but at the cost of two confirmed regressions, one of which silently disabled the entire suite for a full iteration. **Methodology favours `sonnet-4-6`.**

---

## 5. Final Scored Verdict

| Dimension | `sonnet-4-6` | `gemini3-flash` |
|---|---|---|
| Architectural Layer Model Accuracy | **5** /5 | 3 /5 |
| Ground Truth Alignment | **5** /5 | 3 /5 |
| ArchUnit DSL Competency | **4** /5 | 3 /5 |
| Violation Resolution Methodology | **4** /5 | 2 /5 |
| **Total** | **18 /20** | **11 /20** |

### Dimensional rationale

- **Architectural Layer Model Accuracy** — `sonnet-4-6` carves a separate Server / Consensus tier, encodes intra-server acyclicity, and provides per-leaf root mirrors. `gemini3-flash` collapses the server into `agent/**` and forfeits intra-server enforcement.
- **Ground Truth Alignment** — `sonnet-4-6` enforces six documentation-grounded constraints `gemini3-flash` omits (Store/Resolver/Provider naming, intra-server DAG, conformance carve-out, targeted Layer-2 peer ban). `gemini3-flash` enforces three `sonnet-4-6` omits (storage-pluggability symmetry, harness defense-in-depth, 12-target Foundation deny-list). Net advantage to `sonnet-4-6`.
- **ArchUnit DSL Competency** — Both models initially hallucinated the nested-list contents shape; `gemini3-flash` additionally hallucinated `maxComplexity` and the `arch-go check --config` Makefile incantation, and migrated `structsThatImplement` to a v2 form that broke the v1 binary. `sonnet-4-6`'s contents rule retains a known brittleness against `init()` functions but nowhere else exhibits API hallucination.
- **Violation Resolution Methodology** — `sonnet-4-6` shows incremental tightening across 5 iterations with no regressions. `gemini3-flash` shows two confirmed regressions (one of which silently disabled every rule for a full iteration) and one major rule-set rewrite that erased Round-1 work.

### Unequivocal Conclusion

**`sonnet-4-6` is the superior arch-go suite for `hashicorp_consul`.** It produces a more accurate map of Consul's documented architecture, encodes more of its implied invariants (intra-server acyclicity, per-subsystem naming conventions, asymmetric Layer-5 isolation), and arrived at its passing state through five surgical iterations rather than three iterations punctuated by reversals.

### Conditions favouring `gemini3-flash`

`gemini3-flash`'s suite is preferable when:

1. **Maintainability of new agent leaves matters more than per-leaf visibility.** A team that frequently adds new `agent/<leaf>` directories will not need to edit `gemini3-flash`'s YAML; it will need to edit `sonnet-4-6`'s.
2. **Supply-chain hygiene is the dominant concern.** `gemini3-flash`'s harness defense-in-depth block (every production layer banned from importing every harness tree, plus in-tree `agent/<leaf>/testutils` packages) is a real architectural asset `sonnet-4-6` does not match.
3. **Generator-output brittleness must be minimised.** `gemini3-flash`'s `shouldNotContainInterfaces: true` for proto/grpcmocks is more robust against `protoc` and `mockery` upgrades than `sonnet-4-6`'s `shouldOnlyContainStructs: true`.

In all other respects, **`sonnet-4-6` is the more faithful, more granular, and more disciplined enforcement of the Consul architecture documentation.**
