#!/bin/bash
# Flow-aware quality gate - different validation per workflow flow
# OPINIONATED: Skips tests for prototypes, enforces for production (default behavior)
#
# This example hook demonstrates the recommended opinionated approach:
# - rapid_prototype_flow: SKIP tests (fast iteration)
# - with_review_flow: STRICT validation (security/compliance)
# - default_flow: STANDARD tests (normal development)
#
# Customize flow detection and gates based on your project needs

INPUT=$(cat)

# Extract operation and container type
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation')
CONTAINER_TYPE=$(echo "$INPUT" | jq -r '.tool_input.containerType')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status')

# Only run for feature status changes to testing/completed
if [ "$OPERATION" != "setStatus" ] || [ "$CONTAINER_TYPE" != "feature" ]; then
  exit 0
fi

if [ "$STATUS" != "testing" ] && [ "$STATUS" != "completed" ]; then
  exit 0
fi

# Extract feature ID
FEATURE_ID=$(echo "$INPUT" | jq -r '.tool_input.id')

# Query feature tags to determine active flow
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TAGS=$(sqlite3 "$DB_PATH" \
  "SELECT tags FROM Features WHERE id='$FEATURE_ID'" 2>/dev/null)

if [ -z "$TAGS" ]; then
  echo "⚠️  Could not determine feature tags, using default_flow"
  TAGS=""
fi

# Determine active flow from tags
FLOW="default_flow"
if echo "$TAGS" | grep -qE "prototype|spike|experiment"; then
  FLOW="rapid_prototype_flow"
elif echo "$TAGS" | grep -qE "security|compliance|audit"; then
  FLOW="with_review_flow"
fi

echo "════════════════════════════════════════════"
echo "🔍 FLOW-AWARE QUALITY GATE"
echo "════════════════════════════════════════════"
echo "  Detected Flow: $FLOW"
echo "  Feature Tags: $TAGS"
echo "  Target Status: $STATUS"
echo ""

# Flow-specific quality gates
case "$FLOW" in
  "rapid_prototype_flow")
    # OPINIONATED: Skip tests for prototypes (fast iteration)
    echo "⚡ RAPID PROTOTYPE FLOW"
    echo "   Skipping all quality gates for fast iteration"
    echo "   Rationale: Prototypes prioritize speed over validation"
    echo ""
    echo "   ✅ Quality gate skipped (by design)"
    echo "════════════════════════════════════════════"
    exit 0
    ;;

  "with_review_flow")
    # OPINIONATED: Strict validation for security/compliance
    echo "🔒 SECURITY/COMPLIANCE FLOW"
    echo "   Enforcing strict quality gates"
    echo ""

    cd "$CLAUDE_PROJECT_DIR"

    # Gate 1: Full test suite
    echo "   [1/2] Running full test suite..."
    if ! ./gradlew test; then
      cat << EOF
{
  "decision": "block",
  "reason": "Security/compliance flow requires all tests to pass. Please fix failing tests before completing feature."
}
EOF
      exit 0
    fi
    echo "       ✅ Tests passed"

    # Gate 2: Security scan (if trivy available)
    if command -v trivy >/dev/null 2>&1; then
      echo "   [2/2] Running security vulnerability scan..."
      if ! trivy fs .; then
        cat << EOF
{
  "decision": "block",
  "reason": "Security vulnerabilities detected. Please remediate before completing feature."
}
EOF
        exit 0
      fi
      echo "       ✅ Security scan passed"
    else
      echo "   [2/2] Security scan tool (trivy) not installed, skipping"
      echo "       ⚠️  Install trivy for security validation"
    fi

    echo ""
    echo "   ✅ All strict quality gates passed"
    echo "════════════════════════════════════════════"
    ;;

  "default_flow")
    # OPINIONATED: Standard test suite for default flow
    echo "✓ DEFAULT FLOW"
    echo "   Running standard quality gates"
    echo ""

    cd "$CLAUDE_PROJECT_DIR"

    echo "   [1/1] Running standard test suite..."
    if ! ./gradlew test; then
      cat << EOF
{
  "decision": "block",
  "reason": "Tests are failing. Please fix failing tests before completing feature."
}
EOF
      exit 0
    fi
    echo "       ✅ Tests passed"

    echo ""
    echo "   ✅ Standard quality gates passed"
    echo "════════════════════════════════════════════"
    ;;

  *)
    # Unknown flow, use default behavior
    echo "⚠️  UNKNOWN FLOW: $FLOW"
    echo "   Falling back to default flow behavior"
    echo ""

    cd "$CLAUDE_PROJECT_DIR"
    if ! ./gradlew test; then
      cat << EOF
{
  "decision": "block",
  "reason": "Tests are failing. Please fix failing tests before completing feature."
}
EOF
      exit 0
    fi

    echo "   ✅ Tests passed"
    echo "════════════════════════════════════════════"
    ;;
esac

exit 0
