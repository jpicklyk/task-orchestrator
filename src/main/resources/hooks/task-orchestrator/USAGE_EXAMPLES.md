# Feature Complete Gate Hook - Usage Examples

## Quick Start

### 1. Installation

Add to your Claude Code settings file:

**Windows**: `%APPDATA%\Claude\config\settings.json`
**macOS/Linux**: `~/.config/Claude/config/settings.json`

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__update_feature",
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

### 2. Verify Installation

```bash
# Test the hook with a non-completion status change (should allow)
cd /path/to/task-orchestrator
echo '{"tool_input": {"id": "test-uuid", "status": "in-development"}}' | \
  CLAUDE_PROJECT_DIR=$(pwd) bash .claude/hooks/feature-complete-gate.sh

# Expected output: (exits immediately with code 0)
```

### 3. Run Full Test Suite

```bash
cd /path/to/task-orchestrator
bash .claude/hooks/feature-complete-gate-test.sh

# Expected output:
# ==========================================
# Feature Complete Gate Hook Tests
# ==========================================
# ...
# All tests passed!
```

## Example Scenarios

### Scenario 1: Normal Feature Development (Hook Inactive)

```
User: "Update feature ABC123 to in-development status"

Claude: update_feature(id="ABC123", status="in-development")
         ↓
Hook: Checks status != "completed" → Exits immediately (0)
         ↓
Result: Feature status updated to "in-development" ✓
```

**Hook Behavior**: Does nothing - feature not being completed

### Scenario 2: Feature Completion with Passing Tests (Hook Allows)

```
User: "Mark feature ABC123 as complete"

Claude: update_feature(id="ABC123", status="completed")
         ↓
Hook: Detects status="completed"
      → Runs ./gradlew test
      → All tests pass ✓
      → Exits with code 0
         ↓
Result: Feature status updated to "completed" ✓
```

**Hook Behavior**: Runs tests, all pass, allows completion

**Console Output**:
```
==========================================
Feature Complete Quality Gate
==========================================
Feature ID: ABC123
Status: completed
Running project tests...
==========================================
BUILD SUCCESSFUL in 45s
58 tests completed, 58 passed, 0 failed
==========================================
✓ All tests passed
==========================================
Feature completion allowed
```

### Scenario 3: Feature Completion with Failing Tests (Hook Blocks)

```
User: "Mark feature ABC123 as complete"

Claude: update_feature(id="ABC123", status="completed")
         ↓
Hook: Detects status="completed"
      → Runs ./gradlew test
      → Tests fail ✗
      → Exits with code 2 + block JSON
         ↓
Result: Feature completion BLOCKED ✗
        Claude informs user: "Cannot complete feature - tests failing"
```

**Hook Behavior**: Runs tests, failures detected, blocks completion

**Console Output**:
```
==========================================
Feature Complete Quality Gate
==========================================
Feature ID: ABC123
Status: completed
Running project tests...
==========================================
TESTS FAILED - BLOCKING FEATURE COMPLETION
==========================================
> Task :test FAILED

AuthServiceTest > testLoginWithInvalidPassword FAILED
    Expected: 401 Unauthorized
    Actual: 500 Internal Server Error

12 tests completed, 11 passed, 1 failed
==========================================

{
  "decision": "block",
  "reason": "Cannot mark feature as completed - project tests are failing..."
}
```

**Claude Response to User**:
```
I cannot mark the feature as complete because the project tests are failing.
The quality gate hook blocked this operation.

Failed test: AuthServiceTest.testLoginWithInvalidPassword
Expected: 401 Unauthorized, but got 500 Internal Server Error

Please fix the failing test before marking this feature complete.
```

## Debugging

### Enable Verbose Output

Add debug flag to hook script:

```bash
# Edit feature-complete-gate.sh
# Change first line from:
set -euo pipefail

# To:
set -xeuo pipefail  # -x enables command tracing
```

### Test Manually with Different Inputs

```bash
# Test with completion status
echo '{"tool_input": {"id": "ABC123", "status": "completed"}}' | \
  CLAUDE_PROJECT_DIR=$(pwd) bash .claude/hooks/feature-complete-gate.sh

# Test without status field
echo '{"tool_input": {"id": "ABC123"}}' | \
  CLAUDE_PROJECT_DIR=$(pwd) bash .claude/hooks/feature-complete-gate.sh

# Test with empty JSON
echo '{}' | CLAUDE_PROJECT_DIR=$(pwd) bash .claude/hooks/feature-complete-gate.sh
```

### Check Hook Execution in Claude Code

Look for hook output in Claude Code console:

1. Open Claude Code
2. Go to View → Developer → Console
3. Look for lines containing "Feature Complete Quality Gate"

## Customization

### Change Test Command

Edit `feature-complete-gate.sh` and replace:

```bash
# Default
TEST_OUTPUT=$(./gradlew test 2>&1) || TEST_EXIT_CODE=$?
```

With your preferred test command:

```bash
# Maven
TEST_OUTPUT=$(./mvnw test 2>&1) || TEST_EXIT_CODE=$?

# NPM
TEST_OUTPUT=$(npm test 2>&1) || TEST_EXIT_CODE=$?

# Make
TEST_OUTPUT=$(make test 2>&1) || TEST_EXIT_CODE=$?

# Custom script
TEST_OUTPUT=$(./scripts/run-tests.sh 2>&1) || TEST_EXIT_CODE=$?
```

### Adjust Timeout

Edit configuration and change timeout value:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__update_feature",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/feature-complete-gate.sh",
            "timeout": 600  // 10 minutes for large test suites
          }
        ]
      }
    ]
  }
}
```

### Run Additional Checks

Add more validation before allowing completion:

```bash
# Add to feature-complete-gate.sh before "exit 0"

# Check code coverage
COVERAGE=$(./gradlew jacocoTestReport 2>&1 | grep -oP 'Total.*\K[0-9]+(?=%)')
if [ "$COVERAGE" -lt 80 ]; then
  cat << EOF
{
  "decision": "block",
  "reason": "Code coverage is ${COVERAGE}%, must be at least 80%"
}
EOF
  exit 2
fi

# Check for TODOs in code
TODO_COUNT=$(grep -r "TODO" src/ | wc -l)
if [ "$TODO_COUNT" -gt 0 ]; then
  echo "⚠️  Warning: $TODO_COUNT TODOs remaining in code"
fi

# Check documentation
if [ ! -f "docs/feature-$(basename $FEATURE_ID).md" ]; then
  echo "⚠️  Warning: Feature documentation not found"
fi
```

## Troubleshooting

### Hook Not Running

**Symptom**: Features complete without tests running

**Checks**:
1. Verify settings.json contains hook configuration
2. Check matcher is exactly `mcp__task-orchestrator__update_feature`
3. Ensure hook script path is correct
4. Verify hook script is executable: `chmod +x .claude/hooks/feature-complete-gate.sh`

### Hook Always Allows (Never Blocks)

**Symptom**: Hook runs but never blocks even when tests fail

**Checks**:
1. Check exit code handling: `echo $?` after manual test run
2. Verify `./gradlew test` actually fails when tests are broken
3. Check TEST_EXIT_CODE variable is being set correctly
4. Run hook with `-x` flag to see detailed execution

### jq Not Found

**Symptom**: Warning: "jq is required but not installed"

**Solution**:
```bash
# Ubuntu/Debian
sudo apt install jq

# macOS
brew install jq

# Windows (Git Bash)
# Download from https://jqlang.github.io/jq/download/
```

### gradlew Not Found

**Symptom**: Warning: "gradlew not found in project directory"

**Solution**:
- Ensure you're in a Gradle project
- If using Maven, customize hook to use `./mvnw test`
- If using different build system, customize test command

## Integration with CI/CD

This hook works locally in Claude Code. For CI/CD integration:

```yaml
# .github/workflows/quality-gate.yml
name: Quality Gate

on:
  pull_request:
    types: [opened, synchronize]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Run tests
        run: ./gradlew test
      - name: Block merge if tests fail
        if: failure()
        run: |
          echo "::error::Cannot merge - tests failing"
          exit 1
```

This provides similar quality enforcement in your CI/CD pipeline.

## Best Practices

1. **Always run tests locally** before asking Claude to complete features
2. **Keep test suites fast** (< 5 minutes) to avoid timeout issues
3. **Monitor hook output** in Claude Code console for debugging
4. **Customize for your workflow** - add coverage checks, linting, etc.
5. **Document your quality gates** - let team know what criteria must be met
6. **Use in combination with CI/CD** - local hook + pipeline checks = robust quality

## Additional Resources

- [Hook Templates](../../src/main/resources/skills/hook-builder/hook-templates.md)
- [Claude Code Hooks Documentation](https://docs.anthropic.com/claude-code/hooks)
- [Task Orchestrator Documentation](../../docs/)

## Support

For issues or questions:
1. Check test output: `bash .claude/hooks/feature-complete-gate-test.sh`
2. Run hook manually with sample input (see Debugging section)
3. Review Claude Code console for hook execution logs
4. Open an issue in the Task Orchestrator repository
