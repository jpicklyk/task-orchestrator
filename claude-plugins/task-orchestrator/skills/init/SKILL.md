---
name: init
description: "Sets up Task Orchestrator for a project or for the user: registers the MCP entries, creates (or finds) the project anchor root, writes the project: block into .taskorchestrator/config.yaml at the main checkout root, validates and pushes it to the server, and seeds the bundled process rules. In project mode it also writes a project-level client.json when the server is a loopback HTTP server. With --user it creates a personal root and writes the user-level config.yaml and client.json instead. Use when a user says: initialize task orchestrator, task-orchestrator init, set up task orchestrator for this project, set up a personal root, init --user, or when the SessionStart notice says to run /task-orchestrator:init. NOT Claude Code's built-in /init (that writes CLAUDE.md), NOT tutorial onboarding (quick-start), NOT migrating an already-populated unscoped database (adopt-project-scope), and NOT launching or reconfiguring the server (configure-server)."
argument-hint: "[--user] [--no-director] [project name]"
---

# Init - Project and Personal Root Setup

The explicit setup step that the SessionStart notices point at (`/task-orchestrator:init` for a project, `/task-orchestrator:init --user` for a personal root). It registers the MCP entries, creates or finds a root work item, records its id in a `project:` block of the right `config.yaml`, validates and pushes that config to the server, and makes sure the bundled process rules are present for that root.

**Mode:** `--user` in `$ARGUMENTS` selects the personal-root flow. `--no-director` skips the director registration (Step 2). Any other non-flag text is the project (or root) name. No flag means project mode.

Not a director-mode skill: run it outside director mode (`/task-orchestrator:director off`); director-guard denies its file, shell and config steps partway through.

Everything is created through MCP tools; the skill never creates roots over REST. Every call below is a `to-call` block, `{tool, select|op, args}`, in the shapes of [call-shapes.md](../_generated/call-shapes.md).

---

## Step 0 - Resolve paths

Resolve the plugin root: try `${CLAUDE_PLUGIN_ROOT}` (confirm the literal string `${CLAUDE_PLUGIN_ROOT}` did not survive into the command you are about to run); otherwise use the dev-checkout path `<git toplevel>/claude-plugins/task-orchestrator`. Call it `<plugin root>`.

Print the locator's view with one command (forward slashes only, no backslashes anywhere in it, because the Bash tool collapses them):

```bash
node --input-type=module -e "const m = await import('file:///<plugin root>/hooks/config-locator.mjs'); const l = m.locateConfig(); console.log(JSON.stringify({located: l, userHome: m.userHome(), userConfig: m.userConfigPath(), userClient: m.userClientPath(), TASK_ORCHESTRATOR_HOME: process.env.TASK_ORCHESTRATOR_HOME || null, AGENT_CONFIG_DIR: process.env.AGENT_CONFIG_DIR || null}, null, 2))"
```

Use the drive-letter form with forward slashes on Windows (`file:///D:/...`). If the plugin root cannot be resolved, derive the same paths by hand: the user home is `$TASK_ORCHESTRATOR_HOME` when non-empty, else the OS home; the user config is `<home>/.taskorchestrator/config.yaml`; the client file is `<home>/.taskorchestrator/client.json`.

Every later message names files by these resolved paths, never a hard-coded `~`.

## Step 1 - Verify the server

Read the health view (orchestration profile):

```to-call
{"tool": "items_read", "select": "context", "args": {"limit": 5}}
```

If the Task Orchestrator MCP tools are absent or the call errors, stop and point the user to `/task-orchestrator:configure-server`.

Then confirm the server is 4.x: `runs_read` must be among the tools you can call (a 3.x server has no run tools). If it is absent, the server is 3.x or older and this plugin's majors do not match it: stop and point the user to the "Upgrading from 3.x" step of `/task-orchestrator:configure-server`.

## Step 2 - Register the MCP entries

Both modes, once the server answers. Locate the orchestration entry the way `hooks/session-start.mjs` does: `.mcp.json` `mcpServers`, then `~/.claude.json` `mcpServers` and `projects[<dir>].mcpServers`; an entry counts when its key contains `task-orchestrator`. An HTTP entry carries `url` (ending `/mcp`); a stdio entry carries `command` and `args`. Note which file holds it.

**The director entry.** Unless `--no-director` was given, make sure a second entry exists next to the orchestration one. Its key must contain `task-orchestrator` (the plugin's hook matchers key on it), so use the exact key `mcp-task-orchestrator-director` (`claude --agent task-orchestrator:director` looks for that key).

- **stdio, `docker run` entry:** the orchestration entry's `command` and `args`, with `-e MCP_TOOL_PROFILE=director` inserted before the image name.
- **stdio, any other command (JAR, local binary):** copy the entry and set `env: {"MCP_TOOL_PROFILE": "director"}`.
- **HTTP:** the orchestration entry with `url` set to `<base>/mcp/director` (`<base>` is the orchestration URL without its trailing `/mcp`).

Say why it exists: a single developer on one stdio server has no HTTP endpoint for the director tools, and a run-budget or attempt-cap decision is answered through a second stdio entry started with `MCP_TOOL_PROFILE=director` ([fleet deployment](../../../../current/docs/fleet-deployment.md)). That profile serves only read, run-control and decision operations, and no read that returns a note, document or transition-summary body ([API reference, tool profiles](../../../../current/docs/api-reference.md#tool-profiles)). Both stdio processes share the database volume and act as the one `local` principal. On the first 4.0 start over a 3.x volume, follow the "Upgrading from 3.x" step of `/task-orchestrator:configure-server` first and start the orchestration entry alone; a second concurrent first boot waits on the migration lock and can fail the schema update on a large database.

**Existing entries.** Detect a director entry by the exact key `mcp-task-orchestrator-director`. When found, report it and leave it alone, with two checks:

- Its `Authorization` header uses `${TASK_ORCHESTRATOR_API_TOKEN}`: say it shares the agents' credential, and offer via `AskUserQuestion` to switch it to the director placeholder below. Before switching, remind the user that the director token-file entry (configure-server "Director credential") must exist and `TASK_ORCHESTRATOR_DIRECTOR_TOKEN` must be exported before the next session; otherwise the director entry fails `401`.
- A director-profile entry under another key (a `url` ending `/mcp/director`, or `MCP_TOOL_PROFILE=director` in its `args` or `env`): warn that `claude --agent task-orchestrator:director` will not see its tools, and offer to register it under the exact key.

**Credential mode.** The server is in credential mode when an unauthenticated `POST <base>/mcp` answers `401`, or when the orchestration entry already carries an `Authorization` header:

```bash
curl -s -o /dev/null -w "%{http_code}" -X POST <base>/mcp
```

In credential mode each entry carries its own header, written exactly as the placeholders below, because Claude Code expands them from the environment. Never write a token into the file. The orchestration entry keeps the agents' token; the director entry gets a separate placeholder, with no nested default:

```json
"headers": {"Authorization": "Bearer ${TASK_ORCHESTRATOR_API_TOKEN}"}
```

```json
"headers": {"Authorization": "Bearer ${TASK_ORCHESTRATOR_DIRECTOR_TOKEN}"}
```

Tell the user to export both variables before starting Claude Code. The director token needs `read`, `runs` and `decide` and is a different token-file entry from the agents' token; the example token file, the `kind` choice and its hazard are in the "Director credential" block of [`/task-orchestrator:configure-server`](../configure-server/SKILL.md), which this step points to instead of repeating. Both entries load into every session, subagent and headless ralph iteration that reads the file with the variables set, so the separate token separates identity and audit, and authority only where `TASK_ORCHESTRATOR_DIRECTOR_TOKEN` is not exported (ralph, CI, workers). Never put `kind: human` on a token exported into a session that runs agents or subagents; answer human-only classes through REST `POST /api/v1/ops/director/runs_write` with the human token, or a session where only that token is set. Pointing both variables at one token loses the identity and audit split and puts `decide` (and with `kind: human`, the human-only classes) on the agents' own token, reaching every environment that exports it. In an environment that exports only `TASK_ORCHESTRATOR_API_TOKEN`, Claude Code still loads the file; the orchestration entry works, and the director entry shows a missing-variable warning in `claude mcp list` and fails `401` - the intended agent-only setup. A stdio server has no credential mode: its entry is local.

**Who can answer what.** On stdio, and on any unauthenticated or local server, every caller is the `local` agent principal (proof none), so human-only decision classes can never be answered there; director-or-human classes (run budget, attempt cap, deviation) can, through the director entry. A user who wants human sign-off needs bearer (or jwks) with a `kind: human` token.

**Where to write.** Edit `<main>/.mcp.json` only (read-modify-write, preserve everything else). When the orchestration entry lives elsewhere (user scope, `~/.claude.json`), do not edit that file: print the director entry as JSON together with the command for the user to run, for example `claude mcp add-json mcp-task-orchestrator-director '<entry json>' --scope user`. Re-running is idempotent: an existing director entry is left alone and reported.

The entries take effect after the MCP servers reload (a new Claude Code session, or `/mcp`). The end report lists each entry, its file and its mode, and says how to use the director entry once the servers have reloaded: `claude --agent task-orchestrator:director`, or `/task-orchestrator:director` in a normal session.

---

## Project mode (default)

### P1 - Find the main checkout root

The target root is the parent of `git rev-parse --path-format=absolute --git-common-dir` (this resolves to the main checkout even from a linked worktree). Not a git repository: use the current directory. Call it `<main>`.

- **Refuse a home directory.** If `<main>` equals any home (`userHome()`, `os.homedir()`, or `os.userInfo().homedir`), stop: a config directly in a home directory is never a project config. Offer `/task-orchestrator:init --user` instead.
- **Warn on `AGENT_CONFIG_DIR`.** When it is set, its config shadows the one about to be written; say which file wins.

### P2 - Already initialized?

If `<main>/.taskorchestrator/config.yaml` exists with a non-empty `project.rootId`, the project is initialized. Skip to P5 (push), then the P5b `client.json` step, then Rule seeding, so an already-initialized project still gets its `client.json`. Re-running is idempotent.

### P3 - Find or create the anchor

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "project", "depth": 0}}
```

Drop any item tagged `personal-root` from the results.

- **Matches remain** -> `AskUserQuestion`: attach to one of them, create a new anchor, or abort. Attaching uses that item's id as `rootId`.
- **No matches** -> census the depth-0 roots with `items_read` `overview`. If roots exist other than the global containers (Session Retrospectives, Improvement Proposals, Retrospective Trends, `agent-observation` items, `type=project` items), the database already holds unscoped work. Offer via `AskUserQuestion`: hand off to `/task-orchestrator:adopt-project-scope` (it creates the anchor, re-parents the work and writes the config; init then resumes at Rule seeding), create a fresh anchor and leave those roots alone, or abort.
- **Nothing but global containers** -> create a fresh anchor.

Before the create, check that the server declares types at all. An undeclared type is rejected only when the declared set is non-empty; with no types declared the create succeeds schema-free, so the missing process-schema floor would go unnoticed. Read the server layer (`config` `effective` with no `rootId`; skip the check with a note if the `config` tool is not served):

```to-call
{"tool": "config", "op": "effective", "args": {}}
```

If `data.results[0].types` is empty, tell the user the server has no process-schema floor mounted and point them to `/task-orchestrator:configure-server` before going on; create the anchor only if they choose to continue without it (it then has no `project` schema).

Name: from `$ARGUMENTS`, else the main checkout's directory name; confirm with the user. Create it at depth 0. A create takes `type` (see [`items_write` create](../_generated/call-shapes.md#items_write-create)); the global floor declares `project`:

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "<name>", "type": "project", "priority": "low"}}
```

**`schema_violation` on `project`.** When the create fails (call-level `partial_failure`) with `data.results[0].error.code` `schema_violation` and its `detail.availableTypes` does not contain `project` (a custom or older server file), say so and show the list; init does not declare a `project` schema itself. Offer, via `AskUserQuestion`, either `container` (if listed) or adding `project: {lifecycle: permanent, notes: []}` under `work_item_schemas` in the server file (it is imported at boot, so a restart is needed; for stdio a reconnect starts a new process). Never pick a type silently.

### P4 - Write the `project:` block

Write at `<main>/.taskorchestrator/config.yaml`:

```yaml
project:
  rootId: "<anchor-uuid>"
  name: "<name>"
```

Read-modify-write surgically: preserve all other content byte for byte; update an existing `project:` key in place rather than duplicating it. Create the file with only the block when it does not exist.

### P5 - Validate, then push

The `config` tool is orchestration-profile only. Use the file text with CRLF replaced by LF as `configYaml` (at most 131072 bytes). If the `config` tool is not served (a knowledge-profile connection), skip P5 with a note: the local file is authoritative.

1. Head. A root with no config fails with `data.results[0].error.code` `not_found`, which here means "no head".

```to-call
{"tool": "config", "op": "get", "args": {"rootId": "<anchor-uuid>"}}
```

2. Validate (nothing is written). A problem comes back as `valid: false` with `errors[{path, reason}]`, not as a failure; show each `path: reason` and stop, since the local write is saved and the user can fix the file and re-run (or use `/task-orchestrator:manage-schemas`). Relay `warnings`.

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<anchor-uuid>", "configYaml": "<file text, LF>"}}
```

3. Push. Pass `expectedVersion` (the head's `version` from step 1) when a head exists; leave it out when there is none.

```to-call
{"tool": "config", "op": "push", "args": {"rootId": "<anchor-uuid>", "configYaml": "<file text, LF>", "expectedVersion": 1}}
```

Read a failure from `data.results[0].error`; the call-level `error` is only `partial_failure` (no detail, no fix).

- Success: relay `warnings`. Re-pushing identical content appends nothing (`appended: false`).
- `config_invalid`: show `detail.errors[{path, reason}]`; nothing was stored.
- `version_conflict`: someone pushed since step 1, or the document matches an earlier version (`force` re-pushes it). Read the head again (step 1), show what differs and ask before retrying; use `force` only when overwriting is intended (it never skips validation or `expectedVersion`).
- `forbidden`: the credential lacks `write-config`; say so and stop.

### P5b - Project-level `client.json`

So the config-sync and other hooks can reach the REST API without an environment variable. The API URL resolves in this order: `TASK_ORCHESTRATOR_API_URL`, then `apiUrl` in a `client.json` beside the located project config (and, in a linked worktree, the one in the main checkout), then the origin of the orchestrator's http(s) MCP registration (local `~/.claude.json` project entry, then `.mcp.json`, then user `~/.claude.json`; a `.mcp.json` origin must be loopback), then `apiUrl` in the user-level `client.json`; if none resolves the hooks do nothing.

1. Run `apiBaseUrl()` (and, below, `isLoopbackApiUrl()`) from `<plugin root>/hooks/lib/api-client.mjs` with a one-liner like Step 0:
   ```bash
   node --input-type=module -e "const m = await import('file:///<plugin root>/hooks/lib/api-client.mjs'); console.log(JSON.stringify(m.apiBaseUrl({cwd: '<main>'})))"
   ```
   A URL resolves: skip, with one line naming it.
2. Otherwise take the orchestration entry from Step 2 (its URL ends `/mcp`; never the director entry, whose URL ends `/mcp/director`) and derive and health-check `<base>` exactly as U4 steps 2-3. Stdio or an unhealthy server: skip with one line.
3. Check the host with `m.isLoopbackApiUrl("<base>")`. A project-level file is honoured only as a bare loopback origin: an `http`/`https` URL without credentials whose host is `localhost`, an IPv4 address in `127.0.0.0/8`, or `[::1]`, with an optional port and no path, query or fragment (a file inside a repo must not redirect the bearer token to a remote host or choose the request path); anything else is ignored silently. When the check is false, do not write the file: say a project-level file would be ignored for this URL, and that a non-loopback server, or one behind a path prefix, needs `TASK_ORCHESTRATOR_API_URL` or `/task-orchestrator:init --user` (the user-level file is unrestricted).
4. When true, write `<main>/.taskorchestrator/client.json` as UTF-8 JSON containing only `{"apiUrl": "<base>"}`. If the file exists with a different value, show both and ask.
5. Note how the file is found: the hooks read `client.json` beside the located config first. In a linked worktree, when the `client.json` beside the worktree's own config yields no usable loopback URL, the hooks also read `.taskorchestrator/client.json` in the main checkout, under the same loopback rule. `AGENT_CONFIG_DIR` pins both files to the directory it names. One file in the main checkout therefore serves every linked worktree. Advise adding `.taskorchestrator/client.json` to `.gitignore`: the URL is machine-specific.

### P6 - Rule seeding, then advise

Run **Rule seeding** below, then advise committing `.taskorchestrator/config.yaml` (and `.taskorchestrator/rules/` if present). Never commit `client.json`.

Close with one line on the orchestration mode, because a located config turns the plugin's orchestration hooks on by default. The mode is `extensions.claude-code.orchestration.mode` in the config just written (an absent key means `workflow`). Say:

> Orchestration mode: `<mode>`. The plugin now injects its orchestration core at session start, denies `Agent` dispatches that omit `model`, and adds a dispatch hint after `advance`. Set `extensions.claude-code.orchestration.mode` to `schema` (no tiers or model table) or `off` (all three hooks silent) in `.taskorchestrator/config.yaml` to change it.

Do not write the `extensions:` block yourself in project mode: absent already means `workflow`, and init touches this file surgically.

---

## `--user` mode

### U1 - State the target

Say the directory about to be written: `<userHome()>/.taskorchestrator/` (config at `userConfigPath()`, client file at `userClientPath()`).

When `TASK_ORCHESTRATOR_HOME` is set, warn that it replaces the home: the user-level `config.yaml` and `client.json` are read only from `$TASK_ORCHESTRATOR_HOME/.taskorchestrator/`, and `<OS home>/.taskorchestrator/config.yaml` is ignored unless `AGENT_CONFIG_DIR` points at it.

`TASK_ORCHESTRATOR_CEILING` is an opt-in test seam that stops project-config discovery from climbing above that directory; init neither sets it nor needs it.

### U2 - Find or create the personal root

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "project", "depth": 0, "tags": ["personal-root"]}}
```

One match: reuse it. Several: ask which. None (use `$ARGUMENTS` for the title when given; the `schema_violation` fallback of P3 applies here too):

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "Personal", "type": "project", "tags": ["personal-root"], "priority": "low"}}
```

### U3 - Write, validate and push the user-level config

Write the `project:` block (same surgical rule as P4) into `userConfigPath()`, then validate and push it exactly as in P5.

### U3b - Orchestration scope for the personal root

A user-level config counts as "config located" for every directory that has no project config of its own. The orchestration hooks therefore apply **everywhere** once this file exists: the orchestration core is injected at session start, an `Agent` dispatch without `model` is denied, and `advance` gains a dispatch hint, in unrelated repositories too. A project-level config only reaches its own checkout, so this is the one place the reach has to be chosen deliberately.

If the user-level config already sets `extensions.claude-code.orchestration.mode`, keep it and state its mode in one line. Otherwise ask:

```
AskUserQuestion(questions: [{
  question: "A personal root applies Task Orchestrator's orchestration hooks in every directory without its own project config. Which mode should those directories get?",
  header: "Orchestration",
  multiSelect: false,
  options: [
    { label: "Workflow everywhere (default)", description: "Leave the key out. Tier-aware orchestration core, model guard and dispatch hint are active in every unconfigured directory." },
    { label: "Schema everywhere", description: "Write orchestration mode schema. No tiers or model table; the model guard and dispatch hint stay active." },
    { label: "Off outside projects", description: "Write orchestration mode off. All three hooks stay silent in directories that have no project config. The personal root still anchors items and syncs config." }
  ]
}])
```

For the second and third answers, add the key to `userConfigPath()` with the same surgical rule, in block form under `extensions:` and `claude-code:` (the server stores `extensions` without reading it; a top-level `orchestration:` is rejected by a push):

```yaml
extensions:
  claude-code:
    orchestration:
      mode: off   # or schema
```

Write the key before the validate-and-push of U3 so one push covers it; when U3 already ran, push again with the P5 flow. The user-level mode applies only where no project config is located. Inside a project the project's own config is found first and decides alone - its `extensions.claude-code.orchestration.mode`, or `workflow` when it has no such key - so `off` here does not switch orchestration off in initialized projects.

### U4 - `client.json`

`client.json` lets the config-sync and other hooks find the REST API without an environment variable. It holds `apiUrl` only and never a token. The user-level file is unrestricted: unlike the project-level file (P5b) it is honoured for any host.

1. Use the orchestration entry located in Step 2 (its URL ends `/mcp`; never the director entry, whose URL ends `/mcp/director`).
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

**REST path** (an API URL resolves: `TASK_ORCHESTRATOR_API_URL`, a `client.json`, project-level or user-level, or an http(s) MCP registration). Run the hook by hand with stdin closed so it behaves as SessionStart:

```bash
node "<plugin root>/hooks/config-sync.mjs" < /dev/null
```

Project mode: run from the workspace directory. `--user` mode: prefix `AGENT_CONFIG_DIR=<userHome()>` (drive-letter form, forward slashes) so it locates the user-level config. It prints one JSON line with `additionalContext`; relay it. If it reports deferred items, say they will be retried: re-run init or wait for the next session. Always follow with the verification listing below.

**MCP path** (no API URL): list the root's rule documents. Each item carries `slug`, `contentHash` (the rule's `rulesVersion`) and `version`:

```to-call
{"tool": "docs_read", "select": "list", "args": {"rootId": "<root-uuid>", "kind": "rule"}}
```

Then, per manifest key, apply the same policy as `hooks/config-sync.mjs`:

- server `contentHash` equals the manifest's current hash: in sync.
- key absent, or the server hash is one of the manifest's older hashes: upsert it.
- any other hash: leave it (project-authored or a newer plugin) and say so.

Upsert the text of `bundled-rules/<key>.md`. The server normalizes CRLF to LF before it hashes a document body, so a file read on Windows hashes the same as its LF form. Pass `expectedVersion` 0 when the rule is absent (the call must then create) and the listed `version` when it exists. The call needs the `write-docs` capability (`write-config` does not grant it):

```to-call
{"tool": "docs_write", "op": "upsert", "args": {"rootId": "<root-uuid>", "kind": "rule", "slug": "<key>", "body": "<text of bundled-rules/<key>.md>", "expectedVersion": 0}}
```

Compare the returned `contentHash` with the manifest's LAST hash for that key. Mismatch: upsert once more. Still mismatched: report the key and both hashes, and warn that config-sync will treat that body as project-authored and never repair it. `version_conflict` (on `data.results[0].error`) means someone wrote the rule since the listing: list again and re-apply the policy.

Rule documents live under a root: they anchor to the project root that P3 or U2 found or created. Verify (both paths) by listing again with the same `docs_read` call, or with the `config` tool's `rules` op, which returns `{key, rulesVersion, updatedAt}` per rule:

```to-call
{"tool": "config", "op": "rules", "args": {"rootId": "<root-uuid>"}}
```

---

## End report

State: mode; root id and name; each MCP entry (orchestration and director: file and mode, or "not registered: --no-director"); each file written (resolved path); the validate and push result; a rule table (key, action: pushed / in sync / kept / failed); and the next step: reload the MCP servers and start a new session so the setup status changes from not-set-up to configured.
