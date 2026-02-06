package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for documenting technical approach and architectural decisions.
 * Sections set a documentation floor — the AI fills in the details using its own judgment.
 */
object TechnicalApproachTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Technical Approach",
            description = "Minimum documentation for technical decisions and architectural approach. Sections prompt the AI to document key decisions without prescribing how to plan.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("technical", "architecture", "implementation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Technical Decisions",
                usageDescription = "Document key technical decisions, chosen technologies, and architectural approach",
                contentSample = """Document the following:
- Components identified and their responsibilities
- Technology choices with brief rationale
- Data management approach (storage, transformations, access patterns)
- Error handling strategy
- Key risks and mitigation plans
- Testing approach""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("technical-details", "planning", "decisions")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Integration Considerations",
                usageDescription = "Document external dependencies, internal interfaces, and integration concerns",
                contentSample = """Document applicable items:
- External dependencies and fallback strategies
- Internal interfaces and data contracts
- Database schema changes or migration needs
- Configuration and environment requirements
- API contracts (if creating or modifying APIs)""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("integration", "dependencies", "validation")
            )
        )

        return Pair(template, sections)
    }
}
