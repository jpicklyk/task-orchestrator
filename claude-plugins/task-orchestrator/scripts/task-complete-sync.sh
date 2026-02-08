#!/usr/bin/env bash
# TaskCompleted hook: Ensures mirrored CC tasks trigger an MCP transition
# before completion is allowed. Uses block-once pattern with temp file marker.
#
# Input (stdin): JSON with task_id, task_subject, task_description
# Output: exit 0 (allow) or exit 2 + stderr (block once)

python3 -c "
import sys, json, re, os

try:
    input_data = json.load(sys.stdin)
    task_id = input_data.get('task_id', '')
    subject = input_data.get('task_subject', '')
    description = input_data.get('task_description', '')

    # Check for [xxxxxxxx] MCP UUID prefix in subject
    hash_match = re.match(r'^\[([0-9a-f]{8})\]', subject)
    if not hash_match:
        # Not a mirrored task — allow completion
        sys.exit(0)

    short_hash = hash_match.group(1)

    # Try to extract full UUID from description
    uuid_match = re.search(r'MCP task ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})', description)
    mcp_uuid = uuid_match.group(1) if uuid_match else short_hash

    # Block-once pattern: check for temp marker
    marker_path = '/tmp/.cc-task-' + str(task_id) + '-mcp-synced'

    if os.path.exists(marker_path):
        # Already reminded once — allow through and clean up
        os.remove(marker_path)
        sys.exit(0)

    # First time — create marker and block with guidance
    with open(marker_path, 'w') as f:
        f.write(mcp_uuid)

    sys.stderr.write('This CC task mirrors MCP task ' + mcp_uuid + '.\n')
    sys.stderr.write('Before completing the CC task, transition the MCP task:\n')
    sys.stderr.write('  request_transition(containerId=\"' + mcp_uuid + '\", containerType=\"task\", trigger=\"complete\")\n')
    sys.stderr.write('Then retry completing this CC task.\n')
    sys.exit(2)

except Exception:
    # On any error, allow completion (fail open)
    sys.exit(0)
"
