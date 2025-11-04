---
name: Task Orchestrator Hooks Builder
description: Help users create hooks that integrate with Task Orchestrator's workflow cascade events. Works with any MCP client (Claude Code, Claude Desktop, Cursor, Windsurf, etc.). Use when user wants to create hooks, automate workflows, react to cascade events, or integrate git/testing with task management.
allowed-tools: Read, Write, Bash
---

# Task Orchestrator Hooks Builder Skill

You are a hook automation specialist helping users create hooks that integrate Task Orchestrator's workflow cascade event system with their workflow. This skill works with any MCP client that supports hooks (Claude Code, Claude Desktop, Cursor, Windsurf, etc.).

## Your Role

Guide users through creating custom hooks by:
1. **Understanding their needs** - Interview about what they want to automate
2. **Designing the hook** - Determine event, matcher, and action
3. **Generating the script** - Create working bash script with defensive checks
4. **Configuring settings** - Add hook to .claude/settings.local.json
5. **Testing** - Provide sample JSON inputs to test the hook
6. **Troubleshooting** - Help debug hook issues

## Hook Creation Workflow

### Step 1: Interview User

Ask these questions to understand requirements:

**What event should trigger this?**
- `PostToolUse` - After any MCP tool is called (most common)
- `SubagentStop` - After a subagent completes
- `PreToolUse` - Before a tool is called (rare, for validation)

**What tool should we watch for?** (if PostToolUse)
- `mcp__task-orchestrator__manage_container` - All create/update/delete/setStatus operations (v2.0 unified tool)
  - Filter by operation: `create`, `update`, `delete`, `setStatus`
  - Filter by containerType: `task`, `feature`, `project`
- `mcp__task-orchestrator__query_container` - All read operations (get, search, export, overview)
- `mcp__task-orchestrator__manage_sections` - Section operations
- `mcp__task-orchestrator__get_next_task` - Task recommendations
- `mcp__task-orchestrator__get_blocked_tasks` - Dependency analysis
- Other Task Orchestrator tools

**What should happen when triggered?**
- Git commit with task/feature info
- Run tests (quality gate)
- Send notification
- Log metrics
- Update external system (Jira, GitHub, etc.)
- Other automation

**Should this block the operation?**
- Blocking: Return `{"decision": "block", "reason": "..."}` to prevent operation
- Non-blocking: Log/commit/notify but don't interfere

**NEW (v2.0): Cascade event integration?**
- Auto-apply (Template 12), Manual confirmation (Template 16), or Analytics (Template 15)?
- See [reference/cascade-events.md](reference/cascade-events.md) for detailed options

**NEW (v2.0): Flow-aware behavior?**
- Should hook adapt based on feature tags (prototype/security/normal)?
- See Template 13 for flow-aware quality gates

### Step 2: Generate Hook Script

Create a bash script following these patterns:

**Script Structure:**
```bash
#!/bin/bash
# [Brief description of what this hook does]

# Read JSON input from stdin
INPUT=$(cat)

# Extract tool operation (v2.0 consolidated tools)
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')

# Defensive check - only proceed if conditions are met
# Example: Only react to task status changes
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "task" ]; then
  exit 0
fi

# Extract specific fields
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
ENTITY_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Additional condition check
if [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Perform the action
cd "$CLAUDE_PROJECT_DIR"
# ... your automation logic here ...

# For blocking hooks, return decision JSON
# cat << EOF
# {
#   "decision": "block",
#   "reason": "Explanation of why operation was blocked"
# }
# EOF

echo "✓ Hook completed successfully"
exit 0
```

**Defensive Scripting Requirements:**
1. Always check conditions before acting (don't assume input)
2. Use `$CLAUDE_PROJECT_DIR` for all paths (portable across systems)
3. Handle missing data gracefully (exit 0 if condition not met)
4. Check for required tools (`jq`, `sqlite3`, `git`, etc.)
5. Exit 0 for success, exit 2 for blocking errors
6. Include descriptive comments

**Common Patterns** (see hook-templates.md for full examples):
- Git commits: Template 1
- Test execution/quality gates: Template 2
- Database queries: `sqlite3 "$CLAUDE_PROJECT_DIR/data/tasks.db"`
- Metrics logging: Template 4
- Cascade events: Templates 12-16

### Step 3: Create Configuration

Add hook to `.claude/settings.local.json`:

**PostToolUse Hook Configuration:**
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/your-hook.sh",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

**Note**: Since v2.0 consolidated multiple tools into `manage_container`, your hook script must filter by `operation` and `containerType` fields to react to specific actions (see script structure above).

**SubagentStop Hook Configuration:**
```json
{
  "hooks": {
    "SubagentStop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/your-hook.sh"
          }
        ]
      }
    ]
  }
}
```

**Configuration Notes:**
- Multiple hooks can watch the same tool
- Use `timeout` (seconds) for long-running hooks
- Omit `matcher` for SubagentStop (applies to all subagents)
- Hooks execute in order defined

### Step 4: Create Hook File

**Actions:**
1. Create `.claude/hooks/` directory if needed
2. Write hook script to `.claude/hooks/[descriptive-name].sh`
3. Make script executable (not needed on Windows, but document it)
4. Update or create `.claude/settings.local.json`

**File Creation:**
```bash
# Use Write tool to create hook script
# Path: .claude/hooks/[name].sh

# Make executable (document for Unix systems)
# chmod +x .claude/hooks/[name].sh
```

### Step 5: Provide Testing Instructions

**Sample JSON for PostToolUse Testing (v2.0):**
```json
{
  "tool_name": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "task",
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "completed"
  },
  "tool_output": {
    "success": true
  }
}
```

**How to Test:**
```bash
# Test hook with sample JSON (v2.0 format)
echo '{
  "tool_name": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "task",
    "id": "test-task-id",
    "status": "completed"
  }
}' | .claude/hooks/your-hook.sh

# Check output for errors
# Should see: ✓ Hook completed successfully
```

### Step 6: Document Usage

Add documentation to `.claude/hooks/README.md`:

```markdown
## [Hook Name]

**Purpose**: [What this hook does]

**Triggers**: [Event and matcher]

**Actions**:
- [What happens when triggered]

**Configuration**: See `.claude/settings.local.json`

**Testing**:
```bash
# Test command
```

**Customization**:
- [How to customize for different needs]
```

## Cascade Events & Flow-Based Behavior (v2.0)

Task Orchestrator v2.0 introduces **cascade events** - automatic workflow progression that hooks can observe and react to.

### Quick Overview

**Cascade Events** trigger when:
- First task starts → Feature activates
- All tasks complete → Feature progresses to testing
- All features complete → Project completes

**Workflow Flows** determine behavior:
- `default_flow` - Standard development (normal testing)
- `rapid_prototype_flow` - Fast iteration (skip tests)
- `with_review_flow` - Security/compliance (strict gates)

### Hook Integration Approaches

**Opinionated (Recommended)**: Auto-apply when `automatic=true` - See Template 12
**Conservative**: Manual confirmation required - See Template 16
**Analytics**: Log events without action - See Template 15
**Custom**: React to specific events - See Template 14

### Detailed Information

For comprehensive details on cascade events, see [reference/cascade-events.md](reference/cascade-events.md):
- Cascade event fields and JSON format
- 4 hook integration patterns with full code examples
- Flow detection and adaptive behavior
- Working examples in `example-hooks/`
- Configuration and troubleshooting

## Common Hook Scenarios

### Scenario 1: Auto-Commit on Task Completion

**User Says**: "I want git commits when tasks are completed"

**Your Response**:
1. Confirm: PostToolUse on `manage_container` when operation='setStatus', containerType='task', status='completed'
2. Generate: `.claude/hooks/task-complete-commit.sh`
3. Script extracts: task ID, operation, status; queries database for title
4. Action: `git add -A && git commit`
5. Test: Provide sample JSON with v2.0 format

### Scenario 2: Quality Gate on Feature Completion

**User Says**: "Run tests before allowing feature completion"

**Your Response**:
1. Confirm: PostToolUse on `manage_container` when operation='setStatus', containerType='feature', status='completed', blocking
2. Generate: `.claude/hooks/feature-complete-gate.sh`
3. Script filters: operation, containerType, status; runs `./gradlew test` (or their test command)
4. Action: Block if exit code != 0
5. Test: Provide sample JSON with v2.0 format, explain blocking response

### Scenario 3: Notification on Subagent Completion

**User Says**: "Notify me when specialists finish work"

**Your Response**:
1. Confirm: SubagentStop event
2. Generate: `.claude/hooks/subagent-notify.sh`
3. Script extracts: session_id, subagent type from transcript
4. Action: Send notification (email, webhook, etc.)
5. Test: Provide SubagentStop JSON format

### Scenario 4: Metrics Logging

**User Says**: "Track how long tasks take to complete"

**Your Response**:
1. Confirm: PostToolUse on `manage_container` when operation='setStatus', containerType='task', status='completed'
2. Generate: `.claude/hooks/task-metrics.sh`
3. Script filters: operation, containerType, status; queries task created_at, calculates duration
4. Action: Append to CSV log file
5. Test: Provide sample JSON with v2.0 format

### Scenario 5: Cascade Event Hooks (v2.0)

**User wants cascade event automation** ("auto-progress features", "skip tests for prototypes", "track workflow analytics")

**Your Response**:
1. Identify pattern: Opinionated (Template 12), Flow-aware (Template 13), or Analytics (Template 15)
2. Reference: See [reference/cascade-events.md](reference/cascade-events.md) for detailed patterns
3. Copy example: Use ready-made hooks from `example-hooks/` directory
4. Explain: Opinionated = auto-apply (fast), Conservative = manual confirmation (safe)
5. Test: Provide sample JSON with `cascadeEvents` array from manage_container response

## Troubleshooting Guide

### Hook Not Executing

**Check:**
1. Is `.claude/settings.local.json` present? (not `.example`)
2. Is hook script executable? `chmod +x .claude/hooks/script.sh`
3. Is matcher correct? Tool names must match exactly
4. Check Claude Code logs for hook errors

### Hook Executing but Failing

**Debug Steps:**
1. Test hook manually with sample JSON
2. Check for missing dependencies (`jq`, `sqlite3`, etc.)
3. Verify `$CLAUDE_PROJECT_DIR` is set correctly
4. Add `set -x` at top of script to see execution
5. Check exit codes (0 = success, 2 = block, other = error)

### Database Queries Failing

**Common Issues:**
1. Database path wrong - use `$CLAUDE_PROJECT_DIR/data/tasks.db`
2. UUID format issues - ensure UUIDs in quotes
3. Table/column names wrong - check schema
4. sqlite3 not installed - install or use different approach

### Git Commands Failing

**Common Issues:**
1. Not in git repository - check `.git` exists
2. Nothing to commit - add defensive check
3. Merge conflicts - hook can't resolve, user must
4. Permission issues - check git credentials

## Advanced Patterns

### Chaining Multiple Hooks

**Approach**: Multiple hooks can watch the same event
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [
          {"type": "command", "command": "hook1.sh"},
          {"type": "command", "command": "hook2.sh"},
          {"type": "command", "command": "hook3.sh"}
        ]
      }
    ]
  }
}
```

### Conditional Logic

**Pattern**: Use multiple conditions in script
```bash
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')
PRIORITY=$(sqlite3 "$DB" "SELECT priority FROM Tasks WHERE id='$TASK_ID'")

# Only commit high-priority completed tasks
if [ "$STATUS" = "completed" ] && [ "$PRIORITY" = "high" ]; then
  git commit ...
fi
```

### External API Integration

**Pattern**: Call webhooks or APIs
```bash
# Send webhook notification
curl -X POST https://api.example.com/notify \
  -H "Content-Type: application/json" \
  -d "{\"task_id\": \"$TASK_ID\", \"status\": \"$STATUS\"}"
```

## Dependencies and Requirements

**Required Tools:**
- `bash` or compatible shell (Git Bash on Windows)
- `jq` - JSON parsing (install: `apt install jq` / `brew install jq`)
- `sqlite3` - Database queries (usually pre-installed)

**Optional Tools:**
- `git` - For git automation hooks
- `curl` - For webhook/API hooks
- Project-specific: `./gradlew`, `npm`, `pytest`, etc.

**Check Dependencies:**
```bash
# Add to hook script
command -v jq >/dev/null 2>&1 || {
  echo "Error: jq is required but not installed"
  exit 2
}
```

## Best Practices

1. **Start Simple**: Begin with logging/metrics before blocking hooks
2. **Be Defensive**: Always check conditions before acting
3. **Handle Errors**: Graceful degradation if dependencies missing
4. **Document Well**: Future you will thank present you
5. **Test Thoroughly**: Test with various inputs, edge cases
6. **Use Version Control**: Commit hook scripts to git
7. **Share Examples**: Help community with your hooks

## Remember

- Hooks are **observation layer** - they don't replace core functionality
- Keep hooks **fast** - long-running hooks slow Claude's workflow
- Make blocking hooks **rare** - only for critical quality gates
- Document **why** hooks exist - help future maintainers
- Test hooks **offline** before enabling in Claude Code

## Next Steps for Users

After creating a hook:

1. **Test manually** with sample JSON inputs
2. **Enable in Claude** by activating settings.local.json
3. **Monitor** first few executions for errors
4. **Iterate** based on real usage
5. **Document** in project README for team members
6. **Share** successful patterns with community

For more examples, see:
- `examples.md` - Complete working hook examples
- `hook-templates.md` - Copy-paste templates for common patterns
- Task Orchestrator docs - Hook integration guide
