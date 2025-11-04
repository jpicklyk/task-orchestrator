#!/bin/bash
# Log all cascade events to CSV for analytics
# Non-blocking, observation-only hook for understanding workflow patterns
#
# This example hook is SAFE to add - it only logs, never blocks or modifies behavior
# Use this to understand cascade event patterns before implementing auto-apply hooks
#
# Logs include:
# - All cascade events (first_task_started, all_tasks_complete, all_features_complete, custom)
# - Auto vs manual confirmation flags
# - Workflow flow context
# - Summary statistics

INPUT=$(cat)

# Extract cascade events
CASCADE_EVENTS=$(echo "$INPUT" | jq -r '.tool_output.data.cascadeEvents // []')

if [ "$CASCADE_EVENTS" == "[]" ] || [ "$CASCADE_EVENTS" == "null" ]; then
  exit 0
fi

# Create metrics directory
LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$LOG_DIR"

# Define log files
CASCADE_LOG="$LOG_DIR/cascade-events.csv"
SUMMARY_LOG="$LOG_DIR/cascade-summary.json"

# Create header if file doesn't exist
if [ ! -f "$CASCADE_LOG" ]; then
  echo "timestamp,event,target_type,target_id,target_name,current_status,suggested_status,flow,automatic,reason" \
    > "$CASCADE_LOG"
fi

echo "📊 Cascade Event Logger - Recording events for analytics"

# Log each cascade event
EVENT_COUNT=0
echo "$CASCADE_EVENTS" | jq -c '.[]' | while read -r event; do
  EVENT_COUNT=$((EVENT_COUNT + 1))

  TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  EVENT_TYPE=$(echo "$event" | jq -r '.event')
  TARGET_TYPE=$(echo "$event" | jq -r '.targetType')
  TARGET_ID=$(echo "$event" | jq -r '.targetId')
  TARGET_NAME=$(echo "$event" | jq -r '.targetName')
  CURRENT_STATUS=$(echo "$event" | jq -r '.currentStatus')
  SUGGESTED_STATUS=$(echo "$event" | jq -r '.suggestedStatus')
  FLOW=$(echo "$event" | jq -r '.flow')
  AUTOMATIC=$(echo "$event" | jq -r '.automatic')
  REASON=$(echo "$event" | jq -r '.reason')

  # Append to CSV (escape quotes in names/reasons)
  TARGET_NAME_ESCAPED=$(echo "$TARGET_NAME" | sed 's/"/""/g')
  REASON_ESCAPED=$(echo "$REASON" | sed 's/"/""/g')

  echo "$TIMESTAMP,$EVENT_TYPE,$TARGET_TYPE,$TARGET_ID,\"$TARGET_NAME_ESCAPED\",$CURRENT_STATUS,$SUGGESTED_STATUS,$FLOW,$AUTOMATIC,\"$REASON_ESCAPED\"" \
    >> "$CASCADE_LOG"

  echo "   ✓ Event #$EVENT_COUNT: $EVENT_TYPE ($TARGET_TYPE) - auto=$AUTOMATIC, flow=$FLOW"
done

# Update summary statistics
if [ -f "$CASCADE_LOG" ]; then
  TOTAL_EVENTS=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | wc -l | tr -d ' ')
  AUTO_EVENTS=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep ",true," | wc -l | tr -d ' ')
  MANUAL_EVENTS=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep ",false," | wc -l | tr -d ' ')

  # Count by event type
  FIRST_TASK=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep "first_task_started" | wc -l | tr -d ' ')
  ALL_TASKS=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep "all_tasks_complete" | wc -l | tr -d ' ')
  ALL_FEATURES=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep "all_features_complete" | wc -l | tr -d ' ')

  # Count by flow
  DEFAULT_FLOW=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep ",default_flow," | wc -l | tr -d ' ')
  RAPID_FLOW=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep ",rapid_prototype_flow," | wc -l | tr -d ' ')
  REVIEW_FLOW=$(tail -n +2 "$CASCADE_LOG" 2>/dev/null | grep ",with_review_flow," | wc -l | tr -d ' ')

  cat > "$SUMMARY_LOG" <<EOF
{
  "total_cascade_events": $TOTAL_EVENTS,
  "automatic_events": $AUTO_EVENTS,
  "manual_events": $MANUAL_EVENTS,
  "by_event_type": {
    "first_task_started": $FIRST_TASK,
    "all_tasks_complete": $ALL_TASKS,
    "all_features_complete": $ALL_FEATURES
  },
  "by_flow": {
    "default_flow": $DEFAULT_FLOW,
    "rapid_prototype_flow": $RAPID_FLOW,
    "with_review_flow": $REVIEW_FLOW
  },
  "auto_percentage": $(( TOTAL_EVENTS > 0 ? (AUTO_EVENTS * 100) / TOTAL_EVENTS : 0 )),
  "last_updated": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

  echo ""
  echo "📈 Summary Statistics:"
  echo "   Total Events: $TOTAL_EVENTS"
  echo "   Auto-Apply: $AUTO_EVENTS ($((TOTAL_EVENTS > 0 ? (AUTO_EVENTS * 100) / TOTAL_EVENTS : 0))%)"
  echo "   Manual Confirm: $MANUAL_EVENTS"
  echo ""
  echo "   By Event Type:"
  echo "     first_task_started: $FIRST_TASK"
  echo "     all_tasks_complete: $ALL_TASKS"
  echo "     all_features_complete: $ALL_FEATURES"
  echo ""
  echo "   By Workflow Flow:"
  echo "     default_flow: $DEFAULT_FLOW"
  echo "     rapid_prototype_flow: $RAPID_FLOW"
  echo "     with_review_flow: $REVIEW_FLOW"
fi

echo ""
echo "✓ Cascade events logged successfully"
echo "  CSV Log: .claude/metrics/cascade-events.csv"
echo "  Summary: .claude/metrics/cascade-summary.json"
echo ""
echo "  Analytics Queries:"
echo "    tail -10 .claude/metrics/cascade-events.csv  # Recent events"
echo "    cat .claude/metrics/cascade-summary.json     # Summary stats"
echo ""

exit 0
