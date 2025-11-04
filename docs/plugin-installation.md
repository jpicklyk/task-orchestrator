# Plugin Installation Guide

Task Orchestrator is packaged as a Claude Code plugin that bundles the MCP server, Skills, Subagents, and Hooks together for easy installation.

## Prerequisites

1. **Claude Code** installed and running
2. **Docker** installed and running (for MCP server)
3. **Git** (for cloning the repository)

## Installation Methods

### Method 1: Local Marketplace Installation (Recommended for Development)

If you're working on or contributing to the Task Orchestrator project:

1. **Clone or navigate to the repository:**
   ```bash
   cd /path/to/task-orchestrator
   ```

2. **Add the local marketplace:**
   ```
   /plugin marketplace add ./
   ```

   This registers the `.claude-plugin/marketplace.json` with Claude Code.

3. **Install the plugin:**
   ```
   /plugin install task-orchestrator@task-orchestrator-marketplace
   ```

4. **Restart Claude Code** to load the plugin

**Directory Structure:**
```
task-orchestrator/
├── .claude-plugin/
│   └── marketplace.json          # Marketplace definition
└── claude-plugins/
    └── task-orchestrator/         # Plugin files
        ├── plugin.json
        ├── agents/
        ├── skills/
        ├── hooks/
        └── .mcp.json
```

### Method 2: From GitHub Repository

To install Task Orchestrator in any project:

1. **Add the GitHub marketplace:**
   ```
   /plugin marketplace add jpicklyk/task-orchestrator
   ```

2. **Install the plugin:**
   ```
   /plugin install task-orchestrator
   ```

3. **Restart Claude Code**

**Alternative**: Add via settings configuration in `~/.claude/settings.json`:
```json
{
  "pluginMarketplaces": [
    "jpicklyk/task-orchestrator"
  ]
}
```

### Method 3: Manual Installation

For advanced users or custom configurations:

1. **Clone the repository:**
   ```bash
   git clone https://github.com/jpicklyk/task-orchestrator.git
   cd task-orchestrator
   ```

2. **Copy the plugin directory:**
   ```bash
   cp -r .claude-plugin/task-orchestrator ~/.claude/plugins/
   ```

3. **Restart Claude Code**

## First-Time Setup

After installation, initialize Task Orchestrator for your project:

1. **Initialize project configuration:**
   ```
   setup_project
   ```

   This creates:
   - `.taskorchestrator/config.yaml` - Status workflows and validation rules
   - `.taskorchestrator/status-workflow-config.yaml` - Workflow definitions
   - `.taskorchestrator/agent-mapping.yaml` - Agent routing configuration

2. **Verify MCP server connection:**
   ```
   /mcp task-orchestrator
   ```

   You should see "MCP server 'task-orchestrator' has been enabled."

3. **Verify components loaded:**
   - Check Skills: Skills should auto-activate when relevant
   - Check Subagents: Available in Task tool
   - Check Hooks: Session start hook should provide context

## What Gets Installed

The Task Orchestrator plugin includes:

### 🔧 MCP Server
- **task-orchestrator** - 18 unified tools for task management
- SQLite database for persistence
- Docker-based deployment

### 🤖 Subagents (4 agents)
- Implementation Specialist (Haiku) - Standard implementation
- Senior Engineer (Sonnet) - Complex problems and debugging
- Feature Architect (Opus) - Feature design and architecture
- Planning Specialist (Sonnet) - Task breakdown

### ⚡ Skills (10+ skills)
- feature-orchestration - Feature lifecycle management
- task-orchestration - Task management
- dependency-analysis - Blocker identification
- dependency-orchestration - Dependency management
- status-progression - Status workflow validation
- Plus implementation skills and builder tools

### 🪝 Hooks
- SessionStart - Project context and overview at session start

## Verification Steps

### Check Plugin Installation

```
/plugin list
```

You should see `task-orchestrator` in the list of installed plugins.

### Test MCP Server

```javascript
// Get project overview
query_container(operation="overview", containerType="project")

// List templates
query_templates(operation="list", targetEntityType="TASK", isEnabled=true)
```

### Test Skills

Skills auto-activate from natural language. Try:
- "What should I work on next?"
- "Show me what's blocking"
- "Complete this task"

### Test Subagents

```
recommend_agent(taskId="your-task-id")
```

## Troubleshooting

### Plugin Not Loading

1. **Check marketplace.json exists:**
   ```bash
   ls .claude-plugin/marketplace.json
   ```

2. **Check plugin.json exists:**
   ```bash
   ls .claude-plugin/task-orchestrator/plugin.json
   ```

3. **Restart Claude Code:**
   ```
   /restart
   ```

### MCP Server Not Connecting

1. **Verify Docker is running:**
   ```bash
   docker ps
   ```

2. **Check Docker image exists:**
   ```bash
   docker images | grep task-orchestrator
   ```

3. **Pull latest image:**
   ```bash
   docker pull ghcr.io/jpicklyk/mcp-task-orchestrator:latest
   ```

4. **Check .mcp.json configuration:**
   ```bash
   cat .claude-plugin/task-orchestrator/.mcp.json
   ```

### Skills Not Activating

1. **Check skills directory exists:**
   ```bash
   ls .claude-plugin/task-orchestrator/skills/
   ```

2. **Verify SKILL.md files:**
   ```bash
   find .claude-plugin/task-orchestrator/skills -name "SKILL.md"
   ```

3. **Restart Claude Code**

### Hooks Not Running

1. **Check hooks configuration:**
   ```bash
   cat .claude-plugin/task-orchestrator/hooks/hooks.json
   ```

2. **Verify hook handler exists:**
   ```bash
   ls .claude-plugin/task-orchestrator/hooks-handlers/session-start.sh
   ```

3. **Make hook handler executable:**
   ```bash
   chmod +x .claude-plugin/task-orchestrator/hooks-handlers/session-start.sh
   ```

## Updating the Plugin

### From Local Marketplace

1. **Pull latest changes:**
   ```bash
   cd /path/to/task-orchestrator
   git pull origin main
   ```

2. **Reinstall plugin:**
   ```
   /plugin uninstall task-orchestrator
   /plugin install task-orchestrator
   ```

3. **Restart Claude Code**

### From GitHub

1. **Update plugin:**
   ```
   /plugin update task-orchestrator
   ```

2. **Restart Claude Code**

## Uninstallation

To remove Task Orchestrator:

1. **Uninstall plugin:**
   ```
   /plugin uninstall task-orchestrator
   ```

2. **Remove project configuration (optional):**
   ```bash
   rm -rf .taskorchestrator/
   ```

3. **Remove Docker data (optional):**
   ```bash
   docker volume rm mcp-task-data
   ```

## Configuration

### Project-Specific Settings

Edit `.taskorchestrator/config.yaml` to customize:
- Status workflows
- Validation rules
- Quality gates

### Agent Routing

Edit `.taskorchestrator/agent-mapping.yaml` to customize:
- Task tag → Specialist mappings
- Section tags for context loading

### MCP Server Settings

Edit `.claude-plugin/task-orchestrator/.mcp.json` to customize:
- Docker image version
- Volume mounts
- Environment variables

## Support

- **Documentation:** [docs/](https://github.com/jpicklyk/task-orchestrator/tree/main/docs)
- **Issues:** [GitHub Issues](https://github.com/jpicklyk/task-orchestrator/issues)
- **Discussions:** [GitHub Discussions](https://github.com/jpicklyk/task-orchestrator/discussions)

## Next Steps

After installation:

1. Read [Quick Start Guide](quick-start.md)
2. Review [Skills Guide](skills-guide.md)
3. Explore [Agent Orchestration](agent-orchestration.md)
4. Learn about [Templates](templates.md)
5. Understand [Status Progression](status-progression.md)
