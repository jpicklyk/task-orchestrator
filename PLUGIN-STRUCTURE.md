# Plugin Structure Reference

This document describes the correct directory structure for the Task Orchestrator Claude Code plugin marketplace.

## Directory Layout

```
task-orchestrator/
├── .claude-plugin/
│   └── marketplace.json                    # Marketplace definition only
│
├── claude-plugins/                          # Plugin files directory
│   └── task-orchestrator/                   # Task Orchestrator plugin
│       ├── plugin.json                      # Plugin manifest
│       ├── .mcp.json                        # MCP server configuration
│       ├── README.md                        # Plugin documentation
│       ├── agents/                          # Subagents (4 agents)
│       │   ├── feature-architect.md
│       │   ├── implementation-specialist.md
│       │   ├── planning-specialist.md
│       │   └── senior-engineer.md
│       ├── skills/                          # Skills (10+ skills)
│       │   ├── dependency-analysis/
│       │   ├── dependency-orchestration/
│       │   ├── feature-orchestration/
│       │   ├── status-progression/
│       │   ├── task-orchestration/
│       │   ├── backend-implementation/
│       │   ├── database-implementation/
│       │   ├── documentation-implementation/
│       │   ├── frontend-implementation/
│       │   ├── testing-implementation/
│       │   └── ... (and builder skills)
│       ├── hooks/
│       │   └── hooks.json                   # Hook definitions
│       └── hooks-handlers/
│           ├── session-start.js             # SessionStart hook (Node.js - cross-platform)
│           └── session-start.sh             # SessionStart hook (legacy bash)
│
└── src/main/resources/                      # Source files (development)
    ├── claude/
    │   ├── agents/                          # Agent source files
    │   └── skills/                          # Skills source files
    └── claude-plugin/
        └── task-orchestrator/               # Legacy - no longer used
```

## Key Points

1. **`.claude-plugin/marketplace.json`** - Contains marketplace definition only (no plugin files)
2. **`claude-plugins/task-orchestrator/`** - Contains all plugin files (agents, skills, hooks, etc.)
3. **`src/main/resources/`** - Development source files (not used during installation)

## Installation Commands

### Local Development

From the task-orchestrator repository root:

```bash
# Add the marketplace
/plugin marketplace add ./

# Install the plugin
/plugin install task-orchestrator@task-orchestrator-marketplace

# Restart Claude Code
/restart
```

### From GitHub (after publishing)

```bash
# Add the GitHub marketplace
/plugin marketplace add jpicklyk/task-orchestrator

# Install the plugin
/plugin install task-orchestrator

# Restart Claude Code
/restart
```

## Marketplace Configuration

The `.claude-plugin/marketplace.json` file references the plugin location:

```json
{
  "name": "task-orchestrator-marketplace",
  "owner": {
    "name": "Task Orchestrator",
    "url": "https://github.com/jpicklyk/task-orchestrator"
  },
  "metadata": {
    "description": "MCP Task Orchestrator - Hierarchical task management with AI workflows",
    "version": "2.0.0"
  },
  "plugins": [
    {
      "name": "task-orchestrator",
      "version": "2.0.0",
      "source": "./claude-plugins/task-orchestrator",
      "strict": true
    }
  ]
}
```

The `source` field points to the plugin directory containing `plugin.json`.

## What Gets Installed

When a user runs `/plugin install task-orchestrator@task-orchestrator-marketplace`:

1. Claude Code reads `.claude-plugin/marketplace.json`
2. Finds the plugin definition with `source: "./claude-plugins/task-orchestrator"`
3. Copies the entire `claude-plugins/task-orchestrator/` directory to `~/.claude/plugins/task-orchestrator/`
4. Discovers and loads:
   - MCP server from `.mcp.json`
   - Subagents from `agents/`
   - Skills from `skills/`
   - Hooks from `hooks/`

## Maintenance

When updating the plugin:

1. **Update source files** in `src/main/resources/`
2. **Sync to plugin directory**:
   ```bash
   cp -r src/main/resources/claude/agents claude-plugins/task-orchestrator/
   cp -r src/main/resources/claude/skills claude-plugins/task-orchestrator/
   ```
3. **Update version** in `.claude-plugin/marketplace.json` and `claude-plugins/task-orchestrator/plugin.json`
4. **Commit changes** to git
5. **Users reinstall** to get updates:
   ```
   /plugin install task-orchestrator@task-orchestrator-marketplace
   /restart
   ```

## Gitignore Configuration

The `.gitignore` file should:
- ✅ **Track** `.claude-plugin/` directory
- ✅ **Track** `claude-plugins/` directory
- ❌ **Ignore** `.claude/` directory (user installations)
- ❌ **Ignore** `.taskorchestrator/` directory (user configuration)

This ensures the plugin source is versioned while user customizations remain local.

## Cross-Platform Support

### Hooks

The plugin hooks use **Node.js** for cross-platform compatibility:

**Why Node.js?**
- ✅ Guaranteed to be available (Claude Code is built on it)
- ✅ Works on Windows, macOS, and Linux
- ✅ No additional dependencies required

**Hook Implementation:**
- **session-start.js** - Node.js script (primary, cross-platform)
- **session-start.sh** - Bash script (legacy, Unix/macOS/Git Bash only)

The `hooks/hooks.json` file uses the Node.js version:
```json
{
  "type": "command",
  "command": "node ${CLAUDE_PLUGIN_ROOT}/hooks-handlers/session-start.js"
}
```

**Testing Hooks:**
```bash
# Test the Node.js hook (works everywhere)
node claude-plugins/task-orchestrator/hooks-handlers/session-start.js

# Test the bash hook (Unix/macOS/Git Bash only)
bash claude-plugins/task-orchestrator/hooks-handlers/session-start.sh
```

Both scripts produce identical JSON output with the Task Orchestrator communication style.
