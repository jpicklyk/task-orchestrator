---
name: spec-quality
description: This codebase's planning supplement. Repo-specific cross-cutting constraints and worked examples that a plan for Task Orchestrator must account for, on top of the generic planning bar. Referenced by schema guidance fields during queue-phase note filling.
user-invocable: false
---

# Planning Supplement for This Codebase

This supplements the generic planning bar; it never repeats it. Read
`references/project-concerns.md` for the cross-cutting constraints specific to this codebase that
expand a change's blast radius in non-obvious ways.

## Worked examples

**Semantic claims carry a file:line**, per rule `review-cited-claims` points 1 and 4. Worked
case: the spec and a hook header said note attribution is shown only when the caller is ADMIN and
the flag is false; the code redacted only when `redactNoteAttribution && !isAdmin`, so it was
shown when ADMIN OR the flag was false. The 4.0 rule lives at
`platform/rest/ops/OpsAccess.kt:22` (`attributionVisible = !redactNoteAttribution || ADMIN`).
The error survived from spec to header because the sentence was written from intent and no line
was opened.

**Automated budget headroom is measured while writing the spec.** When the blast radius touches a
surface with an automated budget, ceiling or quota test (for example `ToolSurfaceGuardsTest`),
record the measured values and the predicted post-change values; do not defer the measurement to
implementation.

**Negative and exhaustive claims name their sweep** (rule `planning-sweeps` point 6). For
example: `grep -rn "createSuspend" current/src -> 0 hits (src/main + src/test)`.

## Rules this repo's plans point at

- State matrix: rule `live-checks` holds the rows, columns, cell forms and the Live-owed hold;
  a user-visible runtime change carries it, otherwise one line `State matrix: n/a - <reason>`.
- Scenario ids and oracle sources: rule `test-author` sections 2 and 3.
