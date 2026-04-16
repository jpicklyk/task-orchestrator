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

## Security Model

### Trust Boundaries

MCP Task Orchestrator uses a **database-per-tenant** isolation model. The trust boundary is the database file — all agents connecting to the same server instance share the same database and are considered part of the same trust domain (team/fleet).

```
┌─────────────────────────────────────┐  ┌─────────────────────────────────────┐
│          Team A (tenant)            │  │          Team B (tenant)            │
│                                     │  │                                     │
│  TO Instance ──► team-a/tasks.db    │  │  TO Instance ──► team-b/tasks.db    │
│    ▲  ▲  ▲                          │  │    ▲  ▲  ▲                          │
│    │  │  │                          │  │    │  │  │                          │
│  Agent-1  Agent-2  Agent-3          │  │  Agent-4  Agent-5  Agent-6          │
│                                     │  │                                     │
│  Agents cooperate. Full read/write  │  │  Agents cooperate. Full read/write  │
│  access within this database.       │  │  access within this database.       │
└─────────────────────────────────────┘  └─────────────────────────────────────┘
         No shared state between instances.
         Physical file separation = hard isolation.
```

**Between tenants:** Hard isolation via separate database files, separate server processes, and (in Docker) separate volumes. No schema-level multi-tenancy — no tenant_id columns, no row-level filtering. Cross-tenant data leakage is physically impossible because the data lives in different files.

**Within a tenant:** All agents in a fleet share the same database with full read/write access. This is by design — agents within a team read each other's items, advance each other's workflows, and coordinate via shared work items and dependencies. The orchestrator is a collaboration tool, not an access-control system.

### Deployment Patterns for Tenant Isolation

| Pattern | Isolation mechanism | Configuration |
|---------|-------------------|---------------|
| **STDIO** (default) | Each MCP client spawns its own server process | `DATABASE_PATH=team-a/tasks.db` per process |
| **Docker per team** | Separate containers with separate volumes | `-v team-a-data:/app/data` per container |
| **HTTP per team** | Separate server instances on different ports | `DATABASE_PATH` + `MCP_HTTP_PORT` per instance |

The `DATABASE_PATH` environment variable controls which SQLite file a server instance uses. Each instance gets its own Flyway migrations, WAL file, and write serialization. No code changes are needed for tenant isolation — it is an operational deployment decision.

### Authentication and Authorization

MCP Task Orchestrator does **not** implement authentication or authorization at the application layer. The server trusts its transport layer:

- **STDIO transport**: The MCP client spawns the server process directly. The client is implicitly trusted — it controls the process lifecycle and database path.
- **HTTP transport**: The server binds to a configurable host and port (default `0.0.0.0:3001`). **There is no built-in TLS, authentication headers, or CORS.** When using HTTP transport, ensure the server is only accessible from trusted clients — deploy behind a reverse proxy with TLS and authentication, or restrict network access to trusted hosts.

All 13 MCP tools are available to any connected client without identification. There is no permission model, no role-based access control, and no per-tool authorization checks. Within a tenant's database, all agents are peers with equal access.

**This is intentional.** Within a cooperating fleet, access control between agents adds friction without meaningful security benefit — the agents are working toward the same goals. Tenant isolation is achieved at the infrastructure level (separate databases), not the application level.

### Actor Verification (Accountability Layer)

The actor verification system provides **accountability** (who did what) but **not access control** (who can do what). These are distinct concerns:

| Concern | Mechanism | Enforcement |
|---------|-----------|-------------|
| **Accountability** | Actor claims + JWKS verification | Advisory — recorded in audit trail |
| **Access control** | None at application layer | Delegated to transport/infrastructure |

**How it works:**

1. Two tools (`advance_item` and `manage_notes`) accept an optional `actor` claim:
   ```json
   { "id": "agent-7", "kind": "subagent", "parent": "orchestrator-1", "proof": "eyJhbG..." }
   ```
2. If JWKS verification is configured (`.taskorchestrator/config.yaml` → `auditing.verifier.type: jwks`), the `proof` field is validated as a JWT against the configured JWKS endpoint.
3. The verification result (`VERIFIED`, `UNVERIFIED`, or `FAILED`) is stored in the `RoleTransition` or `Note` audit record.
4. **A failed verification does not block the operation.** The write proceeds regardless of verification status.
5. **Actor claims are optional.** Tools can be called without an actor, and the operation proceeds normally.

**When to use JWKS verification:**

Configure JWKS when you need a cryptographically verifiable audit trail — e.g., compliance requirements, multi-agent fleets where you need to trace which specific agent made each change, or integration with identity providers like AgentLair. The verification layer ensures that actor claims cannot be forged (the JWT signature is checked), but it does not gate operations on verification status.

**Default configuration:**

```yaml
auditing:
  enabled: true        # Actor claims are recorded (but not required)
  verifier:
    type: noop         # Default: no JWT verification. Claims accepted at face value.
```

To enable JWKS verification:

```yaml
auditing:
  enabled: true
  verifier:
    type: jwks
    oidc_discovery: "https://identity-provider/.well-known/openid-configuration"
    issuer: "https://identity-provider"
    audience: "mcp-task-orchestrator"
    require_sub_match: true   # JWT 'sub' must match actor.id
```

### Transport Security

| Transport | Built-in security | Operator responsibility |
|-----------|------------------|----------------------|
| **STDIO** | Process-level isolation (client spawns server) | Ensure the host environment is trusted |
| **HTTP** | None — no TLS, no auth headers, no CORS | Deploy behind a reverse proxy with TLS and authentication, or restrict network access |

**For HTTP deployments in production:** Do not expose the server directly to untrusted networks. Use a reverse proxy (nginx, Caddy, Traefik) with TLS termination and client authentication. The server's `MCP_HTTP_HOST` and `MCP_HTTP_PORT` variables control the bind address — restrict to `127.0.0.1` or a private network interface when possible.

### Data Security

- **SQLite database**: Stored on a Docker volume (`mcp-task-data`) or a local file path. Ensure appropriate file permissions on the host mount. The database is not encrypted at rest — use disk-level encryption if required.
- **Config files**: `.taskorchestrator/config.yaml` is mounted read-only (`:ro`) in Docker. It contains workflow rules and optional JWKS endpoints, not credentials. JWKS URIs point to public key endpoints — no secrets are stored in config.
- **No secrets in actor claims**: The `actor.proof` field should contain a JWT token, not raw credentials. The `claimedBy` field (when the claim mechanism is implemented) should contain an identifier (session ID, container name, JWT `jti`), not secrets. These values appear in audit trails and diagnostic tool responses.

### Threat Model Summary

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Cross-tenant data leakage | Separate database files per tenant | Solved by deployment |
| Cross-tenant write contamination | Separate server instances per tenant | Solved by deployment |
| Unauthorized access within a tenant | Not mitigated — all agents in a fleet are peers | By design |
| Actor impersonation (forged claims) | JWKS verification of JWT signatures | Available, opt-in |
| Eavesdropping on HTTP transport | TLS via reverse proxy | Operator responsibility |
| Database file access on host | File permissions on Docker volume mount | Operator responsibility |
| Rogue agent disrupting workflow | Optimistic locking prevents data corruption; actor audit trail provides forensics | Partial — operational guardrails, not prevention |

### Future Considerations

- **Verification gating**: A future enhancement could optionally reject operations when actor verification fails, converting the accountability layer into an access control layer for high-security deployments. This would be opt-in to preserve the current low-friction single-team experience.
- **Claim identity validation**: When the claim mechanism (#117) is implemented, `claim_item` release operations should validate that the caller matches the `claimedBy` identity server-side, rather than trusting the `agentId` string alone. This is an intra-team operational guardrail, not a tenant isolation concern.
