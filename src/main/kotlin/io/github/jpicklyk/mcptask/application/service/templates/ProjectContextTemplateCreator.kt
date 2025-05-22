package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for project context documentation.
 */
object ProjectContextTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Project Context",
            description = "Provides high-level context about the project, its architecture, and technical standards",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("project", "architecture", "standards")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Project Overview",
                usageDescription = "Provides a concise summary of the project's purpose, goals, and scope. Use this to understand what the project aims to accomplish.",
                contentSample = """## Project Overview

[Brief description of the project, its purpose, and main goals]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("overview", "purpose")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Architectural Context",
                usageDescription = "Outlines the architectural patterns, key components, and module structure. Use this to understand how the codebase is organized.",
                contentSample = """## Architectural Context

- **Pattern**: [MVVM, Clean Architecture, etc.]
- **Key Components**:
    - [Component Name]: [Brief description]
    - [Component Name]: [Brief description]
- **Module Structure**:
    - `:app` - [Description]
    - `:feature:xyz` - [Description]
    - `:core:abc` - [Description]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("architecture", "structure")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Technology Stack",
                usageDescription = "Lists the main technologies, libraries, and frameworks used in the project. Use this to understand the technical foundation of the codebase.",
                contentSample = """## Technology Stack

- **UI**: [Jetpack Compose/XML Views]
- **Networking**: [Retrofit, Ktor]
- **Database**: [Room, Realm]
- **DI**: [Hilt, Koin]
- **Async**: [Coroutines, RxJava]
- **Testing**: [JUnit, Espresso, Robolectric]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("technology", "stack", "libraries")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Coding Standards",
                usageDescription = "Describes the project's coding conventions and standards. Use this to ensure consistency with established patterns.",
                contentSample = """## Coding Standards

- **Naming**: [Package naming conventions, class naming, etc.]
- **Architecture**: [Key architectural principles to follow]
- **Testing**: [Testing requirements and standards]
- **Documentation**: [Documentation expectations]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("standards", "conventions")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Relevant Resources",
                usageDescription = "Lists links to important project resources and documentation. Use this to find additional information about the project.",
                contentSample = """## Relevant Resources

- [Link to architecture diagram]
- [Link to API documentation]
- [Link to design guidelines]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = false,
                tags = listOf("resources", "links", "documentation")
            )
        )

        return Pair(template, sections)
    }
}
