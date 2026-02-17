#!/usr/bin/env node
// PostToolUse hook for ExitPlanMode: Creates MCP containers from approved plan.

const context = `Plan approved. Execute the full workflow — materialize then implement — without stopping for additional confirmation. Plan approval is the user's green light for the entire pipeline.

## Phase 1 — Materialize

1. READ plan.md and check for an existing MCP Feature UUID line (<!-- MCP Feature: ... -->).

2. NEW PLAN (no UUID): Create containers by deriving tasks from the plan structure:
   a. Create the feature/project via manage_container(operation='create') with templateIds from confirmed templates. Include summary from plan.
   b. Derive tasks from the plan structure and create each via manage_container(operation='create', containerType='task') with featureId and templateIds:
      - Quick plans: one task per row in the task breakdown table.
      - Standard plans: one task per row in the breakdown table. Apply selected task templates.
      - Detailed plans: one task per Implementation Phase. Apply appropriate templates based on phase nature (e.g., Codebase Exploration for research phases, Design Decision for architectural choice phases, Implementation Specification for coding phases). Set priority and complexity based on phase scope.
   c. For plan content beyond what templates provide, add as additional sections via manage_sections(operation='bulkCreate') on the feature container.
   d. Set up dependencies: phases are sequential by default. Override where the plan notes parallel or independent work.
   e. Write ONLY the feature UUID to plan.md header as an HTML comment: <!-- MCP Feature: <uuid> -->. Do NOT write individual task UUIDs.

3. REVISION (UUID exists): Query the existing feature via query_container(operation='overview'), compare with plan changes, update via manage_container(operation='update'). Check if tasks need adding, updating, or re-prioritizing.

4. VERIFY: Call get_blocked_tasks(featureId='<uuid>') to confirm the dependency graph is correct.

5. REPORT: Use query_container(operation='overview') to show the user the created container hierarchy with task statuses. This is the task view — not plan.md.

## Phase 2 — Implement

After materialization is verified (steps 1-5 complete), IMMEDIATELY proceed to implementation:

6. Dispatch implementation subagents for unblocked tasks. Every delegation prompt MUST include the MCP task UUID. Implementation agents MUST call request_transition(trigger='start') when beginning work and request_transition(trigger='complete') when done.
   - If the feature has an 'Execution Notes' section, respect the user's delegation preference for parallelism and ordering.
   - Respect dependency ordering: dispatch unblocked tasks first, then dispatch newly unblocked tasks as predecessors complete.

## Rules

- AUTOMATIC CONTINUATION: Do NOT use AskUserQuestion or stop to ask the user between phases. The plan approval already authorized both materialization and implementation. Proceed through the full pipeline.
- MATERIALIZATION BEFORE IMPLEMENTATION: Steps 1-5 MUST complete before dispatching ANY implementation subagent. NEVER dispatch materialization and implementation in parallel.
- Token efficiency: Use templateIds during create (not separate apply_template). Use manage_sections(operation='bulkCreate') for multiple custom sections. Use manage_container containers array for batch creates/updates.

plan.md is the design document. MCP is the execution tracker. query_container(operation='overview') is the task view.`;

console.log(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: context
  }
}));
