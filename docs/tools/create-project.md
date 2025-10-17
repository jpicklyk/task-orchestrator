# create_project Tool - Detailed Documentation

## Overview

Creates a new project with metadata. Projects are top-level organizational containers that group related features and tasks together, providing the highest level of organization in Task Orchestrator.

**Resource**: `task-orchestrator://docs/tools/create-project`

## Key Concepts

### Organizational Hierarchy
```
Project (top level)
├── Features (mid level)
│   └── Tasks (lowest level)
└── Tasks (direct project tasks)
```

### Project Status Lifecycle
```
planning → in-development → completed
         ↓
         archived
```

### When to Create a Project

- **Large initiatives**: Multi-feature efforts spanning weeks or months
- **Product releases**: Version-specific work (e.g., "Mobile App 2.0")
- **Client work**: Organizing all work for a specific client
- **Departments**: Backend Team Q1 Roadmap
- **Long-term goals**: Technical debt reduction, infrastructure modernization

## Parameter Reference

### Required Parameters
- **name** (string): Project name
  - Good: "Mobile App Redesign", "Backend API v2.0"
  - Bad: "Project 1", "Stuff"

### Optional Parameters
- **summary** (string, max 500 chars): Brief agent-generated summary
- **description** (string): Detailed user-provided description (no length limit)
- **status** (enum): Project status
  - `planning` - Initial planning phase (default)
  - `in-development` - Active development
  - `completed` - Project finished
  - `archived` - Historical record
- **tags** (string): Comma-separated tags for categorization

## Common Usage Patterns

### Pattern 1: Simple Project Creation
For straightforward organizational needs.

```json
{
  "name": "E-Commerce Platform Redesign",
  "summary": "Complete overhaul of e-commerce user experience and backend architecture",
  "status": "planning",
  "tags": "2025-q1,frontend,backend,high-priority"
}
```

**When to use**:
- Starting a new major initiative
- Organizing existing features under a project
- Creating quarterly roadmap containers

**Response includes**:
- Project ID (UUID) for linking features/tasks
- Created timestamp
- All metadata fields

### Pattern 2: Client Project
For client-specific work organization.

```json
{
  "name": "Acme Corp - Enterprise Dashboard",
  "summary": "Custom enterprise analytics dashboard with real-time data visualization and role-based access control",
  "status": "planning",
  "tags": "client-acme,enterprise,dashboard,2025"
}
```

**When to use**:
- Multi-client organizations
- Separating client deliverables
- Client-specific feature tracking

### Pattern 3: Release Version Project
For version-specific development efforts.

```json
{
  "name": "Mobile App v3.0 Release",
  "summary": "Major release including offline mode, dark theme, and performance improvements",
  "status": "in-development",
  "tags": "mobile,v3.0,release,ios,android"
}
```

**When to use**:
- Version-based releases
- Milestone tracking
- Feature bundling for releases

### Pattern 4: Infrastructure Project
For technical infrastructure initiatives.

```json
{
  "name": "Kubernetes Migration",
  "summary": "Migrate all microservices from Docker Compose to Kubernetes with automated deployment pipelines",
  "status": "planning",
  "tags": "infrastructure,kubernetes,devops,migration"
}
```

**When to use**:
- Infrastructure changes
- Platform migrations
- DevOps initiatives

### Pattern 5: Department Roadmap
For team-specific quarterly planning.

```json
{
  "name": "Backend Team Q1 2025 Roadmap",
  "summary": "Q1 objectives including API performance optimization, database migration, and new microservice development",
  "status": "in-development",
  "tags": "backend,2025-q1,roadmap,performance"
}
```

**When to use**:
- Quarterly planning
- Team-specific initiatives
- Department goals tracking

## Integration with Features and Tasks

### Linking Features to Projects

After creating a project, link features using `projectId`:

```javascript
// Step 1: Create project
const project = await create_project({
  name: "Mobile App Redesign",
  summary: "Complete mobile app overhaul with new UI/UX",
  tags: "mobile,redesign,2025-q2"
});

const projectId = project.data.id;

// Step 2: Create features linked to project
const authFeature = await create_feature({
  name: "User Authentication Redesign",
  projectId: projectId,
  priority: "high",
  tags: "authentication,mobile"
});

const dashboardFeature = await create_feature({
  name: "Dashboard Widget System",
  projectId: projectId,
  priority: "medium",
  tags: "dashboard,widgets,mobile"
});
```

### Creating Project-Level Tasks

Tasks can be linked directly to projects (not through features):

```javascript
const projectTask = await create_task({
  title: "Set up project documentation structure",
  projectId: projectId,
  complexity: 3,
  priority: "high",
  tags: "documentation,setup"
});
```

**Use project-level tasks for**:
- Project setup/teardown work
- Cross-feature coordination
- Project-wide documentation

## Tag Conventions

Consistent tagging improves project organization and searchability:

### Domain Tags
- `frontend`, `backend`, `mobile`, `devops`, `infrastructure`, `database`

### Time-Based Tags
- `2025-q1`, `2025-q2`, `january-2025`, `sprint-12`

### Priority Indicators
- `high-priority`, `critical`, `nice-to-have`

### Client/Customer Tags
- `client-acme`, `client-techcorp`, `internal`

### Project Type Tags
- `release`, `migration`, `redesign`, `infrastructure`, `roadmap`

### Technology Tags
- `react`, `kotlin`, `kubernetes`, `postgresql`, `aws`

## Response Structure

### Success Response
```json
{
  "success": true,
  "message": "Project created successfully",
  "data": {
    "id": "b160fbdb-07e4-42d7-8c61-8deac7d2fc17",
    "name": "Mobile App Redesign",
    "description": null,
    "summary": "Complete mobile app overhaul with new UI/UX",
    "status": "planning",
    "createdAt": "2025-10-17T14:30:00Z",
    "modifiedAt": "2025-10-17T14:30:00Z",
    "tags": ["mobile", "redesign", "2025-q2"]
  },
  "metadata": {
    "timestamp": "2025-10-17T14:30:00Z",
    "version": "1.1.0"
  }
}
```

**Key fields**:
- `id`: Use this UUID for linking features and tasks
- `status`: Current project state
- `createdAt`/`modifiedAt`: Timestamp tracking

## Common Mistakes to Avoid

### ❌ Mistake 1: Projects Too Small
```json
{
  "name": "Fix login button color"
}
```
**Problem**: This should be a task, not a project.

### ✅ Solution: Use Projects for Large Initiatives
```json
{
  "name": "Authentication System Overhaul"
}
```

### ❌ Mistake 2: Vague Project Names
```json
{
  "name": "Website Work",
  "summary": "Do stuff on the website"
}
```
**Problem**: Not actionable, unclear scope.

### ✅ Solution: Specific, Clear Names
```json
{
  "name": "E-Commerce Checkout Redesign",
  "summary": "Redesign checkout flow to reduce cart abandonment by 25% through streamlined UX"
}
```

### ❌ Mistake 3: Summary Too Long
```json
{
  "summary": "This project involves a comprehensive overhaul of the entire mobile application including redesigning all 50+ screens, implementing new design system components, refactoring the navigation architecture, adding offline support, implementing push notifications, integrating analytics..." // 800+ chars
}
```
**Problem**: Summary field limited to 500 characters.

### ✅ Solution: Concise Summary, Details in Sections
```json
{
  "summary": "Complete mobile app redesign with new design system, offline support, and analytics integration",
  // Add detailed sections after creation
}
```

### ❌ Mistake 4: No Tags
```json
{
  "name": "API Development"
}
```
**Problem**: Harder to search and filter.

### ✅ Solution: Meaningful Tags
```json
{
  "name": "REST API v2.0 Development",
  "tags": "backend,api,2025-q1,infrastructure"
}
```

## Workflow Examples

### Workflow 1: New Project Setup

```javascript
// Step 1: Create project
const project = await create_project({
  name: "Customer Portal v2.0",
  summary: "Next-generation customer portal with real-time data and enhanced UX",
  status: "planning",
  tags: "customer-portal,frontend,backend,2025-q2"
});

// Step 2: Add project documentation sections
await add_section({
  entityType: "PROJECT",
  entityId: project.data.id,
  title: "Project Overview",
  usageDescription: "High-level project goals and success criteria",
  content: "## Goals\n- Reduce customer support tickets by 40%\n- Improve load time to < 2s\n- Support 10,000 concurrent users",
  contentFormat: "MARKDOWN",
  ordinal: 0,
  tags: "overview,goals"
});

// Step 3: Create initial features
const features = await Promise.all([
  create_feature({
    name: "Real-time Dashboard",
    projectId: project.data.id,
    priority: "high"
  }),
  create_feature({
    name: "User Profile Management",
    projectId: project.data.id,
    priority: "medium"
  })
]);

// Step 4: Check project status
const projectDetails = await get_project({
  id: project.data.id,
  includeFeatures: true,
  includeTasks: true
});
```

### Workflow 2: Quarterly Roadmap Project

```javascript
// Create quarterly roadmap
const roadmap = await create_project({
  name: "Backend Team Q2 2025 Roadmap",
  summary: "Q2 objectives: API v3 development, database optimization, microservices migration",
  status: "planning",
  tags: "backend,2025-q2,roadmap"
});

// Add quarterly objectives section
await add_section({
  entityType: "PROJECT",
  entityId: roadmap.data.id,
  title: "Q2 Objectives",
  usageDescription: "Key results and objectives for Q2",
  content: "### OKRs\n**Objective 1**: API Performance\n- KR1: Reduce p95 latency to <100ms\n- KR2: Handle 5000 req/sec\n\n**Objective 2**: Migration\n- KR1: Migrate 80% services to new architecture",
  contentFormat: "MARKDOWN",
  ordinal: 0
});

// Create features for each major initiative
const features = [
  { name: "API v3 Core Development", priority: "high" },
  { name: "Database Query Optimization", priority: "high" },
  { name: "Microservices Migration - Phase 1", priority: "medium" }
].map(f => create_feature({
  ...f,
  projectId: roadmap.data.id
}));
```

## Best Practices

1. **Use Descriptive Names**: Make project purpose clear from the name
2. **Set Appropriate Status**: Start with `planning`, move to `in-development`
3. **Apply Consistent Tags**: Follow team tagging conventions
4. **Keep Summary Brief**: Use sections for detailed information
5. **Link Features Properly**: Use projectId when creating related features
6. **Document Project Goals**: Add overview sections early
7. **Plan Before Creating**: Have clear scope and objectives
8. **Use Projects Sparingly**: Only for truly large initiatives

## Performance Considerations

### Token Usage
- Project creation: ~200-300 tokens
- With sections: ~500-2000 tokens depending on content
- Linking features: Additional ~100 tokens per feature

### Optimization Strategies
1. **Batch Feature Creation**: Create multiple features in parallel
2. **Minimal Initial Data**: Add sections after project creation
3. **Use Summary Wisely**: Keep under 500 chars for efficiency

## Related Tools

- **get_project**: Retrieve project details with related entities
- **update_project**: Modify project metadata
- **delete_project**: Remove project (with cascade options)
- **search_projects**: Find projects by criteria
- **create_feature**: Create features linked to project
- **create_task**: Create tasks linked to project
- **add_section**: Add detailed content to project
- **get_overview**: See all projects and their status

## See Also

- Project Management Patterns: `task-orchestrator://guidelines/project-management`
- Feature Organization: `task-orchestrator://guidelines/feature-organization`
- Organizational Hierarchy: `task-orchestrator://guidelines/hierarchy`
