package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for tracking implementation progress.
 */
object ImplementationProgressTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Implementation Progress",
            description = "Tracks the progress, history, and current state of a task's implementation",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("tracking", "progress", "implementation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Current Status",
                usageDescription = "Summarizes the current state of implementation. Use this to quickly assess progress and blockers.",
                contentSample = """## Current Status

- **Stage**: [Not Started / In Progress / Ready for Review / Completed]
- **Completion**: [Estimated percentage complete]
- **Blockers**: [Any blocking issues]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("status", "progress")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation History",
                usageDescription = "Records the history of implementation efforts. Use this to understand how the implementation has evolved over time.",
                contentSample = """## Implementation History

- **[Date]**:
    - [What was implemented]
    - [Decisions made]
    - [Issues encountered]

- **[Date]**:
    - [What was implemented]
    - [Decisions made]
    - [Issues encountered]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("history", "timeline")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Current Implementation",
                usageDescription = "Shows the current implementation approach or code. Use this to understand the current solution.",
                contentSample = """## Current Implementation

```kotlin
// Current implementation snippet or approach
```
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("implementation", "code")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Next Steps",
                usageDescription = "Lists the next actions to take for implementation. Use this to determine what needs to be done next.",
                contentSample = """## Next Steps

1. [Next immediate task to complete]
2. [Following task]
3. [Future consideration]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("next-steps", "planning")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Open Questions",
                usageDescription = "Captures unresolved questions about the implementation. Use this to identify areas needing clarification.",
                contentSample = """## Open Questions

- [Unresolved question 1]
- [Unresolved question 2]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = false,
                tags = listOf("questions", "clarification")
            )
        )

        return Pair(template, sections)
    }
}
