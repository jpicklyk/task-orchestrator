I wrote `audit.md` with three ranked candidates. The ranking rests only on file size and commit counts over the last six months. I did not read the code or look at its structure.

- **Rank 1, `hooks/retro-lib.mjs` (Act now):** It is the largest hook source file at 867 lines, with 16 commits in six months.
- **Rank 2, `hooks/config-sync.mjs` (Plan it):** It has 686 lines and 16 commits, and its test file is the largest in the folder at 1,923 lines.
- **Rank 3, `hooks/session-start.mjs` (Watch):** It has 373 lines and 16 commits. It is the third-largest hook and still changing, but not yet unwieldy.

I didn't edit any other file.
