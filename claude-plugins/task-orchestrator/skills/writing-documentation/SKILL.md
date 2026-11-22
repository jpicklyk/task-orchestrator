---
name: writing-documentation
description: "Decides what a document holds and what it leaves to the code: names the audience first, keeps the why, the invariants, the traps and the knowledge that lives outside the code, points at the defining file instead of copying values, and cuts what no reader needs. Use when writing, rewriting or cutting a README, an architecture or contributor guide, user documentation, or an agent instruction file such as CLAUDE.md or AGENTS.md, and when a change makes a document's subject obsolete."
user-invocable: false
---

# Writing Documentation

Where the project documents its own conventions for documents, they take precedence over this skill.

## Before writing

- Name the document's audience (user, contributor or agent) and its one type. A document that serves two audiences is split into two.
- For each section ask: could a reader with the code and a search find this in a minute? If yes, cut it.
- Cut before you update. A change may cut a section or a whole document, and should when its subject is gone. A living doc that keeps describing something that no longer exists misleads more than a gap does.

## What a document keeps

What the code cannot say:

- The why: decisions, rejected options, consequences.
- Invariants and boundaries, including an absence ("nothing here talks to the network").
- Traps, where reading the code would mislead.
- Knowledge outside the repository: how to verify, deploy and run the process around the code.
- A coarse map of modules by searchable name, not a tour of files.
- A user's tasks: getting started, how-to, troubleshooting, public reference.

## What it never copies

- A count, default, limit, version or inventory: point at the defining file or symbol, or generate the block.
- A file and line: name the symbol instead of linking a location that moves.
- A fact that has a home elsewhere: link to the home. One fact, one home.

## Agent instruction files

- Hold only what an agent cannot discover from the code. No overview of the tree and no list of files.
- A rule that must always hold belongs in an automated check; prose states it only to say where the check lives.

## Records

- A decision goes to a decision record, not into a guide that will be edited.
- A changelog entry is history: it is not edited to match the code later.
