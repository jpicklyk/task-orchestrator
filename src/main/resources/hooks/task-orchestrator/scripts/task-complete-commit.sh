#!/bin/bash
#
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

# Check if sqlite3 is available
if ! command -v sqlite3 &> /dev/null; then
  echo "⚠️  sqlite3 not available - cannot retrieve task title"
  exit 0
fi

# Get task title from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
if [ ! -f "$DB_PATH" ]; then
  echo "⚠️  Database not found at $DB_PATH - skipping commit"
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
Automated-By: Claude Code task-complete-commit hook"

git commit -m "$COMMIT_MSG" 2>&1

COMMIT_RESULT=$?

if [ $COMMIT_RESULT -eq 0 ]; then
  COMMIT_HASH=$(git rev-parse --short HEAD)
  echo "✓ Created git commit $COMMIT_HASH for completed task: $TASK_TITLE"
else
  echo "⚠️  Git commit failed with exit code $COMMIT_RESULT"
fi

exit 0
