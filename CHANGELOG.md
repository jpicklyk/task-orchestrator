# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Template Caching** - In-memory caching for template operations
  - CachedTemplateRepository decorator wraps SQLiteTemplateRepository
  - Caches individual templates, template lists, and template sections
  - Automatic cache invalidation on modifications (create, update, delete, enable/disable)
  - Significant performance improvement for `list_templates` and template application
  - Thread-safe using ConcurrentHashMap
  - No configuration needed - enabled by default
- **Selective Section Loading** - Token optimization for AI agents
  - `includeContent` parameter for `get_sections` (default: true) - Browse section metadata without content (85-99% token savings)
  - `sectionIds` parameter for `get_sections` - Fetch specific sections by ID for selective loading
  - Enables two-step workflow: browse metadata first, then fetch specific content
  - Backward compatible with default behavior
- **Database Performance Optimization** - V4 migration adds 10 strategic indexes
  - Dependency directional lookups (fromTaskId, toTaskId indexes)
  - Search vector indexes for full-text search optimization
  - Composite indexes for common filter patterns (status+priority, featureId+status, projectId+status, priority+createdAt)
  - 2-10x performance improvement for concurrent multi-agent access
- Markdown transformation tools for exporting entities to markdown format with YAML frontmatter
  - `task_to_markdown` - Transform tasks to markdown documents
  - `feature_to_markdown` - Transform features to markdown documents
  - `project_to_markdown` - Transform projects to markdown documents
- Markdown resource provider with usage guide for markdown transformation capabilities
- Clear separation between inspection tools (get_*) and transformation tools (*_to_markdown)

### Changed
- Removed `includeMarkdownView` parameter from get_task, get_feature, and get_project tools
- Updated API reference documentation to reflect 40 total tools (was 37)
- Updated tool category counts: Task Management (7 tools), Feature Management (6 tools), Project Management (6 tools)

### Performance
- V4 migration: Dependency lookups 5-10x faster with directional indexes
- V4 migration: Search operations 2-5x faster with search vector indexes
- V4 migration: Filtered queries 2-4x faster with composite indexes
- Selective section loading: 85-99% token reduction when browsing section structure

### Technical Details
- Markdown transformation uses existing MarkdownRenderer from domain layer
- Dedicated tools avoid content duplication in responses
- Better use case clarity for AI agents: JSON for inspection, markdown for export/rendering
- Selective section loading implemented at tool layer (no repository changes required)
- Content field excluded from response when includeContent=false
- Section filtering applied before content exclusion for efficiency

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
