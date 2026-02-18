---
description: Build Docker image and optionally start a container for the MCP Task Orchestrator
---

# Deploy to Docker

Build the MCP Task Orchestrator Docker image. The Dockerfile handles its own Gradle build internally, so no local build step is needed.

**Usage:** `/deploy_to_docker [image-tag] [--clockwork] [--run]`

**Arguments:**
- `image-tag` (optional): Docker image tag. Default: `task-orchestrator:current`. A bare name like `dev` expands to `task-orchestrator:dev`.
- `--clockwork` (optional): Build the deprecated v2 (Clockwork) architecture instead. Default tag becomes `task-orchestrator:clockwork`. Uses `mcp-task-data` volume. Note: builder stage must also include `:clockwork:jar` — see Dockerfile notes.
- `--run` (optional): Start a container after building. Prompts for run configuration.

**Examples:**
- `/deploy_to_docker` — Build `task-orchestrator:current`, no container
- `/deploy_to_docker --run` — Build v3 and prompt for container config
- `/deploy_to_docker myfeature` — Build with tag `task-orchestrator:myfeature`
- `/deploy_to_docker --clockwork` — Build deprecated v2 as `task-orchestrator:clockwork`
- `/deploy_to_docker --clockwork --run` — Build v2 and prompt for container config

---

## Workflow

### Step 1: Resolve Image Tag

Parse the arguments to determine the image tag and build target:
- Check for `--clockwork` flag in arguments:
  - If `--clockwork` is present: set `TARGET=runtime-v2`, default tag = `task-orchestrator:clockwork`, data volume = `mcp-task-data`
  - Otherwise: set `TARGET=runtime-current`, default tag = `task-orchestrator:current`, data volume = `mcp-task-data-current`
- If a tag argument is provided (not starting with `--`), use it as-is
- If the tag has no `:`, prepend `task-orchestrator:` (e.g., `dev` becomes `task-orchestrator:dev`)
- If no tag argument, use the default tag determined above

### Step 2: Build Docker Image

```bash
docker build --target <TARGET> -t <image-tag> .
```

Where `<TARGET>` is `runtime-current` (default) or `runtime-v2` (when `--clockwork` is specified).

The Dockerfile's multi-stage build compiles the project from source — no prior `./gradlew build` required.

> **Note for `--clockwork`:** The Dockerfile builder stage only runs `:current:jar` by default.
> To build the Clockwork v2 JAR, you must temporarily add `:clockwork:jar` to the Gradle command
> in the Dockerfile builder stage. See `clockwork/DEPRECATED.md` for instructions.

### Step 3: Verify Image

```bash
docker images | grep task-orchestrator
```

Report the image ID and size.

### Step 4: Start Container (only if `--run` was specified)

**If `--run` was NOT specified:** Skip to summary. This is the default path.

**If `--run` was specified**, first ask the user which transport mode to use:

```
AskUserQuestion(
  questions: [{
    question: "Which transport mode?",
    header: "Transport",
    multiSelect: false,
    options: [
      {
        label: "STDIO (Recommended)",
        description: "Connect via stdin/stdout — standard MCP transport, no port needed"
      },
      {
        label: "HTTP",
        description: "Expose port 3001 — runs detached as a long-lived daemon; requires MCP SDK with 2025-11-25 protocol support"
      }
    ]
  }]
)
```

---

#### If STDIO transport selected

Ask which run configuration to use:

```
AskUserQuestion(
  questions: [{
    question: "Which container configuration?",
    header: "Run mode",
    multiSelect: false,
    options: [
      {
        label: "With Project Mount (Recommended)",
        description: "Database volume + project mount for config reading"
      },
      {
        label: "With Debug Logging",
        description: "Project mount + LOG_LEVEL=DEBUG + DATABASE_SHOW_SQL=true"
      },
      {
        label: "Basic",
        description: "Database volume only, no config file access"
      }
    ]
  }]
)
```

Use `<DATA_VOLUME>` as the data volume name: `mcp-task-data-current` for v3 (default) or `mcp-task-data` for v2 (`--clockwork`).

**"With Project Mount"**
```bash
docker run --rm -i \
  -v <DATA_VOLUME>:/app/data \
  -v D:/Projects/task-orchestrator:/project:ro \
  -e AGENT_CONFIG_DIR=/project \
  <image-tag>
```

**"With Debug Logging"**
```bash
docker run --rm -i \
  -v <DATA_VOLUME>:/app/data \
  -v D:/Projects/task-orchestrator:/project:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e LOG_LEVEL=DEBUG \
  -e DATABASE_SHOW_SQL=true \
  <image-tag>
```

**"Basic"**
```bash
docker run --rm -i -v <DATA_VOLUME>:/app/data <image-tag>
```

---

#### If HTTP transport selected

> **Note:** HTTP transport requires the MCP Kotlin SDK to support protocol version `2025-11-25`.
> As of SDK 0.8.4, only `2025-06-18` is supported — Claude Code will show the server as failed.
> Use HTTP mode for testing or when the SDK is updated.

Ask which run configuration to use:

```
AskUserQuestion(
  questions: [{
    question: "Which HTTP container configuration?",
    header: "Run mode",
    multiSelect: false,
    options: [
      {
        label: "With Project Mount (Recommended)",
        description: "Database volume + project mount + port 3001"
      },
      {
        label: "With Debug Logging",
        description: "Project mount + port 3001 + LOG_LEVEL=DEBUG + DATABASE_SHOW_SQL=true"
      }
    ]
  }]
)
```

Use `<DATA_VOLUME>` as the data volume name: `mcp-task-data-current` for v3 (default) or `mcp-task-data` for v2 (`--clockwork`).
The container name will be `mcp-task-orchestrator-http`.

Stop any existing container with that name first:
```bash
docker stop mcp-task-orchestrator-http 2>/dev/null || true
docker rm mcp-task-orchestrator-http 2>/dev/null || true
```

**"With Project Mount"**
```bash
docker run -d \
  --name mcp-task-orchestrator-http \
  -v <DATA_VOLUME>:/app/data \
  -v D:/Projects/task-orchestrator:/project:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e MCP_TRANSPORT=http \
  -e MCP_HTTP_HOST=0.0.0.0 \
  -e MCP_HTTP_PORT=3001 \
  -p 3001:3001 \
  <image-tag>
```

**"With Debug Logging"**
```bash
docker run -d \
  --name mcp-task-orchestrator-http \
  -v <DATA_VOLUME>:/app/data \
  -v D:/Projects/task-orchestrator:/project:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e MCP_TRANSPORT=http \
  -e MCP_HTTP_HOST=0.0.0.0 \
  -e MCP_HTTP_PORT=3001 \
  -e LOG_LEVEL=DEBUG \
  -e DATABASE_SHOW_SQL=true \
  -p 3001:3001 \
  <image-tag>
```

After starting, verify the container is running:
```bash
docker ps --filter name=mcp-task-orchestrator-http --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

Then remind the user to add the server to `.mcp.json` if not already present:
```json
"mcp-task-orchestrator-http": {
  "type": "http",
  "url": "http://localhost:3001/mcp"
}
```

### Step 5: Summary

Report:
- Image tag and ID
- Build target used (`runtime-current` or `runtime-v2`)
- Whether a container was started (transport mode + run config)
- **STDIO:** Remind user to reconnect MCP: `/mcp reconnect mcp-task-orchestrator`
- **HTTP:** Remind user to verify `.mcp.json` has the HTTP entry and run `/mcp` to check connection status

---

## Notes

- **No local build needed:** The Dockerfile runs `./gradlew :current:jar` in a builder stage
- **Database persistence:** Volume `mcp-task-data-current` (v3) or `mcp-task-data` (v2) persists across container runs
- **Config access:** Project mount modes mount the project for `.taskorchestrator/config.yaml` access
- **STDIO transport:** Uses stdin/stdout, no port mapping needed; started with `-i` flag
- **HTTP transport:** Runs detached (`-d`), exposes port 3001, named `mcp-task-orchestrator-http`; requires SDK protocol `2025-11-25` support (SDK 0.8.4 = `2025-06-18` only)
- **v2 is deprecated:** Use `--clockwork` only for legacy reference. Active development is in v3 (Current).

## Troubleshooting

**Docker build fails:**
- Verify Dockerfile exists in project root
- Check disk space

**Container fails to start:**
- Try debug logging mode for detailed output
- Check `docker logs <container-id>`

**Database permission errors:**
```bash
docker run --rm -v mcp-task-data-current:/app/data --user root amazoncorretto:25-al2023-headless chown -R 1001:1001 /app/data
```

**Config files not found:**
- Use project mount mode (not basic)
- Verify `.taskorchestrator/` directory exists in project root
