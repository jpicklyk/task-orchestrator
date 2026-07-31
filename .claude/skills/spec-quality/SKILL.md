---
name: spec-quality
description: Specification quality framework for planning. Defines the minimum bar for what a plan must address — alternatives, non-goals, blast radius, risk flags, and test strategy. Referenced by schema guidance fields during queue-phase note filling. Use when filling feature-summary, task-scope, diagnosis, or other queue-phase specification notes for any MCP work item.
user-invocable: false
---

# Specification Quality Framework

This skill defines the minimum thinking floor for plans and specifications. The sections
below represent what every plan must address. They are not a ceiling — if the problem
demands additional analysis, add it. But these areas must not be skipped.

**Where this applies at each level:** for a `feature-implementation` parent, the queue-phase
`feature-summary` note stays lean by design (goal, findings→tasks table, dependency edges,
a pointer to non-goals — target under 2k chars) and does not carry the full disciplines below.
The disciplines below apply in full to each child's `task-scope` note (or to `specification`/
`diagnosis` notes on schemas without a parent/child split) — that is where alternatives,
blast radius, risk flags, and test strategy must actually be worked through.

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

### Open Decisions

If the spec lists open decisions, naming alternatives, or "options under consideration,"
resolve every one before materializing MCP work items or dispatching implementation.
A spec with unresolved choices left in it is not ready to dispatch.

Mid-implementation pivots cost 5-10× the time of a pre-dispatch user round-trip. Once
an agent is in a worktree, a naming change or interface change requires unwinding
partial code, retracting commits, re-spec'ing the work, and re-dispatching. Resolving
the same question before dispatch is a single short conversation.

The orchestrator should pause for a user round-trip rather than dispatch with ambiguity.
A short pause now is cheaper than a partial implementation later.

If a decision genuinely cannot be resolved at planning time (e.g., depends on a
measurement only available post-implementation), it isn't an "open decision" — it's a
deliberate two-phase approach. Document it as such, scope the first phase explicitly,
and create a separate work item for the second phase. Don't leave the choice latent
in the spec.

### Cite Contracts, Don't Restate Them

When a spec prescribes call sequences, API semantics, or any fact owned by another
artifact (a tool description, a source file, another spec), cite the authoritative
location and direct the implementer to verify against it before relying on it. A
restated copy drifts from its source — and a spec's confident restatement reads as
authority to the agent that implements it faithfully.

If the spec claims something was verified, name exactly what was checked. "Verified
against source" that checked one path while prescribing another is how a partially
verified claim ships a defect (two sessions of evidence: task-scope facts contradicting
target files, 2026-07-27; a design prescribing `advance_item` trigger sequences that
gate-block, 2026-07-31).

### Verification Commands

Before writing a verification command into a spec, plan, or skill, run it twice: once
on the target platform against known-good input (it must succeed) and once against
known-bad input (it must fail). A command that has not been shown to fail on bad input
has not been shown to verify anything — a check that silently always passes is worse
than no check, because it is trusted. Record the command in the form actually executed,
not a reconstruction.

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

If the blast radius touches a surface with an automated budget, ceiling, or quota test
(e.g. `ToolTokenBudgetTest`), measure current headroom while writing the spec and record
both the measured values and the predicted post-change values. Do not defer the
measurement to implementation — by then the scope decision is already made.

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

**Give every scenario a stable numbered id and an oracle source.** Number scenarios
sequentially (S1, S2, S3…) across all three coverage areas, and for each one record the
oracle source the expected result comes from — a spec clause, a stated algorithm, or an
external reference. Never "what the code returns," and never just the ticket's worked
example restated as if it were independent confirmation. Stable ids let downstream
artifacts map coverage back to this spec without duplicating it — most directly the
`needs-test-author` trait's `test-plan` and `test-manifest` notes, which reference these
same ids; cite the trait by name rather than restating its note schema here.

---

## Completion Checklist

Validate spec completeness before advancing past queue phase:

- [ ] At least 2 real alternatives evaluated (not strawmen)
- [ ] No "open decisions" or "options under consideration" sections remain in the spec
- [ ] At least 1 non-goal named (scope boundary explicit)
- [ ] Downstream consumers of changed interfaces traced
- [ ] Contracts cited (with location), not restated; any "verified" claim names what was checked
- [ ] Verification commands proven (succeed on good input, fail on bad input)
- [ ] Automated budget/ceiling headroom measured, if the blast radius touches one
- [ ] 1-2 concrete risk flags identified
- [ ] Test scenarios named for happy paths, failure paths, and edge cases
- [ ] Scenarios carry stable numbered ids (S1…) with an oracle source recorded for each
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
