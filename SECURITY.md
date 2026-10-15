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

MCP Task Orchestrator establishes a caller's identity at the transport layer and has no user or role model above it:

- **STDIO transport**: The MCP client spawns the server process directly. The client is implicitly trusted - it controls the process lifecycle and database path - and every call runs as the single `local` principal.
- **HTTP transport**: The server binds to a configurable host and port (default `0.0.0.0:3001`). **There is no built-in TLS.** `/mcp` authenticates its callers when the REST API runs a credential mode (`API_AUTH_MODE=bearer` or `jwks`): it requires the same `Authorization: Bearer` credential as `/api/v1`, binds each MCP session to the principal that opened it, and refuses another principal on that session. With the REST API disabled, HTTP starts only if the operator opts in with `MCP_ALLOW_UNAUTHENTICATED=true`, and `/mcp` then has **no authentication** and serves every caller as the `local` principal; `API_AUTH_MODE=none` does the same for both surfaces. In those local modes, ensure the server is only accessible from trusted clients - deploy behind a reverse proxy with TLS and authentication, or restrict network access to trusted hosts.

A credential carries capabilities and a scope, and those gate what its holder may do (see below). Without credentials, there is no permission model: within a tenant's database every caller is the same `local` principal, so cooperating agents are peers with equal access, and they share claims and note ownership.

**This is intentional for the local modes.** Within a cooperating fleet, access control between agents adds friction without meaningful security benefit - the agents are working toward the same goals. Tenant isolation is achieved at the infrastructure level (separate databases), not the application level. Fleets that need to tell callers apart use credentials.

### REST API Authentication

The optional REST API (`/api/v1/*`, opt-in via `API_ENABLED=true`) selects its authentication with `API_AUTH_MODE`, and `/mcp` over HTTP uses the same credential in `bearer` and `jwks` modes. Full semantics: [`current/docs/api-rest.md`](current/docs/api-rest.md) §1.

| Mode | Identity | Capability model |
|------|----------|-------------------|
| `bearer` | Static token, hashed (SHA-256) at rest in a YAML file | Per-token `capabilities` (`read`, `write-items`, `write-notes`, `write-config`, `write-docs`, `advance`, `manage-dependencies`, `admin`) and optional `scope.root_ids` |
| `jwks` | JWT verified against a JWKS endpoint (`API_JWKS_URL`) | Same capability model, driven by JWT claims |
| `none` (opt-in, requires `API_ALLOW_UNAUTHENTICATED=true` in addition) | None - every request, REST or `/mcp`, runs as the one `local` `ADMIN` principal | Unrestricted: full read/write/delete access to every item, note, and per-root config, with no scope restriction |

**The unauthenticated mode's threat boundary is the network, not the application.** `API_AUTH_MODE=none` deliberately grants unconditional `ADMIN` access — there is no capability check to bypass because there is no capability restriction at all. This mode is safe **only** when the port is unreachable from anyone but the operator's own machine. Reaching that boundary takes two independent things: how the port is published, and what `Host` values the server accepts once a request does arrive.

- **The primary safety mechanism is `-p 127.0.0.1:3001:3001`** (Docker's port-publish mapping to loopback), not `MCP_HTTP_HOST`. Publishing wider (e.g. `-p 3001:3001` or `-p 0.0.0.0:3001:3001`) while `API_AUTH_MODE=none` exposes an unauthenticated read/write/delete API to the entire reachable network — the `Host` allowlist below does not substitute for a correct publish mapping.
- **`MCP_HTTP_HOST` must stay `0.0.0.0`.** The container binds all interfaces *inside its own network namespace* by design — this is required for Docker's port-publish mechanism to reach the process at all. Setting `MCP_HTTP_HOST=127.0.0.1` to "harden" it instead binds loopback inside the container's namespace, which breaks the docker-proxy path from the host and can make the server unreachable even via `127.0.0.1:3001` on the host.
- **A loopback publish alone does not stop DNS rebinding** — a page loaded from an attacker-controlled hostname that resolves to `127.0.0.1` (or is rebound to it after load) can make the victim's own browser send a same-origin request straight through the loopback publish. A `Host`-header allowlist closes that gap independently of the publish mapping: every HTTP request (`/mcp` and every `/api/v1` route) is rejected with `403` and the standard error envelope (`forbidden`, scope `host`; the same body on `/mcp`, every `/api/v1` route and `/.well-known`, and it never echoes the rejected `Host` or the allowlist) unless its `Host` is `localhost`/`127.0.0.1`/`[::1]` (any port, the always-on default) or listed in `MCP_ALLOWED_HOSTS`. A request with no `Host` header at all is allowed, since browsers always send one. This is enforced by the server regardless of `API_AUTH_MODE`; see [`current/docs/fleet-deployment.md`](current/docs/fleet-deployment.md).
- Operators who need unauthenticated access reachable from more than one host should use `bearer` or `jwks` mode instead — the unauthenticated mode is scoped to single-operator, loopback-only, local use (see [`claude-plugins/task-orchestrator/skills/configure-server/references/runtime-config.md`](claude-plugins/task-orchestrator/skills/configure-server/references/runtime-config.md), and [`current/docs/fleet-deployment.md`](current/docs/fleet-deployment.md) for multi-caller topologies).

### Actor Attribution and Identity

The actor of every write is the **transport principal**, never a request field. A bearer or JWT credential names its holder (audited `api:<id>`); stdio, `/mcp` in a local mode and REST `API_AUTH_MODE=none` run as the single `local` principal. The server refuses a body `actor` on every tool as `unknown_parameter`, so a caller cannot assert an identity of its own, and verification fails closed: a bad credential is `401`, and a JWKS key set that cannot be had is `503 unavailable`, never a guessed identity. Claim ownership and note ownership are enforced against the principal for every client.

- **Client-side hook.** The Claude Code plugin's `enforce-actor-attribution` hook predates this model: it is client-side only, never a server control, and is removed in a later plugin release. A 4.0 server rejects a body `actor` whatever the hook does.
- **`actor_authentication` config is removed.** A leftover `actor_authentication:` block in the global config, or a set `DEGRADED_MODE_POLICY`, fails startup, because the block authenticated and attributed no call (no request carries an actor or an `actor.proof` any more). Configure caller authentication with `API_AUTH_MODE` and the `API_JWKS_*` variables. See [`current/docs/fleet-deployment.md`](current/docs/fleet-deployment.md#actor_authentication-removed-in-40).
- **One principal per credential.** Callers that share a credential, or that all run as `local`, are one principal: they share claims and note ownership. Give each agent its own credential, or its own run seat token, to separate them.
- **Claim write fence.** A write to an item whose claim is ACTIVE and held by another principal (a transition, note write, item update or delete, or dependency write) is refused `not_claim_holder`, on MCP and REST alike. The fence ends when the claim expires or is released. It orders cooperating principals; it is not a secrecy control, and principals sharing one credential are one principal.
- **Note ownership and legacy adoption.** A note is owned by the principal that first wrote it; another principal is refused `note_owned_by_other` unless the key is `shared: true` or an admin passes `takeover`. Notes written before 4.0 are unowned until their first 4.0 upsert, which makes the writer the owner and is audited (`note.ownership_transferred` with `from: null` and the 3.x actor id as `legacyActor`), so until then the 3.x declared actor is not trusted as an owner.
- **Proof evidence from earlier versions.** Since the `V17__Store_Actor_Proof_Evidence.sql` migration the raw `actor.proof` JWT is not persisted - only a SHA-256 hash and, for a verified proof, the verified claims, kept for forensics and returned by no tool. Rows and backups from before that migration may still hold live tokens until each token's `exp`; see fleet-deployment.md, "Proof handling".

### Transport Security

| Transport | Built-in security | Operator responsibility |
|-----------|------------------|----------------------|
| **STDIO** | Process-level isolation (client spawns server) | Ensure the host environment is trusted |
| **HTTP - `/mcp`** | No TLS. Bearer or JWT authentication in a credential mode (same credential as `/api/v1`); none in a local mode (`MCP_ALLOW_UNAUTHENTICATED=true` with the API off, or `API_AUTH_MODE=none`) | Terminate TLS at a reverse proxy; in a local mode also authenticate there or restrict network access |
| **HTTP — `/api/v1/*`** (REST, opt-in) | `API_AUTH_MODE` gates every request (`bearer`, `jwks`, or opt-in `none`) — see "REST API Authentication" above | Same network exposure responsibility as `/mcp`; `none` mode additionally depends on loopback-only port publishing |

**One credential covers both endpoints.** With `API_AUTH_MODE=bearer` or `jwks`, `/mcp` and `/api/v1/*` accept the same bearer credential, and a refusal on `/mcp` is the JSON error envelope, not a JSON-RPC error. A `Host` allowlist is a DNS-rebinding guard, not authentication. With the API disabled, `/mcp` is authenticated only if you front it yourself: HTTP refuses to start unless `MCP_ALLOW_UNAUTHENTICATED=true`.

**For HTTP deployments in production:** Do not expose the server directly to untrusted networks. Use a reverse proxy (nginx, Caddy, Traefik) with TLS termination and client authentication. The server's `MCP_HTTP_HOST` and `MCP_HTTP_PORT` variables control the bind address inside the container's network namespace — for Docker deployments, host-level exposure is controlled by the `-p` port-publish mapping instead (see "REST API Authentication" above); for a direct non-Docker JAR run, restrict `MCP_HTTP_HOST` to `127.0.0.1` or a private network interface when possible.

### Data Security

- **SQLite database**: Stored on a Docker volume (`mcp-task-data`) or a local file path. Ensure appropriate file permissions on the host mount. The database is not encrypted at rest — use disk-level encryption if required.
- **Config files**: `.taskorchestrator/config.yaml` is mounted read-only (`:ro`) in Docker. It contains workflow rules and optional JWKS endpoints, not credentials. JWKS URIs point to public key endpoints — no secrets are stored in config.
- **No secrets in identifiers**: Credential ids and JWT `sub` values become the principal id (`api:<id>`) and appear in audit trails, the `claimedBy` field of a `WorkItem` and diagnostic tool responses, so use opaque identifiers, not secrets or personal data.
- **Actor proofs are not stored verbatim**: since the `V17__Store_Actor_Proof_Evidence.sql` migration, rows never persist a raw `actor.proof` JWT - only a SHA-256 hash and (when verified) the verified claims, returned by no tool. Pre-upgrade backups, and free space in the live file left from before that migration, may still hold live tokens until each token's own `exp`. Rotate long-lived keys/tokens (the only remedy reaching every copy), then purge old backups; compaction of the live file (VACUUM + FTS5 rebuild) runs automatically once on the first Flyway-mode start after upgrading (`DB_COMPACT_ON_UPGRADE=false` to opt out; offline runbook in fleet-deployment.md, "Proof handling").

### Threat Model Summary

| Threat | Mitigation | Status |
|--------|-----------|--------|
| Cross-tenant data leakage | Separate database files per tenant | Solved by deployment |
| Cross-tenant write contamination | Separate server instances per tenant | Solved by deployment |
| Unauthorized access within a tenant | Credential modes gate callers by capability and scope; in a local mode all callers are one peer `local` principal | By design |
| Caller impersonation | The credential establishes the principal; no request carries a self-asserted identity, and JWT signatures are verified against the JWKS | Available in credential modes |
| Eavesdropping on HTTP transport | TLS via reverse proxy | Operator responsibility |
| Database file access on host | File permissions on Docker volume mount | Operator responsibility |
| Rogue agent disrupting workflow | Optimistic locking prevents data corruption; claim ownership is enforced against the principal; the audit trail attributes each write to its principal | Partial — operational guardrails, not prevention |

### Future Considerations

- **Claim identity**: claim ownership is enforced against the transport principal (`api:<id>`, or `local`); since 4.0 no request carries an identity of its own and verification fails closed. A JWT verified against a fresh or stale (within `API_JWKS_MAX_STALE_SECONDS`) key set is the principal and its `sub` is authoritative for claim ownership; a rejected JWT is `401`, and a JWT whose key set is unavailable is `503 unavailable`, never a guessed identity. There is no degraded-mode policy: `actor_authentication.degraded_mode_policy` and `DEGRADED_MODE_POLICY` were removed and fail startup. See [Fleet Deployment Guide](current/docs/fleet-deployment.md#identity-configuration).
