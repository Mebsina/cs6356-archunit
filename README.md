# Automated Architectural Enforcement with ArchUnit and LLMs

## Academic Context
**Course**: CS 6356 Software Maintenance, Evolution, and Re-Engineering  
**Institution**: The University of Texas at Dallas

## Getting Started
To initialize the project environment and clone the required benchmark repositories, execute the provided setup script:

```bash
# Run the setup script to clone the 6 benchmark repositories from ArchEval Benchmark
./run.sh
```

```bash
# Generate package structure if need to update
./package.sh
```

## Project Overview
This project implements an automated enforcement pipeline that translates recovered software architectures into executable build-time constraints. By converting architectural documentation into enforceable rules (e.g., ArchUnit for Java or arch-go for Go), the pipeline provides continuous validation of code changes against the intended design, preventing architectural drift before it is merged into production.

## Core Objective
The primary goal is to bridge the gap between architectural recovery and enforcement. While tools can accurately recover system architectures, those designs often degrade without a continuous validation mechanism. This solution utilizes Large Language Models (LLMs) as translators to convert architecture representations into deterministic architectural constraints.


## Libraries

The following libraries are utilized for architectural enforcement based on the project language:

| Language | Library |
| :--- | :--- |
| Java | [ArchUnit](https://github.com/TNG/ArchUnit) (tngtech/archunit) |
| Go | [arch-go](https://github.com/fdaines/arch-go) (fdaines/arch-go) |

## Enforcement Pipeline
The workflow consists of several integrated stages:

1.  **Architecture and Structure Collection**: Gathers architectural ground truth (PDFs, Mermaid diagrams, or recovery outputs) and extracts the repository package directory structure.
2.  **LLM-Based Rule Generation**: Translates the collected documentation and package mappings into ArchUnit test classes.
3.  **Adversarial Cross-Model Validation**: A secondary, independent model reviews the generated rules against the original documentation to identify missing constraints, overly broad rules, or incorrect package mappings. The review follows a structured four-phase methodology:
    - **Phase 1: Coverage Audit**: Identify documented constraints with zero enforcement.
    - **Phase 2: Precision Audit**: Detect vacuous, overly broad, or narrow rules.
    - **Phase 3: Semantic Correctness**: Verify dependency directions and layer isolation.
    - **Phase 4: Structural Integrity**: Ensure intra-layer isolation and transitivity gap coverage.
4.  **Self-Correction Loop (Manual)**: Generated code is manually compiled. If compilation fails, errors are fed back to the generation model along with the target file and `fix-history.md` history for iterative refinement.
5.  **Baseline and PR Evaluation**: Executes rules against the main branch to establish a baseline and subsequently validates open pull requests to detect new architectural violations.

## Evaluation Scope
The effectiveness of the pipeline is evaluated across six large-scale, real-world repositories from the ArchEval Benchmark:
- **Java**: Spring Framework, Apache ZooKeeper, Apache Kafka
- **Go**: HashiCorp Consul, Kubernetes, Istio

## Usage
The rule generation and review processes utilize generic templates located in the `prompts/` directory. These templates are designed to be adaptable to any multi-module project by replacing placeholders (e.g., `[Insert Path to Architecture Documentation]`) with project-specific context.

### Prompt Templates
- **Generation**: `generate-java.md` (ArchUnit) / `generate-go.md` (arch-go)
- **Correction**: `fix-compilation.md` (Iterative Feedback)
- **Review**: `review-java.md` / `review-go.md` (Adversarial Audit)

### Manual Correction Workflow
If the generated code fails to compile, use the following iterative process:
1. Copy the compilation error and the current state of the source code.
2. Use the `fix-compilation.md` prompt to address all errors in the output in a single pass.
3. Apply the fix to the existing test file (the AI will provide one updated version resolving all reported issues).
4. Append the error and fix strategy to `fix-history.md` using the following format:
   ```
   Compile #[N]
   Error: [Brief description of the compiler error]
   Fix: [Summary of the changes made to resolve it]
   ```
5. Repeat until the code compiles successfully.

## Directory Structure
- `prompts/`: Contains LLM prompt templates for rule generation and adversarial review.
- `package-structure/`: Extracted package hierarchies for benchmark repositories.
- `output/`: Standardized output directory for generated artifacts, organized by `output/[Project Name]/[Model Name]/`.
    - **Java Outputs**: `ArchitectureEnforcementTest.java`, `pom.xml`, `compile-fix.md`
    - **Go Outputs**: `arch-go.yml`, `Makefile`, `compile-fix.md`
    - **Review Outputs**: `feedback#-by-[Reviewer Model Name].md`

## Inputs and Outputs
-   **Input**: Architecture representations (PDF/Mermaid), target repository package structures, and generic prompt templates.
-   **Output**: Enforceable test suites, build configurations (Maven/Makefile), comprehensive adversarial validation reports, and iterative compilation logs (`compile-fix.md`).
