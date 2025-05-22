package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for task implementation details.
 */
object TaskImplementationTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Task Implementation",
            description = "Defines the specific work to be done for a task, including context, requirements, and implementation steps",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("task", "implementation", "requirements")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Task Objective",
                usageDescription = "Clearly states what this task should accomplish. Use this to understand the primary goal of the task.",
                contentSample = """## Task Objective

[Clear, concise statement of what this task should accomplish]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("objective", "goal")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation Context",
                usageDescription = "Provides context about where this task fits within the feature and codebase. Use this to understand the task's scope and relationships.",
                contentSample = """## Implementation Context

- **Feature Area**: [Which feature this belongs to]
- **Affected Components**: [List of components this will modify]
- **New Components**: [Any new components being created]
- **Related PRs/Tasks**: [References to related work]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("context", "scope")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Requirements",
                usageDescription = "Lists specific requirements that must be met by the implementation. Use this as a checklist of essential functionality.",
                contentSample = """## Requirements

1. [Specific requirement 1]
2. [Specific requirement 2]
3. [Specific requirement 3]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("requirements", "specifications")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Technical Approach",
                usageDescription = "Describes the recommended implementation approach. Use this to understand the suggested technical solution.",
                contentSample = """## Technical Approach

[Description of the recommended implementation approach]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("approach", "solution")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation Steps",
                usageDescription = "Breaks down the implementation into discrete steps. Use this as a step-by-step guide for completing the task.",
                contentSample = """## Implementation Steps

1. [ ] [Step 1 with technical details]
2. [ ] [Step 2 with technical details]
3. [ ] [Step 3 with technical details]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = true,
                tags = listOf("steps", "implementation")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Edge Cases & Constraints",
                usageDescription = "Identifies edge cases and implementation constraints. Use this to ensure robustness and maintainability.",
                contentSample = """## Edge Cases & Constraints

- [Edge case 1 to handle]
- [Performance constraint]
- [Backward compatibility requirement]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 5,
                isRequired = false,
                tags = listOf("edge-cases", "constraints")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Testing Criteria",
                usageDescription = "Specifies how the implementation should be tested. Use this to ensure proper test coverage and validation.",
                contentSample = """## Testing Criteria

- [ ] [Test scenario 1]
- [ ] [Test scenario 2]
- [ ] [Test scenario 3]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 6,
                isRequired = false,
                tags = listOf("testing", "validation")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Completion Checklist",
                usageDescription = "Provides a checklist for determining when the task is complete. Use this to verify all aspects have been addressed.",
                contentSample = """## Completion Checklist

- [ ] Implementation complete
- [ ] Unit tests written
- [ ] UI tests (if applicable)
- [ ] Documentation updated
- [ ] Handles all edge cases
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 7,
                isRequired = true,
                tags = listOf("checklist", "completion")
            )
        )

        return Pair(template, sections)
    }
}
