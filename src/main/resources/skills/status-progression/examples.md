# Status Progression Skill - Examples

## Example 1: Successful Status Progression

**User Request:**
> "Move authentication feature to testing"

**Current State:**
- Status: in-development
- All 5 tasks completed
- No blockers

**Actions:**
1. Validate transition: in-development → testing
2. Check prerequisites:
   - All tasks complete: ✓
   - No blocked tasks: ✓
3. Enforce quality gates: (none at testing stage)
4. Update status

**Output:**
```
✓ Status Updated Successfully

Feature: Authentication System
Status: in-development → testing

Prerequisites Met:
✓ All 5 tasks completed
✓ No blocked tasks
✓ No outstanding work

Next Steps:
1. Trigger test suite
2. Validate test results
3. Progress to 'validating' when tests pass
```

---

## Example 2: Blocked by Prerequisites

**User Request:**
> "Move feature to testing"

**Current State:**
- Status: in-development
- 3/5 tasks completed
- 2 tasks still in progress

**Actions:**
1. Validate transition
2. Check prerequisites
3. Fail validation

**Output:**
```
✗ Cannot Progress to Testing

Feature: Payment Integration
Current Status: in-development
Requested: testing

Blockers:
✗ Incomplete tasks: 2 remaining
  - T3: API integration (in-progress, 70% done)
  - T4: Error handling (pending)

Requirements for 'testing' status:
✓ Feature has tasks
✗ All tasks must be complete or in testing

Action Required:
Complete or move to testing: T3, T4

Estimated time to unblock: 2 hours
```

---

## Example 3: Quality Gate Enforcement

**User Request:**
> "Complete authentication feature"

**Current State:**
- Status: validating
- All tasks complete
- Tests configured

**Actions:**
1. Validate transition to completed
2. Enforce quality gates
3. Trigger testing hook
4. Tests fail

**Output:**
```
✗ Cannot Complete Feature: Quality Gates Failing

Feature: Authentication System
Current Status: validating
Requested: completed

Quality Gate Failures:

❌ TESTING GATE: FAILED
   Test Results: 3 failures

   Failing Tests:
   1. AuthenticationTest.testLoginFlow
      Error: Invalid token format
      File: tests/auth.test.ts:45

   2. AuthenticationTest.testLogoutFlow
      Error: Session not cleared
      File: tests/auth.test.ts:67

   3. AuthenticationTest.testTokenRefresh
      Error: Refresh token expired
      File: tests/auth.test.ts:89

❌ COVERAGE GATE: WARNING
   Current: 68%
   Required: 80%
   Missing: 12%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ACTIONS REQUIRED
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Fix 3 failing tests
2. Add tests to reach 80% coverage
3. Re-trigger testing gate

Suggestions:
- Launch Test Engineer to address failures
- Focus on token handling logic
- Add integration tests for session management

Feature remains in 'validating' status.
Retry completion after fixes.
```

---

## Example 4: Automatic Status Progression

**User Request:**
> "Check if feature can progress automatically"

**Configuration:**
```yaml
automation:
  auto_progress_status: true
```

**Current State:**
- Status: in-development
- All tasks just completed

**Actions:**
1. Check auto-progression enabled: ✓
2. Determine next status: testing
3. Validate transition: ✓
4. Update status automatically

**Output:**
```
✓ Status Automatically Progressed

Feature: User Profile
Previous: in-development
Current: testing

Trigger: All 3 tasks completed
Auto-progression: enabled

Next Steps:
Status will remain 'testing' until:
1. Test suite is triggered
2. Tests pass
3. Manual progression to 'validating'

Or disable auto-progression in config:
.taskorchestrator/config.yaml
automation.auto_progress_status: false
```

---

## Example 5: Review Gate Workflow

**User Request:**
> "Complete payment feature"

**Configuration:**
```yaml
quality_gates:
  review:
    enabled: true
    blocking: true
```

**Current State:**
- Status: validating
- Tests passing
- Review configured

**Actions:**
1. Check quality gates
2. Review gate enabled
3. Cannot skip to completed
4. Must go through pending-review

**Output:**
```
⏸️ Review Required

Feature: Payment Integration
Current: validating
Cannot skip to: completed

Review Gate: ENABLED (blocking)

Status Flow:
validating → pending-review → completed
            ↑
         You are here

Next Steps:
1. Move to 'pending-review' status
2. Await team review
3. On approval: Move to 'completed'
4. On rejection: Return to 'in-development'

Reviewers:
(No specific reviewers assigned - any team member can review)

Override: Disable review gate in config
.taskorchestrator/config.yaml
quality_gates.review.enabled: false
```

---

## Example 6: Blocked Status Detection

**User Request:**
> "Why did task status change to blocked?"

**Current State:**
- Task recently started
- Has 2 dependencies
- 1 dependency incomplete

**Actions:**
1. Check task dependencies
2. Find incomplete blocker
3. Auto-set to blocked status

**Output:**
```
Task Status: pending → blocked

Task: Implement payment API (T3)
Auto-blocked by dependency check

Blocked By:
❌ T1: Create database schema
   Status: in-progress (60% complete)
   Est. completion: 1 hour

Dependencies Satisfied:
✓ T2: Setup payment provider
   Status: completed

Auto-Unblock:
Task will automatically return to 'pending' when T1 completes.

Actions Available:
1. Wait for T1 to complete (recommended)
2. Remove dependency if not truly required
3. Start work on mock/stub until T1 ready

Status will auto-progress when blocker resolves.
```

---

## Example 7: Custom Status Flow

**Configuration:**
```yaml
status_progression:
  features:
    custom_flow:
      - planning
      - design-review
      - in-development
      - code-review
      - testing
      - staging
      - production
```

**User Request:**
> "Move feature to code-review"

**Actions:**
1. Load custom flow from config
2. Validate transition
3. Check prerequisites
4. Update status

**Output:**
```
✓ Status Updated (Custom Flow)

Feature: New Dashboard
Status: in-development → code-review

Custom Flow Active:
planning → design-review → in-development
         → code-review → testing → staging
         → production

Current Stage: code-review

Next Steps:
1. Team reviews code changes
2. Address review comments
3. On approval: Move to 'testing'
4. On issues: Return to 'in-development'

Configured in:
.taskorchestrator/config.yaml
status_progression.features.custom_flow
```

---

## Example 8: Skip Status Validation

**User Request:**
> "Move from planning to testing"

**Normal Flow:**
```
planning → in-development → testing
```

**Actions:**
1. Validate transition
2. Detect status skip (skipping in-development)
3. Fail validation

**Output:**
```
✗ Invalid Status Transition

Feature: Quick Prototype
Current: planning
Requested: testing

Error: Cannot skip statuses

Normal progression:
planning → in-development → testing
         ↑ You tried to skip this

Allowed Transitions from 'planning':
✓ in-development (next)
✓ blocked (if issues arise)

To move to 'testing':
1. First move to 'in-development'
2. Complete required work
3. Then move to 'testing'

Note: Backward transitions allowed
(e.g., testing → in-development for rework)
```

---

## Example 9: Validate Before Starting Task

**User Request:**
> "Start work on task T5"

**Current State:**
- Task status: pending
- Has 2 dependencies
- Both dependencies incomplete

**Actions:**
1. Validate transition: pending → in-progress
2. Check dependencies
3. Find blockers
4. Fail validation

**Output:**
```
✗ Cannot Start Task: Dependencies Incomplete

Task: Integration Tests (T5)
Current: pending
Requested: in-progress

Dependency Check:
❌ T2: Implement API
   Status: in-progress (40% complete)
   Est: 2 hours remaining

❌ T3: Create UI
   Status: pending
   Est: Not started

Requirements:
All BLOCKS dependencies must be completed
before task can start.

Status: Auto-set to 'blocked'

Recommendations:
1. Wait for T2 and T3 to complete
2. Work on other tasks in parallel
3. Check back in 3 hours

Task will auto-unblock when dependencies resolve.
```

---

## Example 10: Status Progression with Testing Hook

**User Request:**
> "Validate feature"

**Current State:**
- Status: testing
- Tests configured via hooks

**Actions:**
1. Validate transition: testing → validating
2. Trigger testing hook
3. Wait for test results
4. Tests pass
5. Update status

**Output:**
```
✓ Status Progressed with Testing

Feature: User Authentication
Status: testing → validating

Test Execution:
Hook: run-tests
Duration: 45 seconds

Test Results:
✓ 127 tests passed
✓ 0 tests failed
✓ Coverage: 87% (exceeds 80% threshold)
✓ No linting errors

Quality Gates:
✓ All tests pass
✓ Coverage threshold met
✓ No blocking issues

Next Status: validating
Feature is validated and ready for review/completion.

To Complete:
Move to 'completed' status (will check final gates)
```

---

## Integration Examples

### With Feature Orchestration
```
1. Feature Orchestration: Requests status change
2. Status Progression: Validates transition
3. Status Progression: Checks prerequisites
4. Status Progression: Enforces quality gates
5. Status Progression: Updates status
6. Feature Orchestration: Continues workflow
```

### With Task Orchestration
```
Task Completion Flow:
1. Specialist: Completes task work
2. Task Orchestration: Creates summary
3. Status Progression: Validates completion
4. Status Progression: Checks dependencies resolved
5. Status Progression: Marks completed
6. Task Orchestration: Triggers cascade
```

### Automatic Progression
```
Background Monitoring:
1. Status Progression: Checks all features/tasks
2. For each: Determine if can auto-progress
3. If conditions met: Auto-update status
4. Notify user of progression
5. Repeat periodically
```

---

## Configuration Examples

### Strict Quality Gates
```yaml
quality_gates:
  testing:
    enabled: true
    blocking: true
    requirements:
      all_tests_pass: true
      coverage_threshold: 90  # Strict
      no_linting_errors: true

  security:
    enabled: true
    blocking: true

  review:
    enabled: true
    blocking: true
    assignees: ["tech-lead@company.com"]
```

### Relaxed Quality Gates
```yaml
quality_gates:
  testing:
    enabled: true
    blocking: false  # Warn only
    requirements:
      coverage_threshold: 60  # Lower bar

  review:
    enabled: false  # Skip reviews
```

### Custom Status Flow
```yaml
status_progression:
  features:
    enterprise_flow:
      - planning
      - design-review
      - security-review
      - in-development
      - code-review
      - testing
      - qa-validation
      - staging
      - production-approval
      - production
```

---

## Token Efficiency

### Efficient Validation
```javascript
// Check status transition only
validate_status_transition("feature", uuid, "testing")
// ~100 tokens
```

### With Prerequisites
```javascript
// Full validation with prerequisites
validate + check_prerequisites + enforce_gates
// ~450 tokens total
```

**Much less than old pattern of passing full context through managers (2900 tokens)**
