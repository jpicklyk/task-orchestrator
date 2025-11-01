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
                title = "Implementation Complete",
                usageDescription = "Criteria confirming the implementation is functionally complete and meets quality standards",
                contentSample = """### Functionality
- [ ] **Core Features**: All required functionality is implemented and working
- [ ] **Acceptance Criteria**: All defined acceptance criteria are met
- [ ] **Edge Cases**: Edge cases and error conditions are handled appropriately
- [ ] **Integration**: Required integrations are working correctly

### Code Quality
- [ ] **Code Review**: Code has been reviewed and approved
- [ ] **Coding Standards**: Code follows project conventions and standards
- [ ] **Documentation**: Complex logic and public APIs are documented
- [ ] **No Critical Issues**: Static analysis passes without critical issues

### Testing
- [ ] **Unit Tests**: Unit tests are written and passing
- [ ] **Integration Tests**: Integration tests are written and passing
- [ ] **Test Coverage**: Test coverage meets project requirements
- [ ] **No Regressions**: Existing functionality still works correctly

### Documentation
- [ ] **Code Comments**: Complex logic is explained with appropriate comments
- [ ] **API Documentation**: API changes are documented with examples
- [ ] **User Documentation**: User-facing documentation is updated if needed
- [ ] **Technical Notes**: Architecture and design decisions are documented""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("implementation", "quality", "testing", "documentation", "checklist", "acceptance-criteria")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Production Ready",
                usageDescription = "Criteria confirming the implementation is ready for production deployment and operation",
                contentSample = """### Deployment
- [ ] **Build Success**: Code builds successfully in CI/CD pipeline
- [ ] **Deployment Verified**: Successfully deployed to staging/test environment
- [ ] **Configuration**: Environment-specific configuration is correct
- [ ] **Rollback Plan**: Rollback procedure is documented and tested

### Observability
- [ ] **Logging**: Appropriate logging is in place for debugging and monitoring
- [ ] **Monitoring**: Health checks and key metrics are being monitored
- [ ] **Error Handling**: Errors are handled gracefully with proper logging
- [ ] **Alerts**: Critical alerts are configured if needed

### Performance & Security
- [ ] **Performance**: Meets defined performance requirements
- [ ] **Security**: Security implications have been reviewed
- [ ] **Input Validation**: User input is properly validated and sanitized
- [ ] **Data Protection**: Sensitive data is handled appropriately

### Sign-off
- [ ] **Testing Complete**: All testing activities are complete and documented
- [ ] **Documentation Complete**: All required documentation is in place
- [ ] **Stakeholder Acceptance**: Key stakeholders have reviewed and approved
- [ ] **Ready for Release**: Team confirms ready for production release""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("deployment", "monitoring", "performance", "security", "sign-off", "checklist", "acceptance-criteria")
            )
        )

        return Pair(template, sections)
    }
}