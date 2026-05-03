# Fix History — Apache Kafka ArchUnit Enforcement Tests

**Project**: kafka  
**Model**: sonnet-4-6  
**File**: ArchitectureEnforcementTest.java

---

## 1. Initial test generation

Generated `ArchitectureEnforcementTest.java` and `pom.xml` from the Kafka Streams Architecture
documentation (`inputs/java/7_apache_kafka.pdf`) and the package structure listing
(`inputs/java/7_apache_kafka.txt`).

**Layer hierarchy established (bottom to top)**:
- Core: `common`, `message`, `config`, `logger`, `deferred`, `queue`, `timeline`, `security`
- Storage: `storage`, `snapshot`, `tiered`
- Consensus: `raft`, `metadata`, `image`
- Server: `server`, `controller`, `coordinator`, `network`
- API: `api`, `clients`, `admin`, `connect`, `streams`, `tools`, `shell`

**Excluded packages**: `test`, `jmh`, `trogdor` (non-production, test/benchmark utilities)

**Rules generated**:
- 1 `layeredArchitecture()` rule enforcing the five-layer strict hierarchy
- 10 fine-grained `noClasses()` rules for intra-API-layer sibling isolation
  (`streams` vs `connect`, `streams` vs `admin`, `connect` vs `streams`,
  `clients` vs `admin`, `admin` vs `streams`, `admin` vs `connect`,
  `shell` vs `streams`, `shell` vs `connect`, `tools` vs `streams`, `tools` vs `connect`)
- 4 fine-grained `noClasses()` rules for intra-Server-layer isolation
  (`controller` vs `coordinator`, `coordinator` vs `controller`,
  `network` vs `controller`, `network` vs `coordinator`)
- 2 fine-grained `noClasses()` rules for intra-Consensus-layer isolation
  (`raft` vs `metadata`, `raft` vs `image`)
- 1 rule: `metadata` must not depend on `server`
- 3 fine-grained `noClasses()` rules for Core (`common`) isolation from Storage, Consensus, Server
- 3 fine-grained `noClasses()` rules for Storage (`storage`) isolation from Consensus, Server, API

---

## 2. Fix compile error #1

Compile #1
Error: `ImportOption.DoNotIncludeTests.INSTANCE` cannot be resolved — `DoNotIncludeTests` has no static `INSTANCE` field in ArchUnit 1.x; it is a regular class, not an enum singleton.
Fix: Replaced `.withImportOption(ImportOption.DoNotIncludeTests.INSTANCE)` with `.withImportOption(new ImportOption.DoNotIncludeTests())` on line 123 of `ArchitectureEnforcementTest.java`.

---

## 3. Fix review findings (Review #1 by opus-4-7)

Review #1
Findings: 18 issues across 9 failing tests (2,213 total violations). Critical problems: (1) MAP-01 — org.apache.kafka.admin.. is a phantom package; admin client lives at clients.admin. (2) MAP-02 — org.apache.kafka.server.. glob is too coarse; shared utility sub-packages (server.common, server.util, server.fault, server.authorizer, etc.) are used by metadata/storage/common as low-level dependencies, producing 1,467 false-positive violations. (3) VAC-01/VAC-02 — multiple layer members and rules target empty packages (admin, api, coordinator, tiered, message, config, logger, deferred, queue, timeline), causing failOnEmptyShould failures. (4) MAP-03 — storage -> metadata.properties and metadata.ConfigRepository are legitimate downcalls, not violations. (5) FP-01 — tools -> connect is legitimate for the connect-plugin-path CLI. (6) FP-02 — common -> server.authorizer is an intentional SPI implementation. (7) COV-02/COV-03 — missing image isolation rule; incomplete intra-API matrix. (8) NAR-01/NAR-02 — network and raft lack full upper-layer isolation rules. (9) TRANS-01 — no rule prevents clients -> streams/connect cycle. (10) SCOPE-01 — KAFKA_CLASSES field is dead code; jmh/trogdor exclusions were not applied. (11) LAY-01 — no guard for unmapped packages. (12) REA-01 — all because() clauses cited the PDF as source of constraints, which the PDF does not contain.
Fix:
- MAP-02: Split server.. layer into Core (server.common, server.util, server.metrics, server.fault, server.authorizer, server.immutable, server.config, server.record, server.storage.log, server.log.remote.storage) and Server runtime (server top-level, server.share, server.transaction, server.log.remote.metadata.storage, controller, network).
- MAP-01/VAC-02: Removed empty packages from all layer definitions (api, admin, coordinator, tiered, message, config, logger, deferred, queue, timeline). Removed phantom-admin rules: streams_must_not_depend_on_admin, clients_must_not_depend_on_admin, admin_must_not_depend_on_streams, admin_must_not_depend_on_connect. Added clients_admin_must_not_depend_on_streams and clients_admin_must_not_depend_on_connect targeting the real package.
- MAP-03: Removed metadata.. from storage_must_not_depend_on_consensus target list. Added two ignoreDependency clauses to kafka_layered_architecture for storage -> metadata.properties.. and storage -> metadata (ConfigRepository SAM).
- VAC-01: Removed coordinator_must_not_depend_on_controller (coordinator.. is empty; failOnEmptyShould would fail the rule).
- VAC-03: Removed tiered.. from common_must_not_depend_on_storage; replaced admin.. with clients.. in storage_must_not_depend_on_api_layer.
- FP-01: Removed tools_must_not_depend_on_connect; documented ConnectPluginPath as a legitimate exception.
- FP-02: Renamed common_must_not_depend_on_server_layer to common_must_not_depend_on_server_runtime; target list now contains only broker-runtime packages (server top-level, server.share, server.transaction, controller, network) excluding the shared-utility sub-packages now classified as Core.
- COV-02: Added image_must_not_depend_on_server_runtime rule.
- COV-03: Added missing intra-API isolation rules: streams_must_not_depend_on_tools, streams_must_not_depend_on_shell, connect_must_not_depend_on_tools, connect_must_not_depend_on_shell, tools_must_not_depend_on_shell, shell_must_not_depend_on_tools.
- NAR-01: Added network_must_not_depend_on_server_runtime rule covering server/share/transaction/controller/coordinator.
- NAR-02: Added raft_must_not_depend_on_higher_layers (controller, network, server, share, transaction) and metadata_must_not_depend_on_image. Renamed metadata_must_not_depend_on_server to metadata_must_not_depend_on_server_runtime targeting only runtime packages.
- TRANS-01: Added clients_must_not_depend_on_streams and clients_must_not_depend_on_connect.
- SCOPE-01: Removed dead KAFKA_CLASSES field; added TrogdorAndJmhExclusion public static inner class implementing ImportOption; wired it into @AnalyzeClasses. Removed now-unnecessary ClassFileImporter and JavaClasses imports.
- LAY-01: Added every_production_class_must_be_in_a_layer guard rule using classes().should().resideInAnyPackage(...) to ensure no future packages silently escape layer coverage.
- REA-01: Rewrote all because() clauses to explicitly state constraints are inferred from package naming and are not directly documented in the supplied Kafka Streams Architecture PDF.

---

## 4. Fix review findings (Review #2 by opus-4-7)

Review #2
Findings: 11 issues across 4 failing tests (1,071 total violations). Two failures were regressions introduced by Review #1: (1) REGR-01 — org.apache.kafka.config, deferred, logger, queue, timeline were incorrectly declared empty; they contain 44 real production classes (BrokerReconfigurable, DeferredEventQueue, StateChangeLogger, KafkaEventQueue, TimelineHashMap, SnapshotRegistry, etc.) and were orphaned from layer coverage. (2) REGR-02 — metadata_must_not_depend_on_image was inverted; the KIP-500 direction is metadata -> image (metadata.publisher.* implements image.publisher.MetadataPublisher and receives MetadataImage in callbacks). (3) REGR-03 — tools_must_not_depend_on_connect was deleted entirely instead of scoped to the ConnectPluginPath exception. (4) MOD-01 — 821 remaining layered violations from intrinsic Kafka cross-layer SPI/DTO patterns (server -> clients outbound RPC, raft -> clients, snapshot -> raft, server.config -> metadata SPI, server.log.remote.storage -> storage SPI). (5) MOD-02 — clients.admin DTOs (ScramMechanism, AlterConfigOp$OpType, ConfigEntry$ConfigSource) used as shared primitives by common, controller, image. (6) FP-NEW-01 — metadata_must_not_depend_on_server_runtime fires 4 times on metadata.authorizer.* which contains controller-side ACL implementations deliberately wired into the controller pipeline. (7) LAY-NEW-01 — every-class guard allowlist was hand-copied from layer definitions and could drift. (8) LAY-NEW-02 — bare-package ignoreDependency for ConfigRepository could be mistaken for a typo and incorrectly widened. (9) COV-NEW-01/TRANS-NEW-01 — new future-proofing rules not clearly labelled as such. Two positive findings: REA-NEW-01 and SCOPE-NEW-01 confirm successful fixes from Review #1.
Fix:
- REGR-01: Restored config.., deferred.., logger.., queue.., timeline.. to Core layer and to every_production_class_must_be_in_a_layer guard allowlist.
- REGR-02: Deleted metadata_must_not_depend_on_image (inverted); added image_must_not_depend_on_metadata_publisher_internals with correct direction and resideOutsideOfPackage("image.publisher..") exclusion.
- REGR-03: Re-added tools_must_not_depend_on_connect_except_plugin_path with DescribedPredicate exclusion for ConnectPluginPath and ManifestWorkspace.
- MOD-01: Added ignoreDependency clauses to kafka_layered_architecture for all five SPI/RPC patterns: server.config->metadata, server.log.remote.storage->storage, server->clients, raft->clients, snapshot->raft.
- MOD-02: Added ignoreDependency clauses for common->clients.admin, controller->clients.admin, image->clients.admin (shared DTO types).
- FP-NEW-01: Added .and().resideOutsideOfPackage("org.apache.kafka.metadata.authorizer..") to metadata_must_not_depend_on_server_runtime.
- LAY-NEW-01: Extracted per-layer package constant arrays (CORE_PACKAGES, STORAGE_PACKAGES, CONSENSUS_PACKAGES, SERVER_PACKAGES, API_PACKAGES) and ALL_LAYER_PACKAGES (derived via Stream.flatMap). Used CORE_PACKAGES etc. in layeredArchitecture().layer().definedBy() and ALL_LAYER_PACKAGES in every_production_class_must_be_in_a_layer.should().resideInAnyPackage() so both stay in sync.
- LAY-NEW-02: Replaced bare resideInAPackage("org.apache.kafka.metadata") with a DescribedPredicate that matches only top-level metadata classes; added explicit comment explaining the intentional bare-package constraint.
- COV-NEW-01/TRANS-NEW-01: Updated all future-proofing because() clauses with "FUTURE-PROOFING ONLY:" prefix to clarify the rules guard against future regressions rather than enforcing current-state invariants.

---

## 5. Fix review findings (Review #3 by opus-4-7)

Review #3
Findings: 12 issues across 2 failing tests (209 total violations). (1) REGR-04 (CRITICAL) — 12 server.* sub-packages (~100 production classes) were absent from CORE_PACKAGES and SERVER_PACKAGES because the LAY-NEW-01 refactor copied the same incomplete enumeration. Classes in server.mutable, server.network, server.policy, server.purgatory, server.telemetry, server.log.remote, server.controller, server.logger, server.partition, server.replica, server.quota, server.log.remote.quota were orphaned and silently exempt from the layered rule. (2) MOD-03 (HIGH) — metadata->clients.admin missed; metadata.KafkaConfigSchema and metadata.ScramCredentialData use ConfigEntry and ScramMechanism as wire-format primitives (~25 violations). (3) MOD-04 (HIGH) — security->clients.admin missed; security.CredentialProvider uses ScramMechanism (~4 violations). (4) MOD-05 (HIGH) — server.config->storage missed; AbstractKafkaConfig aggregates ConfigDef from storage.internals.log (~20 violations). (5) MOD-06 (HIGH) — server.metrics->image/metadata/controller; BrokerServerMetrics and NodeMetrics observe higher-layer runtime state (~7 violations). (6) MOD-07 (HIGH) — server.util->metadata; NetworkPartitionMetadataClient depends on MetadataCache SAM (~3 violations). (7) MOD-08 (HIGH) — storage->server.log.remote.metadata.storage.generated; ProducerStateManager uses generated schema DTOs for on-disk snapshot format (~22 violations). (8) MOD-09 (HIGH) — common->clients (non-admin); MessageFormatter, ApiVersionsResponse, ShareFetchRequest, SaslClientAuthenticator share types with clients (~7 violations). (9) FP-AUTH-01 (MEDIUM) — metadata.authorizer->controller still fires on the layered rule even after FP-NEW-01 added the exclusion to metadata_must_not_depend_on_server_runtime (~4 violations). (10) MOD-10 (MEDIUM) — server.log.remote.storage->server.log.remote.metadata.storage; RemoteLogManager SPI instantiates default ClassLoaderAwareRemoteLogMetadataManager (~1 violation). (11) LAY-NEW-03 (MEDIUM) — every_production_class_must_be_in_a_layer prints 100 near-identical multi-line paragraphs making CI output unreadable. (12) REA-NEW-02 (LOW) — minor inconsistency in because() clause voice; skipped (not a correctness issue).
Fix:
- REGR-04: Added server.mutable.., server.network.., server.policy.., server.purgatory.., server.telemetry.., server.log.remote.. to CORE_PACKAGES; added server.controller.., server.logger.., server.partition.., server.replica.., server.quota.., server.log.remote.quota.. to SERVER_PACKAGES.
- MOD-03: Added ignoreDependency(metadata.. -> clients.admin..).
- MOD-04: Added ignoreDependency(security.. -> clients.admin..).
- MOD-05: Added ignoreDependency(server.config.. -> storage..).
- MOD-06: Added ignoreDependency(server.metrics.. -> image/metadata/controller) using DescribedPredicate on the target.
- MOD-07: Added ignoreDependency(server.util.. -> metadata..).
- MOD-08: Added ignoreDependency(storage.. -> server.log.remote.metadata.storage.generated..) using narrow form targeting only the schema-derived .generated sub-package.
- MOD-09: Added ignoreDependency(common.. -> clients..) covering all clients sub-packages; removed the now-redundant MOD-02 clause common..->clients.admin.. (subsumed).
- MOD-10: Added ignoreDependency(server.log.remote.storage.. -> server.log.remote.metadata.storage..).
- FP-AUTH-01: Added ignoreDependency(metadata.authorizer.. -> controller..) to mirror the FP-NEW-01 exception already present on metadata_must_not_depend_on_server_runtime.
- LAY-NEW-03: Replaced .should().resideInAnyPackage(ALL_LAYER_PACKAGES) with a custom ArchCondition<JavaClass> that emits one short sentence per violation instead of the full package list, directing the reader to add the package to the appropriate XXX_PACKAGES constant.
- Added ArchCondition, ConditionEvents, SimpleConditionEvent imports.

---

## 6. Fix review findings (Review #4 by opus-4-7)

Review #4
Findings: 6 issues across 1 failing test (86 total violations). All violations on kafka_layered_architecture; every other rule passes. (1) REGR-05 (CRITICAL) — server.log.remote.quota placed in Server in Review #3 but its only callers (RemoteLogManager, RemoteLogReader) live in Core; ~20 violations from Core→Server direction. (2) REGR-06 (CRITICAL) — server.purgatory placed in Core in Review #3 but the same package contains storage-aware subclasses (DelayedRemoteFetch, DelayedRemoteListOffsets, ListOffsetsPartitionStatus) that legitimately reference storage result types; ~35 violations. (3) REGR-07 (HIGH) — server.quota placed in Server in Review #3 but ClientQuotaCallback SPI and QuotaType enum are consumed by Core (server.log.remote.storage) and Consensus (metadata.publisher); ~5 violations. (4) REGR-08 (HIGH) — server.log.remote.. glob in Core includes TopicPartitionLog which returns Optional<storage.internals.log.UnifiedLog>; 1 violation; also the broad glob silently classifies any future server.log.remote.X sub-package as Core. (5) MOD-05-WIDEN (HIGH) — server.config→storage carve-out from Review #3 missed three additional aggregation targets: network (SocketServerConfigs), raft (KRaftConfigs, MetadataLogConfig, QuorumConfig), and server top-level (DynamicThreadPool.RECONFIGURABLE_CONFIGS); ~6 violations. (6) LAY-NEW-04 (LOW) — LAY-NEW-03 guard passes but the custom ArchCondition message format is unobserved; no code change needed.
Fix:
- REGR-05: Moved server.log.remote.quota.. from SERVER_PACKAGES to CORE_PACKAGES.
- REGR-06: Kept server.purgatory.. in CORE_PACKAGES; added ignoreDependency(server.purgatory.. -> storage..) for the storage-aware delayed-op subclasses.
- REGR-07: Moved server.quota.. from SERVER_PACKAGES to CORE_PACKAGES.
- REGR-08: Replaced "server.log.remote.." glob in CORE_PACKAGES with bare-package "server.log.remote" (top-level only) plus explicit "server.log.remote.quota.." (from REGR-05); added ignoreDependency(server.log.remote -> storage..) with bare-package source predicate so the carve-out is scoped to TopicPartitionLog only.
- MOD-05-WIDEN: Added three ignoreDependency clauses: server.config.. -> network.., server.config.. -> raft.., server.config.. -> server (top-level bare package for DynamicThreadPool).
- LAY-NEW-04: No code change required.

---

## 7. Fix review findings (Review #5 by opus-4-7)

Review #5
Findings: 2 issues (1 actionable), 1 failing test, 14 total violations — all on kafka_layered_architecture. (1) REGR-09 (HIGH) — server.quota runtime managers (ClientQuotaManager, ControllerMutationQuotaManager) take network.Session as a parameter for principal-based throttling. After REGR-07 moved server.quota from Server to Core, this creates a Core→Server upward dependency. Session is an authentication-context value type genuinely cross-cutting between the quota and network layers. All 14 violations are this single pattern across two classes and 11 methods. (2) POS-03 (informational) — all five Round-4 fixes confirmed working.
Fix:
- REGR-09: Added ignoreDependency(server.quota.. → network.Session only) using a narrow DescribedPredicate that matches only org.apache.kafka.network.Session, preserving detection of any future server.quota → network.SocketServer regression.

---

## 8. Fix review findings (Review #6 by opus-4-7)

Review #6
Suite is green (0 violations, 33/33 passing). Findings are precision/quality improvements only — none change pass/fail status. (1) OVR-01 (HIGH) — MOD-01 server.. → clients.. is a package-to-package wildcard that silently allows any future server.* → clients.* dependency (e.g., server.replica → clients.consumer). Originally added for ~15-20 outbound-RPC call sites. (2) OVR-02 (HIGH) — MOD-09 common.. → clients.. is a package-to-package wildcard that effectively abandons enforcement of "clients depends on common, not the reverse". Originally added for 4 specific classes plus inherited admin-DTO uses. (3) VAC-NEW-02 (MEDIUM) — controller_must_not_depend_on_coordinator passes vacuously (coordinator.. is empty in classpath); rule's because() uses definite voice implying active enforcement. network_must_not_depend_on_server_runtime also targets coordinator.. without marking it future-proofing-only. (4) PREC-01 (LOW) — MOD-06 DescribedPredicate uses unanchored startsWith (would also match org.apache.kafka.imagery, etc.). (5) PREC-02 (LOW) — MAP-03 ConfigRepository predicate actually matches any top-level org.apache.kafka.metadata.* class, not specifically ConfigRepository; description and behavior diverge.
Fix:
- OVR-01: Replaced broad server.. → clients.. ignoreDependency with a DescribedPredicate listing 15 specific broker-outbound-RPC types (KafkaClient, NetworkClient, NodeApiVersions, ClientResponse, ClientRequest, RequestCompletionHandler, Metadata, MetadataUpdater, ApiVersions, ManualMetadataUpdater, CommonClientConfigs, plus inner-class variants).
- OVR-02: Split single common.. → clients.. clause into two narrower clauses: (a) common.. → clients.admin.. for admin-DTO wire-format uses (restores MOD-02 scope), (b) DescribedPredicate matching exactly the four documented common.* classes that cross into non-admin clients (MessageFormatter, ApiVersionsResponse, ShareFetchRequest, SaslClientAuthenticator, plus inner-class variants).
- VAC-NEW-02: Updated controller_must_not_depend_on_coordinator because() to "FUTURE-PROOFING ONLY: coordinator.. is empty in current scan...". Added inline comment "FUTURE-PROOFING ONLY: empty in current scan" next to the coordinator.. entry in network_must_not_depend_on_server_runtime.
- PREC-01: Replaced MOD-06 hand-written startsWith predicate with idiomatic resideInAnyPackage("org.apache.kafka.image..", "org.apache.kafka.metadata..", "org.apache.kafka.controller..").
- PREC-02: Replaced MAP-03 ConfigRepository predicate body with c.getName().equals("org.apache.kafka.metadata.ConfigRepository") and updated description to remove the "(top-level SAM only)" qualifier.

---

## 9. Fix review findings (Review #7 by opus-4-7)

Review #7
Suite re-broke after Review #6's precision narrowing (intended). 1 failing test, 79 violations — all on kafka_layered_architecture. (1) TIERED-01 (HIGH) — server.log.remote.metadata.storage (KIP-405 topic-based tiered-metadata store) uses KafkaProducer/KafkaConsumer/Admin/NewTopic by design to read & write the internal __remote_log_metadata topic (~70 violations). The old server.. → clients.. wildcard suppressed this; the OVR-01 narrowing exposed it. (2) SHARE-01 (HIGH) — server.share (KIP-932 server-side share-group) consumes clients.consumer.AcknowledgeType and clients.consumer.internals.ShareAcquireMode as wire-protocol enums (~8 violations). Same shared-DTO pattern as MOD-02/03/04; also previously hidden by the wildcard. (3) OVR-01-WIDEN (MEDIUM) — clients.MetadataRecoveryStrategy (KIP-899 enum) missing from the OVR-01 enumerated list (1 violation, NodeToControllerChannelManagerImpl.buildNetworkClient).
Fix:
- TIERED-01: Added ignoreDependency(server.log.remote.metadata.storage.. -> clients..) — wholesale exemption because every major class in the package is a client user by design.
- SHARE-01: Added ignoreDependency(server.share.. -> resideInAnyPackage(clients.consumer, clients.consumer.internals)) — sub-package-scoped to remain readable as KIP-932 grows.
- OVR-01-WIDEN: Added MetadataRecoveryStrategy and MetadataRecoveryStrategy$ to the OVR-01 DescribedPredicate enumerated list, inserted near MetadataUpdater for thematic grouping.
