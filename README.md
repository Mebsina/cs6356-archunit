# Automated Architectural Enforcement with ArchUnit and LLMs

## Academic Context
**Course**: CS 6356 Software Maintenance, Evolution, and Re-Engineering  
**Institution**: The University of Texas at Dallas

## Getting Started
To initialize the project environment and clone the required benchmark repositories, execute the provided setup script:

```bash
# Run the setup script and select the repositories to clone (All, Java, or Go)
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
The workflow consists of an iterative, 7-stage process designed to move from recovered documentation to high-fidelity architectural constraints:

1.  **Architecture and Structure Collection**: Gathers architectural ground truth (PDFs, Mermaid diagrams, or recovery outputs) and extracts the repository package directory structure using `package.sh`.
2.  **Rule Generation (Prompt 1)**: Translates the collected documentation and package mappings into initial enforcement artifacts (e.g., ArchUnit for Java or arch-go for Go).
3.  **Compilation Fix Loop (Prompt 2)**: Resolves build-time errors in the generated code by feeding compiler output back to the LLM. This loop terminates when the enforcement code compiles successfully.
4.  **Adversarial Cross-Model Validation (Prompt 3)**: A secondary, independent model performs an adversarial audit of the rules against the documentation to identify coverage gaps, semantic errors, or incorrect mappings.
5.  **Review Patching (Prompt 4)**: Applies the recommended fixes and patches from the adversarial review to the source code, recording the changes in `fix-history.md`.
6.  **Violation Triage Loop (Prompt 5)**: Executes the rules against the codebase and triages results into "mapping errors" (incorrect rules) or "real violations" (architectural drift). This loop repeats until mapping errors reach zero.
7.  **Final-Thoughts Calibration (Prompt 6)**: Performs a final assessment of the rule file's fidelity to the documentation, documenting silences, inferences, and judgment calls to establish a clear confidence level.

## Evaluation Scope
The effectiveness of the pipeline is evaluated across six large-scale, real-world repositories from the ArchEval Benchmark:
- **Java**: Spring Framework, Apache ZooKeeper, Apache Kafka
- **Go**: HashiCorp Consul, Kubernetes, Istio

## Usage
The process utilizes standardized prompt templates located in the `prompts/` directory.

### Prompt Templates
- **1-Generation**: `1-generate-java.md` / `1-generate-go.md`
- **2-Correction**: `2-fix-compilation.md` (Build Errors)
- **3-Review**: `3-review-java.md` / `3-review-go.md` (Adversarial Audit)
- **4-Fix Review**: `4-fix-review.md` (Apply Review Patches)
- **5-Analysis**: `5-analyze-java.md` (Violation Triage)
- **6-Final**: `6-final-thoughts.md` (Fidelity Calibration)
- **7-Comparison**: `7-comparison.md` (In-Depth Architectural Comparison)

### Using the Prompts
All prompt templates are standardized to use variable-based paths. To use them:
1. Open the prompt file from the `prompts/` directory.
2. Fill in the **Project Name** and **Model Name** (and **Reviewer Model Name** where applicable) at the very top of the file.
3. The rest of the prompt (instructions, examples, and output paths) will automatically stay consistent with your project setup.

### Iterative Triage Workflow
For violation analysis (Stage 6), use the following process:
1. Run the enforcement tests and capture the test report.
2. Use the `5-analyze-java.md` prompt to cluster violations and identify mapping errors.
3. Apply the recommended "Single-Shot Patch" to the test class.
4. Repeat until the analysis report indicates `Results: 0 mapping error`.

## Directory Structure
- `prompts/`: Standardized LLM prompt templates.
- `inputs/`: Project documentation and extracted package hierarchies.
- `outputs/[Project Name]/[Model Name]/`: Artifacts generated during the pipeline.
    - `ArchitectureEnforcementTest.java` / `arch-go.yml`: The primary rule files.
    - `pom.xml` / `Makefile`: Build configurations.
    - `fix-history.md`: Cumulative log of compilation fixes and review patches.
    - `3-review/`: Adversarial audit reports (`review#-by-[Reviewer].md`).
    - `5-analyze/`: Iterative violation triage reports (`analyze#-by-[Reviewer].md`).
    - `6-final/`: Final fidelity calibration pass (`final-thoughts-by-[Reviewer].md`).

## Inputs and Outputs
-   **Input**: Architecture representations, target repository package structures, and standardized prompt templates.
-   **Output**: Enforceable test suites, build configurations, comprehensive audit reports, and a complete traceability log of the refinement process.
