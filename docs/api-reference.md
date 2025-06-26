---
layout: default
title: API Reference
---

# MCP Tools API Reference

The MCP Task Orchestrator provides **37 comprehensive MCP tools** organized into 6 main categories for complete task and project management. All tools are designed for context efficiency and support progressive loading of related data.

## Table of Contents

- [Task Management Tools (6 tools)](#task-management-tools)
- [Feature Management Tools (5 tools)](#feature-management-tools)
- [Project Management Tools (5 tools)](#project-management-tools)
- [Dependency Management Tools (3 tools)](#dependency-management-tools)
- [Section Management Tools (9 tools)](#section-management-tools)
- [Template Management Tools (9 tools)](#template-management-tools)

---

## Task Management Tools

Task management tools provide core functionality for creating, updating, and organizing individual work items. Tasks are the primary work units in the system and can exist independently or be associated with features and projects. These tools support comprehensive metadata including status tracking, priority levels, complexity scoring, and flexible tagging systems.

### `create_task`
Create new tasks with comprehensive metadata and optional template application

**Purpose**: Creates new tasks with full metadata support, template integration, and relationship establishment.

**Key Features**:
- Comprehensive metadata (title, summary, status, priority, complexity)
- Template application during creation for structured documentation
- Feature and project association
- Flexible tagging system
- Automatic section creation from templates

**Common Use Cases**:
- Creating implementation tasks for features
- Bug tracking and resolution tasks
- Research and investigation work
- Maintenance and refactoring tasks

### `update_task`
Modify existing tasks with validation and relationship preservation

**Purpose**: Updates task properties while maintaining data integrity and relationships.

**Key Features**:
- Partial updates (only specify fields to change)
- Status progression tracking (pending → in-progress → completed)
- Priority adjustments
- Complexity refinement
- Tag management
- Relationship preservation
- **Concurrent access protection**: Prevents conflicts when multiple agents work in parallel

### `get_task`
Retrieve individual task details with optional related entity inclusion

**Purpose**: Fetch complete task information with configurable detail levels.

**Key Features**:
- Progressive loading options
- Include sections for detailed content
- Include dependencies for workflow context
- Include feature information for organizational context
- Summary view for context efficiency

### `delete_task`
Remove tasks with proper dependency cleanup

**Purpose**: Safely delete tasks while maintaining data integrity.

**Key Features**:
- Automatic dependency cleanup
- Cascade deletion options
- Soft delete support
- Section cleanup
- Relationship validation
- **Concurrent access protection**: Prevents conflicts during deletion operations

### `search_tasks`
Find tasks using flexible filtering by status, priority, tags, and text queries

**Purpose**: Powerful task discovery and filtering for project management workflows.

**Key Features**:
- Multi-criteria filtering (status, priority, complexity, tags)
- Full-text search across titles and summaries
- Feature and project filtering
- Flexible sorting options
- Pagination support

**Common Filters**:
- `status="pending"` + `priority="high"` for work planning
- `tag="task-type-bug"` for bug triage
- `featureId="uuid"` for feature-specific work

### `get_overview`
Get hierarchical overview of all tasks organized by features, with token-efficient summaries

**Purpose**: Provides a lightweight, hierarchical view of all project work for context efficiency.

**Key Features**:
- Hierarchical organization (Features → Tasks)
- Orphaned task identification
- Token-efficient summaries
- Status distribution analysis
- Quick project state assessment

---

## Feature Management Tools

Feature management tools enable grouping of related tasks into cohesive functional units. Features represent major functionality areas and provide organizational structure for complex projects. They support status tracking, priority management, and can contain multiple tasks while maintaining clear hierarchical relationships.

### `create_feature`
Create new features with metadata and automatic task association capabilities

**Purpose**: Creates feature containers for organizing related tasks with comprehensive documentation.

**Key Features**:
- Template application for structured documentation
- Project association
- Status and priority tracking
- Comprehensive tagging system
- Automatic section creation from templates

### `update_feature`
Modify existing features while preserving task relationships

**Purpose**: Updates feature properties without disrupting associated tasks.

**Key Features**:
- Partial updates
- Status progression tracking
- Priority adjustments
- Task relationship preservation
- Tag management
- **Concurrent access protection**: Prevents conflicts when multiple agents work in parallel

### `get_feature`
Retrieve feature details with optional task listings and progressive loading

**Purpose**: Fetch complete feature information with configurable detail levels.

**Key Features**:
- Include associated tasks
- Include sections for detailed content
- Task statistics and counts
- Dependency information for tasks
- Summary view options

### `delete_feature`
Remove features with configurable task handling (cascade or orphan)

**Purpose**: Safely delete features with flexible task handling options.

**Key Features**:
- Cascade deletion of all tasks
- Orphan tasks (remove feature association)
- Force deletion override
- Section cleanup
- Relationship validation
- **Concurrent access protection**: Prevents conflicts during deletion operations

### `search_features`
Find features using comprehensive filtering and text search capabilities

**Purpose**: Feature discovery and filtering for project organization.

**Key Features**:
- Multi-criteria filtering (status, priority, project)
- Full-text search across names and descriptions
- Tag-based filtering
- Date range filtering
- Flexible sorting and pagination

---

## Project Management Tools

Project management tools provide top-level organizational containers for large-scale work coordination. Projects can encompass multiple features and tasks, offering the highest level of organizational structure. These tools support complex relationship management and provide comprehensive views of project scope and progress.

### `create_project`
Create new projects with comprehensive metadata and organizational structure

**Purpose**: Creates top-level project containers for large initiatives with comprehensive documentation.

**Key Features**:
- Comprehensive metadata and documentation
- Status and priority tracking
- Flexible tagging system
- Template integration potential
- Hierarchical organization setup

### `get_project`
Retrieve project details with configurable inclusion of features, tasks, and sections

**Purpose**: Fetch complete project information with configurable detail levels.

**Key Features**:
- Include associated features
- Include associated tasks
- Include sections for detailed content
- Progressive loading options
- Summary view for context efficiency

### `update_project`
Modify existing projects with relationship preservation and validation

**Purpose**: Updates project properties while maintaining organizational relationships.

**Key Features**:
- Partial updates
- Status progression tracking
- Priority adjustments
- Relationship preservation
- Tag management
- **Concurrent access protection**: Prevents conflicts when multiple agents work in parallel

### `delete_project`
Remove projects with configurable cascade behavior for contained entities

**Purpose**: Safely delete projects with flexible handling of contained features and tasks.

**Key Features**:
- Cascade deletion options
- Force deletion override
- Hard delete vs soft delete
- Comprehensive cleanup
- Relationship validation
- **Concurrent access protection**: Prevents conflicts during deletion operations

### `search_projects`
Find projects using advanced filtering, tagging, and full-text search capabilities

**Purpose**: Project discovery and filtering for portfolio management.

**Key Features**:
- Multi-criteria filtering (status, priority, tags)
- Full-text search across names and summaries
- Date range filtering
- Flexible sorting and pagination
- Advanced filtering combinations

---

## Dependency Management Tools

Dependency management tools enable modeling relationships between tasks to represent workflows, blocking conditions, and task interdependencies. The system supports multiple dependency types and includes automatic cycle detection to maintain data integrity. These tools help organize complex project workflows and track task prerequisites.

### Dependency Types Supported

- **`BLOCKS`**: Source task blocks the target task from proceeding
- **`IS_BLOCKED_BY`**: Source task is blocked by the target task  
- **`RELATES_TO`**: General relationship between tasks without blocking semantics

### Key Features

- **Automatic Cycle Detection**: Prevents circular dependencies that would create invalid workflows
- **Cascade Operations**: Dependencies are automatically cleaned up when tasks are deleted
- **Relationship Validation**: Ensures dependencies are created between valid, existing tasks
- **Flexible Querying**: Filter dependencies by type, direction, or relationship patterns

### `create_dependency`
Create dependencies between tasks with relationship type specification and automatic cycle detection

**Purpose**: Establishes relationships between tasks to model workflows and dependencies.

**Key Features**:
- Multiple dependency types (BLOCKS, IS_BLOCKED_BY, RELATES_TO)
- Automatic cycle detection
- Duplicate prevention
- Relationship validation
- Task existence verification

**Common Use Cases**:
- Database schema BLOCKS API implementation
- Research task IS_BLOCKED_BY data availability
- Related tasks for coordination

### `get_task_dependencies`
Retrieve dependency information for tasks including incoming and outgoing relationships with filtering options

**Purpose**: Fetch comprehensive dependency information for workflow understanding.

**Key Features**:
- Filter by direction (incoming, outgoing, all)
- Filter by dependency type
- Include basic task information for related tasks
- Dependency statistics and counts
- Relationship chain analysis

### `delete_dependency`
Remove task dependencies with flexible selection criteria (by ID or task relationship)

**Purpose**: Remove dependencies with flexible targeting options.

**Key Features**:
- Delete by specific dependency ID
- Delete by task relationship (fromTaskId + toTaskId)
- Delete all dependencies for a task
- Type-specific deletion
- Bulk deletion operations

---

## Section Management Tools

Section management tools provide structured content capabilities for detailed documentation and information organization. Sections can be attached to any entity (projects, features, tasks) and support multiple content formats including markdown, plain text, and structured data. These tools enable rich documentation workflows and content organization.

### Supported Content Formats

- **MARKDOWN**: Rich text with formatting support (default)
- **PLAIN_TEXT**: Simple unformatted text
- **JSON**: Structured data and configuration
- **CODE**: Source code examples and implementation snippets

### `add_section`
Add structured content sections to any entity with flexible formatting options

**Purpose**: Creates detailed content blocks for comprehensive documentation.

**Key Features**:
- Multiple content formats (MARKDOWN, PLAIN_TEXT, JSON, CODE)
- Flexible ordering with ordinals
- Entity attachment (projects, features, tasks)
- Usage descriptions for AI guidance
- Tagging for categorization

**Common Section Types**:
- Requirements and acceptance criteria
- Implementation notes and technical details
- Testing strategies and coverage
- Reference information and links

### `get_sections`
Retrieve sections for entities with ordering and filtering capabilities

**Purpose**: Fetch all sections for an entity in proper order.

**Key Features**:
- Ordered by ordinal values
- Complete section metadata
- Content format identification
- Tag-based organization
- Creation and modification timestamps

### `update_section`
Modify existing sections with content validation and format preservation

**Purpose**: Update section content and metadata with validation.

**Key Features**:
- Partial updates (content, metadata, or both)
- Content format validation
- Ordering adjustments
- Tag management
- Title and description updates

### `delete_section`
Remove sections with proper cleanup and ordering adjustment

**Purpose**: Safely remove sections while maintaining organization.

**Key Features**:
- Individual section deletion
- Automatic ordering adjustment
- Soft delete options
- Relationship cleanup
- Validation checks

### `bulk_update_sections`
Efficiently update multiple sections in a single operation

**Purpose**: Batch updates for efficient section management.

**Key Features**:
- Multiple section updates in one operation
- Atomic transaction handling
- Partial update support for each section
- Error handling and rollback
- Performance optimization

### `bulk_create_sections`
Create multiple sections simultaneously with proper ordering

**Purpose**: Efficient creation of multiple sections (preferred over multiple add_section calls).

**Key Features**:
- Single operation for multiple sections
- Automatic ordering assignment
- Template integration support
- Error handling and validation
- Performance optimization

### `bulk_delete_sections`
Remove multiple sections with batch processing efficiency

**Purpose**: Efficient deletion of multiple sections.

**Key Features**:
- Batch deletion operations
- Hard vs soft delete options
- Atomic operation handling
- Ordering adjustment
- Error handling

### `update_section_text`
Update only section text content for focused editing workflows

**Purpose**: Efficient text-only updates without full section replacement.

**Key Features**:
- Text-only content updates
- Find and replace operations
- Content validation
- Format preservation
- Performance optimization

### `update_section_metadata`
Update section metadata (title, format, ordering) without content changes

**Purpose**: Modify section properties without affecting content.

**Key Features**:
- Metadata-only updates
- Title and description changes
- Format adjustments
- Ordering modifications
- Tag management

### `reorder_sections`
Change section ordering and organization within entities

**Purpose**: Reorganize section display order efficiently.

**Key Features**:
- Ordinal value adjustments
- Bulk reordering operations
- Entity-wide section management
- Validation and error handling
- Performance optimization

---

## Template Management Tools

Template management tools provide powerful workflow automation and standardization capabilities. The system includes 9 built-in templates organized into AI workflow instructions, documentation properties, and process & quality categories. Templates can be applied to any entity to create structured, consistent documentation and workflow guidance.

### Built-in Template Categories

#### AI Workflow Instructions
- **Local Git Branching Workflow**: Step-by-step git operations and branch management
- **GitHub PR Workflow**: Pull request creation and management using GitHub MCP tools
- **Task Implementation Workflow**: Systematic approach for implementing tasks
- **Bug Investigation Workflow**: Structured debugging and bug resolution process

#### Documentation Properties
- **Technical Approach**: Architecture decisions and implementation strategy
- **Requirements Specification**: Functional and non-functional requirements
- **Context & Background**: Business context and stakeholder information

#### Process & Quality
- **Testing Strategy**: Comprehensive testing approach and quality gates
- **Definition of Done**: Completion criteria and handoff requirements

### `create_template`
Create new custom templates with sections and metadata

**Purpose**: Creates reusable templates for standardized documentation patterns.

**Key Features**:
- Template metadata definition
- Target entity type specification (TASK, FEATURE)
- Protection and enablement settings
- Creator attribution
- Tag-based categorization

### `get_template`
Retrieve template details including sections and usage information

**Purpose**: Fetch complete template information with optional section details.

**Key Features**:
- Template metadata retrieval
- Optional section inclusion
- Usage instructions and descriptions
- Protection status information
- Creation and modification history

### `apply_template`
Apply templates to entities with customizable section creation

**Purpose**: Apply one or more templates to create structured documentation.

**Key Features**:
- Single or multiple template application
- Automatic section creation
- Content format handling
- Ordinal assignment
- Template combination support

### `list_templates`
List available templates with filtering and categorization

**Purpose**: Discover and filter available templates for entity creation.

**Key Features**:
- Filter by target entity type (TASK, FEATURE)
- Filter by enabled status
- Filter by built-in vs custom
- Tag-based filtering
- Category organization

### `add_template_section`
Add new sections to existing templates

**Purpose**: Extend templates with additional section definitions.

**Key Features**:
- Section definition creation
- Content sample provision
- Format specification
- Ordering assignment
- Required vs optional designation

### `update_template_metadata`
Modify template information and categorization

**Purpose**: Update template properties without affecting sections.

**Key Features**:
- Name and description updates
- Target entity type changes
- Enablement status modification
- Tag management
- Protection status (for custom templates)

### `delete_template`
Remove custom templates (built-in templates cannot be deleted)

**Purpose**: Remove user-created templates with safety checks.

**Key Features**:
- Custom template deletion only
- Built-in template protection
- Force deletion override
- Dependency checking
- Alternative suggestion (disable vs delete)

### `enable_template`
Enable templates for use in entity creation and application

**Purpose**: Activate templates for use in the system.

**Key Features**:
- Template activation
- Availability for application
- System-wide enablement
- Usage restoration
- Status validation

### `disable_template`
Disable templates to prevent usage while preserving definition

**Purpose**: Temporarily disable templates without deletion.

**Key Features**:
- Template deactivation
- Definition preservation
- Usage prevention
- Reversible operation
- Status management

---

## Usage Patterns and Best Practices

### Progressive Detail Loading
Many tools support progressive loading to optimize context usage:
- Start with basic entity information
- Add relationships (`includeTasks`, `includeFeatures`)
- Include detailed content (`includeSections`)
- Enable dependency analysis (`includeDependencies`)

### Efficient Bulk Operations
Use bulk operations when possible:
- `bulk_create_sections` vs multiple `add_section` calls
- `bulk_update_sections` vs multiple `update_section` calls
- Template application during entity creation vs post-creation

### Context Optimization
- Use summary views for overview operations
- Filter searches to reduce result sets
- Leverage pagination for large datasets
- Apply templates early in the creation process

### Workflow Integration
- Start with `get_overview` to understand current state
- Use `search_tasks` with specific filters for targeted work
- Apply workflow prompts for structured process guidance
- Combine multiple tools in logical sequences for complex operations

## Concurrent Access Protection

The MCP Task Orchestrator includes built-in protection against sub-agent collisions when multiple AI agents work in parallel. The locking system automatically prevents conflicts on update and delete operations for projects, features, and tasks.

### How It Works

- **Automatic Protection**: No additional configuration needed - protection is built into the tools
- **Conflict Detection**: Operations check for conflicts before proceeding
- **Clear Error Messages**: Blocked operations receive descriptive error responses
- **Timeout Protection**: Operations automatically expire after 2 minutes to prevent deadlocks from crashed agents

### Protected Operations

The following tools include concurrent access protection:
- `update_task` and `delete_task`
- `update_feature` and `delete_feature` 
- `update_project` and `delete_project`

### Handling Conflicts

When a conflict is detected, the tool returns an error response indicating that another operation is currently active on the same entity. The recommended approach is to wait briefly and retry the operation.

### Best Practices

- **Parallel Workflows**: Multiple agents can safely work on different entities simultaneously
- **Retry Logic**: Implement simple retry logic for conflict scenarios
- **Entity Separation**: Distribute work across different projects, features, or tasks to minimize conflicts

This comprehensive API provides all the tools needed for sophisticated project management workflows while maintaining context efficiency, supporting AI-driven automation, and ensuring safe parallel operation.