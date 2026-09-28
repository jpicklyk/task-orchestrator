# commit-discipline

A shared feature worktree has ONE index shared by every agent working the wave. Staging into that
index and committing broadly commits whatever anyone else has staged in flight, not just your own
work.

1. **Stage only your own paths.** Add only the files your dispatch assigns you -- never a blanket
   stage of everything changed in the worktree.
2. **Commit by path, never bare.** Commit restricted to exactly the paths you staged, with a
   conventional-commit-style summary line, a one-line rationale, and an attribution trailer as its
   own paragraph. A bare, unscoped commit is never safe in a shared worktree -- it is the one
   commit form to avoid entirely. The attribution trailer is part of the commit form, not an
   optional flourish: a wave whose commits drop it loses per-seat attribution across the whole
   change.
3. **Verify immediately after committing** that the commit's file list is exactly your owned files
   and nothing else. If it is not, undo the commit (keeping the changes), re-stage only your paths,
   and re-commit scoped to them.
4. **A path-scoped commit still includes everything currently in an owned file**, not just the
   hunks you changed -- if a formatter ran across the file, review the file's full diff before
   committing so you don't carry someone else's edit into your commit.
5. **There is no equivalent safety net for staging itself.** Scoping by path at every step is the
   entire mechanism that keeps a shared worktree safe -- there is nothing else protecting you from
   committing another agent's in-flight work.

## claude:

- `git -C <worktree> add <owned paths>`, then
  `git -C <worktree> commit --only -m "<type>(<scope>): <title> [<short-uuid>]" -m "<why>" -m "Co-Authored-By: <model attribution line>" -- <owned paths>`.
  `--only` is the safe form; never a bare `git commit`.
- Verify with `git -C <worktree> show --stat HEAD` -- it must list exactly your owned files.
- On mismatch: `git -C <worktree> reset --soft HEAD~1`, re-stage your paths, re-commit with
  `--only`.
- Read `git -C <worktree> diff -- <owned paths>` before committing if a formatter (e.g. a lint
  auto-fix task) has run, since `--only` scopes by path, not by hunk.
