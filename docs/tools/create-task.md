# create_task Tool - Detailed Documentation

## Overview

Creates a new task with metadata and optional template application. Tasks are the primary work units in Task Orchestrator.

**Resource**: `task-orchestrator://docs/tools/create-task`

## Key Concepts

### Task Types
- **Independent Task**: Standalone work item not tied to a feature
- **Feature Task**: Task associated with a specific feature via `featureId`
- **Project Task**: Task linked directly to a project via `projectId`

### Status Lifecycle
```
pending → in-progress → completed
         ↓
         cancelled / deferred
```

## Parameter Reference

### Required Parameters
- **title** (string): Short, actionable task title
  - Good: "Implement OAuth login endpoint"
  - Bad: "Login stuff"

### Optional Parameters
- **summary** (string, max 500 chars): Brief summary of what needs to be done
- **description** (string): Detailed description (user-provided, no length limit)
- **status** (enum): pending | in-progress | completed | cancelled | deferred (default: pending)
- **priority** (enum): high | medium | low (default: medium)
- **complexity** (integer, 1-10): Task complexity rating (default: 5)
- **featureId** (UUID): Parent feature ID
- **projectId** (UUID): Parent project ID
- **templateIds** (array of UUIDs): Templates to apply (use list_templates to discover)
- **tags** (string): Comma-separated tags

## Template Discovery Pattern

**CRITICAL**: Always discover templates dynamically, never hardcode template IDs.

```javascript
// Step 1: Discover available templates
const templates = await list_templates({
  targetEntityType: "TASK",
  isEnabled: true
});

// Step 2: Select appropriate templates based on task type
const selectedTemplates = templates.data.templates
  .filter(t => t.tags.includes("implementation") || t.tags.includes("workflow"))
  .map(t => t.id);

// Step 3: Create task with discovered templates
const task = await create_task({
  title: "Implement user authentication",
  summary: "Add JWT-based authentication with login and token refresh",
  complexity: 6,
  priority: "high",
  templateIds: selectedTemplates,
  tags: "authentication,backend,security"
});
```

## Common Usage Patterns

### Pattern 1: Simple Task Creation
For straightforward tasks with minimal structure.

```json
{
  "title": "Fix typo in user profile page",
  "summary": "Correct spelling error in bio field label",
  "complexity": 1,
  "priority": "low",
  "tags": "bug,frontend,ui"
}
```

**When to use**: Simple, well-defined tasks that don't need templates.

### Pattern 2: Feature Implementation Task
For tasks that implement part of a larger feature.

```json
{
  "title": "Implement OAuth Google provider integration",
  "summary": "Add Google OAuth 2.0 authentication flow with token management",
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "complexity": 6,
  "priority": "high",
  "templateIds": ["impl-workflow-uuid", "git-workflow-uuid"],
  "tags": "oauth,authentication,google,backend"
}
```

**When to use**: Task is part of a feature, needs structured workflow guidance.

### Pattern 3: Bug Investigation Task
For systematic bug resolution.

```json
{
  "title": "Login page shows blank screen after credential submission",
  "summary": "User unable to log in, blank screen appears instead of dashboard",
  "complexity": 5,
  "priority": "high",
  "templateIds": ["bug-investigation-uuid"],
  "tags": "task-type-bug,severity-high,authentication,frontend"
}
```

**When to use**: Bug requires investigation and systematic resolution.

### Pattern 4: Research/Spike Task
For exploration and research without immediate implementation.

```json
{
  "title": "Research authentication libraries for Node.js",
  "summary": "Evaluate Passport.js, Auth0, and custom JWT solutions",
  "complexity": 4,
  "priority": "medium",
  "templateIds": ["technical-approach-uuid"],
  "tags": "research,spike,authentication,backend"
}
```

**When to use**: Need to investigate options before implementation.

### Pattern 5: Complex Task with Full Documentation
For high-complexity tasks requiring comprehensive documentation.

```json
{
  "title": "Design and implement distributed caching layer",
  "summary": "Add Redis-based caching with cluster support for API response caching",
  "complexity": 9,
  "priority": "high",
  "templateIds": [
    "architecture-overview-uuid",
    "technical-approach-uuid",
    "implementation-workflow-uuid",
    "testing-strategy-uuid"
  ],
  "tags": "architecture,backend,performance,caching,redis"
}
```

**When to use**: Complex architectural changes requiring full documentation.

## Template Combination Strategies

### Standard Development Task
```
Templates: Task Implementation + Git Branching
Complexity: 4-6
Use: Regular development work with version control
```

### Bug Fix
```
Templates: Bug Investigation + Git Branching + Definition of Done
Complexity: 3-7
Use: Systematic bug resolution with quality gates
```

### Architecture/Design Task
```
Templates: Architecture Overview + Technical Approach + Testing Strategy
Complexity: 7-10
Use: Major architectural decisions or complex systems design
```

### Quick Fix
```
Templates: None or minimal
Complexity: 1-3
Use: Simple fixes, typos, minor updates
```

## Integration with Dependencies

When creating tasks that depend on other tasks:

```javascript
// Create the first task
const schemaTask = await create_task({
  title: "Create database schema for user authentication",
  complexity: 4,
  tags: "database,schema,authentication"
});

// Create dependent task
const apiTask = await create_task({
  title: "Implement authentication API endpoints",
  complexity: 6,
  tags: "api,backend,authentication"
});

// Establish dependency
await create_dependency({
  fromTaskId: schemaTask.data.id,
  toTaskId: apiTask.data.id,
  type: "BLOCKS"
});
```

## Tag Conventions

Follow consistent tagging for better organization:

**Task Type Tags**:
- `task-type-feature`: Feature implementation
- `task-type-bug`: Bug fix
- `task-type-refactor`: Code refactoring
- `task-type-docs`: Documentation
- `task-type-test`: Testing work

**Domain Tags**:
- `frontend`, `backend`, `database`, `devops`, `infrastructure`

**Technology Tags**:
- `react`, `kotlin`, `postgresql`, `docker`, `kubernetes`

**Component Tags**:
- `authentication`, `api`, `ui`, `payments`, `notifications`

**Priority Indicators**:
- `severity-critical`, `severity-high`, `severity-medium`, `severity-low` (for bugs)

## Response Structure

Successful response includes:
```json
{
  "success": true,
  "message": "Task created successfully",
  "data": {
    "id": "uuid",
    "title": "Task title",
    "summary": "Task summary",
    "status": "pending",
    "priority": "medium",
    "complexity": 5,
    "tags": ["tag1", "tag2"],
    "createdAt": "ISO-8601 timestamp",
    "modifiedAt": "ISO-8601 timestamp",
    "appliedTemplates": [
      {
        "templateId": "uuid",
        "sectionsCreated": 3
      }
    ]
  }
}
```

## Common Mistakes to Avoid

### ❌ Mistake 1: Hardcoding Template IDs
```json
{
  "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]
}
```
**Problem**: Template UUIDs vary between installations.

### ✅ Solution: Dynamic Template Discovery
Always use `list_templates` first.

### ❌ Mistake 2: Vague Task Titles
```json
{
  "title": "Work on authentication"
}
```
**Problem**: Not actionable, unclear scope.

### ✅ Solution: Specific, Actionable Titles
```json
{
  "title": "Implement JWT token generation endpoint"
}
```

### ❌ Mistake 3: Over-templating Simple Tasks
```json
{
  "title": "Fix typo in README",
  "templateIds": [/* 5 different templates */]
}
```
**Problem**: Creates unnecessary overhead.

### ✅ Solution: Template Count Matches Complexity
- Complexity 1-3: 0-1 templates
- Complexity 4-6: 1-2 templates
- Complexity 7-10: 2-4 templates

### ❌ Mistake 4: Summary Too Long
```json
{
  "summary": "This task involves implementing a comprehensive authentication system with multiple providers including OAuth for Google, GitHub, and Facebook, plus traditional username/password login, password reset functionality, email verification, two-factor authentication, and session management with Redis..." // 800+ chars
}
```
**Problem**: Summary field limited to 500 characters.

### ✅ Solution: Concise Summary, Details in Sections
```json
{
  "summary": "Implement multi-provider OAuth authentication with Google, GitHub, Facebook plus email/password login",
  "templateIds": ["requirements-uuid"]
  // Details go in Requirements section created by template
}
```

## Best Practices

1. **Always Discover Templates**: Use `list_templates` before creating tasks
2. **Apply Appropriate Templates**: Match template count to task complexity
3. **Use Consistent Tagging**: Follow project tagging conventions
4. **Set Realistic Complexity**: Helps with estimation and prioritization
5. **Link to Features/Projects**: Provides organizational context
6. **Create Dependencies**: Link related tasks for proper sequencing
7. **Write Actionable Titles**: Make it clear what needs to be done
8. **Keep Summary Brief**: Use sections for detailed information

## Related Tools

- **list_templates**: Discover available templates before task creation
- **update_task**: Modify task after creation
- **get_task**: Retrieve task details with sections
- **search_tasks**: Find existing tasks
- **create_dependency**: Link related tasks
- **add_section**: Add detailed content to tasks
- **set_status**: Update task status efficiently

## See Also

- Template Discovery Guide: `task-orchestrator://guidelines/template-strategy`
- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Workflow Integration: `task-orchestrator://guidelines/workflow-integration`
