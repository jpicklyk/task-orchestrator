package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for code review and analysis of existing code.
 */
object CodeReviewTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Code Review and Analysis",
            description = "Provides analysis and recommendations for improving existing code",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("review", "analysis", "code-quality")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Code Quality Assessment",
                usageDescription = "Evaluates the quality of the code across multiple dimensions. Use this to understand strengths and weaknesses of the current implementation.",
                contentSample = """## Code Quality Assessment

- **Structure**: [Assessment of code organization]
- **Performance**: [Performance considerations]
- **Maintainability**: [Maintainability assessment]
- **Test Coverage**: [Assessment of test coverage]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("quality", "assessment")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Improvement Opportunities",
                usageDescription = "Identifies specific opportunities to improve the code. Use this to prioritize enhancement efforts.",
                contentSample = """## Improvement Opportunities

- **Refactoring**: [Suggested refactoring opportunities]
- **Optimizations**: [Potential optimizations]
- **Pattern Application**: [Better pattern applications]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("improvements", "refactoring")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Security & Edge Cases",
                usageDescription = "Highlights security concerns and edge cases. Use this to address potential vulnerabilities and robustness issues.",
                contentSample = """## Security & Edge Cases

- **Security Concerns**: [Any security issues identified]
- **Edge Cases**: [Unhandled edge cases]
- **Resource Management**: [Resource usage concerns]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = false,
                tags = listOf("security", "edge-cases")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Suggested Changes",
                usageDescription = "Lists specific changes recommended to improve the code. Use this as an actionable checklist of improvements.",
                contentSample = """## Suggested Changes

1. [Specific suggested change 1]
2. [Specific suggested change 2]
3. [Specific suggested change 3]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("improvements", "changes")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Code Examples",
                usageDescription = "Provides concrete examples of how to improve the code. Use this to see practical implementations of the suggested changes.",
                contentSample = """## Code Examples

```kotlin
// Before
[problematic code example]

// After (suggested)
[improved code example]
```
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = false,
                tags = listOf("examples", "code")
            )
        )

        return Pair(template, sections)
    }
}
