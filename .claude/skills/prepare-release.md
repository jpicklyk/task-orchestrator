---
description: Prepare a versioned release from main — reads commits since last tag, detects release type (server, plugin, or both), drafts changelog, creates release branch and PR. After merge, tags the release to automatically trigger the appropriate CI workflow.
---

# Prepare Release

Prepares a release from the current state of `main`. Reads commits since the last release tag,
detects whether this is a server release, plugin-only release, or both, drafts a user-facing
changelog, confirms with you, then creates a release branch, commits the version bump, and
opens a PR. After you merge the PR, tags the release — pushing the tag automatically triggers
the appropriate CI workflow.

**Usage:** `/prepare-release`

Run this from any branch after all feature PRs have been merged to `main`.

---

## Step 1 — Ensure Clean Main

Switch to `main`, pull latest, and clean up stale branches:

```bash
git checkout main
git pull origin main --tags
git fetch --prune
git status
```

If `git status` shows uncommitted changes, stop and resolve them before continuing. The
working tree must be clean.

**Clean up merged branches** — delete local branches whose remote tracking branch is gone
(meaning the PR was merged and the remote branch deleted):
```bash
git branch -vv | grep ': gone]' | awk '{print $1}' | xargs -r git branch -d
```
This uses `-d` (safe delete) — only branches fully merged into HEAD are removed.
Report which branches were cleaned up. If any branches fail to delete (unmerged work),
list them and ask the user whether to force-delete or keep them.

---

## Step 2 — Find Last Release Tags

Find the most recent tags for both release tracks:

```bash
git tag -l 'v*' --sort=-v:refname | head -1
git tag -l 'plugin-v*' --sort=-v:refname | head -1
```

Note these as `LAST_SERVER_TAG` and `LAST_PLUGIN_TAG`. The appropriate one will be used
as the baseline depending on the release type detected in Step 4.

If neither tag exists, use the full history and treat this as the initial release.

---

## Step 3 — Gather Commits Since Last Tag

Use the **more recent** of the two tags as the baseline for the commit log:

```bash
git log <LAST_TAG>...HEAD --oneline
git diff <LAST_TAG>...HEAD --stat
git diff <LAST_TAG>...HEAD --name-only
```

Collect the full output of each. Do not truncate.

---

## Step 4 — Detect Release Type

Examine the changed files from Step 3 and classify:

| Changed paths | Release type |
|---------------|-------------|
| Only `claude-plugins/`, `.claude-plugin/` | **plugin-only** |
| Only `current/`, `gradle/`, `Dockerfile`, `build.gradle.kts`, `src/` | **server** |
| Both plugin and server paths | **both** |

Files that are neutral (`.github/`, `README.md`, `CHANGELOG.md`, `version.properties`,
`.claude/`, `.gitignore`, `docs/`) do not influence the classification — they follow
whichever type the substantive changes belong to. If only neutral files changed, ask the
user which release type to use.

Record the release type — it determines which version files to bump, which tag scheme to
use, and which CI workflow gets triggered.

| Release type | Version files | Tag scheme | CI triggered |
|-------------|---------------|------------|-------------|
| **server** | `version.properties` | `vX.Y.Z` | `docker-publish.yml` (Docker + GitHub Release) |
| **plugin-only** | `plugin.json` + `marketplace.json` | `plugin-vX.Y.Z` | `plugin-release.yml` (GitHub Release only) |
| **both** | All three files | `vX.Y.Z` | `docker-publish.yml` (Docker + GitHub Release) |

For **both** releases: a single `v*` tag triggers the Docker workflow and GitHub Release.
The plugin version bump is included in the same commit so the plugin cache updates when
users re-add the marketplace.

---

## Step 5 — Filter and Synthesize (Critical Reasoning Step)

**This is not a raw dump of commit messages.** Analyze the material and produce a curated,
user-facing summary.

### 5a. Discard internal-only commits

Ignore commits that match any of the following:

- Subject starts with: `wip`, `WIP`, `checkpoint`, `fixup!`, `chore: rebase`,
  `Merge branch`, `version bump`, `release:`
- Commit only touches: `build.gradle.kts`, `gradle/`, `*.properties`, `.gitignore`,
  `.github/`, `Dockerfile`, `docker-compose.yml`, `scripts/`
- Subject is a bare file list or CI housekeeping note

**Plugin-aware filtering:** Do NOT discard commits that only touch `claude-plugins/` files.
Plugin skills, hooks, and output styles are user-facing content. Summarize them as features
or fixes as appropriate.

### 5b. Group meaningful changes by theme

Use only these categories (omit any that have no entries):

- **Breaking Changes** — removed tool, renamed/removed parameter, incompatible schema change,
  removed/renamed skill
- **New Features** — new MCP tool, new operation on existing tool, new config option, new
  query parameter, new skill, new hook, new output style
- **Improvements** — enhanced skill behavior, better prompts, workflow refinements
- **Bug Fixes** — incorrect behavior corrected, crash fixed, data integrity issue resolved
- **Performance** — measurable throughput or latency improvement
- **Documentation** — user-visible docs (only if substantive)

### 5c. Write 3-8 user-facing bullet points

Rules for each bullet:
- Start with a past-tense verb (Added, Fixed, Improved, Removed, Changed)
- Name the tool, skill, or feature affected (e.g., `query_items`, `prepare-release`)
- Describe the **benefit to the user**, not the implementation detail
- Do not mention internal class names, Kotlin types, or file paths
- Example: `Added \`includeAncestors\` to \`query_items\` — eliminates parent-walk call chains for breadcrumb context`
- Example: `Added \`prepare-release\` skill — automates versioned releases with changelog generation`

---

## Step 6 — Infer Bump Level

Examine the synthesized changes and determine the bump level:

| Condition | Bump |
|-----------|------|
| Any breaking API change (removed tool, renamed/removed required parameter, incompatible response schema, removed skill) | **major** |
| New tool, new capability on existing tool, new config option, new query parameter, new skill, new hook | **minor** |
| Bug fix, performance improvement, docs only, internal refactor with no API change, skill content fixes | **patch** |

If multiple conditions apply, use the highest applicable level.

State the bump level and the one-sentence reason. Example:
> Bump: **minor** — new skills and workflow hooks added (new capability, no breaking change).

Calculate the proposed new version based on the **release type**:

- **server** or **both**: bump from current `version.properties` values
- **plugin-only**: bump from current `plugin.json` version
- **both**: bump `version.properties` AND `plugin.json` independently — they may get
  different bump levels if the changes warrant it. Present both.

Version arithmetic:
- **patch**: increment patch by 1, keep others
- **minor**: increment minor by 1, reset patch to 0, keep major
- **major**: increment major by 1, reset minor and patch to 0

---

## Step 7 — Present for Confirmation

Output the following block and **stop**. Wait for the user to confirm or request changes.

```
## Proposed Release: <TAG>  (CURRENT -> NEW)

**Release type:** <server | plugin-only | both>
**Bump level:** <major | minor | patch>
**Reason:** <one sentence>

### Changelog Draft

## [X.Y.Z] - YYYY-MM-DD

### <Added | Changed | Fixed | Improved>
- <bullet 1>
- <bullet 2>
...

---
```

For **both** releases, show both version bumps:
```
**Server version:** CURRENT -> NEW
**Plugin version:** CURRENT -> NEW
```

Use today's date in `YYYY-MM-DD` format. If the user requests changes, revise and
re-present before continuing.

---

## Step 8 — Create Release Branch

After confirmation:

```bash
git checkout -b release/<TAG>
```

Where `<TAG>` is `vX.Y.Z` for server/both releases, or `plugin-vX.Y.Z` for plugin-only.

---

## Step 9 — Apply Changes

### 9a. Update version files

**Server or both releases** — edit `version.properties`:
```
VERSION_MAJOR=X
VERSION_MINOR=Y
VERSION_PATCH=Z
```

**Plugin-only or both releases** — edit `claude-plugins/task-orchestrator/.claude-plugin/plugin.json`
and `.claude-plugin/marketplace.json`:
- Update the `version` field in `plugin.json`
- Update the `plugins[name="task-orchestrator"].version` field in `marketplace.json`
- Both must carry the same version string

**Also update** the version table in `claude-plugins/CLAUDE.md` to reflect the new plugin version.

### 9b. Insert new section into `CHANGELOG.md`

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

For plugin-only releases, prefix the version with "Plugin " in the changelog header:
```markdown
## [Plugin X.Y.Z] - YYYY-MM-DD
```

Do not modify any existing entries.

### 9c. Stage, commit, and push

Stage only the files that changed:

**Server release:**
```bash
git add version.properties CHANGELOG.md
```

**Plugin-only release:**
```bash
git add claude-plugins/task-orchestrator/.claude-plugin/plugin.json \
        .claude-plugin/marketplace.json \
        claude-plugins/CLAUDE.md \
        CHANGELOG.md
```

**Both:**
```bash
git add version.properties \
        claude-plugins/task-orchestrator/.claude-plugin/plugin.json \
        .claude-plugin/marketplace.json \
        claude-plugins/CLAUDE.md \
        CHANGELOG.md
```

Then:
```bash
git status   # confirm only expected files are staged
git commit -m "release: bump to <TAG>"
git push origin release/<TAG>
```

---

## Step 10 — Pre-PR Checklist

Before creating the PR, verify these README items and fix any that are stale. If fixes are
needed, add `README.md` to the staged files and amend the commit before pushing.

**Docker image references:**

```bash
grep -n "ghcr.io/jpicklyk/task-orchestrator" README.md
```

Every Docker image reference must use `:latest` — never a branch name or hardcoded version tag.

**Version badge** (line ~7 in README):

Both `/github/v/tag/` and `/github/v/release/` badge endpoints work — the CI workflow
creates a GitHub release on every deploy. No change needed unless the URL is broken.

---

## Step 11 — Create the PR

```bash
gh pr create \
  --base main \
  --title "release: <TAG> — <one-line summary of most significant change>" \
  --body "$(cat <<'EOF'
## Summary

- <bullet 1>
- <bullet 2>
- <bullet 3>

## Version

**Release type:** <server | plugin-only | both>
<CURRENT> -> <NEW>

Prepared with /prepare-release
EOF
)"
```

If the branch already has an open PR, use `gh pr edit` with the same title and body instead.

If the user says "show me the command" or "don't run it yet", print the full command as a
code block instead of executing it.

---

## Step 12 — Print Summary

Output this block after the PR is created:

```
Release prepared: CURRENT -> <TAG>  (<bump level>)
Release type:     <server | plugin-only | both>
Branch:           release/<TAG>
PR:               <URL from gh pr create>

After you merge the PR, I'll tag the release and CI will handle the rest.
```

**Stop and wait for the user to merge the PR.**

---

## Step 13 — Tag and Release

After the user confirms the PR is merged:

```bash
git checkout main
git pull origin main --tags
```

**Verify CI is green before tagging.** The test workflow runs on every push to `main` —
releasing before it passes risks tagging broken code.

```bash
gh run list --workflow=test.yml --branch=main --limit 1 --json status,conclusion,headSha
```

Check the result:
- If `conclusion` is `"success"` and `headSha` matches `HEAD` on main → proceed to tag
- If `status` is `"in_progress"` or `"queued"` → wait and re-check:
  ```bash
  gh run watch --exit-status
  ```
- If `conclusion` is `"failure"` → **stop**. Do not tag. Inform the user that tests
  failed on main and the release cannot proceed until they pass.

After CI is confirmed green:

```bash
git tag <TAG>
git push origin <TAG>
```

Where `<TAG>` is:
- `vX.Y.Z` for **server** or **both** releases — triggers `docker-publish.yml`
- `plugin-vX.Y.Z` for **plugin-only** releases — triggers `plugin-release.yml`

Confirm the workflow started:

```bash
gh run list --workflow=<workflow-file> --limit 1
```

Output the final status based on release type:

**Server or both:**
```
Tag pushed: vX.Y.Z
CI workflow: https://github.com/jpicklyk/task-orchestrator/actions/workflows/docker-publish.yml

The workflow will:
  ✓ Verify tag matches version.properties
  ✓ Build and push Docker image (amd64 + arm64)
  ✓ Create GitHub Release with auto-generated notes
  ✓ Run Trivy vulnerability scan
```

**Plugin-only:**
```
Tag pushed: plugin-vX.Y.Z
CI workflow: https://github.com/jpicklyk/task-orchestrator/actions/workflows/plugin-release.yml

The workflow will:
  ✓ Verify tag matches plugin.json and marketplace.json
  ✓ Create GitHub Release with auto-generated notes
```

---

## Quick Reference

| Bump level | When | Version change |
|------------|------|---------------|
| major | Breaking API change | X+1.0.0 |
| minor | New capability, no breaking change | X.Y+1.0 |
| patch | Bug fix, docs, refactor | X.Y.Z+1 |

| Release type | Tag scheme | CI workflow | Version files |
|-------------|------------|-------------|--------------|
| server | `vX.Y.Z` | `docker-publish.yml` | `version.properties` |
| plugin-only | `plugin-vX.Y.Z` | `plugin-release.yml` | `plugin.json` + `marketplace.json` |
| both | `vX.Y.Z` | `docker-publish.yml` | All three |

**Common mistakes to avoid:**
- Do not include raw commit hashes or internal file paths in the changelog
- Do not bump version without confirmation from the user
- Do not stage files other than the expected version/changelog files for the release type
- Do not create the PR if there are no commits ahead of the last tag
- Do not use `v*` tags for plugin-only releases — use `plugin-v*`
- For **both** releases, ensure `plugin.json` and `marketplace.json` versions match each other
