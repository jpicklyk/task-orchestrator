package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for defining completion criteria and handoff requirements.
 */
object DefinitionOfDoneTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Definition of Done",
            description = "Template for defining clear completion criteria, quality checklist, and handoff requirements for projects, features, and tasks.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("completion", "done", "checklist", "handoff", "quality")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Completion Criteria",
                usageDescription = "Specific, measurable criteria that must be met for the work to be considered complete",
                contentSample = """## Completion Criteria

### Functional Completeness
- [ ] **Core Functionality**: All required features are implemented and working
- [ ] **User Stories**: All user stories in scope are completed and accepted
- [ ] **Acceptance Criteria**: All defined acceptance criteria are met
- [ ] **Edge Cases**: Edge cases and error conditions are handled appropriately
- [ ] **Integration Points**: All required integrations are working correctly

### Code Quality Standards
- [ ] **Code Review**: Code has been reviewed and approved by at least one other developer
- [ ] **Coding Standards**: Code follows project coding standards and conventions
- [ ] **Code Coverage**: Unit test coverage meets project minimum ([X]% coverage)
- [ ] **Static Analysis**: Code passes static analysis tools without critical issues
- [ ] **Technical Debt**: No new technical debt introduced (or documented and approved)

### Testing Requirements
- [ ] **Unit Tests**: All unit tests are written and passing
- [ ] **Integration Tests**: Integration tests are written and passing
- [ ] **Manual Testing**: Manual testing completed for critical user workflows
- [ ] **Regression Testing**: Existing functionality still works (no regressions introduced)
- [ ] **Performance Testing**: Performance requirements are met and verified

### Documentation Standards
- [ ] **Code Documentation**: Complex logic and public APIs are documented
- [ ] **User Documentation**: End-user documentation is updated if needed
- [ ] **Technical Documentation**: Architecture and design decisions are documented
- [ ] **API Documentation**: API changes are documented with examples
- [ ] **Deployment Notes**: Any deployment-specific instructions are documented

### Security and Compliance
- [ ] **Security Review**: Security implications have been reviewed
- [ ] **Data Protection**: Personal or sensitive data is handled appropriately
- [ ] **Access Control**: Proper authentication and authorization are implemented
- [ ] **Compliance**: Relevant compliance requirements are met
- [ ] **Audit Trail**: Necessary logging and audit capabilities are in place

### Performance and Reliability
- [ ] **Performance Metrics**: Meets defined performance requirements
- [ ] **Error Handling**: Graceful error handling and recovery mechanisms
- [ ] **Monitoring**: Appropriate monitoring and alerting are configured
- [ ] **Scalability**: Can handle expected load and usage patterns
- [ ] **Resilience**: System can recover from common failure scenarios""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("functional", "quality", "testing", "documentation", "security")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Quality Checklist",
                usageDescription = "Comprehensive quality checklist covering all aspects of software delivery",
                contentSample = """## Quality Checklist

### Code Quality
- [ ] **Readability**: Code is clean, readable, and well-organized
- [ ] **Maintainability**: Code is structured for easy maintenance and extension
- [ ] **Reusability**: Common functionality is properly abstracted and reusable
- [ ] **Naming**: Variables, functions, and classes have clear, descriptive names
- [ ] **Comments**: Complex logic is explained with appropriate comments
- [ ] **Dependencies**: Dependencies are minimal, appropriate, and up-to-date
- [ ] **Configuration**: Configuration is externalized and environment-specific

### Testing Quality
- [ ] **Test Coverage**: Adequate test coverage for all new code
- [ ] **Test Quality**: Tests are reliable, fast, and maintainable
- [ ] **Test Data**: Tests use appropriate test data and mocking strategies
- [ ] **Test Environment**: Tests run consistently in different environments
- [ ] **Test Documentation**: Test purpose and expectations are clear

### User Experience Quality
- [ ] **Usability**: Interface is intuitive and user-friendly
- [ ] **Accessibility**: Meets accessibility standards (WCAG 2.1 AA)
- [ ] **Responsive Design**: Works correctly on different screen sizes
- [ ] **Browser Support**: Tested on all supported browsers and versions
- [ ] **Error Messages**: User-friendly error messages and guidance
- [ ] **Loading Performance**: Acceptable loading times and progress indicators

### Operational Quality
- [ ] **Deployment**: Can be deployed through standard deployment pipeline
- [ ] **Configuration Management**: Environment-specific settings are manageable
- [ ] **Monitoring**: Health checks and monitoring endpoints are implemented
- [ ] **Logging**: Appropriate logging for debugging and operations
- [ ] **Backup/Recovery**: Data backup and recovery procedures are in place
- [ ] **Rollback**: Can be safely rolled back if issues occur

### Security Quality
- [ ] **Input Validation**: All user input is properly validated and sanitized
- [ ] **Authentication**: Strong authentication mechanisms where required
- [ ] **Authorization**: Proper access controls are implemented
- [ ] **Data Encryption**: Sensitive data is encrypted in transit and at rest
- [ ] **Security Headers**: Appropriate security headers are configured
- [ ] **Vulnerability Scanning**: No critical security vulnerabilities detected

### Integration Quality
- [ ] **API Compatibility**: APIs are backward compatible or properly versioned
- [ ] **Data Consistency**: Data remains consistent across system boundaries
- [ ] **Error Propagation**: Errors are handled and communicated appropriately
- [ ] **Timeout Handling**: Appropriate timeouts and circuit breakers are in place
- [ ] **Idempotency**: Operations are idempotent where appropriate""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("checklist", "code-quality", "user-experience", "operations", "security")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Handoff Requirements",
                usageDescription = "Requirements for transitioning completed work to operations, users, or other teams",
                contentSample = """## Handoff Requirements

### Knowledge Transfer
- [ ] **Documentation**: All relevant documentation is complete and accessible
- [ ] **Knowledge Sharing**: Key knowledge has been shared with relevant team members
- [ ] **Training Materials**: Training materials are prepared if needed
- [ ] **Support Procedures**: Support and troubleshooting procedures are documented
- [ ] **Contact Information**: Points of contact for questions are identified

### Operational Handoff
- [ ] **Deployment Guide**: Step-by-step deployment instructions are provided
- [ ] **Configuration Guide**: Configuration settings and options are documented
- [ ] **Monitoring Setup**: Monitoring dashboards and alerts are configured
- [ ] **Maintenance Procedures**: Regular maintenance tasks are documented
- [ ] **Incident Response**: Incident response procedures are defined and tested

### User Handoff
- [ ] **User Training**: End users have been trained on new functionality
- [ ] **User Documentation**: User guides and help documentation are available
- [ ] **Change Communication**: Users have been notified of changes
- [ ] **Support Channel**: Clear support channels for user questions
- [ ] **Feedback Mechanism**: Way for users to provide feedback is established

### Technical Handoff
- [ ] **Code Repository**: Code is committed to appropriate repository with clear history
- [ ] **Environment Setup**: Development and testing environments are documented
- [ ] **Build Process**: Build and deployment processes are automated and documented
- [ ] **Dependencies**: All dependencies and their versions are clearly documented
- [ ] **Architecture Documentation**: System architecture and design decisions are documented

### Business Handoff
- [ ] **Success Metrics**: How success will be measured post-implementation
- [ ] **Business Impact**: Expected business impact is communicated to stakeholders
- [ ] **Risk Assessment**: Known risks and mitigation strategies are documented
- [ ] **Future Roadmap**: Next steps and future enhancements are identified
- [ ] **Stakeholder Sign-off**: Key stakeholders have approved the delivery

### Compliance and Legal
- [ ] **Regulatory Compliance**: Compliance requirements are met and documented
- [ ] **Data Privacy**: Data privacy requirements are satisfied
- [ ] **Audit Trail**: Audit requirements are met with appropriate logging
- [ ] **Legal Review**: Legal review completed if required
- [ ] **Third-party Agreements**: Third-party agreements and licenses are in order

### Continuous Improvement
- [ ] **Lessons Learned**: Key lessons learned are documented for future reference
- [ ] **Process Improvements**: Process improvement suggestions are captured
- [ ] **Metrics Collection**: Baseline metrics are established for future comparison
- [ ] **Feedback Loop**: Mechanism for ongoing feedback and improvement is established
- [ ] **Post-Implementation Review**: Schedule for post-implementation review is set

### Sign-off and Approval
- [ ] **Technical Lead Approval**: Technical lead has signed off on technical quality
- [ ] **Product Owner Approval**: Product owner has accepted the delivered functionality
- [ ] **Security Approval**: Security team has approved if security review was required
- [ ] **Operations Approval**: Operations team is ready to support the system
- [ ] **Final Documentation**: All required documentation is complete and filed appropriately""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("handoff", "knowledge-transfer", "operations", "compliance", "sign-off")
            )
        )

        return Pair(template, sections)
    }
}