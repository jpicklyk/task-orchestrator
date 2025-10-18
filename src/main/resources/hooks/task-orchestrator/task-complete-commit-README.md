# Task Complete Commit Hook

Automatically creates git commits when tasks are marked complete in Task Orchestrator.

## Purpose

Provides automatic checkpointing of work as tasks finish. Each completed task triggers a git commit, creating a clear audit trail of progress.

## How It Works

1. **Listens** for `mcp__task-orchestrator__set_status` tool calls
2. **Checks** if status is being set to `"completed"`
3. **Retrieves** task title from Task Orchestrator database
4. **Stages** all changes with `git add -A`
5. **Commits** with descriptive message including task title and ID
6. **Reports** success or issues back to Claude Code

## Configuration

Add to `.claude/settings.local.json`:

```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__set_status",
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-complete-commit.sh"
      }]
    }]
  }
}
```

## Commit Message Format

```
feat: Complete task - [Task Title]

Task-ID: [UUID]
Automated-By: Claude Code task-complete-commit hook
```

Example:
```
feat: Complete task - Add summary field to Task entity

Task-ID: 78efafeb-2e07-47c4-8a79-21284198c482
Automated-By: Claude Code task-complete-commit hook
```

## Prerequisites

- Git repository (`.git` directory exists)
- `git` command available
- `sqlite3` command available
- Task Orchestrator database at `data/tasks.db`

## Behavior

### When Commit is Created
- Changes exist in working directory or staging area
- Task status is being set to "completed"
- All prerequisites are met
- **Output**: `✓ Created git commit abc1234 for completed task: [Title]`

### When Commit is Skipped
- No changes to commit (clean working directory)
- **Output**: `ℹ️  No changes to commit for task: [Title]`

### Graceful Degradation
- Not a git repository → Skip with warning
- Git not available → Skip with warning
- sqlite3 not available → Skip with warning
- Database not found → Skip with warning
- Cannot retrieve task title → Skip with warning

## Customization

### Change Commit Message Prefix
Edit line 84 to use different conventional commit type:
```bash
COMMIT_MSG="fix: Complete task - $TASK_TITLE"  # For bug fixes
COMMIT_MSG="docs: Complete task - $TASK_TITLE"  # For documentation
```

### Add Co-Author Attribution
Add after line 87:
```bash
Co-authored-by: Claude <noreply@anthropic.com>"
```

### Stage Specific Files Only
Replace `git add -A` (line 79) with:
```bash
git add src/ docs/  # Only stage src and docs directories
```

### Require Clean Status
Add before line 79:
```bash
if [ -n "$(git status --porcelain --untracked-files=no | grep '^ M')" ]; then
  echo "⚠️  Unstaged changes detected - please stage manually"
  exit 0
fi
```

## Testing

Test the hook with a mock JSON input:

```bash
echo '{
  "tool_input": {
    "id": "78efafeb-2e07-47c4-8a79-21284198c482",
    "status": "completed"
  }
}' | ./.claude/hooks/task-complete-commit.sh
```

Expected output (if changes exist):
```
✓ Created git commit abc1234 for completed task: Add summary field to Task entity
```

## Troubleshooting

### Commit Not Created

**Problem**: Hook runs but no commit is created

**Solutions**:
1. Check if changes exist: `git status`
2. Verify working directory is correct: `echo $CLAUDE_PROJECT_DIR`
3. Check git configuration: `git config user.name` and `git config user.email`
4. Run hook manually with test input (see Testing section)

### Cannot Retrieve Task Title

**Problem**: `⚠️  Could not retrieve task title for [UUID]`

**Solutions**:
1. Verify database exists: `ls $CLAUDE_PROJECT_DIR/data/tasks.db`
2. Check task exists: `sqlite3 data/tasks.db "SELECT title FROM Tasks LIMIT 5"`
3. Verify UUID format in database (stored as BLOB, queried with hex conversion)

### Git Command Fails

**Problem**: `⚠️  Git commit failed with exit code [N]`

**Solutions**:
1. Check git user configuration: `git config user.name` and `git config user.email`
2. Review git output for specific error message
3. Ensure no git hooks are blocking the commit
4. Check file permissions on `.git` directory

## Security Considerations

- **No external data**: Only reads from local Task Orchestrator database
- **No network calls**: All operations are local
- **Defensive checks**: Validates all prerequisites before execution
- **Safe failure**: Always exits 0 (non-blocking) even on errors
- **No user input**: All data comes from Task Orchestrator

## Integration with Workflow

Works seamlessly with Task Orchestrator workflow:

1. **Task Manager END** marks task complete → `set_status(status='completed')`
2. **Hook triggers** on PostToolUse event
3. **Commit created** with task details
4. **Orchestrator sees** commit feedback in next message
5. **Continue** with next task

This provides automatic version control checkpointing without manual git operations.
