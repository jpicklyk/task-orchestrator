# Enterprise Team Configuration

## Use Case

**When to Use**:
- Large organization with compliance requirements
- Regulated industry (finance, healthcare, government)
- Multiple approval gates required
- Security scanning mandatory
- Audit trail essential

**Best For**:
- Enterprise development teams
- Compliance-heavy environments
- Teams with dedicated QA
- Organizations with security requirements

---

## Configuration

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming Conventions
branch_naming_bug: "bugfix/{priority}-{task-id-short}-{description}"
branch_naming_feature: "feature/{feature-id-short}/{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/URGENT-{task-id-short}-{description}"
branch_naming_enhancement: "enhancement/{complexity}-{task-id-short}-{description}"

## Commit Message Customization
commit_message_prefix: "[{type}/{task-id-short}]"

## Custom Workflow Steps
### Bug Fix Workflow Override
1. Create branch from main
2. Implement fix with comprehensive tests (minimum 80% coverage)
3. Run local security scan: `npm run security:scan`
4. Deploy to development environment: `./scripts/deploy-dev.sh`
5. Run automated test suite: `npm run test:integration:dev`
6. Deploy to staging environment: `./scripts/deploy-staging.sh`
7. Request QA testing via PR comment: `@qa-team please verify`
8. Wait for QA sign-off (required)
9. Request security team review for security-related bugs
10. After all approvals, merge to main
11. Automated deployment to production with rollback capability
12. Monitor production metrics for 24 hours

Note: All template validation requirements still apply

### Feature Implementation Workflow Override
1. Create branch from main
2. Implement feature incrementally with tests
3. Document API changes in OpenAPI spec
4. Run security scan: `npm run security:scan`
5. Deploy to development: `./scripts/deploy-dev.sh`
6. Run full test suite including integration tests
7. Deploy to staging: `./scripts/deploy-staging.sh`
8. Create PR with:
   - Feature description
   - Screenshots/videos for UI changes
   - Breaking changes documentation
   - Migration guide if needed
9. Request reviews from:
   - Tech lead (required)
   - Security team (if touching auth/data)
   - QA team (required)
10. Address review feedback
11. QA team performs manual testing
12. After all approvals, schedule production deployment
13. Deploy during approved change window
14. Monitor production for 48 hours
```

---

## What It Does

**Behavior**:
- Always requires pull requests with multiple approvals
- Comprehensive branch naming with priority/complexity
- Mandatory security scanning
- Multi-stage deployment (dev → staging → production)
- Extensive testing requirements
- Audit trail through task IDs in branches and commits

**Branch Examples**:
- Critical Bug: `bugfix/high-70490b4d-security-vulnerability`
- Feature: `feature/a3d0ab76/70490b4d-oauth-integration`
- Emergency Hotfix: `hotfix/URGENT-12bf786d-data-leak`
- Enhancement: `enhancement/8-6d115591-api-performance`

**Commit Examples**:
```
[feature/70490b4d] feat: add OAuth2 authentication
[bug/a2a36aeb] fix: resolve token expiration issue
[hotfix/12bf786d] hotfix: patch critical security vulnerability
```

---

## Quality Gates

**Automated Checks** (run on every PR):
- ✅ Unit test coverage > 80%
- ✅ Integration tests passing
- ✅ Security scan (no high/critical vulnerabilities)
- ✅ Code style/linting
- ✅ Build succeeds
- ✅ API documentation updated

**Manual Reviews** (required approvals):
- ✅ Tech lead approval
- ✅ QA team sign-off
- ✅ Security review (for security-sensitive changes)
- ✅ Architecture review (for major changes)

---

## Compliance Features

**Audit Trail**:
- Every change traced to task ID
- PR discussions preserved
- Approval history recorded
- Deployment timestamps logged

**Rollback Capability**:
- Every deployment tagged
- Automated rollback scripts
- Database migration reversibility
- Feature flags for gradual rollout

**Security**:
- Mandatory security scanning
- Security team review for sensitive changes
- Secrets scanning in commits
- Dependency vulnerability checks

---

## Customization Tips

**Adjust for Your Organization**:

1. **Add More Approval Gates**:
   ```markdown
   9. Request reviews from:
      - Tech lead (required)
      - Security team (if touching auth/data)
      - QA team (required)
      - Product manager (for UX changes)
      - Legal team (for terms/privacy changes)
   ```

2. **Add Compliance Checks**:
   ```markdown
   12. Verify GDPR compliance
   13. Update privacy documentation
   14. Log data processing changes
   ```

3. **Custom Deployment Windows**:
   ```markdown
   12. Schedule deployment during approved change window:
       - Tuesday/Thursday 2-4 PM ET only
       - No deployments during month-end close
       - Emergency hotfixes require VP approval
   ```

---

## Trade-offs

**Advantages**:
- ✅ High code quality
- ✅ Comprehensive testing
- ✅ Strong security posture
- ✅ Complete audit trail
- ✅ Controlled deployments

**Disadvantages**:
- ⚠️ Slower development velocity
- ⚠️ Higher process overhead
- ⚠️ More coordination required
- ⚠️ Longer time to production

---

## Team Size Recommendations

**Minimum Team**:
- 10+ developers
- Dedicated QA team (2+ testers)
- Security team or designated security reviewer
- Tech lead / architect
- DevOps/SRE support

**Ideal For**:
- Teams of 20-100+ developers
- Multiple concurrent projects
- Distributed teams across time zones
- Organizations with compliance mandates
