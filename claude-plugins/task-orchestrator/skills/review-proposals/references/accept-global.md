# Accept - global

The global Accept path of review-proposals Step 5. The `proposal` note is already ensured ("Before any Accept"). The C2, C4 and C5 sections of the shared GitHub feedback reference define the record-back line, the issue template and the `gh` guard; the Source-trend write-back at the end of Step 5 follows this path.

1. **Ensure the `proposal` note exists** (see Before any Accept), then **ensure a GitHub issue exists.** If Step 2 found a `github-issue:` line, reuse it. Else, if `github_feedback.enabled` is `true` and the `gh` guard (C5) passes, file an issue per the C4 template and append the record-back line (C2) to the proposal note. Else proceed without an issue and say so plainly in the `adoption-decision` note.
2. **Distinguish install type:** `gh repo view <repo> --json viewerPermission -q .viewerPermission`.
   - **ADMIN, MAINTAIN or WRITE: maintainer path.** Upsert `adoption-decision` with `accepted - will implement; tracked in <url>.` and advance `start` twice (the same dwell-in-review pattern as project-scoped acceptance, with the same `complete` caveat). Outcome verification is filled by a later retrospective.
   - **Anything else, or `gh` unavailable: community handoff (the default).** Upsert **both** notes (one `notes_write` call, two `upsert` elements; the first shown):

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "accepted - delegated upstream; tracked in <issue-url>."}}
```

   The second element is `key: "outcome-verification"`, `role: "review"`, body `delegated upstream - verification happens in <repo> issue #<N>, not this workspace. Verdict: n/a (handed off).` Check `data.results[i].ok` for both. Then advance `start`, then `complete`, as two calls: `start` enters work, and `complete` goes straight to terminal because the all-phases note gate passes (`proposal`, `adoption-decision` and `outcome-verification` are filled). The item is now terminal; the GitHub issue is the living tracker.
3. Finish with the **Source-trend write-back**: community handoff (terminal) retires the source trend now; the maintainer path leaves it active until a later retrospective verifies the landed change.

## Troubleshooting

**`gh` unavailable or unauthenticated.** `gh auth status` failed (the C5 guard in the shared reference). For global acceptances proceed without an issue and say so in the note (`accepted - no GitHub issue filed (gh unavailable).`). Filing is best-effort and never blocks the disposition; the user can file the issue later and record the URL on the proposal note.
