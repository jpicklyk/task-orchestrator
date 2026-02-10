package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for documenting task implementation.
 * Sections set a documentation floor — the AI fills in the details using its own judgment.
 */
object TaskImplementationWorkflowTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Task Implementation",
            description = "Template for documenting task implementation approach, progress notes, and verification results.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("implementation", "workflow")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Analysis & Approach",
                usageDescription = "Document the implementation approach, scope assessment, and key decisions before coding",
                contentSample = """Document the following:
- Files to modify and new files to create
- Dependencies and libraries involved
- Chosen implementation approach and rationale
- Scope assessment (what's in and out of scope)
- Key risks or unknowns identified""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("analysis", "planning")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation Notes",
                usageDescription = "Document significant implementation details, deviations from plan, and decisions made during coding",
                contentSample = """Document as applicable:
- Notable implementation details or patterns used
- Deviations from the planned approach and why
- Problems encountered and how they were resolved
- Decisions made during implementation""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("implementation", "execution")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification & Results",
                usageDescription = "Document testing approach, test results, and verification that acceptance criteria are met",
                contentSample = """Document the following:
- Tests written or updated
- Test results summary (pass/fail)
- Acceptance criteria verification
- Build verification results
- Known limitations or follow-up items""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("testing", "validation")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification",
                usageDescription = "Define one or more acceptance criteria as a JSON array. Each criterion describes a condition that must be verified before this task can be completed. Format: [{\"criteria\": \"description\", \"pass\": false}, ...] Set pass to true only after personally confirming each condition. The MCP server will block completion until all criteria pass.",
                contentSample = """[{"criteria": "Unit tests pass for new/modified code", "pass": false}, {"criteria": "No regressions in existing test suite", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 3,
                isRequired = true,
                tags = listOf("verification", "acceptance-criteria", "quality")
            )
        )

        return Pair(template, sections)
    }
}
