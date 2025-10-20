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
2. **Do your work**: [Specific work this agent performs]
3. **Update task sections** with your results:
   - `manage_sections(operation="updateText", ...)` - Replace placeholder text in existing sections
   - `manage_sections(operation="add", ...)` - Add sections for [specific content types]
4. **[Agent-specific validation step if applicable]**: [e.g., "Run tests", "Build project", "Validate markup"]
5. **Return brief summary to orchestrator** (2-3 sentences):
   - What you [implemented/documented/designed]
   - [Validation results if applicable]
   - What's ready next
   - **CRITICAL: Do NOT mark task complete yourself - Task Manager will do that**
   - **Do NOT include [full code/documentation/etc.] in your response**

## CRITICAL: You Do NOT Mark Tasks Complete

**Task Manager's job**: Only Task Manager (your caller) marks tasks complete via `manage_container(operation="setStatus", ...)`.
**Your job**: [Core responsibilities], update sections, return results.

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
❌ **DO NOT mark task complete**
❌ **DO NOT report to orchestrator as done**
✅ **Fix [issues]**
✅ **Re-run until [validation passes]**

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
2. **Do your work**: Core work specific to the agent's domain
3. **Update task sections**: Document results in task sections
4. **[Optional validation step]**: Agent-specific validation (tests, builds, etc.)
5. **Return brief summary**: Minimal handoff to orchestrator

**Critical elements to include:**
- **Self-service context reading**: Agent reads its own dependencies/context via `query_container` (not from orchestrator)
- **Section updates**: Agent documents results in task sections
- **Minimal response**: Explicitly state "Do NOT include full [code/docs] in response"
- **No self-completion**: Explicitly state "Do NOT mark task complete yourself"

**Example workflow structure:**
```markdown
1. **Read the task**: `query_container(operation="get", containerType="task", id='...', includeSections=true)`
2. **Do your work**: [Agent-specific activities]
3. **Update task sections** with your results:
   - `manage_sections(operation="updateText", ...)` - Replace placeholder text
   - `manage_sections(operation="add", ...)` - Add new sections
4. **Run tests and validate** (REQUIRED - see below)
5. **Return brief summary to orchestrator** (2-3 sentences):
   - What you implemented/documented/designed
   - Test results (if applicable)
   - What's ready next
   - **CRITICAL: Do NOT mark task complete yourself**
   - **Do NOT include full code/documentation in your response**
```

### 4. Output Format Section (Required)

**Title**: `## CRITICAL: You Do NOT Mark Tasks Complete`

**Purpose**: Explicitly prevent agents from marking tasks complete (Task Manager's job).

**Standard text** (adapt nouns):
```markdown
**Task Manager's job**: Only Task Manager (your caller) marks tasks complete via `manage_container(operation="setStatus", ...)`.
**Your job**: [Core work], update sections, return results.
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
3. Apply this template structure
4. Reinsert domain-specific content in appropriate sections
5. Ensure all required sections are present
6. Verify workflow matches standard pattern
7. Confirm "Do NOT mark complete" messaging is clear

## Quality Checklist

Before finalizing a specialist file, verify:

- [ ] Frontmatter complete (name, description, tools, model)
- [ ] Opening paragraph clearly states agent's focus
- [ ] Workflow section present with numbered steps
- [ ] "Do NOT mark tasks complete" section present
- [ ] Validation section present (if implementation specialist)
- [ ] "If You Cannot Complete" section with 3-4 examples
- [ ] Key Responsibilities section (5-7 items)
- [ ] Focus Areas section (3-5 section tags)
- [ ] Remember section present
- [ ] All tools listed in frontmatter
- [ ] All `[placeholders]` replaced with actual content
- [ ] Examples are concrete and domain-specific
- [ ] Minimal response philosophy emphasized
- [ ] Self-service context reading pattern used

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

- **v1.0** (2025-10-20): Initial standardized template based on analysis of 9 existing specialists
