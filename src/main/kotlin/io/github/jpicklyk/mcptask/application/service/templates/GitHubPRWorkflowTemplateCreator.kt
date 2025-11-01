package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import java.util.*

/**
 * Creates a template for the GitHub pull request workflow using GitHub MCP server and standard git commands,
 * optimized for AI agent consumption with best practices integration.
 */
object GitHubPRWorkflowTemplateCreator {

    fun create(): Pair<Template, List<TemplateSection>> {
        val templateId = UUID.randomUUID()
        val template = Template(
            id = templateId,
            name = "GitHub PR Workflow",
            description = "A standardized workflow template for GitHub pull request creation and management using GitHub MCP server and standard git commands, optimized for AI agents with best practices integration.",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "System",
            tags = listOf("github", "pull-request", "workflow", "ai-optimized", "mcp-tools", "git")
        )

        val sections = listOf(
            TemplateSection(
                templateId = templateId,
                title = "Pre-Push Validation",
                usageDescription = "Essential validation steps before pushing branch and creating PR",
                contentSample = """### Sync with Latest Main
Always sync with latest main before creating PR:

```bash
# Fetch and rebase onto main
git fetch origin
git rebase origin/main

# Run tests after sync
./gradlew build test
```

### Pre-Push Checklist
- [ ] All tests pass locally
- [ ] Code compiles successfully
- [ ] Branch synced with latest origin/main
- [ ] No merge conflicts
- [ ] Commit messages are clear and descriptive
- [ ] No sensitive information in commits""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("github", "sync", "validation", "workflow-instruction", "checklist", "commands")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Create Pull Request",
                usageDescription = "Push branch and create PR using GitHub MCP tools",
                contentSample = """### Push Branch
```bash
git push -u origin [branch-name]
```

### Create PR via GitHub MCP
Use GitHub MCP tools to create pull request with:

**PR Title**: Follow conventional commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`)

**PR Description**:
```markdown
## Description
[What changed and why]

## Changes Made
- [Change 1]
- [Change 2]

## Testing
- [ ] Unit tests passing
- [ ] Integration tests passing
- [ ] Manual testing completed

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Documentation updated if needed
- [ ] Related issues linked

Closes #[issue-number]
```

### Configure PR
- Add reviewers using GitHub MCP tools
- Apply appropriate labels (feature, bugfix, documentation)
- Link related issues
- Set as draft if work-in-progress""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("git", "pull-request", "mcp-tools", "workflow-instruction", "commands")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Review & Merge",
                usageDescription = "Handle reviews, address feedback, and merge PR using GitHub MCP tools",
                contentSample = """### Monitor PR Status
Use GitHub MCP tools to:
- Check review status and approvals
- Monitor CI/CD pipeline results
- Track requested changes

### Address Review Feedback
```bash
# Make requested changes
git add [modified-files]
git commit -m "address review: [description]"
git push origin [branch-name]
```

Respond to comments via GitHub MCP tools:
- Acknowledge feedback and explain changes
- Ask for clarification when needed
- Mark conversations resolved

### Handle Merge Conflicts
```bash
# Sync and resolve conflicts
git fetch origin
git rebase origin/main
# Resolve conflicts in IDE
git rebase --continue
git push --force-with-lease origin [branch-name]
```

### Merge PR
Use GitHub MCP tools to merge when ready:

**Pre-merge checklist**:
- [ ] All required reviews approved
- [ ] All CI/CD checks passing
- [ ] No merge conflicts
- [ ] Branch up-to-date with main

**Merge strategy** (use GitHub MCP tools):
- **Squash and Merge**: Recommended for most features (clean history)
- **Merge Commit**: When preserving commit history is important
- **Rebase and Merge**: For linear history with clean commits

### Post-Merge Cleanup
```bash
# Update local main
git checkout main
git pull origin main
git branch -d [branch-name]
```

Use GitHub MCP tools to:
- Delete remote branch
- Close related issues
- Update task status
- Notify team if needed""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("review", "merge", "mcp-tools", "cleanup", "process", "checklist", "commands")
            )
        )

        return Pair(template, sections)
    }
}