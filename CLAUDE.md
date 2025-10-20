ex# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MCP Task Orchestrator is a Kotlin-based Model Context Protocol (MCP) server that provides comprehensive task management capabilities for AI assistants. It implements a hierarchical task management system (Projects → Features → Tasks) with dependency tracking, templates, and workflow automation.

**Key Technologies:**
- Kotlin 2.2.0 with Coroutines
- Exposed ORM v1 for SQLite database
- MCP SDK 0.7.2 for protocol implementation
- Flyway for database migrations
- Gradle with Kotlin DSL
- Docker for deployment

## Build Commands

### Local Development
```bash
# Build the project (creates fat JAR)
./gradlew build

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "ClassName"

# Run all migration tests
./gradlew test --tests "*migration*"

# Clean build
./gradlew clean build

# Run locally (after build)
java -jar build/libs/mcp-task-orchestrator-*.jar

# Run with environment variables
DATABASE_PATH=data/tasks.db USE_FLYWAY=true MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-*.jar
```

### Docker Development
```bash
# Build Docker image (from project root)
docker build -t mcp-task-orchestrator:dev .

# Run Docker container
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator:dev

# Clean and rebuild Docker
./scripts/docker-clean-and-build.bat

# Debug with logs
docker run --rm -i -v mcp-task-data:/app/data -e MCP_DEBUG=true mcp-task-orchestrator:dev
```

## Architecture

The codebase follows **Clean Architecture** with four distinct layers:

### 1. Domain Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/domain/`)
- **Pure business logic**, framework-agnostic
- `model/` - Core entities (Task, Feature, Project, Template, Section, Dependency)
- `repository/` - Repository interfaces defining data access contracts
- No dependencies outside Kotlin stdlib and kotlinx.serialization

### 2. Application Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/application/`)
- **Business logic orchestration and use cases**
- `tools/` - MCP tool implementations organized by category:
  - `task/` - Task management
  - `feature/` - Feature management
  - `project/` - Project management
  - `template/` - Template management
  - `section/` - Section management
  - `dependency/` - Dependency management
- `service/` - Services like TemplateInitializer
- `service/templates/` - 9 built-in template creators

### 3. Infrastructure Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/`)
- **External concerns and framework implementations**
- `database/` - DatabaseManager, schema management
- `database/repository/` - SQLite repository implementations
- `database/schema/` - Exposed ORM table definitions
- `database/migration/` - Flyway migration management
- `util/` - ErrorCodes, logging utilities

### 4. Interface Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/`)
- **MCP protocol adaptation**
- `McpServer.kt` - Main server, tool registration, lifecycle
- `McpToolAdapter.kt` - Bridges tools to MCP protocol
- `McpServerAiGuidance.kt` - AI guidance configuration
- `TaskOrchestratorResources.kt` - MCP Resources for AI
- `WorkflowPromptsGuidance.kt` - Workflow automation prompts

**Entry Point:** `src/main/kotlin/Main.kt`

## Key Design Patterns

1. **Repository Pattern** - Domain defines interfaces, infrastructure implements
2. **Adapter Pattern** - McpToolAdapter separates MCP protocol from business logic
3. **Dependency Injection** - ToolExecutionContext provides repositories to tools
4. **Factory Pattern** - SchemaManagerFactory creates appropriate schema manager
5. **Template Method** - BaseToolDefinition provides structure for all tools
6. **Result Pattern** - Type-safe error handling with `Result<T>` sealed class

## Adding New Components

### Adding a New MCP Tool

1. Create tool class extending `BaseToolDefinition` in `application/tools/`
2. Implement `validateParams()` and `execute()` methods
3. Register in `McpServer.createTools()`
4. Add tests in `src/test/kotlin/application/tools/`

See existing tools for patterns.

### Adding a Database Migration

**Flyway (Production):** Create `src/main/resources/db/migration/V{N}__{Description}.sql` with sequential numbering. Server auto-applies on restart.

**Direct (Development):** Update schema in `infrastructure/database/schema/` and `DirectDatabaseSchemaManager.kt`

See [database-migrations.md](docs/developer-guides/database-migrations.md) for patterns and examples.

### Adding a New Template

1. Create template creator in `application/service/templates/` following existing patterns
2. Register in `TemplateInitializerImpl.kt` (add to when statement and initialization list)

### Adding a Repository Method

1. Add method to repository interface in `domain/repository/`
2. Implement in SQLite repository in `infrastructure/database/repository/`
3. Use in tools via `context.{repository}()`

## Database Management

**Environment Variables:**
- `DATABASE_PATH` - SQLite database file path (default: `data/tasks.db`)
- `USE_FLYWAY` - Enable Flyway migrations (default: `true` in Docker)
- `MCP_DEBUG` - Enable debug logging

**Schema Management:**
- **Flyway** (Production) - Versioned SQL migrations with history tracking
- **Direct** (Development) - Exposed ORM schema updates for faster iteration

**Migration Files:** `src/main/resources/db/migration/`

## Dependency Management

Add new dependencies via `gradle/libs.versions.toml`:
1. Add version to `[versions]` section
2. Add library to `[libraries]` with version reference
3. Reference in `build.gradle.kts` as `libs.{name}`

## Testing

**Test Structure:**
- Tests mirror main source structure under `src/test/kotlin/`
- Use JUnit 5 with Kotlin Test
- MockK for mocking
- H2 in-memory database for repository tests

**Running Tests:**
```bash
./gradlew test                              # All tests
./gradlew test --tests "ClassName"          # Specific class
./gradlew test --tests "*migration*"        # Migration tests
./gradlew test --tests "*ToolTest"          # All tool tests
```

## Version Management

Format: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`
Edit version in `build.gradle.kts` (majorVersion, minorVersion, patchVersion, qualifier)

## Common File Locations

- **Main entry:** `src/main/kotlin/Main.kt`
- **MCP Server:** `src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/McpServer.kt`
- **Tool definitions:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/`
- **Domain models:** `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/`
- **Repositories:** `src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/repository/`
- **Database schema:** `src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/schema/`
- **Migrations:** `src/main/resources/db/migration/`
- **Templates:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/templates/`
- **Tests:** `src/test/kotlin/` (mirrors main structure)

## Tool Development Guidelines

**Tool Implementation Checklist:**
1. Extend `BaseToolDefinition` or `SimpleLockAwareToolDefinition`
2. Define clear parameter and output schemas
3. Implement `validateParams()` with proper validation
4. Implement `execute()` with business logic
5. Use `successResponse()` and `errorResponse()` helpers
6. Handle `Result<T>` from repositories properly
7. Add comprehensive tests
8. Register in `McpServer.createTools()`
9. Document in tool description for AI agents

**Tool Categories:**
- TASK_MANAGEMENT - Task CRUD operations
- FEATURE_MANAGEMENT - Feature CRUD operations
- PROJECT_MANAGEMENT - Project CRUD operations
- TEMPLATE_MANAGEMENT - Template operations
- SECTION_MANAGEMENT - Section content operations
- DEPENDENCY_MANAGEMENT - Task dependency operations

**Consolidated Tools (v2.0+):**

Task Orchestrator v2.0 introduces **unified container-based tools** that reduce token overhead by 68%:

- **`manage_container`** - Unified write operations for projects, features, and tasks
  - Operations: create, update, delete, setStatus, bulkUpdate
  - Usage: `{"operation": "create", "containerType": "task", "title": "...", ...}`
  - Replaces: 13 individual CRUD tools (create_task, get_task, update_task, delete_task, create_feature, get_feature, update_feature, delete_feature, create_project, get_project, update_project, delete_project, set_status)

- **`query_container`** - Unified read operations for projects, features, and tasks
  - Operations: get, search, export, overview
  - Usage: `{"operation": "search", "containerType": "task", "status": "pending", ...}`
  - Replaces: 9 individual query tools (search_tasks, search_features, search_projects, get_task, get_feature, get_project, task_to_markdown, feature_to_markdown, project_to_markdown)

- **`manage_sections`** - Unified section operations
  - Operations: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete
  - Replaces: 7 individual section tools

- **`query_sections`** - Unified section queries with filtering
  - Replaces: get_sections tool

- **Workflow Optimization Tools** (unchanged, NOT consolidated):
  - **`get_next_task`** - Intelligent task recommendation with dependency checking and priority sorting
  - **`get_blocked_tasks`** - Dependency blocking analysis
  - These tools contain complex recommendation logic that cannot be replaced by simple query_container filters

**Token Savings:** ~84k → ~36k characters (68% reduction) across all 56 → 18 tools

**Migration:** v1 tools were removed in v2.0. See [migration guide](docs/migration/v2.0-migration-guide.md).

**IMPORTANT:** Always use `operation` and `containerType` parameters with v2 consolidated tools.

### Scoped Overview Pattern (v2.0+)

**Overview operations provide hierarchical views without section content**, optimizing for token efficiency in detail queries.

**When to Use:**
- User asks: "Show me details on [specific entity]"
- User asks: "What's the status of [entity]?"
- User asks: "What tasks are in [feature]?"
- Need hierarchical view without section content

**How to Use:**

```javascript
// For "Show me Feature X details"
query_container(
  operation="overview",
  containerType="feature",
  id="feature-uuid"
)
// Returns: feature metadata + tasks list + task counts (NO sections)
// Token efficient: ~1,200 tokens vs ~18,500 with sections

// For "What's in Project Y?"
query_container(
  operation="overview",
  containerType="project",
  id="project-uuid"
)
// Returns: project metadata + features list + task counts

// For "List all features" (global overview)
query_container(
  operation="overview",
  containerType="feature"
)
// Returns: array of all features with minimal fields (NO child entities)
```

**Critical Efficiency Rule:**
- ❌ DON'T: Use `get` with `includeSections=true` for "show details" queries
- ✅ DO: Use scoped `overview` for hierarchical view without sections
- ✅ ONLY use `get` with sections when user specifically asks for documentation/section content

**Token Savings:**
- Feature with 10 sections + 20 tasks: 18.5k → 1.2k tokens (93% reduction)
- Project with 3 features: 30k+ → 1.5k tokens (95% reduction)

**Tool Selection Decision Tree:**

**User Intent: "Show me details/status/overview of X"**
1. Extract entity type (project, feature, task) and ID
2. Use scoped overview:
   ```
   query_container(operation="overview", containerType="...", id="...")
   ```
3. DO NOT use `get` with `includeSections=true` unless user explicitly wants documentation

**User Intent: "List all features/projects/tasks"**
1. Use global overview:
   ```
   query_container(operation="overview", containerType="...")
   ```
2. No id parameter needed

**User Intent: "Show me the full documentation for Feature X"**
1. NOW use get with sections:
   ```
   query_container(operation="get", containerType="feature", id="...", includeSections=true)
   ```

## Documentation

**Developer Guides:** `docs/developer-guides/`
- [architecture.md](docs/developer-guides/architecture.md) - Comprehensive architecture guide
- [database-migrations.md](docs/developer-guides/database-migrations.md) - Migration management

**User Documentation:** `docs/`
- [quick-start.md](docs/quick-start.md) - Getting started
- [ai-guidelines.md](docs/ai-guidelines.md) - How AI uses Task Orchestrator
- [api-reference.md](docs/api-reference.md) - Complete MCP tools documentation
- [templates.md](docs/templates.md) - Template system guide
- [workflow-prompts.md](docs/workflow-prompts.md) - Workflow automation

## Git Workflow

When making commits or PRs:
- Main branch: `main`
- Follow conventional commits style
- Reference issue numbers where applicable
- Ensure all tests pass before committing
- Database migrations require special attention

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## AI Workflow Systems

Task Orchestrator supports three coordination approaches for AI assistants:

### 1. Templates + Workflow Prompts (Universal - All AI Clients)

**What**: Database-driven templates structure work; MCP workflow prompts guide execution
**Setup**: None required (always available)
**Use for**: Any MCP client (Claude Desktop, Claude Code, Cursor, Windsurf, etc.)

**Template Discovery (CRITICAL - never skip)**:
```
1. list_templates(targetEntityType="TASK" or "FEATURE", isEnabled=true)
2. Review available templates
3. Apply via templateIds parameter during creation
```

**Key Workflows**: `initialize_task_orchestrator`, `create_feature_workflow`, `task_breakdown_workflow`, `implementation_workflow`

See: [workflow-prompts.md](docs/workflow-prompts.md), [templates.md](docs/templates.md)

### 2. Skills (Claude Code Only - Lightweight Coordination)

**What**: Auto-activating lightweight coordination (2-5 tool calls, 60-82% token savings vs subagents)
**Setup**: Run `setup_claude_agents` once
**Use for**: Status checks, progress tracking, completing tasks/features

**Skills Available**:
- Feature Management - "What's next?", "Complete feature" (~300-600 tokens)
- Task Management - "Complete task", "Update status" (~300 tokens)
- Dependency Analysis - "What's blocking?", "Show dependencies" (~400 tokens)
- Hook Builder - Create custom hooks interactively
- Skill Builder - Create custom Skills

Skills auto-activate from natural language. See: [.claude/skills/README.md](.claude/skills/README.md), [skills-guide.md](docs/skills-guide.md)

### 3. Subagents (Claude Code Only - Complex Implementation)

**What**: Specialist agents for complex work requiring reasoning and code implementation
**Setup**: Run `setup_claude_agents` once
**Use for**: Code implementation, test writing, documentation, architecture design

**Specialist Lifecycle Pattern (Self-Service)**:
1. Specialist reads task context directly via `query_container(operation="get", ...)`
2. Specialist performs work (writes code, tests, documentation)
3. Specialist updates task sections with results via `manage_sections(...)`
4. Specialist returns brief summary (50-100 tokens) to orchestrator
5. Orchestrator marks task complete (specialists do NOT self-complete)

**Standardized Specialist Structure**:
All specialist agents follow a consistent template structure:
- **Workflow Section**: 4-step process (read task, do work, update sections, return summary)
- **Critical Patterns**: Clear directive that specialists do NOT mark tasks complete
- **Blocking Scenarios**: How to handle blockers and report them
- **Domain Expertise**: Specialist-specific technical guidance
- **Quality Standards**: Role-specific validation requirements (e.g., tests for engineers, clarity for writers)
- **Output Format**: Brief summaries (50-100 tokens), detailed work in sections/files

This standardization ensures:
- Consistent behavior across all specialists
- Clear separation of concerns (specialists implement, orchestrator coordinates)
- Predictable token usage (minimal summaries, not full code/docs)
- Proper blocker reporting when work cannot be completed

**Routing**: Use `recommend_agent(taskId)` to find appropriate specialist based on task tags
**Available Specialists**:
- **Implementation**: Backend Engineer (Sonnet), Frontend Developer (Sonnet), Database Engineer (Sonnet), Test Engineer (Sonnet), Technical Writer (Sonnet)
- **Architecture**: Feature Architect (Opus), Planning Specialist (Sonnet)
- **Triage**: Bug Triage Specialist (Sonnet)

**Token Efficiency**: Specialists return minimal summaries (50-100 tokens), detailed work goes in task sections and code files, not in responses.

Templates work with both direct execution AND subagent execution.

See: [agent-orchestration.md](docs/agent-orchestration.md), [hybrid-architecture.md](docs/hybrid-architecture.md)

### Quick Decision Guide

**Coordination** (status, progress, blockers) → **Skills**
**Implementation** (code, tests, docs, design) → **Subagents** (via `recommend_agent`)
**Always**: Run `list_templates` before creating tasks/features

---

## Decision Gates (Claude Code)

**Quick routing for Skills vs Subagents:**

### When User Asks About Progress/Status/Coordination
→ **Use Skills** (60-82% token savings):
- "What's next?" → Feature Management Skill
- "Complete feature/task" → Feature/Task Management Skill
- "What's blocking?" → Dependency Analysis Skill

### When User Requests Implementation Work
→ **Use Subagents** (direct specialist routing):
- "Create feature for X" / rich context (3+ paragraphs) → Feature Architect (Opus)
- "Implement X" / task with code → `recommend_agent(taskId)` routes to specialist (Backend/Frontend/Database/Test/Technical Writer)
- "Fix bug X" / "broken"/"error" → Bug Triage Specialist (Sonnet)
- "Break down X" → Planning Specialist (Sonnet)

### Specialist Routing with recommend_agent

**Purpose**: Automatically route tasks to appropriate specialists based on task tags and requirements.

**Usage**:
```javascript
recommend_agent(taskId="task-uuid")
```

**Returns**: Recommended specialist agent name based on task analysis:
- Tags contain `backend`, `api`, `service` → Backend Engineer
- Tags contain `frontend`, `ui`, `component` → Frontend Developer
- Tags contain `database`, `migration`, `schema` → Database Engineer
- Tags contain `test`, `testing` → Test Engineer
- Tags contain `documentation`, `docs` → Technical Writer
- Tags contain `bug`, `fix`, `error` → Bug Triage Specialist
- Feature-level tasks or complex architecture → Feature Architect
- Task breakdown or planning → Planning Specialist

**Workflow Pattern**:
1. User: "Implement task X"
2. You: `recommend_agent(taskId)` → returns "Backend Engineer"
3. You: Launch Backend Engineer subagent with task ID
4. Backend Engineer: Reads task, implements code, updates sections, returns summary
5. You: Mark task complete based on specialist's summary

### Critical Patterns
- **Always** run `list_templates` before creating tasks/features
- Feature Architect (Opus) creates feature → Planning Specialist (Sonnet) breaks into tasks → Specialists implement
- **Token optimization**: Specialists return minimal summaries (50-100 tokens), full work goes in sections/files
- **Self-service**: Specialists read task context directly, no manager intermediary needed
- Use `recommend_agent(taskId)` for automatic specialist routing based on task tags

**Complete guide**: See [hybrid-architecture.md](docs/hybrid-architecture.md) for detailed decision matrices and examples.

---

## Task Orchestrator - AI Initialization

**Last initialized:** 2025-10-18 | **Version:** 1.1.0-beta | **Features:** skills,subagents

### Critical Patterns

**Session Start Routine**:
1. Run get_overview() first to understand current state
2. Check for in-progress tasks before starting new work
3. Review priorities and dependencies

**Intent Recognition** (Skills, Subagents, or Direct Tools):
- "What's next?" / "What should I work on?" → Feature Management Skill (coordination)
- "Complete feature" / "Mark feature done" → Feature Management Skill (coordination)
- "Complete task" / "Mark task done" → Task Management Skill (coordination)
- "What's blocking?" / "Show dependencies" → Dependency Analysis Skill (coordination)
- "Create feature for X" → Feature Architect subagent (complex design) + template discovery
- "Implement X" → Use `recommend_agent(taskId)` to route to appropriate specialist + templates
- "Fix bug X" → Bug Triage Specialist subagent + Bug Investigation template
- "Break down X" → Planning Specialist subagent (task decomposition)
- "Set up project" → Project setup workflow

**Specialist Workflow** (Self-Service Pattern):
1. Specialist reads task via `query_container(operation="get", containerType="task", id="...", includeSections=true)`
2. Specialist performs implementation work (code, tests, documentation)
3. Specialist updates task sections via `manage_sections(operation="add|updateText", ...)`
4. Specialist returns brief summary (50-100 tokens) - NOT full implementation details
5. Orchestrator marks task complete - specialists do NOT self-complete

**Template Discovery** (ALWAYS required, regardless of using sub-agents):
- Always: list_templates(targetEntityType, isEnabled=true)
- Never: Assume templates exist
- Apply: Use templateIds parameter during creation
- Templates work with both direct execution AND sub-agent execution

**Git Integration**:
- Auto-detect .git directory presence
- Suggest git workflow templates when detected
- Ask about PR workflows (don't assume)

**Quality Standards**:
- Write descriptive titles and summaries
- Use appropriate complexity ratings (1-10)
- Apply consistent tagging conventions
- Include acceptance criteria in summaries