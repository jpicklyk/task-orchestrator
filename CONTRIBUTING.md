# Contributing to MCP Task Orchestrator

Thank you for your interest in contributing to MCP Task Orchestrator! This guide will help you get started with contributing to the project.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Contribution Guidelines](#contribution-guidelines)
- [Testing](#testing)
- [Documentation](#documentation)
- [Pull Request Process](#pull-request-process)

## Code of Conduct

This project is governed by our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold it.

## Getting Started

### Prerequisites

- **JDK 17 or later** - Required for Kotlin development
- **Gradle** - Build system (included via wrapper)
- **Docker** - Required for running the MCP server
- **Git** - Version control
- **Claude Code** (recommended) - For the full development experience with skills and hooks

### Setup Development Environment

1. **Fork and clone the repository**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/task-orchestrator.git
   cd task-orchestrator
   ```

2. **Build the project**:
   ```bash
   ./gradlew build
   ```

3. **Run tests**:
   ```bash
   ./gradlew test
   ```

4. **Build the dev Docker image**:
   ```bash
   ./scripts/docker-build.sh
   ```

### Claude Code Setup

When you clone this repository, two things are pre-configured for development:

- **Plugin auto-discovery**: The `.claude-plugin/marketplace.json` at the repo root is automatically discovered by Claude Code. Install the plugin via `/plugin` and it provides skills, hooks, and an orchestration context — without bundling an MCP server (developers run their own).

- **Dev MCP server**: The `.mcp.json` at the repo root configures the MCP server to use your locally-built Docker image (`task-orchestrator:dev`). Claude Code reads this automatically on startup.

After building the dev Docker image and installing the plugin, restart Claude Code. The MCP server will start automatically with your local build, and all skills and hooks will be active.

See [CLAUDE.md](CLAUDE.md) for comprehensive architecture details and development patterns.

## Development Workflow

### Branching Strategy

- **`main`** - Production-ready code, protected branch
- **`feature/*`** - New features (e.g., `feature/add-export-tool`)
- **`fix/*`** - Bug fixes (e.g., `fix/dependency-validation`)
- **`docs/*`** - Documentation updates (e.g., `docs/api-reference-update`)

### Commit Message Format

We follow **conventional commits** style:

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types**:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `test`: Adding or updating tests
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `chore`: Build process or auxiliary tool changes

**Examples**:
```
feat(tools): add items_read next select for work prioritization

Implements ranked pick-up candidates based on priority and complexity.
Filters out blocked and claimed items automatically.

Closes #42
```

```
fix(dependencies): prevent circular dependency creation

Add cycle detection in the deps_write create op to prevent infinite loops.

Fixes #53
```

## Contribution Guidelines

### What to Contribute

**We welcome contributions for**:
- New MCP tools for task management workflows
- Bug fixes and error handling improvements
- Documentation improvements and examples
- Performance optimizations
- Test coverage improvements
- Templates for common workflows

**Before starting large features**:
- Open an issue to discuss the feature
- Wait for maintainer feedback
- Ensure alignment with project goals

### Code Style

**Kotlin Code**:
- Follow standard Kotlin conventions
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions focused and small
- Handlers receive collaborators through the one `ToolExecutionContext` (`env`); do not construct services inline

**Architecture**:
- Follow the layer model — domain → application → infrastructure → interfaces, outer layers may depend on inner ones, never the reverse (see below for enforcement)
- Domain layer must remain framework-agnostic
- Use repository pattern for data access
- A tool is a `ToolSpec` of selects or ops registered in `buildToolSpecs()` (`interfaces/shared/tools/ToolRegistry.kt`); its request classes live in `application/api/<area>/`
- Layering is enforced by `current/src/test/kotlin/.../architecture/LayeringTest.kt` (Konsist), and
  `PackageCycleTest` keeps the `application/<sub>` packages acyclic. Both check against two-way-ratcheted
  baselines under `current/src/test/resources/architecture/` (`layering-baseline.txt`,
  `package-cycle-baseline.txt`) that are empty: any new violation or cycle fails the test. Fix the import
  or move the shared type down instead of adding a baseline line.

### Adding New Tools

See the `add-component` skill (`.claude/skills/add-component/SKILL.md`) for the full step-by-step checklist, including the tool-documentation single-source policy.

**Summary**:
- [ ] Write the request class with `@Doc` on every field (`current/src/main/kotlin/.../application/api/<area>/`)
- [ ] Add one select/op file with its name, `doc`, profile, capability and `errors` (`interfaces/shared/tools/<area>/`)
- [ ] Return `Outcome` values from the handler (domain errors, never exceptions, for expected failures)
- [ ] Register the select/op in its tool, and a new tool in `buildToolSpecs()` (`interfaces/shared/tools/ToolRegistry.kt`)
- [ ] Run `./gradlew :current:regenerateToolGoldens` and `./gradlew :current:regenerateApiReference`, and commit both outputs
- [ ] Add parity scenarios (happy path and scope-forbidden) and focused tests (failure cases and edge cases)
- [ ] Write the behavior prose in `current/docs/api-reference.md` (the field tables are generated)

### Creating Custom Skills

Skills are lightweight AI behaviors that coordinate 2-5 tool calls efficiently (300-600 tokens vs 1500-3000 for subagents).

Skills live in the plugin at `claude-plugins/task-orchestrator/skills/`.

**Creation Methods**:

1. **Use Skill Builder (Recommended)** - Interactive tool that guides you:
   ```
   You: "Help me create a Skill that [describes workflow]"
   → Skill Builder interviews you about requirements
   → Generates complete SKILL.md with proper structure
   → Creates examples.md and supporting files
   ```

2. **Copy a Template** - 7 ready-to-use templates available:
   - Coordination Skill - Coordinate multiple tool calls
   - Information Retrieval - Query and analyze data
   - Status Update - Efficiently update entity state
   - Workflow Automation - Automate multi-step workflows
   - Analysis - Analyze patterns and provide insights
   - Validation - Check quality criteria
   - Migration - Transform or move data

3. **Manual Creation** - For full control:
   ```markdown
   ---
   name: My Skill
   description: Brief description (under 200 chars, include when to use)
   allowed-tools: tool1, tool2, tool3
   ---

   # My Skill

   [Implementation with workflows]
   ```

**Skill Structure**:
```
.claude/skills/my-skill/
├── SKILL.md           # Core workflows (required, always loaded)
├── examples.md        # Usage examples (loaded on demand)
├── reference.md       # Tool details (loaded on demand)
└── templates/         # Reusable patterns (optional)
```

**Best Practices**:
- ✅ Specific descriptions: "Coordinate feature workflows" not "Manage features"
- ✅ Clear trigger phrases: Document exactly when Claude should invoke
- ✅ Minimal tool sets: Only include tools actually needed
- ✅ Progressive disclosure: Core workflows in SKILL.md, details in supporting files
- ✅ Concrete examples: Show real usage patterns with expected outputs

**Testing**:
1. Restart Claude Code to load your Skill
2. Test with natural language: "Use [Skill Name] to..."
3. Verify activation with: "What Skills do you have available?"

**When to Create Skills**:
- Repetitive coordination patterns (2-5 tool calls)
- Workflow shortcuts you use frequently
- Domain-specific coordination logic
- NOT for complex reasoning or code generation (use subagents)

---

### Creating Custom Hooks

Hooks are bash scripts that execute automatically when events occur (0 tokens - no LLM calls).

Hooks live in the plugin at `claude-plugins/task-orchestrator/hooks/`.

**Creation Methods**:

1. **Use Hook Builder (Recommended)** - Interactive Skill that helps you:
   ```
   You: "Help me create a hook that [describes automation]"
   → Hook Builder interviews you about needs
   → Generates complete bash script with error handling
   → Creates configuration
   → Provides testing instructions
   ```

2. **Copy a Template** - 11 ready-to-use templates available:
   - Basic PostToolUse Hook - Simple event-triggered automation
   - Blocking Quality Gate Hook - Prevent operations that fail validation
   - Database Query Hook - Query Task Orchestrator database
   - Git Automation Hook - Automate git commits, branches, tags
   - Logging/Metrics Hook - Track events to CSV/JSON logs
   - External API Hook - Call webhooks, REST APIs
   - Notification Hook - Send Slack/email/desktop notifications
   - SubagentStop Hook - React to subagent completion
   - Conditional Multi-Action Hook - Multiple actions based on conditions
   - Dependency Check Hook - Verify dependencies before allowing operations
   - Error Handling Hook - Robust error handling patterns

3. **Manual Creation** - For full control:
   ```bash
   #!/bin/bash
   # Read JSON input from stdin
   INPUT=$(cat)

   # Extract fields using jq
   FIELD=$(echo "$INPUT" | jq -r '.tool_input.field_name')

   # Defensive check
   if [ "$FIELD" != "expected_value" ]; then
     exit 0
   fi

   # Perform action
   cd "$CLAUDE_PROJECT_DIR"
   # ... your automation logic ...

   echo "✓ Hook completed successfully"
   exit 0
   ```

**Hook Configuration** (`.claude/settings.local.json`):
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__set_status",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/your-hook.sh",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

**Best Practices**:
- ✅ Start simple: Begin with logging/metrics before blocking hooks
- ✅ Be defensive: Always validate conditions before acting
- ✅ Handle errors gracefully: Don't break Claude's workflow
- ✅ Keep hooks fast: Long-running hooks slow down Claude (use timeouts)
- ✅ Test thoroughly: Test with sample JSON, edge cases, missing dependencies

**Exit Codes**:
- `0`: Success (allow operation to proceed)
- `1`: Error (unexpected failure, allow operation for safety)
- `2`: Block operation (quality gate failed, operation prevented)

**When to Create Hooks**:
- Side effects (git commit, run tests, send notification)
- Quality gates (block operations that fail validation)
- Metrics tracking (log events, measure performance)
- External integration (update Jira, call webhooks)
- NOT for reasoning or decisions (use Skills or Subagents)

**Testing**:
```bash
# Test with sample input
echo '{"tool_input": {"id": "test", "status": "completed"}}' | \
  .claude/hooks/your-hook.sh

# Check output for errors
```

---

### Database Changes

For schema changes, follow the migration checklist in [.claude/skills/add-component/SKILL.md](.claude/skills/add-component/SKILL.md).

**Production migrations** (Flyway):
1. Create file: `src/main/resources/db/migration/sqlite/V{N}__{Description}.sql`
2. Use sequential numbering
3. Follow SQLite patterns
4. Include rollback instructions in comments
5. Test migration on clean database

## Testing

### Running Tests

```bash
# All tests
./gradlew :current:test

# Specific test class
./gradlew :current:test --tests "ItemsReadNextTest"

# Migration tests
./gradlew :current:test --tests "*migration*"
```

### Serial (timing-sensitive) tests

Tests that assert wall-clock bounds or deliberately spin CPU threads are tagged `@Tag("serial")`.
The `test` task runs in parallel forks and excludes that tag; the `serialTest` task runs only the
tagged tests, alone in a single fork, and `test` is finalized by it so `./gradlew :current:test` still
covers them. Tag a test when its time bound leaves less than about 1.5 s or 3x headroom over its
typical duration, or when it pins CPU. To run one directly use
`./gradlew :current:serialTest --tests "<pattern>"`; plain `:current:test --tests` will not find it
(and skips `serialTest` entirely).

### Test Guidelines

- Write tests for all new functionality
- Cover the happy path, failure/error cases, and edge cases — there is no coverage-percentage
  target or coverage-reporting tool (JaCoCo/Kover) configured in this project
- Use descriptive test names that explain what's being tested
- Test both success and error cases
- Mock external dependencies using MockK
- Repository and route tests run on a real, file-backed SQLite database through the shared fixture
  `SqliteTestDatabase` (`current/src/test/kotlin/.../test/sqlite/`): a copy of one Flyway-migrated
  template per JVM, opened through the production `DatabaseManager`. Declare it with
  `@RegisterExtension val db = SqliteTestDatabase.perMethod()` (a fresh database per test) or, on a
  companion `@JvmField`, `SqliteTestDatabase.perClass()` (one per class, for read-only tests); use
  `SqliteTestDatabase.open().use { }` when one test needs several databases. Pass `db.database` (never a
  global default) to `transaction(db = ...)`.
- For unit tests that only need collaborators stubbed, use the shared `MockRepositoryProvider`
  (`current/src/test/kotlin/.../test/MockRepositoryProvider.kt`); it stubs every `RepositoryProvider`
  accessor and a reflection test fails when a new accessor is left unstubbed.
- Migration and upgrade tests build their starting database with `UpgradeHarness` (see
  `UpgradeHarnessTest` and `GoldenV17UpgradeTest`).

**Test Structure**:
Tool tests drive a select or op through the production topology, `POST /api/v1/ops/{tool}`, against a per-method SQLite database (see `ItemsReadGetTest` and `ItemsReadRig` for the pattern):

```kotlin
class MySelectTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    @Test
    fun `get answers the lean view of an existing item`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Top")
            val r = select("""{"select":"get","itemId":"${item.id}"}""")
            assertEquals(200, r.status)
            assertTrue(r.ok, r.raw)
        }
}
```

## Documentation

### Documentation Updates

When adding features, update:
- **`current/docs/api-reference.md`** - Behavior prose and examples (the field tables and error catalog are generated: run `./gradlew :current:regenerateApiReference`)
- **`CLAUDE.md`** - Developer guidance (if architectural changes)
- **README.md** - Only for major feature additions
- **`claude-plugins/task-orchestrator/skills/`** - If Skills change
- **`claude-plugins/task-orchestrator/hooks/`** - If Hooks change

### Documentation Style

- Use clear, concise language
- Include code examples where appropriate
- Explain **why** not just **what**
- Add usage examples for AI agents
- Keep documentation synchronized with code

**For Skills**:
- Document trigger phrases clearly (what user says → Skill activates)
- Include token cost estimates (compare to subagent approach)
- Provide concrete examples with expected outputs
- Explain allowed-tools rationale

**For Hooks**:
- Document trigger events clearly (which tool calls → Hook fires)
- Include exit code behavior (0 = success, 2 = block)
- Provide sample JSON input for testing
- Explain side effects and potential failures

## Pull Request Process

### Before Submitting

1. **Ensure all tests pass**:
   ```bash
   ./gradlew test
   ```

2. **Update documentation** as needed

3. **Follow commit message format**

4. **Rebase on latest main**:
   ```bash
   git checkout main
   git pull origin main
   git checkout your-feature-branch
   git rebase main
   ```

### Submitting Pull Request

1. **Push your branch**:
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Create pull request** via GitHub:
   - Use descriptive title
   - Reference related issues
   - Explain what changed and why
   - Include test results
   - Add screenshots for UI changes

3. **PR Description Template**:
   ```markdown
   ## Summary
   Brief description of changes

   ## Changes
   - List of specific changes

   ## Testing
   - How you tested the changes

   ## Related Issues
   Closes #issue-number
   ```

### Review Process

- Maintainers will review your PR
- Address feedback and requested changes
- Keep PR focused on single feature/fix
- Squash commits if requested
- PR will be merged after approval

### After Merge

- Delete your feature branch:
  ```bash
  git branch -d feature/your-feature-name
  git push origin --delete feature/your-feature-name
  ```

- Update your main branch:
  ```bash
  git checkout main
  git pull origin main
  ```

## Questions?

- **Technical questions**: Open a GitHub issue
- **Architecture questions**: See [CLAUDE.md](CLAUDE.md)
- **Setup issues**: See [current/docs/quick-start.md](current/docs/quick-start.md)
- **General discussion**: Start a GitHub discussion

Thank you for contributing to MCP Task Orchestrator!
