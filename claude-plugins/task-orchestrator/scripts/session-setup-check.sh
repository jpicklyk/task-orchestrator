#!/usr/bin/env bash
# SessionStart hook: Checks for MCP Task Orchestrator setup instructions marker
# in the project's agent instructions file.

EXPECTED_VERSION="v2"

# 1. Config opt-out check
CONFIG_FILE=".taskorchestrator/config.yaml"
if [ -f "$CONFIG_FILE" ]; then
  if grep -qE 'setup_check_enabled:\s*false' "$CONFIG_FILE"; then
    exit 0
  fi
fi

# 2. Scan instruction files — stop at the first one found
INSTRUCTION_FILE=""
for candidate in "CLAUDE.md" "CLAUDE.local.md" ".cursorrules" ".windsurfrules"; do
  if [ -f "$candidate" ]; then
    INSTRUCTION_FILE="$candidate"
    break
  fi
done

# 3. Version marker check
FOUND_VERSION=""
if [ -n "$INSTRUCTION_FILE" ]; then
  FOUND_VERSION=$(grep -oP '<!-- mcp-task-orchestrator-setup: \K[^ ]+(?= -->)' "$INSTRUCTION_FILE" 2>/dev/null || true)
fi

# 4. Decision
if [ -z "$INSTRUCTION_FILE" ] || [ -z "$FOUND_VERSION" ]; then
  # Missing — no instruction file or no marker found
  cat <<'EOF'
{"hookSpecificOutput":{"additionalContext":"MCP Task Orchestrator setup instructions are not installed in this project. To enable workflow rules, read the MCP resource task-orchestrator://guidelines/setup-instructions and follow its steps to add the instruction block to your project's agent instructions file. This ensures consistent tool usage patterns across sessions."}}
EOF
elif [ "$FOUND_VERSION" != "$EXPECTED_VERSION" ]; then
  # Outdated — marker version doesn't match expected
  printf '{"hookSpecificOutput":{"additionalContext":"MCP Task Orchestrator setup instructions are outdated (found %s, current is %s). Read the MCP resource task-orchestrator://guidelines/setup-instructions to get the updated instruction block. Replace the existing block in %s."}}\n' "$FOUND_VERSION" "$EXPECTED_VERSION" "$INSTRUCTION_FILE"
fi
# If version matches, exit silently (no output)
