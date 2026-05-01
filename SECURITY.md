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

### Actor Attribution and Verification

Actor attribution has two layers — **presence enforcement** (is an actor claim provided?) and **authenticity verification** (is the claim cryptographically valid?). These are implemented in different places:

| Concern | Mechanism | Where | Enforcement |
|---------|-----------|-------|-------------|
| **Presence** | `auditing.enabled: true` in config | Claude Code plugin hook (client-side) | Blocks tool calls missing `actor` objects |
| **Authenticity** | `auditing.verifier.type: jwks` in config | MCP server (server-side) | Advisory — recorded in audit trail, does not block operations |

#### Layer 1: Presence enforcement (plugin hook)

When `auditing.enabled: true` is set in `.taskorchestrator/config.yaml`, the Claude Code plugin hook (`enforce-actor-attribution.mjs`) intercepts `advance_item` and `manage_notes(upsert)` calls **before they reach the server**. If any transition or note element is missing an `actor` object, the call is blocked with an error message.

**Important:** This enforcement only applies to Claude Code clients with the task-orchestrator plugin installed. Raw MCP clients connecting directly to the HTTP endpoint bypass the hook entirely. The server itself does not enforce actor presence — tools accept calls without `actor` claims and proceed with `actorClaim = null`.

#### Layer 2: Authenticity verification (server-side JWKS)

When `auditing.verifier.type: jwks` is configured, the server validates the `actor.proof` JWT against the configured JWKS endpoint:

1. Two tools (`advance_item` and `manage_notes`) accept an optional `actor` claim:
   ```json
   { "id": "agent-7", "kind": "subagent", "parent": "orchestrator-1", "proof": "eyJhbG..." }
   ```
2. The `proof` field is validated as a JWT — signature, expiry, issuer, audience, and subject match are checked.
3. The verification result (`VERIFIED`, `UNVERIFIED`, or `FAILED`) is stored in the `RoleTransition` or `Note` audit record.
4. **A failed verification does not block the operation.** The write proceeds regardless of verification status. This is an accountability mechanism, not an access control gate.

**When to use JWKS verification:**

Configure JWKS when you need a cryptographically verifiable audit trail — e.g., compliance requirements, multi-agent fleets where you need to trace which specific agent made each change, or integration with identity providers like AgentLair. The verification layer ensures that actor claims cannot be forged (the JWT signature is checked), but it does not gate operations on verification status.

**Default configuration:**

```yaml
auditing:
  enabled: true        # Plugin hook enforces actor presence on write operations
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

### DID-rooted trust (v1 — did:web)

**When to use:** Per-agent DID identities in fleet deployments using AgentLair-style or other
`did:web` identity providers. Each agent is identified by a DID rather than a shared JWKS endpoint —
the verifier resolves the agent's DID document on-demand and extracts the signing key inline.

**Configuration:**

```yaml
auditing:
  enabled: true
  degraded_mode_policy: reject       # recommended for cross-org did:web fleets
  verifier:
    type: jwks
    # DID trust mode — mutually exclusive with oidc_discovery / jwks_uri / jwks_path
    did_allowlist:
      - "did:web:agent-a.example.com"
      - "did:web:agent-b.partner.org"
    # OR use a glob pattern to match any did:web under your domain:
    # did_pattern: "did:web:agents.example.com:*"
    algorithms:
      - EdDSA                        # required — no implicit default; empty or missing causes startup error
    audience: "mcp-task-orchestrator"
    require_sub_match: true
    did_strict_relationship: true    # only assertionMethod-referenced keys are eligible
    did_loose_kid_match: true        # allow single-key DID docs with mismatched kid header
```

**Trust policy constraints (enforced at startup):**

- **`algorithms` is required.** Under `type: jwks`, omitting `algorithms` or providing an empty list
  causes an `IllegalArgumentException` at config load. There is no implicit default — operators must
  declare the allowlist explicitly. Supported values include `EdDSA`, `ES256`, `ES384`, `ES512`,
  `RS256`, `RS384`, `RS512`.

- **`did_pattern` `*` is path-segment-bounded.** The wildcard matches a single DID colon-delimited
  segment (`[^:]*` in regex terms). `did:web:example.com:agents:*` matches
  `did:web:example.com:agents:alice` but **not** `did:web:example.com:agents:alice:hijacker`. This
  prevents sub-path hijack where an attacker registers `alice:role:owner` under an operator's fleet
  prefix and matches a broader wildcard. Operators needing multi-segment coverage must list each
  fleet explicitly in `did_allowlist`, or restructure the DID hierarchy so the distinguishing
  segment is the last one. No `**` double-wildcard is available in v1.

- **Exactly one static JWKS source.** Providing more than one of `oidc_discovery`, `jwks_uri`, and
  `jwks_path` causes a startup error (`IllegalArgumentException`). This matches the existing
  mutual-exclusion rule for DID-trust + static-JWKS combinations.

For deeper configuration detail see [Fleet Deployment — Cross-Org did:web Deployments](current/docs/fleet-deployment.md#cross-org-didweb-deployments).

**Trust model:** Under DID trust, the JWT's `iss` claim is the resolution key. Only DIDs matching
`did_allowlist` or `did_pattern` are resolved at all — unrecognised issuers are rejected immediately
with `failureKind=policy` before any network call is made. This limits the resolver's attack surface
to explicitly trusted identity spaces.

**Loose-kid policy:** AgentLair and similar tooling sometimes set a thumbprint-based `kid` in the
JWT header that does not appear as a bare fragment in the DID document's `verificationMethod[].id`.
When `did_loose_kid_match: true` (the default), a mismatched kid is tolerated **only when the
resolved DID document contains exactly one eligible key** — the sole key is used without further
disambiguation. Multi-key documents still require an exact `kid` match regardless of this setting
(the single-key guard prevents "first key wins" behaviour). Set `did_loose_kid_match: false` to
require strict `kid` matching in all cases.

**Mutual exclusion:** `did_allowlist` / `did_pattern` are mutually exclusive with
`oidc_discovery`, `jwks_uri`, and `jwks_path`. Configuring both causes a startup error.

**Cache semantics:** Resolved JWK sets are cached per-issuer DID with an LRU policy
(up to 256 entries) and the same TTL as `cache_ttl_seconds`. The cache is populated lazily at
first verification and refreshed after TTL expiry. The global `stale_on_error` behaviour applies
per-issuer — a JWKS refresh failure falls back to the cached key set when `stale_on_error: true`.

#### Enforcement summary

| Client type | `auditing.enabled: true` | `verifier.type: jwks` |
|-------------|------------------------|-----------------------|
| Claude Code with plugin | Actor presence enforced (hook blocks calls) | JWT verified, result in audit trail |
| Claude Code without plugin | No enforcement | JWT verified, result in audit trail |
| Raw MCP client (HTTP/STDIO) | **No enforcement** — hook does not apply | JWT verified, result in audit trail |

This means that in deployments where non-Claude-Code clients connect to the server, actor presence cannot be relied upon as a security control. The plugin hook is a best-effort guardrail for Claude Code agents, not a server-side security boundary.

### Transport Security

| Transport | Built-in security | Operator responsibility |
|-----------|------------------|----------------------|
| **STDIO** | Process-level isolation (client spawns server) | Ensure the host environment is trusted |
| **HTTP** | None — no TLS, no auth headers, no CORS | Deploy behind a reverse proxy with TLS and authentication, or restrict network access |

**For HTTP deployments in production:** Do not expose the server directly to untrusted networks. Use a reverse proxy (nginx, Caddy, Traefik) with TLS termination and client authentication. The server's `MCP_HTTP_HOST` and `MCP_HTTP_PORT` variables control the bind address — restrict to `127.0.0.1` or a private network interface when possible.

### Data Security

- **SQLite database**: Stored on a Docker volume (`mcp-task-data`) or a local file path. Ensure appropriate file permissions on the host mount. The database is not encrypted at rest — use disk-level encryption if required.
- **Config files**: `.taskorchestrator/config.yaml` is mounted read-only (`:ro`) in Docker. It contains workflow rules and optional JWKS endpoints, not credentials. JWKS URIs point to public key endpoints — no secrets are stored in config.
- **No secrets in actor claims**: The `actor.proof` field should contain a JWT token, not raw credentials. The `claimedBy` field on a `WorkItem` should contain an identifier (session ID, container name, JWT `jti`, or `did:web` identifier), not secrets. These values appear in audit trails and diagnostic tool responses.

### Threat Model Summary

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Cross-tenant data leakage | Separate database files per tenant | Solved by deployment |
| Cross-tenant write contamination | Separate server instances per tenant | Solved by deployment |
| Unauthorized access within a tenant | Not mitigated — all agents in a fleet are peers | By design |
| Actor impersonation (forged claims) | JWKS verification of JWT signatures | Available, opt-in |
| Eavesdropping on HTTP transport | TLS via reverse proxy | Operator responsibility |
| Database file access on host | File permissions on Docker volume mount | Operator responsibility |
| Rogue agent disrupting workflow | Optimistic locking prevents data corruption; `claim_item` enforces exclusive ownership on `advance_item` for claimed items; actor audit trail provides forensics | Partial — operational guardrails, not prevention |

### Future Considerations

- **Verification gating (opt-in)**: A future `auditing.require_verified_actor: true` flag could optionally reject write operations when actor verification fails, converting the accountability layer into an access control layer for high-security deployments. This would apply to write operations only (`advance_item`, `manage_notes`, `claim_item`) and would be opt-in to preserve the current low-friction single-team experience. This is not currently planned — the existing design (accountability, not access control) is intentional and sufficient for the database-per-tenant isolation model.
- **Claim identity and verified actors**: `claim_item` resolves identity via the same `actor` claim used by `advance_item` and `manage_notes`, subject to the deployment's `auditing.degradedModePolicy`. When JWKS verification succeeds, the JWT `sub` becomes authoritative for claim ownership and overrides any per-entry `agentId` supplied on individual claims. Under `degradedModePolicy=reject`, unverified callers cannot place or transition claims. Under `accept-cached` (default), stale-cache verification continues to work during transient JWKS outages. Under `accept-self-reported`, the self-reported `actor.id` is used — this negates the security benefit of JWKS verification when both are configured, and is documented as an explicit opt-out. See [Fleet Deployment Guide](current/docs/fleet-deployment.md#identity-configuration--authdegradedmodepolicy) for cross-org `did:web` recommendations.
