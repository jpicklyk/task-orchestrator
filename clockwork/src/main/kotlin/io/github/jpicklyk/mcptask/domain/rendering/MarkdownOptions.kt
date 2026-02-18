package io.github.jpicklyk.mcptask.domain.rendering

/**
 * Configuration options for markdown rendering.
 *
 * Controls various aspects of how entities are rendered to markdown format.
 */
data class MarkdownOptions(
    /**
     * Whether to include YAML frontmatter with entity metadata.
     * Default: true
     */
    val includeFrontmatter: Boolean = true,

    /**
     * Heading level offset for section titles.
     * Section titles use ## by default, this offset adjusts all heading levels.
     * Default: 0 (no offset)
     */
    val headingLevelOffset: Int = 0,

    /**
     * Language tag to use for code blocks when content format is CODE.
     * Default: "text"
     */
    val defaultCodeLanguage: String = "text",

    /**
     * Line ending style to use in generated markdown.
     * Default: "\n" (LF - Unix style)
     */
    val lineEnding: String = "\n"
) {
    init {
        require(headingLevelOffset >= 0) { "Heading level offset must be non-negative" }
        require(lineEnding.isNotEmpty()) { "Line ending must not be empty" }
    }
}
