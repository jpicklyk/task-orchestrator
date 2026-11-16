# arbitration

Applies to an item carrying `needs-test-author` with a separate test author (not Direct tier). When a
test the author wrote comes back red against the implementation, the test author does not decide who
is at fault; it reports (rule `test-author`, sections 8 and 9). The
orchestrator arbitrates, with exactly three dispositions.

1. **Implementation wrong.** Re-dispatch the implementer, scoped to `src/main/**` only.
2. **Test wrong.** A spec citation is required before this disposition is available: the note key,
   the quoted acceptance criterion, and the asserted-versus-observed values. "The implementation
   does X" is evidence for case 1, never for this case. With no covering criterion to cite, case 2
   is not available; fall through to case 3. Before ruling case 2, confirm the failing test's input
   fixture matches the declared input contract (the declarations file or the producing stage's
   schema, for example planner-v1): a fixture-shape mismatch is a fixture fix on the test side that
   keeps the oracle, not a case-2 oracle change. A case-2 fix keeps the S-id's oracle assertion, and
   the re-dispatch prompt names that oracle. Once cited, re-dispatch the test author, scoped to
   `src/test/**` only.
3. **Spec wrong or silent.** Escalate to the user. Amend `task-scope` or `diagnosis` first, then
   dispatch whichever side the amendment implicates.

**The ledger.** Record every arbitration, and every other orchestrator ruling in a fix cycle, in the
child's `orchestrator-confirmation` (an orchestrator-owned note; written under the coordinator token
while the child is a member of an open run). One line per ruling, above the disposition lines:

`A<n>: case <1|2|3> - <the citation, for case 2> - <resolving sha>`

Read the current body first (`notes_read` `get`) and upsert it whole. Never drop or rewrite an
earlier line; mark a reversed one `REVERSED by A<m>` in place. The author's own report of the red
test stays in its `test-manifest`.

**Never weaken, skip or `@Disable` a test to unblock a wave**, whatever the disposition. That is the
failure the trait exists to prevent.

**A red author-authored test is not a broken build.** Route it to arbitration, never to a generic fix
agent. If arbitration has not resolved when build verification must conclude, hold the child in work
and report the unresolved red test; do not force green.
