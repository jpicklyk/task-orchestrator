# Status Progression Guide

Comprehensive guide to status workflows, validation rules, and integration patterns in Task Orchestrator v2.0.

## Table of Contents

- [Overview](#overview)
- [Event-Driven Architecture (v2.0)](#event-driven-architecture-v20)
- [Status Flow Diagrams](#status-flow-diagrams)
- [Hook Integration Examples](#hook-integration-examples)
- [Workflow Scenarios](#workflow-scenarios)
- [Prerequisite Rules](#prerequisite-rules)
- [Error Troubleshooting](#error-troubleshooting)

## Overview

Task Orchestrator uses a **config-driven status system** that combines:

1. **StatusValidator** - Automatic validation when status changes (enforces prerequisites, flow rules)
2. **get_next_status tool** - Read-only MCP tool providing intelligent workflow recommendations
3. **Status Progression Skill** - Claude Code skill that uses get_next_status for AI-friendly status guidance
4. **Custom hooks** - Automated actions triggered on successful status changes

### System Components

**get_next_status (MCP Tool)**:
- **Type**: Read-only MCP tool (part of core Task Orchestrator)
- **Purpose**: Analyzes entity state and recommends next status
- **Uses**: StatusProgressionService for flow logic and StatusValidator for prerequisite checks
- **Returns**: Ready/Blocked/Terminal recommendation with detailed context
- **Access**: Available to all MCP clients (Claude Desktop, Claude Code, Cursor, etc.)

**Status Progression Skill (Claude Code Only)**:
- **Type**: Auto-activating Claude Code skill (lightweight coordination)
- **Purpose**: Interprets get_next_status output and config for human-friendly guidance
- **Uses**: Calls get_next_status tool + reads config.yaml
- **Returns**: Natural language explanations and actionable commands
- **Access**: Only in Claude Code after installing Task Orchestrator plugin

**Relationship**:
```
User Question ("Can I complete this task?")
    ↓
Status Progression Skill (interprets request)
    ↓
get_next_status tool (analyzes entity)
    ↓
StatusProgressionService (determines flow)
    ↓
StatusValidator (checks prerequisites)
    ↓
Recommendation (Ready/Blocked/Terminal)
    ↓
Status Progression Skill (formats for user)
    ↓
Natural language response with commands
```

## Event-Driven Architecture (v2.0)

Task Orchestrator v2.0 uses **trigger-based status transitions** with automatic cascade detection. Instead of manually setting status values, you use named triggers (start, complete, cancel, block, hold) that validate prerequisites and automatically detect when parent entities should advance.

### Core Principle

**Use request_transition with triggers, not raw status values.** The tool handles validation, cascade detection, and flow context automatically.

### Trigger-Based Workflow

**Primary mechanism**: `request_transition` tool with named triggers:
- `start` - Progress to next status in workflow flow
- `complete` - Move to completed (validates prerequisites)
- `cancel` - Move to cancelled (emergency transition)
- `block` - Move to blocked (emergency transition)
- `hold` - Move to on-hold (emergency transition)

**How It Works:**
1. **Call request_transition** with containerId, containerType, and trigger
2. **Tool validates prerequisites** (summary populated, dependencies resolved, etc.)
3. **Status transition applied** if validation passes
4. **Cascade detection runs automatically** via `WorkflowServiceImpl.detectCascadeEvents()`
5. **Response includes** cascadeEvents, unblockedTasks, and flow context

**Example**:
```javascript
// Complete a task - automatically checks if feature should advance
request_transition(containerId="task-uuid", containerType="task", trigger="complete")

// Response includes cascade events if applicable:
{
  "newStatus": "completed",
  "cascadeEvents": [{
    "entityType": "feature",
    "entityId": "feature-uuid",
    "previousStatus": "in-development",
    "newStatus": "testing",
    "reason": "All child tasks completed"
  }],
  "unblockedTasks": [{"taskId": "...", "title": "..."}],
  "activeFlow": "default_flow",
  "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
  "flowPosition": 4
}
```

### Universal Workflow Events

These events are detected automatically by cascade detection - you don't need to manually check for them:

**Feature Events**:
- `first_task_started` - First task begins execution → May progress feature to in-development
- `all_tasks_complete` - All tasks finished → May progress to testing/completed
- `tests_passed` / `tests_failed` - Quality validation results
- `review_approved` / `changes_requested` - Human review outcomes
- `blocker_detected` / `feature_cancelled` - Exception handling

**Task Events**:
- `work_started` - Implementation begins → May move to in-progress
- `implementation_complete` - Code + tests written → May move to testing/review
- `tests_passed` / `tests_failed` - Automated test results
- `review_approved` / `changes_requested` - Code review outcomes
- `blocker_detected` / `task_cancelled` - Exception handling

### Cascade Lifecycle Prerequisites

Cascade events only fire when the parent entity is in the correct lifecycle state. Event handlers are keyed by the parent's **current status**, so skipping lifecycle steps causes cascades to silently not fire.

**Required sequence for task-to-feature cascades:**

1. **Start first task** → `first_task_started` cascade fires → advance feature to `in-development`
2. **Complete all tasks** → `all_tasks_complete` cascade fires → advance feature to `testing`
3. **Complete feature** → `all_features_complete` cascade fires → advance project to `completed`

If step 1 is skipped (e.g., tasks are completed directly without being started first), the feature stays in `planning` and no `all_tasks_complete` handler matches — the cascade never fires. Always act on `cascadeEvents` in transition responses to keep parent entities in the correct lifecycle state.

## Status Flow Diagrams

### Feature Flow (Default - Software Development)

```
draft → planning → in-development → testing → validating → completed

Alternative with Review:
draft → planning → in-development → testing → validating → pending-review → completed

With Deployment Tracking:
...→ completed → deployed (+ env:staging) → deployed (+ env:production)

Emergency Transitions (if allow_emergency: true):
Any status → blocked (external blocker)
Any status → on-hold (paused intentionally)
Any status → archived (obsolete/replaced)

Terminal Statuses:
completed, archived
```

**v2.0 Feature Statuses (11 total):**
- `draft` - Initial draft, rough ideas
- `planning` - Define requirements, break into tasks
- `in-development` - Active implementation
- `testing` - All tasks complete, running tests
- `validating` - Tests passed, final validation
- `pending-review` - Awaiting human approval
- `blocked` - Blocked by external dependencies
- `on-hold` - Temporarily paused
- `completed` - Feature complete and validated (terminal)
- `archived` - Archived for reference (terminal)
- `deployed` - Successfully deployed to environment

### Task Flow (Default - Software Development)

```
backlog → pending → in-progress → testing → completed

Alternative with Code Review:
backlog → pending → in-progress → in-review → testing → completed

With Review Iterations:
...→ in-progress → in-review → changes-requested → in-progress → in-review → testing → completed

With Deployment Tracking (if enabled):
...→ completed → deployed (+ env:production)

Emergency Transitions (if allow_emergency: true):
Any status → blocked (dependency fails)
Any status → on-hold (priority change)
Any status → cancelled (no longer needed)
Any status → deferred (postponed)

Terminal Statuses:
completed, cancelled, deferred
```

**v2.0 Task Statuses (14 total):**
- `backlog` - In backlog, needs prioritization
- `pending` - Ready to start, waiting for assignment
- `in-progress` - Actively being worked on
- `in-review` - Implementation complete, awaiting review
- `changes-requested` - Review requested changes
- `testing` - Running automated tests or QA
- `ready-for-qa` - Testing complete, ready for QA review
- `investigating` - Investigating issues or technical approach
- `blocked` - Blocked by incomplete dependencies
- `on-hold` - Temporarily paused
- `completed` - Task finished and merged (terminal)
- `cancelled` - Explicitly cancelled (terminal)
- `deferred` - Postponed indefinitely (terminal)
- `deployed` - Successfully deployed to environment

### Project Flow (Default)

```
planning → in-development → completed → archived

Emergency Transitions (if allow_emergency: true):
Any status → on-hold (project paused)
Any status → cancelled (project abandoned)

Terminal Statuses:
completed, archived, cancelled
```

**v2.0 Project Statuses (6 total):**
- `planning` - Define scope and features
- `in-development` - Active development
- `on-hold` - Project paused (emergency)
- `cancelled` - Project cancelled (emergency, terminal)
- `completed` - Project finished (terminal)
- `archived` - Archived for history (terminal)

## Hook Integration Examples

Task Orchestrator supports **custom hooks** that execute automatically on status changes.

### How Hooks Work with Status Validation

**Execution order:**
```
1. User calls request_transition(trigger="...", ...)
2. StatusValidator validates status and transition
3. If valid, status change applied
4. Cascade detection runs automatically
5. Hooks execute AFTER successful status change
6. Response includes cascadeEvents, unblockedTasks, and flow context
```

**Key insight**: Hooks execute **after** validation passes. Status Progression Skill helps users **pass validation** so hooks can execute.

### Example 1: Git Commit Hook on Task Completion

**Hook configuration** (.taskorchestrator/hooks.yaml):
```yaml
hooks:
  - name: git-commit-on-task-complete
    trigger: "task.status.completed"
    action: bash
    script: |
      git add .
      git commit -m "feat: ${TASK_TITLE}"
```

**User workflow:**

**User:** "Can I complete this task?"

**Status Progression Skill checks:**
1. Read config.yaml → completed requires summary (at most 500 chars)
2. Check task → summary is 50 chars
3. Report blocker

**Response:**
```
Not ready for completion. Your config requires task summary (at most 500 chars).

Current: 50 characters
Required: at most 500 characters

The git-commit hook will trigger AFTER completion, but validation must pass first.

Fix:
manage_container(operation="update", containerType="task",
  id="...", summary="[up to 500 char description of what was accomplished]")

Then complete:
request_transition(containerId="...", containerType="task", trigger="complete")
```

### Example 2: Deployment Notification Hook

**Hook configuration**:
```yaml
hooks:
  - name: slack-deployment-notification
    trigger: "feature.status.deployed"
    action: bash
    script: |
      curl -X POST https://hooks.slack.com/... \
        -d "Feature ${FEATURE_NAME} deployed to ${TAGS}"
```

**User workflow:**

**User:** "Deploy feature to production"

**Status Progression Skill actions:**
1. Validate feature is ready (all tasks complete, tests passed)
2. Guide user through deployment with environment tags

**Response:**
```
✅ Ready to deploy!

Step 1: Mark as deployed
request_transition(containerId="...", containerType="feature", trigger="complete")

Step 2: Add environment tag (triggers Slack notification with environment)
manage_container(operation="update", containerType="feature",
  id="...", tags="api,backend,env:production")

Your slack-deployment-notification hook will execute after status change, sending notification with tags.
```

### Example 3: Testing Gate Hook

**Hook configuration**:
```yaml
hooks:
  - name: run-tests-before-testing-status
    trigger: "feature.status.testing"
    action: bash
    script: |
      ./gradlew test
      exit $?  # Fail status change if tests fail
```

**User workflow:**

**User:** "Why did my status change to testing fail?"

**Status Progression Skill interprets:**
1. Read error message from hook execution
2. Interpret hook failure vs validation failure

**Response:**
```
Status validation passed (transition allowed), but hook execution failed.

Hook: run-tests-before-testing-status
Trigger: feature.status.testing
Result: Tests failed (exit code 1)

This is NOT a validation error - validation succeeded. The hook ran and failed.

Your options:
1. Fix failing tests, then retry status change
2. Disable hook temporarily (edit hooks.yaml)
3. Move to different status (back to in-development for fixes)

StatusValidator allows the transition; the hook is blocking execution.
```

### Example 4: Status History Tracking Hook

**Hook configuration**:
```yaml
hooks:
  - name: log-status-transitions
    trigger: "*.status.*"  # All status changes
    action: bash
    script: |
      echo "$(date): ${CONTAINER_TYPE} ${CONTAINER_ID} ${OLD_STATUS} → ${NEW_STATUS}" >> status-log.txt
```

**User workflow:**

**User:** "Show me the workflow history for this feature"

**Status Progression Skill navigates:**
1. Read status-log.txt (if accessible)
2. Parse status transitions
3. Map against configured workflow

**Response:**
```
Feature workflow history:

2024-01-15 10:30 - draft → planning (Feature Architect initiated)
2024-01-15 14:20 - planning → in-development (5 tasks created)
2024-01-18 16:45 - in-development → testing (all 5 tasks completed)
2024-01-19 09:15 - testing → validating (tests passed)
2024-01-19 11:00 - validating → completed (validation approved)

Current position: COMPLETED (terminal status)

Your log-status-transitions hook has been recording all changes to status-log.txt.
```

### Hook + Validation Interaction Patterns

**Pattern 1: Validation blocks before hook**
```
User attempts invalid transition
→ StatusValidator blocks (Status Progression Skill explains why)
→ Hook never executes
→ User fixes validation issue
→ Retry → validation passes → hook executes
```

**Pattern 2: Hook blocks after validation**
```
User attempts valid transition
→ StatusValidator allows
→ Status change applied
→ Hook executes and fails (e.g., tests fail)
→ User sees hook error (NOT validation error)
→ Status Progression Skill clarifies: "Validation passed, hook failed"
```

**Pattern 3: Advisory doesn't block hooks**
```
User changes to DEPLOYED without env tag
→ StatusValidator allows with advisory
→ Status change applied
→ Hook executes successfully
→ User sees advisory about environment tags
→ Status Progression Skill explains: "Add tag for better tracking"
```

## Workflow Scenarios

### Scenario 1: Solo Dev Pattern (Minimal Statuses)

**Context**: Individual developer, informal process, rapid iteration

**Config settings**:
```yaml
status_validation:
  enforce_sequential: false      # Can skip statuses
  allow_backward: true           # Can go backwards for fixes
  allow_emergency: true          # Can jump to blocked/cancelled
  validate_prerequisites: true   # Still enforce task summaries
```

**Typical flow**:
```
pending → in-progress → completed (skip testing if informal)
```

**Example:**

**User:** "Can I skip testing and go straight to completed?"

**Status Progression Skill Response:**
```
Yes! Your config allows skipping statuses (enforce_sequential: false).

Current: in-progress
Target: completed
Validation: ✅ Transition allowed

Prerequisite check:
- Task summary: Required (at most 500 chars) - Current: 420 chars ✅

Safe to proceed:
request_transition(containerId="...", containerType="task", trigger="complete")
```

### Scenario 2: Small Team Pattern (With Review Gates)

**Context**: 2-5 person team, code review required, staged testing

**Config settings**:
```yaml
status_validation:
  enforce_sequential: true       # Must follow flow
  allow_backward: true           # Can return for fixes
  allow_emergency: true          # Can handle blockers
  validate_prerequisites: true   # Enforce quality gates
```

**Typical flow**:
```
pending → in-progress → in-review → changes-requested → in-progress → in-review → testing → completed
```

**Example:**

**User:** "Code review requested changes. What do I do?"

**Status Progression Skill Response:**
```
Your workflow supports review iterations:

Current: in-review
Next: changes-requested (reviewer action)
Then: Back to in-progress (implement fixes)
Finally: in-review again (re-submit)

Review iteration flow:
in-review → changes-requested → in-progress → in-review → testing → completed

Your config allows backward transitions (allow_backward: true), enabling this rework loop.

Implementing changes:
request_transition(containerId="...", containerType="task", trigger="start")

After fixes:
request_transition(containerId="...", containerType="task", trigger="start")

Re-submit for review:
request_transition(containerId="...", containerType="task", trigger="start")
```

### Scenario 3: Enterprise Pattern (Strict Process)

**Context**: Large team, compliance requirements, formal approvals

**Config settings**:
```yaml
status_validation:
  enforce_sequential: true       # Strict sequential flow
  allow_backward: false          # No backwards (audit trail)
  allow_emergency: true          # Can handle blockers
  validate_prerequisites: true   # Strict quality gates
```

**Typical flow**:
```
backlog → pending → in-progress → in-review → testing → ready-for-qa → completed → deployed
```

**Example:**

**User:** "Tests failed. Can I go back to in-progress?"

**Status Progression Skill Response:**
```
No backward transitions allowed (allow_backward: false).

Current: testing
Cannot: Go back to in-progress (violates config)

Your options (compliance-safe):
1. Create new task for fixes (preserves audit trail)
2. Use emergency transition to blocked
3. Cancel this task, create fixed version

Recommended for audit compliance:
# Mark as blocked
request_transition(containerId="...", containerType="task", trigger="block")

# Create fix task
manage_container(operation="create", containerType="task",
  title="Fix tests for original task",
  description="Tests failed: [details]. This task implements fixes.",
  featureId="...", status="pending")

This preserves complete workflow history for compliance audits.
```

## Prerequisite Rules

Enforced by StatusValidator when `validate_prerequisites: true`:

### Feature Prerequisites

| Target Status | Prerequisite Rule |
|---------------|-------------------|
| `in-development` | Must have ≥1 task |
| `testing` | All tasks completed or cancelled |
| `completed` | All tasks completed or cancelled |

**Example validation errors:**

**Error:** "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT"
```
What it means: Feature has 0 tasks
Fix: Create at least one task
Tool call:
manage_container(operation="create", containerType="task",
  featureId="...", title="...", description="...")
```

**Error:** "Cannot transition to TESTING: 2 task(s) not completed. Incomplete tasks: \"Add tests\", \"Update docs\""
```
What it means: 2 tasks still incomplete
Fix options:
1. Complete remaining tasks
2. Cancel unnecessary tasks

To complete:
request_transition(containerId="task-id", containerType="task", trigger="complete")

To cancel:
request_transition(containerId="task-id", containerType="task", trigger="cancel")
```

### Task Prerequisites

| Target Status | Prerequisite Rule |
|---------------|-------------------|
| `in-progress` | No incomplete blocking dependencies |
| `completed` | Summary must be at most 500 characters |

**Example validation errors:**

**Error:** "Cannot transition to IN_PROGRESS: Task has 1 incomplete blocking dependency"
```
What it means: A task that blocks this one is not complete
Fix: Complete the blocking task first
Check dependencies:
query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)
```

**Error:** "Task summary must be at most 500 characters (current: 50)"
```
What it means: Summary too short for completion
Fix: Update summary with detailed description
Tool call:
manage_container(operation="update", containerType="task",
  id="...", summary="Implemented user authentication with JWT tokens. Added login endpoint, token validation middleware, and refresh token flow. All tests passing, integrated with existing user service.")
```

### Project Prerequisites

| Target Status | Prerequisite Rule |
|---------------|-------------------|
| `completed` | All features must be completed |

**Example validation error:**

**Error:** "Cannot transition to COMPLETED: 1 feature(s) not completed. Incomplete features: \"User Dashboard\""
```
What it means: Feature "User Dashboard" still incomplete
Fix: Complete the feature
Tool call:
request_transition(containerId="feature-id", containerType="feature", trigger="complete")
```

## Error Troubleshooting

### Transition Validation Errors

#### Error: "Cannot skip statuses. Must transition through: in-progress"

**Cause:** `enforce_sequential: true` requires following flow order

**Current state:** pending → testing (skipped in-progress)

**Fixes:**
1. **Follow flow sequentially:**
```javascript
request_transition(containerId="...", containerType="task", trigger="start")
// Then later:
request_transition(containerId="...", containerType="task", trigger="start")
```

2. **Change config** (if sequential not needed):
```yaml
# Edit .taskorchestrator/config.yaml
status_validation:
  enforce_sequential: false
```

#### Error: "Backward transition from 'testing' to 'in-progress' not allowed"

**Cause:** `allow_backward: false` prevents moving backwards

**Current state:** testing → in-progress (backward move)

**Fixes:**
1. **Use emergency transition:**
```javascript
request_transition(containerId="...", containerType="task", trigger="block")
// Then create new task for fixes
```

2. **Change config** (if rework allowed):
```yaml
# Edit .taskorchestrator/config.yaml
status_validation:
  allow_backward: true
```

#### Error: "Cannot transition from terminal status 'completed'"

**Cause:** Terminal statuses don't allow further transitions

**Current state:** completed (terminal)

**Fixes:**
1. **Create new task/feature** (if more work needed)
2. **Emergency transition not allowed from terminal** - terminal is final

### Status Enumeration Errors

#### Error: "Invalid status value: 'in_testing'"

**Cause:** Status value not in enum (should be `testing`)

**Fix:** Use correct status name
```javascript
// Wrong:
request_transition(trigger="in_testing")

// Correct:
request_transition(trigger="start")
```

**Valid statuses:** See [Status Flow Diagrams](#status-flow-diagrams)

### Configuration Errors

#### Error: "Status 'deployed' not in allowed_statuses for tasks"

**Cause:** Status not enabled in config (v2.0 derives from flows)

**Check config:**
```yaml
status_progression:
  tasks:
    default_flow: [backlog, pending, in-progress, testing, completed]
    # 'deployed' not in any flow
```

**Fixes:**
1. **Add custom flow with deployed:**
```yaml
tasks:
  deployment_flow: [pending, in-progress, testing, deployed]
  flow_mappings:
    - tags: [deployment, infra]
      flow: deployment_flow
```

2. **Use emergency transitions:**
```yaml
tasks:
  emergency_transitions: [blocked, on-hold, cancelled, deferred, deployed]
```

### DEPLOYED Status Advisory

#### Advisory: "No environment tag detected"

**Cause:** DEPLOYED status without environment tag (advisory, not error)

**Current state:** Feature marked deployed, no `env:*` tag

**Recommendation:**
```javascript
manage_container(operation="update", containerType="feature",
  id="...", tags="api,backend,env:production")
```

**Standard environment tags:**
- `env:dev` - Development
- `env:staging` - Staging/QA
- `env:production` - Production
- `env:canary` - Canary deployment

## Batch Transitions

Task Orchestrator supports batch status transitions for completing multiple tasks in one call.

### When to Use Batch Transitions

**Use batch mode when:**
- Completing multiple tasks simultaneously
- Bulk-updating task statuses after a sprint
- Programmatic workflows that need atomic validation

**Benefits:**
- Fewer API calls (1 call instead of N)
- Aggregated cascade detection
- Combined unblocked task identification
- Atomic validation (all succeed or all fail)

### Batch Transition Example

**Scenario**: Complete 5 tasks that are all ready

**Single mode (old way)**:
```javascript
// 5 separate calls
request_transition(containerId="task-1", containerType="task", trigger="complete")
request_transition(containerId="task-2", containerType="task", trigger="complete")
request_transition(containerId="task-3", containerType="task", trigger="complete")
request_transition(containerId="task-4", containerType="task", trigger="complete")
request_transition(containerId="task-5", containerType="task", trigger="complete")
```

**Batch mode (new way)**:
```javascript
request_transition(
  transitions=[
    {containerId: "task-1", containerType: "task", trigger: "complete"},
    {containerId: "task-2", containerType: "task", trigger: "complete"},
    {containerId: "task-3", containerType: "task", trigger: "complete"},
    {containerId: "task-4", containerType: "task", trigger: "complete"},
    {containerId: "task-5", containerType: "task", trigger: "complete"}
  ]
)
```

### Batch Response Format

**Response includes:**
- `results` - Array of individual transition results
- `totalSuccessful` - Count of successful transitions
- `totalFailed` - Count of failed transitions
- `cascadeEvents` - Aggregated parent entity advances
- `aggregateUnblockedTasks` - All newly unblocked tasks (deduplicated)

**Example response**:
```json
{
  "success": true,
  "message": "Completed 5 transitions",
  "data": {
    "results": [
      {
        "success": true,
        "containerId": "task-1",
        "newStatus": "completed",
        "previousStatus": "testing",
        "previousRole": "work",
        "newRole": "terminal",
        "activeFlow": "default_flow",
        "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
        "flowPosition": 4,
        "unblockedTasks": []
      },
      // ... results for task-2, task-3, task-4
      {
        "success": true,
        "containerId": "task-5",
        "newStatus": "completed",
        "previousStatus": "in-progress",
        "previousRole": "work",
        "newRole": "terminal",
        "activeFlow": "default_flow",
        "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
        "flowPosition": 4,
        "unblockedTasks": [
          {
            "taskId": "task-6-uuid",
            "title": "Integration testing"
          }
        ]
      }
    ],
    "totalSuccessful": 5,
    "totalFailed": 0,
    "cascadeEvents": [
      {
        "entityType": "feature",
        "entityId": "feature-uuid",
        "previousStatus": "in-development",
        "newStatus": "testing",
        "reason": "All child tasks completed"
      }
    ],
    "aggregateUnblockedTasks": [
      {
        "taskId": "task-6-uuid",
        "title": "Integration testing"
      }
    ]
  }
}
```

### Flow Context in Responses

All transition responses now include flow context fields:

**activeFlow**: The workflow flow name (e.g., "default_flow", "bug_fix_flow")

**flowSequence**: Complete ordered list of statuses in the flow

**flowPosition**: Current index in flowSequence (0-based)

**Example - Using Flow Context**:
```javascript
// Task at "in-progress" in default_flow
{
  "activeFlow": "default_flow",
  "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
  "flowPosition": 2
}

// Calculate progress
const progress = (flowPosition / (flowSequence.length - 1)) * 100;
// Result: 50% through workflow

// Check next status
const nextStatus = flowSequence[flowPosition + 1];
// Result: "testing"

// Check if near completion
const isNearEnd = flowPosition >= flowSequence.length - 2;
// Result: false (2 >= 3 is false)
```

### Partial Failures

Batch transitions validate each transition independently. If some succeed and some fail, the response includes both:

**Example - Mixed Results**:
```json
{
  "success": false,
  "message": "Completed 3 of 5 transitions",
  "data": {
    "results": [
      {
        "success": true,
        "containerId": "task-1",
        "newStatus": "completed"
      },
      {
        "success": false,
        "containerId": "task-2",
        "error": {
          "code": "PREREQUISITE_NOT_MET",
          "message": "Task summary must be at most 500 characters (current: 50)"
        }
      },
      {
        "success": true,
        "containerId": "task-3",
        "newStatus": "completed"
      },
      {
        "success": false,
        "containerId": "task-4",
        "error": {
          "code": "BLOCKED",
          "message": "Task has 1 incomplete blocking dependency"
        }
      },
      {
        "success": true,
        "containerId": "task-5",
        "newStatus": "completed"
      }
    ],
    "totalSuccessful": 3,
    "totalFailed": 2,
    "cascadeEvents": [],
    "aggregateUnblockedTasks": []
  }
}
```

### Batch Best Practices

1. **Check prerequisites first**: Use `get_next_status` or task queries to verify readiness before batch transitions

2. **Group by feature**: Batch tasks from the same feature to maximize cascade detection

3. **Handle partial failures**: Check `totalFailed` and iterate through `results` to find failures

4. **Act on cascades**: When `cascadeEvents` is non-empty, parent entities advanced automatically

5. **Act on unblocked tasks**: Use `aggregateUnblockedTasks` to find newly available work

## Role Annotations

Task Orchestrator v2.0 assigns semantic **role annotations** to statuses, providing agents with context about what each status means in the workflow lifecycle.

### Status Roles

The 5 role categories:

- **queue** - Work waiting to be started (pending, backlog, draft, planning)
- **work** - Active implementation (in-progress, in-development, investigating, changes-requested)
- **review** - Validation and quality gates (in-review, testing, validating, pending-review, ready-for-qa)
- **blocked** - Impediments preventing progress (blocked, on-hold)
- **terminal** - Final states (completed, cancelled, deferred, archived, deployed)

### Role Configuration

Role mappings are defined in `src/main/resources/configuration/default-config.yaml` under the `status_roles` section:

```yaml
status_progression:
  tasks:
    status_roles:
      backlog: queue
      pending: queue
      in-progress: work
      in-review: review
      changes-requested: work
      testing: review
      ready-for-qa: review
      investigating: work
      blocked: blocked
      on-hold: blocked
      completed: terminal
      cancelled: terminal
      deferred: terminal
```

### Role Annotations in Tool Responses

Both `get_next_status` and `request_transition` include role annotations in their responses:

**get_next_status response**:
```json
{
  "recommendation": "Ready",
  "currentStatus": "in-progress",
  "currentRole": "work",
  "recommendedStatus": "testing",
  "recommendedRole": "review"
}
```

**request_transition response**:
```json
{
  "previousStatus": "in-progress",
  "newStatus": "testing",
  "previousRole": "work",
  "newRole": "review",
  "activeFlow": "default_flow"
}
```

### Why Roles Matter

Role annotations enable agents to understand workflow semantics:
- **Queue vs Work**: Distinguish between waiting (queue) and active execution (work)
- **Review Gates**: Identify validation checkpoints (review role)
- **Blockers**: Recognize impediments requiring intervention (blocked role)
- **Terminal States**: Know when entities are complete and immutable (terminal role)

This semantic context helps AI agents make better decisions about task prioritization, workflow navigation, and automation logic.

## Verification Gates

Features and tasks can optionally require **verification gates** before completion. When `requiresVerification` is set to `true`, the "complete" trigger validates that all verification criteria are met before allowing completion.

### Setting Verification Required

Set the flag when creating or updating entities:

```javascript
// Create task with verification gate
manage_container(
  operation="create",
  containerType="task",
  title="Implement authentication",
  requiresVerification=true
)

// Enable verification on existing feature
manage_container(
  operation="update",
  containerType="feature",
  id="feature-uuid",
  requiresVerification=true
)
```

### How Verification Gates Work

When `request_transition(trigger="complete")` is called:

1. **Validation runs**: Standard prerequisite checks (summary, dependencies, etc.)
2. **Verification gate check**: If `requiresVerification=true`, `VerificationGateService.checkVerificationSection()` validates criteria
3. **Blocked if verification fails**: Completion is prevented with detailed error message
4. **Proceeds if verification passes**: Status change applied normally

**Implementation reference**: `RequestTransitionTool.kt` lines 462-484

### Verification Failure Response

When verification gate blocks completion:

```json
{
  "applied": false,
  "error": "Completion blocked: Verification section incomplete",
  "containerId": "task-uuid",
  "containerType": "task",
  "gate": "verification",
  "failingCriteria": [
    "All test cases must pass",
    "Documentation must be updated"
  ]
}
```

### Use Cases for Verification Gates

- **Quality gates**: Ensure tests pass, documentation updated, security review complete
- **Compliance requirements**: Validate regulatory checks before marking complete
- **Stakeholder sign-off**: Require explicit approval before completion
- **Multi-step validation**: Enforce multiple criteria before allowing task closure

## Completion Cleanup

When a feature reaches a **terminal status** (completed, archived), Task Orchestrator automatically cleans up transient work artifacts to maintain a lean database.

### What Gets Cleaned Up

When a feature completes:
- **Child tasks** - Tasks are transient work items deleted after feature completion
- **Task sections** - Section content associated with deleted tasks
- **Task dependencies** - Dependency relationships involving deleted tasks

### What Is Preserved

- **The feature itself** - Features are durable records preserved for context
- **Feature sections** - Feature section content is retained
- **Bug-tagged tasks** - Tasks with bug-related tags (configurable) are retained for diagnostics
- **Projects** - Projects are never automatically deleted

### Configuration

Completion cleanup is configured in `src/main/resources/configuration/default-config.yaml`:

```yaml
completion_cleanup:
  # Master switch - set to false to keep all tasks indefinitely
  enabled: true

  # Tasks with these tags survive cleanup (case-insensitive matching)
  retain_tags: [bug, bugfix, fix, hotfix, critical]
```

### Example Cleanup Behavior

**Scenario**: Feature with 10 tasks (8 regular, 2 bug fixes) reaches "completed" status

**Result**:
- 8 regular tasks deleted (with their sections and dependencies)
- 2 bug fix tasks retained (matched `retain_tags: [bug, bugfix]`)
- Feature and its sections preserved
- Project structure unchanged

### Disabling Cleanup

To preserve all tasks permanently:

```yaml
completion_cleanup:
  enabled: false
```

### Terminal Statuses Triggering Cleanup

Cleanup runs when feature reaches any terminal status defined in `status_progression.features.terminal_statuses`:
- `completed`
- `archived`

Tasks reaching terminal statuses do NOT trigger cleanup (only features trigger cleanup).

## request_transition vs manage_container

Understanding when to use `request_transition` versus `manage_container(setStatus)` is critical for correct workflow behavior.

| Aspect | request_transition | manage_container(setStatus) |
|--------|-------------------|---------------------------|
| **Validation** | Full prerequisite + dependency checks | No validation |
| **Cascades** | Automatic detection via `WorkflowServiceImpl.detectCascadeEvents()` | None |
| **Unblocked tasks** | Reports newly unblocked downstream tasks | None |
| **Flow context** | Returns activeFlow, flowSequence, flowPosition | None |
| **Role annotations** | Returns previousRole, newRole | None |
| **Batch support** | `transitions` array with aggregate cascade detection | `bulkUpdate` (but hooks block status changes) |
| **Trigger-based** | Named triggers (start, complete, cancel, block, hold) | Raw status values |
| **Verification gates** | Enforces `requiresVerification` flag | Skips verification |
| **Completion cleanup** | Triggers cleanup on terminal status | Triggers cleanup on terminal status |

### When to Use request_transition

**Always use request_transition for status changes.** It provides:
- Validation to prevent invalid transitions
- Cascade detection to advance parent entities
- Unblocked task identification for workflow optimization
- Flow context for progress tracking
- Role annotations for semantic understanding

### When to Use manage_container

**Never use manage_container(setStatus) for status changes.** The only valid use case for `manage_container` with status is:
- **Initial entity creation**: Setting status during `operation="create"` (not recommended, entities default to appropriate starting status)

For all status changes after creation, use `request_transition`.

### Example Comparison

**Wrong approach** (skips validation and cascades):
```javascript
// Dangerous - no validation, no cascades
manage_container(operation="setStatus", containerType="task", id="...", status="completed")
```

**Correct approach** (full workflow support):
```javascript
// Correct - validation, cascades, unblocked tasks, flow context
request_transition(containerId="...", containerType="task", trigger="complete")

// Response provides actionable workflow intelligence:
{
  "newStatus": "completed",
  "cascadeEvents": [{...}],           // Parent feature advanced
  "unblockedTasks": [{...}],          // Downstream work now available
  "activeFlow": "default_flow",       // Workflow context
  "previousRole": "work",             // Was active implementation
  "newRole": "terminal"               // Now complete
}
```

## Best Practices

### 1. Use request_transition for Status Changes

**Don't:**
```javascript
// NEVER use manage_container for status changes (skips validation and cascades)
manage_container(operation="setStatus", status="completed")
```

**Do:**
```javascript
// ALWAYS use request_transition for validation, cascade detection, and flow context
request_transition(containerId="uuid", containerType="task", trigger="complete")

// Response includes flow context, cascadeEvents, and unblockedTasks
// No need for get_next_status beforehand (optional for preview only)
```

### 2. Use Tag-Based Flows

**Don't:**
```javascript
// Force all tasks through same flow
default_flow: [backlog, pending, in-progress, testing, completed]
```

**Do:**
```yaml
# Customize flows by type
flow_mappings:
  - tags: [bug, bugfix]
    flow: bug_fix_flow  # Skip backlog
  - tags: [hotfix, urgent]
    flow: hotfix_flow   # Skip backlog + pending
```

### 3. Add Environment Tags to Deployments

**Don't:**
```javascript
// Deploy without environment tracking
request_transition(trigger="complete")
```

**Do:**
```javascript
// Track deployment environment with tags
manage_container(operation="update", containerId="...", containerType="feature", tags="feature-tag,env:staging")
request_transition(containerId="...", containerType="feature", trigger="complete")
```

### 4. Configure Validation for Your Team

**Solo dev:**
```yaml
status_validation:
  enforce_sequential: false  # Skip statuses freely
  allow_backward: true       # Fix mistakes
```

**Small team:**
```yaml
status_validation:
  enforce_sequential: true   # Follow flow
  allow_backward: true       # Rework allowed
```

**Enterprise:**
```yaml
status_validation:
  enforce_sequential: true   # Strict flow
  allow_backward: false      # Audit trail
```

### 5. Integrate Hooks for Automation

**Examples:**
- Git commit on task completion
- Slack notification on deployment
- Test execution before testing status
- Deployment scripts on DEPLOYED status

See [Hook Integration Examples](#hook-integration-examples)

## Additional Resources

- **Config reference:** See `src/main/resources/configuration/default-config.yaml`
- **API documentation:** `docs/api-reference.md`
- **Status Progression Skill:** `claude-plugins/task-orchestrator/skills/status-progression/SKILL.md`
- **Hook system:** `docs/hooks.md` (if exists)
