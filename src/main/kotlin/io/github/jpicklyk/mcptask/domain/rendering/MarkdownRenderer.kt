package io.github.jpicklyk.mcptask.domain.rendering

import io.github.jpicklyk.mcptask.domain.model.*
import java.time.format.DateTimeFormatter

/**
 * Renders task orchestrator entities (Tasks, Features, Projects) as markdown documents.
 *
 * Converts structured entity data into human-readable markdown format with:
 * - YAML frontmatter for metadata
 * - Entity summary as main content
 * - Sections rendered as markdown headings with their content
 *
 * The rendered markdown is suitable for:
 * - Direct viewing in markdown readers
 * - Export to documentation tools
 * - MCP resource views
 * - Version control and archival
 */
class MarkdownRenderer(
    private val options: MarkdownOptions = MarkdownOptions()
) {
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Renders a task with its sections as a complete markdown document.
     *
     * @param task The task to render
     * @param sections List of sections associated with the task (ordered by ordinal)
     * @return Complete markdown document with frontmatter and content
     */
    fun renderTask(task: Task, sections: List<Section>): String {
        return buildString {
            if (options.includeFrontmatter) {
                append(renderTaskFrontmatter(task))
                append(options.lineEnding)
                append(options.lineEnding)
            }

            // Title
            append("# ")
            append(task.title)
            append(options.lineEnding)
            append(options.lineEnding)

            // Summary
            append(task.summary)
            append(options.lineEnding)
            append(options.lineEnding)

            // Sections
            sections.sortedBy { it.ordinal }.forEach { section ->
                append(renderSection(section))
                append(options.lineEnding)
            }
        }.trimEnd()
    }

    /**
     * Renders a feature with its sections as a complete markdown document.
     *
     * @param feature The feature to render
     * @param sections List of sections associated with the feature (ordered by ordinal)
     * @return Complete markdown document with frontmatter and content
     */
    fun renderFeature(feature: Feature, sections: List<Section>): String {
        return buildString {
            if (options.includeFrontmatter) {
                append(renderFeatureFrontmatter(feature))
                append(options.lineEnding)
                append(options.lineEnding)
            }

            // Title
            append("# ")
            append(feature.name)
            append(options.lineEnding)
            append(options.lineEnding)

            // Summary
            append(feature.summary)
            append(options.lineEnding)
            append(options.lineEnding)

            // Sections
            sections.sortedBy { it.ordinal }.forEach { section ->
                append(renderSection(section))
                append(options.lineEnding)
            }
        }.trimEnd()
    }

    /**
     * Renders a project with its sections as a complete markdown document.
     *
     * @param project The project to render
     * @param sections List of sections associated with the project (ordered by ordinal)
     * @return Complete markdown document with frontmatter and content
     */
    fun renderProject(project: Project, sections: List<Section>): String {
        return buildString {
            if (options.includeFrontmatter) {
                append(renderProjectFrontmatter(project))
                append(options.lineEnding)
                append(options.lineEnding)
            }

            // Title
            append("# ")
            append(project.name)
            append(options.lineEnding)
            append(options.lineEnding)

            // Summary
            append(project.summary)
            append(options.lineEnding)
            append(options.lineEnding)

            // Sections
            sections.sortedBy { it.ordinal }.forEach { section ->
                append(renderSection(section))
                append(options.lineEnding)
            }
        }.trimEnd()
    }

    /**
     * Renders a single section as markdown.
     *
     * @param section The section to render
     * @return Markdown representation of the section
     */
    private fun renderSection(section: Section): String {
        return buildString {
            // Section heading (## by default, adjusted by offset)
            val headingLevel = 2 + options.headingLevelOffset
            append("#".repeat(headingLevel))
            append(" ")
            append(section.title)
            append(options.lineEnding)
            append(options.lineEnding)

            // Section content based on format
            append(renderSectionContent(section))
        }
    }

    /**
     * Renders section content based on its content format.
     *
     * @param section The section whose content to render
     * @return Formatted content as markdown
     */
    private fun renderSectionContent(section: Section): String {
        return when (section.contentFormat) {
            ContentFormat.MARKDOWN -> {
                // Pass through markdown as-is
                section.content
            }
            ContentFormat.PLAIN_TEXT -> {
                // Plain text - no special formatting needed
                section.content
            }
            ContentFormat.JSON -> {
                // Wrap JSON in code fence
                "```json${options.lineEnding}${section.content}${options.lineEnding}```"
            }
            ContentFormat.CODE -> {
                // Wrap code in code fence with configured language
                "```${options.defaultCodeLanguage}${options.lineEnding}${section.content}${options.lineEnding}```"
            }
        }
    }

    /**
     * Renders YAML frontmatter for a task.
     *
     * @param task The task to generate frontmatter for
     * @return YAML frontmatter block
     */
    private fun renderTaskFrontmatter(task: Task): String {
        return buildString {
            append("---")
            append(options.lineEnding)
            append("id: ")
            append(task.id)
            append(options.lineEnding)
            append("type: task")
            append(options.lineEnding)
            append("title: ")
            append(escapeYamlString(task.title))
            append(options.lineEnding)
            append("status: ")
            append(task.status.name.lowercase().replace('_', '-'))
            append(options.lineEnding)
            append("priority: ")
            append(task.priority.name.lowercase())
            append(options.lineEnding)
            append("complexity: ")
            append(task.complexity)
            append(options.lineEnding)

            if (task.featureId != null) {
                append("featureId: ")
                append(task.featureId)
                append(options.lineEnding)
            }

            if (task.projectId != null) {
                append("projectId: ")
                append(task.projectId)
                append(options.lineEnding)
            }

            if (task.tags.isNotEmpty()) {
                append("tags:")
                append(options.lineEnding)
                task.tags.forEach { tag ->
                    append("  - ")
                    append(escapeYamlString(tag))
                    append(options.lineEnding)
                }
            }

            append("created: ")
            append(isoFormatter.format(task.createdAt))
            append(options.lineEnding)
            append("modified: ")
            append(isoFormatter.format(task.modifiedAt))
            append(options.lineEnding)
            append("---")
        }
    }

    /**
     * Renders YAML frontmatter for a feature.
     *
     * @param feature The feature to generate frontmatter for
     * @return YAML frontmatter block
     */
    private fun renderFeatureFrontmatter(feature: Feature): String {
        return buildString {
            append("---")
            append(options.lineEnding)
            append("id: ")
            append(feature.id)
            append(options.lineEnding)
            append("type: feature")
            append(options.lineEnding)
            append("name: ")
            append(escapeYamlString(feature.name))
            append(options.lineEnding)
            append("status: ")
            append(feature.status.name.lowercase().replace('_', '-'))
            append(options.lineEnding)
            append("priority: ")
            append(feature.priority.name.lowercase())
            append(options.lineEnding)

            if (feature.projectId != null) {
                append("projectId: ")
                append(feature.projectId)
                append(options.lineEnding)
            }

            if (feature.tags.isNotEmpty()) {
                append("tags:")
                append(options.lineEnding)
                feature.tags.forEach { tag ->
                    append("  - ")
                    append(escapeYamlString(tag))
                    append(options.lineEnding)
                }
            }

            append("created: ")
            append(isoFormatter.format(feature.createdAt))
            append(options.lineEnding)
            append("modified: ")
            append(isoFormatter.format(feature.modifiedAt))
            append(options.lineEnding)
            append("---")
        }
    }

    /**
     * Renders YAML frontmatter for a project.
     *
     * @param project The project to generate frontmatter for
     * @return YAML frontmatter block
     */
    private fun renderProjectFrontmatter(project: Project): String {
        return buildString {
            append("---")
            append(options.lineEnding)
            append("id: ")
            append(project.id)
            append(options.lineEnding)
            append("type: project")
            append(options.lineEnding)
            append("name: ")
            append(escapeYamlString(project.name))
            append(options.lineEnding)
            append("status: ")
            append(project.status.name.lowercase().replace('_', '-'))
            append(options.lineEnding)

            if (project.tags.isNotEmpty()) {
                append("tags:")
                append(options.lineEnding)
                project.tags.forEach { tag ->
                    append("  - ")
                    append(escapeYamlString(tag))
                    append(options.lineEnding)
                }
            }

            append("created: ")
            append(isoFormatter.format(project.createdAt))
            append(options.lineEnding)
            append("modified: ")
            append(isoFormatter.format(project.modifiedAt))
            append(options.lineEnding)
            append("---")
        }
    }

    /**
     * Escapes special characters in YAML strings.
     * Wraps in quotes if string contains special characters.
     *
     * @param value The string value to escape
     * @return Escaped YAML-safe string
     */
    private fun escapeYamlString(value: String): String {
        // Characters that require quoting in YAML
        val specialChars = setOf(':', '#', '@', '`', '|', '>', '*', '&', '!', '%', '{', '}', '[', ']', ',', '?', '-', '"', '\\')

        val needsQuoting = value.any { it in specialChars } ||
                value.startsWith(' ') ||
                value.endsWith(' ') ||
                value.contains('\n') ||
                value.contains('\r')

        return if (needsQuoting) {
            // Use double quotes and escape internal quotes and backslashes
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            value
        }
    }
}
