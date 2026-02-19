package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for structuring a feature as an engineering plan with problem definition,
 * architecture, implementation phases, and verification. Composable with Requirements Specification
 * for full coverage. Use for Detailed-tier planning.
 */
object FeaturePlanTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Feature Plan",
            description = "Structures a feature as an engineering plan with problem definition, architecture, implementation phases, and verification. Composable with Requirements Specification for full coverage. Use for Detailed-tier planning.",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("planning", "architecture", "ai-optimized", "engineering")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Problem Statement",
                usageDescription = "Define the problem being solved, why it matters, and any reference material. Include design constraints that scope the solution.",
                contentSample = """## Problem

[What problem does this feature solve?]

## Why It Matters

[Impact of not solving this]

## Design Constraints

- [Constraint 1]
- [Constraint 2]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("planning", "problem-definition", "constraints", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Architecture Overview",
                usageDescription = "High-level architecture: core concept, key mechanisms, component interaction. The 'what' before the detailed 'how.'",
                contentSample = """## Core Concept

[One paragraph describing the central idea]

## Key Mechanisms

1. [Mechanism 1]
2. [Mechanism 2]

## Component Interaction

[How the pieces fit together]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("architecture", "design", "components", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation Phases",
                usageDescription = "Ordered phases targeting architectural layers. Each phase lists files, code patterns, and rationale.",
                contentSample = """## Phase 1: [Layer Name]

**Files:**
- `path/to/file.kt` — [change]

**Pattern:**
```kotlin
// Code snippet
```

**Rationale:** [Why]

---

## Phase 2: [Layer Name]

[Same structure]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("implementation", "phases", "planning", "role:queue", "role:work")
            ),
            TemplateSection(
                templateId = templateId,
                title = "File Change Manifest",
                usageDescription = "Every file affected, with change type and description. The 'blast radius' assessment.",
                contentSample = """| File | Change Type | Description |
|------|------------|-------------|
| `path/to/file.kt` | Modify | [What changes] |""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("files", "manifest", "impact-analysis", "role:queue", "role:work")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Design Decisions",
                usageDescription = "Significant design choices with alternatives considered and rationale.",
                contentSample = """### Why [choice A] over [choice B]?

[Rationale]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = false,
                tags = listOf("design", "decisions", "rationale", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Execution Notes",
                usageDescription = "Optional. Note parallelization opportunities, delegation preferences, or team coordination needs. Which phases can run independently? Which need sequential ordering? Should work be delegated to subagents (focused tasks, report back) or an agent team (collaborative, peer discussion)? Skip this section for straightforward work.",
                contentSample = """## Parallelization

- Phases 1-3: sequential (each builds on prior)
- Phases 4-6: can run in parallel (independent modules)

## Delegation

- [subagents | agent team | single session]
- Rationale: [why this approach suits the work]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 5,
                isRequired = false,
                tags = listOf("execution", "parallelization", "delegation", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Risks & Mitigations",
                usageDescription = "What could go wrong and how to handle it.",
                contentSample = """| Risk | Severity | Mitigation |
|------|----------|------------|
| [Risk] | High/Med/Low | [Mitigation] |""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 6,
                isRequired = false,
                tags = listOf("risks", "mitigations", "planning", "role:queue")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification",
                usageDescription = "Acceptance criteria for the plan. All criteria must pass before implementation begins.",
                contentSample = """[]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 7,
                isRequired = true,
                tags = listOf("verification", "acceptance-criteria", "quality", "role:review")
            )
        )

        return Pair(template, sections)
    }
}
