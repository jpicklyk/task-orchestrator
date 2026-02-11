---
layout: default
title: Claude Code Plugin
---

# Claude Code Plugin

The MCP Task Orchestrator ships a Claude Code plugin that adds skills, hooks, and workflows to enhance your experience with the MCP server. The plugin is distributed via the project's built-in marketplace.

## What the Plugin Provides

| Component | Description |
|-----------|-------------|
| **SessionStart hook** | Automatically prompts `query_container(operation="overview")` at the start of each session |
| **task-orchestration skill** | Task creation, updates, completion patterns, and section management |
| **feature-orchestration skill** | Feature lifecycle, task breakdown, overview queries, and bulk operations |
| **status-progression skill** | Workflow triggers, transitions, emergency statuses, and cascade events |
| **dependency-analysis skill** | Blocking analysis, prerequisite management, and work ordering |
| **project-summary skill** | Formatted project dashboard with feature progress, action items, and housekeeping suggestions |
| **Task Orchestrator output style** | Optional orchestrator mode — strips coding instructions, adds delegation and workflow patterns |

## Installation

### From GitHub (recommended for MCP consumers)

Add the marketplace and install the plugin:

```
/plugin marketplace add jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

### From a Local Clone

If you've cloned the repository:

```
/plugin marketplace add .
/plugin install task-orchestrator@task-orchestrator-marketplace
```

### For Development/Testing

Load the plugin directly without installation:

```bash
claude --plugin-dir ./claude-plugins/task-orchestrator
```

### For Project Developers

If you're working on the task-orchestrator codebase, the plugin is auto-discovered via `extraKnownMarketplaces` in `.claude/settings.json`. You'll be prompted to install it when you trust the project.

## Plugin Structure

```
claude-plugins/task-orchestrator/
├── .claude-plugin/
│   └── plugin.json          # Plugin manifest (name, version, metadata)
├── skills/
│   ├── task-orchestration/
│   │   └── SKILL.md         # Task lifecycle patterns
│   ├── feature-orchestration/
│   │   └── SKILL.md         # Feature management patterns
│   ├── status-progression/
│   │   └── SKILL.md         # Status workflow guidance
│   ├── dependency-analysis/
│   │   └── SKILL.md         # Dependency and blocker patterns
│   ├── project-summary/
│   │   └── SKILL.md         # Project dashboard and status overview
├── hooks/
│   └── hooks.json           # SessionStart hook configuration
├── output-styles/
│   └── orchestrator.md      # Team orchestrator output style
└── scripts/
    └── session-start.sh     # Hook script (overview prompt)
```

## Skills Overview

### task-orchestration

Activated when working with individual tasks. Covers:
- Creating tasks with templates (`query_templates` -> `manage_container`)
- Partial updates for efficiency (only send changed fields)
- Section management (`query_sections`, `manage_sections`)
- Status progression with named triggers
- Tag conventions that drive workflow selection

### feature-orchestration

Activated when managing features and their child tasks. Covers:
- Feature creation with templates
- Breaking features into tasks
- Progress tracking with `query_container(operation="overview")`
- Completion workflow (verify all tasks done, check sections, complete)
- Bulk operations for multiple tasks

### status-progression

Activated when changing entity status. Covers:
- `get_next_status` for read-only readiness checks
- `request_transition` with named triggers (start, complete, cancel, block, hold)
- Status flows per entity type (task, feature, project)
- Emergency transitions and cascade events
- Status roles (queue, work, review, blocked, terminal)

### project-summary

User-invocable with `/project-summary`. Covers:
- Auto-detecting project UUID from CLAUDE.md or accepting it as a parameter
- Querying project overview with feature progress and task counts
- Surfacing open action items (standalone tasks tagged `action-item`)
- Flagging housekeeping opportunities (features ready to close, stale work)

### dependency-analysis

Activated when working with task dependencies. Covers:
- Creating dependencies with direction (BLOCKS, IS_BLOCKED_BY, RELATES_TO)
- Querying dependencies by direction (incoming, outgoing)
- Finding blocked tasks (`get_blocked_tasks`)
- Resolving blocker chains
- Smart task ordering with `get_next_task`

## Output Style: Task Orchestrator

The plugin includes an optional **Task Orchestrator** output style that transforms the main Claude session into a pure orchestrator. When activated, coding instructions are stripped and replaced with orchestration patterns — the main agent plans, delegates, tracks, and reports while subagents or agent team teammates handle implementation.

### What It Does

- **Strips coding instructions** from the main agent's system prompt (default output style behavior)
- **Adds orchestration patterns**: phase management, delegation, status reporting
- **Supports both delegation models**: standard subagents (Task tool) and experimental agent teams (TeamCreate)
- **MCP-first tracking**: all persistent state managed through MCP Task Orchestrator
- **MCP-aware**: references current v2.0 tools and workflow patterns

### How to Activate

```
/output-style task-orchestrator:orchestrator
```

Or select it from the `/output-style` menu.

### When to Use

- When using **subagents** where the main session coordinates and spawned agents implement
- When using **agent teams** where the main session coordinates and teammates code
- When you want Claude to **never write code directly** and instead delegate all implementation
- For **project management sessions** focused on planning, tracking, and reviewing work

### When NOT to Use

- When you're coding directly in the main session (use the Default output style instead)
- When working solo without teams or subagents
- Skills and hooks remain active regardless of output style choice

## Updating the Plugin

To get the latest version:

```
/plugin update task-orchestrator@task-orchestrator-marketplace
```

## Uninstalling

```
/plugin uninstall task-orchestrator@task-orchestrator-marketplace
```

## Prerequisites

- Claude Code version 1.0.33 or later
- MCP Task Orchestrator server configured and running

The plugin enhances Claude's use of the MCP tools but does not configure the MCP server itself. See the [Quick Start Guide](quick-start.md) for MCP server setup.

## Marketplace

The plugin marketplace is defined at `.claude-plugin/marketplace.json` in the repository root. It follows the standard Claude Code marketplace format and can be added by pointing to the GitHub repository.

For marketplace development details, see the [Claude Code Plugin Marketplaces documentation](https://code.claude.com/docs/en/plugin-marketplaces).
