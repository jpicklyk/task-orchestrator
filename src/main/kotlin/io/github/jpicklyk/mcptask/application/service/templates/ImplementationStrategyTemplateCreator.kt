package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for implementation strategy guidance.
 */
object ImplementationStrategyTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Implementation and Usage Strategy",
            description = "Provides guidance on how to implement and use these templates effectively",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("strategy", "guidance", "implementation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Implementation and Usage Strategy",
                usageDescription = "Outlines strategies for implementing and using these templates effectively. Use this to understand how to integrate templates into your workflow.",
                contentSample = """## Implementation and Usage Strategy

<!-- Subsection: Database Storage -->
<!-- usageDescription: Describes how to store templates in the SQLite database. Use this to implement the technical foundation for templates. -->

1. **Database Storage**:
   - Store templates in the SQLite database in a structured format
   - Create appropriate tables for templates and sections
   - Ensure proper indexing for efficient querying

<!-- Subsection: MCP Tool Creation -->
<!-- usageDescription: Explains how to create MCP tools for template management. Use this to understand the implementation approach. -->

2. **MCP Tool Creation**:
   - Implement MCP tools for creating, listing, and applying templates
   - Register tools with proper descriptions for AI guidance
   - Ensure proper parameter validation and error handling

<!-- Subsection: Template Evolution -->
<!-- usageDescription: Provides guidance on refining templates over time. Use this to continually improve template effectiveness. -->
3. **Template Evolution**:
   - Track which templates and fields provide the most value
   - Refine templates based on actual AI interaction patterns
   - Allow project-specific template customization

<!-- Subsection: AI Guidance -->
<!-- usageDescription: Outlines how to provide guidance to AI when using templates. Use this to maximize AI effectiveness with templates. -->
4. **AI Guidance**:
   - Include instructions in each template for how the AI should interpret and use it
   - Add metadata about information priority and relevance
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("strategy", "implementation", "usage")
            )
        )

        return Pair(template, sections)
    }
}
