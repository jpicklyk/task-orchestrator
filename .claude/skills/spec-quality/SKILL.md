---
name: spec-quality
description: Specification quality framework for planning. Defines the minimum bar for what a plan must address — alternatives, non-goals, blast radius, risk flags, and test strategy. Referenced by schema guidance fields during queue-phase note filling. Use when filling requirements or design notes for any MCP work item.
user-invocable: false
---

# Specification Quality Framework

This skill defines the minimum thinking floor for plans and specifications. The sections
below represent what every plan must address. They are not a ceiling — if the problem
demands additional analysis, add it. But these areas must not be skipped.

The value of a spec is entirely in the thinking it forces before code is written. If a
section doesn't change how you'd approach implementation, it isn't earning its place.
Every sentence should either prevent a mistake or force a decision.

---

## Specification Disciplines

These are the required areas of analysis. Each one exists because skipping it leads to
a specific, recurring class of failure.

### Alternatives Considered

Evaluate at least two real approaches. "Do nothing" always counts as one. For each
alternative, state what it would look like and the specific trade-off that led to its
rejection. If you can only think of one approach, you haven't explored the solution
space — step back and look for a fundamentally different angle.

The point is not to document alternatives for posterity. It's to catch yourself before
committing to an approach that has a better option sitting next to it.

*Anti-pattern: strawman alternatives.* "Alternative: rewrite everything from scratch.
Rejected: too much work." This doesn't force any real thinking.

### Non-Goals

Name what someone might reasonably expect this work to include but that is deliberately
excluded. If you cannot name a single non-goal, the scope is not tight enough.

Non-goals prevent scope creep during implementation. Without them, agents tend to
gold-plate — adding adjacent improvements that weren't asked for and that introduce
unplanned risk.

### Blast Radius

Identify every module, file, and interface affected by the change. Trace downstream
consumers — if you change a repository method signature, what tools call it? If you
change a domain model default, what tests assume the old value?

This analysis exists to catch "I didn't realize changing X breaks Y" before it happens.
Read `references/project-concerns.md` for cross-cutting constraints specific to this
codebase that frequently expand blast radius in non-obvious ways.

### Risk Flags

Call out the one or two things most likely to go wrong. These might be areas of tight
coupling, migration complexity, concurrency concerns, or simply parts of the codebase
you don't fully understand yet.

The purpose is to focus review attention where it matters and to make uncertainty
explicit rather than hidden.

### Test Strategy

Every plan must include a concrete test strategy. This is not "add tests" — it's a
specific accounting of what will be verified and how.

**Required coverage areas:**

- **Happy paths** — the primary use cases the change enables. These confirm the feature
  works as intended under normal conditions.
- **Failure paths** — what happens when inputs are invalid, dependencies are missing, or
  operations fail. These confirm the system fails gracefully rather than silently
  corrupting state or throwing unhandled exceptions.
- **Edge cases** — boundary conditions specific to the change. Examples: empty collections,
  null/optional fields, maximum depth limits, circular references, concurrent access.
  Think about what a user or caller could do that you didn't explicitly design for.

For each area, name the specific scenarios you'll test. "Test edge cases" is not a
strategy. "Test that circular parent references are detected and rejected with a clear
error" is.

If the change modifies shared interfaces (domain models, repository contracts, tool
parameters), note which existing tests may break and how you'll handle that — update
them, or confirm they still pass with the new behavior.

---

## Completion Checklist

Validate spec completeness before advancing past queue phase:

- [ ] At least 2 real alternatives evaluated (not strawmen)
- [ ] At least 1 non-goal named (scope boundary explicit)
- [ ] Downstream consumers of changed interfaces traced
- [ ] 1-2 concrete risk flags identified
- [ ] Test scenarios named for happy paths, failure paths, and edge cases
- [ ] Shared interface breakage assessed (if applicable)

---

## Using This Framework

This framework sets a floor. The disciplines above are the minimum required analysis.
Depending on the complexity of the work, additional analysis may be warranted —
performance implications, migration strategies, API compatibility concerns, or
anything else that would change the implementation approach if examined carefully.

Add whatever the problem demands. The goal is a plan that lets someone implement the
change confidently, understanding not just what to build but why this approach was
chosen and what to watch out for.
