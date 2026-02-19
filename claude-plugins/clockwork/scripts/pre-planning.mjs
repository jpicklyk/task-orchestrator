#!/usr/bin/env node
// PreToolUse hook for EnterPlanMode: Guides structured planning with tiered depth and template discovery.

const context = `Planning mode activated. Follow this workflow:

1. DETERMINE planning depth from the user's request:

   Analyze the prompt for sizing signals:
   - Quick signals: 'fix', 'typo', 'rename', 'small change', 'just add', single-file scope, obvious approach, explicit simplicity
   - Standard signals: 'add feature', 'implement', 'build', moderate scope, clear requirements, no architectural uncertainty (THIS IS THE DEFAULT)
   - Detailed signals: 'redesign', 'refactor the system', 'architect', 'migrate', multi-phase language, design tradeoffs, cross-cutting concerns, long problem statements with constraints

   Work type can force a minimum tier:
   - Bug fix → min Quick  |  New feature → min Standard
   - Architecture change → min Detailed  |  Research → min Standard

   DECLARE your determination with a one-line rationale:
     'Standard-tier — bounded feature with clear requirements.'
   Only ASK (AskUserQuestion) if signals are genuinely ambiguous.

2. SIZE the work: Project + Features + Tasks (large multi-feature efforts), Feature + Tasks (focused scope, most common), or Standalone Tasks (small items).

3. DISCOVER templates (skip for Quick): Call query_templates for both FEATURE and TASK types. For Standard, recommend: Requirements Specification, Task Implementation, Technical Approach, Test Plan. For Detailed, also include: Feature Plan, Codebase Exploration, Design Decision, Implementation Specification. Mark recommendations with '(Recommended)' in AskUserQuestion.

4. PRESENT template selection (skip for Quick): Show the auto-selected templates based on tier and work type. The user can adjust if needed. Use AskUserQuestion ONLY if the work type is ambiguous (e.g., could be a bug fix or a feature gap). Otherwise, proceed with the recommended set.

5. WRITE the plan. Plan content varies by depth:
   - Quick: A task breakdown table with title, priority, and one-line description per task. This IS the plan.
   - Standard: Feature-level requirements/context sections (from templates), plus a task breakdown table (title, priority, one-line scope per task). Enough for the user to approve the work breakdown.
   - Detailed: Full feature plan using template sections (problem statement, architecture, implementation phases, design decisions, risks, verification). Do NOT list tasks separately — the Implementation Phases section defines the work breakdown. Each phase becomes a task after approval.
   Templates are the floor, not the ceiling — add content beyond what templates provide.

6. REVISION CHECK: If plan.md already contains an MCP Feature UUID line (<!-- MCP Feature: ... -->), this is a revision. Skip depth/template discovery and focus on what changed.

7. PRE-EXISTING MCP CONTAINERS: If a feature container already exists (created earlier in this session or from a prior session), write its UUID to plan.md as <!-- MCP Feature: <uuid> --> BEFORE calling ExitPlanMode. The post-plan hook checks for this line — if found, it enters revision mode (updates the existing feature) instead of creating a duplicate. Templates can be applied to the feature after planning when the full scope is clear. If no feature exists yet, the post-plan hook creates everything from scratch — do not pre-create containers unnecessarily.

plan.md is the design document. MCP containers are created only after the user approves. Do not write task UUIDs or detailed task specifications into plan.md — MCP is the task tracker.`;

console.log(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: context
  }
}));
