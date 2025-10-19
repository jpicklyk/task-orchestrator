# Task Tool Consolidation Migration Guide

**Version:** 1.1.0-beta
**Date:** 2025-10-18
**Status:** Active (Deprecated tools still supported)

## Overview

Task Orchestrator v1.1.0 introduces **consolidated task tools** that reduce token overhead by **84%** (from ~10.8k to ~1.7k tokens) while preserving all functionality. Nine separate tools have been consolidated into two powerful, operation-based tools.

### Why Consolidation?

**Token Efficiency:** AI agents load MCP tool schemas for every request. Having 9 separate task tools meant:
- ~1,200 characters per tool × 9 tools = ~10,800 characters per request
- Massive context window consumption for simple operations
- Slower AI response times due to schema processing overhead

**Consolidation Benefits:**
- ✅ **84% token reduction** (~10.8k → ~1.7k tokens)
- ✅ **Simpler mental model** (2 tools vs 9)
- ✅ **Consistent patterns** across all operations
- ✅ **Backward compatible** (old tools still work)
- ✅ **Better AI routing** (operation-based selection)

### What Changed

**Before (9 separate tools):**
- `create_task` - Create tasks
- `get_task` - Retrieve task details
- `update_task` - Update task fields
- `delete_task` - Remove tasks
- `task_to_markdown` - Export to markdown
- `search_tasks` - Find tasks by filters
- `get_blocked_tasks` - Identify blocked tasks
- `get_next_task` - Get task recommendations
- `bulk_update_tasks` - Update multiple tasks

**After (2 consolidated tools):**
- `manage_task` - **Single-entity operations** (create, get, update, delete, export)
- `query_tasks` - **Multi-entity queries** (search, blocked, next, bulkUpdate)

### Timeline

- **Now:** Both old and new tools available (deprecated tools marked)
- **v1.2.0 (Q2 2025):** Deprecated tools show warnings
- **v2.0.0 (Q4 2025):** Deprecated tools removed

---

## Migration Mapping

### From `create_task` → `manage_task`

**Before:**
```json
{
  "tool": "create_task",
  "parameters": {
    "title": "Implement user authentication",
    "summary": "Add JWT-based authentication to the API",
    "status": "pending",
    "priority": "high",
    "complexity": 7,
    "tags": "backend,security,api",
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "templateIds": ["661e8511-e29b-41d4-a716-446655440001"]
  }
}
```

**After:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "create",
    "title": "Implement user authentication",
    "summary": "Add JWT-based authentication to the API",
    "status": "pending",
    "priority": "high",
    "complexity": 7,
    "tags": "backend,security,api",
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "templateIds": ["661e8511-e29b-41d4-a716-446655440001"]
  }
}
```

**Change:** Add `"operation": "create"` parameter. All other parameters identical.

---

### From `get_task` → `manage_task`

**Before:**
```json
{
  "tool": "get_task",
  "parameters": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "includeSections": true,
    "includeFeature": true,
    "includeDependencies": true,
    "summaryView": false
  }
}
```

**After:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "get",
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "includeSections": true,
    "includeFeature": true,
    "includeDependencies": true,
    "summaryView": false
  }
}
```

**Change:** Add `"operation": "get"` parameter. All other parameters identical.

---

### From `update_task` → `manage_task`

**Before:**
```json
{
  "tool": "update_task",
  "parameters": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "status": "completed",
    "complexity": 8
  }
}
```

**After:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "update",
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "status": "completed",
    "complexity": 8
  }
}
```

**Change:** Add `"operation": "update"` parameter. Partial updates still supported (only send fields you're changing).

---

### From `delete_task` → `manage_task`

**Before:**
```json
{
  "tool": "delete_task",
  "parameters": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "deleteSections": true,
    "force": false
  }
}
```

**After:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "delete",
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "deleteSections": true,
    "force": false
  }
}
```

**Change:** Add `"operation": "delete"` parameter. All other parameters identical.

---

### From `task_to_markdown` → `manage_task`

**Before:**
```json
{
  "tool": "task_to_markdown",
  "parameters": {
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "includeSections": true
  }
}
```

**After:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "export",
    "id": "640522b7-810e-49a2-865c-3725f5d39608",
    "format": "markdown",
    "includeSections": true
  }
}
```

**Change:**
- Add `"operation": "export"` parameter
- Optionally specify `"format": "markdown"` (default) or `"format": "json"`

---

### From `search_tasks` → `query_tasks`

**Before:**
```json
{
  "tool": "search_tasks",
  "parameters": {
    "status": "pending",
    "priority": "high",
    "tag": "backend",
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "limit": 20,
    "offset": 0,
    "sortBy": "priority",
    "sortDirection": "desc"
  }
}
```

**After:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "search",
    "status": "pending",
    "priority": "high",
    "tag": "backend",
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "limit": 20,
    "offset": 0,
    "sortBy": "priority",
    "sortDirection": "desc"
  }
}
```

**Change:** Add `"queryType": "search"` parameter. All other parameters identical.

---

### From `get_blocked_tasks` → `query_tasks`

**Before:**
```json
{
  "tool": "get_blocked_tasks",
  "parameters": {
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "includeTaskDetails": true
  }
}
```

**After:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "blocked",
    "projectId": "a4fae8cb-7640-4527-bd89-11effbb1d039",
    "includeTaskDetails": true
  }
}
```

**Change:** Add `"queryType": "blocked"` parameter. All other parameters identical.

---

### From `get_next_task` → `query_tasks`

**Before:**
```json
{
  "tool": "get_next_task",
  "parameters": {
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "limit": 3,
    "includeDetails": true
  }
}
```

**After:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "next",
    "featureId": "550e8400-e29b-41d4-a716-446655440000",
    "limit": 3,
    "includeDetails": true
  }
}
```

**Change:** Add `"queryType": "next"` parameter. All other parameters identical.

---

### From `bulk_update_tasks` → `query_tasks`

**Before:**
```json
{
  "tool": "bulk_update_tasks",
  "parameters": {
    "tasks": [
      {
        "id": "640522b7-810e-49a2-865c-3725f5d39608",
        "status": "completed"
      },
      {
        "id": "661e8511-e29b-41d4-a716-446655440001",
        "status": "completed"
      }
    ]
  }
}
```

**After:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "bulkUpdate",
    "tasks": [
      {
        "id": "640522b7-810e-49a2-865c-3725f5d39608",
        "status": "completed"
      },
      {
        "id": "661e8511-e29b-41d4-a716-446655440001",
        "status": "completed"
      }
    ]
  }
}
```

**Change:** Add `"queryType": "bulkUpdate"` parameter. All other parameters identical.

---

## Operation Reference

### `manage_task` Operations

| Operation | Purpose | Required Parameters | Optional Parameters |
|-----------|---------|---------------------|---------------------|
| `create` | Create new task | `title` | `summary`, `description`, `status`, `priority`, `complexity`, `featureId`, `projectId`, `tags`, `templateIds` |
| `get` | Retrieve task | `id` | `includeSections`, `includeFeature`, `includeDependencies`, `summaryView` |
| `update` | Update task | `id` | Any task field (partial updates supported) |
| `delete` | Remove task | `id` | `deleteSections`, `cascade`, `force` |
| `export` | Export task | `id` | `format` (markdown/json), `includeSections` |

### `query_tasks` Query Types

| Query Type | Purpose | Required Parameters | Optional Parameters |
|------------|---------|---------------------|---------------------|
| `search` | Find tasks by filters | None | `query`, `status`, `priority`, `featureId`, `projectId`, `tag`, `limit`, `offset`, `sortBy`, `sortDirection` |
| `blocked` | Identify blocked tasks | None | `projectId`, `featureId`, `includeTaskDetails` |
| `next` | Get recommendations | None | `limit`, `projectId`, `featureId`, `includeDetails` |
| `bulkUpdate` | Update multiple tasks | `tasks` (array) | None (fields in task objects) |

---

## Benefits

### 1. Token Reduction

**Before consolidation:**
```
create_task schema:        ~1,200 chars
get_task schema:           ~1,100 chars
update_task schema:        ~1,150 chars
delete_task schema:        ~1,000 chars
task_to_markdown schema:   ~900 chars
search_tasks schema:       ~1,300 chars
get_blocked_tasks schema:  ~800 chars
get_next_task schema:      ~950 chars
bulk_update_tasks schema:  ~2,400 chars
────────────────────────────────────────
TOTAL:                     ~10,800 chars
```

**After consolidation:**
```
manage_task schema:        ~900 chars
query_tasks schema:        ~800 chars
────────────────────────────────────────
TOTAL:                     ~1,700 chars

SAVINGS: 84% (9,100 chars saved!)
```

### 2. Simpler Mental Model

**Consolidated approach makes AI routing clearer:**

- **Single entity operation?** → Use `manage_task` + operation type
- **Multi-entity query?** → Use `query_tasks` + query type

**AI agents benefit from:**
- Fewer tools to evaluate per request
- Clearer separation of concerns (CRUD vs queries)
- Consistent parameter patterns across operations

### 3. Consistent Patterns

All operations follow the same structure:
1. Specify operation/query type first
2. Provide entity identifier (if needed)
3. Add operation-specific parameters

### 4. Future-Proof

New operations can be added to existing tools without introducing additional tools:
- `manage_task` can add new operations (e.g., `clone`, `archive`)
- `query_tasks` can add new query types (e.g., `analytics`, `timeline`)

---

## Backward Compatibility

### Deprecated Tools Still Work

All 9 deprecated tools remain fully functional in v1.1.0:

```json
// ✓ Still works (but shows deprecation notice in logs)
{
  "tool": "create_task",
  "parameters": {
    "title": "My Task"
  }
}

// ✓ New recommended approach
{
  "tool": "manage_task",
  "parameters": {
    "operation": "create",
    "title": "My Task"
  }
}
```

### Migration Strategy

**Recommended approach:**

1. **Immediate:** Start using new tools for new code
2. **Gradual:** Update existing workflows as you encounter them
3. **No rush:** Take advantage of deprecation period (v1.1.0 → v2.0.0)

**No breaking changes until v2.0.0** (estimated Q4 2025).

---

## AI Agent Updates

### MCP Tool Descriptions

The new tools include comprehensive inline documentation that AI agents automatically discover:

**`manage_task` description excerpt:**
```
Unified task management: create, get, update, delete, or export tasks.

Parameters:
| Field | Type | Required | Description |
| operation | enum | Yes | Operation: create, get, update, delete, export |
...
```

**`query_tasks` description excerpt:**
```
Multi-purpose task query tool. Consolidates search, blocked tasks,
next task, and bulk update operations.

Query Types:
1. "search" - Find tasks by filters...
2. "blocked" - Identify tasks blocked by dependencies...
...
```

### Claude Code Skills/Subagents

**Skills and subagents automatically use new tools** - no configuration needed. The agent-mapping.yaml and skill definitions have been updated to reference the new tool names.

---

## Examples by Use Case

### Creating a Task with Templates

**Old way:**
```json
{
  "tool": "create_task",
  "parameters": {
    "title": "Implement OAuth login",
    "summary": "Add OAuth 2.0 authentication",
    "priority": "high",
    "complexity": 8,
    "tags": "backend,security",
    "templateIds": ["auth-template-uuid"]
  }
}
```

**New way:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "create",
    "title": "Implement OAuth login",
    "summary": "Add OAuth 2.0 authentication",
    "priority": "high",
    "complexity": 8,
    "tags": "backend,security",
    "templateIds": ["auth-template-uuid"]
  }
}
```

---

### Finding Blocked Tasks for Sprint Planning

**Old way:**
```json
{
  "tool": "get_blocked_tasks",
  "parameters": {
    "featureId": "feature-uuid",
    "includeTaskDetails": true
  }
}
```

**New way:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "blocked",
    "featureId": "feature-uuid",
    "includeTaskDetails": true
  }
}
```

---

### Getting Next Task Recommendation

**Old way:**
```json
{
  "tool": "get_next_task",
  "parameters": {
    "projectId": "project-uuid",
    "limit": 5,
    "includeDetails": true
  }
}
```

**New way:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "next",
    "projectId": "project-uuid",
    "limit": 5,
    "includeDetails": true
  }
}
```

---

### Bulk Status Updates

**Old way:**
```json
{
  "tool": "bulk_update_tasks",
  "parameters": {
    "tasks": [
      {"id": "task-1-uuid", "status": "completed"},
      {"id": "task-2-uuid", "status": "completed"},
      {"id": "task-3-uuid", "status": "completed"}
    ]
  }
}
```

**New way:**
```json
{
  "tool": "query_tasks",
  "parameters": {
    "queryType": "bulkUpdate",
    "tasks": [
      {"id": "task-1-uuid", "status": "completed"},
      {"id": "task-2-uuid", "status": "completed"},
      {"id": "task-3-uuid", "status": "completed"}
    ]
  }
}
```

---

### Exporting Task to Markdown

**Old way:**
```json
{
  "tool": "task_to_markdown",
  "parameters": {
    "id": "task-uuid",
    "includeSections": true
  }
}
```

**New way:**
```json
{
  "tool": "manage_task",
  "parameters": {
    "operation": "export",
    "id": "task-uuid",
    "format": "markdown",
    "includeSections": true
  }
}
```

---

## FAQs

### Q: Do I need to update my code immediately?

**A:** No. Deprecated tools remain fully functional in v1.1.0 and will continue working until v2.0.0 (estimated Q4 2025). Migrate at your own pace.

### Q: Will old tools show warnings?

**A:** Starting in v1.2.0 (Q2 2025), deprecated tools will log deprecation warnings. They'll still work perfectly, but logs will encourage migration.

### Q: Can I mix old and new tools?

**A:** Yes! Old and new tools work together seamlessly. You can gradually migrate workflows without breaking anything.

### Q: What if I forget the operation/query type?

**A:** AI agents have access to the full MCP schema with clear descriptions. The tool descriptions include tables showing all available operations and their parameters.

### Q: Are responses identical between old and new tools?

**A:** Yes. Response formats are identical. Only the request structure changes (adding operation/queryType parameter).

### Q: What about performance?

**A:** Performance is identical for individual operations. The token savings improve overall AI agent performance by reducing context consumption.

### Q: Can custom tools or scripts continue using old tools?

**A:** Yes. Old tools remain available until v2.0.0. You have plenty of time to update custom integrations.

---

## Support

### Questions or Issues?

- **GitHub Issues:** [github.com/jpickly/mcp-task-orchestrator/issues](https://github.com/jpickly/mcp-task-orchestrator/issues)
- **Documentation:** [docs/api-reference.md](../api-reference.md)
- **Tool Docs:**
  - [docs/tools/manage-task.md](../tools/manage-task.md)
  - [docs/tools/query-tasks.md](../tools/query-tasks.md)

### Migration Assistance

If you encounter issues migrating:

1. Check this guide for correct parameter mapping
2. Review tool-specific documentation
3. Open a GitHub issue with specific examples
4. Ask Claude directly (Claude has full MCP schema access)

---

## Summary

**Task tool consolidation achieves:**
- ✅ 84% token reduction (~10.8k → ~1.7k)
- ✅ Simplified mental model (9 tools → 2 tools)
- ✅ Consistent operation patterns
- ✅ Backward compatibility (no breaking changes until v2.0.0)
- ✅ Future-proof architecture

**Migration is simple:**
- Add `operation` parameter to `manage_task` calls
- Add `queryType` parameter to `query_tasks` calls
- All other parameters remain identical

**No rush to migrate:**
- Deprecated tools work until v2.0.0
- Gradual migration recommended
- Full support for both approaches during transition
