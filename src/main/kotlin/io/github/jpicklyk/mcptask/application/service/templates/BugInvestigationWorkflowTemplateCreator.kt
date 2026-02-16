package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for documenting bug investigations.
 * Sections set a documentation floor — the AI fills in the details using its own judgment.
 */
object BugInvestigationWorkflowTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Bug Investigation",
            description = "Template for documenting bug investigation findings, root cause analysis, and fix verification.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("bug", "investigation", "debugging", "task-type-bug")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Investigation Findings",
                usageDescription = "Document the bug investigation findings including reproduction results and diagnostic data",
                contentSample = """Document the following:
- Reproduction steps and results (consistently reproducible? conditions required?)
- Environment and conditions where the bug occurs
- Diagnostic data collected (logs, stack traces, error messages)
- Scope of impact (what features/users are affected)
- Related issues or patterns found""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("investigation", "reproduction", "diagnosis", "role:work")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Root Cause",
                usageDescription = "Document the identified root cause, supporting evidence, and contributing factors",
                contentSample = """Document the following:
- Root cause statement (clear, concise description)
- Supporting evidence (code paths, data flow, logs)
- When and how the issue was likely introduced
- Contributing factors (environment, configuration, edge cases)""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("root-cause", "analysis", "role:work")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Fix & Verification",
                usageDescription = "Document the fix approach, changes made, test results, and prevention measures",
                contentSample = """Document the following:
- Fix approach and rationale
- Files and components changed
- Tests added to prevent regression
- Regression test results
- Prevention measures (monitoring, validation, documentation)""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("fix-implementation", "verification", "testing", "role:work", "role:review")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification",
                usageDescription = "Define one or more acceptance criteria as a JSON array. Each criterion describes a condition that must be verified before this bug fix can be completed. Format: [{\"criteria\": \"description\", \"pass\": false}, ...] Set pass to true only after personally confirming each condition. The MCP server will block completion until all criteria pass.",
                contentSample = """[{"criteria": "Bug can no longer be reproduced", "pass": false}, {"criteria": "Regression test added", "pass": false}, {"criteria": "No side effects in related functionality", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 3,
                isRequired = true,
                tags = listOf("verification", "acceptance-criteria", "quality", "role:review")
            )
        )

        return Pair(template, sections)
    }
}
