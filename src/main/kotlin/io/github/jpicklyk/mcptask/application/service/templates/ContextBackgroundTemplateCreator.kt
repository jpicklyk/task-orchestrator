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
                title = "Business Context",
                usageDescription = "Business rationale, strategic alignment, and organizational context for the work",
                contentSample = """## Business Context

### Strategic Alignment
- **Business Objective**: [How this work aligns with broader business goals]
- **Strategic Initiative**: [Which company strategy or initiative this supports]
- **Key Performance Indicators**: [Metrics this work is expected to impact]
- **Success Metrics**: [How success will be measured for this specific work]

### Market Context
- **Market Opportunity**: [Market opportunity or problem this addresses]
- **Competitive Landscape**: [How this relates to competitor offerings]
- **Customer Demand**: [Evidence of customer need or demand]
- **Market Timing**: [Why now is the right time for this work]

### Business Drivers
- **Primary Driver**: [Main business reason for this work]
  - Revenue growth
  - Cost reduction
  - Risk mitigation
  - Competitive differentiation
  - Regulatory compliance
  - Customer satisfaction

- **Secondary Drivers**: [Additional business benefits expected]
- **Business Impact**: [Expected impact on business operations or outcomes]

### Organizational Context
- **Sponsoring Team/Department**: [Who is driving this initiative]
- **Budget/Resources**: [Budget allocation and resource commitment]
- **Decision Makers**: [Key stakeholders and decision makers]
- **Timeline Drivers**: [Business events or deadlines driving the timeline]

### Risk and Mitigation
- **Business Risks**: [What happens if this work is not completed]
- **Opportunity Cost**: [What other opportunities might be missed]
- **Risk Mitigation**: [How business risks are being addressed]

### Return on Investment
- **Investment Required**: [Development cost, resources, time]
- **Expected Returns**: [Revenue, cost savings, efficiency gains]
- **Payback Period**: [When the investment is expected to pay off]
- **Long-term Value**: [Ongoing value beyond initial implementation]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("business", "strategy", "alignment", "roi")
            ),
            TemplateSection(
                templateId = templateId,
                title = "User Needs & Goals",
                usageDescription = "Understanding of user requirements, pain points, and desired outcomes",
                contentSample = """## User Needs & Goals

### Target Users
- **Primary Users**: [Main user group this work serves]
  - **Role/Title**: [Who they are in the organization]
  - **Responsibilities**: [What they do in their job]
  - **Technical Proficiency**: [Their level of technical expertise]
  - **Current Tools**: [What tools they currently use]

- **Secondary Users**: [Additional user groups that will be affected]
  - **Role/Title**: [Who they are in the organization]
  - **Usage Pattern**: [How they will interact with this work]

### User Pain Points
1. **[Pain Point]**: [Current problem or frustration]
   - **Impact**: [How this affects the user's work or experience]
   - **Frequency**: [How often this problem occurs]
   - **Workarounds**: [Current ways users try to solve this]

2. **[Pain Point]**: [Current problem or frustration]
   - **Impact**: [How this affects the user's work or experience]
   - **Frequency**: [How often this problem occurs]
   - **Workarounds**: [Current ways users try to solve this]

### User Goals
- **Immediate Goals**: [What users want to accomplish right now]
- **Short-term Goals**: [What users want to achieve in the near future]
- **Long-term Goals**: [What users hope to accomplish over time]
- **Success Criteria**: [How users will know they've been successful]

### User Journey
1. **Current State**: [How users currently accomplish their goals]
   - **Steps**: [Current process they follow]
   - **Pain Points**: [Where they encounter problems]
   - **Time Required**: [How long the current process takes]

2. **Desired Future State**: [How users want to accomplish their goals]
   - **Improved Steps**: [Streamlined or enhanced process]
   - **Eliminated Pain Points**: [Problems that will be solved]
   - **Time Savings**: [How much time will be saved]

### User Research
- **Research Methods**: [Surveys, interviews, observation, analytics]
- **Key Findings**: [Important insights from user research]
- **User Quotes**: [Direct feedback from users about their needs]
- **Usage Patterns**: [How users currently interact with similar systems]

### Accessibility Needs
- **Accessibility Requirements**: [Specific accessibility needs of users]
- **Assistive Technologies**: [Tools that users rely on]
- **Inclusive Design**: [How to ensure all users can benefit]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("users", "needs", "goals", "journey", "research")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Related Work & Dependencies",
                usageDescription = "Context about related projects, dependencies, and coordination requirements",
                contentSample = """## Related Work & Dependencies

### Related Projects
- **[Project Name]**: [Brief description and relationship to this work]
  - **Status**: [Current status of the related project]
  - **Impact**: [How this project affects or is affected by the related work]
  - **Coordination**: [Any coordination required between projects]

- **[Project Name]**: [Brief description and relationship to this work]
  - **Status**: [Current status of the related project]
  - **Impact**: [How this project affects or is affected by the related work]
  - **Coordination**: [Any coordination required between projects]

### Dependencies
- **Upstream Dependencies**: [Work that must be completed before this can begin]
  - **[Dependency Name]**: [What it is and why it's needed]
  - **Timeline**: [When it's expected to be available]
  - **Risk**: [What happens if this dependency is delayed]

- **Downstream Dependencies**: [Work that depends on this being completed]
  - **[Dependent Work]**: [What depends on this work]
  - **Timeline**: [When they need this to be completed]
  - **Impact**: [What happens if this work is delayed]

### Team Coordination
- **Development Teams**: [Other teams involved in related work]
- **Shared Resources**: [Common infrastructure, tools, or services]
- **Communication Plan**: [How teams will coordinate and communicate]
- **Decision Making**: [How conflicts or decisions will be resolved]

### Technical Context
- **Existing Systems**: [Current systems that will be affected]
- **Legacy Considerations**: [Old systems that must be maintained or migrated]
- **Future Architecture**: [How this fits into the long-term technical vision]
- **Technical Debt**: [Existing technical debt that affects this work]

### Historical Context
- **Previous Attempts**: [Any previous attempts to solve similar problems]
- **Lessons Learned**: [Key insights from past experiences]
- **Evolved Requirements**: [How requirements have changed over time]
- **Success Stories**: [Related successful implementations to learn from]

### External Factors
- **Vendor Dependencies**: [External vendors or services that affect this work]
- **Regulatory Changes**: [Regulatory or compliance factors]
- **Industry Trends**: [Industry developments that influence this work]
- **Partner Requirements**: [Requirements from business partners or clients]

### Timeline Context
- **Critical Milestones**: [Important dates or events that affect timeline]
- **Seasonal Factors**: [Times of year that affect implementation or usage]
- **Business Cycles**: [Business events that affect priority or timeline]
- **Resource Availability**: [Known periods of limited resource availability]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("dependencies", "coordination", "related-work", "timeline")
            )
        )

        return Pair(template, sections)
    }
}