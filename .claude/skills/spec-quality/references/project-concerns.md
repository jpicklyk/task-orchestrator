# Project-Specific Concerns: MCP Task Orchestrator

These are cross-cutting constraints specific to this codebase. When assessing blast
radius and risk flags, check each area that your change might touch.

This is an awareness list, not a set of rules. The right approach depends on the
specific change — these items flag where non-obvious coupling or complexity tends
to hide.

---

## ToolExecutionContext Coupling

`ToolExecutionContext` is constructed in `CurrentMcpServer.kt` and passed to every
tool. Adding a new service dependency means updating both the context class and
the server construction site. If your change introduces a new service that tools
need, this is part of the blast radius.

## Flyway Is the Only Schema Path

A new table or column is defined only by a Flyway migration; the Exposed table objects are
query mappings and no production code creates schema (`SchemaUtils` is banned in production).
There is no second table list to keep in parity.

## SQLite Migration Constraints

Flyway migrations are append-only — never modify an existing migration file. SQLite
has no `ALTER COLUMN`, so schema changes that modify existing columns require table
recreation (create new, copy data, drop old, rename). Migration files live in
`current/src/main/resources/db/migration/sqlite/`.

## Domain Model Defaults and Test Impact

Changing a default value on a domain model field (e.g., `priority: Priority = Priority.MEDIUM`
to `Priority? = null`) will break tests across the codebase that construct instances
without specifying that field. When assessing blast radius for model changes, search
for all test files that instantiate the affected model.

## MCP Tool Registration

New tools must extend `BaseToolDefinition` and be registered in
`CurrentMcpServer.kt::createTools()`. The tool count is not hardcoded anywhere —
but forgetting registration means the tool exists in code but is invisible to clients.

## Exposed ORM Patterns

The project uses Exposed ORM with SQLite. Key patterns to be aware of:
- All database operations must run inside `transaction {}` blocks
- Repository methods that are called from suspend contexts need appropriate coroutine
  handling
- Repository and route tests run on a real, file-backed SQLite database (the `SqliteTestDatabase`
  fixture), so SQL behaves as in production, including FTS5, triggers and foreign keys

## Repository Interface Contracts

Repository interfaces live in `domain/repository/` and their SQLite implementations
in `infrastructure/repository/`. Adding a method to a repository interface requires
updating the implementation, and any mocks in test files that use that repository.
Search for `mockk<RepositoryName>` across test files to find affected mocks.
