#!/usr/bin/env bash
# PreToolUse hook: blocks "gh pr create" if version.properties hasn't changed vs main.
# Receives tool input JSON on stdin.

INPUT=$(cat)

# Extract command field using python3 (pipe via stdin to avoid quoting issues)
COMMAND=$(echo "$INPUT" | python3 -c "
import json, sys
try:
    data = json.loads(sys.stdin.read())
    print(data.get('tool_input', {}).get('command', ''))
except Exception:
    print('')
" 2>/dev/null)

# If extraction failed or command doesn't contain 'gh pr create', allow
if [ -z "$COMMAND" ]; then
    exit 0
fi

# Match 'gh pr create' only as a shell command (not embedded in strings/commit messages).
# Valid prefixes: start of line, &&, ;, |, or whitespace after those.
if ! echo "$COMMAND" | grep -qE '(^|[;&|]|[[:space:]])\s*gh\s+pr\s+create'; then
    exit 0
fi

# Command contains 'gh pr create' — find the repo root via git
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ $? -ne 0 ] || [ -z "$REPO_ROOT" ]; then
    exit 0
fi

# Check if version.properties changed vs main
DIFF_OUTPUT=$(git -C "$REPO_ROOT" diff main -- version.properties 2>/dev/null)
GIT_EXIT=$?

# If git diff failed (e.g. no main branch), allow
if [ $GIT_EXIT -ne 0 ]; then
    exit 0
fi

# If diff is empty, version.properties hasn't changed — block
if [ -z "$DIFF_OUTPUT" ]; then
    printf '{"decision":"block","reason":"version.properties has not been updated. Run /bump-version to analyze the changes, infer the version bump, draft the changelog, and create the PR."}\n'
    exit 0
fi

# Diff is non-empty — version was bumped, allow
exit 0
