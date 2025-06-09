package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for a local git branching workflow, optimized for AI agent consumption with clear workflow steps.
 */
object LocalGitBranchingWorkflowTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "Local Git Branching Workflow",
            description = "A standardized workflow template for local git operations and branch management, optimized for AI agents working with version control.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("git", "workflow", "ai-optimized", "version-control", "branching")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Branch Creation Process",
                usageDescription = "Step-by-step process for creating and setting up a new git branch for this work",
                contentSample = """## Branch Creation Process

### Pre-Branch Analysis
Before creating a new branch, analyze the current task to determine:
- **Branch Type**: feature/, bugfix/, hotfix/, or docs/
- **Branch Scope**: Is this a single task or part of a larger feature?
- **Dependencies**: Are there any other branches this work depends on?

### Branch Naming Convention
Create a concise, descriptive branch name following this pattern:
- `feature/[short-description]` - For new features or enhancements
- `bugfix/[issue-description]` - For bug fixes
- `hotfix/[critical-issue]` - For critical production fixes
- `docs/[documentation-update]` - For documentation changes

**Examples**:
- `feature/user-authentication`
- `bugfix/search-empty-results`
- `hotfix/memory-leak-fix`

### Branch Creation Commands
1. **Ensure clean working directory**:
   ```bash
   git status
   # If there are uncommitted changes, either commit or stash them
   git stash push -m "WIP before branch creation"
   ```

2. **Update main branch**:
   ```bash
   git checkout main
   git pull origin main
   ```

3. **Create and switch to new branch**:
   ```bash
   git checkout -b [branch-name]
   ```

4. **Verify branch creation**:
   ```bash
   git branch --show-current
   git log --oneline -n 3
   ```

### Initial Setup
- Set up branch tracking if working with remote repository
- Verify the starting point is correct
- Note the commit hash where the branch was created for reference""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("git", "branch-creation", "setup")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implementation Steps",
                usageDescription = "Structured approach to implementing changes while maintaining good git hygiene",
                contentSample = """## Implementation Steps

### Development Workflow
Follow these steps during implementation to maintain clean git history:

1. **Make Focused Changes**
   - Work on one logical change at a time
   - Keep changes related to the current task scope
   - Avoid mixing different types of changes (features, bug fixes, refactoring)

2. **Regular Status Checks**
   ```bash
   git status
   git diff
   ```
   - Review changes before committing
   - Ensure only intended files are modified

3. **Incremental Commits**
   - Commit logically related changes together
   - Use descriptive commit messages
   - Commit early and often for complex changes

4. **Commit Message Format**
   ```
   [type]: [short description]
   
   [optional longer description]
   
   [optional references to issues/tasks]
   ```
   
   **Types**: feat, fix, docs, style, refactor, test, chore
   
   **Examples**:
   ```
   feat: add user authentication endpoint
   
   fix: resolve search returning empty results
   
   refactor: extract validation logic to separate class
   ```

### File Management
- Use `git add -p` for selective staging when multiple changes exist
- Review diffs before staging: `git diff --cached`
- Use `.gitignore` appropriately for generated files

### Progress Tracking
- Document significant milestones in commit messages
- Use git tags for important versions if applicable
- Keep notes of decisions made during implementation""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("implementation", "commits", "workflow")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Testing & Verification",
                usageDescription = "Process for testing changes and verifying implementation before finalizing commits",
                contentSample = """## Testing & Verification

### Pre-Commit Testing
Before committing changes, ensure:

1. **Build Verification**
   ```bash
   ./gradlew build
   ```
   - Verify code compiles without errors
   - Address any compilation warnings
   - Ensure all dependencies are properly configured

2. **Test Execution**
   ```bash
   # Run all tests
   ./gradlew test
   
   # Run specific test class if targeting particular functionality
   ./gradlew test --tests "ClassName"
   
   # Run tests with specific pattern
   ./gradlew test --tests "*IntegrationTest"
   ```

3. **Code Quality Checks**
   - Review code formatting and style
   - Check for unused imports and variables
   - Verify proper error handling
   - Ensure code follows project conventions

### Functionality Verification
- Test the specific functionality implemented
- Verify edge cases and error conditions
- Check integration with existing features
- Ensure no regressions in related functionality

### Documentation Updates
- Update inline code documentation as needed
- Verify README or other docs reflect changes
- Update API documentation if applicable

### Pre-Commit Checklist
- [ ] Code compiles successfully
- [ ] All tests pass
- [ ] No obvious code style violations
- [ ] Functionality works as expected
- [ ] No unintended side effects
- [ ] Documentation is current
- [ ] Git status shows only intended changes""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("testing", "verification", "quality-assurance")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Commit Preparation",
                usageDescription = "Final steps for preparing and making commits with proper documentation",
                contentSample = """## Commit Preparation

### Final Review Process
Before making the final commit:

1. **Review All Changes**
   ```bash
   git diff HEAD
   git status
   ```
   - Confirm all intended changes are included
   - Verify no unintended files or changes are staged
   - Check for any forgotten files or cleanup

2. **Staging Strategy**
   ```bash
   # Stage all changes
   git add .
   
   # Or stage selectively
   git add [specific-files]
   
   # Review staged changes
   git diff --cached
   ```

3. **Commit Execution**
   ```bash
   git commit -m "[type]: [clear, concise description]"
   
   # For complex changes, use detailed commit message
   git commit
   # This opens editor for multi-line commit message
   ```

### Commit Message Best Practices
- **Subject line**: 50 characters or less, imperative mood
- **Body**: Explain what and why, not how
- **References**: Include task IDs, issue numbers, or related context

**Good Examples**:
```
feat: implement OAuth2 authentication flow

Add Google and Apple sign-in integration with proper error
handling and token refresh mechanisms. Includes unit tests
and integration with existing user management system.

Addresses task requirements for secure authentication.
```

### Post-Commit Actions
1. **Verify Commit**
   ```bash
   git log --oneline -n 3
   git show HEAD
   ```

2. **Branch Status Check**
   ```bash
   git status
   git branch -v
   ```

3. **Next Steps Planning**
   - Determine if more commits are needed
   - Plan for potential branch push and PR creation
   - Consider if testing in different environment is needed

### Ready for Push
When the branch is ready for sharing:
- All commits are clean and well-documented
- All tests pass
- Code is ready for review
- Documentation is current""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("commit", "documentation", "finalization")
            )
        )

        return Pair(template, sections)
    }
}