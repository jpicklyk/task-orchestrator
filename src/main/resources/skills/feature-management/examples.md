# Feature Management Skill - Usage Examples

This document provides concrete examples of using the Feature Management Skill for common scenarios.

## Example 1: Recommend Next Task (Simple Feature)

**Scenario**: You're working on a feature with 5 tasks. 2 are complete, 3 are pending. You want to know what to work on next.

**Feature State**:
```
Feature ID: f1234567-89ab-cdef-0123-456789abcdef
Name: "User Authentication System"
Total Tasks: 5
Completed: 2
Pending: 3
Status: in_development
```

**Skill Invocation**:
```
You: "What task should I work on next for the authentication feature?"
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeTaskDependencies=true,
     includeTaskCounts=true
   )

2. get_next_task(
     featureId='f1234567-89ab-cdef-0123-456789abcdef',
     limit=1,
     includeDetails=true
   )
```

**Skill Response**:
```
Feature: User Authentication System
Progress: 2/5 tasks completed
Status: in_development

Recommended Task:
- Title: Implement login API endpoint
- Task ID: t2222222-89ab-cdef-0123-456789abcdef
- Priority: high | Complexity: 7
- Reason: Database schema completed (dependency satisfied), highest priority unblocked task
- Context: Create POST /api/auth/login endpoint with JWT token generation

Next: Work on this task or launch Backend Engineer specialist for implementation.
```

## Example 2: Check Feature Progress

**Scenario**: You want to see overall progress on a feature with many tasks.

**Skill Invocation**:
```
You: "What's the status of the authentication feature?"
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeTaskCounts=true
   )
```

**Skill Response**:
```
Feature: User Authentication System
Status: in_development
Progress: 3/7 tasks (43%)

Task Breakdown:
- Completed: 3
  - Database schema design
  - API specification document
  - Password hashing implementation
- In Progress: 2
  - Login API endpoint (Backend Engineer)
  - Login UI component (Frontend Developer)
- Pending: 2
  - Integration tests (waiting for login API + UI)
  - API documentation (waiting for all implementation)
- Blocked: 0

Next: 2 tasks currently in progress. Use "recommend next task" when these complete to get the next batch.
```

## Example 3: Complete Feature (All Tests Pass)

**Scenario**: All tasks are complete, all tests passing. Ready to mark feature done.

**Feature State**:
```
All 7 tasks completed
Implementation tasks report: "All tests passing"
Build successful across all components
```

**Skill Invocation**:
```
You: "All tasks are done for the authentication feature. Mark it complete."
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeSections=true,
     includeTaskCounts=true
   )

2. add_section(
     entityType='FEATURE',
     entityId='f1234567-89ab-cdef-0123-456789abcdef',
     title='Summary',
     usageDescription='Summary of completed feature work including tasks, files, and technical decisions',
     content='[markdown summary content]',
     contentFormat='MARKDOWN',
     ordinal=999,
     tags='summary,completion'
   )

3. update_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     summary='Implemented complete user authentication system with JWT tokens, password hashing (bcrypt), login/logout endpoints, and React UI. All 127 tests passing.'
   )

4. update_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     status='completed'
   )
```

**Skill Response**:
```
Feature: User Authentication System
Status: Completed ✓
Tasks: 7 completed
Summary section created

Implemented complete user authentication system with JWT tokens, password hashing (bcrypt), login/logout endpoints, and React UI. All 127 tests passing. Ready for production deployment.
```

## Example 4: Complete Feature Blocked (Tests Failing)

**Scenario**: All tasks done but one task has failing tests. Feature completion should be blocked.

**Feature State**:
```
All 7 tasks marked "completed"
Task "Login API endpoint" summary: "Implemented all endpoints. 3 integration tests failing."
```

**Skill Invocation**:
```
You: "All tasks are complete. Mark the authentication feature done."
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeSections=true,
     includeTaskCounts=true
   )

[Step 3 QUALITY GATE triggers - detects failing tests]
[NO add_section call - blocked]
[NO update_feature calls - blocked]
```

**Skill Response**:
```
❌ Cannot complete feature - Task 'Login API endpoint' (ID: t2222222-89ab-cdef-0123-456789abcdef) has failing tests. Feature completion blocked until all tests pass.

Failed Task Summary: "Implemented all endpoints. 3 integration tests failing."

Action Required: Reopen task for Backend Engineer to fix test failures. Feature cannot be marked complete with failing tests.
```

## Example 5: List Blocked Tasks

**Scenario**: You notice some tasks can't start yet. You want to understand the blockers.

**Skill Invocation**:
```
You: "What tasks are blocked in the authentication feature?"
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_blocked_tasks(featureId='f1234567-89ab-cdef-0123-456789abcdef')

2. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true
   )
```

**Skill Response**:
```
Feature: User Authentication System
Blocked Tasks: 2

Blockers:
1. Integration Tests
   Blocked by: Login API endpoint (in_progress), Login UI component (in_progress)
   Impact: Final task in feature, blocks feature completion
   Resolution: Wait for both Login API and Login UI tasks to complete

2. API Documentation
   Blocked by: Login API endpoint (in_progress), Password Reset API (pending)
   Impact: Documentation task, doesn't block other work
   Resolution: Wait for Login API to complete, then Password Reset API can start

Recommended Action: Prioritize completing "Login API endpoint" (in_progress) as it unblocks both Integration Tests and Password Reset API.
```

## Example 6: Recommend Next Task (No Tasks Available)

**Scenario**: You ask for next task but all are either completed, in-progress, or blocked.

**Feature State**:
```
Completed: 4 tasks
In Progress: 2 tasks
Blocked: 1 task (waiting on the 2 in-progress)
```

**Skill Invocation**:
```
You: "What's the next task for the authentication feature?"
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeTaskDependencies=true,
     includeTaskCounts=true
   )

2. get_next_task(
     featureId='f1234567-89ab-cdef-0123-456789abcdef',
     limit=1,
     includeDetails=true
   )
   [Returns empty - no unblocked tasks]
```

**Skill Response**:
```
Feature: User Authentication System
Progress: 4/7 tasks completed

All tasks are either:
- Completed: 4
- In Progress: 2 (Login API endpoint, Login UI component)
- Blocked: 1 (Integration Tests - waiting on the 2 in-progress)

Next: Wait for "Login API endpoint" and "Login UI component" to complete. These will unblock "Integration Tests" which is the final task.
```

## Example 7: Starting First Task in Feature

**Scenario**: Feature just created, all tasks pending. Need to start work.

**Feature State**:
```
Status: planning
All tasks: pending
No dependencies between first few tasks
```

**Skill Invocation**:
```
You: "What task should I start first for the new authentication feature?"
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeTaskDependencies=true,
     includeTaskCounts=true
   )

2. get_next_task(
     featureId='f1234567-89ab-cdef-0123-456789abcdef',
     limit=1,
     includeDetails=true
   )

3. update_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     status='in_development'
   )
   [Automatically transitions from planning to in_development]
```

**Skill Response**:
```
Feature: User Authentication System
Progress: 0/7 tasks completed
Status: in_development (updated from planning)

Recommended Task:
- Title: Database schema design
- Task ID: t1111111-89ab-cdef-0123-456789abcdef
- Priority: high | Complexity: 5
- Reason: No dependencies, foundational work for entire feature, highest priority
- Context: Design and implement database tables for users, sessions, and password reset tokens

Next: Work on this task or launch Database Engineer specialist for implementation.
```

## Example 8: Multiple Tasks Ready (Parallel Work)

**Scenario**: Feature has multiple unblocked tasks that can run in parallel.

**Feature State**:
```
Database schema: completed
API spec: completed
Password hashing: completed

Unblocked and ready:
- Login API endpoint (depends on schema ✓, depends on spec ✓)
- Password Reset API (depends on schema ✓, depends on spec ✓)
- Logout API endpoint (depends on schema ✓)
```

**Skill Invocation**:
```
You: "What should I work on next for authentication?"
[Feature Management Skill activates]
```

**Tool Calls**:
```
1. get_feature(
     id='f1234567-89ab-cdef-0123-456789abcdef',
     includeTasks=true,
     includeTaskDependencies=true,
     includeTaskCounts=true
   )

2. get_next_task(
     featureId='f1234567-89ab-cdef-0123-456789abcdef',
     limit=1,
     includeDetails=true
   )
   [Returns 1 task, but notes multiple are ready]
```

**Skill Response**:
```
Feature: User Authentication System
Progress: 3/7 tasks completed
Status: in_development

Recommended Task:
- Title: Login API endpoint
- Task ID: t2222222-89ab-cdef-0123-456789abcdef
- Priority: high | Complexity: 7
- Reason: Highest priority among 3 unblocked tasks (Login API, Password Reset API, Logout API)
- Context: Create POST /api/auth/login endpoint with JWT token generation

Note: 3 tasks are currently unblocked and can be worked on in parallel. If working with a team or using parallel subagents, also consider:
- Password Reset API (Priority: high, Complexity: 6)
- Logout API endpoint (Priority: medium, Complexity: 3)

Next: Work on Login API, or assign other unblocked tasks to team members/subagents.
```

## Usage Tips

**When to Use Each Workflow**:

1. **Recommend Next Task**:
   - Starting work on a feature
   - Just finished a task, need to know what's next
   - Feature has pending tasks and you're ready to work

2. **Check Feature Progress**:
   - Want overall status update
   - Planning team coordination
   - Reporting to stakeholders
   - Feature has many tasks and you need overview

3. **Complete Feature**:
   - All tasks are marked complete
   - All implementation work has passing tests
   - Ready to close out the feature

4. **List Blocked Tasks**:
   - Progress seems stuck
   - Want to understand dependencies
   - Planning which blockers to resolve first
   - Feature has complex dependency chains

**Common Patterns**:

- **Linear workflow**: Recommend → Work → Recommend → Work → ... → Complete
- **Progress check**: Check Progress → Recommend Next → Work
- **Blocker investigation**: List Blocked → Resolve blocker → Recommend Next
- **Feature closure**: Check Progress → Verify all complete → Complete Feature

**Integration with Subagents**:

- Skill recommends WHAT to work on
- Subagents do the actual work
- Example flow: Skill says "Login API task next" → Launch Backend Engineer subagent to implement
