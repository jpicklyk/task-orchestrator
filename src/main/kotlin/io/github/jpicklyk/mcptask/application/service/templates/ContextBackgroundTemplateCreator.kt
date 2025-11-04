package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for providing necessary context and background information.
 */
object ContextBackgroundTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Context & Background",
            description = "Template for providing necessary context and background information for projects and features, including business context, user needs, and strategic alignment.",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("context", "background", "business", "strategic", "documentation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Why This Matters",
                usageDescription = "Business rationale and value proposition for the work",
                contentSample = """### Business Objective
- **Primary Goal**: [What business problem this solves or opportunity it captures]
- **Expected Impact**: [How this affects users, revenue, efficiency, or competitive position]
- **Success Metrics**: [How we'll measure success]

### Business Driver
- Revenue growth
- Cost reduction
- Risk mitigation
- Competitive differentiation
- Customer satisfaction
- Regulatory requirement

### Timeline Context
- **Key Deadline**: [Important date or milestone if applicable]
- **Why Now**: [Reason for current timing]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("business", "value", "objectives", "context")
            ),
            TemplateSection(
                templateId = templateId,
                title = "User Context",
                usageDescription = "Who will use this and what problems it solves for them",
                contentSample = """### Target Users
- **Primary Users**: [Who will use this most]
  - Role and responsibilities
  - Current tools and workflows
  - Technical proficiency level

### User Pain Points
**Current Problems**:
1. [Pain point 1 and its impact]
2. [Pain point 2 and its impact]

**Current Workarounds**: [How users currently handle these issues]

### User Goals
- **What users need**: [Core functionality or capability needed]
- **Why it matters**: [Impact on user productivity, satisfaction, or success]
- **Success looks like**: [How users will know this solves their problem]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("users", "needs", "pain-points", "context")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Dependencies & Coordination",
                usageDescription = "Related work, dependencies, and coordination needs",
                contentSample = """### Related Work
- **[Related Project/Feature]**: [How it relates to this work]
  - Status and timeline
  - Coordination required

### Dependencies
**Upstream** (must be completed first):
- [Dependency]: Why it's needed and when expected

**Downstream** (depends on this work):
- [Dependent work]: What's blocked until this is done

### Technical Context
- **Existing Systems**: [Systems affected by this work]
- **Architecture Fit**: [How this fits into current architecture]
- **Technical Constraints**: [Limitations or requirements to consider]

### Team Coordination
- **Teams Involved**: [Other teams to coordinate with]
- **Shared Resources**: [Infrastructure, tools, or services used by multiple teams]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("dependencies", "coordination", "technical-context", "context")
            )
        )

        return Pair(template, sections)
    }
}