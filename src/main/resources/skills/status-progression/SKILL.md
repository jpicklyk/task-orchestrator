---
skill: status-progression
description: Read user's config.yaml to provide live guidance on status workflows, explain validation rules in effect, and interpret StatusValidator errors. Help users understand what their configuration allows and check readiness before transitions.
---

# Status Progression Skill

Dynamic configuration consultant that reads `.taskorchestrator/config.yaml` to provide personalized status workflow guidance and validation error interpretation.

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

## Status Flow Diagrams

### Feature Flow (Default)

```
planning → in-development → testing → validating → completed

Emergency: Any → blocked/archived (if allow_emergency: true)
```

### Task Flow (Default)

```
pending → in-progress → testing → completed

Emergency: Any → blocked/cancelled/deferred (if allow_emergency: true)
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
