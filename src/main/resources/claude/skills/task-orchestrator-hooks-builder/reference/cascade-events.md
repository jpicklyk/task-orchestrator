# Cascade Events - Detailed Reference

This document provides comprehensive information about cascade events in Task Orchestrator v2.0+.

## What Are Cascade Events?

Cascade events occur when completing one entity should trigger progression of a parent entity:

| Event | Trigger | Effect |
|-------|---------|--------|
| **first_task_started** | First task in feature moves to `in-progress` | Feature should move from `planning` to `in-development` |
| **all_tasks_complete** | All tasks in feature complete | Feature should move to `testing` |
| **all_features_complete** | All features in project complete | Project should move to `completed` |

Cascade events enable **automatic workflow progression** without manual intervention.

## How Hooks Receive Cascade Events

When `manage_container` operations trigger cascade events, the tool response includes a `cascadeEvents` array:

```json
{
  "tool": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "task",
    "id": "task-uuid-123",
    "status": "completed"
  },
  "tool_output": {
    "success": true,
    "data": {
      "cascadeEvents": [
        {
          "event": "all_tasks_complete",
          "targetType": "feature",
          "targetId": "feature-uuid-456",
          "targetName": "User Authentication",
          "currentStatus": "in-development",
          "suggestedStatus": "testing",
          "flow": "default_flow",
          "automatic": true,
          "reason": "All 8 tasks completed successfully"
        }
      ]
    }
  }
}
```

## Cascade Event Fields

| Field | Type | Description |
|-------|------|-------------|
| **event** | string | Event type: `first_task_started`, `all_tasks_complete`, `all_features_complete` |
| **targetType** | string | Entity type affected: `task`, `feature`, `project` |
| **targetId** | UUID | ID of the entity that should change status |
| **targetName** | string | Human-readable name of the entity |
| **currentStatus** | string | Current status of the entity |
| **suggestedStatus** | string | Recommended next status based on workflow |
| **flow** | string | Active workflow flow: `default_flow`, `rapid_prototype_flow`, `with_review_flow` |
| **automatic** | boolean | `true` if safe to auto-apply, `false` if manual confirmation recommended |
| **reason** | string | Human-readable explanation of why this cascade event occurred |

## Workflow Flows

Cascade events include a `flow` field indicating the active workflow:

### default_flow
- Normal development workflow
- Standard quality gates
- Typical progression: planning → in-development → testing → completed

### rapid_prototype_flow
- Fast iteration for prototypes and experiments
- Relaxed quality gates (tests may be skipped)
- Detected by tags: `prototype`, `spike`, `experiment`

### with_review_flow
- Security and compliance features
- Strict quality gates (additional validation required)
- Detected by tags: `security`, `compliance`, `audit`

## Hook Integration Patterns

### Pattern 1: Opinionated Auto-Apply (Recommended)

Automatically log cascade events for auto-application when `automatic=true`:

```bash
#!/bin/bash
# Opinionated auto-progress

INPUT=$(cat)
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ]; then
  exit 0
fi

echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  AUTOMATIC=$(echo "$event" | jq -r '.automatic')

  if [ "$AUTOMATIC" == "true" ]; then
    echo "✅ AUTO-APPLY: Logging for orchestrator to apply status"
    # Log event for audit trail
    # Orchestration Skills will apply status change
  else
    echo "⚠️  MANUAL CONFIRMATION: User approval required"
  fi
done

exit 0
```

**When to use**: Normal development workflows where you trust cascade logic.

**Template**: See Template 12 in hook-templates.md

### Pattern 2: Flow-Aware Quality Gates

Adapt quality gates based on workflow flow:

```bash
#!/bin/bash
# Flow-aware quality gate

INPUT=$(cat)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

# Only run for feature status changes to testing/completed
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "feature" ]; then
  exit 0
fi

# Query feature tags to determine flow
FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_input.id')
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TAGS=$(sqlite3 "$DB_PATH" "SELECT tags FROM Features WHERE id='$FEATURE_ID'" 2>/dev/null)

# Determine flow from tags
FLOW="default_flow"
if echo "$TAGS" | grep -qE "prototype|spike|experiment"; then
  FLOW="rapid_prototype_flow"
elif echo "$TAGS" | grep -qE "security|compliance|audit"; then
  FLOW="with_review_flow"
fi

case "$FLOW" in
  "rapid_prototype_flow")
    echo "⚡ Rapid prototype flow: Skipping tests"
    exit 0
    ;;
  "with_review_flow")
    echo "🔒 Security flow: Enforcing strict validation"
    ./gradlew test integrationTest securityScan || {
      cat << EOF
{
  "decision": "block",
  "reason": "Security features must pass all quality gates."
}
EOF
      exit 0
    }
    ;;
  "default_flow")
    ./gradlew test || {
      cat << EOF
{
  "decision": "block",
  "reason": "Tests are failing."
}
EOF
      exit 0
    }
    ;;
esac

exit 0
```

**When to use**: Projects with different feature types (prototypes, production, security).

**Template**: See Template 13 in hook-templates.md

### Pattern 3: Analytics and Metrics

Non-blocking observation for understanding patterns:

```bash
#!/bin/bash
# Cascade event logger (safe for production)

INPUT=$(cat)
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ]; then
  exit 0
fi

LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$LOG_DIR"
CASCADE_LOG="$LOG_DIR/cascade-events.csv"

# Create header if needed
if [ ! -f "$CASCADE_LOG" ]; then
  echo "timestamp,event,target_type,target_id,target_name,current_status,suggested_status,flow,automatic" \
    > "$CASCADE_LOG"
fi

# Log each event
echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  EVENT_TYPE=$(echo "$event" | jq -r '.event')
  TARGET_TYPE=$(echo "$event" | jq -r '.targetType')
  # ... extract other fields ...

  echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,..." >> "$CASCADE_LOG"
done

exit 0
```

**When to use**: Always safe - observation only, never blocks or modifies behavior.

**Template**: See Template 15 in hook-templates.md

### Pattern 4: Progressive Disclosure (Conservative)

Show suggestions but require manual confirmation:

```bash
#!/bin/bash
# Conservative progressive disclosure

INPUT=$(cat)
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ]; then
  exit 0
fi

echo "════════════════════════════════════════════"
echo "🔔 WORKFLOW CASCADE EVENTS DETECTED"
echo "════════════════════════════════════════════"

echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  TARGET_NAME=$(echo "$event" | jq -r '.targetName')
  CURRENT=$(echo "$event" | jq -r '.currentStatus')
  SUGGESTED=$(echo "$event" | jq -r '.suggestedStatus')

  echo "💡 Suggestion: $TARGET_NAME ($CURRENT → $SUGGESTED)"
  echo "   ⚠️  Manual confirmation required"
done

exit 0
```

**When to use**: Critical production deployments, security features, learning environments.

**Template**: See Template 16 in hook-templates.md

## Working Examples

Three complete example hooks are provided in `example-hooks/`:

1. **cascade-auto-progress.sh** - Opinionated auto-apply with audit trail (RECOMMENDED)
2. **flow-aware-gate.sh** - Adaptive quality gates based on workflow flow
3. **cascade-logger.sh** - Non-blocking analytics (SAFE for production)

See `examples.md` for:
- Installation instructions
- Expected behavior
- Sample test inputs
- Customization guidance
- Testing strategies

## Opinionated vs Conservative Approaches

### Opinionated (Recommended)
- ✅ Auto-apply when `automatic=true`
- ✅ Trust Task Orchestrator's cascade logic
- ✅ Fast workflow progression
- ✅ Audit trail via logging
- ⚠️ Not suitable for critical production, security features

### Conservative
- ✅ Always require manual confirmation
- ✅ Full control over every transition
- ✅ Suitable for any environment
- ⚠️ Slower workflow progression
- ⚠️ More manual intervention

### Migration Path

1. **Start Conservative**: Use cascade-logger.sh (observation only)
2. **Learn Patterns**: After 1-2 weeks, review `.claude/metrics/cascade-summary.json`
3. **Adopt Opinionated**: If `auto_percentage > 80%`, switch to cascade-auto-progress.sh
4. **Add Flow Gates**: Once comfortable, add flow-aware-gate.sh

## Hook Templates

Five cascade event templates are available in `hook-templates.md`:

- **Template 12**: Cascade Event Responder (Opinionated) - Auto-apply with audit trail
- **Template 13**: Flow-Aware Quality Gate - Adaptive quality gates per flow
- **Template 14**: Custom Event Handler - React to specific cascade events
- **Template 15**: Cascade Event Logger - Analytics and metrics
- **Template 16**: Progressive Disclosure (Conservative) - Manual confirmation

## Configuration Example

Register cascade event hooks in `.claude/settings.local.json`:

```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"],
        "comment": "Always log first (observation only)"
      },
      {
        "hook": ".claude/hooks/flow-aware-gate.sh",
        "tools": ["mcp__task-orchestrator__manage_container"],
        "comment": "Then run quality gates (may block)"
      },
      {
        "hook": ".claude/hooks/cascade-auto-progress.sh",
        "tools": ["mcp__task-orchestrator__manage_container"],
        "comment": "Finally handle cascade progression"
      }
    ]
  }
}
```

**Recommended order**:
1. Logger (observation, always safe)
2. Quality gates (may block if tests fail)
3. Auto-progress (applies status changes)

## Custom Event Handlers

You can create hooks that react to specific cascade events:

```bash
#!/bin/bash
# Custom event handler - react to specific events

INPUT=$(cat)
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  EVENT_TYPE=$(echo "$event" | jq -r '.event')

  case "$EVENT_TYPE" in
    "all_tasks_complete")
      # Handle feature completion
      echo "Feature ready for testing"
      # Run feature-level validation
      ;;
    "all_features_complete")
      # Handle project completion
      echo "Project ready for release"
      # Run release preparation
      ;;
    "first_task_started")
      # Handle development start
      echo "Feature development started"
      # Create feature branch, notify team
      ;;
  esac
done

exit 0
```

**Template**: See Template 14 in hook-templates.md

## Custom Workflow Flows

You can define custom flows by detecting specific tag patterns:

```bash
# Determine flow from tags
FLOW="default_flow"

if echo "$TAGS" | grep -qE "hotfix|urgent"; then
  FLOW="hotfix_flow"
elif echo "$TAGS" | grep -qE "experimental|research"; then
  FLOW="experimental_flow"
elif echo "$TAGS" | grep -qE "prototype|spike"; then
  FLOW="rapid_prototype_flow"
elif echo "$TAGS" | grep -qE "security|compliance"; then
  FLOW="with_review_flow"
fi
```

Then adapt hook behavior based on the custom flow.

## Troubleshooting

### Cascade Events Not Appearing

**Check operation**: Cascade events only occur on specific operations:
- Completing a task (when all tasks in feature complete)
- Starting first task in a feature
- Completing all features in a project

**Check entity has children**:
- Feature must have tasks
- Project must have features
- Otherwise no cascade occurs

### Hook Not Reacting to Cascade Events

**Verify JSON path**: Check that you're extracting from correct path:
```bash
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')
```

**Check for empty array**:
```bash
if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi
```

### Flow Detection Not Working

**Check database query**: Verify SQLite query returns tags:
```bash
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TAGS=$(sqlite3 "$DB_PATH" "SELECT tags FROM Features WHERE id='$FEATURE_ID'" 2>/dev/null)

if [ -z "$TAGS" ]; then
  echo "⚠️  Could not determine feature tags, using default_flow"
fi
```

**Test tag patterns**: Ensure grep patterns match your tags:
```bash
# Test the pattern
echo "prototype,frontend,api" | grep -qE "prototype|spike|experiment" && echo "Matched!"
```

## Further Reading

- **examples.md** - Complete installation and usage guide for example hooks
- **hook-templates.md** - Templates 12-16 for cascade events
- **docs/hooks-guide.md** - Comprehensive hooks documentation with cascade event integration
