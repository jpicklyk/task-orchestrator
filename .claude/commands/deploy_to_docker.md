# Deploy to Docker

Build and deploy the MCP Task Orchestrator Docker image with the specified tag.

**Usage:** `/deploy_to_docker [image-tag]`

**Examples:**
- `/deploy_to_docker` (will prompt for tag, default: task-orchestrator:dev)
- `/deploy_to_docker task-orchestrator:local-test` (use specific tag)

---

## Deployment Workflow

Execute the following steps to build and deploy the Docker image:

### Step 0: Determine Image Tag (If Not Provided)

**If the user did not provide an image tag** (e.g., just `/deploy_to_docker` without arguments), ask them using AskUserQuestion:

```
AskUserQuestion(
  questions: [{
    question: "Which Docker image tag would you like to use?",
    header: "Image Tag",
    multiSelect: false,
    options: [
      {
        label: "task-orchestrator:dev",
        description: "Default development tag (recommended)"
      }
    ]
  }]
)
```

**Note:** The AskUserQuestion tool automatically provides an "Other" option where users can type a custom tag name (e.g., `task-orchestrator:local-test`, `task-orchestrator:v2.0`, etc.).

**Based on user choice:**
- **"task-orchestrator:dev"**: Use the default tag
- **"Other" with custom input**: Use the custom tag provided by the user

Store the selected tag and use it for the rest of the workflow as `<image-tag>`.

**If the user DID provide a tag** (e.g., `/deploy_to_docker task-orchestrator:local-test`), skip this step and use the provided tag.

### Step 1: Clean Build (Optional but Recommended)

Run a clean build first to ensure all changes are compiled:

```bash
./gradlew clean build
```

Wait for confirmation that all tests pass before proceeding.

### Step 2: Build Docker Image

Build the Docker image with the determined tag (from Step 0 if prompted, or from command arguments).

**Command:**
```bash
docker build -t <image-tag> .
```

Where `<image-tag>` is the tag from Step 0 or provided by the user (e.g., `task-orchestrator:dev`, `task-orchestrator:local-test`).

### Step 3: Verify Image Built Successfully

After build completes, verify the image exists:

```bash
docker images | grep <image-name>
```

Where `<image-name>` is the base name from the tag (e.g., `task-orchestrator`).

### Step 4: Run Docker Container

**Ask the user about container status** (use AskUserQuestion):

```
AskUserQuestion(
  questions: [{
    question: "Do you want to start a new container with the deployed image?",
    header: "Container",
    multiSelect: false,
    options: [
      {
        label: "Start New Container",
        description: "Run a new container with the deployed image"
      },
      {
        label: "Container Already Running",
        description: "Skip container startup, proceed to testing"
      },
      {
        label: "Skip Container Startup",
        description: "Just build the image, don't start a container"
      }
    ]
  }]
)
```

**Based on user choice:**

- **"Start New Container"**: Continue to ask about run options below
- **"Container Already Running"**: Skip to Step 5 (testing)
- **"Skip Container Startup"**: End deployment workflow

---

**If starting new container**, ask which run option to use (use AskUserQuestion):

```
AskUserQuestion(
  questions: [{
    question: "Which Docker run configuration would you like to use?",
    header: "Run Mode",
    multiSelect: false,
    options: [
      {
        label: "Basic Run",
        description: "Database only (no config file access)"
      },
      {
        label: "With Project Mount",
        description: "Recommended - Enables config reading (.taskorchestrator/)"
      },
      {
        label: "With Debug Logging",
        description: "Project mount + MCP_DEBUG=true for detailed logs"
      }
    ]
  }]
)
```

**Based on user choice, run the appropriate command:**

**"Basic Run" (Database Only)**
```bash
docker run --rm -i -v mcp-task-data:/app/data <image-tag>
```

**"With Project Mount" (Recommended)**
```bash
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v D:/Projects/task-orchestrator:/project \
  -e AGENT_CONFIG_DIR=/project \
  <image-tag>
```

**"With Debug Logging"**
```bash
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v D:/Projects/task-orchestrator:/project \
  -e AGENT_CONFIG_DIR=/project \
  -e MCP_DEBUG=true \
  <image-tag>
```

### Step 5: Test the Deployment

After the container is running, suggest testing basic functionality:

1. Verify server starts without errors
2. Test a simple MCP tool (e.g., `list_templates`)
3. Verify configuration files are accessible (if using Option 2)

---

## Important Notes

- **Database Persistence:** The volume `mcp-task-data` persists database between container runs
- **Config Access:** Option 2 is required for reading `.taskorchestrator/config.yaml` and `agent-mapping.yaml`
- **Port Mapping:** MCP servers use stdin/stdout, no port mapping needed
- **Cleanup:** Use `--rm` flag to auto-remove container after stopping
- **Hot Reload:** If container is already running, rebuild with same tag and restart container to load new image

## Troubleshooting

**If build fails:**
- Check that `./gradlew build` completed successfully
- Verify Dockerfile exists in project root
- Check for sufficient disk space

**If container fails to start:**
- Check logs with `docker logs <container-id>`
- Verify volume paths are correct (Windows uses forward slashes in Docker)
- Try Option 3 (debug mode) for detailed logging

**If config files not found:**
- Ensure using Option 2 with AGENT_CONFIG_DIR set
- Verify project path is correct: `D:/Projects/task-orchestrator`
- Check that `.taskorchestrator/` directory exists in project

---

## Post-Deployment

After successful deployment:
1. ✅ Confirm container is running (or already was running)
2. ✅ Verify MCP tools are accessible
3. ✅ Test configuration file loading (if applicable)
4. ✅ Inform user of deployment success

Return a summary of what was deployed and how to stop/restart the container:

**Stop container:**
```bash
docker stop <container-id>
```

**Restart container with new image:**
```bash
# Stop existing container first
docker stop <container-id>
# Then run with same command as Step 4
```

Or simply press Ctrl+C if running in foreground with `-i` flag.

---

## Final Step: Reconnect MCP Server

**After deployment is complete, reconnect the MCP server to load the new image:**

Use the slash command:
```
/mcp reconnect task-orchestrator
```

This ensures Claude Code is using the newly deployed Docker image with all the latest changes.
