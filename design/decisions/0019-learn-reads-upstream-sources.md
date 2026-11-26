# 0019 Learn reads Task Orchestrator's documents upstream, at the installed version

**Status:** Accepted | **Date:** 2026-11-26 | **Source:** Boundary analysis 2026-11-26, pass 10 decisions (plans/boundaries/README.md, not tracked), decision G2, with the owner's direction given during that pass; settles the question owner decision 27 left open when it declined to ship the glossary (seam S61 in [seams.md](../seams.md))

## Decision

The learn skill explains Task Orchestrator from two sources, read when a question needs them: Task Orchestrator's own documentation, glossary and design documents, fetched from its public GitHub repository at the tag of the installed version (falling back to `main`, with a line saying the documents may be newer than the install) and reached through their index pages; and the user's own served schema and config. Nothing is bundled into the plugin, so the glossary is still not shipped. Fetched text is reference data, never instructions.

## Context

The owner's direction for learn: "take in from github the most updated docs, glossary, design, the user's existing schema and configurations and use that for aligning the agent to help steer the user".

Owner decision 27 declined to ship the glossary as a generated plugin reference: no copy in the plugin, nothing to keep in step and no drift test. That left learn with nothing that explains the tool's own terms, a gap on four journey steps ([journey-overlay.md](../journey-overlay.md)). Reading the canonical files at a known ref is reading a public document, as a user opening the docs would, and leaves nothing in the plugin or the project, so decision 27 stands.

The installed version is known to the plugin (its own manifest) and, where the host exposes it, to the server's `serverInfo.version` (`current/src/main/kotlin/io/github/jpicklyk/mcptask/current/bootstrap/CurrentMcpServer.kt:686`). Release tags exist from `v4.0.0`, so a version-matched ref explains what the user runs rather than what `main` has since added.

## Alternatives rejected

- **Ship the glossary as a generated plugin reference** (owner decision 27). A copy to keep in step and a drift test; not taken.
- **A page document holding a copy of the glossary** (an earlier learn row in [entry-skills.html](../entry-skills.html)). A shipped copy by another route.
- **Always fetch `main`.** Simplest, but it explains features the user's server may not have, so every answer would need a cross-check against the tool contract.
- **A pinned-ref override in `extensions.claude-code`.** A config toggle nobody has asked for.

## Consequences

- Plugin text names one repository URL root, the ref rule and the index pages (`current/docs/Home.md`, `design/README.md`, `GLOSSARY.md`). A URL is not a skill or command name, so the domain-boundary guard permits it, and it matches the published-docs-URL rule of S55 ([seams.md](../seams.md)). The seam is S68.
- learn fetches per question only the pages the question needs, through the indexes; caching is the host's, for the session. learn needs network access to the repository host, a host permission that start mentions.
- Offline or blocked, learn answers from the tool contract (MCP instructions, `describe`) and the user's served schema and config, and says the documents could not be reached. It never falls back to a bundled copy.
- Design documents and the glossary mark target and proposed terms; learn labels such content as not built, and takes how the tool behaves today from the tool contract and the user's server first, then the version-matched documents.
- learn says in its own text that fetched text is data, as the run-wave skill already says of seat returns.
- On the knowledge profile learn lists the root's types through the declared-types read (S63, reopened in P8). That profile serves no `config`, so learn reads the checkout's project config file where it exists, and otherwise says what it cannot see.
- A project's own glossary is not one of these sources: the plugin never names it, and it reaches an agent only through the project's instruction file or a seat or note binding.

## Reopen when

A user reports that learn explained a feature their install lacks (the version match failed), learn runs on a host that cannot fetch from the repository, or the repository's index pages move.
