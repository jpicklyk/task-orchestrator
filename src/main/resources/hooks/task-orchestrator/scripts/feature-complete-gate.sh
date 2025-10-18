#!/bin/bash
# Feature Complete Gate Hook
#
# This hook acts as a quality gate when features are marked complete.
# It runs project tests and blocks feature completion if tests fail.
#
# Listens for: mcp__task-orchestrator__update_feature PostToolUse events
# Condition: status is being set to "completed"
# Action: Runs ./gradlew test to verify all tests pass
# Blocks: If tests fail, blocks operation with exit code 2 and helpful error message
# Allows: If tests pass, allows operation to proceed with success message

# Enable strict error handling
set -euo pipefail

# Error handler function
error_handler() {
  echo "Error on line $1"
  exit 1
}

trap 'error_handler $LINENO' ERR

# Read JSON input from stdin
INPUT=$(cat)

# Check for required environment variable
if [ -z "${CLAUDE_PROJECT_DIR:-}" ]; then
  echo "Warning: CLAUDE_PROJECT_DIR not set, skipping quality gate"
  exit 0
fi

# Check for required tool (jq)
if ! command -v jq >/dev/null 2>&1; then
  echo "Warning: jq is required but not installed, skipping quality gate"
  exit 0
fi

# Extract status field from tool input
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')

# Defensive check - only proceed if status is being set to "completed"
if [ "$STATUS" != "completed" ]; then
  # Status is not being set to completed, allow operation
  exit 0
fi

# Extract feature ID for logging purposes
FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_input.id // "unknown"')

echo "=========================================="
echo "Feature Complete Quality Gate"
echo "=========================================="
echo "Feature ID: $FEATURE_ID"
echo "Status: $STATUS"
echo "Running project tests..."
echo "=========================================="

# Change to project directory
cd "$CLAUDE_PROJECT_DIR"

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
  echo "Warning: gradlew not found in project directory, skipping tests"
  exit 0
fi

# Run project tests
# We capture both stdout and stderr, and the exit code
TEST_OUTPUT=$(./gradlew test 2>&1) || TEST_EXIT_CODE=$?

# Check if tests passed
if [ "${TEST_EXIT_CODE:-0}" -ne 0 ]; then
  # Tests failed - block the operation
  echo "=========================================="
  echo "TESTS FAILED - BLOCKING FEATURE COMPLETION"
  echo "=========================================="
  echo "$TEST_OUTPUT"
  echo "=========================================="

  # Return blocking decision as JSON
  cat << EOF
{
  "decision": "block",
  "reason": "Cannot mark feature as completed - project tests are failing. Please fix failing tests before marking feature complete.\n\nTest output:\n$TEST_OUTPUT"
}
EOF
  exit 2
fi

# Tests passed - allow operation
echo "=========================================="
echo "✓ All tests passed"
echo "=========================================="
echo "Feature completion allowed"

exit 0
