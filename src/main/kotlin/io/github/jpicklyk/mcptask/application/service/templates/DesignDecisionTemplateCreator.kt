package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for structuring an architectural decision with context, options analysis,
 * and recommendation. Use when planning encounters a fork with multiple valid approaches.
 */
object DesignDecisionTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Design Decision",
            description = "Structures an architectural decision with context, options analysis, and recommendation. Use when planning encounters a fork with multiple valid approaches.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("planning", "architecture", "design-decision", "ai-optimized")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Decision Context",
                usageDescription = "What needs to be decided, why, and what constraints apply.",
                contentSample = """## Decision Required

[What needs to be decided]

## Context

[Why this matters]

## Constraints

- [Constraint 1]
- [Constraint 2]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("context", "constraints", "decision")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Options Analysis",
                usageDescription = "Each viable option with approach, pros, cons, and effort.",
                contentSample = """### Option A: [Name]

**Approach:** [Description]
**Pros:** [Advantages]
**Cons:** [Disadvantages]
**Effort:** [Low/Medium/High]

### Option B: [Name]

[Same]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("options", "analysis", "tradeoffs")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Recommendation",
                usageDescription = "Chosen option with rationale referencing specific constraints.",
                contentSample = """## Chosen: [Option Name]

**Rationale:** [Why this satisfies constraints]

**Key Factor:** [Deciding factor]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("recommendation", "rationale", "decision")
            )
        )

        return Pair(template, sections)
    }
}
