# 0006 Method words are schema vocabulary, not tool concepts

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** PR #771; [GLOSSARY.md](../../GLOSSARY.md) "Readiness and acceptance"

## Decision

Words that belong to one delivery method (epic, story, charter, increment, and open question as a trait name) are never Task Orchestrator concepts. A project's schema may name its item types and notes that way, and the tool reads them only as types and notes. The tool's own concepts stay method-neutral: an item, a node of the tree, its notes, a decision, a ruling, refined, acceptance.

## Context

The journey work borrowed agile words to describe incremental delivery, and the glossary briefly defined Increment, Story and Charter as if the tool owned them. A project with a different method, or none, would then meet terms that do not describe its work, and the plugin would start assuming note keys such as `charter`.

## Alternatives rejected

- **Define the agile words in the glossary.** Ties the tool to one method.
- **Keep Increment as a concept.** Folded instead: any node with a goal note can be accepted, at any depth.

## Consequences

- Plugin skills never assume a method's note keys; they read the schema to learn which notes to fill.
- An incremental-delivery pattern, with its own words, belongs in the template library ([0009](0009-setup-applies-tested-templates.md)).
- The light template must work without any method word; if it ever needs one, a method word has leaked into the tool.
