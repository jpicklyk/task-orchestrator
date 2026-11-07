# director-indicator

An opt-in Claude Code mod that makes a **director-mode** session unmistakable. With several desktop sessions open it is easy to forget which ones entered `/task-orchestrator:director`; this plugin shows it on the session itself:

- **Footer label.** `director` joins the dim mode labels at the right of the prompt footer (where `focus` and `memory paused` appear), on the terminal and the desktop app.
- **Desktop backdrop.** The desktop app's backdrop turns a warm amber with a faint `DIRECTOR` watermark while the mode is on, so the window reads as "director" at a glance even from a thumbnail. Turn it off in the plugin's settings (`backdrop: false`) to keep only the label and toasts.
- **Toasts.** `Director mode on since 14:02` when the mode starts, `Director mode off` when it ends.

It is a separate plugin from `task-orchestrator-mod` and shares nothing with it.

## Install

```
/plugin install director-indicator@task-orchestrator-marketplace
```

It needs the `task-orchestrator` plugin, whose `director-guard` hook keeps the marker this plugin reads. It relies on the early-access Claude Code function-hooks API and runs on Claude Code 2.1.317 or later (the `Backdrop` component arrived with that build; the footer label and toasts also work on 2.1.286 and later).

## How it decides

The `task-orchestrator` plugin's `director-guard` hook writes a per-session marker, `<tmp>/task-orchestrator/<session_id>/director.json`, when the director skill is invoked and removes it on `/task-orchestrator:director off`. That file is the single source of truth here: the mod checks it at session start, after every `Skill` tool call that names `task-orchestrator:director`, and at the end of every turn. It never guesses from its own memory of a call, so a resume, a hot reload or an `off` from any path all agree with the guard.

The indicator makes no Task Orchestrator calls and writes nothing but its own `$.state` value (`director`: `{ since } | null`).

## Developing

```
claude plugin validate claude-plugins/director-indicator
node scripts/ci/check-task-orchestrator-mod.mjs claude-plugins/director-indicator
```

The second command is the CI gate: validate, the `=?` unresolved-matcher check, a source scan of `on(...)` matchers, then `claude plugin test`. The test kit has no `fs`, `env` or `session` nouns, so the marker probe is covered live rather than in the kit; the tests cover the footer rewrite and the pure helpers in `src/model.ts`.

Rules that hold here as in every mod: matchers are literals in the registering file (`src/indicator.ts`), `$` never leaves that file, and the atom is declared beside its reads and writes.
