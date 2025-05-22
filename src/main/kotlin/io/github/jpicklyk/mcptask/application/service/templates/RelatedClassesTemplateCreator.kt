package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for documenting related classes and components.
 */
object RelatedClassesTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Related Classes and Components",
            description = "Maps the relationships between code entities and components relevant to a task or feature",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("classes", "components", "relationships")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Primary Classes",
                usageDescription = "Lists the main classes involved in this task/feature with their purposes and key elements. Use this to understand the core code entities.",
                contentSample = """## Primary Classes

- **[ClassName]**
    - **Purpose**: [What this class does]
    - **Key Methods**:
        - `method1()`: [description]
        - `method2()`: [description]
    - **Important Fields**:
        - `field1`: [description]
        - `field2`: [description]
    - **Location**: `path/to/class`

- **[ClassName]**
    - **Purpose**: [What this class does]
    - **Key Methods**: [...]
    - **Important Fields**: [...]
    - **Location**: `path/to/class`
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("classes", "components")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Component Interactions",
                usageDescription = "Explains how components interact with each other. Use this to understand data flow and dependencies between components.",
                contentSample = """## Component Interactions

- **[Component1]** ? **[Component2]**
    - **Interaction Type**: [Data flow, method calls, etc.]
    - **Data Passed**: [Description of data exchanged]
    - **Triggering Events**: [What causes this interaction]

- **[Component2]** ? **[Component3]**
    - **Interaction Type**: [...]
    - **Data Passed**: [...]
    - **Triggering Events**: [...]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("interactions", "data-flow")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Data Flow Diagram",
                usageDescription = "Visualizes the flow of data between components. Use this to understand the sequence and structure of interactions.",
                contentSample = """## Data Flow Diagram

```
[Component1] ? (data) ? [Component2] ? (processed data) ? [Component3]
```
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = false,
                tags = listOf("diagram", "data-flow", "visualization")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Key Dependencies",
                usageDescription = "Lists important libraries and modules used by these components. Use this to understand external dependencies and their purpose.",
                contentSample = """## Key Dependencies

- **[Library/Module]**: [How it's used in these components]
- **[Library/Module]**: [How it's used in these components]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = false,
                tags = listOf("dependencies", "libraries")
            )
        )

        return Pair(template, sections)
    }
}
