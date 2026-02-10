#!/usr/bin/env bash
# PostToolUse hook for request_transition: Detects role boundary crossings
# and prompts the agent to sync the mirrored CC task status.
#
# Input (stdin): JSON with tool_name, tool_input, tool_response
# Output: JSON with additionalContext if role boundary crossed, or empty for no-op

python3 -c "
import sys, json

try:
    input_data = json.load(sys.stdin)
    tool_response = input_data.get('tool_response', '')

    # tool_response may be a JSON string or already parsed
    if isinstance(tool_response, str):
        response = json.loads(tool_response)
    else:
        response = tool_response

    data = response.get('data', {})
    prev_role = data.get('previousRole', '')
    new_role = data.get('newRole', '')

    # No roles or same role — no sync needed
    if not prev_role or not new_role or prev_role == new_role:
        sys.exit(0)

    # Map roles to CC task statuses
    role_to_cc = {
        'queue': 'pending',
        'blocked': 'pending',
        'work': 'in_progress',
        'review': 'in_progress',
        'terminal': 'completed'
    }

    cc_prev = role_to_cc.get(prev_role, '')
    cc_new = role_to_cc.get(new_role, '')

    # Same CC status (e.g. queue->blocked both map to pending) — no sync needed
    if not cc_new or cc_prev == cc_new:
        sys.exit(0)

    # Role boundary crossed — inject sync guidance
    container_id = data.get('containerId', 'unknown')
    container_type = data.get('containerType', 'unknown')
    result = {
        'hookSpecificOutput': {
            'hookEventName': 'PostToolUse',
            'additionalContext': (
                f'MCP status sync: {container_type} {container_id} crossed a role boundary '
                f'({prev_role} -> {new_role}). Update the mirrored CC task status to '
                f'\"{cc_new}\" using TaskUpdate.'
            )
        }
    }
    print(json.dumps(result))
except Exception:
    sys.exit(0)
"
