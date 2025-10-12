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
    // Use ISO format without milliseconds for cleaner, more readable dates
    // Format: 2025-05-10T14:30:00Z (instead of 2025-05-10T14:30:00.123456789Z)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

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
            sections.sortedBy { it.ordinal }.forEachIndexed { index, section ->
                append(renderSection(section))
                // Add consistent spacing between sections (two line endings)
                // Last section doesn't need extra spacing since trimEnd() removes trailing whitespace
                if (index < sections.size - 1) {
                    append(options.lineEnding)
                    append(options.lineEnding)
                }
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
            sections.sortedBy { it.ordinal }.forEachIndexed { index, section ->
                append(renderSection(section))
                // Add consistent spacing between sections (two line endings)
                // Last section doesn't need extra spacing since trimEnd() removes trailing whitespace
                if (index < sections.size - 1) {
                    append(options.lineEnding)
                    append(options.lineEnding)
                }
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
            sections.sortedBy { it.ordinal }.forEachIndexed { index, section ->
                append(renderSection(section))
                // Add consistent spacing between sections (two line endings)
                // Last section doesn't need extra spacing since trimEnd() removes trailing whitespace
                if (index < sections.size - 1) {
                    append(options.lineEnding)
                    append(options.lineEnding)
                }
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
            val content = renderSectionContent(section)

            // Check if content already starts with a heading matching the section title
            // This prevents duplicate headers when section content includes its own title
            val headingLevel = 2 + options.headingLevelOffset
            val expectedHeading = "${"#".repeat(headingLevel)} ${section.title}"
            val contentStartsWithTitle = content.trimStart().startsWith(expectedHeading)

            if (!contentStartsWithTitle) {
                // Only add heading if content doesn't already have it
                append("#".repeat(headingLevel))
                append(" ")
                append(section.title)
                append(options.lineEnding)
                append(options.lineEnding)
            }

            // Section content based on format
            append(content)
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
                // Process markdown content for proper formatting
                var content = section.content
                // Handle nested markdown code blocks to prevent rendering issues
                content = escapeNestedMarkdownBlocks(content)
                // Enforce consistent header hierarchy to prevent structural issues
                content = normalizeHeaderHierarchy(content)
                content
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
                // Detect language from section context for better syntax highlighting
                val language = detectCodeLanguage(section)
                "```${language}${options.lineEnding}${section.content}${options.lineEnding}```"
            }
        }
    }

    /**
     * Detects the code language from section metadata for syntax highlighting.
     *
     * Examines section title and tags to determine appropriate language specifier.
     * Falls back to configured default if no language can be detected.
     *
     * @param section The section containing code
     * @return Language identifier for code fence (e.g., "kotlin", "bash", "json")
     */
    private fun detectCodeLanguage(section: Section): String {
        // Common language keywords to look for in title and tags
        val languagePatterns = mapOf(
            "kotlin" to listOf("kotlin", "kt"),
            "java" to listOf("java"),
            "python" to listOf("python", "py"),
            "javascript" to listOf("javascript", "js"),
            "typescript" to listOf("typescript", "ts"),
            "bash" to listOf("bash", "shell", "sh"),
            "sql" to listOf("sql"),
            "json" to listOf("json"),
            "yaml" to listOf("yaml", "yml"),
            "xml" to listOf("xml"),
            "markdown" to listOf("markdown", "md"),
            "dockerfile" to listOf("dockerfile", "docker"),
            "go" to listOf("go", "golang"),
            "rust" to listOf("rust", "rs"),
            "c++" to listOf("c++", "cpp"),
            "c#" to listOf("c#", "csharp"),
            "ruby" to listOf("ruby", "rb"),
            "php" to listOf("php")
        )

        val titleLower = section.title.lowercase()
        val tagsLower = section.tags.map { it.lowercase() }
        val searchText = (listOf(titleLower) + tagsLower).joinToString(" ")

        // Find first matching language
        for ((language, patterns) in languagePatterns) {
            if (patterns.any { pattern -> searchText.contains(pattern) }) {
                return language
            }
        }

        // No language detected - use configured default
        return options.defaultCodeLanguage
    }

    /**
     * Normalizes markdown header hierarchy to ensure proper nesting.
     *
     * Prevents structural issues by ensuring headers follow logical hierarchy
     * (e.g., H2 → H3 → H4, not H2 → H4). Adjusts header levels to maintain
     * a maximum jump of one level between adjacent headers.
     *
     * @param content The markdown content to normalize
     * @return Content with properly nested headers
     */
    private fun normalizeHeaderHierarchy(content: String): String {
        val lines = content.lines()
        val result = mutableListOf<String>()
        var previousLevel = 0
        var inCodeBlock = false

        for (line in lines) {
            // Track code blocks to avoid processing headers inside them
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                result.add(line)
                continue
            }

            // Skip header processing inside code blocks
            if (inCodeBlock) {
                result.add(line)
                continue
            }

            // Check if line is a header (starts with # characters)
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#") && trimmed.contains(" ")) {
                val headerMatch = Regex("^(#+)\\s+(.*)").find(trimmed)
                if (headerMatch != null) {
                    val (hashes, text) = headerMatch.destructured
                    val currentLevel = hashes.length

                    // Determine appropriate level based on previous header
                    val normalizedLevel = if (previousLevel == 0) {
                        // First header - keep as-is
                        currentLevel
                    } else if (currentLevel > previousLevel + 1) {
                        // Header jumps more than one level - normalize to previous + 1
                        previousLevel + 1
                    } else {
                        // Header level is valid - keep as-is
                        currentLevel
                    }

                    previousLevel = normalizedLevel
                    val normalizedLine = "${line.substringBefore("#")}${"#".repeat(normalizedLevel)} $text"
                    result.add(normalizedLine)
                } else {
                    // Malformed header - keep as-is
                    result.add(line)
                }
            } else {
                // Not a header - keep as-is
                result.add(line)
            }
        }

        return result.joinToString(options.lineEnding)
    }

    /**
     * Escapes nested markdown code blocks to prevent rendering issues.
     *
     * When markdown content contains code blocks with "markdown" language specifier,
     * it creates confusing nesting. This method detects such blocks and re-escapes them
     * using 4-backtick fences to properly display the markdown examples.
     *
     * @param content The markdown content to process
     * @return Content with nested markdown blocks properly escaped
     */
    private fun escapeNestedMarkdownBlocks(content: String): String {
        // Pattern to match code blocks with 'markdown' language specifier
        // Matches: ```markdown or ``` markdown (with optional whitespace)
        val markdownBlockPattern = Regex("```\\s*markdown\\s*\n", RegexOption.IGNORE_CASE)

        // Check if content contains markdown-language code blocks
        if (!markdownBlockPattern.containsMatchIn(content)) {
            // No nested markdown blocks - pass through as-is
            return content
        }

        // Content has nested markdown blocks - need to re-escape
        // Strategy: Replace triple-backtick markdown blocks with 4-backtick blocks
        // This allows proper rendering of markdown examples within markdown content

        val lines = content.lines().toMutableList()
        val result = mutableListOf<String>()
        var inMarkdownBlock = false
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Check if this line starts a markdown code block
            if (!inMarkdownBlock && line.trim().matches(Regex("```\\s*markdown\\s*", RegexOption.IGNORE_CASE))) {
                // Start of markdown block - use 4 backticks instead
                result.add(line.replace(Regex("```\\s*markdown", RegexOption.IGNORE_CASE), "````markdown"))
                inMarkdownBlock = true
            }
            // Check if this line ends a code block while we're in a markdown block
            else if (inMarkdownBlock && line.trim() == "```") {
                // End of markdown block - use 4 backticks instead
                result.add("````")
                inMarkdownBlock = false
            }
            else {
                // Regular line - pass through unchanged
                result.add(line)
            }

            i++
        }

        return result.joinToString(options.lineEnding)
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
            append(dateFormatter.format(task.createdAt.atZone(java.time.ZoneOffset.UTC)))
            append(options.lineEnding)
            append("modified: ")
            append(dateFormatter.format(task.modifiedAt.atZone(java.time.ZoneOffset.UTC)))
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
            append(dateFormatter.format(feature.createdAt.atZone(java.time.ZoneOffset.UTC)))
            append(options.lineEnding)
            append("modified: ")
            append(dateFormatter.format(feature.modifiedAt.atZone(java.time.ZoneOffset.UTC)))
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
            append(dateFormatter.format(project.createdAt.atZone(java.time.ZoneOffset.UTC)))
            append(options.lineEnding)
            append("modified: ")
            append(dateFormatter.format(project.modifiedAt.atZone(java.time.ZoneOffset.UTC)))
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
