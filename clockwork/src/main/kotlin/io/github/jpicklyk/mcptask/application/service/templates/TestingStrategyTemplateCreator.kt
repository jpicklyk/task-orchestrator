package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for documenting testing approach and acceptance criteria.
 * Sections set a documentation floor — the AI fills in the details using its own judgment.
 */
object TestingStrategyTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Test Plan",
            description = "Template for documenting testing approach, test coverage areas, and acceptance criteria.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("testing", "quality", "validation", "qa")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Test Coverage",
                usageDescription = "Test cases and coverage areas for thorough validation",
                contentSample = """Document applicable test areas:
- Unit tests: key classes/functions to test, edge cases to cover
- Integration tests: component interactions, data flow verification
- API tests: endpoints, success/error responses, validation
- End-to-end tests: critical user workflows and business processes""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("testing", "coverage", "unit-tests", "integration-tests", "role:queue", "role:work")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Acceptance Criteria",
                usageDescription = "Measurable criteria that define completion",
                contentSample = """Define measurable criteria:
- Functional: core functionality works, user workflows complete, error handling correct
- Quality: test coverage meets standard, tests pass consistently, no critical issues
- Performance: response times acceptable, resource usage within bounds""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("acceptance-criteria", "completion", "quality", "role:queue", "role:review")
            )
        )

        return Pair(template, sections)
    }
}
