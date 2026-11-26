# 0001 Two session layers: the orchestrator drives, the director decides

**Status:** Accepted | **Date:** 2026-11-23, amended 2026-11-26 | **Source:** glossary PRs #754 and #757; [GLOSSARY.md](../../GLOSSARY.md) "Session layers"; the former root `CLAUDE.md` section "Session Layers", whose rules this record and [0002](0002-director-on-orchestrator-tools.md) now hold

**Amended 2026-11-26:** the rules across the layers, which root `CLAUDE.md` "Session Layers" held, moved here when that file was cut to what every session needs; the altitude rule is in [0002](0002-director-on-orchestrator-tools.md). The run token is named as the run's identity, never as a seat. Why: these rules are decisions with reasons, and an instruction file gives them no record and no supersede path ([0018](0018-content-placement.md)).

## Decision

Above the seats there are exactly two session layers. The orchestrator drives work (plans, sizes, dispatches seats, advances, resumes, carries decisions up) and never answers a decision. The director decides on the user's behalf within each decision class's authority and never drives. "Coordinator" is retired as a concept: an orchestrator scoped to one run is still an orchestrator.

### Rules across the layers

A change to director, orchestrator or run-wave behaviour is judged against these.

- **Content flows down, pointers flow up.** A layer hands the one above ids, counts and short reasons, never note bodies, diffs or transcripts. Notes are the report.
- **Server records are the state; messages are wake-ups.** Every layer restarts from server state (runs, seats, decisions, notes) plus its run files; nothing needed to resume lives only in a conversation. That is why a run's orchestrator may be a disposable background agent: it resumes from the run view and its run files.
- **Driving and deciding stay apart.** A seat or the orchestrator raises a decision; the user or the director answers it, as the decision class allows. The orchestrator never answers one itself; in orchestration mode it records the user's own words as the rationale. The `decision-guard` hook denies a subagent's `runs_write` `answer`, `ack`, `pause`, `resume` and `reissue` (`claude-plugins/task-orchestrator/hooks/decision-guard.mjs:13`). An answer never resumes the item: the orchestrator does, inside a run or outside one.
- **Identity is declared.** The director is a call with no seat token. A run's orchestrator holds the run token (`RunPermissions`). The director path is not a security boundary, so decision authority belongs in a credential's capabilities and scope ([0002](0002-director-on-orchestrator-tools.md), [0003](0003-role-neutral-decisions-surface.md)).
- **Cost is measured, not self-reported.** Model and effort come from `claude-plugins/task-orchestrator/dispatch-policy.json` per class of work, and every Task Orchestrator dispatch names both. Token cost is read from Claude Code transcripts after a run into `delegation-metadata`; the session retrospective reports the share of the session's token volume, by meter and model, that notes record. The tool holds no prices.

## Context

The original design had three tiers (director, run coordinator, seats), and "orchestrator" meant the whole plugin side. In practice the user's own session and a background agent handed a run did the same job, so "coordinator" and "orchestrator" named one thing twice and confused every discussion of the layers. The director exists for four reasons: a lean long-lived context, one decision inbox across runs, staying free while work runs, and more parallelism than one session can hold.

## Alternatives rejected

- **Keep three named layers.** The coordinator and the orchestrator did the same work; a third name only added confusion.
- **"Lead" and "run lead".** Considered and dropped: both describe the orchestrator.
- **Let the orchestrator answer routine decisions.** Driving and deciding stay apart, so the user (or the director) always owns the answer.

## Consequences

- Session modes are orchestration mode (the default: the user's session is the orchestrator and the user decides) and director mode. An open run is an indicator, not a mode.
- The run token still carries the old term in code: its deprecated wire alias `coordinatorToken`, and its stored value `coordinator`, held by the constant `RunSeat.ORCHESTRATOR` (`current/src/main/kotlin/io/github/jpicklyk/mcptask/current/model/run/RunSeat.kt:88`); see [0005](0005-renames-with-deprecated-aliases.md).
- A seat may not be named `director`; `orchestrator` is a reserved seat name. Superseded in part by [0016](0016-seat-capability-and-orchestrator-notes.md): `orchestrator` is a reserved note owner, never a seat, and a seat of that name warns for one release, then is refused.

## Reopen when

A third kind of session work appears that neither drives nor decides.
