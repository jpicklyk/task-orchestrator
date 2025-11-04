# manage_dependency Tool - Detailed Documentation

## Overview

The `manage_dependency` tool provides unified write operations for task dependency management. It consolidates dependency creation and deletion into a single, efficient interface with two core operations: `create` and `delete` with comprehensive validation including circular dependency detection and duplicate prevention.

**Key Feature (v2.0+):** The `manage_dependency` tool handles all write operations for task dependencies with extensive validation, cycle detection, and multiple deletion methods through a single interface.

**Resource**: `task-orchestrator://docs/tools/manage-dependency`

## Key Concepts

### Task Dependencies

Dependencies represent relationships between tasks where one task's completion status affects another:

- **BLOCKS**: Source task blocks target task (target cannot start until source completes)
- **IS_BLOCKED_BY**: Source task is blocked by target task (inverse of BLOCKS)
- **RELATES_TO**: General relationship (informational, no blocking semantics)

### Circular Dependency Prevention

The tool prevents circular dependencies where Task A depends on Task B, and Task B depends on Task A (directly or indirectly). This prevents impossible workflows.

### Duplicate Prevention

The tool prevents creating multiple identical dependencies between the same tasks with the same type. You can create BLOCKS and RELATES_TO dependencies between the same tasks (different types), but not duplicate BLOCKS→BLOCKS relationships.

### Three Deletion Methods

- **By Dependency ID**: Delete a specific dependency by its UUID
- **By Task Relationship**: Delete dependencies between two specific tasks (optionally filtered by type)
- **By Task (DeleteAll)**: Delete all dependencies involving a specific task (incoming and outgoing)

---

## Parameter Reference

### Common Parameters (All Operations)

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `operation` | enum | **Yes** | Operation: `create`, `delete` |

### Operation-Specific Parameters

| Parameter | Type | Operations | Description |
|-----------|------|-----------|-------------|
| `fromTaskId` | UUID | create, delete (optional) | Source task ID (required for create) |
| `toTaskId` | UUID | create, delete (optional) | Target task ID (required for create) |
| `type` | enum | create, delete (optional) | Dependency type: BLOCKS, IS_BLOCKED_BY, RELATES_TO (default: BLOCKS for create) |
| `id` | UUID | delete (optional) | Specific dependency ID for deletion |
| `deleteAll` | boolean | delete (optional) | Delete all dependencies for a task (default: false) |

### Dependency Types

| Type | Meaning | Usage |
|------|---------|-------|
| **BLOCKS** | Source task blocks target task from starting/completing | Create strong sequential dependencies (task A must complete before B starts) |
| **IS_BLOCKED_BY** | Source task is blocked by target task (inverse) | Explicit representation of blocking (Task A is blocked by Task B) |
| **RELATES_TO** | General relationship without blocking semantics | Track related work, cross-references, or weak dependencies |

---

## Quick Start

### Basic Create Pattern

```json
{
  "operation": "create",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
  "type": "BLOCKS"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Dependency created successfully",
  "data": {
    "id": "c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f",
    "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
    "type": "BLOCKS",
    "createdAt": "2025-10-24T19:30:00Z"
  }
}
```

### Basic Delete Pattern - By ID

```json
{
  "operation": "delete",
  "id": "c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Dependency deleted successfully",
  "data": {
    "deletedCount": 1,
    "deletedDependencies": [
      {
        "id": "c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f",
        "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
        "type": "BLOCKS"
      }
    ]
  }
}
```

### Basic Delete Pattern - By Relationship

```json
{
  "operation": "delete",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e"
}
```

### Delete All Dependencies for a Task

```json
{
  "operation": "delete",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "deleteAll": true
}
```

**Response**:

```json
{
  "success": true,
  "message": "3 dependencies deleted successfully",
  "data": {
    "deletedCount": 3,
    "deletedDependencies": [...]
  }
}
```

---

## Operation 1: create

**Purpose**: Create a new dependency relationship between two tasks with comprehensive validation

### Required Parameters

- `operation`: "create"
- `fromTaskId`: UUID of source task
- `toTaskId`: UUID of target task

### Optional Parameters

- `type`: Dependency type (default: "BLOCKS")
  - Accepts: "BLOCKS", "IS_BLOCKED_BY", "RELATES_TO"
  - Case-insensitive ("blocks", "BLOCKS", "Blocks" all accepted)

### Validation Rules

The create operation performs extensive validation:

1. **UUID Format**: Both `fromTaskId` and `toTaskId` must be valid UUIDs
2. **Task Existence**: Both tasks must exist in the database
3. **Self-Dependency Prevention**: `fromTaskId` and `toTaskId` must be different
4. **Circular Dependency Detection**: Creating the dependency must not create a cycle
5. **Duplicate Prevention**: No identical dependency can already exist with same type
6. **Dependency Type Validation**: Type must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO

### Example - Create BLOCKS Dependency

**Scenario**: Database schema migration must complete before backend API implementation can start.

```json
{
  "operation": "create",
  "fromTaskId": "e8f9a0b1-c2d3-7e8f-1a2b-3c4d5e6f7a8b",
  "toTaskId": "f9a0b1c2-d3e4-8f9a-2b3c-4d5e6f7a8b9c",
  "type": "BLOCKS"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Dependency created successfully",
  "data": {
    "id": "d4e5f6a7-b8c9-5c6d-8e9f-0a1b2c3d4e5f",
    "fromTaskId": "e8f9a0b1-c2d3-7e8f-1a2b-3c4d5e6f7a8b",
    "toTaskId": "f9a0b1c2-d3e4-8f9a-2b3c-4d5e6f7a8b9c",
    "type": "BLOCKS",
    "createdAt": "2025-10-24T10:00:00Z"
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Create RELATES_TO Dependency

**Scenario**: Frontend authentication component relates to backend authentication service (informational link).

```json
{
  "operation": "create",
  "fromTaskId": "f9a0b1c2-d3e4-8f9a-2b3c-4d5e6f7a8b9c",
  "toTaskId": "a0b1c2d3-e4f5-9a0b-3c4d-5e6f7a8b9c0d",
  "type": "RELATES_TO"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Dependency created successfully",
  "data": {
    "id": "e5f6a7b8-c9d0-6d7e-9f0a-1b2c3d4e5f6a",
    "fromTaskId": "f9a0b1c2-d3e4-8f9a-2b3c-4d5e6f7a8b9c",
    "toTaskId": "a0b1c2d3-e4f5-9a0b-3c4d-5e6f7a8b9c0d",
    "type": "RELATES_TO",
    "createdAt": "2025-10-24T10:15:00Z"
  }
}
```

**Token Cost**: ~150-200 tokens

### Example - Create with Default Type

```json
{
  "operation": "create",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e"
}
```

**Note**: If type is omitted, defaults to "BLOCKS"

### When to Use Create

✅ **Use create when:**

- Establishing task dependencies within features
- Creating blocking relationships for sequential work
- Linking related tasks that should be coordinated
- Setting up prerequisite chains (Task A → Task B → Task C)

### Create Best Practices

1. **Check for cycles first** - Use query_dependencies to verify no existing backward links
2. **Plan dependency chains** - Think through 2-3 steps ahead to avoid circular dependencies
3. **Use appropriate types** - BLOCKS for sequential, RELATES_TO for informational
4. **Verify both tasks exist** - Tool validates but query first for better error messages
5. **Document in task descriptions** - Explain why dependencies exist in task summaries
6. **Limit dependency depth** - Keep chains shallow (3-4 levels max) for clarity

---

## Operation 2: delete

**Purpose**: Remove dependency relationships with three distinct deletion methods

### Deletion Methods

#### Method 1: Delete by Dependency ID

**Required Parameters**:
- `operation`: "delete"
- `id`: UUID of the dependency to delete

**Usage**: When you know the exact dependency ID (from query_dependencies or create response)

```json
{
  "operation": "delete",
  "id": "c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Dependency deleted successfully",
  "data": {
    "deletedCount": 1,
    "deletedDependencies": [
      {
        "id": "c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f",
        "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
        "type": "BLOCKS"
      }
    ]
  }
}
```

**Use When**: You have the specific dependency ID

#### Method 2: Delete by Task Relationship

**Required Parameters**:
- `operation`: "delete"
- `fromTaskId`: Source task UUID
- `toTaskId`: Target task UUID

**Optional Parameters**:
- `type`: Filter by dependency type (only delete specific type)

**Usage**: When you know the tasks involved but not the dependency ID

```json
{
  "operation": "delete",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e"
}
```

**With Type Filter**:

```json
{
  "operation": "delete",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
  "type": "BLOCKS"
}
```

**Use When**: You know which tasks had dependencies but not the specific dependency ID

#### Method 3: Delete All for a Task

**Required Parameters**:
- `operation`: "delete"
- `fromTaskId` OR `toTaskId`: UUID of task to clean up
- `deleteAll`: true

**Usage**: When you want to remove all dependencies involving a specific task

```json
{
  "operation": "delete",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "deleteAll": true
}
```

**Note**: This removes ALL dependencies where the task is the source (outgoing). To remove all where task is target (incoming), use `toTaskId` instead:

```json
{
  "operation": "delete",
  "toTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "deleteAll": true
}
```

**Response** (when 3 dependencies deleted):

```json
{
  "success": true,
  "message": "3 dependencies deleted successfully",
  "data": {
    "deletedCount": 3,
    "deletedDependencies": [
      {
        "id": "c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f",
        "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
        "type": "BLOCKS"
      },
      {
        "id": "d4e5f6a7-b8c9-7d8e-0f1a-2b3c4d5e6f7a",
        "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "toTaskId": "c3d4e5f6-a7b8-6c7d-8e9f-0a1b2c3d4e5f",
        "type": "RELATES_TO"
      },
      {
        "id": "e5f6a7b8-c9d0-8e9f-1a2b-3c4d5e6f7a8b",
        "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "toTaskId": "d4e5f6a7-b8c9-7d8e-0f1a-2b3c4d5e6f7a",
        "type": "BLOCKS"
      }
    ]
  }
}
```

**Use When**: Cleaning up all dependencies for a deleted or reorganized task

### Delete Validation Rules

- **By ID**: Dependency with specified ID must exist
- **By Relationship**: At least one dependency must exist between the two tasks
- **By Task**: Task can have no dependencies (safe - returns 0 deleted)
- **Type Filter**: If specified, must be valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO

### Example - Delete and Cleanup

**Scenario**: Task is being deleted, clean up all its dependencies first.

```json
{
  "operation": "delete",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "deleteAll": true
}
```

Then delete the incoming dependencies:

```json
{
  "operation": "delete",
  "toTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "deleteAll": true
}
```

**Token Cost**: ~150 tokens per delete operation

### Delete Best Practices

1. **Query dependencies first** - Use query_dependencies to see what will be deleted
2. **Be specific with type filter** - Avoid accidental deletion of unrelated dependency types
3. **Delete both directions** - Remember to clean up both fromTaskId and toTaskId if needed
4. **Verify before deleteAll** - deleteAll is powerful; verify count with query_dependencies first
5. **Update task descriptions** - If you remove a dependency, update task summaries that reference it

### When to Use Delete

✅ **Use delete when:**

- Removing task dependencies due to changed requirements
- Cleaning up dependencies before deleting a task
- Reorganizing task workflow
- Removing obsolete blocking relationships
- Fixing circular dependency issues

❌ **Avoid delete when:**

- You're unsure about the impact (query first with query_dependencies)
- Task is still in progress (document why dependency is being removed)
- You haven't notified stakeholders about the change

---

## Advanced Usage

### Circular Dependency Detection

The create operation prevents circular dependencies by analyzing the entire dependency graph:

**Example of prevented circular dependency**:

```
Task A BLOCKS Task B
Task B BLOCKS Task C
Task C BLOCKS Task A  ← This would create a cycle
```

**Create attempt**:

```json
{
  "operation": "create",
  "fromTaskId": "task-c-uuid",
  "toTaskId": "task-a-uuid",
  "type": "BLOCKS"
}
```

**Error Response**:

```json
{
  "success": false,
  "message": "Circular dependency detected",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Creating this dependency would create a circular dependency chain. Tasks cannot depend on each other directly or indirectly."
  }
}
```

**How to Resolve**:
1. Query dependencies with query_dependencies to understand the chain
2. Identify which dependency in the chain should be removed
3. Delete the conflicting dependency
4. Then create the new dependency

### Duplicate Prevention

**Example of prevented duplicate**:

```json
{
  "operation": "create",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
  "type": "BLOCKS"
}
```

If this dependency already exists, you get:

```json
{
  "success": false,
  "message": "Duplicate dependency",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "A dependency of type BLOCKS already exists between these tasks (ID: c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f)"
  }
}
```

**Note**: You CAN create both BLOCKS and RELATES_TO between the same tasks (different types):

```json
{
  "operation": "create",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e",
  "type": "RELATES_TO"
}
```

This succeeds because it's a different type.

### Dependency Chain Patterns

**Pattern 1: Sequential Workflow (Database → Backend → Frontend)**

```json
[
  {
    "operation": "create",
    "fromTaskId": "db-migration-uuid",
    "toTaskId": "backend-api-uuid",
    "type": "BLOCKS"
  },
  {
    "operation": "create",
    "fromTaskId": "backend-api-uuid",
    "toTaskId": "frontend-integration-uuid",
    "type": "BLOCKS"
  }
]
```

**Pattern 2: Parallel Dependencies (Multiple tasks blocked by one prerequisite)**

```json
[
  {
    "operation": "create",
    "fromTaskId": "core-feature-uuid",
    "toTaskId": "feature-variant-1-uuid",
    "type": "BLOCKS"
  },
  {
    "operation": "create",
    "fromTaskId": "core-feature-uuid",
    "toTaskId": "feature-variant-2-uuid",
    "type": "BLOCKS"
  }
]
```

**Pattern 3: Mixed Relationships**

```json
[
  {
    "operation": "create",
    "fromTaskId": "design-uuid",
    "toTaskId": "implementation-uuid",
    "type": "BLOCKS"
  },
  {
    "operation": "create",
    "fromTaskId": "implementation-uuid",
    "toTaskId": "testing-uuid",
    "type": "RELATES_TO"
  }
]
```

### Dependency Cleanup Workflow

When a task is being deleted or significantly changed:

1. **Query all dependencies**:

```json
{
  "containerId": "task-uuid",
  "direction": "all"
}
```

2. **Delete outgoing dependencies**:

```json
{
  "operation": "delete",
  "fromTaskId": "task-uuid",
  "deleteAll": true
}
```

3. **Delete incoming dependencies**:

```json
{
  "operation": "delete",
  "toTaskId": "task-uuid",
  "deleteAll": true
}
```

4. **Verify cleanup**:

```json
{
  "containerId": "task-uuid",
  "direction": "all"
}
```

---

## Error Handling

| Error Code | Condition | Solution |
|-----------|-----------|----------|
| VALIDATION_ERROR | Invalid operation | Use: `create` or `delete` |
| VALIDATION_ERROR | Missing required parameter | For create: provide `fromTaskId`, `toTaskId`. For delete: provide `id`, OR `fromTaskId`/`toTaskId`, OR one task ID with `deleteAll=true` |
| VALIDATION_ERROR | Invalid UUID format | Use valid UUID format: `550e8400-e29b-41d4-a716-446655440000` |
| VALIDATION_ERROR | fromTaskId and toTaskId are identical | Use different task IDs |
| VALIDATION_ERROR | Invalid dependency type | Use: `BLOCKS`, `IS_BLOCKED_BY`, or `RELATES_TO` |
| VALIDATION_ERROR | Cannot specify both `id` and task relationship parameters | For delete by ID, only use `id`. For delete by relationship, use `fromTaskId`/`toTaskId` |
| VALIDATION_ERROR | When using deleteAll, specify only one of `fromTaskId` or `toTaskId` | Choose either source or target, not both |
| VALIDATION_ERROR | For relationship-based deletion, must specify both `fromTaskId` and `toTaskId` | Provide both task IDs or use `deleteAll=true` with one ID |
| RESOURCE_NOT_FOUND | Source task doesn't exist | Verify `fromTaskId` exists with query_container |
| RESOURCE_NOT_FOUND | Target task doesn't exist | Verify `toTaskId` exists with query_container |
| RESOURCE_NOT_FOUND | Dependency with specified ID doesn't exist | Verify dependency ID with query_dependencies |
| RESOURCE_NOT_FOUND | No dependencies found between tasks | Verify relationship with query_dependencies |
| VALIDATION_ERROR | Circular dependency detected | Remove conflicting dependency or choose different target |
| VALIDATION_ERROR | Duplicate dependency | Dependency already exists; use existing ID or delete and recreate |
| DATABASE_ERROR | Unexpected database issue | Retry operation, contact support if persists |

### Error Response Format

```json
{
  "success": false,
  "message": "Human-readable error message",
  "error": {
    "code": "ERROR_CODE",
    "details": "Additional error details if available"
  }
}
```

### Common Error Scenarios

**Scenario 1: Self-Dependency Attempt**

```json
{
  "operation": "create",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

Response:

```json
{
  "success": false,
  "message": "Self-dependency not allowed",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "A task cannot depend on itself. fromTaskId and toTaskId must be different."
  }
}
```

**Scenario 2: Missing Task**

```json
{
  "operation": "create",
  "fromTaskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "toTaskId": "non-existent-uuid"
}
```

Response:

```json
{
  "success": false,
  "message": "Target task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with toTaskId non-existent-uuid"
  }
}
```

**Scenario 3: Invalid Delete Parameters**

```json
{
  "operation": "delete"
}
```

Response:

```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Must specify either 'id' for specific dependency deletion, or 'fromTaskId'/'toTaskId' for relationship-based deletion"
  }
}
```

---

## Integration Patterns

### Pattern 1: Create Dependency Chain for Feature

**Workflow**: Break down a feature into sequential tasks, create dependencies automatically.

```json
// Step 1: Create feature with tasks
{
  "operation": "create",
  "containerType": "feature",
  "name": "User Authentication System",
  "projectId": "project-uuid",
  "tags": "backend,authentication"
}
// Response: feature-uuid

// Step 2: Create sequential tasks
[
  {
    "operation": "create",
    "containerType": "task",
    "title": "Design authentication schema",
    "featureId": "feature-uuid",
    "complexity": 5
  },
  {
    "operation": "create",
    "containerType": "task",
    "title": "Implement user service",
    "featureId": "feature-uuid",
    "complexity": 7
  },
  {
    "operation": "create",
    "containerType": "task",
    "title": "Implement login endpoint",
    "featureId": "feature-uuid",
    "complexity": 6
  }
]
// Response: task-1-uuid, task-2-uuid, task-3-uuid

// Step 3: Create blocking dependencies
{
  "operation": "create",
  "fromTaskId": "task-1-uuid",
  "toTaskId": "task-2-uuid",
  "type": "BLOCKS"
}
// task-1 blocks task-2

{
  "operation": "create",
  "fromTaskId": "task-2-uuid",
  "toTaskId": "task-3-uuid",
  "type": "BLOCKS"
}
// task-2 blocks task-3
```

**Benefits**:
- Clear workflow visualization
- Prevents out-of-order work
- Easy to adjust dependencies later

### Pattern 2: Query and Cleanup

**Workflow**: Before deleting a task, cleanup all dependencies.

```json
// Step 1: Query dependencies
{
  "containerId": "task-to-delete-uuid",
  "direction": "all"
}
// Response shows: 2 incoming, 1 outgoing

// Step 2: Delete outgoing
{
  "operation": "delete",
  "fromTaskId": "task-to-delete-uuid",
  "deleteAll": true
}

// Step 3: Delete incoming
{
  "operation": "delete",
  "toTaskId": "task-to-delete-uuid",
  "deleteAll": true
}

// Step 4: Verify cleanup
{
  "containerId": "task-to-delete-uuid",
  "direction": "all"
}
// Response: 0 dependencies
```

### Pattern 3: Conditional Dependency Management

**Workflow**: Create different dependency types based on relationship strength.

```json
// Core prerequisite - use BLOCKS
{
  "operation": "create",
  "fromTaskId": "core-auth-uuid",
  "toTaskId": "oauth-impl-uuid",
  "type": "BLOCKS"
}

// Related but not blocking - use RELATES_TO
{
  "operation": "create",
  "fromTaskId": "password-reset-uuid",
  "toTaskId": "oauth-impl-uuid",
  "type": "RELATES_TO"
}
```

### Pattern 4: Dependency Visualization

Use with query_dependencies to build task workflow visualization:

```json
// Get all dependencies for feature
{
  "containerId": "feature-uuid",
  "direction": "all"
}
// Response: List of all task dependencies within feature
// Use to build dependency graph visualization
```

---

## Use Cases

### Use Case 1: Enforcing Implementation Order

**Scenario**: Database migration must complete before service implementation.

**Steps**:

1. Create both tasks:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Database schema migration",
  "featureId": "feature-uuid",
  "priority": "high",
  "complexity": 4
}
```

2. Link with BLOCKS dependency:

```json
{
  "operation": "create",
  "fromTaskId": "db-task-uuid",
  "toTaskId": "api-task-uuid",
  "type": "BLOCKS"
}
```

3. When getting next task, system shows db-task first (api-task is blocked)

**Benefit**: Prevents developers from starting dependent work too early

### Use Case 2: Cross-Feature Coordination

**Scenario**: Frontend team needs to wait for API endpoints before implementing integration.

**Steps**:

1. Create BLOCKS dependency across features:

```json
{
  "operation": "create",
  "fromTaskId": "api-endpoint-task-uuid",
  "toTaskId": "frontend-integration-task-uuid",
  "type": "BLOCKS"
}
```

2. API team completes task, status changes to completed
3. Frontend team now sees unblocked task in their queue

### Use Case 3: Documenting Related Work

**Scenario**: Testing task relates to implementation (same feature, both important to complete).

**Steps**:

```json
{
  "operation": "create",
  "fromTaskId": "implementation-uuid",
  "toTaskId": "testing-uuid",
  "type": "RELATES_TO"
}
```

**Benefit**: Teams see the relationship without blocking; flexible scheduling

### Use Case 4: Reorganizing Work

**Scenario**: Requirements change, need to remove and recreate dependencies.

**Steps**:

1. Find existing dependency:

```json
{
  "containerId": "old-task-uuid",
  "direction": "outgoing"
}
```

2. Delete old dependency:

```json
{
  "operation": "delete",
  "id": "old-dep-uuid"
}
```

3. Create new dependency:

```json
{
  "operation": "create",
  "fromTaskId": "old-task-uuid",
  "toTaskId": "new-task-uuid",
  "type": "BLOCKS"
}
```

---

## Best Practices

### DO

✅ **Use BLOCKS for strict prerequisites** - Sequential work where order matters

✅ **Use RELATES_TO for weak links** - Information sharing without blocking

✅ **Query before modifying** - Use query_dependencies to understand impact

✅ **Keep chains short** - Avoid deep dependency chains (3-4 levels max)

✅ **Document in task summaries** - Explain dependency relationships

✅ **Use task titles that describe workflow** - "Design schema" before "Implement service"

✅ **Create dependencies when planning** - Establish workflow before starting work

✅ **Verify with query_dependencies** - After create/delete, confirm changes

✅ **Clean up before deletion** - Remove all dependencies before deleting tasks

✅ **Consider both directions** - Both fromTaskId and toTaskId dependencies

### DON'T

❌ **Don't create circular dependencies** - Tool prevents, but understand why they're bad

❌ **Don't overuse BLOCKS** - Use RELATES_TO for informational links

❌ **Don't create deep chains** - Breaks visibility and flexibility

❌ **Don't delete without querying first** - Understand all dependencies before cleanup

❌ **Don't create dependencies between unrelated features** - Keep workflow scoped

❌ **Don't assume task order** - Always create explicit dependencies

❌ **Don't forget to update task descriptions** - Document why dependencies exist

❌ **Don't create duplicate dependencies** - Tool prevents, but don't attempt

❌ **Don't mix different dependency semantics** - Be consistent in usage

---

## Related Tools

- **query_dependencies** - Query task dependencies with filtering (read-only)
- **manage_container** - Create, update, delete tasks and features
- **query_container** - Search and retrieve container information
- **get_blocked_tasks** - Find tasks blocked by incomplete prerequisites
- **get_next_task** - Intelligent task recommendation respecting dependencies

---

## Integration Examples

### With Planning Specialist

Planning Specialist uses manage_dependency to:
1. Create initial dependency structure when breaking down features
2. Validate no circular dependencies exist
3. Create sequential task chains for implementation teams

### With Status Progression

When task status changes:
1. If task is marked completed, dependent tasks may become unblocked
2. If task is deleted, manage_dependency cleans up relationships
3. get_blocked_tasks updates based on dependency completion status

### With Task Search and Filtering

Dependencies determine task ordering:
1. get_next_task respects dependencies (blocked tasks lower priority)
2. get_blocked_tasks shows which tasks are waiting on others
3. Task search results show blocking status

---

## Error Scenarios and Recovery

### Scenario: Accidental Circular Dependency Attempt

**Problem**: User tries to create Task A → Task B when Task B → Task A exists

**Error**:
```json
{
  "message": "Circular dependency detected"
}
```

**Recovery**:
1. Query dependencies: `query_dependencies(taskId="TaskA", direction="all")`
2. Identify the backward link (Task B → Task A)
3. Delete the backward link
4. Retry the original create operation

### Scenario: Duplicate Dependency Created

**Problem**: Same dependency created twice

**Error**:
```json
{
  "message": "Duplicate dependency"
}
```

**Recovery**:
1. Error response includes existing dependency ID
2. Use that ID if you need to manage the existing dependency
3. Don't create again

### Scenario: Missing Task in Dependency

**Problem**: Reference to deleted task

**Error**:
```json
{
  "message": "Target task not found"
}
```

**Recovery**:
1. Verify task UUID with query_container
2. If task was deleted, remove this dependency
3. Consider creating dependency with different task

---

## Token Efficiency Notes

| Operation | Typical Tokens | Best For |
|-----------|----------------|----------|
| create | 150-200 | Single new dependency |
| delete (by ID) | 100-150 | Specific dependency removal |
| delete (by relationship) | 150-200 | Removing link between tasks |
| delete (deleteAll) | 150-200 | Cleaning up all task dependencies |

**Recommendation**: Batch dependency operations (create 3+ at once) for more efficiency than individual calls.

---

## References

### Source Code

- **Tool Implementation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/dependency/ManageDependencyTool.kt`
- **Domain Model**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/Dependency.kt`
- **Repository**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/repository/DependencyRepository.kt`

### Related Documentation

- **[query_dependencies Tool](query-dependencies.md)** - Read operations for comparison (complementary query tool)
- **[manage_container Tool](manage-container.md)** - Create/update tasks and features
- **[get_blocked_tasks Tool](get-blocked-tasks.md)** - Find blocked tasks
- **[get_next_task Tool](get-next-task.md)** - Task recommendation respecting dependencies
- **[API Reference](../api-reference.md)** - Complete tool documentation index
- **[Quick Start Guide](../quick-start.md)** - Getting started with Task Orchestrator

### Example Dataset

All examples use consistent IDs:

- **Project**: `b160fbdb-07e4-42d7-8c61-8deac7d2fc17` (MCP Task Orchestrator)
- **Feature**: `f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c` (Container Management API)
- **Task 1**: `a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d` (Database implementation)
- **Task 2**: `b2c3d4e5-f6a7-5b6c-9d0e-1f2a3b4c5d6e` (API implementation)
- **Dependency**: `c3d4e5f6-a7b8-6c7d-0e1f-2a3b4c5d6e7f` (Example dependency)

---

## Version History

- **v2.0.0** (2025-10-24): Initial comprehensive documentation for manage_dependency tool
- **v2.0.0-beta** (2025-10-19): manage_dependency tool release as part of v2.0 consolidation

---

## See Also

- [API Reference](../api-reference.md) - Complete tool documentation
- [query_dependencies Documentation](query-container.md) - Complementary read operations
- [Quick Start Guide](../quick-start.md) - Common workflows
- [Status Progression Guide](../status-progression.md) - How dependencies affect status
