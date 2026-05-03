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