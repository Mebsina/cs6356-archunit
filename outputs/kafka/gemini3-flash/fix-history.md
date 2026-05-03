# Fix History

1. Initial test generation

2. fixed paths to compiled classes


3. Changes with review #1
Findings: Misclassification of `server..` (utilities) as high-level and `image`/`snapshot` (KRaft) as low-level Support. Phantom admin package and vacuous rules due to empty globs. Overly restrictive Server access rules blocking legitimate utility usage.
Fix: Reclassified `server..` sub-packages into Support and KRaft components into Server. Corrected admin package glob. Switched to internal-only dependency scanning. Added explicit intra-layer isolation for KRaft and Infrastructure modules.