# Jira Integration Configuration

## Use Case

**When to Use**:
- Team uses Jira for project management
- Need Jira ticket references in git history
- Want automatic linking between code and tickets
- Require traceability from feature to code changes

**Best For**:
- Teams using Atlassian suite
- Organizations requiring Jira for tracking
- Projects with external stakeholders who use Jira
- Teams needing detailed project reporting

---

## Configuration

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming Conventions
branch_naming_bug: "bugfix/PROJ-{task-id-short}-{description}"
branch_naming_feature: "feature/PROJ-{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/PROJ-{task-id-short}-{description}"
branch_naming_enhancement: "enhancement/PROJ-{task-id-short}-{description}"

## Commit Message Customization
commit_message_prefix: "[PROJ-{task-id-short}]"
```

**Replace `PROJ` with your actual Jira project key** (e.g., AUTH, USER, API, etc.)

---

## What It Does

**Behavior**:
- Prefixes all branches with Jira project key
- Adds Jira ticket reference to commit messages
- Creates automatic links from commits to Jira tickets
- Enables Jira's "View Development" panel

**Branch Examples** (with project key "AUTH"):
- Bug: `bugfix/AUTH-70490b4d-token-expiration`
- Feature: `feature/AUTH-a2a36aeb-oauth-integration`
- Hotfix: `hotfix/AUTH-12bf786d-security-patch`

**Commit Examples**:
```
[AUTH-70490b4d] feat: add OAuth2 authentication provider
[AUTH-a2a36aeb] fix: resolve token refresh race condition
[AUTH-12bf786d] hotfix: patch authentication bypass vulnerability
```

---

## Jira Integration Benefits

**Automatic Linking**:
- Commits appear in Jira ticket's "Development" panel
- Branches linked to tickets automatically
- Pull requests visible in Jira interface
- Deployment status tracked in Jira

**Traceability**:
- See all code changes for a ticket
- Track which commits fixed which bugs
- Audit trail from requirement to deployment
- Release notes auto-generated from ticket IDs

**Workflow Automation** (with Jira Automation):
- Auto-transition ticket to "In Progress" when branch created
- Auto-transition to "In Review" when PR opened
- Auto-transition to "Done" when PR merged
- Auto-comment on ticket with deployment status

---

## Advanced Jira Integration

**Add Jira API Integration** (optional):

```markdown
## Jira API Configuration (optional)
jira_base_url: "https://yourcompany.atlassian.net"
jira_project_key: "PROJ"

## Feature Implementation Workflow Override
### Jira-Integrated Workflow
1. Create branch: feature/PROJ-{task-id-short}-{description}
2. Auto-transition Jira ticket to "In Progress"
3. Implement feature with tests
4. Create PR with Jira ticket link
5. PR automatically comments on Jira ticket
6. Request code review
7. After approval, merge PR
8. Auto-transition Jira ticket to "Done"
9. Auto-comment with deployment link
```

**Note**: API integration requires Jira credentials and MCP server extension.

---

## Multiple Project Keys

**For Multi-Project Repositories**:

```markdown
## Branch Naming by Component
branch_naming_bug: "{component}/bugfix/{task-id-short}-{description}"
branch_naming_feature: "{component}/feature/{task-id-short}-{description}"

# Where {component} maps to Jira project:
# - auth -> AUTH
# - user -> USER
# - payment -> PAY
```

Then use task tags like `component-auth` to determine the correct prefix.

---

## Customization Tips

1. **Match Your Jira Project Key**:
   Replace `PROJ` with your actual project key:
   - Authentication team: `AUTH`
   - User management: `USER`
   - API development: `API`

2. **Add Sprint Information**:
   ```markdown
   commit_message_prefix: "[PROJ-{task-id-short}][Sprint-42]"
   ```

3. **Include Priority in Branch Names**:
   ```markdown
   branch_naming_bug: "bugfix/PROJ-{task-id-short}-{priority}-{description}"
   ```

4. **Link to Epic**:
   Add epic information to commits:
   ```markdown
   commit_message_prefix: "[PROJ-{task-id-short}][EPIC-123]"
   ```

---

## Jira Smart Commits

Enhance your commits with Jira Smart Commit syntax:

**Basic Syntax**:
```
[PROJ-70490b4d] feat: add OAuth integration #time 4h #comment Implemented OAuth2 flow
```

**Commands**:
- `#comment` - Add comment to ticket
- `#time` - Log work time
- `#close` - Close/resolve ticket
- `#<transition>` - Transition ticket status

**Example**:
```
[AUTH-12345] fix: resolve login timeout #time 2h #comment Fixed race condition #close
```

**Task Orchestrator Integration**:
```markdown
## Commit Message Customization
commit_message_prefix: "[PROJ-{task-id-short}]"
commit_message_suffix: "#time {complexity}h"
```

---

## Reporting Benefits

**Jira Reports Enhanced**:
- Burndown charts include all commits
- Velocity tracking more accurate
- Time tracking via commit messages
- Release notes auto-generated from ticket IDs

**Example Release Notes** (auto-generated):
```
## Release 2.1.0

### Features
- [AUTH-70490b4d] OAuth2 authentication integration
- [USER-a2a36aeb] User profile enhancements

### Bug Fixes
- [AUTH-12bf786d] Security vulnerability patched
- [USER-6d115591] Profile update race condition fixed
```

---

## Trade-offs

**Advantages**:
- ✅ Seamless Jira integration
- ✅ Automatic traceability
- ✅ Better project visibility
- ✅ Enhanced reporting

**Disadvantages**:
- ⚠️ Longer branch names
- ⚠️ Requires consistent Jira usage
- ⚠️ More setup complexity
- ⚠️ Dependent on Jira availability

---

## Team Requirements

**Minimum Setup**:
- Jira project with proper permissions
- Git integration enabled in Jira
- Team trained on Jira workflow
- Consistent ticket creation process

**Best Results**:
- Jira Automation rules configured
- Smart Commits enabled
- All work tracked in Jira tickets
- Regular Jira hygiene (closing old tickets, etc.)
