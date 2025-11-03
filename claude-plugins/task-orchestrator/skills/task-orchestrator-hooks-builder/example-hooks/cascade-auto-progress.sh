#!/bin/bash
# Auto-progress workflow when cascade events occur
# OPINIONATED: Auto-applies status changes when automatic=true (default behavior)
#
# This example hook demonstrates the recommended opinionated approach:
# - Automatically logs cascade events when automatic=true
# - Relies on Task Orchestrator orchestration Skills to apply status changes
# - Provides audit trail of all cascade events
#
# For manual confirmation instead, use progressive-disclosure.sh example

INPUT=$(cat)

# Extract cascade events from tool response
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

# Check if any cascade events detected
if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi

echo "════════════════════════════════════════════"
echo "🔄 CASCADE EVENT PROCESSING (Opinionated Mode)"
echo "════════════════════════════════════════════"

# Create metrics directory
LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$LOG_DIR"

# Process each cascade event
EVENT_COUNT=0
echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  EVENT_COUNT=$((EVENT_COUNT + 1))
  EVENT_TYPE=$(echo "$event" | jq -r '.event')
  TARGET_TYPE=$(echo "$event" | jq -r '.targetType')
  TARGET_ID=$(echo "$event" | jq -r '.targetId')
  TARGET_NAME=$(echo "$event" | jq -r '.targetName')
  CURRENT_STATUS=$(echo "$event" | jq -r '.currentStatus')
  SUGGESTED_STATUS=$(echo "$event" | jq -r '.suggestedStatus')
  AUTOMATIC=$(echo "$event" | jq -r '.automatic')
  FLOW=$(echo "$event" | jq -r '.flow')
  REASON=$(echo "$event" | jq -r '.reason')

  echo ""
  echo "Event #$EVENT_COUNT: $EVENT_TYPE"
  echo "  Target: $TARGET_NAME ($TARGET_TYPE)"
  echo "  Status: $CURRENT_STATUS → $SUGGESTED_STATUS"
  echo "  Flow: $FLOW"
  echo "  Reason: $REASON"

  # OPINIONATED: Auto-apply if automatic=true (recommended default)
  if [ "$AUTOMATIC" == "true" ]; then
    echo "  ✅ AUTO-APPLY: Logging for orchestrator to apply status"

    # Log the cascade event for audit trail
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,$TARGET_ID,\"$TARGET_NAME\",$CURRENT_STATUS,$SUGGESTED_STATUS,$FLOW,auto" \
      >> "$LOG_DIR/cascade-events.csv"

    # Note: Actual status application handled by orchestration Skills
    # This hook provides the audit trail and notification
    echo "     Cascade event logged - orchestrator will apply status automatically"

  else
    # Manual confirmation required
    echo "  ⚠️  MANUAL CONFIRMATION: User approval required"
    echo "     Feature/Task Orchestration Skill will prompt for confirmation"

    # Log as manual
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,$TARGET_ID,\"$TARGET_NAME\",$CURRENT_STATUS,$SUGGESTED_STATUS,$FLOW,manual" \
      >> "$LOG_DIR/cascade-events.csv"
  fi
done

echo ""
echo "════════════════════════════════════════════"
echo "✓ Cascade event processing complete"
echo "  Log: .claude/metrics/cascade-events.csv"
echo "════════════════════════════════════════════"
echo ""

exit 0
