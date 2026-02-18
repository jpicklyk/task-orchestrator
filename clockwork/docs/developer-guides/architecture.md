---
layout: default
title: Architecture Guide
---

# Architecture Guide

This guide provides a comprehensive overview of the MCP Task Orchestrator architecture, design patterns, and component interactions. Essential reading for contributors and developers extending the system.

## Table of Contents

**Part 1: MCP Server (Client-Agnostic)**
- [Architectural Overview](#architectural-overview)
- [Clean Architecture Layers](#clean-architecture-layers)
- [MCP Tool System](#mcp-tool-system)
- [Status Progression System](#status-progression-system)
- [Template System](#template-system)
- [AI Guidance System](#ai-guidance-system)
- [Core Components](#core-components)
- [Data Flow](#data-flow)
- [Design Patterns](#design-patterns)
- [Technology Stack](#technology-stack)
- [Extension Points](#extension-points)

**Part 2: Client-Specific Extensions**
- [Claude Code Plugin](#claude-code-plugin)

---

## Architectural Overview

The MCP Task Orchestrator has two distinct architectural layers:

1. **MCP Server** (this document, Part 1) -- A client-agnostic Kotlin server that exposes tools, resources, and prompts via the [Model Context Protocol](https://modelcontextprotocol.io). Any MCP-compatible client can connect: Claude Code, Claude Desktop, Cursor, Windsurf, Cline, or custom integrations.

2. **Client-Specific Plugins** (Part 2) -- Optional extensions that enhance the experience for a specific AI client. Currently, only a **Claude Code plugin** exists, providing skills, hooks, output styles, and agent definitions. Other client plugins can be built independently without modifying the MCP server.

Everything in Part 1 is the MCP server -- it works identically regardless of which client connects.

### High-Level Architecture

The MCP server follows **Clean Architecture** principles with clear separation of concerns across four distinct layers:

```
+-----------------------------------------------------+
|              Interface Layer (MCP)                    |
|  +----------------+  +--------------------------+    |
|  | McpServer      |  | McpToolAdapter           |    |
|  |                |  | TaskOrchestratorResources |    |
|  |                |  | ToolDocumentationResources|    |
|  |                |  | MarkdownResourceProvider  |    |
|  +----------------+  +--------------------------+    |
+-----------------------------------------------------+
                        |
                        v
+-----------------------------------------------------+
|           Application Layer (Business Logic)         |
|  +----------------+  +--------------------------+    |
|  | ToolRegistry   |  | 14 MCP Tool Impls        |    |
|  |                |  | (consolidated v2.0)       |    |
|  +----------------+  +--------------------------+    |
|  +----------------+  +--------------------------+    |
|  | Services       |  | TemplateInitializer      |    |
|  | StatusValidator|  | CascadeService           |    |
|  | StatusProgSvc  |  | DependencyGraphService   |    |
|  +----------------+  +--------------------------+    |
+-----------------------------------------------------+
                        |
                        v
+-----------------------------------------------------+
|            Domain Layer (Core Business)              |
|  +----------------+  +--------------------------+    |
|  | Models         |  | Repository Interfaces    |    |
|  |  Task          |  |  TaskRepository          |    |
|  |  Feature       |  |  FeatureRepository       |    |
|  |  Project       |  |  ProjectRepository       |    |
|  |  Template      |  |  TemplateRepository      |    |
|  |  Section       |  |  SectionRepository       |    |
|  |  Dependency    |  |  DependencyRepository    |    |
|  |  RoleTransition|  |  RoleTransitionRepository |    |
|  |  StatusRole    |  |                          |    |
|  +----------------+  +--------------------------+    |
+-----------------------------------------------------+
                        |
                        v
+-----------------------------------------------------+
|        Infrastructure Layer (Implementation)         |
|  +----------------+  +--------------------------+    |
|  | DatabaseMgr    |  | SQLite Repositories      |    |
|  |                |  | (Exposed ORM)            |    |
|  +----------------+  +--------------------------+    |
|  +----------------+  +--------------------------+    |
|  | Migrations     |  | Utilities                |    |
|  | (Flyway)       |  | Logging, ErrorCodes      |    |
|  +----------------+  +--------------------------+    |
+-----------------------------------------------------+
```

### System Boundary

```
+---------------------------------------------------------------+
|                    CLIENT LAYER (not in this repo)             |
|                                                                |
|  Claude Code    Claude Desktop    Cursor    Windsurf    Custom |
|  + Plugin       (direct MCP)     (MCP)     (MCP)       (MCP)  |
|  (skills,                                                      |
|   hooks,                                                       |
|   output styles)                                               |
+---------------------------------------------------------------+
                            |
                     MCP Protocol (stdio)
                            |
+---------------------------------------------------------------+
|                   MCP SERVER (this repo)                       |
|                                                                |
|   Interface -> Application -> Domain -> Infrastructure         |
|   (14 tools, resources, prompts, services, database)           |
+---------------------------------------------------------------+
```

All clients interact with the same MCP server via the same protocol. Client-specific features (skills, hooks, output styles) live outside the server in their respective plugin packages. See [Claude Code Plugin](#claude-code-plugin) for the only client extension currently implemented.

### Design Principles

1. **Dependency Rule**: Dependencies point inward -- outer layers depend on inner layers, never the reverse
2. **Domain Independence**: Core business logic has no dependencies on infrastructure or interface concerns
3. **Interface Segregation**: Each layer exposes only what's needed through well-defined interfaces
4. **Single Responsibility**: Each component has one clear purpose and reason to change

---

## Clean Architecture Layers

### 1. Domain Layer (`domain/`)

**Purpose**: Core business entities and rules, completely framework-agnostic.

**Components**:

#### Models (`domain/model/`)

| File | Purpose |
|------|---------|
| `Task.kt` | Primary work unit with status, priority, complexity, tags, verification |
| `Feature.kt` | Groups related tasks into functional areas with status and priority |
| `Project.kt` | Top-level organizational container |
| `Dependency.kt` | Task relationship modeling (BLOCKS, IS_BLOCKED_BY, RELATES_TO) with optional `unblockAt` role gating |
| `Template.kt` | Reusable documentation patterns; includes `TemplateSection` data class |
| `Section.kt` | Content blocks for entities with `ContentFormat` (MARKDOWN, PLAIN_TEXT, JSON, CODE) and `EntityType` enum |
| `RoleTransition.kt` | Audit trail for role changes (queue -> work -> review -> terminal) with timestamps and triggers |
| `StatusRole.kt` | Semantic role enum: QUEUE(0), WORK(1), REVIEW(2), TERMINAL(3), BLOCKED(-1) with ordinal progression |
| `Priority.kt` | Enum: HIGH, MEDIUM, LOW |
| `ProjectStatus.kt` | Enum: PLANNING, IN_DEVELOPMENT, COMPLETED, ARCHIVED, ON_HOLD, DEPLOYED, CANCELLED |
| `FilterModels.kt` | Generic `StatusFilter<T>` and `PriorityFilter` with include/exclude matching |
| `Counts.kt` | `TaskCounts` (by-status breakdown) and `FeatureCounts` for cascade detection |
| `TaskLock.kt` | Lock models: TaskLock, LockScope, LockType, LockContext |
| `LockError.kt` | Lock error handling models |
| `WorkSession.kt` | Session tracking |

#### Repository Interfaces (`domain/repository/`)

| File | Purpose |
|------|---------|
| `Result.kt` | Type-safe `Result<T>` sealed class (Success/Error) |
| `BaseRepository.kt` | Base interface for all repositories |
| `TaskRepository.kt` | Task data access: findByFeature, getNextRecommendedTask, findByComplexity |
| `FeatureRepository.kt` | Feature data access: findByName, getTaskCount, getTaskCountsByFeatureId |
| `ProjectRepository.kt` | Project data access: findByName, getFeatureCount, getFeatureCountsByProjectId |
| `DependencyRepository.kt` | Dependency operations: create, findByTaskId, findByToTaskId, createBatch, hasCyclicDependency |
| `SectionRepository.kt` | Section operations: getSectionsForEntity, addSection, reorderSections |
| `TemplateRepository.kt` | Template operations: getAllTemplates, applyTemplate, applyMultipleTemplates, searchTemplates |
| `RoleTransitionRepository.kt` | Role transition audit: create, findByEntityId, findByTimeRange, deleteByEntityId |

**Key Characteristics**:
- No external dependencies (only Kotlin stdlib and kotlinx.serialization)
- Immutable data classes with validation
- Business rules enforced in domain models
- Repository interfaces define contracts without implementation details

---

### 2. Application Layer (`application/`)

**Purpose**: Use cases, business logic orchestration, MCP tool implementations.

#### Tools (`application/tools/`)

The application layer exposes **14 MCP tools** using a consolidated v2.0 architecture. Instead of individual CRUD tools per entity, operations are grouped into unified tools with an `operation` parameter.

**Base Infrastructure** (`tools/base/`):
- `ToolDefinition.kt` -- Tool interface and `ToolCategory` enum
- `BaseToolDefinition.kt` -- Abstract base with parameter extractors, response builders
- `SimpleLockAwareToolDefinition.kt` -- Enhanced base with optional lock support and error handling
- `ToolRegistry.kt` -- Tool registration and discovery
- `ToolExecutionContext.kt` -- Dependency injection: repositories, StatusProgressionService, CascadeService

**Tool Implementations** (14 tools):

| Tool Class | Category | Location | Operations |
|------------|----------|----------|------------|
| `QueryContainerTool` | Container | `tools/` | get, search, export, overview |
| `ManageContainerTool` | Container | `tools/` | create, update, delete (batch arrays) |
| `QuerySectionsTool` | Section | `tools/section/` | Read with filtering, optional content |
| `ManageSectionsTool` | Section | `tools/section/` | add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete |
| `QueryTemplatesTool` | Template | `tools/template/` | get, list |
| `ManageTemplateTool` | Template | `tools/template/` | create, update, delete, enable, disable, addSection |
| `ApplyTemplateTool` | Template | `tools/template/` | Apply template(s) to entity |
| `QueryDependenciesTool` | Dependency | `tools/dependency/` | Query with optional full graph traversal |
| `ManageDependenciesTool` | Dependency | `tools/dependency/` | create (batch/pattern), delete (by ID/relationship) |
| `GetNextTaskTool` | Workflow | `tools/task/` | Priority-based task recommendation |
| `GetBlockedTasksTool` | Workflow | `tools/task/` | Dependency blocking analysis |
| `GetNextStatusTool` | Status | `tools/status/` | Read-only status recommendations with role annotations |
| `RequestTransitionTool` | Status | `tools/status/` | Trigger-based transitions with validation and auto-cascade |
| `QueryRoleTransitionsTool` | Status | `tools/status/` | Audit trail of role transitions |

**Utility Files**:
- `UpdateEfficiencyMetrics.kt` -- Metrics for update operations
- `LockException.kt` -- Exception for lock conflicts
- `utils/TextUpdateUtil.kt` -- Text replacement utility

#### Services (`application/service/`)

| Service | Purpose |
|---------|---------|
| `StatusValidator.kt` | Validates status transitions, checks dependency prerequisites, verification gates |
| `DependencyGraphService.kt` | Graph traversal, cycle detection, critical path analysis, bottleneck identification |
| `SimpleLockingService.kt` | Lock acquisition, renewal, conflict detection |
| `SimpleSessionManager.kt` | Session lifecycle management |
| `VerificationGateService.kt` | Verification requirement checking |
| `CompletionCleanupService.kt` | Auto-delete child tasks when feature reaches terminal status |
| `LockErrorHandler.kt` | User-friendly lock conflict error messages |

**Status Progression** (`service/progression/`):
- `StatusProgressionService.kt` -- Interface for workflow progression
- `StatusProgressionServiceImpl.kt` -- Reads `.taskorchestrator/config.yaml`, maps statuses to roles, validates transitions against flow sequences

**Cascade Management** (`service/cascade/`):
- `CascadeService.kt` -- Interface for cascade event detection and application
- `CascadeServiceImpl.kt` -- Auto-advances parent entities when children complete; recursive cascade chains (task -> feature -> project)

**Template Initialization** (`service/templates/`):
- `TemplateInitializer.kt` / `TemplateInitializerImpl.kt` -- Initializes built-in templates on startup
- 13 template creators covering implementation workflows, testing, documentation, git integration, and planning

---

### 3. Infrastructure Layer (`infrastructure/`)

**Purpose**: External concerns, framework implementations, persistence.

#### Database (`infrastructure/database/`)

**Core Database Management**:
- `DatabaseManager.kt` -- Connection management, schema initialization, transaction configuration
- `DatabaseConfig.kt` -- Configuration from environment variables
- SQLite with JDBC driver
- Exposed ORM 1.0.0-beta-2 for type-safe SQL

**Schema Management** (`database/schema/`):
- `DatabaseSchemaManager.kt` -- Schema update interface
- `SchemaManagerFactory.kt` -- Creates Flyway or Direct schema manager
- `FlywayDatabaseSchemaManager.kt` -- Flyway-based migrations (production)
- `DirectDatabaseSchemaManager.kt` -- Direct Exposed schema updates (development/testing)

**Database Tables** (`database/schema/`):

| Table | Key Columns |
|-------|-------------|
| `TaskTable` | id, projectId, featureId, title, description, summary, status, priority, complexity, tags, requiresVerification, version |
| `FeaturesTable` | id, projectId, name, description, summary, status, priority, tags, requiresVerification, version |
| `ProjectsTable` | id, name, description, summary, status, tags, version |
| `DependenciesTable` | id, fromTaskId, toTaskId, type, unblockAt, createdAt |
| `SectionsTable` | id, entityType, entityId, title, content, contentFormat, ordinal, tags, version |
| `TemplatesTable` | id, name, description, targetEntityType, isBuiltIn, isProtected, isEnabled, tags |
| `RoleTransitionsTable` | id, entityId, entityType, fromRole, toRole, fromStatus, toStatus, trigger, summary |
| `TaskLocksTable` | Lock persistence |
| `WorkSessionsTable` | Session tracking |
| `EntityTagsTable` | Normalized tag storage |
| `EntityAssignmentsTable` | Agent/team assignment tracking |

**Repository Implementations** (`database/repository/`):

| Repository | Implements |
|------------|-----------|
| `SQLiteTaskRepository` | TaskRepository |
| `SQLiteFeatureRepository` | FeatureRepository |
| `SQLiteProjectRepository` | ProjectRepository |
| `SQLiteDependencyRepository` | DependencyRepository |
| `SQLiteSectionRepository` | SectionRepository |
| `SQLiteTemplateRepository` | TemplateRepository |
| `SQLiteRoleTransitionRepository` | RoleTransitionRepository |
| `SQLiteBusinessEntityRepository` | Shared base class for task/feature/project logic |
| `CachedTemplateRepository` | Decorator pattern caching for templates |

**Migration Management** (`database/migration/`):
- 14 Flyway V-migrations (V1 through V14)
- Migration test framework and schema validation tests

#### Repository Provider (`infrastructure/repository/`)
- `RepositoryProvider.kt` -- Interface for repository access
- `DefaultRepositoryProvider.kt` -- Default SQLite implementation with lazy initialization

#### Utilities (`infrastructure/util/`)
- `ErrorCodes.kt` -- Standardized error codes
- `Logging.kt` -- Logging configuration

---

### 4. Interface Layer (`interfaces/mcp/`)

**Purpose**: MCP protocol adaptation, external communication, AI guidance.

| File | Purpose |
|------|---------|
| `McpServer.kt` | Main server: database initialization, service creation, tool registration, lifecycle management |
| `McpToolAdapter.kt` | Adapter bridging application tools to MCP protocol; parameter preprocessing, validation, response formatting |
| `McpServerAiGuidance.kt` | Delegates AI guidance configuration to `TaskOrchestratorResources` |
| `TaskOrchestratorResources.kt` | Registers 5 MCP guideline resources; implements two-layer setup instructions architecture (v3) |
| `ToolDocumentationResources.kt` | Registers 14 on-demand tool documentation resources loaded from markdown files |
| `MarkdownResourceProvider.kt` | Guide for markdown-formatted entity exports |

**Entry Point** (`Main.kt`):
1. Logs version and environment
2. Initializes database (`DatabaseManager.initialize()` + `updateSchema()`)
3. Creates `ShutdownCoordinator` with signal handlers (SIGTERM, SIGINT)
4. Creates and runs `McpServer` (blocks until closed)
5. Graceful shutdown on exit

---

## MCP Tool System

### Tool Architecture

```
+----------------------------------------------+
|          MCP SDK Server                       |
|  addTool(name, description, schema, handler) |
+----------------------------------------------+
                    |
                    | (Adapter Pattern)
+----------------------------------------------+
|          McpToolAdapter                       |
|  - registerToolsWithServer()                 |
|  - preprocessParameters()                    |
|  - buildDetailedErrorMessage()               |
+----------------------------------------------+
                    |
+----------------------------------------------+
|          ToolRegistry                         |
|  - registerTool()                            |
|  - getAllTools()                              |
|  - getToolsByCategory()                      |
+----------------------------------------------+
                    |
+----------------------------------------------+
|  SimpleLockAwareToolDefinition (Abstract)     |
|  (extends BaseToolDefinition)                |
|  + name, title, description, category        |
|  + parameterSchema: Tool.Input               |
|  + outputSchema: Tool.Output                 |
|  + validateParams(params)                    |
|  + executeInternal(params, context)          |
|  # successResponse(), errorResponse()        |
|  # requireString(), optionalBoolean(), etc.  |
+----------------------------------------------+
                    |
    +---------------+-----------------+
    |               |                 |
+-----------+ +------------+ +-------------+
|QueryCon-  | |ManageCon-  | |RequestTran- |
|tainerTool | |tainerTool  | |sitionTool   |
+-----------+ +------------+ +-------------+
```

### Tool Categories

```kotlin
enum class ToolCategory(val value: String) {
    TASK_MANAGEMENT,
    FEATURE_MANAGEMENT,
    TEMPLATE_MANAGEMENT,
    PROJECT_MANAGEMENT,
    SECTION_MANAGEMENT,
    COMPLEXITY_ANALYSIS,
    TASK_EXPANSION,
    DEPENDENCY_MANAGEMENT,
    QUERY_AND_FILTER,
    REPORTING,
    SYSTEM
}
```

### Tool Execution Context

All tools receive a `ToolExecutionContext` for dependency injection:

```kotlin
class ToolExecutionContext(
    private val repositoryProvider: RepositoryProvider,
    private val statusProgressionService: StatusProgressionService? = null,
    private val cascadeService: CascadeService? = null
) {
    fun taskRepository(): TaskRepository
    fun featureRepository(): FeatureRepository
    fun projectRepository(): ProjectRepository
    fun sectionRepository(): SectionRepository
    fun templateRepository(): TemplateRepository
    fun dependencyRepository(): DependencyRepository
    fun roleTransitionRepository(): RoleTransitionRepository
    fun statusProgressionService(): StatusProgressionService?
    fun cascadeService(): CascadeService?
}
```

### Consolidated Tool Pattern (v2.0)

Tools use a unified `operation` parameter instead of individual CRUD tools:

```
// Read operations (query_container)
query_container(operation="get", containerType="task", id="uuid")
query_container(operation="search", containerType="feature", query="auth")
query_container(operation="overview", containerType="project", id="uuid")
query_container(operation="export", containerType="feature", id="uuid")

// Write operations (manage_container)
manage_container(operation="create", containerType="task",
    containers=[{title: "...", ...}], projectId="uuid", templateIds=["uuid"])
manage_container(operation="update", containerType="task",
    containers=[{id: "uuid", status: "in-progress"}])
manage_container(operation="delete", containerType="task", ids=["uuid1", "uuid2"])
```

**Key design decisions**:
- **Array parameters for batch support**: `containers` array for create/update, `ids` array for delete (max 100 items)
- **Shared defaults**: Top-level `projectId`, `featureId`, `templateIds`, `tags` inherited by all items in create
- **Read/write separation**: Separate query and manage tools per domain area
- **Tool hints**: Read-only tools marked `readOnlyHint=true`, `idempotentHint=true`

### Tool Lifecycle

1. **Registration** -- Tool instances created in `McpServer.createTools()`
2. **Adaptation** -- `McpToolAdapter` registers each tool with MCP SDK
3. **Request** -- MCP SDK receives call, routes to adapter
4. **Preprocessing** -- Adapter normalizes parameters (e.g., string booleans to native)
5. **Validation** -- Tool's `validateParams()` checks required fields and types
6. **Execution** -- Tool's `execute()` runs business logic with `ToolExecutionContext`
7. **Response** -- Structured JSON wrapped in `CallToolResult` returned to client

---

## Status Progression System

The status progression system provides workflow-aware status management with semantic roles, named triggers, and automatic cascade propagation.

### Architecture

```
+---------------------------------------------+
|  RequestTransitionTool                       |
|  - Accepts named triggers (start, complete)  |
|  - Validates via StatusProgressionService    |
|  - Applies transition                        |
|  - Fires CascadeService                     |
+---------------------------------------------+
          |                    |
          v                    v
+-------------------+ +-------------------+
| StatusProgression | | CascadeService    |
| ServiceImpl       | | Impl              |
| - Reads config    | | - Detects cascade |
| - Maps status->   | |   events          |
|   role            | | - Auto-advances   |
| - Validates flow  | |   parent entities |
|   sequences       | | - Recursive depth |
+-------------------+ +-------------------+
          |                    |
          v                    v
+-------------------+ +-------------------+
| default-config    | | RoleTransition    |
| .yaml             | | Repository        |
| - Status flows    | | - Audit trail     |
| - Role mappings   | | - Timestamps      |
| - Validation      | | - Triggers        |
|   rules           | |                   |
+-------------------+ +-------------------+
```

### Status Roles

Every status maps to a semantic role that represents a workflow phase:

| Role | Ordinal | Meaning |
|------|---------|---------|
| QUEUE | 0 | Waiting to be worked on (backlog, pending, planning, draft) |
| WORK | 1 | Actively being worked on (in-progress, in-development) |
| REVIEW | 2 | Under review or testing (in-review, testing, validating) |
| TERMINAL | 3 | Final state (completed, cancelled, archived, deployed) |
| BLOCKED | -1 | Cannot proceed (blocked, on-hold) |

### Workflow Flows

Flows define the valid status progression sequence for an entity. Tags on the entity select which flow applies:

**Task Flows**:
- **default_flow**: backlog -> pending -> in-progress -> completed
- **bug_fix_flow**: (tags: bug, bugfix, fix) investigating -> in-progress -> testing -> completed
- **hotfix_flow**: (tags: hotfix, emergency, urgent) pending -> in-progress -> completed
- **with_review_flow**: pending -> in-progress -> in-review -> completed
- **with_testing_flow**: (tags: qa-required, manual-test) pending -> in-progress -> testing -> completed

**Feature Flows**:
- **default_flow**: draft -> planning -> in-development -> testing -> validating -> completed
- **rapid_prototype_flow**: (tags: prototype, poc, spike) draft -> in-development -> completed

**Project Flows**:
- **default_flow**: planning -> in-development -> completed -> archived
- **with_deploy_flow**: planning -> in-development -> completed -> deployed -> archived

### Named Triggers

Status transitions use named triggers instead of raw status values:

| Trigger | Effect |
|---------|--------|
| `start` | Progress to next status in the active flow |
| `complete` | Move to terminal `completed` status (validates prerequisites) |
| `cancel` | Emergency transition to `cancelled` |
| `block` | Emergency transition to `blocked` |
| `hold` | Emergency transition to `on-hold` |

### Auto-Cascade

When a task completes, `CascadeService` checks whether all sibling tasks in the parent feature are done. If so, the feature is automatically advanced. The same logic applies up to the project level. Cascade depth is configurable (default max: 3).

### Role Transition Audit Trail

Every status change records a `RoleTransition` entry with:
- Previous and new role (e.g., QUEUE -> WORK)
- Previous and new status (e.g., pending -> in-progress)
- Trigger that caused the transition
- Timestamp
- Optional summary

Queryable via `query_role_transitions` for workflow analytics.

---

## Template System

### Architecture

```
Template Initialization (Startup)
        |
        v
+------------------------+
| TemplateInitializerImpl|
|  - 13 built-in creators|
+------------------------+
        |
        v
+------------------------+
| Template Creators      |
|  (13 templates)        |
|  - FeaturePlan         |
|  - Requirements        |
|  - TechnicalApproach   |
|  - TaskImplementation  |
|  - TestingStrategy     |
|  - DefinitionOfDone    |
|  - BugInvestigation    |
|  - CodebaseExploration |
|  - ContextBackground   |
|  - LocalGitBranching   |
|  - GitHubPRWorkflow    |
|  - DesignDecision      |
|  - ImplementationSpec  |
+------------------------+
        |
        v
+------------------------+       +------------------------+
| TemplateRepository     |       | CachedTemplate         |
|  (with cache decorator)|  <--  | Repository             |
+------------------------+       +------------------------+
        |
        v
+------------------------+
| Database               |
|  - templates table     |
|  - sections table      |
+------------------------+
```

### Template Application Flow

```
1. AI discovers templates
   query_templates(operation="list", targetEntityType="TASK", isEnabled=true)
        |
        v
2. AI selects and applies during creation
   manage_container(operation="create", containerType="task",
       containers=[{title: "..."}], templateIds=["uuid1", "uuid2"])
        |
        v
3. Or applies post-creation
   apply_template(entityType="TASK", entityId="uuid", templateIds=["uuid1"])
        |
        v
4. ApplyTemplateTool
   - Validates entity and templates exist
   - Creates sections from template definitions
   - Maintains ordinal sequence across templates
```

### Template Structure

```kotlin
data class Template(
    val id: UUID,
    val name: String,
    val description: String,
    val targetEntityType: TemplateTargetType,  // TASK or FEATURE
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
    val contentFormat: ContentFormat,  // MARKDOWN, PLAIN_TEXT, JSON, CODE
    val ordinal: Int,
    val isRequired: Boolean
)
```

---

## AI Guidance System

The MCP server provides guidance to connected AI agents through standard MCP protocol features: `server.instructions`, MCP Resources, and tool descriptions. This guidance is **client-agnostic** -- any MCP client can consume it.

### Three-Layer Progressive Disclosure

```
Layer 1: Server Instructions (server.instructions, ~80 tokens)
   - Sent automatically on MCP connection
   - Version marker: <!-- mcp-task-orchestrator-setup: v3 -->
   - Directs agents to Layer 2 resource if their project
     instructions file is missing or outdated
        |
        v
Layer 2: MCP Resources (on-demand, ~600 tokens each)
   - task-orchestrator://guidelines/setup-instructions
   - task-orchestrator://guidelines/usage-overview
   - task-orchestrator://guidelines/template-strategy
   - task-orchestrator://guidelines/task-management-patterns
   - task-orchestrator://guidelines/workflow-integration
        |
        v
Layer 3: Tool Documentation Resources (on-demand)
   - task-orchestrator://docs/tools/{tool-name}
   - 14 resources (one per tool)
   - Loaded from docs/tools/*.md at runtime
```

**Design rationale**: Layer 1 is always loaded (minimal tokens). Layer 2 is fetched only when setup is needed. Layer 3 is fetched only when an agent needs detailed tool guidance. This progressive disclosure minimizes token usage while ensuring agents can self-configure.

**Client compatibility**: All three layers use standard MCP protocol features (`server.instructions` and `resources/read`). Any MCP client that supports resources can access them. The `setup-instructions` resource generates a block of workflow rules that the agent should add to its project instructions file (e.g., `CLAUDE.md` for Claude Code, `.cursorrules` for Cursor, or equivalent).

### MCP Resources

| Resource URI | Purpose |
|-------------|---------|
| `task-orchestrator://guidelines/setup-instructions` | Full agent instructions template for the project's AI instructions file |
| `task-orchestrator://guidelines/usage-overview` | When and why to use Task Orchestrator |
| `task-orchestrator://guidelines/template-strategy` | Template patterns and application |
| `task-orchestrator://guidelines/task-management-patterns` | Task workflow patterns |
| `task-orchestrator://guidelines/workflow-integration` | Integration with external systems |
| `task-orchestrator://docs/tools/*` | Per-tool documentation (14 resources) |
| `task-orchestrator://markdown-views/guide` | Guide for markdown-formatted entity exports |

---

## Core Components

### Database Manager

**Location**: `infrastructure/database/DatabaseManager.kt`

**Responsibilities**:
- SQLite connection management
- Schema initialization and updates
- Transaction configuration (SERIALIZABLE isolation)
- Foreign key constraint enforcement
- Graceful shutdown

**Configuration (environment variables)**:

| Variable | Default | Purpose |
|----------|---------|---------|
| `DATABASE_PATH` | `data/tasks.db` | SQLite database file path |
| `USE_FLYWAY` | `true` (Docker) | Enable Flyway migrations |
| `LOG_LEVEL` | `INFO` | Logging verbosity |
| `AGENT_CONFIG_DIR` | Current directory | Directory containing `.taskorchestrator/` config folder |
| `DATABASE_MAX_CONNECTIONS` | `10` | Connection pool size |
| `DATABASE_SHOW_SQL` | `false` | Log SQL statements |
| `MCP_SERVER_NAME` | `mcp-task-orchestrator` | Custom server name for MCP identity |
| `FLYWAY_REPAIR` | `false` | Run Flyway repair and exit |

**Schema Management Strategies**:
1. **Flyway** (`USE_FLYWAY=true`) -- Production recommended. 14 versioned SQL migrations (V1-V14) with history tracking.
2. **Direct** (`USE_FLYWAY=false`) -- Development/testing. Exposed ORM schema updates for faster iteration.

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
    suspend fun getNextRecommendedTask(...): Result<List<Task>>
}
```

**Implementation** (Infrastructure Layer):
```kotlin
class SQLiteTaskRepository(
    private val databaseManager: DatabaseManager
) : TaskRepository {
    override suspend fun findById(id: UUID): Result<Task?> =
        transaction {
            TaskTable.select { TaskTable.id eq id }
                .map { rowToTask(it) }
                .firstOrNull()
        }.toResult()
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
    fun roleTransitionRepository(): RoleTransitionRepository
}

class DefaultRepositoryProvider(
    private val databaseManager: DatabaseManager
) : RepositoryProvider {
    // Lazy initialization for all repositories
    private val taskRepository: SQLiteTaskRepository by lazy {
        SQLiteTaskRepository(databaseManager)
    }
    // ...
}
```

---

### Shutdown Coordinator

**Location**: `Main.kt`

Handles graceful server shutdown:
- Registers OS signal handlers (SIGTERM, SIGINT)
- Installs JVM shutdown hook as fallback
- Coordinates closing of MCP server and database connections
- Prevents duplicate shutdown execution

---

## Data Flow

### Tool Execution Flow

```
1. AI Agent sends MCP Request
        |
        v
2. MCP SDK Server (receives request)
        |
        v
3. McpToolAdapter
   - preprocesses parameters (string booleans, etc.)
        |
        v
4. Tool.validateParams()
   - checks required fields, types, constraints
        |
        v
5. Tool.execute(params, context)
   |
   |--> context.taskRepository()
   |--> context.statusProgressionService()
   |--> context.cascadeService()
   |        |
   |        v
   |    Repository / Service Implementation
   |        |
   |        v
   |    Database Query/Update
   |        |
   |        v
   |    Result<T>
   |
   v
6. Tool returns JsonElement (success or error response)
        |
        v
7. McpToolAdapter wraps in CallToolResult
   - Extracts structuredContent
   - Generates userSummary
        |
        v
8. MCP SDK returns to AI Agent
```

### Status Transition Flow

```
1. AI calls request_transition(trigger="start")
        |
        v
2. RequestTransitionTool
   - Resolves entity (task/feature/project)
   - Gets current status
        |
        v
3. StatusProgressionService
   - Determines active flow from entity tags
   - Maps trigger to target status
   - Validates flow sequence
   - Checks prerequisites (dependencies, verification)
        |
        v
4. Status updated in repository
        |
        v
5. RoleTransition recorded (audit trail)
        |
        v
6. CascadeService (if task completion)
   - Checks sibling task statuses
   - If all terminal -> advances parent feature
   - Recursive: feature completion -> project advancement
        |
        v
7. Response includes:
   - previousStatus, newStatus
   - previousRole, newRole
   - activeFlow, flowSequence, flowPosition
   - cascadeEvents (with applied=true/false)
   - unblockedTasks (downstream tasks now unblocked)
```

### Template Application Flow

```
1. AI calls apply_template(entityId, templateIds)
        |
        v
2. ApplyTemplateTool validates
   - Check entity exists
   - Check templates exist and are enabled
   - Check template targets match entity type
        |
        v
3. For each template (in order):
   |
   |--> Get template sections
   |
   |--> For each section:
   |    - Create Section entity
   |    - Link to target entity
   |    - Set ordinal for ordering
   |    - Save to database
        |
        v
4. Return success with section count and details
```

---

## Design Patterns

### 1. Clean Architecture

**Layers**: Domain -> Application -> Infrastructure -> Interface

**Benefits**: Framework independence, testability, flexibility to change implementations.

### 2. Repository Pattern

**Purpose**: Abstract data access from business logic. Domain defines interfaces, infrastructure implements with SQLite/Exposed.

### 3. Adapter Pattern

**Location**: `McpToolAdapter`

**Purpose**: Bridge application tools to MCP protocol without coupling the application layer to the MCP SDK.

### 4. Dependency Injection

**Implementation**: `ToolExecutionContext`

```kotlin
class ToolExecutionContext(
    private val repositoryProvider: RepositoryProvider,
    private val statusProgressionService: StatusProgressionService? = null,
    private val cascadeService: CascadeService? = null
)
```

Tools receive the context at execution time, enabling test isolation with mock repositories.

### 5. Factory Pattern

**Location**: `SchemaManagerFactory`

Creates the appropriate schema manager (Flyway or Direct) based on configuration.

### 6. Strategy Pattern

**Location**: Schema management

Two interchangeable strategies: `FlywayDatabaseSchemaManager` (production) and `DirectDatabaseSchemaManager` (development).

### 7. Template Method Pattern

**Location**: `BaseToolDefinition`

```kotlin
abstract class BaseToolDefinition {
    // Template method (in SimpleLockAwareToolDefinition)
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        return try {
            validateParams(params)
            executeInternal(params, context)
        } catch (e: ToolValidationException) { errorResponse(...) }
          catch (e: LockException) { errorResponse(...) }
          catch (e: Exception) { errorResponse(...) }
    }

    abstract fun validateParams(params: JsonElement)
    abstract suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement
}
```

### 8. Result Pattern

**Location**: `domain/repository/Result.kt`

```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val error: Exception) : Result<Nothing>()
}
```

Type-safe error handling with explicit error states and no exceptions in the happy path.

### 9. Decorator Pattern

**Location**: `CachedTemplateRepository`

Wraps `SQLiteTemplateRepository` to provide caching for frequently accessed templates.

---

## Technology Stack

### Core Technologies

| Technology | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 2.2.0 | Language with coroutines for async operations |
| MCP SDK | 0.8.3 | Model Context Protocol implementation |
| Exposed ORM | 1.0.0-beta-2 | Type-safe Kotlin SQL framework |
| SQLite (JDBC) | 3.45.1.0 | Embedded database |
| Flyway | 10.22.0 | Database migration management |
| kotlinx.serialization | 1.8.0 | JSON serialization |
| kotlinx.coroutines | 1.8.0 | Asynchronous programming |
| SLF4J | 2.0.17 | Logging API |
| Logback | 1.5.28 | Logging implementation |
| SnakeYAML | 2.5 | YAML configuration parsing |

### Build System

| Component | Details |
|-----------|---------|
| Gradle | Kotlin DSL |
| JVM Toolchain | Java 21 |
| Fat JAR | Shadow-style with dependency merging |
| Version Format | `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}` |
| Current Version | 2.0.0.{N}-beta-01 |

### Testing

| Technology | Version | Purpose |
|-----------|---------|---------|
| JUnit 5 | 5.10.2 | Test framework |
| MockK | 1.14.2 | Kotlin-native mocking |
| H2 Database | 2.2.224 | In-memory database for repository tests |
| Mockito Kotlin | 5.2.1 | Additional mocking support |

### Deployment

- Docker containerization
- Docker Compose for development
- GitHub Container Registry (ghcr.io)
- CI/CD via GitHub Actions

---

## Extension Points

### Adding a New MCP Tool

1. **Create tool class** extending `SimpleLockAwareToolDefinition` (or `BaseToolDefinition`):

```kotlin
package io.github.jpicklyk.mcptask.application.tools.task

class MyNewTool : SimpleLockAwareToolDefinition() {
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

    override val outputSchema = Tool.Output(...)

    override fun validateParams(params: JsonElement) {
        requireString(params, "param1")
    }

    override suspend fun executeInternal(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val param1 = requireString(params, "param1")
        val repository = context.taskRepository()
        // Business logic...
        return successResponse(
            data = buildJsonObject { },
            message = "Operation completed"
        )
    }
}
```

2. **Register** in `McpServer.createTools()`:

```kotlin
private fun createTools(): List<ToolDefinition> {
    return listOf(
        // ... existing tools
        MyNewTool(),
    )
}
```

3. **Add tests** in `src/test/kotlin/application/tools/`

4. **(Optional)** Add tool documentation markdown at `docs/tools/my-new-tool.md` for the `ToolDocumentationResources` system

---

### Adding a Repository Method

1. **Add method** to repository interface (domain layer):

```kotlin
interface TaskRepository : ProjectScopedRepository<Task, TaskStatus, Priority> {
    suspend fun findByCustomCriteria(criteria: String): Result<List<Task>>
}
```

2. **Implement** in SQLite repository (infrastructure layer):

```kotlin
override suspend fun findByCustomCriteria(criteria: String): Result<List<Task>> =
    transaction {
        TaskTable.select { TaskTable.customField eq criteria }
            .map { rowToTask(it) }
    }.toResult()
```

3. **Use** in tools via `context.taskRepository().findByCustomCriteria(criteria)`

---

### Adding a New Template

1. **Create template creator** in `application/service/templates/`:

```kotlin
object MyCustomTemplateCreator {
    fun create(): Pair<Template, List<TemplateSection>> {
        val template = Template(
            name = "My Custom Template",
            description = "Template for custom scenarios",
            targetEntityType = TemplateTargetType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            tags = listOf("custom", "workflow")
        )
        val sections = listOf(
            TemplateSection(
                templateId = template.id,
                title = "Section 1",
                usageDescription = "How AI should use this",
                contentSample = "Sample content...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true
            )
        )
        return template to sections
    }
}
```

2. **Register** in `TemplateInitializerImpl.kt` (add to when statement and initialization list)

---

### Adding a Database Migration

**Flyway (Production)**:

Create `src/main/resources/db/migration/V{N}__{Description}.sql` with the next sequential version number:

```sql
-- V15__add_custom_field.sql
ALTER TABLE tasks ADD COLUMN custom_field TEXT;
CREATE INDEX idx_tasks_custom_field ON tasks(custom_field);
```

Server auto-applies on restart.

**Direct (Development)**:

Update table definition in `infrastructure/database/schema/` and `DirectDatabaseSchemaManager.kt`.

See [database-migrations.md](database-migrations.md) for detailed patterns and examples.

---

# Part 2: Client-Specific Extensions

---

## Claude Code Plugin

The Claude Code plugin is a **client-side extension** that enhances how Claude Code interacts with the MCP Task Orchestrator server. It does not modify the server -- it wraps the 14 MCP tools with coordination patterns, workflow enforcement, and output formatting specific to Claude Code.

**Location**: `claude-plugins/task-orchestrator/`

**Important**: The MCP server works without this plugin. The plugin adds convenience and discipline for Claude Code users, but every operation it performs uses standard MCP tool calls that any client could make.

### Plugin Manifest

```
claude-plugins/task-orchestrator/
+-- .claude-plugin/
|   +-- plugin.json          # Manifest: name, version, entry points
+-- skills/                   # Tier 2: Lightweight coordination workflows
+-- hooks/                    # Tier 3: Event-driven automation
+-- output-styles/            # Tier 4: Response formatting
+-- scripts/                  # Supporting Node.js scripts for hooks
```

**Manifest** (`plugin.json`):
- **Name**: `task-orchestrator`
- **Version**: 2.0.0
- **Entry points**: `skills` -> `./skills/`, `outputStyles` -> `./output-styles/`, `hooks` -> `./hooks/session-hooks.json`

### Skills (8 skills)

Skills are lightweight coordination workflows (2-5 MCP tool calls) that Claude Code auto-discovers from `SKILL.md` files. They provide workflow guidance without requiring subagent delegation.

| Skill | Purpose | MCP Tools Used |
|-------|---------|----------------|
| `feature-orchestration` | Feature lifecycle, task breakdown, completion workflows | query_container, manage_container, request_transition |
| `task-orchestration` | Task creation, status progression, section management | manage_container, request_transition, query_templates |
| `dependency-analysis` | Blocker identification, chain resolution, graph traversal | query_dependencies, get_blocked_tasks, manage_dependencies |
| `status-progression` | Workflow navigation, trigger validation, role mapping | get_next_status, request_transition, query_role_transitions |
| `project-orchestration` | Project lifecycle, cross-feature tracking | query_container, manage_container |
| `project-summary` | Read-only dashboard generation | query_container (overview) |
| `template-management` | Template creation, section configuration, application | query_templates, manage_template, apply_template |
| `setup-instructions` | Generate agent instructions block for new projects | MCP resource read |

Each skill's `SKILL.md` contains YAML frontmatter (name, description, allowed-tools) and workflow instructions. Claude Code reads the frontmatter for discovery and loads the full content only when the skill is activated.

### Hooks (5 hooks)

Hooks are event-driven scripts that execute automatically in response to Claude Code lifecycle events. They enforce workflow discipline and inject context.

| Event | Matcher | Type | Purpose |
|-------|---------|------|---------|
| `PreToolUse` | `EnterPlanMode` | command | Inject planning workflow guidance via `additionalContext` |
| `PreToolUse` | `manage_container` | prompt | Block `update` with status field -- enforce `request_transition` for status changes |
| `PostToolUse` | `ExitPlanMode` | command | Inject materialization instructions (create MCP containers after plan approval) |
| `SessionStart` | (all) | command | Validate MCP setup instructions are current |
| `SubagentStart` | `*` | command | Inject workflow context (status flows, dependency rules) to subagents |

**Hook types**:
- `command` -- Runs a Node.js script, can inject `additionalContext` into Claude's context
- `prompt` -- Sends prompt to a small LLM for guardrail decisions (ok/block)

**Scripts** (`scripts/`):
- `pre-planning.mjs` -- Planning phase guidance injection
- `post-planning.mjs` -- Plan-to-materialization transition
- `session-setup-check.mjs` -- MCP configuration validation
- `subagent-workflow-context.mjs` -- Subagent workflow context injection

### Output Style

A single output style (`output-styles/orchestrator.md`) provides an orchestrator persona that:
- Never implements directly -- delegates to subagents
- Queries MCP state before making decisions
- Uses `EnterPlanMode` for non-trivial features
- Produces dashboards with unicode status symbols
- Invokes `/project-summary` skill at session start

### Relationship to MCP Server

```
+---------------------------------------------------------------+
|  Claude Code Plugin (client-side, optional)                    |
|                                                                |
|  Skills: "What should I work on?"                             |
|    -> get_next_task() --------+                                |
|                               |                                |
|  Hooks: "Block direct status  |   Standard MCP                |
|          changes"             |   tool calls                   |
|    -> (blocks manage_container|                                |
|        with status field)     |                                |
|                               |                                |
|  Output Style: "Show me a    |                                |
|    project dashboard"         |                                |
|    -> query_container() ------+                                |
+---------------------------------------------------------------+
                                |
                         MCP Protocol (stdio)
                                |
+---------------------------------------------------------------+
|  MCP Server (client-agnostic)                                  |
|                                                                |
|  14 tools + resources + prompts                                |
|  Same behavior regardless of which client connects             |
+---------------------------------------------------------------+
```

**Key principle**: The plugin calls the same MCP tools that any other client would call. It adds coordination logic, not new server capabilities. If a workflow pattern proves universally useful, it should be promoted into the MCP server (as a new tool, resource, or prompt) rather than remaining plugin-specific.

### Building Plugins for Other Clients

The Claude Code plugin serves as a reference implementation. To build a plugin for another MCP client (e.g., Cursor, Windsurf):

1. **Consult MCP resources** -- Read `task-orchestrator://guidelines/setup-instructions` for the workflow rules your plugin should enforce
2. **Map to client features** -- Translate skills -> your client's macro/snippet system; hooks -> your client's event system; output styles -> your client's prompt/persona system
3. **Call the same tools** -- All 14 MCP tools work identically regardless of client
4. **No server changes needed** -- Client plugins are purely additive

---

## Additional Resources

### Developer Documentation
- [Database Migrations](database-migrations.md) -- Schema management and migration patterns
- [Contributing Guide](../index.md#contributing) -- Contribution guidelines and pull request process

### User Documentation
- [Quick Start](../quick-start.md) -- Getting started guide
- [AI Guidelines](../ai-guidelines.md) -- How AI uses Task Orchestrator
- [API Reference](../api-reference.md) -- Complete MCP tools documentation
- [Status Progression](../status-progression.md) -- Status workflow guide with examples
- [Templates Guide](../templates.md) -- Template system and usage

### External Resources
- [Model Context Protocol](https://modelcontextprotocol.io) -- MCP specification and documentation
- [Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) -- MCP Kotlin SDK
- [Exposed ORM](https://github.com/JetBrains/Exposed) -- Kotlin SQL framework
- [Flyway](https://documentation.flyway.org) -- Database migration tool

---

**Questions?** Open an issue on [GitHub](https://github.com/jpicklyk/task-orchestrator/issues) or check the [Community Discussions](https://github.com/jpicklyk/task-orchestrator/discussions).
