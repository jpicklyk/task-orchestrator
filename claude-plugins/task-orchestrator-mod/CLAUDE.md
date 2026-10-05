# task-orchestrator-mod: maintainer rules

Rules for editing this plugin. They come from live and review findings on the early-access function-hooks API; each cites the work item that found it. User documentation is in [README.md](README.md). Plugin versions are bumped by `/prepare-release`, not here (see [`../CLAUDE.md`](../CLAUDE.md)).

## Hooks

1. **Matchers are literals or `const`s declared in the registering file.** A constant IMPORTED from another module shows as `=?` in `claude plugin validate` and the hook never fires (206a22d3). A constant declared in the registering file resolves even when it is exported (`export const PANE_ID` works). Gate: `claude plugin validate <dir> | grep hooks: | tr ',' '\n' | grep -c '=?'` must print 0.
   - Gap: the validator truncates the hooks line at `[+N chars]`, even with `--json`, so matchers in the tail go unchecked (c432b40f). Pair the `=?` grep with a source grep for imported constants used in `on(` calls.
2. **Never pass `$` into an imported function.** Keep `$` in the registering file and keep helpers pure, using the `graphIo($)` / `guardIo($)` pattern (0a40a620).
3. **Atom locality.** A `read` or `update` atom ref lives in the same file. A consumer declares its own atom with the same literal (see the top of `src/band/index.ts`).
4. **One unmatched hook per event across the whole plugin.** Current owners: `session.start` is graph-data; `prompt.submit` and `turn.complete` are the band; `agent.spawn` is graph-pane; `classic.Stop` is retro; `classic.PostToolUse` and `classic.SubagentStop` are phase-guard. New hooks use matchers.
5. **Do not use your own plugin's `$.state.set` as control flow.** It does not reliably reach your own `state.set` hooks. Export `$`-free request functions instead (`requestLiveSync`, `requestReconnect`, `requestRefresh`; 5fcac8f4).
6. **A `state.set` hook's `await next(e)` resolves `{isSet, version}`**, not `.value.isSet` (cac35728). The test kit cannot drive `state.set` hooks, so this only shows live.
7. **Dedupe flags never go on PreToolUse.** The flag would land inside `tool_input`.

## Rendering

8. **Element props are validated only at render.** An unsupported prop makes the engine refuse the whole tree and draw its own. Check props against the build's `d.ts` and mount-test on terminal and desktop.
9. **Svg needs explicit pixel width and height.** A percentage falls back to about 300x150. Each side must be at most 4096 (`SVG_PX_LIMIT`). The desktop has no Svg background in dark theme, so paint one.
10. **Tree budget.** The host binds 100,000 characters, 20,000 nodes and 32 levels deep; the mod budgets 90k (`src/graph-pane/budget.ts`). Measure unit costs with real 36-character UUIDs, open panels and redraws. Short test ids underestimated the cost and caused three review rounds (c858b28d).

## Files and tests

11. **CRLF.** Working-tree files are CRLF with `core.autocrlf=true` and LF in the index. Normalise `\r\n` when patching, and use function replacers (`s.replace(a, () => b)`) because a string replacement expands `` $` `` and `$&`.
12. **`src/lib/yaml-lite.mjs` is a copy** of the task-orchestrator hook's. Keep the two in sync.
13. **`.claude-plugin/types/` is generated** by the engine on load and is git-ignored.
14. **Testing:**
    - `CLAUDE_CODE_ENABLE_FUNCTION_HOOKS=1 claude plugin test claude-plugins/task-orchestrator-mod`
    - `claude plugin validate claude-plugins/task-orchestrator-mod`
    - The kit has no state, clock, fs or mcp nouns on the test `$`; use rigs.
    - `claude -p "/cmd"` does not dispatch mod commands.
    - If `claude plugin test` refuses with "hooks modules are turned off", the cause is the cached rollout flag `tengu_plugin_hooks_modules` saved off in `.claude.json`. Fix it with `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` or a fresh `CLAUDE_CONFIG_DIR`; `CLAUDE_CODE_ENABLE_FUNCTION_HOOKS` does not clear it. Do not flip it with an ad-hoc headless `claude -p` run (6a976f10 process note).
    - CI runs `node scripts/ci/check-task-orchestrator-mod.mjs` (validate, the `=?` gate, a source scan of `on(` matchers, then the tests) with CLI 2.1.286, pinned in [`.github/workflows/task-orchestrator-mod.yml`](../../.github/workflows/task-orchestrator-mod.yml). The script sets the env above itself, so running it locally is the same run.

## Loading and refresh

See "Mod plugin: loading and refresh" in [`../CLAUDE.md`](../CLAUDE.md). The mod has no skills; its runtime commands are `/to-graph` and `/to-band`.
