---
name: workflows
description: Workflow patterns for feature progress monitoring, status progression, parallel execution, and quality gate integration
version: "2.0.0"
---

# Task Orchestration Workflows

## Feature Progress Monitoring

**When to check and update feature status:**

### Trigger 1: First Task Starts
```
User starts working on first task in feature
  ↓
Task status: pending → in-progress (via Status Progression Skill)
  ↓
Check feature status:
  if feature.status == "planning":
    Use Feature Orchestration Skill to move feature to "in-development"
    Notify user: "Feature [X] moved to IN_DEVELOPMENT (first task started)"
```

### Trigger 2: Any Task Completes
```
Task completes (via Status Progression Skill)
  ↓
Query feature.taskCounts:
  completed: X, total: Y
  ↓
if X < Y:
  Notify user: "Task [T] complete. [Y-X] tasks remaining in feature [F]"
  Check for newly unblocked tasks
  ↓
if X == Y:
  ALL TASKS COMPLETE!
  ↓
  Use Feature Orchestration Skill to progress feature:
    1. Move to "testing" (automatic)
    2. Run quality gates (hooks/manual)
    3. Mark "completed" (after validation)
```

### Trigger 3: Batch Completion
```
All tasks in a batch complete
  ↓
Query feature.taskCounts
  ↓
Check if ALL feature tasks are complete:
  if yes: Trigger Feature Orchestration Skill
  if no: Report progress and check next batch
```

### Trigger 4: Manual Feature Check
```
User asks: "What's the feature status?"
  ↓
Query feature.taskCounts via Feature Orchestration Skill
  ↓
Report: "[X]/[Y] tasks complete"
  ↓
If all complete: Suggest moving to testing/completion
```

## Feature Status Progression Decision Tree

```
Task Status Change
  ↓
  ├─ Task started (pending → in-progress)?
  │  ├─ Is this the first task in feature?
  │  │  └─ YES: Use Feature Orchestration Skill to move feature to "in-development"
  │  └─ NO: Continue task work
  │
  └─ Task completed?
     ├─ Query feature.taskCounts via query_container(operation="overview")
     ├─ All tasks complete?
     │  ├─ YES: Use Feature Orchestration Skill
     │  │  ├─ Move feature to "testing" (automatic)
     │  │  ├─ Run quality gates
     │  │  └─ Mark "completed" (after validation)
     │  └─ NO: Notify user of progress
     │     └─ "[X]/[Y] tasks complete in feature [F]"
     └─ Check for newly unblocked tasks
```

**Automatic vs Manual Confirmation:**

| Transition | Automatic? | Reason |
|------------|-----------|---------|
| planning → in-development | ✅ YES | First task started, obvious progression |
| in-development → testing | ✅ YES | All tasks complete, move to validation |
| testing → completed | ⚠️ ASK USER | Final completion, user should confirm |

## Parallel Execution Management

```
When dependencies allow:
1. Use Task Orchestration Skill to identify parallelizable task groups
2. Skill batches tasks by dependency level
3. Skill launches multiple specialists concurrently (Claude Code) or provides sequential guidance (other clients)
4. Monitor parallel progress
5. Cascade completions through dependency chain
```

## Status Progression

```
Features: planning → in-development → testing → validating → [review] → completed
Tasks: pending → in-progress → testing → [blocked] → completed

ALL status changes via Status Progression Skill (mandatory)
```

### Dynamic Configuration

Status Progression Skill reads `.taskorchestrator/config.yaml` on EVERY invocation:

- **Live configuration reloading:**
  - Skill loads config fresh on each status change attempt
  - No caching - always uses latest config values
  - Changes to config.yaml take effect immediately
  - No server restart required for config updates

- **What Skill reads from config:**
  - `status_progressions.feature` - Valid feature statuses and transitions
  - `status_progressions.task` - Valid task statuses and transitions
  - `status_validation.validate_prerequisites` - Enable/disable prerequisite checks
  - Allowed forward transitions (e.g., planning → in-development)
  - Allowed backward transitions (e.g., testing → in-development for rework)
  - Emergency transitions (any → blocked/archived/cancelled)

- **Validation behavior:**
  - Enforces sequential progression (can't skip statuses)
  - Allows backward transitions (for rework/bug fixes)
  - Allows emergency transitions (block/archive/cancel from any status)
  - Prerequisite validation checks: task counts, summaries, dependencies
  - Validation rules enforced if `validate_prerequisites: true`
  - Validation skipped if `validate_prerequisites: false`

- **Fallback mode:**
  - If config.yaml doesn't exist → Uses enum-based validation (v1.0 mode)
  - Uses hardcoded statuses from ProjectStatus/FeatureStatus/TaskStatus enums
  - Still enforces prerequisite validation unless explicitly disabled

**Example config.yaml:**
```yaml
status_validation:
  validate_prerequisites: true  # Enable prerequisite checks

status_progressions:
  feature:
    - planning
    - in-development
    - testing
    - validating
    - review        # Optional status
    - completed
  task:
    - pending
    - in-progress
    - testing
    - blocked       # Emergency status
    - completed
```

**When to use Status Progression Skill:**
- ✅ ALWAYS for any status change (task, feature, project)
- ✅ Handles validation, config loading, error reporting automatically
- ✅ No manual prerequisite checking needed - Skill does it
- ✅ Dynamic config means rule changes apply immediately

### Quality Gate Integration

```
On feature completion attempt (via Feature Orchestration Skill):
1. Skill checks all tasks completed
2. Skill triggers testing hook (Claude Code) or requests manual test run (other clients)
3. If tests fail → Skill blocks completion
4. If tests pass → Skill continues
5. If review enabled → Skill enters review status
6. Skill marks complete
```
