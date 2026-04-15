# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 3.x     | Yes       |
| 2.x     | No        |
| 1.x     | No        |

## Reporting a Security Issue

If you discover a security issue in MCP Task Orchestrator, please report it responsibly.

**Do not open a public GitHub issue for security concerns.**

Instead, please email the maintainer directly or use [GitHub's private vulnerability reporting](https://github.com/jpicklyk/task-orchestrator/security/advisories/new) to submit a report.

### What to include

- Description of the issue
- Steps to reproduce
- Potential impact
- Suggested fix (if you have one)

### What to expect

- Acknowledgment within 48 hours
- A plan for resolution within 7 days
- Credit in the fix announcement (unless you prefer to remain anonymous)

## Security Considerations

MCP Task Orchestrator is designed to run locally or in a controlled environment:

- **SQLite database**: Stored on a Docker volume (`mcp-task-data`). Ensure appropriate file permissions on the host mount.
- **No authentication by default**: The MCP server trusts its transport layer. When using HTTP transport, ensure it is only accessible from trusted clients.
- **Actor attribution**: When `auditing.enabled: true` in config, the server requires agent identity on all write operations. This provides accountability, not authentication — actor claims are self-reported by default. For verified attribution, configure JWKS validation.
- **Config files**: `.taskorchestrator/config.yaml` is mounted read-only (`:ro`) in Docker. It contains workflow rules, not credentials.
