# live-checks

A change to behaviour seen in a running session or server process is not proven by a unit test alone.
A State matrix lists what must be run live, and an open entry holds the item in work.

**State matrix.** Required when a change alters behaviour observed in a running session or server
process: plugin hooks, hook-injected context or skill text, output styles, mod modules, server startup,
config load or hot-reload, transport or REST behaviour. Otherwise the spec carries one line,
`State matrix: n/a - <reason>` (docs-only, test-only, pure refactor, domain logic fully unit-tested,
agent-read process prose). A missing line is an omission, not a decision. The matrix lives in the
item's spec note (`task-scope`, `diagnosis` or `specification`); a plan may carry it or point at it,
and a dispatch contract only points at it.

- **Rows (minimum):** fresh session (no prior markers or state); configured; unconfigured, one row per
  fallback the change reads, each with that fallback removed alone (for example no `project.rootId`,
  empty API URL, no `config.yaml`); reload or restart mid-use (plugin reload, server restart, config
  hot-reload); resume after compaction or `--resume`. An item may add rows; none is dropped silently.
- **Columns:** terminal (interactive CLI), desktop app, headless `claude -p`.
- **Cells:** exactly one of `test: <test file or S#>` (only when that test's fixture models the row's
  state), `live-owed: <command or steps, in the form that will be executed>`, or `n/a: <reason>`. A
  desktop cell an agent cannot drive is `live-owed`, never `n/a` merely because an agent cannot run it.

**The Live-owed hold.** The item's `session-tracking` note lists every `live-owed` cell on a
`**Live-owed**:` line. Each entry ends `run <date>: <result>` or `deferred: <item id>`; a deferral
names a tracked item, never prose. Before the work-exit `advance`, the orchestrator reads that line.
An entry with neither suffix is open and holds the item in work until it is run or deferred. The
orchestrator writes the line from the spec note's matrix when it first fills `session-tracking`; a
matrix with `live-owed` cells and no `**Live-owed**:` line counts as an open entry. The check sits at
work exit because a `feature-task` skips review by default.
