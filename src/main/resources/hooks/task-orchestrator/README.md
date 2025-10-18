# Task Orchestrator Hooks

This directory contains example hook scripts that integrate Claude Code with the MCP Task Orchestrator for automated workflows, quality gates, and metrics tracking.

## Directory Structure

```
task-orchestrator/
├── README.md (this file)
├── scripts/                      # Hook bash scripts
│   ├── task-complete-commit.sh
│   ├── feature-complete-gate.sh
│   ├── feature-complete-gate-test.sh
│   └── subagent-stop-logger.sh
├── templates/                    # Configuration templates
│   ├── settings.local.json.example
│   └── feature-complete-gate.config.example.json
├── task-complete-commit-README.md       # Detailed docs per hook
├── subagent-stop-logger-README.md
├── QUICK_REFERENCE.md           # Quick start guide
└── USAGE_EXAMPLES.md            # Detailed usage scenarios
```

## Available Hooks

### 1. task-complete-commit.sh

**Purpose**: Automatically creates git commits when tasks are marked complete, providing checkpoint-based version control.

**Triggers on**: `mcp__task-orchestrator__set_status` PostToolUse events

**Condition**: Status is being set to `"completed"`

**Action**:
- Retrieves task title from Task Orchestrator database
- Stages all changes with `git add -A`
- Creates commit with task ID and title in message
- Provides feedback with commit hash

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__set_status",
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-orchestrator/scripts/task-complete-commit.sh"
      }]
    }]
  }
}
```

**Safety Features**:
- Graceful degradation (skips if git/sqlite3 unavailable)
- Skips if no changes exist
- Never blocks operations (always exits 0)
- Defensive prerequisite checks

**Documentation**: See [task-complete-commit-README.md](task-complete-commit-README.md)

---

### 2. feature-complete-gate.sh

**Purpose**: Quality gate that prevents features from being marked complete if project tests are failing.

**Triggers on**: `mcp__task-orchestrator__update_feature` PostToolUse events

**Condition**: Status is being set to `"completed"`

**Action**:
- Runs `./gradlew test` to verify all tests pass
- If tests pass: Allows operation to proceed
- If tests fail: **Blocks operation** with exit code 2 and provides helpful error message

**Configuration**:
```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__update_feature",
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-orchestrator/scripts/feature-complete-gate.sh",
        "timeout": 300
      }]
    }]
  }
}
```

**Testing**:
```bash
# Run comprehensive tests
bash .claude/hooks/task-orchestrator/scripts/feature-complete-gate-test.sh
```

**Safety Features**:
- Graceful degradation (allows operation if tools missing)
- Clear feedback when blocking
- Comprehensive test suite (6 test cases, 100% coverage)
- Error handling with traps

**Documentation**: See [USAGE_EXAMPLES.md](USAGE_EXAMPLES.md) and [QUICK_REFERENCE.md](QUICK_REFERENCE.md)

---

### 3. subagent-stop-logger.sh

**Purpose**: Logs metrics about all subagent executions for workflow analysis and optimization.

**Triggers on**: `SubagentStop` events (any agent completing)

**Action**:
- Extracts agent name, task/feature IDs from subagent prompt
- Categorizes agent by type (implementation, quality, documentation, coordination, planning)
- Logs to both human-readable and CSV formats
- Provides real-time statistics (total runs, top 3 agents)

**Configuration**:
```json
{
  "hooks": {
    "SubagentStop": [{
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-orchestrator/scripts/subagent-stop-logger.sh"
      }]
    }]
  }
}
```

**Output Files**:
- `.claude/logs/subagent-metrics.log` - Human-readable format
- `.claude/logs/subagent-metrics.csv` - Machine-readable for analysis

**Use Cases**:
- Track which agents are most used
- Analyze workflow efficiency
- Identify bottlenecks
- Historical audit trail

**Documentation**: See [subagent-stop-logger-README.md](subagent-stop-logger-README.md)

---

## Quick Start

### Step 1: Install Prerequisites

```bash
# Check if jq is installed
jq --version

# Install jq if needed
# Ubuntu/Debian
sudo apt install jq

# macOS
brew install jq

# Windows (via Chocolatey)
choco install jq
```

### Step 2: Make Scripts Executable

```bash
chmod +x .claude/hooks/task-orchestrator/scripts/*.sh
```

### Step 3: Configure Hooks

Copy the template to your Claude settings:

```bash
cp .claude/hooks/task-orchestrator/templates/settings.local.json.example \
   .claude/settings.local.json
```

Edit `.claude/settings.local.json` to enable desired hooks.

### Step 4: Test Hooks

```bash
# Test task completion hook
echo '{"tool_input": {"id": "test-uuid", "status": "completed"}}' | \
  CLAUDE_PROJECT_DIR=. bash .claude/hooks/task-orchestrator/scripts/task-complete-commit.sh

# Run feature gate test suite
bash .claude/hooks/task-orchestrator/scripts/feature-complete-gate-test.sh
```

---

## Configuration Options

### Option 1: Project Settings (Recommended)

Create `.claude/settings.local.json` in your project:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [{
          "type": "command",
          "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-orchestrator/scripts/task-complete-commit.sh"
        }]
      },
      {
        "matcher": "mcp__task-orchestrator__update_feature",
        "hooks": [{
          "type": "command",
          "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-orchestrator/scripts/feature-complete-gate.sh",
          "timeout": 300
        }]
      }
    ],
    "SubagentStop": [{
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/task-orchestrator/scripts/subagent-stop-logger.sh"
      }]
    }]
  }
}
```

### Option 2: User Settings (Global)

Add to Claude Code user settings:

**Windows**: `%APPDATA%\Claude\config\settings.json`
**macOS**: `~/Library/Application Support/Claude/config/settings.json`
**Linux**: `~/.config/Claude/config/settings.json`

### Option 3: Environment-Specific

Use different configurations per environment:
- `.claude/settings.development.json`
- `.claude/settings.production.json`

---

## Requirements

### System Requirements

- **bash**: Unix shell (Linux, macOS, Git Bash/WSL on Windows)
- **jq**: JSON processor - https://jqlang.github.io/jq/
- **sqlite3**: For database queries (usually pre-installed)

### Project Requirements

- **./gradlew**: Gradle wrapper (for feature-complete-gate.sh)
- **CLAUDE_PROJECT_DIR**: Environment variable (set automatically by Claude Code)

---

## Hook Development

### Creating a New Hook

1. **Use the Hook Builder Skill**:
   ```
   "Help me create a hook that [describes what you want]"
   ```

2. **Or copy a template** from `src/main/resources/skills/hook-builder/hook-templates.md`

3. **Make it executable**: `chmod +x your-hook.sh`

4. **Test thoroughly**: Create test script following `feature-complete-gate-test.sh` pattern

5. **Document it**: Update this README

### Best Practices

✅ **Use defensive checks** - Validate all conditions before acting
✅ **Handle errors gracefully** - Don't break Claude's workflow
✅ **Provide clear feedback** - Descriptive success/failure messages
✅ **Keep hooks fast** - Long-running hooks slow down Claude (use timeouts)
✅ **Test thoroughly** - Create comprehensive test scripts
✅ **Document well** - Future you will thank present you

### Exit Codes

- **0**: Success (allow operation)
- **1**: Error (unexpected failure, allow operation for safety)
- **2**: Block operation (quality gate failed)

### Blocking Operations

To block an operation, return JSON with `"decision": "block"`:

```bash
cat << EOF
{
  "decision": "block",
  "reason": "Detailed explanation of why operation was blocked and what to fix"
}
EOF
exit 2
```

---

## Debugging

### Enable Bash Debugging

```bash
# Add to top of script
set -x  # Print commands as they execute
```

### Test with Sample Input

```bash
echo '{"tool_input": {"id": "test"}}' | \
  CLAUDE_PROJECT_DIR=. bash .claude/hooks/task-orchestrator/scripts/your-hook.sh
```

### Common Issues

**Issue**: Hook doesn't execute
**Solutions**:
- Check file permissions: `chmod +x hook.sh`
- Verify shebang: `#!/bin/bash`
- Check configuration matcher

**Issue**: "jq not found"
**Solution**: Install jq: `apt install jq` or `brew install jq`

**Issue**: "gradlew not found"
**Solution**: Ensure hook runs from project root with `cd "$CLAUDE_PROJECT_DIR"`

**Issue**: Hook times out
**Solution**: Increase timeout in configuration or optimize hook performance

---

## More Resources

- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Quick start guide
- **[USAGE_EXAMPLES.md](USAGE_EXAMPLES.md)** - Detailed usage scenarios
- **[Hook Builder Skill](../../src/main/resources/skills/hook-builder/)** - Interactive hook creation
- **[Hook Templates](../../src/main/resources/skills/hook-builder/hook-templates.md)** - 11 copy-paste templates

---

## Contributing

When adding new hooks:

1. Create the hook script in `scripts/`
2. Create test script (if applicable)
3. Create example configuration in `templates/`
4. Update this README with usage instructions
5. Run tests to verify functionality

---

## License

These hooks are part of the MCP Task Orchestrator project and follow the same license.
