---
name: configure-server
description: "Configures how the MCP Task Orchestrator SERVER runs and is reached — transport (HTTP vs STDIO), the REST API, port publishing, config mounts, and config-sync. Use when a user says: run the server, register the image, set up the Docker container, enable the REST API, set up config-sync, reconfigure the server, change transport, expose the API, or reconnect to a different endpoint. NOT for first-time onboarding (that's quick-start) and NOT for note schemas / gates / traits / actor_authentication policy (that's manage-schemas) — this skill only decides how the container is launched and reached."
argument-hint: "[optional: 'recommended', 'http', 'stdio', 'bearer', or a change request e.g. 'enable REST']"
---

# Configure Server — Runtime & Transport Setup

Decides **how the MCP Task Orchestrator container is launched and reached**: transport, REST API mode,
port publishing, config mount, and config-sync. This is a runtime/deployment concern, distinct from
`quick-start` (first-time onboarding narrative) and `manage-schemas` (workflow gates/traits/
`actor_authentication` content inside `.taskorchestrator/config.yaml`). If the user wants schema or
gate changes, redirect to `/manage-schemas` instead of proceeding here.

The full fragment catalog (exact env tuples, loopback caveat, Windows/MSYS caveat, `.mcp.json` shapes)
lives in `references/runtime-config.md` — this skill's job is the **decision flow** and **rendering**,
not re-deriving that catalog. Read it before rendering any command.

---

## Step 1 — Offer the recommended default first

Before walking the full decision tree, offer the one-tap recommended path via `AskUserQuestion`:

```
AskUserQuestion(questions: [{
  question: "How do you want to run the server?",
  header: "Server setup",
  multiSelect: false,
  options: [
    { label: "Recommended default", description: "HTTP + REST API enabled, unauthenticated, loopback-bound (127.0.0.1). Enables config-sync out of the box. Best for a single developer working across multiple projects." },
    { label: "Customize", description: "Walk through transport, REST mode, config mount, and debug logging one at a time." }
  ]
}])
```

**Recommended default** → skip straight to Step 5 (Render — HTTP) with: REST = unauthenticated,
config mount = none, debug = off. **Customize** → Step 2.

Why this is the default: it is the majority deployment shape for a single developer who wants
`config-sync` (per-project config that hot-reloads without a restart) to just work across every
project they open, without hand-managing tokens. The image itself still ships conservative (STDIO,
REST off, `0.0.0.0` bind) — this posture is entirely rendered by this skill, never an image change.

---

## Step 2 — Transport

```
AskUserQuestion(questions: [{
  question: "Which transport?", header: "Transport", multiSelect: false,
  options: [
    { label: "HTTP (Recommended)", description: "Detached daemon, serves /mcp on a published port. Required for REST API and config-sync." },
    { label: "STDIO", description: "Per-session process, no port, no REST API, no config-sync. Simpler, no persistent daemon." }
  ]
}])
```

**STDIO ⊥ REST — hard constraint:** if the user picks STDIO, skip Steps 3-4 entirely (REST mode and
port are incoherent for a `--rm -i` per-session process) and go straight to Step 5 (Render — STDIO).
Tell the user plainly: *"STDIO has no REST API and no config-sync — those require a persistent HTTP
daemon. If you want config-sync later, re-run this skill and choose HTTP."*

HTTP → Step 3.

---

## Step 3 — REST API mode (HTTP only)

```
AskUserQuestion(questions: [{
  question: "REST API mode?", header: "REST API", multiSelect: false,
  options: [
    { label: "Unauthenticated (Recommended)", description: "No token needed. Loopback-bound only. Enables config-sync with zero extra setup." },
    { label: "Bearer token", description: "Token-authenticated. For shared/multi-user setups." },
    { label: "Off", description: "MCP only, no REST, no config-sync." }
  ]
}])
```

- **Unauthenticated** → always render the loopback SECURITY caveat from `references/runtime-config.md`
  ("Loopback footgun") before the command, and force `-p 127.0.0.1:3001:3001` in the render — never a
  wider publish.
- **Bearer** → ask for the host path to the token YAML (free-text/"Other" answer), e.g.
  `~/.taskorchestrator-secrets/api-tokens.yaml`. If the file doesn't exist yet, point at
  `current/docs/api-rest.md` §1 for the token-generation snippet — this skill does not generate tokens.
- **Off** → REST fragment is just `-e API_ENABLED=false`; config-sync will no-op (tell the user).

---

## Step 4 — Config mount and debug (HTTP only)

```
AskUserQuestion(questions: [{
  question: "Mount this project's config as the server's global/fallback config?", header: "Config mount", multiSelect: false,
  options: [
    { label: "This project (fallback)", description: "Mount ./.taskorchestrator read-only as AGENT_CONFIG_DIR — good for a single-project server." },
    { label: "None (multi-project)", description: "No mount. Per-project config flows in via config-sync into the DB per root — good for one server shared across projects." }
  ]
}])
```

Then ask Yes/No for debug logging (`LOG_LEVEL=DEBUG` + `DATABASE_SHOW_SQL=true`).

---

## Step 5 — Render

Look up the exact fragments in `references/runtime-config.md` — do not improvise env values.

### STDIO

Render the `.mcp.json` **args array** shape (see reference doc, "`.mcp.json` shapes"):

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

Add the config-mount fragment (as `args` entries, not env flags) if the user wants a project mount.
No REST, no port, no config-sync — say so.

### HTTP — three coordinated pieces (all required, this is the default path now)

1. **The detached docker run command** — compose the recommended-default tuple (or the customized
   equivalent) from `references/runtime-config.md`:

   ```
   docker run -d --name mcp-task-orchestrator-http --restart unless-stopped \
     -v mcp-task-data:/app/data \
     -e MCP_TRANSPORT=http -e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true \
     -p 127.0.0.1:3001:3001 \
     ghcr.io/jpicklyk/task-orchestrator:latest
   ```

   (Substitute the REST-off or bearer fragment, and add the config-mount / debug fragments, per the
   user's Step 3-4 answers.) On Windows, run this via **PowerShell** with `${PWD}` for volume paths —
   see "Windows / MSYS path caveat" in the reference doc.

2. **The `.mcp.json` HTTP entry** (NOT an args array):

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

3. **Client-side config-sync env** — export in the user's own shell/profile, not the container:

   ```
   TASK_ORCHESTRATOR_API_URL=http://localhost:3001
   ```

   Add `TASK_ORCHESTRATOR_API_TOKEN=<token>` only for bearer mode. **Omitting this env var is the
   single most common way config-sync silently no-ops** (verified `config-sync.mjs:84-92`) — always
   render it, never treat it as optional polish.

If REST mode is **unauthenticated**, always print the SECURITY caveat (verbatim from
`references/runtime-config.md` → "Loopback footgun") immediately before or after the docker run block.

---

## Step 6 — HTTP lifecycle (verify it's actually working)

For any HTTP render, walk through:

1. **Run** the docker command from Step 5.
2. **Verify the container is up:**
   ```bash
   docker ps --filter name=mcp-task-orchestrator-http --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
   docker logs --since 20s mcp-task-orchestrator-http
   ```
3. **Reconnect the client:** update `.mcp.json` (Step 5, piece 2) if not already in place, then run
   `/mcp` in Claude Code — confirm `mcp-task-orchestrator` shows connected with all tools listed.
4. If REST is enabled, sanity-check it: `curl http://localhost:3001/api/v1/health` should return `200`.

---

## Reconfiguring later

Re-running this skill is safe — it always renders a fresh command from the current answers; it does
not read or depend on any previously-rendered state. To change an existing container's settings,
stop/remove it first (`docker stop mcp-task-orchestrator-http && docker rm mcp-task-orchestrator-http`),
then re-render and run the new command. (Maintainers building the image from source have a dedicated
detect-and-reuse flow in `/deploy_to_docker` — not needed for the published-image path this skill covers.)
