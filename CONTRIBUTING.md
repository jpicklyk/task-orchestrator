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

This project follows a simple code of conduct:
- Be respectful and considerate of others
- Focus on constructive feedback
- Help create a welcoming environment for all contributors

## Getting Started

### Prerequisites

- **JDK 17 or later** - Required for Kotlin development
- **Gradle** - Build system (included via wrapper)
- **Docker** (optional) - For testing containerized deployment
- **Git** - Version control

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

4. **Run locally**:
   ```bash
   java -jar build/libs/mcp-task-orchestrator-*.jar
   ```

See [CLAUDE.md](CLAUDE.md) for comprehensive development setup and architecture details.

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
feat(tools): add get_next_task tool for work prioritization

Implements smart task recommendation based on priority and complexity.
Filters out blocked tasks automatically.

Closes #42
```

```
fix(dependencies): prevent circular dependency creation

Add cycle detection in CreateDependencyTool to prevent infinite loops.

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
- Use dependency injection via `ToolExecutionContext`

**Architecture**:
- Follow Clean Architecture layers (domain → application → infrastructure → interface)
- Domain layer must remain framework-agnostic
- Use repository pattern for data access
- Tools should extend `BaseToolDefinition` or `SimpleLockAwareToolDefinition`

### Adding New Tools

See [CLAUDE.md - Adding a New MCP Tool](CLAUDE.md#adding-a-new-mcp-tool) for detailed instructions.

**Checklist**:
- [ ] Extend `BaseToolDefinition`
- [ ] Define clear parameter and output schemas
- [ ] Implement `validateParams()` with proper validation
- [ ] Implement `execute()` with business logic
- [ ] Handle `Result<T>` from repositories properly
- [ ] Add comprehensive tests (aim for 80%+ coverage)
- [ ] Register in `McpServer.createTools()`
- [ ] Document in `docs/api-reference.md`
- [ ] Add AI usage patterns to `docs/ai-guidelines.md`

### Creating Custom Skills

Skills are lightweight AI behaviors that coordinate 2-5 tool calls efficiently (300-600 tokens vs 1500-3000 for subagents).

**Quick Reference**: See [`.claude/skills/README.md`](.claude/skills/README.md) for complete guide.

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

**Quick Reference**: See [`.claude/hooks/README.md`](.claude/hooks/README.md) for complete guide.

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

For schema changes, see [docs/developer-guides/database-migrations.md](docs/developer-guides/database-migrations.md).

**Production migrations** (Flyway):
1. Create file: `src/main/resources/db/migration/V{N}__{Description}.sql`
2. Use sequential numbering
3. Follow SQLite patterns
4. Include rollback instructions in comments
5. Test migration on clean database

## Testing

### Running Tests

```bash
# All tests
./gradlew test

# Specific test class
./gradlew test --tests "GetNextTaskToolTest"

# Migration tests
./gradlew test --tests "*migration*"

# With coverage report
./gradlew test jacocoTestReport
```

### Test Guidelines

- Write tests for all new functionality
- Aim for 80%+ code coverage
- Use descriptive test names that explain what's being tested
- Test both success and error cases
- Mock external dependencies using MockK
- Use H2 in-memory database for repository tests

**Test Structure**:
```kotlin
class MyToolTest {
    @Test
    fun `should return success when valid parameters provided`() {
        // Arrange
        val tool = MyTool()
        val params = buildJsonObject { }

        // Act
        val result = runBlocking { tool.execute(params, context) }

        // Assert
        assertThat(result["success"]?.jsonPrimitive?.boolean).isTrue()
    }
}
```

## Documentation

### Documentation Updates

When adding features, update:
- **`docs/api-reference.md`** - Tool usage examples and AI patterns
- **`docs/ai-guidelines.md`** - AI workflow integration
- **`CLAUDE.md`** - Developer guidance (if architectural changes)
- **README.md** - Only for major feature additions
- **`.claude/skills/README.md`** - If Skills system changes
- **`.claude/hooks/README.md`** - If Hooks system changes
- **`docs/hybrid-architecture.md`** - If 4-tier architecture changes

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
- **Architecture questions**: See [docs/developer-guides/architecture.md](docs/developer-guides/architecture.md)
- **Setup issues**: See [docs/troubleshooting.md](docs/troubleshooting.md)
- **General discussion**: Start a GitHub discussion

Thank you for contributing to MCP Task Orchestrator!
