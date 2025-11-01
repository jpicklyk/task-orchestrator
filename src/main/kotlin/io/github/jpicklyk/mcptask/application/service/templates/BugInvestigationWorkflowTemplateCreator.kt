package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for bug investigation workflow, optimized for AI agent consumption with systematic debugging steps.
 */
object BugInvestigationWorkflowTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Bug Investigation Workflow",
            description = "A systematic workflow template for investigating and fixing bugs, optimized for AI agents with MCP tool integration and structured debugging approach.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("bug", "investigation", "workflow", "ai-optimized", "debugging", "mcp-tools", "task-type-bug")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Investigation Process",
                usageDescription = "Systematic approach to investigating bug reports and gathering diagnostic information",
                contentSample = """### Initial Information Gathering
Start investigation with comprehensive information collection:

1. **Bug Context Retrieval**
   - Use `get_task` MCP tool to review complete bug report
   - Examine all sections for reproduction steps, expected vs actual behavior
   - Identify severity, impact, and any user-reported workarounds
   - Review any attached logs, screenshots, or error messages

2. **Related Issue Search**
   - Use `search_tasks` to find similar or related bug reports
   - Look for patterns in recent issues that might indicate systemic problems
   - Check for recently completed tasks that might have introduced the issue
   - Identify any ongoing work that could be related

3. **Environment Analysis**
   ```markdown
   ### Environment Factors
   - **Platform/OS**: [Affected platforms]
   - **Version**: [Software version where bug occurs]
   - **Configuration**: [Relevant configuration settings]
   - **Data State**: [Database or file state that might affect issue]
   - **Load Conditions**: [System load, concurrent users, etc.]
   ```

### Reproduction Strategy
Establish reliable reproduction of the issue:

1. **Controlled Reproduction**
   - Follow exact reproduction steps from bug report
   - Document any deviations from expected behavior
   - Try variations to understand scope of the issue
   - Test in multiple environments if possible

2. **Isolation Testing**
   - Remove variables one by one to isolate cause
   - Test with minimal configuration
   - Check with different data sets
   - Verify in clean environment

3. **Documentation of Findings**
   ```markdown
   ### Reproduction Results
   - **Consistently Reproducible**: [Yes/No]
   - **Reproduction Rate**: [X out of Y attempts]
   - **Required Conditions**: [Specific conditions needed]
   - **Variations Tested**: [Different scenarios attempted]
   ```

### Diagnostic Data Collection
Gather technical evidence:

1. **Log Analysis**
   - Review application logs during reproduction
   - Check system logs for related errors
   - Examine database logs if data-related
   - Capture performance metrics if relevant

2. **Code Path Tracing**
   - Identify code paths involved in the reported functionality
   - Use debugger or logging to trace execution
   - Monitor variable states at key points
   - Check for unexpected code branches taken""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("investigation", "reproduction", "diagnosis", "mcp-tools", "process", "workflow-instruction", "guidance")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Root Cause Analysis",
                usageDescription = "Structured approach to identifying the underlying cause of the bug through systematic analysis",
                contentSample = """### Systematic Analysis Process
Apply structured approach to identify root cause:

1. **Code Review and Analysis**
   - Examine the specific code areas identified during investigation
   - Look for recent changes that might have introduced the issue
   - Check for common bug patterns (null pointer, off-by-one, concurrency issues)
   - Review error handling and edge case management

2. **Data Flow Analysis**
   ```markdown
   ### Data Flow Investigation
   - **Input Sources**: [Where does problematic data originate]
   - **Transformation Points**: [Where data is modified or processed]
   - **Validation Steps**: [Where data should be validated]
   - **Output Destinations**: [Where incorrect data ends up]
   ```

3. **Dependency Analysis**
   - Check external dependencies and their versions
   - Verify third-party library behavior
   - Examine configuration dependencies
   - Review database schema and constraints

### Hypothesis Formation
Develop and test hypotheses about the root cause:

1. **Primary Hypothesis**
   ```markdown
   ### Root Cause Hypothesis
   **Theory**: [Most likely cause based on evidence]
   **Supporting Evidence**: 
   - [Evidence point 1]
   - [Evidence point 2]
   - [Evidence point 3]
   
   **Test Plan**: [How to verify this hypothesis]
   ```

2. **Alternative Hypotheses**
   - List other possible causes in order of likelihood
   - Note why each is less likely than primary hypothesis
   - Plan testing approach for each alternative

### Verification Testing
Test hypotheses systematically:

1. **Hypothesis Testing**
   - Design specific tests to prove/disprove each hypothesis
   - Create minimal test cases that isolate the suspected cause
   - Use debugging tools to verify assumptions
   - Document results of each test

2. **Impact Assessment**
   ```markdown
   ### Impact Analysis
   - **Affected Functionality**: [What features are impacted]
   - **User Impact**: [How many users affected, severity of impact]
   - **Data Impact**: [Risk of data corruption or loss]
   - **Performance Impact**: [System performance effects]
   ```

### Root Cause Documentation
Document findings clearly:

1. **Root Cause Statement**
   - Clear, concise description of the actual cause
   - Explanation of how the cause leads to observed behavior
   - Timeline of when the issue was likely introduced

2. **Contributing Factors**
   - Environmental factors that enabled the bug
   - Process gaps that allowed the bug to reach production
   - Monitoring gaps that delayed detection""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("root-cause", "analysis", "hypothesis", "verification", "process", "guidance")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Fix Implementation & Verification",
                usageDescription = "Structured approach to implementing bug fixes and thoroughly verifying the resolution",
                contentSample = """### Fix Strategy Development
Plan fix approach based on root cause analysis:

1. **Solution Design**
   ```markdown
   ### Fix Strategy
   **Approach**: [High-level approach to fixing the issue]
   **Files to Modify**: [Specific files that need changes]
   **Risk Assessment**: [Potential risks of the fix]
   **Alternatives Considered**: [Other approaches evaluated]
   ```

2. **Implementation Planning**
   - Break fix into logical steps
   - Identify testing approach for each step
   - Plan rollback strategy if fix causes issues
   - Consider timing and deployment requirements

### Fix Implementation Process
Follow systematic implementation approach:

1. **Branch Setup**
   ```bash
   # Create fix branch
   git checkout main
   git pull origin main
   git checkout -b bugfix/[issue-description]
   ```

2. **Incremental Development**
   - Implement fix in small, testable increments
   - Test each increment thoroughly
   - Commit changes with clear, descriptive messages
   - Document any unexpected discoveries

3. **Code Quality Focus**
   - Follow existing code patterns and conventions
   - Add appropriate error handling
   - Include logging for future debugging
   - Consider performance implications

### Comprehensive Testing Strategy
Ensure fix resolves issue without creating new problems:

1. **Fix Verification Testing**
   ```bash
   # Run targeted tests
   ./gradlew test --tests "*[RelatedTestClass]"
   
   # Test specific bug scenario
   [Specific test commands for bug reproduction]
   ```

2. **Regression Testing**
   - Run full test suite to ensure no new issues
   - Test related functionality thoroughly
   - Verify edge cases still work correctly
   - Check performance hasn't degraded

3. **Integration Testing**
   - Test in environment similar to where bug occurred
   - Verify fix works with real data
   - Test with concurrent users if applicable
   - Check monitoring and logging work correctly

### Verification Documentation
Document fix effectiveness:

1. **Fix Validation Results**
   ```markdown
   ### Verification Results
   - **Bug Reproduction**: [Can no longer reproduce original issue]
   - **Regression Tests**: [All existing tests pass]
   - **Performance**: [No performance degradation observed]
   - **Edge Cases**: [Edge cases still work correctly]
   ```

2. **Task Status Update**
   - Use `update_task` MCP tool to update progress
   - Document fix implementation approach
   - Note any complications or learnings
   - Update task complexity if it differed from estimate

3. **Prevention Measures**
   ```markdown
   ### Prevention Strategy
   - **Tests Added**: [New tests to prevent regression]
   - **Monitoring**: [Additional monitoring or alerts added]
   - **Process Improvements**: [Process changes to prevent similar issues]
   - **Documentation**: [Documentation updates made]
   ```

### Ready for Review
Prepare fix for review and deployment:
- All tests pass including new tests for the bug
- Code follows project conventions
- Fix is well-documented and includes prevention measures
- Verification results clearly demonstrate issue resolution""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("fix-implementation", "verification", "testing", "prevention", "mcp-tools", "workflow-instruction", "checklist")
            )
        )

        return Pair(template, sections)
    }
}