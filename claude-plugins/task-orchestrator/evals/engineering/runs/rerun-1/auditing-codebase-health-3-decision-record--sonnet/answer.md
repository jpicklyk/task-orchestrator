I wrote `audit.md` with the three candidates, and I didn't edit any other file. I didn't run anything. The repo is only three source files and has no tests.

1. **`src/labels.js` (Plan it):** Both carriers depend on it, and it has no tests. It also passes through a single address line, so a change there breaks both adapters at once.
2. **`src/carriers/ups.js` (Watch):** It changes in almost every commit and looks a lot like `fedex.js`. `docs/decisions/0002-keep-carrier-adapters-separate.md` says to keep the adapters separate and accept the duplicated lines, so I did not recommend merging them.
3. **`src/carriers/fedex.js` (Watch):** Same reasoning as UPS. It needs tests, not a merge.

Neither adapter is marked "Act now", because the only obvious refactor, merging them, is what that decision record rules out.
