# 0002 The director uses the orchestrator's tools; the guard hook enforces altitude

**Status:** Accepted, amended by [0014](0014-director-reads-skills-runs-plugin-scripts.md) | **Date:** 2026-11-23, amended 2026-11-25 | **Source:** Entry Skills Design review (option C); [GLOSSARY.md](../../GLOSSARY.md) "Director mode", "Director profile"

## Decision

Director mode runs on the same connection and tools as the orchestrator. The `director-guard` hook is the one place altitude is enforced: it denies body reads and every write that drives work, and allows read-only scripts and background dispatch. The director tool profile (`/mcp/director`) stays served, and the hardened director agent stays available, but neither is the default entry any more.

The guard draws three lines:

- **Reads.** The director is meant for long-running automation, so what enters its context comes in a known, compact shape: board reads, ids, counts, state and short reasons, one decision request in full at a time, run rollups, and the fixed-shape summaries of the agents it delegates to. A read is denied when it can return body text, however short: `notes_read` and `docs_read` `get`, `items_read` `search` with snippets, `schema` with guidance text, and `config`. Their metadata forms run: note and document lists, document history and links, `search` with `snippet: false`, `schema` without guidance, `related`, and the gate state, health view, ranked next items and transition history.
- **Evidence travels with the decision.** The director decides from the decision request alone. Whoever raises a decision (a seat, an orchestrator, an entry skill on the orchestrator's behalf) puts the evidence the answer needs in the request's `context`, as a short summary, and points `links` at the notes it came from. A body the director would need is a gap in the raise, not a reason to read it.
- **Writes.** The director never drives work. Task Orchestrator writes are denied, except the decision ops (`answer`, `ack`, `withdraw`) and run control (`pause`, `resume`, `close`, `reissue`). Opening a run is denied: a session that opens a run holds its token and is that run's orchestrator.
- **Skills.** The `Skill` tool is open. Loading a skill only adds instructions; every tool the skill then calls is checked on its own call, so the read and write lines above hold whatever skill is loaded.

A pull request's description (`gh pr view` `body`) is allowed: it is a summary written for people, not a note body, diff or test output.

## Context

Director mode was hardened three ways: the director profile, the guard hook and the hardened agent's tool list. Together they forced every entry skill into background delegates, blocked helper scripts, and left it unproven whether delegates could reach the orchestration connection. The owner's aim for the director is to offload research to a background agent and answer from its concise result; the hardening fought that.

An earlier ruling (2026-11-15) kept the `/mcp/director` endpoint for flexibility, other platforms and large deployments. This decision narrows that ruling and does not reverse it.

The 2026-11-25 amendment (edited in place, once, because director mode had not shipped in a public build): the guard still allowed only the director skill and the model-effort reference through `Skill`, which kept every entry skill unusable, and it did not check writes at all, so the glossary's "never drives work" had no enforcement. Opening `Skill` without denying writes would have let a loaded skill drive work; the write line is what makes an open `Skill` safe. The user journeys (`design/journey-sequences.md`), written after the guard, put note reads in the orchestrator's lane and the answer in the director's: in director mode the accept step's notes are read by a background orchestrator, and the director answers. A category ban on reads was also denying metadata reads (note and document lists) that return no body at all.

## Alternatives rejected

- **Keep the three-way hardening as the default.** It made every other entry skill unusable from director mode.
- **Remove the director endpoint.** Rejected on 2026-11-15 and not reopened: other platforms and deployments still need an altitude-limited tool set.
- **Allow a body read when one bounded read answers.** Small reads add up over a long run and leave the context as loose body text rather than a sequence of board reads and decisions; the evidence belongs in the decision request instead.
- **A skill allow-list or deny-list.** Every new skill would need a guard edit, and a skill name says nothing about which tools it calls; the per-call checks already decide that.

## Consequences

- Altitude stays the rule; only the enforcement point moves to one hook.
- The director path is not a security boundary. Decision authority lives in a credential's capabilities and scope ([0003](0003-role-neutral-decisions-surface.md)).
- Raisers do not fill `context` and `links` with evidence yet; until they do, a decision that needs evidence goes to a background agent that reads the notes and returns a fixed-shape summary.
- Read-only scripts are allowed by this decision but the guard still runs only a fixed set of read-only shell commands; helper scripts stay denied until "read-only" is defined for them.

## Reopen when

A host without hooks needs director mode, so the guard cannot run there.
