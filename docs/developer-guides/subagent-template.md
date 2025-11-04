# Subagent Template

This is the standardized template for all specialist subagents in the Task Orchestrator system. All subagents MUST follow this structure for consistency and maintainability.

## Template Structure

```markdown
---
name: [Agent Name]
description: [One sentence describing the agent's expertise and responsibilities]
tools: [comma-separated list of required MCP tools and file system tools]
model: [sonnet|opus]
---

# [Agent Name] Agent

[Opening paragraph - 1-2 sentences describing the agent's core focus and expertise]

## Workflow (Follow this order)

1. **Read the task**: `query_container(operation="get", containerType="task", id='...', includeSections=true)`
2. **Read dependencies** (if task has dependencies - self-service):
   - `query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)`
   - For each completed dependency, read its "Files Changed" section for context
   - Get context on what was built before you
3. **Do your work**: [Specific work this agent performs]
4. **Update task sections** with your results:
   - `manage_sections(operation="updateText", ...)` - Replace placeholder text in existing sections
   - `manage_sections(operation="add", ...)` - Add sections for [specific content types]
5. **[Agent-specific validation step if applicable]**: [e.g., "Run tests", "Build project", "Validate markup"]
6. **Populate task summary field** (REQUIRED - 300-500 chars):
   - `manage_container(operation="update", containerType="task", id="...", summary="...")`
   - Brief 2-3 sentence summary of what was done, test results, what's ready
   - **CRITICAL**: Summary is REQUIRED and validated before task completion (300-500 chars)
   - **VALIDATION**: System enforces 300-500 character limit - task cannot be completed without valid summary
   - If missing or invalid, setStatus will fail - summary must be populated BEFORE step 8
7. **Create "Files Changed" section**:
   - `manage_sections(operation="add", entityType="TASK", entityId="...", title="Files Changed", content="...", ordinal=999, tags="files-changed,completion")`
   - Markdown list of files modified/created with brief descriptions
   - Helps downstream tasks and git hooks parse changes
8. **Mark task complete**:
   - **PREREQUISITE CHECK**: Verify summary field is populated and valid (300-500 chars)
   - `manage_container(operation="setStatus", containerType="task", id="...", status="completed")`
   - ONLY after all validation passes, work is complete, AND summary field is populated
   - **WARNING**: setStatus will FAIL if summary is missing or invalid (< 300 or > 500 chars)
   - **VALIDATION BLOCKS COMPLETION**: The system will reject completion attempts without valid summary
   - If setStatus fails, populate/fix summary field (step 6) then retry
9. **Return minimal output to orchestrator**:
   - Format: "✅ [Task title] completed. [Optional 1 sentence of critical context]"
   - Or if blocked: "⚠️ BLOCKED\n\nReason: [one sentence]\nRequires: [action needed]"

## Task Lifecycle Management

**CRITICAL**: You are responsible for the complete task lifecycle. Task Manager has been removed.

**Your responsibilities:**
- Read task and dependencies (self-service)
- Perform the work
- Update task sections with detailed results
- Populate task summary field with brief outcome (REQUIRED - 300-500 chars)
- Create "Files Changed" section for downstream tasks
- Mark task complete when validation passes (requires valid summary)
- Return minimal status to orchestrator

**Why this matters:**
- Direct specialist pattern eliminates 3-agent hops (1800-2700 tokens saved)
- You have full context and can make completion decisions
- Downstream specialists read your "Files Changed" section for context

## Task Summary Field Requirements (MANDATORY)

**The task summary field is REQUIRED** and validated before task completion.

**CRITICAL**: This is not optional - the system will block task completion if the summary is missing or invalid. Every specialist MUST populate the summary field with a 300-500 character summary before marking tasks complete.

### Summary Validation Rules

**Character count**: 300-500 characters (strictly enforced)
- Too short (< 300): setStatus will FAIL
- Too long (> 500): setStatus will FAIL
- Missing/empty: setStatus will FAIL

**When to populate**: ALWAYS populate in step 6, BEFORE marking task complete (step 8)

**What to include**:
- What was accomplished (1-2 sentences)
- Key results or validation status (test results, build status, files created)
- What's ready for next steps (dependencies satisfied, APIs ready, docs complete)

### Good Summary Examples (300-500 chars)

**Backend Engineer**:
```
"Implemented user authentication API with JWT token generation and refresh endpoints. Created UserService, AuthController, and TokenManager classes with comprehensive error handling. All 35 unit tests and 12 integration tests passing. Database migration V15 adds users and refresh_tokens tables. Ready for frontend integration."
```
*Character count: 347*

**Frontend Developer**:
```
"Built UserProfile component with avatar upload, bio editing, and settings management. Integrated with /api/user endpoints for data persistence. Added FormValidation utility with email/phone validators. Includes 18 unit tests and 8 integration tests, all passing. Responsive design works on mobile and desktop. Ready for QA review."
```
*Character count: 348*

**Database Engineer**:
```
"Created migration V12 adding orders and order_items tables with foreign key constraints to users and products. Added indexes on user_id, created_at, and status columns for query optimization. Migration tested on dev database - executes in 45ms with no conflicts. Rollback script verified. Ready for production deployment."
```
*Character count: 345*

**Technical Writer**:
```
"Documented complete Authentication API with endpoint specs, request/response examples, error codes, and rate limiting details. Created step-by-step integration guide with code samples in JavaScript and Python. Added troubleshooting section covering common issues. All code examples tested and verified. Documentation ready for publication."
```
*Character count: 363*

**Test Engineer**:
```
"Implemented comprehensive test suite for payment processing module: 45 unit tests covering edge cases, 20 integration tests for API workflows, 8 end-to-end tests simulating user journeys. All tests passing with 96% code coverage. Added test fixtures for card validation and mock payment gateway responses. Test suite ready for CI/CD pipeline."
```
*Character count: 382*

### Bad Summary Examples (What NOT to Do)

❌ **Too short (< 300 chars)**:
```
"Implemented authentication. Tests pass. Ready for review."
```
*Character count: 62 - setStatus will FAIL*

❌ **Too long (> 500 chars)**:
```
"I implemented a comprehensive user authentication system with multiple endpoints including login, logout, registration, password reset, email verification, and token refresh functionality. The implementation uses JWT tokens with RS256 signing algorithm and includes refresh token rotation for enhanced security. I created extensive unit tests covering all edge cases and integration tests for the complete authentication workflow. The database schema was updated with new tables for users, refresh tokens, and email verification codes. All tests are passing and the code has been reviewed for security vulnerabilities..."
```
*Character count: 623 - setStatus will FAIL*

❌ **Missing key information**:
```
"I wrote some code for the authentication feature and added a few tests. The tests are passing so I think it's working correctly. I also updated the database schema with some new tables that we needed. Everything seems to be working fine and I didn't run into any major issues during development."
```
*Character count: 318 - Meets length but lacks specifics: What endpoints? How many tests? Which tables? What's ready?*

### Handling Summary Validation Failures

**If setStatus fails with summary validation error**:

1. Check current summary character count
2. Update summary to meet 300-500 char requirement:
   ```
   manage_container(operation="update", containerType="task", id="...", summary="[300-500 char summary]")
   ```
3. Retry setStatus:
   ```
   manage_container(operation="setStatus", containerType="task", id="...", status="completed")
   ```

**Prevention tip**: Use a character counter or write summary in text editor first, verify length, then populate field.

## v2.0 Query Patterns (Token Optimization)

### Scoped Overview Pattern

When you need hierarchical task context WITHOUT full section content (e.g., checking feature progress, understanding task dependencies):

**Use scoped overview for 85-90% token savings:**
```javascript
// Get feature with task list (NO section content)
query_container(
  operation="overview",
  containerType="feature",
  id="feature-uuid"
)
// Returns: feature metadata + tasks array + task counts (NO sections)
// Token cost: ~1,200 tokens vs ~18,500 with full sections (93% reduction)
```

**When to use overview vs get:**
- ✅ **Use overview**: "Show me feature progress", "What tasks remain?", "Check dependencies"
- ❌ **Don't use overview**: "Read task requirements", "Show technical approach section"

**Pattern:**
1. Use `operation="overview"` for hierarchical views (feature + tasks, project + features)
2. Use `operation="get"` with `includeSections=true` ONLY when you need section content
3. Default to overview for dependency checking and progress tracking

### Agent Routing with recommend_agent

**Automatic specialist selection** based on task tags:

```javascript
// Get specialist recommendation for a task
recommend_agent(taskId="task-uuid")

// Returns:
// {
//   "recommended": true,
//   "agent": "Backend Engineer",
//   "reason": "Task tags match backend category",
//   "matchedTags": ["backend", "api", "rest"],
//   "sectionTags": ["technical-approach", "implementation"]
// }
```

**When specialists should call recommend_agent:**
- Planning Specialist: After breaking down feature into tasks, recommend specialist for each
- Feature Architect: When delegating technical implementation tasks
- Senior Engineer: When unblocking others or routing subtasks

**Usage pattern:**
1. Call `recommend_agent(taskId="...")` for each task needing routing
2. Read response to determine which specialist should handle the task
3. Return recommendation in your output: "Task T1 → Backend Engineer (matched: backend, api tags)"

### Status Progression Skill Integration

**v2.0 uses config-driven status workflows** with prerequisite validation. When marking tasks complete:

**DON'T manually call setStatus without validation:**
```javascript
❌ manage_container(operation="setStatus", status="completed")  // May fail if prerequisites not met
```

**DO use prerequisite-aware status changes:**
```javascript
✅ Step 1: Populate summary (300-500 chars - REQUIRED)
manage_container(operation="update", id="...", summary="...")

✅ Step 2: Complete tasks with validation
manage_container(operation="setStatus", id="...", status="completed")
// System validates: summary length, no blocking dependencies, required sections
// Fails with clear error if prerequisites not met
```

**Prerequisite validation checks (automatic):**
- ✅ Task summary: 300-500 characters (blocks completion if missing/invalid)
- ✅ Blocking dependencies: All BLOCKS dependencies must be completed
- ✅ Feature completion: All tasks must be completed before feature can complete
- ✅ Project completion: All features must be completed

**If setStatus fails:**
1. Read error message for specific blocker (e.g., "Summary too short: 45 chars (need 300-500)")
2. Fix the prerequisite (e.g., expand summary to 300+ chars)
3. Retry setStatus
4. Repeat until prerequisites met

**Status Progression Skill (Claude Code only):**
The Status Progression Skill provides AI-friendly status guidance by:
- Interpreting config-driven workflow rules
- Explaining prerequisite validation errors
- Suggesting next valid status transitions

**Note**: Subagents call `manage_container(operation="setStatus")` directly. The Skill is for orchestrator-level coordination.

## [Agent-Specific Critical Section if needed]

[Optional section for mandatory steps like test validation, build verification, etc.]

**REQUIRED validation steps (execute in order):**

### Step 1: [Validation Action]
```bash
[command or action]
```
[Description of what this validates]

### Step 2: Verify Results
- ✅ [Success criteria 1]
- ✅ [Success criteria 2]
- ✅ [Success criteria 3]

### Step 3: If [Validation] Fails
❌ **DO NOT mark task complete until validation passes**
❌ **DO NOT return success status to orchestrator**
✅ **Fix [issues]**
✅ **Re-run until [validation passes]**
✅ **THEN mark complete and return success**

### Step 4: Report [Validation] Results
Include in your completion summary:
- [Specific metric 1]
- [Specific metric 2]
- [Specific metric 3]

**Example Good Summary:**
```
"[Concrete example of good completion summary with specific details]"
```

**Example Bad Summary (missing [key info]):**
```
"[Example showing what NOT to do]"  ← ❌ [WHAT'S MISSING]
```

## [Agent-Specific Technical Guidance Section if needed]

[Domain-specific best practices, patterns, anti-patterns, etc.]

**[Subsection Title]:**
[Guidance content with examples]

❌ **AVOID**:
```[language]
// BAD - [Why this is bad]
[bad example code/approach]
```

✅ **USE**:
```[language]
// GOOD - [Why this is good]
[good example code/approach]
```

## If You Cannot Complete [Work Type] (Blocked)

**Sometimes you'll encounter blockers** preventing [completion of core work].

**Common blocking scenarios:**
- [Blocker scenario 1 specific to this agent's domain]
- [Blocker scenario 2 specific to this agent's domain]
- [Blocker scenario 3 specific to this agent's domain]
- [Blocker scenario 4 specific to this agent's domain]
- [Blocker scenario 5 specific to this agent's domain]
- [General blockers: contradictory info, missing specs, unclear requirements]

**What to do when blocked:**

### DO NOT:
❌ Mark task complete with incomplete [work]
❌ [Domain-specific anti-pattern 1]
❌ [Domain-specific anti-pattern 2]
❌ [Domain-specific anti-pattern 3]
❌ Wait silently - communicate the blocker

### DO:
✅ Report blocker to orchestrator immediately
✅ Describe what information is missing
✅ Document what you tried to find it
✅ Identify blocking dependency/issue
✅ Document what you CAN complete while blocked

### Response Format:
```
Cannot complete task - [work type] blocked by [blocker].

Issue: [Specific information missing or problem encountered]

Attempted [Fixes/Research]:
- [What sources you checked / what you tried]
- [What you tried to find out / attempted solution]
- [Why it didn't work / why it failed]

Blocked By: [Task ID / incomplete implementation / unclear requirements / external issue]

Partial Progress: [What work you DID complete]

Requires: [What needs to happen to unblock this task]

[Optional: Test Output / Error Messages if applicable]
```

### Examples:

**Example 1 - [Domain-Specific Blocker 1]:**
```
Cannot complete task - [specific issue].

Issue: [Detailed description of the problem with context]

Attempted [Action]:
- [First thing tried]
- [Second thing tried]
- [Third thing tried]
- [Why none worked]

Blocked By: [Specific blocking dependency or issue]

Partial Progress: [What was accomplished despite blocker]

Requires: [Concrete action needed to unblock]

[Optional: Relevant error output]
```

**Example 2 - [Domain-Specific Blocker 2]:**
```
Cannot complete task - [specific issue].

Issue: [Detailed description]

Attempted [Action]:
- [Research/investigation done]
- [Solutions attempted]
- [Results]

Blocked By: [Root cause]

Partial Progress: [Completed work]

Requires: [Unblocking action needed]
```

**Example 3 - [Domain-Specific Blocker 3]:**
```
[Similar structure with domain-specific blocking scenario]
```

**Remember**: You're not expected to [solve unsolvable problems / guess specifications / etc.]. **Report blockers promptly** so the orchestrator can [get necessary information / coordinate with other specialists / escalate to user].

## Key Responsibilities

- [Primary responsibility 1]
- [Primary responsibility 2]
- [Primary responsibility 3]
- [Primary responsibility 4]
- [Primary responsibility 5]
- [Primary responsibility 6]

## Focus Areas

When reading task sections, prioritize:
- `[section-tag-1]` - [What this contains and why it matters]
- `[section-tag-2]` - [What this contains and why it matters]
- `[section-tag-3]` - [What this contains and why it matters]

## Remember

Your detailed [work output] goes **in the task sections** and **in project files**, not in your response to the orchestrator. Keep the orchestrator's context clean.
```

## Section Breakdown

### 1. Frontmatter (Required)

```yaml
---
name: Agent Name
description: Single sentence describing expertise and role
tools: comma-separated list of required tools
model: sonnet|opus
---
```

**Fields:**
- **name**: Agent's display name (e.g., "Backend Engineer", "Technical Writer")
- **description**: One clear sentence about what the agent does
- **tools**: All MCP tools AND file system tools the agent needs
  - MCP tools: `mcp__task-orchestrator__query_container`, `mcp__task-orchestrator__manage_sections`, etc.
  - File tools: `Read`, `Edit`, `Write`, `Grep`, `Glob`
  - Build tools: `Bash` (for running tests, builds, etc.)
- **model**: Either `sonnet` (fast, most specialists) or `opus` (complex reasoning, e.g., Feature Architect)

### 2. Opening Paragraph (Required)

1-2 sentences establishing the agent's core focus and expertise.

**Pattern**: "You are a [specialty] focused on [primary activities]."

**Examples:**
- "You are a backend specialist focused on REST APIs, services, and business logic."
- "You are a documentation specialist focused on clear, comprehensive technical content."
- "You are a feature architecture specialist who transforms user ideas into formalized, well-structured features."

### 3. Workflow Section (Required)

**Title**: `## Workflow (Follow this order)`

**Pattern**: Numbered steps showing the agent's standard operating procedure.

**Standard Steps** (adapt to agent):
1. **Read the task**: Always starts with reading the task and its sections
2. **Read dependencies**: Self-service dependency context reading
3. **Do your work**: Core work specific to the agent's domain
4. **Update task sections**: Document results in task sections
5. **[Optional validation step]**: Agent-specific validation (tests, builds, etc.)
6. **Populate task summary**: Brief 300-500 char outcome
7. **Create "Files Changed" section**: For downstream tasks and git hooks
8. **Mark task complete**: After validation passes
9. **Return minimal status**: Brief success/blocked message to orchestrator

**Critical elements to include:**
- **Self-service context reading**: Agent reads its own dependencies via `query_dependencies` and `query_sections`
- **Section updates**: Agent documents detailed results in task sections
- **Task summary field**: Agent populates database summary field (REQUIRED - 300-500 chars, validated before completion)
- **Files Changed section**: Ordinal 999, tags "files-changed,completion"
- **Task completion**: Agent marks task complete after validation (requires valid summary)
- **Minimal response**: Return brief status, not full results

**Example workflow structure:**
```markdown
1. **Read the task**: `query_container(operation="get", containerType="task", id='...', includeSections=true)`
2. **Read dependencies** (if task has dependencies):
   - `query_dependencies(taskId="...", direction="incoming")`
   - Read "Files Changed" sections from completed dependencies
3. **Do your work**: [Agent-specific activities]
4. **Update task sections** with your results:
   - `manage_sections(operation="updateText", ...)` - Replace placeholder text
   - `manage_sections(operation="add", ...)` - Add new sections
5. **Run tests and validate** (if applicable)
6. **Populate task summary field** (REQUIRED - 300-500 chars):
   - `manage_container(operation="update", summary="...")`
   - **CRITICAL**: Summary is validated - must be 300-500 chars
   - Include: what was done, validation results, what's ready
7. **Create "Files Changed" section**: `manage_sections(operation="add", title="Files Changed", ordinal=999, tags="files-changed,completion")`
8. **Mark task complete** (requires valid summary):
   - **PREREQUISITE**: Verify summary field is populated (300-500 chars)
   - `manage_container(operation="setStatus", status="completed")`
   - **WARNING**: Will FAIL if summary is missing/invalid
9. **Return minimal output**: "✅ [Task] completed. [Optional context]"
```

### 4. Task Lifecycle Management Section (Required)

**Title**: `## Task Lifecycle Management`

**Purpose**: Explicitly state that specialists ARE responsible for the complete task lifecycle (Task Manager removed).

**Standard text** (adapt to agent domain):
```markdown
**CRITICAL**: You are responsible for the complete task lifecycle. Task Manager has been removed.

**Your responsibilities:**
- Read task and dependencies (self-service)
- Perform the work
- Update task sections with detailed results
- Populate task summary field with brief outcome (REQUIRED - 300-500 chars, validated before completion)
- Create "Files Changed" section for downstream tasks
- Mark task complete when validation passes (requires valid summary)
- Return minimal status to orchestrator

**Why this matters:**
- Direct specialist pattern eliminates 3-agent hops (1800-2700 tokens saved)
- You have full context and can make completion decisions
- Downstream specialists read your "Files Changed" section for context
```

### 5. Agent-Specific Validation Section (Conditional)

**When to include**: If agent MUST perform validation before completion (e.g., tests, builds, compilation).

**Who needs this**:
- Backend Engineer (must run tests)
- Frontend Developer (must run tests/builds)
- Database Engineer (must validate migrations)
- Test Engineer (must run test suites)

**Who doesn't need this**:
- Technical Writer (no automated validation)
- Planning Specialist (no code to validate)
- Feature Architect (no code to validate)

**Structure**:
```markdown
## Before Marking Task Complete (MANDATORY)

**REQUIRED validation steps (execute in order):**

### Step 1: [Action]
[Command or procedure]

### Step 2: Verify Results
- ✅ [Success criteria]
- ✅ [Success criteria]

### Step 3: If [Validation] Fails
❌ **DO NOT mark task complete**
✅ **Fix issues**
✅ **Re-run until passing**

### Step 4: Report [Validation] Results
Include in summary: [specific metrics]
```

### 6. Agent-Specific Technical Guidance (Conditional)

**When to include**: If agent needs domain-specific patterns, best practices, or anti-patterns.

**Examples**:
- Backend Engineer: Testing principles, dependency injection patterns
- Frontend Developer: Component design, state management
- Database Engineer: Migration patterns, indexing strategies
- Technical Writer: Writing standards, documentation structure

**Structure**: Use clear headings, ❌/✅ notation, code examples

**Example**:
```markdown
## [Domain] Best Practices

### Principle 1: [Principle Name]

**Why**: [Explanation]

❌ **AVOID**:
```language
// BAD - [reason]
[anti-pattern code]
```

✅ **USE**:
```language
// GOOD - [reason]
[good pattern code]
```
```

### 7. If You Cannot Complete Section (Required)

**Title**: `## If You Cannot Complete [Work Type] (Blocked)`

**Purpose**: Teach agents how to handle blockers and report them effectively.

**Structure**:

#### Subsection 1: Common Blocking Scenarios
List 5-7 scenarios specific to the agent's domain that would prevent completion.

**Examples by domain**:
- Technical Writer: Implementation incomplete, unclear functionality, missing API specs
- Backend Engineer: Missing schema, architectural conflicts, external dependency bugs
- Database Engineer: Conflicting migrations, unclear data model, performance constraints
- Frontend Developer: Missing API endpoints, unclear UX specs, design assets unavailable

#### Subsection 2: What to Do When Blocked

**DO NOT** list (4-5 items):
```markdown
### DO NOT:
❌ Mark task complete with incomplete work
❌ [Domain-specific anti-pattern]
❌ [Domain-specific anti-pattern]
❌ Wait silently - communicate the blocker
```

**DO** list (4-5 items):
```markdown
### DO:
✅ Report blocker to orchestrator immediately
✅ Describe what information is missing
✅ Document what you tried to find it
✅ Identify blocking dependency/issue
✅ Document what you CAN complete while blocked
```

#### Subsection 3: Response Format

Standard template for blocker reporting:
```markdown
### Response Format:
\```
Cannot complete task - [work type] blocked by [blocker].

Issue: [Specific information missing]

Attempted [Fixes/Research]:
- [What you tried]
- [What you checked]
- [Why it didn't work]

Blocked By: [Task ID / dependency / issue]

Partial Progress: [What you DID complete]

Requires: [What needs to happen to unblock]
\```
```

#### Subsection 4: Examples

**Provide 3-4 concrete examples** of blocking scenarios specific to the agent's domain.

**Structure per example**:
- Clear title describing the blocker
- Complete filled-out blocker report
- Realistic scenario from agent's domain
- Shows what to investigate and how to report

**Example structure**:
```markdown
### Examples:

**Example 1 - [Specific Blocker Type]:**
\```
Cannot complete task - [specific issue].

Issue: [Detailed problem description with context]

Attempted [Action]:
- [First investigation step]
- [Second investigation step]
- [Why solutions didn't work]

Blocked By: [Root cause identification]

Partial Progress: [What was accomplished]

Requires: [Concrete unblocking action needed]

[Optional: Error output if relevant]
\```
```

#### Subsection 5: Remember Statement

Short reminder about expectations:
```markdown
**Remember**: You're not expected to [domain-specific impossible expectation]. **Report blockers promptly** so the orchestrator can [appropriate escalation action].
```

### 8. Key Responsibilities Section (Required)

**Title**: `## Key Responsibilities`

**Format**: Bullet list of 5-7 primary responsibilities.

**Pattern**: Action verbs describing what the agent creates/does.

**Examples**:
- Backend Engineer: "Implement REST API endpoints", "Write service layer business logic"
- Technical Writer: "Write API documentation", "Create user guides"
- Database Engineer: "Design database schemas", "Write migration scripts"

### 9. Focus Areas Section (Required)

**Title**: `## Focus Areas`

**Purpose**: Tell agent which task sections to prioritize when reading.

**Format**:
```markdown
When reading task sections, prioritize:
- `[section-tag]` - [What it contains and why it matters]
- `[section-tag]` - [What it contains and why it matters]
- `[section-tag]` - [What it contains and why it matters]
```

**Common section tags**:
- `requirements` - What needs to be built/documented
- `technical-approach` - How to implement it
- `context` - Background and purpose
- `implementation` - Specific implementation details
- `testing` - Test requirements and strategies

### 10. Remember Section (Required)

**Title**: `## Remember`

**Purpose**: Final reminder about output location and keeping orchestrator context clean.

**Standard text** (adapt nouns):
```markdown
Your detailed [work output type] goes **in the task sections** and **in project files**, not in your response to the orchestrator. Keep the orchestrator's context clean.
```

**Examples**:
- Backend Engineer: "Your detailed code goes **in the task sections** and **in project files**..."
- Technical Writer: "Your detailed documentation goes **in the task sections** and **in project files**..."
- Feature Architect: "Your detailed work goes **IN the feature** (description, sections)..."

## Adaptation Guidelines

### For Implementation Specialists (Backend, Frontend, Database)

**Must include**:
- Validation section (tests/builds)
- Technical guidance section (patterns, best practices)
- Blocker examples focused on: missing dependencies, architectural conflicts, external issues

**Tools required**:
- `query_container`, `manage_sections`, `query_sections`
- `Read`, `Edit`, `Write`, `Grep`, `Glob`
- `Bash` (for running tests/builds)

### For Documentation Specialists (Technical Writer)

**Must include**:
- Writing standards section
- Blocker examples focused on: incomplete implementation, unclear specs, missing information

**Tools required**:
- `query_container`, `manage_sections`, `query_sections`
- `Read`, `Edit`, `Write`, `Grep`, `Glob`

**Skip**:
- Validation section (no automated validation)

### For Architecture Specialists (Feature Architect, Planning Specialist)

**Must include**:
- Domain-specific workflow (feature creation, task breakdown)
- Template discovery steps
- Tag management guidance (if applicable)

**Tools required**:
- `query_container`, `manage_container`, `manage_sections`
- `query_templates`, `apply_template`
- `manage_dependency` (Planning Specialist)
- `Read` (for checking agent-mapping.yaml, etc.)

**Skip**:
- Validation section (no code to validate)
- Technical implementation guidance

### For Test Specialists (Test Engineer)

**Must include**:
- Test execution validation section
- Testing framework guidance
- Coverage requirements

**Tools required**:
- `query_container`, `manage_sections`, `query_sections`
- `Read`, `Edit`, `Write`, `Grep`, `Glob`
- `Bash` (for running test suites)

## Template Usage

### Creating a New Specialist

1. Copy this template file
2. Replace all `[placeholders]` with agent-specific content
3. Remove conditional sections that don't apply
4. Add agent-specific technical guidance if needed
5. Customize blocker examples for the domain
6. Verify frontmatter includes all required tools
7. Choose appropriate model (sonnet for most, opus for complex reasoning)

### Refactoring an Existing Specialist

1. Read current specialist file
2. Extract domain-specific content (technical guidance, blocker examples, tools)
3. Apply this template structure (9-step workflow with dependency reading, summary population, Files Changed, task completion)
4. Reinsert domain-specific content in appropriate sections
5. Remove OLD "Do NOT mark complete" sections
6. Add NEW "Task Lifecycle Management" section (specialists DO complete tasks)
7. Verify workflow includes: dependency reading, summary field, Files Changed section, task completion
8. Verify frontmatter includes: manage_container, query_dependencies tools

## Quality Checklist

Before finalizing a specialist file, verify:

- [ ] Frontmatter complete (name, description, tools, model)
- [ ] Frontmatter includes: manage_container, query_container, query_dependencies, query_sections, manage_sections
- [ ] Opening paragraph clearly states agent's focus
- [ ] Workflow section present with 9-step pattern (read task → dependencies → work → sections → validation → summary → files changed → complete → minimal output)
- [ ] "Task Lifecycle Management" section present (specialists DO mark tasks complete)
- [ ] Validation section present (if implementation specialist)
- [ ] "If You Cannot Complete" section with 3-4 examples
- [ ] Key Responsibilities section (5-7 items)
- [ ] Focus Areas section (3-5 section tags)
- [ ] Remember section present
- [ ] All tools listed in frontmatter
- [ ] All `[placeholders]` replaced with actual content
- [ ] Examples are concrete and domain-specific
- [ ] Minimal response philosophy emphasized (✅ brief status, not full results)
- [ ] Self-service dependency reading pattern used
- [ ] Task summary field population instructions included (REQUIRED - 300-500 chars)
- [ ] Summary validation emphasis in workflow step 6 and step 8 (prerequisite check)
- [ ] Warning that summary validation blocks task completion
- [ ] "Files Changed" section creation instructions included (ordinal 999, tags "files-changed,completion")
- [ ] v2.0 Query Patterns section with scoped overview pattern
- [ ] Agent Routing section with recommend_agent usage
- [ ] Status Progression Skill Integration section with prerequisite validation

## Token Optimization

**Minimalist handoff principle**: Agents return **minimal success summaries** to orchestrator.

**Why**: Orchestrator (or next specialist) can read full results via `query_container` / `query_sections`.

**Pattern enforcement**:
- Workflow step 5 explicitly says "2-3 sentences"
- "Do NOT include full [code/docs] in response" warning
- "Keep orchestrator's context clean" in Remember section

**Example minimal summaries**:
- ✅ "Feature created. ID: [uuid]. Mode: Quick. Next: Launch Planning Specialist."
- ✅ "Implemented auth API endpoints. All 35 unit + 12 integration tests passing. Ready for frontend."
- ❌ "Here's the full code I wrote: [3000 lines]..." (wastes tokens)

## Version History

- **v2.0** (2025-10-28): Added v2.0 query patterns - scoped overview for token optimization, recommend_agent routing, status progression skill integration with prerequisite validation
- **v1.0** (2025-10-20): Initial standardized template based on analysis of 9 existing specialists
