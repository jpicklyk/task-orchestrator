#!/usr/bin/env bash
# SessionStart hook: Prompts Claude to check task-orchestrator state at session start.
# Outputs additionalContext via hookSpecificOutput to inject state-loading instructions into Claude's context.

cat <<'EOF'
{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"The task-orchestrator MCP server is connected to this project. At the start of this session, call query_container(operation=\"overview\", containerType=\"project\") and query_container(operation=\"overview\", containerType=\"feature\") to understand the current state of tracked work before proceeding. Present the results as a status table, then ask what the user wants to work on."}}
EOF
