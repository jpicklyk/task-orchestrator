# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
