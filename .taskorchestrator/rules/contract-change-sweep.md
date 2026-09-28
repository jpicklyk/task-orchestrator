# contract-change-sweep

A change that tightens a contract breaks call sites and fixtures the changed item never names --
those breakages typically surface on a different, later change's build, not the one that
introduced the tightening. This rule makes finding and construction-only-repairing them a step in
every run, not a reminder that depends on someone remembering it.

## 1. Ownership and timing

- **Orchestrator-owned**, run once after implementation lands for a run and before build
  verification -- never delegated to the implementer, since an implementer is file-scoped and
  cannot see the full fixture surface across the codebase.
- Runs once per run, after every implementing agent in that run has returned, not per file and not
  per commit.

## 2. What counts as tightening

A change that makes previously-valid input or previously-valid test doubles now rejected or
uncompilable:

- A parameter or field moving from optional to required.
- A new validation invariant added to a constructed value.
- A previously-open case in an exhaustive check newly enforced (a switch/match/when gaining a
  case that used to fall through silently, or a sealed-type hierarchy gaining a new arm).
- A new accessor added to an interface that is widely doubled with a strict mock -- every strict
  double that does not already stub the new accessor now fails, even though the item that added
  it never touched those doubles.

## 3. The sweep

For each tightening change identified in the run:

1. Identify the affected type, function, or accessor.
2. Search the whole codebase -- production source **and** test source, not just one tree -- for
   every usage.
3. Check each usage against the new contract. A usage that still satisfies it needs nothing; one
   that doesn't is a casualty.
4. Re-run the full verification suite (not the individual item's own narrow check) to confirm no
   further casualty surfaced elsewhere.

## 4. Repairs are construction-only

- Fix how a casualty is **constructed** to satisfy the new contract: stub the new accessor, derive
  the now-required field from related fields, pass the now-required argument.
- **Never** relax the contract, weaken an assertion to tolerate the old and new shape both, or
  silence/skip a casualty to make the run green.
- Repairs are the orchestrator's own commits, not the implementer's or the test author's --
  declare each repair in the run's shared review record (naming which tightening it answers) so a
  reviewer can tell a declared repair apart from an undeclared edit to someone else's file.
- **A repair that cannot be made construction-only is not a sweep outcome** -- it is a triage case:
  escalate it to arbitration (the same disposition path a red author-written test uses) rather
  than forcing a fix by weakening something.

## claude:

- Search both `src/main` and `src/test` under `current/` for the affected type/tool/accessor,
  e.g. `grep -rn "<type-or-tool-name>"`.
- Concrete tightening triggers seen in this repo: a domain type's `validate()` gaining a new
  invariant; a new accessor added to `RepositoryProvider`, which is widely doubled with strict
  `mockk<...>()` in tests -- every strict double lacking a stub for the new accessor fails.
- **Never** add or widen an `@Disabled` annotation as a repair.
- Adopted from proposals `82034e9a` and `31a1abeb`.
- Declare each repair commit in the wave's Review scoping commit map (implementer commits /
  author commits / declared notes), alongside which tightening it answers.
- This sweep is `/implement` Step 4b's "Contract-change sweep discipline" -- it runs between the
  implementation wave and the orchestrator's build verification, before any child advances to
  review.
