package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for detailed implementation specs covering one phase or component.
 * Precise code change points, snippets, and test requirements. Use after exploration
 * and design decisions are complete.
 */
object ImplementationSpecificationTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Implementation Specification",
            description = "Detailed implementation spec for one phase or component. Precise code change points, snippets, and test requirements. Use after exploration and design decisions are complete. These tasks typically depend on prior phases — check dependency ordering before parallelizing.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("planning", "implementation", "specification", "ai-optimized")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Scope & Boundaries",
                usageDescription = "What this spec covers and doesn't. Prerequisites that must complete first.",
                contentSample = """## In Scope

- [What this covers]

## Out of Scope

- [Handled elsewhere]

## Prerequisites

- [What must complete first]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("scope", "boundaries", "prerequisites")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Code Change Points",
                usageDescription = "Every method, class, or file needing modification with line numbers and change description.",
                contentSample = """| File | Method/Location | Line(s) | Change |
|------|----------------|---------|--------|
| `path/to/file.kt` | `methodName()` | ~123 | [Change] |""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("code-changes", "files", "methods")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Technical Specification",
                usageDescription = "Code snippets, data structures, algorithmic details. Detailed enough to implement without ambiguity.",
                contentSample = """## Data Model

```kotlin
// New/modified structures
```

## Logic

```kotlin
// Key implementation
```

## Integration Points

[How this connects]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("technical", "specification", "code")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Test Plan",
                usageDescription = "Specific test cases: happy path, error paths, edge cases, backward compatibility.",
                contentSample = """## Unit Tests

- [Scenario → expected result]

## Integration Tests

- [End-to-end scenario]

## Backward Compatibility

- [Existing behavior unchanged]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("testing", "test-plan", "quality")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification",
                usageDescription = "Acceptance criteria for this spec. All must pass before implementation begins.",
                contentSample = """[]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 4,
                isRequired = true,
                tags = listOf("verification", "acceptance-criteria", "quality")
            )
        )

        return Pair(template, sections)
    }
}
