---
name: repo-architecture
description: This codebase's architecture guard rules and config traps, for the implementer and reviewer seats. Points at the root CLAUDE.md sections that state them; read it before changing Kotlin source, migrations, tool parameters or config handling.
user-invocable: false
---

# Repository Architecture

The guard rules and config traps of this codebase are stated once, in the root `CLAUDE.md`. Read
these sections before editing or reviewing code, and follow them as written:

- **"Rules a guard test enforces"**: the layering, package-cycle, dialect-neutrality, environment
  access, Flyway, tool-parameter documentation, domain-boundary and test-function rules, each with
  the test that enforces it. A violation fails that test; fix the code and never add a baseline
  line.
- **"Config traps"**: where runtime config comes from, the two config delivery paths, boolean
  environment variables, and the single construction sites that a defaulted constructor parameter
  silently skips.

For a change that adds a tool, an op, a migration or a dependency, the project's component
checklists are in `.claude/skills/add-component/SKILL.md`.
