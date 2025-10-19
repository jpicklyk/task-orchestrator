# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0-beta-01] - 2025-10-19

### 🚨 BREAKING CHANGES

**Container-Based Tool Consolidation**: Major architectural overhaul reducing 56 tools to 16 tools (71% reduction) with clean read/write permission separation.

**Deprecated Tools (40+ removed from registration):**
- Container tools: `create_task`, `get_task`, `update_task`, `delete_task`, `search_tasks`, `create_feature`, `get_feature`, `update_feature`, `delete_feature`, `search_features`, `create_project`, `get_project`, `update_project`, `delete_project`, `search_projects`, `get_overview`, `task_to_markdown`, `feature_to_markdown`, `project_to_markdown`
- Section tools: `add_section`, `get_sections`, `update_section`, `update_section_text`, `update_section_metadata`, `delete_section`, `reorder_sections`, `bulk_create_sections`, `bulk_update_sections`, `bulk_delete_sections`
- Template tools: `create_template`, `get_template`, `list_templates`, `update_template_metadata`, `delete_template`, `enable_template`, `disable_template`, `add_template_section`
- Dependency tools: `create_dependency`, `get_task_dependencies`, `delete_dependency`
- Task query tools: `get_blocked_tasks`, `get_next_task`, `bulk_update_tasks`, `get_feature_tasks`

**Migration Required**: See [v2.0 Migration Guide](docs/migration/v2.0-migration-guide.md) for complete tool mapping and code examples.

### Added

- **🔍 query_container** - Unified read-only container queries
  - 4 operations: `get`, `search`, `export`, `overview`
  - 3 container types: `project`, `feature`, `task`
  - Advanced filtering: status, priority, tags, projectId, featureId
  - Replaces 19 v1.x tools with single consistent interface

- **✏️ manage_container** - Unified write operations for containers
  - 5 operations: `create`, `update`, `delete`, `setStatus`, `bulkUpdate`
  - Supports all container types with entityType parameter
  - Bulk update capabilities (3-100 containers in single operation)
  - Replaces 19 v1.x tools with permission-separated interface

- **🔍 query_sections** - Read-only section queries with advanced filtering
  - Filter by entityType, entityId, sectionIds, tags
  - Optional content inclusion (`includeContent=false` saves 85-99% tokens)
  - Returns sections ordered by ordinal
  - Replaces 11 v1.x section read operations

- **✏️ manage_sections** - Unified section write operations
  - 9 operations: `add`, `update`, `updateText`, `updateMetadata`, `delete`, `reorder`, `bulkCreate`, `bulkUpdate`, `bulkDelete`
  - Transaction support for bulk operations
  - Section ordering and reordering logic
  - Supports all content formats: MARKDOWN, PLAIN_TEXT, JSON, CODE

- **🔍 query_templates** - Template discovery and inspection
  - 2 operations: `get`, `list`
  - Filter by targetEntityType, isBuiltIn, isEnabled, tags
  - Efficient template exploration for AI agents
  - Replaces `get_template` and `list_templates`

- **✏️ manage_template** - Template management operations
  - 6 operations: `create`, `update`, `delete`, `enable`, `disable`, `addSection`
  - Template protection logic for built-in templates
  - Section management within templates
  - Replaces 7 v1.x template modification tools

- **🔍 query_dependencies** - Dependency queries with direction support
  - Direction filtering: dependencies, dependents, both
  - Returns dependency metadata including types (BLOCKS, IS_BLOCKED_BY, RELATES_TO)
  - Dependency graph querying for AI agents
  - Replaces `get_task_dependencies`

- **✏️ manage_dependency** - Dependency management with validation
  - 2 operations: `create`, `delete`
  - Circular dependency detection
  - Dependency validation logic
  - Ensures referential integrity

### Changed

- **Permission Model**: All tools now follow clear read/write separation
  - `query_*` tools: READ-ONLY operations (no locking required)
  - `manage_*` tools: WRITE operations (with locking support)
  - Enables future permission-based access control

- **Parameter Pattern**: Consistent operation-based interface
  - All consolidated tools use `operation` parameter for action selection
  - `containerType` parameter for entity type discrimination
  - Reduces AI confusion and improves tool discovery

- **Token Efficiency**: 71% reduction in MCP tool overhead
  - v1.x: 56 tools consuming ~60k+ characters in MCP schema
  - v2.0: 16 tools consuming ~17.4k characters
  - Massive context window savings for every AI request

- **Documentation**: Complete rewrite for v2.0
  - New [v2.0 Migration Guide](docs/migration/v2.0-migration-guide.md) with 40+ tool mappings
  - Rewritten [API Reference](docs/api-reference.md) (2,063 lines)
  - Updated all core docs (README, CLAUDE.md, quick-start)
  - Updated all agent and skill definitions for v2.0 syntax
  - Created 9 new tool-specific docs with examples
  - Added deprecation notices to 35+ old tool files

### Unchanged (7 tools)

These tools remain available without changes:
- `list_tags`, `get_tag_usage`, `rename_tag` - Tag management
- `setup_claude_agents`, `get_agent_definition`, `recommend_agent` - Agent automation
- `apply_template` - Template application (kept separate due to workflow complexity)

### Technical Details

- **Architecture**: Clean separation of read (`query_*`) and write (`manage_*`) operations
- **Backward Compatibility**: Repository layer unchanged, existing data fully compatible
- **Database**: No schema migrations required
- **Tests**: Comprehensive test coverage for all consolidated tools (90%+ coverage)
- **Locking**: SimpleLockAwareToolDefinition base class for concurrency control

### Migration Path

1. Review [v2.0 Migration Guide](docs/migration/v2.0-migration-guide.md) for complete tool mappings
2. Update tool calls to use new `query_container` and `manage_container` syntax
3. Add `containerType` and `operation` parameters to all container operations
4. Update template discovery: `list_templates` → `query_templates(operation="list")`
5. Update section operations: use `query_sections` and `manage_sections`
6. Test with v2.0-beta-01 before stable release

### Known Issues

- Docker deployment testing in progress
- Real-world usage feedback needed before stable 2.0.0 release

---

## [1.1.0-beta-01]

### Added
- **Bulk Task Updates** - Efficient multi-task update operation
  - `bulk_update_tasks` tool for updating 3-100 tasks in single operation
  - 70-95% token savings vs individual update_task calls
  - Supports partial updates (each task updates only specified fields)
  - Atomic operation with detailed success/failure reporting
- **Template Caching** - In-memory caching for template operations
  - Caches individual templates, template lists, and template sections
  - Automatic cache invalidation on modifications
  - Significant performance improvement for `list_templates` and template application
  - Enabled by default with thread-safe implementation
- **Selective Section Loading** - Token optimization for AI agents
  - `includeContent` parameter for `get_sections` - Browse section metadata without content (85-99% token savings)
  - `sectionIds` parameter for `get_sections` - Fetch specific sections by ID
  - Enables two-step workflow: browse metadata first, then fetch specific content
- **Database Performance Optimization** - Strategic indexes for improved query performance
  - Dependency directional lookups and search optimization
  - Composite indexes for common filter patterns
  - 2-10x performance improvement for concurrent multi-agent access
- **Optimistic Locking** - Multi-agent concurrency protection
  - Prevents concurrent modifications and race conditions
  - Built-in collision detection for sub-agent workflows
- **MCP Resources Infrastructure** - Comprehensive AI guidance system
  - Dynamic resource loading for AI initialization
  - Template strategy, task management patterns, and workflow integration resources
  - Enables autonomous AI pattern recognition without explicit commands
- **Memory-Based Workflow Customization** - AI memory integration
  - Global and project-specific workflow preferences
  - Branch naming variable system ({task-id-short}, {description}, etc.)
  - Work type detection (bug/feature/enhancement/hotfix)
  - Team-specific customization without code changes
  - See [Workflow Prompts Documentation](docs/workflow-prompts.md) for complete details
- Markdown transformation tools for exporting entities
  - `task_to_markdown`, `feature_to_markdown`, `project_to_markdown`
  - YAML frontmatter with metadata

### Changed
- **BREAKING: Workflow rename** - `implement_feature_workflow` renamed to `implementation_workflow`
  - Consolidated bug handling into unified implementation workflow
  - Removed separate `bug_triage_workflow` (functionality merged)
  - Enhanced with bug-specific guidance and mandatory regression testing
- **AI-Agnostic Workflow Prompts** - Made all workflows work with any MCP-compatible AI
  - Removed Claude-specific references
  - Universal workflow patterns for Claude Desktop, Claude Code, Cursor, Windsurf, etc.
- **Template Simplification** - Simplified all 9 built-in templates
  - Reduced complexity while maintaining functionality
  - Improved usability and clarity
- Removed `includeMarkdownView` parameter from get_task, get_feature, and get_project tools
- Optimized search results by removing summary field for better performance

### Fixed
- Upgraded Docker base image to Amazon Corretto 25 (addresses high severity CVEs)

### Performance Improvements
- Bulk task updates: 70-95% token reduction vs individual calls
- Database indexes: 2-10x faster queries for concurrent access
- Selective section loading: 85-99% token reduction when browsing structure
- Optimized update operation responses for reduced bandwidth

## [1.1.0-alpha-01]

### Dependencies

- kotlin-sdk: 0.5.0 → 0.7.2
- Kotlin: 2.1.20 → 2.2.0
- Ktor: 3.3.0 (transitive dependency from kotlin-sdk)

### Added
- Output schemas for all 6 Task Management tools (create_task, get_task, update_task, delete_task, search_tasks, get_overview)
- Output schemas for all 5 Project Management tools (create_project, get_project, update_project, delete_project, search_projects)
- Output schemas for all 10 Section Management tools (add_section, get_sections, update_section, delete_section, update_section_text, update_section_metadata, bulk_create_sections, bulk_update_sections, bulk_delete_sections, reorder_sections)
- Output schemas for all 9 Template Management tools (create_template, get_template, list_templates, apply_template, update_template_metadata, delete_template, add_template_section, enable_template, disable_template)
- Tool titles for all MCP tools for better discoverability in clients
- ToolDefinition interface enhancements: optional `title` and `outputSchema` properties
- ToolRegistry now registers tool titles and output schemas with MCP server
- Installation Guide (`docs/installation-guide.md`) - comprehensive setup guide for all platforms and installation methods
- Developer Guides directory (`docs/developer-guides/`) - organized developer-specific documentation
- PRD-Driven Development Workflow - complete guide for using Product Requirements Documents with AI agents
- Existing Project Integration guide - instructions for connecting Task Orchestrator to ongoing work
- "What AI Sees After Initialization" section in AI Guidelines with example patterns

### Changed
- Upgraded kotlin-sdk from 0.5.0 to 0.7.2
- Updated Kotlin from 2.1.20 to 2.2.0 for SDK compatibility
- Fixed test mock for addTool API signature change (now uses named parameters)
- All tool implementations now include descriptive title properties
- All MCP tools now provide structured output schemas for better agent integration
- Modernized all documentation with improved structure, cross-referencing, and natural language examples
- Made documentation AI-agent agnostic - works with any MCP-compatible AI (Claude Desktop, Claude Code, Cursor, etc.)
- Restructured quick-start.md with configuration options for multiple AI agents
- Enhanced workflow-prompts.md with Dual Workflow Model and PRD development pattern
- Updated templates.md with AI-driven template discovery and composition patterns
- Improved api-reference.md with workflow-based tool patterns and usage examples
- Expanded troubleshooting.md with Quick Reference table and AI-specific issues

### Technical Details
- Output schemas use Tool.Output from kotlin-sdk 0.7.x
- Schemas define complete response structure with required vs optional fields
- Proper JSON Schema types, formats, and enum constraints
- Support for nested structures (pagination, hierarchical data)
- All changes maintain backward compatibility
