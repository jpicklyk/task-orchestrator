#!/usr/bin/env bash
# PostToolUse hook for get_next_task: When task recommendations include a featureId,
# prompts the agent to mirror that feature's tasks into the CC task display.
#
# Input (stdin): JSON with tool_name, tool_input, tool_response
# Output: JSON with additionalContext if feature tasks should be mirrored, or empty for no-op

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

    # Extract recommendations from response data
    data = response.get('data', {})
    recommendations = data.get('recommendations', [])

    if not recommendations:
        sys.exit(0)

    # Collect unique featureIds from recommendations
    feature_ids = set()
    for rec in recommendations:
        fid = rec.get('featureId', '')
        if fid:
            feature_ids.add(fid)

    if not feature_ids:
        sys.exit(0)

    # Build prompt for each feature
    features_list = ', '.join(feature_ids)
    result = {
        'hookSpecificOutput': {
            'hookEventName': 'PostToolUse',
            'additionalContext': (
                f'Task recommendations reference feature(s): {features_list}. '
                f'Before starting work, ensure these features\\'s tasks are mirrored '
                f'to the CC task display. For each feature, use '
                f'query_container(operation=\"overview\", containerType=\"feature\", id=\"<featureId>\") '
                f'to get the task list, then create CC mirror tasks following the task-mirroring '
                f'skill pattern: subject=\"[<first-8-uuid>] <title>\", '
                f'description=\"MCP task <full-uuid>\", metadata with mcpTaskId and mcpFeatureId. '
                f'Skip any tasks that already have CC mirrors.'
            )
        }
    }
    print(json.dumps(result))
except Exception:
    sys.exit(0)
"
