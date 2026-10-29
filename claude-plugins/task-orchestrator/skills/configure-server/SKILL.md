---
name: configure-server
description: "Configures how the MCP Task Orchestrator SERVER runs and is reached — transport (HTTP vs STDIO), the REST API, port publishing, config mounts, and config-sync. Use when a user says: run the server, register the image, set up the Docker container, enable the REST API, set up config-sync, reconfigure the server, change transport, expose the API, reconnect to a different endpoint, or allow a hostname. NOT for first-time onboarding (that's quick-start), NOT for project/personal setup (that's init), and NOT for note schemas / gates / traits (that's manage-schemas) — this skill only decides how the container is launched and reached. Caller identity is set by the REST auth mode (API_AUTH_MODE): this skill renders none and bearer; jwks is linked to the deployment docs, not rendered."
argument-hint: "[optional: 'recommended', 'http', 'stdio', 'bearer', or a change request e.g. 'enable REST']"
---

# Configure Server — Runtime & Transport Setup

Decides **how the MCP Task Orchestrator container is launched and reached**: transport, REST API mode,
port publishing, config mount, and config-sync. This is a runtime/deployment concern, distinct from
`quick-start` (first-time onboarding narrative) and `manage-schemas` (workflow gates/traits/
`resources:` content inside `.taskorchestrator/config.yaml`). Who a caller is comes from `API_AUTH_MODE`
and its credentials; this skill renders `none` and `bearer`, and links `jwks` ([fleet deployment](../../../../current/docs/fleet-deployment.md), [REST API, authentication](../../../../current/docs/api-rest.md#1-authentication)) without rendering it; no config key sets it. If the user wants
schema or gate changes — including resource-lease declarations — redirect to `/manage-schemas` instead
of proceeding here.

One operator escape hatch worth knowing when launching the container: `RESOURCE_LEASES_ENFORCED=false`
(env, default true) disables resource-lease gate enforcement server-wide — a kill switch for lease
contention incidents. Configuring which resources
exist and which traits declare them stays in `/manage-schemas`; this skill only knows the switch.

Not a director-mode skill: run it outside director mode (`/task-orchestrator:director off`); director-guard denies its file, shell and config steps partway through.

The full fragment catalog (exact env tuples, loopback caveat, Windows/MSYS caveat, `.mcp.json` shapes)
lives in `references/runtime-config.md` — this skill's job is the **decision flow** and **rendering**,
not re-deriving that catalog. Read it before rendering any command.

---

## Step 0 - Upgrading from 3.x (only when it applies)

Enter this step when the user mentions upgrading, or an existing `mcp-task-data` volume or container is in play. Ask; `docker ps -a` and `docker volume ls` are allowed to look (outside director mode). A new install skips to Step 1. The image tag `:latest` moves to 4.x, so a 3.x install that pulls `latest` upgrades whether or not anyone planned it: run this step first. The plugin and the server majors must match: a 4.x plugin needs a 4.x server that advertises `seats`.

In order:

1. **Stop every 3.x process** that uses the volume, stdio sessions included.
2. **Back up the whole volume off-volume.** The automatic `.pre-4.0.*.bak` copy sits on the same volume, so it is not a backup.
3. **Clear the settings that fail startup:** remove a leftover `actor_authentication:` or `auditing:` key from the mounted global config, and unset `DEGRADED_MODE_POLICY`. The legacy `schema_resolution`, `orchestration`, `retrospective` and `actor_attribution` keys only warn; `USE_FLYWAY` is ignored.
4. **Start ONE detached 4.0 container and wait until it is healthy** (the `docker inspect` health loop of the fleet guide; raise `--start-period` for a large database). The once-only data steps, including the irreversible `type-backfill`, run at this first boot.
5. **When REST is on, read `/api/v1/info`** and check `configWarnings`.
6. **Then register the clients** (Steps 1-6).

Commands and rollback are in [fleet deployment, Upgrading from 3.x](../../../../current/docs/fleet-deployment.md#upgrading-from-3x); do not copy them here.

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
config mount = process-schema floor, debug = off. **Customize** → Step 2.

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
    { label: "Unauthenticated (Recommended)", description: "No token needed. Loopback-bound only. Enables config-sync with zero extra setup. Every caller is the local agent principal, so human-only decisions cannot be answered." },
    { label: "Bearer token", description: "Token-authenticated. For shared/multi-user setups. The same token also guards /mcp." },
    { label: "Off", description: "MCP only, no REST, no config-sync. /mcp then serves every caller unauthenticated, so it needs an explicit opt-in." }
  ]
}])
```

- **Unauthenticated** → always render the loopback SECURITY caveat from `references/runtime-config.md`
  ("Loopback footgun") before the command, and force `-p 127.0.0.1:3001:3001` in the render — never a
  wider publish.
- **Bearer** → ask for the host path to the token YAML (free-text/"Other" answer), e.g.
  `~/.taskorchestrator-secrets/api-tokens.yaml`. If the file doesn't exist yet, point at
  `current/docs/api-rest.md` §1 for the token-generation snippet — this skill does not generate tokens.
- **Unauthenticated and Off both:** every caller is the local agent principal (proof none), so a human-only decision class can never be answered; director-or-human classes (run budget, attempt cap, deviation) can, through the director entry. A user who wants human sign-off needs Bearer with a `kind: human` token (see "Director credential").
- **Off** → REST fragment is `-e API_ENABLED=false -e MCP_ALLOW_UNAUTHENTICATED=true`; config-sync will
  no-op (tell the user). Without the opt-in, an HTTP server with the API off refuses to start (`MCP_AUTH`,
  [fleet deployment](../../../../current/docs/fleet-deployment.md)); with it, `/mcp` serves every caller as
  the `local` principal, so render the loopback SECURITY caveat here too and keep `-p 127.0.0.1:3001:3001`.
  The recommended default keeps the API on and needs no opt-in.

---

## Step 4 — Config mount and debug (HTTP only)

```
AskUserQuestion(questions: [{
  question: "Mount the process-schema floor as the server's global config?", header: "Config mount", multiSelect: false,
  options: [
    { label: "Process-schema floor (Recommended)", description: "Mount the floor config read-only as AGENT_CONFIG_DIR: the shared process schemas (project anchor, observations, retrospectives, proposals, containers). Per-project config still flows in via config-sync per root." },
    { label: "None", description: "No mount. Warning: with no server config a depth-0 create (and any root with no config of its own) accepts every type schema-free, so a project anchor can auto-complete and retrospective/proposal items have no required notes." }
  ]
}])
```

Do not offer to mount a project's own `.taskorchestrator` as a fallback: a mounted file that carries
`project.rootId` is imported into that root, not the server scope, and fails startup when the
database lacks that root. Project config reaches the server through config-sync, never the mount.

The floor directory (`<FLOOR_DIR>`) is resolved in Step 5 (it is needed on every path that mounts the floor, including the recommended default).

Then ask Yes/No for debug logging (`LOG_LEVEL=DEBUG` + `DATABASE_SHOW_SQL=true`).

---

## Step 5 — Render

Look up the exact fragments in `references/runtime-config.md` — do not improvise env values.

**Resolve `<FLOOR_DIR>` first** whenever the floor is mounted (the recommended default, a Step 4 "Process-schema floor" answer, or STDIO). It is `<checkout>/deploy/global-config/.taskorchestrator` in a source
checkout. Otherwise it is a host directory the user downloads the file into (the image bakes no
config); render the command for them, for example:

```
mkdir -p <FLOOR_DIR> && curl -fsSL -o <FLOOR_DIR>/config.yaml https://raw.githubusercontent.com/jpicklyk/task-orchestrator/main/deploy/global-config/.taskorchestrator/config.yaml
```

On Windows (PowerShell):

```
New-Item -ItemType Directory -Force <FLOOR_DIR>; Invoke-WebRequest -Uri https://raw.githubusercontent.com/jpicklyk/task-orchestrator/main/deploy/global-config/.taskorchestrator/config.yaml -OutFile <FLOOR_DIR>/config.yaml
```

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

Add the floor config-mount fragment (as `args` entries, not env flags: `"-v", "<FLOOR_DIR>:/project/.taskorchestrator:ro", "-e", "AGENT_CONFIG_DIR=/project"`) by default (Step 4's config-mount question is HTTP only and is not asked here); leave it out only if the user asks for no mount.
No REST, no port, no config-sync — say so. When asked, or when this skill runs standalone, also render the stdio director entry from `references/runtime-config.md` (`.mcp.json` shapes): the same `docker run` args with `-e MCP_TOOL_PROFILE=director` before the image, under the key `mcp-task-orchestrator-director`. A stdio server has no credentials, so the entry carries no header; the caller is the local agent principal.

### HTTP — three coordinated pieces (all required, this is the default path now)

1. **The detached docker run command** — compose the recommended-default tuple (or the customized
   equivalent) from `references/runtime-config.md`:

   ```
   docker run -d --name mcp-task-orchestrator-http --restart unless-stopped \
     -v mcp-task-data:/app/data \
     -v "<FLOOR_DIR>:/project/.taskorchestrator:ro" -e AGENT_CONFIG_DIR=/project \
     -e MCP_TRANSPORT=http -e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true \
     -p 127.0.0.1:3001:3001 \
     ghcr.io/jpicklyk/task-orchestrator:latest
   ```

   (Substitute the REST-off or bearer fragment per the user's Step 3 answer, drop the config-mount
   line (`-v "<FLOOR_DIR>..."` and `-e AGENT_CONFIG_DIR`) when the user chose None in Step 4, and add the debug fragment when they chose it.) On Windows, run this via **PowerShell** with `${PWD}` for volume paths —
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

   In bearer mode the entry carries the token as a header, written as the placeholder (Claude Code
   expands it from the environment; never write the token itself into the file):

   ```json
   "headers": {"Authorization": "Bearer ${TASK_ORCHESTRATOR_API_TOKEN}"}
   ```

   The same bearer authenticates `/mcp`, `/mcp/knowledge` and `/mcp/director`
   ([fleet deployment](../../../../current/docs/fleet-deployment.md)).

3. **Client-side config-sync env** — export in the user's own shell/profile, not the container:

   ```
   TASK_ORCHESTRATOR_API_URL=http://localhost:3001
   ```

   This env var is now optional when the MCP server is registered over http(s) (`.mcp.json` or `~/.claude.json`): the hooks derive the REST base from that registration's origin, so the MCP endpoint is the one place to change the server (a dynamically assigned port included). `session-start` warns when the env var or a `client.json` points at a different server than the registration. A persistent alternative to this env var is a `client.json` (`apiUrl` only, never a token), written after checking `/api/v1/health`. The API URL resolves in this order: the env var, then a project-level `client.json` beside the located config (or in the main checkout for a linked worktree), then the origin of the http(s) MCP registration (a `.mcp.json` origin must be loopback), then the user-level `client.json`. Project-mode `/task-orchestrator:init` writes the project-level file, but only for a loopback server reached at a bare origin (the hooks ignore a project-level URL on any other host, or with a path, query or fragment); `/task-orchestrator:init --user` writes the user-level file, which is unrestricted, so a non-loopback server needs that or the env var. The token still comes from the environment only.

   Add `TASK_ORCHESTRATOR_API_TOKEN=<token>` only when the REST API requires authentication (bearer or jwks mode). **Omitting this env var, with no
   `client.json` supplying a URL and no usable http(s) MCP registration to derive one from, is the single most common way config-sync silently no-ops** (`config-sync.mjs` returns early when `apiBaseUrl()` in
   `hooks/lib/api-client.mjs` finds no URL) —
   in that case always render it, never treat it as optional polish.

4. **The director entry (optional).** `/task-orchestrator:init` registers it by default; render it here
   when the user asks for it or runs this skill alone. A second entry, `mcp-task-orchestrator-director`
   (its key must contain `task-orchestrator`), whose `url` is `http://localhost:3001/mcp/director`. In
   bearer mode its header is a separate placeholder, with no nested fallback to the agents' token:

   ```json
   "headers": {"Authorization": "Bearer ${TASK_ORCHESTRATOR_DIRECTOR_TOKEN}"}
   ```

   It serves only reads, run control and decisions
   ([API reference, tool profiles](../../../../current/docs/api-reference.md#tool-profiles)). The token
   behind the placeholder is described in "Director credential" below.

If REST mode is **unauthenticated**, always print the SECURITY caveat (verbatim from
`references/runtime-config.md` → "Loopback footgun") immediately before or after the docker run block.

**Non-loopback Host:** if the user will reach the server by any name other than
`localhost`/`127.0.0.1`/`[::1]` — this includes a `TASK_ORCHESTRATOR_API_URL` or a `.mcp.json` `url`
using a compose service name, `host.docker.internal`, a LAN name, or a reverse-proxy hostname — add
the `MCP_ALLOWED_HOSTS` fragment from `references/runtime-config.md` → "Host allowlist
(MCP_ALLOWED_HOSTS)" to the docker run command, and point the user at that section for the format and
examples. Do not add a new `AskUserQuestion` step for this — infer it from the URL/hostname already in
play.

---

## Step 6 — HTTP lifecycle (verify it's actually working)

For any HTTP render, walk through:

1. **Run** the docker command from Step 5.
2. **Wait until the container is healthy** with the `docker inspect` health loop of
   [fleet deployment, Upgrading from 3.x](../../../../current/docs/fleet-deployment.md#upgrading-from-3x)
   (not a fixed look at the last seconds of the log; a first boot over an old database can take a while),
   then look at the state and the log:
   ```bash
   docker ps --filter name=mcp-task-orchestrator-http --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
   docker logs mcp-task-orchestrator-http
   ```
3. **Reconnect the client:** update `.mcp.json` (Step 5, piece 2) if not already in place, then run
   `/mcp` in Claude Code — confirm `mcp-task-orchestrator` shows connected with all tools listed, then confirm the server is 4.x:
   over HTTP read `features` from `GET /.well-known/mcp-task-orchestrator.json` (it contains `seats`), and on
   either transport `runs_read` must be among the tools (a 3.x server has none; go to Step 0).
4. If REST is enabled, sanity-check it: `curl http://localhost:3001/api/v1/health` should return `200`.
5. An HTTP `403` with the `forbidden` error envelope (`scope: host`) on `/mcp`, `/api/v1` or
   `/.well-known` means the request's `Host` header is not allowlisted — see
   `references/runtime-config.md` → "Host allowlist (MCP_ALLOWED_HOSTS)".

Next: run `/task-orchestrator:init` in each project (or `/task-orchestrator:init --user` once for a personal root).

---

## HTTP-first policy for new infrastructure features

New infrastructure features — `config-sync`, SSE events, the `plan-capture` hook, the SubagentStop
completion guard (`phase-guard.mjs` + `phase-guard-record.mjs`) — are **HTTP-only**, with a graceful
no-op on STDIO. Each resolves its REST base URL (`TASK_ORCHESTRATOR_API_URL`, a `client.json`, or the http(s) MCP registration) and silently
skips when absent, rather than failing. STDIO remains fully supported for MCP tool calls themselves —
it is positioned as the **local/evaluation mode**: no persistent daemon, no REST surface, and
consequently none of these convenience features. When recommending a setup for ongoing project work
(not a one-off trial), prefer the HTTP render in Step 5 for this reason, in addition to config-sync's
per-project hot-reload benefit already covered in Step 1.

The completion guard reads an item's gate state with `POST /api/v1/ops/items_read` and `select: context`,
a READ-capability call — under bearer auth, the token rendered for `TASK_ORCHESTRATOR_API_TOKEN` needs
`read` in addition to whatever capability its other consumers need (config-sync's documented token is
`read` + `write-config` + `write-docs`: `read` for the config get and validate and the rule listing,
`write-config` for the push, `write-docs` for the rule upserts; plan-capture needs `write-docs`).
`docs_write` needs `write-docs` and `write-config` does not grant it
([REST API, authentication](../../../../current/docs/api-rest.md#1-authentication)). A token scoped to
`write-config` alone leaves the guard silently inert (the gate read returns 403, which the guard treats
as fail-open, not an error) — call this out whenever rendering a bearer-mode token for a workspace that
also uses the guard.

### Director credential

In bearer mode the director entry gets its own token-file entry, so its calls carry their own identity and audit trail. Two token properties matter for decisions on a run: `kind: human`, with a real proof and no seat token, is what answers a `human-only` decision class (a non-human credential cannot), and `profile: director` confines a token to the director path (`POST /api/v1/ops/director/{tool}`, `/mcp/director`). Both are set in the token file ([REST API, authentication](../../../../current/docs/api-rest.md#1-authentication), [capabilities](../../../../current/docs/api-rest.md#2-capabilities-authorization), [fleet deployment](../../../../current/docs/fleet-deployment.md)). A token-file token has proof `issued`, so bearer with `kind: human` is enough; jwks is not required.

Render a two-entry token file (placeholders only; the user generates the digests, see api-rest.md section 1; never write a token or a real digest):

```yaml
version: 1
tokens:
  - id: agents
    token_sha256: "<sha256 of the agents token>"
    kind: agent
    capabilities: [read, write-items, write-notes, advance, manage-dependencies, runs, write-docs, write-config]
  - id: director
    token_sha256: "<sha256 of the director token>"
    kind: agent            # kind: human only when a human drives this entry; see the hazard below
    profile: director
    capabilities: [read, runs, decide]
```

The user exports `TASK_ORCHESTRATOR_API_TOKEN` (agents) and `TASK_ORCHESTRATOR_DIRECTOR_TOKEN` (director) before starting Claude Code. The director token defaults to `kind: agent`: it still answers the director-or-human decision classes through the director path. Both entries load into every session, subagent and headless `/ralph` iteration that reads `<main>/.mcp.json` with the variables set, and nothing in the plugin stops a non-director agent from calling the director entry. So a separate director token separates identity and audit everywhere, and separates authority ONLY in environments where `TASK_ORCHESTRATOR_DIRECTOR_TOKEN` is NOT exported (ralph, CI, worker machines). Never put `kind: human` on a token exported into a session that runs autonomous agents or subagents: any of them can then answer human-only classes through the director entry. Answer human-only classes from a client no agent drives: REST `POST /api/v1/ops/director/runs_write` with the human token, or a separate session where only that token is set. Pointing both variables at one token loses the identity and audit split and puts `decide` (and with `kind: human`, the human-only classes) on the agents' own token, so it reaches every environment that exports that token, ralph and CI included; leaving the director variable unset keeps `decide` out of that environment. In an environment that exports only `TASK_ORCHESTRATOR_API_TOKEN`, Claude Code still loads the file: the orchestration entry works, and the director entry shows a missing-variable warning in `claude mcp list` and fails `401`, which is the intended agent-only setup. On stdio both entries are the one `local` principal, so neither identity nor authority is separated there. A `scope.root_ids` on either token must name depth-0 roots only.

## Reconfiguring later

Re-running this skill is safe — it always renders a fresh command from the current answers; it does
not read or depend on any previously-rendered state. To change an existing container's settings,
stop/remove it first (`docker stop mcp-task-orchestrator-http && docker rm mcp-task-orchestrator-http`),
then re-render and run the new command. When a `mcp-task-orchestrator-director` entry already exists in the client config, re-render it alongside the orchestration entry on any transport change (stdio to HTTP or back): its URL or its `MCP_TOOL_PROFILE=director` args change with the transport. (Maintainers building the image from source have a dedicated
detect-and-reuse flow in `/deploy_to_docker` — not needed for the published-image path this skill covers.)
