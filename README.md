# MCP Task Orchestrator

A Kotlin implementation of the Model Context Protocol (MCP) server for task management, providing AI assistants with a
structured way to interact with task management data.

## Overview

The MCP Task Orchestrator enables AI assistants to create, update, retrieve, and delete tasks while maintaining context
efficiency and optimizing token usage. It focuses on sophisticated task organization, complexity analysis, and automated
task breakdown to improve project management workflows.

## Key Features

- Task management tools exposed via the Model Context Protocol
- SQLite database storage for persistence
- Context optimization for efficient AI assistant interaction
- Support for task complexity analysis and breakdown
- Hierarchical organization with features and subtasks
- Dependency tracking and validation

## Getting Started

### Prerequisites

- JDK 17 or higher
- Kotlin 1.9 or higher

### Building

```bash
./gradlew build
```

### Running

```bash
java -jar build/libs/mcp-task-orchestrator-0.1.0.jar
```

With environment variables:

```bash
MCP_TRANSPORT=stdio DATABASE_PATH=data/tasks.db java -jar build/libs/mcp-task-orchestrator-0.1.0.jar
```

## Configuration

| Environment Variable | Description                        | Default               |
|----------------------|------------------------------------|-----------------------|
| `MCP_TRANSPORT`      | Transport type (stdio)             | stdio                 |
| `DATABASE_PATH`      | Path to SQLite database file       | data/tasks.db         |
| `MCP_SERVER_NAME`    | Server name for MCP information    | mcp-task-orchestrator |
| `MCP_DEBUG`          | Enable debug mode                  | false                 |

## Troubleshooting MCP Connection Issues

### JSON Parsing Errors

If you see errors like `"Unexpected number in JSON at position 1"` or
`"Unexpected token 'j', "java.util."... is not valid JSON"`, this indicates that non-JSON content (likely Java exception
stack traces) is being mixed into the JSON-RPC message stream.

#### Solution

Enable debug mode to diagnose the issue:

```bash
MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-0.1.0.jar
```

This will create detailed logs in the `logs` directory:
- `task-orchestrator.log` - General application logs


### Testing Connection

Run the included test script to verify your connection:

```bash
node scripts/test-mcp-connection.js
```

This will start the server and run basic connectivity tests to ensure proper JSON-RPC communication.

## Advanced Debugging

For advanced debugging, you can:

1. Examine the detailed message logs in the `logs` directory
2. Use the echo tool to test basic connectivity:

```json
{
   "jsonrpc": "2.0",
   "id": 1,
   "method": "tools/call",
   "params": {
      "name": "echo",
      "arguments": {
         "message": "Hello, MCP server!"
      }
   }
}
```

## License

[MIT License](LICENSE)
