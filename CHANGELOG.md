# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Output schemas for all 6 Task Management tools (create_task, get_task, update_task, delete_task, search_tasks, get_overview)
- Tool titles for all 39 MCP tools for better discoverability in clients
- ToolDefinition interface enhancements: optional `title` and `outputSchema` properties
- ToolRegistry now registers tool titles and output schemas with MCP server

### Changed
- All tool implementations now include descriptive title properties
- Task Management tools now provide structured output schemas for better agent integration

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
