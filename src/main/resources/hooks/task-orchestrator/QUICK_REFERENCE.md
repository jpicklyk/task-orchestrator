# Feature Complete Gate Hook - Quick Reference

## 🎯 What It Does

Blocks feature completion if project tests fail. Ensures quality by running `./gradlew test` before allowing status="completed".

## ⚡ Quick Install

Add to Claude Code settings:

```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__update_feature",
      "hooks": [{
        "type": "command",
        "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/feature-complete-gate.sh",
        "timeout": 300
      }]
    }]
  }
}
```

**Settings Location**:
- Windows: `%APPDATA%\Claude\config\settings.json`
- macOS: `~/Library/Application Support/Claude/config/settings.json`
- Linux: `~/.config/Claude/config/settings.json`

## ✅ Quick Test

```bash
# Run test suite
bash .claude/hooks/feature-complete-gate-test.sh

# Expected: All 6 tests pass
```

## 📋 Behavior

| Status Change | Hook Action | Test Runs? | Can Complete? |
|--------------|-------------|------------|---------------|
| → planning | Allows immediately | ❌ No | ✅ Yes |
| → in-development | Allows immediately | ❌ No | ✅ Yes |
| → completed (tests pass) | Runs tests | ✅ Yes | ✅ Yes |
| → completed (tests fail) | Runs tests | ✅ Yes | ❌ **BLOCKED** |

## 🔧 Customize Test Command

Edit line 75 in `feature-complete-gate.sh`:

```bash
# Gradle (default)
TEST_OUTPUT=$(./gradlew test 2>&1) || TEST_EXIT_CODE=$?

# Maven
TEST_OUTPUT=$(./mvnw test 2>&1) || TEST_EXIT_CODE=$?

# NPM
TEST_OUTPUT=$(npm test 2>&1) || TEST_EXIT_CODE=$?
```

## 🐛 Troubleshooting

| Problem | Solution |
|---------|----------|
| "jq not found" | Install: `apt install jq` or `brew install jq` |
| Hook doesn't run | Check settings.json matcher is exact |
| Always allows | Verify `./gradlew test` actually fails on broken tests |
| Times out | Increase timeout in configuration (default: 300s) |

## 📁 Files

- `feature-complete-gate.sh` - Main hook (73 lines)
- `feature-complete-gate-test.sh` - Test suite (6 tests)
- `feature-complete-gate.config.example.json` - Config template
- `README.md` - Full documentation
- `USAGE_EXAMPLES.md` - Detailed examples
- `QUICK_REFERENCE.md` - This file

## 🚀 Usage Flow

```
User: "Complete feature ABC"
         ↓
Claude: update_feature(id="ABC", status="completed")
         ↓
Hook: Detect status="completed" → Run ./gradlew test
         ↓
   ├─ Tests Pass ✓ → Exit 0 → Feature marked complete
   └─ Tests Fail ✗ → Exit 2 + block JSON → Feature stays incomplete
```

## ⚙️ Requirements

- ✅ bash 3.0+
- ✅ jq (JSON processor)
- ✅ ./gradlew (or customize for your build tool)
- ✅ Claude Code with hooks support

## 📖 More Info

- Full docs: [README.md](README.md)
- Examples: [USAGE_EXAMPLES.md](USAGE_EXAMPLES.md)
- Templates: [hook-templates.md](../../src/main/resources/skills/hook-builder/hook-templates.md)
