# Task Orchestrator Plugin

Comprehensive task management plugin for Claude Code with Projects → Features → Tasks hierarchy, dependency tracking, templates, and AI workflow automation.

## What's Included

This plugin packages together:

### Subagents (4 agents)
Specialized agents for complex implementation work:
- **Implementation Specialist** (Haiku) - Standard implementation with dynamically loaded Skills
- **Senior Engineer** (Sonnet) - Complex problems, debugging, performance optimization, blockers
- **Feature Architect** (Opus) - Feature design, PRDs, and architecture decisions
- **Planning Specialist** (Sonnet) - Task breakdown and dependency planning

### Skills (10+ skills)
Lightweight coordination workflows (2-5 tool calls, 60-82% token savings):
- **feature-orchestration** - Feature lifecycle management and completion
- **task-orchestration** - Task management and status updates
- **dependency-analysis** - Blocker identification and dependency tracking
- **dependency-orchestration** - Dependency management workflows
- **status-progression** - Status workflow validation and progression
- **backend-implementation** - Backend code implementation patterns
- **database-implementation** - Database schema and migration patterns
- **documentation-implementation** - Documentation writing workflows
- **frontend-implementation** - Frontend component patterns
- **testing-implementation** - Test writing and validation patterns
- Plus: **hook-builder**, **skill-builder**, **subagent-builder** for customization

### Hooks
- **SessionStart** - Provides project context and task overview at session start

## Installation

### Via Plugin Marketplace (Recommended for GitHub Installation)

**From GitHub** (after repository is published):

1. Add the GitHub marketplace:
   ```
   /plugin marketplace add jpicklyk/task-orchestrator
   ```

2. Install the plugin:
   ```
   /plugin install task-orchestrator
   ```

3. Restart Claude Code

**From local repository** (for development):

1. Navigate to the task-orchestrator directory:
   ```bash
   cd /path/to/task-orchestrator
   ```

2. Add the local marketplace:
   ```
   /plugin marketplace add ./
   ```

3. Install the plugin:
   ```
   /plugin install task-orchestrator@task-orchestrator-marketplace
   ```

4. Restart Claude Code

### Manual Installation

1. Ensure Docker is installed and running
2. Copy `.claude-plugin/` directory to your project root
3. Restart Claude Code

## Quick Start

### First-Time Setup

1. Initialize Task Orchestrator configuration:
   ```
   setup_project
   ```
   This creates `.taskorchestrator/` directory with configuration files.

2. Verify MCP server is running:
   ```
   /mcp task-orchestrator
   ```

### Basic Workflows

**Create a feature:**
```
"Create a feature for user authentication"
```
→ Feature Architect subagent designs the feature with templates

**Break down into tasks:**
```
"Break down the authentication feature into tasks"
```
→ Planning Specialist creates dependency-aware task breakdown

**Check what's next:**
```
"What should I work on next?"
```
→ Feature Management Skill recommends next task

**Implement a task:**
```
"Implement task [task-id]"
```
→ Automatically routes to appropriate specialist (Backend/Frontend/Database/Test/Technical Writer)

**Complete a task:**
```
"Complete task [task-id]"
```
→ Task Management Skill validates and marks complete

## Decision Gates (Claude Code)

**Quick routing for Skills vs Subagents:**

### When User Asks About Progress/Status/Coordination
→ **Use Skills** (60-82% token savings):
- "What's next?" → Feature Management Skill
- "Complete feature/task" → Feature/Task Management Skill
- "What's blocking?" → Dependency Analysis Skill

### When User Requests Implementation Work
→ **Use Subagents** (direct specialist routing):
- "Create feature for X" / rich context (3+ paragraphs) → Feature Architect (Opus)
- "Implement X" / task with code → `recommend_agent(taskId)` routes to specialist (Backend/Frontend/Database/Test/Technical Writer)
- "Fix bug X" / "broken"/"error" → Bug Triage Specialist (Sonnet)
- "Break down X" → Planning Specialist (Sonnet)

### Specialist Routing with recommend_agent

**Purpose**: Automatically route tasks to appropriate specialists based on task tags and requirements.

**Usage**:
```javascript
recommend_agent(taskId="task-uuid")
```

**Returns**: Recommended specialist agent name based on task analysis:
- Tags contain `backend`, `api`, `service` → Backend Engineer
- Tags contain `frontend`, `ui`, `component` → Frontend Developer
- Tags contain `database`, `migration`, `schema` → Database Engineer
- Tags contain `test`, `testing` → Test Engineer
- Tags contain `documentation`, `docs` → Technical Writer
- Tags contain `bug`, `fix`, `error` → Bug Triage Specialist
- Feature-level tasks or complex architecture → Feature Architect
- Task breakdown or planning → Planning Specialist

**Workflow Pattern**:
1. User: "Implement task X"
2. You: `recommend_agent(taskId)` → returns "Backend Engineer"
3. You: Launch Backend Engineer subagent with task ID
4. Backend Engineer: Reads task, implements code, updates sections, uses Status Progression Skill to mark complete, returns summary
5. You: Verify completion and inform user

### Critical Patterns
- **Always** run `list_templates` before creating tasks/features
- Feature Architect (Opus) creates feature → Planning Specialist (Sonnet) breaks into tasks → Specialists implement
- **Token optimization**: Specialists return minimal summaries (50-100 tokens), full work goes in sections/files
- **Self-service**: Specialists read task context directly, no manager intermediary needed
- Use `recommend_agent(taskId)` for automatic specialist routing based on task tags

For detailed decision matrices and examples, see [CLAUDE.md](#) in the project documentation.

## Architecture

### Specialist Workflow (Self-Service)

All specialists follow a standardized workflow:
1. Read task context via `query_container(operation="get", ...)`
2. Perform implementation work (code, tests, documentation)
3. Update task sections via `manage_sections(...)`
4. Use Status Progression Skill to mark complete
5. Return brief summary (50-100 tokens)

**Token Efficiency**: Specialists return minimal summaries; detailed work goes in task sections and code files.

## Configuration

### Status Workflows

Edit `.taskorchestrator/config.yaml` to customize status workflows:
- default_flow, bug_fix_flow, documentation_flow
- Prerequisite requirements for status transitions
- Validation rules

### Agent Routing

Edit `.taskorchestrator/agent-mapping.yaml` to customize specialist routing:
- Map task tags to specialist agents
- Define section tags for efficient context loading

### MCP Server Configuration

The MCP server runs in Docker with volume mounts:
- `mcp-task-data:/app/data` - SQLite database persistence
- Project mount for config file access

## Tools Overview

### Unified Container Tools (v2.0)
- **manage_container** - Create, update, delete, setStatus, bulkUpdate
- **query_container** - Get, search, export, overview operations

### Section Management
- **manage_sections** - Add, update, delete, reorder, bulk operations
- **query_sections** - Read sections with filtering and token optimization

### Dependency Management
- **manage_dependency** - Create/delete task dependencies
- **query_dependencies** - Query with filtering and task info

### Workflow Optimization
- **get_next_task** - Intelligent task recommendations
- **get_blocked_tasks** - Dependency blocking analysis
- **get_next_status** - Status progression recommendations

### Templates & Configuration
- **query_templates**, **manage_template**, **apply_template**
- **query_workflow_state**, **get_next_status**

## Token Efficiency

**v2.0 Consolidation** - 68% token reduction:
- 56 tools → 18 unified tools
- ~84k → ~36k characters across all tool definitions

**Scoped Overview Pattern** - 85-95% token savings:
```javascript
// Instead of get with includeSections=true (18.5k tokens)
query_container(operation="overview", containerType="feature", id="...")
// Returns hierarchical view without sections (1.2k tokens)
```

**Skills vs Direct Work** - 60-82% token savings:
- Skills load only when needed (~500-600 tokens)
- Direct MCP tool calls have minimal overhead
- Use Skills for repetitive coordination patterns

## Documentation

- [Quick Start](https://github.com/jpicklyk/task-orchestrator/blob/main/docs/quick-start.md)
- [API Reference](https://github.com/jpicklyk/task-orchestrator/blob/main/docs/api-reference.md)
- [Skills Guide](https://github.com/jpicklyk/task-orchestrator/blob/main/docs/skills-guide.md)
- [Agent Orchestration](https://github.com/jpicklyk/task-orchestrator/blob/main/docs/agent-orchestration.md)
- [Status Progression](https://github.com/jpicklyk/task-orchestrator/blob/main/docs/status-progression.md)
- [Templates](https://github.com/jpicklyk/task-orchestrator/blob/main/docs/templates.md)

## Cross-Platform Compatibility

**Fully supported on Windows, macOS, and Linux:**
- Hooks use Node.js (guaranteed to be available with Claude Code)
- MCP server runs in Docker (cross-platform)
- All Skills and Subagents work identically across platforms

No platform-specific configuration required!

## Support

- [GitHub Issues](https://github.com/jpicklyk/task-orchestrator/issues)
- [Documentation](https://github.com/jpicklyk/task-orchestrator/tree/main/docs)

## License

MIT License - See [LICENSE](https://github.com/jpicklyk/task-orchestrator/blob/main/LICENSE) for details.
