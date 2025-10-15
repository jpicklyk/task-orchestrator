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

### Documentation Style

- Use clear, concise language
- Include code examples where appropriate
- Explain **why** not just **what**
- Add usage examples for AI agents
- Keep documentation synchronized with code

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
