I reviewed HEAD and wrote six findings to `review.md`, ending in `VERDICT: changes`. I read the diff and checked tool and field names against the call shapes and the Kotlin source. I did not run the tests.

- **Matching is text-only.** A seat's observation counts only if its title or summary happens to quote a run, scope or item id (or a label). A real friction observation with no id in it is now ignored, so a group can be wrongly skipped as low-signal.
- **Generic labels still match.** Run labels of 8 or more characters are matched against every observation, so a generic label like "implementation" brings the old false positives back. The hook README's triage text doesn't mention labels.
- **200-row cap.** The list is capped at 200 rows, oldest first, and any truncated page counts as a signal. In a busy project, enough unrelated observations make every group non-low-signal again.
- **Skill and hook disagree.** `SKILL.md` lists up to 200 observations and keeps the ones naming an in-scope id. It has no paging or truncation handling, so rows after the first 200 are dropped silently. The hook treats truncation as a signal instead.
- **Unrelated rewording in `SKILL.md`.** The commit rewords many lines that have nothing to do with this fix, and some lose meaning. For example, "gate-blocked" became "blocked", which reads like the `blocked` role.
- **Hex matching limits are undocumented.** A mention glued to other letters or digits is rejected, and a 7-character id never matches. The README and skill say "8 hex" but not the glue rule.

The parameters the hook sends (`fields: ["summary"]`, `sort`, `order`, `limit`, `createdAfter`) are all valid for `items_read` list.
