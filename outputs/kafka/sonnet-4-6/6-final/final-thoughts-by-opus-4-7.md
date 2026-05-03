# Final Thoughts - Apache Kafka ArchUnit Rules

Reviewer: opus-4-7
Subject: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java` (final passing version)
Iteration terminating the loop: analyze1 (`Results: 0 mapping error`, no patch required — converged green from the eight-round adversarial review series 3-review/review1..8)
Ground truth documents consulted: `inputs/java/7_apache_kafka.pdf` (the single supplied architecture document; describes Kafka Streams runtime semantics only) and `inputs/java/7_apache_kafka.txt` (top-level package listing)

---

## Section 1 — Verdict

The rule file, the imported `org.apache.kafka..` classpath, and my reading of the supplied Kafka Streams Architecture PDF have reached a fixed point: 33 of 33 `@ArchTest` rules pass with zero violations after eight rounds of adversarial review and one trivial analyze iteration. That is a *measurement*, not a *judgment*. It does not establish that the file matches "the architecture of Apache Kafka" in any sense an Apache Kafka committer would recognise — the supplied document does not describe Kafka's broader package layering at all (it documents Kafka Streams runtime), so every rule in the file is an inference from package naming rather than a quoted constraint. What follows is the calibration the test result alone cannot provide.

## Section 2 — What I am confident about

Vouchable claims, none requiring interpretation of the documentation:

1. The Maven Surefire run reports `Tests run: 33, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.811 s` against the imported `org.apache.kafka..` classpath, with `org.apache.kafka.test`, `org.apache.kafka.jmh`, and `org.apache.kafka.trogdor` excluded.
2. The `every_production_class_must_be_in_a_layer` guard rule passes, which means every scanned production class in `org.apache.kafka..` outside the three excluded sub-trees lives in a package that appears in `CORE_PACKAGES`, `STORAGE_PACKAGES`, `CONSENSUS_PACKAGES`, `SERVER_PACKAGES`, or `API_PACKAGES`. The five constants are derived programmatically into `ALL_LAYER_PACKAGES` via `Stream.flatMap(...)`, so layer membership and the guard cannot drift apart.
3. The `kafka_layered_architecture` rule defines five layers (Core, Storage, Consensus, Server, API) and asserts the strict directional partial order Core ← Storage ← Consensus ← Server ← API, with `consideringOnlyDependenciesInLayers()` and 22 `ignoreDependency(...)` carve-outs.
4. Each of the 22 carve-outs is annotated with an inline finding ID (MAP-03, MOD-01..10, MOD-05-WIDEN, FP-AUTH-01, REGR-06, REGR-08, REGR-09, TIERED-01, SHARE-01, OVR-01, OVR-02 — see `fix-history.md`) and an English `because(...)` rationale.
5. Every `because(...)` clause in the file begins with the string "Inferred from package naming" (an explicit truth-in-advertising disclaimer added in Round 1's REA-01 fix).
6. Eight `@ArchTest` rules carry the marker `FUTURE-PROOFING ONLY` in their `because(...)` clause, indicating they cannot fire today (their target packages are empty or their direction is currently unrealised) but will fire if the situation changes.
7. The file uses two custom ArchUnit primitives — `TrogdorAndJmhExclusion` (`ImportOption`) and an inline `ArchCondition<JavaClass>` for the every-class guard — both of which are necessary because the framework defaults are insufficient for this codebase (Round 1 SCOPE-01 and Round 3 LAY-NEW-03 respectively).
8. All Round-7 carve-outs (TIERED-01, SHARE-01, OVR-01-WIDEN) are confirmed silent in the final surefire — i.e., the dependencies they exempt are real, currently present, and would otherwise fire (the deliberate Round 6 → Round 7 narrow-then-fix cycle proved this).

## Section 3 — What I am NOT confident about

### 3.1 Documentation silences

The supplied PDF documents Kafka Streams runtime semantics — stream partitions, threading, state stores, fault tolerance — and contains **zero** cross-package prohibitions for `org.apache.kafka.*`. Every architectural choice the rule file makes is therefore a silence I filled in. Representative silences (non-exhaustive):

- *"Can `metadata` depend on `image`?"* — Doc silent. Rule allows (Round 2 REGR-02 corrected an earlier inversion based on KIP-500 reasoning, not on the PDF).
- *"Can `image.publisher` depend on `metadata.publisher`?"* — Doc silent. Rule forbids by an explicit `image_must_not_depend_on_metadata_publisher_internals` rule with `resideOutsideOfPackage("image.publisher..")`.
- *"Can `tools` depend on `connect`?"* — Doc silent. Rule forbids except for `tools.ConnectPluginPath*` and `tools.ManifestWorkspace*` (Round 1 FP-01 / Round 2 REGR-03 — the `connect-plugin-path` CLI exception is documented in KIP-787, but no KIP citation appears in the file).
- *"Can `metadata.authorizer` depend on `controller`?"* — Doc silent. Rule allows via FP-AUTH-01 because `AclMutator` and `ClusterMetadataAuthorizer` legitimately consume `controller.ControllerRequestContext`.
- *"Can `server.config` aggregate `ConfigDef` instances from `storage`, `network`, `raft`?"* — Doc silent. Rule allows via MOD-05 + MOD-05-WIDEN because `AbstractKafkaConfig.<clinit>` does it.
- *"Can `server.metrics` observe `image` / `metadata` / `controller` runtime state?"* — Doc silent. Rule allows via MOD-06.
- *"Can `server.log.remote.metadata.storage` use the Kafka client library?"* — Doc silent (the PDF doesn't mention KIP-405 tiered storage). Rule allows via TIERED-01 wholesale.
- *"Should the API layer be partitioned by independently-deployable module?"* — Doc silent. Rule does so (clients, connect, streams, tools, shell are all in API but mutually isolated by 14 `noClasses()` rules).
- *"Should `kafka.*` (legacy Scala) be in scope?"* — Doc silent. Rule excludes by `@AnalyzeClasses(packages = "org.apache.kafka")`.

### 3.2 Invented labels

Every layer name in the file is my (or the generator's) invention. The PDF uses none of them.

- **Core** — invented term covering 25 packages: `common`, `security`, `config`, `deferred`, `logger`, `queue`, `timeline`, plus 18 `server.X` sub-packages (common, util, metrics, fault, authorizer, immutable, config, record, storage.log, log.remote.storage, mutable, network, policy, purgatory, telemetry, log.remote (top-level), log.remote.quota, quota).
- **Storage** — invented; covers `storage..` and `snapshot..`.
- **Consensus** — invented; covers `raft..`, `metadata..`, `image..`.
- **Server** — Kafka uses the word "server" but the *boundary* I drew (10 specific packages: `server` top-level, `server.share`, `server.transaction`, `server.log.remote.metadata.storage`, `controller`, `network`, `server.controller`, `server.logger`, `server.partition`, `server.replica`) is invented. The split between "Server runtime" and "Core utilities under `server.*`" is the single biggest interpretive judgment in the entire file.
- **API** — invented term covering `clients`, `connect`, `streams`, `tools`, `shell`. Kafka's own documentation describes these as separate modules, not as a layer.

The directional partial order *Core ← Storage ← Consensus ← Server ← API* is also invented. KIP-500 implies a similar ordering for the KRaft path but does not state the layering as a five-tier architecture.

### 3.3 Inferred rules

All 33 `@ArchTest` rules are inference. In particular:

- **`kafka_layered_architecture`**: inferred from package names. No supplied document defines this five-layer model.
- **`every_production_class_must_be_in_a_layer`**: a meta-rule (not architectural per se); inferred to ensure the layer enumeration stays complete (Round 1 LAY-01).
- **The 14 intra-API isolation rules** (`streams_must_not_depend_on_connect`, etc.): inferred from "these are independently-deployable modules". The build files (`build.gradle` of `streams/`, `connect/`, `clients/`) do enforce this in practice, but no `because(...)` cites them.
- **The intra-Server isolation rules** (`controller_must_not_depend_on_coordinator`, `network_must_not_depend_on_*`): inferred. `coordinator..` is empty in the scanned classpath; `controller_must_not_depend_on_coordinator` is explicitly marked `FUTURE-PROOFING ONLY` after Round 6 VAC-NEW-02.
- **The intra-Consensus isolation rules** (`raft_must_not_depend_on_metadata`, `raft_must_not_depend_on_image`, `raft_must_not_depend_on_higher_layers`, `image_must_not_depend_on_metadata_publisher_internals`, `metadata_must_not_depend_on_server_runtime`): inferred from the role names. KIP-500 supports the directionality but is not cited.
- **The Core-isolation rules** (`common_must_not_depend_on_storage / consensus / server_runtime`, `storage_must_not_depend_on_consensus / server_layer / api_layer`): inferred from layering hygiene; no documentation citation.
- **The eight `FUTURE-PROOFING ONLY` rules**: inferred guards against future regressions. Defensible but not derived from any document.

### 3.4 Undocumented carve-outs

All 22 `ignoreDependency` clauses are my (or the generator's) reasoning, not the PDF's authority. Each has a `because(...)` rationale; none cites the PDF. Categorised:

- **Storage ↔ Metadata SPI / bootstrap**: MAP-03 ×2 (`storage → metadata.properties`, `storage → metadata.ConfigRepository`).
- **Broker-as-client / outbound RPC**: MOD-01 ×3 + OVR-01 narrowed (the 16-class allowlist) + MOD-01 inversions for `server.config → metadata`, `server.log.remote.storage → storage`, `raft → clients`, `snapshot → raft`, plus the entire TIERED-01 sub-package exemption (KIP-405 cited in the comment but not enforced as a citation).
- **Shared DTO / wire-protocol enums**: MOD-02 ×2 (`controller`, `image` → `clients.admin`), MOD-03 (`metadata → clients.admin`), MOD-04 (`security → clients.admin`), MOD-09 ×2 narrowed (`common → clients.admin` + the four named common.* classes), SHARE-01 (`server.share → clients.consumer{,.internals}` for KIP-932 enums).
- **Declarative-config aggregation**: MOD-05 + MOD-05-WIDEN ×3 (`server.config → storage / network / raft / server`).
- **Cross-layer observation / wiring**: MOD-06 (`server.metrics → image / metadata / controller`), MOD-07 (`server.util → metadata`), MOD-08 (`storage → server.log.remote.metadata.storage.generated`), MOD-10 (`server.log.remote.storage → server.log.remote.metadata.storage`), FP-AUTH-01 (`metadata.authorizer → controller`).
- **Layer-rebalance knock-ons**: REGR-06 (`server.purgatory → storage`), REGR-08 (`server.log.remote → storage`), REGR-09 (`server.quota → network.Session`).

The four KIPs referenced in comments (KIP-405, KIP-500, KIP-787, KIP-714, KIP-899, KIP-932) are pointers a reader can follow, not citations the test verifies. No `because(...)` clause says *"per KIP-500 §X.Y, [direction] must be preserved"*.

### 3.5 Import-scope conditionality

The result "0 violations" is conditional on:

- Only `org.apache.kafka..` classes are imported (`@AnalyzeClasses(packages = "org.apache.kafka")`). The legacy Scala broker code under `kafka.*` is **not scanned**. If `kafka.server.KafkaServer`, `kafka.coordinator.GroupCoordinator`, etc. are still part of the build, their cross-layer edges are silently invisible to this suite.
- `org.apache.kafka.test`, `org.apache.kafka.jmh`, `org.apache.kafka.trogdor` are excluded (one via `DoNotIncludeTests`, two via `TrogdorAndJmhExclusion`). Test fixtures in `org.apache.kafka.test` could legitimately violate "production" layering rules; the exclusion is intentional but is itself a judgment.
- The classpath scanned is whatever Maven Surefire happens to assemble during `mvn test`. Modules excluded from the test build (e.g., generated message code in some configurations, `connect-mirror`, `connect-runtime`) may not be present.
- Generated classes (e.g., `server.log.remote.metadata.storage.generated.ProducerSnapshot`) are present and considered production code.

A meaningful change to the build (adding a new module, including the legacy Scala packages, generating a new sub-package) could re-introduce violations the current run does not see.

### 3.6 Per-class judgment calls

Every class-level predicate in the file embeds a micro-decision a domain expert could reverse. The complete list:

- **MAP-03 second clause**: `org.apache.kafka.metadata.ConfigRepository` is the single class exempted as a SAM (PREC-02 fix in Round 6 tightened this from "any top-level metadata class").
- **`tools_must_not_depend_on_connect_except_plugin_path`**: classes whose name starts with `org.apache.kafka.tools.ConnectPluginPath` or `org.apache.kafka.tools.ManifestWorkspace` are exempt. The exact prefix is the judgment; if the upstream rename a class, the carve-out drifts.
- **OVR-01 narrowed list** (16 documented broker-outbound-RPC types in `clients.*`): `KafkaClient`, `KafkaClient$*`, `NetworkClient`, `NetworkClient$*`, `NodeApiVersions`, `ClientResponse`, `ClientRequest`, `RequestCompletionHandler`, `Metadata`, `Metadata$*`, `MetadataUpdater`, `ApiVersions`, `ManualMetadataUpdater`, `CommonClientConfigs`, `CommonClientConfigs$*`, `MetadataRecoveryStrategy`, `MetadataRecoveryStrategy$*`. Each entry is an "I judge this is an outbound-RPC type" call. The OVR-01-WIDEN finding in Round 7 added one missing entry; future Kafka versions will likely add more.
- **OVR-02 narrowed list** (4 documented `common.*` classes that consume non-admin `clients.*`): `org.apache.kafka.common.MessageFormatter`, `org.apache.kafka.common.requests.ApiVersionsResponse`, `org.apache.kafka.common.requests.ShareFetchRequest`, `org.apache.kafka.common.security.authenticator.SaslClientAuthenticator` (plus inner-class variants of two of those).
- **REGR-09 narrow predicate**: `org.apache.kafka.network.Session` is the single `network.*` class exempted for `server.quota` consumption. If `Session` is split or renamed, the carve-out misses.
- **MOD-08 narrowed predicate**: `server.log.remote.metadata.storage.generated..` (only the schema-derived sub-package) is exempt as a storage target; the rest of `server.log.remote.metadata.storage..` is not. A judgment that "generated" classes are layer-agnostic DTOs.
- **TopicPartitionLog (REGR-08)**: implicitly identified as the only top-level class in `server.log.remote` requiring the storage carve-out. The carve-out source predicate uses `resideInAPackage("org.apache.kafka.server.log.remote")` (bare, no `..`), which means any other top-level class in that package gets the same exemption.
- **MOD-06 target glob** (`image..`, `metadata..`, `controller..`): chosen because `BrokerServerMetrics` reports `MetadataProvenance` and `NodeMetrics` reports `QuorumFeatures`. Future metrics could legitimately observe other layers (e.g., raft); the predicate would not need to expand because raft → controller → image are already in the carve-out, but a new `server.metrics → storage` would fire.
- **REGR-06 source glob** (`server.purgatory..`): the entire package is exempted for storage consumption based on three named subclasses (`DelayedRemoteFetch`, `DelayedRemoteListOffsets`, `ListOffsetsPartitionStatus`). Other classes in the package (the generic framework) do not need the exemption today.
- **The 18 `server.X` sub-packages classified as Core vs the 6 classified as Server**: each is a per-package judgment with no single rule. The most contested are `server.purgatory` (Core, with carve-out — REGR-06), `server.quota` (Core, with carve-out — REGR-07 + REGR-09), `server.log.remote.quota` (Core — REGR-05), and `server.log.remote` (Core, narrow scope + carve-out — REGR-08). Reviews 4 and 5 went through three iterations of "wait, that one's actually consumed by Core" before stabilising.

## Section 4 — The strongest claim I can actually support

> The rule file, the imported `org.apache.kafka..` classpath, and my reading of the supplied Kafka Streams Architecture PDF and package listing all agree at a fixed point: every cross-layer edge in the imported classpath is either consistent with the inferred five-layer model or documented as one of 22 explicit `ignoreDependency` carve-outs.

Two corollaries that genuinely follow from test evidence, not interpretation:

1. **No silent suppression remains.** Round 6's narrowing of MOD-01 (`server.. → clients..`) and MOD-09 (`common.. → clients..`) traded a green-but-toothless suite for a red-but-load-bearing one (Round 7 surfaced 79 previously-hidden dependencies); Round 7's three new explicit carve-outs (TIERED-01, SHARE-01, OVR-01-WIDEN) returned the suite to green with the precision intact. Every layer-crossing edge in the bytecode is now individually documented or moved inside a layer via reclassification (REGR-04..09 across Rounds 3–5).
2. **Every new `org.apache.kafka.*` top-level package added to the build will fail the `every_production_class_must_be_in_a_layer` guard with an actionable diagnostic message ("Class X in package [Y] is not assigned to any layer. Add the package to the appropriate CORE_PACKAGES / SERVER_PACKAGES / etc. constant.") until a maintainer routes it to a layer.** This is verified: Round 3 REGR-04 surfaced 12 silently-unrouted `server.X` sub-packages exactly via this mechanism.

## Section 5 — What would upgrade confidence

In descending order of impact:

1. **Replace the documentation source.** The single largest weakness is that the supplied PDF describes Kafka Streams runtime, not the broader Kafka package layering. Substituting (any of) KIP-500 (KRaft control plane), KIP-405 (tiered storage), KIP-932 (share groups), the Kafka `design.html` page, or each module's `build.gradle` `dependencies` block would convert the file's `because(...)` clauses from "inferred from package naming" to "per [citation]". The first citation also enables the phrase *"this rule encodes a documented prohibition"* to appear honestly in the file for the first time.
2. **Domain-expert review of the per-class judgment list.** The Section 3.6 enumeration is short enough (≈12 named classes plus the 16-entry OVR-01 list) for an Apache Kafka committer or PMC member to review in 30–60 minutes. Their opinion on borderline cases (Is `server.purgatory` Core or Server? Should `MetadataRecoveryStrategy` count as broker-outbound-RPC or as a generic config enum?) would either confirm or reverse a handful of the most contested judgments.
3. **Mutation testing.** Inject a known violation in a controlled branch — e.g., add `import org.apache.kafka.connect.runtime.Worker;` in `org.apache.kafka.tools.MetadataQuorumCommand` — confirm `tools_must_not_depend_on_connect_except_plugin_path` fires, then remove and confirm it clears. Repeat for the most-load-bearing rules (the layered rule itself, the every-class guard, and the three intra-API isolation rules for streams/connect/admin). This converts "no false positives in the current run" into "demonstrated true positives on a counterfactual bad change".
4. **Extend the import scope.** The legacy Scala packages under `kafka.*` are not scanned. If those modules are still part of the build (and at the time of writing many `kafka.server.*`, `kafka.coordinator.*`, `kafka.utils.*` classes still are), expanding `@AnalyzeClasses(packages = {"org.apache.kafka", "kafka"})` would either confirm the current rules also hold in the legacy code or surface a new set of patterns to triage.
5. **Pin each carve-out's `because(...)` to an upstream citation.** This is the DOC-01 finding from Review #8 written long-form. For each of the 22 `ignoreDependency` clauses, find the KIP / mailing-list thread / `build.gradle` exclusion that documents the carve-out and add the citation as a comment. Estimate ~30 minutes per clause.

None of these is required to ship the rule file. All of them would upgrade the confidence level from "fixed point reached" to "formally validated against an authoritative source".

## Section 6 — Recommendation

**Ship**, with the existing Javadoc header (lines 1-119 of `ArchitectureEnforcementTest.java`) extended by three explicit caveats so future maintainers cannot mistake "test passes" for "architecture validated":

1. *"The five-layer partition (Core / Storage / Consensus / Server / API) is an operational encoding inferred from package naming. The supplied architecture document (`inputs/java/7_apache_kafka.pdf`) describes Kafka Streams runtime semantics and contains no cross-package prohibitions for `org.apache.kafka.*`. Validate any individual rule against KIP-500 / KIP-405 / KIP-932 / `design.html` before treating it as authoritative."*
2. *"22 `ignoreDependency` clauses are documented inline with finding-ID labels (MAP-03, MOD-01..10, MOD-05-WIDEN, FP-AUTH-01, REGR-06, REGR-08, REGR-09, TIERED-01, SHARE-01, OVR-01, OVR-02). Each carve-out's `because(...)` rationale is reasoning, not a citation — project-maintainer review of the per-class judgment calls (the OVR-01 16-type allowlist, the OVR-02 4-class list, the REGR-05..09 layer-rebalance decisions) is invited and would meaningfully upgrade confidence."*
3. *"Test scope is `org.apache.kafka..` only, with `org.apache.kafka.{test,jmh,trogdor}` excluded. The legacy Scala packages under `kafka.*` are not scanned by this test; their cross-package edges are silently outside its coverage."*

The current rule file is a useful CI regression detector: every cross-layer edge in the imported classpath is documented, and the every-class guard catches new packages with an actionable diagnostic. It is **not** a specification of Kafka's architecture in any sense an Apache Kafka committer would recognise. The caveats above make that limit legible.
