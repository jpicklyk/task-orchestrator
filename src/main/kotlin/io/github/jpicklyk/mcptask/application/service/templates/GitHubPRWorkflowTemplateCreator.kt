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
                title = "Pre-Push Validation & Sync",
                usageDescription = "Best practices for validating changes and syncing with latest main before creating PR",
                contentSample = """### Prerequisites Check
Before pushing branch and creating PR, ensure GitHub MCP server is available:

1. **GitHub MCP Server Status**
   - Verify GitHub MCP server is running and authenticated
   - Use GitHub MCP tools to check authentication status
   - Ensure proper permissions for repository operations

2. **Repository Access Verification**
   - Use GitHub MCP tools to verify repository access
   - Check that you have push permissions to the repository
   - Confirm repository settings and branch protection rules

### Sync with Latest Main Branch
**Critical Step**: Always sync with latest main before creating PR:

1. **Fetch Latest Changes**
   ```bash
   git fetch origin
   ```

2. **Check Main Branch Status**
   ```bash
   # Compare your branch with origin/main
   git log --oneline HEAD..origin/main
   git log --oneline origin/main..HEAD
   ```

3. **Sync Strategy Decision**
   Choose appropriate sync strategy based on changes:
   
   **Option A: Rebase (Recommended for clean history)**
   ```bash
   git rebase origin/main
   # Resolve any conflicts if they arise
   # Test after rebase to ensure functionality works
   ```
   
   **Option B: Merge (If rebase is complex)**
   ```bash
   git merge origin/main
   # Resolve any conflicts if they arise
   # Commit the merge
   ```

4. **Post-Sync Validation**
   ```bash
   # Run build and tests after sync
   ./gradlew build test
   
   # Verify functionality still works as expected
   # Fix any issues introduced by sync
   ```

### Final Pre-Push Checklist
- [ ] All tests pass locally
- [ ] Code compiles successfully
- [ ] Branch is synced with latest origin/main
- [ ] No merge conflicts remain
- [ ] Functionality works as expected
- [ ] Commit messages are clear and descriptive
- [ ] No sensitive information in code or commits""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("github", "sync", "validation", "best-practices")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Branch Push & PR Creation with MCP",
                usageDescription = "Process for pushing branch and creating pull request using GitHub MCP server tools",
                contentSample = """### Branch Push & PR Creation with MCP

### Push Branch to GitHub
1. **Push with Tracking**
   ```bash
   git push -u origin [branch-name]
   ```

2. **Verify Push Success**
   ```bash
   # Check push was successful
   git status
   git branch -vv
   
   # Verify branch exists on remote
   git ls-remote --heads origin [branch-name]
   ```

### Pull Request Creation via GitHub MCP
Use GitHub MCP server tools for PR creation and management:

1. **Repository Context Setup**
   - Use GitHub MCP tools to get repository information
   - Verify repository settings and permissions
   - Check branch protection rules for target branch (main)

2. **PR Creation via MCP Tools**
   - Use GitHub MCP server to create pull request programmatically
   - Set appropriate base branch (typically main) and head branch
   - Include comprehensive PR title and description

3. **PR Title Best Practices**
   Follow conventional commit format:
   - `feat: add user authentication system`
   - `fix: resolve search returning empty results`
   - `refactor: extract validation logic to service layer`
   - `docs: update API documentation`
   - `test: add integration tests for authentication`

4. **PR Description Template**
   Create comprehensive PR description using GitHub MCP tools:
   ```markdown
   ## Description
   [Brief description of changes and their purpose]
   
   ## Type of Change
   - [ ] Bug fix (non-breaking change which fixes an issue)
   - [ ] New feature (non-breaking change which adds functionality)
   - [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
   - [ ] Documentation update
   - [ ] Code refactoring
   
   ## Changes Made
   - [Specific change 1]
   - [Specific change 2]
   - [Specific change 3]
   
   ## Testing
   - [ ] Unit tests added/updated and passing
   - [ ] Integration tests pass
   - [ ] Manual testing completed
   - [ ] Edge cases considered and tested
   - [ ] Performance impact assessed
   
   ## Checklist
   - [ ] Code follows project style guidelines
   - [ ] Self-review completed
   - [ ] Documentation updated if needed
   - [ ] No breaking changes (or documented with migration guide)
   - [ ] Related issues linked
   - [ ] Appropriate labels applied
   
   ## Related Issues
   Closes #[issue-number]
   Related to #[issue-number]
   ```

### PR Configuration via GitHub MCP
Use GitHub MCP server tools to configure the PR:

1. **Reviewer Assignment**
   - Use GitHub MCP tools to add reviewers to the PR
   - Add individual reviewers by username
   - Add team reviewers if applicable
   - Consider code ownership and expertise areas

2. **Labels and Metadata**
   - Use GitHub MCP tools to add appropriate labels (feature, bugfix, documentation, etc.)
   - Add milestone if applicable
   - Assign to project boards if used
   - Link related issues

3. **PR Settings**
   - Verify merge settings are appropriate for the repository
   - Check if draft status should be used for work-in-progress
   - Ensure proper branch protection rules are followed""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = true,
                tags = listOf("git", "pull-request", "mcp-tools", "creation")
            ),
            TemplateSection(
                templateId = templateId,
                title = "Review Management & Updates",
                usageDescription = "Managing pull request reviews, addressing feedback, and handling updates using GitHub MCP tools",
                contentSample = """### Review Management & Updates

### Monitor PR Status with GitHub MCP
Track PR progress using GitHub MCP server tools:

1. **Check PR Status**
   - Use GitHub MCP tools to get current PR status
   - Monitor review requests and approvals
   - Check CI/CD pipeline results
   - Track any requested changes

2. **Review Progress Tracking**
   - Use GitHub MCP tools to check review status and comments
   - Monitor required vs. optional reviews
   - Track approval status from different reviewers
   - Check for any blocking review requests

### Addressing Review Feedback
Systematic approach to handling reviewer feedback:

1. **Review Feedback Analysis**
   - Use GitHub MCP tools to retrieve all PR comments and reviews
   - Analyze feedback by reviewer and comment type
   - Prioritize critical vs. optional feedback
   - Plan implementation approach for requested changes

2. **Making Requested Changes**
   ```bash
   # Make the requested changes locally
   # Edit files based on reviewer feedback
   
   # Stage and commit with descriptive message
   git add [modified-files]
   git commit -m "address review: [description of changes]"
   
   # Push updates to PR branch
   git push origin [branch-name]
   ```

3. **Responding to Comments**
   - Use GitHub MCP tools to add responses to specific comments
   - Acknowledge feedback and explain changes made
   - Ask for clarification when feedback is unclear
   - Mark conversations as resolved when appropriate

### Handling Merge Conflicts
When conflicts arise with main branch:

1. **Conflict Detection**
   - Use GitHub MCP tools to check for merge conflicts
   - Monitor PR mergeable status
   - Get notified when conflicts occur

2. **Sync with Latest Main**
   ```bash
   git fetch origin
   git checkout [branch-name]
   git rebase origin/main
   # or git merge origin/main
   ```

3. **Resolve Conflicts**
   ```bash
   # Use IDE or merge tool to resolve conflicts
   # After resolving, continue rebase
   git rebase --continue
   
   # Or for merge approach
   git add [resolved-files]
   git commit -m "resolve: merge conflicts with main"
   ```

4. **Force Push Updates**
   ```bash
   # After rebase, force push is required
   git push --force-with-lease origin [branch-name]
   ```

### CI/CD Integration Monitoring
Monitor automated checks using GitHub MCP tools:

1. **Check Status**
   - Use GitHub MCP tools to view CI/CD check status
   - Monitor build and test results
   - Track deployment pipeline status
   - Get detailed failure information

2. **Handle CI Failures**
   - Review failed check details via GitHub MCP tools
   - Fix issues in local branch
   - Commit and push fixes
   - Monitor re-run results

### Review Response Best Practices
Effective communication during review process:

**Acknowledging Feedback**:
```
Thanks for catching this! I've updated the validation logic to handle this edge case in commit abc123.
```

**Explaining Decisions**:
```
I chose this approach because it maintains consistency with the existing pattern in UserService. 
However, I'm open to refactoring if you prefer the alternative approach.
```

**Requesting Clarification**:
```
Could you clarify what you mean by 'more robust error handling'? 
Are you referring to specific exception types or the error message format?
```""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = true,
                tags = listOf("review", "feedback", "mcp-tools", "conflict-resolution")
            ),
            TemplateSection(
                templateId = templateId,
                title = "PR Approval & Merge Process",
                usageDescription = "Final steps for PR approval, merging, and cleanup using GitHub MCP tools and git commands",
                contentSample = """### PR Approval & Merge Process

### Pre-Merge Final Validation
Before merging, ensure all requirements are met using GitHub MCP tools:

1. **Check PR Readiness**
   - Use GitHub MCP tools to verify all required reviews are approved
   - Check that all CI/CD checks are passing
   - Confirm PR is mergeable (no conflicts)
   - Verify branch is up-to-date with main

2. **Final Requirements Checklist**
   - [ ] All required reviews approved
   - [ ] All CI/CD checks passing
   - [ ] No merge conflicts with main
   - [ ] Branch is up-to-date with main
   - [ ] Documentation updated if needed
   - [ ] Breaking changes documented

### Merge Strategy Selection
Use GitHub MCP tools to merge the PR with appropriate strategy:

1. **Squash and Merge (Recommended for features)**
   - Use GitHub MCP tools to perform squash merge
   - Combines all commits into single clean commit
   - Keeps main branch history linear
   - Good for feature branches with many small commits

2. **Merge Commit (For significant features)**
   - Use GitHub MCP tools to create merge commit
   - Preserves individual commit history
   - Shows explicit merge point
   - Good when commit history is important

3. **Rebase and Merge (For clean linear history)**
   - Use GitHub MCP tools to rebase and merge
   - Creates completely linear history
   - No merge commit
   - Requires clean, well-structured commits

### Post-Merge Cleanup and Verification
After successful merge, perform cleanup:

1. **GitHub Cleanup via MCP**
   - Use GitHub MCP tools to delete the remote feature branch
   - Verify PR status shows as "merged"
   - Check that merge commit appears in main branch history

2. **Local Cleanup**
   ```bash
   # Switch to main and update
   git checkout main
   git pull origin main
   
   # Delete local feature branch
   git branch -d [branch-name]
   
   # Verify cleanup
   git branch -a
   ```

3. **Verify Merge Success**
   ```bash
   # Check that changes are in main
   git log --oneline -n 10
   
   # Verify functionality in main branch
   ./gradlew build test
   ```

### Post-Merge Actions
Complete the workflow with follow-up actions using GitHub MCP tools:

1. **Update Related Issues**
   - Use GitHub MCP tools to close related issues if not auto-closed
   - Add comments linking to the merged PR
   - Update issue status and labels as needed

2. **Documentation Updates**
   - Update changelog if maintained
   - Update project documentation if needed
   - Notify team members of significant changes via GitHub MCP tools

3. **Deployment Considerations**
   - Use GitHub MCP tools to check deployment workflow status
   - Monitor automated deployment if configured
   - Verify deployment success if applicable

### Success Notification
Document successful completion:

1. **Task Updates**
   - Use MCP Task Orchestrator tools to update task status
   - Document lessons learned or issues encountered
   - Note actual effort vs. estimated effort

2. **Team Communication**
   - Use GitHub MCP tools to notify relevant team members
   - Share any important implementation decisions
   - Document any follow-up tasks needed

### Troubleshooting Common Issues
Handle common merge problems using GitHub MCP tools:

**Merge conflicts after approval**:
- Use GitHub MCP tools to add comment explaining conflict resolution
- Follow conflict resolution process and update PR
- Notify reviewers that conflicts have been resolved

**Failed CI after approval**:
- Use GitHub MCP tools to check detailed failure information
- Fix issues and push updates
- Monitor re-run status via GitHub MCP tools

**Branch protection rule failures**:
- Use GitHub MCP tools to verify all required status checks pass
- Ensure required reviewers have approved
- Check branch is up-to-date with main via GitHub MCP tools""",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 3,
                isRequired = true,
                tags = listOf("merge", "cleanup", "mcp-tools", "post-merge")
            )
        )

        return Pair(template, sections)
    }
}