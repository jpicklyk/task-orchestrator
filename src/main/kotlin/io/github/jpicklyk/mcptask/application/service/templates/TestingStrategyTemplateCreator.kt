package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for defining testing strategy and quality assurance approach.
 */
object TestingStrategyTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Testing Strategy",
            description = "Template for defining comprehensive testing approach, test cases, acceptance criteria, and quality gates for features and tasks.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("testing", "quality", "strategy", "validation", "qa")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Test Coverage",
                usageDescription = "Test cases and coverage areas for thorough validation",
                contentSample = """### Unit Tests
**Test Class**: `[ComponentName]Test`

Test format: `should [expected behavior] when [specific condition]`
- Given: [Initial state]
- When: [Action]
- Then: [Expected result]

**Coverage Areas**:
- Happy path scenarios
- Edge cases (empty input, boundary values, invalid data)
- Error conditions and exception handling

### Integration Tests
**Component Integration**:
- [Component A] ↔ [Component B]: [What's being tested]
- Data flow and transformations
- Failure handling

**Database Integration** (if applicable):
- CRUD operations
- Transaction handling
- Data integrity and constraints

### API Tests
**Endpoint**: `[METHOD] /api/[endpoint]`
- **Success**: Valid request → Expected response + status code
- **Errors**: Invalid input, auth failures, permission issues

### End-to-End Tests
- Key user workflows that must work completely
- Critical business processes
- Integration between multiple components""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("testing", "coverage", "unit-tests", "integration-tests", "guidance", "reference")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Acceptance Criteria",
                usageDescription = "Measurable criteria that define completion",
                contentSample = """### Functional Criteria
- [ ] Core functionality works as specified
- [ ] All acceptance criteria from requirements are met
- [ ] User workflows complete successfully
- [ ] Data validation and error handling work correctly
- [ ] Integration points function as expected

### Quality Criteria
- [ ] Unit test coverage meets project standard ([X]%)
- [ ] All tests pass consistently
- [ ] Code passes static analysis without critical issues
- [ ] Performance meets requirements (response time, throughput)
- [ ] Security best practices followed

### Deployment Criteria
- [ ] Builds successfully in CI/CD pipeline
- [ ] Can be deployed without errors
- [ ] Monitoring and logging in place
- [ ] Rollback procedure tested and documented""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("acceptance-criteria", "completion", "quality", "checklist")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Testing Checkpoints",
                usageDescription = "Key validation checkpoints during development and deployment",
                contentSample = """### During Development
**Before PR**:
- [ ] All new/modified code has tests
- [ ] Unit tests pass locally
- [ ] Code follows style guidelines
- [ ] No obvious bugs or issues

**Before Merge**:
- [ ] Code review approved
- [ ] All CI/CD checks passing
- [ ] Integration tests pass
- [ ] Documentation updated

### Before Deployment
**Pre-deployment Validation**:
- [ ] All tests pass in staging environment
- [ ] Performance tested under realistic load
- [ ] Security scan shows no critical issues
- [ ] Monitoring and alerts configured
- [ ] Rollback plan ready

### After Deployment
**Production Validation** (within 24-48 hours):
- [ ] Critical functionality working
- [ ] No error rate increase
- [ ] Performance metrics healthy
- [ ] Monitoring shows expected behavior

**If Issues Detected**:
- Critical issues → Immediate rollback
- Minor issues → Hot fix if needed
- Monitor closely for first 24-48 hours""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("checkpoints", "validation", "deployment", "checklist", "process", "workflow-instruction")
            )
        )

        return Pair(template, sections)
    }
}