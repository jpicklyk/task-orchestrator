# Retrospective scenario eval results

One row per graded stage: date, the checkout sha the plugin was loaded from, the model ids observed in the session
transcripts (`main:` is the headless session, `fork:` a subagent or skill fork), and the result. A live stage runs once
and a failure is recorded as it happened, never retried. How to run: [README.md](README.md).

| stage | date | sha | observed model | result |
| --- | --- | --- | --- | --- |
| 1-H1 | 2026-11-20 | b38d3c50 | n/a (hook, no model) | pass |
| 1-H2 | 2026-11-20 | b38d3c50 | n/a (hook, no model) | pass |
| 1-L1 | 2026-11-20 | b38d3c50 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass |
| 2-L2a | 2026-11-20 | b38d3c50 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | fail: D2, D3 |
| 2-L2b | 2026-11-20 | b38d3c50 | n/a | not run: the second feature could not be seeded (harness defect, fixed after: the plugin's config-sync hook replaced the seeded schemas with the working directory's config) and the stage needs the filed issue 2-L2a did not produce |
| 2-L2a | 2026-11-20 | db19b02b | main:claude-opus-5-5,main:claude-sonnet-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | fail: D2, D3 (rerun after the Step 5e wording fix) |
| 2-L2b | 2026-11-20 | db19b02b | main:claude-opus-5-5,main:claude-sonnet-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | fail: D5 (rerun after the Step 5e wording fix) |
| 2-L2a | 2026-11-20 | 50485ea2 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | fail: D2, D3 (rerun after bug-report gained --root/--run) |
| 2-L2b | 2026-11-20 | 50485ea2 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | fail: D5 (rerun after bug-report gained --root/--run) |
| 2-L2a | 2026-11-20 | ff47823b | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass |
| 2-L2b | 2026-11-20 | ff47823b | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass |
| 2-L2a | 2026-11-20 | b2019c25 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | fail: D3 (rerun against --product and the env-var gate) |
| 2-L2b | 2026-11-20 | b2019c25 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass (rerun against --product and the env-var gate) |
| 3-L3 | 2026-11-20 | b38d3c50 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass |
| 4a | 2026-11-20 | b38d3c50 | fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass |
| 4b | 2026-11-20 | b38d3c50 | main:claude-opus-5-5,fork:claude-opus-5-5,fork:claude-sonnet-5-5 | pass |
| 4a (red proof) | 2026-11-20 | b38d3c50 | fork:claude-opus-5-5,fork:claude-sonnet-5-5 | red proof NOT held: T1 passed with the stale rule cut |

## Findings

- **2-L2a (product signal).** The session created the bug observation and its evidence but never ran the bug-report
  command: its `actions-taken` says `No GitHub filing`, with the bug-report gate open. Step 5e of the
  skill words the upstream filing as optional ("can also be filed"), which the fork read as skippable. Step 5e was reworded in db19b02b to oblige the command when the gate is open; the rerun at db19b02b still failed D2, D3 and D5: the session again never ran the bug-report command (0 issue create, 0 comment calls), so the wording change did not move the behaviour.
- **4a red proof (eval sensitivity).** The cut rule was loaded (a plugin copy, confirmed in the fork transcript) and the
  title still did not start `[stale] `: the fork never weighed staleness for the manual run, so T1 passes with or without
  the rule. T1 cannot tell the two texts apart with this scenario; a scenario where the model must decide staleness (for
  example a scope whose last run closed over 7 days ago) needs a clock the server does not let the eval move.
- 1-clean through 4-stale used `task-orchestrator:current` (`--image`); the default tag `task-orchestrator:dev` was a June
  image that refuses `API_AUTH_MODE=none`.
- **2-L2a, 2-L2b at 50485ea2 (`--root/--run`).** `bug-report` no longer needs an input file, but the forked session still never ran it: its closing text says the defect was logged as an observation and "not filed on GitHub because I didn't check whether bug reports are allowed" (0 issue create, 0 comment calls; the transcripts hold `retro-metrics` and `retro-ack` Bash calls and no `bug-report` call). The D2 diagnostic earlier read `ran` from any transcript text that mentioned the command (the skill text the fork read); it now matches a Bash call only, so the `ran`/`never ran` wording of the earlier rows is not reliable. The failure is the fork not weighing Step 5e's filing, not a missing input.
- **2-L2a, 2-L2b at ff47823b (unconditional 5e).** Both pass once Step 5e, the checklist and Step 8b say to always run `bug-report` and let the command read the gate (image `task-orchestrator:current`; the rows above record the model ids the transcripts show).
- **2-L2a, 2-L2b at b2019c25 (`--product`, env-var gate; fake gh only, run once).** 2-L2b passes. 2-L2a fails D3 only: the last line of the session reads as a sentence about the fix, not the expected closing line; the bug-report command ran and the other checks held. Recorded, not retried.
