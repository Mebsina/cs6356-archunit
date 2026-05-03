# Adversarial Review #2 — Apache Kafka ArchUnit Rules

**Project**: kafka  
**Generator Model**: sonnet-4-6  
**Reviewer Model**: opus-4-7  
**Review**: #2  
**File reviewed**: `outputs/kafka/sonnet-4-6/ArchitectureEnforcementTest.java`  
**Surefire evidence**: `outputs/kafka/sonnet-4-6/target/surefire-reports/org.archunit.kafka.ArchitectureEnforcementTest.txt`  
**Architecture documentation**: `inputs/java/7_apache_kafka.pdf`  
**Package structure**: `inputs/java/7_apache_kafka.txt`  
**Previous review**: `review1-by-opus-4-7.md`

- **Violations**: 4 / 32 failing tests (1,071 total)
- **Issues**: 11

---

## Executive Summary

Round 2 reduced failures from **9/24** to **4/32** and total architecture violations from **2,213** to **1,071**. The MAP-02 split (server.* into Core utilities + Server runtime) eliminated about 1,200 false positives from the layered rule, MAP-01/VAC-01 fixed all "failed to check any classes" failures by deleting the phantom-package rules, and the new `every_production_class_must_be_in_a_layer` guard immediately exposed a regression introduced by Review #1's own advice.

Round 2 reveals two reviewer mistakes from Review #1 that the generator faithfully implemented:

- **Review #1 was wrong about VAC-02.** Five of the packages I called "vacuous" — `config`, `deferred`, `logger`, `queue`, `timeline` — actually contain 44 real production classes (KIP-500 controller utilities: `BrokerReconfigurable`, `DeferredEventQueue`, `StateChangeLogger`, `KafkaEventQueue`, `TimelineHashMap`, `SnapshotRegistry`, etc.). Removing them from the layer definition orphaned those 44 classes — exactly what the new `every_production_class_must_be_in_a_layer` guard catches today.
- **Review #1 was wrong about NAR-02 (metadata→image direction).** The KIP-500 design has metadata publishers (`AclPublisher`, `FeaturesPublisher`, `ScramPublisher`, etc.) **implement** `image.publisher.MetadataPublisher` and **consume** `image.MetadataImage` — i.e., metadata legitimately depends on image, not the other way around. The new `metadata_must_not_depend_on_image` rule fires 202 times on what is intentional, documented behaviour.

Beyond those two regressions, Round 2 also exposes the deeper issue: even with a corrected layer split, **821 layered violations remain** because Apache Kafka's actual coupling pattern is *not* a strict 5-layer hierarchy. The codebase uses many cross-layer SPI / callback patterns (RemoteStorageManager, RequestCompletionHandler, MetadataPublisher, SupportedConfigChecker, AsyncOffsetReader, KafkaClient, ClientResponse, NetworkClient) plus DTO sharing (clients.admin enums and DTOs reused inside common.requests, controller, and image). These are deliberate dependency-inversion / shared-types patterns that no amount of `..` glob tweaking will reconcile with `whereLayer().mayOnlyBeAccessedBy(...)`.

**Critical Gaps**:
- **REGR-01** (CRITICAL): five packages with real classes were deleted from the layer assignment based on faulty Review #1 advice. 44 classes are now orphaned.
- **REGR-02** (HIGH): `metadata_must_not_depend_on_image` is inverted — metadata→image is the documented KIP-500 callback direction.
- **MOD-01** (CRITICAL): the strict 5-layer model fundamentally cannot describe Kafka's real cross-layer SPI/DTO patterns; the layered rule will never reach 0 violations without major model restructuring.
- **REGR-03** (HIGH): `tools_must_not_depend_on_connect` was deleted entirely instead of scoped to allow only the `ConnectPluginPath` exception. Future tools that import Connect will go undetected.

**Overall verdict**: **FAIL** — but materially closer than Round 1.
- 4/32 failing (was 9/24).
- 1,071 violations (was 2,213).
- 2 of the 4 failures are caused by Review #1 errors (REGR-01, REGR-02). Reverting those would put the file at 2 failures.
- The remaining 821 layered violations are mostly real cross-layer coupling that requires either model restructuring or a substantial allowlist.

---

## Findings

### REGR-01 — Five real packages were orphaned because Review #1's VAC-02 was wrong

```
SEVERITY: CRITICAL
Category: Coverage Gap (regression introduced by Review #1)
Affected Rule / Constraint: kafka_layered_architecture (layer definitions),
                            every_production_class_must_be_in_a_layer (44 violations)
```

**What is wrong:**
Review #1 claimed `org.apache.kafka.config..`, `deferred..`, `logger..`, `queue..`, `timeline..`, `message..`, `tiered..`, `api..`, `admin..`, `coordinator..` were all "vacuous" packages with zero classes. The generator dutifully removed them from every layer definition. **Five of those packages actually contain 44 production classes.** The new `every_production_class_must_be_in_a_layer` rule now reports them as orphaned (lines 850–893 of surefire):

| Package | Classes (sample) |
|---|---|
| `org.apache.kafka.config` | `BrokerReconfigurable` |
| `org.apache.kafka.deferred` | `DeferredEvent`, `DeferredEventQueue` |
| `org.apache.kafka.logger` | `StateChangeLogger` |
| `org.apache.kafka.queue` | `EventQueue`, `KafkaEventQueue`, `KafkaEventQueue$EventContext`, `KafkaEventQueue$EventHandler`, … (~9) |
| `org.apache.kafka.timeline` | `TimelineHashMap`, `TimelineHashSet`, `TimelineLong`, `TimelineInteger`, `TimelineObject`, `SnapshotRegistry`, `SnapshottableHashTable`, `BaseHashTable`, `Delta`, `Revertable`, `Snapshot`, … (~30) |

These are KIP-500 controller utilities — foundational immutable/timeline data structures (`TimelineHashMap`), event queue abstractions (`KafkaEventQueue`), and deferred-result helpers (`DeferredEventQueue`) used pervasively by the Consensus and Server layers. They must be in **Core**.

**Why it matters:**
- 44 real classes are silently exempt from `consideringOnlyDependenciesInLayers()` — any upward dependency originating from these packages goes undetected by the layered rule.
- The newly-added guard `every_production_class_must_be_in_a_layer` is the only thing flagging the regression today; without it the orphaning would be invisible.
- This is a self-inflicted wound: the prior reviewer (me) verified emptiness by `grep -E "<org\.apache\.kafka\.X\." surefire.txt`, but that grep only catches classes that appear as the *source* of a Round 1 violation. Truly foundational utilities (queue, timeline, deferred) that depend only on JDK and `common` will never be sources of upward violations and so were invisible to that grep. The verification was unsound.

**How to fix it:**
Add the five packages back into the **Core** layer definition (and to the every-class guard's allowlist):

```java
.layer("Core").definedBy(
    "org.apache.kafka.common..",
    "org.apache.kafka.security..",
    "org.apache.kafka.config..",      // BrokerReconfigurable
    "org.apache.kafka.deferred..",    // DeferredEvent, DeferredEventQueue
    "org.apache.kafka.logger..",      // StateChangeLogger
    "org.apache.kafka.queue..",       // EventQueue, KafkaEventQueue
    "org.apache.kafka.timeline..",    // TimelineHashMap, SnapshotRegistry, …
    "org.apache.kafka.server.common..",
    "org.apache.kafka.server.util..",
    "org.apache.kafka.server.metrics..",
    "org.apache.kafka.server.fault..",
    "org.apache.kafka.server.authorizer..",
    "org.apache.kafka.server.immutable..",
    "org.apache.kafka.server.config..",
    "org.apache.kafka.server.record..",
    "org.apache.kafka.server.storage.log..",
    "org.apache.kafka.server.log.remote.storage.."
)
```

…and add the same five entries to the every-class guard's allowlist:

```java
.should().resideInAnyPackage(
    "org.apache.kafka.common..",
    "org.apache.kafka.security..",
    "org.apache.kafka.config..",
    "org.apache.kafka.deferred..",
    "org.apache.kafka.logger..",
    "org.apache.kafka.queue..",
    "org.apache.kafka.timeline..",
    "org.apache.kafka.server..",
    /* … rest unchanged … */
)
```

Verification rule for future audits — only declare a package empty if **all three** of these are zero:

```bash
grep -cE "^(Class|Method|Constructor|Field) <org\.apache\.kafka\.PKG\." surefire.txt
grep -cE "<org\.apache\.kafka\.PKG\." surefire.txt   # also as a target
ls path/to/org/apache/kafka/PKG/*.class 2>/dev/null | wc -l
```

The remaining packages I flagged in VAC-02 — `message`, `tiered`, `api`, `admin`, `coordinator` — should also be re-verified by direct classpath inspection before the file ships. The current scan still shows zero classes for them, but that may be because the test classpath excludes a Maven module rather than because the packages don't exist.

---

### REGR-02 — `metadata_must_not_depend_on_image` is inverted (NAR-02 was wrong)

```
SEVERITY: HIGH
Category: Semantic Error (regression introduced by Review #1)
Affected Rule / Constraint: metadata_must_not_depend_on_image (202 violations)
```

**What is wrong:**
Review #1 NAR-02 recommended adding `metadata_must_not_depend_on_image`. This is backwards. KIP-500 architecture has the **metadata layer subscribe to the image layer**: every concrete `MetadataPublisher` lives under `org.apache.kafka.metadata.publisher.*` and **implements** the SAM `org.apache.kafka.image.publisher.MetadataPublisher`. Each publisher receives `image.MetadataImage` / `image.MetadataDelta` arguments in its `onMetadataUpdate(...)` callback. The current `KRaftMetadataCache` literally **holds a `MetadataImage` field** (line 0 of `KRaftMetadataCache.java` per surefire) and exposes it to every consumer via `currentImage()`/`getImage()`/`setImage()`. None of this is a violation — it is the load-balanced read path of the entire KRaft metadata stack.

The 202 violations break down as:
- 7 `metadata.publisher.*` classes implementing `image.publisher.MetadataPublisher` (definitional);
- ~80 `metadata.KRaftMetadataCache.*` methods reading `image.MetadataImage`, `image.TopicsImage`, `image.ClusterImage`, `image.FeaturesImage`, `image.ConfigurationsImage`, `image.ScramImage`, etc.;
- ~30 `metadata.MetadataCache.*` static helpers receiving `image.MetadataImage` parameters;
- ~80 publisher `onMetadataUpdate(MetadataDelta, MetadataImage, LoaderManifest)` overrides.

**Why it matters:**
- The rule will never go green without rewriting Kafka.
- It will be silenced (deleted, allowEmptyShould, or `// TODO`) and that silencing makes future genuine `metadata→image` over-reach undetectable.
- It also signals that the "Consensus" layer model is wrong: `image` is a *higher* abstraction than `metadata` (it materialises metadata records into a queryable snapshot), not a lower one.

**How to fix it:**
Delete the rule and, if directionality really matters, encode the *opposite* direction:

```java
// DELETE: metadata_must_not_depend_on_image  (it's backwards)

@ArchTest
public static final ArchRule image_must_not_depend_on_metadata_publisher_internals =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.image..")
        .and().resideOutsideOfPackage("org.apache.kafka.image.publisher..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.metadata.publisher..")
        .because("Inferred from package naming. The image layer materialises metadata records " +
                 "into a queryable snapshot; image core types must not depend on metadata.publisher " +
                 "implementation classes (publishers are consumers of the image, not producers).");
```

Also consider re-ordering the layered hierarchy so that within "Consensus", `image` sits above `metadata` (or split Consensus into two sub-layers: `metadata-records` and `image-snapshot`).

---

### MOD-01 — The strict 5-layer model cannot describe Kafka's real coupling

```
SEVERITY: CRITICAL
Category: Wrong Layer / Modelling Error
Affected Rule / Constraint: kafka_layered_architecture (821 remaining violations)
```

**What is wrong:**
After MAP-02's server-split absorbed ~1,200 false positives from Round 1, **821 layered violations remain in Round 2**, and they fall into a small number of cross-layer patterns that are intrinsic to Kafka's design. Sampled from the surefire report:

| Pattern | Example | Violation flavor |
|---|---|---|
| Core implements Consensus SPI | `server.config.DefaultSupportedConfigChecker` implements `metadata.SupportedConfigChecker` | Core → Consensus |
| Core implements Storage SPI | `server.log.remote.storage.RemoteLogManager` implements `storage.internals.log.AsyncOffsetReader` | Core → Storage |
| Server uses API callback contract | `server.share.persister.PersisterStateManagerHandler`, `server.transaction.AddPartitionsToTxnHandler` implement `clients.RequestCompletionHandler` | Server → API |
| Storage exposes Consensus types | `snapshot.SnapshotReader` returns `Iterator<raft.Batch<T>>` | Storage → Consensus |
| Core uses API DTOs | `common.requests.IncrementalAlterConfigsRequest` calls `clients.admin.AlterConfigOp.opType()`, `common.requests.DescribeConfigsResponse$ConfigSource` parameter `clients.admin.ConfigEntry$ConfigSource` | Core → API |
| Server runtime uses client library | `server.NodeToControllerChannelManagerImpl`, `server.BrokerLifecycleManager`, `server.AssignmentsManager` reference `clients.KafkaClient`, `clients.ClientResponse`, `clients.ApiVersions`, `clients.ManualMetadataUpdater` | Server → API |
| Consensus uses client library | `raft.KafkaNetworkChannel` references `clients.KafkaClient` | Consensus → API |
| Server uses high-level Kafka client | `server.log.remote.metadata.storage.ConsumerManager` instantiates `clients.consumer.KafkaConsumer`; `ProducerManager` instantiates `clients.producer.KafkaProducer` | Server → API |
| Consensus exposes API DTO enum | `image.ScramImage` parameter `Map<clients.admin.ScramMechanism, …>`; `controller.ScramControlManager` parameter `clients.admin.ScramMechanism` | Consensus/Server → API |

These are not bugs in Kafka. They reflect three architectural facts that the layered rule's `whereLayer().mayOnlyBeAccessedBy(...)` cannot model:

1. **Inverted SPIs.** Many Kafka modules use the Service Provider pattern: a low layer defines an interface, a higher layer provides the default implementation that the low layer wires in via `ServiceLoader` or constructor injection (`SupportedConfigChecker`, `RemoteStorageManager`, `Authorizer`, `MetadataPublisher`, `AsyncOffsetReader`). The dependency at the type level points "up" even though the runtime control flow goes "down".
2. **Shared DTO types.** `clients.admin.*` enums (`AlterConfigOp$OpType`, `ConfigEntry$ConfigSource`, `ScramMechanism`, `FeatureUpdate$UpgradeType`) are used as wire-format primitives by `common.requests.*` serialization code, by the controller, and by the metadata image. They sit in `clients.admin` for historical reasons but logically belong in `common`.
3. **Outbound RPC via client library.** The broker initiates RPCs *to other brokers* using its own client library (`KafkaClient`, `NetworkClient`, `ManualMetadataUpdater`). That makes the server runtime depend on `clients` even though the conceptual layering says clients sit above the broker.

**Why it matters:**
The 821-violation rule cannot ever go green without one of:
- a wholesale restructuring of the layer model (recommended);
- a 50+ entry `ignoreDependency(...)` allowlist that would itself be unreviewable; or
- changes to Apache Kafka's source code.

Without action, the layered rule becomes a permanent red banner in CI that engineers will train themselves to ignore — defeating the entire purpose of the test.

**How to fix it:**
Adopt a coarser, multi-axis model that matches Kafka's real modular layout. Two concrete proposals:

(a) **Drop `kafka_layered_architecture` entirely** and replace with module-pair `noClasses()` rules expressing the constraints that *do* hold (the file already has many of these — Storage must not depend on Streams, Streams must not depend on Connect, etc.).

(b) **Replace the linear hierarchy with a slice-based "no module cycles" check** plus targeted `noClasses()` rules:

```java
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@ArchTest
public static final ArchRule top_level_modules_must_not_form_cycles =
    slices()
        .matching("org.apache.kafka.(*)..")
        .namingSlices("$1")
        .should().beFreeOfCycles()
        .ignoreDependency(/* see allowlist below */)
        .because("Kafka's modules use SPI inversion and shared-DTO patterns that prevent a strict " +
                 "linear layering. The minimum invariant is that no two modules form a dependency " +
                 "cycle. Targeted noClasses() rules below encode the asymmetric constraints.");
```

If you keep `layeredArchitecture()`, then at minimum add the following dependency-inversion exemptions:

```java
.layer("Core").definedBy(/* … */, "org.apache.kafka.clients.admin..")  // shared DTO types
// Then expose the actual high-level admin API via a separate slice:
.layer("API").definedBy(
    "org.apache.kafka.clients..",
    "org.apache.kafka.connect..",
    "org.apache.kafka.streams..",
    "org.apache.kafka.tools..",
    "org.apache.kafka.shell..")
// Note: clients.admin would then be in BOTH Core and API since it's a sub-package of clients;
// ArchUnit's first-match rule resolves this correctly.
```

…and add `.ignoreDependency(...)` for the SPI inversions:

```java
// SPI: low layer defines the interface, higher layer provides the impl
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.config.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.log.remote.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."))
// Server runtime initiates outbound RPCs via the client library
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.server.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.."))
// Snapshot exposes raft batches through its iterator API
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.snapshot.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.raft.."))
```

Even option (b) only works after acknowledging that `clients.admin` is misplaced — that is the single biggest source of remaining violations.

---

### REGR-03 — `tools_must_not_depend_on_connect` was deleted entirely

```
SEVERITY: HIGH
Category: Coverage Gap (regression introduced by Review #1)
Affected Rule / Constraint: tools_must_not_depend_on_connect (now absent)
```

**What is wrong:**
Review #1 FP-01 recommended scoping the rule to exclude only the `ConnectPluginPath` family. The generator instead deleted the rule entirely. The current file has zero rule preventing any tool from importing `org.apache.kafka.connect..`. Verified: `grep -c "tools_must_not_depend_on_connect" ArchitectureEnforcementTest.java` → **0**.

**Why it matters:**
Today the only tool depending on Connect is `ConnectPluginPath` and `ManifestWorkspace` (the `connect-plugin-path` CLI). If Apache Kafka adds another tool tomorrow — say a `consumer-groups-with-connect-status` utility — that quietly imports `connect.runtime.rest.*`, no rule catches it. The whole point of FP-01 was to *narrow* the rule, not to remove it.

**How to fix it:**
Re-add the rule with an exception for the documented ConnectPluginPath family:

```java
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

@ArchTest
public static final ArchRule tools_must_not_depend_on_connect_except_plugin_path =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.tools..")
        .and(DescribedPredicate.describe(
            "are not part of the ConnectPluginPath CLI family",
            (JavaClass clazz) ->
                !clazz.getName().startsWith("org.apache.kafka.tools.ConnectPluginPath")
             && !clazz.getName().startsWith("org.apache.kafka.tools.ManifestWorkspace")))
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.connect..")
        .because("Inferred from package naming. The connect-plugin-path CLI " +
                 "(ConnectPluginPath, ManifestWorkspace) is the documented exception: " +
                 "its sole purpose is to inspect Kafka Connect plugin classpaths. All " +
                 "other tools must remain decoupled from the Connect runtime.");
```

---

### MOD-02 — `clients.admin` is used as a Core DTO library, not as an API surface

```
SEVERITY: HIGH
Category: Wrong Layer
Affected Rule / Constraint: kafka_layered_architecture (a large fraction of the 821 violations);
                            no fine-grained rule covers common→clients.admin
```

**What is wrong:**
The 5-layer model puts all of `org.apache.kafka.clients..` in the API layer, but `clients.admin` is split-purpose:
- `clients.admin.KafkaAdminClient`, `clients.admin.AdminClient`, `clients.admin.Config`, etc. — high-level admin API (correctly API).
- `clients.admin.AlterConfigOp$OpType`, `clients.admin.ConfigEntry$ConfigSource`, `clients.admin.ConfigEntry$ConfigType`, `clients.admin.FeatureUpdate$UpgradeType`, `clients.admin.ScramMechanism` — wire-format enums consumed by `common.requests.*` serialization code, by `controller.ScramControlManager`, and by `image.ScramImage`. These are **shared DTO types** the rest of the codebase needs to see.

The current rule treats every `clients.admin` reference as a layering violation, accounting for ~10 of the layered-rule violation paragraphs (and many more individual lines).

**Why it matters:**
- No fine-grained rule encodes the constraint "`common.requests` must not depend on `clients.admin`" today, so removing or deleting the layered rule would silently allow unrelated `common→clients.admin` couplings without anyone noticing.
- If `clients.admin` is treated as Core, then *real* upward couplings (e.g., `common.requests` referencing `clients.admin.KafkaAdminClient` directly) would still pass. There is no clean split without restructuring.

**How to fix it:**
Either (a) accept the misplacement and explicitly carve `clients.admin.*` out of the upward checks:

```java
.layer("CoreSharedDtos").definedBy("org.apache.kafka.clients.admin..")
// then in the layered rule:
.whereLayer("API").mayNotBeAccessedByAnyLayer()  // unchanged
.whereLayer("CoreSharedDtos").mayOnlyBeAccessedByLayers("Core", "Storage", "Consensus", "Server", "API")
```

…or (b) keep the original layering and add a finer rule narrowing what's allowed:

```java
@ArchTest
public static final ArchRule core_may_only_depend_on_clients_admin_dto_types =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.common..")
        .should().dependOnClassesThat().resideInAPackage("org.apache.kafka.clients.admin..")
        .andShould().dependOnClassesThat()
            .doNotHaveSimpleName("KafkaAdminClient")
            .andShould().dependOnClassesThat()
            .doNotHaveSimpleName("AdminClient")
        .because("…");
```
(b) is fragile; (a) is the honest fix.

---

### FP-NEW-01 — `metadata_must_not_depend_on_server_runtime` flags real KIP-500 wiring

```
SEVERITY: HIGH
Category: Overly Broad
Affected Rule / Constraint: metadata_must_not_depend_on_server_runtime (4 violations)
```

**What is wrong:**
The 4 remaining violations are all in `metadata.authorizer.*` referencing `controller.ControllerRequestContext`:

```
Method <metadata.authorizer.AclMutator.createAcls(controller.ControllerRequestContext, java.util.List)> has parameter of type <controller.ControllerRequestContext> in (AclMutator.java:0)
Method <metadata.authorizer.AclMutator.deleteAcls(controller.ControllerRequestContext, java.util.List)> has parameter of type <controller.ControllerRequestContext> in (AclMutator.java:0)
Method <metadata.authorizer.ClusterMetadataAuthorizer.createAcls(server.authorizer.AuthorizableRequestContext, java.util.List)> calls constructor <controller.ControllerRequestContext.<init>(server.authorizer.AuthorizableRequestContext, java.util.OptionalLong)> in (ClusterMetadataAuthorizer.java:106)
Method <metadata.authorizer.ClusterMetadataAuthorizer.deleteAcls(server.authorizer.AuthorizableRequestContext, java.util.List)> calls constructor <controller.ControllerRequestContext.<init>(server.authorizer.AuthorizableRequestContext, java.util.OptionalLong)> in (ClusterMetadataAuthorizer.java:146)
```

In KIP-500 the `metadata.authorizer.*` package contains the **controller-side ACL implementations** (`StandardAuthorizer`, `AclCache`, `AclMutator`), and they are deliberately wired into the controller's request handling via `ControllerRequestContext`. This is part of the controller stack, not an upward dependency violation.

**Why it matters:**
- 4 violations on a rule whose whole rationale ("metadata is a low-level Consensus-layer component") is contradicted by the actual code organisation.
- Suggests `metadata.authorizer.*` should either be reclassified as part of the controller (Server) layer, or the rule should explicitly carve out the authorizer subpackage.

**How to fix it:**
Add an exemption or move the package:

```java
@ArchTest
public static final ArchRule metadata_must_not_depend_on_server_runtime =
    noClasses()
        .that().resideInAPackage("org.apache.kafka.metadata..")
        .and().resideOutsideOfPackage("org.apache.kafka.metadata.authorizer..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.apache.kafka.server",
            "org.apache.kafka.server.share..",
            "org.apache.kafka.server.transaction..",
            "org.apache.kafka.controller..",
            "org.apache.kafka.network..")
        .because("Inferred from package naming. Metadata is a low-level Consensus-layer component. " +
                 "Exception: metadata.authorizer.* contains the controller-side ACL implementation " +
                 "(StandardAuthorizer, AclMutator, ClusterMetadataAuthorizer) which is deliberately " +
                 "wired into the controller's request handling pipeline.");
```

---

### LAY-NEW-01 — `every_production_class_must_be_in_a_layer` allowlist is structurally tied to the layered rule

```
SEVERITY: MEDIUM
Category: Maintainability / Structural
Affected Rule / Constraint: every_production_class_must_be_in_a_layer
```

**What is wrong:**
The new every-class guard's allowlist is hand-copied from the layered rule's layer definitions. The two will drift. Today it already uses a coarser `org.apache.kafka.server..` glob even though the layered rule splits server into Core sub-packages and Server runtime sub-packages — so the guard accepts classes the layered rule may not (e.g., a hypothetical class in `org.apache.kafka.server.foo` that's not in any defined Core or Server sub-package would pass the guard but be silently exempt from layered checks).

**Why it matters:**
If a future Kafka release adds `org.apache.kafka.server.middleware..` or `org.apache.kafka.server.observability..`, the guard rule will pass but the layered rule will silently treat those classes as out-of-layer. The whole point of the guard (LAY-01) was to prevent this.

**How to fix it:**
Generate the allowlist programmatically from the layer definitions. ArchUnit doesn't expose the layer-definition list directly, so introduce a constant:

```java
private static final String[] ALL_LAYER_PACKAGES = {
    // Core
    "org.apache.kafka.common..", "org.apache.kafka.security..",
    "org.apache.kafka.config..", "org.apache.kafka.deferred..", "org.apache.kafka.logger..",
    "org.apache.kafka.queue..", "org.apache.kafka.timeline..",
    "org.apache.kafka.server.common..", "org.apache.kafka.server.util..",
    "org.apache.kafka.server.metrics..", "org.apache.kafka.server.fault..",
    "org.apache.kafka.server.authorizer..", "org.apache.kafka.server.immutable..",
    "org.apache.kafka.server.config..", "org.apache.kafka.server.record..",
    "org.apache.kafka.server.storage.log..", "org.apache.kafka.server.log.remote.storage..",
    // Storage / Consensus / Server / API …
};

// Use ALL_LAYER_PACKAGES in BOTH layeredArchitecture().layer(...).definedBy(...)
// and in every_production_class_must_be_in_a_layer.should().resideInAnyPackage(...).
```

---

### LAY-NEW-02 — `metadata.properties` ignoreDependency uses bare-package match for ConfigRepository

```
SEVERITY: LOW
Category: Semantic Error
Affected Rule / Constraint: kafka_layered_architecture (ignoreDependency clauses)
```

**What is wrong:**
Line 219–220 reads:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.metadata"))
```

`resideInAPackage("org.apache.kafka.metadata")` matches classes **directly** in that package only — *not* sub-packages. This is intentional here (the goal is to whitelist `ConfigRepository` which lives directly in `org.apache.kafka.metadata`), but it's an undocumented subtlety that future maintainers will mistake for a typo and "fix" by adding `..`. If they do, the broader `metadata..` carve-out will accidentally suppress all of MAP-03's other intended violations.

**Why it matters:**
Low-severity — works today, but is a tripwire.

**How to fix it:**
Add an inline comment and prefer the explicit class predicate:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    // Bare package on purpose: matches ONLY the ConfigRepository SAM in
    // org.apache.kafka.metadata, NOT classes in org.apache.kafka.metadata.<sub>.
    JavaClass.Predicates.belongToAnyOf(
        org.apache.kafka.metadata.ConfigRepository.class))   // requires the class to be on the test classpath
```
…or, if compile-time class reference is not desired:

```java
.ignoreDependency(
    JavaClass.Predicates.resideInAPackage("org.apache.kafka.storage.."),
    DescribedPredicate.describe(
        "is org.apache.kafka.metadata.ConfigRepository (top-level interface only)",
        (JavaClass c) -> "org.apache.kafka.metadata.ConfigRepository".equals(c.getName())))
```

---

### COV-NEW-01 — Five new intra-API rules are likely vacuous in practice

```
SEVERITY: LOW
Category: Vacuous Rule (suspected)
Affected Rule / Constraint: streams_must_not_depend_on_tools, streams_must_not_depend_on_shell,
                            connect_must_not_depend_on_tools, connect_must_not_depend_on_shell,
                            tools_must_not_depend_on_shell, shell_must_not_depend_on_tools
```

**What is wrong:**
Each of these rules passed in Round 2 — but in real Kafka, the streams/connect modules don't depend on tools/shell anyway (the dependency runs the *other* way: tools/shell tend to depend on streams/connect). They're future-proofing rules, not enforcement-of-existing-invariants rules.

**Why it matters:**
- They aren't wrong, just low-value. Worth keeping as guards, but mark them so a maintainer doesn't waste time investigating "why doesn't this rule ever fire?"
- If the test classpath is missing, e.g., the streams-tools artifact, these rules become silently empty (`failOnEmptyShould` will catch the `that()` side, but not the `should()` side).

**How to fix it:**
Annotate the `because()` clauses to make the future-proofing intent explicit:

```java
.because("Inferred from package naming. FUTURE-PROOFING ONLY: Streams does not currently depend " +
         "on tools, but a future Streams feature that imports a tool's helper class would create " +
         "an unintended sibling coupling. This rule guards against that regression.");
```

---

### TRANS-NEW-01 — `clients_must_not_depend_on_streams` and `clients_must_not_depend_on_connect` are guards, not enforced today

```
SEVERITY: LOW
Category: Maintainability
Affected Rule / Constraint: clients_must_not_depend_on_streams, clients_must_not_depend_on_connect
```

**What is wrong:**
Both pass in Round 2 because real Kafka's `clients` module has no such dependency. Same status as COV-NEW-01: useful future-proofing, no current-state enforcement.

**Why it matters:**
Same as COV-NEW-01 — keep them, label them.

**How to fix it:**
Same as COV-NEW-01.

---

### REA-NEW-01 — Honest provenance everywhere ✅ (positive finding)

```
SEVERITY: LOW
Category: (not a defect — confirmation that REA-01 was successfully addressed)
Affected Rule / Constraint: ALL because() clauses
```

**What is right:**
Every `.because("…")` clause in Round 2 now starts with "Inferred from package naming." and acknowledges the PDF does not document the constraint. The architectural rationale that follows is clearly labelled as inferred. This is exactly what REA-01 asked for — the test class no longer claims false documentary authority.

**Why it matters:**
Future maintainers reading any failing rule will know to validate against KIPs and design.html before treating the rule as authoritative. The single biggest "lying to the engineer" defect from Round 1 is fixed.

---

### SCOPE-NEW-01 — `TrogdorAndJmhExclusion` is wired correctly ✅ (positive finding)

```
SEVERITY: LOW
Category: (not a defect — confirmation that SCOPE-01 was successfully addressed)
Affected Rule / Constraint: TrogdorAndJmhExclusion + @AnalyzeClasses importOptions
```

**What is right:**
The dead `KAFKA_CLASSES` field is gone. `TrogdorAndJmhExclusion` is a public static inner class implementing `ImportOption`, wired into `@AnalyzeClasses(importOptions = {…, TrogdorAndJmhExclusion.class})`. The test file's class-loading scope now actually reflects the documented exclusions.

**Why it matters:**
The Round 1 surprise — that the JMH/Trogdor exclusions in the dead `KAFKA_CLASSES` field were never applied — is fixed. The exclusions now work.

---

## Severity Roll-up

| Severity | Count | Findings |
|----------|------:|----------|
| CRITICAL | 2 | REGR-01, MOD-01 |
| HIGH     | 3 | REGR-02, REGR-03, MOD-02, FP-NEW-01 (counts as 4 in the table) |
| MEDIUM   | 1 | LAY-NEW-01 |
| LOW      | 5 | LAY-NEW-02, COV-NEW-01, TRANS-NEW-01, REA-NEW-01 ✅, SCOPE-NEW-01 ✅ |

Counting strictly: **2 CRITICAL, 4 HIGH, 1 MEDIUM, 4 LOW** = 11 findings total. Two of the LOW findings are positive confirmations rather than defects.

**Bottom line:** Round 2 is a clear improvement (1,142 fewer violations, 5 fewer failing tests, all `because()` clauses now honest, JMH/Trogdor exclusions actually work). But two findings are *regressions caused by Review #1* — VAC-02 over-claimed package emptiness and orphaned 44 real classes; NAR-02 inverted the metadata↔image direction. After reverting those two, the file would be at 2 failures (the layered rule and `metadata_must_not_depend_on_server_runtime`), both of which require either Kafka source changes or ignoreDependency allowlists. The strict 5-layer model is fundamentally too rigid for Kafka's SPI-and-shared-DTO-heavy architecture (MOD-01); a slice-based "no module cycles" approach is the right long-term direction.
