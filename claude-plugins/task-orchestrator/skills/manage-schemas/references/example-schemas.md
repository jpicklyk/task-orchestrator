# Example Schemas

Real schemas from the MCP Task Orchestrator project — use as reference when designing new schemas.

## `feature-implementation` (queue + work + review)

Full lifecycle with design gates, implementation evidence, and review verification.

```yaml
work_item_schemas:
  feature-implementation:
    lifecycle: auto
    notes:
      - key: specification
        role: queue
        required: true
        description: "Problem statement, approach, and implementation plan."
        skill: "spec-quality"
        guidance: "This note must cover: problem statement and who benefits, acceptance criteria, non-goals, alternatives considered (min 2 real options + 'do nothing'), blast radius (affected modules and tests), risk flags, and test strategy."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff for downstream agents — deviations, surprises, decisions."
        guidance: "Document decisions not in the specification. Focus on what downstream agents need: deviations, API surprises, wrong assumptions, patterns affecting dependent work."
      - key: session-tracking
        role: work
        required: true
        description: "Session context for retrospective."
        guidance: "Record outcome, files changed, deviations, friction, test results."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — plan alignment, test quality, simplification."
        skill: "review-quality"
        guidance: "Verify: (1) what was built aligns with the specification, (2) tests cover the test strategy, (3) no unnecessary complexity."
```

## `feature-task` (queue + work + review, lighter than feature-implementation)

Schema for child work items under a feature container. Lighter queue gate (task scope instead of full specification) and task-scoped review.

```yaml
  feature-task:
    lifecycle: auto
    notes:
      - key: task-scope
        role: queue
        required: true
        description: "What to build — target files, acceptance criteria, constraints."
        guidance: "Define the narrow scope. Include: what to build, target files, acceptance criteria, constraints."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff — deviations, surprises, decisions affecting dependent work."
        guidance: "Document decisions not in the task scope. Focus on what downstream agents need."
      - key: review-checklist
        role: review
        required: true
        description: "Task-level quality gate — scope alignment and test coverage."
        guidance: "Verify scope alignment, test coverage, no unintended side effects."
      - key: session-tracking
        role: work
        required: true
        description: "Session context for retrospective."
        guidance: "Record outcome, files changed, deviations, friction, test results."
```

## `bug-fix` (queue + work + review)

Root cause analysis before code, verification after.

```yaml
  bug-fix:
    lifecycle: auto
    notes:
      - key: diagnosis
        role: queue
        required: true
        description: "Reproduction, root cause, and fix approach."
        skill: "spec-quality"
        guidance: "This note must cover: reproduction steps, root cause, fix approach with alternatives, blast radius, and test strategy."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff — what changed, deviations from diagnosis."
        guidance: "Document what changed and why. Note if root cause differed from diagnosis."
      - key: session-tracking
        role: work
        required: true
        description: "Session context for retrospective."
        guidance: "Record outcome, files changed, deviations, friction, test results."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — fix alignment, regression coverage."
        skill: "review-quality"
        guidance: "Verify: (1) fix addresses root cause, (2) regression test exists, (3) edge cases covered."
```

## `container` (manual lifecycle, gate-free)

Schema for root-level category containers (Features, Bugs, Tech Debt). No required notes — containers advance freely. The `manual` lifecycle prevents auto-cascade from terminating containers when children complete.

```yaml
  container:
    lifecycle: manual
    notes:
      - key: container-summary
        role: queue
        required: false
        description: "Optional high-level description of what this container organizes."
        guidance: "Brief description of the container's purpose and scope."
```

Set `type: container` on root containers so they match this schema instead of `default`.

## `agent-observation` (queue only, minimal)

Single-note schema for lightweight tracking — no work phase gates.

```yaml
  agent-observation:
    lifecycle: auto
    notes:
      - key: observation-detail
        role: queue
        required: true
        description: "Expected vs actual behavior and suggested improvement."
        guidance: "Describe what was observed. State expected behavior. Suggest a concrete improvement."
```

## Traits Example

Traits add composable note requirements per-item. Define them under the `traits:` top-level key:

```yaml
traits:
  needs-migration-review:
    notes:
      - key: migration-assessment
        role: queue
        required: true
        description: "SQLite migration strategy — table recreation, data migration, rollback."
        skill: "migration-review"
        guidance: "Document schema changes, SQLite constraints, data migration strategy, Flyway migration details."

  needs-security-review:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review of auth, data handling, and access control."
        guidance: "Evaluate input validation, injection risks, access control, data handling. Flag OWASP Top 10 concerns."
```

Apply traits at item creation: `manage_items(create, items=[{..., traits: "needs-migration-review"}])` or via schema-level `default_traits`.
