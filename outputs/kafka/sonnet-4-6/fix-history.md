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
