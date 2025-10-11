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
                title = "Test Case Definitions",
                usageDescription = "Detailed test cases covering functional, edge case, and error condition testing",
                contentSample = """### Test Case Definitions

### Unit Tests
#### Test Class: [ComponentName]Test
1. **Test**: `should [expected behavior] when [specific condition]`
   - **Given**: [Initial state or preconditions]
   - **When**: [Action or trigger]
   - **Then**: [Expected outcome]
   - **Test Data**: [Required test data or mocks]

2. **Test**: `should [expected behavior] when [specific condition]`
   - **Given**: [Initial state or preconditions]
   - **When**: [Action or trigger]
   - **Then**: [Expected outcome]
   - **Test Data**: [Required test data or mocks]

#### Edge Cases
- **Empty Input**: [How system handles empty or null inputs]
- **Boundary Values**: [Testing at limits of valid input ranges]
- **Invalid Data**: [How system handles malformed or invalid data]
- **Large Data Sets**: [Performance with large amounts of data]

### Integration Tests
1. **Integration**: [Component A] ↔ [Component B]
   - **Test Scenario**: [What integration is being tested]
   - **Data Flow**: [How data moves between components]
   - **Expected Behavior**: [What should happen in the integration]
   - **Failure Cases**: [What happens when integration fails]

2. **Database Integration**
   - **CRUD Operations**: [Create, Read, Update, Delete testing]
   - **Transaction Handling**: [Testing transaction rollback and commit]
   - **Data Integrity**: [Foreign key constraints, data validation]
   - **Performance**: [Query performance with realistic data volumes]

### API Tests
#### Endpoint: [HTTP Method] /api/[endpoint]
- **Success Cases**:
  - **Request**: `[Example valid request]`
  - **Response**: `[Expected response format and data]`
  - **Status Code**: `[Expected HTTP status]`

- **Error Cases**:
  - **Invalid Input**: `[Example invalid request and expected error]`
  - **Authentication**: `[Testing with invalid/missing credentials]`
  - **Authorization**: `[Testing access with insufficient permissions]`

### User Interface Tests
- **User Workflows**: [Key user journeys that must work end-to-end]
- **Form Validation**: [Testing form fields, validation messages]
- **Responsive Design**: [Testing on different screen sizes]
- **Browser Compatibility**: [Testing across supported browsers]
- **Accessibility**: [Screen reader, keyboard navigation, color contrast]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("unit-tests", "integration-tests", "api-tests", "ui-tests")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Acceptance Criteria",
                usageDescription = "Clear, measurable criteria that must be met for the implementation to be considered complete",
                contentSample = """### Acceptance Criteria

### Functional Acceptance Criteria
1. **Core Functionality**
   - [ ] [Specific functional requirement] works as specified
   - [ ] [Another functional requirement] produces expected results
   - [ ] All required API endpoints return correct data formats
   - [ ] User interface displays information correctly
   - [ ] Data validation prevents invalid input

2. **User Experience**
   - [ ] User can complete primary workflow without confusion
   - [ ] Error messages are clear and actionable
   - [ ] Loading states provide appropriate feedback
   - [ ] Navigation is intuitive and consistent
   - [ ] Responsive design works on mobile and desktop

3. **Integration Requirements**
   - [ ] Integration with [External System] works correctly
   - [ ] Data synchronization maintains consistency
   - [ ] Authentication and authorization work as expected
   - [ ] Error handling provides graceful degradation

### Performance Acceptance Criteria
- [ ] **Response Time**: [Specific operation] completes in under [X] seconds
- [ ] **Throughput**: System handles [X] concurrent users without degradation
- [ ] **Load Time**: Page loads in under [X] seconds with typical data volume
- [ ] **Database Performance**: Queries execute in under [X] milliseconds
- [ ] **Memory Usage**: Application uses less than [X] MB memory under normal load

### Security Acceptance Criteria
- [ ] **Authentication**: Only authenticated users can access protected resources
- [ ] **Authorization**: Users can only access data they have permission for
- [ ] **Input Validation**: All user input is properly validated and sanitized
- [ ] **Data Protection**: Sensitive data is encrypted in transit and at rest
- [ ] **Audit Logging**: Security-relevant actions are logged appropriately

### Quality Acceptance Criteria
- [ ] **Code Coverage**: Unit test coverage is at least [X]%
- [ ] **Code Quality**: Code passes static analysis without critical issues
- [ ] **Documentation**: All public APIs and complex logic are documented
- [ ] **Error Handling**: All error conditions have appropriate handling
- [ ] **Logging**: Sufficient logging for debugging and monitoring

### Deployment Acceptance Criteria
- [ ] **Build Process**: Application builds successfully in CI/CD pipeline
- [ ] **Database Migration**: Database schema changes apply without errors
- [ ] **Configuration**: All environment-specific configuration works correctly
- [ ] **Rollback**: Changes can be safely rolled back if issues occur
- [ ] **Monitoring**: Application health can be monitored in production

### Accessibility Acceptance Criteria
- [ ] **Screen Reader**: All content is accessible via screen reader
- [ ] **Keyboard Navigation**: All functionality accessible via keyboard
- [ ] **Color Contrast**: Text meets WCAG contrast requirements
- [ ] **Focus Management**: Focus indicators are visible and logical
- [ ] **Alternative Text**: Images have appropriate alt text""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("acceptance-criteria", "functional", "performance", "security", "quality")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Quality Gates",
                usageDescription = "Checkpoints and criteria that must be passed before proceeding to next phase or deployment",
                contentSample = """### Quality Gates

### Development Quality Gates

#### Gate 1: Code Completion
**Criteria to Pass**:
- [ ] All planned functionality is implemented
- [ ] Code follows project style guidelines and conventions
- [ ] All compiler warnings are resolved
- [ ] No obvious code smells or technical debt introduced
- [ ] Complex logic is properly documented

**Actions if Failed**:
- Address code quality issues before proceeding
- Refactor code to meet standards
- Add missing documentation

#### Gate 2: Unit Testing
**Criteria to Pass**:
- [ ] Unit test coverage meets minimum threshold ([X]%)
- [ ] All unit tests pass consistently
- [ ] Tests cover happy path, edge cases, and error conditions
- [ ] Mock dependencies are used appropriately
- [ ] Test code is maintainable and clear

**Actions if Failed**:
- Add missing test coverage
- Fix failing tests
- Improve test quality and clarity

### Integration Quality Gates

#### Gate 3: Integration Testing
**Criteria to Pass**:
- [ ] All integration tests pass
- [ ] API contracts are validated
- [ ] Database operations work correctly
- [ ] External service integrations function as expected
- [ ] Error scenarios are handled properly

**Actions if Failed**:
- Fix integration issues
- Update API contracts if needed
- Resolve data consistency problems

#### Gate 4: System Testing
**Criteria to Pass**:
- [ ] End-to-end workflows complete successfully
- [ ] Performance requirements are met
- [ ] Security scanning shows no critical vulnerabilities
- [ ] Browser/platform compatibility verified
- [ ] Accessibility requirements satisfied

**Actions if Failed**:
- Address performance bottlenecks
- Fix security vulnerabilities
- Resolve compatibility issues

### Pre-Production Quality Gates

#### Gate 5: User Acceptance Testing
**Criteria to Pass**:
- [ ] All acceptance criteria verified
- [ ] Key stakeholders approve functionality
- [ ] User workflows tested with real or realistic data
- [ ] Documentation is complete and accurate
- [ ] Training materials prepared if needed

**Actions if Failed**:
- Address user feedback
- Update documentation
- Refine functionality based on stakeholder input

#### Gate 6: Production Readiness
**Criteria to Pass**:
- [ ] Deployment process tested in staging environment
- [ ] Monitoring and alerting configured
- [ ] Rollback procedure tested and documented
- [ ] Performance tested under realistic load
- [ ] Security review completed

**Actions if Failed**:
- Fix deployment issues
- Set up proper monitoring
- Test rollback procedures
- Address performance or security concerns

### Post-Deployment Quality Gates

#### Gate 7: Production Validation
**Criteria to Pass** (within 24-48 hours of deployment):
- [ ] All critical functionality working in production
- [ ] No increase in error rates or performance degradation
- [ ] Monitoring shows healthy system metrics
- [ ] User feedback is positive or neutral
- [ ] No security incidents related to deployment

**Actions if Failed**:
- Immediate rollback if critical issues detected
- Hot fixes for minor issues
- Incident response procedures if needed

### Continuous Quality Monitoring
- **Code Quality**: Automated code analysis on every commit
- **Test Results**: All tests must pass before merge to main
- **Performance**: Continuous performance monitoring in production
- **Security**: Regular security scans and vulnerability assessments
- **User Experience**: Ongoing monitoring of user satisfaction and usage patterns""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("quality-gates", "checkpoints", "deployment", "monitoring")
            )
        )

        return Pair(template, sections)
    }
}