# Fix History

1. Initial test generation

2. fixed paths to compiled classes


3. Changes with review #1
Findings: Misclassification of `server..` (utilities) as high-level and `image`/`snapshot` (KRaft) as low-level Support. Phantom admin package and vacuous rules due to empty globs. Overly restrictive Server access rules blocking legitimate utility usage.
Fix: Reclassified `server..` sub-packages into Support and KRaft components into Server. Corrected admin package glob. Switched to internal-only dependency scanning. Added explicit intra-layer isolation for KRaft and Infrastructure modules.

4. Review #2 Fixes
Findings: `server.log..` (tiered storage) incorrectly demoted to Support; belongs in Server. Six `server.*` sub-packages (`share`, `purgatory`, etc.) and four top-level packages (`config`, `queue`, etc.) were unmapped. `metadata_should_not_depend_on_controller` violated by legitimate authorizer design seam. Support->Client dependency debt identified in `common.requests` and `server.util`.
Fix: Promoted `server.log..` and unmapped `server.*` orchestrators to Server layer. Mapped remaining utility packages to Support. Added carve-out for `metadata.authorizer` in controller dependency rule. Relaxed layered rule to allow Support->Client to document historical architectural debt. Updated broker internal package list for Streams/Connect isolation.

5. Review #3 Fixes
Findings: Residual 33 violations identified as genuine architectural debt or mis-located value types. `ProducerSnapshot` generated DTOs mis-mapped as Server. `LogManager` dependency on `metadata.ConfigRepository` (SPI) and `metadata.properties..` (value types) flagged. `security.CredentialProvider` dependency on `clients.admin.ScramMechanism` (Infrastructure->Client) identified.
Fix: Mapped generated DTOs to a new `GeneratedDtos` layer and moved `metadata.properties..` to Support. Added an explicit allow-rule for the `ConfigRepository` SPI seam. Symmetrically extended Client layer access to Infrastructure to document the security-admin dependency. Refined metadata-controller carve-out to the specific `ControllerRequestContext` type.

6. Syntax Fix
Findings: Invalid ArchUnit DSL syntax in Review #3 patch. Attempted to call `.and()` on `ClassesShouldConjunction` for target class predicates, which is not supported in the fluid API.
Fix: Corrected syntax by using `dependOnClassesThat(predicate)` with combined `DescribedPredicate` objects. Added missing static imports for `resideInAPackage`, `simpleNameEndingWith`, and `not`.

7. Review #4 Fixes
Findings: Overlapping `definedBy` globs in Review #3 caused classes like `MetaProperties` and `ProducerSnapshot` to belong to multiple layers, triggering spurious Server-access violations. The explicit `ConfigRepository` SPI rule also missed the `metadata.properties..` carve-out, causing failures.
Fix: Switched to predicate-based layer definitions (`SERVER_LAYER_PREDICATE`) to explicitly subtract carve-out sub-packages from the Server layer. Propagated the `metadata.properties..` carve-out to the `storage_may_reference_metadata_only_via_spi_or_properties` rule. Added a `metadata_must_keep_config_repository_spi` guardrail rule.

8. Review #5 Fixes
Findings: `GeneratedDtos` lacked access to `Support`, causing massive false-positive violations. 12 top-level packages were unmapped. Contradiction between layered rule (allowing Application->Server) and standalone rules (blocking it). Missing rules for Streams-specific fault tolerance mentioned in documentation. Rationales cited undocumented decisions.
Fix: Added `GeneratedDtos` to `Support` access list. Mapped all 12 missing top-level packages (admin, api, network, etc.) to layers. Restricted `Server` and `Infrastructure` access to eliminate contradictions. Added `streams_must_depend_on_consumer_for_fault_tolerance` rule. Neutralized rationales to reflect topological inference. Added `.ignoreDependency` for `LogManager`->`ConfigRepository` SPI seam in the layered rule.

9. Syntax Fix (Location Import)
Findings: Missing import for `com.tngtech.archunit.core.importer.Location` in the custom `ExcludeBuildArtifacts` implementation introduced in Review #5.
Fix: Added the required `Location` import to ensure compilation.