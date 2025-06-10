# MCP Task Orchestrator

A Kotlin implementation of the Model Context Protocol (MCP) server for comprehensive task management, providing AI
assistants with a structured, context-efficient way to interact with project data.

## Overview

The MCP Task Orchestrator enables AI assistants to create, update, retrieve, and manage tasks, features, and projects
while maintaining context efficiency and optimizing token usage. It features sophisticated task organization,
template-driven documentation, and automated workflows to improve project management for AI-assisted development.

## Pre-Release Status

**Current Version: Pre-1.0.0 (Development)**

This project is actively being developed toward a 1.0.0 release. **The SQL database schema is not finalized and may
change between updates.** For production use, please wait for the 1.0.0 release when the schema will be stable.

To track releases and updates:

- Watch this repository for release notifications
- Check the [Releases](https://github.com/your-username/mcp-task-orchestrator/releases) page for version updates
- Review the [CHANGELOG](CHANGELOG.md) for breaking changes

## Key Features

### Core Functionality

- **Hierarchical Organization**: Projects, Features, Tasks with flexible relationships
- **Template System**: 9 built-in templates organized into 3 categories:
    - **AI Workflow Instructions**: Git workflows, PR management, task implementation, bug investigation
    - **Documentation Properties**: Technical approach, requirements, context & background
    - **Process & Quality**: Testing strategy, definition of done
- **Context Optimization**: Progressive loading, summary views, and efficient data structures
- **Task Type Classification**: Standardized tagging system (bug, feature, enhancement, research, maintenance)

### Technical Features

- SQLite database storage with comprehensive schema
- Model Context Protocol (MCP) server implementation
- Docker containerization support
- Structured section-based content for detailed documentation
- **Task Dependency Management**: BLOCKS, IS_BLOCKED_BY, and RELATES_TO relationships with cycle detection
- **Automatic Concurrency Protection**: Transparent locking system prevents conflicts during concurrent operations without requiring workflow changes
- Relationship validation and cascade deletion for data integrity
- Bulk operations for efficient data management

## Getting Started

### Prerequisites

- JDK 17 or higher
- Kotlin 1.9 or higher
- Docker Desktop (recommended for containerized deployment)

### Option 1: Docker Deployment (Recommended)

#### Building and Running with Docker

Use the provided build script to create and deploy the Docker container:

```bash
# Windows
./scripts/docker-clean-and-build.bat

# Linux/Mac (create equivalent shell script)
./scripts/docker-clean-and-build.sh
```

This script:

1. Builds the project with Gradle
2. Creates a Docker image tagged as `mcp-task-orchestrator`
3. Sets up the necessary Docker volumes for data persistence

#### Claude Desktop Integration

To use the MCP Task Orchestrator with Claude Desktop, add the following configuration to your
`claude_desktop_config.json` file:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "-l",
        "mcp.client=claude-desktop",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

This configuration:

- Runs the MCP server in a Docker container
- Labels the container for Claude Desktop
- Persists data using a Docker volume
- Automatically removes the container when Claude disconnects

### Option 2: Direct JAR Execution

#### Building

```bash
./gradlew build
```

#### Running

```bash
java -jar build/libs/mcp-task-orchestrator-*.jar
```

With environment variables:

```bash
MCP_TRANSPORT=stdio DATABASE_PATH=data/tasks.db java -jar build/libs/mcp-task-orchestrator-*.jar
```

## Configuration

| Environment Variable | Description                        | Default               |
|----------------------|------------------------------------|-----------------------|
| `MCP_TRANSPORT`      | Transport type (stdio)             | stdio                 |
| `DATABASE_PATH`      | Path to SQLite database file       | data/tasks.db         |
| `MCP_SERVER_NAME`    | Server name for MCP information    | mcp-task-orchestrator |
| `MCP_DEBUG`          | Enable debug mode                  | false                 |

## Template System

The MCP Task Orchestrator includes a comprehensive template system with 9 built-in templates:

### AI Workflow Instructions

- **Local Git Branching Workflow**: Step-by-step git operations and branch management
- **GitHub PR Workflow**: Pull request creation and management using GitHub MCP tools
- **Task Implementation Workflow**: Systematic approach for implementing tasks
- **Bug Investigation Workflow**: Structured debugging and bug resolution process

### Documentation Properties

- **Technical Approach**: Architecture decisions and implementation strategy
- **Requirements Specification**: Functional and non-functional requirements
- **Context & Background**: Business context and stakeholder information

### Process & Quality

- **Testing Strategy**: Comprehensive testing approach and quality gates
- **Definition of Done**: Completion criteria and handoff requirements

Templates can be applied individually or in combination to create structured documentation for projects, features, and
tasks.

## MCP Workflow Prompts

The MCP Task Orchestrator includes **5 user-invokable workflow prompts** that provide structured guidance for common task orchestration scenarios. These prompts are designed to work seamlessly with the MCP tool ecosystem and offer step-by-step workflows for complex project management tasks.

### Available Workflow Prompts

#### `create_feature_workflow`
**Guide for creating a comprehensive feature with templates, tasks, and proper organization**

Provides a complete 7-step workflow for creating well-structured features:
1. Check current project state with `get_overview`
2. Find appropriate templates using `list_templates`
3. Create the feature with comprehensive metadata and templates
4. Review the created structure with `get_feature`
5. Create associated tasks with proper complexity and priority
6. Establish task dependencies where needed
7. Final review of the complete feature structure

**Best Practices Included:**
- Template selection strategies for comprehensive coverage
- Task sizing guidelines (complexity 3-7)
- Consistent tagging conventions
- Clear acceptance criteria definition

#### `task_breakdown_workflow`
**Guide for breaking down complex tasks into manageable, well-organized subtasks**

Systematic 7-step approach for decomposing complex work:
1. Analyze the complex task scope and requirements
2. Identify natural breakdown boundaries (component, phase, skill set)
3. Create feature containers for large breakdowns (4+ subtasks)
4. Create focused subtasks with manageable complexity (3-6)
5. Establish proper task dependencies and sequencing
6. Update the original task to reflect coordination role
7. Review and validate the complete breakdown

**Common Breakdown Patterns:**
- **API Development**: Schema → Endpoints → Auth → Validation → Testing
- **UI Features**: Design → Implementation → State → Styling → Testing
- **Integration**: Research → Connection → Mapping → Error Handling → Monitoring

#### `bug_triage_workflow`
**Systematic approach to bug triage, investigation, and resolution planning**

Comprehensive 7-step bug management process:
1. Initial bug assessment using `search_tasks` with bug filters
2. Create structured bug investigation task with appropriate templates
3. Detailed investigation covering problem analysis, technical review, and impact assessment
4. Determine resolution approach (simple update vs. complex breakdown)
5. Prioritization and planning with proper timeline consideration
6. Implementation workflow with branch management and testing
7. Resolution tracking with status updates and documentation

**Bug Classification System:**
- **Severity**: critical, high, medium, low
- **Component**: frontend, backend, database, integration, infrastructure
- **Type**: regression, performance, security, data corruption

#### `sprint_planning_workflow`
**Comprehensive guide for sprint planning using task orchestrator tools and data**

Data-driven 8-step sprint planning process:
1. Current state analysis with `get_overview` and progress assessment
2. Backlog analysis using `search_tasks` with priority and status filters
3. Capacity assessment including in-progress work and complexity distribution
4. Priority setting with business value and dependency analysis
5. Sprint goal definition with feature-based objectives
6. Task selection and assignment with capacity-based filtering
7. Sprint backlog organization with dependencies and breakdown
8. Sprint monitoring setup with tracking views and standup queries

**Sprint Planning Best Practices:**
- Mix of complexity levels (avoid all high-complexity tasks)
- 20% capacity reserve for unexpected work
- Balance feature development with technical debt
- Risk mitigation and alternative planning

#### `project_setup_workflow`
**Complete guide for setting up a new project with proper structure, features, and initial tasks**

Comprehensive 8-step project initialization:
1. Project foundation creation with clear charter and scope
2. Project documentation structure using `bulk_create_sections`
3. Feature planning and structure identification (3-7 major functional areas)
4. Initial task creation for infrastructure and research
5. Template strategy setup with custom template consideration
6. Development workflow setup including git and QA processes
7. Initial dependencies and sequencing establishment
8. Project monitoring setup with views and progress tracking

**Project Organization Standards:**
- **Naming Conventions**: Business-focused projects, user-focused features, action-focused tasks
- **Tagging Strategy**: Multi-level categorization for domain, technology, and impact
- **Documentation Standards**: High-level project docs, user-value feature docs, implementation-focused task docs
- **Scalability Planning**: Team growth consideration and feature independence

### Using Workflow Prompts

Workflow prompts are accessed through the MCP prompt system and can be invoked by AI assistants to provide structured guidance:

```json
{
  "method": "prompts/get",
  "params": {
    "name": "create_feature_workflow"
  }
}
```

Each prompt provides:
- **Step-by-step instructions** with specific MCP tool calls
- **Best practice guidance** for quality and consistency
- **JSON examples** for tool parameters
- **Quality validation** checklists
- **Integration points** with other workflows and templates

These prompts integrate seamlessly with the template system and MCP tools to provide comprehensive workflow automation for complex project management scenarios.

## Task Type Classification

The system uses a standardized tagging convention for task types:

- `task-type-bug`: Issues, defects, and fixes
- `task-type-feature`: New functionality
- `task-type-enhancement`: Improvements to existing features
- `task-type-research`: Investigation and analysis work
- `task-type-maintenance`: Refactoring, updates, and technical debt

This enables easy filtering and organization in task overviews and search results.

## Development

### Testing

```bash
./gradlew test
```

### Testing MCP Connection

Run the included test script to verify your connection:

```bash
node scripts/test-mcp-connection.js
```

This will start the server and run basic connectivity tests to ensure proper JSON-RPC communication.

## Troubleshooting

### JSON Parsing Errors

If you see errors like `"Unexpected number in JSON at position 1"` or
`"Unexpected token 'j', "java.util."... is not valid JSON"`, this indicates that non-JSON content (likely Java exception
stack traces) is being mixed into the JSON-RPC message stream.

#### Solution

Enable debug mode to diagnose the issue:

```bash
MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-*.jar
```

This will create detailed logs in the `logs` directory:
- `task-orchestrator.log` - General application logs

### Docker Issues

If you encounter Docker-related issues:

1. Ensure Docker Desktop is running
2. Check that the `mcp-task-data` volume has proper permissions
3. Verify the container can access the data directory:

```bash
docker volume inspect mcp-task-data
```

### Advanced Debugging

For advanced debugging, you can:

1. Examine the detailed message logs in the `logs` directory
2. Use the echo tool to test basic connectivity:

```json
{
   "jsonrpc": "2.0",
   "id": 1,
   "method": "tools/call",
   "params": {
      "name": "echo",
      "arguments": {
         "message": "Hello, MCP server!"
      }
   }
}
```

## Available MCP Tools

The server provides **37 comprehensive MCP tools** organized into 6 main categories for complete task and project
management. All tools are designed for context efficiency and support progressive loading of related data.

### Task Management Tools (6 tools)

Task management tools provide core functionality for creating, updating, and organizing individual work items. Tasks are
the primary work units in the system and can exist independently or be associated with features and projects. These
tools support comprehensive metadata including status tracking, priority levels, complexity scoring, and flexible
tagging systems.

- `create_task` - Create new tasks with comprehensive metadata and optional template application
- `update_task` - Modify existing tasks with validation and relationship preservation
- `get_task` - Retrieve individual task details with optional related entity inclusion
- `delete_task` - Remove tasks with proper dependency cleanup
- `search_tasks` - Find tasks using flexible filtering by status, priority, tags, and text queries
- `get_overview` - Get hierarchical overview of all tasks organized by features, with token-efficient summaries

### Feature Management Tools (5 tools)

Feature management tools enable grouping of related tasks into cohesive functional units. Features represent major
functionality areas and provide organizational structure for complex projects. They support status tracking, priority
management, and can contain multiple tasks while maintaining clear hierarchical relationships.

- `create_feature` - Create new features with metadata and automatic task association capabilities
- `update_feature` - Modify existing features while preserving task relationships
- `get_feature` - Retrieve feature details with optional task listings and progressive loading
- `delete_feature` - Remove features with configurable task handling (cascade or orphan)
- `search_features` - Find features using comprehensive filtering and text search capabilities

### Project Management Tools (5 tools)

Project management tools provide top-level organizational containers for large-scale work coordination. Projects can
encompass multiple features and tasks, offering the highest level of organizational structure. These tools support
complex relationship management and provide comprehensive views of project scope and progress.

- `create_project` - Create new projects with comprehensive metadata and organizational structure
- `get_project` - Retrieve project details with configurable inclusion of features, tasks, and sections
- `update_project` - Modify existing projects with relationship preservation and validation
- `delete_project` - Remove projects with configurable cascade behavior for contained entities
- `search_projects` - Find projects using advanced filtering, tagging, and full-text search capabilities

### Dependency Management Tools (3 tools)

Dependency management tools enable modeling relationships between tasks to represent workflows, blocking conditions, and task interdependencies. The system supports multiple dependency types and includes automatic cycle detection to maintain data integrity. These tools help organize complex project workflows and track task prerequisites.

- `create_dependency` - Create dependencies between tasks with relationship type specification and automatic cycle detection
- `get_task_dependencies` - Retrieve dependency information for tasks including incoming and outgoing relationships with filtering options
- `delete_dependency` - Remove task dependencies with flexible selection criteria (by ID or task relationship)

**Dependency Types Supported:**
- `BLOCKS` - Source task blocks the target task from proceeding
- `IS_BLOCKED_BY` - Source task is blocked by the target task
- `RELATES_TO` - General relationship between tasks without blocking semantics

**Key Features:**
- **Automatic Cycle Detection**: Prevents circular dependencies that would create invalid workflows
- **Cascade Operations**: Dependencies are automatically cleaned up when tasks are deleted
- **Relationship Validation**: Ensures dependencies are created between valid, existing tasks
- **Flexible Querying**: Filter dependencies by type, direction, or relationship patterns

### Section Management Tools (9 tools)

Section management tools provide structured content capabilities for detailed documentation and information
organization. Sections can be attached to any entity (projects, features, tasks) and support multiple content formats
including markdown, plain text, and structured data. These tools enable rich documentation workflows and content
organization.

- `add_section` - Add structured content sections to any entity with flexible formatting options
- `get_sections` - Retrieve sections for entities with ordering and filtering capabilities
- `update_section` - Modify existing sections with content validation and format preservation
- `delete_section` - Remove sections with proper cleanup and ordering adjustment
- `bulk_update_sections` - Efficiently update multiple sections in a single operation
- `bulk_create_sections` - Create multiple sections simultaneously with proper ordering
- `bulk_delete_sections` - Remove multiple sections with batch processing efficiency
- `update_section_text` - Update only section text content for focused editing workflows
- `update_section_metadata` - Update section metadata (title, format, ordering) without content changes
- `reorder_sections` - Change section ordering and organization within entities

### Template Management Tools (9 tools)

Template management tools provide powerful workflow automation and standardization capabilities. The system includes 9
built-in templates organized into AI workflow instructions, documentation properties, and process & quality categories.
Templates can be applied to any entity to create structured, consistent documentation and workflow guidance.

- `create_template` - Create new custom templates with sections and metadata
- `get_template` - Retrieve template details including sections and usage information
- `apply_template` - Apply templates to entities with customizable section creation
- `list_templates` - List available templates with filtering and categorization
- `add_template_section` - Add new sections to existing templates
- `update_template_metadata` - Modify template information and categorization
- `delete_template` - Remove custom templates (built-in templates cannot be deleted)
- `enable_template` - Enable templates for use in entity creation and application
- `disable_template` - Disable templates to prevent usage while preserving definition

## Data Model

The system uses a hierarchical structure with dependency relationships:

```
Project (optional)
  ??? Feature (optional)
      ??? Task (required) ?????? Dependencies ??? Task
          ??? Section (optional, for detailed content)
```

- **Projects**: Top-level organizational containers
- **Features**: Optional groupings for related tasks
- **Tasks**: Primary work units with status, priority, and complexity
- **Dependencies**: Relationships between tasks (BLOCKS, IS_BLOCKED_BY, RELATES_TO)
- **Sections**: Structured content blocks for detailed documentation

**Key Relationships:**
- Tasks can exist independently or be associated with Features and Projects as needed
- Dependencies create directed relationships between any two tasks regardless of their project/feature associations
- Circular dependencies are automatically prevented to maintain workflow integrity
- Cascade deletion ensures dependency cleanup when tasks are removed

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

Please ensure all tests pass and follow the existing code style.

## License

[MIT License](LICENSE)
