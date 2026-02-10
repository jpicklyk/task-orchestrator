#!/usr/bin/env bash
# PostToolUseFailure hook for MCP tools: Provides targeted error recovery guidance.
#
# Input (stdin): JSON with tool_name, tool_input, error, is_interrupt
# Output: JSON with additionalContext containing recovery steps

python3 -c "
import sys, json

try:
    input_data = json.load(sys.stdin)
    tool_name = input_data.get('tool_name', '')
    error = input_data.get('error', '')
    is_interrupt = input_data.get('is_interrupt', False)

    # Don't inject guidance for user interrupts
    if is_interrupt:
        sys.exit(0)

    error_lower = error.lower()

    # Pattern match on error for specific guidance
    if 'connection' in error_lower or 'refused' in error_lower or 'econnrefused' in error_lower:
        guidance = (
            'MCP server connection failed. The task-orchestrator server may not be running. '
            'Check that the MCP server is configured and accessible. '
            'Continue with non-MCP work if possible.'
        )
    elif 'not found' in error_lower or 'no entity' in error_lower:
        guidance = (
            'Entity not found. The UUID may be incorrect or the entity was deleted. '
            'Verify with query_container(operation=\"overview\") to see current entities.'
        )
    elif 'transition' in error_lower or 'status' in error_lower:
        guidance = (
            'Status transition error. The requested transition may not be valid from the current status. '
            'Check prerequisites with get_next_status before retrying.'
        )
    elif 'duplicate' in error_lower or 'conflict' in error_lower or 'already exists' in error_lower:
        guidance = (
            'Conflict or duplicate detected. The entity or dependency may already exist. '
            'Query current state before retrying.'
        )
    elif 'validation' in error_lower or 'invalid' in error_lower:
        guidance = (
            'Validation error. Check tool parameters: required fields, correct types, '
            'and valid enum values. Review the tool description for parameter constraints.'
        )
    else:
        guidance = (
            f'MCP tool call failed: {tool_name}. '
            'Review the error, verify parameters, and retry. '
            'Use query_container(operation=\"overview\") to confirm current state.'
        )

    result = {
        'hookSpecificOutput': {
            'hookEventName': 'PostToolUseFailure',
            'additionalContext': guidance
        }
    }
    print(json.dumps(result))
except Exception:
    sys.exit(0)
"
