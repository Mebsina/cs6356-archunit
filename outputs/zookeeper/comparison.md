# In-Depth Architectural Enforcement Comparison: Apache ZooKeeper

## 1. Executive Summary

The model **`sonnet-4-6`** produced the conclusively superior architectural enforcement suite. 

**Key Differentiators:** 
* Its sophisticated conceptualization of a **"Protocol" layer** isolates shared TCP wire-format records from both client and server tiers. 
* This perfectly captures the ground truth architectural intent. 
* In contrast, **`gemini3-flash`** struggled with ArchUnit DSL hallucinations and produced a more brittle, less accurate layer mapping.

## 2. Architectural Layer Model Analysis

The architectural models defined by the two LLMs differ significantly in depth and precision.

### Model: `gemini3-flash`
* **Architecture:** Defined a flat, 6-layer architecture (`Support`, `Server`, `PublicApi`, `Client`, `Recipes`, and `Tools`).
* **Package Handling:** Struggled with heterogeneous packages. Relied on basic predicates like `simpleName` and `resideInAPackage`.
* **Accuracy:** Defined `public_api_classes` simply by excluding CLI tools from the root package. This inaccurately swept server-internal utilities into the public API layer. 
* **Dependencies:** Failed to isolate third-party dependencies from its scan scope, missing the opportunity to use advanced ArchUnit import options.

### Model: `sonnet-4-6`
* **Architecture:** Defined a nuanced 7-layer lattice (`Infrastructure`, `Protocol`, `Monitoring`, `Server`, `Client`, `Admin`, `Cli`, and `Recipes`).
* **Package Handling:** Accurately mapped the ground truth by identifying the root `org.apache.zookeeper` package as heterogeneous.
* **Accuracy:** Employed precise regular expressions to isolate specific cross-tier API objects into the `Protocol` layer. 
* **Dependencies:** Properly excluded standalone, build-only tools by implementing a custom `ExcludeStandaloneTools` class that implements `ImportOption`. This completely isolated development utilities from application layers. 
* **Advanced Features:** Implemented `rootPackageName(JavaClass)` to unwrap JVM array types (e.g., `[Lorg.apache.zookeeper.AddWatchMode;`), demonstrating advanced handling of complex bytecode artifacts.

```java
private static final DescribedPredicate<JavaClass> ROOT_PROTOCOL_TYPES =
        DescribedPredicate.describe(
                "root-package protocol / public-API / cross-tier types",
                c -> rootPackageName(c).equals("org.apache.zookeeper")
                  && rootName(c).matches(
                        "org\\.apache\\.zookeeper\\."
                      + "(Watcher(\\$.*)?|WatchedEvent|AddWatchMode|CreateMode"
                      + "|CreateOptions(\\$.*)?"
                      + "|AsyncCallback(\\$.*)?|KeeperException(\\$.*)?|ClientInfo|StatsTrack"
                      + "|Op(\\$.*)?|OpResult(\\$.*)?"
                      + "|ZooDefs(\\$.*)?|MultiOperationRecord(\\$.*)?"
                      + "|MultiResponse|DeleteContainerRequest|Quotas"
                      + "|DigestWatcher|Login(\\$.*)?|ClientWatchManager"
                      + "|SaslClientCallbackHandler(\\$.*)?"
                      + "|SaslServerPrincipal(\\$.*)?"
                      + "|Environment(\\$.*)?|ZookeeperBanner"
                      + "|Shell(\\$.*)?|Version(\\$.*)?)"));
```

## 3. Alignment with Ground Truth (Primary Dimension)

Alignment with the official Apache ZooKeeper documentation separates the two models most starkly.

### Model: `sonnet-4-6`
* **Intent Capture:** Captured the deep architectural intent of the documentation (Sections §1.1 and §1.7), which mandates that clients and servers communicate exclusively over the TCP wire protocol. 
  **Example Rule:** Constructed a distinct `Protocol` layer containing `ROOT_PROTOCOL_TYPES` (shared wire-format records) to serve as a neutral boundary.
* **Boundary Enforcement:** Enforced a strict boundary via the `Protocol` layer, where both the `Client` and `Server` tiers depend on shared protocol records, but never on each other. 
  **Example Rule:** `.whereLayer("Server").mayOnlyAccessLayers("Infrastructure", "Protocol", "Monitoring", "Server")` explicitly omits the `Client` layer.
* **Rule Codification:** Explicitly codified Section §1.8 by implementing the `recipes_must_not_depend_on_zookeeper_internals` rule. This ensures recipes remain higher-order primitives built on the public API. 
  **Example Rule:** 
  ```java
  noClasses().that().resideInAPackage("org.apache.zookeeper.recipes..")
    .should().dependOnClassesThat(resideInAnyPackage("org.apache.zookeeper.server..", ...))
  ```
* **Architectural Debt:** Utilized highly targeted `.ignoreDependency()` rules pinned to exact source-to-target pairs. It explicitly documented these as "honest architectural smells" in its `.because()` clauses rather than suppressing them broadly.
  **Example Rule:**
  ```java
  .ignoreDependency(
          name("org.apache.zookeeper.cli.GetConfigCommand"),
          name("org.apache.zookeeper.server.util.ConfigUtils"))
  ```

### Model: `gemini3-flash`
* **Intent Capture:** Constructed rules to brute-force a passing test suite rather than honoring the documentation. 
  **Example:** It defined the `PublicApi` layer by simply excluding CLI tools from the root package, which accidentally swept server-internal types into the shared API layer just to make the test compile.
* **Boundary Enforcement:** Arbitrarily moved the `audit` package to the `Server` layer and placed `server.auth` in the `Support` layer to resolve violations. This blurred the boundaries between infrastructural utilities and core server logic. 
  **Example Rule:** 
  ```java
  .layer("Server").definedBy(resideInAnyPackage(
          "org.apache.zookeeper.server..",
          "org.apache.zookeeper.audit..")) // Audit improperly grouped with core Server
  ```
* **Architectural Debt:** Implementations were overly broad. It used package-level suppressions such as `resideInAnyPackage("org.apache.zookeeper.server.quorum..")`, which risks masking genuine future violations.
  **Example Rule:**
  ```java
  .ignoreDependency(simpleName("ReconfigCommand"),
          resideInAnyPackage("org.apache.zookeeper.server.quorum.."))
  ```

## 4. Methodological Analysis (Fix History)

The evolutionary paths documented in the respective `fix-history.md` files reveal stark differences in methodology and ArchUnit competency.

### Model: `sonnet-4-6`
* **Iterations:** Reached a flawless state in **8 generations**. 
* **Competency:** Demonstrated mastery of the ArchUnit DSL. Immediately recognized that scanning the entire classpath produced false positives from third-party libraries. 
* **Noise Reduction:** Surgically applied `.consideringOnlyDependenciesInAnyPackage("org.apache.zookeeper..")` in its fourth generation to filter out noise from the JDK and Netty. 
* **Refinement:** Fixes were progressive and precise. When confronted with false positives from cross-tier SASL configurations, it carefully extracted `ZKClientConfig` into the `Protocol` layer rather than destroying the `Client` layer's isolation.

### Model: `gemini3-flash`
* **Iterations:** Required **14 generations** to achieve a passing state. 
* **Competency:** Hindered significantly by API hallucinations. The fix history reveals that the model repeatedly hallucinated a `.mayBeAccessedByAllLayers()` method for the `LayerDependencySpecification` API, triggering multiple `cannot find symbol` compilation errors.
* **Refinement:** Frequently resorted to redefining layer definitions and reassigning packages. This indicates a trial-and-error approach rather than a systematic architectural mapping.

## 5. Final Scored Verdict

| Dimension | gemini3-flash | sonnet-4-6 |
|---|---|---|
| Architectural Layer Model Accuracy | 3/5 | 5/5 |
| Ground Truth Alignment | 3/5 | 5/5 |
| ArchUnit DSL Competency | 2/5 | 5/5 |
| Violation Resolution Methodology | 2/5 | 5/5 |
| **Total** | **10/20** | **20/20** |

**Conclusion:** 

**`sonnet-4-6`** is the unequivocally superior model for architectural enforcement. 
* Its deep understanding of the ArchUnit DSL, its ability to model complex lattice architectures, and its strict adherence to the project's ground-truth documentation make its test suite robust and highly maintainable. 
* **`gemini3-flash`**'s approach of hallucinating API methods and brute-forcing package assignments renders its output too brittle for production-grade architectural enforcement. 
* While **`gemini3-flash`** might suffice for trivially simple and perfectly layered microservices, **`sonnet-4-6`** is unequivocally required for complex, legacy codebases with established architectural debt.
