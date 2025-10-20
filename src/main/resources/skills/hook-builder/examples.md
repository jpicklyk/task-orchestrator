# Hook Builder Examples

Complete working examples of hooks that integrate Task Orchestrator with your workflow.

## Example 1: Auto-Commit on Task Completion

**Scenario**: Automatically create git commits when tasks are marked complete

**File**: `.claude/hooks/task-complete-commit.sh`

```bash
#!/bin/bash
# Auto-commit when task is marked complete

# Read JSON input from stdin
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for task status changes
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract status and task ID
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Only proceed if status is 'completed'
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Check if we're in a git repository
if [ ! -d "$CLAUDE_PROJECT_DIR/.git" ]; then
  echo "Not a git repository, skipping commit"
  exit 0
fi

# Get task title from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id='$TASK_ID'" 2>/dev/null)

if [ -z "$TASK_TITLE" ]; then
  TASK_TITLE="Unknown Task"
fi

# Check if there are changes to commit
cd "$CLAUDE_PROJECT_DIR"
if ! git diff-index --quiet HEAD --; then
  # Create git commit
  git add -A
  git commit -m "feat: Complete task - $TASK_TITLE" -m "Task-ID: $TASK_ID"
  echo "✓ Created git commit for completed task"
else
  echo "No changes to commit"
fi

exit 0
```

**Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-complete-commit.sh"
          }
        ]
      }
    ]
  }
}
```

**Test Command**:
```bash
echo '{
  "tool_name": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "task",
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed"
  }
}' | .claude/hooks/task-complete-commit.sh
```

**Customizations**:
- Change commit message format
- Add conventional commit types based on task tags
- Skip commits for certain task types
- Push to remote automatically

## Example 2: Quality Gate on Feature Completion

**Scenario**: Run tests before allowing feature to be marked complete, block if tests fail

**File**: `.claude/hooks/feature-complete-gate.sh`

```bash
#!/bin/bash
# Quality gate: Run tests before feature completion

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for feature status changes
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "feature" ]; then
  exit 0
fi

# Extract feature status
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

# Only run when feature is being marked completed
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Run project tests
cd "$CLAUDE_PROJECT_DIR"

echo "Running tests before feature completion..."

# Run tests (customize for your project)
./gradlew test

if [ $? -ne 0 ]; then
  # Tests failed - block feature completion
  cat << EOF
{
  "decision": "block",
  "reason": "Feature marked complete but tests are failing. Please review test failures and fix before completing feature."
}
EOF
  exit 0
fi

echo "✓ All tests passed. Feature completion approved."
exit 0
```

**Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/feature-complete-gate.sh",
            "timeout": 300
          }
        ]
      }
    ]
  }
}
```

**Test Command**:
```bash
echo '{
  "tool_name": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "feature",
    "id": "7f2882b5-3334-4c60-940e-4c8464f93023",
    "status": "completed"
  }
}' | .claude/hooks/feature-complete-gate.sh
```

**Customizations**:
- Run specific test suites (unit, integration, e2e)
- Check code coverage thresholds
- Run linting before tests
- Allow override with flag in feature metadata

## Example 3: Subagent Completion Logger

**Scenario**: Log when subagents complete for metrics and analysis

**File**: `.claude/hooks/subagent-stop-logger.sh`

```bash
#!/bin/bash
# Log subagent completion for metrics

# Read JSON input
INPUT=$(cat)

# Extract session ID
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Parse transcript to get subagent type
TRANSCRIPT=$(echo "$INPUT" | jq -r '.transcript_path')

if [ -f "$TRANSCRIPT" ]; then
  # Extract subagent type from transcript
  SUBAGENT_TYPE=$(tail -50 "$TRANSCRIPT" | grep -o '"subagent_type":"[^"]*"' | tail -1 | cut -d'"' -f4)
else
  SUBAGENT_TYPE="unknown"
fi

# Create metrics directory if needed
METRICS_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$METRICS_DIR"

# Log to CSV file
LOG_FILE="$METRICS_DIR/subagent-completions.csv"

# Create header if file doesn't exist
if [ ! -f "$LOG_FILE" ]; then
  echo "timestamp,session_id,subagent_type" > "$LOG_FILE"
fi

# Append log entry
echo "$TIMESTAMP,$SESSION_ID,$SUBAGENT_TYPE" >> "$LOG_FILE"

echo "✓ Logged subagent completion: $SUBAGENT_TYPE"
exit 0
```

**Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "SubagentStop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/subagent-stop-logger.sh"
          }
        ]
      }
    ]
  }
}
```

**Test Command**:
```bash
echo '{
  "session_id": "test-session-123",
  "transcript_path": "/path/to/transcript.json"
}' | .claude/hooks/subagent-stop-logger.sh
```

**Customizations**:
- Log additional metrics (duration, token count)
- Send to external analytics service
- Generate daily/weekly reports
- Alert on long-running subagents

## Example 4: Task Creation Notification

**Scenario**: Send webhook notification when new tasks are created

**File**: `.claude/hooks/task-create-notify.sh`

```bash
#!/bin/bash
# Send notification when tasks are created

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for task creation
if [ "$OPERATION" != "create" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract task details from output
TASK_ID=$(echo "$INPUT" | jq -r '.tool_output.data.id')
TASK_TITLE=$(echo "$INPUT" | jq -r '.tool_output.data.title')
FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_output.data.featureId')

# Skip if no feature (standalone task)
if [ "$FEATURE_ID" = "null" ] || [ -z "$FEATURE_ID" ]; then
  exit 0
fi

# Get feature name from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
FEATURE_NAME=$(sqlite3 "$DB_PATH" \
  "SELECT name FROM Features WHERE id='$FEATURE_ID'" 2>/dev/null)

# Send webhook notification (customize URL and payload)
WEBHOOK_URL="https://hooks.slack.com/services/YOUR/WEBHOOK/URL"

curl -X POST "$WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"text\": \"New task created: *$TASK_TITLE*\",
    \"blocks\": [
      {
        \"type\": \"section\",
        \"text\": {
          \"type\": \"mrkdwn\",
          \"text\": \"*Task*: $TASK_TITLE\\n*Feature*: $FEATURE_NAME\\n*ID*: \`$TASK_ID\`\"
        }
      }
    ]
  }" 2>/dev/null

echo "✓ Sent notification for new task"
exit 0
```

**Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-create-notify.sh"
          }
        ]
      }
    ]
  }
}
```

## Example 5: High-Priority Task Alert

**Scenario**: Special handling for high-priority tasks

**File**: `.claude/hooks/high-priority-alert.sh`

```bash
#!/bin/bash
# Alert when high-priority tasks are created or updated

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for task operations
if [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract task ID (from input for update, from output for create)
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // .tool_output.data.id')

# Query task priority
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
PRIORITY=$(sqlite3 "$DB_PATH" \
  "SELECT priority FROM Tasks WHERE id='$TASK_ID'" 2>/dev/null)

# Only proceed for high priority
if [ "$PRIORITY" != "high" ]; then
  exit 0
fi

# Get task details
TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id='$TASK_ID'" 2>/dev/null)

TASK_STATUS=$(sqlite3 "$DB_PATH" \
  "SELECT status FROM Tasks WHERE id='$TASK_ID'" 2>/dev/null)

# Visual alert
echo "⚠️  HIGH PRIORITY TASK: $TASK_TITLE (Status: $TASK_STATUS)"

# Could also:
# - Send email
# - Create calendar event
# - Update external project management tool
# - Log to priority tracking file

exit 0
```

**Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/high-priority-alert.sh"
          }
        ]
      }
    ]
  }
}
```

**Note**: This single matcher now catches both create and update operations for tasks. The hook script filters by containerType to ensure it only processes task operations.

## Example 6: Dependency Blocker Detection

**Scenario**: Alert when task is blocked by dependencies

**File**: `.claude/hooks/dependency-blocker-alert.sh`

```bash
#!/bin/bash
# Alert when creating tasks with blocking dependencies

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for task creation
if [ "$OPERATION" != "create" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Get task ID from output
TASK_ID=$(echo "$INPUT" | jq -r '.tool_output.data.id')

# Check if task has dependencies
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
BLOCKING_COUNT=$(sqlite3 "$DB_PATH" \
  "SELECT COUNT(*) FROM TaskDependencies
   WHERE dependentTaskId='$TASK_ID'" 2>/dev/null)

if [ "$BLOCKING_COUNT" -gt 0 ]; then
  TASK_TITLE=$(sqlite3 "$DB_PATH" \
    "SELECT title FROM Tasks WHERE id='$TASK_ID'" 2>/dev/null)

  echo "⚠️  Task '$TASK_TITLE' is blocked by $BLOCKING_COUNT dependencies"

  # List blocking tasks
  sqlite3 "$DB_PATH" \
    "SELECT t.title FROM Tasks t
     JOIN TaskDependencies d ON t.id = d.taskId
     WHERE d.dependentTaskId='$TASK_ID'" 2>/dev/null | \
     while read BLOCKING_TASK; do
       echo "  - $BLOCKING_TASK"
     done
fi

exit 0
```

**Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/dependency-blocker-alert.sh"
          }
        ]
      }
    ]
  }
}
```

## Example 7: Task Metrics Tracker

**Scenario**: Track task completion times for analytics

**File**: `.claude/hooks/task-metrics-tracker.sh`

```bash
#!/bin/bash
# Track task completion metrics

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for task status changes
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Only track when task is completed
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id')
COMPLETION_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Get task details
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TASK_DATA=$(sqlite3 "$DB_PATH" -json \
  "SELECT title, createdAt, complexity, priority, tags
   FROM Tasks WHERE id='$TASK_ID'" 2>/dev/null)

if [ -z "$TASK_DATA" ]; then
  exit 0
fi

CREATED_AT=$(echo "$TASK_DATA" | jq -r '.[0].createdAt')
COMPLEXITY=$(echo "$TASK_DATA" | jq -r '.[0].complexity')
PRIORITY=$(echo "$TASK_DATA" | jq -r '.[0].priority')

# Calculate duration in seconds
if command -v date >/dev/null 2>&1; then
  # Convert timestamps to seconds and calculate duration
  # (This is simplified - real implementation needs better date parsing)
  DURATION_HOURS=$(( ($(date -d "$COMPLETION_TIME" +%s) - $(date -d "$CREATED_AT" +%s)) / 3600 ))
else
  DURATION_HOURS="N/A"
fi

# Log metrics
METRICS_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$METRICS_DIR"
LOG_FILE="$METRICS_DIR/task-completions.csv"

# Create header if needed
if [ ! -f "$LOG_FILE" ]; then
  echo "task_id,completion_time,created_at,duration_hours,complexity,priority" > "$LOG_FILE"
fi

# Append metrics
echo "$TASK_ID,$COMPLETION_TIME,$CREATED_AT,$DURATION_HOURS,$COMPLEXITY,$PRIORITY" >> "$LOG_FILE"

echo "✓ Logged task completion metrics"
exit 0
```

## Example 8: Git Branch Per Feature

**Scenario**: Create git branch when feature is created, merge when completed

**File**: `.claude/hooks/feature-git-branch.sh`

```bash
#!/bin/bash
# Create git branch for features

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only proceed for feature operations
if [ "$CONTAINER_TYPE" != "feature" ]; then
  exit 0
fi

# Extract feature details (ID from output for create, from input for setStatus)
FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_output.data.id // .tool_input.id')
FEATURE_STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // .tool_output.data.status')

# Check if we're in a git repository
if [ ! -d "$CLAUDE_PROJECT_DIR/.git" ]; then
  exit 0
fi

# Get feature name
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
FEATURE_NAME=$(sqlite3 "$DB_PATH" \
  "SELECT name FROM Features WHERE id='$FEATURE_ID'" 2>/dev/null)

if [ -z "$FEATURE_NAME" ]; then
  exit 0
fi

# Create branch name (sanitize)
BRANCH_NAME=$(echo "feature/$FEATURE_NAME" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | sed 's/[^a-z0-9-]//g')

cd "$CLAUDE_PROJECT_DIR"

# Create branch when feature is in_development
if [ "$FEATURE_STATUS" = "in_development" ]; then
  # Check if branch exists
  if ! git rev-parse --verify "$BRANCH_NAME" >/dev/null 2>&1; then
    git checkout -b "$BRANCH_NAME"
    echo "✓ Created branch: $BRANCH_NAME"
  fi
fi

# Merge to main when completed
if [ "$FEATURE_STATUS" = "completed" ]; then
  # Check if we're on the feature branch
  CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
  if [ "$CURRENT_BRANCH" = "$BRANCH_NAME" ]; then
    git checkout main
    git merge "$BRANCH_NAME" --no-ff -m "feat: Complete feature - $FEATURE_NAME"
    echo "✓ Merged feature branch to main"
  fi
fi

exit 0
```

## Testing Multiple Hooks

**Create Test Script**: `.claude/hooks/test-hooks.sh`

```bash
#!/bin/bash
# Test multiple hooks with sample data (v2.0 format)

echo "Testing task completion hook..."
echo '{
  "tool_name": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "task",
    "id": "test-task-id",
    "status": "completed"
  }
}' | .claude/hooks/task-complete-commit.sh

echo ""
echo "Testing feature completion hook..."
echo '{
  "tool_name": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "feature",
    "id": "test-feature-id",
    "status": "completed"
  }
}' | .claude/hooks/feature-complete-gate.sh

echo ""
echo "Testing subagent logger hook..."
echo '{
  "session_id": "test-session",
  "transcript_path": "/dev/null"
}' | .claude/hooks/subagent-stop-logger.sh

echo ""
echo "✓ All hooks tested"
```

## Complete Configuration Example

**File**: `.claude/settings.local.json`

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "comment": "Auto-commit on task completion",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-complete-commit.sh"
          },
          {
            "type": "command",
            "comment": "Track task metrics",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-metrics-tracker.sh"
          },
          {
            "type": "command",
            "comment": "Quality gate for feature completion",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/feature-complete-gate.sh",
            "timeout": 300
          },
          {
            "type": "command",
            "comment": "Git branch management for features",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/feature-git-branch.sh"
          },
          {
            "type": "command",
            "comment": "Notify on task creation",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-create-notify.sh"
          },
          {
            "type": "command",
            "comment": "Alert on high-priority tasks",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/high-priority-alert.sh"
          },
          {
            "type": "command",
            "comment": "Dependency blocker detection",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/dependency-blocker-alert.sh"
          }
        ]
      }
    ],
    "SubagentStop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/subagent-stop-logger.sh"
          }
        ]
      }
    ]
  }
}
```

**v2.0 Consolidation Benefits:**
- Single `manage_container` matcher handles all task/feature/project operations
- Each hook script filters by `operation` and `containerType` to react to specific actions
- Simpler configuration with fewer matcher blocks
- All hooks run on every manage_container call, but exit early if conditions don't match

This configuration enables:
- Auto-commit on task completion (operation=setStatus, containerType=task, status=completed)
- Metrics tracking for completed tasks (operation=setStatus, containerType=task, status=completed)
- Quality gate for feature completion (operation=setStatus, containerType=feature, status=completed)
- Git branch management for features (containerType=feature, any operation)
- Notifications for new tasks (operation=create, containerType=task)
- Alerts for high-priority tasks (containerType=task, any operation)
- Dependency blocker detection (operation=create, containerType=task)
- Subagent completion logging
