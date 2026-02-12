#!/usr/bin/env bash
# SubagentStart hook: Injects MCP workflow discipline into every subagent's context.

cat <<'EOF'
{"hookSpecificOutput":{"hookEventName":"SubagentStart","additionalContext":"MCP Workflow Rules:\n\nSTATUS — Use request_transition(trigger=start|complete|cancel|block|hold). NEVER use manage_container setStatus, update-with-status, or bulkUpdate-with-status — they skip validation and cascade detection. Call get_next_status before trigger=\"complete\" to verify readiness.\n\nPOST-TRANSITION — Check response for cascadeEvents (advance parent entities) and unblockedTasks (newly available downstream work). Act on both.\n\nCREATION — Before creating tasks/features, call query_templates and include templateIds in the create call.\n\nCOMPLETION — Tasks: summary populated, dependencies resolved, required sections filled. Features: all child tasks terminal. Projects: all features completed."}}
EOF
