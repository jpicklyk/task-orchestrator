# live-checks

A change to behaviour seen in a running session or server process is not proven by a unit test alone.
A State matrix lists what must be run live, and an open entry holds the item in work.

**State matrix.** Required when a change alters behaviour observed in a running session or server
process: plugin hooks, hook-injected context or skill text, output styles, mod modules, server startup,
config load or hot-reload, transport or REST behaviour. Otherwise the spec carries one line,
`State matrix: n/a - <reason>` (docs-only, test-only, pure refactor, domain logic fully unit-tested,
agent-read process prose). A missing line is an omission, not a decision. The matrix lives in the
item's spec note (`task-scope`, `diagnosis` or `specification`); a plan may carry it or point at it,
and a dispatch contract only points at it and never copies its cells.

- **Rows (minimum):** fresh session (no prior markers or state); configured; unconfigured, one row per
  fallback the change reads, each with that fallback removed alone (for example no `project.rootId`,
  empty API URL, no `config.yaml`); reload or restart mid-use (plugin reload, server restart, config
  hot-reload); resume after compaction or `--resume`. An item may add rows; none is dropped silently.
- **Columns:** terminal (interactive CLI), desktop app, headless `claude -p`.
- **Cells:** exactly one of `test: <test file or S#>` (only when that test's fixture models the row's
  state), `live-owed: <command or steps, in the form that will be executed>`, or `n/a: <reason>`. A
  desktop cell an agent cannot drive is `live-owed` (to the user, or carrying a `deferred: <item id>`
  suffix on its Live-owed entry), never `n/a` merely because an agent cannot run it.

**The Live-owed hold.** The item's `session-tracking` note lists every `live-owed` cell on a
`**Live-owed**:` line. Each entry ends `run <date>: <result>` or `deferred: <item id>`; a deferral
names a tracked item, never prose. Before the work-exit `advance`, the orchestrator reads that line.
An entry with neither suffix is open and holds the item in work until it is run or deferred. The
orchestrator writes the line from the spec note's matrix when it first fills `session-tracking`; a
matrix with `live-owed` cells and no `**Live-owed**:` line counts as an open entry. The check sits at
work exit so it holds every item, whether or not its schema has a review phase.

**Post-merge holder.** A live check that needs a refreshed plugin cache or a user opt-in cannot run
before its feature PR merges. After the merge, its holder item is created with type `plugin-change`.
The holder's specification opens with this next-session preamble: (1) purge the plugin cache and
update the marketplace, per `claude-plugins/CLAUDE.md` "Plugin Discovery and Cache Refresh"; (2)
once the next session starts, grep the cached files for one string the change added, and name that
string in the specification.

The same holder is this project's post-merge step `live-acceptance` (`run-profile.json` `postRun`,
`when` `post-merge`). After the feature PR has merged (under review handoff the project's finalization
carries the same step), if the only non-terminal children are live checks that need a refreshed plugin
cache or a user opt-in:

- (a) Create one holder item (type `plugin-change`) titled `Post-merge live acceptance: <feature title>`
  under the feature's parent, its `specification` filled at creation and opening with the preamble above.
- (b) `deps_write` `create` a `relates_to` edge from the holder to the feature.
- (c) Re-parent each such check under the holder (`items_write` `update` `parentId`).
- (d) Add one line `Post-merge live acceptance: <holder short id> (<check short ids>)` to the feature's
  `session-tracking`, token-less when that note was created token-less (it stays re-writable after the
  runs close), else in `orchestrator-confirmation`, since a note created under a coordinator token takes a
  re-edit only inside an open run.
- (e) Complete the feature.

The preamble always gets the user's opt-in for any check that runs the Workflow tool, as its step (3).
Then one line per re-parented check. The next session runs the checks, fills the holder's
`session-tracking` and completes it. Create the holder and re-parent the checks before (e): a terminal
parent takes no new children. In shared mode, where the feature review runs before the feature PR is
pushed, the split and the feature's complete wait for the merge. No server gate enforces the split: it
is discipline.
