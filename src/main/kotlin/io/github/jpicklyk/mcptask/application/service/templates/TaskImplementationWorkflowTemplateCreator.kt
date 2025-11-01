package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for task implementation workflow, optimized for AI agent consumption with clear implementation steps.
 */
object TaskImplementationWorkflowTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Task Implementation Workflow",
            description = "A standardized workflow template for implementing individual tasks, optimized for AI agents with MCP tool integration.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("implementation", "workflow", "ai-optimized", "mcp-tools", "task-management")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Implementation Analysis",
                usageDescription = "Systematic analysis of the task before beginning implementation, including context gathering and planning",
                contentSample = """### Implementation Analysis

### Task Context Review
Before starting implementation, gather complete context:

1. **Task Information Retrieval**
   - Use `get_task` MCP tool to retrieve complete task details including sections
   - Review task title, description, complexity, and priority
   - Understand success criteria and acceptance requirements
   - Identify any existing task sections with additional context

2. **Related Context Analysis**
   - Check for parent tasks or subtasks using `search_tasks`
   - Review any dependencies or blocking relationships
   - Examine related features or components
   - Look for similar completed tasks for patterns

3. **Technical Scope Assessment**
   ```markdown
   ### Scope Analysis
   - **Files to Modify**: [List of files that will likely need changes]
   - **New Files**: [Any new files that need to be created]
   - **Dependencies**: [External libraries or components involved]
   - **Testing Strategy**: [Types of tests needed]
   - **Complexity Factors**: [What makes this task complex]
   ```

### Implementation Planning
Based on analysis, create implementation plan:

1. **Break Down into Steps**
   - Identify logical implementation phases
   - Order steps by dependencies and risk
   - Estimate effort for each step
   - Plan testing approach for each phase

2. **Risk Assessment**
   - Identify potential technical challenges
   - Plan mitigation strategies
   - Consider rollback approaches
   - Note areas requiring extra attention

3. **Resource Identification**
   - Code examples or patterns to follow
   - Documentation or references needed
   - Team members to consult if needed
   - Tools or utilities required

### Context Documentation
Update task with analysis findings using MCP tools:
- Use `add_section` or `update_section` to document analysis
- Include implementation plan and key decisions
- Note any assumptions or constraints discovered""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("analysis", "planning", "mcp-tools", "guidance", "process", "workflow-instruction")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Step-by-Step Implementation",
                usageDescription = "Detailed implementation process with checkpoints and validation steps",
                contentSample = """### Step-by-Step Implementation

### Implementation Execution Process
Follow systematic approach for reliable implementation:

1. **Environment Setup**
   - Ensure development environment is properly configured
   - Verify all required dependencies are available
   - Create or switch to appropriate git branch
   - Confirm baseline functionality works before changes

2. **Incremental Implementation**
   Execute planned steps incrementally:
   
   **For each implementation step:**
   
   a) **Code Changes**
   - Make focused changes addressing single aspect
   - Follow existing code patterns and conventions
   - Include appropriate error handling
   - Add logging or debugging support as needed
   
   b) **Immediate Verification**
   ```bash
   # Compile check
   ./gradlew build
   
   # Run relevant tests
   ./gradlew test --tests "*[RelatedTestClass]"
   ```
   
   c) **Incremental Commit**
   ```bash
   git add [changed-files]
   git commit -m "[type]: [step description]"
   ```

3. **Progress Tracking**
   - Update task status using `update_task` as milestones are reached
   - Document significant decisions or discoveries
   - Note any deviations from original plan
   - Track time spent vs. estimates

### Implementation Patterns
Follow established patterns:

1. **Code Structure**
   - Use existing architectural patterns
   - Follow naming conventions
   - Maintain consistent formatting
   - Apply SOLID principles appropriately

2. **Error Handling**
   - Use consistent exception handling approach
   - Provide meaningful error messages
   - Log errors appropriately
   - Consider recovery strategies

3. **Testing Integration**
   - Write tests as you implement (TDD approach)
   - Ensure new tests follow existing patterns
   - Maintain or improve code coverage
   - Include both positive and negative test cases

### Quality Checkpoints
At each major step:
- [ ] Code compiles successfully
- [ ] Existing tests still pass
- [ ] New functionality works as expected
- [ ] Code follows project standards
- [ ] No obvious performance issues
- [ ] Error cases are handled properly""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("implementation", "execution", "quality", "workflow-instruction", "process", "commands", "checklist")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Testing & Validation",
                usageDescription = "Comprehensive testing approach to ensure implementation meets requirements and maintains quality",
                contentSample = """### Testing & Validation

### Testing Strategy Implementation
Execute comprehensive testing to validate implementation:

1. **Unit Testing**
   ```bash
   # Run specific test class
   ./gradlew test --tests "*[ImplementedClass]Test"
   
   # Run all tests in package
   ./gradlew test --tests "io.github.jpicklyk.mcptask.domain.*"
   
   # Run with coverage report
   ./gradlew test jacocoTestReport
   ```

2. **Integration Testing**
   - Test interactions between components
   - Verify database operations if applicable
   - Check API endpoints and responses
   - Validate configuration and setup

3. **Functional Testing**
   - Test end-to-end user scenarios
   - Verify business logic correctness
   - Check edge cases and boundary conditions
   - Validate error handling paths

### Test Creation Guidelines
When adding new tests:

1. **Test Structure**
   ```kotlin
   @Test
   fun `should [expected behavior] when [specific condition]`() {
       // Given
       val [setup] = [test data setup]
       
       // When
       val result = [method under test]
       
       // Then
       assertThat(result).isEqualTo([expected])
   }
   ```

2. **Test Coverage Areas**
   - Happy path scenarios
   - Edge cases and boundary conditions
   - Error conditions and exception handling
   - Integration points with other components

### Validation Checklist
Before marking implementation complete:

- [ ] **Functionality**
  - [ ] Core functionality works as specified
  - [ ] All acceptance criteria are met
  - [ ] Edge cases are handled appropriately
  - [ ] Error scenarios are managed correctly

- [ ] **Quality**
  - [ ] Code follows project conventions
  - [ ] No code smells or obvious issues
  - [ ] Performance is acceptable
  - [ ] Security considerations addressed

- [ ] **Testing**
  - [ ] Unit tests pass and provide good coverage
  - [ ] Integration tests verify component interactions
  - [ ] Manual testing confirms expected behavior
  - [ ] Regression testing shows no broken functionality

- [ ] **Documentation**
  - [ ] Code is properly documented
  - [ ] API changes documented if applicable
  - [ ] README updated if needed
  - [ ] Task documentation reflects actual implementation

### Final Validation Steps
1. **Clean Build Verification**
   ```bash
   ./gradlew clean build
   ```

2. **Full Test Suite**
   ```bash
   ./gradlew test
   ```

3. **Task Status Update**
   - Use `update_task` to mark task as completed
   - Update complexity assessment if different than expected
   - Document any lessons learned or issues encountered

4. **Implementation Documentation**
   - Use `add_section` to document final implementation notes
   - Include any important technical decisions
   - Note areas for future improvement or optimization""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("testing", "validation", "quality-assurance", "mcp-tools", "workflow-instruction", "commands", "checklist")
            )
        )

        return Pair(template, sections)
    }
}