# create_feature Tool - Detailed Documentation

## Overview

Creates a new feature with metadata and optional template application. Features are high-level organizational units that group multiple related tasks representing coherent functionality.

**Resource**: `task-orchestrator://docs/tools/create-feature`

## Key Concepts

### Feature Purpose
- **Organization**: Features group 3+ related tasks into logical units
- **Project Structure**: Optional parent relationship with projects
- **Template Support**: Apply templates for structured documentation
- **Context Efficiency**: Detailed content stored in separate sections

### Status Lifecycle
```
planning → in-development → completed
         ↓
         archived
```

## Parameter Reference

### Required Parameters
- **name** (string): Feature name
  - Good: "User Authentication System"
  - Bad: "Auth stuff"

### Optional Parameters
- **summary** (string, max 500 chars): Brief summary of feature scope
- **description** (string): Detailed description (user-provided, no length limit)
- **status** (enum): planning | in-development | completed | archived (default: planning)
- **priority** (enum): high | medium | low (default: medium)
- **projectId** (UUID): Parent project ID
- **templateIds** (array of UUIDs): Templates to apply (use list_templates to discover)
- **tags** (string): Comma-separated tags

## Template Discovery Pattern

**CRITICAL**: Always discover templates dynamically, never hardcode template IDs.

```javascript
// Step 1: Discover available templates
const templates = await list_templates({
  targetEntityType: "FEATURE",
  isEnabled: true
});

// Step 2: Select appropriate templates based on feature type
const selectedTemplates = templates.data.templates
  .filter(t => t.tags.includes("architecture") || t.tags.includes("requirements"))
  .map(t => t.id);

// Step 3: Create feature with discovered templates
const feature = await create_feature({
  name: "Multi-Provider Authentication",
  summary: "OAuth2 authentication supporting Google, GitHub, and Facebook providers",
  priority: "high",
  templateIds: selectedTemplates,
  tags: "authentication,oauth,security"
});
```

## Common Usage Patterns

### Pattern 1: Simple Feature Creation
For straightforward features with minimal structure.

```json
{
  "name": "Dark Mode Theme",
  "summary": "Add dark mode theme toggle to application",
  "priority": "medium",
  "tags": "ui,theme,enhancement"
}
```

**When to use**: Simple features that don't need extensive documentation.

### Pattern 2: Feature with Project Association
For features that belong to a larger project.

```json
{
  "name": "Payment Gateway Integration",
  "summary": "Integrate Stripe payment processing with checkout flow",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "priority": "high",
  "templateIds": ["architecture-uuid", "requirements-uuid"],
  "tags": "payments,stripe,backend,integration"
}
```

**When to use**: Feature is part of a defined project scope.

### Pattern 3: Complex Feature with Templates
For high-complexity features requiring comprehensive documentation.

```json
{
  "name": "Real-time Notification System",
  "summary": "WebSocket-based notification system with Redis pub/sub and push notifications",
  "priority": "high",
  "templateIds": [
    "architecture-overview-uuid",
    "technical-approach-uuid",
    "requirements-uuid"
  ],
  "tags": "realtime,websocket,notifications,redis,backend"
}
```

**When to use**: Complex features requiring architecture planning and detailed requirements.

### Pattern 4: Research Feature
For exploration and proof-of-concept work.

```json
{
  "name": "GraphQL API Evaluation",
  "summary": "Evaluate GraphQL as alternative to REST API for mobile app",
  "status": "planning",
  "priority": "medium",
  "templateIds": ["technical-approach-uuid"],
  "tags": "research,spike,graphql,api"
}
```

**When to use**: Investigative work before committing to implementation.

## Template Combination Strategies

### Standard Development Feature
```
Templates: Requirements + Technical Approach
Priority: High
Use: New functionality with clear requirements
```

### Architecture Feature
```
Templates: Architecture Overview + Technical Approach
Priority: High
Use: System design changes requiring detailed planning
```

### Enhancement Feature
```
Templates: Requirements only or none
Priority: Medium-Low
Use: Improvements to existing functionality
```

## Integration with Tasks

Features group related tasks. Create the feature first, then add tasks:

```javascript
// Create the feature
const feature = await create_feature({
  name: "User Authentication System",
  summary: "Complete authentication system with OAuth and password login",
  priority: "high",
  templateIds: ["requirements-uuid", "architecture-uuid"],
  tags: "authentication,security,backend"
});

// Create tasks for this feature
const schemaTask = await create_task({
  title: "Create user authentication database schema",
  featureId: feature.data.id,
  complexity: 4,
  tags: "database,schema,authentication"
});

const oauthTask = await create_task({
  title: "Implement OAuth provider integration",
  featureId: feature.data.id,
  complexity: 7,
  tags: "oauth,authentication,backend"
});
```

## Tag Conventions

Follow consistent tagging for better organization:

**Domain Tags**:
- `frontend`, `backend`, `database`, `devops`, `infrastructure`

**Technology Tags**:
- `react`, `kotlin`, `postgresql`, `docker`, `kubernetes`, `oauth`, `websocket`

**Type Tags**:
- `enhancement`, `refactor`, `integration`, `research`, `spike`

**Component Tags**:
- `authentication`, `payments`, `notifications`, `api`, `ui`

## Response Structure

Successful response includes:
```json
{
  "success": true,
  "message": "Feature created successfully with 2 template(s) applied, creating 5 section(s)",
  "data": {
    "id": "uuid",
    "name": "Feature name",
    "summary": "Feature summary",
    "status": "planning",
    "priority": "high",
    "projectId": "uuid-or-null",
    "tags": "tag1, tag2, tag3",
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

### ❌ Mistake 2: Vague Feature Names
```json
{
  "name": "New features"
}
```
**Problem**: Not descriptive, unclear scope.

### ✅ Solution: Specific, Clear Names
```json
{
  "name": "Multi-Provider OAuth Authentication System"
}
```

### ❌ Mistake 3: Creating Features for Single Tasks
```json
{
  "name": "Fix typo in header"
}
```
**Problem**: Features should group multiple related tasks (3+ recommended).

### ✅ Solution: Use Tasks Directly for Small Items
Only create features for substantial work requiring multiple tasks.

### ❌ Mistake 4: Summary Too Long
```json
{
  "summary": "This feature implements a comprehensive authentication system with support for multiple OAuth providers including Google, GitHub, Facebook, Twitter, and LinkedIn, plus traditional email/password login, password reset via email, email verification, two-factor authentication using TOTP, SMS verification, biometric authentication on mobile devices, session management with Redis, JWT token generation and validation, refresh token rotation, account lockout after failed attempts..." // 800+ chars
}
```
**Problem**: Summary field limited to 500 characters.

### ✅ Solution: Concise Summary, Details in Sections
```json
{
  "summary": "Multi-provider OAuth authentication with email/password login, 2FA, and session management",
  "templateIds": ["requirements-uuid"]
  // Details go in Requirements section created by template
}
```

## Best Practices

1. **Always Discover Templates**: Use `list_templates` before creating features
2. **Apply Appropriate Templates**: Match template count to feature complexity
3. **Use Consistent Tagging**: Follow project tagging conventions
4. **Set Realistic Priority**: Helps with planning and resource allocation
5. **Link to Projects**: Provides organizational hierarchy
6. **Create Related Tasks**: Link tasks to feature using featureId
7. **Write Descriptive Names**: Make feature purpose immediately clear
8. **Keep Summary Brief**: Use sections for detailed information
9. **Group Related Work**: Features should contain 3+ related tasks

## Feature Size Guidelines

### Small Feature (3-5 tasks)
- Complexity: 4-6
- Templates: 1-2
- Duration: 1-2 weeks
- Example: "Dark Mode Theme", "Export to CSV"

### Medium Feature (6-10 tasks)
- Complexity: 6-8
- Templates: 2-3
- Duration: 2-4 weeks
- Example: "User Authentication", "Search Functionality"

### Large Feature (10+ tasks)
- Complexity: 8-10
- Templates: 3-4
- Duration: 4+ weeks
- Example: "Payment Processing System", "Real-time Notification Infrastructure"

## Related Tools

- **list_templates**: Discover available templates before feature creation
- **update_feature**: Modify feature after creation
- **get_feature**: Retrieve feature details with sections
- **search_features**: Find existing features
- **create_task**: Create tasks linked to this feature
- **add_section**: Add detailed content to features
- **set_status**: Update feature status efficiently
- **get_feature_tasks**: View all tasks within feature

## See Also

- Template Discovery Guide: `task-orchestrator://guidelines/template-strategy`
- Feature Management Patterns: `task-orchestrator://guidelines/feature-management`
- Workflow Integration: `task-orchestrator://guidelines/workflow-integration`
