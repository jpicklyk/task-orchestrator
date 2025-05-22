package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for defining feature context and specifications.
 */
object FeatureContextTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Feature Context",
            description = "Defines the scope, purpose, and implementation approach for a specific feature",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("planning", "requirements", "design")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Feature Overview",
                usageDescription = "Provides a concise description of the feature and its value proposition. Use this to understand the feature's core purpose.",
                contentSample = """## Feature Overview

[Concise description of the feature, its primary purpose and value proposition]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("overview", "purpose")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Business Context",
                usageDescription = "Explains the business rationale and user needs addressed by this feature. Use this to understand why the feature is important.",
                contentSample = """## Business Context

- **User Need**: [What user needs or problems this feature addresses]
- **Success Metrics**: [How success of this feature will be measured (KPIs)]
- **Target Users**: [Primary user segments this feature serves]
- **Business Impact**: [Expected impact on business goals or metrics]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("business", "requirements")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Technical Scope",
                usageDescription = "Defines the boundaries and key components of the feature. Use this to understand what's included in the feature's implementation.",
                contentSample = """## Technical Scope

- **Feature Boundaries**: [Clear definition of what's in/out of scope]
- **Core Components**:
    - [Component 1]: [Description and responsibility]
    - [Component 2]: [Description and responsibility]
- **Key Interfaces**:
    - [Interface 1]: [Description of what it connects to]
    - [Interface 2]: [Description of what it connects to]
- **Data Requirements**: [Key data entities and storage considerations]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("technical", "scope", "boundaries")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Dependencies",
                usageDescription = "Lists what this feature depends on and what depends on it. Use this to understand the feature's place in the system.",
                contentSample = """## Dependencies

- **Upstream Dependencies**:
    - [Dependency 1]: [How this feature depends on it]
    - [Dependency 2]: [How this feature depends on it]
- **Downstream Consumers**:
    - [Consumer 1]: [How it will use this feature]
    - [Consumer 2]: [How it will use this feature]
- **External Systems**: [3rd party services or APIs this feature interacts with]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("dependencies", "integrations")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Design and Architecture",
                usageDescription = "Describes the technical implementation approach for this feature. Use this to understand how the feature should be built.",
                contentSample = """## Design and Architecture

- **Architectural Approach**: [Description of the technical approach]
- **Design Patterns**: [Key patterns being applied]
- **Technical Constraints**: [Technical limitations or requirements]
- **Performance Considerations**: [Key performance requirements]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = true,
                tags = listOf("architecture", "design", "technical")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Integration Points",
                usageDescription = "Identifies how this feature integrates with the rest of the system. Use this to understand connection points and interfaces.",
                contentSample = """## Integration Points

- **APIs**: [APIs this feature will expose or consume]
- **Event Flows**: [Events this feature will publish or subscribe to]
- **UI Integration**: [How this feature integrates with the UI]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 5,
                isRequired = false,
                tags = listOf("integration", "apis", "events")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Feature Roadmap",
                usageDescription = "Outlines the planned evolution of this feature. Use this to understand immediate implementation priorities and future direction.",
                contentSample = """## Feature Roadmap

- **Phase 1**: [Initial implementation scope]
- **Phase 2**: [Planned enhancements]
- **Future Considerations**: [Potential future directions]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 6,
                isRequired = false,
                tags = listOf("roadmap", "planning", "phases")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Related Resources",
                usageDescription = "Lists links to relevant documentation and resources. Use this to find additional information about the feature.",
                contentSample = """## Related Resources

- [Link to design mockups]
- [Link to technical RFC/specification]
- [Link to user research]
- [Link to API documentation]
""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 7,
                isRequired = false,
                tags = listOf("resources", "links", "documentation")
            )
        )

        return Pair(template, sections)
    }
}
