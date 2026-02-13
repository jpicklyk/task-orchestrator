---
description: Build Docker image and optionally start a container for the MCP Task Orchestrator
---

# Deploy to Docker

Build the MCP Task Orchestrator Docker image. The Dockerfile handles its own Gradle build internally, so no local build step is needed.

**Usage:** `/deploy_to_docker [image-tag] [--run]`

**Arguments:**
- `image-tag` (optional): Docker image tag. Default: `task-orchestrator:dev`. A bare name like `dev` expands to `task-orchestrator:dev`.
- `--run` (optional): Start a container after building. Prompts for run configuration.

**Examples:**
- `/deploy_to_docker` — Build `task-orchestrator:dev`, no container
- `/deploy_to_docker dev` — Same as above
- `/deploy_to_docker task-orchestrator:v2` — Custom tag
- `/deploy_to_docker --run` — Build and prompt for container config

---

## Workflow

### Step 1: Resolve Image Tag

Parse the arguments to determine the image tag:
- If a tag argument is provided (not starting with `--`), use it as-is
- If the tag has no `:`, prepend `task-orchestrator:` (e.g., `dev` becomes `task-orchestrator:dev`)
- If no tag argument, default to `task-orchestrator:dev`

### Step 2: Build Docker Image

```bash
docker build -t <image-tag> .
```

The Dockerfile's multi-stage build compiles the project from source — no prior `./gradlew build` required.

### Step 3: Verify Image

```bash
docker images | grep task-orchestrator
```

Report the image ID and size.

### Step 4: Start Container (only if `--run` was specified)

**If `--run` was NOT specified:** Skip to summary. This is the default path.

**If `--run` was specified**, ask the user which configuration to use:

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

**Based on user choice, run the appropriate command:**

**"With Project Mount"**
```bash
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v D:/Projects/task-orchestrator:/project:ro \
  -e AGENT_CONFIG_DIR=/project \
  <image-tag>
```

**"With Debug Logging"**
```bash
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v D:/Projects/task-orchestrator:/project:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e LOG_LEVEL=DEBUG \
  -e DATABASE_SHOW_SQL=true \
  <image-tag>
```

**"Basic"**
```bash
docker run --rm -i -v mcp-task-data:/app/data <image-tag>
```

### Step 5: Summary

Report:
- Image tag and ID
- Whether a container was started (and which mode)
- Remind user to reconnect MCP: `/mcp reconnect task-orchestrator`

---

## Notes

- **No local build needed:** The Dockerfile runs `./gradlew build` in a builder stage
- **Database persistence:** Volume `mcp-task-data` persists across container runs
- **Config access:** Project mount modes mount the project for `.taskorchestrator/config.yaml` access
- **MCP protocol:** Uses stdin/stdout, no port mapping needed

## Troubleshooting

**Docker build fails:**
- Verify Dockerfile exists in project root
- Check disk space

**Container fails to start:**
- Try debug logging mode for detailed output
- Check `docker logs <container-id>`

**Database permission errors:**
```bash
docker run --rm -v mcp-task-data:/app/data --user root amazoncorretto:25-al2023-headless chown -R 1001:1001 /app/data
```

**Config files not found:**
- Use project mount mode (not basic)
- Verify `.taskorchestrator/` directory exists in project root
