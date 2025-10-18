#!/bin/bash
#
# Subagent Stop Logger Hook
#
# Logs metrics about subagent execution including duration, agent type,
# and associated task/feature IDs. Useful for understanding agent usage
# patterns and optimizing workflow efficiency.
#
# Hook Event: SubagentStop
# Trigger: When any subagent completes execution
#
# Usage:
#   Configure in .claude/settings.local.json:
#   {
#     "hooks": {
#       "SubagentStop": [{
#         "hooks": [{
#           "type": "command",
#           "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/subagent-stop-logger.sh"
#         }]
#       }]
#     }
#   }

set -euo pipefail

# Read JSON input from stdin
INPUT=$(cat)

# Create logs directory if it doesn't exist
LOGS_DIR="$CLAUDE_PROJECT_DIR/.claude/logs"
mkdir -p "$LOGS_DIR"

LOG_FILE="$LOGS_DIR/subagent-metrics.log"
CSV_FILE="$LOGS_DIR/subagent-metrics.csv"

# Extract subagent information
AGENT_NAME=$(echo "$INPUT" | jq -r '.agent_name // "unknown"')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')

# Extract subagent prompt to find task/feature IDs
PROMPT=$(echo "$INPUT" | jq -r '.subagent_prompt // ""')

# Try to extract task ID from prompt (format: "Work on task UUID:")
TASK_ID=$(echo "$PROMPT" | grep -oP 'task\s+[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | grep -oP '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' || echo "")

# Try to extract feature ID from prompt (format: "FEATURE: Name (UUID)" or "feature UUID")
FEATURE_ID=$(echo "$PROMPT" | grep -oP 'feature[:\s]+[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | grep -oP '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' || echo "")

# Calculate duration if start time is available (not typically in SubagentStop, but log for future)
DURATION="N/A"

# Categorize agent type
AGENT_CATEGORY="unknown"
case "$AGENT_NAME" in
  *"Backend Engineer"*|*"backend-engineer"*) AGENT_CATEGORY="implementation" ;;
  *"Frontend Developer"*|*"frontend-developer"*) AGENT_CATEGORY="implementation" ;;
  *"Database Engineer"*|*"database-engineer"*) AGENT_CATEGORY="implementation" ;;
  *"Test Engineer"*|*"test-engineer"*) AGENT_CATEGORY="quality" ;;
  *"Technical Writer"*|*"technical-writer"*) AGENT_CATEGORY="documentation" ;;
  *"Feature Manager"*|*"feature-manager"*) AGENT_CATEGORY="coordination" ;;
  *"Task Manager"*|*"task-orchestrator"*) AGENT_CATEGORY="coordination" ;;
  *"Feature Architect"*|*"feature-architect"*) AGENT_CATEGORY="planning" ;;
  *"Planning Specialist"*|*"planning-specialist"*) AGENT_CATEGORY="planning" ;;
  *"Bug Triage"*|*"bug-triage"*) AGENT_CATEGORY="investigation" ;;
esac

# Write to human-readable log
cat >> "$LOG_FILE" << EOF
────────────────────────────────────────
Timestamp:       $TIMESTAMP
Agent:           $AGENT_NAME
Category:        $AGENT_CATEGORY
Session:         $SESSION_ID
Task ID:         ${TASK_ID:-"N/A"}
Feature ID:      ${FEATURE_ID:-"N/A"}
Duration:        $DURATION
────────────────────────────────────────

EOF

# Initialize CSV if it doesn't exist
if [ ! -f "$CSV_FILE" ]; then
  echo "timestamp,agent_name,agent_category,session_id,task_id,feature_id,duration" > "$CSV_FILE"
fi

# Append CSV entry
echo "$TIMESTAMP,$AGENT_NAME,$AGENT_CATEGORY,$SESSION_ID,${TASK_ID:-""},${FEATURE_ID:-""},$DURATION" >> "$CSV_FILE"

# Generate summary statistics
TOTAL_RUNS=$(tail -n +2 "$CSV_FILE" | wc -l)
AGENT_COUNTS=$(tail -n +2 "$CSV_FILE" | cut -d',' -f2 | sort | uniq -c | sort -rn)

# Output feedback to orchestrator
cat << EOF
✓ Logged subagent execution: $AGENT_NAME ($AGENT_CATEGORY)
  Session: $SESSION_ID
  Task: ${TASK_ID:-"N/A"} | Feature: ${FEATURE_ID:-"N/A"}
  Total subagent runs: $TOTAL_RUNS

Top 3 agents:
$(echo "$AGENT_COUNTS" | head -3 | awk '{printf "  %2d× %s\n", $1, $2}')

Logs: $LOG_FILE
Data: $CSV_FILE
EOF

exit 0
