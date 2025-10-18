# Task Management Skill - Specialist Routing Guide

This guide explains how the Task Management Skill routes tasks to appropriate specialist agents based on task characteristics.

## Routing Decision Process

### Step 1: Analyze Task Tags
Tags are the primary routing signal. Common tag patterns:

| Tag Pattern | Specialist | Reasoning |
|-------------|------------|-----------|
| `backend`, `api`, `server` | Backend Engineer | Server-side implementation |
| `frontend`, `ui`, `react`, `vue` | Frontend Engineer | Client-side UI work |
| `database`, `schema`, `migration`, `sql` | Database Engineer | Database changes |
| `testing`, `test`, `qa`, `integration-test` | Test Engineer | Test implementation |
| `documentation`, `docs`, `readme` | Technical Writer | Documentation work |
| `planning`, `breakdown`, `design` | Planning Specialist | Task decomposition |
| `bug`, `fix`, `error`, `crash` | Bug Triage Specialist | Bug investigation |

### Step 2: Analyze Task Title
If tags are ambiguous, title provides context:

| Title Pattern | Specialist |
|---------------|------------|
| "Implement ... API" | Backend Engineer |
| "Create ... Component" | Frontend Engineer |
| "Add ... Table" | Database Engineer |
| "Write tests for ..." | Test Engineer |
| "Document ..." | Technical Writer |
| "Break down ..." | Planning Specialist |
| "Fix ..." | Bug Triage Specialist |

### Step 3: Check Task Sections
Review section titles and content:
- **Technical Approach**: Mentions specific frameworks/technologies
- **Requirements**: Describes what needs to be built
- **Testing Strategy**: Indicates test implementation needed

### Step 4: Consider Complexity
High complexity (8-10) may require Planning Specialist first to break down into subtasks.

## Specialist Profiles

### Backend Engineer
**Expertise**:
- REST API implementation
- Server-side business logic
- Authentication/authorization
- Data processing
- Background jobs
- Third-party integrations

**Technologies**:
- Kotlin, Java, Python, Node.js
- Ktor, Spring Boot, Express
- JWT, OAuth
- Message queues (Kafka, RabbitMQ)

**Triggers**:
- Tags: `backend`, `api`, `server`, `authentication`, `integration`
- Title contains: "API", "endpoint", "service", "authentication"
- Sections mention: Ktor, Spring, REST, GraphQL

### Frontend Engineer
**Expertise**:
- UI component implementation
- Client-side state management
- Form handling and validation
- Responsive design
- Browser APIs
- Client-side routing

**Technologies**:
- React, Vue, Svelte, Angular
- TypeScript, JavaScript
- CSS, SCSS, Tailwind
- Webpack, Vite

**Triggers**:
- Tags: `frontend`, `ui`, `react`, `vue`, `component`
- Title contains: "component", "page", "UI", "form"
- Sections mention: React, Vue, CSS, HTML, browser

### Database Engineer
**Expertise**:
- Schema design
- Migration scripts
- Query optimization
- Indexing strategy
- Data modeling
- Database migrations (Flyway)

**Technologies**:
- PostgreSQL, MySQL, SQLite
- Exposed ORM, Hibernate
- Flyway, Liquibase
- SQL optimization

**Triggers**:
- Tags: `database`, `schema`, `migration`, `sql`, `data-model`
- Title contains: "table", "schema", "migration", "database"
- Sections mention: SQL, Flyway, Exposed, schema, indexes

### Test Engineer
**Expertise**:
- Unit test implementation
- Integration testing
- E2E testing
- Test data management
- Mocking and stubbing
- Test coverage analysis

**Technologies**:
- JUnit, MockK, Kotlin Test
- Selenium, Playwright, Cypress
- Test containers
- CI/CD test integration

**Triggers**:
- Tags: `testing`, `test`, `qa`, `unit-test`, `integration-test`
- Title contains: "test", "testing", "QA", "coverage"
- Sections: "Testing Strategy" section exists

### Technical Writer
**Expertise**:
- API documentation
- User guides
- Code comments
- README files
- Architecture documentation
- Tutorial creation

**Technologies**:
- Markdown, AsciiDoc
- OpenAPI/Swagger
- KDoc, JSDoc
- Documentation generators

**Triggers**:
- Tags: `documentation`, `docs`, `readme`, `user-guide`
- Title contains: "document", "README", "guide", "docs"
- Sections: "Documentation Plan" section exists

### Planning Specialist
**Expertise**:
- Feature breakdown
- Task decomposition
- Dependency analysis
- Scope definition
- Acceptance criteria
- Technical planning

**Technologies**:
- Not technology-specific
- Focuses on planning and coordination

**Triggers**:
- Tags: `planning`, `breakdown`, `design`, `architecture`
- Title contains: "break down", "plan", "design", "scope"
- Complexity: 8-10 (large tasks needing decomposition)

### Bug Triage Specialist
**Expertise**:
- Bug investigation
- Root cause analysis
- Reproduction steps
- Error log analysis
- Fix verification
- Regression testing

**Technologies**:
- Debugging tools
- Log analysis
- Stack trace interpretation
- Browser dev tools

**Triggers**:
- Tags: `bug`, `fix`, `error`, `crash`, `issue`
- Title contains: "fix", "bug", "error", "crash", "doesn't work"
- User reports: "broken", "not working", "failing"

## Routing Examples

### Example 1: Backend API Task

**Task**:
```
Title: "Implement User Registration API"
Tags: ["backend", "api", "authentication"]
Complexity: 7
```

**Analysis**:
- Tags: `backend`, `api`, `authentication` → Strong backend signal
- Title: "Implement ... API" → Backend pattern
- Complexity: 7 → Moderate, no breakdown needed

**Routing Decision**: **Backend Engineer**

**Recommendation Format**:
```
Recommend Backend Engineer for this task.

Task: Implement User Registration API
Complexity: 7/10

Routing Rationale:
- Backend API implementation (clear backend scope)
- Authentication logic (server-side security)
- Database integration for user storage

[Include task context from sections]

Launch: Task Backend Engineer START with taskId=...
```

---

### Example 2: Frontend UI Task

**Task**:
```
Title: "Create User Profile Dashboard Component"
Tags: ["frontend", "react", "ui"]
Complexity: 6
```

**Analysis**:
- Tags: `frontend`, `react`, `ui` → Strong frontend signal
- Title: "Create ... Component" → Frontend pattern
- Complexity: 6 → Moderate, no breakdown needed

**Routing Decision**: **Frontend Engineer**

---

### Example 3: Database Migration Task

**Task**:
```
Title: "Add Orders Table with Foreign Keys"
Tags: ["database", "schema", "migration"]
Complexity: 5
```

**Analysis**:
- Tags: `database`, `schema`, `migration` → Strong database signal
- Title: "Add ... Table" → Database pattern
- Complexity: 5 → Straightforward schema change

**Routing Decision**: **Database Engineer**

---

### Example 4: Multi-Discipline Task

**Task**:
```
Title: "Implement Payment Processing Feature"
Tags: ["backend", "frontend", "database", "testing"]
Complexity: 9
```

**Analysis**:
- Tags: Multiple disciplines → Complex feature
- Complexity: 9 → High complexity, needs breakdown
- Scope: Too broad for single specialist

**Routing Decision**: **Planning Specialist**

**Recommendation Format**:
```
Recommend Planning Specialist for this task.

Task: Implement Payment Processing Feature
Complexity: 9/10

Routing Rationale:
- Multi-discipline scope (backend + frontend + database + testing)
- High complexity requires breakdown into subtasks
- Each subtask can be routed to appropriate specialist

Planning Specialist should:
1. Break down into subtasks (API, UI, schema, tests)
2. Define dependencies between subtasks
3. Create acceptance criteria for each
4. Return subtask list for individual specialist routing

Launch: Task Planning Specialist START with taskId=...
```

---

### Example 5: Ambiguous Task

**Task**:
```
Title: "Improve User Experience"
Tags: ["enhancement"]
Complexity: 6
```

**Analysis**:
- Tags: Generic, no clear signal
- Title: Vague scope
- No sections provided

**Routing Decision**: **Planning Specialist** (needs clarification)

**Recommendation Format**:
```
Recommend Planning Specialist for this task.

Task: Improve User Experience
Complexity: 6/10

Routing Rationale:
- Scope is unclear ("Improve UX" is too broad)
- No specific technical domain indicated
- Needs requirements clarification and breakdown

Planning Specialist should:
1. Interview stakeholders about specific UX issues
2. Identify concrete improvements (performance, UI polish, accessibility)
3. Break down into actionable, specific tasks
4. Route resulting tasks to appropriate specialists

Launch: Task Planning Specialist START with taskId=...
```

---

### Example 6: Documentation Task

**Task**:
```
Title: "Document Payment API Endpoints"
Tags: ["documentation", "api", "backend"]
Complexity: 4
```

**Analysis**:
- Primary tag: `documentation` → Documentation work
- Secondary tags: `api`, `backend` → Context, not implementation
- Title: "Document ..." → Documentation pattern
- Complexity: 4 → Straightforward documentation

**Routing Decision**: **Technical Writer**

**Note**: Even though `api` and `backend` tags exist, `documentation` tag takes precedence. Technical Writer will READ existing backend code to WRITE documentation, not implement backend changes.

---

### Example 7: Testing Task

**Task**:
```
Title: "Write Integration Tests for Auth API"
Tags: ["testing", "integration-test", "api"]
Complexity: 5
```

**Analysis**:
- Primary tag: `testing`, `integration-test` → Testing work
- Secondary tag: `api` → What to test (not implementation)
- Title: "Write ... Tests" → Testing pattern
- Complexity: 5 → Moderate testing scope

**Routing Decision**: **Test Engineer**

---

## Multi-Step Routing Scenarios

### Scenario 1: Large Feature → Planning → Specialists

**Step 1**: Route large feature to Planning Specialist
```
Feature: "User Management System"
Complexity: 10
Tags: ["feature", "backend", "frontend", "database"]

→ Planning Specialist breaks down into tasks
```

**Step 2**: Route each subtask to specialist
```
Task 1: "Create users table schema"
Tags: ["database", "schema"]
→ Database Engineer

Task 2: "Implement user CRUD API"
Tags: ["backend", "api"]
→ Backend Engineer

Task 3: "Create user management UI"
Tags: ["frontend", "react"]
→ Frontend Engineer

Task 4: "Write integration tests"
Tags: ["testing", "integration-test"]
→ Test Engineer
```

### Scenario 2: Bug → Triage → Fix Specialist

**Step 1**: Route bug report to Bug Triage Specialist
```
Task: "Fix login crash on mobile"
Tags: ["bug", "frontend", "mobile"]

→ Bug Triage Specialist investigates
```

**Step 2**: Route fix to domain specialist
```
Bug Triage Result: "Crash caused by null state in LoginForm component"

→ Create fix task with tag ["frontend", "react", "bug-fix"]
→ Route to Frontend Engineer
```

## Edge Cases and Special Handling

### Edge Case 1: No Tags
**Fallback**: Use title analysis and section content
**If still ambiguous**: Route to Planning Specialist for clarification

### Edge Case 2: Conflicting Tags
**Example**: `["backend", "frontend"]`
**Resolution**: Check which is primary based on title and sections
**If equal priority**: Route to Planning Specialist to split into separate tasks

### Edge Case 3: Unknown Tag
**Example**: `["blockchain", "smart-contract"]`
**Fallback**: Check if matches known specialist
**If no match**: Report to user that specialist doesn't exist, suggest creating custom agent

### Edge Case 4: Completed Dependencies
**Before routing**: Always check `get_task_dependencies()`
**If incomplete dependencies**: Report blocker, don't route yet
**If completed**: Proceed with routing

## Best Practices for Routing

### 1. Always Check Dependencies First
```
Before: recommend_agent(taskId)
First: get_task_dependencies(taskId)

If dependencies incomplete:
→ Report blocker
→ Don't route to specialist yet
```

### 2. Include Relevant Context
When routing to specialist, include:
- Task title and ID
- Complexity rating
- Key requirements from sections
- Technical approach if defined
- Dependencies status (all completed)

### 3. Use Specific Launch Instructions
```
Good: "Launch: Task Backend Engineer START with taskId=550e8400-..."
Bad: "Use Backend Engineer"
```

### 4. Explain Routing Rationale
Help orchestrator understand why this specialist was chosen:
```
Routing Rationale:
- Backend API implementation (server-side logic required)
- Database integration (user storage and queries)
- Authentication logic (security-critical, needs backend expertise)
```

### 5. Handle Multi-Specialist Tasks
If task requires multiple specialists:
```
Option A: Route to Planning Specialist to break down
Option B: Route to primary specialist, note cross-specialist coordination needed
```

## Integration with recommend_agent Tool

The `recommend_agent` tool provides algorithmic recommendations. Task Management Skill should:

1. **Call recommend_agent** to get baseline recommendation
2. **Validate** recommendation against task context
3. **Override** if task context suggests different specialist
4. **Document** reasoning if overriding algorithm

**Example**:
```
recommend_agent(taskId) → "Backend Engineer"

Task context:
- Title: "Document Authentication API"
- Tags: ["documentation", "api", "backend"]

Override Decision: Technical Writer
Reasoning: Primary goal is documentation (not implementation).
Backend Engineer would implement, but task is about documenting existing API.
```

## Conclusion

Effective routing requires:
1. **Accurate tag analysis** (primary signal)
2. **Title pattern matching** (secondary signal)
3. **Section content review** (context and details)
4. **Complexity assessment** (breakdown vs direct implementation)
5. **Dependency verification** (ready to start)

When in doubt, route to Planning Specialist for clarification and breakdown.
