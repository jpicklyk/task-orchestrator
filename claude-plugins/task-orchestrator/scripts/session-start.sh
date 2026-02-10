#!/usr/bin/env bash
# SessionStart hook: Prompts Claude to check task-orchestrator state at session start.
# Outputs additionalContext via hookSpecificOutput to inject state-loading instructions into Claude's context.

cat <<'EOF'
{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"Before responding to the user's message, you MUST first follow your Session Start instructions from your output style. Do not skip this step, even if the user has already sent a message."}}
EOF
