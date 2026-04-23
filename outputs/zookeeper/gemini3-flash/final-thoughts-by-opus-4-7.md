# Final Thoughts — Apache ZooKeeper ArchUnit Rules

Reviewer: opus-4-7
Subject: `outputs/zookeeper/gemini3-flash/ArchitectureEnforcementTest.java`
Iteration terminating the loop: analysis4 (`Results: 0 mapping error` after patch)
Ground truth documents consulted: `inputs/java/3_apache_zookeeper.pdf`, `inputs/java/3_apache_zookeeper.txt`

---

## Verdict

The rule file and the codebase have reached a **fixed point** where
all four `@ArchTest` rules pass, every `org.apache.zookeeper.*`
top-level package is assigned to exactly one layer, and every
layer-crossing edge that appeared over four iterations has either
been accepted as a documented carve-out or refactored out of the
mapping.

That is a strictly weaker claim than "the rule file matches the
documentation." Below is the honest calibration.

---

## What I am confident about

1. **All four `@ArchTest` rules pass** against the actual imported
   classpath.
2. **The single explicit prohibition in the PDF** — "clients and
   servers communicate only over the TCP wire protocol" (§1.1 /
   §1.7) — is encoded and enforced by
   `whereLayer("Server").mayNotBeAccessedByAnyLayer()` plus the
   `public_api_must_not_leak_server_types` check.
3. **Every top-level package** listed in
   `inputs/java/3_apache_zookeeper.txt` is now assigned to exactly
   one layer. No package is architecturally invisible to the check.
4. **The four `ignoreDependency` carve-outs** are
   **class-pair narrow**, not package-wide:
   - `ReconfigCommand → server.quorum..`
   - `ZooTrace → server.quorum.. / server.Request`
   - `ConfigUtils → server.quorum.QuorumPeer*`
   - `X509Util → server.auth.ProviderRegistry`

   Because they are class-pair scoped, they cannot silently
   whitewash new violations added in adjacent classes.

5. **All previously-visible false positives have been eliminated**
   — 180 → 160 → 30 → 1 → 0 across four iterations, with every
   intermediate residue traced to a specific mapping cause and fixed
   surgically rather than by blanket suppression.

---

## What I am NOT confident about

### 1. The PDF is thin on architectural specifics

The ZooKeeper PDF is a high-level design document covering the data
model, public API, Zab protocol, and two reference recipes. It does
NOT specify:

- Whether `Tools` may access `Recipes` — **I allowed it.**
- Whether `Recipes` may access `Server` directly — **I forbade it.**
- Whether `PublicApi` may reach back into `Client` implementation
  packages — **I allowed it.**
- The correct layer for `audit`, `jmx`, `metrics`, `compat`,
  `compatibility` — **I folded them into `Support` by naming
  convention.**
- Whether inter-recipe dependencies are forbidden — **I enforced
  `recipe_modules_are_independent` by inference from §1.1's
  "independent coordination primitives" phrasing**, not from an
  explicit prohibition.

### 2. Several labels are my inventions, not PDF terms

`Support`, `Tools`, `PublicApi` (as defined here), and the entire
ASCII diagram in the Javadoc are a reasonable *reading* of the PDF,
but they are a reading. A different reviewer could plausibly split
or merge these layers differently and still honour §1.1 / §1.7.

### 3. The four carve-outs are rationalised, not documented

Each carve-out's `because(...)` clause uses phrasing like "admin CLI
reconfig", "shared-trace-utility bleed-through", "client-config
bridging", "SSL bootstrap / SPI registration". These are my
defensible readings of the call sites, but the PDF does not mention
any of the four edges by name. I cannot point to a sentence in the
documentation that *authorises* them — only to the fact that none
of the four edges crosses the documented wire-protocol boundary.

### 4. Two `@ArchTest` rules have no explicit PDF backing

- `recipe_modules_are_independent` — inferred from §1.1 phrasing
  about "independent coordination primitives"; the PDF does not
  specifically state that `lock`, `queue`, and `leader` may not
  share code.
- `test_package_must_not_ship_in_production` — a defensive tripwire
  for `src/main/java/**/test/*` promotion; the PDF says nothing
  about test scope.

Both rules are cheap insurance; neither is a strict translation of
documentation.

### 5. "Zero violations" is scoped to the imported classpath

The Maven pom imports specific module JARs under
`additionalClasspathElements`. If a future build adds a module that
is currently excluded (e.g., `zookeeper-contrib`, a new subproject,
or integration-test helpers), classes there could produce new edges
the rule file never saw. The passing verdict is conditional on the
current import scope.

### 6. ~15 individual classes are classified by judgment

The rule file embeds per-class shared-utility classifications for:
`ZooKeeperThread`, `ByteBufferInputStream`, `ZooTrace`, `ExitCode`,
`EphemeralType` (+ `$1`, `Emulate353`), `ConfigUtils`,
`VerifyingFileFactory` (+ `$Builder`), `KerberosUtil`,
`KerberosName` (+ `$Rule`, `$NoMatchingRule`), `PathParentIterator`,
the entire `server.metric..` subpackage, plus the root-tool
overrides `ZooKeeperMain` (+ nested types) and `JLineZNodeCompleter`.

Each of these is based on reading the class name and observed call-
graph behaviour. The PDF names none of them. A ZooKeeper core
committer could reasonably disagree with any single classification
(for instance: is `KerberosUtil` truly Support, or should it be
Server with a carve-out for `KerberosName`?). I cannot settle that
debate from the PDF alone.

---

## The strongest claim I can actually support

> **The rule file, the codebase, and my reading of the
> documentation all agree at a fixed point.**

This is a three-way consistency statement. It is strictly weaker
than "the rule file matches the documentation" (which would require
a ground truth richer than the PDF provides), but it is the
strongest claim that four iterations of test execution actually
justify.

Two weaker but also-true corollaries:

- **Every edge that exists in the codebase and crosses a layer
  boundary has either been explicitly documented (four carve-outs
  with `because(...)` rationale) or moved inside a layer via
  reclassification.** There is no silent suppression.
- **No edge that the PDF explicitly forbids (§1.1 / §1.7 wire-
  protocol boundary) is permitted by the current rules.** The
  `whereLayer("Server").mayNotBeAccessedByAnyLayer()` plus
  `public_api_must_not_leak_server_types` pair will catch any
  violation of that specific constraint.

---

## What would increase my confidence to "yes, definitely"

In descending order of impact:

1. **Review by a ZooKeeper PMC / core committer.** The shared-class
   list (§ "What I am not confident about" #6) and the four carve-
   outs (#3) are the two places where a core-committer sanity check
   would convert judgment into authority. A 30-minute review of
   those two sections of the rule file is all this would take.
2. **An architecture-rationale document that supplements the PDF.**
   Any statement of the form "admin CLIs may reach into server
   quorum packages for reconfig purposes" or "authentication
   providers are server-only; registration is shared" would
   directly authorise the carve-outs. Short of that, a design-notes
   or ADR-style document in the repo would serve the same purpose.
3. **Extending the import scope** to the full build output of
   every ZooKeeper module, verifying that the rule file still
   passes. This addresses confidence gap #5.
4. **Running the same four iterations on a mutated version of the
   codebase** (deliberately introduce a known violation; verify it
   fires; remove it; verify it clears). This tests the *rule file's
   sensitivity*, not just its current false-positive rate.

None of these are required to ship the current rule file; all four
would upgrade the confidence level from "fixed point reached" to
"formally validated".

---

## Recommendation

**Ship the current rule file with the following caveats in the
Javadoc header:**

- A note that the six-layer partition (`Support`, `Server`,
  `PublicApi`, `Client`, `Recipes`, `Tools`) is an operational
  encoding, not a direct PDF quote.
- A note listing the four class-pair `ignoreDependency` carve-outs
  and inviting a PMC review if anyone disagrees with the rationale.
- A pointer to `inputs/java/3_apache_zookeeper.pdf` §1.1 / §1.7 as
  the single documented constraint the rules exist to enforce.

These three notes make the file's relationship to the documentation
honest about its own limits, so future maintainers don't mistake
"test passes" for "architecture validated".
