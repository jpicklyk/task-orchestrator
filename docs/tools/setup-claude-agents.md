# setup_claude_agents Tool - Detailed Documentation

## Overview

Initializes the Claude Code agent orchestration system by creating directory structures, installing agent definitions, and configuring agent mappings. This is the first step to enable the 3-level agent coordination architecture for scalable AI workflows.

**Resource**: `task-orchestrator://docs/tools/setup-claude-agents`

## Key Concepts

### Agent Orchestration System

The setup tool installs a **3-level agent coordination architecture**:
- **Level 1**: Feature Manager - coordinates tasks within a feature
- **Level 2**: Task Manager - routes tasks to specialists
- **Level 3**: Specialists - Backend, Frontend, Database, Test, etc.

### What Gets Installed

1. **Agent Definition Files** (`.claude/agents/`)
   - 10 specialized agent markdown files with YAML frontmatter
   - Claude Code automatically discovers agents in this directory
   - Files include agent instructions, capabilities, and workflows

2. **Agent Mapping Configuration** (`.taskorchestrator/agent-mapping.yaml`)
   - Maps task tags to agent names
   - Defines section tags for efficient context retrieval
   - Customizable for project-specific needs

3. **Decision Gates** (`CLAUDE.md`)
   - Injects proactive routing decision points
   - Helps orchestrator decide when to launch agents
   - Preserves existing CLAUDE.md content

### Idempotent Operation

The tool is **safe to run multiple times**:
- Skips existing files (won't overwrite customizations)
- Only creates missing files
- Reports what was created vs. skipped
- Can be used to restore deleted agent files

## Parameter Reference

### Parameters

**None** - This tool requires no parameters.

Simply invoke: `setup_claude_agents()`

## Installed Agents

### Coordination Agents

| Agent | Model | Role |
|-------|-------|------|
| **Feature Manager** | Sonnet | Analyzes feature progress, recommends next task |
| **Task Manager** | Sonnet | Routes tasks to specialists, manages dependency context |

### Specialist Agents

| Agent | Model | Specialization |
|-------|-------|----------------|
| **Backend Engineer** | Sonnet | REST APIs, services, business logic |
| **Database Engineer** | Sonnet | Schema design, migrations, queries |
| **Frontend Developer** | Sonnet | UI components, client-side logic |
| **Test Engineer** | Sonnet | Unit tests, integration tests, test automation |
| **Technical Writer** | Sonnet | Documentation, API docs, guides |

### Planning Agents

| Agent | Model | Role |
|-------|-------|------|
| **Feature Architect** | Opus | Feature design from user requirements |
| **Planning Specialist** | Sonnet | Task breakdown and decomposition (cost optimized) |
| **Bug Triage Specialist** | Sonnet | Bug investigation and prioritization |

## Usage Patterns

### Pattern 1: First-Time Setup

Initialize the agent system for a new project.

```json
{}
```

**Expected Output**:
```json
{
  "success": true,
  "message": "Claude Code agent system setup completed successfully. Created 10 agent file(s). Created agent-mapping.yaml. Injected decision gates into CLAUDE.md.",
  "data": {
    "directoryCreated": true,
    "agentFilesCreated": [
      "backend-engineer.md",
      "database-engineer.md",
      "frontend-developer.md",
      "test-engineer.md",
      "technical-writer.md",
      "feature-architect.md",
      "planning-specialist.md",
      "feature-manager.md",
      "task-manager.md",
      "bug-triage-specialist.md"
    ],
    "agentFilesSkipped": [],
    "totalAgents": 10,
    "agentMappingCreated": true
  }
}
```

**When to use**:
- First time using agent orchestration
- After cloning repository
- Setting up new project

### Pattern 2: Restore Deleted Files

Restore agent files that were accidentally deleted.

```json
{}
```

**Expected Output**:
```json
{
  "success": true,
  "message": "Claude Code agent system setup verified. Created 3 agent file(s). Skipped 7 existing agent file(s).",
  "data": {
    "directoryCreated": false,
    "agentFilesCreated": [
      "backend-engineer.md",
      "database-engineer.md"
    ],
    "agentFilesSkipped": [
      "frontend-developer.md",
      "test-engineer.md",
      // ... other existing files
    ],
    "totalAgents": 10
  }
}
```

**When to use**:
- Agent files accidentally deleted
- Upgrading to new agent definitions
- Verifying installation

### Pattern 3: Verify Existing Installation

Check if agents are already installed.

```json
{}
```

**Expected Output**:
```json
{
  "success": true,
  "message": "Claude Code agent system setup verified. Skipped 10 existing agent file(s).",
  "data": {
    "directoryCreated": false,
    "agentFilesCreated": [],
    "agentFilesSkipped": [
      "backend-engineer.md",
      // ... all 10 agent files
    ],
    "totalAgents": 10
  }
}
```

**When to use**:
- Verifying setup completed successfully
- Checking installation status
- Before using agent features

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Claude Code agent system setup completed successfully. Created N agent file(s).",
  "data": {
    "directoryCreated": true,
    "agentFilesCreated": ["agent1.md", "agent2.md"],
    "agentFilesSkipped": ["agent3.md"],
    "directory": "/absolute/path/to/.claude",
    "totalAgents": 10,
    "taskOrchestratorDirCreated": true,
    "agentMappingCreated": true,
    "agentMappingPath": "/absolute/path/to/.taskorchestrator/agent-mapping.yaml",
    "decisionGatesInjected": true
  }
}
```

**Response Fields**:
- `directoryCreated`: Whether `.claude/agents/` was newly created
- `agentFilesCreated`: List of agent files that were newly created
- `agentFilesSkipped`: List of agent files that already existed
- `directory`: Absolute path to `.claude` directory
- `totalAgents`: Total number of agent definitions (created + skipped)
- `taskOrchestratorDirCreated`: Whether `.taskorchestrator/` was newly created
- `agentMappingCreated`: Whether `agent-mapping.yaml` was newly created
- `agentMappingPath`: Absolute path to agent mapping configuration
- `decisionGatesInjected`: Whether decision gates were added to CLAUDE.md

## Directory Structure

After running `setup_claude_agents`, your project will have:

```
project-root/
├── .claude/
│   └── agents/
│       ├── backend-engineer.md
│       ├── database-engineer.md
│       ├── frontend-developer.md
│       ├── test-engineer.md
│       ├── technical-writer.md
│       ├── feature-architect.md
│       ├── planning-specialist.md
│       ├── feature-manager.md
│       ├── task-manager.md
│       └── bug-triage-specialist.md
└── .taskorchestrator/
    └── agent-mapping.yaml
```

## Agent Definition Format

Each agent file uses **YAML frontmatter** with **Markdown content**:

```markdown
---
name: Backend Engineer
description: Specialized in REST APIs, services, and business logic
model: claude-sonnet-4
tools:
  - mcp__task-orchestrator__*
---

# Backend Engineer Agent

You are a backend specialist focused on REST APIs, services, and business logic.

## Workflow
1. Read task: get_task(id='...', includeSections=true)
2. Implement services, APIs, tests
3. Update sections with results
4. Run tests (REQUIRED)
5. Mark complete: set_status(id='...', status='completed')
...
```

## Next Steps After Setup

### 1. Verify Agent Availability

Check that Claude Code discovered the agents:
```
User: "List available agents"
```

Claude Code should show the 10 installed agents.

### 2. Test Agent Recommendation

Create a test task and get agent recommendation:
```json
{
  "title": "Implement user authentication API",
  "tags": "backend,api,authentication",
  "complexity": 6
}
```

Then use:
```json
{
  "taskId": "task-uuid-here"
}
```

Should recommend **Backend Engineer**.

### 3. Review Agent Mapping

Check the agent mapping configuration:
```
User: "Show me the agent mapping configuration"
```

Or read: `.taskorchestrator/agent-mapping.yaml`

### 4. Customize Agents (Optional)

Edit agent files in `.claude/agents/` to:
- Add project-specific workflows
- Include custom tool usage patterns
- Add domain-specific guidelines
- Adjust agent personalities

**Important**: After customization, commit agent files to version control for team sharing.

## Integration with Other Tools

### Recommended Workflow

1. **Setup** → `setup_claude_agents()`
2. **Create Task** → `create_task(...)` with appropriate tags
3. **Get Recommendation** → `recommend_agent(taskId)`
4. **Read Agent** → `get_agent_definition(agentName)` (optional, for review)
5. **Launch Agent** → Use Task tool with recommended agent

### Tag-Based Agent Selection

The `agent-mapping.yaml` file defines tag mappings:

```yaml
backend-engineer:
  tags:
    - backend
    - api
    - rest
    - service
  sectionTags:
    - technical-approach
    - implementation
    - api-design

database-engineer:
  tags:
    - database
    - schema
    - migration
    - sql
  sectionTags:
    - technical-approach
    - implementation
    - data-model
```

**How it works**:
1. Task tagged with `backend` + `api`
2. `recommend_agent` matches tags to `backend-engineer`
3. Returns `backend-engineer` with `sectionTags`
4. Specialist reads only relevant sections (token efficiency)

## Customization Options

### Customizing Agent Definitions

Edit `.claude/agents/[agent-name].md` to:

**Add Project Tools**:
```markdown
## Available Tools
- Project-specific linting: run_eslint()
- Code formatting: run_prettier()
- Custom deployment: deploy_to_staging()
```

**Add Domain Guidelines**:
```markdown
## Project Standards
- Use Repository pattern for data access
- All APIs must include OpenAPI documentation
- Follow OAuth 2.0 for authentication
```

**Adjust Workflows**:
```markdown
## Custom Workflow
1. Read task with sections
2. Run existing tests FIRST
3. Implement changes
4. Update tests
5. Run full test suite
6. Create Summary section
```

### Customizing Agent Mappings

Edit `.taskorchestrator/agent-mapping.yaml` to:

**Add Custom Tags**:
```yaml
backend-engineer:
  tags:
    - backend
    - api
    - microservice  # Custom tag
    - graphql       # Custom tag
```

**Add New Specialists**:
```yaml
devops-engineer:  # New agent
  tags:
    - devops
    - deployment
    - infrastructure
    - kubernetes
  sectionTags:
    - deployment-strategy
    - infrastructure-notes
```

**Then create**: `.claude/agents/devops-engineer.md`

### Version Control Recommendations

**Commit to Git**:
- ✅ `.claude/agents/*.md` - Share agent definitions with team
- ✅ `.taskorchestrator/agent-mapping.yaml` - Share tag mappings

**Add to .gitignore**:
- ❌ Don't ignore agent files (they're project configuration)

**For Teams**:
- Commit agent customizations
- Document custom workflows in agent files
- Review agent file changes in PRs
- Keep agent definitions synchronized across team

## Common Workflows

### Workflow 1: Initial Project Setup

```
User: "Setup Task Orchestrator with agent support"

You:
1. setup_claude_agents()
2. Verify agent files created
3. Explain agent system to user
4. Suggest creating first feature/task
```

### Workflow 2: Adding Custom Agent

```
User: "Add a DevOps engineer agent"

You:
1. Create .claude/agents/devops-engineer.md
2. Update .taskorchestrator/agent-mapping.yaml
3. Test with recommend_agent on DevOps task
4. Commit files to version control
```

### Workflow 3: Upgrading Agent Definitions

```
User: "Update agent definitions to latest version"

You:
1. Backup existing agent files (if customized)
2. Run setup_claude_agents()
3. Review agentFilesSkipped in response
4. Manually merge customizations if needed
```

## Error Handling

### Directory Creation Failed

```json
{
  "success": false,
  "message": "Failed to setup Claude Code agent system",
  "error": {
    "code": "INTERNAL_ERROR",
    "details": "Permission denied creating directory .claude/agents/"
  }
}
```

**Common Causes**:
- Insufficient file system permissions
- Read-only file system
- Disk full

**Solutions**:
- Check directory permissions
- Run with appropriate privileges
- Ensure sufficient disk space

### Resource Extraction Failed

```json
{
  "success": false,
  "message": "Failed to setup Claude Code agent system",
  "error": {
    "code": "INTERNAL_ERROR",
    "details": "Failed to extract agent template: backend-engineer.md"
  }
}
```

**Common Causes**:
- JAR file corrupted
- Missing embedded resources
- File system issues

**Solutions**:
- Rebuild application: `./gradlew clean build`
- Verify JAR integrity
- Check application logs for details

## Best Practices

1. **Run Setup Once**: Only need to run on first setup or when restoring files
2. **Commit Agent Files**: Share customizations with team via version control
3. **Document Customizations**: Add comments to customized agent files
4. **Test After Setup**: Verify agent recommendations work with test tasks
5. **Review Agent Mappings**: Ensure tag mappings match your project's conventions
6. **Update CLAUDE.md**: Decision gates help with proactive agent routing
7. **Keep Agents Current**: Re-run setup after major updates (preserves customizations)

## Related Tools

- **recommend_agent**: Get agent recommendation for a task (uses agent mappings)
- **get_agent_definition**: Read agent definition file contents
- **create_task**: Create tasks with tags that trigger agent recommendations
- **get_overview**: Check task/feature status before agent work

## See Also

- Agent Orchestration Guide: `task-orchestrator://docs/agent-orchestration`
- Agent Customization: `task-orchestrator://guidelines/agent-customization`
- Workflow Integration: `task-orchestrator://guidelines/workflow-integration`
- Tag Conventions: `task-orchestrator://guidelines/tagging-strategy`

## Frequently Asked Questions

### Q: Can I run this multiple times?

**A**: Yes! The tool is idempotent. It will only create missing files and skip existing ones.

### Q: Will it overwrite my customizations?

**A**: No. Existing files are skipped. Your customizations are safe.

### Q: Do I need to commit agent files to git?

**A**: Yes, recommended. Agent files are project configuration that should be shared with your team.

### Q: Can I customize the agents?

**A**: Absolutely! Edit `.claude/agents/*.md` files and `.taskorchestrator/agent-mapping.yaml` to match your project needs.

### Q: What if I only use Claude Desktop, not Claude Code?

**A**: Agent orchestration is Claude Code specific. Templates and workflow prompts work with all MCP clients (Claude Desktop, Cursor, Windsurf, etc.).

### Q: How do I add a new custom agent?

**A**: Create a new `.md` file in `.claude/agents/`, add mapping to `agent-mapping.yaml`, and test with `recommend_agent`.

### Q: Can I delete agents I don't need?

**A**: Yes, but they won't be recommended anyway. Safer to leave them (they only take disk space, not context).

### Q: What happens if decision gates already exist in CLAUDE.md?

**A**: The tool detects existing gates and skips injection to avoid duplicates.
