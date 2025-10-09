# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0-alpha-01]

### Added
- Output schemas for all 6 Task Management tools (create_task, get_task, update_task, delete_task, search_tasks, get_overview)
- Output schemas for all 5 Project Management tools (create_project, get_project, update_project, delete_project, search_projects)
- Output schemas for all 10 Section Management tools (add_section, get_sections, update_section, delete_section, update_section_text, update_section_metadata, bulk_create_sections, bulk_update_sections, bulk_delete_sections, reorder_sections)
- Output schemas for all 9 Template Management tools (create_template, get_template, list_templates, apply_template, update_template_metadata, delete_template, add_template_section, enable_template, disable_template)
- Tool titles for all 39 MCP tools for better discoverability in clients
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
- Removed pre-release notices (project now v1.0.1)

### Dependencies
- kotlin-sdk: 0.5.0 → 0.7.2
- Kotlin: 2.1.20 → 2.2.0
- Ktor: 3.3.0 (transitive dependency from kotlin-sdk)

### Technical Details
- Output schemas use Tool.Output from kotlin-sdk 0.7.x
- Schemas define complete response structure with required vs optional fields
- Proper JSON Schema types, formats, and enum constraints
- Support for nested structures (pagination, hierarchical data)
- All changes maintain backward compatibility
