#!/usr/bin/env bash
# SessionStart hook: Reminds Claude to check task-orchestrator state at session start.
# This replaces hardcoded project context in CLAUDE.local.md with a dynamic prompt
# that causes Claude to fetch fresh state from the MCP server.

cat <<'EOF'
{"systemMessage":"The task-orchestrator MCP server is connected to this project. At the start of this session, call query_container(operation=\"overview\") to understand the current state of tracked work before proceeding with any tasks."}
EOF
