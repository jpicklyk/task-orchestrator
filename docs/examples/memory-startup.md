# Startup Team Configuration

## Use Case

**When to Use**:
- Fast-moving startup environment
- Ship features quickly, iterate rapidly
- Minimal process overhead
- Direct commits to main branch

**Best For**:
- Early-stage startups
- MVP development
- Rapid prototyping teams
- Small co-located teams (3-5 people)

---

## Configuration

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "never"

## Branch Naming Conventions
branch_naming_bug: "fix/{description}"
branch_naming_feature: "feat/{description}"
branch_naming_hotfix: "hotfix/{description}"
branch_naming_enhancement: "improve/{description}"

## Commit Message Customization
commit_message_prefix: ""
```

---

## What It Does

**Behavior**:
- Never creates pull requests (merges directly to main)
- Uses simplified branch naming (no task IDs)
- No commit message prefixes
- Fast, minimal-friction workflow

**Branch Examples**:
- Bug: `fix/authentication-error`
- Feature: `feat/user-dashboard`
- Hotfix: `hotfix/payment-processing`

**Workflow**:
1. Create branch with short descriptive name
2. Implement changes with tests
3. Merge directly to main
4. Deploy immediately

---

## Customization Tips

**Add Complexity as You Grow**:

1. **Transition to PRs**: When team grows beyond 5 people:
   ```markdown
   use_pull_requests: "always"
   ```

2. **Add Task IDs**: When you need traceability:
   ```markdown
   branch_naming_feature: "feat/{task-id-short}-{description}"
   ```

3. **Add Deployment Steps**: When you need staging:
   ```markdown
   ## Feature Implementation Workflow Override
   ### Custom Deployment Process
   1. Implement feature
   2. Deploy to staging: `npm run deploy:staging`
   3. Test on staging
   4. Merge to main
   5. Deploy to production: `npm run deploy:prod`
   ```

---

## Trade-offs

**Advantages**:
- ✅ Fastest possible development cycle
- ✅ Minimal overhead
- ✅ No PR review delays
- ✅ Simple branch naming

**Disadvantages**:
- ⚠️ Less code review
- ⚠️ Higher risk of bugs reaching production
- ⚠️ Harder to trace features to requirements
- ⚠️ Not suitable for regulated industries

---

## When to Migrate

**Move to More Structure When**:
- Team grows beyond 5 people
- Multiple features in development simultaneously
- Customers affected by production bugs
- Need audit trail for changes
- Onboarding junior developers
