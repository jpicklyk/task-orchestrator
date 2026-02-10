#!/usr/bin/env bash
# PostToolUse hook for manage_container: When a new MCP task is created,
# prompts the agent to create a CC mirror task.
#
# Input (stdin): JSON with tool_name, tool_input, tool_response
# Output: JSON with additionalContext if task created, or empty for no-op

python3 -c "
import sys, json

try:
    input_data = json.load(sys.stdin)
    tool_input = input_data.get('tool_input', {})

    # Only fire for create + task
    operation = tool_input.get('operation', '')
    container_type = tool_input.get('containerType', '')
    if operation != 'create' or container_type != 'task':
        sys.exit(0)

    tool_response = input_data.get('tool_response', '')

    # tool_response may be a JSON string or already parsed
    if isinstance(tool_response, str):
        response = json.loads(tool_response)
    else:
        response = tool_response

    # Check success
    if not response.get('success', False):
        sys.exit(0)

    data = response.get('data', {})
    task_id = data.get('id', '')
    task_title = tool_input.get('title', data.get('title', 'Untitled'))

    if not task_id:
        sys.exit(0)

    short_id = task_id[:8]

    result = {
        'hookSpecificOutput': {
            'hookEventName': 'PostToolUse',
            'additionalContext': (
                f'New MCP task created: [{short_id}] {task_title}. '
                f'Create a CC mirror task: TaskCreate(subject=\"[{short_id}] {task_title}\", '
                f'description=\"MCP task {task_id}\", '
                f'activeForm=\"Working on {task_title}\")'
            )
        }
    }
    print(json.dumps(result))
except Exception:
    sys.exit(0)
"
