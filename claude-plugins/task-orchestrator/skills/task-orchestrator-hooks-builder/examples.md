# Task Orchestrator Hooks - Working Examples

This document provides complete working examples of hooks that integrate with Task Orchestrator's workflow cascade event system. All examples follow the **opinionated approach** (auto-apply by default) with clear guidance on when to use conservative alternatives.

## Overview

Three example hooks are provided:

1. **cascade-auto-progress.sh** - Opinionated auto-apply with audit trail (RECOMMENDED)
2. **flow-aware-gate.sh** - Adaptive quality gates based on workflow flow
3. **cascade-logger.sh** - Non-blocking analytics for learning patterns (SAFE for production)

All examples are located in: `src/main/resources/claude/skills/task-orchestrator-hooks-builder/example-hooks/`

## Quick Start

### Installation Steps

1. **Copy hook to your project's hooks directory**:
   ```bash
   # Create hooks directory if it doesn't exist
   mkdir -p .claude/hooks

   # Copy desired example hook
   cp src/main/resources/claude/skills/task-orchestrator-hooks-builder/example-hooks/cascade-auto-progress.sh \
      .claude/hooks/cascade-auto-progress.sh

   # Make executable
   chmod +x .claude/hooks/cascade-auto-progress.sh
   ```

2. **Register hook in Claude Code settings**:
   Edit `.claude/settings.local.json` (create if doesn't exist):
   ```json
   {
     "hooks": {
       "postToolUse": [
         {
           "hook": ".claude/hooks/cascade-auto-progress.sh",
           "tools": ["mcp__task-orchestrator__manage_container"]
         }
       ]
     }
   }
   ```

3. **Test the hook** (see Testing section below)

---

## Example 1: Cascade Auto-Progress (Opinionated)

### Purpose

Automatically logs cascade events for auto-application when `automatic=true`. This is the **RECOMMENDED opinionated approach** for most projects.

### Use Cases

✅ **Use this when:**
- Normal development workflows
- You trust the Task Orchestrator's cascade event logic
- You want automatic progression (Task complete → Feature progresses → Project progresses)
- You need an audit trail of all cascade events

⚠️ **Consider alternatives when:**
- Critical production deployments requiring manual approval
- Security/compliance workflows needing sign-off
- Learning/training environments where you want to see every step
- You need full control over every status transition

### Installation

```bash
# Copy to hooks directory
cp src/main/resources/claude/skills/task-orchestrator-hooks-builder/example-hooks/cascade-auto-progress.sh \
   .claude/hooks/cascade-auto-progress.sh

# Make executable
chmod +x .claude/hooks/cascade-auto-progress.sh
```

**Register in `.claude/settings.local.json`:**
```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-auto-progress.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

### Expected Behavior

**When a cascade event occurs (e.g., all tasks in a feature complete):**

```
════════════════════════════════════════════
🔄 CASCADE EVENT PROCESSING (Opinionated Mode)
════════════════════════════════════════════

Event #1: all_tasks_complete
  Target: User Authentication (feature)
  Status: in-development → testing
  Flow: default_flow
  Reason: All tasks completed, ready for testing phase
  ✅ AUTO-APPLY: Logging for orchestrator to apply status
     Cascade event logged - orchestrator will apply status automatically

════════════════════════════════════════════
✓ Cascade event processing complete
  Log: .claude/metrics/cascade-events.csv
════════════════════════════════════════════
```

**Audit Trail (`~/.claude/metrics/cascade-events.csv`):**
```csv
2025-10-27T14:30:45Z,all_tasks_complete,feature,abc-123-def,"User Authentication",in-development,testing,default_flow,auto
2025-10-27T14:32:10Z,first_task_started,feature,abc-123-def,"User Authentication",planning,in-development,default_flow,auto
```

### Sample Test Input

You can test this hook by simulating a manage_container response with cascade events:

```bash
# Create test input file
cat > test-cascade-input.json << 'EOF'
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
          "reason": "All tasks completed, ready for testing phase"
        }
      ]
    }
  }
}
EOF

# Test the hook
cat test-cascade-input.json | .claude/hooks/cascade-auto-progress.sh
```

### Customization

**To make MORE conservative (require manual confirmation):**

Change line 52 from:
```bash
if [ "$AUTOMATIC" == "true" ]; then
```

To:
```bash
if [ "$AUTOMATIC" == "true" ] && [ "$FLOW" != "with_review_flow" ]; then
```

This will require manual confirmation for security/compliance workflows.

**To disable auto-apply entirely:**

Use the Progressive Disclosure example instead (see Template 16 in hook-templates.md).

---

## Example 2: Flow-Aware Quality Gate

### Purpose

Adapts quality gate behavior based on workflow flow. Skips tests for prototypes, enforces strict validation for security features, runs standard tests for normal development.

### Use Cases

✅ **Use this when:**
- You have different types of features (prototypes, production, security)
- You want fast iteration on experimental work
- You need strict validation for compliance features
- You tag your features consistently (prototype, security, etc.)

⚠️ **Consider alternatives when:**
- All features should have the same quality standards
- You don't use feature tags for workflow classification
- You prefer consistent behavior across all work

### Installation

```bash
# Copy to hooks directory
cp src/main/resources/claude/skills/task-orchestrator-hooks-builder/example-hooks/flow-aware-gate.sh \
   .claude/hooks/flow-aware-gate.sh

# Make executable
chmod +x .claude/hooks/flow-aware-gate.sh
```

**Register in `.claude/settings.local.json`:**
```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/flow-aware-gate.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

### Expected Behavior

**For a feature tagged with "prototype":**

```
════════════════════════════════════════════
🔍 FLOW-AWARE QUALITY GATE
════════════════════════════════════════════
  Detected Flow: rapid_prototype_flow
  Feature Tags: prototype, spike, frontend
  Target Status: testing

⚡ RAPID PROTOTYPE FLOW
   Skipping all quality gates for fast iteration
   Rationale: Prototypes prioritize speed over validation

   ✅ Quality gate skipped (by design)
════════════════════════════════════════════
```

**For a feature tagged with "security":**

```
════════════════════════════════════════════
🔍 FLOW-AWARE QUALITY GATE
════════════════════════════════════════════
  Detected Flow: with_review_flow
  Feature Tags: security, authentication, api
  Target Status: testing

🔒 SECURITY/COMPLIANCE FLOW
   Enforcing strict quality gates

   [1/2] Running full test suite...
       ✅ Tests passed (235 tests, 0 failures)
   [2/2] Running security vulnerability scan...
       ✅ Security scan passed (0 vulnerabilities)

   ✅ All strict quality gates passed
════════════════════════════════════════════
```

**For a normal feature (default flow):**

```
════════════════════════════════════════════
🔍 FLOW-AWARE QUALITY GATE
════════════════════════════════════════════
  Detected Flow: default_flow
  Feature Tags: backend, api, enhancement
  Target Status: testing

✓ DEFAULT FLOW
   Running standard quality gates

   [1/1] Running standard test suite...
       ✅ Tests passed (187 tests, 0 failures)

   ✅ Standard quality gates passed
════════════════════════════════════════════
```

### Flow Detection

The hook detects workflow flow from feature tags:

| Flow | Detected Tags | Behavior |
|------|--------------|----------|
| **rapid_prototype_flow** | prototype, spike, experiment | Skip all tests |
| **with_review_flow** | security, compliance, audit | Strict validation + security scan |
| **default_flow** | (anything else) | Standard test suite |

### Sample Test Input

```bash
# Create test input for security feature
cat > test-flow-gate-input.json << 'EOF'
{
  "tool": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "feature",
    "id": "feature-uuid-789",
    "status": "testing"
  },
  "tool_output": {
    "success": true,
    "data": {
      "id": "feature-uuid-789",
      "tags": "security,authentication,api"
    }
  }
}
EOF

# Test the hook (will attempt to run gradlew test)
cat test-flow-gate-input.json | .claude/hooks/flow-aware-gate.sh
```

### Customization

**To add a new flow (e.g., "experimental_flow"):**

Add to flow detection (around line 42):
```bash
if echo "$TAGS" | grep -qE "prototype|spike|experiment"; then
  FLOW="rapid_prototype_flow"
elif echo "$TAGS" | grep -qE "experimental|research"; then
  FLOW="experimental_flow"
elif echo "$TAGS" | grep -qE "security|compliance|audit"; then
  FLOW="with_review_flow"
fi
```

Then add case handling (around line 58):
```bash
case "$FLOW" in
  "experimental_flow")
    echo "🔬 EXPERIMENTAL FLOW"
    echo "   Running fast tests only, skipping integration tests"
    ./gradlew test -x integrationTest || {
      # Block on failure
    }
    ;;
```

**To make prototype flow less permissive:**

Change line 60-67 to run minimal tests instead of skipping entirely:
```bash
"rapid_prototype_flow")
  echo "⚡ RAPID PROTOTYPE FLOW"
  echo "   Running smoke tests only (skipping full suite)"
  ./gradlew smokeTest || {
    cat << EOF
{
  "decision": "block",
  "reason": "Even prototypes must pass smoke tests. Fix failing tests before proceeding."
}
EOF
    exit 0
  }
  ;;
```

---

## Example 3: Cascade Logger (Analytics)

### Purpose

Non-blocking analytics hook that logs all cascade events to CSV for understanding workflow patterns. **SAFE to add to production** - observation only, never blocks or modifies behavior.

### Use Cases

✅ **Use this when:**
- Learning how cascade events work
- Analyzing workflow patterns in your project
- Collecting metrics on automation effectiveness
- Need audit trail without affecting behavior
- Want to understand which events are automatic vs manual

✅ **Safe for:**
- Production environments (observation only)
- Learning environments
- Any project (zero risk)

### Installation

```bash
# Copy to hooks directory
cp src/main/resources/claude/skills/task-orchestrator-hooks-builder/example-hooks/cascade-logger.sh \
   .claude/hooks/cascade-logger.sh

# Make executable
chmod +x .claude/hooks/cascade-logger.sh
```

**Register in `.claude/settings.local.json`:**
```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

### Expected Behavior

**When cascade events occur:**

```
📊 Cascade Event Logger - Recording events for analytics
   ✓ Event #1: all_tasks_complete (feature) - auto=true, flow=default_flow
   ✓ Event #2: first_task_started (feature) - auto=true, flow=rapid_prototype_flow

📈 Summary Statistics:
   Total Events: 47
   Auto-Apply: 42 (89%)
   Manual Confirm: 5

   By Event Type:
     first_task_started: 15
     all_tasks_complete: 28
     all_features_complete: 4

   By Workflow Flow:
     default_flow: 32
     rapid_prototype_flow: 10
     with_review_flow: 5

✓ Cascade events logged successfully
  CSV Log: .claude/metrics/cascade-events.csv
  Summary: .claude/metrics/cascade-summary.json

  Analytics Queries:
    tail -10 .claude/metrics/cascade-events.csv  # Recent events
    cat .claude/metrics/cascade-summary.json     # Summary stats
```

### CSV Output Format

**File**: `.claude/metrics/cascade-events.csv`

```csv
timestamp,event,target_type,target_id,target_name,current_status,suggested_status,flow,automatic,reason
2025-10-27T14:30:45Z,all_tasks_complete,feature,abc-123,"User Auth",in-development,testing,default_flow,true,"All tasks completed"
2025-10-27T14:32:10Z,first_task_started,feature,abc-123,"User Auth",planning,in-development,default_flow,true,"First task started"
```

### JSON Summary Output

**File**: `.claude/metrics/cascade-summary.json`

```json
{
  "total_cascade_events": 47,
  "automatic_events": 42,
  "manual_events": 5,
  "by_event_type": {
    "first_task_started": 15,
    "all_tasks_complete": 28,
    "all_features_complete": 4
  },
  "by_flow": {
    "default_flow": 32,
    "rapid_prototype_flow": 10,
    "with_review_flow": 5
  },
  "auto_percentage": 89,
  "last_updated": "2025-10-27T14:35:22Z"
}
```

### Analytics Queries

**View recent events:**
```bash
tail -10 .claude/metrics/cascade-events.csv
```

**Count auto vs manual:**
```bash
tail -n +2 .claude/metrics/cascade-events.csv | grep ",true," | wc -l   # Auto
tail -n +2 .claude/metrics/cascade-events.csv | grep ",false," | wc -l  # Manual
```

**Find all security flow events:**
```bash
tail -n +2 .claude/metrics/cascade-events.csv | grep ",with_review_flow,"
```

**View summary statistics:**
```bash
cat .claude/metrics/cascade-summary.json | jq .
```

### Sample Test Input

```bash
# Create test input with multiple cascade events
cat > test-logger-input.json << 'EOF'
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
          "targetName": "API Authentication",
          "currentStatus": "in-development",
          "suggestedStatus": "testing",
          "flow": "default_flow",
          "automatic": true,
          "reason": "All 8 tasks completed successfully"
        },
        {
          "event": "all_features_complete",
          "targetType": "project",
          "targetId": "project-uuid-789",
          "targetName": "User Management System",
          "currentStatus": "in-progress",
          "suggestedStatus": "completed",
          "flow": "with_review_flow",
          "automatic": false,
          "reason": "All features complete, requires final approval"
        }
      ]
    }
  }
}
EOF

# Test the hook
cat test-logger-input.json | .claude/hooks/cascade-logger.sh
```

### Customization

This hook is safe as-is, but you can customize:

**Change CSV location:**
```bash
# Line 28 - change log directory
LOG_DIR="$CLAUDE_PROJECT_DIR/metrics"  # Instead of .claude/metrics
```

**Add email alerts for manual confirmations:**
```bash
# After line 73, add:
if [ "$AUTOMATIC" == "false" ]; then
  echo "Manual confirmation required for $TARGET_NAME" | \
    mail -s "Task Orchestrator: Manual Approval Needed" admin@example.com
fi
```

**Export to external analytics:**
```bash
# After line 116, add:
curl -X POST https://analytics.example.com/api/cascade-events \
  -H "Content-Type: application/json" \
  -d @"$SUMMARY_LOG"
```

---

## Testing Your Hooks

### Testing with Real Operations

1. **Create a test task and complete it:**
   ```bash
   # Your hooks will trigger on real manage_container operations
   # Watch for hook output in Claude Code
   ```

2. **Check logs:**
   ```bash
   ls -la .claude/metrics/
   cat .claude/metrics/cascade-events.csv
   cat .claude/metrics/cascade-summary.json
   ```

### Testing with Simulated Input

Create a test script to simulate cascade events:

```bash
#!/bin/bash
# test-cascade-hooks.sh - Simulate cascade events for testing

cat << 'EOF' | .claude/hooks/cascade-auto-progress.sh
{
  "tool": "mcp__task-orchestrator__manage_container",
  "tool_input": {
    "operation": "setStatus",
    "containerType": "task",
    "id": "test-task-123",
    "status": "completed"
  },
  "tool_output": {
    "success": true,
    "data": {
      "cascadeEvents": [
        {
          "event": "all_tasks_complete",
          "targetType": "feature",
          "targetId": "test-feature-456",
          "targetName": "Test Feature",
          "currentStatus": "in-development",
          "suggestedStatus": "testing",
          "flow": "default_flow",
          "automatic": true,
          "reason": "All tasks completed for testing"
        }
      ]
    }
  }
}
EOF

echo ""
echo "✓ Test completed. Check output above."
```

### Debugging Hooks

**Enable verbose output:**
```bash
# Add to top of hook (after #!/bin/bash)
set -x  # Print each command
```

**Check hook execution:**
```bash
# Claude Code logs hook execution
# Look for "Running hook: .claude/hooks/..."
```

**Test JSON parsing:**
```bash
# Verify jq is working
echo '{"test": "value"}' | jq -r '.test'
# Should output: value
```

---

## Combining Multiple Hooks

You can use multiple hooks together. They run in the order listed in `settings.local.json`:

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

**Recommended Order:**
1. **Logger** (observation only, always safe)
2. **Quality gates** (may block if tests fail)
3. **Auto-progress** (applies status changes)

---

## Migration from Conservative to Opinionated

### Starting Conservative

If you're new to cascade events, start with the logger:

```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

**Goal**: Understand cascade event patterns in your project.

**When to progress**: After 1-2 weeks, review `.claude/metrics/cascade-summary.json`. If auto_percentage > 80%, you're ready for opinionated approach.

### Adopting Opinionated Approach

Add cascade-auto-progress.sh:

```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      },
      {
        "hook": ".claude/hooks/cascade-auto-progress.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

**Goal**: Automatic progression with full audit trail.

**When to progress**: Once comfortable with automatic behavior, consider flow-aware gates.

### Adding Flow-Aware Gates

Enable adaptive quality gates:

```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      },
      {
        "hook": ".claude/hooks/flow-aware-gate.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      },
      {
        "hook": ".claude/hooks/cascade-auto-progress.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

**Goal**: Prototype features skip tests, security features enforce strict validation.

**Requirement**: Consistent feature tagging (prototype, security, etc.).

---

## Troubleshooting

### Hook Not Executing

**Check registration:**
```bash
cat .claude/settings.local.json
# Verify hook is listed under postToolUse
```

**Check executable permission:**
```bash
ls -la .claude/hooks/
# Should show -rwxr-xr-x (executable)
```

**Check tool name:**
```json
{
  "tools": ["mcp__task-orchestrator__manage_container"]
  // Note: Double underscores, correct tool name
}
```

### Cascade Events Not Appearing

**Verify operation triggers cascade:**
```bash
# Cascade events only occur on certain operations:
# - Completing a task (when all tasks in feature complete)
# - Starting first task in a feature
# - Completing all features in a project
```

**Check feature/project has children:**
```bash
# Feature must have tasks
# Project must have features
# Otherwise no cascade occurs
```

### JSON Parsing Errors

**Install jq if missing:**
```bash
# Ubuntu/Debian
sudo apt-get install jq

# macOS
brew install jq

# Windows (via scoop)
scoop install jq
```

**Test jq installation:**
```bash
echo '{"test": "value"}' | jq -r '.test'
# Should output: value
```

### Gradlew Not Found (flow-aware-gate.sh)

**Verify project structure:**
```bash
ls -la ./gradlew
# Should exist and be executable
```

**Adjust path in hook:**
```bash
# Line 76, 120, 146 - change to absolute path
cd "$CLAUDE_PROJECT_DIR"
./gradlew test
```

---

## Best Practices

### General Guidelines

1. **Start with logger** - Always begin with cascade-logger.sh to learn patterns
2. **Test in isolation** - Test each hook individually before combining
3. **Keep hooks simple** - Each hook should do one thing well
4. **Exit cleanly** - Always exit 0 unless blocking (exit 1 requires JSON)
5. **Log everything** - Create audit trails for debugging

### Security Considerations

1. **Review hook source** - Always review hook code before installation
2. **Limit permissions** - Hooks run with user permissions, no sudo required
3. **Validate input** - Check for null/empty values from JSON
4. **Handle errors** - Use `|| true` for non-critical operations
5. **Don't store secrets** - Never hardcode credentials in hooks

### Performance Tips

1. **Use lightweight operations** - Hooks add overhead to every operation
2. **Filter early** - Exit early if operation doesn't match
3. **Batch operations** - Update metrics once, not per event
4. **Avoid network calls** - Local operations are faster
5. **Cache database queries** - SQLite queries can be slow for large databases

---

## Additional Resources

### Hook Templates

See `hook-templates.md` for 16 copy-paste templates covering:
- Templates 1-11: Core hook patterns (git, testing, metrics, notifications)
- Templates 12-16: Cascade event integration (NEW in v2.0)

### Documentation

- **SKILL.md** - Complete hook builder skill with interactive interview
- **docs/hooks-guide.md** - Comprehensive hook system documentation
- **WorkflowConfig.kt** - Source code for cascade event system

### Getting Help

1. Use the Task Orchestrator Hooks Builder skill in Claude Code
2. Read the interactive interview questions (handles 95% of use cases)
3. Review these examples for working patterns
4. Check `docs/hooks-guide.md` for advanced scenarios

---

## Summary

**Recommended Setup** (Most projects):
```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      },
      {
        "hook": ".claude/hooks/cascade-auto-progress.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

**Full Setup** (With flow-aware quality gates):
```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      },
      {
        "hook": ".claude/hooks/flow-aware-gate.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      },
      {
        "hook": ".claude/hooks/cascade-auto-progress.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

**Conservative Setup** (Learning mode):
```json
{
  "hooks": {
    "postToolUse": [
      {
        "hook": ".claude/hooks/cascade-logger.sh",
        "tools": ["mcp__task-orchestrator__manage_container"]
      }
    ]
  }
}
```

All examples follow the **opinionated approach** - auto-apply by default with clear guidance on when to use conservative alternatives. Start with the logger to learn, then adopt auto-progress when comfortable.
