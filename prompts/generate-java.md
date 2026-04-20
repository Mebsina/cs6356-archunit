# Prompt: Generate ArchUnit Rules from Architecture Documentation

You are a Staff Software Engineer with deep expertise in ArchUnit and complex build tool integrations. Your task is to generate ArchUnit test rules that enforce the architectural constraints of a Java project based on the documentation and package structure provided below.

Project Name - e.g., spring-framework
Model Name - e.g., sonnet-4-6

## Environment Context

The target project is a multi-module Java project (e.g., Gradle or Maven). The compiled classes to be scanned are located in the build output directory of each module (e.g., `[module-name]/build/classes/java/main` for Gradle or `[module-name]/target/classes` for Maven).


## Architecture Documentation

[Insert Content or Path of Architecture Documentation]
Example: `arch-eval-benchmark\repos with arch_doc\2_spring-projects_spring-framework.pdf`

## Package Structure

[Insert Content or Path of Package Structure]
Example: `package-structure\java\spring-framework.txt`

## Output Format

```
output\[Project Name]\[Model Name]\ArchitectureEnforcementTest.java
output\[Project Name]\[Model Name]\pom.xml
```

## Your Task

1. Read the documentation and identify the core layers (e.g., API, Service, Repository, Infrastructure).
2. Map each package to its corresponding layer, including sub-layers such as `Integration` and `Support` where applicable.
3. Generate ArchUnit rules using the `layeredArchitecture()` API, ensuring parallel layers (like Web and DataAccess) are restricted from accessing each other.
4. **Fine-grained Intra-layer Rules**: Add specific `noClasses()` rules to enforce module-to-module constraints within layers to prevent circular dependencies (e.g., `module-a` should not depend on `module-b`).
5. Create `ArchitectureEnforcementTest.java` with these rules.
6. Create a standalone `pom.xml` in the same directory. This Maven configuration MUST:
    - Include `com.tngtech.archunit:archunit-junit5`.
    - Set `<testSourceDirectory>` to `.` for portability.
    - **CRITICAL**: Include `<additionalClasspathElements>` in the `maven-surefire-plugin` configuration that point back to the compiled classes (using relative paths like `../../../[project-root]/[module-a]/build/classes/java/main`).

## Requirements

1. Use `@ArchTest` annotations on static final fields.
2. Ensure lower layers do not depend on higher layers.
3. **Syntax Guidance**: Use `dependOnClassesThat(resideInAPackage(".."))` or `resideInAnyPackage("..", "..")` with static imports from `com.tngtech.archunit.core.domain.JavaClass.Predicates`.
4. Include a `ClassFileImporter` that imports the project's root package.
5. **Structural Integrity**: Organize the class with logical separators (comments) between different types of rules (e.g., Layered Architecture vs. Fine-grained Rules).
6. Output only the complete Java code and XML. No explanations, no markdown.
7. Every rule must have a descriptive `.because()` clause.
8. **Detailed Documentation**: Include a comprehensive header comment (Javadoc style) at the top of the file that outlines:
    - The documented layer hierarchy (from bottom to top).
    - A detailed list of excluded packages with the specific rationale for their exclusion (e.g., repackaged third-party libraries, build-only utilities).
9. Exclude implementation details from layer definitions.
10. Generate the complete code in a single pass. Do not iterate.