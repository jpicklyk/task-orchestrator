---
skill: status-progression
description: Two-part status workflow assistant - (1) Status History Navigation for understanding current workflow state, and (2) Status Validation for ensuring proper transitions and prerequisites. Reads user's config.yaml for personalized workflow guidance, explains validation rules, interprets StatusValidator errors, and provides DEPLOYED status + environment tag guidance.
---

# Status Progression Skill

**Two-part workflow assistant** providing status history navigation and validation consulting by reading `.taskorchestrator/config.yaml` for personalized workflow guidance.

## Skill Architecture: Two-Part Workflow

This skill implements a **two-part workflow pattern** for complete status lifecycle management:

### Part 1: Status History Navigation (Understanding State)
**Purpose**: Understand current workflow state and status history

**When to use:**
- "Where are we in the workflow?"
- "What's the status history for this feature?"
- "How did we get to this status?"

**Actions:**
1. Read entity to get current status and history
2. Map status against configured workflow
3. Show progression path taken
4. Identify current position in flow

**Example navigation:**
```javascript
// Feature currently at TESTING status
query_container(operation="get", containerType="feature", id="...")
// Returns: status="testing", modifiedAt history

// Show navigation:
"Feature workflow: planning → in-development → testing [YOU ARE HERE] → validating → completed"
```

### Part 2: Status Validation (Ensuring Safe Transitions)
**Purpose**: Validate transitions are allowed and prerequisites met before status changes

**When to use:**
- "Can I move to testing?"
- "Am I ready for completion?"
- "Why did my status change fail?"

**Actions:**
1. Read config.yaml for validation rules
2. Check target status is allowed
3. Verify transition is permitted (sequential, backward, emergency)
4. Validate prerequisites are met (task counts, summaries, dependencies)
5. Provide actionable fixes if blocked

**Example validation:**
```javascript
// Before attempting status change
config = Read(".taskorchestrator/config.yaml")
overview = query_container(operation="overview", containerType="feature", id="...")

// Check prerequisites
if (status="testing" requires all tasks completed) {
  if (overview.taskCounts.byStatus.pending > 0) {
    return "Not ready: 3 pending tasks must complete first"
  }
}
```

## Two-Part Workflow Benefits

**Separation of concerns:**
- **Navigation** answers "where am I?" (read-only, no validation)
- **Validation** answers "can I proceed?" (checks rules, prerequisites)

**Token efficiency:**
- Navigation uses lightweight overview queries
- Validation only runs when needed (before transitions)
- Cached config prevents repeated file reads

**Clear user experience:**
- Users understand current state separately from transition rules
- Error messages reference both current position AND validation requirements

## DEPLOYED Status & Environment Tags (v2.0)

The **DEPLOYED** status represents successful deployment to an environment. Unlike COMPLETED (work finished), DEPLOYED means the feature/task is **live and accessible** in a specific environment.

### DEPLOYED vs COMPLETED

**Use COMPLETED when:**
- Work is finished, validated, and merged
- Ready for deployment but not yet deployed
- Final status for non-deployable work (documentation, planning)

**Use DEPLOYED when:**
- Feature/task is live in an environment (staging, production, canary)
- Users/systems can actively access the deployed code
- Tracking deployment state is important for rollback or verification

**Example flow:**
```
testing → validating → completed → DEPLOYED (to staging) → DEPLOYED (to production)
```

### Environment Tag Convention

**Pattern**: Always tag DEPLOYED entities with environment tag(s) for tracking:

**Standard environment tags:**
- `env:dev` - Development environment
- `env:staging` - Staging/QA environment
- `env:production` - Production environment
- `env:canary` - Canary deployment (partial production rollout)

**Multiple deployments:**
Entities can be DEPLOYED to multiple environments simultaneously. Use multiple tags:
```javascript
manage_container(operation="setStatus", containerType="feature",
  id="...", status="deployed", tags="env:staging,env:canary")
```

### Environment Tag Advisory

**StatusValidator provides advisory** (not error) when DEPLOYED status lacks environment tag:

**Advisory message:**
```
Status changed to DEPLOYED successfully.

ADVISORY: No environment tag detected. Consider adding environment tag for deployment tracking:
- env:staging (staging environment)
- env:production (production environment)
- env:canary (canary deployment)
- env:dev (development environment)

Example:
manage_container(operation="update", containerType="feature",
  id="...", tags="existing-tags,env:production")
```

**Why advisory, not error?**
- Environment tags are best practice, not required
- Some projects may not need environment tracking
- Users control their own tagging conventions
- Advisory educates without blocking workflow

### DEPLOYED Workflow Examples

#### Example 1: Staged Deployment (Typical Team Pattern)

**Feature flow with staged deployment:**
```
1. planning → in-development (tasks being implemented)
2. in-development → testing (all tasks complete, running tests)
3. testing → validating (tests passed, final validation)
4. validating → completed (validation passed, ready for deployment)
5. completed → deployed + env:staging (deployed to staging for QA)
6. [QA validation in staging]
7. Update tags: env:staging,env:production (deployed to production)
```

**Tool calls:**
```javascript
// Step 5: Deploy to staging
manage_container(operation="setStatus", containerType="feature",
  id="feature-id", status="deployed")
manage_container(operation="update", containerType="feature",
  id="feature-id", tags="api,backend,env:staging")

// Step 7: Deploy to production (add tag)
manage_container(operation="update", containerType="feature",
  id="feature-id", tags="api,backend,env:staging,env:production")
```

#### Example 2: Canary Deployment (Production Rollout)

**Canary deployment pattern:**
```
1. completed → deployed + env:canary (5% of traffic)
2. [Monitor metrics for 24 hours]
3. Add env:production tag (100% rollout)
```

**Tool calls:**
```javascript
// Canary deployment (5% traffic)
manage_container(operation="setStatus", containerType="feature",
  id="...", status="deployed")
manage_container(operation="update", containerType="feature",
  id="...", tags="frontend,ui,env:canary")

// Full production rollout (after monitoring)
manage_container(operation="update", containerType="feature",
  id="...", tags="frontend,ui,env:canary,env:production")
```

#### Example 3: Solo Dev Pattern (Minimal Statuses)

**Simplified flow for solo developers:**
```
1. pending → in-progress (working on task)
2. in-progress → testing (implementation done, running tests)
3. testing → deployed + env:production (tests passed, deploy directly)
```

**Tool calls:**
```javascript
// Skip intermediate statuses (if enforce_sequential: false)
manage_container(operation="setStatus", containerType="task",
  id="...", status="deployed")
manage_container(operation="update", containerType="task",
  id="...", tags="hotfix,bugfix,env:production")
```

### DEPLOYED Status in config.yaml

**v2.0 status system includes DEPLOYED:**

**Tasks** (default-config.yaml):
```yaml
tasks:
  allowed_statuses:
    - backlog
    - pending
    - in-progress
    - in-review
    - changes-requested
    - testing
    - blocked
    - on-hold
    - completed
    - cancelled
    - deferred
    # NOT in default flow, but available via emergency_transitions or custom flows
    # DEPLOYED is a valid enum value but not in default task workflows
```

**Features** (default-config.yaml):
```yaml
features:
  allowed_statuses:
    - draft
    - planning
    - in-development
    - testing
    - validating
    - pending-review
    - blocked
    - on-hold
    - completed
    - archived
    - deployed  # Available for deployment tracking
```

**Note**: DEPLOYED is in the enum (code) but may not be in default flows (config). Users can add custom flows:

```yaml
# Custom deployment flow (user's config.yaml)
features:
  deployment_flow:
    - completed
    - deployed  # Add DEPLOYED to flow
```

### Checking Deployment Status

**Query deployed entities:**
```javascript
// Find all features deployed to production
query_container(operation="search", containerType="feature",
  status="deployed", tags="env:production")

// Get deployment overview
query_container(operation="overview", containerType="feature", id="...")
// Check status="deployed" and tags for environment
```

**Navigation example:**
```
User: "What features are in production?"

Actions:
1. Query deployed features with production tag
2. Show list with deployment dates (modifiedAt)

Response:
"3 features currently deployed to production:
- User Authentication (deployed 2024-01-15, env:production)
- Payment Integration (deployed 2024-01-18, env:staging,env:production)
- Dashboard Redesign (deployed 2024-01-20, env:production)"
```

## When to Use This Skill

**Activate for:**
- "What status comes after X?"
- "What statuses are allowed?"
- "Why did my status change fail?"
- "Am I ready to move to testing?"
- "What validation rules are active?"
- "Can I skip from pending to completed?"

**This skill handles:**
- Reading user's config.yaml for live workflows
- Explaining what statuses are allowed in their config
- Interpreting StatusValidator error messages
- Checking readiness before status transitions
- Explaining active validation rules

## Tools Available

- `Read` - Read config.yaml file
- `query_container` - Check current status, task counts
- `query_dependencies` - Check blocking dependencies

**Note:** This skill does NOT call `manage_container` - it only guides. StatusValidator performs actual validation automatically.

## Your Role: Configuration Consultant

You are a **configuration reader and interpreter**, NOT a validator:

**You DO:**
- ✅ Read `.taskorchestrator/config.yaml` from project root
- ✅ Parse YAML to understand user's workflows
- ✅ Provide recommendations based on THEIR configuration
- ✅ Interpret StatusValidator errors with context
- ✅ Help users check readiness before transitions

**You do NOT:**
- ❌ Validate status transitions (StatusValidator does this automatically)
- ❌ Modify configuration (users edit manually)
- ❌ Hardcode status flows (always read from config)

### What Happens Automatically

When users call `manage_container(operation="setStatus", ...)`:

**StatusValidator automatically:**
1. Validates status value (is it a valid enum?)
2. Checks config enablement (is it in allowed_statuses?)
3. Validates transition (does flow allow current → new?)
4. Checks prerequisites (are requirements met?)
5. Returns detailed error if validation fails

**Your job:** Help users understand what StatusValidator will check and interpret its error messages.

## Core Patterns

### 1. Reading Config

**Always start here:**
```javascript
// Read config file
configPath = "$CLAUDE_PROJECT_DIR/.taskorchestrator/config.yaml"
config = Read(file_path=configPath)

// Parse and extract relevant sections
status_progression = config.status_progression
status_validation = config.status_validation
```

### 2. Explaining Flows

**Show user's actual flows from config:**
```javascript
// Get flow for entity type
flow = config.status_progression.tasks.default_flow
// e.g., [pending, in-progress, testing, completed]

// Show user their flow
"According to your config: " + flow.join(" → ")
```

### 3. Interpreting Common Errors

**Error: "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT"**

```
What it means: StatusValidator checked prerequisites and found 0 tasks
Your config: validate_prerequisites: true enforces this rule

How to fix:
manage_container(operation="create", containerType="task", featureId="...", ...)
```

**Error: "Cannot transition to TESTING: 2 task(s) not completed"**

```
What it means: Prerequisite check found incomplete tasks
Your config requires: All tasks completed before TESTING

How to fix:
1. Complete remaining tasks, OR
2. Cancel unnecessary tasks
```

**Error: "Cannot skip statuses. Must transition through: in-progress"**

```
What it means: enforce_sequential: true in your config
You tried to skip from pending → testing

How to fix:
1. Progress sequentially (respect config), OR
2. Edit config: enforce_sequential: false
```

**Error: "Task summary must be 300-500 characters (current: 50)"**

```
What it means: validate_prerequisites: true requires task summary
Current summary too short

How to fix:
manage_container(operation="update", containerType="task",
  summary="[300-500 char description of what was accomplished]")
```

### 4. Checking Readiness

**Before user attempts transition:**
```javascript
// Read config to know requirements
config = readConfig()
// e.g., "testing" requires all tasks completed

// Check current state
overview = query_container(operation="overview", containerType="feature", id="...")

// Compare and report
if (incomplete_tasks > 0) {
  "Not ready. Your config requires all tasks done. Currently X tasks incomplete."
} else {
  "Ready! All prerequisites met according to your config."
}
```

### 5. Explaining Validation Rules

**Show what's active:**
```javascript
rules = config.status_validation

"Your validation rules:
- enforce_sequential: " + rules.enforce_sequential + " (skip statuses allowed: " + !rules.enforce_sequential + ")
- allow_backward: " + rules.allow_backward + " (can move backwards: " + rules.allow_backward + ")
- allow_emergency: " + rules.allow_emergency + " (can jump to blocked/cancelled: " + rules.allow_emergency + ")
- validate_prerequisites: " + rules.validate_prerequisites + " (prerequisite checks: " + rules.validate_prerequisites + ")"
```

## Configuration Structure

Located at: `.taskorchestrator/config.yaml`

**Key sections:**
```yaml
status_progression:
  tasks:
    allowed_statuses: [...]
    default_flow: [...]
    emergency_transitions: [...]
    terminal_statuses: [...]

  features:
    allowed_statuses: [...]
    default_flow: [...]
    ...

status_validation:
  enforce_sequential: true/false    # Must go through each status in order
  allow_backward: true/false        # Can move backwards for rework
  allow_emergency: true/false       # Can jump to blocked/cancelled/deferred
  validate_prerequisites: true/false # Enforce requirements before changes
```

## Prerequisite Rules

Enforced by StatusValidator when `validate_prerequisites: true`:

**Features:**
- `in-development`: Must have ≥1 task
- `testing`: All tasks completed or cancelled
- `completed`: All tasks completed or cancelled

**Tasks:**
- `in-progress`: No incomplete blocking dependencies
- `completed`: Summary must be 300-500 characters

**Projects:**
- `completed`: All features must be completed

## When Config Missing

If `.taskorchestrator/config.yaml` doesn't exist:

```
No config found. StatusValidator using fallback mode:
- All enum statuses allowed
- No transition rules enforced
- No prerequisite validation

To enable config-driven validation:
1. Run setup_claude_orchestration tool
2. Creates .taskorchestrator/config.yaml
3. Customize rules as needed
```

## Examples

### Example 1: What's Next?

**User:** "What status comes after in-progress?"

**Your Actions:**
1. Read config.yaml
2. Extract tasks.default_flow
3. Find current position
4. Return next status

**Response:**
```
According to your config, after "in-progress" comes "testing".

Your task flow: pending → in-progress → testing → completed
```

### Example 2: Why Did It Fail?

**User gets error:** "Cannot transition to COMPLETED: 1 task(s) not completed. Incomplete tasks: \"Add tests\""

**Response:**
```
StatusValidator blocked this because your config requires all tasks completed before COMPLETED.

Found incomplete: "Add tests"

Fix options:
1. Complete the task (needs 300-500 char summary)
2. Cancel if no longer needed

Your config: validate_prerequisites: true enforces this rule
```

### Example 3: Readiness Check

**User:** "Am I ready to move feature to testing?"

**Your Actions:**
1. Read config → testing requires all tasks done
2. Get feature overview
3. Check task counts
4. Report readiness

**Response (Not Ready):**
```
Not ready. Your config requires all tasks completed for TESTING.

Current:
- Total: 8 tasks
- Completed: 5
- In-progress: 2 ← Blocking
- Pending: 1 ← Blocking

Action: Complete or cancel 3 tasks first
```

**Response (Ready):**
```
✅ Ready! All prerequisites met.

Requirements: All tasks completed (your config)
Current: 8/8 tasks completed

Safe to proceed:
manage_container(operation="setStatus", containerType="feature",
  id="...", status="testing")
```

## Status Flow Diagrams (v2.0)

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

Task Orchestrator supports **custom hooks** that execute automatically on status changes. This Skill helps users understand hook triggering and validation interaction.

### How Hooks Work with Status Validation

**Execution order:**
```
1. User calls manage_container(operation="setStatus", ...)
2. StatusValidator validates status and transition (THIS SKILL helps interpret)
3. If valid, status change applied
4. Hooks execute AFTER successful status change
5. Hook output returned to user
```

**Key insight**: Hooks execute **after** validation passes. This Skill helps users **pass validation** so hooks can execute.

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

**User workflow with Status Progression Skill:**

**User:** "Can I complete this task?"

**Skill Actions:**
1. Read config.yaml → completed requires summary (300-500 chars)
2. Check task → summary is 50 chars
3. Report blocker

**Skill Response:**
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

**User workflow with Status Progression Skill:**

**User:** "Deploy feature to production"

**Skill Actions:**
1. Validate feature is ready (all tasks complete, tests passed)
2. Guide user through deployment with environment tags

**Skill Response:**
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

**User workflow with Status Progression Skill:**

**User:** "Why did my status change to testing fail?"

**Skill Actions:**
1. Read error message from hook execution
2. Interpret hook failure vs validation failure

**Skill Response:**
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

**User workflow with Status Progression Skill:**

**User:** "Show me the workflow history for this feature"

**Skill Actions (Navigation):**
1. Read status-log.txt (if accessible via hook output or file)
2. Parse status transitions
3. Map against configured workflow

**Skill Response:**
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
→ StatusValidator blocks (THIS SKILL explains why)
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
→ THIS SKILL clarifies: "Validation passed, hook failed"
```

**Pattern 3: Advisory doesn't block hooks**
```
User changes to DEPLOYED without env tag
→ StatusValidator allows with advisory
→ Status change applied
→ Hook executes successfully
→ User sees advisory about environment tags
→ THIS SKILL explains: "Add tag for better tracking"
```

### When to Use This Skill with Hooks

**Skill helps with:**
- ✅ Understanding why validation blocked hook execution
- ✅ Preparing for status changes that trigger hooks
- ✅ Interpreting hook failure vs validation failure
- ✅ Checking prerequisites before attempting transitions
- ✅ Navigating workflow history captured by hooks

**Skill does NOT:**
- ❌ Execute hooks (manage_container does this)
- ❌ Configure hooks (users edit hooks.yaml manually)
- ❌ Debug hook scripts (bash/script errors are external)
- ❌ Validate hook configuration syntax

## Workflow Scenario Examples

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

**Skill usage example:**

**User:** "Can I skip testing and go straight to completed?"

**Skill Response:**
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

**Skill usage example:**

**User:** "Code review requested changes. What do I do?"

**Skill Response:**
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

**Skill usage example:**

**User:** "Tests failed. Can I go back to in-progress?"

**Skill Response:**
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

## Integration

**Works alongside:**
- Feature Orchestration Skill - Uses config for quality gates
- Task Orchestration Skill - Uses config for execution planning
- Dependency Analysis Skill - Complements prerequisite checking

**Delegates to:**
- StatusValidator (automatic) - Actual validation when manage_container called

## Best Practices

1. **Read config first** - Source of truth for workflows
2. **Show THEIR flows** - Not generic defaults
3. **Interpret contextually** - Reference their specific config rules
4. **Provide fixes** - Step-by-step with exact tool calls
5. **Check proactively** - Help users avoid errors before attempting

## Key Reminders

- You read and interpret, StatusValidator validates
- Always reference user's actual config
- Explain automation: "StatusValidator does X automatically"
- Config path: `.taskorchestrator/config.yaml`
