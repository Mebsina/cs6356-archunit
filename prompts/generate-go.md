# Prompt: Generate arch-go Rules from Architecture Documentation

You are a Staff Software Engineer with deep expertise in arch-go and Go module structures. Your task is to generate an `arch-go.yml` configuration that enforces the architectural constraints of a Go project based on the documentation and package structure provided below.

Project Name - e.g., my-go-service
Model Name - e.g., sonnet-4-6

## Environment Context

The target project is a Go module (or multi-module workspace). Package paths follow Go import conventions rooted at the module path declared in `go.mod` (e.g., `github.com/org/repo/internal/service`). arch-go is run from the repository root via `arch-go check` and operates on **source files**, not compiled artifacts — no classpath configuration is required.

## Architecture Documentation

[Insert Content or Path of Architecture Documentation]
Example: `arch-eval-benchmark\repos with arch_doc\my-go-service-architecture.pdf`

## Package Structure

[Insert Content or Path of Package Structure]
Example: `package-structure\go\my-go-service.txt`

## Output Format

```
output\[Project Name]\[Model Name]\arch-go.yml
output\[Project Name]\[Model Name]\Makefile
```

## Your Task

1. Read the documentation and identify the core layers (e.g., API/Handler, Service/Domain, Repository, Infrastructure).
2. Map each Go package path (relative to the module root) to its corresponding layer, using glob patterns (`**`) where appropriate.
3. Generate `dependenciesRules` using arch-go's YAML DSL, ensuring parallel layers (e.g., `internal/handler` and `internal/repository`) are restricted from accessing each other.
4. **Fine-grained Intra-layer Rules**: Add specific `dependenciesRules` entries to enforce package-to-package constraints within layers to prevent circular dependencies (e.g., `internal/service/orders` must not depend on `internal/service/billing`).
5. Add `contentsRules` to enforce structural conventions per layer (e.g., interface definitions belong in domain packages, not infrastructure packages).
6. Add `namingRules` where the documentation specifies naming conventions (e.g., all types in `internal/repository/**` implementing a `Repository` interface must be suffixed with `Repository`).
7. Add `functionsRules` where the documentation specifies complexity or return-value constraints.
8. Create a standalone `Makefile` in the same directory. This MUST:
   - Include an `arch-check` target that installs arch-go if not present (`go install github.com/fdaines/arch-go@latest`) and runs `arch-go check` from the project root.
   - Accept an optional `PROJECT_ROOT` variable so the Makefile is portable across machines (e.g., `make arch-check PROJECT_ROOT=../../..`).

## Requirements

1. Use arch-go's native YAML DSL — no custom Go test files.
2. Ensure lower layers do not depend on higher layers (dependency arrows point inward/downward only).
3. **Syntax Guidance**: Use glob patterns consistent with arch-go's package matching:
   - `github.com/org/repo/internal/service/**` matches all sub-packages of `service`.
   - `**` alone matches all packages in the module.
   - `internal` and `external` blocks under `shouldOnlyDependsOn` / `shouldNotDependsOn` control internal module packages vs. third-party modules respectively.
4. Include a top-level `description` field in the YAML summarising the enforced architecture.
5. **Structural Integrity**: Organize the YAML with YAML comments (`#`) acting as section separators between rule types (`dependenciesRules`, `contentsRules`, `namingRules`, `functionsRules`).
6. Output only the complete YAML and Makefile. No explanations, no markdown.
7. Every rule block must include a `description` field with a human-readable rationale (arch-go surfaces this on violation).
8. **Detailed Documentation**: Include a comprehensive YAML comment block at the top of `arch-go.yml` that outlines:
   - The documented layer hierarchy (from bottom to top).
   - A detailed list of excluded or unconstrained packages with the specific rationale for their exclusion (e.g., generated protobuf code, build-only utilities, test helpers).
9. Exclude generated packages (e.g., `proto/gen/**`, `mocks/**`) from layer definitions using `shouldNotContainAnyOf` guards or by omitting them from rule scope.
10. Generate the complete configuration in a single pass. Do not iterate.