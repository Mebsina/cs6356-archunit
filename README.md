# Automated Architectural Enforcement with ArchUnit and LLMs

## Academic Context
**Course**: CS 6356 Software Maintenance, Evolution, and Re-Engineering  
**Institution**: The University of Texas at Dallas

## Getting Started
To initialize the project environment and clone the required benchmark repositories, execute the provided setup script:

```bash
# Run the setup script to clone the 9 benchmark repositories
./run.sh
```

## Project Overview
This project implements an automated enforcement pipeline that translates recovered software architectures into executable build-time constraints. By converting architectural documentation into ArchUnit test rules, the pipeline provides continuous validation of code changes against the intended design, preventing architectural drift before it is merged into production.

## Core Objective
The primary goal is to bridge the gap between architectural recovery and enforcement. While tools can accurately recover system architectures, those designs often degrade without a continuous validation mechanism. This solution utilizes Large Language Models (LLMs) as translators to convert architecture representations into deterministic ArchUnit rules.

## Enforcement Pipeline
The workflow consists of several integrated stages:

1.  **Architecture and Structure Collection**: Gathers architectural ground truth (PDFs, Mermaid diagrams, or recovery outputs) and extracts the repository package directory structure.
2.  **LLM-Based Rule Generation**: Translates the collected documentation and package mappings into ArchUnit test classes.
3.  **Adversarial Cross-Model Validation**: A secondary, independent model reviews the generated rules against the original documentation to identify missing constraints, overly broad rules, or incorrect package mappings.
4.  **Self-Correction Loop**: Compilation failures or validation issues are fed back into the generation model for automated correction.
5.  **Baseline and PR Evaluation**: Executes rules against the main branch to establish a baseline and subsequently validates open pull requests to detect new architectural violations.

## Evaluation Scope
The effectiveness of the pipeline is evaluated across eight large-scale, real-world repositories:
- HashiCorp Consul
- Spring Framework
- Apache ZooKeeper
- MindSpore
- Kubernetes
- TensorFlow
- Apache Kafka
- Istio

## Inputs and Outputs
-   **Input**: Architecture representations and target repository package structures.
-   **Output**: Enforceable ArchUnit test suites and comprehensive architectural validation reports.
