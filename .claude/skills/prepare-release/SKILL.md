---
description: End-to-end release automation — reads commits since last tag, infers semver bump, drafts changelog, creates release PR, merges it, waits for CI green, tags, and monitors the Docker build to completion.
disable-model-invocation: true
---

# Prepare Release

End-to-end release workflow. Reads commits since the last release tag, infers the semver bump,
drafts a user-facing changelog, confirms with you, creates a release PR, merges it, verifies
CI is green, tags the release, and monitors the Docker build to completion.

**Usage:** `/prepare-release`

Run this from any branch after all feature PRs have been merged to `main`.

---

## Step 1 — Ensure Clean Main

Switch to `main` and pull latest:

```bash
git checkout main
git pull origin main
git status
```

If `git status` shows uncommitted changes, stop and resolve them before continuing. The
working tree must be clean.

---

## Step 2 — Find Last Release Tag

```bash
git describe --tags --abbrev=0
```

Note this as `LAST_TAG` (e.g., `v2.0.2`). All commits after this tag are candidates for
the release.

If this command fails (no tags exist yet), use the full history — note the first commit
hash as the baseline and treat the release as the initial version.

---

## Step 3 — Gather Commits Since Last Tag

```bash
git log <LAST_TAG>...HEAD --oneline
git diff <LAST_TAG>...HEAD --stat
git diff <LAST_TAG>...HEAD --name-only
```

Collect the full output of each. Do not truncate.

---

## Step 4 — Filter and Synthesize (Critical Reasoning Step)

**This is not a raw dump of commit messages.** Analyze the material and produce a curated,
user-facing summary.

### 4a. Discard internal-only commits

Ignore commits that match any of the following:

- Subject starts with: `wip`, `WIP`, `checkpoint`, `fixup!`, `chore: rebase`,
  `Merge branch`, `version bump`, `release:`
- Commit only touches: `build.gradle.kts`, `gradle/`, `*.properties`, `.gitignore`,
  `.github/`, `Dockerfile`, `docker-compose.yml`, `scripts/`, `*.md` files with no API impact
- Subject is a bare file list or CI housekeeping note

### 4b. Group meaningful changes by theme

Use only these categories (omit any that have no entries):

- **Breaking Changes** — removed tool, renamed/removed parameter, incompatible schema change
- **New Features** — new MCP tool, new operation on existing tool, new config option, new query parameter
- **Bug Fixes** — incorrect behavior corrected, crash fixed, data integrity issue resolved
- **Performance** — measurable throughput or latency improvement
- **Documentation** — user-visible docs (only if substantive)

### 4c. Write 3-8 user-facing bullet points

Rules for each bullet:
- Start with a past-tense verb (Added, Fixed, Improved, Removed, Changed)
- Name the tool or feature affected (e.g., `query_items`, `advance_item`)
- Describe the **benefit to the user**, not the implementation detail
- Do not mention internal class names, Kotlin types, or file paths
- Example: `Added \`includeAncestors\` to \`query_items\` — eliminates parent-walk call chains for breadcrumb context`

### 4d. Detect plugin content changes

Check if any files under `claude-plugins/` changed since the last tag:

```bash
git diff <LAST_TAG>...HEAD --name-only -- claude-plugins/
```

If the output is non-empty, plugin content changed. Also check if any server source files
(Kotlin, migrations, Gradle config) changed:

```bash
git diff <LAST_TAG>...HEAD --name-only -- current/src/ current/build.gradle.kts gradle/
```

Classify the release type:
- **server** — server source changed, no plugin changes
- **both** — server source and plugin content both changed
- **plugin-only** — only plugin files changed (skills, hooks, output styles), no server source

**Read the current plugin version from the authoritative source** — do not assume it matches any
git tag. The version in the repository files may have been bumped in a previous release:

```bash
cat claude-plugins/task-orchestrator/.claude-plugin/plugin.json | grep '"version"'
```

Determine the plugin bump level using the same semver rules as the server version, but scoped
to plugin content:

| Condition | Plugin Bump |
|-----------|-------------|
| Breaking change to skill interface, hook behavior, or output style contract | **major** |
| New skill, new hook, new output style added | **minor** |
| Content fixes, wording, skill adjustments, script tweaks | **patch** |

Note the plugin bump level separately from the server bump level — they are independent.
If no plugin files changed, skip plugin versioning entirely (server-only release).

---

## Step 5 — Infer Bump Level

**Plugin-only release:** If the release type is `plugin-only`, skip the server bump entirely.
The server version in `version.properties` stays unchanged. Only the plugin version (determined
in Step 4d) is bumped. Proceed to Step 6 with the server version as-is and the plugin version
as the new value.

**Server or both release:** Examine the synthesized changes and determine the bump level:

| Condition | Bump |
|-----------|------|
| Any breaking API change (removed tool, renamed/removed required parameter, incompatible response schema) | **major** |
| New tool, new capability on existing tool, new config option, new query parameter | **minor** |
| Bug fix, performance improvement, docs only, internal refactor with no API change | **patch** |

If multiple conditions apply, use the highest applicable level.

State the bump level and the one-sentence reason. Example:
> Bump: **minor** — GitHub wiki CI sync and release automation added (new capability, no breaking change).

Calculate the proposed new version:
- **patch**: increment `VERSION_PATCH` by 1, keep others
- **minor**: increment `VERSION_MINOR` by 1, reset `VERSION_PATCH` to 0, keep `VERSION_MAJOR`
- **major**: increment `VERSION_MAJOR` by 1, reset `VERSION_MINOR` and `VERSION_PATCH` to 0

---

## Step 6 — Present for Confirmation

Output the following block and **stop**. Wait for the user to confirm or request changes.

```
## Proposed Release: vX.Y.Z  (CURRENT -> NEW)

**Release type:** <server | both | plugin-only>
**Bump level:** <major | minor | patch>
**Reason:** <one sentence>

**Plugin version:** <CURRENT -> NEW> (<bump level>) — or "No plugin changes"

### Changelog Draft

## [X.Y.Z] - YYYY-MM-DD

### <Added | Changed | Fixed>
- <bullet 1>
- <bullet 2>
...

---
```

Use today's date in `YYYY-MM-DD` format. If the user requests changes, revise and
re-present before continuing.

---

## Step 7 — Create Release Branch

After confirmation:

```bash
git checkout -b release/vX.Y.Z
```

---

## Step 8 — Apply Changes

### 8a. Update `version.properties`

**Plugin-only release:** Skip this step — the server version stays unchanged.

**Server or both release:** Edit `version.properties` in the project root. Set only the
lines that need to change. Reset lower components on a major or minor bump.

Example for a minor bump from 2.0.2 -> 2.1.0:
```
VERSION_MAJOR=2
VERSION_MINOR=1
VERSION_PATCH=0
```

### 8b. Update plugin version files (if plugin content changed)

Skip this step if no plugin files changed in Step 4d.

Read the current plugin version from `claude-plugins/task-orchestrator/.claude-plugin/plugin.json`
(already retrieved in Step 4d). Calculate the new version using the plugin bump level from Step 4d.

Update **both** files with the new version:

1. `claude-plugins/task-orchestrator/.claude-plugin/plugin.json` — update the `version` field
2. `.claude-plugin/marketplace.json` — update `plugins[name="task-orchestrator"].version`

Also update the version table in `claude-plugins/CLAUDE.md`:
- Find the row for `task-orchestrator` and replace the version number

Stage the three files (in addition to version.properties):
```bash
git add claude-plugins/task-orchestrator/.claude-plugin/plugin.json \
        .claude-plugin/marketplace.json \
        claude-plugins/CLAUDE.md
```

### 8c. Insert new section into `CHANGELOG.md`

Read `CHANGELOG.md`. Find the first `## [` versioned entry (after the header). Insert the
new section **immediately above** it, with a trailing `---` separator and a blank line:

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added
- bullet 1
- bullet 2

---

## [previous version] ...
```

Do not modify any existing entries.

If plugin content changed (Step 4d), add under the appropriate section:
- Bumped plugin version to X.Y.Z (<reason>)

### 8d. Stage, commit, and push

**Plugin-only release** (plugin files already staged from 8b):
```bash
git add CHANGELOG.md
git status   # confirm only plugin version files + changelog are staged
git commit -m "release: bump to vX.Y.Z — plugin vA.B.C"
git push origin release/vX.Y.Z
```

**Server-only release:**
```bash
git add version.properties CHANGELOG.md
git status   # confirm only expected files are staged
git commit -m "release: bump to vX.Y.Z"
git push origin release/vX.Y.Z
```

**Both release** (plugin files already staged from 8b):
```bash
git add version.properties CHANGELOG.md
git status   # confirm expected files are staged
git commit -m "release: bump to vX.Y.Z"
git push origin release/vX.Y.Z
```

---

## Step 9 — Pre-PR Checklist

Before creating the PR, verify these README items and fix any that are stale. If fixes are
needed, add `README.md` to the staged files and amend the commit before pushing.

**Docker image references:**

```bash
grep -n "ghcr.io/jpicklyk/task-orchestrator" README.md
```

Every Docker image reference must use `:latest` — never a branch name or hardcoded version tag.

**Version badge** (line ~7 in README):

Both `/github/v/tag/` and `/github/v/release/` badge endpoints work — the CI workflow
creates a git tag and a GitHub release on every deploy. No change needed unless the URL
is broken.

---

## Step 10 — Create the PR

```bash
gh pr create \
  --base main \
  --title "release: vX.Y.Z — <one-line summary of most significant change>" \
  --body "$(cat <<'EOF'
## Summary

- <bullet 1>
- <bullet 2>
- <bullet 3>

## Version

**Release type:** <server | both | plugin-only>
<CURRENT> -> <NEW>

Prepared with /prepare-release
EOF
)"
```

If the branch already has an open PR, use `gh pr edit` with the same title and body instead.

If the user says "show me the command" or "don't run it yet", print the full command as a
code block instead of executing it.

---

## Step 11 — Merge, Verify, Tag, and Monitor

This step automates the post-PR flow. After the PR is created, drive the release
to completion rather than printing manual instructions.

### 11a. Print summary

```
Release prepared: CURRENT -> vX.Y.Z  (<bump level>)
Release type:     <server | both | plugin-only>
Branch:           release/vX.Y.Z
PR:               <URL from gh pr create>
```

### 11b. Merge the release PR

Ask the user: "Ready to merge the release PR?"

If confirmed:
```bash
gh pr merge <PR-number> --squash --delete-branch
```

If the user prefers to merge manually (e.g., via GitHub UI), wait for them to
confirm it's merged before continuing.

### 11c. Sync local main

```bash
git checkout main
git pull origin main
```

Verify the pull is a fast-forward. If it's not (local main has diverged from
origin), stop and investigate — this should not happen under the PR-per-feature
workflow.

### 11d. Wait for CI to pass on main

**Server or both release:** CI must be green before tagging. Use `/loop` to
monitor automatically:

```
/loop 2m gh run list --branch main --limit 1 --json status,conclusion,displayTitle
```

While monitoring, check the first result immediately:
```bash
gh run list --branch main --limit 1 --json status,conclusion,displayTitle
```

- If `conclusion: success` — proceed to 11e immediately, cancel the loop
- If `status: in_progress` — wait for the loop to report completion
- If `conclusion: failure` — **stop and fix**. Do NOT tag. Report the failure
  to the user, investigate the cause, and merge a fix. After fixing, re-check
  CI before tagging. The tag must point to a green commit.

**Plugin-only release:** Skip CI monitoring — no server code changed, no Docker
image to build. Proceed directly to the plugin-only completion in 11g.

### 11e. Tag and push (server or both release)

Once CI is green, cancel the monitoring loop and create the tag:

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

This triggers the "Build, Publish, and Release" workflow (docker-publish.yml).

### 11f. Monitor the Docker build

Use `/loop` to track the docker-publish workflow:

```
/loop 2m gh run list --workflow=docker-publish.yml --limit=1 --json status,conclusion,displayTitle
```

Check immediately:
```bash
gh run list --workflow=docker-publish.yml --limit=1 --json status,conclusion,displayTitle
```

- If `conclusion: success` — cancel the loop, proceed to 11h
- If `status: in_progress` or `queued` — wait for the loop
- If `conclusion: failure` — report the failure, investigate, and help the user
  resolve it. The Docker build may need a re-tag or a fix-and-retag cycle.

### 11g. Plugin-only completion

No tag or Docker rebuild needed — only plugin content changed.
Plugin users pick up the new version when they reinstall the marketplace.

Report:
```
Release complete: vX.Y.Z (plugin-only)
Plugin version:   vA.B.C
```

Skip to 11h.

### 11h. Final report

**Server or both release:**
```
Release complete: vX.Y.Z
Docker image:     ghcr.io/jpicklyk/task-orchestrator:X.Y.Z (and :latest)
GitHub Release:   https://github.com/jpicklyk/task-orchestrator/releases/tag/vX.Y.Z
```

Cancel any remaining monitoring loops.

**IMPORTANT:** Do NOT use `gh workflow run` — the CI workflow is triggered by tag
pushes (`v*`), not manual dispatch.

---

## Quick Reference

| Bump level | When | Version change |
|------------|------|---------------|
| major | Breaking API change | X+1.0.0 |
| minor | New capability, no breaking change | X.Y+1.0 |
| patch | Bug fix, docs, refactor | X.Y.Z+1 |

**Common mistakes to avoid:**
- Do not tag before CI is green on main — tagging a broken commit ships a broken Docker image
- Do not include raw commit hashes or internal file paths in the changelog
- Do not bump version without confirmation from the user
- Do not stage files other than `version.properties`, `CHANGELOG.md`, plugin version files (if changed), and `README.md` (if fixes were needed)
- Do not create the PR if there are no commits ahead of the last tag
- Do not bump plugin versions outside of the release workflow
