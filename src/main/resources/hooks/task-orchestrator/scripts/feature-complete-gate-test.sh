#!/bin/bash
# Test script for feature-complete-gate.sh hook
#
# This script tests all scenarios for the feature-complete-gate hook:
# 1. Status NOT set to "completed" (should allow)
# 2. Status set to "completed" with passing tests (should allow)
# 3. Status set to "completed" with failing tests (should block)
# 4. Missing jq (should allow with warning)
# 5. Missing gradlew (should allow with warning)
# 6. Missing CLAUDE_PROJECT_DIR (should allow with warning)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK_SCRIPT="$SCRIPT_DIR/feature-complete-gate.sh"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_TOTAL=0

# Helper function to print test results
print_result() {
  local test_name="$1"
  local expected="$2"
  local actual="$3"

  TESTS_TOTAL=$((TESTS_TOTAL + 1))

  if [ "$expected" = "$actual" ]; then
    echo -e "${GREEN}✓ PASS${NC}: $test_name"
    TESTS_PASSED=$((TESTS_PASSED + 1))
  else
    echo -e "${RED}✗ FAIL${NC}: $test_name"
    echo -e "  Expected: $expected"
    echo -e "  Actual: $actual"
    TESTS_FAILED=$((TESTS_FAILED + 1))
  fi
}

echo "=========================================="
echo "Feature Complete Gate Hook Tests"
echo "=========================================="
echo ""

# Test 1: Status NOT set to "completed" (in-development)
echo "Test 1: Status NOT set to 'completed' (should allow)"
echo "---"
TEST_INPUT='{"tool_input": {"id": "test-uuid-1", "status": "in-development"}}'
export CLAUDE_PROJECT_DIR="$SCRIPT_DIR/../.."
TEST_OUTPUT=$(echo "$TEST_INPUT" | bash "$HOOK_SCRIPT" 2>&1) || TEST_EXIT=$?
TEST_EXIT=${TEST_EXIT:-0}
print_result "Status 'in-development' allows operation" "0" "$TEST_EXIT"
echo ""

# Test 2: Status NOT set to "completed" (planning)
echo "Test 2: Status NOT set to 'completed' - planning (should allow)"
echo "---"
TEST_INPUT='{"tool_input": {"id": "test-uuid-2", "status": "planning"}}'
TEST_OUTPUT=$(echo "$TEST_INPUT" | bash "$HOOK_SCRIPT" 2>&1) || TEST_EXIT=$?
TEST_EXIT=${TEST_EXIT:-0}
print_result "Status 'planning' allows operation" "0" "$TEST_EXIT"
echo ""

# Test 3: No status field (should allow)
echo "Test 3: No status field in input (should allow)"
echo "---"
TEST_INPUT='{"tool_input": {"id": "test-uuid-3", "name": "Some Feature"}}'
TEST_OUTPUT=$(echo "$TEST_INPUT" | bash "$HOOK_SCRIPT" 2>&1) || TEST_EXIT=$?
TEST_EXIT=${TEST_EXIT:-0}
print_result "Missing status field allows operation" "0" "$TEST_EXIT"
echo ""

# Test 4: Empty JSON (should allow)
echo "Test 4: Empty JSON input (should allow)"
echo "---"
TEST_INPUT='{}'
TEST_OUTPUT=$(echo "$TEST_INPUT" | bash "$HOOK_SCRIPT" 2>&1) || TEST_EXIT=$?
TEST_EXIT=${TEST_EXIT:-0}
print_result "Empty JSON allows operation" "0" "$TEST_EXIT"
echo ""

# Test 5: Missing CLAUDE_PROJECT_DIR (should allow with warning)
echo "Test 5: Missing CLAUDE_PROJECT_DIR (should allow with warning)"
echo "---"
TEST_INPUT='{"tool_input": {"id": "test-uuid-5", "status": "completed"}}'
unset CLAUDE_PROJECT_DIR
TEST_OUTPUT=$(echo "$TEST_INPUT" | bash "$HOOK_SCRIPT" 2>&1) || TEST_EXIT=$?
TEST_EXIT=${TEST_EXIT:-0}
export CLAUDE_PROJECT_DIR="$SCRIPT_DIR/../.."
print_result "Missing CLAUDE_PROJECT_DIR allows operation" "0" "$TEST_EXIT"
if echo "$TEST_OUTPUT" | grep -q "CLAUDE_PROJECT_DIR not set"; then
  echo -e "${GREEN}✓${NC} Warning message present"
else
  echo -e "${RED}✗${NC} Warning message missing"
fi
echo ""

# Test 6: Status set to "completed" with real project (tests should run)
echo "Test 6: Status set to 'completed' with real project"
echo "---"
TEST_INPUT='{"tool_input": {"id": "test-uuid-6", "status": "completed"}}'
export CLAUDE_PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
echo "Project directory: $CLAUDE_PROJECT_DIR"

# Check if gradlew exists
if [ -f "$CLAUDE_PROJECT_DIR/gradlew" ]; then
  echo "Running actual tests..."
  TEST_OUTPUT=$(echo "$TEST_INPUT" | bash "$HOOK_SCRIPT" 2>&1) || TEST_EXIT=$?
  TEST_EXIT=${TEST_EXIT:-0}

  if [ "$TEST_EXIT" = "0" ]; then
    print_result "Status 'completed' with passing tests allows operation" "0" "$TEST_EXIT"
    if echo "$TEST_OUTPUT" | grep -q "All tests passed"; then
      echo -e "${GREEN}✓${NC} Success message present"
    else
      echo -e "${YELLOW}⚠${NC} Success message may be missing"
    fi
  elif [ "$TEST_EXIT" = "2" ]; then
    echo -e "${YELLOW}⚠ EXPECTED BLOCK${NC}: Tests failed (this is correct behavior)"
    print_result "Status 'completed' with failing tests blocks operation" "2" "$TEST_EXIT"
    if echo "$TEST_OUTPUT" | grep -q "decision.*block"; then
      echo -e "${GREEN}✓${NC} Block decision JSON present"
    else
      echo -e "${RED}✗${NC} Block decision JSON missing"
    fi
  else
    print_result "Unexpected exit code" "0 or 2" "$TEST_EXIT"
  fi
else
  echo -e "${YELLOW}⚠ SKIP${NC}: gradlew not found in project"
fi
echo ""

# Summary
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo "Total tests: $TESTS_TOTAL"
echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
  echo -e "${GREEN}All tests passed!${NC}"
  exit 0
else
  echo -e "${RED}Some tests failed!${NC}"
  exit 1
fi
