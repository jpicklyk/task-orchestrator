package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.UUID

/**
 * Creates a template for bug reports, optimized for AI agent consumption with clear workflow steps.
 */
object BugReportTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Bug Report Template",
            description = "A structured template for bug reports, optimized for AI agent consumption with clear workflow steps to follow when implementing fixes.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("bug", "workflow", "ai-optimized", "standard-process")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Bug Description",
                usageDescription = "Detailed description of the bug including observed behavior vs. expected behavior",
                contentSample = """## Bug Description

### Observed Behavior
[Detailed description of the actual behavior or issue observed. Be specific about exact error messages, unexpected results, or problematic behaviors.]

### Expected Behavior
[Description of how the system should behave correctly. Explain what would be the proper outcome if the bug didn't exist.]

### Reproduction Steps
1. [Step-by-step instructions to reproduce the issue]
2. [Include specific inputs, configurations, or conditions necessary]
3. [Note any environmental factors that might be relevant]

### Impact
[Assessment of the severity of the bug and its impact on users or system functionality. Consider:
- Blocking critical functionality?
- Affecting many users?
- Data integrity concerns?
- Workarounds available?]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("bug-report", "description")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Technical Investigation",
                usageDescription = "Analysis of the potential causes and technical details of the bug",
                contentSample = """## Technical Investigation

### Potential Causes
[List of possible causes for the bug, based on observed behavior and system knowledge]
1. [Potential cause 1 with reasoning]
2. [Potential cause 2 with reasoning]

### Code Areas to Investigate
[Specific files, classes, or components likely involved in the issue]
1. `[File/class name]` - [Reason for investigation]
2. `[File/class name]` - [Reason for investigation]

### Related System Components
[Other components or services that might be affected or contributing]

### Diagnosis Notes
[Additional technical context, observations from logs, or other diagnostic information]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("technical", "investigation", "debugging")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Proposed Fix",
                usageDescription = "Suggested approach to fixing the issue with code examples if applicable",
                contentSample = """## Proposed Fix

### Root Cause
[Detailed explanation of the identified cause of the bug, based on investigation]

### Solution Approach
[High-level description of the proposed fix strategy]

### Implementation Details
```kotlin
// Code examples or pseudocode demonstrating the proposed changes
```

### Potential Side Effects
[Discussion of any risks or side effects the proposed changes might introduce]

### Alternative Approaches Considered
[Brief description of alternative solutions considered and why they were rejected]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("solution", "code", "fix")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verification Plan",
                usageDescription = "Steps to verify the bug is fixed after implementation",
                contentSample = """## Verification Plan

### Test Cases
1. [Test case 1: specific scenario to verify bug is fixed]
   - Input: [Test input]
   - Expected outcome: [Expected result]

2. [Test case 2: specific scenario to verify bug is fixed]
   - Input: [Test input]
   - Expected outcome: [Expected result]

### Regression Testing
[Description of existing tests or areas to check for potential regressions]

### Performance Considerations
[Any performance aspects that should be validated]

### Acceptance Criteria
- [Specific, measurable criterion that must be met]
- [Specific, measurable criterion that must be met]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("testing", "verification", "quality-assurance")
            ),
            TemplateSection(
                templateId = templateId,
                title = "AI Workflow",
                usageDescription = "Standardized workflow for AI agents to follow when implementing bug fixes",
                contentSample = """## AI Workflow

### Implementation Process
Follow these steps when implementing the fix for this bug:

1. **Branch Creation**
   - Create a new git branch with a name based on the bug but keep it concise
   - Example: `git checkout -b fix/search-tasks-empty-results`

2. **Branch Switch**
   - Switch to the new branch: `git checkout [branch-name]`

3. **Code Investigation**
   - Examine the associated classes related to the bug as identified in the Technical Investigation section
   - Use IDE tools to find references, implementations, and usages
   - Look for similar patterns in other, working parts of the codebase

4. **Fix Implementation**
   - Implement the fixes described in the Proposed Fix section
   - Keep changes focused only on addressing the reported issue
   - Use the JetBrains IDE tools to refactor, navigate, and edit code efficiently
   - Follow existing code style and patterns

5. **Build Verification**
   - Run `./gradlew build` to ensure the fixes compile correctly
   - Address any compilation errors or warnings

6. **Test Creation**
   - Create specific tests to verify the fix and prevent regression
   - Place tests in the appropriate test class following project conventions
   - Include both the failing case (which should now pass) and edge cases

7. **Test Verification**
   - Run `./gradlew build` again to compile the tests
   - Run the specific test to verify it passes with your fix

8. **Full Test Suite**
   - Run all project tests with `./gradlew test` to ensure no regressions
   - Address any failing tests

9. **Commit Preparation**
   - Inform the user that changes are ready to commit
   - Provide a suggested commit message
   - Remind them to push the branch and create a pull request if appropriate""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 4,
                isRequired = true,
                tags = listOf("ai-workflow", "process")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Follow-up Actions",
                usageDescription = "Additional actions needed after the bug is fixed",
                contentSample = """## Follow-up Actions

### Documentation Updates
[Any documentation that needs to be updated as a result of this bug or its fix]

### Related Issues
[Links or references to related bugs or issues that might need investigation]

### Preventative Measures
[Suggestions for preventing similar bugs in the future]

### Knowledge Sharing
[Notes on important learnings that should be shared with the team]""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 5,
                isRequired = false,
                tags = listOf("follow-up", "documentation", "prevention")
            )
        )

        return Pair(template, sections)
    }
}