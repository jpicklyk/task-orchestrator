# create_dependency Tool - Detailed Documentation

## Overview

Create dependencies between tasks to establish work ordering and track blockers. Dependencies ensure tasks are completed in the correct sequence.

**Resource**: `task-orchestrator://docs/tools/create-dependency`

## Key Concepts

### Dependency Types

**BLOCKS**: Task A BLOCKS Task B
- Task A must complete before Task B can start
- Most common dependency type
- Use for: Sequential work, prerequisites

**IS_BLOCKED_BY**: Task A IS_BLOCKED_BY Task B
- Task A cannot start until Task B completes
- Inverse perspective of BLOCKS
- Use for: Same as BLOCKS, different perspective

**RELATES_TO**: Task A RELATES_TO Task B
- Tasks are related but no strict ordering
- Informational relationship only
- Use for: Context, related work, cross-references

### Dependency Direction

Dependencies are **directional**:
```
fromTaskId: Source task
toTaskId: Target task

Example:
fromTaskId: Database Schema (must complete first)
toTaskId: API Implementation (depends on schema)
type: BLOCKS
```

## Parameter Reference

### Required Parameters
- **fromTaskId** (UUID): Source task ID
- **toTaskId** (UUID): Target task ID
- **type** (enum): BLOCKS | IS_BLOCKED_BY | RELATES_TO

## Common Dependency Patterns

### Pattern 1: Sequential Work Chain
Database → Backend → Frontend

```javascript
// Create tasks
const dbTask = await create_task({ title: "Create database schema" });
const apiTask = await create_task({ title: "Implement API endpoints" });
const uiTask = await create_task({ title: "Build UI components" });

// Create dependency chain
await create_dependency({
  fromTaskId: dbTask.data.id,
  toTaskId: apiTask.data.id,
  type: "BLOCKS"
});

await create_dependency({
  fromTaskId: apiTask.data.id,
  toTaskId: uiTask.data.id,
  type: "BLOCKS"
});

// Result: DB → API → UI
```

**When to use**: Sequential implementation requiring specific order

### Pattern 2: Parallel with Merge Point
Multiple tasks feed into integration task.

```javascript
// Create parallel tasks
const auth = await create_task({ title: "Implement authentication" });
const profiles = await create_task({ title: "Implement user profiles" });
const integration = await create_task({ title: "Integrate auth and profiles" });

// Both block integration
await create_dependency({
  fromTaskId: auth.data.id,
  toTaskId: integration.data.id,
  type: "BLOCKS"
});

await create_dependency({
  fromTaskId: profiles.data.id,
  toTaskId: integration.data.id,
  type: "BLOCKS"
});

// Result: Auth ↘
//              → Integration
// Profiles ↗
```

**When to use**: Parallel work that converges

### Pattern 3: Foundation Task
One task blocks many others.

```javascript
const foundation = await create_task({ title: "Set up infrastructure" });
const service1 = await create_task({ title: "Deploy auth service" });
const service2 = await create_task({ title: "Deploy API service" });
const service3 = await create_task({ title: "Deploy UI service" });

// Foundation blocks all services
for (const service of [service1, service2, service3]) {
  await create_dependency({
    fromTaskId: foundation.data.id,
    toTaskId: service.data.id,
    type: "BLOCKS"
  });
}

// Result: Foundation → [Service1, Service2, Service3]
```

**When to use**: Prerequisites for multiple parallel tasks

### Pattern 4: Related Work (No Blocking)
Tasks are related for context but no strict ordering.

```javascript
const featureImpl = await create_task({ title: "Implement feature X" });
const docs = await create_task({ title: "Document feature X" });

await create_dependency({
  fromTaskId: featureImpl.data.id,
  toTaskId: docs.data.id,
  type: "RELATES_TO"
});

// Result: Related but can work in any order
```

**When to use**: Contextual relationships without ordering constraints

## Dependency Validation

The system prevents:
- **Circular dependencies**: Task A blocks B, B blocks A (directly or indirectly)
- **Self-dependencies**: Task blocks itself
- **Duplicate dependencies**: Same relationship already exists

```javascript
// ❌ This will fail (circular)
await create_dependency({
  fromTaskId: taskA.data.id,
  toTaskId: taskB.data.id,
  type: "BLOCKS"
});

await create_dependency({
  fromTaskId: taskB.data.id,
  toTaskId: taskA.data.id,
  type: "BLOCKS"
});
// Error: Circular dependency detected
```

## Common Workflows

### Workflow 1: Feature Breakdown with Dependencies
```javascript
// Create feature tasks in logical order
const tasks = {
  schema: await create_task({ title: "Database schema for feature X" }),
  backend: await create_task({ title: "Backend API for feature X" }),
  frontend: await create_task({ title: "Frontend UI for feature X" }),
  tests: await create_task({ title: "Integration tests for feature X" })
};

// Establish dependencies
await create_dependency({
  fromTaskId: tasks.schema.data.id,
  toTaskId: tasks.backend.data.id,
  type: "BLOCKS"
});

await create_dependency({
  fromTaskId: tasks.backend.data.id,
  toTaskId: tasks.frontend.data.id,
  type: "BLOCKS"
});

await create_dependency({
  fromTaskId: tasks.frontend.data.id,
  toTaskId: tasks.tests.data.id,
  type: "BLOCKS"
});

// Work order: Schema → Backend → Frontend → Tests
```

### Workflow 2: Check Dependencies Before Starting Work
```javascript
// Get task with dependencies
const task = await get_task({
  id: taskId,
  includeDependencies: true
});

// Check if task is blocked
const blockedBy = task.data.dependencies.incoming.filter(dep =>
  dep.fromTask.status !== 'completed' && dep.type === 'BLOCKS'
);

if (blockedBy.length > 0) {
  console.log("Task is blocked by:");
  blockedBy.forEach(dep => {
    console.log(`- ${dep.fromTask.title} (${dep.fromTask.status})`);
  });
} else {
  console.log("Task is ready to start!");
  await set_status({ id: taskId, status: "in-progress" });
}
```

### Workflow 3: Find Next Unblocked Task
```javascript
// Get all pending high-priority tasks
const tasks = await search_tasks({
  status: "pending",
  priority: "high"
});

// Check each for blockers
for (const task of tasks.data.tasks) {
  const detailed = await get_task({
    id: task.id,
    includeDependencies: true
  });

  const isBlocked = detailed.data.dependencies.incoming.some(dep =>
    dep.fromTask.status !== 'completed' && dep.type === 'BLOCKS'
  );

  if (!isBlocked) {
    console.log(`Ready to start: ${task.title}`);
    break;
  }
}
```

## Response Structure

```json
{
  "success": true,
  "message": "Dependency created successfully",
  "data": {
    "dependency": {
      "id": "uuid",
      "fromTaskId": "uuid",
      "toTaskId": "uuid",
      "type": "BLOCKS",
      "createdAt": "ISO-8601"
    }
  }
}
```

## BLOCKS vs IS_BLOCKED_BY

These are **inverse perspectives** of the same relationship:

### BLOCKS (Forward Perspective)
```json
{
  "fromTaskId": "schema-task-id",
  "toTaskId": "api-task-id",
  "type": "BLOCKS"
}
```
**Meaning**: Schema task BLOCKS API task
**Effect**: API task cannot start until schema completes

### IS_BLOCKED_BY (Reverse Perspective)
```json
{
  "fromTaskId": "api-task-id",
  "toTaskId": "schema-task-id",
  "type": "IS_BLOCKED_BY"
}
```
**Meaning**: API task IS_BLOCKED_BY schema task
**Effect**: Same as above (API task cannot start until schema completes)

**Recommendation**: Use BLOCKS consistently for clarity.

## Best Practices

1. **Use BLOCKS for Sequential Work**: Most common and clearest
2. **Avoid Circular Dependencies**: Plan task order before creating dependencies
3. **Check Dependencies Before Starting**: Use `get_task` with `includeDependencies`
4. **Use RELATES_TO for Context**: When tasks are related but order doesn't matter
5. **Create Dependencies During Planning**: Not as an afterthought
6. **Document Dependency Rationale**: Use task summaries to explain why dependencies exist
7. **Limit Dependency Depth**: Avoid overly complex dependency graphs

## Common Mistakes to Avoid

### ❌ Mistake 1: Inverted fromTaskId and toTaskId
```json
{
  "fromTaskId": "api-task",
  "toTaskId": "schema-task",
  "type": "BLOCKS"
}
```
**Problem**: Says "API blocks schema" (backwards)

### ✅ Solution: fromTaskId is the Prerequisite
```json
{
  "fromTaskId": "schema-task",
  "toTaskId": "api-task",
  "type": "BLOCKS"
}
```
**Correct**: Schema blocks API

### ❌ Mistake 2: Creating Circular Dependencies
```javascript
// Task A blocks B
await create_dependency({
  fromTaskId: A,
  toTaskId: B,
  type: "BLOCKS"
});

// Task B blocks A (circular!)
await create_dependency({
  fromTaskId: B,
  toTaskId: A,
  type: "BLOCKS"
});
```

### ✅ Solution: Plan Dependencies Before Creating
Draw dependency graph on paper first.

### ❌ Mistake 3: Over-constraining with Dependencies
Creating too many BLOCKS dependencies for loosely related work.

### ✅ Solution: Use RELATES_TO for Context
Only use BLOCKS for true prerequisites.

## Related Tools

- **get_task_dependencies**: Get detailed dependency information
- **delete_dependency**: Remove dependencies
- **get_blocked_tasks**: Find all blocked tasks
- **search_tasks**: Find tasks by status (check for blockers)

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Task Creation: `task-orchestrator://docs/tools/create-task`
