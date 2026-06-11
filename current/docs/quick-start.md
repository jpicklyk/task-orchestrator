# Quick Start: MCP Task Orchestrator

MCP Task Orchestrator gives AI agents persistent, structured task tracking that survives across sessions. Instead of loading your entire project state into context on every prompt, agents read and write a shared graph of `WorkItem` entities — keeping context lean and work visible.

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) installed and running
- [Claude Code CLI](https://claude.ai/code) installed (`claude --version` should work)

---

## Step 1: Pull the Docker image

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

The image is published to GitHub Container Registry. The `latest` tag always points to the most recent release from the main branch.

---

## Step 2: Add to Claude Code

> **Choosing a transport — read this before registering.** This guide sets up the **stdio** transport (Steps 2–3), which is the right choice for most local Claude Code users: the server runs on demand, one process per project, with no ports to manage. If you instead need **HTTP** — for remote clients, container-to-container access, or a single shared long-running server — the registration is different. Skip ahead to [Step 9: Running MCP over HTTP](#step-9-running-mcp-over-http-optional) and register that endpoint *instead of* the stdio one below; Steps 4–8 (plugin, workflow, schemas) then apply unchanged. Step 1 (pulling the image) is shared by both transports. You can switch later by re-registering.

### Option A: CLI (recommended)

Run this once in your terminal:

```bash
claude mcp add-json mcp-task-orchestrator '{
  "command": "docker",
  "args": [
    "run", "--rm", "-i",
    "-v", "mcp-task-data:/app/data",
    "ghcr.io/jpicklyk/task-orchestrator:latest"
  ]
}'
```

This registers the server at the user level. The `mcp-task-data` Docker volume persists the SQLite database across container restarts.

> **Note:** The `-i` flag connects the container's stdin to the MCP client's pipe, which is required for the JSON-RPC stdio transport. If you run this command directly in a terminal (without an MCP client), the container will appear to hang — that is expected, as the server is waiting for JSON-RPC input that never arrives.

### Option B: Project `.mcp.json`

Add to `.mcp.json` in your project root (checked into source control so teammates get it automatically):

```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

The server auto-initializes its schema on first run — no additional setup required.

### Option C: Other MCP clients

Configure your client using the same JSON structure as Option B above. STDIO transport works with any MCP-compatible client.

---

## Step 3: Verify the connection

Restart Claude Code (close and reopen), then run:

```
/mcp
```

You should see `mcp-task-orchestrator` listed as connected, with tools including `manage_items`, `query_items`, `advance_item`, `claim_item`, `manage_notes`, `query_notes`, `manage_dependencies`, `query_dependencies`, `get_next_item`, `get_blocked_items`, `get_next_status`, `get_context`, `create_work_tree`, and `complete_tree`.

If the server shows as disconnected, check that Docker is running and that the image pulled successfully:

```bash
docker images ghcr.io/jpicklyk/task-orchestrator
```

---

## Step 4: Install the plugin

The plugin adds workflow skills, automation hooks, and an orchestrator output style to Claude Code. It is the recommended experience layer for Claude Code users — the sections below describe a workflow shaped by what the plugin provides.

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

After installing, restart Claude Code and run `/plugin list` to confirm `task-orchestrator` is enabled.

> The plugin is optional if you are using a non-Claude-Code MCP client, but for Claude Code it provides the guided workflow experience described below.

### Skills

Skills are invoked as slash commands in any Claude Code session:

| Command | Description |
|---------|-------------|
| `/task-orchestrator:work-summary` | Insight-driven dashboard: active work, blockers, and next actions |
| `/task-orchestrator:create-item` | Create a tracked work item from the current conversation context |
| `/task-orchestrator:quick-start` | Interactive onboarding — teaches by doing, adapts to empty or populated workspaces |
| `/task-orchestrator:manage-schemas` | Create, view, edit, delete, and validate note schemas in config |
| `/task-orchestrator:status-progression` | Navigate role transitions; shows current gate status and the correct trigger |
| `/task-orchestrator:dependency-manager` | Visualize, create, and diagnose dependencies between work items |
| `/task-orchestrator:batch-complete` | Complete or cancel multiple items at once — close out features or workstreams |

### Hooks

Hooks run automatically — no invocation required:

- **Session start** — injects current work context at the beginning of each Claude Code session so you never have to re-orient
- **Plan mode** — after plan approval, prompts Claude to create MCP items so persistent tracking stays in sync with the conversation
- **Subagent start** — passes task context into spawned subagents so they start with full awareness of the current item

**Optional:** Enable actor authentication to require agents to identify themselves on every write operation — add an `actor_authentication` section with `enabled: true` to `.taskorchestrator/config.yaml`. See [Enforcing Actor Attribution](./api-reference.md#enforcing-actor-attribution) in the API reference.

### Output style

The plugin includes a **Workflow Analyst** output style. When active, Claude Code acts as a project management orchestrator — it plans, delegates implementation to subagents, and tracks progress in the WorkItem graph without writing code directly. Useful for complex multi-step features. Select it from the output style menu (`/output-style`) after installing the plugin.

---

## Step 5: How it works — the plan-mode pipeline

When you describe a feature or task, the plugin hooks automate a structured pipeline that keeps your design document and your project board in sync:

```
You describe what you want
        │
        ▼
  EnterPlanMode              ← Claude explores the codebase
        │
  pre-plan hook fires        ← Plugin sets the definition floor: existing work, schemas, gate requirements
        │
        ▼
  Plan written to disk       ← Persistent markdown file — your design document
        │
  Plan approved (ExitPlanMode)
        │
  post-plan hook fires       ← Plugin tells Claude to materialize before implementing
        │
        ▼
  Materialize                ← Claude creates MCP items from the plan
        │                       Items, dependencies, notes — execution tracking
        ▼
  Implement                  ← Subagents work, each transitioning their MCP item
        │                       advance_item(start) → work → advance_item(complete)
        ▼
  Health check               ← get_context() shows what completed and what didn't
```

The plan file and the MCP items are not duplicates — they serve different roles:

- **Plan file** = design document. It captures the what and how: decisions, rationale, scope. It is a readable artifact you can review and share.
- **MCP items** = project board. They track progress and status: what is in flight, what is blocked, what is done. They survive across sessions without any re-explaining.

The MCP also shapes the plan itself. When Claude enters plan mode, the pre-plan hook tells it to check for existing tracked work and note schema requirements — setting a **definition floor**. This means the plan is written with awareness of what documentation gates must be satisfied and what items are already in progress, rather than starting from scratch.

The plugin hooks automate the handoff between these two artifacts. You describe what you want, approve the plan, and the hooks prompt Claude to materialize MCP items before implementation begins. From there, subagents self-report their progress through role transitions.

---

## Step 6: Your first work item

The easiest way to get started is to just tell Claude what you want to build.

**Creating structured work:**

```
You: "I want to build user authentication with a database schema,
      API endpoints, and a login UI."

Claude: → Calls create_work_tree to create a root item and three child items
        → Wires dependency edges so the UI and API must complete before integration tests
        → Shows the structure and which items are immediately actionable
```

**Navigating what to do next:**

```
You: "What should I work on next?"

Claude: → Calls get_next_item() to find the highest-priority unblocked item
        → Reports: "Database schema is ready — no blockers, high priority"
        → Starts working on it, filling notes as it goes
        → Calls advance_item(trigger="complete") when done
```

**Checking overall status:**

```
You: "Where do we stand on the authentication work?"

Claude: → Calls get_context() for a health snapshot
        → Reports: 2 items complete, 1 in progress, 1 blocked on the in-progress item
```

**Under the hood**

The conversational examples above translate to these tool calls:

```
# Build a work tree with children and dependencies in one call
create_work_tree(
  root={title: "Authentication system", type: "feature", priority: "high"},
  children=[
    {ref: "schema", title: "Database schema"},
    {ref: "api",    title: "Auth API endpoints"},
    {ref: "ui",     title: "Login UI"},
    {ref: "tests",  title: "Integration tests"}
  ],
  deps=[
    {from: "api",   to: "tests"},
    {from: "ui",    to: "tests"}
  ]
)

# Find the next thing to work on (highest-priority, dependency-unblocked)
get_next_item()

# Find the next high-priority quick win (complexity ≤ 3)
get_next_item(priority="high", complexityMax=3)

# Transition an item through its lifecycle
advance_item(transitions=[{itemId: "<uuid>", trigger: "start"}])
advance_item(transitions=[{itemId: "<uuid>", trigger: "complete"}])

# Health snapshot across all active work
get_context()
```

See [api-reference.md](api-reference.md) for full parameter documentation on all 14 tools.

---

## Step 7: Session resume

When you start a new Claude Code session, the plugin's session-start hook fires automatically. It injects your current work context so Claude knows what is in flight before you say anything.

```
[New session starts]

Session-start hook fires → injects active items, blockers, and recent transitions

You: "Let's keep going."

Claude: → Already knows: 2 items in progress, 1 blocked, last completed 4 hours ago
        → Picks up exactly where the previous session left off
        → No re-explaining required
```

You can also trigger the dashboard manually at any time:

```
/task-orchestrator:work-summary
```

This calls `get_context()` and `get_blocked_items()` and presents a structured view of active work, blockers, and recommended next actions — useful at the start of a session or after a long implementation run.

---

## Step 7a: Searching content

The MCP server includes FTS5 full-text search backed by SQLite's FTS5 extension (requires SQLite ≥ 3.45, bundled automatically).

**Find items by content:**

```
query_items(operation="search", query="OAuth token")
```

Returns ranked hits with ~32-token snippets from item titles and summaries. Multiple words are
treated as implicit AND. Pass plain terms — special characters are automatically escaped.

**Search within a feature's subtree:**

```
query_items(operation="search", query="migration", scope={ancestorId="<feature-uuid>"})
```

**Search across all note bodies:**

```
query_notes(operation="search", query="rate limiting", limit=10)
```

**Find what references an item (backlinks):**

```
query_dependencies(operation="backlinks", itemId="<uuid>")
```

Returns all items that hold a dependency edge pointing at the given item — useful for impact
analysis and tracing who depends on what.

For the full FTS5 architecture, score interpretation, and `explain=true` usage, see
[`search-and-discovery.md`](./search-and-discovery.md).

---

## Step 8: Note schemas

> **Schemas vs notes:** Schemas are user-configured rules in `.taskorchestrator/config.yaml` — they define what documentation agents must provide at each workflow phase. Notes are the actual content agents write as they work on items. Schemas live in your project config; notes live in the MCP database. Schemas define the gates; agents fill the notes to pass them.

Note schemas enforce per-phase documentation requirements. When an item's `type` or `tags` match a schema, `advance_item` gates progression until the required notes are filled.

Create `.taskorchestrator/config.yaml` in your project root:

```yaml
# Preferred format — supports lifecycle and traits fields
work_item_schemas:
  task-implementation:
    lifecycle: AUTO          # AUTO (default), MANUAL, AUTO_REOPEN, or PERMANENT
    notes:
      - key: acceptance-criteria
        role: queue
        required: true
        description: "Testable acceptance criteria for this task."
      - key: done-criteria
        role: work
        required: true
        description: "What does done look like? How was it verified?"
```

> The legacy `note_schemas:` flat-list format is still accepted and fully backward-compatible. New configs should prefer `work_item_schemas:`.

Items whose `type` is `task-implementation`, or that carry the `task-implementation` tag, will require an `acceptance-criteria` note before `advance_item(trigger="start")` advances them to work, and a `done-criteria` note before `advance_item(trigger="complete")` closes them.

The interactive way to build schemas is the `/task-orchestrator:manage-schemas` skill — it walks you through creating, viewing, editing, and validating schemas without editing YAML directly.

After adding or editing this file, reconnect the MCP server:

```
/mcp  (disconnect and reconnect mcp-task-orchestrator)
```

> **Docker:** To read this config file, mount the `.taskorchestrator/` folder into the container. Add this to your project-level `.mcp.json` (not the global CLI registration — a globally-registered server should not have its schema config vary per project):
> ```json
> {
>   "mcpServers": {
>     "mcp-task-orchestrator": {
>       "command": "docker",
>       "args": [
>         "run", "--rm", "-i",
>         "-v", "mcp-task-data:/app/data",
>         "-v", "${workspaceFolder}/.taskorchestrator:/project/.taskorchestrator:ro",
>         "-e", "AGENT_CONFIG_DIR=/project",
>         "ghcr.io/jpicklyk/task-orchestrator:latest"
>       ]
>     }
>   }
> }
> ```
> Only the `.taskorchestrator/` folder is exposed — the server has no access to the rest of your project.
>
> **Using JWKS path verification?** Also mount `.agentlair/` so the container can read your local JWKS file:
> ```json
> "-v", "${workspaceFolder}/.agentlair:/project/.agentlair:ro",
> ```
> Add this line alongside the `.taskorchestrator` mount. The `.agentlair/` mount is only needed when `verifier.jwks_path` is configured. See [Actor Authentication & Verification](./api-reference.md#actor-authentication--verification) in the API reference for details including Docker network access options.

---

## Key concepts

| Concept | Description |
|---------|-------------|
| `WorkItem` | The core entity. Has a `role` (queue/work/review/terminal/blocked), `type` (e.g. `feature`, `task`, `bug`), `priority`, `tags`, `depth` (0-3), optional `parentId`, and a `properties` map for custom metadata. |
| `Note` | Key-value text attached to an item. Has a `role` indicating which workflow phase it belongs to. |
| `Dependency` | Directed edge between items: `BLOCKS`, `IS_BLOCKED_BY`, or `RELATES_TO`. |
| Role progression | Items advance via triggers: `start` (queue→work, work→review), `complete` (any→terminal), `block`/`hold` (any→blocked), `resume` (blocked→previous). |
| Note schema gating | When enabled, `advance_item` checks required notes exist and are non-empty before allowing phase transitions. Schema is resolved by `type` first, then tags, then the `default` schema. |

---

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_PATH` | `data/current-tasks.db` | SQLite file path inside the container |
| `USE_FLYWAY` | `true` | Apply database migrations on startup |
| `AGENT_CONFIG_DIR` | _(unset)_ | Parent directory of `.taskorchestrator/`; set when mounting a config folder into the container |
| `LOG_LEVEL` | `INFO` | Verbosity: `DEBUG`, `INFO`, `WARN`, `ERROR` |

---

---

## Step 9: Running MCP over HTTP (optional)

By default the server speaks the **stdio** transport (Step 2). To serve MCP over **HTTP** instead — for remote clients, container-to-container access, or a shared long-running server — set `MCP_TRANSPORT=http`. The MCP endpoint is mounted at `/mcp` using the Streamable HTTP transport.

```bash
docker run --rm -p 127.0.0.1:3001:3001 \
  -v mcp-task-data:/app/data \
  -v "$(pwd)/.taskorchestrator:/project/.taskorchestrator:ro" \
  -e MCP_TRANSPORT=http \
  -e MCP_HTTP_PORT=3001 \
  -e AGENT_CONFIG_DIR=/project \
  -e API_ENABLED=false \
  ghcr.io/jpicklyk/task-orchestrator:latest
```

**Register the HTTP endpoint with Claude Code:**

```bash
claude mcp add --transport http mcp-task-orchestrator-http http://localhost:3001/mcp
```

Or add it to a project `.mcp.json`:

```json
{
  "mcpServers": {
    "mcp-task-orchestrator-http": {
      "type": "http",
      "url": "http://localhost:3001/mcp"
    }
  }
}
```

Restart Claude Code and run `/mcp` to confirm the connection and all 14 tools. Other MCP clients should target the same `http://localhost:3001/mcp` URL using the Streamable HTTP transport.

**HTTP transport environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `MCP_TRANSPORT` | `stdio` | Set to `http` to serve the Streamable HTTP transport instead of stdio. |
| `MCP_HTTP_PORT` | `3001` | Port the server listens on (inside the container for Docker). |
| `MCP_HTTP_HOST` | `0.0.0.0` | Interface the server binds. Leave at `0.0.0.0` for Docker — the container is network-isolated, so control host exposure via the `-p` mapping (publish to `127.0.0.1` as shown above). For a direct, non-Docker JAR run, set `127.0.0.1` to bind loopback only. |
| `API_ENABLED` | `false` | REST API off by default (MCP-only). Set `true` to opt into the REST API (Step 10), which then requires `API_AUTH_MODE`. |
| `AGENT_CONFIG_DIR` | working dir | Directory containing `.taskorchestrator/config.yaml` (schema config). Set to the mounted project path. |

> **The REST API is off by default.** `API_ENABLED` defaults to `false`, so MCP-over-HTTP needs no API configuration — the example sets it explicitly only for clarity. Opt into the REST API (Step 10) with `API_ENABLED=true`, which then *hard-requires* `API_AUTH_MODE` (omitting it is a fatal startup error).

> **Schema config works identically over HTTP.** `.taskorchestrator/config.yaml` is loaded via `AGENT_CONFIG_DIR` regardless of transport — the `-v ...:/project/.taskorchestrator:ro` mount above gives the HTTP server the same schemas it would have over stdio. (An HTTP server resolves a single project's config; run one server per project.)

The `docker compose --profile http up` service in `docker-compose.yml` is pre-configured this way.

### HTTP transport security

> ⚠️ **SECURITY: `/mcp` over HTTP is UNAUTHENTICATED.** The Streamable HTTP transport has **no built-in authentication**. Anyone who can reach `MCP_HTTP_HOST:MCP_HTTP_PORT` gets **full read / write / delete** access to every MCP tool. Enabling the REST API (Step 10) does **not** protect `/mcp` — REST bearer/JWKS auth gates `/api/v1/*` only; `/mcp` stays open by design so MCP clients (which send no REST token) can still connect. The server logs a loud `SECURITY:` warning at startup whenever `MCP_TRANSPORT=http`. **Never expose this port to untrusted callers** — front it with an authenticating reverse proxy / mTLS, or keep it on a private network or loopback.

The Streamable HTTP transport follows the [MCP transport security requirements](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#security-warning):

- **Origin validation is enforced.** The server validates the `Origin` header and rejects cross-origin browser requests with `403 Forbidden` (DNS-rebinding protection). Non-browser MCP clients (Claude Code and other CLI agents) send no `Origin` and are unaffected.
- **Bind to loopback when running locally.** For a direct (non-Docker) JAR run, set `MCP_HTTP_HOST=127.0.0.1` so the server binds loopback only. The default is `0.0.0.0` because Docker port-mapping requires the server to bind all interfaces *inside* the container — control host exposure via the `-p` mapping instead (the examples above publish as `127.0.0.1:3001:3001` so the port is reachable only from the local host). Use `-p 3001:3001` only when you intentionally need access from other hosts, and only behind a reverse proxy / mTLS.
- **The `/mcp` endpoint is unauthenticated** (see the warning above). Keep it on a trusted network boundary, or front it with an authenticating reverse proxy, before exposing it to untrusted callers.

---

## Step 10: Enabling the REST API (optional)

The REST API adds HTTP endpoints under `/api/v1` for dashboards, CI systems, and operators who want to read or write work items without an MCP client. It runs on the **same** HTTP server as the MCP transport (Step 9) — `/mcp` keeps serving MCP clients while `/api/v1/*` serves REST. So you enable it on top of an `MCP_TRANSPORT=http` container.

The API is **off by default**, and when enabled it **always requires authentication** (there is no unauthenticated mode). This walkthrough uses **bearer-token** auth — the simplest option for local use. For JWT/JWKS, see [api-rest.md](api-rest.md).

> **You never store your plaintext token.** The server only keeps its **SHA-256 hash**. You generate a token once, keep the plaintext somewhere safe (e.g. a password manager), and put only the hash in `api-tokens.yaml`. You send the plaintext in each request.

### 1. Generate a token and its hash

Run the snippet for your platform. It prints a random **token** (you send this in requests) and its **SHA-256 hash** (this goes in the file).

**macOS / Linux / Git Bash:**

```bash
TOKEN=$(openssl rand -hex 32)
printf 'TOKEN   (save this securely): %s\n' "$TOKEN"
printf 'SHA-256 (paste into the yaml): %s\n' "$(printf '%s' "$TOKEN" | openssl dgst -sha256 | awk '{print $NF}')"
```

**Windows (PowerShell):**

```powershell
$bytes = New-Object 'System.Byte[]' 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = ([System.BitConverter]::ToString($bytes) -replace '-','').ToLower()
$hash  = ([System.BitConverter]::ToString(
  [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes($token))) -replace '-','').ToLower()
"TOKEN   (save this securely): $token"
"SHA-256 (paste into the yaml): $hash"
```

> **Hashing gotcha (the #1 cause of `401`s):** the hash must cover the token's **exact bytes with no trailing newline**. The commands above are correct. Do **not** use `openssl dgst -sha256 <<< "token"` or `echo "token" | openssl dgst -sha256` — `<<<` and `echo` append a newline, producing a hash that never matches.

### 2. Create the token file

Save a file named `api-tokens.yaml` containing the **hash** (not the token):

```yaml
version: 1
tokens:
  - id: local-dev
    description: "Local developer token"
    token_sha256: "PASTE_THE_SHA256_FROM_STEP_1_HERE"
    capabilities:
      - read
      - write-items
      - write-notes
      - advance
      - manage-dependencies
```

`capabilities` controls what the token may do. Valid values: `read`, `write-items`, `write-notes`, `advance`, `manage-dependencies`, `admin`. For a read-only dashboard token use just `- read`. (`admin` additionally exposes actor/verification attribution fields — omit it unless you need that.)

### 3. Start the server with the API enabled

This mounts your token file into the container and turns the API on. Run it from the folder where you saved `api-tokens.yaml`.

**macOS / Linux / Git Bash:**

```bash
docker run -d --name task-orchestrator-http --restart unless-stopped \
  -p 127.0.0.1:3001:3001 \
  -v mcp-task-data:/app/data \
  -v "$(pwd)/api-tokens.yaml:/run/secrets/api-tokens.yaml:ro" \
  -e MCP_TRANSPORT=http \
  -e MCP_HTTP_PORT=3001 \
  -e API_ENABLED=true \
  -e API_AUTH_MODE=bearer \
  -e API_TOKENS_PATH=/run/secrets/api-tokens.yaml \
  ghcr.io/jpicklyk/task-orchestrator:latest
```

**Windows (PowerShell):**

```powershell
docker run -d --name task-orchestrator-http --restart unless-stopped `
  -p 127.0.0.1:3001:3001 `
  -v mcp-task-data:/app/data `
  -v "${PWD}\api-tokens.yaml:/run/secrets/api-tokens.yaml:ro" `
  -e MCP_TRANSPORT=http `
  -e MCP_HTTP_PORT=3001 `
  -e API_ENABLED=true `
  -e API_AUTH_MODE=bearer `
  -e API_TOKENS_PATH=/run/secrets/api-tokens.yaml `
  ghcr.io/jpicklyk/task-orchestrator:latest
```

What the key flags do:

- `-p 127.0.0.1:3001:3001` — publishes the server on your machine's **loopback only**, not the wider network. Use `-p 3001:3001` only if other machines must reach it.
- `-v "<host>/api-tokens.yaml:/run/secrets/api-tokens.yaml:ro"` — mounts your token file read-only where `API_TOKENS_PATH` points. The **left** side is the host path (`$(pwd)` / `${PWD}` expands to an absolute path for you); the **right** side is the in-container path.
- `-e API_ENABLED=true -e API_AUTH_MODE=bearer` — turns the REST API on in bearer mode.
- **Docker Desktop:** the folder holding `api-tokens.yaml` must be allowed under **Settings → Resources → File sharing** (your home/project directory is shared by default).
- **Schema config (optional):** to also load note schemas from `.taskorchestrator/config.yaml`, add `-v "$(pwd)/.taskorchestrator:/project/.taskorchestrator:ro"` and `-e AGENT_CONFIG_DIR=/project` (as in Step 9). The REST API works without it (schema-free).

If the API is enabled but the token file is missing, empty, or malformed, the server **exits on startup** — check `docker logs task-orchestrator-http`.

### 4. Test it

```bash
# Health — no auth required
curl http://localhost:3001/api/v1/health

# Read items — send your PLAINTEXT token from step 1 as the bearer
curl -H "Authorization: Bearer YOUR_PLAINTEXT_TOKEN" \
     http://localhost:3001/api/v1/items

# Create an item (requires the write-items capability)
curl -X POST http://localhost:3001/api/v1/items \
  -H "Authorization: Bearer YOUR_PLAINTEXT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "New task from REST API", "priority": "high"}'
```

Expected: `/api/v1/health` → `200`; the authenticated calls **without** the header → `401`; a `read`-only token on the POST → `403`.

### Notes & troubleshooting

- **`401` on every authenticated call** almost always means the hash in `api-tokens.yaml` doesn't match your token — re-run step 1 (usually the trailing-newline gotcha).
- **The `/mcp` endpoint stays open** even with the API enabled (MCP clients don't send the REST token). Keep the server on a trusted boundary — the `127.0.0.1` binding above does this.
- **Rotating a token:** generate a new one (step 1), replace `token_sha256`, then restart the container (`docker restart task-orchestrator-http`). Tokens are read once at startup.
- **Docker Compose alternative:** the `http` profile in `docker-compose.yml` runs an MCP-only HTTP server; to enable the REST API there, add the same `API_ENABLED`/`API_AUTH_MODE`/`API_TOKENS_PATH` env vars and the `api-tokens.yaml` mount to that service.

See [api-rest.md](api-rest.md) for full endpoint documentation, capabilities and scopes, JWKS mode, DTOs, merge-patch semantics, ETag/idempotency, SSE, and the complete env-var reference.

---

## What's next

- Run `/task-orchestrator:quick-start` for an interactive hands-on tutorial
- [api-reference.md](api-reference.md) — full reference for all 14 MCP tools, parameters, and response shapes
- [workflow-guide.md](workflow-guide.md) — note schemas, phase gates, dependency patterns, and lifecycle examples
- [api-rest.md](api-rest.md) — REST API reference: endpoints, DTOs, SSE, auth
