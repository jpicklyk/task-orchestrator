# query_tasks

**Category:** Task Management
**Type:** Consolidated Query Tool
**Replaces:** search_tasks, get_blocked_tasks, get_next_task, bulk_update_tasks

## Purpose

Unified tool for all multi-entity task queries and operations. Consolidates 4 separate tools into one query-type-based interface, reducing token overhead by ~5k characters while preserving all functionality.

## Query Types

The `query_tasks` tool supports 4 query types via the `queryType` parameter:

| Query Type | Purpose | Common Use Cases |
|------------|---------|------------------|
| `search` | Find tasks by filters | Sprint planning, tag-based queries, status filtering |
| `blocked` | Identify blocked tasks | Bottleneck analysis, dependency troubleshooting |
| `next` | Get task recommendations | Work prioritization, "what should I do next?" |
| `bulkUpdate` | Update multiple tasks | Batch status changes, sprint completion, bulk edits |

## Parameters

### Common Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `queryType` | enum | **Yes** | Query type: `search`, `blocked`, `next`, `bulkUpdate` |

### Query-Specific Parameters

#### Search Query

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | No | null | Text search in titles and descriptions |
| `status` | enum | No | null | Filter by status (`pending`, `in-progress`, `completed`, `cancelled`, `deferred`) |
| `priority` | enum | No | null | Filter by priority (`high`, `medium`, `low`) |
| `featureId` | UUID | No | null | Filter by parent feature |
| `projectId` | UUID | No | null | Filter by parent project |
| `tag` | string | No | null | Filter by single tag |
| `limit` | integer | No | 20 | Max results (1-100) |
| `offset` | integer | No | 0 | Results to skip (pagination) |
| `sortBy` | enum | No | "modifiedAt" | Sort field: `createdAt`, `modifiedAt`, `priority`, `status`, `complexity` |
| `sortDirection` | enum | No | "desc" | Sort direction: `asc`, `desc` |

#### Blocked Query

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `projectId` | UUID | No | null | Filter by project |
| `featureId` | UUID | No | null | Filter by feature |
| `includeTaskDetails` | boolean | No | false | Include full task metadata |

#### Next Query

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `limit` | integer | No | 1 | Number of recommendations (1-20) |
| `projectId` | UUID | No | null | Filter by project |
| `featureId` | UUID | No | null | Filter by feature |
| `includeDetails` | boolean | No | false | Include summary, tags, featureId |

#### BulkUpdate Query

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `tasks` | array | **Yes** | - | Array of task updates (max 100) |

**Task object structure:**
```json
{
  "id": "task-uuid",           // Required
  "title": "...",              // Optional
  "description": "...",        // Optional
  "summary": "...",            // Optional
  "status": "...",             // Optional
  "priority": "...",           // Optional
  "complexity": 5,             // Optional
  "featureId": "...",          // Optional
  "tags": "tag1,tag2"          // Optional
}
```

## Response Formats

### Search Response

```json
{
  "success": true,
  "message": "Found 15 tasks",
  "data": {
    "items": [
      {
        "id": "640522b7-810e-49a2-865c-3725f5d39608",
        "title": "Implement OAuth login",
        "status": "in-progress",
        "priority": "high",
        "complexity": 8,
        "createdAt": "2025-10-18T12:00:00Z",
        "modifiedAt": "2025-10-18T14:30:00Z",
        "featureId": "550e8400-e29b-41d4-a716-446655440000",
        "projectId": null,
        "tags": ["backend", "security"]
      }
    ],
    "pagination": {
      "page": 1,
      "pageSize": 20,
      "totalItems": 15,
      "totalPages": 1,
      "hasNext": false,
      "hasPrevious": false
    }
  }
}
```

### Blocked Response

```json
{
  "success": true,
  "message": "Found 3 blocked task(s)",
  "data": {
    "blockedTasks": [
      {
        "taskId": "blocked-task-uuid",
        "title": "Implement user dashboard",
        "status": "pending",
        "priority": "high",
        "complexity": 7,
        "blockedBy": [
          {
            "taskId": "blocker-uuid-1",
            "title": "Design dashboard mockups",
            "status": "in-progress",
            "priority": "high"
          },
          {
            "taskId": "blocker-uuid-2",
            "title": "Create API endpoints",
            "status": "pending",
            "priority": "medium"
          }
        ],
        "blockerCount": 2
      }
    ],
    "totalBlocked": 3
  }
}
```

### Next Response

```json
{
  "success": true,
  "message": "Found 3 recommendation(s) from 12 unblocked task(s)",
  "data": {
    "recommendations": [
      {
        "taskId": "task-uuid-1",
        "title": "Fix login validation bug",
        "status": "pending",
        "priority": "high",
        "complexity": 3
      },
      {
        "taskId": "task-uuid-2",
        "title": "Add API rate limiting",
        "status": "pending",
        "priority": "high",
        "complexity": 7
      },
      {
        "taskId": "task-uuid-3",
        "title": "Update documentation",
        "status": "pending",
        "priority": "medium",
        "complexity": 2
      }
    ],
    "totalCandidates": 12
  }
}
```

### BulkUpdate Response

```json
{
  "success": true,
  "message": "5 tasks updated successfully",
  "data": {
    "items": [
      {
        "id": "task-1-uuid",
        "status": "completed",
        "modifiedAt": "2025-10-18T16:00:00Z"
      },
      {
        "id": "task-2-uuid",
        "status": "completed",
        "modifiedAt": "2025-10-18T16:00:00Z"
      }
    ],
    "updated": 5,
    "failed": 0
  }
}
```

**Partial success response** (some updates failed):
```json
{
  "success": true,
  "message": "3 tasks updated, 2 failed",
  "data": {
    "items": [
      {
        "id": "task-1-uuid",
        "status": "completed",
        "modifiedAt": "2025-10-18T16:00:00Z"
      }
    ],
    "updated": 3,
    "failed": 2,
    "failures": [
      {
        "index": 2,
        "id": "invalid-uuid",
        "error": {
          "code": "RESOURCE_NOT_FOUND",
          "details": "Task not found"
        }
      }
    ]
  }
}
```

## Examples

### Example 1: Search for High-Priority Backend Tasks

**Scenario:** Find all high-priority backend tasks for sprint planning.

**Request:**
```json
{
  "queryType": "search",
  "priority": "high",
  "tag": "backend",
  "status": "pending",
  "sortBy": "complexity",
  "sortDirection": "asc",
  "limit": 10
}
```

**Response:**
```json
{
  "success": true,
  "message": "Found 5 tasks",
  "data": {
    "items": [
      {
        "id": "task-1-uuid",
        "title": "Fix authentication bug",
        "status": "pending",
        "priority": "high",
        "complexity": 3,
        "tags": ["backend", "bug", "security"]
      },
      {
        "id": "task-2-uuid",
        "title": "Implement rate limiting",
        "status": "pending",
        "priority": "high",
        "complexity": 6,
        "tags": ["backend", "api", "security"]
      },
      {
        "id": "task-3-uuid",
        "title": "Database migration for users table",
        "status": "pending",
        "priority": "high",
        "complexity": 8,
        "tags": ["backend", "database"]
      }
    ],
    "pagination": {
      "page": 1,
      "pageSize": 10,
      "totalItems": 5,
      "totalPages": 1,
      "hasNext": false,
      "hasPrevious": false
    }
  }
}
```

**Use Case:** AI can present tasks in complexity order, suggesting "start with easy wins" (complexity 3) before tackling harder tasks (complexity 8).

---

### Example 2: Identify Blocked Tasks for Bottleneck Analysis

**Scenario:** Find all tasks blocked by incomplete dependencies to identify workflow bottlenecks.

**Request:**
```json
{
  "queryType": "blocked",
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "includeTaskDetails": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Found 2 blocked task(s)",
  "data": {
    "blockedTasks": [
      {
        "taskId": "blocked-task-1-uuid",
        "title": "Implement user dashboard",
        "status": "pending",
        "priority": "high",
        "complexity": 7,
        "summary": "Create interactive dashboard for user analytics",
        "featureId": "550e8400-e29b-41d4-a716-446655440000",
        "tags": ["frontend", "dashboard"],
        "blockedBy": [
          {
            "taskId": "blocker-1-uuid",
            "title": "Design dashboard mockups",
            "status": "in-progress",
            "priority": "high",
            "complexity": 5
          },
          {
            "taskId": "blocker-2-uuid",
            "title": "Create analytics API endpoints",
            "status": "pending",
            "priority": "medium",
            "complexity": 6
          }
        ],
        "blockerCount": 2
      },
      {
        "taskId": "blocked-task-2-uuid",
        "title": "Add export functionality",
        "status": "pending",
        "priority": "medium",
        "complexity": 4,
        "summary": "Allow users to export dashboard data as CSV",
        "featureId": "550e8400-e29b-41d4-a716-446655440000",
        "tags": ["frontend", "export"],
        "blockedBy": [
          {
            "taskId": "blocked-task-1-uuid",
            "title": "Implement user dashboard",
            "status": "pending",
            "priority": "high"
          }
        ],
        "blockerCount": 1
      }
    ],
    "totalBlocked": 2
  }
}
```

**AI Analysis:** "Two tasks blocked. Critical path: Complete 'Design dashboard mockups' (in-progress) and 'Create analytics API endpoints' (pending) to unblock 'Implement user dashboard', which is itself blocking 'Add export functionality'."

---

### Example 3: Get Next Task Recommendation

**Scenario:** "What should I work on next?" - Get intelligent task recommendations.

**Request:**
```json
{
  "queryType": "next",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "limit": 5,
  "includeDetails": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "Found 5 recommendation(s) from 18 unblocked task(s)",
  "data": {
    "recommendations": [
      {
        "taskId": "task-1-uuid",
        "title": "Fix critical login bug",
        "status": "pending",
        "priority": "high",
        "complexity": 2,
        "summary": "Users cannot log in with special characters in password",
        "featureId": "auth-feature-uuid",
        "tags": ["bug", "critical", "backend"]
      },
      {
        "taskId": "task-2-uuid",
        "title": "Add input validation to registration",
        "status": "pending",
        "priority": "high",
        "complexity": 4,
        "summary": "Validate email format and password strength",
        "featureId": "auth-feature-uuid",
        "tags": ["backend", "security", "validation"]
      },
      {
        "taskId": "task-3-uuid",
        "title": "Implement OAuth provider integration",
        "status": "pending",
        "priority": "high",
        "complexity": 9,
        "summary": "Add Google and GitHub OAuth login options",
        "featureId": "auth-feature-uuid",
        "tags": ["backend", "oauth", "authentication"]
      },
      {
        "taskId": "task-4-uuid",
        "title": "Update API documentation",
        "status": "pending",
        "priority": "medium",
        "complexity": 3,
        "summary": "Document new authentication endpoints",
        "featureId": "auth-feature-uuid",
        "tags": ["documentation", "api"]
      },
      {
        "taskId": "task-5-uuid",
        "title": "Add rate limiting to login endpoint",
        "status": "pending",
        "priority": "medium",
        "complexity": 5,
        "summary": "Prevent brute force attacks",
        "featureId": "auth-feature-uuid",
        "tags": ["backend", "security", "rate-limiting"]
      }
    ],
    "totalCandidates": 18
  }
}
```

**Recommendation Logic:**
1. **High priority, low complexity first** (quick wins: task-1 complexity 2, task-2 complexity 4)
2. **High priority, higher complexity** (important but time-consuming: task-3 complexity 9)
3. **Medium priority, lower complexity** (task-4 complexity 3, task-5 complexity 5)

**AI Guidance:** "Start with 'Fix critical login bug' (high priority, complexity 2) - quick win that unblocks users. Then tackle 'Add input validation' (complexity 4). Save 'Implement OAuth' (complexity 9) for when you have focused time."

---

### Example 4: Bulk Update Tasks After Sprint Completion

**Scenario:** Mark all completed sprint tasks as done in one operation.

**Request:**
```json
{
  "queryType": "bulkUpdate",
  "tasks": [
    {
      "id": "task-1-uuid",
      "status": "completed"
    },
    {
      "id": "task-2-uuid",
      "status": "completed"
    },
    {
      "id": "task-3-uuid",
      "status": "completed"
    },
    {
      "id": "task-4-uuid",
      "status": "completed"
    },
    {
      "id": "task-5-uuid",
      "status": "completed"
    },
    {
      "id": "task-6-uuid",
      "status": "completed"
    },
    {
      "id": "task-7-uuid",
      "status": "completed"
    },
    {
      "id": "task-8-uuid",
      "status": "completed"
    },
    {
      "id": "task-9-uuid",
      "status": "completed"
    },
    {
      "id": "task-10-uuid",
      "status": "completed"
    }
  ]
}
```

**Response:**
```json
{
  "success": true,
  "message": "10 tasks updated successfully",
  "data": {
    "items": [
      {
        "id": "task-1-uuid",
        "status": "completed",
        "modifiedAt": "2025-10-18T16:00:00Z"
      },
      {
        "id": "task-2-uuid",
        "status": "completed",
        "modifiedAt": "2025-10-18T16:00:00Z"
      }
      // ... 8 more
    ],
    "updated": 10,
    "failed": 0
  }
}
```

**Token Savings:**
- **Individual calls:** 10 × ~1,200 chars = ~12,000 chars
- **Bulk operation:** ~650 chars
- **Savings:** 95% (11,350 chars saved!)

---

### Example 5: Search with Pagination

**Scenario:** Browse all pending tasks across entire project with pagination.

**Request (Page 1):**
```json
{
  "queryType": "search",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "status": "pending",
  "sortBy": "priority",
  "sortDirection": "desc",
  "limit": 20,
  "offset": 0
}
```

**Response:**
```json
{
  "success": true,
  "message": "Found 20 tasks",
  "data": {
    "items": [
      // ... 20 tasks
    ],
    "pagination": {
      "page": 1,
      "pageSize": 20,
      "totalItems": 47,
      "totalPages": 3,
      "hasNext": true,
      "hasPrevious": false
    }
  }
}
```

**Request (Page 2):**
```json
{
  "queryType": "search",
  "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
  "status": "pending",
  "sortBy": "priority",
  "sortDirection": "desc",
  "limit": 20,
  "offset": 20
}
```

**Response:**
```json
{
  "success": true,
  "message": "Found 20 tasks",
  "data": {
    "items": [
      // ... next 20 tasks
    ],
    "pagination": {
      "page": 2,
      "pageSize": 20,
      "totalItems": 47,
      "totalPages": 3,
      "hasNext": true,
      "hasPrevious": true
    }
  }
}
```

---

### Example 6: Complex Search with Multiple Filters

**Scenario:** Find in-progress backend tasks in specific feature, sorted by complexity.

**Request:**
```json
{
  "queryType": "search",
  "featureId": "550e8400-e29b-41d4-a716-446655440000",
  "tag": "backend",
  "status": "in-progress",
  "priority": "high",
  "sortBy": "complexity",
  "sortDirection": "asc",
  "limit": 10
}
```

**Response:**
```json
{
  "success": true,
  "message": "Found 4 tasks",
  "data": {
    "items": [
      {
        "id": "task-1-uuid",
        "title": "Add error logging",
        "status": "in-progress",
        "priority": "high",
        "complexity": 3,
        "tags": ["backend", "logging"]
      },
      {
        "id": "task-2-uuid",
        "title": "Implement caching layer",
        "status": "in-progress",
        "priority": "high",
        "complexity": 6,
        "tags": ["backend", "performance", "caching"]
      },
      {
        "id": "task-3-uuid",
        "title": "Database query optimization",
        "status": "in-progress",
        "priority": "high",
        "complexity": 7,
        "tags": ["backend", "database", "performance"]
      },
      {
        "id": "task-4-uuid",
        "title": "Implement distributed tracing",
        "status": "in-progress",
        "priority": "high",
        "complexity": 9,
        "tags": ["backend", "observability", "tracing"]
      }
    ],
    "pagination": {
      "page": 1,
      "pageSize": 10,
      "totalItems": 4,
      "totalPages": 1,
      "hasNext": false,
      "hasPrevious": false
    }
  }
}
```

## Best Practices

### 1. Use Appropriate Query Type

| Scenario | Query Type | Reasoning |
|----------|------------|-----------|
| "Show me all backend tasks" | `search` | Filtering by attributes |
| "Why is nothing moving?" | `blocked` | Finding bottlenecks |
| "What should I do next?" | `next` | Work prioritization |
| "Mark sprint done" | `bulkUpdate` | Batch status changes |

### 2. Optimize Search Queries

**❌ Too broad:**
```json
{
  "queryType": "search"
}
// Returns everything - overwhelming
```

**✅ Focused:**
```json
{
  "queryType": "search",
  "status": "pending",
  "priority": "high",
  "tag": "backend",
  "limit": 10
}
// Returns actionable subset
```

### 3. Use Pagination for Large Result Sets

**For 100+ results:**
```json
{
  "queryType": "search",
  "limit": 20,
  "offset": 0
}
```

**Then navigate:**
```json
{
  "queryType": "search",
  "limit": 20,
  "offset": 20  // Next page
}
```

### 4. Leverage Blocked Query for Workflow Analysis

**Daily standup:**
```json
{
  "queryType": "blocked",
  "projectId": "current-project-uuid"
}
```

**Response helps answer:** "What's blocking progress? Which tasks should we prioritize to unblock others?"

### 5. Use Next Query for Context Switching

**After completing a task:**
```json
{
  "queryType": "next",
  "featureId": "current-feature-uuid",
  "limit": 3
}
```

**AI suggests:** "Here are 3 unblocked tasks in your current feature, sorted by priority and complexity."

### 6. Bulk Updates for Efficiency

**❌ Inefficient (10 separate calls):**
```bash
manage_task --operation update --id task-1 --status completed
manage_task --operation update --id task-2 --status completed
# ... 8 more calls
```

**✅ Efficient (1 bulk call):**
```json
{
  "queryType": "bulkUpdate",
  "tasks": [
    {"id": "task-1", "status": "completed"},
    {"id": "task-2", "status": "completed"}
    // ... 8 more
  ]
}
```

## Error Handling

### Validation Errors

```json
{
  "success": false,
  "message": "Invalid status: in_progres",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: in_progres"
  }
}
```

### Bulk Update Partial Failure

```json
{
  "success": true,
  "message": "7 tasks updated, 3 failed",
  "data": {
    "items": [
      // ... successful updates
    ],
    "updated": 7,
    "failed": 3,
    "failures": [
      {
        "index": 2,
        "id": "invalid-uuid",
        "error": {
          "code": "RESOURCE_NOT_FOUND",
          "details": "Task not found"
        }
      },
      {
        "index": 5,
        "id": "another-uuid",
        "error": {
          "code": "VALIDATION_ERROR",
          "details": "Invalid status"
        }
      }
    ]
  }
}
```

**Handling:** Process successful updates, retry or investigate failed ones.

## Related Tools

- **manage_task** - Single-entity operations (create, get, update, delete, export)
- **get_task** - Detailed task retrieval (alternative to query_tasks search)
- **list_tags** - Discover available tags before searching
- **create_dependency** - Create blocking relationships
- **set_status** - Simple status updates (alternative to bulkUpdate for single tasks)

## Migration from Deprecated Tools

See [Migration Guide](../migration/task-tool-consolidation.md) for complete migration instructions from:
- `search_tasks` → `query_tasks` (queryType: search)
- `get_blocked_tasks` → `query_tasks` (queryType: blocked)
- `get_next_task` → `query_tasks` (queryType: next)
- `bulk_update_tasks` → `query_tasks` (queryType: bulkUpdate)

## Performance Notes

- **Search:** Query time ~10-50ms depending on filters and result size
- **Blocked:** Dependency analysis ~20-100ms (scales with number of dependencies)
- **Next:** Recommendation calculation ~15-60ms (dependency checking + sorting)
- **BulkUpdate:** ~5-10ms per task (parallel processing where possible)

**Optimization Tips:**
- Use specific filters to reduce result set size
- Limit results to what you need (don't fetch 100 when 10 will do)
- Use pagination for large datasets
- Bulk updates are ~10x faster than individual updates
