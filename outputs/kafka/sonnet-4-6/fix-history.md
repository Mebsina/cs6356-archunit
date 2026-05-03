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
