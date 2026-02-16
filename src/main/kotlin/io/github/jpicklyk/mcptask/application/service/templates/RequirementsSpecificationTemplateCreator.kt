package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for capturing functional and non-functional requirements.
 * Sections set a documentation floor — the AI fills in the details using its own judgment.
 */
object RequirementsSpecificationTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Requirements Specification",
            description = "Template for capturing functional requirements, optional enhancements, and constraints.",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("requirements", "specification", "acceptance-criteria", "constraints", "documentation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Must-Have Requirements",
                usageDescription = "Critical functional requirements that must be implemented for successful delivery",
                contentSample = """Document each requirement with:
- Description of the functionality needed
- User story: As a [user type], I want [functionality] so that [benefit]
- Acceptance criteria: Given [context], when [action], then [expected result]

Include requirements for: core functionality, data handling, integrations, and business logic.""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("functional", "core", "critical", "acceptance-criteria", "requirements", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Nice-to-Have Features",
                usageDescription = "Optional features and enhancements that would improve the solution but aren't critical",
                contentSample = """List optional enhancements with:
- Feature description and value to users
- Rough effort estimate
- Dependencies on must-have requirements""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("optional", "enhancements", "future", "nice-to-have", "requirements", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Constraints & Non-Functional Requirements",
                usageDescription = "Technical constraints and non-functional requirements that must be considered",
                contentSample = """Document applicable constraints:
- Technology stack requirements or limitations
- Performance requirements (response times, throughput, scalability)
- Security requirements (authentication, authorization, data protection)
- Data requirements (volume, retention, backup/recovery)
- Integration constraints (API limits, data formats, network requirements)
- Platform/browser support requirements""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("constraints", "non-functional", "performance", "security", "requirements", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification",
                usageDescription = "Define acceptance criteria for this feature as a JSON array. Feature criteria verify that child tasks integrate correctly and the feature works end-to-end. Format: [{\"criteria\": \"description\", \"pass\": false}, ...] Set pass to true only after verifying each condition across the completed tasks. The MCP server will block feature completion until all criteria pass.",
                contentSample = """[{"criteria": "All child tasks completed", "pass": false}, {"criteria": "End-to-end flow works as specified", "pass": false}, {"criteria": "No regressions in existing test suite", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 3,
                isRequired = true,
                tags = listOf("verification", "acceptance-criteria", "quality", "role:review")
            )
        )

        return Pair(template, sections)
    }
}
