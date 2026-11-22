I wrote `audit.md` at the repo root and changed no other file. To rank the files I compared each non-test hook file's size with how often it has changed since June 2026, then read the code of the top candidates.

1. **`hooks/retro-lib.mjs` — Act now.** At 867 lines it's the largest hook module, and it's among the most-changed. It has one function of about 290 lines (`evaluateScopes`) built from nested inner functions. It also has its own YAML-reading code that duplicates `yaml-lite.mjs`.
2. **`hooks/config-sync.mjs` — Plan it.** It's 686 lines and changes as often as `retro-lib.mjs`. It mixes several jobs: config fingerprinting, rule sync, bundled-rule sync, error formatting, and a `main()` of about 145 lines. Rule sync could be moved into its own module, and its existing test file (about 1,900 lines) would help catch regressions while doing that.
3. **`hooks/session-start.mjs` — Watch.** It changes just as often, but its 373 lines are already split into small functions. For now, just watch whether its version and registration checks keep growing.

Large test files and the generated `lib/tool-catalog.json` weren't candidates, since generated files aren't edited by hand.
