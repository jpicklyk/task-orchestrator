package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for scoped codebase investigation with target files, key questions,
 * and structured findings. Prevents infinite exploration by requiring explicit scope and
 * out-of-scope boundaries.
 */
object CodebaseExplorationTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Codebase Exploration",
            description = "Scoped codebase investigation with target files, key questions, and structured findings. Prevents infinite exploration by requiring explicit scope and out-of-scope boundaries. These tasks are inherently parallelizable — multiple explorations can run as concurrent subagents.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("planning", "exploration", "research", "ai-optimized")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Exploration Scope",
                usageDescription = "What to investigate and what NOT to investigate. List specific files and patterns. Narrow scope prevents context waste.",
                contentSample = """## Target Files

- `path/to/file.kt` — [why]

## Target Patterns

- [What to understand]

## Out of Scope

- [What NOT to investigate]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("scope", "investigation", "boundaries")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Key Questions",
                usageDescription = "Specific questions this exploration must answer. Each should be answerable from the codebase.",
                contentSample = """1. [Question 1]
2. [Question 2]
3. [Question 3]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("questions", "research", "discovery")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Findings",
                usageDescription = "Structured answers to key questions with file:line references. Becomes reference material for planning.",
                contentSample = """### Q1: [Question]

**Answer:** [Answer with file:line references]

### Q2: [Question]

**Answer:** [Answer]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("findings", "answers", "references")
            )
        )

        return Pair(template, sections)
    }
}
