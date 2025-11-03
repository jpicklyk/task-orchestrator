# get_agent_definition Tool - Detailed Documentation

## Overview

Retrieves the complete agent definition file for a specific AI agent. Returns the full Markdown content including YAML frontmatter with agent metadata, instructions, capabilities, and workflows.

**Resource**: `task-orchestrator://docs/tools/get-agent-definition`

## Key Concepts

### Agent Definition Files

Agent definitions are stored as **Markdown files with YAML frontmatter**:
- Located in `.claude/agents/` directory
- Claude Code automatically discovers agents in this directory
- Each file contains agent-specific instructions and capabilities
- Format compatible with Claude Code's agent system

### Agent Definition Structure

```markdown
---
name: Agent Name
description: Brief description
model: claude-sonnet-4 or claude-opus-4
tools:
  - mcp__task-orchestrator__*
  - other-tool-patterns
---

# Agent Instructions

[Detailed agent behavior, workflows, guidelines]
```

### When to Use This Tool

**Read agent definition when**:
- Reviewing agent capabilities before launching
- Understanding agent workflows and patterns
- Debugging agent behavior
- Customizing agent instructions
- Documenting agent system

**Don't read agent definition when**:
- Just need agent recommendation (use `recommend_agent`)
- Want to launch agent immediately (recommendation provides path)
- Checking if agents are installed (use `install Task Orchestrator plugin`)

## Parameter Reference

### Required Parameters

- **agentName** (string): Name of the agent to retrieve
  - Can include `.md` extension: `"backend-engineer.md"`
  - Or omit extension: `"backend-engineer"`
  - Case-sensitive file name matching
  - Must match exact file name in `.claude/agents/`

### Examples of Valid Agent Names

```json
{"agentName": "backend-engineer"}
{"agentName": "backend-engineer.md"}
{"agentName": "frontend-developer"}
{"agentName": "database-engineer"}
{"agentName": "feature-manager"}
{"agentName": "task-manager"}
```

## Available Agents

### Specialist Agents (Level 3)

| Agent Name | File Name | Specialization |
|------------|-----------|----------------|
| Backend Engineer | `backend-engineer.md` | REST APIs, services, business logic |
| Database Engineer | `database-engineer.md` | Schema, migrations, queries |
| Frontend Developer | `frontend-developer.md` | UI components, client-side logic |
| Test Engineer | `test-engineer.md` | Tests, test automation, coverage |
| Technical Writer | `technical-writer.md` | Documentation, API docs, guides |

### Planning Agents

| Agent Name | File Name | Role |
|------------|-----------|------|
| Feature Architect | `feature-architect.md` | Feature design from requirements |
| Planning Specialist | `planning-specialist.md` | Task breakdown, decomposition |
| Bug Triage Specialist | `bug-triage-specialist.md` | Bug investigation, prioritization |

### Coordination Agents (Level 1 & 2)

| Agent Name | File Name | Role |
|------------|-----------|------|
| Feature Manager | `feature-manager.md` | Feature progress, task recommendations |
| Task Manager | `task-manager.md` | Task routing, specialist coordination |

## Usage Patterns

### Pattern 1: Review Agent Before Launching

Check agent capabilities before using it.

```json
{
  "agentName": "backend-engineer"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Agent definition retrieved successfully",
  "data": {
    "agentName": "backend-engineer",
    "fileName": "backend-engineer.md",
    "content": "---\nname: Backend Engineer\n...",
    "filePath": ".claude/agents/backend-engineer.md"
  }
}
```

**When to use**:
- First time using an agent
- Understanding agent workflows
- Checking agent capabilities
- Before customizing agent

### Pattern 2: Debug Agent Behavior

Review agent instructions when debugging unexpected behavior.

```json
{
  "agentName": "test-engineer"
}
```

**Review the content** to understand:
- What tools the agent should use
- What workflow the agent follows
- What validation steps are required
- What output format is expected

**When to use**:
- Agent not behaving as expected
- Understanding why agent made certain decisions
- Debugging workflow issues
- Checking agent tool access

### Pattern 3: Documentation and Training

Retrieve agent definitions for documentation purposes.

```json
{
  "agentName": "feature-architect"
}
```

**Use cases**:
- Creating team documentation
- Training new team members
- Understanding agent system
- Documenting custom workflows

### Pattern 4: Compare Agent Capabilities

Retrieve multiple agents to compare specializations.

```javascript
// Get multiple agent definitions to compare
const backendAgent = await get_agent_definition({ agentName: "backend-engineer" });
const databaseAgent = await get_agent_definition({ agentName: "database-engineer" });

// Compare their tool access, workflows, capabilities
```

**When to use**:
- Deciding between multiple agents
- Understanding agent specializations
- Planning agent customizations
- Documenting agent system architecture

### Pattern 5: Verify Installation

Check if agents are properly installed.

```json
{
  "agentName": "backend-engineer"
}
```

**Success** → Agents installed correctly
**Error (RESOURCE_NOT_FOUND)** → Run `install Task Orchestrator plugin`

## Response Structure

### Success Response

```json
{
  "success": true,
  "message": "Agent definition retrieved successfully",
  "data": {
    "agentName": "backend-engineer",
    "fileName": "backend-engineer.md",
    "content": "---\nname: Backend Engineer\ndescription: Specialized in REST APIs...\nmodel: claude-sonnet-4\ntools:\n  - mcp__task-orchestrator__*\n---\n\n# Backend Engineer Agent\n\nYou are a backend specialist...",
    "filePath": ".claude/agents/backend-engineer.md"
  }
}
```

**Response Fields**:
- `agentName`: Original agent name from request (without extension if omitted)
- `fileName`: Full file name with `.md` extension
- `content`: Complete file content (YAML frontmatter + Markdown)
- `filePath`: Relative path to agent file

### Agent Content Structure

The `content` field contains:

**YAML Frontmatter** (metadata):
```yaml
---
name: Backend Engineer
description: Specialized in REST APIs, services, and business logic
model: claude-sonnet-4
tools:
  - mcp__task-orchestrator__*
---
```

**Markdown Body** (instructions):
```markdown
# Backend Engineer Agent

You are a backend specialist focused on REST APIs, services, and business logic.

## Workflow
1. Read the task: get_task(id='...', includeSections=true)
2. Do your work: Write services, APIs, tests
3. Update sections with results
4. Run tests (REQUIRED)
5. Mark complete: set_status(id='...', status='completed')

## Key Responsibilities
- Implement REST API endpoints
- Write service layer business logic
- Integrate with databases
- Add error handling and validation
- Write unit and integration tests

[... additional agent-specific content ...]
```

## Common Workflows

### Workflow 1: Learn About New Agent

```javascript
// Step 1: Get agent recommendation
const recommendation = await recommend_agent({ taskId: "task-uuid" });
// Response: { agent: "Backend Engineer", ... }

// Step 2: Read agent definition to understand capabilities
const agentDef = await get_agent_definition({
  agentName: "backend-engineer"
});

// Step 3: Review content, then launch agent
// Use Task tool with agent from recommendation
```

### Workflow 2: Customize Agent

```javascript
// Step 1: Read current agent definition
const agentDef = await get_agent_definition({
  agentName: "backend-engineer"
});

// Step 2: Save content to edit
// (Use file system tools to write modified version)

// Step 3: Test customized agent
const recommendation = await recommend_agent({ taskId: "test-task-uuid" });
// Launch and verify behavior
```

### Workflow 3: Verify Agent Installation

```javascript
// Try to read agent definition
const result = await get_agent_definition({ agentName: "backend-engineer" });

if (result.success) {
  console.log("Agent installed successfully");
} else {
  // Run install Task Orchestrator plugin()
  await install Task Orchestrator plugin();
}
```

### Workflow 4: Document Agent System

```javascript
// Retrieve all agent definitions for documentation
const agents = [
  "backend-engineer",
  "database-engineer",
  "frontend-developer",
  "test-engineer",
  "technical-writer"
];

for (const agentName of agents) {
  const def = await get_agent_definition({ agentName });
  // Extract metadata from YAML frontmatter
  // Document agent capabilities
}
```

## Agent Definition Metadata

### YAML Frontmatter Fields

**name** (string):
- Agent's display name in proper case
- Example: "Backend Engineer", "Database Engineer"

**description** (string):
- Brief description of agent's specialization
- Used in agent selection UI
- Keep under 100 characters

**model** (string):
- Claude model to use for this agent
- Options: `claude-sonnet-4`, `claude-opus-4`
- Sonnet: Fast, cost-effective for implementation
- Opus: High-reasoning for planning and architecture

**tools** (array):
- Tool patterns the agent can access
- `mcp__task-orchestrator__*`: All Task Orchestrator tools
- Can restrict to specific tools: `mcp__task-orchestrator__get_task`

### Example Frontmatter

```yaml
---
name: Backend Engineer
description: Specialized in REST APIs, services, and business logic
model: claude-sonnet-4
tools:
  - mcp__task-orchestrator__*
  - bash
  - read
  - write
  - edit
---
```

## Error Handling

### Agent Not Found

```json
{
  "success": false,
  "message": "Agent definition file not found: backend-engineer.md",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "Run install Task Orchestrator plugin tool to initialize agent configurations, or check that the agent name is correct."
  }
}
```

**Common Causes**:
- Agent not installed (need to run `install Task Orchestrator plugin`)
- Typo in agent name
- Agent file deleted
- Wrong directory

**Solutions**:
1. Run `install Task Orchestrator plugin()` to install agents
2. Verify agent name spelling
3. Check `.claude/agents/` directory exists
4. List available agents using MCP resource

### Empty Agent Name

```json
{
  "success": false,
  "message": "Invalid input",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Agent name cannot be empty"
  }
}
```

**Solution**: Provide valid agent name

### File System Error

```json
{
  "success": false,
  "message": "Failed to retrieve agent definition",
  "error": {
    "code": "INTERNAL_ERROR",
    "details": "Permission denied reading file .claude/agents/backend-engineer.md"
  }
}
```

**Common Causes**:
- File permission issues
- Directory permissions
- File system errors

**Solutions**:
- Check file permissions
- Verify directory access
- Check application logs

## Integration with Other Tools

### Complete Agent Launch Workflow

```javascript
// 1. Create task with appropriate tags
const task = await create_task({
  title: "Implement user authentication API",
  tags: "backend,api,authentication",
  complexity: 6,
  templateIds: ["technical-approach-uuid"]
});

// 2. Get agent recommendation
const rec = await recommend_agent({ taskId: task.data.id });
// Returns: { recommended: true, agent: "Backend Engineer", ... }

// 3. (Optional) Review agent definition
const agentDef = await get_agent_definition({
  agentName: rec.data.agent.toLowerCase().replace(" ", "-")
});
// Review content to understand agent's workflow

// 4. Launch agent using Task tool
// Use parameters from rec.data.nextAction
```

### Customization Workflow

```javascript
// 1. Read current definition
const current = await get_agent_definition({ agentName: "backend-engineer" });

// 2. Edit content (using file system tools)
// Add project-specific guidelines, tools, workflows

// 3. Verify changes
const updated = await get_agent_definition({ agentName: "backend-engineer" });

// 4. Test with real task
const rec = await recommend_agent({ taskId: "test-task-uuid" });
// Launch and verify behavior
```

## Best Practices

1. **Review Before First Use**: Read agent definition before first launch
2. **Understand Workflows**: Review agent's workflow section for expectations
3. **Check Tool Access**: Verify agent has access to needed tools
4. **Document Customizations**: Comment custom changes in agent files
5. **Version Control**: Commit customized agent definitions to git
6. **Test After Customization**: Verify agent behavior after changes
7. **Use for Debugging**: Read agent instructions when debugging issues

## Customization Examples

### Adding Project-Specific Tools

Edit `.claude/agents/backend-engineer.md`:

```markdown
## Available Tools

Standard Task Orchestrator tools plus:

- **Project Linting**: run_eslint() - Check code quality
- **Code Formatting**: run_prettier() - Auto-format code
- **Custom Deployment**: deploy_to_staging() - Deploy to staging env
```

### Adding Domain Guidelines

```markdown
## Project Standards

- **Repository Pattern**: Use for all data access
- **OpenAPI Docs**: All APIs must include OpenAPI documentation
- **OAuth 2.0**: Use for all authentication endpoints
- **Error Format**: Follow RFC 7807 Problem Details standard
```

### Customizing Workflows

```markdown
## Custom Workflow

1. Read task: get_task(id='...', includeSections=true)
2. **Run existing tests FIRST**: ./gradlew test
3. Implement changes following project patterns
4. Update tests for new functionality
5. **Run full test suite**: ./gradlew test
6. **Run integration tests**: ./gradlew integrationTest
7. Update task sections with implementation notes
8. Create Summary section (300-500 tokens)
9. Mark complete: set_status(id='...', status='completed')
```

### Restricting Tool Access

Edit YAML frontmatter to restrict tools:

```yaml
---
name: Documentation Writer
description: Specialized in writing documentation
model: claude-sonnet-4
tools:
  - mcp__task-orchestrator__get_task
  - mcp__task-orchestrator__get_sections
  - mcp__task-orchestrator__add_section
  - mcp__task-orchestrator__update_section_text
  - read
  - write
  # No bash, edit, or other tools
---
```

## Related Tools

- **install Task Orchestrator plugin**: Install agent definition files (must run first)
- **recommend_agent**: Get agent recommendation for a task
- **create_task**: Create tasks that trigger agent recommendations
- **get_sections**: Read task sections that agents will work with

## See Also

- Agent Orchestration Guide: `task-orchestrator://docs/agent-orchestration`
- Agent List Resource: `task-orchestrator://agents/list`
- Agent Customization: `task-orchestrator://guidelines/agent-customization`
- YAML Frontmatter Spec: `task-orchestrator://reference/agent-frontmatter`

## Frequently Asked Questions

### Q: Do I need to read the agent definition before launching?

**A**: No, but it's helpful for first-time use. The `recommend_agent` tool provides everything needed to launch.

### Q: Can I modify agent definitions?

**A**: Yes! Edit files in `.claude/agents/` to customize agent behavior for your project.

### Q: What if agent definition is very long?

**A**: Agent definitions are typically 500-2000 tokens. Only read when needed (review, customization, debugging).

### Q: How do I list all available agents?

**A**: Use the MCP resource `task-orchestrator://agents/list` or check `.claude/agents/` directory.

### Q: Can I create my own custom agents?

**A**: Yes! Create a new `.md` file in `.claude/agents/` with YAML frontmatter, then add mapping to `.taskorchestrator/agent-mapping.yaml`.

### Q: What's the difference between agent name and file name?

**A**: Agent name is display name ("Backend Engineer"), file name is kebab-case ("backend-engineer.md"). This tool accepts both.

### Q: Do I need the .md extension?

**A**: No, the tool automatically adds it if omitted. Both "backend-engineer" and "backend-engineer.md" work.

### Q: What if I customize an agent and then run install Task Orchestrator plugin again?

**A**: Your customizations are safe. The setup tool skips existing files.
