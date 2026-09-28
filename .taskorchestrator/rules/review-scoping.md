# review-scoping

Review a shared-worktree change by OWNED FILES, not by a commit range. In a shared worktree,
commits between one contributor's first and last commit interleave other contributors' work, and a
later fix-up or formatting commit can fall outside any single contributor's naive range entirely --
a commit-range diff both over- and under-reports what that contributor actually wrote.

1. **Diff the base against HEAD, filtered to the files a given contributor owns**, not a commit
   range for that contributor. This is the only diff that reflects exactly what they wrote,
   independent of interleaving from concurrent contributors.
2. **Use the per-contributor commit list only for a narrower check**: that each of their commits
   stayed inside their declared file boundary (for example, an implementation commit touches no
   test files, and a test-authorship commit touches no implementation files). This is a boundary
   check, not the basis for the content review itself.
3. **Record every declared exception** -- arbitrated ambiguities, contract changes made mid-wave,
   declared edits to files another seat normally owns, independence caveats -- in a shared record
   so a reviewer reading owned-file diffs can tell a legitimate declared exception from an
   undeclared boundary breach.
4. **An undeclared edit outside a contributor's owned files is a scope breach**, regardless of
   whether the edit itself was reasonable. The absence of a declaration is what makes the breach
   checkable at all -- don't wave it through because the change looks fine in isolation.

## claude:

- `git -C <worktree> diff <base-sha>..HEAD -- <the reviewed contributor's owned files>` is the
  review diff.
- `git -C <worktree> show --stat <sha>` per commit is the boundary check (implementer commits
  touch no `src/test/**`; test-author commits touch no `src/main/**`).
- The declared-exception record lives in the wave's Review scoping table (or equivalent shared
  note): item, implementer commits, author commits, declared notes (arbitrations, declared
  contract changes, declared edits to existing test files, independence caveats, or "none").
