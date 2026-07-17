# Example Schemas

Real schemas from the MCP Task Orchestrator project — use as reference when designing new schemas.

## `feature-implementation` (queue + work + review)

Full lifecycle with design gates, implementation evidence, and review verification. `session-tracking` and `delegation-metadata` are not listed as base notes below — they arrive via `default_traits: [delegated, session-tracked]` (see Traits Example below); trait notes append after the base notes shown here.

```yaml
work_item_schemas:
  feature-implementation:
    lifecycle: auto
    default_traits: [delegated, session-tracked]
    notes:
      - key: feature-summary
        role: queue
        required: true
        description: "Lean feature-level summary — goal, findings-to-tasks mapping, dependency edges, non-goals pointer."
        skill: "spec-quality"
        guidance: "Keep this lean — target under 2k chars. Cover: goal (2-3 sentences), a findings→tasks table mapping research/brainstorm findings to child task-scope items, dependency edges between those tasks, and a pointer to non-goals. Full spec-quality depth (alternatives, blast radius, risk flags, test strategy) belongs in each child's task-scope note, not here."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff for downstream agents — deviations, surprises, decisions."
        guidance: "Document decisions not in the feature-summary. Focus on what downstream agents need: deviations, API surprises, wrong assumptions, patterns affecting dependent work."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — plan alignment, test quality, simplification."
        skill: "review-quality"
        guidance: "Verify: (1) what was built aligns with the feature-summary and each child's task-scope, (2) tests cover the test strategy, (3) no unnecessary complexity."
```

## `feature-task` (queue + work, lighter than feature-implementation)

Schema for child work items under a feature container. Lighter queue gate (task scope instead of full specification). Review is opt-in here, not a base note — apply the `needs-task-review` trait per-item when a task needs task-scoped review (see Traits Example below). `session-tracking` and `delegation-metadata` come from `default_traits: [delegated, session-tracked]`, not an inline base note.

```yaml
  feature-task:
    lifecycle: auto
    default_traits: [delegated, session-tracked]
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
```

## `bug-fix` (queue + work + review)

Root cause analysis before code, verification after.

```yaml
  bug-fix:
    lifecycle: auto
    default_traits: [delegated, session-tracked]
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
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — fix alignment, regression coverage."
        skill: "review-quality"
        guidance: "Verify: (1) fix addresses root cause, (2) regression test exists, (3) edge cases covered."
```

`session-tracking` comes from the `session-tracked` default trait, not an inline base note (same pattern as `feature-implementation` and `feature-task` above).

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

## `agent-observation` — process-global schema (global floor, not per-project)

Unlike the schemas above, this one is **not** defined in a project's own `.taskorchestrator/config.yaml`. It's delivered by the shared server's **global** config (`deploy/global-config/.taskorchestrator/config.yaml`), which carries only process/self-improvement schemas — `agent-observation`, `session-retrospective`, `improvement-proposal`, and `container` — so every project sharing the server gets them for free without redeclaring them. See "Global vs Per-Project Config" in `config-format.md`. `agent-observation` items are always standalone depth-0 roots, never children of a container.

```yaml
  agent-observation:
    lifecycle: auto
    notes:
      - key: observation-detail
        role: queue
        required: true
        description: "Expected vs actual behavior and suggested improvement."
        guidance: "Describe what you observed. State what you expected instead. Suggest a concrete improvement. Tag the observation type: optimization, friction, bug, or missing-capability."
      - key: resolution
        role: work
        required: false
        description: "Outcome when the observation is addressed — what was done, or why it was rejected."
        guidance: "Record the disposition: fixed, rejected, or superseded. Optional — lets retrospectives calibrate which observation types convert to real fixes."
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

  needs-api-compat-review:
    notes:
      - key: api-compatibility
        role: review
        required: true
        description: "Compatibility assessment for API changes — the MCP tool surface (dynamically re-discovered) and the REST surface (hardcoded clients) have different compatibility models."
        skill: "api-compat-review"
        guidance: "Assess by surface. **MCP tools:** clients re-read the tools/list schema each session, so parameter renames/additions are NOT breaking — verify instead that each changed param's schema key and its read site stay in sync, and that all first-party callers (skills, hooks, output styles, memory, api-reference.md) update in lockstep; a pure rename needn't keep the old name working. **REST API:** clients hardcode field/param names, so compatibility matters — keep response shapes additive, and renames/removals need a migration path or version bump. Update openapi.yaml + api-rest.md for REST changes."

  needs-plugin-update:
    notes:
      - key: plugin-impact
        role: review
        required: true
        description: "Assessment of plugin skill and hook changes needed after a behavior change."
        skill: "plugin-impact-review"
        guidance: "Identify which skills reference the changed behavior, which hooks inject affected context, and whether config-format docs or output styles need updating. List specific files/sections. Plugin content is cached — note if an /mcp reconnect is needed."

  needs-security-review:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review of auth, data handling, and access control."
        guidance: "Evaluate input validation, injection risks, access control, data handling. Flag OWASP Top 10 concerns."

  needs-perf-review:
    notes:
      - key: performance-baseline
        role: queue
        required: false
        description: "Performance impact assessment and measurement plan."
        skill: "perf-review"
        guidance: "Document affected hot paths, expected impact (new queries, parsing, file I/O), any O(n) risk where n could be large, and a measurement plan. Optional — use when the change touches known hot paths."

  delegated:
    notes:
      - key: delegation-metadata
        role: work
        required: false
        description: "Model, isolation, rationale, and outcome for a delegated dispatch (orchestrator-filled)."
        guidance: "Filled by the orchestrator AFTER a subagent returns (the subagent doesn't know which model it ran on). Record model (haiku/sonnet/opus), isolation (inline or worktree:<path>), a one-line rationale, and a one-line outcome. Optional — feeds /session-retrospective delegation-alignment scoring; absent is tolerated."

  session-tracked:
    notes:
      - key: session-tracking
        role: work
        required: true
        description: "Session context — what was done, how it went, and anything the retrospective should know."
        guidance: "Record what happened during implementation. Structure: **Outcome** (success/partial/failure), **Files changed** (list with rationale), **Deviations** (from spec/plan), **Friction** (tool errors, roundtrips, workarounds), **Gate interactions**, **Observations**, **Test results** (pass/fail counts). Keep it factual and concise — this feeds the session retrospective."

  needs-task-review:
    notes:
      - key: review-checklist
        role: review
        required: true
        description: "Task-level quality gate — scope alignment and test coverage."
        guidance: "Verify: (1) what was built matches the task-scope note — flag scope creep or missed acceptance criteria, (2) tests cover the stated acceptance criteria and aren't strawman tests, (3) no unintended side effects on files outside the task scope."
```

Apply traits at item creation: `manage_items(operation="create", items=[{..., traits: "needs-migration-review"}])` or via schema-level `default_traits`. Most schemas in this project apply `session-tracked` (and often `delegated`) via `default_traits` rather than per-item, since nearly every item needs session tracking for `/session-retrospective`; `needs-task-review` is the opt-in trait that layers a review phase onto `feature-task`.
