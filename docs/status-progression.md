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

Task Orchestrator v2.0 uses an **event-driven status progression pattern** where Skills detect workflow events and delegate to the Status Progression system for intelligent status recommendations.

### Core Principle

**Skills detect workflow events, not status names.** The Status Progression system determines appropriate status transitions based on your configuration.

### Universal Workflow Events

**Feature Events** that trigger status checks:
- `first_task_started` - First task begins execution → May progress feature to in-development
- `all_tasks_complete` - All tasks finished → May progress to testing/completed
- `tests_passed` / `tests_failed` - Quality validation results
- `review_approved` / `changes_requested` - Human review outcomes
- `blocker_detected` / `feature_cancelled` - Exception handling

**Task Events** that trigger status checks:
- `work_started` - Implementation begins → May move to in-progress
- `implementation_complete` - Code + tests written → May move to testing/review
- `tests_passed` / `tests_failed` - Automated test results
- `review_approved` / `changes_requested` - Code review outcomes
- `blocker_detected` / `task_cancelled` - Exception handling

**How It Works:**
1. **Event occurs** (task completes, tests run, etc.)
2. **Skills detect event** using query tools
3. **Skills delegate** to Status Progression Skill
4. **get_next_status tool analyzes** state and config
5. **Recommendation returned** based on your configured flows
6. **Status change applied** if prerequisites met

This architecture ensures status progression adapts to your custom workflows without hardcoding status names in Skills.

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
1. User calls manage_container(operation="setStatus", ...)
2. StatusValidator validates status and transition
3. If valid, status change applied
4. Hooks execute AFTER successful status change
5. Hook output returned to user
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
1. Read config.yaml → completed requires summary (300-500 chars)
2. Check task → summary is 50 chars
3. Report blocker

**Response:**
```
Not ready for completion. Your config requires task summary (300-500 chars).

Current: 50 characters
Required: 300-500 characters

The git-commit hook will trigger AFTER completion, but validation must pass first.

Fix:
manage_container(operation="update", containerType="task",
  id="...", summary="[300-500 char description of what was accomplished]")

Then complete:
manage_container(operation="setStatus", containerType="task",
  id="...", status="completed")
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
manage_container(operation="setStatus", containerType="feature",
  id="...", status="deployed")

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
- Task summary: Required (300-500 chars) - Current: 420 chars ✅

Safe to proceed:
manage_container(operation="setStatus", containerType="task",
  id="...", status="completed")
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
manage_container(operation="setStatus", containerType="task",
  id="...", status="changes-requested")

After fixes:
manage_container(operation="setStatus", containerType="task",
  id="...", status="in-progress")

Re-submit for review:
manage_container(operation="setStatus", containerType="task",
  id="...", status="in-review")
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
# Mark as blocked with reason
manage_container(operation="setStatus", containerType="task",
  id="...", status="blocked")

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
manage_container(operation="setStatus", containerType="task",
  id="task-id", status="completed")

To cancel:
manage_container(operation="setStatus", containerType="task",
  id="task-id", status="cancelled")
```

### Task Prerequisites

| Target Status | Prerequisite Rule |
|---------------|-------------------|
| `in-progress` | No incomplete blocking dependencies |
| `completed` | Summary must be 300-500 characters |

**Example validation errors:**

**Error:** "Cannot transition to IN_PROGRESS: Task has 1 incomplete blocking dependency"
```
What it means: A task that blocks this one is not complete
Fix: Complete the blocking task first
Check dependencies:
query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)
```

**Error:** "Task summary must be 300-500 characters (current: 50)"
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
manage_container(operation="setStatus", containerType="feature",
  id="feature-id", status="completed")
```

## Error Troubleshooting

### Transition Validation Errors

#### Error: "Cannot skip statuses. Must transition through: in-progress"

**Cause:** `enforce_sequential: true` requires following flow order

**Current state:** pending → testing (skipped in-progress)

**Fixes:**
1. **Follow flow sequentially:**
```javascript
manage_container(operation="setStatus", containerType="task",
  id="...", status="in-progress")
// Then later:
manage_container(operation="setStatus", containerType="task",
  id="...", status="testing")
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
manage_container(operation="setStatus", containerType="task",
  id="...", status="blocked")
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
manage_container(operation="setStatus", status="in_testing")

// Correct:
manage_container(operation="setStatus", status="testing")
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

## Best Practices

### 1. Always Check Readiness Before Transitions

**Don't:**
```javascript
// Blindly attempt transition
manage_container(operation="setStatus", status="completed")
// Hope it works
```

**Do:**
```javascript
// Check first with Status Progression Skill or get_next_status
recommendation = get_next_status(currentStatus="testing", containerType="task", tags=["bug"])
// Verify prerequisites met
// Then transition
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
manage_container(operation="setStatus", status="deployed")
```

**Do:**
```javascript
// Track deployment environment
manage_container(operation="setStatus", status="deployed")
manage_container(operation="update", tags="feature-tag,env:staging")
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

- **Config reference:** See `src/main/resources/orchestration/default-config.yaml`
- **API documentation:** `docs/api-reference.md`
- **Status Progression Skill:** `.claude/skills/status-progression/SKILL.md`
- **Hook system:** `docs/hooks.md` (if exists)
