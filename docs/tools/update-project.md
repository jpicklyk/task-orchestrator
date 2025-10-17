# update_project Tool - Detailed Documentation

## Overview

Updates an existing project's metadata. Supports partial updates - only send fields you want to change. Uses locking to prevent concurrent modification conflicts.

**Resource**: `task-orchestrator://docs/tools/update-project`

## Key Concepts

### Partial Update Support
Only fields specified in the request are updated:
- Unspecified fields remain unchanged
- Reduces token usage by sending only changes
- No need to retrieve current values first (for simple updates)

### Concurrency Control
Uses locking mechanism to prevent conflicts:
- Automatic lock acquisition before update
- Safe for concurrent AI agent operations
- Lock released after update completes

### Metadata vs Content
- **update_project**: Changes project metadata (name, status, summary, tags)
- **Sections**: For detailed content changes, use `update_section_text` or `add_section`

## Parameter Reference

### Required Parameters
- **id** (UUID): Project identifier

### Optional Parameters (at least one required)
- **name** (string): New project name
- **summary** (string, max 500 chars): New brief summary
- **description** (string): New detailed description (no length limit)
- **status** (enum): New project status
  - `planning` - Initial planning phase
  - `in-development` - Active development
  - `completed` - Project finished
  - `archived` - Historical record
- **tags** (string): New comma-separated tags (replaces existing tags)

## Common Usage Patterns

### Pattern 1: Status Update
Most common operation - update project status as work progresses.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "status": "in-development"
}
```

**When to use**:
- Moving from planning to development
- Marking project complete
- Archiving completed projects

**Token usage**: ~150 tokens

**Alternative**: Use `set_status` for even more efficiency:
```javascript
await set_status({
  id: "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  status: "in-development"
});
```

### Pattern 2: Update Summary
Update agent-generated summary after project changes.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "summary": "Mobile app redesign with new authentication, dashboard widgets, and offline support"
}
```

**When to use**:
- After adding/removing features
- After scope changes
- After project direction changes

**Token usage**: ~200 tokens

### Pattern 3: Add/Update Tags
Update categorization tags.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "tags": "mobile,redesign,2025-q2,high-priority,authentication,dashboard"
}
```

**When to use**:
- Adding new tags for categorization
- Removing obsolete tags
- Re-organizing project taxonomy

**Token usage**: ~150 tokens

**IMPORTANT**: This replaces ALL existing tags. To preserve existing tags:
```javascript
// Step 1: Get current tags
const project = await get_project({ id: projectId });
const currentTags = project.data.tags;

// Step 2: Add new tags
const updatedTags = [...currentTags, "new-tag"].join(",");

// Step 3: Update
await update_project({
  id: projectId,
  tags: updatedTags
});
```

### Pattern 4: Rename Project
Change project name to reflect scope or focus changes.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "name": "Mobile App v2.0 - Authentication & Dashboard"
}
```

**When to use**:
- Clarifying project scope
- After major scope changes
- Correcting naming mistakes

**Token usage**: ~150 tokens

### Pattern 5: Multiple Field Update
Update several fields simultaneously.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "name": "Customer Portal v2.0 (Phase 1)",
  "summary": "Phase 1 focus: Real-time dashboard and user management. Deferred: Payment integration to Phase 2",
  "status": "in-development",
  "tags": "customer-portal,phase-1,frontend,backend,2025-q2"
}
```

**When to use**:
- Major project reorganization
- After project kickoff meeting
- Scope refinement

**Token usage**: ~250 tokens

**Best practice**: Combine related changes in a single update to reduce API calls

## Response Structure

### Success Response
Returns minimal response to optimize bandwidth:

```json
{
  "success": true,
  "message": "Project updated successfully",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "status": "in-development",
    "modifiedAt": "2025-10-17T16:20:00Z"
  }
}
```

**Key fields**:
- `id`: Project identifier (confirmation)
- `status`: Current project status
- `modifiedAt`: When the update occurred

**Note**: Only essential fields returned. Use `get_project` if you need full details after update.

## Common Workflows

### Workflow 1: Progress Project Through Lifecycle

```javascript
// Start planning
await update_project({
  id: projectId,
  status: "planning"
});

// ... planning activities ...

// Move to development
await update_project({
  id: projectId,
  status: "in-development",
  summary: "Started development with 3 initial features in progress"
});

// ... development activities ...

// Mark complete
await update_project({
  id: projectId,
  status: "completed",
  summary: "Completed all features. Deployed to production. 8 features delivered successfully"
});

// ... after some time ...

// Archive
await update_project({
  id: projectId,
  status: "archived"
});
```

### Workflow 2: Update After Feature Addition

```javascript
// Get current state
const project = await get_project({
  id: projectId,
  includeFeatures: true
});

// Add new feature
const newFeature = await create_feature({
  name: "Push Notifications",
  projectId: projectId,
  priority: "medium"
});

// Update project summary to reflect new feature
await update_project({
  id: projectId,
  summary: `${project.data.summary}. Added push notification support`,
  tags: `${project.data.tags.join(",")},notifications`
});
```

### Workflow 3: Scope Refinement

```javascript
// Original broad project
// Name: "E-Commerce Platform Redesign"
// Summary: "Complete overhaul of e-commerce platform"

// After planning, refined scope
await update_project({
  id: projectId,
  name: "E-Commerce Platform Redesign - Phase 1",
  summary: "Phase 1 focus: Product catalog and search. Deferred: Checkout and payment to Phase 2",
  tags: "e-commerce,phase-1,catalog,search,frontend,2025-q2"
});
```

### Workflow 4: Quarterly Status Update

```javascript
// Quarterly roadmap project update
await update_project({
  id: roadmapProjectId,
  summary: "Q2 Progress: Completed API v3 (8 endpoints). Database optimization in progress (60% complete). Microservices migration deferred to Q3",
  tags: "backend,2025-q2,roadmap,in-progress"
});
```

## Status Transition Best Practices

### Recommended Transitions

```
planning → in-development: When first feature/task starts
in-development → completed: When all features delivered
completed → archived: After retention period (e.g., 90 days)
```

### Avoid These Transitions

```
❌ planning → completed: Skipping development phase
❌ in-development → planning: Going backwards (use new project instead)
❌ completed → in-development: Reopening completed projects (create Phase 2 instead)
❌ archived → any: Archived should be final state
```

### Handling Scope Changes

If project needs major changes after completion:
```javascript
// DON'T reopen completed project
// ❌ update_project({ id: oldProject, status: "in-development" })

// DO create new phase/version
const phase2 = await create_project({
  name: "E-Commerce Platform Redesign - Phase 2",
  summary: "Phase 2: Checkout, payment integration, and admin dashboard",
  tags: "e-commerce,phase-2,checkout,payments,2025-q3"
});
```

## Error Handling

### Project Not Found
```json
{
  "success": false,
  "message": "Project not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No project exists with ID b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
  }
}
```

**Solution**: Verify project ID using `search_projects` or `get_overview`

### Empty Field Validation
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Project name cannot be empty if provided"
  }
}
```

**Solution**: Don't send empty strings; omit the field instead

### Invalid Status
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid status: active. Must be one of: planning, in-development, completed, archived"
  }
}
```

**Solution**: Use correct status values (planning, in-development, completed, archived)

### Concurrent Modification
```json
{
  "success": false,
  "message": "Resource is locked",
  "error": {
    "code": "RESOURCE_LOCKED",
    "details": "Project is currently being modified by another operation"
  }
}
```

**Solution**: Wait and retry; locking prevents data corruption

## Common Mistakes to Avoid

### ❌ Mistake 1: Updating When Status Change Sufficient
```json
{
  "id": "project-uuid",
  "name": "Same Name",
  "summary": "Same summary",
  "status": "completed",
  "tags": "same,tags"
}
```
**Problem**: Unnecessary token usage for unchanged fields.

### ✅ Solution: Only Send Changed Fields
```json
{
  "id": "project-uuid",
  "status": "completed"
}
```
**Better**: Use `set_status` tool for status-only updates.

### ❌ Mistake 2: Not Preserving Tags
```json
{
  "id": "project-uuid",
  "tags": "new-tag"
}
```
**Problem**: Replaces all existing tags with just "new-tag".

### ✅ Solution: Get Current Tags First
```javascript
const project = await get_project({ id: projectId });
const allTags = [...project.data.tags, "new-tag"].join(",");
await update_project({ id: projectId, tags: allTags });
```

### ❌ Mistake 3: Summary Too Long
```json
{
  "summary": "Very long summary exceeding 500 characters limit..."
}
```
**Problem**: Summary field limited to 500 characters.

### ✅ Solution: Keep Summary Brief
```json
{
  "summary": "Concise summary within 500 char limit"
}
```
Use sections for detailed information.

## Best Practices

1. **Partial Updates**: Only send fields that changed
2. **Use set_status**: For status-only updates (more efficient)
3. **Preserve Tags**: Get current tags before updating
4. **Batch Changes**: Combine related updates in single call
5. **Update Summary**: Keep summary current as project evolves
6. **Status Progression**: Follow recommended status transitions
7. **Avoid Reopening**: Create new projects/phases instead

## Performance Considerations

### Token Usage by Update Type

| Update Type | Approximate Tokens | Example |
|-------------|-------------------|---------|
| Status only | ~100-150 | Change to in-development |
| Single field | ~150-200 | Update summary |
| Multiple fields | ~200-300 | Name + summary + tags |
| Full update | ~250-350 | All fields changed |

### Optimization Strategies

1. **Use set_status**: 50% fewer tokens for status updates
2. **Batch Updates**: Combine related changes
3. **Skip Retrieval**: Update directly without getting first (when possible)
4. **Minimal Response**: Tool returns only essential fields

## Related Tools

- **set_status**: More efficient status-only updates
- **get_project**: Retrieve project details before/after update
- **create_project**: Create new projects
- **delete_project**: Remove projects
- **update_section_text**: Update project content sections
- **add_section**: Add new content sections

## See Also

- Project Management Patterns: `task-orchestrator://guidelines/project-management`
- Status Lifecycle: `task-orchestrator://guidelines/status-management`
- Concurrency Control: `task-orchestrator://guidelines/locking`
