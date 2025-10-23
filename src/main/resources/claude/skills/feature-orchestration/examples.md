# Feature Orchestration Skill - Examples

## Example 1: Simple Feature Creation

**User Request:**
> "Create a user profile feature"

**Skill Assessment:**
- Description: Short and clear (< 50 characters)
- Scope: Single purpose (user profile)
- Expected tasks: 2-3 (database, backend, frontend)
- Complexity: Simple

**Actions:**
1. Query templates for FEATURE type
2. Create feature with "Requirements Specification" template
3. Create 3 tasks directly:
   - Database: "Create user profile schema"
   - Backend: "Implement profile API endpoints"
   - Frontend: "Build profile UI component"

**Output:**
```
✓ Feature created: User Profile
  Status: planning
  Tasks: 3 created
  Templates: Requirements Specification

Next: Ready for task execution. Use Task Orchestration Skill to begin.
```

---

## Example 2: Complex Feature - Delegate to Feature Architect

**User Request:**
> "Build a comprehensive OAuth integration system supporting Google, GitHub, and Microsoft authentication with JWT token management, refresh token handling, secure session storage, role-based access control, and audit logging"

**Skill Assessment:**
- Description: Long (> 200 characters)
- Scope: Multiple systems (OAuth providers, JWT, RBAC, audit)
- Technical complexity: High (authentication, security)
- Expected tasks: 8+ tasks
- Complexity: Complex

**Actions:**
1. Assess complexity score: 9/10
2. Return recommendation to orchestrator

**Output:**
```
⚠️ Complex Feature Detected

This feature requires detailed planning:
- Multiple integration points (3 OAuth providers)
- Security-critical components
- Cross-cutting concerns (RBAC, audit)
- Estimated 8+ tasks

Recommendation: Launch Feature Architect subagent
The Feature Architect will:
1. Formalize requirements
2. Apply security and API design templates
3. Create comprehensive feature structure
4. Hand off to Planning Specialist for task breakdown
```

---

## Example 3: Progress Feature to Testing

**User Request:**
> "Check status of authentication feature"

**Actions:**
1. Query feature with overview operation
2. Analyze task status:
   - Total: 5 tasks
   - Completed: 4 tasks
   - In Progress: 1 task
3. Determine next status: Cannot progress yet

**Output:**
```
Feature: Authentication System
Status: in-development (80% complete)

Tasks:
✓ Database schema (completed)
✓ Backend API (completed)
✓ Frontend UI (completed)
✓ Unit tests (completed)
⏳ Integration tests (in-progress)

Cannot progress to testing yet - 1 task still in progress.
Next action: Wait for integration tests to complete.
```

---

## Example 4: Feature Completion with Quality Gates

**User Request:**
> "Complete authentication feature"

**Actions:**
1. Check all tasks completed: ✓
2. Trigger testing hook
3. Tests pass: ✓
4. Create feature summary section
5. Mark feature complete

**Output:**
```
✓ Feature Completed: Authentication System

Summary:
- 5 tasks completed
- All tests passing
- Coverage: 87%

Key Changes:
- OAuth integration with Google, GitHub
- JWT token management
- Session handling and refresh tokens
- Comprehensive test suite

Files Changed: 23 files
- Backend: 12 files
- Frontend: 8 files
- Tests: 3 files
```

---

## Example 5: Quality Gate Failure

**User Request:**
> "Complete user management feature"

**Actions:**
1. Validate completion readiness
2. All tasks complete: ✓
3. Trigger testing hook
4. Tests fail: ✗

**Output:**
```
✗ Cannot Complete Feature: Quality Gates Failing

Testing Gate: FAILED
- 3 test failures detected
  • UserManagementTest.testDeleteUser
  • UserManagementTest.testUpdatePermissions
  • UserManagementTest.testBulkImport

Coverage Gate: WARNING
- Current: 68%
- Required: 80%
- Missing: 12% coverage

Actions Required:
1. Fix 3 failing tests
2. Add tests to reach 80% coverage threshold
3. Re-run testing gate

Suggestion: Launch Test Engineer to address failures.
```

---

## Example 6: Parallel Execution Planning

**User Request:**
> "Plan execution for notification system feature"

**Actions:**
1. Get all tasks (6 tasks total)
2. Analyze dependencies
3. Create execution batches

**Output:**
```
Execution Plan: Notification System

Batch 1 (Parallel - 40% time saved):
├─ Database schema (Complexity: 4)
├─ Email templates (Complexity: 3)
└─ UI notification center (Complexity: 5)
  Total: 12 units → 5 units (parallel)

Batch 2 (Sequential):
└─ Backend notification service (Complexity: 7)
  Depends on: Database schema

Batch 3 (Parallel - 33% time saved):
├─ Integration tests (Complexity: 5)
└─ Documentation (Complexity: 4)
  Total: 9 units → 5 units (parallel)

Estimated Time Savings: 40% overall
Next: Launch Batch 1 with 3 specialists in parallel
```

---

## Example 7: Check Feature Progress

**User Request:**
> "What's next for the API redesign feature?"

**Actions:**
1. Query feature overview
2. Analyze current state
3. Determine blockers and next actions

**Output:**
```
Feature: API Redesign
Status: in-development
Progress: 60% (6/10 tasks)

Current Status:
✓ 4 completed
⏳ 2 in progress
  • API versioning implementation
  • Swagger documentation
⏸️ 4 pending

Blockers: None

Next Actions:
1. Wait for current 2 tasks to complete
2. Then launch Batch 3 (2 parallel tasks):
   - Integration tests
   - Migration scripts

Parallel opportunity available once current batch completes.
```

---

## Example 8: Feature with Review Gate

**User Request:**
> "Complete payment integration feature"

**Actions:**
1. Check configuration: review gate enabled
2. All tasks complete: ✓
3. Tests passing: ✓
4. Update status to pending-review

**Output:**
```
Feature: Payment Integration
Status: validating → pending-review

✓ All Prerequisites Met:
  • All 8 tasks completed
  • Tests passing (94% coverage)
  • No blockers

⏸️ Awaiting Review:
  Review required before completion (configured in .taskorchestrator/config.yaml)

Next: Review feature implementation, then:
  • Approve: Move to completed
  • Request changes: Return to in-development
```

---

## Token Usage Examples

### Efficient Status Check
```javascript
// Using overview operation
query_container(operation="overview", containerType="feature", id="uuid")
// Returns: ~1,200 tokens (task list + counts, no sections)
```

### Inefficient Status Check (Don't do this)
```javascript
// Using get with sections
query_container(operation="get", containerType="feature", id="uuid", includeSections=true)
// Returns: ~18,500 tokens (full sections + tasks)
```

**Savings: 93% token reduction**

---

## Integration Examples

### With Task Orchestration Skill
```
1. Feature Orchestration: Creates feature + tasks
2. Task Orchestration: Executes tasks in parallel batches
3. Feature Orchestration: Validates quality gates
4. Feature Orchestration: Marks complete
```

### With Dependency Orchestration Skill
```
User: "This feature has complex dependencies"
1. Feature Orchestration: Creates feature
2. Dependency Orchestration: Analyzes dependency graph
3. Task Orchestration: Uses analysis for batching
```

### With Status Progression Skill
```
User: "Progress feature through workflow"
1. Status Progression: Validates transition
2. Status Progression: Checks prerequisites
3. Feature Orchestration: Enforces quality gates
4. Status Progression: Updates status
```
