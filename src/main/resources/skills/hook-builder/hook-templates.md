# Hook Templates

Copy-paste templates for common hook patterns. Customize for your specific needs.

## Template 1: Basic PostToolUse Hook

**Purpose**: React to any MCP tool call

```bash
#!/bin/bash
# [Description of what this hook does]

# Read JSON input from stdin
INPUT=$(cat)

# Extract fields from tool input
FIELD=$(echo "$INPUT" | jq -r '.tool_input.field_name')

# Defensive check - only proceed if condition is met
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
        "matcher": "mcp__task-orchestrator__TOOL_NAME",
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

## Template 2: Blocking Quality Gate Hook

**Purpose**: Prevent operations that don't meet criteria

```bash
#!/bin/bash
# [Description of quality gate]

# Read JSON input
INPUT=$(cat)

# Extract field to check
FIELD=$(echo "$INPUT" | jq -r '.tool_input.field_name')

# Only run quality gate when [condition]
if [ "$FIELD" != "triggering_value" ]; then
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
        "matcher": "mcp__task-orchestrator__TOOL_NAME",
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

## Template 3: Database Query Hook

**Purpose**: Get data from Task Orchestrator database

```bash
#!/bin/bash
# [Description]

# Read JSON input
INPUT=$(cat)

# Extract ID to query
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

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

## Template 4: Git Automation Hook

**Purpose**: Automate git operations

```bash
#!/bin/bash
# [Description of git automation]

# Read JSON input
INPUT=$(cat)

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

## Template 5: Logging/Metrics Hook

**Purpose**: Track events for analytics

```bash
#!/bin/bash
# [Description of what is being logged]

# Read JSON input
INPUT=$(cat)

# Extract data to log
FIELD1=$(echo "$INPUT" | jq -r '.path.to.field1')
FIELD2=$(echo "$INPUT" | jq -r '.path.to.field2')

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

## Template 6: External API Hook

**Purpose**: Send data to external service

```bash
#!/bin/bash
# [Description of API integration]

# Read JSON input
INPUT=$(cat)

# Extract data
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

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

## Template 7: Notification Hook

**Purpose**: Send notifications to user or team

```bash
#!/bin/bash
# [Description of notification]

# Read JSON input
INPUT=$(cat)

# Extract data for notification
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

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

## Template 9: Conditional Multi-Action Hook

**Purpose**: Different actions based on conditions

```bash
#!/bin/bash
# [Description of conditional logic]

# Read JSON input
INPUT=$(cat)

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

## Configuration Combination Examples

**Multiple Hooks on Same Tool**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [
          {"type": "command", "command": ".claude/hooks/commit.sh"},
          {"type": "command", "command": ".claude/hooks/notify.sh"},
          {"type": "command", "command": ".claude/hooks/metrics.sh"}
        ]
      }
    ]
  }
}
```

**Multiple Tools, Different Hooks**:
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [
          {"type": "command", "command": ".claude/hooks/task-commit.sh"}
        ]
      },
      {
        "matcher": "mcp__task-orchestrator__update_feature",
        "hooks": [
          {"type": "command", "command": ".claude/hooks/feature-gate.sh"}
        ]
      },
      {
        "matcher": "mcp__task-orchestrator__create_task",
        "hooks": [
          {"type": "command", "command": ".claude/hooks/task-notify.sh"}
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

## Usage Tips

1. **Start with templates** - Copy and customize rather than writing from scratch
2. **Test incrementally** - Test each part of the hook separately
3. **Use defensive checks** - Always validate conditions before acting
4. **Handle errors gracefully** - Don't let hooks break Claude's workflow
5. **Log for debugging** - Add echo statements to understand execution
6. **Keep hooks fast** - Long-running hooks slow down Claude
7. **Document your hooks** - Future you will thank present you

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
