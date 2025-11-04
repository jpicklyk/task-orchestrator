# rename_tag Tool - Detailed Documentation

## Overview

Renames a tag across all entities (tasks, features, projects, templates) in a single atomic operation. Essential for maintaining consistent tag taxonomy and fixing tag-related issues across your entire workspace.

**Resource**: `task-orchestrator://docs/tools/rename-tag`

## Key Concepts

### Bulk Tag Replacement
Unlike updating tags on individual entities, rename_tag performs workspace-wide updates:
- **Atomic operation**: All matching entities updated in single operation
- **Cross-entity**: Updates tasks, features, projects, and templates simultaneously
- **Case-insensitive matching**: Finds all case variations of old tag
- **Duplicate prevention**: Automatically handles cases where newTag already exists
- **Preserves order**: Tag position maintained in entity tag lists

### Use Cases for Tag Renaming
1. **Typo Correction**: "authentcation" → "authentication"
2. **Standardization**: "API" → "api" (enforce lowercase convention)
3. **Tag Consolidation**: Merge "rest-api" and "restapi" into "api"
4. **Convention Changes**: "bug-fix" → "bugfix" (remove hyphens)
5. **Taxonomy Refinement**: "backend-api" → "api" (simplify taxonomy)

### Dry Run Mode
Preview changes before committing:
- Set `dryRun: true` to see what would be updated
- No modifications made to database
- Returns same statistics as actual rename
- Essential for large-scale renames

## Parameter Reference

### Required Parameters
- **oldTag** (string): Tag to rename
  - Case-insensitive matching (finds "API", "api", "Api")
  - Must exist in at least one entity to have effect
  - Cannot be empty or whitespace

- **newTag** (string): New tag name
  - Exact case used (if you specify "api", result will be "api")
  - Cannot be same as oldTag (case-insensitive comparison)
  - Cannot be empty or whitespace

### Optional Parameters
- **entityTypes** (string): Comma-separated list of entity types to update
  - Valid values: TASK, FEATURE, PROJECT, TEMPLATE
  - Default: "TASK,FEATURE,PROJECT,TEMPLATE" (all types)
  - Example: "TASK,FEATURE" to update only tasks and features

- **dryRun** (boolean): Preview mode
  - Default: false (actual rename)
  - Set to true to preview without modifying data
  - Returns statistics showing what would be changed

## Usage Patterns

### Pattern 1: Full Workspace Rename
Rename tag across all entity types (most common use case).

```json
{
  "oldTag": "authentcation",
  "newTag": "authentication"
}
```

**When to use**:
- Fixing typos that propagated across workspace
- Standardizing tag capitalization
- Major taxonomy changes
- Consolidating tag variations

**Example response**:
```json
{
  "success": true,
  "message": "Successfully renamed tag in 47 entities",
  "data": {
    "oldTag": "authentcation",
    "newTag": "authentication",
    "totalUpdated": 47,
    "byEntityType": {
      "TASK": 32,
      "FEATURE": 8,
      "PROJECT": 5,
      "TEMPLATE": 2
    },
    "failedUpdates": 0,
    "dryRun": false
  }
}
```

### Pattern 2: Dry Run Preview
Preview impact before committing to change.

```json
{
  "oldTag": "API",
  "newTag": "api",
  "dryRun": true
}
```

**When to use**:
- Large-scale renames affecting many entities
- Uncertain about scope of change
- Want to verify entity counts before proceeding
- Testing rename logic

**Example workflow**:
```javascript
// Step 1: Check current usage
const usage = await get_tag_usage({ tag: "API" });
console.log(`Found ${usage.data.totalCount} entities`);

// Step 2: Preview rename
const preview = await rename_tag({
  oldTag: "API",
  newTag: "api",
  dryRun: true
});
console.log(`Would update ${preview.data.totalUpdated} entities`);

// Step 3: Execute if preview looks good
const result = await rename_tag({
  oldTag: "API",
  newTag: "api"
});
console.log(`Updated ${result.data.totalUpdated} entities`);
```

### Pattern 3: Selective Entity Type Rename
Rename tag only in specific entity types.

```json
{
  "oldTag": "v1-api",
  "newTag": "api",
  "entityTypes": "TASK,FEATURE"
}
```

**When to use**:
- Want to preserve tag in some entity types but change in others
- Different naming conventions for different entity types
- Gradual migration of tag taxonomy
- Testing rename on subset before full rollout

### Pattern 4: Tag Consolidation
Merge multiple tag variations into single standard tag.

```javascript
// Consolidate "rest-api", "restapi", "REST-API" into "api"
await rename_tag({ oldTag: "rest-api", newTag: "api" });
await rename_tag({ oldTag: "restapi", newTag: "api" });
await rename_tag({ oldTag: "REST-API", newTag: "api" });
```

**When to use**:
- Multiple tag variations exist for same concept
- Simplifying tag taxonomy
- Enforcing naming conventions

## Behavior Details

### Case-Insensitive Matching
The `oldTag` parameter uses case-insensitive matching:

```javascript
await rename_tag({
  oldTag: "API",  // Matches "api", "Api", "API", "ApI", etc.
  newTag: "api"
});
```

All case variations of "API" will be found and replaced with "api" (exact case preserved from newTag).

### Duplicate Prevention
If entity already has the `newTag`, the `oldTag` is simply removed:

**Example**:
- Entity has tags: `["backend", "API", "api"]`
- Rename "API" to "api"
- Result: `["backend", "api"]` (duplicate "api" prevented)

### Order Preservation
Tag position in tag list is preserved:

**Example**:
- Original tags: `["frontend", "API", "react"]`
- Rename "API" to "rest-api"
- Result: `["frontend", "rest-api", "react"]` (position 1 maintained)

### Partial Failures
The tool continues processing even if individual updates fail:
- Successful updates are committed
- Failed updates are counted and reported
- Operation completes and returns statistics
- Check `failedUpdates` count in response

## Common Workflows

### Workflow 1: Safe Rename with Preview
Standard workflow for important renames.

```javascript
// Step 1: Understand current usage
const usage = await get_tag_usage({ tag: "old-tag" });
console.log(`Currently used in ${usage.data.totalCount} entities:`);
Object.entries(usage.data.entities).forEach(([type, items]) => {
  console.log(`- ${type}: ${items.length}`);
});

// Step 2: Preview the rename
const preview = await rename_tag({
  oldTag: "old-tag",
  newTag: "new-tag",
  dryRun: true
});

if (preview.data.totalUpdated !== usage.data.totalCount) {
  console.warn("Warning: Update count doesn't match usage count!");
}

// Step 3: Execute the rename
const result = await rename_tag({
  oldTag: "old-tag",
  newTag: "new-tag"
});

if (result.data.failedUpdates > 0) {
  console.error(`${result.data.failedUpdates} updates failed!`);
} else {
  console.log(`Successfully renamed ${result.data.totalUpdated} entities`);
}

// Step 4: Verify the change
const verification = await get_tag_usage({ tag: "old-tag" });
if (verification.data.totalCount === 0) {
  console.log("Rename verified - old tag no longer in use");
}
```

### Workflow 2: Typo Correction
Quick fix for tag typos.

```javascript
// Discovered typo in tag
await rename_tag({
  oldTag: "authentcation",
  newTag: "authentication"
});

// Verify correction
const usage = await get_tag_usage({ tag: "authentication" });
console.log(`Tag now used in ${usage.data.totalCount} entities`);
```

### Workflow 3: Standardize Capitalization
Enforce lowercase tag convention.

```javascript
// Find tags that might need standardization
const tagsToStandardize = ["API", "REST", "JSON", "HTTP"];

for (const tag of tagsToStandardize) {
  const usage = await get_tag_usage({ tag });

  if (usage.data.totalCount > 0) {
    // Standardize to lowercase
    await rename_tag({
      oldTag: tag,
      newTag: tag.toLowerCase()
    });

    console.log(`Standardized ${tag} → ${tag.toLowerCase()}`);
  }
}
```

### Workflow 4: Gradual Tag Migration
Migrate tags gradually by entity type.

```javascript
// Phase 1: Migrate tasks only
const phase1 = await rename_tag({
  oldTag: "old-taxonomy",
  newTag: "new-taxonomy",
  entityTypes: "TASK"
});
console.log(`Phase 1: Migrated ${phase1.data.totalUpdated} tasks`);

// Test and validate...

// Phase 2: Migrate features
const phase2 = await rename_tag({
  oldTag: "old-taxonomy",
  newTag: "new-taxonomy",
  entityTypes: "FEATURE"
});
console.log(`Phase 2: Migrated ${phase2.data.totalUpdated} features`);

// Phase 3: Migrate projects and templates
const phase3 = await rename_tag({
  oldTag: "old-taxonomy",
  newTag: "new-taxonomy",
  entityTypes: "PROJECT,TEMPLATE"
});
console.log(`Phase 3: Migrated ${phase3.data.totalUpdated} projects/templates`);
```

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Successfully renamed tag in N entities",
  "data": {
    "oldTag": "original-tag",
    "newTag": "renamed-tag",
    "totalUpdated": 0,
    "byEntityType": {
      "TASK": 0,
      "FEATURE": 0,
      "PROJECT": 0,
      "TEMPLATE": 0
    },
    "failedUpdates": 0,
    "dryRun": false
  }
}
```

### Dry Run Response
```json
{
  "success": true,
  "message": "Would update N entities (dry run - no changes made)",
  "data": {
    "oldTag": "original-tag",
    "newTag": "renamed-tag",
    "totalUpdated": 0,
    "byEntityType": {
      "TASK": 0,
      "FEATURE": 0
    },
    "failedUpdates": 0,
    "dryRun": true
  }
}
```

### No Entities Found Response
```json
{
  "success": true,
  "message": "No entities found with tag 'nonexistent-tag'",
  "data": {
    "oldTag": "nonexistent-tag",
    "newTag": "new-tag",
    "totalUpdated": 0,
    "byEntityType": {},
    "failedUpdates": 0,
    "dryRun": false
  }
}
```

### Partial Failure Response
```json
{
  "success": true,
  "message": "Updated 45 entities, 3 failed",
  "data": {
    "oldTag": "old-tag",
    "newTag": "new-tag",
    "totalUpdated": 45,
    "byEntityType": {
      "TASK": 30,
      "FEATURE": 15
    },
    "failedUpdates": 3,
    "dryRun": false
  }
}
```

## Error Handling

### Validation Error - Missing Parameter
```json
{
  "success": false,
  "message": "Missing required parameter: oldTag",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Required parameter not provided

**Solution**: Provide both oldTag and newTag parameters

### Validation Error - Empty Tag
```json
{
  "success": false,
  "message": "oldTag cannot be empty",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Tag parameter is empty string or whitespace

**Solution**: Provide non-empty tag values

### Validation Error - Same Tag
```json
{
  "success": false,
  "message": "oldTag and newTag cannot be the same (case-insensitive)",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: oldTag and newTag are identical (case-insensitive)

**Solution**: Use different tag names for oldTag and newTag

### Validation Error - Invalid Entity Type
```json
{
  "success": false,
  "message": "Invalid entity types: INVALID. Valid types are: TASK, FEATURE, PROJECT, TEMPLATE",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Cause**: Invalid entity type in entityTypes parameter

**Solution**: Use only: TASK, FEATURE, PROJECT, TEMPLATE

## Performance Considerations

### Query Scope
The tool processes entities in batches:
- Tasks processed: Up to 10,000
- Features processed: Up to 10,000
- Projects processed: Up to 10,000
- Templates processed: All (typically < 100)

**Note**: Workspaces with more than 10,000 entities per type may have incomplete renames.

### Operation Time
Rename operations are I/O bound:
- Small workspace (< 100 entities): < 1 second
- Medium workspace (100-1000 entities): 1-5 seconds
- Large workspace (1000-10000 entities): 5-30 seconds

### Dry Run Performance
Dry runs are nearly as fast as actual renames:
- Reads same data (entities with matching tags)
- Skips only the database write operations
- Use for preview without performance penalty

## Best Practices

1. **Always Preview Large Renames**: Use `dryRun: true` for renames affecting 10+ entities
2. **Check Impact First**: Use `get_tag_usage` before renaming
3. **Standardize Early**: Fix tag inconsistencies before they spread
4. **Document Conventions**: Maintain tag naming conventions to prevent future issues
5. **Verify After Rename**: Use `get_tag_usage` to confirm old tag is gone
6. **Handle Failures Gracefully**: Check `failedUpdates` count and investigate if non-zero
7. **Use Lowercase**: Consider lowercase-only tag convention for consistency
8. **Batch Related Changes**: If renaming multiple tags, do them together

## Common Mistakes to Avoid

### ❌ Mistake 1: Renaming Without Impact Analysis
```javascript
// BAD: Rename without checking what's affected
await rename_tag({
  oldTag: "api",
  newTag: "rest-api"
});
```

**Problem**: Might affect dozens of entities unexpectedly

### ✅ Solution: Check Impact First
```javascript
// GOOD: Understand impact before renaming
const usage = await get_tag_usage({ tag: "api" });
console.log(`Will affect ${usage.data.totalCount} entities`);

await rename_tag({
  oldTag: "api",
  newTag: "rest-api"
});
```

### ❌ Mistake 2: Skipping Dry Run for Large Changes
```javascript
// BAD: Directly executing large rename
await rename_tag({
  oldTag: "backend",
  newTag: "api"  // Major taxonomy change
});
```

**Problem**: No preview of impact, might be unintended consequences

### ✅ Solution: Always Preview Large Changes
```javascript
// GOOD: Preview first
const preview = await rename_tag({
  oldTag: "backend",
  newTag: "api",
  dryRun: true
});

console.log(`Would update ${preview.data.totalUpdated} entities`);
// Review and confirm before proceeding

await rename_tag({
  oldTag: "backend",
  newTag: "api"
});
```

### ❌ Mistake 3: Ignoring Case Sensitivity
```javascript
// BAD: Trying to fix case by renaming to same tag
await rename_tag({
  oldTag: "API",
  newTag: "API"  // This will fail validation!
});
```

**Problem**: oldTag and newTag must be different (case-insensitive)

### ✅ Solution: Use Different Tag for Case Change
```javascript
// GOOD: Change to different case
await rename_tag({
  oldTag: "API",
  newTag: "api"  // Lowercase is different
});
```

### ❌ Mistake 4: Not Verifying After Rename
```javascript
// BAD: Rename and assume success
await rename_tag({
  oldTag: "old-tag",
  newTag: "new-tag"
});
// No verification that it worked
```

### ✅ Solution: Verify the Rename
```javascript
// GOOD: Verify after rename
const result = await rename_tag({
  oldTag: "old-tag",
  newTag: "new-tag"
});

if (result.data.failedUpdates > 0) {
  console.error(`${result.data.failedUpdates} failed!`);
}

// Verify old tag is gone
const oldTagCheck = await get_tag_usage({ tag: "old-tag" });
if (oldTagCheck.data.totalCount > 0) {
  console.warn("Old tag still in use - investigate!");
}
```

### ❌ Mistake 5: Sequential Renames for Consolidation
```javascript
// INEFFICIENT: Multiple separate operations
await rename_tag({ oldTag: "rest-api", newTag: "api" });
await rename_tag({ oldTag: "restapi", newTag: "api" });
await rename_tag({ oldTag: "REST-API", newTag: "api" });
```

**Problem**: Works but inefficient (3 separate database operations)

### ✅ Better: Batch Operations
```javascript
// BETTER: Parallel operations if independent
await Promise.all([
  rename_tag({ oldTag: "rest-api", newTag: "api" }),
  rename_tag({ oldTag: "restapi", newTag: "api" }),
  rename_tag({ oldTag: "REST-API", newTag: "api" })
]);

// Note: These are independent - renaming different old tags
// Still 3 operations, but execute in parallel
```

## Use Cases

### 1. Fix Typo Propagation
**Scenario**: Typo in tag has spread across 50 tasks

**Solution**:
```javascript
const result = await rename_tag({
  oldTag: "authentcation",
  newTag: "authentication"
});

console.log(`Fixed typo in ${result.data.totalUpdated} entities`);
```

### 2. Enforce Tag Conventions
**Scenario**: Some tags use uppercase, some lowercase

**Solution**:
```javascript
// Standardize to lowercase
const tagsToFix = ["API", "REST", "JSON"];

for (const tag of tagsToFix) {
  await rename_tag({
    oldTag: tag,
    newTag: tag.toLowerCase()
  });
}
```

### 3. Simplify Tag Taxonomy
**Scenario**: Too many specific tags, need to consolidate

**Solution**:
```javascript
// Consolidate specific tags into broader categories
await rename_tag({ oldTag: "bug-critical", newTag: "bug" });
await rename_tag({ oldTag: "bug-minor", newTag: "bug" });
await rename_tag({ oldTag: "bug-medium", newTag: "bug" });
// Use priority field for severity instead of tag
```

### 4. Migration to New Taxonomy
**Scenario**: Redesigning entire tag system

**Solution**:
```javascript
// Gradual migration with verification
const migrations = [
  { old: "backend-api", new: "api" },
  { old: "frontend-ui", new: "ui" },
  { old: "db-schema", new: "database" }
];

for (const { old, new: newTag } of migrations) {
  // Preview
  const preview = await rename_tag({
    oldTag: old,
    newTag,
    dryRun: true
  });

  console.log(`${old} → ${newTag}: ${preview.data.totalUpdated} entities`);

  // Execute
  await rename_tag({ oldTag: old, newTag });
}
```

## Related Tools

- **get_tag_usage**: Check tag usage before renaming (ALWAYS use this first)
- **search_tasks**: Find tasks by tags after renaming
- **search_features**: Find features by tags
- **search_projects**: Find projects by tags
- **update_task**: Update individual task tags manually
- **update_feature**: Update individual feature tags manually
- **update_project**: Update individual project tags manually

## Integration Examples

### Complete Tag Standardization Workflow
```javascript
// Step 1: Audit current tags
const audit = await get_tag_usage({ tag: "API" });
console.log(`Found ${audit.data.totalCount} uses of "API"`);

// Step 2: Preview standardization
const preview = await rename_tag({
  oldTag: "API",
  newTag: "api",
  dryRun: true
});
console.log(`Would update: ${preview.data.byEntityType}`);

// Step 3: Execute standardization
const result = await rename_tag({
  oldTag: "API",
  newTag: "api"
});

// Step 4: Verify completion
if (result.data.failedUpdates === 0) {
  const verify = await get_tag_usage({ tag: "API" });
  console.log(`Old tag remaining: ${verify.data.totalCount}`);
  console.log(`Standardization complete!`);
} else {
  console.error(`${result.data.failedUpdates} updates failed`);
}
```

## See Also

- Tag Management Guide: `task-orchestrator://guidelines/tag-management`
- Tag Conventions: `task-orchestrator://guidelines/tagging-conventions`
- Bulk Operations: `task-orchestrator://guidelines/bulk-operations`
