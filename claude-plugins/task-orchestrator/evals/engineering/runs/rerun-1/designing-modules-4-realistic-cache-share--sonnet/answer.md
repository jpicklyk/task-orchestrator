I added `cacheShare(transcriptText)` to `claude-plugins/task-orchestrator/scripts/lib/meters.mjs`, next to `transcriptMeters`. The new tests and the existing `layer-meters` tests all pass (36 in total).

- **What it returns:** `cacheRead / (input + cacheWrite + cacheRead)`, a number from 0 to 1.
- **How it counts:** it reuses `transcriptMeters`, so it counts each `message.id` once, with the last line of an id winning. Output tokens are left out.
- **When it returns `undefined`:** the transcript has no usage, or its input side is all zero. I chose `undefined` for the zero case so you don't get `NaN`.

The tests are in `claude-plugins/task-orchestrator/scripts/tests/cache-share.test.mjs`. They cover a share across two messages, output tokens being ignored, a repeated `message.id`, shares of exactly 0 and 1, empty or garbage input, and a zero input side. I summed each expected value by hand in a comment rather than reading it back from the code.

Nothing is committed.
