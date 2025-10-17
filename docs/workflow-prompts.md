---
layout: default
title: Workflow Prompts
---

# Workflow Prompts

Workflow prompts provide structured, step-by-step guidance for complex project management scenarios. **Workflow prompts guide the PROCESS** of accomplishing work, while templates structure the WORK itself. They complement AI's autonomous pattern application by offering explicit workflows when needed.

**Available on all MCP clients**: Workflow prompts work with Claude Desktop, Claude Code, Cursor, Windsurf, and any MCP-compatible AI assistant.

## Table of Contents

- [Overview](#overview)
- [Dual Workflow Model](#dual-workflow-model)
- [Available Workflow Prompts](#available-workflow-prompts)
- [When to Use Workflow Prompts](#when-to-use-workflow-prompts)
- [Usage Patterns](#usage-patterns)
- [Integration with Templates](#integration-with-templates)

---

## Overview

Workflow prompts are AI-invokable guides that provide comprehensive instructions for complex scenarios. Unlike autonomous pattern recognition (where Claude understands your intent naturally), workflow prompts offer explicit, step-by-step processes.

### What They Provide

- **Structured Processes**: Step-by-step instructions for complex workflows (the HOW)
- **Quality Validation**: Built-in checkpoints and validation steps
- **Template Integration**: Automatic template selection and application (the WHAT)
- **Best Practices**: Proven patterns for common scenarios
- **Universal Availability**: Works with ANY MCP client (not Claude Code specific)

### Templates vs. Workflow Prompts

**Templates structure the WORK** (WHAT needs to be documented):
- Create sections: Requirements, Technical Approach, Testing Strategy
- Define what information must be captured
- Provide consistent documentation structure
- Work with both direct execution and sub-agent orchestration

**Workflow Prompts guide the PROCESS** (HOW to accomplish the work):
- Provide step-by-step procedural guidance
- Integrate MCP tool calls systematically
- Offer quality validation checkpoints
- Guide from start to completion

**Example**:
```
Template (Technical Approach):
→ Creates "Technical Approach" section
→ Defines: Architecture, Technology Stack, Design Patterns

Workflow Prompt (implementation_workflow):
→ Step 1: Check current state
→ Step 2: Auto-detect git and apply git workflow templates
→ Step 3: Read Technical Approach section for context
→ Step 4: Implement following the architecture
→ Step 5: Validate implementation
→ Step 6: Mark task complete
```

### What Makes Them Different

Workflow prompts are **explicit guidance** - you invoke them by name and receive detailed instructions. This contrasts with **autonomous patterns** where Claude recognizes your intent and applies appropriate workflows automatically.

> **Learn More**: See [AI Guidelines - Dual Workflow Model](ai-guidelines#dual-workflow-model) for understanding when to use autonomous vs. explicit workflows.

---

## Dual Workflow Model

Task Orchestrator supports two complementary modes of operation:

### Mode 1: Autonomous Pattern Application

Claude recognizes your intent from natural language and applies appropriate patterns automatically.

**Example**:
```
User: "Help me plan this authentication feature"

Claude autonomously:
1. Runs get_overview to understand context
2. Discovers templates via list_templates
3. Creates feature with appropriate templates
4. Confirms creation and suggests next steps
```

**When to use**: Most everyday tasks - faster, more natural conversation.

### Mode 2: Explicit Workflow Invocation

You invoke a specific workflow prompt by name for guaranteed comprehensive guidance.

**Example**:
```
User: "Walk me through creating a new project using the project setup workflow"

Claude explicitly:
1. Follows project_setup_workflow prompt
2. Walks through all 8 steps systematically
3. Validates each step before proceeding
4. Ensures nothing is missed
```

**When to use**: Complex scenarios, learning, ensuring comprehensive coverage.

> **See**: [AI Guidelines - Dual Workflow Model](ai-guidelines#dual-workflow-model) for detailed explanation and decision guidance.

---

## Available Workflow Prompts

### `initialize_task_orchestrator`

**Purpose**: Initialize AI agent with Task Orchestrator guidelines and patterns

**When to Use**:
- First time using Task Orchestrator with an AI agent
- AI seems unfamiliar with Task Orchestrator capabilities
- Starting a new project or work session
- Need to refresh AI's understanding of patterns

**What It Does**:
- Fetches all guideline resources from MCP
- Internalizes workflow patterns and best practices
- Sets up template discovery process
- Validates understanding of tool ecosystem

**Invocation**: AI automatically initializes on first connection, or invoke manually if needed

> **See**: [AI Guidelines - Initialization](ai-guidelines#initialization-process) for detailed initialization process

---

### `create_feature_workflow`

**Purpose**: Create comprehensive features with templates, tasks, and proper organization

**When to Use**:
- Creating a major new functional area (3+ related tasks)
- Need structured approach to feature planning
- Want to ensure comprehensive documentation from the start
- Learning feature creation best practices

**What It Covers**:
1. Understanding current project state
2. Template discovery and selection
3. Feature creation with metadata
4. Associated task creation with git workflow detection
5. Dependency establishment
6. Validation and review

**Key Decisions It Helps With**:
- Which templates to apply for comprehensive coverage
- How to break down feature into tasks
- What priority and complexity to assign
- How to establish task dependencies

**Autonomous Alternative**: Simply ask "Create a feature for user authentication" and Claude will apply feature creation patterns automatically

---

### `task_breakdown_workflow`

**Purpose**: Break down complex tasks into manageable, focused subtasks

**When to Use**:
- Task complexity rating is 7 or higher
- Task spans multiple technical areas or skill sets
- Need clear implementation phases
- Want to enable parallel work by team members

**What It Covers**:
1. Analyzing the complex task
2. Identifying natural boundaries (component, phase, skill set)
3. Creating feature container (if beneficial for 4+ subtasks)
4. Creating focused subtasks with proper templates
5. Establishing dependencies and sequencing
6. Updating original task to coordination role

**Key Decisions It Helps With**:
- When to create a feature vs. just subtasks
- How to determine natural breakdown boundaries
- What complexity target for subtasks (3-6 recommended)
- How to sequence implementation dependencies

**Autonomous Alternative**: Ask "This task is too complex, help me break it down" and Claude will apply breakdown patterns automatically

---

### `project_setup_workflow`

**Purpose**: Initialize new projects with proper structure, features, and foundation tasks

**When to Use**:
- Starting a brand new project from scratch
- Need comprehensive project foundation
- Want to establish standards and workflows upfront
- Large project requiring proper planning and structure

**What It Covers**:
1. Project foundation and comprehensive summary creation
2. Project documentation structure (charter, architecture, standards)
3. Feature planning and core feature creation
4. Initial task creation (infrastructure, research, setup)
5. Template strategy and custom template planning
6. Development workflow setup (git, QA, standards)
7. Dependency sequencing
8. Project monitoring and progress tracking setup

**Key Decisions It Helps With**:
- How to structure project hierarchy
- Which features to create initially
- What foundation tasks are needed
- How to set up development standards

**Autonomous Alternative**: Ask "Create a new project for building a web application" and Claude will create project with appropriate structure automatically

---

### `implementation_workflow`

**Purpose**: Smart implementation workflow for tasks, features, and bugs with automatic git detection and workflow integration

**When to Use**:
- Ready to start implementing a specific feature or task
- Want automatic git workflow integration
- Need guidance on template application
- Learning implementation best practices

**What It Covers**:
1. Memory-based configuration loading (PR preferences, branch naming, custom workflows)
2. Current state check and git detection (automatic)
3. Work type detection (task, feature, or **bug**) with specialized guidance
4. **Bug-specific investigation** and root cause verification (for bugs)
5. Smart template application based on context and git detection
6. Implementation execution with template guidance
7. **Mandatory regression testing** (for bug fixes)
8. Completion validation before marking done

**Key Decisions It Helps With**:
- Which task to work on next
- What templates to apply (automatic suggestions)
- Whether to use git workflows (auto-detected)
- **For bugs**: When investigation is complete and ready to implement
- **For bugs**: What regression tests are needed
- When task is truly complete

**Special Features**:
- **Git Detection**: Automatically detects .git directory and suggests git workflow templates
- **GitHub Integration**: Asks about PR workflows if git detected
- **Template Stacking**: Suggests combining multiple templates for comprehensive guidance
- **Bug Investigation Integration**: Offers Bug Investigation template if not applied, verifies root cause before implementation
- **Regression Testing Enforcement**: For bug fixes, requires comprehensive regression tests before completion

**Bug Handling**:

When working on bugs (task-type-bug), the workflow provides specialized guidance:

1. **Investigation Phase**:
   - Checks if Bug Investigation template is applied
   - Offers to apply template if missing
   - Verifies root cause is documented before allowing implementation
   - Guides through systematic investigation if incomplete

2. **Implementation Phase**:
   - Reproduce bug in tests first (test should fail with current code)
   - Document reproduction steps
   - Implement fix addressing root cause
   - Verify test passes with fix

3. **Regression Testing** (MANDATORY):
   - **Bug Reproduction Test**: Test that fails with old code, passes with fix
   - **Edge Case Tests**: Boundary conditions that led to the bug
   - **Integration Tests**: If bug crossed component boundaries
   - **Performance Tests**: If bug was performance-related
   - **Test Documentation**: BUG/ROOT CAUSE/FIX comments required

4. **Completion Validation**:
   - Root cause documented
   - Bug investigation complete
   - Regression tests created and passing
   - Test names reference task ID
   - Code coverage increased
   - **Cannot complete without regression tests**

See [Regression Testing Requirements](#regression-testing-requirements) below for detailed guidance.

**Autonomous Alternative**: Ask "What should I work on next?" or "I'll start implementing the login feature" and Claude will guide implementation automatically

---

## When to Use Workflow Prompts

### Choose Explicit Workflow Prompts When:

✅ **Complex scenarios** requiring guaranteed comprehensive coverage
✅ **Learning** the system and want to understand best practices
✅ **Quality gates** are critical (security, compliance, production systems)
✅ **Team standardization** - workflow prompts ensure consistent approach
✅ **Uncertain about approach** - step-by-step guidance reduces mistakes

### Choose Autonomous Pattern Application When:

✅ **Everyday tasks** - faster, more natural conversation
✅ **Familiar scenarios** - you know what you want
✅ **Iterative work** - quick adjustments and updates
✅ **Exploration** - trying different approaches
✅ **Simple operations** - creating single tasks, updating status

### Decision Tree

```
Is this a complex scenario with multiple steps?
├─ Yes → Consider explicit workflow prompt
│   └─ Are you learning or need guaranteed coverage?
│       ├─ Yes → Use explicit workflow prompt
│       └─ No → Try autonomous first, escalate if needed
│
└─ No → Use autonomous pattern application
```

> **See**: [AI Guidelines - When to Use Each Mode](ai-guidelines#dual-workflow-model) for detailed guidance

---

## Usage Patterns

### Pattern 1: Guided Discovery

Invoke workflow prompt without details - Claude will ask questions to understand your needs:

```
User: "Use the project setup workflow"

Claude: "I'll help you set up a new project. What type of project are you building?
What technologies will you use? What are your main business goals?"
```

**Best For**: Exploring options, learning, not sure about all details yet

---

### Pattern 2: Direct Implementation

Provide details directly for immediate, targeted assistance:

```
User: "Use the create feature workflow for user authentication with email/password
login, social auth, and JWT token management"

Claude: [Immediately applies workflow with your specific details]
```

**Best For**: Clear requirements, time-sensitive work, specific problem-solving

---

### Pattern 3: Escalation from Autonomous

Start autonomous, escalate to explicit if complexity warrants:

```
User: "Help me plan this feature"

Claude: [Starts autonomous pattern application]
[Recognizes complexity]

Claude: "This is a complex feature with multiple integration points. Would you like
me to use the create_feature_workflow for comprehensive step-by-step guidance?"
```

**Best For**: Most scenarios - let Claude assess and suggest escalation

---

### Pattern 4: Sequential Workflow Application

Combine workflows for comprehensive coverage:

```
User: "Use the project setup workflow to create a new API project"
[Project created]

User: "Now use the create feature workflow for the authentication feature"
[Feature created with tasks]

User: "Apply the implement feature workflow to start working on the first task"
[Implementation begins with proper templates]
```

**Best For**: Large projects, systematic development, learning the full workflow

---

### Pattern 5: PRD-Driven Development ⭐ Most Effective

Provide Product Requirements Document for comprehensive intelligent breakdown:

```
User: "Analyze this PRD and break it down into a complete project structure:

# Authentication System v2 PRD

## Overview
Modernize authentication with OAuth 2.0, social login, and MFA support.

## Requirements
- Email/password authentication
- Google and GitHub OAuth
- Two-factor authentication (TOTP)
- Session management with JWT
- Password reset flow

## Technical Constraints
- Must support 10,000 concurrent users
- Session tokens expire after 24 hours
- Integration with existing user database

## Success Criteria
- All authentication methods working
- < 200ms login response time
- 100% test coverage on auth flows"

Claude: [Reads entire PRD]
[Identifies 4 major features]
[Creates project structure with 15 tasks]
[Applies Technical Approach + Requirements templates]
[Creates 8 blocking dependencies]

Claude: "Created 'Authentication System v2' project with:

Features:
1. OAuth Integration (5 tasks) - BLOCKS other features
2. Session Management (4 tasks) - Core dependency
3. MFA Implementation (3 tasks) - Can run parallel after OAuth
4. Password Management (3 tasks) - Independent

Recommended sequence:
1. Start: OAuth Integration (unblocks most work)
2. Parallel: Password Management flow
3. After OAuth: Session Management
4. Final: MFA Implementation

Ready to begin implementation?"
```

**Best For**: ⭐ **Most scenarios with defined requirements** - provides best results

**Why It Works Best**:
- Complete context enables intelligent breakdown
- Proper technical dependency sequencing
- Systematic template application based on PRD content
- Optimal feature and task granularity
- AI makes informed decisions with full scope

> **Detailed Guide**: See [PRD Workflow Guide](quick-start#prd-driven-development-workflow) for complete instructions, examples, and PRD best practices.

---

## Integration with Templates

### How Workflows and Templates Work Together

**Workflow prompts guide the PROCESS, templates structure the WORK**:

| Aspect | Templates | Workflow Prompts |
|--------|-----------|------------------|
| **Purpose** | Define WHAT to document | Define HOW to accomplish work |
| **Creates** | Sections (Requirements, Technical Approach) | Step-by-step procedural guidance |
| **Works With** | Any MCP client | Any MCP client |
| **Sub-Agents** | Sections read by specialists | Process followed by orchestrator |
| **Example** | "Technical Approach" section with architecture | "Step 3: Read Technical Approach and implement" |

### How Workflows Use Templates

All workflow prompts integrate with the template system:

1. **Automatic Discovery**: Workflows use `list_templates` to find applicable templates
2. **Smart Selection**: Workflows suggest templates based on work type and context
3. **Section Creation**: Templates create the sections (WORK structure)
4. **Process Guidance**: Workflows guide you through using those sections (PROCESS steps)
5. **Multiple Templates**: Workflows often apply 2-3 templates for comprehensive coverage
6. **Git Detection**: Workflows automatically suggest git templates when .git directory detected

### Template Categories by Workflow

**Feature Creation Workflow** (creates WORK structure):
- Context & Background → creates "Business Context" section
- Requirements Specification → creates "Requirements" section
- Technical Approach → creates "Technical Approach" section

**Then workflow guides PROCESS**:
1. Apply templates (creates sections)
2. Fill in business context section
3. Document requirements section
4. Plan technical approach section

---

**Task Breakdown Workflow** (creates WORK structure):
- Task Implementation Workflow → creates "Implementation Steps" section
- Local Git Branching Workflow → creates "Git Workflow" section (if git detected)
- Technical Approach → creates "Technical Approach" section (for complex subtasks)

**Then workflow guides PROCESS**:
1. Apply templates to subtasks (creates sections)
2. Analyze complex task
3. Create subtasks with template sections
4. Establish dependencies
5. Sequence implementation

---

**Implementation Workflow** (uses existing WORK structure):
1. Check if templates already applied
2. If not, suggest and apply appropriate templates (creates sections)
3. Read template sections for context (Requirements, Technical Approach)
4. Follow procedural steps to implement
5. Update template sections with results

**Key Insight**: Templates create the sections FIRST, then workflows guide you through USING those sections.

### Templates Work With Both Execution Patterns

**Direct Execution** (any MCP client):
```
1. Workflow applies templates → creates sections
2. Workflow guides you through process
3. You read template sections for context
4. You implement the work
5. You update template sections with results
```

**Sub-Agent Execution** (Claude Code only):
```
1. Workflow applies templates → creates sections
2. Orchestrator launches specialist
3. Specialist reads template sections for context
4. Specialist implements the work
5. Specialist updates template sections with results
```

**Templates are identical in both patterns** - they create the same sections (Requirements, Technical Approach, Testing Strategy). The difference is WHO reads and implements: you directly, or a specialist agent.

> **See**: [Templates Guide](templates) for complete template documentation, including how templates work with both direct and sub-agent execution patterns, and [AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven) for discovery patterns

---

## Regression Testing Requirements

When fixing bugs using `implementation_workflow`, **comprehensive regression tests are mandatory** to prevent the issue from recurring. The workflow enforces these requirements and will not allow completion without proper tests.

### When Required

**All bug fixes MUST include regression tests.** This applies to:
- Tasks tagged with `task-type-bug`
- Hotfixes (`task-type-hotfix`)
- Any work addressing production issues or defects

### Required Test Types

#### 1. Bug Reproduction Test (Required)

Create a test that **fails with the old code** and **passes with the fix**:

```kotlin
@Test
fun `should handle null user token without NPE - regression for AUTH-70490b4d`() {
    // BUG: User logout crashed when token was null
    // ROOT CAUSE: user.token.invalidate() called without null check
    // FIX: Changed to user.token?.invalidate()

    val user = User(token = null)
    assertDoesNotThrow {
        authService.logout(user)
    }
}
```

**Requirements**:
- Test name clearly describes the scenario and references task ID
- Comments explain BUG, ROOT CAUSE, and FIX
- Test reproduces exact conditions that caused the bug
- Assertions verify bug condition doesn't cause failure

#### 2. Edge Case Tests (Required if applicable)

Test boundary conditions and scenarios not previously covered:

```kotlin
@Test
fun `should handle empty token string - edge case for AUTH-70490b4d`() {
    val user = User(token = "")
    assertDoesNotThrow { authService.logout(user) }
}

@Test
fun `should handle whitespace-only token - edge case for AUTH-70490b4d`() {
    val user = User(token = "   ")
    assertDoesNotThrow { authService.logout(user) }
}
```

#### 3. Integration Tests (Required if bug crossed boundaries)

Test component interactions where the bug occurred:

```kotlin
@Test
fun `logout flow should complete when user has no active session`() {
    // This bug affected the full logout flow
    val user = createUserWithoutSession()

    val result = authService.logout(user)

    assertEquals(LogoutResult.SUCCESS, result.status)
    verifySessionCleaned(user)
    verifyAuditLogCreated(user, "logout")
}
```

#### 4. Performance Tests (Required if performance-related)

Verify fix doesn't introduce performance regressions:

```kotlin
@Test
fun `logout should complete within 100ms even with null token`() {
    val user = User(token = null)

    val duration = measureTimeMillis {
        authService.logout(user)
    }

    assertTrue(duration < 100, "Logout took ${duration}ms, expected < 100ms")
}
```

### Test Naming Convention

**Format**: `should [expected behavior] - regression for [TASK-ID-SHORT]`

**Examples**:
- `should handle null input - regression for TASK-70490b4d`
- `should process concurrent requests - regression for TASK-a2a36aeb`
- `should validate maximum value - regression for TASK-12bf786d`

### Test Documentation Requirements

Every regression test **MUST** include:

1. **Descriptive test name** with task ID reference
2. **Comment block** explaining:
   - `BUG:` What went wrong and user impact
   - `ROOT CAUSE:` Technical reason for the bug
   - `FIX:` What code change fixed it
3. **Clear assertions** verifying expected behavior

### Completion Checklist

Before marking a bug fix as completed, verify:

- ✅ Bug reproduction test exists and fails on old code
- ✅ Bug reproduction test passes with fix
- ✅ Edge cases identified and tested
- ✅ Integration tests added if bug crossed boundaries
- ✅ Performance tests added if relevant
- ✅ All tests have proper documentation comments
- ✅ Test names reference task ID for traceability
- ✅ Code coverage increased for affected code paths
- ✅ All tests passing

### Common Patterns

**Null/Empty Input Bugs**:
```kotlin
@Test
fun `should handle null input - regression for TASK-xxxxx`() {
    assertDoesNotThrow { service.process(null) }
}
```

**Race Condition Bugs**:
```kotlin
@Test
fun `should handle concurrent access - regression for TASK-xxxxx`() {
    val threads = (1..10).map { thread { service.processRequest(it) } }
    threads.forEach { it.join() }
    // Verify no corruption occurred
}
```

**Boundary Value Bugs**:
```kotlin
@Test
fun `should handle maximum value - regression for TASK-xxxxx`() {
    val result = service.calculate(Int.MAX_VALUE)
    assertTrue(result.isSuccess)
}
```

**State Management Bugs**:
```kotlin
@Test
fun `should handle state transition - regression for TASK-xxxxx`() {
    service.initialize()
    service.stop()
    service.initialize() // Bug: second init failed
    assertTrue(service.isRunning)
}
```

### Enforcement

The `implementation_workflow` **enforces** regression testing requirements:

- **Step 3 (Bug Detection)**: Identifies bug fixes and prepares for regression testing
- **Step 5 (Implementation)**: Guides through bug reproduction and test creation
- **Step 7 (Validation)**: Checks for regression tests before allowing completion
- **Critical Warning**: If user attempts to complete without tests, workflow reminds them of requirements

**You cannot mark a bug fix as completed without regression tests.**

---

## Memory-Based Workflow Customization

The `implementation_workflow` supports customization through AI memory configuration, allowing teams to adapt workflows to their specific processes without modifying code.

### Overview

**Memory-based customization** allows you to:
- Define pull request preferences (always/never/ask)
- Customize branch naming conventions with variables
- Override procedural workflow steps while keeping validation
- Configure team-specific processes
- Store configuration globally (user-wide) or per-project (team-wide)

**Key Benefits**:
- ✅ **Zero-config default**: Works out of the box with sensible defaults
- ✅ **Progressive enhancement**: Start minimal, add complexity as needed
- ✅ **Version-controlled**: Project configuration lives in your repo
- ✅ **Natural language**: Update via conversation with AI
- ✅ **AI-agnostic**: Works with any AI memory mechanism

---

### Minimal Configuration

The simplest customization is just your PR preference:

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"
```

That's it! The workflow will now always create pull requests without asking.

**Options**:
- `"always"` - Always create PRs (skip asking)
- `"never"` - Never create PRs, merge directly to main
- `"ask"` - Ask each time (default if not configured)

---

### Memory Configuration Schema

Complete configuration schema with all available options:

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always" | "never" | "ask"

## Branch Naming Conventions (optional - defaults provided)
branch_naming_bug: "bugfix/{task-id-short}-{description}"
branch_naming_feature: "feature/{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/{task-id-short}-{description}"
branch_naming_enhancement: "enhancement/{task-id-short}-{description}"

## Commit Message Customization (optional)
commit_message_prefix: "[{type}/{task-id-short}]"

## Custom Workflow Steps (optional - leave empty to use templates)
### Bug Fix Workflow Override
# [Custom steps override Bug Investigation template procedural guidance]
# Template validation requirements still apply

### Feature Implementation Workflow Override
# [Custom steps override Task Implementation template procedural guidance]
# Template validation requirements still apply
```

---

### Branch Naming Variables

Use these standardized variables in branch naming patterns:

| Variable | Description | Example |
|----------|-------------|---------|
| `{task-id}` | Full task UUID | `70490b4d-f412-4c20-93f1-cacf038a2ee8` |
| `{task-id-short}` | First 8 characters of UUID | `70490b4d` |
| `{description}` | Sanitized task title | `fix-authentication-bug` |
| `{feature-id}` | Feature UUID (if applicable) | `a3d0ab76-d93d-455c-ba54-459476633a3f` |
| `{feature-id-short}` | First 8 chars of feature UUID | `a3d0ab76` |
| `{priority}` | Task priority | `high`, `medium`, `low` |
| `{complexity}` | Task complexity | `1` through `10` |
| `{type}` | Work type from tags | `bug`, `feature`, `enhancement`, `hotfix` |

**Sanitization**: The `{description}` variable is automatically sanitized (lowercase, hyphenated, special chars removed, max 50 chars).

---

### Template Validation vs Procedural Override

**What's Always Used** (never overridden):
- ✅ Validation requirements from templates
- ✅ Acceptance criteria and definition of done
- ✅ Testing requirements and quality gates
- ✅ Technical context and background information

**What Can Be Overridden** (custom workflow steps replace):
- ⚠️ Step-by-step implementation instructions
- ⚠️ Procedural workflow guidance
- ⚠️ Tool invocation sequences

This ensures quality standards are maintained while allowing team-specific processes.

---

### Real-World Configuration Examples

#### Example 1: Startup Team (Minimal Setup)

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "never"
```

**Use Case**: Fast-moving startup, direct commits to main, rapid iteration.

---

#### Example 2: Jira Integration with Custom Branch Naming

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming (Jira-style)
branch_naming_bug: "bugfix/PROJ-{task-id-short}-{description}"
branch_naming_feature: "feature/PROJ-{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/PROJ-{task-id-short}-{description}"

## Commit Messages
commit_message_prefix: "[PROJ-{task-id-short}]"
```

**Use Case**: Team using Jira with project prefix "PROJ", wants consistent ticket references.

**Result**:
- Branch: `feature/PROJ-70490b4d-oauth-authentication`
- Commit: `[PROJ-70490b4d] feat: add OAuth2 authentication`

---

#### Example 3: Enterprise Team with Staging Deployment

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming
branch_naming_bug: "bugfix/{priority}-{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/{task-id-short}-{description}"

## Bug Fix Workflow Override
### Custom Bug Fix Process
1. Create branch from main
2. Implement fix with tests
3. Deploy to staging environment: `./deploy-staging.sh`
4. Run integration test suite: `npm run test:integration:staging`
5. Request QA approval in PR
6. After QA approval, merge to main
7. Deploy to production: `./deploy-production.sh`

Note: Template validation still requires:
- Bug Investigation template analysis completed
- Root cause documented
- Test coverage for bug fix
- Regression tests passing
```

**Use Case**: Enterprise team with required staging deployment and QA approval.

---

#### Example 4: Priority-Based Branch Naming

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming (priority-based)
branch_naming_bug: "bug/{priority}-{complexity}-{description}"
branch_naming_feature: "feature/{feature-id-short}/{description}"
branch_naming_hotfix: "hotfix/{description}"
```

**Use Case**: Team that prioritizes by severity and groups features together.

**Result**:
- Bug: `bug/high-8-security-vulnerability`
- Feature: `feature/a3d0ab76/oauth-integration`
- Hotfix: `hotfix/critical-data-leak`

---

### Natural Language Customization

You can update configuration through natural conversation with your AI:

**Example Conversation**:
```
User: "Can you update our workflow to use Linear-style branch naming?"

AI: "I'll update your project configuration to use Linear conventions:
     {type}/{description}-{task-id-short}

     Should I save this to your project's memory?"

User: "Yes, and we always use pull requests"

AI: "Updated! I've saved:
     - Linear-style branch naming
     - use_pull_requests: always

     Your team's configuration is now in CLAUDE.md"
```

The AI handles:
- Schema generation
- Variable substitution
- Validation
- Storage in appropriate memory location

---

### Global vs Project-Specific Configuration

**Global (User-Wide)**:
- Your personal preferences across all projects
- Stored in AI's global memory
- Examples: PR preference, default branch naming

**Project-Specific (Team-Wide)**:
- Team conventions for specific project
- Stored in project repo (e.g., `CLAUDE.md`)
- Examples: Jira integration, staging deployment steps

**Priority**: Project-specific configuration overrides global preferences.

---

### Getting Started with Customization

1. **Start Simple**: Just configure `use_pull_requests` preference
2. **Add Branch Naming**: Customize patterns for your team's conventions
3. **Override Workflows**: Add custom steps only if needed
4. **Iterate**: Refine based on team feedback

**First-Time Setup** (via AI conversation):
```
User: "Set up workflow customization for our team"

AI: "I'll help you configure. A few questions:
     1. Do you always use pull requests? (always/never/ask)
     2. Do you use Jira or Linear for tickets?
     3. Any specific branch naming conventions?"

[AI creates configuration based on answers]
```

---

### Best Practices

**DO**:
- ✅ Start with minimal configuration (just PR preference)
- ✅ Use default branch naming patterns unless team has strong conventions
- ✅ Store team configuration in version-controlled project files
- ✅ Document custom workflow steps clearly
- ✅ Test configuration with simple task first

**DON'T**:
- ❌ Override template validation requirements
- ❌ Create overly complex branch naming patterns
- ❌ Duplicate template guidance in custom workflow steps
- ❌ Store sensitive information in configuration

---

### Related Documentation

- **[implementation_workflow](workflow-prompts#implementation_workflow)** - Workflow that uses this configuration
- **[AI Guidelines - Memory Patterns](ai-guidelines)** - How AI agents use memory
- **[Quick Start](quick-start)** - Getting started examples
- **[Templates Guide](templates)** - Understanding template validation

---

## Customization

### Adding Team-Specific Patterns

Extend workflow prompts with team-specific patterns by adding to your project's `CLAUDE.md`:

```markdown
## Custom Task Orchestrator Workflow Patterns

### Security Review Workflow Pattern
When creating security-related tasks:
1. Always apply "security" tag
2. Set priority to "high"
3. Apply Technical Approach + Testing Strategy templates
4. Add security checklist section
5. Require review from security team member
```

### Creating Custom Workflow Prompts

Work with your team to create custom workflow prompts for:
- Domain-specific processes (healthcare, finance, etc.)
- Regulatory compliance workflows
- Team-specific quality gates
- Integration with external tools

---

## Best Practices

### For AI Agents

1. **Start with get_overview** before applying any workflow
2. **Suggest workflows** when complexity warrants explicit guidance
3. **Don't force workflows** - use autonomous patterns when appropriate
4. **Validate completion** using get_sections before marking tasks/features done
5. **Apply git workflows** automatically when .git directory detected

### For Development Teams

1. **Use autonomous mode** for most everyday work - it's faster
2. **Invoke explicit workflows** for complex scenarios or when learning
3. **Combine workflows** sequentially for comprehensive coverage
4. **Monitor effectiveness** - refine approach based on what works
5. **Extend with custom patterns** to match your team's processes

---

## Additional Resources

- **[AI Guidelines](ai-guidelines)** - Complete AI initialization and autonomous pattern documentation
- **[Templates Guide](templates)** - Template system and customization
- **[Quick Start](quick-start)** - Getting started with Task Orchestrator
- **[API Reference](api-reference)** - Complete tool documentation

---

**Questions?** Check the [Troubleshooting Guide](troubleshooting) or see [AI Guidelines - Troubleshooting](ai-guidelines#troubleshooting) for AI-specific issues.
