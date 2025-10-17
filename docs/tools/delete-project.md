# delete_project Tool - Detailed Documentation

## Overview

Deletes a project by ID with options for cascade deletion of associated features and tasks. Includes safety mechanisms to prevent accidental data loss.

**Resource**: `task-orchestrator://docs/tools/delete-project`

## Key Concepts

### Cascade Deletion
Projects can contain features and tasks. Cascade options control what happens to them:
- **No cascade**: Prevents deletion if features/tasks exist (default, safest)
- **Cascade enabled**: Deletes project AND all associated features and tasks
- **Force without cascade**: Deletes project, orphans features/tasks

### Soft vs Hard Delete
- **Soft delete** (default): Mark as deleted but retain in database
  - Currently implemented as hard delete (soft delete planned for future)
- **Hard delete**: Permanently remove from database

### Safety Mechanisms
Multiple safety checks prevent accidental deletion:
1. **Default protection**: Fails if features/tasks exist
2. **Force flag**: Explicit confirmation required
3. **Cascade flag**: Explicit permission for cascade
4. **Locking**: Prevents concurrent modification during deletion

## Parameter Reference

### Required Parameters
- **id** (UUID): Project identifier

### Optional Parameters
- **cascade** (boolean, default: false): Delete associated features and tasks
- **force** (boolean, default: false): Delete even with active features/tasks
- **hardDelete** (boolean, default: false): Permanently remove (vs soft delete)

## Safety Matrix

| Has Features/Tasks | cascade | force | Result |
|-------------------|---------|-------|--------|
| No | false | false | ✅ Delete succeeds |
| No | true | false | ✅ Delete succeeds |
| Yes | false | false | ❌ Error: has dependencies |
| Yes | true | false | ✅ Cascade delete all |
| Yes | false | true | ✅ Delete project only (orphans) |
| Yes | true | true | ✅ Cascade delete all |

## Common Usage Patterns

### Pattern 1: Delete Empty Project (Safe)
Delete a project with no features or tasks.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17"
}
```

**When to use**:
- Project created by mistake
- Empty planning project
- Abandoned early-stage project

**What happens**:
- ✅ Deletes project if empty
- ❌ Fails if features/tasks exist
- Returns confirmation

**Safety level**: 🟢 High - Cannot accidentally delete work

### Pattern 2: Cascade Delete Project and Contents
Delete project and all associated features and tasks.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "cascade": true
}
```

**When to use**:
- Canceling entire project
- Removing obsolete project
- Cleaning up test/demo projects

**What happens**:
- Deletes all tasks in features
- Deletes all direct tasks
- Deletes all features
- Deletes project
- Returns counts of deleted items

**Safety level**: 🟡 Medium - Explicit cascade required

**IMPORTANT**: This is permanent. Deleted features and tasks cannot be recovered.

### Pattern 3: Force Delete (Orphan Contents)
Delete project but leave features/tasks orphaned.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "force": true
}
```

**When to use**:
- Removing project structure but keeping work
- Reorganizing into different projects
- Project was organizational only

**What happens**:
- Deletes project
- Features become project-less (orphaned)
- Direct tasks become project-less (orphaned)

**Safety level**: 🟡 Medium - Creates orphaned entities

**Note**: Orphaned features/tasks remain accessible but lose project association.

### Pattern 4: Hard Delete with Cascade
Permanently remove project and all contents.

```json
{
  "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
  "cascade": true,
  "hardDelete": true
}
```

**When to use**:
- Permanent cleanup of test data
- Removing sensitive/confidential projects
- Database cleanup operations

**What happens**:
- Permanently deletes all tasks
- Permanently deletes all features
- Permanently deletes project
- No recovery possible

**Safety level**: 🔴 Low - Permanent and irreversible

**WARNING**: This is irreversible. Use with extreme caution.

## Response Structure

### Success Response (Simple Delete)
```json
{
  "success": true,
  "message": "Project deleted successfully",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "deleteType": "soft",
    "cascaded": false,
    "featuresAffected": 0,
    "tasksAffected": 0
  }
}
```

### Success Response (Cascade Delete)
```json
{
  "success": true,
  "message": "Project deleted successfully with 3 associated features and 12 tasks",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "deleteType": "soft",
    "cascaded": true,
    "featuresAffected": 3,
    "tasksAffected": 12
  }
}
```

**Key fields**:
- `deleteType`: "soft" or "hard" (currently always "hard" until soft delete implemented)
- `cascaded`: Whether cascade deletion was performed
- `featuresAffected`: Number of features deleted
- `tasksAffected`: Number of tasks deleted (includes feature tasks + direct tasks)

## Common Workflows

### Workflow 1: Safe Project Deletion with Verification

```javascript
// Step 1: Check what would be deleted
const project = await get_project({
  id: projectId,
  includeFeatures: true,
  includeTasks: true
});

console.log(`Project: ${project.data.name}`);
console.log(`Features: ${project.data.features?.total || 0}`);
console.log(`Direct Tasks: ${project.data.tasks?.total || 0}`);

// Step 2: Decide on deletion strategy
if (project.data.features?.total === 0 && project.data.tasks?.total === 0) {
  // Safe to delete without cascade
  await delete_project({ id: projectId });
} else {
  // Ask user or use cascade
  console.log("Project has contents. Use cascade=true to delete all.");
}
```

### Workflow 2: Complete Project Cleanup

```javascript
// Delete entire project hierarchy
const result = await delete_project({
  id: projectId,
  cascade: true
});

console.log(`Deleted project with:`);
console.log(`- ${result.data.featuresAffected} features`);
console.log(`- ${result.data.tasksAffected} tasks`);
```

### Workflow 3: Migrate Features to New Project

```javascript
// Step 1: Get features from old project
const oldProject = await get_project({
  id: oldProjectId,
  includeFeatures: true
});

// Step 2: Create new project
const newProject = await create_project({
  name: "Reorganized Project",
  summary: "Reorganized from previous project structure",
  tags: "reorganized,2025-q2"
});

// Step 3: Move features to new project
for (const feature of oldProject.data.features.items) {
  await update_feature({
    id: feature.id,
    projectId: newProject.data.id
  });
}

// Step 4: Delete old project (now empty)
await delete_project({
  id: oldProjectId
});
```

### Workflow 4: Archive Instead of Delete

Better practice for completed projects:

```javascript
// DON'T delete completed projects
// ❌ await delete_project({ id: projectId, cascade: true });

// DO archive them instead
await update_project({
  id: projectId,
  status: "archived"
});
```

**Why archiving is better**:
- Preserves historical record
- Maintains project knowledge
- Enables metrics and reporting
- Allows future reference
- Reversible decision

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

**Causes**:
- Wrong project ID
- Project already deleted
- Typo in UUID

**Solution**: Verify project ID using `search_projects` or `get_overview`

### Project Has Dependencies
```json
{
  "success": false,
  "message": "Cannot delete project with associated features or tasks",
  "error": {
    "code": "DEPENDENCY_ERROR",
    "details": "Project with ID ... has 3 associated features and 2 directly associated tasks. Use 'force=true' to delete anyway, or 'cascade=true' to delete associated entities as well."
  }
}
```

**Causes**:
- Project contains features
- Project contains direct tasks
- No cascade or force flag provided

**Solutions**:
1. Use `cascade=true` to delete everything
2. Use `force=true` to delete project only (orphan contents)
3. Manually delete/move features first
4. Archive instead of deleting

### Invalid UUID
```json
{
  "success": false,
  "message": "Validation error",
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Invalid id format. Must be a valid UUID"
  }
}
```

**Solution**: Ensure ID is valid UUID format

## Common Mistakes to Avoid

### ❌ Mistake 1: Deleting Instead of Archiving
```javascript
// Deleting completed project
await delete_project({
  id: completedProjectId,
  cascade: true
});
```
**Problem**: Loses historical data, metrics, and knowledge.

### ✅ Solution: Archive Completed Projects
```javascript
await update_project({
  id: completedProjectId,
  status: "archived"
});
```

### ❌ Mistake 2: Not Checking Contents First
```javascript
// Blind deletion
await delete_project({
  id: projectId,
  cascade: true
});
```
**Problem**: Might delete more than intended.

### ✅ Solution: Check Before Deleting
```javascript
const project = await get_project({
  id: projectId,
  includeFeatures: true,
  includeTasks: true
});

console.log(`Will delete: ${project.data.features.total} features, ${project.data.tasks.total} tasks`);
// Then decide
```

### ❌ Mistake 3: Using Force Without Understanding
```javascript
await delete_project({
  id: projectId,
  force: true
});
```
**Problem**: Creates orphaned features and tasks.

### ✅ Solution: Use Cascade or Migrate First
```javascript
// Either cascade delete
await delete_project({
  id: projectId,
  cascade: true
});

// Or migrate to new project first
// (see Workflow 3 above)
```

## Best Practices

1. **Archive Instead of Delete**: For completed projects, use status="archived"
2. **Verify Before Deletion**: Check contents with get_project first
3. **Use Cascade Carefully**: Understand what will be deleted
4. **Prefer Migration**: Move features to new project rather than deleting
5. **Document Deletions**: Log why and what was deleted
6. **Test Projects Only**: Reserve deletion for test/demo data
7. **Backup Important Data**: Export to markdown before deletion if needed

## When to Delete vs Archive

### Delete When:
- ✅ Test/demo project
- ✅ Created by mistake
- ✅ Duplicate project
- ✅ Empty planning project abandoned early
- ✅ Outdated test data

### Archive When:
- ✅ Completed project
- ✅ Canceled but has historical value
- ✅ Successfully delivered project
- ✅ Project with learnings to reference
- ✅ Need to maintain metrics

## Alternative Actions

Before deleting, consider:

1. **Archive**: `update_project({ id, status: "archived" })`
2. **Export**: `project_to_markdown({ id })` then save
3. **Reorganize**: Move features to different project
4. **Rename**: Update project name/scope instead

## Related Tools

- **update_project**: Archive instead of deleting
- **project_to_markdown**: Export before deletion
- **get_project**: Check contents before deleting
- **search_projects**: Find projects to clean up
- **delete_feature**: Delete individual features
- **delete_task**: Delete individual tasks

## See Also

- Project Lifecycle: `task-orchestrator://guidelines/project-lifecycle`
- Data Management: `task-orchestrator://guidelines/data-management`
- Archival Strategy: `task-orchestrator://guidelines/archival`
