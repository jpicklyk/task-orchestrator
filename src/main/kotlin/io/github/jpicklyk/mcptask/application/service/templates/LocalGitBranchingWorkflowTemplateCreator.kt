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
                title = "Create Branch",
                usageDescription = "Creating and setting up a new git branch for local development",
                contentSample = """### Branch Naming
Choose appropriate branch prefix:
- `feature/[description]` - New features or enhancements
- `bugfix/[description]` - Bug fixes
- `hotfix/[description]` - Critical production fixes
- `docs/[description]` - Documentation changes

Examples: `feature/user-auth`, `bugfix/search-crash`

### Create Branch
```bash
# Update main
git checkout main
git pull origin main

# Create and switch to new branch
git checkout -b [branch-name]

# Verify
git branch --show-current
```""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("git", "branch-creation", "workflow-instruction", "commands")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Implement & Commit",
                usageDescription = "Making changes and committing with good git hygiene",
                contentSample = """### Development Workflow
1. **Make focused changes**: One logical change at a time
2. **Check status frequently**: `git status` and `git diff`
3. **Commit incrementally**: Logical chunks, not everything at once

### Before Committing
```bash
# Build and test
./gradlew build test

# Review changes
git status
git diff
```

### Commit Changes
```bash
# Stage changes
git add [files]  # or git add . for all

# Commit with clear message
git commit -m "[type]: [description]"
```

**Commit types**: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

**Examples**:
- `feat: add user authentication endpoint`
- `fix: resolve search returning empty results`
- `refactor: extract validation logic`

### Commit Message Best Practices
- Subject: 50 chars or less, imperative mood ("add" not "added")
- Body: Explain what and why (optional for small changes)
- Reference: Include task IDs or issue numbers if applicable""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("implementation", "commits", "workflow", "workflow-instruction", "commands")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Verify & Finalize",
                usageDescription = "Final verification before pushing branch",
                contentSample = """### Pre-Push Checklist
- [ ] All commits have clear messages
- [ ] Build succeeds: `./gradlew build`
- [ ] All tests pass: `./gradlew test`
- [ ] Code follows project style
- [ ] Documentation updated if needed
- [ ] Only intended changes included

### Review Commits
```bash
# View commit history
git log --oneline -n 5

# Review latest commit
git show HEAD

# Check branch status
git status
git branch -v
```

### Ready for Push
When branch is ready:
- All planned work is committed
- Tests pass
- Code is ready for review
- Ready to create PR (see GitHub PR Workflow template)""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("verification", "finalization", "checklist", "commands")
            )
        )

        return Pair(template, sections)
    }
}