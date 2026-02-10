package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for defining completion criteria and readiness checklists.
 * Sections set a documentation floor — the AI fills in the details using its own judgment.
 */
object DefinitionOfDoneTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Definition of Done",
            description = "Template for defining minimum completion criteria and production readiness checklist.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = false,
            createdBy = "System",
            tags = listOf("completion", "done", "checklist", "handoff", "quality")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Completion Checklist",
                usageDescription = "Minimum criteria confirming the implementation is functionally complete",
                contentSample = """Verify and document:
- [ ] Core functionality implemented and working
- [ ] Acceptance criteria met
- [ ] Edge cases and error conditions handled
- [ ] Unit and integration tests written and passing
- [ ] Code reviewed (or self-reviewed)
- [ ] Documentation updated where needed""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("implementation", "quality", "testing", "checklist")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Production Readiness",
                usageDescription = "Criteria for production deployment readiness — fill in only applicable items",
                contentSample = """Verify applicable items:
- [ ] Builds successfully in CI/CD pipeline
- [ ] Deployed and verified in staging/test environment
- [ ] Logging and monitoring in place
- [ ] Performance meets requirements
- [ ] Security implications reviewed
- [ ] Rollback plan documented""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("deployment", "monitoring", "performance", "security", "checklist")
            )
        )

        return Pair(template, sections)
    }
}
