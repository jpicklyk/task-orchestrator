package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for capturing detailed functional and non-functional requirements.
 */
object RequirementsSpecificationTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Requirements Specification",
            description = "Template for capturing detailed functional and non-functional requirements, acceptance criteria, and constraints for projects and features.",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("requirements", "specification", "acceptance-criteria", "constraints", "documentation")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Must-Have Requirements",
                usageDescription = "Critical functional requirements that must be implemented for successful delivery",
                contentSample = """### Must-Have Requirements

### Core Functionality
1. **[Requirement ID]**: [Requirement Name]
   - **Description**: [Detailed description of what must be implemented]
   - **User Story**: As a [user type], I want [functionality] so that [benefit]
   - **Acceptance Criteria**:
     - Given [context], when [action], then [expected result]
     - Given [context], when [action], then [expected result]
   - **Priority**: Critical

2. **[Requirement ID]**: [Requirement Name]
   - **Description**: [Detailed description of what must be implemented]
   - **User Story**: As a [user type], I want [functionality] so that [benefit]
   - **Acceptance Criteria**:
     - Given [context], when [action], then [expected result]
     - Given [context], when [action], then [expected result]
   - **Priority**: Critical

### Data Requirements
- **Data Input**: [What data must be accepted, format, validation rules]
- **Data Output**: [What data must be produced, format, structure]
- **Data Storage**: [What data must be persisted, retention requirements]
- **Data Migration**: [Any existing data that must be migrated or transformed]

### Integration Requirements
- **API Requirements**: [Required API endpoints, request/response formats]
- **External System Integration**: [Systems that must be integrated with]
- **Authentication/Authorization**: [Security requirements for access control]
- **Data Synchronization**: [How data stays consistent across systems]

### User Interface Requirements
- **User Experience**: [Key UX requirements and user flows]
- **Accessibility**: [WCAG compliance level, specific accessibility features]
- **Browser/Platform Support**: [Supported browsers, mobile requirements]
- **Responsive Design**: [Requirements for different screen sizes]

### Business Logic Requirements
- **Validation Rules**: [Business rules that must be enforced]
- **Calculation Logic**: [Any calculations or algorithms required]
- **Workflow Requirements**: [Business process flows that must be supported]
- **Audit Requirements**: [What actions must be logged or tracked]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("functional", "core", "critical", "acceptance-criteria", "requirements", "context")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Nice-to-Have Features",
                usageDescription = "Optional features and enhancements that would improve the solution but aren't critical",
                contentSample = """### Nice-to-Have Features

### Enhanced Functionality
1. **[Feature Name]**: [Description of optional feature]
   - **Value**: [How this would improve the user experience or business value]
   - **Effort Estimate**: [Rough estimate of implementation effort]
   - **Dependencies**: [What would need to be in place first]

2. **[Feature Name]**: [Description of optional feature]
   - **Value**: [How this would improve the user experience or business value]
   - **Effort Estimate**: [Rough estimate of implementation effort]
   - **Dependencies**: [What would need to be in place first]

### User Experience Enhancements
- **Advanced Search**: [Enhanced search capabilities beyond basic requirements]
- **Bulk Operations**: [Ability to perform operations on multiple items]
- **Customization Options**: [User preferences, themes, personalization]
- **Advanced Filtering**: [Additional filtering and sorting options]

### Performance Enhancements
- **Caching Improvements**: [Advanced caching for better performance]
- **Real-time Updates**: [Live updates without page refresh]
- **Offline Capabilities**: [Ability to work without internet connection]
- **Progressive Loading**: [Improved loading experience for large datasets]

### Administrative Features
- **Advanced Analytics**: [Detailed reporting and analytics capabilities]
- **Export/Import**: [Additional data export/import formats]
- **Advanced User Management**: [Role management, user provisioning]
- **System Monitoring**: [Enhanced monitoring and alerting capabilities]

### Integration Enhancements
- **Additional API Endpoints**: [Extra APIs for future integrations]
- **Webhook Support**: [Event-driven integrations with external systems]
- **Third-party Integrations**: [Integrations with popular tools/services]
- **Advanced Security**: [Additional security features like 2FA, SSO]

### Future Considerations
- **Mobile Application**: [Native mobile app considerations]
- **Internationalization**: [Multi-language support]
- **Multi-tenancy**: [Support for multiple organizations/tenants]
- **Advanced Workflow**: [Complex business process automation]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("optional", "enhancements", "future", "nice-to-have", "requirements", "context")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Constraints & Non-Functional Requirements",
                usageDescription = "Technical constraints and non-functional requirements that must be considered",
                contentSample = """### Technical Constraints
- **Technology Stack**: [Required technologies, frameworks, or platforms]
- **Legacy Compatibility**: [Existing systems that must be supported]
- **Infrastructure**: [Hardware, network, or cloud limitations]
- **Third-party Dependencies**: [External services or APIs that must be used]

### Performance Requirements
- **Response Time**: [Maximum acceptable response times]
- **Throughput**: [Required transactions per second or concurrent users]
- **Scalability**: [Expected growth in users, data, or transactions]

### Security Requirements
- **Authentication**: [Required authentication methods]
- **Authorization**: [Access control and permission model]
- **Data Protection**: [Encryption and data handling requirements]
- **Audit Logging**: [What must be logged for security]

### User Experience Requirements
- **Browser/Platform Support**: [Supported browsers and devices]
- **Accessibility**: [WCAG level or specific accessibility needs]
- **Offline Capability**: [Any offline functionality requirements]

### Data Requirements
- **Data Volume**: [Expected data sizes and growth rates]
- **Data Retention**: [How long data must be kept]
- **Backup/Recovery**: [Data backup and recovery requirements]

### Integration Constraints
- **API Limitations**: [Rate limits, data format restrictions]
- **Network Requirements**: [Bandwidth, latency requirements]
- **Error Handling**: [How integration failures must be handled]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("constraints", "non-functional", "performance", "security", "requirements", "context")
            )
        )

        return Pair(template, sections)
    }
}