# Runtime Config Catalog

This is the **rendering catalog** for `/configure-server` (and the fragment source `deploy_to_docker`
cites): the exact env fragments, tuples, and caveats to render for each runtime choice. It is not a
tutorial and not a security explainer — for env *semantics* (what each flag does, auth model details,
capability model) this doc defers to:

- [`current/docs/api-rest.md`](../../../../../current/docs/api-rest.md) §1 (Authentication) — authoritative for REST auth modes
- [`current/docs/fleet-deployment.md`](../../../../../current/docs/fleet-deployment.md) — authoritative for fleet/security posture

If this catalog and either of those docs ever disagree, those docs win — fix this file to match.

> **Anti-drift note:** this file is cited by `configure-server/SKILL.md`,
> `.claude/commands/deploy_to_docker.md`, and a comment in `docker-compose.yml`'s `http-rest`
> profile. If this file is renamed or moved, update all of those citations — grep the repo root for
> `runtime-config.md` and fix every hit (aside from this file's own self-reference).

---

## The recommended default tuple (new installs)

Localhost + HTTP + REST enabled + no auth. This is the tuple `/configure-server` renders as the
one-tap default:

```
-e MCP_TRANSPORT=http -e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true -p 127.0.0.1:3001:3001
```

Plus, on the client side (required — see "Loopback footgun" and "config-sync" below):

```
TASK_ORCHESTRATOR_API_URL=http://localhost:3001
```

`MCP_HTTP_HOST` is **not** part of this tuple — it stays at its image default (`0.0.0.0`). See below.

---

## Env-fragment table (compose into a `docker run` command)

| Choice | Fragment |
|--------|----------|
| Transport: HTTP | `-e MCP_TRANSPORT=http -e MCP_HTTP_HOST=0.0.0.0 -e MCP_HTTP_PORT=3001` |
| Transport: STDIO | *(no HTTP env — use `docker run --rm -i`, no `-p`, no REST)* |
| REST off | `-e API_ENABLED=false` |
| REST unauthenticated (recommended) | `-e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true` |
| REST bearer | `-e API_ENABLED=true -e API_AUTH_MODE=bearer -e API_TOKENS_PATH=/run/secrets/api-tokens.yaml -v <TOKENS_HOST_PATH>:/run/secrets/api-tokens.yaml:ro` |
| Config mount = global floor (recommended) | `-v "<pwd>/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro" -e AGENT_CONFIG_DIR=/project` |
| Config mount = none (multi-project via config-sync) | *(omit)* |
| Debug on | `-e LOG_LEVEL=DEBUG -e DATABASE_SHOW_SQL=true` |
| Port publish (loopback only) | `-p 127.0.0.1:3001:3001` |

## REST-mode env tuples (full, copy-paste)

**Off** (MCP only, no REST):
```
-e API_ENABLED=false
```

**Unauthenticated (recommended for single-developer local use):**
```
-e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true
```
Both keys are required together — `api-rest.md` §1 "Unauthenticated Mode" — `API_AUTH_MODE=none` alone
still fails startup; `API_ALLOW_UNAUTHENTICATED=true` alone (with `bearer`/`jwks`) is silently ignored.

**Bearer (token file):**
```
-e API_ENABLED=true -e API_AUTH_MODE=bearer -e API_TOKENS_PATH=/run/secrets/api-tokens.yaml \
-v <host-path-to-api-tokens.yaml>:/run/secrets/api-tokens.yaml:ro
```
See `api-rest.md` §1 for the token file format and hash generation.

---

## Loopback footgun (must always be rendered alongside unauthenticated REST)

> **SECURITY:** unauthenticated REST means anyone who can reach the port has full read/write/delete
> access to every item, note, and per-root config — identical to the existing `/mcp` exposure. This
> is only safe when the port is **published loopback-only**.

**The safety mechanism is the Docker port-publish mapping, not the server bind:**

- Render `-p 127.0.0.1:3001:3001` — this is what makes the port unreachable from outside the host.
- **Leave `MCP_HTTP_HOST=0.0.0.0` alone.** The container must bind all interfaces *inside* its own
  network namespace for Docker's port-publish mechanism to reach it at all — that bind is internal to
  the container and is not the same thing as host-network exposure. Setting `MCP_HTTP_HOST=127.0.0.1`
  binds loopback *inside the container's own namespace*, which breaks the docker-proxy path from the
  host and makes the server unreachable even via `127.0.0.1:3001` on the host. This has bitten people
  who "hardened" the wrong knob — don't repeat it.
- Never publish with `-p 3001:3001` (all interfaces) when REST is unauthenticated.

This mirrors the existing `/mcp` guidance already documented in `current/docs/quick-start.md` (Step 9,
HTTP transport security) and `current/docs/fleet-deployment.md` — both already establish that host
exposure is controlled by the `-p` mapping, not `MCP_HTTP_HOST`, for Docker deployments.

---

## config-sync — the easy way to silently no-op it

`config-sync` (the SessionStart hook, `config-sync.mjs`) pushes this workspace's
`.taskorchestrator/config.yaml` into the server's per-root config store over the REST API. It requires,
on the **client side** (the environment the Claude Code session itself runs in — not the container):

```
TASK_ORCHESTRATOR_API_URL=http://localhost:3001
TASK_ORCHESTRATOR_API_TOKEN=<token>   # bearer mode only; omit entirely for unauthenticated mode
```

Verified in `config-sync.mjs` (the `TASK_ORCHESTRATOR_API_URL` guard in `main()`): if `TASK_ORCHESTRATOR_API_URL` is unset, the hook returns
immediately with no error and no log — it silently no-ops. There is no visible symptom other than
per-project config never showing up server-side. **Always render this env var alongside the HTTP+REST
docker run command** — it is easy to forget because it's set outside the container, not inside it.

config-sync also requires the workspace's `.taskorchestrator/config.yaml` to carry a `project:` block
with a `rootId` (project-scoping) — without it, the hook has no root to push to and no-ops even with
the API URL set. See `manage-schemas/references/config-format.md` → "Project Scoping" (owned by
`manage-schemas`, out of scope here — mention only, don't duplicate).

---

## Windows / MSYS path caveat

Run the `docker run` command (HTTP or STDIO) via **PowerShell**, using `${PWD}` for volume paths — not
the Bash tool / Git Bash. MSYS rewrites a leading slash in arguments like `-e AGENT_CONFIG_DIR=/project`
and in mount specs (`:/app/data`, `:/project/...`), silently breaking the container's env/mounts. This
does not affect `docker build`, `docker inspect`, `docker logs`, or `docker stop`/`rm` — only `docker run`
invocations that pass `/`-rooted values as `-e`/`-v` arguments.

---

## `.mcp.json` shapes (do not conflate)

**STDIO** — args array:
```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "command": "docker",
      "args": ["run", "--rm", "-i", "-v", "mcp-task-data:/app/data", "ghcr.io/jpicklyk/task-orchestrator:latest"]
    }
  }
}
```

**HTTP** — `type: http` entry, no args array:
```json
{
  "mcpServers": {
    "mcp-task-orchestrator": {
      "type": "http",
      "url": "http://localhost:3001/mcp"
    }
  }
}
```
