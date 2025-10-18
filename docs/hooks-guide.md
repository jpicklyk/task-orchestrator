# Claude Code Hooks - Comprehensive Guide

## Table of Contents

- [Introduction](#introduction)
- [What Are Claude Code Hooks?](#what-are-claude-code-hooks)
- [Hook Lifecycle and Events](#hook-lifecycle-and-events)
- [Anatomy of a Hook](#anatomy-of-a-hook)
- [Creating Custom Hooks](#creating-custom-hooks)
- [Hook Templates and Patterns](#hook-templates-and-patterns)
- [Integration with Task Orchestrator](#integration-with-task-orchestrator)
- [Integration with Skills and Subagents](#integration-with-skills-and-subagents)
- [Testing and Debugging Hooks](#testing-and-debugging-hooks)
- [Best Practices](#best-practices)
- [Security Considerations](#security-considerations)
- [Complete Reference](#complete-reference)

---

## Introduction

Claude Code hooks enable automated workflow integration between Claude's AI capabilities and your development tools. They allow you to trigger custom actions in response to specific events, such as when tasks are completed, features are updated, or subagents finish their work.

This guide provides comprehensive documentation for creating, configuring, and maintaining hooks that integrate with the MCP Task Orchestrator.

### What You'll Learn

- Understanding hook events and lifecycle
- Creating custom hooks for your workflow
- Integrating hooks with git, testing, metrics, and external systems
- Testing and debugging hook scripts
- Security best practices
- Using the Hook Builder Skill for interactive hook creation

### Prerequisites

- **Claude Code**: Latest version with hooks support
- **bash**: Unix shell (Linux, macOS, Git Bash/WSL on Windows)
- **jq**: JSON processor ([installation guide](https://jqlang.github.io/jq/download/))
- **sqlite3**: Database queries (usually pre-installed)
- **Optional**: git, curl, project build tools (gradlew, npm, etc.)

---

## What Are Claude Code Hooks?

### Overview

Hooks are executable scripts that run automatically in response to specific Claude Code events. They act as an **observation and automation layer** that doesn't interfere with core functionality but enables powerful workflow integrations.

### Use Cases

**Quality Gates**
- Block feature completion if tests fail
- Enforce code coverage requirements
- Validate dependencies before allowing status changes

**Version Control Automation**
- Auto-commit when tasks complete
- Create feature branches automatically
- Tag releases based on project milestones

**Metrics and Analytics**
- Track subagent usage patterns
- Log task completion times
- Monitor workflow efficiency

**External Integrations**
- Send Slack/Discord notifications
- Update Jira/GitHub issues
- Trigger CI/CD pipelines

**Documentation**
- Generate changelogs from task titles
- Update README files automatically
- Create release notes

---

## Hook Lifecycle and Events

### Available Hook Events

Claude Code provides three hook event types:

#### 1. PostToolUse

**When**: After any MCP tool is called and completes
**Use for**: Reacting to task/feature changes, commits, notifications
**Input**: Tool name, input parameters, output result

```json
{
  "tool_name": "mcp__task-orchestrator__set_status",
  "tool_input": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed"
  },
  "tool_output": {
    "success": true,
    "message": "Task status updated"
  }
}
```

**Common Matchers**:
- `mcp__task-orchestrator__set_status` - Task status changes
- `mcp__task-orchestrator__update_feature` - Feature updates
- `mcp__task-orchestrator__update_task` - Task updates
- `mcp__task-orchestrator__create_task` - New tasks
- `mcp__task-orchestrator__create_feature` - New features

#### 2. SubagentStop

**When**: After a subagent (specialist agent) completes execution
**Use for**: Logging agent metrics, coordinating workflows
**Input**: Agent name, session ID, transcript path, subagent prompt

```json
{
  "agent_name": "Backend Engineer",
  "session_id": "abc123...",
  "transcript_path": "/path/to/transcript.json",
  "subagent_prompt": "Work on task 550e8400-e29b-41d4-a716-446655440000"
}
```

**Agent Types**: Backend Engineer, Frontend Developer, Database Engineer, Test Engineer, Technical Writer, Feature Manager, Task Manager, Planning Specialist, Bug Triage Specialist

#### 3. PreToolUse

**When**: Before an MCP tool is called
**Use for**: Validation, pre-flight checks (less common)
**Input**: Tool name, input parameters

**Note**: PreToolUse hooks are rare because PostToolUse hooks can block operations after execution.

### Hook Execution Flow

```
User Request
    ↓
Claude Code executes MCP tool
    ↓
Tool completes successfully
    ↓
PostToolUse hook triggered
    ↓
Hook script receives JSON input via stdin
    ↓
Hook checks conditions
    ↓
    ├─ Conditions not met → Exit 0 (allow)
    └─ Conditions met → Execute action
           ↓
           ├─ Action succeeds → Exit 0 (allow)
           └─ Validation fails → Exit 2 + block JSON (block)
```

---

## Anatomy of a Hook

### Basic Structure

Every hook script follows this pattern:

```bash
#!/bin/bash
# Hook description and purpose

# 1. Read JSON input from stdin
INPUT=$(cat)

# 2. Extract relevant fields using jq
FIELD=$(echo "$INPUT" | jq -r '.tool_input.field_name')

# 3. Defensive check - only proceed if conditions met
if [ "$FIELD" != "expected_value" ]; then
  exit 0  # Condition not met, allow operation
fi

# 4. Perform the action
cd "$CLAUDE_PROJECT_DIR"
# ... your automation logic ...

# 5. Report success
echo "✓ Hook completed successfully"
exit 0
```

### Input Format

Hooks receive JSON input via **stdin** (standard input). The structure varies by event type:

**PostToolUse Input**:
```json
{
  "tool_name": "mcp__task-orchestrator__TOOL_NAME",
  "tool_input": {
    "id": "uuid",
    "field1": "value1",
    "field2": "value2"
  },
  "tool_output": {
    "success": true,
    "data": { ... }
  }
}
```

**SubagentStop Input**:
```json
{
  "agent_name": "Agent Name",
  "session_id": "session-uuid",
  "transcript_path": "/path/to/transcript.json",
  "subagent_prompt": "Prompt text containing task/feature IDs"
}
```

### Output Format

**Non-Blocking Hooks** (most common):
```bash
echo "✓ Success message"
exit 0
```

**Blocking Hooks** (quality gates):
```bash
cat << EOF
{
  "decision": "block",
  "reason": "Detailed explanation of why operation was blocked and what to fix"
}
EOF
exit 2
```

### Exit Codes

- **0**: Success - allow operation to proceed
- **1**: Unexpected error - allow operation for safety
- **2**: Block operation (quality gate failed)

**Important**: Even blocking hooks should exit 0 or 2. Exit code 1 is treated as an unexpected error and allows the operation to proceed for safety.

### Environment Variables

Hooks have access to Claude Code environment variables:

- **`$CLAUDE_PROJECT_DIR`**: Project root directory (always use this for paths)
- **`$CLAUDE_SESSION_ID`**: Current Claude Code session ID
- **Other env vars**: Any environment variables set in your system

---

## Creating Custom Hooks

### Step-by-Step Guide

#### Step 1: Define Your Requirements

Ask yourself:
- **What event should trigger this hook?** (PostToolUse, SubagentStop, PreToolUse)
- **What tool should we watch for?** (if PostToolUse)
- **What should happen when triggered?** (commit, test, notify, log)
- **Should this block operations?** (yes for quality gates, no for logging/notifications)

#### Step 2: Create the Hook Script

**Using Hook Builder Skill** (Recommended):

The Task Orchestrator includes a Hook Builder Skill that interactively guides you through hook creation.

```
"Help me create a hook that commits to git when tasks are completed"
```

The Hook Builder will:
1. Interview you about requirements
2. Generate the hook script
3. Create configuration
4. Provide testing instructions

**Manual Creation**:

Create `.claude/hooks/your-hook-name.sh`:

```bash
#!/bin/bash
# Description of what this hook does
#
# Hook Event: PostToolUse
# Matcher: mcp__task-orchestrator__set_status
# Trigger: When status is set to "completed"

set -euo pipefail

# Read JSON input from stdin
INPUT=$(cat)

# Extract status from tool_input
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')

# Only proceed if status is being set to "completed"
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Extract task ID
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')

if [ -z "$TASK_ID" ]; then
  echo "⚠️  No task ID found in input"
  exit 0
fi

# Verify we're in a git repository
if [ ! -d "$CLAUDE_PROJECT_DIR/.git" ]; then
  echo "⚠️  Not a git repository - skipping commit"
  exit 0
fi

# Check if git is available
if ! command -v git &> /dev/null; then
  echo "⚠️  Git not available - skipping commit"
  exit 0
fi

# Get task title from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
if [ ! -f "$DB_PATH" ]; then
  echo "⚠️  Database not found - skipping commit"
  exit 0
fi

TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')' LIMIT 1" 2>/dev/null || echo "")

if [ -z "$TASK_TITLE" ]; then
  echo "⚠️  Could not retrieve task title for $TASK_ID"
  exit 0
fi

# Change to project directory
cd "$CLAUDE_PROJECT_DIR"

# Check if there are changes to commit
if git diff --quiet && git diff --cached --quiet; then
  echo "ℹ️  No changes to commit for task: $TASK_TITLE"
  exit 0
fi

# Stage all changes
git add -A

# Create commit with task information
COMMIT_MSG="feat: Complete task - $TASK_TITLE

Task-ID: $TASK_ID
Automated-By: Claude Code hook"

git commit -m "$COMMIT_MSG" 2>&1

COMMIT_RESULT=$?

if [ $COMMIT_RESULT -eq 0 ]; then
  COMMIT_HASH=$(git rev-parse --short HEAD)
  echo "✓ Created git commit $COMMIT_HASH for completed task: $TASK_TITLE"
else
  echo "⚠️  Git commit failed with exit code $COMMIT_RESULT"
fi

exit 0
```

#### Step 3: Make Script Executable

On Unix systems (Linux, macOS):
```bash
chmod +x .claude/hooks/your-hook-name.sh
```

On Windows with Git Bash, executable permissions are typically not required, but the shebang (`#!/bin/bash`) is still important.

#### Step 4: Configure Hook Settings

Create or edit `.claude/settings.local.json`:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/your-hook-name.sh",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

**Configuration Options**:
- **matcher**: Tool name to watch for (exact match required)
- **command**: Path to hook script (use `$CLAUDE_PROJECT_DIR` for portability)
- **timeout**: Maximum execution time in seconds (optional, default varies)

**Settings File Locations**:
- **Project-specific**: `.claude/settings.local.json` (recommended)
- **Windows**: `%APPDATA%\Claude\config\settings.json`
- **macOS**: `~/Library/Application Support/Claude/config/settings.json`
- **Linux**: `~/.config/Claude/config/settings.json`

#### Step 5: Test the Hook

Test with sample JSON input:

```bash
echo '{
  "tool_input": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed"
  }
}' | .claude/hooks/your-hook-name.sh
```

Expected output:
```
✓ Created git commit abc1234 for completed task: Your Task Title
```

#### Step 6: Document Your Hook

Add documentation to `.claude/hooks/README.md`:

```markdown
## Your Hook Name

**Purpose**: [What this hook does]

**Triggers**: PostToolUse on `mcp__task-orchestrator__set_status` when status='completed'

**Actions**:
- Retrieves task title from database
- Stages all changes with `git add -A`
- Creates commit with task ID and title

**Configuration**: See `.claude/settings.local.json`

**Testing**:
```bash
echo '{"tool_input": {"id": "test-uuid", "status": "completed"}}' | \
  .claude/hooks/your-hook-name.sh
```

**Customization**:
- Change commit message format (line 75)
- Stage specific files only (replace `git add -A`)
```

---

## Hook Templates and Patterns

The Task Orchestrator provides 11 ready-to-use hook templates in `src/main/resources/skills/hook-builder/hook-templates.md`. Here are the most common patterns:

### Pattern 1: Git Automation

**Auto-commit when tasks complete**:

```bash
#!/bin/bash
# Auto-commit on task completion

INPUT=$(cat)
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

if [ "$STATUS" != "completed" ]; then
  exit 0
fi

TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id')
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'" 2>/dev/null)

cd "$CLAUDE_PROJECT_DIR"

if git diff --quiet && git diff --cached --quiet; then
  echo "No changes to commit"
  exit 0
fi

git add -A
git commit -m "feat: Complete task - $TASK_TITLE

Task-ID: $TASK_ID
Automated-By: Claude Code hook"

echo "✓ Created git commit"
exit 0
```

### Pattern 2: Blocking Quality Gate

**Block feature completion if tests fail**:

```bash
#!/bin/bash
# Feature complete quality gate

set -euo pipefail

INPUT=$(cat)
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')

# Only check when marking as completed
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_input.id // "unknown"')

echo "=========================================="
echo "Feature Complete Quality Gate"
echo "=========================================="
echo "Feature ID: $FEATURE_ID"
echo "Running project tests..."

cd "$CLAUDE_PROJECT_DIR"

# Run tests
TEST_OUTPUT=$(./gradlew test 2>&1) || TEST_EXIT_CODE=$?

if [ "${TEST_EXIT_CODE:-0}" -ne 0 ]; then
  # Tests failed - block the operation
  echo "=========================================="
  echo "TESTS FAILED - BLOCKING FEATURE COMPLETION"
  echo "=========================================="
  echo "$TEST_OUTPUT"

  cat << EOF
{
  "decision": "block",
  "reason": "Cannot mark feature as completed - project tests are failing. Please fix failing tests before marking feature complete.\n\nTest output:\n$TEST_OUTPUT"
}
EOF
  exit 2
fi

echo "✓ All tests passed"
echo "Feature completion allowed"
exit 0
```

### Pattern 3: Metrics Logging

**Track subagent usage**:

```bash
#!/bin/bash
# Subagent metrics logger

INPUT=$(cat)

# Create logs directory
LOGS_DIR="$CLAUDE_PROJECT_DIR/.claude/logs"
mkdir -p "$LOGS_DIR"

LOG_FILE="$LOGS_DIR/subagent-metrics.log"
CSV_FILE="$LOGS_DIR/subagent-metrics.csv"

# Extract information
AGENT_NAME=$(echo "$INPUT" | jq -r '.agent_name // "unknown"')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')
PROMPT=$(echo "$INPUT" | jq -r '.subagent_prompt // ""')

# Extract task/feature IDs from prompt
TASK_ID=$(echo "$PROMPT" | grep -oP 'task\s+[0-9a-f-]{36}' | grep -oP '[0-9a-f-]{36}' || echo "")
FEATURE_ID=$(echo "$PROMPT" | grep -oP 'feature[:\s]+[0-9a-f-]{36}' | grep -oP '[0-9a-f-]{36}' || echo "")

# Categorize agent
AGENT_CATEGORY="unknown"
case "$AGENT_NAME" in
  *"Backend Engineer"*) AGENT_CATEGORY="implementation" ;;
  *"Frontend Developer"*) AGENT_CATEGORY="implementation" ;;
  *"Database Engineer"*) AGENT_CATEGORY="implementation" ;;
  *"Test Engineer"*) AGENT_CATEGORY="quality" ;;
  *"Technical Writer"*) AGENT_CATEGORY="documentation" ;;
  *"Feature Manager"*|*"Task Manager"*) AGENT_CATEGORY="coordination" ;;
  *"Planning Specialist"*|*"Feature Architect"*) AGENT_CATEGORY="planning" ;;
esac

# Write to log
cat >> "$LOG_FILE" << EOF
────────────────────────────────────────
Timestamp:       $TIMESTAMP
Agent:           $AGENT_NAME
Category:        $AGENT_CATEGORY
Session:         $SESSION_ID
Task ID:         ${TASK_ID:-"N/A"}
Feature ID:      ${FEATURE_ID:-"N/A"}
────────────────────────────────────────

EOF

# Initialize CSV if needed
if [ ! -f "$CSV_FILE" ]; then
  echo "timestamp,agent_name,agent_category,session_id,task_id,feature_id" > "$CSV_FILE"
fi

# Append CSV entry
echo "$TIMESTAMP,$AGENT_NAME,$AGENT_CATEGORY,$SESSION_ID,${TASK_ID:-""},${FEATURE_ID:-""}" >> "$CSV_FILE"

# Generate summary
TOTAL_RUNS=$(tail -n +2 "$CSV_FILE" | wc -l)
AGENT_COUNTS=$(tail -n +2 "$CSV_FILE" | cut -d',' -f2 | sort | uniq -c | sort -rn)

cat << EOF
✓ Logged subagent execution: $AGENT_NAME ($AGENT_CATEGORY)
  Total subagent runs: $TOTAL_RUNS

Top 3 agents:
$(echo "$AGENT_COUNTS" | head -3 | awk '{printf "  %2d× %s\n", $1, $2}')

Logs: $LOG_FILE
Data: $CSV_FILE
EOF

exit 0
```

### Pattern 4: Database Queries

**Query Task Orchestrator database**:

```bash
#!/bin/bash
# Database query pattern

INPUT=$(cat)
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"

# Single value query
TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$ENTITY_ID" | tr -d '-')'" 2>/dev/null)

# Multiple columns query (JSON output)
DETAILS=$(sqlite3 "$DB_PATH" -json \
  "SELECT title, status, priority FROM Tasks WHERE id=x'$(echo "$ENTITY_ID" | tr -d '-')'" 2>/dev/null)

# Count query
TASK_COUNT=$(sqlite3 "$DB_PATH" \
  "SELECT COUNT(*) FROM Tasks WHERE featureId=x'$(echo "$FEATURE_ID" | tr -d '-')'" 2>/dev/null)

if [ -z "$TITLE" ]; then
  echo "Warning: Could not find record"
  exit 0
fi

echo "Found: $TITLE"
exit 0
```

**Common Database Queries**:

```sql
-- Get task details
SELECT title, status, priority, complexity
FROM Tasks
WHERE id=x'UUID_WITHOUT_DASHES';

-- Get feature details
SELECT name, status, summary
FROM Features
WHERE id=x'UUID_WITHOUT_DASHES';

-- Get incomplete tasks in feature
SELECT id, title
FROM Tasks
WHERE featureId=x'FEATURE_UUID_WITHOUT_DASHES'
  AND status != 'completed';

-- Count dependencies
SELECT COUNT(*)
FROM TaskDependencies
WHERE dependentTaskId=x'TASK_UUID_WITHOUT_DASHES';
```

**Important**: SQLite stores UUIDs as BLOBs. Use `x'UUID_WITHOUT_DASHES'` format and remove hyphens with `tr -d '-'`.

### Pattern 5: External API Integration

**Send webhook notifications**:

```bash
#!/bin/bash
# External API integration

INPUT=$(cat)
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Get details from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$ENTITY_ID" | tr -d '-')'" 2>/dev/null)

# Prepare payload
PAYLOAD=$(cat <<EOF
{
  "entity_id": "$ENTITY_ID",
  "title": "$TITLE",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF
)

# Send to API (examples)

# Slack
curl -X POST "$SLACK_WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d "{\"text\": \"Task completed: $TITLE\"}"

# Discord
curl -X POST "$DISCORD_WEBHOOK_URL" \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"Task completed: $TITLE\"}"

# Generic REST API
curl -X POST "https://api.example.com/webhook" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d "$PAYLOAD"

echo "✓ Sent to external API"
exit 0
```

---

## Integration with Task Orchestrator

### MCP Tool Integration

Hooks can react to any Task Orchestrator MCP tool. Here are the most useful tools for hook integration:

#### Task Management Tools

**`mcp__task-orchestrator__set_status`**:
```json
{
  "matcher": "mcp__task-orchestrator__set_status",
  "hooks": [
    {"type": "command", "command": ".claude/hooks/task-complete-commit.sh"}
  ]
}
```

**Use cases**: Auto-commit on completion, send notifications, log metrics

**`mcp__task-orchestrator__create_task`**:
```json
{
  "matcher": "mcp__task-orchestrator__create_task",
  "hooks": [
    {"type": "command", "command": ".claude/hooks/task-created-notify.sh"}
  ]
}
```

**Use cases**: Notify team of new tasks, create git branch, update external tracker

**`mcp__task-orchestrator__update_task`**:
```json
{
  "matcher": "mcp__task-orchestrator__update_task",
  "hooks": [
    {"type": "command", "command": ".claude/hooks/task-updated-log.sh"}
  ]
}
```

**Use cases**: Track changes, sync with external systems

#### Feature Management Tools

**`mcp__task-orchestrator__update_feature`**:
```json
{
  "matcher": "mcp__task-orchestrator__update_feature",
  "hooks": [
    {"type": "command", "command": ".claude/hooks/feature-complete-gate.sh"}
  ]
}
```

**Use cases**: Quality gates, test validation, documentation checks

**`mcp__task-orchestrator__create_feature`**:
```json
{
  "matcher": "mcp__task-orchestrator__create_feature",
  "hooks": [
    {"type": "command", "command": ".claude/hooks/feature-created-branch.sh"}
  ]
}
```

**Use cases**: Create feature branches, initialize documentation, notify stakeholders

#### Dependency Tools

**`mcp__task-orchestrator__create_dependency`**:
```json
{
  "matcher": "mcp__task-orchestrator__create_dependency",
  "hooks": [
    {"type": "command", "command": ".claude/hooks/dependency-graph-update.sh"}
  ]
}
```

**Use cases**: Update dependency graphs, validate circular dependencies

### Database Schema Reference

Task Orchestrator uses SQLite. Key tables for hook queries:

**Tasks Table**:
```sql
CREATE TABLE Tasks (
  id BLOB PRIMARY KEY,
  title TEXT NOT NULL,
  summary TEXT,
  status TEXT NOT NULL,  -- 'pending', 'in_progress', 'completed', 'blocked'
  priority TEXT NOT NULL,  -- 'low', 'medium', 'high'
  complexity INTEGER,  -- 1-10
  featureId BLOB,
  createdAt TIMESTAMP,
  modifiedAt TIMESTAMP
);
```

**Features Table**:
```sql
CREATE TABLE Features (
  id BLOB PRIMARY KEY,
  name TEXT NOT NULL,
  summary TEXT,
  status TEXT NOT NULL,  -- 'planning', 'in-development', 'completed'
  projectId BLOB,
  createdAt TIMESTAMP,
  modifiedAt TIMESTAMP
);
```

**TaskDependencies Table**:
```sql
CREATE TABLE TaskDependencies (
  id BLOB PRIMARY KEY,
  taskId BLOB NOT NULL,  -- The dependency (must complete first)
  dependentTaskId BLOB NOT NULL,  -- The task that depends on it
  FOREIGN KEY (taskId) REFERENCES Tasks(id),
  FOREIGN KEY (dependentTaskId) REFERENCES Tasks(id)
);
```

**Sections Table**:
```sql
CREATE TABLE Sections (
  id BLOB PRIMARY KEY,
  entityType TEXT NOT NULL,  -- 'TASK', 'FEATURE', 'PROJECT'
  entityId BLOB NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  contentFormat TEXT NOT NULL,  -- 'MARKDOWN', 'PLAIN_TEXT', 'JSON', 'CODE'
  ordinal INTEGER NOT NULL,
  tags TEXT
);
```

---

## Integration with Skills and Subagents

### Hook Builder Skill

The Hook Builder Skill provides interactive hook creation. It's located in `src/main/resources/skills/hook-builder/`.

**Using the Hook Builder**:

```
"Help me create a hook that runs tests before allowing feature completion"
```

The Hook Builder will:
1. Interview you about requirements (event, matcher, action, blocking)
2. Generate a working bash script with defensive checks
3. Create the hook configuration for `.claude/settings.local.json`
4. Provide sample JSON inputs for testing
5. Offer troubleshooting guidance

**Available in**: `.claude/agents/hook-builder.md` (if you've run `setup_claude_agents`)

**Skill files**:
- `SKILL.md` - Skill definition and workflow
- `hook-templates.md` - 11 copy-paste templates
- `examples.md` - Complete working examples

### Subagent Integration

Hooks can track and react to subagent execution:

**SubagentStop Hook Example**:

```bash
#!/bin/bash
# React to subagent completion

INPUT=$(cat)

AGENT_NAME=$(echo "$INPUT" | jq -r '.agent_name')
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id')
PROMPT=$(echo "$INPUT" | jq -r '.subagent_prompt')

# Extract task ID from prompt
TASK_ID=$(echo "$PROMPT" | grep -oP 'task\s+[0-9a-f-]{36}' | grep -oP '[0-9a-f-]{36}')

# Different actions based on agent type
case "$AGENT_NAME" in
  *"Backend Engineer"*)
    echo "Backend work completed for task $TASK_ID"
    # Run backend-specific checks (linting, tests, etc.)
    ;;
  *"Test Engineer"*)
    echo "Testing completed for task $TASK_ID"
    # Verify test coverage, update metrics
    ;;
  *"Technical Writer"*)
    echo "Documentation completed for task $TASK_ID"
    # Validate documentation, generate docs site
    ;;
esac

exit 0
```

**Use cases**:
- Log specialist usage patterns
- Coordinate workflow between agents
- Trigger next steps based on agent completion
- Collect metrics for optimization

### Agent Coordination Patterns

**Pattern 1: Sequential Agent Workflow**:

```bash
# After Backend Engineer completes, trigger Test Engineer
if [[ "$AGENT_NAME" == *"Backend Engineer"* ]]; then
  # Mark task ready for testing
  sqlite3 "$DB_PATH" \
    "UPDATE Tasks SET tags='ready-for-testing' WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'"

  echo "✓ Task ready for Test Engineer"
fi
```

**Pattern 2: Metrics Collection**:

```bash
# Track which agents are most used
LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$LOG_DIR"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
echo "$TIMESTAMP,$AGENT_NAME,$TASK_ID" >> "$LOG_DIR/agent-usage.csv"

# Generate summary
TOTAL=$(wc -l < "$LOG_DIR/agent-usage.csv")
MOST_USED=$(cut -d',' -f2 "$LOG_DIR/agent-usage.csv" | sort | uniq -c | sort -rn | head -1)

echo "Total agent invocations: $TOTAL"
echo "Most used: $MOST_USED"
```

---

## Testing and Debugging Hooks

### Manual Testing

#### Test with Sample JSON

Create a test input file `test-input.json`:

```json
{
  "tool_input": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed"
  }
}
```

Run the hook:

```bash
cat test-input.json | .claude/hooks/your-hook.sh
```

Or inline:

```bash
echo '{"tool_input": {"id": "test-uuid", "status": "completed"}}' | \
  .claude/hooks/your-hook.sh
```

#### Enable Bash Debugging

Add debugging flags to your hook script:

```bash
#!/bin/bash
set -x  # Print commands as they execute
set -v  # Print script lines as they're read

# Or combine with error handling
set -xeuo pipefail
```

Run with explicit bash debugging:

```bash
bash -x .claude/hooks/your-hook.sh < test-input.json
```

#### Test Individual Components

Test jq parsing:

```bash
echo '{"tool_input": {"status": "completed"}}' | jq -r '.tool_input.status'
```

Test database queries:

```bash
sqlite3 data/tasks.db "SELECT title FROM Tasks LIMIT 5"
```

Test git operations:

```bash
cd "$CLAUDE_PROJECT_DIR"
git diff --quiet && echo "No changes" || echo "Changes detected"
```

### Automated Testing

Create a test script `test-hook.sh`:

```bash
#!/bin/bash
# Test script for your hook

echo "Testing hook with various inputs..."

# Test 1: Status not 'completed' (should exit immediately)
echo "Test 1: Non-completion status"
echo '{"tool_input": {"id": "test", "status": "in_progress"}}' | \
  .claude/hooks/your-hook.sh
if [ $? -eq 0 ]; then
  echo "✓ Test 1 passed"
else
  echo "✗ Test 1 failed"
fi

# Test 2: Status 'completed' with valid ID
echo "Test 2: Completion with valid ID"
echo '{"tool_input": {"id": "550e8400-e29b-41d4-a716-446655440000", "status": "completed"}}' | \
  .claude/hooks/your-hook.sh
if [ $? -eq 0 ]; then
  echo "✓ Test 2 passed"
else
  echo "✗ Test 2 failed"
fi

# Test 3: Missing ID field
echo "Test 3: Missing ID"
echo '{"tool_input": {"status": "completed"}}' | \
  .claude/hooks/your-hook.sh
if [ $? -eq 0 ]; then
  echo "✓ Test 3 passed (graceful degradation)"
else
  echo "✗ Test 3 failed"
fi

# Test 4: Empty JSON
echo "Test 4: Empty input"
echo '{}' | .claude/hooks/your-hook.sh
if [ $? -eq 0 ]; then
  echo "✓ Test 4 passed (graceful degradation)"
else
  echo "✗ Test 4 failed"
fi

echo "All tests completed"
```

Run tests:

```bash
chmod +x test-hook.sh
./test-hook.sh
```

### Comprehensive Test Suite Example

See `.claude/hooks/task-manager/scripts/feature-complete-gate-test.sh` for a comprehensive test suite with 6 test cases and 100% coverage.

**Key testing patterns**:
- Test happy path (expected behavior)
- Test edge cases (missing fields, empty input)
- Test error conditions (missing dependencies, validation failures)
- Test blocking behavior (exit code 2, block JSON output)
- Test graceful degradation (missing tools, wrong environment)

### Debugging Common Issues

#### Hook Not Executing

**Symptom**: Hook doesn't run when tool is called

**Check**:
1. Verify `.claude/settings.local.json` exists (not `.example`)
2. Check matcher is exact: `mcp__task-orchestrator__set_status`
3. Verify hook script path is correct
4. Make script executable: `chmod +x .claude/hooks/script.sh`
5. Check Claude Code console for hook errors

**Debug**:
```bash
# Verify settings file
cat .claude/settings.local.json

# Test hook manually
echo '{"tool_input": {"id": "test", "status": "completed"}}' | \
  .claude/hooks/your-hook.sh
```

#### Hook Executing but Failing

**Symptom**: Hook runs but doesn't complete successfully

**Check**:
1. Test hook manually with sample JSON
2. Verify dependencies installed (`jq`, `sqlite3`, `git`)
3. Check `$CLAUDE_PROJECT_DIR` is set correctly
4. Add `set -x` to see execution trace
5. Check exit codes (0 = success, 2 = block, other = error)

**Debug**:
```bash
# Test with debugging
bash -x .claude/hooks/your-hook.sh < test-input.json 2>&1 | tee debug.log

# Check for missing commands
command -v jq || echo "jq not found"
command -v sqlite3 || echo "sqlite3 not found"
command -v git || echo "git not found"

# Verify environment
echo "CLAUDE_PROJECT_DIR: $CLAUDE_PROJECT_DIR"
```

#### Database Queries Failing

**Symptom**: Cannot retrieve data from Task Orchestrator database

**Common Issues**:
1. Database path wrong - use `$CLAUDE_PROJECT_DIR/data/tasks.db`
2. UUID format issues - ensure UUIDs are in `x'...'` format without hyphens
3. Table/column names wrong - check schema with `sqlite3 data/tasks.db .schema`
4. sqlite3 not installed - install or use different approach

**Debug**:
```bash
# Verify database exists
ls -la "$CLAUDE_PROJECT_DIR/data/tasks.db"

# Check schema
sqlite3 "$CLAUDE_PROJECT_DIR/data/tasks.db" .schema

# Test query
TASK_ID="550e8400-e29b-41d4-a716-446655440000"
sqlite3 "$CLAUDE_PROJECT_DIR/data/tasks.db" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'"

# List all tasks
sqlite3 "$CLAUDE_PROJECT_DIR/data/tasks.db" "SELECT title FROM Tasks LIMIT 5"
```

#### Git Commands Failing

**Symptom**: Git operations fail or produce errors

**Common Issues**:
1. Not in git repository - check `.git` directory exists
2. Nothing to commit - add defensive check for changes
3. Merge conflicts - hook can't resolve, user must
4. Permission issues - check git credentials
5. Git user not configured - set `user.name` and `user.email`

**Debug**:
```bash
# Check if git repository
test -d .git && echo "Git repo" || echo "Not a git repo"

# Check for changes
git status --short

# Check git configuration
git config user.name
git config user.email

# Test commit without hook
git add -A
git commit -m "Test commit" --dry-run
```

### Logging Best Practices

Add logging to your hooks for debugging:

```bash
#!/bin/bash
# Hook with logging

LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/hook-debug.log"

# Log function
log() {
  echo "[$(date -u +"%Y-%m-%d %H:%M:%S")] $1" >> "$LOG_FILE"
}

log "Hook started"

INPUT=$(cat)
log "Input: $INPUT"

STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
log "Status: $STATUS"

if [ "$STATUS" != "completed" ]; then
  log "Status not completed, exiting"
  exit 0
fi

log "Proceeding with action"
# ... your logic ...

log "Hook completed successfully"
exit 0
```

View logs:

```bash
tail -f .claude/logs/hook-debug.log
```

---

## Best Practices

### 1. Defensive Programming

**Always validate conditions before acting**:

```bash
# Bad - assumes field exists
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
if [ "$STATUS" = "completed" ]; then
  # Might execute even if status doesn't exist
fi

# Good - uses fallback and validates
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
if [ -z "$STATUS" ]; then
  exit 0  # Field doesn't exist, safe exit
fi

if [ "$STATUS" = "completed" ]; then
  # Only executes if status explicitly set to "completed"
fi
```

**Check for required tools**:

```bash
# Check before using
if ! command -v jq &> /dev/null; then
  echo "⚠️  jq not available - skipping hook"
  exit 0
fi

if ! command -v git &> /dev/null; then
  echo "⚠️  git not available - skipping hook"
  exit 0
fi
```

**Validate database access**:

```bash
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
if [ ! -f "$DB_PATH" ]; then
  echo "⚠️  Database not found - skipping hook"
  exit 0
fi
```

### 2. Graceful Degradation

**Never break Claude's workflow**:

```bash
# Bad - fails loudly
cd "$CLAUDE_PROJECT_DIR" || exit 1
git commit -m "Message"  # Dies if commit fails

# Good - handles errors gracefully
if [ ! -d "$CLAUDE_PROJECT_DIR" ]; then
  echo "⚠️  Project directory not found, skipping"
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

if git commit -m "Message" 2>&1; then
  echo "✓ Commit created"
else
  echo "⚠️  Commit failed, but continuing"
fi

exit 0  # Always exit 0 for non-blocking hooks
```

### 3. Performance Optimization

**Keep hooks fast** (< 5 seconds for non-blocking, < 60 seconds for blocking):

```bash
# Bad - slow query
RESULT=$(sqlite3 "$DB_PATH" "SELECT * FROM Tasks")  # Queries all tasks

# Good - specific query
RESULT=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$UUID_NO_DASHES' LIMIT 1")

# Good - use indexes
# Database already has indexes on id, featureId, status
```

**Use timeouts in configuration**:

```json
{
  "hooks": [{
    "type": "command",
    "command": ".claude/hooks/long-running-hook.sh",
    "timeout": 300
  }]
}
```

### 4. Clear Feedback

**Provide descriptive messages**:

```bash
# Bad
echo "Done"

# Good
echo "✓ Created git commit abc1234 for completed task: Implement user authentication"

# Good - for blocking hooks
cat << EOF
{
  "decision": "block",
  "reason": "Cannot mark feature as completed because 3 tests are failing:

  - AuthServiceTest.testLoginWithInvalidPassword (Expected 401, got 500)
  - UserRepositoryTest.testCreateDuplicateUser (NullPointerException)
  - SessionManagerTest.testSessionExpiry (Timeout after 30s)

  Please fix these tests before marking the feature complete."
}
EOF
```

### 5. Documentation

**Document every hook**:

```bash
#!/bin/bash
# Task Complete Commit Hook
#
# Automatically creates git commits when tasks are marked complete.
# This provides automatic checkpointing of work as tasks finish.
#
# Hook Event: PostToolUse
# Matcher: mcp__task-orchestrator__set_status
# Trigger: When status is set to "completed"
#
# Usage:
#   Configure in .claude/settings.local.json:
#   {
#     "hooks": {
#       "PostToolUse": [{
#         "matcher": "mcp__task-orchestrator__set_status",
#         "hooks": [{
#           "type": "command",
#           "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-complete-commit.sh"
#         }]
#       }]
#     }
#   }
#
# Safety Features:
# - Graceful degradation (skips if git/sqlite3 unavailable)
# - Skips if no changes exist
# - Never blocks operations (always exits 0)
# - Defensive prerequisite checks
```

**Maintain README**:

Keep `.claude/hooks/README.md` updated with:
- List of all hooks
- Purpose of each hook
- Configuration instructions
- Testing examples
- Customization guides

### 6. Version Control

**Commit hooks to git**:

```bash
# Add hooks to git
git add .claude/hooks/
git add .claude/settings.local.json.example  # Template, not actual settings
git commit -m "feat: Add task completion commit hook"
```

**Create .gitignore entries**:

```gitignore
# .gitignore
.claude/settings.local.json  # User-specific settings
.claude/logs/                # Log files
.claude/metrics/             # Metrics data
```

### 7. Security First

**Never commit secrets**:

```bash
# Bad
SLACK_WEBHOOK="https://hooks.slack.com/services/SECRET/TOKEN"

# Good - use environment variables
SLACK_WEBHOOK="${SLACK_WEBHOOK_URL}"
if [ -z "$SLACK_WEBHOOK" ]; then
  echo "⚠️  SLACK_WEBHOOK_URL not set, skipping notification"
  exit 0
fi
```

**Sanitize user input** (though hooks receive structured JSON, not user input):

```bash
# Extract and validate
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Validate UUID format
if ! echo "$ENTITY_ID" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo "⚠️  Invalid UUID format"
  exit 0
fi
```

### 8. Testing Rigor

**Test all paths**:

1. Happy path (expected behavior)
2. Missing fields (graceful degradation)
3. Invalid data (validation)
4. Error conditions (failures)
5. Edge cases (empty, null, special characters)

**Example test scenarios**:

```bash
# Test 1: Happy path
echo '{"tool_input": {"id": "valid-uuid", "status": "completed"}}' | ./hook.sh

# Test 2: Missing status
echo '{"tool_input": {"id": "valid-uuid"}}' | ./hook.sh

# Test 3: Missing ID
echo '{"tool_input": {"status": "completed"}}' | ./hook.sh

# Test 4: Empty input
echo '{}' | ./hook.sh

# Test 5: Invalid JSON
echo 'not json' | ./hook.sh || echo "Handled invalid JSON"

# Test 6: Different status
echo '{"tool_input": {"id": "valid-uuid", "status": "in_progress"}}' | ./hook.sh
```

---

## Security Considerations

### Principle of Least Privilege

**Run hooks with minimal permissions**:

- Hooks run with the permissions of the Claude Code process
- Don't require sudo or elevated privileges
- Limit file system access to project directory
- Use read-only database queries when possible

### Input Validation

**Validate all extracted fields**:

```bash
# UUID validation
if ! echo "$ENTITY_ID" | grep -qE '^[0-9a-f-]{36}$'; then
  echo "Invalid UUID format"
  exit 0
fi

# Status validation (whitelist)
case "$STATUS" in
  "pending"|"in_progress"|"completed"|"blocked")
    # Valid status
    ;;
  *)
    echo "Invalid status: $STATUS"
    exit 0
    ;;
esac

# Integer validation
if ! [[ "$PRIORITY" =~ ^[0-9]+$ ]]; then
  echo "Invalid priority (not a number)"
  exit 0
fi
```

### Secrets Management

**Never hardcode secrets**:

```bash
# Bad
API_KEY="sk-1234567890abcdef"

# Good - use environment variables
API_KEY="${MY_API_KEY}"
if [ -z "$API_KEY" ]; then
  echo "⚠️  MY_API_KEY not set"
  exit 0
fi
```

**Store secrets securely**:

- Use environment variables (set in shell profile or Claude Code settings)
- Use secret management tools (1Password CLI, AWS Secrets Manager, etc.)
- Never commit `.claude/settings.local.json` with secrets to git

**Example with 1Password CLI**:

```bash
# Retrieve secret from 1Password
API_KEY=$(op read "op://Private/API Keys/MY_SERVICE")

if [ -z "$API_KEY" ]; then
  echo "⚠️  Failed to retrieve API key from 1Password"
  exit 0
fi
```

### Safe Command Execution

**Avoid command injection**:

```bash
# Bad - susceptible to injection
eval "git commit -m '$COMMIT_MSG'"

# Good - use proper quoting
git commit -m "$COMMIT_MSG"

# Good - use arrays for complex commands
ARGS=("commit" "-m" "$COMMIT_MSG")
git "${ARGS[@]}"
```

**Sanitize file paths**:

```bash
# Validate path is within project directory
FULL_PATH="$(cd "$CLAUDE_PROJECT_DIR" && pwd)"
TARGET_PATH="$(cd "$TARGET" && pwd)"

if [[ "$TARGET_PATH" != "$FULL_PATH"* ]]; then
  echo "⚠️  Path outside project directory"
  exit 0
fi
```

### Network Security

**Use HTTPS for external APIs**:

```bash
# Good - HTTPS
curl -X POST "https://api.example.com/webhook" \
  -H "Authorization: Bearer $API_KEY" \
  -d "$PAYLOAD"

# Bad - HTTP (insecure)
# curl -X POST "http://api.example.com/webhook" ...
```

**Verify SSL certificates**:

```bash
# Default behavior (verifies certificates)
curl -X POST "https://api.example.com/webhook"

# Only skip verification if absolutely necessary (development only)
# curl -k -X POST "https://localhost:8443/webhook"  # Don't do this in production
```

**Set timeouts**:

```bash
# Prevent hanging on network calls
curl -X POST "https://api.example.com/webhook" \
  --max-time 10 \
  --connect-timeout 5 \
  -d "$PAYLOAD"
```

### Error Information Disclosure

**Don't expose sensitive information in errors**:

```bash
# Bad - exposes database path and query
echo "Database query failed: $DB_PATH - SELECT * FROM Users WHERE password='...'"

# Good - generic error message
echo "⚠️  Database query failed"

# Good - log detailed error to file, show generic message
echo "[$(date)] Query failed: $FULL_QUERY" >> "$LOG_FILE"
echo "⚠️  Database error (see logs for details)"
```

### File Permissions

**Set appropriate permissions on hook scripts**:

```bash
# Make executable by user only
chmod 700 .claude/hooks/sensitive-hook.sh

# Make readable/executable by user and group
chmod 750 .claude/hooks/shared-hook.sh

# Standard executable (read/execute for all, write for user)
chmod 755 .claude/hooks/public-hook.sh
```

**Protect log files**:

```bash
# Create logs directory with restrictive permissions
mkdir -p .claude/logs
chmod 700 .claude/logs

# Create log file with user-only access
touch .claude/logs/sensitive.log
chmod 600 .claude/logs/sensitive.log
```

### Audit Trail

**Log security-relevant events**:

```bash
AUDIT_LOG="$CLAUDE_PROJECT_DIR/.claude/logs/audit.log"

audit_log() {
  echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $1" >> "$AUDIT_LOG"
}

audit_log "Hook executed: $0"
audit_log "User: $USER"
audit_log "Entity ID: $ENTITY_ID"
audit_log "Action: $ACTION"
```

### Defense in Depth

**Multiple layers of security**:

1. **Input validation** - Validate all extracted fields
2. **Environment checks** - Verify required tools and paths
3. **Permission checks** - Ensure files/directories are accessible
4. **Error handling** - Graceful degradation on failures
5. **Logging** - Audit trail of all actions
6. **Testing** - Comprehensive test suite including security tests

**Example layered approach**:

```bash
#!/bin/bash
# Multi-layer security example

set -euo pipefail

# Layer 1: Input validation
INPUT=$(cat)
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')

if [ -z "$ENTITY_ID" ]; then
  echo "⚠️  No entity ID provided"
  exit 0
fi

# Validate UUID format
if ! echo "$ENTITY_ID" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo "⚠️  Invalid UUID format"
  exit 0
fi

# Layer 2: Environment validation
if [ -z "${CLAUDE_PROJECT_DIR:-}" ]; then
  echo "⚠️  CLAUDE_PROJECT_DIR not set"
  exit 0
fi

# Verify we're in a project directory
if [ ! -d "$CLAUDE_PROJECT_DIR" ]; then
  echo "⚠️  Project directory does not exist"
  exit 0
fi

# Layer 3: Tool availability
if ! command -v sqlite3 &> /dev/null; then
  echo "⚠️  sqlite3 not available"
  exit 0
fi

# Layer 4: File permissions
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
if [ ! -r "$DB_PATH" ]; then
  echo "⚠️  Cannot read database (permission denied)"
  exit 0
fi

# Layer 5: Audit logging
AUDIT_LOG="$CLAUDE_PROJECT_DIR/.claude/logs/audit.log"
mkdir -p "$(dirname "$AUDIT_LOG")"
chmod 700 "$(dirname "$AUDIT_LOG")"
echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Hook executed for entity $ENTITY_ID" >> "$AUDIT_LOG"

# Perform the action with error handling
if ! RESULT=$(sqlite3 "$DB_PATH" "SELECT ..." 2>&1); then
  echo "⚠️  Query failed"
  echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Query failed: $RESULT" >> "$AUDIT_LOG"
  exit 0
fi

echo "✓ Hook completed successfully"
exit 0
```

---

## Complete Reference

### Working Hook Examples

Task Orchestrator includes three production-ready hooks:

#### 1. task-complete-commit.sh

**Location**: `.claude/hooks/task-manager/scripts/task-complete-commit.sh`

**Purpose**: Automatically creates git commits when tasks are marked complete

**Triggers**: `mcp__task-orchestrator__set_status` when status='completed'

**Actions**:
- Retrieves task title from database
- Stages all changes with `git add -A`
- Creates commit with task ID and title
- Reports commit hash

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__set_status",
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-manager/scripts/task-complete-commit.sh"
      }]
    }]
  }
}
```

**Commit Format**:
```
feat: Complete task - [Task Title]

Task-ID: [UUID]
Automated-By: Claude Code task-complete-commit hook
```

**Documentation**: `.claude/hooks/task-manager/task-complete-commit-README.md`

#### 2. feature-complete-gate.sh

**Location**: `.claude/hooks/task-manager/scripts/feature-complete-gate.sh`

**Purpose**: Quality gate that blocks feature completion if tests fail

**Triggers**: `mcp__task-orchestrator__update_feature` when status='completed'

**Actions**:
- Runs `./gradlew test` to verify all tests pass
- If tests pass: Allows operation (exit 0)
- If tests fail: Blocks operation (exit 2) with detailed error

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__update_feature",
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-manager/scripts/feature-complete-gate.sh",
        "timeout": 300
      }]
    }]
  }
}
```

**Testing**: Run comprehensive test suite:
```bash
bash .claude/hooks/task-manager/scripts/feature-complete-gate-test.sh
```

**Documentation**: `.claude/hooks/task-manager/USAGE_EXAMPLES.md`

#### 3. subagent-stop-logger.sh

**Location**: `.claude/hooks/task-manager/scripts/subagent-stop-logger.sh`

**Purpose**: Logs metrics about subagent execution for analysis

**Triggers**: SubagentStop (any agent completing)

**Actions**:
- Extracts agent name, task/feature IDs from prompt
- Categorizes agent by type
- Logs to human-readable and CSV formats
- Provides real-time statistics

**Configuration**:
```json
{
  "hooks": {
    "SubagentStop": [{
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-manager/scripts/subagent-stop-logger.sh"
      }]
    }]
  }
}
```

**Output Files**:
- `.claude/logs/subagent-metrics.log` - Human-readable
- `.claude/logs/subagent-metrics.csv` - Machine-readable

**Documentation**: `.claude/hooks/task-manager/subagent-stop-logger-README.md`

### Available Hook Templates

See `src/main/resources/skills/hook-builder/hook-templates.md` for 11 copy-paste templates:

1. **Basic PostToolUse Hook** - React to any MCP tool call
2. **Blocking Quality Gate Hook** - Prevent operations that don't meet criteria
3. **Database Query Hook** - Get data from Task Orchestrator database
4. **Git Automation Hook** - Automate git operations
5. **Logging/Metrics Hook** - Track events for analytics
6. **External API Hook** - Send data to external service
7. **Notification Hook** - Send notifications to user or team
8. **SubagentStop Hook** - React to subagent completion
9. **Conditional Multi-Action Hook** - Different actions based on conditions
10. **Dependency Check** - Check for blocking dependencies
11. **Error Handling Hook** - Robust error handling

### Configuration Reference

**Settings File Structure**:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__TOOL_NAME",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/hook1.sh",
            "timeout": 30
          },
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/hook2.sh"
          }
        ]
      },
      {
        "matcher": "mcp__task-orchestrator__ANOTHER_TOOL",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/hook3.sh",
            "timeout": 60
          }
        ]
      }
    ],
    "SubagentStop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/subagent-logger.sh"
          }
        ]
      }
    ]
  }
}
```

**Configuration Fields**:

- **matcher**: (PostToolUse only) Tool name to watch for (exact match required)
- **type**: Always `"command"` for bash scripts
- **command**: Path to hook script (use `$CLAUDE_PROJECT_DIR` for portability)
- **timeout**: (Optional) Maximum execution time in seconds

**Settings File Locations**:

| Platform | Path |
|----------|------|
| Project-specific | `.claude/settings.local.json` (recommended) |
| Windows | `%APPDATA%\Claude\config\settings.json` |
| macOS | `~/Library/Application Support/Claude/config/settings.json` |
| Linux | `~/.config/Claude/config/settings.json` |

### Tool Matcher Reference

**Task Management**:
- `mcp__task-orchestrator__create_task`
- `mcp__task-orchestrator__get_task`
- `mcp__task-orchestrator__update_task`
- `mcp__task-orchestrator__delete_task`
- `mcp__task-orchestrator__set_status`
- `mcp__task-orchestrator__search_tasks`

**Feature Management**:
- `mcp__task-orchestrator__create_feature`
- `mcp__task-orchestrator__get_feature`
- `mcp__task-orchestrator__update_feature`
- `mcp__task-orchestrator__delete_feature`
- `mcp__task-orchestrator__search_features`

**Project Management**:
- `mcp__task-orchestrator__create_project`
- `mcp__task-orchestrator__get_project`
- `mcp__task-orchestrator__update_project`
- `mcp__task-orchestrator__delete_project`

**Dependency Management**:
- `mcp__task-orchestrator__create_dependency`
- `mcp__task-orchestrator__delete_dependency`
- `mcp__task-orchestrator__get_task_dependencies`

**Section Management**:
- `mcp__task-orchestrator__add_section`
- `mcp__task-orchestrator__update_section`
- `mcp__task-orchestrator__update_section_text`
- `mcp__task-orchestrator__delete_section`
- `mcp__task-orchestrator__get_sections`

See [api-reference.md](api-reference.md) for complete tool documentation.

### Common Database Queries

**Task Queries**:

```sql
-- Get task details
SELECT title, status, priority, complexity, summary
FROM Tasks
WHERE id=x'UUID_WITHOUT_DASHES';

-- Get all tasks in a feature
SELECT id, title, status, priority
FROM Tasks
WHERE featureId=x'FEATURE_UUID_WITHOUT_DASHES'
ORDER BY priority DESC, createdAt ASC;

-- Get incomplete tasks
SELECT id, title, priority
FROM Tasks
WHERE status IN ('pending', 'in_progress', 'blocked')
ORDER BY priority DESC;

-- Get tasks by tag
SELECT t.id, t.title, t.status
FROM Tasks t
JOIN TaskTags tt ON t.id = tt.taskId
WHERE tt.tag = 'backend';
```

**Feature Queries**:

```sql
-- Get feature details
SELECT name, status, summary
FROM Features
WHERE id=x'FEATURE_UUID_WITHOUT_DASHES';

-- Get all features in project
SELECT id, name, status
FROM Features
WHERE projectId=x'PROJECT_UUID_WITHOUT_DASHES'
ORDER BY createdAt DESC;

-- Count tasks in feature by status
SELECT status, COUNT(*) as count
FROM Tasks
WHERE featureId=x'FEATURE_UUID_WITHOUT_DASHES'
GROUP BY status;
```

**Dependency Queries**:

```sql
-- Get task dependencies (tasks that must complete first)
SELECT t.id, t.title, t.status
FROM Tasks t
JOIN TaskDependencies d ON t.id = d.taskId
WHERE d.dependentTaskId=x'DEPENDENT_TASK_UUID_WITHOUT_DASHES';

-- Get dependent tasks (tasks waiting on this one)
SELECT t.id, t.title, t.status
FROM Tasks t
JOIN TaskDependencies d ON t.id = d.dependentTaskId
WHERE d.taskId=x'TASK_UUID_WITHOUT_DASHES';

-- Check for blocking dependencies
SELECT COUNT(*) as blocking_count
FROM Tasks t
JOIN TaskDependencies d ON t.id = d.taskId
WHERE d.dependentTaskId=x'TASK_UUID_WITHOUT_DASHES'
  AND t.status != 'completed';
```

**Section Queries**:

```sql
-- Get all sections for a task
SELECT id, title, contentFormat, ordinal
FROM Sections
WHERE entityType = 'TASK'
  AND entityId=x'TASK_UUID_WITHOUT_DASHES'
ORDER BY ordinal;

-- Get section content by title
SELECT content
FROM Sections
WHERE entityType = 'TASK'
  AND entityId=x'TASK_UUID_WITHOUT_DASHES'
  AND title = 'Requirements';

-- Count sections by format
SELECT contentFormat, COUNT(*) as count
FROM Sections
WHERE entityId=x'ENTITY_UUID_WITHOUT_DASHES'
GROUP BY contentFormat;
```

### jq Command Reference

**Basic Extraction**:

```bash
# Simple field
FIELD=$(echo "$INPUT" | jq -r '.field_name')

# Nested field
NESTED=$(echo "$INPUT" | jq -r '.parent.child.field')

# Array element
ELEMENT=$(echo "$INPUT" | jq -r '.array[0]')

# With fallback (returns "empty" if missing)
FIELD=$(echo "$INPUT" | jq -r '.field // empty')

# With default value
FIELD=$(echo "$INPUT" | jq -r '.field // "default"')
```

**Conditional Extraction**:

```bash
# Extract if condition is true
VALUE=$(echo "$INPUT" | jq -r 'if .status == "completed" then .id else empty end')

# Select objects matching condition
RESULT=$(echo "$INPUT" | jq '.items[] | select(.priority == "high")')
```

**Multiple Fields**:

```bash
# Extract multiple fields to variables
read -r ID STATUS PRIORITY <<< $(echo "$INPUT" | jq -r '.id, .status, .priority')

# Create new JSON with selected fields
OUTPUT=$(echo "$INPUT" | jq '{id: .id, title: .title}')
```

**Array Processing**:

```bash
# Get array length
COUNT=$(echo "$INPUT" | jq '.items | length')

# Join array elements
JOINED=$(echo "$INPUT" | jq -r '.tags | join(",")')

# Filter array
FILTERED=$(echo "$INPUT" | jq '[.items[] | select(.status == "completed")]')
```

### Exit Code Reference

| Exit Code | Meaning | Hook Behavior |
|-----------|---------|---------------|
| 0 | Success | Allow operation to proceed |
| 1 | Unexpected error | Allow operation (fail-safe) |
| 2 | Validation failed | Block operation (must include block JSON) |

**Blocking Operation Example**:

```bash
if [ "$VALIDATION_FAILED" = "true" ]; then
  cat << EOF
{
  "decision": "block",
  "reason": "Detailed explanation of why blocked"
}
EOF
  exit 2
fi
```

### Environment Variable Reference

**Claude Code Variables**:

- `$CLAUDE_PROJECT_DIR` - Project root directory (always set by Claude Code)
- `$CLAUDE_SESSION_ID` - Current session ID
- `$USER` - Current user (system variable)
- `$HOME` - User home directory (system variable)

**Custom Variables**:

Set in shell profile or Claude Code settings:

```bash
# .bashrc or .zprofile
export MY_API_KEY="sk-..."
export SLACK_WEBHOOK_URL="https://hooks.slack.com/..."
export NOTIFICATION_EMAIL="team@example.com"
```

**Using in Hooks**:

```bash
API_KEY="${MY_API_KEY}"
if [ -z "$API_KEY" ]; then
  echo "⚠️  MY_API_KEY not set"
  exit 0
fi
```

---

## Additional Resources

### Documentation

- **[Quick Reference](../.claude/hooks/task-orchestrator/QUICK_REFERENCE.md)** - Quick start guide
- **[Usage Examples](../.claude/hooks/task-orchestrator/USAGE_EXAMPLES.md)** - Detailed usage scenarios
- **[Hook Templates](../src/main/resources/skills/hook-builder/hook-templates.md)** - 11 copy-paste templates
- **[Hook Builder Skill](../src/main/resources/skills/hook-builder/SKILL.md)** - Interactive hook creation
- **[API Reference](api-reference.md)** - Complete MCP tools documentation
- **[Agent Orchestration](agent-orchestration.md)** - Subagent workflow guide

### Example Hooks

- **[task-complete-commit.sh](../.claude/hooks/task-orchestrator/scripts/task-complete-commit.sh)** - Auto-commit on task completion
- **[feature-complete-gate.sh](../.claude/hooks/task-orchestrator/scripts/feature-complete-gate.sh)** - Test quality gate
- **[subagent-stop-logger.sh](../.claude/hooks/task-orchestrator/scripts/subagent-stop-logger.sh)** - Agent metrics logging

### Testing Examples

- **[feature-complete-gate-test.sh](../.claude/hooks/task-orchestrator/scripts/feature-complete-gate-test.sh)** - Comprehensive test suite

### Configuration Templates

- **[settings.local.json.example](../.claude/hooks/task-orchestrator/templates/settings.local.json.example)** - Complete configuration example
- **[feature-complete-gate.config.example.json](../.claude/hooks/task-orchestrator/templates/feature-complete-gate.config.example.json)** - Quality gate configuration

---

## Getting Help

### Troubleshooting

If you encounter issues:

1. **Check Prerequisites**: Verify `jq`, `sqlite3`, `git` are installed
2. **Test Manually**: Run hook with sample JSON input
3. **Enable Debugging**: Add `set -x` to see execution trace
4. **Check Logs**: Review `.claude/logs/` for error messages
5. **Verify Configuration**: Ensure `.claude/settings.local.json` is correct

### Common Questions

**Q: Can I use hooks with other LLMs (Cursor, Windsurf)?**
A: Hooks are specific to Claude Code. Other LLMs may have different extension/plugin systems.

**Q: Do hooks work on Windows?**
A: Yes, using Git Bash or WSL. The shebang (`#!/bin/bash`) and bash commands work in Git Bash.

**Q: Can hooks modify Claude's behavior?**
A: Blocking hooks (exit 2 + block JSON) can prevent operations. Non-blocking hooks observe and react but don't modify Claude's core functionality.

**Q: How do I share hooks with my team?**
A: Commit hook scripts to git. Each team member configures `.claude/settings.local.json` on their machine.

**Q: Can I use hooks for CI/CD integration?**
A: Yes! Hooks can trigger CI/CD pipelines via webhooks, update GitHub/Jira, or run deployment scripts.

### Contributing

Found a useful hook pattern? Share it with the community:

1. Add your hook to `.claude/hooks/`
2. Create documentation in `.claude/hooks/README.md`
3. Add test script following `feature-complete-gate-test.sh` pattern
4. Submit PR to Task Orchestrator repository

---

## Conclusion

Claude Code hooks provide powerful workflow automation capabilities. By following the patterns and best practices in this guide, you can:

- Automate repetitive tasks (git commits, notifications)
- Enforce quality standards (test gates, dependency checks)
- Collect metrics (agent usage, task completion times)
- Integrate external systems (Jira, Slack, CI/CD)

**Key Takeaways**:

1. **Start simple** - Begin with logging/metrics before blocking hooks
2. **Be defensive** - Always validate conditions and handle errors gracefully
3. **Test thoroughly** - Use comprehensive test suites for all hooks
4. **Document well** - Future you (and your team) will thank you
5. **Use the Hook Builder** - Interactive creation is easier than manual

**Next Steps**:

1. Review the [three working examples](../.claude/hooks/task-orchestrator/)
2. Try the [Hook Builder Skill](../src/main/resources/skills/hook-builder/SKILL.md)
3. Customize [hook templates](../src/main/resources/skills/hook-builder/hook-templates.md) for your workflow
4. Share successful patterns with the community

Happy hooking! 🎣
