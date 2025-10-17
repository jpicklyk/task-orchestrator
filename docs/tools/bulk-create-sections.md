# bulk_create_sections Tool - Detailed Documentation

## Overview

Creates multiple sections in a single atomic operation. This is the **preferred tool** for adding 2+ sections at once, offering significant efficiency advantages over multiple `add_section` calls.

**Resource**: `task-orchestrator://docs/tools/bulk-create-sections`

## Key Concepts

### Why Use Bulk Creation

**bulk_create_sections** provides:

- ✅ **Atomic operation** - All sections succeed or all fail
- ✅ **Single database transaction** - Better performance
- ✅ **Single network round-trip** - Reduced latency
- ✅ **Maintains ordering** - Ordinal values preserved

**Efficiency gain**: 60-80% faster than individual `add_section` calls for 3+ sections

### When to Use This Tool

- **Initial section sets** - Creating 3+ sections for new task/feature
- **Template-based content** - Applying custom section templates
- **Bulk documentation** - Adding multiple documentation sections
- **Section migration** - Copying sections between entities

### When to Use add_section Instead

- **Single section** - Adding only 1 section
- **Large individual sections** - Section with extensive content (use add_section for better error isolation)

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| **sections** | array | Array of section objects to create |

### Section Object Fields

Each section object in the array requires:

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| **entityType** | enum | Yes | - | TASK, FEATURE, or PROJECT |
| **entityId** | UUID | Yes | - | Parent entity identifier |
| **title** | string | Yes | - | Section title (becomes ## H2 heading) |
| **usageDescription** | string | Yes | - | Purpose description for AI/users |
| **content** | string | Yes | - | Section content |
| **ordinal** | integer | Yes | - | Display order (0-based) |
| **contentFormat** | enum | No | MARKDOWN | MARKDOWN, PLAIN_TEXT, JSON, or CODE |
| **tags** | string | No | - | Comma-separated tags |

## Common Usage Patterns

### Pattern 1: Standard Task Sections

Create common section set for new task.

```json
{
  "sections": [
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Requirements",
      "usageDescription": "Core requirements for this task",
      "content": "- Must support OAuth 2.0\n- Must handle token refresh\n- Should support rate limiting",
      "contentFormat": "MARKDOWN",
      "ordinal": 0,
      "tags": "requirements,specifications"
    },
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Technical Approach",
      "usageDescription": "Implementation strategy and technical decisions",
      "content": "Use passport.js for OAuth integration with Redis for token storage.",
      "contentFormat": "MARKDOWN",
      "ordinal": 1,
      "tags": "implementation,technical"
    },
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Testing Strategy",
      "usageDescription": "Testing approach and coverage requirements",
      "content": "### Unit Tests\n- Token generation\n- Token validation\n\n### Integration Tests\n- OAuth flow\n- Token refresh",
      "contentFormat": "MARKDOWN",
      "ordinal": 2,
      "tags": "testing,qa"
    }
  ]
}
```

**When to use**: Task creation with standard documentation structure

### Pattern 2: Code Examples Set

Create multiple code example sections.

```json
{
  "sections": [
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "OAuth Configuration",
      "usageDescription": "Example OAuth provider configuration",
      "content": "passport.use(new GoogleStrategy({\n  clientID: process.env.GOOGLE_CLIENT_ID,\n  clientSecret: process.env.GOOGLE_CLIENT_SECRET\n}));",
      "contentFormat": "CODE",
      "ordinal": 0,
      "tags": "code-example,oauth,configuration"
    },
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Token Generation",
      "usageDescription": "Example token generation function",
      "content": "function generateToken(user) {\n  return jwt.sign({ id: user.id }, process.env.JWT_SECRET, { expiresIn: '24h' });\n}",
      "contentFormat": "CODE",
      "ordinal": 1,
      "tags": "code-example,jwt,token"
    }
  ]
}
```

**When to use**: Adding implementation examples to task

### Pattern 3: Mixed Format Sections

Create sections with different content formats.

```json
{
  "sections": [
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "API Specification",
      "usageDescription": "API endpoint definitions",
      "content": "{\"endpoints\": [{\"path\": \"/auth/login\", \"method\": \"POST\"}]}",
      "contentFormat": "JSON",
      "ordinal": 0,
      "tags": "api,specifications"
    },
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Implementation Notes",
      "usageDescription": "Implementation guidance",
      "content": "### Approach\nUse RESTful API design patterns.",
      "contentFormat": "MARKDOWN",
      "ordinal": 1,
      "tags": "implementation,documentation"
    }
  ]
}
```

**When to use**: Combining structured data with narrative documentation

### Pattern 4: Placeholder Template

Create template sections with placeholders.

```json
{
  "sections": [
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Current State",
      "usageDescription": "Description of current system state",
      "content": "[Describe current implementation]",
      "ordinal": 0,
      "tags": "context,template"
    },
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Proposed Changes",
      "usageDescription": "Detailed description of proposed changes",
      "content": "[Describe what needs to change]",
      "ordinal": 1,
      "tags": "requirements,template"
    },
    {
      "entityType": "TASK",
      "entityId": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Implementation Plan",
      "usageDescription": "Step-by-step implementation plan",
      "content": "1. [Step 1]\n2. [Step 2]\n3. [Step 3]",
      "ordinal": 2,
      "tags": "implementation,template"
    }
  ]
}
```

**When to use**: Creating custom templates for specific task types

## Response Structure

### Success Response (All Sections Created)

```json
{
  "success": true,
  "message": "3 sections created successfully",
  "data": {
    "items": [
      {
        "id": "uuid-1",
        "entityType": "task",
        "entityId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Requirements",
        "contentFormat": "markdown",
        "ordinal": 0,
        "createdAt": "2025-05-10T14:30:00Z"
      },
      {
        "id": "uuid-2",
        "entityType": "task",
        "entityId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Technical Approach",
        "contentFormat": "markdown",
        "ordinal": 1,
        "createdAt": "2025-05-10T14:30:00Z"
      },
      {
        "id": "uuid-3",
        "entityType": "task",
        "entityId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Testing Strategy",
        "contentFormat": "markdown",
        "ordinal": 2,
        "createdAt": "2025-05-10T14:30:00Z"
      }
    ],
    "count": 3,
    "failed": 0
  }
}
```

### Partial Success Response

```json
{
  "success": true,
  "message": "2 sections created successfully, 1 failed",
  "data": {
    "items": [ /* successfully created sections */ ],
    "count": 2,
    "failed": 1,
    "failures": [
      {
        "index": 1,
        "error": {
          "code": "VALIDATION_ERROR",
          "details": "Title is required"
        }
      }
    ]
  }
}
```

## Error Responses

### RESOURCE_NOT_FOUND (404)
Entity doesn't exist:
```json
{
  "success": false,
  "message": "Failed to create any sections",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "Entity not found: TASK with ID ..."
  }
}
```

### VALIDATION_ERROR (400)
Invalid section data:
```json
{
  "success": false,
  "message": "Section at index 0 is missing required field: title",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

## Best Practices

### 1. Use for Multiple Sections

```javascript
// ✅ Efficient: Bulk create for 3+ sections
await bulk_create_sections({
  sections: [section1, section2, section3]
});

// ❌ Inefficient: Multiple individual calls
await add_section(section1);
await add_section(section2);
await add_section(section3);
```

### 2. Ensure All Sections Target Same Entity

```javascript
// ✅ Correct: All sections for same entity
await bulk_create_sections({
  sections: [
    { entityType: "TASK", entityId: taskId, title: "Req", /* ... */ },
    { entityType: "TASK", entityId: taskId, title: "Tech", /* ... */ },
    { entityType: "TASK", entityId: taskId, title: "Test", /* ... */ }
  ]
});

// While this works, it's more efficient to group by entity
```

### 3. Use Sequential Ordinals

```javascript
// ✅ Good: Sequential ordinals (0, 1, 2)
const sections = [
  { ordinal: 0, title: "Requirements", /* ... */ },
  { ordinal: 1, title: "Technical Approach", /* ... */ },
  { ordinal: 2, title: "Testing", /* ... */ }
];

// Also valid: Gapped ordinals (0, 10, 20) for future insertions
```

### 4. Don't Duplicate Title in Content

```javascript
// ❌ Wrong: Duplicate heading
{
  "title": "Requirements",
  "content": "## Requirements\n\n- Must support OAuth..."
}

// ✅ Correct: Title provides heading
{
  "title": "Requirements",
  "content": "- Must support OAuth 2.0\n- Must handle tokens..."
}
```

### 5. Validate Before Bulk Creating

```javascript
// Verify entity exists
const task = await get_task({ id: taskId });

if (task.success) {
  await bulk_create_sections({
    sections: sectionsArray
  });
}
```

## Common Workflows

### Workflow 1: Task Creation with Standard Sections

```javascript
// Create task
const task = await create_task({
  title: "Implement authentication",
  summary: "Add OAuth 2.0 authentication"
});

// Add standard section set
await bulk_create_sections({
  sections: [
    {
      entityType: "TASK",
      entityId: task.data.id,
      title: "Requirements",
      usageDescription: "Core requirements",
      content: "...",
      ordinal: 0,
      tags: "requirements"
    },
    {
      entityType: "TASK",
      entityId: task.data.id,
      title: "Technical Approach",
      usageDescription: "Implementation strategy",
      content: "...",
      ordinal: 1,
      tags: "implementation"
    }
  ]
});
```

### Workflow 2: Custom Template Application

```javascript
// Define custom template
const customTemplate = [
  {
    title: "Problem Statement",
    usageDescription: "Clear problem definition",
    content: "[Describe the problem]",
    ordinal: 0,
    tags: "context,template"
  },
  {
    title: "Solution Approach",
    usageDescription: "Proposed solution",
    content: "[Describe solution]",
    ordinal: 1,
    tags: "solution,template"
  }
];

// Apply to task
await bulk_create_sections({
  sections: customTemplate.map(section => ({
    entityType: "TASK",
    entityId: taskId,
    contentFormat: "MARKDOWN",
    ...section
  }))
});
```

## Related Tools

- **add_section**: Create single section
- **get_sections**: Retrieve created sections
- **bulk_update_sections**: Update multiple sections
- **bulk_delete_sections**: Delete multiple sections

## See Also

- Single Section: `task-orchestrator://docs/tools/add-section`
- Section Retrieval: `task-orchestrator://docs/tools/get-sections`
- Bulk Updates: `task-orchestrator://docs/tools/bulk-update-sections`
