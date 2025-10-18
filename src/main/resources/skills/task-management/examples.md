# Task Management Skill - Examples

This document provides concrete examples of using the Task Management Skill for common scenarios.

## Example 1: Route Backend Task to Specialist

### Scenario
User wants to start work on a backend API task. Task Management Skill determines which specialist should handle it.

### Input
```
User: "Start work on task 550e8400-e29b-41d4-a716-446655440000"
```

### Skill Execution

**Step 1: Get Task Details**
```
get_task(
  id="550e8400-e29b-41d4-a716-446655440000",
  includeSections=true,
  includeFeature=false
)
```

**Response**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Implement User Authentication API",
  "status": "pending",
  "complexity": 7,
  "tags": ["backend", "api", "security", "authentication"],
  "sections": [
    {
      "title": "Requirements",
      "content": "### Functional Requirements\n- JWT token generation\n- Password hashing with bcrypt..."
    },
    {
      "title": "Technical Approach",
      "content": "### Implementation Strategy\n- Use Ktor framework for REST API..."
    }
  ]
}
```

**Step 2: Get Specialist Recommendation**
```
recommend_agent(taskId="550e8400-e29b-41d4-a716-446655440000")
```

**Response**:
```json
{
  "specialist": "Backend Engineer",
  "reasoning": "Task has 'backend' and 'api' tags, requires server-side implementation"
}
```

**Step 3: Format Recommendation**

**Output to Orchestrator**:
```
Recommend Backend Engineer for this task.

Task: Implement User Authentication API
Complexity: 7/10
Status: pending

Key Requirements:
- JWT token generation with 24-hour expiry
- Password hashing using bcrypt (cost factor 12)
- Rate limiting: 5 attempts per minute per IP
- Email/password authentication
- Refresh token support

Technical Approach:
- Use Ktor framework for REST API
- PostgreSQL for user storage (users table with hashed passwords)
- Redis for session management and rate limiting
- JWT library: auth0/java-jwt
- Endpoints: POST /auth/login, POST /auth/refresh, POST /auth/logout

Files to Modify:
- src/main/kotlin/api/AuthRoutes.kt (create)
- src/main/kotlin/service/AuthService.kt (create)
- src/main/kotlin/repository/UserRepository.kt (extend)

Dependencies: All complete (Database schema, API framework setup)

Launch: Task Backend Engineer START with taskId=550e8400-e29b-41d4-a716-446655440000
```

---

## Example 2: Complete Task with Summary

### Scenario
Backend Engineer reports completion of authentication API. Task Management Skill creates Summary section and marks task complete.

### Input
```
Backend Engineer: "Completed authentication API implementation. All tests passing."
```

### Skill Execution

**Step 1: Get Task Details**
```
get_task(
  id="550e8400-e29b-41d4-a716-446655440000",
  includeSections=true
)
```

**Step 2: Create Summary Section**
```
add_section(
  entityType="TASK",
  entityId="550e8400-e29b-41d4-a716-446655440000",
  title="Summary",
  usageDescription="Summary of completed work for dependency context",
  content="### Completed Work\n\nImplemented complete user authentication API with JWT token-based authentication.\n\n### Implementation Details\n\n**New Files Created**:\n- `src/main/kotlin/api/AuthRoutes.kt` - Authentication endpoints\n- `src/main/kotlin/service/AuthService.kt` - Business logic for auth operations\n- `src/main/kotlin/service/JwtService.kt` - JWT token generation and validation\n- `src/main/kotlin/model/AuthRequest.kt` - Request/response models\n- `src/test/kotlin/api/AuthRoutesTest.kt` - Integration tests\n\n**Modified Files**:\n- `src/main/kotlin/repository/UserRepository.kt` - Added findByEmail() method\n- `src/main/kotlin/Application.kt` - Registered auth routes\n- `src/main/resources/application.conf` - Added JWT secret configuration\n\n### Key Decisions\n\n**JWT Configuration**:\n- Token expiry: 24 hours (configurable via environment variable)\n- Refresh token expiry: 7 days\n- Algorithm: HS256 (HMAC with SHA-256)\n- Claims: userId, email, issuedAt, expiresAt\n\n**Password Security**:\n- BCrypt cost factor: 12 (balances security and performance)\n- Minimum password length: 8 characters\n- Password validation: Requires uppercase, lowercase, number, special character\n\n**Rate Limiting**:\n- Implemented using Redis with sliding window\n- Login attempts: 5 per minute per IP address\n- Lockout period: 15 minutes after exceeding limit\n- Clears on successful login\n\n### API Endpoints\n\n**POST /auth/login**\n- Request: `{\"email\": \"user@example.com\", \"password\": \"...\"}`\n- Response: `{\"token\": \"...\", \"refreshToken\": \"...\", \"expiresIn\": 86400}`\n- Status Codes: 200 (success), 401 (invalid credentials), 429 (rate limited)\n\n**POST /auth/refresh**\n- Request: `{\"refreshToken\": \"...\"}`\n- Response: `{\"token\": \"...\", \"expiresIn\": 86400}`\n- Status Codes: 200 (success), 401 (invalid token)\n\n**POST /auth/logout**\n- Request: `Authorization: Bearer <token>`\n- Response: `{\"message\": \"Logged out successfully\"}`\n- Status Codes: 200 (success), 401 (unauthorized)\n\n### Testing\n\n**Test Coverage**: 94%\n- Unit tests for AuthService (token generation, password validation)\n- Integration tests for all endpoints\n- Security tests for rate limiting and token validation\n- Error case tests for invalid inputs\n\n**All Tests Passing**: 37/37 tests pass\n\n### Next Steps for Dependent Tasks\n\n**For Frontend Implementation**:\n- Use POST /auth/login endpoint with email/password\n- Store JWT token in memory (not localStorage for security)\n- Include token in Authorization header for authenticated requests\n- Implement token refresh logic when receiving 401 responses\n- Handle rate limiting errors (429) with user-friendly message\n\n**For User Profile Tasks**:\n- Token contains userId claim - use for fetching user data\n- All profile endpoints should require valid JWT\n- Use `JwtService.validateToken()` to verify tokens\n\n### Performance Considerations\n\n- Average login time: 180ms (including BCrypt hashing)\n- Token validation: <5ms\n- Redis rate limiting check: <10ms\n- No database queries during token validation (stateless JWT)",
  contentFormat="MARKDOWN",
  ordinal=999
)
```

**Step 3: Mark Task Complete**
```
set_status(
  id="550e8400-e29b-41d4-a716-446655440000",
  status="completed"
)
```

**Output to Orchestrator**:
```
Task completed successfully.

Implemented user authentication API with JWT tokens, password hashing, and rate limiting.
All 37 tests passing. Summary section created for dependent tasks.
```

**Note**: If a PostToolUse hook is configured for `set_status`, it will automatically create a git commit at this point.

---

## Example 3: Check Dependencies Before Starting

### Scenario
User wants to start a task that has dependencies. Task Management Skill checks if dependencies are ready.

### Input
```
User: "Can I start task 660e9511-f39c-42d5-b827-557766551111?"
```

### Skill Execution

**Step 1: Get Task Dependencies**
```
get_task_dependencies(id="660e9511-f39c-42d5-b827-557766551111")
```

**Response**:
```json
{
  "task": {
    "id": "660e9511-f39c-42d5-b827-557766551111",
    "title": "Implement User Profile Frontend"
  },
  "dependencies": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Implement User Authentication API",
      "status": "completed"
    },
    {
      "id": "770e9622-g49d-53e6-c938-668877662222",
      "title": "Create User Profile API Endpoints",
      "status": "in_progress"
    },
    {
      "id": "880e9733-h59e-64f7-d049-779988773333",
      "title": "Design User Profile UI Components",
      "status": "pending"
    }
  ]
}
```

**Step 2: Analyze Dependencies**
```
Total: 3 dependencies
Completed: 1
In Progress: 1
Pending: 1
```

**Step 3: Get Details on Incomplete Dependencies**
```
get_task(id="770e9622-g49d-53e6-c938-668877662222", includeSections=false)
get_task(id="880e9733-h59e-64f7-d049-779988773333", includeSections=false)
```

**Output to Orchestrator**:
```
Task "Implement User Profile Frontend" is BLOCKED by incomplete dependencies.

Dependency Status:
✅ Implement User Authentication API (completed)
⏳ Create User Profile API Endpoints (in_progress, complexity: 6)
❌ Design User Profile UI Components (pending, complexity: 4)

Analysis:
- Authentication API is ready for integration
- Profile API is 60% complete (Backend Engineer working on it)
- UI designs not started yet (blocks frontend implementation)

Recommendation: WAIT
Cannot start frontend implementation without:
1. Profile API endpoints (needed for data fetching)
2. UI component designs (needed for component structure)

Estimated Wait Time: 4-6 hours
- Profile API: ~2 hours remaining
- UI Designs: ~2-4 hours (needs Planning Specialist assignment)

Suggested Action:
Consider starting task 880e9733 (UI Designs) first to unblock this task faster.
```

---

## Example 4: Update Task Status to Blocked

### Scenario
Specialist discovers a dependency issue mid-work and needs to mark task as blocked.

### Input
```
Frontend Engineer: "Can't continue on profile frontend. Profile API is returning 500 errors."
```

### Skill Execution

**Step 1: Get Current Task**
```
get_task(id="660e9511-f39c-42d5-b827-557766551111", includeSections=false)
```

**Step 2: Update Status**
```
set_status(
  id="660e9511-f39c-42d5-b827-557766551111",
  status="blocked"
)
```

**Step 3: Document Blocker**
```
update_task(
  id="660e9511-f39c-42d5-b827-557766551111",
  summary="Blocked: Profile API endpoints returning 500 errors. Cannot integrate until API is fixed. See task 770e9622 for API implementation."
)
```

**Output to Orchestrator**:
```
Task marked as blocked.

Blocker: Profile API endpoints not functioning correctly (500 errors)
Blocking Task: 770e9622 (Create User Profile API Endpoints)

Action Required:
1. Backend Engineer needs to debug Profile API
2. Frontend Engineer should resume after API is fixed
3. Estimated impact: 1-2 hour delay

Status updated: in_progress → blocked
```

---

## Example 5: Route Database Task

### Scenario
Task requires database schema changes. Skill recommends Database Engineer.

### Input
```
User: "Start task 990e9844-i69f-75g8-e15a-88aa99884444"
```

### Skill Execution

**Step 1: Get Task**
```
get_task(
  id="990e9844-i69f-75g8-e15a-88aa99884444",
  includeSections=true
)
```

**Response**:
```json
{
  "title": "Add User Preferences Table",
  "tags": ["database", "schema", "migration"],
  "complexity": 5,
  "sections": [
    {
      "title": "Requirements",
      "content": "### Database Schema\n- Create user_preferences table\n- Foreign key to users table..."
    }
  ]
}
```

**Step 2: Recommend Specialist**
```
recommend_agent(taskId="990e9844-i69f-75g8-e15a-88aa99884444")
```

**Response**: `"Database Engineer"`

**Output to Orchestrator**:
```
Recommend Database Engineer for this task.

Task: Add User Preferences Table
Complexity: 5/10

Database Schema Requirements:
- Table: user_preferences
- Columns: id (UUID PK), user_id (FK to users), theme (varchar), language (varchar), notifications_enabled (boolean), created_at, updated_at
- Indexes: user_id (unique), created_at
- Migration: Flyway migration script

This requires:
1. Create Flyway migration file (V{N}__add_user_preferences.sql)
2. Define schema with proper constraints
3. Test migration up/down
4. Update repository with new table

Launch: Task Database Engineer START with taskId=990e9844-i69f-75g8-e15a-88aa99884444
```

---

## Example 6: Complete Task Without Specialist (Simple Update)

### Scenario
Simple documentation update completed directly, no specialist needed.

### Input
```
User: "I updated the README file for task 11ae9955-j79g-86h9-f26b-99bb1aa95555"
```

### Skill Execution

**Step 1: Create Summary**
```
add_section(
  entityType="TASK",
  entityId="11ae9955-j79g-86h9-f26b-99bb1aa95555",
  title="Summary",
  usageDescription="Summary of documentation updates",
  content="### Completed Work\n\nUpdated README.md with authentication setup instructions.\n\n### Changes Made\n- Added \"Authentication Setup\" section\n- Documented environment variables (JWT_SECRET, JWT_EXPIRY)\n- Added code examples for login/logout\n- Included troubleshooting guide for common auth errors\n\n### Files Modified\n- `README.md` - Added 200 lines of authentication documentation\n\nNo code changes required. Documentation is ready for users.",
  contentFormat="MARKDOWN",
  ordinal=999
)
```

**Step 2: Mark Complete**
```
set_status(id="11ae9955-j79g-86h9-f26b-99bb1aa95555", status="completed")
```

**Output**:
```
Task completed. README updated with authentication setup instructions.
```

---

## Example 7: Multi-Dependency Complex Check

### Scenario
Task has multiple dependency levels. Need to check entire dependency chain.

### Input
```
User: "Check if task T6 is ready to start"
```

### Skill Execution

**Step 1: Get Immediate Dependencies**
```
get_task_dependencies(id="T6")
```

**Response**: T6 depends on T4 and T5

**Step 2: Check Each Dependency**
```
get_task(id="T4") → status: completed ✅
get_task(id="T5") → status: blocked ❌
```

**Step 3: Check Why T5 is Blocked**
```
get_task_dependencies(id="T5")
```

**Response**: T5 depends on T3

**Step 4: Check T3**
```
get_task(id="T3") → status: in_progress ⏳
```

**Output**:
```
Task T6 is BLOCKED by dependency chain.

Dependency Analysis:
T6 (Pending)
├─ T4 ✅ Completed
└─ T5 ❌ Blocked
   └─ T3 ⏳ In Progress (Backend Engineer working on it)

Blocker Root Cause:
T3 "Implement Payment Gateway Integration" is in progress
→ Blocks T5 "Create Payment Confirmation UI"
→ Blocks T6 "Add Payment History Page"

Estimated Resolution:
- T3 completion: ~3 hours (complexity 8)
- T5 can start after T3
- T6 can start after T5
- Total wait: ~6-8 hours

Recommendation: Work on unrelated tasks in parallel while waiting.
```

---

## Common Patterns Summary

### Pattern: Route Task
1. `get_task()` - Get details
2. `recommend_agent()` - Get specialist
3. Format recommendation with context

### Pattern: Complete Task
1. `get_task()` - Verify task
2. `add_section()` - Create Summary
3. `set_status()` - Mark completed

### Pattern: Check Dependencies
1. `get_task_dependencies()` - Get deps
2. `get_task()` for each incomplete - Get details
3. Analyze and report blockers

### Pattern: Block Task
1. `get_task()` - Get current state
2. `set_status(blocked)` - Mark blocked
3. `update_task()` - Document blocker

### Pattern: Resume Blocked Task
1. `get_task_dependencies()` - Verify unblocked
2. `set_status(in_progress)` - Resume work
3. Route to specialist if needed
