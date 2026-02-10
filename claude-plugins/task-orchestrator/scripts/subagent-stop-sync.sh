#!/usr/bin/env bash
# SubagentStop hook: When a subagent finishes, checks if it referenced MCP tasks
# and prompts the orchestrator to update MCP task status.
#
# NOTE: SubagentStop uses Stop decision control (decision/reason only).
# additionalContext is NOT documented for this event. We use it best-effort
# since there's no other mechanism to inject post-subagent context.
# If CC ignores it, the orchestrator output style still covers this behavior.
#
# Input (stdin): JSON with agent_id, agent_type, agent_transcript_path, stop_hook_active
# Output: JSON with hookSpecificOutput if MCP references found, or empty for no-op

python3 -c "
import sys, json, re, os

try:
    input_data = json.load(sys.stdin)

    # Prevent loops: if stop hook is already active, skip
    stop_hook_active = input_data.get('stop_hook_active', False)
    if stop_hook_active:
        sys.exit(0)

    agent_type = input_data.get('agent_type', '')
    transcript_path = input_data.get('agent_transcript_path', '')

    # No transcript path — nothing to scan
    if not transcript_path:
        sys.exit(0)

    # Expand ~ in path
    transcript_path = os.path.expanduser(transcript_path)

    # Check file exists and is readable
    if not os.path.isfile(transcript_path):
        sys.exit(0)

    # UUID pattern for MCP entities
    uuid_pattern = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')

    # MCP tool name pattern
    mcp_tool_pattern = re.compile(r'mcp__mcp-task-orchestrator__\w+')

    found_uuids = set()
    found_mcp_tools = False

    # Scan transcript line by line (JSONL format) as raw text
    # Limit to last 200 lines for performance on long transcripts
    with open(transcript_path, 'r', encoding='utf-8', errors='replace') as f:
        lines = f.readlines()

    scan_lines = lines[-200:] if len(lines) > 200 else lines

    for line in scan_lines:
        line_stripped = line.strip()
        if not line_stripped:
            continue

        # Check for MCP tool references
        if mcp_tool_pattern.search(line_stripped):
            found_mcp_tools = True

        # Collect UUIDs from lines that reference MCP tools
        uuids = uuid_pattern.findall(line_stripped)
        found_uuids.update(uuids)

    # Only inject guidance if MCP tools were actually referenced
    if not found_mcp_tools:
        sys.exit(0)

    # Deduplicate and limit UUID list for readability
    uuid_list = sorted(found_uuids)[:5]
    uuid_str = ', '.join(uuid_list)

    result = {
        'hookSpecificOutput': {
            'hookEventName': 'SubagentStop',
            'additionalContext': (
                f'Subagent ({agent_type}) completed and referenced MCP task-orchestrator entities: '
                f'{uuid_str}. Review the subagent results and update MCP task status if work was '
                f'completed. Use request_transition(trigger=\"complete\") for finished tasks or '
                f'query_container(operation=\"overview\") to check current state.'
            )
        }
    }
    print(json.dumps(result))
except Exception:
    sys.exit(0)
"
