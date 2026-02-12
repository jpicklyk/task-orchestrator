#!/usr/bin/env bash
# PreToolUse hook for request_transition: Verifies get_next_status was called before completion.

# Read hook input from stdin
INPUT=$(cat)

# Extract fields using node (guaranteed available in Claude Code)
PARSED=$(echo "$INPUT" | node -e "
  const d = JSON.parse(require('fs').readFileSync(0,'utf8'));
  console.log([
    d.tool_input?.trigger || '',
    d.tool_input?.containerId || '',
    d.transcript_path || ''
  ].join('\n'));
" 2>/dev/null)

TRIGGER=$(echo "$PARSED" | sed -n '1p')
CONTAINER_ID=$(echo "$PARSED" | sed -n '2p')
TRANSCRIPT=$(echo "$PARSED" | sed -n '3p')

# Only check for completion trigger
if [ "$TRIGGER" != "complete" ]; then
  cat <<'ALLOW'
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}
ALLOW
  exit 0
fi

# Check transcript for recent get_next_status call with this container ID
if [ -n "$TRANSCRIPT" ] && [ -f "$TRANSCRIPT" ]; then
  # Search last 200 lines for get_next_status with this container's UUID
  if tail -200 "$TRANSCRIPT" 2>/dev/null | grep -q "get_next_status.*$CONTAINER_ID"; then
    cat <<ALLOW
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"Readiness verified via get_next_status"}}
ALLOW
    exit 0
  fi
fi

# Block: readiness not verified
cat <<DENY
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Workflow requires calling get_next_status(containerId='$CONTAINER_ID') before completing. This verifies prerequisites, blockers, and workflow readiness. Call get_next_status first, then retry this completion."}}
DENY
exit 0
