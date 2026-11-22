---
name: implementation-standards
description: "Engineering standards for building a code change: reuse before writing, matching local patterns, the smallest change that meets the requirement, error handling and logging, behaviour-preserving refactoring, safe state and released resources, documentation kept in step with behaviour, and verifying the change before calling it done. Use when writing new production code or changing existing code (a feature, a bug fix, a refactoring, a change to shared code), and before reporting the change as done."
user-invocable: false
---

# Implementation Standards

## When it applies

These standards apply whenever you write new production code or change existing production code:
a feature, a bug fix, a refactoring, or a change to shared code that other code calls. They
describe how the change is built, not what it must do; the requirement says what it must do.

Where the project has its own written standards or conventions, they take precedence over this
skill wherever the two differ.

## Method

Work through these steps in order. Each step is short here; its reference file holds the detail
and ends with the signs a reader of the diff or of the change's report can use to tell it was
followed.

1. **Orient and search for what exists.** Before writing anything, search the code for what already
   does the job, by the nouns of what the code does, in shared modules and in the code's
   neighbours. Detail: [reuse and local patterns](references/reuse-and-local-patterns.md).
2. **Fit local patterns.** Match the naming, layout, error style, logging style, test style and
   dependency choices of the code around the change. Detail:
   [reuse and local patterns](references/reuse-and-local-patterns.md).
3. **Make the smallest change that meets the requirement.** Change only what the requirement
   needs: no unrelated cleanup, no speculative abstraction, no dead code, and no silent departure
   from the agreed design. Detail: [minimal change](references/minimal-change.md).
4. **Handle errors.** Expected failures are handled or returned with context, programming errors
   fail fast, nothing is swallowed, and each failure is logged once. Detail:
   [error handling](references/error-handling.md).
5. **Restructure safely.** When the change needs restructuring, pin current behaviour first and
   keep mechanical and semantic changes in separate steps. Detail:
   [refactoring](references/refactoring.md).
6. **Check state and resources.** Make the change safe when calls overlap, repeat or fail half
   way, release every resource on every path, and keep growth bounded. Detail:
   [state and resources](references/state-and-resources.md).
7. **Update what describes the change.** When the change alters behaviour that a document, a
   comment or an example describes, update it in the same change, or say in the change's report
   that it was not done and why. Detail: [documentation](references/documentation.md). When the
   change writes, reworks or cuts a document, follow `task-orchestrator:writing-documentation`
   for what the document holds and what it leaves to the code.
8. **Verify and report.** Verify your own change before calling it done, and say what you ran,
   what it showed, and what you did not run and why. Detail:
   [verification](references/verification.md).
