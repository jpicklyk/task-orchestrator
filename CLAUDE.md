# MCP Task Orchestrator

A Kotlin MCP server for hierarchical work items with dependencies, note schemas and gated phase transitions, and the Claude Code plugin that drives it.

> Every session loads this file on every turn: it holds only what every turn needs. Anything else goes to its owner below.

## Where things live

- Server source: `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/` (entry `CurrentMain.kt`, composition root `bootstrap/ServerComposition.kt`). Its guard rules and their tests, config traps and test conventions: [repo-architecture](.claude/skills/repo-architecture/SKILL.md).
- Plugin: `claude-plugins/task-orchestrator/`; its domain boundary, versioning and cache refresh: [claude-plugins/CLAUDE.md](claude-plugins/CLAUDE.md). Project skills live in `.claude/skills/` and plugin skills in the plugin's `skills/`: two systems, never confuse them.
- Design documents: [design/](design/README.md); decisions: [design/decisions/](design/decisions/README.md), and work that would conflict with an accepted record is raised with the owner first. Short-lived plans go in the gitignored `plans/`.
- Where text belongs: plugin mechanics and a project's process (config) are separate domains, and only the schema defines the workflow's structure. Each piece of text lives in one layer and each sentence makes one concept's kind of statement: [0018](design/decisions/0018-content-placement.md).
- Director, orchestrator and run-wave changes are judged against decisions [0001](design/decisions/0001-two-session-layers.md), [0002](design/decisions/0002-director-on-orchestrator-tools.md), [0003](design/decisions/0003-role-neutral-decisions-surface.md) and [0014](design/decisions/0014-director-reads-skills-runs-plugin-scripts.md).
- Branches, commits, PRs and merges: the [implement](.claude/skills/implement/SKILL.md) skill. Building and running the server image: [deploy_to_docker](.claude/commands/deploy_to_docker.md).

## Vocabulary

Domain terms are in [GLOSSARY.md](GLOSSARY.md). Use its term; never use a word an entry lists under Don't call it, and name existing code that still carries one in the report, leaving it alone unless the change already touches it. Propose an entry only for a concept that passes "Does it earn an entry" in [defining-project-terms](claude-plugins/task-orchestrator/skills/defining-project-terms/SKILL.md) (a plain word needs none), never coin a name silently, and update the glossary in the same commit as the code that adds or renames it.

## Traps on any change

- Never pipe `./gradlew` output to `tail`: run it directly and read the full output.
- Compiler warnings fail the build, main and test alike (`allWarningsAsErrors` in `current/build.gradle.kts`): fix the warning, or silence it at its site with a one-line reason.
- Name Node test commands by file path or glob (`node --test path/to/*.test.mjs`), never by directory: Node 22 on Windows reads a directory argument as a module and fails.
