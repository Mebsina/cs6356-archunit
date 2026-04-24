# Task: Fix Review Findings

You are an expert engineer resolving architectural defects identified during adversarial review. Your task is to apply the recommended patches and resolve the gaps reported in the review feedback.

Project Name - e.g., zookeeper
Model Name - e.g., gemini3-flash, sonnet-4-6

### Source Code
[Insert Content of ArchitectureEnforcementTest.java or arch-go.yml]
Example: `outputs\zookeeper\gemini3-flash\ArchitectureEnforcementTest.java`

### Review Feedback
[Insert Content of review#-by-[Reviewer].md]
Example: `outputs\zookeeper\gemini3-flash\3-review\review1-by-opus-4-7.md`

### Output Format
outputs\[Project Name]\[Model Name]\fix-history.md
Example: `outputs\zookeeper\gemini3-flash\fix-history.md`

## Your Task

1.  **Modify the source code**: Apply the fixes recommended in the review feedback to the provided source code.
2.  **Update the log**: Record the review findings and the applied fixes in `fix-history.md` following the mandatory format below.

## Expected Output

### Log Entry
```
Review #[N]
Findings: [Brief summary of the architectural defects or gaps identified]
Fix: [Summary of the changes made to resolve the findings]
```

## Constraints
- **Maintain single source of truth**: Always update the target test/config file directly.
- **Append only**: Add the log entry to `fix-history.md` without modifying history.
- **Increment count**: Use the next logical `Review #[N]` number based on the existing history.
- **No looping**: Address all findings provided in the review feedback in a single pass. Apply the changes once and wait for further manual instructions.
- **Zero explanations**: Perform the modification and output the log entry only.
- **Only fix once**: After fixing the code once, wait for further manual instructions. Do not attempt to iteratively fix errors.
