package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.slf4j.LoggerFactory

/**
 * Provides MCP resources for markdown-formatted entity views.
 *
 * NOTE: This uses a pattern-based approach where markdown views are provided as
 * tool response enhancements rather than as standalone MCP resources, since the MCP
 * resource system is designed for static/discoverable resources rather than dynamic
 * entity retrieval with UUID parameters.
 *
 * For markdown views of specific entities, use the get_task, get_feature, or get_project
 * tools with includeSections=true, and the response will include markdown formatting
 * guidance.
 */
object MarkdownResourceProvider {
    private val logger = LoggerFactory.getLogger(MarkdownResourceProvider::class.java)

    /**
     * Configures markdown resource documentation with the MCP server.
     *
     * This adds a documentation resource explaining how to get markdown views of entities.
     *
     * @param server The MCP server instance to configure
     * @param repositoryProvider Provider for accessing entity repositories
     */
    fun Server.configureMarkdownResources(repositoryProvider: RepositoryProvider) {
        logger.info("Configuring markdown resource documentation")

        // Add a documentation resource explaining the markdown view capability
        this.addResource(
            uri = "task-orchestrator://markdown-views/guide",
            name = "Markdown Views Guide",
            description = "Guide to obtaining markdown-formatted views of tasks, features, and projects",
            mimeType = "text/markdown"
        ) { _ ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        uri = "task-orchestrator://markdown-views/guide",
                        mimeType = "text/markdown",
                        text = """
# Markdown Views for Task Orchestrator Entities

## Overview

Task Orchestrator entities (Tasks, Features, Projects) can be rendered as markdown documents with YAML frontmatter for easy reading and documentation.

## How to Get Markdown Views

### Using Markdown Transformation Tools

The recommended approach is to use the dedicated markdown transformation tools:

```
# Transform a task to markdown
task_to_markdown --id "uuid"

# Transform a feature to markdown
feature_to_markdown --id "uuid"

# Transform a project to markdown
project_to_markdown --id "uuid"
```

These tools return the complete entity as a markdown document with YAML frontmatter, ready for:
- File export and documentation generation
- Display in markdown-capable systems
- Version control and diff-friendly storage
- Human-readable archives

### Inspecting Entity Data

For structured JSON inspection of entities, use the standard get tools:

```
# Get task details in JSON format
get_task --id "uuid" --includeSections true

# Get feature details in JSON format
get_feature --id "uuid" --includeSections true

# Get project details in JSON format
get_project --id "uuid" --includeSections true
```

### Markdown Format

The markdown transformation tools produce documents with:

**YAML Frontmatter**:
```yaml
---
id: 550e8400-e29b-41d4-a716-446655440000
type: task
title: Task Title
status: in-progress
priority: high
complexity: 7
tags:
  - tag1
  - tag2
created: 2025-05-10T14:30:00Z
modified: 2025-05-10T14:30:00Z
---
```

**Markdown Content**:
```markdown
# Task Title

Task summary paragraph describing what needs to be done.

## Section Title

Section content in the specified format (markdown, plain text, JSON, or code).

## Another Section

More section content...
```

## Content Formats

Sections support multiple content formats:
- **MARKDOWN**: Rich formatted text (default)
- **PLAIN_TEXT**: Unformatted text
- **JSON**: Structured data (rendered in code fences)
- **CODE**: Source code (rendered in code fences)

## Use Cases

**Documentation Export**:
- Generate documentation from task/feature definitions
- Export to markdown files for wikis or documentation sites
- Create human-readable archives

**Version Control**:
- Track entity changes in git-friendly markdown format
- Review entity history with standard diff tools
- Store snapshots alongside code

**Reporting**:
- Create readable status reports
- Share task/feature details in markdown-native tools
- Generate documentation from project state

## Integration Notes

The markdown rendering is handled by the `MarkdownRenderer` class in the domain layer:
- Path: `io.github.jpicklyk.mcptask.domain.rendering.MarkdownRenderer`
- Configurable via `MarkdownOptions` for customization
- Tested extensively with various content formats and edge cases

For programmatic access, use the renderer directly with entity and section data from the repositories.
                        """.trimIndent()
                    )
                )
            )
        }

        logger.info("Markdown resource documentation configured")
    }
}
