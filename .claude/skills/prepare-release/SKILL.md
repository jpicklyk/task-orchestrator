---
description: Prepare a versioned release — reads commits since last tag, infers semver bump, drafts changelog, creates release branch and PR, then provides the Docker publish trigger command.
disable-model-invocation: true
---

# Prepare Release

Prepares a release from the current state of `main`. Reads commits since the last release tag,
infers the semver bump, drafts a user-facing changelog, confirms with you, then creates a
`release/vX.Y.Z` branch, commits the version bump, and opens a PR. After you merge the PR,
one command triggers the Docker build and GitHub release.

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

### 4c. Write 3–8 user-facing bullet points

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

If the output is non-empty, plugin content changed.

**Read the current plugin version from the authoritative source** — do not assume it matches any
git tag. The version in the repository files may have been bumped in a previous standalone plugin PR
without a corresponding project release tag:

```bash
cat claude-plugins/task-orchestrator/.claude-plugin/plugin.json | grep '"version"'
```

Determine the plugin bump level using the same semver rules as the project version, but scoped
to plugin content:

| Condition | Plugin Bump |
|-----------|-------------|
| Breaking change to skill interface, hook behavior, or output style contract | **major** |
| New skill, new hook, new output style added | **minor** |
| Content fixes, wording, skill adjustments, script tweaks | **patch** |

Note the plugin bump level separately from the project bump level — they are independent.
If no plugin files changed, skip plugin versioning entirely.

**Standalone plugin release:** If plugin content changed but there are no project-level changes
(no new tools, no bug fixes, no API changes), this is a plugin-only release. In this case:
- Skip Steps 5 and 8a (no project version bump needed)
- The release branch and PR are still created, but only contain plugin version files + changelog
- Use commit message: `chore: bump plugin version to X.Y.Z`

---

## Step 5 — Infer Bump Level

Examine the synthesized changes and determine the bump level:

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
## Proposed Release: vX.Y.Z  (CURRENT → NEW)

**Bump level:** <major | minor | patch>
**Reason:** <one sentence>

**Plugin version:** <CURRENT → NEW> (<bump level>) — or "No plugin changes"
**Release type:** project release | plugin-only release

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

Edit `version.properties` in the project root. Set only the lines that need to change.
Reset lower components on a major or minor bump.

Example for a minor bump from 2.0.2 → 2.1.0:
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

```bash
git add version.properties CHANGELOG.md
# Plugin files (if changed — already staged from 8b)
git status   # confirm only expected files are staged
git commit -m "release: bump to vX.Y.Z"
git push origin release/vX.Y.Z
```

**Standalone plugin release** (no project version bump — see Step 4d):

```bash
git add CHANGELOG.md
# Plugin files already staged from 8b
git status   # confirm only plugin + changelog files are staged
git commit -m "chore: bump plugin version to X.Y.Z"
git push origin release/plugin-vX.Y.Z
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

<CURRENT> → <NEW>

🤖 Prepared with /prepare-release
EOF
)"
```

If the branch already has an open PR, use `gh pr edit` with the same title and body instead.

If the user says "show me the command" or "don't run it yet", print the full command as a
code block instead of executing it.

---

## Step 11 — Print Summary and Post-Merge Tag Command

Output this block after the PR is created:

```
Release prepared: CURRENT → vX.Y.Z  (<bump level>)
Branch:           release/vX.Y.Z
PR:               <URL from gh pr create>

After merging the PR, create the release tag to trigger CI:

  git checkout main && git pull origin main
  git tag vX.Y.Z
  git push origin vX.Y.Z

This triggers the "Build, Publish, and Release" workflow (docker-publish.yml)
which builds the Docker image and creates a GitHub Release.

Monitor: https://github.com/jpicklyk/task-orchestrator/actions/workflows/docker-publish.yml
```

**Standalone plugin release** — use a `plugin-v` prefixed tag instead:

```
After merging the PR, create the plugin release tag to trigger CI:

  git checkout main && git pull origin main
  git tag plugin-vX.Y.Z
  git push origin plugin-vX.Y.Z

This triggers the "Plugin Release" workflow (plugin-release.yml)
which verifies version consistency and creates a GitHub Release.

Monitor: https://github.com/jpicklyk/task-orchestrator/actions/workflows/plugin-release.yml
```

**IMPORTANT:** Do NOT use `gh workflow run` — the CI workflows are triggered by tag
pushes (`v*` and `plugin-v*`), not manual dispatch. The tag must be created on main
after the release PR is merged.

---

## Quick Reference

| Bump level | When | Version change |
|------------|------|---------------|
| major | Breaking API change | X+1.0.0 |
| minor | New capability, no breaking change | X.Y+1.0 |
| patch | Bug fix, docs, refactor | X.Y.Z+1 |

**Common mistakes to avoid:**
- Do not include raw commit hashes or internal file paths in the changelog
- Do not bump version without confirmation from the user
- Do not stage files other than `version.properties`, `CHANGELOG.md`, plugin version files (if changed), and `README.md` (if fixes were needed)
- Do not create the PR if there are no commits ahead of the last tag
- Do not bump plugin versions outside of the release workflow
