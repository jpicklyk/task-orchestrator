package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for documenting technical approach and implementation strategy.
 */
object TechnicalApproachTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Technical Approach",
            description = "Template for documenting technical solution approach, architecture decisions, and implementation strategy for features and tasks.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("technical", "architecture", "implementation", "strategy", "documentation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Architecture Overview",
                usageDescription = "High-level technical architecture and design decisions for the implementation",
                contentSample = """## Architecture Overview

### System Design
[Describe the overall system design and how this component fits into the larger architecture]

### Key Components
- **[Component 1]**: [Purpose and responsibility]
- **[Component 2]**: [Purpose and responsibility] 
- **[Component 3]**: [Purpose and responsibility]

### Design Patterns
[List and explain any design patterns being used]
- **Pattern Name**: [Why this pattern was chosen and how it's applied]

### Data Flow
```
[User/Client] → [Component A] → [Component B] → [Database/External Service]
```

[Describe the flow of data through the system and any transformations]

### Integration Points
- **[External System/Service]**: [How integration works, APIs used, data exchange format]
- **[Internal System]**: [How components communicate, interfaces, protocols]

### Technology Stack
- **Language/Framework**: [Specific technology and version]
- **Database**: [Database technology and schema considerations]
- **Libraries/Dependencies**: [Key libraries and their purposes]
- **Tools**: [Development, testing, and deployment tools]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("architecture", "design", "components")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Key Dependencies",
                usageDescription = "External dependencies, libraries, and services required for implementation",
                contentSample = """## Key Dependencies

### External Libraries
| Library | Version | Purpose | Justification |
|---------|---------|---------|---------------|
| [Library Name] | [Version] | [What it does] | [Why chosen over alternatives] |
| [Library Name] | [Version] | [What it does] | [Why chosen over alternatives] |

### Internal Dependencies
- **[Module/Service Name]**: [What functionality is needed and how it's accessed]
- **[Module/Service Name]**: [What functionality is needed and how it's accessed]

### External Services
- **[Service Name]**: [API endpoints, authentication, rate limits, SLA considerations]
- **[Service Name]**: [API endpoints, authentication, rate limits, SLA considerations]

### Database Dependencies
- **Schema Changes**: [Any new tables, columns, or indexes required]
- **Data Migration**: [Any data migration or transformation needed]
- **Performance Considerations**: [Indexing strategy, query optimization]

### Configuration Dependencies
- **Environment Variables**: [New configuration values needed]
- **Feature Flags**: [Any feature toggles or gradual rollout mechanisms]
- **Security Configurations**: [Authentication, authorization, encryption requirements]

### Development Dependencies
- **Build Tools**: [Any new build or compilation requirements]
- **Testing Dependencies**: [Test frameworks, mock services, test data]
- **Deployment Dependencies**: [Infrastructure, containers, deployment scripts]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("dependencies", "libraries", "services")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation Strategy",
                usageDescription = "Detailed implementation plan, phases, and technical considerations",
                contentSample = """## Implementation Strategy

### Implementation Phases
1. **Phase 1: [Phase Name]**
   - **Goal**: [What this phase accomplishes]
   - **Deliverables**: [Specific outputs and milestones]
   - **Duration**: [Estimated time]
   - **Dependencies**: [What must be completed first]

2. **Phase 2: [Phase Name]**
   - **Goal**: [What this phase accomplishes]
   - **Deliverables**: [Specific outputs and milestones]
   - **Duration**: [Estimated time]
   - **Dependencies**: [What must be completed first]

### Technical Approach
- **Code Organization**: [How code will be structured, packages, modules]
- **Error Handling**: [Strategy for error handling and recovery]
- **Logging and Monitoring**: [What will be logged and monitored]
- **Performance Considerations**: [Performance requirements and optimization strategies]

### Security Considerations
- **Authentication/Authorization**: [How security will be implemented]
- **Data Protection**: [Encryption, data handling, privacy considerations]
- **Input Validation**: [How user input and external data will be validated]
- **Security Testing**: [Security testing approach and tools]

### Scalability and Performance
- **Performance Requirements**: [Response time, throughput, resource usage targets]
- **Scalability Strategy**: [How system will handle increased load]
- **Caching Strategy**: [What will be cached and how]
- **Resource Optimization**: [Memory, CPU, network optimization approaches]

### Risk Mitigation
- **Technical Risks**: [Identified risks and mitigation strategies]
- **Performance Risks**: [Potential performance issues and solutions]
- **Integration Risks**: [Risks with external dependencies and fallback plans]
- **Rollback Strategy**: [How to safely rollback if issues arise]

### Alternative Approaches Considered
- **Alternative 1**: [Approach considered and why it was rejected]
- **Alternative 2**: [Approach considered and why it was rejected]
- **Trade-offs**: [Key trade-offs made in the chosen approach]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("implementation", "strategy", "phases", "security")
            )
        )

        return Pair(template, sections)
    }
}