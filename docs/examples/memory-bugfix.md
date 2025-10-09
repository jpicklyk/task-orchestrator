# Custom Bug Fix Workflow Configuration

## Use Case

**When to Use**:
- Bugs require special handling beyond features
- Production bugs need enhanced validation
- Staging deployment mandatory before production
- Root cause analysis required
- On-call team needs notification

**Best For**:
- Production systems with SLAs
- Teams with dedicated on-call rotation
- Systems requiring post-deployment monitoring
- Organizations with incident management process

---

## Configuration

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming Conventions
branch_naming_bug: "bugfix/{priority}-{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/URGENT-{task-id-short}-{description}"

## Bug Fix Workflow Override
### Production Bug Fix Process
1. **Triage & Analysis**
   - Create task with Bug Investigation template
   - Document symptoms and user impact
   - Gather logs and error traces
   - Identify root cause
   - Estimate severity and priority

2. **Branch & Notification**
   - Create branch: bugfix/{priority}-{task-id-short}-{description}
   - Notify on-call team via Slack: `#incidents channel`
   - Update incident tracker with bug task ID

3. **Implementation**
   - Implement fix following root cause analysis
   - Add comprehensive unit tests covering the bug scenario
   - Add regression tests to prevent recurrence
   - Update error handling if needed
   - Document the fix in task sections

4. **Local Validation**
   - Run full test suite locally: `npm run test:all`
   - Verify fix resolves original issue
   - Test edge cases and related functionality
   - Run security scan: `npm run security:scan`

5. **Staging Deployment**
   - Deploy to staging environment: `./scripts/deploy-staging.sh`
   - Run integration test suite: `npm run test:integration:staging`
   - Manual testing of bug scenario on staging
   - Performance testing if bug was performance-related
   - Monitor staging metrics for 30 minutes

6. **Pull Request**
   - Create PR with:
     - Bug description and root cause
     - Impact assessment
     - Fix explanation
     - Test coverage details
     - Screenshots/logs demonstrating fix
   - Link to incident tracker
   - Tag on-call team for review

7. **Review & Approval**
   - Code review by senior developer (required)
   - QA testing on staging (required for high/critical bugs)
   - On-call lead approval (for production bugs)
   - Security review (if security-related)

8. **Production Deployment**
   - Schedule deployment (immediate for critical, next window for others)
   - Create deployment runbook with rollback steps
   - Deploy to production: `./scripts/deploy-production.sh`
   - Monitor deployment: `./scripts/monitor-deployment.sh`

9. **Post-Deployment Validation**
   - Verify fix in production (check original bug scenario)
   - Monitor error rates for 2 hours
   - Monitor performance metrics
   - Check for new errors introduced
   - Notify affected users of resolution

10. **Incident Closure**
    - Update incident tracker with resolution
    - Document lessons learned in task sections
    - Update runbooks if needed
    - Schedule post-mortem for critical bugs
    - Close Jira ticket with resolution notes

Note: Template validation requirements still apply:
- Bug Investigation template analysis completed
- Root cause documented
- Test coverage for bug fix
- Regression tests passing

### Hotfix Workflow Override
### Emergency Production Hotfix Process
1. **Emergency Response**
   - Create task tagged as hotfix and critical
   - Immediately notify on-call team and engineering lead
   - Create incident in PagerDuty/incident tracker
   - Branch: hotfix/URGENT-{task-id-short}-{description}

2. **Rapid Implementation**
   - Implement minimal fix to restore service
   - Add tests covering the critical scenario
   - Skip comprehensive testing (time-critical)
   - Document what was done and why

3. **Fast-Track Review**
   - Get approval from on-call lead (can be async)
   - Deploy to production immediately: `./scripts/hotfix-deploy.sh`
   - Monitor closely for 4 hours

4. **Follow-Up**
   - Schedule proper fix within 48 hours
   - Create follow-up task for comprehensive solution
   - Post-mortem required within 24 hours
   - Update incident documentation
```

---

## What It Does

**Behavior**:
- Enforces comprehensive bug investigation
- Requires staging deployment and validation
- Mandates root cause analysis
- Includes post-deployment monitoring
- Automates on-call notifications

**Branch Examples**:
- Normal Bug: `bugfix/medium-70490b4d-login-timeout`
- High Priority: `bugfix/high-a2a36aeb-data-corruption`
- Emergency: `hotfix/URGENT-12bf786d-service-outage`

**Priority-Based Handling**:
- **Critical/Hotfix**: Immediate deployment, 4-hour monitoring
- **High**: Next deployment window, 2-hour monitoring
- **Medium**: Standard deployment cycle, 1-hour monitoring
- **Low**: Batch with next release, basic monitoring

---

## Notification Integration

**Slack Integration** (requires Slack bot):

```bash
# In workflow step 2:
./scripts/notify-oncall.sh \
  --severity "${priority}" \
  --task-id "${task-id-short}" \
  --description "${description}"
```

**PagerDuty Integration**:

```bash
# For critical bugs:
./scripts/create-incident.sh \
  --severity "high" \
  --title "Bug: ${description}" \
  --task-id "${task-id}"
```

---

## Monitoring & Validation

**Automated Monitoring** (post-deployment):

```bash
# Monitor error rates
./scripts/monitor-errors.sh --duration 2h --alert-threshold 5%

# Monitor performance
./scripts/monitor-performance.sh --duration 2h --p99-threshold 500ms

# Check for new errors
./scripts/check-new-errors.sh --since-deployment
```

**Validation Checklist**:
- [ ] Original bug scenario resolved
- [ ] No new errors introduced
- [ ] Performance metrics within normal range
- [ ] Error rate below baseline
- [ ] User-reported issues resolved
- [ ] Monitoring alerts clear

---

## Root Cause Analysis Template

**Required Documentation** (in task sections):

```markdown
## Root Cause Analysis

### Symptoms
- What users experienced
- Error messages/logs
- Affected user segments

### Investigation Steps
1. [Step taken to diagnose]
2. [Tools/queries used]
3. [Hypotheses tested]

### Root Cause
[Detailed explanation of underlying issue]

### Why It Happened
- Code defect
- Configuration error
- Integration issue
- Infrastructure problem

### Why It Wasn't Caught
- Missing test coverage
- Integration test gap
- Monitoring blind spot
- Edge case not considered

### Fix Description
[How the fix addresses root cause]

### Prevention Measures
- Tests added
- Monitoring improved
- Process changes
- Documentation updates
```

---

## Customization Tips

1. **Adjust Monitoring Duration**:
   ```markdown
   9. Monitor for 2 hours (critical) or 30 minutes (low priority)
   ```

2. **Add Performance Testing**:
   ```markdown
   5. Deploy to staging
      - Run load tests: `npm run test:load`
      - Verify no performance regression
   ```

3. **Custom Notification Channels**:
   ```markdown
   2. Notify stakeholders:
      - Slack #incidents for critical
      - Email for high priority
      - Jira comment for medium/low
   ```

4. **Add Rollback Steps**:
   ```markdown
   8. Production Deployment
      - Tag current version: `git tag pre-bugfix-${task-id-short}`
      - Deploy with feature flag (gradual rollout)
      - If errors spike, immediate rollback: `./scripts/rollback.sh`
   ```

---

## Integration with Incident Management

**Link to External Systems**:

```markdown
## Incident Tracker Integration
incident_tracker: "pagerduty"  # or "opsgenie", "jira-ops"
auto_create_incident: true  # for priority=high,critical
auto_update_status: true

## Workflow Enhancement
2. Branch & Notification
   - Create PagerDuty incident
   - Link task ID to incident
   - Update incident with progress
```

---

## Post-Mortem Process

**For Critical/High Priority Bugs**:

```markdown
10. Post-Incident Review (within 24h for critical, 1 week for high)
    - Schedule post-mortem meeting
    - Document timeline of events
    - Identify what went well
    - Identify improvements
    - Create action items as new tasks
    - Share lessons learned with team
```

---

## Trade-offs

**Advantages**:
- ✅ Thorough bug investigation
- ✅ Reduced production incidents
- ✅ Better root cause understanding
- ✅ Improved on-call response
- ✅ Comprehensive monitoring

**Disadvantages**:
- ⚠️ Slower bug fix cycle
- ⚠️ More process overhead
- ⚠️ Requires mature monitoring
- ⚠️ Needs incident management tooling

---

## Team Requirements

**Minimum Setup**:
- On-call rotation established
- Staging environment available
- Basic monitoring in place
- Incident tracking system

**Ideal Setup**:
- 24/7 on-call coverage
- Comprehensive monitoring (Datadog, NewRelic, etc.)
- Automated deployment pipelines
- Incident management platform (PagerDuty, OpsGenie)
- Post-mortem process established
