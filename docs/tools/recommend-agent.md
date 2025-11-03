# recommend_agent Tool - Detailed Documentation

## Overview

Analyzes a task's metadata (tags, status, complexity) to recommend the most appropriate specialized AI agent for working on the task. Uses agent-mapping configuration to match task characteristics with agent capabilities.

**Resource**: `task-orchestrator://docs/tools/recommend-agent`

## Key Concepts

### Agent Recommendation System

The tool uses **tag-based matching** to recommend agents:
1. Retrieves task metadata (tags, title, summary, complexity)
2. Loads agent mapping configuration (`.taskorchestrator/agent-mapping.yaml`)
3. Matches task tags against agent tag mappings
4. Returns recommended agent with execution instructions

### When Recommendation is Found

**Response includes**:
- Recommended agent name (e.g., "Backend Engineer")
- Reason for recommendation (which tags matched)
- Matched tags that triggered the recommendation
- Section tags for efficient context retrieval
- Next action instructions for launching the agent

### When No Recommendation

**Response indicates**:
- No agent recommendation available
- Reason (task tags don't match any agent mapping)
- All task tags for reference
- Instruction to work on task directly

### Agent Mapping Configuration

Located at `.taskorchestrator/agent-mapping.yaml`:

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
    - data-model
    - implementation
```

**How matching works**:
- Task tag: `backend` → Matches `backend-engineer`
- Task tag: `database` → Matches `database-engineer`
- Task tags: `backend`, `database` → First match wins (backend-engineer)

## Parameter Reference

### Required Parameters

- **taskId** (UUID): UUID of the task to get agent recommendation for
  - Must be valid UUID format
  - Task must exist in database
  - Task metadata is analyzed for recommendation

### Example Parameters

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000"
}
```

## Usage Patterns

### Pattern 1: Get Recommendation and Launch Agent

Standard workflow for task delegation.

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response with Recommendation**:
```json
{
  "success": true,
  "message": "Agent recommendation found. Use the Task tool to launch the Backend Engineer agent.",
  "data": {
    "recommended": true,
    "agent": "Backend Engineer",
    "reason": "Task tags matched: backend, api",
    "matchedTags": ["backend", "api"],
    "sectionTags": ["technical-approach", "implementation", "api-design"],
    "definitionPath": ".claude/agents/backend-engineer.md",
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "taskTitle": "Implement user authentication API",
    "nextAction": {
      "instruction": "Launch the Backend Engineer agent using the Task tool",
      "tool": "Task",
      "parameters": {
        "subagent_type": "Backend Engineer",
        "description": "Implement user authentication API",
        "prompt": "Work on task 550e8400-e29b-41d4-a716-446655440000: Implement user authentication API\n\nSUMMARY: Create REST API endpoints for user login, registration, and token refresh\n\nRead full details: get_task(id='550e8400-e29b-41d4-a716-446655440000', includeSections=true). Focus on sections tagged: technical-approach, implementation, api-design. Follow your standard workflow."
      }
    }
  }
}
```

**Next Step**: Use the Task tool with parameters from `nextAction`

**When to use**:
- Task has specialist tags (backend, frontend, database, testing)
- Want to delegate to specialized agent
- Task is part of larger feature workflow
- Using Claude Code agent orchestration

### Pattern 2: No Recommendation - Direct Execution

Task tags don't match any agent mappings.

```json
{
  "taskId": "661f9511-f30c-52e5-b827-557766551111"
}
```

**Response without Recommendation**:
```json
{
  "success": true,
  "message": "No agent recommendation available. Execute this task yourself.",
  "data": {
    "recommended": false,
    "reason": "No agent recommendation available for this task's tags",
    "taskId": "661f9511-f30c-52e5-b827-557766551111",
    "taskTitle": "Update project README",
    "taskTags": ["documentation", "readme", "project-docs"],
    "nextAction": {
      "instruction": "No specialized agent recommended. You should work on this task directly.",
      "approach": "Execute the task yourself using available tools and your general capabilities"
    }
  }
}
```

**Next Step**: Work on task directly (no agent launch)

**When to use**:
- Task tags are generic or don't match specialists
- Simple tasks that don't need specialization
- Tasks with custom tags not in agent mappings

### Pattern 3: Batch Recommendations for Feature Planning

Get recommendations for multiple tasks to plan feature work.

```javascript
// Get all tasks in a feature
const feature = await get_feature({
  id: "feature-uuid",
  includeTasks: true
});

// Get recommendations for each task
for (const task of feature.data.tasks) {
  const rec = await recommend_agent({ taskId: task.id });

  if (rec.data.recommended) {
    console.log(`Task ${task.title}: ${rec.data.agent}`);
  } else {
    console.log(`Task ${task.title}: Direct execution`);
  }
}

// Result:
// Task "Create database schema": Database Engineer
// Task "Implement API endpoints": Backend Engineer
// Task "Add unit tests": Test Engineer
// Task "Update documentation": Direct execution
```

**When to use**:
- Planning feature implementation sequence
- Understanding specialist coordination needs
- Identifying tasks for parallel execution
- Feature Manager workflow

### Pattern 4: Verify Task Tagging

Check if task tags trigger correct agent recommendation.

```javascript
// Create task with specific tags
const task = await create_task({
  title: "Optimize database queries",
  tags: "database,performance,sql",
  complexity: 6
});

// Verify recommendation
const rec = await recommend_agent({ taskId: task.data.id });

// Should recommend: "Database Engineer"
// If not, adjust tags or agent mapping
```

**When to use**:
- Testing agent mapping configuration
- Validating task tagging strategy
- Debugging recommendation issues
- Creating custom agent mappings

### Pattern 5: Task Manager Workflow

Task Manager agent uses this tool to route tasks.

```javascript
// Task Manager reads task
const task = await get_task({
  id: taskId,
  includeSections: true,
  includeDependencies: true
});

// Get agent recommendation
const rec = await recommend_agent({ taskId });

// Report to orchestrator
if (rec.data.recommended) {
  return `Recommend launching ${rec.data.agent} agent. ${rec.data.reason}.`;
} else {
  return `No specialist recommendation. Orchestrator should handle this task directly.`;
}
```

**When to use**:
- Task Manager START mode
- Multi-task feature coordination
- Agent orchestration workflow

## Response Structure

### Success Response (Recommendation Found)

```json
{
  "success": true,
  "message": "Agent recommendation found. Use the Task tool to launch the [Agent Name] agent.",
  "data": {
    "recommended": true,
    "agent": "Backend Engineer",
    "reason": "Task tags matched: backend, api",
    "matchedTags": ["backend", "api", "rest"],
    "sectionTags": ["technical-approach", "implementation", "api-design"],
    "definitionPath": ".claude/agents/backend-engineer.md",
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "taskTitle": "Implement user authentication API",
    "nextAction": {
      "instruction": "Launch the Backend Engineer agent using the Task tool",
      "tool": "Task",
      "parameters": {
        "subagent_type": "Backend Engineer",
        "description": "Implement user authentication API",
        "prompt": "Work on task [...full prompt with task context...]"
      }
    }
  }
}
```

**Key Fields**:
- `recommended`: `true` when agent found
- `agent`: Agent name in Proper Case ("Backend Engineer")
- `reason`: Explanation of why this agent was recommended
- `matchedTags`: Task tags that matched agent mapping
- `sectionTags`: Tags to filter sections (token efficiency)
- `definitionPath`: Path to agent definition file
- `taskId`: UUID of the task
- `taskTitle`: Task title for context
- `nextAction`: Complete instructions for launching agent
  - `instruction`: What to do next
  - `tool`: Which tool to use ("Task")
  - `parameters`: Tool parameters ready to use

### Success Response (No Recommendation)

```json
{
  "success": true,
  "message": "No agent recommendation available. Execute this task yourself.",
  "data": {
    "recommended": false,
    "reason": "No agent recommendation available for this task's tags",
    "taskId": "661f9511-f30c-52e5-b827-557766551111",
    "taskTitle": "Update project README",
    "taskTags": ["documentation", "readme", "project-docs"],
    "nextAction": {
      "instruction": "No specialized agent recommended. You should work on this task directly.",
      "approach": "Execute the task yourself using available tools and your general capabilities"
    }
  }
}
```

**Key Fields**:
- `recommended`: `false` when no agent found
- `reason`: Explanation of why no recommendation
- `taskId`: UUID of the task
- `taskTitle`: Task title for context
- `taskTags`: All tags on the task (for debugging)
- `nextAction`: Instructions for direct execution

### Prompt Structure (nextAction.parameters.prompt)

The generated prompt includes:

**Task Identification**:
```
Work on task 550e8400-e29b-41d4-a716-446655440000: Implement user authentication API
```

**Summary**:
```
SUMMARY: Create REST API endpoints for user login, registration, and token refresh
```

**Context (if available)**:
```
PROJECT: MCP Task Orchestrator / FEATURE: User Authentication System
```

**Instructions**:
```
Read full details: get_task(id='550e8400-e29b-41d4-a716-446655440000', includeSections=true).
Focus on sections tagged: technical-approach, implementation, api-design.
Follow your standard workflow.
```

## Agent Matching Logic

### Tag Matching Algorithm

1. **Load Task**: Retrieve task metadata
2. **Load Mappings**: Read `.taskorchestrator/agent-mapping.yaml`
3. **Match Tags**: Check if any task tag matches any agent mapping tag
4. **Select First Match**: First agent with matching tag wins
5. **Return Recommendation**: Agent name + matched tags + section tags

### Example Matches

**Task tags**: `["backend", "api", "authentication"]`
**Result**: Backend Engineer (matches "backend" and "api")

**Task tags**: `["database", "schema", "migration"]`
**Result**: Database Engineer (matches all three tags)

**Task tags**: `["frontend", "react", "ui"]`
**Result**: Frontend Developer (matches "frontend")

**Task tags**: `["documentation", "readme"]`
**Result**: No recommendation (no agent mapping for these tags)

### Multiple Matches

When task tags match multiple agents:
- **First match wins** (based on agent mapping file order)
- Example: Task with `["backend", "database"]` tags
  - If `backend-engineer` is listed first → Backend Engineer
  - If `database-engineer` is listed first → Database Engineer

**Recommendation**: Order agent mappings by priority in YAML file

## Section Tags for Context Efficiency

### What Are Section Tags?

Section tags help agents **focus on relevant sections only**:
- Reduces context size by 50-90%
- Agents skip irrelevant sections
- Improves response speed and quality

### How They Work

**Agent mapping includes section tags**:
```yaml
backend-engineer:
  tags:
    - backend
    - api
  sectionTags:
    - technical-approach
    - implementation
    - api-design
```

**Agent uses section tags to filter**:
```javascript
// Agent receives sectionTags: ["technical-approach", "implementation", "api-design"]

// Load only relevant sections
const sections = await get_sections({
  entityType: "TASK",
  entityId: taskId,
  tags: "technical-approach,implementation,api-design"  // Filter by tags
});

// Result: Only sections with these tags loaded (85-99% token savings)
```

### Common Section Tags by Agent

| Agent | Section Tags |
|-------|-------------|
| Backend Engineer | technical-approach, implementation, api-design |
| Database Engineer | technical-approach, data-model, implementation |
| Frontend Developer | technical-approach, implementation, ui-design |
| Test Engineer | technical-approach, testing-strategy, implementation |
| Technical Writer | requirements, api-design, user-guide |

## Integration with Other Tools

### Complete Task Workflow

```javascript
// 1. Create task with tags
const task = await create_task({
  title: "Implement user authentication API",
  summary: "Create REST API endpoints for login, registration, token refresh",
  tags: "backend,api,authentication",
  complexity: 6,
  templateIds: ["technical-approach-uuid", "implementation-workflow-uuid"]
});

// 2. Get agent recommendation
const rec = await recommend_agent({ taskId: task.data.id });

// 3. Review recommendation
console.log(`Recommended: ${rec.data.agent}`);
console.log(`Reason: ${rec.data.reason}`);
console.log(`Matched tags: ${rec.data.matchedTags.join(", ")}`);

// 4. Launch agent using Task tool
// Use parameters from rec.data.nextAction
```

### Feature Manager Workflow

```javascript
// Feature Manager recommends next task
const nextTask = /* ... select task from feature ... */;

// Get agent recommendation
const rec = await recommend_agent({ taskId: nextTask.id });

// Report to orchestrator
return `Next task: ${nextTask.title}. ${rec.data.recommended ? `Recommend launching ${rec.data.agent} agent.` : 'Handle directly.'}`;
```

### Task Manager Workflow

```javascript
// Task Manager START mode

// 1. Read task details
const task = await get_task({
  id: taskId,
  includeSections: true,
  includeDependencies: true
});

// 2. Get agent recommendation
const rec = await recommend_agent({ taskId });

// 3. Load dependency summaries if needed
const depSummaries = /* ... load completed dependency summaries ... */;

// 4. Brief orchestrator
if (rec.data.recommended) {
  return `Task ready for ${rec.data.agent}. ${depSummaries ? 'Dependency context loaded.' : 'No dependencies.'}`;
} else {
  return `No specialist needed. Orchestrator should execute directly.`;
}
```

## Error Handling

### Task Not Found

```json
{
  "success": false,
  "message": "Task not found with ID: 550e8400-e29b-41d4-a716-446655440000",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "The specified task does not exist in the database"
  }
}
```

**Common Causes**:
- Task ID doesn't exist
- Task was deleted
- Wrong UUID provided
- Typo in task ID

**Solutions**:
- Verify task ID using `search_tasks` or `get_overview`
- Check task exists before calling recommend_agent
- Use correct UUID format

### Invalid Task ID Format

```json
{
  "success": false,
  "message": "Invalid task ID format",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Task ID must be a valid UUID"
  }
}
```

**Common Causes**:
- Not a valid UUID format
- Missing hyphens
- Wrong length

**Solutions**:
- Use valid UUID format: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
- Get task ID from task creation response or search results

### Agent Mapping File Missing

```json
{
  "success": false,
  "message": "Failed to get agent recommendation",
  "error": {
    "code": "INTERNAL_ERROR",
    "details": "Agent mapping configuration not found"
  }
}
```

**Common Causes**:
- Agent system not set up
- Mapping file deleted
- Wrong directory

**Solutions**:
- Run `install Task Orchestrator plugin()` to create agent mapping file
- Verify `.taskorchestrator/agent-mapping.yaml` exists
- Check file permissions

## Customizing Agent Mappings

### Adding Custom Tags

Edit `.taskorchestrator/agent-mapping.yaml`:

```yaml
backend-engineer:
  tags:
    - backend
    - api
    - rest
    - service
    - microservice     # Custom tag
    - graphql          # Custom tag
  sectionTags:
    - technical-approach
    - implementation
    - api-design
```

**Then tag tasks**:
```javascript
const task = await create_task({
  title: "Implement GraphQL resolver",
  tags: "backend,graphql",  // Matches backend-engineer
  complexity: 5
});
```

### Creating Custom Agent Mappings

Add new agent to mapping file:

```yaml
devops-engineer:
  tags:
    - devops
    - deployment
    - infrastructure
    - kubernetes
    - docker
  sectionTags:
    - deployment-strategy
    - infrastructure-notes
    - technical-approach
```

**Create agent definition**: `.claude/agents/devops-engineer.md`

**Test recommendation**:
```javascript
const task = await create_task({
  title: "Setup Kubernetes deployment",
  tags: "devops,kubernetes,deployment",
  complexity: 7
});

const rec = await recommend_agent({ taskId: task.data.id });
// Should recommend: "DevOps Engineer"
```

### Adjusting Agent Priority

**Agent priority** is determined by **order in YAML file**.

To prioritize Database Engineer over Backend Engineer:

```yaml
# Put database-engineer FIRST
database-engineer:
  tags:
    - database
    - schema

backend-engineer:
  tags:
    - backend
    - api
```

**Result**: Task with `["backend", "database"]` tags → Database Engineer

### Tag Hierarchies

Create **specific before general** tag mappings:

```yaml
# Specific: Full-stack tasks
fullstack-engineer:
  tags:
    - fullstack
  sectionTags:
    - technical-approach
    - implementation

# General: Backend-only tasks
backend-engineer:
  tags:
    - backend
    - api
  sectionTags:
    - technical-approach
    - implementation
```

## Best Practices

1. **Tag Tasks Consistently**: Follow project tagging conventions for reliable recommendations
2. **Review Recommendations**: Check `matchedTags` to understand why agent was recommended
3. **Use Section Tags**: Leverage section tags for token-efficient agent execution
4. **Customize Mappings**: Adjust agent mappings to match your project's needs
5. **Test Recommendations**: Verify tags trigger correct agents before feature work
6. **Order Matters**: Put specific agents before general ones in mapping file
7. **Document Custom Tags**: Add comments to agent-mapping.yaml for team clarity
8. **Version Control**: Commit agent-mapping.yaml to share with team

## Common Workflows

### Workflow 1: Delegate Task to Specialist

```javascript
// Orchestrator workflow

// 1. Get recommendation
const rec = await recommend_agent({ taskId: "task-uuid" });

// 2. Review recommendation
if (rec.data.recommended) {
  console.log(`Launching ${rec.data.agent}...`);
  console.log(`Matched tags: ${rec.data.matchedTags.join(", ")}`);

  // 3. Launch agent using Task tool
  // Use parameters from rec.data.nextAction
} else {
  console.log("No specialist needed. Executing task directly.");
  // Work on task yourself
}
```

### Workflow 2: Feature Manager Task Selection

```javascript
// Feature Manager START mode

// 1. Get feature tasks
const feature = await get_feature({ id: featureId, includeTasks: true });

// 2. Filter ready tasks (no blockers)
const readyTasks = feature.data.tasks.filter(t => t.status === "pending");

// 3. Get recommendations for each
const recommendations = [];
for (const task of readyTasks) {
  const rec = await recommend_agent({ taskId: task.id });
  recommendations.push({
    task: task.title,
    agent: rec.data.recommended ? rec.data.agent : "Direct",
    priority: task.priority
  });
}

// 4. Recommend highest priority task to orchestrator
const nextTask = recommendations[0];
return `Next task: ${nextTask.task}. Recommend ${nextTask.agent}.`;
```

### Workflow 3: Test Agent Configuration

```javascript
// Test all specialist agents

const testTasks = [
  { title: "API endpoint", tags: "backend,api" },
  { title: "Database schema", tags: "database,schema" },
  { title: "React component", tags: "frontend,react" },
  { title: "Unit tests", tags: "testing,unit-tests" },
  { title: "API docs", tags: "documentation,api" }
];

for (const testTask of testTasks) {
  const task = await create_task(testTask);
  const rec = await recommend_agent({ taskId: task.data.id });

  console.log(`Task: ${testTask.title}`);
  console.log(`Tags: ${testTask.tags}`);
  console.log(`Recommended: ${rec.data.recommended ? rec.data.agent : "None"}`);
  console.log("---");
}

// Verify all tasks got appropriate recommendations
```

## Related Tools

- **install Task Orchestrator plugin**: Install agent definitions and mapping configuration (required first)
- **get_agent_definition**: Read agent definition file for review
- **create_task**: Create tasks with tags that trigger recommendations
- **get_task**: Retrieve task details that agents will work with
- **get_sections**: Load sections using section tags for efficiency

## See Also

- Agent Orchestration Guide: `task-orchestrator://docs/agent-orchestration`
- Agent Mapping Configuration: `task-orchestrator://docs/agent-orchestration#agent-mapping-configuration`
- Tagging Strategy: `task-orchestrator://guidelines/tagging-strategy`
- Section Tag Filtering: `task-orchestrator://docs/agent-orchestration#section-tag-filtering`

## Frequently Asked Questions

### Q: What if I don't want to use agents?

**A**: No problem! Just ignore the recommendation and work on tasks directly. Agent orchestration is optional.

### Q: Can I have multiple agents for the same tags?

**A**: The first matching agent wins. Order agents by priority in the YAML file.

### Q: What if task has no tags?

**A**: No recommendation will be returned. The tool requires tags to match agents.

### Q: Can I change agent mappings at runtime?

**A**: Yes, edit `.taskorchestrator/agent-mapping.yaml`. Changes take effect on next `recommend_agent` call.

### Q: What if I disagree with the recommendation?

**A**: You can launch a different agent or work on the task yourself. Recommendations are suggestions, not requirements.

### Q: How do I add a completely new agent type?

**A**: 1) Create agent definition in `.claude/agents/`, 2) Add mapping to `agent-mapping.yaml`, 3) Test with tagged task.

### Q: Can I use this without Claude Code?

**A**: The recommendation logic works, but agent launching requires Claude Code's Task tool. For other MCP clients, use recommendations as guidance for manual delegation.

### Q: What's the token cost of this tool?

**A**: Low (~200-400 tokens). Only reads task metadata + agent mapping config, not full task content.
