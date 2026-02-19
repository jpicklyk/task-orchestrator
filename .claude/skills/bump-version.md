---
description: Prepare a release PR by inferring semver bump, drafting a user-facing changelog, and creating the PR via GitHub CLI
---

# Bump Version

Prepares a release PR from the current feature branch to `main`. Reads the commit history,
infers the appropriate semver bump, drafts a user-facing changelog section, confirms with the
user, then updates `version.properties`, `CHANGELOG.md`, and creates the PR.

**Usage:** `/bump-version`

Run this from a feature branch that is ready to merge. The branch must have commits ahead of `main`.

---

## Step 1 — Read Current Version

Read `version.properties` from the project root and extract the three components:

```
Read version.properties
```

Note the current version as `CURRENT = VERSION_MAJOR.VERSION_MINOR.VERSION_PATCH`.

---

## Step 2 — Gather Raw Material

Run these three commands via Bash:

```bash
git log main...HEAD --oneline
```

```bash
git diff main...HEAD --stat
```

```bash
git diff main...HEAD --name-only
```

Collect the full output of each. Do not truncate.

---

## Step 3 — Filter and Synthesize (Critical Reasoning Step)

**This is not a raw dump of commit messages.** Analyze the material and produce a curated,
user-facing summary.

### 3a. Discard internal-only commits

Ignore commits that match any of the following — they carry no user-visible information:

- Subject starts with: `wip`, `WIP`, `checkpoint`, `fixup!`, `chore: rebase`, `Merge branch`, `version bump`
- Commit only touches: `build.gradle.kts`, `gradle/`, `*.properties`, `.gitignore`, `.github/`,
  `Dockerfile`, `docker-compose.yml`, `scripts/`, `*.md` files with no API impact
- Subject is a bare file list or CI housekeeping note

### 3b. Group meaningful changes by theme

Use only these categories (omit any that have no entries):

- **Breaking Changes** — removed tool, renamed/removed parameter, incompatible schema change
- **New Features** — new MCP tool, new operation on existing tool, new config option, new query parameter
- **Bug Fixes** — incorrect behavior corrected, crash fixed, data integrity issue resolved
- **Performance** — measurable throughput or latency improvement
- **Documentation** — user-visible docs, changelog entries (only if substantive)

### 3c. Write 3–8 user-facing bullet points

Rules for each bullet:
- Start with a past-tense verb (Added, Fixed, Improved, Removed, Changed)
- Name the tool or feature affected (e.g., `query_container`, `request_transition`)
- Describe the **benefit to the user**, not the implementation detail
- Do not mention internal class names, Kotlin types, or file paths
- Example: `Added \`includeAncestors\` parameter to \`query_items\` — eliminates parent-walk call chains for breadcrumb context`

---

## Step 4 — Infer Bump Level

Examine the synthesized changes and determine the bump level using this decision tree:

| Condition | Bump |
|-----------|------|
| Any breaking API change (removed tool, renamed/removed required parameter, incompatible response schema) | **major** |
| New tool added, new capability on existing tool, new config option, new query parameter | **minor** |
| Bug fix, performance improvement, docs only, internal refactor with no API change | **patch** |

If multiple conditions apply, use the highest applicable level.

State the bump level and the one-sentence reason. For example:
> Bump: **minor** — `includeAncestors` parameter added to three read tools (new capability, no breaking change).

Calculate the proposed new version:
- **patch**: increment `VERSION_PATCH` by 1, keep others
- **minor**: increment `VERSION_MINOR` by 1, reset `VERSION_PATCH` to 0, keep `VERSION_MAJOR`
- **major**: increment `VERSION_MAJOR` by 1, reset `VERSION_MINOR` and `VERSION_PATCH` to 0

---

## Step 5 — Present for Confirmation

Output the following block and **stop**. Wait for the user to confirm or request changes before
proceeding.

```
## Proposed Release: vX.Y.Z  (CURRENT → NEW)

**Bump level:** <major | minor | patch>
**Reason:** <one sentence>

### Changelog Draft

## [X.Y.Z] - YYYY-MM-DD

### <Added | Changed | Fixed>
- <bullet 1>
- <bullet 2>
...

---
```

Use today's date in `YYYY-MM-DD` format for the changelog header.

If the user requests changes to the wording or bump level, revise and re-present before
continuing.

---

## Step 6 — Apply Changes (After Confirmation)

### 6a. Update `version.properties`

Edit `version.properties` in the project root. Set exactly the lines that need to change.
Reset lower components if performing a major or minor bump.

Example for a minor bump from 2.0.0 → 2.1.0:
```
VERSION_MAJOR=2
VERSION_MINOR=1
VERSION_PATCH=0
```

### 6b. Insert new section into `CHANGELOG.md`

Read `CHANGELOG.md`. Find the line of the most recent `## [` entry (the first versioned section
after the header). Insert the new section **immediately above** that line, including the trailing
`---` separator and a blank line:

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added
- bullet 1
- bullet 2

---

## [previous version] ...
```

Do not modify any existing entries.

### 6c. Stage both files

```bash
git add version.properties CHANGELOG.md
```

Confirm both files appear as staged (green) in `git status`. Do not stage anything else.

---

## Step 7 — Create the PR

Construct and run the following command, substituting the actual version and changelog bullets:

```bash
gh pr create \
  --title "release: vX.Y.Z — <one-line summary of the most significant change>" \
  --body "$(cat <<'EOF'
## Summary

- <bullet 1>
- <bullet 2>
- <bullet 3>

## Version

<CURRENT> → <NEW>

🤖 Prepared with /bump-version
EOF
)"
```

The `--title` one-line summary should capture the most user-significant change from the changelog
(not a generic "bump version" message).

If the branch already has an open PR, run `gh pr edit` with the same title and body instead of
`gh pr create`.

If the user says "show me the command" or "don't run it yet", print the full command as a code
block instead of executing it.

---

## Step 8 — Print Summary

Output a brief summary:

```
Version bumped: CURRENT → NEW  (<bump level>)
Files staged:   version.properties, CHANGELOG.md
PR:             <URL returned by gh pr create, or "not created — command shown above">
```

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
- Do not stage files other than `version.properties` and `CHANGELOG.md`
- Do not create the PR if the branch has no commits ahead of `main`
