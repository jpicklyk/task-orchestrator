---
description: Build Docker image and start/redeploy the MCP Task Orchestrator container, reusing the last-used config by default
---

# Deploy to Docker

Build the MCP Task Orchestrator Docker image and start (or redeploy) the detached HTTP container that the active `.mcp.json` connects to. The Dockerfile handles its own Gradle build internally — no local build step needed.

On every deploy the command **detects the current container's settings and defaults to reusing them**, so you never have to re-specify things like `API_AUTH_MODE=none` after the first time. You can always review or change them via the chooser.

**Usage:** `/deploy_to_docker [image-tag] [--build-only] [--run] [--reuse]`

**Default (no flags):** Build the image, detect the existing container's config, then present a **[Reuse / Reconfigure]** chooser. Reuse redeploys with the same settings; Reconfigure walks the REST-mode / config-mount prompts. Matches the active `.mcp.json` (`type: http`, `url: http://localhost:3001/mcp`) — reachable after `/mcp reconnect mcp-task-orchestrator`.

**Arguments:**
- `image-tag` (optional): Docker image tag. Default `task-orchestrator:current`. A bare name like `dev` → `task-orchestrator:dev`.
- `--build-only`: Build the image only; start no container; no prompts.
- `--run` / `--reconfigure`: Build, then go straight to the reconfigure prompts (skip the reuse chooser) — including STDIO/HTTP transport choice.
- `--reuse`: Build, then redeploy with the detected settings **without prompting** (non-interactive redeploy).

**Examples:**
- `/deploy_to_docker` — build, then Reuse-or-Reconfigure chooser
- `/deploy_to_docker --reuse` — rebuild + redeploy identical settings, no prompts
- `/deploy_to_docker --run` — build + full interactive reconfigure
- `/deploy_to_docker --build-only` — build image only

---

## Workflow

### Step 1: Resolve Image Tag
- `TARGET=runtime-current`, default tag `task-orchestrator:current`, data volume `mcp-task-data`.
- A non-`--` tag argument is used as-is; a tag with no `:` gets `task-orchestrator:` prepended; no arg → default.

### Step 2: Build Docker Image
```bash
docker build --target runtime-current -t <image-tag> .
```
Multi-stage build compiles from source — no prior `./gradlew build`.

### Step 3: Verify Image
```bash
docker images | grep task-orchestrator
```
Report image ID and size.

### Step 4: Determine Run Settings (detect → reuse or reconfigure)

**If `--build-only`:** skip to Step 5 — no container.

#### 4a. Detect current settings
Read what the existing container was last started with (this is the source of truth for a redeploy — run it **before** removing the container):
```bash
# REST env + debug
docker inspect mcp-task-orchestrator-http --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
  | grep -E 'API_ENABLED|API_AUTH_MODE|API_ALLOW_UNAUTHENTICATED|API_TOKENS_PATH|LOG_LEVEL' || echo '(no existing container)'
# config mount present?
docker inspect mcp-task-orchestrator-http --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}' 2>/dev/null \
  | grep -q '/project/.taskorchestrator' && echo 'config-mount: project' || echo 'config-mount: none'
```
Derive the **REST mode** from the env: `API_ENABLED=false` → **off**; `API_ENABLED=true` + `API_AUTH_MODE=none` → **unauthenticated**; `+ API_AUTH_MODE=bearer` → **bearer**.

If no container exists, fall back (in order) to: the persisted file `${HOME}/.taskorchestrator/deploy.env` (see 4d); else the **defaults** — HTTP transport, REST **off**, **project** config mount, loopback `127.0.0.1:3001`, debug off.

Build a one-line `DETECTED` summary, e.g. `HTTP · REST=unauthenticated (local) · config-mount=none · debug=off · 127.0.0.1:3001 · image=task-orchestrator:current`.

#### 4b. Chooser
- **If `--reuse`:** skip prompting — use the detected settings, go to 4d.
- **If `--run`/`--reconfigure`:** skip this chooser, go to 4c.
- **Otherwise (default):** present the detected summary and ask:
```
AskUserQuestion(questions: [{
  question: "Detected config: <DETECTED>. Reuse it, or reconfigure?",
  header: "Deploy config",
  multiSelect: false,
  options: [
    { label: "Reuse detected settings", description: "Redeploy with the settings shown above — no further prompts" },
    { label: "Reconfigure", description: "Choose transport / REST API mode / config mount" }
  ]
}])
```
Reuse → 4d. Reconfigure → 4c.

#### 4c. Reconfigure prompts

**Transport** (ask only on `--run`; the default/reconfigure path assumes **HTTP**, since `.mcp.json` uses HTTP):
```
AskUserQuestion(questions: [{
  question: "Which transport mode?", header: "Transport", multiSelect: false,
  options: [
    { label: "HTTP (Recommended)", description: "Serves /mcp on 127.0.0.1:3001, detached daemon — matches .mcp.json" },
    { label: "STDIO", description: "stdin/stdout, no port; interactive -i run (does not match the http .mcp.json)" }
  ]
}])
```
For **STDIO**, use the legacy interactive run (`docker run --rm -i -v mcp-task-data:/app/data [-v <pwd>/.taskorchestrator:/project/.taskorchestrator:ro -e AGENT_CONFIG_DIR=/project] [-e LOG_LEVEL=DEBUG -e DATABASE_SHOW_SQL=true] <image-tag>`) and skip the HTTP questions below.

For **HTTP**, ask:

**REST API mode** — pre-note the detected value as recommended:
```
AskUserQuestion(questions: [{
  question: "REST API mode? (the /api/v1 layer used by config-sync)", header: "REST API", multiSelect: false,
  options: [
    { label: "Off (MCP only)", description: "API_ENABLED=false — the /mcp endpoint only. Default for plain local MCP use." },
    { label: "Unauthenticated (local)", description: "API_ENABLED=true + API_AUTH_MODE=none + API_ALLOW_UNAUTHENTICATED=true. Token-free config-sync; loopback-bound ONLY." },
    { label: "Bearer (token file)", description: "API_ENABLED=true + API_AUTH_MODE=bearer + a mounted api-tokens.yaml. For authed / shared setups." }
  ]
}])
```
- If **Bearer**, ask for the host path to the token YAML (via the "Other" free-text answer), e.g. `~/.taskorchestrator-secrets/api-tokens.yaml`.
- If **Unauthenticated**, show this caveat before proceeding:
  > **SECURITY:** unauthenticated REST means anyone who can reach the port can read/write/delete config and data. This is only safe **loopback-bound** (`-p 127.0.0.1:3001:3001`). Never publish the port on `0.0.0.0`. (`/mcp` is already unauthenticated on the same server — this just matches that posture.)

**Config mount:**
```
AskUserQuestion(questions: [{
  question: "Mount this project's config as the server's global/fallback config?", header: "Config mount", multiSelect: false,
  options: [
    { label: "This project (fallback)", description: "Mount ./.taskorchestrator read-only as AGENT_CONFIG_DIR — the global fallback config." },
    { label: "None (multi-project)", description: "No project mount. Per-project config flows in via the config-sync hook into the DB per root." }
  ]
}])
```

**Debug logging:** ask Yes/No (`LOG_LEVEL=DEBUG` + `DATABASE_SHOW_SQL=true`).

#### 4d. Persist settings
Write the resolved settings so the next deploy reuses them even if the container is later removed:
```bash
mkdir -p "${HOME}/.taskorchestrator"
cat > "${HOME}/.taskorchestrator/deploy.env" <<EOF
# written by /deploy_to_docker — last-used deploy settings
IMAGE_TAG=<image-tag>
TRANSPORT=http
REST_MODE=<off|unauth|bearer>
TOKENS_HOST_PATH=<host path, bearer only>
CONFIG_MOUNT=<project|none>
DEBUG=<true|false>
EOF
```

#### 4e. Assemble and run (HTTP)
Stop/remove the existing container (AFTER detection in 4a), then run with the resolved settings.
```bash
docker stop mcp-task-orchestrator-http 2>/dev/null || true
docker rm   mcp-task-orchestrator-http 2>/dev/null || true
```

Base command (compose the REST-mode / config-mount / debug fragments below into it):
```
docker run -d --name mcp-task-orchestrator-http --restart unless-stopped \
  -v mcp-task-data:/app/data \
  -e MCP_TRANSPORT=http -e MCP_HTTP_HOST=0.0.0.0 -e MCP_HTTP_PORT=3001 \
  <REST-MODE fragment> <CONFIG-MOUNT fragment> <DEBUG fragment> \
  -p 127.0.0.1:3001:3001 <image-tag>
```

| Choice | Fragment |
|--------|----------|
| REST off | `-e API_ENABLED=false` |
| REST unauthenticated | `-e API_ENABLED=true -e API_AUTH_MODE=none -e API_ALLOW_UNAUTHENTICATED=true` |
| REST bearer | `-e API_ENABLED=true -e API_AUTH_MODE=bearer -e API_TOKENS_PATH=/run/secrets/api-tokens.yaml -v <TOKENS_HOST_PATH>:/run/secrets/api-tokens.yaml:ro` |
| Config mount = project | `-v "<pwd>/.taskorchestrator:/project/.taskorchestrator:ro" -e AGENT_CONFIG_DIR=/project` |
| Config mount = none | *(omit)* |
| Debug on | `-e LOG_LEVEL=DEBUG -e DATABASE_SHOW_SQL=true` |

> **Windows:** run the `docker run` (and the STDIO variant) via **PowerShell** using `${PWD}` for the volume path — the Bash/MSYS shell rewrites the leading slash in `-e AGENT_CONFIG_DIR=/project` and in `:/app/data` / `:/project/...` mounts, breaking the container. Bash is fine for `docker build`, `docker inspect`, `docker logs`, `docker stop/rm`.

Verify running:
```bash
docker ps --filter name=mcp-task-orchestrator-http --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
docker logs --since 20s mcp-task-orchestrator-http 2>&1 | tail -8
```
`.mcp.json` should already hold the HTTP entry (do NOT rename the `mcp-task-orchestrator` key or add a duplicate):
```json
"mcp-task-orchestrator": { "type": "http", "url": "http://localhost:3001/mcp" }
```

### Step 5: Summary
Report: image tag + ID; the resolved run config (transport, REST mode, config mount, debug, port); whether settings were reused or reconfigured; and — for HTTP — remind the user to run `/mcp reconnect mcp-task-orchestrator` then `/mcp` to verify. For `--build-only`, note no container was started.

If **unauthenticated** REST was selected, repeat the loopback-only SECURITY caveat in the summary.

---

## Notes
- **Reuse is the default:** detection reads the live container (`docker inspect`) first, then `${HOME}/.taskorchestrator/deploy.env`, then built-in defaults — so a redeploy keeps your last REST mode / mounts unless you Reconfigure.
- **REST API + config-sync:** the `config-sync` SessionStart hook needs the REST API **on** (`Unauthenticated` locally, or `Bearer`). With REST `off`, only `/mcp` is served and config-sync no-ops.
- **No local build needed:** the Dockerfile runs `./gradlew :current:jar` in a builder stage.
- **Persistence:** `mcp-task-data` volume persists the DB across runs.
- **HTTP:** detached (`-d`), `/mcp` served, container `mcp-task-orchestrator-http`, published loopback-only by default.

## Troubleshooting
- **Build fails:** verify the Dockerfile exists at project root; check disk space.
- **Container won't start:** reconfigure with Debug logging; `docker logs mcp-task-orchestrator-http`. If you set REST unauthenticated on a **pre-#237 image**, the old loader rejects `API_AUTH_MODE=none` at startup — rebuild first.
- **DB permission errors:** `docker run --rm -v mcp-task-data:/app/data --user root amazoncorretto:25-al2023-headless chown -R 1001:1001 /app/data`.
- **Config not found:** use a config mount (not None) if you rely on the global fallback config; verify `.taskorchestrator/` exists.
