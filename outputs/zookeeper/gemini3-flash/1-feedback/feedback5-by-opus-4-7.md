# Adversarial Review #5 — `outputs/zookeeper/gemini3-flash/ArchitectureEnforcementTest.java`

Reviewer: opus-4-7
Reviewed Model: gemini3-flash
Project: Apache ZooKeeper
Round: 5 (post round-4 revision)

---

### Executive Summary

The round-4 patch is applied in full:

- `Support` is now in `PublicApi`'s access list — fixes the last real
  false-positive source.
- `Server.mayNotBeAccessedByAnyLayer()` replaces the two-step
  `mayOnlyBeAccessedByLayers("PublicApi")` + fine-grained inversion.
- `test_package_must_not_ship_in_production` uses the correct
  `noClasses().should().resideInAPackage(...)` idiom.
- `recipes_only_depend_on_client_or_support` has been replaced by a
  `slices().matching(...).should().notDependOnEachOther()` rule that
  expresses an independent constraint (inter-recipe module isolation).
- Static import for `slices` is present, so the file compiles.

**Remaining findings are cosmetic / LOW only.**

| Prior Finding | Status |
|---------------|--------|
| R4-F01 Support → PublicApi | **Fixed** |
| R4-F02 Vacuous `haveSimpleNameNotEndingWith("")` | **Fixed** |
| R4-F03 Two-step Server isolation | **Fixed** |
| R4-F04 Redundant recipes rule | **Fixed** (repurposed to inter-recipe isolation) |

**New / remaining findings:**

- **F-R5-01 (LOW)** — The Javadoc header diagram still shows `Client (root
  + api + admin + retry)` as a single box, but the rules now split root
  into a separate `PublicApi` layer. One-paragraph diagram refresh.
- **F-R5-02 (LOW)** — `test_package_must_not_ship_in_production` is likely
  vacuous on a standard Maven build because `ImportOption.DoNotIncludeTests`
  already filters out everything under `src/test/java`. Keeping it is
  defensible (it's cheap and documents intent), but the author should know
  it will probably never fire.
- **F-R5-03 (LOW)** — Long-standing R1-F06: `graph` is still grouped with
  `cli`/`inspector` under Tools, so `graph → server` is forbidden.
  `org.apache.zookeeper.graph` is a log-visualisation webapp that legitimately
  reads server-side log/snapshot formats. Author judgement call.

**Overall verdict:** `PASS WITH WARNINGS` (up from `FAIL` in round 4).

No blocking issues remain. The file should run green against the real
zookeeper build, modulo the F-R5-03 judgement call if the `graph` module
turns out to be on the scanned classpath.

---

### Findings

```
[F-R5-01] SEVERITY: LOW
Category: Documentation Drift
Affected Rule / Constraint: Javadoc header diagram

What is wrong:
The header box labelled "Client (root + api + admin + retry)" describes
the round-2 topology where root was folded into Client. The current rules
(round 3 onward) split root into a dedicated `PublicApi` layer that sits
between the Client tower and everything it shares contract types with
(Support, Server, Recipes, Tools). The diagram no longer matches the code.

Why it matters:
A maintainer reading only the header will misunderstand why
`Server → org.apache.zookeeper.KeeperException` is legal but
`Server → org.apache.zookeeper.client.HostProvider` is not. Clarifying
the diagram to show PublicApi as its own box is a two-minute fix with
outsized readability benefit.

How to fix it:
Replace the ASCII diagram with one that surfaces PublicApi as the shared
contract spine:

```java
/**
 * Architectural enforcement tests for Apache ZooKeeper.
 *
 * ZooKeeper is logically split into client- and server-side sub-systems
 * that share only a narrow public-API contract and communicate at runtime
 * via the TCP wire protocol:
 *
 *                     +-------------------+
 *                     |    Tooling/CLI    |   cli, inspector, graph
 *                     +---------+---------+
 *                               |
 *   +-------------+     +-------v-------+
 *   |   Recipes   | --> |    Client     |   client, admin, retry
 *   |  (recipes)  |     +-------+-------+
 *   +------+------+             |
 *          |                    |
 *          v                    v
 *          +--------+------------+--------+
 *                   |  PublicApi  |                   root:
 *                   |   (shared   |          ZooKeeper, KeeperException,
 *                   |   contract) |     Watcher, CreateMode, ZooDefs, ...
 *                   +------+------+
 *                          ^
 *                          |
 *                   +------+------+
 *                   |   Server    |   server (isolated; only reaches down
 *                   +------+------+       to PublicApi and Support)
 *                          |
 *                          v
 *                   +------+------------------------------+
 *                   |             Support                 |
 *                   |  (common, util, metrics, jmx,       |
 *                   |   audit, compat, compatibility)     |
 *                   +-------------------------------------+
 *
 * Allowed edges (summary):
 *   - Support      is reachable from every layer; it reaches PublicApi.
 *   - PublicApi    is reachable from every layer; it reaches Client and Support.
 *   - Client       reaches PublicApi and Support; reachable by PublicApi,
 *                  Recipes, Tools.
 *   - Server       reaches PublicApi and Support; not reachable from any layer.
 *   - Recipes      reach Client, PublicApi, Support; reachable only by Tools.
 *   - Tools        reach everything except Server; not reachable from any layer.
 *
 * Excluded packages:
 *   - org.apache.zookeeper.test..  JUnit fixtures under src/test/java,
 *                                   excluded via DoNotIncludeTests.
 */
```

(No code change needed below the header.)
```

```
[F-R5-02] SEVERITY: LOW
Category: Likely-Vacuous Rule (defensive)
Affected Rule / Constraint: test_package_must_not_ship_in_production

What is wrong:
The rule asserts that no imported class resides in
`org.apache.zookeeper.test..`. But the importer is configured with
`ImportOption.DoNotIncludeTests`, which excludes every class compiled
under `src/test/java` (Maven) or `src/test/*` (Gradle). In practice this
is where 100% of `org.apache.zookeeper.test.*` classes live in real
ZooKeeper, so the importer never loads them, and the rule's `that()`
predicate matches the empty set.

Why it matters:
Not wrong — the rule's stated purpose is exactly "fail loudly if a fixture
ever gets promoted to production". But a reviewer doing a coverage audit
will (correctly) flag it as a rule that cannot fire on a healthy build.
That is the intended behaviour.

How to fix it:
Three options, in increasing invasiveness; no change is also defensible.

1. Leave as-is and add a one-line comment stating the rule is intentionally
   a no-op on healthy builds and exists as a tripwire:

```java
// Tripwire: should always pass on a healthy build. Would fire only if a
// test-fixture class were ever promoted from src/test/java to src/main/java.
@ArchTest
public static final ArchRule test_package_must_not_ship_in_production = ...;
```

2. Remove `ImportOption.DoNotIncludeTests` and rely on this rule plus
   package-based filtering to partition production vs. test — not
   recommended (DoNotIncludeTests correctly handles JUnit fixtures in any
   package, not just `test..`).

3. Delete the rule if the author does not want a documentation-only
   tripwire.

No action required unless the author has a preference for (3).
```

```
[F-R5-03] SEVERITY: LOW
Category: Author Judgement Call (long-standing)
Affected Rule / Constraint: layered_architecture_is_respected —
                            "Tools" groups cli + inspector + graph

What is wrong:
`org.apache.zookeeper.graph` in the ZK contrib tree is a Jetty-based
log/snapshot visualisation webapp, not a CLI tool. It legitimately reads
server-side types (`org.apache.zookeeper.server.persistence.FileTxnLog`,
etc.). Grouping it with `cli` and `inspector` under a Tools layer that is
`mayNotBeAccessedByAnyLayer()` AND cannot access Server (via
`Server.mayNotBeAccessedByAnyLayer()`) produces violations for exactly the
use case the graph module was written for.

The author has flagged this as a judgement call across rounds 1–4. It is
called out here one more time because it is now the only remaining
non-cosmetic risk.

Why it matters:
If the scanned classpath includes
`zookeeper/zookeeper-contrib/zookeeper-contrib-zooinspector/target/classes`
or the graph module's classes, the test will emit genuine violations
every time those modules import server-side types. Those violations are
probably not architectural errors — they are the graph module doing its
job.

How to fix it:
Three reasonable options:

1. Accept the current classification. Do nothing — just make sure the
   graph module is NOT on the arch-test classpath (check `pom.xml`; it
   currently is not).

2. Move `graph` into its own "Analytics" layer with server access:

```java
.layer("Tools").definedBy(
        "org.apache.zookeeper.cli..",
        "org.apache.zookeeper.inspector..")
.layer("Analytics").definedBy("org.apache.zookeeper.graph..")
.whereLayer("Tools").mayNotBeAccessedByAnyLayer()
.whereLayer("Analytics").mayNotBeAccessedByAnyLayer()
// Analytics may legitimately read server log/snapshot internals:
// NOTE: Server.mayNotBeAccessedByAnyLayer forbids this, so we'd need
//       to relax to
// .whereLayer("Server").mayOnlyBeAccessedByLayers("Analytics")
//       or drop the server isolation entirely for graph.
```

3. Exclude the graph module explicitly with an ImportOption:

```java
private static final ImportOption NO_GRAPH_MODULE = location ->
        !location.contains("/graph/") && !location.contains("/zookeeper-contrib-");

@AnalyzeClasses(
    packages = "org.apache.zookeeper",
    importOptions = { ImportOption.DoNotIncludeTests.class, NO_GRAPH_MODULE.class })
```

Option (1) is acceptable given the current `pom.xml` does not include the
graph module; option (2) is the architecturally cleanest; option (3)
hides the problem. Author's call.
```

---

### Nothing Blocking

There are no CRITICAL, HIGH, or MEDIUM findings in this round. The three
LOW items above are either cosmetic (F-R5-01), intentional no-op defense
(F-R5-02), or a long-standing judgement call that does not affect the
currently-scanned classpath (F-R5-03).

### Suggested Next Steps

1. Apply F-R5-01 diagram refresh (two minutes, no code change).
2. Decide on F-R5-03 disposition and document the decision in a
   `// NOTE: ` comment above the layered rule so future reviewers don't
   keep surfacing it.
3. Ship.

After those, this file is a legitimate reference-quality example of how
to encode the architectural intent of a PDF that is considerably thinner
than the rule set one ultimately needs. Well done.
