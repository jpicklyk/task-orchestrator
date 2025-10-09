---
layout: default
title: Developer Guides
---

# Developer Guides

Comprehensive technical documentation for developers contributing to or extending MCP Task Orchestrator.

### [Architecture Guide](architecture)

Comprehensive overview of Task Orchestrator architecture, design patterns, and component interactions.

**Topics Covered**:
- Clean Architecture layers
- Core components (Database Manager, Tool System, Repository Pattern)
- Data flow and execution patterns
- Design patterns (Adapter, Repository, Factory, Strategy, etc.)
- Technology stack and dependencies
- Extension points for adding tools, repositories, templates, migrations

**When to Use**: Understanding system design, extending functionality, architectural decisions

---

### [Database Migrations](database-migrations)

Complete guide to managing database schema changes in Task Orchestrator.

**Topics Covered**:
- Flyway migration system
- Creating new migrations
- Testing migrations
- Migration best practices
- Common migration patterns

**When to Use**: Database schema changes, new entity types, schema refactoring

---


## Quick Links

- **[GitHub Repository](https://github.com/jpicklyk/task-orchestrator)** - Source code and issues
- **[Installation Guide](../installation-guide)** - Building from source
- **[API Reference](../api-reference)** - Complete MCP tool documentation
- **[Troubleshooting](../troubleshooting)** - Common development issues

---

## For Developers

### Development Environment

**Prerequisites**:
- JDK 17+
- Gradle 8.5+
- Docker 20.10+
- Git 2.30+

**Quick Setup**:
```bash
# Clone repository
git clone https://github.com/jpicklyk/task-orchestrator.git
cd task-orchestrator

# Build
./gradlew build

# Run tests
./gradlew test

# Build Docker image
docker build -t mcp-task-orchestrator:dev .
```

### Useful Development Commands

```bash
# Run with debug logging
docker run --rm -i -v mcp-task-dev-data:/app/data \
  --env MCP_DEBUG=true \
  mcp-task-orchestrator:dev

# Run tests with coverage
./gradlew test jacocoTestReport

# Check code style
./gradlew ktlintCheck

# Format code
./gradlew ktlintFormat
```

---

## Contributing

We welcome contributions! For contributing guidelines:

1. **Check Issues**: Review [GitHub Issues](https://github.com/jpicklyk/task-orchestrator/issues) for open tasks
2. **Fork Repository**: Create your own fork for development
3. **Create Branch**: Use descriptive branch names (`feature/`, `fix/`, `docs/`)
4. **Follow Standards**: Maintain code quality and test coverage
5. **Submit PR**: Create pull request with clear description

### Code Standards

- **Kotlin**: Follow Kotlin coding conventions
- **Testing**: Maintain >80% coverage for new code
- **Documentation**: Update docs for new features
- **Commits**: Use conventional commit format

---

## Additional Resources

- **[Quick Start](../quick-start)** - Getting started with Task Orchestrator
- **[AI Guidelines](../ai-guidelines)** - AI usage patterns and initialization
- **[Templates Guide](../templates)** - Template system reference
- **[Workflow Prompts](../workflow-prompts)** - Workflow automation

---

**Questions?** Check [Discussions](https://github.com/jpicklyk/task-orchestrator/discussions) or open an [Issue](https://github.com/jpicklyk/task-orchestrator/issues) for developer-specific questions.
