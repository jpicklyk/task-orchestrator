---
name: init
description: "Sets up Task Orchestrator for a project or for the user: creates (or finds) the project anchor root, writes the project: block into .taskorchestrator/config.yaml at the main checkout root, pushes it to the server, and seeds the bundled process rules. In project mode it also writes a project-level client.json when the server is a loopback HTTP server. With --user it creates a personal root and writes the user-level config.yaml and client.json instead. Use when a user says: initialize task orchestrator, task-orchestrator init, set up task orchestrator for this project, set up a personal root, init --user, or when the SessionStart notice says to run /task-orchestrator:init. NOT Claude Code's built-in /init (that writes CLAUDE.md), NOT tutorial onboarding (quick-start), NOT migrating an already-populated unscoped database (adopt-project-scope), and NOT launching or reconfiguring the server (configure-server)."
argument-hint: "[--user] [project name]"
---

# Init — Project and Personal Root Setup

The explicit setup step that the SessionStart notices point at (`/task-orchestrator:init` for a project, `/task-orchestrator:init --user` for a personal root). It creates or finds a root work item, records its id in a `project:` block of the right `config.yaml`, pushes that config to the server, and makes sure the bundled process rules are present for that root.

**Mode:** `--user` in `$ARGUMENTS` selects the personal-root flow. Any other non-flag text is the project (or root) name. No flag means project mode.

Everything is created through MCP tools; the skill never creates roots over REST.

---

## Step 0 — Resolve paths

Resolve the plugin root: try `${CLAUDE_PLUGIN_ROOT}` (confirm the literal string `${CLAUDE_PLUGIN_ROOT}` did not survive into the command you are about to run); otherwise use the dev-checkout path `<git toplevel>/claude-plugins/task-orchestrator`. Call it `<plugin root>`.

Print the locator's view with one command (forward slashes only, no backslashes anywhere in it, because the Bash tool collapses them):

```bash
node --input-type=module -e "const m = await import('file:///<plugin root>/hooks/config-locator.mjs'); const l = m.locateConfig(); console.log(JSON.stringify({located: l, userHome: m.userHome(), userConfig: m.userConfigPath(), userClient: m.userClientPath(), TASK_ORCHESTRATOR_HOME: process.env.TASK_ORCHESTRATOR_HOME || null, AGENT_CONFIG_DIR: process.env.AGENT_CONFIG_DIR || null}, null, 2))"
```

Use the drive-letter form with forward slashes on Windows (`file:///D:/...`). If the plugin root cannot be resolved, derive the same paths by hand: the user home is `$TASK_ORCHESTRATOR_HOME` when non-empty, else the OS home; the user config is `<home>/.taskorchestrator/config.yaml`; the client file is `<home>/.taskorchestrator/client.json`.

Every later message names files by these resolved paths, never a hard-coded `~`.

## Step 1 — Verify the server

Call `get_context()`. If the Task Orchestrator MCP tools are absent or the call errors, stop and point the user to `/task-orchestrator:configure-server`.

---

## Project mode (default)

### P1 — Find the main checkout root

The target root is the parent of `git rev-parse --path-format=absolute --git-common-dir` (this resolves to the main checkout even from a linked worktree). Not a git repository: use the current directory. Call it `<main>`.

- **Refuse a home directory.** If `<main>` equals any home (`userHome()`, `os.homedir()`, or `os.userInfo().homedir`), stop: a config directly in a home directory is never a project config. Offer `/task-orchestrator:init --user` instead.
- **Warn on `AGENT_CONFIG_DIR`.** When it is set, its config shadows the one about to be written; say which file wins.

### P2 — Already initialized?

If `<main>/.taskorchestrator/config.yaml` exists with a non-empty `project.rootId`, the project is initialized. Skip to P5 (push), then the P5b `client.json` step, then Rule seeding, so an already-initialized project still gets its `client.json`. Re-running is idempotent.

### P3 — Find or create the anchor

```
query_items(operation="search", type="project", depth=0)
```

Drop any item tagged `personal-root` from the results.

- **Matches remain** -> `AskUserQuestion`: attach to one of them, create a new anchor, or abort. Attaching uses that item's id as `rootId`.
- **No matches** -> census the depth-0 roots with `query_items(operation="overview")`. If roots exist other than the global containers (Session Retrospectives, Improvement Proposals, `agent-observation` items, `type=project` items), the database already holds unscoped work. Offer via `AskUserQuestion`: hand off to `/task-orchestrator:adopt-project-scope` (it creates the anchor, re-parents the work and writes the config; init then resumes at Rule seeding), create a fresh anchor and leave those roots alone, or abort.
- **Nothing but global containers** -> create a fresh anchor.

Name: from `$ARGUMENTS`, else the main checkout's directory name; confirm with the user. Create it at depth 0:

```
manage_items(operation="create", items=[{title: "<name>", type: "project", priority: "low"}])
```

### P4 — Write the `project:` block

Write at `<main>/.taskorchestrator/config.yaml`:

```yaml
project:
  rootId: "<anchor-uuid>"
  name: "<name>"
```

Read-modify-write surgically: preserve all other content byte for byte; update an existing `project:` key in place rather than duplicating it. Create the file with only the block when it does not exist.

### P5 — Push to the server

If a `manage_project_config` tool is available, push the full current file text:

```
manage_project_config(operation="push", rootId="<anchor-uuid>", configYaml="<full file text>")
```

- Success: the returned `fingerprint` confirms it; re-pushing identical content returns the same fingerprint.
- `VALIDATION_ERROR`: show the parse error; the local write is saved, so tell the user to fix the file and re-run (or `/manage-schemas validate`).
- `CONFLICT_ERROR` (superseded): the server holds a newer config. Fetch it with `manage_project_config(operation="get", rootId=...)` and reconcile, or pass `force: true` only if overwriting is intended.
- A `warning` field: relay it (non-fatal).
- Tool absent: note it and continue; the local file is authoritative.

### P5b — Project-level `client.json`

So the config-sync and other hooks can reach the REST API without an environment variable. The API URL resolves in this order: `TASK_ORCHESTRATOR_API_URL`, then `apiUrl` in a `client.json` beside the located project config (and, in a linked worktree, the one in the main checkout), then `apiUrl` in the user-level `client.json`; if none resolves the hooks do nothing.

1. Run `apiBaseUrl()` (and, below, `isLoopbackApiUrl()`) from `<plugin root>/hooks/api-client.mjs` with a one-liner like Step 0:
   ```bash
   node --input-type=module -e "const m = await import('file:///<plugin root>/hooks/api-client.mjs'); console.log(JSON.stringify(m.apiBaseUrl({cwd: '<main>'})))"
   ```
   A URL resolves: skip, with one line naming it.
2. Otherwise find the registered http MCP entry and derive and health-check `<base>` exactly as U4 steps 1-3. Stdio or an unhealthy server: skip with one line.
3. Check the host with `m.isLoopbackApiUrl("<base>")`. A project-level file is honoured only for an `http`/`https` URL without credentials whose host is `localhost`, an IPv4 address in `127.0.0.0/8`, or `[::1]` (a file inside a repo must not redirect the bearer token to a remote host); anything else is ignored silently. When the check is false, do not write the file: say a project-level file would be ignored for this host, and that a non-loopback server needs `TASK_ORCHESTRATOR_API_URL` or `/task-orchestrator:init --user` (the user-level file is unrestricted).
4. When true, write `<main>/.taskorchestrator/client.json` as UTF-8 JSON containing only `{"apiUrl": "<base>"}`. If the file exists with a different value, show both and ask.
5. Note how the file is found: the hooks read `client.json` beside the located config first. In a linked worktree, when the `client.json` beside the worktree's own config yields no usable loopback URL, the hooks also read `.taskorchestrator/client.json` in the main checkout, under the same loopback rule. `AGENT_CONFIG_DIR` pins both files to the directory it names. One file in the main checkout therefore serves every linked worktree. Advise adding `.taskorchestrator/client.json` to `.gitignore`: the URL is machine-specific.

### P6 — Rule seeding, then advise

Run **Rule seeding** below, then advise committing `.taskorchestrator/config.yaml` (and `.taskorchestrator/rules/` if present). Never commit `client.json`.

---

## `--user` mode

### U1 — State the target

Say the directory about to be written: `<userHome()>/.taskorchestrator/` (config at `userConfigPath()`, client file at `userClientPath()`).

When `TASK_ORCHESTRATOR_HOME` is set, warn that it replaces the home: the user-level `config.yaml` and `client.json` are read only from `$TASK_ORCHESTRATOR_HOME/.taskorchestrator/`, and `<OS home>/.taskorchestrator/config.yaml` is ignored unless `AGENT_CONFIG_DIR` points at it.

`TASK_ORCHESTRATOR_CEILING` is an opt-in test seam that stops project-config discovery from climbing above that directory; init neither sets it nor needs it.

### U2 — Find or create the personal root

```
query_items(operation="search", type="project", depth=0, tags="personal-root")
```

One match: reuse it. Several: ask which. None:

```
manage_items(operation="create", items=[{title: "Personal", type: "project", tags: "personal-root", priority: "low"}])
```

(Use `$ARGUMENTS` for the title when given.)

### U3 — Write and push the user-level config

Write the `project:` block (same surgical rule as P4) into `userConfigPath()`, then push it with `manage_project_config` exactly as in P5.

### U4 — `client.json`

`client.json` lets the config-sync and other hooks find the REST API without an environment variable. It holds `apiUrl` only and never a token. The user-level file is unrestricted: unlike the project-level file (P5b) it is honoured for any host.

1. Find the registered Task Orchestrator MCP entry the way `hooks/session-start.mjs` does: `.mcp.json` `mcpServers`, then `~/.claude.json` `mcpServers` and `projects[<dir>].mcpServers`. An `http` entry carries `url`.
2. No http entry (stdio): skip with one line. Config-sync stays a no-op in that case; MCP rule seeding below still works.
3. Otherwise `<base>` = scheme + host + port + path with a trailing `/mcp` removed. Check it:
   ```bash
   curl -s -o /dev/null -w "%{http_code}" <base>/api/v1/health
   ```
   Anything but `200`: do not write; report the code.
4. Write `userClientPath()` as UTF-8 JSON containing only `{"apiUrl": "<base>"}`. If the file exists with a different value, show both and ask.
5. If `TASK_ORCHESTRATOR_API_URL` is set in the environment, note that the environment value wins over the file.

Then run Rule seeding.

---

## Rule seeding (both modes)

The plugin ships these rules in `<plugin root>/bundled-rules/`: `protocol.entry-seat`, `protocol.in-phase-seat`, `protocol.read-only-agent`, `commit-discipline`, `review-scoping`, plus a `manifest.json` mapping each key to the list of hashes ever shipped, current one last. Config-sync's policy per key: absent on the server -> push; a known older hash -> overwrite; any other hash -> never touched.

**REST path** (an API URL resolves: `TASK_ORCHESTRATOR_API_URL`, or a `client.json`, project-level or user-level). Run the hook by hand with stdin closed so it behaves as SessionStart:

```bash
node "<plugin root>/hooks/config-sync.mjs" < /dev/null
```

Project mode: run from the workspace directory. `--user` mode: prefix `AGENT_CONFIG_DIR=<userHome()>` (drive-letter form, forward slashes) so it locates the user-level config. It prints one JSON line with `additionalContext`; relay it. If it reports deferred items, say they will be retried: re-run init or wait for the next session. Always follow with verification:

```
query_rules(operation="list", rootId="<root-uuid>")
```

**MCP path** (no API URL): `query_rules(operation="list", rootId=...)`, then per manifest key apply the same policy:

- server hash equals the manifest's current hash: in sync.
- key absent, or the server hash is one of the manifest's older hashes: stash it.
- any other hash: leave it (project-authored or a newer plugin) and say so.

Stash with the LF text of `bundled-rules/<key>.md` (the server does not normalize CRLF in an inline body):

```
manage_plan_documents(operation="stash", rootId="<root-uuid>", slug="rule/<key>", body="<LF text>")
```

Compare the returned `contentHash` with the manifest's LAST hash for that key. Mismatch: stash once more. Still mismatched: report the key and both hashes, and warn that config-sync will treat that body as project-authored and never repair it.

Rules need a depth-0 root; both modes use one.

---

## End report

State: mode; root id and name; each file written (resolved path); push result; a rule table (key, action: pushed / in sync / kept / failed); and the next step: start a new session so the setup status changes from not-set-up to configured.
