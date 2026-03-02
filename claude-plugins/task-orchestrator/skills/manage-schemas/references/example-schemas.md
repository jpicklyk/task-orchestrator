# Example Schemas

Real schemas from the MCP Task Orchestrator project — use as reference when designing new schemas.

## `feature-implementation` (queue + work + review)

Full lifecycle with design gates, implementation evidence, and optional deploy verification.

```yaml
feature-implementation:
  - key: requirements
    role: queue
    required: true
    description: "Problem statement and acceptance criteria."
    guidance: "Run /feature-implementation for the full lifecycle guide. For this note: describe what problem this solves. List 2-5 acceptance criteria."
  - key: design
    role: queue
    required: true
    description: "Chosen approach, alternatives considered, key risks."
    guidance: "Explain the implementation approach. Note alternatives ruled out. Call out risks (schema migrations, tight coupling, ORM quirks)."
  - key: implementation-notes
    role: work
    required: true
    description: "Key decisions made during implementation, deviations from design."
    guidance: "Document surprises, wrong-turns, or deviations from the planned approach. Include API/class names that differed from expectations."
  - key: test-results
    role: work
    required: true
    description: "Test pass/fail count and any new tests added."
    guidance: "Run tests and report total count and failures. List any new test classes added."
  - key: deploy-notes
    role: review
    required: false
    description: "Deploy needed? Version bump? Reconnect required?"
    guidance: "Note whether a rebuild/deploy was done, what version was bumped to, and whether reconnect was required."
```

## `bug-fix` (queue + work, no review)

Lightweight schema for bug fixes — root cause before code, verification after.

```yaml
bug-fix:
  - key: reproduction-steps
    role: queue
    required: true
    description: "Step-by-step reproduction with expected vs actual result."
    guidance: "Include the exact tool call that triggers the bug. State expected output and actual output."
  - key: root-cause
    role: queue
    required: true
    description: "Why it happens — file, line, and condition."
    guidance: "Identify the specific file and function. Explain the condition that triggers it."
  - key: fix-summary
    role: work
    required: true
    description: "What was changed and which files were modified."
    guidance: "List each file changed and summarize the change."
  - key: test-verification
    role: work
    required: true
    description: "How the fix was verified and test results after fix."
    guidance: "Run tests and report results. Note if a new test was added to cover the fix."
```

## `agent-observation` (queue only, minimal)

Single-note schema for lightweight tracking — no work phase gates.

```yaml
agent-observation:
  - key: observation-detail
    role: queue
    required: true
    description: "Expected vs actual behavior and suggested improvement."
    guidance: "Describe what was observed. State expected behavior. Suggest a concrete improvement."
```
