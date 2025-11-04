# Task Orchestrator Communication Style Plugin

This plugin provides professional coordinator communication style for Task Orchestrator workflows.

## What It Does

Automatically injects Task Orchestrator communication guidelines at the start of each Claude Code session, enabling:

- **Professional coordinator voice** - decisive, concise, transparent
- **Clear status indicators** (✅ ⚠️ ❌ 🔄)
- **Structured responses** - Status first, action second, context last
- **Phase-based coordination** - Clear workflow progression
- **Consistent formatting** - Standardized skill/subagent references

## Installation

This plugin is installed through the Claude Code plugin marketplace:

1. **From local marketplace** (development):
   ```
   /plugin marketplace add ./
   /plugin install task-orchestrator
   ```

2. **From GitHub** (production):
   ```
   /plugin install jpicklyk/task-orchestrator
   ```

After installation, run `setup_project` to initialize Task Orchestrator for your project.

## Usage

Once installed, the communication style is automatically active in all Claude Code sessions. No configuration needed.

**Disable**: Remove or rename the plugin directory in `.claude/plugins/task-orchestrator/`

## Token Cost

This plugin adds approximately **600 tokens** to each session context. The guidelines help maintain consistency across orchestration workflows.

## Version

**Version**: 2.0.0
**Replaces**: Deprecated output-style system from Task Orchestrator v1.x

## Support

Part of [MCP Task Orchestrator](https://github.com/jpicklyk/task-orchestrator)
