---
layout: default
title: Architecture Guide
---

# Architecture Guide

This guide provides a comprehensive overview of the MCP Task Orchestrator architecture, design patterns, and component interactions. Essential reading for contributors and developers extending the system.

## Table of Contents

- [Architectural Overview](#architectural-overview)
- [Clean Architecture Layers](#clean-architecture-layers)
- [Sub-Agent Orchestration System](#sub-agent-orchestration-system)
- [Core Components](#core-components)
- [Data Flow](#data-flow)
- [Design Patterns](#design-patterns)
- [Technology Stack](#technology-stack)
- [Extension Points](#extension-points)

---

## Architectural Overview

### High-Level Architecture

The MCP Task Orchestrator follows **Clean Architecture** principles with clear separation of concerns across four distinct layers:

```
┌─────────────────────────────────────────────────────┐
│              Interface Layer (MCP)                  │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  McpServer   │  │   McpToolAdapter         │   │
│  │              │  │   McpServerAiGuidance    │   │
│  └──────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ▲
                        │
┌─────────────────────────────────────────────────────┐
│           Application Layer (Business Logic)        │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │ ToolRegistry │  │   Tool Implementations   │   │
│  │              │  │   (MCP Tools)            │   │
│  └──────────────┘  └──────────────────────────┘   │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  Services    │  │  TemplateInitializer     │   │
│  └──────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ▲
                        │
┌─────────────────────────────────────────────────────┐
│            Domain Layer (Core Business)             │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │    Models    │  │  Repository Interfaces   │   │
│  │  Task        │  │  TaskRepository          │   │
│  │  Feature     │  │  FeatureRepository       │   │
│  │  Project     │  │  ProjectRepository       │   │
│  │  Template    │  │  TemplateRepository      │   │
│  │  Section     │  │  SectionRepository       │   │
│  │  Dependency  │  │  DependencyRepository    │   │
│  └──────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ▲
                        │
┌─────────────────────────────────────────────────────┐
│        Infrastructure Layer (Implementation)        │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │ DatabaseMgr  │  │  SQLite Repositories     │   │
│  │              │  │  (Exposed ORM)           │   │
│  └──────────────┘  └──────────────────────────┘   │
│  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  Migrations  │  │  Utilities               │   │
│  │  (Flyway)    │  │  Logging, ErrorCodes     │   │
│  └──────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### Design Principles

1. **Dependency Rule**: Dependencies point inward - outer layers depend on inner layers, never the reverse
2. **Domain Independence**: Core business logic has no dependencies on infrastructure or interface concerns
3. **Interface Segregation**: Each layer exposes only what's needed through well-defined interfaces
4. **Single Responsibility**: Each component has one clear purpose and reason to change

---

## Clean Architecture Layers

### 1. Domain Layer (`domain/`)

**Purpose**: Core business entities and rules, completely framework-agnostic

**Components**:

#### Models (`domain/model/`)
- `Task.kt` - Primary work unit with status, priority, complexity
- `Feature.kt` - Groups related tasks into functional areas
- `Project.kt` - Top-level organizational container
- `Template.kt` - Reusable documentation patterns
- `Section.kt` - Detailed content blocks for entities
- `Dependency.kt` - Task relationship modeling
- `TaskStatus.kt`, `Priority.kt`, `ProjectStatus.kt` - Enumerations
- `TaskLock.kt` - Concurrent access control
- `WorkSession.kt` - Session tracking

#### Repository Interfaces (`domain/repository/`)
- `TaskRepository.kt` - Task data access contract
- `FeatureRepository.kt` - Feature data access contract
- `ProjectRepository.kt` - Project data access contract
- `TemplateRepository.kt` - Template data access contract
- `SectionRepository.kt` - Section data access contract
- `DependencyRepository.kt` - Dependency data access contract
- `Result.kt` - Type-safe result wrapper for operations

**Key Characteristics**:
- No external dependencies (only Kotlin stdlib and kotlin-serialization)
- Immutable data classes with validation
- Business rules enforced in domain models
- Repository interfaces define contracts without implementation details

---

### 2. Application Layer (`application/`)

**Purpose**: Use cases, business logic orchestration, MCP tool implementations

**Components**:

#### Tools (`application/tools/`)

**Base Infrastructure**:
- `ToolDefinition.kt` - Abstract tool interface
- `BaseToolDefinition.kt` - Common tool functionality
- `ToolRegistry.kt` - Tool registration and management
- `ToolExecutionContext.kt` - Dependency injection for tools
- `ToolCategory.kt` - Tool categorization
- `ToolValidationException.kt`, `ToolExecutionException.kt` - Error handling

**Tool Categories**:
- `task/` - Task management tools
  - CreateTaskTool, UpdateTaskTool, GetTaskTool, DeleteTaskTool, SearchTasksTool, GetOverviewTool
- `feature/` - Feature management tools
  - CreateFeatureTool, UpdateFeatureTool, GetFeatureTool, DeleteFeatureTool, SearchFeaturesTool
- `project/` - Project management tools
  - CreateProjectTool, GetProjectTool, UpdateProjectTool, DeleteProjectTool, SearchProjectsTool
- `template/` - Template management tools
  - CreateTemplateTool, GetTemplateTool, ListTemplatesTool, ApplyTemplateTool, AddTemplateSectionTool, UpdateTemplateMetadataTool, DeleteTemplateTool, EnableTemplateTool, DisableTemplateTool
- `section/` - Section management tools
  - AddSectionTool, GetSectionsTool, UpdateSectionTool, DeleteSectionTool, BulkCreateSectionsTool, BulkUpdateSectionsTool, BulkDeleteSectionsTool, UpdateSectionTextTool, UpdateSectionMetadataTool, ReorderSectionsTool
- `dependency/` - Dependency management tools
  - CreateDependencyTool, GetTaskDependenciesTool, DeleteDependencyTool

#### Services (`application/service/`)
- `TemplateInitializer.kt` - Interface for template initialization
- `TemplateInitializerImpl.kt` - Template initialization implementation
- `templates/` - Template creator implementations for 9 built-in templates

**Tool Implementation Pattern**:
```kotlin
class CreateTaskTool : BaseToolDefinition() {
    override val name: String = "create_task"
    override val title: String = "Create Task"
    override val category: ToolCategory = ToolCategory.TASK_MANAGEMENT
    override val description: String = "..."
    override val parameterSchema: Tool.Input = ...
    override val outputSchema: Tool.Output = ...

    override fun validateParams(params: JsonElement) { ... }
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement { ... }
}
```

**Key Characteristics**:
- Tools are stateless and context-injected
- Standardized input validation and error handling
- JSON-based input/output schemas for MCP compatibility
- Output schemas provide structured response contracts
- Template-driven creation patterns

---

### 3. Infrastructure Layer (`infrastructure/`)

**Purpose**: External concerns, framework implementations, persistence

**Components**:

#### Database (`infrastructure/database/`)

**Core Database Management**:
- `DatabaseManager.kt` - Connection and lifecycle management
- `DatabaseConfig.kt` - Configuration (environment variables)
- SQLite with JDBC driver
- Exposed ORM v1 for type-safe SQL

**Schema Management** (`database/schema/`):
- `DatabaseSchemaManager.kt` - Schema update interface
- `SchemaManagerFactory.kt` - Creates appropriate schema manager
- `FlywayDatabaseSchemaManager.kt` - Flyway-based migrations
- `DirectDatabaseSchemaManager.kt` - Direct Exposed schema updates

**Migration Management** (`database/migration/`):
- Flyway V-migrations (`V1__initial_schema.sql`, etc.)
- Repeatable migrations for data seeding
- Version tracking and rollback support

**Repository Implementations** (`database/repository/`):
- `SQLiteTaskRepository.kt` - Task persistence
- `SQLiteFeatureRepository.kt` - Feature persistence
- `SQLiteProjectRepository.kt` - Project persistence
- `SQLiteTemplateRepository.kt` - Template persistence
- `SQLiteSectionRepository.kt` - Section persistence
- `SQLiteDependencyRepository.kt` - Dependency persistence

**Database Tables** (`database/schema/`):
- Tasks, Features, Projects, Templates, Sections, Dependencies
- TaskLocks (concurrent access control)
- Foreign key constraints enforced
- Indexes for performance

#### Repository Provider (`infrastructure/repository/`)
- `RepositoryProvider.kt` - Interface for repository access
- `DefaultRepositoryProvider.kt` - Default SQLite implementation
- Lazy initialization of repositories
- Centralized dependency management

#### Utilities (`infrastructure/util/`)
- `ErrorCodes.kt` - Standardized error codes
- `Logging.kt` - Logging configuration

**Key Characteristics**:
- Database abstraction through Exposed ORM
- Support for both Flyway and direct migrations
- Foreign key enforcement for data integrity
- Transaction support (SERIALIZABLE isolation)
- Lazy repository initialization

---

### 4. Interface Layer (`interfaces/mcp/`)

**Purpose**: MCP protocol adaptation, external communication

**Components**:

#### MCP Server (`interfaces/mcp/`)
- `McpServer.kt` - Main server implementation
  - Initializes database
  - Creates repository provider
  - Registers tools with MCP SDK
  - Manages server lifecycle
  - Configures AI guidance

- `McpToolAdapter.kt` - Adapter pattern for tools
  - Bridges application tools to MCP protocol
  - Parameter preprocessing and normalization
  - Enhanced error messaging
  - Tool registration with MCP SDK

- `McpServerAiGuidance.kt` - AI guidance configuration
  - MCP Resources for guidelines
  - Workflow Prompts registration
  - Getting started prompt

- `TaskOrchestratorResources.kt` - MCP Resources implementation
  - Serves AI guidelines as resources
  - Template for autonomous AI behavior

- `WorkflowPromptsGuidance.kt` - Workflow prompts
  - 6 workflow automation prompts
  - Comprehensive workflow scenarios

#### Entry Point
- `Main.kt` - Application entry point
  - Environment logging
  - Database initialization
  - Server startup
  - Error handling

**Key Characteristics**:
- Clean separation of MCP protocol from application logic
- Adapter pattern prevents MCP SDK coupling
- Comprehensive error handling and logging
- AI guidance through multiple mechanisms

---

## Sub-Agent Orchestration System

The MCP Task Orchestrator implements a **3-level agent coordination architecture** for Claude Code that enables scalable AI workflows with minimal token usage. This system is an **optional enhancement** that sits alongside the core architecture - the MCP tool system works with or without sub-agents.

### Architectural Position

Sub-agent orchestration integrates at the **Interface Layer**, specifically as an extension of the MCP communication pattern:

```
┌─────────────────────────────────────────────────────────────────┐
│                    INTERFACE LAYER (MCP)                         │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Traditional MCP Communication                       │ │
│  │  AI ←→ MCP Server ←→ Tools ←→ Repositories ←→ Database    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │      Sub-Agent Orchestration (Claude Code Only)            │ │
│  │                                                             │ │
│  │  Level 0: Orchestrator (Main Claude Instance)              │ │
│  │     ↓ Launches sub-agents via Claude Code                  │ │
│  │  Level 1: Feature Manager                                  │ │
│  │     ↓ Coordinates tasks within features                    │ │
│  │  Level 2: Task Manager                                     │ │
│  │     ↓ Routes tasks to specialists                          │ │
│  │  Level 3: Specialists (Backend, Frontend, Database, etc.)  │ │
│  │     ↓ Each uses MCP tools to access Application Layer     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                          │                                        │
└──────────────────────────┼────────────────────────────────────────┘
                           │
┌──────────────────────────┼────────────────────────────────────────┐
│                    APPLICATION LAYER                              │
│                                                                    │
│  All sub-agents use the same MCP tools:                           │
│  - get_task, create_task, update_task                            │
│  - get_feature, create_feature                                   │
│  - add_section, get_sections, update_section                     │
│  - recommend_agent (routing intelligence)                        │
│  - set_status (unified status updates)                           │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

**Key Architectural Principles**:

1. **Clean Separation**: Sub-agent orchestration is an Interface Layer concern - does not affect Domain, Application, or Infrastructure layers
2. **Tool Reuse**: Sub-agents use existing MCP tools - no new application logic required
3. **Optional Enhancement**: Core MCP server works independently of sub-agent system
4. **Claude Code Specific**: Requires `.claude/agents/` directory structure (Claude Code feature)

### 3-Level Architecture

```
Level 0: ORCHESTRATOR (Main Claude Code Instance)
         ↓
         Launches Feature Manager for multi-task features
         Receives brief summaries (200 tokens each)
         Accumulates minimal context

Level 1: FEATURE MANAGER (Sub-Agent, Clean Context)
         ↓
         Coordinates tasks within a feature
         START mode: Recommends next task
         END mode: Creates feature summary
         Uses: get_feature, get_next_task, update_feature

Level 2: TASK MANAGER (Sub-Agent, Clean Context)
         ↓
         Routes tasks to appropriate specialists
         START mode: Calls recommend_agent, reads dependency summaries
         END mode: Extracts specialist output, creates task summary
         Uses: get_task, recommend_agent, get_sections, set_status

Level 3: SPECIALISTS (Sub-Agents, Clean Context, Domain-Specific)
         ↓
         Perform actual implementation work
         Backend Engineer, Frontend Developer, Database Engineer,
         Test Engineer, Technical Writer, Planning Specialist
         Uses: get_task, Read/Edit/Write files, add_section, set_status
```

### Agent Definition Files

**Location**: `.claude/agents/*.md` (user workspace)

Sub-agents are defined as Markdown files with YAML frontmatter:

```markdown
---
name: Backend Engineer
description: Specialized in backend API development
tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__add_section, Read, Edit, Write
model: sonnet
---

# Backend Engineer Agent

You are a backend specialist focused on REST APIs, services, and business logic.

[Agent-specific guidance and workflow...]
```

**Creation**: The `setup_claude_agents` MCP tool creates these files automatically. This tool is implemented in the Application Layer (`application/tools/agent/`) and writes agent definitions to the user's workspace.

**Discovery**: Claude Code automatically discovers agents in `.claude/agents/` directory - no server-side configuration needed.

### Agent Routing System

**Component**: `recommend_agent` MCP tool

**Location**: Application Layer (`application/tools/agent/RecommendAgentTool.kt`)

**Purpose**: Intelligent routing of tasks to appropriate specialist agents based on task tags and configuration.

**Architecture**:

```
┌────────────────────────────────────────────────────────────┐
│                   RecommendAgentTool                        │
├────────────────────────────────────────────────────────────┤
│  1. Read task (via TaskRepository)                         │
│  2. Load agent-mapping.yaml configuration                  │
│  3. Match task tags to agent mappings                      │
│  4. Apply priority rules                                   │
│  5. Return: agent name, reason, section tags               │
└────────────────────────────────────────────────────────────┘
                           │
                           │ Reads configuration
                           ▼
┌────────────────────────────────────────────────────────────┐
│        src/main/resources/agents/agent-mapping.yaml         │
├────────────────────────────────────────────────────────────┤
│  tagMappings:                                              │
│    - task_tags: [backend, api, service]                   │
│      agent: Backend Engineer                              │
│      section_tags: [requirements, technical-approach]     │
│                                                            │
│  tagPriority:                                              │
│    - database                                              │
│    - backend                                               │
│    - frontend                                              │
└────────────────────────────────────────────────────────────┘
```

### Token Efficiency Architecture

The sub-agent system achieves **97% token reduction** through architectural patterns:

**1. Context Isolation**
- Each sub-agent invocation starts with clean context
- No context inheritance from orchestrator
- Sub-agent contexts discarded after completion
- Peak sub-agent context: ~3k tokens (constant)

**2. Summary Sections**
- Task Manager END creates 300-500 token summaries
- Stored as Section entities in database
- Read by dependent tasks instead of full context
- Enables knowledge transfer at 3-7% of original size

**3. Dependency Context Passing**
```
Traditional:  Task 2 reads full Task 1 context (5-10k tokens)
Sub-Agent:    Task 2 reads Task 1 Summary section (300-500 tokens)
              ↓
              Savings: 93-97% per dependency
```

**4. Orchestrator Context Growth**
```
Traditional:  Orchestrator accumulates full task contexts (5-10k per task)
              10 tasks = 50-100k tokens accumulated

Sub-Agent:    Orchestrator accumulates brief summaries (200 tokens per task)
              10 tasks = 2k tokens accumulated
              ↓
              Savings: 97% reduction in context growth
```

**Database Schema Support**:
- Summary sections stored using existing Section entity
- Tags: "summary,completion" for filtering
- No new tables or schema changes required
- Leverage existing get_sections tool for retrieval

### Integration with Clean Architecture

**Domain Layer**: No changes required
- Section entity already supports summary content
- Task/Feature entities unchanged
- Repository interfaces remain stable

**Application Layer**: Minimal additions
- `RecommendAgentTool` - new tool for agent routing
- `SetupClaudeAgentsTool` - new tool for agent installation
- Existing tools used by sub-agents unchanged

**Infrastructure Layer**: No changes required
- Existing repositories handle summary sections
- Database schema already supports all required data

**Interface Layer**: Optional enhancement
- Agent definitions in user workspace (`.claude/agents/`)
- Agent routing via MCP tool (`recommend_agent`)
- No MCP server code changes required

### Workflow Comparison

**Without Sub-Agents** (Traditional MCP workflow):
```
User → Claude → MCP Tools → Application Logic → Database
                    ↓
            Single agent accumulates context
            Limited to ~15 tasks before context overflow
```

**With Sub-Agents** (Claude Code only):
```
User → Orchestrator → Feature Manager → Task Manager → Specialist
                           ↓               ↓              ↓
                      MCP Tools       MCP Tools      MCP Tools
                           ↓               ↓              ↓
                      Application     Application    Application
                       Logic           Logic          Logic
                           ↓               ↓              ↓
                       Database        Database       Database

Each sub-agent: Clean context, focused work, brief summary
Orchestrator: Accumulates only summaries (200 tokens per task)
Scaling: 100+ tasks possible within context limits
```

### Setup and Configuration

**Setup Tool**: `setup_claude_agents`
- **Type**: MCP tool (Application Layer)
- **Function**: Writes agent definition files to `.claude/agents/`
- **Agent Definitions Source**: Embedded in JAR (`src/main/resources/agents/claude/*.md`)
- **Idempotent**: Safe to run multiple times, preserves existing customizations

**Configuration File**: `agent-mapping.yaml`
- **Location**: `src/main/resources/agents/agent-mapping.yaml`
- **Purpose**: Maps task tags to specialist agents
- **Format**: YAML with tag mappings and priority rules
- **Customizable**: Users can add custom agents and mappings

**No Server Changes Required**:
- Agent discovery handled by Claude Code (client-side)
- MCP server provides tools only
- Sub-agent coordination logic in agent definitions, not server code

### When to Use Sub-Agent Orchestration

**Use sub-agents when**:
- Working with Claude Code (required platform)
- Feature has 4+ related tasks with dependencies
- Need specialist coordination (Backend → Database → Frontend → Test)
- Token efficiency critical (large context, many tasks)
- Want 97% token reduction for orchestrator context

**Use traditional MCP workflow when**:
- Simple tasks (1-3 tasks)
- Single-specialist work
- Not using Claude Code (using Cursor, Windsurf, Claude Desktop, etc.)
- Learning the system

**Important**: Templates and workflow prompts work with BOTH approaches. Sub-agents enhance but don't replace the core MCP tool system.

### Additional Resources

For detailed sub-agent orchestration documentation, see:
- [Agent Orchestration Guide](../agent-orchestration.md) - Complete workflow patterns
- [Token Reduction Examples](../token-reduction-examples.md) - Quantitative efficiency analysis

---

## Core Components

### Database Manager

**Location**: `infrastructure/database/DatabaseManager.kt`

**Responsibilities**:
- SQLite connection management
- Schema initialization and updates
- Transaction configuration
- Foreign key constraint enforcement
- Graceful shutdown

**Configuration**:
```kotlin
// Environment variables
DATABASE_PATH=data/tasks.db  // Database file path
USE_FLYWAY=true              // Enable Flyway migrations
MCP_DEBUG=true               // Enable debug logging
```

**Schema Management Strategies**:
1. **Flyway** (`USE_FLYWAY=true`) - Production recommended
   - Versioned SQL migrations
   - Rollback support
   - Migration history tracking

2. **Direct** (`USE_FLYWAY=false`) - Development/testing
   - Exposed ORM schema updates
   - Faster iteration
   - No migration history

---

### Tool System

**Architecture**:

```
┌────────────────────────────────────────────────┐
│          MCP SDK Server                        │
│  addTool(name, description, schema, handler)   │
└────────────────────────────────────────────────┘
                    ▲
                    │ (Adapter Pattern)
┌────────────────────────────────────────────────┐
│          McpToolAdapter                        │
│  - registerToolsWithServer()                   │
│  - preprocessParameters()                      │
│  - buildDetailedErrorMessage()                 │
└────────────────────────────────────────────────┘
                    ▲
                    │
┌────────────────────────────────────────────────┐
│          ToolRegistry                          │
│  - registerTool()                              │
│  - getAllTools()                               │
│  - getToolsByCategory()                        │
└────────────────────────────────────────────────┘
                    ▲
                    │
┌────────────────────────────────────────────────┐
│          BaseToolDefinition (Abstract)         │
│  + name: String                                │
│  + title: String                               │
│  + description: String                         │
│  + category: ToolCategory                      │
│  + parameterSchema: Tool.Input                 │
│  + outputSchema: Tool.Output                   │
│  + validateParams(params)                      │
│  + execute(params, context): JsonElement       │
│  # successResponse(), errorResponse()          │
└────────────────────────────────────────────────┘
                    ▲
                    │
    ┌───────────────┴───────────────┬────────────────┐
    │                               │                │
┌───────────┐            ┌──────────────┐  ┌────────────┐
│CreateTask │            │GetOverview   │  │ApplyTemplate│
│Tool       │            │Tool          │  │Tool         │
└───────────┘            └──────────────┘  └────────────┘
```

**Tool Lifecycle**:
1. **Registration** - Tool instances created in `McpServer.createTools()`
2. **Adaptation** - `McpToolAdapter` registers with MCP SDK
3. **Execution** - MCP SDK calls tool handler
4. **Validation** - Parameters validated by tool
5. **Execution** - Business logic executed with `ToolExecutionContext`
6. **Response** - Structured JSON response returned

**Tool Categories**:
```kotlin
enum class ToolCategory {
    TASK_MANAGEMENT,
    FEATURE_MANAGEMENT,
    PROJECT_MANAGEMENT,
    TEMPLATE_MANAGEMENT,
    SECTION_MANAGEMENT,
    DEPENDENCY_MANAGEMENT
}
```

---

### Repository Pattern

**Interface Definition** (Domain Layer):
```kotlin
interface TaskRepository : ProjectScopedRepository<Task, TaskStatus, Priority> {
    suspend fun findById(id: UUID): Result<Task?>
    suspend fun create(task: Task): Result<Task>
    suspend fun update(task: Task): Result<Task>
    suspend fun delete(id: UUID): Result<Boolean>
    suspend fun findAll(limit: Int): Result<List<Task>>
    suspend fun findByFeature(featureId: UUID, ...): Result<List<Task>>
    // ... additional domain-specific queries
}
```

**Implementation** (Infrastructure Layer):
```kotlin
class SQLiteTaskRepository(
    private val databaseManager: DatabaseManager
) : TaskRepository {
    override suspend fun findById(id: UUID): Result<Task?> =
        transaction {
            // Exposed ORM query
            TaskTable.select { TaskTable.id eq id }
                .map { rowToTask(it) }
                .firstOrNull()
        }.toResult()

    // ... other implementations
}
```

**Provider Pattern**:
```kotlin
interface RepositoryProvider {
    fun taskRepository(): TaskRepository
    fun featureRepository(): FeatureRepository
    fun projectRepository(): ProjectRepository
    fun templateRepository(): TemplateRepository
    fun sectionRepository(): SectionRepository
    fun dependencyRepository(): DependencyRepository
}

class DefaultRepositoryProvider(
    private val databaseManager: DatabaseManager
) : RepositoryProvider {
    private val taskRepository: SQLiteTaskRepository by lazy {
        SQLiteTaskRepository(databaseManager)
    }
    // ... lazy initialization for all repositories
}
```

**Benefits**:
- Domain layer remains database-agnostic
- Easy to swap implementations (e.g., PostgreSQL, MongoDB)
- Testable with mock repositories
- Clear contracts for data access

---

### Template System

**Architecture**:

```
Template Initialization (Startup)
        │
        ▼
┌────────────────────────┐
│ TemplateInitializerImpl│
│  - initializeTemplates()│
│  - createTemplate()     │
└────────────────────────┘
        │
        ▼
┌────────────────────────┐
│ Template Creators       │
│  (9 built-in templates)│
│  - LocalGitBranching   │
│  - GitHubPRWorkflow    │
│  - TaskImplementation  │
│  - BugInvestigation    │
│  - TechnicalApproach   │
│  - Requirements        │
│  - Context&Background  │
│  - TestingStrategy     │
│  - DefinitionOfDone    │
└────────────────────────┘
        │
        ▼
┌────────────────────────┐
│ Template Repository     │
│  - createTemplate()    │
│  - addTemplateSection()│
│  - getAllTemplates()   │
└────────────────────────┘
        │
        ▼
┌────────────────────────┐
│ Database (Templates)   │
│  - templates table     │
│  - sections table      │
└────────────────────────┘
```

**Template Application Flow**:
```
AI Agent discovers templates
        │
        ▼
┌────────────────────────┐
│ list_templates         │
│  --targetEntityType    │
│  --isEnabled           │
└────────────────────────┘
        │
        ▼
AI selects appropriate templates
        │
        ▼
┌────────────────────────┐
│ create_task            │
│  --templateIds [...]   │
└────────────────────────┘
  OR
┌────────────────────────┐
│ apply_template         │
│  --entityId           │
│  --templateIds [...]   │
└────────────────────────┘
        │
        ▼
┌────────────────────────┐
│ ApplyTemplateTool      │
│  - Validates templates │
│  - Creates sections    │
│  - Associates with task│
└────────────────────────┘
```

**Template Structure**:
```kotlin
data class Template(
    val id: UUID,
    val name: String,
    val description: String,
    val targetEntityType: TemplateTargetType, // TASK or FEATURE
    val isBuiltIn: Boolean,
    val isProtected: Boolean,
    val isEnabled: Boolean,
    val tags: List<String>
)

data class TemplateSection(
    val id: UUID,
    val templateId: UUID,
    val title: String,
    val usageDescription: String,
    val contentSample: String,
    val contentFormat: ContentFormat, // MARKDOWN, PLAIN_TEXT, JSON, CODE
    val ordinal: Int,
    val isRequired: Boolean
)
```

---

### AI Guidance System

**Three-Layer Architecture**:

1. **Layer 1: MCP Resources** (internalized knowledge)
   - Location: `TaskOrchestratorResources.kt`
   - Provides: AI Guidelines resource (`resource://ai-guidelines`)
   - Purpose: LLM internalizes autonomous behavior patterns
   - Loaded: Automatically on MCP server connection

2. **Layer 2: Workflow Prompts** (explicit guidance)
   - Location: `WorkflowPromptsGuidance.kt`
   - Provides: 6 workflow automation prompts
   - Purpose: Step-by-step comprehensive guidance
   - Invoked: User or AI explicitly invokes prompt

3. **Layer 3: Dynamic Templates** (database-driven)
   - Location: Database + `list_templates` tool
   - Provides: 9 built-in + custom templates
   - Purpose: Structured documentation patterns
   - Discovered: AI queries via `list_templates`

**Workflow Prompts**:
- `initialize_task_orchestrator` - AI initialization
- `create_feature_workflow` - Feature creation
- `task_breakdown_workflow` - Task decomposition
- `project_setup_workflow` - Project initialization
- `implementation_workflow` - Git-aware implementation for tasks, features, and bugs

---

## Data Flow

### Tool Execution Flow

```
1. AI Agent → MCP Request
        │
        ▼
2. MCP SDK Server
        │ (receives request)
        ▼
3. McpToolAdapter
        │ (preprocesses parameters)
        ▼
4. Tool.validateParams()
        │ (validates input)
        ▼
5. Tool.execute(params, context)
        │
        ├─→ context.taskRepository()
        ├─→ context.featureRepository()
        ├─→ context.templateRepository()
        │      │
        │      ▼
        │   Repository Implementation
        │      │
        │      ▼
        │   Database Query/Update
        │      │
        │      ▼
        │   Result<T>
        │
        ▼
6. Tool returns JsonElement
        │
        ▼
7. McpToolAdapter → CallToolResult
        │
        ▼
8. MCP SDK → AI Agent
```

### Database Transaction Flow

```
Tool.execute()
    │
    ▼
suspend transaction {
    │
    ├─→ Acquire connection
    ├─→ Set isolation level (SERIALIZABLE)
    ├─→ Enable foreign keys
    │
    ├─→ Execute query/update
    │   │
    │   ├─→ Validate domain model
    │   ├─→ Execute SQL via Exposed
    │   ├─→ Map result to domain model
    │
    ├─→ Commit or rollback
    │
    └─→ Return Result<T>
}
```

### Template Application Flow

```
1. AI calls apply_template
        │
        ▼
2. ApplyTemplateTool validates
        │
        ├─→ Check entity exists
        ├─→ Check templates exist
        ├─→ Check templates enabled
        │
        ▼
3. For each template:
        │
        ├─→ Get template sections
        │
        ├─→ For each section:
        │   │
        │   ├─→ Create Section entity
        │   ├─→ Link to target entity
        │   ├─→ Set ordinal for ordering
        │   │
        │   └─→ Save to database
        │
        └─→ Return created sections
        │
        ▼
4. Return success with section count
```

---

## Design Patterns

### 1. Clean Architecture

**Layers**:
- **Domain** - Business entities and rules
- **Application** - Use cases and orchestration
- **Infrastructure** - Framework and database concerns
- **Interface** - External communication (MCP)

**Benefits**:
- Framework independence
- Testability
- Flexibility to change implementations

---

### 2. Repository Pattern

**Purpose**: Abstract data access from business logic

**Implementation**:
- Domain defines interfaces
- Infrastructure provides implementations
- Application uses through RepositoryProvider

**Benefits**:
- Database-agnostic domain layer
- Easy to mock for testing
- Clear contracts for data operations

---

### 3. Adapter Pattern

**Location**: `McpToolAdapter`

**Purpose**: Bridge application tools to MCP protocol

**Benefits**:
- Application layer independent of MCP SDK
- Centralized parameter preprocessing
- Consistent error handling

---

### 4. Dependency Injection

**Implementation**: `ToolExecutionContext`

**Pattern**:
```kotlin
data class ToolExecutionContext(
    private val repositoryProvider: RepositoryProvider
) {
    fun taskRepository() = repositoryProvider.taskRepository()
    fun featureRepository() = repositoryProvider.featureRepository()
    // ... other repositories
}

// Tools receive context
override suspend fun execute(
    params: JsonElement,
    context: ToolExecutionContext
): JsonElement {
    val repository = context.taskRepository()
    // ... use repository
}
```

**Benefits**:
- Testable tools (inject mock context)
- Flexible repository swapping
- Clear dependencies

---

### 5. Factory Pattern

**Location**: `SchemaManagerFactory`

**Purpose**: Create appropriate schema manager based on configuration

**Implementation**:
```kotlin
object SchemaManagerFactory {
    fun createSchemaManager(
        database: Database,
        useFlyway: Boolean = DatabaseConfig.useFlyway
    ): DatabaseSchemaManager {
        return if (useFlyway) {
            FlywayDatabaseSchemaManager(database)
        } else {
            DirectDatabaseSchemaManager(database)
        }
    }
}
```

---

### 6. Strategy Pattern

**Location**: Schema management

**Strategies**:
- `FlywayDatabaseSchemaManager` - Flyway-based migrations
- `DirectDatabaseSchemaManager` - Direct Exposed updates

**Benefits**:
- Interchangeable migration strategies
- Configuration-driven selection
- Clean abstraction

---

### 7. Template Method Pattern

**Location**: `BaseToolDefinition`

**Pattern**:
```kotlin
abstract class BaseToolDefinition {
    // Template method
    suspend fun callTool(params: JsonElement, context: ToolExecutionContext): JsonElement {
        validateParams(params)  // Hook
        return execute(params, context)  // Hook
    }

    // Hooks implemented by subclasses
    abstract fun validateParams(params: JsonElement)
    abstract suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement

    // Concrete methods
    protected fun successResponse(...): JsonElement { ... }
    protected fun errorResponse(...): JsonElement { ... }
}
```

---

### 8. Result Pattern

**Location**: `domain/repository/Result.kt`

**Implementation**:
```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val error: Exception) : Result<Nothing>()
}
```

**Benefits**:
- Type-safe error handling
- Explicit error states
- No exceptions in happy path

---

## Technology Stack

### Core Technologies

**Language**:
- Kotlin 2.2.0
- Coroutines for async operations

**MCP Integration**:
- kotlin-sdk 0.7.2 (MCP protocol implementation)
- JSON serialization with kotlinx.serialization

**Database**:
- SQLite (embedded database)
- Exposed v1 (Kotlin ORM)
- JDBC driver

**Migration Management**:
- Flyway 9.22.3 (optional, production recommended)
- Direct schema updates (development/testing)

**Build System**:
- Gradle 8.5+ with Kotlin DSL
- Shadow plugin for fat JAR
- Version management via libs.versions.toml

**Logging**:
- SLF4J API
- Logback implementation

**Testing**:
- Kotlin Test
- JUnit 5
- MockK for mocking

**Deployment**:
- Docker containerization
- Docker Compose for development
- GitHub Container Registry (ghcr.io)

---

### Dependencies

**Key Libraries** (from `libs.versions.toml`):

```toml
[versions]
kotlin = "2.2.0"
kotlin-sdk = "0.7.2"
exposed = "0.56.0"
flyway = "9.22.3"
sqlite-jdbc = "3.47.1.0"
slf4j = "2.0.16"
logback = "1.5.12"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
kotlin-sdk = { module = "io.modelcontextprotocol:kotlin-sdk", version.ref = "kotlin-sdk" }
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite-jdbc" }
# ... more dependencies
```

---

## Extension Points

### Adding a New Tool

**Steps**:

1. Create tool class extending `BaseToolDefinition`:

```kotlin
package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

class MyNewTool : BaseToolDefinition() {
    override val category = ToolCategory.TASK_MANAGEMENT
    override val name = "my_new_tool"
    override val title = "My New Tool"

    override val description = """
        Detailed description for AI agents...
    """.trimIndent()

    override val parameterSchema = Tool.Input(
        properties = JsonObject(mapOf(
            "param1" to JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive("...")
            ))
        )),
        required = listOf("param1")
    )

    override val outputSchema = Tool.Output(
        properties = JsonObject(mapOf(
            "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
            "message" to JsonObject(mapOf("type" to JsonPrimitive("string")))
        )),
        required = listOf("success", "message")
    )

    override fun validateParams(params: JsonElement) {
        requireString(params, "param1")
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val param1 = requireString(params, "param1")

        // Business logic
        val repository = context.taskRepository()
        // ...

        return successResponse(
            data = buildJsonObject { },
            message = "Operation completed"
        )
    }
}
```

2. Register tool in `McpServer.createTools()`:

```kotlin
private fun createTools(): List<ToolDefinition> {
    return listOf(
        // ... existing tools
        MyNewTool(),
        // ...
    )
}
```

3. Add tests in `src/test/kotlin/application/tools/`:

```kotlin
class MyNewToolTest {
    @Test
    fun `should execute successfully`() {
        // Test implementation
    }
}
```

---

### Adding a New Repository Method

**Steps**:

1. Add method to repository interface (domain layer):

```kotlin
// domain/repository/TaskRepository.kt
interface TaskRepository : ProjectScopedRepository<Task, TaskStatus, Priority> {
    // ... existing methods

    suspend fun findByCustomCriteria(
        criteria: String
    ): Result<List<Task>>
}
```

2. Implement in SQLite repository (infrastructure layer):

```kotlin
// infrastructure/database/repository/SQLiteTaskRepository.kt
override suspend fun findByCustomCriteria(
    criteria: String
): Result<List<Task>> = transaction {
    TaskTable
        .select { TaskTable.customField eq criteria }
        .map { rowToTask(it) }
}.toResult()
```

3. Use in tool:

```kotlin
override suspend fun execute(
    params: JsonElement,
    context: ToolExecutionContext
): JsonElement {
    val result = context.taskRepository()
        .findByCustomCriteria(criteria)
    // ...
}
```

---

### Adding a New Template

**Steps**:

1. Create template creator in `application/service/templates/`:

```kotlin
package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.*

object MyCustomTemplateCreator {
    fun create(): Pair<Template, List<TemplateSection>> {
        val template = Template.create {
            Template(
                name = "My Custom Template",
                description = "Template for custom scenarios",
                targetEntityType = TemplateTargetType.TASK,
                isBuiltIn = true,
                isProtected = true,
                isEnabled = true,
                tags = listOf("custom", "workflow")
            )
        }

        val sections = listOf(
            TemplateSection.create {
                TemplateSection(
                    templateId = template.id,
                    title = "Section 1",
                    usageDescription = "How AI should use this",
                    contentSample = "Sample content...",
                    contentFormat = ContentFormat.MARKDOWN,
                    ordinal = 0,
                    isRequired = true
                )
            },
            // ... more sections
        )

        return template to sections
    }
}
```

2. Register in `TemplateInitializerImpl`:

```kotlin
override fun initializeTemplate(templateName: String): Boolean {
    return when (templateName) {
        // ... existing templates
        "My Custom Template" -> createMyCustomTemplate()
        else -> { ... }
    }
}

private fun createMyCustomTemplate(): Boolean {
    val (template, sections) = MyCustomTemplateCreator.create()
    return createTemplateWithSections(template, sections, "My Custom Template")
}
```

3. Add to initialization list:

```kotlin
val templateNames = listOf(
    // ... existing templates
    "My Custom Template"
)
```

---

### Adding a New Migration

**Flyway Approach** (Production):

1. Create migration file in `src/main/resources/db/migration/`:

```sql
-- V3__add_custom_field.sql
ALTER TABLE tasks ADD COLUMN custom_field TEXT;
CREATE INDEX idx_tasks_custom_field ON tasks(custom_field);
```

2. Restart server - Flyway applies automatically

**Direct Approach** (Development):

1. Update table definition in `infrastructure/database/schema/`:

```kotlin
object TaskTable : UUIDTable("tasks") {
    // ... existing columns
    val customField = text("custom_field").nullable()
}
```

2. Update `DirectDatabaseSchemaManager.kt`:

```kotlin
override fun updateSchema(): Boolean {
    SchemaUtils.createMissingTablesAndColumns(
        TaskTable,
        // ... other tables
    )
    return true
}
```

---

### Adding a New Workflow Prompt

**Steps**:

1. Add prompt in `WorkflowPromptsGuidance.kt`:

```kotlin
private fun addMyWorkflowPrompt(server: Server) {
    server.addPrompt(
        name = "my_workflow",
        description = "Workflow for custom scenario"
    ) { _ ->
        GetPromptResult(
            description = "Custom workflow automation",
            messages = listOf(
                PromptMessage(
                    role = Role.assistant,
                    content = TextContent(
                        text = """
                        # My Custom Workflow

                        ## Purpose
                        ...

                        ## Steps
                        1. Step 1
                        2. Step 2
                        ...
                        """.trimIndent()
                    )
                )
            )
        )
    }
}
```

2. Register in `configureWorkflowPrompts()`:

```kotlin
fun configureWorkflowPrompts(server: Server) {
    // ... existing prompts
    addMyWorkflowPrompt(server)
}
```

---

## Additional Resources

### Developer Documentation
- [Contributing Guide](index.md#contributing) - Contribution guidelines and pull request process
- [Database Migrations](database-migrations.md) - Schema management and migration patterns

### User Documentation
- [Quick Start](../quick-start.md) - Getting started guide
- [AI Guidelines](../ai-guidelines.md) - How AI uses Task Orchestrator
- [API Reference](../api-reference.md) - Complete MCP tools documentation
- [Templates Guide](../templates.md) - Template system and usage

### Sub-Agent Orchestration
- [Agent Orchestration Guide](../agent-orchestration.md) - 3-level architecture, workflow patterns, setup
- [Token Reduction Examples](../token-reduction-examples.md) - Quantitative efficiency analysis

### External Resources
- [Model Context Protocol](https://modelcontextprotocol.io) - MCP specification and documentation
- [Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) - MCP Kotlin SDK
- [Exposed ORM](https://github.com/JetBrains/Exposed) - Kotlin SQL framework
- [Flyway](https://documentation.flyway.org) - Database migration tool

---

**Questions?** Open an issue on [GitHub](https://github.com/jpicklyk/task-orchestrator/issues) or check the [Community Discussions](https://github.com/jpicklyk/task-orchestrator/discussions).
