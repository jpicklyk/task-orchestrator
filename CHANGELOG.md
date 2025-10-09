# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Documentation

#### Added
- **Installation Guide** (`docs/installation-guide.md`) - Comprehensive 630-line guide covering all installation methods, environment variables, platform-specific instructions, and troubleshooting
- **Developer Guides** (`docs/developer-guides/`) - New directory structure for developer-specific documentation
  - `docs/developer-guides/index.md` - Hub for all developer documentation with setup instructions
  - `docs/developer-guides/database-migrations.md` - Moved from `docs/` for better organization
- **PRD-Driven Development Workflow** - Extensive documentation of the most effective AI-assisted development approach:
  - Complete 220+ line guide in `docs/quick-start.md` with step-by-step workflow
  - Added as 7th Task Management Pattern in `docs/ai-guidelines.md`
  - Cross-referenced in `docs/index.md`, `docs/workflow-prompts.md`, `docs/templates.md`, and `docs/api-reference.md`
- **Existing Project Integration** - Guide for connecting Task Orchestrator to existing work (`docs/quick-start.md`)
- **AI Guidelines Enhancements**:
  - "What AI Sees After Initialization" section with example pattern
  - Cross-reference to quick-start guide
  - PRD-Driven Development Pattern marked as most effective

#### Changed
- **Complete Documentation Overhaul** - Modernized all documentation following ai-guidelines.md model:
  - Clear hierarchical structure with comprehensive Table of Contents
  - Natural flow with extensive cross-referencing
  - Focus on WHEN/WHY rather than exhaustive HOW (AI has MCP schemas)
  - Eliminated redundancy through single-source-of-truth principle
  - Replaced JSON examples with natural language conversation examples

- **quick-start.md** (323→432 lines):
  - Split technical details into separate installation-guide.md
  - Made AI-agent agnostic (works with any MCP-compatible AI)
  - Added configuration for Claude Desktop, Claude Code, and other AI agents
  - Removed pre-release notice (project is now v1.0.1)
  - Added PRD-driven development workflow section
  - Added existing project integration section

- **workflow-prompts.md** (754→520 lines, 31% reduction):
  - Restructured with Dual Workflow Model section (autonomous vs. explicit)
  - Added Pattern 5: PRD-Driven Development
  - Removed exhaustive details that duplicate MCP prompt content
  - Focus on WHEN to use each mode with decision tree

- **templates.md** (530→516 lines):
  - Added "AI-Driven Template Discovery" section
  - Added template composition patterns with decision tree
  - Enhanced integration with PRD workflow
  - Replaced JSON examples with realistic Claude conversations

- **api-reference.md** (633→454 lines, 28% reduction):
  - Added "How AI Uses Tools" section explaining autonomous discovery
  - Added "Workflow-Based Tool Patterns" with 7 patterns (including PRD)
  - Added Natural Language → Tool Mapping examples
  - Removed exhaustive parameter documentation (AI has MCP schemas)
  - Added complete tool chaining examples

- **troubleshooting.md** (570→702 lines):
  - Added Quick Reference table with top 5 issues
  - Added "AI Guidelines Issues" section
  - Added cross-references to installation-guide.md
  - Expanded with AI-specific troubleshooting

- **index.md** (134→173 lines):
  - Updated navigation grid from 5 to 6 items
  - Added "Getting Started Path" with 5-step progression
  - Added PRD-Driven Development showcase in Quick Examples
  - Updated "For Developers" section with developer-guides link
  - Removed pre-release notice

- **README.md** (239→204 lines, 15% reduction):
  - Reorganized documentation links into Getting Started / Using / For Developers sections
  - Added "AI-Native Design" section
  - Simplified Quick Start with cross-references
  - Added PRD-Driven Development callout
  - Made AI-agent agnostic (Claude Desktop, Claude Code, other MCP-compatible AI agents)
  - Updated all documentation references to new structure

#### Impact
- ~400 lines reduced across existing docs through cross-referencing
- +630 lines added for comprehensive installation guide
- +220+ lines added for PRD workflow documentation
- Better organization with clear audience segmentation (new users, power users, developers)
- Improved discoverability of best practices (PRD workflow prominently featured)
- AI-agent agnostic approach emphasizing MCP protocol compatibility

## [1.1.0-alpha-01] - 2025-10-08

### Added
- Output schemas for all 6 Task Management tools (create_task, get_task, update_task, delete_task, search_tasks, get_overview)
- Output schemas for all 5 Project Management tools (create_project, get_project, update_project, delete_project, search_projects)
- Output schemas for all 10 Section Management tools (add_section, get_sections, update_section, delete_section, update_section_text, update_section_metadata, bulk_create_sections, bulk_update_sections, bulk_delete_sections, reorder_sections)
- Output schemas for all 9 Template Management tools (create_template, get_template, list_templates, apply_template, update_template_metadata, delete_template, add_template_section, enable_template, disable_template)
- Tool titles for all 39 MCP tools for better discoverability in clients
- ToolDefinition interface enhancements: optional `title` and `outputSchema` properties
- ToolRegistry now registers tool titles and output schemas with MCP server

### Changed
- All tool implementations now include descriptive title properties
- All MCP tools now provide structured output schemas for better agent integration (Task, Project, Section, Template, Feature, and Dependency management)

### Technical Details
- Output schemas use Tool.Output from kotlin-sdk 0.7.x
- Schemas define complete response structure with required vs optional fields
- Proper JSON Schema types, formats, and enum constraints
- Support for nested structures (pagination, hierarchical data)
- All changes maintain backward compatibility

## [1.0.2] - 2025-10-08

### Changed
- Upgraded kotlin-sdk from 0.5.0 to 0.7.2
- Updated Kotlin from 2.1.20 to 2.2.0 for SDK compatibility
- Fixed test mock for addTool API signature change (now uses named parameters)

### Dependencies
- kotlin-sdk: 0.5.0 → 0.7.2
- Kotlin: 2.1.20 → 2.2.0
- Ktor: 3.3.0 (transitive dependency from kotlin-sdk)

### Notes
- SDK "breaking changes" (Kotlin-style callbacks, JSON serialization refactoring) did not affect codebase
- All existing callbacks were already using proper Kotlin lambda syntax
- JSON serialization patterns were already compatible with SDK 0.7.x changes
- All tests pass successfully
- Docker build validated and working
- Build configuration validated with no deprecation warnings

### Technical Details
For detailed information about the upgrade process and validation:
- See feature: Kotlin SDK 0.7.2 Integration (feature-branch: feature/kotlin-sdk-0.7.2-integration)
- All validation tasks completed successfully
- No runtime issues detected
