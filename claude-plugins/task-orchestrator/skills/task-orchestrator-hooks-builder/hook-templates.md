# Hook Templates

Copy-paste templates for common hook patterns. Customize for your specific needs.

## Template 1: Basic PostToolUse Hook (v2.0)

**Purpose**: React to any MCP tool call

```bash
#!/bin/bash
# [Description of what this hook does]

# Read JSON input from stdin
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Defensive check - only proceed if condition is met
# Example: Only react to task status changes
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract specific fields
FIELD=$(echo "$INPUT" | jq -r '.tool_input.field_name')

# Additional defensive check
if [ "$FIELD" != "expected_value" ]; then
  exit 0
fi

# Perform your action here
cd "$CLAUDE_PROJECT_DIR"
# ... your logic ...

echo "✓ Hook completed successfully"
exit 0
```

**Configuration Template**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/YOUR-HOOK.sh"
          }
        ]
      }
    ]
  }
}
```

**v2.0 Note**: Since `manage_container` handles all create/update/delete/setStatus operations, your hook script must filter by `operation` and `containerType` to react to specific actions.

## Template 2: Blocking Quality Gate Hook (v2.0)

**Purpose**: Prevent operations that don't meet criteria

```bash
#!/bin/bash
# [Description of quality gate]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Only run quality gate for specific operations
# Example: Block feature completion unless tests pass
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "feature" ]; then
  exit 0
fi

# Extract field to check
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

# Only run quality gate when marking feature complete
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Run validation check
cd "$CLAUDE_PROJECT_DIR"
./your-validation-command

if [ $? -ne 0 ]; then
  # Validation failed - block the operation
  cat << EOF
{
  "decision": "block",
  "reason": "Detailed explanation of why operation was blocked and what to fix"
}
EOF
  exit 0
fi

echo "✓ Validation passed"
exit 0
```

**Configuration Template**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/YOUR-GATE.sh",
            "timeout": 300
          }
        ]
      }
    ]
  }
}
```

## Template 3: Database Query Hook (v2.0)

**Purpose**: Get data from Task Orchestrator database

```bash
#!/bin/bash
# [Description]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Filter by operation/containerType as needed
if [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract ID to query (from input for update/setStatus, from output for create)
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id // .tool_output.data.id')

# Query database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"

# Single value query
VALUE=$(sqlite3 "$DB_PATH" \
  "SELECT column FROM table WHERE id='$ENTITY_ID'" 2>/dev/null)

# Multiple columns query
RESULT=$(sqlite3 "$DB_PATH" -json \
  "SELECT col1, col2, col3 FROM table WHERE id='$ENTITY_ID'" 2>/dev/null)

# Check if query succeeded
if [ -z "$VALUE" ]; then
  echo "Warning: Could not find record"
  exit 0
fi

# Use the data
echo "Found: $VALUE"

# Your action here based on database data
# ...

exit 0
```

**Common Database Queries**:

```sql
-- Get task details
SELECT title, status, priority, complexity
FROM Tasks
WHERE id='TASK_ID';

-- Get feature details
SELECT name, status, summary
FROM Features
WHERE id='FEATURE_ID';

-- Get task dependencies
SELECT t.title
FROM Tasks t
JOIN TaskDependencies d ON t.id = d.taskId
WHERE d.dependentTaskId='TASK_ID';

-- Count tasks in feature
SELECT COUNT(*)
FROM Tasks
WHERE featureId='FEATURE_ID';

-- Get incomplete tasks
SELECT id, title
FROM Tasks
WHERE featureId='FEATURE_ID' AND status != 'completed';
```

## Template 4: Git Automation Hook (v2.0)

**Purpose**: Automate git operations

```bash
#!/bin/bash
# [Description of git automation]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Filter by operation/containerType
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract relevant data
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

# Only proceed for specific condition
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Check if we're in a git repository
if [ ! -d "$CLAUDE_PROJECT_DIR/.git" ]; then
  echo "Not a git repository, skipping"
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

# Check if there are changes
if git diff-index --quiet HEAD --; then
  echo "No changes to commit"
  exit 0
fi

# Get entity details from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id='$ENTITY_ID'" 2>/dev/null)

# Stage changes
git add -A

# Create commit
git commit -m "TYPE: $TITLE" -m "Entity-ID: $ENTITY_ID"

# Optional: Push to remote
# git push origin $(git rev-parse --abbrev-ref HEAD)

echo "✓ Created git commit"
exit 0
```

**Git Command Patterns**:

```bash
# Create branch
git checkout -b "feature/branch-name"

# Switch branch
git checkout branch-name

# Merge branch (no fast-forward)
git merge branch-name --no-ff -m "Merge message"

# Delete branch
git branch -d branch-name

# Tag release
git tag -a "v1.0.0" -m "Release message"

# Push tags
git push --tags

# Get current branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

# Check if branch exists
if git rev-parse --verify branch-name >/dev/null 2>&1; then
  echo "Branch exists"
fi

# Get last commit message
LAST_COMMIT=$(git log -1 --pretty=%B)
```

## Template 5: Logging/Metrics Hook (v2.0)

**Purpose**: Track events for analytics

```bash
#!/bin/bash
# [Description of what is being logged]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Filter by operation/containerType as needed
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract data to log
FIELD1=$(echo "$INPUT" | jq -r '.tool_input.field1')
FIELD2=$(echo "$INPUT" | jq -r '.tool_input.field2')

# Generate timestamp
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Create metrics directory
METRICS_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$METRICS_DIR"

# Define log file
LOG_FILE="$METRICS_DIR/event-log.csv"

# Create header if file doesn't exist
if [ ! -f "$LOG_FILE" ]; then
  echo "timestamp,field1,field2,field3" > "$LOG_FILE"
fi

# Append log entry
echo "$TIMESTAMP,$FIELD1,$FIELD2,value" >> "$LOG_FILE"

echo "✓ Logged event"
exit 0
```

**Log File Formats**:

```csv
# CSV format (easy to import into Excel/Google Sheets)
timestamp,event_type,entity_id,value1,value2
2025-10-18T14:30:00Z,task_complete,uuid,high,7

# JSON Lines format (easy to parse programmatically)
{"timestamp":"2025-10-18T14:30:00Z","event":"task_complete","id":"uuid"}

# Human-readable format
[2025-10-18 14:30:00 UTC] Task Completed: "Task Title" (ID: uuid)
```

## Template 6: External API Hook (v2.0)

**Purpose**: Send data to external service

```bash
#!/bin/bash
# [Description of API integration]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Filter by operation/containerType as needed
if [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract data (from input for update/setStatus, from output for create)
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id // .tool_output.data.id')

# Get additional data from database if needed
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id='$ENTITY_ID'" 2>/dev/null)

# Prepare API payload
PAYLOAD=$(cat <<EOF
{
  "entity_id": "$ENTITY_ID",
  "title": "$TITLE",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF
)

# Send to API
curl -X POST "https://api.example.com/webhook" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d "$PAYLOAD" \
  -s \
  -o /dev/null \
  -w "HTTP %{http_code}"

echo "✓ Sent to external API"
exit 0
```

**API Integration Patterns**:

```bash
# Slack webhook
curl -X POST "$SLACK_WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d '{"text": "Message"}'

# Discord webhook
curl -X POST "$DISCORD_WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d '{"content": "Message"}'

# Generic REST API with auth
curl -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "$JSON_PAYLOAD"

# GitHub API (create issue)
curl -X POST "https://api.github.com/repos/owner/repo/issues" \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "Issue", "body": "Description"}'

# Jira API (create issue)
curl -X POST "$JIRA_URL/rest/api/2/issue" \
  -u "$JIRA_USER:$JIRA_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fields": {"project": {"key": "PROJ"}, "summary": "Title"}}'
```

## Template 7: Notification Hook (v2.0)

**Purpose**: Send notifications to user or team

```bash
#!/bin/bash
# [Description of notification]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Filter by operation/containerType as needed
if [ "$OPERATION" != "create" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract data for notification (from output for create operations)
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_output.data.id')

# Get entity details
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
DETAILS=$(sqlite3 "$DB_PATH" \
  "SELECT title, status FROM Tasks WHERE id='$ENTITY_ID'" 2>/dev/null)

# Only notify for specific conditions
if [ -z "$DETAILS" ]; then
  exit 0
fi

# Choose notification method

# Method 1: Terminal notification (macOS)
# osascript -e 'display notification "Message" with title "Title"'

# Method 2: Terminal notification (Linux with notify-send)
# notify-send "Title" "Message"

# Method 3: Email (requires mail command)
# echo "Message body" | mail -s "Subject" user@example.com

# Method 4: Slack
# curl -X POST "$SLACK_WEBHOOK" -d '{"text": "Message"}'

# Method 5: Console output (always works)
echo "==================================="
echo "NOTIFICATION"
echo "==================================="
echo "Details: $DETAILS"
echo "==================================="

exit 0
```

## Template 8: SubagentStop Hook

**Purpose**: React to subagent completion

```bash
#!/bin/bash
# [Description of subagent reaction]

# Read JSON input
INPUT=$(cat)

# Extract session info
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id')
TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path')

# Parse transcript if needed
if [ -f "$TRANSCRIPT_PATH" ]; then
  # Extract information from transcript
  SUBAGENT_TYPE=$(tail -50 "$TRANSCRIPT_PATH" | \
    grep -o '"subagent_type":"[^"]*"' | tail -1 | cut -d'"' -f4)

  # Your logic based on subagent type
  case "$SUBAGENT_TYPE" in
    "backend-engineer")
      echo "Backend work completed"
      # Run backend-specific actions
      ;;
    "test-engineer")
      echo "Testing completed"
      # Run test-specific actions
      ;;
    *)
      echo "Subagent completed: $SUBAGENT_TYPE"
      ;;
  esac
fi

# Log or take action
echo "✓ Processed subagent completion"
exit 0
```

## Template 9: Conditional Multi-Action Hook (v2.0)

**Purpose**: Different actions based on conditions

```bash
#!/bin/bash
# [Description of conditional logic]

# Read JSON input
INPUT=$(cat)

# Extract operation and container type (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Filter by operation/containerType
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract fields
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Get additional context from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
PRIORITY=$(sqlite3 "$DB_PATH" \
  "SELECT priority FROM Tasks WHERE id='$ENTITY_ID'" 2>/dev/null)

# Conditional logic
if [ "$STATUS" = "completed" ] && [ "$PRIORITY" = "high" ]; then
  # High priority task completed
  echo "🎉 High priority task completed!"
  # Send urgent notification
  # Create git commit
  # Update dashboard

elif [ "$STATUS" = "completed" ] && [ "$PRIORITY" = "low" ]; then
  # Low priority task completed
  echo "✓ Low priority task completed"
  # Just log it

elif [ "$STATUS" = "blocked" ]; then
  # Task is blocked
  echo "⚠️  Task blocked"
  # Alert team
  # Identify blocker

else
  # Other status changes
  echo "Status changed to: $STATUS"
fi

exit 0
```

## Template 10: Dependency Check

**Purpose**: Check for blocking dependencies

```bash
#!/bin/bash
# Check dependencies before allowing operation

# Read JSON input
INPUT=$(cat)

# Extract entity ID
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Check for incomplete dependencies
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
INCOMPLETE_DEPS=$(sqlite3 "$DB_PATH" \
  "SELECT COUNT(*) FROM Tasks t
   JOIN TaskDependencies d ON t.id = d.taskId
   WHERE d.dependentTaskId='$ENTITY_ID'
   AND t.status != 'completed'" 2>/dev/null)

if [ "$INCOMPLETE_DEPS" -gt 0 ]; then
  # Block operation - dependencies not complete
  cat << EOF
{
  "decision": "block",
  "reason": "Cannot proceed - $INCOMPLETE_DEPS blocking dependencies are incomplete"
}
EOF
  exit 0
fi

echo "✓ All dependencies complete"
exit 0
```

## Template 11: Error Handling Hook

**Purpose**: Robust error handling

```bash
#!/bin/bash
# [Description with robust error handling]

# Enable strict error handling
set -euo pipefail

# Error handler function
error_handler() {
  echo "Error on line $1"
  exit 1
}

trap 'error_handler $LINENO' ERR

# Read JSON input
INPUT=$(cat)

# Check for required tools
command -v jq >/dev/null 2>&1 || {
  echo "Error: jq is required but not installed"
  exit 2
}

command -v sqlite3 >/dev/null 2>&1 || {
  echo "Error: sqlite3 is required but not installed"
  exit 2
}

# Validate environment variables
if [ -z "${CLAUDE_PROJECT_DIR:-}" ]; then
  echo "Error: CLAUDE_PROJECT_DIR not set"
  exit 2
fi

# Extract with fallback
FIELD=$(echo "$INPUT" | jq -r '.tool_input.field // "default_value"')

# Check database exists
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
if [ ! -f "$DB_PATH" ]; then
  echo "Error: Database not found at $DB_PATH"
  exit 2
fi

# Your logic here with error checking
if ! result=$(sqlite3 "$DB_PATH" "SELECT ..." 2>&1); then
  echo "Error querying database: $result"
  exit 2
fi

echo "✓ Hook completed successfully"
exit 0
```

## Configuration Combination Examples (v2.0)

**Multiple Hooks on Consolidated Tool**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {"type": "command", "comment": "Auto-commit", "command": ".claude/hooks/commit.sh"},
          {"type": "command", "comment": "Send notifications", "command": ".claude/hooks/notify.sh"},
          {"type": "command", "comment": "Log metrics", "command": ".claude/hooks/metrics.sh"}
        ]
      }
    ]
  }
}
```

**Note**: In v2.0, the single `manage_container` matcher handles all operations (create, update, delete, setStatus) for all container types (task, feature, project). Each hook script filters by `operation` and `containerType` to react to specific actions.

**Multiple Tools, Different Hooks**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {"type": "command", "comment": "Task/feature operations", "command": ".claude/hooks/container-handler.sh"}
        ]
      },
      {
        "matcher": "mcp__task-orchestrator__manage_sections",
        "hooks": [
          {"type": "command", "comment": "Section operations", "command": ".claude/hooks/section-handler.sh"}
        ]
      },
      {
        "matcher": "mcp__task-orchestrator__get_next_task",
        "hooks": [
          {"type": "command", "command": ".claude/hooks/task-recommender.sh"}
        ]
      }
    ]
  }
}
```

**Mixed Event Types**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [
          {"type": "command", "command": ".claude/hooks/task-complete.sh"}
        ]
      }
    ],
    "SubagentStop": [
      {
        "hooks": [
          {"type": "command", "command": ".claude/hooks/subagent-logger.sh"}
        ]
      }
    ]
  }
}
```

## Template 12: Cascade Event Responder (v2.0 - Opinionated)

**Purpose**: Automatically progress features/projects when cascade events detected (OPINIONATED - auto-applies by default)

```bash
#!/bin/bash
# Auto-progress workflow when cascade events occur
# OPINIONATED: Auto-applies status changes when automatic=true
# For manual confirmation, see Progressive Disclosure template

INPUT=$(cat)

# Extract cascade events from tool response
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

# Check if any cascade events detected
if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi

# Process each cascade event
echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  EVENT_TYPE=$(echo "$event" | jq -r '.event')
  TARGET_TYPE=$(echo "$event" | jq -r '.targetType')
  TARGET_ID=$(echo "$event" | jq -r '.targetId')
  TARGET_NAME=$(echo "$event" | jq -r '.targetName')
  CURRENT_STATUS=$(echo "$event" | jq -r '.currentStatus')
  SUGGESTED_STATUS=$(echo "$event" | jq -r '.suggestedStatus')
  AUTOMATIC=$(echo "$event" | jq -r '.automatic')
  REASON=$(echo "$event" | jq -r '.reason')

  echo "🔄 Cascade Event Detected"
  echo "   Event: $EVENT_TYPE"
  echo "   Target: $TARGET_NAME ($TARGET_TYPE)"
  echo "   Status: $CURRENT_STATUS → $SUGGESTED_STATUS"
  echo "   Reason: $REASON"

  # OPINIONATED: Auto-apply if automatic=true (default behavior)
  if [ "$AUTOMATIC" == "true" ]; then
    echo "✅ Auto-applying status change (automatic=true)"

    # Log the cascade event
    LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
    mkdir -p "$LOG_DIR"
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,$TARGET_ID,$TARGET_NAME,$CURRENT_STATUS,$SUGGESTED_STATUS,auto" \
      >> "$LOG_DIR/cascade-events.csv"

    # TODO: Call manage_container to apply status
    # This requires MCP client support for hook → tool communication
    # For now, we log and rely on Skills to apply
    echo "   ✓ Cascade event logged for auto-application by orchestrator"
  else
    # Manual confirmation required
    echo "⚠️  Manual confirmation required (automatic=false)"
    echo "   → Feature/Task Orchestration Skill will prompt user"

    # Log as manual
    LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
    mkdir -p "$LOG_DIR"
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,$TARGET_ID,$TARGET_NAME,$CURRENT_STATUS,$SUGGESTED_STATUS,manual" \
      >> "$LOG_DIR/cascade-events.csv"
  fi
done

exit 0
```

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "comment": "Auto-progress workflow on cascade events",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/cascade-auto-progress.sh"
          }
        ]
      }
    ]
  }
}
```

**When NOT to use auto-apply:**
- Critical production deployments (use Template 16: Progressive Disclosure)
- Security/compliance workflows requiring human review
- Learning/training environments where manual confirmation is educational
- When you want full control over every status transition

## Template 13: Flow-Aware Quality Gate (v2.0)

**Purpose**: Adaptive quality gates based on workflow flow (default_flow, rapid_prototype_flow, with_review_flow)

```bash
#!/bin/bash
# Flow-aware quality gate - different validation per workflow flow
# OPINIONATED: Skips tests for prototypes, enforces for production

INPUT=$(cat)

# Extract operation and container type
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

# Only run for feature status changes to testing/completed
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "feature" ]; then
  exit 0
fi

if [ "$STATUS" != "testing" ] && [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Extract feature ID
FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Query feature tags to determine active flow
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TAGS=$(sqlite3 "$DB_PATH" \
  "SELECT tags FROM Features WHERE id='$FEATURE_ID'" 2>/dev/null)

# Determine active flow from tags
FLOW="default_flow"
if echo "$TAGS" | grep -qE "prototype|spike|experiment"; then
  FLOW="rapid_prototype_flow"
elif echo "$TAGS" | grep -qE "security|compliance|audit"; then
  FLOW="with_review_flow"
fi

echo "🔍 Detected workflow flow: $FLOW"

# Flow-specific quality gates
case "$FLOW" in
  "rapid_prototype_flow")
    # OPINIONATED: Skip tests for prototypes (fast iteration)
    echo "⚡ Rapid prototype flow: skipping tests for fast iteration"
    echo "   Tags: $TAGS"
    exit 0
    ;;

  "with_review_flow")
    # OPINIONATED: Strict validation for security/compliance
    echo "🔒 Security/compliance flow: enforcing strict validation"
    cd "$CLAUDE_PROJECT_DIR"

    # Run full test suite
    ./gradlew test || {
      cat << EOF
{
  "decision": "block",
  "reason": "Security flow requires all tests to pass. Please fix failing tests."
}
EOF
      exit 0
    }

    # Check for security scan (if available)
    if command -v trivy >/dev/null 2>&1; then
      echo "   Running security scan..."
      trivy fs . || {
        cat << EOF
{
  "decision": "block",
  "reason": "Security vulnerabilities detected. Please remediate before completion."
}
EOF
        exit 0
      }
    fi

    echo "   ✅ Security validation passed"
    ;;

  "default_flow")
    # OPINIONATED: Standard test suite for default flow
    echo "✓ Default flow: running standard test suite"
    cd "$CLAUDE_PROJECT_DIR"
    ./gradlew test || {
      cat << EOF
{
  "decision": "block",
  "reason": "Tests are failing. Please fix before completing feature."
}
EOF
      exit 0
    }
    echo "   ✅ Tests passed"
    ;;
esac

exit 0
```

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "comment": "Flow-aware quality gates",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/flow-aware-gate.sh",
            "timeout": 600
          }
        ]
      }
    ]
  }
}
```

**Customization for Conservative Approach:**
```bash
# To require tests for ALL flows (not just default/security):
case "$FLOW" in
  "rapid_prototype_flow")
    echo "⚡ Prototype flow: running quick smoke tests"
    ./gradlew quickTest || { # Lightweight test suite
      # Block on failure
    }
    ;;
```

## Template 14: Custom Event Handler (v2.0)

**Purpose**: React to specific custom events defined in status-workflow-config.yaml

```bash
#!/bin/bash
# Custom event handler for workflow-specific events
# Example: tests_passed, review_approved, deployment_ready

INPUT=$(cat)

# Extract cascade events
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi

# Look for specific event (customize EVENT_NAME)
EVENT_NAME="tests_passed"  # Change to your custom event

MATCHING_EVENT=$(echo "$CASCADE_EVENTS" | \
  jq -r ".[] | select(.event==\"$EVENT_NAME\")")

if [ -z "$MATCHING_EVENT" ] || [ "$MATCHING_EVENT" == "null" ]; then
  exit 0
fi

# Extract event details
TARGET_TYPE=$(echo "$MATCHING_EVENT" | jq -r '.targetType')
TARGET_ID=$(echo "$MATCHING_EVENT" | jq -r '.targetId')
TARGET_NAME=$(echo "$MATCHING_EVENT" | jq -r '.targetName')
SUGGESTED_STATUS=$(echo "$MATCHING_EVENT" | jq -r '.suggestedStatus')

echo "✅ Custom Event: $EVENT_NAME"
echo "   Target: $TARGET_NAME ($TARGET_TYPE)"
echo "   Suggested: $SUGGESTED_STATUS"

# OPINIONATED: Execute custom logic based on event
case "$EVENT_NAME" in
  "tests_passed")
    echo "   → Triggering deployment pipeline..."
    # Call CI/CD webhook
    # curl -X POST "$DEPLOY_WEBHOOK" -d "{\"id\":\"$TARGET_ID\"}"
    ;;

  "review_approved")
    echo "   → Creating git tag for release..."
    # cd "$CLAUDE_PROJECT_DIR"
    # git tag -a "release-$(date +%Y%m%d)" -m "Release: $TARGET_NAME"
    ;;

  "deployment_ready")
    echo "   → Notifying stakeholders..."
    # Send notifications
    ;;
esac

echo "✓ Custom event handler completed"
exit 0
```

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "comment": "Custom event handlers",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/custom-event-handler.sh"
          }
        ]
      }
    ]
  }
}
```

## Template 15: Cascade Event Logger (v2.0)

**Purpose**: Log all cascade events for analytics and debugging

```bash
#!/bin/bash
# Log all cascade events to CSV for analytics
# Non-blocking, observation-only

INPUT=$(cat)

# Extract cascade events
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi

# Create metrics directory
LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$LOG_DIR"

# Define log files
CASCADE_LOG="$LOG_DIR/cascade-events.csv"
SUMMARY_LOG="$LOG_DIR/cascade-summary.json"

# Create header if file doesn't exist
if [ ! -f "$CASCADE_LOG" ]; then
  echo "timestamp,event,target_type,target_id,target_name,current_status,suggested_status,flow,automatic,reason" \
    > "$CASCADE_LOG"
fi

# Log each cascade event
echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  EVENT_TYPE=$(echo "$event" | jq -r '.event')
  TARGET_TYPE=$(echo "$event" | jq -r '.targetType')
  TARGET_ID=$(echo "$event" | jq -r '.targetId')
  TARGET_NAME=$(echo "$event" | jq -r '.targetName')
  CURRENT_STATUS=$(echo "$event" | jq -r '.currentStatus')
  SUGGESTED_STATUS=$(echo "$event" | jq -r '.suggestedStatus')
  FLOW=$(echo "$event" | jq -r '.flow')
  AUTOMATIC=$(echo "$event" | jq -r '.automatic')
  REASON=$(echo "$event" | jq -r '.reason')

  # Append to CSV
  echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,$TARGET_ID,\"$TARGET_NAME\",$CURRENT_STATUS,$SUGGESTED_STATUS,$FLOW,$AUTOMATIC,\"$REASON\"" \
    >> "$CASCADE_LOG"

  echo "📊 Logged cascade event: $EVENT_TYPE ($TARGET_TYPE)"
done

# Update summary statistics
TOTAL_EVENTS=$(tail -n +2 "$CASCADE_LOG" | wc -l)
AUTO_EVENTS=$(tail -n +2 "$CASCADE_LOG" | grep ",true," | wc -l)
MANUAL_EVENTS=$(tail -n +2 "$CASCADE_LOG" | grep ",false," | wc -l)

cat > "$SUMMARY_LOG" <<EOF
{
  "total_cascade_events": $TOTAL_EVENTS,
  "automatic_events": $AUTO_EVENTS,
  "manual_events": $MANUAL_EVENTS,
  "last_updated": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

echo "✓ Cascade event logged ($TOTAL_EVENTS total, $AUTO_EVENTS auto, $MANUAL_EVENTS manual)"
exit 0
```

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "comment": "Log cascade events for analytics",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/cascade-logger.sh"
          }
        ]
      }
    ]
  }
}
```

**Analytics Queries:**
```bash
# Count cascade events by type
cut -d',' -f2 .claude/metrics/cascade-events.csv | sort | uniq -c

# Count auto vs manual events
grep ",true," .claude/metrics/cascade-events.csv | wc -l  # Auto
grep ",false," .claude/metrics/cascade-events.csv | wc -l # Manual

# Events by flow
cut -d',' -f8 .claude/metrics/cascade-events.csv | sort | uniq -c

# Recent events (last 10)
tail -10 .claude/metrics/cascade-events.csv
```

## Template 16: Progressive Disclosure Hook (v2.0 - Conservative)

**Purpose**: Notify user of cascade events WITHOUT auto-applying (conservative alternative to Template 12)

```bash
#!/bin/bash
# Progressive disclosure - show cascade suggestions without auto-applying
# CONSERVATIVE: Always requires user confirmation
# Use this when you want full control over status transitions

INPUT=$(cat)

# Extract cascade events
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi

echo "════════════════════════════════════════════"
echo "🔔 WORKFLOW CASCADE EVENTS DETECTED"
echo "════════════════════════════════════════════"

# Display each cascade event
EVENT_COUNT=0
echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  EVENT_COUNT=$((EVENT_COUNT + 1))
  EVENT_TYPE=$(echo "$event" | jq -r '.event')
  TARGET_TYPE=$(echo "$event" | jq -r '.targetType')
  TARGET_NAME=$(echo "$event" | jq -r '.targetName')
  CURRENT_STATUS=$(echo "$event" | jq -r '.currentStatus')
  SUGGESTED_STATUS=$(echo "$event" | jq -r '.suggestedStatus')
  REASON=$(echo "$event" | jq -r '.reason')

  echo ""
  echo "Event #$EVENT_COUNT: $EVENT_TYPE"
  echo "  Target: $TARGET_NAME ($TARGET_TYPE)"
  echo "  Current: $CURRENT_STATUS"
  echo "  Suggested: $SUGGESTED_STATUS"
  echo "  Reason: $REASON"
  echo ""
  echo "  💡 To apply: Ask AI to progress $TARGET_NAME to $SUGGESTED_STATUS"
done

echo "════════════════════════════════════════════"
echo ""
echo "ℹ️  These are workflow suggestions based on completion events."
echo "   Review and apply manually using Feature/Task Orchestration Skills."
echo ""

# Log for reference (non-blocking)
LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$LOG_DIR"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
echo "$TIMESTAMP - Displayed $EVENT_COUNT cascade events to user" \
  >> "$LOG_DIR/progressive-disclosure.log"

exit 0
```

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "comment": "Show cascade suggestions (no auto-apply)",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/progressive-disclosure.sh"
          }
        ]
      }
    ]
  }
}
```

**When to use Progressive Disclosure instead of Auto-Apply:**
- ✅ Learning environments (understand workflow before automating)
- ✅ Critical systems (review every decision)
- ✅ Complex approval workflows (manual gates required)
- ✅ First-time setup (observe patterns before automating)

**Migration path:**
Start with Progressive Disclosure → Observe patterns → Switch to Auto-Apply for trusted flows

## Usage Tips

1. **Start with templates** - Copy and customize rather than writing from scratch
2. **Test incrementally** - Test each part of the hook separately
3. **Use defensive checks** - Always validate conditions before acting
4. **Handle errors gracefully** - Don't let hooks break Claude's workflow
5. **Log for debugging** - Add echo statements to understand execution
6. **Keep hooks fast** - Long-running hooks slow down Claude
7. **Document your hooks** - Future you will thank present you
8. **Choose opinionated vs conservative** - Auto-apply for trust, Progressive Disclosure for control

## Debugging Commands

```bash
# Test hook with sample JSON
echo '{"tool_input": {"id": "test"}}' | .claude/hooks/your-hook.sh

# Enable bash debugging
bash -x .claude/hooks/your-hook.sh < test-input.json

# Check hook permissions
ls -la .claude/hooks/*.sh

# Make hook executable
chmod +x .claude/hooks/your-hook.sh

# View hook output
.claude/hooks/your-hook.sh < test-input.json 2>&1 | tee output.log
```
