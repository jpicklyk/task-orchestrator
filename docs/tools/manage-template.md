# manage_template Tool - Detailed Documentation

## Overview

The `manage_template` tool provides unified write operations for template management. It consolidates multiple former individual tools into a single, efficient interface with six operations: `create`, `update`, `delete`, `enable`, `disable`, and `addSection`.

**Key Feature (v2.0+):** The `manage_template` tool handles all template write operations with consistent validation, protection mechanisms for built-in templates, template lifecycle management, and section building capabilities.

**Resource**: `task-orchestrator://docs/tools/manage-template`

## Key Concepts

### Template Lifecycle

Templates progress through several states during their lifecycle:

1. **Creation** - New template created with metadata (name, description, target entity type)
2. **Configuration** - Sections added to define structure and content samples
3. **Enablement** - Template enabled/disabled to control availability for use
4. **Application** - Template applied to create new entities (tasks/features)
5. **Deletion** - Template removed (with protection for built-in templates)

### Built-in Template Protection

Task Orchestrator includes 9 built-in templates (bug investigation, feature planning, task breakdown, etc.). These templates are:

- **Protected**: Cannot be modified (isProtected = true)
- **Built-in**: Marked with isBuiltIn = true
- **Force-deletable**: Can only be deleted with force=true
- **Disable-friendly**: Can be disabled instead of deleted

**Best Practice**: Disable built-in templates instead of deleting them. Deletion should only occur with explicit force=true and understanding of the implications.

### Protection Mechanisms

| Mechanism | Protected | Built-in | Effect |
|-----------|-----------|----------|--------|
| Update | Yes | Yes/No | Cannot modify (returns error) |
| Delete | No | Yes | Can only delete with force=true |
| Enable/Disable | No | Yes/No | Always allowed |

### Six Core Operations

1. **create** - Create new template with name, description, target entity type, and flags
2. **update** - Modify template metadata (name, description, target type, tags, enabled state)
3. **delete** - Remove template (with protection checks for built-in templates)
4. **enable** - Activate template for use
5. **disable** - Deactivate template (same as isEnabled=false in update)
6. **addSection** - Add section definition to template for structure

## Parameter Reference

### Common Parameters (All Operations)

| Parameter       | Type | Required | Description                                      |
|-----------------|------|----------|--------------------------------------------------|
| `operation`     | enum | **Yes**  | Operation: create, update, delete, enable, disable, addSection |

### Operation-Specific Parameters

| Parameter      | Type    | Operations             | Description                                       |
|----------------|---------|------------------------|---------------------------------------------------|
| `id`           | UUID    | update, delete, enable, disable, addSection | Template ID |
| `name`         | string  | create, update         | Template name                                     |
| `description`  | string  | create, update         | Detailed template description                     |
| `targetEntityType` | enum | create, update      | TASK or FEATURE (what entity type this template applies to) |
| `isBuiltIn`    | boolean | create                 | Built-in template flag (default: false)           |
| `isProtected`  | boolean | create                 | Protected from modification (default: false)      |
| `isEnabled`    | boolean | create, update         | Enabled for use (default: true)                  |
| `createdBy`    | string  | create                 | Creator identifier (optional)                     |
| `tags`         | string  | create, update         | Comma-separated tags                              |
| `force`        | boolean | delete                 | Force delete (overrides built-in protection)     |
| `title`        | string  | addSection             | Section title                                     |
| `usageDescription` | string | addSection          | Description of section usage                     |
| `contentSample` | string | addSection             | Sample content for the section                   |
| `contentFormat` | enum   | addSection             | MARKDOWN, PLAIN_TEXT, JSON, CODE (default: MARKDOWN) |
| `ordinal`      | integer | addSection             | Section display order (0-based, required)        |
| `isRequired`   | boolean | addSection             | Section required in instantiated templates       |
| `tags`         | string  | addSection             | Comma-separated tags for the section             |

### Target Entity Types

| Type      | Description |
|-----------|-------------|
| **TASK**  | Template for creating individual tasks |
| **FEATURE** | Template for creating features |

### Content Format Options

- **MARKDOWN** - Markdown formatted content (default)
- **PLAIN_TEXT** - Plain text without formatting
- **JSON** - JSON structured data
- **CODE** - Source code with syntax support

---

## Quick Start

### Basic Create Pattern

```json
{
  "operation": "create",
  "name": "Bug Investigation Template",
  "description": "Standard template for investigating and documenting bugs",
  "targetEntityType": "TASK",
  "tags": "bug,investigation,template"
}
```

**Response**:

```json
{
  "success": true,
  "message": "Template created successfully",
  "data": {
    "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
    "name": "Bug Investigation Template",
    "description": "Standard template for investigating and documenting bugs",
    "targetEntityType": "TASK",
    "isBuiltIn": false,
    "isProtected": false,
    "isEnabled": true,
    "createdBy": null,
    "tags": ["bug", "investigation", "template"],
    "createdAt": "2025-10-24T19:30:00Z",
    "modifiedAt": "2025-10-24T19:30:00Z"
  }
}
```

### Update Template Metadata

```json
{
  "operation": "update",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "name": "Bug Investigation and Analysis",
  "isEnabled": false
}
```

### Delete Custom Template

```json
{
  "operation": "delete",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

### Force Delete Built-in Template

```json
{
  "operation": "delete",
  "id": "550e8400-e29b-41d4-a716-446655440001",
  "force": true
}
```

### Add Section to Template

```json
{
  "operation": "addSection",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "title": "Reproduction Steps",
  "usageDescription": "Steps to reproduce the bug",
  "contentSample": "1. Open the application\n2. Navigate to [Feature]\n3. Perform [Action]\n4. Observe [Result]",
  "contentFormat": "MARKDOWN",
  "ordinal": 0,
  "isRequired": true,
  "tags": "bug,reproduction"
}
```

### Enable Template

```json
{
  "operation": "enable",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

### Disable Template

```json
{
  "operation": "disable",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
}
```

---

## Operation Details

### create

Creates a new template with the specified properties. All new templates default to enabled (isEnabled=true) and unprotected (isProtected=false).

**Required Parameters:**
- `operation`: "create"
- `name`: Template name (must be non-empty)
- `description`: Template description (must be non-empty)
- `targetEntityType`: TASK or FEATURE

**Optional Parameters:**
- `isBuiltIn`: Mark as built-in (default: false)
- `isProtected`: Protect from modification (default: false)
- `isEnabled`: Enable for use (default: true)
- `createdBy`: Creator identifier
- `tags`: Comma-separated tags

**Validation:**
- Name and description must be non-empty
- targetEntityType must be valid (TASK or FEATURE)
- Tags are parsed and trimmed

**Response**: Returns created template object with generated UUID and timestamps

**Example Use Cases:**
- Create organization-specific templates for common task types
- Create domain-specific feature templates
- Create template variations for different workflows

### update

Modifies an existing template's metadata. Protected templates cannot be updated.

**Required Parameters:**
- `operation`: "update"
- `id`: Template ID
- At least one update field: name, description, targetEntityType, isEnabled, or tags

**Optional Parameters:**
- `name`: New template name
- `description`: New template description
- `targetEntityType`: New target entity type
- `isEnabled`: Enable/disable template
- `tags`: New comma-separated tags

**Protection Rules:**
- Protected templates (isProtected=true) cannot be updated - returns error
- Attempt to update built-in template that isn't protected returns error only if it's protected

**Validation:**
- At least one update field must be provided
- Name and description, if provided, must be non-empty
- targetEntityType, if provided, must be valid

**Response**: Returns updated template object

**Common Patterns:**
- Enable/disable templates without deletion
- Update tags to categorize templates
- Rename templates for clarity
- Change target entity type (rare, for template repurposing)

### delete

Removes a template. Built-in templates require force=true to delete.

**Required Parameters:**
- `operation`: "delete"
- `id`: Template ID

**Optional Parameters:**
- `force`: Force delete (overrides built-in protection, default: false)

**Protection Rules:**
- Built-in templates (isBuiltIn=true) cannot be deleted without force=true
- When force=false and template is built-in, returns descriptive error message
- Custom templates can be deleted without force

**Error Scenarios:**
- Template not found → Returns NOT_FOUND error
- Built-in template without force → Returns VALIDATION_ERROR with explanation
- Database failure → Returns DATABASE_ERROR

**Response**: Returns confirmation with template name and deleted flag

**Best Practices:**
- Use disable instead of delete for important templates
- Prefer force=false (fails safely if template is built-in)
- Only use force=true with explicit understanding of implications
- Built-in templates should only be deleted during system maintenance

### enable

Activates a template for use by setting isEnabled=true.

**Required Parameters:**
- `operation`: "enable"
- `id`: Template ID

**No Optional Parameters**

**Effects:**
- Sets isEnabled=true
- Updates modifiedAt timestamp
- Does not affect template content or sections

**Response**: Returns updated template object with isEnabled=true

**Use Cases:**
- Re-enable previously disabled templates
- Activate new templates after configuration

### disable

Deactivates a template by setting isEnabled=false. This prevents the template from being applied to new entities.

**Required Parameters:**
- `operation`: "disable"
- `id`: Template ID

**No Optional Parameters**

**Effects:**
- Sets isEnabled=false
- Updates modifiedAt timestamp
- Existing entities using this template are not affected
- New entities cannot be created with disabled template

**Response**: Returns updated template object with isEnabled=false

**Benefits Over Delete:**
- Reversible (can re-enable later)
- Preserves template for historical reference
- No cascade effects on existing entities
- Works with built-in templates (no force needed)

### addSection

Adds a section definition to a template. Sections define the structure and content samples for instantiated templates.

**Required Parameters:**
- `operation`: "addSection"
- `id`: Template ID (must exist)
- `title`: Section title
- `usageDescription`: Description of what this section is for
- `contentSample`: Example content for the section
- `ordinal`: Display order (0-based non-negative integer)

**Optional Parameters:**
- `contentFormat`: Content format (default: MARKDOWN)
- `isRequired`: Whether section is required (default: false)
- `tags`: Comma-separated tags for the section

**Validation:**
- Template must exist (returns NOT_FOUND error if not)
- Title and usageDescription must be non-empty
- contentSample must be non-empty
- ordinal must be >= 0
- contentFormat must be valid if provided

**Response**: Returns created template section object

**Section Properties:**
- Each section has unique ID and ordinal within template
- Sections are returned in ordinal order
- Required sections must be present when instantiating template
- Sections can have tags for categorization

**Example: Building Complete Template**

```json
[
  {
    "operation": "addSection",
    "id": "template-id",
    "title": "Problem Statement",
    "usageDescription": "Clear description of what needs to be fixed",
    "contentSample": "Users are unable to...",
    "ordinal": 0,
    "isRequired": true
  },
  {
    "operation": "addSection",
    "id": "template-id",
    "title": "Root Cause Analysis",
    "usageDescription": "Analysis of why the problem occurs",
    "contentSample": "The issue occurs because...",
    "ordinal": 1,
    "isRequired": false
  },
  {
    "operation": "addSection",
    "id": "template-id",
    "title": "Resolution Steps",
    "usageDescription": "Steps to fix the problem",
    "contentSample": "1. Update...\n2. Deploy...",
    "ordinal": 2,
    "isRequired": true
  }
]
```

---

## Advanced Usage

### Built-in Template Protection Mechanisms

Task Orchestrator protects built-in templates through multiple mechanisms:

**1. Protected Flag (isProtected)**
```kotlin
if (template.isProtected) {
    return errorResponse("Cannot update protected template")
}
```

**2. Built-in Check in Delete**
```kotlin
if (template.isBuiltIn && !force) {
    return errorResponse("Built-in templates cannot be deleted. Use force=true to override.")
}
```

**3. Safe Disable Pattern**
```json
{
  "operation": "disable",
  "id": "builtin-template-id"
}
// This works! Disable is allowed for built-in templates
```

### Template Section Management

Sections are the core of template structure. Each template can have multiple sections that define what content users should provide.

**Section Ordering**:
- Sections are displayed in ordinal order (0 = first, 1 = second, etc.)
- When instantiating a template, sections maintain their defined order
- Reordering sections requires redefining them with new ordinals

**Required vs. Optional Sections**:
- Required sections (isRequired=true) must be filled when instantiating template
- Optional sections can be skipped or left empty
- Mark critical sections (e.g., "Reproduction Steps" for bugs) as required

**Content Samples**:
- Samples serve as templates for users
- Good samples include:
  - Example structure and formatting
  - Placeholder text showing expected content
  - Tips for writing effective content
- Samples help users understand what content is expected

### Creator Tracking

When creating templates, you can optionally specify a creator identifier:

```json
{
  "operation": "create",
  "name": "Custom Bug Template",
  "description": "Company-specific bug investigation template",
  "targetEntityType": "TASK",
  "createdBy": "team-lead@company.com"
}
```

This information is:
- Stored in the template metadata
- Returned in responses
- Useful for auditing and template ownership
- Visible to other users when listing templates

### Force Delete Behavior

Force delete is specifically designed for built-in template management:

**Normal Delete (force=false)**:
```json
{
  "operation": "delete",
  "id": "custom-template-id"
}
// Success: Custom template deleted

{
  "operation": "delete",
  "id": "builtin-template-id"
}
// Failure: "Built-in templates cannot be deleted"
```

**Force Delete (force=true)**:
```json
{
  "operation": "delete",
  "id": "builtin-template-id",
  "force": true
}
// Success: Built-in template deleted (with warning implications)
```

**When to Use force=true**:
- System maintenance/cleanup
- Removing corrupted built-in templates
- Decommissioning entire template systems
- With explicit authorization

**When NOT to Use force=true**:
- Regular template management
- Testing
- Uncertain scenarios
- When disable would suffice

### Template Tagging Strategy

Tags help organize templates by purpose, domain, or workflow:

```json
{
  "operation": "create",
  "name": "Bug Investigation Template",
  "targetEntityType": "TASK",
  "tags": "bug,defect,triage,critical"
}
```

**Tag Categories**:
- **Workflow**: bug, feature, documentation, refactor
- **Domain**: backend, frontend, database, devops
- **Priority**: critical, high-priority, low-priority
- **Organization**: company-specific, team-specific
- **Status**: deprecated, experimental, mature

**Query Integration**:
- Use list_templates with tags filter to find templates by category
- Helps users discover appropriate templates
- Enable/disable templates by tag in bulk (future enhancement)

---

## Error Handling

### Common Error Scenarios

| Error | Cause | Solution |
|-------|-------|----------|
| VALIDATION_ERROR (Missing required parameter) | operation, name, description, or targetEntityType not provided | Verify all required parameters present |
| VALIDATION_ERROR (Invalid operation) | operation value not in list | Use: create, update, delete, enable, disable, addSection |
| VALIDATION_ERROR (Invalid UUID format) | id parameter malformed | Verify id is valid UUID format |
| VALIDATION_ERROR (Protected template) | Attempting to update protected template | Protected templates cannot be modified |
| VALIDATION_ERROR (Built-in template) | Attempting to delete built-in template without force | Use force=true or disable instead |
| RESOURCE_NOT_FOUND | Template with given id doesn't exist | Verify template id exists via list_templates |
| DATABASE_ERROR | Database connection or query failure | Retry operation; check database health |

### Error Response Format

All errors follow consistent format:

```json
{
  "success": false,
  "message": "Brief error description",
  "code": "ERROR_CODE",
  "details": "Additional context if available"
}
```

### Handling Update Failures

When updating a template fails:

```json
{
  "success": false,
  "message": "Cannot update protected template",
  "code": "VALIDATION_ERROR",
  "details": "Template with ID ... is protected and cannot be updated"
}
```

**Resolution Options**:
1. If protected: Use disable/enable instead, or check if it's a built-in
2. If not found: Verify template id via list_templates first
3. If validation error: Check parameter values and types

### Handling Delete Failures

Built-in template deletion specifically returns:

```json
{
  "success": false,
  "message": "Built-in templates cannot be deleted. Use 'disable_template' instead to make the template unavailable for use.",
  "code": "VALIDATION_ERROR",
  "details": "Template 'Bug Investigation' (id: ...) is a built-in template and cannot be deleted."
}
```

**Resolution Options**:
1. Use disable operation instead
2. Use force=true if deletion is truly needed
3. Check is_built_in flag via list_templates before deletion

---

## Integration Patterns

### Complete Template Lifecycle Example

```json
[
  {
    "operation": "create",
    "name": "Custom Deployment Template",
    "description": "Organization-specific deployment checklist",
    "targetEntityType": "TASK",
    "tags": "deployment,checklist,devops"
  },
  {
    "operation": "addSection",
    "id": "newly-created-template-id",
    "title": "Pre-Deployment Checklist",
    "usageDescription": "Items to verify before deployment",
    "contentSample": "- Database migrations tested\n- Feature flags configured\n- Load balancers updated",
    "ordinal": 0,
    "isRequired": true,
    "tags": "checklist"
  },
  {
    "operation": "addSection",
    "id": "newly-created-template-id",
    "title": "Post-Deployment Verification",
    "usageDescription": "Verification steps after deployment",
    "contentSample": "- Health checks passing\n- Metrics normal\n- User reports none",
    "ordinal": 1,
    "isRequired": true,
    "tags": "verification"
  },
  {
    "operation": "enable",
    "id": "newly-created-template-id"
  }
]
```

### With manage_container (apply_template)

After building a template, use it to create entities:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Deploy to Production",
  "featureId": "feature-id",
  "priority": "high",
  "templateIds": ["custom-deployment-template-id"]
}
```

When created with templateIds, the task will:
1. Include sections from the template
2. Have pre-populated content samples
3. Guide user through required sections
4. Maintain consistent structure

### With list_templates and query_templates

Discover and inspect templates:

```json
{
  "operation": "search",
  "containerType": "template",
  "query": "deployment",
  "tags": "devops"
}
```

This finds all templates matching the query and tagged with "devops".

### Enable/Disable Workflow

Control template availability without deletion:

```json
[
  {
    "operation": "disable",
    "id": "old-template-id"
  },
  {
    "operation": "enable",
    "id": "new-template-id"
  }
]
```

This safely transitions users to new templates without data loss.

---

## Use Cases

### 1. Creating Domain-Specific Templates

Organizations often need templates tailored to their specific workflows:

```json
{
  "operation": "create",
  "name": "API Integration Template",
  "description": "Template for implementing integrations with external APIs",
  "targetEntityType": "FEATURE",
  "tags": "integration,api,backend",
  "createdBy": "platform-team"
}
```

Then add sections:
- Authentication & Authorization
- Rate Limiting & Throttling
- Error Handling & Retries
- Documentation & Examples
- Testing Strategy

### 2. Enforcing Consistency

Ensure all features follow company standards:

```json
{
  "operation": "create",
  "name": "Security Review Checklist",
  "description": "Required security checks for all features",
  "targetEntityType": "FEATURE",
  "tags": "security,compliance",
  "isProtected": true,
  "isBuiltIn": true
}
```

Protect it to prevent accidental modifications.

### 3. Template Evolution

Update templates as processes improve:

```json
[
  {
    "operation": "disable",
    "id": "v1-template-id"
  },
  {
    "operation": "create",
    "name": "Deployment Template v2",
    "description": "Improved deployment checklist based on lessons learned",
    "targetEntityType": "TASK",
    "tags": "deployment,checklist,v2"
  },
  {
    "operation": "addSection",
    "id": "new-v2-template-id",
    "title": "New: Rollback Plan",
    "usageDescription": "Plan for quick rollback if needed",
    "contentSample": "1. Rollback steps...",
    "ordinal": 2,
    "isRequired": true
  }
]
```

Old template is disabled, new one is available.

### 4. Temporary Disabling

Temporarily disable templates during maintenance:

```json
{
  "operation": "disable",
  "id": "template-id"
}
```

Later, re-enable when ready:

```json
{
  "operation": "enable",
  "id": "template-id"
}
```

### 5. Cleaning Up Obsolete Templates

Remove templates that are no longer needed:

```json
{
  "operation": "delete",
  "id": "obsolete-template-id"
}
```

For built-in templates you created (marked as built-in for system consistency):

```json
{
  "operation": "delete",
  "id": "builtin-template-id",
  "force": true
}
```

---

## Best Practices

### 1. Template Naming

Use clear, descriptive names:

```json
GOOD:   "Kubernetes Deployment Checklist"
AVOID:  "Template 1"

GOOD:   "User Authentication Feature"
AVOID:  "Feature"
```

### 2. Section Design

Create well-structured sections:

```json
GOOD: {
  "title": "Security Considerations",
  "usageDescription": "Security implications and mitigations",
  "contentSample": "- Authentication method: [specify]\n- Rate limiting: [specify]\n- Data encryption: [specify]",
  "isRequired": true
}

AVOID: {
  "title": "Notes",
  "usageDescription": "Any notes",
  "contentSample": "Enter notes here"
}
```

### 3. Required Sections

Mark critical sections as required:

```json
{
  "operation": "addSection",
  "id": "template-id",
  "title": "Acceptance Criteria",
  "usageDescription": "Clear success criteria for completion",
  "contentSample": "- Criterion 1\n- Criterion 2",
  "ordinal": 0,
  "isRequired": true
}
```

### 4. Content Samples

Provide helpful examples:

```json
GOOD: {
  "contentSample": "## Problem\nUsers cannot reset passwords because...\n\n## Root Cause\nThe reset token expiration is set to...\n\n## Solution\nIncrease token TTL to..."
}

AVOID: {
  "contentSample": "Write here"
}
```

### 5. Tag Consistency

Use consistent tag naming:

```json
GOOD:  ["backend", "api", "critical"]
AVOID: ["Backend", "API", "High Priority"]
```

### 6. Protection Strategy

Only protect templates that should never change:

```json
GOOD: {
  "isBuiltIn": true,
  "isProtected": true
}
// For system-critical, unchanging templates

GOOD: {
  "isBuiltIn": false,
  "isProtected": false
}
// For user/team-created templates (allow updates)

AVOID: {
  "isBuiltIn": false,
  "isProtected": true
}
// Contradictory: why protect non-built-in?
```

### 7. Enable/Disable Over Delete

Prefer disabling for rollback capability:

```json
GOOD: {
  "operation": "disable",
  "id": "template-id"
}
// Can be re-enabled later

LESS GOOD: {
  "operation": "delete",
  "id": "template-id"
}
// Lost permanently
```

### 8. Creator Attribution

Track template creators:

```json
{
  "operation": "create",
  "name": "Template Name",
  "targetEntityType": "TASK",
  "createdBy": "user@company.com"
}
```

Helps with template ownership and governance.

### 9. Content Format Consistency

Match format to content type:

```json
MARKDOWN:   "## Title\n- Bullet\n**Bold**"
PLAIN_TEXT: "Simple unformatted text"
JSON:       "{\"key\": \"value\"}"
CODE:       "function example() { ... }"
```

### 10. Section Ordering

Number ordinals explicitly:

```json
[
  {"ordinal": 0, "title": "First Section"},
  {"ordinal": 1, "title": "Second Section"},
  {"ordinal": 2, "title": "Third Section"}
]
// NOT [1, 3, 5] - use sequential numbering
```

---

## Related Tools

### list_templates / query_templates

Discover and search templates:

```json
{
  "operation": "search",
  "containerType": "template",
  "query": "deployment",
  "tags": "devops",
  "limit": 10
}
```

Returns matching templates for inspection before applying.

### apply_template (manage_container)

Apply templates to create new entities:

```json
{
  "operation": "create",
  "containerType": "task",
  "title": "Production Deployment",
  "templateIds": ["template-id-1", "template-id-2"]
}
```

Created task includes sections from applied templates.

### get_template / query_container

Inspect specific template:

```json
{
  "operation": "get",
  "containerType": "template",
  "id": "template-id"
}
```

Returns complete template with all sections.

### manage_sections

Modify sections of templates (for template instantiation):

```json
{
  "operation": "add",
  "entityType": "TEMPLATE",
  "entityId": "template-id",
  "title": "New Section",
  "usageDescription": "Description",
  "content": "Content"
}
```

Works with templates like any other entity.

---

## References

### Related Documentation
- [Templates Guide](../templates.md) - Overview of template system
- [manage_sections Documentation](./manage-sections.md) - Section write operations
- [query_container Documentation](./query-container.md) - Template queries
- [apply_template Guide](./apply-template.md) - How to apply templates to entities
- [API Reference](../api-reference.md) - Complete tool listing

### Built-in Templates
Task Orchestrator includes 9 built-in templates:
1. Bug Investigation - For investigating and documenting bugs
2. Feature Planning - For planning new features
3. Task Breakdown - For decomposing features into tasks
4. Technical Documentation - For system documentation
5. Performance Analysis - For performance investigations
6. API Integration - For API implementation tasks
7. Database Migration - For database schema changes
8. Test Implementation - For test suite creation
9. Code Review Checklist - For code review processes

### Quick Command Reference

```bash
# Create template
operation=create, name, description, targetEntityType

# Update template
operation=update, id, (name|description|targetEntityType|isEnabled|tags)

# Add section
operation=addSection, id, title, usageDescription, contentSample, ordinal

# Enable/Disable
operation=enable, id
operation=disable, id

# Delete template
operation=delete, id, (force)
```
